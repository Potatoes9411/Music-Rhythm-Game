package com.rhythmphysics.core.mechanic.square

import com.rhythmphysics.core.math.MathUtil
import com.rhythmphysics.core.math.Rect
import com.rhythmphysics.core.math.Vec2
import com.rhythmphysics.core.music.EventRole
import com.rhythmphysics.core.music.MusicEvent
import com.rhythmphysics.core.preset.GenerationParams
import com.rhythmphysics.core.rng.SeededRng
import com.rhythmphysics.core.util.StateReader
import com.rhythmphysics.core.util.StateWriter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

enum class BounceAxis { X, Y }

/** One planned contact of the hero square with a generated surface, exactly at a musical event. */
data class PlannedSquareImpact(
    val index: Int,
    val eventId: Long,
    val eventTimeSec: Double,
    /** Hero center at the moment of contact (world units, y up). */
    val position: Vec2,
    val incomingVelocity: Vec2,
    val outgoingVelocity: Vec2,
    val bounceAxis: BounceAxis,
    val surfaceRect: Rect,
    val surfaceNormal: Vec2,
    val note: Int?,
    val velocity: Float?,
    val importance: Float,
    val colorRole: Int,
    val role: EventRole,
    /** True if the planner had to relax constraints for this impact. */
    val degraded: Boolean = false,
    /** Framing center (leash) at this impact; lags the route and drifts in the scroll direction. */
    val leash: Vec2 = Vec2.ZERO,
    /** How long before the contact the surface is shown (scales with local event spacing). */
    val futureSec: Double = 1.6,
    /** How long after the contact the surface remains. */
    val lifeSec: Double = 3.2,
)

/**
 * Plans the Music Square course from future musical event times.
 *
 *   event time -> travel distance -> choose reflection axis -> place a short surface where the
 *   hero will be exactly at that time -> validate -> (backtrack) -> commit
 *
 * The hero moves in straight lines at a constant heading angle; every direction change happens at a
 * musical event against a surface generated for it. Decisions use a per-impact seeded RNG so the
 * plan is identical no matter when (or in how many chunks) it is computed.
 */
