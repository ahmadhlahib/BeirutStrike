package com.example.beirutrun.solo

import com.example.beirutrun.city.CityMap
import java.util.PriorityQueue
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * The streets as a network of corners (the points of the map's roads and pedestrian streets
 * inside the play area) joined where a road runs between them, for bots to find their way:
 * the nearest corner to a point ([nearest]) and the shortest way between two ([route]).
 */
class StreetGraph(city: CityMap) {
    private val xs = ArrayList<Float>()
    private val zs = ArrayList<Float>()
    private val links = ArrayList<MutableList<Int>>()
    private val ids = HashMap<Long, Int>()
    private val cells = HashMap<Long, MutableList<Int>>()

    init {
        for (road in city.roads) {
            if (road.kind > CityMap.ROAD_PEDESTRIAN && road.kind != CityMap.ROAD_TRACK) continue
            var last = -1
            for (i in 0 until road.pts.size / 2) {
                val x = road.pts[2 * i]; val z = road.pts[2 * i + 1]
                if (!city.inPlayArea(x, z)) { last = -1; continue }
                val node = node(x, z)
                if (last >= 0 && last != node) {
                    if (node !in links[last]) links[last] += node
                    if (last !in links[node]) links[node] += last
                }
                last = node
            }
        }
    }

    val size get() = xs.size
    fun x(n: Int) = xs[n]
    fun z(n: Int) = zs[n]
    fun neighbours(n: Int): List<Int> = links[n]

    /** The corner nearest (x, z) that leads somewhere, or -1 if there are none. */
    fun nearest(x: Float, z: Float): Int {
        val cx = floor(x / CELL).toLong(); val cz = floor(z / CELL).toLong()
        var best = -1
        var bestD = Float.MAX_VALUE
        for (ring in 0..RINGS) {
            for (dx in -ring..ring) for (dz in -ring..ring) {
                if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) != ring) continue
                for (n in cells[cell(cx + dx, cz + dz)] ?: continue) {
                    if (links[n].isEmpty()) continue
                    val d = hypot(xs[n] - x, zs[n] - z)
                    if (d < bestD) { bestD = d; best = n }
                }
            }
            // Anything found within this ring is nearer than anything further out.
            if (best >= 0 && bestD <= ring * CELL) return best
        }
        if (best >= 0) return best
        for (n in xs.indices) {
            if (links[n].isEmpty()) continue
            val d = hypot(xs[n] - x, zs[n] - z)
            if (d < bestD) { bestD = d; best = n }
        }
        return best
    }

    /**
     * The corners on the shortest way from [from] to [to] (A*, straight-line distance as the
     * guide), not counting [from]; empty if they're the same corner, null if there's no way.
     */
    fun route(from: Int, to: Int): List<Int>? {
        if (from < 0 || to < 0) return null
        if (from == to) return emptyList()
        val cost = HashMap<Int, Float>()
        val came = HashMap<Int, Int>()
        val open = PriorityQueue<Pair<Int, Float>>(compareBy { it.second })
        cost[from] = 0f
        open += from to guess(from, to)
        var visited = 0
        while (open.isNotEmpty() && visited++ < MAX_VISITS) {
            val (n, _) = open.poll()!!
            if (n == to) {
                val path = ArrayList<Int>()
                var at = to
                while (at != from) { path += at; at = came.getValue(at) }
                path.reverse()
                return path
            }
            val here = cost.getValue(n)
            for (m in links[n]) {
                val c = here + hypot(xs[m] - xs[n], zs[m] - zs[n])
                if (c < (cost[m] ?: Float.MAX_VALUE)) {
                    cost[m] = c
                    came[m] = n
                    open += m to c + guess(m, to)
                }
            }
        }
        return null
    }

    private fun guess(a: Int, b: Int) = hypot(xs[a] - xs[b], zs[a] - zs[b])

    private fun node(x: Float, z: Float): Int = ids.getOrPut(key(x, z)) {
        xs.add(x); zs.add(z); links.add(ArrayList(3))
        val n = xs.size - 1
        cells.getOrPut(cell(floor(x / CELL).toLong(), floor(z / CELL).toLong())) { ArrayList(4) } += n
        n
    }

    private fun key(x: Float, z: Float) = cell((x * 8f).roundToLong(), (z * 8f).roundToLong())
    private fun cell(a: Long, b: Long) = (a shl 32) xor (b and 0xFFFFFFFFL)

    private companion object {
        const val CELL = 20f
        const val RINGS = 6
        const val MAX_VISITS = 20_000
    }
}
