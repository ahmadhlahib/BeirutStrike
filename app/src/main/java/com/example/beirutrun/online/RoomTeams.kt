package com.example.beirutrun.online

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import com.example.beirutrun.Team
import com.example.beirutrun.WorldRepository
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * Teams that players added to the current room, on top of the built-in ones (see Teams), in
 * `rooms/{room}/teams/{id}`: name, colour, flag (a small WebP, base64) and who made it. Everyone
 * in the room sees them, and they go when the room is deleted.
 *
 * One room is followed at a time ([follow]); the team screen and the city both read from here, and
 * the GL thread asks for flags, so the teams are kept as an immutable map that is swapped whole.
 */
object RoomTeams {
    private class Entry(val team: Team, val flag: ByteArray?, val createdBy: String)

    @Volatile private var entries: Map<String, Entry> = emptyMap()
    private var roomId: String? = null
    private var ref: DatabaseReference? = null
    private var listener: ValueEventListener? = null
    private val watchers = LinkedHashSet<() -> Unit>()

    /** Starts following [room]'s teams (null: none, e.g. offline). Following the same room again does nothing. */
    fun follow(room: String?) {
        if (room == roomId) return
        listener?.let { ref?.removeEventListener(it) }
        ref = null
        listener = null
        roomId = room
        entries = emptyMap()
        notifyWatchers()
        val database = FirebaseSession.database() ?: return
        room ?: return
        val r = database.getReference("rooms/$room/teams")
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                entries = snapshot.children.mapNotNull(::entryFrom).associateBy { it.team.id }
                notifyWatchers()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Room teams: ${error.message}")
            }
        }
        r.addValueEventListener(l)
        ref = r
        listener = l
    }

    /** [onChange] runs (main thread) whenever the room's teams change. */
    fun watch(onChange: () -> Unit) { watchers += onChange }

    fun unwatch(onChange: () -> Unit) { watchers -= onChange }

    /** The teams added to this room, oldest first. */
    fun teams(): List<Team> = entries.values.map { it.team }

    fun byId(id: String?): Team? = id?.let { entries[it]?.team }

    /** The team's flag image (WebP bytes), for a team added to this room. */
    fun flag(id: String): ByteArray? = entries[id]?.flag

    fun createdBy(id: String): String? = entries[id]?.createdBy

    /** Whether the room has room for one more team. */
    val canAdd get() = roomId != null && entries.size < MAX_TEAMS

    /**
     * Adds a team named [name] in [color] (opaque RGB) with [flag] (from [encodeFlag]) to the
     * room. [onDone] gets the new team, or null if it failed.
     */
    fun create(userId: String, name: String, color: Int, flag: ByteArray, onDone: (Team?) -> Unit) {
        val database = FirebaseSession.database() ?: return onDone(null)
        val room = roomId ?: return onDone(null)
        val r = database.getReference("rooms/$room/teams").push()
        val id = CUSTOM_PREFIX + (r.key ?: return onDone(null))
        database.getReference("rooms/$room/teams/$id").setValue(mapOf(
            "name" to name,
            "color" to (color and 0xFFFFFF),
            "flag" to Base64.encodeToString(flag, Base64.NO_WRAP),
            "createdBy" to userId,
            "createdAt" to ServerValue.TIMESTAMP,
        )).addOnCompleteListener { task ->
            if (!task.isSuccessful) Log.w(TAG, "Add team failed", task.exception)
            onDone(if (task.isSuccessful) Team(id, name, color or OPAQUE) else null)
        }
    }

    /** Reports a team's name or flag as abusive (see OnlineWorld.report); [onDone] says if it was sent. */
    fun report(userId: String, team: Team, onDone: (Boolean) -> Unit) {
        val database = FirebaseSession.database() ?: return onDone(false)
        database.getReference("reports").push().setValue(mapOf(
            "kind" to "team",
            "by" to userId,
            "target" to (createdBy(team.id) ?: team.id),
            "targetName" to team.name.take(40),
            "detail" to team.id,
            "room" to roomId.orEmpty(),
            "at" to ServerValue.TIMESTAMP,
        )).addOnCompleteListener { onDone(it.isSuccessful) }
    }

    /**
     * Turns a picked picture into a flag: cropped to the middle at 3:2, [FLAG_WIDTH] × [FLAG_HEIGHT],
     * as WebP. Slow (reads and decodes the picture): call it off the main thread. Null if the
     * picture can't be read.
     */
    fun encodeFlag(context: Context, uri: Uri): ByteArray? {
        val temp = File(context.cacheDir, "flag_pick.img")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: return null
            val picture = WorldRepository.decodeScaled(temp, FLAG_WIDTH * 2) ?: return null
            // The largest 3:2 rectangle in the middle of the picture.
            val cropW = minOf(picture.width, (picture.height * 1.5f).roundToInt())
            val cropH = minOf(picture.height, (picture.width / 1.5f).roundToInt())
            val cropped = Bitmap.createBitmap(picture, (picture.width - cropW) / 2, (picture.height - cropH) / 2, cropW, cropH)
            val flag = Bitmap.createScaledBitmap(cropped, FLAG_WIDTH, FLAG_HEIGHT, true)
            val out = ByteArrayOutputStream()
            flag.compress(webp(), FLAG_QUALITY, out)
            listOf(picture, cropped, flag).distinct().forEach { it.recycle() }
            out.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the flag picture", e)
            null
        } finally {
            temp.delete()
        }
    }

    private fun entryFrom(s: DataSnapshot): Entry? {
        val id = s.key ?: return null
        val name = s.child("name").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: return null
        val color = (s.child("color").value as? Number)?.toInt() ?: return null
        val flag = s.child("flag").getValue(String::class.java)
            ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
        return Entry(Team(id, name, color or OPAQUE), flag, s.child("createdBy").getValue(String::class.java).orEmpty())
    }

    private fun notifyWatchers() = watchers.toList().forEach { it() }

    @Suppress("DEPRECATION")
    private fun webp(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

    private const val TAG = "RoomTeams"
    private const val OPAQUE = 0xFF shl 24
    /** Ids of teams added in a room start with this, so they never clash with the built-in ones. */
    private const val CUSTOM_PREFIX = "c_"
    /** At most this many teams can be added to one room. */
    const val MAX_TEAMS = 6
    const val FLAG_WIDTH = 300
    const val FLAG_HEIGHT = 200
    private const val FLAG_QUALITY = 80

    /** The colours a new team can pick from (opaque ARGB). */
    val colors = listOf(
        0xFFC62828, 0xFFEF6C00, 0xFFF9A825, 0xFF2E7D32, 0xFF00897B,
        0xFF1565C0, 0xFF283593, 0xFF6A1B9A, 0xFFAD1457, 0xFF4E342E, 0xFF212121, 0xFFECEFF1,
    ).map { it.toInt() }
}
