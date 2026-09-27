package com.rhythmphysics.app.audio

/**
 * Turns sparse audio-hardware timestamps into a smooth, monotonic playback clock.
 * Pure logic (unit-tested): the audio device is the authority; this only interpolates between its
 * reports and slews small backward corrections instead of jumping.
 */
class ClockSmoother(private val maxBackwardSlewSec: Double = 0.020) {
    private var anchorPos = 0.0
    private var anchorNanos = 0L
    private var lastOut = 0.0
    private var hasAnchor = false

    fun reset(positionSec: Double, nowNanos: Long) {
        anchorPos = positionSec; anchorNanos = nowNanos; lastOut = positionSec; hasAnchor = true
    }

    /** New authoritative sample: the device says [positionSec] at [atNanos]. */
    fun anchor(positionSec: Double, atNanos: Long) {
        anchorPos = positionSec; anchorNanos = atNanos; hasAnchor = true
    }

    /** Extrapolated position at [nowNanos] (playing). Never goes backwards by more than a slew. */
    fun position(nowNanos: Long, playing: Boolean): Double {
        if (!hasAnchor) return lastOut
        val raw = if (playing) anchorPos + (nowNanos - anchorNanos) / 1e9 else anchorPos
        val out = when {
            raw >= lastOut -> raw
            lastOut - raw <= maxBackwardSlewSec -> lastOut // tiny jitter: hold
            else -> raw // real discontinuity (seek): accept
        }
        lastOut = out
        return out
    }
}
