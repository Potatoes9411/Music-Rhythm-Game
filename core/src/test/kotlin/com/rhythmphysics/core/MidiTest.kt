package com.rhythmphysics.core

import com.rhythmphysics.core.midi.MidiParser
import com.rhythmphysics.core.midi.MidiWriter
import com.rhythmphysics.core.music.EventMapper
import com.rhythmphysics.core.music.EventMappingSettings
import com.rhythmphysics.core.music.EventMode
import com.rhythmphysics.core.music.EventNormalizer
import com.rhythmphysics.core.music.EventRole
import com.rhythmphysics.core.music.PolyphonyMapping
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class MidiTest {
    @Test
    fun parsesTempoMapAndNotes() {
        val w = MidiWriter(480)
        val t0 = w.newTrack("meta")
        w.tempo(t0, 0, 120.0)
        w.tempo(t0, 1920, 60.0) // after one bar at 120 bpm (2 s), slow to 60 bpm
        val t1 = w.newTrack("notes")
        w.note(t1, 0, 480, 0, 60, 100)        // 0.0 s, 0.5 s long
        w.note(t1, 1920, 480, 0, 64, 90)      // 2.0 s, 1.0 s long (60 bpm)
        w.note(t1, 2400, 240, 0, 67, 80)      // 3.0 s
        val m = MidiParser.parse(w.toBytes())
        assertEquals(3, m.notes.size)
        assertEquals(0.0, m.notes[0].startSec, 1e-9)
        assertEquals(0.5, m.notes[0].durationSec, 1e-9)
        assertEquals(2.0, m.notes[1].startSec, 1e-9)
        assertEquals(1.0, m.notes[1].durationSec, 1e-9)
        assertEquals(3.0, m.notes[2].startSec, 1e-9)
        assertEquals("notes", m.trackNames[1])
    }

    @Test
    fun runningStatusAndZeroVelocityNoteOff() {
        // Hand-assembled track using running status and note-on velocity 0 as note-off.
        val track = byteArrayOf(
            0x00, 0x90.toByte(), 60, 100,   // note on
            0x00, 64, 100,                  // running status note on
            0x83.toByte(), 0x60, 60, 0,     // delta 480, running status: note 60 vel 0 -> off
            0x00, 64, 0,
            0x00, 0xFF.toByte(), 0x2F, 0x00,
        )
        val bytes = "MThd".toByteArray() + byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, 0x01, 0xE0.toByte()) +
            "MTrk".toByteArray() + byteArrayOf(0, 0, 0, track.size.toByte()) + track
        val m = MidiParser.parse(bytes)
        assertEquals(2, m.notes.size)
        assertTrue(m.notes.all { abs(it.durationSec - 0.5) < 1e-9 })
    }

    @Test
    fun rejectsNonMidi() {
        assertFailsWith<Exception> { MidiParser.parse("ID3 this is an mp3".toByteArray()) }
        assertTrue(!MidiParser.isMidi("ID3\u0004".toByteArray()))
        assertTrue(MidiParser.isMidi(Fixtures.demoMidiBytes))
    }

    @Test
    fun demoSongParsesWithSectionsAndDrums() {
        val m = Fixtures.demoMidi
        assertTrue(m.durationSec in 85.0..110.0, "duration ${m.durationSec}")
        assertTrue(m.notes.count { it.isPercussion } > 100)
        assertEquals(112.0, m.initialBpm, 0.01)
    }

    @Test
    fun polyphonyMappingsAreDeterministicAndDistinct() {
        val m = Fixtures.demoMidi
        val sizes = PolyphonyMapping.values().associateWith { EventNormalizer.fromMidi(m, it).size }
        for (mode in PolyphonyMapping.values()) {
            val a = EventNormalizer.fromMidi(m, mode)
            val b = EventNormalizer.fromMidi(m, mode)
            assertEquals(a, b, "mapping $mode not deterministic")
            assertTrue(a.zipWithNext().all { (x, y) -> x.timeSec <= y.timeSec })
        }
        assertTrue(sizes.getValue(PolyphonyMapping.ALL_NOTES) > sizes.getValue(PolyphonyMapping.CHORD_REDUCTION))
        val melody = EventNormalizer.fromMidi(m, PolyphonyMapping.MELODY)
        assertTrue(melody.all { it.channel == 0 }, "melody should come from the lead track")
        val perc = EventNormalizer.fromMidi(m, PolyphonyMapping.PERCUSSION)
        assertTrue(perc.all { it.channel == 9 })
    }

    @Test
    fun thinningRespectsMinimumGapAndKeepsInformation() {
        val raw = EventNormalizer.fromMidi(Fixtures.demoMidi, PolyphonyMapping.ALL_NOTES)
        for (density in listOf(0f, 0.5f, 1f)) {
            val s = EventMappingSettings(EventMode.DENSE, density)
            val mapped = EventMapper.map(raw, s)
            assertEquals(raw.size, mapped.size, "events must be demoted, not dropped")
            val phys = mapped.filter { it.role.physical }.map { it.timeSec }
            assertTrue(phys.zipWithNext().all { (a, b) -> b - a >= s.minIntervalSec - 1e-9 })
        }
        val hybrid = EventMapper.map(raw, EventMappingSettings(EventMode.HYBRID, 0.6f))
        assertTrue(hybrid.any { it.role == EventRole.MAJOR } && hybrid.any { it.role == EventRole.FX_ONLY })
    }
}

class MidiStructureTest {
    @Test
    fun demoSectionsFoundFromMidiStructure() {
        val s = com.rhythmphysics.core.music.MidiStructure.sections(Fixtures.demoMidi)
        val bar = 4 * 60.0 / 112
        val truth = listOf(4, 12, 20, 28, 36).map { it * bar }
        val hits = truth.count { t -> s.any { kotlin.math.abs(it - t) < 0.1 } }
        println("midi sections ${s.map { "%.2f".format(it) }} hits $hits/5")
        assertTrue(hits >= 4, "sections $s")
    }
}
