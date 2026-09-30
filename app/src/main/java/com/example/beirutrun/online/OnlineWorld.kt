package com.example.beirutrun.online

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.example.beirutrun.PhotoDrop
import com.example.beirutrun.Session
import com.example.beirutrun.WorldRepository
import com.example.beirutrun.city.Pickup
import com.example.beirutrun.city.PickupKind
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

/** Another player in the city, as last reported by their phone. */
data class RemotePlayer(
    val uid: String,
    val name: String,
    val x: Float,
    val z: Float,
    val heading: Float,
    val walking: Boolean,
    val say: String,
    /** Server time the current [say] was set; a new value means a new bubble. */
    val sayAt: Long,
    val faceVersion: Long,
    val updated: Long,
    /** Team id (see Teams); teammates can't hurt each other. */
    val team: String = "",
    /** Hits left before dying, out of 5. */
    val health: Int = 5,
    val dead: Boolean = false,
    /** Uid of whoever killed them, while [dead]. */
    val killedBy: String = "",
    /** Goes up by one per shot fired; the shot's start and direction are below. */
    val shotSeq: Long = 0L,
    val shotX: Float = 0f,
    val shotZ: Float = 0f,
    val shotDX: Float = 0f,
    val shotDZ: Float = 0f,
    /** Shot start height and vertical direction (shots fly in 3D). */
    val shotY: Float = 1.5f,
    val shotDY: Float = 0f,
    /** Lying down, crawling. */
    val prone: Boolean = false,
    /** Goes up by one per jump; the other phones replay the jump. */
    val jumpSeq: Long = 0L,
    /** The gun in their hands (a Weapon id). */
    val weapon: String = "",
)

/** One player's score in the room's game, kept after they leave so the scoreboard stays whole. */
data class PlayerStats(
    val uid: String,
    val name: String,
    val team: String,
    val kills: Int,
    val deaths: Int,
    /** Bullets fired. */
    val shots: Int,
    /** Bullets that hit an enemy. */
    val hits: Int,
    /** Bullets that hit this player. */
    val hitsTaken: Int,
) {
    /** Share of shots that hit, 0..1. */
    val accuracy get() = if (shots > 0) hits.toFloat() / shots else 0f

    /** A player's score is how many of their shots hit an enemy. */
    val score get() = hits

    companion object {
        /** Reads a `stats/{uid}` or `career/{uid}` entry. */
        fun from(s: DataSnapshot): PlayerStats? {
            fun count(key: String) = (s.child(key).value as? Number)?.toInt() ?: 0
            return PlayerStats(
                uid = s.key ?: return null,
                name = s.child("name").getValue(String::class.java).orEmpty(),
                team = s.child("team").getValue(String::class.java).orEmpty(),
                kills = count("kills"),
                deaths = count("deaths"),
                shots = count("shots"),
                hits = count("hits"),
                hitsTaken = count("hitsTaken"),
            )
        }
    }
}

/**
 * Shares one room's city between phones through Firebase Realtime Database. Inside
 * `rooms/{room}/` (members only, see [RoomDirectory]):
 *
 * - `players/{uid}`: each player's live position, team, health, shots and speech bubble,
 *   removed on disconnect.
 * - `drops/{id}`: dropped photo details; `dropPhotos/{id}`: the photo itself (base64 JPEG).
 * - `hits/{uid}/{id}`: bullets that hit that player. The shooter's phone decides a bullet hit and
 *   writes it here; the victim's phone counts hits, handles dying, and deletes them.
 * - `stats/{uid}`: that player's kills, deaths, shots, hits and hits taken for the scoreboard.
 *   Each phone only adds to its own, and they stay when the player leaves.
 *
 * - `pickups/{slot}`: an ammo pack or scope ([PickupKind.SLOTS] says which kind each slot holds),
 *   where it lies, and when someone took it (`takenAt`, 0 = still there). Taking one and putting
 *   it back somewhere new are transactions, so only one player gets each and it comes back once.
 *
 * Outside the room, `career/{uid}` adds up the same counts over every game played, for the
 * ranking screen and army ranks (see Army). Rooms that allow cheats (`roomList/{room}/cheats`)
 * are just for fun: their games don't add to anyone's career.
 *
 * The game clock is in the room list: `roomList/{room}/duration` (set by the room's creator) and
 * `startedAt`, which the first player into the city sets to the server's time.
 *
 * Shared by all rooms: `faces/{uid}` (face photos, base64 WebP) and `roomList/{room}/online/{uid}`
 * (who is in which room, for the room list).
 *
 * Photos live in the database rather than Cloud Storage so the free Spark plan is enough.
 * Each phone downloads a photo or face once and keeps it on disk.
 *
 * Without a Firebase configuration or a room, [configured] is false and nothing here runs.
 * All callbacks arrive on the main thread.
 */
