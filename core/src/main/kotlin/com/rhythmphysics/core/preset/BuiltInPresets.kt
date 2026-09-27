package com.rhythmphysics.core.preset

import com.rhythmphysics.core.mechanic.MechanicType
import com.rhythmphysics.core.music.EventMappingSettings
import com.rhythmphysics.core.music.EventMode


/**
 * Built-in recipes. Every preset configures one of the four engines; none of them is a separate game.
 */
object BuiltInPresets {
    fun all(): List<Preset> = square() + circle() + arch() + platform()

    private fun platform(): List<Preset> {
        fun p(id: String, name: String, desc: String, style: String, palette: String, vis: VisualParams, gravity: Double = 26.0, gap: Double = 0.13, corridor: Double = 9.0) =
            Preset(id = id, name = name, mechanic = MechanicType.PLATFORM, description = desc,
                generation = GenerationParams(style = style, corridorWidth = corridor, minContactGapSec = gap, stepDrop = 1.1),
                physics = PhysicsParams(gravity = gravity), camera = CameraParams(shake = 0.35),
                visuals = vis.copy(palette = palette), eventMapping = EventMappingSettings(EventMode.HYBRID, 0.6f))
        return listOf(
            p("platform.music_ball", "Platform — Music Ball", "Glowing ball bouncing down colorful pads placed exactly on the notes.", "music_ball", "music_ball",
                VisualParams(trailOpacity = 0.6, trailLengthSec = 0.35, trailWidth = 0.9, bloom = 0.7, emissive = 1.1, particles = 1.0)),
            p("platform.piano_tiles", "Platform — Piano Tiles", "Keys laid out by pitch like a keyboard; the ball plays them as it falls.", "piano_tiles", "piano",
                VisualParams(trailOpacity = 0.45, trailLengthSec = 0.3, bloom = 0.5, particles = 0.6), gap = 0.12),
            p("platform.staircase", "Platform — Staircase", "A descending staircase: one step per note, drop size follows the rhythm.", "staircase", "classic",
                VisualParams(trailOpacity = 0.5, trailLengthSec = 0.3, bloom = 0.4, particles = 0.7), gravity = 30.0, gap = 0.12),
            p("platform.minimal_bars", "Platform — Minimal Bars", "Thin white bars, white ball, black background. No bloom.", "minimal_bars", "dark_minimal",
                VisualParams(trailOpacity = 0.25, trailLengthSec = 0.25, bloom = 0.0, particles = 0.3, impactFlash = 0.6)),
            p("platform.neon", "Platform — Neon Platforms", "Neon outlined platforms with controlled glow on every hit.", "neon", "neon",
                VisualParams(trailOpacity = 0.7, trailLengthSec = 0.45, bloom = 0.9, emissive = 1.2, particles = 1.2)),
            p("platform.block_terrain", "Platform — Block Terrain", "A glowing orb falls through dark blocky terrain with note markers (and note names).", "block_terrain", "terrain",
                VisualParams(trailOpacity = 0.55, trailLengthSec = 0.4, bloom = 0.75, emissive = 1.2, particles = 0.8, noteLabels = true)),
        )
    }

    private fun rule(type: AnomalyType, trigger: AnomalyTrigger, every: Int = 1, p: Double = 1.0, params: Map<String, Float> = emptyMap(), max: Int = 0, at: Double = 0.0, repeat: Double = 0.0) =
        AnomalyRule(type, trigger, every, at, repeat, p, params, max)

