package com.example.beirutrun.city

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.min

/** Draws the labels, bubbles and crosshair shown in the city (its textures are in [CityTextures]). */
object CityBitmaps {
    /** A white speech bubble with a tail pointing down at the speaker. */
    fun speechBubble(text: String): Bitmap {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A1A1A.toInt()
            textSize = 38f
        }
        val maxWidth = 460
        val width = min(ceil(StaticLayout.getDesiredWidth(text, paint)).toInt(), maxWidth).coerceAtLeast(40)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(5)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
        val pad = 22f
        val tail = 22f
        val stroke = 4f
        val w = (layout.width + pad * 2 + stroke * 2).toInt()
        val h = (layout.height + pad * 2 + tail + stroke * 2).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)

        val body = RectF(stroke, stroke, w - stroke, h - tail - stroke)
        val path = Path().apply {
            addRoundRect(body, 26f, 26f, Path.Direction.CW)
            moveTo(w / 2f - 18f, body.bottom - 2f)
            lineTo(w / 2f, h - stroke)
            lineTo(w / 2f + 18f, body.bottom - 2f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = 0xFF333333.toInt()
        }
        c.drawPath(path, outline)
        c.drawPath(path, fill)
        c.save()
        c.translate(stroke + pad, stroke + pad)
        layout.draw(c)
        c.restore()
        return bitmap
    }

    /** A pill with the player's name: dark, or in their team colour when [teamColor] is given. */
    fun nameTag(name: String, teamColor: Int? = null): Bitmap {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 30f
            isFakeBoldText = true
        }
        val tw = paint.measureText(name)
        val w = (tw + 36f).toInt()
        val h = 48
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawRoundRect(
            RectF(0f, 0f, w.toFloat(), h.toFloat()), 24f, 24f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (teamColor != null) (teamColor and 0x00FFFFFF) or 0xDD000000.toInt() else 0xAA000000.toInt()
            },
        )
        c.drawText(name, 18f, h / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        return bitmap
    }

    /** The aiming crosshair: a ring with four ticks and a centre dot, outlined so it shows on any background. */
    fun crosshair(color: Int): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val mid = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        // Dark outline first, then the colour on top.
        for ((strokeColor, width) in listOf(0xAA000000.toInt() to 13f, color to 7f)) {
            paint.color = strokeColor
            paint.strokeWidth = width
            paint.style = Paint.Style.STROKE
            c.drawCircle(mid, mid, 34f, paint)
            c.drawLine(mid, 8f, mid, 30f, paint)
            c.drawLine(mid, size - 30f, mid, size - 8f, paint)
            c.drawLine(8f, mid, 30f, mid, paint)
            c.drawLine(size - 30f, mid, size - 8f, mid, paint)
            paint.style = Paint.Style.FILL
            c.drawCircle(mid, mid, width * 0.75f, paint)
        }
        return bitmap
    }
}
