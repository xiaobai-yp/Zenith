package com.zenith.thermal

object ThermalController {
    const val PROPERTY = "persist.sys.zenith.thermal"

    fun apply(thermalId: Int) {
        val id = thermalId.coerceAtLeast(0)
        try {
            val cls = Class.forName("android.os.SystemProperties")
            val method = cls.getMethod("set", String::class.java, String::class.java)
            method.invoke(null, PROPERTY, id.toString())
        } catch (_: Throwable) {
        }
    }
}
