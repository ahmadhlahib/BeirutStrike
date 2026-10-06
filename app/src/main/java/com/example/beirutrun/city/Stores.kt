package com.example.beirutrun.city

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * An arms store: a small kiosk at the side of a street at ([x], [z]), its counter facing the
 * street along ([fx], [fz]) (unit). Players walk up to it to buy magazines, medkits, a scope and
 * grenades (see CityActivity's shop).
 */
class Store(val x: Float, val z: Float, val fx: Float, val fz: Float) {
    /** The heading of the counter's front (see CityRenderer.heading: 0 faces -z). */
    val facing get() = atan2(fx, -fz)

    /** Where a player stands to shop: in front of the counter. */
    val frontX get() = x + fx * FRONT
    val frontZ get() = z + fz * FRONT

    companion object {
        /** Half the kiosk's width and depth, metres (players can't walk through it). */
        const val HALF = 1.1f
        /** How far in front of the kiosk's middle a shopper stands. */
        const val FRONT = 2f
        /** How near a player must be to shop. */
        const val REACH = 3.5f
    }
}

/**
 * Picks where the stores go, from the map alone: the same spots on every phone in a room. Each is
 * beside a street (a road or pedestrian street), on clear ground in the play area, facing the
 * street, and they're spread out: the first one nearest the start point, then each as far from
 * the others as the play area allows. Plain Kotlin, so it can be unit tested.
 */
object Stores {
    fun place(city: CityMap): List<Store> {
        val candidates = ArrayList<Store>()
        for (road in city.roads) {
            if (road.kind > CityMap.ROAD_PEDESTRIAN) continue
            val n = road.pts.size / 2
            for (i in 0 until n - 1) {
                val ax = road.pts[2 * i]; val az = road.pts[2 * i + 1]
                val bx = road.pts[2 * i + 2]; val bz = road.pts[2 * i + 3]
                val len = hypot(bx - ax, bz - az)
                if (len < MIN_SEGMENT) continue
                // Halfway along the stretch, set back from the road's edge on either side.
                val mx = (ax + bx) / 2f; val mz = (az + bz) / 2f
                val dx = (bx - ax) / len; val dz = (bz - az) / len
                for (side in floatArrayOf(1f, -1f)) {
                    val out = road.width / 2f + SETBACK
                    val nx = -dz * side; val nz = dx * side
                    val x = mx + nx * out; val z = mz + nz * out
                    if (!city.inPlayArea(x, z) || city.isBlocked(x, z, Store.HALF + 0.6f)) continue
                    // The shopper's spot, in front, must be clear too.
                    val s = Store(x, z, -nx, -nz)
                    if (city.isBlocked(s.frontX, s.frontZ, 0.5f)) continue
                    candidates += s
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()
        val spacing = (minOf(city.playMaxX - city.playMinX, city.playMaxZ - city.playMinZ) / 3f).coerceIn(MIN_SPACING, MAX_SPACING)
        val chosen = ArrayList<Store>()
        // First: the one nearest the start, a short walk away.
        chosen += candidates.minBy { kotlin.math.abs(hypot(it.x - city.spawnX, it.z - city.spawnZ) - FIRST_DISTANCE) }
        // Then each as far as possible from those already chosen.
        while (chosen.size < COUNT) {
            val next = candidates.maxBy { c -> chosen.minOf { hypot(it.x - c.x, it.z - c.z) } }
            if (chosen.minOf { hypot(it.x - next.x, it.z - next.z) } < spacing) break
            chosen += next
        }
        return chosen
    }

    /** How many stores a map gets (fewer if the play area is too small to spread them). */
    const val COUNT = 4
    private const val MIN_SEGMENT = 8f
    private const val SETBACK = 2.2f
    private const val FIRST_DISTANCE = 40f
    private const val MIN_SPACING = 60f
    private const val MAX_SPACING = 220f
}
