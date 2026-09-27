package com.rhythmphysics.app

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.Toast
import com.rhythmphysics.app.session.LoadedSession
import com.rhythmphysics.app.store.RecentItem
import com.rhythmphysics.app.ui.CreatorScreen
import com.rhythmphysics.app.ui.HomeScreen
import com.rhythmphysics.app.ui.Ui
import com.rhythmphysics.core.session.SceneConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single Activity. Screens are plain view controllers ([HomeScreen], [CreatorScreen]) swapped
 * inside one root — one app, one session, one clock, no per-mechanic activities.
 */
class MainActivity : Activity() {
    private companion object { const val TAG = "RhythmPhysics" }

    val app: RhythmApp get() = application as RhythmApp
    val scope: CoroutineScope = MainScope()

    private lateinit var root: FrameLayout
    private var home: HomeScreen? = null
    private var creator: CreatorScreen? = null
    /** Current screens (read by device-less tests). */
    val homeScreen: HomeScreen? get() = home
    val creatorScreen: CreatorScreen? get() = creator
    /** "demo", "sandbox" or a content URI string. */
    private var currentSource: String? = null
    private var loadJob: Job? = null
    private var insets = IntArray(4)
    private val pending = HashMap<Int, (Uri) -> Unit>()
    private var nextRequest = 100
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        root.setOnApplyWindowInsetsListener { _, wi ->
            insets = if (Build.VERSION.SDK_INT >= 30) {
                val i = wi.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                intArrayOf(i.left, i.top, i.right, i.bottom)
            } else @Suppress("DEPRECATION") intArrayOf(wi.systemWindowInsetLeft, wi.systemWindowInsetTop, wi.systemWindowInsetRight, wi.systemWindowInsetBottom)
            home?.setInsets(insets[0], insets[1], insets[2], insets[3])
            creator?.setInsets(insets[0], insets[1], insets[2], insets[3])
            wi
        }
        setContentView(root)

