package com.zenith.thermal.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.thermal.ZenithDaemonClient
import com.zenith.thermal.ui.theme.*
import kotlinx.coroutines.delay

private enum class SubScreen {
    NONE, FPS, THERMAL, BATTERY, SESSIONS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen() {
    var isRecording by remember { mutableStateOf(false) }
    var fps by remember { mutableIntStateOf(0) }
    var fpsLow by remember { mutableIntStateOf(0) }
    var fpsPeak by remember { mutableIntStateOf(0) }
    var cpuLoad by remember { mutableFloatStateOf(0f) }
    var gpuTemp by remember { mutableStateOf("--") }
    var gpuVendor by remember { mutableStateOf("--") }
    var powerW by remember { mutableFloatStateOf(0f) }
    var currentMa by remember { mutableFloatStateOf(0f) }
    var batPct by remember { mutableIntStateOf(0) }
    var drainPctHr by remember { mutableFloatStateOf(0f) }
    var charging by remember { mutableStateOf(false) }
    var thermalCpu by remember { mutableStateOf("--") }
    var thermalGpu by remember { mutableStateOf("--") }
    var thermalBat by remember { mutableStateOf("--") }
    var recordSec by remember { mutableLongStateOf(0L) }
    var benchmarkRunning by remember { mutableStateOf(false) }
    var benchElapsedMs by remember { mutableLongStateOf(0L) }
    var benchFrames by remember { mutableLongStateOf(0L) }
    var activeProfile by remember { mutableStateOf("--") }

    var activeSubScreen by remember { mutableStateOf(SubScreen.NONE) }

    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        while (true) {
            runCatching {
                val s = ZenithDaemonClient.getStatus()
                if (s != null) {
                    fps = s.fpsShort
                    cpuLoad = s.cpuLoadPct
                    gpuTemp = s.gpu?.let { "${it.curFreqMhz} MHz" } ?: "--"
                    gpuVendor = s.gpu?.vendor ?: "--"
                    powerW = s.battery.powerMw / 1000f
                    currentMa = s.batteryCurrentMa.toFloat()
                    batPct = s.batteryCapacityPct.toInt()
                    drainPctHr = s.batteryDrainPctPerHr.toFloat()
                    charging = s.batteryCharging
                    activeProfile = s.activeProfile

                    val zones = s.thermalZones
                    thermalCpu = zones.firstOrNull()?.let { "%.1f°C".format(it.tempC) } ?: "--"
                    thermalGpu = s.gpu?.tempC?.let { "%.1f°C".format(it) } ?: "--"
                    thermalBat = zones.getOrNull(1)?.let { "%.1f°C".format(it.tempC) } ?: "--"
                }
                // Benchmark state
                val bench = ZenithDaemonClient.sendCommand("bench_data")?.let {
                    BenchmarkState(
                        running = it.optBoolean("running", false),
                        elapsedMs = it.optLong("elapsed_ms", 0L),
                        frames = it.optLong("frames", 0L),
                    )
                }
                if (bench != null) {
                    benchmarkRunning = bench.running
                    benchElapsedMs = bench.elapsedMs
                    benchFrames = bench.frames
                }
            }
            if (isRecording) {
                fpsPeak = maxOf(fpsPeak, fps)
                fpsLow = if (fpsLow == 0) fps else minOf(fpsLow, fps)
                recordSec++
            }
            delay(1000)
        }
    }

