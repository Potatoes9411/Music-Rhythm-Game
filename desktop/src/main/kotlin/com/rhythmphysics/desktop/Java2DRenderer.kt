package com.rhythmphysics.desktop

import com.rhythmphysics.core.render.DrawList
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Composite
import java.awt.CompositeContext
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.MultipleGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.ColorModel
import java.awt.image.Raster
import java.awt.image.WritableRaster

/**
 * Java2D backend for [DrawList] — the JVM reference renderer used for deterministic screenshots
 * in environments without an Android device/emulator. Semantics mirror the Android Canvas backend.
 */
class Java2DRenderer(val width: Int, val height: Int) : DrawList.Visitor {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    private var g: Graphics2D = image.createGraphics()
    private var sx = 1f
    private val clipStack = ArrayList<Shape?>()
    private val layerStack = ArrayList<Triple<BufferedImage, Graphics2D, Float>>()
    private var additive = false

    fun render(dl: DrawList): BufferedImage {
        sx = width / dl.width
        g = image.createGraphics()
        setup(g)
        dl.replay(this)
        g.dispose()
        return image
    }

    private fun setup(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
        g.transform = AffineTransform.getScaleInstance(sx.toDouble(), sx.toDouble())
    }

    private fun col(c: Int) = Color(c, true)

    private fun fill(shape: Shape, color: Int) {
        g.composite = if (additive) AddComposite else AlphaComposite.SrcOver
        g.paint = col(color)
        g.fill(shape)
    }

    override fun clear(color: Int) {
        val old = g.composite
        g.composite = AlphaComposite.Src
        g.paint = col(color)
        g.fill(Rectangle2D.Float(0f, 0f, width / sx, height / sx))
        g.composite = old
    }

    override fun gradientRect(x: Float, y: Float, w: Float, h: Float, top: Int, bottom: Int) {
        g.composite = if (additive) AddComposite else AlphaComposite.SrcOver
        g.paint = GradientPaint(0f, y, col(top), 0f, y + h, col(bottom))
        g.fill(Rectangle2D.Float(x, y, w, h))
    }

    override fun radialRect(x: Float, y: Float, w: Float, h: Float, cx: Float, cy: Float, r: Float, inner: Int, outer: Int) {
        g.composite = if (additive) AddComposite else AlphaComposite.SrcOver
        g.paint = RadialGradientPaint(Point2D.Float(cx, cy), r, floatArrayOf(0f, 1f), arrayOf(col(inner), col(outer)), MultipleGradientPaint.CycleMethod.NO_CYCLE)
        g.fill(Rectangle2D.Float(x, y, w, h))
    }

    private fun rotated(shape: Shape, cx: Float, cy: Float, deg: Float): Shape =
        if (deg == 0f) shape else AffineTransform.getRotateInstance(Math.toRadians(deg.toDouble()), cx.toDouble(), cy.toDouble()).createTransformedShape(shape)

