package com.rhythmphysics.core

import com.rhythmphysics.core.mechanic.square.PlannedSquareImpact
import com.rhythmphysics.core.mechanic.square.SquareRoutePlanner
import com.rhythmphysics.core.music.EventMapper
import com.rhythmphysics.core.music.EventMappingSettings
import com.rhythmphysics.core.music.EventMode
import com.rhythmphysics.core.music.MusicEvent
import com.rhythmphysics.core.music.SourceType
import com.rhythmphysics.core.preset.PresetManager
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SquareTest {
    private fun physical(mapping: EventMappingSettings = EventMappingSettings(EventMode.HYBRID, 0.62f)): List<MusicEvent> {
        val ev = Fixtures.demoSession().source.events(mapping)
        return EventMapper.forMechanic(ev, 0.09).filter { it.role.physical }
    }

    /** Runs the planner the way the mechanic does (rolling buffer + pruning) and collects every commit. */
    private fun runPlanner(events: List<MusicEvent>, aspect: Float, seed: Long, stepSec: Double = 1.0 / 120): List<PlannedSquareImpact> {
        val preset = PresetManager.byId("square.classic")!!
        val planner = SquareRoutePlanner(events, preset.generation, aspect, seed, events.first().timeSec - 0.6)
        val all = LinkedHashMap<Int, PlannedSquareImpact>()
        var t = 0.0
        val end = events.last().timeSec + 1.0
        while (t < end) {
            planner.ensure(t + 1.2)
            for (i in planner.impacts) all.putIfAbsent(i.index, i)
            planner.prune(t)
            t += stepSec
        }
        return all.values.toList()
    }

    @Test
    fun everyPhysicalEventBecomesAContactExactlyOnTime() {
        val ev = physical()
        val impacts = runPlanner(ev, 9f / 16f, 1337)
        assertEquals(ev.size, impacts.size)
        for ((i, imp) in impacts.withIndex()) {
            assertEquals(ev[i].id, imp.eventId)
            assertEquals(ev[i].timeSec, imp.eventTimeSec, 0.0)
        }
        // Kinematic continuity: each contact is reached by travelling the outgoing velocity.
        for (k in 0 until impacts.size - 1) {
            val a = impacts[k]; val b = impacts[k + 1]
            val dt = b.eventTimeSec - a.eventTimeSec
            val predicted = a.position + a.outgoingVelocity * dt
            assertTrue((predicted - b.position).length < 1e-6, "discontinuity at $k")
        }
        val degraded = impacts.count { it.degraded }
        assertTrue(degraded <= impacts.size / 50, "degraded $degraded / ${impacts.size}")
    }

    @Test
    fun courseIsCleanNoOverlapsNoPassThrough() {
        val preset = PresetManager.byId("square.classic")!!
        val life = preset.generation.surfaceLifetimeSec; val fut = preset.generation.futureVisibleSec
        for (aspect in listOf(9f / 16f, 16f / 9f, 1f)) {
            val impacts = runPlanner(physical(), aspect, 99)
            val clean = impacts.filter { !it.degraded }
            var overlaps = 0; var passThrough = 0
            for (i in clean.indices) for (j in i + 1 until clean.size) {
                val a = clean[i]; val b = clean[j]
                if (b.eventTimeSec - fut > a.eventTimeSec + life) break
                val coVisible = b.eventTimeSec - b.futureSec <= a.eventTimeSec + a.lifeSec
                if (coVisible && a.surfaceRect.intersects(b.surfaceRect, 0.0)) overlaps++
            }
            // Path segments vs surfaces visible during them (ignore the two surfaces at the segment ends).
            for (k in 0 until impacts.size - 1) {
                val a = impacts[k]; val b = impacts[k + 1]
                for (s in impacts) {
                    if (s.index == a.index || s.index == b.index || s.degraded) continue
                    val visible = s.eventTimeSec - s.futureSec <= b.eventTimeSec && s.eventTimeSec + s.lifeSec >= a.eventTimeSec
                    if (!visible) continue
                    if (SquareRoutePlanner.segmentHitsRect(a.position, b.position, 0.49, s.surfaceRect)) passThrough++
                }
            }
            assertEquals(0, overlaps, "aspect $aspect overlaps")
            assertEquals(0, passThrough, "aspect $aspect pass-through")
        }
    }

    @Test
    fun sameEventsAndSeedGiveSameRouteRegardlessOfChunking() {
        val ev = physical()
        val a = runPlanner(ev, 9f / 16f, 7, stepSec = 1.0 / 120)
        val b = runPlanner(ev, 9f / 16f, 7, stepSec = 0.37)
        assertEquals(a, b)
        val c = runPlanner(ev, 9f / 16f, 8)
        assertTrue(a != c, "different seed should change the course")
    }

    @Test
    fun routeStaysFramedForPortrait() {
        val ev = physical()
        val preset = PresetManager.byId("square.classic")!!
        val planner = SquareRoutePlanner(ev, preset.generation, 9f / 16f, 1337, ev.first().timeSec - 0.6)
        var t = 0.0
        var worst = 0.0
        while (t < ev.last().timeSec) {
            planner.ensure(t + 1.2)
            val p = planner.positionAt(t)
            val a = planner.anchor(t)
            val ex = (p.x - a.x) / (planner.frameW / 2); val ey = (p.y - a.y) / (planner.frameH / 2)
            worst = maxOf(worst, maxOf(abs(ex), abs(ey)))
            planner.prune(t)
            t += 0.05
        }
        assertTrue(worst < 1.0, "hero left the framed region: $worst")
    }

    @Test
    fun syntheticDenseEventsStillPlan() {
        val ev = (0 until 400).map { i ->
            MusicEvent(i.toLong(), 0.5 + i * 0.1 + (if (i % 3 == 0) 0.02 else 0.0), sourceType = SourceType.SYNTHETIC, importance = 0.5f)
        }
        val impacts = runPlanner(ev, 9f / 16f, 3)
        assertEquals(ev.size, impacts.size)
        val degraded = impacts.count { it.degraded }
        assertTrue(degraded < 12, "degraded $degraded")
    }
}

