package com.zenith.thermal.ui.screens

import android.os.Build
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.thermal.BuildConfig
import com.zenith.thermal.ui.theme.*
import kotlin.math.*

// ════════════════════════════════════════════════════════════
// Data model
// ════════════════════════════════════════════════════════════
private data class SessionEntry(
    val id: Long, val appName: String, val appPkg: String,
    val date: String, val version: String, val crop: String,
    val avgFps: Float, val maxFps: Float, val minFps: Float,
    val variance: Float, val smoothPct: Float, val low5Pct: Float,
    val peakTemp: Float, val avgPowerW: Float,
    val durationSec: Long,
    val chartData: ChartData = ChartData()
)
private data class ChartData(
    val fps: List<Float> = List(20) { 0f },
    val temp: List<Float> = List(20) { 0f },
    val cpuTotal: List<Float> = List(20) { 0f },
    val cpu03: List<Float> = List(20) { 0f },
    val cpu46: List<Float> = List(20) { 0f },
    val cpu7: List<Float> = List(20) { 0f },
    val gpuFreq: List<Float> = List(20) { 0f },
    val gpuUsage: List<Float> = List(20) { 0f },
    val ddr: List<Float> = List(20) { 0f },
    val powerW: List<Float> = List(20) { 0f },
    val capacity: List<Float> = List(20) { 100f },
    val frameTime: List<Float> = List(20) { 0f },
)
private enum class RecordView { LIST, DETAIL }

// ════════════════════════════════════════════════════════════
// Root
// ════════════════════════════════════════════════════════════
@Composable
fun RecordScreen() {
    var view by remember { mutableStateOf(RecordView.LIST) }
    var selected by remember { mutableStateOf<SessionEntry?>(null) }
    var sessions by remember { mutableStateOf(listOf<SessionEntry>()) }
    LaunchedEffect(Unit) { sessions = loadSessions() }
    AnimatedContent(view, label = "rt") { cur ->
        when (cur) {
            RecordView.LIST -> SessionListView(sessions, { selected = it; view = RecordView.DETAIL }, { sessions = loadSessions() })
            RecordView.DETAIL -> selected?.let { SessionDetailView(it) { view = RecordView.LIST } }
        }
    }
}

// ════════════════════════════════════════════════════════════
// LIST VIEW
// ════════════════════════════════════════════════════════════
@Composable
private fun SessionListView(sessions: List<SessionEntry>, onSelect: (SessionEntry) -> Unit, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // Header: back (hidden on main), "FPS Stats" center, upload icon right
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(36.dp)) // placeholder for alignment
            Spacer(Modifier.weight(1f))
            Text("FPS Stats", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Surface(onClick = onRefresh, shape = CircleShape, color = Color(0xFF1c1c2d), modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.CloudUpload, "Refresh", tint = Color(0xFF4a9eff), modifier = Modifier.padding(8.dp))
            }
        }
        DeviceInfoCard(modifier = Modifier.padding(horizontal = 14.dp))
        Spacer(Modifier.height(8.dp))
        Text("SESSION HISTORY", color = Color(0xFF8e8e93), fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Analytics, null, tint = Color(0xFF3a3a3a), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No sessions recorded yet", color = Color(0xFF8e8e93), fontSize = 14.sp)
                    Text("Start a benchmark to capture data", color = Color(0xFF5a5a5a), fontSize = 12.sp)
                }
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions) { s -> SessionListItem(s) { onSelect(s) } }
        }
    }
}

