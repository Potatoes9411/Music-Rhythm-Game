package com.rhythmphysics.core

import com.rhythmphysics.core.engine.RhythmEngine
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.preset.PresetManager
import com.rhythmphysics.core.render.AspectRatio
import com.rhythmphysics.core.session.ReplayManager
import com.rhythmphysics.core.session.SceneConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayTest {
    @Test
    fun exportImportReproducesRunIncludingCustomPreset() {
        val custom = PresetManager.byId("square.neon_trail")!!.let { it.copy(id = "my.neon", name = "My Neon", generation = it.generation.copy(speed = 11.5, surfaceLength = 2.0), seed = 4242) }
        val engine = RhythmEngine(Fixtures.demoSession(), SceneConfig(seed = 777, aspect = AspectRatio.SQUARE_1_1, presetIds = mapOf(MechanicType.SQUARE to "my.neon"))) { id ->
            if (id == "my.neon") custom else PresetManager.byId(id)
        }
        var t = 0.0; while (t < 50) { engine.update(t); t += 1 / 90.0 }
        engine.update(50.0)
        val recipe = ReplayManager.create(engine, verifyAt = 50.0)
        val text = ReplayManager.export(recipe)
        val back = ReplayManager.import(text)
        assertEquals(recipe, back)
        assertTrue(ReplayManager.compatibility(back, Fixtures.demoSession()).isEmpty())
        val replayed = ReplayManager.engineFor(back, Fixtures.demoSession())
        replayed.seek(50.0)
        assertEquals(engine.stateHash(), replayed.stateHash())
        assertEquals(back.verifyHash, replayed.stateHash())
        assertTrue(back.presets.any { it.id == "my.neon" && it.generation.speed == 11.5 })
    }

    @Test
    fun compatibilityFlagsDifferentMedia() {
        val engine = RhythmEngine(Fixtures.demoSession(), SceneConfig())
        val r = ReplayManager.create(engine)
        val other = com.rhythmphysics.core.session.RhythmSession("x", "", "other-fp", com.rhythmphysics.core.session.MediaType.MIDI, "x", 10.0, com.rhythmphysics.core.session.NoEventSource)
        assertTrue(ReplayManager.compatibility(r, other).isNotEmpty())
    }

    @Test
    fun sandboxInputsAreReplayedAndVerified() {
        val session = com.rhythmphysics.core.session.RhythmSession.sandbox(60.0)
        val cfg = SceneConfig(seed = 99, focus = MechanicType.CIRCLE, presetIds = mapOf(MechanicType.CIRCLE to "circle.sandbox"))
        val live = RhythmEngine(session, cfg)
        var t = 0.0
        val taps = listOf(1.0 to (540f to 900f), 2.5 to (400f to 1100f), 4.0 to (700f to 800f))
        var k = 0
        while (t < 8.0) {
            live.update(t)
            if (k < taps.size && t >= taps[k].first) { live.input("tap", taps[k].second.first, taps[k].second.second); k++ }
            t += 1 / 60.0
        }
        live.update(8.0)
        val recipe = ReplayManager.import(ReplayManager.export(ReplayManager.create(live, inputs = live.inputLog.toList(), verifyAt = 8.0)))
        assertEquals(3, recipe.sandboxInputs.size)
        val replayed = ReplayManager.engineFor(recipe, session)
        replayed.loadInputs(recipe.sandboxInputs)
        replayed.seek(8.0)
        assertEquals(live.stateHash(), replayed.stateHash())
        assertEquals(recipe.verifyHash, replayed.stateHash())
        // Without the inputs the state must differ (the taps really changed the simulation).
        val bare = ReplayManager.engineFor(recipe, session); bare.seek(8.0)
        assertTrue(bare.stateHash() != live.stateHash())
    }
}
