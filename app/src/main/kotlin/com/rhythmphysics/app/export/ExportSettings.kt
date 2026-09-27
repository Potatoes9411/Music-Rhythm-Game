package com.rhythmphysics.app.export

import kotlinx.serialization.Serializable

@Serializable
data class ExportSettings(val width: Int, val height: Int, val fps: Int, val startSec: Double = 0.0, val endSec: Double = -1.0) {
    val frameDurationUs: Long get() = 1_000_000L / fps
    fun ptsForFrame(i: Long): Long = i * 1_000_000L / fps
    fun bitrate(): Int = (width.toLong() * height * fps * 0.14).toInt().coerceIn(2_000_000, 24_000_000)

    companion object {
        /** Creator targets; filtered by device encoder capabilities at runtime. */
        val PRESETS = listOf(
            "720 × 1280 (9:16)" to (720 to 1280), "1080 × 1920 (9:16)" to (1080 to 1920),
            "1280 × 720 (16:9)" to (1280 to 720), "1920 × 1080 (16:9)" to (1920 to 1080),
            "1080 × 1080 (1:1)" to (1080 to 1080),
        )
    }
}
