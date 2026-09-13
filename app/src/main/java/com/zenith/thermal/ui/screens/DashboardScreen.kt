package com.zenith.thermal.ui.screens

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.thermal.AppProfileCache
import com.zenith.thermal.Profile
import com.zenith.thermal.R
import com.zenith.thermal.ZenithDaemonClient
import com.zenith.thermal.ui.components.*
import com.zenith.thermal.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ── Device info (read once on IO) ──
private data class DeviceInfo(
    val productName: String,
    val socModel: String,
    val platform: String,
    val kernelVersion: String,
    val glVersion: String,
)

private fun String.sysProp(): String = runCatching {
    Runtime.getRuntime().exec(arrayOf("sh", "-c", "getprop $this"))
        .inputStream.bufferedReader().readText().trim()
}.getOrElse { "-" }

private fun readDeviceInfo(): DeviceInfo = DeviceInfo(
    productName = "ro.product.name".sysProp().ifEmpty { "-" },
    socModel = "ro.soc.model".sysProp().ifEmpty { "-" },
    platform = "ro.board.platform".sysProp().ifEmpty { "-" },
    kernelVersion = runCatching {
        Runtime.getRuntime().exec(arrayOf("sh", "-c", "uname -r"))
            .inputStream.bufferedReader().readText().trim()
    }.getOrElse { "-" },
    glVersion = runCatching {
        Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys SurfaceFlinger"))
            .inputStream.bufferedReader().readText()
            .lineSequence().firstOrNull { "V@" in it }?.let { raw ->
                val gl = raw.substringAfter("OpenGL ES ").trim()
                val vAt = gl.indexOf("V@")
                val vAtEnd = gl.indexOf(" ", vAt)
                if (vAt > 0 && vAtEnd > 0) "OpenGL ES " + gl.substring(0, vAtEnd)
                else "OpenGL ES $gl"
            } ?: "-"
    }.getOrElse { "-" }
)

// ── Version string: v{versionName} ({versionCode}-{hash}-release) ──
private fun buildVersionString(): String = runCatching {
    val bc = Class.forName("com.zenith.thermal.BuildConfig")
    val ver = bc.getField("VERSION_NAME").get(null) as String
    val code = bc.getField("VERSION_CODE").get(null) as Int
    val hash = runCatching { bc.getField("GIT_HASH").get(null) as? String ?: "" }.getOrDefault("")
    val h = hash.takeIf { it.isNotEmpty() }?.take(7) ?: "release"
    "v$ver ($code-$h-release)"
}.getOrElse { "v1.0.0 (unknown)" }

@Composable
fun DashboardScreen() {
    val ctx = LocalContext.current

    // ── Live state ──
    var profileName by remember { mutableStateOf("Default") }
    var batPct by remember { mutableIntStateOf(0) }
    var isDaemonConnected by remember { mutableStateOf(ZenithDaemonClient.isConnected) }
    var perAppCount by remember { mutableIntStateOf(0) }
    var uptimeMs by remember { mutableLongStateOf(0L) }

    // Device info (read once)
    var deviceInfo by remember { mutableStateOf<DeviceInfo?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { deviceInfo = readDeviceInfo() }

        while (true) {
            isDaemonConnected = ZenithDaemonClient.isConnected

            // Global profile from SharedPreferences (always works, IPC or not)
            runCatching {
                val globalId = AppProfileCache.getGlobal(ctx)
                profileName = Profile.nameForId(globalId)
            }

            runCatching {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                batPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            }
            runCatching {
                val map = ZenithDaemonClient.getAppsMap()
                val local = AppProfileCache.all(ctx)
                var count = 0
                map.forEach { if (it.value > 0) count++ }
                local.forEach { if (it.value > 0 && !map.containsKey(it.key)) count++ }
                perAppCount = count
            }
            uptimeMs = SystemClock.elapsedRealtime()
            delay(2000)
        }
    }

    val appPid = Process.myPid()

    // ── Layout: pinned header + scrollable sheet ──
    Column(modifier = Modifier.fillMaxSize().background(ZenithBg)) {
        // Header: title + gear icon
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Zenith",
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
            )
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = Color(0xFF1E1A2B)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_zenith_logo),
                    contentDescription = "Zenith logo",
                    tint = Color.White,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        // Foreground sheet
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
                // ── HeroCard ──
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF2D2345)
                ) {
                    Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp)) {
                        // Title row
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Zenith is working",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    lineHeight = 25.sp
                                )
                                Spacer(Modifier.height(1.dp))
                                Text(buildVersionString(), color = ZenithMuted, fontSize = 12.sp)
                            }
                            // Info button
                            Surface(
                                modifier = Modifier.size(30.dp),
                                shape = CircleShape,
                                color = Color(0x23ED9DF8)
                            ) {
                                Text(
                                    "i",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.wrapContentSize(Alignment.Center)
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // Badges row — round + status dot
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Badge(
                                text = "PID $appPid",
                                bg = Color(0x40A78BFA),
                                fg = Color.White,
                                round = true,
                                dot = isDaemonConnected
                            )
                            Badge(
                                text = deviceInfo?.productName ?: Build.DEVICE,
                                bg = Color(0x40A78BFA),
                                fg = Color.White,
                                round = true
                            )
                        }
                    }
                }

                // ── MiniCardRow: Per-App + Global Profile ──
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    // Per-App
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF16131F)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🎮", fontSize = 15.sp)
                                Spacer(Modifier.width(8.dp))
                                Text("Per-App", color = ZenithMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(10.dp))
                            Text("$perAppCount", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                        }
                    }
                    // Global Profile
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF16131F)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("⚡", fontSize = 15.sp)
                                Spacer(Modifier.width(8.dp))
                                Text("Global Profile", color = ZenithMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(profileName, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }

                // ── System Metrics ──
                Text(
                    "SYSTEM METRICS",
                    color = ZenithPurple,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 0.dp)
                )
                HomeMetricItem("🤖", "Android", Build.VERSION.RELEASE)
                HomeMetricItem("📱", "Device", "${Build.MODEL} (OPPO)")
                deviceInfo?.let { di ->
                    HomeMetricItem("⚡", "Processor", di.platform)
                    HomeMetricItem("🐧", "Kernel", di.kernelVersion)
                    HomeMetricItem("🎮", "GPU", di.glVersion)
                }
            }
        }
    }
}

@Composable
private fun HomeMetricItem(emoji: String, label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF2D2345)
        ) {
            Text(emoji, fontSize = 16.sp, modifier = Modifier.wrapContentSize(Alignment.Center))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = ZenithPurple,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            modifier = Modifier.widthIn(max = 200.dp)
        )
    }
}
