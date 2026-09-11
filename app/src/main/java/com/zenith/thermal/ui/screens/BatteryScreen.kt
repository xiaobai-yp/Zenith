package com.zenith.thermal.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.thermal.BatteryMonitorService
import com.zenith.thermal.ZenithDaemonClient
import kotlinx.coroutines.delay

private val SurfaceColor = Color(0xFF2A444D)
private val TextColor = Color(0xFFF2F5F6)
private val MutedColor = Color(0xFFB8C6CA)
private val AccentColor = Color(0xFF5EA7FF)
private val BorderColor = Color(0xFF49636B)

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
    var showUnitDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            status = ZenithDaemonClient.getStatus()
            batteryMonitorOn = batteryMonitorServiceRunning(ctx)
            delay(2000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = 88.dp)
    ) {
        // Status Monitor section
        SectionCard("Status Monitor") {
            Text(
                "Displays real-time battery statistics (Temperature, Current mA, Deep Sleep) in the notification panel.",
                color = MutedColor, fontSize = 14.sp, lineHeight = 20.sp
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { toggleBatteryMonitor(ctx) },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = if (batteryMonitorOn) Color(0xFFB3261E) else AccentColor
                )
            ) {
                Text(
                    if (batteryMonitorOn) "TURN OFF MONITOR" else "TURN ON MONITOR",
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        // Display section
        SectionCard("Display") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Battery Temperature Unit",
                    color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    unitLabel(tempUnit) + "  ▾",
                    color = AccentColor, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { showUnitDialog = true }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Voltage and power accuracy depends on your device kernel. Values are real-time.",
                color = MutedColor, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        // Reset section
        SectionCard("Reset") {
            ToggleRow("Reset the stats when the battery reaches at or above a certain percentage", resetTarget) {
                resetTarget = it; prefs.edit().putBoolean(KEY_RESET_TARGET, it).apply()
            }
            SliderRow("Battery percentage for stats reset", resetTargetVal, onClick = {
                resetTargetVal = it; prefs.edit().putInt(KEY_RESET_TARGET_VAL, it).apply()
            })
            ToggleRow("Reset the stats when the device is plugged in", resetPlugged) {
                resetPlugged = it; prefs.edit().putBoolean(KEY_RESET_PLUGGED, it).apply()
            }
            ToggleRow("Reset the stats when the device reboots or shuts down", resetRestart) {
                resetRestart = it; prefs.edit().putBoolean(KEY_RESET_RESTART, it).apply()
            }
            ToggleRow("Notify me if idle drain is higher than a certain percentage", idleWarn) {
                idleWarn = it; prefs.edit().putBoolean(KEY_IDLE_WARN, it).apply()
            }
            SliderRow("Idle drain percentage warning", idleTarget, onClick = {
                idleTarget = it; prefs.edit().putInt(KEY_IDLE_TARGET, it).apply()
            })
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val intent = Intent(ctx, BatteryMonitorService::class.java).apply {
                        action = BatteryMonitorService.ACTION_RESET
                    }
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentColor)
            ) {
                Text("RESET STATS", color = AccentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Live stats
        SectionCard("Live Stats") {
            val bat = status?.battery
            StatRow("Capacity", "${bat?.capacityPct?.toInt() ?: 0}%")
            StatRow("Current", bat?.currentMa?.let { "%.1f mA".format(it) } ?: "—")
            StatRow("Power", bat?.powerMw?.let { if (it > 0) "%.2f W".format(it / 1000.0) else "—" } ?: "—")
            StatRow("Status", when {
                bat == null -> "—"
                bat.capacityPct >= 100 && bat.online -> "Fully charged"
                bat.online -> "Charging"
                else -> "Discharging"
            })
            StatRow("Active drain", status?.batteryDrainPctPerHr?.let { "%.1f%%/h".format(it) } ?: "—")
        }
    }

    if (showUnitDialog) {
        TemperatureUnitDialog(
            current = tempUnit,
            onSelect = { unit ->
                tempUnit = unit
                prefs.edit().putString(KEY_TEMP_UNIT, unit).apply()
                showUnitDialog = false
            },
            onDismiss = { showUnitDialog = false }
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(SurfaceColor, RoundedCornerShape(14.dp))
            .padding(18.dp)
    ) {
        Text(title, color = TextColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Int, onClick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label, color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                "$value%", color = TextColor, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Color(0xFF1B3138), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        androidx.compose.material3.Slider(
            value = value.toFloat(),
            onValueChange = { onClick(it.toInt().coerceIn(1, 100)) },
            valueRange = 1f..100f,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = AccentColor,
                activeTrackColor = AccentColor
            )
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MutedColor, fontSize = 14.sp)
        Text(value, color = TextColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TemperatureUnitDialog(current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val units = listOf("C" to "Celsius (°C)", "F" to "Fahrenheit (°F)", "K" to "Kelvin (K)")
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceColor,
        title = { Text("Temperature Unit", color = TextColor) },
        text = {
            Column {
                units.forEach { (code, label) ->
                    Text(
                        label + if (code == current) "  ✓" else "",
                        color = if (code == current) AccentColor else TextColor,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(code) }
                            .padding(vertical = 10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Text("Close", color = AccentColor, modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
        }
    )
}

private fun unitLabel(code: String): String = when (code) {
    "F" -> "Fahrenheit (°F)"
    "K" -> "Kelvin (K)"
    else -> "Celsius (°C)"
}

private fun batteryMonitorServiceRunning(ctx: Context): Boolean =
    runCatching {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.getRunningServices(100).any { it.service.className == BatteryMonitorService::class.java.name }
    }.getOrDefault(false)

private fun toggleBatteryMonitor(ctx: Context) {
    runCatching {
        val intent = Intent(ctx, BatteryMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
    }
}