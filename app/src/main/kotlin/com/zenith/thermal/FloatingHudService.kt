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
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.graphics.PixelFormat
import android.view.WindowManager
import android.widget.Toast
import java.io.File
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import org.json.JSONObject

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

        // Broadcast for new CSV saved — RecordScreen listens
        private val _newCsvFlow = kotlinx.coroutines.channels.Channel<String>(capacity = 1)
        val newCsvFlow: kotlinx.coroutines.channels.Channel<String> = _newCsvFlow

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

        fun toggle(context: Context) {
            if (isRunning) stop(context) else start(context)
        }

        @Volatile var isRunning: Boolean = false
            private set
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
                isRunning = true
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
                    gpuTemp = status?.gpu?.tempC,
                    cpuLoadPct = status?.cpuLoadPct ?: 0f,
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
        isRunning = false
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
        private var gpuTemp: Double? = null
        private var cpuLoadPct = 0f
        private var gpu: ZenithDaemonClient.GpuInfo? = null
        private var cpuP0: ZenithDaemonClient.CpuInfo? = null
        private var cpuP7: ZenithDaemonClient.CpuInfo? = null
        private var batteryPct: Double? = null
        private var powerW: Double? = null
        private var maxFrameTimeMs = 0
        private var benchRunning = false

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
        private var touchStartTime = 0L
        private var paramX = 0
        private var paramY = 0
        private var dragging = false
        private var longPressRunnable: Runnable? = null

        fun updateData(
            fpsShort: Int, fpsLong: Int, fpsAvg: Int,
            cpuTemp: Double?,
            gpuTemp: Double?,
            cpuLoadPct: Float,
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
            this.gpuTemp = gpuTemp
            this.cpuLoadPct = cpuLoadPct
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

            // CPU load
            if (cpuLoadPct > 0f) {
                val loadPaint = when {
                    cpuLoadPct > 80f -> warnPaint
                    cpuLoadPct > 60f -> accentPaint
                    else -> valuePaint
                }
                lines.add(Triple("LOAD  %.0f%%".format(Locale.US, cpuLoadPct), loadPaint, false))
            }

            // GPU
            gpu?.let {
                val vendorTag = if (it.vendor.isNotEmpty()) it.vendor.uppercase() + " " else ""
                lines.add(Triple("GPU  %s%d/%dMHz  %d%%".format(Locale.US, vendorTag, it.curFreqMhz, it.maxFreqMhz, it.busyPct), accentPaint, false))
            }
            gpuTemp?.let {
                val t = if (it > 80.0) warnPaint else valuePaint
                lines.add(Triple("GPUT  %.0f°C".format(Locale.US, it), t, false))
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

            // Battery + Power
            batteryPct?.let { b ->
                val pStr = powerW?.let { "%.1fW".format(Locale.US, it) } ?: "—"
                lines.add(Triple("BAT  %d%%  %s".format(Locale.US, b.toInt(), pStr), valuePaint, false))
            }

            // Bench indicator
            if (benchRunning) {
                lines.add(Triple("● RECORDING", warnPaint, false))
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
                y += paint.textSize.coerceAtLeast(headerPaint.textSize) + lineGap
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val params = layoutParams as? WindowManager.LayoutParams ?: return false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = ev.rawX
                    touchStartY = ev.rawY
                    touchStartTime = SystemClock.uptimeMillis()
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
                        val upTime = SystemClock.uptimeMillis()
                        val downTime = touchStartTime
                        val deltaMs = upTime - downTime
                        // Quick tap (<200ms): toggle bench recording
                        if (deltaMs < 200) {
                            Thread {
                                val bench = ZenithDaemonClient.getBenchmarkData()
                                val benchRunning = bench?.running == true
                                if (benchRunning) {
                                    // Stop bench and collect data
                                    ZenithDaemonClient.stopBenchmark()
                                    try {
                                        // Small delay so daemon stops cleanly
                                        Thread.sleep(200)
                                        val data = ZenithDaemonClient.sendCommand("bench_data")
                                        val arr = data?.optJSONArray("data") ?: org.json.JSONArray()
                                        val n = arr.length()

                                        // Get foreground app name for CSV filename
                                        val appName = ZenithDaemonClient.getForegroundApp() ?: "Zenith"
                                        val shortName = appName.substringAfterLast('.')
                                        val dateStr = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date())
                                        val csvName = "$shortName $dateStr.csv"

                                        // Write CSV content in Scene format (RecordScreen parser compatible)
                                        val sb = StringBuilder()
                                        // Scene CSV header — columns RecordScreen.parseCsvSessions() expects
                                        sb.appendLine("FPS,JANK,BigJANK,Max FrameTime(ms),CPU0(%),CPU1(%),CPU2(%),CPU3(%),CPU4(%),CPU5(%),CPU6(%),CPU7(%),GPU(MHz),GPU(%),DDR(Mbps),Power(mW),Battery(%),CPU(℃),CPU0(MHz),CPU1(MHz),CPU2(MHz),CPU3(MHz),CPU4(MHz),CPU5(MHz),CPU6(MHz),CPU7(MHz),CPU0(M Cycles),CPU1(M Cycles),CPU2(M Cycles),CPU3(M Cycles),CPU4(M Cycles),CPU5(M Cycles),CPU6(M Cycles),CPU7(M Cycles)")
                                        for (i in 0 until n) {
                                            val pt = arr.optJSONObject(i) ?: continue
                                            val fps = pt.optInt("fps", 0)
                                            val tempM = pt.optLong("temp_milli", 0)
                                            val battP = pt.optInt("batt_pct", 0)
                                            val battMa = pt.optLong("current_ma", 0)
                                            // Daemon provides: fps, temp_milli (battery temp), batt_pct, current_ma (mA)
                                            // Missing fields → empty string (parser returns 0f)
                                            val row = StringBuilder()
                                            row.append(fps)                          // FPS
                                            row.append(',').append('')               // JANK
                                            row.append(',').append('')               // BigJANK
                                            row.append(',').append('')               // FrameTime(ms)
                                            row.append(',').append('')  // CPU0(%)
                                            row.append(',').append('')  // CPU1(%)
                                            row.append(',').append('')  // CPU2(%)
                                            row.append(',').append('')  // CPU3(%)
                                            row.append(',').append('')  // CPU4(%)
                                            row.append(',').append('')  // CPU5(%)
                                            row.append(',').append('')  // CPU6(%)
                                            row.append(',').append('')  // CPU7(%)
                                            row.append(',').append('')               // GPU(MHz)
                                            row.append(',').append('')               // GPU(%)
                                            row.append(',').append('')               // DDR(Mbps)
                                            row.append(',').append((battMa * 38) / 10)   // Power(mW) — current(mA) × 3.8V nominal
                                            row.append(',').append(battP)            // Battery(%)
                                            row.append(',').append(tempM / 10.0)    // CPU(℃) — battery temp proxy
                                            row.append(',').append('')  // CPU0(MHz)
                                            row.append(',').append('')  // CPU1(MHz)
                                            row.append(',').append('')  // CPU2(MHz)
                                            row.append(',').append('')  // CPU3(MHz)
                                            row.append(',').append('')  // CPU4(MHz)
                                            row.append(',').append('')  // CPU5(MHz)
                                            row.append(',').append('')  // CPU6(MHz)
                                            row.append(',').append('')  // CPU7(MHz)
                                            row.append(',').append('')  // CPU0(MCycles)
                                            row.append(',').append('')  // CPU1(MCycles)
                                            row.append(',').append('')  // CPU2(MCycles)
                                            row.append(',').append('')  // CPU3(MCycles)
                                            row.append(',').append('')  // CPU4(MCycles)
                                            row.append(',').append('')  // CPU5(MCycles)
                                            row.append(',').append('')  // CPU6(MCycles)
                                            row.append(',').append('')  // CPU7(MCycles)
                                            sb.append(row).appendLine()
                                        }

                                        // Write to /data/local/tmp/ (root-writable temp), then su cp to /sdcard/
                                        val tmpFile = java.io.File("/data/local/tmp/$csvName")
                                        tmpFile.writeText(sb.toString())

                                        val cp = Runtime.getRuntime().exec(arrayOf(
                                            "su", "-c", "cp \"/data/local/tmp/$csvName\" \"/sdcard/$csvName\""
                                        ))
                                        cp.waitFor()
                                        tmpFile.delete()

                                        handler.post {
                                            Toast.makeText(this@FloatingHudService, "Session saved: $shortName", Toast.LENGTH_SHORT).show()
                                            // Notify RecordScreen
                                            _newCsvFlow.trySend(csvName)
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "CSV save failed: ${e.message}")
                                        handler.post {
                                            Toast.makeText(this@FloatingHudService, "Save failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }.start()
                            } else {
                                ZenithDaemonClient.startBenchmark()
                            }
                        }
                    }
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }
    }
}
