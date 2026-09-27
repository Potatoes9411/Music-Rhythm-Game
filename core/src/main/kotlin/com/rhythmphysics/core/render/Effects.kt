package com.rhythmphysics.core.render

import com.rhythmphysics.core.rng.SeededRng
import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Quality tier. Only affects visuals; never the simulation or musical timing. */
@Serializable
enum class Quality(val particleScale: Float, val trailSamples: Int, val glowScale: Float, val detail: Int) {
    LOW(0.35f, 16, 0.6f, 12), MEDIUM(0.65f, 28, 0.85f, 18), HIGH(1f, 44, 1f, 24), ULTRA(1.4f, 64, 1.1f, 32);
    val label get() = name.lowercase().replaceFirstChar(Char::uppercase)
}

/** Render-only settings (quality + accessibility). Changing these can never change simulation state. */
@Serializable
data class RenderSettings(
    val quality: Quality = Quality.HIGH,
    val reducedFlash: Boolean = false,
    val reducedMotion: Boolean = false,
    val cameraShake: Boolean = true,
    val reducedBloom: Boolean = false,
    val reducedParticles: Boolean = false,
    val showDebug: Boolean = false,
    /** Hides everything that is not the visualization (creator/recording output). */
    val cleanOutput: Boolean = false,
) {
    val flashScale get() = if (reducedFlash) 0.3f else 1f
    val bloomScale get() = (if (reducedBloom) 0.35f else 1f) * quality.glowScale
    val particleScale get() = (if (reducedParticles) 0.3f else 1f) * quality.particleScale
    val shakeScale get() = if (!cameraShake || reducedMotion) 0f else 1f
    val motionScale get() = if (reducedMotion) 0.4f else 1f
}

/** Allocation-free deterministic hashing for stateless visuals. */
object Hash {
    fun unit(seed: Long, a: Long, b: Long): Double {
        val z = SeededRng.mix(SeededRng.mix(seed + a * -0x61c8864680b583ebL) + b * 0x632BE59BD9B4E019L)
        return (z ushr 11) * (1.0 / (1L shl 53))
    }
    fun signed(seed: Long, a: Long, b: Long) = unit(seed, a, b) * 2 - 1
}

/**
 * Stateless particle bursts: the position of every particle is an analytic function of
 * (impact time, impact index, particle index, now). Seeking, pausing, replays and offline export
 * therefore always show identical particles, and nothing is allocated per impact.
 */
object Bursts {
    const val SHAPE_SQUARE = 0
    const val SHAPE_CIRCLE = 1
    const val SHAPE_SPARK = 2

