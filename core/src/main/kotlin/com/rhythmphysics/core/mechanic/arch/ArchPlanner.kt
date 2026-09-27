package com.rhythmphysics.core.mechanic.arch

import com.rhythmphysics.core.math.MathUtil
import com.rhythmphysics.core.math.Vec3
import com.rhythmphysics.core.music.EventRole
import com.rhythmphysics.core.music.MusicEvent
import com.rhythmphysics.core.preset.GenerationParams
import com.rhythmphysics.core.rng.SeededRng
import com.rhythmphysics.core.util.StateReader
import com.rhythmphysics.core.util.StateWriter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

enum class ArchStyle { BOUNCE_CURVE, PILLAR_WEAVE }

/** A contact target placed at a musical event. For pillars [height] is the top; discs sit on the floor. */
data class ArchTarget(
    val index: Int, val eventId: Long, val timeSec: Double,
    val x: Double, val z: Double, val topY: Double, val radius: Double,
    val colorRole: Int, val role: EventRole, val importance: Float, val note: Int?,
)

/**
 * Motion between two contacts. Ballistic segments are real projectile motion under gravity [g]
 * (solved so the hero lands exactly at the next event). Guided segments (Pillar Weave) are an
 * intentional swooping curve and are labelled as such — not claimed to be free physics.
 */
data class ArchSegment(
    val t0: Double, val t1: Double, val p0: Vec3, val p1: Vec3,
    val v0: Vec3, val g: Double, val guided: Boolean, val dip: Double, val bulge: Double, val apexY: Double,
) {
    fun at(t: Double, lateral: Vec3): Vec3 {
        val tt = MathUtil.clamp(t - t0, 0.0, t1 - t0)
        if (!guided) return Vec3(p0.x + v0.x * tt, p0.y + v0.y * tt - 0.5 * g * tt * tt, p0.z + v0.z * tt)
        val u = tt / (t1 - t0)
        val e = u * u * (3 - 2 * u) * 0.65 + u * 0.35 // eased so it lingers near the tops
        val s = sin(PI * u)
        return Vec3(
            p0.x + (p1.x - p0.x) * e + lateral.x * bulge * s,
            p0.y + (p1.y - p0.y) * e - dip * s,
            p0.z + (p1.z - p0.z) * e + lateral.z * bulge * s,
        )
    }
}

/**
 * Plans Arch contacts from musical events (rolling buffer, deterministic, per-event seeded RNG).
 * Validation rejects unreasonable apexes, excessive speeds, overlapping targets and courses that
 * leave the corridor the camera frames; alternatives are regenerated before committing.
 */
