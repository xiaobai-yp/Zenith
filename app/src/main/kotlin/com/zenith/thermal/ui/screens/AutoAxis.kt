// auto_axis.kt — Dynamic Y-axis computation for charts
package com.zenith.thermal.ui.screens

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round

/** Compute dynamic Y-axis: (min, max, tickLabels) from data values. */
internal fun autoAxis(
    values: List<Float>,
    minFloor: Float = 0f,
    maxCeiling: Float? = null,
    niceStep: Boolean = true,
): Triple<Float, Float, List<String>> {
    if (values.isEmpty()) return Triple(minFloor, minFloor + 100f, listOf("100", "50", "0"))
    val dataMin = values.min()
    val dataMax = values.max()
    // Pad range
    val range = dataMax - dataMin
    val pad = (range * 0.15f).coerceAtLeast(1f)
    var lo = floor((dataMin - pad).coerceAtLeast(minFloor))
    var hi = (dataMax + pad).coerceAtMost(maxCeiling ?: Float.MAX_VALUE)
    if (hi <= lo) hi = lo + 10f
    // Nice step
    if (niceStep) {
        val rawStep = (hi - lo) / 5f
        val magnitude = 10f.pow(floor(kotlin.math.log10(rawStep.toDouble())).toInt())
        val residual = rawStep / magnitude
        val niceStep = when {
            residual <= 1.5f -> 1f * magnitude
            residual <= 3f -> 2f * magnitude
            residual <= 7f -> 5f * magnitude
            else -> 10f * magnitude
        }
        lo = floor(lo / niceStep) * niceStep
        hi = ceil(hi / niceStep) * niceStep
    }
    val steps = 5
    val step = (hi - lo) / steps
    val ticks = (0..steps).map { i -> round(hi - i * step).toInt().toString() }
    return Triple(lo, hi, ticks)
}

/** Auto axis for FPS: snaps max to nearest 30 (30/60/90/120). */
internal fun fpsAxis(values: List<Float>): Triple<Float, Float, List<String>> {
    if (values.isEmpty()) return Triple(0f, 120f, listOf("120", "90", "60", "30"))
    val dataMax = values.max()
    val max = when {
        dataMax > 90f -> 120f
        dataMax > 60f -> 90f
        dataMax > 30f -> 60f
        else -> 30f
    }
    val ticks = when (max) {
        120f -> listOf("120", "90", "60", "30")
        90f -> listOf("90", "60", "30")
        60f -> listOf("60", "30")
        else -> listOf("30")
    }
    return Triple(0f, max, ticks)
}

/** Auto axis for temperature: dynamic from data with padding. */
internal fun tempAxis(values: List<Float>): Triple<Float, Float, List<String>> {
    if (values.isEmpty()) return Triple(30f, 50f, listOf("50", "45", "40", "35", "30"))
    val (lo, hi, ticks) = autoAxis(values, minFloor = 20f, maxCeiling = 80f)
    return Triple(lo, hi, ticks)
}