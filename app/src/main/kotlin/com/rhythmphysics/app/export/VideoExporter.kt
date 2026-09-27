package com.rhythmphysics.app.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import com.rhythmphysics.app.audio.CollisionLayer
import com.rhythmphysics.app.render.CanvasRenderer
import com.rhythmphysics.app.render.Letterbox
import com.rhythmphysics.core.audio.WavReader
import com.rhythmphysics.core.engine.RhythmEngine
import com.rhythmphysics.core.mechanic.EngineSink
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.preset.Preset
import com.rhythmphysics.core.render.DrawList
import com.rhythmphysics.core.render.RenderSettings
import com.rhythmphysics.core.session.RhythmSession
import com.rhythmphysics.core.session.SandboxInput
import com.rhythmphysics.core.session.SceneConfig
import com.rhythmphysics.core.synth.Sf2Synth
import com.rhythmphysics.core.synth.SoundFont
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExportCancelled : IOException("Export cancelled")

/**
 * Deterministic offline export: every video frame is rendered at an exact song time (i / fps) from
 * a fresh engine built from the same recipe, drawn with the same CanvasRenderer as the preview
 * into the H.264 encoder's input surface, and muxed with the original song audio (plus the optional
 * collision/generative note layer rendered offline with the SoundFont synth).
 */
