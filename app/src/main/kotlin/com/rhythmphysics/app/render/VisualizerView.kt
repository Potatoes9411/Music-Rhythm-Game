package com.rhythmphysics.app.render

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.rhythmphysics.app.audio.AudioClock
import com.rhythmphysics.core.engine.RhythmEngine
import com.rhythmphysics.core.render.DrawList
import com.rhythmphysics.core.render.RenderSettings

/**
 * Live visualization surface. A dedicated render thread runs Choreographer frame callbacks:
 *
 *   audio clock -> engine.update(t) (fixed 120 Hz steps) -> engine.render -> hardware Canvas
 *
 * t is the audio position *at the moment this frame will be on screen*: the audio clock reports
 * what is audible now (AudioTrack timestamps), and a frame drawn now is presented one or more
 * vsyncs later, so the frame's expected presentation time is added (exact FrameTimeline on
 * API 33+, estimated from the vsync period before). A user offset corrects for output devices
 * that under-report latency (some Bluetooth headsets).
 *
 * All engine access happens on the render thread; the UI posts commands via [post].
 * Rendering at 60/90/120 Hz never changes the simulation (steps are fixed and clock-driven).
 */
class VisualizerView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private val thread = HandlerThread("rp-render").also { it.start() }
    private val handler = Handler(thread.looper)
    private var choreographer: Choreographer? = null
    private val renderer = CanvasRenderer()
    private val drawList = DrawList()
    @Volatile private var surfaceReady = false
    @Volatile private var active = false
    private var surfaceW = 1; private var surfaceH = 1

    // Render-thread state
    private var engine: RhythmEngine? = null
    private var clock: AudioClock? = null
    var renderSettings = RenderSettings()
    var onFrameStats: ((fps: Double, frameMs: Double, steps: Int) -> Unit)? = null
    /** Tap in creator-frame coordinates (sandbox input), delivered on the render thread. */
    var onTap: ((RhythmEngine, Float, Float) -> Unit)? = null
    var tapEnabled = false
    /** Tap when sandbox input is off (e.g. leave clean view). Main thread. */
    var onPlainTap: (() -> Unit)? = null

    private var lastFrameNanos = 0L
    /** User A/V offset: positive shows visuals later (ms). */
    @Volatile var avOffsetMs = 0
    /** Last presentation lead applied (ms), for the debug overlay. */
    @Volatile var presentLeadMs = 0.0; private set
    private var vsyncNanos = 16_666_667L
    /** Render-buffer scale (quality/thermal lever); the display hardware scales it to the view. */
    var resolutionScale = 1f
        private set
    /** Current surface buffer size (debug overlay). */
    val surfaceSize: String get() = "${surfaceW}x${surfaceH}"

    /** Main thread. Shrinks the surface buffer below the view size (1 = native resolution). */
    fun setResolutionScale(scale: Float) {
        val sc = scale.coerceIn(0.4f, 1f)
        if (sc == resolutionScale) return
        resolutionScale = sc
        applyFixedSize()
    }

    private fun applyFixedSize() {
        if (width <= 0 || height <= 0) return
        if (resolutionScale >= 0.999f) holder.setSizeFromLayout()
        else holder.setFixedSize((width * resolutionScale).toInt().coerceAtLeast(1), (height * resolutionScale).toInt().coerceAtLeast(1))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // After layout (the view's own post, not the render-thread [post]).
        if (resolutionScale < 0.999f) super.post(Runnable { applyFixedSize() })
    }

    init {
        holder.addCallback(this)
        handler.post { choreographer = Choreographer.getInstance() }
    }

    fun post(r: () -> Unit) { handler.post { r() } }

    /** Swaps engine/clock (render thread). The previous engine is disposed. */
    fun attach(engine: RhythmEngine?, clock: AudioClock?) = post {
        this.engine?.let { if (it !== engine) { it.dispose(); renderer.releaseCaches() } }
        this.engine = engine
        this.clock = clock
    }

    fun withEngine(block: (RhythmEngine) -> Unit) = post { engine?.let(block) }

    /** Like [withEngine] but always calls back (null when no engine is attached yet). */
    fun query(block: (RhythmEngine?) -> Unit) = post { block(engine) }

    fun resume() { active = true; schedule() }
    fun pauseRendering() { active = false }

    private fun schedule() = post { if (active && surfaceReady) postNext() }

    private fun postNext() {
        val ch = choreographer ?: return
        val v = vsyncCallback
        if (Build.VERSION.SDK_INT >= 33 && v != null) VsyncCallbacks.post(ch, v) else ch.postFrameCallback(frameCallback)
    }

    private fun cancelNext() {
        val ch = choreographer ?: return
        val v = vsyncCallback
        if (Build.VERSION.SDK_INT >= 33 && v != null) VsyncCallbacks.remove(ch, v) else ch.removeFrameCallback(frameCallback)
    }

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        // Pre-33: HWUI + SurfaceFlinger typically present two vsyncs after the frame's vsync.
        onFrame(frameTimeNanos, frameTimeNanos + 2 * vsyncNanos)
    }

    private val vsyncCallback: Any? = if (Build.VERSION.SDK_INT >= 33) VsyncCallbacks.create { frameTime, present -> onFrame(frameTime, present) } else null

    private fun onFrame(frameTimeNanos: Long, expectedPresentNanos: Long) {
        if (!active || !surfaceReady) return
        val t0 = SystemClock.elapsedRealtimeNanos()
        val lead = (expectedPresentNanos - System.nanoTime()).coerceIn(0L, 100_000_000L)
        renderFrame(lead / 1e9)
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        engine?.frameStats?.addFrame(if (lastFrameNanos > 0) (frameTimeNanos - lastFrameNanos) / 1e6 else 16.7)
        lastFrameNanos = frameTimeNanos
        engine?.let { onFrameStats?.invoke(it.frameStats.fps, ms, it.lastSteps) }
        postNext()
    }

    private fun renderFrame(presentLeadSec: Double = 0.0) {
        val e = engine
        val canvas = try { holder.surface.lockHardwareCanvas() } catch (ex: Exception) { return }
        try {
            if (e == null) { canvas.drawColor(0xFF000000.toInt()); return }
            val c = clock
            val t = if (c == null) e.renderTime
                else if (c.isPlaying) (c.positionSec() + presentLeadSec - avOffsetMs / 1000.0).coerceAtLeast(0.0)
                else c.positionSec()
            presentLeadMs = if (c?.isPlaying == true) presentLeadSec * 1000 else 0.0
            e.update(t)
            e.render(drawList, renderSettings)
            val box = Letterbox.fit(e.frame.w, e.frame.h, surfaceW.toFloat(), surfaceH.toFloat())
            renderer.draw(canvas, drawList, box)
        } finally {
            holder.surface.unlockCanvasAndPost(canvas)
        }
    }

    /** Renders one frame immediately (paused state, after seeks or setting changes). */
    fun redraw() = post { if (surfaceReady && !active) renderFrame() }

    override fun surfaceCreated(h: SurfaceHolder) {
        display?.refreshRate?.let { if (it > 1f) vsyncNanos = (1e9 / it).toLong() }
        if (Build.VERSION.SDK_INT >= 30) {
            val rate = display?.refreshRate ?: 60f
            try { h.surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) } catch (_: Exception) {}
        }
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        post {
            surfaceW = width; surfaceH = height; surfaceReady = true
            if (active) postNext() else renderFrame()
        }
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        surfaceReady = false
        // Block until the render thread has stopped touching the surface.
        val lock = Object()
        synchronized(lock) {
            handler.post { cancelNext(); synchronized(lock) { lock.notifyAll() } }
            lock.wait(500)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!tapEnabled) {
            val plain = onPlainTap ?: return super.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP) { performClick(); plain() }
            return true
        }
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) return true
        // View pixels -> surface buffer pixels (the buffer may be smaller than the view).
        val vw = width.coerceAtLeast(1).toFloat(); val vh = height.coerceAtLeast(1).toFloat()
        val fx = ev.x / vw; val fy = ev.y / vh
        post {
            val e = engine ?: return@post
            val box = Letterbox.fit(e.frame.w, e.frame.h, surfaceW.toFloat(), surfaceH.toFloat())
            onTap?.invoke(e, box.toFrameX(fx * surfaceW), box.toFrameY(fy * surfaceH))
        }
        return true
    }

    fun release() {
        active = false
        post { engine?.dispose(); engine = null }
        thread.quitSafely()
    }
}
