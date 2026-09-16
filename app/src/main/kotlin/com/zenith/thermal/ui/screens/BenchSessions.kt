// bench_sessions.kt — Load sessions from daemon IPC (primary) + CSV files (fallback)
package com.zenith.thermal.ui.screens

import android.content.Context
import com.zenith.thermal.ZenithDaemonClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Query daemon for persisted sessions. Returns empty if daemon not connected.
 */
internal fun loadDaemonSessions(): List<SessionEntry> {
    if (!ZenithDaemonClient.isConnected) return emptyList()
    return try {
        val res = ZenithDaemonClient.sendCommand("list_sessions") ?: return emptyList()
        val arr = res.optJSONArray("sessions") ?: return emptyList()
        val sessions = mutableListOf<SessionEntry>()
        for (i in 0 until arr.length()) {
            val meta = arr.optJSONObject(i) ?: continue
            val id = meta.optString("id", "")
            if (id.isEmpty()) continue
            val full = ZenithDaemonClient.sendCommand("get_session", JSONObject().put("id", id))
                ?: continue
            val points = full.optJSONArray("points")
            if (points == null || points.length() == 0) continue
            val entry = daemonSessionToEntry(meta, points)
            if (entry != null) sessions.add(entry)
        }
        sessions.sortedByDescending { it.date }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun daemonSessionToEntry(meta: JSONObject, points: JSONArray): SessionEntry? {
    val pkg = meta.optString("package", "")
    val startedAt = meta.optLong("started_at", 0)
    val durationMs = meta.optLong("duration_ms", 0)
    val n = points.length()
    if (n == 0) return null

    // Extract time series
    val fpsList = mutableListOf<Float>()
    val tempList = mutableListOf<Float>()
    val powerList = mutableListOf<Float>()
    val capList = mutableListOf<Float>()
    val frameTimeList = mutableListOf<Float>()
    val cpuLoadList = mutableListOf<Float>()
    val cpu0FreqList = mutableListOf<Float>()
    val cpu4FreqList = mutableListOf<Float>()
    val cpu7FreqList = mutableListOf<Float>()
    val gpuFreqList = mutableListOf<Float>()
    val gpuUsageList = mutableListOf<Float>()
    val gpuTempList = mutableListOf<Float>()
    val battTempList = mutableListOf<Float>()
    val ddrFreqList = mutableListOf<Float>()

    var fpsSum = 0f; var fpsMax = 0f; var fpsMin = 999f
    var peakTemp = 0f; var powerSum = 0f

    for (i in 0 until n) {
        val pt = points.optJSONObject(i) ?: continue
        val fps = pt.optInt("fps", 0).toFloat()
        val tempMilli = pt.optLong("temp_milli", 0)
        val battPct = pt.optInt("batt_pct", 0)
        val currentMa = pt.optLong("current_ma", 0)
        val powerMw = pt.optLong("power_mw", 0)
        val battTemp = pt.optDouble("batt_temp", 0.0).toFloat()
        val cpuLoad = pt.optDouble("cpu_load", 0.0).toFloat()
        val cpu0Freq = pt.optInt("cpu0_freq", 0) / 1000f  // kHz → MHz
        val cpu4Freq = pt.optInt("cpu4_freq", 0) / 1000f
        val cpu7Freq = pt.optInt("cpu7_freq", 0) / 1000f
        val gpuFreq = pt.optLong("gpu_freq", 0) / 1000000f  // Hz → MHz
        val gpuBusy = pt.optInt("gpu_busy", 0)
        val gpuTemp = pt.optDouble("gpu_temp", 0.0).toFloat()
        val ddrFreq = pt.optInt("ddr_freq", 0) / 1000f  // kHz → MHz

        fpsList.add(fps)
        val tempC = (tempMilli / 10f)
        tempList.add(tempC)
        powerList.add(powerMw / 1000f)  // mW → W
        capList.add(battPct.toFloat())
        val ft = if (fps > 0) 1000f / fps else 0f
        frameTimeList.add(ft)
        cpuLoadList.add(cpuLoad)
        cpu0FreqList.add(cpu0Freq)
        cpu4FreqList.add(cpu4Freq)
        cpu7FreqList.add(cpu7Freq)
        gpuFreqList.add(gpuFreq)
        gpuUsageList.add(gpuBusy.toFloat())
        gpuTempList.add(gpuTemp)
        battTempList.add(battTemp)
        ddrFreqList.add(ddrFreq)

        fpsSum += fps
        if (fps > fpsMax) fpsMax = fps
        if (fps < fpsMin) fpsMin = fps
        if (tempC > peakTemp) peakTemp = tempC
        powerSum += (powerMw / 1000f)
    }

    val avgFps = if (n > 0) fpsSum / n else 0f
    val avgPower = if (n > 0) powerSum / n else 0f
    val durationSec = durationMs / 1000

    // CPU freq chart: map each cluster's MHz to the 3 chart lines
    // cpu03 = cpu0 (little), cpu46 = cpu4 (mid), cpu7 = cpu7 (big)
    // powerW chart
    // capacity chart

    val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date(startedAt))

    return SessionEntry(
        id = startedAt,
        appName = pkg.substringAfterLast('.'),
        appPkg = pkg,
        date = dateStr,
        version = "",
        crop = "",
        avgFps = avgFps,
        maxFps = fpsMax,
        minFps = if (fpsMin == 999f) 0f else fpsMin,
        variance = 0f,
        smoothPct = 0f,
        low5Pct = 0f,
        peakTemp = peakTemp,
        avgPowerW = avgPower,
        durationSec = durationSec,
        chartData = ChartData(
            fps = fpsList,
            temp = tempList,
            powerW = powerList,
            capacity = capList,
            frameTime = frameTimeList,
            cpu03 = cpuLoadList,        // reuse for CPU load % chart
            cpuFreq03 = cpu0FreqList,   // CPU freq MHz (little)
            cpuFreq46 = cpu4FreqList,   // CPU freq MHz (mid)
            cpuFreq7 = cpu7FreqList,    // CPU freq MHz (big)
            gpuFreq = gpuFreqList,      // GPU freq MHz
            gpuUsage = gpuUsageList,    // GPU busy %
            ddr = ddrFreqList,          // DDR freq MHz
            cpu46 = gpuTempList,        // reuse for GPU temp (if needed)
            cpu7 = battTempList,        // reuse for battery temp (if needed)
        )
    )
}
