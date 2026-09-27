package com.rhythmphysics.core.mechanic.square

import com.rhythmphysics.core.math.MathUtil
import com.rhythmphysics.core.math.Vec2
import com.rhythmphysics.core.mechanic.MechanicCheckpoint
import com.rhythmphysics.core.mechanic.MechanicContext
import com.rhythmphysics.core.mechanic.MechanicController
import com.rhythmphysics.core.mechanic.MechanicType
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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Music Square: a hero square travels a planned bounce course whose surfaces are generated from
 * future musical event times (see [SquareRoutePlanner]). There is no enclosing cage: the camera
 * follows a predictive average of the planned path, and surfaces live only around their moment.
 */
class SquareMechanic : MechanicController {
    override val type = MechanicType.SQUARE
    override var fastForward = false

    private lateinit var ctx: MechanicContext
    private lateinit var planner: SquareRoutePlanner
    private lateinit var palette: Palette
    private val camera = Camera2D()
    private val ribbon = Ribbon(96)
    private var fxEvents: List<MusicEvent> = emptyList()
    private var startTime = 0.0

    val routePlanner: SquareRoutePlanner get() = planner

    override fun loadSession(ctx: MechanicContext) {
        this.ctx = ctx
        palette = Palette.named(ctx.preset.visuals.palette)
        precompute(ctx.physicalEvents)
    }

    override fun precompute(events: List<MusicEvent>) {
        val gen = ctx.preset.generation
        val physical = com.rhythmphysics.core.music.EventMapper.forMechanic(events, gen.minContactGapSec)
            .filter { it.role.physical }
        startTime = min(ctx.windowStart, (physical.firstOrNull()?.timeSec ?: ctx.windowStart) - 0.6)
        planner = SquareRoutePlanner(
            physical, gen, ctx.aspect, ctx.preset.seed ?: ctx.seed, startTime,
            heroSize = ctx.preset.visuals.heroSize.coerceIn(0.4, 2.5),
            viewShort = 11.5 / ctx.preset.camera.zoom.coerceIn(0.4, 3.0),
        )
        fxEvents = ctx.fxEvents
        planner.ensure(ctx.windowStart)
    }

    override fun reset() {
        precompute(ctx.physicalEvents)
    }

    override fun fixedUpdate(dt: Double, songTime: Double) {
        planner.ensure(songTime + 1.2)
        val t0 = songTime - dt
        for (imp in planner.impacts) {
            if (imp.eventTimeSec <= t0) continue
            if (imp.eventTimeSec > songTime) break
            // Logical contact: when the simulated hero's leading edge actually reaches the surface
            // plane along its incoming line (measured from the evaluated route, not assumed).
            val contact = measureContact(imp, t0)
            ctx.sync.log(imp.eventId, imp.eventTimeSec, contact, "square")
            if (imp.degraded) ctx.sync.degraded()
            if (!fastForward) ctx.sink.onImpact(type, imp.eventTimeSec, imp.importance, imp.eventId, imp.note)
        }
        planner.prune(songTime)
    }

    private fun measureContact(imp: PlannedSquareImpact, t0: Double): Double {
        val n = imp.surfaceNormal
        // Plane: points x with (x - surfacePoint)·n = 0 where surfacePoint is on the face toward the hero.
        val face = if (imp.bounceAxis == BounceAxis.X) {
            val x = if (n.x > 0) imp.surfaceRect.right else imp.surfaceRect.x
            Vec2(x, imp.position.y)
        } else {
            val y = if (n.y > 0) imp.surfaceRect.top else imp.surfaceRect.y
            Vec2(imp.position.x, y)
        }
        val probeT = min(t0, imp.eventTimeSec - 1e-4)
        val p = planner.positionAt(probeT)
        val lead = p - n * planner.half // hero edge facing the surface
        val gap = (lead - face) dot n // positive while approaching
        val closing = -(imp.incomingVelocity dot n)
        return if (closing <= 1e-9) imp.eventTimeSec else probeT + gap / closing
    }

    // ---- rendering -------------------------------------------------------------------------

