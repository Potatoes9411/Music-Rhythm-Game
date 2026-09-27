package com.rhythmphysics.core.mechanic.platform

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

enum class PlatformStyle { MUSIC_BALL, PIANO_TILES, STAIRCASE, MINIMAL_BARS, NEON, BLOCK_TERRAIN;
    companion object {
        fun of(s: String) = when (s) {
            "piano_tiles" -> PIANO_TILES; "staircase" -> STAIRCASE; "minimal_bars" -> MINIMAL_BARS
            "neon" -> NEON; "block_terrain" -> BLOCK_TERRAIN; else -> MUSIC_BALL
        }
    }
}

/** A planned contact: the ball touches [platform]'s top at [position] exactly at [timeSec]. */
data class PlatformContact(
    val index: Int, val eventId: Long, val timeSec: Double,
    val position: Vec2, val launch: Vec2, val platform: Rect,
    val colorRole: Int, val importance: Float, val note: Int?, val role: EventRole,
)

/**
 * Generates a physical course from musical events (never random geometry that hopes to be on
 * beat): for each next event, choose a bounce energy (from importance) and horizontal speed, solve
 * where the ball will be at the event time under constant gravity, validate, and place a platform
 * there. Dense passages get small hops (rapid-note structures); very dense events are thinned by
 * [com.rhythmphysics.core.music.EventMapper] into visual-only effects.
 */
