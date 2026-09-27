package com.rhythmphysics.core.session

import com.rhythmphysics.core.audio.AudioAnalysis
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.midi.MidiFile
import com.rhythmphysics.core.music.EventMapper
import com.rhythmphysics.core.music.EventMappingSettings
import com.rhythmphysics.core.music.EventNormalizer
import com.rhythmphysics.core.music.MusicEvent
import com.rhythmphysics.core.render.AspectRatio
import kotlinx.serialization.Serializable

@Serializable
enum class MediaType { MIDI, AUDIO, NONE }

@Serializable
enum class ViewMode(val label: String) {
    FOCUS("Focus"), JOURNEY("Journey"), SCRIPTED_JOURNEY("Scripted Journey"), DUET("Duet"), QUAD("Quad");
}

@Serializable
enum class AudioMode(val label: String) {
    ORIGINAL_AND_VISUALS("Original audio + visualization"),
    ORIGINAL_AND_COLLISION_LAYER("Original audio + collision layer"),
    ANALYSIS_ONLY("Analysis only (muted)"),
}

@Serializable
data class JourneySegment(
    val startSec: Double,
    val endSec: Double,
    val mechanic: MechanicType,
    val presetId: String,
    val label: String = "",
)

@Serializable
data class JourneySchedule(val segments: List<JourneySegment>, val transitionSec: Double = 1.2) {
    fun segmentAt(t: Double): Int {
        for (i in segments.indices) if (t < segments[i].endSec) return i
        return segments.lastIndex
    }
}

/**
 * Everything that determines what the simulation does (besides the media itself).
 * The replay recipe is this config plus the source fingerprint, versions and preset bodies.
 */
@Serializable
data class SceneConfig(
    val seed: Long = 1337L,
    val aspect: AspectRatio = AspectRatio.PORTRAIT_9_16,
    val viewMode: ViewMode = ViewMode.FOCUS,
    val focus: MechanicType = MechanicType.SQUARE,
    val presetIds: Map<MechanicType, String> = emptyMap(),
    /** Used for SCRIPTED_JOURNEY; JOURNEY derives a schedule from song sections. */
    val journey: JourneySchedule? = null,
    val duet: List<MechanicType> = listOf(MechanicType.SQUARE, MechanicType.ARCH),
    /** Null = use each preset's own event mapping. */
    val eventMapping: EventMappingSettings? = null,
    val audioMode: AudioMode = AudioMode.ORIGINAL_AND_VISUALS,
)

/** Supplies normalized events for the session's media. */
interface EventSource {
    fun events(mapping: EventMappingSettings): List<MusicEvent>
    /** Melody note stream for generative mechanics (collisions consume notes). */
    fun noteStream(): List<MusicEvent>
}

class MidiEventSource(val midi: MidiFile) : EventSource {
    private val cache = HashMap<EventMappingSettings, List<MusicEvent>>()
    override fun events(mapping: EventMappingSettings) = cache.getOrPut(mapping) {
        val raw = EventNormalizer.fromMidi(midi, mapping.polyphony, mapping.selectedTracks.toSet())
        EventMapper.map(raw, mapping)
    }
    override fun noteStream(): List<MusicEvent> =
        EventNormalizer.fromMidi(midi, com.rhythmphysics.core.music.PolyphonyMapping.MELODY)
}

class AudioEventSource(val analysis: AudioAnalysis) : EventSource {
    private val cache = HashMap<EventMappingSettings, List<MusicEvent>>()
    override fun events(mapping: EventMappingSettings) = cache.getOrPut(mapping) { EventMapper.map(analysis.events, mapping) }
    override fun noteStream(): List<MusicEvent> = analysis.events
}

/** Sandbox (no song): an empty event stream. */
object NoEventSource : EventSource {
    override fun events(mapping: EventMappingSettings) = emptyList<MusicEvent>()
    override fun noteStream() = emptyList<MusicEvent>()
}

/** One loaded song/MIDI (or sandbox) shared by every mechanic, view mode and the recorder. */
class RhythmSession(
    val sessionId: String,
    val mediaUri: String,
    val mediaFingerprint: String,
    val mediaType: MediaType,
    val title: String,
    val durationSec: Double,
    val source: EventSource,
    val analysis: AudioAnalysis? = null,
    val midi: MidiFile? = null,
) {
    val sections: List<Double>
        get() = analysis?.sections ?: midi?.let { midiSections(it) } ?: emptyList()

    val bpm: Double get() = analysis?.bpm ?: midi?.initialBpm ?: 120.0

    private val midiSectionCache: List<Double>? by lazy { midi?.let { com.rhythmphysics.core.music.MidiStructure.sections(it) } }
    private fun midiSections(@Suppress("UNUSED_PARAMETER") m: MidiFile): List<Double> = midiSectionCache ?: emptyList()

    companion object {
        fun sandbox(durationSec: Double = 600.0) = RhythmSession(
            "sandbox", "", "sandbox", MediaType.NONE, "Sandbox", durationSec, NoEventSource,
        )
    }
}
