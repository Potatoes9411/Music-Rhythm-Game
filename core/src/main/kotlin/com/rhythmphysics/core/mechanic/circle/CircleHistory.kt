package com.rhythmphysics.core.mechanic.circle

import com.rhythmphysics.core.render.DrawList
import com.rhythmphysics.core.render.PersistentLayer

/**
 * Never-cleared paint for the circle looks from the reference footage: ball-outline stamps
 * ("rings") and permanent rainbow trail segments ("trails").
 *
 * Coordinates are world units with y already flipped (screen-down), so the renderer only needs a
 * translate + uniform scale. The history is append-only while the simulation runs forward and is
 * a pure function of the deterministic simulation, so checkpoints store only its length.
 */
class CircleHistory(private val capacity: Int = 200_000) : PersistentLayer() {
    private var kind = ByteArray(1024)
    private var f = FloatArray(1024 * 5)
    private var col = IntArray(1024)
    private var n = 0
    private var ep = 0

    override val epoch: Int get() = ep
    override val size: Int get() = n
    val full: Boolean get() = n >= capacity

    private fun grow() {
        val c = kind.size * 2
        kind = kind.copyOf(c); f = f.copyOf(c * 5); col = col.copyOf(c)
    }

    fun ring(x: Double, y: Double, r: Double, width: Double, color: Int) = add(RING, x, -y, r, width, 0.0, color)

    fun segment(x0: Double, y0: Double, x1: Double, y1: Double, width: Double, color: Int) = add(SEG, x0, -y0, x1, -y1, width, color)

    private fun add(k: Byte, a: Double, b: Double, c: Double, d: Double, e: Double, color: Int) {
        if (n >= capacity) return
        if (n == kind.size) grow()
        kind[n] = k; col[n] = color
        val o = n * 5
        f[o] = a.toFloat(); f[o + 1] = b.toFloat(); f[o + 2] = c.toFloat(); f[o + 3] = d.toFloat(); f[o + 4] = e.toFloat()
        n++
    }

    /** Restore/seek: history beyond [len] belongs to a timeline being replaced. */
    fun truncate(len: Int) {
        if (len < n) { n = len.coerceAtLeast(0); ep++ }
    }

    fun clear() { n = 0; ep++ }

    override fun emit(dl: DrawList, from: Int, to: Int) {
        for (i in from until minOf(to, n)) {
            val o = i * 5
            when (kind[i]) {
                RING -> dl.circleStroke(f[o], f[o + 1], f[o + 2], f[o + 3], col[i])
                SEG -> dl.line(f[o], f[o + 1], f[o + 2], f[o + 3], f[o + 4], col[i], true)
            }
        }
    }

    companion object {
        const val RING: Byte = 1
        const val SEG: Byte = 2
    }
}
