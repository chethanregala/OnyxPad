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
 * One socket is bound to an ephemeral local port for the lifetime of a session (start -> stop).
 * Each packet carries its destination explicitly; the socket is never connect()ed.
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
        const val RECOVER_MS = 1000L
        const val TICK_MS = 20L
        const val MAX_LOG = 40
        val REPLY_TAG = "ONYX!".toByteArray(Charsets.US_ASCII)
        val PROBE = "ONYX?".toByteArray(Charsets.US_ASCII)
        val BROADCAST_ALL = byteArrayOf(-1, -1, -1, -1) // 255.255.255.255
    }

    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val vibrator = app.getSystemService(Vibrator::class.java)

    private val lifecycleLock = Any()
    private val socketLock = Any()
    private val eventsLock = Any()

    @Volatile private var running = false
    private var recvThread: Thread? = null
    private var tickThread: Thread? = null

    private var socket: DatagramSocket? = null
    @Volatile private var recreateAt = 0L

    @Volatile private var pending: ByteArray? = null
    @Volatile private var lastReport = ByteArray(9)
    @Volatile private var wantHost: String? = prefs.getString(KEY_HOST, null)
    @Volatile private var target: InetAddress? = null
    @Volatile private var hostName: String = prefs.getString(KEY_NAME, "") ?: ""
    @Volatile private var lastHeard = 0L
    @Volatile private var forceDiscover = false
    @Volatile private var hostRevision = 0

    private val events = ArrayDeque<String>(MAX_LOG)
    private val eventCounts = HashMap<String, Int>()
    private val statusLock = Any()
    private var lastStatus = ""

    /** Starts a session: one socket plus the receive and tick threads. Idempotent. */
    fun start() {
        synchronized(lifecycleLock) {
            if (running) return
            running = true
            recreateAt = 0
            target = null
            lastHeard = 0
            forceDiscover = true
            recvThread = Thread({ receiveLoop() }, "onyx-recv").apply {
                isDaemon = true
                start()
            }
            tickThread = Thread({ tickLoop() }, "onyx-tick").apply {
                isDaemon = true
                start()
            }
        }
        setStatus("SEARCHING…")
    }

    /** Called from onResume only. */
    fun onResume() = start()

    /** Ends the session: closes the socket, joins the threads and resets the session state. */
    fun stop() {
        var r: Thread? = null
        var t: Thread? = null
        synchronized(lifecycleLock) {
            running = false
            synchronized(socketLock) {
                val s = socket
                socket = null
                try {
                    s?.close()
                } catch (_: Exception) {
                }
            }
            recreateAt = 0
            r = recvThread
            t = tickThread
        }
        joinQuietly(r)
        joinQuietly(t)
        synchronized(lifecycleLock) {
            recvThread = null
            tickThread = null
            pending = null
            target = null
            lastHeard = 0
            forceDiscover = false
        }
        synchronized(socketLock) {
            val s = socket
            socket = null
            try {
                s?.close()
            } catch (_: Exception) {
            }
            recreateAt = 0
        }
        synchronized(statusLock) { lastStatus = "" }
        synchronized(eventsLock) {
            eventCounts.clear()
        }
        main.post {
            try {
                vibrator?.cancel()
            } catch (_: Exception) {
            }
        }
        Log.d(TAG, "stopped")
    }

    /** Called from the UI thread with PadView's 9-byte report. Never blocks. */
    fun send(report: ByteArray) {
        if (report.size < 9) return
        val copy = report.copyOf(9)
        lastReport = copy
        pending = copy
    }

    fun savedHost(): String = wantHost ?: prefs.getString(KEY_HOST, "") ?: ""

    /**
     * Changes the manual target only: saves it and lets discovery probe that address.
     * Never touches the socket.
     */
    fun setHost(host: String) {
        val h = host.trim()
        wantHost = if (h.isEmpty()) null else h
        hostRevision++
        val ed = prefs.edit()
        if (h.isEmpty()) ed.remove(KEY_HOST) else ed.putString(KEY_HOST, h)
        ed.apply()
        target = null
        lastHeard = 0
        forceDiscover = true
        setStatus("SEARCHING…")
        log(if (h.isEmpty()) "manual host cleared" else "manual host $h")
    }

    /** Resets discovery state with the address currently typed in the dialog. Never touches the socket. */
    fun retry(host: String) {
        val h = host.trim()
        if (h.isNotEmpty()) {
            setHost(h)
            return
        }
        target = null
        lastHeard = 0
        forceDiscover = true
        hostRevision++
        setStatus("SEARCHING…")
        log("retry requested")
    }

    fun logLines(): List<String> = synchronized(eventsLock) { events.toList() }

    // ------------------------------------------------------------------ receive thread

    private fun receiveLoop() {
        val buf = ByteArray(256)
        while (running) {
            val s = synchronized(socketLock) { socket }
            if (s == null) {
                pause(100)
                continue
            }
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (!running) break
                socketFailed(s, "receive error", e)
                continue
            }
            try {
                handle(packet)
            } catch (e: Exception) {
                logError("packet error", e)
            }
        }
    }

    // ------------------------------------------------------------------ tick thread

    private fun tickLoop() {
        var lastSent = 0L
        var lastDiscover = 0L
        var resolvedKey: String? = null
        var resolvedRevision = -1
        var resolved: InetAddress? = null
        var broadcastAt = 0L
        var broadcastTargets: List<InetAddress> = emptyList()

        while (running) {
            val now = SystemClock.elapsedRealtime()
            val s = ensureSocket()

            // Resolve a saved/manual host off the UI thread. Retry bumps hostRevision.
            val want = wantHost
            val revision = hostRevision
            if (want.isNullOrBlank()) {
                resolvedKey = null
                resolvedRevision = revision
                resolved = null
            } else if (want != resolvedKey || revision != resolvedRevision) {
                resolvedKey = want
                resolvedRevision = revision
                resolved = try {
                    InetAddress.getByName(want)
                } catch (e: Exception) {
                    logError("cannot resolve '$want'", e)
                    null
                }
            }

            val change = pending
            if (change != null) {
                pending = null
                lastReport = change
            }

            if (s != null) {
                val t = target

                // Report on change, otherwise heartbeat at 20 Hz.
                if (t != null && (change != null || now - lastSent >= HEARTBEAT_MS)) {
                    if (sendTo(s, reportPacket(lastReport), t)) lastSent = now
                }

                // Discovery probe. While connected a unicast probe is also the keepalive that
                // lets the phone notice a dead laptop after 3 s.
                if (forceDiscover) {
                    forceDiscover = false
                    lastDiscover = 0
                }
                if (now - lastDiscover >= DISCOVER_MS) {
                    lastDiscover = now
                    if (t != null) {
                        sendTo(s, PROBE, t)
                        if (resolved != null && resolved.hostAddress != t.hostAddress) {
                            sendTo(s, PROBE, resolved)
                        }
                    } else {
                        if (broadcastTargets.isEmpty() || now - broadcastAt > 10_000) {
                            broadcastTargets = broadcastAddresses()
                            broadcastAt = now
                        }
                        for (addr in broadcastTargets) {
                            if (!sendTo(s, PROBE, addr)) break
                        }
                        resolved?.let { sendTo(s, PROBE, it) }
                    }
                }
            }

            // Link timeout (also while the socket is being recovered).
            val current = target
            if (current != null && lastHeard > 0 && now - lastHeard > LOST_MS) {
                target = null
                lastHeard = 0
                setStatus("LOST – RECONNECTING…")
                log("no reply from ${current.hostAddress} for 3 s")
                forceDiscover = true
            }

            pause(TICK_MS)
        }
    }

    /** Opens the socket if needed. Recovery attempts are spaced by [RECOVER_MS]. */
    private fun ensureSocket(): DatagramSocket? {
        var result: DatagramSocket? = null
        synchronized(socketLock) {
            if (!running) return null
            val existing = socket
            if (existing != null) {
                result = existing
            } else {
                val now = SystemClock.elapsedRealtime()
                if (now >= recreateAt) {
                    try {
                        val s = DatagramSocket(null)
                        s.reuseAddress = true
                        s.broadcast = true
                        s.soTimeout = 500
                        s.bind(InetSocketAddress(0))
                        socket = s
                        log("socket ready")
                        result = s
                    } catch (e: Exception) {
                        logError("socket open error", e)
                        recreateAt = SystemClock.elapsedRealtime() + RECOVER_MS
                    }
                }
            }
        }
        return result
    }

    /** Closes a broken socket once and schedules its replacement, without touching the status. */
    private fun socketFailed(s: DatagramSocket, what: String, e: Exception) {
        if (synchronized(socketLock) { socket } !== s) return
        logError(what, e)
        synchronized(socketLock) {
            if (socket !== s) return
            socket = null
            try {
                s.close()
            } catch (_: Exception) {
            }
            recreateAt = SystemClock.elapsedRealtime() + RECOVER_MS
        }
    }

    // ------------------------------------------------------------------ packets

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

    private fun reportPacket(report: ByteArray): ByteArray {
        val out = ByteArray(10)
        out[0] = 0x01
        System.arraycopy(report, 0, out, 1, 9)
        return out
    }

    /** Sends with an explicit destination; never connect()s the socket. */
    private fun sendTo(s: DatagramSocket, data: ByteArray, to: InetAddress): Boolean {
        return try {
            s.send(DatagramPacket(data, data.size, to, PORT))
            true
        } catch (e: Exception) {
            if (running) socketFailed(s, "send error", e)
            false
        }
    }

    // ------------------------------------------------------------------ rumble

    /** Runs on the UI thread. */
    private fun rumble(large: Int, small: Int) {
        val amp = max(large, small).coerceIn(0, 255)
        try {
            vibrator?.cancel()
            if (amp > 0) vibrator?.vibrate(VibrationEffect.createOneShot(2000, amp))
        } catch (e: Exception) {
            logError("vibration failed", e)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        out.add(InetAddress.getByAddress(BROADCAST_ALL))
        try {
            @Suppress("DEPRECATION")
            val dhcp = app.getSystemService(WifiManager::class.java)?.dhcpInfo
            if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0) {
                val b = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
                out.add(ip4(b))
            }
        } catch (e: Exception) {
            logError("subnet broadcast lookup failed", e)
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

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun joinQuietly(t: Thread?) {
        if (t == null) return
        try {
            t.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun logError(what: String, e: Throwable) {
        log("$what: ${e.message ?: e.javaClass.simpleName}", e)
    }

    /** Logs each distinct event once; repeats update the last-event line with an "(xN)" counter. */
    private fun log(msg: String, error: Throwable? = null) {
        if (error != null) Log.e(TAG, msg, error) else Log.d(TAG, msg)
        var shown = msg
        synchronized(eventsLock) {
            val count = (eventCounts[msg] ?: 0) + 1
            eventCounts[msg] = count
            if (count == 1) {
                events.addLast(msg)
                while (events.size > MAX_LOG) events.removeFirst()
            } else {
                shown = "$msg (x$count)"
            }
        }
        onEvent(shown)
    }

    private fun setStatus(s: String) {
        synchronized(statusLock) {
            if (s == lastStatus) return
            lastStatus = s
        }
        onStatus(s)
    }
}
