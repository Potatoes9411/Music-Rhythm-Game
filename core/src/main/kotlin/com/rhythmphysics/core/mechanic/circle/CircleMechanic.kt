package com.rhythmphysics.core.mechanic.circle

import com.rhythmphysics.core.math.MathUtil
import com.rhythmphysics.core.mechanic.MechanicCheckpoint
import com.rhythmphysics.core.mechanic.MechanicContext
import com.rhythmphysics.core.mechanic.MechanicController
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.music.EventRole
import com.rhythmphysics.core.music.MusicEvent
import com.rhythmphysics.core.preset.AnomalyTrigger
import com.rhythmphysics.core.render.Bursts
import com.rhythmphysics.core.render.Colors
import com.rhythmphysics.core.render.DrawList
import com.rhythmphysics.core.render.Palette
import com.rhythmphysics.core.render.RenderSettings
import com.rhythmphysics.core.render.Ribbon
import com.rhythmphysics.core.render.Viewport
import com.rhythmphysics.core.rng.SeededRng
import com.rhythmphysics.core.util.StateReader
import com.rhythmphysics.core.util.StateWriter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Circle Physics / Circle Chaos. Real ring containment physics (see [CircleWorld]) in three styles:
 *  - sandbox: no song; tap to add balls,
 *  - reactive: the original song plays and musical events pulse the simulation / fire anomalies,
 *  - generative: every ring collision consumes and plays the next melody note.
 */
class CircleMechanic : MechanicController {
    override val type = MechanicType.CIRCLE
    override var fastForward = false

    private lateinit var ctx: MechanicContext
    lateinit var world: CircleWorld; private set
    private lateinit var palette: Palette
    private var mode = "reactive"
    private var events: List<MusicEvent> = emptyList()
    private var notes: List<MusicEvent> = emptyList()
    private var eventCursor = 0
    private var noteCursor = 0
    private var beatCount = 0
    private var downbeatCount = 0
    private var majorCount = 0
    private lateinit var ruleFired: IntArray
    private lateinit var ruleOccurrences: IntArray
    private var pulseUntil = -1.0
    private var lastSongTime = 0.0
    private val ribbon = Ribbon(Ball.TRAIL)
    /** Never-cleared paint (rings / trails looks); length is checkpointed, content is replayed. */
    val history = CircleHistory()
    private val lastRec = HashMap<Int, DoubleArray>()
    /** Times of "pops" (grown ball reset), for the burst effect (bounded list, part of state). */
    private val popTimes = ArrayList<Double>()
    private val tmp = DoubleArray(2)

    override fun loadSession(ctx: MechanicContext) {
        this.ctx = ctx
        palette = Palette.named(ctx.preset.visuals.palette)
        mode = if (ctx.session.mediaType == com.rhythmphysics.core.session.MediaType.NONE) "sandbox" else ctx.preset.generation.mode
        precompute(ctx.fxEvents)
    }

    override fun precompute(events: List<MusicEvent>) {
        this.events = events
        history.clear(); lastRec.clear()
        notes = ctx.session.source.noteStream()
        world = CircleWorld(ctx.preset.physics, ctx.preset.seed ?: ctx.seed)
        world.onRingHit = ::onRingHit
        world.onEscape = ::onEscape
        val spawn = SeededRng.stream(ctx.preset.seed ?: ctx.seed, "circle.spawn")
        val n = ctx.preset.physics.ballCount.coerceAtLeast(if (mode == "sandbox") 1 else 1)
        world.mirror = ctx.preset.generation.style == "mirror"
        if (world.mirror) {
            // One mirrored pair released from just above centre, drifting apart slowly.
            val vx = ctx.preset.physics.initialSpeed * 0.06
            world.spawnBall(0.02, world.ringR * 0.15, vx, 0.0, color = 0)
            world.spawnBall(-0.02, world.ringR * 0.15, -vx, 0.0, color = 0)
        } else for (i in 0 until n) {
            val a = spawn.nextDouble() * 2 * PI
            val d = if (n == 1) world.ringR * 0.25 else spawn.nextDouble() * world.ringR * 0.55
            val va = spawn.nextDouble() * 2 * PI
            val sp = ctx.preset.physics.initialSpeed * (0.8 + 0.4 * spawn.nextDouble())
            world.spawnBall(d * cos(a), d * sin(a) + (if (n == 1) world.ringR * 0.2 else 0.0), sp * cos(va), sp * sin(va), color = i)
        }
        eventCursor = events.indexOfFirst { it.timeSec > ctx.windowStart - 1e-9 }.let { if (it < 0) events.size else it }
        noteCursor = 0; beatCount = 0; downbeatCount = 0; majorCount = 0
        popTimes.clear()
        ruleFired = IntArray(ctx.preset.anomalies.size)
        ruleOccurrences = IntArray(ctx.preset.anomalies.size)
        lastSongTime = ctx.windowStart
    }

