package com.example.beirutrun

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * The picture shared to WhatsApp Status, Facebook and Instagram stories (see InviteFriends): a
 * phone-screen-sized (1080 × 1920) poster with the logo, the title, a look at the game, and
 * where to get it, inviting friends in the player's name.
 */
object StoryCard {
    private const val W = 1080
    private const val H = 1920
    private const val GOLD = 0xFFFFC83D.toInt()

    /** Draws the poster for [playerName] and saves it for sharing; returns its content Uri, or null. */
    fun create(context: Context, playerName: String?): Uri? = runCatching {
        val bitmap = draw(context, playerName)
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "beirut_strike_story.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()

    private fun draw(context: Context, playerName: String?): Bitmap {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Night-blue background, lighter at the top, with a gold glow behind the logo.
        paint.shader = LinearGradient(0f, 0f, 0f, H.toFloat(), 0xFF1E2E44.toInt(), 0xFF0A1018.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        paint.shader = android.graphics.RadialGradient(W / 2f, 330f, 420f, 0x55FFC83D, 0x00FFC83D, Shader.TileMode.CLAMP)
        c.drawCircle(W / 2f, 330f, 420f, paint)
        paint.shader = null

        // The logo, round.
        asset(context, "models/ic_logo.png")?.let { logo ->
            val size = 300f
            val r = RectF(W / 2f - size / 2, 160f, W / 2f + size / 2, 160f + size)
            c.save()
            c.clipPath(Path().apply { addRoundRect(r, 70f, 70f, Path.Direction.CW) })
            c.drawBitmap(logo, null, r, paint)
            c.restore()
            logo.recycle()
        }

        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text(c, "BEIRUT STRIKE", 600f, 118f, GOLD, bold, spacing = 0.06f)
        text(c, context.getString(R.string.story_card_tagline), 680f, 46f, 0xE6FFFFFF.toInt(), Typeface.DEFAULT)

        // A look at the game, framed in gold.
        asset(context, "promo/gameplay.jpg")?.let { shot ->
            val w = 1000f
            val h = w * shot.height / shot.width
            val r = RectF((W - w) / 2, 780f, (W + w) / 2, 780f + h)
            c.save()
            c.clipPath(Path().apply { addRoundRect(r, 36f, 36f, Path.Direction.CW) })
            c.drawBitmap(shot, null, r, paint)
            c.restore()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 8f
            paint.color = GOLD
            c.drawRoundRect(r, 36f, 36f, paint)
            paint.style = Paint.Style.FILL
            shot.recycle()
        }

        val join = if (playerName.isNullOrBlank()) context.getString(R.string.story_card_join_anyone)
        else context.getString(R.string.story_card_join, playerName.take(20))
        text(c, join, 1430f, 72f, 0xFFFFFFFF.toInt(), bold)

        // "Free on Google Play", as a button.
        val pill = RectF(W / 2f - 380f, 1530f, W / 2f + 380f, 1680f)
        paint.color = GOLD
        c.drawRoundRect(pill, 75f, 75f, paint)
        text(c, context.getString(R.string.story_card_free), pill.centerY() + 20f, 54f, 0xFF101823.toInt(), bold, spacing = 0.04f)
        text(c, context.getString(R.string.story_card_search), 1780f, 44f, 0xB3FFFFFF.toInt(), Typeface.DEFAULT)
        return bitmap
    }

    /** [s] centred across the poster with its baseline at [y]. */
    private fun text(c: Canvas, s: String, y: Float, size: Float, color: Int, face: Typeface, spacing: Float = 0f) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = face
            letterSpacing = spacing
            textAlign = Paint.Align.CENTER
            setShadowLayer(8f, 0f, 3f, 0x99000000.toInt())
        }
        // Long text (a long name, or a translation) shrinks to fit.
        val fit = (W - 80f) / p.measureText(s)
        if (fit < 1f) p.textSize = size * fit
        c.drawText(s, W / 2f, y, p)
    }

    private fun asset(context: Context, path: String): Bitmap? =
        runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull()
}