@Composable
private fun SessionListItem(s: SessionEntry, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF1c1c2d), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF6b21a8), Color(0xFFa855f7)))),
                contentAlignment = Alignment.Center) {
                Text(s.appName.take(1), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.appName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${s.date} · %.0f fps · %.2fW · ${fmtDur(s.durationSec)}".format(s.avgFps, s.powerW), color = Color(0xFF8e8e93), fontSize = 11.sp)
            }
            Surface(shape = CircleShape, color = Color(0xFF2c2c2e), modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Delete, "Delete", tint = Color(0xFF8e8e93), modifier = Modifier.padding(7.dp))
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// DETAIL VIEW
// ════════════════════════════════════════════════════════════
@Composable
private fun SessionDetailView(s: SessionEntry, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // Header: back + app name + version/sub + filter/share/download icons
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(onClick = onBack, shape = CircleShape, color = Color(0xFF1c1c2d), modifier = Modifier.size(42.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.padding(11.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.appName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${s.version} · ${Build.BOARD} · Android ${Build.VERSION.RELEASE}", color = Color(0xFF8e8e93), fontSize = 11.sp)
            }
            Icon(Icons.Outlined.CloudDownload, null, tint = Color(0xFF8e8e93), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.Share, null, tint = Color(0xFF8e8e93), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.Settings, null, tint = Color(0xFF8e8e93), modifier = Modifier.size(20.dp))
        }

        // Device card 4 cols (with Profile)
        DeviceInfoCard(showProfile = true, modifier = Modifier.padding(horizontal = 14.dp))
        Spacer(Modifier.height(8.dp))

        // Session card: icon+date center | name+crop right | stats grid
        SessionInfoCard(s)
        Spacer(Modifier.height(4.dp))

        // All chart cards — scrollable
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 1. FPS & Temperature (dual Y-axis)
            item { FpsTempChart(s.chartData) }
            // 2. Frame Time (bar)
            item { FrameTimeChart(s.chartData) }
            // 3. CPU Usage (4 lines)
            item { CpuUsageChart(s.chartData) }
            // 4. CPU Frequency (3 lines)
            item { CpuFreqChart(s.chartData) }
            // 5. CPU Cycles + CPU Temp (side by side)
            item { CpuCyclesTempChart(s.chartData) }
            // 6. GPU Frequency & Usage
            item { GpuChart(s.chartData) }
            // 7. DDR
            item { DdrChart(s.chartData) }
            // 8. Power + Capacity (side by side)
            item { PowerCapacityChart(s.chartData) }
            // 9. CPU Temperature
            item { CpuTempChart(s.chartData) }
        }
    }
}

