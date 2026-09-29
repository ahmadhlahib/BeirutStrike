package com.example.beirutrun.city

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** A thumb stick. Reports x and y in -1..1 (y is down, so pushing up gives a negative y). */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onMove: ((Float, Float) -> Unit)? = null

    private var knobX = 0f
    private var knobY = 0f
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = 0x88FFFFFF.toInt()
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDDFFFFFF.toInt() }

    private val radius get() = min(width, height) / 2f * 0.8f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, radius, basePaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawCircle(cx + knobX, cy + knobY, radius * 0.42f, knobPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                var dx = event.x - width / 2f
                var dy = event.y - height / 2f
                val len = hypot(dx, dy)
                if (len > radius) {
                    dx = dx / len * radius
                    dy = dy / len * radius
                }
                knobX = dx
                knobY = dy
                onMove?.invoke(dx / radius, dy / radius)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobX = 0f
                knobY = 0f
                onMove?.invoke(0f, 0f)
            }
        }
        invalidate()
        return true
    }
}
