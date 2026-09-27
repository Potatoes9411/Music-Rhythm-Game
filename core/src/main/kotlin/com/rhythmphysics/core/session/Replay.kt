package com.rhythmphysics.core.session

import com.rhythmphysics.core.audio.AudioAnalyzer
import com.rhythmphysics.core.engine.RhythmEngine
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.preset.Preset
import com.rhythmphysics.core.preset.PresetManager
import com.rhythmphysics.core.preset.PresetValidationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A user input in sandbox mode (e.g. tap to spawn a ball), recorded with its simulation step. */
@Serializable
data class SandboxInput(val step: Long, val kind: String, val x: Float, val y: Float)

/**
 * Everything needed to regenerate a run exactly: same recipe + same media => same simulation.
 */
@Serializable
data class ReplayRecipe(
    val schemaVersion: Int = SCHEMA_VERSION,
    val appVersion: String,
    val sourceFingerprint: String,
    val mediaType: MediaType,
    val mediaTitle: String,
    val analysisVersion: Int,
    val generatorVersions: Map<String, Int>,
    val scene: SceneConfig,
    /** Full preset bodies used (so custom presets travel with the replay). */
    val presets: List<Preset>,
    val sandboxInputs: List<SandboxInput> = emptyList(),
    /** Optional verification: engine state hash at [verifyAtSec]. */
    val verifyAtSec: Double? = null,
    val verifyHash: String? = null,
) {
    companion object { const val SCHEMA_VERSION = 1 }
}

object ReplayManager {
    /** Bumped whenever a planner/solver change would alter generated courses. */
    val GENERATOR_VERSIONS = mapOf("square" to 1, "circle" to 1, "arch" to 1, "platform" to 1, "journey" to 1, "events" to 1)
    const val APP_VERSION = "0.1.0"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun create(engine: RhythmEngine, extraPresets: List<Preset> = emptyList(), inputs: List<SandboxInput> = emptyList(), verifyAt: Double? = null): ReplayRecipe {
        val used = LinkedHashMap<String, Preset>()
        engine.director.slots.forEach { used[it.preset.id] = it.preset }
        extraPresets.forEach { used[it.id] = it }
        val hash = verifyAt?.let { t ->
            val probe = RhythmEngine(engine.session, engine.config) { id -> used[id] ?: PresetManager.byId(id) }
            probe.seek(t); probe.stateHash().also { probe.dispose() }
        }
        return ReplayRecipe(
            appVersion = APP_VERSION,
            sourceFingerprint = engine.session.mediaFingerprint,
            mediaType = engine.session.mediaType,
            mediaTitle = engine.session.title,
            analysisVersion = AudioAnalyzer.VERSION,
            generatorVersions = GENERATOR_VERSIONS,
            scene = engine.config.copy(presetIds = engine.director.slots.associate { it.type to it.preset.id } + engine.config.presetIds),
            presets = used.values.toList(),
            sandboxInputs = inputs,
            verifyAtSec = verifyAt,
            verifyHash = hash,
        )
    }

    fun export(r: ReplayRecipe): String = json.encodeToString(ReplayRecipe.serializer(), r)

    fun import(text: String): ReplayRecipe {
        if (text.length > 4_000_000) throw PresetValidationException("Replay file is too large")
        val r = try { json.decodeFromString(ReplayRecipe.serializer(), text) } catch (e: Exception) {
            throw PresetValidationException("Not a valid replay: ${e.message?.take(160)}")
        }
        if (r.schemaVersion > ReplayRecipe.SCHEMA_VERSION) throw PresetValidationException("Replay schema ${r.schemaVersion} is newer than supported")
        return r.copy(presets = r.presets.map { PresetManager.validate(it) })
    }

    /** Problems that would prevent an exact reproduction (empty = compatible). */
    fun compatibility(r: ReplayRecipe, session: RhythmSession): List<String> {
        val issues = ArrayList<String>()
        if (r.sourceFingerprint != session.mediaFingerprint) issues += "Different media file (fingerprint mismatch)"
        if (r.mediaType == MediaType.AUDIO && r.analysisVersion != AudioAnalyzer.VERSION) issues += "Audio analysis version changed (${r.analysisVersion} -> ${AudioAnalyzer.VERSION})"
        for ((k, v) in r.generatorVersions) if (GENERATOR_VERSIONS[k] != v) issues += "Generator '$k' changed ($v -> ${GENERATOR_VERSIONS[k]})"
        return issues
    }

    /** Builds an engine that reproduces the recipe (presets from the recipe take precedence). */
    fun engineFor(r: ReplayRecipe, session: RhythmSession, sink: com.rhythmphysics.core.mechanic.EngineSink = com.rhythmphysics.core.mechanic.EngineSink.None): RhythmEngine {
        val byId = r.presets.associateBy { it.id }
        return RhythmEngine(session, r.scene, sink) { id -> byId[id] ?: PresetManager.byId(id) }
    }

    @Suppress("unused")
    private val mechanicTypes = MechanicType.values()
}
