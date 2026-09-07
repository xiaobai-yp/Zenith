package com.zenith.thermal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SHUTDOWN -> {
                val prefs = context.getSharedPreferences("zenith_battery", Context.MODE_PRIVATE)
                if (prefs.getBoolean("reset_on_restart", false)) {
                    context.getSharedPreferences("zenith_battery_stats", Context.MODE_PRIVATE)
                        .edit().clear().apply()
                }
                return
            }
            Intent.ACTION_BOOT_COMPLETED -> Unit
            else -> return
        }

        val thermal = Intent(context, AppMonitorService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(thermal)
            else context.startService(thermal)
        }

        val prefs = context.getSharedPreferences("zenith_battery", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", true)) return
        if (prefs.getBoolean("reset_on_restart", false)) {
            context.getSharedPreferences("zenith_battery_stats", Context.MODE_PRIVATE).edit()
                .clear().apply()
        }

        val battery = Intent(context, BatteryMonitorService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(battery)
            else context.startService(battery)
        }
    }
}
