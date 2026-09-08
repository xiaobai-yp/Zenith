package com.zenith.thermal

import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Singleton client for IPC with zenithd via /dev/socket/zenithd.
 *
 * Wire format (all little-endian):
 *   version(u8) msg_type(u8) payload_len(u16) seq(u32) crc(u32) payload(...)
 *
 * All socket state is guarded by ioLock. All blocking I/O runs on a dedicated
 * daemon thread; sendCommand() blocks the calling thread up to IO_TIMEOUT_MS
 * while the actual socket work happens in the background.
 */
object ZenithDaemonClient {

    private const val TAG = "ZDaemonClient"
    private const val SOCKET_PATH = "/dev/socket/zenithd"
    private const val HEADER_SIZE = 10
    private const val MSG_VERSION = 1
    private const val IO_TIMEOUT_MS = 3_000

    const val MSG_HEARTBEAT: Byte = 0x01
    const val MSG_GET_STATUS: Byte = 0x10
    const val MSG_SET_PROFILE: Byte = 0x20
    const val MSG_GET_APPS: Byte = 0x30
    const val MSG_SET_THERMAL: Byte = 0x40
    const val MSG_ERROR: Byte = (-1).toByte()

    private val ioLock = Any()
    private var socket: LocalSocket? = null
    @Volatile var isConnected: Boolean = false
        private set
    private val seq = AtomicInteger(0)

    // Multiple consumers (AppMonitorService, MainActivity) can register
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addConnectionListener(l: (Boolean) -> Unit) { listeners.add(l) }
    fun removeConnectionListener(l: (Boolean) -> Unit) { listeners.remove(l) }

    private fun notifyListeners(connected: Boolean) {
        for (l in listeners) {
            try { l.invoke(connected) } catch (_: Throwable) {}
        }
    }

    private class Work(
        val msgType: Byte,
        val payload: ByteArray,
        val result: Array<ByteArray?> = arrayOfNulls(1),
        val latch: CountDownLatch = CountDownLatch(1)
    )

    private val workQueue = LinkedBlockingQueue<Work>()

    // Dedicated I/O thread — processes one work item at a time, keeping the
    // socket open across requests so there's no connect overhead per call.
    private val ioThread = Thread({
        while (!Thread.currentThread().isInterrupted) {
            try {
                val w = workQueue.take()
                val result = synchronized(ioLock) {
                    if (socket == null || socket?.isConnected != true) doConnectLocked()
                    if (socket == null || socket?.isConnected != true) {
                        null
                    } else {
                        try {
                            doSendRecv(w.msgType, w.payload)
                        } catch (e: IOException) {
                            Log.w(TAG, "IPC error: ${e.message}")
                            disconnectLocked()
                            null
                        }
                    }
                }
                w.result[0] = result
                w.latch.countDown()
            } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
    }, "zenith-daemon-io").apply { isDaemon = true; start() }

    fun connect(): Boolean = synchronized(ioLock) { doConnectLocked() }

    /** Non-blocking: enqueue a heartbeat that connects if needed. */
    fun requestConnect() { workQueue.offer(Work(MSG_HEARTBEAT, ByteArray(0))) }

    fun disconnect() { synchronized(ioLock) { disconnectLocked() } }

    /** Locked: close any existing socket. */
    private fun disconnectLocked() {
        val was = isConnected
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        isConnected = false
        if (was) notifyListeners(false)
    }

    /** Locked: open a new socket to the daemon. */
    private fun doConnectLocked(): Boolean {
        disconnectLocked()
        return try {
            val s = LocalSocket()
            s.connect(LocalSocketAddress(SOCKET_PATH, LocalSocketAddress.Namespace.FILESYSTEM))
            s.soTimeout = IO_TIMEOUT_MS
            socket = s
            isConnected = true
            notifyListeners(true)
            Log.i(TAG, "Connected")
            true
        } catch (e: IOException) {
            Log.w(TAG, "Connect failed: ${e.message}")
            socket = null
            isConnected = false
            notifyListeners(false)
            false
        }
    }

    /**
     * Send a command and wait. Safe from any thread (including main).
     * Runs the socket I/O on the daemon thread; the caller blocks until the
     * result is ready (up to IO_TIMEOUT_MS).
     */
    fun sendCommand(msgType: Byte, payload: ByteArray = ByteArray(0)): ByteArray? {
        val w = Work(msgType, payload)
        workQueue.offer(w)
        val ok = w.latch.await(IO_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        return if (ok) w.result[0] else { Log.w(TAG, "Command timed out"); null }
    }

    /** Locked: full request/response exchange on the current socket. */
    private fun doSendRecv(msgType: Byte, payload: ByteArray): ByteArray? {
        sendLocked(msgType, payload)
        return readResponseLocked()
    }

    private fun sendLocked(msgType: Byte, payload: ByteArray) {
        val s = socket ?: throw IOException("No socket")
        val curSeq = seq.getAndIncrement()
        val totalSize = HEADER_SIZE + payload.size
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MSG_VERSION.toByte())
        buf.put(msgType)
        buf.putShort(payload.size.toShort())
        buf.putInt(curSeq)
        buf.putInt(0) // crc placeholder
        buf.put(payload)
        val bytes = buf.array()
        val crc = crc32(bytes)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(6, crc)
        s.outputStream.write(bytes)
        s.outputStream.flush()
    }

    private fun readResponseLocked(): ByteArray? {
        val s = socket ?: throw IOException("No socket")
        val header = ByteArray(HEADER_SIZE)
        readFully(s, header)
        val hdr = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val version = hdr.get().toInt() and 0xFF
        val msgType = hdr.get()
        val payloadLen = hdr.getShort().toInt() and 0xFFFF
        val recvCrc = hdr.getInt()
        if (version != MSG_VERSION) { Log.w(TAG, "Bad version: $version"); return null }
        if (payloadLen > 4096) { Log.w(TAG, "Payload too large"); return null }
        val payload = if (payloadLen > 0) {
            val p = ByteArray(payloadLen); readFully(s, p); p
        } else ByteArray(0)
        // CRC verify (crc field zeroed like the daemon does)
        val fullPacket = header + payload
        val crcBuf = fullPacket.copyOf()
        crcBuf[6] = 0; crcBuf[7] = 0; crcBuf[8] = 0; crcBuf[9] = 0
        if (crc32(crcBuf) != recvCrc) { Log.w(TAG, "CRC mismatch"); return null }
        if (msgType == MSG_ERROR) {
            val msg = if (payload.size >= 2) String(payload, 2, payload.size - 2, Charsets.UTF_8).trim('\u0000') else "unknown"
            Log.w(TAG, "Daemon error: $msg")
            return null
        }
        return payload
    }

    private fun readFully(s: LocalSocket, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = s.inputStream.read(buf, off, buf.size - off)
            if (n == -1) throw IOException("EOF")
            off += n
        }
    }

