package com.rhythmphysics.core.mechanic.arch

import com.rhythmphysics.core.math.MathUtil
import com.rhythmphysics.core.math.Vec3
import com.rhythmphysics.core.mechanic.MechanicCheckpoint
import com.rhythmphysics.core.mechanic.MechanicContext
import com.rhythmphysics.core.mechanic.MechanicController
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.music.EventMapper
import com.rhythmphysics.core.music.EventRole
import com.rhythmphysics.core.music.MusicEvent
import com.rhythmphysics.core.render.Bursts
import com.rhythmphysics.core.render.Camera3D
import com.rhythmphysics.core.render.Colors
import com.rhythmphysics.core.render.Draw3D
import com.rhythmphysics.core.render.DrawList
import com.rhythmphysics.core.render.Palette
import com.rhythmphysics.core.render.RenderSettings
import com.rhythmphysics.core.render.Ribbon
import com.rhythmphysics.core.render.Viewport
import com.rhythmphysics.core.util.StateReader
import com.rhythmphysics.core.util.StateWriter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Arch: a glowing hero travels musical ballistic arcs (Bounce Curve) or guided swoops (Pillar Weave)
 * through targets placed at event times, framed by a predictive camera director that re-composes
 * for 9:16 / 16:9 / 1:1 instead of cropping.
 */
class ArchMechanic : MechanicController {
    override val type = MechanicType.ARCH
    override var fastForward = false

    private lateinit var ctx: MechanicContext
    lateinit var planner: ArchPlanner; private set
    private lateinit var palette: Palette
    private val cam = Camera3D()
    private val ribbon = Ribbon(128)

    override fun loadSession(ctx: MechanicContext) {
        this.ctx = ctx
        palette = Palette.named(ctx.preset.visuals.palette)
        precompute(ctx.physicalEvents)
    }

    override fun precompute(events: List<MusicEvent>) {
        val gen = ctx.preset.generation
        val physical = EventMapper.forMechanic(events, gen.minContactGapSec).filter { it.role.physical }
        val style = if (gen.style == "pillar_weave") ArchStyle.PILLAR_WEAVE else ArchStyle.BOUNCE_CURVE
        val start = min(ctx.windowStart, (physical.firstOrNull()?.timeSec ?: ctx.windowStart) - 0.8)
        planner = ArchPlanner(physical, gen, style, ctx.aspect, ctx.preset.seed ?: ctx.seed, start)
        planner.ensure(ctx.windowStart)
    }

    override fun reset() = precompute(ctx.physicalEvents)

    override fun fixedUpdate(dt: Double, songTime: Double) {
        planner.ensure(songTime + 1.5)
        val t0 = songTime - dt
        for (tg in planner.targets) {
            if (tg.timeSec <= t0) continue
            if (tg.timeSec > songTime) break
            // Logical contact: when the evaluated hero trajectory actually reaches the target top.
            val contact = measureContact(tg, t0, songTime)
            ctx.sync.log(tg.eventId, tg.timeSec, contact, "arch")
            if (!fastForward) ctx.sink.onImpact(type, tg.timeSec, tg.importance, tg.eventId, tg.note)
        }
        planner.prune(songTime)
    }

    /**
     * Time at which the evaluated hero path is closest to the contact point (target top + radius).
     * Euclidean distance has a single zero at the real touch; (a signed height + horizontal sum
     * would also vanish below the top, which guided swoops pass through).
     */
    private fun measureContact(tg: ArchTarget, t0: Double, t1: Double): Double {
        val top = tg.topY + planner.heroRadius
        fun gap(t: Double): Double {
            val p = planner.heroAt(t)
            val dx = p.x - tg.x; val dy = p.y - top; val dz = p.z - tg.z
            return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        }
        var lo = max(t0 - 0.02, tg.timeSec - 0.05); var hi = min(t1 + 0.02, tg.timeSec + 0.05)
        var best = tg.timeSec; var bestV = Double.MAX_VALUE
        // coarse scan then refine around the minimum
        for (i in 0..40) { val t = lo + (hi - lo) * i / 40; val v = abs(gap(t)); if (v < bestV) { bestV = v; best = t } }
        lo = best - (hi - lo) / 40; hi = best + (hi - lo) / 40
        repeat(30) {
            val m1 = lo + (hi - lo) / 3; val m2 = hi - (hi - lo) / 3
            // Ties go left: when the hero rests on the final target the distance is 0 over an
            // interval and the contact is its start (the arrival), not its middle.
            if (abs(gap(m1)) <= abs(gap(m2))) hi = m2 else lo = m1
        }
        return (lo + hi) / 2
    }

