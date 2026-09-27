package com.rhythmphysics.core.music

import com.rhythmphysics.core.midi.MidiFile
import com.rhythmphysics.core.midi.MidiNote
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

@Serializable
enum class PolyphonyMapping {
    MELODY, HIGHEST_VOICE, LOWEST_VOICE, BASS, PERCUSSION, ALL_NOTES, CHORD_REDUCTION, TRACK_SELECTION, DENSITY_LIMITED;

    val label: String get() = name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}

/** Beat grid derived from a MIDI tempo map + time signatures. */
class BeatGrid(val beatTimes: DoubleArray, val downbeat: BooleanArray) {
    fun nearestBeat(t: Double): Int {
        if (beatTimes.isEmpty()) return -1
        var lo = 0; var hi = beatTimes.size - 1
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (beatTimes[mid] < t) lo = mid + 1 else hi = mid }
        return if (lo > 0 && abs(beatTimes[lo - 1] - t) < abs(beatTimes[lo] - t)) lo - 1 else lo
    }

    companion object {
        fun fromMidi(midi: MidiFile): BeatGrid {
            val times = ArrayList<Double>()
            val down = ArrayList<Boolean>()
            val sigs = midi.timeSignatures.ifEmpty { listOf(com.rhythmphysics.core.midi.TimeSignature(0, 0.0, 4, 4)) }
            val endSec = midi.durationSec + 0.001
            var sigIdx = 0
            var tick = 0L
            var beatInBar = 0
            // Bars restart at every time-signature change.
            while (true) {
                val sig = sigs[sigIdx]
                val beatTicks = (midi.ppq * 4L / sig.denominator).coerceAtLeast(1)
                val sec = midi.tickToSec(tick)
                if (sec > endSec) break
                times += sec
                down += beatInBar == 0
                tick += beatTicks
                beatInBar = (beatInBar + 1) % sig.numerator.coerceAtLeast(1)
                if (sigIdx + 1 < sigs.size && tick >= sigs[sigIdx + 1].tick) {
                    sigIdx++
                    tick = sigs[sigIdx].tick
                    beatInBar = 0
                }
                if (times.size > 200_000) break
            }
            return BeatGrid(times.toDoubleArray(), down.toBooleanArray())
        }
    }
}

/** Converts MIDI notes into normalized [MusicEvent]s using one of the polyphony mappings. */
object EventNormalizer {
    const val CLUSTER_SEC = 0.035

    fun fromMidi(
        midi: MidiFile,
        mapping: PolyphonyMapping,
        selectedTracks: Set<Int> = emptySet(),
        densityMinInterval: Double = 0.12,
    ): List<MusicEvent> {
        val grid = BeatGrid.fromMidi(midi)
        val tonal = midi.notes.filter { !it.isPercussion }
        val drums = midi.notes.filter { it.isPercussion }
        val clusters: List<List<MidiNote>> = when (mapping) {
            PolyphonyMapping.ALL_NOTES -> midi.notes.map { listOf(it) }
            PolyphonyMapping.HIGHEST_VOICE -> cluster(tonal).map { c -> listOf(c.maxBy { it.pitch }) }
            PolyphonyMapping.LOWEST_VOICE -> cluster(tonal).map { c -> listOf(c.minBy { it.pitch }) }
            PolyphonyMapping.BASS -> {
                val low = tonal.filter { it.pitch < 55 }
                val src = if (low.size >= 8) low else tonal
                cluster(src).map { c -> listOf(c.minBy { it.pitch }) }
            }
            PolyphonyMapping.PERCUSSION -> cluster(drums.ifEmpty { tonal })
            PolyphonyMapping.CHORD_REDUCTION -> cluster(tonal)
            PolyphonyMapping.TRACK_SELECTION -> cluster(midi.notes.filter { it.track in selectedTracks }.ifEmpty { tonal })
            PolyphonyMapping.MELODY -> {
                val track = melodyTrack(tonal)
                val src = if (track == null) tonal else tonal.filter { it.track == track }
                cluster(src).map { c -> listOf(c.maxBy { it.pitch }) }
            }
            PolyphonyMapping.DENSITY_LIMITED -> cluster(midi.notes)
        }
        var events = clusters.filter { it.isNotEmpty() }.map { c -> toEvent(c, grid, mapping) }
            .sortedWith(compareBy({ it.timeSec }, { -(it.midiNote ?: 0) }))
        if (mapping == PolyphonyMapping.DENSITY_LIMITED) events = EventMapper.enforceMinInterval(events, densityMinInterval)
        return events.mapIndexed { i, e -> e.copy(id = i.toLong()) }
    }

    /** All notes as individual events (used by generative mechanics that consume notes one by one). */
    fun noteStream(midi: MidiFile, mapping: PolyphonyMapping): List<MusicEvent> = fromMidi(midi, mapping)

