package com.rhythmphysics.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import com.rhythmphysics.app.MainActivity
import com.rhythmphysics.app.audio.AudioClock
import com.rhythmphysics.app.audio.AudioEngine
import com.rhythmphysics.app.audio.CollisionLayer
import com.rhythmphysics.app.audio.FreeClock
import com.rhythmphysics.app.audio.PlaybackController
import com.rhythmphysics.app.audio.RealtimeSynth
import com.rhythmphysics.app.platform.ThermalMonitor
import com.rhythmphysics.app.render.VisualizerView
import com.rhythmphysics.app.session.LoadedSession
import com.rhythmphysics.core.engine.RhythmEngine
import com.rhythmphysics.core.mechanic.EngineSink
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.music.EventMappingSettings
import com.rhythmphysics.core.preset.Preset
import com.rhythmphysics.core.preset.PresetManager
import com.rhythmphysics.core.render.AspectRatio
import com.rhythmphysics.core.render.Quality
import com.rhythmphysics.core.render.RenderSettings
import com.rhythmphysics.core.session.AudioMode
import com.rhythmphysics.core.session.RhythmSession
import com.rhythmphysics.core.session.SceneConfig
import com.rhythmphysics.core.session.ViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * The creator screen: live visualizer + transport + scene controls. One audio clock drives every
 * mechanic; changing mechanic/preset/seed/aspect rebuilds the deterministic engine in the
 * background and swaps it in at the current song position — the audio never restarts.
 */
