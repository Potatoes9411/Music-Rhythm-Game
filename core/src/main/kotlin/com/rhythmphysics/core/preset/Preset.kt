package com.rhythmphysics.core.preset

import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.music.EventMappingSettings
import kotlinx.serialization.Serializable

/**
 * Presets *configure* one of the four mechanic engines; they never replace an engine.
 * All sections have defaults so partial JSON imports are valid; [PresetManager.validate] clamps
 * every numeric field into a safe range (user JSON is data, never code).
 */
@Serializable
data class Preset(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val name: String,
    val mechanic: MechanicType,
    /** Optional seed override; null = use the session seed. */
    val seed: Long? = null,
    val description: String = "",
    val generation: GenerationParams = GenerationParams(),
    val physics: PhysicsParams = PhysicsParams(),
    val camera: CameraParams = CameraParams(),
    val visuals: VisualParams = VisualParams(),
    val eventMapping: EventMappingSettings = EventMappingSettings(),
    val anomalies: List<AnomalyRule> = emptyList(),
) {
    companion object { const val SCHEMA_VERSION = 1 }
}

@Serializable
data class GenerationParams(
    /** Layout/style key interpreted by the mechanic (e.g. "bounce_curve", "pillar_weave", "staircase"). */
    val style: String = "classic",
    /** Travel speed in world units per second. */
    val speed: Double = 9.0,
    val surfaceLength: Double = 2.4,
    val surfaceThickness: Double = 0.36,
    /** Rolling planning buffer (impacts planned ahead of the playhead). */
    val lookahead: Int = 64,
    /** How strongly the route is pulled back toward the framed region (0..1). */
    val compactness: Double = 0.6,
    /** Scroll speed of the framed region, world units per second. */
    val drift: Double = 0.9,
    val showFutureSurfaces: Boolean = true,
    val futureVisibleSec: Double = 3.0,
    val surfaceLifetimeSec: Double = 2.8,
    /** Mechanic-specific minimum time between physical contacts. */
    val minContactGapSec: Double = 0.09,
    val corridorWidth: Double = 9.0,
    val apexMin: Double = 0.9,
    val apexMax: Double = 4.2,
    val apexDecay: Double = 0.8,
    val targetSpacing: Double = 2.6,
    val pillarMinHeight: Double = 1.2,
    val pillarMaxHeight: Double = 4.5,
    val stepDrop: Double = 1.1,
    /** Circle: "sandbox" | "reactive" | "generative". */
    val mode: String = "reactive",
    val bounceAngleDeg: Double = 45.0,
)

@Serializable
data class PhysicsParams(
    val gravity: Double = 30.0,
    val restitution: Double = 1.0,
    val ballCount: Int = 1,
    val ballRadius: Double = 0.6,
    val ringRadius: Double = 10.0,
    val ringThickness: Double = 0.28,
    val gapCount: Int = 0,
    val gapSizeDeg: Double = 38.0,
    val gapRotationDegPerSec: Double = 0.0,
    val ballGrowthPerHit: Double = 0.0,
    val ringShrinkPerHit: Double = 0.0,
    val minRingRadius: Double = 2.5,
    val maxBallRadius: Double = 6.0,
    val maxBalls: Int = 96,
    val spawnOnEscape: Int = 0,
    val initialSpeed: Double = 13.0,
    val maxSpeed: Double = 60.0,
    val substeps: Int = 4,
    val ballCollisions: Boolean = true,
    val attract: Double = 0.0,
    val orbit: Double = 0.0,
)

@Serializable
data class CameraParams(
    val zoom: Double = 1.0,
    val lookaheadSec: Double = 0.8,
    val pitchDeg: Double = 16.0,
    val height: Double = 2.4,
    val distance: Double = 10.0,
    val fovDeg: Double = 46.0,
    val shake: Double = 0.35,
    val follow: Double = 1.0,
)

@Serializable
data class VisualParams(
    val palette: String = "classic",
    val heroSize: Double = 1.0,
    val heroShape: String = "square",
    val trailLengthSec: Double = 0.35,
    val trailWidth: Double = 1.0,
    val trailOpacity: Double = 0.45,
    val trailTaper: Double = 0.9,
    val trailColor: String? = null,
    val emissive: Double = 1.0,
    val bloom: Double = 0.35,
    val particles: Double = 1.0,
    val squashStretch: Boolean = true,
    val impactFlash: Double = 1.0,
    val colorShiftOnImpact: Boolean = true,
    val background: String = "gradient",
    val reflections: Boolean = true,
    val noteLabels: Boolean = false,
    val outlineOnly: Boolean = false,
    val ghostTrail: Boolean = false,
    /** Hit surfaces keep a tint of the note color ("course memory"). */
    val surfaceMemoryTint: Boolean = true,
    /** Shows counters (balls, hits, escapes) under the ring — common in circle-chaos videos. */
    val showStats: Boolean = false,
)

@Serializable
enum class AnomalyType {
    BALL_GROW, BALL_SHRINK, RING_GROW, RING_SHRINK, SPAWN_BALL, REMOVE_BALL, SPLIT_BALL,
    SPEED_UP, SLOW_DOWN, GRAVITY_ENABLE, GRAVITY_DISABLE, GRAVITY_FLIP, GRAVITY_ROTATE,
    GAP_OPEN, GAP_CLOSE, GAP_ROTATE, MULTIPLE_GAPS, RESTITUTION_CHANGE, COLOR_SHIFT, TRAIL_SHIFT,
    ATTRACT, REPEL, ORBIT, RING_BREAK, CHAOS_BURST, DUPLICATION_CASCADE,
}

/** When an anomaly fires. */
@Serializable
enum class AnomalyTrigger { TIME, BEAT, DOWNBEAT, MAJOR_EVENT, COLLISION, ESCAPE }

@Serializable
data class AnomalyRule(
    val type: AnomalyType,
    val trigger: AnomalyTrigger,
    /** Fire on every Nth trigger occurrence. */
    val every: Int = 1,
    /** For TIME triggers: absolute time, or period if [repeatSec] > 0. */
    val atSec: Double = 0.0,
    val repeatSec: Double = 0.0,
    val probability: Double = 1.0,
    val params: Map<String, Float> = emptyMap(),
    /** Stop firing after this many occurrences (0 = unlimited). */
    val maxCount: Int = 0,
)