class ArchPlanner(
    private val events: List<MusicEvent>,
    private val gen: GenerationParams,
    val style: ArchStyle,
    private val aspect: Float,
    private val seed: Long,
    private val startTime: Double,
) {
    /** Course axes: portrait/square progress away from the camera, landscape progresses sideways. */
    val forward: Vec3 = if (aspect > 1.25f) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 0.0, -1.0)
    val lateral: Vec3 = if (aspect > 1.25f) Vec3(0.0, 0.0, 1.0) else Vec3(1.0, 0.0, 0.0)
    val heroRadius = 0.2
    private val discRadius = 0.62
    private val gMin = 5.0
    private val gMax = 95.0
    private val speed = gen.speed.coerceIn(1.0, 20.0)
    private val corridor = gen.corridorWidth.coerceIn(3.0, 30.0) * (if (aspect > 1.25f) 0.35 else 0.5)

    val targets = ArrayList<ArchTarget>()
    val segments = ArrayList<ArchSegment>()
    var nextEvent = 0; private set
    private var progress = 0.0     // along forward
    private var lastLat = 0.0
    private var lastTop = 0.0
    private var lastApex = gen.apexMax
    private var lastSide = 1.0
    val totalEvents get() = events.size
    var rejectedCandidates = 0; private set

    /** Start pad (index -1) where the hero waits before the first contact. */
    val startTarget = ArchTarget(-1, -1, startTime, 0.0, 0.0, if (style == ArchStyle.PILLAR_WEAVE) gen.pillarMinHeight else 0.1,
        if (style == ArchStyle.PILLAR_WEAVE) 0.42 else discRadius, 0, EventRole.MAJOR, 1f, null)

    private fun pos(progress: Double, lat: Double, y: Double) = Vec3(
        forward.x * progress + lateral.x * lat, y, forward.z * progress + lateral.z * lat,
    )

    private fun pillarHeight(e: MusicEvent): Double {
        val lo = gen.pillarMinHeight; val hi = maxOf(lo + 0.2, gen.pillarMaxHeight)
        val pitchNorm = e.midiNote?.let { ((it - 48) / 36.0) } ?: (e.spectralCentroid?.let { (it - 400.0) / 3000.0 } ?: 0.5)
        return MathUtil.lerp(lo, hi, MathUtil.clamp(0.8 * pitchNorm + 0.2 * e.importance, 0.0, 1.0))
    }

    fun ensure(now: Double) {
        var guard = 0
        while (nextEvent < events.size) {
            val ahead = targets.count { it.timeSec > now }
            if (ahead >= 24 && (targets.lastOrNull()?.timeSec ?: 0.0) > now + 4.0) break
            planNext()
            if (++guard > 2048) break
        }
    }

    fun prune(now: Double) {
        val cutoff = now - 4.0
        var n = 0
        while (n < targets.size - 3 && targets[n].timeSec < cutoff) n++
        if (n > 0) { targets.subList(0, n).clear(); segments.subList(0, minOf(n, segments.size)).clear() }
    }

    private fun planNext() {
        val k = nextEvent
        val e = events[k]
        val prev = targets.lastOrNull() ?: startTarget
        val t0 = prev.timeSec
        val T = (e.timeSec - t0).coerceAtLeast(0.05)
        val rng = SeededRng.forKey(seed, "arch.course", e.id)
        val jitter = rng.range(-1.0, 1.0)
        val baseDist = MathUtil.clamp(speed * T, 1.25 * 2 * discRadius, 7.5)
        val sway = corridor * 0.55 * sin((e.timeSec - startTime) * 0.45) + corridor * 0.25 * jitter
        // Apex: resets on strong beats, decays through the phrase ("decaying parabolic bounces").
        val apex = if (e.role == EventRole.MAJOR || e.isDownbeat) gen.apexMax * (0.65 + 0.35 * e.importance)
        else maxOf(gen.apexMin, lastApex * gen.apexDecay)
        val side = -lastSide

        data class Cand(val dist: Double, val lat: Double)
        val cands = ArrayList<Cand>()
        for (f in doubleArrayOf(1.0, 0.8, 1.25, 0.65, 1.5)) {
            val lat = if (style == ArchStyle.PILLAR_WEAVE) side * (0.9 + 0.35 * abs(jitter)) + 0.3 * sway else sway
            cands += Cand(baseDist * f, lat)
            cands += Cand(baseDist * f, lat * 0.5)
        }
        var chosen: Pair<ArchTarget, ArchSegment>? = null
        for (c in cands) {
            val t = buildTarget(k, e, c.dist, c.lat)
            val seg = buildSegment(prev, t, apex) ?: run { rejectedCandidates++; null } ?: continue
            if (!valid(t, seg)) { rejectedCandidates++; continue }
            chosen = t to seg
            break
        }
        if (chosen == null) {
            // Fallback keeps musical timing: nearest spacing that solves, apex clamped.
            val t = buildTarget(k, e, baseDist, sway * 0.3)
            chosen = t to (buildSegment(prev, t, apex) ?: buildSegment(prev, t, gen.apexMin)!!)
        }
        val (target, seg) = chosen
        targets += target; segments += seg
        progress = progressOf(target)
        lastLat = latOf(target)
        lastTop = target.topY
        lastApex = seg.apexY - maxOf(seg.p0.y, seg.p1.y)
        lastSide = if (style == ArchStyle.PILLAR_WEAVE) side else lastSide
        nextEvent++
    }

    private fun progressOf(t: ArchTarget) = t.x * forward.x + t.z * forward.z
    private fun latOf(t: ArchTarget) = t.x * lateral.x + t.z * lateral.z

    private fun buildTarget(k: Int, e: MusicEvent, dist: Double, lat: Double): ArchTarget {
        val p = pos(progress + dist, lat, 0.0)
        val top = if (style == ArchStyle.PILLAR_WEAVE) pillarHeight(e) else 0.1
        val r = if (style == ArchStyle.PILLAR_WEAVE) 0.34 + 0.16 * e.importance else discRadius * (0.85 + 0.3 * e.importance)
        return ArchTarget(k, e.id, e.timeSec, p.x, p.z, top, r, e.pitchClass ?: k % 12, e.role, e.importance, e.midiNote)
    }

    /** Solves the segment; null if no reasonable solution. */
    private fun buildSegment(from: ArchTarget, to: ArchTarget, apexAbove: Double): ArchSegment? {
        val p0 = Vec3(from.x, from.topY + heroRadius, from.z)
        val p1 = Vec3(to.x, to.topY + heroRadius, to.z)
        val T = to.timeSec - from.timeSec
        if (T <= 0.0) return null
        if (style == ArchStyle.PILLAR_WEAVE) {
            val horiz = hypot(p1.x - p0.x, p1.z - p0.z)
            val dip = MathUtil.clamp(0.35 * minOf(p0.y, p1.y), 0.2, 1.6) * MathUtil.clamp(T / 0.5, 0.5, 1.3)
            val bulge = MathUtil.clamp(horiz * 0.18, 0.2, 1.1) * (if (to.index % 2 == 0) 1 else -1)
            return ArchSegment(from.timeSec, to.timeSec, p0, p1, Vec3.ZERO, 0.0, true, dip, bulge, maxOf(p0.y, p1.y))
        }
        // Ballistic: apex H above the higher endpoint; solve g and vertical launch speed.
        var h = MathUtil.clamp(apexAbove, 0.05, 30.0)
        val a0 = maxOf(p0.y, p1.y) + h - p0.y
        val b = p1.y - p0.y
        var q = (sqrt(2 * a0) + sqrt(2 * (a0 - b))) / T
        var g = q * q
        if (g > gMax || g < gMin) {
            // Keep gravity physical-looking; the apex adapts instead (h = g T^2 / 8 for level pads).
            g = MathUtil.clamp(g, gMin, gMax)
            h = g * T * T / 8.0
            val a1 = maxOf(p0.y, p1.y) + h - p0.y
            q = (sqrt(2 * a1) + sqrt(2 * (a1 - b))) / T
            g = q * q
        }
        val vy = (p1.y - p0.y + 0.5 * g * T * T) / T
        // v0 = (p1 - p0 - 0.5 g T^2) / T with g pointing down
        val v0 = Vec3((p1.x - p0.x) / T, vy, (p1.z - p0.z) / T)
        val apexY = p0.y + vy * vy / (2 * g)
        return ArchSegment(from.timeSec, to.timeSec, p0, p1, v0, g, false, 0.0, 0.0, apexY)
    }

    private fun valid(t: ArchTarget, seg: ArchSegment): Boolean {
        // Target overlap with recent targets.
        for (i in maxOf(0, targets.size - 8) until targets.size) {
            val o = targets[i]
            if (hypot(o.x - t.x, o.z - t.z) < (o.radius + t.radius) * 1.15) return false
        }
        // Corridor (what the camera director frames).
        if (abs(latOf(t)) > corridor) return false
        if (!seg.guided) {
            val horizSpeed = hypot(seg.v0.x, seg.v0.z)
            if (horizSpeed > 16.0) return false
            val apexAbove = seg.apexY - maxOf(seg.p0.y, seg.p1.y)
            if (apexAbove > gen.apexMax * 1.6 || apexAbove < 0.05) return false
        } else {
            // Guided swoop must not pass through other pillars.
            for (s in 1..9) {
                val p = seg.at(seg.t0 + (seg.t1 - seg.t0) * s / 10.0, lateral)
                for (i in maxOf(0, targets.size - 6) until targets.size) {
                    val o = targets[i]
                    if (o.index == t.index) continue
                    if (p.y < o.topY + heroRadius && hypot(p.x - o.x, p.z - o.z) < o.radius + heroRadius + 0.05) return false
                }
            }
        }
        return true
    }

    // ---- evaluation --------------------------------------------------------------------------

    fun heroAt(t: Double): Vec3 {
        if (segments.isEmpty()) return Vec3(startTarget.x, startTarget.topY + heroRadius, startTarget.z)
        if (t <= segments[0].t0) return segments[0].p0
        var lo = 0; var hi = segments.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (segments[mid].t0 <= t) lo = mid else hi = mid - 1 }
        val s = segments[lo]
        if (t <= s.t1) return s.at(t, lateral)
        // after the last contact: settle on the last target
        return s.p1
    }

    fun segmentAt(t: Double): ArchSegment? {
        var r: ArchSegment? = null
        for (s in segments) { if (s.t0 <= t) r = s else break }
        return r
    }

    fun targetsAfter(t: Double, n: Int): List<ArchTarget> {
        val out = ArrayList<ArchTarget>(n)
        for (x in targets) if (x.timeSec > t) { out += x; if (out.size == n) break }
        return out
    }

    fun lastTargetAtOrBefore(t: Double): ArchTarget? {
        var r: ArchTarget? = null
        for (x in targets) { if (x.timeSec <= t) r = x else break }
        return r
    }

    // ---- checkpoint --------------------------------------------------------------------------

    fun write(w: StateWriter) {
        w.i(nextEvent); w.d(progress); w.d(lastLat); w.d(lastTop); w.d(lastApex); w.d(lastSide); w.i(rejectedCandidates)
        w.i(targets.size)
        for (t in targets) {
            w.i(t.index); w.l(t.eventId); w.d(t.timeSec); w.d(t.x); w.d(t.z); w.d(t.topY); w.d(t.radius)
            w.i(t.colorRole); w.i(t.role.ordinal); w.f(t.importance); w.i(t.note ?: -1)
        }
        w.i(segments.size)
        for (s in segments) {
            w.d(s.t0); w.d(s.t1); for (v in listOf(s.p0, s.p1, s.v0)) { w.d(v.x); w.d(v.y); w.d(v.z) }
            w.d(s.g); w.b(s.guided); w.d(s.dip); w.d(s.bulge); w.d(s.apexY)
        }
    }

    fun read(r: StateReader) {
        nextEvent = r.i(); progress = r.d(); lastLat = r.d(); lastTop = r.d(); lastApex = r.d(); lastSide = r.d(); rejectedCandidates = r.i()
        targets.clear()
        repeat(r.i()) {
            targets += ArchTarget(r.i(), r.l(), r.d(), r.d(), r.d(), r.d(), r.d(), r.i(), EventRole.values()[r.i()], r.f(), r.i().let { if (it < 0) null else it })
        }
        segments.clear()
        repeat(r.i()) {
            val t0 = r.d(); val t1 = r.d()
            val p0 = Vec3(r.d(), r.d(), r.d()); val p1 = Vec3(r.d(), r.d(), r.d()); val v0 = Vec3(r.d(), r.d(), r.d())
            segments += ArchSegment(t0, t1, p0, p1, v0, r.d(), r.b(), r.d(), r.d(), r.d())
        }
    }
}
