package com.onyx.pad

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var pad: PadView
    private lateinit var net: NetPad

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        net = NetPad(
            this,
            onStatus = { s -> runOnUiThread { pad.status = s } },
            onEvent = { e -> runOnUiThread { pad.event = e } })
        pad = PadView(this, { net.send(it) }, { showHostDialog() }, { showLogs() })
        pad.status = "SEARCHING…"
        setContentView(pad)
    }

    override fun onResume() {
        super.onResume()
        if (::net.isInitialized) net.onResume()
    }

    override fun onPause() {
        if (::net.isInitialized) net.stop()
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(f: Boolean) {
        if (f) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun showHostDialog() {
        val input = EditText(this).apply {
            setText(net.savedHost())
            hint = "192.168.1.42"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Laptop IP")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> net.setHost(input.text.toString()) }
            .setNeutralButton("Retry") { _, _ -> net.retry(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLogs() {
        val lines = net.logLines()
        val tv = TextView(this).apply {
            text = if (lines.isEmpty()) "(no log yet)" else lines.joinToString("\n")
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(32, 24, 32, 24)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("NET log (${lines.size}/40)")
            .setView(scroll)
            .setPositiveButton("Close") { _, _ -> }
            .show()
    }

    override fun onDestroy() {
        net.stop()
        super.onDestroy()
    }
}
