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

    private var lastFrameNanos = 0L

    init {
        holder.addCallback(this)
        handler.post { choreographer = Choreographer.getInstance() }
    }

    fun post(r: () -> Unit) { handler.post { r() } }

    /** Swaps engine/clock (render thread). The previous engine is disposed. */
    fun attach(engine: RhythmEngine?, clock: AudioClock?) = post {
        this.engine?.let { if (it !== engine) it.dispose() }
        this.engine = engine
        this.clock = clock
    }

    fun withEngine(block: (RhythmEngine) -> Unit) = post { engine?.let(block) }

    fun resume() { active = true; schedule() }
    fun pauseRendering() { active = false }

    private fun schedule() = post { if (active && surfaceReady) choreographer?.postFrameCallback(frameCallback) }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!active || !surfaceReady) return
            val t0 = SystemClock.elapsedRealtimeNanos()
            renderFrame()
            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            engine?.frameStats?.addFrame(if (lastFrameNanos > 0) (frameTimeNanos - lastFrameNanos) / 1e6 else 16.7)
            lastFrameNanos = frameTimeNanos
            engine?.let { onFrameStats?.invoke(it.frameStats.fps, ms, it.lastSteps) }
            choreographer?.postFrameCallback(this)
        }
    }

    private fun renderFrame() {
        val e = engine
        val canvas = try { holder.surface.lockHardwareCanvas() } catch (ex: Exception) { return }
        try {
            if (e == null) { canvas.drawColor(0xFF000000.toInt()); return }
            val t = clock?.positionSec() ?: e.renderTime
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
        if (Build.VERSION.SDK_INT >= 30) {
            val rate = display?.refreshRate ?: 60f
            try { h.surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) } catch (_: Exception) {}
        }
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        post {
            surfaceW = width; surfaceH = height; surfaceReady = true
            if (active) choreographer?.postFrameCallback(frameCallback) else renderFrame()
        }
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        surfaceReady = false
        // Block until the render thread has stopped touching the surface.
        val lock = Object()
        synchronized(lock) {
            handler.post { choreographer?.removeFrameCallback(frameCallback); synchronized(lock) { lock.notifyAll() } }
            lock.wait(500)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!tapEnabled || ev.actionMasked != MotionEvent.ACTION_DOWN) return super.onTouchEvent(ev)
        val x = ev.x; val y = ev.y
        post {
            val e = engine ?: return@post
            val box = Letterbox.fit(e.frame.w, e.frame.h, surfaceW.toFloat(), surfaceH.toFloat())
            onTap?.invoke(e, box.toFrameX(x), box.toFrameY(y))
        }
        return true
    }

    fun release() {
        active = false
        post { engine?.dispose(); engine = null }
        thread.quitSafely()
    }
}
