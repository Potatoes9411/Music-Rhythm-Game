package com.rhythmphysics.core.mechanic.circle

import com.rhythmphysics.core.preset.AnomalyType
import com.rhythmphysics.core.preset.PhysicsParams
import com.rhythmphysics.core.rng.SeededRng
import com.rhythmphysics.core.util.StateReader
import com.rhythmphysics.core.util.StateWriter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** An explicit, logged anomaly (never scattered randomness). */
data class AnomalyEvent(
    val timeSec: Double?,
    val collisionIndex: Long?,
    val type: AnomalyType,
    val params: Map<String, Float>,
    val rngState: Long,
)

class Ball(
    var id: Int, var x: Double, var y: Double, var vx: Double, var vy: Double, var r: Double, var targetR: Double, var color: Int,
) {
    var px = x; var py = y           // previous step position (render interpolation)
    var lastHit = -10.0
    val trailX = DoubleArray(TRAIL); val trailY = DoubleArray(TRAIL)
    var trailHead = 0; var trailCount = 0
    fun pushTrail() { trailX[trailHead] = x; trailY[trailHead] = y; trailHead = (trailHead + 1) % TRAIL; if (trailCount < TRAIL) trailCount++ }
    /** i = 0 newest. */
    fun trail(i: Int, out: DoubleArray) { val k = (trailHead - 1 - i + TRAIL * 2) % TRAIL; out[0] = trailX[k]; out[1] = trailY[k] }
    companion object { const val TRAIL = 24 }
}

/** Recent contact, kept for visuals (flash on the ring, particles) and replay-exact rendering. */
data class Contact(val time: Double, val x: Double, val y: Double, val angle: Double, val strength: Float, val index: Long, val color: Int, val ringHit: Boolean)

/**
 * Deterministic circle physics: ring containment with analytic time-of-impact inside fixed
 * substeps, proper normal reflection (never axis flips), rotating gaps, ball-ball impulses on a
 * spatial grid and an explicit anomaly system.
 */
class CircleWorld(val params: PhysicsParams, seed: Long) {
    val balls = ArrayList<Ball>()
    var ringR = params.ringRadius
    var targetRingR = params.ringRadius
    var ringRotation = 0.0
    var gapRotationSpeed = Math.toRadians(params.gapRotationDegPerSec)
    val gaps = ArrayList<DoubleArray>() // [centerAngle(rad, ring-local), halfWidth(rad)]
    var gx = 0.0; var gy = -params.gravity
    var restitution = params.restitution
    var speedScale = 1.0
    var attract = params.attract
    var orbit = params.orbit
    var ringBrokenUntil = -1.0
    var colorShift = 0
    var trailMode = 0
    var collisions = 0L
    var escapes = 0L
    var nextBallId = 0
    val rng = SeededRng.stream(seed, "circle.anomalies")
    val contacts = ArrayList<Contact>()
    val anomalyLog = ArrayList<AnomalyEvent>()
    /** Hook: called on every ring contact (sound/haptics/generative notes/anomalies). */
    var onRingHit: ((Ball, Contact) -> Unit)? = null
    var onEscape: ((Ball) -> Unit)? = null

    init {
        for (i in 0 until params.gapCount) addGap(2 * PI * i / params.gapCount.coerceAtLeast(1), Math.toRadians(params.gapSizeDeg) / 2)
    }

    fun addGap(center: Double, half: Double) { if (gaps.size < 12) gaps += doubleArrayOf(center, half) }

    fun spawnBall(x: Double, y: Double, vx: Double, vy: Double, r: Double = params.ballRadius, color: Int = nextBallId): Ball? {
        if (balls.size >= params.maxBalls) return null
        val b = Ball(nextBallId++, x, y, vx, vy, r, r, color)
        balls += b
        return b
    }

