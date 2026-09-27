package com.rhythmphysics.core.midi

import java.io.ByteArrayOutputStream

/** Minimal Standard MIDI File (format 1) writer used for fixtures and the bundled demo song. */
class MidiWriter(val ppq: Int = 480) {
    class Ev(val tick: Long, val order: Int, val bytes: ByteArray)

    private val tracks = ArrayList<MutableList<Ev>>()
    private var order = 0

    fun newTrack(name: String? = null): Int {
        tracks.add(ArrayList())
        val t = tracks.size - 1
        if (name != null) meta(t, 0, 0x03, name.toByteArray(Charsets.ISO_8859_1))
        return t
    }

    fun meta(track: Int, tick: Long, type: Int, data: ByteArray) {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(type); writeVlq(out, data.size.toLong()); out.write(data)
        tracks[track] += Ev(tick, order++, out.toByteArray())
    }

    fun tempo(track: Int, tick: Long, bpm: Double) {
        val us = (60_000_000.0 / bpm).toInt()
        meta(track, tick, 0x51, byteArrayOf((us shr 16).toByte(), (us shr 8).toByte(), us.toByte()))
    }

    fun timeSignature(track: Int, tick: Long, num: Int, den: Int) {
        var pow = 0; var d = den; while (d > 1) { d = d shr 1; pow++ }
        meta(track, tick, 0x58, byteArrayOf(num.toByte(), pow.toByte(), 24, 8))
    }

    fun program(track: Int, tick: Long, channel: Int, program: Int) {
        tracks[track] += Ev(tick, order++, byteArrayOf((0xC0 or channel).toByte(), program.toByte()))
    }

    fun cc(track: Int, tick: Long, channel: Int, controller: Int, value: Int) {
        tracks[track] += Ev(tick, order++, byteArrayOf((0xB0 or channel).toByte(), controller.toByte(), value.toByte()))
    }

    fun note(track: Int, tick: Long, durTicks: Long, channel: Int, pitch: Int, velocity: Int) {
        tracks[track] += Ev(tick, order++, byteArrayOf((0x90 or channel).toByte(), pitch.toByte(), velocity.coerceIn(1, 127).toByte()))
        tracks[track] += Ev(tick + durTicks, order++, byteArrayOf((0x80 or channel).toByte(), pitch.toByte(), 0))
    }

    fun toBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray()); int32(out, 6); int16(out, 1); int16(out, tracks.size); int16(out, ppq)
        for (t in tracks) {
            // Note-offs sort before note-ons at the same tick (avoids zero-length overlaps).
            val sorted = t.sortedWith(compareBy<Ev>({ it.tick }, { if ((it.bytes[0].toInt() and 0xF0) == 0x80) 0 else 1 }, { it.order }))
            val body = ByteArrayOutputStream()
            var last = 0L
            for (e in sorted) { writeVlq(body, e.tick - last); body.write(e.bytes); last = e.tick }
            writeVlq(body, 0); body.write(0xFF); body.write(0x2F); body.write(0)
            out.write("MTrk".toByteArray()); int32(out, body.size()); out.write(body.toByteArray())
        }
        return out.toByteArray()
    }

    private fun int32(o: ByteArrayOutputStream, v: Int) { o.write(v ushr 24); o.write(v ushr 16); o.write(v ushr 8); o.write(v) }
    private fun int16(o: ByteArrayOutputStream, v: Int) { o.write(v ushr 8); o.write(v) }

    companion object {
        fun writeVlq(o: ByteArrayOutputStream, value: Long) {
            var v = value
            val buf = IntArray(5); var n = 0
            buf[n++] = (v and 0x7F).toInt(); v = v shr 7
            while (v > 0) { buf[n++] = ((v and 0x7F) or 0x80).toInt(); v = v shr 7 }
            for (i in n - 1 downTo 0) o.write(buf[i])
        }
    }
}

/**
 * "Orbit Lines" — an original ~95 s composition (A minor, 112 BPM) written for this app as the
 * demo song and as the deterministic MIDI test fixture. Hand-written motifs, no randomness.
 * Sections: intro, verse, chorus, bridge, final chorus, outro (useful for Journey mode).
 */
object DemoSong {
    const val BPM = 112.0
    const val TITLE = "Orbit Lines (demo)"

    // Chord roots (MIDI) and qualities for the loops.
    private val verseChords = listOf(57 to "m", 53 to "M", 48 to "M", 55 to "M")  // Am F C G
    private val bridgeChords = listOf(50 to "m", 52 to "m", 53 to "M", 55 to "M") // Dm Em F G

