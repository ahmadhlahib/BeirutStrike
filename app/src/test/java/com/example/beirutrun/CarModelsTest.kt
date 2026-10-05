package com.example.beirutrun

import com.example.beirutrun.city.CarModels
import com.example.beirutrun.city.CityLife
import com.example.beirutrun.city.CityMap
import com.example.beirutrun.city.RoadNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Every car model for the traffic (assets/models/cars/, skipped if there are none): it reads,
 * is a real car's size, has four round wheels on the ground (two that steer), and the traffic
 * rolls and steers them.
 */
class CarModelsTest {

    private val dir = File("src/main/assets/${CarModels.DIR}")
    private val files = dir.listFiles { f -> f.extension == "glb" }.orEmpty().map { it.name }.sorted()
    private val models = CarModels.loadAll(files) { File(dir, it).takeIf { f -> f.exists() }?.readBytes() }

    @Test
    fun carsAreCarShapedWithFourWheelsOnTheGround() {
        assumeTrue(files.isNotEmpty())
        assertEquals("every car should read", files.size, models.size)
        for (m in models) {
            val length = m.halfLength * 2f; val width = m.halfWidth * 2f
            var top = 0f; var bottom = Float.MAX_VALUE
            for (i in 0 until m.body.size / 8) { top = maxOf(top, m.body[i * 8 + 1]); bottom = minOf(bottom, m.body[i * 8 + 1]) }
            println("${m.name}: %.2f × %.2f × %.2f m, wheels r %.2f, wheelbase %.2f, %d + %d vertices".format(
                length, width, top, m.wheelRadius, m.wheelbase, m.body.size / 8, m.wheels.sumOf { it.vertices.size / 8 }))
            assertNotNull("${m.name}: no texture", m.image)
            assertTrue("${m.name}: $length m long", length in 3.4f..7f)
            assertTrue("${m.name}: $width m wide", width in 1.5f..2.6f)
            assertTrue("${m.name}: $top m tall", top in 1.2f..3.2f)
            assertTrue("${m.name}: body below the road", bottom >= -0.01f)
            assertEquals("${m.name}: wheels", 4, m.wheels.size)
            assertEquals("${m.name}: steering wheels", 2, m.wheels.count { it.front })
            assertTrue("${m.name}: front wheels should be ahead", m.wheels.filter { it.front }.all { it.z > 0f })
            for (w in m.wheels) {
                assertTrue("${m.name}: wheel r ${w.radius}", w.radius in 0.25f..0.6f)
                // Resting on the road: its lowest point at ground level.
                var low = Float.MAX_VALUE
                for (i in 0 until w.vertices.size / 8) low = minOf(low, w.y + w.vertices[i * 8 + 1])
                assertTrue("${m.name}: wheel bottom at $low", abs(low) < 0.02f)
            }
        }
    }

    @Test
    fun trafficRollsAndSteersTheWheels() {
        assumeTrue(models.isNotEmpty())
        val file = File("src/main/assets/maps").listFiles { f -> f.extension == "bin" }.orEmpty().minByOrNull { it.name }
        assumeTrue(file != null)
        val map = file!!.inputStream().use { CityMap.load(it) }
        val life = CityLife(map, RoadNetwork(map.roads), seed = 3)
        life.useCarModels(models.map { CityLife.CarFit(it.halfLength, it.halfWidth, it.wheelRadius, it.wheelbase, CarModels.weight(it.name)) })
        val px = map.spawnX; val pz = map.spawnZ
        var steered = false
        var rolled = false
        repeat(60 * 60) {
            life.update(1f / 60f, px, pz, floatArrayOf(px, pz))
            for (c in life.modelledCars(px, pz, 1000f)) {
                assertTrue("car model ${c.fit}", c.fit in models.indices)
                assertTrue(!c.spin.isNaN() && !c.steer.isNaN())
                assertTrue("steer ${c.steer}", abs(c.steer) <= 0.61f)
                if (abs(c.steer) > 0.1f) steered = true
                if (abs(c.spin) > 0.5f) rolled = true
            }
        }
        assertTrue("cars should be drawn with the models", life.modelledCars(px, pz, 1000f).isNotEmpty())
        assertTrue("wheels should roll", rolled)
        assertTrue("front wheels should steer at junctions", steered)
        // Modelled cars aren't drawn again as built-in shapes.
        val (_, size) = life.fill(px, pz, 1000f, 0f)
        assertEquals(0, life.carFloats)
        assertTrue(size >= 0)
    }
}
