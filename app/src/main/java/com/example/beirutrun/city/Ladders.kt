package com.example.beirutrun.city

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * A ladder fixed to the outside wall of [building], from the street to its flat roof, to climb
 * up and shoot from there.
 *
 * @property wallX Where it meets the wall (the middle of one of the building's walls).
 * @property nx The wall's outward direction (unit, along the ground).
 */
class Ladder(
    val building: CityMap.Building,
    val wallX: Float, val wallZ: Float,
    val nx: Float, val nz: Float,
) {
    /** The roof's height: the top of the climb. */
    val height get() = building.height

    /** Where a player stands to start climbing, in the street just in front of it. */
    val footX get() = wallX + nx * FOOT
    val footZ get() = wallZ + nz * FOOT

    /** Where a player steps off onto the roof (and starts climbing down). */
    val topX get() = wallX - nx * TOP
    val topZ get() = wallZ - nz * TOP

    /** On the ladder, pressed close against it. */
    val onX get() = wallX + nx * ON
    val onZ get() = wallZ + nz * ON

    /** The heading of someone facing the wall to climb (see CityRenderer.heading). */
    val facingWall get() = atan2(-nx, nz)

    companion object {
        const val FOOT = 0.9f
        const val TOP = 1.1f
        const val ON = 0.42f
    }
}

/**
 * Picks the buildings that get a ladder, from the map alone: the same ones on every phone in a
 * room (it's the same map and the same play area). Ordinary buildings with a flat roof, between
 * one and about seven storeys, with a clear street at the wall's foot and room to stand on top,
 * spread out across the play area.
 */
object Ladders {
    fun place(city: CityMap): List<Ladder> {
        val candidates = city.buildings
            .filter { b ->
                b.kind == CityMap.BUILDING_GENERIC && b.minHeight < 0.5f &&
                    b.height in MIN_HEIGHT..MAX_HEIGHT && b.area in MIN_AREA..MAX_AREA &&
                    city.inPlayArea(b.centerX, b.centerZ)
            }
            // Nearest the start first, so the first ladders are where the players are.
            .sortedBy { hypot(it.centerX - city.spawnX, it.centerZ - city.spawnZ) }
        val chosen = ArrayList<Ladder>()
        for (b in candidates) {
            if (chosen.size >= MAX_LADDERS) break
            if (chosen.any { hypot(it.building.centerX - b.centerX, it.building.centerZ - b.centerZ) < SPACING }) continue
            ladderOn(city, b)?.let(chosen::add)
        }
        return chosen
    }

    /** A ladder up the longest of [b]'s walls that has a clear street in front and roof behind; null if none does. */
    private fun ladderOn(city: CityMap, b: CityMap.Building): Ladder? {
        val p = b.pts
        val n = p.size / 2
        val walls = (0 until n).sortedByDescending { k ->
            val j = (k + 1) % n
            hypot(p[2 * j] - p[2 * k], p[2 * j + 1] - p[2 * k + 1])
        }
        for (k in walls.take(4)) {
            val j = (k + 1) % n
            val ax = p[2 * k]; val az = p[2 * k + 1]; val bx = p[2 * j]; val bz = p[2 * j + 1]
            val len = hypot(bx - ax, bz - az)
            if (len < MIN_WALL) break
            val mx = (ax + bx) / 2f; val mz = (az + bz) / 2f
            // Perpendicular to the wall, turned to point out of the building.
            var nx = -(bz - az) / len; var nz = (bx - ax) / len
            if (CityMap.inside(p, mx + nx * 0.3f, mz + nz * 0.3f)) { nx = -nx; nz = -nz }
            val ladder = Ladder(b, mx, mz, nx, nz)
            val footClear = city.inPlayArea(ladder.footX, ladder.footZ) && !city.isBlocked(ladder.footX, ladder.footZ, 0.45f)
            // Somewhere to stand on top: on the roof, back from its edge, with nothing taller there.
            val roofClear = CityMap.inside(p, ladder.topX, ladder.topZ) &&
                CityMap.edgeDistance(p, ladder.topX, ladder.topZ) > 0.6f &&
                !city.isInsideBuilding(ladder.topX, b.height + 1f, ladder.topZ, 0.4f)
            if (footClear && roofClear) return ladder
        }
        return null
    }

    private const val MAX_LADDERS = 14
    /** At least this far apart (building centres), metres. */
    private const val SPACING = 30f
    private const val MIN_HEIGHT = 4f
    private const val MAX_HEIGHT = 22f
    /** Footprints from a small house to a large block, m². */
    private const val MIN_AREA = 60f
    private const val MAX_AREA = 5_000f
    private const val MIN_WALL = 4f
}
