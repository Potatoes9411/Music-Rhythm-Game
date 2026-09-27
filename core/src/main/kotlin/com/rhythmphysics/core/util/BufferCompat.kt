package com.rhythmphysics.core.util

import java.nio.Buffer

/**
 * JDK 9+ added covariant overrides (ByteBuffer.position(int) returning ByteBuffer, ...). Code
 * compiled against a newer JDK binds to those and throws NoSuchMethodError on older Android
 * runtimes, so every position/limit/clear goes through the [Buffer] base type (checked by
 * `:app:apiCheck`).
 */
fun Buffer.setPosition(p: Int) { position(p) }
fun Buffer.setLimit(l: Int) { limit(l) }
fun Buffer.clearCompat() { clear() }