    override fun reset() = precompute(ctx.fxEvents)

    private fun fireRules(trigger: AnomalyTrigger, time: Double, collision: Long?) {
        val rules = ctx.preset.anomalies
        for ((i, r) in rules.withIndex()) {
            if (r.trigger != trigger) continue
            ruleOccurrences[i]++
            if (ruleOccurrences[i] % r.every.coerceAtLeast(1) != 0) continue
            if (r.maxCount > 0 && ruleFired[i] >= r.maxCount) continue
            if (r.probability < 1.0 && !world.rng.chance(r.probability)) continue
            ruleFired[i]++
            world.apply(r.type, r.params, time, collision)
        }
    }

    private fun onRingHit(b: Ball, c: Contact) {
        val phys = ctx.preset.physics
        if (phys.ballGrowthPerHit > 0) {
            b.targetR = min(phys.maxBallRadius, b.targetR + phys.ballGrowthPerHit)
            // Fully grown (fills the ring): pop back to the starting size and burst.
            if (b.targetR >= min(phys.maxBallRadius, world.ringR * 0.88) - 1e-9) {
                b.targetR = phys.ballRadius
                world.apply(com.rhythmphysics.core.preset.AnomalyType.COLOR_SHIFT, emptyMap(), c.time, c.index)
                popTimes += c.time
            }
        }
        if (phys.ringShrinkPerHit > 0) world.targetRingR = max(phys.minRingRadius, world.targetRingR - phys.ringShrinkPerHit)
        var note: Int? = null
        if (mode == "generative") {
            if (notes.isNotEmpty()) {
                val e = notes[noteCursor % notes.size]
                noteCursor++
                note = e.midiNote ?: (60 + (e.pitchClass ?: 0))
                b.color = (e.pitchClass ?: noteCursor)
                if (!fastForward) ctx.sink.onNote(c.time, note, (0.55f + 0.45f * min(1f, c.strength)).coerceIn(0.3f, 1f), GENERATIVE_PROGRAM)
            } else {
                // No song: pentatonic notes chosen from the contact angle (sandbox collision synth).
                val scale = intArrayOf(0, 2, 4, 7, 9)
                val idx = (((c.angle + PI) / (2 * PI)) * 10).toInt().coerceIn(0, 9)
                note = 60 + 12 * (idx / 5) + scale[idx % 5]
                b.color = idx
                if (!fastForward) ctx.sink.onNote(c.time, note, 0.7f, GENERATIVE_PROGRAM)
            }
        }
        if (!fastForward) ctx.sink.onImpact(type, c.time, c.strength.coerceAtMost(1f), c.index, note)
        fireRules(AnomalyTrigger.COLLISION, c.time, c.index)
    }

    private fun onEscape(b: Ball) {
        val phys = ctx.preset.physics
        repeat(phys.spawnOnEscape) {
            val a = world.rng.nextDouble() * 2 * PI
            val sp = phys.initialSpeed * (0.8 + 0.4 * world.rng.nextDouble())
            world.spawnBall(0.0, world.ringR * 0.1, sp * cos(a), sp * sin(a), phys.ballRadius, b.color + 1 + it)
        }
        fireRules(AnomalyTrigger.ESCAPE, lastSongTime, null)
        if (world.balls.isEmpty()) world.spawnBall(0.0, 0.0, phys.initialSpeed * 0.7, phys.initialSpeed * 0.7, phys.ballRadius, b.color + 1)
    }

