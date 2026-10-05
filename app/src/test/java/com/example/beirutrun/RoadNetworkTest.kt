package com.example.beirutrun

import com.example.beirutrun.city.CityMap
import com.example.beirutrun.city.RoadNetwork
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The roads in every map share points where they meet, so junctions can be found. */
class RoadNetworkTest {

    private val maps = File("src/main/assets/maps").listFiles { f -> f.extension == "bin" }.orEmpty().sortedBy { it.name }

    @Test
    fun everyMapHasJunctions() {
        for (file in maps) {
            val map = file.inputStream().use { CityMap.load(it) }
            val network = RoadNetwork(map.roads)
            val id = file.nameWithoutExtension
            println("$id: ${network.junctions.size} junctions for ${map.roads.size} roads")
            assertTrue("$id: only ${network.junctions.size} junctions", network.junctions.size > map.roads.size / 4)
            val j = network.junctions.first()
            assertTrue(network.nearJunction(j.x + 0.5f, j.z, 0f))
            assertTrue(network.at(j.x, j.z).size >= 2)
        }
    }
}
