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
    val jankList = mutableListOf<Float>()
    val bigJankList = mutableListOf<Float>()
    val maxFrameTimeList = mutableListOf<Float>()
    val cpu0List = mutableListOf<Float>()
    val cpu1List = mutableListOf<Float>()
    val cpu2List = mutableListOf<Float>()
    val cpu3List = mutableListOf<Float>()
    val cpu4List = mutableListOf<Float>()
    val cpu5List = mutableListOf<Float>()
    val cpu6List = mutableListOf<Float>()
    val cpu7bList = mutableListOf<Float>()
    val cpuFreq0List = mutableListOf<Float>()
    val cpuFreq1List = mutableListOf<Float>()
    val cpuFreq2List = mutableListOf<Float>()
    val cpuFreq3List = mutableListOf<Float>()
    val cpuFreq4List = mutableListOf<Float>()
    val cpuFreq5List = mutableListOf<Float>()
    val cpuFreq6List = mutableListOf<Float>()
    val cpuFreq7List = mutableListOf<Float>()
    val cpuCyc0List = mutableListOf<Float>()
    val cpuCyc1List = mutableListOf<Float>()
    val cpuCyc2List = mutableListOf<Float>()
    val cpuCyc3List = mutableListOf<Float>()
    val cpuCyc4List = mutableListOf<Float>()
    val cpuCyc5List = mutableListOf<Float>()
    val cpuCyc6List = mutableListOf<Float>()
    val cpuCyc7List = mutableListOf<Float>()
    val voltageList = mutableListOf<Float>()
    val currentList = mutableListOf<Float>()
    val threadPercentList = mutableListOf<Float>()

    var fpsSum = 0f; var fpsMax = 0f; var fpsMin = 999f
    var peakTemp = 0f; var powerSum = 0f

    for (i in 0 until n) {
        val pt = points.optJSONObject(i) ?: continue
        val fps = pt.optInt("fps", 0).toFloat()
        val tempMilli = pt.optLong("temp_milli", 0)
        val battPct = pt.optInt("batt_pct", 0)
        val currentMa = pt.optLong("current_ma", 0)
        val powerMw = pt.optLong("power_mw", 0)
        val voltageUv = pt.optLong("voltage_mv", 0) * 1000L  // mV → uV
        val battTemp = pt.optDouble("batt_temp", 0.0).toFloat()
        val cpuLoad = pt.optDouble("cpu_load", 0.0).toFloat()
        val cpu0Freq = pt.optInt("cpu0_freq", 0) / 1000f  // kHz → MHz
        val cpu1Freq = pt.optInt("cpu1_freq", 0) / 1000f
        val cpu2Freq = pt.optInt("cpu2_freq", 0) / 1000f
        val cpu3Freq = pt.optInt("cpu3_freq", 0) / 1000f
        val cpu4Freq = pt.optInt("cpu4_freq", 0) / 1000f
        val cpu5Freq = pt.optInt("cpu5_freq", 0) / 1000f
        val cpu6Freq = pt.optInt("cpu6_freq", 0) / 1000f
        val cpu7Freq = pt.optInt("cpu7_freq", 0) / 1000f
        val gpuFreq = pt.optLong("gpu_freq", 0) / 1000000f  // Hz → MHz
        val gpuBusy = pt.optInt("gpu_busy", 0)
        val gpuTemp = pt.optDouble("gpu_temp", 0.0).toFloat()
        val ddrFreq = pt.optInt("ddr_freq", 0) / 1000f  // kHz → MHz
        val jank = pt.optInt("jank", 0).toFloat()
        val bigJank = pt.optInt("big_jank", 0).toFloat()
        val maxFrameTimeMs = pt.optInt("max_frame_time_ms", 0).toFloat()
        val cpu0Cycles = pt.optLong("cpu0_cycles", 0).toFloat()
        val cpu1Cycles = pt.optLong("cpu1_cycles", 0).toFloat()
        val cpu2Cycles = pt.optLong("cpu2_cycles", 0).toFloat()
        val cpu3Cycles = pt.optLong("cpu3_cycles", 0).toFloat()
        val cpu4Cycles = pt.optLong("cpu4_cycles", 0).toFloat()
        val cpu5Cycles = pt.optLong("cpu5_cycles", 0).toFloat()
        val cpu6Cycles = pt.optLong("cpu6_cycles", 0).toFloat()
        val cpu7Cycles = pt.optLong("cpu7_cycles", 0).toFloat()

        fpsList.add(fps)
        val tempC = (tempMilli / 1000f)
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
        jankList.add(jank)
        bigJankList.add(bigJank)
        maxFrameTimeList.add(maxFrameTimeMs)
        // Per-core CPU load (same as total for now, individual needs separate capture)
        cpu0List.add(cpuLoad)
        cpu1List.add(cpuLoad)
        cpu2List.add(cpuLoad)
        cpu3List.add(cpuLoad)
        cpu4List.add(cpuLoad)
        cpu5List.add(cpuLoad)
        cpu6List.add(cpuLoad)
        cpu7bList.add(cpuLoad)
        // Per-core freq
        cpuFreq0List.add(cpu0Freq)
        cpuFreq1List.add(cpu1Freq)
        cpuFreq2List.add(cpu2Freq)
        cpuFreq3List.add(cpu3Freq)
        cpuFreq4List.add(cpu4Freq)
        cpuFreq5List.add(cpu5Freq)
        cpuFreq6List.add(cpu6Freq)
        cpuFreq7List.add(cpu7Freq)
        // Per-core cycles
        cpuCyc0List.add(cpu0Cycles)
        cpuCyc1List.add(cpu1Cycles)
        cpuCyc2List.add(cpu2Cycles)
        cpuCyc3List.add(cpu3Cycles)
        cpuCyc4List.add(cpu4Cycles)
        cpuCyc5List.add(cpu5Cycles)
        cpuCyc6List.add(cpu6Cycles)
        cpuCyc7List.add(cpu7Cycles)
        // Battery
        voltageList.add(voltageUv / 1_000_000f)  // uV → V
        currentList.add(currentMa / 1000f)  // mA → A
        // Thread usage - placeholder
        threadPercentList.add(0f)

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

    // Calculate stats from fpsList
    val smoothPct = if (n > 0) fpsList.count { it >= 45f }.toFloat() / n * 100f else 0f
    val sortedFps = fpsList.sorted()
    val low5Pct = if (n > 0) sortedFps[(n * 0.05).toInt().coerceIn(0, n - 1)] else 0f
    val variance = if (n > 1) fpsList.map { (it - avgFps) * (it - avgFps) }.average().toFloat() else 0f

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
            variance = variance,
            smoothPct = smoothPct,
            low5Pct = low5Pct,
            peakTemp = peakTemp,
            avgPowerW = avgPower,
            durationSec = durationSec,
            chartData = ChartData(
                fps = fpsList, temp = tempList,
                cpu03 = cpuLoadList, cpu46 = cpu4FreqList, cpu7 = cpu7FreqList,
                cpu0 = cpu0List, cpu1 = cpu1List, cpu2 = cpu2List, cpu3 = cpu3List,
                cpu4 = cpu4List, cpu5 = cpu5List, cpu6 = cpu6List, cpu7b = cpu7bList,
                gpuFreq = gpuFreqList, gpuUsage = gpuUsageList,
                ddr = ddrFreqList, powerW = powerList, capacity = capList,
                frameTime = frameTimeList,
                jank = jankList,
                bigJank = bigJankList,
                maxFrameTime = maxFrameTimeList,
                cpuFreq0 = cpuFreq0List, cpuFreq1 = cpuFreq1List, cpuFreq2 = cpuFreq2List, cpuFreq3 = cpuFreq3List,
                cpuFreq4 = cpuFreq4List, cpuFreq5 = cpuFreq5List, cpuFreq6 = cpuFreq6List, cpuFreq7 = cpuFreq7List,
                cpuCyc0 = cpuCyc0List, cpuCyc1 = cpuCyc1List, cpuCyc2 = cpuCyc2List, cpuCyc3 = cpuCyc3List,
                cpuCyc4 = cpuCyc4List, cpuCyc5 = cpuCyc5List, cpuCyc6 = cpuCyc6List, cpuCyc7 = cpuCyc7List,
                voltage = voltageList, current = currentList, battTemp = battTempList,
                threadPercent = threadPercentList,
            )
        )
    }
