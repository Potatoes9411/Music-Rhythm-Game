package com.rhythmphysics.core

import com.rhythmphysics.core.engine.RhythmEngine
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.mechanic.platform.PlatformCoursePlanner
import com.rhythmphysics.core.mechanic.platform.PlatformMechanic
import com.rhythmphysics.core.mechanic.platform.PlatformStyle
import com.rhythmphysics.core.music.EventMapper
import com.rhythmphysics.core.music.EventMappingSettings
import com.rhythmphysics.core.music.EventMode
import com.rhythmphysics.core.preset.PresetManager
import com.rhythmphysics.core.render.AspectRatio
import com.rhythmphysics.core.session.SceneConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformTest {
    @Test
    fun courseIsGeneratedFromEventsLandsExactlyAndDescends() {
        val ev = EventMapper.forMechanic(Fixtures.demoSession().source.events(EventMappingSettings(EventMode.HYBRID, 0.6f)), 0.13).filter { it.role.physical }
        for (style in PlatformStyle.values()) {
            val gen = PresetManager.forMechanic(MechanicType.PLATFORM).first().generation
            val p = PlatformCoursePlanner(ev, gen, style, 9f / 16f, 3, ev.first().timeSec - 0.7, 26.0)
            p.ensure(1e9)
            assertEquals(ev.size, p.contacts.size)
            for ((i, c) in p.contacts.withIndex()) {
                assertEquals(ev[i].timeSec, c.timeSec, 0.0)
                // ball evaluated exactly at the event is on the platform top
                val b = p.ballAt(c.timeSec)
                assertEquals(c.platform.top + p.ballRadius, b.y, 1e-6, "$style contact $i height")
                assertTrue(c.launch.y >= 1.5 || style == PlatformStyle.STAIRCASE || i == 0, "$style contact $i bounces downward (${c.launch.y})")
            }
            val drop = p.contacts.first().position.y - p.contacts.last().position.y
            assertTrue(drop > 20, "$style course should descend (drop $drop)")
            val a = PlatformCoursePlanner(ev, gen, style, 9f / 16f, 3, ev.first().timeSec - 0.7, 26.0); a.ensure(1e9)
            assertEquals(p.contacts, a.contacts, "$style not deterministic")
        }
    }

    @Test
    fun syncSeekAndComposition() {
        for (preset in PresetManager.forMechanic(MechanicType.PLATFORM)) for (aspect in AspectRatio.values()) {
            val cfg = SceneConfig(aspect = aspect, focus = MechanicType.PLATFORM, presetIds = mapOf(MechanicType.PLATFORM to preset.id))
            val e = RhythmEngine(Fixtures.demoSession(), cfg)
            val m = e.director.slots[0].mechanic as PlatformMechanic
            val cov = ArrayList<Double>(); val inF = ArrayList<Double>(); var minBall = 1.0
            var t = 0.0
            while (t < 80) {
                e.update(t)
                if (t > 10 && ((t * 10).toInt() % 4 == 0)) { val c = m.composition(e.frame, t); cov += c[0]; inF += c[2]; minBall = minOf(minBall, c[1]) }
                t += 1 / 60.0
            }
            e.update(80.0)
            val st = e.sync.stats()
            assertTrue(st.count > 60 && st.passesPlannedGoal, "${preset.id} $st")
            val s2 = RhythmEngine(Fixtures.demoSession(), cfg); s2.seek(33.0); s2.seek(80.0)
            assertEquals(e.stateHash(), s2.stateHash(), "${preset.id} ${aspect.label} seek")
            val medCov = cov.sorted()[cov.size / 2]; val p10In = inF.sorted()[inF.size / 10]
            println("${preset.id} ${aspect.label}: coverage %.2f nextInFrame p10 %.2f ball %.3f".format(medCov, p10In, minBall))
            // Known weaker composition (documented in README): Piano Tiles in 16:9 uses x for pitch, so the
            // course cannot also progress sideways; it stays readable but sparse.
            val floor = if (preset.id == "platform.piano_tiles" && aspect == AspectRatio.LANDSCAPE_16_9) 0.22 else 0.25
            assertTrue(medCov >= floor, "${preset.id} ${aspect.label} coverage $medCov")
            assertTrue(p10In >= 0.5, "${preset.id} ${aspect.label} upcoming contacts out of frame")
            assertTrue(minBall >= 0.04, "${preset.id} ${aspect.label} ball too small $minBall")
        }
    }
}