    override fun fixedUpdate(dt: Double, songTime: Double) {
        lastSongTime = songTime
        val t0 = songTime - dt
        // Musical events in (t0, t]
        if (mode != "sandbox") {
            while (eventCursor < events.size && events[eventCursor].timeSec <= songTime) {
                val e = events[eventCursor++]
                if (e.timeSec <= t0 - 1e-9) continue
                if (e.isBeat) { beatCount++; fireRules(AnomalyTrigger.BEAT, e.timeSec, null) }
                if (e.isDownbeat) { downbeatCount++; fireRules(AnomalyTrigger.DOWNBEAT, e.timeSec, null) }
                if (e.role == EventRole.MAJOR) { majorCount++; fireRules(AnomalyTrigger.MAJOR_EVENT, e.timeSec, null) }
                if (mode == "reactive" && e.role.physical) {
                    // Pulse: a musical kick outward along each ball's radial direction.
                    val k = if (e.role == EventRole.MAJOR) 0.22 else if (e.role == EventRole.NORMAL) 0.10 else 0.05
                    for (b in world.balls) {
                        val d = sqrt(b.x * b.x + b.y * b.y).coerceAtLeast(1e-3)
                        val sp = sqrt(b.vx * b.vx + b.vy * b.vy)
                        b.vx += b.x / d * sp * k; b.vy += b.y / d * sp * k
                    }
                    if (e.role == EventRole.MAJOR) pulseUntil = e.timeSec + 0.25
                }
            }
        }
        // TIME rules
        for ((i, r) in ctx.preset.anomalies.withIndex()) {
            if (r.trigger != AnomalyTrigger.TIME) continue
            val first = r.atSec
            val crossed = if (r.repeatSec > 0) {
                if (songTime < first) false else {
                    val k1 = kotlin.math.floor((songTime - first) / r.repeatSec); val k0 = kotlin.math.floor((t0 - first) / r.repeatSec)
                    k1 > k0 || (t0 < first && songTime >= first)
                }
            } else t0 < first && songTime >= first
            if (crossed) {
                if (r.maxCount > 0 && ruleFired[i] >= r.maxCount) continue
                if (r.probability < 1.0 && !world.rng.chance(r.probability)) continue
                ruleFired[i]++
                world.apply(r.type, r.params, songTime, null)
            }
        }
        world.step(dt, songTime)
        record(songTime)
    }

    /** Appends this step's paint for the persistent looks (deterministic: depends only on state + time). */
    private fun record(songTime: Double) {
        val mode = ctx.preset.visuals.persist
        if (mode == "none" || history.full) return
        val step = kotlin.math.round(songTime * 120).toLong()
        if (mode == "rings") {
            if (step % 2L != 0L) return
            val hue = (songTime * 110.0 % 360.0).toFloat()
            val w = world.ringR * 0.012
            for (b in world.balls) history.ring(b.x, b.y, b.r, w, Colors.hsv(hue, 0.95f, 1f))
        } else if (mode == "trails") {
            if (step % 3L != 0L) return
            val mirror = world.mirror
            // Reference look: continuous lines while there are few balls, then per-frame dots.
            val dots = mirror && world.balls.size > 24
            for (b in world.balls) {
                val prev = lastRec[b.id]
                if (prev != null) {
                    val c = trailColor(b, songTime)
                    if (dots) history.segment(b.x, b.y, b.x, b.y, b.r * 0.55, c)
                    else history.segment(prev[0], prev[1], b.x, b.y, b.r * (if (mirror) 0.5 else 0.7), c)
                    prev[0] = b.x; prev[1] = b.y
                } else lastRec[b.id] = doubleArrayOf(b.x, b.y)
            }
            if (lastRec.size > world.balls.size) { val ids = world.balls.map { it.id }.toHashSet(); lastRec.keys.retainAll(ids) }
        }
    }

    /**
     * Trail paint color. Mirror style: a hue that settles from green/cyan into blue/purple over the
     * song, with each duplication generation offset a little.
     */
    private fun trailColor(b: Ball, t: Double): Int {
        if (!world.mirror) return Colors.hsv(((t * 120.0 + b.id * 47.0) % 360.0).toFloat(), 0.85f, 1f)
        val base = 150.0 + 125.0 * (1 - kotlin.math.exp(-t / 30.0))
        val gen = ((b.color * 37) % 90) - 45.0
        return Colors.hsv((((base + gen) % 360.0 + 360.0) % 360.0).toFloat(), 0.8f, 1f)
    }

