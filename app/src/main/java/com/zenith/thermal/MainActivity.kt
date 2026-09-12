package com.zenith.thermal

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zenith.thermal.ui.theme.ZenithTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cleanupLegacyNotificationChannels()
        ensureDaemon()

        setContent {
            ZenithTheme {
                ZenithApp()
            }
        }
    }

    /** Ensure zenithd daemon is running (extracted from AppMonitorService). */
    private fun ensureDaemon() {
        Thread {
            try {
                val ok = DaemonManager.ensureDaemon(this)
                android.util.Log.i("MainActivity", "ensureDaemon -> $ok")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "ensureDaemon failed: ${e.message}")
            }
        }.start()
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
