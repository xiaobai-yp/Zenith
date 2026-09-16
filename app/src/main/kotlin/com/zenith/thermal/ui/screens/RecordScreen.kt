package com.zenith.thermal.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
private val Bg = ZenithBg
private val Panel = Color(0xFF1C1C1E)
private val Divider = Color(0xFF2A2A2E)
private val Ink = Color(0xFFE8E8E8)
private val Muted = Color(0xFF888888)
private val Dim = Color(0xFF666666)
private val Faint = Color(0xFF555555)
private val AxisC = Color(0xFF444444)
private val StatBlue = Color(0xFF5B9CF6)
private val Green = Color(0xFF76C442)
private val Orange = Color(0xFFFF7756)
private val TitleC = Color(0xFFAAAAAA)
private val LegendC = Color(0xFFAAAAAA)
private val GridC = Color(0xFF333333)
// series
private val S_FPS = Color(0xFF5B9CF6)
private val S_TEMP = Color(0xFFE58C3A)          // battery temp (battery stats line)
private val S_CPU_PCT = Color(0xFFCC79C8)       // pink: CPU(%) placeholder line
private val S_GPU_PCT = Color(0xFF4CC9D8)       // cyan: GPU(%) placeholder line
private val S_CPU03 = Color(0xFFCC79C8)
private val S_CPU46 = Color(0xFF4CC9D8)
private val S_CPU7 = Color(0xFFE58C3A)
private val S_GF = Color(0xFF5B9CF6)
private val S_GU = Color(0xFF5B9CF6)
private val S_DDR = Color(0xFF5B9CF6)
private val S_PWR = Color(0xFF5B9CF6)
private val S_CAP = Color(0xFFB0B0B0)
private val S_TEMPL = Color(0xFF5B9CF6)         // CPU temperature line
private val S_TEMP2 = Color(0xFF8DC9E8)         // temp on cycles chart

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
    val gpuFreq: List<Float> = emptyList(), val gpuUsage: List<Float> = emptyList(),
    val ddr: List<Float> = emptyList(), val powerW: List<Float> = emptyList(), val capacity: List<Float> = emptyList(),
    val frameTime: List<Float> = emptyList(),
    val jank: List<Float> = emptyList(),
    val bigJank: List<Float> = emptyList(),
    val cpuFreq03: List<Float> = emptyList(), val cpuFreq46: List<Float> = emptyList(), val cpuFreq7: List<Float> = emptyList(),
    val cpuCyc03: List<Float> = emptyList(), val cpuCyc46: List<Float> = emptyList(), val cpuCyc7: List<Float> = emptyList(),
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
    sb.appendLine("Session,${s.appName},${s.date},${s.version},${s.crop}")
    sb.appendLine("FPS,AVG=${s.avgFps},MAX=${s.maxFps},MIN=${s.minFps},VAR=${s.variance},Smooth=${s.smoothPct},5%Low=${s.low5Pct}")
    sb.appendLine("Temp,Peak=${s.peakTemp}")
    sb.appendLine("Power,AVG=${s.avgPowerW}W")
    sb.appendLine()
    sb.appendLine("t,FPS,Temp,FrameTime,Jank,BigJank,Cpu03,Cpu46,Cpu7,GpuFreq,GpuUsage,DDR,Power,Capacity")
    val n = s.chartData.fps.size
    val d2 = s.chartData
    fun g(l: List<Float>, i: Int): String = if (i < l.size) l[i].toString() else ""
    for (i in 0 until n) {
        sb.append(i).append(',').append(g(d2.fps, i)).append(',').append(g(d2.temp, i)).append(',').append(g(d2.frameTime, i)).append(',')
        sb.append(g(d2.jank, i)).append(',').append(g(d2.bigJank, i)).append(',').append(g(d2.cpu03, i)).append(',').append(g(d2.cpu46, i)).append(',')
        sb.append(g(d2.cpu7, i)).append(',').append(g(d2.gpuFreq, i)).append(',').append(g(d2.gpuUsage, i)).append(',').append(g(d2.ddr, i)).append(',')
        sb.append(g(d2.powerW, i)).append(',').append(g(d2.capacity, i)).appendLine()
    }
    java.io.File("/sdcard", name).writeText(sb.toString())
    android.widget.Toast.makeText(ctx, "Saved to /sdcard/$name", android.widget.Toast.LENGTH_SHORT).show()
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
            ChartCard(title = "FPS", titleRight = "Temperature(°C)", rightColor = S_TEMP,
                legend = listOf("FPS" to S_FPS, "TEMP(°C)" to S_TEMP, "CPU(%)" to S_CPU_PCT, "GPU(%)" to S_GPU_PCT),
                spec = run {
                    val (fLo, fHi, fTicks) = fpsAxis(s.chartData.fps)
                    val (tLo, tHi, tTicks) = tempAxis(s.chartData.temp)
                    ChartSpec(
                        series = listOf(s.chartData.fps to S_FPS),
                        rightSeries = listOf(s.chartData.temp to S_TEMP),
                        yMin = fLo, yMax = fHi,
                        leftTicks = fTicks,
                        rightTicks = tTicks, rightMin = tLo, rightMax = tHi,
                    )
                })
            Spacer(Modifier.height(14.dp))
            if ("Jank" !in hiddenCards) {
                ChartCard(title = "Jank",
                    legend = listOf("Jank" to S_CPU03, "Big Jank" to S_TEMP2),
                    sub = "Jank: $totalJank | Big Jank: $totalBig",
                    spec = ChartSpec(
                        series = listOf(s.chartData.jank to S_CPU03),
                        rightSeries = listOf(s.chartData.bigJank to S_TEMP2),
                        yMin = 0f, yMax = 5f,
                        leftTicks = listOf("5"),
                        height = 120,
                        bar = true,
                    ))
            }
            Spacer(Modifier.height(14.dp))
            if ("Frame Time" !in hiddenCards) ChartCard(title = "Frame Time (ms)",
                legend = emptyList(), sub = "MAX: ${maxFt}ms",
                spec = ChartSpec(
                    series = listOf(s.chartData.frameTime to S_FPS),
                    yMin = 8f, yMax = 100f,
                    leftTicks = listOf("100", "91", "83", "75", "66", "58", "50", "41", "33", "25", "16", "8"),
                    bar = true,
                ))
            Spacer(Modifier.height(14.dp))
            if ("CPU temperature" !in hiddenCards) ChartCard(title = "CPU Usage (%)", opts = true,
                legend = listOf("Total" to S_FPS, "CPU 0~3" to S_CPU03, "CPU 4~6" to S_CPU46, "CPU 7" to S_CPU7),
                spec = ChartSpec(
                    series = listOf(
                        s.chartData.cpu03 to S_CPU03,
                        s.chartData.cpu46 to S_CPU46,
                        s.chartData.cpu7 to S_CPU7,
                        s.chartData.cpu03.mapIndexed { i, v -> (v + (s.chartData.cpu46.getOrElse(i) { v }) * 0.6f + (s.chartData.cpu7.getOrElse(i) { v }) * 0.3f).coerceAtMost(100f) } to S_FPS,
                    ),
                    dashed = setOf(3),
                    yMin = 0f, yMax = 100f,
                    leftTicks = (100 downTo 10 step 10).map { it.toString() },
                ))
            Spacer(Modifier.height(14.dp))
            ChartCard(title = "CPU Frequency (MHz)", opts = true,
                legend = listOf("CPU 0~3" to S_CPU03, "CPU 4~6" to S_CPU46, "CPU 7" to S_CPU7),
                spec = run {
                    val all = s.chartData.cpuFreq03 + s.chartData.cpuFreq46 + s.chartData.cpuFreq7
                    val (lo, hi, ticks) = autoAxis(all, minFloor = 0f, niceStep = true)
                    ChartSpec(
                        series = listOf(
                            s.chartData.cpuFreq03 to S_CPU03,
                            s.chartData.cpuFreq46 to S_CPU46,
                            s.chartData.cpuFreq7 to S_CPU7,
                        ),
                        yMin = lo, yMax = hi,
                        leftTicks = ticks,
                    )
                })
            Spacer(Modifier.height(14.dp))
            ChartCard(title = "CPU Cycles (M)", titleRight = "CPU Temperature (°C)", rightColor = S_TEMP2,
                legend = listOf("CPU 0~3" to S_CPU03, "CPU 4~6" to S_CPU46, "CPU 7" to S_CPU7, "TEMP(°C)" to S_TEMP2),
                spec = run {
                    val all = s.chartData.cpuCyc03 + s.chartData.cpuCyc46 + s.chartData.cpuCyc7
                    val (lo, hi, ticks) = autoAxis(all, minFloor = 0f)
                    val (tLo, tHi, tTicks) = tempAxis(s.chartData.temp)
                    ChartSpec(
                        series = listOf(
                            s.chartData.cpuCyc03 to S_CPU03,
                            s.chartData.cpuCyc46 to S_CPU46,
                            s.chartData.cpuCyc7 to S_CPU7,
                        ),
                        rightSeries = listOf(s.chartData.temp to S_TEMP2),
                        yMin = lo, yMax = hi,
                        leftTicks = ticks,
                        rightTicks = tTicks, rightMin = tLo, rightMax = tHi,
                    )
                })
            Spacer(Modifier.height(14.dp))
            if ("GPU" !in hiddenCards) ChartCard(title = "GPU Frequency (MHz)", titleRight = "Usage (%)",
                legend = listOf("Frequency (MHz)" to S_GF, "Usage (%)" to S_GU),
                spec = run {
                    val (lo, hi, ticks) = autoAxis(s.chartData.gpuFreq, minFloor = 0f)
                    ChartSpec(
                        series = listOf(s.chartData.gpuFreq to S_GF),
                        rightSeries = listOf(s.chartData.gpuUsage to S_GU),
                        yMin = lo, yMax = hi,
                        leftTicks = ticks,
                        rightTicks = listOf("100", "75", "50", "25"), rightMin = 0f, rightMax = 100f,
                    )
                })
            Spacer(Modifier.height(14.dp))
            if ("DDR" !in hiddenCards) ChartCard(title = "DDR (MHz | Mbps)",
                legend = emptyList(),
                spec = run {
                    val (lo, hi, ticks) = autoAxis(s.chartData.ddr, minFloor = 0f)
                    ChartSpec(
                        series = listOf(s.chartData.ddr to S_DDR),
                        yMin = lo, yMax = hi,
                        leftTicks = ticks,
                    )
                })
            Spacer(Modifier.height(14.dp))
            if ("Power" !in hiddenCards) ChartCard(title = "Power (W)", titleRight = "Capacity %",
                legend = listOf("Power (W)" to S_PWR, "Capacity (%)" to S_CAP),
                sub = "MAX: ${comma(pMax, 2)}W | MIN: ${comma(pMin, 2)}W | AVG: ${comma(pAvg.toFloat(), 2)}W",
                spec = run {
                    val (lo, hi, ticks) = autoAxis(s.chartData.powerW, minFloor = 0f, maxCeiling = 20f)
                    ChartSpec(
                        series = listOf(s.chartData.powerW to S_PWR),
                        rightSeries = listOf(s.chartData.capacity to S_CAP),
                        yMin = lo, yMax = hi,
                        leftTicks = ticks,
                        rightTicks = listOf("100", "80", "60", "40", "20"), rightMin = 0f, rightMax = 100f,
                    )
                })
            Spacer(Modifier.height(14.dp))
            ChartCard(title = "CPU Temperature (°C)",
                legend = emptyList(), sub = "MAX: ${comma(tMax)}°C | MIN: ${comma(tMin)}°C | AVG: ${comma(tAvg.toFloat())}°C",
                spec = run {
                    val (lo, hi, ticks) = tempAxis(s.chartData.temp)
                    ChartSpec(
                        series = listOf(s.chartData.temp to S_TEMPL),
                        yMin = lo, yMax = hi,
                        leftTicks = ticks,
                    )
                })
        }
    }
}

