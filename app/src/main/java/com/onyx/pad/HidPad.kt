package com.onyx.pad

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import java.util.concurrent.Executors

/** Registers the phone as a Bluetooth HID gamepad (report: 16 btn, hat, 4 sticks, 2 triggers = 9 bytes). */
@SuppressLint("MissingPermission")
class HidPad(private val ctx: Context, private val onState: (String) -> Unit) {
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

    private val cb = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(d: BluetoothDevice?, registered: Boolean) =
            onState(if (registered) "TAP TO CONNECT" else "BLUETOOTH HID UNAVAILABLE")
        override fun onConnectionStateChanged(d: BluetoothDevice, s: Int) {
            if (s == BluetoothProfile.STATE_CONNECTED) { host = d; onState("CONNECTED · " + (d.name ?: d.address)) }
            else if (s == BluetoothProfile.STATE_DISCONNECTED && d == host) { host = null; onState("TAP TO CONNECT") }
        }
    }

    fun start() {
        adapter?.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice; hid = h
                h.registerApp(BluetoothHidDeviceAppSdpSettings("Onyx Pad", "Gamepad", "Onyx",
                    BluetoothHidDevice.SUBCLASS2_GAMEPAD, desc), null, null, ex, cb)
            }
            override fun onServiceDisconnected(p: Int) { hid = null }
        }, BluetoothProfile.HID_DEVICE)
    }
    fun paired(): List<BluetoothDevice> = adapter?.bondedDevices?.toList() ?: emptyList()
    fun connect(d: BluetoothDevice) { hid?.connect(d) }
    fun send(r: ByteArray) { val h = host ?: return; hid?.sendReport(h, 1, r) }
    fun stop() { hid?.unregisterApp(); adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) }
}
