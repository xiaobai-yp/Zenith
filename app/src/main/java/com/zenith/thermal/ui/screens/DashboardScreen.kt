package com.zenith.thermal.ui.screens

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.thermal.AppProfileCache
import com.zenith.thermal.Profile
import com.zenith.thermal.ZenithDaemonClient
import com.zenith.thermal.ui.components.*
import com.zenith.thermal.ui.theme.*
import kotlinx.coroutines.delay

// ── Profile badge colors (shared with ThermalScreen) ──
private data class PBadge(val bg: Color, val fg: Color)
private fun pBadge(id: Int): PBadge = when (id) {
    9, 10, 11, 13, 14 -> PBadge(ZenithPurple.copy(alpha = 0.12f), ZenithPurple)
    7, 12             -> PBadge(ZenithPink.copy(alpha = 0.12f), ZenithPink)
    1                 -> PBadge(ZenithGreen.copy(alpha = 0.12f), ZenithGreen)
    4, 15             -> PBadge(ZenithAmber.copy(alpha = 0.12f), ZenithAmber)
    8                 -> PBadge(ZenithRed.copy(alpha = 0.12f), ZenithRed)
    else              -> PBadge(Color(0x0DFFFFFF), ZenithMuted)
}

@Composable
fun DashboardScreen() {
    val ctx = LocalContext.current

    // ── Live state ──
    var cpuLoad by remember { mutableFloatStateOf(0f) }
    var gpuTempC by remember { mutableFloatStateOf(0f) }
    var currentFps by remember { mutableIntStateOf(0) }
    var powerMw by remember { mutableLongStateOf(0L) }
    var profileName by remember { mutableStateOf("Default") }
    var batTempC by remember { mutableFloatStateOf(0f) }
    var batPct by remember { mutableIntStateOf(0) }
    var uptimeMs by remember { mutableLongStateOf(0L) }
    var isDaemonConnected by remember { mutableStateOf(ZenithDaemonClient.isConnected) }

    // Per-app top 5
    var topApps by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            isDaemonConnected = ZenithDaemonClient.isConnected

            // Daemon telemetry
            runCatching {
                ZenithDaemonClient.getStatus()?.let { s ->
                    cpuLoad = s.cpuLoadPct
                    gpuTempC = s.gpu?.tempC?.toFloat() ?: 0f
                    currentFps = s.fpsShort
                    powerMw = s.battery.powerMw
                    profileName = Profile.nameForId(s.currentProfileId)
                }
            }

            // Battery via BatteryManager (same pattern as BatteryScreen)
            runCatching {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                batPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            }
            runCatching {
                val intent = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                batTempC = (intent?.getIntExtra("temperature", 0) ?: 0) / 10f
            }

            uptimeMs = SystemClock.elapsedRealtime()

            // Top 5 per-app profiles
            runCatching {
                val map = ZenithDaemonClient.getAppsMap()
                val local = AppProfileCache.all(ctx)
                val merged = mutableMapOf<String, Int>()
                map.forEach { (k, v) -> merged[k] = v }
                local.forEach { (k, v) -> if (v > 0 && !merged.containsKey(k)) merged[k] = v }
                topApps = merged.entries
                    .filter { it.value > 0 }
                    .take(5)
                    .map { it.key to it.value }
            }

            delay(2000)
        }
    }

    // ── Layout: Auriya-style pinned header + scrollable sheet ──
    Column(modifier = Modifier.fillMaxSize().background(ZenithBg)) {
        // Pinned header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Space.lg, end = Space.lg, top = Space.md, bottom = Space.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GradientTitle("Zenith")
            // Status pill
            Badge(
                text = if (isDaemonConnected) "Running" else "Stopped",
                bg = if (isDaemonConnected) ZenithGreen.copy(alpha = 0.12f) else ZenithRed.copy(alpha = 0.12f),
                fg = if (isDaemonConnected) ZenithGreen else ZenithRed
            )
        }

        Spacer(Modifier.height(14.dp))

        // Foreground sheet
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(topStart = Radius.xxl, topEnd = Radius.xxl),
            color = Color(0xF50C0C18)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.lg)
                    .padding(top = Space.xl, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(Space.md)
            ) {
                // ── Hero card ──
                GradientBorderCard(
                    modifier = Modifier.fillMaxWidth(),
                    gradient = Brush.linearGradient(listOf(ZenithPurple.copy(0.8f), ZenithPink.copy(0.7f))),
                    innerPadding = Space.lg
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Profile: $profileName", color = Color.White, fontSize = TextHeading, fontWeight = FontWeight.ExtraBold)
                            Spacer(Modifier.height(Space.xs))
                            Text(
                                "Battery: ${batTempC}°C  ·  ${batPct}%",
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = TextBodySm
                            )
                        }
                        // Uptime pill
                        Text(
                            formatUptime(uptimeMs),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = TextCaption,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // ── Telemetry grid 2×2 ──
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    TelemetryCard("CPU Load", cpuLoad, 100f, ZenithPurple, "%", Modifier.weight(1f))
                    TelemetryCard("GPU Temp", gpuTempC, 100f, ZenithPink, "°C", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    TelemetryCard("FPS", currentFps.toFloat(), 120f, ZenithGreen, "", Modifier.weight(1f))
                    TelemetryCard("Power", powerMw / 1000f, 10f, ZenithAmber, "W", Modifier.weight(1f))
                }

                // ── Global profile selector ──
                SectionLabel("Global Profile")
                GradientBorderCard(
                    modifier = Modifier.fillMaxWidth(),
                    radius = Radius.xl,
                    innerPadding = Space.xs,
                    gradient = null
                ) {
                    val selectedIdx = Profile.indexOf(ZenithDaemonClient.getStatus()?.currentProfileId ?: 0)
                    for (i in 0 until Profile.count()) {
                        val pid = Profile.value(i)
                        val selected = selectedIdx == i
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    com.zenith.thermal.ThermalController.applyGlobal(ctx, pid)
                                }
                                .padding(horizontal = Space.lg, vertical = Space.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                Profile.MENU_NAMES[i],
                                color = if (selected) ZenithPurple else ZenithText,
                                fontSize = TextBodySm,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            ZenithRadio(checked = selected, onClick = {
                                com.zenith.thermal.ThermalController.applyGlobal(ctx, pid)
                            })
                        }
                    }
                }

                // ── Per-app top 5 ──
                if (topApps.isNotEmpty()) {
                    SectionLabel("Per-App", count = "· ${topApps.size}")
                    GradientBorderCard(
                        modifier = Modifier.fillMaxWidth(),
                        radius = Radius.xl,
                        innerPadding = Space.xs,
                        gradient = null
                    ) {
                        for ((pkg, pid) in topApps) {
                            val b = pBadge(pid)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Space.lg, vertical = Space.md),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        pkg.substringAfterLast('.'),
                                        color = ZenithText,
                                        fontSize = TextBodySm,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(pkg, color = ZenithMuted2, fontSize = TextMicro, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.width(Space.sm))
                                Badge(text = Profile.name(pid).uppercase(), bg = b.bg, fg = b.fg)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(Space.lg))
            }
        }
    }
}