@Composable
private fun SessionStatsCard(s: SessionEntry) {
    val ctx = LocalContext.current
    Surface(shape = RoundedCornerShape(14.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameIcon(s.appName, s.appPkg, size = 34, fontSize = 5, radius = 8)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(s.date, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    val label = resolveAppLabel(ctx, s.appPkg).ifEmpty { s.appName }
                    Text("$label (${s.version}) · ${s.crop}", color = Dim, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                StatCell("MAX", comma(s.maxFps), "FPS", Modifier.weight(1f))
                StatCell("MIN", comma(s.minFps), "FPS", Modifier.weight(1f))
                StatCell("AVG", comma(s.avgFps, 2), "FPS", Modifier.weight(1f))
                StatCell("VARIANCE", comma(s.variance), "FPS", Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().background(Divider, RoundedCornerShape(2.dp)).height(1.dp)) {}
            Spacer(Modifier.height(6.dp))
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
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.padding(vertical = 4.dp)) {
        Text(label, color = Dim, fontSize = 9.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = valueColor, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(unit, color = Faint, fontSize = 9.sp)
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
    val xLabels: List<String> = emptyList(), // adaptive X-axis labels
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
    Surface(shape = RoundedCornerShape(16.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TitleC, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (titleRight != null) Text(titleRight, color = rightColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                else if (opts) Text("Chart Options ▸", color = Dim, fontSize = 11.sp)
            }
            Spacer(Modifier.height(12.dp))
            ChartCanvas(spec)
            if (legend.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    legend.forEach { (name, clr) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 7.dp)) {
                            Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(clr))
                            Spacer(Modifier.width(5.dp))
                            Text(name, color = LegendC, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (sub != null) {
                Spacer(Modifier.height(8.dp))
                Text(sub, color = Faint, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
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
        // right y labels
        spec.rightTicks?.forEachIndexed { i, v ->
            val res = tm.measure(AnnotatedString(v), style = labelStyle)
            val y = top + h * i / denR
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
                val style = if (idx in spec.dashed) Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))) else Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
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
            29 -> 39                         // CPU(℃)
            30 -> 41                         // DDR(Mbps)
            31 -> 42                         // GPU(%)
            32 -> 44                         // GPU(MHz)
            33 -> 45                         // Battery(%)
            34 -> 46                         // Battery(℃)
            35 -> 47                         // Current(mA)
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
                gpuFreq = gfL, gpuUsage = guL, ddr = ddrL, powerW = pwrL,
                capacity = capL, frameTime = ftL, jank = jankL, bigJank = bjL,
                cpuFreq03 = cf03L, cpuFreq46 = cf46L, cpuFreq7 = cf7L,
                cpuCyc03 = cc03L, cpuCyc46 = cc46L, cpuCyc7 = cc7L,
            )
        )
    } catch (_: Exception) {
        return null
    }
}