    fun spawnRandom(time: Double) {
        val a = rng.nextDouble() * 2 * PI
        val d = rng.nextDouble() * ringR * 0.3
        val sp = params.initialSpeed * (0.7 + 0.6 * rng.nextDouble())
        val va = rng.nextDouble() * 2 * PI
        spawnBall(d * cos(a), d * sin(a), sp * cos(va), sp * sin(va))
        @Suppress("UNUSED_VARIABLE") val unused = time
    }

    private fun inGap(worldAngle: Double, time: Double): Boolean {
        if (time < ringBrokenUntil) return true
        for (g in gaps) {
            var d = (worldAngle - (g[0] + ringRotation)) % (2 * PI)
            if (d > PI) d -= 2 * PI
            if (d < -PI) d += 2 * PI
            if (abs(d) <= g[1]) return true
        }
        return false
    }

    fun step(dt: Double, time: Double) {
        val sub = params.substeps.coerceIn(1, 16)
        val h = dt / sub
        for (b in balls) { b.px = b.x; b.py = b.y }
        for (s in 0 until sub) {
            val tSub = time - dt + (s + 1) * h
            ringRotation += gapRotationSpeed * h
            ringR += (targetRingR - ringR) * (1 - kotlin.math.exp(-h * 6))
            // Snapshot: callbacks (anomalies) may add/remove balls during the substep.
            val snapshot = balls.toTypedArray()
            for (b in snapshot) {
                b.r += (b.targetR - b.r) * (1 - kotlin.math.exp(-h * 8))
                // forces
                val d = sqrt(b.x * b.x + b.y * b.y).coerceAtLeast(1e-6)
                val nx = b.x / d; val ny = b.y / d
                b.vx += (gx - attract * nx - orbit * ny) * h
                b.vy += (gy - attract * ny + orbit * nx) * h
                val sp = sqrt(b.vx * b.vx + b.vy * b.vy)
                if (sp > params.maxSpeed) { b.vx *= params.maxSpeed / sp; b.vy *= params.maxSpeed / sp }
                moveWithRing(b, h, tSub)
            }
            if (params.ballCollisions && balls.size > 1) collideBalls()
            // Positional correction: growth, ring shrink or ball pushes must never leave a ball
            // straddling the wall (unless it is inside an open gap).
            for (b in balls) {
                if (b.targetR > ringR * 0.9) b.targetR = ringR * 0.9
                if (b.r > ringR * 0.95) b.r = ringR * 0.95
                val rr = ringR - b.r
                val d = sqrt(b.x * b.x + b.y * b.y)
                if (d > rr && d < ringR + b.r && !inGap(atan2(b.y, b.x), tSub)) {
                    val k = (rr - 1e-6) / d
                    b.x *= k; b.y *= k
                    val nx = b.x / (rr - 1e-6).coerceAtLeast(1e-9); val ny = b.y / (rr - 1e-6).coerceAtLeast(1e-9)
                    val vn = b.vx * nx + b.vy * ny
                    if (vn > 0) { b.vx -= (1 + restitution) * vn * nx; b.vy -= (1 + restitution) * vn * ny }
                }
            }
            // escapes: remove first, then notify (the callback may spawn replacements)
            var escaped: ArrayList<Ball>? = null
            val it = balls.iterator()
            while (it.hasNext()) {
                val b = it.next()
                val dist = sqrt(b.x * b.x + b.y * b.y)
                if (dist > ringR + b.r + 0.5 || dist > 60) { it.remove(); escapes++; (escaped ?: ArrayList<Ball>().also { e -> escaped = e }).add(b) }
            }
            escaped?.forEach { onEscape?.invoke(it) }
        }
        for ((i, b) in balls.withIndex()) if ((i + (time * 120).toLong()) % 2 == 0L) b.pushTrail() else if (b.trailCount == 0) b.pushTrail()
        if (contacts.size > 96) contacts.subList(0, contacts.size - 96).clear()
    }

