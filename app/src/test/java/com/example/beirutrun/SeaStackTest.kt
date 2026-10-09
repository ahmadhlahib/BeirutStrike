package com.example.beirutrun

import com.example.beirutrun.city.CityMap
import com.example.beirutrun.city.SeaStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/** Pigeon Rocks off Raouche: real sea stacks, about 60 m high, the big one with its arch towards the Corniche. */
class SeaStackTest {

    private val city = File("src/main/assets/maps/raouche.bin").inputStream().use { CityMap.load(it) }
    private val rocks = city.buildings.filter { it.kind == CityMap.BUILDING_ROCK }.sortedByDescending { it.area }

    private fun stack(b: CityMap.Building, arch: Boolean) =
        SeaStack(b.pts, b.height, arch, city.spawnX, city.spawnZ, seed = 1)

    @Test
    fun pigeonRocksStandTallOutOfTheSea() {
        assertTrue("two big rocks and an islet", rocks.size >= 2)
        val big = rocks[0]
        assertEquals(50f, big.height, 3f)
        assertTrue("second rock ${rocks[1].height}", rocks[1].height in 30f..50f)
        val tris = stack(big, arch = true).triangles()
        assertTrue(tris.size > 5_000)
        val top = tris.maxOf { maxOf(it.p[1], it.p[4], it.p[7]) }
        // Rounded and rough on top, but about as high as the real rock.
        assertTrue("top $top", top in big.height - 8f..big.height + 3f)
        // Scrub on top, dark wet rock at the waterline, pale limestone between.
        assertTrue(tris.any { it.part == SeaStack.Part.SCRUB })
        assertTrue(tris.any { it.part == SeaStack.Part.WET })
        assertTrue(tris.count { it.part == SeaStack.Part.CLIFF } > tris.size / 2)
        // Normals are unit length and the corners finite.
        for (t in tris.take(500)) for (k in 0 until 3) {
            val n = hypot(hypot(t.n[3 * k], t.n[3 * k + 1]), t.n[3 * k + 2])
            assertEquals(1f, n, 0.01f)
            assertTrue(t.p.none { it.isNaN() })
        }
    }

    @Test
    fun theArchGoesRightThrough() {
        val big = rocks[0]
        val withArch = stack(big, arch = true)
        val without = stack(big, arch = false)
        // Along the arch (off-centre, towards the rock's northern, seaward end), from one side of the
        // rock to the other, at 5 m above the sea: open water through the arch, solid rock without it.
        // The rock's middle as SeaStack takes it: the average of its outline's points.
        val n = big.pts.size / 2
        val mx = (0 until n).sumOf { big.pts[2 * it].toDouble() }.toFloat() / n
        val mz = (0 until n).sumOf { big.pts[2 * it + 1].toDouble() }.toFloat() / n
        val dx = city.spawnX - mx; val dz = city.spawnZ - mz
        val l = hypot(dx, dz)
        val ax = -dz / l; val az = dx / l
        val radius = kotlin.math.sqrt(abs(CityMap.signedArea(big.pts)) / Math.PI.toFloat())
        val shift = -SeaStack.ARCH_OFFSET * 2f * radius * (if (az < 0f) -1f else 1f)
        var solidWithout = 0
        for (k in -15..15) {
            val x = mx + dx / l * k + ax * shift; val z = mz + dz / l * k + az * shift
            assertTrue("arch blocked at $k", withArch.distance(x, 5f, z) > 0f)
            if (without.distance(x, 5f, z) < 0f) solidWithout++
        }
        assertTrue(solidWithout > 20)
        // Above the arch, the rock is solid again.
        assertTrue(withArch.distance(big.centerX, 30f, big.centerZ) < 0f)
    }
}
