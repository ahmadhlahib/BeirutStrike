package com.example.beirutrun

import com.example.beirutrun.city.CityMap
import com.example.beirutrun.city.CityScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Checks the real Downtown Beirut map shipped in assets/maps/downtown.bin. */
class CityMapTest {

    companion object {
        lateinit var map: CityMap

        @BeforeClass
        @JvmStatic
        fun load() {
            map = File("src/main/assets/maps/downtown.bin").inputStream().use { CityMap.load(it) }
        }
    }

    @Test
    fun mapHasDowntownContents() {
        assertTrue("buildings: ${map.buildings.size}", map.buildings.size > 900)
        assertTrue("roads: ${map.roads.size}", map.roads.size > 500)
        assertTrue("sea polygons", map.sea.isNotEmpty())
        assertTrue("trees", map.trees.size > 500)
        assertTrue("mosques", map.buildings.any { it.kind == CityMap.BUILDING_MOSQUE })
        assertTrue(map.attribution.contains("OpenStreetMap"))
    }

    @Test
    fun spawnIsWalkable() {
        assertFalse(map.isBlocked(map.spawnX, map.spawnZ, 0.35f))
        // Some room to move away from the start in at least one direction.
        val free = listOf(3f to 0f, -3f to 0f, 0f to 3f, 0f to -3f)
            .count { (dx, dz) -> !map.isBlocked(map.spawnX + dx, map.spawnZ + dz, 0.35f) }
        assertTrue(free >= 2)
    }

    @Test
    fun buildingsBlockWalkingAndBullets() {
        val b = map.buildings.filter { it.blocksWalking && it.area > 400f }
            .first { CityMap.inside(it.pts, it.centerX, it.centerZ) }
        assertTrue(map.isBlocked(b.centerX, b.centerZ, 0.35f))
        assertTrue(map.isInsideBuilding(b.centerX, 1.5f, b.centerZ, 0f))
        assertFalse(map.isInsideBuilding(b.centerX, b.height + 2f, b.centerZ, 0f))
    }

    @Test
    fun randomStreetPointsAreWalkable() {
        val rnd = Random(3)
        repeat(20) {
            val (x, z) = map.randomStreetPoint(rnd)
            assertFalse(map.isBlocked(x, z, 0.35f))
        }
    }

    @Test
    fun triangulatesConcavePolygon() {
        // An L shape (area 3): its triangles must cover exactly that area.
        val ring = floatArrayOf(0f, 0f, 2f, 0f, 2f, 1f, 1f, 1f, 1f, 2f, 0f, 2f)
        val ccw = if (CityMap.signedArea(ring) > 0) ring else ring.reversedPairs()
        val tri = CityMap.triangulate(ccw)
        assertEquals(4 * 3, tri.size)
        var area = 0f
        for (i in tri.indices step 3) {
            val t = floatArrayOf(
                ccw[2 * tri[i]], ccw[2 * tri[i] + 1], ccw[2 * tri[i + 1]], ccw[2 * tri[i + 1] + 1],
                ccw[2 * tri[i + 2]], ccw[2 * tri[i + 2] + 1],
            )
            area += kotlin.math.abs(CityMap.signedArea(t))
        }
        assertEquals(3f, area, 1e-4f)
    }

    @Test
    fun sceneBuildsWithinPhoneBudget() {
        val scene = CityScene.build(map)
        assertTrue(scene.tiles.isNotEmpty())
        val vertices = scene.vertexCount
        assertTrue("vertices: $vertices", vertices in 100_000..3_000_000)
        println("scene: ${scene.tiles.size} tiles, $vertices vertices")
    }

    private fun FloatArray.reversedPairs(): FloatArray {
        val out = FloatArray(size)
        for (i in indices step 2) {
            out[size - 2 - i] = this[i]
            out[size - 1 - i] = this[i + 1]
        }
        return out
    }
}
