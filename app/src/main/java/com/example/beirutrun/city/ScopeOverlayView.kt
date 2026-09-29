package com.example.beirutrun.city

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * What you see through the AK-47's scope, drawn over the zoomed-in city: black all round a round
 * lens, a darker rim, and fine crosshairs with range marks. Touches pass through to the city.
 */
class ScopeOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val thick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        strokeWidth = 5f * resources.displayMetrics.density
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD32F2F.toInt() }
    private val mask = Path()

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = radius(w, h)
        // Everything outside the lens is black.
        mask.reset()
        mask.fillType = Path.FillType.EVEN_ODD
        mask.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        mask.addCircle(w / 2f, h / 2f, r, Path.Direction.CW)
        // The lens darkens towards its edge.
        rim.shader = RadialGradient(
            w / 2f, h / 2f, r,
            intArrayOf(0x00000000, 0x00000000, 0x99000000.toInt(), 0xFF000000.toInt()),
            floatArrayOf(0f, 0.8f, 0.96f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = radius(width, height)
        canvas.drawPath(mask, black)
        canvas.drawCircle(cx, cy, r, rim)
        // Thick posts from the edge, thin lines in the middle, a red dot at the centre.
        val gap = r * 0.55f
        canvas.drawLine(cx - r, cy, cx - gap, cy, thick)
        canvas.drawLine(cx + gap, cy, cx + r, cy, thick)
        canvas.drawLine(cx, cy + gap, cx, cy + r, thick)
        canvas.drawLine(cx - gap, cy, cx + gap, cy, line)
        canvas.drawLine(cx, cy - r, cx, cy + gap, line)
        val tick = r * 0.03f
        for (i in 1..4) {
            val d = gap * i / 5f
            canvas.drawLine(cx - tick, cy + d, cx + tick, cy + d, line)
            canvas.drawLine(cx - d, cy - tick, cx - d, cy + tick, line)
            canvas.drawLine(cx + d, cy - tick, cx + d, cy + tick, line)
        }
        canvas.drawCircle(cx, cy, 2.5f * resources.displayMetrics.density, dot)
    }

    private fun radius(w: Int, h: Int) = min(w, h) * 0.46f
}
