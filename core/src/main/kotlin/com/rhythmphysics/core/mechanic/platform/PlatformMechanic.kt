package com.rhythmphysics.core.mechanic.platform

import com.rhythmphysics.core.math.MathUtil
import com.rhythmphysics.core.math.Vec2
import com.rhythmphysics.core.mechanic.MechanicCheckpoint
import com.rhythmphysics.core.mechanic.MechanicContext
import com.rhythmphysics.core.mechanic.MechanicController
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.music.EventMapper
import com.rhythmphysics.core.music.EventRole
import com.rhythmphysics.core.music.MusicEvent
import com.rhythmphysics.core.render.Bursts
import com.rhythmphysics.core.render.Camera2D
import com.rhythmphysics.core.render.Colors
import com.rhythmphysics.core.render.DrawList
import com.rhythmphysics.core.render.Palette
import com.rhythmphysics.core.render.RenderSettings
import com.rhythmphysics.core.render.Ribbon
import com.rhythmphysics.core.render.Viewport
import com.rhythmphysics.core.util.StateReader
import com.rhythmphysics.core.util.StateWriter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Platform Fall / Music Ball: a ball falls and bounces through a course generated from the music;
 * every meaningful contact happens exactly at its event. Six looks share one planner.
 */
class PlatformMechanic : MechanicController {
    override val type = MechanicType.PLATFORM
    override var fastForward = false

    private lateinit var ctx: MechanicContext
    lateinit var planner: PlatformCoursePlanner; private set
    private lateinit var palette: Palette
    private val cam = Camera2D()
    private val ribbon = Ribbon(96)
    private var fx: List<MusicEvent> = emptyList()

    override fun loadSession(ctx: MechanicContext) {
        this.ctx = ctx
        palette = Palette.named(ctx.preset.visuals.palette)
        precompute(ctx.physicalEvents)
    }

    override fun precompute(events: List<MusicEvent>) {
        val gen = ctx.preset.generation
        val physical = EventMapper.forMechanic(events, gen.minContactGapSec).filter { it.role.physical }
        val start = min(ctx.windowStart, (physical.firstOrNull()?.timeSec ?: ctx.windowStart) - 0.7)
        planner = PlatformCoursePlanner(physical, gen, PlatformStyle.of(gen.style), ctx.aspect, ctx.preset.seed ?: ctx.seed, start, ctx.preset.physics.gravity.coerceIn(8.0, 80.0))
        fx = ctx.fxEvents
        planner.ensure(ctx.windowStart)
    }

    override fun reset() = precompute(ctx.physicalEvents)

    override fun fixedUpdate(dt: Double, songTime: Double) {
        planner.ensure(songTime + 1.5)
        val t0 = songTime - dt
        for (c in planner.contacts) {
            if (c.timeSec <= t0) continue
            if (c.timeSec > songTime) break
            ctx.sync.log(c.eventId, c.timeSec, measureContact(c), "platform")
            if (!fastForward) ctx.sink.onImpact(type, c.timeSec, c.importance, c.eventId, c.note)
        }
        planner.prune(songTime)
    }

    /** Solves when the evaluated ball height reaches the platform top (bisection on the falling arc). */
    private fun measureContact(c: PlatformContact): Double {
        val top = c.platform.top + planner.ballRadius
        var lo = c.timeSec - 0.03; var hi = c.timeSec + 0.03
        // on the incoming arc the ball is above until contact
        fun h(t: Double) = if (t <= c.timeSec) planner.ballAt(t).y - top else -(t - c.timeSec)
        if (h(lo) < 0) return c.timeSec
        repeat(40) { val m = (lo + hi) / 2; if (h(m) > 0) lo = m else hi = m }
        return (lo + hi) / 2
    }

    // ---- camera -------------------------------------------------------------------------------

    private fun setupCamera(vp: Viewport, t: Double) {
        cam.vp = vp
        val zoom = ctx.preset.camera.zoom.coerceIn(0.4, 3.0)
        // 9:16 / 1:1: the corridor spans the width; 16:9: fixed visible height (vertical descent still readable).
        cam.pixelsPerUnit = if (vp.isLandscape) vp.h / (8.5 / zoom) else vp.w / (planner.corridor * 1.05 / zoom)
        var sx = 0.0; var sy = 0.0; var sw = 0.0
        for (i in -3..12) {
            val d = i * 0.1
            val w = MathUtil.gaussian(d - 0.4, 0.55)
            val p = planner.ballAt(t + d)
            sx += p.x * w; sy += p.y * w; sw += w
        }
        var cx = sx / sw; var cy = sy / sw
        // look slightly ahead/below: the upcoming course reads beneath the ball
        val next = planner.contactsAfter(t, 2)
        if (next.isNotEmpty()) { cy = 0.75 * cy + 0.25 * next.last().position.y; cx = 0.8 * cx + 0.2 * next.last().position.x }
        val ball = planner.ballAt(t)
        val halfH = vp.h / cam.pixelsPerUnit / 2; val halfW = vp.w / cam.pixelsPerUnit / 2
        // Ball in the upper-middle of the frame, never outside.
        val maxAbove = if (vp.isLandscape) 0.45 else 0.72
        cy = MathUtil.clamp(cy - halfH * 0.12, ball.y - halfH * maxAbove, ball.y + halfH * 0.25)
        cx = MathUtil.clamp(cx, ball.x - halfW * 0.65, ball.x + halfW * 0.65)
        cam.centerX = cx; cam.centerY = cy
    }

