package com.rhythmphysics.core.scratch

import com.rhythmphysics.core.AudioFixtures
import com.rhythmphysics.core.Fixtures
import com.rhythmphysics.core.audio.AudioAnalyzer
import kotlin.math.abs
import kotlin.test.Test

class AnalysisProbe {
    @Test
    fun probe() {
        if (System.getProperty("rp.probe") == null) return
        val (x, clicks) = AudioFixtures.clickTrack()
        val a = AudioAnalyzer(44100); a.feed(x, x.size); val r = a.finish()
        println("CLICK onsets relative to nearest click (ms):")
        println(r.onsets.take(30).joinToString(" ") { o -> val c = clicks.minBy { abs(it - o.timeSec) }; "%.1f(%.2f)".format((o.timeSec - c) * 1000, o.strength) })
        val d = AudioFixtures.analyze(AudioFixtures.demoWav)
        val notes = Fixtures.demoMidi.notes.map { it.startSec }.distinct().sorted()
        val errs = d.onsets.map { o -> val n = notes.minBy { abs(it - o.timeSec) }; (o.timeSec - n) * 1000 }
        println("DEMO onset error histogram (ms): " + errs.groupBy { (it / 10).toInt() * 10 }.toSortedMap().map { "${it.key}:${it.value.size}" })
        println("DEMO far onsets: " + d.onsets.filterIndexed { i, _ -> abs(errs[i]) > 25 }.take(20).joinToString { "%.3f(%.2f)".format(it.timeSec, it.strength) })
    }
}