    override fun onInput(kind: String, x: Float, y: Float, vp: Viewport) {
        val (cx, cy, ppu) = layout(vp)
        val wx = (x - cx) / ppu; val wy = -(y - cy) / ppu
        val d = sqrt((wx * wx + wy * wy).toDouble())
        if (d > world.ringR - 0.3) return
        val a = world.rng.nextDouble() * 2 * PI
        val sp = ctx.preset.physics.initialSpeed
        world.spawnBall(wx.toDouble(), wy.toDouble(), sp * cos(a), sp * sin(a), ctx.preset.physics.ballRadius, world.nextBallId)
    }

    // ---- rendering ----------------------------------------------------------------------------

    /** Ring centered and large: diameter ~86% of the short side, slightly above center in 9:16. */
    private fun layout(vp: Viewport): Triple<Float, Float, Float> {
        val ppu = (vp.unit * (if (vp.isPortrait) 0.46f else 0.44f) / ctx.preset.physics.ringRadius.toFloat()) * ctx.preset.camera.zoom.toFloat()
        val cy = vp.y + vp.h * (if (vp.isPortrait) 0.46f else 0.5f)
        return Triple(vp.cx, cy, ppu)
    }

    override fun render(dl: DrawList, vp: Viewport, renderTime: Double, alpha: Float, rs: RenderSettings) {
        val vis = ctx.preset.visuals
        val (cx, cy, ppu) = layout(vp)
        val t = renderTime
        fun sx(x: Double) = (cx + x * ppu).toFloat()
        fun sy(y: Double) = (cy - y * ppu).toFloat()
        dl.pushClip(vp.x, vp.y, vp.w, vp.h)
        dl.gradientRect(vp.x, vp.y, vp.w, vp.h, palette.bgTop, palette.bgBottom)
        val pulse = if (t < pulseUntil) ((pulseUntil - t) / 0.25).toFloat() else 0f
        val persist = vis.persist != "none"
        if (persist) {
            dl.pushTransform(cx, cy, ppu)
            dl.persistent(history)
            dl.popTransform()
        } else {
            dl.glow(cx, cy, (world.ringR * ppu * 1.35).toFloat(), palette.accent(world.colorShift), (0.10f + 0.18f * pulse * rs.flashScale) * rs.bloomScale)
        }

        // Ring (arcs between gaps); broken ring shows fragments flying out.
        val ringPx = (world.ringR * ppu).toFloat()
        val thick = max(2f, (ctx.preset.physics.ringThickness * ppu).toFloat())
        val ringColor = Colors.lerp(palette.surface, palette.surfaceLit, pulse)
        if (t < world.ringBrokenUntil) {
            val left = (world.ringBrokenUntil - t).toFloat()
            val n = 18
            for (i in 0 until n) {
                val a0 = 2 * PI * i / n
                val spread = (1f - min(1f, left)) * 0.0f + (1.2f - left).coerceAtLeast(0f) * 0.9f
                val r = ringPx * (1 + spread * 0.25f)
                dl.arc(cx, cy, r, -Math.toDegrees(a0 + world.ringRotation).toFloat(), -300f / n, thick, Colors.withAlpha(ringColor, 0.6f), false)
            }
        } else {
            drawRingArcs(dl, cx, cy, ringPx, thick, ringColor)
        }
        // Contact flashes on the ring.
        for (c in world.contacts) {
            val age = t - c.time
            if (age < 0 || age > 0.35 || !c.ringHit) continue
            val u = (age / 0.35).toFloat()
            val col = palette.accent(c.color + world.colorShift)
            val deg = -Math.toDegrees(c.angle).toFloat()
            dl.arc(cx, cy, ringPx, deg - 9f, 18f, thick * (2.2f - u), Colors.withAlpha(Colors.lerp(Colors.WHITE, col, u), (1 - u) * vis.impactFlash.toFloat() * rs.flashScale))
            if (vis.bloom > 0) dl.glow(sx(c.x), sy(c.y), thick * 7, col, (1 - u) * 0.45f * vis.bloom.toFloat() * rs.bloomScale)
        }
        // Particles from contacts (stateless per contact index).
        if (vis.particles > 0.01) for (c in world.contacts) {
            val age = t - c.time
            if (age < 0 || age > 0.6 || !c.ringHit) continue
            val col = palette.accent(c.color + world.colorShift)
            Bursts.draw(dl, ctx.seed, c.index, c.time, t, sx(c.x), sy(c.y), (6 * vis.particles * (0.5 + c.strength)).toInt(),
                speed = ppu * 9f, size = ppu * 0.22f, color = col, life = 0.5, gravity = ppu * 12f, drag = 4f,
                dirX = -cos(c.angle).toFloat(), dirY = sin(c.angle).toFloat(), spread = 1.1f, shape = Bursts.SHAPE_CIRCLE, settings = rs)
        }
        // Pop bursts (grown ball resets).
        for (pt in popTimes) {
            val age = t - pt
            if (age < 0 || age > 0.5) continue
            val u = (age / 0.5).toFloat()
            dl.circleStroke(cx, cy, ringPx * (0.3f + 0.7f * u), thick * 2 * (1 - u), Colors.withAlpha(palette.accent(world.colorShift), (1 - u) * rs.flashScale))
        }
        // Balls with trails.
        val trailLen = when (world.trailMode) { 1 -> Ball.TRAIL; 2 -> 6; else -> (Ball.TRAIL * vis.trailLengthSec / 0.4).toInt().coerceIn(2, Ball.TRAIL) }
        for (b in world.balls) {
            val col = palette.accent(b.color + world.colorShift)
            val bx = b.px + (b.x - b.px) * alpha; val by = b.py + (b.y - b.py) * alpha
            val rpx = (b.r * ppu).toFloat()
            if (persist) {
                if (vis.persist == "rings") {
                    // Solid ball in front of its own stamped history, outlined in the current hue.
                    dl.circle(sx(bx), sy(by), rpx, palette.hero)
                    dl.circleStroke(sx(bx), sy(by), rpx, max(1.5f, (world.ringR * 0.012 * ppu).toFloat()), Colors.hsv((t * 110.0 % 360.0).toFloat(), 0.95f, 1f))
                } else {
                    // The live ball is just the head of its trail (the reference shows no separate balls).
                    dl.circle(sx(bx), sy(by), if (world.mirror) rpx * 0.55f else rpx, trailColor(b, t))
                }
                continue
            }
            if (vis.trailOpacity > 0.01 && b.trailCount > 1) {
                ribbon.clear()
                ribbon.add(sx(bx), sy(by))
                for (i in 0 until min(b.trailCount, trailLen)) { b.trail(i, tmp); ribbon.add(sx(tmp[0]), sy(tmp[1])) }
                ribbon.draw(dl, col, rpx * 2f * vis.trailWidth.toFloat(), vis.trailTaper.toFloat(), vis.trailOpacity.toFloat(), vis.bloom > 0.3)
            }
            val hitGlow = if (t - b.lastHit < 0.2) ((0.2 - (t - b.lastHit)) / 0.2).toFloat() else 0f
            if (vis.bloom > 0) dl.glow(sx(bx), sy(by), rpx * 2.6f, col, (0.25f + 0.5f * hitGlow) * vis.bloom.toFloat() * rs.bloomScale)
            dl.circle(sx(bx), sy(by), rpx, col)
            if (rpx > 4) dl.circle(sx(bx) - rpx * 0.3f, sy(by) - rpx * 0.3f, rpx * 0.35f, Colors.withAlpha(Colors.WHITE, 0.35f))
        }
        if (vis.showStats && !rs.cleanOutput) {
            val size = vp.unit * 0.04f
            val y = if (vp.isPortrait) cy + ringPx + size * 3.2f else vp.y + vp.h - size * 1.2f
            dl.text(vp.cx, y, size, Colors.withAlpha(palette.text, 0.85f), "balls ${world.balls.size}   hits ${world.collisions}" + (if (world.escapes > 0) "   escapes ${world.escapes}" else ""), DrawList.Align.CENTER)
        }
        dl.popClip()
    }

