package com.zenith.thermal

/**
 * Sends thermal profile commands to zenithd via socket IPC.
 * No direct sysfs access — the daemon handles all hardware interaction.
 */
object ThermalController {

    /** Per-app profile: apply without pinning. */
    fun apply(thermalId: Int): Boolean {
        val id = thermalId.coerceAtLeast(0)
        return if (ZenithDaemonClient.isConnected) {
            ZenithDaemonClient.setAppProfile(id)
        } else {
            android.util.Log.w("ThermalController", "Daemon not connected, profile $id not applied")
            false
        }
    }

    /**
     * Global profile: persist via system property (survives reboot, triggers
     * init.rc) AND apply + pin immediately via daemon IPC.
     */
    fun applyGlobal(context: android.content.Context, thermalId: Int): Boolean {
        val id = thermalId.coerceAtLeast(0)
        // Persist property — init.rc trigger after reboot; daemon reads it too
        try {
            val proc = ProcessBuilder("su", "-c", "setprop persist.sys.zenith.thermal $id").start()
            proc.waitFor()
        } catch (e: Exception) {
            android.util.Log.w("ThermalController", "setprop failed: ${e.message}")
        }
        // Apply immediately + pin global (daemon skips per-app while pinned)
        return if (ZenithDaemonClient.isConnected) {
            ZenithDaemonClient.setGlobalProfile(id)
        } else {
            android.util.Log.w("ThermalController", "Daemon not connected, profile $id not applied")
            false
        }
    }
}