    Column(Modifier.fillMaxSize().background(ZenithBg)) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (activeSubScreen != SubScreen.NONE) {
                    FilledIconButton(
                        onClick = { activeSubScreen = SubScreen.NONE },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF1E1A2B),
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.size(42.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        when (activeSubScreen) {
                            SubScreen.NONE -> "Record"
                            SubScreen.FPS -> "FPS & Frametimes"
                            SubScreen.THERMAL -> "Thermals"
                            SubScreen.BATTERY -> "Battery"
                            SubScreen.SESSIONS -> "Benchmark History"
                        },
                        color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        when (activeSubScreen) {
                            SubScreen.NONE -> "Live performance & session benchmark"
                            SubScreen.FPS -> "Real-time frame rates and sparkline"
                            SubScreen.THERMAL -> "CPU and GPU temperature sensors"
                            SubScreen.BATTERY -> "Power level, discharge rate, and health"
                            SubScreen.SESSIONS -> "Recorded benchmark sessions"
                        },
                        color = ZenithMuted2, fontSize = TextCaption, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            // Status pill
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isRecording) Color(0xFF2D1A1A) else Color(0xFF1A1A2B),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isRecording) ZenithRed else ZenithPurple),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isRecording) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(ZenithRed))
                    }
                    Text(
                        if (isRecording) formatDur(recordSec) else "Idle",
                        color = if (isRecording) ZenithRed else ZenithPurple,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Animated sub-screen content
        AnimatedContent(
            targetState = activeSubScreen,
            transitionSpec = {
                (slideInHorizontally(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
                    initialOffsetX = { (it * 0.2f).toInt() }
                ) + fadeIn(tween(200))).togetherWith(
                    slideOutHorizontally(
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
                        targetOffsetX = { -(it * 0.2f).toInt() }
                    ) + fadeOut(tween(160))
                )
            },
            label = "SubScreenTransition",
            modifier = Modifier.weight(1f)
        ) { sub ->
            when (sub) {
                SubScreen.NONE -> MainHub(
                    isRecording = isRecording, fps = fps, fpsLow = fpsLow, fpsPeak = fpsPeak,
                    recordSec = recordSec, powerW = powerW, activeProfile = activeProfile,
                    onStartStop = {
                        isRecording = !isRecording
                        if (isRecording) {
                            ZenithDaemonClient.startBenchmark()
                            fpsPeak = 0; fpsLow = fps; recordSec = 0
                        } else {
                            ZenithDaemonClient.stopBenchmark()
                        }
                    },
                    cpuLoad = cpuLoad, gpuTemp = gpuTemp, batPct = batPct, currentMa = currentMa,
                    drainPctHr = drainPctHr, charging = charging,
                    thermalCpu = thermalCpu, thermalGpu = thermalGpu, thermalBat = thermalBat,
                    benchmarkRunning = benchmarkRunning, benchElapsedMs = benchElapsedMs, benchFrames = benchFrames,
                    onOpenFps = { activeSubScreen = SubScreen.FPS },
                    onOpenThermal = { activeSubScreen = SubScreen.THERMAL },
                    onOpenBattery = { activeSubScreen = SubScreen.BATTERY },
                    onOpenSessions = { activeSubScreen = SubScreen.SESSIONS },
                )
                SubScreen.FPS -> FpsDetailPane(fps = fps, fpsLow = fpsLow, fpsPeak = fpsPeak, cpuLoad = cpuLoad, gpuTemp = gpuTemp, gpuVendor = gpuVendor)
                SubScreen.THERMAL -> ThermalDetailPane(thermalCpu = thermalCpu, thermalGpu = thermalGpu, thermalBat = thermalBat, gpuVendor = gpuVendor)
                SubScreen.BATTERY -> BatteryDetailPane(batPct = batPct, currentMa = currentMa, powerW = powerW, drainPctHr = drainPctHr, charging = charging)
                SubScreen.SESSIONS -> SessionsPane(benchmarkRunning = benchmarkRunning, elapsedMs = benchElapsedMs, frames = benchFrames, fps = fps)
            }
        }
    }
}

// ── Main Hub ──
@Composable
private fun MainHub(
    isRecording: Boolean, fps: Int, fpsLow: Int, fpsPeak: Int, recordSec: Long,
    powerW: Float, activeProfile: String, onStartStop: () -> Unit,
    cpuLoad: Float, gpuTemp: String, batPct: Int, currentMa: Float, drainPctHr: Float, charging: Boolean,
    thermalCpu: String, thermalGpu: String, thermalBat: String,
    benchmarkRunning: Boolean, benchElapsedMs: Long, benchFrames: Long,
    onOpenFps: () -> Unit, onOpenThermal: () -> Unit, onOpenBattery: () -> Unit, onOpenSessions: () -> Unit
) {
    Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), color = Color(0xF50C0C18)) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Hero card
            item {
                Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF16131F), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Status badges
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            BadgeChip(
                                if (isRecording) "LIVE" else if (benchmarkRunning) "BENCHMARK" else "STANDBY",
                                if (isRecording) Color(0xFF2D1A1A) else if (benchmarkRunning) Color(0xFF1A2D1A) else Color(0xFF1A1A2B),
                                if (isRecording) ZenithRed else if (benchmarkRunning) ZenithGreen else ZenithMuted2
                            )
                            BadgeChip(activeProfile.uppercase(), Color(0xFF2D2345), ZenithPurple)
                        }
                        // FPS number
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                if (fps > 0) "$fps" else "--",
                                fontSize = 42.sp, fontWeight = FontWeight.Black,
                                color = if (fps > 0) ZenithPurple else ZenithMuted2
                            )
                            Text("FPS", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ZenithMuted2, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        Text(
                            if (isRecording) "1% Low: $fpsLow • Peak: $fpsPeak • ${formatDur(recordSec)}"
                            else "Tap START to begin session recording",
                            color = ZenithMuted2, fontSize = 12.sp
                        )
                        // Start/Stop
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onStartStop() },
                            shape = RoundedCornerShape(999.dp),
                            color = if (isRecording) ZenithRed else ZenithPurple
                        ) {
                            Text(
                                if (isRecording) "● STOP" else "● START",
                                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                        // Stats row
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            HeroStat("$fpsLow", "1% LOW")
                            HeroStat("$fpsPeak", "PEAK")
                            HeroStat(formatDur(recordSec), "TIME")
                            HeroStat("%.1fW".format(powerW), "POWER")
                        }
                    }
                }
            }

            // Live Telemetry section
            item { SectionHeader("LIVE TELEMETRY") }
            item {
                MenuItem(Icons.Outlined.Speed, "FPS & Frametimes",
                    "Avg $fps FPS • 1% Low $fpsLow • Jank ${if (fpsPeak > 0) fpsPeak - fps else 0}",
                    onClick = onOpenFps)
            }
            item {
                MenuItem(Icons.Outlined.DeviceThermostat, "Thermals",
                    "CPU $thermalCpu • GPU $thermalGpu • Battery $thermalBat",
                    onClick = onOpenThermal)
            }
            item {
                MenuItem(Icons.Outlined.BatteryChargingFull, "Battery",
                    "$batPct% • ${currentMa.toInt()} mA • ${if (charging) "Charging" else "Discharging"}",
                    onClick = onOpenBattery)
            }

            // Benchmark section
            item { SectionHeader("BENCHMARK SESSIONS") }
            item {
                MenuItem(Icons.Outlined.Analytics, "Recorded Sessions",
                    if (benchmarkRunning) "Running: ${formatMs(benchElapsedMs)} • $benchFrames frames"
                    else "Tap START above to record a session",
                    onClick = onOpenSessions)
            }
        }
    }
}