    private fun cameraCenter(t: Double, follow: Double, zoom: Double = 1.0): Vec2 {
        var sx = 0.0; var sy = 0.0; var sw = 0.0
        // Predictive: frame where the planned route goes over the next ~1.5 s (plus a little history).
        for (i in -4..16) {
            val d = i * 0.1
            val w = MathUtil.gaussian(d - 0.45, 0.6)
            val p = planner.positionAt(t + d)
            sx += p.x * w; sy += p.y * w; sw += w
        }
        val avg = Vec2(sx / sw, sy / sw)
        val a = planner.anchor(t)
        // Look ahead along the scroll direction so upcoming pegs get the larger share of the frame.
        var c = avg * (0.8) + a * 0.2
        val hero = planner.positionAt(t)
        val mx = planner.frameW * 0.30 / zoom / follow.coerceIn(0.3, 3.0)
        val my = planner.frameH * 0.30 / zoom / follow.coerceIn(0.3, 3.0)
        c = Vec2(MathUtil.clamp(c.x, hero.x - mx, hero.x + mx), MathUtil.clamp(c.y, hero.y - my, hero.y + my))
        return c
    }

    /**
     * Stateless adaptive zoom: tight passages (small spread of the planned path around now) zoom in
     * so the course fills the frame; wide passages zoom out. Clamped so the hero never gets small.
     */
    fun zoomAt(t: Double): Double {
        var sx = 0.0; var sy = 0.0; var sw = 0.0
        val xs = DoubleArray(23); val ys = DoubleArray(23); val ws = DoubleArray(23)
        for (i in 0 until 23) {
            val d = -0.8 + i * 0.1
            val w = MathUtil.gaussian(d - 0.3, 0.7)
            val p = planner.positionAt(t + d)
            xs[i] = p.x; ys[i] = p.y; ws[i] = w
            sx += p.x * w; sy += p.y * w; sw += w
        }
        val mx = sx / sw; val my = sy / sw
        var vx = 0.0; var vy = 0.0
        for (i in 0 until 23) { vx += ws[i] * (xs[i] - mx) * (xs[i] - mx); vy += ws[i] * (ys[i] - my) * (ys[i] - my) }
        val sdx = kotlin.math.sqrt(vx / sw); val sdy = kotlin.math.sqrt(vy / sw)
        val need = maxOf((4.4 * sdx + 4.0) / planner.frameW, (3.4 * sdy + 6.0) / planner.frameH)
        // Carved routes are followed by the camera; a near-constant scale keeps the carve width and
        // the small hero from visibly shrinking in spread-out passages.
        val lo = if (ctx.preset.visuals.background == "carved") 1.4 else 1.0
        return MathUtil.clamp(1.0 / need, lo, 1.6)
    }

    private fun setupCamera(vp: Viewport, t: Double) {
        camera.vp = vp
        camera.pixelsPerUnit = vp.unit / planner.viewShort * zoomAt(t)
        val c = cameraCenter(t, ctx.preset.camera.follow, camera.pixelsPerUnit / (vp.unit / planner.viewShort))
        camera.centerX = c.x; camera.centerY = c.y
    }

