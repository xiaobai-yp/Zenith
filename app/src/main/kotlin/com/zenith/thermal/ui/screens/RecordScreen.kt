package com.zenith.thermal.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.zenith.thermal.ui.theme.ZenithBg
import com.zenith.thermal.ui.theme.ZenithMuted2
import com.zenith.thermal.ui.theme.ZenithText
import com.zenith.thermal.FloatingHudService
import com.zenith.thermal.ZenithDaemonClient
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlin.math.roundToInt

// ═══════════════════════════════════════════════
// Palette v15 — user-approved (Scene order, full ticks)
// ═══════════════════════════════════════════════
private val Bg = Color(0xFF171717)          // scene body bg
private val Panel = Color(0xFF090909)       // scene card bg
private val Divider = Color(0xFF383838)     // scene grid bg (reuse for divider)
private val Divider2 = Color(0xFF2A2A2E)    // preview divider (session-row border)
private val Panel2 = Color(0xFF1C1C1E)      // preview card bg (#1c1c1e)
private val Ink = Color(0xFFf4f4f4)         // scene text
private val Muted = Color(0xFF8e8e93)       // scene muted (stats app text)
private val Dim = Color(0xFF969696)         // scene dim (label text)
private val Faint = Color(0xFF777777)       // scene faint (axis labels)
private val AxisC = Color(0xFF777777)       // scene axis label color
private val StatBlue = Color(0xFF3180FF)    // scene stat value (biru)
private val Green = Color(0xFF76C442)       // smoothness (tetap)
private val Orange = Color(0xFFFF7756)      // profile icon (scene)
private val TitleC = Color(0xFFf4f4f4)      // scene title text
private val LegendC = Color(0xFF8d8d8d)     // scene legend text
// FPS chart legend colors (scene exact — ini warna DOT di legend, bukan line color)
private val S_CPU_PCT = Color(0xFFA35AA2)   // CPU(%) dot legend — ungu muda
private val S_GPU_PCT = Color(0xFF6BC9EA)   // GPU(%) dot legend — biru muda
private val S_FT = Color(0xFF87CBED)        // FrameTime line+fill — biru muda (scene exact)
private val GridC = Color(0xFF383838)       // scene grid line
// Series colors (scene exact)
private val S_FPS = Color(0xFF828282)       // FPS line, abu-abu
private val S_TEMP = Color(0xFFB06B2C)      // Temperature (FPS chart), oranye cokelat
private val S_TEMP2 = Color(0xFF78C94A)     // Temperature (CPU cycles chart), hijau
private val S_CPU03 = Color(0xFF8E62C7)     // CPU 0~3, ungu
private val S_CPU46 = Color(0xFF36C6C4)     // CPU 4~6, cyan
private val S_CPU7 = Color(0xFFEE923E)      // CPU 7, oranye lembayung
private val S_GF = Color(0xFF8DC9E8)        // GPU Freq, biru muda
private val S_GU = Color(0xFF2D6FFF)        // GPU Usage, biru
private val S_DDR = Color(0xFF90D4F3)       // DDR, biru langit
private val S_PWR = Color(0xFF2D6FFF)       // Power, biru
private val S_CAP = Color(0xFF8DC9E8)       // Capacity, biru muda
private val S_TEMPL = Color(0xFF8DC9E8)     // CPU Temperature chart, biru muda

internal data class SessionEntry(
    val id: Long, val appName: String, val appPkg: String,
    val date: String, val version: String, val crop: String,
    val avgFps: Float, val maxFps: Float, val minFps: Float,
    val variance: Float, val smoothPct: Float, val low5Pct: Float,
    val peakTemp: Float, val avgPowerW: Float, val durationSec: Long,
    val chartData: ChartData = ChartData()
)
internal data class ChartData(
    val fps: List<Float> = emptyList(), val temp: List<Float> = emptyList(),
    val cpu03: List<Float> = emptyList(), val cpu46: List<Float> = emptyList(), val cpu7: List<Float> = emptyList(),
    val cpu0: List<Float> = emptyList(), val cpu1: List<Float> = emptyList(), val cpu2: List<Float> = emptyList(), val cpu3: List<Float> = emptyList(),
    val cpu4: List<Float> = emptyList(), val cpu5: List<Float> = emptyList(), val cpu6: List<Float> = emptyList(), val cpu7b: List<Float> = emptyList(),
    val gpuFreq: List<Float> = emptyList(), val gpuUsage: List<Float> = emptyList(),
    val ddr: List<Float> = emptyList(), val powerW: List<Float> = emptyList(), val capacity: List<Float> = emptyList(),
    val frameTime: List<Float> = emptyList(),
    val jank: List<Float> = emptyList(),
    val bigJank: List<Float> = emptyList(),
    val maxFrameTime: List<Float> = emptyList(),
    val cpuFreq0: List<Float> = emptyList(), val cpuFreq1: List<Float> = emptyList(), val cpuFreq2: List<Float> = emptyList(), val cpuFreq3: List<Float> = emptyList(),
    val cpuFreq4: List<Float> = emptyList(), val cpuFreq5: List<Float> = emptyList(), val cpuFreq6: List<Float> = emptyList(), val cpuFreq7: List<Float> = emptyList(),
    val cpuCyc0: List<Float> = emptyList(), val cpuCyc1: List<Float> = emptyList(), val cpuCyc2: List<Float> = emptyList(), val cpuCyc3: List<Float> = emptyList(),
    val cpuCyc4: List<Float> = emptyList(), val cpuCyc5: List<Float> = emptyList(), val cpuCyc6: List<Float> = emptyList(), val cpuCyc7: List<Float> = emptyList(),
    val voltage: List<Float> = emptyList(), val current: List<Float> = emptyList(), val battTemp: List<Float> = emptyList(),
    val threadPercent: List<Float> = emptyList(),
)
private enum class RecordView { LIST, DETAIL }

// ═══════════════════════════════════════════════
// Root
// ═══════════════════════════════════════════════

/** Filenames deleted — persisted to SharedPreferences so they survive process death */
private fun getDeletedPrefs(ctx: android.content.Context): MutableSet<String> =
    ctx.getSharedPreferences("zenith_record", 0).getStringSet("deleted_files", emptySet())!!.toMutableSet()

private fun markDeleted(ctx: android.content.Context, vararg names: String) {
    val prefs = ctx.getSharedPreferences("zenith_record", 0)
    val set = prefs.getStringSet("deleted_files", emptySet())!!.toMutableSet()
    set.addAll(names)
    prefs.edit().putStringSet("deleted_files", set).apply()
}

private fun isDeleted(ctx: android.content.Context, name: String): Boolean =
    ctx.getSharedPreferences("zenith_record", 0).getStringSet("deleted_files", emptySet())?.contains(name) == true

