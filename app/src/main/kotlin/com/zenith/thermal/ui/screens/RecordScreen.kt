package com.zenith.thermal.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// ═══════════════════════════════════════════════
// Palette — user-approved FPS Stats reference
// ═══════════════════════════════════════════════
private val Bg = Color(0xFF171717)
private val Panel = Color(0xFF090909)
private val Ink = Color(0xFFF4F4F4)
private val MutedC = Color(0xFFA4A4A4)
private val LabelC = Color(0xFF999999)
private val StatBlue = Color(0xFF3180FF)
private val CloudBlue = Color(0xFF2B75FF)
private val AxisC = Color(0xFF777777)
private val ChartTitleC = Color(0xFFA9A9A9)
private val LegendC = Color(0xFF8D8D8D)
private val GridC = Color(0x40BEBEBE)
// series
private val S_FPS = Color(0xFF828282)
private val S_TEMP = Color(0xFFB06B2C)
private val S_CPU03 = Color(0xFF8E62C7)
private val S_CPU46 = Color(0xFF36C6C4)
private val S_CPU7 = Color(0xFFEE923E)
private val S_GF = Color(0xFF8DC9E8)
private val S_GU = Color(0xFF2D6FFF)
private val S_DDR = Color(0xFF90D4F3)
private val S_PWR = Color(0xFF2D6FFF)
private val S_CAP = Color(0xFF8DC9E8)

private data class SessionEntry(
    val id: Long, val appName: String, val appPkg: String,
    val date: String, val version: String, val crop: String,
    val avgFps: Float, val maxFps: Float, val minFps: Float,
    val variance: Float, val smoothPct: Float, val low5Pct: Float,
    val peakTemp: Float, val avgPowerW: Float, val durationSec: Long,
    val chartData: ChartData = ChartData()
)
private data class ChartData(
    val fps: List<Float> = emptyList(), val temp: List<Float> = emptyList(),
    val cpu03: List<Float> = emptyList(), val cpu46: List<Float> = emptyList(), val cpu7: List<Float> = emptyList(),
    val gpuFreq: List<Float> = emptyList(), val gpuUsage: List<Float> = emptyList(),
    val ddr: List<Float> = emptyList(), val powerW: List<Float> = emptyList(), val capacity: List<Float> = emptyList(),
    val frameTime: List<Float> = emptyList(),
)
private enum class RecordView { LIST, DETAIL }

// ═══════════════════════════════════════════════
// Root
// ═══════════════════════════════════════════════
@Composable
fun RecordScreen() {
    var view by remember { mutableStateOf(RecordView.LIST) }
    var selected by remember { mutableStateOf<SessionEntry?>(null) }
    var sessions by remember { mutableStateOf(loadSessions()) }
    AnimatedContent(view, label = "rt") { cur ->
        when (cur) {
            RecordView.LIST -> SessionListView(sessions, { selected = it; view = RecordView.DETAIL })
            RecordView.DETAIL -> selected?.let { SessionDetailView(it) { view = RecordView.LIST } }
        }
    }
}

// ═══════════════════════════════════════════════
// Shared widgets
// ═══════════════════════════════════════════════
@Composable
private fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 18.dp, top = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("‹", color = Color.White, fontSize = 42.sp, lineHeight = 36.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .clickable(enabled = onBack != null) { onBack?.invoke() }
                .padding(horizontal = 6.dp, vertical = 2.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        actions()
    }
}

@Composable
private fun ChipIcon(tint: Color, side: Int = 46) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(side.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val s = size.width; val b = s * 0.12f
                drawRect(Color.Transparent, Offset.Zero, this.size)
                drawRoundRect(tint, Offset(b, b * 1.6f), Size(s - 2 * b, s - 3.2f * b), CornerRadius(4.dp.toPx()))
                val pin = b * 0.9f
                drawLine(tint, Offset(s / 2 - pin * 2, 0f), Offset(s / 2 - pin * 2, b), strokeWidth = b * 0.55f)
                drawLine(tint, Offset(s / 2 + pin * 2, 0f), Offset(s / 2 + pin * 2, b), strokeWidth = b * 0.55f)
                drawLine(tint, Offset(s / 2 - pin * 2, s - b), Offset(s / 2 - pin * 2, s), strokeWidth = b * 0.55f)
                drawLine(tint, Offset(s / 2 + pin * 2, s - b), Offset(s / 2 + pin * 2, s), strokeWidth = b * 0.55f)
                drawRect(tint, Offset(b, s / 2 - b * 0.35f), Size(s - 2 * b, b * 0.7f))
            }
        }
    }
}

