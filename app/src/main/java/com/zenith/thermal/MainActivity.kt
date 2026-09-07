package com.zenith.thermal

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*
import android.text.Editable
import android.text.TextWatcher

class MainActivity : android.app.Activity() {
    private lateinit var store: ProfileStore
    private lateinit var adapter: AppAdapter
    private var showSystem = false
    private val apps = ArrayList<AppItem>()
    private var currentSearch = ""
    private lateinit var bottomBarHost: FrameLayout
    private lateinit var navThermalView: View
    private lateinit var navBatteryView: View
    private lateinit var navThermalTextView: TextView
    private lateinit var navBatteryTextView: TextView
    private lateinit var navThermalIconView: ImageView
    private lateinit var navBatteryIconView: ImageView

    companion object {
        private val DIALOG_BG = Color.rgb(8, 28, 36)
        private val BORDER = Color.rgb(56, 83, 93)
        private val TEXT = Color.rgb(244, 247, 248)
        private val MUTED = Color.rgb(193, 204, 208)
        private val RADIO = Color.rgb(94, 167, 255)
    }

    private val batteryPrefs by lazy { getSharedPreferences("zenith_battery", MODE_PRIVATE) }

    private val notificationPermissionRequest = 7001

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        cleanupLegacyNotificationChannels()
        applySystemBarInsets()
        store = ProfileStore(this)
        val list = findViewById<ListView>(R.id.appList)
        adapter = AppAdapter(this, store)
        list.adapter = adapter
        list.isVerticalScrollBarEnabled = true
        list.isScrollbarFadingEnabled = true
        list.isFastScrollEnabled = false
        setupGlassBottomBar()
        findViewById<View>(R.id.menuButton).apply {
            translationY = -dp(2).toFloat()
            setOnClickListener { menu(it) }
        }
        findViewById<TextView>(R.id.batteryToggle).setOnClickListener { toggleBatteryMonitor() }
        setupBatterySettings()
        updateBatteryNav()
        ensureBatteryMonitor()
        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { currentSearch = s?.toString() ?: ""; adapter.filter(currentSearch) }
            override fun afterTextChanged(s: Editable?) {}
        })
        list.setOnItemClickListener { _, _, position, _ -> choose(adapter.getItem(position)) }
        load()
        showThermal()
        runCatching { startMonitor() }
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) { load(); adapter.notifyDataSetChanged() }
    }

    override fun onPause() {
        super.onPause()
    }

    private fun load() {
        val pm = packageManager
        apps.clear()
        for (info in pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))) {
            if (!showSystem && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            apps.add(AppItem(info, pm))
        }
        apps.sortBy { it.name.lowercase() }
        adapter.setItems(apps)
        adapter.filter(currentSearch)
    }

    private fun cleanupLegacyNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            listOf("zenith_battery", "zenith_battery_monitor", "battery_monitor_channel", "zenith_refresh")
                .forEach { runCatching { nm.deleteNotificationChannel(it) } }
            nm.notificationChannels
                .filter { it.id != "battery_monitor" && (
                    it.name.toString().contains("Zenith Battery Monitor", true) ||
                    it.name.toString().contains("Zenith Refresh Rate", true) ||
                    it.name.toString().equals("Battery Monitor", true)
                ) }
                .forEach { runCatching { nm.deleteNotificationChannel(it.id) } }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun rounded(color: Int, stroke: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), stroke) }

    // bg_bottom_active is an <inset> drawable (4dp on every side), and InsetDrawable.getPadding()
    // reports those insets as the drawable's own padding. Assigning it to `.background` at
    // runtime (as showThermal/showBattery do below) makes View.setBackgroundDrawable() adopt
    // that reported padding, silently overwriting the paddingLeft/Right="12dp" declared on
    // navThermal/navBattery in dialog_bottom_bar.xml down to 4dp — that collapse is what let the
    // text reach the border. Re-applying the intended padding after every background swap fixes it.
    private fun restoreNavPadding() {
        navThermalView.setPadding(dp(12), 0, dp(12), 0)
        navBatteryView.setPadding(dp(12), 0, dp(12), 0)
    }

    private fun setupGlassBottomBar() {
        // Keep the navigation in the activity window. A Dialog is a separate
        // touch window and can intercept touches outside the visible bar on
        // some Android/OEM builds. This overlay only owns the bottom controls.
        bottomBarHost = findViewById(R.id.bottomBarHost)
        val view = layoutInflater.inflate(R.layout.dialog_bottom_bar, bottomBarHost, false)
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            bottomMargin = dp(18)
        }
        view.layoutParams = lp

        navThermalView = view.findViewById(R.id.navThermal)
        navBatteryView = view.findViewById(R.id.navBattery)
        navThermalTextView = view.findViewById(R.id.navThermalText)
        navBatteryTextView = view.findViewById(R.id.navBatteryText)
        navThermalIconView = view.findViewById(R.id.navThermalIcon)
        navBatteryIconView = view.findViewById(R.id.navBatteryIcon)

        navThermalView.setOnClickListener { showThermal() }
        navBatteryView.setOnClickListener { showBattery() }
        bottomBarHost.addView(view)
    }

    private fun choose(app: AppItem) {
        val override = store.app(app.pkg)
        showProfileDialog("Set Profile: ${app.name}", if (override == null) 0 else Profile.indexOf(override), true, app.pkg)
    }
    private fun global() = showProfileDialog("Global Profile", Profile.indexOf(store.global()), false, null)

    private fun showProfileDialog(title: String, selected: Int, appMode: Boolean, pkg: String?) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(10)); background = getDrawable(R.drawable.bg_glass_dialog) }
        val heading = TextView(this).apply { text = title; setTextColor(TEXT); textSize = 20f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); maxLines = 2; setPadding(dp(4), dp(4), dp(4), dp(8)) }
        root.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0,0,0,dp(2)) })
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (i in 0 until Profile.count()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(46); isClickable = true; isFocusable = true }
            val label = TextView(this).apply { text = Profile.MENU_NAMES[i]; setTextColor(TEXT); textSize = 16f; gravity = Gravity.CENTER_VERTICAL; maxLines = 2; setPadding(0,0,dp(8),0) }
            row.addView(label, LinearLayout.LayoutParams(0, dp(46), 1f))
            val radio = ProfileRadio(this).apply { setChecked(i == selected) }
            row.addView(radio, LinearLayout.LayoutParams(dp(48), dp(46)))
            val click = View.OnClickListener {
                if (appMode && pkg != null) {
                    if (i == 0) store.clear(pkg) else store.app(pkg, Profile.value(i))
                } else {
                    store.global(Profile.value(i))
                }
                if (appMode && pkg != null) ThermalController.apply(store.app(pkg) ?: store.global())
                else ThermalController.apply(store.global())
                adapter.notifyDataSetChanged()
                showThemedToast("Profile applied: ${Profile.NAMES[i]}")
                dialog.dismiss()
            }
            row.setOnClickListener(click); radio.setOnClickListener(click); group.addView(row, LinearLayout.LayoutParams(-1, dp(46)))
        }
        root.addView(group, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(root)
        dialog.window?.apply { setBackgroundDrawableResource(android.R.color.transparent); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(.62f); setGravity(Gravity.CENTER) }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .86f).toInt(), -2)
    }

    private fun menu(anchor: View) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = getDrawable(R.drawable.bg_glass_popup)
        }
        val systemRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(44) }
        val systemText = menuText("Show System Apps")
        val check = menuText(if (showSystem) "✓" else "").apply { gravity = Gravity.CENTER; setTextColor(RADIO); textSize = 20f }
        systemRow.addView(systemText, LinearLayout.LayoutParams(0, dp(44), 1f)); systemRow.addView(check, LinearLayout.LayoutParams(dp(42), dp(44)))
        systemRow.setOnClickListener { showSystem = !showSystem; dialog.dismiss(); load() }
        box.addView(systemRow)
        val reset = menuText("Reset Per-App Profiles"); box.addView(reset, LinearLayout.LayoutParams(-1, dp(44))); reset.setOnClickListener { store.reset(); dialog.dismiss(); load(); showThemedToast("Per-app profiles reset") }
        val global = menuText("Global Profile"); box.addView(global, LinearLayout.LayoutParams(-1, dp(44))); global.setOnClickListener { dialog.dismiss(); global() }
        val about = menuText("About"); box.addView(about, LinearLayout.LayoutParams(-1, dp(44))); about.setOnClickListener { dialog.dismiss(); showAboutDialog() }
        dialog.setContentView(box)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.16f)
            setGravity(Gravity.TOP or Gravity.END)
        }
        dialog.show()
        dialog.window?.setLayout(dp(238), WindowManager.LayoutParams.WRAP_CONTENT)
        val pos = IntArray(2); anchor.getLocationOnScreen(pos)
        dialog.window?.attributes = dialog.window?.attributes?.apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = pos[1] - dp(8)
        }
    }

    private fun menuText(text: String) = TextView(this).apply { this.text=text; setTextColor(TEXT); textSize=15f; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(14),0,dp(10),0) }

    private fun showThemedToast(message: String) {
        try {
            @Suppress("DEPRECATION")
            val view = layoutInflater.inflate(R.layout.toast_profile, null)
            view.findViewById<TextView>(R.id.toast_text).text = message
            @Suppress("DEPRECATION")
            Toast(this).apply {
                setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(76))
                duration = Toast.LENGTH_SHORT
                setView(view)
                show()
            }
        }
        catch (_: Throwable) { Toast.makeText(this,message,Toast.LENGTH_SHORT).show() }
    }

    private fun showAboutDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(12))
            background = getDrawable(R.drawable.bg_glass_dialog)
        }

        root.addView(TextView(this).apply {
            text = "About Zenith Thermal"
            setTextColor(TEXT)
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, dp(42)))

        val scroll = ScrollView(this)
        val message = TextView(this).apply {
            text = "Zenith Thermal is an Android utility for managing thermal profiles on a per-app basis. It lets you assign a thermal profile to individual applications while keeping a Global Profile as the fallback for apps without a custom override.\n\nPER-APP PROFILES\nSelect an application and assign a profile such as Default, Dynamic, Game, Game 2, Pubg, AR & VR, Camera, or YouTube. Choosing Default removes the app-specific override and makes the app follow the Global Profile.\n\nGLOBAL PROFILE\nDefines the thermal profile used by applications that do not have their own profile. It also provides the baseline profile for the system's thermal profile controller.\n\nPROFILE APPLICATION\nZenith Thermal watches the foreground application and applies its configured profile. When no per-app profile is configured, the Global Profile is used instead.\n\nBATTERY MONITOR\nThe Battery Monitor provides battery status information and keeps persistent accounting for screen-on, screen-off, deep-sleep, awake time, and active/idle drain. It can also show current, voltage, temperature, and power information when supported by the device kernel.\n\nRESET PER-APP PROFILES\nRemoves all application-specific profile overrides so that applications return to the Global Profile.\n\nNOTE\nActual thermal behavior depends on the device kernel, vendor thermal framework, and available thermal interfaces. Zenith Thermal changes the configured profile; it does not replace the device thermal controller itself."
            setTextColor(TEXT)
            textSize = 14f
            setLineSpacing(0f, 1.12f)
        }
        scroll.addView(message, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val ok = TextView(this).apply {
            text = "OK"
            setTextColor(TEXT)
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            background = ColorDrawable(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            stateListAnimator = null
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(ok, LinearLayout.LayoutParams(-2, dp(48)).apply {
            gravity = Gravity.END
            rightMargin = dp(4)
            leftMargin = dp(4)
        })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.62f)
            setGravity(Gravity.CENTER)
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .88f).toInt(), (resources.displayMetrics.heightPixels * .72f).toInt())
    }

    private fun applySystemBarInsets() {
        val root=findViewById<View>(R.id.root); if (Build.VERSION.SDK_INT>=30) root.setOnApplyWindowInsetsListener { v,insets -> val bars=insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()); v.setPadding(v.paddingLeft,bars.top+dp(6),v.paddingRight,bars.bottom+dp(2)); insets }; root.requestApplyInsets()
    }
    private fun startMonitor() {
        val intent=Intent(this,AppMonitorService::class.java)
        runCatching {
            if(Build.VERSION.SDK_INT>=26) startForegroundService(intent) else startService(intent)
        }
    }


    private fun setupBatterySettings() {
        val resetPlugged = batteryPrefs.getBoolean("reset_on_plugged", false)
        val resetTarget = batteryPrefs.getBoolean("reset_on_target", true)
        val resetRestart = batteryPrefs.getBoolean("reset_on_restart", false)
        val showPower = batteryPrefs.getBoolean("show_power", true)
        val idleWarning = batteryPrefs.getBoolean("idle_warning_enabled", false)
        val idleWarningTarget = batteryPrefs.getInt("idle_warning_target", 5).coerceIn(1, 100)
        val target = batteryPrefs.getInt("reset_target", 100).coerceIn(1, 100)

        findViewById<Switch>(R.id.resetTargetSwitch).apply {
            isChecked = resetTarget
            setOnCheckedChangeListener { _, checked ->
                batteryPrefs.edit().putBoolean("reset_on_target", checked).apply()
            }
        }

        val resetTargetValueView = findViewById<TextView>(R.id.resetTargetValue)
        val resetTargetSlider = findViewById<BatteryTargetSlider>(R.id.resetTargetSeek)
        resetTargetValueView.text = "$target%"
        resetTargetSlider.setProgress(target)
        resetTargetSlider.setOnProgressChangedListener { value, fromUser ->
            val safeValue = value.coerceIn(1, 100)
            resetTargetValueView.text = "$safeValue%"
            if (fromUser) {
                batteryPrefs.edit().putInt("reset_target", safeValue).apply()
            }
        }

        findViewById<Switch>(R.id.resetPluggedSwitch).apply {
            isChecked = resetPlugged
            setOnCheckedChangeListener { _, checked ->
                batteryPrefs.edit().putBoolean("reset_on_plugged", checked).apply()
            }
        }

        findViewById<Switch>(R.id.resetRestartSwitch).apply {
            isChecked = resetRestart
            setOnCheckedChangeListener { _, checked ->
                batteryPrefs.edit().putBoolean("reset_on_restart", checked).apply()
            }
        }

        findViewById<Switch>(R.id.showPowerSwitch).apply {
            isChecked = showPower
            setOnCheckedChangeListener { _, checked ->
                batteryPrefs.edit().putBoolean("show_power", checked).apply()
            }
        }

        findViewById<Switch>(R.id.idleWarningSwitch).apply {
            isChecked = idleWarning
            setOnCheckedChangeListener { _, checked ->
                batteryPrefs.edit().putBoolean("idle_warning_enabled", checked).apply()
            }
        }

        val idleWarningValueView = findViewById<TextView>(R.id.idleWarningValue)
        val idleWarningSlider = findViewById<BatteryTargetSlider>(R.id.idleWarningSeek)
        idleWarningValueView.text = "$idleWarningTarget%"
        idleWarningSlider.setProgress(idleWarningTarget)
        idleWarningSlider.setOnProgressChangedListener { value, fromUser ->
            val safeValue = value.coerceIn(1, 100)
            idleWarningValueView.text = "$safeValue%"
            if (fromUser) batteryPrefs.edit().putInt("idle_warning_target", safeValue).apply()
        }

        val temperatureUnits = arrayOf("Celsius (°C)", "Fahrenheit (°F)", "Kelvin (K)")
        val savedUnit = batteryPrefs.getString("temperature_unit", "C") ?: "C"
        val unitIndex = when (savedUnit) { "F" -> 1; "K" -> 2; else -> 0 }
        findViewById<TextView>(R.id.temperatureUnit).apply {
            text = temperatureUnits[unitIndex] + "  ▾"
            setOnClickListener { showTemperatureUnitDialog() }
        }

        findViewById<TextView>(R.id.resetStatsButton).setOnClickListener {
            val intent = Intent(this, BatteryMonitorService::class.java).apply {
                action = BatteryMonitorService.ACTION_RESET
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            showThemedToast("Battery statistics reset")
        }
    }

    private fun showTemperatureUnitDialog() {
        val units = arrayOf("Celsius (°C)", "Fahrenheit (°F)", "Kelvin (K)")
        val current = batteryPrefs.getString("temperature_unit", "C") ?: "C"
        val selected = when (current) { "F" -> 1; "K" -> 2; else -> 0 }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
            background = getDrawable(R.drawable.bg_glass_dialog)
        }

        val title = TextView(this).apply {
            text = "Battery Temperature Unit"
            setTextColor(TEXT)
            textSize = 20f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        root.addView(title, LinearLayout.LayoutParams(-1, dp(48)))

        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        units.forEachIndexed { index, label ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(50)
                isClickable = true
                isFocusable = true
                setPadding(dp(4), 0, dp(2), 0)
            }

            val text = TextView(this).apply {
                this.text = label
                setTextColor(TEXT)
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
            }
            row.addView(text, LinearLayout.LayoutParams(0, dp(50), 1f))

            val radio = ProfileRadio(this).apply {
                setChecked(index == selected)
                isClickable = false
                isFocusable = false
            }
            row.addView(radio, LinearLayout.LayoutParams(dp(48), dp(50)))

            val select = View.OnClickListener {
                val unit = when (index) { 1 -> "F"; 2 -> "K"; else -> "C" }
                batteryPrefs.edit().putString("temperature_unit", unit).apply()
                findViewById<TextView>(R.id.temperatureUnit).text = "$label  ▾"
                dialog.dismiss()
                showThemedToast("Temperature unit: $label")
                runCatching { startBatteryMonitor() }
            }
            row.setOnClickListener(select)
            group.addView(row, LinearLayout.LayoutParams(-1, dp(50)))
        }

        root.addView(group, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.CENTER)
            setDimAmount(.62f)
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .86f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun batteryEnabled(): Boolean = batteryPrefs.getBoolean("enabled", true)

    private fun startBatteryMonitor() {
        val intent = Intent(this, BatteryMonitorService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        }.onFailure {
            batteryPrefs.edit().putBoolean("enabled", false).apply()
            updateBatteryNav()
            showThemedToast("Battery Monitor could not start")
        }
    }

    private fun stopBatteryMonitor() {
        stopService(Intent(this, BatteryMonitorService::class.java))
    }

    private fun ensureBatteryMonitor() {
        if (!batteryEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionRequest)
            return
        }
        startBatteryMonitor()
    }

    private fun toggleBatteryMonitor() {
        if (batteryEnabled()) {
            batteryPrefs.edit().putBoolean("enabled", false).apply()
            stopBatteryMonitor()
            updateBatteryNav()
            showThemedToast("Battery Monitor: Off")
        } else {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                batteryPrefs.edit().putBoolean("pending_enable", true).putBoolean("enabled", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionRequest)
            } else {
                batteryPrefs.edit().putBoolean("enabled", true).apply()
                startBatteryMonitor()
                updateBatteryNav()
                showThemedToast("Battery Monitor: On")
            }
        }
    }

    private fun updateBatteryNav() {
        val text = navBatteryTextView
        val icon = navBatteryIconView
        val on = batteryEnabled()
        findViewById<TextView>(R.id.batteryStatus)?.text = if (on) "Monitor is running" else "Monitor is off"
        findViewById<TextView>(R.id.batteryToggle)?.apply {
            this.text = if (on) "TURN OFF MONITOR" else "TURN ON MONITOR"
            setTextColor(Color.WHITE)
            background = rounded(if (on) Color.rgb(255, 82, 82) else Color.rgb(183, 57, 57), Color.argb(135, 255, 255, 255), 18)
        }
        if (findViewById<View>(R.id.batteryPage)?.visibility == View.VISIBLE) {
            text.setTextColor(RADIO)
            icon.setColorFilter(RADIO)
        }
    }

    private fun showBattery() {
        findViewById<View>(R.id.searchContainer).visibility = View.GONE
        findViewById<View>(R.id.appList).visibility = View.GONE
        findViewById<View>(R.id.batteryPage).visibility = View.VISIBLE
        findViewById<TextView>(R.id.title).text = "Battery Monitor"
        navThermalView.background = null
        navBatteryView.background = getDrawable(R.drawable.bg_bottom_active)
        restoreNavPadding()
        navThermalTextView.setTextColor(MUTED)
        navThermalIconView.setColorFilter(MUTED)
        navBatteryTextView.setTextColor(RADIO)
        navBatteryIconView.setColorFilter(RADIO)
        updateBatteryNav()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != notificationPermissionRequest) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted && batteryPrefs.getBoolean("enabled", true)) {
            batteryPrefs.edit().remove("pending_enable").apply()
            startBatteryMonitor()
            updateBatteryNav()
            showThemedToast("Battery Monitor: On")
        } else if (!granted && batteryPrefs.getBoolean("pending_enable", false)) {
            batteryPrefs.edit().putBoolean("enabled", false).remove("pending_enable").apply()
            updateBatteryNav()
            showThemedToast("Notification permission required")
        }
    }

    private fun showThermal() {
        findViewById<View>(R.id.searchContainer).visibility = View.VISIBLE
        findViewById<View>(R.id.appList).visibility = View.VISIBLE
        findViewById<View>(R.id.batteryPage).visibility = View.GONE
        findViewById<TextView>(R.id.title).text = "Zenith Thermal"
        navThermalView.background = getDrawable(R.drawable.bg_bottom_active)
        navBatteryView.background = null
        restoreNavPadding()
        navThermalTextView.setTextColor(RADIO)
        navThermalIconView.setColorFilter(RADIO)
        navBatteryTextView.setTextColor(MUTED)
        navBatteryIconView.setColorFilter(MUTED)
        updateBatteryNav()
    }

    private class ProfileRadio(context: android.content.Context) : View(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG); private var checked=false; private val d=resources.displayMetrics.density
        fun setChecked(v:Boolean){checked=v;invalidate()}
        override fun onDraw(c:Canvas){ super.onDraw(c); val cx=width/2f; val cy=height/2f; paint.style=Paint.Style.STROKE; paint.strokeWidth=2f*d; paint.color=if(checked) RADIO else MUTED; c.drawCircle(cx,cy,10f*d,paint); if(checked){paint.style=Paint.Style.FILL;paint.color=RADIO;c.drawCircle(cx,cy,4.4f*d,paint)} }
    }
}
