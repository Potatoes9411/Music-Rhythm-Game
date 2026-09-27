package com.rhythmphysics.app.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Owns the song clock and Android audio policy: audio focus (pause on loss, resume after transient
 * loss, duck on "can duck"), headphone unplug ("becoming noisy") and lifecycle pauses.
 * The app never keeps playing after it loses focus.
 */
class PlaybackController(private val context: Context, val clock: AudioClock) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var noisyRegistered = false
    var muted = false
        set(v) { field = v; (clock as? AudioEngine)?.volume = if (v) 0f else 1f }
    /** Invoked on the main thread whenever play/pause state changes for any reason. */
    var onStateChanged: (() -> Unit)? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocusGain = false; pause(); abandonFocus() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { val was = clock.isPlaying; pause(); resumeOnFocusGain = was }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> (clock as? AudioEngine)?.volume = if (muted) 0f else 0.3f
                AudioManager.AUDIOFOCUS_GAIN -> {
                    (clock as? AudioEngine)?.volume = if (muted) 0f else 1f
                    if (resumeOnFocusGain) { resumeOnFocusGain = false; play() }
                }
            }
        }
    }

    val isPlaying get() = clock.isPlaying

    fun play() {
        if (clock is AudioEngine) {
            if (!requestFocus()) return
            if (!noisyRegistered) {
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
                else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                noisyRegistered = true
            }
            clock.play()
        } else (clock as? FreeClock)?.play()
        onStateChanged?.invoke()
    }

    fun pause() {
        when (clock) { is AudioEngine -> clock.pause(); is FreeClock -> clock.pause() }
        unregisterNoisy()
        onStateChanged?.invoke()
    }

    fun seek(sec: Double) {
        when (clock) { is AudioEngine -> clock.seek(sec); is FreeClock -> clock.seek(sec) }
        onStateChanged?.invoke()
    }

    private fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(focusListener, main)
            .setWillPauseWhenDucked(false)
            .build().also { focusRequest = it }
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() { focusRequest?.let { am.abandonAudioFocusRequest(it) } }

    private fun unregisterNoisy() {
        if (noisyRegistered) { try { context.unregisterReceiver(noisyReceiver) } catch (_: IllegalArgumentException) {}; noisyRegistered = false }
    }

    fun release() {
        pause()
        abandonFocus()
        (clock as? AudioEngine)?.release()
    }
}
