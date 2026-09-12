package com.zenith.thermal.ui.screens

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.thermal.BatteryMonitorService
import com.zenith.thermal.ui.components.*
import com.zenith.thermal.ui.theme.*

private const val PREFS = "zenith_battery"

@Composable
fun BatteryScreen() {
    val ctx = LocalContext.current
    val prefs = remember(ctx) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var monitorOn by remember { mutableStateOf(prefs.getBoolean("monitor_running", false)) }
    var resetPlugged by remember { mutableStateOf(prefs.getBoolean("reset_on_plugged", false)) }
    var resetTargetOn by remember { mutableStateOf(prefs.getBoolean("reset_on_target", true)) }
    var resetRestart by remember { mutableStateOf(prefs.getBoolean("reset_on_restart", false)) }
    var idleWarn by remember { mutableStateOf(prefs.getBoolean("idle_warning_enabled", false)) }
    var idlePct by remember { mutableStateOf(prefs.getInt("idle_warning_target", 5).coerceIn(1, 30).toFloat()) }
    var tgtPct by remember { mutableStateOf(prefs.getInt("reset_target", 100).coerceIn(1, 100).toFloat()) }
    var tempUnit by remember { mutableStateOf(prefs.getString("temperature_unit", "C") ?: "C") }
    var showPower by remember { mutableStateOf(true) }

    // Battery data
    var batPct by remember { mutableIntStateOf(0) }
    var batCurrentMa by remember { mutableFloatStateOf(0f) }
    var batTempC by remember { mutableFloatStateOf(0f) }
    var isCharging by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            monitorOn = prefs.getBoolean("monitor_running", false)
            runCatching {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                batPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                batCurrentMa = kotlin.math.abs(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)) / 1000f
                isCharging = bm.isCharging
            }
            runCatching {
                val intent = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                batTempC = (intent?.getIntExtra("temperature", 0) ?: 0) / 10f
            }
            delay(2000)
        }
    }

    val powerW = remember(batCurrentMa) { batCurrentMa * 3.8f / 1000f }

    // Gradient bg per HTML preview
    Box(Modifier.fillMaxSize()) {
        // Background gradient
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF08080F))
        )
        // Radial glow top
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(
                            ZenithPurple.copy(alpha = 0.10f),
                            ZenithPink.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        center = Offset(0.5f, 0.1f),
                        radius = 800f
                    )
                )
        )
        // Content
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(44.dp))
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.lg)
                    .padding(bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                // Header
                Text("Zenith Thermal", color = ZenithText, fontSize = TextHeading, fontWeight = FontWeight.Bold)

                // Status Monitor card
                GradientBorderCard(modifier = Modifier.fillMaxWidth(), innerPadding = Space.lg) {
                    Text("Status Monitor", color = ZenithText, fontSize = TextBodySm, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(Space.xs))
                    Text("Real-time battery statistics in notification.", color = ZenithMuted2, fontSize = TextMicro)
                    Spacer(Modifier.height(Space.sm))
                    Box(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(if (monitorOn) ZenithRed.copy(0.2f) else ZenithPurple.copy(0.2f))
                            .clickable {
                                if (monitorOn) {
                                    ctx.startService(
                                        Intent(ctx, BatteryMonitorService::class.java).apply {
                                            action = BatteryMonitorService.ACTION_STOP
                                        }
                                    )
                                    monitorOn = false
                                    prefs.edit().putBoolean("monitor_running", false).apply()
                                } else {
                                    if (Build.VERSION.SDK_INT >= 33 &&
                                        ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                                        android.content.pm.PackageManager.PERMISSION_GRANTED
                                    ) {
                                        ctx.startActivity(
                                            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                        )
                                    } else {
                                        val start = Intent(ctx, BatteryMonitorService::class.java)
                                        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(start) else ctx.startService(start)
                                        monitorOn = true
                                        prefs.edit().putBoolean("monitor_running", true).apply()
                                    }
                                }
                            }
                            .padding(vertical = Space.sm),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (monitorOn) "TURN OFF MONITOR" else "TURN ON MONITOR",
                            color = ZenithText, fontSize = TextMicro, fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Stats row — current/power/temp
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    StatPill("Power", "%.2fW".format(powerW), ZenithGreen, Modifier.weight(1f))
                    StatPill("Battery", "%.0f°C".format(batTempC), ZenithText, Modifier.weight(1f))
                    StatPill("Current", "%.0fmA".format(batCurrentMa), ZenithText, Modifier.weight(1f))
                }

                // Temp unit
                SectionLabel("Battery Temperature Unit")
                GradientBorderCard(modifier = Modifier.fillMaxWidth(), innerPadding = Space.xs) {
                    listOf("C" to "Celsius (°C)", "F" to "Fahrenheit (°F)", "K" to "Kelvin (K)").forEach { (code, label) ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { tempUnit = code; prefs.edit().putString("temperature_unit", code).apply() }
                                .padding(horizontal = Space.lg, vertical = Space.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, color = ZenithText, fontSize = TextBodySm, modifier = Modifier.weight(1f))
                            ZenithRadio(checked = code == tempUnit, onClick = {
                                tempUnit = code; prefs.edit().putString("temperature_unit", code).apply()
                            })
                        }
                    }
                }

                // Display — show current/power toggle
                SectionLabel("Display")
                GradientBorderCard(modifier = Modifier.fillMaxWidth(), innerPadding = Space.xs) {
                    SwitchRow("Show Current and Power (Watt)", showPower) {
                        showPower = it; prefs.edit().putBoolean("show_power", it).apply()
                    }
                }

                // Reset stats
                SectionLabel("Reset Stats")
                GradientBorderCard(modifier = Modifier.fillMaxWidth(), innerPadding = Space.xs) {
                    // Target threshold with slider
                    Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Reset stats when battery ≤", color = ZenithText, fontSize = TextBodySm, modifier = Modifier.weight(1f))
                            Text("${tgtPct.toInt()}%", color = ZenithPurple, fontSize = TextBodySm, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = tgtPct,
                            onValueChange = { tgtPct = it },
                            onValueChangeFinished = { prefs.edit().putInt("reset_target", tgtPct.toInt()).apply() },
                            valueRange = 5f..100f,
                            steps = 18,
                            colors = SliderDefaults.colors(
                                thumbColor = ZenithPurple,
                                activeTrackColor = ZenithPurple,
                                inactiveTrackColor = ZenithMuted3
                            )
                        )
                    }
                    SwitchRow("Reset stats when plugged in", resetPlugged) {
                        resetPlugged = it; prefs.edit().putBoolean("reset_on_plugged", it).apply()
                    }
                    SwitchRow("Reset stats on reboot", resetRestart) {
                        resetRestart = it; prefs.edit().putBoolean("reset_on_restart", it).apply()
                    }
                }

                // Idle drain warning
                SectionLabel("Idle Drain Warning")
                GradientBorderCard(modifier = Modifier.fillMaxWidth(), innerPadding = Space.xs) {
                    SwitchRow("Notify if idle drain is higher than threshold", idleWarn) {
                        idleWarn = it; prefs.edit().putBoolean("idle_warning_enabled", it).apply()
                    }
                    Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Threshold", color = ZenithText, fontSize = TextBodySm, modifier = Modifier.weight(1f))
                            Text("${idlePct.toInt()}%", color = ZenithPurple, fontSize = TextBodySm, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = idlePct,
                            onValueChange = { idlePct = it },
                            onValueChangeFinished = { prefs.edit().putInt("idle_warning_target", idlePct.toInt()).apply() },
                            valueRange = 1f..30f,
                            steps = 28,
                            colors = SliderDefaults.colors(
                                thumbColor = ZenithPurple,
                                activeTrackColor = ZenithPurple,
                                inactiveTrackColor = ZenithMuted3
                            )
                        )
                    }
                }

                Spacer(Modifier.height(Space.sm))

                // Reset button — danger
                Box(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.lg))
                        .background(ZenithRed.copy(0.1f))
                        .border(1.dp, ZenithRed.copy(0.15f), RoundedCornerShape(Radius.lg))
                        .clickable {
                            ctx.startService(
                                Intent(ctx, BatteryMonitorService::class.java).apply {
                                    action = BatteryMonitorService.ACTION_RESET
                                }
                            )
                        }
                        .padding(vertical = Space.md),
                    contentAlignment = Alignment.Center
                ) {
                    Text("RESET STATS", color = ZenithRed, fontSize = TextMicro, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                }

                Spacer(Modifier.height(Space.sm))
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(Color(0x08FFFFFF))
            .border(1.dp, ZenithBorder2, RoundedCornerShape(Radius.md))
            .padding(vertical = Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = color, fontSize = TextCaption, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Space.xs))
        Text(label.uppercase(), color = ZenithMuted2, fontSize = TextMicro, letterSpacing = 0.6.sp)
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = ZenithText, fontSize = TextBodySm, modifier = Modifier.weight(1f))
        ZenithSwitch(checked = checked, onChange = onChange)
    }
}