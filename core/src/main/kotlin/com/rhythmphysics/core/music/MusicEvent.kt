package com.rhythmphysics.core.music

import kotlinx.serialization.Serializable

@Serializable
enum class SourceType { MIDI_NOTE, MIDI_CHORD, AUDIO_ONSET, AUDIO_BEAT, SYNTHETIC }

/**
 * How strongly an event should manifest physically. Assigned by [EventMapper].
 * MAJOR/NORMAL/MINOR create physical contacts; FX_ONLY only drives flashes, particles and color.
 */
@Serializable
enum class EventRole { MAJOR, NORMAL, MINOR, FX_ONLY;
    val physical: Boolean get() = this != FX_ONLY
}

/** The one normalized event model every mechanic consumes (MIDI and analyzed audio alike). */
@Serializable
data class MusicEvent(
    val id: Long,
    val timeSec: Double,
    val durationSec: Double? = null,
    val sourceType: SourceType,
    val midiNote: Int? = null,
    val velocity: Float? = null,
    val onsetStrength: Float? = null,
    val beatStrength: Float? = null,
    val isBeat: Boolean = false,
    val isDownbeat: Boolean = false,
    val rms: Float? = null,
    val bassEnergy: Float? = null,
    val midEnergy: Float? = null,
    val highEnergy: Float? = null,
    val spectralCentroid: Float? = null,
    val chroma: FloatArray? = null,
    val importance: Float,
    val track: Int? = null,
    val channel: Int? = null,
    val role: EventRole = EventRole.NORMAL,
    /** Number of notes merged into this event (chords). */
    val voices: Int = 1,
) {
    /** Pitch class 0..11 from the MIDI note, or from the dominant chroma bin for audio events. */
    val pitchClass: Int?
        get() = midiNote?.let { ((it % 12) + 12) % 12 } ?: chroma?.let { c ->
            var best = 0
            for (i in 1 until c.size) if (c[i] > c[best]) best = i
            if (c[best] > 0f) best else null
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MusicEvent) return false
        return id == other.id && timeSec == other.timeSec && durationSec == other.durationSec &&
            sourceType == other.sourceType && midiNote == other.midiNote && velocity == other.velocity &&
            onsetStrength == other.onsetStrength && beatStrength == other.beatStrength && isBeat == other.isBeat &&
            isDownbeat == other.isDownbeat && rms == other.rms && bassEnergy == other.bassEnergy &&
            midEnergy == other.midEnergy && highEnergy == other.highEnergy &&
            spectralCentroid == other.spectralCentroid && (chroma?.contentEquals(other.chroma) ?: (other.chroma == null)) &&
            importance == other.importance && track == other.track && channel == other.channel &&
            role == other.role && voices == other.voices
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + timeSec.hashCode()
        h = 31 * h + (midiNote ?: -1)
        h = 31 * h + importance.hashCode()
        h = 31 * h + role.hashCode()
        return h
    }
}

/** Sorted event list with time lookup helpers. */
class EventTrack(events: List<MusicEvent>) {
    val events: List<MusicEvent> = events.sortedWith(compareBy({ it.timeSec }, { it.id }))
    val size get() = events.size
    operator fun get(i: Int) = events[i]

    /** Index of the first event with timeSec > t. */
    fun firstAfter(t: Double): Int {
        var lo = 0; var hi = events.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (events[mid].timeSec <= t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Index of the first event with timeSec >= t. */
    fun firstAtOrAfter(t: Double): Int {
        var lo = 0; var hi = events.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (events[mid].timeSec < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun between(t0: Double, t1: Double): List<MusicEvent> = events.subList(firstAtOrAfter(t0), firstAtOrAfter(t1))

    val physical: List<MusicEvent> by lazy { this.events.filter { it.role.physical } }
}