@Composable
fun RecordScreen() {
    val ctx = LocalContext.current
    var view by remember { mutableStateOf(RecordView.LIST) }
    var selected by remember { mutableStateOf<SessionEntry?>(null) }
    var sessions by remember { mutableStateOf(loadSessions(ctx)) }

    // Back gesture: DETAIL → LIST instead of leaving RecordScreen
    BackHandler(enabled = view == RecordView.DETAIL) {
        view = RecordView.LIST
        selected = null
    }

    // Periodically refresh sessions (detects new CSV files from bench recordings)
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(3000)
            sessions = loadSessions(ctx)
        }
    }

    // Scoped storage (API 30+): ask once per install, poll until granted, then reload
    LaunchedEffect(Unit) {
        val prefs = ctx.getSharedPreferences("zenith_record", 0)
        if (Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            if (!prefs.getBoolean("asked_all_files", false)) {
                prefs.edit().putBoolean("asked_all_files", true).apply()
                try {
                    val i = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:${ctx.packageName}")
                    )
                    ctx.startActivity(i)
                } catch (e: Exception) {
                    try {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (e2: Exception) { /* ignore */ }
                }
            }
        }
        // wait until granted (user returns from settings), then re-scan
        while (Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            kotlinx.coroutines.delay(2000)
        }
        if (Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()) {
            sessions = loadSessions(ctx)
        }
    }
    AnimatedContent(view, label = "rt") { cur ->
        when (cur) {
            RecordView.LIST -> SessionListView(sessions, { selected = it; view = RecordView.DETAIL },
                onDeleteAll = { deleteAllSessions(ctx); sessions = emptyList() },
                onDeleteSession = { s -> deleteSessionFile(s, ctx); sessions = sessions.filter { it.date != s.date } })
            RecordView.DETAIL -> selected?.let { SessionDetailView(it) { view = RecordView.LIST } }
        }
    }
}

// ═══════════════════════════════════════════════
// Shared widgets
// ═══════════════════════════════════════════════
@Composable
private fun TopBar(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    if (onBack == null && subtitle != null) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 18.dp, top = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = ZenithText, fontSize = 36.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = ZenithMuted2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            actions()
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 18.dp, top = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(22.dp)).clickable(enabled = onBack != null) { onBack?.invoke() }, contentAlignment = Alignment.Center) {
            Text("←", color = Color.White, fontSize = 24.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.width(14.dp))
        Text(title, color = ZenithText, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        actions()
    }
}

@Composable
private fun ChipIcon(tint: Color, side: Int = 46) {
    Canvas(Modifier.size(side.dp)) {
        val s = size.width; val b = s * 0.12f
        drawRoundRect(tint, Offset(b, b * 1.6f), Size(s - 2 * b, s - 3.2f * b), CornerRadius(4.dp.toPx()))
        val pin = b * 0.9f
        drawLine(tint, Offset(s / 2 - pin * 2, 0f), Offset(s / 2 - pin * 2, b), strokeWidth = b * 0.55f)
        drawLine(tint, Offset(s / 2 + pin * 2, 0f), Offset(s / 2 + pin * 2, b), strokeWidth = b * 0.55f)
        drawLine(tint, Offset(s / 2 - pin * 2, s - b), Offset(s / 2 - pin * 2, s), strokeWidth = b * 0.55f)
        drawLine(tint, Offset(s / 2 + pin * 2, s - b), Offset(s / 2 + pin * 2, s), strokeWidth = b * 0.55f)
        drawRect(tint, Offset(b, s / 2 - b * 0.35f), Size(s - 2 * b, b * 0.7f))
    }
}

@Composable
private fun PhoneIcon(tint: Color, side: Int = 46) {
    Canvas(Modifier.size(side.dp)) {
        val w = size.width * 0.6f; val h = size.height * 0.78f
        val x = (size.width - w) / 2; val y = (size.height - h) / 2
        drawRoundRect(tint, Offset(x, y), Size(w, h), CornerRadius(4.dp.toPx()), style = Stroke(2.2.dp.toPx()))
        drawCircle(tint, radius = 1.6.dp.toPx(), center = Offset(size.width / 2, y + h - 5.dp.toPx()))
    }
}

@Composable
private fun AndroidIcon(tint: Color, side: Int = 46) {
    Icon(Icons.Outlined.Android, null, tint = tint, modifier = Modifier.size(side.dp))
}

private fun resolveAppLabel(ctx: android.content.Context?, pkg: String): String {
    if (ctx == null || pkg.isEmpty()) return pkg
    return try {
        val appInfo = ctx.packageManager.getApplicationInfo(pkg, 0)
        ctx.packageManager.getApplicationLabel(appInfo).toString()
    } catch (_: Exception) { pkg }
}

@Composable
private fun GameIcon(name: String, pkg: String = "", size: Int = 88, fontSize: Int = 14, radius: Int = 20) {
    val ctx = LocalContext.current
    val icon = remember(pkg) {
        if (pkg.isNotEmpty()) {
            try { ctx.packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
        } else null
    }
    if (icon != null) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.graphics.painter.BitmapPainter(
                icon.toBitmap().asImageBitmap()
            ), contentDescription = name,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(radius.dp))
        )
    } else {
        Box(Modifier.size(size.dp).clip(RoundedCornerShape(radius.dp)).background(Brush.linearGradient(listOf(Color(0xFF7D8C9F), Color(0xFF243C53)))),
            contentAlignment = Alignment.Center) {
            Text(name.take(4), color = Color.White, fontSize = fontSize.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.background(Color(0x11111111)).padding(horizontal = 3.dp, vertical = 1.dp))
        }
    }
}

private fun comma(v: Float, dec: Int = 1): String = String.format("%.${dec}f", v).replace('.', ',')

