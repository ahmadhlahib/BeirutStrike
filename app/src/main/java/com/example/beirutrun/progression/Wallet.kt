package com.example.beirutrun.progression

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.beirutrun.city.Weapon
import java.util.concurrent.CopyOnWriteArraySet

/** Cash for what happens in an online game (not solo or cheat rooms, like XP). */
enum class CashReward(val cash: Long) {
    KILL(300),
    HEADSHOT(100),
    VICTORY(1_000),
}

/**
 * What the guns cost (the ones every player starts with are free), roughly by how strong they
 * are. Plain Kotlin, so prices can be unit tested.
 */
object GunPrices {
    fun of(gun: Weapon): Long = when (gun) {
        Weapon.M9, Weapon.AK47, Weapon.SVD -> 0
        Weapon.GLOCK17 -> 1_500
        Weapon.DEAGLE -> 3_000
        Weapon.MP5 -> 2_500
        Weapon.M4 -> 4_500
        Weapon.RPK -> 5_500
        Weapon.M249 -> 8_000
        Weapon.M24 -> 6_000
        Weapon.AWM -> 9_000
        Weapon.M82 -> 12_000
    }

    /** Free: everyone owns it from the start. */
    fun free(gun: Weapon) = of(gun) == 0L
}

/**
 * The player's money and the guns they own, on this phone (the "progress" preferences, next to
 * XP) and copied to their online career after every change ([CareerWallet]). Unlike XP, money
 * goes down when spent, so this phone's balance is the true one: the career only fills in a
 * phone that has never been matched with it, e.g. after its app data was cleared ([adoptCareer]).
 *
 * Screens that show the balance can [observe] it; changes are delivered on the main thread.
 */
object Wallet {
    private const val PREFS = "progress"
    private const val KEY_CASH = "cash"
    private const val KEY_GUNS = "owned_guns"
    /** Set once this phone's wallet has been matched with the career (or started fresh). */
    private const val KEY_SETTLED = "cash_settled"

    /** What a new player starts with: enough for a first gun. */
    const val STARTING_CASH = 2_000L

    fun interface Listener {
        fun onWalletChanged(cash: Long)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cash(context: Context): Long = prefs(context).getLong(KEY_CASH, STARTING_CASH)

    /** Ids of the guns bought (the free ones aren't listed). */
    fun ownedIds(context: Context): Set<String> =
        prefs(context).getString(KEY_GUNS, "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()

    fun owns(context: Context, gun: Weapon) = GunPrices.free(gun) || gun.id in ownedIds(context)

    /** Adds [amount] to the balance; returns the new one. */
    fun earn(context: Context, amount: Long): Long {
        if (amount <= 0) return cash(context)
        val now = synchronized(this) {
            val p = prefs(context)
            (p.getLong(KEY_CASH, STARTING_CASH) + amount).also { p.edit().putLong(KEY_CASH, it).apply() }
        }
        changed(now)
        return now
    }

    /** Buys [gun] if it isn't owned yet and there's enough money; returns whether it was bought. */
    fun buy(context: Context, gun: Weapon): Boolean {
        val now = synchronized(this) {
            val p = prefs(context)
            val owned = ownedIds(context)
            val price = GunPrices.of(gun)
            val cash = p.getLong(KEY_CASH, STARTING_CASH)
            if (GunPrices.free(gun) || gun.id in owned || cash < price) return false
            (cash - price).also {
                p.edit().putLong(KEY_CASH, it).putString(KEY_GUNS, (owned + gun.id).sorted().joinToString(",")).apply()
            }
        }
        changed(now)
        return true
    }

    /**
     * Matches this phone's wallet with the online career's ([careerCash], [careerGuns] as stored
     * there, null without one). A phone that has never been matched takes the career's money (its
     * app data was cleared); guns owned on either are kept. Returns what the career should
     * now hold (cash and guns), to write back.
     */
    fun adoptCareer(context: Context, careerCash: Long?, careerGuns: String?): Pair<Long, String> = synchronized(this) {
        val p = prefs(context)
        val guns = (ownedIds(context) + careerGuns.orEmpty().split(',').filter { id ->
            id.isNotBlank() && Weapon.entries.any { it.id == id }
        }).sorted().joinToString(",")
        val cash = if (!p.getBoolean(KEY_SETTLED, false) && careerCash != null) careerCash else p.getLong(KEY_CASH, STARTING_CASH)
        p.edit().putLong(KEY_CASH, cash).putString(KEY_GUNS, guns).putBoolean(KEY_SETTLED, true).apply()
        cash to guns
    }.also { changed(it.first) }

    /** The balance and the guns, as the career stores them. */
    fun forCareer(context: Context): Pair<Long, String> = cash(context) to ownedIds(context).sorted().joinToString(",")

    fun observe(listener: Listener) { listeners += listener }
    fun stopObserving(listener: Listener) { listeners -= listener }

    private fun changed(cash: Long) = main.post { listeners.forEach { it.onWalletChanged(cash) } }

    /** "$2,000". */
    fun format(cash: Long): String = "$" + String.format(java.util.Locale.US, "%,d", cash)
}