        val restored = savedInstanceState?.getString("source")
        when {
            restored != null -> {
                val scene = savedInstanceState.getString("scene")?.let { runCatching { json.decodeFromString(SceneConfig.serializer(), it) }.getOrNull() }
                showHome()
                openSource(restored, scene, savedInstanceState.getDouble("pos", 0.0), fromPicker = false)
            }
            !handleIntent(intent) -> showHome()
        }
    }

    private fun setupWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else @Suppress("DEPRECATION") {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** "Open with" (VIEW) and share-sheet (SEND) entry points. Returns true when a file is being opened. */
    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            else -> null
        }
        if (uri == null) return false
        if (home == null && creator == null) showHome()
        openUri(uri, fromPicker = false)
        return true
    }

    // ---- navigation -------------------------------------------------------------------------------
    private fun showHome() {
        creator?.release(); creator = null
        currentSource = null
        setImmersiveBars(false)
        val h = home ?: HomeScreen(this).also { home = it }
        h.refresh()
        root.removeAllViews()
        root.addView(h.root, Ui.frameMatch())
        h.setInsets(insets[0], insets[1], insets[2], insets[3])
        root.requestApplyInsets()
    }

    fun goHome() = showHome()

    private fun showCreator(loaded: LoadedSession?, source: String, scene: SceneConfig, pos: Double, recipePresets: Map<String, com.rhythmphysics.core.preset.Preset> = emptyMap()) {
        creator?.release()
        val cs = CreatorScreen(this, loaded, scene, pos, recipePresets)
        creator = cs
        currentSource = source
        root.removeAllViews()
        root.addView(cs.root, Ui.frameMatch())
        cs.setInsets(insets[0], insets[1], insets[2], insets[3])
        cs.onHostResume()
        root.requestApplyInsets()
    }

    fun openDemo() = openSource("demo", null, 0.0, fromPicker = false)
    fun openSandbox() = openSource("sandbox", null, 0.0, fromPicker = false)
    fun openUri(uri: Uri, fromPicker: Boolean) = openSource(uri.toString(), null, 0.0, fromPicker)

    /** Re-opens the current media (e.g. after a SoundFont change) keeping scene and position. */
    fun reloadCurrent() {
        val cs = creator ?: return
        val src = currentSource ?: return
        openSource(src, cs.scene, cs.position(), fromPicker = false, overrides = HashMap(cs.overrides))
    }

    private fun openSource(source: String, scene: SceneConfig?, pos: Double, fromPicker: Boolean, overrides: Map<String, com.rhythmphysics.core.preset.Preset> = emptyMap()) {
        val sceneToUse = scene ?: app.settings.lastScene
        if (source == "sandbox") { showCreator(null, source, sceneToUse, pos); return }
        loadJob?.cancel()
        val cancelled = AtomicBoolean(false)
        val progress = showProgress("Loading", cancellable = true) { cancelled.set(true); loadJob?.cancel() }
        loadJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report: (String, Double) -> Unit = { msg, p -> runOnUiThread { progress.update(msg, p) } }
                    if (source == "demo") app.loader.loadDemo(report) { cancelled.get() }
                    else {
                        val uri = Uri.parse(source)
                        val persisted = if (fromPicker) app.loader.persistPermission(uri) else false
                        val ls = app.loader.load(uri, report) { cancelled.get() }
                        if (fromPicker && persisted) app.recents.add(RecentItem(source, ls.session.title, ls.kind.label, System.currentTimeMillis()))
                        else if (!fromPicker && app.recents.list().any { it.uri == source }) app.recents.add(RecentItem(source, ls.session.title, ls.kind.label, System.currentTimeMillis()))
                        ls
                    }
                }
            }
            progress.dismiss()
            result.onSuccess { ls -> showCreator(ls, source, sceneToUse, pos, overrides) }
                .onFailure { e ->
                    if (cancelled.get()) return@onFailure
                    android.util.Log.w(TAG, "Loading $source failed", e)
                    val msg = when {
                        source == "demo" && e is java.io.FileNotFoundException -> "The bundled SoundFont is missing from this build (${e.message})."
                        else -> loadErrorMessage(e)
                    }
                    if (e is SecurityException || e is java.io.FileNotFoundException) { app.recents.remove(source); home?.refresh() }
                    Ui.dialog(this@MainActivity, "Could not load").setMessage(msg).setPositiveButton("OK", null).show()
                }
        }
    }

    private fun loadErrorMessage(e: Throwable): String = when (e) {
        is SecurityException -> "Permission to read this file was lost. Open it again from the file picker."
        is java.io.FileNotFoundException -> "The file is no longer available."
        is IOException -> e.message ?: "Could not read the file."
        is OutOfMemoryError -> "This file is too large for the available memory."
        else -> "Could not open this file (${e.javaClass.simpleName}: ${e.message})."
    }

    // ---- Storage Access Framework ---------------------------------------------------------------
    fun pickDocument(mimeTypes: Array<String>, onPicked: (Uri) -> Unit) {
        val req = nextRequest++
        pending[req] = onPicked
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(i, req) } catch (e: Exception) { pending.remove(req); toast("No file picker available") }
    }

    fun createDocument(mime: String, name: String, onCreated: (Uri) -> Unit) {
        val req = nextRequest++
        pending[req] = onCreated
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = mime; putExtra(Intent.EXTRA_TITLE, name)
        }
        try { startActivityForResult(i, req) } catch (e: Exception) { pending.remove(req); toast("No document provider available") }
    }

    @Deprecated("Framework Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val cb = pending.remove(requestCode) ?: return
        if (resultCode == RESULT_OK) data?.data?.let(cb)
    }

    /** Reads a small text document (presets, replays); refuses anything over 4 MB. */
    fun readText(uri: Uri): String = contentResolver.openInputStream(uri)?.use { input ->
        val bytes = input.readBytes()
        if (bytes.size > 4 * 1024 * 1024) throw IOException("File too large")
        String(bytes, Charsets.UTF_8)
    } ?: throw IOException("Cannot open file")

    // ---- UI helpers --------------------------------------------------------------------------------
    fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    fun setImmersiveBars(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (on) { c.hide(WindowInsets.Type.systemBars()); c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
            else c.show(WindowInsets.Type.systemBars())
        } else @Suppress("DEPRECATION") {
            val base = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            window.decorView.systemUiVisibility = if (on) base or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION else base
        }
    }

    inner class ProgressHandle(private val overlay: View, private val title: android.widget.TextView, private val msg: android.widget.TextView, private val bar: android.widget.ProgressBar) {
        fun update(message: String, fraction: Double) {
            msg.text = message
            bar.isIndeterminate = fraction.isNaN()
            if (!fraction.isNaN()) bar.progress = (fraction * 1000).toInt().coerceIn(0, 1000)
        }
        fun dismiss() { root.removeView(overlay) }
        @Suppress("unused") fun setTitle(t: String) { title.text = t }
    }

    /** Modal progress card over the current screen (touches behind it are blocked). */
    fun showProgress(title: String, cancellable: Boolean, onCancel: (() -> Unit)? = null): ProgressHandle {
        val c = this
        val overlay = FrameLayout(c).apply { setBackgroundColor(0xB0000000.toInt()); isClickable = true; isFocusable = true }
        val card = Ui.column(c).apply {
            background = Ui.rounded(Ui.CARD, Ui.dp(c, 20).toFloat())
            setPadding(Ui.dp(c, 22), Ui.dp(c, 20), Ui.dp(c, 22), Ui.dp(c, 18))
        }
        val t = Ui.text(c, title, 18f, bold = true)
        val m = Ui.text(c, "", 14f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 12)) }
        val bar = Ui.horizontalProgress(c).apply { isIndeterminate = true }
        card.addView(t); card.addView(m); card.addView(bar, Ui.matchWrap())
        val handle = ProgressHandle(overlay, t, m, bar)
        if (cancellable) {
            card.addView(Ui.button(c, "Cancel") { onCancel?.invoke(); handle.dismiss() }, Ui.matchWrap().apply { topMargin = Ui.dp(c, 16) })
        }
        overlay.addView(card, FrameLayout.LayoutParams(minOf(Ui.dp(c, 360), resources.displayMetrics.widthPixels - Ui.dp(c, 48)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(overlay, Ui.frameMatch())
        return handle
    }

    // ---- lifecycle ---------------------------------------------------------------------------------
    override fun onResume() { super.onResume(); creator?.onHostResume() }

    override fun onPause() { creator?.onHostPause(); super.onPause() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        creator?.relayout()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        val cs = creator ?: return
        val src = currentSource ?: return
        out.putString("source", src)
        out.putString("scene", json.encodeToString(SceneConfig.serializer(), cs.scene))
        out.putDouble("pos", cs.position())
    }

    @Deprecated("Framework back handling (predictive back is not opted in)")
    override fun onBackPressed() {
        val cs = creator
        when {
            cs != null && cs.onBack() -> {}
            cs != null -> showHome()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_RUNNING_LOW && creator == null) app.soundFonts.release()
    }

    override fun onDestroy() {
        creator?.release(); creator = null
        scope.cancel()
        super.onDestroy()
    }
}
