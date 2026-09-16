// auto_axis.kt — Dynamic Y-axis computation for charts (Scene-style)
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
    maxTicks: Int = 6,
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
        val rawStep = (hi - lo) / maxTicks.toFloat()
        val magnitude = 10f.pow(floor(kotlin.math.log10(rawStep.toDouble())).toInt())
        val residual = rawStep / magnitude
        val ns = when {
            residual <= 1.5f -> 1f * magnitude
            residual <= 3f -> 2f * magnitude
            residual <= 7f -> 5f * magnitude
            else -> 10f * magnitude
        }
        lo = floor(lo / ns) * ns
        hi = ceil(hi / ns) * ns
    }
    val steps = ((hi - lo) / ((hi - lo) / maxTicks.toFloat())).toInt().coerceIn(2, maxTicks)
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

/** Auto axis for CPU frequency: Scene-style fixed steps (300/400 increments). */
internal fun cpuFreqAxis(values: List<Float>): Triple<Float, Float, List<String>> {
    if (values.isEmpty()) return Triple(0f, 3000f, listOf("3000", "2000", "1000", "0"))
    val dataMax = values.max()
    // Scene pattern: step 300 if max≤3300, step 400 if max≤4400, else step 400
    val (step, max) = when {
        dataMax <= 3300 -> Pair(300, 3300f)
        dataMax <= 4400 -> Pair(400, 4400f)
        else -> Pair(400, (ceil(dataMax / 400f) * 400f))
    }
    val ticks = generateSequence(max.toInt()) { it - step }.takeWhile { it >= 0 }.map { it.toString() }.toList()
    return Triple(0f, max, ticks)
}

/** Auto axis for temperature: Scene-style with 2 labels for narrow range. */
internal fun tempAxis(values: List<Float>): Triple<Float, Float, List<String>> {
    if (values.isEmpty()) return Triple(30f, 50f, listOf("50", "40", "30"))
    val dataMin = values.min()
    val dataMax = values.max()
    // Scene pattern: round to nearest 5, show only min/max labels
    val lo = floor(dataMin / 5f) * 5f - 5f
    val hi = ceil(dataMax / 5f) * 5f + 5f
    // Generate 2-3 ticks: bottom, middle (if range > 10), top
    val ticks = if (hi - lo <= 10f) {
        listOf(hi.toInt().toString(), lo.toInt().toString())
    } else {
        val mid = round((hi + lo) / 2f / 5f) * 5f
        listOf(hi.toInt().toString(), mid.toInt().toString(), lo.toInt().toString())
    }
    return Triple(lo, hi, ticks)
}

/** Auto axis for generic narrow-range data (battery, GPU usage etc): 2-3 labels. */
internal fun narrowAxis(values: List<Float>, minFloor: Float = 0f): Triple<Float, Float, List<String>> {
    if (values.isEmpty()) return Triple(0f, 100f, listOf("100", "50", "0"))
    val dataMin = values.min()
    val dataMax = values.max()
    val lo = floor(dataMin.coerceAtLeast(minFloor) / 10f) * 10f
    val hi = ceil(dataMax / 10f) * 10f
    val step = ((hi - lo) / 2f).coerceAtLeast(1f)
    val ticks = generateSequence(hi) { it - step }.takeWhile { it >= lo - 0.5f }.map { it.toInt().toString() }.toList()
    return Triple(lo, hi, ticks)
}