// ── Telemetry mini-card with bar ──
@Composable
private fun TelemetryCard(
    label: String,
    value: Float,
    max: Float,
    accent: Color,
    unit: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.xl),
        color = Color(0x0DFFFFFF),
        border = androidx.compose.foundation.BorderStroke(1.dp, ZenithBorder2)
    ) {
        Column(Modifier.padding(Space.md)) {
            Text(label.uppercase(), color = ZenithMuted2, fontSize = TextMicro, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
            Spacer(Modifier.height(Space.xs))
            Text(
                "${if (unit == "W") "%.1f".format(value) else value.toInt()}$unit",
                color = accent,
                fontSize = TextHeading,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(Space.sm))
            LinearProgressIndicator(
                progress = { (value / max).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(Radius.sm)),
                color = accent,
                trackColor = accent.copy(alpha = 0.12f),
                strokeCap = StrokeCap.Round
            )
        }
    }
}

// ── Gradient text title (solid accent — perf) ──
@Composable
private fun GradientTitle(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 26.sp,
        fontWeight = FontWeight.ExtraBold,
    )
}

// ── Uptime formatter ──
private fun formatUptime(ms: Long): String {
    val totalSec = ms / 1000
    val d = totalSec / 86400
    val h = (totalSec % 86400) / 3600
    val m = (totalSec % 3600) / 60
    return if (d > 0) "${d}d ${h}h" else if (h > 0) "${h}h ${m}m" else "${m}m"
}
