package com.rhythmphysics.core

import com.rhythmphysics.core.audio.AnalysisCache
import com.rhythmphysics.core.audio.AnalysisSettings
import com.rhythmphysics.core.audio.AudioAnalysis
import com.rhythmphysics.core.audio.AudioAnalyzer
import com.rhythmphysics.core.audio.WavReader
import com.rhythmphysics.core.audio.WavWriter
import com.rhythmphysics.core.music.BeatGrid
import com.rhythmphysics.core.rng.SeededRng
import com.rhythmphysics.core.synth.MidiRenderer
import com.rhythmphysics.core.synth.Sf2Synth
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

object AudioFixtures {
    /** Deterministic click track (developer fixture): 120 BPM, accented downbeats, light noise. */
    fun clickTrack(sr: Int = 44100, bpm: Double = 120.0, seconds: Double = 32.0, offset: Double = 0.25): Pair<FloatArray, List<Double>> {
        val n = (sr * seconds).toInt()
        val x = FloatArray(n)
        val rng = SeededRng(99)
        for (i in 0 until n) x[i] = ((rng.nextDouble() - 0.5) * 0.004).toFloat()
        val clicks = ArrayList<Double>()
        var t = offset; var k = 0
        while (t < seconds - 0.2) {
            clicks += t
            val accent = k % 4 == 0
            val start = (t * sr).toInt()
            val f = if (accent) 1500.0 else 1000.0
            for (j in 0 until (0.06 * sr).toInt()) {
                if (start + j >= n) break
                val env = exp(-j / (0.008 * sr))
                x[start + j] += (env * sin(2 * Math.PI * f * j / sr) * (if (accent) 0.8 else 0.5)).toFloat()
                if (accent) x[start + j] += (exp(-j / (0.03 * sr)) * sin(2 * Math.PI * 60.0 * j / sr) * 0.6).toFloat()
            }
            t += 60.0 / bpm; k++
        }
        return x to clicks
    }

    /** The demo song rendered with GeneralUser GS (realistic multi-instrument audio with known ground truth). */
    val demoWav: File by lazy {
        val f = File(System.getProperty("java.io.tmpdir"), "rp_demo_render_analysis.wav")
        if (!f.exists()) {
            val synth = Sf2Synth(SoundFontFixture.sf, 44100)
            WavWriter(f, 44100, 2).use { w -> MidiRenderer.render(Fixtures.demoMidi, synth) { l, r, n -> w.write(l, r, n) } }
        }
        f
    }

    fun analyze(file: File): AudioAnalysis {
        val info = WavReader.readInfo(file)
        val a = AudioAnalyzer(info.sampleRate)
        WavReader.streamMono(file) { b, n -> a.feed(b, n) }
        return a.finish()
    }
}

class AnalysisTest {
    @Test
    fun clickTrackTempoBeatsDownbeatsOnsets() {
        val (x, clicks) = AudioFixtures.clickTrack()
        val a = AudioAnalyzer(44100)
        var i = 0
        while (i < x.size) { val n = minOf(4096, x.size - i); a.feed(x.copyOfRange(i, i + n), n); i += n }
        val r = a.finish()
        println("click: bpm %.2f beats ${r.beatTimes.size} onsets ${r.onsets.size} downbeats ${r.downbeatTimes.size} conf %.2f".format(r.bpm, r.downbeatConfidence))
        assertEquals(120.0, r.bpm, 1.0)
        // Onset timing error
        val errs = clicks.map { c -> r.onsets.minOf { abs(it.timeSec - c) } }
        val sorted = errs.sorted()
        println("onset err median %.2f ms p95 %.2f ms".format(sorted[sorted.size / 2] * 1000, sorted[(sorted.size * 0.95).toInt()] * 1000))
        assertTrue(sorted[(sorted.size * 0.95).toInt()] < 0.010, "onset p95 ${sorted[(sorted.size * 0.95).toInt()]}")
        val beatErr = clicks.drop(1).dropLast(1).map { c -> r.beatTimes.minOf { abs(it - c) } }.sorted()
        assertTrue(beatErr[(beatErr.size * 0.95).toInt()] < 0.015, "beat p95 ${beatErr[(beatErr.size * 0.95).toInt()]}")
        // downbeats on accented clicks
        val accents = clicks.filterIndexed { k, _ -> k % 4 == 0 }
        val hits = r.downbeatTimes.count { d -> accents.any { abs(it - d) < 0.03 } }
        assertTrue(hits >= r.downbeatTimes.size * 0.9, "downbeats $hits/${r.downbeatTimes.size}")
    }

