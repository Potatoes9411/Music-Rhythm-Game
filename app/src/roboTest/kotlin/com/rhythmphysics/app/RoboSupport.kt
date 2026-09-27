package com.rhythmphysics.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.FileOutputStream

/** Helpers for device-less app tests. */
object RoboSupport {
    val artifacts = File(System.getProperty("rp.artifacts") ?: "artifacts")

    /** Pumps the main looper while background work (coroutines on real threads) progresses. */
    var diagnostics: (() -> String)? = null

    fun waitUntil(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
        check(cond()) { "Timed out waiting for: $what. " + (diagnostics?.invoke() ?: "") }
    }

    fun idle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
    }

    fun capture(v: View, extra: ((Canvas) -> Unit)? = null): Bitmap {
        val bmp = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        v.draw(c)
        extra?.invoke(c)
        return bmp
    }

    fun save(bmp: Bitmap, relative: String): File {
        val f = File(artifacts, relative)
        f.parentFile.mkdirs()
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    fun findText(root: View, text: String): View? {
        if (root is TextView && root.text?.toString() == text) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findText(root.getChildAt(i), text)?.let { return it }
        return null
    }

    fun findByDescription(root: View, d: String): View? {
        if (root.contentDescription?.toString() == d) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findByDescription(root.getChildAt(i), d)?.let { return it }
        return null
    }

    fun allTexts(root: View, out: MutableList<String> = ArrayList()): List<String> {
        if (root is TextView) root.text?.toString()?.let { if (it.isNotBlank()) out += it }
        if (root is ViewGroup) for (i in 0 until root.childCount) allTexts(root.getChildAt(i), out)
        return out
    }
}
