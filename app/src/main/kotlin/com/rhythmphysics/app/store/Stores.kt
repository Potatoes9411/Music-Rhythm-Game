package com.rhythmphysics.app.store

import android.content.Context
import com.rhythmphysics.app.platform.HapticLevel
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.preset.Preset
import com.rhythmphysics.core.preset.PresetManager
import com.rhythmphysics.core.render.Quality
import com.rhythmphysics.core.render.RenderSettings
import com.rhythmphysics.core.session.SceneConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

private val storeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** App-wide preferences (render quality, accessibility, haptics, defaults for new sessions). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var renderSettings: RenderSettings
        get() = prefs.getString("render", null)?.let { runCatching { storeJson.decodeFromString(RenderSettings.serializer(), it) }.getOrNull() }
            ?: RenderSettings(quality = Quality.HIGH)
        set(v) { prefs.edit().putString("render", storeJson.encodeToString(RenderSettings.serializer(), v.copy(cleanOutput = false))).apply() }

    var haptics: HapticLevel
        get() = runCatching { HapticLevel.valueOf(prefs.getString("haptics", "OFF")!!) }.getOrDefault(HapticLevel.OFF)
        set(v) { prefs.edit().putString("haptics", v.name).apply() }

    var useUserSoundFont: Boolean
        get() = prefs.getBoolean("userSf", false)
        set(v) { prefs.edit().putBoolean("userSf", v).apply() }

    /** Last scene (seed, aspect, mechanic, presets, mapping) — new sessions start from it. */
    var lastScene: SceneConfig
        get() = prefs.getString("scene", null)?.let { runCatching { storeJson.decodeFromString(SceneConfig.serializer(), it) }.getOrNull() } ?: SceneConfig()
        set(v) { prefs.edit().putString("scene", storeJson.encodeToString(SceneConfig.serializer(), v.copy(journey = null))).apply() }

    var panelCollapsed: Boolean
        get() = prefs.getBoolean("panelCollapsed", false)
        set(v) { prefs.edit().putBoolean("panelCollapsed", v).apply() }
}

@Serializable
data class RecentItem(val uri: String, val title: String, val kind: String, val openedAt: Long)

/** Recently opened files (content URIs with persisted read permission). */
class RecentStore(context: Context) {
    private val prefs = context.getSharedPreferences("recent", Context.MODE_PRIVATE)
    private val ser = ListSerializer(RecentItem.serializer())

    fun list(): List<RecentItem> = prefs.getString("items", null)?.let { runCatching { storeJson.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()

    fun add(item: RecentItem) = save(listOf(item) + list().filter { it.uri != item.uri })

    fun remove(uri: String) = save(list().filter { it.uri != uri })

    private fun save(items: List<RecentItem>) { prefs.edit().putString("items", storeJson.encodeToString(ser, items.take(12))).apply() }
}

/**
 * User presets: validated, schema-versioned JSON files in app storage. Imported presets whose id
 * collides with a built-in are re-namespaced so built-ins can never be shadowed.
 */
class CustomPresetStore(context: Context) {
    private val dir = File(context.filesDir, "presets").also { it.mkdirs() }
    @Volatile private var cache: List<Preset>? = null

    fun all(): List<Preset> = cache ?: (dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }?.mapNotNull { f ->
        runCatching { PresetManager.import(f.readText()) }.getOrNull()
    } ?: emptyList()).also { cache = it }

    fun forMechanic(type: MechanicType) = all().filter { it.mechanic == type }

    fun byId(id: String): Preset? = all().firstOrNull { it.id == id }

    /** Parses, validates and stores [json]; returns the stored preset. Throws with a readable message on bad input. */
    fun import(json: String): Preset {
        var p = PresetManager.import(json)
        if (PresetManager.byId(p.id) != null) p = p.copy(id = "custom." + p.id, name = p.name + " (custom)")
        p = PresetManager.validate(p)
        File(dir, safeName(p.id) + ".json").writeText(PresetManager.export(p))
        cache = null
        return p
    }

    fun delete(id: String) { File(dir, safeName(id) + ".json").delete(); cache = null }

    private fun safeName(id: String) = id.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }.take(64)

    /** Resolver used by engines: built-ins first, then user presets. */
    fun resolver(): (String) -> Preset? = { id -> PresetManager.byId(id) ?: byId(id) }
}
