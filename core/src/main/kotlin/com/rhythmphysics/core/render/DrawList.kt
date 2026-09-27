package com.rhythmphysics.core.render

/**
 * Platform-neutral, allocation-free (after warm-up) list of 2D draw commands in *virtual viewport*
 * coordinates (e.g. 1080x1920 for a 9:16 creator frame, y down).
 *
 * Mechanics record into a DrawList; backends replay it:
 *  - Android: hardware Canvas (app module) for the live view and video export,
 *  - JVM: Java2D (desktop module) for deterministic reference screenshots in CI/cloud.
 *
 * A record-time transform (translate + uniform scale) supports split/quad views.
 */
class DrawList {
    object Op {
        const val CLEAR = 1
        const val GRADIENT_RECT = 2
        const val RADIAL_RECT = 3
        const val RECT = 4
        const val RECT_STROKE = 5
        const val CIRCLE = 6
        const val CIRCLE_STROKE = 7
        const val ARC = 8
        const val ELLIPSE = 9
        const val GLOW = 10
        const val LINE = 11
        const val POLY = 12
        const val TEXT = 13
        const val PUSH_CLIP = 14
        const val POP_CLIP = 15
        const val BLEND = 16
        const val PUSH_CLIP_CIRCLE = 17
        const val PUSH_LAYER = 18
        const val POP_LAYER = 19
        const val PERSISTENT = 20
    }

    object Blend { const val NORMAL = 0; const val ADD = 1 }
    object Align { const val LEFT = 0; const val CENTER = 1; const val RIGHT = 2 }

    var width = 1080f; private set
    var height = 1920f; private set

    var ops = IntArray(1024); private set
    var opCount = 0; private set
    var f = FloatArray(8192); private set
    var fCount = 0; private set
    var ints = IntArray(4096); private set
    var iCount = 0; private set
    val strings = ArrayList<String>()
    val layers = ArrayList<PersistentLayer>()

    private var tx = 0f
    private var ty = 0f
    private var s = 1f
    private val stack = FloatArray(48)
    private var sp = 0
    private var blend = Blend.NORMAL

    /** Statistics for the debug overlay. */
    val commandCount get() = opCount

    fun reset(width: Float, height: Float) {
        this.width = width; this.height = height
        opCount = 0; fCount = 0; iCount = 0
        strings.clear()
        layers.clear()
        tx = 0f; ty = 0f; s = 1f; sp = 0
        blend = Blend.NORMAL
    }

    // ---- transform ---------------------------------------------------------------------

    fun pushTransform(dx: Float, dy: Float, scale: Float) {
        stack[sp++] = tx; stack[sp++] = ty; stack[sp++] = s
        tx += dx * s; ty += dy * s; s *= scale
    }

    fun popTransform() { s = stack[--sp]; ty = stack[--sp]; tx = stack[--sp] }

    private fun X(x: Float) = tx + x * s
    private fun Y(y: Float) = ty + y * s
    private fun L(l: Float) = l * s

    // ---- low-level writers ------------------------------------------------------------

    private fun op(o: Int) {
        if (opCount == ops.size) ops = ops.copyOf(ops.size * 2)
        ops[opCount++] = o
    }
    private fun fl(v: Float) {
        if (fCount == f.size) f = f.copyOf(f.size * 2)
        f[fCount++] = v
    }
    private fun ii(v: Int) {
        if (iCount == ints.size) ints = ints.copyOf(ints.size * 2)
        ints[iCount++] = v
    }

    // ---- commands ----------------------------------------------------------------------

    fun blend(mode: Int) {
        if (mode == blend) return
        blend = mode
        op(Op.BLEND); ii(mode)
    }

    fun clear(color: Int) { op(Op.CLEAR); ii(color) }

    fun gradientRect(x: Float, y: Float, w: Float, h: Float, top: Int, bottom: Int) {
        op(Op.GRADIENT_RECT); fl(X(x)); fl(Y(y)); fl(L(w)); fl(L(h)); ii(top); ii(bottom)
    }

