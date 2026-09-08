package com.zenith.thermal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.graphics.PixelFormat
import android.view.WindowManager
import java.util.Locale

/**
 * Foreground overlay service that draws a compact HUD showing live FPS,
 * CPU temperature, battery %, current draw, and benchmark status.
 *
 * Start with ACTION_START / ACTION_STOP intents. Tap toggles benchmark.
 * Drag moves the HUD. Long-press dismisses it.
 */
class FloatingHudService : Service() {

    companion object {
        private const val TAG = "FloatingHudService"
        private const val CHANNEL = "floating_hud"
        private const val NOTIFICATION_ID = 103
        private const val POLL_MS = 1_000L
        private const val LONG_PRESS_MS = 800L
        const val ACTION_START = "com.zenith.thermal.action.HUD_START"
        const val ACTION_STOP = "com.zenith.thermal.action.HUD_STOP"

        fun start(context: Context) {
            val intent = Intent(context, FloatingHudService::class.java)
            intent.action = ACTION_START
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingHudService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }
    }

    private lateinit var wm: WindowManager
    private var hudView: HudView? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    private var benchmarkRunning = false

    private val poll = object : Runnable {
        override fun run() {
            if (destroyed) return
            updateHud()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Floating HUD", NotificationManager.IMPORTANCE_LOW).apply {
                description = "FPS, temperature, battery overlay"
                setShowBadge(false)
            }
        )
        showNotification("Starting…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                removeHud()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                showHud()
                handler.post(poll)
            }
        }
        return START_STICKY
    }

    private fun showNotification(body: String) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_thermal)
            .setContentTitle("Floating HUD")
            .setContentText(body)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: SecurityException) { stopSelf() }
          catch (_: RuntimeException) { stopSelf() }
    }

    private fun showHud() {
        if (hudView != null) return
        val view = HudView(this)
        hudView = view
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add overlay: ${e.message}")
            hudView = null
        }
    }

    private fun removeHud() {
        hudView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        hudView = null
    }

    private fun updateHud() {
        val v = hudView ?: return
        // Poll on background thread to avoid blocking UI
        Thread {
            val status = ZenithDaemonClient.getStatus()
            val fps = ZenithDaemonClient.getFps()
            val bench = ZenithDaemonClient.getBenchmarkData()
            benchmarkRunning = bench?.running == true
            handler.post {
                v.updateData(
                    cpuTemp = status?.thermalZones?.firstOrNull()?.tempC,
                    batteryPct = status?.batteryCapacityPct,
                    currentMa = status?.batteryCurrentMa,
                    shortFps = fps?.shortFps?.let { it / 10.0 },
                    longFps = fps?.longFps?.let { it / 10.0 },
                    benchmarkRunning = benchmarkRunning,
                    benchElapsed = bench?.elapsedMs
                )
            }
        }.start()
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        removeHud()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- HUD View ----

    private inner class HudView(context: Context) : View(context) {

        private var shortFps: Double = 0.0
        private var longFps: Double = 0.0
        private var cpuTemp: Double? = null
        private var batteryPct: Double? = null
        private var currentMa: Double? = null
        private var benchRunning = false
        private var benchElapsed: Long? = null

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 24, 24, 32)
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4FC3F7")
            textSize = 32f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B0BEC5")
            textSize = 26f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        private val cornerRadius = 20f
        private val padH = 24f
        private val padV = 16f
        private val lineGap = 4f

        private val lines = mutableListOf<String>()
        private var touchStartX = 0f
        private var touchStartY = 0f
        private var paramX = 0
        private var paramY = 0
        private var dragging = false
        private var longPressRunnable: Runnable? = null

        fun updateData(
            cpuTemp: Double?,
            batteryPct: Double?,
            currentMa: Double?,
            shortFps: Double?,
            longFps: Double?,
            benchmarkRunning: Boolean,
            benchElapsed: Long?
        ) {
            this.cpuTemp = cpuTemp
            this.batteryPct = batteryPct
            this.currentMa = currentMa
            this.shortFps = shortFps ?: 0.0
            this.longFps = longFps ?: 0.0
            this.benchRunning = benchmarkRunning
            this.benchElapsed = benchElapsed
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            lines.clear()
            lines.add("FPS  %.0f / %.0f".format(Locale.US, shortFps, longFps))
            cpuTemp?.let { lines.add("CPU  %.0f°C".format(Locale.US, it)) }
            batteryPct?.let { b ->
                val ma = currentMa?.let { "%.0f".format(Locale.US, it) } ?: "—"
                lines.add("BAT  %d%%  %s mA".format(Locale.US, b.toInt(), ma))
            }
            if (benchRunning) {
                val ms = benchElapsed ?: 0L
                lines.add("BENCH  running %ds".format(Locale.US, ms / 1000))
            } else {
                lines.add("BENCH  idle (tap)")
            }

            val maxW = lines.maxOf { textPaint.measureText(it) }
            val totalH = lines.size * (textPaint.textSize + lineGap)
            val w = maxW + padH * 2
            val h = totalH + padV * 2
            setMeasuredDimension(w.toInt(), h.toInt())

            val rect = RectF(0f, 0f, w, h)
            c.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

            var y = padV + textPaint.textSize
            for (i in lines.indices) {
                val paint = when {
                    i == 0 -> accentPaint
                    lines[i].startsWith("BENCH  running") -> Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#FFB74D"); textSize = 26f
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                    else -> smallPaint
                }
                c.drawText(lines[i], padH, y, paint)
                y += textPaint.textSize + lineGap
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val params = layoutParams as? WindowManager.LayoutParams ?: return false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = ev.rawX
                    touchStartY = ev.rawY
                    paramX = params.x
                    paramY = params.y
                    dragging = false
                    longPressRunnable = Runnable {
                        if (!dragging) {
                            FloatingHudService@this@FloatingHudService.handler.post {
                                stop(FloatingHudService@this@FloatingHudService)
                            }
                        }
                    }
                    FloatingHudService@this@FloatingHudService.handler.postDelayed(
                        longPressRunnable!!, LONG_PRESS_MS
                    )
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchStartX).toInt()
                    val dy = (ev.rawY - touchStartY).toInt()
                    if (!dragging && (dx * dx + dy * dy) > 400) {
                        dragging = true
                        longPressRunnable?.let {
                            FloatingHudService@this@FloatingHudService.handler.removeCallbacks(it)
                        }
                    }
                    if (dragging) {
                        params.x = paramX + dx
                        params.y = paramY + dy
                        wm.updateViewLayout(this, params)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let {
                        FloatingHudService@this@FloatingHudService.handler.removeCallbacks(it)
                    }
                    if (!dragging) {
                        // Tap → toggle benchmark
                        Thread {
                            if (benchRunning) ZenithDaemonClient.stopBenchmark()
                            else ZenithDaemonClient.startBenchmark()
                        }.start()
                    }
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }
    }
}
