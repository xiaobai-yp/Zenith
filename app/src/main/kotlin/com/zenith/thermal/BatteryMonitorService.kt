package com.zenith.thermal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject

/**
 * Foreground service — battery stats notification only.
 * All accounting logic lives in daemon's battery_monitor.rs.
 * Thermal switching lives in ThermalController.kt — do NOT add thermal logic here.
 */
class BatteryMonitorService : Service() {

    companion object {
        private const val TAG = "BatteryMonService"
        private const val CHANNEL = "battery_monitor"
        private const val CHANNEL_WARN = "battery_warning"
        private const val NOTIFICATION_ID = 102
        private const val NOTIFICATION_ID_WARN = 103
        private const val POLL_INTERVAL_MS = 1_000L
        const val ACTION_STOP = "com.zenith.thermal.action.STOP_BATTERY_MONITOR"
        const val ACTION_RESET = "com.zenith.thermal.action.RESET_BATTERY_STATS"
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    private lateinit var notificationManager: NotificationManager
    private var wasCharging = false
    private var lastLevel = -1
    private var restartResetSent = false

    private val poll = object : Runnable {
        override fun run() {
            if (destroyed) return
            checkAutoReset()
            updateNotification()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Battery Monitor", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Battery stats from daemon"
                setShowBadge(false)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_WARN, "Battery Warning", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Idle drain warning"
                setShowBadge(false)
            }
        )

        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        wasCharging = bm.isCharging
        lastLevel = getCurrentLevel()

        val notification = buildNotification("Loading...")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            handler.post(poll)
        } catch (_: SecurityException) { stopSelf() }
          catch (_: RuntimeException) { stopSelf() }
    }

    private fun checkAutoReset() {
        val prefs = getSharedPreferences("zenith_battery", MODE_PRIVATE)
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val isCharging = bm.isCharging
        val level = getCurrentLevel()

        if (!restartResetSent && prefs.getBoolean("reset_on_restart", false) && ZenithDaemonClient.isConnected) {
            ZenithDaemonClient.sendCommand("reset_battery")
            restartResetSent = true
            Log.i(TAG, "Auto-reset: on restart (deferred until daemon attached)")
        }
        if (prefs.getBoolean("reset_on_plugged", false) && isCharging && !wasCharging) {
            ZenithDaemonClient.sendCommand("reset_battery")
            Log.i(TAG, "Auto-reset: charger connected")
        }
        if (wasCharging && !isCharging) {
            ZenithDaemonClient.sendCommand("reset_battery")
            Log.i(TAG, "Auto-reset: unplugged")
        }
        wasCharging = isCharging

        if (prefs.getBoolean("reset_on_target", false)) {
            val target = prefs.getInt("reset_target", 100)
            if (lastLevel >= target && level < target) {
                ZenithDaemonClient.sendCommand("reset_battery")
                Log.i(TAG, "Auto-reset: battery below target $target%")
            }
        }
        lastLevel = level
    }

    private fun updateNotification() {
        val prefs = getSharedPreferences("zenith_battery", MODE_PRIVATE)
        val tempUnit = prefs.getString("temperature_unit", "C") ?: "C"
        val args = JSONObject()
            .put("temp_unit", tempUnit)
            .put("show_power", true)

        val response = ZenithDaemonClient.sendCommand("battery_notif", args)
        if (response == null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification("Daemon unavailable"))
            return
        }
        try {
            val data = response.optJSONObject("data")
            val body = data?.optString("body", "No data") ?: "No data"
            notificationManager.notify(NOTIFICATION_ID, buildNotification(body))
            checkIdleDrainWarning(data)
        } catch (_: Exception) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification("Parse error"))
        }
    }

    private fun checkIdleDrainWarning(data: JSONObject?) {
        val prefs = getSharedPreferences("zenith_battery", MODE_PRIVATE)
        if (!prefs.getBoolean("idle_warning_enabled", false)) return

        val drain = data?.optJSONObject("drain") ?: return
        val idleDrain = drain.optDouble("idle_drain_pct_per_hr", 0.0)
        val threshold = prefs.getInt("idle_warning_target", 5).toDouble()

        if (idleDrain > threshold) {
            val warnBody = "Idle drain ${String.format("%.1f", idleDrain)}%/hr exceeds ${threshold.toInt()}%/hr threshold"
            val warnNotif = Notification.Builder(this, CHANNEL_WARN)
                .setSmallIcon(R.drawable.ic_battery)
                .setContentTitle("Battery Drain Warning")
                .setContentText(warnBody)
                .setStyle(Notification.BigTextStyle().bigText(warnBody))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .build()
            notificationManager.notify(NOTIFICATION_ID_WARN, warnNotif)
        } else {
            notificationManager.cancel(NOTIFICATION_ID_WARN)
        }
    }

    private fun getCurrentLevel(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
    }

    private fun buildNotification(body: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentText(body.lineSequence().firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESET -> {
                ZenithDaemonClient.sendCommand("reset_battery")
                updateNotification()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
