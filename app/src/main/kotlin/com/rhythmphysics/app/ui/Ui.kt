package com.rhythmphysics.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Small programmatic view toolkit (framework widgets only; no AndroidX). */
object Ui {
    const val BG = 0xFF0B0D12.toInt()
    const val PANEL = 0xF0141821.toInt()
    const val CARD = 0xFF1A1F2B.toInt()
    const val CHIP = 0xFF222838.toInt()
    const val ACCENT = 0xFF4FD1C5.toInt()
    const val ACCENT_DARK = 0xFF123C3A.toInt()
    const val TEXT = 0xFFE8ECF4.toInt()
    const val MUTED = 0xFF8A93A6.toInt()
    const val DANGER = 0xFFFF6B6B.toInt()

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()
    fun dp(c: Context, v: Int): Int = dp(c, v.toFloat())

    fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

    fun ripple(base: GradientDrawable) = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, null)

    fun text(c: Context, s: String, sizeSp: Float = 14f, color: Int = TEXT, bold: Boolean = false) = TextView(c).apply {
        text = s; textSize = sizeSp; setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun label(c: Context, s: String) = text(c, s.uppercase(), 11f, MUTED, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(0, dp(c, 12), 0, dp(c, 6))
    }

    /** Filled or outlined button with ripple. */
    fun button(c: Context, s: String, primary: Boolean = false, onClick: () -> Unit) = TextView(c).apply {
        text = s; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(if (primary) 0xFF06201E.toInt() else TEXT)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = ripple(rounded(if (primary) ACCENT else CHIP, dp(c, 12).toFloat()))
        setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12))
        minHeight = dp(c, 44)
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Round icon-like button using a text glyph (keeps the APK free of icon assets). */
    fun iconButton(c: Context, glyph: String, description: String, onClick: () -> Unit) = TextView(c).apply {
        text = glyph; textSize = 18f; gravity = Gravity.CENTER; setTextColor(TEXT)
        contentDescription = description
        background = ripple(rounded(CHIP, dp(c, 22).toFloat()))
        val s = dp(c, 44)
        layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = dp(c, 8) }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    fun row(c: Context, vararg views: View, spacing: Int = 8): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        for ((i, v) in views.withIndex()) {
            val lp = (v.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0 && lp.marginStart == 0) lp.marginStart = dp(c, spacing)
            addView(v, lp)
        }
    }

    fun column(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    fun weighted(v: View, w: Float = 1f): View { v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w); return v }

    fun switch(c: Context, s: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(c).apply {
        text = s; textSize = 14f; setTextColor(TEXT); isChecked = checked
        setPadding(0, dp(c, 6), 0, dp(c, 6))
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ACCENT, 0xFFB0B6C3.toInt()))
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ACCENT_DARK, 0xFF3A4150.toInt()))
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

    fun slider(c: Context, max: Int, value: Int, onChange: (Int, Boolean) -> Unit) = SeekBar(c).apply {
        this.max = max; progress = value
        progressTintList = ColorStateList.valueOf(ACCENT); thumbTintList = ColorStateList.valueOf(ACCENT)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p, false) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { onChange(s.progress, true) }
        })
    }

    fun numberField(c: Context, value: String) = EditText(c).apply {
        setText(value); textSize = 14f; setTextColor(TEXT); setSingleLine()
        inputType = InputType.TYPE_CLASS_NUMBER
        background = rounded(CHIP, dp(c, 10).toFloat())
        setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8))
    }

    fun horizontalProgress(c: Context) = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progressTintList = ColorStateList.valueOf(ACCENT)
    }

    fun scrollRow(c: Context, content: View) = HorizontalScrollView(c).apply {
        isHorizontalScrollBarEnabled = false
        addView(content)
    }

    fun dialog(c: Context, title: String) = AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title)

    fun formatTime(sec: Double): String {
        val s = sec.coerceAtLeast(0.0).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    fun frameMatch() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
}

/**
 * Single-choice chip row. Chips are real toggle controls (selected state is announced to
 * accessibility services); [onSelect] fires only on user taps.
 */
class ChipRow<T>(private val c: Context, items: List<Pair<String, T>>, selected: T?, private val onSelect: (T) -> Unit) {
    val content = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
    val view: View = Ui.scrollRow(c, content)
    private val chips = ArrayList<Pair<TextView, T>>()
    var selected: T? = selected; private set

    init { setItems(items, selected) }

    fun setItems(items: List<Pair<String, T>>, sel: T?) {
        content.removeAllViews(); chips.clear()
        for ((label, value) in items) {
            val tv = TextView(c).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER
                setPadding(Ui.dp(c, 14), Ui.dp(c, 9), Ui.dp(c, 14), Ui.dp(c, 9))
                minHeight = Ui.dp(c, 38)
                isClickable = true; isFocusable = true
                setOnClickListener { select(value); onSelect(value) }
            }
            content.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(c, 6) })
            chips += tv to value
        }
        select(sel)
    }

    fun select(value: T?) {
        selected = value
        for ((tv, v) in chips) {
            val on = v == value
            tv.isSelected = on
            tv.setTextColor(if (on) 0xFF06201E.toInt() else Ui.TEXT)
            tv.background = Ui.ripple(Ui.rounded(if (on) Ui.ACCENT else Ui.CHIP, Ui.dp(c, 19).toFloat()))
        }
        // Keep the selected chip visible (long rows scroll horizontally).
        chips.firstOrNull { it.second == value }?.first?.let { tv ->
            view.post {
                val sv = view as HorizontalScrollView
                val m = Ui.dp(c, 24)
                when {
                    tv.left < sv.scrollX -> sv.scrollTo((tv.left - m).coerceAtLeast(0), 0)
                    tv.right > sv.scrollX + sv.width -> sv.scrollTo(tv.right + m - sv.width, 0)
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) { for ((tv, _) in chips) { tv.isEnabled = enabled; tv.alpha = if (enabled) 1f else 0.4f } }
}
