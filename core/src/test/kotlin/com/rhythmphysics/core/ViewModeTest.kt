package com.rhythmphysics.core

import com.rhythmphysics.core.engine.RhythmEngine
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.render.AspectRatio
import com.rhythmphysics.core.render.DrawList
import com.rhythmphysics.core.render.RenderSettings
import com.rhythmphysics.core.session.ReplayManager
import com.rhythmphysics.core.session.SceneConfig
import com.rhythmphysics.core.session.ViewMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewModeTest {
    @Test
    fun journeyUsesEveryMechanicOnOneClockAndSeeksDeterministically() {
        for (session in listOf(Fixtures.demoSession(), AudioSessionFixture.session())) {
            val cfg = SceneConfig(viewMode = ViewMode.JOURNEY, aspect = AspectRatio.PORTRAIT_9_16)
            val e = RhythmEngine(session, cfg)
            val j = e.director.journey!!
            assertTrue(j.segments.map { it.mechanic }.toSet() == MechanicType.values().toSet(), "journey ${j.segments.map { it.mechanic }}")
            assertEquals(0.0, j.segments.first().startSec); assertEquals(session.durationSec, j.segments.last().endSec, 1e-9)
            for (k in 1 until j.segments.size) assertEquals(j.segments[k - 1].endSec, j.segments[k].startSec, 1e-9)
            val dl = DrawList()
            var t = 0.0
            val seen = HashSet<MechanicType>()
            while (t < session.durationSec) {
                e.update(t)
                e.director.activeSlots(t).forEach { seen += it.type }
                if ((t * 4).toInt() % 3 == 0) e.render(dl, RenderSettings())
                t += 1 / 30.0
            }
            assertEquals(MechanicType.values().toSet(), seen)
            val hash = e.stateHash()
            val e2 = RhythmEngine(session, cfg); e2.seek(50.0); e2.seek(session.durationSec - 0.5); e2.update(t - 1 / 30.0)
            e.update(t - 1 / 30.0)
            assertEquals(e.stateHash(), e2.stateHash(), "journey seek (${session.mediaType})")
            val r = ReplayManager.engineFor(ReplayManager.import(ReplayManager.export(ReplayManager.create(e))), session)
            r.seek(t - 1 / 30.0)
            assertEquals(e.stateHash(), r.stateHash(), "journey replay (${session.mediaType})")
            @Suppress("UNUSED_VARIABLE") val u = hash
        }
    }

    @Test
    fun quadAndDuetRunAllSlots() {
        for (mode in listOf(ViewMode.QUAD, ViewMode.DUET)) for (aspect in AspectRatio.values()) {
            val e = RhythmEngine(Fixtures.demoSession(), SceneConfig(viewMode = mode, aspect = aspect))
            var t = 0.0; while (t < 30) { e.update(t); t += 1 / 30.0 }
            val expected = if (mode == ViewMode.QUAD) 4 else 2
            assertEquals(expected, e.director.slots.count { it.mechanic != null }, "$mode ${aspect.label}")
            val dl = DrawList(); e.render(dl, RenderSettings())
            assertTrue(dl.commandCount > 50)
            val e2 = RhythmEngine(Fixtures.demoSession(), SceneConfig(viewMode = mode, aspect = aspect)); e2.seek(t - 1 / 30.0 + 1e-9)
            e.update(t - 1 / 30.0 + 1e-9)
            assertEquals(e.stateHash(), e2.stateHash(), "$mode ${aspect.label} seek")
        }
    }
}
