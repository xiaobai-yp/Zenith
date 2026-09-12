package com.zenith.thermal.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import com.zenith.thermal.AppItem
import com.zenith.thermal.AppProfileCache
import com.zenith.thermal.Profile
import com.zenith.thermal.ZenithDaemonClient
import androidx.compose.ui.geometry.Offset
import com.zenith.thermal.ui.components.*
import com.zenith.thermal.ui.theme.*
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

// ---- Profile color mapping for badges ----
private data class ProfileBadge(val bg: Color, val fg: Color)
private fun badgeFor(id: Int): ProfileBadge = when (id) {
    9, 10, 11, 13, 14 -> ProfileBadge(ZenithPurple.copy(alpha = 0.12f), ZenithPurple)     // game
    7, 12            -> ProfileBadge(ZenithPink.copy(alpha = 0.12f), ZenithPink)           // dynamic
    1                -> ProfileBadge(ZenithGreen.copy(alpha = 0.12f), ZenithGreen)         // save
    4, 15            -> ProfileBadge(ZenithAmber.copy(alpha = 0.12f), ZenithAmber)         // youtube/camera
    8                -> ProfileBadge(ZenithRed.copy(alpha = 0.12f), ZenithRed)             // incall
    else             -> ProfileBadge(Color(0x0DFFFFFF), Color(0x66FFFFFF))                  // muted (0)
}

private enum class Filter(val label: String) {
    ALL("Semua"), DYNAMIC("Dynamic"), GAME("Game"), SAVE("Save"), YOUTUBE("YouTube")
}
private fun profileMatchesFilter(id: Int, f: Filter): Boolean = when (f) {
    Filter.ALL -> true
    Filter.DYNAMIC -> id in listOf(7, 12)
    Filter.GAME -> id in listOf(9, 10, 11, 13, 14)
    Filter.SAVE -> id == 1
    Filter.YOUTUBE -> id in listOf(4, 15)
}

@Composable
fun ThermalScreen() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var showSystem by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showProfileDialog by remember { mutableStateOf<AppItem?>(null) }
    var showGlobalDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    // Centralized icon cache — decode ALL icons once on IO thread
    val iconCache = remember { ConcurrentHashMap<String, androidx.compose.ui.graphics.painter.Painter?>() }
    var iconVersion by remember { mutableIntStateOf(0) }

    fun loadApps() {
        val pm = ctx.packageManager
        val map = try { ZenithDaemonClient.getAppsMap() } catch (_: Throwable) { emptyMap() }
        val localCache = AppProfileCache.all(ctx)
        val loaded = mutableListOf<AppItem>()
        @Suppress("DEPRECATION")
        for (info in pm.getInstalledApplications(0)) {
            if (!showSystem && (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue
            loaded.add(AppItem(info, pm, map[info.packageName] ?: localCache[info.packageName] ?: 0))
        }
        loaded.sortBy { it.name.lowercase() }
        apps = loaded
    }
    LaunchedEffect(Unit) {
        loadApps()
        // Icons on Main — AdaptiveIconDrawable.toBitmap() needs main-thread Canvas
        for (item in apps) {
            if (iconCache.containsKey(item.pkg)) continue
            try {
                val bmp = item.icon.toBitmap(24, 24).asImageBitmap()
                iconCache[item.pkg] = BitmapPainter(bmp)
            } catch (_: Exception) {}
        }
        iconVersion++
    }
    LaunchedEffect(showSystem) {
        loadApps()
        for (item in apps) {
            if (iconCache.containsKey(item.pkg)) continue
            try {
                val bmp = item.icon.toBitmap(24, 24).asImageBitmap()
                iconCache[item.pkg] = BitmapPainter(bmp)
            } catch (_: Exception) {}
        }
        iconVersion++
    }

    val filtered by remember(apps, searchQuery) {
        derivedStateOf {
            val q = searchQuery.trim().lowercase()
            if (q.isEmpty()) apps
            else apps.filter { it.name.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
        }
    }

    // ——— Root with gradient bg ———
    Box(Modifier.fillMaxSize().background(ZenithBg)) {
        Column(Modifier.fillMaxSize()) {
            // Status bar spacer
            Spacer(Modifier.height(28.dp))

            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                // Header
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Zenith Thermal", color = ZenithText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(ZenithGreen))
                            Spacer(Modifier.width(5.dp))
                            Text("Running", color = ZenithGreen, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    // Menu dots
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(ZenithBorder2)
                            .clickable { showMenu = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⋯", color = ZenithMuted, fontSize = 16.sp)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Search bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.lg))
                        .background(Color(0x0DFFFFFF))
                        .border(1.dp, ZenithBorder2, RoundedCornerShape(Radius.lg))
                        .padding(horizontal = Space.md, vertical = Space.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("\uD83D\uDD0D", fontSize = 13.sp, color = ZenithMuted2)
                    Spacer(Modifier.width(6.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(color = ZenithText, fontSize = 12.sp),
                        singleLine = true,
                        cursorBrush = Brush.linearGradient(listOf(ZenithPurple, ZenithPurple)),
                        decorationBox = { inner ->
                            Box {
                                if (searchQuery.isEmpty()) {
                                    Text("Cari aplikasi…", color = ZenithMuted2, fontSize = 12.sp)
                                }
                                inner()
                            }
                        }
                    )
                }

                Spacer(Modifier.height(Space.sm))

                // Section label
                SectionLabel("Per-App", count = "· ${filtered.size} apps")

                Spacer(Modifier.height(4.dp))

                // App cards
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered, key = { it.pkg }, contentType = { "app" }) { item ->
                        AppCard(item = item, iconCache = iconCache) { showProfileDialog = item }
                    }
                }
            }
        }

        // ——— Menu dropdown (top-right, text-only) ———
        if (showMenu) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .zIndex(10f)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = { showMenu = false }
                    )
            ) {
                GradientBorderCard(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 52.dp, end = 14.dp)
                        .width(210.dp),
                    radius = Radius.lg,
                    innerPadding = 4.dp,
                    innerColor = Color(0xF70C0C18)
                ) {
                    OverlayMenuItem("Show System Apps") {
                        showSystem = !showSystem; showMenu = false; loadApps()
                    }
                    OverlayMenuItem("Reset Per-App Profiles") {
                        AppProfileCache.prefs(ctx).edit().clear().apply()
                        runCatching { ZenithDaemonClient.resetProfiles() }
                        showMenu = false; loadApps()
                    }
                    OverlayMenuItem("Global Profile") {
                        showMenu = false; showGlobalDialog = true
                    }
                    OverlayMenuItem("About") { showMenu = false }
                }
            }
        }

        // ——— Profile dialogs ———
        showProfileDialog?.let { app ->
            GradientProfileDialog(
                title = "Set Profile: ${app.name}",
                pkg = app.pkg,
                onDismiss = { showProfileDialog = null },
                onApplied = { loadApps() }
            )
        }
        if (showGlobalDialog) {
            GradientProfileDialog(
                title = "Global Profile",
                pkg = null,
                onDismiss = { showGlobalDialog = false },
                onApplied = { showGlobalDialog = false }
            )
        }
    }
}

