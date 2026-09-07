package com.zenith.thermal

import android.content.Context

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    fun global(): Int = prefs.getInt("global", Profile.value(0))

    fun global(value: Int) {
        prefs.edit().putInt("global", value).apply()
    }

    fun app(pkg: String): Int? {
        val key = "app_$pkg"
        return if (prefs.contains(key)) prefs.getInt(key, 0) else null
    }

    fun app(pkg: String, value: Int) {
        prefs.edit().putInt("app_$pkg", value).apply()
    }

    fun clear(pkg: String) {
        prefs.edit().remove("app_$pkg").apply()
    }

    fun reset() {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("app_") }.forEach { e.remove(it) }
        e.apply()
    }
}
