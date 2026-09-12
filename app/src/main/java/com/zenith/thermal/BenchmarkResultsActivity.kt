package com.zenith.thermal

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BenchmarkResultsActivity : Activity() {

    companion object {
        private const val BG      = 0xFF102830.toInt()
        private const val SURFACE = 0xFF2A444D.toInt()
        private const val ACCENT  = 0xFF5EA7FF.toInt()
        private const val TEXT    = 0xFFF2F5F6.toInt()
        private const val MUTED   = 0xFFB8C6CA.toInt()
        private const val BORDER  = 0xFF49636B.toInt()
        private const val MAX_SAMPLES = 300
    }

    private val handler = Handler(Looper.getMainLooper())
    private val fpsHistory = mutableListOf<FpsChartView.Sample>()

    private var running = false
    private lateinit var statusText: TextView
    private lateinit var durationText: TextView
    private lateinit var avgFpsText: TextView
    private lateinit var minFpsText: TextView
    private lateinit var maxFpsText: TextView
    private lateinit var avgTempText: TextView
    private lateinit var battDrainText: TextView
    private lateinit var startStopButton: TextView
    private lateinit var chartView: FpsChartView

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateData()
            if (running) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_benchmark_results)

        val root = findViewById<View>(R.id.root)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setPadding(root.paddingLeft, dp(32), root.paddingRight, root.paddingBottom)
        }

        statusText = findViewById(R.id.statusText)
        durationText = findViewById(R.id.durationText)
        avgFpsText = findViewById(R.id.avgFpsText)
        minFpsText = findViewById(R.id.minFpsText)
        maxFpsText = findViewById(R.id.maxFpsText)
        avgTempText = findViewById(R.id.avgTempText)
        battDrainText = findViewById(R.id.battDrainText)
        startStopButton = findViewById(R.id.startStopButton)
        chartView = findViewById(R.id.chartView)

        val exportButton = findViewById<TextView>(R.id.exportButton)
        val shareButton = findViewById<TextView>(R.id.shareButton)

        styleButton(startStopButton, true)
        styleButton(exportButton, false)
        styleButton(shareButton, false)

        startStopButton.setOnClickListener { toggleBenchmark() }
        exportButton.setOnClickListener { exportJson() }
        shareButton.setOnClickListener { shareJson() }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun toggleBenchmark() {
        if (running) {
            ZenithDaemonClient.stopBenchmark()
            running = false
            statusText.text = "Stopped"
            startStopButton.text = "START"
            styleButton(startStopButton, true)
        } else {
            fpsHistory.clear()
            ZenithDaemonClient.startBenchmark()
            running = true
            statusText.text = "Running…"
            startStopButton.text = "STOP"
            styleButton(startStopButton, false)
            handler.post(refreshRunnable)
        }
    }

    private fun updateData() {
        try {
            val data = ZenithDaemonClient.getBenchmarkData() ?: return
            durationText.text = formatDuration(data.elapsedMs)

            data.fps?.let { fps ->
                avgFpsText.text = String.format(Locale.US, "%.1f", fps.avgFps.toDouble())
                minFpsText.text = String.format(Locale.US, "%.1f", fps.minFps.toDouble())
                maxFpsText.text = String.format(Locale.US, "%.1f", fps.maxFps.toDouble())

                val tsSec = data.elapsedMs / 1000
                if (data.elapsedMs > 0 && fpsHistory.none { it.tsSec == tsSec }) {
                    fpsHistory += FpsChartView.Sample(tsSec, fps.avgFps.toDouble())
                    while (fpsHistory.size > MAX_SAMPLES) fpsHistory.removeAt(0)
                    chartView.setData(fpsHistory)
                }
            }

            ZenithDaemonClient.getStatus()?.let { status ->
                val zones = status.thermalZones
                avgTempText.text = if (zones.isEmpty()) "—"
                    else String.format(Locale.US, "%.1f°C", zones.map { it.tempC }.average())
                battDrainText.text = String.format(Locale.US, "%.1f%%/h", status.batteryDrainPctPerHr)
            }

            if (!data.running && running) {
                running = false
                statusText.text = "Completed"
                startStopButton.text = "START"
                styleButton(startStopButton, true)
            }

            if (running) handler.postDelayed(refreshRunnable, 1000)
        } catch (_: Exception) {
            if (running) handler.postDelayed(refreshRunnable, 1500)
        }
    }

    private fun exportJson() = saveJson(share = false)

    private fun shareJson() = saveJson(share = true)

    private fun saveJson(share: Boolean) {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?: getExternalFilesDir(null)
            if (dir != null && !dir.exists()) dir.mkdirs()
            if (dir == null) {
                Toast.makeText(this, "Cannot write file", Toast.LENGTH_SHORT).show()
                return
            }
            val file = File(dir, "zenith_benchmark_$ts.json")
            FileWriter(file).use { it.write(buildJson().toString(2)) }

            if (share) {
                val uri = Uri.fromFile(file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Zenith Benchmark Results")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share Benchmark"))
            } else {
                Toast.makeText(this, "Saved: ${file.name}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, if (share) "Share failed: ${e.message}" else "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildJson(): JSONObject {
        val obj = JSONObject()
        obj.put("timestamp", System.currentTimeMillis())
        obj.put("durationMs", if (fpsHistory.isEmpty()) 0
            else (fpsHistory.last().tsSec - fpsHistory.first().tsSec) * 1000)

        ZenithDaemonClient.getBenchmarkData()?.let { data ->
            obj.put("elapsedMs", data.elapsedMs)
            obj.put("totalFrames", data.frames)
            data.fps?.let { fps ->
                obj.put("fps", JSONObject().apply {
                    put("avg", fps.avgFps.toDouble())
                    put("min", fps.minFps.toDouble())
                    put("max", fps.maxFps.toDouble())
                })
            }
        }

        obj.put("fpsHistory", JSONArray().apply {
            fpsHistory.forEach { s ->
                put(JSONObject().apply {
                    put("timeMs", s.tsSec * 1000)
                    put("fps", Math.round(s.fps * 10.0) / 10.0)
                })
            }
        })
        return obj
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        if (s < 60) return "${s}s"
        val m = s / 60; val rem = s % 60
        if (m < 60) return "${m}m ${rem}s"
        return "${m / 60}h ${m % 60}m"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun styleButton(btn: TextView, primary: Boolean) {
        btn.background = GradientDrawable().apply {
            setColor(if (primary) ACCENT else SURFACE)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), BORDER)
        }
    }
}