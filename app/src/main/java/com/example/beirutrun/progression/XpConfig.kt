package com.example.beirutrun.progression

/**
 * Every number that balances progression, in one place: how much XP each level needs and how
 * much XP each gameplay event gives. Change them here; nothing else hardcodes them.
 */
object XpConfig {
    /**
     * Total XP needed to reach each level, from level 1 (index 0, always 0) up to the top level.
     * One entry per [Rank], in order; they must keep going up.
     */
    val LEVEL_XP: IntArray = intArrayOf(
        0,      // 1  Private
        500,    // 2  Private First Class
        1_200,  // 3  Corporal
        2_100,  // 4  Sergeant
        3_200,  // 5  Staff Sergeant
        4_500,  // 6  Warrant Officer
        6_000,  // 7  Chief Warrant Officer
        7_700,  // 8  Second Lieutenant
        9_600,  // 9  First Lieutenant
        11_700, // 10 Captain
        14_000, // 11 Major
        16_500, // 12 Lieutenant Colonel
        19_200, // 13 Colonel
        22_100, // 14 Brigadier General
        25_200, // 15 Major General
        28_500, // 16 General
        32_000, // 17 Field Commander
        36_000, // 18 Special Forces Commander
        40_500, // 19 Supreme Commander
        45_500, // 20 Beirut Legend
    )

    val MAX_LEVEL get() = LEVEL_XP.size
}

/** XP earned for each gameplay event. */
enum class XpReward(val xp: Int) {
    /** Killing an enemy player. */
    ELIMINATION(50),
    /** Extra, on top of [ELIMINATION], when the killing shot hit the head. */
    HEADSHOT(25),
    /** Completing a mission (no missions exist yet; see PlayerProgress.award). */
    MISSION(300),
    /** Winning a game outright: the top score, not shared. */
    VICTORY(500),
}
