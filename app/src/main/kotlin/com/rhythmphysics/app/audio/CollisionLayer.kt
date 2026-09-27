package com.rhythmphysics.app.audio

/**
 * The optional "collision layer": one SoundFont note per physical contact, mixed over the original
 * song. Shared by live playback and video export so both sound identical.
 */
object CollisionLayer {
    /** GM program 11 (Vibraphone, 0-based). */
    const val PROGRAM = 11
    const val IMPACT_NOTE_SEC = 0.45
    const val GENERATIVE_NOTE_SEC = 0.6

    /** Uses the event's own pitch when it has one (MIDI), otherwise a pentatonic-ish fallback. */
    fun noteFor(eventId: Long, note: Int?): Int = (note ?: (72 + Math.floorMod(eventId, 5L).toInt() * 2)).coerceIn(0, 127)

    fun velocityFor(strength: Float): Float = (0.35f + 0.5f * strength).coerceIn(0.1f, 1f)
}
