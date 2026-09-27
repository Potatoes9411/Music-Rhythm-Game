package com.rhythmphysics.core.render

import com.rhythmphysics.core.math.Vec3
import kotlinx.serialization.Serializable
import kotlin.math.tan

/** Creator frame aspect. The logical canvas is independent of the physical display. */
@Serializable
enum class AspectRatio(val width: Int, val height: Int, val label: String) {
    PORTRAIT_9_16(1080, 1920, "9:16"),
    LANDSCAPE_16_9(1920, 1080, "16:9"),
    SQUARE_1_1(1080, 1080, "1:1");

    val ratio: Double get() = width.toDouble() / height
    val isPortrait get() = height > width
}

/** A rectangular region of the DrawList into which one mechanic composes. */
data class Viewport(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx get() = x + w / 2
    val cy get() = y + h / 2
    val aspect get() = w / h
    val isPortrait get() = h > w * 1.05f
    val isLandscape get() = w > h * 1.05f
    /** Reference length: the short side. Sizes are expressed relative to it so compositions scale. */
    val unit get() = minOf(w, h)
}

/** Orthographic 2D camera: world (y up) -> viewport pixels (y down). */
class Camera2D(var centerX: Double = 0.0, var centerY: Double = 0.0, var pixelsPerUnit: Double = 60.0) {
    lateinit var vp: Viewport
    fun sx(wx: Double) = (vp.cx + (wx - centerX) * pixelsPerUnit).toFloat()
    fun sy(wy: Double) = (vp.cy - (wy - centerY) * pixelsPerUnit).toFloat()
    fun len(l: Double) = (l * pixelsPerUnit).toFloat()
    fun worldWidth() = vp.w / pixelsPerUnit
    fun worldHeight() = vp.h / pixelsPerUnit
}

/** Perspective camera for the 3D-staged mechanics (Arch, Platform-3D looks). Projection is done on the CPU. */
class Camera3D {
    var position = Vec3(0.0, 3.0, 10.0)
    var target = Vec3.ZERO
    var fovYDeg = 50.0
    lateinit var vp: Viewport

    private var rx = 1.0; private var ry = 0.0; private var rz = 0.0
    private var ux = 0.0; private var uy = 1.0; private var uz = 0.0
    private var fx = 0.0; private var fy = 0.0; private var fz = -1.0
    private var focal = 1.0

    /** Results of the last [project] call (no allocation). */
    var outX = 0f; private set
    var outY = 0f; private set
    var outDepth = 0.0; private set
    /** Pixels per world unit at the projected depth. */
    var outScale = 0f; private set

    fun update() {
        val f = (target - position).normalized()
        var r = f cross Vec3.UP
        if (r.length < 1e-6) r = Vec3(1.0, 0.0, 0.0)
        r = r.normalized()
        val u = r cross f
        fx = f.x; fy = f.y; fz = f.z
        rx = r.x; ry = r.y; rz = r.z
        ux = u.x; uy = u.y; uz = u.z
        focal = 1.0 / tan(Math.toRadians(fovYDeg) / 2) * (vp.h / 2.0)
    }

    /** Projects world point; returns false if behind the near plane. */
    fun project(x: Double, y: Double, z: Double): Boolean {
        val dx = x - position.x; val dy = y - position.y; val dz = z - position.z
        val cz = dx * fx + dy * fy + dz * fz
        val cx = dx * rx + dy * ry + dz * rz
        val cy = dx * ux + dy * uy + dz * uz
        outDepth = cz
        if (cz < 0.05) return false
        val k = focal / cz
        outX = (vp.cx + cx * k).toFloat()
        outY = (vp.cy - cy * k).toFloat()
        outScale = k.toFloat()
        return true
    }

    fun project(p: Vec3) = project(p.x, p.y, p.z)

    fun depthOf(x: Double, y: Double, z: Double) = (x - position.x) * fx + (y - position.y) * fy + (z - position.z) * fz
}
