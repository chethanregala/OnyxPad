package com.onyx.pad

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/** Registers the phone as a Bluetooth HID gamepad (report: 16 btn, hat, 4 sticks, 2 triggers = 9 bytes). */
@SuppressLint("MissingPermission")
class HidPad(
    private val ctx: Context,
    private val onState: (String) -> Unit,
    private val onEvent: (String) -> Unit = {}
) {
    private companion object {
        const val TAG = "OnyxHid"
        fun stateName(s: Int) = when (s) {
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            else -> "UNKNOWN($s)"
        }
    }

    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private val ex = Executors.newSingleThreadExecutor()
    private val adapter: BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    private val desc = ("05 01 09 05 A1 01 85 01 " +
        "05 09 19 01 29 10 15 00 25 01 75 01 95 10 81 02 " +
        "05 01 09 39 15 01 25 08 75 04 95 01 81 42 75 04 95 01 81 03 " +
        "09 30 09 31 09 32 09 35 15 81 25 7F 75 08 95 04 81 02 " +
        "09 33 09 34 15 00 26 FF 00 75 08 95 02 81 02 C0")
        .split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private val qos = BluetoothHidDeviceAppQosSettings(
        BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250,
        BluetoothHidDeviceAppQosSettings.MAX)

    private fun label(d: BluetoothDevice?) = d?.let { it.name ?: it.address } ?: "null"

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onEvent(msg)
    }

    private val cb = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(d: BluetoothDevice?, registered: Boolean) {
            log("onAppStatusChanged(device=${label(d)}, registered=$registered)")
            if (!registered) { onState("HID REGISTER FAILED"); return }
            onState("READY – TAP TO CONNECT")
            if (d != null) connect(d)
        }

        override fun onConnectionStateChanged(d: BluetoothDevice, s: Int) {
            log("onConnectionStateChanged(device=${label(d)}, state=${stateName(s)})")
            when (s) {
                BluetoothProfile.STATE_CONNECTED -> { host = d; onState("CONNECTED · ${label(d)}") }
                BluetoothProfile.STATE_CONNECTING -> onState("CONNECTING…")
                BluetoothProfile.STATE_DISCONNECTED -> { if (d == host) host = null; onState("DISCONNECTED") }
            }
        }
    }

    fun start() {
        onState("REGISTERING…")
        val a = adapter
        if (a == null) { log("no Bluetooth adapter"); onState("HID REGISTER FAILED"); return }
        log("requesting HID device profile")
        a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice; hid = h
                log("HID profile connected")
                val ok = h.registerApp(BluetoothHidDeviceAppSdpSettings("Onyx Pad", "Gamepad", "Onyx",
                    BluetoothHidDevice.SUBCLASS2_GAMEPAD, desc), qos, qos, ex, cb)
                log("registerApp -> $ok")
                if (!ok) onState("HID REGISTER FAILED")
            }

            override fun onServiceDisconnected(p: Int) {
                log("HID profile disconnected")
                hid = null
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    fun paired(): List<BluetoothDevice> = adapter?.bondedDevices?.toList() ?: emptyList()

    fun connect(d: BluetoothDevice) {
        val h = hid
        if (h == null) {
            log("connect(${label(d)}) -> no HID profile")
            onState("HID REGISTER FAILED")
            return
        }
        onState("CONNECTING…")
        val ok = h.connect(d)
        log("connect(${label(d)}) -> $ok")
        if (!ok) onState("CONNECT REFUSED")
    }

    fun send(r: ByteArray) { val h = host ?: return; hid?.sendReport(h, 1, r) }

    fun stop() { hid?.unregisterApp(); adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) }
}