    /**
     * Draws burst #[key] spawned at screen (x0,y0) at [t0], evaluated at [now]. Velocities are in
     * screen px/s; gravity in px/s^2 (screen y down). [dirX],[dirY] bias the emission (0,0 = radial).
     */
    fun draw(
        dl: DrawList, seed: Long, key: Long, t0: Double, now: Double,
        x0: Float, y0: Float, count: Int, speed: Float, size: Float, color: Int,
        life: Double = 0.7, gravity: Float = 900f, drag: Float = 3.2f,
        dirX: Float = 0f, dirY: Float = 0f, spread: Float = 3.14159f, shape: Int = SHAPE_SQUARE,
        settings: RenderSettings,
    ) {
        val age = now - t0
        if (age < 0 || age > life) return
        val n = (count * settings.particleScale).toInt().coerceAtLeast(if (count > 0) 1 else 0)
        val baseAngle = if (dirX == 0f && dirY == 0f) 0.0 else atan2(dirY.toDouble(), dirX.toDouble())
        val k = drag.toDouble()
        val e = exp(-k * age)
        for (i in 0 until n) {
            val li = i.toLong()
            val ang = if (dirX == 0f && dirY == 0f) Hash.unit(seed, key, li * 4) * 2 * Math.PI
            else baseAngle + Hash.signed(seed, key, li * 4) * spread
            val sp = speed * (0.35 + 0.65 * Hash.unit(seed, key, li * 4 + 1))
            val lifeI = life * (0.55 + 0.45 * Hash.unit(seed, key, li * 4 + 2))
            if (age > lifeI) continue
            val vx = cos(ang) * sp; val vy = sin(ang) * sp
            // p(t) = p0 + (g/k)t + (v0 - g/k)(1-e^{-kt})/k  (gravity only on y)
            val px = x0 + vx * (1 - e) / k
            val py = y0 + gravity / k * age + (vy - gravity / k) * (1 - e) / k
            val fade = (1 - age / lifeI).toFloat()
            val sz = size * (0.5f + 0.5f * Hash.unit(seed, key, li * 4 + 3).toFloat()) * (0.4f + 0.6f * fade)
            val c = Colors.withAlpha(color, fade)
            when (shape) {
                SHAPE_CIRCLE -> dl.circle(px.toFloat(), py.toFloat(), sz / 2, c)
                SHAPE_SPARK -> {
                    val vxT = vx * e; val vyT = vy * e + gravity / k * (1 - e)
                    val vl = hypot(vxT, vyT).coerceAtLeast(1e-3)
                    val len = (sz * 2.2f)
                    dl.line(px.toFloat(), py.toFloat(), (px - vxT / vl * len).toFloat(), (py - vyT / vl * len).toFloat(), sz * 0.45f, c)
                }
                else -> dl.rectCentered(px.toFloat(), py.toFloat(), sz, sz, c, sz * 0.15f, (ang * 57.3 + age * 360 * Hash.signed(seed, key, li)).toFloat())
            }
        }
    }
}

/** Builds tapered, fading ribbons from sampled points (trails). */
class Ribbon(capacity: Int = 128) {
    val xs = FloatArray(capacity)
    val ys = FloatArray(capacity)
    /** 0 = head (newest), 1 = tail */
    var n = 0
    private val qx = FloatArray(4)
    private val qy = FloatArray(4)

    fun clear() { n = 0 }
    fun add(x: Float, y: Float) { if (n < xs.size) { xs[n] = x; ys[n] = y; n++ } }

    /**
     * Draws the ribbon from head (index 0) to tail. Width tapers from [headWidth] to headWidth*(1-taper);
     * alpha fades from [headAlpha] to 0.
     */
    fun draw(dl: DrawList, color: Int, headWidth: Float, taper: Float, headAlpha: Float, additive: Boolean) {
        if (n < 2) return
        if (additive) dl.blend(DrawList.Blend.ADD)
        var pLx = 0f; var pLy = 0f; var pRx = 0f; var pRy = 0f
        for (i in 0 until n) {
            // normal from neighbors
            val i0 = maxOf(0, i - 1); val i1 = minOf(n - 1, i + 1)
            var dx = xs[i1] - xs[i0]; var dy = ys[i1] - ys[i0]
            val l = sqrt(dx * dx + dy * dy)
            if (l < 1e-4f) { dx = 1f; dy = 0f } else { dx /= l; dy /= l }
            val u = i.toFloat() / (n - 1)
            val w = headWidth * (1 - taper * u) / 2
            val lx = xs[i] - dy * w; val ly = ys[i] + dx * w
            val rx = xs[i] + dy * w; val ry = ys[i] - dx * w
            if (i > 0) {
                val a = headAlpha * (1 - u) * (1 - u * 0.3f)
                qx[0] = pLx; qy[0] = pLy; qx[1] = lx; qy[1] = ly; qx[2] = rx; qy[2] = ry; qx[3] = pRx; qy[3] = pRy
                dl.quad(qx[0], qy[0], qx[1], qy[1], qx[2], qy[2], qx[3], qy[3], Colors.withAlpha(color, a))
            }
            pLx = lx; pLy = ly; pRx = rx; pRy = ry
        }
        if (additive) dl.blend(DrawList.Blend.NORMAL)
    }
}
