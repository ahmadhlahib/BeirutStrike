package com.example.beirutrun.city

import com.example.beirutrun.city.CityMap.Companion.triangulate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * What a piece of the city is made of: its colour, optional repeating texture (see [CityTextures]),
 * whether sunlight shades it, how much of the sky it reflects ([shine]), whether it darkens
 * near the ground the way real walls do ([ao]), and whether its texture's see-through parts are
 * cut away, as between a palm frond's leaflets ([cutout]).
 */
enum class Surface(
    val color: Int, val texture: Int = -1, val lit: Boolean = true, val shine: Float = 0f, val ao: Boolean = false,
    val cutout: Boolean = false,
) {
    SEA(0xFF2F6E95.toInt(), shine = 0.5f),
    PARKING(0xFF96938E.toInt(), CityTextures.ASPHALT), PLAZA(0xFFE8DDC8.toInt(), CityTextures.PAVING),
    PARK(0xFF78AE5F.toInt(), CityTextures.GRASS), PITCH(0xFF56A44E.toInt(), CityTextures.GRASS),
    WATER(0xFF2F6E95.toInt(), shine = 0.5f), SAND(0xFFEFDDA8.toInt(), CityTextures.SAND),
    PIER(0xFFADABA6.toInt(), CityTextures.SLABS), CONSTRUCTION(0xFFB39878.toInt(), CityTextures.SAND),
    ROAD_PATH(0xFFC8BA9F.toInt(), CityTextures.SAND), ROAD_TRACK(0xFFC9A86A.toInt(), CityTextures.SAND),
    // Hillsides (maps with hills): dry grass and scrub, and bare rock and earth where it's steep.
    HILLSIDE(0xFFB9B98A.toInt(), CityTextures.GRASS), HILL_ROCK(0xFFB4A288.toInt(), CityTextures.SAND),
    ROAD_PEDESTRIAN(0xFFDDCFB3.toInt(), CityTextures.PAVING),
    ROAD_MINOR(0xFF56575C.toInt(), CityTextures.ASPHALT), ROAD_MEDIUM(0xFF4C4D52.toInt(), CityTextures.ASPHALT),
    ROAD_MAJOR(0xFF434448.toInt(), CityTextures.ASPHALT), ROAD_PIER(0xFFA4A29E.toInt(), CityTextures.SLABS),
    SIDEWALK(0xFFD6D1C7.toInt(), CityTextures.SLABS), CURB(0xFFBDB9B0.toInt()),
    LANE(0xFFE9E4D0.toInt(), lit = false),
    WALL_SANDSTONE(0xFFFFFFFF.toInt(), CityTextures.SANDSTONE, ao = true),
    WALL_CREAM(0xFFFFFFFF.toInt(), CityTextures.CREAM, ao = true),
    WALL_CONCRETE(0xFFFFFFFF.toInt(), CityTextures.CONCRETE, ao = true),
    WALL_GLASS(0xFFFFFFFF.toInt(), CityTextures.GLASS, shine = 0.45f, ao = true),
    WALL_WHITE(0xFFFFFFFF.toInt(), CityTextures.WHITE_TOWER, ao = true),
    SHOPFRONT(0xFFFFFFFF.toInt(), CityTextures.SHOPFRONT, ao = true),
    CORNICE(0xFFE4DACA.toInt()),
    ROOF(0xFFBDB2A0.toInt(), CityTextures.ROOF), ROOF_TOWER(0xFF5E6870.toInt(), CityTextures.ROOF),
    DOME(0xFF2F6DB5.toInt()), GOLD(0xFFD4AF37.toInt()), MINARET(0xFFE8E0CC.toInt()), TERRACOTTA(0xFFA4553A.toInt()),
    // Sea stacks (Pigeon Rocks, see SeaStack): sunlit limestone, dark wet rock at the waterline, scrub on top.
    ROCK(0xFFD9C6A2.toInt(), CityTextures.SAND), ROCK_WET(0xFF7D705E.toInt(), CityTextures.SAND),
    SCRUB(0xFF5E7340.toInt(), CityTextures.GRASS),
    TRUNK(0xFF8E6E52.toInt(), CityTextures.BARK), PALM_TRUNK(0xFFAA9474.toInt(), CityTextures.PALM_BARK),
    LEAVES(0xFF74B654.toInt(), CityTextures.LEAF), LEAVES_DARK(0xFF559A4A.toInt(), CityTextures.LEAF),
    LEAVES_OLIVE(0xFF98AE58.toInt(), CityTextures.LEAF),
    PALM_LEAVES(0xFF6EA43E.toInt(), CityTextures.FROND, cutout = true), PALM_CROWN(0xFF6E5A3E.toInt()),
    POLE(0xFF4A4D50.toInt()), LAMP(0xFFF4F0DE.toInt(), lit = false), GUTTER(0xFF3A3A38.toInt()),
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
        /** Road layers, bottom to top: where roads cross, the higher one covers the other's edges. */
        private const val SIDEWALK_Y = 0.045f
        private const val CURB_Y = 0.054f
        private const val CURB_WIDTH = 0.25f
        private const val GUTTER_Y = 0.06f
        private const val GUTTER_WIDTH = 0.08f
        /** Lane lines stop this far short of a junction's widest road. */
        private const val JUNCTION_CLEAR = 1.5f
        /** Zebra crossings: how far back from a junction's widest road they start, and how long the stripes are. */
        private const val CROSSING_BACK = 2.4f
        private const val CROSSING_LENGTH = 3f
        private const val LAMP_SPACING = 32f
        private const val LAMP_HEIGHT = 7.5f
        /**
         * Laying roads and squares over hills: how far the ground may bend away from a straight
         * line before a point is added (metres), and the shortest piece worth splitting.
         */
        private const val GROUND_TOLERANCE = 0.1f
        private const val MIN_GROUND_STEP = 2f
        /** How far the hillside under a road is drawn below it (out of sight), metres. */
        private const val ROAD_SINK = 0.3f
        /** A sea rock bigger than this (m²) is the big Pigeon Rock, with its arch. */
        private const val ARCH_ROCK_AREA = 1500f
        /** Pieces a palm trunk is curved in, and panels along a frond. */
        private const val PALM_SEGMENTS = 3
        private const val FROND_PANELS = 3

        /** How wide the sidewalk is each side of a kind of road (0: none). */
        fun sidewalkWidth(kind: Int) = when (kind) {
            CityMap.ROAD_MAJOR -> 3f
            CityMap.ROAD_MEDIUM -> 2.5f
            CityMap.ROAD_MINOR -> 1.8f
            else -> 0f
        }
        /** The moulding round the top of a building's walls: how far it sticks out, and how tall it is. */
        private const val CORNICE_DEPTH = 0.28f
        private const val CORNICE_HEIGHT = 0.4f

        fun build(map: CityMap, look: CityLook = CityLook.MIXED): CityScene {
            val builder = Builder(map, look)
            builder.addAll()
            return builder.result()
        }
    }

    /**
     * Growable float list for vertex data. Ground textures are mapped from ([ox], [oz]), the
     * tile's corner, so their coordinates stay small enough for the GPU to keep precise.
     */
    class Floats(val ox: Float = 0f, val oz: Float = 0f, private val lift: Lift? = null) {
        var data = FloatArray(1024)
        var size = 0

        fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float) {
            if (size + FLOATS > data.size) data = data.copyOf(max(data.size * 2, size + FLOATS))
            val d = data
            d[size] = x; d[size + 1] = y + (lift?.at(x, z) ?: 0f); d[size + 2] = z
            d[size + 3] = nx; d[size + 4] = ny; d[size + 5] = nz
            d[size + 6] = u; d[size + 7] = v
            size += FLOATS
        }

        fun toArray() = data.copyOf(size)
    }

    /**
     * Raises what's built onto the hills (maps with hills; on flat maps the ground is 0, so
     * nothing moves). Things laid on the ground (roads, areas, lamps) follow it vertex by vertex;
     * a building or tree is raised as one piece ([fixed]) so it stays upright and square.
     */
    class Lift(private val terrain: Terrain) {
        /** Raise everything by this much, not by the ground under each vertex; null: follow the ground. */
        var fixed: Float? = null

        fun at(x: Float, z: Float): Float = fixed ?: terrain.heightAt(x, z)

        /** Builds [what] raised by [dy] as one piece. */
        inline fun <T> by(dy: Float, what: () -> T): T {
            val before = fixed
            fixed = dy
            try { return what() } finally { fixed = before }
        }
    }

    private class Builder(val map: CityMap, val look: CityLook) {
        private val cols = ((map.maxX - map.minX) / TILE).toInt() + 1
        private val rows = ((map.maxZ - map.minZ) / TILE).toInt() + 1
        private val tileParts = Array(cols * rows) { HashMap<Surface, Floats>() }
        private val alwaysParts = HashMap<Surface, Floats>()
        /** Buildings with a ladder up the wall (see Ladders): no cornice, so it doesn't cut through one. */
        private val withLadder = Ladders.place(map).mapTo(HashSet()) { it.building }
        private val network = RoadNetwork(map.roads)
        /** Raises everything onto the hills (see [Lift]). */
        private val lift = Lift(map.terrain)

        private fun out(x: Float, z: Float, s: Surface): Floats {
            val c = floor((x - map.minX) / TILE).toInt().coerceIn(0, cols - 1)
            val r = floor((z - map.minZ) / TILE).toInt().coerceIn(0, rows - 1)
            return tileParts[r * cols + c].getOrPut(s) { Floats(map.minX + c * TILE, map.minZ + r * TILE, lift) }
        }

        /** Metres one repeat of [s]'s ground texture covers; 0 when it has none. */
        private fun span(s: Surface) = if (s.texture >= 0) CityTextures.groundSpan(s.texture) else 0f

        /** An upward-facing vertex, its texture laid in world space every [span] metres (0 = untextured). */
        private fun flat(o: Floats, x: Float, y: Float, z: Float, span: Float) {
            if (span > 0f) o.vertex(x, y, z, 0f, 1f, 0f, (x - o.ox) / span, (z - o.oz) / span)
            else o.vertex(x, y, z, 0f, 1f, 0f, 0.02f, 0.02f)
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
            // The sea is at sea level, flat, whatever the land beside it.
            lift.by(0f) { for (s in map.sea) flatPolygon(alwaysParts.getOrPut(Surface.SEA) { Floats(lift = lift) }, s, 0.012f) }
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
                // Water and piers lie level, at the lowest ground they touch (the sea for a harbour);
                // everything else is laid over the ground.
                if (a.kind == CityMap.AREA_WATER || a.kind == CityMap.AREA_PIER) {
                    lift.by(map.terrain.lowestUnder(a.pts)) { flatPolygon(out(cx, cz, surface), a.pts, y, span(surface)) }
                } else {
                    flatPolygon(out(cx, cz, surface), a.pts, y, span(surface))
                }
            }
            for (road in map.roads) addRoad(road)
            addCrossings()
            // Buildings stand on the lowest ground under them, trees a little into it, upright.
            map.buildings.forEachIndexed { i, b -> lift.by(b.base) { addBuilding(b, i) } }
            for (t in map.trees) lift.by(map.groundAt(t.x, t.z) - 0.15f) {
                if (t.kind == CityMap.TREE_PALM) addPalm(t) else addLeafyTree(t)
            }
            if (!map.terrain.flat) addHillsides()
        }

        /**
         * The hills' ground: the height grid as triangles, lit by their slope; gentle slopes are
         * dry grass, steep ones bare rock and earth. Split into tiles like everything else.
         */
        private fun addHillsides() {
            val t = map.terrain
            val c = t.cell
            val under = underRoads()
            // The hillside under a road is drawn a little lower, out of sight: the road's surface is
            // flat between its points, and the hill mustn't show through it where it bulges.
            fun height(cc: Int, rr: Int): Float {
                val ci = cc.coerceIn(0, t.cols - 1); val ri = rr.coerceIn(0, t.rows - 1)
                return t.at(ci, ri) - if (under[ri * t.cols + ci]) ROAD_SINK else 0f
            }
            lift.by(0f) {
                for (r in 0 until t.rows - 1) for (k in 0 until t.cols - 1) {
                    val x0 = t.x0 + k * c
                    val z0 = t.z0 + r * c
                    val h00 = t.at(k, r); val h10 = t.at(k + 1, r); val h01 = t.at(k, r + 1); val h11 = t.at(k + 1, r + 1)
                    // Nobody sees the ground under the sea, or inside a building that stands on it.
                    if (maxOf(h00, h10, h01, h11) <= 0f) continue
                    if (coveredByBuilding(x0, z0, c)) continue
                    // How steep this cell is (rise over run), for grass or rock.
                    val steep = maxOf(abs(h10 - h00), abs(h01 - h00), abs(h11 - h10), abs(h11 - h01)) / c
                    val surface = if (steep > 0.75f) Surface.HILL_ROCK else Surface.HILLSIDE
                    val o = out(x0 + c / 2f, z0 + c / 2f, surface)
                    val s = span(surface)
                    fun v(cc: Int, rr: Int) {
                        val x = t.x0 + cc * c
                        val z = t.z0 + rr * c
                        // The slope's normal, from the heights either side.
                        val nx = t.at(cc - 1, rr) - t.at(cc + 1, rr)
                        val nz = t.at(cc, rr - 1) - t.at(cc, rr + 1)
                        val ny = 2f * c
                        val len = sqrt(nx * nx + ny * ny + nz * nz)
                        o.vertex(x, height(cc, rr), z, nx / len, ny / len, nz / len, (x - o.ox) / s, (z - o.oz) / s)
                    }
                    // Two triangles, wound to face up.
                    v(k, r); v(k, r + 1); v(k + 1, r)
                    v(k + 1, r); v(k, r + 1); v(k + 1, r + 1)
                }
            }
        }

        // ---- Flat things ---------------------------------------------------------------------

        private fun flatPolygon(o: Floats, ring: FloatArray, y: Float, span: Float = 0f) {
            val tri = triangulate(ring)
            // Laid on hills, big triangles are cut small so they follow the ground; flat ones stay whole.
            val drape = !map.terrain.flat && lift.fixed == null
            var i = 0
            while (i < tri.size) {
                // triangulate() returns counter-clockwise triangles; face them upwards.
                val a = tri[i]; val b = tri[i + 2]; val c = tri[i + 1]
                if (drape) {
                    draped(o, ring[2 * a], ring[2 * a + 1], ring[2 * b], ring[2 * b + 1], ring[2 * c], ring[2 * c + 1], y, span, 0)
                } else {
                    for (k in intArrayOf(a, b, c)) flat(o, ring[2 * k], y, ring[2 * k + 1], span)
                }
                i += 3
            }
        }

        /** Which of the height grid's points are under a road or its sidewalks (see [addHillsides]). */
        private fun underRoads(): BooleanArray {
            val t = map.terrain
            val under = BooleanArray(t.cols * t.rows)
            for (road in map.roads) {
                if (road.kind == CityMap.ROAD_PIER) continue
                // Short of the road's edge, so the lowered ground never shows beside it.
                val reach = road.width / 2f + sidewalkWidth(road.kind) - 0.3f
                if (reach <= 0f) continue
                val p = road.pts
                for (i in 0 until p.size / 2 - 1) {
                    val ax = p[2 * i]; val az = p[2 * i + 1]; val bx = p[2 * i + 2]; val bz = p[2 * i + 3]
                    val c0 = floor((minOf(ax, bx) - reach - t.x0) / t.cell).toInt().coerceAtLeast(0)
                    val c1 = ceil((maxOf(ax, bx) + reach - t.x0) / t.cell).toInt().coerceAtMost(t.cols - 1)
                    val r0 = floor((minOf(az, bz) - reach - t.z0) / t.cell).toInt().coerceAtLeast(0)
                    val r1 = ceil((maxOf(az, bz) + reach - t.z0) / t.cell).toInt().coerceAtMost(t.rows - 1)
                    for (r in r0..r1) for (c in c0..c1) {
                        if (CityMap.segmentDistance(t.x0 + c * t.cell, t.z0 + r * t.cell, ax, az, bx, bz) <= reach) under[r * t.cols + c] = true
                    }
                }
            }
            return under
        }

        /** Whether the ground cell at (x0, z0), [c] metres square, is all inside one building's solid ground floor. */
        private fun coveredByBuilding(x0: Float, z0: Float, c: Float): Boolean {
            val y = map.groundAt(x0 + c / 2f, z0 + c / 2f) + 0.5f
            return map.isInsideBuilding(x0, y, z0, 0f) && map.isInsideBuilding(x0 + c, y, z0, 0f) &&
                map.isInsideBuilding(x0, y, z0 + c, 0f) && map.isInsideBuilding(x0 + c, y, z0 + c, 0f) &&
                map.isInsideBuilding(x0 + c / 2f, y, z0 + c / 2f, 0f)
        }

        /**
         * A line ([p], x/z pairs) with points added where the ground under it bends away from a
         * straight line (by more than [GROUND_TOLERANCE]), so a road laid along it follows the hill.
         */
        private fun onGround(p: FloatArray): FloatArray {
            val out = ArrayList<Float>(p.size * 2)
            fun split(ax: Float, az: Float, bx: Float, bz: Float, depth: Int) {
                val mx = (ax + bx) / 2f
                val mz = (az + bz) / 2f
                if (depth < 10 && hypot(bx - ax, bz - az) > MIN_GROUND_STEP && bends(ax, az, bx, bz)) {
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

        /** Whether the ground between two points isn't a straight slope (checked at the quarters). */
        private fun bends(ax: Float, az: Float, bx: Float, bz: Float): Boolean {
            val t = map.terrain
            val ha = t.heightAt(ax, az)
            val hb = t.heightAt(bx, bz)
            for (k in 1..3) {
                val f = k / 4f
                if (abs(t.heightAt(ax + (bx - ax) * f, az + (bz - az) * f) - (ha + (hb - ha) * f)) > GROUND_TOLERANCE) return true
            }
            return false
        }

        /** A ground triangle split along its longest side until it lies on the ground (see [bends]). */
        private fun draped(o: Floats, ax: Float, az: Float, bx: Float, bz: Float, cx: Float, cz: Float, y: Float, span: Float, depth: Int) {
            val ab = hypot(bx - ax, bz - az)
            val bc = hypot(cx - bx, cz - bz)
            val ca = hypot(ax - cx, az - cz)
            val longest = maxOf(ab, bc, ca)
            // Flat enough: along each side, and from each corner to the middle of the side opposite.
            val fits = !bends(ax, az, bx, bz) && !bends(bx, bz, cx, cz) && !bends(cx, cz, ax, az) &&
                !bends(ax, az, (bx + cx) / 2f, (bz + cz) / 2f)
            if (longest <= MIN_GROUND_STEP || fits || depth > 16) {
                flat(o, ax, y, az, span); flat(o, bx, y, bz, span); flat(o, cx, y, cz, span)
                return
            }
            // Split the longest side in two; both halves keep the winding.
            when (longest) {
                ab -> { val mx = (ax + bx) / 2f; val mz = (az + bz) / 2f
                    draped(o, ax, az, mx, mz, cx, cz, y, span, depth + 1); draped(o, mx, mz, bx, bz, cx, cz, y, span, depth + 1) }
                bc -> { val mx = (bx + cx) / 2f; val mz = (bz + cz) / 2f
                    draped(o, ax, az, bx, bz, mx, mz, y, span, depth + 1); draped(o, ax, az, mx, mz, cx, cz, y, span, depth + 1) }
                else -> { val mx = (cx + ax) / 2f; val mz = (cz + az) / 2f
                    draped(o, ax, az, bx, bz, mx, mz, y, span, depth + 1); draped(o, mx, mz, bx, bz, cx, cz, y, span, depth + 1) }
            }
        }

        /**
         * A street: the carriageway over a curb and a sidewalk (on roads with traffic), then its
         * lane markings. Each layer is a little higher than the one under it, and busier roads are
         * higher than quieter ones, so at junctions the road on top covers the other's sidewalk.
         */
        private fun addRoad(road: CityMap.Road) {
            val surface = when (road.kind) {
                CityMap.ROAD_MAJOR -> Surface.ROAD_MAJOR
                CityMap.ROAD_MEDIUM -> Surface.ROAD_MEDIUM
                CityMap.ROAD_MINOR -> Surface.ROAD_MINOR
                CityMap.ROAD_PEDESTRIAN -> Surface.ROAD_PEDESTRIAN
                CityMap.ROAD_PIER -> Surface.ROAD_PIER
                CityMap.ROAD_TRACK -> Surface.ROAD_TRACK
                // Footpaths: earth and sand in a mountain village, paving slabs in the city.
                else -> if (look == CityLook.VILLAGE) Surface.ROAD_PATH else Surface.SIDEWALK
            }
            val y = roadY(road.kind)
            // On hills, a point every few metres so the surface follows the ground between them.
            val p = if (map.terrain.flat) road.pts else onGround(road.pts)
            val half = road.width / 2f
            val sidewalk = sidewalkWidth(road.kind)
            if (sidewalk > 0f) {
                band(p, Surface.SIDEWALK, half + sidewalk, SIDEWALK_Y)
                band(p, Surface.CURB, half + CURB_WIDTH, CURB_Y)
                // The dark gutter where the road meets the curb.
                band(p, Surface.GUTTER, half + GUTTER_WIDTH, GUTTER_Y)
            }
            band(p, surface, half, y)
            if (road.kind == CityMap.ROAD_MAJOR || road.kind == CityMap.ROAD_MEDIUM) addLamps(road, half)

            val paint = y + 0.012f
            when (road.kind) {
                CityMap.ROAD_MEDIUM -> line(p, 0f, 0.08f, paint, dash = 3f, gap = 6f)
                CityMap.ROAD_MAJOR -> {
                    // A double solid line down the middle, and dashed lanes on wide avenues.
                    line(p, 0.14f, 0.06f, paint)
                    line(p, -0.14f, 0.06f, paint)
                    if (road.width >= 12f) {
                        line(p, road.width / 4f, 0.07f, paint, dash = 3f, gap = 6f)
                        line(p, -road.width / 4f, 0.07f, paint, dash = 3f, gap = 6f)
                    }
                }
            }
        }

        /** A strip [half] wide each side of the line [p], with round joins at its bends and ends. */
        private fun band(p: FloatArray, surface: Surface, half: Float, y: Float) {
            val span = span(surface)
            var i = 0
            while (i + 3 < p.size) {
                val ax = p[i]; val az = p[i + 1]; val bx = p[i + 2]; val bz = p[i + 3]
                val o = out((ax + bx) / 2f, (az + bz) / 2f, surface)
                strip(o, ax, az, bx, bz, half, y, span)
                if (i == 0) disc(o, ax, az, half, y, span) else join(o, p, i, half, y, span)
                i += 2
            }
            disc(out(p[p.size - 2], p[p.size - 1], surface), p[p.size - 2], p[p.size - 1], half, y, span)
        }

        /**
         * Fills the gap between two strips where the line [p] bends at index [i]: nothing when
         * it runs straight on, a wedge each side at a gentle bend, a round join at a sharp one.
         */
        private fun join(o: Floats, p: FloatArray, i: Int, half: Float, y: Float, span: Float) {
            val bx = p[i]; val bz = p[i + 1]
            val d0x = bx - p[i - 2]; val d0z = bz - p[i - 1]
            val d1x = p[i + 2] - bx; val d1z = p[i + 3] - bz
            val l0 = hypot(d0x, d0z); val l1 = hypot(d1x, d1z)
            if (l0 < 1e-3f || l1 < 1e-3f) { disc(o, bx, bz, half, y, span); return }
            val cos = (d0x * d1x + d0z * d1z) / (l0 * l1)
            if (cos > 0.99995f) return
            if (cos < 0.6f) { disc(o, bx, bz, half, y, span); return }
            val n0x = -d0z / l0 * half; val n0z = d0x / l0 * half
            val n1x = -d1z / l1 * half; val n1z = d1x / l1 * half
            for (s in floatArrayOf(1f, -1f)) {
                flat(o, bx, y, bz, span)
                flat(o, bx + n0x * s, y, bz + n0z * s, span)
                flat(o, bx + n1x * s, y, bz + n1z * s, span)
            }
        }

        /** How high each kind of road is laid (see [addRoad]). */
        private fun roadY(kind: Int) = when (kind) {
            CityMap.ROAD_MAJOR -> 0.085f
            CityMap.ROAD_MEDIUM -> 0.08f
            CityMap.ROAD_MINOR -> 0.075f
            CityMap.ROAD_PEDESTRIAN -> 0.068f
            CityMap.ROAD_PIER -> 0.3f
            CityMap.ROAD_TRACK -> 0.065f
            else -> 0.062f
        }

        /**
         * A painted line [offset] metres to the left of the line [p], [half] wide: solid, or
         * [dash] metres on and [gap] off, the pattern carried on round bends. Lines stop short
         * of junctions instead of running across the road they meet.
         */
        private fun line(p: FloatArray, offset: Float, half: Float, y: Float, dash: Float = 0f, gap: Float = 0f) {
            // A solid line is laid as touching pieces, so the bits in a junction can be left out.
            val on = if (dash > 0f) dash else 4f
            val off = if (dash > 0f) gap else 0f
            var carry = if (dash > 0f) 2f else 0f
            var i = 0
            while (i + 3 < p.size) {
                val ax = p[i]; val az = p[i + 1]; val bx = p[i + 2]; val bz = p[i + 3]
                i += 2
                val len = hypot(bx - ax, bz - az)
                if (len < 1e-3f) continue
                val dx = (bx - ax) / len; val dz = (bz - az) / len
                val ox = -dz * offset; val oz = dx * offset
                var t = carry
                while (t < len) {
                    val t1 = minOf(t + on, len)
                    val sx = ax + dx * t + ox; val sz = az + dz * t + oz
                    val ex = ax + dx * t1 + ox; val ez = az + dz * t1 + oz
                    if (!network.nearJunction((sx + ex) / 2f, (sz + ez) / 2f, JUNCTION_CLEAR)) {
                        strip(out(sx, sz, Surface.LANE), sx, sz, ex, ez, half, y, 0f)
                    }
                    t += on + off
                }
                carry = if (dash > 0f) max(0f, t - len) else 0f
            }
        }

        /**
         * Zebra crossings across each road leading into a junction (a little back from it, past
         * the corner's sidewalk), with a stop line before each for the traffic coming in.
         */
        private fun addCrossings() {
            for (j in network.junctions) for (stop in j.stops) {
                val road = map.roads[stop.road]
                if (road.kind > CityMap.ROAD_MINOR || road.width < 5f) continue
                val p = road.pts
                val n = p.size / 2
                val half = road.width / 2f
                val y = roadY(road.kind) + 0.012f
                for (step in intArrayOf(-1, 1)) {
                    val next = stop.vertex + step
                    if (next !in 0 until n) continue
                    // Along the arm, away from the junction.
                    val jx = p[2 * stop.vertex]; val jz = p[2 * stop.vertex + 1]
                    val len = hypot(p[2 * next] - jx, p[2 * next + 1] - jz)
                    val start = j.half + CROSSING_BACK
                    if (len < start + CROSSING_LENGTH + 2f) continue
                    val ax = (p[2 * next] - jx) / len; val az = (p[2 * next + 1] - jz) / len
                    val sx = -az; val sz = ax
                    var o = -half + 0.5f
                    while (o <= half - 0.45f) {
                        val x0 = jx + ax * start + sx * o; val z0 = jz + az * start + sz * o
                        val x1 = x0 + ax * CROSSING_LENGTH; val z1 = z0 + az * CROSSING_LENGTH
                        strip(out(x0, z0, Surface.LANE), x0, z0, x1, z1, 0.25f, y, 0f)
                        o += 1f
                    }
                    if (road.width >= 6f) {
                        // Traffic coming in drives on its right: the side (az, -ax) of the arm.
                        val d = start + CROSSING_LENGTH + 0.9f
                        val cx = jx + ax * d; val cz = jz + az * d
                        val rx = az; val rz = -ax
                        strip(out(cx, cz, Surface.LANE), cx + rx * 0.15f, cz + rz * 0.15f, cx + rx * (half - 0.3f), cz + rz * (half - 0.3f), 0.15f, y, 0f)
                    }
                }
            }
        }

        /** Street lamps along a main road, every [LAMP_SPACING] metres, on alternate sides. */
        private fun addLamps(road: CityMap.Road, half: Float) {
            val p = road.pts
            var carry = LAMP_SPACING / 2f
            var side = 1f
            var i = 0
            while (i + 3 < p.size) {
                val ax = p[i]; val az = p[i + 1]; val bx = p[i + 2]; val bz = p[i + 3]
                i += 2
                val len = hypot(bx - ax, bz - az)
                if (len < 1e-3f) continue
                val dx = (bx - ax) / len; val dz = (bz - az) / len
                var t = carry
                while (t < len) {
                    // On the sidewalk just behind the curb, the arm reaching out over the road.
                    val nx = -dz * side; val nz = dx * side
                    val x = ax + dx * t + nx * (half + 0.6f); val z = az + dz * t + nz * (half + 0.6f)
                    val ground = map.groundAt(x, z)
                    if (!network.nearJunction(x, z, 6f) && !map.isInsideBuilding(x, ground + 1f, z, 0.5f)) lift.by(ground) { lamp(x, z, -nx, -nz) }
                    side = -side
                    t += LAMP_SPACING
                }
                carry = t - len
            }
        }

        /** A lamp post at (x, z), its arm and lamp reaching towards (fx, fz). */
        private fun lamp(x: Float, z: Float, fx: Float, fz: Float) {
            val o = out(x, z, Surface.POLE)
            limb(o, x, 0f, z, x, 0.7f, z, 0.13f, 0.11f, 6)
            limb(o, x, 0f, z, x, LAMP_HEIGHT, z, 0.08f, 0.055f, 6)
            limb(o, x, LAMP_HEIGHT - 0.25f, z, x + fx * 1.7f, LAMP_HEIGHT + 0.05f, z + fz * 1.7f, 0.045f, 0.04f, 4)
            limb(out(x, z, Surface.LAMP), x + fx * 1.35f, LAMP_HEIGHT, z + fz * 1.35f, x + fx * 2f, LAMP_HEIGHT - 0.02f, z + fz * 2f, 0.15f, 0.11f, 6)
        }

        private fun strip(o: Floats, ax: Float, az: Float, bx: Float, bz: Float, half: Float, y: Float, span: Float) {
            val len = hypot(bx - ax, bz - az)
            if (len < 1e-3f) return
            val nx = -(bz - az) / len * half
            val nz = (bx - ax) / len * half
            // Two triangles, wound to face up.
            flat(o, ax + nx, y, az + nz, span)
            flat(o, bx - nx, y, bz - nz, span)
            flat(o, ax - nx, y, az - nz, span)
            flat(o, ax + nx, y, az + nz, span)
            flat(o, bx + nx, y, bz + nz, span)
            flat(o, bx - nx, y, bz - nz, span)
        }

        /** A round join so road segments meet without gaps at bends. */
        private fun disc(o: Floats, cx: Float, cz: Float, r: Float, y: Float, span: Float) {
            val n = 8
            for (k in 0 until n) {
                val a0 = 2 * PI * k / n
                val a1 = 2 * PI * (k + 1) / n
                flat(o, cx, y, cz, span)
                flat(o, cx + (cos(a1) * r).toFloat(), y, cz + (sin(a1) * r).toFloat(), span)
                flat(o, cx + (cos(a0) * r).toFloat(), y, cz + (sin(a0) * r).toFloat(), span)
            }
        }

        /**
         * A rock in the sea, shaped like a real sea stack (see [SeaStack]); the biggest, the big
         * Pigeon Rock, has its arch. Textured in world space: across on the cliffs, flat on top.
         */
        private fun addSeaStack(b: CityMap.Building, index: Int) {
            val stack = SeaStack(b.pts, b.height, arch = b.area > ARCH_ROCK_AREA, viewX = map.spawnX, viewZ = map.spawnZ, seed = index)
            for (t in stack.triangles()) {
                val surface = when (t.part) {
                    SeaStack.Part.CLIFF -> Surface.ROCK
                    SeaStack.Part.WET -> Surface.ROCK_WET
                    SeaStack.Part.SCRUB -> Surface.SCRUB
                }
                val o = out(t.p[0], t.p[2], surface)
                val s = span(surface).takeIf { it > 0f } ?: 4f
                for (k in 0 until 3) {
                    val x = t.p[3 * k]; val y = t.p[3 * k + 1]; val z = t.p[3 * k + 2]
                    val nx = t.n[3 * k]; val ny = t.n[3 * k + 1]; val nz = t.n[3 * k + 2]
                    // Steep faces take the texture up the cliff, gentle ones across the top.
                    val u = if (abs(ny) > 0.7f) (x - o.ox) / s else ((x - o.ox) * abs(nz) + (z - o.oz) * abs(nx)) / s
                    val v = if (abs(ny) > 0.7f) (z - o.oz) / s else y / s
                    o.vertex(x, y, z, nx, ny, nz, u, v)
                }
            }
        }

        private fun addBuilding(b: CityMap.Building, index: Int) {
            if (b.kind == CityMap.BUILDING_ROCK) return addSeaStack(b, index)
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
            // Most ordinary buildings standing on the street have shops along the ground floor.
            val shopChance = when (wall) {
                Surface.WALL_CREAM -> 0.8f
                Surface.WALL_CONCRETE -> 0.7f
                Surface.WALL_SANDSTONE -> 0.55f
                Surface.WALL_WHITE -> 0.45f
                else -> 0f
            }
            val shops = b.kind == CityMap.BUILDING_GENERIC && b.minHeight < 0.5f &&
                b.height >= CityTextures.SHOP_HEIGHT + 3f && rnd.nextFloat() < shopChance
            var wallBottom = b.minHeight
            if (shops) {
                walls(out(cx, cz, Surface.SHOPFRONT), b.pts, b.minHeight, CityTextures.SHOP_HEIGHT, CityTextures.SHOP_SPAN, CityTextures.SHOP_HEIGHT)
                wallBottom = CityTextures.SHOP_HEIGHT
            }
            val facade = if (wall.texture >= 0) CityTextures.FACADE_SPAN else 0f
            walls(out(cx, cz, wall), b.pts, wallBottom, b.height, facade, facade)
            if (wall != Surface.WALL_GLASS && wall != Surface.ROCK && b.area > 15f &&
                b.height - b.minHeight > 3f && b !in withLadder
            ) {
                cornice(out(cx, cz, Surface.CORNICE), b.pts, b.height)
            }
            val roof = when (wall) { Surface.WALL_GLASS -> Surface.ROOF_TOWER; Surface.ROCK -> Surface.ROCK; else -> Surface.ROOF }
            flatPolygon(out(cx, cz, roof), b.pts, b.height, span(roof))

            when (b.kind) {
                CityMap.BUILDING_MOSQUE -> addMosque(b)
                CityMap.BUILDING_CHURCH -> addBellTower(b)
            }
        }

        /**
         * Extruded walls from [y0] to [y1], textured every [spanU] metres across and [spanV] up
         * (0 = untextured). The texture is two windows wide, so each wall is fitted with a whole
         * number of windows (stretched a little) rather than cutting one at a corner; vertically
         * whole repeats start at [y0], so floors line up from the bottom.
         */
        private fun walls(o: Floats, ring: FloatArray, y0: Float, y1: Float, spanU: Float = 0f, spanV: Float = 0f) {
            val n = ring.size / 2
            val textured = spanU > 0f
            // v grows down the texture: a whole number at y0.
            val vBottom = if (textured) ceil((y1 - y0) / spanV) else 0.02f
            val vTop = if (textured) vBottom - (y1 - y0) / spanV else 0.02f
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ax = ring[2 * i]; val az = ring[2 * i + 1]
                val bx = ring[2 * j]; val bz = ring[2 * j + 1]
                val len = hypot(bx - ax, bz - az)
                if (len < 1e-3f) continue
                // Counter-clockwise ring: the outside is to the right of a → b.
                val nx = (bz - az) / len
                val nz = -(bx - ax) / len
                // Seen from outside, b is on the left (u = 0) and a on the right.
                val ub = if (textured) 0f else 0.02f
                val ua = if (!textured) 0.02f else {
                    val cells = (len / (spanU / 2f)).roundToInt()
                    if (cells == 0) len / spanU else cells / 2f
                }
                o.vertex(bx, y0, bz, nx, 0f, nz, ub, vBottom)
                o.vertex(ax, y0, az, nx, 0f, nz, ua, vBottom)
                o.vertex(ax, y1, az, nx, 0f, nz, ua, vTop)
                o.vertex(bx, y0, bz, nx, 0f, nz, ub, vBottom)
                o.vertex(ax, y1, az, nx, 0f, nz, ua, vTop)
                o.vertex(bx, y1, bz, nx, 0f, nz, ub, vTop)
            }
        }

        /**
         * A moulding round the top of the walls: a band [CORNICE_DEPTH] proud of them, shaded
         * underneath, level with the roof on top.
         */
        private fun cornice(o: Floats, ring: FloatArray, top: Float) {
            val outer = pushedOut(ring, CORNICE_DEPTH)
            val bottom = top - CORNICE_HEIGHT
            walls(o, outer, bottom, top)
            val n = ring.size / 2
            val u = 0.02f
            for (i in 0 until n) {
                val j = (i + 1) % n
                for ((y, ny) in arrayOf(bottom to -1f, top to 1f)) {
                    o.vertex(ring[2 * i], y, ring[2 * i + 1], 0f, ny, 0f, u, u)
                    o.vertex(outer[2 * i], y, outer[2 * i + 1], 0f, ny, 0f, u, u)
                    o.vertex(outer[2 * j], y, outer[2 * j + 1], 0f, ny, 0f, u, u)
                    o.vertex(ring[2 * i], y, ring[2 * i + 1], 0f, ny, 0f, u, u)
                    o.vertex(outer[2 * j], y, outer[2 * j + 1], 0f, ny, 0f, u, u)
                    o.vertex(ring[2 * j], y, ring[2 * j + 1], 0f, ny, 0f, u, u)
                }
            }
        }

        /** [ring] (counter-clockwise) moved [d] outwards, its corners mitred (at most twice as far). */
        private fun pushedOut(ring: FloatArray, d: Float): FloatArray {
            val n = ring.size / 2
            fun normal(a: Int, b: Int): Pair<Float, Float> {
                val dx = ring[2 * b] - ring[2 * a]; val dz = ring[2 * b + 1] - ring[2 * a + 1]
                val len = hypot(dx, dz)
                return if (len < 1e-4f) 0f to 0f else dz / len to -dx / len
            }
            val out = FloatArray(ring.size)
            for (i in 0 until n) {
                val (n0x, n0z) = normal((i + n - 1) % n, i)
                val (n1x, n1z) = normal(i, (i + 1) % n)
                var mx = n0x + n1x; var mz = n0z + n1z
                val ml = hypot(mx, mz)
                if (ml < 1e-4f) { mx = n1x; mz = n1z } else { mx /= ml; mz /= ml }
                val k = d / max(mx * n1x + mz * n1z, 0.5f)
                out[2 * i] = ring[2 * i] + mx * k
                out[2 * i + 1] = ring[2 * i + 1] + mz * k
            }
            return out
        }

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

        /**
         * A date palm: a ringed trunk curving gently to one side, a dark crown, and a dozen
         * fronds arching out and drooping, the young ones near upright and the old ones hanging.
         */
        private fun addPalm(t: CityMap.Tree) {
            val s = t.size
            val rnd = Random((t.x * 31 + t.z * 17).toInt())
            val h = (6.5f + rnd.nextFloat() * 2.5f) * s
            val lean = (0.3f + rnd.nextFloat() * 0.9f) * s
            val leanAngle = rnd.nextFloat() * 2f * PI.toFloat()
            val ldx = cos(leanAngle); val ldz = sin(leanAngle)
            val trunk = out(t.x, t.z, Surface.PALM_TRUNK)
            var px = t.x; var py = 0f; var pz = t.z
            var v = 0f
            for (k in 1..PALM_SEGMENTS) {
                val f = k / PALM_SEGMENTS.toFloat()
                val nx = t.x + ldx * lean * f * f; val ny = h * f; val nz = t.z + ldz * lean * f * f
                val r0 = (0.25f - 0.08f * (k - 1) / PALM_SEGMENTS) * s
                val r1 = (0.25f - 0.08f * f) * s
                v = limb(trunk, px, py, pz, nx, ny, nz, r0, r1, 6, uRepeat = 2f, vSpan = 2f, v0 = v)
                px = nx; py = ny; pz = nz
            }
            // The crown: the bulge of old frond bases the fronds spring from.
            limb(out(px, pz, Surface.PALM_CROWN), px, py - 0.5f * s, pz, px, py + 0.25f * s, pz, 0.3f * s, 0.12f * s, 5)
            val o = out(px, pz, Surface.PALM_LEAVES)
            val fronds = 9 + rnd.nextInt(3)
            val twist = rnd.nextFloat() * 2f * PI.toFloat()
            for (k in 0 until fronds) {
                val a = twist + 2f * PI.toFloat() * (k + rnd.nextFloat() * 0.4f) / fronds
                val up = 0.85f - 1.15f * rnd.nextFloat()
                frond(o, px, py, pz, a, up, (2.8f + rnd.nextFloat() * 1.3f) * s, 1.15f * s)
            }
        }

        /**
         * One frond from (x, y, z) towards [angle], leaving at [elevation] radians above level and
         * drooping more along its [length]: a strip of panels folded along the rib, so the
         * leaflets (the cut-out [CityTextures.FROND] texture) hang down to either side.
         */
        private fun frond(o: Floats, x: Float, y: Float, z: Float, angle: Float, elevation: Float, length: Float, width: Float) {
            val dx = cos(angle); val dz = sin(angle)
            val sx = -dz; val sz = dx
            val step = length / FROND_PANELS
            val half = width / 2f
            val fold = half * 0.35f
            var cx = x; var cy = y; var cz = z
            var e = elevation
            for (k in 0 until FROND_PANELS) {
                val ce = cos(e); val se = sin(e)
                val nx = cx + dx * ce * step; val ny = cy + se * step; val nz = cz + dz * ce * step
                val u0 = k / FROND_PANELS.toFloat(); val u1 = (k + 1) / FROND_PANELS.toFloat()
                for (side in intArrayOf(-1, 1)) {
                    val v = if (side < 0) 0f else 1f
                    val ax = cx + sx * half * side; val az = cz + sz * half * side
                    val bx = nx + sx * half * side; val bz = nz + sz * half * side
                    upTri(o, cx, cy, cz, u0, 0.5f, ax, cy - fold, az, u0, v, bx, ny - fold, bz, u1, v)
                    upTri(o, cx, cy, cz, u0, 0.5f, bx, ny - fold, bz, u1, v, nx, ny, nz, u1, 0.5f)
                }
                cx = nx; cy = ny; cz = nz
                e -= 0.3f
            }
        }

        /** A triangle with its normal worked out from its corners, turned to face upwards. */
        private fun upTri(
            o: Floats,
            ax: Float, ay: Float, az: Float, au: Float, av: Float,
            bx: Float, by: Float, bz: Float, bu: Float, bv: Float,
            cx: Float, cy: Float, cz: Float, cu: Float, cv: Float,
        ) {
            val ux = bx - ax; val uy = by - ay; val uz = bz - az
            val wx = cx - ax; val wy = cy - ay; val wz = cz - az
            var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l < 1e-6f) return
            nx /= l; ny /= l; nz /= l
            if (ny >= 0f) {
                o.vertex(ax, ay, az, nx, ny, nz, au, av)
                o.vertex(bx, by, bz, nx, ny, nz, bu, bv)
                o.vertex(cx, cy, cz, nx, ny, nz, cu, cv)
            } else {
                // Wound the other way round, so the front face is the upper one.
                o.vertex(ax, ay, az, -nx, -ny, -nz, au, av)
                o.vertex(cx, cy, cz, -nx, -ny, -nz, cu, cv)
                o.vertex(bx, by, bz, -nx, -ny, -nz, bu, bv)
            }
        }

        /**
         * A broadleaf street tree (ficus, plane): a bark trunk forking into a couple of branches,
         * under a lumpy canopy of overlapping clumps in one of three greens.
         */
        private fun addLeafyTree(t: CityMap.Tree) {
            val s = t.size
            val rnd = Random((t.x * 13 + t.z * 29).toInt())
            val trunkTop = (2.2f + rnd.nextFloat() * 0.8f) * s
            val bark = out(t.x, t.z, Surface.TRUNK)
            limb(bark, t.x, 0f, t.z, t.x, trunkTop, t.z, 0.2f * s, 0.14f * s, 6, uRepeat = 1f, vSpan = 2f)
            val green = when (rnd.nextInt(3)) { 0 -> Surface.LEAVES; 1 -> Surface.LEAVES_DARK; else -> Surface.LEAVES_OLIVE }
            val o = out(t.x, t.z, green)
            val r = (1.7f + rnd.nextFloat() * 0.5f) * s
            val cy = trunkTop + r * 0.75f
            clump(o, t.x, cy, t.z, r, 4, 7, rnd.nextInt())
            val turn = rnd.nextFloat() * 2f * PI.toFloat()
            for (k in 0 until 3) {
                val a = turn + 2f * PI.toFloat() * k / 3
                val d = r * (0.5f + rnd.nextFloat() * 0.2f)
                val bx = t.x + cos(a) * d; val bz = t.z + sin(a) * d
                val by = cy + (rnd.nextFloat() - 0.35f) * r * 0.7f
                clump(o, bx, by, bz, r * (0.55f + rnd.nextFloat() * 0.2f), 3, 6, rnd.nextInt())
                if (k < 2) limb(bark, t.x, trunkTop * 0.9f, t.z, bx, by - r * 0.3f, bz, 0.1f * s, 0.05f * s, 5, uRepeat = 1f, vSpan = 2f)
            }
        }

        /**
         * A clump of leaves: a slightly squashed sphere with its surface pushed in and out at
         * random (the same at shared points, so it stays closed), leaf-textured, smooth-shaded.
         */
        private fun clump(o: Floats, cx: Float, cy: Float, cz: Float, r: Float, stacks: Int, slices: Int, seed: Int) {
            fun bump(i: Int, j: Int): Float {
                if (i == 0 || i == stacks) return 1f
                val h = ((i * 73856093) xor ((j % slices) * 19349663) xor seed) and 0xFFFF
                return 0.82f + 0.36f * h / 65535f
            }
            fun v(i: Int, j: Int) {
                val lat = -PI / 2 + PI * i / stacks
                val lon = 2 * PI * j / slices
                val x = (cos(lat) * cos(lon)).toFloat(); val y = sin(lat).toFloat(); val z = (cos(lat) * sin(lon)).toFloat()
                val k = r * bump(i, j)
                o.vertex(cx + x * k, cy + y * k * 0.82f, cz + z * k, x, y, z, 2f * j / slices, 1.5f * i / stacks)
            }
            for (i in 0 until stacks) for (j in 0 until slices) {
                v(i, j); v(i + 1, j); v(i + 1, j + 1)
                v(i, j); v(i + 1, j + 1); v(i, j + 1)
            }
        }

        /**
         * A tapering round branch (or trunk, or pole) from (ax, ay, az) to (bx, by, bz). Textured
         * [uRepeat] times round and once every [vSpan] metres along, from [v0]; returns the v at
         * its far end, so the next piece can carry on from there. Untextured when [vSpan] is 0.
         */
        private fun limb(
            o: Floats, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
            r0: Float, r1: Float, sides: Int, uRepeat: Float = 1f, vSpan: Float = 0f, v0: Float = 0f,
        ): Float {
            var wx = bx - ax; var wy = by - ay; var wz = bz - az
            val len = sqrt(wx * wx + wy * wy + wz * wz)
            if (len < 1e-4f) return v0
            wx /= len; wy /= len; wz /= len
            // Two directions across the limb, at right angles to it and each other.
            val helperY = if (abs(wy) < 0.9f) 1f else 0f
            val helperX = 1f - helperY
            var e1x = wy * 0f - wz * helperY; var e1y = wz * helperX - wx * 0f; var e1z = wx * helperY - wy * helperX
            val el = sqrt(e1x * e1x + e1y * e1y + e1z * e1z)
            e1x /= el; e1y /= el; e1z /= el
            val e2x = wy * e1z - wz * e1y; val e2y = wz * e1x - wx * e1z; val e2z = wx * e1y - wy * e1x
            val textured = vSpan > 0f
            val va = if (textured) v0 else 0.02f
            val vb = if (textured) v0 + len / vSpan else 0.02f
            fun point(k: Int, end: Boolean) {
                val a = 2f * PI.toFloat() * k / sides
                val c = cos(a); val s = sin(a)
                val nx = e1x * c + e2x * s; val ny = e1y * c + e2y * s; val nz = e1z * c + e2z * s
                val u = if (textured) uRepeat * k / sides else 0.02f
                if (end) o.vertex(bx + nx * r1, by + ny * r1, bz + nz * r1, nx, ny, nz, u, vb)
                else o.vertex(ax + nx * r0, ay + ny * r0, az + nz * r0, nx, ny, nz, u, va)
            }
            for (k in 0 until sides) {
                point(k, false); point(k + 1, false); point(k + 1, true)
                point(k, false); point(k + 1, true); point(k, true)
            }
            return if (textured) vb else v0
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
