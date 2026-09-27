package com.rhythmphysics.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val names = listOf("Square", "Circle", "Arch", "Platform").map { it.lowercase() }
        setContentView(TextView(this).apply { text = getString(R.string.app_name) + " " + names.joinToString { it } })
    }
}
