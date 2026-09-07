package com.zenith.thermal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class AppMonitorService : Service() {

    companion object {
        private const val CHECK_INTERVAL = 500L

        fun applyCurrent(context: Context) {
            val app = findForeground(context) ?: return
            if (app == context.packageName) return

            val store = ProfileStore(context)
            ThermalController.apply(store.app(app) ?: store.global())
        }

        private fun findForeground(context: Context): String? {
            val usage = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return null

            val now = System.currentTimeMillis()
            val events = usage.queryEvents(now - 5000L, now)
            val event = UsageEvents.Event()

            var result: String? = null
            var latest = 0L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)

                @Suppress("DEPRECATION")
                val isForeground = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                if (isForeground && event.timeStamp >= latest
                ) {
                    latest = event.timeStamp
                    result = event.packageName
                }
            }

            return result
        }
    }

    private lateinit var store: ProfileStore
    private val handler = Handler(Looper.getMainLooper())

    private var lastRealApp: String? = null

    private var lastAppliedApp: String? = null

    private val monitor = object : Runnable {
        override fun run() {
            updateForeground()
            handler.postDelayed(this, CHECK_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()

        store = ProfileStore(this)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                "zenith",
                "Zenith Thermal",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val notification = Notification.Builder(this, "zenith")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Zenith Thermal")
            .setContentText("Per-app thermal monitor active")
            .setOngoing(true)
            .build()

        startForeground(101, notification)
        handler.post(monitor)
    }

    private fun updateForeground() {
        val current = findForeground(this) ?: return

        if (current == packageName) return

        if (isTransientSystemUi(current)) {
            reapplyLastRealProfile()
            return
        }

        if (current == lastRealApp) {
            reapplyLastRealProfile()
            return
        }

        lastRealApp = current
        lastAppliedApp = current

        ThermalController.apply(store.app(current) ?: store.global())
    }

    private fun reapplyLastRealProfile() {
        val app = lastRealApp ?: return

        ThermalController.apply(store.app(app) ?: store.global())
        lastAppliedApp = app
    }

    private fun isTransientSystemUi(pkg: String): Boolean {
        if (pkg == "com.android.systemui") return true

        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }

        val resolved = packageManager.resolveActivity(homeIntent, 0)
        val launcher = resolved?.activityInfo?.packageName

        return !launcher.isNullOrEmpty() && pkg == launcher
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