class SquareCompositionGauntletTest {
    /**
     * Negative-fixture regression: the bad Square had a tiny hero, a small permanent cage in a huge
     * empty 9:16 canvas, random decorative squares and a ghost rectangle. Assert the opposite for
     * every Square preset and aspect, sampled across the whole demo song.
     */
    @Test
    fun heroLargeCourseFillsFrameNoCage() {
        for (preset in com.rhythmphysics.core.preset.PresetManager.forMechanic(com.rhythmphysics.core.mechanic.MechanicType.SQUARE)) {
            for (aspect in com.rhythmphysics.core.render.AspectRatio.values()) {
                val engine = com.rhythmphysics.core.engine.RhythmEngine(
                    Fixtures.demoSession(),
                    com.rhythmphysics.core.session.SceneConfig(aspect = aspect, presetIds = mapOf(preset.mechanic to preset.id)),
                )
                val m = engine.director.slots[0].mechanic as com.rhythmphysics.core.mechanic.square.SquareMechanic
                var t = 10.0
                val coverages = ArrayList<Double>(); val counts = ArrayList<Int>(); var minHero = 1.0
                while (t < 88.0) {
                    engine.update(t)
                    val (hero, cov, n) = m.composition(engine.frame, t)
                    minHero = minOf(minHero, hero); coverages += cov; counts += n
                    t += 0.37
                }
                val medianCov = coverages.sorted()[coverages.size / 2]
                val medianN = counts.sorted()[counts.size / 2]
                println("${preset.id} ${aspect.label}: minHero=%.3f medianCoverage=%.2f medianVisible=$medianN".format(minHero, medianCov))
                assertTrue(minHero >= 0.065, "${preset.id} ${aspect.label}: hero too small ($minHero of short side)")
                assertTrue(medianCov >= 0.30, "${preset.id} ${aspect.label}: course covers only $medianCov of the frame")
                assertTrue(medianN >= 5, "${preset.id} ${aspect.label}: only $medianN surfaces visible")
            }
        }
    }
}
