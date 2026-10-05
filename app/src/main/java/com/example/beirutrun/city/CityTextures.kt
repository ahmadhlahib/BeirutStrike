package com.example.beirutrun.city

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.random.Random

/**
 * The repeating textures the city is drawn with (see [Surface.texture]): building facades two
 * windows wide and two floors high, a ground-floor row of shops, and the street and ground
 * materials. All are power-of-two sized so they can repeat and be mipmapped. The facades carry
 * their own colours; the ground materials are near white, tinted by their surface's colour.
 */
object CityTextures {
    const val SANDSTONE = 0
    const val CREAM = 1
    const val CONCRETE = 2
    const val GLASS = 3
    const val WHITE_TOWER = 4
    const val SHOPFRONT = 5
    const val ASPHALT = 6
    const val SLABS = 7
    const val PAVING = 8
    const val GRASS = 9
    const val SAND = 10
    const val ROOF = 11
    const val GROUND = 12
    const val BARK = 13
    const val PALM_BARK = 14
    const val LEAF = 15
    const val FROND = 16
    const val COUNT = 17

    /** Metres a facade texture covers, across (two windows) and up (two floors). */
    const val FACADE_SPAN = 6.4f
    /** Metres the shop row covers across (two shops); it is [SHOP_HEIGHT] high. */
    const val SHOP_SPAN = 6.4f
    const val SHOP_HEIGHT = 4f

    /** Metres one repeat of each ground material covers (a power of two, so tiles meet seamlessly). */
    fun groundSpan(style: Int) = when (style) {
        ASPHALT, GRASS, SAND -> 8f
        GROUND -> 16f
        else -> 4f
    }

