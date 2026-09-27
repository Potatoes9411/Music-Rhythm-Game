package com.rhythmphysics.core

import com.rhythmphysics.core.engine.RhythmEngine
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.mechanic.arch.ArchMechanic
import com.rhythmphysics.core.mechanic.arch.ArchPlanner
import com.rhythmphysics.core.mechanic.arch.ArchStyle
import com.rhythmphysics.core.music.EventMapper
import com.rhythmphysics.core.music.EventMappingSettings
import com.rhythmphysics.core.music.EventMode
import com.rhythmphysics.core.preset.PresetManager
import com.rhythmphysics.core.render.AspectRatio
import com.rhythmphysics.core.session.SceneConfig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchTest {
    private fun events() = EventMapper.forMechanic(Fixtures.demoSession().source.events(EventMappingSettings(EventMode.HYBRID, 0.5f)), 0.2).filter { it.role.physical }

    @Test
    fun ballisticSolutionIsExactAndDeterministic() {
        val ev = events()
        val gen = PresetManager.byId("arch.bounce_curve")!!.generation
        val a = ArchPlanner(ev, gen, ArchStyle.BOUNCE_CURVE, 9f / 16f, 5, ev.first().timeSec - 0.8)
        val b = ArchPlanner(ev, gen, ArchStyle.BOUNCE_CURVE, 9f / 16f, 5, ev.first().timeSec - 0.8)
        a.ensure(1e9); b.ensure(1e9)
        assertEquals(a.targets, b.targets)
        assertEquals(a.segments, b.segments)
        for ((i, s) in a.segments.withIndex()) {
            assertTrue(!s.guided)
            val end = s.at(s.t1, a.lateral)
            assertTrue((end - s.p1).length < 1e-6, "segment $i misses its target by ${(end - s.p1).length}")
            // p(t) = p0 + v0 t + 1/2 g t^2 and v0 = (p1 - p0 - 1/2 g T^2)/T
            val T = s.t1 - s.t0
            val v0y = (s.p1.y - s.p0.y + 0.5 * s.g * T * T) / T
            assertEquals(v0y, s.v0.y, 1e-9)
            assertTrue(s.g in 4.9..95.1, "gravity ${s.g}")
            assertEquals(a.targets[i].timeSec, s.t1, 0.0)
        }
    }

    @Test
    fun archSyncAndSeekDeterminism() {
        for (id in listOf("arch.bounce_curve", "arch.pillar_weave")) {
            val cfg = SceneConfig(focus = MechanicType.ARCH, presetIds = mapOf(MechanicType.ARCH to id))
            val e = RhythmEngine(Fixtures.demoSession(), cfg)
            var t = 0.0; while (t < 70) { e.update(t); t += 1 / 60.0 }
            e.update(70.0)
            val st = e.sync.stats()
            println("$id sync: n=${st.count} mean %.3f p95 %.3f max %.3f".format(st.meanAbsMs, st.p95AbsMs, st.maxAbsMs))
            assertTrue(st.count > 40 && st.passesPlannedGoal, "$id $st")
            val e2 = RhythmEngine(Fixtures.demoSession(), cfg)
            e2.seek(40.0); e2.seek(70.0)
            assertEquals(e.stateHash(), e2.stateHash(), id)
        }
    }

    /**
     * Negative-fixture regression (bad Arch: giant dead black area, content compressed at the bottom,
     * tiny distant pillars, trail dominating). Assert readable framing for every preset and aspect.
     */
    @Test
    fun cameraDirectorComposition() {
        for (id in listOf("arch.bounce_curve", "arch.pillar_weave")) for (aspect in AspectRatio.values()) {
            val e = RhythmEngine(Fixtures.demoSession(), SceneConfig(aspect = aspect, focus = MechanicType.ARCH, presetIds = mapOf(MechanicType.ARCH to id)))
            val m = e.director.slots[0].mechanic as ArchMechanic
            val cov = ArrayList<Double>(); val cy = ArrayList<Double>(); val tsz = ArrayList<Double>(); val hsz = ArrayList<Double>(); val inf = ArrayList<Double>()
            var t = 10.0
            while (t < 85) {
                e.update(t)
                val c = m.composition(e.frame, t)
                cov += c[0]; cy += c[1]; tsz += c[2]; hsz += c[3]; inf += c[4]
                t += 0.43
            }
            fun med(l: List<Double>) = l.sorted()[l.size / 2]
            fun p10(l: List<Double>) = l.sorted()[l.size / 10]
            println("$id ${aspect.label}: coverage %.2f centerY %.2f target %.3f hero %.3f inFrame p10 %.2f".format(med(cov), med(cy), p10(tsz), p10(hsz), p10(inf)))
            // Coverage counts only hero + arc apex + the next three targets (further targets that also
            // fill the frame are not counted), so 0.20 is a conservative floor; see artifacts/screenshots/arch.
            assertTrue(med(cov) >= 0.20, "$id ${aspect.label} coverage ${med(cov)}")
            assertTrue(med(cy) in 0.3..0.72, "$id ${aspect.label} content center y ${med(cy)} (bottom strip?)")
            assertTrue(p10(tsz) >= 0.03, "$id ${aspect.label} targets too small ${p10(tsz)}")
            assertTrue(p10(hsz) >= 0.02, "$id ${aspect.label} hero too small ${p10(hsz)}")
            assertTrue(p10(inf) >= 0.75, "$id ${aspect.label} upcoming course leaves the frame ${p10(inf)}")
            @Suppress("UNUSED_VARIABLE") val u = abs(0.0)
        }
    }
}
