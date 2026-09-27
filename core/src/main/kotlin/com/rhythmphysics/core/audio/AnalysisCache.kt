package com.rhythmphysics.core.audio

import com.rhythmphysics.core.util.Hashing
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local on-disk cache of [AudioAnalysis]. Key = SHA-256(content fingerprint + analysis algorithm
 * version + settings), so a new analyzer version or different settings automatically miss.
 */
class AnalysisCache(private val dir: File, private val maxEntries: Int = 64) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }

    fun key(fingerprint: String, settings: AnalysisSettings, version: Int = AudioAnalyzer.VERSION): String =
        Hashing.sha256Hex("$fingerprint|v$version|" + json.encodeToString(AnalysisSettings.serializer(), settings))

    fun get(fingerprint: String, settings: AnalysisSettings): AudioAnalysis? {
        val f = File(dir, key(fingerprint, settings) + ".json")
        if (!f.exists()) return null
        return try {
            val a = json.decodeFromString(AudioAnalysis.serializer(), f.readText())
            if (a.version != AudioAnalyzer.VERSION) null else { f.setLastModified(System.currentTimeMillis()); a }
        } catch (e: Exception) {
            f.delete(); null
        }
    }

    fun put(fingerprint: String, settings: AnalysisSettings, analysis: AudioAnalysis) {
        dir.mkdirs()
        val f = File(dir, key(fingerprint, settings) + ".json")
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(json.encodeToString(AudioAnalysis.serializer(), analysis))
        tmp.renameTo(f)
        evict()
    }

    private fun evict() {
        val files = dir.listFiles { x -> x.name.endsWith(".json") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(maxEntries).forEach { it.delete() }
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }
}
