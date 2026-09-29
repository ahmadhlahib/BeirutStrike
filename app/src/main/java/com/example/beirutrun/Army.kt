package com.example.beirutrun

import android.content.Context

/**
 * Army ranks, earned from a player's career score (successful shots, all games): more than
 * [COMMANDER_HITS] makes them a Commander, shown with a star by their face; everyone else is a Soldier.
 */
object Army {
    const val COMMANDER_HITS = 100

    fun isCommander(careerHits: Int) = careerHits > COMMANDER_HITS

    fun title(context: Context, careerHits: Int): String =
        context.getString(if (isCommander(careerHits)) R.string.rank_commander else R.string.rank_soldier)
}
