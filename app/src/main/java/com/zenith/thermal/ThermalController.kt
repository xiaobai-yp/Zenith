package com.zenith.thermal

/**
 * Sends thermal profile commands to zenithd via stdin/stdout IPC.
 * Thermal hardware switching is handled by init.rc via property triggers.
 * Daemon only writes sconfig for the Oplus thermal HAL.
 * Property name is kept ONLY in daemon binary — not in APK.
 */
object ThermalController {

    /**
     * Apply global profile: IPC to daemon (daemon handles setprop + sconfig).
     */
    fun applyGlobal(ctx: android.content.Context, profileId: Int): Boolean {
        AppProfileCache.setGlobal(ctx, profileId)
        return ZenithDaemonClient.setGlobalProfile(profileId)
    }

    /**
     * Apply per-app profile: daemon detect → setprop → init.rc triggers hardware + sconfig.
     */
    fun apply(profileId: Int) {
        ZenithDaemonClient.setAppProfile(profileId)
    }
}