    fun draw(style: Int): Bitmap = when (style) {
        SANDSTONE -> sandstone()
        CREAM -> cream()
        CONCRETE -> concrete()
        GLASS -> glass()
        WHITE_TOWER -> whiteTower()
        SHOPFRONT -> shopfront()
        ASPHALT -> asphalt()
        SLABS -> slabs()
        PAVING -> paving()
        GRASS -> grass()
        SAND -> plain(style, 0.05f, 0.03f, 0.08f)
        ROOF -> roof()
        BARK -> bark()
        PALM_BARK -> palmBark()
        LEAF -> leaves()
        FROND -> frond()
        else -> plain(style, 0.08f, 0.06f, 0.06f)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- Facades (256 px = 6.4 m: 128 px per window cell and per floor) ------------------------

    /** Downtown's restored Ottoman and mandate stone: coursed blocks, arched windows, green shutters. */
    private fun sandstone(): Bitmap {
        val (b, c) = canvas(256, 256)
        val rnd = Random(11)
        val wall = 0xFFD8C19A.toInt()
        fill(c, 0f, 0f, 256f, 256f, wall)
        // Stone courses, the joints staggered.
        for (row in 0 until 16) {
            val y = row * 16f
            fill(c, 0f, y, 256f, y + 1f, shade(wall, 0.88f))
            var x = if (row % 2 == 0) 0f else 16f
            while (x < 256f) { fill(c, x, y, x + 1f, y + 16f, shade(wall, 0.9f)); x += 32f }
        }
        for (floor in 0..1) for (cell in 0..1) {
            val x0 = cell * 128f; val y0 = floor * 128f
            ledge(c, x0, y0 + 116f, x0 + 128f, wall)
            val l = x0 + 38f; val r = x0 + 90f; val t = y0 + 18f; val bt = y0 + 102f
            val closed = rnd.nextFloat() < 0.25f
            window(c, l, t, r, bt, 0xFFEFE6D2.toInt(), wall, rnd, arched = true, curtains = false, shutters = closed)
            // Keystone over the arch.
            fill(c, x0 + 60f, t - 6f, x0 + 68f, t + 2f, shade(wall, 1.08f))
            if (!closed) {
                shutter(c, l - 26f, t + 26f, l - 4f, bt)
                shutter(c, r + 4f, t + 26f, r + 26f, bt)
            }
        }
        weather(b, 11, 0.05f, 64 to 0.05f, 16 to 0.04f)
        return b
    }

    /** Hamra's plastered mid-rise blocks: balcony doors behind metal rails, the odd AC unit. */
    private fun cream(): Bitmap {
        val (b, c) = canvas(256, 256)
        val rnd = Random(23)
        val wall = 0xFFEAE0CB.toInt()
        fill(c, 0f, 0f, 256f, 256f, wall)
        for (floor in 0..1) for (cell in 0..1) {
            val x0 = cell * 128f; val y0 = floor * 128f
            window(c, x0 + 30f, y0 + 16f, x0 + 98f, y0 + 110f, 0xFFF7F3EA.toInt(), wall, rnd,
                curtains = rnd.nextFloat() < 0.5f, sill = false)
            // Balcony slab and its shadow, then the rail in front of the door.
            fill(c, x0, y0 + 110f, x0 + 128f, y0 + 119f, 0xFFF4EEE2.toInt())
            fill(c, x0, y0 + 119f, x0 + 128f, y0 + 126f, 0x66000000)
            rail(c, x0 + 2f, y0 + 84f, x0 + 126f, y0 + 110f, 0xFF4F4C47.toInt())
            if (rnd.nextFloat() < 0.4f) acUnit(c, x0 + 102f, y0 + 38f)
        }
        weather(b, 23, 0.04f, 64 to 0.05f, 16 to 0.03f)
        return b
    }

    /** Bare concrete: expressed floor slabs, ribbon windows, rain streaks and AC units. */
    private fun concrete(): Bitmap {
        val (b, c) = canvas(256, 256)
        val rnd = Random(37)
        val wall = 0xFFA6A49D.toInt()
        fill(c, 0f, 0f, 256f, 256f, wall)
        for (floor in 0..1) {
            val y0 = floor * 128f
            fill(c, 0f, y0, 256f, y0 + 14f, shade(wall, 1.1f))
            fill(c, 0f, y0 + 14f, 256f, y0 + 17f, shade(wall, 0.7f))
            for (cell in 0..1) {
                val x0 = cell * 128f
                window(c, x0 + 10f, y0 + 32f, x0 + 118f, y0 + 94f, 0xFFB8BCBF.toInt(), wall, rnd,
                    curtains = rnd.nextFloat() < 0.3f, panes = 3)
                streak(c, x0 + 12f + rnd.nextFloat() * 90f, y0 + 102f, 6f + rnd.nextFloat() * 10f, 22f, 0x40000000)
                if (rnd.nextFloat() < 0.35f) streak(c, x0 + rnd.nextFloat() * 110f, y0 + 17f, 4f, 30f, 0x30603010)
                if (rnd.nextFloat() < 0.5f) acUnit(c, x0 + 20f + rnd.nextFloat() * 70f, y0 + 100f)
            }
        }
        weather(b, 37, 0.06f, 64 to 0.07f, 16 to 0.05f)
        return b
    }

    /** A curtain wall: tinted panels between mullions, a dark spandrel at each floor slab. */
    private fun glass(): Bitmap {
        val (b, c) = canvas(256, 256)
        val rnd = Random(41)
        for (floor in 0..1) {
            val y0 = floor * 128f
            for (panel in 0 until 8) {
                val x0 = panel * 32f
                paint.shader = LinearGradient(0f, y0, 0f, y0 + 100f, 0xFF93B6CE.toInt(), 0xFF3B6383.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(x0, y0, x0 + 32f, y0 + 100f, paint)
                paint.shader = null
                // Panels differ a little, as real glazing does.
                val k = rnd.nextFloat()
                fill(c, x0, y0, x0 + 32f, y0 + 100f, if (k < 0.5f) ((k * 140).toInt() shl 24) else (((k - 0.5f) * 90).toInt() shl 24) or 0xFFFFFF)
            }
            fill(c, 0f, y0 + 100f, 256f, y0 + 124f, 0xFF26323C.toInt())
            fill(c, 0f, y0 + 124f, 256f, y0 + 128f, 0xFF1B242B.toInt())
            fill(c, 0f, y0 + 99f, 256f, y0 + 102f, 0xFFA7B1B8.toInt())
        }
        // A soft diagonal reflection across the whole wall.
        val sheen = Path().apply { moveTo(40f, 0f); lineTo(140f, 0f); lineTo(60f, 256f); lineTo(-40f, 256f); close() }
        paint.color = 0x22FFFFFF
        c.drawPath(sheen, paint)
        for (panel in 0 until 8) {
            val x = panel * 32f
            fill(c, x, 0f, x + 3f, 256f, 0xFFA7B1B8.toInt())
            fill(c, x + 3f, 0f, x + 4f, 256f, 0x55000000)
        }
        weather(b, 41, 0.01f, 128 to 0.1f, 32 to 0.03f)
        return b
    }

    /** White seafront tower: wide windows behind full-width balconies with glass rails. */
    private fun whiteTower(): Bitmap {
        val (b, c) = canvas(256, 256)
        val rnd = Random(53)
        val wall = 0xFFF3F1EB.toInt()
        fill(c, 0f, 0f, 256f, 256f, wall)
        for (floor in 0..1) for (cell in 0..1) {
            val x0 = cell * 128f; val y0 = floor * 128f
            window(c, x0 + 10f, y0 + 12f, x0 + 118f, y0 + 102f, 0xFF4A5560.toInt(), wall, rnd,
                curtains = rnd.nextFloat() < 0.4f, sill = false, panes = 2)
            fill(c, x0, y0 + 74f, x0 + 128f, y0 + 102f, 0x40A9C8D6)
            fill(c, x0, y0 + 74f, x0 + 128f, y0 + 76f, 0xFFB9C2C8.toInt())
            fill(c, x0, y0 + 102f, x0 + 128f, y0 + 114f, 0xFFFAF9F6.toInt())
            fill(c, x0, y0 + 114f, x0 + 128f, y0 + 122f, 0x50000000)
        }
        weather(b, 53, 0.02f, 64 to 0.03f)
        return b
    }

    /** The ground floor: two shops (256 × 128 px = 6.4 × 4 m), each a sign over a window or a shutter. */
    private fun shopfront(): Bitmap {
        val (b, c) = canvas(256, 128)
        val rnd = Random(67)
        val stone = 0xFFCFC5B3.toInt()
        val signs = intArrayOf(0xFF8E1F1F.toInt(), 0xFF1F5E3A.toInt(), 0xFF1E3557.toInt(), 0xFF5A1E33.toInt(), 0xFF262626.toInt(), 0xFFB07A1C.toInt())
        fill(c, 0f, 0f, 256f, 128f, stone)
        for (shop in 0..1) {
            val x0 = shop * 128f
            // The sign, with blocks of "lettering".
            val sign = signs[rnd.nextInt(signs.size)]
            fill(c, x0 + 6f, 5f, x0 + 122f, 30f, shade(sign, 1.25f))
            fill(c, x0 + 8f, 7f, x0 + 120f, 28f, sign)
            val letters = 4 + rnd.nextInt(4)
            var lx = x0 + 64f - letters * 6.5f
            repeat(letters) {
                val lw = 6f + rnd.nextFloat() * 5f
                paint.color = 0xE6F4EEDC.toInt()
                c.drawRoundRect(RectF(lx, 12f, lx + lw, 23f), 2f, 2f, paint)
                lx += lw + 3f
            }
            val l = x0 + 10f; val t = 36f; val r = x0 + 118f; val bt = 114f
            fill(c, l - 3f, t - 3f, r + 3f, bt, shade(stone, 0.7f))
            if (rnd.nextFloat() < 0.32f) {
                // Rolled-down shutter.
                fill(c, l, t, r, bt, 0xFF9A9D9F.toInt())
                var y = t + 3f
                while (y < bt) { fill(c, l, y, r, y + 1f, 0xFF7C7F81.toInt()); y += 4f }
                fill(c, l, bt - 5f, r, bt, 0xFF6E7173.toInt())
                if (rnd.nextBoolean()) {
                    paint.color = (signs[rnd.nextInt(signs.size)] and 0xFFFFFF) or 0x70000000
                    c.drawOval(RectF(l + 14f + rnd.nextFloat() * 40f, t + 30f, l + 70f + rnd.nextFloat() * 20f, t + 60f), paint)
                }
            } else {
                // Lit display window and a glazed door.
                paint.shader = LinearGradient(0f, t, 0f, bt, 0xFF3A3C3D.toInt(), 0xFF6A5A44.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(l, t, r, bt, paint)
                paint.shader = null
                for (k in 0..2) fill(c, l + 4f, t + 22f + k * 20f, x0 + 80f, t + 25f + k * 20f, 0x40FFE2B0)
                val sheen = Path().apply { moveTo(l + 20f, t); lineTo(l + 44f, t); lineTo(l + 14f, bt); lineTo(l - 10f, bt); close() }
                c.save(); c.clipRect(l, t, r, bt)
                paint.color = 0x2AFFFFFF
                c.drawPath(sheen, paint)
                c.restore()
                frame(c, l, t, r, bt, 0xFF2B2B2B.toInt(), 3f)
                frame(c, x0 + 84f, t, r, bt, 0xFF2B2B2B.toInt(), 3f)
                fill(c, x0 + 88f, t + 44f, x0 + 90f, t + 56f, 0xFFC9C9C9.toInt())
                if (rnd.nextFloat() < 0.5f) awning(c, l - 4f, t - 2f, r + 4f, sign)
            }
            fill(c, x0, 114f, x0 + 128f, 128f, 0xFF7F776B.toInt())
        }
        weather(b, 67, 0.04f, 32 to 0.04f)
        return b
    }

    // ---- Ground materials (near white, tinted by the surface colour) ---------------------------

    private fun asphalt(): Bitmap {
        val (b, c) = canvas(256, 256)
        val rnd = Random(71)
        fill(c, 0f, 0f, 256f, 256f, 0xFFEEEEEE.toInt())
        // Repaired patches and cracks, kept off the edges so the tiling doesn't show.
        repeat(3) {
            val x = 20f + rnd.nextFloat() * 150f; val y = 20f + rnd.nextFloat() * 150f
            val w = 30f + rnd.nextFloat() * 50f; val h = 20f + rnd.nextFloat() * 50f
            fill(c, x, y, x + w, y + h, 0xFFD6D6D6.toInt())
            frame(c, x, y, x + w, y + h, 0xFFC4C4C4.toInt(), 1.5f)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = 0xFFB4B4B4.toInt()
        repeat(4) {
            val path = Path()
            var x = 20f + rnd.nextFloat() * 200f; var y = 20f + rnd.nextFloat() * 200f
            path.moveTo(x, y)
            repeat(6) {
                x = (x + (rnd.nextFloat() - 0.5f) * 30f).coerceIn(8f, 248f)
                y = (y + (rnd.nextFloat() - 0.5f) * 30f).coerceIn(8f, 248f)
                path.lineTo(x, y)
            }
            c.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
        weather(b, 71, 0.16f, 128 to 0.07f, 32 to 0.05f)
        return b
    }

    /** Sidewalk: half-metre concrete slabs (64 px per metre). */
    private fun slabs(): Bitmap {
        val (b, c) = canvas(256, 256)
        val rnd = Random(73)
        for (y in 0 until 8) for (x in 0 until 8) {
            fill(c, x * 32f, y * 32f, x * 32f + 32f, y * 32f + 32f, shade(0xFFF2F2F2.toInt(), 0.9f + rnd.nextFloat() * 0.1f))
        }
        for (k in 0 until 8) {
            fill(c, k * 32f, 0f, k * 32f + 2f, 256f, 0xFFB4B4B4.toInt())
            fill(c, 0f, k * 32f, 256f, k * 32f + 2f, 0xFFB4B4B4.toInt())
        }
        weather(b, 73, 0.05f, 64 to 0.04f, 16 to 0.03f)
        return b
    }

    /** Pedestrian streets and squares: bricks laid in running bond. */
    private fun paving(): Bitmap {
        val (b, c) = canvas(256, 256)
        val rnd = Random(79)
        fill(c, 0f, 0f, 256f, 256f, 0xFFB8B8B8.toInt())
        for (row in 0 until 16) {
            val y = row * 16f
            val offset = if (row % 2 == 0) 0f else 16f
            for (k in -1 until 8) {
                val x = k * 32f + offset
                fill(c, x + 1f, y + 1f, x + 31f, y + 15f, shade(0xFFF2F2F2.toInt(), 0.84f + rnd.nextFloat() * 0.16f))
            }
        }
        weather(b, 79, 0.05f, 64 to 0.04f)
        return b
    }

    private fun grass(): Bitmap {
        val (b, c) = canvas(256, 256)
        val rnd = Random(83)
        fill(c, 0f, 0f, 256f, 256f, 0xFFE4E4E4.toInt())
        repeat(2500) {
            val x = rnd.nextFloat() * 254f; val y = rnd.nextFloat() * 252f
            fill(c, x, y, x + 1.2f, y + 3f, if (rnd.nextBoolean()) 0xFFFFFFFF.toInt() else 0xFFC6C6C6.toInt())
        }
        weather(b, 83, 0.08f, 64 to 0.12f, 16 to 0.06f)
        return b
    }

    /** Roofs: a bitumen membrane in strips, gritty. */
    private fun roof(): Bitmap {
        val (b, c) = canvas(256, 256)
        fill(c, 0f, 0f, 256f, 256f, 0xFFE6E6E6.toInt())
        for (k in 0 until 4) fill(c, 0f, k * 64f, 256f, k * 64f + 2f, 0xFFF8F8F8.toInt())
        weather(b, 89, 0.12f, 32 to 0.06f, 8 to 0.04f)
        return b
    }

    private fun plain(seed: Int, coarse: Float, fine: Float, speck: Float): Bitmap {
        val (b, c) = canvas(256, 256)
        fill(c, 0f, 0f, 256f, 256f, 0xFFE8E8E8.toInt())
        weather(b, seed, speck, 128 to coarse, 32 to fine, 8 to fine * 0.5f)
        return b
    }

    // ---- Trees ---------------------------------------------------------------------------------

    /** Bark: furrows running up the trunk (u round it, v up it). */
    private fun bark(): Bitmap {
        val (b, c) = canvas(256, 256)
        val rnd = Random(97)
        fill(c, 0f, 0f, 256f, 256f, 0xFFD2D2D2.toInt())
        repeat(90) {
            val x = rnd.nextFloat() * 256f
            val w = 1f + rnd.nextFloat() * 3f
            val dark = 0xFF000000.toInt() or ((0x60 + rnd.nextInt(0x40)) * 0x010101)
            // Wavy, and drawn twice where it crosses the edge so the bark wraps round seamlessly.
            for (shift in floatArrayOf(-256f, 0f, 256f)) {
                var y = 0f
                var px = x + shift
                while (y < 256f) {
                    val nx = px + (rnd.nextFloat() - 0.5f) * 3f
                    paint.strokeWidth = w
                    paint.color = dark
                    c.drawLine(px, y, nx, y + 8f, paint)
                    px = nx; y += 8f
                }
            }
        }
        weather(b, 97, 0.08f, 32 to 0.08f)
        return b
    }

    /** Palm trunk: the stubs of old fronds, ring above ring (v: 128 px per metre). */
    private fun palmBark(): Bitmap {
        val (b, c) = canvas(256, 256)
        val rnd = Random(101)
        fill(c, 0f, 0f, 256f, 256f, 0xFFD8D8D8.toInt())
        for (ring in 0 until 8) {
            val y = ring * 32f
            val offset = if (ring % 2 == 0) 0f else 16f
            var x = -32f + offset
            while (x < 256f) {
                // A diamond-ish leaf base: light at the top, shadowed at its lower edge.
                val path = Path().apply {
                    moveTo(x, y + 26f); lineTo(x + 16f, y + 4f); lineTo(x + 32f, y + 26f); lineTo(x + 16f, y + 32f); close()
                }
                paint.color = shade(0xFFE6E6E6.toInt(), 0.85f + rnd.nextFloat() * 0.15f)
                c.drawPath(path, paint)
                x += 32f
            }
            fill(c, 0f, y + 26f, 256f, y + 32f, 0xFF8A8A8A.toInt())
        }
        weather(b, 101, 0.07f, 32 to 0.06f)
        return b
    }

    /** A canopy of leaves: overlapping light leaves over dark gaps, wrapping at the edges. */
    private fun leaves(): Bitmap {
        val (b, c) = canvas(256, 256)
        val rnd = Random(103)
        fill(c, 0f, 0f, 256f, 256f, 0xFF5E5E5E.toInt())
        val leaf = RectF()
        repeat(2600) {
            val x = rnd.nextFloat() * 256f; val y = rnd.nextFloat() * 256f
            val len = 9f + rnd.nextFloat() * 8f; val w = 4f + rnd.nextFloat() * 4f
            val angle = rnd.nextFloat() * 360f
            paint.color = 0xFF000000.toInt() or ((0x90 + rnd.nextInt(0x70)) * 0x010101)
            for (sx in floatArrayOf(-256f, 0f, 256f)) for (sy in floatArrayOf(-256f, 0f, 256f)) {
                val px = x + sx; val py = y + sy
                if (px < -20f || px > 276f || py < -20f || py > 276f) continue
                c.save()
                c.rotate(angle, px, py)
                leaf.set(px - len / 2f, py - w / 2f, px + len / 2f, py + w / 2f)
                c.drawOval(leaf, paint)
                c.restore()
            }
        }
        weather(b, 103, 0.06f, 64 to 0.1f)
        return b
    }

    /**
     * A palm frond seen from above: the rib along the middle (u from the trunk to the tip), with
     * leaflets slanting out to both sides; transparent between them (see [Surface.cutout]).
     */
    private fun frond(): Bitmap {
        val b = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val rnd = Random(107)
        paint.strokeCap = Paint.Cap.ROUND
        var u = 10f
        while (u < 248f) {
            val k = u / 256f
            // Leaflets are longest a third of the way along, short at the base and the tip.
            val reach = (60f * kotlin.math.sin(Math.PI.toFloat() * k) * (1.15f - k * 0.4f)).coerceAtMost(60f)
            paint.strokeWidth = 4.5f - k * 1.5f
            for (side in intArrayOf(-1, 1)) {
                paint.color = 0xFF000000.toInt() or ((0xB4 + rnd.nextInt(0x4B)) * 0x010101)
                val tipU = (u + reach * 0.55f).coerceAtMost(254f)
                c.drawLine(u, 64f, tipU, 64f + side * reach, paint)
            }
            u += 6.5f
        }
        // The rib, thick at the base.
        val rib = Path().apply { moveTo(0f, 60f); lineTo(256f, 63f); lineTo(256f, 65f); lineTo(0f, 68f); close() }
        paint.color = 0xFFE8E8D8.toInt()
        c.drawPath(rib, paint)
        paint.strokeCap = Paint.Cap.BUTT
        return b
    }

    /** Solid squares of [colors], 8 × 8 of them, 8 px each: models pick a colour by pointing at one. */
    fun palette(colors: IntArray): Bitmap {
        val (b, c) = canvas(64, 64)
        colors.forEachIndexed { i, color -> fill(c, (i % 8) * 8f, (i / 8) * 8f, (i % 8) * 8f + 8f, (i / 8) * 8f + 8f, color) }
        return b
    }

    // ---- Facade parts --------------------------------------------------------------------------

    /**
     * A window set into the wall: a shadowed reveal, glass with the sky in it (or curtains, or
     * closed [shutters]), the frame and a sill with a rain stain under it.
     */
    private fun window(
        c: Canvas, l: Float, t: Float, r: Float, b: Float, frameColor: Int, wall: Int, rnd: Random,
        arched: Boolean = false, curtains: Boolean, shutters: Boolean = false, sill: Boolean = true, panes: Int = 2,
    ) {
        val w = r - l
        fun shape(inset: Float) = Path().apply {
            val rr = if (arched) (w / 2f - inset) else 0f
            addRoundRect(RectF(l - inset, t - inset, r + inset, b), floatArrayOf(rr, rr, rr, rr, 0f, 0f, 0f, 0f), Path.Direction.CW)
        }
        paint.color = shade(wall, 0.68f)
        c.drawPath(shape(-3f), paint)
        val glass = shape(0f)
        c.save()
        c.clipPath(glass)
        val dark = rnd.nextFloat() < 0.3f
        paint.shader = LinearGradient(0f, t, 0f, b,
            if (dark) 0xFF4A5B68.toInt() else 0xFF9DB6C7.toInt(),
            if (dark) 0xFF1C252C.toInt() else 0xFF34495A.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(l, t, r, b, paint)
        paint.shader = null
        if (curtains) {
            fill(c, l, t, l + w * 0.32f, b, 0xC8E6D9C0.toInt())
            fill(c, r - w * 0.32f, t, r, b, 0xC8E6D9C0.toInt())
        }
        paint.color = 0x2EFFFFFF
        c.drawPath(Path().apply {
            moveTo(l + w * 0.25f, t); lineTo(l + w * 0.6f, t); lineTo(l + w * 0.2f, b); lineTo(l - w * 0.15f, b); close()
        }, paint)
        if (shutters) {
            shutter(c, l, t, l + w / 2f, b)
            shutter(c, l + w / 2f, t, r, b)
        }
        // The wall above shades the top of the opening.
        fill(c, l, t, r, t + 6f, 0x50000000)
        c.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = frameColor
        c.drawPath(glass, paint)
        paint.style = Paint.Style.FILL
        if (!shutters) {
            for (k in 1 until panes) fill(c, l + w * k / panes - 1.5f, t, l + w * k / panes + 1.5f, b, frameColor)
            val transom = t + (b - t) * if (arched) 0.36f else 0.26f
            fill(c, l, transom - 1f, r, transom + 1f, frameColor)
        }
        if (sill) {
            fill(c, l - 6f, b, r + 6f, b + 5f, shade(wall, 1.1f))
            fill(c, l - 6f, b + 5f, r + 6f, b + 8f, 0x55000000)
            streak(c, l, b + 8f, w, 24f, 0x28000000)
        }
    }

    /** A louvred wooden shutter. */
    private fun shutter(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        fill(c, l, t, r, b, 0xFF5E7F4F.toInt())
        var y = t + 2f
        while (y < b - 2f) { fill(c, l + 2f, y, r - 2f, y + 1.5f, 0xFF45603A.toInt()); y += 5f }
        frame(c, l, t, r, b, 0xFF3E5634.toInt(), 1.5f)
    }

    /** A string course: a projecting stone band with its shadow under it. */
    private fun ledge(c: Canvas, l: Float, y: Float, r: Float, wall: Int) {
        fill(c, l, y, r, y + 6f, shade(wall, 1.09f))
        fill(c, l, y + 6f, r, y + 10f, shade(wall, 0.72f))
    }

    /** Metal balcony rail: top and bottom bars with thin balusters between. */
    private fun rail(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        fill(c, l, t, r, t + 3f, color)
        var x = l
        while (x < r) { fill(c, x, t, x + 1.5f, b, color); x += 6f }
    }

    private fun acUnit(c: Canvas, x: Float, y: Float) {
        fill(c, x, y + 18f, x + 24f, y + 22f, 0x50000000)
        fill(c, x, y, x + 24f, y + 18f, 0xFFE4E4E0.toInt())
        frame(c, x, y, x + 24f, y + 18f, 0xFFB5B5AF.toInt(), 1f)
        paint.color = 0xFF9A9A94.toInt()
        c.drawCircle(x + 15f, y + 9f, 6f, paint)
        paint.color = 0xFFCFCFCA.toInt()
        c.drawCircle(x + 15f, y + 9f, 2f, paint)
    }

    /** A striped canvas awning over a shop window, with its shadow. */
    private fun awning(c: Canvas, l: Float, t: Float, r: Float, color: Int) {
        val h = 16f
        fill(c, l + 4f, t + h, r - 4f, t + h + 8f, 0x55000000)
        var x = l
        var k = 0
        while (x < r) {
            fill(c, x, t, minOf(x + 10f, r), t + h, if (k % 2 == 0) color else 0xFFF1ECE0.toInt())
            x += 10f; k++
        }
        fill(c, l, t + h - 3f, r, t + h, shade(color, 0.7f))
    }

    /** A dirty streak fading downwards from (x, y). */
    private fun streak(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        paint.shader = LinearGradient(0f, y, 0f, y + h, color, color and 0xFFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(x, y, x + w, y + h, paint)
        paint.shader = null
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private fun canvas(w: Int, h: Int): Pair<Bitmap, Canvas> {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return b to Canvas(b)
    }

    private fun fill(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        paint.color = color
        c.drawRect(l, t, r, b, paint)
    }

    private fun frame(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
        c.drawRect(l, t, r, b, paint)
        paint.style = Paint.Style.FILL
    }

    /** [color] with its red, green and blue scaled by [k]. */
    private fun shade(color: Int, k: Float): Int {
        fun ch(shift: Int) = ((((color shr shift) and 0xFF) * k).toInt().coerceIn(0, 255)) shl shift
        return (color and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
    }

    /**
     * Ages [b]: multiplies every pixel by one plus some wrapping noise, each layer given as
     * (lattice spacing in pixels, strength), plus per-pixel [speck]le.
     */
    private fun weather(b: Bitmap, seed: Int, speck: Float, vararg layers: Pair<Int, Float>) {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val sum = FloatArray(w * h)
        layers.forEachIndexed { i, (step, strength) ->
            val n = noise(w, h, step, seed * 13 + i)
            for (k in sum.indices) sum[k] += (n[k] - 0.5f) * 2f * strength
        }
        val rnd = Random(seed * 31 + 7)
        for (k in px.indices) px[k] = shade(px[k], 1f + sum[k] + (rnd.nextFloat() - 0.5f) * 2f * speck)
        b.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** Smooth value noise in 0..1 over a w × h image that wraps at its edges; [step] pixels per lattice cell. */
    private fun noise(w: Int, h: Int, step: Int, seed: Int): FloatArray {
        val cx = (w / step).coerceAtLeast(1); val cy = (h / step).coerceAtLeast(1)
        val rnd = Random(seed)
        val lattice = FloatArray(cx * cy) { rnd.nextFloat() }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val gy = y / step
            val ty = fade((y % step) / step.toFloat())
            val y0 = gy % cy; val y1 = (gy + 1) % cy
            for (x in 0 until w) {
                val gx = x / step
                val tx = fade((x % step) / step.toFloat())
                val x0 = gx % cx; val x1 = (gx + 1) % cx
                val top = lattice[y0 * cx + x0] + (lattice[y0 * cx + x1] - lattice[y0 * cx + x0]) * tx
                val bottom = lattice[y1 * cx + x0] + (lattice[y1 * cx + x1] - lattice[y1 * cx + x0]) * tx
                out[y * w + x] = top + (bottom - top) * ty
            }
        }
        return out
    }

    private fun fade(t: Float) = t * t * (3f - 2f * t)
}
