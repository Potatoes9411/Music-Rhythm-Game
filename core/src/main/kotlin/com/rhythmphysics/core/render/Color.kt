package com.rhythmphysics.core.render

import kotlin.math.abs
import kotlin.math.roundToInt

/** ARGB color helpers (colors are plain Ints, 0xAARRGGBB). */
object Colors {
    fun argb(a: Int, r: Int, g: Int, b: Int) = (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or
        (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun rgb(hex: Long): Int = (0xFF000000L or hex).toInt()
    fun parse(hex: String): Int {
        val h = hex.removePrefix("#")
        return when (h.length) {
            6 -> (0xFF000000L or h.toLong(16)).toInt()
            8 -> h.toLong(16).toInt()
            else -> throw IllegalArgumentException("bad color $hex")
        }
    }
    fun toHex(c: Int) = "#%08X".format(c)

    fun a(c: Int) = (c ushr 24) and 0xFF
    fun r(c: Int) = (c ushr 16) and 0xFF
    fun g(c: Int) = (c ushr 8) and 0xFF
    fun b(c: Int) = c and 0xFF

    fun withAlpha(c: Int, alpha: Float): Int = ((alpha.coerceIn(0f, 1f) * a(c)).roundToInt() shl 24) or (c and 0xFFFFFF)
    fun setAlpha(c: Int, alpha: Float): Int = ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (c and 0xFFFFFF)

    fun lerp(c1: Int, c2: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return argb(
            (a(c1) + (a(c2) - a(c1)) * u).roundToInt(), (r(c1) + (r(c2) - r(c1)) * u).roundToInt(),
            (g(c1) + (g(c2) - g(c1)) * u).roundToInt(), (b(c1) + (b(c2) - b(c1)) * u).roundToInt(),
        )
    }

    fun scale(c: Int, k: Float): Int = argb(a(c), (r(c) * k).roundToInt(), (g(c) * k).roundToInt(), (b(c) * k).roundToInt())

    /** h in [0,360), s,v in [0,1]. */
    fun hsv(h: Float, s: Float, v: Float, alpha: Float = 1f): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val c = v * s
        val x = c * (1 - abs(hh % 2 - 1))
        val m = v - c
        val (r, g, b) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return argb((alpha * 255).roundToInt(), ((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
    }

    /** Relative luminance 0..1 (sRGB approximation). */
    fun luminance(c: Int) = (0.2126f * r(c) + 0.7152f * g(c) + 0.0722f * b(c)) / 255f

    const val WHITE = -0x1
    const val BLACK = -0x1000000
    const val TRANSPARENT = 0
}

/** A named palette: background + accents. Mechanics pick colors by role index. */
data class Palette(
    val name: String,
    val bgTop: Int,
    val bgBottom: Int,
    val surface: Int,
    val surfaceLit: Int,
    val hero: Int,
    val accents: IntArray,
    val trail: Int,
    val text: Int = Colors.WHITE,
) {
    fun accent(i: Int) = accents[((i % accents.size) + accents.size) % accents.size]

    companion object {
        private fun c(s: String) = Colors.parse(s)

        val registry: Map<String, Palette> by lazy {
            listOf(
                // Classic planned square: deep navy stage, warm-to-cool accent wheel.
                Palette("classic", c("#FF161A2E"), c("#FF0C0E1A"), c("#FFE8EAF2"), c("#FFFFFFFF"), c("#FFFF5470"),
                    intArrayOf(c("#FFFF5470"), c("#FFFFB547"), c("#FF3DDC97"), c("#FF4CC9F0"), c("#FF9D7CFF"), c("#FFFF7AD9")),
                    c("#FFFF5470")),
                // MIDI Playground style: light grey, dark surfaces, flat colors.
                Palette("playground", c("#FFD9DADF"), c("#FFD1D2D8"), c("#FF2B2D35"), c("#FF15161B"), c("#FF3A86FF"),
                    intArrayOf(c("#FF3A86FF"), c("#FFFF006E"), c("#FFFB5607"), c("#FF8338EC"), c("#FF06A77D"), c("#FFFFBE0B")),
                    c("#FF3A86FF"), text = c("#FF15161B")),
                Palette("dark_minimal", c("#FF0E0F12"), c("#FF08090B"), c("#FF6B7080"), c("#FFF2F3F7"), c("#FFF2F3F7"),
                    intArrayOf(c("#FFF2F3F7"), c("#FFB9C0D4"), c("#FF8C93A8")), c("#FFF2F3F7")),
                Palette("neon", c("#FF0A0618"), c("#FF03020A"), c("#FF2DE2E6"), c("#FFFFFFFF"), c("#FFFF2E97"),
                    intArrayOf(c("#FFFF2E97"), c("#FF2DE2E6"), c("#FFF9C80E"), c("#FF7B2FF7"), c("#FF00FF9F")),
                    c("#FFFF2E97")),
                // Arch studio: near-black floor, warm hero, teal targets.
                Palette("studio_warm", c("#FF0D0F14"), c("#FF050608"), c("#FF1FB5A9"), c("#FF7FFFF2"), c("#FFFFF4DA"),
                    intArrayOf(c("#FFFFB547"), c("#FFFF8A3D"), c("#FFFFD37A")), c("#FFFFA53D")),
                Palette("studio_pillars", c("#FF101218"), c("#FF040506"), c("#FF2A2E3A"), c("#FFFFC98A"), c("#FFFFF4DA"),
                    intArrayOf(c("#FFFF8A3D"), c("#FFFFB547"), c("#FFFF6B3D")), c("#FFFF7A2F")),
                Palette("circle_night", c("#FF0B0D17"), c("#FF05060B"), c("#FFEDEFF7"), c("#FFFFFFFF"), c("#FFFFFFFF"),
                    intArrayOf(c("#FFFF4D6D"), c("#FFFFB547"), c("#FF3DDC97"), c("#FF4CC9F0"), c("#FF9D7CFF"), c("#FFFF7AD9")),
                    c("#FFFFFFFF")),
                Palette("music_ball", c("#FF120B24"), c("#FF06040E"), c("#FF6C5CE7"), c("#FFFFFFFF"), c("#FFFFF3C4"),
                    intArrayOf(c("#FFFF6B9A"), c("#FFFFC75F"), c("#FF4FD1C5"), c("#FF8C7CFF"), c("#FF5EC8FF")), c("#FFFFD79A")),
                Palette("piano", c("#FF14151A"), c("#FF08080B"), c("#FFF4F4F0"), c("#FFFFE08A"), c("#FFFFE08A"),
                    intArrayOf(c("#FFFFE08A"), c("#FFFF9F68"), c("#FF8AD7FF")), c("#FFFFE08A")),
                Palette("terrain", c("#FF0B0E14"), c("#FF030406"), c("#FF1B2230"), c("#FF67E8F9"), c("#FFFFF7E0"),
                    intArrayOf(c("#FF67E8F9"), c("#FFA78BFA"), c("#FFFCD34D"), c("#FFF472B6")), c("#FFFFE3A3")),
            ).associateBy { it.name }
        }

        fun named(name: String): Palette = registry[name] ?: registry.getValue("classic")
    }
}
