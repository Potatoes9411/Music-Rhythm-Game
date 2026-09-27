package com.rhythmphysics.core.engine

import com.rhythmphysics.core.diag.SyncLogger
import com.rhythmphysics.core.mechanic.EngineSink
import com.rhythmphysics.core.mechanic.MechanicCheckpoint
import com.rhythmphysics.core.mechanic.MechanicContext
import com.rhythmphysics.core.mechanic.MechanicController
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.mechanic.square.SquareMechanic
import com.rhythmphysics.core.music.MusicEvent
import com.rhythmphysics.core.preset.Preset
import com.rhythmphysics.core.preset.PresetManager
import com.rhythmphysics.core.render.Colors
import com.rhythmphysics.core.render.DrawList
import com.rhythmphysics.core.render.RenderSettings
import com.rhythmphysics.core.render.Viewport
import com.rhythmphysics.core.session.JourneySchedule
import com.rhythmphysics.core.session.RhythmSession
import com.rhythmphysics.core.session.SceneConfig
import com.rhythmphysics.core.session.ViewMode
import com.rhythmphysics.core.util.StateWriter

/** Creates engine instances for a mechanic type. */
object Mechanics {
    private val factories = HashMap<MechanicType, () -> MechanicController>()

    init {
        register(MechanicType.SQUARE) { SquareMechanic() }
    }

    fun register(type: MechanicType, f: () -> MechanicController) { factories[type] = f }
    fun create(type: MechanicType): MechanicController = factories[type]?.invoke() ?: error("No engine for $type")
    fun isAvailable(type: MechanicType) = factories.containsKey(type)
}

/**
 * One mechanic instance placed in the frame (Focus/Duet/Quad) or in time (Journey segments).
 * All slots share the same session, event stream, clock and seed.
 */
class Slot(
    val key: String,
    val type: MechanicType,
    val preset: Preset,
    /** Region within the frame, in fractions (x, y, w, h). */
    val region: FloatArray,
    val windowStart: Double,
    val windowEnd: Double,
    /** When the slot is alive (instantiated and stepped) — wider than its window for transitions. */
    val liveStart: Double,
    val liveEnd: Double,
) {
    var mechanic: MechanicController? = null
    fun viewport(frame: Viewport) = Viewport(
        frame.x + region[0] * frame.w, frame.y + region[1] * frame.h, region[2] * frame.w, region[3] * frame.h,
    )
}

