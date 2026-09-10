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

/**
 * Foreground service — battery stats notification.
 * All data comes from daemon's battery_notif command.
 * Resets when charger connected or user presses reset.
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
                description = "Battery stats from daemon"
                setShowBadge(false)
            }
        )

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

    private fun updateNotification() {
        // Query daemon for formatted battery notification text
        val response = ZenithDaemonClient.sendCommand("battery_notif")
        if (response == null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification("Daemon unavailable"))
            return
        }
        try {
            val data = response.optJSONObject("data")
            val body = data?.optString("body", "No data") ?: "No data"
            notificationManager.notify(NOTIFICATION_ID, buildNotification(body))
        } catch (_: Exception) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification("Parse error"))
        }
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
                // Tell daemon to reset times
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
