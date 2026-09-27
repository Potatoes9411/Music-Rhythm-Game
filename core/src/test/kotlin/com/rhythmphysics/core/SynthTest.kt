package com.rhythmphysics.core

import com.rhythmphysics.core.audio.WavReader
import com.rhythmphysics.core.audio.WavWriter
import com.rhythmphysics.core.midi.MidiParser
import com.rhythmphysics.core.midi.MidiWriter
import com.rhythmphysics.core.synth.MidiRenderer
import com.rhythmphysics.core.synth.Sf2Synth
import com.rhythmphysics.core.synth.SoundFont
import com.rhythmphysics.core.util.Hashing
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

object SoundFontFixture {
    val sf: SoundFont by lazy { SoundFont.parse(File(System.getProperty("rp.soundfont")).readBytes()) }
}

class SynthTest {
    private fun renderNote(program: Int, key: Int, sec: Double, channel: Int = 0): FloatArray {
        val synth = Sf2Synth(SoundFontFixture.sf, 44100)
        synth.programChange(channel, program)
        synth.noteOn(channel, key, 100)
        val n = (sec * 44100).toInt()
        val l = FloatArray(n); val r = FloatArray(n)
        synth.render(l, r, 0, n)
        return FloatArray(n) { (l[it] + r[it]) / 2 }
    }

    /** Fundamental estimate: smallest-lag autocorrelation peak within 90% of the global maximum. */
    private fun pitchHz(x: FloatArray, from: Int, len: Int): Double {
        val r = DoubleArray(2001)
        for (lag in 20..2000) { var s = 0.0; for (i in 0 until len) s += x[from + i] * x[from + i + lag]; r[lag] = s }
        val max = (20..2000).maxOf { r[it] }
        for (lag in 21..1999) if (r[lag] >= 0.9 * max && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) {
            // parabolic refinement
            val d = (r[lag - 1] - r[lag + 1]) / (2 * (r[lag - 1] - 2 * r[lag] + r[lag + 1]))
            return 44100.0 / (lag + d)
        }
        return 0.0
    }

    @Test
    fun parsesGeneralUserBank() {
        val sf = SoundFontFixture.sf
        assertTrue(sf.presets.size > 200, "presets ${sf.presets.size}")
        assertTrue(sf.presets.any { it.bank == 128 }, "drum kits")
        assertTrue(sf.samples.size > 1_000_000)
        val grand = sf.findPreset(0, 0)!!
        assertTrue(grand.name.contains("Piano", ignoreCase = true) || grand.name.contains("Grand", ignoreCase = true), grand.name)
    }

    @Test
    fun notesAreInTuneAndSampleBased() {
        for ((program, key) in listOf(0 to 69, 0 to 60, 11 to 72, 33 to 45)) {
            val x = renderNote(program, key, 1.2)
            val rms = sqrt(x.sumOf { (it * it).toDouble() } / x.size)
            assertTrue(rms > 0.005, "program $program key $key silent (rms $rms)")
            val expected = 440.0 * Math.pow(2.0, (key - 69) / 12.0)
            val hz = pitchHz(x, 8000, 4096)
            val cents = 1200 * kotlin.math.ln(hz / expected) / kotlin.math.ln(2.0)
            assertTrue(abs(cents) < 30 || abs(abs(cents) - 1200) < 30, "program $program key $key: $hz Hz vs $expected ($cents cents)")
        }
        // A sampled piano is not a pure sine: strong harmonic content.
        val piano = renderNote(0, 57, 1.0)
        var fundamental = 0.0; var total = 0.0
        val f0 = 220.0
        for (h in 1..8) {
            var re = 0.0; var im = 0.0
            for (i in 4000 until 12000) { val ph = 2 * Math.PI * f0 * h * i / 44100; re += piano[i] * cos(ph); im += piano[i] * sin(ph) }
            val mag = sqrt(re * re + im * im)
            if (h == 1) fundamental = mag
            total += mag
        }
        assertTrue(fundamental / total < 0.8, "piano looks like a pure tone")
    }

