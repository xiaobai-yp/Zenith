// adaptive_x_axis.kt — Generate adaptive X-axis labels based on data point count
package com.zenith.thermal.ui.screens

/**
 * Generate ~6 evenly spaced X-axis labels based on data point count.
 * Each point ≈ 1 second (Scene sampling rate).
 */
internal fun generateAdaptiveXLabels(pointCount: Int): List<String> {
    if (pointCount <= 1) return listOf("0")
    val durationSec = pointCount  // 1 point ≈ 1s
    val numLabels = 6
    val labels = mutableListOf<String>()
    for (i in 0 until numLabels) {
        val sec = (durationSec * i) / (numLabels - 1)
        labels.add(formatDuration(sec))
    }
    return labels
}

private fun formatDuration(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return when {
        m > 0 && s > 0 -> "${m}m${s}s"
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}