class SquareRoutePlanner(
    private val events: List<MusicEvent>,
    private val gen: GenerationParams,
    /** Viewport aspect (w/h); the framed region is shaped to it so 9:16 fills vertically. */
    private val aspect: Float,
    private val seed: Long,
    private val startTime: Double,
    heroSize: Double = 1.0,
    /** Short side of the framed view in world units. */
    val viewShort: Double = 11.5,
) {
    val half = heroSize / 2
    private val thickness = gen.surfaceThickness.coerceIn(0.12, 1.2)
    private val length = gen.surfaceLength.coerceIn(0.8, 8.0)
    private val speed = gen.speed.coerceIn(1.0, 40.0)
    private val futureSec = gen.futureVisibleSec.coerceIn(0.0, 8.0)
    private val lifeSec = gen.surfaceLifetimeSec.coerceIn(0.5, 30.0)
    private val lookahead = gen.lookahead.coerceIn(8, 256)
    private val angle = Math.toRadians(gen.bounceAngleDeg.coerceIn(20.0, 70.0))

    /** Framed world size (w,h). */
    val frameW: Double = if (aspect >= 1f) viewShort * aspect else viewShort
    val frameH: Double = if (aspect >= 1f) viewShort else viewShort / aspect
    private val rx = frameW * (0.46 - 0.10 * gen.compactness.coerceIn(0.0, 1.0))
    private val ry = frameH * (0.46 - 0.10 * gen.compactness.coerceIn(0.0, 1.0))
    /** Long gaps travel slower instead of shooting across the frame. */
    private val maxSegment = 0.62 * minOf(frameW, frameH)
    /** Minimum travel between contacts (dense passages move faster rather than jitter in place). */
    private val minSegment = 1.15 * heroSize

    /** Committed impacts still relevant (rolling window). */
    val impacts = ArrayList<PlannedSquareImpact>()
    /** Index into [events] of the next event to plan. */
    var nextEvent = 0; private set
    private var startPos = Vec2.ZERO
    private var startDir = Vec2(cos(angle), -sin(angle))

    // Hero state after the last committed impact.
    private var lastPos = startPos
    private var lastVel = startDir * initialSpeed()
    private var lastLeash = startPos
    private var lastTime = startTime
    private var lastAxis: BounceAxis? = null

    val totalEvents get() = events.size
    /** Diagnostics: candidate rejections by reason (path-cut, incoming, overlap, pass-through, framing). */
    val rejects = IntArray(5)
    var degradedCount = 0; private set

    /** Scroll direction/speed of the framed region (course keeps generating new space). */
    private val driftVec: Vec2 = when {
        aspect < 0.8f -> Vec2(0.0, -gen.drift)
        aspect > 1.25f -> Vec2(gen.drift, 0.0)
        else -> Vec2(gen.drift * 0.7, -gen.drift * 0.45)
    }
    private val leashTau = 0.8

    private fun advanceLeash(leash: Vec2, target: Vec2, dt: Double): Vec2 {
        val lambda = 1 - kotlin.math.exp(-dt / leashTau)
        return leash + (target - leash) * lambda + driftVec * dt
    }

    /** Framing center at time t (interpolated leash). */
    fun anchor(t: Double): Vec2 {
        if (impacts.isEmpty() || t <= impacts[0].eventTimeSec) {
            val f = impacts.firstOrNull() ?: return startPos + driftVec * (t - startTime)
            return f.leash + driftVec * (t - f.eventTimeSec)
        }
        var lo = 0; var hi = impacts.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (impacts[mid].eventTimeSec <= t) lo = mid else hi = mid - 1 }
        val a = impacts[lo]
        if (lo + 1 < impacts.size) {
            val b = impacts[lo + 1]
            val u = (t - a.eventTimeSec) / (b.eventTimeSec - a.eventTimeSec)
            return a.leash + (b.leash - a.leash) * u
        }
        return a.leash + driftVec * (t - a.eventTimeSec)
    }

    private fun initialSpeed(): Double {
        if (events.isEmpty()) return speed * 0.5
        val dt = (events[0].timeSec - startTime).coerceAtLeast(1e-3)
        return segmentLength(dt) / dt
    }

    private fun dirFor(sx: Double, sy: Double) = Vec2(sx * cos(angle), sy * sin(angle))

    private fun segmentLength(dt: Double): Double = MathUtil.clamp(speed * dt, minSegment, maxSegment)

    // ---- planning ---------------------------------------------------------------------------

    /** Plans ahead so the buffer covers [now] + future visibility + [lookahead] impacts. */
    fun ensure(now: Double) {
        var guard = 0
        while (nextEvent < events.size) {
            val ahead = impacts.count { it.eventTimeSec > now }
            val lastT = impacts.lastOrNull()?.eventTimeSec ?: startTime
            if (ahead >= lookahead && lastT > now + futureSec + 1.0) break
            planNext()
            if (++guard > 4096) break
        }
    }

    /** Drops impacts that can no longer be seen or constrain planning. */
    fun prune(now: Double) {
        val cutoff = now - lifeSec - futureSec - 2.0
        var n = 0
        while (n < impacts.size - 2 && impacts[n].eventTimeSec < cutoff) n++
        if (n > 0) impacts.subList(0, n).clear()
    }

    private class Candidate(
        val axis: BounceAxis, val pos: Vec2, val inVel: Vec2, val outDir: Vec2, val outLen: Double,
        val rect: Rect, val normal: Vec2, val score: Double, val leash: Vec2,
    ) {
        /** Outgoing velocity given the time to the next event. */
        fun outVel(nextDt: Double) = outDir * (outLen / nextDt)
    }

    /** Outgoing speed multipliers the planner may choose at each bounce (breaks lattice-like routes). */
    private val speedFactors = doubleArrayOf(1.0, 0.82, 1.18, 0.68, 1.32, 1.55, 0.5, 0.36)

    /** Leash at the "from" state of the candidate generation (set by callers). */
    private var curLeash = Vec2.ZERO

    private fun candidates(k: Int, fromPos: Vec2, fromVel: Vec2, fromTime: Double, prevAxis: BounceAxis?, fromLeash: Vec2): List<Candidate> {
        curLeash = fromLeash
        val e = events[k]
        val dt = (e.timeSec - fromTime).coerceAtLeast(1e-3)
        val p = fromPos + fromVel * dt
        val rng = SeededRng.forKey(seed, "square.course", e.id)
        val offsetX = rng.range(-0.28, 0.28) * length
        val offsetY = rng.range(-0.28, 0.28) * length
        val noiseX = rng.nextDouble()
        val noiseY = rng.nextDouble()
        val speedNoise = rng.nextDouble()
        val nextDt = if (k + 1 < events.size) (events[k + 1].timeSec - e.timeSec).coerceAtLeast(1e-3) else 1.0
        val out = ArrayList<Candidate>(10)
        val sx = sign(fromVel.x); val sy = sign(fromVel.y)
        val leashK = advanceLeash(curLeash, p, dt)
        val distIn = (fromVel * dt).length
        for (axis in BounceAxis.values()) {
            val normal: Vec2; val newDir: Vec2
            if (axis == BounceAxis.X) { normal = Vec2(-sx, 0.0); newDir = dirFor(-sx, sy) }
            else { normal = Vec2(0.0, -sy); newDir = dirFor(sx, -sy) }
            for ((fi, f) in speedFactors.withIndex()) {
                val len = MathUtil.clamp(speed * f * nextDt, minSegment, maxSegment)
                // Peg length follows the local travel so dense passages get short pegs.
                val pegLen = MathUtil.clamp(0.75 * minOf(distIn, len) + 0.35, 0.9, length)
                // Random slide of the peg along its length; none in dense passages (corridors stay clean).
                val slide = MathUtil.clamp((minOf(distIn, len) - 1.2) / 2.5, 0.0, 1.0) * pegLen / length
                val rect = if (axis == BounceAxis.X)
                    Rect.centered(p.x + sx * (half + thickness / 2), p.y + offsetY * slide, thickness, pegLen)
                else Rect.centered(p.x + offsetX * slide, p.y + sy * (half + thickness / 2), pegLen, thickness)
                val end = p + newDir * len
                val a = leashK + driftVec * nextDt
                val ex = (end.x - a.x) / rx; val ey = (end.y - a.y) / ry
                val framing = sqrt(ex * ex + ey * ey)
                val noise = if (axis == BounceAxis.X) noiseX else noiseY
                val repeatPenalty = if (axis == prevAxis) 0.18 else 0.0
                val speedPenalty = abs(f - 1.0) * 0.6 + (if (fi > 0) 0.12 * ((speedNoise * 7 + fi) % 1.0) else 0.0)
                out += Candidate(axis, p, fromVel, newDir, len, rect, normal, 2.0 * framing + 0.4 * noise + repeatPenalty + speedPenalty, leashK)
            }
        }
        out.sortBy { it.score }
        return out
    }

    private fun valid(c: Candidate, k: Int, fromTime: Double, tentative: List<PlannedSquareImpact>, strictFraming: Boolean): Boolean {
        val e = events[k]
        val (fK, lK) = windowFor(k)
        val sv0 = e.timeSec - fK
        val sv1 = e.timeSec + lK
        val margin = 0.12
        val total = impacts.size + tentative.size
        fun at(i: Int) = if (i < impacts.size) impacts[i] else tentative[i - impacts.size]

        // (a) the new surface must not cut through path segments that are on screen while it is.
        for (j in total - 2 downTo 0) {
            val a = at(j); val b = at(j + 1)
            if (b.eventTimeSec < sv0) break
            if (segmentHitsRect(a.position, b.position, half + margin, c.rect)) { rejects[0]++; return false }
        }
        if (total > 0 && at(0).index == 0 && at(0).eventTimeSec >= sv0 &&
            segmentHitsRect(startPos, at(0).position, half + margin, c.rect)) { rejects[0]++; return false }
        // Incoming segment (ends touching the surface): check its first 80%.
        val inStart = if (total > 0) at(total - 1).position else startPos
        val inEnd = inStart + (c.pos - inStart) * 0.8
        if (segmentHitsRect(inStart, inEnd, half + margin, c.rect)) { rejects[1]++; return false }

        // (b) no overlap with surfaces visible at the same time.
        for (i in total - 1 downTo 0) {
            val o = at(i)
            if (o.eventTimeSec + lifeSec < sv0) break
            if (o.eventTimeSec + o.lifeSec < sv0 || o.eventTimeSec - o.futureSec > sv1) continue
            if (o.surfaceRect.intersects(c.rect, overlapMargin(c.rect, o.surfaceRect))) { rejects[2]++; return false }
        }
        // (c) the outgoing segment must not pass through surfaces visible during it.
        val nextDt = if (k + 1 < events.size) events[k + 1].timeSec - e.timeSec else 1.0
        val end = c.pos + c.outDir * c.outLen
        for (i in total - 1 downTo 0) {
            val o = at(i)
            if (o.eventTimeSec + lifeSec < e.timeSec) break
            if (o.eventTimeSec + o.lifeSec < e.timeSec) continue
            if (segmentHitsRect(c.pos, end, half + 0.05, o.surfaceRect)) { rejects[3]++; return false }
        }
        // (d) stay framed.
        val a = c.leash + driftVec * nextDt
        val ex = (end.x - a.x) / rx; val ey = (end.y - a.y) / ry
        val limit = if (strictFraming) 1.25 else 2.2
        if (ex * ex + ey * ey > limit * limit) { rejects[4]++; return false }
        val ap = c.leash
        val px = (c.pos.x - ap.x) / rx; val py = (c.pos.y - ap.y) / ry
        if (px * px + py * py > (limit + 0.3) * (limit + 0.3)) { rejects[4]++; return false }
        return true
    }

    /** Visibility window for event k: about 4 events ahead and 8 behind, capped by the preset. */
    private fun windowFor(k: Int): Pair<Double, Double> {
        val prev = if (k > 0) events[k].timeSec - events[k - 1].timeSec else 0.6
        val next = if (k + 1 < events.size) events[k + 1].timeSec - events[k].timeSec else prev
        val local = MathUtil.clamp((prev + next) / 2, 0.05, 2.0)
        return MathUtil.clamp(4.0 * local, 0.35, futureSec) to MathUtil.clamp(8.0 * local, 0.8, lifeSec)
    }

    private fun overlapMargin(a: Rect, b: Rect) = MathUtil.clamp(0.12 * (maxOf(a.w, a.h) + maxOf(b.w, b.h)) / 2, 0.08, 0.3)

    private fun nextDtOf(k: Int) = if (k + 1 < events.size) (events[k + 1].timeSec - events[k].timeSec).coerceAtLeast(1e-3) else 1.0

    private fun toImpact(k: Int, c: Candidate, degraded: Boolean): PlannedSquareImpact {
        val e = events[k]
        val colorRole = e.pitchClass ?: (k % 12)
        return PlannedSquareImpact(
            index = k, eventId = e.id, eventTimeSec = e.timeSec, position = c.pos,
            incomingVelocity = c.inVel, outgoingVelocity = c.outVel(nextDtOf(k)),
            bounceAxis = c.axis, surfaceRect = c.rect, surfaceNormal = c.normal,
            note = e.midiNote, velocity = e.velocity, importance = e.importance, colorRole = colorRole,
            role = e.role, degraded = degraded, leash = c.leash,
            futureSec = windowFor(k).first, lifeSec = windowFor(k).second,
        )
    }

    private var budget = 0

    /** Depth-limited DFS: is there a valid continuation of [depth] impacts after the tentative ones? */
    private fun feasible(k: Int, depth: Int, fromPos: Vec2, fromVel: Vec2, fromTime: Double, prevAxis: BounceAxis?, fromLeash: Vec2, tentative: ArrayList<PlannedSquareImpact>): Boolean {
        if (depth == 0 || k >= events.size) return true
        for (c in candidates(k, fromPos, fromVel, fromTime, prevAxis, fromLeash)) {
            if (--budget < 0) return true // bounded work; still deterministic
            if (!valid(c, k, fromTime, tentative, true)) continue
            tentative += toImpact(k, c, false)
            val ok = feasible(k + 1, depth - 1, c.pos, c.outVel(nextDtOf(k)), events[k].timeSec, c.axis, c.leash, tentative)
            tentative.removeAt(tentative.size - 1)
            if (ok) return true
        }
        return false
    }

    private fun planNext() {
        val k = nextEvent
        val cands = candidates(k, lastPos, lastVel, lastTime, lastAxis, lastLeash)
        var chosen: Candidate? = null
        var degraded = false
        val tentative = ArrayList<PlannedSquareImpact>(8)
        budget = 2500
        for (c in cands) {
            if (!valid(c, k, lastTime, tentative, true)) continue
            tentative += toImpact(k, c, false)
            val ok = feasible(k + 1, 5, c.pos, c.outVel(nextDtOf(k)), events[k].timeSec, c.axis, c.leash, tentative)
            tentative.clear()
            if (ok) { chosen = c; break }
        }
        if (chosen == null) chosen = cands.firstOrNull { valid(it, k, lastTime, tentative, true) }
        if (chosen == null) {
            // Relax framing first (the camera follows the hero anyway).
            chosen = cands.firstOrNull { valid(it, k, lastTime, tentative, false) }
            degraded = chosen != null
        }
        if (chosen == null) {
            // Last resort: still bounce exactly on time (musical sync is never sacrificed); the
            // surface may touch an older one. Pick the candidate closest to the framed region.
            chosen = cands.first()
            degraded = true
        }
        if (degraded) degradedCount++
        impacts += toImpact(k, chosen, degraded)
        lastPos = chosen.pos
        lastVel = chosen.outVel(nextDtOf(k))
        lastTime = events[k].timeSec
        lastAxis = chosen.axis
        lastLeash = chosen.leash
        nextEvent++
    }

    // ---- evaluation --------------------------------------------------------------------------

    /** Hero center at time t (piecewise linear between planned contacts). */
    fun positionAt(tRaw: Double): Vec2 {
        val t = maxOf(tRaw, startTime) // before the route starts the hero waits at its start position
        if (impacts.isEmpty()) return startPos + startDir * (speed * 0.5 * (t - startTime))
        val f = impacts[0]
        if (t <= f.eventTimeSec) {
            return if (f.index == 0 && f.eventTimeSec > startTime) {
                startPos + (f.position - startPos) * ((t - startTime) / (f.eventTimeSec - startTime))
            } else f.position + f.incomingVelocity * (t - f.eventTimeSec)
        }
        var lo = 0; var hi = impacts.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (impacts[mid].eventTimeSec <= t) lo = mid else hi = mid - 1 }
        val a = impacts[lo]
        if (lo + 1 < impacts.size) {
            val b = impacts[lo + 1]
            val frac = (t - a.eventTimeSec) / (b.eventTimeSec - a.eventTimeSec)
            return a.position + (b.position - a.position) * frac
        }
        // After the last planned impact (end of song): keep moving but slow down.
        val dtAfter = t - a.eventTimeSec
        val travel = a.outgoingVelocity.length * (1 - kotlin.math.exp(-dtAfter)) // eases to a stop
        return a.position + a.outgoingVelocity.normalized() * travel
    }

    /** Most recent impact at or before t, if any. */
    fun impactAtOrBefore(t: Double): PlannedSquareImpact? {
        var r: PlannedSquareImpact? = null
        for (i in impacts) { if (i.eventTimeSec <= t) r = i else break }
        return r
    }

    // ---- checkpoint --------------------------------------------------------------------------

    fun write(w: StateWriter) {
        w.i(nextEvent); w.i(degradedCount)
        w.d(lastPos.x); w.d(lastPos.y); w.d(lastVel.x); w.d(lastVel.y); w.d(lastTime)
        w.i(lastAxis?.ordinal ?: -1)
        w.d(lastLeash.x); w.d(lastLeash.y)
        w.i(impacts.size)
        for (p in impacts) {
            w.i(p.index); w.l(p.eventId); w.d(p.eventTimeSec); w.d(p.position.x); w.d(p.position.y)
            w.d(p.incomingVelocity.x); w.d(p.incomingVelocity.y); w.d(p.outgoingVelocity.x); w.d(p.outgoingVelocity.y)
            w.i(p.bounceAxis.ordinal); w.d(p.surfaceRect.x); w.d(p.surfaceRect.y); w.d(p.surfaceRect.w); w.d(p.surfaceRect.h)
            w.d(p.surfaceNormal.x); w.d(p.surfaceNormal.y); w.i(p.note ?: -1); w.f(p.velocity ?: -1f); w.f(p.importance)
            w.i(p.colorRole); w.i(p.role.ordinal); w.b(p.degraded); w.d(p.leash.x); w.d(p.leash.y); w.d(p.futureSec); w.d(p.lifeSec)
        }
    }

    fun read(r: StateReader) {
        nextEvent = r.i(); degradedCount = r.i()
        lastPos = Vec2(r.d(), r.d()); lastVel = Vec2(r.d(), r.d()); lastTime = r.d()
        lastAxis = r.i().let { if (it < 0) null else BounceAxis.values()[it] }
        lastLeash = Vec2(r.d(), r.d())
        impacts.clear()
        repeat(r.i()) {
            val index = r.i(); val id = r.l(); val t = r.d(); val pos = Vec2(r.d(), r.d())
            val inV = Vec2(r.d(), r.d()); val outV = Vec2(r.d(), r.d())
            val axis = BounceAxis.values()[r.i()]; val rect = Rect(r.d(), r.d(), r.d(), r.d())
            val normal = Vec2(r.d(), r.d()); val note = r.i().let { if (it < 0) null else it }
            val vel = r.f().let { if (it < 0f) null else it }; val imp = r.f(); val cr = r.i()
            val role = EventRole.values()[r.i()]; val deg = r.b(); val leash = Vec2(r.d(), r.d()); val fs = r.d(); val ls = r.d()
            impacts += PlannedSquareImpact(index, id, t, pos, inV, outV, axis, rect, normal, note, vel, imp, cr, role, deg, leash, fs, ls)
        }
    }

    companion object {
        /** Liang-Barsky: does segment a->b, inflated by [inflate], intersect [r]? */
        fun segmentHitsRect(a: Vec2, b: Vec2, inflate: Double, r: Rect): Boolean {
            val x0 = r.x - inflate; val x1 = r.right + inflate
            val y0 = r.y - inflate; val y1 = r.top + inflate
            val dx = b.x - a.x; val dy = b.y - a.y
            var t0 = 0.0; var t1 = 1.0
            val p = doubleArrayOf(-dx, dx, -dy, dy)
            val q = doubleArrayOf(a.x - x0, x1 - a.x, a.y - y0, y1 - a.y)
            for (i in 0 until 4) {
                if (abs(p[i]) < 1e-12) {
                    if (q[i] < 0) return false
                } else {
                    val t = q[i] / p[i]
                    if (p[i] < 0) { if (t > t1) return false; if (t > t0) t0 = t }
                    else { if (t < t0) return false; if (t < t1) t1 = t }
                }
            }
            return t0 <= t1
        }
    }
}
