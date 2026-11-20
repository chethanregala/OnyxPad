package com.onyx.pad

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var pad: PadView
    private lateinit var hid: HidPad

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        pad = PadView(this, { hid.send(it) }, { pick() })
        hid = HidPad(
            this,
            onState = { s -> runOnUiThread { pad.status = s } },
            onEvent = { e -> runOnUiThread { pad.event = e } })
        setContentView(pad)
        if (Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) hid.start()
        else requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) hid.start()
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(f: Boolean) {
        if (f) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    @Suppress("MissingPermission")
    private fun pick() {
        val l = hid.paired()
        val names = if (l.isEmpty()) arrayOf("NO PAIRED DEVICES")
            else l.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Select laptop")
            .setItems(names) { _, i -> if (l.isNotEmpty()) hid.connect(l[i]) }
            .setNeutralButton("Pair new laptop") { _, _ ->
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
            }.show()
    }

    override fun onDestroy() { hid.stop(); super.onDestroy() }
}
