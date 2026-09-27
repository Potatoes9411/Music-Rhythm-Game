package com.rhythmphysics.app.render

/** Fits the creator frame (e.g. 1080x1920) into a surface, preserving aspect (pure, unit-tested). */
data class Letterbox(val scale: Float, val offsetX: Float, val offsetY: Float, val width: Float, val height: Float) {
    /** Surface pixel -> creator frame coordinates. */
    fun toFrameX(px: Float) = (px - offsetX) / scale
    fun toFrameY(py: Float) = (py - offsetY) / scale

    companion object {
        fun fit(frameW: Float, frameH: Float, surfaceW: Float, surfaceH: Float): Letterbox {
            val s = minOf(surfaceW / frameW, surfaceH / frameH)
            val w = frameW * s; val h = frameH * s
            return Letterbox(s, (surfaceW - w) / 2, (surfaceH - h) / 2, w, h)
        }
    }
}