    /** Rect filled with a radial gradient centered at (cx,cy). */
    fun radialRect(x: Float, y: Float, w: Float, h: Float, cx: Float, cy: Float, r: Float, inner: Int, outer: Int) {
        op(Op.RADIAL_RECT); fl(X(x)); fl(Y(y)); fl(L(w)); fl(L(h)); fl(X(cx)); fl(Y(cy)); fl(L(r)); ii(inner); ii(outer)
    }

    /** Filled rect; rotation (degrees) is about the rect center. */
    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float = 0f, rotationDeg: Float = 0f) {
        if (Colors.a(color) == 0) return
        op(Op.RECT); fl(X(x)); fl(Y(y)); fl(L(w)); fl(L(h)); fl(L(radius)); fl(rotationDeg); ii(color)
    }

    fun rectCentered(cx: Float, cy: Float, w: Float, h: Float, color: Int, radius: Float = 0f, rotationDeg: Float = 0f) =
        rect(cx - w / 2, cy - h / 2, w, h, color, radius, rotationDeg)

    fun rectStroke(x: Float, y: Float, w: Float, h: Float, width: Float, color: Int, radius: Float = 0f, rotationDeg: Float = 0f) {
        if (Colors.a(color) == 0) return
        op(Op.RECT_STROKE); fl(X(x)); fl(Y(y)); fl(L(w)); fl(L(h)); fl(L(radius)); fl(rotationDeg); fl(L(width)); ii(color)
    }

    fun circle(cx: Float, cy: Float, r: Float, color: Int) {
        if (Colors.a(color) == 0 || r <= 0f) return
        op(Op.CIRCLE); fl(X(cx)); fl(Y(cy)); fl(L(r)); ii(color)
    }

    fun circleStroke(cx: Float, cy: Float, r: Float, width: Float, color: Int) {
        if (Colors.a(color) == 0 || r <= 0f) return
        op(Op.CIRCLE_STROKE); fl(X(cx)); fl(Y(cy)); fl(L(r)); fl(L(width)); ii(color)
    }

    /** Stroked arc, angles in degrees, 0 = +x, clockwise on screen (y down). */
    fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, width: Float, color: Int, roundCap: Boolean = true) {
        if (Colors.a(color) == 0 || r <= 0f) return
        op(Op.ARC); fl(X(cx)); fl(Y(cy)); fl(L(r)); fl(startDeg); fl(sweepDeg); fl(L(width)); ii(color); ii(if (roundCap) 1 else 0)
    }

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, color: Int, rotationDeg: Float = 0f) {
        if (Colors.a(color) == 0 || rx <= 0f || ry <= 0f) return
        op(Op.ELLIPSE); fl(X(cx)); fl(Y(cy)); fl(L(rx)); fl(L(ry)); fl(rotationDeg); ii(color)
    }

    /** Soft radial glow (color at center fading to transparent at r). Drawn additively. */
    fun glow(cx: Float, cy: Float, r: Float, color: Int, intensity: Float = 1f) {
        if (intensity <= 0.003f || r <= 0f) return
        val prev = blend
        blend(Blend.ADD)
        op(Op.GLOW); fl(X(cx)); fl(Y(cy)); fl(L(r)); ii(Colors.withAlpha(color, intensity.coerceAtMost(1f)))
        blend(prev)
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int, roundCap: Boolean = true) {
        if (Colors.a(color) == 0) return
        op(Op.LINE); fl(X(x1)); fl(Y(y1)); fl(X(x2)); fl(Y(y2)); fl(L(width)); ii(color); ii(if (roundCap) 1 else 0)
    }

    /**
     * Filled polygon. [colorBottom] != [colorTop] draws a vertical linear gradient between
     * [gradY0] and [gradY1] (viewport coordinates before transform).
     */
    fun poly(xs: FloatArray, ys: FloatArray, n: Int, colorTop: Int, colorBottom: Int = colorTop, gradY0: Float = 0f, gradY1: Float = 0f) {
        if (n < 3 || (Colors.a(colorTop) == 0 && Colors.a(colorBottom) == 0)) return
        op(Op.POLY); ii(n); ii(colorTop); ii(colorBottom)
        fl(Y(gradY0)); fl(Y(gradY1))
        for (i in 0 until n) { fl(X(xs[i])); fl(Y(ys[i])) }
    }

    fun quad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, color: Int) {
        if (Colors.a(color) == 0) return
        op(Op.POLY); ii(4); ii(color); ii(color)
        fl(0f); fl(0f)
        fl(X(x0)); fl(Y(y0)); fl(X(x1)); fl(Y(y1)); fl(X(x2)); fl(Y(y2)); fl(X(x3)); fl(Y(y3))
    }

    fun text(x: Float, y: Float, size: Float, color: Int, str: String, align: Int = Align.LEFT) {
        op(Op.TEXT); fl(X(x)); fl(Y(y)); fl(L(size)); ii(color); ii(align); ii(strings.size)
        strings += str
    }

    fun pushClip(x: Float, y: Float, w: Float, h: Float) { op(Op.PUSH_CLIP); fl(X(x)); fl(Y(y)); fl(L(w)); fl(L(h)) }
    fun popClip() { op(Op.POP_CLIP) }

    /** Circular clip (journey "portal" transitions). Closed by [popClip]. */
    fun pushClipCircle(cx: Float, cy: Float, r: Float) { op(Op.PUSH_CLIP_CIRCLE); fl(X(cx)); fl(Y(cy)); fl(L(r)) }

    /** Everything until [popLayer] is composited with [alpha] (cross-fades). */
    fun pushLayer(alpha: Float) { op(Op.PUSH_LAYER); fl(alpha.coerceIn(0f, 1f)) }
    fun popLayer() { op(Op.POP_LAYER) }

    /**
     * Never-cleared drawing ("paint that stays"): [layer] emits its items in the current local
     * coordinates. Backends may cache the result and only draw items appended since the last frame.
     */
    fun persistent(layer: PersistentLayer) {
        op(Op.PERSISTENT); fl(tx); fl(ty); fl(s); ii(layers.size); layers += layer
    }

    /** Visits commands with their parameter offsets; backends implement [Visitor]. */
    fun replay(v: Visitor) {
        var fi = 0
        var ci = 0
        for (k in 0 until opCount) {
            when (ops[k]) {
                Op.CLEAR -> { v.clear(ints[ci]); ci += 1 }
                Op.GRADIENT_RECT -> { v.gradientRect(f[fi], f[fi + 1], f[fi + 2], f[fi + 3], ints[ci], ints[ci + 1]); fi += 4; ci += 2 }
                Op.RADIAL_RECT -> { v.radialRect(f[fi], f[fi + 1], f[fi + 2], f[fi + 3], f[fi + 4], f[fi + 5], f[fi + 6], ints[ci], ints[ci + 1]); fi += 7; ci += 2 }
                Op.RECT -> { v.rect(f[fi], f[fi + 1], f[fi + 2], f[fi + 3], f[fi + 4], f[fi + 5], ints[ci]); fi += 6; ci += 1 }
                Op.RECT_STROKE -> { v.rectStroke(f[fi], f[fi + 1], f[fi + 2], f[fi + 3], f[fi + 4], f[fi + 5], f[fi + 6], ints[ci]); fi += 7; ci += 1 }
                Op.CIRCLE -> { v.circle(f[fi], f[fi + 1], f[fi + 2], ints[ci]); fi += 3; ci += 1 }
                Op.CIRCLE_STROKE -> { v.circleStroke(f[fi], f[fi + 1], f[fi + 2], f[fi + 3], ints[ci]); fi += 4; ci += 1 }
                Op.ARC -> { v.arc(f[fi], f[fi + 1], f[fi + 2], f[fi + 3], f[fi + 4], f[fi + 5], ints[ci], ints[ci + 1] == 1); fi += 6; ci += 2 }
                Op.ELLIPSE -> { v.ellipse(f[fi], f[fi + 1], f[fi + 2], f[fi + 3], f[fi + 4], ints[ci]); fi += 5; ci += 1 }
                Op.GLOW -> { v.glow(f[fi], f[fi + 1], f[fi + 2], ints[ci]); fi += 3; ci += 1 }
                Op.LINE -> { v.line(f[fi], f[fi + 1], f[fi + 2], f[fi + 3], f[fi + 4], ints[ci], ints[ci + 1] == 1); fi += 5; ci += 2 }
                Op.POLY -> {
                    val n = ints[ci]
                    v.poly(f, fi + 2, n, ints[ci + 1], ints[ci + 2], f[fi], f[fi + 1])
                    fi += 2 + 2 * n; ci += 3
                }
                Op.TEXT -> { v.text(f[fi], f[fi + 1], f[fi + 2], ints[ci], ints[ci + 1], strings[ints[ci + 2]]); fi += 3; ci += 3 }
                Op.PUSH_CLIP -> { v.pushClip(f[fi], f[fi + 1], f[fi + 2], f[fi + 3]); fi += 4 }
                Op.POP_CLIP -> v.popClip()
                Op.BLEND -> { v.blend(ints[ci]); ci += 1 }
                Op.PUSH_CLIP_CIRCLE -> { v.pushClipCircle(f[fi], f[fi + 1], f[fi + 2]); fi += 3 }
                Op.PUSH_LAYER -> { v.pushLayer(f[fi]); fi += 1 }
                Op.POP_LAYER -> v.popLayer()
                Op.PERSISTENT -> { v.persistent(layers[ints[ci]], f[fi], f[fi + 1], f[fi + 2], width, height); fi += 3; ci += 1 }
            }
        }
    }

    interface Visitor {
        fun clear(color: Int)
        fun gradientRect(x: Float, y: Float, w: Float, h: Float, top: Int, bottom: Int)
        fun radialRect(x: Float, y: Float, w: Float, h: Float, cx: Float, cy: Float, r: Float, inner: Int, outer: Int)
        fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float, rotationDeg: Float, color: Int)
        fun rectStroke(x: Float, y: Float, w: Float, h: Float, radius: Float, rotationDeg: Float, width: Float, color: Int)
        fun circle(cx: Float, cy: Float, r: Float, color: Int)
        fun circleStroke(cx: Float, cy: Float, r: Float, width: Float, color: Int)
        fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, width: Float, color: Int, roundCap: Boolean)
        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, rotationDeg: Float, color: Int)
        fun glow(cx: Float, cy: Float, r: Float, color: Int)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int, roundCap: Boolean)
        /** Points are (xy[off+2i], xy[off+2i+1]) for i < n. */
        fun poly(xy: FloatArray, off: Int, n: Int, colorTop: Int, colorBottom: Int, gradY0: Float, gradY1: Float)
        fun text(x: Float, y: Float, size: Float, color: Int, align: Int, str: String)
        fun pushClip(x: Float, y: Float, w: Float, h: Float)
        fun popClip()
        fun blend(mode: Int)
        fun pushClipCircle(cx: Float, cy: Float, r: Float)
        fun pushLayer(alpha: Float)
        fun popLayer()

        /** Default: redraw every item (reference renderer); live backends cache incrementally. */
        fun persistent(layer: PersistentLayer, tx: Float, ty: Float, scale: Float, frameW: Float, frameH: Float) {
            val d = DrawList()
            d.reset(frameW, frameH)
            d.pushTransform(tx, ty, scale)
            layer.emit(d, 0, layer.size)
            d.replay(this)
        }
    }
}

/**
 * Append-only drawing history owned by a mechanic. Items [0, size) are immutable while [epoch] is
 * unchanged; any non-append change (restore, reset) must bump [epoch] so caches start over.
 */
abstract class PersistentLayer {
    abstract val epoch: Int
    abstract val size: Int
    /** Emits items [from, to) into [dl] (local coordinates; dl carries the transform). */
    abstract fun emit(dl: DrawList, from: Int, to: Int)
}