    // ---- rendering ----------------------------------------------------------------------------

    override fun render(dl: DrawList, vp: Viewport, renderTime: Double, alpha: Float, rs: RenderSettings) {
        val t = renderTime
        val vis = ctx.preset.visuals
        setupCamera(vp, t)
        val last = planner.lastContactAtOrBefore(t)
        if (last != null && rs.shakeScale > 0f && last.role == EventRole.MAJOR) {
            val age = t - last.timeSec
            if (age < 0.18) cam.centerY += (1 - age / 0.18) * 0.12 * ctx.preset.camera.shake * rs.shakeScale
        }
        dl.pushClip(vp.x, vp.y, vp.w, vp.h)
        dl.gradientRect(vp.x, vp.y, vp.w, vp.h, palette.bgTop, palette.bgBottom)
        if (planner.style != PlatformStyle.MINIMAL_BARS) {
            dl.radialRect(vp.x, vp.y, vp.w, vp.h, vp.cx, vp.y + vp.h * 0.35f, max(vp.w, vp.h) * 0.8f, Colors.withAlpha(palette.accent(last?.colorRole ?: 0), 0.10f), 0)
        }
        val ppu = cam.pixelsPerUnit.toFloat()
        for (c in planner.contacts) {
            val age = t - c.timeSec
            if (age > 3.0 || age < -4.0) continue
            drawPlatform(dl, c, age, vp, rs)
        }
        // FX-only events: sparkle on the current platform.
        if (last != null && vis.particles > 0.01) {
            var lo = 0; var hi = fx.size
            while (lo < hi) { val m = (lo + hi) ushr 1; if (fx[m].timeSec < t - 0.35) lo = m + 1 else hi = m }
            var i = lo
            while (i < fx.size && fx[i].timeSec <= t) {
                val e = fx[i]
                if (e.role == EventRole.FX_ONLY) {
                    val b = planner.ballAt(e.timeSec)
                    Bursts.draw(dl, ctx.seed, 50_000 + e.id, e.timeSec, t, cam.sx(b.x), cam.sy(b.y), (5 * vis.particles).toInt(),
                        speed = ppu * 3f, size = ppu * 0.08f, color = palette.accent(e.pitchClass ?: 0), life = 0.35, gravity = 0f, drag = 3f,
                        shape = Bursts.SHAPE_CIRCLE, settings = rs)
                }
                i++
            }
        }
        // Impact particles.
        for (c in planner.contacts) {
            val age = t - c.timeSec
            if (age < 0 || age > 0.7 || vis.particles <= 0.01) continue
            Bursts.draw(dl, ctx.seed, c.eventId, c.timeSec, t, cam.sx(c.position.x), cam.sy(c.platform.top), (10 * vis.particles * (0.5 + c.importance)).toInt(),
                speed = ppu * 6f, size = ppu * 0.14f, color = palette.accent(c.colorRole), life = 0.6, gravity = ppu * 18f, drag = 3f,
                dirX = 0f, dirY = -1f, spread = 1.2f, shape = if (planner.style == PlatformStyle.BLOCK_TERRAIN) Bursts.SHAPE_SQUARE else Bursts.SHAPE_SPARK, settings = rs)
        }
        // Trail + ball.
        val ball = planner.ballAt(t)
        val bpx = (planner.ballRadius * ppu * vis.heroSize).toFloat()
        val trailColor = vis.trailColor?.let { Colors.parse(it) } ?: palette.trail
        if (vis.trailOpacity > 0.01) {
            val n = rs.quality.trailSamples.coerceAtMost(80)
            ribbon.clear()
            for (i in 0 until n) { val p = planner.ballAt(t - vis.trailLengthSec * i / (n - 1)); ribbon.add(cam.sx(p.x), cam.sy(p.y)) }
            ribbon.draw(dl, trailColor, bpx * 2 * vis.trailWidth.toFloat(), vis.trailTaper.toFloat(), vis.trailOpacity.toFloat(), vis.bloom > 0.3)
        }
        val bloom = (vis.bloom * rs.bloomScale).toFloat()
        if (bloom > 0) { dl.glow(cam.sx(ball.x), cam.sy(ball.y), bpx * 5f, trailColor, 0.35f * bloom); dl.glow(cam.sx(ball.x), cam.sy(ball.y), bpx * 2.2f, palette.hero, 0.7f * bloom * vis.emissive.toFloat()) }
        dl.circle(cam.sx(ball.x), cam.sy(ball.y), bpx, palette.hero)
        if (planner.style != PlatformStyle.MINIMAL_BARS) dl.circle(cam.sx(ball.x) - bpx * 0.3f, cam.sy(ball.y) - bpx * 0.3f, bpx * 0.4f, Colors.withAlpha(Colors.WHITE, 0.6f))
        dl.popClip()
    }

