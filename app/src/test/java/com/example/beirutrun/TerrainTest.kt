package com.example.beirutrun

import com.example.beirutrun.city.CityMap
import com.example.beirutrun.city.Ladders
import com.example.beirutrun.city.Terrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Maps with hills (Kfarnabrakh): the ground has its real shape, roads climb it evenly and lie
 * level across, buildings stand on it, and hills block sight. Flat maps stay flat.
 */
class TerrainTest {

    private fun load(id: String) = File("src/main/assets/maps/$id.bin").inputStream().use { CityMap.load(it) }

    /** Beirut by the sea: heights from sea level, the sea flat at 0, the land rising from it. */
    @Test
    fun beirutRisesFromTheSea() {
        val heights = mapOf("downtown" to 24f, "hamra" to 67f, "ain_el_mreisseh" to 3f, "raouche" to 37f, "souks" to 15f)
        for ((id, start) in heights) {
            val city = load(id)
            assertFalse("$id is flat", city.terrain.flat)
            assertEquals("$id: the start's height above the sea", start, city.groundAt(city.spawnX, city.spawnZ), 3f)
            // Out at sea (well inside a sea polygon): sea level.
            val sea = city.sea.maxBy { CityMap.signedArea(it).let(::abs) }
            val inSea = (0 until 400).asSequence().map { k ->
                val x = city.minX + (k % 20 + 0.5f) * (city.maxX - city.minX) / 20f
                val z = city.minZ + (k / 20 + 0.5f) * (city.maxZ - city.minZ) / 20f
                x to z
            }.firstOrNull { (x, z) -> CityMap.inside(sea, x, z) && CityMap.edgeDistance(sea, x, z) > 20f }
            if (inSea != null) assertEquals("$id: the sea", 0f, city.groundAt(inSea.first, inSea.second), 0.01f)
            // Every building stands on the ground, none below the sea.
            assertTrue(city.buildings.all { it.base >= 0f && it.top == it.base + it.height })
        }
    }

    @Test
    fun flatGroundIsZero() {
        assertTrue(Terrain.FLAT.flat)
        assertEquals(0f, Terrain.FLAT.heightAt(100f, -50f))
        assertEquals(0f, Terrain.FLAT.lowestUnder(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f)))
    }

    @Test
    fun kfarnabrakhHasItsHills() {
        val city = load("kfarnabrakh")
        val t = city.terrain
        assertFalse(t.flat)
        // The start is the zero; the valley to the north is far below, the ridge above.
        assertEquals(0f, city.groundAt(city.spawnX, city.spawnZ), 1f)
        assertTrue("valley ${t.minHeight}", t.minHeight < -100f)
        assertTrue("ridge ${t.maxHeight}", t.maxHeight > 40f)
        // Bilinear between grid points: halfway is the average.
        val c = 50; val r = 60
        val mid = t.heightAt(t.x0 + (c + 0.5f) * t.cell, t.z0 + r * t.cell)
        assertEquals((t.at(c, r) + t.at(c + 1, r)) / 2f, mid, 0.01f)
    }

    @Test
    fun roadsClimbEvenlyAndLieLevel() {
        for (id in listOf("kfarnabrakh", "downtown", "souks", "hamra", "ain_el_mreisseh", "raouche")) {
            val city = load(id)
            val along = ArrayList<Float>()
            val tilts = ArrayList<Float>()
            for (road in city.roads) {
                if (road.kind == CityMap.ROAD_PATH) continue
                val p = CityMap.densify(road.pts, 3f)
                for (i in 0 until p.size / 2 - 1) {
                    val ax = p[2 * i]; val az = p[2 * i + 1]; val bx = p[2 * i + 2]; val bz = p[2 * i + 3]
                    val len = hypot(bx - ax, bz - az)
                    // (Roads run on past the map's edge, where there are no heights: only the map counts.)
                    if (len < 1f || ax < city.minX + 10f || ax > city.maxX - 10f || az < city.minZ + 10f || az > city.maxZ - 10f) continue
                    along += abs(city.groundAt(bx, bz) - city.groundAt(ax, az)) / len
                    // Across the road, a metre each side of the middle.
                    val nx = -(bz - az) / len; val nz = (bx - ax) / len
                    tilts += abs(city.groundAt(ax + nx, az + nz) - city.groundAt(ax - nx, az - nz)) / 2f
                }
            }
            along.sort()
            val typical = along[along.size / 2]
            val steep = along.count { it > 0.3f } / along.size.toFloat()
            val level = tilts.count { it < 0.15f } / tilts.size.toFloat()
            println("$id roads: typical ${"%.0f".format(typical * 100)}%, steepest ${"%.0f".format(along.last() * 100)}%, " +
                "${"%.1f".format(steep * 100)}% steeper than 30%; ${"%.1f".format(level * 100)}% level across")
            // Streets mostly gentle; steep only in short stretches (mountain bends, city interchanges
            // and flyovers, which here meet at street level), never a wall; level across almost
            // everywhere (the rest: tight hairpins, and roads on the edge of the seafront cliffs).
            assertTrue("$id typical $typical", typical < 0.08f)
            assertTrue("$id steepest ${along.last()}", along.last() < 0.6f)
            assertTrue("$id: $steep steeper than 30%", steep < 0.015f)
            assertTrue("$id: only $level level", level > if (id == "kfarnabrakh") 0.99f else 0.93f)
        }
    }

    @Test
    fun buildingsStandOnTheGroundAndLaddersReachTheRoof() {
        val city = load("kfarnabrakh")
        for (b in city.buildings) {
            // Never floating: the base is at or under the ground all round.
            assertTrue("building base ${b.base} above the ground", b.base <= city.groundAt(b.centerX, b.centerZ) + 0.01f)
            assertEquals(b.base + b.height, b.top, 0.001f)
        }
        for (l in Ladders.place(city)) {
            assertEquals(city.groundAt(l.footX, l.footZ), l.footY, 0.01f)
            assertEquals(l.building.top, l.topY, 0.01f)
            assertTrue(l.height > 2f)
        }
    }

    @Test
    fun hillsBlockTheView() {
        val t = Terrain(0f, 0f, 10f, 3, 2, floatArrayOf(0f, 20f, 0f, 0f, 20f, 0f))
        // Over a 20 m ridge between two people standing either side: hidden.
        assertTrue(t.blocks(0f, 1.5f, 0f, 20f, 1.5f, 0f, step = 1f))
        // From high enough, over the top: seen.
        assertFalse(t.blocks(0f, 41.5f, 0f, 20f, 1.5f, 0f, step = 1f))
        assertFalse(Terrain.FLAT.blocks(0f, 1.5f, 0f, 500f, 1.5f, 0f))
    }

    @Test
    fun roadsGetPointsEveryFewMetresOnHills() {
        val p = CityMap.densify(floatArrayOf(0f, 0f, 10f, 0f), 3f)
        // 10 m in pieces of at most 3 m: 4 pieces, 5 points.
        assertEquals(10, p.size)
        for (i in 0 until p.size / 2 - 1) assertTrue(p[2 * i + 2] - p[2 * i] <= 3f)
    }
}
