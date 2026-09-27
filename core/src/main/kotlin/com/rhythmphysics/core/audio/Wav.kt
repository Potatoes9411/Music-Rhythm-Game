package com.rhythmphysics.core.audio

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFormatException(msg: String) : Exception(msg)

/** Streaming 16-bit PCM WAV writer (header patched on close). */
class WavWriter(file: File, val sampleRate: Int, val channels: Int = 2) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private val buf = ByteBuffer.allocate(8192 * 4).order(ByteOrder.LITTLE_ENDIAN)

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    /** Writes frames from planar float buffers (clamped to [-1,1]). */
    fun write(left: FloatArray, right: FloatArray?, frames: Int) {
        var i = 0
        while (i < frames) {
            buf.clear()
            val n = minOf(frames - i, buf.capacity() / (2 * channels))
            for (k in 0 until n) {
                buf.putShort(toPcm(left[i + k]))
                if (channels == 2) buf.putShort(toPcm((right ?: left)[i + k]))
            }
            raf.write(buf.array(), 0, buf.position())
            dataBytes += buf.position()
            i += n
        }
    }

    fun writeShorts(interleaved: ShortArray, count: Int) {
        val b = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) b.putShort(interleaved[i])
        raf.write(b.array())
        dataBytes += count * 2
    }

    private fun toPcm(x: Float): Short = (x.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    override fun close() {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).toInt()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(channels.toShort())
        h.putInt(sampleRate); h.putInt(sampleRate * channels * 2); h.putShort((channels * 2).toShort()); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataBytes.toInt())
        raf.seek(0); raf.write(h.array())
        raf.close()
    }
}

/** Parsed WAV header. */
data class WavInfo(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val isFloat: Boolean, val dataOffset: Long, val dataBytes: Long) {
    val frameBytes get() = channels * bitsPerSample / 8
    val frames get() = dataBytes / frameBytes
    val durationSec get() = frames.toDouble() / sampleRate
}

/** WAV reader: PCM 8/16/24/32-bit, IEEE float 32/64, WAVE_FORMAT_EXTENSIBLE. */
object WavReader {
    fun isWav(header: ByteArray) = header.size >= 12 && String(header, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
        String(header, 8, 4, Charsets.ISO_8859_1) == "WAVE"

    fun readInfo(file: File): WavInfo = RandomAccessFile(file, "r").use { raf ->
        val head = ByteArray(12); raf.readFully(head)
        if (!isWav(head)) throw WavFormatException("Not a WAV file")
        var fmt: ByteBuffer? = null
        while (raf.filePointer + 8 <= raf.length()) {
            val ch = ByteArray(8); raf.readFully(ch)
            val id = String(ch, 0, 4, Charsets.ISO_8859_1)
            val len = ByteBuffer.wrap(ch, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (id == "fmt ") {
                val b = ByteArray(len.toInt()); raf.readFully(b); fmt = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                if (len and 1L == 1L) raf.skipBytes(1)
            } else if (id == "data") {
                val f = fmt ?: throw WavFormatException("data before fmt")
                var tag = f.getShort(0).toInt() and 0xFFFF
                val channels = f.getShort(2).toInt()
                val sr = f.getInt(4)
                val bits = f.getShort(14).toInt()
                if (tag == 0xFFFE && f.capacity() >= 26) tag = f.getShort(24).toInt() and 0xFFFF
                if (tag != 1 && tag != 3) throw WavFormatException("Unsupported WAV encoding $tag")
                if (channels !in 1..8 || sr <= 0) throw WavFormatException("Bad WAV format")
                val available = raf.length() - raf.filePointer
                return WavInfo(sr, channels, bits, tag == 3, raf.filePointer, minOf(len, available))
            } else {
                raf.seek(raf.filePointer + len + (len and 1L))
            }
        }
        throw WavFormatException("No data chunk")
    }

    /** Streams the file as mono floats in blocks (downmix), without loading it all. */
    fun streamMono(file: File, block: Int = 8192, consumer: (FloatArray, Int) -> Unit): WavInfo {
        val info = readInfo(file)
        file.inputStream().buffered(1 shl 16).use { input ->
            input.skipFully(info.dataOffset)
            decode(input, info, block) { buf, n -> consumer(buf, n) }
        }
        return info
    }

    private fun InputStream.skipFully(n: Long) { var left = n; while (left > 0) { val s = skip(left); if (s <= 0) { if (read() < 0) return; left-- } else left -= s } }

    fun decode(input: InputStream, info: WavInfo, block: Int, consumer: (FloatArray, Int) -> Unit) {
        val bytesPer = info.bitsPerSample / 8
        val frameBytes = info.frameBytes
        val raw = ByteArray(block * frameBytes)
        val out = FloatArray(block)
        var remaining = info.dataBytes
        while (remaining >= frameBytes) {
            val want = minOf(raw.size.toLong(), remaining - remaining % frameBytes).toInt()
            var got = 0
            while (got < want) { val r = input.read(raw, got, want - got); if (r <= 0) break; got += r }
            if (got < frameBytes) break
            val frames = got / frameBytes
            val bb = ByteBuffer.wrap(raw, 0, frames * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until info.channels) {
                    val o = f * frameBytes + c * bytesPer
                    sum += when {
                        info.isFloat && bytesPer == 4 -> bb.getFloat(o)
                        info.isFloat && bytesPer == 8 -> bb.getDouble(o).toFloat()
                        bytesPer == 1 -> ((raw[o].toInt() and 0xFF) - 128) / 128f
                        bytesPer == 2 -> bb.getShort(o) / 32768f
                        bytesPer == 3 -> ((raw[o].toInt() and 0xFF) or ((raw[o + 1].toInt() and 0xFF) shl 8) or (raw[o + 2].toInt() shl 16)) / 8388608f
                        bytesPer == 4 -> bb.getInt(o) / 2147483648f
                        else -> 0f
                    }
                }
                out[f] = sum / info.channels
            }
            consumer(out, frames)
            remaining -= got
            if (got < want) break
        }
    }
}