// ── Device Card ──
@Composable
private fun DeviceInfoCard(showProfile: Boolean = false, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF1c1c2d), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            DevInfoItem(Icons.Outlined.Memory, "Platform", Build.BOARD)
            DevInfoItem(Icons.Outlined.Smartphone, "Model", Build.MODEL)
            DevInfoItem(Icons.Outlined.Android, "OS", "Android ${Build.VERSION.RELEASE}")
            if (showProfile) DevInfoItem(Icons.Outlined.Tune, "Profile", "###")
        }
    }
}
@Composable
private fun DevInfoItem(icon: ImageVector, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
        Icon(icon, null, tint = Color(0xFF4a9eff), modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color(0xFF8e8e93), fontSize = 9.sp, fontWeight = FontWeight.Medium)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Session Info Card (icon+date center | name+crop right | stats 2×4) ──
@Composable
private fun SessionInfoCard(s: SessionEntry) {
    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF1c1c2d), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Column(Modifier.padding(14.dp)) {
            // Top row: icon + date center | name + crop right
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF6b21a8), Color(0xFFa855f7)))),
                    contentAlignment = Alignment.Center) {
                    Text(s.appName.take(1), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Text(s.date, color = Color(0xFF8e8e93), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("${s.appName}(${s.version})", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("crop: ${s.crop}", color = Color(0xFF8e8e93), fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            // Stats row 1: FPS metrics
            StatsGridRow(listOf(
                Triple("MAX", "%.1f".format(s.maxFps), "FPS"),
                Triple("MIN", "%.1f".format(s.minFps), "FPS"),
                Triple("AVG", "%.1f".format(s.avgFps), "FPS"),
                Triple("VARIANCE", "%.1f".format(s.variance), "FPS"),
            ))
            Spacer(Modifier.height(4.dp))
            // Stats row 2: quality metrics
            StatsGridRow(listOf(
                Triple("≥45FPS", "%.1f%%".format(s.smoothPct), "Smoothness"),
                Triple("5% Low", "%.1f".format(s.low5Pct), "FPS"),
                Triple("MAX", "%.1f°".format(s.peakTemp), "Temperature"),
                Triple("AVG", "%.2f".format(s.avgPowerW), "Power(W)"),
            ))
        }
    }
}
@Composable
private fun StatsGridRow(items: List<Triple<String, String, String>>) {
    Row(Modifier.fillMaxWidth()) {
        items.forEach { (label, value, unit) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(label, color = Color(0xFF6b7280), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text(value, color = Color(0xFF4a9eff), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Text(unit, color = Color(0xFF9ca3af), fontSize = 9.sp)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// CHART CARDS — Custom Canvas
// ════════════════════════════════════════════════════════════
private val cardBg = Color(0xFF1c1c2d)
private val gridColor = Color(0xFF222222)
private val mutedText = Color(0xFF9ca3af)
private val dimText = Color(0xFF6b7280)
private val accentBlue = Color(0xFF4a9eff)
private val accentGray = Color(0xFF8e8e93)

// ── Chart card wrapper ──
@Composable
private fun ChartCard(titleLeft: String, titleRight: String? = null, chartOptions: Boolean = false, legend: @Composable (() -> Unit)? = null, stats: String? = null, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = cardBg, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(titleLeft, color = mutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (titleRight != null) Text(titleRight, color = mutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (chartOptions) Text("Chart Options", color = dimText, fontSize = 10.sp)
            }
            Spacer(Modifier.height(6.dp))
            content()
            legend?.let { Spacer(Modifier.height(4.dp)); it() }
            stats?.let { Spacer(Modifier.height(4.dp)); Text(it, color = dimText, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        }
    }
}
@Composable
private fun ChartLegend(items: List<Pair<String, Color>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        items.forEach { (name, clr) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(clr))
                Spacer(Modifier.width(3.dp))
                Text(name, color = mutedText, fontSize = 10.sp)
            }
        }
    }
}

// ── 1. FPS & Temperature (dual Y-axis) ──
@Composable
private fun FpsTempChart(d: ChartData) {
    ChartCard("FPS", "Temperature(C°)") {
        Box(Modifier.fillMaxWidth().height(140.dp)) {
            DualAxisLineChart(d.fps, d.temp, 0f, 100f, 30f, 50f, accentGray, Color(0xFFfb923c))
        }
    }?.also {
        // Legend after chart
    }
    // legend outside ChartCard
    ChartLegend(listOf("FPS" to accentGray, "TEMP(°C)" to Color(0xFFfb923c), "CPU(%)" to Color(0xFFc084fc), "GPU(%)" to Color(0xFF22d3ee)))
}

// ── 2. Frame Time (bar) ──
@Composable
private fun FrameTimeChart(d: ChartData) {
    ChartCard("Frame Time(ms)", stats = "MAX: ${"%.0f".format(d.frameTime.maxOrNull() ?: 0f)}ms") {
        Box(Modifier.fillMaxWidth().height(130.dp)) {
            BarChart(d.frameTime, accentBlue, maxValue = (d.frameTime.maxOrNull()?.coerceAtLeast(50f) ?: 100f) * 1.2f)
        }
    }
}

// ── 3. CPU Usage (4 lines) ──
@Composable
private fun CpuUsageChart(d: ChartData) {
    ChartCard("CPU Usage(%)", chartOptions = true) {
        Box(Modifier.fillMaxWidth().height(130.dp)) {
            MultiLineChart(listOf(d.cpuTotal to Color(0xFF60a5fa), d.cpu03 to Color(0xFFc084fc), d.cpu46 to Color(0xFF22d3ee), d.cpu7 to Color(0xFFfb923c)), 0f, 100f)
        }
    }?.also {}
    ChartLegend(listOf("Total" to Color(0xFF60a5fa), "CPU 0~3" to Color(0xFFc084fc), "CPU 4~6" to Color(0xFF22d3ee), "CPU 7" to Color(0xFFfb923c)))
}

// ── 4. CPU Frequency (3 lines) ──
@Composable
private fun CpuFreqChart(d: ChartData) {
    ChartCard("CPU Frequency (MHz)", chartOptions = true) {
        Box(Modifier.fillMaxWidth().height(130.dp)) {
            MultiLineChart(listOf(d.cpu03 to Color(0xFFc084fc), d.cpu46 to Color(0xFF22d3ee), d.cpu7 to Color(0xFFfb923c)), 0f, 3000f)
        }
    }
    ChartLegend(listOf("CPU 0~3" to Color(0xFFc084fc), "CPU 4~6" to Color(0xFF22d3ee), "CPU 7" to Color(0xFFfb923c)))
}

// ── 5. CPU Cycles + CPU Temperature (side by side) ──
@Composable
private fun CpuCyclesTempChart(d: ChartData) {
    Surface(shape = RoundedCornerShape(14.dp), color = cardBg, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("CPU Cycles(M)", color = mutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(100.dp)) { MultiLineChart(listOf(d.cpu03 to Color(0xFFc084fc), d.cpu46 to Color(0xFF22d3ee), d.cpu7 to Color(0xFFfb923c)), 0f, 100f) }
                }
                Column(Modifier.weight(1f)) {
                    Text("CPU Temperature(°C)", color = mutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(100.dp)) { AreaChart(d.temp, Color(0xFFfb923c)) }
                }
            }
            Spacer(Modifier.height(4.dp))
            ChartLegend(listOf("CPU 0~3" to Color(0xFFc084fc), "CPU 4~6" to Color(0xFF22d3ee), "CPU 7" to Color(0xFFfb923c), "TEMP(°C)" to Color(0xFFfb923c)))
        }
    }
}

// ── 6. GPU Frequency & Usage ──
@Composable
private fun GpuChart(d: ChartData) {
    ChartCard("GPU Frequency (MHz)", "Usage(%)") {
        Box(Modifier.fillMaxWidth().height(130.dp)) {
            MultiLineChart(listOf(d.gpuFreq to Color(0xFF87ceeb), d.gpuUsage to Color(0xFF60a5fa)), 0f, 600f)
        }
    }
    ChartLegend(listOf("Frequency(MHz)" to Color(0xFF87ceeb), "Usage(%)" to Color(0xFF60a5fa)))
}

// ── 7. DDR ──
@Composable
private fun DdrChart(d: ChartData) {
    ChartCard("DDR(MHz | Mbps)") {
        Box(Modifier.fillMaxWidth().height(130.dp)) {
            AreaChart(d.ddr, accentBlue)
        }
    }
}

// ── 8. Power + Capacity (side by side) ──
@Composable
private fun PowerCapacityChart(d: ChartData) {
    val maxP = (d.powerW.maxOrNull()?.coerceAtLeast(5f) ?: 10f) * 1.2f
    Surface(shape = RoundedCornerShape(14.dp), color = cardBg, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Power(W)", color = mutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("Capacity %", color = mutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).height(100.dp)) { LineChart(d.powerW, accentBlue, 0f, maxP) }
                Box(Modifier.weight(1f).height(100.dp)) { LineChart(d.capacity, Color.White, 0f, 100f) }
            }
            Spacer(Modifier.height(4.dp))
            ChartLegend(listOf("Power(W)" to accentBlue, "Capacity(%)" to Color.White))
            Spacer(Modifier.height(4.dp))
            Text("MAX: ${"%.2f".format(d.powerW.maxOrNull() ?: 0f)}W  MIN: ${"%.2f".format(d.powerW.minOrNull() ?: 0f)}W  AVG: ${"%.2f".format(d.powerW.average())}W", color = dimText, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ── 9. CPU Temperature ──
@Composable
private fun CpuTempChart(d: ChartData) {
    ChartCard("CPU Temperature (°C)") {
        Box(Modifier.fillMaxWidth().height(130.dp)) { AreaChart(d.temp, accentBlue) }
    }
    Text("MAX: ${"%.1f".format(d.temp.maxOrNull() ?: 0f)}°C  MIN: ${"%.1f".format(d.temp.minOrNull() ?: 0f)}°C  AVG: ${"%.1f".format(d.temp.average())}°C", color = dimText, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
}

// ════════════════════════════════════════════════════════════
// CANVAS CHART COMPONENTS
// ════════════════════════════════════════════════════════════
// Single line chart
@Composable
private fun LineChart(data: List<Float>, color: Color, minY: Float, maxY: Float, fillBelow: Boolean = false) {
    Canvas(Modifier.fillMaxSize().padding(end = 2.dp)) {
        if (data.size < 2) return@Canvas
        val w = size.width; val h = size.height
        val range = (maxY - minY).coerceAtLeast(0.01f)
        val stepX = w / (data.size - 1)
        val pts = data.mapIndexed { i, v -> Offset(i * stepX, h - ((v - minY) / range) * h) }
        val path = Path().apply { moveTo(pts.first().x, pts.first().y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y) }
        if (fillBelow) {
            val fill = Path().apply { addPath(path); lineTo(w, h); lineTo(0f, h); close() }
            drawPath(fill, color.copy(alpha = 0.15f))
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// Multi-line chart
@Composable
private fun MultiLineChart(series: List<Pair<List<Float>, Color>>, minY: Float, maxY: Float) {
    Canvas(Modifier.fillMaxSize().padding(end = 2.dp)) {
        val w = size.width; val h = size.height; val range = (maxY - minY).coerceAtLeast(0.01f)
        // Grid lines
        for (i in 0..4) { val y = h * i / 4; drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 0.5.dp.toPx()) }
        series.forEach { (data, clr) ->
            if (data.size < 2) return@forEach
            val stepX = w / (data.size - 1)
            val pts = data.mapIndexed { i, v -> Offset(i * stepX, h - ((v.coerceIn(minY, maxY) - minY) / range) * h) }
            val path = Path().apply { moveTo(pts.first().x, pts.first().y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y) }
            drawPath(path, clr, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

// Dual Y-axis line chart
@Composable
private fun DualAxisLineChart(data1: List<Float>, data2: List<Float>, min1: Float, max1: Float, min2: Float, max2: Float, clr1: Color, clr2: Color) {
    Canvas(Modifier.fillMaxSize().padding(end = 2.dp)) {
        val w = size.width; val h = size.height
        val range1 = (max1 - min1).coerceAtLeast(0.01f); val range2 = (max2 - min2).coerceAtLeast(0.01f)
        // Grid
        for (i in 0..4) { val y = h * i / 4; drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 0.5.dp.toPx()) }
        // Line 1 (FPS left axis)
        if (data1.size >= 2) {
            val stepX = w / (data1.size - 1)
            val pts = data1.mapIndexed { i, v -> Offset(i * stepX, h - ((v.coerceIn(min1, max1) - min1) / range1) * h) }
            val path = Path().apply { moveTo(pts.first().x, pts.first().y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y) }
            drawPath(path, clr1, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // Line 2 (Temp right axis)
        if (data2.size >= 2) {
            val stepX = w / (data2.size - 1)
            val pts = data2.mapIndexed { i, v -> Offset(i * stepX, h - ((v.coerceIn(min2, max2) - min2) / range2) * h) }
            val path = Path().apply { moveTo(pts.first().x, pts.first().y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y) }
            drawPath(path, clr2, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

// Bar chart
@Composable
private fun BarChart(data: List<Float>, color: Color, maxValue: Float) {
    Canvas(Modifier.fillMaxSize().padding(end = 2.dp)) {
        val w = size.width; val h = size.height; val range = maxValue.coerceAtLeast(0.01f)
        val barW = w / data.size * 0.7f; val gap = w / data.size * 0.3f
        data.forEachIndexed { i, v ->
            val x = i * (barW + gap) + gap / 2
            val barH = ((v / range) * h).coerceIn(0f, h)
            drawRect(color, Offset(x, h - barH), Size(barW, barH))
        }
    }
}

// Area chart (filled line)
@Composable
private fun AreaChart(data: List<Float>, color: Color) {
    Canvas(Modifier.fillMaxSize().padding(end = 2.dp)) {
        if (data.size < 2) return@Canvas
        val w = size.width; val h = size.height
        val maxVal = data.max().coerceAtLeast(1f); val minVal = data.min().coerceAtMost(0f); val range = (maxVal - minVal).coerceAtLeast(0.01f)
        val stepX = w / (data.size - 1)
        val pts = data.mapIndexed { i, v -> Offset(i * stepX, h - ((v - minVal) / range) * h) }
        // Fill
        val fill = Path().apply { moveTo(pts.first().x, pts.first().y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y); lineTo(w, h); lineTo(0f, h); close() }
        drawPath(fill, color.copy(alpha = 0.15f))
        // Line
        val line = Path().apply { moveTo(pts.first().x, pts.first().y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y) }
        drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ════════════════════════════════════════════════════════════
// Helpers
// ════════════════════════════════════════════════════════════
private fun fmtDur(sec: Long): String { val m = sec / 60; val s = sec % 60; return if (m > 0) "${m}m${s}s" else "${s}s" }

private fun loadSessions(): List<SessionEntry> {
    // TODO: load from JSON/SQLite when daemon bench_data populates time-series
    return listOf(
        SessionEntry(1, "PUBG MOBILE", "com.tencent.ig", "2026-07-07 16:44:14", "4.4.0", "720×1600",
            avgFps = 83.3f, maxFps = 90.1f, minFps = 55.0f, variance = 101.3f,
            smoothPct = 100.0f, low5Pct = 57.5f, peakTemp = 40.9f, avgPowerW = 4.50f, durationSec = 226,
            chartData = demoChart(fpsBase = 83f, tempBase = 42f)),
    )
}

private fun demoChart(fpsBase: Float, tempBase: Float): ChartData = ChartData(
    fps = listOf(83f, 85f, 60f, 90f, 84f, 78f, 90f, 65f, 88f, 83f, 82f, 86f, 60f, 89f, 83f, 78f, 90f, 85f, 62f, 83f),
    temp = listOf(41f, 42f, 42.5f, 43f, 43f, 42f, 42.5f, 43f, 42f, 41.5f, 42f, 42.5f, 43f, 42.5f, 42f, 41.5f, 42f, 42.5f, 43f, 40.9f),
    cpuTotal = listOf(45f, 50f, 42f, 48f, 44f, 46f, 52f, 48f, 44f, 45f, 43f, 47f, 50f, 46f, 44f, 42f, 48f, 45f, 50f, 46f),
    cpu03 = listOf(38f, 42f, 35f, 40f, 36f, 38f, 44f, 40f, 36f, 38f, 35f, 39f, 42f, 38f, 36f, 35f, 40f, 38f, 42f, 38f),
    cpu46 = listOf(44f, 48f, 40f, 46f, 42f, 44f, 50f, 46f, 42f, 44f, 40f, 45f, 48f, 44f, 42f, 40f, 46f, 44f, 48f, 44f),
    cpu7 = listOf(60f, 78f, 55f, 95f, 70f, 65f, 85f, 60f, 70f, 65f, 55f, 75f, 80f, 68f, 60f, 55f, 78f, 65f, 78f, 65f),
    gpuFreq = listOf(520f, 520f, 520f, 520f, 520f, 520f, 520f, 520f, 520f, 520f, 520f, 520f, 520f, 520f, 520f, 520f, 520f, 520f, 520f, 520f),
    gpuUsage = listOf(15f, 12f, 20f, 10f, 18f, 14f, 16f, 12f, 19f, 15f, 13f, 17f, 11f, 18f, 14f, 16f, 12f, 15f, 20f, 14f),
    ddr = listOf(3110f, 4192f, 6410f, 5479f, 6410f, 4192f, 3110f, 4192f, 6410f, 5479f, 6410f, 4192f, 3110f, 4192f, 6410f, 5479f, 6410f, 4192f, 3110f, 4192f),
    powerW = listOf(4.2f, 6.1f, 3.8f, 5.5f, 4.5f, 2.0f, 4.8f, 5.2f, 3.9f, 4.5f, 5.8f, 4.2f, 3.5f, 5.0f, 4.5f, 4.0f, 5.5f, 4.8f, 3.8f, 4.5f),
    capacity = listOf(60f, 60f, 59f, 59f, 59f, 59f, 59f, 59f, 58f, 58f, 58f, 58f, 58f, 58f, 57f, 57f, 57f, 57f, 57f, 57f),
    frameTime = listOf(16f, 18f, 207f, 15f, 17f, 16f, 18f, 207f, 15f, 16f, 17f, 16f, 207f, 15f, 16f, 17f, 16f, 18f, 15f, 16f),
)
