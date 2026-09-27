package com.rhythmphysics.core.mechanic

import com.rhythmphysics.core.diag.SyncLogger
import com.rhythmphysics.core.music.MusicEvent
import com.rhythmphysics.core.preset.Preset
import com.rhythmphysics.core.render.DrawList
import com.rhythmphysics.core.render.RenderSettings
import com.rhythmphysics.core.render.Viewport
import com.rhythmphysics.core.session.RhythmSession
import kotlinx.serialization.Serializable

@Serializable
enum class MechanicType(val label: String) {
    SQUARE("Square"), CIRCLE("Circle"), ARCH("Arch"), PLATFORM("Platform");
}

class MechanicCheckpoint(val type: MechanicType, val bytes: ByteArray)

/** Side effects emitted by the simulation (never read back by it, so they cannot break determinism). */
interface EngineSink {
    /** A physical contact happened (haptics, optional collision sound layer). */
    fun onImpact(mechanic: MechanicType, timeSec: Double, strength: Float, eventId: Long, note: Int?) {}
    /** Generative mechanics request a note to be played now. */
    fun onNote(timeSec: Double, note: Int, velocity: Float, program: Int) {}

    object None : EngineSink
}

/** Everything a mechanic instance needs; one instance = one mechanic in one viewport slot/time window. */
class MechanicContext(
    val session: RhythmSession,
    val preset: Preset,
    val seed: Long,
    /** Aspect (w/h) of the viewport the mechanic composes for. Layout is re-composed per aspect, never cropped. */
    val aspect: Float,
    /** Physical (role-mapped) events for the whole song. */
    val events: List<MusicEvent>,
    /** Every normalized event including FX-only ones. */
    val allEvents: List<MusicEvent>,
    val windowStart: Double,
    val windowEnd: Double,
    val sink: EngineSink,
    val sync: SyncLogger,
) {
    val physicalEvents: List<MusicEvent> by lazy {
        events.filter { it.role.physical && it.timeSec >= windowStart - 1e-9 && it.timeSec <= windowEnd + 1e-9 }
    }
    val fxEvents: List<MusicEvent> by lazy {
        allEvents.filter { it.timeSec >= windowStart - 1e-9 && it.timeSec <= windowEnd + 1e-9 }
    }
}

/**
 * Common mechanic lifecycle. Timing contract:
 *  - [fixedUpdate] is called at a fixed rate (120 Hz) with the *song* time at the end of the step;
 *  - [render] may be called at any display rate with the exact render time; it must not mutate
 *    simulation state (so 60/90/120 Hz displays produce identical simulations).
 */
interface MechanicController {
    val type: MechanicType
    fun loadSession(ctx: MechanicContext)
    fun precompute(events: List<MusicEvent>)
    fun reset()
    fun fixedUpdate(dt: Double, songTime: Double)
    fun render(dl: DrawList, vp: Viewport, renderTime: Double, alpha: Float, rs: RenderSettings)
    fun createCheckpoint(): MechanicCheckpoint
    fun restoreCheckpoint(checkpoint: MechanicCheckpoint)
    /** Mechanics may implement direct seeking; the engine otherwise restores a checkpoint and fast-forwards. */
    fun seek(timeSec: Double) {}
    fun dispose() {}

    /** True while the engine fast-forwards (seek): sound/haptic side effects must be suppressed. */
    var fastForward: Boolean

    /** Hero position in viewport pixels at [renderTime] (for journey transitions), or null. */
    fun heroScreenPosition(vp: Viewport, renderTime: Double): FloatArray? = null
    /** Primary hero color at [renderTime] (for journey transitions). */
    fun heroColor(renderTime: Double): Int = -1
    fun debugLines(): List<String> = emptyList()

    /** Sandbox interaction in viewport pixels (applied inside a fixed step, recorded for replay). */
    fun onInput(kind: String, x: Float, y: Float, vp: Viewport) {}
    val bodyCount: Int get() = 1
}