    override fun render(dl: DrawList, vp: Viewport, renderTime: Double, alpha: Float, rs: RenderSettings) {
        val t = renderTime
        val vis = ctx.preset.visuals
        val gen = ctx.preset.generation
        val light = Colors.luminance(palette.bgTop) > 0.5f
        setupCamera(vp, t)

        // Camera impulse on major impacts (disabled by accessibility settings).
        val lastImp = planner.impactAtOrBefore(t)
        if (lastImp != null && rs.shakeScale > 0f && lastImp.role == EventRole.MAJOR) {
            val age = t - lastImp.eventTimeSec
            if (age < 0.25) {
                val k = (1 - age / 0.25) * ctx.preset.camera.shake * 0.12 * rs.shakeScale
                camera.centerX -= lastImp.surfaceNormal.x * k
                camera.centerY -= lastImp.surfaceNormal.y * k
            }
        }

        dl.pushClip(vp.x, vp.y, vp.w, vp.h)
        if (vis.background == "carved") { renderCarved(dl, vp, t, rs); dl.popClip(); return }
        drawBackground(dl, vp, t, light)

        val heroPos = planner.positionAt(t)
        val colorRole = if (vis.colorShiftOnImpact) lastImp?.colorRole ?: 0 else 0
        val heroColor = if (vis.colorShiftOnImpact) palette.accent(colorRole) else palette.hero
        val ppu = camera.pixelsPerUnit.toFloat()
        val heroPx = (vis.heroSize * ppu).toFloat()

        // Surfaces (past, current, future). Drawn before the hero.
        for (imp in planner.impacts) {
            val age = t - imp.eventTimeSec
            val future = if (gen.showFutureSurfaces) imp.futureSec else minOf(0.35, imp.futureSec)
            if (age < -future || age > imp.lifeSec) continue
            drawSurface(dl, imp, age, future, imp.lifeSec, rs, light)
        }

        // Trail from actual (planned) past positions.
        drawTrail(dl, t, heroColor, heroPx, rs)

        // Impact effects.
        for (imp in planner.impacts) {
            val age = t - imp.eventTimeSec
            if (age < 0 || age > 0.8) continue
            drawImpactFx(dl, imp, age, t, heroPx, rs)
        }

        // Hero with squash & stretch.
        drawHero(dl, heroPos, heroColor, heroPx, lastImp, t, rs)

        // FX-only events: subtle hero pulse/sparkle (weak onsets never move the hero).
        drawFxEvents(dl, t, heroPos, heroColor, heroPx, rs)

        // Major-hit screen flash (reduced-flash aware, never a full-strength strobe).
        if (lastImp != null && lastImp.role == EventRole.MAJOR && vis.impactFlash > 0) {
            val age = t - lastImp.eventTimeSec
            if (age in 0.0..0.18) {
                val a = ((1 - age / 0.18) * 0.045 * vis.impactFlash * rs.flashScale).toFloat()
                dl.rect(vp.x, vp.y, vp.w, vp.h, Colors.withAlpha(heroColor, a))
            }
        }
        dl.popClip()
    }

    /**
     * MIDI-Playground look: a solid field with the route carved out of it. Every position the square
     * has occupied (swept as a blocky, axis-aligned corridor wider than the square) stays carved;
     * each bounce leaves a colored tick on the corridor wall; the hero is a small outline square.
     */
    private fun renderCarved(dl: DrawList, vp: Viewport, t: Double, rs: RenderSettings) {
        val vis = ctx.preset.visuals
        dl.rect(vp.x, vp.y, vp.w, vp.h, palette.bgTop)
        // The reference keeps the square near the middle of the frame with the carved past around it.
        val focus = planner.positionAt(t)
        camera.centerX = focus.x; camera.centerY = focus.y
        val ppu = camera.pixelsPerUnit
        val halfW = vp.w / ppu / 2 + 2; val halfH = vp.h / ppu / 2 + 2
        val cx = camera.centerX; val cy = camera.centerY
        val half = vis.heroSize * 0.5 * vis.carveWidth.coerceIn(1.0, 8.0) // corridor half-width in world units
        val carve = palette.bgBottom
        // Route points up to t: pruned history, live impacts, then the hero itself.
        val pts = ArrayList<DoubleArray>(planner.history.size + planner.impacts.size + 2)
        pts += doubleArrayOf(planner.positionAt(0.0).x, planner.positionAt(0.0).y)
        for (h in planner.history) if (h[0] <= t) pts += doubleArrayOf(h[1], h[2])
        for (imp in planner.impacts) if (imp.eventTimeSec <= t) pts += doubleArrayOf(imp.position.x, imp.position.y)
        val hero = planner.positionAt(t)
        pts += doubleArrayOf(hero.x, hero.y)
        // Each segment between bounces is cut into short pieces and every piece carves its
        // axis-aligned bounding box (inflated by the corridor half-width): straight runs where the
        // square travels along an axis, blocky staircases on diagonals, as in the reference footage.
        val piece = half * 1.5
        for (i in 1 until pts.size) {
            val ax = pts[i - 1][0]; val ay = pts[i - 1][1]; val bx = pts[i][0]; val by = pts[i][1]
            if (maxOf(ax, bx) + half < cx - halfW || minOf(ax, bx) - half > cx + halfW ||
                maxOf(ay, by) + half < cy - halfH || minOf(ay, by) - half > cy + halfH) continue
            val n = kotlin.math.max(1, kotlin.math.ceil(kotlin.math.hypot(bx - ax, by - ay) / piece).toInt())
            for (k in 0 until n) {
                val px0 = ax + (bx - ax) * k / n; val py0 = ay + (by - ay) * k / n
                val px1 = ax + (bx - ax) * (k + 1) / n; val py1 = ay + (by - ay) * (k + 1) / n
                val x0 = minOf(px0, px1) - half; val x1 = maxOf(px0, px1) + half
                val y0 = minOf(py0, py1) - half; val y1 = maxOf(py0, py1) + half
                dl.rect(camera.sx(x0), camera.sy(y1), camera.len(x1 - x0), camera.len(y1 - y0), carve)
            }
        }
        // Bounce ticks on the corridor wall (persist for the whole song).
        val tickLen = camera.len(vis.heroSize * 0.9); val tickW = kotlin.math.max(3f, camera.len(vis.heroSize * 0.18))
        fun tick(x: Double, y: Double, nx: Double, ny: Double, role: Int) {
            val wx = x - nx * half; val wy = y - ny * half
            val sx = camera.sx(wx); val sy = camera.sy(wy)
            val col = palette.accent(role)
            if (kotlin.math.abs(nx) > kotlin.math.abs(ny)) dl.rect(sx - tickW / 2, sy - tickLen / 2, tickW, tickLen, col)
            else dl.rect(sx - tickLen / 2, sy - tickW / 2, tickLen, tickW, col)
        }
        for (h in planner.history) if (h[0] <= t) tick(h[1], h[2], h[3], h[4], h[5].toInt())
        for (imp in planner.impacts) if (imp.eventTimeSec <= t) tick(imp.position.x, imp.position.y, imp.surfaceNormal.x, imp.surfaceNormal.y, imp.colorRole)
        // Impact sparks.
        val heroPx = (vis.heroSize * ppu).toFloat()
        for (imp in planner.impacts) {
            val age = t - imp.eventTimeSec
            if (age < 0 || age > 0.8) continue
            drawImpactFx(dl, imp, age, t, heroPx, rs)
        }
        // Hero: outline square with a soft glow.
        val x = camera.sx(hero.x); val y = camera.sy(hero.y)
        if (vis.bloom > 0) dl.glow(x, y, heroPx * 1.8f, palette.hero, (0.5 * vis.bloom).toFloat() * rs.bloomScale)
        dl.rectStroke(x - heroPx / 2, y - heroPx / 2, heroPx, heroPx, kotlin.math.max(2f, heroPx * 0.16f), palette.hero)
    }

