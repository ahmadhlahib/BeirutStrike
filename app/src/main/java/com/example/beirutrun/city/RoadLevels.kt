package com.example.beirutrun.city

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

/**
 * How roads lie on a map with hills: each road along the ground's line under its middle (with a
 * point wherever the ground bends, see [onGround]), and level from side to side at its middle's
 * height ([RoadProfile]), as real roads are, whatever the hillside does across it. The scene draws
 * roads this way (CityScene) and the game stands people and things on them the same way
 * ([CityMap.groundAt]), so nobody sinks into a road or floats above it.
 */
class RoadLevels(private val roads: List<CityMap.Road>, private val terrain: Terrain) {

    private val laidPoints = HashMap<CityMap.Road, FloatArray>()
    private val profiles = HashMap<CityMap.Road, RoadProfile>()

    /** [road]'s middle with points added where the ground bends. */
    fun laid(road: CityMap.Road): FloatArray = synchronized(laidPoints) { laidPoints.getOrPut(road) { onGround(road.pts, terrain) } }

    /** [road]'s height along its middle. */
    fun profile(road: CityMap.Road): RoadProfile = synchronized(profiles) { profiles.getOrPut(road) { RoadProfile(laid(road), terrain) } }

    /** How far each side of a road's middle it's laid level: the road, its sidewalks, a little past. */
    fun reach(road: CityMap.Road) = road.width / 2f + CityScene.sidewalkWidth(road.kind) + 0.5f

    /** Which roads pass near each [CELL]-metre square (by index), for [heightAt]. */
    private val cells: HashMap<Long, MutableList<Int>> by lazy {
        val map = HashMap<Long, MutableList<Int>>()
        roads.forEachIndexed { index, road ->
            if (road.kind == CityMap.ROAD_PIER) return@forEachIndexed
            val r = reach(road)
            val p = road.pts
            for (i in 0 until p.size / 2 - 1) {
                val x0 = floor((minOf(p[2 * i], p[2 * i + 2]) - r) / CELL).toInt()
                val x1 = floor((maxOf(p[2 * i], p[2 * i + 2]) + r) / CELL).toInt()
                val z0 = floor((minOf(p[2 * i + 1], p[2 * i + 3]) - r) / CELL).toInt()
                val z1 = floor((maxOf(p[2 * i + 1], p[2 * i + 3]) + r) / CELL).toInt()
                for (cx in x0..x1) for (cz in z0..z1) {
                    val list = map.getOrPut(key(cx, cz)) { ArrayList(4) }
                    if (list.lastOrNull() != index) list += index
                }
            }
        }
        map
    }

    /**
     * The height of the road surface at (x, z), or null when that's not on a road (or its
     * sidewalks). Where roads overlap (a junction), the one whose middle is nearest.
     */
    fun heightAt(x: Float, z: Float): Float? {
        val list = cells[key(floor(x / CELL).toInt(), floor(z / CELL).toInt())] ?: return null
        var best = Float.MAX_VALUE
        var road: CityMap.Road? = null
        for (index in list) {
            val r = roads[index]
            val p = r.pts
            val reach = reach(r) - 0.5f
            for (i in 0 until p.size / 2 - 1) {
                val d = CityMap.segmentDistance(x, z, p[2 * i], p[2 * i + 1], p[2 * i + 2], p[2 * i + 3])
                if (d <= reach && d < best) { best = d; road = r }
            }
        }
        return road?.let { profile(it).at(x, z) }
    }

    private fun key(cx: Int, cz: Int) = (cx.toLong() shl 32) xor (cz.toLong() and 0xffffffffL)

