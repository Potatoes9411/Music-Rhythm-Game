package com.rhythmphysics.core.engine

import com.rhythmphysics.core.diag.FrameStats
import com.rhythmphysics.core.diag.SyncLogger
import com.rhythmphysics.core.mechanic.EngineSink
import com.rhythmphysics.core.preset.Preset
import com.rhythmphysics.core.preset.PresetManager
import com.rhythmphysics.core.render.Colors
import com.rhythmphysics.core.render.DrawList
import com.rhythmphysics.core.render.RenderSettings
import com.rhythmphysics.core.render.Viewport
import com.rhythmphysics.core.session.RhythmSession
import com.rhythmphysics.core.session.SceneConfig
import com.rhythmphysics.core.util.Hashing
import java.util.TreeMap
import kotlin.math.floor

/**
 * The shared engine: AUDIO CLOCK -> MASTER TIMELINE -> FIXED SIMULATION (120 Hz) -> INTERPOLATED RENDER.
 *
 * Musical time always comes from the caller's playback clock ([update]); it is never accumulated
 * from frame deltas. Simulation step n always represents song time n / fixedHz exactly.
 */
class RhythmEngine(
    val session: RhythmSession,
    val config: SceneConfig,
    val sink: EngineSink = EngineSink.None,
    val fixedHz: Int = FIXED_HZ,
    private val presetResolver: (String) -> Preset? = { PresetManager.byId(it) },
) {
    val dt = 1.0 / fixedHz
    val sync = SyncLogger()
    val frame = Viewport(0f, 0f, config.aspect.width.toFloat(), config.aspect.height.toFloat())
    val director = MechanicDirector(session, config, frame, sink, sync, presetResolver)
    val frameStats = FrameStats()

    var stepIndex = 0L; private set
    val simTime: Double get() = stepIndex * dt
    /** Time of the last update/seek; what [render] shows. */
    var renderTime = 0.0; private set
    var alpha = 0f; private set
    var lastSteps = 0; private set

    private val checkpoints = TreeMap<Long, ByteArray>()
    /** Recorded sandbox inputs (step-stamped). Replayed identically on seek and in replays. */
    val inputLog = ArrayList<com.rhythmphysics.core.session.SandboxInput>()
    private var inputCursor = 0
    private var checkpointBytes = 0L
    var checkpointIntervalSteps = fixedHz.toLong()
    var maxCheckpointBytes = 48L * 1024 * 1024
    /** Extra lines supplied by the platform (fps, audio state...). */
    var debugProvider: (() -> List<String>)? = null

    init {
        director.prepare(0.0)
        checkpoints[0] = director.checkpoint()
    }

    /**
     * Advances the simulation to the audio clock. Returns the number of fixed steps taken.
     * Large jumps (seek, lifecycle stalls) are handled as deterministic seeks instead of a burst.
     */
    fun update(audioTime: Double): Int {
        if (audioTime < simTime - 0.06 || audioTime - simTime > 1.5) {
            seek(audioTime)
            lastSteps = 0
            return 0
        }
        var steps = 0
        while ((stepIndex + 1) * dt <= audioTime + 1e-9) {
            step()
            steps++
        }
        renderTime = maxOf(audioTime, simTime)
        alpha = ((renderTime - simTime) / dt).toFloat().coerceIn(0f, 1f)
        lastSteps = steps
        frameStats.lastStepsPerFrame = steps
        return steps
    }

    /** Queues a user input for the next fixed step (viewport pixels of the creator frame). */
    fun input(kind: String, x: Float, y: Float) {
        val step = stepIndex + 1
        // Inputs after the playhead are superseded by new interaction (like re-recording).
        inputLog.removeAll { it.step >= step }
        inputLog += com.rhythmphysics.core.session.SandboxInput(step, kind, x, y)
        inputCursor = inputLog.size - 1
    }

    fun loadInputs(inputs: List<com.rhythmphysics.core.session.SandboxInput>) {
        inputLog.clear(); inputLog.addAll(inputs.sortedBy { it.step }); inputCursor = 0
    }

    private fun applyInputs() {
        if (inputLog.isEmpty()) return
        // find inputs for this step
        var lo = 0; var hi = inputLog.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (inputLog[m].step < stepIndex) lo = m + 1 else hi = m }
        var i = lo
        while (i < inputLog.size && inputLog[i].step == stepIndex) {
            val inp = inputLog[i]
            for (slot in director.slots) {
                val vp = slot.viewport(frame)
                if (inp.x >= vp.x && inp.x <= vp.x + vp.w && inp.y >= vp.y && inp.y <= vp.y + vp.h) slot.mechanic?.onInput(inp.kind, inp.x, inp.y, vp)
            }
            i++
        }
    }

    private fun step() {
        stepIndex++
        applyInputs()
        director.fixedUpdate(dt, simTime)
        if (stepIndex % checkpointIntervalSteps == 0L && !checkpoints.containsKey(stepIndex)) {
            val cp = director.checkpoint()
            checkpoints[stepIndex] = cp
            checkpointBytes += cp.size
            if (checkpointBytes > maxCheckpointBytes) thinCheckpoints()
        }
    }

    private fun thinCheckpoints() {
        // Keep every other checkpoint (never the origin).
        var keep = true
        val it = checkpoints.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key == 0L) continue
            if (!keep) { checkpointBytes -= e.value.size; it.remove() }
            keep = !keep
        }
        checkpointIntervalSteps *= 2
    }

    /** pause -> nearest previous checkpoint -> restore -> deterministic fast-forward -> target. */
    fun seek(timeSec: Double) {
        val t = timeSec.coerceAtLeast(0.0)
        val target = floor(t / dt + 1e-9).toLong()
        val entry = checkpoints.floorEntry(target)!!
        director.restore(entry.value)
        stepIndex = entry.key
        director.fastForward = true
        sync.enabled = false
        while (stepIndex < target) step()
        sync.enabled = true
        director.fastForward = false
        renderTime = t
        alpha = ((t - simTime) / dt).toFloat().coerceIn(0f, 1f)
    }

    fun restart() = seek(0.0)

    fun render(dl: DrawList, rs: RenderSettings, renderAt: Double = renderTime) {
        dl.reset(frame.w, frame.h)
        dl.clear(Colors.BLACK)
        director.render(dl, renderAt, alpha, rs)
        if (rs.showDebug && !rs.cleanOutput) DebugOverlay.draw(dl, this, rs)
        frameStats.drawCommands = dl.commandCount
    }

    /** Deterministic fingerprint of the complete simulation state (tests, replay verification). */
    fun stateHash(): String {
        val bytes = director.checkpoint()
        return Hashing.sha256Hex(bytes + stepIndex.toString().toByteArray())
    }

    fun dispose() {
        director.dispose()
        checkpoints.clear()
    }

    companion object {
        const val FIXED_HZ = 120
    }
}
