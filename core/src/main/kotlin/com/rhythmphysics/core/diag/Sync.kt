package com.rhythmphysics.core.diag

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** One measured contact: when the music says it should happen vs. when the simulated hero touched. */
@Serializable
data class SyncSample(
    val eventId: Long,
    val expectedTimeSec: Double,
    val logicalContactTimeSec: Double,
    val errorMs: Double,
    val mechanic: String = "",
)

@Serializable
data class SyncStats(
    val count: Int,
    val meanAbsMs: Double,
    val p95AbsMs: Double,
    val maxAbsMs: Double,
    val outliersOver30ms: Int,
    val degraded: Int,
) {
    val passesPlannedGoal: Boolean get() = count == 0 || (meanAbsMs <= 5.0 && p95AbsMs <= 15.0 && maxAbsMs <= 30.0)
}

/**
 * Logical (simulation) sync. Audio-timeline drift and render-presentation timing are measured
 * separately (see [FrameStats] and the app's clock monitor): a 60 Hz display can only present a
 * contact to within ~16.7 ms no matter how exact the simulation is.
 */
class SyncLogger(private val capacity: Int = 20_000) {
    private val samples = ArrayList<SyncSample>()
    var degradedEvents = 0
        private set
    var enabled = true

    fun log(eventId: Long, expected: Double, actual: Double, mechanic: String) {
        if (!enabled) return
        if (samples.size >= capacity) samples.removeAt(0)
        samples += SyncSample(eventId, expected, actual, (actual - expected) * 1000.0, mechanic)
    }

    /** A musical event that could not become a physical contact (e.g. planner fallback). */
    fun degraded() { if (enabled) degradedEvents++ }

    fun clear() { samples.clear(); degradedEvents = 0 }
    fun samples(): List<SyncSample> = samples.toList()
    val last: SyncSample? get() = samples.lastOrNull()

    fun stats(): SyncStats {
        if (samples.isEmpty()) return SyncStats(0, 0.0, 0.0, 0.0, 0, degradedEvents)
        val errs = samples.map { abs(it.errorMs) }.sorted()
        return SyncStats(
            count = errs.size,
            meanAbsMs = errs.average(),
            p95AbsMs = errs[((errs.size - 1) * 0.95).toInt()],
            maxAbsMs = errs.last(),
            outliersOver30ms = errs.count { it > 30.0 },
            degraded = degradedEvents,
        )
    }
}

/** Rolling frame statistics for the debug overlay and perf gauntlet. */
class FrameStats(private val window: Int = 120) {
    private val frameMs = DoubleArray(window)
    private var n = 0
    private var idx = 0
    var lastStepsPerFrame = 0
    var drawCommands = 0

    fun addFrame(ms: Double) { frameMs[idx] = ms; idx = (idx + 1) % window; if (n < window) n++ }
    val averageMs: Double get() = if (n == 0) 0.0 else (0 until n).sumOf { frameMs[it] } / n
    val fps: Double get() = if (averageMs <= 0) 0.0 else 1000.0 / averageMs
    val worstMs: Double get() = if (n == 0) 0.0 else (0 until n).maxOf { frameMs[it] }
}
