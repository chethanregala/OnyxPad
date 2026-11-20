package com.onyx.pad

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import kotlin.math.max

/**
 * Sends the 9-byte pad report to the laptop over Wi-Fi/UDP and receives rumble packets back.
 *
 * Phone -> laptop: 0x01 + 9 byte report, immediately on change and as a 20 Hz heartbeat,
 *                  plus an ASCII "ONYX?" probe every second (broadcast until a laptop answers).
 * Laptop -> phone: "ONYX!" + hostname (discovery reply / keepalive) and 0x02 + large + small.
 *
 * All socket work happens on a single background thread; public methods may be called from the UI thread.
 */
class NetPad(
    ctx: Context,
    private val onStatus: (String) -> Unit,
    private val onEvent: (String) -> Unit = {}
) {
    private companion object {
        const val TAG = "OnyxNet"
        const val PORT = 7777
        const val PREF = "onyx_pad"
        const val KEY_HOST = "host_ip"
        const val KEY_NAME = "host_name"
        const val HEARTBEAT_MS = 50L       // 20 Hz
        const val DISCOVER_MS = 1000L
        const val LOST_MS = 3000L
        const val MAX_LOG = 40
        val REPLY_TAG = "ONYX!".toByteArray(Charsets.US_ASCII)
    }

    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val vib = app.getSystemService(Vibrator::class.java)

    @Volatile private var running = false
    @Volatile private var socketRef: DatagramSocket? = null
    @Volatile private var pending: ByteArray? = null
    @Volatile private var lastReport = ByteArray(9)
    @Volatile private var wantHost: String? = prefs.getString(KEY_HOST, null)
    @Volatile private var target: InetAddress? = null
    @Volatile private var hostName: String = prefs.getString(KEY_NAME, "") ?: ""
    @Volatile private var lastHeard = 0L
    @Volatile private var forceNow = false

    private val events = ArrayDeque<String>(MAX_LOG)
    private val statusLock = Any()
    private var lastStatus = ""

    /** Starts the UDP worker (idempotent). */
    @Synchronized
    fun start() {
        if (running) {
            forceNow = true
            return
        }
        running = true
        Thread({ loop() }, "onyx-net").apply { isDaemon = true }.start()
    }

    /** Reconnect automatically when the app comes back to the foreground. */
    fun onResume() {
        start()
        forceNow = true
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            socketRef?.close()
        } catch (_: Exception) {
        }
        main.post {
            try {
                vib?.cancel()
            } catch (_: Exception) {
            }
        }
        log("stopping")
    }

    /** Called from the UI thread with PadView's 9-byte report. Never blocks. */
    fun send(report: ByteArray) {
        if (report.size < 9) return
        val copy = report.copyOf(9)
        lastReport = copy
        pending = copy
    }

    fun savedHost(): String = wantHost ?: prefs.getString(KEY_HOST, "") ?: ""

    /** Saves a manual host and starts looking for it immediately. */
    fun setHost(host: String) {
        val h = host.trim()
        wantHost = if (h.isEmpty()) null else h
        val ed = prefs.edit()
        if (h.isEmpty()) ed.remove(KEY_HOST) else ed.putString(KEY_HOST, h)
        ed.apply()
        target = null
        lastHeard = 0
        forceNow = true
        setStatus("SEARCHING…")
        log(if (h.isEmpty()) "host cleared — auto-discovery" else "host set to $h")
    }

    /** Re-runs discovery with the address currently typed in the dialog. */
    fun retry(host: String) {
        val h = host.trim()
        if (h.isNotEmpty()) {
            setHost(h)
        } else {
            target = null
            lastHeard = 0
            forceNow = true
            setStatus("SEARCHING…")
        }
        log("retry requested")
    }

    @Synchronized
    fun logLines(): List<String> = events.toList()

    private fun loop() {
        while (running) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(PORT))
                    soTimeout = 20
                }
                socketRef = socket
                log("listening on UDP $PORT")
                session(socket)
            } catch (e: SocketException) {
                log("socket error: ${e.message}")
            } catch (e: Exception) {
                log("net error: ${e.message}")
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                socketRef = null
            }
            if (running) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        running = false
        target = null
        setStatus("SEARCHING…")
        log("stopped")
    }

    private fun session(socket: DatagramSocket) {
        if (target == null) setStatus("SEARCHING…")

        var lastSent = 0L
        var lastDiscover = 0L
        var resolvedKey: String? = null
        var resolved: InetAddress? = null
        var bcAt = 0L
        var broadcasts: List<InetAddress> = emptyList()
        val buf = ByteArray(256)

        while (running) {
            val now = SystemClock.elapsedRealtime()

            // Resolve the saved/manual host off the UI thread.
            val want = wantHost
            if (want.isNullOrBlank()) {
                resolvedKey = null
                resolved = null
            } else if (want != resolvedKey) {
                resolvedKey = want
                resolved = try {
                    InetAddress.getByName(want)
                } catch (e: Exception) {
                    log("cannot resolve '$want': ${e.message}")
                    null
                }
            }

            // Report on change, otherwise heartbeat at 20 Hz while connected.
            val change = pending
            if (change != null) {
                pending = null
                lastReport = change
            }
            val t = target
            if (t != null && (change != null || now - lastSent >= HEARTBEAT_MS)) {
                sendReport(socket, t, lastReport)
                lastSent = now
            }

            // Discovery probes. While connected a unicast probe doubles as a keepalive so the
            // phone can detect a dead laptop ("LOST" after 3 s without a reply).
            if (forceNow) {
                forceNow = false
                lastDiscover = 0
            }
            if (now - lastDiscover >= DISCOVER_MS) {
                lastDiscover = now
                if (t != null) {
                    sendText(socket, t, "ONYX?")
                } else {
                    resolved?.let { sendText(socket, it, "ONYX?") }
                    if (broadcasts.isEmpty() || now - bcAt > 10_000) {
                        broadcasts = broadcastAddresses()
                        bcAt = now
                    }
                    for (addr in broadcasts) sendText(socket, addr, "ONYX?")
                }
            }

            // Link timeout.
            val current = target
            if (current != null && lastHeard > 0 && now - lastHeard > LOST_MS) {
                log("no reply from ${current.hostAddress} for 3 s")
                target = null
                lastHeard = 0
                setStatus("LOST – RECONNECTING…")
                forceNow = true
            }

            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                handle(packet)
            } catch (_: SocketTimeoutException) {
            } catch (e: SocketException) {
                if (running) throw e else break
            }
        }
    }

    private fun handle(packet: DatagramPacket) {
        val len = packet.length
        if (len < 1) return
        val addr = packet.address ?: return
        val data = packet.data
        val now = SystemClock.elapsedRealtime()

        // Rumble: 0x02, large, small.
        if ((data[0].toInt() and 0xFF) == 0x02 && len >= 3) {
            val current = target
            if (current == null || current.hostAddress == addr.hostAddress) lastHeard = now
            val large = data[1].toInt() and 0xFF
            val small = data[2].toInt() and 0xFF
            main.post { rumble(large, small) }
            return
        }

        // Discovery reply / keepalive: "ONYX!" + hostname.
        if (len >= REPLY_TAG.size && data.copyOfRange(0, REPLY_TAG.size).contentEquals(REPLY_TAG)) {
            val name = String(data, REPLY_TAG.size, len - REPLY_TAG.size, Charsets.UTF_8).trim()
            val old = target
            val fresh = old == null || old.hostAddress != addr.hostAddress
            target = addr
            lastHeard = now

            if (fresh) {
                hostName = if (name.isNotEmpty()) name else prefs.getString(KEY_NAME, "") ?: ""
                val ed = prefs.edit().putString(KEY_HOST, addr.hostAddress)
                if (name.isNotEmpty()) ed.putString(KEY_NAME, name)
                ed.apply()
                log("connected to ${addr.hostAddress}${if (hostName.isNotEmpty()) " ($hostName)" else ""}")
            } else if (name.isNotEmpty() && name != hostName) {
                hostName = name
                prefs.edit().putString(KEY_NAME, name).apply()
            }

            setStatus("CONNECTED · ${if (hostName.isNotBlank()) hostName else addr.hostAddress}")
        }
    }

    /** Runs on the UI thread. */
    private fun rumble(large: Int, small: Int) {
        val amp = max(large, small).coerceIn(0, 255)
        try {
            vib?.cancel()
            if (amp > 0) vib?.vibrate(VibrationEffect.createOneShot(2000, amp))
        } catch (e: Exception) {
            log("vibration failed: ${e.message}")
        }
    }

    private fun sendReport(socket: DatagramSocket, to: InetAddress, report: ByteArray) {
        try {
            val out = ByteArray(10)
            out[0] = 0x01
            System.arraycopy(report, 0, out, 1, 9)
            socket.send(DatagramPacket(out, out.size, to, PORT))
        } catch (_: Exception) {
            // Transient Wi-Fi failures are handled by the timeout/discovery loop.
        }
    }

    private fun sendText(socket: DatagramSocket, to: InetAddress, text: String) {
        try {
            val data = text.toByteArray(Charsets.US_ASCII)
            socket.send(DatagramPacket(data, data.size, to, PORT))
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            out.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        try {
            val dhcp = app.getSystemService(WifiManager::class.java)?.dhcpInfo
            if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0) {
                val b = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
                out.add(ip4(b))
            }
        } catch (_: Exception) {
        }
        try {
            val e = NetworkInterface.getNetworkInterfaces()
            while (e.hasMoreElements()) {
                val nif = e.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val b = ia.broadcast
                    if (b is Inet4Address) out.add(b)
                }
            }
        } catch (_: Exception) {
        }
        return out.distinctBy { it.hostAddress }
    }

    /** Android's DhcpInfo ints are little-endian. */
    private fun ip4(v: Int) = InetAddress.getByAddress(
        byteArrayOf(
            (v and 0xFF).toByte(),
            (v ushr 8 and 0xFF).toByte(),
            (v ushr 16 and 0xFF).toByte(),
            (v ushr 24 and 0xFF).toByte()
        )
    )

    @Synchronized
    private fun log(msg: String) {
        Log.d(TAG, msg)
        events.addLast(msg)
        while (events.size > MAX_LOG) events.removeFirst()
        onEvent(msg)
    }

    private fun setStatus(s: String) {
        synchronized(statusLock) {
            if (s == lastStatus) return
            lastStatus = s
        }
        onStatus(s)
    }
}