private fun exportCsv(s: SessionEntry?, ctx: android.content.Context) {
    if (s == null) return
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.US)
    val ts = fmt.format(java.util.Date())
    val name = "${s.appName} $ts.csv"
    val sb = StringBuilder()
    
    // Scene-style header (48 columns)
    sb.appendLine("FPS,JANK,BigJANK,Max FrameTime(ms),CPU(%),CPU0(%),CPU1(%),CPU2(%),CPU3(%),CPU4(%),CPU5(%),CPU6(%),CPU7(%),CPU0(MHz),CPU1(MHz),CPU2(MHz),CPU3(MHz),CPU4(MHz),CPU5(MHz),CPU6(MHz),CPU7(MHz),CPU0(M Cycles),CPU1(M Cycles),CPU2(M Cycles),CPU3(M Cycles),CPU4(M Cycles),CPU5(M Cycles),CPU6(M Cycles),CPU7(M Cycles),CPU(℃),DDR(Mbps),GPU(%),GPU(MHz),Battery(%),Battery(℃),Current(mA),Battery(volt),Power(mW),Thread-80(%),Thread-80(cpus),Thread-77(%),Thread-77(cpus),Thread-85(%),Thread-85(cpus),Thread-79(%),Thread-79(cpus),Thread-90(%),Thread-90(cpus)")
    
    val n = s.chartData.fps.size
    val d2 = s.chartData
    fun g(l: List<Float>, i: Int): String = if (i < l.size) comma(l[i]) else "0"
    fun gi(l: List<Float>, i: Int): String = if (i < l.size) l[i].toInt().toString() else "0"
    
    for (i in 0 until n) {
        // FPS
        sb.append(gi(d2.fps, i)).append(',')
        // JANK, BigJANK, Max FrameTime
        sb.append(gi(d2.jank, i)).append(',')
        sb.append(gi(d2.bigJank, i)).append(',')
        sb.append(gi(d2.maxFrameTime, i)).append(',')
        // CPU total (use cpu03 as total)
        sb.append(g(d2.cpu03, i)).append(',')
        // CPU per-core
        sb.append(g(d2.cpu0, i)).append(',')
        sb.append(g(d2.cpu1, i)).append(',')
        sb.append(g(d2.cpu2, i)).append(',')
        sb.append(g(d2.cpu3, i)).append(',')
        sb.append(g(d2.cpu4, i)).append(',')
        sb.append(g(d2.cpu5, i)).append(',')
        sb.append(g(d2.cpu6, i)).append(',')
        sb.append(g(d2.cpu7b, i)).append(',')
        // CPU freq per-core
        sb.append(g(d2.cpuFreq0, i)).append(',')
        sb.append(g(d2.cpuFreq1, i)).append(',')
        sb.append(g(d2.cpuFreq2, i)).append(',')
        sb.append(g(d2.cpuFreq3, i)).append(',')
        sb.append(g(d2.cpuFreq4, i)).append(',')
        sb.append(g(d2.cpuFreq5, i)).append(',')
        sb.append(g(d2.cpuFreq6, i)).append(',')
        sb.append(g(d2.cpuFreq7, i)).append(',')
        // CPU cycles per-core
        sb.append(gi(d2.cpuCyc0, i)).append(',')
        sb.append(gi(d2.cpuCyc1, i)).append(',')
        sb.append(gi(d2.cpuCyc2, i)).append(',')
        sb.append(gi(d2.cpuCyc3, i)).append(',')
        sb.append(gi(d2.cpuCyc4, i)).append(',')
        sb.append(gi(d2.cpuCyc5, i)).append(',')
        sb.append(gi(d2.cpuCyc6, i)).append(',')
        sb.append(gi(d2.cpuCyc7, i)).append(',')
        // CPU temp
        sb.append(g(d2.temp, i)).append(',')
        // DDR
        sb.append(g(d2.ddr, i)).append(',')
        // GPU
        sb.append(g(d2.gpuUsage, i)).append(',')
        sb.append(g(d2.gpuFreq, i)).append(',')
        // Battery
        sb.append(g(d2.capacity, i)).append(',')
        sb.append(g(d2.battTemp, i)).append(',')
        sb.append(g(d2.current, i)).append(',')
        sb.append(g(d2.voltage, i)).append(',')
        // Power
        sb.append(g(d2.powerW, i)).append(',')
        // Thread stats (placeholder)
        sb.append("0,0,0,0,0,0,0,0,0,0")
        sb.appendLine()
    }
    
    java.io.File("/sdcard", name).writeText(sb.toString())
    android.widget.Toast.makeText(ctx, "Saved to /sdcard/$name (Scene format)", android.widget.Toast.LENGTH_SHORT).show()
}

private fun deleteAllSessions(ctx: android.content.Context): Int {
    // Also delete daemon sessions
    if (ZenithDaemonClient.isConnected) {
        ZenithDaemonClient.sendCommand("delete_all_sessions")
    }
    val regex = Regex("""^(.+) (\d{4}-\d{2}-\d{2} \d{2}-\d{2}-\d{2})\.csv$""")
    var count = 0
    val names = mutableListOf<String>()
    val toDelete = mutableListOf<String>()
    // Collect from /sdcard/
    val sdcard = File("/sdcard")
    if (sdcard.exists()) {
        val files = sdcard.listFiles { f -> f.isFile && regex.matches(f.name) }
        files?.forEach { names.add(it.name); toDelete.add(it.absolutePath); if (it.delete()) count++ }
    }
    // Collect from filesDir/bench_sessions/
    val benchDir = File(ctx.filesDir, "bench_sessions")
    if (benchDir.exists()) {
        val files = benchDir.listFiles { f -> f.isFile && regex.matches(f.name) }
        files?.forEach { names.add(it.name); toDelete.add(it.absolutePath); if (it.delete()) count++ }
    }
    // Fallback: su -c rm for files that couldn't be deleted
    val remaining = toDelete.filter { File(it).exists() }
    if (remaining.isNotEmpty()) {
        try {
            val cmd = remaining.joinToString(" && ") { "rm -f \"$it\"" }
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
            count = names.size
        } catch (_: Exception) {}
    }
    // Persist ALL names (even if physical delete failed) so scan skips them
    markDeleted(ctx, *names.toTypedArray())
    android.widget.Toast.makeText(ctx, "Deleted $count session(s)", android.widget.Toast.LENGTH_SHORT).show()
    return count
}

private fun deleteSessionFile(s: SessionEntry, ctx: android.content.Context) {
    val fileName = "${s.appName} ${s.date.replace(':', '-')}.csv"
    markDeleted(ctx, fileName)
    val paths = mutableListOf<String>()
    val f1 = File("/sdcard", fileName)
    if (f1.exists()) paths.add(f1.absolutePath)
    val f2 = File(ctx.filesDir, "bench_sessions/$fileName")
    if (f2.exists()) paths.add(f2.absolutePath)
    if (paths.isNotEmpty()) {
        try {
            val cmd = paths.joinToString(" && ") { "rm -f \"$it\"" }
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
        } catch (_: Exception) {}
    }
    android.widget.Toast.makeText(ctx, "Deleted ${s.appName} session", android.widget.Toast.LENGTH_SHORT).show()
}