    private fun drawRingArcs(dl: DrawList, cx: Float, cy: Float, r: Float, thick: Float, color: Int) {
        if (world.gaps.isEmpty()) { dl.circleStroke(cx, cy, r, thick, color); return }
        // Sort gaps by world angle, draw arcs between consecutive gaps (world CCW -> screen clockwise negative).
        val gs = world.gaps.map { doubleArrayOf(norm(it[0] + world.ringRotation), it[1]) }.sortedBy { it[0] }
        for (i in gs.indices) {
            val g = gs[i]; val next = gs[(i + 1) % gs.size]
            val start = g[0] + g[1]
            var end = next[0] - next[1]
            if (i == gs.size - 1 || end <= start) end += 2 * PI
            val sweep = end - start
            if (sweep <= 0.01) continue
            // screen angle for world angle a is -a (y flipped); draw clockwise from -end with +sweep
            dl.arc(cx, cy, r, -Math.toDegrees(end).toFloat(), Math.toDegrees(sweep).toFloat(), thick, color, true)
        }
    }

    private fun norm(a: Double): Double { var x = a % (2 * PI); if (x < 0) x += 2 * PI; return x }

    // ---- lifecycle ---------------------------------------------------------------------------

    override fun createCheckpoint(): MechanicCheckpoint {
        val w = StateWriter()
        w.i(history.size) // first: read cheaply by canRestore
        world.write(w)
        w.i(eventCursor); w.i(noteCursor); w.i(beatCount); w.i(downbeatCount); w.i(majorCount); w.d(pulseUntil); w.d(lastSongTime)
        w.i(ruleFired.size); for (i in ruleFired.indices) { w.i(ruleFired[i]); w.i(ruleOccurrences[i]) }
        while (popTimes.size > 8) popTimes.removeAt(0)
        w.i(popTimes.size); for (pt in popTimes) w.d(pt)
        w.i(lastRec.size); for ((id, p) in lastRec.entries.sortedBy { it.key }) { w.i(id); w.d(p[0]); w.d(p[1]) }
        return MechanicCheckpoint(type, w.bytes())
    }

