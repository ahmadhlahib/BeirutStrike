package com.example.beirutrun.online

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.security.MessageDigest

/** A room in the room list. [online] is how many players are in it right now; [map] is a CityMaps id. */
data class RoomInfo(
    val id: String,
    val name: String,
    val hasPassword: Boolean,
    val createdBy: String,
    val createdAt: Long,
    val online: Int,
    val map: String,
    /** Server time a player was last in the room (or its creation time). */
    val lastActive: Long,
    /** How long a game in this room lasts, ms; 0 = no time limit (rooms made by older versions). */
    val durationMs: Long = 0L,
    /** Server time the game started (the first player entered the city); 0 = not started yet. */
    val startedAt: Long = 0L,
) {
    /** Server time the game ends, or 0 if it has no end (yet). */
    val endsAt get() = if (durationMs > 0 && startedAt > 0) startedAt + durationMs else 0L
}

/**
 * The list of rooms and joining them. In the database:
 *
 * - `roomList/{room}`: name, map, whether it has a password, who made it, when it was last
 *   active, how long its game lasts (`duration`, set once by its creator), when the game started
 *   (`startedAt`, set once by the first player into the city) and `online/{uid}` flags for the
 *   players in it.
 * - `roomKeys/{room}`: a hash of the password. Nobody can read it; the security rules compare it
 *   with what a joining player writes to `rooms/{room}/members/{uid}`, so a wrong password is
 *   refused by the server itself.
 * - `rooms/{room}/...`: everything inside the room (players, shots, drops), readable and
 *   writable only by members.
 *
 * Empty rooms are deleted: by the last player when they leave, or, if their phone just went away
 * (crash, no signal), by whoever next looks at the room list once it has been empty for
 * [EMPTY_ROOM_GRACE_MS]. The security rules only allow deleting a room that really is empty.
 */
class RoomDirectory(private val userId: String) {

    private val db = FirebaseSession.database()
    private var listRef: DatabaseReference? = null
    private var listListener: ValueEventListener? = null
    private var offsetRef: DatabaseReference? = null
    private var offsetListener: ValueEventListener? = null
    private var serverOffset = 0L

    val available get() = db != null

