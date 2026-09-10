package com.zenith.thermal

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Singleton client for IPC with zenithd via newline-delimited JSON.
 *
 * Wire format:
 *   Request:  {"cmd":"...","args":{...}}\n
 *   Response: {"ok":true,"data":{...}}\n  or  {"ok":false,"error":"..."}\n
 *
 * Persistent connection — socket stays open across requests.
 * All blocking I/O runs on a dedicated daemon thread.
 */
object ZenithDaemonClient {

    private const val TAG = "ZDaemonClient"
    private const val SOCKET_PATH = "/data/data/com.zenith.thermal/files/zenithd.sock"
    private const val IO_TIMEOUT_MS = 5_000

    private val ioLock = Any()
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private var socket: LocalSocket? = null
    @Volatile var isConnected: Boolean = false
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addConnectionListener(l: (Boolean) -> Unit) { listeners.add(l) }
    fun removeConnectionListener(l: (Boolean) -> Unit) { listeners.remove(l) }

    private fun notifyListeners(connected: Boolean) {
        for (l in listeners) {
            try { l.invoke(connected) } catch (_: Throwable) {}
        }
    }

    // ---- Internal request envelope ----

    private class Work(
        val request: JSONObject,
        val result: Array<Any?> = arrayOfNulls(1),
        val latch: CountDownLatch = CountDownLatch(1),
        val failed: AtomicBoolean = AtomicBoolean(false)
    )

    private val workQueue = LinkedBlockingQueue<Work>(64)

    // Dedicated I/O thread — processes one request at a time, socket stays open.
    private val ioThread = Thread({
        while (!Thread.currentThread().isInterrupted) {
            try {
                val w = workQueue.take()
                val result = synchronized(ioLock) {
                    if (socket == null || socket?.isConnected != true) doConnectLocked()
                    if (socket == null || socket?.isConnected != true) {
                        w.failed.set(true)
                        null
                    } else {
                        try {
                            doSendRecv(w.request)
                        } catch (e: Exception) {
                            Log.w(TAG, "IPC error: ${e.message}")
                            disconnectLocked()
                            w.failed.set(true)
                            null
                        }
                    }
                }
                w.result[0] = result
                w.latch.countDown()
            } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
    }, "zenith-daemon-io").apply { isDaemon = true; start() }

    // ---- Connection management ----

    fun connect(): Boolean = synchronized(ioLock) { doConnectLocked() }

    /** Non-blocking: enqueue a connect check. */
    fun requestConnect() { workQueue.offer(work("status")) }

    fun disconnect() { synchronized(ioLock) { disconnectLocked() } }

    private fun disconnectLocked() {
        val was = isConnected
        try { reader?.close() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        reader = null; writer = null; socket = null
        isConnected = false
        if (was) notifyListeners(false)
    }

    private fun doConnectLocked(): Boolean {
        disconnectLocked()
        return try {
            val s = LocalSocket()
            s.connect(LocalSocketAddress(SOCKET_PATH, LocalSocketAddress.Namespace.FILESYSTEM))
            s.soTimeout = IO_TIMEOUT_MS
            socket = s
            reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8), 4096)
            writer = PrintWriter(s.outputStream, true)
            isConnected = true
            notifyListeners(true)
            Log.i(TAG, "Connected to $SOCKET_PATH")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Connect failed: ${e.message}")
            disconnectLocked()
            false
        }
    }

    // ---- Request / Response ----

    /** Build a Work item from a JSON request object. */
    private fun work(cmd: String, args: JSONObject? = null): Work {
        val req = JSONObject()
        req.put("cmd", cmd)
        if (args != null) req.put("args", args)
        return Work(req)
    }

