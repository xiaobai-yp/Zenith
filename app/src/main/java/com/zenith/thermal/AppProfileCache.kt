package com.zenith.thermal

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Per-app thermal profile dialog with:
 * - Gradient-bordered badge showing current profile
 * - Radio that pre-selects the active profile (not always Default)
 * - Writes to both daemon (if connected) and local SharedPreferences cache
 */

/** Local cache of per-app profile assignments (survives daemon disconnect). */
object AppProfileCache {
    private const val PREFS = "zenith_app_profiles"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context, pkg: String): Int =
        prefs(ctx).getInt(pkg, -1)

    fun set(ctx: Context, pkg: String, profileId: Int) {
        prefs(ctx).edit().putInt(pkg, profileId).apply()
    }

    fun all(ctx: Context): Map<String, Int> =
        prefs(ctx).all.mapValues { (it.value as? Int) ?: -1 }

    fun remove(ctx: Context, pkg: String) {
        prefs(ctx).edit().remove(pkg).apply()
    }
}
