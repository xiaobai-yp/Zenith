package com.zenith.thermal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.thermal.ZenithDaemonClient
import com.zenith.thermal.ui.components.SectionLabel
import com.zenith.thermal.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun RecordScreen() {
    var isRecording by remember { mutableStateOf(false) }
    var fps by remember { mutableIntStateOf(0) }
    var peakFps by remember { mutableIntStateOf(0) }
    var cpuLoad by remember { mutableFloatStateOf(0f) }
    var gpuTemp by remember { mutableFloatStateOf(0f) }
    var powerW by remember { mutableFloatStateOf(0f) }
    var batPct by remember { mutableIntStateOf(0) }
    var recordSec by remember { mutableLongStateOf(0L) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        while (true) {
            runCatching {
                val status = ZenithDaemonClient.getStatus()
                if (status != null) {
                    fps = status.fpsShort
                    cpuLoad = status.cpuLoadPct
                    gpuTemp = status.gpu?.tempC?.toFloat() ?: 0f
                    powerW = status.battery.powerMw / 1000f
                }
                val bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                batPct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            }
            if (isRecording) {
                peakFps = maxOf(peakFps, fps)
                recordSec++
            }
            delay(1000)
        }
    }

    Column(Modifier.fillMaxSize().background(ZenithBg)) {
        // Status bar spacer
        Spacer(Modifier.height(16.dp))

        // Header
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.lg), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Record", color = ZenithText, fontSize = 36.sp, fontWeight = FontWeight.Black)
                Text(
                    "Session telemetry",
                    color = ZenithMuted2,
                    fontSize = TextCaption,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = Space.xs)
                )
            }
                // Recording badge
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isRecording) ZenithRed.copy(0.12f) else ZenithPurple.copy(0.12f))
                        .border(1.dp, if (isRecording) ZenithRed else ZenithPurple, RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isRecording) {
                            Icon(Icons.Outlined.FiberManualRecord, null, tint = ZenithRed, modifier = Modifier.size(8.dp))
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            if (isRecording) "Recording" else "Idle",
                            color = if (isRecording) ZenithRed else ZenithPurple,
                            fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

        Spacer(Modifier.height(10.dp))

        // Content sheet
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

            // FPS hero card
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.xxl))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0x402D2345), Color(0x22221B38), Color(0x1616131F))
                        )
                    )
                    .border(1.dp, ZenithBorder2, RoundedCornerShape(Radius.xxl))
                    .padding(24.dp)
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    // FPS number with gradient text effect
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            fps.toString(),
                            fontSize = 64.sp, fontWeight = FontWeight.Black,
                            color = ZenithPurple // solid fallback for perf
                        )
                    }
                    Text("FPS · ${if (isRecording) "recording" else "avg"}", color = ZenithMuted2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(18.dp))

                    // Start/Stop button
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (isRecording) ZenithRed else ZenithPurple)
                            .clickable { isRecording = !isRecording }
                            .padding(horizontal = 32.dp, vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (isRecording) "● STOP" else "● START",
                            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    // Mini stats row
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        MiniStat(peakFps.toString(), "Peak")
                        MiniStat("%02d:%02d".format(recordSec / 60, recordSec % 60), "Duration")
                        MiniStat("%.1fW".format(powerW), "Power")
                        MiniStat("%.0f°C".format(gpuTemp), "GPU Temp")
                    }
                }
            }

            Spacer(Modifier.height(Space.xl))

            // Telemetry cards
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TelemetryCard("CPU Load", "%.0f%%".format(cpuLoad), ZenithPurple, Modifier.weight(1f))
                TelemetryCard("Battery", "$batPct%", ZenithGreen, Modifier.weight(1f))
            }

            Spacer(Modifier.height(Space.xl))

            // Recent sessions
            SectionLabel("Recent Sessions")
            Spacer(Modifier.height(Space.sm))
            SessionRow("BiliBili", "Today 14:02 · 18 min", "58 fps")
            SessionRow("MLBB", "Yesterday 20:11 · 42 min", "60 fps")
            }
            } // Surface
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = ZenithText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label.uppercase(), color = ZenithMuted2, fontSize = 10.sp, letterSpacing = 0.8.sp)
    }
}

@Composable
private fun TelemetryCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(Radius.xl))
            .background(Color(0x0DFFFFFF))
            .border(1.dp, ZenithBorder2, RoundedCornerShape(Radius.xl))
            .padding(16.dp)
    ) {
        Text(label.uppercase(), color = ZenithMuted2, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SessionRow(title: String, subtitle: String, fps: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.xl))
            .background(Color(0x0DFFFFFF))
            .border(1.dp, ZenithBorder2, RoundedCornerShape(Radius.xl))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = ZenithText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = ZenithMuted2, fontSize = 11.sp)
        }
        Text(fps, color = ZenithPurple, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}