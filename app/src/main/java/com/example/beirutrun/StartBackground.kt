package com.example.beirutrun

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.ViewGroup

/**
 * The picture behind the start screens (login, rooms, teams): the game logo
 * `assets/models/ic_logo.png`. To use another picture instead, put it in
 * `app/src/main/assets/backgrounds/` named `start.jpg` (or `.png` / `.webp`). With neither, the
 * screens keep their purple-to-orange gradient.
 */
object StartBackground {
    private val extensions = listOf("jpg", "jpeg", "png", "webp")
    private const val LOGO = "models/ic_logo.png"
    /** Share of each side of the logo that is cut off: its black rounded frame. */
    private const val LOGO_FRAME = 0.035f
    private var cached: Bitmap? = null
    private var inset = 0f
    private var looked = false

    /** Puts the start image behind [activity]'s whole screen, if there is one. */
    fun applyTo(activity: Activity) {
        val bitmap = load(activity) ?: return
        val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) ?: return
        root.background = CenterCropDrawable(bitmap, inset)
    }

    private fun load(activity: Activity): Bitmap? {
        if (looked) return cached
        looked = true
        for (ext in extensions) {
            cached = runCatching {
                activity.assets.open("backgrounds/start.$ext").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (cached != null) break
        }
        if (cached == null) {
            cached = runCatching { activity.assets.open(LOGO).use { BitmapFactory.decodeStream(it) } }.getOrNull()
            if (cached != null) inset = LOGO_FRAME
        }
        return cached
    }

    /**
     * Fills the screen with the image without stretching it, darkened so text stays readable.
     * [inset] is the share of each side left out (a frame around the picture).
     */
    private class CenterCropDrawable(private val bitmap: Bitmap, private val inset: Float) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val shade = Paint().apply { color = 0x66000000 }
        private val src = Rect()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val iw = bitmap.width * (1 - 2 * inset)
            val ih = bitmap.height * (1 - 2 * inset)
            val scale = maxOf(b.width() / iw, b.height() / ih)
            val w = (b.width() / scale).toInt()
            val h = (b.height() / scale).toInt()
            val left = (bitmap.width - w) / 2
            val top = (bitmap.height - h) / 2
            src.set(left, top, left + w, top + h)
            canvas.drawBitmap(bitmap, src, b, paint)
            canvas.drawRect(b, shade)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }
}
