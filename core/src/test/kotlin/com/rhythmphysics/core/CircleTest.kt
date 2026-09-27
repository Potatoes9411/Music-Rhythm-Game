package com.rhythmphysics.core

import com.rhythmphysics.core.engine.RhythmEngine
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.mechanic.circle.CircleMechanic
import com.rhythmphysics.core.mechanic.circle.CircleWorld
import com.rhythmphysics.core.preset.PhysicsParams
import com.rhythmphysics.core.preset.PresetManager
import com.rhythmphysics.core.session.ReplayManager
import com.rhythmphysics.core.session.RhythmSession
import com.rhythmphysics.core.session.SceneConfig
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CircleTest {
    @Test
    fun elasticRingConservesSpeedAndReflectsAboutNormal() {
        val w = CircleWorld(PhysicsParams(gravity = 0.0, restitution = 1.0, substeps = 4, maxSpeed = 1000.0), 1)
        val b = w.spawnBall(1.0, 2.0, 13.0, -7.0, 0.5)!!
        val sp0 = sqrt(b.vx * b.vx + b.vy * b.vy)
        var t = 0.0
        repeat(120 * 30) { t += 1 / 120.0; w.step(1 / 120.0, t) }
        val sp1 = sqrt(b.vx * b.vx + b.vy * b.vy)
        assertTrue(w.collisions > 15, "collisions ${w.collisions}")
        assertEquals(sp0, sp1, 1e-6 * sp0, "elastic reflections must conserve speed")
        assertTrue(sqrt(b.x * b.x + b.y * b.y) <= w.ringR - b.r + 1e-6)
    }

    @Test
    fun noTunnelingAtExtremeSpeed() {
        val w = CircleWorld(PhysicsParams(gravity = 40.0, restitution = 1.0, substeps = 1, maxSpeed = 5000.0, ballCollisions = false), 2)
        repeat(20) { i -> w.spawnBall(0.0, 0.0, 800.0 * kotlin.math.cos(i * 0.7), 800.0 * kotlin.math.sin(i * 0.7), 0.3) }
        var t = 0.0
        repeat(120 * 10) { t += 1 / 120.0; w.step(1 / 120.0, t) }
        assertEquals(0L, w.escapes)
        assertTrue(w.balls.all { sqrt(it.x * it.x + it.y * it.y) <= w.ringR - it.r + 1e-5 })
    }

    @Test
    fun containmentHoldsForAllClosedRingPresets() {
        for (p in PresetManager.forMechanic(MechanicType.CIRCLE).filter { it.physics.gapCount == 0 && it.anomalies.none { r -> r.type.name.startsWith("GAP") || r.type.name == "RING_BREAK" || r.type.name == "MULTIPLE_GAPS" } }) {
            val e = RhythmEngine(Fixtures.demoSession(), SceneConfig(focus = MechanicType.CIRCLE, presetIds = mapOf(MechanicType.CIRCLE to p.id)))
            val m = e.director.slots[0].mechanic as CircleMechanic
            var t = 0.0; var worst = 0.0
            while (t < 60) {
                e.update(t)
                for (b in m.world.balls) worst = maxOf(worst, sqrt(b.x * b.x + b.y * b.y) - (m.world.ringR - b.r))
                t += 1 / 30.0
            }
            assertTrue(worst < 1e-3, "${p.id}: ball outside ring by $worst")
            assertEquals(0L, m.world.escapes, "${p.id} leaked")
        }
    }

    @Test
    fun deterministicAnomaliesSeekAndReplayForEveryPreset() {
        for (p in PresetManager.forMechanic(MechanicType.CIRCLE)) {
            val cfg = SceneConfig(seed = 99, focus = MechanicType.CIRCLE, presetIds = mapOf(MechanicType.CIRCLE to p.id))
            val a = RhythmEngine(Fixtures.demoSession(), cfg); var t = 0.0; while (t < 45) { a.update(t); t += 1 / 60.0 }; a.update(45.0)
            val b = RhythmEngine(Fixtures.demoSession(), cfg); t = 0.0; while (t < 45) { b.update(t); t += 1 / 144.0 }; b.update(45.0)
            assertEquals(a.stateHash(), b.stateHash(), "${p.id}: display rate changed the simulation")
            val c = RhythmEngine(Fixtures.demoSession(), cfg); c.seek(20.0); c.seek(45.0)
            assertEquals(a.stateHash(), c.stateHash(), "${p.id}: seek mismatch")
            val ma = a.director.slots[0].mechanic as CircleMechanic
            val mb = b.director.slots[0].mechanic as CircleMechanic
            assertEquals(ma.world.anomalyLog, mb.world.anomalyLog, p.id)
        }
    }

    @Test
    fun sandboxInputsReplayExactly() {
        val session = RhythmSession.sandbox(120.0)
        val cfg = SceneConfig(focus = MechanicType.CIRCLE, presetIds = mapOf(MechanicType.CIRCLE to "circle.sandbox"))
        val e = RhythmEngine(session, cfg)
        var t = 0.0
        val taps = listOf(1.0 to (540f to 900f), 2.5 to (400f to 1000f), 4.0 to (700f to 800f))
        var ti = 0
        while (t < 8) {
            if (ti < taps.size && t >= taps[ti].first) { e.input("tap", taps[ti].second.first, taps[ti].second.second); ti++ }
            e.update(t); t += 1 / 60.0
        }
        e.update(8.0)
        val m = e.director.slots[0].mechanic as CircleMechanic
        assertEquals(4, m.world.balls.size, "1 initial + 3 tapped balls")
        val recipe = ReplayManager.create(e, inputs = e.inputLog.toList())
        val back = ReplayManager.import(ReplayManager.export(recipe))
        val r = ReplayManager.engineFor(back, session)
        r.loadInputs(back.sandboxInputs)
        r.seek(8.0)
        assertEquals(e.stateHash(), r.stateHash())
        @Suppress("UNUSED_VARIABLE") val u = abs(0)
    }
}
