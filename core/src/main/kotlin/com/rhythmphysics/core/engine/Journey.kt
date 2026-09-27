package com.rhythmphysics.core.engine

import com.rhythmphysics.core.math.MathUtil
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.music.BeatGrid
import com.rhythmphysics.core.preset.Preset
import com.rhythmphysics.core.preset.PresetManager
import com.rhythmphysics.core.render.Colors
import com.rhythmphysics.core.render.DrawList
import com.rhythmphysics.core.render.RenderSettings
import com.rhythmphysics.core.render.Viewport
import com.rhythmphysics.core.session.JourneySchedule
import com.rhythmphysics.core.session.JourneySegment
import com.rhythmphysics.core.session.RhythmSession
import com.rhythmphysics.core.session.SceneConfig
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Builds a Journey schedule (mechanic per song section) from analysis sections or the MIDI bar grid. */
object JourneyPlanner {
    private const val MIN_SEGMENT = 9.0

    fun autoSchedule(session: RhythmSession, config: SceneConfig, presetResolver: (String) -> Preset?): JourneySchedule {
        val dur = session.durationSec.coerceAtLeast(1.0)
        val beats = beatTimes(session)
        var bounds = session.sections.filter { it > MIN_SEGMENT && it < dur - MIN_SEGMENT }.sorted()
        // Merge sections that are too short.
        val merged = ArrayList<Double>()
        var last = 0.0
        for (b in bounds) if (b - last >= MIN_SEGMENT) { merged += b; last = b }
        bounds = merged
        if (bounds.size < 3) {
            // Not enough structure detected: split into ~22 s parts snapped to beats.
            val n = (dur / 22.0).roundToInt().coerceIn(2, 9)
            bounds = (1 until n).map { snap(dur * it / n, beats) }
        }
        val edges = listOf(0.0) + bounds + listOf(dur)
        val available = MechanicType.values().filter { Mechanics.isAvailable(it) }
        val cycle = listOf(MechanicType.ARCH, MechanicType.PLATFORM, MechanicType.CIRCLE, MechanicType.SQUARE)
            .filter { it in available }.ifEmpty { available }
        val segs = ArrayList<JourneySegment>()
        val count = edges.size - 1
        for (i in 0 until count) {
            val type = when {
                i == 0 -> MechanicType.SQUARE.takeIf { it in available } ?: cycle[0]
                i == count - 1 && count >= 3 -> MechanicType.SQUARE.takeIf { it in available } ?: cycle[(i - 1) % cycle.size]
                else -> cycle[(i - 1) % cycle.size]
            }
            val presetId = config.presetIds[type]?.takeIf { presetResolver(it) != null } ?: PresetManager.defaultFor(type).id
            val label = when {
                i == 0 -> "Intro"
                i == count - 1 && count >= 3 -> "Outro"
                else -> "Part ${i + 1}"
            }
            segs += JourneySegment(edges[i], edges[i + 1], type, presetId, label)
        }
        return JourneySchedule(segs, transitionSec = 1.2)
    }

    private fun beatTimes(session: RhythmSession): List<Double> =
        session.analysis?.downbeatTimes?.ifEmpty { session.analysis.beatTimes }
            ?: session.midi?.let { m ->
                val g = BeatGrid.fromMidi(m)
                g.beatTimes.filterIndexed { i, _ -> g.downbeat[i] }
            } ?: emptyList()

    private fun snap(t: Double, beats: List<Double>): Double {
        if (beats.isEmpty()) return t
        var best = t; var bestD = Double.MAX_VALUE
        for (b in beats) { val d = abs(b - t); if (d < bestD) { bestD = d; best = b } }
        return if (bestD < 4.0) best else t
    }
}

/**
 * Renders Journey mode: one mechanic at a time, and during transitions a "portal" iris that opens
 * at the outgoing hero's screen position, with the hero morphing (square <-> orb) through it.
 * Audio, clock and event stream never restart.
 */
object JourneyRenderer {
    fun render(director: MechanicDirector, dl: DrawList, frame: Viewport, t: Double, alpha: Float, rs: RenderSettings) {
        val j = director.journey ?: return
        val slots = director.slots
        val i = j.segmentAt(t)
        val half = j.transitionSec / 2
        // Which boundary are we near?
        var outIdx = -1; var inIdx = -1; var u = 0.0
        if (i > 0 && t < j.segments[i].startSec + half) { outIdx = i - 1; inIdx = i; u = (t - (j.segments[i].startSec - half)) / j.transitionSec }
        else if (i < j.segments.lastIndex && t > j.segments[i].endSec - half) { outIdx = i; inIdx = i + 1; u = (t - (j.segments[i].endSec - half)) / j.transitionSec }
        val outM = slots.getOrNull(outIdx)?.mechanic
        val inM = slots.getOrNull(inIdx)?.mechanic
        if (outIdx < 0 || outM == null || inM == null) {
            slots[i].mechanic?.render(dl, frame, t, alpha, rs)
            return
        }
        u = MathUtil.clamp(u, 0.0, 1.0)
        val e = MathUtil.easeInOutSine(u).toFloat()
        val boundary = j.segments[outIdx].endSec
        val hero = outM.heroScreenPosition(frame, minOf(t, boundary)) ?: floatArrayOf(frame.cx, frame.cy, frame.unit * 0.08f)
        val cx = hero[0].coerceIn(frame.x, frame.x + frame.w)
        val cy = hero[1].coerceIn(frame.y, frame.y + frame.h)
        val maxR = maxOf(
            hypot((cx - frame.x).toDouble(), (cy - frame.y).toDouble()), hypot((frame.x + frame.w - cx).toDouble(), (cy - frame.y).toDouble()),
            hypot((cx - frame.x).toDouble(), (frame.y + frame.h - cy).toDouble()), hypot((frame.x + frame.w - cx).toDouble(), (frame.y + frame.h - cy).toDouble()),
        ).toFloat()
        val r = maxR * e
        val colorOut = outM.heroColor(t)
        val colorIn = inM.heroColor(t)
        val ringColor = Colors.lerp(if (colorOut == -1) Colors.WHITE else colorOut, if (colorIn == -1) Colors.WHITE else colorIn, e)

        outM.render(dl, frame, t, alpha, rs)
        if (r > 1f) {
            dl.pushClipCircle(cx, cy, r)
            inM.render(dl, frame, t, alpha, rs)
            dl.popClip()
            // Portal rim.
            val width = frame.unit * 0.012f * (1 - e * 0.6f)
            dl.circleStroke(cx, cy, r, width, Colors.withAlpha(ringColor, 0.85f * (1 - e * 0.5f)))
            dl.glow(cx, cy, r + width * 4, ringColor, 0.10f * (1 - e) * rs.bloomScale)
        }
        // Morphing hero passing through the portal: square (corner 14%) -> orb (corner 50%).
        if (u < 0.75) {
            val k = (u / 0.75).toFloat()
            val size = hero[2] * (1.0f + 0.6f * kotlin.math.sin(k * Math.PI).toFloat())
            val corner = size * (0.14f + 0.36f * k)
            val a = 1f - k * k
            dl.glow(cx, cy, size * 1.8f, ringColor, 0.5f * a * rs.bloomScale)
            dl.rect(cx - size / 2, cy - size / 2, size, size, Colors.withAlpha(ringColor, a), corner)
        }
    }
}
