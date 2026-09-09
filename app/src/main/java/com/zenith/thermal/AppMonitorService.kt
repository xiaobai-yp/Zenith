package com.zenith.thermal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Foreground service that maintains the ZenithDaemonClient connection.
 * Per-app profile switching is handled by zenithd, not this app.
 */
class AppMonitorService : Service() {

    companion object {
        private const val TAG = "AppMonitorService"
        private const val CHANNEL = "zenith"
        private const val NOTIFICATION_ID = 101
        private const val HEARTBEAT_INTERVAL_MS = 15_000L

        fun start(context: Context) {
            val intent = Intent(context, AppMonitorService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false

    private val connListener: (Boolean) -> Unit = { connected ->
        if (!destroyed) {
            val msg = if (connected) "Connected" else "Disconnected — reconnecting…"
            showNotification(msg)
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (ZenithDaemonClient.isConnected) {
                // Keep-alive: verify connection with a lightweight status check
                ZenithDaemonClient.sendStatus()
            } else {
                Log.i(TAG, "Daemon disconnected, reconnecting…")
                ZenithDaemonClient.requestConnect()
            }
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Zenith Thermal", NotificationManager.IMPORTANCE_LOW)
        )
        showNotification("Connecting…")

        ZenithDaemonClient.addConnectionListener(connListener)
        ZenithDaemonClient.requestConnect()
        showNotification(if (ZenithDaemonClient.isConnected) "Connected" else "Disconnected")
        handler.post(heartbeat)
    }

    private fun showNotification(text: String) {
        if (destroyed) return
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Zenith Thermal")
            .setContentText(text)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        ZenithDaemonClient.removeConnectionListener(connListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}