    @Test
    fun drumsAndEnvelopesBehave() {
        val synth = Sf2Synth(SoundFontFixture.sf, 44100)
        synth.noteOn(9, 38, 110) // snare
        val n = 44100
        val l = FloatArray(n); val r = FloatArray(n)
        synth.render(l, r, 0, n)
        val early = (0 until 4410).maxOf { abs(l[it]) }
        val late = (30000 until n).maxOf { abs(l[it]) }
        assertTrue(early > 0.05 && late < early * 0.2, "snare should decay: early $early late $late")
    }

    @Test
    fun demoRenderIsDeterministicFastAndWellLevelled() {
        val midi = Fixtures.demoMidi
        fun render(): Pair<String, FloatArray> {
            val synth = Sf2Synth(SoundFontFixture.sf, 44100)
            val mono = FloatArray(((midi.durationSec + 2.5) * 44100).toInt() + 2048)
            var pos = 0
            val md = java.security.MessageDigest.getInstance("SHA-256")
            MidiRenderer.render(midi, synth) { l, r, n ->
                for (i in 0 until n) {
                    if (pos < mono.size) mono[pos++] = (l[i] + r[i]) / 2
                    val a = java.lang.Float.floatToIntBits(l[i]); md.update(a.toByte()); md.update((a shr 8).toByte()); md.update((a shr 16).toByte()); md.update((a shr 24).toByte())
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) } to mono.copyOf(pos)
        }
        val t0 = System.nanoTime()
        val (h1, mono) = render()
        val sec = (System.nanoTime() - t0) / 1e9
        val (h2, _) = render()
        assertEquals(h1, h2, "rendering must be deterministic")
        val speed = midi.durationSec / sec
        println("demo render: %.1f s audio in %.2f s (%.1fx realtime)".format(midi.durationSec, sec, speed))
        assertTrue(speed > 5, "too slow: ${speed}x")
        val peak = mono.maxOf { abs(it) }
        val rms = sqrt(mono.sumOf { (it * it).toDouble() } / mono.size)
        println("peak %.3f rms %.1f dBFS".format(peak, 20 * log10(rms)))
        assertTrue(peak in 0.2f..1.0f, "peak $peak")
        assertTrue(20 * log10(rms) in -30.0..-8.0, "rms ${20 * log10(rms)} dBFS")
        assertTrue(mono.none { it.isNaN() })
        // Write a short listening excerpt + full render for the analysis tests.
        val dir = File(Fixtures.artifacts, "audio").also { it.mkdirs() }
        WavWriter(File(dir, "demo_orbit_lines_generaluser_excerpt.wav"), 44100, 1).use { w -> w.write(mono, null, minOf(mono.size, 44100 * 14)) }
        val full = File(System.getProperty("java.io.tmpdir"), "rp_demo_render.wav")
        WavWriter(full, 44100, 1).use { w -> w.write(mono, null, mono.size) }
        val info = WavReader.readInfo(full)
        assertEquals(44100, info.sampleRate); assertEquals(mono.size.toLong(), info.frames)
    }

    @Test
    fun wavRoundTrip() {
        val f = File.createTempFile("rp_wav", ".wav")
        val x = FloatArray(1000) { sin(it * 0.05).toFloat() * 0.5f }
        WavWriter(f, 22050, 2).use { it.write(x, x, x.size) }
        val got = ArrayList<Float>()
        val info = WavReader.streamMono(f, 128) { b, n -> for (i in 0 until n) got += b[i] }
        assertEquals(22050, info.sampleRate); assertEquals(1000, got.size)
        for (i in x.indices) assertTrue(abs(got[i] - x[i]) < 1e-3)
        f.delete()
        assertTrue(Hashing.sha256Hex("a").length == 64)
        @Suppress("UNUSED_VARIABLE") val unused = MidiParser.isMidi(ByteArray(0)) || MidiWriter().ppq > 0
    }
}
