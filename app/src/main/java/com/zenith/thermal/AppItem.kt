package com.zenith.thermal

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

data class AppItem(
    val info: ApplicationInfo,
    val pm: PackageManager,
    val profileId: Int = -1
) {
    val pkg: String = info.packageName
    val name: String = info.loadLabel(pm).toString()
    val icon = info.loadIcon(pm)
}
