package com.zenith.thermal

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun tileLog(msg: String) {
    try {
        val f = java.io.File("/sdcard/tile.log")
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        f.appendText("[$ts] $msg\n")
    } catch (e: Exception) {
        try {
            val f2 = java.io.File("/sdcard/Android/data/com.zenith.thermal/tile.log")
            f2.parentFile?.mkdirs()
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            f2.appendText("[$ts] $msg\n")
        } catch (_: Exception) {}
    }
}

class BenchmarkTileService : TileService() {
    companion object {
        @Volatile var isBenchActive: Boolean = false
        @Volatile var capturedPkg: String = ""
    }

    override fun onStartListening() {
        super.onStartListening()
        tileLog("onStartListening: active=$isBenchActive, connected=${ZenithDaemonClient.isConnected}")
        qsTile?.let {
            it.state = if (isBenchActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        tileLog("onClick: active=$isBenchActive, connected=${ZenithDaemonClient.isConnected}")

        if (!ZenithDaemonClient.isConnected) {
            tileLog("Daemon NOT connected")
            Toast.makeText(this, "Starting daemon...", Toast.LENGTH_SHORT).show()
            Thread {
                DaemonManager.ensureDaemon(this)
                tileLog("ensureDaemon: connected=${ZenithDaemonClient.isConnected}")
                mainThread {
                    if (ZenithDaemonClient.isConnected) {
                        Toast.makeText(this@BenchmarkTileService, "Daemon ready", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@BenchmarkTileService, "Daemon gagal", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
            return
        }

        if (isBenchActive) {
            isBenchActive = false
            qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
            Toast.makeText(this, "Stopping...", Toast.LENGTH_SHORT).show()
            tileLog("STOP (pkg=$capturedPkg)")
            Thread { doExport() }.start()
        } else {
            isBenchActive = true
            qsTile?.let { it.state = Tile.STATE_ACTIVE; it.updateTile() }
            Toast.makeText(this, "Recording...", Toast.LENGTH_SHORT).show()
            // Use cached fg app from HUD — detect_fg won't work here (QS panel is foreground)
            Thread {
                val cached = ZenithDaemonClient.lastFgApp
                val fresh = ZenithDaemonClient.detectForegroundApp(this@BenchmarkTileService)
                val pkg = cached.ifEmpty { fresh ?: "Unknown" }
                capturedPkg = pkg
                tileLog("START (cached=$cached, fresh=$fresh, pkg=$pkg)")
                val ok = ZenithDaemonClient.startBenchmark()
                tileLog("bench_start: ok=$ok")
                if (!ok) {
                    isBenchActive = false
                    mainThread {
                        qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
                        Toast.makeText(this@BenchmarkTileService, "bench_start gagal", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    private fun doExport() {
        try {
            var running = false
            for (i in 0 until 10) {
                Thread.sleep(500)
                val b = ZenithDaemonClient.getBenchmarkData()
                tileLog("poll $i: running=${b?.running}, elapsed=${b?.elapsedMs}")
                if (b?.running == true) { running = true; break }
            }
            if (!running) {
                tileLog("FAIL: not recording")
                mainThread { Toast.makeText(this, "Daemon tidak recording", Toast.LENGTH_SHORT).show() }
                return
            }

            ZenithDaemonClient.stopBenchmark()
            tileLog("bench_stop sent")
            Thread.sleep(1500)

            val data = ZenithDaemonClient.sendCommand("bench_data")
            val arr = data?.optJSONArray("data")
            val n = arr?.length() ?: 0
            val elapsedMs = data?.optLong("elapsed_ms", 0) ?: 0
            tileLog("bench_data: n=$n, elapsed=$elapsedMs")

            if (n == 0) {
                mainThread { Toast.makeText(this, "No data (${elapsedMs}ms)", Toast.LENGTH_SHORT).show() }
                return
            }

            // Use captured pkg from START time, not fg now (QS panel is foreground at STOP)
            val appName = capturedPkg.ifEmpty { ZenithDaemonClient.getForegroundApp() ?: "Unknown" }
            val shortName = appName.substringAfterLast('.')
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date())
            val csvName = "$shortName $dateStr.csv"

            val header = "#PACKAGE:$appName\n" +
                "FPS,JANK,BigJANK,Max FrameTime(ms)," +
                "CPU0(%),CPU1(%),CPU2(%),CPU3(%),CPU4(%),CPU5(%),CPU6(%),CPU7(%)," +
                "GPU(MHz),GPU(%),DDR(Mbps),Power(mW),Battery(%),CPU(\u2103)," +
                "CPU0(MHz),CPU1(MHz),CPU2(MHz),CPU3(MHz),CPU4(MHz),CPU5(MHz),CPU6(MHz),CPU7(MHz)," +
                "CPU0(M Cycles),CPU1(M Cycles),CPU2(M Cycles),CPU3(M Cycles),CPU4(M Cycles),CPU5(M Cycles),CPU6(M Cycles),CPU7(M Cycles)"

            val sb = StringBuilder()
            sb.appendLine(header)
            for (i in 0 until n) {
                val pt = arr?.optJSONObject(i) ?: continue
                val fps = pt.optInt("fps", 0)
                val tempM = pt.optLong("temp_milli", 0)
                val battP = pt.optInt("batt_pct", 0)
                val battMa = pt.optLong("current_ma", 0)
                val powerMw = if (battMa > 0) (battMa * 38) / 10 else 0
                sb.append("$fps,,,,,,,,,,,,,$powerMw,$battP,${tempM / 10.0},,,,,,,,,,,,,,,,")
                sb.appendLine()
            }

            val dir = File(filesDir, "bench_sessions")
            dir.mkdirs()
            val csvFile = File(dir, csvName)
            csvFile.writeText(sb.toString())
            tileLog("CSV: ${csvFile.absolutePath} ($n pts, pkg=$appName)")

            val dur = elapsedMs / 1000
            mainThread {
                Toast.makeText(this, "Session saved: $shortName (${dur}s)", Toast.LENGTH_SHORT).show()
                FloatingHudService.newCsvFlow.trySend(csvName)
            }
        } catch (e: Exception) {
            tileLog("ERROR: ${e.message}")
            mainThread { Toast.makeText(this, "Export error: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }
}

private fun mainThread(action: () -> Unit) {
    android.os.Handler(android.os.Looper.getMainLooper()).post(action)
}
