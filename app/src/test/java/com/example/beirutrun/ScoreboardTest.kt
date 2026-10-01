package com.example.beirutrun

import com.example.beirutrun.online.PlayerStats
import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreboardTest {

    private fun player(name: String, kills: Int, deaths: Int, hits: Int = 0) =
        PlayerStats(uid = name, name = name, team = "", kills = kills, deaths = deaths, shots = hits, hits = hits, hitsTaken = 0)

    @Test
    fun formatsGameTime() {
        assertEquals("0:30", GameClock.format(30_000))
        assertEquals("0:01", GameClock.format(1))
        assertEquals("0:00", GameClock.format(-5))
        assertEquals("12:05", GameClock.format(725_000))
        assertEquals("1:00:00", GameClock.format(3_600_000))
    }

    @Test
    fun ranksByScoreThenKillsThenFewestDeaths() {
        val ranked = Scoreboard.ranked(listOf(
            player("a", kills = 9, deaths = 0, hits = 3),
            player("b", kills = 1, deaths = 4, hits = 20),
            player("c", kills = 2, deaths = 5, hits = 10),
            player("d", kills = 2, deaths = 1, hits = 10),
            player("e", kills = 3, deaths = 9, hits = 10),
        ))
        assertEquals(listOf("b", "e", "d", "c", "a"), ranked.map { it.name })
    }

    @Test
    fun killDeathRatio() {
        assertEquals("4", Scoreboard.ratio(4, 0))
        assertEquals("2.5", Scoreboard.ratio(5, 2))
        assertEquals("0.0", Scoreboard.ratio(0, 3))
    }

    @Test
    fun accuracy() {
        assertEquals(0f, player("a", 0, 0).accuracy)
        assertEquals(0.25f, PlayerStats("a", "a", "", 0, 0, shots = 8, hits = 2, hitsTaken = 0).accuracy)
    }
}
