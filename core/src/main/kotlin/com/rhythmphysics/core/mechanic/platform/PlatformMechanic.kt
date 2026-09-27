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
import com.rhythmphysics.core.render.Hash
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
        if (vis.padLook != "none") { renderPads(dl, vp, t, rs); dl.popClip(); return }
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

    // ---- tilted-pad looks (studio / neon / pastel / stones / marble machine) ---------------------

    /** Pad normal at contact i: parallel to (v_out - v_in), so the pad reflects the marble exactly. */
    private fun padNormal(i: Int): Vec2 {
        val cs = planner.contacts
        val c = cs[i]
        val prevT = if (i > 0) cs[i - 1].timeSec else c.timeSec - 0.5
        val T = (c.timeSec - prevT).coerceAtLeast(1e-3)
        val vin = Vec2(c.launch.x, c.launch.y - planner.gravity * T)
        val vout = if (i + 1 < cs.size) cs[i + 1].launch else Vec2(c.launch.x, -vin.y)
        val d = vout - vin
        val len = d.length
        return if (len < 1e-6 || d.y <= 0) Vec2(0.0, 1.0) else d * (1.0 / len)
    }

    private fun renderPads(dl: DrawList, vp: Viewport, t: Double, rs: RenderSettings) {
        val vis = ctx.preset.visuals
        val look = vis.padLook
        val ppu = cam.pixelsPerUnit.toFloat()
        dl.gradientRect(vp.x, vp.y, vp.w, vp.h, palette.bgTop, palette.bgBottom)
        val left = cam.centerX - vp.w / 2 / ppu; val top = cam.centerY + vp.h / 2 / ppu
        val right = left + vp.w / ppu; val bottom = top - vp.h / ppu
        when (look) {
            "marble" -> {
                // World-anchored wall tiles.
                val tile = 3.0
                val lc = Colors.withAlpha(Colors.scale(palette.bgBottom, 0.9f), 0.9f)
                var gx = kotlin.math.floor(left / tile) * tile
                while (gx < right) { dl.line(cam.sx(gx), vp.y, cam.sx(gx), vp.y + vp.h, max(1f, ppu * 0.03f), lc, false); gx += tile }
                var gy = kotlin.math.floor(bottom / tile) * tile
                while (gy < top) { dl.line(vp.x, cam.sy(gy), vp.x + vp.w, cam.sy(gy), max(1f, ppu * 0.03f), lc, false); gy += tile }
            }
            "stones" -> {
                // Soft pastel stones on the dark wall, seeded per world cell (stable while scrolling).
                val cell = 2.6
                val cols = intArrayOf(0xFF6E6A78.toInt(), 0xFF8A7A6A.toInt(), 0xFF2E6E6A.toInt(), 0xFF3A4F8A.toInt(), 0xFF9A8E86.toInt(), 0xFF5A5060.toInt(), 0xFFB8B4AE.toInt())
                var cx0 = kotlin.math.floor(left / cell).toLong()
                while (cx0 * cell < right + cell) {
                    var cy0 = kotlin.math.floor(bottom / cell).toLong()
                    while (cy0 * cell < top + cell) {
                        val h1 = Hash.unit(ctx.seed, cx0, cy0); val h2 = Hash.unit(ctx.seed + 7, cx0, cy0); val h3 = Hash.unit(ctx.seed + 13, cx0, cy0)
                        if (h3 < 0.8) {
                            val wx = (cx0 + 0.2 + 0.6 * h1) * cell; val wy = (cy0 + 0.2 + 0.6 * h2) * cell
                            val rr = cell * (0.22 + 0.25 * h3)
                            val xs = FloatArray(7); val ys = FloatArray(7)
                            for (k in 0 until 7) {
                                val a = 2 * Math.PI * k / 7 + h1 * 3
                                val rk = rr * (0.75 + 0.35 * Hash.unit(ctx.seed + 31, cx0 * 7 + k, cy0))
                                xs[k] = cam.sx(wx + kotlin.math.cos(a) * rk); ys[k] = cam.sy(wy + kotlin.math.sin(a) * rk * 0.8)
                            }
                            dl.poly(xs, ys, 7, Colors.withAlpha(cols[((h1 * 97).toInt()) % cols.size], 0.55f))
                        }
                        cy0++
                    }
                    cx0++
                }
            }
            "studio", "pastel" -> dl.radialRect(vp.x, vp.y, vp.w, vp.h, vp.cx, vp.y + vp.h * 0.3f, max(vp.w, vp.h) * 0.9f, 0x22FFFFFF, 0x22000000)
            "neon" -> dl.radialRect(vp.x, vp.y, vp.w, vp.h, vp.cx, vp.cy, max(vp.w, vp.h) * 0.8f, 0x183FA8FF, 0x00000000)
        }
        val cs = planner.contacts
        val ball = planner.ballAt(t)
        val bpx = (planner.ballRadius * ppu * vis.heroSize).toFloat()
        // Shadow of the marble on the wall (light from the upper left).
        val shOff = ppu * 0.35f
        if (look != "neon") dl.circle(cam.sx(ball.x) + shOff, cam.sy(ball.y) + shOff * 1.3f, bpx * 1.05f, 0x33000000)
        for (i in cs.indices) {
            val c = cs[i]
            val age = t - c.timeSec
            if (age > 4.0 || age < -5.0) continue
            val n = padNormal(i)
            // Chunky slabs sized from the drawn marble (references: ~2.5 marble widths long).
            val vr = planner.ballRadius * vis.heroSize
            val w = vr * (if (look == "marble") 5.6 else 5.0)
            val th = vr * (if (look == "marble") 0.6 else if (look == "neon") 0.7 else 0.9)
            // Pad surface centre sits one (drawn) marble radius below the marble centre along the normal.
            val px = c.position.x - n.x * (vr + th / 2); val py = c.position.y - n.y * (vr + th / 2)
            val sx = cam.sx(px); val sy = cam.sy(py)
            if (sx < vp.x - 200 || sx > vp.x + vp.w + 200 || sy < vp.y - 200 || sy > vp.y + vp.h + 200) continue
            val deg = (-Math.toDegrees(kotlin.math.atan2(n.y, n.x) - Math.PI / 2)).toFloat()
            val wp = cam.len(w); val hp = cam.len(th)
            val lit = if (age >= 0 && age < 0.6) (1 - age / 0.6).toFloat() else 0f
            val role = c.colorRole
            val accent = palette.accent(role)
            val base = when (look) {
                "pastel" -> if (age >= 0) Colors.lerp(accent, palette.surface, 0.25f) else palette.surface
                "marble" -> palette.accent(i % 2 * 2 + (role and 1))
                "stones" -> palette.accent(role)
                "neon" -> palette.surface
                else -> accent
            }
            // Bracket + rod into the wall.
            if (look != "neon" && look != "stones") {
                val rx = px - n.x * 0.55; val ry = py - n.y * 0.55
                dl.line(sx, sy, cam.sx(rx) + shOff * 0.3f, cam.sy(ry) + shOff * 0.3f, max(1.5f, ppu * 0.05f), palette.text, true)
                dl.circle(cam.sx(rx) + shOff * 0.3f, cam.sy(ry) + shOff * 0.3f, max(2f, ppu * 0.07f), palette.text)
            }
            // Soft drop shadow on the wall.
            if (look != "neon") {
                // Soft penumbra: stacked, growing, fainter layers (the references use soft area lights).
                for (k in 4 downTo 0) {
                    val g = 1f + k * 0.28f
                    dl.rectCentered(sx + shOff * (1.2f + k * 0.15f), sy + shOff * (1.6f + k * 0.2f), wp * (1f + k * 0.05f), hp * g * 1.3f, 0x12000000, hp * g * 0.6f, deg)
                }
            }
            when (look) {
                "neon", "stones" -> {
                    val glowA = 0.25f + 0.75f * lit
                    if (vis.bloom > 0) dl.glow(sx, sy, wp * 0.9f, base, glowA * 0.5f * vis.bloom.toFloat() * rs.bloomScale)
                    dl.rectCentered(sx, sy, wp, hp, Colors.withAlpha(Colors.scale(base, 0.25f), 0.85f), hp * 0.25f, deg)
                    dl.rectStroke(sx - wp / 2, sy - hp / 2, wp, hp, max(1.5f, hp * 0.22f), Colors.lerp(base, Colors.WHITE, 0.5f * lit), hp * 0.25f, deg)
                }
                else -> if (look == "marble" && i % 3 == 0) {
                    // Percussion tube: saturated cylinder with a dark open end facing the viewer.
                    val tubeCols = intArrayOf(0xFFFF5A2A.toInt(), 0xFFF5B82A.toInt(), 0xFF3DBB47.toInt(), 0xFF2F7FE0.toInt(), 0xFF9A5AD8.toInt(), 0xFFFF7FB0.toInt())
                    val tc = tubeCols[(c.note ?: i) % tubeCols.size]
                    dl.rectCentered(sx, sy, wp, hp * 1.9f, Colors.scale(tc, 0.75f), hp * 0.95f, deg)
                    dl.rectCentered(sx, sy - hp * 0.2f, wp * 0.97f, hp * 1.3f, Colors.lerp(tc, palette.surfaceLit, 0.4f * lit), hp * 0.65f, deg)
                    val ex = (sx - kotlin.math.cos(Math.toRadians(deg.toDouble())) * wp / 2).toFloat()
                    val ey = (sy - kotlin.math.sin(Math.toRadians(deg.toDouble())) * wp / 2).toFloat()
                    dl.circle(ex, ey, hp * 0.95f, Colors.scale(tc, 0.85f)); dl.circle(ex, ey, hp * 0.6f, 0xFF1A1414.toInt())
                } else {
                    val bevel = Colors.scale(base, 0.72f)
                    val topC = Colors.lerp(base, palette.surfaceLit, 0.45f * lit)
                    // Thickness side (toward the viewer's lower right), then the lit face.
                    dl.rectCentered(sx + hp * 0.18f, sy + hp * 0.32f, wp, hp, bevel, hp * 0.2f, deg)
                    dl.rectCentered(sx, sy, wp, hp * 0.8f, topC, hp * 0.2f, deg)
                    if (lit > 0 && (look == "pastel") && vis.bloom > 0) dl.glow(sx, sy, wp * 0.8f, accent, lit * 0.6f * vis.bloom.toFloat() * rs.bloomScale)
                }
            }
        }
        // Trail (neon / stones glow trails), then the marble.
        val trailColor = vis.trailColor?.let { Colors.parse(it) } ?: palette.trail
        if (vis.trailOpacity > 0.01) {
            val nS = rs.quality.trailSamples.coerceAtMost(80)
            ribbon.clear()
            for (k in 0 until nS) { val p = planner.ballAt(t - vis.trailLengthSec * k / (nS - 1)); ribbon.add(cam.sx(p.x), cam.sy(p.y)) }
            ribbon.draw(dl, trailColor, bpx * 2 * vis.trailWidth.toFloat(), vis.trailTaper.toFloat(), vis.trailOpacity.toFloat(), look == "neon" || look == "stones")
        }
        val bx = cam.sx(ball.x); val by = cam.sy(ball.y)
        val bloom = (vis.bloom * rs.bloomScale).toFloat()
        when (look) {
            "neon", "stones" -> {
                if (bloom > 0) { dl.glow(bx, by, bpx * 6f, trailColor, 0.5f * bloom); dl.glow(bx, by, bpx * 2.5f, palette.hero, 0.9f * bloom) }
                dl.circle(bx, by, bpx, palette.hero)
            }
            "pastel" -> {
                // Glass marble: translucent body, bright rim and highlight.
                if (bloom > 0) dl.glow(bx, by, bpx * 3f, palette.hero, 0.35f * bloom)
                dl.circle(bx, by, bpx, Colors.withAlpha(palette.hero, 0.45f))
                dl.circleStroke(bx, by, bpx * 0.95f, max(1.5f, bpx * 0.14f), Colors.withAlpha(palette.hero, 0.95f))
                dl.circle(bx - bpx * 0.35f, by - bpx * 0.35f, bpx * 0.22f, Colors.withAlpha(Colors.WHITE, 0.8f))
            }
            else -> {
                // Polished marble: base, darker lower rim, specular highlight.
                dl.circle(bx, by, bpx, Colors.scale(palette.hero, 0.72f))
                dl.circle(bx - bpx * 0.12f, by - bpx * 0.12f, bpx * 0.86f, palette.hero)
                dl.circle(bx - bpx * 0.38f, by - bpx * 0.38f, bpx * 0.28f, Colors.withAlpha(Colors.WHITE, 0.75f))
                if (look == "marble") dl.circleStroke(bx, by, bpx, max(1f, bpx * 0.08f), Colors.withAlpha(Colors.WHITE, 0.5f))
            }
        }
        // Impact sparkles (studio looks keep them subtle).
        for (c in cs) {
            val age = t - c.timeSec
            if (age < 0 || age > 0.6 || vis.particles <= 0.01) continue
            Bursts.draw(dl, ctx.seed, c.eventId, c.timeSec, t, cam.sx(c.position.x), cam.sy(c.position.y - planner.ballRadius), (8 * vis.particles * (0.5 + c.importance)).toInt(),
                speed = ppu * 5f, size = ppu * 0.1f, color = if (look == "neon" || look == "stones") trailColor else palette.surfaceLit, life = 0.5, gravity = ppu * 12f, drag = 3f,
                dirX = 0f, dirY = -1f, spread = 1.4f, shape = Bursts.SHAPE_CIRCLE, settings = rs)
        }
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
