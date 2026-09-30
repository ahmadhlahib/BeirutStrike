package com.example.beirutrun

import com.example.beirutrun.city.Stand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandTest {

    @Test
    fun meshesAreWellFormed() {
        for ((v, idx) in listOf(Stand.base(), Stand.rim())) {
            assertEquals(0, v.size % 8)
            assertEquals(0, idx.size % 3)
            val count = v.size / 8
            assertTrue(idx.all { it in 0 until count })
        }
    }

    @Test
    fun topIsWhereTheFeetAre() {
        val (v, _) = Stand.base()
        val ys = (0 until v.size / 8).map { v[it * 8 + 1] }
        assertEquals(0f, ys.max(), 1e-6f)
        assertEquals(-Stand.HEIGHT, ys.min(), 1e-6f)
        // The rim sits just above the top, inside the edge.
        val (r, _) = Stand.rim()
        for (i in 0 until r.size / 8) {
            assertTrue(r[i * 8 + 1] > 0f)
            val d = Math.hypot(r[i * 8].toDouble(), r[i * 8 + 2].toDouble())
            assertTrue(d >= Stand.RIM_INNER - 1e-4 && d < Stand.RADIUS)
        }
    }
}