class CreatorScreen(
    private val act: MainActivity,
    /** Null for the no-music sandbox. */
    val loaded: LoadedSession?,
    initialScene: SceneConfig,
    startSec: Double,
    /** Presets carried by an imported replay recipe (take precedence over same-id presets). */
    recipePresets: Map<String, Preset> = emptyMap(),
) {
    private val app = act.app
    val session: RhythmSession = loaded?.session ?: RhythmSession.sandbox()
    val isSandbox get() = loaded == null
    val clock: AudioClock = loaded?.pcm?.let { AudioEngine(it) } ?: FreeClock(session.durationSec)
    val playback = PlaybackController(act, clock)
    val visualizer = VisualizerView(act)
    private val main = Handler(Looper.getMainLooper())

    val overrides = HashMap(recipePresets)
    val resolver: (String) -> Preset? = { id -> overrides[id] ?: app.presets.resolver()(id) }
    @Volatile var scene: SceneConfig = normalize(initialScene); private set
    var userRender: RenderSettings = app.settings.renderSettings; private set
    private var thermalCap: Quality? = null
    private val thermal = ThermalMonitor(act) { cap -> main.post { thermalCap = cap; pushRenderSettings() } }
    private var synth: RealtimeSynth? = null
    private var synthLoading = false
    private var buildJob: Job? = null
    private var buildGen = 0
    private var released = false
    @Volatile var engineReady = false; private set
    var cleanMode = false; private set
    /** Sandbox inputs loaded from an imported replay; re-applied whenever the engine is rebuilt. */
    var baseInputs: List<com.rhythmphysics.core.session.SandboxInput> = emptyList()
    private var presetTarget: MechanicType = scene.focus
    /** Mechanic whose preset row is shown (the focused one in single view). */
    val currentPresetTarget: MechanicType get() = presetTarget

    /** Live side effects: haptics + optional collision layer + generative notes. Called on the render thread. */
    private val sink = object : EngineSink {
        override fun onImpact(mechanic: MechanicType, timeSec: Double, strength: Float, eventId: Long, note: Int?) {
            if (!clock.isPlaying) return
            app.haptics.impact(strength)
            if (scene.audioMode == AudioMode.ORIGINAL_AND_COLLISION_LAYER)
                synth?.note(CollisionLayer.noteFor(eventId, note), CollisionLayer.velocityFor(strength), CollisionLayer.PROGRAM, CollisionLayer.IMPACT_NOTE_SEC)
        }
        override fun onNote(timeSec: Double, note: Int, velocity: Float, program: Int) {
            if (!clock.isPlaying) return
            synth?.note(note, velocity, program, CollisionLayer.GENERATIVE_NOTE_SEC)
        }
    }

    // ---- views ------------------------------------------------------------------------------
    val root = FrameLayout(act).apply { setBackgroundColor(Ui.BG) }
    private val body = LinearLayout(act)
    private val panel = Ui.column(act).apply { background = Ui.rounded(Ui.PANEL, Ui.dp(act, 18).toFloat()) }
    private val scroll = MaxHeightScrollView(act)
    private val controls = Ui.column(act)
    private val titleView = Ui.text(act, session.title, 16f, bold = true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
    private val subtitleView = Ui.text(act, "", 12f, Ui.MUTED).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
    private val playBtn = IconView(act, IconView.Kind.PLAY, "Play", big = true)
    private val timeView = Ui.text(act, "0:00 / 0:00", 13f, Ui.MUTED).apply { gravity = Gravity.CENTER_VERTICAL }
    private val seekBar = Ui.slider(act, 1000, 0) { p, done -> onSeek(p, done) }
    private val debugBtn = IconView(act, IconView.Kind.DEBUG, "Debug overlay")
    private val collapseBtn = IconView(act, IconView.Kind.COLLAPSE, "Collapse controls")
    private val statusView = Ui.text(act, "", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(act, 10), 0, 0) }
    private val cleanHint = Ui.text(act, "Clean view — tap or press Back to show controls", 13f).apply {
        background = Ui.rounded(0xCC000000.toInt(), Ui.dp(act, 16).toFloat())
        setPadding(Ui.dp(act, 14), Ui.dp(act, 8), Ui.dp(act, 14), Ui.dp(act, 8))
        visibility = View.GONE
    }

    private lateinit var mechanicRow: ChipRow<String>
    private lateinit var layoutRow: ChipRow<ViewMode>
    private lateinit var partnerRow: ChipRow<MechanicType>
    private lateinit var journeyRow: ChipRow<ViewMode>
    private lateinit var presetTargetRow: ChipRow<MechanicType>
    private lateinit var presetRow: ChipRow<String>
    private lateinit var aspectRow: ChipRow<AspectRatio>
    private lateinit var seedField: android.widget.EditText
    private val layoutSection = Ui.column(act)
    private val partnerSection = Ui.column(act)
    private val journeySection = Ui.column(act)
    private val presetTargetSection = Ui.column(act)
    private val presetSection = Ui.column(act)
    private var dragging = false
    private var scrubSec = 0.0
    private var collapsed = app.settings.panelCollapsed

    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            updateTransport()
            main.postDelayed(this, 120)
        }
    }

    init {
        buildViews()
        (clock as? AudioEngine)?.onCompletion = { main.post { if (!released) { playback.pause(); updateTransport() } } }
        playback.onStateChanged = { onPlayState() }
        playback.muted = scene.audioMode == AudioMode.ANALYSIS_ONLY
        if (startSec > 0) playback.seek(startSec)
        visualizer.renderSettings = effectiveRender()
        visualizer.tapEnabled = isSandbox
        visualizer.onTap = { e, x, y -> e.input("tap", x, y) }
        visualizer.onPlainTap = { if (cleanMode) setCleanMode(false) }
        rebuild()
        ensureSynth()
        main.post(ticker)
    }

    // ---- scene ------------------------------------------------------------------------------
    private fun normalize(c: SceneConfig): SceneConfig {
        if (!isSandbox) return c
        val cur = c.presetIds[MechanicType.CIRCLE]?.let { resolver(it) }
        val ok = cur != null && cur.mechanic == MechanicType.CIRCLE && cur.generation.mode != "reactive"
        return c.copy(viewMode = ViewMode.FOCUS, focus = MechanicType.CIRCLE,
            presetIds = c.presetIds + (MechanicType.CIRCLE to (if (ok) cur!!.id else "circle.sandbox")))
    }

    fun presetIdFor(type: MechanicType): String =
        scene.presetIds[type]?.takeIf { id -> resolver(id)?.mechanic == type } ?: PresetManager.defaultFor(type).id

    fun presetsFor(type: MechanicType): List<Preset> {
        val list = LinkedHashMap<String, Preset>()
        PresetManager.forMechanic(type).forEach { list[it.id] = it }
        app.presets.forMechanic(type).forEach { list[it.id] = it }
        overrides.values.filter { it.mechanic == type }.forEach { if (it.id !in list) list[it.id] = it }
        var all = list.values.toList()
        if (isSandbox) all = all.filter { it.generation.mode != "reactive" }
        return all
    }

    /** Applies a scene change. Simulation-affecting changes rebuild the engine at the current time. */
    fun updateScene(next: SceneConfig) {
        val n = normalize(next)
        if (n == scene) return
        val audioOnly = n.copy(audioMode = scene.audioMode) == scene
        scene = n
        app.settings.lastScene = n
        playback.muted = n.audioMode == AudioMode.ANALYSIS_ONLY
        ensureSynth()
        syncControls()
        if (!audioOnly) rebuild()
    }

    fun rebuild() {
        val gen = ++buildGen
        val cfg = scene
        val inputs = baseInputs
        engineReady = false
        setStatus("Building scene…")
        buildJob?.cancel()
        buildJob = act.scope.launch {
            val t = clock.positionSec()
            val e = try {
                withContext(Dispatchers.Default) {
                    RhythmEngine(session, cfg, sink, presetResolver = resolver).also { e ->
                        e.debugProvider = { debugLines() }
                        if (inputs.isNotEmpty()) e.loadInputs(inputs)
                        if (t > 0) e.update(t) // deterministic fast-forward to the playhead
                    }
                }
            } catch (ex: Exception) {
                if (gen == buildGen) { setStatus("Could not build this scene: ${ex.message}"); act.toast("Scene error: ${ex.message}") }
                return@launch
            }
            if (gen != buildGen || released) { e.dispose(); return@launch }
            visualizer.attach(e, clock)
            engineReady = true
            if (!clock.isPlaying) visualizer.redraw()
            setStatus(null)
        }
    }

    /** The live engine, fetched on the render thread (null while the first build is running). */
    suspend fun liveEngine(): RhythmEngine? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        visualizer.query { e -> if (cont.isActive) cont.resumeWith(Result.success(e)) }
    }

    /** Copies the live sandbox input log on the render thread (the only thread that mutates it). */
    suspend fun liveInputs(): List<com.rhythmphysics.core.session.SandboxInput> = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        visualizer.query { e -> if (cont.isActive) cont.resumeWith(Result.success(e?.inputLog?.toList() ?: emptyList())) }
    }

    /** Snapshot of sync/perf diagnostics, taken on the render thread. */
    suspend fun diagnostics(): DeviceReport? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        visualizer.query { e ->
            val r = e?.let {
                DeviceReport(
                    app = "${com.rhythmphysics.core.Branding.APP_NAME} ${com.rhythmphysics.core.session.ReplayManager.APP_VERSION}",
                    device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", sdk = android.os.Build.VERSION.SDK_INT,
                    media = session.title, mediaType = session.mediaType.name, scene = it.config,
                    clock = if (clock is AudioEngine) "AudioTrack.getTimestamp (smoothed)" else "monotonic (sandbox)",
                    positionSec = it.renderTime, simTimeSec = it.simTime, logicalSync = it.sync.stats(),
                    frameAvgMs = it.frameStats.averageMs, frameWorstMs = it.frameStats.worstMs, fps = it.frameStats.fps,
                    drawCommands = it.frameStats.drawCommands, quality = effectiveRender().quality.name, thermalStatus = thermal.label,
                    syncSamples = it.sync.samples().takeLast(2000),
                )
            }
            if (cont.isActive) cont.resumeWith(Result.success(r))
        }
    }

    /** Replaces the scene with an imported replay (presets + scene + sandbox inputs). */
    fun applyReplay(r: com.rhythmphysics.core.session.ReplayRecipe) {
        for (p in r.presets) if (PresetManager.byId(p.id) != p) overrides[p.id] = p
        baseInputs = r.sandboxInputs
        scene = SceneConfig() // force rebuild even if identical
        updateScene(r.scene)
        syncControls()
    }

    private fun debugLines(): List<String> {
        val ae = clock as? AudioEngine
        return listOf(
            "clock ${if (ae != null) "AudioTrack timestamp" else "free-running (sandbox)"}  playing ${clock.isPlaying}",
            "quality ${effectiveRender().quality.label}${thermalCap?.let { " (thermal cap ${it.label})" } ?: ""}  thermal ${thermal.label}",
            "audio mode ${scene.audioMode.label}",
        )
    }

    // ---- render settings --------------------------------------------------------------------
    private fun effectiveRender(): RenderSettings {
        val q = ThermalMonitor.apply(userRender.quality, thermalCap)
        return userRender.copy(quality = q, cleanOutput = cleanMode)
    }

    fun setRenderSettings(rs: RenderSettings) {
        userRender = rs.copy(cleanOutput = false)
        app.settings.renderSettings = userRender
        pushRenderSettings()
        debugBtn.active = userRender.showDebug
    }

    private fun pushRenderSettings() {
        val rs = effectiveRender()
        visualizer.post { visualizer.renderSettings = rs }
        visualizer.redraw()
    }

    // ---- audio ------------------------------------------------------------------------------
    private fun needsSynth(): Boolean {
        if (scene.audioMode == AudioMode.ORIGINAL_AND_COLLISION_LAYER) return true
        val types = when (scene.viewMode) {
            ViewMode.FOCUS -> listOf(scene.focus)
            ViewMode.DUET -> scene.duet
            else -> MechanicType.values().toList()
        }
        return MechanicType.CIRCLE in types && resolver(presetIdFor(MechanicType.CIRCLE))?.generation?.mode != "reactive"
    }

    private fun ensureSynth() {
        if (synth != null || synthLoading || !needsSynth()) return
        synthLoading = true
        act.scope.launch {
            val s = withContext(Dispatchers.IO) { runCatching { RealtimeSynth(app.soundFonts.get().second) }.getOrNull() }
            synthLoading = false
            if (released) return@launch
            if (s == null) { act.toast("SoundFont unavailable: collision sounds disabled"); return@launch }
            synth = s
            if (clock.isPlaying) s.start()
        }
    }

    fun invalidateSynth() { synth?.stop(); synth = null; ensureSynth() }

    private fun onPlayState() {
        val playing = clock.isPlaying
        playBtn.kind = if (playing) IconView.Kind.PAUSE else IconView.Kind.PLAY
        playBtn.contentDescription = if (playing) "Pause" else "Play"
        if (playing) { act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); synth?.start(); visualizer.resume() }
        else { act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); synth?.stop(); visualizer.pauseRendering(); visualizer.redraw() }
        updateTransport()
    }

    fun togglePlay() {
        if (clock.isPlaying) playback.pause()
        else {
            if (clock.positionSec() >= clock.durationSec - 0.05) playback.seek(0.0)
            playback.play()
            if (!clock.isPlaying) act.toast("Audio focus unavailable (another app is playing)")
        }
    }

    fun seekTo(sec: Double) { playback.seek(sec.coerceIn(0.0, clock.durationSec)); visualizer.redraw(); updateTransport() }

    private fun onSeek(p: Int, done: Boolean) {
        scrubSec = p / 1000.0 * clock.durationSec
        dragging = !done
        if (done) seekTo(scrubSec) else timeView.text = "${Ui.formatTime(scrubSec)} / ${Ui.formatTime(clock.durationSec)}"
    }

    private fun updateTransport() {
        if (dragging) return
        val t = clock.positionSec(); val d = clock.durationSec
        timeView.text = "${Ui.formatTime(t)} / ${Ui.formatTime(d)}"
        seekBar.progress = if (d > 0) (t / d * 1000).toInt().coerceIn(0, 1000) else 0
    }

    // ---- lifecycle --------------------------------------------------------------------------
    fun onHostPause() {
        if (clock.isPlaying) playback.pause()
        thermal.stop()
        synth?.stop()
    }

    fun onHostResume() {
        thermal.start()
        thermalCap = thermal.cap
        pushRenderSettings()
        visualizer.redraw()
        if (cleanMode) act.setImmersiveBars(true)
    }

    /** Returns true when Back was consumed. */
    fun onBack(): Boolean {
        if (cleanMode) { setCleanMode(false); return true }
        return false
    }

    fun release() {
        released = true
        main.removeCallbacks(ticker)
        buildJob?.cancel()
        playback.release()
        thermal.stop()
        synth?.stop(); synth = null
        visualizer.release()
        act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun position(): Double = clock.positionSec()

    // ---- clean mode ---------------------------------------------------------------------------
    fun setCleanMode(on: Boolean) {
        cleanMode = on
        panel.visibility = if (on) View.GONE else View.VISIBLE
        act.setImmersiveBars(on)
        applyInsets()
        pushRenderSettings()
        if (on) {
            cleanHint.visibility = View.VISIBLE; cleanHint.alpha = 1f
            cleanHint.animate().alpha(0f).setStartDelay(1800).setDuration(500).withEndAction { cleanHint.visibility = View.GONE }.start()
        }
    }

    // ---- layout -----------------------------------------------------------------------------
    private var insetL = 0; private var insetT = 0; private var insetR = 0; private var insetB = 0

    fun setInsets(l: Int, t: Int, r: Int, b: Int) { insetL = l; insetT = t; insetR = r; insetB = b; applyInsets() }

    private fun applyInsets() {
        if (cleanMode) body.setPadding(0, 0, 0, 0) else body.setPadding(insetL, insetT, insetR, insetB)
    }

    fun relayout() {
        val landscape = act.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        body.removeAllViews()
        val m = Ui.dp(act, 8)
        if (landscape) {
            body.orientation = LinearLayout.HORIZONTAL
            body.addView(visualizer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            val w = minOf(Ui.dp(act, 380), (act.resources.displayMetrics.widthPixels * 0.46f).toInt())
            body.addView(panel, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(m, m, m, m) })
            scroll.maxHeight = Int.MAX_VALUE
            (scroll.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f }
        } else {
            body.orientation = LinearLayout.VERTICAL
            body.addView(visualizer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            body.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(m, m, m, m) })
            scroll.maxHeight = (act.resources.displayMetrics.heightPixels * 0.26f).toInt()
            (scroll.layoutParams as LinearLayout.LayoutParams).apply { height = ViewGroup.LayoutParams.WRAP_CONTENT; weight = 0f }
        }
        scroll.requestLayout()
    }

    private fun buildViews() {
        val c = act
        val pad = Ui.dp(c, 14)
        panel.setPadding(pad, Ui.dp(c, 10), pad, Ui.dp(c, 10))

        // Header: back, title, debug, settings
        val back = IconView(c, IconView.Kind.BACK, "Back to home").apply { setOnClickListener { act.goHome() } }
        val titles = Ui.column(c).apply { addView(titleView); addView(subtitleView) }
        val settingsBtn = IconView(c, IconView.Kind.SETTINGS, "Settings").apply { setOnClickListener { CreatorDialogs.settings(act, this@CreatorScreen) } }
        debugBtn.active = userRender.showDebug
        debugBtn.setOnClickListener { setRenderSettings(userRender.copy(showDebug = !userRender.showDebug)) }
        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(back)
            addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(c, 10); marginEnd = Ui.dp(c, 6) })
            addView(debugBtn); addView(settingsBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(c, 8) })
        }
        panel.addView(header)

        // Transport: restart, play, time, clean, export, collapse
        val restart = IconView(c, IconView.Kind.RESTART, "Restart").apply { setOnClickListener { seekTo(0.0) } }
        playBtn.setOnClickListener { togglePlay() }
        val clean = IconView(c, IconView.Kind.CLEAN, "Clean view").apply { setOnClickListener { setCleanMode(true) } }
        val export = IconView(c, IconView.Kind.EXPORT, "Export video").apply { setOnClickListener { CreatorDialogs.export(act, this@CreatorScreen) } }
        collapseBtn.setOnClickListener { setCollapsed(!collapsed) }
        val transport = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(c, 8), 0, 0)
            addView(restart); addView(playBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(c, 8) })
            addView(timeView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(c, 12) })
            for (v in listOf(clean, export, collapseBtn)) addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(c, 8) })
        }
        panel.addView(transport)
        panel.addView(seekBar, Ui.matchWrap().apply { topMargin = Ui.dp(c, 6) })

        // Scene controls (scrollable, collapsible)
        buildControls()
        scroll.addView(controls)
        scroll.isVerticalScrollBarEnabled = false
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(body, Ui.frameMatch())
        root.addView(cleanHint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dp(c, 36) })
        setCollapsed(collapsed)
        relayout()
        syncControls()
        onPlayState()
    }

    private fun setCollapsed(v: Boolean) {
        collapsed = v
        app.settings.panelCollapsed = v
        scroll.visibility = if (v) View.GONE else View.VISIBLE
        collapseBtn.kind = if (v) IconView.Kind.EXPAND else IconView.Kind.COLLAPSE
        collapseBtn.contentDescription = if (v) "Show scene controls" else "Hide scene controls"
    }

    private fun buildControls() {
        val c = act
        if (!isSandbox) {
            controls.addView(Ui.label(c, "Mechanic"))
            mechanicRow = ChipRow(c, MechanicType.values().map { it.label to it.name } + ("Journey" to "JOURNEY"), null) { key -> onMechanic(key) }
            controls.addView(mechanicRow.view)

            layoutSection.addView(Ui.label(c, "Layout"))
            layoutRow = ChipRow(c, listOf("Single" to ViewMode.FOCUS, "Duet" to ViewMode.DUET, "Quad" to ViewMode.QUAD), null) { mode ->
                val s = scene
                updateScene(when (mode) {
                    ViewMode.DUET -> s.copy(viewMode = ViewMode.DUET, duet = listOf(s.focus, s.duet.firstOrNull { it != s.focus } ?: partnerDefault(s.focus)))
                    else -> s.copy(viewMode = mode)
                })
            }
            layoutSection.addView(layoutRow.view)
            controls.addView(layoutSection)

            partnerSection.addView(Ui.label(c, "Duet partner"))
            partnerRow = ChipRow(c, emptyList(), null) { p -> updateScene(scene.copy(duet = listOf(scene.focus, p))) }
            partnerSection.addView(partnerRow.view)
            controls.addView(partnerSection)

            journeySection.addView(Ui.label(c, "Journey"))
            journeyRow = ChipRow(c, listOf("Auto (song sections)" to ViewMode.JOURNEY, "Scripted" to ViewMode.SCRIPTED_JOURNEY), null) { mode ->
                if (mode == ViewMode.SCRIPTED_JOURNEY) CreatorDialogs.journeyEditor(act, this) else updateScene(scene.copy(viewMode = ViewMode.JOURNEY, journey = null))
                syncControls()
            }
            journeySection.addView(journeyRow.view)
            journeySection.addView(Ui.button(c, "Edit journey segments…") { CreatorDialogs.journeyEditor(act, this) }, Ui.matchWrap().apply { topMargin = Ui.dp(c, 8) })
            controls.addView(journeySection)
        } else {
            controls.addView(Ui.text(c, "Sandbox — tap inside the ring to add balls. Every tap is recorded and replayable.", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 10), 0, 0) })
        }

        presetTargetSection.addView(Ui.label(c, "Edit presets for"))
        presetTargetRow = ChipRow(c, emptyList(), null) { t -> presetTarget = t; syncControls() }
        presetTargetSection.addView(presetTargetRow.view)
        controls.addView(presetTargetSection)

        presetSection.addView(Ui.label(c, "Preset"))
        presetRow = ChipRow(c, emptyList(), null) { id -> updateScene(scene.copy(presetIds = scene.presetIds + (presetTarget to id))) }
        presetSection.addView(presetRow.view)
        controls.addView(presetSection)

        controls.addView(Ui.label(c, "Seed"))
        seedField = Ui.numberField(c, scene.seed.toString()).apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, action, ev ->
                if (action == EditorInfo.IME_ACTION_DONE || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { applySeedField(); true } else false
            }
            setOnFocusChangeListener { _, has -> if (!has) applySeedField() }
        }
        val dice = IconView(c, IconView.Kind.DICE, "Random seed").apply {
            setOnClickListener { val s = Random.nextLong(1, 999_999_999); seedField.setText(s.toString()); applySeedField() }
        }
        controls.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(seedField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(dice, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(c, 8) })
        })

        controls.addView(Ui.label(c, "Aspect ratio"))
        aspectRow = ChipRow(c, AspectRatio.values().map { it.label to it }, scene.aspect) { a -> updateScene(scene.copy(aspect = a)) }
        controls.addView(aspectRow.view)
        controls.addView(statusView)
    }

    private fun applySeedField() {
        val v = seedField.text.toString().trim().toLongOrNull()
        if (v == null) { seedField.setText(scene.seed.toString()); return }
        (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(seedField.windowToken, 0)
        seedField.clearFocus()
        updateScene(scene.copy(seed = v))
    }

    private fun partnerDefault(t: MechanicType) = if (t == MechanicType.ARCH) MechanicType.SQUARE else MechanicType.ARCH

    private fun onMechanic(key: String) {
        val s = scene
        if (key == "JOURNEY") {
            updateScene(s.copy(viewMode = if (s.journey != null) ViewMode.SCRIPTED_JOURNEY else ViewMode.JOURNEY))
            return
        }
        val t = MechanicType.valueOf(key)
        presetTarget = t
        updateScene(when (s.viewMode) {
            ViewMode.DUET -> s.copy(focus = t, duet = listOf(t, s.duet.getOrNull(1)?.takeIf { it != t } ?: partnerDefault(t)))
            ViewMode.QUAD -> s.copy(focus = t)
            else -> s.copy(viewMode = ViewMode.FOCUS, focus = t)
        })
        syncControls()
    }

    /** Refreshes every control from [scene] (after edits, replay import, restore). */
    fun syncControls() {
        val s = scene
        val journey = s.viewMode == ViewMode.JOURNEY || s.viewMode == ViewMode.SCRIPTED_JOURNEY
        if (!isSandbox) {
            mechanicRow.select(if (journey) "JOURNEY" else s.focus.name)
            layoutSection.visibility = if (journey) View.GONE else View.VISIBLE
            layoutRow.select(if (journey) null else s.viewMode)
            partnerSection.visibility = if (s.viewMode == ViewMode.DUET) View.VISIBLE else View.GONE
            if (s.viewMode == ViewMode.DUET) partnerRow.setItems(MechanicType.values().filter { it != s.focus }.map { it.label to it }, s.duet.getOrNull(1))
            journeySection.visibility = if (journey) View.VISIBLE else View.GONE
            journeyRow.select(if (journey) s.viewMode else null)
        }
        val multi = s.viewMode != ViewMode.FOCUS && !isSandbox
        val targets = when (s.viewMode) {
            ViewMode.FOCUS -> listOf(s.focus)
            ViewMode.DUET -> s.duet
            else -> MechanicType.values().toList()
        }
        if (presetTarget !in targets) presetTarget = if (s.focus in targets) s.focus else targets.first()
        presetTargetSection.visibility = if (multi && s.viewMode != ViewMode.SCRIPTED_JOURNEY) View.VISIBLE else View.GONE
        presetTargetRow.setItems(targets.map { it.label to it }, presetTarget)
        presetSection.visibility = if (s.viewMode == ViewMode.SCRIPTED_JOURNEY) View.GONE else View.VISIBLE
        presetRow.setItems(presetsFor(presetTarget).map { it.name.substringAfter("— ").ifEmpty { it.name } to it.id }, presetIdFor(presetTarget))
        aspectRow.select(s.aspect)
        if (!seedField.hasFocus()) seedField.setText(s.seed.toString())
        subtitleView.text = buildString {
            append(when (session.mediaType) { com.rhythmphysics.core.session.MediaType.MIDI -> "MIDI"; com.rhythmphysics.core.session.MediaType.AUDIO -> "Audio"; else -> "Sandbox" })
            if (!isSandbox) append(" · %.0f BPM".format(session.bpm))
            append(" · ").append(s.viewMode.label)
            if (s.viewMode == ViewMode.FOCUS) append(" · ").append(s.focus.label)
        }
    }

    private fun setStatus(msg: String?) {
        statusView.text = msg ?: when {
            isSandbox -> ""
            else -> "Events: ${(scene.eventMapping ?: resolver(presetIdFor(scene.focus))?.eventMapping ?: EventMappingSettings()).let { "${it.mode.label}, density ${(it.density * 100).toInt()}%" }}" +
                if (scene.eventMapping == null) " (preset default)" else ""
        }
    }

    /** ScrollView with a maximum height (keeps the preview large in portrait). */
    class MaxHeightScrollView(c: Context) : ScrollView(c) {
        var maxHeight = Int.MAX_VALUE
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val hs = if (maxHeight == Int.MAX_VALUE) heightMeasureSpec else MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, hs)
        }
    }
}

/** On-device sync/performance report (Settings → Diagnostics → Export report). */
@kotlinx.serialization.Serializable
data class DeviceReport(
    val app: String, val device: String, val sdk: Int, val media: String, val mediaType: String,
    val scene: SceneConfig, val clock: String, val positionSec: Double, val simTimeSec: Double,
    val logicalSync: com.rhythmphysics.core.diag.SyncStats,
    val frameAvgMs: Double, val frameWorstMs: Double, val fps: Double, val drawCommands: Int,
    val quality: String, val thermalStatus: String,
    val syncSamples: List<com.rhythmphysics.core.diag.SyncSample>,
)
