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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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

private const val PREFS = "zenith_battery"
private const val K_PLUGGED = "reset_on_plugged"
private const val K_TARGET = "reset_on_target"
private const val K_RESTART = "reset_on_restart"
private const val K_IDLE_ON = "idle_warning_enabled"
private const val K_IDLE_PCT = "idle_warning_target"
private const val K_TGT_PCT = "reset_target"
private const val K_UNIT = "temperature_unit"

/** Read battery info from Android BatteryManager — no daemon dependency. */
private fun readBattery(ctx: Context): Triple<Int, Float, Float>? {
    return runCatching {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val tempC = bm.getIntProperty(0) // need to get from intent
        // For temp, read from registerReceiver intent
        val intent = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val tempDeci = intent?.getIntExtra("temperature", 0) ?: 0
        Triple(pct, currentUa.toFloat() / 1000f, tempDeci / 10f)
    }.getOrNull()
}

@Composable
fun BatteryScreen() {
    val ctx = LocalContext.current
    val prefs = remember(ctx) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var monitorOn by remember { mutableStateOf(false) }
    var resetPlugged by remember { mutableStateOf(prefs.getBoolean(K_PLUGGED, false)) }
    var resetTargetOn by remember { mutableStateOf(prefs.getBoolean(K_TARGET, true)) }
    var resetRestart by remember { mutableStateOf(prefs.getBoolean(K_RESTART, false)) }
    var idleWarn by remember { mutableStateOf(prefs.getBoolean(K_IDLE_ON, false)) }
    var idlePct by remember { mutableStateOf(prefs.getInt(K_IDLE_PCT, 5).coerceIn(1, 100)) }
    var tgtPct by remember { mutableStateOf(prefs.getInt(K_TGT_PCT, 100).coerceIn(1, 100)) }
    var tempUnit by remember { mutableStateOf(prefs.getString(K_UNIT, "C") ?: "C") }

    // Battery data — poll via BatteryManager
    var batPct by remember { mutableStateOf(0) }
    var batCurrentMa by remember { mutableStateOf(0f) }
    var batTempC by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            monitorOn = batteryMonitorServiceRunning(ctx)
            readBattery(ctx)?.let { (pct, currentMa, tempC) ->
                batPct = pct; batCurrentMa = currentMa; batTempC = tempC
            }
            delay(2000)
        }
    }

    // Power estimate from current × 3.8V typical
    val powerW = remember(batCurrentMa) { kotlin.math.abs(batCurrentMa) * 3.8f / 1000f }

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
                val charging = monitorOn
                GradientBorderCard(
                    modifier = Modifier.fillMaxWidth(),
                    radius = 14.dp,
                    gradient = androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(ZenithPurple.copy(0.4f), ZenithPink.copy(0.3f), ZenithPurple.copy(0.2f))
                    )
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        BatteryRing(pct = batPct, size = 72.dp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (monitorOn) "Charging · $batPct%" else "Discharging · $batPct%",
                        color = ZenithText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }

                // Stats row
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatPill("Power", "%.1fW".format(powerW), ZenithGreen)
                    StatPill("Battery", "%.0f°C".format(batTempC), ZenithText)
                    StatPill("Current", "%.0f mA".format(kotlin.math.abs(batCurrentMa)), ZenithText)
                }

                // Temperature unit
                SectionLabel("Battery Temperature Unit")
                GradientBorderCard(modifier = Modifier.fillMaxWidth(), innerPadding = 4.dp) {
                    UnitRow("Celsius (°C)", "C", tempUnit) { tempUnit = it; prefs.edit().putString(K_UNIT, it).apply() }
                    Div()
                    UnitRow("Fahrenheit (°F)", "F", tempUnit) { tempUnit = it; prefs.edit().putString(K_UNIT, it).apply() }
                    Div()
                    UnitRow("Kelvin (K)", "K", tempUnit) { tempUnit = it; prefs.edit().putString(K_UNIT, it).apply() }
                }

                // Display
                SectionLabel("Display")
                GradientBorderCard(modifier = Modifier.fillMaxWidth(), innerPadding = 4.dp) {
                    SwitchRow("Show Current and Power (Watt)", true) { }
                }

                // Reset stats
                SectionLabel("Reset Stats")
                GradientBorderCard(modifier = Modifier.fillMaxWidth(), innerPadding = 4.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Reset stats when battery ≤",
                            color = ZenithText, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        SelectPill(text = "$tgtPct%", selected = resetTargetOn, onClick = { })
                    }
                    Div()
                    SwitchRow("Reset stats when plugged in", resetPlugged) {
                        resetPlugged = it; prefs.edit().putBoolean(K_PLUGGED, it).apply()
                    }
                    Div()
                    SwitchRow("Reset stats on reboot", resetRestart) {
                        resetRestart = it; prefs.edit().putBoolean(K_RESTART, it).apply()
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
                        SelectPill(text = "$idlePct%", selected = idleWarn, onClick = { })
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Reset button
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ZenithRed.copy(0.1f))
                        .border(1.dp, ZenithRed.copy(0.15f), RoundedCornerShape(12.dp))
                        .clickable {
                            val intent = Intent(ctx, BatteryMonitorService::class.java).apply {
                                action = BatteryMonitorService.ACTION_RESET
                            }
                            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("RESET STATS", color = ZenithRed, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun BatteryRing(pct: Int, size: Dp) {
    val sweep = pct.coerceIn(0, 100) * 3.6f
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            drawArc(Color(0x0FFFFFFF), -90f, 360f, false, Stroke(8.dp.toPx(), cap = StrokeCap.Butt), Size(size.toPx(), size.toPx()), Offset.Zero)
            drawArc(ZenithGreen, -90f, sweep, false, Stroke(8.dp.toPx(), cap = StrokeCap.Butt), Size(size.toPx(), size.toPx()), Offset.Zero)
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
            .border(1.dp, ZenithBorder2, RoundedCornerShape(10.dp))
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
        Modifier.fillMaxWidth().clickable { onSelect(code) }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = ZenithText, fontSize = 12.sp, modifier = Modifier.weight(1f))
        ZenithRadio(checked = code == current, onClick = { onSelect(code) })
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = ZenithText, fontSize = 12.sp, modifier = Modifier.weight(1f))
        ZenithSwitch(checked = checked, onChange = onChange)
    }
}

@Composable
private fun Div() {
    Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(0.5.dp).background(Color(0x08FFFFFF)))
}

private fun batteryMonitorServiceRunning(ctx: Context): Boolean =
    runCatching {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.getRunningServices(100).any { it.service.className == BatteryMonitorService::class.java.name }
    }.getOrDefault(false)