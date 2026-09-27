package com.rhythmphysics.core.math

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

@Serializable
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    operator fun unaryMinus() = Vec2(-x, -y)
    infix fun dot(o: Vec2) = x * o.x + y * o.y
    val length: Double get() = sqrt(x * x + y * y)
    fun normalized(): Vec2 { val l = length; return if (l < 1e-12) ZERO else Vec2(x / l, y / l) }
    companion object { val ZERO = Vec2(0.0, 0.0) }
}

@Serializable
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Double get() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 { val l = length; return if (l < 1e-12) ZERO else Vec3(x / l, y / l, z / l) }
    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UP = Vec3(0.0, 1.0, 0.0)
    }
}

/** Axis-aligned rectangle (min corner + size). */
@Serializable
data class Rect(val x: Double, val y: Double, val w: Double, val h: Double) {
    val right get() = x + w
    val top get() = y + h
    val cx get() = x + w / 2
    val cy get() = y + h / 2
    fun intersects(o: Rect, margin: Double = 0.0) =
        x - margin < o.right && right + margin > o.x && y - margin < o.top && top + margin > o.y
    fun contains(px: Double, py: Double) = px >= x && px <= right && py >= y && py <= top
    fun expanded(m: Double) = Rect(x - m, y - m, w + 2 * m, h + 2 * m)
    companion object {
        fun centered(cx: Double, cy: Double, w: Double, h: Double) = Rect(cx - w / 2, cy - h / 2, w, h)
    }
}

object MathUtil {
    fun clamp(v: Double, lo: Double, hi: Double) = if (v < lo) lo else if (v > hi) hi else v
    fun clamp(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v
    fun clamp(v: Int, lo: Int, hi: Int) = if (v < lo) lo else if (v > hi) hi else v
    fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = clamp((x - e0) / (e1 - e0), 0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
    fun easeOutCubic(t: Double): Double { val u = 1 - clamp(t, 0.0, 1.0); return 1 - u * u * u }
    fun easeInOutSine(t: Double) = -(kotlin.math.cos(Math.PI * clamp(t, 0.0, 1.0)) - 1) / 2
    fun approxEq(a: Double, b: Double, eps: Double = 1e-9) = abs(a - b) <= eps
    fun gaussian(x: Double, sigma: Double) = exp(-(x * x) / (2 * sigma * sigma))

    /** Distance from point to segment ab. */
    fun pointSegmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val abx = bx - ax; val aby = by - ay
        val len2 = abx * abx + aby * aby
        val t = if (len2 < 1e-12) 0.0 else clamp(((px - ax) * abx + (py - ay) * aby) / len2, 0.0, 1.0)
        val dx = ax + abx * t - px; val dy = ay + aby * t - py
        return sqrt(dx * dx + dy * dy)
    }

    /** True if segment (a->b) swept by a square of half-size [half] overlaps [r]. */
    fun sweptSquareHitsRect(ax: Double, ay: Double, bx: Double, by: Double, half: Double, r: Rect, samples: Int = 24): Boolean {
        val grown = r.expanded(half)
        // Conservative: sample along the segment (segments are short relative to surfaces).
        for (i in 0..samples) {
            val t = i.toDouble() / samples
            if (grown.contains(ax + (bx - ax) * t, ay + (by - ay) * t)) return true
        }
        return false
    }
}