class PlatformCoursePlanner(
    private val events: List<MusicEvent>,
    private val gen: GenerationParams,
    val style: PlatformStyle,
    private val aspect: Float,
    private val seed: Long,
    private val startTime: Double,
    val gravity: Double,
) {
    val ballRadius = 0.3
    val corridor = gen.corridorWidth.coerceIn(4.0, 30.0) * (if (aspect > 1.25f) 1.7 else if (aspect < 0.8f) 1.0 else 1.25)
    private val laneCenter = 0.0
    /** Pitch range of the song (piano layout spans the corridor like a keyboard). */
    private val sortedNotes = events.mapNotNull { it.midiNote }.sorted()
    private val noteLo = sortedNotes.getOrNull(sortedNotes.size / 10) ?: 48
    private val noteHi = maxOf(noteLo + 7, sortedNotes.getOrNull(sortedNotes.size * 9 / 10) ?: 84)
    val contacts = ArrayList<PlatformContact>()
    var nextEvent = 0; private set
    private var dir = 1.0
    var rejected = 0; private set

    val startContact = PlatformContact(-1, -1, startTime, Vec2(0.0, ballRadius + 0.2), Vec2(1.0, 6.0), Rect.centered(0.0, 0.1, 2.4, 0.2), 0, 0.8f, null, EventRole.MAJOR)

    fun ensure(now: Double) {
        var guard = 0
        while (nextEvent < events.size) {
            val ahead = contacts.count { it.timeSec > now }
            if (ahead >= 24 && (contacts.lastOrNull()?.timeSec ?: 0.0) > now + 4.0) break
            planNext()
            if (++guard > 2048) break
        }
    }

    fun prune(now: Double) {
        var n = 0
        while (n < contacts.size - 3 && contacts[n].timeSec < now - 5.0) n++
        if (n > 0) contacts.subList(0, n).clear()
    }

    private fun platformSize(e: MusicEvent): Pair<Double, Double> = when (style) {
        PlatformStyle.PIANO_TILES -> (if (aspect > 1.25f) 1.45 else 1.0) to 0.34
        PlatformStyle.MINIMAL_BARS -> (1.6 + 1.2 * e.importance) to 0.12
        PlatformStyle.STAIRCASE -> 1.9 to 0.4
        PlatformStyle.BLOCK_TERRAIN -> (1.2 + 0.8 * e.importance) to 0.5
        else -> (1.3 + 1.3 * e.importance) to 0.32
    }

    private fun planNext() {
        val k = nextEvent
        val e = events[k]
        val prev = contacts.lastOrNull() ?: startContact
        val T = (e.timeSec - prev.timeSec).coerceAtLeast(0.05)
        val rng = SeededRng.forKey(seed, "platform.course", e.id)
        val (w, h) = platformSize(e)
        val x0 = prev.position.x
        // keep inside the corridor: turn around near the edges
        if (x0 > laneCenter + corridor * 0.32) dir = -1.0 else if (x0 < laneCenter - corridor * 0.32) dir = 1.0
        else if (style != PlatformStyle.STAIRCASE && rng.chance(0.28)) dir = -dir

        // Choose the DROP first (the course must descend), then solve the bounce speed:
        //   dy = vb*T - g*T^2/2  =>  vb = (dy + g*T^2/2) / T
        // The largest drop that still leaves an upward bounce of vbMin is Dmax = g*T^2/2 - vbMin*T.
        val vbMin = 1.6
        val dMax = (0.5 * gravity * T * T - vbMin * T).coerceAtMost(7.0)
        data class Cand(val dropFrac: Double, val vx: Double)
        val cands = ArrayList<Cand>()
        // Important notes bounce higher (= smaller drop for the same time).
        val baseFrac = MathUtil.lerp(0.85, 0.35, e.importance.toDouble()) * (if (e.role == EventRole.MAJOR) 0.8 else 1.0)
        val baseVx = MathUtil.clamp(rng.range(1.6, 3.4), 1.0, 5.0)
        val minFrac = if (style == PlatformStyle.PIANO_TILES) 0.7 else 0.1 // keys: the ball clearly falls onto them
        for (ff in doubleArrayOf(1.0, 0.75, 1.15, 0.5, 0.3)) for (fx in doubleArrayOf(1.0, 0.6, 1.5, 0.3)) cands += Cand((baseFrac * ff).coerceIn(minFrac, 1.0), baseVx * fx)

        var chosen: PlatformContact? = null
        for (c in cands) {
            var dy = if (dMax > 0.05) -dMax * c.dropFrac else dMax.coerceAtMost(0.0) // very short gaps: flat quick hops
            var vb = (dy + 0.5 * gravity * T * T) / T
            if (style == PlatformStyle.STAIRCASE) {
                // fixed step drop (bounded by what the time allows)
                dy = -minOf(gen.stepDrop * (1 + (T / 0.6 - 1).coerceIn(-0.4, 1.5) * 0.5), maxOf(0.2, dMax))
                vb = (dy + 0.5 * gravity * T * T) / T
            }
            var dx = dir * c.vx * T
            if (style == PlatformStyle.PIANO_TILES && e.midiNote != null) {
                // Piano: x follows pitch (note markers line up like a keyboard).
                val norm = ((e.midiNote - noteLo).toDouble() / (noteHi - noteLo)).coerceIn(-0.05, 1.05)
                val targetX = laneCenter + (norm - 0.5) * corridor * 0.8
                dx = targetX - x0
            }
            val pos = Vec2(x0 + dx, prev.position.y + dy)
            val launch = Vec2(dx / T, vb)
            val apex = if (vb > 0) vb * vb / (2 * gravity) else 0.0
            val platform = Rect(pos.x - w / 2 + rng.range(-0.15, 0.15) * w, pos.y - ballRadius - h, w, h)
            val contact = PlatformContact(k, e.id, e.timeSec, pos, launch, platform, e.pitchClass ?: k % 12, e.importance, e.midiNote, e.role)
            if (!valid(prev, contact, apex, T)) { rejected++; continue }
            chosen = contact
            break
        }
        if (chosen == null) {
            // Fallback: always on time; straight drop below the previous platform.
            val dy = -0.5 * gravity * T * T + 2.0 * T
            val pos = Vec2(x0 + dir * 1.8, prev.position.y + maxOf(dy, -7.0))
            val vb = (pos.y - prev.position.y + 0.5 * gravity * T * T) / T
            chosen = PlatformContact(k, e.id, e.timeSec, pos, Vec2((pos.x - x0) / T, vb), Rect(pos.x - w / 2, pos.y - ballRadius - h, w, h), e.pitchClass ?: k % 12, e.importance, e.midiNote, e.role)
        }
        contacts += chosen
        nextEvent++
    }

    private fun valid(prev: PlatformContact, c: PlatformContact, apex: Double, T: Double): Boolean {
        if (abs(c.position.x - laneCenter) > corridor * 0.45) return false
        if (apex > 7.5) return false
        if (abs(c.launch.x) > 12) return false
        // no overlap with recent platforms
        for (i in maxOf(0, contacts.size - 14) until contacts.size) {
            if (contacts[i].platform.intersects(c.platform, 0.12)) return false
        }
        if (prev.platform.intersects(c.platform, 0.05) && prev.index >= 0) return false
        // the arc must not pass through any recent platform (except start/end surfaces)
        for (s in 1..11) {
            val tt = T * s / 12
            val p = Vec2(prev.position.x + prev.launchTo(c).x * tt, prev.position.y + c.launch.y * tt - 0.5 * gravity * tt * tt)
            for (i in maxOf(0, contacts.size - 14) until contacts.size) {
                val o = contacts[i].platform
                if (o.expanded(ballRadius * 0.9).contains(p.x, p.y)) return false
            }
        }
        // the new platform must not sit in the recent flight paths
        for (i in maxOf(1, contacts.size - 8) until contacts.size) {
            val a = contacts[i - 1]; val b = contacts[i]
            val dt = b.timeSec - a.timeSec
            for (s in 1..9) {
                val tt = dt * s / 10
                val px = a.position.x + b.launch.x * tt
                val py = a.position.y + b.launch.y * tt - 0.5 * gravity * tt * tt
                if (c.platform.expanded(ballRadius).contains(px, py)) return false
            }
        }
        return true
    }

    private fun PlatformContact.launchTo(next: PlatformContact) = next.launch

    // ---- evaluation --------------------------------------------------------------------------

    /** Ball center at time t: exact projectile motion between planned contacts. */
    fun ballAt(t: Double): Vec2 {
        if (contacts.isEmpty()) return startContact.position
        var prev = startContact
        var next = contacts[0]
        if (t > contacts[0].timeSec) {
            var lo = 0; var hi = contacts.size - 1
            while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (contacts[mid].timeSec <= t) lo = mid else hi = mid - 1 }
            prev = contacts[lo]
            if (lo + 1 >= contacts.size) return prev.position
            next = contacts[lo + 1]
        } else if (contacts[0].index > 0) {
            // history pruned before first retained contact
            return contacts[0].position
        }
        val tt = (t - prev.timeSec).coerceAtLeast(0.0)
        if (t < prev.timeSec) return prev.position
        return Vec2(prev.position.x + next.launch.x * tt, prev.position.y + next.launch.y * tt - 0.5 * gravity * tt * tt)
    }

    fun lastContactAtOrBefore(t: Double): PlatformContact? {
        var r: PlatformContact? = null
        for (c in contacts) { if (c.timeSec <= t) r = c else break }
        return r
    }

    fun contactsAfter(t: Double, n: Int): List<PlatformContact> {
        val out = ArrayList<PlatformContact>(n)
        for (c in contacts) if (c.timeSec > t) { out += c; if (out.size == n) break }
        return out
    }

    fun write(w: StateWriter) {
        w.i(nextEvent); w.d(dir); w.i(rejected); w.i(contacts.size)
        for (c in contacts) {
            w.i(c.index); w.l(c.eventId); w.d(c.timeSec); w.d(c.position.x); w.d(c.position.y); w.d(c.launch.x); w.d(c.launch.y)
            w.d(c.platform.x); w.d(c.platform.y); w.d(c.platform.w); w.d(c.platform.h); w.i(c.colorRole); w.f(c.importance); w.i(c.note ?: -1); w.i(c.role.ordinal)
        }
    }

    fun read(r: StateReader) {
        nextEvent = r.i(); dir = r.d(); rejected = r.i()
        contacts.clear()
        repeat(r.i()) {
            contacts += PlatformContact(r.i(), r.l(), r.d(), Vec2(r.d(), r.d()), Vec2(r.d(), r.d()), Rect(r.d(), r.d(), r.d(), r.d()),
                r.i(), r.f(), r.i().let { if (it < 0) null else it }, EventRole.values()[r.i()])
        }
    }
}