    private fun drawPlatform(dl: DrawList, c: PlatformContact, age: Double, vp: Viewport, rs: RenderSettings) {
        val vis = ctx.preset.visuals
        val r = c.platform
        val x = cam.sx(r.x); val y = cam.sy(r.top); val w = cam.len(r.w); val h = cam.len(r.h)
        if (x > vp.x + vp.w + 50 || x + w < vp.x - 50) return
        val appear = if (age < 0) MathUtil.clamp((4.0 + age) / 0.5, 0.0, 1.0).toFloat() else 1f
        val fade = MathUtil.clamp((3.0 - age) / 0.8, 0.0, 1.0).toFloat() * appear
        val lit = if (age >= 0 && age < 0.5) (1 - age / 0.5).toFloat() else 0f
        val accent = palette.accent(c.colorRole)
        val hit = age >= 0
        val depthX = cam.len(0.22); val depthY = cam.len(0.16)
        when (planner.style) {
            PlatformStyle.MINIMAL_BARS -> {
                dl.rect(x, y, w, max(2f, h), Colors.withAlpha(Colors.lerp(palette.surface, palette.surfaceLit, lit), fade), h / 2)
            }
            PlatformStyle.NEON -> {
                val col = Colors.lerp(accent, Colors.WHITE, lit * 0.6f)
                dl.rect(x, y, w, h, Colors.withAlpha(palette.bgBottom, fade), h * 0.3f)
                dl.rectStroke(x, y, w, h, max(2f, h * 0.18f), Colors.withAlpha(col, fade * (if (hit) 1f else 0.7f)), h * 0.3f)
                if (vis.bloom > 0) dl.glow(x + w / 2, y + h / 2, w * 0.75f, col, fade * (0.12f + 0.5f * lit) * vis.bloom.toFloat() * rs.bloomScale)
            }
            PlatformStyle.PIANO_TILES -> {
                val white = c.note?.let { it % 12 !in intArrayOf(1, 3, 6, 8, 10) } ?: true
                val base = if (white) palette.surface else 0xFF23252C.toInt()
                val col = Colors.lerp(base, palette.surfaceLit, lit)
                dl.quad(x, y, x + depthX, y - depthY, x + w + depthX, y - depthY, x + w, y, Colors.withAlpha(Colors.scale(col, 0.85f), fade))
                dl.rect(x, y, w, h * 1.6f, Colors.withAlpha(col, fade), w * 0.08f)
                dl.rectStroke(x, y, w, h * 1.6f, max(1.5f, w * 0.03f), Colors.withAlpha(0xFF101014.toInt(), fade), w * 0.08f)
                if (lit > 0 && vis.bloom > 0) dl.glow(x + w / 2, y, w, palette.surfaceLit, lit * 0.6f * vis.bloom.toFloat() * rs.bloomScale)
            }
            PlatformStyle.BLOCK_TERRAIN -> {
                // dark block column down to the bottom of the view, note marker on top
                val bottom = vp.y + vp.h + 20
                val col = Colors.lerp(palette.surface, Colors.scale(palette.surface, 1.6f), lit)
                dl.quad(x, y, x + depthX, y - depthY, x + w + depthX, y - depthY, x + w, y, Colors.withAlpha(Colors.scale(col, 1.35f), fade))
                dl.quad(x + w, y, x + w + depthX, y - depthY, x + w + depthX, bottom, x + w, bottom, Colors.withAlpha(Colors.scale(col, 0.6f), fade))
                dl.rect(x, y, w, bottom - y, Colors.withAlpha(col, fade))
                val markerColor = if (hit) accent else Colors.withAlpha(accent, 0.55f)
                dl.rect(x + w * 0.15f, y - max(2f, h * 0.18f), w * 0.7f, max(2f, h * 0.18f), Colors.withAlpha(markerColor, fade), h * 0.1f)
                if (lit > 0 && vis.bloom > 0) dl.glow(x + w / 2, y, w * 0.9f, accent, lit * 0.7f * vis.bloom.toFloat() * rs.bloomScale)
                if (vis.noteLabels && c.note != null && w > 28) dl.text(x + w / 2, y + max(14f, h * 1.8f), max(10f, w * 0.22f), Colors.withAlpha(accent, fade * 0.8f), noteName(c.note), DrawList.Align.CENTER)
            }
            PlatformStyle.STAIRCASE, PlatformStyle.MUSIC_BALL -> {
                val base = if (planner.style == PlatformStyle.STAIRCASE) palette.surface else Colors.lerp(palette.surface, accent, 0.55f)
                val col = Colors.lerp(base, if (hit) Colors.lerp(accent, palette.surfaceLit, 0.4f) else base, if (hit) min(1f, 0.55f + lit) else 0f)
                // top face (depth) + front face
                dl.quad(x, y, x + depthX, y - depthY, x + w + depthX, y - depthY, x + w, y, Colors.withAlpha(Colors.scale(col, 1.3f), fade))
                dl.quad(x + w, y, x + w + depthX, y - depthY, x + w + depthX, y + h - depthY, x + w, y + h, Colors.withAlpha(Colors.scale(col, 0.6f), fade))
                dl.rect(x, y, w, h, Colors.withAlpha(col, fade), min(h, w) * 0.2f)
                if (vis.bloom > 0 && (lit > 0 || hit)) dl.glow(x + w / 2, y + h / 2, w * 0.85f, accent, fade * (0.08f + 0.6f * lit) * vis.bloom.toFloat() * rs.bloomScale)
            }
        }
        if (lit > 0 && vis.impactFlash > 0) {
            val u = 1 - lit
            dl.rectStroke(x - u * w * 0.15f, y - u * h, w * (1 + u * 0.3f), h * (1 + u * 2), max(1.5f, h * 0.12f), Colors.withAlpha(Colors.WHITE, lit * 0.6f * vis.impactFlash.toFloat() * rs.flashScale), h * 0.3f)
        }
    }

