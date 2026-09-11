package com.zenith.thermal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.thermal.ZenithDaemonClient
import kotlinx.coroutines.delay

private val SurfaceColor = Color(0xFF2A444D)
private val TextColor = Color(0xFFF2F5F6)
private val MutedColor = Color(0xFFB8C6CA)

@Composable
fun BatteryScreen() {
    var status by remember { mutableStateOf<ZenithDaemonClient.StatusResponse?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            status = ZenithDaemonClient.getStatus()
            delay(2000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Battery", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextColor)
        Spacer(Modifier.height(18.dp))

        val bat = status?.battery
        StatCard("Capacity", "${bat?.capacityPct?.toInt() ?: 0}%")
        StatCard("Current", bat?.currentMa?.let { "%.1f mA".format(it) } ?: "—")
        StatCard("Power", bat?.powerMw?.let { if (it > 0) "%.2f W".format(it / 1000.0) else "—" } ?: "—")
        StatCard(
            "Status",
            when {
                bat == null -> "—"
                bat.capacityPct >= 100 && bat.online -> "Fully charged"
                bat.online -> "Charging"
                else -> "Discharging"
            }
        )

        Spacer(Modifier.height(12.dp))
        Text("Drain", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MutedColor)
        StatCard("Active", status?.batteryDrainPctPerHr?.let { "%.1f%%/h".format(it) } ?: "—")

        Spacer(Modifier.height(12.dp))
        Text("Thermal", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MutedColor)
        val zones = status?.thermalZones
        if (!zones.isNullOrEmpty()) {
            StatCard("Avg temp", "%.1f°C".format(zones.map { it.tempC }.average()))
            StatCard("Max temp", "%.1f°C".format(zones.maxOf { it.tempC }))
        } else {
            StatCard("Temp", "—")
        }
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .background(SurfaceColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = MutedColor)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextColor)
    }
}
