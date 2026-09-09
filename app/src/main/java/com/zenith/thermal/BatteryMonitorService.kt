package com.zenith.thermal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * Foreground service that displays battery stats from zenithd.
 * All battery data comes from the daemon via MSG_GET_STATUS.
 * User settings (reset target, temperature unit, etc) are kept in SharedPreferences.
 */
class BatteryMonitorService : Service() {

    companion object {
        private const val TAG = "BatteryMonService"
        private const val CHANNEL = "battery_monitor"
        private const val NOTIFICATION_ID = 102
        private const val POLL_INTERVAL_MS = 3_000L
        const val ACTION_STOP = "com.zenith.thermal.action.STOP_BATTERY_MONITOR"
        const val ACTION_RESET = "com.zenith.thermal.action.RESET_BATTERY_STATS"
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    private lateinit var notificationManager: NotificationManager

    private val poll = object : Runnable {
        override fun run() {
            if (destroyed) return
            updateNotification()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Battery Monitor", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Battery current, power, temperature from daemon"
                setShowBadge(false)
            }
        )

        val notification = buildNotification("Starting…")
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

    private fun updateNotification() {
        val status = ZenithDaemonClient.getStatus()
        if (status == null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification("Daemon unavailable"))
            return
        }
        val prefs = getSharedPreferences("zenith_battery", MODE_PRIVATE)
        val showPower = prefs.getBoolean("show_power", true)
        val tempUnit = prefs.getString("temperature_unit", "C") ?: "C"
        val charging = status.batteryCharging
        val level = status.batteryCapacityPct.toInt().coerceIn(0, 100)
        val tempC = status.thermalZones.firstOrNull()?.tempC ?: 0.0
        val (tempVal, tempSuffix) = when (tempUnit) {
            "F" -> tempC * 9.0 / 5.0 + 32.0 to "°F"
            "K" -> tempC + 273.15 to "K"
            else -> tempC to "°C"
        }
        val currentMa = status.batteryCurrentMa
        val drainRate = status.batteryDrainPctPerHr

        val chargeLabel = when {
            !charging -> "Discharging"
            level >= 100 && currentMa <= 50 -> "Full Charge"
            else -> "Charging"
        }

        val nowLine = when {
            charging && level >= 100 && currentMa <= 50 ->
                String.format(Locale.US, "%d%% %.1f%s Full Charge", level, tempVal, tempSuffix)
            charging && showPower ->
                String.format(Locale.US, "⚡%d%% %.1f%s %.0f mA — %s", level, tempVal, tempSuffix, currentMa, chargeLabel)
            charging ->
                String.format(Locale.US, "%d%% %.1f%s — %s", level, tempVal, tempSuffix, chargeLabel)
            showPower ->
                String.format(Locale.US, "%d%% %.1f%s %.0f mA", level, tempVal, tempSuffix, currentMa)
            else ->
                String.format(Locale.US, "%d%% %.1f%s", level, tempVal, tempSuffix)
        }

        val body = if (!charging) {
            String.format(Locale.US, "%s\nDrain: %.1f%%/hr", nowLine, drainRate)
        } else nowLine

        notificationManager.notify(NOTIFICATION_ID, buildNotification(body))
    }

    private fun buildNotification(body: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle("Battery Monitor")
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
                // Reset is a no-op now; daemon manages its own state.
                // Kept for backward compat with any external callers.
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
