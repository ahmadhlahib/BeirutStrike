package com.example.beirutrun.city

import com.example.beirutrun.city.CityMap.Companion.triangulate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
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
    /**
     * Laid on the ground (areas 1, streets 2, their paint 3): drawn pulled towards the camera, so
     * on hills the ground never shows through where it bulges a few centimetres (0: not laid on it).
     */
    val layer: Int = 0,
) {
    SEA(0xFF2F6E95.toInt(), shine = 0.5f),
    PARKING(0xFF96938E.toInt(), CityTextures.ASPHALT, layer = 1), PLAZA(0xFFE8DDC8.toInt(), CityTextures.PAVING, layer = 1),
    PARK(0xFF78AE5F.toInt(), CityTextures.GRASS, layer = 1), PITCH(0xFF56A44E.toInt(), CityTextures.GRASS, layer = 1),
    WATER(0xFF2F6E95.toInt(), shine = 0.5f), SAND(0xFFEFDDA8.toInt(), CityTextures.SAND, layer = 1),
    PIER(0xFFADABA6.toInt(), CityTextures.SLABS), CONSTRUCTION(0xFFB39878.toInt(), CityTextures.SAND, layer = 1),
    ROAD_PATH(0xFFC8BA9F.toInt(), CityTextures.SAND, layer = 2), ROAD_TRACK(0xFFC9A86A.toInt(), CityTextures.SAND, layer = 2),
    // Hillsides (maps with hills): dry grass and scrub, and bare rock and earth where it's steep.
    HILLSIDE(0xFFB9B98A.toInt(), CityTextures.GRASS), HILL_ROCK(0xFFB4A288.toInt(), CityTextures.SAND),
    // The mountain's sandy red-brown earth on the steeper slopes, between the dry grass and the rock.
    HILL_EARTH(0xFFBF9F78.toInt(), CityTextures.SAND),
    // The city's bare ground on hills (as on flat maps): pale stone and dust, not grass.
    CITY_GROUND(0xFFDAD3C4.toInt(), CityTextures.GROUND),
    ROAD_PEDESTRIAN(0xFFDDCFB3.toInt(), CityTextures.PAVING, layer = 2),
    ROAD_MINOR(0xFF56575C.toInt(), CityTextures.ASPHALT, layer = 2), ROAD_MEDIUM(0xFF4C4D52.toInt(), CityTextures.ASPHALT, layer = 2),
    ROAD_MAJOR(0xFF434448.toInt(), CityTextures.ASPHALT, layer = 2), ROAD_PIER(0xFFA4A29E.toInt(), CityTextures.SLABS),
    SIDEWALK(0xFFD6D1C7.toInt(), CityTextures.SLABS, layer = 2), CURB(0xFFBDB9B0.toInt(), layer = 2),
    LANE(0xFFE9E4D0.toInt(), lit = false, layer = 3),
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
    SCRUB(0xFF5E7340.toInt(), CityTextures.GRASS), ROCK_STAIN(0xFF6A665A.toInt(), CityTextures.SAND),
    // Local identity (see Landmarks and the builders below): retaining walls, red tile roofs,
    // shop signs, the Corniche's blue railing, fishing boats, and the landmarks' materials.
    RETAINING_WALL(0xFFFFFFFF.toInt(), CityTextures.STONE_WALL),
    ROOF_TILES(0xFFFFFFFF.toInt(), CityTextures.TILES),
    SIGN(0xFFFFFFFF.toInt(), CityTextures.SIGNS),
    RAILING(0xFF3D8FC7.toInt(), shine = 0.2f),
    BOAT_HULL(0xFFF1F0EA.toInt()), BOAT_BLUE(0xFF1F5FA8.toInt()), BOAT_RED(0xFFB03A2E.toInt()), BOAT_WOOD(0xFF8A6A48.toInt()),
    BRONZE(0xFF6B5636.toInt(), shine = 0.25f),
    STRIPE_BLACK(0xFF262626.toInt()), STRIPE_WHITE(0xFFF2F1EC.toInt()),
    BARE_CONCRETE(0xFFA8A49A.toInt(), CityTextures.CONCRETE),
    RAW_CONCRETE(0xFFB0ADA5.toInt()), SHELL_DARK(0xFF2B2926.toInt()),
    MARBLE(0xFFDCD5C6.toInt()), CLOCK_FACE(0xFFF4F0E2.toInt(), lit = false), CLOCK_HANDS(0xFF1E1E1E.toInt()),
    DOME_LIGHT(0xFF3F86CC.toInt(), shine = 0.35f),
    // The landmarks in their real materials (from photos; see each builder).
    OCHRE_STONE(0xFFD8B27A.toInt()), DOME_PALE(0xFF6AAED6.toInt(), shine = 0.35f), ARCH_SHADOW(0xFF5E4630.toInt()),
    WINDOW_DARK(0xFF1E2A30.toInt()), LIMESTONE_PALE(0xFFCDB894.toInt()), STONE_TRIM(0xFFA89470.toInt()),
    TRIM_WHITE(0xFFECE4D4.toInt()), ROSE_SANDSTONE(0xFFC9A27A.toInt()),
    BRONZE_DARK(0xFF3B3A35.toInt(), shine = 0.2f), RENDER_WHITE(0xFFE9E7E1.toInt()), STEP_GREY(0xFF6E6A62.toInt()),
    ROCK_PALE(0xFFCFC6AE.toInt()), FLAME(0xFFF2A33A.toInt(), lit = false),
    GRANITE(0xFF8F8B86.toInt(), shine = 0.15f), MARBLE_WHITE(0xFFE6E2D8.toInt()), LIMESTONE_BLOCK(0xFFCFC4AD.toInt()),
    EGG_CONCRETE(0xFF8A8478.toInt()), EGG_FACE(0xFFB5AB98.toInt()), RUST_PANEL(0xFFB8784A.toInt()), BLOCK_GREY(0xFFA8A294.toInt()),
    VOID_DARK(0xFF1E1E1E.toInt()),
    PLASTER_DOME(0xFFECE6D8.toInt()), LEAD_DOME(0xFF9A9EA3.toInt(), shine = 0.2f), CAP_GREY(0xFF8C9096.toInt()),
    MOSQUE_STONE(0xFFD6C7A6.toInt()), CHURCH_STONE(0xFFD9C9A6.toInt()), BELFRY_DARK(0xFF2A2622.toInt()),
    IRON_DARK(0xFF333333.toInt()),
    MURR_CONCRETE(0xFF8E7E6A.toInt()), HOTEL_WHITE(0xFFDAD7D0.toInt()), HOTEL_DARK(0xFF2E2C2A.toInt()),
    HOTEL_TILE(0xFFC8A497.toInt()), NAVY_TILE(0xFF2B3550.toInt()), AUB_STONE(0xFFD2B47A.toInt()),
    PINE(0xFF2E4F2E.toInt(), CityTextures.LEAF),
    TRUNK(0xFF8E6E52.toInt(), CityTextures.BARK), PALM_TRUNK(0xFFAA9474.toInt(), CityTextures.PALM_BARK),
    LEAVES(0xFF74B654.toInt(), CityTextures.LEAF), LEAVES_DARK(0xFF559A4A.toInt(), CityTextures.LEAF),
    LEAVES_OLIVE(0xFF98AE58.toInt(), CityTextures.LEAF),
    PALM_LEAVES(0xFF6EA43E.toInt(), CityTextures.FROND, cutout = true), PALM_CROWN(0xFF6E5A3E.toInt()),
    POLE(0xFF4A4D50.toInt()), LAMP(0xFFF4F0DE.toInt(), lit = false), GUTTER(0xFF3A3A38.toInt(), layer = 2),
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
        /** City ground steeper than this (rise over run) is drawn as a stone retaining wall. */
        private const val CITY_WALL_SLOPE = 0.45f
        /** Hillside steeper than these (rise over run) is bare rock, or (in a village) sandy earth. */
        private const val HILL_ROCK_SLOPE = 0.75f
        private const val HILL_EARTH_SLOPE = 0.38f
        /** The Corniche railing: one post every this many metres. And at most this many boats on a map. */
        private const val RAIL_STEP = 3f
        private const val MAX_BOATS = 80
        /** A divided road: the planted strip left between its halves at the least, and the narrowest a half is drawn. */
        private const val MEDIAN = 3f
        private const val MIN_HALF = 3.2f
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

        /** A road being laid: level across, at its middle's height (see [RoadProfile]). */
        var road: RoadProfile? = null

        /** The road segment being drawn (its index along the road's points), or -1: any, the nearest. */
        var segment = -1

        fun at(x: Float, z: Float): Float = fixed ?: road?.let { if (segment >= 0) it.atSegment(x, z, segment) else it.at(x, z) } ?: terrain.heightAt(x, z)

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
        private val lift = Lift(map.drawnGround)

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
            // Each road is laid level across, at its middle's height (see RoadProfile).
            for (road in map.roads) { lift.road = profileOf(road); addRoad(road) }
            lift.road = null
            addCrossings()
            lift.road = null
            // By the sea: the Corniche's blue railing, and fishing boats at the piers.
            if (map.sea.isNotEmpty()) { addCornicheRailing(); addBoats() }
            // Buildings stand on the lowest ground under them, trees a little into it, upright.
            map.buildings.forEachIndexed { i, b -> lift.by(b.base) { addBuilding(b, i) } }
            for (t in map.trees) lift.by(map.groundAt(t.x, t.z) - 0.15f) {
                when (t.kind) {
                    CityMap.TREE_PALM -> addPalm(t)
                    CityMap.TREE_PINE -> addPine(t)
                    CityMap.TREE_OLIVE -> addOlive(t)
                    else -> addLeafyTree(t)
                }
            }
            if (!map.terrain.flat) addHillsides()
        }

        /**
         * The hills' ground: the height grid as triangles, lit by their slope; gentle slopes are
         * dry grass, steep ones bare rock and earth. Split into tiles like everything else.
         */
        private fun addHillsides() {
            // The ground as drawn and stood on: the hillside shaped round the roads (see RoadLevels).
            val t = map.drawnGround
            val c = t.cell
            fun height(cc: Int, rr: Int): Float {
                val ci = cc.coerceIn(0, t.cols - 1); val ri = rr.coerceIn(0, t.rows - 1)
                return t.at(ci, ri)
            }
            lift.by(0f) {
                for (r in 0 until t.rows - 1) for (k in 0 until t.cols - 1) {
                    val x0 = t.x0 + k * c
                    val z0 = t.z0 + r * c
                    val h00 = t.at(k, r); val h10 = t.at(k + 1, r); val h01 = t.at(k, r + 1); val h11 = t.at(k + 1, r + 1)
                    // Nobody sees the ground under the sea, or inside a building that stands on it.
                    // (Only by the sea is 0 sea level: inland, 0 is the start's height and the valleys go below it.)
                    if (map.sea.isNotEmpty() && maxOf(h00, h10, h01, h11) <= 0f) continue
                    if (coveredByBuilding(x0, z0, c)) continue
                    // Each of the cell's two triangles is chosen on its own (by its own slope), so a
                    // flat triangle beside a steep one never takes a wall's or rock's texture.
                    triangle(t, intArrayOf(k, r, k, r + 1, k + 1, r), ::height)
                    triangle(t, intArrayOf(k + 1, r, k, r + 1, k + 1, r + 1), ::height)
                }
            }
        }

        /**
         * One triangle of the hillside (grid corners [g]: column, row × 3, wound to face up), its
         * surface by how steep it is: in a village dry grass, sandy earth and rock where steep; in
         * the city pale stone ground, and a sandstone retaining wall where it steps steeply.
         */
        private fun triangle(t: Terrain, g: IntArray, height: (Int, Int) -> Float) {
            val c = t.cell
            val xs = FloatArray(3) { t.x0 + g[2 * it] * c }
            val zs = FloatArray(3) { t.z0 + g[2 * it + 1] * c }
            val hs = FloatArray(3) { t.at(g[2 * it], g[2 * it + 1]) }
            // The triangle's own slope (rise over run), from its plane.
            val e1x = xs[1] - xs[0]; val e1y = hs[1] - hs[0]; val e1z = zs[1] - zs[0]
            val e2x = xs[2] - xs[0]; val e2y = hs[2] - hs[0]; val e2z = zs[2] - zs[0]
            val fx = e1y * e2z - e1z * e2y; val fy = e1z * e2x - e1x * e2z; val fz = e1x * e2y - e1y * e2x
            val steep = sqrt(fx * fx + fz * fz) / abs(fy).coerceAtLeast(1e-6f)
            val surface = when {
                look != CityLook.VILLAGE && steep > CITY_WALL_SLOPE -> Surface.RETAINING_WALL
                steep > HILL_ROCK_SLOPE -> Surface.HILL_ROCK
                look == CityLook.VILLAGE && steep > HILL_EARTH_SLOPE -> Surface.HILL_EARTH
                look == CityLook.VILLAGE -> Surface.HILLSIDE
                else -> Surface.CITY_GROUND
            }
            val o = out((xs[0] + xs[1] + xs[2]) / 3f, (zs[0] + zs[1] + zs[2]) / 3f, surface)
            val s = span(surface)
            // A wall's stones stand upright: along the wall one way (across its slope), up it the other.
            val wall = surface == Surface.RETAINING_WALL
            val al = sqrt(fx * fx + fz * fz).coerceAtLeast(1e-6f)
            val ax = -fz / al; val az = fx / al
            for (k in 0 until 3) {
                val cc = g[2 * k]; val rr = g[2 * k + 1]
                val x = xs[k]; val z = zs[k]
                // The slope's normal, from the heights either side (smooth across the hillside).
                val nx = t.at(cc - 1, rr) - t.at(cc + 1, rr)
                val nz = t.at(cc, rr - 1) - t.at(cc, rr + 1)
                val ny = 2f * c
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                val y = height(cc, rr)
                if (wall) o.vertex(x, y, z, nx / len, ny / len, nz / len, ((x - o.ox) * ax + (z - o.oz) * az) / s, -y / s)
                else o.vertex(x, y, z, nx / len, ny / len, nz / len, (x - o.ox) / s, (z - o.oz) / s)
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

        /** Each road's points laid over the hills, and its height along its middle (maps with hills; see RoadLevels). */
        private fun laid(road: CityMap.Road) = map.roadLevels?.laid(road) ?: road.pts

        private fun profileOf(road: CityMap.Road): RoadProfile? = map.roadLevels?.profile(road)

        /** Whether the ground cell at (x0, z0), [c] metres square, is all inside one building's solid ground floor. */
        private fun coveredByBuilding(x0: Float, z0: Float, c: Float): Boolean {
            val y = map.groundAt(x0 + c / 2f, z0 + c / 2f) + 0.5f
            return map.isInsideBuilding(x0, y, z0, 0f) && map.isInsideBuilding(x0 + c, y, z0, 0f) &&
                map.isInsideBuilding(x0, y, z0 + c, 0f) && map.isInsideBuilding(x0 + c, y, z0 + c, 0f) &&
                map.isInsideBuilding(x0 + c / 2f, y, z0 + c / 2f, 0f)
        }

        /** A ground triangle split along its longest side until it lies on the ground (see [bends]). */
        private fun draped(o: Floats, ax: Float, az: Float, bx: Float, bz: Float, cx: Float, cz: Float, y: Float, span: Float, depth: Int) {
            val ab = hypot(bx - ax, bz - az)
            val bc = hypot(cx - bx, cz - bz)
            val ca = hypot(ax - cx, az - cz)
            val longest = maxOf(ab, bc, ca)
            // Flat enough: along each side, and from each corner to the middle of the side opposite.
            val t = map.drawnGround
            val fits = !RoadLevels.bends(ax, az, bx, bz, t) && !RoadLevels.bends(bx, bz, cx, cz, t) && !RoadLevels.bends(cx, cz, ax, az, t) &&
                !RoadLevels.bends(ax, az, (bx + cx) / 2f, (bz + cz) / 2f, t)
            if (longest <= RoadLevels.MIN_GROUND_STEP || fits || depth > 16) {
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
            val p = laid(road)
            val half = drawnHalf(road)
            val width = half * 2f
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
                    if (width >= 12f) {
                        line(p, width / 4f, 0.07f, paint, dash = 3f, gap = 6f)
                        line(p, -width / 4f, 0.07f, paint, dash = 3f, gap = 6f)
                    }
                }
            }
        }

        /**
         * How wide each side of [road]'s middle it's drawn: half its width; but the two halves of a
         * divided road are drawn narrower where they're close, so a planted strip at least [MEDIAN]
         * wide runs between them rather than their asphalt overlapping.
         */
        private fun drawnHalf(road: CityMap.Road): Float {
            val gap = map.roadLevels?.partnerGap(road) ?: return road.width / 2f
            return minOf(road.width / 2f, (gap - MEDIAN) / 2f).coerceAtLeast(MIN_HALF)
        }

        /** A strip [half] wide each side of the line [p], with round joins at its bends and ends. */
        private fun band(p: FloatArray, surface: Surface, half: Float, y: Float) {
            val span = span(surface)
            var i = 0
            while (i + 3 < p.size) {
                val ax = p[i]; val az = p[i + 1]; val bx = p[i + 2]; val bz = p[i + 3]
                // This piece takes its heights from its own stretch of road (not another arm of a hairpin).
                lift.segment = i / 2
                val o = out((ax + bx) / 2f, (az + bz) / 2f, surface)
                strip(o, ax, az, bx, bz, half, y, span)
                if (i == 0) disc(o, ax, az, half, y, span) else join(o, p, i, half, y, span)
                i += 2
            }
            lift.segment = p.size / 2 - 2
            disc(out(p[p.size - 2], p[p.size - 1], surface), p[p.size - 2], p[p.size - 1], half, y, span)
            lift.segment = -1
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
                lift.segment = i / 2
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
            lift.segment = -1
        }

        /**
         * Zebra crossings across each road leading into a junction (a little back from it, past
         * the corner's sidewalk), with a stop line before each for the traffic coming in.
         */
        private fun addCrossings() {
            for (j in network.junctions) for (stop in j.stops) {
                val road = map.roads[stop.road]
                if (road.kind > CityMap.ROAD_MINOR || road.width < 5f) continue
                // Painted on the road, so level across like it.
                lift.road = profileOf(road)
                val p = road.pts
                val n = p.size / 2
                val half = drawnHalf(road)
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
                    SeaStack.Part.STAIN -> Surface.ROCK_STAIN
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

        // ---- The seafront: the Corniche's railing, and boats at the piers ------------------------

        /** Road segments by 25 m square, to find whether a street runs near a point. */
        private val roadCells: HashMap<Long, MutableList<Int>> by lazy {
            val cells = HashMap<Long, MutableList<Int>>()
            map.roads.forEachIndexed { index, r ->
                if (r.kind == CityMap.ROAD_PIER) return@forEachIndexed
                val p = r.pts
                for (i in 0 until p.size / 2) {
                    val key = cellKey(p[2 * i], p[2 * i + 1])
                    val list = cells.getOrPut(key) { ArrayList(2) }
                    if (list.lastOrNull() != index) list += index
                }
            }
            cells
        }

        private fun cellKey(x: Float, z: Float) = (floor(x / 25f).toLong() shl 32) xor (floor(z / 25f).toLong() and 0xffffffffL)

        /** Whether a street (not a footpath) passes within [d] metres of (x, z). */
        private fun streetNear(x: Float, z: Float, d: Float): Boolean {
            val cx = floor(x / 25f).toInt(); val cz = floor(z / 25f).toInt()
            for (dx in -1..1) for (dz in -1..1) {
                val list = roadCells[((cx + dx).toLong() shl 32) xor ((cz + dz).toLong() and 0xffffffffL)] ?: continue
                for (index in list) {
                    val r = map.roads[index]
                    if (r.kind == CityMap.ROAD_PATH || r.kind == CityMap.ROAD_TRACK) continue
                    val p = r.pts
                    for (i in 0 until p.size / 2 - 1) {
                        if (CityMap.segmentDistance(x, z, p[2 * i], p[2 * i + 1], p[2 * i + 2], p[2 * i + 3]) < d) return true
                    }
                }
            }
            return false
        }

        /**
         * The Corniche's railing along the sea wall wherever a street runs beside the sea: posts
         * and two rails in its old blue, following the ground (not along a beach).
         */
        private fun addCornicheRailing() {
            val beaches = map.areas.filter { it.kind == CityMap.AREA_SAND }
            for (s in map.sea) {
                val n = s.size / 2
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val ax = s[2 * i]; val az = s[2 * i + 1]; val bx = s[2 * j]; val bz = s[2 * j + 1]
                    val len = hypot(bx - ax, bz - az)
                    if (len < 0.5f) continue
                    // Not the sea's edge along the map's edge.
                    val mx = (ax + bx) / 2f; val mz = (az + bz) / 2f
                    if (mx < map.minX + 3f || mx > map.maxX - 3f || mz < map.minZ + 3f || mz > map.maxZ - 3f) continue
                    // Which side is the land.
                    var nx = -(bz - az) / len; var nz = (bx - ax) / len
                    if (CityMap.inside(s, mx + nx * 1.5f, mz + nz * 1.5f)) { nx = -nx; nz = -nz }
                    if (!streetNear(mx + nx * 8f, mz + nz * 8f, 25f)) continue
                    if (beaches.any { CityMap.inside(it.pts, mx + nx * 2f, mz + nz * 2f) }) continue
                    val pieces = (len / RAIL_STEP).toInt().coerceAtLeast(1)
                    for (k in 0 until pieces) {
                        val t0 = k / pieces.toFloat(); val t1 = (k + 1) / pieces.toFloat()
                        val x0 = ax + (bx - ax) * t0 + nx * 0.8f; val z0 = az + (bz - az) * t0 + nz * 0.8f
                        val x1 = ax + (bx - ax) * t1 + nx * 0.8f; val z1 = az + (bz - az) * t1 + nz * 0.8f
                        val g0 = map.groundAt(x0, z0); val g1 = map.groundAt(x1, z1)
                        if (g0 < 0.3f && g1 < 0.3f) continue // right down at the water: no wall to rail
                        val o = out(x0, z0, Surface.RAILING)
                        lift.by(0f) {
                            limb(o, x0, g0, z0, x0, g0 + 1.05f, z0, 0.045f, 0.04f, 3)
                            limb(o, x0, g0 + 1.02f, z0, x1, g1 + 1.02f, z1, 0.04f, 0.04f, 3)
                            limb(o, x0, g0 + 0.55f, z0, x1, g1 + 0.55f, z1, 0.025f, 0.025f, 3)
                        }
                    }
                }
            }
        }

        /** Small wooden fishing boats moored along the piers, white with a blue or red band, as at Ain El Mreisseh. */
        private fun addBoats() {
            var count = 0
            map.roads.forEachIndexed { index, r ->
                if (r.kind != CityMap.ROAD_PIER) return@forEachIndexed
                val rnd = Random(index * 41L + 5)
                val p = r.pts
                for (i in 0 until p.size / 2 - 1) {
                    val ax = p[2 * i]; val az = p[2 * i + 1]; val bx = p[2 * i + 2]; val bz = p[2 * i + 3]
                    val len = hypot(bx - ax, bz - az)
                    if (len < 6f) continue
                    val ux = (bx - ax) / len; val uz = (bz - az) / len
                    var t = 4f
                    while (t < len - 3f && count < MAX_BOATS) {
                        for (side in floatArrayOf(1f, -1f)) {
                            if (rnd.nextFloat() < 0.35f) continue
                            val off = r.width / 2f + 1.6f
                            val cx = ax + ux * t - uz * off * side; val cz = az + uz * t + ux * off * side
                            if (map.sea.none { CityMap.inside(it, cx, cz) } || map.groundAt(cx, cz) > 0.05f) continue
                            if (map.isInsideBuilding(cx, 0.5f, cz, 1.5f)) continue
                            lift.by(0f) { boat(cx, cz, ux, uz, rnd) }
                            count++
                        }
                        t += 7.5f
                    }
                }
            }
        }

        private fun boat(cx: Float, cz: Float, ux: Float, uz: Float, rnd: Random) {
            val band = if (rnd.nextBoolean()) Surface.BOAT_BLUE else Surface.BOAT_RED
            val len = 2.4f + rnd.nextFloat() * 0.8f
            block(Surface.BOAT_HULL, rect(cx, cz, ux, uz, len, 0.95f), -0.3f, 0.45f)
            block(band, rect(cx, cz, ux, uz, len + 0.03f, 0.98f), 0.45f, 0.62f)
            // The bow: a short wedge forward.
            val fx = cx + ux * len; val fz = cz + uz * len
            limb(out(fx, fz, Surface.BOAT_HULL), fx, 0.15f, fz, fx + ux * 0.9f, 0.55f, fz + uz * 0.9f, 0.7f, 0.08f, 4)
            block(Surface.BOAT_WOOD, rect(cx, cz, ux, uz, len * 0.8f, 0.8f), 0.45f, 0.5f)
            if (rnd.nextFloat() < 0.5f) block(Surface.BOAT_HULL, rect(cx - ux * len * 0.3f, cz - uz * len * 0.3f, ux, uz, 0.7f, 0.6f), 0.5f, 1.5f)
        }

        // ---- Landmarks: each place's own (found by name in the map data, see OsmToCity) -----

        /** A rectangle ring (counter-clockwise, as footprints are) round (cx, cz), [hu] along (ux, uz) and [hv] across. */
        private fun rect(cx: Float, cz: Float, ux: Float, uz: Float, hu: Float, hv: Float): FloatArray {
            val vx = -uz; val vz = ux
            return floatArrayOf(
                cx - ux * hu - vx * hv, cz - uz * hu - vz * hv,
                cx + ux * hu - vx * hv, cz + uz * hu - vz * hv,
                cx + ux * hu + vx * hv, cz + uz * hu + vz * hv,
                cx - ux * hu + vx * hv, cz - uz * hu + vz * hv,
            )
        }

        /**
         * One side of a [Box]: its middle (on the wall), outward direction and half its length;
         * [along] is the direction along it with the outside to its right, as walls, panels and
         * arches are drawn.
         */
        private class Side(val cx: Float, val cz: Float, val nx: Float, val nz: Float, val half: Float) {
            val ax get() = -nz
            val az get() = nx
            /** The point [t] metres along the side from its middle and [out] metres out from it. */
            fun x(t: Float, out: Float = 0f) = cx + ax * t + nx * out
            fun z(t: Float, out: Float = 0f) = cz + az * t + nz * out
        }

        /** [bx]'s four sides: along its length (+u, −u), then across it (+v, −v); [inset] metres in from its edges. */
        private fun sides(bx: Box, inset: Float = 0f): List<Side> {
            val vx = -bx.uz; val vz = bx.ux
            return listOf(
                Side(bx.cx + bx.ux * (bx.hu - inset), bx.cz + bx.uz * (bx.hu - inset), bx.ux, bx.uz, bx.hv - inset),
                Side(bx.cx - bx.ux * (bx.hu - inset), bx.cz - bx.uz * (bx.hu - inset), -bx.ux, -bx.uz, bx.hv - inset),
                Side(bx.cx + vx * (bx.hv - inset), bx.cz + vz * (bx.hv - inset), vx, vz, bx.hu - inset),
                Side(bx.cx - vx * (bx.hv - inset), bx.cz - vz * (bx.hv - inset), -vx, -vz, bx.hu - inset),
            )
        }

        /** Which of [bx]'s [sides] faces the start point (where players first see it from). */
        private fun frontOf(bx: Box): Int {
            val dx = map.spawnX - bx.cx; val dz = map.spawnZ - bx.cz
            return sides(bx).withIndex().maxBy { (_, s) -> s.nx * dx + s.nz * dz }.index
        }

        /** An arch (see [arch]) on side [s], [w] wide centred [t] metres along it. */
        private fun archOn(o: Floats, s: Side, t: Float, w: Float, y0: Float, spring: Float, out: Float = 0.05f) =
            arch(o, s.x(t - w / 2f), s.z(t - w / 2f), s.x(t + w / 2f), s.z(t + w / 2f), y0, spring, out)

        /** A flat panel (see [panel]) on side [s], [w] wide centred [t] metres along it. */
        private fun panelOn(o: Floats, s: Side, t: Float, w: Float, y0: Float, y1: Float, out: Float = 0.04f) =
            panel(o, s.x(t - w / 2f), s.z(t - w / 2f), s.x(t + w / 2f), s.z(t + w / 2f), y0, y1, out)

        /** A solid block on [ring] from [y0] to [y1], walls and top in [s]. */
        private fun block(s: Surface, ring: FloatArray, y0: Float, y1: Float, spanU: Float = 0f, spanV: Float = 0f) {
            val (cx, cz) = centroid(ring)
            walls(out(cx, cz, s), ring, y0, y1, spanU, spanV)
            flatPolygon(out(cx, cz, s), ring, y1, span(s))
        }

        /**
         * The footprint's smallest enclosing rectangle: centre, long axis (unit) and half-sizes
         * along and across it; and how much of it the footprint fills.
         */
        private class Box(val cx: Float, val cz: Float, val ux: Float, val uz: Float, val hu: Float, val hv: Float, val fill: Float)

        private fun box(b: CityMap.Building): Box {
            val p = b.pts
            val n = p.size / 2
            var best: Box? = null
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ex = p[2 * j] - p[2 * i]; val ez = p[2 * j + 1] - p[2 * i + 1]
                val l = hypot(ex, ez)
                if (l < 0.5f) continue
                val ux = ex / l; val uz = ez / l
                var u0 = Float.MAX_VALUE; var u1 = -Float.MAX_VALUE; var v0 = Float.MAX_VALUE; var v1 = -Float.MAX_VALUE
                for (k in 0 until n) {
                    val u = p[2 * k] * ux + p[2 * k + 1] * uz
                    val v = -p[2 * k] * uz + p[2 * k + 1] * ux
                    u0 = minOf(u0, u); u1 = maxOf(u1, u); v0 = minOf(v0, v); v1 = maxOf(v1, v)
                }
                val area = (u1 - u0) * (v1 - v0)
                if (best == null || area < best.hu * best.hv * 4f) {
                    val cu = (u0 + u1) / 2f; val cv = (v0 + v1) / 2f
                    val cx = cu * ux - cv * uz; val cz = cu * uz + cv * ux
                    // The long side first.
                    best = if (u1 - u0 >= v1 - v0) Box(cx, cz, ux, uz, (u1 - u0) / 2f, (v1 - v0) / 2f, b.area / area)
                    else Box(cx, cz, -uz, ux, (v1 - v0) / 2f, (u1 - u0) / 2f, b.area / area)
                }
            }
            return best ?: Box(b.centerX, b.centerZ, 1f, 0f, (b.maxX - b.minX) / 2f, (b.maxZ - b.minZ) / 2f, 1f)
        }

        /**
         * A hipped roof of red clay tiles over the footprint's rectangle, from [top], each slope
         * rising at [pitch] (rise over run), its eaves [overhang] past the walls: the Lebanese
         * house roof.
         */
        private fun hipRoof(bx: Box, top: Float, pitch: Float = 0.5f, overhang: Float = 0.45f) {
            val hu = bx.hu + overhang; val hv = bx.hv + overhang
            val rise = hv * pitch
            val ridge = (hu - hv).coerceAtLeast(0f)
            val ux = bx.ux; val uz = bx.uz; val vx = -uz; val vz = ux
            fun p(u: Float, v: Float, y: Float) = floatArrayOf(bx.cx + ux * u + vx * v, y, bx.cz + uz * u + vz * v)
            val o = out(bx.cx, bx.cz, Surface.ROOF_TILES)
            val s = span(Surface.ROOF_TILES)
            val slope = sqrt(hv * hv + rise * rise)
            // A face: corners [a] [b] [c] (and [d] for a trapezium), its texture laid along u and up the slope.
            fun face(vararg c: FloatArray) {
                val e1 = floatArrayOf(c[1][0] - c[0][0], c[1][1] - c[0][1], c[1][2] - c[0][2])
                val e2 = floatArrayOf(c[2][0] - c[0][0], c[2][1] - c[0][1], c[2][2] - c[0][2])
                var nx = e1[1] * e2[2] - e1[2] * e2[1]; var ny = e1[2] * e2[0] - e1[0] * e2[2]; var nz = e1[0] * e2[1] - e1[1] * e2[0]
                val l = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-6f } ?: return
                nx /= l; ny /= l; nz /= l
                // Along the eaves for u, up the slope for v.
                val ax = -nz; val az = nx
                val al = hypot(ax, az).takeIf { it > 1e-4f } ?: 1f
                fun vtx(q: FloatArray) = o.vertex(q[0], q[1], q[2], nx, ny, nz,
                    ((q[0] - bx.cx) * ax / al + (q[2] - bx.cz) * az / al) / s, -(q[1] - top) / rise * slope / s)
                for (k in 1 until c.size - 1) { vtx(c[0]); vtx(c[k]); vtx(c[k + 1]) }
            }
            val r0 = p(-ridge / 2f, 0f, top + rise); val r1 = p(ridge / 2f, 0f, top + rise)
            val a = p(-hu, -hv, top); val b2 = p(hu, -hv, top); val c = p(hu, hv, top); val d = p(-hu, hv, top)
            // Two long slopes and two hipped ends, wound to face out.
            face(a, r0, r1, b2)
            face(c, r1, r0, d)
            face(b2, r1, c)
            face(d, r0, a)
            // The underside of the eaves, so the roof isn't see-through from below.
            face(a, b2, c, d)
        }

        /** A clock face on a wall facing (nx, nz): white dial, dark hands at ten past ten. */
        private fun clock(cx: Float, cy: Float, cz: Float, nx: Float, nz: Float, r: Float) {
            val rx = -nz; val rz = nx
            val face = out(cx, cz, Surface.CLOCK_FACE)
            val sides = 16
            for (k in 0 until sides) {
                val a0 = 2f * PI.toFloat() * k / sides; val a1 = 2f * PI.toFloat() * (k + 1) / sides
                face.vertex(cx, cy, cz, nx, 0f, nz, 0.02f, 0.02f)
                face.vertex(cx + rx * cos(a1) * r, cy + sin(a1) * r, cz + rz * cos(a1) * r, nx, 0f, nz, 0.02f, 0.02f)
                face.vertex(cx + rx * cos(a0) * r, cy + sin(a0) * r, cz + rz * cos(a0) * r, nx, 0f, nz, 0.02f, 0.02f)
            }
            val hands = out(cx, cz, Surface.CLOCK_HANDS)
            val ox = cx + nx * 0.03f; val oz = cz + nz * 0.03f
            for ((angle, length) in listOf(2.1f to 0.5f, 0.52f to 0.8f)) {
                val dx = cos(angle); val dy = sin(angle)
                val w = r * 0.06f
                val tx = ox + rx * dx * r * length; val ty = cy + dy * r * length; val tz = oz + rz * dx * r * length
                val px = -dy * w; val py = dx * w
                hands.vertex(ox + rx * px, cy + py, oz + rz * px, nx, 0f, nz, 0.02f, 0.02f)
                hands.vertex(tx - rx * px, ty - py, tz - rz * px, nx, 0f, nz, 0.02f, 0.02f)
                hands.vertex(tx + rx * px, ty + py, tz + rz * px, nx, 0f, nz, 0.02f, 0.02f)
                hands.vertex(ox + rx * px, cy + py, oz + rz * px, nx, 0f, nz, 0.02f, 0.02f)
                hands.vertex(ox - rx * px, cy - py, oz - rz * px, nx, 0f, nz, 0.02f, 0.02f)
                hands.vertex(tx - rx * px, ty - py, tz - rz * px, nx, 0f, nz, 0.02f, 0.02f)
            }
        }

        /** A flat panel on the wall from (ax, az) to (bx, bz) (outside to its right), [out] metres proud, textured [uv] (left, top, right, bottom). */
        private fun panel(o: Floats, ax: Float, az: Float, bx: Float, bz: Float, y0: Float, y1: Float, out: Float, uv: FloatArray? = null) {
            val len = hypot(bx - ax, bz - az)
            if (len < 1e-3f) return
            val nx = (bz - az) / len; val nz = -(bx - ax) / len
            val x0 = ax + nx * out; val z0 = az + nz * out; val x1 = bx + nx * out; val z1 = bz + nz * out
            val l = uv?.get(0) ?: 0.02f; val t = uv?.get(1) ?: 0.02f; val r = uv?.get(2) ?: 0.02f; val bt = uv?.get(3) ?: 0.02f
            // Seen from outside, b is on the left.
            o.vertex(x1, y0, z1, nx, 0f, nz, l, bt)
            o.vertex(x0, y0, z0, nx, 0f, nz, r, bt)
            o.vertex(x0, y1, z0, nx, 0f, nz, r, t)
            o.vertex(x1, y0, z1, nx, 0f, nz, l, bt)
            o.vertex(x0, y1, z0, nx, 0f, nz, r, t)
            o.vertex(x1, y1, z1, nx, 0f, nz, l, t)
        }

        /**
         * The Mohammad Al-Amin Mosque (2008, Ottoman style), as built: a 46 m square body of ochre
         * stone 17 m high with a portico of five tall arches on the square, a pale blue dome on a
         * windowed drum (42 m, a gold spire to 48 m) over a square base, a lower blue half-dome in
         * front of it and four small domes at the corners; and four slender pencil minarets of 72 m
         * flush with the corners, fluted, with two balconies each and a tall stone cone (not blue).
         * Sizes are the real ones, scaled to the footprint in the map.
         */
        private fun addGrandMosque(b: CityMap.Building) {
            val bx = box(b)
            val s = (minOf(bx.hu, bx.hv) / 23f).coerceIn(0.6f, 1.4f)
            val c = Surface.OCHRE_STONE
            val bodyTop = 17f
            // Plain ochre stone: no ordinary building's windows and shutters.
            walls(out(b.centerX, b.centerZ, c), b.pts, 0f, bodyTop)
            cornice(out(b.centerX, b.centerZ, Surface.CORNICE), b.pts, bodyTop)
            cornice(out(b.centerX, b.centerZ, Surface.CORNICE), b.pts, 14.5f)
            flatPolygon(out(b.centerX, b.centerZ, Surface.ROOF), b.pts, bodyTop, span(Surface.ROOF))
            // The facades, on the footprint's own walls: tall arches along the front (the walls facing
            // the square), two rows of arched windows elsewhere.
            val all = sides(bx)
            val front = frontOf(bx)
            val fx = all[front].nx; val fz = all[front].nz
            val ring = if (CityMap.signedArea(b.pts) > 0f) b.pts else FloatArray(b.pts.size) { i -> b.pts[(b.pts.size - 2 - (i / 2) * 2) + i % 2] }
            val shade = out(b.centerX, b.centerZ, Surface.ARCH_SHADOW)
            val n = ring.size / 2
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ax = ring[2 * i]; val az = ring[2 * i + 1]; val ex = ring[2 * j]; val ez = ring[2 * j + 1]
                val len = hypot(ex - ax, ez - az)
                if (len < 4f) continue
                // Outside is to the right going round a footprint.
                val wall = Side((ax + ex) / 2f, (az + ez) / 2f, (ez - az) / len, -(ex - ax) / len, len / 2f)
                if (wall.nx * fx + wall.nz * fz > 0.7f && len >= 8f) {
                    val count = (len / 8f).toInt().coerceIn(1, 7)
                    val pitch = len / count
                    val w = minOf(6f * s, pitch * 0.75f)
                    for (k in 0 until count) archOn(shade, wall, -len / 2f + (k + 0.5f) * pitch, w, 0.6f, 12.6f - w / 2f)
                } else {
                    val count = (len / 9f).toInt()
                    if (count == 0) continue
                    val pitch = len / count
                    for (k in 0 until count) {
                        val t = -len / 2f + (k + 0.5f) * pitch
                        archOn(shade, wall, t, 2.4f, 2f, 6f)
                        archOn(shade, wall, t, 2.4f, 8.5f, 12f)
                    }
                }
            }
            // The dome's square base, the drum with its ring of windows, the pale blue dome and its spire.
            block(c, rect(bx.cx, bx.cz, bx.ux, bx.uz, 13f * s, 13f * s), bodyTop, 26f)
            val drum = 11.5f * s
            prism(out(bx.cx, bx.cz, c), bx.cx, bx.cz, drum, drum, 26f, 33f, 20)
            val windows = out(bx.cx, bx.cz, Surface.WINDOW_DARK)
            for (k in 0 until 20) {
                val a0 = 2f * PI.toFloat() * (k + 0.3f) / 20f; val a1 = 2f * PI.toFloat() * (k + 0.7f) / 20f
                // From a0 to a1 round the drum the outside is on the right.
                arch(windows, bx.cx + cos(a0) * drum, bx.cz + sin(a0) * drum, bx.cx + cos(a1) * drum, bx.cz + sin(a1) * drum, 28f, 31f, 0.06f)
            }
            lathe(out(bx.cx, bx.cz, Surface.DOME_PALE), bx.cx, bx.cz, domeProfile(10.8f * s, 33f, 9f, 10), 24)
            prism(out(bx.cx, bx.cz, Surface.GOLD), bx.cx, bx.cz, 0.3f, 0.03f, 41.5f, 48f, 6)
            sphere(out(bx.cx, bx.cz, Surface.GOLD), bx.cx, 43.5f, bx.cz, 0.45f, 4, 8, hemisphere = false)
            // The half-dome over the entrance, lower, on its own windowed drum.
            val hx = bx.cx + fx * 15f * s; val hz = bx.cz + fz * 15f * s
            if (CityMap.inside(b.pts, hx, hz)) {
                prism(out(hx, hz, c), hx, hz, 6.2f * s, 6.2f * s, bodyTop, 21f, 16)
                lathe(out(hx, hz, Surface.DOME_PALE), hx, hz, domeProfile(6f * s, 21f, 5f, 8), 18)
            }
            // Four small domes at the roof's corners, inside the minarets.
            for ((su, sv) in listOf(1f to 1f, -1f to 1f, 1f to -1f, -1f to -1f)) {
                val iu = bx.hu - 9f * s; val iv = bx.hv - 9f * s
                val x = bx.cx + bx.ux * su * iu - bx.uz * sv * iv; val z = bx.cz + bx.uz * su * iu + bx.ux * sv * iv
                if (!CityMap.inside(b.pts, x, z)) continue
                prism(out(x, z, c), x, z, 2.7f * s, 2.7f * s, bodyTop, 19.5f, 12)
                lathe(out(x, z, Surface.DOME_PALE), x, z, domeProfile(2.6f * s, 19.5f, 2.6f * s, 6), 12)
            }
            // The four minarets, flush with the corners: slender, at their real size whatever the footprint.
            for ((su, sv) in listOf(1f to 1f, -1f to 1f, 1f to -1f, -1f to -1f)) {
                val iu = bx.hu - 2.6f; val iv = bx.hv - 2.6f
                val mx = bx.cx + bx.ux * su * iu - bx.uz * sv * iv; val mz = bx.cz + bx.uz * su * iu + bx.ux * sv * iv
                val o = out(mx, mz, c)
                block(c, rect(mx, mz, bx.ux, bx.uz, 2.6f, 2.6f), 0f, 20f)
                // From the square base to the round shaft, a flared collar.
                lathe(o, mx, mz, floatArrayOf(2.8f, 20f, 2.6f, 21f, 1.9f, 23f), 8)
                prism(o, mx, mz, 1.85f, 1.55f, 23f, 60.5f, 16)
                // Two balconies on corbels.
                for (y in floatArrayOf(42f, 56f)) {
                    lathe(o, mx, mz, floatArrayOf(1.65f, y - 1.4f, 2.45f, y), 16)
                    prism(o, mx, mz, 2.45f, 2.45f, y, y + 1f, 16)
                    prism(out(mx, mz, Surface.CORNICE), mx, mz, 2.5f, 2.5f, y + 1f, y + 1.15f, 16)
                }
                // The tall pointed stone cone, and the gold finial.
                prism(o, mx, mz, 1.65f, 0.05f, 60.5f, 70.5f, 16)
                prism(out(mx, mz, Surface.GOLD), mx, mz, 0.14f, 0.02f, 70.3f, 72.5f, 6)
                sphere(out(mx, mz, Surface.GOLD), mx, 71f, mz, 0.22f, 3, 6, hemisphere = false)
            }
        }

        /**
         * The Al-Abed clock tower in Nejmeh Square (Art Deco, 1934): a 5.5 m square shaft of pale
         * limestone, 22 m to its flat top, on a plinth with flared corners. Each face has three
         * tall slit windows, a cornice band, the white Rolex dial and two louvred openings; at each
         * corner two fluted finials rise above the top (eight in all). No dome.
         */
        private fun addClockTower(b: CityMap.Building) {
            val bx = box(b)
            val cx = bx.cx; val cz = bx.cz
            val h = 2.75f
            val stone = Surface.LIMESTONE_PALE
            val trim = Surface.STONE_TRIM
            block(trim, rect(cx, cz, bx.ux, bx.uz, 3.5f, 3.5f), 0f, 0.9f)
            block(stone, rect(cx, cz, bx.ux, bx.uz, 3.1f, 3.1f), 0.9f, 2.5f)
            block(stone, rect(cx, cz, bx.ux, bx.uz, h, h), 2.5f, 22f)
            block(trim, rect(cx, cz, bx.ux, bx.uz, h + 0.18f, h + 0.18f), 13.3f, 13.7f)
            block(trim, rect(cx, cz, bx.ux, bx.uz, h + 0.12f, h + 0.12f), 17.9f, 18.2f)
            block(trim, rect(cx, cz, bx.ux, bx.uz, h + 0.1f, h + 0.1f), 21.6f, 22f)
            val box = Box(cx, cz, bx.ux, bx.uz, h, h, 1f)
            for (side in sides(box)) {
                val dark = out(side.cx, side.cz, Surface.WINDOW_DARK)
                for (t in floatArrayOf(-0.5f, 0f, 0.5f)) panelOn(dark, side, t, 0.3f, 4f, 13f)
                for (t in floatArrayOf(-0.42f, 0.42f)) panelOn(dark, side, t, 0.75f, 18.5f, 20.2f)
                clock(side.x(0f, 0.05f), 16f, side.z(0f, 0.05f), side.nx, side.nz, 0.95f)
            }
            // Two fluted finials at each corner, one on each face.
            val finial = out(cx, cz, stone)
            for ((su, sv) in listOf(1f to 1f, -1f to 1f, 1f to -1f, -1f to -1f)) for (k in 0..1) {
                val du = if (k == 0) h - 0.35f else h - 1.1f
                val dv = if (k == 0) h - 1.1f else h - 0.35f
                val x = cx + bx.ux * su * du - bx.uz * sv * dv; val z = cz + bx.uz * su * du + bx.ux * sv * dv
                lathe(finial, x, z, floatArrayOf(0.34f, 22f, 0.3f, 23.2f, 0.22f, 23.7f, 0.1f, 24f, 0f, 24.1f), 8)
            }
        }

        /**
         * The Hamidiyyeh clock tower by the Grand Serail (Ottoman, 1897, 25 m): warm sandstone
         * with white trim, four stages on a 5 m base. A striped pointed-arch doorway, paired
         * pointed windows, a small white balcony on every face, a clock face each way under the
         * belfry's paired arches, and battlements of stepped merlons on top. No dome.
         */
        private fun addHamidiyyeh(b: CityMap.Building) {
            val bx = box(b)
            val cx = bx.cx; val cz = bx.cz
            val stone = Surface.ROSE_SANDSTONE
            val white = Surface.TRIM_WHITE
            fun square(half: Float) = rect(cx, cz, bx.ux, bx.uz, half, half)
            block(stone, square(2.5f), 0f, 6.2f)
            block(white, square(2.6f), 6.2f, 6.5f)
            block(stone, square(2.1f), 6.5f, 17f)
            block(white, square(2.25f), 17f, 17.25f)
            block(stone, square(2.1f), 17.25f, 20.9f)
            block(white, square(2.4f), 20.9f, 21.4f)
            block(stone, square(2.1f), 21.4f, 23.5f)
            block(white, square(2.25f), 23.5f, 23.7f)
            val front = frontOf(Box(cx, cz, bx.ux, bx.uz, 2.5f, 2.5f, 1f))
            sides(Box(cx, cz, bx.ux, bx.uz, 2.5f, 2.5f, 1f)).forEachIndexed { k, side ->
                if (k != front) return@forEachIndexed
                // The doorway: a white frame, then the dark door under its arch.
                archOn(out(side.cx, side.cz, white), side, 0f, 2.6f, 0f, 3.9f, 0.03f)
                archOn(out(side.cx, side.cz, Surface.WINDOW_DARK), side, 0f, 1.9f, 0f, 3.5f, 0.06f)
            }
            for (side in sides(Box(cx, cz, bx.ux, bx.uz, 2.1f, 2.1f, 1f))) {
                val w = out(side.cx, side.cz, white)
                val dark = out(side.cx, side.cz, Surface.WINDOW_DARK)
                // Paired pointed windows in one white arch.
                archOn(w, side, 0f, 2f, 9f, 11.2f, 0.03f)
                for (t in floatArrayOf(-0.48f, 0.48f)) archOn(dark, side, t, 0.8f, 9.2f, 11f, 0.06f)
                // The balcony: a slab on the wall, its white railing, and the door behind it.
                block(white, rect(side.x(0f, 0.3f), side.z(0f, 0.3f), side.ax, side.az, 0.9f, 0.3f), 13.5f, 13.75f)
                block(white, rect(side.x(0f, 0.58f), side.z(0f, 0.58f), side.ax, side.az, 0.9f, 0.03f), 13.75f, 14.65f)
                panelOn(w, side, 0f, 1.2f, 13.75f, 16.2f, 0.03f)
                panelOn(dark, side, 0f, 0.9f, 13.75f, 15.75f, 0.05f)
                clock(side.x(0f, 0.05f), 19f, side.z(0f, 0.05f), side.nx, side.nz, 1f)
                // The belfry's two arches.
                for (t in floatArrayOf(-0.6f, 0.6f)) archOn(dark, side, t, 0.6f, 21.7f, 22.8f, 0.05f)
                // Five stepped merlons along the top.
                for (i in -2..2) {
                    val mx = side.x(i * 0.85f, -0.15f); val mz = side.z(i * 0.85f, -0.15f)
                    block(stone, rect(mx, mz, side.ax, side.az, 0.25f, 0.15f), 23.7f, 24.3f)
                    block(stone, rect(mx, mz, side.ax, side.az, 0.14f, 0.15f), 24.3f, 24.75f)
                }
            }
            prism(out(cx, cz, Surface.POLE), cx, cz, 0.04f, 0.03f, 23.7f, 26f, 6)
        }

        /**
         * The Egg: the 1960s concrete shell of the unfinished Beirut City Centre cinema by Martyrs'
         * Square, an oval dome of bare, stained concrete (24 m across, 11 m high) on a dark base.
         */
        private fun addEgg(b: CityMap.Building) {
            val bx = box(b)
            // The open face towards the square (the start), the rounded back away from it.
            val all = sides(bx)
            val front = listOf(2, 3).maxBy { k -> all[k].nx * (map.spawnX - bx.cx) + all[k].nz * (map.spawnZ - bx.cz) }
            val f = all[front]
            // The podium: two storeys of bare concrete frame (slabs, a 7 m grid of columns), mostly open.
            val pu = bx.hu.coerceIn(10f, 20f); val pv = bx.hv.coerceIn(8f, 15f)
            val slab = Surface.RAW_CONCRETE
            for ((y0, y1) in listOf(4.6f to 5f, 9.6f to 10f)) block(slab, rect(bx.cx, bx.cz, bx.ux, bx.uz, pu, pv), y0, y1)
            val vx = -bx.uz; val vz = bx.ux
            var u = -pu + 0.4f
            while (u <= pu) {
                var v = -pv + 0.4f
                while (v <= pv) {
                    block(slab, rect(bx.cx + bx.ux * u + vx * v, bx.cz + bx.uz * u + vz * v, bx.ux, bx.uz, 0.4f, 0.4f), 0f, 9.6f)
                    v += ((pv * 2f - 0.8f) / ((pv * 2f / 7f).roundToInt().coerceAtLeast(1))).coerceAtLeast(3f)
                }
                u += ((pu * 2f - 0.8f) / ((pu * 2f / 7f).roundToInt().coerceAtLeast(1))).coerceAtLeast(3f)
            }
            // Here and there on the front, rusty panels and grey block walls fill a bay.
            val pf = Side(bx.cx + f.nx * pv, bx.cz + f.nz * pv, f.nx, f.nz, pu)
            panelOn(out(pf.cx, pf.cz, Surface.RUST_PANEL), pf, -pu * 0.15f, 5.5f, 0.2f, 4.4f, -0.3f)
            panelOn(out(pf.cx, pf.cz, Surface.BLOCK_GREY), pf, pu * 0.45f, 5f, 0.2f, 1.6f, -0.3f)
            panelOn(out(pf.cx, pf.cz, Surface.BLOCK_GREY), pf, -pu * 0.7f, 4f, 5f, 6.2f, -0.3f)
            // The shell: the back half of a squarish capsule 25 m long and 11 m high, cut open along
            // its length, its underside resting on the podium.
            val a = 12.5f; val hb = 5.5f; val c = (pv * 1.4f).coerceIn(8f, 14f)
            val cy = 10f + hb
            val ox = bx.cx + f.nx * (pv - 1f); val oz = bx.cz + f.nz * (pv - 1f)
            val bxn = -f.nx; val bzn = -f.nz // backwards, into the shell
            val e = 2.5f
            fun sp(t: Float) = kotlin.math.sign(t) * abs(t).pow(2f / e)
            val shell = out(bx.cx, bx.cz, Surface.EGG_CONCRETE)
            val rings = 8; val around = 28
            fun at(i: Int, j: Int): FloatArray {
                val al = PI.toFloat() / 2f * i / rings
                val be = 2f * PI.toFloat() * j / around
                val ca = cos(al); val sa = sin(al)
                val x = sp(ca) * sp(cos(be)); val y = sp(ca) * sp(sin(be)); val z = sp(sa)
                // The surface's normal, from the plain ellipsoid's: near enough for lighting.
                var nu = ca * cos(be) / a; var ny = ca * sin(be) / hb; var nb = sa / c
                val nl = sqrt(nu * nu + ny * ny + nb * nb); nu /= nl; ny /= nl; nb /= nl
                return floatArrayOf(
                    ox + bx.ux * x * a + bxn * z * c, cy + y * hb, oz + bx.uz * x * a + bzn * z * c,
                    bx.ux * nu + bxn * nb, ny, bx.uz * nu + bzn * nb,
                )
            }
            for (i in 0 until rings) for (j in 0 until around) {
                val q = arrayOf(at(i, j), at(i + 1, j), at(i + 1, j + 1), at(i, j + 1))
                for (tri in arrayOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 3))) {
                    val pts = FloatArray(9) { k -> q[tri[k / 3]][k % 3] }
                    val n = q[tri[0]]
                    for (k in facing(pts, intArrayOf(0, 1, 2), n[3], n[4], n[5])) {
                        val p = q[tri[k]]
                        shell.vertex(p[0], p[1], p[2], p[3], p[4], p[5], 0.02f, 0.02f)
                    }
                }
            }
            // The open face: an oval of weathered concrete, the black voids where the cinema's
            // floors were, a concrete pier between them, and two tiers of slab edges sticking out.
            val face = out(ox, oz, Surface.EGG_FACE)
            val fanN = 28
            for (j in 0 until fanN) {
                val b0 = 2f * PI.toFloat() * j / fanN; val b1 = 2f * PI.toFloat() * (j + 1) / fanN
                val pts = floatArrayOf(
                    ox, cy, oz,
                    ox + bx.ux * sp(cos(b0)) * a, cy + sp(sin(b0)) * hb, oz + bx.uz * sp(cos(b0)) * a,
                    ox + bx.ux * sp(cos(b1)) * a, cy + sp(sin(b1)) * hb, oz + bx.uz * sp(cos(b1)) * a,
                )
                for (k in facing(pts, intArrayOf(0, 1, 2), f.nx, 0f, f.nz)) face.vertex(pts[3 * k], pts[3 * k + 1], pts[3 * k + 2], f.nx, 0f, f.nz, 0.02f, 0.02f)
            }
            val fs = Side(ox, oz, f.nx, f.nz, a)
            val voids = out(ox, oz, Surface.VOID_DARK)
            panelOn(voids, fs, -5.6f, 7.2f, cy - 2.2f, cy + 1.6f, 0.05f)
            panelOn(voids, fs, 5.6f, 7.2f, cy - 2.2f, cy + 1.6f, 0.05f)
            panelOn(voids, fs, 0f, 18f, 10.3f, cy - 2.9f, 0.05f)
            for ((y, w) in listOf(cy - 2.5f to a * 0.92f, cy + 2f to a * 0.85f)) {
                block(slab, rect(fs.x(0f, 0.3f), fs.z(0f, 0.3f), fs.ax, fs.az, w, 0.35f), y - 0.25f, y + 0.1f)
            }
        }

        /**
         * The Murr Tower (1974, unfinished since the war): a closed 140 m monolith of brown-grey
         * cast concrete, nearly windowless. Its long faces have one narrow field of five columns
         * of small windows up the middle, a window a floor; its short faces a single stack of
         * small square holes off to one side. Shell-pocked walls, a dark open ground floor.
         */
        private fun addMurr(b: CityMap.Building, index: Int) {
            val (cx, cz) = centroid(b.pts)
            val h = b.height
            walls(out(cx, cz, Surface.MURR_CONCRETE), b.pts, 0f, h)
            flatPolygon(out(cx, cz, Surface.RAW_CONCRETE), b.pts, h)
            cornice(out(cx, cz, Surface.RAW_CONCRETE), b.pts, h)
            val bx = box(b)
            val rnd = Random(index * 97L + 13)
            val floors = ((h - 5f) / 3.5f).toInt()
            for (side in sides(bx)) {
                val dark = out(side.cx, side.cz, Surface.SHELL_DARK)
                panelOn(dark, side, 0f, side.half * 1.7f, 0f, 4.2f, 0.05f)
                val long = side.half >= maxOf(bx.hu, bx.hv) - 0.01f
                for (f in 0 until floors) {
                    val y = 5.5f + f * 3.5f
                    if (long) for (c in -2..2) panelOn(dark, side, c * 2.6f, 1.8f, y, y + 1.4f, 0.05f)
                    else panelOn(dark, side, side.half * 0.55f, 0.8f, y + 0.3f, y + 1.1f, 0.05f)
                }
                // Shell holes, here and there on the blind walls.
                repeat(6) {
                    val t = (rnd.nextFloat() * 2f - 1f) * side.half * 0.9f
                    if (long && abs(t) < 7.5f) return@repeat
                    val y = 8f + rnd.nextFloat() * (h - 16f)
                    val w = 0.8f + rnd.nextFloat() * 1.4f
                    panelOn(dark, side, t, w, y, y + w * 0.8f, 0.05f)
                }
            }
        }

        /**
         * The old Holiday Inn (1974, gutted in the battle of the hotels): a 79 m slab of white
         * concrete balconies in a deep, regular egg-crate grid, every bay an empty dark hole, over
         * a dark open podium; blank, shot-up end walls; a navy-tiled stair tower at one end rising
         * above the roof, and the curved drum of the revolving restaurant at the other.
         */
        private fun addHolidayInn(b: CityMap.Building, index: Int) {
            val (cx, cz) = centroid(b.pts)
            val h = b.height
            walls(out(cx, cz, Surface.HOTEL_WHITE), b.pts, 0f, h)
            flatPolygon(out(cx, cz, Surface.RAW_CONCRETE), b.pts, h)
            cornice(out(cx, cz, Surface.HOTEL_WHITE), b.pts, h)
            val rnd = Random(index * 131L + 7)
            // The footprint's own walls (outside to the right going round it): the two longest are
            // the balcony faces, the rest the blank end walls.
            val ring = if (CityMap.signedArea(b.pts) > 0f) b.pts else FloatArray(b.pts.size) { i -> b.pts[(b.pts.size - 2 - (i / 2) * 2) + i % 2] }
            val n = ring.size / 2
            val walls = (0 until n).map { i ->
                val j = (i + 1) % n
                val ax = ring[2 * i]; val az = ring[2 * i + 1]; val ex = ring[2 * j]; val ez = ring[2 * j + 1]
                val len = hypot(ex - ax, ez - az).coerceAtLeast(1e-3f)
                Side((ax + ex) / 2f, (az + ez) / 2f, (ez - az) / len, -(ex - ax) / len, len / 2f)
            }
            val faces = walls.sortedByDescending { it.half }.take(2).toSet()
            val top = h - 6.5f // the top two floors: solid, a few small openings
            for (wall in walls) {
                if (wall.half < 1.5f) continue
                val dark = out(wall.cx, wall.cz, Surface.HOTEL_DARK)
                // The podium: pinkish tile, its big bays open and dark.
                panelOn(out(wall.cx, wall.cz, Surface.HOTEL_TILE), wall, 0f, wall.half * 2f, 0f, 10f, 0.03f)
                val podiumBays = (wall.half * 2f / 6f).toInt()
                for (i in 0 until podiumBays) panelOn(dark, wall, -wall.half + (i + 0.5f) * wall.half * 2f / podiumBays, wall.half * 2f / podiumBays - 1.2f, 0.3f, 8.6f, 0.06f)
                if (wall in faces) {
                    // The egg-crate: in every ~4 m bay of every 3.3 m floor, a dark opening over a
                    // white balcony parapet, between the white floor slabs and fins.
                    val bays = (wall.half * 2f / 4.1f).toInt().coerceAtLeast(1)
                    val bay = wall.half * 2f / bays
                    var y = 10.6f
                    while (y + 3.3f <= top) {
                        for (i in 0 until bays) panelOn(dark, wall, -wall.half + (i + 0.5f) * bay, bay - 0.9f, y + 1.15f, y + 2.85f, 0.05f)
                        y += 3.3f
                    }
                    // The expansion joint down the middle.
                    panelOn(dark, wall, 0f, 0.3f, 10f, top, 0.06f)
                    // A few small openings in the solid top floors.
                    for (i in 0 until bays step 3) panelOn(dark, wall, -wall.half + (i + 0.5f) * bay, 1.2f, top + 2f, top + 3.2f, 0.05f)
                } else if (wall.half > 3f) {
                    // A blank end wall, shot up.
                    repeat((wall.half * 1.2f).toInt()) {
                        val t = (rnd.nextFloat() * 2f - 1f) * wall.half * 0.85f
                        val y = 12f + rnd.nextFloat() * (h - 20f)
                        val w = 0.5f + rnd.nextFloat() * 1.4f
                        panelOn(dark, wall, t, w, y, y + w, 0.05f)
                    }
                }
            }
            // The navy stair tower at one end, above the roof; the restaurant's curved drum at the
            // other, its fascia reaching past the end wall.
            val bx = box(b)
            val ends = if (bx.hu >= bx.hv) 1f else 0f
            val ex = if (ends > 0f) bx.ux else -bx.uz; val ez = if (ends > 0f) bx.uz else bx.ux
            val long = maxOf(bx.hu, bx.hv); val short = minOf(bx.hu, bx.hv)
            val tx = bx.cx - ex * (long - 3f); val tz = bx.cz - ez * (long - 3f)
            if (CityMap.inside(b.pts, tx, tz)) block(Surface.NAVY_TILE, rect(tx, tz, ex, ez, 2.6f, short * 0.55f), h - 1f, h + 4f)
            val dx = bx.cx + ex * (long - 8f); val dz = bx.cz + ez * (long - 8f)
            val dr = minOf(10f, short * 0.9f)
            prism(out(dx, dz, Surface.HOTEL_WHITE), dx, dz, dr, dr, h, h + 5.5f, 24)
            prism(out(dx, dz, Surface.HOTEL_DARK), dx, dz, dr + 0.05f, dr + 0.05f, h + 1.2f, h + 4.3f, 24)
            prism(out(dx, dz, Surface.HOTEL_WHITE), dx, dz, dr + 0.1f, dr + 0.1f, h + 5.5f, h + 6f, 24)
        }

        /** The old Ras Beirut lighthouse (the Manara): an octagonal tower in black and white bands, its lantern and gallery on top. */
        private fun addLighthouse(b: CityMap.Building) {
            val cx = b.centerX; val cz = b.centerZ
            val h = b.height
            val bands = 6
            for (k in 0 until bands) {
                val y0 = h * 0.85f * k / bands; val y1 = h * 0.85f * (k + 1) / bands
                val r0 = 2.6f - 0.6f * k / bands; val r1 = 2.6f - 0.6f * (k + 1) / bands
                prism(out(cx, cz, if (k % 2 == 0) Surface.STRIPE_WHITE else Surface.STRIPE_BLACK), cx, cz, r0, r1, y0, y1, 8)
            }
            prism(out(cx, cz, Surface.STRIPE_BLACK), cx, cz, 2.6f, 2.6f, h * 0.85f, h * 0.87f, 8)
            prism(out(cx, cz, Surface.LAMP), cx, cz, 1.4f, 1.4f, h * 0.87f, h * 0.96f, 8)
            prism(out(cx, cz, Surface.STRIPE_BLACK), cx, cz, 1.6f, 0.2f, h * 0.96f, h, 8)
        }

        /**
         * The Martyrs' Monument: the 1960 bronze group on its stepped stone pedestal, a woman
         * holding a torch high over the fallen, still scarred by the war's bullets.
         */
        private fun addStatue(b: CityMap.Building) {
            val cx = b.centerX; val cz = b.centerZ
            // Facing the start (across the square): f forwards, r to the viewer's right as they face it.
            val dx = map.spawnX - cx; val dz = map.spawnZ - cz
            val dl = hypot(dx, dz).coerceAtLeast(1e-3f)
            val fx = dx / dl; val fz = dz / dl
            val rx = fz; val rz = -fx
            /** A point [right] metres to the viewer's right of the middle and [fwd] metres towards them. */
            fun px(right: Float, fwd: Float) = cx + rx * right + fx * fwd
            fun pz(right: Float, fwd: Float) = cz + rz * right + fz * fwd
            /** An elongated hexagon, a flat face towards the viewer. */
            fun hexagon(hw: Float, hd: Float): FloatArray {
                val cut = hd * 0.6f
                val corners = listOf(-hw + cut to -hd, hw - cut to -hd, hw to 0f, hw - cut to hd, -hw + cut to hd, -hw to 0f)
                return corners.flatMap { (r, f) -> listOf(px(r, f), pz(r, f)) }.toFloatArray().let { ring ->
                    // Wound the way footprints are (as [rect] makes them), or turned round.
                    if (CityMap.signedArea(ring) > 0f) ring else FloatArray(ring.size) { i -> ring[(ring.size - 2 - (i / 2) * 2) + i % 2] }
                }
            }
            // Two low grey steps, the white pedestal, and the pale rough rock the figures stand on.
            block(Surface.STEP_GREY, hexagon(3f, 2f), 0f, 0.15f)
            block(Surface.STEP_GREY, hexagon(2.8f, 1.85f), 0.15f, 0.3f)
            block(Surface.RENDER_WHITE, hexagon(1.6f, 1.2f), 0.3f, 1.9f)
            block(Surface.ROCK_PALE, rect(px(-0.2f, -0.15f), pz(-0.2f, -0.15f), rx, rz, 0.55f, 0.45f), 1.9f, 3f)
            val o = out(cx, cz, Surface.BRONZE_DARK)
            val y = 3f
            // The woman, 2.4 m: a long robe, her right arm straight up with the torch, a cloak hanging from it.
            val wx = px(-0.2f, -0.15f); val wz = pz(-0.2f, -0.15f)
            lathe(o, wx, wz, floatArrayOf(0.45f, y, 0.25f, y + 1.4f, 0.3f, y + 1.95f, 0.12f, y + 2.12f), 10)
            sphere(o, wx, y + 2.26f, wz, 0.13f, 4, 8, hemisphere = false)
            val hx = px(0.05f, -0.15f); val hz = pz(0.05f, -0.15f)
            limb(o, px(-0.02f, -0.15f), y + 1.95f, pz(-0.02f, -0.15f), hx, y + 2.9f, hz, 0.07f, 0.06f, 6)
            limb(o, hx, y + 2.85f, hz, hx, y + 3.2f, hz, 0.05f, 0.06f, 6)
            prism(out(hx, hz, Surface.FLAME), hx, hz, 0.09f, 0.01f, y + 3.2f, y + 3.5f, 6)
            limb(o, px(0.08f, -0.2f), y + 2.7f, pz(0.08f, -0.2f), px(0.25f, -0.25f), y + 1.4f, pz(0.25f, -0.25f), 0.17f, 0.1f, 6)
            // Her left arm round the young man's shoulders.
            limb(o, px(-0.38f, -0.15f), y + 1.95f, pz(-0.38f, -0.15f), px(-0.78f, -0.05f), y + 1.75f, pz(-0.78f, -0.05f), 0.06f, 0.05f, 6)
            // The young man, 2.0 m, at the rock's edge on her left; his left forearm is gone.
            val mx = -0.85f; val mf = -0.05f
            for (side in floatArrayOf(-0.1f, 0.1f)) limb(o, px(mx + side, mf), y, pz(mx + side, mf), px(mx + side * 0.6f, mf), y + 0.95f, pz(mx + side * 0.6f, mf), 0.08f, 0.1f, 6)
            limb(o, px(mx, mf), y + 0.95f, pz(mx, mf), px(mx, mf), y + 1.62f, pz(mx, mf), 0.17f, 0.21f, 8)
            sphere(o, px(mx, mf), y + 1.77f, pz(mx, mf), 0.12f, 4, 8, hemisphere = false)
            limb(o, px(mx - 0.22f, mf), y + 1.58f, pz(mx - 0.22f, mf), px(mx - 0.42f, mf + 0.1f), y + 0.95f, pz(mx - 0.42f, mf + 0.1f), 0.06f, 0.05f, 6)
            limb(o, px(mx + 0.22f, mf), y + 1.58f, pz(mx + 0.22f, mf), px(mx + 0.3f, mf + 0.1f), y + 1.3f, pz(mx + 0.3f, mf + 0.1f), 0.06f, 0.055f, 6)
            // The fallen man at the front right corner, propped up, reaching up to her.
            val p = 1.9f
            limb(o, px(0.2f, 0.8f), p + 0.15f, pz(0.2f, 0.8f), px(1.1f, 0.95f), p + 0.15f, pz(1.1f, 0.95f), 0.11f, 0.13f, 6)
            limb(o, px(1.1f, 0.95f), p + 0.15f, pz(1.1f, 0.95f), px(1.45f, 0.85f), p + 0.75f, pz(1.45f, 0.85f), 0.19f, 0.17f, 8)
            sphere(o, px(1.5f, 0.82f), p + 0.92f, pz(1.5f, 0.82f), 0.12f, 4, 8, hemisphere = false)
            limb(o, px(1.55f, 1f), p + 0.45f, pz(1.55f, 1f), px(1.7f, 1.2f), p, pz(1.7f, 1.2f), 0.05f, 0.05f, 6)
            limb(o, px(1.35f, 0.8f), p + 0.7f, pz(1.35f, 0.8f), px(0.9f, 0.55f), p + 1.05f, pz(0.9f, 0.55f), 0.055f, 0.05f, 6)
            // And one crouched behind the rock, head bowed.
            val kx = px(-0.9f, -0.8f); val kz = pz(-0.9f, -0.8f)
            clump(o, kx, p + 0.4f, kz, 0.4f, 4, 8, 3)
            sphere(o, px(-0.75f, -0.55f), p + 0.7f, pz(-0.75f, -0.55f), 0.12f, 4, 8, hemisphere = false)
        }

        /**
         * The Roman columns of the Cardo Maximus (re-erected by Riad Al Solh): grey granite shafts
         * about 6 m tall on white marble Attic bases, 3.3 m apart along a kerb of limestone
         * blocks; only two keep their Corinthian capitals, one still carrying a broken block. A
         * fifth stands broken behind them, and fallen drums lie at their feet.
         */
        private fun addColumns(b: CityMap.Building) {
            val bx = box(b)
            val vx = -bx.uz; val vz = bx.ux
            fun px(u: Float, v: Float) = bx.cx + bx.ux * u + vx * v
            fun pz(u: Float, v: Float) = bx.cz + bx.uz * u + vz * v
            block(Surface.LIMESTONE_BLOCK, rect(bx.cx, bx.cz, bx.ux, bx.uz, 8f, 0.6f), 0f, 0.4f)
            fun column(x: Float, z: Float, y: Float, top: Float) {
                block(Surface.MARBLE_WHITE, rect(x, z, bx.ux, bx.uz, 0.45f, 0.45f), y, y + 0.25f)
                lathe(out(x, z, Surface.MARBLE_WHITE), x, z, floatArrayOf(0.42f, y + 0.25f, 0.42f, y + 0.38f, 0.32f, y + 0.45f, 0.36f, y + 0.5f, 0.36f, y + 0.6f), 12)
                prism(out(x, z, Surface.GRANITE), x, z, 0.31f, 0.27f + 0.04f * (1f - (top - y) / 5.6f).coerceIn(0f, 1f), y + 0.6f, top, 12)
            }
            for ((k, u) in floatArrayOf(-4.95f, -1.65f, 1.65f, 4.95f).withIndex()) {
                val x = px(u, 0f); val z = pz(u, 0f)
                column(x, z, 0.4f, 6f)
                if (k == 1 || k == 2) {
                    prism(out(x, z, Surface.MARBLE_WHITE), x, z, 0.3f, 0.45f, 6f, 6.7f, 12)
                    block(Surface.MARBLE_WHITE, rect(x, z, bx.ux, bx.uz, 0.45f, 0.45f), 6.7f, 6.85f)
                }
                if (k == 2) block(Surface.MARBLE_WHITE, rect(x, z, bx.ux, bx.uz, 0.55f, 0.25f), 6.85f, 7.6f)
            }
            // The broken fifth, behind; two fallen drums and a stray base block in front.
            column(px(-3.2f, -3.5f), pz(-3.2f, -3.5f), 0f, 3.5f)
            val o = out(bx.cx, bx.cz, Surface.GRANITE)
            limb(o, px(-2.6f, 1.6f), 0.3f, pz(-2.6f, 1.6f), px(-0.8f, 1.9f), 0.3f, pz(-0.8f, 1.9f), 0.3f, 0.3f, 12)
            limb(o, px(2.2f, 1.9f), 0.3f, pz(2.2f, 1.9f), px(3.9f, 2.7f), 0.3f, pz(3.9f, 2.7f), 0.3f, 0.3f, 12)
            block(Surface.MARBLE_WHITE, rect(px(0.6f, 2.3f), pz(0.6f, 2.3f), bx.ux, bx.uz, 0.45f, 0.4f), 0f, 0.5f)
        }

        /**
         * AUB's College Hall (rebuilt 1999 as the 1873 original): yellow sandstone with white string
         * courses, three storeys of round-headed windows in pairs, a pointed-arch arcade along the
         * ground floor, a low red-tiled hipped roof, and its square clock tower at the middle of the
         * front: three arched openings a side, a white clock on each face, and a flat parapet top.
         */
        private fun addCollegeHall(b: CityMap.Building) {
            val bx = box(b)
            val (cx, cz) = centroid(b.pts)
            val h = b.height
            walls(out(cx, cz, Surface.AUB_STONE), b.pts, 0f, h)
            for (y in floatArrayOf(4.2f, 8.2f, 12.2f)) cornice(out(cx, cz, Surface.TRIM_WHITE), b.pts, y)
            cornice(out(cx, cz, Surface.TRIM_WHITE), b.pts, h)
            flatPolygon(out(cx, cz, Surface.ROOF), b.pts, h, span(Surface.ROOF))
            if (bx.fill > 0.7f) hipRoof(bx, h, 0.47f)
            val all = sides(bx)
            val longSides = if (bx.hu >= bx.hv) listOf(2, 3) else listOf(0, 1)
            val front = longSides.maxBy { k -> all[k].nx * (map.spawnX - bx.cx) + all[k].nz * (map.spawnZ - bx.cz) }
            if (bx.fill > 0.7f) all.forEachIndexed { k, side ->
                val white = out(side.cx, side.cz, Surface.TRIM_WHITE)
                val dark = out(side.cx, side.cz, Surface.WINDOW_DARK)
                // Windows in pairs, 3 m apart, on each upper floor: white surrounds, dark panes.
                val pairs = (side.half * 2f / 7f).toInt().coerceAtLeast(1)
                for (p in 0 until pairs) {
                    val mid = -side.half + (p + 0.5f) * side.half * 2f / pairs
                    for (t in floatArrayOf(mid - 1.5f, mid + 1.5f)) for (y in floatArrayOf(5f, 9f, 13f)) {
                        if (y + 2.6f > h - 0.3f) continue
                        archOn(white, side, t, 1.8f, y - 0.15f, y + 1.85f, 0.03f)
                        archOn(dark, side, t, 1.4f, y, y + 1.9f, 0.06f)
                    }
                }
                // The arcade along the front's ground floor.
                if (k == front) {
                    val n = (side.half * 2f * 0.6f / 3.6f).toInt().coerceIn(3, 8)
                    for (i in 0 until n) archOn(out(side.cx, side.cz, Surface.ARCH_SHADOW), side, (i - (n - 1) / 2f) * 3.6f, 3f, 0.2f, 3.6f, 0.06f)
                } else for (t in floatArrayOf(-side.half * 0.5f, 0f, side.half * 0.5f)) archOn(dark, side, t, 1.4f, 1f, 2.9f, 0.06f)
            }
            // The clock tower, half into the building at the middle of the front.
            val f = all[front]
            val tx = f.x(0f, -2.5f); val tz = f.z(0f, -2.5f)
            val tower = Box(tx, tz, f.ax, f.az, 2.5f, 2.5f, 1f)
            val stone = Surface.AUB_STONE
            block(stone, rect(tx, tz, f.ax, f.az, 2.5f, 2.5f), 0f, h + 5.5f)
            block(Surface.TRIM_WHITE, rect(tx, tz, f.ax, f.az, 2.6f, 2.6f), h + 2.6f, h + 2.85f)
            block(Surface.TRIM_WHITE, rect(tx, tz, f.ax, f.az, 2.65f, 2.65f), h + 5.5f, h + 5.8f)
            block(stone, rect(tx, tz, f.ax, f.az, 2.5f, 2.5f), h + 5.8f, h + 9.1f)
            block(Surface.TRIM_WHITE, rect(tx, tz, f.ax, f.az, 2.8f, 2.8f), h + 9.1f, h + 9.45f)
            block(stone, rect(tx, tz, f.ax, f.az, 2.55f, 2.55f), h + 9.45f, h + 10.4f)
            block(Surface.TRIM_WHITE, rect(tx, tz, f.ax, f.az, 2.65f, 2.65f), h + 10.4f, h + 10.6f)
            for (side in sides(tower)) {
                val dark = out(side.cx, side.cz, Surface.WINDOW_DARK)
                for (t in floatArrayOf(-1.2f, 0f, 1.2f)) archOn(dark, side, t, 0.9f, h + 3.1f, h + 4.85f, 0.05f)
                clock(side.x(0f, 0.05f), h + 7.45f, side.z(0f, 0.05f), side.nx, side.nz, 0.9f)
            }
        }

        private fun addBuilding(b: CityMap.Building, index: Int) {
            when (b.kind) {
                CityMap.BUILDING_ROCK -> return addSeaStack(b, index)
                CityMap.BUILDING_GRAND_MOSQUE -> return addGrandMosque(b)
                CityMap.BUILDING_CLOCK_TOWER -> return addClockTower(b)
                CityMap.BUILDING_HAMIDIYYEH -> return addHamidiyyeh(b)
                CityMap.BUILDING_EGG -> return addEgg(b)
                CityMap.BUILDING_MURR -> return addMurr(b, index)
                CityMap.BUILDING_HOLIDAY_INN -> return addHolidayInn(b, index)
                CityMap.BUILDING_LIGHTHOUSE -> return addLighthouse(b)
                CityMap.BUILDING_STATUE -> return addStatue(b)
                CityMap.BUILDING_COLUMNS -> return addColumns(b)
                CityMap.BUILDING_COLLEGE_HALL -> return addCollegeHall(b)
            }
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
            // (A mountain village has the odd shop, not a shop under every house.)
            val shops = b.kind == CityMap.BUILDING_GENERIC && b.minHeight < 0.5f &&
                b.height >= CityTextures.SHOP_HEIGHT + 3f && rnd.nextFloat() < shopChance * (if (look == CityLook.VILLAGE) 0.15f else 1f)
            var wallBottom = b.minHeight
            if (shops) {
                walls(out(cx, cz, Surface.SHOPFRONT), b.pts, b.minHeight, CityTextures.SHOP_HEIGHT, CityTextures.SHOP_SPAN, CityTextures.SHOP_HEIGHT)
                wallBottom = CityTextures.SHOP_HEIGHT
                addSigns(b, rnd)
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
            if (b.kind == CityMap.BUILDING_GENERIC && b !in withLadder && tileRoof(b, wall, rnd)) {
                val bx = box(b)
                if (bx.fill > 0.72f) hipRoof(bx, b.height, pitch = 0.5f + rnd.nextFloat() * 0.15f)
            }

            when (b.kind) {
                CityMap.BUILDING_MOSQUE -> addMosque(b)
                CityMap.BUILDING_CHURCH -> addBellTower(b)
            }
        }

        /**
         * Whether [b] has a red tile roof, as the place builds them: nearly every house in a
         * mountain village; many of the restored Ottoman and mandate stone buildings Downtown and
         * in the Souks; the odd old house between Hamra's and the seafront's blocks.
         */
        private fun tileRoof(b: CityMap.Building, wall: Surface, rnd: Random): Boolean {
            if (b.area > 700f) return false
            val chance = when (look) {
                CityLook.VILLAGE -> if (b.height <= 14f) 0.9f else 0f
                CityLook.SANDSTONE, CityLook.OLD_STONE -> if (wall == Surface.WALL_SANDSTONE && b.height <= 16f) 0.45f else 0f
                else -> if (b.height <= 10f && wall != Surface.WALL_GLASS) 0.25f else 0f
            }
            return rnd.nextFloat() < chance
        }

        /**
         * Shop signs over a shop row: a board over each shop on walls facing the street, in Arabic
         * and French or English (see CityTextures.signs). Hamra's busy commercial street has one
         * over nearly every shop; quieter places fewer.
         */
        private fun addSigns(b: CityMap.Building, rnd: Random) {
            val density = when (look) {
                CityLook.MIDRISE -> 0.95f
                CityLook.OLD_STONE -> 0.8f
                CityLook.SEAFRONT -> 0.5f
                CityLook.SANDSTONE -> 0.45f
                CityLook.VILLAGE -> 0.6f
                CityLook.MIXED -> 0.5f
            }
            val o = out(b.centerX, b.centerZ, Surface.SIGN)
            val p = b.pts
            val n = p.size / 2
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ax = p[2 * i]; val az = p[2 * i + 1]; val ex = p[2 * j]; val ez = p[2 * j + 1]
                val len = hypot(ex - ax, ez - az)
                if (len < 3.5f) continue
                // Only on walls with open street in front of them.
                val nx = (ez - az) / len; val nz = -(ex - ax) / len
                val mx = (ax + ex) / 2f + nx * 5f; val mz = (az + ez) / 2f + nz * 5f
                if (map.isInsideBuilding(mx, map.groundAt(mx, mz) + 1.5f, mz, 0.5f)) continue
                val shopsHere = (len / CityTextures.SHOP_SPAN * 2f).toInt().coerceAtLeast(1)
                for (k in 0 until shopsHere) {
                    if (rnd.nextFloat() > density) continue
                    val w = (len / shopsHere * (0.7f + rnd.nextFloat() * 0.2f)).coerceAtMost(4.2f)
                    val c = (k + 0.5f) / shopsHere
                    val t0 = c - w / 2f / len; val t1 = c + w / 2f / len
                    val y0 = CityTextures.SHOP_HEIGHT - 1.05f + b.minHeight
                    panel(o, ax + (ex - ax) * t0, az + (ez - az) * t0, ax + (ex - ax) * t1, az + (ez - az) * t1,
                        y0, y0 + (w / 2f).coerceIn(0.7f, 1f), 0.12f, CityTextures.signUv(rnd.nextInt(CityTextures.SIGN_COUNT)))
                }
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

        /**
         * A Beirut neighbourhood mosque (as Emir Assaf, Al-Omari, Al-Da'ouk): light domes of
         * plaster or lead, never blue (none on a small mosque; a row of small ones beside the main
         * dome on a middling one), and one Ottoman pencil minaret flush with a street corner, its
         * single balcony on corbels at four fifths of its height, a narrower lantern above, a short
         * cone cap and a gold crescent. The few really big ones keep four minarets.
         */
        private fun addMosque(b: CityMap.Building) {
            val bx = box(b)
            val seed = ((b.centerX * 7.31f + b.centerZ * 3.17f).toInt() and 0xFFFF)
            val s = sqrt(b.area)
            val dome = if (seed % 3 == 0) Surface.LEAD_DOME else Surface.PLASTER_DOME
            if (b.area >= 300f) {
                val r = (s * if (b.area < 1200f) 0.25f else 0.22f).coerceIn(2.5f, 9f)
                prism(out(bx.cx, bx.cz, Surface.MOSQUE_STONE), bx.cx, bx.cz, r * 1.03f, r * 1.03f, b.height, b.height + r * 0.15f, 16)
                lathe(out(bx.cx, bx.cz, dome), bx.cx, bx.cz, domeProfile(r, b.height + r * 0.15f, r * 0.85f, 8), 16)
                prism(out(bx.cx, bx.cz, Surface.GOLD), bx.cx, bx.cz, 0.1f, 0.02f, b.height + r, b.height + r + 1.2f, 6)
                // Small domes along one long side of a middling mosque.
                if (b.area < 1200f && bx.hu > r * 1.6f) {
                    val small = r * 0.45f
                    val vx = -bx.uz; val vz = bx.ux
                    for (k in -1..1 step 2) {
                        val x = bx.cx + bx.ux * k * (r + small * 1.3f) + vx * (bx.hv - small * 1.4f)
                        val z = bx.cz + bx.uz * k * (r + small * 1.3f) + vz * (bx.hv - small * 1.4f)
                        if (CityMap.inside(b.pts, x, z)) lathe(out(x, z, dome), x, z, domeProfile(small, b.height, small, 6), 12)
                    }
                }
            }
            val big = b.area > 1200f
            val corners = listOf(1f to 1f, -1f to 1f, -1f to -1f, 1f to -1f)
            val chosen = if (big) corners else listOf(corners[seed % 4])
            val hm = (b.height + 18f).coerceIn(22f, 35f) * if (big) 1.3f else 1f
            val mr = if (big) 1.4f else 1.15f
            for ((su, sv) in chosen) {
                val iu = bx.hu - mr; val iv = bx.hv - mr
                val mx = bx.cx + bx.ux * su * iu - bx.uz * sv * iv; val mz = bx.cz + bx.uz * su * iu + bx.ux * sv * iv
                val o = out(mx, mz, Surface.MOSQUE_STONE)
                prism(o, mx, mz, mr, mr * 0.92f, 0f, hm * 0.8f, 12)
                // The balcony on its corbels, its parapet, and the narrower lantern above.
                lathe(o, mx, mz, floatArrayOf(mr * 0.95f, hm * 0.8f - 0.7f, mr * 1.5f, hm * 0.8f), 12)
                prism(o, mx, mz, mr * 1.5f, mr * 1.5f, hm * 0.8f, hm * 0.8f + 1f, 12)
                prism(o, mx, mz, mr * 0.8f, mr * 0.8f, hm * 0.8f + 1f, hm, 12)
                prism(out(mx, mz, Surface.CAP_GREY), mx, mz, mr * 0.95f, 0.04f, hm, hm + mr * 2.8f, 12)
                prism(out(mx, mz, Surface.GOLD), mx, mz, 0.06f, 0.02f, hm + mr * 2.6f, hm + mr * 2.8f + 1.2f, 6)
                sphere(out(mx, mz, Surface.GOLD), mx, hm + mr * 2.8f + 0.7f, mz, 0.18f, 3, 6, hemisphere = false)
            }
        }

        /**
         * A Beirut church's bell tower: square and in line with the church, at the front, about
         * twice the nave's height; a dark round-arched belfry opening on each face between
         * cornices, then a red-tiled pyramid, a small dome on an octagonal drum or a stone spire,
         * and an iron cross.
         */
        private fun addBellTower(b: CityMap.Building) {
            val bx = box(b)
            val seed = ((b.centerX * 5.13f + b.centerZ * 9.71f).toInt() and 0xFFFF)
            val t = (sqrt(b.area) * 0.18f).coerceIn(3f, 6f) / 2f
            val top = maxOf(2f * b.height, 18f).coerceIn(15f, 35f)
            // At the front (the short end towards the start), centred on it, in line with the walls.
            val ends = sides(bx).take(2)
            val end = ends.maxBy { it.nx * (map.spawnX - bx.cx) + it.nz * (map.spawnZ - bx.cz) }
            val tx = end.x(0f, -t); val tz = end.z(0f, -t)
            if (!CityMap.inside(b.pts, tx, tz)) return
            val stone = Surface.CHURCH_STONE
            val belfry = top * 0.8f
            block(stone, rect(tx, tz, bx.ux, bx.uz, t, t), 0f, top)
            block(Surface.CORNICE, rect(tx, tz, bx.ux, bx.uz, t * 1.15f, t * 1.15f), belfry - 0.35f, belfry)
            block(Surface.CORNICE, rect(tx, tz, bx.ux, bx.uz, t * 1.15f, t * 1.15f), top, top + 0.35f)
            for (side in sides(Box(tx, tz, bx.ux, bx.uz, t, t, 1f))) {
                archOn(out(side.cx, side.cz, Surface.BELFRY_DARK), side, 0f, t, belfry + 0.4f, belfry + 0.4f + (top - belfry) * 0.6f - t / 2f, 0.05f)
            }
            val capTop: Float = when (seed % 10) {
                in 0..4 -> { hipRoof(Box(tx, tz, bx.ux, bx.uz, t, t, 1f), top + 0.35f, 1.2f, 0.15f); top + 0.35f + t * 1.2f }
                in 5..7 -> {
                    prism(out(tx, tz, stone), tx, tz, t * 0.85f, t * 0.85f, top + 0.35f, top + 0.35f + t * 0.8f, 8)
                    lathe(out(tx, tz, Surface.LEAD_DOME), tx, tz, domeProfile(t * 0.8f, top + 0.35f + t * 0.8f, t * 0.8f, 6), 12)
                    top + 0.35f + t * 1.6f
                }
                else -> { hipRoof(Box(tx, tz, bx.ux, bx.uz, t, t, 1f), top + 0.35f, 3f, 0.1f); top + 0.35f + t * 3f }
            }
            val iron = Surface.IRON_DARK
            block(iron, rect(tx, tz, bx.ux, bx.uz, 0.07f, 0.07f), capTop - 0.2f, capTop + 1.8f)
            block(iron, rect(tx, tz, bx.ux, bx.uz, 0.45f, 0.07f), capTop + 1.05f, capTop + 1.2f)
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
         * A stone pine, as on the Chouf's hillsides: a tall bare trunk leaning a little, and a
         * broad, flat, dark umbrella crown on top.
         */
        private fun addPine(t: CityMap.Tree) {
            val s = t.size
            val rnd = Random((t.x * 19 + t.z * 23).toInt())
            val h = (7f + rnd.nextFloat() * 3f) * s
            val lean = (rnd.nextFloat() - 0.5f) * 1.2f * s
            val tx = t.x + lean; val tz = t.z + lean * 0.5f
            val bark = out(t.x, t.z, Surface.TRUNK)
            limb(bark, t.x, 0f, t.z, tx, h, tz, 0.24f * s, 0.15f * s, 6, uRepeat = 1f, vSpan = 2f)
            val o = out(t.x, t.z, Surface.PINE)
            val r = (2.6f + rnd.nextFloat() * 0.8f) * s
            // A flat crown: a few squashed clumps side by side.
            clump(o, tx, h + r * 0.15f, tz, r, 3, 8, rnd.nextInt())
            for (k in 0 until 3) {
                val a = rnd.nextFloat() * 2f * PI.toFloat()
                clump(o, tx + cos(a) * r * 0.6f, h + r * 0.05f, tz + sin(a) * r * 0.6f, r * 0.6f, 3, 6, rnd.nextInt())
            }
        }

        /** An olive tree: a short, twisted trunk splitting low, and a loose silver-green crown. */
        private fun addOlive(t: CityMap.Tree) {
            val s = t.size * 0.85f
            val rnd = Random((t.x * 7 + t.z * 37).toInt())
            val bark = out(t.x, t.z, Surface.TRUNK)
            val o = out(t.x, t.z, Surface.LEAVES_OLIVE)
            limb(bark, t.x, 0f, t.z, t.x + 0.2f * s, 1.1f * s, t.z, 0.3f * s, 0.22f * s, 6, uRepeat = 1f, vSpan = 2f)
            for (k in 0 until 3) {
                val a = rnd.nextFloat() * 2f * PI.toFloat()
                val ex = t.x + cos(a) * 1.1f * s; val ez = t.z + sin(a) * 1.1f * s
                val ey = (2.1f + rnd.nextFloat() * 0.6f) * s
                limb(bark, t.x + 0.2f * s, 1.1f * s, t.z, ex, ey, ez, 0.14f * s, 0.07f * s, 5, uRepeat = 1f, vSpan = 2f)
                clump(o, ex, ey + 0.6f * s, ez, (1.2f + rnd.nextFloat() * 0.3f) * s, 3, 6, rnd.nextInt())
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

        /**
         * A solid turned round an upright axis at ([cx], [cz]): [profile] is (radius, height) pairs
         * from the bottom up, each ring [sides]-sided; a radius of 0 closes it to a point. Smooth
         * normals, from the profile's slope. For domes of any shape, minarets and towers.
         */
        private fun lathe(o: Floats, cx: Float, cz: Float, profile: FloatArray, sides: Int) {
            val n = profile.size / 2
            for (i in 0 until n - 1) {
                val r0 = profile[2 * i]; val y0 = profile[2 * i + 1]
                val r1 = profile[2 * i + 2]; val y1 = profile[2 * i + 3]
                // The side's outward normal in the profile's plane: across the slope.
                val dr = r1 - r0; val dy = y1 - y0
                val l = hypot(dr, dy).takeIf { it > 1e-5f } ?: continue
                val nh = dy / l; val ny = -dr / l
                for (k in 0 until sides) {
                    val a0 = 2f * PI.toFloat() * k / sides; val a1 = 2f * PI.toFloat() * (k + 1) / sides
                    val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
                    val u = 0.02f
                    o.vertex(cx + c1 * r0, y0, cz + s1 * r0, c1 * nh, ny, s1 * nh, u, u)
                    o.vertex(cx + c0 * r0, y0, cz + s0 * r0, c0 * nh, ny, s0 * nh, u, u)
                    o.vertex(cx + c0 * r1, y1, cz + s0 * r1, c0 * nh, ny, s0 * nh, u, u)
                    o.vertex(cx + c1 * r0, y0, cz + s1 * r0, c1 * nh, ny, s1 * nh, u, u)
                    o.vertex(cx + c0 * r1, y1, cz + s0 * r1, c0 * nh, ny, s0 * nh, u, u)
                    o.vertex(cx + c1 * r1, y1, cz + s1 * r1, c1 * nh, ny, s1 * nh, u, u)
                }
            }
            // A flat top where the profile ends open.
            val rt = profile[2 * n - 2]; val yt = profile[2 * n - 1]
            if (rt > 0.05f) for (k in 0 until sides) {
                val a0 = 2f * PI.toFloat() * k / sides; val a1 = 2f * PI.toFloat() * (k + 1) / sides
                o.vertex(cx, yt, cz, 0f, 1f, 0f, 0.02f, 0.02f)
                o.vertex(cx + cos(a1) * rt, yt, cz + sin(a1) * rt, 0f, 1f, 0f, 0.02f, 0.02f)
                o.vertex(cx + cos(a0) * rt, yt, cz + sin(a0) * rt, 0f, 1f, 0f, 0.02f, 0.02f)
            }
        }

        /**
         * A dome's profile for [lathe]: radius [r] at height [y], rising [h] to its tip, in
         * [steps] rings. [shape] 0 is a round (elliptical) dome; above 0 it swells out first and
         * comes to a point, like an onion (Ottoman and Mamluk domes, minaret caps).
         */
        private fun domeProfile(r: Float, y: Float, h: Float, steps: Int = 8, shape: Float = 0f): FloatArray {
            val out = FloatArray((steps + 1) * 2)
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val a = t * PI.toFloat() / 2f
                // Round: a quarter ellipse. Onion: wider by [shape] partway up, rising evenly to a point.
                out[2 * i] = if (i == steps) 0f else r * cos(a) * (1f + shape * sin(t * PI.toFloat()))
                out[2 * i + 1] = y + h * (if (shape > 0f) t else sin(a))
            }
            return out
        }

        /**
         * A round-topped opening (window, arcade, belfry) on the wall from (ax, az) to (bx, bz)
         * (outside to its right), [out] metres proud: its sides from [y0] up to [spring], then a
         * half-round arch over it. Drawn flat in [o]'s colour (dark for an opening).
         */
        private fun arch(o: Floats, ax: Float, az: Float, bx: Float, bz: Float, y0: Float, spring: Float, out: Float) {
            val len = hypot(bx - ax, bz - az)
            if (len < 1e-3f) return
            val nx = (bz - az) / len; val nz = -(bx - ax) / len
            val ux = (bx - ax) / len; val uz = (bz - az) / len
            val r = len / 2f
            val mx = (ax + bx) / 2f + nx * out; val mz = (az + bz) / 2f + nz * out
            fun p(u: Float, y: Float) = floatArrayOf(mx + ux * u, y, mz + uz * u)
            // Seen from outside, b (+u) is on the left: wind each triangle so it faces out.
            fun tri(a: FloatArray, b: FloatArray, c: FloatArray) {
                for (q in arrayOf(a, c, b)) o.vertex(q[0], q[1], q[2], nx, 0f, nz, 0.02f, 0.02f)
            }
            if (spring > y0) {
                tri(p(-r, y0), p(r, y0), p(r, spring))
                tri(p(-r, y0), p(r, spring), p(-r, spring))
            }
            val steps = 8
            for (k in 0 until steps) {
                val a0 = PI.toFloat() * k / steps; val a1 = PI.toFloat() * (k + 1) / steps
                tri(p(0f, spring), p(cos(a0) * r, spring + sin(a0) * r), p(cos(a1) * r, spring + sin(a1) * r))
            }
        }

        /** Triangle [tri] of [pts] (x, y, z each), its corners ordered so it faces towards (nx, ny, nz). */
        private fun facing(pts: FloatArray, tri: IntArray, nx: Float, ny: Float, nz: Float): IntArray {
            val (a, b, c) = tri
            val e1x = pts[3 * b] - pts[3 * a]; val e1y = pts[3 * b + 1] - pts[3 * a + 1]; val e1z = pts[3 * b + 2] - pts[3 * a + 2]
            val e2x = pts[3 * c] - pts[3 * a]; val e2y = pts[3 * c + 1] - pts[3 * a + 1]; val e2z = pts[3 * c + 2] - pts[3 * a + 2]
            val fx = e1y * e2z - e1z * e2y; val fy = e1z * e2x - e1x * e2z; val fz = e1x * e2y - e1y * e2x
            return if (fx * nx + fy * ny + fz * nz >= 0f) tri else intArrayOf(a, c, b)
        }

        private fun centroid(ring: FloatArray): Pair<Float, Float> {
            var sx = 0f; var sz = 0f
            val n = ring.size / 2
            for (i in 0 until n) { sx += ring[2 * i]; sz += ring[2 * i + 1] }
            return sx / n to sz / n
        }
    }
}
