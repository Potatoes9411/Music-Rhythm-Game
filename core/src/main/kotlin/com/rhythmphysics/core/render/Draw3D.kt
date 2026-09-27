package com.rhythmphysics.core.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared 3D primitives drawn through [Camera3D] into a [DrawList] (CPU projection, painter's
 * algorithm). Used by Arch and Platform so both stay one renderer with one set of effects.
 */
class Draw3D(val cam: Camera3D, val dl: DrawList) {
    private val xs = FloatArray(128)
    private val ys = FloatArray(128)

    /** Projects a horizontal circle (y = const) as a polygon; returns point count (0 if clipped). */
    private fun ring(cx: Double, cy: Double, cz: Double, r: Double, n: Int, outX: FloatArray, outY: FloatArray, off: Int): Boolean {
        for (i in 0 until n) {
            val a = 2 * PI * i / n
            if (!cam.project(cx + r * cos(a), cy, cz + r * sin(a))) return false
            outX[off + i] = cam.outX; outY[off + i] = cam.outY
        }
        return true
    }

    /** Flat horizontal disc (e.g. a target pad or a floor ripple). */
    fun disc(cx: Double, y: Double, cz: Double, r: Double, color: Int, segments: Int = 28) {
        val n = segments.coerceIn(8, 64)
        if (!ring(cx, y, cz, r, n, xs, ys, 0)) return
        dl.poly(xs, ys, n, color)
    }

    fun discStroke(cx: Double, y: Double, cz: Double, r: Double, width: Float, color: Int, segments: Int = 36) {
        val n = segments.coerceIn(8, 64)
        if (!ring(cx, y, cz, r, n, xs, ys, 0)) return
        for (i in 0 until n) {
            val j = (i + 1) % n
            dl.line(xs[i], ys[i], xs[j], ys[j], width, color, roundCap = true)
        }
    }

    /**
     * Vertical cylinder from y0 to y1: silhouette side (vertical gradient) then the top cap.
     * Works for pillars and thick pads.
     */
    fun cylinder(cx: Double, cz: Double, r: Double, y0: Double, y1: Double, sideTop: Int, sideBottom: Int, cap: Int, segments: Int = 24) {
        val n = segments.coerceIn(8, 48)
        val bx = FloatArray(n); val by = FloatArray(n); val tx = FloatArray(n); val ty = FloatArray(n)
        if (!ring(cx, y0, cz, r, n, bx, by, 0)) return
        if (!ring(cx, y1, cz, r, n, tx, ty, 0)) return
        // Silhouette: leftmost/rightmost points of both rings form the side quad (convex hull approx).
        var minI = 0; var maxI = 0
        for (i in 1 until n) { if (tx[i] + bx[i] < tx[minI] + bx[minI]) minI = i; if (tx[i] + bx[i] > tx[maxI] + bx[maxI]) maxI = i }
        // Build side polygon: top arc (front half) from minI to maxI, bottom arc back.
        var k = 0
        // front half = points with larger screen y on the bottom ring (closer to camera when looking down)
        val front = frontArc(bx, by, n, minI, maxI)
        for (i in front) { xs[k] = bx[i]; ys[k] = by[i]; k++ }
        for (i in front.reversed()) { if (k >= xs.size) break; xs[k] = tx[i]; ys[k] = ty[i]; k++ }
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until k) { minY = minOf(minY, ys[i]); maxY = maxOf(maxY, ys[i]) }
        dl.poly(xs, ys, k, sideTop, sideBottom, minY, maxY)
        // top cap
        dl.poly(tx, ty, n, cap)
    }

    /** Indices from minI to maxI along the arc that faces the viewer (higher average screen y). */
    private fun frontArc(x: FloatArray, y: FloatArray, n: Int, minI: Int, maxI: Int): List<Int> {
        val a = ArrayList<Int>(); var i = minI
        while (true) { a += i; if (i == maxI) break; i = (i + 1) % n }
        val b = ArrayList<Int>(); i = minI
        while (true) { b += i; if (i == maxI) break; i = (i - 1 + n) % n }
        val ay = a.sumOf { y[it].toDouble() } / a.size
        val byy = b.sumOf { y[it].toDouble() } / b.size
        return if (ay >= byy) a else b
    }

    /** Glowing sphere: bloom halo + solid core, sized by perspective. */
    fun orb(x: Double, y: Double, z: Double, r: Double, core: Int, glowColor: Int, glowScale: Float, bloom: Float): Boolean {
        if (!cam.project(x, y, z)) return false
        val px = cam.outX; val py = cam.outY; val pr = (r * cam.outScale).toFloat()
        if (bloom > 0f) {
            dl.glow(px, py, pr * 5.5f * glowScale, glowColor, 0.35f * bloom)
            dl.glow(px, py, pr * 2.4f * glowScale, glowColor, 0.75f * bloom)
        }
        dl.circle(px, py, pr, core)
        dl.circle(px - pr * 0.25f, py - pr * 0.25f, pr * 0.45f, Colors.withAlpha(Colors.WHITE, 0.9f))
        return true
    }

    /** Screen-space projected radius of a world length at a point. */
    fun projectedSize(x: Double, y: Double, z: Double, len: Double): Float = if (cam.project(x, y, z)) (len * cam.outScale).toFloat() else 0f
}
