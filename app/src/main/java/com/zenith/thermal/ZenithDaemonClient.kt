package com.zenith.thermal

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Singleton client for IPC with zenithd via newline-delimited JSON over
 * Process stdin/stdout pipes.
 *
 * Wire format:
 *   Request:  {"cmd":"...","args":{...}}\n
 *   Response: {"ok":true,"data":{...}}\n  or  {"ok":false,"error":"..."}\n
 *
 * DaemonManager calls [attachProcess] when the child process starts.
 * All blocking I/O runs on a dedicated daemon thread.
 */
object ZenithDaemonClient {

    private const val TAG = "ZDaemonClient"
    private const val IO_TIMEOUT_MS = 5_000

    private val ioLock = Any()
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
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

    // Dedicated I/O thread — processes one request at a time.
    private val ioThread = object : Thread("zenith-daemon-io") {
        init { isDaemon = true }
        override fun run() {
            while (!isInterrupted) {
                try {
                    val w = workQueue.take()
                    val result = synchronized(ioLock) {
                        if (reader == null || writer == null) {
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
                } catch (_: InterruptedException) { return }
            }
        }
    }.apply { start() }

    // ---- Connection management ----

    /**
     * Attach streams from a launched daemon process. Called by DaemonManager
     * right after launching zenithd via su.
     */
    fun attachProcess(inputStream: InputStream, outputStream: OutputStream) = synchronized(ioLock) {
        disconnectLocked()
        reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8), 4096)
        writer = PrintWriter(outputStream, true)
        isConnected = true
        notifyListeners(true)
        Log.i(TAG, "Attached to daemon process streams")
    }

    /** Non-blocking: enqueue a status check through existing streams. */
    fun requestConnect() { workQueue.offer(work("status")) }

    fun disconnect() { synchronized(ioLock) { disconnectLocked() } }

