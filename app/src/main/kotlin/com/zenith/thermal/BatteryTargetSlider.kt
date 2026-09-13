package com.zenith.thermal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.roundToInt

class BatteryTargetSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val d = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()

    private var value = 100
    private var listener: ((Int, Boolean) -> Unit)? = null
    private var dragging = false
    private var downX = 0f
    private var lastX = 0f

    init {
        isFocusable = true
        isClickable = true
        setWillNotDraw(false)
        trackPaint.color = context.getColor(R.color.muted)
        progressPaint.color = context.getColor(R.color.accent)
        thumbPaint.color = context.getColor(R.color.accent)
    }

    fun setProgress(progress: Int) {
        value = progress.coerceIn(1, 100)
        invalidate()
    }

    fun getProgress(): Int = value

    fun setOnProgressChangedListener(callback: (progress: Int, fromUser: Boolean) -> Unit) {
        listener = callback
    }

    private fun left(): Float = 22f * d
    private fun right(): Float = (width - 22f * d).coerceAtLeast(left() + 1f)
    private fun thumbRadius(): Float = 10f * d
    private fun centerY(): Float = height / 2f
    private fun thumbX(): Float = left() + (right() - left()) * ((value - 1) / 99f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = centerY()
        val l = left()
        val r = right()
        val radius = 3f * d
        track.set(l, cy - radius, r, cy + radius)
        canvas.drawRoundRect(track, radius, radius, trackPaint)
        val x = thumbX()
        canvas.drawRoundRect(RectF(l, cy - radius, x, cy + radius), radius, radius, progressPaint)
        canvas.drawCircle(x, cy, thumbRadius(), thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val inTrack = event.y >= centerY() - 28f * d && event.y <= centerY() + 28f * d
                if (!inTrack) return false
                dragging = true
                downX = event.x
                lastX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val dx = event.x - downX
                if (abs(dx) >= touchSlop || abs(event.x - lastX) >= 1f) {
                    lastX = event.x
                    updateFromX(event.x)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                updateFromX(event.x)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return dragging
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromX(x: Float) {
        val span = (right() - left()).coerceAtLeast(1f)
        val fraction = ((x - left()) / span).coerceIn(0f, 1f)
        val next = (1f + fraction * 99f).roundToInt().coerceIn(1, 100)
        if (next != value) {
            value = next
            invalidate()
            listener?.invoke(value, true)
        }
    }
}
