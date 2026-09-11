package com.zenith.thermal

/**
 * Sends thermal profile commands to zenithd via stdin/stdout IPC.
 * Thermal hardware switching is handled by init.rc via property triggers.
 * Daemon only writes sconfig for the Oplus thermal HAL.
 */
object ThermalController {
    private const val PROP_THERMAL = "persist.sys.zenith.thermal"

    /**
     * Apply global profile: set property (init.rc triggers hardware) + sconfig (thermal HAL).
     */
    fun applyGlobal(ctx: android.content.Context, profileId: Int): Boolean {
        setprop(PROP_THERMAL, profileId.toString())
        return ZenithDaemonClient.setGlobalProfile(profileId)
    }

    /**
     * Apply per-app profile: daemon detect → setprop → init.rc triggers hardware + sconfig.
     */
    fun apply(profileId: Int) {
        ZenithDaemonClient.setAppProfile(profileId)
    }

    private fun setprop(name: String, value: String) {
        Runtime.getRuntime().exec(arrayOf("su", "-c", "setprop $name $value"))
    }
}