    // ---- camera director ----------------------------------------------------------------------

    private class CamPose(val eye: Vec3, val look: Vec3)

    private var poiTargets = 3

    private fun poiAt(t: Double): List<Vec3> {
        val pts = ArrayList<Vec3>(8)
        pts += planner.heroAt(t)
        pts += planner.heroAt(t + 0.3)
        val seg = planner.segmentAt(t)
        if (seg != null && t <= seg.t1) {
            val mid = seg.at((seg.t0 + seg.t1) / 2, planner.lateral)
            pts += Vec3(mid.x, max(mid.y, seg.apexY), mid.z)
        }
        for (tg in planner.targetsAfter(t, poiTargets)) { pts += Vec3(tg.x, tg.topY, tg.z); if (planner.style == ArchStyle.PILLAR_WEAVE) pts += Vec3(tg.x, tg.topY * 0.35, tg.z) }
        return pts
    }

    /** Ideal camera for time t: low cinematic angle, re-composed per aspect, distance fitted to content. */
    private fun idealPose(vp: Viewport, t: Double): CamPose {
        poiTargets = if (vp.isLandscape) 2 else 3
        val pts = poiAt(t)
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        for ((i, p) in pts.withIndex()) { val w = if (i == 0) 2.0 else 1.0; cx += p.x * w; cy += p.y * w; cz += p.z * w }
        val wsum = pts.size + 1.0
        var center = Vec3(cx / wsum, cy / wsum, cz / wsum)
        val cp = ctx.preset.camera
        val portrait = vp.isPortrait
        val landscape = vp.isLandscape
        val f = planner.forward; val l = planner.lateral
        // View direction: portrait looks along the course (higher pitch), landscape views from the side at 3/4.
        val pitch = Math.toRadians(if (portrait) cp.pitchDeg + 22 else if (landscape) cp.pitchDeg + 11 else cp.pitchDeg + 14)
        val yaw = Math.toRadians(if (landscape) -28.0 else 12.0)
        // horizontal view direction
        val baseDir = if (landscape) Vec3(-l.x, 0.0, -l.z) else f
        val hd = Vec3(baseDir.x * cos(yaw) - baseDir.z * sin(yaw), 0.0, baseDir.x * sin(yaw) + baseDir.z * cos(yaw)).normalized()
        val viewDir = Vec3(hd.x * cos(pitch), -sin(pitch), hd.z * cos(pitch))
        var dist = cp.distance
        cam.vp = vp; cam.fovYDeg = cp.fovDeg
        repeat(4) {
            cam.position = center - viewDir * dist
            cam.target = center
            cam.update()
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (p in pts) if (cam.project(p)) { minX = min(minX, cam.outX); maxX = max(maxX, cam.outX); minY = min(minY, cam.outY); maxY = max(maxY, cam.outY) }
            if (minX == Float.MAX_VALUE) { dist *= 1.3; return@repeat }
            val needW = (maxX - minX) / (vp.w * (if (landscape) 0.74f else if (portrait) 0.68f else 0.8f))
            val needH = (maxY - minY) / (vp.h * (if (portrait) 0.62f else if (landscape) 0.76f else 0.74f))
            val need = max(needW, needH).toDouble()
            dist = MathUtil.clamp(dist * MathUtil.clamp(need, 0.55, 1.8), 7.0, 40.0) / cp.zoom.coerceIn(0.4, 3.0)
            // Compose: half content-bbox centering, half placing the hero at its composition spot
            // (lower-middle in portrait so the upcoming course reads above it; left-third in landscape).
            var ox = ((minX + maxX) / 2 - vp.cx) / vp.w
            var oy = ((minY + maxY) / 2 - (vp.cy + vp.h * 0.04f)) / vp.h
            val h0 = pts[0]
            if (cam.project(h0)) {
                val tx = if (landscape) vp.x + vp.w * 0.38f else vp.cx
                val ty = if (portrait) vp.y + vp.h * 0.66f else vp.y + vp.h * 0.5f
                ox = 0.4f * ox + 0.6f * (cam.outX - tx) / vp.w
                oy = 0.4f * oy + 0.6f * (cam.outY - ty) / vp.h
            }
            val right = (viewDir cross Vec3.UP).normalized()
            val up = (right cross viewDir).normalized()
            // Visible frustum size at the look distance (world units per full viewport).
            val worldH = 2 * dist * kotlin.math.tan(Math.toRadians(cp.fovDeg) / 2)
            val worldW = worldH * vp.w / vp.h
            center = center + right * (ox * worldW * 0.85) - up * (oy * worldH * 0.85)
        }
        // Containment: pull back until every point of interest is inside the frame (5% margin).
        repeat(6) {
            cam.position = center - viewDir * dist; cam.target = center; cam.update()
            var out = false
            for (p in pts) {
                if (!cam.project(p)) { out = true; break }
                if (cam.outX < vp.x + vp.w * 0.04f || cam.outX > vp.x + vp.w * 0.96f || cam.outY < vp.y + vp.h * 0.04f || cam.outY > vp.y + vp.h * 0.96f) { out = true; break }
            }
            if (!out) return CamPose(center - viewDir * dist, center)
            dist = (dist * 1.12).coerceAtMost(48.0)
        }
        return CamPose(center - viewDir * dist, center)
    }