    private fun crc32(data: ByteArray): Int {
        var crc = -1
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            for (j in 0 until 8) crc = (crc ushr 1) xor (0xEDB88320.toInt() and (-(crc and 1)))
        }
        return crc xor -1
    }

    // ---- Typed helpers ----

    fun getStatus(): StatusResponse? = sendCommand(MSG_GET_STATUS)?.let { parseStatusPayload(it) }

    fun setProfile(profileId: Int): Boolean {
        val data = profileId.toString().toByteArray(Charsets.UTF_8)
        val padded = ByteArray(32); data.copyInto(padded)
        return sendCommand(MSG_SET_PROFILE, padded) != null
    }

    // MSG_SET_APP_PROFILE (0x42): [pkg:64][profile_id:int32]
    fun setAppProfile(pkg: String, profileId: Int): Boolean {
        val buf = ByteBuffer.allocate(64 + 4).order(ByteOrder.LITTLE_ENDIAN)
        val pkgBytes = pkg.toByteArray(Charsets.UTF_8)
        buf.put(pkgBytes, 0, minOf(pkgBytes.size, 64))
        repeat(64 - minOf(pkgBytes.size, 64)) { buf.put(0) }
        buf.putInt(profileId)
        return sendCommand(MSG_SET_APP_PROFILE, buf.array()) != null
    }

    fun getForegroundApp(): String? {
        val payload = sendCommand(MSG_GET_APPS) ?: return null
        val end = payload.indexOf(0).takeIf { it >= 0 } ?: payload.size
        return String(payload, 0, end.coerceAtMost(payload.size), Charsets.UTF_8).trim().ifEmpty { null }
    }

    // MSG_GET_APPS_MAP (0x32) → [count][pkg:32][profile_id:int32] repeated
    fun getAppsMap(): Map<String, Int> {
        val payload = sendCommand(MSG_GET_APPS_MAP) ?: return emptyMap()
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 1) return emptyMap()
        val count = buf.get().toInt() and 0xFF
        val map = HashMap<String, Int>()
        for (i in 0 until count.coerceAtMost(20)) {
            if (buf.remaining() < 32 + 4) break
            val pkg = ByteArray(32); buf.get(pkg)
            val end = pkg.indexOf(0).takeIf { it >= 0 } ?: pkg.size
            val name = String(pkg, 0, end, Charsets.UTF_8).trim()
            val profileId = buf.getInt()
            if (name.isNotEmpty()) map[name] = profileId
        }
        return map
    }

    private fun parseStatusPayload(data: ByteArray): StatusResponse? {
        if (data.size < 6) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val fgPid = buf.get().toInt() and 0xFF
        val profileId = buf.getInt()
        val zoneCount = buf.get().toInt() and 0xFF
        data class Z(val id: Int, val tempMilli: Int, val throttle: Int)
        val zones = mutableListOf<Z>()
        for (i in 0 until zoneCount.coerceAtMost(16)) {
            if (buf.remaining() < 9) break
            zones.add(Z(buf.get().toInt() and 0xFF, buf.getInt(), buf.getInt()))
        }
        if (buf.remaining() < 13) return null
        return StatusResponse(
            foregroundPid = fgPid,
            currentProfileId = profileId,
            thermalZones = zones.map { ThermalZone(it.id, it.tempMilli / 1000.0, it.throttle != 0) },
            batteryOnline = buf.get().toInt() and 0xFF != 0,
            batteryCapacityPct = buf.getInt() / 10.0,
            batteryCurrentUa = buf.getInt(),
            batteryDrainPctPerHr = buf.getInt() / 10.0
        )
    }

    data class ThermalZone(val id: Int, val tempC: Double, val throttled: Boolean)

    data class StatusResponse(
        val foregroundPid: Int,
        val currentProfileId: Int,
        val thermalZones: List<ThermalZone>,
        val batteryOnline: Boolean,
        val batteryCapacityPct: Double,
        val batteryCurrentUa: Int,
        val batteryDrainPctPerHr: Double
    ) {
        val batteryCurrentMa: Double get() = kotlin.math.abs(batteryCurrentUa / 1000.0)
        val batteryCharging: Boolean get() = batteryOnline && batteryCurrentUa >= 0
    }
}