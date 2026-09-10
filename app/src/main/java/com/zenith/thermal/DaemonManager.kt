package com.zenith.thermal

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages the zenithd daemon lifecycle: extract, start, stop, check.
 *
 * All blocking work runs on the calling thread. Only call from a background
 * thread (Service.onCreate, or wrap calls in `Thread { ... }`).
 */
object DaemonManager {

    private const val TAG = "DaemonManager"
    private const val SOCKET_PATH = "zenithd"
    private const val ASSET_NAME = "zenithd"
    private const val ASSET_PROFILES = "profiles.json"
    private const val SOCKET_WAIT_MS = 3_000L
    private const val SOCKET_POLL_MS = 100L

    /**
     * Start zenithd if not already running.
     * Extracts the binary from assets if missing or updated (size differs),
     * chmods it, launches via su, and waits for the IPC socket to appear.
     *
     * @return true if the daemon is running (socket present) after the call.
     */
    fun startDaemon(context: Context): Boolean {
        Log.i(TAG, "startDaemon called, filesDir=${context.filesDir}")
        if (isDaemonRunning()) {
            Log.i(TAG, "Daemon already running (abstract socket alive)")
            return true
        }

        val binFile = File(context.filesDir, "zenithd")
        val assetBytes = context.assets.open(ASSET_NAME).use { it.readBytes() }

        // Only extract if missing or the asset changed (size differs).
        // open() + readBytes() — NOT openFd(), which throws when the asset
        // is compressed in the APK (default for large assets).
        val needsExtract = !binFile.exists() || binFile.length() != assetBytes.size.toLong()
        if (needsExtract) {
            Log.i(TAG, "Extracting $ASSET_NAME (${assetBytes.size} bytes)")
            binFile.outputStream().use { it.write(assetBytes) }
        }

        // Extract profiles.json alongside the daemon (it must exist before
        // zenithd starts — profile_engine::init reads it at boot).
        val profilesFile = File(context.filesDir, ASSET_PROFILES)
        if (!profilesFile.exists()) {
            try {
                val profilesBytes = context.assets.open(ASSET_PROFILES).use { it.readBytes() }
                profilesFile.outputStream().use { it.write(profilesBytes) }
                Log.i(TAG, "Extracted $ASSET_PROFILES (${profilesBytes.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "profiles.json extract failed: ${e.message}")
            }
        }

        // Make executable
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 755 ${binFile.absolutePath}")).waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "chmod failed: ${e.message}")
            return false
        }

        // Launch daemon via su as root (needs sysfs write access). Abstract
        // socket — no chown/chmod/chcon needed for app connectivity.
        Log.i(TAG, "bin=${binFile.absolutePath}")
        try {
            // setsid detaches daemon into its own session so it survives
            // su shell exit. KernelSU kills background children of su -c
            // on exit; setsid prevents that by creating a new process group.
            val cmd = "setsid ${binFile.absolutePath} >/dev/null 2>&1 &"
            Log.i(TAG, "Launch cmd: su -c '$cmd'")
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            Log.i(TAG, "Launched: su -c '$cmd'")
        } catch (e: Exception) {
            Log.e(TAG, "Exec failed: ${e.message}")
            return false
        }

        // Wait up to SOCKET_WAIT_MS for the abstract socket to be connectable.
        val deadline = System.currentTimeMillis() + SOCKET_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isDaemonRunning()) {
                Log.i(TAG, "Daemon abstract socket connectable")
                return true
            }
            try { Thread.sleep(SOCKET_POLL_MS) } catch (_: InterruptedException) { break }
        }

        Log.w(TAG, "Socket did not appear within ${SOCKET_WAIT_MS}ms")
        return false
    }

    /** Kill zenithd via su. */
    fun stopDaemon() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "killall zenithd")).waitFor()
            Log.i(TAG, "Sent killall zenithd")
        } catch (e: Exception) {
            Log.e(TAG, "Stop failed: ${e.message}")
        }
    }

    /** Connect-ping: abstract sockets have no filesystem node to stat. */
    fun isDaemonRunning(): Boolean = try {
        android.net.LocalSocket().use { s ->
            s.connect(
                android.net.LocalSocketAddress(
                    SOCKET_PATH,
                    android.net.LocalSocketAddress.Namespace.ABSTRACT
                )
            )
            true
        }
    } catch (_: Exception) { false }

    /** Start the daemon only if it is not already running. */
    fun ensureDaemon(context: Context): Boolean =
        isDaemonRunning() || startDaemon(context)
}