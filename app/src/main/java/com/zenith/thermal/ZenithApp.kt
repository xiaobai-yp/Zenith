package com.zenith.thermal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zenith.thermal.ui.navigation.Screen
import com.zenith.thermal.ui.screens.BenchmarkScreen
import com.zenith.thermal.ui.screens.BatteryScreen
import com.zenith.thermal.ui.screens.ThermalScreen
import com.zenith.thermal.ui.theme.*

private val BottomBarBg = Color(0xFF102830)
private val AccentBlue = Color(0xFF5EA7FF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZenithApp(onOpenBenchmark: () -> Unit = {}) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        containerColor = ZenithTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = BottomBarBg,
                contentColor = AccentBlue,
                tonalElevation = 0.dp
            ) {
                val items = listOf(
                    Screen.Thermal to "Thermal",
                    Screen.Battery to "Battery",
                    Screen.Benchmark to "Benchmark"
                )
                items.forEach { (screen, label) ->
                    val selected = currentRoute == screen.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(Screen.Thermal.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        label = {
                            Text(
                                label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentBlue,
                            selectedTextColor = AccentBlue,
                            unselectedIconColor = Color(0xFFB8C6CA),
                            unselectedTextColor = Color(0xFFB8C6CA),
                            indicatorColor = AccentBlue.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Thermal.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Thermal.route) { ThermalScreen() }
            composable(Screen.Battery.route) { BatteryScreen() }
            composable(Screen.Benchmark.route) { BenchmarkScreen() }
        }
    }
}
