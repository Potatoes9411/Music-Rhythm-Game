package com.rhythmphysics.core.preset

import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.music.EventMappingSettings
import com.rhythmphysics.core.music.EventMode

/**
 * Built-in recipes. Every preset configures one of the four engines; none of them is a separate game.
 */
object BuiltInPresets {
    fun all(): List<Preset> = square() + arch()

    private fun arch() = listOf(
        Preset(
            id = "arch.bounce_curve", name = "Arch — Bounce Curve", mechanic = MechanicType.ARCH,
            description = "White-hot hero on decaying ballistic bounces across separated teal pads; dark studio floor, low camera, warm comet trail with controlled bloom.",
            generation = GenerationParams(style = "bounce_curve", speed = 5.2, apexMin = 0.55, apexMax = 3.0, apexDecay = 0.76, corridorWidth = 7.0, minContactGapSec = 0.2),
            camera = CameraParams(pitchDeg = 14.0, distance = 16.0, fovDeg = 30.0, shake = 0.3),
            visuals = VisualParams(
                palette = "studio_warm", heroSize = 1.0, trailLengthSec = 0.45, trailWidth = 0.8, trailOpacity = 0.7, trailTaper = 0.95,
                bloom = 0.8, emissive = 1.2, particles = 0.8, reflections = true,
            ),
            eventMapping = EventMappingSettings(mode = EventMode.HYBRID, density = 0.5f),
        ),
        Preset(
            id = "arch.pillar_weave", name = "Arch — Pillar Weave", mechanic = MechanicType.ARCH,
            description = "Glowing hero swoops between and onto cylindrical pillars of musical height (guided curves), long orange comet trail, reflective floor.",
            generation = GenerationParams(style = "pillar_weave", speed = 4.4, pillarMinHeight = 1.0, pillarMaxHeight = 4.0, corridorWidth = 6.0, minContactGapSec = 0.24),
            camera = CameraParams(pitchDeg = 12.0, distance = 18.0, fovDeg = 32.0, shake = 0.25),
            visuals = VisualParams(
                palette = "studio_pillars", heroSize = 1.05, trailLengthSec = 0.7, trailWidth = 0.8, trailOpacity = 0.75, trailTaper = 0.95,
                bloom = 0.85, emissive = 1.25, particles = 0.6, reflections = true,
            ),
            eventMapping = EventMappingSettings(mode = EventMode.HYBRID, density = 0.45f),
        ),
    )

    private fun square() = listOf(
        Preset(
            id = "square.classic", name = "Square — Classic Planned", mechanic = MechanicType.SQUARE,
            description = "Creator-ready planned bounce course: large hero, short pegs placed exactly on the music, controlled trail.",
            generation = GenerationParams(style = "classic", speed = 9.0, surfaceLength = 2.4, compactness = 0.6, drift = 0.9),
            visuals = VisualParams(palette = "classic", trailOpacity = 0.42, trailLengthSec = 0.32, bloom = 0.35, particles = 1.0),
            eventMapping = EventMappingSettings(mode = EventMode.HYBRID, density = 0.62f),
        ),
        Preset(
            id = "square.midi_playground", name = "Square — MIDI Playground", mechanic = MechanicType.SQUARE,
            description = "Minimal look inspired by MIDI Playground: light grey backdrop, dark surfaces, one solid recoloring square, no bloom or particles.",
            generation = GenerationParams(style = "playground", speed = 10.0, surfaceLength = 2.0, surfaceThickness = 0.3, compactness = 0.45, drift = 0.7),
            visuals = VisualParams(
                palette = "playground", background = "flat", trailOpacity = 0.0, bloom = 0.0, particles = 0.0,
                impactFlash = 0.35, squashStretch = false, heroSize = 0.85, surfaceMemoryTint = false,
            ),
            eventMapping = EventMappingSettings(mode = EventMode.MELODIC, density = 0.75f),
        ),
        Preset(
            id = "square.dark_minimal", name = "Square — Dark Minimal", mechanic = MechanicType.SQUARE,
            description = "Monochrome, no bloom: white square and grey pegs on near-black with ghost outlines.",
            generation = GenerationParams(style = "minimal", speed = 8.0, surfaceLength = 2.6, compactness = 0.7, drift = 0.8),
            visuals = VisualParams(
                palette = "dark_minimal", background = "flat", ghostTrail = true, trailOpacity = 0.35, trailLengthSec = 0.3,
                bloom = 0.0, particles = 0.35, colorShiftOnImpact = false, impactFlash = 0.6,
            ),
            eventMapping = EventMappingSettings(mode = EventMode.HYBRID, density = 0.55f),
        ),
        Preset(
            id = "square.neon_trail", name = "Square — Neon Trail", mechanic = MechanicType.SQUARE,
            description = "Neon outlines, additive glowing trail and controlled bloom on impacts.",
            generation = GenerationParams(style = "neon", speed = 10.0, surfaceLength = 2.3, compactness = 0.55, drift = 1.0),
            visuals = VisualParams(
                palette = "neon", background = "grid", outlineOnly = true, trailOpacity = 0.75, trailLengthSec = 0.5,
                trailWidth = 0.9, bloom = 0.8, emissive = 1.2, particles = 1.4,
            ),
            eventMapping = EventMappingSettings(mode = EventMode.HYBRID, density = 0.7f),
        ),
    )
}