    companion object {
        /** Squares for finding the roads near a point, metres. */
        private const val CELL = 16f
        /**
         * Laying roads and squares over hills: how far the ground may bend away from a straight
         * line before a point is added (metres), how often it's checked, and the shortest piece
         * worth splitting.
         */
        const val GROUND_TOLERANCE = 0.08f
        private const val BEND_CHECK = 1f
        const val MIN_GROUND_STEP = 2f

        /**
         * A line ([p], x/z pairs) with points added where the ground under it bends away from a
         * straight line (by more than [GROUND_TOLERANCE]), so a road laid along it follows the hill.
         */
        fun onGround(p: FloatArray, terrain: Terrain): FloatArray {
            val out = ArrayList<Float>(p.size * 2)
            fun split(ax: Float, az: Float, bx: Float, bz: Float, depth: Int) {
                val mx = (ax + bx) / 2f
                val mz = (az + bz) / 2f
                if (depth < 10 && hypot(bx - ax, bz - az) > MIN_GROUND_STEP && bends(ax, az, bx, bz, terrain)) {
                    split(ax, az, mx, mz, depth + 1)
                    split(mx, mz, bx, bz, depth + 1)
                } else {
                    out += bx; out += bz
                }
            }
            out += p[0]; out += p[1]
            for (i in 1 until p.size / 2) split(p[2 * i - 2], p[2 * i - 1], p[2 * i], p[2 * i + 1], 0)
            return out.toFloatArray()
        }

        /** Whether the ground between two points isn't a straight slope (checked every metre, so no bump is missed). */
        fun bends(ax: Float, az: Float, bx: Float, bz: Float, terrain: Terrain): Boolean {
            val ha = terrain.heightAt(ax, az)
            val hb = terrain.heightAt(bx, bz)
            val n = (hypot(bx - ax, bz - az) / BEND_CHECK).toInt().coerceAtLeast(2)
            for (k in 1 until n) {
                val f = k / n.toFloat()
                if (abs(terrain.heightAt(ax + (bx - ax) * f, az + (bz - az) * f) - (ha + (hb - ha) * f)) > GROUND_TOLERANCE) return true
            }
            return false
        }
    }
}

/**
 * A road's height along its middle, from the ground there; anywhere beside it takes the height
 * of the nearest point of its middle, so the road (its sidewalks, curbs and paint too) lies level
 * from side to side.
 */
class RoadProfile(private val pts: FloatArray, terrain: Terrain) {
    private val heights = FloatArray(pts.size / 2) { terrain.heightAt(pts[2 * it], pts[2 * it + 1]) }
    /** Which segments pass near each [CELL]-metre square, so the nearest is found quickly. */
    private val cells = HashMap<Long, MutableList<Int>>()

    init {
        for (i in 0 until pts.size / 2 - 1) {
            val x0 = floor(minOf(pts[2 * i], pts[2 * i + 2]) / CELL).toInt()
            val x1 = floor(maxOf(pts[2 * i], pts[2 * i + 2]) / CELL).toInt()
            val z0 = floor(minOf(pts[2 * i + 1], pts[2 * i + 3]) / CELL).toInt()
            val z1 = floor(maxOf(pts[2 * i + 1], pts[2 * i + 3]) / CELL).toInt()
            for (cx in x0..x1) for (cz in z0..z1) cells.getOrPut(key(cx, cz)) { ArrayList(2) } += i
        }
    }

    private fun key(cx: Int, cz: Int) = (cx.toLong() shl 32) xor (cz.toLong() and 0xffffffffL)

    /** The middle's height at the point of it nearest (x, z). */
    fun at(x: Float, z: Float): Float {
        var best = Float.MAX_VALUE
        var height = heights[0]
        val cx = floor(x / CELL).toInt()
        val cz = floor(z / CELL).toInt()
        for (dx in -1..1) for (dz in -1..1) {
            val list = cells[key(cx + dx, cz + dz)] ?: continue
            for (i in list) {
                val ax = pts[2 * i]; val az = pts[2 * i + 1]; val bx = pts[2 * i + 2]; val bz = pts[2 * i + 3]
                val vx = bx - ax; val vz = bz - az
                val len2 = vx * vx + vz * vz
                val t = if (len2 < 1e-6f) 0f else (((x - ax) * vx + (z - az) * vz) / len2).coerceIn(0f, 1f)
                val px = ax + vx * t - x; val pz = az + vz * t - z
                val d = px * px + pz * pz
                if (d < best) { best = d; height = heights[i] + (heights[i + 1] - heights[i]) * t }
            }
        }
        return height
    }

    private companion object {
        /** Bigger than any road's half-width with its sidewalk, so the nearest segment is always in reach. */
        const val CELL = 12f
    }
}
