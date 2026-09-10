package com.zenith.thermal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SHUTDOWN -> {
                // Reset on shutdown if enabled — wait briefly for daemon pipe
                val prefs = context.getSharedPreferences("zenith_battery", Context.MODE_PRIVATE)
                if (prefs.getBoolean("reset_on_restart", false)) {
                    // AppMonitorService.start() is called below, but ensureDaemon is async.
                    // Try sending immediately; if daemon pipe not ready yet, it will be
                    // retried by BatteryMonitorService poll loop.
                    AppMonitorService.start(context)
                    Thread.sleep(300) // brief wait for pipe attach
                    if (ZenithDaemonClient.isConnected) {
                        ZenithDaemonClient.sendCommand("reset_battery")
                    }
                }
                return
            }
            Intent.ACTION_BOOT_COMPLETED -> Unit
            else -> return
        }

        // zenithd is already running from init.rc; the app service just maintains
        // the socket connection and reconnects on daemon restart.
        AppMonitorService.start(context)

        val prefs = context.getSharedPreferences("zenith_battery", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", true)) return

        val battery = Intent(context, BatteryMonitorService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(battery)
            else context.startService(battery)
        }
    }
}