package com.example.beirutrun

import com.example.beirutrun.city.EnemyAreas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

/** The maps' enemy circles: the enemy is always inside, rarely in the middle, and it doesn't follow them step by step. */
class EnemyAreasTest {

    @Test
    fun enemyIsInsideButNotAtTheCentre() {
        val areas = EnemyAreas(Random(1))
        var offCentre = 0
        repeat(500) { i ->
            val x = i * 3f; val z = -i * 2f
            val a = areas.update(listOf(Triple("e$i", x, z)), 0L).single()
            val d = hypot(a.x - x, a.z - z)
            assertTrue("enemy outside its circle", d <= a.radius * EnemyAreas.OFFSET + 0.001f)
            if (d > a.radius * 0.1f) offCentre++
        }
        assertTrue("circles should rarely sit right on the enemy: $offCentre of 500 off-centre", offCentre > 450)
    }

    @Test
    fun circlesStayPutThenMove() {
        val areas = EnemyAreas(Random(2))
        val first = areas.update(listOf(Triple("e", 0f, 0f)), 0L).single()
        // A few steps later, within the circle: the same circle.
        assertSame(first, areas.update(listOf(Triple("e", 3f, 2f)), 2_000L).single())
        // Walked out towards the edge: a new one, with them inside.
        val moved = areas.update(listOf(Triple("e", first.x + 30f, first.z)), 2_500L).single()
        assertNotSame(first, moved)
        assertTrue(hypot(moved.x - (first.x + 30f), moved.z - first.z) <= moved.radius)
        // After a while, a new one even standing still.
        val still = areas.update(listOf(Triple("e", first.x + 30f, first.z)), 2_500L + EnemyAreas.REFRESH_MS).single()
        assertNotSame(moved, still)
        // Gone (left or dead): no circle.
        assertEquals(0, areas.update(emptyList(), 20_000L).size)
    }
}