    private fun setupCamera(vp: Viewport, t: Double) {
        var ex = 0.0; var ey = 0.0; var ez = 0.0; var lx = 0.0; var ly = 0.0; var lz = 0.0; var ws = 0.0
        for (i in -3..9) {
            val d = i * 0.1
            val w = MathUtil.gaussian(d - 0.25, 0.4)
            val p = idealPose(vp, t + d)
            ex += p.eye.x * w; ey += p.eye.y * w; ez += p.eye.z * w
            lx += p.look.x * w; ly += p.look.y * w; lz += p.look.z * w; ws += w
        }
        cam.vp = vp; cam.fovYDeg = ctx.preset.camera.fovDeg
        cam.position = Vec3(ex / ws, max(ey / ws, 0.6), ez / ws)
        cam.target = Vec3(lx / ws, ly / ws, lz / ws)
        cam.update()
        // Hard guarantee: the hero stays inside the safe area (shift the view, deterministic).
        val h = planner.heroAt(t)
        repeat(3) {
            if (!cam.project(h)) return@repeat
            val mx = vp.w * 0.14f; val my = vp.h * 0.12f
            val dx = when { cam.outX < vp.x + mx -> cam.outX - (vp.x + mx); cam.outX > vp.x + vp.w - mx -> cam.outX - (vp.x + vp.w - mx); else -> 0f }
            val dy = when { cam.outY < vp.y + my -> cam.outY - (vp.y + my); cam.outY > vp.y + vp.h - my -> cam.outY - (vp.y + vp.h - my); else -> 0f }
            if (dx == 0f && dy == 0f) return@repeat
            val viewDir = (cam.target - cam.position).normalized()
            val right = (viewDir cross Vec3.UP).normalized(); val up = (right cross viewDir).normalized()
            val k = 1.0 / cam.outScale.toDouble().coerceAtLeast(1e-6) // world units per pixel at the hero depth
            val shift = right * (dx * k) - up * (dy * k)
            cam.position = cam.position + shift; cam.target = cam.target + shift
            cam.update()
        }
    }

    // ---- rendering --------------------------------------------------------------------------