// ═══════════════════════════════════════════════
// LIST VIEW
// ═══════════════════════════════════════════════
@Composable
private fun SessionListView(sessions: List<SessionEntry>, onSelect: (SessionEntry) -> Unit, onDeleteAll: () -> Unit = {}, onDeleteSession: (SessionEntry) -> Unit = {}) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar("Record", subtitle = "Session telemetry", actions = {
            Box(Modifier.clickable { onDeleteAll() }) { Icon(Icons.Outlined.DeleteForever, "Delete All", tint = Color(0xFFEF4444), modifier = Modifier.size(22.dp)) }
        })
        Column(Modifier.padding(horizontal = 17.dp)) {
            DeviceCard(
                listOf(
                    Pair<@Composable () -> Unit, Pair<String, String>>({ ChipIcon(Color(0xFF7B6FEF)) }, "Platform" to Build.BOARD),
                    Pair<@Composable () -> Unit, Pair<String, String>>({ PhoneIcon(Color(0xFF5B9CF6)) }, "Model" to Build.MODEL),
                    Pair<@Composable () -> Unit, Pair<String, String>>({ AndroidIcon(Color(0xFF76C442)) }, "OS" to "Android ${Build.VERSION.RELEASE}"))
            )
        }
        Spacer(Modifier.height(26.dp))
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No sessions yet", color = Faint, fontSize = 18.sp)
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 17.dp, end = 17.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(sessions) { s ->
                Surface(shape = RoundedCornerShape(14.dp), color = Panel, modifier = Modifier.fillMaxWidth().clickable { onSelect(s) }) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        GameIcon(s.appName, s.appPkg, size = 42, fontSize = 7, radius = 10)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(resolveAppLabel(ctx, s.appPkg).ifEmpty { s.appName }, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(3.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.date.take(10), color = Dim, fontSize = 11.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(comma(s.avgFps, 2), color = StatBlue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(8.dp))
                                Text("${comma(s.avgPowerW, 2)}W", color = Dim, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(fmtDur(s.durationSec), color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onDeleteSession(s) }) { Icon(Icons.Outlined.Delete, "Delete", tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(items: List<Pair<@Composable () -> Unit, Pair<String, String>>>) {
    Surface(shape = RoundedCornerShape(16.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            items.forEachIndexed { i, (icon, pair) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    icon()
                    Spacer(Modifier.height(8.dp))
                    Text(pair.first, color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(pair.second, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (i < items.size - 1) {
                    Box(Modifier.width(1.dp).height(40.dp).background(Divider))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════
// DETAIL VIEW
// ═══════════════════════════════════════════════

@Composable
private fun FilterDropdown(
    hidden: Set<String>, onToggle: (Set<String>) -> Unit, expanded: Boolean, onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf("Info", "Jank", "Frame Time", "Power", "DDR", "GPU", "CPU temperature")
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = modifier.background(Color(0xF50C0C18), RoundedCornerShape(14.dp))) {
        Text("Hide/Show Cards", color = Faint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        items.forEach { itm ->
            val isHidden = itm in hidden
            DropdownMenuItem(text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isHidden) "☐" else "☑", color = if (isHidden) Faint else StatBlue, fontSize = 16.sp, modifier = Modifier.width(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(itm, color = if (isHidden) Faint else Ink, fontSize = 15.sp)
                }
            }, onClick = { onToggle(if (isHidden) hidden - itm else hidden + itm) })
        }
    }
}

@Composable
private fun SessionDetailView(s: SessionEntry, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var showFilter by remember { mutableStateOf(false) }
    var hiddenCards by remember { mutableStateOf(setOf<String>()) }

    val totalJank = s.chartData.jank.sum().toInt()
    val totalBig = s.chartData.bigJank.sum().toInt()
    val maxFt = if (s.chartData.frameTime.isNotEmpty()) s.chartData.frameTime.max().toInt() else 0
    val pMax = if (s.chartData.powerW.isNotEmpty()) s.chartData.powerW.max() else 0f
    val pMin = if (s.chartData.powerW.isNotEmpty()) s.chartData.powerW.min() else 0f
    val pAvg = if (s.chartData.powerW.isNotEmpty()) s.chartData.powerW.average() else 0.0
    val tMax = if (s.chartData.temp.isNotEmpty()) s.chartData.temp.max() else 0f
    val tMin = if (s.chartData.temp.isNotEmpty()) s.chartData.temp.min() else 0f
    val tAvg = if (s.chartData.temp.isNotEmpty()) s.chartData.temp.average() else 0.0

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(resolveAppLabel(ctx, s.appPkg).ifEmpty { s.appName }, onBack = onBack, actions = {
            Box(Modifier.clickable { showFilter = !showFilter }) { Icon(Icons.Outlined.FilterList, "Filter", tint = Faint, modifier = Modifier.size(22.dp)) }
            FilterDropdown(hiddenCards, { hiddenCards = it }, expanded = showFilter, onDismiss = { showFilter = false })
            Spacer(Modifier.width(18.dp))
            Box(Modifier.clickable { exportCsv(s, ctx) }) { Icon(Icons.Outlined.FileDownload, "Export", tint = Faint, modifier = Modifier.size(22.dp)) }
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 17.dp).padding(bottom = 40.dp)) {
            if ("Info" !in hiddenCards) {
                DeviceCard(
                    listOf(
                        Pair<@Composable () -> Unit, Pair<String, String>>({ ChipIcon(Color(0xFF7B6FEF)) }, "Platform" to Build.BOARD),
                        Pair<@Composable () -> Unit, Pair<String, String>>({ PhoneIcon(Color(0xFF5B9CF6)) }, "Model" to Build.MODEL),
                        Pair<@Composable () -> Unit, Pair<String, String>>({ AndroidIcon(Color(0xFF76C442)) }, "OS" to "Android ${Build.VERSION.RELEASE}"),
                        Pair<@Composable () -> Unit, Pair<String, String>>({ Text("◉", color = Orange, fontSize = 32.sp, lineHeight = 40.sp) }, "Profile" to "Default"))
                )
                Spacer(Modifier.height(12.dp))
                SessionStatsCard(s)
                Spacer(Modifier.height(12.dp))
            }
            // ── Charts (Scene order) ──
            if ("FPS" !in hiddenCards) ChartCard(title = "FPS", titleRight = "Temperature(°C)", rightColor = S_TEMP,
                legend = listOf(
                    "FPS" to S_FPS,
                    "TEMP(°C)" to S_TEMP,
                    "CPU(%)" to S_CPU_PCT,
                    "GPU(%)" to S_GPU_PCT
                ),
                spec = ChartSpec(
                    series = listOf(s.chartData.fps to S_FPS),
                    rightSeries = listOf(s.chartData.temp to S_TEMP),
                    yMin = 0f, yMax = 90f,
                    leftTicks = listOf("90", "60", "30", "0"),
                    rightTicks = listOf("45", "40"), rightMin = 35f, rightMax = 50f,
                    fillArea = true,
                    fillColor = S_FPS.copy(alpha = 0.15f),
                ))
            Spacer(Modifier.height(14.dp))
            if ("Frame Time" !in hiddenCards) ChartCard(title = "Frame Time (ms)",
                legend = emptyList(),
                sub = "MAX: ${maxFt}ms",
                spec = ChartSpec(
                    series = listOf(s.chartData.frameTime to S_FT),
                    yMin = 8f, yMax = 100f,
                    leftTicks = listOf("100", "91", "83", "75", "66", "58", "50", "41", "33", "25", "16", "8"),
                    fillArea = true,
                    fillColor = S_FT.copy(alpha = 0.15f),
                    lineWidth = 1.5.dp,
                ))
            Spacer(Modifier.height(14.dp))
            if ("CPU Usage" !in hiddenCards) ChartCard(title = "CPU Usage (%)", opts = true,
                legend = listOf("CPU 0~3" to S_CPU03, "CPU 4~6" to S_CPU46, "CPU 7" to S_CPU7),
                spec = ChartSpec(
                    series = listOf(
                        s.chartData.cpu03 to S_CPU03,
                        s.chartData.cpu46 to S_CPU46,
                        s.chartData.cpu7 to S_CPU7,
                    ),
                    yMin = 0f, yMax = 100f,
                    leftTicks = listOf("100", "90", "80", "70", "60", "50", "40", "30", "20", "10"),
                    fillArea = true,
                    fillColor = S_CPU03.copy(alpha = 0.15f),
                ))
            Spacer(Modifier.height(14.dp))
            if ("CPU Frequency" !in hiddenCards) ChartCard(title = "CPU Frequency (MHz)", opts = true,
                legend = listOf("CPU 0~3" to S_CPU03, "CPU 4~6" to S_CPU46, "CPU 7" to S_CPU7),
                spec = ChartSpec(
                    series = listOf(
                        s.chartData.cpuFreq0 to S_CPU03,
                        s.chartData.cpuFreq4 to S_CPU46,
                        s.chartData.cpuFreq7 to S_CPU7,
                    ),
                    yMin = 0f, yMax = 3000f,
                    leftTicks = listOf("2918", "2700", "2400", "2100", "1800", "1500", "1200", "900", "600", "300"),
                    fillArea = true,
                    fillColor = S_CPU03.copy(alpha = 0.15f),
                ))
            Spacer(Modifier.height(14.dp))
            if (s.chartData.cpuCyc0.isNotEmpty())
                ChartCard(title = "CPU Cycles (M)", titleRight = "CPU Temperature (°C)", rightColor = S_TEMP2,
                    legend = listOf("CPU 0~3" to S_CPU03, "CPU 4~6" to S_CPU46, "CPU 7" to S_CPU7, "TEMP(°C)" to S_TEMP2),
                    spec = ChartSpec(
                        series = listOf(
                            s.chartData.cpuCyc0 to S_CPU03,
                            s.chartData.cpuCyc4 to S_CPU46,
                        ),
                        rightSeries = listOf(s.chartData.temp to S_TEMP2),
                        yMin = 0f, yMax = 3000f,
                        leftTicks = listOf("2890", "2700", "2400", "2100", "1800", "1500", "1200", "900", "600", "300"),
                        rightTicks = listOf("100", "90", "80", "70", "60", "50", "40", "30", "20", "10"),
                        rightMin = 0f, rightMax = 100f,
                        fillArea = true,
                        fillColor = S_CPU03.copy(alpha = 0.15f),
                        lineWidth = 1.5.dp,
                    )
                )
            Spacer(Modifier.height(14.dp))
            if ("GPU" !in hiddenCards) ChartCard(title = "GPU Frequency (MHz)", titleRight = "Usage (%)", rightColor = S_GU,
                legend = listOf("GPU" to S_GF, "Usage (%)" to S_GU),
                spec = ChartSpec(
                    series = listOf(s.chartData.gpuFreq to S_GF),
                    rightSeries = listOf(s.chartData.gpuUsage to S_GU),
                    yMin = 0f, yMax = 600f,
                    leftTicks = listOf("600", "500", "400", "300", "200", "100"),
                    rightTicks = listOf("100", "90", "75", "50"), rightMin = 0f, rightMax = 100f,
                ))
            Spacer(Modifier.height(14.dp))
            if ("DDR" !in hiddenCards) ChartCard(title = "DDR (MHz | Mbps)",
                legend = listOf("DDR" to S_DDR),
                spec = ChartSpec(
                    series = listOf(s.chartData.ddr to S_DDR),
                    yMin = 0f, yMax = 6500f,
                    leftTicks = listOf("6410", "5479", "4192", "3418", "3110"),
                    fillArea = true,
                    fillColor = S_DDR.copy(alpha = 0.15f),
                ))
            Spacer(Modifier.height(14.dp))
            if ("Power" !in hiddenCards) ChartCard(title = "Power (W)", titleRight = "Capacity %", rightColor = S_CAP,
                legend = listOf("Power (W)" to S_PWR, "Capacity (%)" to S_CAP),
                sub = "MAX: ${comma(pMax, 2)}W  MIN: ${comma(pMin, 2)}W  AVG: ${comma(pAvg.toFloat(), 2)}W",
                spec = ChartSpec(
                    series = listOf(s.chartData.powerW to S_PWR),
                    rightSeries = listOf(s.chartData.capacity to S_CAP),
                    yMin = 0f, yMax = 7f,
                    leftTicks = listOf("7", "6", "5", "4", "3", "2", "1", "0"),
                    rightTicks = listOf("100", "80", "60", "40", "20"), rightMin = 0f, rightMax = 100f,
                    fillArea = true,
                    fillColor = S_PWR.copy(alpha = 0.15f),
                ))
            Spacer(Modifier.height(14.dp))
            if ("CPU Temperature" !in hiddenCards) ChartCard(title = "CPU Temperature (°C)",
                legend = listOf("Temperature" to S_TEMPL),
                sub = "MAX: ${comma(tMax)}°C  MIN: ${comma(tMin)}°C  AVG: ${comma(tAvg.toFloat())}°C",
                spec = ChartSpec(
                    series = listOf(s.chartData.temp to S_TEMPL),
                    yMin = 0f, yMax = 100f,
                    leftTicks = listOf("100", "90", "80", "70", "60", "50", "40", "30", "20", "10"),
                    fillArea = true,
                    fillColor = S_TEMPL.copy(alpha = 0.15f),
                ))
        }
    }
}

@Composable
private fun SessionStatsCard(s: SessionEntry) {
    val ctx = LocalContext.current
    Surface(
        shape = RoundedCornerShape(16.dp), color = Panel2,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // session-row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                GameIcon(s.appName, s.appPkg, size = 32, fontSize = 5, radius = 8)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(s.date, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    val label = resolveAppLabel(ctx, s.appPkg).ifEmpty { s.appName }
                    Text("$label (${s.version}) · crop: ${s.crop}", color = Color(0xFF666666), fontSize = 11.sp)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Divider2))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                StatCell("MAX", comma(s.maxFps), "FPS", Modifier.weight(1f))
                StatCell("MIN", comma(s.minFps), "FPS", Modifier.weight(1f))
                StatCell("AVG", comma(s.avgFps), "FPS", Modifier.weight(1f))
                StatCell("VARIANCE", comma(s.variance), "FPS", Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                StatCell("≥45FPS", comma(s.smoothPct, 1) + "%", "Smoothness", Modifier.weight(1f), valueColor = Green)
                StatCell("5% Low", comma(s.low5Pct), "FPS", Modifier.weight(1f))
                StatCell("MAX", comma(s.peakTemp), "Temperature", Modifier.weight(1f))
                StatCell("AVG", comma(s.avgPowerW, 2), "Power(W)", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, unit: String, modifier: Modifier = Modifier, valueColor: Color = StatBlue) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(label, color = Color(0xFF666666), fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = valueColor, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(unit, color = Color(0xFF555555), fontSize = 10.sp)
    }
}

// ═══════════════════════════════════════════════
// Chart card + canvas
// ═══════════════════════════════════════════════
private data class ChartSpec(
    val series: List<Pair<List<Float>, Color>> = emptyList(),
    val rightSeries: List<Pair<List<Float>, Color>> = emptyList(),
    val yMin: Float = 0f, val yMax: Float = 100f,
    val leftTicks: List<String> = listOf("100", "50", "0"),
    val rightTicks: List<String>? = null,
    val rightMin: Float = 0f, val rightMax: Float = 100f,
    val bar: Boolean = false,
    val dashed: Set<Int> = emptySet(),
    val height: Int = 220,
    val xLabels: List<String> = emptyList(),
    val fillArea: Boolean = false,
    val fillColor: Color? = null,
    val lineWidth: Dp = 1.8.dp
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartCard(
    title: String, titleRight: String? = null, rightColor: Color = S_TEMP,
    opts: Boolean = false,
    legend: List<Pair<String, Color>> = emptyList(),
    sub: String? = null,
    spec: ChartSpec,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Panel,
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = TitleC, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                when {
                    titleRight != null -> Text(titleRight, color = rightColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    opts -> Text("Chart Options ▸", color = Faint, fontSize = 12.sp)
                }
            }
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                ChartCanvas(spec)
            }
            Spacer(Modifier.height(8.dp))
            if (legend.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalArrangement = Arrangement.Center
                ) {
                    legend.forEach { (name, clr) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 7.dp)) {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(clr))
                            Spacer(Modifier.width(5.dp))
                            Text(name, color = LegendC, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (sub != null) {
                Text(sub, color = Color(0xFF777777), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun ChartCanvas(spec: ChartSpec) {
    val tm = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = AxisC)
    // Adaptive X-axis: use spec.xLabels or fallback
    val times = spec.xLabels.ifEmpty {
        val n = spec.series.firstOrNull()?.first?.size ?: 0
        if (n == 0) listOf("0") else generateAdaptiveXLabels(n)
    }
    val maxTicks = maxOf(spec.leftTicks.size, spec.rightTicks?.size ?: 0); val denG = (maxTicks - 1).coerceAtLeast(1); val denL = (spec.leftTicks.size - 1).coerceAtLeast(1); val denR = ((spec.rightTicks?.size ?: 1) - 1).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(spec.height.dp)) {
        val insetL = 22.dp.toPx(); val insetR = if (spec.rightTicks != null) 30.dp.toPx() else 22.dp.toPx()
        val top = 8.dp.toPx(); val bottom = 28.dp.toPx()
        val w = size.width - insetL - insetR; val h = size.height - top - bottom
        // grid (dashed) — horizontal + vertical
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
        // Horizontal grid lines (Y-axis)
        for (i in 0 until maxTicks) {
            val y = top + h * i / denG
            drawLine(GridC, Offset(insetL, y), Offset(insetL + w, y), strokeWidth = 1.2.dp.toPx(), pathEffect = dash)
        }
        // Vertical grid lines (X-axis) — one per x tick
        for (i in 0 until times.size) {
            val x = insetL + w * i / (times.size - 1)
            drawLine(GridC, Offset(x, top), Offset(x, top + h), strokeWidth = 1.2.dp.toPx(), pathEffect = dash)
        }
        // left y labels
        spec.leftTicks.forEachIndexed { i, v ->
            val res = tm.measure(AnnotatedString(v), style = labelStyle)
            val y = top + h * i / denL
            drawText(res, topLeft = Offset(insetL - res.size.width - 6.dp.toPx(), y - res.size.height / 2f))
        }
        // right y labels — positioned by value mapping (rightMin..rightMax) to same Y space
        spec.rightTicks?.forEachIndexed { i, v ->
            val res = tm.measure(AnnotatedString(v), style = labelStyle)
            val tickVal = v.replace(',', '.').toFloatOrNull() ?: 0f
            val yFrac = if (spec.rightMax > spec.rightMin) (1f - (tickVal - spec.rightMin) / (spec.rightMax - spec.rightMin)) else (i.toFloat() / denR)
            val y = top + h * yFrac.coerceIn(0f, 1f)
            drawText(res, topLeft = Offset(size.width - insetR + 6.dp.toPx(), y - res.size.height / 2f))
        }
        // x labels
        times.forEachIndexed { i, t ->
            val res = tm.measure(AnnotatedString(t), style = labelStyle)
            val x = insetL + w * i / (times.size - 1)
            drawText(res, topLeft = Offset(x - res.size.width / 2f, size.height - 6.dp.toPx() - res.size.height))
        }
        // data
        fun yOf(v: Float, min: Float, max: Float) = top + h - ((v - min) / (max - min)).coerceIn(0f, 1f) * h
        if (spec.bar) {
            val a = spec.series.firstOrNull()?.first ?: return@Canvas; val c = spec.series.first().second
            if (a.size >= 2) {
                val n = a.size - 1
                val bw = maxOf(2.dp.toPx(), w / n - 2.dp.toPx())
                a.forEachIndexed { i, v ->
                    val x = insetL + (i / n.toFloat()) * w
                    val y = yOf(v, spec.yMin, spec.yMax)
                    drawRect(c.copy(alpha = 0.7f), Offset(x - bw / 2, y), Size(bw, top + h - y))
                }
            }
        } else {
            spec.series.forEachIndexed { idx, (data, color) ->
                if (data.size < 2) return@forEachIndexed
                val step = w / (data.size - 1)
                val pts = data.mapIndexed { i, v -> Offset(insetL + i * step, yOf(v, spec.yMin, spec.yMax)) }
                val path = Path().apply { moveTo(pts.first().x, pts.first().y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y) }
                val style = if (idx in spec.dashed) Stroke(spec.lineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))) else Stroke(spec.lineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(path, color, style = style)
            }
            spec.rightSeries.forEach { (data, color) ->
                if (data.size < 2) return@forEach
                val step = w / (data.size - 1)
                val pts = data.mapIndexed { i, v -> Offset(insetL + i * step, yOf(v, spec.rightMin, spec.rightMax)) }
                val path = Path().apply { moveTo(pts.first().x, pts.first().y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y) }
                drawPath(path, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
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

private fun loadSessions(ctx: android.content.Context? = null): List<SessionEntry> {
    val daemonSessions = loadDaemonSessions()
    val csvSessions = parseCsvSessions(ctx)
    // Merge: daemon sessions first (authoritative), then CSV (legacy)
    val combined = (daemonSessions + csvSessions)
        .distinctBy { "${it.appPkg}_${it.date}" }
        .sortedByDescending { it.date }
    return combined
}

// ── CSV parsing from /sdcard/ ──
private fun parseCsvSessions(ctx: android.content.Context? = null): List<SessionEntry> {
    val regex = Regex("""^(.+) (\d{4}-\d{2}-\d{2} \d{2}-\d{2}-\d{2})\.csv$""")
    val allSessions = mutableListOf<SessionEntry>()

    // Scan /sdcard/
    val sdcard = java.io.File("/sdcard")
    if (sdcard.exists()) {
        sdcard.listFiles { f -> f.isFile && regex.matches(f.name) && (ctx == null || !isDeleted(ctx, f.name)) }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNullTo(allSessions) { parseCsvFile(it, regex, ctx) }
        }

        // Scan app filesDir/bench_sessions/
        ctx?.let {
            val benchDir = java.io.File(it.filesDir, "bench_sessions")
            if (benchDir.exists()) {
                benchDir.listFiles { f -> f.isFile && regex.matches(f.name) && !isDeleted(it, f.name) }
                    ?.sortedByDescending { it.lastModified() }
                    ?.mapNotNullTo(allSessions) { f -> parseCsvFile(f, regex, ctx) }
        }
    }

    return allSessions.sortedByDescending { it.date }
}

private fun parseCsvFile(file: File, regex: Regex, ctx: android.content.Context? = null): SessionEntry? {
    val match = regex.find(file.name) ?: return null
    val appName = match.groupValues[1]
    val rawDate = match.groupValues[2]
    val date = rawDate.let {
        val parts = it.split(" ")
        if (parts.size == 2) "${parts[0]} ${parts[1].replace('-', ':')}" else it
    }
    try {
        val lines = file.readLines()
        if (lines.size < 2) return null
        // Find header row: scan for line starting with known column names
        var headerIdx = -1
        var appPkg = ""
        for (i in lines.indices) {
            val l = lines[i].trim()
            if (l.startsWith("#PACKAGE:")) {
                appPkg = l.removePrefix("#PACKAGE:").trim()
                continue
            }
            // Scene format: starts with "FPS,JANK,..."
            // Zenith old format: starts with "t,FPS,..."
            if (l.startsWith("FPS,JANK") || l.startsWith("t,FPS")) {
                headerIdx = i
                break
            }
        }
        if (headerIdx < 0) return null
        val startLine = headerIdx
        // Resolve version + crop from PackageManager / display
        val version = if (ctx != null && appPkg.isNotEmpty()) {
            try {
                val pi = ctx.packageManager.getPackageInfo(appPkg, 0)
                @Suppress("DEPRECATION") pi.versionName ?: "—"
            } catch (_: Exception) { "—" }
        } else "—"
        val crop = if (ctx != null) {
            val dm = ctx.resources.displayMetrics
            "${dm.widthPixels}x${dm.heightPixels}"
        } else "—"
        val header = lines[startLine]
        val headerCols = header.split(",").map { it.trim() }
        val colMap = headerCols.mapIndexed { i, name -> name to i }.toMap()
        fun col(vararg names: String): Int? { for (n in names) { colMap[n]?.let { return it }; colMap[n.lowercase()]?.let { return it } }; return null }
        fun safeFloat(idx: Int?, cols: List<String>): Float {
            if (idx == null || idx >= cols.size) return 0f
            return cols[idx].trim().toFloatOrNull() ?: 0f
        }
        // Detect Scene CSV format: header has 48 cols but data has 61 cols (13 extra columns shift indices)
        val firstDataCols = lines.getOrNull(startLine + 1)?.split(",")?.size ?: headerCols.size
        val isSceneFormat = firstDataCols > headerCols.size + 5
        // Scene CSV exact data indices (verified from actual data):
        // CPU MHz: 23-30, CPU M Cycles: 31-38, CPU℃: 39, DDR: 41, GPU%: 42, GPUMHz: 44,
        // Battery%: 45, Battery℃: 46, Current: 47, Volt: 49, Power: 50
        fun adjIdx(headerIdx: Int): Int = if (!isSceneFormat) headerIdx else when (headerIdx) {
            in 0..12 -> headerIdx           // FPS-JANK-BigJANK-FrameTime-CPU(%)-CPU0-7(%): no shift
            in 13..20 -> headerIdx + 10     // CPU0-7(MHz): shift +10
            in 21..28 -> headerIdx + 10     // CPU0-7(M Cycles): shift +10
            29 -> 45                         // CPU(℃): verified at data[45] (values 37-38°C)
            30 -> 41                         // DDR(Mbps)
            31 -> 42                         // GPU(%)
            32 -> 44                         // GPU(MHz)
            33 -> 46                         // Battery(%)
            34 -> 47                         // Battery(℃)
            35 -> 48                         // Current(mA)
            36 -> 49                         // Battery(volt)
            37 -> 50                         // Power(mW)
            else -> headerIdx
        }

        val fpsI = col("FPS") ?: return null
        val jankI = col("JANK", "Jank")
        val bigJankI = col("BigJANK", "Big Jank", "BigJank")
        val ftI = col("Max FrameTime(ms)", "FrameTime")
        val cpu0I = col("CPU0(%)", "Cpu03"); val cpu1I = col("CPU1(%)"); val cpu2I = col("CPU2(%)"); val cpu3I = col("CPU3(%)")
        val cpu4I = col("CPU4(%)", "Cpu46"); val cpu5I = col("CPU5(%)"); val cpu6I = col("CPU6(%)"); val cpu7I = col("CPU7(%)", "Cpu7")
        val gpuFI = adjIdx(col("GPU(MHz)") ?: 32); val gpuUI = adjIdx(col("GPU(%)") ?: 31)
        val ddrI = adjIdx(col("DDR(Mbps)") ?: 30); val pwrI = adjIdx(col("Power(mW)") ?: 37)
        val capI = adjIdx(col("Battery(%)") ?: 33); val tmpI = adjIdx(col("CPU(℃)") ?: 29)
        val cf0I = adjIdx(col("CPU0(MHz)") ?: 13); val cf1I = adjIdx(col("CPU1(MHz)") ?: 14)
        val cf2I = adjIdx(col("CPU2(MHz)") ?: 15); val cf3I = adjIdx(col("CPU3(MHz)") ?: 16)
        val cf4I = adjIdx(col("CPU4(MHz)") ?: 17); val cf5I = adjIdx(col("CPU5(MHz)") ?: 18)
        val cf6I = adjIdx(col("CPU6(MHz)") ?: 19); val cf7I = adjIdx(col("CPU7(MHz)") ?: 20)
        val cc0I = adjIdx(col("CPU0(M Cycles)") ?: 21); val cc1I = adjIdx(col("CPU1(M Cycles)") ?: 22)
        val cc2I = adjIdx(col("CPU2(M Cycles)") ?: 23); val cc3I = adjIdx(col("CPU3(M Cycles)") ?: 24)
        val cc4I = adjIdx(col("CPU4(M Cycles)") ?: 25); val cc5I = adjIdx(col("CPU5(M Cycles)") ?: 26)
        val cc6I = adjIdx(col("CPU6(M Cycles)") ?: 27); val cc7I = adjIdx(col("CPU7(M Cycles)") ?: 28)

        val fpsL = mutableListOf<Float>(); val jankL = mutableListOf<Float>(); val bjL = mutableListOf<Float>()
        val ftL = mutableListOf<Float>(); val c03L = mutableListOf<Float>(); val c46L = mutableListOf<Float>()
        val c7L = mutableListOf<Float>(); val gfL = mutableListOf<Float>(); val guL = mutableListOf<Float>()
        val ddrL = mutableListOf<Float>(); val pwrL = mutableListOf<Float>(); val capL = mutableListOf<Float>()
        val tmpL = mutableListOf<Float>()
        val cf03L = mutableListOf<Float>(); val cf46L = mutableListOf<Float>(); val cf7L = mutableListOf<Float>()
        val cc03L = mutableListOf<Float>(); val cc46L = mutableListOf<Float>(); val cc7L = mutableListOf<Float>()

        // Zenith old format: Cpu03/Cpu46/Cpu7 are pre-aggregated (no CPU0-7 individual cols)
        val hasPreAggCpu = colMap.containsKey("Cpu03")

        for (i in (startLine + 1) until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val c = line.split(",")
            fpsL.add(safeFloat(fpsI, c))
            jankL.add(safeFloat(jankI, c))
            bjL.add(safeFloat(bigJankI, c))
            ftL.add(safeFloat(ftI, c))
            if (hasPreAggCpu) {
                // Zenith format: use pre-aggregated columns directly
                c03L.add(safeFloat(col("Cpu03"), c))
                c46L.add(safeFloat(col("Cpu46"), c))
                c7L.add(safeFloat(col("Cpu7"), c))
            } else {
                val c0 = safeFloat(cpu0I, c); val c1 = safeFloat(cpu1I, c)
                val c2 = safeFloat(cpu2I, c); val c3 = safeFloat(cpu3I, c)
                c03L.add((c0 + c1 + c2 + c3) / 4f)
                val c4 = safeFloat(cpu4I, c); val c5 = safeFloat(cpu5I, c); val c6 = safeFloat(cpu6I, c)
                c46L.add((c4 + c5 + c6) / 3f)
                c7L.add(safeFloat(cpu7I, c))
            }
            gfL.add(safeFloat(gpuFI, c))
            guL.add(safeFloat(gpuUI, c))
            ddrL.add(safeFloat(ddrI, c))
            pwrL.add(safeFloat(pwrI, c) / 1000f) // mW → W
            capL.add(safeFloat(capI, c))
            val f0 = safeFloat(cf0I, c); val f1 = safeFloat(cf1I, c); val f2 = safeFloat(cf2I, c); val f3 = safeFloat(cf3I, c)
            cf03L.add((f0 + f1 + f2 + f3) / 4f)
            val f4 = safeFloat(cf4I, c); val f5 = safeFloat(cf5I, c); val f6 = safeFloat(cf6I, c)
            cf46L.add((f4 + f5 + f6) / 3f)
            cf7L.add(safeFloat(cf7I, c))
            val d0 = safeFloat(cc0I, c); val d1 = safeFloat(cc1I, c); val d2 = safeFloat(cc2I, c); val d3 = safeFloat(cc3I, c)
            cc03L.add((d0 + d1 + d2 + d3) / 4f)
            val d4 = safeFloat(cc4I, c); val d5 = safeFloat(cc5I, c); val d6 = safeFloat(cc6I, c)
            cc46L.add((d4 + d5 + d6) / 3f)
            cc7L.add(safeFloat(cc7I, c))
            tmpL.add(safeFloat(tmpI, c))
        }
        if (fpsL.isEmpty()) return null
        val n = fpsL.size
        val avgFps = fpsL.average().toFloat()
        val maxFps = fpsL.maxOrNull() ?: 0f
        val minFps = fpsL.filter { it > 0f }.minOrNull() ?: 0f
        val variance = fpsL.map { (it - avgFps) * (it - avgFps) }.average().toFloat()
        val smoothPct = fpsL.count { it >= 45f }.toFloat() / n * 100f
        val sorted = fpsL.sorted()
        val low5Pct = sorted[(n * 0.05).toInt().coerceIn(0, n - 1)]
        val peakTemp = tmpL.maxOrNull() ?: 0f
        val avgPowerW = pwrL.average().toFloat()
        return SessionEntry(
            id = file.lastModified(), appName = appName, appPkg = appPkg,
            date = date, version = version, crop = crop,
            avgFps = avgFps, maxFps = maxFps, minFps = minFps,
            variance = variance, smoothPct = smoothPct, low5Pct = low5Pct,
            peakTemp = peakTemp, avgPowerW = avgPowerW, durationSec = n.toLong(),
            chartData = ChartData(
                fps = fpsL, temp = tmpL, cpu03 = c03L, cpu46 = c46L, cpu7 = c7L,
                cpu0 = c03L, cpu1 = c03L, cpu2 = c03L, cpu3 = c03L,
                cpu4 = c46L, cpu5 = c46L, cpu6 = c46L, cpu7b = c7L,
                gpuFreq = gfL, gpuUsage = guL, ddr = ddrL, powerW = pwrL,
                capacity = capL, frameTime = ftL, jank = jankL, bigJank = bjL,
                maxFrameTime = ftL,  // use frameTime as placeholder
                cpuFreq0 = cf03L, cpuFreq1 = cf03L, cpuFreq2 = cf03L, cpuFreq3 = cf03L,
                cpuFreq4 = cf46L, cpuFreq5 = cf46L, cpuFreq6 = cf46L, cpuFreq7 = cf7L,
                cpuCyc0 = cc03L, cpuCyc1 = cc03L, cpuCyc2 = cc03L, cpuCyc3 = cc03L,
                cpuCyc4 = cc46L, cpuCyc5 = cc46L, cpuCyc6 = cc46L, cpuCyc7 = cc7L,
                voltage = capL, current = capL, battTemp = capL,
                threadPercent = mutableListOf(),
            )
        )
    } catch (_: Exception) {
        return null
    }
}