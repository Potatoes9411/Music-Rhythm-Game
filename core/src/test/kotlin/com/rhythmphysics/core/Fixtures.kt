package com.rhythmphysics.core

import com.rhythmphysics.core.midi.DemoSong
import com.rhythmphysics.core.midi.MidiParser
import com.rhythmphysics.core.session.MediaType
import com.rhythmphysics.core.session.MidiEventSource
import com.rhythmphysics.core.session.RhythmSession
import com.rhythmphysics.core.util.Hashing
import java.io.File

object Fixtures {
    val demoMidiBytes: ByteArray by lazy { DemoSong.bytes() }
    val demoMidi by lazy { MidiParser.parse(demoMidiBytes) }

    fun demoSession(): RhythmSession {
        val midi = demoMidi
        return RhythmSession(
            "demo", "asset://demo.mid", Hashing.sha256Hex(demoMidiBytes), MediaType.MIDI, DemoSong.TITLE,
            midi.durationSec, MidiEventSource(midi), midi = midi,
        )
    }

    val artifacts: File by lazy { File(System.getProperty("rp.artifacts") ?: "build/artifacts").also { it.mkdirs() } }
}
