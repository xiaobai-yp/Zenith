package com.zenith.thermal

/**
 * Sends thermal profile commands to zenithd via stdin/stdout IPC.
 * Also writes sconfig + governors directly via su as a fallback
 * when the daemon IPC path is unreliable.
 */
object ThermalController {

    // Oplus thermal HAL profile selector
    private const val SCONFIG_PATH = "/sys/class/thermal/thermal_message/sconfig"

    // CPU governor sysfs paths (Snapdragon 7 Gen 1: policy0/4/7)
    private val GOV_PATHS = arrayOf(
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_governor",
        "/sys/devices/system/cpu/cpufreq/policy4/scaling_governor",
        "/sys/devices/system/cpu/cpufreq/policy7/scaling_governor",
    )

    // Profile ID → governor name for each profile that uses non-default governor
    private val GOV_MAP = mapOf(
        9 to "vorpal",   // Game
        10 to "vorpal",  // Dynamic
        13 to "vorpal",  // Pubg
        16 to "vorpal",  // Game 2
    )

    /** Per-app profile: apply without pinning. */
    fun apply(thermalId: Int): Boolean {
        val id = thermalId.coerceAtLeast(0)
        // Try daemon IPC first
        if (ZenithDaemonClient.isConnected) {
            val ok = ZenithDaemonClient.setAppProfile(id)
            if (ok) return true
        }
        // Fallback: write sconfig + governors directly
        return writeThermalProfile(id)
    }

    /**
     * Global profile: persist via system property (survives reboot, triggers
     * init.rc) AND apply immediately via daemon IPC + direct sysfs writes.
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
        // Write sconfig + governors directly via su (bypasses daemon IPC issues)
        writeThermalProfile(id)
        // Also send IPC to daemon for tracking (best effort)
        if (ZenithDaemonClient.isConnected) {
            ZenithDaemonClient.setGlobalProfile(id)
        }
        return true
    }

    /**
     * Write sconfig + CPU governors directly via su.
     * This ensures thermal switching works even if daemon IPC is broken.
     */
    fun writeThermalProfile(thermalId: Int): Boolean {
        val gov = GOV_MAP[thermalId] ?: "schedutil"
        val cmds = mutableListOf<String>()
        // Kill mi_thermald — it resets governors back to schedutil
        cmds.add("killall -9 mi_thermald 2>/dev/null || true")
        // sconfig — tells Oplus thermal HAL which profile is active
        cmds.add("echo $thermalId > $SCONFIG_PATH")
        // CPU governors
        for (path in GOV_PATHS) {
            cmds.add("echo $gov > $path")
        }
        return try {
            val cmd = arrayOf("su", "-c", cmds.joinToString(" && "))
            val proc = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            android.util.Log.i("ThermalController", "writeThermalProfile($thermalId) gov=$gov: exit=${proc.exitValue()}")
            proc.exitValue() == 0
        } catch (e: Exception) {
            android.util.Log.w("ThermalController", "writeThermalProfile failed: ${e.message}")
            false
        }
    }
}
