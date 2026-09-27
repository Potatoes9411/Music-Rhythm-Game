package com.rhythmphysics.core

import com.rhythmphysics.core.preset.PresetManager
import com.rhythmphysics.core.preset.PresetValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PresetTest {
    @Test
    fun allBuiltInsRoundTripThroughJson() {
        assertTrue(PresetManager.builtIns.size >= 4)
        assertEquals(PresetManager.builtIns.size, PresetManager.builtIns.map { it.id }.toSet().size, "duplicate ids")
        for (p in PresetManager.builtIns) {
            val json = PresetManager.export(p)
            assertEquals(p, PresetManager.import(json), p.id)
            assertTrue(json.contains("\"schemaVersion\": 1"))
        }
    }

    @Test
    fun importValidatesAndClamps() {
        val p = PresetManager.import("""{"schemaVersion":1,"id":"x y!","name":"Evil","mechanic":"SQUARE","generation":{"speed":1e9,"lookahead":-5},"visuals":{"bloom":99,"trailColor":"javascript:alert(1)"},"unknownField":{"a":1}}""")
        assertEquals("xy", p.id)
        assertEquals(40.0, p.generation.speed)
        assertEquals(8, p.generation.lookahead)
        assertEquals(1.5, p.visuals.bloom)
        assertEquals(null, p.visuals.trailColor)
        assertFailsWith<PresetValidationException> { PresetManager.import("""{"schemaVersion":99,"id":"a","name":"a","mechanic":"SQUARE"}""") }
        assertFailsWith<PresetValidationException> { PresetManager.import("""{"id":"a","name":"a","mechanic":"TELEPORT"}""") }
        assertFailsWith<PresetValidationException> { PresetManager.import("not json") }
    }
}