    private fun disconnectLocked() {
        val was = isConnected
        try { reader?.close() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        reader = null; writer = null
        isConnected = false
        if (was) notifyListeners(false)
    }

    /**
     * Called by DaemonManager when the child process exits unexpectedly.
     * Closes streams and notifies listeners without killing anything.
     */
    fun onProcessDied() {
        Log.w(TAG, "Daemon process died")
        synchronized(ioLock) { disconnectLocked() }
    }

    // ---- Request / Response ----

    private fun work(cmd: String, args: JSONObject? = null): Work {
        val req = JSONObject()
        req.put("cmd", cmd)
        if (args != null) req.put("args", args)
        return Work(req)
    }

    fun sendCommand(cmd: String, args: JSONObject? = null): JSONObject? {
        val w = work(cmd, args)
        workQueue.offer(w)
        val ok = w.latch.await(IO_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        if (!ok) { Log.w(TAG, "Command '$cmd' timed out"); return null }
        if (w.failed.get()) return null
        @Suppress("UNCHECKED_CAST")
        return w.result[0] as? JSONObject
    }

    fun sendRequest(cmd: String, args: JSONObject? = null): String? {
        val data = sendCommand(cmd, args) ?: return null
        return if (data.has("error")) data.getString("error") else data.toString()
    }

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
        val currentUa: Int, val drainPctPerHr: Double,
        val powerMw: Long = 0
    ) {
        val currentMa: Double get() = kotlin.math.abs(currentUa / 1000.0)
        val charging: Boolean get() = online && currentUa >= 0
    }

    data class GpuInfo(
        val curFreqMhz: Int, val maxFreqMhz: Int, val busyPct: Int,
        val vendor: String = "",
        val tempC: Double? = null
    )

    data class CpuInfo(val governor: String, val curFreqKhz: Int, val maxFreqKhz: Int) {
        val curFreqMhz: Int get() = curFreqKhz / 1000
        val maxFreqMhz: Int get() = maxFreqKhz / 1000
    }

    data class StatusResponse(
        val activeProfile: String,
        val thermalZones: List<ThermalZone>,
        val battery: BatteryInfo,
        val fpsShort: Int, val fpsLong: Int,
        val fpsAvg: Int, val fpsMin: Int, val fpsMax: Int,
        val maxFrameTimeMs: Int,
        val cpuLoadPct: Float,
        val gpu: GpuInfo?,
        val cpuPolicy0: CpuInfo?, val cpuPolicy4: CpuInfo?, val cpuPolicy7: CpuInfo?
    ) {
        val batteryCapacityPct: Double get() = battery.capacityPct
        val batteryCurrentUa: Int get() = battery.currentUa
        val batteryCurrentMa: Double get() = battery.currentMa
        val batteryCharging: Boolean get() = battery.charging
        val batteryOnline: Boolean get() = battery.online
        val batteryDrainPctPerHr: Double get() = battery.drainPctPerHr
        val currentProfileId: Int get() = activeProfile.toIntOrNull() ?: 0
        val foregroundPid: Int get() = 0
    }

    fun getStatus(): StatusResponse? {
        val data = sendCommand("status") ?: return null
        return try {
            val t = data.optJSONObject("thermal")
            val bat = data.optJSONObject("battery")
            val fps = data.optJSONObject("fps")
            val session = fps?.optJSONObject("session")
            val snap = data.optJSONObject("snapshot")
            val gpuJson = snap?.optJSONObject("gpu")

            StatusResponse(
                activeProfile = t?.optString("active_profile", "0") ?: "0",
                thermalZones = parseThermalZones(snap),
                battery = BatteryInfo(
                    online = bat?.optBoolean("online", false) ?: false,
                    capacityPct = bat?.optDouble("capacity_pct", 0.0) ?: 0.0,
                    currentUa = bat?.optInt("current_ua", 0) ?: 0,
                    drainPctPerHr = bat?.optDouble("active_drain_pct_per_hr", 0.0) ?: 0.0,
                    powerMw = bat?.optLong("power_mw", 0) ?: 0
                ),
                fpsShort = fps?.optInt("short", 0) ?: 0,
                fpsLong = fps?.optInt("long", 0) ?: 0,
                fpsAvg = session?.optInt("avg", 0) ?: 0,
                fpsMin = session?.optInt("min", 0) ?: 0,
                fpsMax = session?.optInt("max", 0) ?: 0,
                maxFrameTimeMs = fps?.optInt("max_frame_time_ms", 0) ?: 0,
                cpuLoadPct = snap?.optDouble("cpu_load_pct", 0.0)?.toFloat() ?: 0f,
                gpu = gpuJson?.let { GpuInfo(
                    curFreqMhz = (it.optLong("cur_freq", 0) / 1_000_000).toInt(),
                    maxFreqMhz = (it.optLong("max_freq", 0) / 1_000_000).toInt(),
                    busyPct = it.optInt("busy_pct", 0),
                    vendor = it.optString("vendor", ""),
                    tempC = if (it.has("temp_c")) it.optDouble("temp_c") else null
                ) },
                cpuPolicy0 = parseCpuPolicy(snap, "cpu_policy0"),
                cpuPolicy4 = parseCpuPolicy(snap, "cpu_policy4"),
                cpuPolicy7 = parseCpuPolicy(snap, "cpu_policy7"),
            )
        } catch (e: Exception) { Log.w(TAG, "Parse status: ${e.message}"); null }
    }

    private fun parseCpuPolicy(snap: JSONObject?, key: String): CpuInfo? {
        val json = snap?.optJSONObject(key) ?: return null
        val gov = json.optString("governor", "")
        val cur = json.optInt("cur_freq", 0)
        val max = json.optInt("max_freq", 0)
        return if (gov.isNotEmpty() || cur > 0) CpuInfo(gov, cur, max) else null
    }

    /** Global profile: apply + pin (foreground detection won't override). */
    fun setGlobalProfile(profileId: Int): Boolean {
        val args = JSONObject().put("id", profileId.toString())
        return sendCommand("setglobalprofile", args) != null
    }

    /** Per-app profile: apply without pinning. */
    fun setAppProfile(profileId: Int): Boolean {
        val args = JSONObject().put("id", profileId.toString())
        return sendCommand("setappprofile", args) != null
    }

    fun setAppProfile(pkg: String, profileId: Int): Boolean {
        val args = JSONObject().put("package", pkg).put("profile_id", profileId)
        return sendCommand("map_app", args) != null
    }

    /** Sync all local per-app mappings (SharedPreferences) to the daemon. */
    fun syncMappings(context: Context) {
        val map = AppProfileCache.all(context)
        if (map.isEmpty()) return
        for ((pkg, id) in map) {
            if (id > 0) {
                try {
                    setAppProfile(pkg, id)
                    Log.i(TAG, "Synced mapping: $pkg → $id")
                } catch (e: Exception) {
                    Log.w(TAG, "Sync failed for $pkg: ${e.message}")
                }
            }
        }
    }

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
            val running = data.optBoolean("running", false)
            val elapsedMs = data.optLong("elapsed_ms", 0)
            val frames = data.optLong("frames", 0)
            val arr = data.optJSONArray("data")
            if (arr != null && arr.length() > 0) {
                val last = arr.getJSONObject(arr.length() - 1)
                val fps = FpsResponse(
                    0,
                    last.optInt("fps_short", 0),
                    last.optInt("fps_avg", 0),
                    last.optInt("fps", 0),
                    last.optInt("fps", 0)
                )
                BenchmarkData(running, elapsedMs, frames, fps)
            } else BenchmarkData(running, elapsedMs, frames, null)
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
                drainPctPerHr = data.optDouble("active_drain_pct_per_hr", 0.0)
            )
        } catch (e: Exception) { null }
    }

    fun resetFpsSession(): Boolean = sendCommand("reset_fps_session") != null

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