    /** Analytic time-of-impact against the ring (inner wall), reflect about the contact normal. */
    private fun moveWithRing(b: Ball, h: Double, time: Double) {
        var remaining = h
        var iterations = 0
        val v = speedScale
        while (remaining > 1e-12 && iterations < 4) {
            iterations++
            val rr = ringR - b.r
            val px = b.x; val py = b.y
            val vx = b.vx * v; val vy = b.vy * v
            val a = vx * vx + vy * vy
            val bq = 2 * (px * vx + py * vy)
            val c = px * px + py * py - rr * rr
            var toi = Double.MAX_VALUE
            if (a > 1e-12 && rr > 0) {
                val disc = bq * bq - 4 * a * c
                if (disc >= 0) {
                    val sq = sqrt(disc)
                    // Only contacts from the inside count. A ball whose center is already past
                    // the wall has left through a gap and is never pulled back.
                    if (c <= 0) toi = (-bq + sq) / (2 * a) // exit root

                }
            }
            if (toi > remaining || toi < 0) {
                b.x += vx * remaining; b.y += vy * remaining
                return
            }
            // move to contact
            b.x += vx * toi; b.y += vy * toi
            remaining -= toi
            val dist = sqrt(b.x * b.x + b.y * b.y).coerceAtLeast(1e-9)
            val nx = b.x / dist; val ny = b.y / dist
            val angle = atan2(ny, nx)
            if (inGap(angle, time)) {
                // passes through the opening
                b.x += vx * remaining; b.y += vy * remaining
                return
            }
            // n = normalize(P - C); v' = v - (1 + e)(v.n) n
            val vn = b.vx * nx + b.vy * ny
            if (vn > 0) {
                b.vx -= (1 + restitution) * vn * nx
                b.vy -= (1 + restitution) * vn * ny
            }
            // nudge inside to avoid re-contact due to rounding
            b.x -= nx * 1e-7; b.y -= ny * 1e-7
            collisions++
            val strength = (abs(vn) / params.initialSpeed.coerceAtLeast(1.0)).toFloat().coerceIn(0.05f, 1.5f)
            val c2 = Contact(time, b.x + nx * b.r, b.y + ny * b.r, angle, strength, collisions, b.color, true)
            contacts += c2
            b.lastHit = time
            onRingHit?.invoke(b, c2)
        }
    }

    private val grid = HashMap<Long, ArrayList<Ball>>()

    private fun collideBalls() {
        val cell = (balls.maxOf { it.r } * 2).coerceAtLeast(0.5)
        grid.values.forEach { it.clear() }
        for (b in balls) {
            val key = (floor(b.x / cell).toLong() shl 32) xor (floor(b.y / cell).toLong() and 0xffffffffL)
            grid.getOrPut(key) { ArrayList() } += b
        }
        for ((i, a) in balls.withIndex()) {
            val cx = floor(a.x / cell).toLong(); val cy = floor(a.y / cell).toLong()
            for (dx in -1..1) for (dy in -1..1) {
                val list = grid[((cx + dx) shl 32) xor ((cy + dy) and 0xffffffffL)] ?: continue
                for (b in list) {
                    if (b.id <= a.id) continue
                    val ddx = b.x - a.x; val ddy = b.y - a.y
                    val minD = a.r + b.r
                    val d2 = ddx * ddx + ddy * ddy
                    if (d2 >= minD * minD || d2 < 1e-12) continue
                    val d = sqrt(d2)
                    val nx = ddx / d; val ny = ddy / d
                    val ma = a.r * a.r; val mb = b.r * b.r
                    val rel = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
                    if (rel < 0) {
                        val j = -(1 + restitution) * rel / (1 / ma + 1 / mb)
                        a.vx -= j / ma * nx; a.vy -= j / ma * ny
                        b.vx += j / mb * nx; b.vy += j / mb * ny
                    }
                    val overlap = (minD - d) / 2
                    a.x -= nx * overlap; a.y -= ny * overlap
                    b.x += nx * overlap; b.y += ny * overlap
                }
            }
            @Suppress("UNUSED_VARIABLE") val unusedI = i
        }
    }

