package com.rhythmphysics.app.platform

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.rhythmphysics.core.render.Quality

/**
 * Watches the platform thermal status (API 29+) and caps the *visual* quality tier when the device
 * heats up. Simulation and musical timing are unaffected by quality, so throttling never changes
 * what happens — only how many particles/glow samples are drawn.
 */
class ThermalMonitor(context: Context, private val onChange: (Quality?) -> Unit) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    @Volatile var status = 0; private set
    private var listener: Any? = null

    /** Highest quality allowed at the current thermal status, or null for "no cap". */
    val cap: Quality? get() = capFor(status)

    fun start() {
        if (Build.VERSION.SDK_INT < 29 || listener != null) return
        val l = PowerManager.OnThermalStatusChangedListener { s -> status = s; onChange(capFor(s)) }
        pm.addThermalStatusListener(l)
        listener = l
        status = pm.currentThermalStatus
    }

    fun stop() {
        if (Build.VERSION.SDK_INT < 29) return
        (listener as? PowerManager.OnThermalStatusChangedListener)?.let { pm.removeThermalStatusListener(it) }
        listener = null
    }

    val label: String get() = when (status) {
        0 -> "none"; 1 -> "light"; 2 -> "moderate"; 3 -> "severe"; 4 -> "critical"; 5 -> "emergency"; 6 -> "shutdown"; else -> "?"
    }

    companion object {
        fun capFor(s: Int): Quality? = when {
            s >= 3 -> Quality.LOW       // SEVERE and above
            s == 2 -> Quality.MEDIUM    // MODERATE
            else -> null
        }
        fun apply(user: Quality, cap: Quality?): Quality = if (cap != null && cap.ordinal < user.ordinal) cap else user
    }
}