class OnlineWorld(
    private val context: Context,
    private val repo: WorldRepository,
    private val roomId: String?,
    private val team: String,
) {

    enum class Status { NOT_CONFIGURED, CONNECTING, ONLINE, FAILED }

    interface Listener {
        fun onStatus(status: Status)
        fun onDrops(drops: List<PhotoDrop>)
        fun onPlayers(players: List<RemotePlayer>)
        fun onFacesChanged()
        /** Another player fired a shot (draw it). */
        fun onRemoteShot(player: RemotePlayer) = Unit
        /** One of [fromName]'s bullets hit me, taking [damage] hearts. */
        fun onHitBy(fromUid: String, fromName: String, damage: Int) = Unit
        /** A player I shot has just died. */
        fun onKilled(victimName: String) = Unit
        /** The room's game length or start time is known or changed (both server ms; 0 = unknown). */
        fun onGameClock(startedAt: Long, durationMs: Long) = Unit
        /** Everyone's scores in this room, as they change. */
        fun onStats(stats: List<PlayerStats>) = Unit
        /** Career scores (all games) of the players on this room's scoreboard, by uid. */
        fun onCareerScores(scores: Map<String, Int>) = Unit
        /** The ammo packs and scopes lying in the street right now. */
        fun onPickups(pickups: List<Pickup>) = Unit
        /** Whether this room allows cheat codes (then scores don't count toward the ranking). */
        fun onCheatsAllowed(allowed: Boolean) = Unit
    }

    var listener: Listener? = null

    val configured: Boolean = FirebaseSession.configured(context) && roomId != null

    /** Prefix for everything inside this room. */
    private val room = "rooms/$roomId/"
    private var inRoomList: DatabaseReference? = null

    /** This phone's Firebase user id, once signed in. */
    @Volatile var uid: String? = null
        private set

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("online", Context.MODE_PRIVATE)

    private var db: FirebaseDatabase? = null
    private var me: DatabaseReference? = null
    private val attached = mutableListOf<Pair<Query, Any>>()

    private var active = false
    private var paused = false
    private var stopped = false
    private var serverOffset = 0L

    private var name = ""
    private var x = 0f
    private var z = 0f
    private var heading = 0f
    private var walking = false
    private var say = ""
    private var health = 5
    private var dead = false
    private var killedBy = ""
    private var shotSeq = 0L
    private var prone = false
    private var jumpSeq = 0L
    private var weapon = ""
    private var sentX = Float.NaN
    private var sentZ = 0f
    private var sentHeading = 0f
    private var sentWalking = false
    private var sentProne = false
    private var sentJumpSeq = 0L
    private var lastWrite = 0L
    private var lastRoomTouch = 0L

    private var gameStartedAt = 0L
    private var gameDurationMs = 0L
    /** Whether the room allows cheats; null until the server says. Career counts wait until it's known. */
    private var cheatsAllowed: Boolean? = null
    private var startRequested = false

    /** Counts not yet added to my `stats/{uid}` (sent in batches: shooting can be many per second). */
    private val pendingStats = HashMap<String, Long>()
    private var statsDirty = true
    private var lastStatsWrite = 0L
    /** Career scores of the players on the scoreboard, each followed once it shows up. */
    private val careerScores = HashMap<String, Int>()

    private val players = HashMap<String, RemotePlayer>()
    private var remoteDrops: List<PhotoDrop> = emptyList()
    private val downloadingPhotos = HashSet<String>()
    private val downloadingFaces = HashSet<String>()

    /** Where another player's face is kept once downloaded. */
    fun remoteFaceFile(uid: String) = FaceStore.file(context, uid)

    // ---- Lifecycle ----------------------------------------------------------------------------

    fun start(playerName: String) {
        name = playerName
        if (!configured) {
            listener?.onStatus(Status.NOT_CONFIGURED)
            return
        }
        listener?.onStatus(Status.CONNECTING)
        FirebaseSession.signIn { userId ->
            if (userId != null) connect(userId) else listener?.onStatus(Status.FAILED)
        }
    }

    /** The app went to the background: disappear from other players' cities. */
    fun pause() {
        flushStats(force = true)
        paused = true
        active = false
        me?.removeValue()
        inRoomList?.removeValue()
    }

    fun resume() {
        paused = false
        if (me == null || stopped) return
        active = true
        writePresence()
        if (gameStartedAt == 0L) startGameClock()
    }

    /**
     * Leaves the city. When [leavingRoom] (the player really left, not just the screen being
     * rebuilt) and nobody else is in the room any more, the room is deleted.
     */
    fun stop(leavingRoom: Boolean = false) {
        flushStats(force = true)
        stopped = true
        active = false
        me?.removeValue()
        val database = db
        val roomToCheck = roomId
        inRoomList?.removeValue()?.addOnCompleteListener {
            if (leavingRoom && database != null && roomToCheck != null) RoomDirectory.deleteIfEmpty(database, roomToCheck)
        }
        for ((query, l) in attached) {
            when (l) {
                is ValueEventListener -> query.removeEventListener(l)
                is ChildEventListener -> query.removeEventListener(l)
            }
        }
        attached.clear()
        io.shutdown()
    }

    private fun connect(userId: String) {
        if (stopped) return
        val database = database() ?: run {
            listener?.onStatus(Status.FAILED)
            return
        }
        db = database
        uid = userId
        me = database.getReference("${room}players/$userId")
        inRoomList = database.getReference("roomList/$roomId/online/$userId")

        listenValue(database.getReference(".info/serverTimeOffset")) { snap ->
            serverOffset = (snap.value as? Number)?.toLong() ?: 0L
        }
        listenValue(database.getReference(".info/connected")) { snap ->
            val connected = snap.getValue(Boolean::class.java) == true
            if (connected) {
                // Re-arm on every reconnect: the server forgets it once it fires.
                me?.onDisconnect()?.removeValue()
                inRoomList?.onDisconnect()?.removeValue()
                if (active) writePresence()
            }
            listener?.onStatus(if (connected) Status.ONLINE else Status.CONNECTING)
        }
        listenPlayers(database)
        listenHits(database, userId)
        listenValue(database.getReference("${room}drops")) { snap -> onDropsSnapshot(snap) }
        listenGameClock(database)
        listenValue(database.getReference("${room}stats")) { snap -> onStatsSnapshot(snap) }
        listenValue(database.getReference("${room}pickups")) { snap -> onPickupsSnapshot(snap) }

        active = !paused
        if (active) writePresence()
        flushStats(force = true)
        uploadFace()
        uploadOfflineDrops()
    }

    private fun database(): FirebaseDatabase? = FirebaseSession.database()

    // ---- My presence --------------------------------------------------------------------------

    private fun writePresence() {
        val ref = me ?: return
        inRoomList?.setValue(true)
        touchRoom(force = true)
        ref.setValue(mapOf(
            "name" to name,
            "team" to team,
            "x" to x.toDouble(),
            "z" to z.toDouble(),
            "heading" to heading.toDouble(),
            "walking" to walking,
            "say" to say,
            "sayAt" to ServerValue.TIMESTAMP,
            "faceVersion" to uploadedFaceVersion(),
            "health" to health,
            "dead" to dead,
            "killedBy" to killedBy,
            "shotSeq" to shotSeq,
            "prone" to prone,
            "jumpSeq" to jumpSeq,
            "weapon" to weapon,
            "updated" to ServerValue.TIMESTAMP,
        ))
        markSent()
    }

    /** I switched guns ([id] is a Weapon id). */
    fun setWeapon(id: String) {
        if (id == weapon) return
        weapon = id
        me?.updateChildren(mapOf("weapon" to id))
    }

    /** Keeps the room's "last active" time fresh while I'm in it, so it isn't cleaned up as empty. */
    private fun touchRoom(force: Boolean = false) {
        val database = db ?: return
        val id = roomId ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastRoomTouch < ROOM_TOUCH_MS) return
        lastRoomTouch = now
        RoomDirectory.touch(database, id)
    }

    /** Called often (a few times a second); only sends when something visibly changed. */
    fun updatePose(newX: Float, newZ: Float, newHeading: Float, isWalking: Boolean, isProne: Boolean = false, jumps: Long = 0L) {
        x = newX; z = newZ; heading = newHeading; walking = isWalking; prone = isProne; jumpSeq = jumps
        val ref = me ?: return
        if (!active) return
        touchRoom()
        flushStats()
        respawnDuePickups()
        val now = System.currentTimeMillis()
        val changed = sentX.isNaN() ||
            hypot(x - sentX, z - sentZ) > 0.05f ||
            abs(heading - sentHeading) > 0.05f ||
            walking != sentWalking || prone != sentProne || jumpSeq != sentJumpSeq
        if (!changed && now - lastWrite < HEARTBEAT_MS) return
        ref.updateChildren(mapOf(
            "x" to x.toDouble(),
            "z" to z.toDouble(),
            "heading" to heading.toDouble(),
            "walking" to walking,
            "prone" to prone,
            "jumpSeq" to jumpSeq,
            "updated" to ServerValue.TIMESTAMP,
        ))
        markSent()
    }

    private fun markSent() {
        sentX = x; sentZ = z; sentHeading = heading; sentWalking = walking; sentProne = prone; sentJumpSeq = jumpSeq
        lastWrite = System.currentTimeMillis()
    }

    fun say(text: String) {
        say = text
        me?.updateChildren(mapOf("say" to text, "sayAt" to ServerValue.TIMESTAMP))
    }

    // ---- Shooting -----------------------------------------------------------------------------

    /** Tells everyone I fired, so their phones can draw the bullet. */
    fun sendShot(x: Float, y: Float, z: Float, dx: Float, dy: Float, dz: Float) {
        val ref = me ?: return
        if (!active) return
        shotSeq++
        ref.updateChildren(mapOf(
            "shotSeq" to shotSeq,
            "shotX" to x.toDouble(),
            "shotY" to y.toDouble(),
            "shotZ" to z.toDouble(),
            "shotDX" to dx.toDouble(),
            "shotDY" to dy.toDouble(),
            "shotDZ" to dz.toDouble(),
        ))
    }

    /** One of my bullets hit [victimUid]; their phone takes it from here. */
    /** One of my bullets hit [victimUid], taking [damage] hearts (see Weapon.damage). */
    fun sendHit(victimUid: String, damage: Int) {
        val database = db ?: return
        val from = uid ?: return
        database.getReference("${room}hits/$victimUid").push().setValue(mapOf(
            "from" to from,
            "fromName" to name,
            "damage" to damage,
            "at" to ServerValue.TIMESTAMP,
        ))
    }

    /** My health changed (hit, killed, or back after respawning). */
    fun setHealth(newHealth: Int, isDead: Boolean, killer: String) {
        health = newHealth
        dead = isDead
        killedBy = killer
        me?.updateChildren(mapOf("health" to health, "dead" to dead, "killedBy" to killedBy))
    }

    private fun listenHits(database: FirebaseDatabase, userId: String) {
        val ref = database.getReference("${room}hits/$userId")
        val l = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val at = snapshot.num("at").toLong()
                val from = snapshot.child("from").getValue(String::class.java).orEmpty()
                val fromName = snapshot.child("fromName").getValue(String::class.java).orEmpty()
                // Older versions of the app don't send the damage: one heart, as it always was.
                val damage = (snapshot.child("damage").value as? Number)?.toInt()?.coerceIn(1, MAX_DAMAGE) ?: 1
                snapshot.ref.removeValue()
                // Hits left over from before I joined (or while the app was closed) don't count.
                val now = System.currentTimeMillis() + serverOffset
                if (active && now - at < HIT_MAX_AGE_MS) listener?.onHitBy(from, fromName, damage)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onChildRemoved(snapshot: DataSnapshot) = Unit
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: DatabaseError) = logCancelled(error)
        }
        ref.addChildEventListener(l)
        attached += ref to l
    }

    // ---- Game clock and scores ----------------------------------------------------------------

    /** The server's clock, as far as this phone can tell. */
    fun serverNow() = System.currentTimeMillis() + serverOffset

    private fun listenGameClock(database: FirebaseDatabase) {
        val info = database.getReference("roomList/$roomId")
        listenValue(info.child("duration")) { snap ->
            gameDurationMs = (snap.value as? Number)?.toLong() ?: 0L
            listener?.onGameClock(gameStartedAt, gameDurationMs)
        }
        listenValue(info.child("cheats")) { snap ->
            val allowed = snap.value == true
            cheatsAllowed = allowed
            listener?.onCheatsAllowed(allowed)
        }
        listenValue(info.child("startedAt")) { snap ->
            gameStartedAt = (snap.value as? Number)?.toLong() ?: 0L
            if (gameStartedAt == 0L) startGameClock()
            listener?.onGameClock(gameStartedAt, gameDurationMs)
        }
    }

    /**
     * The first player into the city starts the room's game. The rules only accept the first
     * `startedAt`, so if two phones try at once the second write is simply refused.
     */
    private fun startGameClock() {
        val database = db ?: return
        if (startRequested || !active) return
        startRequested = true
        database.getReference("roomList/$roomId/startedAt").setValue(ServerValue.TIMESTAMP)
            .addOnFailureListener { Log.i(TAG, "Game clock already started: ${it.message}") }
    }

    /** I fired a bullet. */
    fun countShot() = addStat("shots")
    /** One of my bullets hit an enemy. */
    fun countHit() = addStat("hits")
    /** A bullet hit me. */
    fun countHitTaken() = addStat("hitsTaken")
    /** I died. */
    fun countDeath() = addStat("deaths")
    /** I killed someone. */
    fun countKill() = addStat("kills")

    private fun addStat(key: String) {
        pendingStats[key] = (pendingStats[key] ?: 0L) + 1
        statsDirty = true
    }

    /**
     * Adds my pending counts to this room's `stats/{uid}` and to my `career/{uid}` in one write,
     * at most every [STATS_WRITE_MS] unless [force]. They are increments, so counts from before
     * the screen was rebuilt, and from earlier games, are kept. In a room that allows cheats only
     * the room's stats are written, not the career.
     */
    private fun flushStats(force: Boolean = false) {
        val database = db ?: return
        val userId = uid ?: return
        if (!statsDirty) return
        val countsForCareer = !(cheatsAllowed ?: return)
        val now = System.currentTimeMillis()
        if (!force && now - lastStatsWrite < STATS_WRITE_MS) return
        lastStatsWrite = now
        val update = mutableMapOf<String, Any>()
        val paths = if (countsForCareer) listOf("${room}stats/$userId", "career/$userId") else listOf("${room}stats/$userId")
        for (path in paths) {
            update["$path/name"] = name
            update["$path/team"] = team
            for ((key, count) in pendingStats) update["$path/$key"] = ServerValue.increment(count)
        }
        if (countsForCareer) update["career/$userId/updated"] = ServerValue.TIMESTAMP
        pendingStats.clear()
        statsDirty = false
        database.reference.updateChildren(update)
            .addOnFailureListener { Log.w(TAG, "Stats update failed: ${it.message}") }
    }

    private fun onStatsSnapshot(snapshot: DataSnapshot) {
        val stats = snapshot.children.mapNotNull(PlayerStats::from)
        for (s in stats) {
            followCareer(s.uid)
            // Players who already left still get their face on the scoreboard.
            ensureFace(s.uid, null)
        }
        listener?.onStats(stats)
    }

    /** Keeps [careerScores] up to date for [userId] (their army rank depends on it). */
    private fun followCareer(userId: String) {
        val database = db ?: return
        if (careerScores.containsKey(userId)) return
        careerScores[userId] = 0
        listenValue(database.getReference("career/$userId/hits")) { snap ->
            careerScores[userId] = (snap.value as? Number)?.toInt() ?: 0
            listener?.onCareerScores(HashMap(careerScores))
        }
    }

    // ---- Pickups ------------------------------------------------------------------------------

    /** Where a new pickup may appear (a random spot in the street, from the city map). */
    var pickupSpot: (() -> Pair<Float, Float>)? = null

    /** One pickup slot as last seen in the database. */
    private class SlotState(val kind: PickupKind, val x: Float, val z: Float, val takenAt: Long)
    private var slots: Map<Int, SlotState> = emptyMap()
    private var pickupsLoaded = false
    /** Slots with a placing transaction under way, so each is only tried once at a time. */
    private val placing = HashSet<Int>()

    private fun onPickupsSnapshot(snapshot: DataSnapshot) {
        slots = PickupKind.SLOTS.indices.mapNotNull { slot ->
            val s = snapshot.child(slot.toString())
            val kind = PickupKind.byId(s.child("kind").getValue(String::class.java)) ?: return@mapNotNull null
            slot to SlotState(kind, s.num("x").toFloat(), s.num("z").toFloat(), s.num("takenAt").toLong())
        }.toMap()
        pickupsLoaded = true
        respawnDuePickups()
        listener?.onPickups(slots.filterValues { it.takenAt == 0L }.map { (slot, s) -> Pickup(slot, s.kind, s.x, s.z) })
    }

    /**
     * Puts out any pickup that is missing (a new room) or was taken long enough ago. Every phone
     * checks, but the transaction only lets the first one place it.
     */
    private fun respawnDuePickups() {
        if (!pickupsLoaded || !active) return
        val now = serverNow()
        for ((slot, kind) in PickupKind.SLOTS.withIndex()) {
            val s = slots[slot]
            when {
                s == null -> placePickup(slot, kind, expectedTakenAt = null)
                s.takenAt > 0 && now - s.takenAt > PickupKind.respawnMs(kind) -> placePickup(slot, kind, s.takenAt)
            }
        }
    }

    /**
     * Places slot [slot]'s pickup at a new random spot, if the slot is still as seen: empty
     * ([expectedTakenAt] null) or taken at [expectedTakenAt].
     */
    private fun placePickup(slot: Int, kind: PickupKind, expectedTakenAt: Long?) {
        val database = db ?: return
        val spot = pickupSpot ?: return
        if (!placing.add(slot)) return
        val (x, z) = spot()
        database.getReference("${room}pickups/$slot").runTransaction(object : Transaction.Handler {
            override fun doTransaction(current: MutableData): Transaction.Result {
                val takenAt = (current.child("takenAt").value as? Number)?.toLong()
                val stillAsSeen = if (expectedTakenAt == null) current.value == null else takenAt == expectedTakenAt
                if (!stillAsSeen) return Transaction.abort()
                current.value = mapOf("kind" to kind.id, "x" to x.toDouble(), "z" to z.toDouble(), "takenAt" to 0L)
                return Transaction.success(current)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                main.post { placing.remove(slot) }
                if (error != null) Log.w(TAG, "Placing pickup $slot failed: ${error.message}")
            }
        })
    }

    /**
     * Tries to pick up [pickup]. [onDone] (main thread) says whether I got it: false if someone
     * else was quicker, or it had already moved.
     */
    fun takePickup(pickup: Pickup, onDone: (Boolean) -> Unit) {
        val database = db ?: return onDone(false)
        val me = uid ?: return onDone(false)
        val takenAt = serverNow()
        database.getReference("${room}pickups/${pickup.slot}").runTransaction(object : Transaction.Handler {
            override fun doTransaction(current: MutableData): Transaction.Result {
                val kind = current.child("kind").getValue(String::class.java)
                val x = (current.child("x").value as? Number)?.toFloat() ?: return Transaction.abort()
                val z = (current.child("z").value as? Number)?.toFloat() ?: return Transaction.abort()
                val taken = (current.child("takenAt").value as? Number)?.toLong() ?: 0L
                if (kind != pickup.kind.id || taken != 0L || abs(x - pickup.x) > 0.5f || abs(z - pickup.z) > 0.5f) {
                    return Transaction.abort()
                }
                current.child("takenAt").value = takenAt
                current.child("takenBy").value = me
                return Transaction.success(current)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                main.post { onDone(committed && error == null) }
            }
        })
    }

    // ---- Reports and deleting my data ---------------------------------------------------------

    /**
     * Reports another player's [kind] of content ("photo", "message" or "player") for the app's
     * owner to review in the Firebase console (`reports/`, which nobody can read from the app).
     */
    fun report(kind: String, targetUid: String, targetName: String, detail: String, onDone: (Boolean) -> Unit) {
        val database = db ?: return onDone(false)
        val me = uid ?: return onDone(false)
        database.getReference("reports").push().setValue(mapOf(
            "kind" to kind,
            "by" to me,
            "target" to targetUid,
            "targetName" to targetName.take(40),
            "detail" to detail.take(200),
            "room" to roomId.orEmpty(),
            "at" to ServerValue.TIMESTAMP,
        )).addOnCompleteListener { main.post { onDone(it.isSuccessful) } }
    }

    /**
     * Deletes everything the server keeps about me: face photo, career, my stats and presence in
     * this room, and the photos I dropped here; then my anonymous account itself, so the next
     * sign-in starts afresh. [onDone] says whether the server data went.
     */
    fun deleteMyData(onDone: (Boolean) -> Unit) {
        val database = db ?: return onDone(false)
        val me = uid ?: return onDone(false)
        active = false
        val paths = mutableMapOf<String, Any?>(
            "faces/$me" to null,
            "career/$me" to null,
            "${room}stats/$me" to null,
            "${room}players/$me" to null,
            "roomList/$roomId/online/$me" to null,
        )
        for (drop in remoteDrops.filter { it.authorId == me }) {
            paths["${room}drops/${drop.id}"] = null
            paths["${room}dropPhotos/${drop.id}"] = null
        }
        database.reference.updateChildren(paths).addOnCompleteListener { task ->
            // Only once the data is gone: until then the same account can try again.
            if (task.isSuccessful) FirebaseSession.deleteAccount()
            else Log.w(TAG, "Deleting my data failed", task.exception)
            main.post { onDone(task.isSuccessful) }
        }
    }

    // ---- Other players ------------------------------------------------------------------------

    private fun listenPlayers(database: FirebaseDatabase) {
        val ref = database.getReference("${room}players")
        val l = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) = put(snapshot)
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = put(snapshot)
            override fun onChildRemoved(snapshot: DataSnapshot) {
                snapshot.key?.let { players.remove(it) }
                publishPlayers()
            }
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: DatabaseError) = logCancelled(error)

            private fun put(s: DataSnapshot) {
                val id = s.key ?: return
                val before = players[id]
                val player = RemotePlayer(
                    uid = id,
                    name = s.child("name").getValue(String::class.java).orEmpty(),
                    x = s.num("x").toFloat(),
                    z = s.num("z").toFloat(),
                    heading = s.num("heading").toFloat(),
                    walking = s.child("walking").getValue(Boolean::class.java) == true,
                    say = s.child("say").getValue(String::class.java).orEmpty(),
                    sayAt = s.num("sayAt").toLong(),
                    faceVersion = s.num("faceVersion").toLong(),
                    updated = s.num("updated").toLong(),
                    team = s.child("team").getValue(String::class.java).orEmpty(),
                    health = (s.child("health").value as? Number)?.toInt() ?: 5,
                    dead = s.child("dead").getValue(Boolean::class.java) == true,
                    killedBy = s.child("killedBy").getValue(String::class.java).orEmpty(),
                    shotSeq = s.num("shotSeq").toLong(),
                    shotX = s.num("shotX").toFloat(),
                    shotZ = s.num("shotZ").toFloat(),
                    shotDX = s.num("shotDX").toFloat(),
                    shotY = (s.child("shotY").value as? Number)?.toFloat() ?: 1.5f,
                    shotDY = s.num("shotDY").toFloat(),
                    prone = s.child("prone").getValue(Boolean::class.java) == true,
                    jumpSeq = s.num("jumpSeq").toLong(),
                    shotDZ = s.num("shotDZ").toFloat(),
                    weapon = s.child("weapon").getValue(String::class.java).orEmpty(),
                )
                players[id] = player
                // Events only for changes seen live, not for the state found on joining.
                if (before != null && id != uid) {
                    if (player.shotSeq != before.shotSeq) listener?.onRemoteShot(player)
                    if (player.dead && !before.dead && player.killedBy == uid) listener?.onKilled(player.name)
                }
                publishPlayers()
            }
        }
        ref.addChildEventListener(l)
        attached += ref to l
    }

    /** Sends the current list of other players, dropping anyone who stopped updating. */
    fun publishPlayers() {
        val now = System.currentTimeMillis() + serverOffset
        val list = players.values.filter { it.uid != uid && now - it.updated < STALE_MS }
        list.forEach { ensureFace(it.uid, it.faceVersion) }
        listener?.onPlayers(list)
    }

    // ---- Drops --------------------------------------------------------------------------------

    private fun onDropsSnapshot(snapshot: DataSnapshot) {
        remoteDrops = snapshot.children.mapNotNull { s ->
            val id = s.key ?: return@mapNotNull null
            PhotoDrop(
                id = id,
                x = s.num("x").toFloat(),
                z = s.num("z").toFloat(),
                yaw = s.num("yaw").toFloat(),
                caption = s.child("caption").getValue(String::class.java).orEmpty(),
                author = s.child("author").getValue(String::class.java).orEmpty(),
                team = s.child("team").getValue(String::class.java).orEmpty(),
                time = s.num("time").toLong(),
                authorId = s.child("authorId").getValue(String::class.java).orEmpty(),
            )
        }
        for (drop in remoteDrops) {
            if (!repo.photoFile(drop.id).exists()) downloadPhoto(drop.id)
            if (drop.authorId.isNotEmpty()) ensureFace(drop.authorId, null)
        }
        publishDrops()
    }

    /** Only drops whose photo is on this phone are shown; the rest appear when downloaded. */
    private fun publishDrops() {
        listener?.onDrops(remoteDrops.filter { repo.photoFile(it.id).exists() })
    }

    private fun downloadPhoto(id: String) {
        val database = db ?: return
        if (!downloadingPhotos.add(id)) return
        database.getReference("${room}dropPhotos/$id").get()
            .addOnSuccessListener { snap ->
                val data = snap.getValue(String::class.java)
                if (data == null) {
                    downloadingPhotos.remove(id)
                    return@addOnSuccessListener
                }
                background {
                    runCatching { repo.photoFile(id).writeBytes(Base64.decode(data, Base64.DEFAULT)) }
                    main.post {
                        downloadingPhotos.remove(id)
                        publishDrops()
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Photo $id download failed", e)
                downloadingPhotos.remove(id)
            }
    }

    /** Shares a drop. The photo and its details are written together, so no one sees half a drop. */
    fun publishDrop(drop: PhotoDrop, onDone: (Boolean) -> Unit = {}) {
        val database = db ?: return onDone(false)
        val authorId = uid ?: return onDone(false)
        val photo = repo.photoFile(drop.id)
        background {
            val encoded = encodeImage(photo, maxSize = 1024, Bitmap.CompressFormat.JPEG, quality = 78)
            main.post {
                if (encoded == null) return@post onDone(false)
                database.reference.updateChildren(mapOf(
                    "${room}dropPhotos/${drop.id}" to encoded,
                    "${room}drops/${drop.id}" to mapOf(
                        "x" to drop.x.toDouble(),
                        "z" to drop.z.toDouble(),
                        "yaw" to drop.yaw.toDouble(),
                        "caption" to drop.caption,
                        "author" to drop.author,
                        "authorId" to authorId,
                        "team" to drop.team,
                        "time" to drop.time,
                    ),
                )).addOnCompleteListener { task ->
                    if (!task.isSuccessful) Log.w(TAG, "Drop upload failed", task.exception)
                    onDone(task.isSuccessful)
                }
            }
        }
    }

    fun deleteDrop(drop: PhotoDrop) {
        db?.reference?.updateChildren(mapOf("${room}drops/${drop.id}" to null, "${room}dropPhotos/${drop.id}" to null))
        repo.photoFile(drop.id).delete()
    }

    /** Drops made while offline wait on this phone; share them once online. */
    private fun uploadOfflineDrops() {
        val pending = repo.drops()
        for (drop in pending) {
            publishDrop(drop) { ok ->
                if (!ok) return@publishDrop
                val left = repo.drops()
                left.removeAll { it.id == drop.id }
                repo.saveDrops(left)
            }
        }
    }

    // ---- Faces --------------------------------------------------------------------------------

    private fun uploadedFaceVersion(): Long =
        if (prefs.getString(KEY_FACE_UID, null) == uid) prefs.getLong(KEY_FACE_VERSION, 0L) else 0L

    /** Uploads this player's face if it changed since the last upload; call after retaking it. */
    fun uploadFace() {
        val database = db ?: return
        val userId = uid ?: return
        val file = Session.faceFile(context)
        val version = if (file.exists()) file.lastModified() else 0L
        if (version == uploadedFaceVersion()) return
        background {
            val encoded = if (version == 0L) null else encodeImage(file, maxSize = 192, webp(), quality = 80)
            main.post {
                val ref = database.getReference("faces/$userId")
                val task = if (encoded == null) ref.removeValue()
                else ref.setValue(mapOf("data" to encoded, "version" to version))
                task.addOnSuccessListener {
                    prefs.edit().putString(KEY_FACE_UID, userId).putLong(KEY_FACE_VERSION, version).apply()
                    me?.updateChildren(mapOf("faceVersion" to version))
                }
            }
        }
    }

    /**
     * Makes sure another player's face is on this phone. [version] comes from their presence
     * (null when only a drop mentions them: then fetch only if it was never downloaded).
     */
    private fun ensureFace(userId: String, version: Long?) {
        if (userId == uid) return
        val database = db ?: return
        val file = remoteFaceFile(userId)
        val known = prefs.getLong("face_$userId", -1L)
        if (version == 0L) {
            // They have no face photo (any more).
            if (file.exists()) {
                file.delete()
                prefs.edit().putLong("face_$userId", 0L).apply()
                listener?.onFacesChanged()
            }
            return
        }
        val needed = if (version == null) known == -1L else version != known
        if (!needed || !downloadingFaces.add(userId)) return
        database.getReference("faces/$userId").get()
            .addOnSuccessListener { snap ->
                val data = snap.child("data").getValue(String::class.java)
                val v = (snap.child("version").value as? Number)?.toLong() ?: 0L
                background {
                    if (data != null) runCatching { file.writeBytes(Base64.decode(data, Base64.DEFAULT)) }
                    main.post {
                        downloadingFaces.remove(userId)
                        prefs.edit().putLong("face_$userId", if (data != null) v else 0L).apply()
                        if (data != null) listener?.onFacesChanged()
                    }
                }
            }
            .addOnFailureListener { downloadingFaces.remove(userId) }
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /** Runs image work off the main thread; late Firebase callbacks after [stop] are ignored. */
    private fun background(block: () -> Unit) {
        if (io.isShutdown) return
        runCatching { io.execute(block) }
    }

    private fun listenValue(query: Query, onValue: (DataSnapshot) -> Unit) {
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onValue(snapshot)
            override fun onCancelled(error: DatabaseError) = logCancelled(error)
        }
        query.addValueEventListener(l)
        attached += query to l
    }

    private fun logCancelled(error: DatabaseError) {
        // Almost always the security rules: see firebase/database.rules.json.
        Log.w(TAG, "Listener cancelled: ${error.message}")
        listener?.onStatus(Status.FAILED)
    }

    private fun DataSnapshot.num(key: String): Number = (child(key).value as? Number) ?: 0

    private fun encodeImage(file: File, maxSize: Int, format: Bitmap.CompressFormat, quality: Int): String? {
        val bitmap = WorldRepository.decodeScaled(file, maxSize) ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(format, quality, out)
        bitmap.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    @Suppress("DEPRECATION")
    private fun webp(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

    companion object {
        private const val TAG = "OnlineWorld"
        private const val HEARTBEAT_MS = 20_000L
        /** How often the room's "last active" time is refreshed while playing. */
        private const val ROOM_TOUCH_MS = 30_000L
        /** Players whose phone hasn't reported for this long are hidden. */
        private const val STALE_MS = 75_000L
        /** Hits older than this when they arrive are ignored. */
        private const val HIT_MAX_AGE_MS = 10_000L
        /** The most hearts one hit can take (a player has 5; see Weapon.damage). */
        private const val MAX_DAMAGE = 5
        /** How often my scoreboard counts are sent while playing. */
        private const val STATS_WRITE_MS = 1_500L
        private const val KEY_FACE_UID = "face_uid"
        private const val KEY_FACE_VERSION = "face_version"
    }
}