    /** Applies an anomaly. Randomness comes only from [rng] (logged in the event). */
    fun apply(type: AnomalyType, p: Map<String, Float>, time: Double, collisionIndex: Long?) {
        anomalyLog += AnomalyEvent(time, collisionIndex, type, p, rng.state)
        if (anomalyLog.size > 64) anomalyLog.removeAt(0)
        val amount = (p["amount"] ?: 0f).toDouble()
        when (type) {
            AnomalyType.BALL_GROW -> balls.forEach { it.targetR = (it.targetR * (1 + (if (amount > 0) amount else 0.15))).coerceAtMost(params.maxBallRadius) }
            AnomalyType.BALL_SHRINK -> balls.forEach { it.targetR = (it.targetR * (1 - (if (amount > 0) amount else 0.12))).coerceAtLeast(0.12) }
            AnomalyType.RING_GROW -> targetRingR = (targetRingR + (if (amount > 0) amount else 0.8)).coerceAtMost(params.ringRadius * 1.3)
            AnomalyType.RING_SHRINK -> targetRingR = (targetRingR - (if (amount > 0) amount else 0.6)).coerceAtLeast(params.minRingRadius)
            AnomalyType.SPAWN_BALL -> repeat((p["count"] ?: 1f).toInt().coerceIn(1, 16)) { spawnRandom(time) }
            AnomalyType.REMOVE_BALL -> if (balls.size > 1) balls.removeAt(0)
            AnomalyType.SPLIT_BALL -> balls.maxByOrNull { it.r }?.let { b ->
                val nr = b.targetR / sqrt(2.0)
                b.targetR = nr; b.r = nr
                val sp = sqrt(b.vx * b.vx + b.vy * b.vy)
                spawnBall(b.x, b.y, -b.vy.let { if (sp < 1e-6) 1.0 else it }, b.vx, nr, b.color + 1)
            }
            AnomalyType.SPEED_UP -> speedScale = (speedScale * (1 + (if (amount > 0) amount else 0.15))).coerceAtMost(2.5)
            AnomalyType.SLOW_DOWN -> speedScale = (speedScale * (1 - (if (amount > 0) amount else 0.15))).coerceAtLeast(0.4)
            AnomalyType.GRAVITY_ENABLE -> { gx = 0.0; gy = -(if (amount > 0) amount else params.gravity.coerceAtLeast(18.0)) }
            AnomalyType.GRAVITY_DISABLE -> { gx = 0.0; gy = 0.0 }
            AnomalyType.GRAVITY_FLIP -> { gx = -gx; gy = -gy }
            AnomalyType.GRAVITY_ROTATE -> {
                val a = (p["degrees"]?.toDouble()?.let { Math.toRadians(it) }) ?: (rng.nextDouble() * 2 * PI)
                val g = sqrt(gx * gx + gy * gy).coerceAtLeast(18.0)
                val cur = atan2(gy, gx) + a
                gx = g * cos(cur); gy = g * sin(cur)
            }
            AnomalyType.GAP_OPEN -> addGap(rng.nextDouble() * 2 * PI, Math.toRadians((p["degrees"] ?: params.gapSizeDeg.toFloat()).toDouble()) / 2)
            AnomalyType.GAP_CLOSE -> if (gaps.isNotEmpty()) gaps.removeAt(gaps.size - 1)
            AnomalyType.GAP_ROTATE -> gapRotationSpeed = Math.toRadians((p["degPerSec"] ?: 90f).toDouble()) * (if (rng.chance(0.5)) 1 else -1)
            AnomalyType.MULTIPLE_GAPS -> {
                gaps.clear(); val n = (p["count"] ?: 3f).toInt().coerceIn(1, 8)
                val off = rng.nextDouble() * 2 * PI
                for (i in 0 until n) addGap(off + 2 * PI * i / n, Math.toRadians(params.gapSizeDeg * 0.7) / 2)
            }
            AnomalyType.RESTITUTION_CHANGE -> restitution = (p["value"]?.toDouble() ?: (0.85 + 0.3 * rng.nextDouble())).coerceIn(0.5, 1.15)
            AnomalyType.COLOR_SHIFT -> colorShift++
            AnomalyType.TRAIL_SHIFT -> trailMode = (trailMode + 1) % 3
            AnomalyType.ATTRACT -> { attract = (if (amount > 0) amount else 25.0); orbit = 0.0 }
            AnomalyType.REPEL -> { attract = -(if (amount > 0) amount else 20.0) }
            AnomalyType.ORBIT -> { orbit = (if (amount > 0) amount else 30.0) * (if (rng.chance(0.5)) 1 else -1) }
            AnomalyType.RING_BREAK -> ringBrokenUntil = time + (p["seconds"] ?: 1.2f)
            AnomalyType.CHAOS_BURST -> {
                for (b in balls) { val a = rng.nextDouble() * 2 * PI; val k = params.initialSpeed * 0.8; b.vx += k * cos(a); b.vy += k * sin(a) }
                repeat(2) { spawnRandom(time) }
            }
            AnomalyType.DUPLICATION_CASCADE -> {
                val snapshot = balls.toList()
                for (b in snapshot) spawnBall(b.x, b.y, -b.vx * 0.9 + rng.range(-2.0, 2.0), -b.vy * 0.9 + rng.range(-2.0, 2.0), b.targetR, b.color + 1)
            }
        }
    }

