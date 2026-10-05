package com.example.beirutrun.city

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.floor
import kotlin.random.Random

/**
 * Puts a character's own clothes into army kit: their textures repainted as woodland
 * camouflage (see [MaterialRole.CAMO]) or dark boots ([MaterialRole.BOOTS]), keeping the
 * folds, seams and pockets of the original. The camouflage is light, green, brown and
 * near-black tones of the team's uniform colour (drawn with [color]), so each team still
 * looks like itself.
 */
object ArmyOutfit {
    /** Clothes textures are repainted at no more than this size (their detail is plenty for a phone). */
    private const val MAX_SIZE = 512

    /** The texture for a material in [role], from its image [bytes]; null if it doesn't decode. */
    fun bitmap(bytes: ByteArray, role: MaterialRole?): Bitmap? {
        val options = BitmapFactory.Options()
        if (role == MaterialRole.CAMO || role == MaterialRole.BOOTS) {
            // Read the size first, then decode no bigger than needed.
            options.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            var sample = 1
            while (maxOf(options.outWidth, options.outHeight) / (sample * 2) >= MAX_SIZE) sample *= 2
            options.inJustDecodeBounds = false
            options.inSampleSize = sample
            options.inMutable = true
        }
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (role != MaterialRole.CAMO && role != MaterialRole.BOOTS) return original
        val w = original.width; val h = original.height
        val px = IntArray(w * h)
        original.getPixels(px, 0, w, 0, 0, w, h)
        repaint(px, w, h, role)
        val out = if (original.isMutable) original else original.copy(Bitmap.Config.ARGB_8888, true).also { original.recycle() }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** What colour to draw a material in [role] with, for a team in [uniform]. */
    fun color(role: MaterialRole, uniform: Int): Int = when (role) {
        // The textures are darker than white; brightened, their light tone is about the uniform.
        MaterialRole.CAMO -> scale(uniform, 1.45f)
        MaterialRole.BOOTS -> BOOTS_COLOR
        else -> uniform
    }

    /**
     * Repaints the ARGB pixels [px] ([w] × [h]) of a texture in [role] (camouflage or boots), in
     * place: each pixel's brightness against the texture's average (its folds and seams) shades
     * the new colour; see-through stays see-through.
     */
    fun repaint(px: IntArray, w: Int, h: Int, role: MaterialRole) {
        val paint: (Float, Float, Float) -> Int = if (role == MaterialRole.BOOTS) ::leather else ::camo
        var sum = 0.0
        var count = 0
        for (c in px) if (c ushr 24 > 16) { sum += luma(c); count++ }
        val mean = if (count > 0) (sum / count).toFloat().coerceAtLeast(0.05f) else 0.5f
        for (i in px.indices) {
            val c = px[i]
            val detail = (luma(c) / mean).coerceIn(0.4f, 1.6f)
            val rgb = paint((i % w + 0.5f) / w, (i / w + 0.5f) / h, detail)
            px[i] = (c and 0xFF000000.toInt()) or (rgb and 0xFFFFFF)
        }
    }

    /**
     * Woodland camouflage: blotches of green, brown and near-black over a light base, from two
     * layers of smooth noise; the cloth's own shading on top, softened so prints and logos fade.
     */
    private fun camo(u: Float, v: Float, detail: Float): Int {
        val a = BLOTCHES.at(u, v)
        val b = STREAKS.at(u + 0.37f, v + 0.61f)
        val tone = when {
            a < 0.33f && b < 0.38f -> BLACK_TONE
            b > 0.6f -> BROWN_TONE
            a > 0.55f -> GREEN_TONE
            else -> LIGHT_TONE
        }
        val shade = 0.78f + 0.22f * detail
        return rgb(tone[0] * shade, tone[1] * shade, tone[2] * shade)
    }

    /**
     * Boots: grey from the shoe's own shading, to be drawn in a dark leather colour; kept faint so
     * a sneaker's stripes and logos all but vanish.
     */
    private fun leather(@Suppress("UNUSED_PARAMETER") u: Float, @Suppress("UNUSED_PARAMETER") v: Float, detail: Float): Int {
        val g = (0.72f + 0.16f * (detail - 1f)).coerceIn(0.62f, 0.85f)
        return rgb(g, g, g)
    }

    /** Smooth random noise over the texture (0..1), summed from a few sizes of blotch. */
    private class Noise(seed: Int, private val cells: IntArray, private val weights: FloatArray) {
        private val grids = cells.mapIndexed { k, n -> Random(seed + k).let { r -> FloatArray(n * n) { r.nextFloat() } } }

        fun at(u: Float, v: Float): Float {
            var total = 0f
            for (k in cells.indices) {
                val n = cells[k]
                val x = u * n; val y = v * n
                val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
                val fx = smooth(x - x0); val fy = smooth(y - y0)
                val g = grids[k]
                fun cell(i: Int, j: Int) = g[((j % n + n) % n) * n + (i % n + n) % n]
                val top = cell(x0, y0) + (cell(x0 + 1, y0) - cell(x0, y0)) * fx
                val bottom = cell(x0, y0 + 1) + (cell(x0 + 1, y0 + 1) - cell(x0, y0 + 1)) * fx
                total += (top + (bottom - top) * fy) * weights[k]
            }
            return total
        }

        private fun smooth(t: Float) = t * t * (3f - 2f * t)
    }

    private val BLOTCHES = Noise(11, intArrayOf(9, 19, 41), floatArrayOf(0.6f, 0.28f, 0.12f))
    private val STREAKS = Noise(29, intArrayOf(12, 25, 53), floatArrayOf(0.58f, 0.3f, 0.12f))

    /** The camouflage's tones (red, green, blue multipliers of the uniform colour). */
    private val LIGHT_TONE = floatArrayOf(0.98f, 0.97f, 0.86f)
    private val GREEN_TONE = floatArrayOf(0.66f, 0.74f, 0.56f)
    private val BROWN_TONE = floatArrayOf(0.56f, 0.46f, 0.34f)
    private val BLACK_TONE = floatArrayOf(0.25f, 0.25f, 0.23f)
    private const val BOOTS_COLOR = 0xFF4A4038.toInt()

    private fun luma(c: Int) = (0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)) / 255f

    private fun rgb(r: Float, g: Float, b: Float): Int {
        fun ch(x: Float) = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    private fun scale(color: Int, k: Float): Int {
        fun ch(shift: Int) = (((color shr shift) and 0xFF) * k).toInt().coerceAtMost(255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
