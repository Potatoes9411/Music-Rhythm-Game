package com.rhythmphysics.desktop

import com.rhythmphysics.core.engine.RhythmEngine
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.midi.DemoSong
import com.rhythmphysics.core.midi.MidiParser
import com.rhythmphysics.core.preset.PresetManager
import com.rhythmphysics.core.render.AspectRatio
import com.rhythmphysics.core.render.DrawList
import com.rhythmphysics.core.render.RenderSettings
import com.rhythmphysics.core.session.MediaType
import com.rhythmphysics.core.session.MidiEventSource
import com.rhythmphysics.core.session.RhythmSession
import com.rhythmphysics.core.session.SceneConfig
import com.rhythmphysics.core.session.ViewMode
import com.rhythmphysics.core.util.Hashing
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object Scenes {
    fun demoSession(): RhythmSession {
        val bytes = DemoSong.bytes()
        val midi = MidiParser.parse(bytes)
        return RhythmSession("demo", "asset://demo.mid", Hashing.sha256Hex(bytes), MediaType.MIDI, DemoSong.TITLE, midi.durationSec, MidiEventSource(midi), midi = midi)
    }

    fun engine(session: RhythmSession, preset: String?, aspect: AspectRatio, mode: ViewMode = ViewMode.FOCUS, seed: Long = 1337, focus: MechanicType? = null): RhythmEngine {
        val p = preset?.let { PresetManager.byId(it) ?: error("unknown preset $it") }
        val type = focus ?: p?.mechanic ?: MechanicType.SQUARE
        val ids = if (p != null) mapOf(p.mechanic to p.id) else emptyMap()
        return RhythmEngine(session, SceneConfig(seed = seed, aspect = aspect, viewMode = mode, focus = type, presetIds = ids))
    }

    fun frame(engine: RhythmEngine, t: Double, scale: Float = 0.5f, rs: RenderSettings = RenderSettings()): BufferedImage {
        engine.update(t)
        val dl = DrawList()
        engine.render(dl, rs)
        val w = (engine.frame.w * scale).toInt(); val h = (engine.frame.h * scale).toInt()
        return Java2DRenderer(w, h).render(dl)
    }

    fun sheet(frames: List<BufferedImage>, labels: List<String>, cols: Int): BufferedImage {
        val fw = frames[0].width; val fh = frames[0].height
        val rows = (frames.size + cols - 1) / cols
        val pad = 8; val labelH = 22
        val out = BufferedImage(cols * (fw + pad) + pad, rows * (fh + pad + labelH) + pad, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.color = Color(40, 40, 44); g.fillRect(0, 0, out.width, out.height)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
        frames.forEachIndexed { i, f ->
            val x = pad + (i % cols) * (fw + pad); val y = pad + (i / cols) * (fh + pad + labelH)
            g.color = Color.WHITE; g.drawString(labels[i], x, y + 15)
            g.drawImage(f, x, y + labelH, null)
        }
        g.dispose()
        return out
    }

    fun save(img: BufferedImage, file: File) {
        file.parentFile.mkdirs()
        ImageIO.write(img, "png", file)
        println("wrote ${file.path} (${img.width}x${img.height})")
    }
}

fun main(args: Array<String>) {
    val out = File(args.getOrNull(1) ?: "artifacts/screenshots")
    when (args.firstOrNull() ?: "square") {
        "frame" -> {
            // frame <preset> <aspect: 9x16|16x9|1x1> <time> [file]
            val aspect = when (args[2]) { "16x9" -> AspectRatio.LANDSCAPE_16_9; "1x1" -> AspectRatio.SQUARE_1_1; else -> AspectRatio.PORTRAIT_9_16 }
            val e = Scenes.engine(Scenes.demoSession(), args[1], aspect)
            Scenes.save(Scenes.frame(e, args[3].toDouble()), File(args.getOrElse(4) { "artifacts/tmp/frame.png" }))
        }
        "batch" -> {
            // batch <list-file>; each line: <preset> <aspect> <time> <out.png>  (one JVM for many frames)
            File(args[1]).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val (preset, asp, t, out) = line.split(Regex("\\s+"))
                val aspect = when (asp) { "16x9" -> AspectRatio.LANDSCAPE_16_9; "1x1" -> AspectRatio.SQUARE_1_1; else -> AspectRatio.PORTRAIT_9_16 }
                Scenes.save(Scenes.frame(Scenes.engine(Scenes.demoSession(), preset, aspect), t.toDouble()), File(out))
            }
        }
        "sheet" -> {
            // sheet <preset> <aspect> <t0> <dt> <n> <file> [debug]
            val aspect = when (args[2]) { "16x9" -> AspectRatio.LANDSCAPE_16_9; "1x1" -> AspectRatio.SQUARE_1_1; else -> AspectRatio.PORTRAIT_9_16 }
            val e = Scenes.engine(Scenes.demoSession(), args[1], aspect)
            val t0 = args[3].toDouble(); val dt = args[4].toDouble(); val n = args[5].toInt()
            val rs = RenderSettings(showDebug = args.getOrNull(7) == "debug")
            val scale = if (aspect.isPortrait) 0.3f else 0.22f
            val frames = (0 until n).map { Scenes.frame(e, t0 + it * dt, scale, rs) }
            Scenes.save(Scenes.sheet(frames, (0 until n).map { "t=%.2fs".format(t0 + it * dt) }, if (aspect.isPortrait) minOf(n, 6) else minOf(n, 3)), File(args[6]))
        }
        "sheetmode" -> {
            // sheetmode <journey|duet|quad> <aspect> <t0> <dt> <n> <file>
            val aspect = when (args[2]) { "16x9" -> AspectRatio.LANDSCAPE_16_9; "1x1" -> AspectRatio.SQUARE_1_1; else -> AspectRatio.PORTRAIT_9_16 }
            val mode = when (args[1]) { "journey" -> ViewMode.JOURNEY; "duet" -> ViewMode.DUET; else -> ViewMode.QUAD }
            val e = Scenes.engine(Scenes.demoSession(), null, aspect, mode)
            e.director.journey?.segments?.forEach { println("segment %.2f-%.2f %s %s".format(it.startSec, it.endSec, it.mechanic, it.presetId)) }
            val t0 = args[3].toDouble(); val dt = args[4].toDouble(); val n = args[5].toInt()
            val scale = if (aspect.isPortrait) 0.3f else 0.22f
            val frames = (0 until n).map { Scenes.frame(e, t0 + it * dt, scale) }
            Scenes.save(Scenes.sheet(frames, (0 until n).map { "t=%.2fs".format(t0 + it * dt) }, if (aspect.isPortrait) minOf(n, 6) else minOf(n, 3)), File(args[6]))
        }
        else -> error("unknown command")
    }
    System.exit(0)
}
