package com.zenith.thermal

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.zenith.thermal.ui.navigation.Screen
import com.zenith.thermal.ui.theme.ZenithTheme

/**
 * Main entry — hosts the Compose NavHost.
 * Legacy dialog/profile logic is migrated to composable screens.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_BATTERY = "zenith_battery"
    }

    private val batteryPrefs by lazy { getSharedPreferences(PREFS_BATTERY, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cleanupLegacyNotificationChannels()

        setContent {
            ZenithTheme {
                ZenithApp(
                    onOpenBenchmark = {
                        // Navigate to benchmark screen
                    }
                )
            }
        }
    }

    private fun cleanupLegacyNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            listOf(
                "zenith_battery", "zenith_battery_monitor",
                "battery_monitor_channel", "zenith_refresh"
            ).forEach { runCatching { nm.deleteNotificationChannel(it) } }
            nm.notificationChannels
                .filter {
                    it.id != "battery_monitor" && (
                        it.name.toString().contains("Zenith Battery Monitor", true) ||
                        it.name.toString().contains("Zenith Refresh Rate", true) ||
                        it.name.toString().equals("Battery Monitor", true)
                    )
                }
                .forEach { runCatching { nm.deleteNotificationChannel(it.id) } }
        }
    }
}