    /**
     * Enqueue a JSON command and block up to IO_TIMEOUT_MS for the result.
     * Returns the "data" object, null on error/disconnect, or a JSONObject with
     * "error" key on daemon error (callers that check for errors use [sendRequest]).
     */
    fun sendCommand(cmd: String, args: JSONObject? = null): JSONObject? {
        val w = work(cmd, args)
        workQueue.offer(w)
        val ok = w.latch.await(IO_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        if (!ok) { Log.w(TAG, "Command '$cmd' timed out"); return null }
        if (w.failed.get()) return null
        @Suppress("UNCHECKED_CAST")
        return w.result[0] as? JSONObject
    }

    /** Like [sendCommand] but returns error string on daemon error, null on exception. */
    fun sendRequest(cmd: String, args: JSONObject? = null): String? {
        val data = sendCommand(cmd, args) ?: return null
        return if (data.has("error")) data.getString("error") else data.toString()
    }

    /** Locked: write one JSON line, read one JSON line. */
    private fun doSendRecv(request: JSONObject): JSONObject? {
        val w = writer ?: throw Exception("No writer")
        val r = reader ?: throw Exception("No reader")
        w.println(request.toString())
        if (w.checkError()) throw Exception("Write failed")
        val line = r.readLine() ?: throw Exception("EOF")
        return try { JSONObject(line) } catch (e: Exception) { null }
    }

    // ---- Typed helpers ----

    data class ThermalZone(val id: Int, val tempC: Double, val throttled: Boolean)

    data class BatteryInfo(
        val online: Boolean, val capacityPct: Double,
        val currentUa: Int, val drainPctPerHr: Double
    ) {
        val currentMa: Double get() = kotlin.math.abs(currentUa / 1000.0)
        val charging: Boolean get() = online && currentUa >= 0
    }

    data class StatusResponse(
        val activeProfile: String,
        val thermalZones: List<ThermalZone>,
        val battery: BatteryInfo,
        val fpsShort: Int, val fpsLong: Int,
        val fpsAvg: Int, val fpsMin: Int, val fpsMax: Int
    ) {
        val batteryCapacityPct: Double get() = battery.capacityPct
        val batteryCurrentUa: Int get() = battery.currentUa
        val batteryCurrentMa: Double get() = battery.currentMa
        val batteryCharging: Boolean get() = battery.charging
        val batteryOnline: Boolean get() = battery.online
        val batteryDrainPctPerHr: Double get() = battery.drainPctPerHr
        val currentProfileId: Int get() = activeProfile.toIntOrNull() ?: 0
        val foregroundPid: Int get() = 0 // not in new protocol
    }

    fun getStatus(): StatusResponse? {
        val data = sendCommand("status") ?: return null
        return try {
            val t = data.optJSONObject("thermal")
            val bat = data.optJSONObject("battery")
            val fps = data.optJSONObject("fps")
            val session = fps?.optJSONObject("session")

            StatusResponse(
                activeProfile = t?.optString("active_profile", "0") ?: "0",
                thermalZones = parseThermalZones(data.optJSONObject("snapshot")),
                battery = BatteryInfo(
                    online = bat?.optBoolean("online", false) ?: false,
                    capacityPct = bat?.optDouble("capacity_pct", 0.0) ?: 0.0,
                    currentUa = bat?.optInt("current_ua", 0) ?: 0,
                    drainPctPerHr = bat?.optDouble("drain_pct_per_hr", 0.0) ?: 0.0
                ),
                fpsShort = fps?.optInt("short", 0) ?: 0,
                fpsLong = fps?.optInt("long", 0) ?: 0,
                fpsAvg = session?.optInt("avg", 0) ?: 0,
                fpsMin = session?.optInt("min", 0) ?: 0,
                fpsMax = session?.optInt("max", 0) ?: 0
            )
        } catch (e: Exception) { Log.w(TAG, "Parse status: ${e.message}"); null }
    }

    fun setProfile(profileId: Int): Boolean {
        val args = JSONObject().put("id", profileId.toString())
        return sendCommand("apply_profile", args) != null
    }

    fun setAppProfile(pkg: String, profileId: Int): Boolean {
        val args = JSONObject().put("package", pkg).put("profile_id", profileId)
        return sendCommand("map_app", args) != null
    }

    /** Reset all per-app profile mappings in the daemon. */
    fun resetProfiles(): Boolean {
        return sendCommand("reset_profiles") != null
    }

    fun getForegroundApp(): String? {
        val data = sendCommand("detect_fg") ?: return null
        return data.optString("package", null).takeIf { it?.isNotEmpty() == true }
    }

    fun getAppsMap(): Map<String, Int> {
        val data = sendCommand("list_profiles") ?: return emptyMap()
        val mapJson = data.optJSONObject("package_map") ?: return emptyMap()
        val result = HashMap<String, Int>()
        for (key in mapJson.keys()) {
            val v = mapJson.optInt(key, -1)
            if (v >= 0) result[key] = v
        }
        return result
    }

    // ---- FPS / Benchmark ----

    data class FpsResponse(val shortFps: Int, val longFps: Int, val avgFps: Int, val minFps: Int, val maxFps: Int)

    fun getFps(): FpsResponse? {
        val status = getStatus() ?: return null
        return FpsResponse(status.fpsShort, status.fpsLong, status.fpsAvg, status.fpsMin, status.fpsMax)
    }

    fun getFpsAvg(): FpsResponse? {
        val data = sendCommand("fps_avg") ?: return null
        return try {
            FpsResponse(0, 0, data.getInt("avg"), data.getInt("min"), data.getInt("max"))
        } catch (e: Exception) { null }
    }

    fun startBenchmark(): Boolean = sendCommand("bench_start") != null
    fun stopBenchmark(): Boolean = sendCommand("bench_stop") != null

    data class BenchmarkData(val running: Boolean, val elapsedMs: Long, val frames: Long, val fps: FpsResponse?)

    fun getBenchmarkData(): BenchmarkData? {
        val data = sendCommand("bench_data") ?: return null
        return try {
            val arr = data.optJSONArray("data")
            if (arr != null && arr.length() > 0) {
                val last = arr.getJSONObject(arr.length() - 1)
                val fpsArr = last.optJSONArray("fps")
                val fps = if (fpsArr != null && fpsArr.length() >= 3) {
                    FpsResponse(0, 0, fpsArr.getInt(0), fpsArr.getInt(1), fpsArr.getInt(2))
                } else null
                BenchmarkData(
                    running = last.optBoolean("running", false),
                    elapsedMs = last.optLong("elapsed_ms", 0),
                    frames = last.optLong("frames", 0),
                    fps = fps
                )
            } else BenchmarkData(false, 0, 0, null)
        } catch (e: Exception) { Log.w(TAG, "Parse bench_data: ${e.message}"); null }
    }

    fun readSysfs(path: String): String? {
        val args = JSONObject().put("path", path)
        val data = sendCommand("read_sysfs", args) ?: return null
        return data.optString("value", null)
    }

    fun getBatteryStats(): BatteryInfo? {
        val data = sendCommand("battery_stats") ?: return null
        return try {
            BatteryInfo(
                online = data.optBoolean("online", false),
                capacityPct = data.optDouble("capacity_pct", 0.0),
                currentUa = data.optInt("current_ua", 0),
                drainPctPerHr = data.optDouble("drain_pct_per_hr", 0.0)
            )
        } catch (e: Exception) { null }
    }

    fun resetFpsSession(): Boolean = sendCommand("reset_fps_session") != null

    /** Lightweight keep-alive ping — just checks connection health. */
    fun sendStatus(): StatusResponse? = getStatus()

    // ---- Private parsers ----

    private fun parseThermalZones(snapshot: JSONObject?): List<ThermalZone> {
        if (snapshot == null) return emptyList()
        val zonesJson = snapshot.optJSONArray("zones") ?: return emptyList()
        val zones = mutableListOf<ThermalZone>()
        for (i in 0 until zonesJson.length()) {
            val z = zonesJson.optJSONObject(i) ?: continue
            zones.add(ThermalZone(
                id = z.optInt("id", i),
                tempC = z.optDouble("temp_c", 0.0),
                throttled = z.optBoolean("throttled", false)
            ))
        }
        return zones
    }
}
