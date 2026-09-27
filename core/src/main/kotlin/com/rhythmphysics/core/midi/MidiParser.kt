package com.rhythmphysics.core.midi

/** A resolved note with absolute times. */
data class MidiNote(
    val startSec: Double,
    val durationSec: Double,
    val pitch: Int,
    val velocity: Int,
    val channel: Int,
    val track: Int,
    val program: Int,
    val startTick: Long,
) {
    val isPercussion get() = channel == 9
}

/** Raw channel-voice message with absolute time, used by the synthesizer. */
data class MidiChannelEvent(
    val timeSec: Double,
    val tick: Long,
    val track: Int,
    val order: Int,
    val status: Int, // 0x80..0xE0 (high nibble)
    val channel: Int,
    val data1: Int,
    val data2: Int,
)

data class TempoChange(val tick: Long, val microsPerQuarter: Int) {
    val bpm get() = 60_000_000.0 / microsPerQuarter
}

data class TimeSignature(val tick: Long, val timeSec: Double, val numerator: Int, val denominator: Int)

class MidiFile(
    val format: Int,
    val division: Int,
    val trackCount: Int,
    val notes: List<MidiNote>,
    val channelEvents: List<MidiChannelEvent>,
    val tempos: List<TempoChange>,
    val timeSignatures: List<TimeSignature>,
    val trackNames: Map<Int, String>,
    val durationSec: Double,
    private val tickToSecFn: (Long) -> Double,
) {
    fun tickToSec(tick: Long) = tickToSecFn(tick)
    val ppq: Int get() = if (division > 0) division else 480
    val initialBpm: Double get() = tempos.firstOrNull()?.bpm ?: 120.0
}

class MidiFormatException(msg: String) : Exception(msg)

/** Standard MIDI File parser (format 0/1/2, PPQ and SMPTE division, running status, RMID wrapper). */
object MidiParser {

