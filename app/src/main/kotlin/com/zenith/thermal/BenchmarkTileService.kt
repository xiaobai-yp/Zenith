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
        @Volatile var benchStartTime: Long = 0L
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
                val fresh = ZenithDaemonClient.detectForegroundApp(this@BenchmarkTileService)
                val pkg = fresh ?: "Unknown"
                capturedPkg = pkg
                tileLog("START (pkg=$pkg)")
                benchStartTime = System.currentTimeMillis()
                val ok = ZenithDaemonClient.startBenchmark()
                tileLog("bench_start: ok=$ok")
                if (!ok) {
                    isBenchActive = false
                    mainThread {
                        qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
                        Toast.makeText(this@BenchmarkTileService, "bench_start gagal", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Monitor fg app — auto-stop if changed (min 3s to avoid switch animation)
                    while (isBenchActive) {
                        Thread.sleep(2000)
                        if (!isBenchActive) break
                        if (System.currentTimeMillis() - benchStartTime < 3000) continue
                        val curFg = ZenithDaemonClient.detectForegroundApp(this@BenchmarkTileService)
                        if (!curFg.isNullOrEmpty() && curFg != pkg) {
                            tileLog("FG_CHANGED: $pkg → $curFg")
                            isBenchActive = false
                            mainThread {
                                qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
                                Toast.makeText(this@BenchmarkTileService, "Auto-stopped: fg changed", Toast.LENGTH_SHORT).show()
                            }
                            doExport()
                            return@Thread
                        }
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

            ZenithDaemonClient.stopBenchmark(capturedPkg, benchStartTime)
            tileLog("bench_stop sent")
            Thread.sleep(1000)

            // Daemon bench_stop already persisted to /data/zenith/sessions/ — no CSV needed
            val elapsedMs = ZenithDaemonClient.getBenchmarkData()?.elapsedMs ?: 0
            val shortName = capturedPkg.substringAfterLast('.')
            val dur = elapsedMs / 1000
            mainThread {
                Toast.makeText(this, "Session saved: $shortName (${dur}s)", Toast.LENGTH_SHORT).show()
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
