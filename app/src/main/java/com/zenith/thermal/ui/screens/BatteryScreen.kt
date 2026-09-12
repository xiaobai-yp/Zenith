package com.zenith.thermal.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
// AlertDialog removed — radio group inline
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.thermal.BatteryMonitorService
import com.zenith.thermal.ZenithDaemonClient
import com.zenith.thermal.ui.components.*
import com.zenith.thermal.ui.theme.*
import kotlinx.coroutines.delay

private val PREFS_BATTERY = "zenith_battery"
private const val KEY_RESET_PLUGGED = "reset_on_plugged"
private const val KEY_RESET_TARGET = "reset_on_target"
private const val KEY_RESET_RESTART = "reset_on_restart"
private const val KEY_IDLE_WARN = "idle_warning_enabled"
private const val KEY_IDLE_TARGET = "idle_warning_target"
private const val KEY_RESET_TARGET_VAL = "reset_target"
private const val KEY_TEMP_UNIT = "temperature_unit"

@Composable
fun BatteryScreen() {
    val ctx = LocalContext.current
    val prefs = remember(ctx) { ctx.getSharedPreferences(PREFS_BATTERY, Context.MODE_PRIVATE) }

    var status by remember { mutableStateOf<ZenithDaemonClient.StatusResponse?>(null) }
    var batteryMonitorOn by remember { mutableStateOf(false) }
    var resetPlugged by remember { mutableStateOf(prefs.getBoolean(KEY_RESET_PLUGGED, false)) }
    var resetTarget by remember { mutableStateOf(prefs.getBoolean(KEY_RESET_TARGET, true)) }
    var resetRestart by remember { mutableStateOf(prefs.getBoolean(KEY_RESET_RESTART, false)) }
    var idleWarn by remember { mutableStateOf(prefs.getBoolean(KEY_IDLE_WARN, false)) }
    var idleTarget by remember { mutableStateOf(prefs.getInt(KEY_IDLE_TARGET, 5).coerceIn(1, 100)) }
    var resetTargetVal by remember { mutableStateOf(prefs.getInt(KEY_RESET_TARGET_VAL, 100).coerceIn(1, 100)) }
    var tempUnit by remember { mutableStateOf(prefs.getString(KEY_TEMP_UNIT, "C") ?: "C") }

    LaunchedEffect(Unit) {
        while (true) {
            status = ZenithDaemonClient.getStatus()
            batteryMonitorOn = batteryMonitorServiceRunning(ctx)
            delay(2000)
        }
    }

    Box(Modifier.fillMaxSize().background(ZenithBg)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(44.dp))
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("Zenith Thermal", color = ZenithText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }

                // Battery ring hero
                val cap = status?.battery?.capacityPct?.toInt() ?: 0
                val charging = status?.battery?.online == true
                GradientBorderCard(
                    modifier = Modifier.fillMaxWidth(),
                    radius = 14.dp,
                    gradient = Brush.linearGradient(
                        listOf(
                            ZenithPurple.copy(alpha = 0.4f),
                            ZenithPink.copy(alpha = 0.3f),
                            ZenithPurple.copy(alpha = 0.2f)
                        )
                    )
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        BatteryRing(pct = cap, size = 72.dp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (charging) "Charging · $cap%" else "Discharging · $cap%",
                        color = ZenithText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }

                // Stats row — power / temp / current
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val bat = status?.battery
                    StatPill(
                        label = "Power",
                        value = bat?.powerMw?.let {
                            if (it > 0) "%.1fW".format(it / 1000.0) else "—"
                        } ?: "—",
                        color = ZenithGreen
                    )
                    StatPill(
                        label = "Battery",
                        value = bat?.temperatureCelsius?.let { "${it.toInt()}°C" } ?: "—",
                        color = ZenithText
                    )
                    StatPill(
                        label = "Current",
                        value = bat?.currentMa?.let { "%.0f mA".format(it) } ?: "—",
                        color = ZenithText
                    )
                }

                // Temperature unit — radio group (preview style: row cards)
                SectionLabel("Battery Temperature Unit")
                GradientBorderCard(modifier = Modifier.fillMaxWidth(), innerPadding = 4.dp) {
                    UnitRow("Celsius (°C)", "C", tempUnit) { tempUnit = it; prefs.edit().putString(KEY_TEMP_UNIT, it).apply() }
                    DividerRow()
                    UnitRow("Fahrenheit (°F)", "F", tempUnit) { tempUnit = it; prefs.edit().putString(KEY_TEMP_UNIT, it).apply() }
                    DividerRow()
                    UnitRow("Kelvin (K)", "K", tempUnit) { tempUnit = it; prefs.edit().putString(KEY_TEMP_UNIT, it).apply() }
                }

                // Display — show current/power toggle
                SectionLabel("Display")
                GradientBorderCard(modifier = Modifier.fillMaxWidth(), innerPadding = 4.dp) {
                    // hardcoded true (per legacy), toggling only updates prefs for future
                    ZenithSwitchRow(
                        text = "Show Current and Power (Watt)",
                        checked = true,
                        onChange = { /* legacy hardcodes true; kept for parity */ }
                    )
                }

                // Reset stats
                SectionLabel("Reset Stats")
                GradientBorderCard(modifier = Modifier.fillMaxWidth(), innerPadding = 4.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (resetTarget) "Reset stats when battery ≤" else "Reset stats when battery ≤ OFF",
                            color = ZenithText, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        SelectPill(
                            text = "$resetTargetVal%",
                            selected = resetTarget && resetTargetVal == 100,
                            onClick = { }
                        )
                    }
                    DividerRow()
                    ZenithSwitchRow("Reset stats when plugged in", resetPlugged) {
                        resetPlugged = it; prefs.edit().putBoolean(KEY_RESET_PLUGGED, it).apply()
                    }
                    DividerRow()
                    ZenithSwitchRow("Reset stats on reboot", resetRestart) {
                        resetRestart = it; prefs.edit().putBoolean(KEY_RESET_RESTART, it).apply()
                    }
                }

                // Idle drain warning
                SectionLabel("Idle Drain Warning")
                GradientBorderCard(modifier = Modifier.fillMaxWidth(), innerPadding = 4.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (idleWarn) "Notify if idle drain > " else "Notify if idle drain > OFF",
                            color = ZenithText, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        SelectPill(text = "$idleTarget%", selected = idleWarn, onClick = { })
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Reset button — danger
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ZenithRed.copy(alpha = 0.1f))
                        .then(Modifier.border(1.dp, ZenithRed.copy(alpha = 0.15f), RoundedCornerShape(12.dp)))
                        .clickable {
                            val intent = Intent(ctx, BatteryMonitorService::class.java).apply {
                                action = BatteryMonitorService.ACTION_RESET
                            }
                            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "RESET STATS",
                        color = ZenithRed, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun BatteryRing(pct: Int, size: Dp) {
    val sweep = (pct.coerceIn(0, 100)) * 3.6f
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            drawArc(
                color = Color(0x0FFFFFFF), // 6% white track
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Butt),
                size = Size(size.toPx(), size.toPx()),
                topLeft = Offset.Zero
            )
            drawArc(
                color = ZenithGreen,
                startAngle = -90f, sweepAngle = sweep, useCenter = false,
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Butt),
                size = Size(size.toPx(), size.toPx()),
                topLeft = Offset.Zero
            )
        }
        Text("$pct%", color = ZenithText, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun StatPill(label: String, value: String, color: Color) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x08FFFFFF))
            .then(Modifier.border(1.dp, ZenithBorder2, RoundedCornerShape(10.dp)))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(label.uppercase(), color = ZenithMuted2, fontSize = 7.sp, letterSpacing = 0.6.sp)
    }
}

@Composable
private fun UnitRow(label: String, code: String, current: String, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onSelect(code) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = ZenithText, fontSize = 12.sp, modifier = Modifier.weight(1f))
        ZenithRadio(checked = code == current, onClick = { onSelect(code) })
    }
}

@Composable
private fun ZenithSwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = ZenithText, fontSize = 12.sp, modifier = Modifier.weight(1f))
        ZenithSwitch(checked = checked, onChange = onChange)
    }
}

@Composable
private fun DividerRow() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp)
            .height(0.5.dp)
            .background(Color(0x08FFFFFF))
    )
}

private fun batteryMonitorServiceRunning(ctx: Context): Boolean =
    runCatching {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.getRunningServices(100).any { it.service.className == BatteryMonitorService::class.java.name }
    }.getOrDefault(false)