@Composable
private fun AppCard(item: AppItem, iconCache: java.util.concurrent.ConcurrentHashMap<String, androidx.compose.ui.graphics.painter.Painter?>, onClick: () -> Unit) {
    val badge = badgeFor(item.profileId)
    GradientBorderCard(
        modifier = Modifier.fillMaxWidth(),
        radius = Radius.xl,
        innerPadding = Space.sm,
        gradient = null,
        onClick = onClick
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val painter = iconCache[item.pkg]
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ZenithPurple.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                painter?.let {
                    Image(it, contentDescription = null, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                }
            }
            Spacer(Modifier.width(10.dp))

            // Info
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    item.name, color = ZenithText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    item.pkg, color = ZenithMuted2, fontSize = 9.sp,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))

            // Badge
            Badge(text = Profile.name(item.profileId).uppercase(), bg = badge.bg, fg = badge.fg)

            Spacer(Modifier.width(6.dp))

            // Chevron
            Text("›", color = ZenithMuted3, fontSize = 14.sp)
        }
    }
}

@Composable
private fun OverlayMenuItem(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.lg, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = ZenithText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GradientProfileDialog(
    title: String,
    pkg: String?,
    onDismiss: () -> Unit,
    onApplied: () -> Unit
) {
    val ctx = LocalContext.current
    val currentId = if (pkg != null) {
        AppProfileCache.get(ctx, pkg).takeIf { it >= 0 }
            ?: runCatching { ZenithDaemonClient.getAppsMap() }.getOrNull()?.get(pkg) ?: 0
    } else AppProfileCache.getGlobal(ctx)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xF00C0C18),
        title = { Text(title, color = ZenithText, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (i in 0 until Profile.count()) {
                    val profileId = Profile.value(i)
                    val selected = Profile.indexOf(currentId) == i
                    val badge = badgeFor(profileId)

                    GradientBorderCard(
                        modifier = Modifier.fillMaxWidth(),
                        radius = Radius.lg,
                        borderPadding = if (selected) 1.5.dp else 1.dp,
                        gradient = if (selected) Brush.linearGradient(listOf(ZenithPurple.copy(0.8f), ZenithPink.copy(0.7f))) else Brush.linearGradient(listOf(ZenithPurple.copy(0.15f), ZenithPink.copy(0.1f))),
                        innerPadding = Space.md,
                        innerColor = if (selected) Color(0xF00C0C18) else Color(0xE00C0C18),
                        onClick = {
                            if (pkg != null) {
                                AppProfileCache.set(ctx, pkg, profileId)
                                ZenithDaemonClient.setAppProfile(pkg, profileId)
                            } else {
                                com.zenith.thermal.ThermalController.applyGlobal(ctx, profileId)
                            }
                            onApplied(); onDismiss()
                        }
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                Profile.MENU_NAMES[i],
                                color = if (selected) Color.White else ZenithText,
                                fontSize = 15.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                                maxLines = 2
                            )
                            if (selected) {
                                Badge(text = "ACTIVE", bg = ZenithGreen.copy(alpha = 0.12f), fg = ZenithGreen)
                            } else {
                                ZenithRadio(checked = false, onClick = {
                                    if (pkg != null) {
                                        AppProfileCache.set(ctx, pkg, profileId)
                                        ZenithDaemonClient.setAppProfile(pkg, profileId)
                                    } else {
                                        com.zenith.thermal.ThermalController.applyGlobal(ctx, profileId)
                                    }
                                    onApplied(); onDismiss()
                                })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}