package com.rhythmphysics.core.audio

import com.rhythmphysics.core.music.MusicEvent
import kotlinx.serialization.Serializable

@Serializable
data class Onset(val timeSec: Double, val strength: Float)

@Serializable
data class AnalysisSettings(
    val analysisSampleRate: Int = 22050,
    val fftSize: Int = 1024,
    val hop: Int = 256,
    val onsetThreshold: Float = 0.12f,
    val minOnsetGapSec: Double = 0.05,
    val minBpm: Double = 60.0,
    val maxBpm: Double = 200.0,
    val beatsPerBar: Int = 4,
)

/** Result of offline analysis of real audio. Cached per (content fingerprint, version, settings). */
@Serializable
data class AudioAnalysis(
    val version: Int,
    val settings: AnalysisSettings,
    val durationSec: Double,
    val sourceSampleRate: Int,
    val bpm: Double,
    val bpmConfidence: Float,
    val beatTimes: List<Double>,
    val downbeatTimes: List<Double>,
    val downbeatConfidence: Float,
    val onsets: List<Onset>,
    /** Normalized events (beats + onsets merged, with spectral features). Roles are assigned later. */
    val events: List<MusicEvent>,
    /** Peak envelope for the timeline waveform, [waveformRate] points per second, capped in size. */
    val waveform: List<Float>,
    val waveformRate: Float,
    /** Section boundaries (novelty peaks snapped to downbeats), used by Journey mode. */
    val sections: List<Double>,
    val keyPitchClass: Int?,
    val loudnessDb: Float,
)