    override fun render(dl: DrawList, vp: Viewport, renderTime: Double, alpha: Float, rs: RenderSettings) {
        val t = renderTime
        val vis = ctx.preset.visuals
        setupCamera(vp, t)
        // Camera impulse on strong contacts.
        val lastT = planner.lastTargetAtOrBefore(t)
        if (lastT != null && rs.shakeScale > 0f) {
            val age = t - lastT.timeSec
            if (age < 0.2 && lastT.role == EventRole.MAJOR) {
                val k = (1 - age / 0.2) * ctx.preset.camera.shake * 0.08 * rs.shakeScale
                cam.position = cam.position + Vec3(0.0, -k, 0.0); cam.update()
            }
        }
        val d3 = Draw3D(cam, dl)
        dl.pushClip(vp.x, vp.y, vp.w, vp.h)
        // Studio: seamless cove (backdrop = the floor's far color), no horizon line.
        if (vis.background == "studio") dl.rect(vp.x, vp.y, vp.w, vp.h, palette.bgTop)
        else dl.gradientRect(vp.x, vp.y, vp.w, vp.h, palette.bgTop, palette.bgBottom)
        drawFloor(dl, d3, vp, t)

        val hero = planner.heroAt(t)
        val heroColor = palette.hero
        val warm = vis.trailColor?.let { Colors.parse(it) } ?: palette.trail
        val bloom = (vis.bloom * rs.bloomScale).toFloat()

        // Reflections on the dark floor (mirror y), faint.
        if (vis.reflections) {
            for (tg in planner.targets) {
                if (abs(tg.timeSec - t) > 6) continue
                drawTarget(d3, tg, t, rs, reflect = true)
            }
            if (cam.project(hero.x, -hero.y, hero.z)) dl.glow(cam.outX, cam.outY, (planner.heroRadius * cam.outScale * 4).toFloat(), warm, 0.18f * bloom)
        }

        // Painter's order: targets farther than the hero, then trail + hero, then nearer targets.
        val heroDepth = cam.depthOf(hero.x, hero.y, hero.z)
        val visible = planner.targets.filter { it.timeSec > t - 3.5 && it.timeSec < t + 5.0 }
            .sortedByDescending { cam.depthOf(it.x, it.topY, it.z) }
        for (tg in visible) if (cam.depthOf(tg.x, tg.topY, tg.z) > heroDepth) drawTarget(d3, tg, t, rs, false)
        drawTrail(dl, t, warm, vis, rs)
        d3.orb(hero.x, hero.y, hero.z, planner.heroRadius * vis.heroSize, heroColor, warm, 1f, bloom * vis.emissive.toFloat())
        for (tg in visible) if (cam.depthOf(tg.x, tg.topY, tg.z) <= heroDepth) drawTarget(d3, tg, t, rs, false)

        // Contact effects: floor ripples + sparks.
        for (tg in planner.targets) {
            val age = t - tg.timeSec
            if (age < 0 || age > 0.7) continue
            val accent = palette.accent(tg.colorRole)
            if (age < 0.5) {
                val u = age / 0.5
                d3.discStroke(tg.x, tg.topY + 0.01, tg.z, tg.radius * (1 + 0.9 * u * rs.motionScale), max(1.2f, vp.unit * 0.003f),
                    Colors.withAlpha(palette.surfaceLit, ((1 - u) * 0.55 * vis.impactFlash * rs.flashScale).toFloat()))
            }
            if (vis.particles > 0.01 && cam.project(tg.x, tg.topY + 0.1, tg.z)) {
                val s = (0.1 * cam.outScale).toFloat()
                Bursts.draw(dl, ctx.seed, tg.eventId, tg.timeSec, t, cam.outX, cam.outY, (10 * vis.particles * (0.5 + tg.importance)).toInt(),
                    speed = s * 40f, size = s * 0.9f, color = Colors.lerp(accent, warm, 0.5f), life = 0.6, gravity = s * 90f, drag = 3.5f,
                    dirX = 0f, dirY = -1f, spread = 1.3f, shape = Bursts.SHAPE_SPARK, settings = rs)
            }
        }
        dl.popClip()
    }

