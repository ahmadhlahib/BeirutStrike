package com.example.beirutrun

import android.content.Context

/**
 * Players this phone has blocked (by user id): their speech bubbles and photo drops are hidden
 * here. Kept on the phone only; the blocked player isn't told.
 */
object Blocklist {
    private const val PREFS = "blocklist"
    private const val KEY = "blocked"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): Set<String> = prefs(context).getStringSet(KEY, emptySet()).orEmpty()

    fun isBlocked(context: Context, uid: String) = uid.isNotEmpty() && uid in all(context)

    fun block(context: Context, uid: String) {
        if (uid.isEmpty()) return
        prefs(context).edit().putStringSet(KEY, all(context) + uid).apply()
    }

    fun clear(context: Context) = prefs(context).edit().clear().apply()
}
