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
 * Foreground overlay service — compact game HUD.
 * Shows FPS, frame time, CPU/GPU temp+freq, battery power W.
 *
 * Start with ACTION_START / ACTION_STOP intents.
 * Tap cycles display mode (minimal ↔ full).
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
                description = "Game performance overlay"
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
        Thread {
            val status = ZenithDaemonClient.getStatus()
            val bench = ZenithDaemonClient.getBenchmarkData()
            val benchRunning = bench?.running == true
            val powerW = status?.battery?.powerMw?.let { if (it > 0) it / 1000.0 else null }
            handler.post {
                v.updateData(
                    fpsShort = status?.fpsShort ?: 0,
                    fpsLong = status?.fpsLong ?: 0,
                    fpsAvg = status?.fpsAvg ?: 0,
                    cpuTemp = status?.thermalZones?.firstOrNull()?.tempC,
                    gpu = status?.gpu,
                    cpuPolicy0 = status?.cpuPolicy0,
                    cpuPolicy7 = status?.cpuPolicy7,
                    batteryPct = status?.batteryCapacityPct,
                    powerW = powerW,
                    maxFrameTimeMs = status?.maxFrameTimeMs ?: 0,
                    benchRunning = benchRunning,
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
        // data
        private var fpsShort = 0
        private var fpsLong = 0
        private var fpsAvg = 0
        private var cpuTemp: Double? = null
        private var gpu: ZenithDaemonClient.GpuInfo? = null
        private var cpuP0: ZenithDaemonClient.CpuInfo? = null
        private var cpuP7: ZenithDaemonClient.CpuInfo? = null
        private var batteryPct: Double? = null
        private var powerW: Double? = null
        private var maxFrameTimeMs = 0
        private var benchRunning = false

        // display mode: 0=compact  1=full
        private var displayMode = 0

        // paints
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 16, 16, 24)
            setShadowLayer(12f, 0f, 2f, Color.argb(100, 0, 0, 0))
        }
        private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4FC3F7")
            textSize = 34f
            typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
        }
        private val fpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#69F0AE")
            textSize = 52f
            typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
        }
        private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#90A4AE")
            textSize = 24f
            typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
        }
        private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5252")
            textSize = 24f
            typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
        }
        private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD740")
            textSize = 24f
            typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
        }
        private val benchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9800")
            textSize = 24f
            typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
        }

        private val cornerRadius = 16f
        private val padH = 20f
        private val padV = 14f
        private val lineGap = 3f

        private val lines = mutableListOf<Triple<String, Paint, Boolean>>() // text, paint, isMetric
        private var touchStartX = 0f
        private var touchStartY = 0f
        private var paramX = 0
        private var paramY = 0
        private var dragging = false
        private var longPressRunnable: Runnable? = null

        fun updateData(
            fpsShort: Int, fpsLong: Int, fpsAvg: Int,
            cpuTemp: Double?,
            gpu: ZenithDaemonClient.GpuInfo?,
            cpuPolicy0: ZenithDaemonClient.CpuInfo?,
            cpuPolicy7: ZenithDaemonClient.CpuInfo?,
            batteryPct: Double?,
            powerW: Double?,
            maxFrameTimeMs: Int,
            benchRunning: Boolean,
        ) {
            this.fpsShort = fpsShort
            this.fpsLong = fpsLong
            this.fpsAvg = fpsAvg
            this.cpuTemp = cpuTemp
            this.gpu = gpu
            this.cpuP0 = cpuPolicy0
            this.cpuP7 = cpuPolicy7
            this.batteryPct = batteryPct
            this.powerW = powerW
            this.maxFrameTimeMs = maxFrameTimeMs
            this.benchRunning = benchRunning
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            lines.clear()

            // FPS big number
            lines.add(Triple("FPS  %d / %d".format(Locale.US, fpsShort, fpsLong), fpsPaint, true))

            // Frame time + session avg
            if (maxFrameTimeMs > 100) {
                lines.add(Triple("FRT  %dms ⚠".format(Locale.US, maxFrameTimeMs), warnPaint, true))
            } else if (maxFrameTimeMs > 0) {
                lines.add(Triple("FRT  %dms".format(Locale.US, maxFrameTimeMs), dimPaint, false))
            }
            if (fpsAvg > 0) {
                lines.add(Triple("AVG  %d".format(Locale.US, fpsAvg), dimPaint, false))
            }

            if (displayMode == 1) {
                // GPU
                gpu?.let {
                    lines.add(Triple("GPU  %d/%dMHz  %d%%".format(Locale.US, it.curFreqMhz, it.maxFreqMhz, it.busyPct), accentPaint, false))
                }

                // CPU freq
                cpuP0?.let { p0 ->
                    val p7 = cpuP7
                    if (p7 != null) {
                        lines.add(Triple("CPU  L%d/%dMHz  X%d/%dMHz".format(Locale.US, p0.curFreqMhz, p0.maxFreqMhz, p7.curFreqMhz, p7.maxFreqMhz), valuePaint, false))
                    } else {
                        lines.add(Triple("CPU  %d/%dMHz".format(Locale.US, p0.curFreqMhz, p0.maxFreqMhz), valuePaint, false))
                    }
                }

                // Temp
                cpuTemp?.let {
                    val tempPaint = if (it > 75.0) warnPaint else valuePaint
                    lines.add(Triple("TMP  %.0f°C".format(Locale.US, it), tempPaint, false))
                }
            }

            // Battery + Power
            batteryPct?.let { b ->
                val pStr = powerW?.let { "%.1fW".format(Locale.US, it) } ?: "—"
                lines.add(Triple("BAT  %d%%  %s".format(Locale.US, b.toInt(), pStr), valuePaint, false))
            }

            // Bench indicator
            if (benchRunning) {
                lines.add(Triple("● BENCH", benchPaint, false))
            }

            val maxW = lines.maxOf { it.second.measureText(it.first) }
            val totalH = lines.size * (headerPaint.textSize + lineGap)
            val w = maxW + padH * 2
            val h = totalH + padV * 2
            setMeasuredDimension(w.toInt(), h.toInt())

            // Background
            val rect = RectF(0f, 0f, w, h)
            c.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

            // Accent left bar
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#4FC3F7")
            }
            c.drawRoundRect(RectF(0f, 0f, 4f, h), cornerRadius, cornerRadius, barPaint)

            // Text
            var y = padV + headerPaint.textSize
            for (i in lines.indices) {
                val (text, paint, _) = lines[i]
                c.drawText(text, padH, y, paint)
                y += headerPaint.textSize + lineGap
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
                        // Tap → cycle display mode
                        displayMode = (displayMode + 1) % 2
                        invalidate()
                    }
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }
    }
}
