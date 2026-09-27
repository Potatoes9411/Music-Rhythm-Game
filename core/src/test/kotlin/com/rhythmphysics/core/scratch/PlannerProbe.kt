package com.rhythmphysics.core.scratch

import com.rhythmphysics.core.mechanic.square.SquareRoutePlanner
import com.rhythmphysics.core.music.MusicEvent
import com.rhythmphysics.core.music.SourceType
import com.rhythmphysics.core.preset.PresetManager
import kotlin.test.Test

class PlannerProbe {
    @Test
    fun probe() {
        if (System.getProperty("rp.probe") == null) return
        val ev = (0 until 400).map { i -> MusicEvent(i.toLong(), 0.5 + i * 0.1 + (if (i % 3 == 0) 0.02 else 0.0), sourceType = SourceType.SYNTHETIC, importance = 0.5f) }
        val preset = PresetManager.byId("square.classic")!!
        for (aspect in listOf(9f / 16f, 1f, 16f / 9f)) {
            val p = SquareRoutePlanner(ev, preset.generation, aspect, 3, ev.first().timeSec - 0.6)
            var t = 0.0
            val seen = HashSet<Int>(); var firstDeg = -1
            while (t < 41.0) {
                p.ensure(t + 1.2)
                for (i in p.impacts) if (seen.add(i.index) && i.degraded && firstDeg < 0) { firstDeg = i.index; println("first degraded #${i.index} pos=${i.position} leash=${i.leash}") }
                p.prune(t); t += 0.05
            }
            println("aspect $aspect degraded ${p.degradedCount} rejects ${p.rejects.toList()}")
        }
    }
}