    private fun drawBackground(dl: DrawList, vp: Viewport, t: Double, light: Boolean) {
        val vis = ctx.preset.visuals
        dl.gradientRect(vp.x, vp.y, vp.w, vp.h, palette.bgTop, palette.bgBottom)
        if (vis.background == "flat") return
        // World-anchored dot grid gives a sense of travel without clutter.
        val spacing = 1.5
        val ppu = camera.pixelsPerUnit
        val left = camera.centerX - vp.w / 2 / ppu
        val bottom = camera.centerY - vp.h / 2 / ppu
        val dotColor = if (light) Colors.withAlpha(palette.surface, 0.10f) else Colors.withAlpha(palette.text, 0.09f)
        val gx0 = floor(left / spacing) * spacing
        val gy0 = floor(bottom / spacing) * spacing
        val r = max(1.2f, vp.unit * 0.0022f)
        var gx = gx0
        while (gx < left + vp.w / ppu + spacing) {
            var gy = gy0
            while (gy < bottom + vp.h / ppu + spacing) {
                if (vis.background == "grid") dl.circle(camera.sx(gx), camera.sy(gy), r, dotColor)
                else dl.circle(camera.sx(gx), camera.sy(gy), r, dotColor)
                gy += spacing
            }
            gx += spacing
        }
        if (!light) {
            // soft vignette toward the edges
            dl.radialRect(vp.x, vp.y, vp.w, vp.h, vp.cx, vp.cy, max(vp.w, vp.h) * 0.75f, 0x00000000, 0x66000000)
        }
    }