    @Test
    fun realisticMusicMatchesMidiGroundTruth() {
        val r = AudioFixtures.analyze(AudioFixtures.demoWav)
        val grid = BeatGrid.fromMidi(Fixtures.demoMidi)
        println("demo: bpm %.2f conf %.2f beats ${r.beatTimes.size} onsets ${r.onsets.size} downbeat conf %.2f sections ${r.sections.map { "%.1f".format(it) }} key ${r.keyPitchClass}".format(r.bpm, r.bpmConfidence, r.downbeatConfidence))
        assertEquals(112.0, r.bpm, 1.5)
        // Beats vs MIDI beat grid (ignore the tail).
        val truth = grid.beatTimes.filter { it > 0.3 && it < Fixtures.demoMidi.durationSec - 1 }
        val matched = truth.count { t -> r.beatTimes.any { abs(it - t) < 0.035 } }
        println("beats matched $matched/${truth.size}")
        assertTrue(matched >= truth.size * 0.9, "beats matched $matched/${truth.size}")
        // Downbeats vs bars.
        val bars = grid.beatTimes.filterIndexed { i, _ -> grid.downbeat[i] }.filter { it > 0.3 && it < Fixtures.demoMidi.durationSec - 1 }
        val dbMatched = r.downbeatTimes.count { d -> bars.any { abs(it - d) < 0.035 } }
        println("downbeats on bar lines $dbMatched/${r.downbeatTimes.size}")
        assertTrue(dbMatched >= r.downbeatTimes.size * 0.8, "downbeats $dbMatched/${r.downbeatTimes.size}")
        // Onsets vs MIDI note onsets.
        val noteOnsets = Fixtures.demoMidi.notes.map { it.startSec }.distinct().sorted()
        val precise = r.onsets.count { o -> noteOnsets.any { abs(it - o.timeSec) < 0.025 } }
        println("onset precision $precise/${r.onsets.size}")
        assertTrue(precise >= r.onsets.size * 0.85, "onset precision $precise/${r.onsets.size}")
        // Sections near true section starts (bars 4, 12, 20, 28, 36 at 112 bpm).
        val barSec = 4 * 60.0 / 112
        val truthSections = listOf(4, 12, 20, 28, 36).map { it * barSec }
        val secHits = truthSections.count { s -> r.sections.any { abs(it - s) < barSec * 1.05 } }
        println("section hits $secHits/5")
        assertTrue(secHits >= 3, "sections ${r.sections}")
        assertTrue(r.keyPitchClass == 9 || r.keyPitchClass == 0, "key ${r.keyPitchClass}: A minor (or relative C major) expected")
    }

    @Test
    fun cacheReusesByFingerprintVersionAndSettings() {
        val dir = File(System.getProperty("java.io.tmpdir"), "rp_cache_test").also { it.deleteRecursively() }
        val cache = AnalysisCache(dir)
        val (x, _) = AudioFixtures.clickTrack(seconds = 6.0)
        val a = AudioAnalyzer(44100); a.feed(x, x.size); val r = a.finish()
        val s = AnalysisSettings()
        assertNull(cache.get("abc", s))
        cache.put("abc", s, r)
        val back = cache.get("abc", s)
        assertNotNull(back)
        assertEquals(r.beatTimes, back.beatTimes)
        assertEquals(r.events, back.events)
        assertNull(cache.get("abd", s), "different fingerprint must miss")
        assertNull(cache.get("abc", s.copy(hop = 512)), "different settings must miss")
        assertTrue(cache.key("abc", s, 1) != cache.key("abc", s, 2), "analysis version is part of the key")
    }
}