// ── FPS Detail Pane ──
@Composable
private fun FpsDetailPane(fps: Int, fpsLow: Int, fpsPeak: Int, cpuLoad: Float, gpuTemp: String, gpuVendor: String) {
    Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), color = Color(0xF50C0C18)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailStatCard("Current FPS", "$fps", "Frames per second (real-time)", ZenithPurple)
            DetailStatCard("1% Low FPS", "$fpsLow", "Worst 1% frame rate — consistency indicator", ZenithRed)
            DetailStatCard("Peak FPS", "$fpsPeak", "Maximum frame rate achieved in session", ZenithGreen)
            DetailStatCard("CPU Load", "%.0f%%".format(cpuLoad), "Total system processing load", ZenithPurple)
            DetailStatCard("GPU", gpuTemp, gpuVendor, ZenithPurple)
        }
    }
}

// ── Thermal Detail Pane ──
@Composable
private fun ThermalDetailPane(thermalCpu: String, thermalGpu: String, thermalBat: String, gpuVendor: String) {
    Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), color = Color(0xF50C0C18)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailStatCard("CPU Temperature", thermalCpu, "ARM core cluster junction", ZenithPurple)
            DetailStatCard("GPU Temperature", thermalGpu, gpuVendor, ZenithPurple)
            DetailStatCard("Battery Temperature", thermalBat, "Lithium polymer pack", ZenithGreen)
        }
    }
}

// ── Battery Detail Pane ──
@Composable
private fun BatteryDetailPane(batPct: Int, currentMa: Float, powerW: Float, drainPctHr: Float, charging: Boolean) {
    Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), color = Color(0xF50C0C18)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailStatCard("Capacity", "$batPct%", "Current charge level", ZenithGreen)
            DetailStatCard("Current", "%.0f mA".format(currentMa), if (charging) "Charging ⚡" else "Discharging", ZenithPurple)
            DetailStatCard("Power", "%.2fW".format(powerW), "Instantaneous power draw", ZenithPurple)
            DetailStatCard("Drain Rate", "%.2f%%/hr".format(drainPctHr), "Estimated battery life remaining", ZenithRed)
        }
    }
}

// ── Sessions Pane ──
@Composable
private fun SessionsPane(benchmarkRunning: Boolean, elapsedMs: Long, frames: Long, fps: Int) {
    Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), color = Color(0xF50C0C18)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (benchmarkRunning) {
                DetailStatCard("Session Duration", formatMs(elapsedMs), "Recording active", ZenithGreen)
                DetailStatCard("Total Frames", "$frames", "Frames rendered in session", ZenithPurple)
                DetailStatCard("Current FPS", "$fps", "Real-time frame rate", ZenithPurple)
            } else {
                Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF16131F), modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp)) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Analytics, null, tint = ZenithMuted2, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No sessions yet", color = ZenithMuted2, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("Tap START on the Record screen to begin", color = ZenithMuted2, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ── Shared components ──
@Composable
private fun BadgeChip(text: String, bg: Color, fg: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun HeroStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label.uppercase(), color = ZenithMuted2, fontSize = 9.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, color = ZenithPurple, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun MenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF0D0D18), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(38.dp), shape = RoundedCornerShape(12.dp), color = Color(0xFF2D2345)) {
                Icon(icon, null, tint = ZenithPurple, modifier = Modifier.padding(10.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = ZenithMuted2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("›", color = Color(0xFF3A3A50), fontSize = 18.sp)
        }
    }
}

@Composable
private fun DetailStatCard(title: String, value: String, subtitle: String, color: Color) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF16131F), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = ZenithMuted2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = ZenithMuted2, fontSize = 11.sp)
            }
            Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Black)
        }
    }
}

private fun formatDur(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)
private fun formatMs(ms: Long): String {
    val s = ms / 1000; val m = s / 60; val h = m / 60
    return if (h > 0) "%dh %dm %ds".format(h, m % 60, s % 60)
    else if (m > 0) "%dm %ds".format(m, s % 60)
    else "%ds".format(s)
}

private data class BenchmarkState(val running: Boolean, val elapsedMs: Long, val frames: Long)