    override fun restoreCheckpoint(checkpoint: MechanicCheckpoint) {
        val r = StateReader(checkpoint.bytes)
        val histLen = r.i()
        world.read(r)
        eventCursor = r.i(); noteCursor = r.i(); beatCount = r.i(); downbeatCount = r.i(); majorCount = r.i(); pulseUntil = r.d(); lastSongTime = r.d()
        val n = r.i(); for (i in 0 until n) { ruleFired[i] = r.i(); ruleOccurrences[i] = r.i() }
        popTimes.clear(); repeat(r.i()) { popTimes += r.d() }
        history.truncate(histLen)
        lastRec.clear(); repeat(r.i()) { val id = r.i(); lastRec[id] = doubleArrayOf(r.d(), r.d()) }
    }

    override fun canRestore(checkpoint: MechanicCheckpoint): Boolean =
        StateReader(checkpoint.bytes).i() <= history.size

    override fun heroScreenPosition(vp: Viewport, renderTime: Double): FloatArray? {
        val b = world.balls.firstOrNull() ?: return null
        val (cx, cy, ppu) = layout(vp)
        return floatArrayOf((cx + b.x * ppu).toFloat(), (cy - b.y * ppu).toFloat(), (b.r * 2 * ppu).toFloat())
    }

    override fun heroColor(renderTime: Double): Int = world.balls.firstOrNull()?.let { palette.accent(it.color + world.colorShift) } ?: -1

    override val bodyCount: Int get() = world.balls.size

    override fun debugLines(): List<String> = listOf(
        "circle mode $mode balls ${world.balls.size} hits ${world.collisions} escapes ${world.escapes}",
        "ring R %.2f gaps ${world.gaps.size} g=(%.1f,%.1f) e=%.2f".format(world.ringR, world.gx, world.gy, world.restitution),
    ) + world.anomalyLog.takeLast(2).map { "anomaly ${it.type} @%.2f".format(it.timeSec ?: 0.0) }

    companion object {
        /** GM program used for generative collision notes (11 = Vibraphone). */
        const val GENERATIVE_PROGRAM = 11
    }
}
