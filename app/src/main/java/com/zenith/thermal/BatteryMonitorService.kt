package com.zenith.thermal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * Foreground service — battery stats notification.
 * Tracks screen on/off time + active/idle drain separately.
 * Resets all stats when charger is connected (same as battery menu reset).
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

    // --- State tracking ---
    private var screenOn = false
    private var lastLevelPct = -1.0
    private var lastPollMs = System.currentTimeMillis()

    // Time accumulators (ms)
    private var screenOnMs = 0L
    private var screenOffMs = 0L
    private var deepSleepMs = 0L
    private var awakeMs = 0L

    // Drain tracking (from daemon)
    private var activeDrainPctPerHr = 0.0
    private var idleDrainPctPerHr = 0.0
    private var isCharging = false

    // Last known values for display
    private var displayLevel = 0
    private var displayTempC = 0.0
    private var displayCurrentMa = 0.0
    private var displayChargeLabel = "Discharging"

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    if (!screenOn) {
                        screenOn = true
                        updateAccumulators()
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    if (screenOn) {
                        screenOn = false
                        updateAccumulators()
                    }
                }
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (destroyed) return
            updateAccumulators()
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

        // Register screen on/off receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)

        // Initial screen state
        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        screenOn = !bm.is Charging // rough guess; actual state comes from receiver

        // Get initial level
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        lastLevelPct = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)?.toDouble() ?: 0.0
        lastPollMs = System.currentTimeMillis()
        displayLevel = lastLevelPct.toInt()

        val notification = buildNotification()
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

    private fun updateAccumulators() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastPollMs
        if (elapsed <= 0) return

        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        val charging = bm.isCharging

        // Detect charge start → reset everything
        if (charging && !isCharging) {
            resetStats()
            isCharging = true
            lastPollMs = now
            lastLevelPct = getCurrentLevel()
            return
        }
        isCharging = charging

        val currentLevel = getCurrentLevel()
        val drainPct = if (lastLevelPct > 0) lastLevelPct - currentLevel else 0.0
        val drainRatePerHr = if (elapsed > 0) drainPct * 3_600_000.0 / elapsed else 0.0

        if (screenOn) {
            screenOnMs += elapsed
            activeDrainPctPerHr = drainRatePerHr
        } else {
            screenOffMs += elapsed
            idleDrainPctPerHr = drainRatePerHr
            // Deep sleep heuristic: if level didn't change significantly
            if (kotlin.math.abs(drainPct) < 0.05) {
                deepSleepMs += elapsed
            } else {
                awakeMs += elapsed
            }
        }

        lastLevelPct = currentLevel
        lastPollMs = now
    }

    private fun getCurrentLevel(): Double {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)?.toDouble() ?: 0.0
    }

    private fun resetStats() {
        screenOnMs = 0L
        screenOffMs = 0L
        deepSleepMs = 0L
        awakeMs = 0L
        activeDrainPctPerHr = 0.0
        idleDrainPctPerHr = 0.0
        Log.i(TAG, "Stats reset (charging started)")
    }

    private fun updateNotification() {
        val status = ZenithDaemonClient.getStatus()
        if (status == null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
            return
        }

        val prefs = getSharedPreferences("zenith_battery", MODE_PRIVATE)
        val tempUnit = prefs.getString("temperature_unit", "C") ?: "C"

        // Battery data from daemon
        val level = status.batteryCapacityPct.toInt().coerceIn(0, 100)
        val tempC = status.thermalZones.firstOrNull()?.tempC ?: 0.0
        val (tempVal, tempSuffix) = when (tempUnit) {
            "F" -> tempC * 9.0 / 5.0 + 32.0 to "°F"
            "K" -> tempC + 273.15 to "K"
            else -> tempC to "°C"
        }
        val currentMa = status.batteryCurrentMa
        val daemonDrain = status.batteryDrainPctPerHr
        val charging = status.batteryCharging

        val chargeLabel = when {
            !charging -> "Discharging"
            level >= 100 && currentMa <= 50 -> "Full Charge"
            else -> "Charging"
        }

        displayLevel = level
        displayTempC = tempVal
        displayCurrentMa = currentMa
        displayChargeLabel = chargeLabel

        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh %dm %ds", h, m, s)
            m > 0 -> String.format(Locale.US, "%dm %ds", m, s)
            else -> "${s}s"
        }
    }

    private fun formatPercent(ms: Long, totalMs: Long): String {
        if (totalMs <= 0) return "0.0"
        return String.format(Locale.US, "%.1f", ms * 100.0 / totalMs)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val totalTimeMs = screenOnMs + screenOffMs

        val body = if (isCharging) {
            // Frozen while charging — show last known state
            String.format(Locale.US,
                "%d%% %.1f°C %s %.0f mA\n" +
                "Active: 0.00%%/hr  Idle: 0.00%%/hr\n" +
                "Screen on: 0s (0%%)\n" +
                "Screen off: 0s (0%%)\n" +
                "Deep sleep: 0s (0.0%%)\n" +
                "Awake: 0s (0.0%%)",
                displayLevel, displayTempC, displayChargeLabel, displayCurrentMa
            )
        } else {
            val activePct = if (totalTimeMs > 0) activeDrainPctPerHr else 0.0
            val idlePct = if (totalTimeMs > 0) idleDrainPctPerHr else 0.0
            String.format(Locale.US,
                "%d%% %.1f°C %s %.0f mA\n" +
                "Active: %.2f%%/hr  Idle: %.2f%%/hr\n" +
                "Screen on: %s (%s%%)\n" +
                "Screen off: %s (%s%%)\n" +
                "Deep sleep: %s (%s%%)\n" +
                "Awake: %s (%s%%)",
                displayLevel, displayTempC, displayChargeLabel, displayCurrentMa,
                activePct, idlePct,
                formatDuration(screenOnMs), formatPercent(screenOnMs, totalTimeMs),
                formatDuration(screenOffMs), formatPercent(screenOffMs, totalTimeMs),
                formatDuration(deepSleepMs), formatPercent(deepSleepMs, totalTimeMs),
                formatDuration(awakeMs), formatPercent(awakeMs, totalTimeMs)
            )
        }

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
                resetStats()
                updateNotification()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