class VideoExporter(
    private val session: RhythmSession,
    private val config: SceneConfig,
    private val presetResolver: (String) -> Preset?,
    private val inputs: List<SandboxInput>,
    private val songPcm: File?,
    private val soundFont: (() -> SoundFont)?,
    /** Adds one SoundFont note per physical contact (AudioMode.ORIGINAL_AND_COLLISION_LAYER). */
    private val impactLayer: Boolean,
    /** Includes notes requested by generative mechanics (Circle generative/sandbox). */
    private val generativeNotes: Boolean,
    private val muteSong: Boolean,
    private val settings: ExportSettings,
    private val renderSettings: RenderSettings,
) {
    private class Encoded(val data: ByteArray, val ptsUs: Long, val flags: Int)
    private class NoteEv(val time: Double, val note: Int, val vel: Float, val program: Int, val dur: Double)

    fun export(out: FileDescriptor, progress: (String, Double) -> Unit, cancelled: () -> Boolean) {
        val start = settings.startSec.coerceAtLeast(0.0)
        val end = (if (settings.endSec > 0) settings.endSec else session.durationSec).coerceAtMost(session.durationSec)
        if (end <= start) throw IOException("Nothing to export")

        // Pass 1: collect note events (deterministic simulation, no rendering).
        val notes = ArrayList<NoteEv>()
        if ((impactLayer || generativeNotes) && soundFont != null) {
            progress("Simulating note layer", 0.0)
            val sink = object : EngineSink {
                override fun onImpact(mechanic: MechanicType, timeSec: Double, strength: Float, eventId: Long, note: Int?) {
                    if (impactLayer) notes += NoteEv(timeSec, CollisionLayer.noteFor(eventId, note), CollisionLayer.velocityFor(strength), CollisionLayer.PROGRAM, CollisionLayer.IMPACT_NOTE_SEC)
                }
                override fun onNote(timeSec: Double, note: Int, velocity: Float, program: Int) {
                    if (generativeNotes) notes += NoteEv(timeSec, note, velocity, program, CollisionLayer.GENERATIVE_NOTE_SEC)
                }
            }
            val sim = RhythmEngine(session, config, sink, presetResolver = presetResolver)
            sim.loadInputs(inputs)
            var t = 0.0
            while (t < end) { if (cancelled()) throw ExportCancelled(); sim.update(t); t += 1.0 / 60 }
            sim.update(end); sim.dispose()
            notes.sortBy { it.time }
        }

        // Pass 2: audio -> AAC (kept in memory: ~1.5 MB per minute).
        val audio = encodeAudio(start, end, notes, progress, cancelled)

        // Pass 3: video frames -> H.264 via input surface, muxed with audio.
        val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, settings.width, settings.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrate())
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) // output order == input order (PTS rewrite)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var surface: android.view.Surface? = null
        var muxerStarted = false
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = enc.createInputSurface()
            enc.start()
            val engine = RhythmEngine(session, config, EngineSink.None, presetResolver = presetResolver)
            engine.loadInputs(inputs)
            val dl = DrawList()
            val renderer = CanvasRenderer()
            val rs = renderSettings.copy(showDebug = false, cleanOutput = true)
            val frames = ((end - start) * settings.fps).toLong()
            val info = MediaCodec.BufferInfo()
            var videoTrack = -1; var audioTrack = -1
            var written = 0L
            var audioIdx = 0

            fun writeAudioUpTo(ptsUs: Long) {
                if (audioTrack < 0) return
                val bi = MediaCodec.BufferInfo()
                while (audioIdx < audio.second.size && audio.second[audioIdx].ptsUs <= ptsUs) {
                    val s = audio.second[audioIdx++]
                    bi.set(0, s.data.size, s.ptsUs, s.flags)
                    muxer.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), bi)
                }
            }

            fun drain(endOfStream: Boolean) {
                if (endOfStream) enc.signalEndOfInputStream()
                var idle = 0
                while (true) {
                    val idx = enc.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!endOfStream || ++idle > 300) return }
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            videoTrack = muxer.addTrack(enc.outputFormat)
                            audio.first?.let { audioTrack = muxer.addTrack(it) }
                            muxer.start(); muxerStarted = true
                        }
                        idx >= 0 -> {
                            val buf = enc.getOutputBuffer(idx)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxerStarted) {
                                // Timestamps come from the frame index, not wall-clock encode time.
                                info.presentationTimeUs = settings.ptsForFrame(written++)
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                muxer.writeSampleData(videoTrack, buf, info)
                                writeAudioUpTo(info.presentationTimeUs)
                            }
                            enc.releaseOutputBuffer(idx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            val box = Letterbox.fit(engine.frame.w, engine.frame.h, settings.width.toFloat(), settings.height.toFloat())
            for (i in 0 until frames) {
                if (cancelled()) throw ExportCancelled()
                val t = start + i.toDouble() / settings.fps
                engine.update(t)
                engine.render(dl, rs, t)
                val canvas = surface.lockHardwareCanvas()
                try { renderer.draw(canvas, dl, box) } finally { surface.unlockCanvasAndPost(canvas) }
                drain(false)
                if (i % 15 == 0L) progress("Rendering video", i.toDouble() / frames)
            }
            drain(true)
            writeAudioUpTo(Long.MAX_VALUE)
            engine.dispose()
            progress("Finishing", 1.0)
        } finally {
            try { enc.stop() } catch (_: Exception) {}
            enc.release()
            surface?.release()
            try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
            muxer.release()
        }
    }

    /** Song PCM (+ offline-rendered note layer) -> AAC samples. Returns (format, samples). */
    private fun encodeAudio(start: Double, end: Double, notes: List<NoteEv>, progress: (String, Double) -> Unit, cancelled: () -> Boolean): Pair<MediaFormat?, List<Encoded>> {
        val wavInfo = songPcm?.let { WavReader.readInfo(it) }
        val sr = wavInfo?.sampleRate ?: 44100
        if (wavInfo == null && notes.isEmpty()) return null to emptyList()
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val outSamples = ArrayList<Encoded>()
        var outFormat: MediaFormat? = null
        val synth = if (notes.isNotEmpty()) soundFont?.let { Sf2Synth(it(), sr).also { s -> s.reverbLevel = 0.25 } } else null
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val totalFrames = ((end - start) * sr).toLong()
            val raf = songPcm?.let { RandomAccessFile(it, "r") }
            val frameBytes = wavInfo?.frameBytes ?: 4
            raf?.seek(wavInfo!!.dataOffset + (start * sr).toLong() * frameBytes)
            val block = 1024
            val raw = ByteArray(block * frameBytes)
            val sl = FloatArray(block); val sr2 = FloatArray(block)
            val pcmOut = ByteArray(block * 4)
            var fed = 0L
            var noteIdx = 0
            val offs = ArrayList<Pair<Long, Int>>() // (frame, key) note-offs
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            var outputDone = false
            while (!outputDone) {
                if (cancelled()) throw ExportCancelled()
                if (!inputDone) {
                    val idx = enc.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val n = minOf(block.toLong(), totalFrames - fed).toInt()
                        if (n <= 0) {
                            enc.queueInputBuffer(idx, 0, 0, fed * 1_000_000L / sr, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            // song
                            val songL = FloatArray(n); val songR = FloatArray(n)
                            if (raf != null && !muteSong) {
                                val got = raf.read(raw, 0, n * frameBytes).coerceAtLeast(0)
                                val bb = ByteBuffer.wrap(raw, 0, got).order(ByteOrder.LITTLE_ENDIAN)
                                val ch = wavInfo!!.channels
                                for (f in 0 until got / frameBytes) {
                                    val l = bb.getShort(f * frameBytes) / 32768f
                                    val r = if (ch > 1) bb.getShort(f * frameBytes + 2) / 32768f else l
                                    songL[f] = l; songR[f] = r
                                }
                            } else raf?.skipBytes(n * frameBytes)
                            // note layer
                            if (synth != null) {
                                // Sample-accurate: render up to each note-on/off, then apply it.
                                val absStart = (start * sr).toLong() + fed
                                val absEnd = absStart + n
                                var pos = 0
                                while (true) {
                                    val nextOn = if (noteIdx < notes.size) Math.round(notes[noteIdx].time * sr) else Long.MAX_VALUE
                                    var offI = -1
                                    for (i in offs.indices) if (offI < 0 || offs[i].first < offs[offI].first) offI = i
                                    val nextOff = if (offI >= 0) offs[offI].first else Long.MAX_VALUE
                                    val next = minOf(nextOn, nextOff)
                                    if (next >= absEnd) break
                                    val at = (next - absStart).coerceIn(pos.toLong(), n.toLong()).toInt()
                                    if (at > pos) { synth.render(sl, sr2, pos, at - pos); pos = at }
                                    if (nextOn <= nextOff) {
                                        val ne = notes[noteIdx++]
                                        if (ne.time >= start - 1e-9) {
                                            synth.programChange(0, ne.program)
                                            synth.noteOn(0, ne.note.coerceIn(0, 127), (ne.vel * 127).toInt().coerceIn(1, 127))
                                            offs += Math.round((ne.time + ne.dur) * sr) to ne.note.coerceIn(0, 127)
                                        }
                                    } else {
                                        synth.noteOff(0, offs[offI].second); offs.removeAt(offI)
                                    }
                                }
                                if (pos < n) synth.render(sl, sr2, pos, n - pos)
                            }
                            val bb = ByteBuffer.wrap(pcmOut, 0, n * 4).order(ByteOrder.LITTLE_ENDIAN)
                            for (f in 0 until n) {
                                val l = (songL[f] + (if (synth != null) sl[f] * 0.8f else 0f)).coerceIn(-1f, 1f)
                                val r = (songR[f] + (if (synth != null) sr2[f] * 0.8f else 0f)).coerceIn(-1f, 1f)
                                bb.putShort((l * 32767).toInt().toShort()); bb.putShort((r * 32767).toInt().toShort())
                            }
                            val ib = enc.getInputBuffer(idx)!!
                            ib.clear(); ib.put(pcmOut, 0, n * 4)
                            enc.queueInputBuffer(idx, 0, n * 4, fed * 1_000_000L / sr, 0)
                            fed += n
                            if (fed % (sr * 5L) < block) progress("Encoding audio", fed.toDouble() / totalFrames)
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = enc.outputFormat
                    o >= 0 -> {
                        val buf = enc.getOutputBuffer(o)!!
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val bytes = ByteArray(info.size)
                            buf.position(info.offset); buf.get(bytes)
                            outSamples += Encoded(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        }
                        enc.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            raf?.close()
        } finally {
            try { enc.stop() } catch (_: Exception) {}
            enc.release()
        }
        return outFormat to outSamples
    }

    companion object {
        /** Whether this device's H.264 encoder supports [w]x[h] at [fps]. */
        fun isSupported(w: Int, h: Int, fps: Int): Boolean {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }) continue
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities ?: continue
                if (caps.isSizeSupported(w, h) && caps.areSizeAndRateSupported(w, h, fps.toDouble())) return true
            }
            return false
        }
    }
}