    fun isMidi(header: ByteArray): Boolean {
        if (header.size >= 4 && header[0] == 'M'.code.toByte() && header[1] == 'T'.code.toByte() &&
            header[2] == 'h'.code.toByte() && header[3] == 'd'.code.toByte()) return true
        return header.size >= 12 && String(header, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(header, 8, 4, Charsets.ISO_8859_1) == "RMID"
    }

    private class Reader(val b: ByteArray, var pos: Int, val end: Int) {
        fun u8(): Int { if (pos >= end) throw MidiFormatException("unexpected end of data"); return b[pos++].toInt() and 0xFF }
        fun u16() = (u8() shl 8) or u8()
        fun u32() = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun vlq(): Long {
            var v = 0L
            for (i in 0 until 4) {
                val c = u8()
                v = (v shl 7) or (c and 0x7F).toLong()
                if (c and 0x80 == 0) return v
            }
            throw MidiFormatException("variable-length quantity too long")
        }
        fun skip(n: Int) { pos = minOf(end, pos + n) }
        val hasMore get() = pos < end
    }

    private class RawEvent(val tick: Long, val track: Int, val order: Int, val status: Int, val d1: Int, val d2: Int)

    fun parse(input: ByteArray): MidiFile {
        var data = input
        var start = 0
        if (input.size >= 12 && String(input, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(input, 8, 4, Charsets.ISO_8859_1) == "RMID") {
            // RIFF RMID wrapper: find the "data" chunk.
            var p = 12
            while (p + 8 <= input.size) {
                val id = String(input, p, 4, Charsets.ISO_8859_1)
                val len = (input[p + 4].toInt() and 0xFF) or ((input[p + 5].toInt() and 0xFF) shl 8) or
                    ((input[p + 6].toInt() and 0xFF) shl 16) or ((input[p + 7].toInt() and 0xFF) shl 24)
                if (id == "data") { start = p + 8; break }
                p += 8 + len + (len and 1)
            }
        }
        val r = Reader(data, start, data.size)
        if (r.u32() != 0x4D546864) throw MidiFormatException("missing MThd header")
        val hlen = r.u32()
        val hdrEnd = r.pos + hlen
        val format = r.u16()
        val ntracks = r.u16()
        val division = r.u16().let { if (it and 0x8000 != 0) it.toShort().toInt() else it }
        r.pos = hdrEnd
        if (division == 0) throw MidiFormatException("invalid division")

        val raw = ArrayList<RawEvent>()
        val tempoEvents = ArrayList<TempoChange>()
        val timeSigRaw = ArrayList<Triple<Long, Int, Int>>()
        val trackNames = HashMap<Int, String>()
        var maxTick = 0L
        var order = 0
        var track = 0
        while (r.hasMore && track < ntracks) {
            if (r.pos + 8 > data.size) break
            val id = r.u32()
            val len = r.u32()
            val chunkEnd = minOf(data.size, r.pos + len)
            if (id != 0x4D54726B) { r.pos = chunkEnd; continue } // not MTrk
            val tr = Reader(data, r.pos, chunkEnd)
            var tick = 0L
            var running = 0
            try {
                while (tr.hasMore) {
                    tick += tr.vlq()
                    var status = tr.u8()
                    if (status < 0x80) {
                        if (running == 0) throw MidiFormatException("data byte without running status")
                        tr.pos--
                        status = running
                    }
                    when {
                        status == 0xFF -> {
                            val type = tr.u8()
                            val mlen = tr.vlq().toInt()
                            val mstart = tr.pos
                            when (type) {
                                0x51 -> if (mlen >= 3) {
                                    val us = (tr.u8() shl 16) or (tr.u8() shl 8) or tr.u8()
                                    if (us > 0) tempoEvents += TempoChange(tick, us)
                                }
                                0x58 -> if (mlen >= 2) {
                                    val num = tr.u8(); val den = 1 shl tr.u8()
                                    timeSigRaw += Triple(tick, num, den)
                                }
                                0x03 -> trackNames[track] = String(data, mstart, minOf(mlen, chunkEnd - mstart), Charsets.ISO_8859_1).trim()
                            }
                            tr.pos = mstart
                            tr.skip(mlen)
                            if (type == 0x2F) break
                            running = 0
                        }
                        status == 0xF0 || status == 0xF7 -> { tr.skip(tr.vlq().toInt()); running = 0 }
                        status >= 0xF8 -> Unit // realtime (should not appear in files)
                        status in 0xF1..0xF6 -> { /* system common in file: ignore payload best effort */ }
                        else -> {
                            running = status
                            val hi = status and 0xF0
                            val d1 = tr.u8()
                            val d2 = if (hi == 0xC0 || hi == 0xD0) 0 else tr.u8()
                            raw += RawEvent(tick, track, order++, status, d1, d2)
                        }
                    }
                }
            } catch (e: MidiFormatException) {
                // Truncated track: keep what was parsed.
            }
            maxTick = maxOf(maxTick, tick)
            r.pos = chunkEnd
            track++
        }

        // Tempo map (format 2 is treated like format 1; rare in practice).
        tempoEvents.sortBy { it.tick }
        val tempos = ArrayList<TempoChange>()
        for (t in tempoEvents) {
            if (tempos.isNotEmpty() && tempos.last().tick == t.tick) tempos[tempos.size - 1] = t else tempos += t
        }
        if (tempos.isEmpty() || tempos[0].tick != 0L) tempos.add(0, TempoChange(0, 500_000))
        val segStartSec = DoubleArray(tempos.size)
        for (i in 1 until tempos.size) {
            val prev = tempos[i - 1]
            segStartSec[i] = segStartSec[i - 1] + (tempos[i].tick - prev.tick) * prev.microsPerQuarter / 1e6 / division
        }
        val tickToSec: (Long) -> Double = if (division < 0) {
            val fps = -(division shr 8)
            val tpf = division and 0xFF
            val scale = 1.0 / (if (fps == 29) 29.97 else fps.toDouble()) / tpf;
            { t: Long -> t * scale }
        } else { t: Long ->
            var lo = 0; var hi = tempos.size - 1
            while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (tempos[mid].tick <= t) lo = mid else hi = mid - 1 }
            segStartSec[lo] + (t - tempos[lo].tick) * tempos[lo].microsPerQuarter / 1e6 / division
        }

        raw.sortWith(compareBy({ it.tick }, { it.order }))
        val channelEvents = ArrayList<MidiChannelEvent>(raw.size)
        val notes = ArrayList<MidiNote>()
        val programs = IntArray(16)
        // Pending note-ons per (channel, pitch), FIFO.
        val pending = HashMap<Int, ArrayDeque<Triple<Long, Int, Int>>>() // key -> (tick, velocity, track)
        val pendingProgram = HashMap<Int, ArrayDeque<Int>>()
        fun closeNote(ch: Int, pitch: Int, endTick: Long) {
            val key = ch * 128 + pitch
            val q = pending[key] ?: return
            val on = q.removeFirstOrNull() ?: return
            val prog = pendingProgram[key]?.removeFirstOrNull() ?: programs[ch]
            val s = tickToSec(on.first)
            notes += MidiNote(s, (tickToSec(endTick) - s).coerceAtLeast(0.0), pitch, on.second, ch, on.third, prog, on.first)
        }
        for (e in raw) {
            val hi = e.status and 0xF0
            val ch = e.status and 0x0F
            channelEvents += MidiChannelEvent(tickToSec(e.tick), e.tick, e.track, e.order, hi, ch, e.d1, e.d2)
            when (hi) {
                0xC0 -> programs[ch] = e.d1
                0x90 -> if (e.d2 > 0) {
                    val key = ch * 128 + e.d1
                    pending.getOrPut(key) { ArrayDeque() }.addLast(Triple(e.tick, e.d2, e.track))
                    pendingProgram.getOrPut(key) { ArrayDeque() }.addLast(programs[ch])
                } else closeNote(ch, e.d1, e.tick)
                0x80 -> closeNote(ch, e.d1, e.tick)
            }
        }
        for ((key, q) in pending) while (q.isNotEmpty()) closeNote(key / 128, key % 128, maxTick)
        notes.sortWith(compareBy({ it.startSec }, { it.pitch }, { it.channel }))

        val timeSigs = timeSigRaw.sortedBy { it.first }.map { TimeSignature(it.first, tickToSec(it.first), it.second, it.third) }
        val lastNoteEnd = notes.maxOfOrNull { it.startSec + it.durationSec } ?: 0.0
        val duration = maxOf(tickToSec(maxTick), lastNoteEnd)
        return MidiFile(format, division, track, notes, channelEvents, tempos, timeSigs, trackNames, duration, tickToSec)
    }
}