    private fun drawSurface(dl: DrawList, imp: PlannedSquareImpact, age: Double, future: Double, life: Double, rs: RenderSettings, light: Boolean) {
        val vis = ctx.preset.visuals
        val r = imp.surfaceRect
        val x = camera.sx(r.x); val y = camera.sy(r.top)
        val w = camera.len(r.w); val h = camera.len(r.h)
        val radius = if (vis.sharpSurfaces) 0f else min(w, h) * 0.5f
        val accent = palette.accent(imp.colorRole)
        var alpha: Float
        var color: Int
        var scale = 1f
        if (age < 0) {
            // Upcoming surface: fades in, drawn a little dimmer so the active one reads first.
            val appear = MathUtil.clamp((future + age) / 0.35, 0.0, 1.0).toFloat()
            alpha = appear * 0.62f
            color = palette.surface
        } else {
            val hit = MathUtil.clamp(age / 0.45, 0.0, 1.0).toFloat()
            color = Colors.lerp(palette.surfaceLit, Colors.lerp(accent, palette.surface, 0.55f), hit)
            if (!vis.colorShiftOnImpact || !vis.surfaceMemoryTint) color = Colors.lerp(palette.surfaceLit, palette.surface, hit)
            val pulse = if (age < 0.2) (1 - age / 0.2).toFloat() else 0f
            scale = 1f + 0.12f * pulse * rs.motionScale
            val fadeOut = MathUtil.clamp((life - age) / 1.0, 0.0, 1.0).toFloat()
            alpha = fadeOut
            if (pulse > 0 && vis.bloom > 0) {
                dl.glow(x + w / 2, y + h / 2, max(w, h) * 0.9f, accent, pulse * vis.bloom.toFloat() * rs.bloomScale)
            }
        }
        val sw = w * (if (imp.bounceAxis == BounceAxis.Y) scale else 1f)
        val sh = h * (if (imp.bounceAxis == BounceAxis.X) scale else 1f)
        if (!light && !vis.outlineOnly) {
            dl.rect(x + (w - sw) / 2 + 3, y + (h - sh) / 2 + 5, sw, sh, Colors.withAlpha(0xFF000000.toInt(), 0.35f * alpha), radius)
        }
        if (vis.outlineOnly) {
            dl.rectStroke(x + (w - sw) / 2, y + (h - sh) / 2, sw, sh, max(2f, radius * 0.35f), Colors.withAlpha(color, alpha), radius)
            if (vis.bloom > 0) dl.glow(x + w / 2, y + h / 2, max(w, h) * 0.6f, color, alpha * 0.25f * vis.bloom.toFloat() * rs.bloomScale)
        } else {
            dl.rect(x + (w - sw) / 2, y + (h - sh) / 2, sw, sh, Colors.withAlpha(color, alpha), radius)
        }
    }

    private fun drawTrail(dl: DrawList, t: Double, color: Int, heroPx: Float, rs: RenderSettings) {
        val vis = ctx.preset.visuals
        if (vis.trailOpacity <= 0.01 || vis.trailLengthSec <= 0.01) return
        val n = rs.quality.trailSamples.coerceAtMost(90)
        val tint = vis.trailColor?.let { Colors.parse(it) } ?: color
        if (vis.stampCount > 0) {
            // "Bouncing squares" look: solid stamps at fixed time steps along the real path,
            // snapped to the stamp clock so they stay put while the hero moves on.
            val sp = vis.stampSpacingSec.coerceIn(0.03, 1.0)
            val newest = kotlin.math.floor(t / sp) * sp
            val n = vis.stampCount.coerceIn(1, 40)
            for (i in n - 1 downTo 0) {
                val ts = newest - i * sp
                if (ts < 0) continue
                val p = planner.positionAt(ts)
                val c = Colors.lerp(palette.accent(0), palette.hero, 1f - i.toFloat() / n)
                dl.rect(camera.sx(p.x) - heroPx / 2, camera.sy(p.y) - heroPx / 2, heroPx, heroPx, c)
            }
            return
        }
        if (vis.ghostTrail) {
            val ghosts = 6
            for (i in ghosts downTo 1) {
                val p = planner.positionAt(t - vis.trailLengthSec * i / ghosts)
                val a = (vis.trailOpacity * (1 - i.toDouble() / (ghosts + 1))).toFloat()
                val s = heroPx * (1 - 0.06f * i)
                dl.rectStroke(camera.sx(p.x) - s / 2, camera.sy(p.y) - s / 2, s, s, max(1.5f, heroPx * 0.05f), Colors.withAlpha(tint, a), s * 0.12f)
            }
            return
        }
        ribbon.clear()
        for (i in 0 until n) {
            val p = planner.positionAt(t - vis.trailLengthSec * i / (n - 1))
            ribbon.add(camera.sx(p.x), camera.sy(p.y))
        }
        val additive = vis.bloom > 0.3
        ribbon.draw(dl, tint, heroPx * vis.trailWidth.toFloat() * 0.78f, vis.trailTaper.toFloat(), vis.trailOpacity.toFloat(), additive)
    }

