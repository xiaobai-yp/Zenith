package com.zenith.thermal.ui.screens

import android.app.Dialog
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.zenith.thermal.AppItem
import com.zenith.thermal.AppProfileCache
import com.zenith.thermal.Profile
import com.zenith.thermal.ThermalController
import com.zenith.thermal.ZenithDaemonClient
import kotlinx.coroutines.delay

private val SurfaceColor = Color(0xFF2A444D)
private val TextColor = Color(0xFFF2F5F6)
private val MutedColor = Color(0xFFB8C6CA)
private val AccentColor = Color(0xFF5EA7FF)
private val BgRow = Color(0xFF172D35)
private val BadgeBgActive = Color(0xFF162A32)
private val BadgeBgDefault = Color(0xFF1B3138)
private val GradStart = Color(0xFF6C3CE0)
private val GradEnd = Color(0xFFE04090)

@Composable
fun ThermalScreen() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var showSystem by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf<AppItem?>(null) }
    var showProfileDialog by remember { mutableStateOf<AppItem?>(null) }
    var showGlobalDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<ZenithDaemonClient.StatusResponse?>(null) }

    fun loadApps() {
        val pm = ctx.packageManager
        val map = try { ZenithDaemonClient.getAppsMap() } catch (_: Throwable) { emptyMap() }
        val localCache = AppProfileCache.all(ctx)
        val loaded = mutableListOf<AppItem>()
        @Suppress("DEPRECATION")
        for (info in pm.getInstalledApplications(0)) {
            if (!showSystem && (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue
            val pid = map[info.packageName] ?: localCache[info.packageName] ?: 0
            loaded.add(AppItem(info, pm, pid))
        }
        loaded.sortBy { it.name.lowercase() }
        apps = loaded
    }

    LaunchedEffect(Unit) {
        loadApps()
        status = ZenithDaemonClient.getStatus()
        while (true) { delay(2000); status = ZenithDaemonClient.getStatus() }
    }
    LaunchedEffect(showSystem) { loadApps() }

    val filtered = remember(apps, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) apps
        else apps.filter { it.name.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Zenith Thermal", fontSize = 27.sp, color = TextColor,
                modifier = Modifier.weight(1f)
            )
            Text(
                "⋯", fontSize = 26.sp, color = TextColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showMenu = true }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        // Search bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(BgRow, RoundedCornerShape(12.dp))
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🔍", color = MutedColor, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search", color = MutedColor, fontSize = 16.sp) },
                modifier = Modifier.weight(1f),
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedTextColor = TextColor,
                    unfocusedTextColor = TextColor,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = AccentColor
                ),
                singleLine = true
            )
        }

        Spacer(Modifier.height(10.dp))

        // App list
        LazyColumn(
            contentPadding = PaddingValues(bottom = 94.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            items(filtered, key = { it.pkg }) { item ->
                AppRow(
                    item = item,
                    onClick = { showProfileDialog = item }
                )
            }
        }
    }

    // Profile dialog
    showProfileDialog?.let { app ->
        ProfileDialog(
            title = "Set Profile: ${app.name}",
            pkg = app.pkg,
            onDismiss = { showProfileDialog = null },
            onProfileApplied = { loadApps() }
        )
    }

    // Menu dialog
    if (showMenu) {
        MenuDialog(
            showSystem = showSystem,
            onToggleSystem = { showSystem = it; showMenu = false; loadApps() },
            onResetProfiles = {
                showMenu = false
                AppProfileCache.prefs(ctx).edit().clear().apply()
                runCatching { ZenithDaemonClient.resetProfiles() }
                loadApps()
            },
            onGlobalProfile = { showMenu = false; showGlobalDialog = true },
            onBenchmark = { showMenu = false },
            onDismiss = { showMenu = false }
        )
    }

    // Global profile dialog
    if (showGlobalDialog) {
        ProfileDialog(
            title = "Global Profile",
            pkg = null,
            onDismiss = { showGlobalDialog = false },
            onProfileApplied = { showGlobalDialog = false }
        )
    }
}

@Composable
private fun AppRow(item: AppItem, onClick: () -> Unit) {
    val badgeActive = item.profileId != 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgRow, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App icon
        val bitmap = remember(item.pkg) {
            runCatching { item.icon.toBitmap(40, 40).asImageBitmap() }.getOrNull()
        }
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = item.name,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
        Spacer(Modifier.width(10.dp))

        // Name + package
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(1.dp))
            Text(item.pkg, color = MutedColor, fontSize = 11.sp, maxLines = 2, lineHeight = 14.sp)
        }

        // Profile badge
        Box(
            modifier = Modifier
                .background(if (badgeActive) BadgeBgActive else BadgeBgDefault, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                Profile.name(item.profileId),
                color = if (badgeActive) AccentColor else MutedColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ProfileDialog(
    title: String,
    pkg: String?,
    onDismiss: () -> Unit,
    onProfileApplied: () -> Unit
) {
    val ctx = LocalContext.current
    val currentId = if (pkg != null) {
        AppProfileCache.get(ctx, pkg).takeIf { it >= 0 }
            ?: runCatching { ZenithDaemonClient.getAppsMap() }.getOrNull()?.get(pkg) ?: 0
    } else {
        AppProfileCache.getGlobal(ctx)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF081C24),
        title = {
            Text(title, color = TextColor, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        },
        text = {
            Column {
                for (i in 0 until Profile.count()) {
                    val profileId = Profile.value(i)
                    val selected = Profile.indexOf(currentId) == i
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (selected) Modifier.background(
                                    androidx.compose.ui.graphics.Brush.linearGradient(
                                        listOf(GradStart, GradEnd)
                                    ), RoundedCornerShape(8.dp)
                                ).padding(1.5.dp) else Modifier
                            )
                            .background(SurfaceColor, RoundedCornerShape(8.dp))
                            .clickable {
                                val ok = if (pkg != null) {
                                    AppProfileCache.set(ctx, pkg, profileId)
                                    ZenithDaemonClient.setAppProfile(pkg, profileId)
                                } else {
                                    ThermalController.applyGlobal(ctx, profileId)
                                }
                                onProfileApplied()
                                onDismiss()
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            Profile.MENU_NAMES[i], color = if (selected) Color.White else TextColor,
                            fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 2
                        )
                        Text(
                            if (selected) "●" else "○",
                            color = if (selected) AccentColor else MutedColor,
                            fontSize = 18.sp
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
private fun MenuDialog(
    showSystem: Boolean,
    onToggleSystem: (Boolean) -> Unit,
    onResetProfiles: () -> Unit,
    onGlobalProfile: () -> Unit,
    onBenchmark: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceColor,
        title = null,
        text = {
            Column {
                MenuItem("Show System Apps") { onToggleSystem(!showSystem) }
                MenuItem("Reset Per-App Profiles") { onResetProfiles() }
                MenuItem("Global Profile") { onGlobalProfile() }
                MenuItem("About") { onDismiss() }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    Text(
        text, color = TextColor, fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp)
    )
}