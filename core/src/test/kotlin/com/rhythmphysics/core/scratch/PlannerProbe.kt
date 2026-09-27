package com.rhythmphysics.core.scratch

import com.rhythmphysics.core.Fixtures
import com.rhythmphysics.core.mechanic.square.PlannedSquareImpact
import com.rhythmphysics.core.mechanic.square.SquareRoutePlanner
import com.rhythmphysics.core.music.EventMapper
import com.rhythmphysics.core.music.EventMappingSettings
import com.rhythmphysics.core.music.EventMode
import com.rhythmphysics.core.preset.PresetManager
import kotlin.test.Test

class PlannerProbe {
    @Test
    fun probe() {
        if (System.getProperty("rp.probe") == null) return
        val ev = EventMapper.forMechanic(Fixtures.demoSession().source.events(EventMappingSettings(EventMode.HYBRID, 0.62f)), 0.09).filter { it.role.physical }
        val preset = PresetManager.byId("square.classic")!!
        for ((aspect, seed) in listOf(9f / 16f to 1337L, 1f to 99L)) {
            val p = SquareRoutePlanner(ev, preset.generation, aspect, seed, ev.first().timeSec - 0.6)
            val all = LinkedHashMap<Int, PlannedSquareImpact>()
            var t = 0.0
            while (t < ev.last().timeSec + 1) { p.ensure(t + 1.2); for (i in p.impacts) all.putIfAbsent(i.index, i); p.prune(t); t += 1.0 / 120 }
            val imps = all.values.toList()
            println("aspect $aspect degraded ${p.degradedCount} rejects ${p.rejects.toList()}")
            imps.filter { it.degraded }.forEach { i ->
                val dtIn = if (i.index > 0) ev[i.index].timeSec - ev[i.index - 1].timeSec else -1.0
                val dtOut = if (i.index + 1 < ev.size) ev[i.index + 1].timeSec - ev[i.index].timeSec else -1.0
                println("  degraded #${i.index} t=%.2f dtIn=%.3f dtOut=%.3f".format(i.eventTimeSec, dtIn, dtOut))
            }
            for (k in 0 until imps.size - 1) {
                val a = imps[k]; val b = imps[k + 1]
                for (s in imps) {
                    if (s.index == a.index || s.index == b.index || s.degraded) continue
                    val visible = s.eventTimeSec - s.futureSec <= b.eventTimeSec && s.eventTimeSec + s.lifeSec >= a.eventTimeSec
                    if (visible && SquareRoutePlanner.segmentHitsRect(a.position, b.position, 0.49, s.surfaceRect))
                        println("  PASS-THROUGH seg ${a.index}->${b.index} (t %.2f-%.2f) hits surface #${s.index} (t %.2f win -%.2f/+%.2f)".format(a.eventTimeSec, b.eventTimeSec, s.eventTimeSec, s.futureSec, s.lifeSec))
                }
            }
        }
    }
}
