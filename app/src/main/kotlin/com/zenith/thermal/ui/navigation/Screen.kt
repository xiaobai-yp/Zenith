package com.zenith.thermal.ui.navigation

sealed class Screen(val route: String) {
    data object Dashboard : Screen("dashboard")
    data object Thermal : Screen("thermal")
    data object Battery : Screen("battery")
    data object Record : Screen("record")
}