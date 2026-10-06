package com.example.beirutrun

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/**
 * Invites to an online room, shared over WhatsApp (or anything else): a link to the website's
 * join page, which opens the game on the room (`beirutstrike://join?r=…`), or Google Play if the
 * game isn't installed. A room with a password carries it in the link, so friends get in with a
 * tap; whoever has the link can join.
 */
object RoomInvite {
    /** The website's join page (docs/join/), which hands the invite to the app. */
    const val PAGE = "https://ahmadhlahib.github.io/BeirutStrike/join/"
    private const val SCHEME = "beirutstrike"
    private const val HOST = "join"

    /** A room to join: its id, and its password if it has one. */
    data class Invite(val roomId: String, val password: String?)

    /** The link to share for room [roomId] called [name], with its [password] (empty: none). */
    fun link(roomId: String, name: String, password: String?): String =
        Uri.parse(PAGE).buildUpon()
            .appendQueryParameter("r", roomId)
            .appendQueryParameter("n", name.take(40))
            .apply { if (!password.isNullOrEmpty()) appendQueryParameter("p", password) }
            .build().toString()

    /** The invite in a link that opened the app (from the join page, or the page's own address), or null. */
    fun parse(uri: Uri?): Invite? {
        uri ?: return null
        val fromApp = uri.scheme == SCHEME && uri.host == HOST
        val fromPage = uri.scheme == "https" && uri.toString().startsWith(PAGE)
        if (!fromApp && !fromPage) return null
        val room = uri.getQueryParameter("r")?.takeIf { ROOM_ID.matches(it) } ?: return null
        return Invite(room, uri.getQueryParameter("p")?.takeIf { it.isNotEmpty() }?.take(64))
    }

    /** Shares an invite to room [roomId] called [name] on WhatsApp, or anywhere else if it isn't installed. */
    fun share(activity: Activity, roomId: String, name: String, password: String?) {
        val text = activity.getString(R.string.invite_message, name, link(roomId, name, password))
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        for (app in WHATSAPP) {
            try {
                activity.startActivity(Intent(send).setPackage(app))
                return
            } catch (_: ActivityNotFoundException) {
                // Not installed: try the next, then the share sheet.
            }
        }
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.invite_share_title)))
    }

    private val WHATSAPP = listOf("com.whatsapp", "com.whatsapp.w4b")
    /** Firebase push keys: letters, digits, '-' and '_'. */
    private val ROOM_ID = Regex("[A-Za-z0-9_-]{1,40}")
}
