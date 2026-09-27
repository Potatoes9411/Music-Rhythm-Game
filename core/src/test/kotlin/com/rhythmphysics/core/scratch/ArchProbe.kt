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
}