    private fun circle(): List<Preset> {
        fun c(id: String, name: String, desc: String, gen: GenerationParams = GenerationParams(mode = "reactive"), ph: PhysicsParams, vis: VisualParams = VisualParams(palette = "circle_night", trailOpacity = 0.55, trailLengthSec = 0.35, bloom = 0.55, particles = 0.8),
                     rules: List<AnomalyRule> = emptyList(), mapping: EventMappingSettings = EventMappingSettings(EventMode.HYBRID, 0.55f)) =
            Preset(id = id, name = name, mechanic = MechanicType.CIRCLE, description = desc, generation = gen, physics = ph, visuals = vis, anomalies = rules, eventMapping = mapping)
        return listOf(
            c("circle.classic", "Circle — Classic Elastic", "One ball, gravity, perfectly elastic ring; the song drives kicks and colors.",
                ph = PhysicsParams(gravity = 24.0, restitution = 1.0, ballRadius = 0.95, initialSpeed = 12.0),
                vis = VisualParams(palette = "circle_night", trailOpacity = 0.6, trailLengthSec = 0.4, trailWidth = 0.9, bloom = 0.6, particles = 0.9)),
            c("circle.growing_ball", "Circle — Growing Ball", "Every bounce plays the next melody note and the ball grows until it fills the ring.",
                gen = GenerationParams(mode = "generative"),
                ph = PhysicsParams(gravity = 22.0, ballRadius = 0.45, ballGrowthPerHit = 0.06, maxBallRadius = 8.5, initialSpeed = 13.0)),
            c("circle.shrinking_ring", "Circle — Shrinking Ring", "The ring tightens on every hit, speeding up the rhythm.",
                ph = PhysicsParams(gravity = 20.0, ballRadius = 0.6, ringShrinkPerHit = 0.05, minRingRadius = 2.2, initialSpeed = 12.0),
                rules = listOf(rule(AnomalyType.RING_GROW, AnomalyTrigger.DOWNBEAT, every = 8, params = mapOf("amount" to 3f)))),
            c("circle.grow_shrink", "Circle — Growing Ball / Shrinking Ring", "Ball grows, ring shrinks: tension builds to the drop.",
                gen = GenerationParams(mode = "generative"),
                ph = PhysicsParams(gravity = 20.0, ballRadius = 0.5, ballGrowthPerHit = 0.035, ringShrinkPerHit = 0.03, minRingRadius = 3.0, maxBallRadius = 7.0),
                vis = VisualParams(palette = "circle_night", trailOpacity = 0.5, bloom = 0.6, particles = 0.9, showStats = true)),
            c("circle.escape_gap", "Circle — Escape the Gap", "A gap in the ring rotates; every escaping ball spawns two more.",
                ph = PhysicsParams(gravity = 18.0, ballRadius = 0.5, gapCount = 1, gapSizeDeg = 34.0, gapRotationDegPerSec = 55.0, spawnOnEscape = 2, maxBalls = 160, ballCollisions = true),
                vis = VisualParams(palette = "circle_night", trailOpacity = 0.35, trailLengthSec = 0.25, bloom = 0.5, particles = 0.6, showStats = true)),
            c("circle.rotating_gap", "Circle — Rotating Gap", "Fast-rotating opening; beats reverse the rotation.",
                ph = PhysicsParams(gravity = 16.0, ballRadius = 0.55, gapCount = 1, gapSizeDeg = 42.0, gapRotationDegPerSec = 120.0, spawnOnEscape = 1, maxBalls = 40),
                rules = listOf(rule(AnomalyType.GAP_ROTATE, AnomalyTrigger.DOWNBEAT, params = mapOf("degPerSec" to 130f))),
                vis = VisualParams(palette = "circle_night", trailOpacity = 0.5, bloom = 0.55, particles = 0.8, showStats = true)),
            c("circle.multiplication", "Circle — Multiplication", "Balls duplicate on strong beats; ball-ball collisions everywhere.",
                ph = PhysicsParams(gravity = 12.0, ballRadius = 0.45, maxBalls = 128, ballCollisions = true, initialSpeed = 11.0),
                rules = listOf(rule(AnomalyType.DUPLICATION_CASCADE, AnomalyTrigger.DOWNBEAT, every = 4, max = 6), rule(AnomalyType.BALL_SHRINK, AnomalyTrigger.DOWNBEAT, every = 4, params = mapOf("amount" to 0.12f))),
                vis = VisualParams(palette = "circle_night", trailOpacity = 0.3, trailLengthSec = 0.2, bloom = 0.45, particles = 0.5, showStats = true)),
            c("circle.gravity_chaos", "Circle — Gravity Chaos", "Gravity rotates on every beat and flips on downbeats.",
                ph = PhysicsParams(gravity = 26.0, ballCount = 3, ballRadius = 0.55, ballCollisions = true),
                rules = listOf(rule(AnomalyType.GRAVITY_ROTATE, AnomalyTrigger.BEAT, params = mapOf("degrees" to 90f)), rule(AnomalyType.GRAVITY_FLIP, AnomalyTrigger.DOWNBEAT, every = 2))),
            c("circle.melody_collision", "Circle — Melody Collision", "Each collision plays the next note of the melody (MIDI) — the ball performs the song.",
                gen = GenerationParams(mode = "generative"),
                ph = PhysicsParams(gravity = 26.0, ballRadius = 0.6, initialSpeed = 14.0),
                mapping = EventMappingSettings(EventMode.MELODIC, 0.8f)),
            c("circle.collision_synth", "Circle — Collision Synth", "Several balls; every ring hit plays a pitched note (angle-mapped scale without a song).",
                gen = GenerationParams(mode = "generative"),
                ph = PhysicsParams(gravity = 0.0, ballCount = 5, ballRadius = 0.5, initialSpeed = 9.0, ballCollisions = true),
                vis = VisualParams(palette = "neon", trailOpacity = 0.6, trailLengthSec = 0.4, bloom = 0.8, particles = 0.8)),
            c("circle.orbit_force", "Circle — Orbit Force", "Tangential and attractive forces make balls swirl; downbeats flip the orbit.",
                ph = PhysicsParams(gravity = 0.0, ballCount = 6, ballRadius = 0.4, orbit = 28.0, attract = 6.0, initialSpeed = 8.0, ballCollisions = true),
                rules = listOf(rule(AnomalyType.ORBIT, AnomalyTrigger.DOWNBEAT, every = 2, params = mapOf("amount" to 30f)), rule(AnomalyType.REPEL, AnomalyTrigger.MAJOR_EVENT, every = 8, params = mapOf("amount" to 14f)), rule(AnomalyType.ATTRACT, AnomalyTrigger.MAJOR_EVENT, every = 8, params = mapOf("amount" to 8f)))),
            c("circle.ring_break", "Circle — Ring Break", "The ring shatters on big hits and reforms — balls that fly out are replaced.",
                ph = PhysicsParams(gravity = 20.0, ballCount = 2, ballRadius = 0.55, spawnOnEscape = 1, maxBalls = 24),
                rules = listOf(rule(AnomalyType.RING_BREAK, AnomalyTrigger.DOWNBEAT, every = 8, params = mapOf("seconds" to 0.5f)))),
            c("circle.chaos", "Circle — Chaos", "Seeded anomaly soup: gaps, gravity, growth, duplication and bursts driven by the song.",
                ph = PhysicsParams(gravity = 18.0, ballCount = 2, ballRadius = 0.5, maxBalls = 90, spawnOnEscape = 1, ballCollisions = true),
                rules = listOf(
                    rule(AnomalyType.GRAVITY_ROTATE, AnomalyTrigger.DOWNBEAT, p = 0.5),
                    rule(AnomalyType.GAP_OPEN, AnomalyTrigger.DOWNBEAT, every = 4, p = 0.6, max = 3),
                    rule(AnomalyType.GAP_CLOSE, AnomalyTrigger.DOWNBEAT, every = 6, p = 0.6),
                    rule(AnomalyType.GAP_ROTATE, AnomalyTrigger.DOWNBEAT, every = 5),
                    rule(AnomalyType.SPLIT_BALL, AnomalyTrigger.MAJOR_EVENT, every = 3, p = 0.7),
                    rule(AnomalyType.CHAOS_BURST, AnomalyTrigger.DOWNBEAT, every = 8),
                    rule(AnomalyType.COLOR_SHIFT, AnomalyTrigger.DOWNBEAT, every = 2),
                    rule(AnomalyType.RESTITUTION_CHANGE, AnomalyTrigger.COLLISION, every = 50),
                    rule(AnomalyType.SPEED_UP, AnomalyTrigger.BEAT, every = 16, params = mapOf("amount" to 0.1f)),
                    rule(AnomalyType.SLOW_DOWN, AnomalyTrigger.BEAT, every = 24, params = mapOf("amount" to 0.1f)),
                ),
                vis = VisualParams(palette = "circle_night", trailOpacity = 0.45, trailLengthSec = 0.3, bloom = 0.6, particles = 0.8, showStats = true)),
            c("circle.rainbow_rings", "Circle — Rainbow Rings", "The ball's outline is stamped every frame in cycling rainbow colors and never erased while it grows; navy backdrop, red ring.",
                gen = GenerationParams(mode = "generative"),
                ph = PhysicsParams(gravity = 22.0, ballRadius = 1.2, ballGrowthPerHit = 0.10, maxBallRadius = 7.2, initialSpeed = 13.0, ringThickness = 0.16),
                vis = VisualParams(palette = "rings_navy", persist = "rings", background = "flat", trailOpacity = 0.0, bloom = 0.0, particles = 0.0, impactFlash = 0.3)),
            c("circle.rainbow_trails", "Circle — Rainbow Trails", "Balls multiply on the beat and every path is painted permanently in shifting rainbow colors until the disc fills; black backdrop, thin grey ring.",
                ph = PhysicsParams(gravity = 16.0, ballRadius = 0.28, maxBalls = 160, initialSpeed = 11.0, ringThickness = 0.05),
                rules = listOf(rule(AnomalyType.DUPLICATION_CASCADE, AnomalyTrigger.DOWNBEAT, every = 4, max = 7)),
                vis = VisualParams(palette = "trails_black", persist = "trails", background = "flat", trailOpacity = 0.0, bloom = 0.0, particles = 0.0, impactFlash = 0.0)),
            c("circle.sandbox", "Circle — Sandbox", "No song needed: tap inside the ring to add balls; collisions play notes.",
                gen = GenerationParams(mode = "generative"),
                ph = PhysicsParams(gravity = 20.0, ballRadius = 0.55, ballCollisions = true, maxBalls = 80)),
        )
    }

