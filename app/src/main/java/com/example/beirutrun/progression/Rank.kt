package com.example.beirutrun.progression

import androidx.annotation.DrawableRes
import com.example.beirutrun.R

/**
 * The 20 military ranks, lowest first: level 1 is [PRIVATE], the top one [BEIRUT_LEGEND]. Every
 * screen gets a rank's names, badge and XP from here.
 *
 * Both names are always shown together (Arabic above English, like on a uniform), so they are
 * data here rather than translated strings. Each badge is a drawable named after the rank; draw a
 * new one under the same name to replace it.
 */
enum class Rank(val arabicName: String, val englishName: String, @DrawableRes val badge: Int) {
    PRIVATE("جندي", "Private", R.drawable.rank_private),
    PRIVATE_FIRST_CLASS("جندي أول", "Private First Class", R.drawable.rank_private_first_class),
    CORPORAL("عريف", "Corporal", R.drawable.rank_corporal),
    SERGEANT("رقيب", "Sergeant", R.drawable.rank_sergeant),
    STAFF_SERGEANT("رقيب أول", "Staff Sergeant", R.drawable.rank_staff_sergeant),
    WARRANT_OFFICER("معاون", "Warrant Officer", R.drawable.rank_warrant_officer),
    CHIEF_WARRANT_OFFICER("معاون أول", "Chief Warrant Officer", R.drawable.rank_chief_warrant_officer),
    SECOND_LIEUTENANT("ملازم", "Second Lieutenant", R.drawable.rank_second_lieutenant),
    FIRST_LIEUTENANT("ملازم أول", "First Lieutenant", R.drawable.rank_first_lieutenant),
    CAPTAIN("نقيب", "Captain", R.drawable.rank_captain),
    MAJOR("رائد", "Major", R.drawable.rank_major),
    LIEUTENANT_COLONEL("مقدم", "Lieutenant Colonel", R.drawable.rank_lieutenant_colonel),
    COLONEL("عقيد", "Colonel", R.drawable.rank_colonel),
    BRIGADIER_GENERAL("عميد", "Brigadier General", R.drawable.rank_brigadier_general),
    MAJOR_GENERAL("لواء", "Major General", R.drawable.rank_major_general),
    GENERAL("عماد", "General", R.drawable.rank_general),
    FIELD_COMMANDER("قائد ميداني", "Field Commander", R.drawable.rank_field_commander),
    SPECIAL_FORCES_COMMANDER("قائد القوات الخاصة", "Special Forces Commander", R.drawable.rank_special_forces_commander),
    SUPREME_COMMANDER("القائد الأعلى", "Supreme Commander", R.drawable.rank_supreme_commander),
    BEIRUT_LEGEND("أسطورة بيروت", "Beirut Legend", R.drawable.rank_beirut_legend);

    /** 1 for [PRIVATE] up to 20. */
    val level get() = ordinal + 1

    /** Total XP needed to reach this rank (see XpConfig.LEVEL_XP). */
    val xpRequired get() = XpConfig.LEVEL_XP[ordinal]

    /** The rank above this one; null for the top rank. */
    val next get() = entries.getOrNull(ordinal + 1)

    companion object {
        init {
            check(entries.size == XpConfig.LEVEL_XP.size) { "One XP threshold per rank" }
        }

        fun forLevel(level: Int): Rank = entries[level.coerceIn(1, entries.size) - 1]

        /** The rank a player with [totalXp] has earned. */
        fun forXp(totalXp: Long): Rank = forLevel(Progression.levelFor(totalXp))
    }
}
