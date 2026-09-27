package com.rhythmphysics.app.ui

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.rhythmphysics.app.MainActivity
import com.rhythmphysics.core.Branding
import com.rhythmphysics.core.midi.DemoSong
import com.rhythmphysics.core.preset.PresetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Start screen: import (SAF), demo song, sandbox, recent files, user presets. */
class HomeScreen(private val act: MainActivity) {
    private val app = act.app
    private val content = Ui.column(act)
    private val recentList = Ui.column(act)
    val root: View = ScrollView(act).apply {
        setBackgroundColor(Ui.BG)
        isFillViewport = true
        addView(content)
    }

    init { build(); refresh() }

    fun setInsets(l: Int, t: Int, r: Int, b: Int) {
        val p = Ui.dp(act, 20)
        content.setPadding(l + p, t + Ui.dp(act, 28), r + p, b + p)
    }

    private fun build() {
        val c = act
        content.addView(Ui.text(c, Branding.APP_NAME, 34f, bold = true).apply { typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
        content.addView(Ui.text(c, "Turn a song into physics: planned bounces, ring chaos, ballistic arcs and falling courses — all locked to the music.", 15f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 20)) })

        val card = Ui.column(c).apply {
            background = Ui.rounded(Ui.CARD, Ui.dp(c, 20).toFloat())
            setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16))
        }
        card.addView(Ui.button(c, "Load audio file", primary = true) {
            act.pickDocument(arrayOf("audio/*", "application/ogg", "application/x-flac")) { act.openUri(it, fromPicker = true) }
        }, Ui.matchWrap())
        card.addView(Ui.button(c, "Load MIDI file") {
            act.pickDocument(arrayOf("audio/midi", "audio/x-midi", "application/x-midi", "audio/mid", "audio/sp-midi", "application/octet-stream")) { act.openUri(it, fromPicker = true) }
        }, Ui.matchWrap().apply { topMargin = Ui.dp(c, 10) })
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.button(c, "Demo song") { act.openDemo() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.button(c, "Sandbox") { act.openSandbox() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(c, 10) })
        card.addView(row, Ui.matchWrap().apply { topMargin = Ui.dp(c, 10) })
        card.addView(Ui.text(c, "Demo: “${DemoSong.TITLE}”, an original composition played with the bundled SoundFont. Sandbox: no music, tap to add balls.", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 10), 0, 0) })
        content.addView(card, Ui.matchWrap())

        content.addView(Ui.label(c, "Recent"))
        content.addView(recentList, Ui.matchWrap())

        content.addView(Ui.label(c, "Presets"))
        content.addView(Ui.button(c, "My presets…") { presetsDialog() }, Ui.matchWrap())

        content.addView(View(c), LinearLayout.LayoutParams(1, 0, 1f))
        content.addView(Ui.text(c, "Formats: MIDI (.mid) · WAV · MP3 · OGG · M4A/AAC · FLAC (as supported by this device). Files are opened with the system picker and never leave your device.", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 24), 0, 0) })
    }

    fun refresh() {
        val c = act
        recentList.removeAllViews()
        val items = app.recents.list()
        if (items.isEmpty()) {
            recentList.addView(Ui.text(c, "Nothing yet — load a song to start.", 13f, Ui.MUTED))
            return
        }
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        for (item in items) {
            val v = Ui.column(c).apply {
                background = Ui.ripple(Ui.rounded(Ui.CARD, Ui.dp(c, 14).toFloat()))
                setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10))
                isClickable = true; isFocusable = true
                addView(Ui.text(c, item.title, 15f, bold = true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
                addView(Ui.text(c, "${item.kind} · ${df.format(Date(item.openedAt))}", 12f, Ui.MUTED))
                setOnClickListener { act.openUri(android.net.Uri.parse(item.uri), fromPicker = false) }
                setOnLongClickListener {
                    Ui.dialog(act, item.title).setItems(arrayOf("Open", "Remove from recent")) { _, which ->
                        if (which == 0) act.openUri(android.net.Uri.parse(item.uri), fromPicker = false)
                        else { app.recents.remove(item.uri); refresh() }
                    }.show()
                    true
                }
                contentDescription = "${item.title}, ${item.kind}. Long press for options."
            }
            recentList.addView(v, Ui.matchWrap().apply { bottomMargin = Ui.dp(c, 8) })
        }
    }

    private fun presetsDialog() {
        val c = act
        val col = Ui.column(c).apply { setPadding(Ui.dp(c, 22), Ui.dp(c, 4), Ui.dp(c, 22), Ui.dp(c, 12)) }
        val custom = app.presets.all()
        col.addView(Ui.text(c, "${PresetManager.builtIns.size} built-in presets. Presets are versioned JSON files; imported presets are validated and clamped to safe ranges.", 13f, Ui.MUTED))
        if (custom.isEmpty()) col.addView(Ui.text(c, "No imported presets yet.", 14f).apply { setPadding(0, Ui.dp(c, 12), 0, 0) })
        lateinit var dialog: android.app.AlertDialog
        for (p in custom) {
            col.addView(Ui.button(c, "${p.name} · ${p.mechanic.label}") {
                Ui.dialog(act, p.name).setItems(arrayOf("Export…", "Delete")) { _, which ->
                    if (which == 0) act.createDocument("application/json", "${p.id}.json") { uri ->
                        act.scope.launch {
                            val err = withContext(Dispatchers.IO) { runCatching { act.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(PresetManager.export(p).toByteArray()) } }.exceptionOrNull() }
                            act.toast(if (err == null) "Preset exported" else "Export failed: ${err.message}")
                        }
                    } else { app.presets.delete(p.id); dialog.dismiss(); act.toast("Deleted “${p.name}”") }
                }.show()
            }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, Ui.matchWrap().apply { topMargin = Ui.dp(c, 8) })
        }
        dialog = Ui.dialog(act, "My presets").setView(ScrollView(c).apply { addView(col) })
            .setPositiveButton("Import preset…") { _, _ ->
                act.pickDocument(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) { uri ->
                    act.scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { app.presets.import(act.readText(uri)) } }
                        act.toast(r.fold({ "Imported “${it.name}” (${it.mechanic.label})" }, { "Invalid preset: ${it.message}" }))
                    }
                }
            }
            .setNegativeButton("Close", null).show()
    }
}
