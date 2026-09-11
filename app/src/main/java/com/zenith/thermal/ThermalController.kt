package com.zenith.thermal

/**
 * Sends thermal profile commands to zenithd via stdin/stdout IPC.
 * Thermal hardware switching is handled by init.rc via property triggers.
 * Daemon only writes sconfig — this controller triggers that.
 */
object ThermalController {

    /** Per-app profile: write sconfig directly (no property — daemon IPC). */
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
     * Global profile: set property (init.rc triggers hardware changes) +
     * send IPC (daemon writes sconfig for immediate thermal HAL response).
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
        // Apply immediately via daemon IPC (sconfig)
        return if (ZenithDaemonClient.isConnected) {
            ZenithDaemonClient.setGlobalProfile(id)
        } else {
            android.util.Log.w("ThermalController", "Daemon not connected, profile $id not applied via IPC")
            false
        }
    }
}