    private fun drawImpactFx(dl: DrawList, imp: PlannedSquareImpact, age: Double, t: Double, heroPx: Float, rs: RenderSettings) {
        val vis = ctx.preset.visuals
        val accent = palette.accent(imp.colorRole)
        val px = camera.sx(imp.position.x); val py = camera.sy(imp.position.y)
        // Expanding outline from the contact.
        if (age < 0.35 && vis.impactFlash > 0) {
            val u = (age / 0.35).toFloat()
            val s = heroPx * (1f + 1.5f * u * rs.motionScale)
            val a = (1 - u) * 0.75f * vis.impactFlash.toFloat() * rs.flashScale
            dl.rectStroke(px - s / 2, py - s / 2, s, s, max(2f, heroPx * 0.06f), Colors.withAlpha(accent, a), s * 0.14f)
        }
        // Particles fly off the surface along its normal.
        if (vis.particles > 0.01) {
            val n = imp.surfaceNormal
            val cx = px - (n.x * heroPx / 2).toFloat()
            val cy = py + (n.y * heroPx / 2).toFloat()
            val count = ((6 + 10 * imp.importance) * vis.particles).toInt()
            Bursts.draw(
                dl, ctx.seed, imp.eventId, imp.eventTimeSec, t, cx, cy, count,
                speed = heroPx * 7f, size = heroPx * 0.16f, color = accent, life = 0.55,
                gravity = heroPx * 6f, drag = 4.5f, dirX = n.x.toFloat(), dirY = (-n.y).toFloat(), spread = 1.0f,
                settings = rs,
            )
        }
    }

    private fun drawHero(dl: DrawList, pos: Vec2, color: Int, heroPx: Float, last: PlannedSquareImpact?, t: Double, rs: RenderSettings) {
        val vis = ctx.preset.visuals
        var sxScale = 1f; var syScale = 1f
        if (vis.squashStretch && last != null) {
            val age = t - last.eventTimeSec
            if (age in 0.0..0.12) {
                val q = ((1 - age / 0.12) * (1 - age / 0.12) * (0.14 + 0.14 * last.importance) * rs.motionScale).toFloat()
                if (last.bounceAxis == BounceAxis.X) { sxScale = 1 - q; syScale = 1 + q } else { syScale = 1 - q; sxScale = 1 + q }
            }
        }
        val x = camera.sx(pos.x); val y = camera.sy(pos.y)
        val w = heroPx * sxScale; val h = heroPx * syScale
        val radius = if (vis.stampCount > 0) 0f else heroPx * 0.14f
        if (vis.bloom > 0) dl.glow(x, y, heroPx * 1.6f, color, (0.35 * vis.bloom * vis.emissive).toFloat() * rs.bloomScale)
        val light = Colors.luminance(palette.bgTop) > 0.5f
        if (!light) dl.rect(x - w / 2 + 3, y - h / 2 + 6, w, h, 0x55000000, radius)
        if (vis.outlineOnly) {
            dl.rect(x - w / 2, y - h / 2, w, h, palette.bgBottom, radius) // hides the trail inside
            dl.rect(x - w / 2, y - h / 2, w, h, Colors.withAlpha(color, 0.18f), radius)
            dl.rectStroke(x - w / 2, y - h / 2, w, h, heroPx * 0.12f, color, radius)
        } else {
            dl.rect(x - w / 2, y - h / 2, w, h, color, radius)
            if (!light && vis.stampCount == 0) {
                // subtle top highlight for a crafted look
                dl.rect(x - w / 2 + w * 0.12f, y - h / 2 + h * 0.10f, w * 0.76f, h * 0.18f, Colors.withAlpha(Colors.WHITE, 0.22f), radius * 0.6f)
            }
        }
    }

