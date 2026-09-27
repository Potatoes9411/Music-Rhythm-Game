package com.rhythmphysics.core.preset

import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.music.EventMappingSettings
import com.rhythmphysics.core.music.EventMode
import kotlinx.serialization.json.Json

class PresetValidationException(msg: String) : Exception(msg)

/** Built-in presets plus JSON import/export with validation. */
object PresetManager {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    val builtIns: List<Preset> by lazy { BuiltInPresets.all().map { validate(it) } }

    fun byId(id: String): Preset? = builtIns.firstOrNull { it.id == id }

    fun forMechanic(type: MechanicType) = builtIns.filter { it.mechanic == type }

    fun defaultFor(type: MechanicType): Preset = forMechanic(type).first()

    fun export(p: Preset): String = json.encodeToString(Preset.serializer(), p)

    /** Parses and validates user JSON. Never executes anything; unknown keys are ignored. */
    fun import(text: String): Preset {
        if (text.length > 256_000) throw PresetValidationException("Preset file is too large")
        val p = try {
            json.decodeFromString(Preset.serializer(), text)
        } catch (e: Exception) {
            throw PresetValidationException("Not a valid preset: ${e.message?.take(160)}")
        }
        if (p.schemaVersion > Preset.SCHEMA_VERSION) throw PresetValidationException("Preset schema ${p.schemaVersion} is newer than supported ${Preset.SCHEMA_VERSION}")
        if (p.schemaVersion < 1) throw PresetValidationException("Invalid schema version")
        return validate(p)
    }

    private fun d(v: Double, lo: Double, hi: Double, def: Double) = if (v.isNaN()) def else v.coerceIn(lo, hi)

