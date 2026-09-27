package com.rhythmphysics.app.audio

import com.rhythmphysics.core.util.clearCompat
import com.rhythmphysics.core.util.setLimit
import com.rhythmphysics.core.util.setPosition
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.rhythmphysics.core.audio.AudioAnalysis
import com.rhythmphysics.core.audio.AudioAnalyzer
import com.rhythmphysics.core.audio.WavWriter
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

class DecodeException(msg: String) : IOException(msg)

/**
 * Decodes any platform-supported audio (WAV, MP3, OGG, M4A/AAC, FLAC...) with MediaExtractor +
 * MediaCodec, streaming: PCM goes straight to a 16-bit stereo WAV cache (for playback and export)
 * and a mono copy goes through [AudioAnalyzer] — the whole song is never held in memory.
 */
class AudioDecoder(private val context: Context) {

    class Result(val wav: File, val analysis: AudioAnalysis?, val sampleRate: Int, val durationSec: Double)

    fun decode(uri: Uri, outWav: File, analyze: Boolean, progress: (Double) -> Unit, cancelled: () -> Boolean): Result {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var writer: WavWriter? = null
        val tmp = File(outWav.path + ".part")
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; break }
            }
            if (track < 0) throw DecodeException("No audio track found")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            if (mime.contains("midi")) throw DecodeException("MIDI must be loaded as MIDI, not decoded as audio")
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else -1L
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(inFormat, null, null, 0)
            c.start()

            var sampleRate = if (inFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (inFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var floatPcm = false
            var analyzer: AudioAnalyzer? = null
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var stereo = ShortArray(0)
            var mono = FloatArray(0)
            var framesOut = 0L

            fun ensureWriter() {
                if (writer == null) {
                    writer = WavWriter(tmp, sampleRate, 2)
                    if (analyze) analyzer = AudioAnalyzer(sampleRate)
                }
            }

            while (!outputDone) {
                if (cancelled()) throw DecodeException("Cancelled")
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        if (writer == null) {
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            ensureWriter()
                            val out = c.getOutputBuffer(outIdx)!!
                            out.setPosition(info.offset); out.setLimit(info.offset + info.size)
                            out.order(ByteOrder.LITTLE_ENDIAN)
                            val bytesPerSample = if (floatPcm) 4 else 2
                            val frames = info.size / (bytesPerSample * channels)
                            if (stereo.size < frames * 2) stereo = ShortArray(frames * 2)
                            if (mono.size < frames) mono = FloatArray(frames)
                            for (fr in 0 until frames) {
                                var l = 0f; var r = 0f
                                for (ch in 0 until channels) {
                                    val v = if (floatPcm) out.getFloat() else out.getShort() / 32768f
                                    when {
                                        channels == 1 -> { l = v; r = v }
                                        ch == 0 -> l += v
                                        ch == 1 -> r += v
                                        else -> { l += v * 0.5f; r += v * 0.5f } // fold surround channels
                                    }
                                }
                                if (channels > 2) { l /= (1 + (channels - 2) * 0.5f); r /= (1 + (channels - 2) * 0.5f) }
                                stereo[2 * fr] = (l.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                                stereo[2 * fr + 1] = (r.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                                mono[fr] = (l + r) * 0.5f
                            }
                            writer!!.writeShorts(stereo, frames * 2)
                            analyzer?.feed(mono, frames)
                            framesOut += frames
                            if (durationUs > 0) progress((info.presentationTimeUs.toDouble() / durationUs).coerceIn(0.0, 1.0))
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (writer == null || framesOut == 0L) throw DecodeException("The file contains no decodable audio")
            writer!!.close(); writer = null
            val analysis = analyzer?.finish()
            if (outWav.exists()) outWav.delete()
            if (!tmp.renameTo(outWav)) throw DecodeException("Could not write the audio cache")
            progress(1.0)
            return Result(outWav, analysis, sampleRate, framesOut.toDouble() / sampleRate)
        } catch (e: IllegalStateException) {
            throw DecodeException("This audio format is not supported on this device (${e.message})")
        } catch (e: IllegalArgumentException) {
            throw DecodeException("Unsupported or corrupt audio file")
        } finally {
            try { writer?.close() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            extractor.release()
            if (tmp.exists() && !outWav.exists()) tmp.delete()
        }
    }
}
