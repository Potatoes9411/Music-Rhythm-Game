package com.rhythmphysics.core.music

import com.rhythmphysics.core.math.MathUtil
import kotlinx.serialization.Serializable
import java.util.TreeSet

@Serializable
enum class EventMode {
    SPARSE, BEAT, PERCUSSIVE, MELODIC, DENSE, HYBRID;
    val label: String get() = name.lowercase().replaceFirstChar(Char::uppercase)
}

@Serializable
data class EventMappingSettings(
    val mode: EventMode = EventMode.HYBRID,
    /** 0 = very sparse physical events, 1 = as dense as the mechanic allows. */
    val density: Float = 0.6f,
    val polyphony: PolyphonyMapping = PolyphonyMapping.HIGHEST_VOICE,
    val selectedTracks: List<Int> = emptyList(),
) {
    /** Minimum spacing between physical contacts implied by the density control. */
    val minIntervalSec: Double get() = MathUtil.lerp(0.60, 0.075, MathUtil.clamp(density.toDouble(), 0.0, 1.0))

    fun validated() = copy(density = MathUtil.clamp(density, 0f, 1f))
}

/**
 * Assigns [EventRole]s (which events become physical contacts vs. visual-only effects) and thins
 * events that are too close together. Deterministic: ties are broken by time then id.
 */
object EventMapper {

    fun map(events: List<MusicEvent>, settings: EventMappingSettings): List<MusicEvent> {
        val s = settings.validated()
        val strong = strongThreshold(events)
        val roled = events.map { e ->
            val role = when (s.mode) {
                EventMode.SPARSE -> if (e.isDownbeat || e.importance >= 0.85f) EventRole.MAJOR else EventRole.FX_ONLY
                EventMode.BEAT -> when {
                    e.isDownbeat -> EventRole.MAJOR
                    e.isBeat -> EventRole.NORMAL
                    else -> EventRole.FX_ONLY
                }
                EventMode.PERCUSSIVE -> when {
                    e.isDownbeat -> EventRole.MAJOR
                    percussive(e) >= 0.55f -> EventRole.NORMAL
                    percussive(e) >= 0.35f -> EventRole.MINOR
                    else -> EventRole.FX_ONLY
                }
                EventMode.MELODIC -> when {
                    e.midiNote == null && e.sourceType == SourceType.AUDIO_BEAT && e.onsetStrength == null -> EventRole.FX_ONLY
                    e.isDownbeat -> EventRole.MAJOR
                    e.importance >= 0.35f -> EventRole.NORMAL
                    else -> EventRole.MINOR
                }
                EventMode.DENSE -> if (e.isDownbeat) EventRole.MAJOR else if (e.isBeat) EventRole.NORMAL else EventRole.MINOR
                EventMode.HYBRID -> when {
                    e.isDownbeat -> EventRole.MAJOR
                    e.isBeat -> EventRole.NORMAL
                    e.importance >= strong -> EventRole.MINOR
                    else -> EventRole.FX_ONLY
                }
            }
            e.copy(role = role)
        }
        val minGap = if (s.mode == EventMode.SPARSE) maxOf(0.9, s.minIntervalSec) else s.minIntervalSec
        return thinPhysical(roled, minGap)
    }

    private fun percussive(e: MusicEvent): Float {
        val onset = e.onsetStrength ?: 0f
        val high = e.highEnergy ?: 0f
        val drum = if (e.channel == 9) 0.4f else 0f
        return MathUtil.clamp(0.6f * onset + 0.25f * high + drum + 0.15f * e.importance, 0f, 1f)
    }

    /** Importance threshold separating "strong" from "weak" onsets (upper ~40%). */
    private fun strongThreshold(events: List<MusicEvent>): Float {
        if (events.isEmpty()) return 0.5f
        val sorted = events.map { it.importance }.sorted()
        return MathUtil.clamp(sorted[(sorted.size * 0.6).toInt().coerceAtMost(sorted.size - 1)], 0.2f, 0.8f)
    }

    private fun rank(r: EventRole) = when (r) { EventRole.MAJOR -> 3; EventRole.NORMAL -> 2; EventRole.MINOR -> 1; EventRole.FX_ONLY -> 0 }

    /**
     * Keeps physical events at least [minGap] apart. Higher role/importance wins; losers are demoted
     * to FX_ONLY (they still flash/emit particles), so no musical information is thrown away.
     */
    fun thinPhysical(events: List<MusicEvent>, minGap: Double): List<MusicEvent> {
        val order = events.indices.filter { events[it].role.physical }.sortedWith(
            compareByDescending<Int> { rank(events[it].role) }
                .thenByDescending { events[it].importance }
                .thenBy { events[it].timeSec }
                .thenBy { events[it].id }
        )
        val accepted = TreeSet<Double>()
        val keep = BooleanArray(events.size)
        for (i in order) {
            val t = events[i].timeSec
            val lo = accepted.floor(t)
            val hi = accepted.ceiling(t)
            if ((lo == null || t - lo >= minGap) && (hi == null || hi - t >= minGap)) {
                accepted += t
                keep[i] = true
            }
        }
        return events.mapIndexed { i, e -> if (e.role.physical && !keep[i]) e.copy(role = EventRole.FX_ONLY) else e }
    }

    /** Drops (rather than demotes) events closer than [minGap], keeping the most important. */
    fun enforceMinInterval(events: List<MusicEvent>, minGap: Double): List<MusicEvent> {
        val tmp = events.map { it.copy(role = EventRole.NORMAL) }
        val thinned = thinPhysical(tmp, minGap)
        return events.filterIndexed { i, _ -> thinned[i].role.physical }
    }

    /** Mechanic-specific extra thinning: a mechanic may need more room between contacts than the global density. */
    fun forMechanic(events: List<MusicEvent>, mechanicMinGap: Double): List<MusicEvent> {
        val gap = mechanicMinGap
        return thinPhysical(events, gap)
    }
}
