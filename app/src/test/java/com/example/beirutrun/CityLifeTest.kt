package com.example.beirutrun

import com.example.beirutrun.city.CityLife
import com.example.beirutrun.city.CityMap
import com.example.beirutrun.city.RoadNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Cars and passers-by appear round the player, keep to the streets and can be drawn. */
class CityLifeTest {

    private val maps = File("src/main/assets/maps").listFiles { f -> f.extension == "bin" }.orEmpty().sortedBy { it.name }

    @Test
    fun streetsFillAndKeepMoving() {
        for (file in maps) {
            val map = file.inputStream().use { CityMap.load(it) }
            val id = file.nameWithoutExtension
            val life = CityLife(map, RoadNetwork(map.roads), seed = 7)
            val px = map.spawnX; val pz = map.spawnZ
            life.update(0.016f, px, pz, floatArrayOf(px, pz))
            assertTrue("$id: no cars", life.carCount > 0)
            assertTrue("$id: no people", life.walkerCount > 0)
            // Two minutes of play.
            repeat(60 * 120) { life.update(1f / 60f, px, pz, floatArrayOf(px, pz)) }
            assertTrue("$id: cars gone", life.carCount > CityLife.MAX_CARS / 3)
            assertTrue("$id: people gone", life.walkerCount > CityLife.MAX_WALKERS / 3)
            val (data, size) = life.fill(px, pz, 1000f, 1000f)
            assertTrue("$id: nothing drawn", size > 0)
            assertEquals(0, size % 24)
            for (i in 0 until size) assertFalse("$id: NaN in vertex data", data[i].isNaN())
            // No car parked on the player.
            assertFalse("$id: a car on the start point", life.blocks(px, pz, 0.35f))
            println("$id: ${life.carCount} cars, ${life.walkerCount} people, ${size / 8} vertices")
        }
    }

    @Test
    fun peopleCanBeShotAndAreClearedAway() {
        val map = maps.first().inputStream().use { CityMap.load(it) }
        val life = CityLife(map, RoadNetwork(map.roads), seed = 3)
        val px = map.spawnX; val pz = map.spawnZ
        repeat(60) { life.update(1f / 60f, px, pz, floatArrayOf(px, pz)) }
        val target = life.nearest(px, pz, 1, 1000f).first()
        // A bullet at chest height, through them from the front, kills them.
        // Chest high above the ground they stand on (the maps have hills).
        val chest = map.groundAt(target.x, target.z) + 1.2f
        assertTrue(life.shoot(target.x, chest, target.z, -kotlin.math.sin(target.heading), kotlin.math.cos(target.heading)))
        val dead = life.nearest(px, pz, 100, 1000f).first { it.id == target.id }
        assertTrue("should be dead", dead.death in 1..3)
        // A second bullet in the same place goes through the body.
        assertFalse(life.shoot(target.x, chest, target.z, 1f, 0f))
        // Bodies lie still, are drawn without NaNs, and are cleared away after a while.
        repeat(60 * 5) { life.update(1f / 60f, px, pz, floatArrayOf(px, pz)) }
        val still = life.nearest(px, pz, 100, 1000f).first { it.id == target.id }
        assertEquals(dead.x, still.x, 0.01f)
        val (data, size) = life.fill(px, pz, 1000f, 1000f)
        for (i in 0 until size) assertFalse(data[i].isNaN())
        repeat(60 * 30) { life.update(1f / 60f, px, pz, floatArrayOf(px, pz)) }
        assertTrue("body cleared", life.nearest(px, pz, 100, 1000f).none { it.id == target.id })
    }
}
