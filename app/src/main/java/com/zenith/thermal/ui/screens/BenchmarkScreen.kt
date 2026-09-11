package com.zenith.thermal.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TextColor = Color(0xFFF2F5F6)
private val MutedColor = Color(0xFFB8C6CA)

@Composable
fun BenchmarkScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Benchmark", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextColor)
        Spacer(Modifier.height(8.dp))
        Text("FPS chart migration in progress", fontSize = 14.sp, color = MutedColor)
    }
}