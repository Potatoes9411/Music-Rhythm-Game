package com.rhythmphysics.app.ui

import android.app.AlertDialog
import android.provider.DocumentsContract
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.rhythmphysics.app.MainActivity
import com.rhythmphysics.app.export.ExportCancelled
import com.rhythmphysics.app.export.ExportSettings
import com.rhythmphysics.app.export.VideoExporter
import com.rhythmphysics.app.platform.HapticLevel
import com.rhythmphysics.core.Branding
import com.rhythmphysics.core.engine.JourneyPlanner
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.music.EventMappingSettings
import com.rhythmphysics.core.music.EventMode
import com.rhythmphysics.core.music.PolyphonyMapping
import com.rhythmphysics.core.preset.PresetManager
import com.rhythmphysics.core.render.Quality
import com.rhythmphysics.core.session.AudioMode
import com.rhythmphysics.core.session.JourneySchedule
import com.rhythmphysics.core.session.MediaType
import com.rhythmphysics.core.session.ReplayManager
import com.rhythmphysics.core.session.ViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.roundToInt

/** Settings, export, journey editor and MIDI track dialogs for [CreatorScreen]. */
object CreatorDialogs {
    private val reportJson = Json { prettyPrint = true; encodeDefaults = true }
    /** Device H.264 encoder capability query (replaceable in device-less tests). */
    @Volatile var encoderSupports: (Int, Int, Int) -> Boolean = { w, h, fps -> VideoExporter.isSupported(w, h, fps) }

    fun audioLabel(m: AudioMode) = when (m) {
        AudioMode.ORIGINAL_AND_VISUALS -> "Song"
        AudioMode.ORIGINAL_AND_COLLISION_LAYER -> "Song + collision sounds"
        AudioMode.ANALYSIS_ONLY -> "Muted (visuals only)"
    }

    private fun sheet(act: MainActivity): LinearLayout = Ui.column(act).apply {
        setPadding(Ui.dp(act, 22), Ui.dp(act, 4), Ui.dp(act, 22), Ui.dp(act, 12))
    }

    private fun show(act: MainActivity, title: String, content: View, positive: String = "Done", onPositive: (() -> Unit)? = null, neutral: Pair<String, () -> Unit>? = null): AlertDialog {
        val sv = ScrollView(act).apply { addView(content) }
        val b = Ui.dialog(act, title).setView(sv).setPositiveButton(positive) { _, _ -> onPositive?.invoke() }
        if (onPositive != null) b.setNegativeButton("Cancel", null)
        neutral?.let { (label, fn) -> b.setNeutralButton(label) { _, _ -> fn() } }
        return b.show()
    }

