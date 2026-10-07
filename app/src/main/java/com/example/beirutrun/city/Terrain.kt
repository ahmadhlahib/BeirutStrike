package com.example.beirutrun.city

import kotlin.math.floor

/**
 * The ground's height on maps with hills (see tools/OsmToCity's Terrain): a grid of heights in
 * metres, [cell] metres apart, starting at ([x0], [z0]) and running east ([cols]) and south
 * ([rows]); 0 is the start point's ground. Between grid points the ground is bilinear. Maps
 * without hills use [FLAT], where the ground is 0 everywhere.
 */
class Terrain(
    val x0: Float, val z0: Float, val cell: Float,
    val cols: Int, val rows: Int,
    private val heights: FloatArray,
) {
    val flat get() = cols == 0

    /** The lowest and highest ground on the map. */
    val minHeight = if (flat) 0f else heights.min()
    val maxHeight = if (flat) 0f else heights.max()

    /**
     * The ground's height at (x, z); beyond the grid's edge, the edge's. Exactly the drawn ground:
     * each grid square is two flat triangles split from its north-east to its south-west corner
     * (as CityScene draws it), so people and things stand on the hillside you see.
     */
    fun heightAt(x: Float, z: Float): Float {
        if (flat) return 0f
        val fc = ((x - x0) / cell).coerceIn(0f, cols - 1.001f)
        val fr = ((z - z0) / cell).coerceIn(0f, rows - 1.001f)
        val c = floor(fc).toInt()
        val r = floor(fr).toInt()
        val tx = fc - c
        val tz = fr - r
        val i = r * cols + c
        val h00 = heights[i]; val h10 = heights[i + 1]; val h01 = heights[i + cols]; val h11 = heights[i + cols + 1]
        return if (tx + tz <= 1f) h00 + (h10 - h00) * tx + (h01 - h00) * tz
        else h11 + (h01 - h11) * (1f - tx) + (h10 - h11) * (1f - tz)
    }

    /** The grid point's height (column [c], row [r]), for building the ground's mesh. */
    fun at(c: Int, r: Int): Float = if (flat) 0f else heights[r.coerceIn(0, rows - 1) * cols + c.coerceIn(0, cols - 1)]

    /** The lowest ground under a footprint ring (x, z pairs), checked at its corners, its edges' midpoints and centre. */
    fun lowestUnder(pts: FloatArray): Float {
        if (flat) return 0f
        var low = Float.MAX_VALUE
        var cx = 0f
        var cz = 0f
        val n = pts.size / 2
        for (i in 0 until n) {
            val x = pts[2 * i]
            val z = pts[2 * i + 1]
            val j = (i + 1) % n
            low = minOf(low, heightAt(x, z), heightAt((x + pts[2 * j]) / 2f, (z + pts[2 * j + 1]) / 2f))
            cx += x
            cz += z
        }
        return minOf(low, heightAt(cx / n, cz / n))
    }

    /**
     * Whether the ground rises above the straight line from (x0, y0, z0) to (x1, y1, z1): a hill
     * between them blocks sight and bullets. Checked every [step] metres.
     */
    fun blocks(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, step: Float = 2f): Boolean {
        if (flat) return false
        val dx = bx - ax
        val dz = bz - az
        val len = kotlin.math.sqrt(dx * dx + dz * dz)
        val n = (len / step).toInt()
        for (k in 1 until n) {
            val t = k / n.toFloat()
            if (heightAt(ax + dx * t, az + dz * t) > ay + (by - ay) * t) return true
        }
        return false
    }

    companion object {
        val FLAT = Terrain(0f, 0f, 1f, 0, 0, FloatArray(0))
    }
}
