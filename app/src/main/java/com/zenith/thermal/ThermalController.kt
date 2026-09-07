package com.zenith.thermal

/**
 * Sends thermal profile commands to zenithd via socket IPC.
 * No direct sysfs access — the daemon handles all hardware interaction.
 */
object ThermalController {

    fun apply(thermalId: Int): Boolean {
        val id = thermalId.coerceAtLeast(0)
        return if (ZenithDaemonClient.isConnected) {
            ZenithDaemonClient.setProfile(id)
        } else {
            android.util.Log.w("ThermalController", "Daemon not connected, profile $id not applied")
            false
        }
    }
}
