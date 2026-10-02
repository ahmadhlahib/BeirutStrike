package com.example.beirutrun

import com.example.beirutrun.city.CityMap
import com.example.beirutrun.city.Ladders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every map gets ladders even in the smallest room, each usable at both ends, the same on every phone. */
class LaddersTest {

    private val maps = File("src/main/assets/maps").listFiles { f -> f.extension == "bin" }.orEmpty().sortedBy { it.name }

    @Test
    fun everyMapHasUsableLadders() {
        assertTrue("no maps found", maps.isNotEmpty())
        for (file in maps) {
            val id = file.nameWithoutExtension
            for (size in listOf(200f, null)) {
                val map = file.inputStream().use { CityMap.load(it) }
                size?.let(map::limitTo)
                val ladders = Ladders.place(map)
                println("$id (${size?.toInt() ?: "whole map"}): ${ladders.size} ladders, roofs " +
                    ladders.joinToString { "%.0f".format(it.height) } + " m")
                // The seafront maps have few low buildings near the start, so a small room may only get two.
                val least = if (size == null) 8 else 2
                assertTrue("$id: only ${ladders.size} ladders in a ${size ?: "whole"} map", ladders.size >= least)
                for (l in ladders) {
                    assertTrue("$id: ladder foot outside the play area", map.inPlayArea(l.footX, l.footZ))
                    assertFalse("$id: ladder foot is blocked", map.isBlocked(l.footX, l.footZ, 0.35f))
                    assertTrue("$id: ladder top is off the roof", CityMap.inside(l.building.pts, l.topX, l.topZ))
                    assertFalse("$id: ladder top is inside something", map.isInsideBuilding(l.topX, l.height + 1f, l.topZ, 0.35f))
                }
            }
        }
    }

    /** Phones in a room must agree on where the ladders are. */
    @Test
    fun placementIsTheSameEveryTime() {
        for (file in maps) {
            fun place() = file.inputStream().use { CityMap.load(it) }.also { it.limitTo(400f) }
                .let { map -> Ladders.place(map).map { Triple(it.wallX, it.wallZ, it.height) } }
            assertEquals(file.nameWithoutExtension, place(), place())
        }
    }
}
