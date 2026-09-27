package com.rhythmphysics.core.scratch

import com.rhythmphysics.core.Fixtures
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.mechanic.platform.PlatformCoursePlanner
import com.rhythmphysics.core.mechanic.platform.PlatformStyle
import com.rhythmphysics.core.music.EventMapper
import com.rhythmphysics.core.music.EventMappingSettings
import com.rhythmphysics.core.music.EventMode
import com.rhythmphysics.core.preset.PresetManager
import kotlin.test.Test

class PlatformProbe {
    @Test
    fun probe() {
        if (System.getProperty("rp.probe") == null) return
        val ev = EventMapper.forMechanic(Fixtures.demoSession().source.events(EventMappingSettings(EventMode.HYBRID, 0.6f)), 0.12).filter { it.role.physical }
        val gen = PresetManager.byId("platform.piano_tiles")!!.generation
        val p = PlatformCoursePlanner(ev, gen, PlatformStyle.PIANO_TILES, 16f / 9f, 3, ev.first().timeSec - 0.7, 26.0)
        p.ensure(1e9)
        println("notes " + ev.mapNotNull { it.midiNote }.let { "${it.minOrNull()}..${it.maxOrNull()} nulls=${ev.count { e -> e.midiNote == null }}" } + " corridor ${p.corridor} rejected ${p.rejected}")
        println("x: " + p.contacts.take(40).joinToString(" ") { "%.1f".format(it.position.x) })
    }
}