@Composable
private fun PhoneIcon(tint: Color, side: Int = 46) {
    Canvas(Modifier.size(side.dp)) {
        val w = size.width * 0.6f; val h = size.height * 0.78f
        val x = (size.width - w) / 2; val y = (size.height - h) / 2
        drawRoundRect(Color.Transparent, Offset.Zero, this.size)
        drawRoundRect(tint, Offset(x, y), Size(w, h), CornerRadius(4.dp.toPx()), style = Stroke(2.2.dp.toPx()))
        drawCircle(tint, radius = 1.6.dp.toPx(), center = Offset(size.width / 2, y + h - 5.dp.toPx()))
    }
}

@Composable
private fun AndroidIcon(tint: Color, size: Int = 46) {
    Icon(Icons.Outlined.Android, null, tint = tint, modifier = Modifier.size(size.dp))
}

@Composable
private fun GameIcon(name: String, size: Int = 88, fontSize: Int = 14, radius: Int = 20) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(radius.dp)).background(Brush.linearGradient(listOf(Color(0xFF7D8C9F), Color(0xFF243C53)))),
        contentAlignment = Alignment.Center) {
        Text(name.take(4), color = Color.White, fontSize = fontSize.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.background(Color(0x11111111)).padding(horizontal = 5.dp, vertical = 3.dp))
    }
}

private fun comma(v: Float, dec: Int = 1): String = String.format("%.${dec}f", v).replace('.', ',')

