package com.example.beirutrun.city

import com.example.beirutrun.city.CityMap.Companion.triangulate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** What a piece of the city is made of: its colour, optional wall texture, and whether sunlight shades it. */
enum class Surface(val color: Int, val wallStyle: Int = -1, val lit: Boolean = true) {
    SEA(0xFF2F6E95.toInt()),
    PARKING(0xFF8E8C88.toInt()), PLAZA(0xFFE0D6C2.toInt()), PARK(0xFF6FA35A.toInt()), PITCH(0xFF4F9A48.toInt()),
    WATER(0xFF2F6E95.toInt()), SAND(0xFFE3D3A2.toInt()), PIER(0xFFA3A19C.toInt()), CONSTRUCTION(0xFFA88F70.toInt()),
    ROAD_PATH(0xFFBBAE95.toInt()), ROAD_PEDESTRIAN(0xFFCBBFA7.toInt()), ROAD_MINOR(0xFF4E4F54.toInt()),
    ROAD_MEDIUM(0xFF44454A.toInt()), ROAD_MAJOR(0xFF3B3C40.toInt()), ROAD_PIER(0xFF9A9894.toInt()),
    LANE(0xFFE9E4D0.toInt(), lit = false),
    WALL_SANDSTONE(0xFFFFFFFF.toInt(), 0), WALL_CREAM(0xFFFFFFFF.toInt(), 1),
    WALL_CONCRETE(0xFFFFFFFF.toInt(), 2), WALL_GLASS(0xFFFFFFFF.toInt(), 3), WALL_WHITE(0xFFFFFFFF.toInt(), 4),
    ROOF(0xFFB8AD99.toInt()), ROOF_TOWER(0xFF5A646C.toInt()),
    DOME(0xFF2F6DB5.toInt()), GOLD(0xFFD4AF37.toInt()), MINARET(0xFFE8E0CC.toInt()), TERRACOTTA(0xFFA4553A.toInt()),
    ROCK(0xFF9C8A74.toInt()),
    TRUNK(0xFF6B4A2F.toInt()), PALM_TRUNK(0xFF8A7355.toInt()), LEAVES(0xFF4E8C3A.toInt()), PALM_LEAVES(0xFF3F7F2E.toInt()),
}

/**
 * The city's geometry, ready to upload: split into square tiles so only tiles near the camera are
 * drawn, each holding one vertex list per [Surface] (position 3, normal 3, uv 2 per vertex, as
 * [Mesh] expects). Plain Kotlin, so it can be built on a background thread and unit tested.
 */
class CityScene(val tiles: List<Tile>, val always: Map<Surface, FloatArray>) {

    class Tile(val centerX: Float, val centerZ: Float, val parts: Map<Surface, FloatArray>)

    val vertexCount get() = (tiles.sumOf { t -> t.parts.values.sumOf { it.size } } + always.values.sumOf { it.size }) / FLOATS

    companion object {
        const val FLOATS = 8
        const val TILE = 128f
        private const val WINDOW_CELL = 3.2f

        fun build(map: CityMap, look: CityLook = CityLook.MIXED): CityScene {
            val builder = Builder(map, look)
            builder.addAll()
            return builder.result()
        }
    }

    /** Growable float list for vertex data. */
    class Floats {
        var data = FloatArray(1024)
        var size = 0

        fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float) {
            if (size + FLOATS > data.size) data = data.copyOf(max(data.size * 2, size + FLOATS))
            val d = data
            d[size] = x; d[size + 1] = y; d[size + 2] = z
            d[size + 3] = nx; d[size + 4] = ny; d[size + 5] = nz
            d[size + 6] = u; d[size + 7] = v
            size += FLOATS
        }

