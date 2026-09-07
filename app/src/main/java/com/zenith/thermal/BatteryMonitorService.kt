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
import android.os.PowerManager
import android.content.pm.ServiceInfo
import android.os.SystemClock
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.abs

class BatteryMonitorService : Service() {
    companion object {
        const val FULL_CHARGE_CURRENT_THRESHOLD_MA = 50

        private const val CHANNEL = "battery_monitor"
        private const val NOTIFICATION_ID = 102
        private const val IDLE_WARNING_NOTIFICATION_ID = 103
        private const val IDLE_WARNING_CHANNEL = "battery_idle_warning"
        private const val INTERVAL_MS = 1000L
        private const val BATTERY_STATS_CACHE_MS = 3000L
        const val ACTION_STOP = "com.zenith.thermal.action.STOP_BATTERY_MONITOR"
        const val ACTION_RESET = "com.zenith.thermal.action.RESET_BATTERY_STATS"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var batteryManager: BatteryManager
    private lateinit var powerManager: PowerManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var prefs: android.content.SharedPreferences

    private var screenOnTotalMs = 0L
    private var screenOffTotalMs = 0L
    private var deepTotalMs = 0L
    private var awakeTotalMs = 0L
    private var activeMahTotal = 0.0
    private var idleMahTotal = 0.0

    private var stateInitialized = false
    private var stateScreenOn = false
    private var screenOnStartElapsed = 0L
    private var screenOffStartElapsed = 0L
    private var screenOnStartUptime = 0L
    private var screenOffStartUptime = 0L
    private var battOnStartPct = -1
    private var battOffStartPct = -1
    private var battOnStartMah = Double.NaN
    private var battOffStartMah = Double.NaN

    private var lastElapsed = 0L
    private var lastUptime = 0L
    private var lastCharging = false
    private var chargingPaused = false
    private var foregroundStarted = false
    private var idleWarningNotified = false
    private var targetResetTriggered = false
    private var lastResetTarget = -1

    private var batteryStatsLastReadMs = 0L
    private var batteryStatsScreenOnMah = Double.NaN
    private var batteryStatsScreenOffMah = Double.NaN

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> handleScreenStateChanged(true)
                Intent.ACTION_SCREEN_OFF -> handleScreenStateChanged(false)
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            updateNotification()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        notificationManager = getSystemService(NotificationManager::class.java)
        prefs = getSharedPreferences("zenith_battery_stats", MODE_PRIVATE)

        restoreState()

        listOf("zenith_battery", "zenith_battery_monitor", "battery_monitor_channel", "zenith_refresh")
            .forEach { runCatching { notificationManager.deleteNotificationChannel(it) } }
        notificationManager.notificationChannels
            .filter { it.id != CHANNEL && (
                it.name.toString().contains("Zenith Battery Monitor", true) ||
                it.name.toString().contains("Zenith Refresh Rate", true) ||
                it.name.toString().equals("Battery Monitor", true)
            ) }
            .forEach { runCatching { notificationManager.deleteNotificationChannel(it.id) } }

        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Battery Monitor", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Realtime battery current, power, temperature and screen statistics"
                setShowBadge(false)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(IDLE_WARNING_CHANNEL, "Battery Monitor Warnings", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Warnings when idle battery drain exceeds the configured threshold"
                setShowBadge(true)
            }
        )

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") registerReceiver(screenReceiver, filter)
        }

        val notification = buildNotification(null, "Starting…")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            reconcileChargingStateOnStart()
            initializeStateIfNeeded()
            handler.post(poll)
        } catch (_: SecurityException) {
            stopSelf()
        } catch (_: RuntimeException) {
            stopSelf()
        }
    }

    private fun reconcileChargingStateOnStart() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowUptime = SystemClock.uptimeMillis()
        val actualCharging = isCharging()

        // A service process can be recreated while the device is charging.
        // Never carry a pre-charge screen interval across that charging period.
        if (!actualCharging) return

        if (!lastCharging) {
            // Recover only the interval up to the last persisted poll. The time
            // between that checkpoint and service recreation is deliberately not
            // attributed to discharge because it may include an unknown charging interval.
            finalizeDischargeAt(lastElapsed.takeIf { it > 0L } ?: nowElapsed,
                lastUptime.takeIf { it > 0L } ?: nowUptime)
        }

        chargingPaused = true
        screenOnStartElapsed = 0L
        screenOffStartElapsed = 0L
        screenOnStartUptime = 0L
        screenOffStartUptime = 0L
        battOnStartPct = -1
        battOffStartPct = -1
        battOnStartMah = Double.NaN
        battOffStartMah = Double.NaN
        lastElapsed = nowElapsed
        lastUptime = nowUptime
        lastCharging = true
        persistState()
    }

    private fun restoreState() {
        screenOnTotalMs = prefs.getLong("screen_on_total", prefs.getLong("screen_on_ms", 0L))
        screenOffTotalMs = prefs.getLong("screen_off_total", prefs.getLong("screen_off_ms", 0L))
        deepTotalMs = prefs.getLong("deep_total", prefs.getLong("deep_sleep_ms", 0L))
        awakeTotalMs = prefs.getLong("awake_total", prefs.getLong("awake_ms", 0L))
        activeMahTotal = prefs.getFloat("active_mah_total", prefs.getFloat("active_mah", 0f)).toDouble()
        idleMahTotal = prefs.getFloat("idle_mah_total", prefs.getFloat("idle_mah", 0f)).toDouble()

        stateInitialized = prefs.getBoolean("state_initialized", false)
        stateScreenOn = prefs.getBoolean("state_screen_on", isScreenOn())
        screenOnStartElapsed = prefs.getLong("screen_on_start", 0L)
        screenOffStartElapsed = prefs.getLong("screen_off_start", 0L)
        screenOnStartUptime = prefs.getLong("screen_on_start_uptime", 0L)
        screenOffStartUptime = prefs.getLong("screen_off_start_uptime", 0L)
        battOnStartPct = prefs.getInt("batt_on_start", -1)
        battOffStartPct = prefs.getInt("batt_off_start", -1)
        battOnStartMah = prefs.getFloat("batt_on_start_mah", Float.NaN).toDouble()
        battOffStartMah = prefs.getFloat("batt_off_start_mah", Float.NaN).toDouble()
        lastElapsed = prefs.getLong("last_elapsed", 0L)
        lastUptime = prefs.getLong("last_uptime", 0L)
        lastCharging = prefs.getBoolean("last_charging", isCharging())
        chargingPaused = prefs.getBoolean("charging_paused", false)
        targetResetTriggered = prefs.getBoolean("target_reset_triggered", false)
        lastResetTarget = prefs.getInt("last_reset_target", -1)
    }

    private fun persistState() {
        prefs.edit()
            .putLong("screen_on_total", screenOnTotalMs)
            .putLong("screen_off_total", screenOffTotalMs)
            .putLong("deep_total", deepTotalMs)
            .putLong("awake_total", awakeTotalMs)
            .putFloat("active_mah_total", activeMahTotal.toFloat())
            .putFloat("idle_mah_total", idleMahTotal.toFloat())
            .putBoolean("state_initialized", stateInitialized)
            .putBoolean("state_screen_on", stateScreenOn)
            .putLong("screen_on_start", screenOnStartElapsed)
            .putLong("screen_off_start", screenOffStartElapsed)
            .putLong("screen_on_start_uptime", screenOnStartUptime)
            .putLong("screen_off_start_uptime", screenOffStartUptime)
            .putInt("batt_on_start", battOnStartPct)
            .putInt("batt_off_start", battOffStartPct)
            .putFloat("batt_on_start_mah", battOnStartMah.toFloat())
            .putFloat("batt_off_start_mah", battOffStartMah.toFloat())
            .putLong("last_elapsed", lastElapsed)
            .putLong("last_uptime", lastUptime)
            .putBoolean("last_charging", lastCharging)
            .putBoolean("charging_paused", chargingPaused)
            .putBoolean("target_reset_triggered", targetResetTriggered)
            .putInt("last_reset_target", lastResetTarget)
            .apply()
    }

    private fun initializeStateIfNeeded() {
        if (stateInitialized && screenOnStartElapsed > 0L || stateInitialized && screenOffStartElapsed > 0L) return
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowUptime = SystemClock.uptimeMillis()
        stateScreenOn = isScreenOn()
        stateInitialized = true

        // Charging is outside discharge accounting. Keep the state paused while
        // plugged in and wait for the unplug transition to resume a fresh session.
        if (chargingPaused || isCharging()) {
            screenOnStartElapsed = 0L
            screenOffStartElapsed = 0L
            screenOnStartUptime = 0L
            screenOffStartUptime = 0L
            battOnStartPct = -1
            battOffStartPct = -1
            battOnStartMah = Double.NaN
            battOffStartMah = Double.NaN
            lastElapsed = nowElapsed
            lastUptime = nowUptime
            persistState()
            return
        }
        if (stateScreenOn) {
            screenOnStartElapsed = nowElapsed
            screenOnStartUptime = nowUptime
            battOnStartPct = level()
            readBatteryStatsDischarge(force = true)
            battOnStartMah = batteryStatsScreenOnMah
        } else {
            screenOffStartElapsed = nowElapsed
            screenOffStartUptime = nowUptime
            battOffStartPct = level()
            readBatteryStatsDischarge(force = true)
            battOffStartMah = batteryStatsScreenOffMah
        }
        lastElapsed = nowElapsed
        lastUptime = nowUptime
        lastCharging = isCharging()
        persistState()
    }

    private fun handleScreenStateChanged(screenOn: Boolean) {
        if (!stateInitialized) {
            initializeStateIfNeeded()
            return
        }
        if (stateScreenOn == screenOn) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val nowUptime = SystemClock.uptimeMillis()

        if (chargingPaused || isCharging()) {
            stateScreenOn = screenOn
            screenOnStartElapsed = 0L
            screenOffStartElapsed = 0L
            screenOnStartUptime = 0L
            screenOffStartUptime = 0L
            battOnStartPct = -1
            battOffStartPct = -1
            battOnStartMah = Double.NaN
            battOffStartMah = Double.NaN
            lastElapsed = nowElapsed
            lastUptime = nowUptime
            persistState()
            return
        }

        readBatteryStatsDischarge(force = true)

        if (stateScreenOn) {
            val elapsed = (nowElapsed - screenOnStartElapsed).coerceAtLeast(0L)
            screenOnTotalMs += elapsed
            activeMahTotal += currentSessionMah(true)
        } else {
            val elapsed = (nowElapsed - screenOffStartElapsed).coerceAtLeast(0L)
            val uptime = (nowUptime - screenOffStartUptime).coerceIn(0L, elapsed)
            screenOffTotalMs += elapsed
            deepTotalMs += (elapsed - uptime).coerceAtLeast(0L)
            awakeTotalMs += uptime
            idleMahTotal += currentSessionMah(false)
        }

        stateScreenOn = screenOn
        if (screenOn) {
            screenOnStartElapsed = nowElapsed
            screenOnStartUptime = nowUptime
            screenOffStartElapsed = 0L
            screenOffStartUptime = 0L
            battOnStartPct = level()
            battOffStartPct = -1
            battOnStartMah = batteryStatsScreenOnMah
            battOffStartMah = Double.NaN
        } else {
            screenOffStartElapsed = nowElapsed
            screenOffStartUptime = nowUptime
            screenOnStartElapsed = 0L
            screenOnStartUptime = 0L
            battOffStartPct = level()
            battOnStartPct = -1
            battOffStartMah = batteryStatsScreenOffMah
            battOnStartMah = Double.NaN
        }
        lastElapsed = nowElapsed
        lastUptime = nowUptime
        persistState()
    }

    private fun finalizeDischargeAt(endElapsed: Long, endUptime: Long) {
        if (!stateInitialized || chargingPaused) return

        readBatteryStatsDischarge(force = true)

        if (stateScreenOn && screenOnStartElapsed > 0L && endElapsed >= screenOnStartElapsed) {
            val elapsed = (endElapsed - screenOnStartElapsed).coerceAtLeast(0L)
            screenOnTotalMs += elapsed
            activeMahTotal += currentSessionMah(true)
        } else if (!stateScreenOn && screenOffStartElapsed > 0L && endElapsed >= screenOffStartElapsed) {
            val elapsed = (endElapsed - screenOffStartElapsed).coerceAtLeast(0L)
            val uptime = if (endUptime >= screenOffStartUptime) {
                (endUptime - screenOffStartUptime).coerceIn(0L, elapsed)
            } else 0L
            screenOffTotalMs += elapsed
            deepTotalMs += (elapsed - uptime).coerceAtLeast(0L)
            awakeTotalMs += uptime
            idleMahTotal += currentSessionMah(false)
        }
    }

    private fun pauseDischargeAccounting(nowElapsed: Long, nowUptime: Long) {
        if (chargingPaused) return
        finalizeDischargeAt(nowElapsed, nowUptime)

        screenOnStartElapsed = 0L
        screenOffStartElapsed = 0L
        screenOnStartUptime = 0L
        screenOffStartUptime = 0L
        battOnStartPct = -1
        battOffStartPct = -1
        battOnStartMah = Double.NaN
        battOffStartMah = Double.NaN
        chargingPaused = true
        lastElapsed = nowElapsed
        lastUptime = nowUptime
        persistState()
    }

    private fun resumeDischargeAccounting(nowElapsed: Long, nowUptime: Long) {
        chargingPaused = false
        stateScreenOn = isScreenOn()
        readBatteryStatsDischarge(force = true)

        if (stateScreenOn) {
            screenOnStartElapsed = nowElapsed
            screenOnStartUptime = nowUptime
            screenOffStartElapsed = 0L
            screenOffStartUptime = 0L
            battOnStartPct = level()
            battOffStartPct = -1
            battOnStartMah = batteryStatsScreenOnMah
            battOffStartMah = Double.NaN
        } else {
            screenOffStartElapsed = nowElapsed
            screenOffStartUptime = nowUptime
            screenOnStartElapsed = 0L
            screenOnStartUptime = 0L
            battOffStartPct = level()
            battOnStartPct = -1
            battOffStartMah = batteryStatsScreenOffMah
            battOnStartMah = Double.NaN
        }
        lastElapsed = nowElapsed
        lastUptime = nowUptime
        persistState()
    }

    private fun currentSessionMah(screenOn: Boolean): Double {
        val start = if (screenOn) battOnStartMah else battOffStartMah
        val now = if (screenOn) batteryStatsScreenOnMah else batteryStatsScreenOffMah
        if (!start.isNaN() && !now.isNaN()) return (now - start).coerceAtLeast(0.0)

        val startPct = if (screenOn) battOnStartPct else battOffStartPct
        val currentPct = level()
        val capacity = capacityMah().coerceAtLeast(1.0)
        return if (startPct >= 0 && currentPct >= 0 && startPct > currentPct) {
            (startPct - currentPct) * capacity / 100.0
        } else 0.0
    }

    private fun totalMah(screenOn: Boolean): Double {
        if (chargingPaused || isCharging()) {
            return if (screenOn) activeMahTotal else idleMahTotal
        }
        return if (screenOn) activeMahTotal + currentSessionMah(true)
        else idleMahTotal + currentSessionMah(false)
    }

    private fun readLong(path: String): Long? = try {
        File(path).readText().trim().toLongOrNull()
    } catch (_: Throwable) { null }

    private fun firstLong(vararg paths: String): Long? {
        for (path in paths) readLong(path)?.let { return it }
        return null
    }

    private fun firstPositiveLong(vararg paths: String): Long? {
        for (path in paths) {
            val value = readLong(path) ?: continue
            if (value > 0L) return value
        }
        return null
    }

    private fun currentMicroAmps(): Long {
        val api = runCatching {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toLong()
        }.getOrDefault(0L)
        if (api != 0L && api != Int.MIN_VALUE.toLong()) return api
        return firstLong(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/bms/current_now",
            "/sys/class/power_supply/main_battery/current_now"
        ) ?: 0L
    }

    private fun currentMa(): Double {
        val raw = abs(currentMicroAmps()).toDouble()
        return if (raw < 10_000.0) raw else raw / 1000.0
    }

    private fun voltageV(): Double {
        val raw = firstPositiveLong(
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/bms/voltage_now",
            "/sys/class/power_supply/main_battery/voltage_now",
            "/sys/class/power_supply/usb/voltage_now",
            "/sys/class/power_supply/wireless/voltage_now"
        )
        if (raw != null) {
            return when {
                raw >= 100_000L -> raw / 1_000_000.0
                raw >= 1_000L -> raw / 1000.0
                else -> 0.0
            }
        }
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val mv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.toDouble() ?: 0.0
        return if (mv > 100.0) mv / 1000.0 else 0.0
    }

    private fun temperatureDisplay(tempC: Double): Pair<Double, String> {
        val unit = getSharedPreferences("zenith_battery", MODE_PRIVATE)
            .getString("temperature_unit", "C") ?: "C"
        return when (unit) {
            "F" -> tempC * 9.0 / 5.0 + 32.0 to "°F"
            "K" -> tempC + 273.15 to "K"
            else -> tempC to "°C"
        }
    }

    private fun temperatureC(): Double {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        if (tenths > 0) return tenths / 10.0
        val powerPaths = listOf(
            "/sys/class/power_supply/battery/temp",
            "/sys/class/power_supply/bms/temp",
            "/sys/class/power_supply/main_battery/temp",
            "/sys/class/power_supply/battery/battery_temp",
            "/sys/class/power_supply/battery/temperature",
            "/sys/class/power_supply/bms/temp_now"
        )
        for (path in powerPaths) {
            val raw = readLong(path) ?: continue
            if (raw > 0L) return when {
                raw >= 10_000L -> raw / 1000.0
                raw >= 1000L -> raw / 100.0
                else -> raw / 10.0
            }
        }
        return 0.0
    }

    private fun level(): Int = runCatching {
        batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
    }.getOrDefault(0)

    private fun capacityMah(): Double {
        val raw = firstLong(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/battery/charge_full",
            "/sys/class/power_supply/bms/charge_full_design",
            "/sys/class/power_supply/bms/charge_full"
        ) ?: 0L
        return when {
            raw >= 100_000L -> raw / 1000.0
            raw > 0L -> raw.toDouble()
            else -> 5000.0
        }
    }

    private fun isCharging(): Boolean {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun isScreenOn(): Boolean = if (Build.VERSION.SDK_INT >= 20) {
        powerManager.isInteractive
    } else {
        @Suppress("DEPRECATION") powerManager.isScreenOn
    }

    private fun duration(ms: Long): String {
        val sec = ms.coerceAtLeast(0L) / 1000L
        val h = sec / 3600L
        val m = (sec % 3600L) / 60L
        val s = sec % 60L
        return when {
            sec == 0L -> "0s"
            h > 0L -> String.format(Locale.US, "%dh %02dm %02ds", h, m, s)
            m > 0L -> String.format(Locale.US, "%dm %02ds", m, s)
            else -> String.format(Locale.US, "%ds", s)
        }
    }

    private fun readBatteryStatsDischarge(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - batteryStatsLastReadMs < BATTERY_STATS_CACHE_MS) return
        batteryStatsLastReadMs = now

        val output = runCatching {
            val process = ProcessBuilder("su", "-c", "dumpsys batterystats --charged")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(1500L, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull() ?: return

        fun parseMah(label: String): Double? {
            val pattern = Pattern.compile(
                "(?m)^\\s*" + Pattern.quote(label) +
                    ":\\s*([0-9]+(?:\\.[0-9]+)?)\\s*mAh\\s*$",
                Pattern.CASE_INSENSITIVE
            )
            val match = pattern.matcher(output)
            return if (match.find()) match.group(1)?.toDoubleOrNull() else null
        }

        parseMah("Screen on discharge")?.let { batteryStatsScreenOnMah = it }
        parseMah("Screen off discharge")?.let { batteryStatsScreenOffMah = it }
    }

    private fun resetStats() {
        screenOnTotalMs = 0L
        screenOffTotalMs = 0L
        deepTotalMs = 0L
        awakeTotalMs = 0L
        activeMahTotal = 0.0
        idleMahTotal = 0.0
        stateInitialized = false
        battOnStartPct = -1
        battOffStartPct = -1
        battOnStartMah = Double.NaN
        battOffStartMah = Double.NaN
        prefs.edit().clear().apply()
        batteryStatsScreenOnMah = Double.NaN
        batteryStatsScreenOffMah = Double.NaN
        batteryStatsLastReadMs = 0L
        idleWarningNotified = false
        notificationManager.cancel(IDLE_WARNING_NOTIFICATION_ID)
        initializeStateIfNeeded()
    }

    private fun updateNotification() {
        initializeStateIfNeeded()

        val nowElapsed = SystemClock.elapsedRealtime()
        val nowUptime = SystemClock.uptimeMillis()
        val charging = isCharging()
        val level = level()

        val batteryPrefs = getSharedPreferences("zenith_battery", MODE_PRIVATE)
        val autoReset = batteryPrefs.getBoolean("reset_on_plugged", false)
        val resetOnTarget = batteryPrefs.getBoolean("reset_on_target", true)
        val resetTarget = batteryPrefs.getInt("reset_target", 100).coerceIn(1, 100)

        if (lastResetTarget != resetTarget) {
            lastResetTarget = resetTarget
            targetResetTriggered = false
        }

        if (charging != lastCharging) {
            if (charging) {
                if (autoReset) {
                    chargingPaused = false
                    resetStats()
                    targetResetTriggered = resetOnTarget && level >= resetTarget
                    lastResetTarget = resetTarget
                    lastCharging = true
                    persistState()
                    return
                }

                targetResetTriggered = false
                pauseDischargeAccounting(nowElapsed, nowUptime)
            } else {
                if (level < resetTarget) targetResetTriggered = false
                resumeDischargeAccounting(nowElapsed, nowUptime)
            }

            lastCharging = charging
            persistState()
        }

        // Reconcile on every poll so missed battery broadcasts cannot make a
        // charging interval leak into discharge accounting.
        if (charging && !chargingPaused) {
            pauseDischargeAccounting(nowElapsed, nowUptime)
        } else if (!charging && chargingPaused) {
            resumeDischargeAccounting(nowElapsed, nowUptime)
        }

        if (resetOnTarget && !targetResetTriggered && level >= resetTarget) {
            resetStats()
            targetResetTriggered = true
            lastResetTarget = resetTarget
            lastCharging = true
            persistState()
            return
        }

        val actualScreenOn = isScreenOn()
        if (actualScreenOn != stateScreenOn) {
            handleScreenStateChanged(actualScreenOn)
        }

        readBatteryStatsDischarge()

        val current = currentMa()
        val voltage = voltageV()
        val power = if (current > 0.0 && voltage > 0.0) current * voltage / 1000.0 else 0.0
        val tempC = temperatureC()
        val (temp, tempUnit) = temperatureDisplay(tempC)

        val onCurrentMs = if (!charging && !chargingPaused && stateScreenOn) {
            (nowElapsed - screenOnStartElapsed).coerceAtLeast(0L)
        } else 0L
        val offCurrentMs = if (!charging && !chargingPaused && !stateScreenOn) {
            (nowElapsed - screenOffStartElapsed).coerceAtLeast(0L)
        } else 0L
        val displayOnMs = screenOnTotalMs + onCurrentMs
        val displayOffMs = screenOffTotalMs + offCurrentMs

        val currentDeep = if (!charging && !chargingPaused && !stateScreenOn) {
            val elapsed = offCurrentMs
            val uptime = (nowUptime - screenOffStartUptime).coerceIn(0L, elapsed)
            (elapsed - uptime).coerceAtLeast(0L)
        } else 0L
        val currentAwake = if (!charging && !chargingPaused && !stateScreenOn) {
            (nowUptime - screenOffStartUptime).coerceAtLeast(0L)
        } else 0L
        val displayDeepMs = (deepTotalMs + currentDeep).coerceAtMost(displayOffMs)
        val displayAwakeMs = (awakeTotalMs + currentAwake).coerceAtMost(displayOffMs)

        val activeMah = totalMah(true)
        val idleMah = totalMah(false)
        val capacity = capacityMah().coerceAtLeast(1.0)
        val activePct = activeMah / capacity * 100.0
        val idlePct = idleMah / capacity * 100.0
        val activeHours = displayOnMs / 3_600_000.0
        val idleHours = displayOffMs / 3_600_000.0
        val activeRate = if (!charging && activeHours > 0.0) activePct / activeHours else 0.0
        val idleRate = if (!charging && idleHours > 0.0) idlePct / idleHours else 0.0

        val deepPct = if (displayOffMs > 0L) displayDeepMs.toDouble() / displayOffMs * 100.0 else 0.0
        val awakePct = if (displayOffMs > 0L) displayAwakeMs.toDouble() / displayOffMs * 100.0 else 0.0

        updateIdleWarning(idleRate, displayOffMs)

        val showPower = getSharedPreferences("zenith_battery", MODE_PRIVATE)
            .getBoolean("show_power", true)
        val status = batteryStatusLabel(charging, level, power, current)
        val nowLine = when {
            charging && status == "Full Charge" -> {
                String.format(Locale.US, "%d%% %.1f%s Full Charge", level, temp, tempUnit)
            }
            charging -> {
                if (showPower) {
                    String.format(Locale.US, "%d%% %.1f%s %s ⚡%.0f mA %.1f W", level, temp, tempUnit, status, current, power)
                } else {
                    String.format(Locale.US, "%d%% %.1f%s %s", level, temp, tempUnit, status)
                }
            }
            else -> {
                if (showPower) {
                    String.format(Locale.US, "%d%% %.1f%s Discharging %.0f mA", level, temp, tempUnit, current)
                } else {
                    String.format(Locale.US, "%d%% %.1f%s Discharging", level, temp, tempUnit)
                }
            }
        }

        val body = String.format(
            Locale.US,
            "Active: %.2f%% /hr • Idle: %.2f%% /hr\n" +
                "Screen on: %s (%.1f%%)\n" +
                "Screen off: %s (%.1f%%)\n" +
                "Deep sleep: %s (%.1f%%)\n" +
                "Awake: %s (%.1f%%)",
            activeRate, idleRate,
            duration(displayOnMs), if (charging) 0.0 else activePct,
            duration(displayOffMs), if (charging) 0.0 else idlePct,
            duration(displayDeepMs), deepPct,
            duration(displayAwakeMs), awakePct
        )

        notificationManager.notify(NOTIFICATION_ID, buildNotification(null, "$nowLine\n$body"))

        lastElapsed = nowElapsed
        lastUptime = nowUptime
        lastCharging = charging
        persistState()
    }

    private fun updateIdleWarning(idleRate: Double, idleObservationMs: Long) {
        val settings = getSharedPreferences("zenith_battery", MODE_PRIVATE)
        val enabled = settings.getBoolean("idle_warning_enabled", false)
        val threshold = settings.getInt("idle_warning_target", 5).coerceIn(1, 100).toDouble()
        if (!enabled || isCharging() || idleObservationMs < 5 * 60 * 1000L || idleRate < threshold) {
            if (idleRate < threshold || !enabled || isCharging() || idleObservationMs < 5 * 60 * 1000L) {
                idleWarningNotified = false
                notificationManager.cancel(IDLE_WARNING_NOTIFICATION_ID)
            }
            return
        }
        if (idleWarningNotified) return
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, IDLE_WARNING_CHANNEL)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle("High idle drain")
            .setContentText(String.format(Locale.US, "Idle drain is %.1f%%/hr (threshold %.0f%%/hr)", idleRate, threshold))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        notificationManager.notify(IDLE_WARNING_NOTIFICATION_ID, notification)
        idleWarningNotified = true
    }

    private fun batteryStatusLabel(charging: Boolean, battery: Int, powerW: Double, current: Double): String {
        if (!charging) return "Discharging"
        if (battery >= 100 && abs(current) <= FULL_CHARGE_CURRENT_THRESHOLD_MA) return "Full Charge"
        return when {
            powerW < 2.0 -> "Charging slowly"
            powerW >= 7.0 -> "Charging rapidly"
            else -> "Charging"
        }
    }

    private fun buildNotification(title: String?, body: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(title)
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
        if (!foregroundStarted) return START_NOT_STICKY
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
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        persistState()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
