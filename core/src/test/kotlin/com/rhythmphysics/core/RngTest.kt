package com.rhythmphysics.core

import com.rhythmphysics.core.rng.SeededRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RngTest {
    @Test
    fun sameSeedSameSequenceAndStreamsIndependent() {
        val a = SeededRng.stream(42, "course"); val b = SeededRng.stream(42, "course")
        repeat(1000) { assertEquals(a.nextLong(), b.nextLong()) }
        assertNotEquals(SeededRng.stream(42, "course").nextLong(), SeededRng.stream(42, "colors").nextLong())
        assertNotEquals(SeededRng.stream(42, "course").nextLong(), SeededRng.stream(43, "course").nextLong())
    }

    @Test
    fun uniformity() {
        val r = SeededRng(7)
        val buckets = IntArray(10)
        repeat(100_000) { buckets[(r.nextDouble() * 10).toInt()]++ }
        assertTrue(buckets.all { it in 9_500..10_500 }, buckets.toList().toString())
        val s = r.state
        val c = r.copy(); assertEquals(r.nextLong(), c.nextLong()); assertTrue(s != r.state)
    }
}
