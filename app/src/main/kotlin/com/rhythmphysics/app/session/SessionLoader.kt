package com.rhythmphysics.app.session

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.rhythmphysics.app.audio.AudioDecoder
import com.rhythmphysics.app.media.MediaKind
import com.rhythmphysics.app.media.MediaSniffer
import com.rhythmphysics.core.audio.AnalysisCache
import com.rhythmphysics.core.audio.AnalysisSettings
import com.rhythmphysics.core.audio.AudioAnalyzer
import com.rhythmphysics.core.audio.WavReader
import com.rhythmphysics.core.audio.WavWriter
import com.rhythmphysics.core.midi.DemoSong
import com.rhythmphysics.core.midi.MidiParser
import com.rhythmphysics.core.session.AudioEventSource
import com.rhythmphysics.core.session.MediaType
import com.rhythmphysics.core.session.MidiEventSource
import com.rhythmphysics.core.session.RhythmSession
import com.rhythmphysics.core.synth.MidiRenderer
import com.rhythmphysics.core.synth.Sf2Synth
import com.rhythmphysics.core.synth.SoundFont
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** A session ready to play: the shared [RhythmSession] plus the PCM cache the audio engine plays. */
class LoadedSession(val session: RhythmSession, val pcm: File?, val kind: MediaKind, val uri: String)

/** Loads the SoundFont lazily (bundled GeneralUser GS or a user-imported .sf2). */
class SoundFontProvider(private val context: Context) {
    @Volatile private var cached: Pair<String, SoundFont>? = null
    val userFile: File get() = File(context.filesDir, "soundfonts/user.sf2")
    var useUser: Boolean = false

    @Synchronized
    fun get(): Pair<String, SoundFont> {
        val key = if (useUser && userFile.exists()) "user:${userFile.length()}:${userFile.lastModified()}" else "bundled:GeneralUser-GS-1.471"
        cached?.let { if (it.first == key) return it }
        val bytes = if (key.startsWith("user")) userFile.readBytes() else context.assets.open("soundfonts/GeneralUser-GS.sf2").use { it.readBytes() }
        val sf = SoundFont.parse(bytes)
        cached = key to sf
        return key to sf
    }

    /** Drops the parsed SoundFont (memory pressure); it is re-read on next use. */
    fun release() { cached = null }

    fun importUser(input: InputStream) {
        userFile.parentFile?.mkdirs()
        val tmp = File(userFile.path + ".tmp")
        input.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        SoundFont.parse(tmp.readBytes()) // validate before accepting
        tmp.renameTo(userFile)
        cached = null
    }
}

/**
 * Import pipeline: SAF uri -> sniff (bytes, not names) -> fingerprint -> MIDI render or audio
 * decode + analysis -> caches. Runs on a background thread (callers use coroutines).
 */
class SessionLoader(private val context: Context, private val soundFonts: SoundFontProvider) {
    private val pcmDir = File(context.cacheDir, "pcm").also { it.mkdirs() }
    val analysisCache = AnalysisCache(File(context.filesDir, "analysis"))
    private val analysisSettings = AnalysisSettings()

    fun displayName(uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: uri.lastPathSegment ?: "Song"
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment ?: "Song"
    }

    /** Keeps read access across restarts when the provider allows it (recent files). */
    fun persistPermission(uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true
    } catch (_: SecurityException) { false }

