package com.rhythmphysics.core.rng

/**
 * The single deterministic PRNG used by all gameplay code (SplitMix64).
 *
 * Its complete state is one Long, which makes checkpoints and replays trivial. Gameplay code must
 * never use kotlin.random.Random.Default or Math.random(); derive a named stream instead:
 *
 *   val course = SeededRng.stream(seed, "course")
 */
class SeededRng(var state: Long) {

    fun nextLong(): Long {
        state += GOLDEN
        return mix(state)
    }

    /** Uniform in [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11) * DOUBLE_UNIT

    fun nextFloat(): Float = (nextLong() ushr 40) * FLOAT_UNIT

    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return ((nextLong() ushr 33) % bound).toInt()
    }

    fun range(lo: Double, hi: Double) = lo + (hi - lo) * nextDouble()

    fun rangeInt(lo: Int, hiInclusive: Int) = lo + nextInt(hiInclusive - lo + 1)

    fun chance(p: Double) = nextDouble() < p

    fun sign() = if (nextLong() and 1L == 0L) 1.0 else -1.0

    fun <T> pick(list: List<T>): T = list[nextInt(list.size)]

    /** Approximately normal(0,1) via sum of uniforms (cheap, deterministic, no transcendental edge cases). */
    fun gaussianish(): Double {
        var s = 0.0
        repeat(4) { s += nextDouble() }
        return (s - 2.0) * 1.7320508075688772
    }

    fun copy() = SeededRng(state)

    companion object {
        private const val GOLDEN = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        private const val DOUBLE_UNIT = 1.0 / (1L shl 53)
        private const val FLOAT_UNIT = 1.0f / (1 shl 24)

        fun mix(z0: Long): Long {
            var z = z0
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        /** Stable 64-bit FNV-1a hash of a stream name. */
        fun hashName(name: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (c in name) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }

        /** Independent named stream derived from the session seed (course, anomalies, colors, particles, camera...). */
        fun stream(seed: Long, name: String) = SeededRng(mix(seed xor hashName(name)))

        /** Stateless hash-derived stream for per-object randomness (e.g. particles of impact #k). */
        fun forKey(seed: Long, name: String, key: Long) = SeededRng(mix(mix(seed xor hashName(name)) + key * GOLDEN))
    }
}
