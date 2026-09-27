package com.rhythmphysics.app

import com.rhythmphysics.app.audio.ClockSmoother
import com.rhythmphysics.app.export.ExportSettings
import com.rhythmphysics.app.media.MediaKind
import com.rhythmphysics.app.media.MediaSniffer
import com.rhythmphysics.app.render.Letterbox
import com.rhythmphysics.core.midi.DemoSong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PureLogicTest {
    @Test
    fun snifferNeverTreatsAudioAsMidi() {
        assertEquals(MediaKind.MIDI, MediaSniffer.sniff(DemoSong.bytes()))
        assertEquals(MediaKind.MP3, MediaSniffer.sniff("ID3\u0004\u0000".toByteArray(Charsets.ISO_8859_1), "audio/mpeg", "song.mid"))
        assertEquals(MediaKind.MP3, MediaSniffer.sniff(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64)))
        assertEquals(MediaKind.WAV, MediaSniffer.sniff("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(MediaKind.OGG, MediaSniffer.sniff("OggS\u0000".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(MediaKind.FLAC, MediaSniffer.sniff("fLaC".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(MediaKind.MP4_AUDIO, MediaSniffer.sniff("\u0000\u0000\u0000 ftypM4A ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(MediaKind.AAC_ADTS, MediaSniffer.sniff(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte())))
        assertEquals(MediaKind.UNKNOWN, MediaSniffer.sniff("hello".toByteArray(), "audio/midi", "x.mid"), "a .mid name without MThd is not MIDI")
    }

    @Test
    fun clockIsMonotonicAndFollowsTheDevice() {
        val c = ClockSmoother()
        c.reset(0.0, 0)
        var last = 0.0
        var devicePos = 0.0
        for (i in 1..1000) {
            val now = i * 8_333_333L // 120 Hz frames
            if (i % 6 == 0) { devicePos = now / 1e9 + (if (i % 12 == 0) -0.004 else 0.003); c.anchor(devicePos, now) }
            val p = c.position(now, true)
            assertTrue(p >= last, "went backwards at $i")
            assertTrue(kotlin.math.abs(p - now / 1e9) < 0.03)
            last = p
        }
        c.reset(5.0, 9_000_000_000L) // seek
        assertEquals(5.0, c.position(9_000_000_000L, false), 1e-9)
    }

    @Test
    fun letterboxAndPts() {
        val lb = Letterbox.fit(1080f, 1920f, 1440f, 1440f)
        assertEquals(0.75f, lb.scale, 1e-6f)
        assertEquals(315f, lb.offsetX, 1e-3f)
        assertEquals(540f, lb.toFrameX(315f + 405f), 1e-3f)
        val ex = ExportSettings(1080, 1920, 30)
        assertEquals(1_000_000L, ex.ptsForFrame(30))
        assertTrue(ex.bitrate() in 2_000_000..24_000_000)
    }

    @Test
    fun thermalCapOnlyLowersQuality() {
        val q = com.rhythmphysics.core.render.Quality.values()
        assertEquals(null, com.rhythmphysics.app.platform.ThermalMonitor.capFor(0))
        assertEquals(null, com.rhythmphysics.app.platform.ThermalMonitor.capFor(1))
        for (user in q) {
            assertEquals(user, com.rhythmphysics.app.platform.ThermalMonitor.apply(user, null))
            val moderate = com.rhythmphysics.app.platform.ThermalMonitor.apply(user, com.rhythmphysics.app.platform.ThermalMonitor.capFor(2))
            assertTrue(moderate.ordinal <= minOf(user.ordinal, com.rhythmphysics.core.render.Quality.MEDIUM.ordinal))
            assertEquals(minOf(user.ordinal, 0), com.rhythmphysics.app.platform.ThermalMonitor.apply(user, com.rhythmphysics.app.platform.ThermalMonitor.capFor(4)).ordinal)
        }
    }

    @Test
    fun collisionLayerNotes() {
        val cl = com.rhythmphysics.app.audio.CollisionLayer
        assertEquals(64, cl.noteFor(3, 64), "MIDI events keep their own pitch")
        for (id in listOf(-7L, 0L, 1L, 4L, 123456789L)) assertTrue(cl.noteFor(id, null) in 72..80)
        assertTrue(cl.velocityFor(0f) > 0.1f && cl.velocityFor(1f) <= 1f && cl.velocityFor(1f) > cl.velocityFor(0.2f))
    }
}