    private fun cluster(notes: List<MidiNote>): List<List<MidiNote>> {
        val sorted = notes.sortedWith(compareBy({ it.startSec }, { it.pitch }))
        val out = ArrayList<List<MidiNote>>()
        var cur = ArrayList<MidiNote>()
        for (n in sorted) {
            if (cur.isNotEmpty() && n.startSec - cur[0].startSec > CLUSTER_SEC) { out += cur; cur = ArrayList() }
            cur += n
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun melodyTrack(tonal: List<MidiNote>): Int? {
        val byTrack = tonal.groupBy { it.track }.filter { it.value.size >= 8 }
        if (byTrack.isEmpty()) return null
        return byTrack.maxByOrNull { (_, ns) ->
            val meanPitch = ns.sumOf { it.pitch }.toDouble() / ns.size
            val onsets = cluster(ns).size.toDouble()
            val poly = ns.size / onsets // average notes per onset
            meanPitch + 6.0 * ln(ns.size.toDouble()) / ln(10.0) - (poly - 1.0) * 8.0
        }?.key
    }

    private fun toEvent(c: List<MidiNote>, grid: BeatGrid, mapping: PolyphonyMapping): MusicEvent {
        val top = c.maxBy { it.pitch }
        val t = c.minOf { it.startSec }
        val vel = c.maxOf { it.velocity } / 127f
        val dur = c.maxOf { it.durationSec }
        val bi = grid.nearestBeat(t)
        val onBeat = bi >= 0 && abs(grid.beatTimes[bi] - t) < 0.04
        val onDown = onBeat && grid.downbeat[bi]
        val beatStrength = when { onDown -> 1f; onBeat -> 0.6f; else -> 0.25f }
        val drum = top.isPercussion
        val drumWeight = if (drum) when (top.pitch) { 35, 36 -> 1f; 38, 40 -> 0.85f; 49, 57 -> 0.9f; else -> 0.45f } else 1f
        val importance = (0.42f * vel + 0.28f * beatStrength + 0.15f * min(1f, c.size / 4f) +
            0.15f * min(1f, (dur / 0.6).toFloat())) * drumWeight
        return MusicEvent(
            id = 0,
            timeSec = t,
            durationSec = dur,
            sourceType = if (c.size > 1 && mapping != PolyphonyMapping.PERCUSSION) SourceType.MIDI_CHORD else SourceType.MIDI_NOTE,
            midiNote = top.pitch,
            velocity = vel,
            beatStrength = beatStrength,
            isBeat = onBeat,
            isDownbeat = onDown,
            importance = importance.coerceIn(0f, 1f),
            track = top.track,
            channel = top.channel,
            voices = c.size,
        )
    }
}

/** Structural section boundaries for MIDI (bar-level novelty of instrumentation/density/register). */
object MidiStructure {
    fun sections(midi: MidiFile): List<Double> {
        val grid = BeatGrid.fromMidi(midi)
        val bars = grid.beatTimes.filterIndexed { i, _ -> grid.downbeat[i] }
        if (bars.size < 10) return emptyList()
        val nb = bars.size
        val feats = Array(nb) { DoubleArray(20) }
        for (n in midi.notes) {
            var lo = 0; var hi = nb - 1
            while (lo < hi) { val m = (lo + hi + 1) ushr 1; if (bars[m] <= n.startSec) lo = m else hi = m - 1 }
            val f = feats[lo]
            f[n.channel] += 1.0
            f[16] += n.velocity / 127.0
            if (!n.isPercussion) { f[17] += n.pitch.toDouble(); f[18] += 1.0 }
            f[19] += 1.0
        }
        for (f in feats) {
            if (f[18] > 0) f[17] = f[17] / f[18] / 12.0 else f[17] = 0.0
            val total = f[19].coerceAtLeast(1.0)
            for (c in 0 until 16) f[c] = kotlin.math.sqrt(f[c] / total) * 2   // instrumentation profile
            f[16] = f[16] / total; f[18] = kotlin.math.ln(1 + f[19]) ; f[19] = 0.0
        }
        fun dist(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) s += (a[i] - b[i]) * (a[i] - b[i]); return kotlin.math.sqrt(s) }
        val w = 2
        val nov = DoubleArray(nb)
        for (b in w until nb - w + 1) {
            val left = DoubleArray(20); val right = DoubleArray(20)
            for (i in b - w until b) for (k in 0 until 20) left[k] += feats[i][k] / w
            for (i in b until minOf(nb, b + w)) for (k in 0 until 20) right[k] += feats[i][k] / w
            nov[b] = dist(left, right)
        }
        // Strongest local maxima (ignoring the final bars' ring-out), about one boundary per ~18 s,
        // at least 6 bars apart (sections are rarely shorter).
        val last = nb - 3
        val want = kotlin.math.round(midi.durationSec / 18.0).toInt().coerceIn(2, 12)
        val cands = (w until last).filter { b -> nov[b] > 0.0 && nov[b] >= nov[b - 1] && nov[b] >= nov[b + 1] }.sortedByDescending { nov[it] }
        val chosen = ArrayList<Int>()
        for (b in cands) { if (chosen.size >= want) break; if (chosen.all { abs(it - b) >= 6 }) chosen += b }
        return chosen.sorted().map { bars[it] }.filter { it > 3.0 && it < midi.durationSec - 3.0 }
    }
}
