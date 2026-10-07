package com.example.beirutrun.city

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

/**
 * How roads and the ground fit together on a map with hills, worked out once and used by
 * everything, so what's drawn and where things stand always agree:
 *
 * - Each road follows the ground's line under its middle (a point wherever the ground bends, see
 *   [onGround]), smoothed along its length so it never jumps over a short distance, and lies level
 *   from side to side at its middle's height ([RoadProfile]), as real roads do. Junctions keep the
 *   ground's height there, so roads meeting there meet exactly.
 * - The ground ([drawnGround]) is shaped round the roads: just under each road, and beside it a
 *   bank sloping away up into a cutting or down from an embankment, never rising over the road's
 *   edge.
 *
 * The scene draws the roads and [drawnGround] (CityScene), and the game stands people, cars and
 * the camera on them the same way ([CityMap.groundAt]).
 */
class RoadLevels(
    private val roads: List<CityMap.Road>,
    private val terrain: Terrain,
    /** By the sea, heights are from sea level and 0 or below is the sea (left as it is); inland, 0 is just the start's height. */
    private val coastal: Boolean = false,
) {

    private val laidPoints = HashMap<CityMap.Road, FloatArray>()
    private val profiles = HashMap<CityMap.Road, RoadProfile>()

    /** Where roads join (points shared by two or more roads): they keep the ground's height there. */
    private val junctions: Set<Long> by lazy {
        val count = HashMap<Long, Int>()
        for (r in roads) {
            if (r.kind == CityMap.ROAD_PIER) continue
            val seen = HashSet<Long>()
            for (i in 0 until r.pts.size / 2) {
                val k = pointKey(r.pts[2 * i], r.pts[2 * i + 1])
                if (seen.add(k)) count[k] = (count[k] ?: 0) + 1
            }
        }
        count.filterValues { it >= 2 }.keys
    }

    /** [road]'s middle with points added where the ground bends. */
    fun laid(road: CityMap.Road): FloatArray = synchronized(laidPoints) { laidPoints.getOrPut(road) { onGround(road.pts, terrain) } }

    /** [road]'s height along its middle. */
    fun profile(road: CityMap.Road): RoadProfile = synchronized(profiles) {
        profiles.getOrPut(road) { RoadProfile(laid(road), terrain) { x, z -> junctionHeights[pointKey(x, z)] } }
    }

    /**
     * Each junction's height: the average of the (smoothed) heights of the roads meeting there,
     * so they all meet at one height and none has to climb a step to get there.
     */
    private val junctionHeights: Map<Long, Float> by lazy {
        val sums = HashMap<Long, Float>()
        val counts = HashMap<Long, Int>()
        for (r in roads) {
            if (r.kind == CityMap.ROAD_PIER) continue
            val smooth = RoadProfile(laid(r), terrain)
            for (i in 0 until r.pts.size / 2) {
                val x = r.pts[2 * i]; val z = r.pts[2 * i + 1]
                val k = pointKey(x, z)
                if (k !in junctions) continue
                sums[k] = (sums[k] ?: 0f) + smooth.at(x, z)
                counts[k] = (counts[k] ?: 0) + 1
            }
        }
        sums.mapValues { (k, s) -> s / counts.getValue(k) }
    }

    /** How far each side of a road's middle it's laid level: the road, its sidewalks, a little past. */
    fun reach(road: CityMap.Road) = road.width / 2f + CityScene.sidewalkWidth(road.kind) + 0.5f

    /** Which roads pass near each [CELL]-metre square (by index), with their banks. */
    private val cells: HashMap<Long, MutableList<Int>> by lazy {
        val map = HashMap<Long, MutableList<Int>>()
        roads.forEachIndexed { index, road ->
            if (road.kind == CityMap.ROAD_PIER) return@forEachIndexed
            val r = reach(road) + BANK_WIDTH
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

    /** The road nearest (x, z) within [extra] metres past its reach, and how far its middle is; null if none. */
    private fun nearestRoad(x: Float, z: Float, extra: Float): Pair<CityMap.Road, Float>? {
        val list = cells[key(floor(x / CELL).toInt(), floor(z / CELL).toInt())] ?: return null
        var best: CityMap.Road? = null
        var bestD = Float.MAX_VALUE
        var bestOver = Float.MAX_VALUE
        for (index in list) {
            val r = roads[index]
            val p = r.pts
            val reach = reach(r)
            var d = Float.MAX_VALUE
            for (i in 0 until p.size / 2 - 1) d = minOf(d, CityMap.segmentDistance(x, z, p[2 * i], p[2 * i + 1], p[2 * i + 2], p[2 * i + 3]))
            // The one this point is most inside of (or nearest outside of).
            val over = d - reach
            if (over <= extra && over < bestOver) { bestOver = over; bestD = d; best = r }
        }
        return best?.let { it to bestD }
    }

    /**
     * The height of the road surface at (x, z), or null when that's not on a road (or its
     * sidewalks). Where roads overlap, the highest: that's the surface that's seen, and stood on.
     */
    fun heightAt(x: Float, z: Float): Float? {
        val list = cells[key(floor(x / CELL).toInt(), floor(z / CELL).toInt())] ?: return null
        var top: Float? = null
        for (index in list) {
            val r = roads[index]
            val p = r.pts
            val reach = reach(r) - 0.5f
            for (i in 0 until p.size / 2 - 1) {
                if (CityMap.segmentDistance(x, z, p[2 * i], p[2 * i + 1], p[2 * i + 2], p[2 * i + 3]) > reach) continue
                val h = profile(r).at(x, z)
                if (top == null || h > top) top = h
                break
            }
        }
        return top
    }

    /**
     * The ground as it's drawn and stood on: the hillside, but under each road a little below its
     * surface ([ROAD_SINK], out of sight), and beside it a bank from the road's edge back to the
     * hillside over [BANK_WIDTH] metres, so the hill never rises over a road and a road never
     * floats over a drop. The sea (height 0 or below by the coast) is left as it is.
     */
    val drawnGround: Terrain by lazy {
        val t = terrain
        val h = FloatArray(t.cols * t.rows) { t.at(it % t.cols, it / t.cols) }
        for (r in 0 until t.rows) for (c in 0 until t.cols) {
            val x = t.x0 + c * t.cell
            val z = t.z0 + r * t.cell
            val (road, d) = nearestRoad(x, z, BANK_WIDTH) ?: continue
            val i = r * t.cols + c
            val level = profile(road).at(x, z) - ROAD_SINK
            val reach = reach(road)
            if (d <= reach) {
                // Under the road: just below it, cut or filled.
                h[i] = if (coastal && h[i] <= 0f && level <= 0.5f) h[i] else level
            } else {
                // The bank: from the road's edge towards the hillside, at most [BANK_SLOPE].
                val k = (d - reach) * BANK_SLOPE
                val lo = level - k; val hi = level + k
                if (h[i] > hi) h[i] = hi
                else if (h[i] < lo && (!coastal || h[i] > 0f)) h[i] = lo
            }
        }
        Terrain(t.x0, t.z0, t.cell, t.cols, t.rows, h)
    }

    private fun key(cx: Int, cz: Int) = (cx.toLong() shl 32) xor (cz.toLong() and 0xffffffffL)

    companion object {
        /** Squares for finding the roads near a point, metres. */
        private const val CELL = 16f
        /** How far under a road its ground is drawn (out of sight), metres. */
        const val ROAD_SINK = 0.3f
        /** A road's bank: how far it reaches past the road's edge, and how steeply it rises or falls. */
        const val BANK_WIDTH = 6f
        const val BANK_SLOPE = 0.7f
        /**
         * Laying roads and squares over hills: how far the ground may bend away from a straight
         * line before a point is added (metres), how often it's checked, and the shortest piece
         * worth splitting.
         */
        const val GROUND_TOLERANCE = 0.1f
        private const val BEND_CHECK = 1f
        const val MIN_GROUND_STEP = 2f

        /** A point to the nearest 0.5 m, to match the same point on two roads. */
        fun pointKey(x: Float, z: Float): Long = (Math.round(x * 2f).toLong() shl 32) xor (Math.round(z * 2f).toLong() and 0xffffffffL)

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
 * A road's height along its middle: the ground there, smoothed over [SMOOTH] metres either way so
 * it climbs and dips gently, never jumping over a short distance; points where it meets another
 * road ([anchored]) keep the ground's height, so the roads meet exactly. Anywhere beside the road
 * takes the height of the nearest point of its middle, so it lies level from side to side.
 */
class RoadProfile(private val pts: FloatArray, terrain: Terrain, junction: (Float, Float) -> Float? = { _, _ -> null }) {
    private val heights: FloatArray
    /** Which segments pass near each [CELL]-metre square, so the nearest is found quickly. */
    private val cells = HashMap<Long, MutableList<Int>>()

    init {
        val n = pts.size / 2
        val raw = FloatArray(n) { terrain.heightAt(pts[2 * it], pts[2 * it + 1]) }
        // The height each junction on the road must have (shared by every road meeting there), or NaN.
        val target = FloatArray(n) { junction(pts[2 * it], pts[2 * it + 1]) ?: Float.NaN }
        // Distance along the road to each point.
        val dist = FloatArray(n)
        for (i in 1 until n) dist[i] = dist[i - 1] + hypot(pts[2 * i] - pts[2 * i - 2], pts[2 * i + 1] - pts[2 * i - 1])
        var h = raw
        repeat(PASSES) {
            val next = FloatArray(n)
            var lo = 0
            var hi = 0
            for (i in 0 until n) {
                while (dist[i] - dist[lo] > SMOOTH) lo++
                while (hi + 1 < n && dist[hi + 1] - dist[i] <= SMOOTH) hi++
                var sum = 0f
                for (k in lo..hi) sum += h[k]
                next[i] = sum / (hi - lo + 1)
            }
            h = next
        }
        // Then eased to each junction's shared height, over [JOIN] metres either side
        // (so every road meeting there meets at the same height, without a step).
        val joins = (0 until n).filter { !target[it].isNaN() }.map { dist[it] to target[it] - h[it] }
        if (joins.isNotEmpty()) {
            for (i in 0 until n) {
                var sum = 0f
                var weights = 0f
                for ((d, delta) in joins) {
                    val w = (1f - abs(dist[i] - d) / JOIN).coerceAtLeast(0f)
                    sum += delta * w; weights += w
                }
                if (weights > 0f) h[i] += sum / maxOf(1f, weights)
            }
        }
        // Never steeper than [MAX_GRADE] from one point to the next: forwards, then backwards
        // (junctions keep their shared height).
        for (pass in 0 until 2) {
            val range = if (pass == 0) 1 until n else (n - 2 downTo 0)
            for (i in range) {
                if (!target[i].isNaN()) continue
                val j = if (pass == 0) i - 1 else i + 1
                val step = abs(dist[i] - dist[j]) * MAX_GRADE
                h[i] = h[i].coerceIn(h[j] - step, h[j] + step)
            }
        }
        heights = h
        for (i in 0 until n - 1) {
            val x0 = floor(minOf(pts[2 * i], pts[2 * i + 2]) / CELL).toInt()
            val x1 = floor(maxOf(pts[2 * i], pts[2 * i + 2]) / CELL).toInt()
            val z0 = floor(minOf(pts[2 * i + 1], pts[2 * i + 3]) / CELL).toInt()
            val z1 = floor(maxOf(pts[2 * i + 1], pts[2 * i + 3]) / CELL).toInt()
            for (cx in x0..x1) for (cz in z0..z1) cells.getOrPut(key(cx, cz)) { ArrayList(2) } += i
        }
    }

    private fun key(cx: Int, cz: Int) = (cx.toLong() shl 32) xor (cz.toLong() and 0xffffffffL)

    /** The height of segment [i] of the middle (from point i to i + 1) where (x, z) is level with it. */
    fun atSegment(x: Float, z: Float, i: Int): Float {
        val k = i.coerceIn(0, heights.size - 2)
        val ax = pts[2 * k]; val az = pts[2 * k + 1]; val bx = pts[2 * k + 2]; val bz = pts[2 * k + 3]
        val vx = bx - ax; val vz = bz - az
        val len2 = vx * vx + vz * vz
        val t = if (len2 < 1e-6f) 0f else (((x - ax) * vx + (z - az) * vz) / len2).coerceIn(0f, 1f)
        return heights[k] + (heights[k + 1] - heights[k]) * t
    }

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
        /** Bigger than any road's half-width with its sidewalk and bank, so the nearest segment is always in reach. */
        const val CELL = 24f
        /** How far either way a road's height is evened out, metres, and how many times. */
        const val SMOOTH = 12f
        const val PASSES = 3
        /** How far a road eases to the ground's height at a junction, metres. */
        const val JOIN = 20f
        /** The steepest a road climbs from one point to the next (rise over run). */
        const val MAX_GRADE = 0.3f
    }
}