class MechanicDirector(
    val session: RhythmSession,
    val config: SceneConfig,
    val frame: Viewport,
    private val sink: EngineSink,
    val sync: SyncLogger,
    private val presetResolver: (String) -> Preset? = { PresetManager.byId(it) },
) {
    val slots: List<Slot>
    val journey: JourneySchedule?
    private val eventCache = HashMap<Preset, Pair<List<MusicEvent>, List<MusicEvent>>>()
    var fastForward = false
        set(v) { field = v; slots.forEach { it.mechanic?.fastForward = v } }

    init {
        journey = when (config.viewMode) {
            ViewMode.JOURNEY -> JourneyPlanner.autoSchedule(session, config, presetResolver)
            ViewMode.SCRIPTED_JOURNEY -> config.journey ?: JourneyPlanner.autoSchedule(session, config, presetResolver)
            else -> null
        }
        slots = buildSlots()
    }

    fun presetFor(type: MechanicType): Preset =
        config.presetIds[type]?.let(presetResolver)?.takeIf { it.mechanic == type } ?: PresetManager.defaultFor(type)

    private fun buildSlots(): List<Slot> {
        val dur = session.durationSec
        val full = floatArrayOf(0f, 0f, 1f, 1f)
        return when (config.viewMode) {
            ViewMode.FOCUS -> listOf(Slot("focus", config.focus, presetFor(config.focus), full, 0.0, dur, -1e9, 1e9))
            ViewMode.DUET -> {
                val a = config.duet.getOrElse(0) { MechanicType.SQUARE }
                val b = config.duet.getOrElse(1) { MechanicType.ARCH }
                val (ra, rb) = if (frame.isPortrait || !frame.isLandscape && frame.h >= frame.w)
                    floatArrayOf(0f, 0f, 1f, 0.5f) to floatArrayOf(0f, 0.5f, 1f, 0.5f)
                else floatArrayOf(0f, 0f, 0.5f, 1f) to floatArrayOf(0.5f, 0f, 0.5f, 1f)
                listOf(Slot("duet0", a, presetFor(a), ra, 0.0, dur, -1e9, 1e9), Slot("duet1", b, presetFor(b), rb, 0.0, dur, -1e9, 1e9))
            }
            ViewMode.QUAD -> {
                val types = MechanicType.values().filter { Mechanics.isAvailable(it) }
                val regions = listOf(
                    floatArrayOf(0f, 0f, 0.5f, 0.5f), floatArrayOf(0.5f, 0f, 0.5f, 0.5f),
                    floatArrayOf(0f, 0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f),
                )
                types.take(4).mapIndexed { i, t -> Slot("quad$i", t, presetFor(t), regions[i], 0.0, dur, -1e9, 1e9) }
            }
            ViewMode.JOURNEY, ViewMode.SCRIPTED_JOURNEY -> {
                val j = journey!!
                val half = j.transitionSec / 2
                j.segments.mapIndexed { i, s ->
                    val preset = presetResolver(s.presetId)?.takeIf { it.mechanic == s.mechanic } ?: presetFor(s.mechanic)
                    Slot("seg$i", s.mechanic, preset, full, s.startSec, s.endSec,
                        if (i == 0) -1e9 else s.startSec - half - 0.05,
                        if (i == j.segments.lastIndex) 1e9 else s.endSec + half + 0.05)
                }
            }
        }
    }

    private fun events(p: Preset): Pair<List<MusicEvent>, List<MusicEvent>> = eventCache.getOrPut(p) {
        val mapping = config.eventMapping ?: p.eventMapping
        val all = session.source.events(mapping)
        all to all
    }

    private fun instantiate(slot: Slot): MechanicController {
        val m = Mechanics.create(slot.type)
        val (phys, all) = events(slot.preset)
        val vp = slot.viewport(frame)
        m.loadSession(
            MechanicContext(
                session, slot.preset, config.seed, vp.aspect, phys, all,
                slot.windowStart, slot.windowEnd, sink, sync,
            )
        )
        m.fastForward = fastForward
        slot.mechanic = m
        return m
    }

    /** Instantiates slots alive at [t] without stepping (so the first frame renders content). */
    fun prepare(t: Double) {
        for (s in slots) if (s.mechanic == null && t >= s.liveStart && t <= s.liveEnd) instantiate(s)
    }

    fun fixedUpdate(dt: Double, songTime: Double) {
        for (s in slots) {
            val alive = songTime >= s.liveStart && songTime <= s.liveEnd
            if (alive) {
                val m = s.mechanic ?: instantiate(s)
                m.fixedUpdate(dt, songTime)
            } else if (s.mechanic != null) {
                s.mechanic?.dispose()
                s.mechanic = null
            }
        }
    }

    fun render(dl: DrawList, renderTime: Double, alpha: Float, rs: RenderSettings) {
        if (journey != null) {
            JourneyRenderer.render(this, dl, frame, renderTime, alpha, rs)
            return
        }
        for (s in slots) {
            val m = s.mechanic ?: continue
            val vp = s.viewport(frame)
            m.render(dl, vp, renderTime, alpha, rs)
        }
        if (slots.size > 1) {
            // thin separators between split views
            val sep = Colors.withAlpha(Colors.BLACK, 0.85f)
            for (s in slots) {
                val vp = s.viewport(frame)
                dl.rectStroke(vp.x, vp.y, vp.w, vp.h, frame.unit * 0.004f, sep)
            }
        }
    }

    fun activeSlots(t: Double) = slots.filter { it.mechanic != null && t >= it.liveStart && t <= it.liveEnd }

    /** Primary slot at time t (for the debug overlay / UI). */
    fun primarySlot(t: Double): Slot? = when {
        journey != null -> slots[journey.segmentAt(t)]
        else -> slots.firstOrNull()
    }

    // ---- checkpoints ------------------------------------------------------------------------

    fun checkpoint(): ByteArray {
        val w = StateWriter()
        w.i(slots.size)
        for (s in slots) {
            val m = s.mechanic
            w.b(m != null)
            if (m != null) {
                val cp = m.createCheckpoint().bytes
                w.i(cp.size); w.out.write(cp)
            }
        }
        return w.bytes()
    }

    fun restore(bytes: ByteArray) {
        val input = java.io.DataInputStream(bytes.inputStream())
        val n = input.readInt()
        require(n == slots.size) { "checkpoint does not match layout" }
        for (s in slots) {
            val present = input.readBoolean()
            if (present) {
                val len = input.readInt()
                val b = ByteArray(len); input.readFully(b)
                val m = s.mechanic ?: instantiate(s)
                m.restoreCheckpoint(MechanicCheckpoint(s.type, b))
            } else if (s.mechanic != null) {
                s.mechanic?.dispose(); s.mechanic = null
            }
        }
    }

    fun resetAll() {
        for (s in slots) { s.mechanic?.dispose(); s.mechanic = null }
    }

    fun dispose() = resetAll()
}