    private fun noteName(n: Int): String = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[((n % 12) + 12) % 12] + (n / 12 - 1)

    // ---- lifecycle ------------------------------------------------------------------------------

    override fun createCheckpoint(): MechanicCheckpoint { val w = StateWriter(); planner.write(w); return MechanicCheckpoint(type, w.bytes()) }
    override fun restoreCheckpoint(checkpoint: MechanicCheckpoint) = planner.read(StateReader(checkpoint.bytes))

    override fun heroScreenPosition(vp: Viewport, renderTime: Double): FloatArray {
        setupCamera(vp, renderTime)
        val b = planner.ballAt(renderTime)
        return floatArrayOf(cam.sx(b.x), cam.sy(b.y), (planner.ballRadius * 2 * cam.pixelsPerUnit).toFloat())
    }

    override fun heroColor(renderTime: Double): Int = palette.trail

    /** [coverage of visible course bbox, ball size / short side, upcoming contacts in frame ratio, visible platforms]. */
    fun composition(vp: Viewport, t: Double): DoubleArray {
        setupCamera(vp, t)
        val b = planner.ballAt(t)
        var minX = cam.sx(b.x); var maxX = minX; var minY = cam.sy(b.y); var maxY = minY
        var n = 0
        for (c in planner.contacts) {
            val age = t - c.timeSec
            if (age > 3.0 || age < -3.0) continue
            val x0 = cam.sx(c.platform.x); val x1 = cam.sx(c.platform.right); val y0 = cam.sy(c.platform.top)
            if (x1 < vp.x || x0 > vp.x + vp.w || y0 < vp.y || y0 > vp.y + vp.h) continue
            n++
            minX = min(minX, x0); maxX = max(maxX, x1); minY = min(minY, y0); maxY = max(maxY, y0)
        }
        val next = planner.contactsAfter(t, 2)
        val inFrame = next.count { val x = cam.sx(it.position.x); val y = cam.sy(it.position.y); x in vp.x..(vp.x + vp.w) && y in vp.y..(vp.y + vp.h) }.toDouble() / max(1, next.size)
        return doubleArrayOf(((maxX - minX) * (maxY - minY) / (vp.w * vp.h)).toDouble(), planner.ballRadius * 2 * cam.pixelsPerUnit / vp.unit, inFrame, n.toDouble())
    }

    override fun debugLines(): List<String> = listOf(
        "platform ${planner.style} contacts ${planner.contacts.size} next #${planner.nextEvent} rejected ${planner.rejected}",
        "g=%.1f corridor %.1f".format(planner.gravity, planner.corridor),
    )

    @Suppress("unused") private fun unusedAbs() = abs(0) + Vec2.ZERO.x.toInt()
}
