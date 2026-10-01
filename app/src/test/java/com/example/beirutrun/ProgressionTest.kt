package com.example.beirutrun

import com.example.beirutrun.progression.Progression
import com.example.beirutrun.progression.Rank
import com.example.beirutrun.progression.XpConfig
import com.example.beirutrun.progression.XpReward
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressionTest {

    @Test
    fun levelsFromXp() {
        assertEquals(1, Progression.levelFor(0))
        assertEquals(1, Progression.levelFor(499))
        assertEquals(2, Progression.levelFor(500))
        assertEquals(9, Progression.levelFor(11_699))
        assertEquals(10, Progression.levelFor(11_700))
        assertEquals(19, Progression.levelFor(45_499))
        assertEquals(20, Progression.levelFor(45_500))
    }

    @Test
    fun neverAboveTheTopLevel() {
        assertEquals(20, Progression.levelFor(45_501))
        assertEquals(20, Progression.levelFor(10_000_000))
        assertEquals(Rank.BEIRUT_LEGEND, Rank.forXp(Long.MAX_VALUE))
        assertEquals(20, Progression.gain(45_000, 1_000_000).after.level)
    }

    @Test
    fun ranksByLevelAndXp() {
        assertEquals(20, Rank.entries.size)
        assertEquals(Rank.PRIVATE, Rank.forXp(0))
        assertEquals(Rank.PRIVATE_FIRST_CLASS, Rank.forXp(500))
        assertEquals(Rank.CAPTAIN, Rank.forXp(12_450))
        assertEquals(Rank.MAJOR, Rank.CAPTAIN.next)
        assertNull(Rank.BEIRUT_LEGEND.next)
        assertEquals("نقيب", Rank.CAPTAIN.arabicName)
        assertEquals("Captain", Rank.CAPTAIN.englishName)
        Rank.entries.forEachIndexed { i, r ->
            assertEquals(i + 1, r.level)
            assertEquals(r, Rank.forLevel(r.level))
            assertEquals(XpConfig.LEVEL_XP[i], r.xpRequired)
        }
        // Out-of-range levels clamp.
        assertEquals(Rank.PRIVATE, Rank.forLevel(0))
        assertEquals(Rank.BEIRUT_LEGEND, Rank.forLevel(99))
    }

    @Test
    fun thresholdsKeepGoingUp() {
        assertEquals(0, XpConfig.LEVEL_XP[0])
        for (i in 1 until XpConfig.LEVEL_XP.size) assertTrue(XpConfig.LEVEL_XP[i] > XpConfig.LEVEL_XP[i - 1])
    }

    @Test
    fun progressWithinTheCurrentLevel() {
        val s = Progression.stateFor(12_450)
        assertEquals(10, s.level)
        assertEquals(11_700L, s.levelStartXp)
        assertEquals(14_000L, s.nextLevelXp)
        assertEquals(1_550L, s.xpToNextLevel)
        // (12,450 - 11,700) / (14,000 - 11,700), not 12,450 / 14,000.
        assertEquals(750f / 2_300f, s.progress, 1e-6f)
        assertEquals(0f, Progression.stateFor(11_700).progress, 0f)
        assertEquals(0f, Progression.stateFor(0).progress, 0f)
        assertEquals(499f / 500f, Progression.stateFor(499).progress, 1e-6f)
    }

    @Test
    fun maxLevel() {
        val s = Progression.stateFor(50_000)
        assertTrue(s.isMaxLevel)
        assertNull(s.nextRank)
        assertNull(s.nextLevelXp)
        assertEquals(0L, s.xpToNextLevel)
        assertEquals(1f, s.progress, 0f)
        assertFalse(Progression.stateFor(45_499).isMaxLevel)
    }

    @Test
    fun gainingXp() {
        val kill = Progression.gain(0, XpReward.ELIMINATION.xp)
        assertEquals(50L, kill.xp)
        assertFalse(kill.rankedUp)
        assertTrue(kill.newRanks.isEmpty())

        val one = Progression.gain(450, XpReward.ELIMINATION.xp)
        assertTrue(one.rankedUp)
        assertEquals(1, one.levelsGained)
        assertEquals(listOf(Rank.PRIVATE_FIRST_CLASS), one.newRanks)
    }

    @Test
    fun severalLevelsAtOnce() {
        val g = Progression.gain(400, 2_000)
        assertEquals(1, g.before.level)
        assertEquals(4, g.after.level)
        assertEquals(3, g.levelsGained)
        assertEquals(listOf(Rank.PRIVATE_FIRST_CLASS, Rank.CORPORAL, Rank.SERGEANT), g.newRanks)
    }

    @Test
    fun noLevelsPastTheTop() {
        val g = Progression.gain(45_500, 5_000)
        assertEquals(50_500L, g.after.totalXp)
        assertEquals(0, g.levelsGained)
        assertFalse(g.rankedUp)
    }

    @Test
    fun negativeAmountsAddNothing() {
        assertEquals(0L, Progression.gain(100, -50).xp)
        assertEquals(0L, Progression.stateFor(-5).totalXp)
    }
}
