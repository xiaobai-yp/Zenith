package com.zenith.thermal

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStreamReader

/**
 * Manages the zenithd daemon lifecycle: extract, start, stop, check.
 *
 * Daemon runs as a child process launched via `su -c "setsid <bin>"`.
 * IPC is over the process's stdin/stdout pipes — no sockets.
 */
object DaemonManager {

    private const val TAG = "DaemonManager"
    private const val ASSET_NAME = "zenithd"
    private const val ASSET_PROFILES = "profiles.json"

    @Volatile private var process: Process? = null
    @Volatile private var daemonContext: Context? = null
    private var restartCount = 0
    private val MAX_RESTARTS = 5

    private val monitorThread = object : Thread("zenithd-monitor") {
        init { isDaemon = true }
        override fun run() {
            while (!isInterrupted) {
                val p = process
                if (p == null) {
                    try { sleep(500) } catch (_: InterruptedException) { return }
                    continue
                }
                try {
                    p.waitFor()
                    Log.w(TAG, "Daemon process exited with code ${p.exitValue()}")
                } catch (_: InterruptedException) { return }
                catch (e: Exception) { Log.w(TAG, "waitFor: ${e.message}") }
                process = null
                ZenithDaemonClient.onProcessDied()
                // Auto-restart with backoff
                val ctx = daemonContext
                if (ctx != null && restartCount < MAX_RESTARTS) {
                    restartCount++
                    val delay = 2000L * restartCount
                    Log.i(TAG, "Auto-restarting daemon in ${delay}ms (attempt $restartCount/$MAX_RESTARTS)")
                    try { sleep(delay) } catch (_: InterruptedException) { return }
                    startDaemon(ctx)
                } else if (restartCount >= MAX_RESTARTS) {
                    Log.e(TAG, "Max restarts ($MAX_RESTARTS) reached — daemon stays dead until app restart")
                }
            }
        }
    }.apply { start() }

    /**
     * Start zenithd if not already running.
     * Extracts the binary from assets if missing or updated, chmods it,
     * launches via su, and pipes stdin/stdout to ZenithDaemonClient.
     *
     * @return true if the daemon is running after the call.
     */
    fun startDaemon(context: Context): Boolean {
        Log.i(TAG, "startDaemon called")
        daemonContext = context
        if (isDaemonRunning()) {
            Log.i(TAG, "Daemon already running")
            return true
        }

        // Kill stale daemon from previous session
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "killall zenithd")).waitFor()
            Thread.sleep(200)
        } catch (_: Exception) {}

        // Extract to /data/local/tmp/ — SELinux allows exec there.
        // App data files dir has system_data_file label which blocks exec.
        val binFile = File("/data/local/tmp/zenithd")
        val assetBytes = context.assets.open(ASSET_NAME).use { it.readBytes() }

        val needsExtract = !binFile.exists() ||
            binFile.lastModified() < context.packageManager
                .getPackageInfo(context.packageName, 0).lastUpdateTime
        if (needsExtract) {
            Log.i(TAG, "Extracting $ASSET_NAME (${assetBytes.size} bytes)")
            val su = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat > /data/local/tmp/zenithd"))
            su.outputStream.use { it.write(assetBytes) }
            su.waitFor()
        }

        // Make executable
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 755 /data/local/tmp/zenithd")).waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "chmod failed: ${e.message}")
            return false
        }

        // Launch daemon via su as root.
        // setsid detaches into its own session so it survives su shell exit.
        // KernelSU kills background children of `su -c` on exit;
        // setsid prevents that by creating a new process group.
        val cmd = arrayOf("su", "-c", "setsid /data/local/tmp/zenithd")
        Log.i(TAG, "Launch: ${cmd.joinToString(" ")}")
        try {
            val p = Runtime.getRuntime().exec(cmd)
            process = p

            // Drain stderr to a thread so it doesn't block
            Thread({
                try {
                    InputStreamReader(p.errorStream, Charsets.UTF_8).use { reader ->
                        val buf = CharArray(1024)
                        while (true) {
                            val n = reader.read(buf) ?: break
                            if (n < 0) break
                            // Log stderr at debug level — don't spam logcat
                            Log.d(TAG, "zenithd: ${String(buf, 0, n).trim()}")
                        }
                    }
                } catch (_: Exception) {}
            }, "zenithd-stderr").apply { isDaemon = true; start() }

            // Attach the process stdin/stdout to the IPC client
            ZenithDaemonClient.attachProcess(p.inputStream, p.outputStream)
            restartCount = 0
            Log.i(TAG, "Daemon launched and attached")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exec failed: ${e.message}")
            return false
        }
    }

    /** Kill zenithd via su and destroy the process reference. */
    fun stopDaemon() {
        val p = process
        process = null
        try { p?.destroy() } catch (_: Exception) {}
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "killall zenithd")).waitFor()
            Log.i(TAG, "Sent killall zenithd")
        } catch (e: Exception) {
            Log.e(TAG, "Stop failed: ${e.message}")
        }
        ZenithDaemonClient.onProcessDied()
    }

    /** Check if the child process is still alive. */
    fun isDaemonRunning(): Boolean = process?.isAlive == true

    /** Start the daemon only if it is not already running. */
    fun ensureDaemon(context: Context): Boolean =
        isDaemonRunning() || startDaemon(context)
}
