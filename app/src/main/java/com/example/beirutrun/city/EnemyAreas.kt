package com.example.beirutrun.city

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Where enemies roughly are, for the maps: one red circle per enemy that they're somewhere
 * inside, but not at its middle. Each circle is placed off-centre at random and stays put for a
 * few seconds (or until they walk out of it), so the map gives away the area, never the spot.
 * Plain Kotlin, so it can be unit tested.
 */
class EnemyAreas(private val random: Random = Random) {

    /** A circle on the map: centre ([x], [z]) and [radius], metres. */
    class Area(val x: Float, val z: Float, val radius: Float)

    private class Placed(val area: Area, val until: Long)

    private val placed = HashMap<String, Placed>()

    /**
     * The circles for [enemies] (id, x, z) at [now] ms: each enemy keeps its circle until it's
     * [REFRESH_MS] old or they've walked near its edge, then gets a new one.
     */
    fun update(enemies: List<Triple<String, Float, Float>>, now: Long): List<Area> {
        val here = enemies.mapTo(HashSet()) { it.first }
        placed.keys.retainAll(here)
        return enemies.map { (id, x, z) ->
            val old = placed[id]
            if (old != null && now < old.until && hypot(x - old.area.x, z - old.area.z) < RADIUS * KEEP_INSIDE) {
                old.area
            } else {
                // Off-centre: the middle is up to OFFSET × the radius away from them, any direction.
                val angle = random.nextFloat() * 2f * Math.PI.toFloat()
                val d = RADIUS * OFFSET * sqrt(random.nextFloat())
                val area = Area(x + cos(angle) * d, z + sin(angle) * d, RADIUS)
                placed[id] = Placed(area, now + REFRESH_MS)
                area
            }
        }
    }

    companion object {
        /**
         * How big the circles are, metres: wide (120 m across, the corner map shows 160), so
         * they only say "somewhere round here".
         */
        const val RADIUS = 60f
        /** How far off-centre an enemy can be, as a share of the radius. */
        const val OFFSET = 0.6f
        /** A circle is replaced once the enemy is this far out towards its edge. */
        const val KEEP_INSIDE = 0.85f
        const val REFRESH_MS = 6_000L
    }
}