        fun toArray() = data.copyOf(size)
    }

    private class Builder(val map: CityMap, val look: CityLook) {
        private val cols = ((map.maxX - map.minX) / TILE).toInt() + 1
        private val rows = ((map.maxZ - map.minZ) / TILE).toInt() + 1
        private val tileParts = Array(cols * rows) { HashMap<Surface, Floats>() }
        private val alwaysParts = HashMap<Surface, Floats>()

        private fun out(x: Float, z: Float, s: Surface): Floats {
            val c = floor((x - map.minX) / TILE).toInt().coerceIn(0, cols - 1)
            val r = floor((z - map.minZ) / TILE).toInt().coerceIn(0, rows - 1)
            return tileParts[r * cols + c].getOrPut(s) { Floats() }
        }

        fun result(): CityScene {
            val tiles = ArrayList<Tile>()
            for (r in 0 until rows) for (c in 0 until cols) {
                val parts = tileParts[r * cols + c]
                if (parts.isEmpty()) continue
                tiles += Tile(
                    map.minX + (c + 0.5f) * TILE, map.minZ + (r + 0.5f) * TILE,
                    parts.mapValues { it.value.toArray() },
                )
            }
            return CityScene(tiles, alwaysParts.mapValues { it.value.toArray() })
        }

        fun addAll() {
            // Sea goes in "always" so the coast is visible from far off.
            for (s in map.sea) flatPolygon(alwaysParts.getOrPut(Surface.SEA) { Floats() }, s, 0.012f)
            for (a in map.areas) {
                val (surface, y) = when (a.kind) {
                    CityMap.AREA_PARKING -> Surface.PARKING to 0.02f
                    CityMap.AREA_PLAZA -> Surface.PLAZA to 0.025f
                    CityMap.AREA_PARK -> Surface.PARK to 0.03f
                    CityMap.AREA_PITCH -> Surface.PITCH to 0.032f
                    CityMap.AREA_WATER -> Surface.WATER to 0.035f
                    CityMap.AREA_SAND -> Surface.SAND to 0.028f
                    CityMap.AREA_PIER -> Surface.PIER to 0.3f
                    else -> Surface.CONSTRUCTION to 0.022f
                }
                val (cx, cz) = centroid(a.pts)
                flatPolygon(out(cx, cz, surface), a.pts, y)
            }
            for (road in map.roads) addRoad(road)
            map.buildings.forEachIndexed { i, b -> addBuilding(b, i) }
            for (t in map.trees) if (t.kind == CityMap.TREE_PALM) addPalm(t) else addLeafyTree(t)
        }

        // ---- Flat things ---------------------------------------------------------------------

        private fun flatPolygon(o: Floats, ring: FloatArray, y: Float) {
            val tri = triangulate(ring)
            var i = 0
            while (i < tri.size) {
                // triangulate() returns counter-clockwise triangles; face them upwards.
                for (k in intArrayOf(tri[i], tri[i + 2], tri[i + 1])) {
                    o.vertex(ring[2 * k], y, ring[2 * k + 1], 0f, 1f, 0f, 0.02f, 0.02f)
                }
                i += 3
            }
        }

        private fun addRoad(road: CityMap.Road) {
            val (surface, y) = when (road.kind) {
                CityMap.ROAD_MAJOR -> Surface.ROAD_MAJOR to 0.06f
                CityMap.ROAD_MEDIUM -> Surface.ROAD_MEDIUM to 0.055f
                CityMap.ROAD_MINOR -> Surface.ROAD_MINOR to 0.05f
                CityMap.ROAD_PEDESTRIAN -> Surface.ROAD_PEDESTRIAN to 0.045f
                CityMap.ROAD_PIER -> Surface.ROAD_PIER to 0.3f
                else -> Surface.ROAD_PATH to 0.04f
            }
            val p = road.pts
            val half = road.width / 2f
            var i = 0
            while (i + 3 < p.size) {
                val ax = p[i]; val az = p[i + 1]; val bx = p[i + 2]; val bz = p[i + 3]
                val o = out((ax + bx) / 2f, (az + bz) / 2f, surface)
                strip(o, ax, az, bx, bz, half, y)
                disc(o, ax, az, half, y)
                i += 2
            }
            disc(out(p[p.size - 2], p[p.size - 1], surface), p[p.size - 2], p[p.size - 1], half, y)

            // Dashed centre line on the bigger roads.
            if (road.kind == CityMap.ROAD_MAJOR || road.kind == CityMap.ROAD_MEDIUM) {
                var carry = 2f
                i = 0
                while (i + 3 < p.size) {
                    val ax = p[i]; val az = p[i + 1]; val bx = p[i + 2]; val bz = p[i + 3]
                    val len = hypot(bx - ax, bz - az)
                    var t = carry
                    while (t + 3f < len) {
                        val sx = ax + (bx - ax) * t / len; val sz = az + (bz - az) * t / len
                        val ex = ax + (bx - ax) * (t + 3f) / len; val ez = az + (bz - az) * (t + 3f) / len
                        strip(out(sx, sz, Surface.LANE), sx, sz, ex, ez, 0.08f, y + 0.012f)
                        t += 9f
                    }
                    carry = max(0f, t - len)
                    i += 2
                }
            }
        }

        private fun strip(o: Floats, ax: Float, az: Float, bx: Float, bz: Float, half: Float, y: Float) {
            val len = hypot(bx - ax, bz - az)
            if (len < 1e-3f) return
            val nx = -(bz - az) / len * half
            val nz = (bx - ax) / len * half
            val u = 0.02f
            // Two triangles, wound to face up.
            o.vertex(ax + nx, y, az + nz, 0f, 1f, 0f, u, u)
            o.vertex(bx - nx, y, bz - nz, 0f, 1f, 0f, u, u)
            o.vertex(ax - nx, y, az - nz, 0f, 1f, 0f, u, u)
            o.vertex(ax + nx, y, az + nz, 0f, 1f, 0f, u, u)
            o.vertex(bx + nx, y, bz + nz, 0f, 1f, 0f, u, u)
            o.vertex(bx - nx, y, bz - nz, 0f, 1f, 0f, u, u)
        }

        /** A round join so road segments meet without gaps at bends. */
        private fun disc(o: Floats, cx: Float, cz: Float, r: Float, y: Float) {
            val n = 8
            for (k in 0 until n) {
                val a0 = 2 * PI * k / n
                val a1 = 2 * PI * (k + 1) / n
                o.vertex(cx, y, cz, 0f, 1f, 0f, 0.02f, 0.02f)
                o.vertex(cx + (cos(a1) * r).toFloat(), y, cz + (sin(a1) * r).toFloat(), 0f, 1f, 0f, 0.02f, 0.02f)
                o.vertex(cx + (cos(a0) * r).toFloat(), y, cz + (sin(a0) * r).toFloat(), 0f, 1f, 0f, 0.02f, 0.02f)
            }
        }

        // ---- Buildings -----------------------------------------------------------------------

        private fun addBuilding(b: CityMap.Building, index: Int) {
            val rnd = Random(index * 7919L + 17)
            val wall = when {
                b.kind == CityMap.BUILDING_MOSQUE || b.kind == CityMap.BUILDING_CHURCH -> Surface.WALL_SANDSTONE
                b.kind == CityMap.BUILDING_ROCK -> Surface.ROCK
                b.kind == CityMap.BUILDING_CONSTRUCTION -> Surface.WALL_CONCRETE
                b.height > look.glassAbove -> Surface.WALL_GLASS
                else -> {
                    // The area's typical mix of wall styles (see CityLook).
                    val r = rnd.nextFloat()
                    when {
                        r < look.sandstone -> Surface.WALL_SANDSTONE
                        r < look.sandstone + look.cream -> Surface.WALL_CREAM
                        r < look.sandstone + look.cream + look.concrete -> Surface.WALL_CONCRETE
                        else -> Surface.WALL_WHITE
                    }
                }
            }
            val cx = b.centerX
            val cz = b.centerZ
            walls(out(cx, cz, wall), b.pts, b.minHeight, b.height)
            val roof = when (wall) { Surface.WALL_GLASS -> Surface.ROOF_TOWER; Surface.ROCK -> Surface.ROCK; else -> Surface.ROOF }
            flatRoof(out(cx, cz, roof), b.pts, b.height)

            when (b.kind) {
                CityMap.BUILDING_MOSQUE -> addMosque(b)
                CityMap.BUILDING_CHURCH -> addBellTower(b)
            }
        }

        /** Extruded walls; the window texture runs continuously round the building. */
        private fun walls(o: Floats, ring: FloatArray, y0: Float, y1: Float) {
            val n = ring.size / 2
            var u = 0f
            val v = (y1 - y0) / WINDOW_CELL
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ax = ring[2 * i]; val az = ring[2 * i + 1]
                val bx = ring[2 * j]; val bz = ring[2 * j + 1]
                val len = hypot(bx - ax, bz - az)
                if (len < 1e-3f) continue
                // Counter-clockwise ring: the outside is to the right of a → b.
                val nx = (bz - az) / len
                val nz = -(bx - ax) / len
                val u1 = u + len / WINDOW_CELL
                // Seen from outside, b is on the left and a on the right.
                o.vertex(bx, y0, bz, nx, 0f, nz, u, v)
                o.vertex(ax, y0, az, nx, 0f, nz, u1, v)
                o.vertex(ax, y1, az, nx, 0f, nz, u1, 0f)
                o.vertex(bx, y0, bz, nx, 0f, nz, u, v)
                o.vertex(ax, y1, az, nx, 0f, nz, u1, 0f)
                o.vertex(bx, y1, bz, nx, 0f, nz, u, 0f)
                u = u1
            }
        }

        private fun flatRoof(o: Floats, ring: FloatArray, y: Float) = flatPolygon(o, ring, y)

        /** A blue dome with a gold tip on the roof, and minarets (four on big mosques like Al-Amin). */
        private fun addMosque(b: CityMap.Building) {
            val cx = b.centerX
            val cz = b.centerZ
            val r = (sqrt(b.area) * 0.28f).coerceIn(3f, 14f)
            sphere(out(cx, cz, Surface.DOME), cx, b.height, cz, r, 7, 12, hemisphere = true)
            sphere(out(cx, cz, Surface.GOLD), cx, b.height + r, cz, r * 0.08f + 0.3f, 4, 6, hemisphere = false)
            val minaretHeight = (b.height * 2.6f).coerceIn(22f, 65f)
            val inset = 1.8f
            val corners = listOf(b.minX + inset to b.minZ + inset, b.maxX - inset to b.minZ + inset,
                b.maxX - inset to b.maxZ - inset, b.minX + inset to b.maxZ - inset)
            val chosen = if (b.area > 1200f) corners else corners.take(1)
            for ((mx, mz) in chosen) {
                if (!CityMap.inside(b.pts, mx, mz)) continue
                val mr = if (b.area > 1200f) 1.4f else 1f
                prism(out(mx, mz, Surface.MINARET), mx, mz, mr, mr * 0.85f, 0f, minaretHeight, 8)
                // Balcony ring, then a pointed blue cap.
                prism(out(mx, mz, Surface.MINARET), mx, mz, mr * 1.4f, mr * 1.4f, minaretHeight * 0.78f, minaretHeight * 0.8f, 8)
                prism(out(mx, mz, Surface.DOME), mx, mz, mr * 0.85f, 0.05f, minaretHeight, minaretHeight + mr * 3f, 8)
            }
        }

        private fun addBellTower(b: CityMap.Building) {
            val size = (sqrt(b.area) * 0.22f).coerceIn(2.5f, 6f)
            val tx = b.minX + size / 2f + 0.5f
            val tz = b.minZ + size / 2f + 0.5f
            if (!CityMap.inside(b.pts, tx, tz)) return
            val top = b.height * 1.7f
            prism(out(tx, tz, Surface.WALL_SANDSTONE), tx, tz, size * 0.7f, size * 0.7f, 0f, top, 4)
            prism(out(tx, tz, Surface.TERRACOTTA), tx, tz, size * 0.75f, 0.05f, top, top + size * 1.2f, 4)
        }

        // ---- Trees ---------------------------------------------------------------------------

        private fun addPalm(t: CityMap.Tree) {
            val h = 7.5f * t.size
            prism(out(t.x, t.z, Surface.PALM_TRUNK), t.x, t.z, 0.24f * t.size, 0.16f * t.size, 0f, h, 7)
            val o = out(t.x, t.z, Surface.PALM_LEAVES)
            val fronds = 8
            val rnd = Random((t.x * 31 + t.z * 17).toInt())
            val twist = rnd.nextFloat() * 2f * PI.toFloat()
            for (k in 0 until fronds) {
                val a = twist + 2f * PI.toFloat() * k / fronds
                val dx = cos(a); val dz = sin(a)
                val len = (2.6f + rnd.nextFloat() * 0.8f) * t.size
                // Each frond rises a little, then droops: two leaf panels.
                leaf(o, t.x, h, t.z, dx, dz, len * 0.5f, 0.55f * t.size, 0.35f * t.size, 0.5f * t.size)
                leaf(o, t.x + dx * len * 0.5f, h + 0.35f * t.size, t.z + dz * len * 0.5f, dx, dz, len * 0.5f, 0.5f * t.size, -1.3f * t.size, 0.25f * t.size)
            }
        }

        /** A flat leaf panel from (x, y, z) outwards along (dx, dz), rising by [rise]; tapers from w0 to w1. */
        private fun leaf(o: Floats, x: Float, y: Float, z: Float, dx: Float, dz: Float, len: Float, w0: Float, rise: Float, w1: Float) {
            val sx = -dz; val sz = dx // sideways
            val ex = x + dx * len; val ey = y + rise; val ez = z + dz * len
            // Normal: roughly up, tilted by the slope.
            val nl = hypot(rise, len)
            val nx = -dx * rise / nl; val ny = len / nl; val nz = -dz * rise / nl
            val u = 0.02f
            o.vertex(x - sx * w0 / 2, y, z - sz * w0 / 2, nx, ny, nz, u, u)
            o.vertex(x + sx * w0 / 2, y, z + sz * w0 / 2, nx, ny, nz, u, u)
            o.vertex(ex + sx * w1 / 2, ey, ez + sz * w1 / 2, nx, ny, nz, u, u)
            o.vertex(x - sx * w0 / 2, y, z - sz * w0 / 2, nx, ny, nz, u, u)
            o.vertex(ex + sx * w1 / 2, ey, ez + sz * w1 / 2, nx, ny, nz, u, u)
            o.vertex(ex - sx * w1 / 2, ey, ez - sz * w1 / 2, nx, ny, nz, u, u)
        }

        private fun addLeafyTree(t: CityMap.Tree) {
            val s = t.size
            prism(out(t.x, t.z, Surface.TRUNK), t.x, t.z, 0.2f * s, 0.14f * s, 0f, 2.6f * s, 6)
            val o = out(t.x, t.z, Surface.LEAVES)
            sphere(o, t.x, 3.6f * s, t.z, 1.9f * s, 5, 8, hemisphere = false)
            sphere(o, t.x + 0.7f * s, 4.4f * s, t.z - 0.4f * s, 1.2f * s, 4, 7, hemisphere = false)
        }

        // ---- Solids --------------------------------------------------------------------------

        /** An upright prism (or cone when [r1] is ~0) with smooth side normals. */
        private fun prism(o: Floats, cx: Float, cz: Float, r0: Float, r1: Float, y0: Float, y1: Float, sides: Int) {
            val slope = (r0 - r1) / (y1 - y0)
            for (k in 0 until sides) {
                val a0 = 2f * PI.toFloat() * k / sides
                val a1 = 2f * PI.toFloat() * (k + 1) / sides
                val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
                val ny = slope / sqrt(1f + slope * slope)
                val nh = 1f / sqrt(1f + slope * slope)
                val u = 0.02f
                // Outside faces: going round with increasing angle, (a1) is on the viewer's left.
                o.vertex(cx + c1 * r0, y0, cz + s1 * r0, c1 * nh, ny, s1 * nh, u, u)
                o.vertex(cx + c0 * r0, y0, cz + s0 * r0, c0 * nh, ny, s0 * nh, u, u)
                o.vertex(cx + c0 * r1, y1, cz + s0 * r1, c0 * nh, ny, s0 * nh, u, u)
                o.vertex(cx + c1 * r0, y0, cz + s1 * r0, c1 * nh, ny, s1 * nh, u, u)
                o.vertex(cx + c0 * r1, y1, cz + s0 * r1, c0 * nh, ny, s0 * nh, u, u)
                o.vertex(cx + c1 * r1, y1, cz + s1 * r1, c1 * nh, ny, s1 * nh, u, u)
                if (r1 > 0.1f) {
                    // Flat top.
                    o.vertex(cx, y1, cz, 0f, 1f, 0f, u, u)
                    o.vertex(cx + c1 * r1, y1, cz + s1 * r1, 0f, 1f, 0f, u, u)
                    o.vertex(cx + c0 * r1, y1, cz + s0 * r1, 0f, 1f, 0f, u, u)
                }
            }
        }

        /** A UV sphere (or its top half) with smooth normals, for domes and tree canopies. */
        private fun sphere(o: Floats, cx: Float, cy: Float, cz: Float, r: Float, stacks: Int, slices: Int, hemisphere: Boolean) {
            val startLat = if (hemisphere) 0.0 else -PI / 2
            fun point(i: Int, j: Int): FloatArray {
                val lat = startLat + (PI / 2 - startLat) * i / stacks
                val lon = 2 * PI * j / slices
                val x = (cos(lat) * cos(lon)).toFloat()
                val y = sin(lat).toFloat()
                val z = (cos(lat) * sin(lon)).toFloat()
                return floatArrayOf(x, y, z)
            }
            val u = 0.02f
            fun v(p: FloatArray) = o.vertex(cx + p[0] * r, cy + p[1] * r, cz + p[2] * r, p[0], p[1], p[2], u, u)
            for (i in 0 until stacks) for (j in 0 until slices) {
                val a = point(i, j); val b = point(i, j + 1); val c = point(i + 1, j + 1); val d = point(i + 1, j)
                v(a); v(d); v(c)
                v(a); v(c); v(b)
            }
        }

        private fun centroid(ring: FloatArray): Pair<Float, Float> {
            var sx = 0f; var sz = 0f
            val n = ring.size / 2
            for (i in 0 until n) { sx += ring[2 * i]; sz += ring[2 * i + 1] }
            return sx / n to sz / n
        }
    }
}
