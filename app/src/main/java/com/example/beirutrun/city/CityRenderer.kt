package com.example.beirutrun.city

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.beirutrun.PhotoDrop
import com.example.beirutrun.WorldRepository
import com.example.beirutrun.online.RemotePlayer
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Draws the 3D city and runs the player: joystick movement relative to the camera, collisions with
 * buildings and trees, a third-person camera that stays out of walls, photo signs dropped by
 * players, and speech bubbles.
 *
 * UI threads talk to it only through the volatile fields and the small "pending" setters; all GL
 * work happens on the GL thread in [onDrawFrame].
 */
class CityRenderer(
    private val city: CityMap,
    /** The city's geometry, being built on a background thread; uploaded when the GL surface is ready. */
    private val sceneSource: java.util.concurrent.Future<CityScene>,
    private val repo: WorldRepository,
    private val playerName: String,
    /**
     * A character's animated model and its attachment points (see Characters), loaded on a
     * background thread; asked for once per character id.
     */
    private val rigFor: (String) -> java.util.concurrent.Future<SoldierRig>,
    /**
     * Character models for people in the street (assets/models/pedestrians/, see
     * Characters.pedestrians), loading on a background thread; none means they are all drawn
     * the simple way (see CityLife).
     */
    private val pedestrianRigs: List<java.util.concurrent.Future<SoldierRig>> = emptyList(),
    /** The character the player plays as. */
    private val playerCharacter: String,
    /** The detailed gun models (see GunMeshes), read on a background thread; guns without one are drawn from boxes. */
    private val gunMeshSource: java.util.concurrent.Future<GunMeshes.Library>? = null,
    /** Car models for the traffic (assets/models/cars/, see CarModels), read on a background thread; until then cars are the built-in shapes. */
    private val carModelSource: java.util.concurrent.Future<List<CarModel>>? = null,
    start: Triple<Float, Float, Float>?,
    private val streetPhotos: List<File>,
    /** Face photo file for the player, a drop's author, or another player (may not exist). */
    private val playerFace: () -> File,
    private val dropFace: (PhotoDrop) -> File,
    private val remoteFace: (RemotePlayer) -> File,
    /** Called on the main thread when the nearest photo within reach changes (null = none). */
    private val onNearbyDrop: (PhotoDrop?) -> Unit,
    /** Called on the main thread when the player fires: start x, y, z and direction x, y, z. */
    private val onShot: (Float, Float, Float, Float, Float, Float) -> Unit,
    /** Called on the main thread when one of the player's bullets hits another player: their uid, the hearts it takes, and whether it hit the head. */
    private val onHitPlayer: (String, Int, Boolean) -> Unit,
    /** The player's team id (see Teams); empty = no team. */
    private val playerTeam: String,
    /** A team's flag image and colour, for the label above each player. */
    private val teamFlag: (String) -> Bitmap?,
    private val teamColor: (String) -> Int?,
    /** A team's uniform and gear colours for the soldier model (null = neutral). */
    private val teamUniform: (String) -> Pair<Int, Int>?,
    /** Called on the main thread when the player pulls the trigger with no bullets left. */
    private val onOutOfAmmo: (Weapon) -> Unit = {},
    /** Called on the main thread when a reload starts (for its sounds) and when the new magazine is in. */
    private val onReloadStart: (Weapon) -> Unit = {},
    private val onReloaded: (Weapon) -> Unit = {},
    /** The text floating over a pickup, e.g. "AK-47 ×10". */
    private val pickupLabel: (PickupKind) -> String = { it.id },
    /** The words over the arms stores. */
    private val storeLabel: String = "$ Arms store",
    /** Called on the main thread when the player throws a grenade: its kind, and start x, y, z and velocity x, y, z. */
    private val onGrenadeThrown: (GrenadeKind, FloatArray) -> Unit = { _, _ -> },
    /** Called on the main thread when the player starts winding up a throw (the grenade now in hand), and with null if it's called off. */
    private val onHoldGrenade: (GrenadeKind?) -> Unit = {},
    /** Called on the main thread when the player tries to throw a grenade of a kind they have none of. */
    private val onNoGrenade: (GrenadeKind) -> Unit = {},
    /** Called on the main thread when any grenade goes off (for its sound): kind, x, y, z, and whether it was mine. */
    private val onGrenadeBurst: (GrenadeKind, Float, Float, Float, Boolean) -> Unit = { _, _, _, _, _ -> },
    /** Called on the main thread when a flashbang blinds the player: how badly, 0..1. */
    private val onFlashed: (Float) -> Unit = {},
    /** Called on the main thread when the player's own grenade hurts them: the hearts it takes. */
    private val onSelfHit: (Int) -> Unit = {},
) : GLSurfaceView.Renderer {

    /** Someone my bullets can hurt: alive, and not on my team. */
    private fun isEnemy(r: RemoteAvatar) =
        !r.player.dead && (playerTeam.isEmpty() || r.player.team != playerTeam)

    // ---- State shared with the UI thread ------------------------------------------------------

    /** Joystick input, each -1..1 (y is down on screen, so -1 means forward). */
    @Volatile var joyX = 0f
    @Volatile var joyY = 0f

    /** Resume where the player was, unless that spot is inside something (e.g. saved on an older map). */
    private val resume = start?.takeIf { !city.isBlocked(it.first, it.second, BODY_RADIUS) }

    @Volatile var playerX = resume?.first ?: city.spawnX
        private set
    @Volatile var playerZ = resume?.second ?: city.spawnZ
        private set
    /** Camera direction; the camera looks along (sin yaw, -cos yaw). */
    @Volatile var yaw = start?.third ?: 0f
        private set
    /** Direction the character faces, same convention as [yaw]. */
    @Volatile var heading = yaw
        private set
    @Volatile var isWalking = false
        private set

    private val lookLock = Any()
    private var pendingYaw = 0f
    private var pendingPitch = 0f

    @Volatile private var pendingDrops: List<PhotoDrop>? = null
    @Volatile private var pendingSpeech: String? = null
    @Volatile private var pendingFaceReload = false
    @Volatile private var pendingRemote: List<RemotePlayer>? = null

    fun look(dxPixels: Float, dyPixels: Float) = synchronized(lookLock) {
        // Through the scope everything is magnified, so turn slower to aim precisely.
        val sensitivity = if (scoped) scopeFov() / NORMAL_FOV else 1f
        pendingYaw += dxPixels * 0.0065f * sensitivity
        pendingPitch += dyPixels * 0.004f * sensitivity
    }

    // ---- Guns ---------------------------------------------------------------------------------

    /** The gun in the player's hands. Switching guns stops a reload. */
    @Volatile var weapon = Weapon.AK47

    /**
     * Rounds in each gun's loaded magazine, and in its spare magazines (by [Weapon.ordinal]). The
     * GL thread fires and reloads; pickups add spare magazines from the main thread.
     */
    private val loaded = java.util.concurrent.atomic.AtomicIntegerArray(Weapon.entries.size).also { a ->
        Weapon.entries.forEach { a.set(it.ordinal, it.magazine) }
    }
    private val spare = java.util.concurrent.atomic.AtomicIntegerArray(Weapon.entries.size).also { a ->
        Weapon.entries.forEach { a.set(it.ordinal, it.startAmmo - it.magazine) }
    }

    fun loaded(w: Weapon) = loaded.get(w.ordinal)

    fun spare(w: Weapon) = spare.get(w.ordinal)

    /** A magazine picked up for [w]. */
    fun addMagazine(w: Weapon) { spare.addAndGet(w.ordinal, w.magazine) }

    /** After respawning: every gun back to at least its starting rounds, magazine loaded, and the grenades too. */
    fun refillToStart() {
        Weapon.entries.forEach { w ->
            loaded.set(w.ordinal, w.magazine)
            spare.getAndUpdate(w.ordinal) { maxOf(it, w.startAmmo - w.magazine) }
        }
        GrenadeKind.entries.forEach { k -> grenadesLeft.getAndUpdate(k.ordinal) { maxOf(it, k.carried) } }
    }

    // ---- Grenades -----------------------------------------------------------------------------

    /** The kind of grenade the grenade button throws. */
    @Volatile var grenadeKind = GrenadeKind.FRAG

    /** Grenades of each kind left (by [GrenadeKind.ordinal]); the GL thread throws them. */
    private val grenadesLeft = java.util.concurrent.atomic.AtomicIntegerArray(GrenadeKind.entries.size).also { a ->
        GrenadeKind.entries.forEach { a.set(it.ordinal, it.carried) }
    }

    fun grenades(kind: GrenadeKind) = grenadesLeft.get(kind.ordinal)

    /** Whether there's room for another [kind] (see [GrenadeKind.most]). */
    fun canCarry(kind: GrenadeKind) = grenadesLeft.get(kind.ordinal) < kind.most

    /** A grenade picked up in the street (if there's room for it). */
    fun addGrenade(kind: GrenadeKind) { grenadesLeft.getAndUpdate(kind.ordinal) { minOf(it + 1, kind.most) } }

    @Volatile private var throwRequested = false

    /** Throws a grenade of [grenadeKind] where the camera looks (if there's one left). */
    fun throwGrenade() { throwRequested = true }

    private val remoteGrenades = java.util.concurrent.ConcurrentLinkedQueue<Pair<GrenadeKind, FloatArray>>()

    /**
     * Another player threw a grenade: it flies the same way here as on their phone. Their phone
     * decides whom its blast or fire hurts; a flashbang's flash is judged here, by where I look.
     */
    fun addRemoteGrenade(uid: String, kind: GrenadeKind, x: Float, y: Float, z: Float, vx: Float, vy: Float, vz: Float) {
        remoteAiming[uid] = SystemClock.uptimeMillis() + AIM_POSE_MS
        remoteGrenades.add(kind to floatArrayOf(x, y, z, vx, vy, vz))
    }

    /** The gun being reloaded (null = none) and when it's done (uptime ms); set by the GL thread. */
    @Volatile var reloading: Weapon? = null
        private set
    @Volatile private var reloadStartedAt = 0L
    @Volatile private var reloadEndsAt = 0L
    @Volatile private var reloadRequested = false

    /** Reload the gun in hand (if its magazine isn't full and there's a spare one). */
    fun reload() { reloadRequested = true }

    /** How far the current reload has got, 0..1, or null when not reloading. */
    fun reloadProgress(): Float? {
        reloading ?: return null
        val span = (reloadEndsAt - reloadStartedAt).coerceAtLeast(1L)
        return ((SystemClock.uptimeMillis() - reloadStartedAt) / span.toFloat()).coerceIn(0f, 1f)
    }

    /** The scope's magnification while [scoped]. */
    @Volatile var zoom = Weapon.PICKUP_SCOPE_ZOOM

    /** Field of view through the scope: the normal view narrowed [zoom] times. */
    private fun scopeFov() = deg(2f * atan(tan(Math.toRadians(NORMAL_FOV / 2.0).toFloat()) / zoom))

    /** Cheat: shooting never uses up bullets. */
    @Volatile var unlimitedAmmo = false

    /** Cheat: every gun fires automatically, and faster (see [RAPID_FIRE_SCALE]). */
    @Volatile var rapidFire = false

    /** Cheat: walking and running speed multiplier (1 = normal). */
    @Volatile var speedBoost = 1f

    /** Looking through the AK-47's scope: zoomed in, in first person, seeing and shooting further. */
    @Volatile var scoped = false

    /** Shoot was just pressed (the pistol fires once per press; the AK-47 while [triggerHeld]). */
    @Volatile private var triggerPulled = false

    fun pullTrigger() { triggerPulled = true }

    /** Seen through the soldier's eyes: the chosen first-person view, or looking through the scope. */
    private val eyeView get() = firstPerson || scoped

    @Volatile private var pendingPickups: List<Pickup>? = null
    private var pickups: List<Pickup> = emptyList()

    /** The ammo packs and scopes lying in the street. */
    fun setPickups(list: List<Pickup>) { pendingPickups = list.toList() }

    fun setDrops(drops: List<PhotoDrop>) { pendingDrops = drops.toList() }

    /** Shows [text] in a bubble above the player for a few seconds. */
    fun say(text: String) { pendingSpeech = text }

    /** A face photo changed or arrived; reload face textures on the next frame. */
    fun reloadFaces() { pendingFaceReload = true }

    /** Teams were added to the room: look again for flags that weren't known before. */
    fun reloadTeams() { pendingTeamReload = true }
    @Volatile private var pendingTeamReload = false

    /** Latest positions of the other players (from Firebase). */
    fun setRemotePlayers(players: List<RemotePlayer>) { pendingRemote = players }

    /** True while the Shoot button is held; an automatic gun keeps firing meanwhile. */
    @Volatile var triggerHeld = false

    /** The player's remaining health, shown in the bar above their head. */
    @Volatile var health = MAX_HEALTH

    /** The player has been killed: lies on the ground, can't walk or shoot. */
    @Volatile var down = false

    /** First-person view: see through the soldier's eyes (only the rifle shows) instead of from behind. */
    @Volatile var firstPerson = false

    /** Lying on the ground (crawling): slow, low, hard to hit; can't run or jump. */
    @Volatile var prone = false

    /** How high the player's feet are right now, metres: the floor they stand on, plus a jump. */
    @Volatile var playerY = 0f
        private set

    // ---- Ladders and roofs --------------------------------------------------------------------

    /** The ladders up buildings on this map (the same on every phone, see Ladders). */
    val ladders: List<Ladder> = Ladders.place(city)

    /** The arms stores on this map (the same on every phone, see Stores). */
    val stores: List<Store> = Stores.place(city)

    /** The store the player is close enough to shop at, or null. */
    @Volatile var nearStore: Store? = null
        private set

    /** Passing cars and people (scenery, made up on this phone; see CityLife). */
    private val life = CityLife(city, RoadNetwork(city.roads))
    private var lifeMesh: StreamMesh? = null
    private var paletteTexture = 0
    /** The car models on the GPU (see carModelSource), once read and uploaded. */
    private class CarGl(val body: Mesh, val wheels: List<Mesh>, val texture: Int, val model: CarModel)
    private var carGl: List<CarGl>? = null
    private val carMatrix = FloatArray(16)
    private val wheelMatrix = FloatArray(16)
    private var lifePeople = FloatArray(2)
    /** The nearest people in the street drawn with a character model (see pedestrianRigs), by id. */
    private val passersby = HashMap<Int, SoldierAnimator>()
    private var passerbyViews: List<CityLife.Passerby> = emptyList()
    /** How many times each was seen turning round (see CityLife.Passerby.turns), and who is turning now. */
    private val passerbyTurns = HashMap<Int, Int>()
    private val passerbyTurning = HashSet<Int>()

    /**
     * What the player stands on: the street (0) or a roof's height, rising and falling while on a
     * ladder. Other phones are sent it, so they draw (and can hit) the player up there.
     */
    @Volatile var floorY = 0f
        private set

    /** The building whose roof the player is on (null = in the street). */
    private var roof: CityMap.Building? = null

    /** What the Climb button would do now (see [climb]). */
    enum class LadderAction { NONE, UP, DOWN }

    @Volatile var ladderAction = LadderAction.NONE
        private set

    /** On a ladder right now (going up or down): no walking, shooting or throwing until off it. */
    @Volatile var climbing = false
        private set

    @Volatile private var climbRequested = false

    /** Climbs the ladder at whose foot the player stands, or back down the one beside them on a roof. */
    fun climb() { climbRequested = true }

    /** A climb under way: which ladder, which way, from where, and when it started (uptime ms). */
    private class Climb(val ladder: Ladder, val up: Boolean, val fromX: Float, val fromZ: Float, val start: Long) {
        /** Seconds stepping onto the ladder, on it, and stepping off at the other end. */
        val onto = if (up) 0.35f else 0.5f
        val rungs = ladder.height / CLIMB_SPEED
        val off = if (up) 0.5f else 0.35f
        val total get() = onto + rungs + off
    }
    private var climb: Climb? = null

    /** Goes up by one per jump, so other phones can play the same jump. */
    @Volatile var jumpSeq = 0L
        private set

    /** True while the centre-screen crosshair is on an enemy (the HUD turns it red). */
    @Volatile var aimOnTarget = false
        private set

    @Volatile private var jumpRequested = false
    @Volatile private var pendingRespawn: Pair<Float, Float>? = null
    private val remoteShots = java.util.concurrent.ConcurrentLinkedQueue<FloatArray>()
    /** Who fired recently (uid → until when), so their soldier plays a shooting animation. */
    private val remoteAiming = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Moves the player to a new spot (after being killed). */
    fun respawn(x: Float, z: Float) { pendingRespawn = x to z }

    /** Jumps, if standing on the ground (not while crawling or dead). */
    fun jump() { jumpRequested = true }

    /** Shows a bullet another player fired; it is only drawn, their phone decides what it hits. */
    fun addRemoteShot(uid: String, x: Float, y: Float, z: Float, dx: Float, dy: Float, dz: Float, gun: Weapon) {
        remoteAiming[uid] = SystemClock.uptimeMillis() + AIM_POSE_MS
        remoteShots.add(floatArrayOf(x, y, z, dx, dy, dz, gun.range, gun.bulletSpeed))
    }

    // ---- GL resources (recreated whenever the GL context is) ----------------------------------

    private lateinit var shader: CityShader
    private lateinit var cube: Mesh
    private lateinit var quad: Mesh
    private lateinit var ground: Mesh
    private lateinit var openSea: Mesh
    /** The see-through wall round the play area (null when the whole map is playable). */
    private var border: Mesh? = null
    /** Every ladder up a building, in one mesh (null when the map has none). */
    private var ladderMesh: Mesh? = null

    /** Which map edges (north, south, west, east) the sea reaches, so the view beyond is water. */
    private fun seaEdges(): List<Boolean> {
        val touch = BooleanArray(4)
        val e = 3f
        for (poly in city.sea) for (i in poly.indices step 2) {
            val x = poly[i]; val z = poly[i + 1]
            if (abs(z - city.minZ) < e) touch[0] = true
            if (abs(z - city.maxZ) < e) touch[1] = true
            if (abs(x - city.minX) < e) touch[2] = true
            if (abs(x - city.maxX) < e) touch[3] = true
        }
        return touch.toList()
    }
    private class TileMeshes(val centerX: Float, val centerZ: Float, val meshes: List<Pair<Surface, Mesh>>)
    private val tileMeshes = ArrayList<TileMeshes>()
    private var alwaysMeshes: List<Pair<Surface, Mesh>> = emptyList()
    private val surfaceTextures = IntArray(CityTextures.COUNT)
    private val murals = mutableListOf<Pair<Mesh, Int>>()

    private class Label(val texture: Int, val width: Float, val height: Float)
    private val labels = HashMap<String, Label>()
    private val faces = HashMap<String, Int>()

    /** Another player as drawn: eases towards their last reported position. */
    private class RemoteAvatar(var player: RemotePlayer) {
        var x = player.x
        var z = player.z
        var heading = player.heading
        var saying: String? = null
        var sayUntil = 0L
        var sayAt = -1L
        /** Flashes red until this time after one of my bullets hits them. */
        var flashUntil = 0L
        var soldier: SoldierAnimator? = null
        var lastX = x
        var lastZ = z
        var lastFloor = player.floor
        var speed = 0f
        /** The height they stand at (a roof, a ladder), easing towards [RemotePlayer.floor]. */
        var floor = player.floor
        /** Their jump, replayed here from [RemotePlayer.jumpSeq] so it's smooth: above [floor]. */
        var y = 0f
        /** Where their feet are. */
        val feet get() = floor + y
        var vy = 0f
        var jumpSeq = -1L
    }

    // ---- Soldiers -----------------------------------------------------------------------------

    /** Characters' rigs by id once loaded (see [rigFor]); the soldier's stands in while one loads. */
    private val rigSources = HashMap<String, java.util.concurrent.Future<SoldierRig>>()
    private val rigs = HashMap<String, SoldierRig?>()
    private var playerSoldier: SoldierAnimator? = null
    /** One shared idle pose for every photo-drop statue. */
    private var statueSoldier: SoldierAnimator? = null
    private var lastPlayerX = playerX
    private var lastPlayerZ = playerZ
    private var lastPlayerY = playerY
    private var playerSpeed = 0f
    /** GPU copies of each soldier's skinned mesh (recreated with the GL context). */
    private val soldierMeshes = HashMap<SoldierAnimator, List<DynamicMesh>>()
    private val uploadedVersion = HashMap<SoldierAnimator, Int>()
    /** GPU textures of each soldier model's embedded images (a realistic model's clothes). */
    private val modelTextures = HashMap<SoldierRig, List<Int>>()
    private val bone = FloatArray(16)
    private val boneWorld = FloatArray(16)
    /** A detailed gun model placed in a soldier's hand. */
    private val gunInHand = FloatArray(16)
    /** Where my rifle's muzzle was last drawn (world space), so bullets leave the gun. */
    private val muzzle = FloatArray(3)
    private var muzzleKnown = false
    /** First-person look up/down, radians (0 = level, positive = down). */
    private var lookPitch = 0f
    private var lastShotAt = 0L
    private var lastShotWeapon = Weapon.AK47
    private val cameraToWorld = FloatArray(16)
    private val remotes = HashMap<String, RemoteAvatar>()

    /** A bullet flying in 3D along (dx, dy, dz), a unit direction. */
    private class Bullet(
        var x: Float, var y: Float, var z: Float,
        val dx: Float, val dy: Float, val dz: Float,
        val mine: Boolean,
        /** How far it flies before falling away, and how fast (see [Weapon]). */
        val range: Float,
        val speed: Float,
        /** Hearts it takes from the player it hits (my bullets only). */
        val damage: Int = 1,
    ) {
        var travelled = 0f
    }
    private var vy = 0f
    private val bullets = ArrayList<Bullet>()

    /** A grenade in flight (or rolling), moved in fixed steps (see [GRENADE_STEP]). */
    private class Grenade(
        val kind: GrenadeKind,
        var x: Float, var y: Float, var z: Float,
        var vx: Float, var vy: Float, var vz: Float,
        /** Thrown by me: my phone decides whom it hurts. */
        val mine: Boolean,
    ) {
        var steps = 0
    }
    private val grenades = ArrayList<Grenade>()
    /**
     * A throw under way (uptime ms it started, 0 = none) and its grenade: the gun comes down,
     * the hand winds back and the grenade leaves it [THROW_RELEASE_MS] in, then the gun comes back.
     */
    @Volatile private var throwStartedAt = 0L
    private var throwing = GrenadeKind.FRAG
    private var thrown = false
    /** Time not yet simulated in whole grenade steps. */
    private var grenadeClock = 0f
    private var throwCooldown = 0f

    /** What a grenade hit in one step. */
    private enum class Contact { NONE, GROUND, WALL, ROOF }

    /**
     * A grenade that went off, while it still shows (and for smoke and fire, while it lasts):
     * where, and when (uptime ms). [puffs] are its puffs' places around it, x, y, z and a size each.
     */
    private class Blast(
        val kind: GrenadeKind, val x: Float, val y: Float, val z: Float, val mine: Boolean,
        val start: Long, val until: Long, val puffs: FloatArray,
    ) {
        /** A fire burns whoever stands in it once a second. */
        var nextBurn = start + BURN_FIRST_MS
    }
    private val blasts = ArrayList<Blast>()
    /** How hard the camera shakes after a blast nearby, 0..1; dies away. */
    private var shake = 0f
    /** A soft round puff for smoke, fire and flashes (made with the GL context). */
    private var puffTexture = 0
    private var fireCooldown = 0f
    /** Keeps the character facing where it shot for a moment. */
    private var aimTime = 0f
    /** Set while drawing an avatar that should flash red. */
    private var flashing = false

    /** Short-lived flashes at gun muzzles: x, y, z, direction x, y, z, end time (ms since [clockBase]). */
    private val muzzleFlashes = ArrayList<FloatArray>()
    private val clockBase = SystemClock.uptimeMillis()

    /**
     * Where the centre-screen crosshair points in the world this frame: the first wall, ground
     * or enemy along the camera's line of sight. Bullets fly from the muzzle to this point.
     */
    private var aimX = 0f
    private var aimY = 0f
    private var aimZ = 0f
    /** The camera's viewing direction (unit vector), set in updateCamera(). */
    private var camFx = 0f
    private var camFy = 0f
    private var camFz = -1f

    /** The gun muzzle: ahead of the chest and a little to the right, along the camera direction. */
    private fun muzzleX() = playerX + sin(yaw) * MUZZLE_FORWARD + cos(yaw) * MUZZLE_RIGHT
    private fun muzzleZ() = playerZ - cos(yaw) * MUZZLE_FORWARD + sin(yaw) * MUZZLE_RIGHT
    private fun muzzleY() = playerY + if (prone) PRONE_GUN_HEIGHT else BULLET_Y

    private class DropVisual(val drop: PhotoDrop, val texture: Int, val aspect: Float)
    private val dropVisuals = LinkedHashMap<String, DropVisual>()
    private var drops: List<PhotoDrop> = emptyList()

    // ---- Simulation ---------------------------------------------------------------------------

    private var pitch = 0.32f
    private var walking = false
    private var idleTime = 0f
    private var speech: String? = null
    private var speechUntil = 0L
    private var nearbyId: String? = null
    private var lastFrame = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private var eyeX = 0f
    private var eyeY = 0f
    private var eyeZ = 0f

    // ---- Matrices -----------------------------------------------------------------------------

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val mvp = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val base = FloatArray(16)
    private val part = FloatArray(16)

    // ---- GLSurfaceView.Renderer ---------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: every old GL id is gone, so forget them rather than deleting.
        labels.clear()
        faces.clear()
        dropVisuals.clear()
        murals.clear()
        soldierMeshes.clear()
        gunGl.clear()
        modelTextures.clear()
        uploadedVersion.clear()
        drops.let { if (pendingDrops == null) pendingDrops = it }

        GLES20.glClearColor(SKY[0], SKY[1], SKY[2], 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        shader = CityShader()
        uploadWorld()
        for (style in 0 until CityTextures.COUNT) {
            val bitmap = CityTextures.draw(style)
            surfaceTextures[style] = Textures.upload(bitmap, repeat = true)
            bitmap.recycle()
        }
        buildMurals()
        puffTexture = uploadAndRecycle(puffBitmap())
        paletteTexture = uploadAndRecycle(CityTextures.palette(CityLife.PALETTE))
        lifeMesh = StreamMesh()
        carGl = null
        lastFrame = SystemClock.uptimeMillis()
    }

    /** White, solid in the middle and fading to nothing at the edge: tinted, it's a puff of smoke or flame. */
    private fun puffBitmap(): Bitmap {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(
                size / 2f, size / 2f, size / 2f,
                intArrayOf(0xFFFFFFFF.toInt(), 0xB0FFFFFF.toInt(), 0x00FFFFFF),
                floatArrayOf(0f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP,
            )
        }
        android.graphics.Canvas(bitmap).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return bitmap
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = width / height.coerceAtLeast(1).toFloat()
    }

    private var aspect = 1f

    /**
     * Normal view, or zoomed in (and seeing further) through the scope. The near clipping plane
     * is a little in front of the eye, and its corners reach about twice that far out; right up
     * against a wall it would reach through it and show the inside of the building, so there it
     * comes closer (at a cost in depth precision far away, which only matters while there).
     */
    private fun updateProjection(zoomed: Boolean) {
        val far = if (zoomed) 900f else 600f
        val clear = city.wallClearance(eyeX, eyeY, eyeZ, NEAR_PLANE * 2.5f)
        val near = (clear / 2.2f).coerceIn(MIN_NEAR_PLANE, NEAR_PLANE)
        Matrix.perspectiveM(projection, 0, if (zoomed) scopeFov() else NORMAL_FOV, aspect, near, far)
        Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now

        applyPending(now)
        val zoomed = scoped
        update(dt)
        updateLife(dt)
        updateCamera()
        // After the camera has moved, as the near plane depends on how close it is to a wall.
        updateProjection(zoomed)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(shader.program)
        shineSet = -1f
        aoSet = -1f
        cutoutSet = -1f
        GLES20.glEnableVertexAttribArray(shader.aPos)
        GLES20.glEnableVertexAttribArray(shader.aNormal)
        GLES20.glEnableVertexAttribArray(shader.aUv)
        GLES20.glUniform1i(shader.uTex, 0)
        GLES20.glUniform3f(shader.uEye, eyeX, eyeY, eyeZ)
        GLES20.glUniform3f(shader.uLightDir, LIGHT[0], LIGHT[1], LIGHT[2])
        GLES20.glUniform3f(shader.uFogColor, SKY[0], SKY[1], SKY[2])
        // Through the scope the haze is pushed back, so distant streets and enemies show.
        val fogScale = if (zoomed) SCOPE_RANGE_SCALE else 1f
        GLES20.glUniform2f(shader.uFog, FOG_START * fogScale, FOG_END * fogScale)
        // The ground's height map, so walls darken near the ground under them on hills.
        val t = city.terrain
        if (groundTexture != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, groundTexture)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glUniform1i(shader.uGroundMap, 1)
            GLES20.glUniform4f(shader.uGround, t.x0 - t.cell / 2f, t.z0 - t.cell / 2f, 1f / (t.cols * t.cell), 1f / (t.rows * t.cell))
            GLES20.glUniform1f(shader.uGroundBase, t.minHeight)
        } else {
            GLES20.glUniform1f(shader.uGroundBase, NO_GROUND_MAP)
        }

        drawWorld()
        for (visual in dropVisuals.values) drawDrop(visual)
        drawPickups(now)
        drawStores()
        val isDown = down
        val fp = eyeView
        if (fp) muzzleKnown = false
        else playerSoldier?.let {
            val holding = if (throwStartedAt != 0L && !thrown) throwing else null
            drawSoldier(it, lookFor(playerTeam), faceTexture(playerFace()), playerX, playerZ, heading, playerY, prone && !isDown, weapon, holding, climbing)
        }
        for (r in remotes.values) {
            val soldier = r.soldier ?: continue
            if (!isWorthAnimating(r.x, r.z)) continue
            flashing = now < r.flashUntil
            val holding = if (r.player.dead) null else GrenadeKind.byId(r.player.grenadeHold)
            drawSoldier(soldier, lookFor(r.player.team), faceTexture(remoteFace(r.player)), r.x, r.z, r.heading, r.feet, r.player.prone && !r.player.dead, Weapon.byId(r.player.weapon), holding, r.player.climbing && !r.player.dead)
            flashing = false
        }
        drawBullets()
        drawGrenades()
        drawMuzzleFlashes(now)
        drawBlasts(now)

        // Transparent labels last so they blend over everything behind them.
        for (visual in dropVisuals.values) drawDropLabels(visual)
        drawPickupLabels()
        drawStoreLabels()
        for (r in remotes.values) {
            // Smoke hides who's behind it, name tag and all.
            if (smokeBetween(eyeX, eyeY, eyeZ, r.x, r.feet + 1.2f, r.z, now)) continue
            val saying = if (now < r.sayUntil && !r.player.dead) r.saying else null
            drawLabels(r.x, r.z, r.player.name, saying, r.player.health, r.player.team, labelLift(r.feet, r.player.prone))
        }
        if (!fp) drawLabels(playerX, playerZ, playerName, if (now < speechUntil && !isDown) speech else null, health, playerTeam, labelLift(playerY, prone))
        // Through the scope the gun is out of sight (the scope picture covers the screen).
        if (fp && !isDown && !zoomed) drawHeldGun(now)
    }

    // ---- Aiming -------------------------------------------------------------------------------

    /**
     * Where the centre of the screen points: follows the camera's line of sight to the first
     * wall, the ground, or an enemy's body (then the crosshair turns red), up to [AIM_RANGE].
     * It starts just past the player so your own soldier never blocks the aim.
     */
    private fun updateAim() {
        val ox = eyeX; val oy = eyeY; val oz = eyeZ
        var t = if (eyeView) 0.3f else hypot(playerX - ox, playerZ - oz) + 0.8f
        var onTarget = false
        // A little past the gun's range, so the crosshair turns red for any enemy it can reach.
        val range = maxOf(AIM_RANGE, weapon.range + 10f)
        while (t < range) {
            val x = ox + camFx * t
            val y = oy + camFy * t
            val z = oz + camFz * t
            if (city.underground(x, y, z) || city.isInsideBuilding(x, y, z, 0f)) break
            if (remotes.values.any { isEnemy(it) && hitsBody(it, x, y, z) }) { onTarget = true; break }
            t += BULLET_STEP
        }
        // An enemy in or behind smoke can't be seen, so the crosshair doesn't give them away.
        if (onTarget && smokeBetween(ox, oy, oz, ox + camFx * t, oy + camFy * t, oz + camFz * t, SystemClock.uptimeMillis())) {
            onTarget = false
        }
        aimOnTarget = onTarget
        aimX = ox + camFx * t
        aimY = (oy + camFy * t).coerceAtLeast(city.groundAt(aimX, oz + camFz * t))
        aimZ = oz + camFz * t
    }

    /**
     * Whether a point is inside another player's body: a standing (or jumping) body is an upright
     * cylinder; a crawling one lies flat along the ground in the direction they face.
     */
    private fun hitsBody(r: RemoteAvatar, x: Float, y: Float, z: Float): Boolean {
        if (r.player.prone) {
            // Lying on a roof, they're out of reach of anything below it.
            if (y > r.feet + PRONE_BODY_HEIGHT || y < r.feet - 0.1f) return false
            val hx = r.x + sin(r.heading) * PRONE_BODY_LENGTH
            val hz = r.z - cos(r.heading) * PRONE_BODY_LENGTH
            return CityMap.segmentDistance(x, z, r.x, r.z, hx, hz) < HIT_RADIUS * 0.8f
        }
        if (y < r.feet || y > r.feet + BODY_HEIGHT) return false
        return hypot(r.x - x, r.z - z) < HIT_RADIUS
    }

    /** Whether a bullet at height [y] that hit [r]'s body hit their head (standing only: lying down, the head is too low to tell). */
    private fun hitsHead(r: RemoteAvatar, y: Float): Boolean = !r.player.prone && y > r.feet + BODY_HEIGHT - HEAD_HEIGHT

    /** Turns [part] so its local z axis points along (dx, dy, dz). */
    private fun orientAlong(dx: Float, dy: Float, dz: Float) {
        val flat = hypot(dx, dz)
        Matrix.rotateM(part, 0, -deg(atan2(dx, -dz)), 0f, 1f, 0f)
        Matrix.rotateM(part, 0, deg(atan2(dy, flat)), 1f, 0f, 0f)
    }

    /**
     * First person: the gun in hand, drawn over everything so it never pokes into walls. It kicks
     * back and up on each shot (pistols and sniper rifles harder), sways while walking, dips down
     * and to the side while the magazine is changed, and flashes at the muzzle.
     */
    private fun drawHeldGun(now: Long) {
        Matrix.invertM(cameraToWorld, 0, view, 0)
        val gun = weapon
        val model = GunModels.of(gun)
        val mesh = meshOf(gun)
        val sinceShot = (now - lastShotAt) / 1000f
        val recover = when (lastShotWeapon.slot) {
            GunSlot.PISTOL -> 0.18f
            GunSlot.SNIPER -> 0.3f
            GunSlot.PRIMARY -> 0.08f
        }
        val kick = if (sinceShot < recover && lastShotWeapon == gun) (recover - sinceShot) / recover else 0f
        val dip = reloadProgress()?.let { sin(it * PI.toFloat()) } ?: 0f
        val t = now / 1000f
        val bobX = if (isWalking) sin(t * 4.5f) * 0.008f else 0f
        val bobY = if (isWalking) -abs(sin(t * 4.5f)) * 0.012f else sin(t * 1.3f) * 0.002f
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        // The gun and arms get their own narrower field of view, so they look bigger than the
        // world around them (as in most shooters), and a closer near plane so the hands aren't cut off.
        Matrix.perspectiveM(heldProjection, 0, HELD_GUN_FOV, aspect, 0.03f, 10f)
        Matrix.multiplyMM(viewProj, 0, heldProjection, 0, view, 0)
        val sleeve = teamUniform(playerTeam)?.first ?: 0xFF5E6266.toInt()
        // Camera space: x right, y up, looking down -z. The gun is angled a touch towards the
        // centre of the screen, where the crosshair is.
        val pistol = gun.slot == GunSlot.PISTOL
        if (pistol) {
            Matrix.translateM(base, 0, cameraToWorld, 0, 0.075f + bobX, -0.095f + bobY - dip * 0.06f, -0.36f + kick * 0.05f)
            Matrix.rotateM(base, 0, 3f, 0f, 1f, 0f)
            Matrix.rotateM(base, 0, kick * 14f - dip * 35f, 1f, 0f, 0f)
        } else {
            val kickBack = if (gun.slot == GunSlot.SNIPER) 0.06f else 0.035f
            val kickUp = if (gun.slot == GunSlot.SNIPER) 8f else 3f
            Matrix.translateM(base, 0, cameraToWorld, 0, 0.12f + bobX, -0.13f + bobY - dip * 0.08f, -0.4f + kick * kickBack)
            Matrix.rotateM(base, 0, 4f + dip * 20f, 0f, 1f, 0f)
            Matrix.rotateM(base, 0, 1.5f + kick * kickUp - dip * 25f, 1f, 0f, 0f)
        }
        // Throwing a grenade: the gun drops down out of the way and comes back after.
        val lower = gunLowered(now)
        if (lower > 0f) {
            Matrix.translateM(base, 0, -lower * 0.04f, -lower * 0.22f, lower * 0.06f)
            Matrix.rotateM(base, 0, -lower * 40f, 1f, 0f, 0f)
        }
        // The detailed model when there is one (in the same gun space), else the gun built from boxes.
        if (mesh != null) drawGunMesh(gun, mesh, base)
        else for (part in model.parts) tiltedBox(part.x, part.y, part.z, part.sx, part.sy, part.sz, part.pitch, part.color)
        val gripY = mesh?.gripY ?: model.gripY
        val gripZ = mesh?.gripZ ?: model.gripZ
        val supportZ = mesh?.supportZ ?: model.supportZ
        val supportY = mesh?.supportY ?: -0.03f
        // Real arms when they have been read (the right hand on the grip, the left under the
        // handguard, or both round a pistol's grip); boxes until then.
        val arms = library()?.arms.orEmpty()
        val right = arms[if (pistol) GunMeshes.Arm.PISTOL_RIGHT else GunMeshes.Arm.RIFLE_RIGHT]
        val left = arms[if (pistol) GunMeshes.Arm.PISTOL_LEFT else GunMeshes.Arm.RIFLE_LEFT]
        if (right != null && left != null) {
            if (pistol) {
                drawArm(right, GunMeshes.Arm.PISTOL_RIGHT, gripY, gripZ, sleeve)
                drawArm(left, GunMeshes.Arm.PISTOL_LEFT, gripY - 0.03f, gripZ - 0.03f, sleeve)
            } else {
                drawArm(right, GunMeshes.Arm.RIFLE_RIGHT, gripY, gripZ, sleeve)
                drawArm(left, GunMeshes.Arm.RIFLE_LEFT, supportY - 0.012f, supportZ, sleeve)
            }
        } else if (pistol) drawPistolHands(gripY, gripZ, sleeve)
        else drawRifleHands(gripY, gripZ, supportZ, supportY, sleeve)
        if (sinceShot < MUZZLE_FLASH_MS / 1000f && lastShotWeapon == gun) {
            val size = when (gun.slot) {
                GunSlot.PISTOL -> 0.07f
                GunSlot.PRIMARY -> 0.1f
                GunSlot.SNIPER -> 0.14f
            }
            muzzleFlash(0f, mesh?.muzzleY ?: model.muzzleY, (mesh?.muzzleZ ?: model.muzzleZ) - size * 0.3f, size)
        }
        drawThrowingHand(now, sleeve, bobX, bobY)
        // Back to the world's projection.
        Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0)
    }
    private val heldProjection = FloatArray(16)

    /** How far the gun is lowered for a throw, 0..1: down quickly, held, back up once the grenade is gone. */
    private fun gunLowered(now: Long): Float {
        val start = throwStartedAt
        if (start == 0L) return 0f
        val p = ((now - start) / THROW_ANIM_MS.toFloat()).coerceIn(0f, 1f)
        val k = when {
            p < 0.18f -> p / 0.18f
            p < 0.7f -> 1f
            else -> 1f - (p - 0.7f) / 0.3f
        }
        return k * k * (3f - 2f * k)
    }

    /**
     * First person, during a throw: the right hand comes up holding the grenade, draws back
     * beside the head, swings forward and lets go (the grenade leaves at [THROW_RELEASE_MS]),
     * then drops out of sight. In camera space, like the gun.
     */
    private fun drawThrowingHand(now: Long, sleeve: Int, bobX: Float, bobY: Float) {
        val start = throwStartedAt
        if (start == 0L) return
        val p = ((now - start) / THROW_ANIM_MS.toFloat()).coerceIn(0f, 1f)
        val release = THROW_RELEASE_MS / THROW_ANIM_MS.toFloat()
        // Key poses (when 0..1, x, y, z, wrist tilt back in degrees): below the screen, up and
        // cocked, drawn back to the edge of the view, forward at the release, and down out of
        // view after the follow-through. With the held gun's narrow view, a point shows while
        // |y| < 0.36 |z| and |x| < 0.65 |z|.
        val keys = arrayOf(
            floatArrayOf(0f, 0.2f, -0.28f, -0.35f, 0f),
            floatArrayOf(0.2f, 0.17f, 0.02f, -0.32f, 35f),
            floatArrayOf(release - 0.1f, 0.19f, 0.06f, -0.25f, 55f),
            floatArrayOf(release, 0.06f, 0.02f, -0.42f, -15f),
            floatArrayOf(0.75f, 0.04f, -0.3f, -0.4f, -40f),
            floatArrayOf(1f, 0.04f, -0.32f, -0.4f, -40f),
        )
        val i = (1 until keys.size).first { p <= keys[it][0] || it == keys.lastIndex }
        val a = keys[i - 1]; val b = keys[i]
        val span = (b[0] - a[0]).coerceAtLeast(1e-4f)
        val k0 = ((p - a[0]) / span).coerceIn(0f, 1f)
        val k = k0 * k0 * (3f - 2f * k0)
        fun mix(j: Int) = a[j] + (b[j] - a[j]) * k
        Matrix.translateM(base, 0, cameraToWorld, 0, mix(1) + bobX, mix(2) + bobY, mix(3))
        Matrix.rotateM(base, 0, -8f, 0f, 1f, 0f)
        Matrix.rotateM(base, 0, mix(4), 1f, 0f, 0f)
        // The grenade sits in the palm until it's thrown.
        if (p < release) {
            System.arraycopy(base, 0, gripMatrix, 0, 16)
            Matrix.translateM(base, 0, 0f, 0.035f, -0.01f)
            grenadeShape(throwing)
            System.arraycopy(gripMatrix, 0, base, 0, 16)
        }
        // The arm reaching in from the right, as round a pistol's grip; boxes until the arms are read.
        val arm = library()?.arms?.get(GunMeshes.Arm.PISTOL_RIGHT)
        if (arm != null) drawArm(arm, GunMeshes.Arm.PISTOL_RIGHT, 0f, 0f, sleeve)
        else {
            tiltedBox(0f, 0f, 0f, 0.07f, 0.075f, 0.08f, -16f, SKIN)
            tiltedBox(0.04f, -0.045f, 0.13f, 0.09f, 0.09f, 0.21f, -25f, sleeve, yawDeg = 15f)
        }
    }
    private val gripMatrix = FloatArray(16)
    private val soldierMatrix = FloatArray(16)

    /** Right hand on the pistol grip, left forward on the handguard, forearms in the team's uniform. */
    private fun drawRifleHands(gy: Float, gz: Float, sz: Float, sy: Float, sleeve: Int) {
        tiltedBox(0f, gy + 0.007f, gz, 0.072f, 0.075f, 0.085f, -18f, SKIN)                 // right hand
        tiltedBox(0.03f, gy - 0.045f, gz + 0.12f, 0.1f, 0.1f, 0.2f, -30f, sleeve)            // right forearm
        partBox(-0.004f, sy, sz, 0.078f, 0.06f, 0.1f, SKIN)                                // left hand
        tiltedBox(-0.07f, sy - 0.07f, sz + 0.08f, 0.09f, 0.09f, 0.22f, -25f, sleeve, yawDeg = -35f) // left forearm
    }

    /** A two-handed pistol grip: the right hand round it, the left cupping it, both arms reaching forward. */
    private fun drawPistolHands(gy: Float, gz: Float, sleeve: Int) {
        tiltedBox(0.008f, gy + 0.002f, gz + 0.005f, 0.06f, 0.075f, 0.07f, -16f, SKIN)
        tiltedBox(-0.022f, gy - 0.008f, gz, 0.05f, 0.06f, 0.075f, -16f, SKIN)
        tiltedBox(0.05f, gy - 0.04f, gz + 0.125f, 0.09f, 0.09f, 0.2f, -22f, sleeve, yawDeg = 18f)
        tiltedBox(-0.07f, gy - 0.05f, gz + 0.115f, 0.09f, 0.09f, 0.2f, -22f, sleeve, yawDeg = -24f)
    }

    // ---- Detailed gun models ------------------------------------------------------------------

    /** A gun model's GPU copy: one mesh per part, and its textures. */
    private class GunGl(val meshes: List<DynamicMesh>, val textures: IntArray)
    private val gunGl = HashMap<Any, GunGl>()
    private var gunLibrary: GunMeshes.Library? = null

    /** The gun models and arms once they have been read (see [gunMeshSource]), else null. */
    private fun library(): GunMeshes.Library? = gunLibrary ?: gunMeshSource?.takeIf { it.isDone }
        ?.let { runCatching { it.get() }.getOrNull() ?: GunMeshes.Library(emptyMap(), emptyMap()) }
        ?.also { gunLibrary = it }

    /** [w]'s detailed model, if there is one and it has been read. */
    private fun meshOf(w: Weapon): GunMesh? = library()?.guns?.get(w)

    /** Draws [mesh] (a gun, or an arm keyed by its [GunMeshes.Arm]) with [matrix], uploading it the first time. */
    private fun drawGunMesh(key: Any, mesh: GunMesh, matrix: FloatArray, sleeve: Int = 0) {
        val gl = gunGl.getOrPut(key) {
            GunGl(
                mesh.parts.map { p -> DynamicMesh(p.vertices.size, p.indices).also { it.update(p.vertices) } },
                IntArray(mesh.textures.size) { i ->
                    val bytes = mesh.textures[i]
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(::uploadAndRecycle) ?: 0
                },
            )
        }
        mesh.parts.forEachIndexed { i, p ->
            val texture = if (p.texture >= 0) gl.textures.getOrElse(p.texture) { 0 } else 0
            // Parts with no alpha are an arm's sleeve: the team's uniform. On a fabric texture
            // (mid grey) it's brightened first, so the cloth keeps the uniform's shade.
            val color = when {
                p.color ushr 24 != 0 -> p.color
                texture != 0 -> brighten(sleeve, SLEEVE_ON_FABRIC)
                else -> sleeve
            }
            drawMesh(gl.meshes[i], matrix, tint(color), texture)
        }
    }

    /** [color] with each channel multiplied by [k] (at most full). */
    private fun brighten(color: Int, k: Float): Int {
        fun ch(shift: Int) = (((color shr shift) and 0xFF) * k).toInt().coerceAtMost(255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** One posed arm, its palm moved to (0, [y], [z]) in the held gun's space ([base]). */
    private fun drawArm(arm: GunMesh, key: GunMeshes.Arm, y: Float, z: Float, sleeve: Int) {
        Matrix.translateM(armMatrix, 0, base, 0, 0f, y, z)
        drawGunMesh(key, arm, armMatrix, sleeve)
    }
    private val armMatrix = FloatArray(16)

    /** A bright flash at the muzzle (in [base] space), for the moment a shot leaves the gun. */
    private fun muzzleFlash(x: Float, y: Float, z: Float, size: Float) {
        partBox(x, y, z, size, size, size * 1.4f, 0xEEFFF59D.toInt())
        partBox(x, y, z - size * 0.6f, size * 0.5f, size * 0.5f, size, 0xDDFFB74D.toInt())
    }

    /**
     * A box part of [base] centred at (x, y, z), turned [pitchDeg] about x (positive tips its
     * front up) and [yawDeg] about y before sizing, for parts that aren't square to the gun.
     */
    private fun tiltedBox(
        x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, pitchDeg: Float, color: Int,
        yawDeg: Float = 0f,
    ) {
        Matrix.translateM(part, 0, base, 0, x, y, z)
        if (yawDeg != 0f) Matrix.rotateM(part, 0, yawDeg, 0f, 1f, 0f)
        Matrix.rotateM(part, 0, pitchDeg, 1f, 0f, 0f)
        Matrix.scaleM(part, 0, sx, sy, sz)
        draw(cube, part, tint(color))
    }

    // ---- Pickups ------------------------------------------------------------------------------

    /**
     * Ammo packs and scopes in the street: floating a little off the ground, turning slowly, with
     * a label above saying what they are.
     */
    private fun drawPickups(now: Long) {
        val t = (now - clockBase) / 1000f
        for (p in pickups) {
            val d = hypot(p.x - playerX, p.z - playerZ)
            if (d > PICKUP_DRAW_DISTANCE * (if (scoped) SCOPE_RANGE_SCALE else 1f)) continue
            val y = city.groundAt(p.x, p.z) + 0.35f + sin(t * 2f + p.slot) * 0.06f
            Matrix.setIdentityM(base, 0)
            Matrix.translateM(base, 0, p.x, y, p.z)
            Matrix.rotateM(base, 0, deg(t * 1.2f) + p.slot * 40f, 0f, 1f, 0f)
            when (p.kind) {
                PickupKind.AK_AMMO -> {
                    partBox(0f, 0f, 0f, 0.42f, 0.24f, 0.26f, 0xFF4B5320.toInt())      // olive crate
                    partBox(0f, 0.125f, 0f, 0.44f, 0.02f, 0.28f, 0xFF3A4118.toInt())  // lid
                    partBox(0f, 0f, 0.132f, 0.3f, 0.06f, 0.005f, 0xFFFFC107.toInt())  // stripe
                    partBox(0f, 0.15f, 0f, 0.12f, 0.03f, 0.05f, 0xFF2A2A2A.toInt())   // handle
                }
                PickupKind.PISTOL_AMMO -> {
                    partBox(0f, 0f, 0f, 0.3f, 0.16f, 0.2f, 0xFF263238.toInt())        // box
                    partBox(0f, 0f, 0.102f, 0.22f, 0.05f, 0.005f, 0xFFFFC107.toInt()) // stripe
                    partBox(0f, 0.085f, 0f, 0.31f, 0.012f, 0.21f, 0xFF37474F.toInt()) // lid
                }
                PickupKind.SCOPE -> {
                    partBox(0f, 0.05f, 0f, 0.07f, 0.07f, 0.34f, 0xFF1B1B1B.toInt())   // tube
                    partBox(0f, 0.05f, -0.18f, 0.09f, 0.09f, 0.05f, 0xFF1B1B1B.toInt()) // front bell
                    partBox(0f, 0.05f, -0.206f, 0.07f, 0.07f, 0.004f, 0xFF4FC3F7.toInt()) // lens
                    partBox(0f, 0.095f, 0f, 0.03f, 0.03f, 0.04f, 0xFF1B1B1B.toInt())  // turret
                    partBox(0f, -0.01f, 0f, 0.05f, 0.03f, 0.18f, 0xFF2A2A2A.toInt())  // mount
                }
                PickupKind.SNIPER_AMMO -> {
                    partBox(0f, 0f, 0f, 0.36f, 0.1f, 0.14f, 0xFF3E2723.toInt())       // long cartridge box
                    partBox(0f, 0.052f, 0f, 0.37f, 0.01f, 0.15f, 0xFF4E342E.toInt())  // lid
                    // A row of brass cartridges standing on top.
                    for (i in 0 until 5) partBox(-0.12f + i * 0.06f, 0.11f, 0f, 0.022f, 0.1f, 0.022f, 0xFFD4A537.toInt())
                }
                PickupKind.FRAG_GRENADE, PickupKind.FLASH_GRENADE, PickupKind.SMOKE_GRENADE, PickupKind.MOLOTOV -> {
                    val kind = p.kind.grenade ?: continue
                    // A small wooden crate with a band in the grenade's colour, two of them on top.
                    partBox(0f, 0f, 0f, 0.34f, 0.14f, 0.24f, 0xFF6D4C2F.toInt())
                    partBox(0f, 0f, 0.122f, 0.34f, 0.04f, 0.005f, kind.color)
                    partBox(0f, 0.072f, 0f, 0.35f, 0.012f, 0.25f, 0xFF5A3E26.toInt())
                    val crate = base.copyOf()
                    for (side in listOf(-1f, 1f)) {
                        // The grenade models are life-size; bigger here so they read from afar.
                        Matrix.translateM(base, 0, crate, 0, side * 0.08f, 0.17f, 0f)
                        Matrix.scaleM(base, 0, 1.6f, 1.6f, 1.6f)
                        grenadeShape(kind)
                    }
                    System.arraycopy(crate, 0, base, 0, 16)
                }
                PickupKind.MEDKIT_SMALL -> {
                    // A white first-aid box with a red cross on its lid and front.
                    partBox(0f, 0f, 0f, 0.3f, 0.14f, 0.2f, 0xFFF2F2F2.toInt())
                    partBox(0f, 0.072f, 0f, 0.31f, 0.012f, 0.21f, 0xFFDADADA.toInt())
                    medCross(0.079f, 0.11f, horizontal = true)
                    medCross(0.102f, 0.1f, horizontal = false)
                }
                PickupKind.MEDKIT_BIG -> {
                    // A red medical bag with a white cross, a handle and a darker zip.
                    partBox(0f, 0f, 0f, 0.46f, 0.26f, 0.28f, 0xFFC62828.toInt())
                    partBox(0f, 0.132f, 0f, 0.47f, 0.012f, 0.06f, 0xFF7F1717.toInt())
                    partBox(0f, 0.17f, 0f, 0.2f, 0.03f, 0.04f, 0xFF2A2A2A.toInt())
                    for (side in listOf(-0.09f, 0.09f)) partBox(side, 0.15f, 0f, 0.025f, 0.05f, 0.04f, 0xFF2A2A2A.toInt())
                    medCross(0.142f, 0.16f, horizontal = false, color = 0xFFFFFFFF.toInt())
                }
            }
        }
    }

    /**
     * A red (or [color]) cross [size] across: lying on a lid [at] metres up when [horizontal], else
     * standing on the front face [at] metres forward.
     */
    private fun medCross(at: Float, size: Float, horizontal: Boolean, color: Int = 0xFFD32F2F.toInt()) {
        val bar = size / 3f
        if (horizontal) {
            partBox(0f, at, 0f, size, 0.004f, bar, color)
            partBox(0f, at, 0f, bar, 0.004f, size, color)
        } else {
            partBox(0f, 0f, at, size, bar, 0.004f, color)
            partBox(0f, 0f, at, bar, size, 0.004f, color)
        }
    }

    /**
     * The arms stores: a wooden kiosk with a counter at the front (facing the street), rifles on
     * the back wall, a striped awning on two posts, a gold sign, and an ammo crate beside it.
     */
    private fun drawStores() {
        for (s in stores) {
            if (hypot(s.x - playerX, s.z - playerZ) > STORE_DRAW_DISTANCE * (if (scoped) SCOPE_RANGE_SCALE else 1f)) continue
            Matrix.setIdentityM(base, 0)
            Matrix.translateM(base, 0, s.x, city.groundAt(s.x, s.z), s.z)
            // Kiosk space: +z is the counter's front, towards the street.
            Matrix.rotateM(base, 0, deg(atan2(s.fx, s.fz)), 0f, 1f, 0f)
            partBox(0f, 1.2f, -0.35f, 2.2f, 2.4f, 1.3f, 0xFF5D4037.toInt())     // back cabin
            partBox(0f, 0.55f, 0.55f, 2.2f, 1.1f, 0.5f, 0xFF4B5320.toInt())     // counter
            partBox(0f, 1.13f, 0.6f, 2.32f, 0.06f, 0.62f, 0xFF8D6E63.toInt())   // counter top
            partBox(0f, 0.55f, 0.81f, 1.6f, 0.08f, 0.005f, 0xFFFFC107.toInt())  // gold stripe
            for (y in listOf(1.55f, 1.85f)) {
                partBox(0f, y, 0.31f, 1.1f, 0.07f, 0.08f, 0xFF1B1B1B.toInt())    // rifles on the wall
                partBox(0.38f, y - 0.06f, 0.31f, 0.28f, 0.13f, 0.09f, 0xFF6D4C2F.toInt())
            }
            for (side in listOf(-1.05f, 1.05f)) partBox(side, 1.45f, 0.82f, 0.08f, 1.9f, 0.08f, 0xFF2A2A2A.toInt()) // posts
            for (i in 0 until 6) {
                // The awning: red and white stripes from the cabin out over the counter.
                partBox(-0.96f + i * 0.385f, 2.45f, 0.25f, 0.385f, 0.06f, 1.4f, if (i % 2 == 0) 0xFFC62828.toInt() else 0xFFF2F2F2.toInt())
            }
            partBox(0f, 2.82f, 0.25f, 1.5f, 0.5f, 0.08f, 0xFF1B1B1B.toInt())    // sign board
            partBox(0f, 2.82f, 0.3f, 1.3f, 0.34f, 0.02f, 0xFFFFC107.toInt())    // gold face
            partBox(0f, 2.82f, 0.32f, 0.08f, 0.3f, 0.01f, 0xFF1B1B1B.toInt())   // "$": the bar
            partBox(0f, 2.82f, 0.32f, 0.22f, 0.05f, 0.01f, 0xFF1B1B1B.toInt())
            partBox(1.55f, 0.2f, 0.35f, 0.7f, 0.4f, 0.5f, 0xFF4B5320.toInt())   // ammo crate
            partBox(1.55f, 0.41f, 0.35f, 0.72f, 0.03f, 0.52f, 0xFF3A4118.toInt())
        }
    }

    /** "$ Arms store" over the stores, from further off than pickup labels. */
    private fun drawStoreLabels() {
        for (s in stores) {
            if (hypot(s.x - playerX, s.z - playerZ) > STORE_LABEL_DISTANCE) continue
            val text = storeLabel
            val tag = label("store:$text") { CityBitmaps.nameTag(text, 0xFFFFC107.toInt()) to NAME_UNITS_PER_PX }
            drawBillboard(tag, s.x, city.groundAt(s.x, s.z) + 3.25f, s.z)
        }
    }

    /** "AK-47 ×10" and the like over nearby pickups (drawn with the other see-through labels). */
    private fun drawPickupLabels() {
        for (p in pickups) {
            if (hypot(p.x - playerX, p.z - playerZ) > PICKUP_LABEL_DISTANCE) continue
            val text = pickupLabel(p.kind)
            val tag = label("p:$text") { CityBitmaps.nameTag(text, 0xFFFFC107.toInt()) to NAME_UNITS_PER_PX }
            drawBillboard(tag, p.x, city.groundAt(p.x, p.z) + 0.75f, p.z)
        }
    }

    private fun drawMuzzleFlashes(now: Long) {
        muzzleFlashes.removeAll { it[6] < (now - clockBase).toFloat() }
        for (f in muzzleFlashes) {
            Matrix.setIdentityM(part, 0)
            Matrix.translateM(part, 0, f[0], f[1], f[2])
            orientAlong(f[3], f[4], f[5])
            Matrix.scaleM(part, 0, 0.22f, 0.22f, 0.35f)
            draw(cube, part, 0xEEFFF59D.toInt(), lit = false)
        }
    }

    private fun uploadAndRecycle(bitmap: Bitmap): Int = Textures.upload(bitmap).also { bitmap.recycle() }

    // ---- Updates ------------------------------------------------------------------------------

    private fun applyPending(now: Long) {
        pendingDrops?.let { list ->
            pendingDrops = null
            syncDrops(list)
        }
        pendingPickups?.let { list ->
            pendingPickups = null
            pickups = list
        }
        pendingSpeech?.let { text ->
            pendingSpeech = null
            speech = text.ifBlank { null }
            speechUntil = if (speech != null) now + SPEECH_MILLIS else 0L
        }
        if (pendingFaceReload) {
            pendingFaceReload = false
            faces.values.forEach { Textures.delete(it) }
            faces.clear()
        }
        if (pendingTeamReload) {
            pendingTeamReload = false
            unknownTeams.clear()
        }
        pendingRespawn?.let { (x, z) ->
            pendingRespawn = null
            playerX = x
            playerZ = z
            // Back in the street, off any roof or ladder.
            roof = null
            climb = null
            climbing = false
            floorY = city.groundAt(x, z)
            playerY = floorY
            vy = 0f
            bullets.clear()
        }
        while (true) {
            val s = remoteShots.poll() ?: break
            bullets += Bullet(s[0], s[1], s[2], s[3], s[4], s[5], mine = false, range = s[6], speed = s[7])
            life.alarm(s[0], s[2])
            muzzleFlashes += floatArrayOf(s[0], s[1], s[2], s[3], s[4], s[5], (now - clockBase + MUZZLE_FLASH_MS).toFloat())
        }
        pendingRemote?.let { list ->
            pendingRemote = null
            val ids = list.map { it.uid }.toSet()
            remotes.keys.retainAll(ids)
            for (p in list) {
                val avatar = remotes.getOrPut(p.uid) { RemoteAvatar(p) }
                avatar.player = p
                // A new sayAt means they said something new: show it for the usual time,
                // measured on this phone so clocks don't need to agree.
                if (p.sayAt != avatar.sayAt) {
                    val firstSight = avatar.sayAt == -1L
                    avatar.sayAt = p.sayAt
                    avatar.saying = p.say.ifBlank { null }
                    avatar.sayUntil = if (avatar.saying != null && !firstSight) now + SPEECH_MILLIS else 0L
                }
            }
        }
    }

    /** Glides each other player towards where their phone last said they were. */
    private fun updateRemotes(dt: Float) {
        val blend = 1f - kotlin.math.exp(-8f * dt)
        for (r in remotes.values) {
            val p = r.player
            val jump = hypot(p.x - r.x, p.z - r.z)
            if (jump > 15f) {
                r.x = p.x; r.z = p.z; r.floor = p.floor
            } else {
                val nx = r.x + (p.x - r.x) * blend
                val nz = r.z + (p.z - r.z) * blend
                r.x = nx; r.z = nz
                r.floor += (p.floor - r.floor) * blend
            }
            r.heading = approachAngle(r.heading, p.heading, 10f * dt)

            // A new jumpSeq means they just jumped: play the same jump here.
            if (p.jumpSeq != r.jumpSeq) {
                if (r.jumpSeq != -1L && !p.prone) r.vy = JUMP_SPEED
                r.jumpSeq = p.jumpSeq
            }
            if (r.y > 0f || r.vy > 0f) {
                r.vy -= GRAVITY * dt
                r.y = (r.y + r.vy * dt).coerceAtLeast(0f)
                if (r.y <= 0f) r.vy = 0f
            }
        }
    }

    /**
     * Reloading, on the GL thread: starts one that was asked for, cancels it when the gun is
     * switched or the player goes down, and puts the new magazine in once its time is up. The
     * rounds left in the old magazine are kept (tipped into the spares).
     */
    private fun updateReload(gun: Weapon) {
        if (reloadRequested) {
            reloadRequested = false
            if (reloading == null && !unlimitedAmmo && loaded.get(gun.ordinal) < gun.magazine) startReload(gun)
        }
        val current = reloading ?: return
        if (current != gun || down) {
            reloading = null
            return
        }
        if (SystemClock.uptimeMillis() < reloadEndsAt) return
        val want = current.magazine
        val pool = loaded.get(current.ordinal) + spare.get(current.ordinal)
        val load = min(want, pool)
        loaded.set(current.ordinal, load)
        spare.set(current.ordinal, pool - load)
        reloading = null
        mainHandler.post { onReloaded(current) }
    }

    private fun startReload(gun: Weapon) {
        if (reloading != null || down || spare.get(gun.ordinal) <= 0) return
        val now = SystemClock.uptimeMillis()
        reloadStartedAt = now
        reloadEndsAt = now + (gun.reloadSeconds * 1000).toLong()
        reloading = gun
        mainHandler.post { onReloadStart(gun) }
    }

    /**
     * Fires one bullet from the gun's muzzle towards the point under the crosshair, a little off
     * by the gun's [Weapon.spread]; the gun kicks the aim up a touch (more for the pistol per shot,
     * but the AK-47 climbs as it keeps firing).
     */
    private fun fire(gun: Weapon) {
        // From the gun's muzzle when it has been drawn, else from where it would be.
        val x = if (muzzleKnown) muzzle[0] else muzzleX()
        val y = if (muzzleKnown) muzzle[1] else muzzleY()
        val z = if (muzzleKnown) muzzle[2] else muzzleZ()
        life.alarm(x, z)
        var dx = aimX - x; var dy = aimY - y; var dz = aimZ - z
        var len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1.5f) {
            // Aim point right in front of the gun (e.g. a wall): just fire where the camera looks.
            dx = camFx; dy = camFy; dz = camFz; len = 1f
        }
        dx /= len; dy /= len; dz /= len
        // Sniper rifles are only accurate through their scope; other guns steady a little with one.
        val spread = if (scoped) gun.scopedSpread else gun.hipSpread
        dx += (kotlin.random.Random.nextFloat() - 0.5f) * 2f * spread
        dy += (kotlin.random.Random.nextFloat() - 0.5f) * 2f * spread
        dz += (kotlin.random.Random.nextFloat() - 0.5f) * 2f * spread
        len = sqrt(dx * dx + dy * dy + dz * dz)
        dx /= len; dy /= len; dz /= len
        bullets += Bullet(x, y, z, dx, dy, dz, mine = true, range = gun.range, speed = gun.bulletSpeed, damage = gun.damage)
        if (eyeView) synchronized(lookLock) { pendingPitch -= gun.recoil }
        lastShotAt = SystemClock.uptimeMillis()
        lastShotWeapon = gun
        muzzleFlashes += floatArrayOf(x, y, z, dx, dy, dz, (SystemClock.uptimeMillis() - clockBase + MUZZLE_FLASH_MS).toFloat())
        heading = yaw
        aimTime = 0.6f
        idleTime = 0f
        mainHandler.post { onShot(x, y, z, dx, dy, dz) }
    }

    /**
     * Moves bullets in small steps so fast ones can't skip through a wall or a player. Buildings
     * stop every bullet; only my own bullets can hit other players (their phone applies the damage).
     */
    private fun updateBullets(dt: Float) {
        if (bullets.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val iterator = bullets.iterator()
        while (iterator.hasNext()) {
            val b = iterator.next()
            var remaining = b.speed * dt
            var spent = false
            while (remaining > 0f && !spent) {
                val s = min(BULLET_STEP, remaining)
                remaining -= s
                b.x += b.dx * s
                b.y += b.dy * s
                b.z += b.dz * s
                b.travelled += s
                if (b.travelled > b.range || city.underground(b.x, b.y, b.z) || city.isInsideBuilding(b.x, b.y, b.z, 0f)) {
                    spent = true
                } else if (life.shoot(b.x, b.y, b.z, b.dx, b.dz)) {
                    // A passer-by hit: they fall, but it counts for nobody's score (not reported).
                    spent = true
                } else if (b.mine) {
                    val target = remotes.values.firstOrNull { isEnemy(it) && hitsBody(it, b.x, b.y, b.z) }
                    if (target != null) {
                        target.flashUntil = now + 160
                        val uid = target.player.uid
                        val damage = b.damage
                        val headshot = hitsHead(target, b.y)
                        mainHandler.post { onHitPlayer(uid, damage, headshot) }
                        spent = true
                    }
                } else if (!down && hypot(playerX - b.x, playerZ - b.z) < HIT_RADIUS && b.y > playerY && b.y < playerY + BODY_HEIGHT) {
                    // Someone else's bullet reached me: stop drawing it here.
                    spent = true
                }
            }
            if (spent) iterator.remove()
        }
    }

    private fun drawBullets() {
        for (b in bullets) {
            Matrix.setIdentityM(part, 0)
            Matrix.translateM(part, 0, b.x, b.y, b.z)
            orientAlong(b.dx, b.dy, b.dz)
            Matrix.scaleM(part, 0, 0.07f, 0.07f, 0.55f)
            draw(cube, part, if (b.mine) 0xFFFFD54F.toInt() else 0xFFFF7043.toInt(), lit = false)
        }
    }

    // ---- Ladders and roofs --------------------------------------------------------------------

    /** Where the player can't walk: walls and the like in the street; on a roof, past its edge or into anything built on it. */
    private fun blocked(x: Float, z: Float): Boolean {
        // A car blocks the way, unless already standing in one (then the player can step out).
        // A store kiosk is solid.
        if (stores.any { abs(it.x - x) < Store.HALF + BODY_RADIUS && abs(it.z - z) < Store.HALF + BODY_RADIUS }) return true
        val r = roof ?: return city.isBlocked(x, z, BODY_RADIUS) ||
            (life.blocks(x, z, BODY_RADIUS) && !life.blocks(playerX, playerZ, BODY_RADIUS))
        if (!CityMap.inside(r.pts, x, z) || CityMap.edgeDistance(r.pts, x, z) < ROOF_EDGE) return true
        return city.isInsideBuilding(x, floorY + 1f, z, BODY_RADIUS) || !city.inPlayArea(x, z)
    }

    /**
     * Starts a climb when asked (from a ladder's foot, or from its top on a roof), moves the player
     * along one under way, and works out what the Climb button would do next.
     */
    private fun updateClimb(isDown: Boolean) {
        val now = SystemClock.uptimeMillis()
        val requested = climbRequested
        climbRequested = false
        val c = climb
        if (c == null) {
            val action = when {
                isDown -> LadderAction.NONE
                roof == null && nearestLadder(onRoof = false) != null -> LadderAction.UP
                roof != null && nearestLadder(onRoof = true) != null -> LadderAction.DOWN
                else -> LadderAction.NONE
            }
            ladderAction = action
            if (!requested || action == LadderAction.NONE || prone || playerY > floorY + 0.05f) return
            val ladder = nearestLadder(onRoof = action == LadderAction.DOWN) ?: return
            climb = Climb(ladder, action == LadderAction.UP, playerX, playerZ, now)
            climbing = true
            ladderAction = LadderAction.NONE
            return
        }
        val l = c.ladder
        val t = (now - c.start) / 1000f
        // Killed on the ladder: down at whichever end is nearer.
        val done = t >= c.total
        if (isDown || done) {
            val atTop = if (done) c.up else playerY > (l.footY + l.topY) / 2f
            playerX = if (atTop) l.topX else l.footX
            playerZ = if (atTop) l.topZ else l.footZ
            floorY = if (atTop) l.topY else l.footY
            playerY = floorY
            roof = if (atTop) l.building else null
            climb = null
            climbing = false
            return
        }
        heading = l.facingWall
        // Onto the ladder, up or down its rungs, then off it at the other end.
        val (startY, endY) = if (c.up) l.footY to l.topY else l.topY to l.footY
        when {
            t < c.onto -> {
                val k = smooth(t / c.onto)
                playerX = c.fromX + (l.onX - c.fromX) * k
                playerZ = c.fromZ + (l.onZ - c.fromZ) * k
                playerY = startY
            }
            t < c.onto + c.rungs -> {
                playerX = l.onX; playerZ = l.onZ
                playerY = startY + (endY - startY) * ((t - c.onto) / c.rungs)
            }
            else -> {
                val k = smooth((t - c.onto - c.rungs) / c.off)
                val (ex, ez) = if (c.up) l.topX to l.topZ else l.footX to l.footZ
                playerX = l.onX + (ex - l.onX) * k
                playerZ = l.onZ + (ez - l.onZ) * k
                // Over the top: a little lift to clear the edge of the roof.
                playerY = endY + if (c.up) sin(k * PI.toFloat()) * 0.25f else 0f
            }
        }
        floorY = playerY
    }

    /** The ladder whose foot (in the street) or top (on my roof) is within reach, if any. */
    private fun nearestLadder(onRoof: Boolean): Ladder? {
        val r = roof
        return ladders
            .filter { !onRoof || it.building === r }
            .map { l -> l to if (onRoof) hypot(l.topX - playerX, l.topZ - playerZ) else hypot(l.footX - playerX, l.footZ - playerZ) }
            .filter { it.second < LADDER_REACH }
            .minByOrNull { it.second }?.first
    }

    private fun smooth(k: Float): Float {
        val c = k.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** All the ladders as one mesh: two rails up the wall, rungs every [RUNG_GAP], and handles over the roof's edge. */
    private fun buildLadders(): Mesh? {
        if (ladders.isEmpty()) return null
        val m = MeshBuilder()
        for (l in ladders) {
            // Along the wall, a hand's width out from it.
            val ax = -l.nz; val az = l.nx
            val cx = l.wallX + l.nx * 0.12f
            val cz = l.wallZ + l.nz * 0.12f
            val top = l.height + 1f
            val foot = l.footY
            for (side in listOf(-1f, 1f)) {
                m.turnedBox(cx + ax * side * 0.26f, foot + top / 2f, cz + az * side * 0.26f, ax, az, 0.03f, top / 2f, 0.03f)
            }
            var y = RUNG_GAP
            while (y < top - 0.05f) {
                m.turnedBox(cx, foot + y, cz, ax, az, 0.26f, 0.018f, 0.02f)
                y += RUNG_GAP
            }
        }
        return m.build()
    }

    // ---- Grenades: throwing, flight and what they do ------------------------------------------

    /**
     * Throws one if asked (not while down, nor straight after the last throw), takes in other
     * players' throws, flies every grenade in fixed steps, and runs the smoke and fire left behind.
     */
    private fun updateGrenades(dt: Float, isDown: Boolean) {
        throwCooldown = (throwCooldown - dt).coerceAtLeast(0f)
        if (throwRequested) {
            throwRequested = false
            val kind = grenadeKind
            when {
                isDown || climbing || throwCooldown > 0f || throwStartedAt != 0L -> Unit
                unlimitedAmmo || grenadesLeft.get(kind.ordinal) > 0 -> {
                    if (!unlimitedAmmo) grenadesLeft.decrementAndGet(kind.ordinal)
                    throwCooldown = THROW_INTERVAL
                    throwing = kind
                    thrown = false
                    throwStartedAt = SystemClock.uptimeMillis()
                    // Turn to where the throw goes, in the aiming pose, for the whole wind-up.
                    heading = yaw
                    aimTime = THROW_RELEASE_MS / 1000f + 0.6f
                    mainHandler.post { onHoldGrenade(kind) }
                }
                else -> mainHandler.post { onNoGrenade(kind) }
            }
        }
        if (throwStartedAt != 0L) {
            val since = SystemClock.uptimeMillis() - throwStartedAt
            when {
                // Killed while winding up: the grenade is dropped unthrown, and kept.
                isDown && !thrown -> {
                    if (!unlimitedAmmo) grenadesLeft.incrementAndGet(throwing.ordinal)
                    throwStartedAt = 0L
                    mainHandler.post { onHoldGrenade(null) }
                }
                !thrown && since >= THROW_RELEASE_MS -> {
                    thrown = true
                    throwOne(throwing)
                }
                since >= THROW_ANIM_MS -> throwStartedAt = 0L
            }
        }
        while (true) {
            val (kind, s) = remoteGrenades.poll() ?: break
            grenades += Grenade(kind, s[0], s[1], s[2], s[3], s[4], s[5], mine = false)
        }
        // Every phone moves a grenade in the same steps from the same throw, so it bounces and
        // lands in the same place everywhere, whatever each phone's frame rate.
        grenadeClock += dt
        while (grenadeClock >= GRENADE_STEP) {
            grenadeClock -= GRENADE_STEP
            stepGrenades()
        }
        updateBlasts(SystemClock.uptimeMillis())
        shake = (shake - dt * SHAKE_FADE).coerceAtLeast(0f)
    }

    /**
     * Out of the hand at head height, a little ahead, towards where the camera looks and lobbed
     * up a little; a weaker throw lying down.
     */
    private fun throwOne(kind: GrenadeKind) {
        val crawling = prone && !down
        val speed = if (crawling) THROW_SPEED_PRONE else THROW_SPEED
        var x = playerX + sin(yaw) * 0.45f + cos(yaw) * 0.2f
        var z = playerZ - cos(yaw) * 0.45f + sin(yaw) * 0.2f
        val y = playerY + if (crawling) 0.5f else 1.75f
        if (grenadeBlocked(x, y, z)) { x = playerX; z = playerZ }
        val vx = camFx * speed
        val vy = camFy * speed + THROW_LIFT
        val vz = camFz * speed
        grenades += Grenade(kind, x, y, z, vx, vy, vz, mine = true)
        heading = yaw
        aimTime = 0.6f
        idleTime = 0f
        mainHandler.post { onGrenadeThrown(kind, floatArrayOf(x, y, z, vx, vy, vz)) }
    }

    private fun stepGrenades() {
        val iterator = grenades.iterator()
        while (iterator.hasNext()) {
            val g = iterator.next()
            g.steps++
            val contact = moveGrenade(g)
            if (g.steps * GRENADE_STEP >= g.kind.fuseSeconds || (g.kind.impact && contact != Contact.NONE)) {
                iterator.remove()
                burst(g, contact)
            }
        }
    }

    /** One step of flight: gravity, then each axis on its own so it bounces off walls, roofs and the ground. */
    private fun moveGrenade(g: Grenade): Contact {
        val h = GRENADE_STEP
        var contact = Contact.NONE
        g.vy -= GRAVITY * h
        val nx = g.x + g.vx * h
        if (grenadeBlocked(nx, g.y, g.z)) { g.vx = -g.vx * BOUNCE; contact = Contact.WALL } else g.x = nx
        val nz = g.z + g.vz * h
        if (grenadeBlocked(g.x, g.y, nz)) { g.vz = -g.vz * BOUNCE; contact = Contact.WALL } else g.z = nz
        val ny = g.y + g.vy * h
        val ground = city.groundAt(g.x, g.z)
        val floor = ny <= ground + GRENADE_RADIUS
        if (floor || grenadeBlocked(g.x, ny, g.z)) {
            if (floor) {
                g.y = ground + GRENADE_RADIUS
                // On a hillside it rolls on downhill.
                val sx = city.groundAt(g.x + 0.5f, g.z) - city.groundAt(g.x - 0.5f, g.z)
                val sz = city.groundAt(g.x, g.z + 0.5f) - city.groundAt(g.x, g.z - 0.5f)
                g.vx -= sx * GRAVITY * h * 0.5f
                g.vz -= sz * GRAVITY * h * 0.5f
            }
            val falling = g.vy < 0f
            if (falling) contact = if (floor) Contact.GROUND else Contact.ROOF
            else if (contact == Contact.NONE) contact = Contact.WALL
            // A real bounce loses most of its speed; rolling slows it bit by bit.
            if (abs(g.vy) > 1f) { g.vx *= BOUNCE_FRICTION; g.vz *= BOUNCE_FRICTION }
            else { g.vx *= ROLL_FRICTION; g.vz *= ROLL_FRICTION }
            g.vy = -g.vy * BOUNCE
        } else g.y = ny
        return contact
    }

    /** A grenade can't go into a building or past the edge of the play area. */
    private fun grenadeBlocked(x: Float, y: Float, z: Float): Boolean =
        x < city.playMinX || x > city.playMaxX || z < city.playMinZ || z > city.playMaxZ ||
            city.isInsideBuilding(x, y, z, GRENADE_RADIUS)

    /** [g] goes off: a blast, a flash, a cloud of smoke or a pool of fire. */
    private fun burst(g: Grenade, contact: Contact) {
        val now = SystemClock.uptimeMillis()
        val kind = g.kind
        // Smoke and fire settle on the ground, or on the roof the grenade landed on.
        val ground = city.groundAt(g.x, g.z)
        val onRoof = contact == Contact.ROOF || (g.y > ground + 0.5f && city.isInsideBuilding(g.x, g.y - 0.3f, g.z, 0f))
        val y = when (kind) {
            GrenadeKind.SMOKE, GrenadeKind.MOLOTOV -> if (onRoof) g.y - GRENADE_RADIUS else ground
            else -> g.y
        }
        val lasts = when (kind) {
            GrenadeKind.FRAG -> FRAG_SHOW_MS
            GrenadeKind.FLASH -> FLASH_SHOW_MS
            else -> (kind.seconds * 1000).toLong()
        }
        blasts += Blast(kind, g.x, y, g.z, g.mine, now, now + lasts, puffsFor(kind))
        life.alarm(g.x, g.z)
        when (kind) {
            GrenadeKind.FRAG -> fragBlast(g, now)
            GrenadeKind.FLASH -> flashBlind(g)
            else -> Unit
        }
        mainHandler.post { onGrenadeBurst(kind, g.x, g.y, g.z, g.mine) }
    }

    /** Where a blast's puffs go: x, y, z (around its centre) and a size, each. */
    private fun puffsFor(kind: GrenadeKind): FloatArray {
        val rnd = kotlin.random.Random
        val count = when (kind) {
            GrenadeKind.FRAG -> 14
            GrenadeKind.FLASH -> 3
            GrenadeKind.SMOKE -> 30
            GrenadeKind.MOLOTOV -> 22
        }
        return FloatArray(count * 4).also { p ->
            for (i in 0 until count) {
                val a = rnd.nextFloat() * 2f * PI.toFloat()
                val r = sqrt(rnd.nextFloat()) * when (kind) {
                    GrenadeKind.SMOKE -> kind.radius * 0.75f
                    GrenadeKind.MOLOTOV -> kind.radius * 0.85f
                    else -> 0.8f
                }
                p[i * 4] = cos(a) * r
                p[i * 4 + 1] = when (kind) {
                    GrenadeKind.SMOKE -> rnd.nextFloat() * 2.6f + 0.4f
                    GrenadeKind.MOLOTOV -> 0.2f
                    else -> rnd.nextFloat() * 0.8f
                }
                p[i * 4 + 2] = sin(a) * r
                p[i * 4 + 3] = 0.7f + rnd.nextFloat() * 0.6f
            }
        }
    }

    /**
     * A frag's blast: the closer, the more hearts it takes, but a wall in between stops it. My
     * phone decides for my grenades (the hurt players' phones are told, as with my bullets), and
     * my own grenade can hurt me too. Anyone near feels the ground shake.
     */
    private fun fragBlast(g: Grenade, now: Long) {
        // Passers-by caught in it fall (any grenade; no score).
        life.blast(g.x, g.z, g.kind.radius)
        val r = g.kind.radius
        if (g.mine) {
            for (t in remotes.values) {
                if (!isEnemy(t)) continue
                val ty = t.feet + if (t.player.prone) 0.3f else 1f
                val d = dist(g.x, g.y, g.z, t.x, ty, t.z)
                if (d > r || !clearPath(g.x, g.y, g.z, t.x, ty, t.z)) continue
                t.flashUntil = now + 160
                val uid = t.player.uid
                val damage = blastDamage(d, r)
                mainHandler.post { onHitPlayer(uid, damage, false) }
            }
            val my = playerY + if (prone) 0.3f else 1f
            val d = dist(g.x, g.y, g.z, playerX, my, playerZ)
            if (!down && d <= r && clearPath(g.x, g.y, g.z, playerX, my, playerZ)) {
                val damage = blastDamage(d, r)
                mainHandler.post { onSelfHit(damage) }
            }
        }
        val d = dist(g.x, g.y, g.z, playerX, playerY + 1f, playerZ)
        if (d < SHAKE_DISTANCE) shake = maxOf(shake, 1f - d / SHAKE_DISTANCE)
    }

    /** Hearts a frag takes at [d] metres from it: all of them right on it, one at the edge of [r]. */
    private fun blastDamage(d: Float, r: Float) =
        kotlin.math.ceil(MAX_HEALTH * (1f - d / r)).toInt().coerceIn(1, MAX_HEALTH)

    /**
     * A flashbang, whoever threw it, judged on this phone: it blinds me if I can see it from
     * where my head is, worst up close and looking right at it, much less looking away.
     */
    private fun flashBlind(g: Grenade) {
        if (down) return
        val hy = playerY + if (prone) PRONE_EYE_HEIGHT else EYE_HEIGHT
        val d = dist(g.x, g.y, g.z, playerX, hy, playerZ)
        val r = g.kind.radius
        if (d > r || !clearPath(g.x, g.y, g.z, playerX, hy, playerZ)) return
        val facing = if (d < 0.01f) 1f else (camFx * (g.x - playerX) + camFy * (g.y - hy) + camFz * (g.z - playerZ)) / d
        val look = if (facing > 0f) 0.45f + 0.55f * facing else 0.15f + 0.3f * (1f + facing)
        val strength = (((1f - d / r) * 1.4f).coerceAtMost(1f) * look).coerceIn(0f, 1f)
        if (strength > 0.05f) mainHandler.post { onFlashed(strength) }
    }

    /** Fires burn: once a second, a heart from each enemy standing in mine (and me, in mine). */
    private fun updateBlasts(now: Long) {
        blasts.removeAll { now >= it.until }
        for (b in blasts) {
            if (b.kind != GrenadeKind.MOLOTOV || now < b.nextBurn) continue
            b.nextBurn = now + BURN_INTERVAL_MS
            if (!b.mine) continue
            val r = b.kind.radius
            for (t in remotes.values) {
                if (!isEnemy(t) || hypot(t.x - b.x, t.z - b.z) > r || abs(t.feet - b.y) > 1.5f) continue
                t.flashUntil = now + 160
                val uid = t.player.uid
                mainHandler.post { onHitPlayer(uid, 1, false) }
            }
            if (!down && hypot(playerX - b.x, playerZ - b.z) <= r && abs(playerY - b.y) < 1.5f) {
                mainHandler.post { onSelfHit(1) }
            }
        }
    }

    /** Whether the line from a to b passes through a smoke cloud thick enough to hide what's beyond. */
    private fun smokeBetween(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, now: Long): Boolean {
        for (b in blasts) {
            if (b.kind != GrenadeKind.SMOKE) continue
            val thick = smokeThickness(b, now)
            if (thick < 0.5f) continue
            val r = b.kind.radius * 0.85f * thick
            val cy = b.y + 1.5f
            // Distance from the cloud's middle to the segment.
            val sx = bx - ax; val sy = by - ay; val sz = bz - az
            val len2 = sx * sx + sy * sy + sz * sz
            val k = if (len2 < 1e-6f) 0f else (((b.x - ax) * sx + (cy - ay) * sy + (b.z - az) * sz) / len2).coerceIn(0f, 1f)
            if (dist(ax + sx * k, ay + sy * k, az + sz * k, b.x, cy, b.z) < r) return true
        }
        return false
    }

    /** A smoke cloud spreads over its first seconds and thins out over its last: 0..1. */
    private fun smokeThickness(b: Blast, now: Long): Float {
        val grow = ((now - b.start) / SMOKE_GROW_MS.toFloat()).coerceIn(0f, 1f)
        val fade = ((b.until - now) / SMOKE_FADE_MS.toFloat()).coerceIn(0f, 1f)
        return min(grow, fade)
    }

    /** Whether no building stands between a and b (a blast or a flash reaches). */
    private fun clearPath(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Boolean {
        val d = dist(ax, ay, az, bx, by, bz)
        val steps = (d / BULLET_STEP).toInt()
        for (i in 1 until steps) {
            val k = i / steps.toFloat()
            val x = ax + (bx - ax) * k; val y = ay + (by - ay) * k; val z = az + (bz - az) * k
            // A wall or a hill in the way.
            if (city.isInsideBuilding(x, y, z, 0f) || city.underground(x, y, z)) return false
        }
        return true
    }

    private fun dist(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val dx = bx - ax; val dy = by - ay; val dz = bz - az
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Grenades in flight, tumbling: each kind its own shape and colour. */
    private fun drawGrenades() {
        for (g in grenades) {
            Matrix.setIdentityM(base, 0)
            Matrix.translateM(base, 0, g.x, g.y, g.z)
            // Tumbles while flying, lies still once it has stopped.
            val moving = abs(g.vx) + abs(g.vz) > 0.3f || g.y > GRENADE_RADIUS + 0.02f
            if (moving) Matrix.rotateM(base, 0, g.steps * 9f, 1f, 0.3f, 0f)
            else Matrix.rotateM(base, 0, 90f, 1f, 0f, 0f)
            grenadeShape(g.kind, lit = true)
        }
    }

    /**
     * A grenade of [kind] in [base] space, standing up along +y: each kind its own shape and
     * colour. A molotov's rag burns only once [lit] (thrown), not lying in a crate.
     */
    private fun grenadeShape(kind: GrenadeKind, lit: Boolean = false) {
        when (kind) {
            GrenadeKind.FRAG -> {
                partBox(0f, 0f, 0f, 0.085f, 0.1f, 0.085f, kind.color)
                partBox(0f, 0.06f, 0f, 0.035f, 0.03f, 0.035f, 0xFF6D6D6D.toInt())        // fuse
                partBox(0.03f, 0.03f, 0f, 0.015f, 0.08f, 0.02f, 0xFF8D8D8D.toInt())      // lever
            }
            GrenadeKind.FLASH, GrenadeKind.SMOKE -> {
                partBox(0f, 0f, 0f, 0.065f, 0.14f, 0.065f, kind.color)
                partBox(0f, 0.035f, 0f, 0.068f, 0.02f, 0.068f, if (kind == GrenadeKind.FLASH) 0xFF37474F.toInt() else 0xFFEEEEEE.toInt())
                partBox(0f, 0.08f, 0f, 0.035f, 0.025f, 0.035f, 0xFF6D6D6D.toInt())
            }
            GrenadeKind.MOLOTOV -> {
                partBox(0f, -0.02f, 0f, 0.075f, 0.15f, 0.075f, 0xFF4E7A3A.toInt())       // bottle
                partBox(0f, 0.08f, 0f, 0.03f, 0.07f, 0.03f, 0xFF4E7A3A.toInt())          // neck
                partBox(0f, 0.13f, 0f, 0.04f, 0.04f, 0.04f, 0xFFF5DEB3.toInt())          // rag
                if (lit) {
                    Matrix.translateM(part, 0, base, 0, 0f, 0.17f, 0f)
                    val flicker = 0.06f + kotlin.random.Random.nextFloat() * 0.03f
                    Matrix.scaleM(part, 0, flicker, flicker * 1.4f, flicker)
                    draw(cube, part, 0xEEFFA726.toInt(), lit = false)
                }
            }
        }
    }

    /**
     * What grenades leave behind, as soft see-through puffs: a frag's fireball and dark smoke, a
     * flashbang's white flash, a smoke grenade's grey cloud, a molotov's flickering flames.
     */
    private fun drawBlasts(now: Long) {
        if (blasts.isEmpty() || puffTexture == 0) return
        Matrix.invertM(cameraToWorld, 0, view, 0)
        GLES20.glDepthMask(false)
        for (b in blasts) {
            val age = (now - b.start).toFloat()
            val p = b.puffs
            val n = p.size / 4
            when (b.kind) {
                GrenadeKind.FRAG -> {
                    val fire = (age / 450f).coerceIn(0f, 1f)
                    for (i in 0 until n) {
                        val s = p[i * 4 + 3]
                        val px = b.x + p[i * 4] * (0.5f + fire * 2f)
                        val pz = b.z + p[i * 4 + 2] * (0.5f + fire * 2f)
                        if (i % 2 == 0 && fire < 1f) {
                            // Fireball: bright yellow to orange, swelling then gone.
                            val color = if (fire < 0.4f) 0xFFFFF176.toInt() else 0xFFFF8A00.toInt()
                            puff(px, b.y + p[i * 4 + 1] * fire, pz, s * (0.8f + fire * 3f), color, 1f - fire)
                        } else {
                            // Smoke rising after it, darkening the air for a moment.
                            val k = ((age - 150f) / (FRAG_SHOW_MS - 150f)).coerceIn(0f, 1f)
                            if (age < 150f) continue
                            puff(px, b.y + p[i * 4 + 1] + k * 2.5f, pz, s * (1.6f + k * 2.5f), 0xFF424242.toInt(), 0.75f * (1f - k))
                        }
                    }
                }
                GrenadeKind.FLASH -> {
                    val k = (age / FLASH_SHOW_MS).coerceIn(0f, 1f)
                    for (i in 0 until n) {
                        puff(b.x + p[i * 4] * 0.3f, b.y, b.z + p[i * 4 + 2] * 0.3f, (3f + k * 4f) * p[i * 4 + 3], WHITE, 1f - k)
                    }
                }
                GrenadeKind.SMOKE -> {
                    val thick = smokeThickness(b, now)
                    val t = age / 1000f
                    for (i in 0 until n) {
                        val s = p[i * 4 + 3]
                        // Each puff drifts a little, so the cloud seems to roll.
                        val wobble = sin(t * 0.4f + i) * 0.3f
                        val grey = if (i % 3 == 0) 0xFFB0B0B0.toInt() else 0xFF9E9E9E.toInt()
                        puff(b.x + p[i * 4] * thick + wobble, b.y + p[i * 4 + 1] * thick, b.z + p[i * 4 + 2] * thick - wobble,
                            s * (1.2f + 2.6f * thick), grey, 0.9f * thick)
                    }
                }
                GrenadeKind.MOLOTOV -> {
                    val fade = ((b.until - now) / 1000f).coerceIn(0f, 1f)
                    val spread = (age / 400f).coerceIn(0.3f, 1f)
                    val t = age / 1000f
                    for (i in 0 until n) {
                        val s = p[i * 4 + 3]
                        val flicker = 0.75f + 0.25f * sin(t * 13f + i * 2.1f)
                        val px = b.x + p[i * 4] * spread
                        val pz = b.z + p[i * 4 + 2] * spread
                        val color = if (i % 3 == 0) 0xFFFFD54F.toInt() else 0xFFFF6D00.toInt()
                        puff(px, b.y + 0.35f * flicker, pz, s * 1.1f * flicker, color, 0.9f * fade)
                        // A little dark smoke over some of the flames.
                        if (i % 4 == 0) {
                            val rise = (t * 0.8f + i) % 2.5f
                            puff(px, b.y + 1f + rise, pz, s * (1f + rise * 0.6f), 0xFF3E3E3E.toInt(), 0.35f * fade * (1f - rise / 2.5f))
                        }
                    }
                }
            }
        }
        GLES20.glDepthMask(true)
    }

    /** One soft puff [size] across at (x, y, z), turned to face the camera, [alpha] see-through. */
    private fun puff(x: Float, y: Float, z: Float, size: Float, color: Int, alpha: Float) {
        if (alpha <= 0.02f) return
        System.arraycopy(cameraToWorld, 0, part, 0, 16)
        part[12] = x; part[13] = y; part[14] = z
        Matrix.scaleM(part, 0, size, size, size)
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        draw(quad, part, (a shl 24) or (color and 0xFFFFFF), puffTexture, lit = false)
    }

    /** My soldier's dance (a clip name, see Dance.clip; null = none), e.g. after winning; loops. */
    @Volatile var playerDance: String? = null

    /** Keeps [soldier] doing [clip] over and over (null or "": not dancing). */
    private fun keepDancing(soldier: SoldierAnimator, clip: String?) {
        val want = clip?.takeIf { it.isNotEmpty() }
        if (soldier.dancing != want) soldier.dance(want, loop = true)
    }

    /** [id]'s rig once it has loaded (starting the load the first time it's asked for), else null. */
    private fun rigOf(id: String): SoldierRig? {
        rigs[id]?.let { return it }
        val source = rigSources.getOrPut(id) { rigFor(id) }
        if (!source.isDone) return null
        return runCatching { source.get() }.getOrNull().also { rigs[id] = it }
    }

    /** [current] if it already shows [rig]; otherwise a new animator for it (the old one's GPU copy is dropped). */
    private fun soldierFor(current: SoldierAnimator?, rig: SoldierRig): SoldierAnimator {
        if (current != null && current.rig === rig) return current
        if (current != null) {
            soldierMeshes.remove(current)?.forEach { it.release() }
            uploadedVersion.remove(current)
        }
        return SoldierAnimator(rig)
    }

    /**
     * Picks and advances each soldier's animation from how they're moving. Soldiers far away or
     * well behind the camera keep their clock running but aren't re-posed (saves battery).
     */
    private fun updateSoldiers(dt: Float) {
        val rig = rigOf(Characters.SOLDIER) ?: return
        if (dt <= 0f) return
        val player = soldierFor(playerSoldier, rigOf(playerCharacter) ?: rig).also { playerSoldier = it }
        keepDancing(player, playerDance)
        val statue = statueSoldier ?: SoldierAnimator(rig).also { statueSoldier = it }

        // My speed and direction come from how far I actually moved (walls stop you).
        val vx = (playerX - lastPlayerX) / dt
        val vz = (playerZ - lastPlayerZ) / dt
        lastPlayerX = playerX
        lastPlayerZ = playerZ
        playerSpeed += (hypot(vx, vz) - playerSpeed) * min(1f, dt * 10f)
        // On a ladder the hands and feet move with the height climbed (see ClimbPose).
        val climbSpeed = abs(playerY - lastPlayerY) / dt
        lastPlayerY = playerY
        player.update(
            dt, if (climbing) climbSpeed else playerSpeed, SoldierAnimator.relativeAngle(vx, vz, heading),
            aimTime > 0f, down, skin = true, prone = prone, airborne = playerY - floorY > 0.05f, climbing = climbing,
        )

        val now = SystemClock.uptimeMillis()
        for (r in remotes.values) {
            val want = rigOf(r.player.character.ifEmpty { Characters.SOLDIER }) ?: rig
            val soldier = soldierFor(r.soldier, want).also { r.soldier = it }
            keepDancing(soldier, r.player.dance)
            val rvx = (r.x - r.lastX) / dt
            val rvz = (r.z - r.lastZ) / dt
            r.lastX = r.x
            r.lastZ = r.z
            r.speed += (hypot(rvx, rvz) - r.speed) * min(1f, dt * 8f)
            val climbSpeed = abs(r.floor - r.lastFloor) / dt
            r.lastFloor = r.floor
            soldier.update(
                dt, if (r.player.climbing) climbSpeed else r.speed, SoldierAnimator.relativeAngle(rvx, rvz, r.heading),
                aiming = now < (remoteAiming[r.player.uid] ?: 0L) || r.player.grenadeHold.isNotEmpty(), dead = r.player.dead, skin = isWorthAnimating(r.x, r.z),
                prone = r.player.prone, airborne = r.y > 0.05f, climbing = r.player.climbing,
            )
        }
        // All photo-drop statues share one idle pose.
        statue.update(dt, 0f, 0f, aiming = false, dead = false, skin = dropVisuals.values.any { isWorthAnimating(it.drop.x, it.drop.z) })
    }

    /** Close enough, and not far behind the camera, to be worth animating this frame. */
    private fun isWorthAnimating(x: Float, z: Float): Boolean {
        val dx = x - playerX
        val dz = z - playerZ
        val d = hypot(dx, dz)
        if (d > SOLDIER_DRAW_DISTANCE * (if (scoped) SCOPE_RANGE_SCALE else 1f)) return false
        return d < 8f || dx * sin(yaw) - dz * cos(yaw) > -4f
    }

    private fun update(dt: Float) {
        // Standing at a store's counter (in the street, alive): the city screen offers the shop.
        nearStore = if (down || climbing || roof != null) null
            else stores.firstOrNull { hypot(it.frontX - playerX, it.frontZ - playerZ) < Store.REACH }
        synchronized(lookLock) {
            yaw += pendingYaw
            if (eyeView) lookPitch = (lookPitch + pendingPitch).coerceIn(-1.1f, 1.1f)
            // Negative pitch drops the camera low behind you so you can aim up at rooftops.
            else pitch = (pitch + pendingPitch).coerceIn(-0.5f, 1.2f)
            pendingYaw = 0f
            pendingPitch = 0f
        }

        val isDown = down
        updateClimb(isDown)
        // On a ladder the climb moves the player; the joystick does nothing.
        val jx = if (isDown || climbing) 0f else joyX
        val jy = if (isDown || climbing) 0f else joyY
        val amount = min(1f, hypot(jx, jy))
        walking = amount > 0.12f
        isWalking = walking
        updateRemotes(dt)
        updateBullets(dt)
        updateGrenades(dt, isDown)

        fireCooldown = (fireCooldown - dt).coerceAtLeast(0f)
        aimTime -= dt
        // Automatic guns fire for as long as Shoot is held; the others once per press, and a
        // press while the gun is still recovering from the last shot (or working the bolt) is lost.
        val gun = weapon
        val pulled = triggerPulled
        triggerPulled = false
        updateReload(gun)
        val wantsToFire = if (gun.automatic || rapidFire) triggerHeld || pulled else pulled
        // The gun is lowered while a grenade is thrown.
        if (wantsToFire && !isDown && fireCooldown <= 0f && reloading == null && throwStartedAt == 0L && !climbing) {
            when {
                unlimitedAmmo || loaded.get(gun.ordinal) > 0 -> {
                    if (!unlimitedAmmo) loaded.decrementAndGet(gun.ordinal)
                    fireCooldown = gun.fireInterval * if (rapidFire) RAPID_FIRE_SCALE else 1f
                    fire(gun)
                    // The last round: change the magazine straight away, as a soldier would.
                    if (!unlimitedAmmo && loaded.get(gun.ordinal) == 0) startReload(gun)
                }
                spare.get(gun.ordinal) > 0 -> startReload(gun)
                else -> {
                    // Click: no bullets. Don't repeat the warning every frame while held.
                    fireCooldown = EMPTY_CLICK_INTERVAL
                    mainHandler.post { onOutOfAmmo(gun) }
                }
            }
        }

        // Jumping: a push upwards, then gravity until the feet are back on the ground.
        val crawling = prone && !isDown
        if (jumpRequested) {
            jumpRequested = false
            if (playerY <= floorY && !crawling && !isDown && !climbing) {
                vy = JUMP_SPEED
                jumpSeq++
            }
        }
        // Back down onto whatever the player stands on: the street or a roof.
        if (!climbing && (playerY > floorY || vy > 0f)) {
            vy -= GRAVITY * dt
            playerY = (playerY + vy * dt).coerceAtLeast(floorY)
            if (playerY <= floorY) vy = 0f
        }

        if (walking) {
            idleTime = 0f
            // Joystick up walks away from the camera; sideways strafes.
            val fx = sin(yaw); val fz = -cos(yaw)
            val rx = cos(yaw); val rz = sin(yaw)
            var dx = fx * -jy + rx * jx
            var dz = fz * -jy + rz * jx
            val len = sqrt(dx * dx + dz * dz)
            dx /= len; dz /= len
            heading = approachAngle(heading, atan2(dx, -dz), 12f * dt)

            // A gentle push walks, pushing the stick most of the way runs; crawling is always slow.
            val speed = when {
                crawling -> CRAWL_SPEED * amount.coerceAtLeast(0.5f)
                amount < RUN_STICK -> WALK_SPEED * (amount / RUN_STICK).coerceAtLeast(0.45f)
                else -> RUN_SPEED
            }
            val step = speed * speedBoost * weapon.moveSpeed * dt
            // Slide along walls: try each axis on its own.
            if (!blocked(playerX + dx * step, playerZ)) playerX += dx * step
            if (!blocked(playerX, playerZ + dz * step)) playerZ += dz * step
        } else {
            idleTime += dt
            // Standing still for a moment: turn round to face the camera so the face shows.
            if (idleTime > 1.5f && aimTime <= 0f && !isDown && !eyeView && !climbing) {
                heading = approachAngle(heading, yaw + PI.toFloat(), 2.5f * dt)
            }
        }
        // In the street the floor is the ground under the feet: on a hillside it rises and falls
        // with each step, and feet on the ground stay on it (only a jump leaves it).
        if (roof == null && !climbing) {
            val ground = city.groundAt(playerX, playerZ)
            val onGround = playerY <= floorY + 0.05f && vy <= 0f
            floorY = ground
            if (onGround || playerY < ground) playerY = ground
        }
        // Shooting turns the character to aim along the camera, even while strafing; in first
        // person the soldier always faces where you look (so others see where you aim).
        if (!climbing && (aimTime > 0f || eyeView)) heading = yaw
        updateAim()
        updateSoldiers(dt)

        val nearest = drops
            .map { it to hypot(it.x - playerX, it.z - playerZ) }
            .filter { it.second < REACH }
            .minByOrNull { it.second }?.first
        if (nearest?.id != nearbyId) {
            nearbyId = nearest?.id
            mainHandler.post { onNearbyDrop(nearest) }
        }
    }

    private fun updateCamera() {
        if (eyeView) {
            // Through the soldier's eyes; on the ground when killed.
            eyeX = playerX + sin(yaw) * 0.15f
            eyeY = playerY + if (down) 0.35f else if (prone) PRONE_EYE_HEIGHT else EYE_HEIGHT
            eyeZ = playerZ - cos(yaw) * 0.15f
            camFx = sin(yaw) * cos(lookPitch)
            camFy = -sin(lookPitch)
            camFz = -cos(yaw) * cos(lookPitch)
            if (shake > 0f) {
                // A blast nearby: the view jolts about for a moment.
                camFx += (kotlin.random.Random.nextFloat() - 0.5f) * shake * SHAKE_LOOK
                camFy += (kotlin.random.Random.nextFloat() - 0.5f) * shake * SHAKE_LOOK
                val l = sqrt(camFx * camFx + camFy * camFy + camFz * camFz)
                camFx /= l; camFy /= l; camFz /= l
            }
            Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, eyeX + camFx, eyeY + camFy, eyeZ + camFz, 0f, 1f, 0f)
            Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0)
            return
        }
        // The camera follows the body: lower when crawling, rising with a jump.
        val targetY = playerY + if (prone) 0.7f else 1.5f
        // Over the right shoulder: the camera looks at a point beside the character, so what's
        // straight ahead (the crosshair, the bullets) isn't hidden behind their head.
        var shoulder = SHOULDER_OFFSET
        while (shoulder > 0f && city.isInsideBuilding(playerX + cos(yaw) * shoulder, targetY, playerZ + sin(yaw) * shoulder, 0.3f)) {
            shoulder -= 0.15f
        }
        val lookX = playerX + cos(yaw) * shoulder.coerceAtLeast(0f)
        val lookZ = playerZ + sin(yaw) * shoulder.coerceAtLeast(0f)

        var distance = CAMERA_DISTANCE
        // Pull the camera in when a building is between it and the player.
        // Close against a wall it may come right up to the head rather than into the wall.
        var t = 0.3f
        while (t < CAMERA_DISTANCE) {
            val px = lookX - sin(yaw) * cos(pitch) * t
            val py = targetY + sin(pitch) * t
            val pz = lookZ + cos(yaw) * cos(pitch) * t
            // (Or a hillside: on a slope the camera comes in rather than going into the ground.)
            if (city.isInsideBuilding(px, py, pz, 0.35f) || py < city.groundAt(px, pz) + 0.3f) {
                distance = (t - 0.35f).coerceAtLeast(0.3f)
                break
            }
            t += 0.2f
        }
        eyeX = lookX - sin(yaw) * cos(pitch) * distance
        eyeY = (targetY + sin(pitch) * distance).coerceAtLeast(city.groundAt(eyeX, lookZ + cos(yaw) * cos(pitch) * distance) + 0.3f)
        eyeZ = lookZ + cos(yaw) * cos(pitch) * distance
        if (shake > 0f) {
            eyeX += (kotlin.random.Random.nextFloat() - 0.5f) * shake * SHAKE_MOVE
            eyeY += (kotlin.random.Random.nextFloat() - 0.5f) * shake * SHAKE_MOVE
            eyeZ += (kotlin.random.Random.nextFloat() - 0.5f) * shake * SHAKE_MOVE
        }
        Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, lookX, targetY, lookZ, 0f, 1f, 0f)
        val fx = lookX - eyeX; val fy = targetY - eyeY; val fz = lookZ - eyeZ
        val fl = sqrt(fx * fx + fy * fy + fz * fz).takeIf { it > 1e-4f } ?: 1f
        camFx = fx / fl; camFy = fy / fl; camFz = fz / fl
        Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0)
    }

    private fun syncDrops(list: List<PhotoDrop>) {
        val ids = list.map { it.id }.toSet()
        dropVisuals.keys.filter { it !in ids }.forEach { id ->
            dropVisuals.remove(id)?.let { Textures.delete(it.texture) }
        }
        for (drop in list) {
            if (drop.id in dropVisuals) continue
            val bitmap = WorldRepository.decodeScaled(repo.photoFile(drop.id), 768)
            val visual = if (bitmap != null) {
                DropVisual(drop, Textures.upload(bitmap), bitmap.width / bitmap.height.toFloat())
                    .also { bitmap.recycle() }
            } else DropVisual(drop, 0, 4f / 3f)
            dropVisuals[drop.id] = visual
        }
        drops = list
    }

    // ---- World --------------------------------------------------------------------------------

    /** Uploads the Downtown scene (built on a background thread) as one GPU mesh per tile and surface. */
    private fun uploadWorld() {
        val scene = sceneSource.get()
        tileMeshes.clear()
        for (tile in scene.tiles) {
            tileMeshes += TileMeshes(tile.centerX, tile.centerZ, tile.parts.map { (s, data) -> s to Mesh(data) })
        }
        alwaysMeshes = scene.always.map { (s, data) -> s to Mesh(data) }
        // Land under the map; beyond each edge, open sea where the coastline meets that edge (so
        // the Corniche looks out to the horizon), land elsewhere.
        val m = 900f
        val x0 = city.minX; val x1 = city.maxX; val z0 = city.minZ; val z1 = city.maxZ
        val (north, south, west, east) = seaEdges()
        val land = MeshBuilder()
        val water = MeshBuilder()
        fun strip(sea: Boolean, ax: Float, az: Float, bx: Float, bz: Float) =
            if (sea) water.floor(ax, az, bx, bz, 0.012f) else land.floor(ax, az, bx, bz, 0f, GROUND_SPAN)
        if (city.terrain.flat) {
            land.floor(x0, z0, x1, z1, 0f, GROUND_SPAN)
            strip(north, x0, z0 - m, x1, z0)
            strip(south, x0, z1, x1, z1 + m)
            strip(west, x0 - m, z0, x0, z1)
            strip(east, x1, z0, x1 + m, z1)
            strip(north || west, x0 - m, z0 - m, x0, z0)
            strip(north || east, x1, z0 - m, x1 + m, z0)
            strip(south || west, x0 - m, z1, x0, z1 + m)
            strip(south || east, x1, z1, x1 + m, z1 + m)
        } else {
            // The hills themselves are in the scene (CityScene's hillsides); beyond the map's
            // edges the land carries on at the edge's height, out to the horizon.
            hillSkirts(land, m)
        }
        ground = land.build()
        groundTexture = uploadGroundMap()
        openSea = water.build()
        if (city.limited) border = MeshBuilder().apply {
            val t = 0.08f
            val x0p = city.playMinX; val x1p = city.playMaxX; val z0p = city.playMinZ; val z1p = city.playMaxZ
            if (city.terrain.flat) {
                box(x0p - t, 0f, z0p - t, x1p + t, BORDER_HEIGHT, z0p + t)
                box(x0p - t, 0f, z1p - t, x1p + t, BORDER_HEIGHT, z1p + t)
                box(x0p - t, 0f, z0p - t, x0p + t, BORDER_HEIGHT, z1p + t)
                box(x1p - t, 0f, z0p - t, x1p + t, BORDER_HEIGHT, z1p + t)
            } else {
                // On hills, in short pieces that follow the ground.
                fun along(ax: Float, az: Float, bx: Float, bz: Float) {
                    val n = (hypot(bx - ax, bz - az) / BORDER_PIECE).toInt().coerceAtLeast(1)
                    for (i in 0 until n) {
                        val sx = ax + (bx - ax) * i / n; val sz = az + (bz - az) * i / n
                        val ex = ax + (bx - ax) * (i + 1) / n; val ez = az + (bz - az) * (i + 1) / n
                        val g = city.groundAt((sx + ex) / 2f, (sz + ez) / 2f)
                        box(min(sx, ex) - t, g - 1f, min(sz, ez) - t, max(sx, ex) + t, g + BORDER_HEIGHT, max(sz, ez) + t)
                    }
                }
                along(x0p, z0p, x1p, z0p); along(x0p, z1p, x1p, z1p)
                along(x0p, z0p, x0p, z1p); along(x1p, z0p, x1p, z1p)
            }
        }.build()
        ladderMesh = buildLadders()
        cube = MeshBuilder().apply { box(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f) }.build()
        quad = MeshBuilder().apply {
            quad(
                floatArrayOf(-0.5f, -0.5f, 0f), floatArrayOf(0.5f, -0.5f, 0f),
                floatArrayOf(0.5f, 0.5f, 0f), floatArrayOf(-0.5f, 0.5f, 0f),
                0f, 0f, 1f,
            )
        }.build()
    }

    /** The ground's height map as a texture (see the shader's uGroundMap): 0 on a flat map. */
    private var groundTexture = 0

    /**
     * The ground's heights as a texture, one texel per grid point: decimetres above the lowest
     * point, high byte in red and low in green. Sampled exactly (no blending between texels, which
     * would mix the bytes up).
     */
    private fun uploadGroundMap(): Int {
        val t = city.terrain
        if (t.flat) return 0
        val pixels = IntArray(t.cols * t.rows)
        for (r in 0 until t.rows) for (c in 0 until t.cols) {
            val v = ((t.at(c, r) - t.minHeight) * 10f).toInt().coerceIn(0, 65535)
            pixels[r * t.cols + c] = (0xFF shl 24) or ((v shr 8) shl 16) or ((v and 0xFF) shl 8)
        }
        val bitmap = Bitmap.createBitmap(pixels, t.cols, t.rows, Bitmap.Config.ARGB_8888)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return ids[0]
    }

    /**
     * Land beyond a hilly map's edges: from each piece of the edge, out [m] metres at that piece's
     * height, so the hills don't end in a cliff; corners at their corner's height.
     */
    private fun hillSkirts(land: MeshBuilder, m: Float) {
        val x0 = city.minX; val x1 = city.maxX; val z0 = city.minZ; val z1 = city.maxZ
        val piece = SKIRT_PIECE
        fun flat(ax: Float, az: Float, bx: Float, bz: Float, y: Float) = land.floor(min(ax, bx), min(az, bz), max(ax, bx), max(az, bz), y, GROUND_SPAN)
        var x = x0
        while (x < x1) {
            val e = min(x + piece, x1)
            flat(x, z0 - m, e, z0, city.groundAt((x + e) / 2f, z0))
            flat(x, z1, e, z1 + m, city.groundAt((x + e) / 2f, z1))
            x = e
        }
        var z = z0
        while (z < z1) {
            val e = min(z + piece, z1)
            flat(x0 - m, z, x0, e, city.groundAt(x0, (z + e) / 2f))
            flat(x1, z, x1 + m, e, city.groundAt(x1, (z + e) / 2f))
            z = e
        }
        flat(x0 - m, z0 - m, x0, z0, city.groundAt(x0, z0))
        flat(x1, z0 - m, x1 + m, z0, city.groundAt(x1, z0))
        flat(x0 - m, z1, x0, z1 + m, city.groundAt(x0, z1))
        flat(x1, z1, x1 + m, z1 + m, city.groundAt(x1, z1))
    }

    /**
     * Uploaded street photos become murals: each on the longest wall of one of the buildings
     * nearest the start.
     */
    private fun buildMurals() {
        if (streetPhotos.isEmpty()) return
        val nearest = city.buildings
            .filter { it.blocksWalking && it.height > 6f }
            .sortedBy { hypot(it.centerX - city.spawnX, it.centerZ - city.spawnZ) }
        streetPhotos.take(MAX_MURALS).forEachIndexed { i, file ->
            val b = nearest.getOrNull(i) ?: return
            val bitmap = WorldRepository.decodeScaled(file, 1024) ?: return@forEachIndexed
            val aspect = bitmap.width / bitmap.height.toFloat()

            // Longest wall: a → b along a counter-clockwise ring, outside to the right.
            val p = b.pts
            val n = p.size / 2
            var best = 0
            var bestLen = 0f
            for (k in 0 until n) {
                val j = (k + 1) % n
                val len = hypot(p[2 * j] - p[2 * k], p[2 * j + 1] - p[2 * k + 1])
                if (len > bestLen) { bestLen = len; best = k }
            }
            val j = (best + 1) % n
            val ax = p[2 * best]; val az = p[2 * best + 1]; val bx = p[2 * j]; val bz = p[2 * j + 1]
            val dx = (bx - ax) / bestLen; val dz = (bz - az) / bestLen
            val nx = dz; val nz = -dx
            var w = bestLen * 0.9f
            var h = w / aspect
            val cx = (ax + bx) / 2f + nx * 0.05f
            val cz = (az + bz) / 2f + nz * 0.05f
            // From just above the street in front (on a hillside, the ground there).
            val street = maxOf(city.groundAt(cx, cz), b.base)
            val maxHeight = (b.top - street) * 0.9f - 0.4f
            if (h > maxHeight) { h = maxHeight; w = h * aspect }
            val y0 = street + 0.4f
            val y1 = y0 + h
            // Seen from outside, the b end is on the left.
            val left = floatArrayOf(cx + dx * w / 2, 0f, cz + dz * w / 2)
            val right = floatArrayOf(cx - dx * w / 2, 0f, cz - dz * w / 2)
            val mesh = MeshBuilder().apply {
                quad(
                    p(left[0], y0, left[2]), p(right[0], y0, right[2]),
                    p(right[0], y1, right[2]), p(left[0], y1, left[2]),
                    nx, 0f, nz,
                )
            }.build()
            murals += mesh to Textures.upload(bitmap)
            bitmap.recycle()
        }
    }

    /** Draws the ground, the sea, and the city tiles near the camera that are in front of it. */
    private fun drawWorld() {
        draw(ground, identity, GROUND_COLOR, surfaceTextures[CityTextures.GROUND])
        draw(openSea, identity, Surface.SEA.color, shine = Surface.SEA.shine)
        for ((surface, mesh) in alwaysMeshes) drawSurface(surface, mesh)
        val fx = sin(yaw)
        val fz = -cos(yaw)
        for (tile in tileMeshes) {
            val dx = tile.centerX - playerX
            val dz = tile.centerZ - playerZ
            val distance = hypot(dx, dz)
            if (distance > DRAW_DISTANCE * (if (scoped) SCOPE_DRAW_SCALE else 1f)) continue
            // Skip tiles well behind the camera (the tile half-diagonal is ~0.71 × its size).
            if (distance > CityScene.TILE && dx * fx + dz * fz < -CityScene.TILE * 0.75f) continue
            for ((surface, mesh) in tile.meshes) drawSurface(surface, mesh)
        }
        for ((mesh, texture) in murals) draw(mesh, identity, WHITE, texture)
        drawLife()
        drawPassersby()
        ladderMesh?.let { draw(it, identity, LADDER_COLOR) }
        border?.let {
            // Drawn without writing depth so what is behind it still shows through.
            GLES20.glDepthMask(false)
            draw(it, identity, BORDER_COLOR, lit = false)
            GLES20.glDepthMask(true)
        }
    }

    /** Moves the passing cars and people on; they stop for me and the other players. */
    private fun updateLife(dt: Float) {
        val n = 2 + remotes.size * 2
        if (lifePeople.size != n) lifePeople = FloatArray(n)
        lifePeople[0] = playerX; lifePeople[1] = playerZ
        var i = 2
        for (r in remotes.values) { lifePeople[i++] = r.x; lifePeople[i++] = r.z }
        life.update(dt, playerX, playerZ, lifePeople)
        updatePassersby(dt)
    }

    /**
     * The passing cars and people, uploaded as one batch coloured from the palette: the cars
     * drawn with glossy paint and glass, then the people.
     */
    private fun drawLife() {
        val mesh = lifeMesh ?: return
        val scale = if (scoped) SCOPE_DRAW_SCALE else 1f
        val (data, floats) = life.fill(eyeX, eyeZ, CAR_DRAW_DISTANCE * scale, WALKER_DRAW_DISTANCE * scale)
        mesh.update(data, floats)
        val cars = life.carFloats / Mesh.FLOATS
        mesh.range(0, cars)
        draw(mesh, identity, WHITE, paletteTexture, shine = CAR_SHINE)
        mesh.range(cars, -1)
        draw(mesh, identity, WHITE, paletteTexture)
        drawCarModels(CAR_DRAW_DISTANCE * scale)
    }

    /** The car models once read (see [carModelSource]), uploaded the first time; null until then. */
    private fun carModels(): List<CarGl>? {
        carGl?.let { return it }
        val source = carModelSource?.takeIf { it.isDone } ?: return null
        val models = runCatching { source.get() }.getOrNull().orEmpty()
        // Kits share one colour map: uploaded once.
        val textures = HashMap<Int, Int>()
        val uploaded = models.map { m ->
            val texture = m.image?.let { bytes ->
                textures.getOrPut(bytes.contentHashCode()) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(::uploadAndRecycle) ?: 0
                }
            } ?: 0
            CarGl(Mesh(m.body), m.wheels.map { Mesh(it.vertices) }, texture, m)
        }
        carGl = uploaded
        life.useCarModels(models.map { CityLife.CarFit(it.halfLength, it.halfWidth, it.wheelRadius, it.wheelbase, CarModels.weight(it.name)) })
        return uploaded
    }

    /**
     * Cars drawn with a model: a soft shadow on the road, the body, and each wheel rolled by how
     * far the car has gone and, at the front, steered into its turn.
     */
    private fun drawCarModels(range: Float) {
        val models = carModels()?.takeIf { it.isNotEmpty() } ?: return
        val views = life.modelledCars(eyeX, eyeZ, range)
        for (car in views) {
            val gl = models.getOrNull(car.fit) ?: continue
            // Model +z forward along (fx, fz), +x to its left, (fz, -fx).
            val m = carMatrix
            m[0] = car.fz; m[1] = 0f; m[2] = -car.fx; m[3] = 0f
            m[4] = 0f; m[5] = 1f; m[6] = 0f; m[7] = 0f
            m[8] = car.fx; m[9] = 0f; m[10] = car.fz; m[11] = 0f
            m[12] = car.x; m[13] = city.groundAt(car.x, car.z); m[14] = car.z; m[15] = 1f
            // The shadow: drawn without writing depth, a little above the road.
            System.arraycopy(m, 0, wheelMatrix, 0, 16)
            Matrix.translateM(wheelMatrix, 0, 0f, CAR_SHADOW_LIFT, 0f)
            Matrix.rotateM(wheelMatrix, 0, -90f, 1f, 0f, 0f)
            Matrix.scaleM(wheelMatrix, 0, gl.model.halfWidth * 2.5f, gl.model.halfLength * 2.3f, 1f)
            GLES20.glDepthMask(false)
            draw(quad, wheelMatrix, CAR_SHADOW_COLOR, puffTexture, lit = false)
            GLES20.glDepthMask(true)
            draw(gl.body, m, WHITE, gl.texture, shine = CAR_SHINE)
            gl.model.wheels.forEachIndexed { i, w ->
                System.arraycopy(m, 0, wheelMatrix, 0, 16)
                Matrix.translateM(wheelMatrix, 0, w.x, w.y, w.z)
                if (w.front) Matrix.rotateM(wheelMatrix, 0, deg(car.steer), 0f, 1f, 0f)
                Matrix.rotateM(wheelMatrix, 0, deg(car.spin), 1f, 0f, 0f)
                draw(gl.wheels[i], wheelMatrix, WHITE, gl.texture)
            }
        }
    }

    /**
     * Gives the people nearest the camera a real character model, walking, running or standing
     * as they move, once the models have loaded; everyone else stays a simple figure.
     */
    private fun updatePassersby(dt: Float) {
        val ready = pedestrianRigs.filter { it.isDone }.mapNotNull { runCatching { it.get() }.getOrNull() }
        passerbyViews = if (ready.isEmpty()) emptyList() else life.nearest(eyeX, eyeZ, MODELLED_PEOPLE, MODELLED_PEOPLE_RANGE)
        life.modelled = passerbyViews.mapTo(HashSet()) { it.id }
        // Forget those who are no longer near (and their GPU copies).
        val gone = passersby.keys - life.modelled
        for (id in gone) {
            passerbyTurns.remove(id)
            passersby.remove(id)?.let { soldierMeshes.remove(it)?.forEach { m -> m.release() }; uploadedVersion.remove(it) }
        }
        passerbyTurning.clear()
        for (p in passerbyViews) {
            val anim = passersby.getOrPut(p.id) { SoldierAnimator(ready[p.variant % ready.size]) }
            // Turned round to run from danger: the turn clip swings the body round from the old
            // way to the new, then the run follows with no blend (which would swing it back).
            val seen = passerbyTurns.put(p.id, p.turns)
            if (seen != null && seen != p.turns) anim.dance(TURN_CLIP, snapBack = true)
            // Judged before this frame's update, which may end the turn: its last frame still
            // shows the turned body, so it's drawn the old way round once more.
            if (anim.dancing == TURN_CLIP && p.death == 0) passerbyTurning += p.id
            anim.update(dt, p.speed, 0f, aiming = false, dead = p.death > 0, skin = true, deathClip = "Death${p.death}")
        }
    }

    private fun drawPassersby() {
        for (p in passerbyViews) {
            val anim = passersby[p.id] ?: continue
            // Mid-turn they're drawn facing the way they came; the clip turns them round.
            val heading = if (p.id in passerbyTurning) p.heading + PI.toFloat() else p.heading
            // Models with plain materials named like clothes take the person's colours.
            drawSoldier(anim, SoldierLook(p.shirt, p.trousers, 0), 0, p.x, p.z, heading, y = city.groundAt(p.x, p.z), armed = false)
        }
    }

    private fun drawSurface(surface: Surface, mesh: Mesh) {
        val texture = if (surface.texture >= 0) surfaceTextures[surface.texture] else 0
        // Things laid on the ground are pulled towards the camera a little (more for each layer up),
        // so on hills the ground never pokes through them where it bulges.
        if (surface.layer > 0) {
            GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
            GLES20.glPolygonOffset(-1f, -DECAL_UNITS * surface.layer)
        }
        draw(mesh, identity, surface.color, texture, surface.lit, surface.shine, surface.ao, surface.cutout)
        if (surface.layer > 0) GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
    }

    // ---- Photo drops --------------------------------------------------------------------------

    private fun boardSize(aspect: Float): Pair<Float, Float> {
        var w = 2.2f
        var h = w / aspect
        if (h > 2.4f) { h = 2.4f; w = h * aspect }
        return w to h
    }

    /** Local (lx, lz) around a drop, turned the way the drop faces, to world x/z. */
    private fun dropToWorld(drop: PhotoDrop, lx: Float, lz: Float): Pair<Float, Float> {
        val a = -drop.yaw
        return (drop.x + lx * cos(a) + lz * sin(a)) to (drop.z - lx * sin(a) + lz * cos(a))
    }

    private fun drawDrop(visual: DropVisual) {
        val drop = visual.drop
        val (w, h) = boardSize(visual.aspect)
        val bottom = 1.0f
        Matrix.setIdentityM(base, 0)
        Matrix.translateM(base, 0, drop.x, city.groundAt(drop.x, drop.z), drop.z)
        // Local +z is the side facing whoever dropped it.
        Matrix.rotateM(base, 0, -deg(drop.yaw), 0f, 1f, 0f)

        partBox(0f, (bottom + h / 2f) / 2f, -0.06f, 0.1f, bottom + h / 2f, 0.1f, 0xFF3A3A3A.toInt())
        partBox(0f, bottom + h / 2f, 0f, w + 0.18f, h + 0.18f, 0.08f, 0xFFF5F5F5.toInt())
        if (visual.texture != 0) {
            Matrix.translateM(part, 0, base, 0, 0f, bottom + h / 2f, 0.055f)
            Matrix.scaleM(part, 0, w, h, 1f)
            draw(quad, part, WHITE, visual.texture, lit = false)
        }

        // The author stands beside the photo in their team's uniform, facing the same way.
        val (sx, sz) = dropToWorld(drop, w / 2f + 0.8f, 0.4f)
        val statue = statueSoldier ?: return
        if (!isWorthAnimating(sx, sz)) return
        drawSoldier(statue, lookFor(drop.team), faceTexture(dropFace(drop)), sx, sz, drop.yaw + PI.toFloat(), y = city.groundAt(sx, sz))
    }

    /** How far a player's name tag moves: up with a jump, down near the ground when crawling. */
    private fun labelLift(y: Float, prone: Boolean) = y - if (prone) 1.4f else 0f

    /** Uniform, gear and flag badge for a team id ("" or unknown: a neutral grey uniform). */
    private fun lookFor(team: String): SoldierLook {
        val colors = teamUniform(team)
        return SoldierLook(
            uniform = colors?.first ?: 0xFF5E6266.toInt(),
            gear = colors?.second ?: 0xFF3A3D40.toInt(),
            badgeTexture = team.takeIf { it.isNotEmpty() }?.let(::flagLabel)?.texture ?: 0,
        )
    }

    private fun drawDropLabels(visual: DropVisual) {
        val (w, _) = boardSize(visual.aspect)
        val (sx, sz) = dropToWorld(visual.drop, w / 2f + 0.8f, 0.4f)
        drawLabels(sx, sz, visual.drop.author, visual.drop.caption.ifBlank { null }, lift = city.groundAt(sx, sz))
    }

    // ---- Characters ---------------------------------------------------------------------------

    /** Face texture for a face file, cached by path; 0 when there is no face photo. */
    private fun faceTexture(file: File): Int = faces.getOrPut(file.path) {
        file.takeIf { it.exists() }
            ?.let { BitmapFactory.decodeFile(it.path) }
            ?.let { bitmap: Bitmap -> Textures.upload(bitmap).also { bitmap.recycle() } }
            ?: 0
    }

    /**
     * An animated soldier with feet at (x, 0, z), facing [facing], in their team's uniform: the
     * face photo on the head, the team flag on both shoulders and a rifle in the right hand, all
     * following the animated bones.
     */
    private fun drawSoldier(
        anim: SoldierAnimator, look: SoldierLook, faceTex: Int, x: Float, z: Float, facing: Float,
        y: Float = 0f, prone: Boolean = false, gun: Weapon = Weapon.AK47,
        /** A grenade about to be thrown: in the hand instead of the gun. */
        holding: GrenadeKind? = null,
        /** On a ladder: both hands on the rails, the gun slung out of sight. */
        climbing: Boolean = false,
        /** False for people in the street: no gun. */
        armed: Boolean = true,
    ) {
        if (!anim.ready) return
        val meshes = soldierMeshes.getOrPut(anim) {
            anim.rig.model.primitives.mapIndexed { i, p -> DynamicMesh(anim.pose.vertices[i].size, p.indices) }
        }
        if (uploadedVersion[anim] != anim.version) {
            meshes.forEachIndexed { i, m -> m.update(anim.pose.vertices[i]) }
            uploadedVersion[anim] = anim.version
        }

        // The model faces +z; the game's characters face -z at heading 0, hence the extra half turn.
        // Scaled to metres and stood on the ground, whatever units the model uses.
        val rig = anim.rig
        val s = rig.scale
        Matrix.setIdentityM(base, 0)
        Matrix.translateM(base, 0, x, y, z)
        Matrix.rotateM(base, 0, 180f - deg(facing), 0f, 1f, 0f)
        if (prone && anim.lieDownForCrawl) {
            // No crawl animation: lay the body flat on its belly, head forward (+z); CrawlPose
            // then poses the arms, legs, chest and head for an army crawl.
            Matrix.translateM(base, 0, 0f, PRONE_LIFT, 0f)
            Matrix.rotateM(base, 0, 90f, 1f, 0f, 0f)
        }
        Matrix.scaleM(base, 0, s, s, s)
        Matrix.translateM(base, 0, 0f, -rig.footY, 0f)
        val textures = modelTextures.getOrPut(rig) {
            rig.model.images.mapIndexed { i, bytes ->
                // A character in army kit has its clothes repainted (see ArmyOutfit).
                ArmyOutfit.bitmap(bytes, rig.imageRole(i))?.let(::uploadAndRecycle) ?: 0
            }
        }
        rig.model.primitives.forEachIndexed { i, p ->
            val texture = if (p.image >= 0) textures.getOrElse(p.image) { 0 } else 0
            drawMesh(meshes[i], base, tint(materialColor(rig.roles[i], p.baseColor, look)), texture)
        }

        if (faceTex != 0 && rig.head >= 0) {
            anim.pose.nodeMatrix(rig.head, bone)
            Matrix.multiplyMM(boneWorld, 0, base, 0, bone, 0)
            Matrix.multiplyMM(part, 0, boneWorld, 0, rig.faceAnchor, 0)
            draw(quad, part, WHITE, faceTex, lit = false)
        }
        // The team flag on the back of the shirt.
        if (look.badgeTexture != 0) {
            for ((k, badgeBone) in rig.badgeBones.withIndex()) {
                if (badgeBone < 0) continue
                anim.pose.nodeMatrix(badgeBone, bone)
                Matrix.multiplyMM(boneWorld, 0, base, 0, bone, 0)
                Matrix.multiplyMM(part, 0, boneWorld, 0, rig.badgeAnchors[k], 0)
                draw(quad, part, WHITE, look.badgeTexture, lit = false)
            }
        }

        // Gun: held at the right hand, pointing where the soldier faces (+z in model space).
        // Sizes are in metres, times the model's units per metre.
        if (rig.wrist < 0 || climbing || !armed) return
        anim.pose.nodeMatrix(rig.wrist, bone)
        val wx = bone[12]; val wy = bone[13]; val wz = bone[14]
        val u = rig.unit
        // Standing, the rifle points along model +z with +y up; lying flat (the model tipped
        // forwards) it points along +y with -z up, so it lies ahead of the soldier, not in the ground.
        val flat = prone && anim.lieDownForCrawl
        if (holding != null) {
            // The grenade in the hand (life-size, in metres, so times the model's units per
            // metre), gripped a little above the wrist; the gun is put away meanwhile.
            System.arraycopy(base, 0, soldierMatrix, 0, 16)
            Matrix.translateM(gunInHand, 0, base, 0, wx, wy, wz)
            if (flat) Matrix.rotateM(gunInHand, 0, -90f, 1f, 0f, 0f)
            Matrix.scaleM(gunInHand, 0, u, u, u)
            Matrix.translateM(base, 0, gunInHand, 0, 0f, -0.02f, 0.06f)
            grenadeShape(holding)
            System.arraycopy(soldierMatrix, 0, base, 0, 16)
            return
        }
        fun gunBox(up: Float, fwd: Float, width: Float, height: Float, length: Float, color: Int) =
            if (flat) partBox(wx, wy + fwd * u, wz - up * u, width * u, length * u, height * u, color)
            else partBox(wx, wy + up * u, wz + fwd * u, width * u, height * u, length * u, color)
        // The gun, moved so its grip sits in the hand. Pistols are held a little higher and closer
        // than long guns.
        val model = GunModels.of(gun)
        val mesh = meshOf(gun)
        val handUp = if (gun.slot == GunSlot.PISTOL) -0.03f else -0.06f
        val handFwd = if (gun.slot == GunSlot.PISTOL) 0.03f else 0.02f
        val gripY = mesh?.gripY ?: model.gripY
        val gripZ = mesh?.gripZ ?: model.gripZ
        if (mesh != null) {
            // Gun space (muzzle along -z) to the hand: grip to the origin, half a turn so the
            // muzzle points the way the soldier faces (+z), out to the hand, into model units.
            Matrix.translateM(gunInHand, 0, base, 0, wx, wy, wz)
            if (flat) Matrix.rotateM(gunInHand, 0, -90f, 1f, 0f, 0f)
            Matrix.scaleM(gunInHand, 0, u, u, u)
            Matrix.translateM(gunInHand, 0, 0f, handUp, handFwd)
            Matrix.rotateM(gunInHand, 0, 180f, 0f, 1f, 0f)
            Matrix.translateM(gunInHand, 0, 0f, -gripY, -gripZ)
            drawGunMesh(gun, mesh, gunInHand)
        } else {
            // Tiny details are left out at this size.
            for (p in model.parts) {
                if (maxOf(p.sx, p.sy, p.sz) < 0.03f) continue
                gunBox(p.y - model.gripY + handUp, model.gripZ - p.z + handFwd, p.sx, p.sy, p.sz, p.color)
            }
        }
        val muzzleAhead = gripZ - (mesh?.muzzleZ ?: model.muzzleZ) + handFwd
        val muzzleUp = (mesh?.muzzleY ?: model.muzzleY) - gripY + handUp
        if (anim === playerSoldier) {
            if (flat) Mat.transformPoint(base, wx, wy + muzzleAhead * u, wz - muzzleUp * u, muzzle)
            else Mat.transformPoint(base, wx, wy + muzzleUp * u, wz + muzzleAhead * u, muzzle)
            muzzleKnown = true
        }
    }

    /** Colour of a soldier material: uniform and gear follow the team, the rest looks natural. */
    private fun materialColor(role: MaterialRole, own: Int, look: SoldierLook): Int = when (role) {
        MaterialRole.UNIFORM -> look.uniform
        MaterialRole.GEAR -> look.gear
        MaterialRole.DARK -> 0xFF1E1E1E.toInt()
        MaterialRole.SKIN -> SKIN
        MaterialRole.HAIR -> 0xFF3B2A1C.toInt()
        MaterialRole.BROWN -> 0xFF4A3322.toInt()
        MaterialRole.OWN -> own
        MaterialRole.CAMO, MaterialRole.BOOTS -> ArmyOutfit.color(role, look.uniform)
    }

    private fun drawMesh(mesh: DynamicMesh, model: FloatArray, color: Int, texture: Int = 0) {
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)
        GLES20.glUniformMatrix4fv(shader.uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(shader.uModel, 1, false, model, 0)
        GLES20.glUniform4f(
            shader.uColor,
            (color shr 16 and 0xFF) / 255f, (color shr 8 and 0xFF) / 255f,
            (color and 0xFF) / 255f, (color ushr 24) / 255f,
        )
        if (texture != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        }
        GLES20.glUniform1f(shader.uUseTex, if (texture != 0) 1f else 0f)
        GLES20.glUniform1f(shader.uLit, 1f)
        finish(0f, false)
        mesh.draw(shader)
    }

    /**
     * Name tag (in the team colour, with the team flag beside it), then a health bar (when
     * [health] is given), then an optional speech bubble, floating above a character at (x, z).
     */
    private fun drawLabels(
        x: Float, z: Float, name: String, saying: String?,
        health: Int? = null, team: String? = null, lift: Float = 0f,
    ) {
        val color = team?.let(teamColor)
        val tag = label("n:$name:$color") { CityBitmaps.nameTag(name, color) to NAME_UNITS_PER_PX }
        val flag = team?.takeIf { it.isNotEmpty() }?.let(::flagLabel)
        if (flag != null) {
            // Flag then name, centred together.
            val gap = 0.05f
            val total = flag.width + gap + tag.width
            drawBillboard(flag, x, LABEL_Y + lift + (tag.height - flag.height) / 2f, z, -total / 2f + flag.width / 2f)
            drawBillboard(tag, x, LABEL_Y + lift, z, total / 2f - tag.width / 2f)
        } else {
            drawBillboard(tag, x, LABEL_Y + lift, z)
        }
        var top = LABEL_Y + lift + tag.height + 0.06f
        if (health != null) {
            drawHealthBar(x, top, z, health)
            top += HEALTH_BAR_HEIGHT + 0.06f
        }
        if (saying != null) {
            val bubble = label("b:$saying") { CityBitmaps.speechBubble(saying) to BUBBLE_UNITS_PER_PX }
            drawBillboard(bubble, x, top, z)
        }
    }

    /** A bar of [MAX_HEALTH] that empties with each hit, turning from green to red. */
    private fun drawHealthBar(x: Float, bottom: Float, z: Float, health: Int) {
        val w = HEALTH_BAR_WIDTH
        val h = HEALTH_BAR_HEIGHT
        val fill = health.coerceIn(0, MAX_HEALTH) / MAX_HEALTH.toFloat()
        Matrix.setIdentityM(part, 0)
        Matrix.translateM(part, 0, x, bottom + h / 2f, z)
        Matrix.rotateM(part, 0, -deg(yaw), 0f, 1f, 0f)
        val face = part.copyOf()
        Matrix.scaleM(part, 0, w + 0.06f, h + 0.06f, 1f)
        draw(quad, part, 0xCC000000.toInt(), lit = false)
        if (fill <= 0f) return
        // Left-aligned fill, a hair in front of the background.
        Matrix.translateM(part, 0, face, 0, -(w - w * fill) / 2f, 0f, 0.005f)
        Matrix.scaleM(part, 0, w * fill, h, 1f)
        val color = if (fill > 0.5f) 0xFF43A047.toInt() else if (fill > 0.2f) 0xFFFFB300.toInt() else 0xFFE53935.toInt()
        draw(quad, part, color, lit = false)
    }

    /** The team's flag texture, loaded once; null for a team id this app doesn't know. */
    private fun flagLabel(team: String): Label? {
        labels["f:$team"]?.let { return it }
        if (team in unknownTeams) return null
        val bitmap = teamFlag(team) ?: run {
            unknownTeams += team
            return null
        }
        return label("f:$team") { bitmap to FLAG_WIDTH / bitmap.width }
    }
    private val unknownTeams = HashSet<String>()

    private fun label(key: String, make: () -> Pair<Bitmap, Float>): Label = labels.getOrPut(key) {
        if (labels.size > MAX_LABELS) {
            labels.values.forEach { Textures.delete(it.texture) }
            labels.clear()
        }
        val (bitmap, unitsPerPx) = make()
        Label(Textures.upload(bitmap), bitmap.width * unitsPerPx, bitmap.height * unitsPerPx)
            .also { bitmap.recycle() }
    }

    /**
     * A flat label that always turns to face the camera, its bottom edge at [bottom], moved
     * [sideways] along the screen's horizontal.
     */
    private fun drawBillboard(label: Label, x: Float, bottom: Float, z: Float, sideways: Float = 0f) {
        Matrix.setIdentityM(part, 0)
        Matrix.translateM(part, 0, x, bottom + label.height / 2f, z)
        Matrix.rotateM(part, 0, -deg(yaw), 0f, 1f, 0f)
        if (sideways != 0f) Matrix.translateM(part, 0, sideways, 0f, 0f)
        Matrix.scaleM(part, 0, label.width, label.height, 1f)
        draw(quad, part, WHITE, label.texture, lit = false)
    }

    // ---- Drawing helpers ----------------------------------------------------------------------

    /** A box part of the current [base] model, centred at (x, y, z) with the given size. */
    private fun partBox(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, color: Int) {
        Matrix.translateM(part, 0, base, 0, x, y, z)
        Matrix.scaleM(part, 0, sx, sy, sz)
        draw(cube, part, tint(color))
    }

    /** While [flashing], every body part turns red for a moment (a hit marker). */
    private fun tint(color: Int) = if (flashing) 0xFFFF5252.toInt() else color

    private fun draw(
        mesh: Drawable, model: FloatArray, color: Int, texture: Int = 0, lit: Boolean = true,
        shine: Float = 0f, ao: Boolean = false, cutout: Boolean = false,
    ) {
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)
        GLES20.glUniformMatrix4fv(shader.uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(shader.uModel, 1, false, model, 0)
        GLES20.glUniform4f(
            shader.uColor,
            (color shr 16 and 0xFF) / 255f, (color shr 8 and 0xFF) / 255f,
            (color and 0xFF) / 255f, (color ushr 24) / 255f,
        )
        if (texture != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        }
        GLES20.glUniform1f(shader.uUseTex, if (texture != 0) 1f else 0f)
        GLES20.glUniform1f(shader.uLit, if (lit) 1f else 0f)
        finish(shine, ao, cutout)
        mesh.draw(shader)
    }

    /** What the shader's sky reflection, wall shading and cut-out are set to (-1 = not yet this frame). */
    private var shineSet = -1f
    private var aoSet = -1f
    private var cutoutSet = -1f

    /** Sets how much the sky is reflected, whether walls darken at their foot and whether see-through texels are cut away, if changed. */
    private fun finish(shine: Float, ao: Boolean, cutout: Boolean = false) {
        if (shine != shineSet) { GLES20.glUniform1f(shader.uShine, shine); shineSet = shine }
        val a = if (ao) 1f else 0f
        if (a != aoSet) { GLES20.glUniform1f(shader.uAo, a); aoSet = a }
        val c = if (cutout) 0.5f else 0f
        if (c != cutoutSet) { GLES20.glUniform1f(shader.uCutout, c); cutoutSet = c }
    }

    private fun p(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    private fun deg(radians: Float) = Math.toDegrees(radians.toDouble()).toFloat()

    /** Turns [current] towards [target] by at most [maxStep], the short way round. */
    private fun approachAngle(current: Float, target: Float, maxStep: Float): Float {
        var diff = (target - current) % (2 * PI.toFloat())
        if (diff > PI) diff -= 2 * PI.toFloat()
        if (diff < -PI) diff += 2 * PI.toFloat()
        return current + diff.coerceIn(-maxStep, maxStep)
    }

    companion object {
        private val SKY = floatArrayOf(0.64f, 0.8f, 0.94f)
        private val LIGHT = floatArrayOf(0.36f, 0.83f, 0.42f)
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val WALK_SPEED = 2.6f
        private const val RUN_SPEED = 7.5f
        /** Pushing the joystick further than this (0..1) runs instead of walking. */
        private const val RUN_STICK = 0.6f
        /** Soldiers further than this aren't drawn or animated (lost in the fog anyway). */
        private const val SOLDIER_DRAW_DISTANCE = 110f
        private const val EYE_HEIGHT = 1.68f
        private const val PRONE_EYE_HEIGHT = 0.35f
        /** Bottom of the name tag above a standing player. */
        private const val LABEL_Y = 2.2f
        // Body shape for hits: standing is an upright cylinder, crawling a flat one along the ground.
        private const val BODY_HEIGHT = 1.85f
        private const val PRONE_BODY_HEIGHT = 0.45f
        /** The top of a standing body that counts as the head, for headshots. */
        private const val HEAD_HEIGHT = 0.3f
        private const val PRONE_BODY_LENGTH = 1.6f
        /** How high the lying body is lifted so it rests on the ground rather than in it. */
        private const val PRONE_LIFT = 0.16f
        private const val PRONE_GUN_HEIGHT = 0.3f
        private const val CRAWL_SPEED = 1.4f
        /** Rapid fire cheat: time between shots is cut to this share (the pistol 2 a second, the AK-47 20). */
        private const val RAPID_FIRE_SCALE = 0.5f
        /** Take-off speed and gravity: a jump about 0.9 m high, 0.85 s long. */
        private const val JUMP_SPEED = 4.2f
        private const val GRAVITY = 9.8f
        private const val AIM_POSE_MS = 700L
        private const val BODY_RADIUS = 0.35f
        private const val CAMERA_DISTANCE = 6.5f
        /** How far right of the character the camera looks (over-the-shoulder view), metres. */
        private const val SHOULDER_OFFSET = 0.9f
        private const val REACH = 3.5f
        private const val SPEECH_MILLIS = 12_000L
        private const val GROUND_COLOR = 0xFFDAD3C4.toInt()
        /** Hilly maps: the land past the edge, and the play-area border, in pieces this long (metres). */
        private const val SKIRT_PIECE = 8f
        private const val BORDER_PIECE = 4f
        /** uGroundBase when there's no ground height map (a flat map). */
        private const val NO_GROUND_MAP = -10000f
        /** Depth pulled towards the camera per ground layer (see Surface.layer), in the depth buffer's smallest steps. */
        private const val DECAL_UNITS = 4f
        private val GROUND_SPAN = CityTextures.groundSpan(CityTextures.GROUND)
        private const val FOG_START = 70f
        private const val FOG_END = 230f
        /** City tiles further than this from the player are not drawn (they'd be lost in the fog). */
        private const val DRAW_DISTANCE = 300f
        /** Passing cars and people further than this aren't drawn (lost in the haze), and how glossy car paint is. */
        private const val CAR_DRAW_DISTANCE = 200f
        private const val WALKER_DRAW_DISTANCE = 90f
        private const val CAR_SHINE = 0.3f
        /** The soft dark patch under a car model, and how far above the road it is drawn. */
        private const val CAR_SHADOW_COLOR = 0x8C000000.toInt()
        private const val CAR_SHADOW_LIFT = 0.04f
        /** How many of the nearest people in the street get a character model, and how near they must be. */
        private const val MODELLED_PEOPLE = 8
        private const val MODELLED_PEOPLE_RANGE = 35f
        /** A street person turning round to run away (turn.glb, see SoldierRig.MIXAMO_CLIPS). */
        private const val TURN_CLIP = "Turn"
        /** The play-area wall: 3 m of see-through red. */
        private const val BORDER_HEIGHT = 3f
        private const val BORDER_COLOR = 0x55FF3B30

        /** Hits it takes to kill a player. */
        const val MAX_HEALTH = 5
        /** How often "out of bullets" is reported while Shoot is held with an empty gun. */
        private const val EMPTY_CLICK_INTERVAL = 0.6f
        private const val NORMAL_FOV = 60f
        /** The near clipping plane, and how close it may come when the camera is against a wall. */
        private const val NEAR_PLANE = 0.25f
        private const val MIN_NEAR_PLANE = 0.03f
        /** Field of view the held gun is drawn with in Gun view: narrower than the world's, so it looks bigger. */
        private const val HELD_GUN_FOV = 40f
        /** A textured sleeve (grey cloth) is tinted with the team colour brightened this much. */
        private const val SLEEVE_ON_FABRIC = 2.2f
        /** Through the scope: fog, pickups and soldiers can be seen this many times further. */
        private const val SCOPE_RANGE_SCALE = 2.5f
        private const val SCOPE_DRAW_SCALE = 1.6f
        private const val PICKUP_DRAW_DISTANCE = 90f
        private const val PICKUP_LABEL_DISTANCE = 30f
        /** Stores are drawn this far off, and labelled this far. */
        private const val STORE_DRAW_DISTANCE = 160f
        private const val STORE_LABEL_DISTANCE = 70f
        private const val SKIN = 0xFFC9A07E.toInt()
        private const val BULLET_STEP = 0.4f
        /** Bullets fly at gun height (the raised arm is at shoulder height). */
        private const val BULLET_Y = 1.5f
        private const val MUZZLE_FORWARD = 0.95f
        private const val MUZZLE_RIGHT = 0.32f
        private const val MUZZLE_FLASH_MS = 60L
        private const val AIM_RANGE = 120f
        private const val HIT_RADIUS = 0.5f
        private const val FLAG_WIDTH = 0.42f
        private const val HEALTH_BAR_WIDTH = 0.8f
        private const val HEALTH_BAR_HEIGHT = 0.09f
        // Grenades: thrown at this speed (m/s) along the view, plus a lift, at most once per interval (s).
        private const val THROW_SPEED = 14f
        private const val THROW_SPEED_PRONE = 8f
        private const val THROW_LIFT = 3.5f
        private const val THROW_INTERVAL = 0.9f
        /** A throw's whole movement (gun down, wind up, throw, gun back) and when in it the grenade leaves the hand, ms. */
        private const val THROW_ANIM_MS = 850L
        private const val THROW_RELEASE_MS = 380L
        /** Grenades fly in steps of this many seconds (the same on every phone). */
        private const val GRENADE_STEP = 1f / 120f
        private const val GRENADE_RADIUS = 0.07f
        /** Share of its speed a grenade keeps bouncing back, and along the ground on a bounce or while rolling (per step). */
        private const val BOUNCE = 0.35f
        private const val BOUNCE_FRICTION = 0.6f
        private const val ROLL_FRICTION = 0.97f
        /** How long a frag's fireball and smoke, and a flashbang's flash, show (ms). */
        private const val FRAG_SHOW_MS = 2_200L
        private const val FLASH_SHOW_MS = 400L
        /** A molotov's fire first burns this long after it breaks, then every interval (ms). */
        private const val BURN_FIRST_MS = 300L
        private const val BURN_INTERVAL_MS = 1_000L
        /** A smoke cloud spreads over this long, and thins out over the last of its time (ms). */
        private const val SMOKE_GROW_MS = 2_500L
        private const val SMOKE_FADE_MS = 3_000L
        /** A frag shakes the camera within this many metres; the shake dies away at this rate per second. */
        private const val SHAKE_DISTANCE = 22f
        private const val SHAKE_FADE = 1.6f
        /** At full shake: how far the view direction (first person) or the camera (behind) jolts. */
        private const val SHAKE_LOOK = 0.08f
        private const val SHAKE_MOVE = 0.35f
        // Ladders: climbed at this many metres a second, from within reach of their foot or top.
        private const val CLIMB_SPEED = 3f
        private const val LADDER_REACH = 1.3f
        /** How close to a roof's edge a player can walk, metres (there's no walking off it). */
        private const val ROOF_EDGE = 0.35f
        private const val RUNG_GAP = 0.32f
        /** Safety yellow, so a ladder can be spotted down a street. */
        private const val LADDER_COLOR = 0xFFE5A823.toInt()
        private const val MAX_MURALS = 16
        private const val MAX_LABELS = 64
        private const val BUBBLE_UNITS_PER_PX = 1f / 190f
        private const val NAME_UNITS_PER_PX = 1f / 170f
    }
}
