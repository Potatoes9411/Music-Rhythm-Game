package com.rhythmphysics.app.media

/** What an imported file really is, decided from its bytes (never trusting the file name). */
enum class MediaKind(val label: String) {
    MIDI("MIDI"), WAV("WAV"), MP3("MP3"), OGG("OGG"), FLAC("FLAC"), MP4_AUDIO("M4A/AAC"), AAC_ADTS("AAC"), UNKNOWN("Unknown");
    val isAudio get() = this != MIDI && this != UNKNOWN
}

/** Pure-Kotlin content sniffing (unit-tested on the JVM). MP3 is never treated as MIDI. */
object MediaSniffer {
    fun sniff(h: ByteArray, mimeHint: String? = null, nameHint: String? = null): MediaKind {
        fun s(off: Int, len: Int) = if (h.size >= off + len) String(h, off, len, Charsets.ISO_8859_1) else ""
        return when {
            s(0, 4) == "MThd" -> MediaKind.MIDI
            s(0, 4) == "RIFF" && s(8, 4) == "RMID" -> MediaKind.MIDI
            s(0, 4) == "RIFF" && s(8, 4) == "WAVE" -> MediaKind.WAV
            s(0, 4) == "OggS" -> MediaKind.OGG
            s(0, 4) == "fLaC" -> MediaKind.FLAC
            s(4, 4) == "ftyp" -> MediaKind.MP4_AUDIO
            s(0, 3) == "ID3" -> MediaKind.MP3
            h.size >= 2 && (h[0].toInt() and 0xFF) == 0xFF && (h[1].toInt() and 0xF6) == 0xF0 -> MediaKind.AAC_ADTS
            h.size >= 2 && (h[0].toInt() and 0xFF) == 0xFF && (h[1].toInt() and 0xE0) == 0xE0 -> MediaKind.MP3
            else -> {
                // Fall back to hints only when the header is inconclusive.
                val m = mimeHint?.lowercase() ?: ""
                val n = nameHint?.lowercase() ?: ""
                when {
                    m.contains("midi") || n.endsWith(".mid") || n.endsWith(".midi") -> MediaKind.UNKNOWN // MIDI must have an MThd header
                    m.startsWith("audio/") -> MediaKind.MP4_AUDIO // let the platform decoder try
                    else -> MediaKind.UNKNOWN
                }
            }
        }
    }
}
