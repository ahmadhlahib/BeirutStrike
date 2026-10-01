package com.example.beirutrun.progression

/**
 * Level, rank and progress worked out from total XP alone (with the thresholds in XpConfig), so
 * they can never disagree with it. Plain Kotlin, no Android, so it's easy to test.
 */
object Progression {

    /** The level [totalXp] reaches: the highest one whose threshold it meets, at most the top level. */
    fun levelFor(totalXp: Long): Int {
        val levels = XpConfig.LEVEL_XP
        var level = 1
        while (level < levels.size && totalXp >= levels[level]) level++
        return level
    }

    fun stateFor(totalXp: Long): ProgressState = ProgressState(totalXp.coerceAtLeast(0))

    /** What adding [amount] XP to [totalXp] does (negative amounts count as none). */
    fun gain(totalXp: Long, amount: Int): XpGain =
        XpGain(stateFor(totalXp), stateFor(totalXp.coerceAtLeast(0) + amount.coerceAtLeast(0)))
}

/** Where a player with [totalXp] stands. */
data class ProgressState(val totalXp: Long) {
    val level: Int = Progression.levelFor(totalXp)
    val rank: Rank get() = Rank.forLevel(level)
    /** The rank above, or null at the top. */
    val nextRank: Rank? get() = rank.next
    val isMaxLevel get() = nextRank == null

    /** Total XP at which the current level began. */
    val levelStartXp: Long get() = rank.xpRequired.toLong()
    /** Total XP the next level needs (null at the top). */
    val nextLevelXp: Long? get() = nextRank?.xpRequired?.toLong()
    /** XP still needed for the next level (0 at the top). */
    val xpToNextLevel: Long get() = nextLevelXp?.let { (it - totalXp).coerceAtLeast(0) } ?: 0L

    /**
     * How far through the current level, 0..1: XP earned since it began over the XP it spans (not
     * total XP over the next threshold). Full at the top level.
     */
    val progress: Float get() {
        val end = nextLevelXp ?: return 1f
        val span = end - levelStartXp
        if (span <= 0) return 1f
        return ((totalXp - levelStartXp).toFloat() / span).coerceIn(0f, 1f)
    }
}

/** The result of earning XP: where the player was and where they are now. */
data class XpGain(val before: ProgressState, val after: ProgressState) {
    val xp: Long get() = after.totalXp - before.totalXp
    /** Levels gained (can be several at once). */
    val levelsGained: Int get() = after.level - before.level
    val rankedUp: Boolean get() = levelsGained > 0
    /** Every rank reached by this gain, lowest first (empty without a rank-up). */
    val newRanks: List<Rank> get() = ((before.level + 1)..after.level).map { Rank.forLevel(it) }
}
