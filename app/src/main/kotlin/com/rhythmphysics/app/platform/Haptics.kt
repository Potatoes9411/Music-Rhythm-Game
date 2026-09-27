package com.rhythmphysics.app.platform

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

enum class HapticLevel(val label: String) { OFF("Off"), LIGHT("Light"), MEDIUM("Medium") }

/**
 * Impact haptics. Only strong (major) contacts vibrate, rate-limited so dense passages never turn
 * into a continuous buzz. Haptics are a side effect only: they never feed back into the simulation.
 */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)

    @Volatile var level = HapticLevel.OFF
    private var lastMs = 0L

    val available: Boolean get() = vibrator?.hasVibrator() == true

    /** [strength] is the impact importance in 0..1. Safe to call from the render thread. */
    fun impact(strength: Float) {
        val lv = level
        if (lv == HapticLevel.OFF) return
        val v = vibrator ?: return
        val threshold = if (lv == HapticLevel.LIGHT) 0.62f else 0.45f
        if (strength < threshold) return
        val now = SystemClock.uptimeMillis()
        val minGap = if (lv == HapticLevel.LIGHT) 140L else 90L
        if (now - lastMs < minGap) return
        lastMs = now
        val (ms, amp) = if (lv == HapticLevel.LIGHT) 10L to (40 + 60 * strength).toInt() else 18L to (90 + 120 * strength).toInt()
        try {
            v.vibrate(if (v.hasAmplitudeControl()) VibrationEffect.createOneShot(ms, amp.coerceIn(1, 255)) else VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }
}