    // ---- checkpoint ----------------------------------------------------------------------------

    fun write(w: StateWriter) {
        w.d(ringR); w.d(targetRingR); w.d(ringRotation); w.d(gapRotationSpeed); w.d(gx); w.d(gy); w.d(restitution); w.d(speedScale)
        w.d(attract); w.d(orbit); w.d(ringBrokenUntil); w.i(colorShift); w.i(trailMode); w.l(collisions); w.l(escapes); w.i(nextBallId); w.l(rng.state)
        w.i(gaps.size); for (g in gaps) { w.d(g[0]); w.d(g[1]) }
        w.i(balls.size)
        for (b in balls) {
            w.i(b.id); w.d(b.x); w.d(b.y); w.d(b.vx); w.d(b.vy); w.d(b.r); w.d(b.targetR); w.i(b.color); w.d(b.px); w.d(b.py); w.d(b.lastHit)
            w.i(b.trailHead); w.i(b.trailCount); for (i in 0 until Ball.TRAIL) { w.d(b.trailX[i]); w.d(b.trailY[i]) }
        }
        w.i(contacts.size)
        for (c in contacts) { w.d(c.time); w.d(c.x); w.d(c.y); w.d(c.angle); w.f(c.strength); w.l(c.index); w.i(c.color); w.b(c.ringHit) }
    }

    fun read(r: StateReader) {
        ringR = r.d(); targetRingR = r.d(); ringRotation = r.d(); gapRotationSpeed = r.d(); gx = r.d(); gy = r.d(); restitution = r.d(); speedScale = r.d()
        attract = r.d(); orbit = r.d(); ringBrokenUntil = r.d(); colorShift = r.i(); trailMode = r.i(); collisions = r.l(); escapes = r.l(); nextBallId = r.i(); rng.state = r.l()
        gaps.clear(); repeat(r.i()) { gaps += doubleArrayOf(r.d(), r.d()) }
        balls.clear()
        repeat(r.i()) {
            val b = Ball(r.i(), r.d(), r.d(), r.d(), r.d(), r.d(), r.d(), r.i())
            b.px = r.d(); b.py = r.d(); b.lastHit = r.d()
            b.trailHead = r.i(); b.trailCount = r.i(); for (i in 0 until Ball.TRAIL) { b.trailX[i] = r.d(); b.trailY[i] = r.d() }
            balls += b
        }
        contacts.clear()
        repeat(r.i()) { contacts += Contact(r.d(), r.d(), r.d(), r.d(), r.f(), r.l(), r.i(), r.b()) }
        anomalyLog.clear()
    }
}