    /** Keeps [onRooms] up to date with the room list, busiest and newest first. */
    fun listen(onRooms: (List<RoomInfo>) -> Unit, onError: (String) -> Unit) {
        val database = db ?: return onError("Realtime Database unavailable")
        offsetRef = database.getReference(".info/serverTimeOffset").also { ref ->
            offsetListener = ref.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    serverOffset = (snapshot.value as? Number)?.toLong() ?: 0L
                }
                override fun onCancelled(error: DatabaseError) = Unit
            })
        }
        val ref = database.getReference("roomList")
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val rooms = snapshot.children.mapNotNull { s ->
                    val createdAt = (s.child("createdAt").value as? Number)?.toLong() ?: 0L
                    RoomInfo(
                        id = s.key ?: return@mapNotNull null,
                        name = s.child("name").getValue(String::class.java).orEmpty(),
                        hasPassword = s.child("hasPassword").getValue(Boolean::class.java) == true,
                        createdBy = s.child("createdBy").getValue(String::class.java).orEmpty(),
                        createdAt = createdAt,
                        online = s.child("online").childrenCount.toInt(),
                        map = s.child("map").getValue(String::class.java).orEmpty(),
                        lastActive = (s.child("lastActive").value as? Number)?.toLong() ?: createdAt,
                        durationMs = (s.child("duration").value as? Number)?.toLong() ?: 0L,
                        startedAt = (s.child("startedAt").value as? Number)?.toLong() ?: 0L,
                    )
                }
                val now = System.currentTimeMillis() + serverOffset
                val (stale, live) = rooms.partition { it.online == 0 && now - it.lastActive > EMPTY_ROOM_GRACE_MS }
                stale.forEach { delete(database, it.id) }
                onRooms(live.sortedWith(compareByDescending<RoomInfo> { it.online }.thenByDescending { it.createdAt }))
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Room list: ${error.message}")
                onError(error.message)
            }
        }
        ref.addValueEventListener(l)
        listRef = ref
        listListener = l
    }

    /** The server's clock, as far as this phone can tell. */
    fun serverNow() = System.currentTimeMillis() + serverOffset

    fun stop() {
        listListener?.let { listRef?.removeEventListener(it) }
        offsetListener?.let { offsetRef?.removeEventListener(it) }
        listListener = null
        offsetListener = null
    }

    /**
     * Creates a room on [map] (with an optional password) whose game lasts [durationMs], and joins
     * it. [onDone] gets the new room id.
     */
    fun create(name: String, password: String, map: String, durationMs: Long, onDone: (String?) -> Unit) {
        val database = db ?: return onDone(null)
        val id = database.getReference("roomList").push().key ?: return onDone(null)
        val hasPassword = password.isNotEmpty()
        val key = memberKey(id, password)
        val updates = mutableMapOf<String, Any?>(
            "roomList/$id/name" to name,
            "roomList/$id/map" to map,
            "roomList/$id/duration" to durationMs,
            "roomList/$id/hasPassword" to hasPassword,
            "roomList/$id/createdBy" to userId,
            "roomList/$id/createdAt" to ServerValue.TIMESTAMP,
            "roomList/$id/lastActive" to ServerValue.TIMESTAMP,
            "rooms/$id/members/$userId" to key,
        )
        if (hasPassword) updates["roomKeys/$id"] = key
        database.reference.updateChildren(updates).addOnCompleteListener { task ->
            if (!task.isSuccessful) Log.w(TAG, "Create room failed", task.exception)
            onDone(if (task.isSuccessful) id else null)
        }
    }

    /** Joins [room]; for a locked room the server checks [password]. [onDone] says if it worked. */
    fun join(room: RoomInfo, password: String, onDone: (Boolean) -> Unit) {
        val database = db ?: return onDone(false)
        val key = if (room.hasPassword) memberKey(room.id, password) else OPEN
        database.getReference("rooms/${room.id}/members/$userId").setValue(key)
            .addOnCompleteListener { task ->
                // Joining counts as activity, so the room isn't cleaned up while you pick a team.
                if (task.isSuccessful) touch(database, room.id)
                onDone(task.isSuccessful)
            }
    }

    companion object {
        private const val TAG = "RoomDirectory"
        /** How long a room may sit with nobody in it before anyone may delete it. */
        const val EMPTY_ROOM_GRACE_MS = 5 * 60 * 1000L
        /** What members of a room without a password write as their key. */
        const val OPEN = "open"

        /** The game lengths a room's creator can choose from, 30 seconds to an hour. */
        val durations = listOf(30, 60, 120, 180, 300, 600, 900, 1200, 1800, 2700, 3600).map { it * 1000L }
        const val DEFAULT_DURATION_MS = 600_000L

        /** Marks the room as in use now. */
        fun touch(database: FirebaseDatabase, roomId: String) {
            database.getReference("roomList/$roomId/lastActive").setValue(ServerValue.TIMESTAMP)
        }

        /** Deletes a room and everything in it (the rules refuse unless it is empty). */
        fun delete(database: FirebaseDatabase, roomId: String) {
            database.reference.updateChildren(mapOf(
                "roomList/$roomId" to null,
                "rooms/$roomId" to null,
                "roomKeys/$roomId" to null,
            )).addOnFailureListener { Log.w(TAG, "Couldn't delete room $roomId: ${it.message}") }
        }

        /** Deletes the room if nobody is in it any more (called by the last player as they leave). */
        fun deleteIfEmpty(database: FirebaseDatabase, roomId: String) {
            database.getReference("roomList/$roomId/online").get().addOnSuccessListener { online ->
                if (!online.hasChildren()) delete(database, roomId)
            }
        }

        /**
         * What a member writes to prove they know the password: a SHA-256 of the room id and the
         * password, so the password itself is never stored and differs per room.
         */
        fun memberKey(roomId: String, password: String): String {
            if (password.isEmpty()) return OPEN
            val digest = MessageDigest.getInstance("SHA-256").digest("$roomId:$password".toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
