package com.example.beirutrun.progression

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The logged-in player's XP and wins on this phone (SharedPreferences, like Session), the one
 * place XP is added ([addXp]). Level and rank are never stored: they are worked out from total XP
 * (see Progression). The same XP is also added to the player's online career (OnlineWorld.countXp),
 * so other players see their rank.
 *
 * Screens that show the rank can [observe] it; every change is delivered on the main thread.
 */
object PlayerProgress {
    private const val PREFS = "progress"
    private const val KEY_XP = "total_xp"
    private const val KEY_WINS = "wins"
    private const val KEY_LAST_WIN = "last_won_game"

    /** Called after XP was added, with what it did (rank-ups included). */
    fun interface Listener {
        fun onXpGained(gain: XpGain)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun state(context: Context): ProgressState = Progression.stateFor(prefs(context).getLong(KEY_XP, 0L))

    /**
     * Adds [amount] XP and saves it, then tells the listeners, who show a rank-up when
     * [XpGain.rankedUp]. Level and rank follow from the new total, up to the top level (XP keeps
     * counting past it).
     */
    fun addXp(context: Context, amount: Int): XpGain {
        val gain = synchronized(this) {
            val p = prefs(context)
            Progression.gain(p.getLong(KEY_XP, 0L), amount).also { p.edit().putLong(KEY_XP, it.after.totalXp).apply() }
        }
        if (gain.xp > 0) main.post { listeners.forEach { it.onXpGained(gain) } }
        return gain
    }

    /**
     * Brings this phone's XP and the online career's [careerXp] together; XP only goes up, so
     * the higher one wins. Raises this phone's if the career has more (no rank-up shown), and
     * returns how much the career is missing (to add to it, e.g. XP whose upload failed), 0 if
     * none. Rooms with a minimum rank check the career's XP.
     */
    fun syncWithCareer(context: Context, careerXp: Long): Long = synchronized(this) {
        val p = prefs(context)
        val local = p.getLong(KEY_XP, 0L)
        if (careerXp > local) p.edit().putLong(KEY_XP, careerXp).apply()
        (local - careerXp).coerceAtLeast(0L)
    }

    /** Adds the XP for [reward] (see XpConfig for the amounts). */
    fun award(context: Context, reward: XpReward): XpGain = addXp(context, reward.xp)

    /** Games won on this phone. */
    fun wins(context: Context): Int = prefs(context).getInt(KEY_WINS, 0)

    /**
     * Counts a win for the game [gameKey] (e.g. room and start time), once: coming back into a
     * finished game doesn't count it again. Returns whether it was counted.
     */
    fun recordWin(context: Context, gameKey: String): Boolean = synchronized(this) {
        val p = prefs(context)
        if (p.getString(KEY_LAST_WIN, null) == gameKey) return false
        p.edit().putString(KEY_LAST_WIN, gameKey).putInt(KEY_WINS, p.getInt(KEY_WINS, 0) + 1).apply()
        true
    }

    fun observe(listener: Listener) { listeners += listener }

    fun stopObserving(listener: Listener) { listeners -= listener }

    /** Forgets all progress on this phone (Session.deleteAll). */
    fun clear(context: Context) = prefs(context).edit().clear().apply()
}