    // ---- settings -----------------------------------------------------------------------------
    fun settings(act: MainActivity, cs: CreatorScreen) {
        val c = act
        val app = act.app
        val col = sheet(c)
        var rs = cs.userRender

        col.addView(Ui.label(c, "Visual quality"))
        col.addView(ChipRow(c, Quality.values().map { it.label to it }, rs.quality) { q -> rs = rs.copy(quality = q); cs.setRenderSettings(rs) }.view)
        col.addView(Ui.text(c, "Quality only changes visuals (particles, trails, glow). Timing and physics are identical at every tier.", 12f, Ui.MUTED))

        col.addView(Ui.label(c, "Accessibility"))
        col.addView(Ui.switch(c, "Reduced flash", rs.reducedFlash) { rs = rs.copy(reducedFlash = it); cs.setRenderSettings(rs) })
        col.addView(Ui.switch(c, "Reduced motion", rs.reducedMotion) { rs = rs.copy(reducedMotion = it); cs.setRenderSettings(rs) })
        col.addView(Ui.switch(c, "Disable camera shake", !rs.cameraShake) { rs = rs.copy(cameraShake = !it); cs.setRenderSettings(rs) })
        col.addView(Ui.switch(c, "Reduced bloom", rs.reducedBloom) { rs = rs.copy(reducedBloom = it); cs.setRenderSettings(rs) })
        col.addView(Ui.switch(c, "Reduced particles", rs.reducedParticles) { rs = rs.copy(reducedParticles = it); cs.setRenderSettings(rs) })

        col.addView(Ui.label(c, "Timing"))
        val offsetLabel = Ui.text(c, "", 13f)
        fun offsetText(v: Int) = "Visual offset: ${if (v > 0) "+" else ""}$v ms" + if (v == 0) " (auto: audio timestamps + display latency)" else ""
        offsetLabel.text = offsetText(app.settings.avOffsetMs)
        col.addView(offsetLabel)
        col.addView(Ui.slider(c, 300, app.settings.avOffsetMs + 150) { p, done ->
            val v = (p - 150) / 5 * 5
            offsetLabel.text = offsetText(v)
            if (done) cs.setAvOffset(v)
        })
        col.addView(Ui.text(c, "Sync is automatic. Only adjust if impacts look early (+) or late (−) on your output, e.g. some Bluetooth headphones.", 12f, Ui.MUTED))

        col.addView(Ui.label(c, "Haptics"))
        if (app.haptics.available) {
            col.addView(ChipRow(c, HapticLevel.values().map { it.label to it }, app.haptics.level) { lv -> app.haptics.level = lv; app.settings.haptics = lv }.view)
            col.addView(Ui.text(c, "Strong impacts only, rate-limited.", 12f, Ui.MUTED))
        } else col.addView(Ui.text(c, "This device has no vibrator.", 13f, Ui.MUTED))

        if (!cs.isSandbox) {
            col.addView(Ui.label(c, "Audio"))
            col.addView(ChipRow(c, AudioMode.values().map { audioLabel(it) to it }, cs.scene.audioMode) { m -> cs.updateScene(cs.scene.copy(audioMode = m)) }.view)

            col.addView(Ui.label(c, "Music events"))
            val presetMapping = { cs.resolver(cs.presetIdFor(cs.scene.focus))?.eventMapping ?: EventMappingSettings() }
            val densityLabel = Ui.text(c, "", 13f)
            val density = Ui.slider(c, 100, ((cs.scene.eventMapping ?: presetMapping()).density * 100).roundToInt()) { v, done ->
                densityLabel.text = "Event density: $v%"
                if (done) cs.updateScene(cs.scene.copy(eventMapping = (cs.scene.eventMapping ?: presetMapping()).copy(density = v / 100f)))
            }
            densityLabel.text = "Event density: ${density.progress}%"
            fun refreshDensity() { val on = cs.scene.eventMapping != null; density.isEnabled = on; density.alpha = if (on) 1f else 0.4f; densityLabel.alpha = density.alpha }
            val modeRow = ChipRow(c, listOf<Pair<String, EventMode?>>("Preset default" to null) + EventMode.values().map { it.label to it }, cs.scene.eventMapping?.mode) { m ->
                cs.updateScene(cs.scene.copy(eventMapping = m?.let { (cs.scene.eventMapping ?: presetMapping()).copy(mode = it) }))
                density.progress = ((cs.scene.eventMapping ?: presetMapping()).density * 100).roundToInt()
                densityLabel.text = "Event density: ${density.progress}%"
                refreshDensity()
            }
            col.addView(modeRow.view)
            col.addView(densityLabel, Ui.matchWrap().apply { topMargin = Ui.dp(c, 10) })
            col.addView(density)
            refreshDensity()
            col.addView(Ui.text(c, "Sparse · Beat · Percussive · Melodic · Dense · Hybrid decide which musical events become physical contacts; the rest drive visual effects only.", 12f, Ui.MUTED))

            if (cs.session.mediaType == MediaType.MIDI) {
                col.addView(Ui.label(c, "MIDI voice mapping"))
                val polyRow = ChipRow(c, PolyphonyMapping.values().map { it.label to it }, cs.scene.eventMapping?.polyphony) { p ->
                    if (p == PolyphonyMapping.TRACK_SELECTION) midiTracks(act, cs)
                    else cs.updateScene(cs.scene.copy(eventMapping = (cs.scene.eventMapping ?: presetMapping()).copy(polyphony = p)))
                    modeRow.select(cs.scene.eventMapping?.mode)
                    refreshDensity()
                }
                col.addView(polyRow.view)
                col.addView(Ui.button(c, "Choose MIDI tracks…") { midiTracks(act, cs) }, Ui.matchWrap().apply { topMargin = Ui.dp(c, 8) })
            }
        }

        col.addView(Ui.label(c, "SoundFont"))
        val sfRow = ChipRow(c, listOf("GeneralUser GS (bundled)" to false, "Imported .sf2" to true), app.soundFonts.useUser && app.soundFonts.userFile.exists()) { user ->
            if (user && !app.soundFonts.userFile.exists()) { act.toast("Import a .sf2 file first"); return@ChipRow }
            if (app.soundFonts.useUser != user) {
                app.soundFonts.useUser = user; app.settings.useUserSoundFont = user
                cs.invalidateSynth()
                if (cs.session.mediaType == MediaType.MIDI) act.reloadCurrent()
            }
        }
        col.addView(sfRow.view)
        col.addView(Ui.button(c, "Import SoundFont (.sf2)…") {
            act.pickDocument(arrayOf("audio/x-soundfont", "audio/sf2", "application/octet-stream", "*/*")) { uri ->
                act.scope.launch {
                    val err = withContext(Dispatchers.IO) {
                        runCatching { act.contentResolver.openInputStream(uri)!!.use { app.soundFonts.importUser(it) } }.exceptionOrNull()
                    }
                    if (err != null) { act.toast("Not a valid SoundFont: ${err.message}"); return@launch }
                    app.soundFonts.useUser = true; app.settings.useUserSoundFont = true
                    sfRow.select(true)
                    cs.invalidateSynth()
                    act.toast("SoundFont imported")
                    if (cs.session.mediaType == MediaType.MIDI) act.reloadCurrent()
                }
            }
        }, Ui.matchWrap().apply { topMargin = Ui.dp(c, 8) })
        col.addView(Ui.text(c, "Used for MIDI playback, the collision layer and generative notes. MIDI is re-rendered when the SoundFont changes.", 12f, Ui.MUTED))

        col.addView(Ui.label(c, "Presets & replays"))
        col.addView(Ui.button(c, "Export current preset (JSON)…") { exportPreset(act, cs) }, Ui.matchWrap())
        col.addView(Ui.button(c, "Import preset…") { importPreset(act, cs) }, Ui.matchWrap().apply { topMargin = Ui.dp(c, 8) })
        col.addView(Ui.button(c, "Export replay…") { exportReplay(act, cs) }, Ui.matchWrap().apply { topMargin = Ui.dp(c, 8) })
        col.addView(Ui.button(c, "Import replay…") { importReplay(act, cs) }, Ui.matchWrap().apply { topMargin = Ui.dp(c, 8) })

        col.addView(Ui.label(c, "Diagnostics"))
        col.addView(Ui.switch(c, "Debug overlay", rs.showDebug) { rs = rs.copy(showDebug = it); cs.setRenderSettings(rs) })
        col.addView(Ui.button(c, "Export sync & performance report…") { exportReport(act, cs) }, Ui.matchWrap().apply { topMargin = Ui.dp(c, 4) })

        col.addView(Ui.text(c, "${Branding.APP_NAME} ${ReplayManager.APP_VERSION} · SoundFont: GeneralUser GS by S. Christian Collins (license bundled in the app). Everything stays on this device.", 11f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 16), 0, 0) })
        show(act, "Settings", col)
    }

    fun midiTracks(act: MainActivity, cs: CreatorScreen) {
        val midi = cs.session.midi ?: return
        val counts = midi.notes.groupingBy { it.track }.eachCount().toSortedMap()
        if (counts.isEmpty()) { act.toast("This MIDI file has no notes"); return }
        val tracks = counts.keys.toList()
        val current = cs.scene.eventMapping?.takeIf { it.polyphony == PolyphonyMapping.TRACK_SELECTION }?.selectedTracks?.toSet() ?: emptySet()
        val checked = BooleanArray(tracks.size) { tracks[it] in current }
        val labels = tracks.map { t ->
            val drums = midi.notes.any { it.track == t && it.isPercussion }
            "${midi.trackNames[t]?.takeIf { it.isNotBlank() } ?: "Track $t"} — ${counts[t]} notes${if (drums) " (drums)" else ""}"
        }.toTypedArray()
        Ui.dialog(act, "MIDI tracks that drive physics")
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Apply") { _, _ ->
                val sel = tracks.filterIndexed { i, _ -> checked[i] }
                if (sel.isEmpty()) { act.toast("Select at least one track"); return@setPositiveButton }
                val base = cs.scene.eventMapping ?: cs.resolver(cs.presetIdFor(cs.scene.focus))?.eventMapping ?: EventMappingSettings()
                cs.updateScene(cs.scene.copy(eventMapping = base.copy(polyphony = PolyphonyMapping.TRACK_SELECTION, selectedTracks = sel)))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- presets / replays / reports --------------------------------------------------------------
    private fun exportPreset(act: MainActivity, cs: CreatorScreen) {
        val p = cs.resolver(cs.presetIdFor(cs.currentPresetTarget)) ?: return
        act.createDocument("application/json", "${p.id}.json") { uri ->
            act.scope.launch {
                val err = withContext(Dispatchers.IO) { runCatching { act.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(PresetManager.export(p).toByteArray()) } }.exceptionOrNull() }
                act.toast(if (err == null) "Preset exported" else "Export failed: ${err.message}")
            }
        }
    }

    private fun importPreset(act: MainActivity, cs: CreatorScreen) {
        act.pickDocument(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) { uri ->
            act.scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { act.app.presets.import(act.readText(uri)) } }
                val p = r.getOrElse { act.toast("Invalid preset: ${it.message}"); return@launch }
                if (cs.isSandbox && (p.mechanic != MechanicType.CIRCLE || p.generation.mode == "reactive")) {
                    act.toast("Imported “${p.name}”. The sandbox only uses sandbox/generative Circle presets."); return@launch
                }
                val s = cs.scene
                cs.updateScene(s.copy(presetIds = s.presetIds + (p.mechanic to p.id), focus = if (s.viewMode == ViewMode.FOCUS) p.mechanic else s.focus))
                cs.syncControls()
                act.toast("Imported preset “${p.name}”")
            }
        }
    }

    private fun exportReplay(act: MainActivity, cs: CreatorScreen) {
        act.scope.launch {
            val engine = cs.liveEngine() ?: run { act.toast("Scene is still loading"); return@launch }
            val inputs = cs.liveInputs()
            val pos = cs.position()
            val verifyAt = if (pos > 1.0) pos else minOf(10.0, cs.session.durationSec)
            val progress = act.showProgress("Preparing replay", cancellable = false)
            val text = withContext(Dispatchers.Default) {
                runCatching {
                    val ids = cs.scene.presetIds.values + (cs.scene.journey?.segments?.map { it.presetId } ?: emptyList())
                    val extra = ids.mapNotNull { cs.resolver(it) }.filter { PresetManager.byId(it.id) != it }
                    ReplayManager.export(ReplayManager.create(engine, extra, inputs, verifyAt))
                }
            }
            progress.dismiss()
            val json = text.getOrElse { act.toast("Replay failed: ${it.message}"); return@launch }
            act.createDocument("application/json", "${Branding.FILE_PREFIX}-replay-${cs.session.title.take(24).filter { it.isLetterOrDigit() || it == '-' }}.json") { uri ->
                act.scope.launch {
                    val err = withContext(Dispatchers.IO) { runCatching { act.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) } }.exceptionOrNull() }
                    act.toast(if (err == null) "Replay exported" else "Export failed: ${err.message}")
                }
            }
        }
    }

    private fun importReplay(act: MainActivity, cs: CreatorScreen) {
        act.pickDocument(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) { uri ->
            act.scope.launch {
                val recipe = withContext(Dispatchers.IO) { runCatching { ReplayManager.import(act.readText(uri)) } }
                    .getOrElse { act.toast("Invalid replay: ${it.message}"); return@launch }
                val issues = ReplayManager.compatibility(recipe, cs.session)
                val apply = {
                    cs.applyReplay(recipe)
                    if (issues.isEmpty() && recipe.verifyHash != null && recipe.verifyAtSec != null) verifyReplay(act, cs, recipe)
                    else act.toast("Replay applied")
                }
                if (issues.isEmpty()) apply()
                else Ui.dialog(act, "Replay may not reproduce exactly")
                    .setMessage("Recorded with “${recipe.mediaTitle}”.\n\n" + issues.joinToString("\n") { "• $it" } + "\n\nApply its scene settings anyway?")
                    .setPositiveButton("Apply anyway") { _, _ -> apply() }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun verifyReplay(act: MainActivity, cs: CreatorScreen, r: com.rhythmphysics.core.session.ReplayRecipe) {
        act.scope.launch {
            val ok = withContext(Dispatchers.Default) {
                runCatching {
                    val e = ReplayManager.engineFor(r, cs.session)
                    e.loadInputs(r.sandboxInputs)
                    e.seek(r.verifyAtSec!!)
                    (e.stateHash() == r.verifyHash).also { e.dispose() }
                }.getOrDefault(false)
            }
            act.toast(if (ok) "Replay verified: identical simulation state at %.1f s".format(r.verifyAtSec) else "Replay applied, but the simulation state differs from the recording")
        }
    }

    private fun exportReport(act: MainActivity, cs: CreatorScreen) {
        act.scope.launch {
            val report = cs.diagnostics() ?: run { act.toast("Scene is still loading"); return@launch }
            val text = reportJson.encodeToString(DeviceReport.serializer(), report)
            act.createDocument("application/json", "${Branding.FILE_PREFIX}-sync-report.json") { uri ->
                act.scope.launch {
                    val err = withContext(Dispatchers.IO) { runCatching { act.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }.exceptionOrNull() }
                    act.toast(if (err == null) "Report exported" else "Export failed: ${err.message}")
                }
            }
        }
    }

    // ---- journey editor ---------------------------------------------------------------------------
    fun journeyEditor(act: MainActivity, cs: CreatorScreen) {
        val c = act
        val base = cs.scene.journey ?: JourneyPlanner.autoSchedule(cs.session, cs.scene, cs.resolver)
        val segs = base.segments.toMutableList()
        val col = sheet(c)
        col.addView(Ui.text(c, "Segments follow the song's sections. Pick a mechanic and preset for each; transitions keep the audio running.", 13f, Ui.MUTED))
        segs.forEachIndexed { i, seg ->
            col.addView(Ui.label(c, "Segment ${i + 1} · ${Ui.formatTime(seg.startSec)} – ${Ui.formatTime(seg.endSec)}").apply {
                isClickable = true
                setOnClickListener { cs.seekTo(segs[i].startSec) }
                contentDescription = "Segment ${i + 1}, tap to jump there"
            })
            lateinit var presetRow: ChipRow<String>
            fun presetItems(t: MechanicType) = cs.presetsFor(t).map { it.name.substringAfter("— ") to it.id }
            val mechRow = ChipRow(c, MechanicType.values().map { it.label to it }, seg.mechanic) { t ->
                val pid = cs.presetIdFor(t)
                segs[i] = segs[i].copy(mechanic = t, presetId = pid)
                presetRow.setItems(presetItems(t), pid)
            }
            presetRow = ChipRow(c, presetItems(seg.mechanic), seg.presetId) { id -> segs[i] = segs[i].copy(presetId = id) }
            col.addView(mechRow.view)
            col.addView(presetRow.view, Ui.matchWrap().apply { topMargin = Ui.dp(c, 6) })
        }
        show(act, "Scripted journey", col, positive = "Apply", onPositive = {
            cs.updateScene(cs.scene.copy(viewMode = ViewMode.SCRIPTED_JOURNEY, journey = JourneySchedule(segs.toList(), base.transitionSec)))
            cs.syncControls()
        }, neutral = "Auto" to {
            cs.updateScene(cs.scene.copy(viewMode = ViewMode.JOURNEY, journey = null))
            cs.syncControls()
        })
    }

    // ---- export -------------------------------------------------------------------------------------
    fun export(act: MainActivity, cs: CreatorScreen) {
        val c = act
        val aspect = cs.scene.aspect
        val target = aspect.width.toDouble() / aspect.height
        val sizes = ExportSettings.PRESETS.filter { (_, wh) -> abs(wh.first.toDouble() / wh.second - target) < 0.01 }
        val supported = sizes.filter { (_, wh) -> encoderSupports(wh.first, wh.second, 30) }
        if (supported.isEmpty()) {
            Ui.dialog(act, "Export unavailable").setMessage("This device's H.264 encoder does not support ${sizes.joinToString { it.first }}. Try another aspect ratio.").setPositiveButton("OK", null).show()
            return
        }
        var size = supported.last().second
        var fps = 30
        val pos = cs.position()
        val dur = cs.session.durationSec
        var range: Pair<Double, Double> = if (cs.isSandbox) 0.0 to pos.coerceAtLeast(5.0) else 0.0 to dur
        val col = sheet(c)
        col.addView(Ui.label(c, "Resolution"))
        lateinit var fpsRow: ChipRow<Int>
        val fpsOptions = { listOf(30, 60).filter { encoderSupports(size.first, size.second, it) } }
        col.addView(ChipRow(c, supported.map { it.first to it.second }, size) { s -> size = s; fpsRow.setItems(fpsOptions().map { "$it fps" to it }, fpsOptions().let { if (fps in it) fps else it.first() }); fps = fpsRow.selected ?: 30 }.view)
        col.addView(Ui.label(c, "Frame rate"))
        fpsRow = ChipRow(c, fpsOptions().map { "$it fps" to it }, 30) { f -> fps = f }
        col.addView(fpsRow.view)
        col.addView(Ui.label(c, "Range"))
        val ranges = ArrayList<Pair<String, Pair<Double, Double>>>()
        if (cs.isSandbox) ranges += "Start → playhead (${Ui.formatTime(range.second)})" to range else ranges += "Whole song (${Ui.formatTime(dur)})" to (0.0 to dur)
        for (len in listOf(15.0, 30.0, 60.0)) if (pos + 1 < dur) ranges += "${len.toInt()} s from ${Ui.formatTime(pos)}" to (pos to minOf(dur, pos + len))
        col.addView(ChipRow(c, ranges, range) { r -> range = r }.view)
        val audioText = when {
            cs.isSandbox -> "Audio: generative SoundFont notes"
            cs.scene.audioMode == AudioMode.ANALYSIS_ONLY -> "Audio: muted (analysis-only mode)"
            cs.scene.audioMode == AudioMode.ORIGINAL_AND_COLLISION_LAYER -> "Audio: original song + collision layer"
            else -> "Audio: original song"
        }
        col.addView(Ui.text(c, "$audioText. Frames are rendered offline at exact song times, so the video is frame-accurate even on slow devices. UI and debug overlays are never recorded.", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 12), 0, 0) })
        show(act, "Export video", col, positive = "Choose file…", onPositive = {
            val name = "${Branding.FILE_PREFIX}-${cs.session.title.take(32).filter { it.isLetterOrDigit() || it == '-' || it == ' ' }.trim().replace(' ', '-')}-${size.first}x${size.second}.mp4"
            act.createDocument("video/mp4", name) { uri -> runExport(act, cs, uri, ExportSettings(size.first, size.second, fps, range.first, range.second)) }
        })
    }

    private fun runExport(act: MainActivity, cs: CreatorScreen, uri: android.net.Uri, settings: ExportSettings) {
        if (cs.clock.isPlaying) cs.playback.pause()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val progress = act.showProgress("Exporting video", cancellable = true) { cancelled.set(true) }
        act.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        act.scope.launch {
            val inputs = cs.liveInputs() // includes replay-loaded inputs (loadInputs seeds the log)
            val scene = cs.scene
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    act.contentResolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                        VideoExporter(
                            session = cs.session, config = scene, presetResolver = cs.resolver, inputs = inputs,
                            songPcm = cs.loaded?.pcm, soundFont = { act.app.soundFonts.get().second },
                            impactLayer = scene.audioMode == AudioMode.ORIGINAL_AND_COLLISION_LAYER,
                            generativeNotes = true,
                            muteSong = scene.audioMode == AudioMode.ANALYSIS_ONLY,
                            settings = settings, renderSettings = cs.userRender,
                        ).export(pfd.fileDescriptor, { msg, p -> act.runOnUiThread { progress.update(msg, p) } }, { cancelled.get() })
                    }
                }
            }
            progress.dismiss()
            act.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val err = result.exceptionOrNull()
            if (err == null) act.toast("Video saved (${settings.width}×${settings.height}, ${settings.fps} fps)")
            else {
                withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(act.contentResolver, uri) } }
                act.toast(if (err is ExportCancelled) "Export cancelled" else "Export failed: ${err.message}")
            }
        }
    }
}
