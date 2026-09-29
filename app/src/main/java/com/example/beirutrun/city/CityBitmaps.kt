package com.example.beirutrun.city

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.min

/** Draws the images the city is textured with. */
object CityBitmaps {
    /** The facade style drawn as a glass curtain wall (see [Surface.WALL_GLASS]). */
    private const val GLASS_STYLE = 3
    /** White seafront residential tower (see [Surface.WALL_WHITE]). */
    private const val WHITE_TOWER_STYLE = 4

    /** One window cell of a building wall, repeated across the facade. 128 px so it can tile. */
    fun facade(style: Int): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val s = size.toFloat()

        if (style == GLASS_STYLE) {
            paint.shader = LinearGradient(0f, 0f, s, s, 0xFF7FA7C4.toInt(), 0xFF3C6180.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, s, s, paint)
            paint.shader = null
            paint.color = 0xFF2B3A46.toInt()
            c.drawRect(0f, 0f, s, 6f, paint)
            c.drawRect(0f, 0f, 5f, s, paint)
            return bitmap
        }

        if (style == WHITE_TOWER_STYLE) {
            // Seafront residential tower: white walls, a full-width balcony slab under tall
            // blue-tinted windows (Raouche, Ain El Mreisseh).
            paint.color = 0xFFF4F2EC.toInt()
            c.drawRect(0f, 0f, s, s, paint)
            paint.shader = LinearGradient(0f, s * 0.12f, 0f, s * 0.78f, 0xFF9FC3D9.toInt(), 0xFF4E7A98.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(s * 0.08f, s * 0.12f, s * 0.92f, s * 0.78f, paint)
            paint.shader = null
            paint.color = 0xFFFFFFFF.toInt()
            c.drawRect(s * 0.48f, s * 0.12f, s * 0.52f, s * 0.78f, paint)
            paint.color = 0xFFDAD6CC.toInt()
            c.drawRect(0f, s * 0.8f, s, s * 0.92f, paint)            // balcony slab
            paint.color = 0xFF8E9AA3.toInt()
            c.drawRect(0f, s * 0.72f, s, s * 0.745f, paint)          // railing
            return bitmap
        }

        val wall = intArrayOf(0xFFDCC7A1.toInt(), 0xFFEFE7D6.toInt(), 0xFFA8A8A2.toInt())[style]
        val window = intArrayOf(0xFF3E5569.toInt(), 0xFF4B6B82.toInt(), 0xFF2F3E4A.toInt())[style]
        val frame = intArrayOf(0xFFF3E6CC.toInt(), 0xFFFFFFFF.toInt(), 0xFFC9C9C3.toInt())[style]
        paint.color = wall
        c.drawRect(0f, 0f, s, s, paint)

        val win = RectF(s * 0.26f, s * 0.2f, s * 0.74f, s * 0.78f)
        paint.color = frame
        c.drawRect(win.left - 5f, win.top - 5f, win.right + 5f, win.bottom + 5f, paint)
        paint.shader = LinearGradient(0f, win.top, 0f, win.bottom, lighten(window), window, Shader.TileMode.CLAMP)
        c.drawRect(win, paint)
        paint.shader = null
        paint.color = frame
        c.drawRect(win.centerX() - 2f, win.top, win.centerX() + 2f, win.bottom, paint)

        if (style == 1) {
            // Balcony rail under the window.
            paint.color = 0xFF8D8272.toInt()
            c.drawRect(s * 0.12f, win.bottom + 6f, s * 0.88f, win.bottom + 12f, paint)
        } else if (style == 0) {
            // Wooden shutters.
            paint.color = 0xFF6E8B5A.toInt()
            c.drawRect(win.left - 16f, win.top, win.left - 6f, win.bottom, paint)
            c.drawRect(win.right + 6f, win.top, win.right + 16f, win.bottom, paint)
        }
        return bitmap
    }

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

    private fun lighten(color: Int): Int {
        val r = ((color shr 16 and 0xFF) + 60).coerceAtMost(255)
        val g = ((color shr 8 and 0xFF) + 60).coerceAtMost(255)
        val b = ((color and 0xFF) + 60).coerceAtMost(255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
