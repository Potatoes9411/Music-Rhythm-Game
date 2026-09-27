package com.rhythmphysics.app

import android.app.Dialog
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import com.rhythmphysics.app.RoboSupport.capture
import com.rhythmphysics.app.RoboSupport.findByDescription
import com.rhythmphysics.app.RoboSupport.findText
import com.rhythmphysics.app.RoboSupport.save
import com.rhythmphysics.app.RoboSupport.waitUntil
import com.rhythmphysics.app.render.CanvasRenderer
import com.rhythmphysics.app.render.Letterbox
import com.rhythmphysics.app.ui.CreatorScreen
import com.rhythmphysics.core.engine.RhythmEngine
import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.render.AspectRatio
import com.rhythmphysics.core.render.DrawList
import com.rhythmphysics.core.render.Quality
import com.rhythmphysics.core.session.MediaType
import com.rhythmphysics.core.session.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

/**
 * Drives the real app (MainActivity, Home/Creator screens, dialogs, stores, SessionLoader with the
 * bundled SoundFont) on Robolectric. Audio output, the SurfaceView surface and MediaCodec are
 * simulated by Robolectric, so these tests prove wiring and UI behaviour, not device timing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppFlowTest {

    private fun click(root: View, text: String) {
        val v = findText(root, text) ?: error("No view with text '$text'. Visible: ${RoboSupport.allTexts(root).take(60)}")
        val handled = v.performClick()
        // CompoundButton toggles but reports "handled" only when an OnClickListener exists.
        if (v !is android.widget.CompoundButton) assertTrue("'$text' should be clickable", handled)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun clickDesc(root: View, d: String) {
        val v = findByDescription(root, d) ?: error("No view described '$d'")
        assertTrue(v.performClick())
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitReady(cs: CreatorScreen, what: String) = waitUntil(120_000, what) { cs.engineReady }

    /** Screen capture with the SurfaceView area filled by the same engine + CanvasRenderer the surface uses. */
    private fun captureCreator(act: MainActivity, cs: CreatorScreen, t: Double, file: String) {
        val root = act.window.decorView
        val engine = RhythmEngine(cs.session, cs.scene, presetResolver = cs.resolver)
        engine.update(t)
        val dl = DrawList()
        engine.render(dl, cs.userRender)
        val vis = cs.visualizer
        val loc = IntArray(2); vis.getLocationInWindow(loc)
        save(capture(root) { c: Canvas ->
            c.save()
            c.clipRect(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + vis.width).toFloat(), (loc[1] + vis.height).toFloat())
            c.translate(loc[0].toFloat(), loc[1].toFloat())
            CanvasRenderer().draw(c, dl, Letterbox.fit(engine.frame.w, engine.frame.h, vis.width.toFloat(), vis.height.toFloat()))
            c.restore()
        }, file)
        engine.dispose()
    }

    private fun latestDialog(): Dialog = ShadowDialog.getLatestDialog() ?: error("no dialog shown")

    @org.junit.Before
    fun logToStdout() { org.robolectric.shadows.ShadowLog.stream = System.out }

    @Test
    fun bundledSoundFontIsPackagedAsAsset() {
        val am = org.robolectric.RuntimeEnvironment.getApplication().assets
        val names = am.list("soundfonts")?.toList() ?: emptyList()
        assertTrue("assets/soundfonts: $names", "GeneralUser-GS.sf2" in names && "GeneralUser-LICENSE.txt" in names)
        am.open("soundfonts/GeneralUser-GS.sf2").use { val h = ByteArray(12); it.read(h); assertEquals("RIFF", String(h, 0, 4)); assertEquals("sfbk", String(h, 8, 4)) }
    }

    @Test
    fun homeScreenLaunches() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = act.window.decorView
        assertNotNull(act.homeScreen)
        for (label in listOf("Load audio file", "Load MIDI file", "Demo song", "Sandbox", "My presets…")) assertNotNull(label, findText(root, label))
        save(capture(root), "screenshots/android/home_portrait.png")
    }

    @Test
    fun demoSongCreatorWorkflow() {
        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = ctrl.get()
        RoboSupport.diagnostics = {
            val d = ShadowDialog.getLatestDialog()
            "screen=" + RoboSupport.allTexts(act.window.decorView).take(40) + " dialog=" + (d?.window?.decorView?.let { RoboSupport.allTexts(it) } ?: "none")
        }
        click(act.window.decorView, "Demo song")
        waitUntil(180_000, "demo loaded") { act.creatorScreen != null }
        val cs = act.creatorScreen!!
        waitReady(cs, "square engine")
        assertEquals(MediaType.MIDI, cs.session.mediaType)
        assertEquals(ViewMode.FOCUS, cs.scene.viewMode)
        val root = act.window.decorView
        for (label in listOf("Square", "Circle", "Arch", "Platform", "Journey", "Single", "Duet", "Quad", "9:16", "16:9", "1:1")) assertNotNull("chip $label", findText(root, label))

        // Seek (paused) and capture the Square preview.
        cs.seekTo(21.0)
        assertEquals(21.0, cs.position(), 1e-6)
        captureCreator(act, cs, 21.0, "screenshots/android/creator_square_portrait.png")

        // Mechanic selector: Arch (engine rebuilt in the background, audio position kept).
        click(root, "Arch"); waitReady(cs, "arch engine")
        assertEquals(MechanicType.ARCH, cs.scene.focus)
        assertEquals(21.0, cs.position(), 1e-6)
        captureCreator(act, cs, 21.0, "screenshots/android/creator_arch_portrait.png")

        // Preset chip for Arch: Pillar Weave.
        click(root, "Pillar Weave"); waitReady(cs, "pillar engine")
        assertEquals("arch.pillar_weave", cs.presetIdFor(MechanicType.ARCH))

        click(root, "Circle"); waitReady(cs, "circle engine")
        assertEquals(MechanicType.CIRCLE, cs.scene.focus)
        click(root, "Platform"); waitReady(cs, "platform engine")
        captureCreator(act, cs, 21.0, "screenshots/android/creator_platform_portrait.png")

        // Layouts.
        click(root, "Duet"); waitReady(cs, "duet")
        assertEquals(ViewMode.DUET, cs.scene.viewMode)
        assertEquals(listOf(MechanicType.PLATFORM, MechanicType.SQUARE), cs.scene.duet)
        assertNotNull(findText(root, "DUET PARTNER"))
        click(root, "Quad"); waitReady(cs, "quad")
        assertEquals(ViewMode.QUAD, cs.scene.viewMode)
        captureCreator(act, cs, 21.0, "screenshots/android/creator_quad_portrait.png")
        click(root, "Journey"); waitReady(cs, "journey")
        assertEquals(ViewMode.JOURNEY, cs.scene.viewMode)
        assertNull("layout row hidden in journey", findText(root, "Duet")?.takeIf { it.isShown })

        // Aspect ratio.
        click(root, "Square"); click(root, "Single"); waitReady(cs, "square single")
        click(root, "16:9"); waitReady(cs, "landscape")
        assertEquals(AspectRatio.LANDSCAPE_16_9, cs.scene.aspect)
        captureCreator(act, cs, 21.0, "screenshots/android/creator_square_16x9_in_portrait_ui.png")
        click(root, "9:16"); waitReady(cs, "portrait")

        // Seed: dice changes the seed and rebuilds.
        val seed = cs.scene.seed
        clickDesc(root, "Random seed"); waitReady(cs, "reseed")
        assertTrue(cs.scene.seed != seed)

        // Debug overlay toggle.
        clickDesc(root, "Debug overlay")
        assertTrue(cs.userRender.showDebug)
        captureCreator(act, cs, 21.0, "screenshots/android/creator_debug_overlay.png")
        clickDesc(root, "Debug overlay")
        assertFalse(cs.userRender.showDebug)

        // Settings dialog: quality chip + accessibility switch act immediately.
        clickDesc(root, "Settings")
        val settings = latestDialog()
        assertTrue(settings.isShowing)
        val sroot = settings.window!!.decorView
        click(sroot, "Low")
        assertEquals(Quality.LOW, cs.userRender.quality)
        assertEquals("Low renders a smaller buffer", 0.6f, cs.visualizer.resolutionScale, 1e-6f)
        click(sroot, "Reduced flash")
        assertTrue(cs.userRender.reducedFlash)
        click(sroot, "Song + collision sounds")
        assertEquals(com.rhythmphysics.core.session.AudioMode.ORIGINAL_AND_COLLISION_LAYER, cs.scene.audioMode)
        click(sroot, "Percussive"); waitReady(cs, "event mode")
        assertEquals(com.rhythmphysics.core.music.EventMode.PERCUSSIVE, cs.scene.eventMapping?.mode)
        save(capture(sroot), "screenshots/android/settings_dialog.png")
        settings.dismiss(); shadowOf(Looper.getMainLooper()).idle()
        // Settings persist.
        assertEquals(Quality.LOW, act.app.settings.renderSettings.quality)

        // Export: first a device without a usable encoder, then one that supports every size.
        com.rhythmphysics.app.ui.CreatorDialogs.encoderSupports = { _, _, _ -> false }
        clickDesc(root, "Export video")
        val unavailable = latestDialog()
        assertNotNull(findText(unavailable.window!!.decorView, "Export unavailable"))
        unavailable.dismiss(); shadowOf(Looper.getMainLooper()).idle()
        com.rhythmphysics.app.ui.CreatorDialogs.encoderSupports = { w, h, fps -> w * h <= 1920 * 1080 && fps <= 60 }
        clickDesc(root, "Export video")
        val export = latestDialog()
        val eroot = export.window!!.decorView
        assertNotNull("export dialog: " + RoboSupport.allTexts(eroot), findText(eroot, "RESOLUTION"))
        assertNotNull(findText(eroot, "1080 × 1920 (9:16)"))
        assertNotNull(findText(eroot, "60 fps"))
        assertNull("16:9 sizes are not offered for a 9:16 scene", findText(eroot, "1920 × 1080 (16:9)"))
        save(capture(eroot), "screenshots/android/export_dialog.png")
        export.dismiss(); shadowOf(Looper.getMainLooper()).idle()

        // Transport with the song clock. Robolectric's AudioTrack consumes PCM instantly, so playback
        // may "complete" before we look: either it is playing, or it completed into a clean paused
        // state at the end with the Play button restored. (Deterministic transport: sandbox test.)
        clickDesc(root, "Play")
        if (cs.clock.isPlaying) clickDesc(root, "Pause")
        waitUntil(20_000, "paused") { !cs.clock.isPlaying }
        RoboSupport.idle(100)
        assertFalse(cs.clock.isPlaying)
        assertNotNull(findByDescription(root, "Play"))

        // Clean view hides the panel; Back restores it; Back again returns home.
        clickDesc(root, "Clean view")
        assertTrue(cs.cleanMode)
        act.onBackPressed(); shadowOf(Looper.getMainLooper()).idle()
        assertFalse(cs.cleanMode)
        act.onBackPressed(); shadowOf(Looper.getMainLooper()).idle()
        assertNull(act.creatorScreen)
        assertNotNull(act.homeScreen)

        // Process-recreation path: save state from a creator and restore it in a new activity.
        click(act.window.decorView, "Demo song")
        waitUntil(180_000, "demo reloaded (cached render)") { act.creatorScreen != null }
        val cs2 = act.creatorScreen!!
        waitReady(cs2, "restored engine")
        cs2.seekTo(33.0)
        val state = android.os.Bundle()
        ctrl.saveInstanceState(state)
        assertEquals("demo", state.getString("source"))
        val act2 = Robolectric.buildActivity(MainActivity::class.java).setup(state).get()
        waitUntil(180_000, "restored creator") { act2.creatorScreen?.engineReady == true }
        assertEquals(33.0, act2.creatorScreen!!.position(), 1e-6)
        assertEquals(cs2.scene, act2.creatorScreen!!.scene)
    }

    @Test
    fun sandboxIsCircleOnlyAndRecordsTaps() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        click(act.window.decorView, "Sandbox")
        waitUntil(60_000, "sandbox") { act.creatorScreen?.engineReady == true }
        val cs = act.creatorScreen!!
        assertTrue(cs.isSandbox)
        assertEquals(MechanicType.CIRCLE, cs.scene.focus)
        assertEquals("circle.sandbox", cs.presetIdFor(MechanicType.CIRCLE))
        assertNull("no mechanic selector in the sandbox", findText(act.window.decorView, "Arch"))
        // Transport on the free-running sandbox clock: play advances time, pause freezes it.
        val root = act.window.decorView
        clickDesc(root, "Play")
        assertTrue(cs.clock.isPlaying)
        RoboSupport.idle(300)
        val t1 = cs.position()
        assertTrue("clock advances while playing ($t1)", t1 > 0.1)
        clickDesc(root, "Pause")
        assertFalse(cs.clock.isPlaying)
        val t2 = cs.position()
        RoboSupport.idle(200)
        assertEquals(t2, cs.position(), 1e-9)
        clickDesc(root, "Restart")
        assertEquals(0.0, cs.position(), 1e-9)
        captureCreator(act, cs, 0.5, "screenshots/android/creator_sandbox.png")
    }

    @Test
    fun importedFilesAreRoutedByContentNotName() {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as RhythmApp
        val dir = app.cacheDir
        // A real MIDI file named .mp3 is still loaded as MIDI (rendered with the bundled SoundFont).
        val disguised = java.io.File(dir, "song.mp3").apply { writeBytes(com.rhythmphysics.core.midi.DemoSong.bytes()) }
        val ls = app.loader.load(android.net.Uri.fromFile(disguised), { _, _ -> }, { false })
        assertEquals(MediaType.MIDI, ls.session.mediaType)
        assertTrue(ls.pcm!!.length() > 1_000_000)
        // An MP3 (ID3 header) named .mid must never be parsed as MIDI.
        val fakeMidi = java.io.File(dir, "track.mid").apply { writeBytes("ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(2048)) }
        val err = runCatching { app.loader.load(android.net.Uri.fromFile(fakeMidi), { _, _ -> }, { false }) }.exceptionOrNull()
        assertNotNull("an MP3 named .mid must not load as MIDI", err)
        assertFalse("error must come from the audio path, not the MIDI parser: $err", err is com.rhythmphysics.core.midi.MidiFormatException)
        // A .mid name without an MThd header gets a clear message.
        val junk = java.io.File(dir, "broken.mid").apply { writeBytes("not midi at all".toByteArray()) }
        val msg = runCatching { app.loader.load(android.net.Uri.fromFile(junk), { _, _ -> }, { false }) }.exceptionOrNull()?.message ?: ""
        assertTrue(msg, msg.contains("MIDI header"))
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun landscapeCreatorUsesSidePanel() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        click(act.window.decorView, "Demo song")
        waitUntil(180_000, "demo") { act.creatorScreen?.engineReady == true }
        val cs = act.creatorScreen!!
        click(act.window.decorView, "16:9"); waitReady(cs, "16:9")
        cs.seekTo(21.0)
        val vis = cs.visualizer
        assertTrue("visualizer is left of the panel in landscape", vis.width > vis.height)
        captureCreator(act, cs, 21.0, "screenshots/android/creator_square_landscape.png")
        click(act.window.decorView, "Arch"); waitReady(cs, "arch")
        captureCreator(act, cs, 21.0, "screenshots/android/creator_arch_landscape.png")
    }

    @Test
    fun openingAndClosingTheCreatorDoesNotLeakThreads() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        fun appThreads() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("rp-") }.map { it.name }
        val before = appThreads()
        repeat(4) {
            click(act.window.decorView, "Sandbox")
            waitUntil(60_000, "sandbox open") { act.creatorScreen?.engineReady == true }
            clickDesc(act.window.decorView, "Play")
            RoboSupport.idle(100)
            act.onBackPressed(); shadowOf(Looper.getMainLooper()).idle()
            assertNull(act.creatorScreen)
        }
        waitUntil(5_000, "render threads stopped: ${appThreads()}") { appThreads().size <= before.size }
    }
}