// ═══════════════════════════════════════════════
// LIST VIEW
// ═══════════════════════════════════════════════
@Composable
private fun SessionListView(sessions: List<SessionEntry>, onSelect: (SessionEntry) -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar("FPS Stats", actions = {
            Icon(Icons.Outlined.CloudUpload, "Refresh", tint = CloudBlue, modifier = Modifier.size(34.dp))
        })
        Column(Modifier.padding(horizontal = 17.dp)) {
            DeviceCard(
                listOf(ChipIcon(LabelC) to ("Platform" to Build.BOARD),
                    PhoneIcon(LabelC) to ("Model" to Build.MODEL),
                    AndroidIcon(Color(0xFF8BD25A)) to ("OS" to "Android ${Build.VERSION.RELEASE}"))
            )
        }
        Spacer(Modifier.height(26.dp))
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No sessions yet", color = Color(0xFF555555), fontSize = 18.sp)
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 17.dp, end = 17.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(sessions) { s ->
                Surface(shape = RoundedCornerShape(22.dp), color = Panel, modifier = Modifier.fillMaxWidth().clickable { onSelect(s) }) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        GameIcon(s.appName, size = 88, fontSize = 14, radius = 20)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.appName, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(7.dp))
                            Text("${s.date.take(10)} · ${comma(s.avgFps, 2)} · ${comma(s.avgPowerW, 2)}W", color = MutedC, fontSize = 15.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(fmtDur(s.durationSec), color = MutedC, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(items: List<Pair<@Composable () -> Unit, Pair<String, String>>>) {
    Surface(shape = RoundedCornerShape(24.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 28.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            items.forEach { (icon, pair) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    icon()
                    Spacer(Modifier.height(11.dp))
                    Text(pair.first, color = LabelC, fontSize = 16.sp)
                    Spacer(Modifier.height(9.dp))
                    Text(pair.second, color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════
// DETAIL VIEW
// ═══════════════════════════════════════════════
@Composable
private fun SessionDetailView(s: SessionEntry, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(s.appName, onBack = onBack, actions = {
            Icon(Icons.Outlined.Tune, "Filter", tint = Color(0xFFBBBBBB), modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(18.dp))
            Icon(Icons.Outlined.Share, "Share", tint = Color(0xFFBBBBBB), modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(18.dp))
            Icon(Icons.Outlined.CloudDownload, "Export", tint = Color(0xFFBBBBBB), modifier = Modifier.size(26.dp))
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 17.dp, bottom = 40.dp)) {
            DeviceCard(
                listOf(ChipIcon(LabelC) to ("Platform" to Build.BOARD),
                    PhoneIcon(LabelC) to ("Model" to Build.MODEL),
                    AndroidIcon(Color(0xFF8BD25A)) to ("OS" to "Android ${Build.VERSION.RELEASE}"),
                    { Text("◉", color = Color(0xFFFF7756), fontSize = 40.sp, lineHeight = 40.sp) } to ("Profile" to "###"))
            )
            Spacer(Modifier.height(14.dp))
            // Note placeholder
            Surface(shape = RoundedCornerShape(22.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
                Text("Note not set, click here to enter", color = Color(0xFF999999), fontSize = 20.sp,
                    modifier = Modifier.padding(25.dp))
            }
            Spacer(Modifier.height(28.dp))
            StatsCard(s)
            ChartCard4("FPS", titleRight = "Temperature(°C)", fields = listOf(
                "FPS" to S_FPS, "TEMP(°C)" to S_TEMP, "CPU(%)" to Color(0xFFA35AA2), "GPU(%)" to Color(0xFF6BC9EA)
            )) {
                FpsCanvas(listOf(s.chartData.fps to S_FPS, s.chartData.temp to S_TEMP), 0f, 90f)
            }
            ChartCard4("Frame Time(ms)", fields = listOf("MAX: 207ms" to null)) {
                FpsCanvas(listOf(s.chartData.frameTime to Color(0xFF87CBED)), 0f, 110f, bar = true)
            }
            ChartCard4("CPU Usage(%)", fields = listOf(
                "CPU 0~3" to S_CPU03, "CPU 4~6" to S_CPU46, "CPU 7" to S_CPU7
            )) {
                FpsCanvas(listOf(s.chartData.cpu03 to S_CPU03, s.chartData.cpu46 to S_CPU46, s.chartData.cpu7 to S_CPU7), 0f, 100f)
            }
            ChartCard4("CPU Frequency(MHz)", fields = listOf(
                "CPU 0~3" to S_CPU03, "CPU 4~6" to S_CPU46, "CPU 7" to S_CPU7
            )) {
                FpsCanvas(listOf(s.chartData.cpu03 to S_CPU03, s.chartData.cpu46 to S_CPU46, s.chartData.cpu7 to S_CPU7), 300f, 3000f)
            }
            ChartCard4("CPU Cycles(M) / CPU Temperature(°C)", fields = listOf(
                "CPU 0~3" to S_CPU03, "CPU 4~6" to S_CPU46, "CPU 7" to S_CPU7, "TEMP(°C)" to S_TEMP
            )) {
                FpsCanvas(listOf(
                    s.chartData.cpu03.map { it * 16f } to S_CPU03,
                    s.chartData.cpu46.map { it * 20f } to S_CPU46,
                    s.chartData.cpu7.map { it * 24f } to S_CPU7,
                    s.chartData.temp to S_TEMP
                ), 0f, 2900f)
            }
            ChartCard4("GPU Frequency(MHz) / Usage(%)", fields = listOf(
                "Frequency(MHz)" to S_GF, "Usage(%)" to S_GU
            )) {
                FpsCanvas(listOf(s.chartData.gpuFreq to S_GF, s.chartData.gpuUsage to S_GU), 0f, 600f)
            }
            ChartCard4("DDR(MHz | Mbps)", fields = emptyList()) {
                FpsCanvas(listOf(s.chartData.ddr to S_DDR), 0f, 6410f)
            }
            ChartCard4("Power(W) / Capacity %", fields = listOf(
                "MAX: 6,28W" to null, "MIN: 2,01W" to null, "AVG: 4,50W" to null
            )) {
                FpsCanvas(listOf(
                    s.chartData.powerW to S_PWR,
                    s.chartData.capacity.map { it / 10f } to S_CAP
                ), 0f, 7f)
            }
            ChartCard4("CPU Temperature(°C)", fields = listOf(
                "MAX: 82,5°C" to null, "MIN: 51,0°C" to null, "AVG: 68,7°C" to null
            )) {
                FpsCanvas(listOf(s.chartData.temp to S_TEMP), 0f, 100f)
            }
        }
    }
}

@Composable
private fun StatsCard(s: SessionEntry) {
    Surface(shape = RoundedCornerShape(22.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 25.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameIcon(s.appName, size = 45, fontSize = 8, radius = 12)
                Spacer(Modifier.width(12.dp))
                Text(s.date, color = Color(0xFFD9D9D9), fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("${s.appName}(${s.version})", color = Color(0xFF969696), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("crop: ${s.crop}", color = Color(0xFF969696), fontSize = 15.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                StatCell("MAX", comma(s.maxFps), "FPS", Modifier.weight(1f))
                StatCell("MIN", comma(s.minFps), "FPS", Modifier.weight(1f))
                StatCell("AVG", comma(s.avgFps, 2), "FPS", Modifier.weight(1f))
                StatCell("VARIANCE", comma(s.variance), "FPS", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                StatCell("≥45FPS", comma(s.smoothPct, 1) + "%", "Smoothness", Modifier.weight(1f))
                StatCell("5% Low", comma(s.low5Pct), "FPS", Modifier.weight(1f))
                StatCell("MAX", comma(s.peakTemp), "Temperature", Modifier.weight(1f))
                StatCell("AVG", comma(s.avgPowerW, 2), "Power(W)", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.padding(vertical = 8.dp)) {
        Text(label, color = Color(0xFF969696), fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp))
        Text(value, color = StatBlue, fontSize = 37.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(unit, color = Color(0xFF9B9B9B), fontSize = 15.sp)
    }
}

// ═══════════════════════════════════════════════
// Chart card + canvas (FPS Stats style, single left axis)
// ═══════════════════════════════════════════════
@Composable
private fun ChartCard4(title: String, titleRight: String? = null, fields: List<Pair<String, Color?>>, canvas: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = Panel, modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, color = ChartTitleC, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (titleRight != null) Text(titleRight, color = ChartTitleC, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(7.dp))
            canvas()
            if (fields.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.Center, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    fields.forEach { (name, clr) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                            if (clr != null) {
                                Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(clr))
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(name, color = LegendC, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun FpsCanvas(series: List<Pair<List<Float>, Color>>, yMin: Float, yMax: Float, bar: Boolean = false) {
    val tm = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 13.sp, color = AxisC)
    val times = listOf("0", "45s", "1m30s", "2m15s", "3m", "3m45s")
    Canvas(Modifier.fillMaxWidth().height(280.dp)) {
        val left = 44.dp.toPx(); val right = 18.dp.toPx(); val top = 14.dp.toPx(); val bottom = 32.dp.toPx()
        val w = size.width - left - right; val h = size.height - top - bottom
        drawRect(Panel, Offset.Zero, size)
        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx()))
        for (i in 0..5) {
            val y = top + h * i / 5f
            drawLine(GridC, Offset(left, y), Offset(left + w, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        }
        for (i in 0..5) {
            val v = (yMax - (yMax - yMin) * i / 5f).roundToInt().toString()
            val res = tm.measure(AnnotatedString(v), style = labelStyle)
            val y = top + h * i / 5f
            drawText(res, topLeft = Offset(left - 7.dp.toPx() - res.size.width, y - res.size.height / 2f))
        }
        times.forEachIndexed { i, t ->
            val res = tm.measure(AnnotatedString(t), style = labelStyle)
            val x = left + w * i / 5f
            drawText(res, topLeft = Offset(x - res.size.width / 2f, size.height - 8.dp.toPx() - res.size.height))
        }
        if (bar) {
            val a = series[0].first; val c = series[0].second
            if (a.size >= 2) {
                val n = a.size - 1
                val bw = maxOf(2.dp.toPx(), w / n - 1.5.dp.toPx())
                a.forEachIndexed { i, v ->
                    val x = left + (i / n.toFloat()) * w
                    val rawY = top + h - ((v - yMin) / (yMax - yMin)) * h
                    val y = rawY.coerceAtLeast(top)
                    drawRect(c, Offset(x - bw / 2, y), Size(bw, top + h - y))
                }
            }
        } else {
            series.forEach { (data, color) ->
                if (data.size < 2) return@forEach
                val step = w / (data.size - 1)
                val pts = data.mapIndexed { i, v -> Offset(left + i * step, top + h - ((v - yMin) / (yMax - yMin)) * h) }
                val path = Path().apply { moveTo(pts.first().x, pts.first().y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y) }
                drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

// ═══════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════
private fun fmtDur(sec: Long): String {
    val m = sec / 60; val s = sec % 60
    return if (m > 0) "${m}m${s}s" else "${s}s"
}

private fun loadSessions(): List<SessionEntry> = listOf(
    SessionEntry(1, "PUBG MOBILE", "com.tencent.ig", "2026-07-07 16:44:14", "4.4.0", "720×1600",
        avgFps = 83.28f, maxFps = 90.1f, minFps = 55.0f, variance = 101.3f,
        smoothPct = 100.0f, low5Pct = 57.5f, peakTemp = 40.9f, avgPowerW = 4.50f, durationSec = 226,
        chartData = ChartData(
            fps = listOf(83f, 85f, 60f, 90f, 84f, 78f, 90f, 65f, 88f, 83f, 82f, 86f, 60f, 89f, 83f, 78f, 90f, 85f, 62f, 83f,
                84f, 88f, 59f, 91f, 82f, 80f, 87f, 66f, 86f, 84f, 81f, 85f, 63f, 87f, 82f, 79f, 89f, 84f, 61f, 82f),
            temp = listOf(74f, 75f, 77f, 74f, 73f, 76f, 75f, 72f, 68f, 61f, 56f, 53f, 52f, 51f, 53f, 55f, 54f, 53f, 55f, 57f,
                58f, 56f, 54f, 61f, 68f, 72f, 70f, 66f, 73f, 74f, 75f, 76f, 77f, 78f, 76f, 75f, 73f, 77f, 76f, 77f),
            cpu03 = listOf(38f, 42f, 35f, 40f, 36f, 38f, 44f, 40f, 36f, 38f, 35f, 39f, 42f, 38f, 36f, 35f, 40f, 38f, 42f, 38f,
                37f, 41f, 34f, 39f, 35f, 37f, 43f, 39f, 35f, 37f, 34f, 38f, 41f, 37f, 35f, 34f, 39f, 37f, 41f, 37f),
            cpu46 = listOf(44f, 48f, 40f, 46f, 42f, 44f, 50f, 46f, 42f, 44f, 40f, 45f, 48f, 44f, 42f, 40f, 46f, 44f, 48f, 44f,
                43f, 47f, 39f, 45f, 41f, 43f, 49f, 45f, 41f, 43f, 39f, 44f, 47f, 43f, 41f, 39f, 45f, 43f, 47f, 43f),
            cpu7 = listOf(60f, 78f, 55f, 95f, 70f, 65f, 85f, 60f, 70f, 65f, 55f, 75f, 80f, 68f, 60f, 55f, 78f, 65f, 78f, 65f,
                58f, 80f, 53f, 92f, 72f, 63f, 87f, 58f, 68f, 64f, 52f, 73f, 82f, 67f, 58f, 54f, 76f, 64f, 80f, 63f),
            gpuFreq = List(24) { 520f },
            gpuUsage = listOf(82f, 89f, 87f, 88f, 72f, 48f, 41f, 38f, 45f, 52f, 59f, 55f, 67f, 71f, 61f, 63f, 76f, 74f, 70f, 79f,
                80f, 71f, 87f, 85f, 50f, 55f, 60f, 52f, 48f, 65f, 70f, 58f, 62f, 75f, 68f, 72f, 80f, 66f, 74f, 78f),
            ddr = listOf(6410f, 6410f, 6410f, 6410f, 3110f, 3110f, 4192f, 4192f, 4192f, 3418f, 4192f, 5490f, 5490f, 6410f, 6410f, 6410f,
                4192f, 5490f, 6410f, 6410f, 6410f, 6410f, 4192f, 6410f),
            powerW = listOf(5.2f, 5.4f, 5.5f, 5.2f, 5.4f, 5.6f, 5.5f, 2.7f, 2.4f, 2.8f, 2.6f, 2.9f, 3.1f, 3.3f, 3.0f, 3.2f, 3.5f, 5.4f, 4.0f, 5.5f,
                5.8f, 5.3f, 5.6f, 6.1f, 5.0f, 5.3f, 5.7f, 5.3f, 5.4f, 5.7f, 5.4f, 5.6f),
            capacity = listOf(58f, 58f, 58f, 58f, 58f, 58f, 58f, 58f, 58f, 58f, 58f, 58f, 58f, 58f, 58f, 58f, 58f, 58f, 57.5f, 57.5f,
                57.5f, 57.5f, 57f, 57f),
            frameTime = listOf(16f, 18f, 207f, 15f, 17f, 16f, 18f, 207f, 15f, 16f, 17f, 16f, 207f, 15f, 16f, 17f, 16f, 18f, 15f, 16f,
                17f, 19f, 207f, 16f, 18f, 17f, 19f, 207f, 16f, 17f, 18f, 17f, 207f, 16f, 17f, 18f, 17f, 19f, 16f, 17f),
        )),
)