    private fun fingerprint(uri: Uri): Pair<String, ByteArray> {
        val md = MessageDigest.getInstance("SHA-256")
        val head = ByteArray(64)
        var headLen = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (headLen < head.size) { val k = minOf(n, head.size - headLen); System.arraycopy(buf, 0, head, headLen, k); headLen += k }
                md.update(buf, 0, n)
            }
        } ?: throw IOException("Cannot open the file")
        return md.digest().joinToString("") { "%02x".format(it) } to head.copyOf(headLen)
    }

    fun load(uri: Uri, progress: (String, Double) -> Unit, cancelled: () -> Boolean): LoadedSession {
        progress("Reading file", 0.0)
        val (fp, head) = fingerprint(uri)
        val name = displayName(uri)
        val kind = MediaSniffer.sniff(head, context.contentResolver.getType(uri), name)
        return when {
            kind == MediaKind.MIDI -> {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                loadMidi(bytes, fp, name.substringBeforeLast('.'), uri.toString(), progress, cancelled)
            }
            kind == MediaKind.UNKNOWN && ((context.contentResolver.getType(uri) ?: "").contains("midi") || name.lowercase().let { it.endsWith(".mid") || it.endsWith(".midi") }) ->
                throw IOException("This file is labelled as MIDI but has no MIDI header (MThd); it may be corrupt or not a Standard MIDI File.")
            kind == MediaKind.UNKNOWN && !(context.contentResolver.getType(uri) ?: "").startsWith("audio") ->
                throw IOException("Unrecognized file. Supported: MIDI (.mid/.midi) and audio (WAV, MP3, OGG, M4A/AAC, FLAC).")
            else -> loadAudio(uri, fp, name.substringBeforeLast('.'), kind, progress, cancelled)
        }
    }

    fun loadDemo(progress: (String, Double) -> Unit, cancelled: () -> Boolean): LoadedSession {
        val bytes = DemoSong.bytes()
        val fp = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return loadMidi(bytes, fp, DemoSong.TITLE, "asset://demo.mid", progress, cancelled)
    }

    private fun loadMidi(bytes: ByteArray, fp: String, title: String, uri: String, progress: (String, Double) -> Unit, cancelled: () -> Boolean): LoadedSession {
        val midi = MidiParser.parse(bytes)
        progress("Loading SoundFont", 0.05)
        val (sfKey, sf) = soundFonts.get()
        val sfTag = MessageDigest.getInstance("SHA-1").digest(sfKey.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        val wav = File(pcmDir, "midi-$fp-$sfTag.wav")
        if (!validWav(wav)) {
            val tmp = File(wav.path + ".part")
            val synth = Sf2Synth(sf, 44100)
            WavWriter(tmp, 44100, 2).use { w ->
                MidiRenderer.render(midi, synth, progress = { p -> progress("Rendering MIDI with SoundFont", 0.1 + 0.9 * p) }) { l, r, n ->
                    if (cancelled()) throw IOException("Cancelled")
                    w.write(l, r, n)
                }
            }
            tmp.renameTo(wav)
        }
        touch(wav)
        val session = RhythmSession(fp.take(16), uri, fp, MediaType.MIDI, title, midi.durationSec, MidiEventSource(midi), midi = midi)
        return LoadedSession(session, wav, MediaKind.MIDI, uri)
    }

    private fun loadAudio(uri: Uri, fp: String, title: String, kind: MediaKind, progress: (String, Double) -> Unit, cancelled: () -> Boolean): LoadedSession {
        val wav = File(pcmDir, "audio-v1-$fp.wav")
        var analysis = analysisCache.get(fp, analysisSettings)
        if (!validWav(wav) || analysis == null) {
            val r = AudioDecoder(context).decode(uri, wav, analysis == null, { p -> progress("Decoding and analyzing audio", p) }, cancelled)
            if (analysis == null) {
                analysis = r.analysis ?: throw IOException("Analysis failed")
                analysisCache.put(fp, analysisSettings, analysis)
            }
        } else progress("Using cached analysis", 1.0)
        touch(wav)
        val a = analysis!!
        val session = RhythmSession(fp.take(16), uri.toString(), fp, MediaType.AUDIO, title, WavReader.readInfo(wav).durationSec, AudioEventSource(a), analysis = a)
        return LoadedSession(session, wav, kind, uri.toString())
    }

    private fun validWav(f: File) = f.exists() && try { WavReader.readInfo(f).frames > 0 } catch (_: Exception) { false }

    /** LRU: keep the PCM cache under ~1.2 GB. */
    private fun touch(f: File) {
        f.setLastModified(System.currentTimeMillis())
        val files = pcmDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (x in files) { total += x.length(); if (total > 1_200L * 1024 * 1024 && x != f) x.delete() }
    }

    @Suppress("unused") private val analyzerVersion = AudioAnalyzer.VERSION
}
