package com.example.beirutrun

import com.example.beirutrun.city.CityMap
import com.example.beirutrun.city.CityScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every map shipped in assets/maps/ must load, have a walkable start and fit a phone's budget. */
class AllMapsTest {

    private val maps = File("src/main/assets/maps").listFiles { f -> f.extension == "bin" }.orEmpty().sortedBy { it.name }

    @Test
    fun everyMapLoadsAndIsPlayable() {
        assertTrue("no maps found", maps.isNotEmpty())
        for (file in maps) {
            val map = file.inputStream().use { CityMap.load(it) }
            val id = file.nameWithoutExtension
            assertTrue("$id: preview picture missing", File(file.parentFile, "${id}_preview.png").exists())
            assertTrue("$id: too few buildings (${map.buildings.size})", map.buildings.size > 100)
            assertTrue("$id: too few roads (${map.roads.size})", map.roads.size > 50)
            assertFalse("$id: start point is blocked", map.isBlocked(map.spawnX, map.spawnZ, 0.35f))
            val scene = CityScene.build(map)
            assertTrue("$id: ${scene.vertexCount} vertices is too many for a phone", scene.vertexCount < 3_000_000)
            println("$id: ${map.buildings.size} buildings, ${map.roads.size} roads, ${map.sea.size} sea, " +
                "${map.trees.size} trees, ${scene.vertexCount} vertices")
        }
    }

    /** The smallest room size still has a walkable start, and respawns stay inside its square. */
    @Test
    fun smallPlayAreaKeepsPlayersInside() {
        for (file in maps) {
            val map = file.inputStream().use { CityMap.load(it) }
            val id = file.nameWithoutExtension
            map.limitTo(200f)
            assertTrue("$id: not limited", map.limited)
            assertEquals("$id: play area width", 200f, map.playMaxX - map.playMinX, 0.01f)
            assertFalse("$id: start point is blocked", map.isBlocked(map.spawnX, map.spawnZ, 0.35f))
            assertTrue("$id: can walk out of the play area", map.isBlocked(map.playMaxX + 1f, map.spawnZ, 0.35f))
            repeat(20) {
                val (x, z) = map.randomStreetPoint()
                assertTrue("$id: respawn outside the play area", map.inPlayArea(x, z))
            }
        }
    }
}
