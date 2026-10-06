package com.example.beirutrun.online

import android.content.Context
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.example.beirutrun.Session
import com.example.beirutrun.progression.Wallet
import com.google.firebase.database.ServerValue
import java.net.URLDecoder
import java.net.URLEncoder

/** The money for inviting friends, and the sums shown for it. Plain Kotlin, so it can be unit tested. */
object InviteRewards {
    /** For each friend who installs the game from my link and opens it. */
    const val FRIEND = 50L
    /** Friends paid for at most (beyond that they still count, but pay nothing). */
    const val MAX_PAID_FRIENDS = 100

    /** All I've earned from [friends] who joined (paid up to the limit). */
    fun earned(friends: Int): Long = friends.coerceIn(0, MAX_PAID_FRIENDS) * FRIEND

    /** The Google Play link that installs the game and remembers [inviter] invited them. */
    fun playLink(packageName: String, inviter: String): String =
        "https://play.google.com/store/apps/details?id=$packageName&referrer=" +
            URLEncoder.encode("utm_source=invite&utm_medium=friend&inviter=$inviter", "UTF-8")

    /** Who invited this player, from Play's install referrer (`…&inviter=uid`), or null. */
    fun inviterIn(referrer: String?): String? {
        if (referrer.isNullOrBlank()) return null
        return referrer.split('&').firstNotNullOfOrNull { pair ->
            val (key, value) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: return@firstNotNullOfOrNull null
            if (key != "inviter") null
            else runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()?.takeIf { UID.matches(it) }
        }
    }

    /** Firebase user ids: letters and digits. */
    private val UID = Regex("[A-Za-z0-9]{6,128}")
}

/**
 * Inviting friends, with money as thanks (see [InviteRewards]):
 *
 * - My invite link ([InviteRewards.playLink]) installs the game from Google Play carrying my id.
 *   On a friend's first start, [register] reads it (Play's install referrer) and records the
 *   friend under my id: `referredBy/{friend}` once, and `referrals/{me}/{friend}`.
 * - My phone [collect]s friends not yet paid for, adds the money to my wallet, and marks them paid.
 *
 * The database rules allow each step only for the right player, once.
 */
object Referrals {
    private const val TAG = "Referrals"
    private const val PREFS = "referrals"
    private const val KEY_CHECKED = "install_referrer_checked"
    private const val KEY_INVITER = "inviter"
    private const val KEY_REGISTERED = "registered"
    private const val KEY_PAID = "paid"

    /** What I've got from inviting: the friends who joined. */
    data class Progress(val friends: Int) {
        val earned get() = InviteRewards.earned(friends)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** My invite link. */
    fun link(context: Context, uid: String) = InviteRewards.playLink(context.packageName, uid)

    /**
     * Signed in as [uid]: on the first start after installing, finds who invited me (if anyone)
     * and records it. Safe to call on every start.
     */
    fun register(context: Context, uid: String) {
        val app = context.applicationContext
        val p = prefs(app)
        if (p.getBoolean(KEY_REGISTERED, false)) return
        if (!p.getBoolean(KEY_CHECKED, false)) {
            readInstallReferrer(app) { inviter ->
                p.edit().putBoolean(KEY_CHECKED, true).putString(KEY_INVITER, inviter).apply()
                if (inviter != null) record(app, uid, inviter) else p.edit().putBoolean(KEY_REGISTERED, true).apply()
            }
        } else {
            val inviter = p.getString(KEY_INVITER, null)
            if (inviter != null) record(app, uid, inviter) else p.edit().putBoolean(KEY_REGISTERED, true).apply()
        }
    }

    /** Asks Google Play which link this install came from; [onDone] gets the inviter, or null. */
    private fun readInstallReferrer(context: Context, onDone: (String?) -> Unit) {
        val client = runCatching { InstallReferrerClient.newBuilder(context).build() }.getOrElse { return onDone(null) }
        runCatching {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(code: Int) {
                    val inviter = if (code == InstallReferrerClient.InstallReferrerResponse.OK)
                        runCatching { InviteRewards.inviterIn(client.installReferrer.installReferrer) }.getOrNull()
                    else null
                    runCatching { client.endConnection() }
                    // Not available now (e.g. Play busy): try again on the next start.
                    if (code == InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE) return
                    onDone(inviter)
                }
                override fun onInstallReferrerServiceDisconnected() = Unit
            })
        }.onFailure { onDone(null) }
    }

    /** Records that [inviter] invited me ([uid]); done for good once written (or refused). */
    private fun record(context: Context, uid: String, inviter: String) {
        val p = prefs(context)
        if (inviter == uid) return p.edit().putBoolean(KEY_REGISTERED, true).apply()
        val database = FirebaseSession.database() ?: return
        database.reference.updateChildren(mapOf(
            "referredBy/$uid" to inviter,
            "referrals/$inviter/$uid/name" to Session.name(context).orEmpty().take(40),
            "referrals/$inviter/$uid/at" to ServerValue.TIMESTAMP,
        )).addOnSuccessListener {
            Log.i(TAG, "Invited by $inviter")
            p.edit().putBoolean(KEY_REGISTERED, true).apply()
        }.addOnFailureListener {
            // Refused: already recorded, or an inviter who doesn't exist. Not tried again.
            Log.i(TAG, "Invite not recorded: ${it.message}")
            p.edit().putBoolean(KEY_REGISTERED, true).apply()
        }
    }

    /**
     * Signed in as [uid]: pays for friends who joined since last time, and tells [onDone] where
     * things stand and how much was just paid (0: nothing new). Runs on the main thread.
     */
    fun collect(
        context: Context, uid: String, onFailed: () -> Unit = {},
        onDone: (progress: Progress, paidNow: Long, newFriends: Int) -> Unit,
    ) {
        val app = context.applicationContext
        val database = FirebaseSession.database() ?: return onFailed()
        database.getReference("referrals/$uid").get().addOnSuccessListener { friends ->
            val p = prefs(app)
            val paidHere = p.getStringSet(KEY_PAID, emptySet()).orEmpty().toMutableSet()
            val all = friends.children.mapNotNull { it.key }
            val alreadyPaid = friends.children.count { it.child("paid").getValue(Boolean::class.java) == true }
            val toPay = friends.children
                .filter { it.child("paid").getValue(Boolean::class.java) != true && it.key !in paidHere }
                .mapNotNull { it.key }
                .take((InviteRewards.MAX_PAID_FRIENDS - alreadyPaid).coerceAtLeast(0))
            var paid = 0L
            if (toPay.isNotEmpty()) {
                paid = toPay.size * InviteRewards.FRIEND
                paidHere += toPay
                // Remembered here first, so a failed write can't pay twice on this phone.
                p.edit().putStringSet(KEY_PAID, paidHere).apply()
                Wallet.earn(app, paid)
                CareerWallet.upload(app)
                database.reference.updateChildren(toPay.associate { "referrals/$uid/$it/paid" to true })
                    .addOnFailureListener { Log.w(TAG, "Couldn't mark friends paid: ${it.message}") }
            }
            onDone(Progress(all.size), paid, toPay.size)
        }.addOnFailureListener {
            Log.w(TAG, "Couldn't read invites: ${it.message}")
            onFailed()
        }
    }
}
