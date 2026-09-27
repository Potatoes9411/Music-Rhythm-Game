package com.rhythmphysics.buildtools

import com.android.apksig.ApkSigner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipFile

/**
 * Builds the final APK zip from the aapt2 resource package plus dex files, applying the
 * alignment Android requires: STORED entries 4-byte aligned (resources.arsc must be stored and
 * aligned for targetSdk >= 30), native libs page aligned. Writes local headers by hand so the
 * data offset of each stored entry can be padded through the extra field (what zipalign does).
 */
object ApkPackager {
    private val storeExtensions = setOf("arsc", "png", "jpg", "ogg", "mp3", "wav", "so")

    class Entry(val name: String, val data: ByteArray)

    fun build(resourceApk: File, dexFiles: List<File>, extraAssets: Map<String, File>, out: File, storeAssets: Set<String>) {
        val entries = ArrayList<Entry>()
        ZipFile(resourceApk).use { zf ->
            zf.entries().toList().forEach { e ->
                if (!e.isDirectory) entries += Entry(e.name, zf.getInputStream(e).readBytes())
            }
        }
        dexFiles.sortedBy { it.name }.forEach { entries += Entry(it.name, it.readBytes()) }
        extraAssets.toSortedMap().forEach { (name, f) -> entries += Entry(name, f.readBytes()) }
        // AndroidManifest.xml first, then deterministic order.
        entries.sortWith(compareBy<Entry>({ if (it.name == "AndroidManifest.xml") 0 else 1 }, { it.name }))
        writeAligned(entries, out) { name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            ext in storeExtensions || name in storeAssets || storeAssets.any { name.startsWith(it) }
        }
    }

    private class Central(val name: ByteArray, val method: Int, val crc: Long, val csize: Int, val usize: Int, val offset: Long)

    fun writeAligned(entries: List<Entry>, out: File, stored: (String) -> Boolean) {
        out.parentFile.mkdirs()
        if (out.exists()) out.delete()
        val centrals = ArrayList<Central>()
        RandomAccessFile(out, "rw").use { raf ->
            for (e in entries) {
                val nameBytes = e.name.toByteArray(Charsets.UTF_8)
                val crc = CRC32().apply { update(e.data) }.value
                val store = stored(e.name)
                val payload = if (store) e.data else deflate(e.data)
                val method = if (store) 0 else 8
                val offset = raf.filePointer
                val headerLen = 30 + nameBytes.size
                var extraLen = 0
                if (store) {
                    val align = if (e.name.endsWith(".so")) 4096 else 4
                    val dataStart = offset + headerLen
                    extraLen = ((align - (dataStart % align)) % align).toInt()
                }
                val h = java.nio.ByteBuffer.allocate(headerLen + extraLen).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                h.putInt(0x04034b50)
                h.putShort(if (store) 10 else 20) // version needed
                h.putShort(0x0800.toShort()) // UTF-8 names
                h.putShort(method.toShort())
                h.putShort(0) // time
                h.putShort(0x21) // date (1980-01-01)
                h.putInt(crc.toInt())
                h.putInt(payload.size)
                h.putInt(e.data.size)
                h.putShort(nameBytes.size.toShort())
                h.putShort(extraLen.toShort())
                h.put(nameBytes)
                repeat(extraLen) { h.put(0) }
                raf.write(h.array())
                raf.write(payload)
                centrals += Central(nameBytes, method, crc, payload.size, e.data.size, offset)
            }
            val cdStart = raf.filePointer
            val cd = ByteArrayOutputStream()
            for (c in centrals) {
                val b = java.nio.ByteBuffer.allocate(46 + c.name.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                b.putInt(0x02014b50)
                b.putShort(20); b.putShort(if (c.method == 0) 10 else 20)
                b.putShort(0x0800.toShort()); b.putShort(c.method.toShort())
                b.putShort(0); b.putShort(0x21)
                b.putInt(c.crc.toInt()); b.putInt(c.csize); b.putInt(c.usize)
                b.putShort(c.name.size.toShort()); b.putShort(0); b.putShort(0)
                b.putShort(0); b.putShort(0); b.putInt(0)
                b.putInt(c.offset.toInt())
                b.put(c.name)
                cd.write(b.array())
            }
            raf.write(cd.toByteArray())
            val eocd = java.nio.ByteBuffer.allocate(22).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            eocd.putInt(0x06054b50); eocd.putShort(0); eocd.putShort(0)
            eocd.putShort(centrals.size.toShort()); eocd.putShort(centrals.size.toShort())
            eocd.putInt(cd.size()); eocd.putInt(cdStart.toInt()); eocd.putShort(0)
            raf.write(eocd.array())
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        DeflaterOutputStream(bos, Deflater(Deflater.BEST_COMPRESSION, true)).use { it.write(data) }
        return bos.toByteArray()
    }

    /** Signs with APK Signature Scheme v2 using apksig (Maven Central). */
    fun sign(input: File, output: File, keystore: File, storePass: String, alias: String, keyPass: String) {
        val ks = KeyStore.getInstance(if (keystore.name.endsWith(".p12")) "PKCS12" else "JKS")
        keystore.inputStream().use { ks.load(it, storePass.toCharArray()) }
        val key = ks.getKey(alias, keyPass.toCharArray()) as PrivateKey
        val certs = ks.getCertificateChain(alias).map { it as X509Certificate }
        val cfg = ApkSigner.SignerConfig.Builder("RHYTHMPH", key, certs).build()
        if (output.exists()) output.delete()
        ApkSigner.Builder(listOf(cfg))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(false) // minSdk 26: v2 is sufficient (v1 is only needed below API 24)
            .setV2SigningEnabled(true)
            .setMinSdkVersion(26)
            .build()
            .sign()
    }
}
