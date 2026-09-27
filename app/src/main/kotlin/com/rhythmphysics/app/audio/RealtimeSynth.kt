package com.rhythmphysics.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.rhythmphysics.core.synth.Sf2Synth
import com.rhythmphysics.core.synth.SoundFont
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Low-latency SoundFont voice output for the optional collision layer and generative (Circle)
 * notes. Notes are queued from the render/simulation thread and rendered on a dedicated audio
 * thread (the synth itself is single-threaded).
 */
class RealtimeSynth(sf: SoundFont, private val sampleRate: Int = 44100) {
    private data class Cmd(val on: Boolean, val ch: Int, val key: Int, val vel: Int, val program: Int, val offAtNanos: Long)

    private val synth = Sf2Synth(sf, sampleRate, maxVoices = 48).also { it.reverbLevel = 0.25 }
    private val queue = ConcurrentLinkedQueue<Cmd>()
    private val pendingOffs = ArrayList<Cmd>()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    @Volatile var volume = 0.8f
        set(v) { field = v; track?.setVolume(v) }

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(minBuf, 256 * 8 * 2))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.setVolume(volume)
        track = t
        running = true
        thread = Thread({ loop(t) }, "rp-synth").also { it.start() }
        t.play()
    }

    /** Plays a note now for [durationSec]. Safe to call from any thread. */
    fun note(key: Int, velocity: Float, program: Int, durationSec: Double = 0.45, channel: Int = 0) {
        if (!running) return
        queue += Cmd(true, channel, key.coerceIn(0, 127), (velocity * 127).toInt().coerceIn(1, 127), program, System.nanoTime() + (durationSec * 1e9).toLong())
    }

    private fun loop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val n = 256
        val l = FloatArray(n); val r = FloatArray(n); val inter = FloatArray(n * 2)
        val programs = IntArray(16) { -1 }
        while (running) {
            while (true) {
                val c = queue.poll() ?: break
                if (programs[c.ch] != c.program) { synth.programChange(c.ch, c.program); programs[c.ch] = c.program }
                synth.noteOn(c.ch, c.key, c.vel)
                pendingOffs += c
            }
            val now = System.nanoTime()
            val it = pendingOffs.iterator()
            while (it.hasNext()) { val c = it.next(); if (c.offAtNanos <= now) { synth.noteOff(c.ch, c.key); it.remove() } }
            synth.render(l, r, 0, n)
            for (i in 0 until n) { inter[2 * i] = l[i]; inter[2 * i + 1] = r[i] }
            t.write(inter, 0, n * 2, AudioTrack.WRITE_BLOCKING)
        }
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
        track?.let { try { it.stop() } catch (_: IllegalStateException) {}; it.release() }
        track = null
        queue.clear()
    }
}