    /** Clamps every field into a safe range. */
    fun validate(p: Preset): Preset {
        val id = p.id.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }.take(64).ifEmpty { "custom" }
        val g = p.generation
        val ph = p.physics
        val c = p.camera
        val v = p.visuals
        return p.copy(
            id = id,
            name = p.name.take(80).ifBlank { id },
            description = p.description.take(400),
            generation = g.copy(
                style = g.style.filter { it.isLetterOrDigit() || it == '_' }.take(32).ifEmpty { "classic" },
                speed = d(g.speed, 1.0, 40.0, 9.0),
                surfaceLength = d(g.surfaceLength, 0.8, 8.0, 2.4),
                surfaceThickness = d(g.surfaceThickness, 0.12, 1.2, 0.36),
                lookahead = g.lookahead.coerceIn(8, 256),
                compactness = d(g.compactness, 0.0, 1.0, 0.6),
                drift = d(g.drift, 0.0, 6.0, 0.9),
                futureVisibleSec = d(g.futureVisibleSec, 0.0, 8.0, 2.4),
                surfaceLifetimeSec = d(g.surfaceLifetimeSec, 0.5, 30.0, 5.0),
                minContactGapSec = d(g.minContactGapSec, 0.04, 2.0, 0.09),
                corridorWidth = d(g.corridorWidth, 3.0, 40.0, 9.0),
                apexMin = d(g.apexMin, 0.1, 20.0, 0.9),
                apexMax = d(g.apexMax, 0.3, 30.0, 4.2),
                apexDecay = d(g.apexDecay, 0.2, 1.0, 0.8),
                targetSpacing = d(g.targetSpacing, 0.5, 20.0, 2.6),
                pillarMinHeight = d(g.pillarMinHeight, 0.2, 20.0, 1.2),
                pillarMaxHeight = d(g.pillarMaxHeight, 0.3, 30.0, 4.5),
                stepDrop = d(g.stepDrop, 0.2, 5.0, 1.1),
                mode = if (g.mode in setOf("sandbox", "reactive", "generative")) g.mode else "reactive",
                bounceAngleDeg = d(g.bounceAngleDeg, 20.0, 70.0, 45.0),
            ),
            physics = ph.copy(
                gravity = d(ph.gravity, -200.0, 200.0, 30.0),
                restitution = d(ph.restitution, 0.2, 1.2, 1.0),
                ballCount = ph.ballCount.coerceIn(0, 400),
                ballRadius = d(ph.ballRadius, 0.05, 8.0, 0.6),
                ringRadius = d(ph.ringRadius, 2.0, 20.0, 10.0),
                ringThickness = d(ph.ringThickness, 0.02, 2.0, 0.28),
                gapCount = ph.gapCount.coerceIn(0, 12),
                gapSizeDeg = d(ph.gapSizeDeg, 2.0, 180.0, 38.0),
                gapRotationDegPerSec = d(ph.gapRotationDegPerSec, -720.0, 720.0, 0.0),
                ballGrowthPerHit = d(ph.ballGrowthPerHit, 0.0, 1.0, 0.0),
                ringShrinkPerHit = d(ph.ringShrinkPerHit, 0.0, 1.0, 0.0),
                minRingRadius = d(ph.minRingRadius, 0.5, 20.0, 2.5),
                maxBallRadius = d(ph.maxBallRadius, 0.1, 15.0, 6.0),
                maxBalls = ph.maxBalls.coerceIn(1, 600),
                spawnOnEscape = ph.spawnOnEscape.coerceIn(0, 4),
                initialSpeed = d(ph.initialSpeed, 0.0, 80.0, 13.0),
                maxSpeed = d(ph.maxSpeed, 1.0, 200.0, 60.0),
                substeps = ph.substeps.coerceIn(1, 16),
                attract = d(ph.attract, -200.0, 200.0, 0.0),
                orbit = d(ph.orbit, -200.0, 200.0, 0.0),
            ),
            camera = c.copy(
                zoom = d(c.zoom, 0.4, 3.0, 1.0),
                lookaheadSec = d(c.lookaheadSec, 0.0, 4.0, 0.8),
                pitchDeg = d(c.pitchDeg, 0.0, 80.0, 16.0),
                height = d(c.height, 0.2, 40.0, 2.4),
                distance = d(c.distance, 2.0, 80.0, 10.0),
                fovDeg = d(c.fovDeg, 20.0, 90.0, 46.0),
                shake = d(c.shake, 0.0, 2.0, 0.35),
                follow = d(c.follow, 0.3, 3.0, 1.0),
            ),
            visuals = v.copy(
                palette = v.palette.filter { it.isLetterOrDigit() || it == '_' }.take(32),
                heroSize = d(v.heroSize, 0.4, 2.5, 1.0),
                heroShape = if (v.heroShape in setOf("square", "orb", "ball")) v.heroShape else "square",
                trailLengthSec = d(v.trailLengthSec, 0.0, 3.0, 0.35),
                trailWidth = d(v.trailWidth, 0.05, 3.0, 1.0),
                trailOpacity = d(v.trailOpacity, 0.0, 1.0, 0.45),
                trailTaper = d(v.trailTaper, 0.0, 1.0, 0.9),
                trailColor = v.trailColor?.takeIf { Regex("#?[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?").matches(it) },
                emissive = d(v.emissive, 0.0, 3.0, 1.0),
                bloom = d(v.bloom, 0.0, 1.5, 0.35),
                particles = d(v.particles, 0.0, 3.0, 1.0),
                impactFlash = d(v.impactFlash, 0.0, 2.0, 1.0),
                background = if (v.background in setOf("gradient", "flat", "grid", "carved", "studio")) v.background else "gradient",
                persist = if (v.persist in setOf("none", "rings", "trails")) v.persist else "none",
                carveWidth = d(v.carveWidth, 1.0, 8.0, 1.7),
                targetScale = d(v.targetScale, 0.2, 2.0, 1.0),
                padLook = if (v.padLook in setOf("none", "studio", "neon", "pastel", "stones", "marble")) v.padLook else "none",
                stampCount = v.stampCount.coerceIn(0, 40),
                stampSpacingSec = d(v.stampSpacingSec, 0.03, 1.0, 0.1),
            ),
            eventMapping = p.eventMapping.validated(),
            anomalies = p.anomalies.take(64).map { r ->
                r.copy(
                    every = r.every.coerceIn(1, 10_000),
                    atSec = d(r.atSec, 0.0, 36_000.0, 0.0),
                    repeatSec = d(r.repeatSec, 0.0, 3600.0, 0.0),
                    probability = d(r.probability, 0.0, 1.0, 1.0),
                    params = r.params.entries.take(16).associate { (k, value) ->
                        k.take(24) to (if (value.isNaN()) 0f else value.coerceIn(-1000f, 1000f))
                    },
                    maxCount = r.maxCount.coerceIn(0, 100_000),
                )
            },
        )
    }

    fun mapping(mode: EventMode, density: Float) = EventMappingSettings(mode = mode, density = density)
}
