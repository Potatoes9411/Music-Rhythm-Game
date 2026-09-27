package com.rhythmphysics.core.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Compact binary writer/reader for checkpoints and state hashes. */
class StateWriter {
    private val bos = ByteArrayOutputStream(1024)
    val out = DataOutputStream(bos)
    fun d(v: Double) = out.writeDouble(v)
    fun f(v: Float) = out.writeFloat(v)
    fun i(v: Int) = out.writeInt(v)
    fun l(v: Long) = out.writeLong(v)
    fun b(v: Boolean) = out.writeBoolean(v)
    fun s(v: String) = out.writeUTF(v)
    fun bytes(): ByteArray { out.flush(); return bos.toByteArray() }
}

class StateReader(bytes: ByteArray) {
    private val inp = DataInputStream(ByteArrayInputStream(bytes))
    fun d() = inp.readDouble()
    fun f() = inp.readFloat()
    fun i() = inp.readInt()
    fun l() = inp.readLong()
    fun b() = inp.readBoolean()
    fun s(): String = inp.readUTF()
}

object Hashing {
    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun sha256Hex(s: String) = sha256Hex(s.toByteArray(Charsets.UTF_8))
}
