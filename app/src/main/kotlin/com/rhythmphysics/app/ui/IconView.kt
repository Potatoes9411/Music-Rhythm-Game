package com.rhythmphysics.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Vector icons drawn with Canvas paths (no icon fonts or bitmap assets; crisp at any density). */
class IconView(context: Context, kind: Kind, description: String, private val big: Boolean = false) : View(context) {
    enum class Kind { PLAY, PAUSE, RESTART, BACK, SETTINGS, EXPORT, CLEAN, DEBUG, DICE, EXPAND, COLLAPSE }

    var kind: Kind = kind
        set(v) { field = v; invalidate() }
    var active = false
        set(v) { field = v; background = bg(); invalidate() }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val oval = RectF()

    init {
        contentDescription = description
        isClickable = true; isFocusable = true
        background = bg()
    }

    private fun bg() = Ui.ripple(Ui.rounded(when { big -> Ui.ACCENT; active -> Ui.ACCENT_DARK; else -> Ui.CHIP }, Ui.dp(context, if (big) 28 else 22).toFloat()))

    override fun onMeasure(w: Int, h: Int) {
        val s = Ui.dp(context, if (big) 56 else 44)
        setMeasuredDimension(resolveSize(s, w), resolveSize(s, h))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = minOf(w, h) * (if (big) 0.36f else 0.40f)
        val cx = w / 2; val cy = h / 2
        val col = if (big) 0xFF06201E.toInt() else if (active) Ui.ACCENT else Ui.TEXT
        stroke.color = col; fill.color = col
        stroke.strokeWidth = s * 0.16f
        path.rewind()
        when (kind) {
            Kind.PLAY -> { path.moveTo(cx - s * 0.38f, cy - s * 0.5f); path.lineTo(cx + s * 0.52f, cy); path.lineTo(cx - s * 0.38f, cy + s * 0.5f); path.close(); c.drawPath(path, fill) }
            Kind.PAUSE -> { c.drawRoundRect(cx - s * 0.42f, cy - s * 0.48f, cx - s * 0.12f, cy + s * 0.48f, s * 0.06f, s * 0.06f, fill); c.drawRoundRect(cx + s * 0.12f, cy - s * 0.48f, cx + s * 0.42f, cy + s * 0.48f, s * 0.06f, s * 0.06f, fill) }
            Kind.RESTART -> {
                // Clockwise circular arrow with the head at the top-right gap.
                val r = s * 0.44f
                oval.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(oval, 0f, 290f, false, stroke)
                val th = Math.toRadians(290.0)
                val px = cx + r * cos(th).toFloat(); val py = cy + r * sin(th).toFloat()
                val dx = -sin(th).toFloat(); val dy = cos(th).toFloat()      // tangent (clockwise)
                val nx = cos(th).toFloat(); val ny = sin(th).toFloat()       // radial
                path.moveTo(px + dx * s * 0.26f, py + dy * s * 0.26f)
                path.lineTo(px - dx * s * 0.04f + nx * s * 0.2f, py - dy * s * 0.04f + ny * s * 0.2f)
                path.lineTo(px - dx * s * 0.04f - nx * s * 0.2f, py - dy * s * 0.04f - ny * s * 0.2f)
                path.close()
                c.drawPath(path, fill)
            }
            Kind.BACK -> { path.moveTo(cx + s * 0.18f, cy - s * 0.42f); path.lineTo(cx - s * 0.24f, cy); path.lineTo(cx + s * 0.18f, cy + s * 0.42f); c.drawPath(path, stroke) }
            Kind.SETTINGS -> {
                // Three sliders
                for (i in -1..1) {
                    val y = cy + i * s * 0.36f
                    c.drawLine(cx - s * 0.5f, y, cx + s * 0.5f, y, stroke)
                    c.drawCircle(cx + i * s * 0.25f, y, s * 0.13f, fill)
                }
            }
            Kind.EXPORT -> {
                c.drawLine(cx, cy - s * 0.5f, cx, cy + s * 0.15f, stroke)
                path.moveTo(cx - s * 0.28f, cy - s * 0.12f); path.lineTo(cx, cy + s * 0.16f); path.lineTo(cx + s * 0.28f, cy - s * 0.12f); c.drawPath(path, stroke)
                path.rewind(); path.moveTo(cx - s * 0.5f, cy + s * 0.18f); path.lineTo(cx - s * 0.5f, cy + s * 0.5f); path.lineTo(cx + s * 0.5f, cy + s * 0.5f); path.lineTo(cx + s * 0.5f, cy + s * 0.18f); c.drawPath(path, stroke)
            }
            Kind.CLEAN -> {
                val a = s * 0.5f; val b = s * 0.2f
                for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                    path.rewind(); path.moveTo(cx + sx * a, cy + sy * (a - b)); path.lineTo(cx + sx * a, cy + sy * a); path.lineTo(cx + sx * (a - b), cy + sy * a); c.drawPath(path, stroke)
                }
            }
            Kind.DEBUG -> {
                c.drawCircle(cx, cy, s * 0.5f, stroke)
                c.drawLine(cx, cy - s * 0.02f, cx, cy + s * 0.26f, stroke)
                c.drawCircle(cx, cy - s * 0.22f, s * 0.07f, fill)
            }
            Kind.DICE -> {
                c.drawRoundRect(cx - s * 0.5f, cy - s * 0.5f, cx + s * 0.5f, cy + s * 0.5f, s * 0.14f, s * 0.14f, stroke)
                for ((dx, dy) in listOf(-0.22f to -0.22f, 0f to 0f, 0.22f to 0.22f)) c.drawCircle(cx + dx * s, cy + dy * s, s * 0.08f, fill)
            }
            Kind.EXPAND, Kind.COLLAPSE -> {
                val d = if (kind == Kind.EXPAND) -1f else 1f
                path.moveTo(cx - s * 0.4f, cy - d * s * 0.18f); path.lineTo(cx, cy + d * s * 0.2f); path.lineTo(cx + s * 0.4f, cy - d * s * 0.18f); c.drawPath(path, stroke)
            }
        }
    }
}
