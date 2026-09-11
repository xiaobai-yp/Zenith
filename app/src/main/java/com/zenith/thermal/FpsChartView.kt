package com.zenith.thermal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.Locale

/** FPS line chart view — usable from both Kotlin code and XML layout. */
class FpsChartView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    data class Sample(val tsSec: Long, val fps: Double)

    companion object {
        private const val ACCENT = 0xFF5EA7FF.toInt()
        private const val TEXT   = 0xFFF2F5F6.toInt()
        private const val MUTED  = 0xFFB8C6CA.toInt()
    }

    private val linePaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * density; color = ACCENT }
    private val dotPaint      = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = ACCENT }
    private val textPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; textSize = 12f * density; textAlign = Paint.Align.LEFT }
    private val gridPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30FFFFFF.toInt(); strokeWidth = 1f }
    private val gridTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = 10f * density; textAlign = Paint.Align.RIGHT }
    private val fillPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var data = emptyList<Sample>()

    fun setData(samples: List<Sample>) {
        data = samples.toList()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val d = density
        val padL = 38 * d; val padR = 10 * d; val padT = 8 * d; val padB = 20 * d
        val w = width - padL - padR
        val h = height - padT - padB

        if (data.isEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            c.drawText("Start benchmark to see FPS chart", width / 2f, height / 2f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            return
        }

        var maxFps = data.maxOf { it.fps }.coerceAtLeast(60.0) * 1.1
        if (maxFps < 1) maxFps = 1.0

        // Grid lines
        for (i in 1..4) {
            val y = padT + h * (1f - i / 4f)
            c.drawLine(padL, y, padL + w, y, gridPaint)
            c.drawText("${Math.round(maxFps * i / 4)}", padL - 4 * d, y + 4 * d, gridTextPaint)
        }

        val n = data.size
        if (n == 1) {
            c.drawCircle(padL + w / 2f, padT + h * (1 - data[0].fps / maxFps).toFloat(), 5 * d, dotPaint)
            return
        }

        // FPS line + area fill
        val path = Path(); val fillPath = Path()
        data.forEachIndexed { i, s ->
            val x = padL + w * i / (n - 1)
            val y = padT + h * (1 - s.fps / maxFps).toFloat()
            if (i == 0) { path.moveTo(x, y); fillPath.moveTo(x, y) }
            else        { path.lineTo(x, y); fillPath.lineTo(x, y) }
        }
        fillPath.lineTo(padL + w, padT + h)
        fillPath.lineTo(padL, padT + h)
        fillPath.close()
        fillPaint.color = 0x405EA7FF
        c.drawPath(fillPath, fillPaint)
        c.drawPath(path, linePaint)

        // Latest dot + label
        val lx = padL + w; val ly = padT + h * (1 - data.last().fps / maxFps).toFloat()
        c.drawCircle(lx, ly, 4 * d, dotPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        c.drawText(String.format(Locale.US, "%.1f", data.last().fps), lx - 6 * d, ly - 6 * d, textPaint)

        // Point count
        textPaint.textAlign = Paint.Align.LEFT
        c.drawText("$n points", padL, height - 2f, textPaint)
    }
}