    private fun drawFloor(dl: DrawList, d3: Draw3D, vp: Viewport, t: Double) {
        val c = planner.heroAt(t)
        val r = 60.0
        val xs = FloatArray(4); val ys = FloatArray(4)
        val corners = listOf(Vec3(c.x - r, 0.0, c.z - r), Vec3(c.x + r, 0.0, c.z - r), Vec3(c.x + r, 0.0, c.z + r), Vec3(c.x - r, 0.0, c.z + r))
        // Floor quad is clipped roughly by projecting corners in front of the camera only.
        var n = 0
        for (p in corners) if (cam.project(p)) { xs[n] = cam.outX; ys[n] = cam.outY; n++ }
        val studio = ctx.preset.visuals.background == "studio"
        val floorTop = if (studio) palette.bgTop else Colors.scale(palette.bgBottom, 1.25f)
        val floorNear = if (studio) palette.bgBottom else Colors.scale(palette.bgBottom, 0.7f)
        if (n == 4) dl.poly(xs, ys, 4, floorTop, floorNear, ys.minOrNull() ?: 0f, ys.maxOrNull() ?: vp.h)
        else {
            // Camera sees the horizon: fill below the projected horizon line.
            val far = c + planner.forward * 80.0
            val hy = if (cam.project(far.x, 0.0, far.z)) cam.outY else vp.y + vp.h * 0.45f
            dl.gradientRect(vp.x, hy, vp.w, vp.y + vp.h - hy, floorTop, if (studio) palette.bgBottom else Colors.scale(palette.bgBottom, 0.6f))
        }
        if (studio) {
            // Studio spotlight: bright pool behind the course, falling off toward the camera.
            dl.radialRect(vp.x, vp.y, vp.w, vp.h, vp.cx, vp.y + vp.h * 0.12f, vp.unit * 0.95f, 0x40FFFFFF, 0x00FFFFFF)
            return
        }
        // Soft light pool under the course, and a faint horizon glow.
        d3.disc(c.x, 0.001, c.z, 5.5, Colors.withAlpha(palette.surfaceLit, 0.035f), 40)
        d3.disc(c.x, 0.002, c.z, 3.0, Colors.withAlpha(palette.surfaceLit, 0.035f), 40)
    }

    private fun drawTarget(d3: Draw3D, tg: ArchTarget, t: Double, rs: RenderSettings, reflect: Boolean) {
        val vis = ctx.preset.visuals
        val age = t - tg.timeSec
        val appear = MathUtil.clamp((4.8 + (-age).coerceAtMost(4.8)) / 0.6, 0.0, 1.0).toFloat().let { if (age > -4.2) 1f else it }
        val fade = MathUtil.clamp((3.5 - age) / 1.0, 0.0, 1.0).toFloat() * appear
        if (fade <= 0.01f) return
        val lit = if (age >= 0 && age < 0.45) (1 - age / 0.45).toFloat() else 0f
        val ySign = if (reflect) -1.0 else 1.0
        val ra = if (reflect) 0.16f else 1f
        val rad = tg.radius * vis.targetScale
        val thick = if (vis.background == "studio") 0.025 else 0.1
        if (planner.style == ArchStyle.PILLAR_WEAVE) {
            val side = Colors.lerp(palette.surface, palette.surfaceLit, 0.25f * lit)
            val capC = Colors.lerp(Colors.scale(palette.surface, 1.35f), palette.surfaceLit, lit)
            if (reflect) {
                d3.cylinder(tg.x, tg.z, tg.radius, 0.0, -tg.topY, Colors.withAlpha(side, 0.1f * fade), Colors.withAlpha(side, 0.0f), Colors.withAlpha(capC, 0.12f * fade), 18)
            } else {
                d3.cylinder(tg.x, tg.z, tg.radius, 0.0, tg.topY, Colors.withAlpha(Colors.scale(side, 1.15f), fade), Colors.withAlpha(Colors.scale(side, 0.35f), fade), Colors.withAlpha(capC, fade),
                    rs.quality.detail)
                d3.discStroke(tg.x, tg.topY, tg.z, tg.radius, 1.4f, Colors.withAlpha(palette.surfaceLit, 0.28f * fade + 0.6f * lit), 28)
                if (lit > 0 && cam.project(tg.x, tg.topY, tg.z)) d3.dl.glow(cam.outX, cam.outY, (tg.radius * cam.outScale * 3.2).toFloat(), palette.surfaceLit, lit * 0.6f * vis.bloom.toFloat() * rs.bloomScale)
            }
        } else {
            val base = palette.surface
            val top = Colors.lerp(base, palette.surfaceLit, 0.15f + 0.85f * lit)
            if (reflect) {
                d3.disc(tg.x, -0.1, tg.z, rad, Colors.withAlpha(top, 0.18f * fade * ra * 6f), 24)
            } else {
                d3.cylinder(tg.x, tg.z, rad, 0.0, thick * ySign, Colors.withAlpha(Colors.scale(base, 0.8f), fade), Colors.withAlpha(Colors.scale(base, 0.45f), fade),
                    Colors.withAlpha(top, fade), rs.quality.detail + 4)
                if (vis.background != "studio") d3.discStroke(tg.x, thick + 0.001, tg.z, rad * 0.97, 1.5f, Colors.withAlpha(palette.surfaceLit, 0.35f * fade + 0.6f * lit), 32)
                if (lit > 0 && cam.project(tg.x, thick, tg.z)) d3.dl.glow(cam.outX, cam.outY, (rad * cam.outScale * 2.6).toFloat(), palette.surfaceLit, lit * 0.55f * vis.bloom.toFloat() * rs.bloomScale)
            }
        }
    }

