package com.rhythmphysics.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import com.rhythmphysics.core.audio.WavReader
import java.io.File
import java.io.RandomAccessFile

/** The authoritative song clock the simulation follows. */
interface AudioClock {
    val durationSec: Double
    val isPlaying: Boolean
    /** Current playback position in seconds (monotonic while playing). */
    fun positionSec(): Double
}

/**
 * Plays a 16-bit PCM WAV cache (decoded song or SoundFont-rendered MIDI) through AudioTrack in
 * streaming mode. The clock comes from AudioTrack.getTimestamp() (hardware presentation position),
 * smoothed by [ClockSmoother] — musical time is never accumulated from frame deltas.
 */
class AudioEngine(private val wav: File) : AudioClock {
    private val info = WavReader.readInfo(wav)
    val sampleRate = info.sampleRate
    private val channels = info.channels.coerceIn(1, 2)
    override val durationSec: Double = info.durationSec

    private val lock = Object()
    private var track: AudioTrack? = null
    private var writer: Thread? = null
    @Volatile private var running = false
    @Volatile override var isPlaying = false; private set
    @Volatile private var baseFrame = 0L          // file frame that AudioTrack frame 0 corresponds to
    @Volatile private var pausedPos = 0.0
    @Volatile var volume = 1f
        set(v) { field = v; track?.setVolume(v) }
    var onCompletion: (() -> Unit)? = null

    private val smoother = ClockSmoother()
    private val ts = AudioTimestamp()
    private var lastTsQuery = 0L

    override fun positionSec(): Double {
        if (!isPlaying) return pausedPos
        val now = System.nanoTime()
        val t = track ?: return pausedPos
        // Query the hardware timestamp at most every 40 ms and extrapolate in between.
        if (now - lastTsQuery > 40_000_000L) {
            lastTsQuery = now
            if (t.getTimestamp(ts)) {
                smoother.anchor((baseFrame + ts.framePosition).toDouble() / sampleRate, ts.nanoTime)
            } else {
                smoother.anchor((baseFrame + (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)).toDouble() / sampleRate, now)
            }
        }
        return smoother.position(now, true).coerceIn(0.0, durationSec)
    }

    fun play() = synchronized(lock) {
        if (isPlaying) return
        if (pausedPos >= durationSec - 0.01) pausedPos = 0.0
        startAt(pausedPos)
    }

    fun pause() = synchronized(lock) {
        if (!isPlaying) return
        pausedPos = positionSec()
        stopTrack()
        isPlaying = false
    }

    /** Seeks; playback resumes automatically if it was playing. */
    fun seek(sec: Double) = synchronized(lock) {
        val target = sec.coerceIn(0.0, durationSec)
        val wasPlaying = isPlaying
        stopTrack()
        isPlaying = false
        pausedPos = target
        if (wasPlaying) startAt(target)
    }

    private fun startAt(sec: Double) {
        val frame = (sec * sampleRate).toLong().coerceAtLeast(0)
        val chMask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(chMask).build())
            .setBufferSizeInBytes(maxOf(minBuf * 2, sampleRate * channels * 2 / 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.setVolume(volume)
        track = t
        baseFrame = frame
        smoother.reset(sec, System.nanoTime())
        lastTsQuery = 0
        running = true
        isPlaying = true
        val th = Thread({ writeLoop(t, frame) }, "rp-audio-writer")
        writer = th
        th.start()
        t.play()
    }

    private fun stopTrack() {
        running = false
        val t = track
        track = null
        try { t?.pause(); t?.flush() } catch (_: IllegalStateException) {}
        writer?.let { if (it !== Thread.currentThread()) it.join(500) }
        writer = null
        t?.release()
    }

    private fun writeLoop(t: AudioTrack, startFrame: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val frameBytes = info.frameBytes
        val buf = ByteArray(4096 * frameBytes)
        try {
            RandomAccessFile(wav, "r").use { raf ->
                raf.seek(info.dataOffset + startFrame * frameBytes)
                var remaining = info.dataBytes - startFrame * frameBytes
                while (running && remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    var off = 0
                    while (running && off < n) {
                        val w = t.write(buf, off, n - off)
                        if (w < 0) { running = false; break }
                        off += w
                    }
                    remaining -= n
                }
            }
            // Let the tail play out, then report completion.
            if (running) {
                while (running && positionSec() < durationSec - 0.02) SystemClock.sleep(20)
                var completed = false
                synchronized(lock) {
                    // Only the current writer may complete (a seek may have replaced this track).
                    if (running && track === t) { pausedPos = durationSec; isPlaying = false; completed = true }
                }
                if (completed) onCompletion?.invoke()
            }
        } catch (e: Exception) {
            running = false
        }
    }

    fun release() = synchronized(lock) { stopTrack(); isPlaying = false }
}

/** Free-running clock for sandbox mode (no song). */
class FreeClock(override val durationSec: Double = 3600.0) : AudioClock {
    private var startNanos = 0L
    private var pausedAt = 0.0
    @Volatile override var isPlaying = false; private set
    override fun positionSec(): Double = if (isPlaying) pausedAt + (System.nanoTime() - startNanos) / 1e9 else pausedAt
    fun play() { if (!isPlaying) { startNanos = System.nanoTime(); isPlaying = true } }
    fun pause() { if (isPlaying) { pausedAt = positionSec(); isPlaying = false } }
    fun seek(sec: Double) { val was = isPlaying; pausedAt = sec.coerceAtLeast(0.0); if (was) startNanos = System.nanoTime() }
}
