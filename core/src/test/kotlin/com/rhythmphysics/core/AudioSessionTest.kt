package com.rhythmphysics.core

import com.rhythmphysics.core.engine.RhythmEngine
import com.rhythmphysics.core.music.EventMappingSettings
import com.rhythmphysics.core.music.EventMode
import com.rhythmphysics.core.music.SourceType
import com.rhythmphysics.core.render.AspectRatio
import com.rhythmphysics.core.session.AudioEventSource
import com.rhythmphysics.core.session.MediaType
import com.rhythmphysics.core.session.RhythmSession
import com.rhythmphysics.core.session.SceneConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

object AudioSessionFixture {
    val analysis by lazy { AudioFixtures.analyze(AudioFixtures.demoWav) }
    fun session() = RhythmSession("audio-demo", "file://demo.wav", "audio-demo-fp", MediaType.AUDIO, "Orbit Lines (rendered audio)",
        analysis.durationSec, AudioEventSource(analysis), analysis = analysis)
}

class AudioSessionTest {
    @Test
    fun audioEventsAreRealAudioEventsNotMidi() {
        val ev = AudioSessionFixture.session().source.events(EventMappingSettings(EventMode.HYBRID, 0.6f))
        assertTrue(ev.isNotEmpty())
        assertTrue(ev.all { it.sourceType == SourceType.AUDIO_BEAT || it.sourceType == SourceType.AUDIO_ONSET })
        assertTrue(ev.any { it.chroma != null && it.bassEnergy != null && it.spectralCentroid != null })
    }

    @Test
    fun squareDrivenByAudioAnalysisMeetsSyncAndIsDeterministic() {
        for (mode in EventMode.values()) {
            val cfg = SceneConfig(aspect = AspectRatio.PORTRAIT_9_16, eventMapping = EventMappingSettings(mode, 0.6f))
            val e = RhythmEngine(AudioSessionFixture.session(), cfg)
            var t = 0.0
            while (t < 60.0) { e.update(t); t += 1.0 / 60 }
            val st = e.sync.stats()
            println("audio $mode: contacts ${st.count} mean %.4f p95 %.4f max %.4f degraded ${st.degraded}".format(st.meanAbsMs, st.p95AbsMs, st.maxAbsMs))
            assertTrue(st.count > 10, "$mode produced ${st.count} contacts")
            assertTrue(st.passesPlannedGoal, "$mode $st")
            assertTrue(st.degraded <= st.count / 25 + 1, "$mode degraded ${st.degraded}")
            val hash = e.stateHash()
            val e2 = RhythmEngine(AudioSessionFixture.session(), cfg)
            e2.seek(33.3); e2.seek(60.0)
            assertEquals(hash, e2.stateHash(), "$mode seek/replay mismatch")
        }
    }
}
