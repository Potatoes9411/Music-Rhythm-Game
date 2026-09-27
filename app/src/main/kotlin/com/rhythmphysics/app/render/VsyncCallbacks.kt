package com.rhythmphysics.app.render

import android.view.Choreographer

/**
 * API 33+ only: Choreographer vsync callbacks with FrameTimeline expected-presentation times.
 * Kept in its own class so no pre-33 code path references the API-33 types (see :app:apiCheck).
 */
internal object VsyncCallbacks {
    fun create(onFrame: (frameTimeNanos: Long, expectedPresentNanos: Long) -> Unit): Any =
        Choreographer.VsyncCallback { data -> onFrame(data.frameTimeNanos, data.preferredFrameTimeline.expectedPresentationTimeNanos) }

    fun post(ch: Choreographer, cb: Any) = ch.postVsyncCallback(cb as Choreographer.VsyncCallback)

    fun remove(ch: Choreographer, cb: Any) = ch.removeVsyncCallback(cb as Choreographer.VsyncCallback)
}