    // Melody motifs: (beat offset in eighths, length in eighths, pitch)
    private val verseMotif = listOf(
        listOf(0 to 2 to 69, 2 to 1 to 72, 3 to 1 to 71, 4 to 2 to 69, 6 to 2 to 64),
        listOf(0 to 3 to 65, 3 to 1 to 67, 4 to 2 to 69, 6 to 2 to 72),
        listOf(0 to 2 to 67, 2 to 2 to 64, 4 to 1 to 67, 5 to 1 to 69, 6 to 2 to 71),
        listOf(0 to 4 to 71, 4 to 1 to 69, 5 to 1 to 67, 6 to 2 to 74),
    )
    private val chorusMotif = listOf(
        listOf(0 to 1 to 76, 1 to 1 to 76, 2 to 2 to 74, 4 to 1 to 72, 5 to 1 to 74, 6 to 2 to 76),
        listOf(0 to 2 to 77, 2 to 2 to 76, 4 to 2 to 74, 6 to 1 to 72, 7 to 1 to 71),
        listOf(0 to 1 to 72, 1 to 1 to 74, 2 to 2 to 76, 4 to 2 to 79, 6 to 2 to 76),
        listOf(0 to 3 to 74, 3 to 1 to 72, 4 to 2 to 71, 6 to 1 to 74, 7 to 1 to 79),
    )
    private val bridgeMotif = listOf(
        listOf(0 to 4 to 74, 4 to 2 to 77, 6 to 2 to 76),
        listOf(0 to 4 to 71, 4 to 2 to 72, 6 to 2 to 74),
        listOf(0 to 2 to 72, 2 to 2 to 76, 4 to 4 to 77),
        listOf(0 to 2 to 79, 2 to 2 to 77, 4 to 2 to 76, 6 to 2 to 74),
    )

    fun bytes(): ByteArray {
        val w = MidiWriter(480)
        val q = 480L; val e8 = q / 2; val bar = 4 * q
        val meta = w.newTrack("Orbit Lines")
        w.tempo(meta, 0, BPM); w.timeSignature(meta, 0, 4, 4)
        val mel = w.newTrack("Lead"); w.program(mel, 0, 0, 11); w.cc(mel, 0, 0, 7, 100); w.cc(mel, 0, 0, 91, 50)
        val keys = w.newTrack("Keys"); w.program(keys, 0, 1, 4); w.cc(keys, 0, 1, 7, 78); w.cc(keys, 0, 1, 10, 44)
        val pad = w.newTrack("Pad"); w.program(pad, 0, 2, 89); w.cc(pad, 0, 2, 7, 64); w.cc(pad, 0, 2, 10, 84)
        val bass = w.newTrack("Bass"); w.program(bass, 0, 3, 33); w.cc(bass, 0, 3, 7, 104)
        val drums = w.newTrack("Drums"); w.cc(drums, 0, 9, 7, 108)

        // (section name, bars, chords, motif, drum intensity 0..3, melody on)
        data class Sec(val bars: Int, val chords: List<Pair<Int, String>>, val motif: List<List<Pair<Pair<Int, Int>, Int>>>?, val drums: Int, val pad: Boolean)
        val sections = listOf(
            Sec(4, verseChords, null, 1, true),          // intro
            Sec(8, verseChords, verseMotif, 2, false),   // verse
            Sec(8, verseChords, chorusMotif, 3, true),   // chorus
            Sec(8, bridgeChords, bridgeMotif, 1, true),  // bridge
            Sec(8, verseChords, chorusMotif, 3, true),   // final chorus
            Sec(4, verseChords, null, 0, true),          // outro
        )
        var barStart = 0L
        for (sec in sections) {
            for (b in 0 until sec.bars) {
                val tick = barStart + b * bar
                val (root, qual) = sec.chords[b % sec.chords.size]
                val third = if (qual == "m") 3 else 4
                // Keys: syncopated chord stabs.
                for ((pos, len) in listOf(0 to 3, 3 to 3, 6 to 2)) {
                    for (iv in listOf(0, third, 7)) w.note(keys, tick + pos * e8, len * e8 - 20, 1, root + 12 + iv, if (pos == 0) 84 else 70)
                }
                if (sec.pad) for (iv in listOf(0, third, 7, 12)) w.note(pad, tick, bar - 10, 2, root + iv, 58)
                // Bass: root eighths with octave pops.
                val bassPat = listOf(0 to 0, 2 to 0, 3 to 12, 4 to 0, 6 to 7, 7 to 12)
                if (sec.drums > 0) for ((pos, iv) in bassPat) w.note(bass, tick + pos * e8, e8 - 30, 3, root - 12 + iv, if (pos == 0) 110 else 88)
                else w.note(bass, tick, bar - 20, 3, root - 12, 90)
                // Melody.
                sec.motif?.let { m -> for ((pl, pitch) in m[b % m.size]) { val (pos, len) = pl; w.note(mel, tick + pos * e8, len * e8 - 25, 0, pitch, 96 + (if (pos == 0) 14 else 0)) } }
                // Drums.
                if (sec.drums >= 1) {
                    w.note(drums, tick, e8, 9, 36, 118); w.note(drums, tick + 4 * e8, e8, 9, 36, 108)
                    if (sec.drums >= 2) { w.note(drums, tick + 2 * e8, e8, 9, 38, 104); w.note(drums, tick + 6 * e8, e8, 9, 38, 110) }
                    if (sec.drums >= 3) { w.note(drums, tick + 5 * e8, e8, 9, 36, 96) }
                    val hats = if (sec.drums >= 2) 8 else 4
                    for (h in 0 until hats) w.note(drums, tick + h * (bar / hats), e8 / 2, 9, 42, if (h % 2 == 0) 80 else 58)
                    if (b == 0) w.note(drums, tick, q, 9, 49, 100)
                }
            }
            barStart += sec.bars * bar
        }
        // Final chord ring-out.
        for (iv in listOf(0, 3, 7, 12)) w.note(pad, barStart, 2 * bar, 2, 57 + iv, 70)
        w.note(bass, barStart, 2 * bar, 3, 45, 96)
        w.note(drums, barStart, q, 9, 49, 110)
        return w.toBytes()
    }
}
