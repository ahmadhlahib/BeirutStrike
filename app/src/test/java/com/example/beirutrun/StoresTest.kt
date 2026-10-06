package com.example.beirutrun

import com.example.beirutrun.city.CityMap
import com.example.beirutrun.city.Stores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/** Every map gets its arms stores beside streets, on open ground, spread out, the same each time. */
class StoresTest {

    private val maps = File("src/main/assets/maps").listFiles { f -> f.extension == "bin" }.orEmpty().sortedBy { it.name }

    @Test
    fun storesAreBesideStreetsAndSpreadOut() {
        for (file in maps) for (size in listOf(null, 400f, 200f)) {
            val city = file.inputStream().use { CityMap.load(it) }
            size?.let { city.limitTo(it) }
            val stores = Stores.place(city)
            val id = "${file.nameWithoutExtension} ${size ?: "whole map"}"
            println("$id: ${stores.size} stores, nearest to the start ${"%.0f".format(stores.minOf { hypot(it.x - city.spawnX, it.z - city.spawnZ) })} m")
            assertTrue("$id: only ${stores.size} stores", stores.size >= if (size == 200f) 2 else 3)
            for (s in stores) {
                assertTrue("$id: a store outside the play area", city.inPlayArea(s.x, s.z))
                assertFalse("$id: a store in a building", city.isBlocked(s.x, s.z, 1f))
                assertFalse("$id: can't stand in front of a store", city.isBlocked(s.frontX, s.frontZ, 0.4f))
            }
            for (i in stores.indices) for (j in i + 1 until stores.size) {
                assertTrue("$id: two stores too close", hypot(stores[i].x - stores[j].x, stores[i].z - stores[j].z) > 50f)
            }
            // The same on every phone.
            val again = file.inputStream().use { CityMap.load(it) }.also { c -> size?.let { c.limitTo(it) } }
            assertEquals(stores.map { it.x to it.z }, Stores.place(again).map { it.x to it.z })
        }
    }
}