    override fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float, rotationDeg: Float, color: Int) {
        val s: Shape = if (radius > 0f) RoundRectangle2D.Float(x, y, w, h, radius * 2, radius * 2) else Rectangle2D.Float(x, y, w, h)
        fill(rotated(s, x + w / 2, y + h / 2, rotationDeg), color)
    }

    override fun rectStroke(x: Float, y: Float, w: Float, h: Float, radius: Float, rotationDeg: Float, width: Float, color: Int) {
        val s: Shape = if (radius > 0f) RoundRectangle2D.Float(x, y, w, h, radius * 2, radius * 2) else Rectangle2D.Float(x, y, w, h)
        val stroked = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(s)
        fill(rotated(stroked, x + w / 2, y + h / 2, rotationDeg), color)
    }

    override fun circle(cx: Float, cy: Float, r: Float, color: Int) = fill(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r), color)

    override fun circleStroke(cx: Float, cy: Float, r: Float, width: Float, color: Int) =
        fill(BasicStroke(width).createStrokedShape(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r)), color)

    override fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, width: Float, color: Int, roundCap: Boolean) {
        // DrawList: clockwise on screen from +x. Java2D: counter-clockwise on screen.
        val a = Arc2D.Float(cx - r, cy - r, 2 * r, 2 * r, -startDeg, -sweepDeg, Arc2D.OPEN)
        fill(BasicStroke(width, if (roundCap) BasicStroke.CAP_ROUND else BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND).createStrokedShape(a), color)
    }

    override fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, rotationDeg: Float, color: Int) =
        fill(rotated(Ellipse2D.Float(cx - rx, cy - ry, 2 * rx, 2 * ry), cx, cy, rotationDeg), color)

    override fun glow(cx: Float, cy: Float, r: Float, color: Int) {
        val a = (color ushr 24) and 0xFF
        val rgb = color and 0xFFFFFF
        val c0 = Color((a shl 24) or rgb, true)
        val c1 = Color(((a * 0.42f).toInt() shl 24) or rgb, true)
        val c2 = Color(((a * 0.12f).toInt() shl 24) or rgb, true)
        val c3 = Color(rgb, true)
        g.composite = if (additive) AddComposite else AlphaComposite.SrcOver
        g.paint = RadialGradientPaint(Point2D.Float(cx, cy), r, floatArrayOf(0f, 0.22f, 0.55f, 1f), arrayOf(c0, c1, c2, c3))
        g.fill(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int, roundCap: Boolean) {
        val s = BasicStroke(width, if (roundCap) BasicStroke.CAP_ROUND else BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
        fill(s.createStrokedShape(Line2D.Float(x1, y1, x2, y2)), color)
    }

    override fun poly(xy: FloatArray, off: Int, n: Int, colorTop: Int, colorBottom: Int, gradY0: Float, gradY1: Float) {
        val p = Path2D.Float()
        p.moveTo(xy[off], xy[off + 1])
        for (i in 1 until n) p.lineTo(xy[off + 2 * i], xy[off + 2 * i + 1])
        p.closePath()
        g.composite = if (additive) AddComposite else AlphaComposite.SrcOver
        g.paint = if (colorTop != colorBottom && gradY1 != gradY0) GradientPaint(0f, gradY0, col(colorTop), 0f, gradY1, col(colorBottom)) else col(colorTop)
        g.fill(p)
    }

    override fun text(x: Float, y: Float, size: Float, color: Int, align: Int, str: String) {
        g.composite = AlphaComposite.SrcOver
        g.font = Font(Font.MONOSPACED, Font.PLAIN, size.toInt().coerceAtLeast(1))
        val w = g.fontMetrics.stringWidth(str)
        val dx = when (align) { DrawList.Align.CENTER -> -w / 2f; DrawList.Align.RIGHT -> -w.toFloat(); else -> 0f }
        g.paint = col(color)
        g.drawString(str, x + dx, y)
    }

    override fun pushClip(x: Float, y: Float, w: Float, h: Float) { clipStack += g.clip; g.clip(Rectangle2D.Float(x, y, w, h)) }
    override fun pushClipCircle(cx: Float, cy: Float, r: Float) { clipStack += g.clip; g.clip(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r)) }
    override fun popClip() { if (clipStack.isNotEmpty()) g.clip = clipStack.removeAt(clipStack.size - 1) }

    override fun pushLayer(alpha: Float) {
        val layer = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val lg = layer.createGraphics()
        setup(lg)
        lg.clip = g.clip
        layerStack += Triple(layer, g, alpha)
        g = lg
    }

    override fun popLayer() {
        if (layerStack.isEmpty()) return
        val (layer, prev, alpha) = layerStack.removeAt(layerStack.size - 1)
        g.dispose()
        g = prev
        val t = g.transform
        g.transform = AffineTransform()
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
        g.drawImage(layer, 0, 0, null)
        g.transform = t
    }

    override fun blend(mode: Int) { additive = mode == DrawList.Blend.ADD }

    /** Additive blending (src * srcAlpha + dst), matching Android PorterDuff ADD for premultiplied colors. */
    object AddComposite : Composite {
        override fun createContext(srcColorModel: ColorModel, dstColorModel: ColorModel, hints: RenderingHints?) = object : CompositeContext {
            override fun dispose() {}
            override fun compose(src: Raster, dstIn: Raster, dstOut: WritableRaster) {
                val w = minOf(src.width, dstIn.width); val h = minOf(src.height, dstIn.height)
                val s = IntArray(4); val d = IntArray(4)
                for (y in 0 until h) for (x in 0 until w) {
                    src.getPixel(x, y, s); dstIn.getPixel(x, y, d)
                    val a = s[3] / 255f
                    d[0] = minOf(255, d[0] + (s[0] * a).toInt())
                    d[1] = minOf(255, d[1] + (s[1] * a).toInt())
                    d[2] = minOf(255, d[2] + (s[2] * a).toInt())
                    d[3] = minOf(255, d[3] + s[3])
                    dstOut.setPixel(x, y, d)
                }
            }
        }
    }
}
