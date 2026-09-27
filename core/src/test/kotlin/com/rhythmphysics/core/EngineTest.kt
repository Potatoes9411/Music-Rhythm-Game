package com.rhythmphysics.core

import com.rhythmphysics.core.engine.RhythmEngine
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.render.AspectRatio
import com.rhythmphysics.core.render.DrawList
import com.rhythmphysics.core.render.RenderSettings
import com.rhythmphysics.core.session.SceneConfig
import com.rhythmphysics.core.session.ViewMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineTest {
    private fun cfg(type: MechanicType = MechanicType.SQUARE, mode: ViewMode = ViewMode.FOCUS, aspect: AspectRatio = AspectRatio.PORTRAIT_9_16) =
        SceneConfig(seed = 1337, aspect = aspect, viewMode = mode, focus = type)

    /** Drives the engine with a given render rate (display Hz) up to [until]. */
    private fun run(engine: RhythmEngine, hz: Double, until: Double, jitter: Boolean = false, from: Double = 0.0) {
        var t = from; var i = 0
        while (t < until) {
            engine.update(t)
            i++
            t += (1.0 / hz) * (if (jitter) (0.6 + 0.8 * ((i * 7919) % 13) / 13.0) else 1.0)
        }
        engine.update(until)
    }

    @Test
    fun simulationIsIndependentOfDisplayRate() {
        val hashes = listOf(60.0, 90.0, 120.0, 144.0).map { hz ->
            val e = RhythmEngine(Fixtures.demoSession(), cfg())
            run(e, hz, 42.0)
            e.stateHash()
        }
        assertEquals(1, hashes.toSet().size, hashes.toString())
        val jittery = RhythmEngine(Fixtures.demoSession(), cfg())
        run(jittery, 60.0, 42.0, jitter = true)
        assertEquals(hashes[0], jittery.stateHash())
    }

    @Test
    fun seekRestoresAndFastForwardsToIdenticalState() {
        val continuous = RhythmEngine(Fixtures.demoSession(), cfg())
        run(continuous, 60.0, 61.3)
        val seeker = RhythmEngine(Fixtures.demoSession(), cfg())
        run(seeker, 60.0, 30.0)
        seeker.seek(80.0)      // forward past any checkpoint
        seeker.seek(12.5)      // backward
        seeker.seek(61.3)
        assertEquals(continuous.stateHash(), seeker.stateHash())
        // restart is deterministic
        val a = RhythmEngine(Fixtures.demoSession(), cfg()); run(a, 60.0, 20.0)
        a.restart(); run(a, 60.0, 20.0)
        val b = RhythmEngine(Fixtures.demoSession(), cfg()); run(b, 60.0, 20.0)
        assertEquals(b.stateHash(), a.stateHash())
    }

    @Test
    fun noDuplicateImpactsAcrossPauseAndResume() {
        var impacts = 0
        val sink = object : com.rhythmphysics.core.mechanic.EngineSink {
            override fun onImpact(mechanic: MechanicType, timeSec: Double, strength: Float, eventId: Long, note: Int?) { impacts++ }
        }
        val e = RhythmEngine(Fixtures.demoSession(), cfg(), sink)
        run(e, 60.0, 10.0)
        val before = impacts
        repeat(300) { e.update(10.0) }  // paused: clock does not move
        assertEquals(before, impacts)
        run(e, 60.0, 20.0, from = 10.0)
        val e2Impacts = run { var n = 0; val s2 = object : com.rhythmphysics.core.mechanic.EngineSink { override fun onImpact(mechanic: MechanicType, timeSec: Double, strength: Float, eventId: Long, note: Int?) { n++ } }; val e2 = RhythmEngine(Fixtures.demoSession(), cfg(), s2); run(e2, 60.0, 20.0); n }
        assertEquals(e2Impacts, impacts)
    }

    @Test
    fun plannedSquareMeetsSyncGoals() {
        for (aspect in AspectRatio.values()) {
            val e = RhythmEngine(Fixtures.demoSession(), cfg(aspect = aspect))
            run(e, 60.0, Fixtures.demoMidi.durationSec)
            val st = e.sync.stats()
            assertTrue(st.count > 100, "count ${st.count}")
            assertTrue(st.passesPlannedGoal, "$aspect $st")
        }
    }

    @Test
    fun rendersWithoutCrashAtManyTimes() {
        val e = RhythmEngine(Fixtures.demoSession(), cfg())
        val dl = DrawList()
        var t = 0.0
        while (t < 30) { e.update(t); e.render(dl, RenderSettings(showDebug = true)); assertTrue(dl.commandCount > 10); t += 0.25 }
    }
}
