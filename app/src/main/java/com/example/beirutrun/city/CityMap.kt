package com.example.beirutrun.city

import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * The real city: Downtown Beirut from OpenStreetMap, converted by tools/OsmToCity.java into
 * assets/maps/downtown.bin. Holds building footprints with heights, roads, areas (parks,
 * squares, parking, water), the sea and trees, plus fast collision queries.
 *
 * World units are metres; x runs east, z runs south (north is -z), y is up.
 * Map data © OpenStreetMap contributors (ODbL).
 */
class CityMap(
    val minX: Float, val minZ: Float, val maxX: Float, val maxZ: Float,
    val spawnX: Float, val spawnZ: Float,
    val buildings: List<Building>,
    val roads: List<Road>,
    val areas: List<Area>,
    val sea: List<FloatArray>,
    val trees: List<Tree>,
    val attribution: String,
    /** The ground's height (hills); [Terrain.FLAT] on flat maps. */
    val terrain: Terrain = Terrain.FLAT,
) {
    /** A footprint ring (x, z pairs, counter-clockwise as drawn north-up) extruded to [height]. */
    class Building(val pts: FloatArray, val height: Float, val minHeight: Float, val kind: Int) {
        val minX = pts.xs().min()
        val maxX = pts.xs().max()
        val minZ = pts.zs().min()
        val maxZ = pts.zs().max()
        val centerX get() = (minX + maxX) / 2f
        val centerZ get() = (minZ + maxZ) / 2f
        val area = abs(signedArea(pts))

        /** Only the ground floor blocks walking; raised parts (bridges, overhangs) don't. */
        val blocksWalking get() = minHeight < 2.5f

        /**
         * Where the building stands: the lowest ground under it (0 on flat maps). On a hillside its
         * walls start there, so the uphill side is partly sunk into the slope, as village houses are.
         * [height] and [minHeight] are measured from here.
         */
        var base = 0f
            internal set

        /** The roof's height in the world. */
        val top get() = base + height

        /** The bottom of its solid part in the world. */
        val bottom get() = base + minHeight
    }

    class Road(val kind: Int, val width: Float, val pts: FloatArray)
    class Area(val kind: Int, val pts: FloatArray)
    class Tree(val kind: Int, val x: Float, val z: Float, val size: Float)

    // ---- Play area -----------------------------------------------------------------------------

    /** The square players may walk in (the room's map size); the whole map unless [limitTo] is called. */
    var playMinX = minX; private set
    var playMinZ = minZ; private set
    var playMaxX = maxX; private set
    var playMaxZ = maxZ; private set

    /** True when the play area is smaller than the map. */
    val limited get() = playMinX > minX || playMinZ > minZ || playMaxX < maxX || playMaxZ < maxZ

    /**
     * Keeps players in a [size] x [size] metre square around the start point (shifted so it stays
     * on the map). The rest of the city is still drawn around it.
     */
    fun limitTo(size: Float) {
        val w = min(size, maxX - minX)
        val h = min(size, maxZ - minZ)
        playMinX = (spawnX - w / 2f).coerceIn(minX, maxX - w)
        playMinZ = (spawnZ - h / 2f).coerceIn(minZ, maxZ - h)
        playMaxX = playMinX + w
        playMaxZ = playMinZ + h
    }

    fun inPlayArea(x: Float, z: Float) = x in playMinX..playMaxX && z in playMinZ..playMaxZ

    // ---- Spatial index -------------------------------------------------------------------------

    private val cols = ((maxX - minX) / CELL).toInt() + 1
    private val rows = ((maxZ - minZ) / CELL).toInt() + 1
    private val buildingCells = Array(cols * rows) { ArrayList<Building>(4) }
    private val treeCells = Array(cols * rows) { ArrayList<Tree>(4) }
    private val waterCells = Array(cols * rows) { ArrayList<FloatArray>(1) }

    init {
        if (!terrain.flat) for (b in buildings) b.base = terrain.lowestUnder(b.pts)
        for (b in buildings) forCells(b.minX, b.minZ, b.maxX, b.maxZ) { buildingCells[it] += b }
        for (t in trees) forCells(t.x, t.z, t.x, t.z) { treeCells[it] += t }
        val water = sea + areas.filter { it.kind == AREA_WATER }.map { it.pts }
        for (w in water) forCells(w.xs().min(), w.zs().min(), w.xs().max(), w.zs().max()) { waterCells[it] += w }
    }

    private inline fun forCells(x0: Float, z0: Float, x1: Float, z1: Float, action: (Int) -> Unit) {
        val c0 = col(x0); val c1 = col(x1); val r0 = row(z0); val r1 = row(z1)
        for (r in r0..r1) for (c in c0..c1) action(r * cols + c)
    }

    private fun col(x: Float) = floor((x - minX) / CELL).toInt().coerceIn(0, cols - 1)
    private fun row(z: Float) = floor((z - minZ) / CELL).toInt().coerceIn(0, rows - 1)

    // ---- Queries -------------------------------------------------------------------------------

    /** How roads lie on the hills (null on flat maps): level across, as drawn (see [RoadLevels]). */
    val roadLevels: RoadLevels? by lazy { if (terrain.flat) null else RoadLevels(roads, terrain) }

    /**
     * The ground's height at (x, z): 0 on flat maps; on maps with hills the hillside, or on a road
     * (or its sidewalks) the road, which lies level across as it's drawn.
     */
    fun groundAt(x: Float, z: Float): Float {
        if (terrain.flat) return 0f
        return roadLevels?.heightAt(x, z) ?: terrain.heightAt(x, z)
    }

    /** True when (x, y, z) is under the ground (a bullet or grenade reaching a hillside or the street). */
    fun underground(x: Float, y: Float, z: Float) = y <= groundAt(x, z)

    /** True when a body of radius [r] at ([x], [z]) would hit a building, a tree, water or the play area's edge. */
    fun isBlocked(x: Float, z: Float, r: Float): Boolean {
        if (x < playMinX + r || x > playMaxX - r || z < playMinZ + r || z > playMaxZ - r) return true
        forCells(x - r, z - r, x + r, z + r) { cell ->
            for (b in buildingCells[cell]) {
                if (!b.blocksWalking) continue
                if (x < b.minX - r || x > b.maxX + r || z < b.minZ - r || z > b.maxZ + r) continue
                if (inside(b.pts, x, z) || edgeDistance(b.pts, x, z) < r) return true
            }
            for (t in treeCells[cell]) {
                val min = r + TRUNK_RADIUS * t.size
                val dx = x - t.x
                val dz = z - t.z
                if (dx * dx + dz * dz < min * min) return true
            }
            for (w in waterCells[cell]) if (inside(w, x, z)) return true
        }
        return false
    }

    /** True when a point is inside (or within [pad] of) a building's solid part; for the camera and bullets. */
    fun isInsideBuilding(x: Float, y: Float, z: Float, pad: Float): Boolean {
        forCells(x - pad, z - pad, x + pad, z + pad) { cell ->
            for (b in buildingCells[cell]) {
                if (y > b.top + pad || y < b.bottom - pad) continue
                if (x < b.minX - pad || x > b.maxX + pad || z < b.minZ - pad || z > b.maxZ + pad) continue
                if (inside(b.pts, x, z) || (pad > 0f && edgeDistance(b.pts, x, z) < pad)) return true
            }
        }
        return false
    }

    /**
     * How far (x, y, z) is from the nearest building wall, up to [max] metres, or 0 inside a
     * building; the camera uses it to keep its near clipping plane from reaching through walls.
     */
    fun wallClearance(x: Float, y: Float, z: Float, max: Float): Float {
        var best = max
        forCells(x - max, z - max, x + max, z + max) { cell ->
            for (b in buildingCells[cell]) {
                if (y > b.top + 0.3f || y < b.bottom - 0.3f) continue
                if (x < b.minX - best || x > b.maxX + best || z < b.minZ - best || z > b.maxZ + best) continue
                if (inside(b.pts, x, z) && y <= b.top && y >= b.bottom) return 0f
                best = min(best, edgeDistance(b.pts, x, z))
            }
        }
        return best
    }

    /** A random clear spot on a street inside the play area (for respawning). */
    fun randomStreetPoint(random: Random = Random): Pair<Float, Float> {
        val points = ArrayList<Pair<Float, Float>>()
        for (road in roads) {
            if (road.kind > ROAD_PEDESTRIAN && road.kind != ROAD_TRACK) continue
            for (i in road.pts.indices step 2) {
                if (inPlayArea(road.pts[i], road.pts[i + 1])) points += road.pts[i] to road.pts[i + 1]
            }
        }
        if (points.isNotEmpty()) repeat(200) {
            val p = points[random.nextInt(points.size)]
            if (!isBlocked(p.first, p.second, 0.6f)) return p
        }
        return spawnX to spawnZ
    }

    companion object {
        /** Spatial index cell size, metres. */
        private const val CELL = 24f
        private const val TRUNK_RADIUS = 0.3f

        const val BUILDING_GENERIC = 0
        const val BUILDING_MOSQUE = 1
        const val BUILDING_CHURCH = 2
        const val BUILDING_CONSTRUCTION = 3
        /** A sea rock such as Pigeon Rocks at Raouche: an island extruded from the coastline. */
        const val BUILDING_ROCK = 4

        const val ROAD_MAJOR = 0
        const val ROAD_MEDIUM = 1
        const val ROAD_MINOR = 2
        const val ROAD_PEDESTRIAN = 3
        const val ROAD_PATH = 4
        const val ROAD_PIER = 5
        /** A dirt or sand track (farm tracks in the mountains). */
        const val ROAD_TRACK = 6

        const val AREA_PARK = 0
        const val AREA_PITCH = 1
        const val AREA_PARKING = 2
        const val AREA_WATER = 3
        const val AREA_PLAZA = 4
        const val AREA_SAND = 5
        const val AREA_PIER = 6
        const val AREA_CONSTRUCTION = 7

        const val TREE_LEAFY = 0
        const val TREE_PALM = 1

        /** Reads the file written by tools/OsmToCity.java. */
        fun load(input: InputStream): CityMap = DataInputStream(input.buffered()).use { d ->
            val magic = ByteArray(4).also { d.readFully(it) }
            require(String(magic) == "BRMP") { "Not a city map" }
            val version = d.readInt()
            require(version in 1..2) { "Unsupported map version" }
            val minX = d.readFloat(); val minZ = d.readFloat(); val maxX = d.readFloat(); val maxZ = d.readFloat()
            val spawnX = d.readFloat(); val spawnZ = d.readFloat()
            val buildings = List(d.readInt()) {
                val h = d.readFloat(); val minH = d.readFloat(); val kind = d.readByte().toInt()
                Building(d.points(), h, minH, kind)
            }
            val roads = List(d.readInt()) {
                val kind = d.readByte().toInt(); val width = d.readFloat()
                Road(kind, width, d.points())
            }
            val areas = List(d.readInt()) { Area(d.readByte().toInt(), d.points()) }
            val sea = List(d.readInt()) { d.points() }
            val trees = List(d.readInt()) {
                Tree(d.readByte().toInt(), d.readFloat(), d.readFloat(), d.readFloat())
            }
            val attribution = d.readUTF()
            // Version 2 adds the ground's height (hills); version 1 maps are flat.
            val terrain = if (version < 2) Terrain.FLAT else {
                val x0 = d.readFloat(); val z0 = d.readFloat(); val cell = d.readFloat()
                val cols = d.readInt(); val rows = d.readInt()
                Terrain(x0, z0, cell, cols, rows, FloatArray(cols * rows) { d.readInt() / 100f })
            }
            CityMap(minX, minZ, maxX, maxZ, spawnX, spawnZ, buildings, roads, areas, sea, trees, attribution, terrain)
        }

        /** [p] (x, z pairs) with points added so none is more than [step] metres from the next. */
        fun densify(p: FloatArray, step: Float): FloatArray {
            val out = ArrayList<Float>(p.size * 2)
            for (i in 0 until p.size / 2) {
                val x = p[2 * i]; val z = p[2 * i + 1]
                if (i > 0) {
                    val px = p[2 * i - 2]; val pz = p[2 * i - 1]
                    // n points in between leave n + 1 pieces, each at most [step] long.
                    val n = (hypot(x - px, z - pz) / step).toInt()
                    for (k in 1..n) {
                        val t = k / (n + 1f)
                        out += px + (x - px) * t; out += pz + (z - pz) * t
                    }
                }
                out += x; out += z
            }
            return out.toFloatArray()
        }

        private fun DataInputStream.points(): FloatArray {
            val n = readUnsignedShort()
            return FloatArray(n * 2) { readFloat() }
        }

        // ---- Polygon helpers ---------------------------------------------------------------

        fun FloatArray.xs() = (indices step 2).map { this[it] }
        fun FloatArray.zs() = (1 until size step 2).map { this[it] }

        fun signedArea(r: FloatArray): Float {
            var a = 0f
            val n = r.size / 2
            for (i in 0 until n) {
                val j = (i + 1) % n
                a += r[2 * i] * r[2 * j + 1] - r[2 * j] * r[2 * i + 1]
            }
            return a / 2f
        }

        fun inside(ring: FloatArray, px: Float, pz: Float): Boolean {
            var result = false
            val n = ring.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = ring[2 * i]; val zi = ring[2 * i + 1]
                val xj = ring[2 * j]; val zj = ring[2 * j + 1]
                if ((zi > pz) != (zj > pz) && px < (xj - xi) * (pz - zi) / (zj - zi) + xi) result = !result
                j = i
            }
            return result
        }

        fun edgeDistance(ring: FloatArray, px: Float, pz: Float): Float {
            var best = Float.MAX_VALUE
            val n = ring.size / 2
            var j = n - 1
            for (i in 0 until n) {
                best = min(best, segmentDistance(px, pz, ring[2 * j], ring[2 * j + 1], ring[2 * i], ring[2 * i + 1]))
                j = i
            }
            return best
        }

        fun segmentDistance(px: Float, pz: Float, ax: Float, az: Float, bx: Float, bz: Float): Float {
            val dx = bx - ax
            val dz = bz - az
            val len2 = dx * dx + dz * dz
            val t = if (len2 == 0f) 0f else (((px - ax) * dx + (pz - az) * dz) / len2).coerceIn(0f, 1f)
            return hypot(px - (ax + t * dx), pz - (az + t * dz))
        }

        /**
         * Ear-clipping triangulation of a simple counter-clockwise ring; returns vertex index
         * triples. Falls back to a fan if the ring is self-intersecting (rare in OSM data).
         */
        fun triangulate(ring: FloatArray): IntArray {
            val n = ring.size / 2
            if (n < 3) return IntArray(0)
            val ccw = signedArea(ring) > 0f
            val idx = ArrayList<Int>(n).apply { for (i in 0 until n) add(if (ccw) i else n - 1 - i) }
            val out = ArrayList<Int>((n - 2) * 3)
            var guard = 0
            while (idx.size > 3 && guard < n * n) {
                guard++
                var clipped = false
                for (k in idx.indices) {
                    val a = idx[(k + idx.size - 1) % idx.size]
                    val b = idx[k]
                    val c = idx[(k + 1) % idx.size]
                    if (!isEar(ring, a, b, c, idx)) continue
                    out += a; out += b; out += c
                    idx.removeAt(k)
                    clipped = true
                    break
                }
                if (!clipped) break
            }
            if (idx.size == 3) { out += idx[0]; out += idx[1]; out += idx[2] }
            else for (k in 1 until idx.size - 1) { out += idx[0]; out += idx[k]; out += idx[k + 1] }
            return out.toIntArray()
        }

        private fun isEar(r: FloatArray, a: Int, b: Int, c: Int, idx: List<Int>): Boolean {
            val ax = r[2 * a]; val az = r[2 * a + 1]
            val bx = r[2 * b]; val bz = r[2 * b + 1]
            val cx = r[2 * c]; val cz = r[2 * c + 1]
            val cross = (bx - ax) * (cz - az) - (bz - az) * (cx - ax)
            if (cross <= 1e-6f) return false // reflex or degenerate corner
            for (p in idx) {
                if (p == a || p == b || p == c) continue
                if (inTriangle(r[2 * p], r[2 * p + 1], ax, az, bx, bz, cx, cz)) return false
            }
            return true
        }

        private fun inTriangle(px: Float, pz: Float, ax: Float, az: Float, bx: Float, bz: Float, cx: Float, cz: Float): Boolean {
            val d1 = (px - bx) * (az - bz) - (ax - bx) * (pz - bz)
            val d2 = (px - cx) * (bz - cz) - (bx - cx) * (pz - cz)
            val d3 = (px - ax) * (cz - az) - (cx - ax) * (pz - az)
            val neg = d1 < 0 || d2 < 0 || d3 < 0
            val pos = d1 > 0 || d2 > 0 || d3 > 0
            return !(neg && pos)
        }
    }
}
