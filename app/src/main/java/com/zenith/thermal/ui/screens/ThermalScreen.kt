package com.zenith.thermal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.thermal.Profile
import com.zenith.thermal.ThermalController
import com.zenith.thermal.ZenithDaemonClient
import kotlinx.coroutines.delay

private val AccentBlue = Color(0xFF5EA7FF)
private val SurfaceColor = Color(0xFF2A444D)
private val TextColor = Color(0xFFF2F5F6)
private val MutedColor = Color(0xFFB8C6CA)
private val GradStart = Color(0xFF6C3CE0)
private val GradEnd = Color(0xFFE04090)

@Composable
fun ThermalScreen() {
    var status by remember { mutableStateOf<ZenithDaemonClient.StatusResponse?>(null) }
    val ctx = LocalContext.current

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
        Text("Thermal", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextColor)
        Spacer(Modifier.height(18.dp))

        // Active profile banner
        val activeId = status?.currentProfileId ?: 0
        GradientCard {
            Column(Modifier.padding(16.dp)) {
                Text("Active Profile", fontSize = 13.sp, color = MutedColor)
                Text(
                    Profile.NAMES.getOrElse(Profile.indexOf(activeId)) { "Unknown" },
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextColor
                )
                if (status != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${status!!.cpuLoadPct.toInt()}% CPU · GPU ${status!!.gpu?.busyPct ?: 0}% · " +
                            "${status!!.fpsShort} FPS",
                        fontSize = 13.sp, color = MutedColor
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Global Profile", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextColor)
        Spacer(Modifier.height(8.dp))

        // Profile radio list
        Profile.NAMES.forEachIndexed { i, name ->
            val profileId = Profile.value(i)
            val selected = activeId == profileId
            ProfileRow(
                name = name,
                selected = selected,
                onClick = {
                    ThermalController.applyGlobal(ctx, profileId)
                    status = ZenithDaemonClient.getStatus()
                }
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun GradientCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(GradStart, GradEnd)),
                RoundedCornerShape(14.dp)
            )
            .padding(1.5.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceColor, RoundedCornerShape(12.5.dp))
        ) {
            content()
        }
    }
}

@Composable
private fun ProfileRow(name: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.background(
                    Brush.linearGradient(listOf(GradStart, GradEnd)),
                    shape
                ).padding(1.5.dp) else Modifier
            )
            .background(SurfaceColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            fontSize = 15.sp,
            color = if (selected) Color.White else TextColor,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text("●", color = AccentBlue, fontSize = 18.sp)
        } else {
            Text("○", color = MutedColor, fontSize = 18.sp)
        }
    }
}