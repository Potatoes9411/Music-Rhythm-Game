package com.rhythmphysics.core.engine

import com.rhythmphysics.core.render.Colors
import com.rhythmphysics.core.render.DrawList
import com.rhythmphysics.core.render.RenderSettings

/** Developer overlay (never drawn in clean/creator output). */
object DebugOverlay {
    fun draw(dl: DrawList, engine: RhythmEngine, rs: RenderSettings) {
        val f = engine.frame
        val size = f.unit * 0.024f
        val lines = ArrayList<String>()
        val slot = engine.director.primarySlot(engine.renderTime)
        val drift = (engine.renderTime - engine.simTime) * 1000
        lines += "audio %.3fs  sim %.3fs  lead %.1fms".format(engine.renderTime, engine.simTime, drift)
        lines += "mode ${engine.config.viewMode.label}  mech ${slot?.type?.label ?: "-"}"
        lines += "preset ${slot?.preset?.name ?: "-"}"
        lines += "seed ${engine.config.seed}  aspect ${engine.config.aspect.label}"
        val phys = slot?.mechanic?.let { engine.director.slots.size } ?: 0
        lines += "slots $phys  steps/frame ${engine.lastSteps}  draw cmds ${engine.frameStats.drawCommands}"
        val events = engine.session.source.events(slot?.preset?.eventMapping ?: engine.config.eventMapping ?: com.rhythmphysics.core.music.EventMappingSettings())
        val next = events.firstOrNull { it.timeSec > engine.renderTime && it.role.physical }
        lines += "events ${events.size}  next ${next?.let { "%.3fs".format(it.timeSec) } ?: "-"}"
        engine.sync.last?.let { s ->
            lines += "contact exp %.3f act %.4f err %.3fms".format(s.expectedTimeSec, s.logicalContactTimeSec, s.errorMs)
        }
        val st = engine.sync.stats()
        lines += "sync n=${st.count} mean %.3f p95 %.3f max %.3f ms  degraded ${st.degraded}".format(st.meanAbsMs, st.p95AbsMs, st.maxAbsMs)
        slot?.mechanic?.let { m -> lines += "bodies ${m.bodyCount}"; lines += m.debugLines() }
        engine.debugProvider?.invoke()?.let { lines += it }
        val h = size * 1.35f * lines.size + size
        val w = f.unit * 0.92f
        dl.rect(f.x + size * 0.5f, f.y + size * 3f, w, h, Colors.withAlpha(Colors.BLACK, 0.62f), size * 0.4f)
        lines.forEachIndexed { i, s ->
            dl.text(f.x + size, f.y + size * 3f + size * 1.35f * (i + 1), size, 0xFFB8FFB0.toInt(), s)
        }
    }
}
