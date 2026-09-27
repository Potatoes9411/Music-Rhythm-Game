package com.rhythmphysics.core.scratch

import com.rhythmphysics.core.Fixtures
import com.rhythmphysics.core.engine.RhythmEngine
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.mechanic.arch.ArchMechanic
import com.rhythmphysics.core.render.AspectRatio
import com.rhythmphysics.core.session.SceneConfig
import kotlin.test.Test

class ArchProbe {
    @Test
    fun probe() {
        if (System.getProperty("rp.probe") == null) return
        val e = RhythmEngine(Fixtures.demoSession(), SceneConfig(aspect = AspectRatio.PORTRAIT_9_16, focus = MechanicType.ARCH, presetIds = mapOf(MechanicType.ARCH to "arch.bounce_curve")))
        e.update(30.0)
        val m = e.director.slots[0].mechanic as ArchMechanic
        val p = m.planner
        println("hero ${p.heroAt(30.0)} fwd ${p.forward} lat ${p.lateral}")
        println("next " + p.targetsAfter(30.0, 3).joinToString { "(%.2f,%.2f,%.2f @%.2f)".format(it.x, it.topY, it.z, it.timeSec) })
        println("last " + p.lastTargetAtOrBefore(30.0)?.let { "(%.2f,%.2f,%.2f @%.2f)".format(it.x, it.topY, it.z, it.timeSec) })
        println("hs " + m.heroScreenPosition(e.frame, 30.0)?.toList())
        println("comp " + m.composition(e.frame, 30.0).toList())
    }

    @Test
    fun worstContact() {
        if (System.getProperty("rp.probe") == null) return
        val e = RhythmEngine(Fixtures.demoSession(), SceneConfig(focus = MechanicType.ARCH, presetIds = mapOf(MechanicType.ARCH to "arch.bounce_curve")))
        var t = 0.0; while (t < 92) { e.update(t); t += 1 / 60.0 }
        val worst = e.sync.samples().sortedByDescending { kotlin.math.abs(it.errorMs) }.take(3)
        worst.forEach { println("worst id=${it.eventId} expected=%.4f actual=%.4f err=%.3f".format(it.expectedTimeSec, it.logicalContactTimeSec, it.errorMs)) }
        println("first samples " + e.sync.samples().take(3).map { "%.3f".format(it.expectedTimeSec) })
    }
}
