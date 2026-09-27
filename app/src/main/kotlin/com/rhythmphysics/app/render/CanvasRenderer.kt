package com.rhythmphysics.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.rhythmphysics.core.render.DrawList
import com.rhythmphysics.core.render.PersistentLayer
import java.util.IdentityHashMap

/**
 * Android backend for the engine's [DrawList]: replays commands onto a (hardware) Canvas.
 * Used for the live SurfaceView and for the video encoder's input surface, so exported videos
 * are drawn by exactly the same code as the preview.
 */
class CanvasRenderer : DrawList.Visitor {
    private lateinit var c: Canvas
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val rect = RectF()
    private val path = Path()
    private val matrix = Matrix()
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private var additive = false
    /** Unit-radius glow shaders per color, positioned with a local matrix (no per-frame allocation). */
    private val glowCache = object : LinkedHashMap<Int, RadialGradient>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, RadialGradient>?) = size > 160
    }
    var commands = 0; private set
    private var frameScale = 1f

    /** Offscreen bitmaps for never-cleared layers; only newly appended items are drawn each frame. */
    private class PersistCache(var bmp: Bitmap, val canvas: Canvas, var key: String, var epoch: Int, var drawn: Int)
    private val persistCaches = IdentityHashMap<PersistentLayer, PersistCache>()
    private val scratch = DrawList()
    private var sub: CanvasRenderer? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Draws [dl] (frame coordinates) into the canvas region described by [box]. */
    fun draw(canvas: Canvas, dl: DrawList, box: Letterbox, clearColor: Int = 0xFF000000.toInt()) {
        c = canvas
        canvas.drawColor(clearColor, PorterDuff.Mode.SRC)
        canvas.save()
        canvas.translate(box.offsetX, box.offsetY)
        canvas.scale(box.scale, box.scale)
        frameScale = box.scale
        canvas.clipRect(0f, 0f, dl.width, dl.height)
        additive = false
        applyBlend()
        commands = dl.commandCount
        dl.replay(this)
        canvas.restore()
    }

    private fun applyBlend() {
        val x = if (additive) add else null
        fill.xfermode = x; stroke.xfermode = x; shaderPaint.xfermode = x
    }

    override fun blend(mode: Int) { additive = mode == DrawList.Blend.ADD; applyBlend() }

    override fun clear(color: Int) { c.drawColor(color, PorterDuff.Mode.SRC) }

    override fun gradientRect(x: Float, y: Float, w: Float, h: Float, top: Int, bottom: Int) {
        shaderPaint.shader = LinearGradient(0f, y, 0f, y + h, top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(x, y, x + w, y + h, shaderPaint)
        shaderPaint.shader = null
    }

    override fun radialRect(x: Float, y: Float, w: Float, h: Float, cx: Float, cy: Float, r: Float, inner: Int, outer: Int) {
        shaderPaint.shader = RadialGradient(cx, cy, r.coerceAtLeast(1f), inner, outer, Shader.TileMode.CLAMP)
        c.drawRect(x, y, x + w, y + h, shaderPaint)
        shaderPaint.shader = null
    }

    private inline fun rotated(rot: Float, cx: Float, cy: Float, block: () -> Unit) {
        if (rot == 0f) { block(); return }
        c.save(); c.rotate(rot, cx, cy); block(); c.restore()
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float, rotationDeg: Float, color: Int) {
        fill.color = color
        rotated(rotationDeg, x + w / 2, y + h / 2) {
            if (radius > 0f) c.drawRoundRect(x, y, x + w, y + h, radius, radius, fill) else c.drawRect(x, y, x + w, y + h, fill)
        }
    }

    override fun rectStroke(x: Float, y: Float, w: Float, h: Float, radius: Float, rotationDeg: Float, width: Float, color: Int) {
        stroke.color = color; stroke.strokeWidth = width; stroke.strokeCap = Paint.Cap.ROUND
        rotated(rotationDeg, x + w / 2, y + h / 2) {
            if (radius > 0f) c.drawRoundRect(x, y, x + w, y + h, radius, radius, stroke) else c.drawRect(x, y, x + w, y + h, stroke)
        }
    }

    override fun circle(cx: Float, cy: Float, r: Float, color: Int) { fill.color = color; c.drawCircle(cx, cy, r, fill) }

    override fun circleStroke(cx: Float, cy: Float, r: Float, width: Float, color: Int) {
        stroke.color = color; stroke.strokeWidth = width; c.drawCircle(cx, cy, r, stroke)
    }

    override fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, width: Float, color: Int, roundCap: Boolean) {
        stroke.color = color; stroke.strokeWidth = width; stroke.strokeCap = if (roundCap) Paint.Cap.ROUND else Paint.Cap.BUTT
        rect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(rect, startDeg, sweepDeg, false, stroke) // Android: clockwise from 3 o'clock, same as DrawList
    }

    override fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, rotationDeg: Float, color: Int) {
        fill.color = color
        rotated(rotationDeg, cx, cy) { c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, fill) }
    }

    override fun glow(cx: Float, cy: Float, r: Float, color: Int) {
        val shader = glowCache.getOrPut(color) {
            val a = (color ushr 24) and 0xFF; val rgb = color and 0xFFFFFF
            RadialGradient(0f, 0f, 1f,
                intArrayOf((a shl 24) or rgb, ((a * 0.42f).toInt() shl 24) or rgb, ((a * 0.12f).toInt() shl 24) or rgb, rgb),
                floatArrayOf(0f, 0.22f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        matrix.setScale(r, r); matrix.postTranslate(cx, cy)
        shader.setLocalMatrix(matrix)
        shaderPaint.shader = shader
        c.drawCircle(cx, cy, r, shaderPaint)
        shaderPaint.shader = null
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int, roundCap: Boolean) {
        stroke.color = color; stroke.strokeWidth = width; stroke.strokeCap = if (roundCap) Paint.Cap.ROUND else Paint.Cap.BUTT
        c.drawLine(x1, y1, x2, y2, stroke)
    }

    override fun poly(xy: FloatArray, off: Int, n: Int, colorTop: Int, colorBottom: Int, gradY0: Float, gradY1: Float) {
        path.rewind()
        path.moveTo(xy[off], xy[off + 1])
        for (i in 1 until n) path.lineTo(xy[off + 2 * i], xy[off + 2 * i + 1])
        path.close()
        if (colorTop != colorBottom && gradY1 != gradY0) {
            shaderPaint.shader = LinearGradient(0f, gradY0, 0f, gradY1, colorTop, colorBottom, Shader.TileMode.CLAMP)
            c.drawPath(path, shaderPaint)
            shaderPaint.shader = null
        } else {
            fill.color = colorTop
            c.drawPath(path, fill)
        }
    }

    override fun text(x: Float, y: Float, size: Float, color: Int, align: Int, str: String) {
        text.color = color; text.textSize = size
        text.textAlign = when (align) { DrawList.Align.CENTER -> Paint.Align.CENTER; DrawList.Align.RIGHT -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
        c.drawText(str, x, y, text)
    }

    override fun pushClip(x: Float, y: Float, w: Float, h: Float) { c.save(); c.clipRect(x, y, x + w, y + h) }

    override fun pushClipCircle(cx: Float, cy: Float, r: Float) {
        c.save()
        path.rewind(); path.addCircle(cx, cy, r, Path.Direction.CW)
        c.clipPath(path)
    }

    override fun popClip() { c.restore() }

    override fun pushLayer(alpha: Float) { c.saveLayerAlpha(null, (alpha * 255).toInt().coerceIn(0, 255)) }

    override fun popLayer() { c.restore() }

    /** Replays [dl] onto [canvas] at [scale] without clearing (used to paint persistent caches). */
    private fun paintInto(canvas: Canvas, dl: DrawList, scale: Float) {
        c = canvas
        canvas.save(); canvas.scale(scale, scale)
        additive = false; applyBlend()
        dl.replay(this)
        canvas.restore()
    }

    override fun persistent(layer: PersistentLayer, tx: Float, ty: Float, scale: Float, frameW: Float, frameH: Float) {
        val s = frameScale.coerceIn(0.1f, 2f)
        val w = kotlin.math.ceil(frameW * s).toInt().coerceAtLeast(1)
        val h = kotlin.math.ceil(frameH * s).toInt().coerceAtLeast(1)
        val key = "$tx,$ty,$scale,$w,$h"
        var cache = persistCaches[layer]
        if (cache == null || cache.bmp.width != w || cache.bmp.height != h) {
            cache?.bmp?.recycle()
            if (persistCaches.size >= 4 && cache == null) { persistCaches.values.forEach { it.bmp.recycle() }; persistCaches.clear() }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cache = PersistCache(bmp, Canvas(bmp), key, layer.epoch, 0)
            persistCaches[layer] = cache
        }
        if (cache.key != key || cache.epoch != layer.epoch || layer.size < cache.drawn) {
            cache.bmp.eraseColor(0); cache.key = key; cache.epoch = layer.epoch; cache.drawn = 0
        }
        if (layer.size > cache.drawn) {
            scratch.reset(frameW, frameH)
            scratch.pushTransform(tx, ty, scale)
            layer.emit(scratch, cache.drawn, layer.size)
            val r = sub ?: CanvasRenderer().also { sub = it }
            r.paintInto(cache.canvas, scratch, s)
            cache.drawn = layer.size
        }
        c.save()
        c.scale(1f / s, 1f / s)
        c.drawBitmap(cache.bmp, 0f, 0f, bitmapPaint)
        c.restore()
    }

    /** Frees persistent-layer bitmaps (engine swap / release). */
    fun releaseCaches() { persistCaches.values.forEach { it.bmp.recycle() }; persistCaches.clear() }
}
