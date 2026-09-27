package com.rhythmphysics.app

import android.graphics.Bitmap
import android.graphics.Canvas
import com.rhythmphysics.app.render.CanvasRenderer
import com.rhythmphysics.app.render.Letterbox
import com.rhythmphysics.core.engine.RhythmEngine
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
import com.rhythmphysics.core.util.Hashing
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every built-in preset through the *Android* backend ([CanvasRenderer] on a real Skia
 * canvas, via Robolectric native graphics) with exactly the scene the desktop reference renderer
 * uses (`desktop frame <preset> <aspect> <t>`), so the two backends can be compared pixel-wise
 * (tools/compare_backends.py).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasBackendTest {
    private fun demoSession(): RhythmSession {
        val bytes = DemoSong.bytes()
        val midi = MidiParser.parse(bytes)
        return RhythmSession("demo", "asset://demo.mid", Hashing.sha256Hex(bytes), MediaType.MIDI, DemoSong.TITLE, midi.durationSec, MidiEventSource(midi), midi = midi)
    }

    private fun render(presetId: String, aspect: AspectRatio, t: Double, scale: Float = 0.5f): Bitmap {
        val p = PresetManager.byId(presetId)!!
        val e = RhythmEngine(demoSession(), SceneConfig(seed = 1337, aspect = aspect, focus = p.mechanic, presetIds = mapOf(p.mechanic to p.id)))
        e.update(t)
        val dl = DrawList()
        e.render(dl, RenderSettings())
        val w = (e.frame.w * scale).toInt(); val h = (e.frame.h * scale).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        CanvasRenderer().draw(Canvas(bmp), dl, Letterbox.fit(e.frame.w, e.frame.h, w.toFloat(), h.toFloat()))
        e.dispose()
        return bmp
    }

    /** Fraction of pixels that are not (near) the most common colour: a cheap "has content" check. */
    private fun contentFraction(b: Bitmap): Double {
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val bg = px.groupBy { it and 0xF0F0F0 }.maxByOrNull { it.value.size }!!.key
        return px.count { (it and 0xF0F0F0) != bg }.toDouble() / px.size
    }

    @Test
    fun everyPresetRendersThroughAndroidCanvas() {
        val cases = PresetManager.builtIns.map { it.id to 24.0 } +
            listOf("square.classic" to 50.0, "arch.bounce_curve" to 50.0, "platform.music_ball" to 50.0)
        val report = StringBuilder("preset,aspect,time,contentFraction\n")
        for ((id, t) in cases) {
            for (aspect in listOf(AspectRatio.PORTRAIT_9_16, AspectRatio.LANDSCAPE_16_9)) {
                if (aspect == AspectRatio.LANDSCAPE_16_9 && t != 24.0) continue
                val bmp = render(id, aspect, t)
                val tag = if (aspect == AspectRatio.PORTRAIT_9_16) "9x16" else "16x9"
                RoboSupport.save(bmp, "screenshots/android-canvas/${id}_${tag}_t${t.toInt()}.png")
                val cf = contentFraction(bmp)
                report.append("$id,$tag,$t,%.4f\n".format(cf))
                assertTrue("$id $tag rendered an empty frame ($cf)", cf > 0.01)
            }
        }
        java.io.File(RoboSupport.artifacts, "screenshots/android-canvas/content.csv").writeText(report.toString())
    }
}