    private fun drawTrail(dl: DrawList, t: Double, color: Int, vis: com.rhythmphysics.core.preset.VisualParams, rs: RenderSettings) {
        if (vis.trailOpacity <= 0.01) return
        val n = rs.quality.trailSamples.coerceAtMost(100)
        ribbon.clear()
        var headWidth = 0f
        for (i in 0 until n) {
            val p = planner.heroAt(t - vis.trailLengthSec * i / (n - 1))
            if (!cam.project(p)) break
            ribbon.add(cam.outX, cam.outY)
            if (i == 0) headWidth = (planner.heroRadius * 2 * vis.trailWidth * cam.outScale).toFloat()
        }
        // Width capped relative to the hero so the trail never swallows the core.
        // On the light studio floor additive blending would wash the yellow to white.
        ribbon.draw(dl, color, headWidth * 0.9f, vis.trailTaper.toFloat(), (vis.trailOpacity * 0.9).toFloat(), additive = vis.background != "studio")
        if (vis.background == "studio") {
            // Hot white core along the streak, and its warm reflection smeared on the glossy floor.
            ribbon.draw(dl, Colors.lerp(color, Colors.WHITE, 0.75f), headWidth * 0.22f, vis.trailTaper.toFloat(), 0.9f, additive = true)
            ribbon.clear()
            for (i in 0 until n) {
                val p = planner.heroAt(t - vis.trailLengthSec * i / (n - 1))
                if (!cam.project(p.x, -p.y, p.z)) break
                ribbon.add(cam.outX, cam.outY)
            }
            ribbon.draw(dl, color, headWidth * 1.1f, vis.trailTaper.toFloat(), (vis.trailOpacity * 0.18).toFloat(), additive = true)
            return
        }
        if (vis.bloom > 0 && ribbon.n > 2) {
            // Controlled bloom along the recent part of the trail.
            for (i in 0 until min(ribbon.n, 10) step 2) {
                val a = (1 - i / 10f) * 0.12f * vis.bloom.toFloat() * rs.bloomScale
                dl.glow(ribbon.xs[i], ribbon.ys[i], headWidth * 1.6f, color, a)
            }
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------

    override fun createCheckpoint(): MechanicCheckpoint {
        val w = StateWriter(); planner.write(w); return MechanicCheckpoint(type, w.bytes())
    }

    override fun restoreCheckpoint(checkpoint: MechanicCheckpoint) = planner.read(StateReader(checkpoint.bytes))

    override fun heroScreenPosition(vp: Viewport, renderTime: Double): FloatArray? {
        setupCamera(vp, renderTime)
        val h = planner.heroAt(renderTime)
        if (!cam.project(h)) return null
        return floatArrayOf(cam.outX, cam.outY, (planner.heroRadius * 2 * cam.outScale).toFloat())
    }

    override fun heroColor(renderTime: Double): Int = palette.trail

    /**
     * Composition metrics for the Arch gauntlet (negative fixture: dead space, tiny targets, bottom strip).
     * [coverage, contentCenterY, minTargetSize, heroSize, fractionOfGuaranteedPoiInFrame]
     * Coverage/center use every course element visible on screen (hero, arc apex, next 3 targets);
     * the in-frame ratio uses what the director guarantees (hero, apex, next 2-3 targets).
     */
    fun composition(vp: Viewport, t: Double): DoubleArray {
        setupCamera(vp, t)
        fun inVp(x: Float, y: Float) = x in vp.x..(vp.x + vp.w) && y in vp.y..(vp.y + vp.h)
        val visible = ArrayList<FloatArray>()
        var guaranteed = 0; var guaranteedIn = 0
        val hero = planner.heroAt(t)
        var heroPx = 0f
        guaranteed++
        if (cam.project(hero)) { heroPx = (planner.heroRadius * 2 * cam.outScale).toFloat(); if (inVp(cam.outX, cam.outY)) { guaranteedIn++; visible += floatArrayOf(cam.outX, cam.outY) } }
        planner.segmentAt(t)?.let { seg ->
            if (t <= seg.t1) {
                val mid = seg.at((seg.t0 + seg.t1) / 2, planner.lateral)
                guaranteed++
                if (cam.project(mid.x, maxOf(mid.y, seg.apexY), mid.z) && inVp(cam.outX, cam.outY)) { guaranteedIn++; visible += floatArrayOf(cam.outX, cam.outY) }
            }
        }
        val nGuaranteed = if (vp.isLandscape) 2 else 3
        var minTargetPx = Float.MAX_VALUE
        for ((i, tg) in planner.targetsAfter(t, 3).withIndex()) {
            if (i < nGuaranteed) guaranteed++
            if (!cam.project(tg.x, tg.topY, tg.z)) continue
            val inside = inVp(cam.outX, cam.outY)
            if (i < nGuaranteed && inside) guaranteedIn++
            if (inside) {
                visible += floatArrayOf(cam.outX, cam.outY); minTargetPx = min(minTargetPx, (tg.radius * 2 * cam.outScale).toFloat())
                if (cam.project(tg.x, 0.0, tg.z) && inVp(cam.outX, cam.outY)) visible += floatArrayOf(cam.outX, cam.outY)
            }
        }
        if (visible.isEmpty()) return doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0)
        val minX = visible.minOf { it[0] }; val maxX = visible.maxOf { it[0] }; val minY = visible.minOf { it[1] }; val maxY = visible.maxOf { it[1] }
        val coverage = ((maxX - minX) * (maxY - minY)).toDouble() / (vp.w * vp.h)
        val centerY = ((minY + maxY) / 2 - vp.y) / vp.h
        if (minTargetPx == Float.MAX_VALUE) minTargetPx = 0f
        return doubleArrayOf(coverage, centerY.toDouble(), (minTargetPx / vp.unit).toDouble(), (heroPx / vp.unit).toDouble(), guaranteedIn.toDouble() / guaranteed)
    }

    override fun debugLines(): List<String> = listOf(
        "arch ${planner.style} targets ${planner.targets.size} next #${planner.nextEvent}/${planner.totalEvents}",
        "rejected candidates ${planner.rejectedCandidates}",
        planner.segmentAt(planner.targets.firstOrNull()?.timeSec ?: 0.0)?.let { if (it.guided) "motion: guided swoop (not free physics)" else "motion: ballistic g=%.1f".format(it.g) } ?: "",
    )
}