    private fun arch() = listOf(
        Preset(
            id = "arch.bounce_curve", name = "Arch — Bounce Curve", mechanic = MechanicType.ARCH,
            description = "Grey spotlit studio floor, small flat teal discs, and a white-hot hero drawing a long yellow comet streak reflected in the floor.",
            generation = GenerationParams(style = "bounce_curve", speed = 5.2, apexMin = 0.55, apexMax = 3.0, apexDecay = 0.76, corridorWidth = 7.0, minContactGapSec = 0.2),
            camera = CameraParams(pitchDeg = 14.0, distance = 16.0, fovDeg = 30.0, shake = 0.3, zoom = 0.9),
            visuals = VisualParams(
                palette = "studio_grey", background = "studio", targetScale = 0.55, heroSize = 0.7, trailLengthSec = 1.1, trailWidth = 0.7, trailOpacity = 0.85, trailTaper = 0.9,
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
            description = "MIDI Playground gameplay look: a brick-red field with the square's route carved out in black, a colored tick on the wall at every bounce, small yellow outline square.",
            generation = GenerationParams(style = "wander", speed = 10.0, surfaceLength = 2.0, surfaceThickness = 0.3, compactness = 0.45, drift = 1.2),
            visuals = VisualParams(
                palette = "carved_brick", background = "carved", trailOpacity = 0.0, bloom = 0.25, particles = 0.6,
                impactFlash = 0.0, squashStretch = false, heroSize = 0.7, surfaceMemoryTint = false,
            ),
            camera = CameraParams(zoom = 0.75),
            eventMapping = EventMappingSettings(mode = EventMode.MELODIC, density = 0.75f),
        ),
        Preset(
            id = "square.carved_orange", name = "Square — Carved Orange", mechanic = MechanicType.SQUARE,
            description = "Orange field, blocky black carved route, tiny glowing outline square.",
            generation = GenerationParams(style = "wander", speed = 10.0, surfaceLength = 2.0, surfaceThickness = 0.3, compactness = 0.45, drift = 1.2),
            visuals = VisualParams(palette = "carved_orange", background = "carved", trailOpacity = 0.0, bloom = 0.3, particles = 0.5,
                impactFlash = 0.0, squashStretch = false, heroSize = 0.45, surfaceMemoryTint = false, carveWidth = 4.5),
            camera = CameraParams(zoom = 0.6),
            eventMapping = EventMappingSettings(mode = EventMode.HYBRID, density = 0.8f),
        ),
        Preset(
            id = "square.carved_navy", name = "Square — Carved Navy", mechanic = MechanicType.SQUARE,
            description = "Dark navy field with a subtly darker carved route and pale outline square.",
            generation = GenerationParams(style = "wander", speed = 10.0, surfaceLength = 2.0, surfaceThickness = 0.3, compactness = 0.45, drift = 1.2),
            visuals = VisualParams(palette = "carved_navy", background = "carved", trailOpacity = 0.0, bloom = 0.15, particles = 0.4,
                impactFlash = 0.0, squashStretch = false, heroSize = 0.45, surfaceMemoryTint = false, carveWidth = 3.2),
            camera = CameraParams(zoom = 0.55),
            eventMapping = EventMappingSettings(mode = EventMode.HYBRID, density = 0.8f),
        ),
        Preset(
            id = "square.stamp_walls", name = "Square — Stamp Walls", mechanic = MechanicType.SQUARE,
            description = "Black backdrop, tall grey wall slabs, and a square that leaves a purple-to-pink trail of solid stamps as it zig-zags.",
            generation = GenerationParams(style = "classic", speed = 9.0, surfaceLength = 9.0, surfaceThickness = 1.8, compactness = 0.5, drift = 0.7),
            visuals = VisualParams(
                palette = "stamps", background = "flat", trailOpacity = 1.0, trailLengthSec = 0.8, bloom = 0.0, particles = 0.0,
                impactFlash = 0.0, squashStretch = false, heroSize = 1.1, colorShiftOnImpact = false, surfaceMemoryTint = false,
                stampCount = 8, stampSpacingSec = 0.15, sharpSurfaces = true,
            ),
            eventMapping = EventMappingSettings(mode = EventMode.BEAT, density = 0.6f),
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
