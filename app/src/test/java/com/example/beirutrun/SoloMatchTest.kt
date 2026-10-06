package com.example.beirutrun

import com.example.beirutrun.city.CityMap
import com.example.beirutrun.solo.BotDifficulty
import com.example.beirutrun.solo.SoloMatch
import com.example.beirutrun.solo.SoloSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * Solo games against bots, played on the real maps: bots start on the streets and walk them,
 * find the player and shoot, take hits, die and come back, and keep the score.
 */
class SoloMatchTest {

    private val maps = File("src/main/assets/maps").listFiles { f -> f.extension == "bin" }.orEmpty().sortedBy { it.name }

    private fun load(file: File) = file.inputStream().use { CityMap.load(it) }

    private fun match(city: CityMap, bots: Int, difficulty: BotDifficulty, allies: Boolean, seed: Int = 5) =
        SoloMatch(city, SoloSettings(bots, difficulty, allies, 300_000L), "el_lahib", listOf("corniche_sharks", "golden_lions"), seed = seed)

    private fun me(city: CityMap, dead: Boolean = false) =
        SoloMatch.Player("Me", "el_lahib", city.spawnX, 0f, city.spawnZ, prone = false, dead = dead)

    @Test
    fun botsStartOnTheStreetsAndWalkThem() {
        for (file in maps) {
            val city = load(file)
            val game = match(city, 8, BotDifficulty.EASY, allies = false)
            val start = game.players(0L)
            assertEquals(8, start.size)
            for (b in start) {
                assertTrue("${file.name}: ${b.name} starts in a building", !city.isBlocked(b.x, b.z, 0.3f))
                assertTrue("${file.name}: ${b.name} outside the play area", city.inPlayArea(b.x, b.z))
            }
            // A minute later, they've moved, and are still on open ground in the play area.
            repeat(60 * 20) { game.update(0.05f, me(city, dead = true)) }
            val later = game.players(0L)
            val moved = start.zip(later).count { (a, b) -> hypot(a.x - b.x, a.z - b.z) > 5f }
            assertTrue("${file.name}: only $moved of 8 bots moved", moved >= 6)
            for (b in later) {
                assertTrue("${file.name}: ${b.name} walked out of the play area", city.inPlayArea(b.x, b.z))
                assertFalse(b.x.isNaN() || b.z.isNaN() || b.heading.isNaN())
            }
        }
    }

    @Test
    fun hardBotsFindAndShootThePlayer() {
        val city = load(maps.first())
        val game = match(city, 4, BotDifficulty.HARD, allies = false)
        var shots = 0
        var hits = 0
        repeat(60 * 20 * 3) {
            for (e in game.update(0.05f, me(city))) when (e) {
                is SoloMatch.Event.Shot -> shots++
                is SoloMatch.Event.PlayerHit -> hits++
            }
        }
        println("${maps.first().name}: hard bots fired $shots shots, $hits hit the player in 3 minutes")
        assertTrue("hard bots never fired", shots > 0)
        assertTrue("hard bots never hit", hits > 0)
    }

    @Test
    fun botsDieAndComeBack() {
        val city = load(maps.first())
        val game = match(city, 2, BotDifficulty.MEDIUM, allies = false)
        val bot = game.players(0L).first()
        assertTrue(game.isBot(bot.uid))
        repeat(4) { assertEquals(null, game.hitByPlayer(bot.uid, 1)) }
        assertNotNull("the fifth hit should kill", game.hitByPlayer(bot.uid, 1))
        assertTrue(game.players(0L).first { it.uid == bot.uid }.dead)
        // Hits on a dead bot don't count again.
        assertEquals(null, game.hitByPlayer(bot.uid, 1))
        repeat(90) { game.update(0.05f, me(city, dead = true)) }
        val back = game.players(0L).first { it.uid == bot.uid }
        assertFalse("back after 4 s", back.dead)
        assertEquals(5, back.health)
        val stats = game.stats().first { it.uid == bot.uid }
        assertEquals(1, stats.deaths)
        assertEquals(5, stats.hitsTaken)
    }

    @Test
    fun alliesSplitTheBotsAndTheScoreIsKept() {
        val city = load(maps.first())
        val withAllies = match(city, 5, BotDifficulty.MEDIUM, allies = true)
        val teams = withAllies.players(0L).groupingBy { it.team }.eachCount()
        assertEquals(mapOf("el_lahib" to 2, "corniche_sharks" to 3), teams)
        val alone = match(city, 4, BotDifficulty.MEDIUM, allies = false)
        assertTrue(alone.players(0L).none { it.team == "el_lahib" })
        // The player's own counts, and a row for everyone.
        alone.count("shots"); alone.count("shots"); alone.count("hits")
        val stats = alone.stats()
        assertEquals(5, stats.size)
        val mine = stats.first { it.uid == SoloMatch.ME }
        assertEquals(2, mine.shots)
        assertEquals(1, mine.hits)
        // Bots fight each other too, and the score keeps up.
        repeat(60 * 20 * 3) { alone.update(0.05f, me(city, dead = true)) }
        val after = alone.stats().filter { it.uid != SoloMatch.ME }
        println("bots without the player, 3 minutes: " + after.joinToString { "${it.name} ${it.kills}/${it.deaths}" })
        assertTrue("bots on different teams should fight", after.sumOf { it.shots } > 0)
    }
}