    private fun drawFxEvents(dl: DrawList, t: Double, heroPos: Vec2, color: Int, heroPx: Float, rs: RenderSettings) {
        val vis = ctx.preset.visuals
        if (vis.particles <= 0.01) return
        // binary search to the window [t-0.4, t]
        var lo = 0; var hi = fxEvents.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (fxEvents[mid].timeSec < t - 0.4) lo = mid + 1 else hi = mid }
        var i = lo
        while (i < fxEvents.size && fxEvents[i].timeSec <= t) {
            val e = fxEvents[i]
            if (e.role == EventRole.FX_ONLY) {
                val age = t - e.timeSec
                val a = ((1 - age / 0.4) * 0.35 * e.importance * rs.flashScale).toFloat()
                val s = heroPx * (1.1f + 0.6f * (age / 0.4).toFloat())
                dl.rectStroke(camera.sx(heroPos.x) - s / 2, camera.sy(heroPos.y) - s / 2, s, s, max(1.5f, heroPx * 0.035f), Colors.withAlpha(color, a), s * 0.16f)
            }
            i++
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------

    override fun createCheckpoint(): MechanicCheckpoint {
        val w = StateWriter()
        planner.write(w)
        return MechanicCheckpoint(type, w.bytes())
    }

    override fun restoreCheckpoint(checkpoint: MechanicCheckpoint) {
        planner.read(StateReader(checkpoint.bytes))
    }

    override fun heroScreenPosition(vp: Viewport, renderTime: Double): FloatArray {
        setupCamera(vp, renderTime)
        val p = planner.positionAt(renderTime)
        return floatArrayOf(camera.sx(p.x), camera.sy(p.y), (camera.pixelsPerUnit * ctx.preset.visuals.heroSize).toFloat())
    }

    override fun heroColor(renderTime: Double): Int {
        val imp = planner.impactAtOrBefore(renderTime)
        return if (ctx.preset.visuals.colorShiftOnImpact) palette.accent(imp?.colorRole ?: 0) else palette.hero
    }

    /**
     * Composition metrics used by the regression gauntlet (the "bad Square" negative fixture had a
     * tiny hero, a permanent cage and a mostly dead canvas).
     * Returns: heroFraction (hero px / short side), coverage (bbox of hero+visible surfaces / viewport
     * area), visibleSurfaces.
     */
    fun composition(vp: Viewport, t: Double): Triple<Double, Double, Int> {
        setupCamera(vp, t)
        val hero = planner.positionAt(t)
        var minX = camera.sx(hero.x).toDouble(); var maxX = minX
        var minY = camera.sy(hero.y).toDouble(); var maxY = minY
        var n = 0
        for (imp in planner.impacts) {
            val age = t - imp.eventTimeSec
            if (age < -imp.futureSec || age > imp.lifeSec) continue
            val r = imp.surfaceRect
            val x0 = camera.sx(r.x).toDouble(); val x1 = camera.sx(r.right).toDouble()
            val y0 = camera.sy(r.top).toDouble(); val y1 = camera.sy(r.y).toDouble()
            if (x1 < vp.x || x0 > vp.x + vp.w || y1 < vp.y || y0 > vp.y + vp.h) continue
            n++
            minX = minOf(minX, x0.coerceAtLeast(vp.x.toDouble())); maxX = maxOf(maxX, x1.coerceAtMost((vp.x + vp.w).toDouble()))
            minY = minOf(minY, y0.coerceAtLeast(vp.y.toDouble())); maxY = maxOf(maxY, y1.coerceAtMost((vp.y + vp.h).toDouble()))
        }
        val heroFrac = camera.pixelsPerUnit * ctx.preset.visuals.heroSize / vp.unit
        return Triple(heroFrac, (maxX - minX) * (maxY - minY) / (vp.w * vp.h), n)
    }

    override fun debugLines(): List<String> {
        val next = planner.impacts.firstOrNull()
        return listOf(
            "planned ${planner.impacts.size} (next ev #${planner.nextEvent}/${planner.totalEvents})",
            "degraded ${planner.degradedCount}",
        ) + (next?.let { listOf("window from ${"%.2f".format(it.eventTimeSec)}s") } ?: emptyList())
    }

    @Suppress("unused")
    private fun abs2(v: Double) = abs(v)
}
