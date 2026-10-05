package com.example.beirutrun.city

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * How the roads join. Roads that meet share a point (an OpenStreetMap node), so a point used by
 * more than one road, or twice by one, is where they join: crossing markings stop short of
 * junctions, crossings are painted across the roads leading into them, and traffic turns there.
 * Plain Kotlin, so it can be built on a background thread and unit tested.
 */
class RoadNetwork(val roads: List<CityMap.Road>) {

    /** Point [vertex] of road [road]. */
    class Stop(val road: Int, val vertex: Int)

    /**
     * Where three or more road ends meet (a crossroads or a T, not one road simply continuing
     * as another): [half] is half the width of the widest road there.
     */
    class Junction(val x: Float, val z: Float, val half: Float, val stops: List<Stop>)

    private val nodes = HashMap<Long, MutableList<Stop>>()
    val junctions: List<Junction>
    private val junctionCells = HashMap<Long, MutableList<Junction>>()

    init {
        roads.forEachIndexed { r, road ->
            for (v in 0 until road.pts.size / 2) {
                nodes.getOrPut(key(road.pts[2 * v], road.pts[2 * v + 1])) { ArrayList(2) } += Stop(r, v)
            }
        }
        val found = ArrayList<Junction>()
        for (stops in nodes.values) {
            if (stops.size < 2) continue
            // Arms leaving the point: a road passing through gives two, a road ending there one.
            var arms = 0
            var half = 0f
            for (s in stops) {
                val n = roads[s.road].pts.size / 2
                arms += if (s.vertex == 0 || s.vertex == n - 1) 1 else 2
                half = max(half, roads[s.road].width / 2f)
            }
            if (arms < 3) continue
            val road = roads[stops[0].road]
            val j = Junction(road.pts[2 * stops[0].vertex], road.pts[2 * stops[0].vertex + 1], half, stops)
            found += j
            junctionCells.getOrPut(cell(floor(j.x / CELL).toLong(), floor(j.z / CELL).toLong())) { ArrayList(1) } += j
        }
        junctions = found
    }

    /** Every road point at (x, z). */
    fun at(x: Float, z: Float): List<Stop> = nodes[key(x, z)] ?: emptyList()

    /** True when (x, z) is within a junction's widest road (plus [extra] metres) of its centre. */
    fun nearJunction(x: Float, z: Float, extra: Float): Boolean {
        val cx = floor(x / CELL).toLong(); val cz = floor(z / CELL).toLong()
        for (dx in -1L..1L) for (dz in -1L..1L) {
            val list = junctionCells[cell(cx + dx, cz + dz)] ?: continue
            for (j in list) if (hypot(j.x - x, j.z - z) < j.half + extra) return true
        }
        return false
    }

    private fun key(x: Float, z: Float): Long = cell((x * 8f).roundToLong(), (z * 8f).roundToLong())

    private fun cell(a: Long, b: Long): Long = (a shl 32) xor (b and 0xFFFFFFFFL)

    private companion object {
        const val CELL = 32f
    }
}
