package com.zenith.thermal

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zenith.thermal.ui.navigation.Screen
import com.zenith.thermal.ui.screens.BatteryScreen
import com.zenith.thermal.ui.screens.DashboardScreen
import com.zenith.thermal.ui.screens.RecordScreen
import com.zenith.thermal.ui.screens.ThermalScreen
import com.zenith.thermal.ui.theme.ZenithAccent
import com.zenith.thermal.ui.theme.ZenithBg
import com.zenith.thermal.ui.theme.ZenithTheme

private val AccentBlue = ZenithAccent
private val UnselectedNav = Color(0xFF8A8FA3)

@Composable
fun ZenithApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        containerColor = ZenithBg,
        bottomBar = {
            NavigationBar(
                containerColor = ZenithBg,
                contentColor = AccentBlue,
                tonalElevation = 0.dp
            ) {
                val items = listOf(
                    Screen.Dashboard to "Dashboard",
                    Screen.Thermal to "Thermal",
                    Screen.Battery to "Battery",
                    Screen.Record to "Record"
                )
                items.forEach { (screen, label) ->
                    val selected = currentRoute == screen.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(Screen.Dashboard.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = when (screen) {
                                    Screen.Dashboard -> Icons.Outlined.Speed
                                    Screen.Thermal -> Icons.Outlined.Whatshot
                                    Screen.Battery -> Icons.Outlined.BatteryFull
                                    Screen.Record -> Icons.Outlined.Leaderboard
                                },
                                contentDescription = label
                            )
                        },
                        label = {
                            Text(
                                label,
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentBlue,
                            selectedTextColor = AccentBlue,
                            unselectedIconColor = UnselectedNav,
                            unselectedTextColor = UnselectedNav,
                            indicatorColor = AccentBlue.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Dashboard.route) { DashboardScreen() }
            composable(Screen.Thermal.route) { ThermalScreen() }
            composable(Screen.Battery.route) { BatteryScreen() }
            composable(Screen.Record.route) { RecordScreen() }
        }
    }
}