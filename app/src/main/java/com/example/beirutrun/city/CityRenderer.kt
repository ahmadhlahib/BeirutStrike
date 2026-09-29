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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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
    /** The animated soldier model and its attachment points, loaded on a background thread. */
    private val soldierSource: java.util.concurrent.Future<SoldierRig>,
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
    /** Called on the main thread when one of the player's bullets hits another player (their uid). */
    private val onHitPlayer: (String) -> Unit,
    /** The player's team id (see Teams); empty = no team. */
    private val playerTeam: String,
    /** A team's flag image and colour, for the label above each player. */
    private val teamFlag: (String) -> Bitmap?,
    private val teamColor: (String) -> Int?,
    /** A team's uniform and gear colours for the soldier model (null = neutral). */
    private val teamUniform: (String) -> Pair<Int, Int>?,
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
        pendingYaw += dxPixels * 0.0065f
        pendingPitch += dyPixels * 0.004f
    }

    fun setDrops(drops: List<PhotoDrop>) { pendingDrops = drops.toList() }

    /** Shows [text] in a bubble above the player for a few seconds. */
    fun say(text: String) { pendingSpeech = text }

    /** A face photo changed or arrived; reload face textures on the next frame. */
    fun reloadFaces() { pendingFaceReload = true }

    /** Latest positions of the other players (from Firebase). */
    fun setRemotePlayers(players: List<RemotePlayer>) { pendingRemote = players }

    /** True while the Shoot button is held; fires every [FIRE_INTERVAL] seconds. */
    @Volatile var triggerHeld = false

    /** The player's remaining health, shown in the bar above their head. */
    @Volatile var health = MAX_HEALTH

    /** The player has been killed: lies on the ground, can't walk or shoot. */
    @Volatile var down = false

    /** First-person view: see through the soldier's eyes (only the rifle shows) instead of from behind. */
    @Volatile var firstPerson = false

    /** Lying on the ground (crawling): slow, low, hard to hit; can't run or jump. */
    @Volatile var prone = false

    /** How high the player is off the ground right now (jumping), metres. */
    @Volatile var playerY = 0f
        private set

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
    fun addRemoteShot(uid: String, x: Float, y: Float, z: Float, dx: Float, dy: Float, dz: Float) {
        remoteAiming[uid] = SystemClock.uptimeMillis() + AIM_POSE_MS
        remoteShots.add(floatArrayOf(x, y, z, dx, dy, dz))
    }

    // ---- GL resources (recreated whenever the GL context is) ----------------------------------

    private lateinit var shader: CityShader
    private lateinit var cube: Mesh
    private lateinit var quad: Mesh
    private lateinit var ground: Mesh
    private lateinit var openSea: Mesh
    /** The see-through wall round the play area (null when the whole map is playable). */
    private var border: Mesh? = null

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
    private val facadeTextures = IntArray(WALL_STYLES)
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
        var speed = 0f
        /** Their jump, replayed here from [RemotePlayer.jumpSeq] so it's smooth. */
        var y = 0f
        var vy = 0f
        var jumpSeq = -1L
    }

    // ---- Soldiers -----------------------------------------------------------------------------

    private var rig: SoldierRig? = null
    private var playerSoldier: SoldierAnimator? = null
    /** One shared idle pose for every photo-drop statue. */
    private var statueSoldier: SoldierAnimator? = null
    private var lastPlayerX = playerX
    private var lastPlayerZ = playerZ
    private var playerSpeed = 0f
    /** GPU copies of each soldier's skinned mesh (recreated with the GL context). */
    private val soldierMeshes = HashMap<SoldierAnimator, List<DynamicMesh>>()
    private val uploadedVersion = HashMap<SoldierAnimator, Int>()
    /** GPU textures of each soldier model's embedded images (a realistic model's clothes). */
    private val modelTextures = HashMap<SoldierRig, List<Int>>()
    private val bone = FloatArray(16)
    private val boneWorld = FloatArray(16)
    /** Where my rifle's muzzle was last drawn (world space), so bullets leave the gun. */
    private val muzzle = FloatArray(3)
    private var muzzleKnown = false
    /** First-person look up/down, radians (0 = level, positive = down). */
    private var lookPitch = 0f
    private var lastShotAt = 0L
    private val cameraToWorld = FloatArray(16)
    private val remotes = HashMap<String, RemoteAvatar>()

    /** A bullet flying in 3D along (dx, dy, dz), a unit direction. */
    private class Bullet(
        var x: Float, var y: Float, var z: Float,
        val dx: Float, val dy: Float, val dz: Float,
        val mine: Boolean,
    ) {
        var travelled = 0f
    }
    private var vy = 0f
    private val bullets = ArrayList<Bullet>()
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
        for (style in 0 until WALL_STYLES) {
            val bitmap = CityBitmaps.facade(style)
            facadeTextures[style] = Textures.upload(bitmap, repeat = true)
            bitmap.recycle()
        }
        buildMurals()
        lastFrame = SystemClock.uptimeMillis()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        Matrix.perspectiveM(projection, 0, 60f, width / height.coerceAtLeast(1).toFloat(), 0.25f, 600f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now

        applyPending(now)
        update(dt)
        updateCamera()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(shader.program)
        GLES20.glEnableVertexAttribArray(shader.aPos)
        GLES20.glEnableVertexAttribArray(shader.aNormal)
        GLES20.glEnableVertexAttribArray(shader.aUv)
        GLES20.glUniform1i(shader.uTex, 0)
        GLES20.glUniform3f(shader.uEye, eyeX, eyeY, eyeZ)
        GLES20.glUniform3f(shader.uLightDir, LIGHT[0], LIGHT[1], LIGHT[2])
        GLES20.glUniform3f(shader.uFogColor, SKY[0], SKY[1], SKY[2])
        GLES20.glUniform2f(shader.uFog, FOG_START, FOG_END)

        drawWorld()
        for (visual in dropVisuals.values) drawDrop(visual)
        val isDown = down
        val fp = firstPerson
        if (fp) muzzleKnown = false
        else playerSoldier?.let { drawSoldier(it, lookFor(playerTeam), faceTexture(playerFace()), playerX, playerZ, heading, playerY, prone && !isDown) }
        for (r in remotes.values) {
            val soldier = r.soldier ?: continue
            if (!isWorthAnimating(r.x, r.z)) continue
            flashing = now < r.flashUntil
            drawSoldier(soldier, lookFor(r.player.team), faceTexture(remoteFace(r.player)), r.x, r.z, r.heading, r.y, r.player.prone && !r.player.dead)
            flashing = false
        }
        drawBullets()
        drawMuzzleFlashes(now)

        // Transparent labels last so they blend over everything behind them.
        for (visual in dropVisuals.values) drawDropLabels(visual)
        for (r in remotes.values) {
            val saying = if (now < r.sayUntil && !r.player.dead) r.saying else null
            drawLabels(r.x, r.z, r.player.name, saying, r.player.health, r.player.team, labelLift(r.y, r.player.prone))
        }
        if (!fp) drawLabels(playerX, playerZ, playerName, if (now < speechUntil && !isDown) speech else null, health, playerTeam, labelLift(playerY, prone))
        if (fp && !isDown) drawHeldRifle(now)
    }

    // ---- Aiming -------------------------------------------------------------------------------

    /**
     * Where the centre of the screen points: follows the camera's line of sight to the first
     * wall, the ground, or an enemy's body (then the crosshair turns red), up to [AIM_RANGE].
     * It starts just past the player so your own soldier never blocks the aim.
     */
    private fun updateAim() {
        val ox = eyeX; val oy = eyeY; val oz = eyeZ
        var t = if (firstPerson) 0.3f else hypot(playerX - ox, playerZ - oz) + 0.8f
        var onTarget = false
        while (t < AIM_RANGE) {
            val x = ox + camFx * t
            val y = oy + camFy * t
            val z = oz + camFz * t
            if (y <= 0f || city.isInsideBuilding(x, y, z, 0f)) break
            if (remotes.values.any { isEnemy(it) && hitsBody(it, x, y, z) }) { onTarget = true; break }
            t += BULLET_STEP
        }
        aimOnTarget = onTarget
        aimX = ox + camFx * t
        aimY = (oy + camFy * t).coerceAtLeast(0f)
        aimZ = oz + camFz * t
    }

    /**
     * Whether a point is inside another player's body: a standing (or jumping) body is an upright
     * cylinder; a crawling one lies flat along the ground in the direction they face.
     */
    private fun hitsBody(r: RemoteAvatar, x: Float, y: Float, z: Float): Boolean {
        if (r.player.prone) {
            if (y > r.y + PRONE_BODY_HEIGHT) return false
            val hx = r.x + sin(r.heading) * PRONE_BODY_LENGTH
            val hz = r.z - cos(r.heading) * PRONE_BODY_LENGTH
            return CityMap.segmentDistance(x, z, r.x, r.z, hx, hz) < HIT_RADIUS * 0.8f
        }
        if (y < r.y || y > r.y + BODY_HEIGHT) return false
        return hypot(r.x - x, r.z - z) < HIT_RADIUS
    }

    /** Turns [part] so its local z axis points along (dx, dy, dz). */
    private fun orientAlong(dx: Float, dy: Float, dz: Float) {
        val flat = hypot(dx, dz)
        Matrix.rotateM(part, 0, -deg(atan2(dx, -dz)), 0f, 1f, 0f)
        Matrix.rotateM(part, 0, deg(atan2(dy, flat)), 1f, 0f, 0f)
    }

    /**
     * First person: the rifle held at the bottom right of the view, drawn over everything so it
     * never pokes into walls. It kicks back briefly on each shot and bobs gently while moving.
     */
    private fun drawHeldRifle(now: Long) {
        Matrix.invertM(cameraToWorld, 0, view, 0)
        val sinceShot = (now - lastShotAt) / 1000f
        val kick = if (sinceShot < 0.12f) (0.12f - sinceShot) / 0.12f * 0.06f else 0f
        val bob = if (isWalking) sin(now / 1000f * 9f) * 0.012f else 0f
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        // Camera space: x right, y up, looking down -z.
        Matrix.translateM(base, 0, cameraToWorld, 0, 0.24f, -0.25f + bob, -0.55f + kick)
        Matrix.scaleM(base, 0, 0.75f, 0.75f, 0.75f)
        partBox(0f, 0f, 0f, 0.06f, 0.09f, 0.42f, GUN_COLOR)                   // body
        partBox(0f, 0.02f, -0.32f, 0.03f, 0.03f, 0.26f, GUN_COLOR)            // barrel
        partBox(0f, -0.09f, -0.04f, 0.045f, 0.13f, 0.06f, GUN_COLOR)          // magazine
        partBox(0f, 0.065f, 0.02f, 0.02f, 0.04f, 0.12f, GUN_COLOR)            // sight
        partBox(0.01f, -0.05f, 0.14f, 0.09f, 0.08f, 0.12f, 0xFFC9A07E.toInt()) // hand
        if (sinceShot < MUZZLE_FLASH_MS / 1000f) {
            partBox(0f, 0.02f, -0.5f, 0.09f, 0.09f, 0.12f, 0xEEFFF59D.toInt())
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
        pendingRespawn?.let { (x, z) ->
            pendingRespawn = null
            playerX = x
            playerZ = z
            bullets.clear()
        }
        while (true) {
            val s = remoteShots.poll() ?: break
            bullets += Bullet(s[0], s[1], s[2], s[3], s[4], s[5], mine = false)
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
                r.x = p.x; r.z = p.z
            } else {
                val nx = r.x + (p.x - r.x) * blend
                val nz = r.z + (p.z - r.z) * blend
                r.x = nx; r.z = nz
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

    /** Fires one bullet from the rifle's muzzle towards the point under the crosshair. */
    private fun fire() {
        // From the rifle's muzzle when it has been drawn, else from where it would be.
        val x = if (muzzleKnown) muzzle[0] else muzzleX()
        val y = if (muzzleKnown) muzzle[1] else muzzleY()
        val z = if (muzzleKnown) muzzle[2] else muzzleZ()
        var dx = aimX - x; var dy = aimY - y; var dz = aimZ - z
        var len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1.5f) {
            // Aim point right in front of the gun (e.g. a wall): just fire where the camera looks.
            dx = camFx; dy = camFy; dz = camFz; len = 1f
        }
        dx /= len; dy /= len; dz /= len
        bullets += Bullet(x, y, z, dx, dy, dz, mine = true)
        lastShotAt = SystemClock.uptimeMillis()
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
            var remaining = BULLET_SPEED * dt
            var spent = false
            while (remaining > 0f && !spent) {
                val s = min(BULLET_STEP, remaining)
                remaining -= s
                b.x += b.dx * s
                b.y += b.dy * s
                b.z += b.dz * s
                b.travelled += s
                if (b.travelled > BULLET_RANGE || b.y <= 0f || city.isInsideBuilding(b.x, b.y, b.z, 0f)) {
                    spent = true
                } else if (b.mine) {
                    val target = remotes.values.firstOrNull { isEnemy(it) && hitsBody(it, b.x, b.y, b.z) }
                    if (target != null) {
                        target.flashUntil = now + 160
                        val uid = target.player.uid
                        mainHandler.post { onHitPlayer(uid) }
                        spent = true
                    }
                } else if (!down && hypot(playerX - b.x, playerZ - b.z) < HIT_RADIUS && b.y < playerY + BODY_HEIGHT) {
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

    /**
     * Picks and advances each soldier's animation from how they're moving. Soldiers far away or
     * well behind the camera keep their clock running but aren't re-posed (saves battery).
     */
    private fun updateSoldiers(dt: Float) {
        if (rig == null && soldierSource.isDone) {
            rig = runCatching { soldierSource.get() }.getOrNull()
        }
        val rig = rig ?: return
        if (dt <= 0f) return
        val player = playerSoldier ?: SoldierAnimator(rig).also { playerSoldier = it }
        val statue = statueSoldier ?: SoldierAnimator(rig).also { statueSoldier = it }

        // My speed and direction come from how far I actually moved (walls stop you).
        val vx = (playerX - lastPlayerX) / dt
        val vz = (playerZ - lastPlayerZ) / dt
        lastPlayerX = playerX
        lastPlayerZ = playerZ
        playerSpeed += (hypot(vx, vz) - playerSpeed) * min(1f, dt * 10f)
        player.update(
            dt, playerSpeed, SoldierAnimator.relativeAngle(vx, vz, heading), aimTime > 0f, down, skin = true,
            prone = prone, airborne = playerY > 0.05f,
        )

        val now = SystemClock.uptimeMillis()
        for (r in remotes.values) {
            val soldier = r.soldier ?: SoldierAnimator(rig).also { r.soldier = it }
            val rvx = (r.x - r.lastX) / dt
            val rvz = (r.z - r.lastZ) / dt
            r.lastX = r.x
            r.lastZ = r.z
            r.speed += (hypot(rvx, rvz) - r.speed) * min(1f, dt * 8f)
            soldier.update(
                dt, r.speed, SoldierAnimator.relativeAngle(rvx, rvz, r.heading),
                aiming = now < (remoteAiming[r.player.uid] ?: 0L), dead = r.player.dead, skin = isWorthAnimating(r.x, r.z),
                prone = r.player.prone, airborne = r.y > 0.05f,
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
        if (d > SOLDIER_DRAW_DISTANCE) return false
        return d < 8f || dx * sin(yaw) - dz * cos(yaw) > -4f
    }

    private fun update(dt: Float) {
        synchronized(lookLock) {
            yaw += pendingYaw
            if (firstPerson) lookPitch = (lookPitch + pendingPitch).coerceIn(-1.1f, 1.1f)
            // Negative pitch drops the camera low behind you so you can aim up at rooftops.
            else pitch = (pitch + pendingPitch).coerceIn(-0.5f, 1.2f)
            pendingYaw = 0f
            pendingPitch = 0f
        }

        val isDown = down
        val jx = if (isDown) 0f else joyX
        val jy = if (isDown) 0f else joyY
        val amount = min(1f, hypot(jx, jy))
        walking = amount > 0.12f
        isWalking = walking
        updateRemotes(dt)
        updateBullets(dt)

        fireCooldown -= dt
        aimTime -= dt
        if (triggerHeld && !isDown && fireCooldown <= 0f) {
            fireCooldown = FIRE_INTERVAL
            fire()
        }

        // Jumping: a push upwards, then gravity until the feet are back on the ground.
        val crawling = prone && !isDown
        if (jumpRequested) {
            jumpRequested = false
            if (playerY <= 0f && !crawling && !isDown) {
                vy = JUMP_SPEED
                jumpSeq++
            }
        }
        if (playerY > 0f || vy > 0f) {
            vy -= GRAVITY * dt
            playerY = (playerY + vy * dt).coerceAtLeast(0f)
            if (playerY <= 0f) vy = 0f
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
            val step = speed * dt
            // Slide along walls: try each axis on its own.
            if (!city.isBlocked(playerX + dx * step, playerZ, BODY_RADIUS)) playerX += dx * step
            if (!city.isBlocked(playerX, playerZ + dz * step, BODY_RADIUS)) playerZ += dz * step
        } else {
            idleTime += dt
            // Standing still for a moment: turn round to face the camera so the face shows.
            if (idleTime > 1.5f && aimTime <= 0f && !isDown && !firstPerson) {
                heading = approachAngle(heading, yaw + PI.toFloat(), 2.5f * dt)
            }
        }
        // Shooting turns the character to aim along the camera, even while strafing; in first
        // person the soldier always faces where you look (so others see where you aim).
        if (aimTime > 0f || firstPerson) heading = yaw
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
        if (firstPerson) {
            // Through the soldier's eyes; on the ground when killed.
            eyeX = playerX + sin(yaw) * 0.15f
            eyeY = playerY + if (down) 0.35f else if (prone) PRONE_EYE_HEIGHT else EYE_HEIGHT
            eyeZ = playerZ - cos(yaw) * 0.15f
            camFx = sin(yaw) * cos(lookPitch)
            camFy = -sin(lookPitch)
            camFz = -cos(yaw) * cos(lookPitch)
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
        var t = 0.6f
        while (t < CAMERA_DISTANCE) {
            val px = lookX - sin(yaw) * cos(pitch) * t
            val py = targetY + sin(pitch) * t
            val pz = lookZ + cos(yaw) * cos(pitch) * t
            if (city.isInsideBuilding(px, py, pz, 0.35f)) {
                distance = (t - 0.35f).coerceAtLeast(0.6f)
                break
            }
            t += 0.25f
        }
        eyeX = lookX - sin(yaw) * cos(pitch) * distance
        eyeY = (targetY + sin(pitch) * distance).coerceAtLeast(0.3f)
        eyeZ = lookZ + cos(yaw) * cos(pitch) * distance
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
            if (sea) water.floor(ax, az, bx, bz, 0.012f) else land.floor(ax, az, bx, bz, 0f)
        land.floor(x0, z0, x1, z1, 0f)
        strip(north, x0, z0 - m, x1, z0)
        strip(south, x0, z1, x1, z1 + m)
        strip(west, x0 - m, z0, x0, z1)
        strip(east, x1, z0, x1 + m, z1)
        strip(north || west, x0 - m, z0 - m, x0, z0)
        strip(north || east, x1, z0 - m, x1 + m, z0)
        strip(south || west, x0 - m, z1, x0, z1 + m)
        strip(south || east, x1, z1, x1 + m, z1 + m)
        ground = land.build()
        openSea = water.build()
        if (city.limited) border = MeshBuilder().apply {
            val t = 0.08f
            val x0p = city.playMinX; val x1p = city.playMaxX; val z0p = city.playMinZ; val z1p = city.playMaxZ
            box(x0p - t, 0f, z0p - t, x1p + t, BORDER_HEIGHT, z0p + t)
            box(x0p - t, 0f, z1p - t, x1p + t, BORDER_HEIGHT, z1p + t)
            box(x0p - t, 0f, z0p - t, x0p + t, BORDER_HEIGHT, z1p + t)
            box(x1p - t, 0f, z0p - t, x1p + t, BORDER_HEIGHT, z1p + t)
        }.build()
        cube = MeshBuilder().apply { box(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f) }.build()
        quad = MeshBuilder().apply {
            quad(
                floatArrayOf(-0.5f, -0.5f, 0f), floatArrayOf(0.5f, -0.5f, 0f),
                floatArrayOf(0.5f, 0.5f, 0f), floatArrayOf(-0.5f, 0.5f, 0f),
                0f, 0f, 1f,
            )
        }.build()
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
            val maxHeight = b.height * 0.9f - 0.4f
            if (h > maxHeight) { h = maxHeight; w = h * aspect }
            val cx = (ax + bx) / 2f + nx * 0.05f
            val cz = (az + bz) / 2f + nz * 0.05f
            val y0 = 0.4f
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
        draw(ground, identity, GROUND_COLOR)
        draw(openSea, identity, Surface.SEA.color)
        for ((surface, mesh) in alwaysMeshes) drawSurface(surface, mesh)
        val fx = sin(yaw)
        val fz = -cos(yaw)
        for (tile in tileMeshes) {
            val dx = tile.centerX - playerX
            val dz = tile.centerZ - playerZ
            val distance = hypot(dx, dz)
            if (distance > DRAW_DISTANCE) continue
            // Skip tiles well behind the camera (the tile half-diagonal is ~0.71 × its size).
            if (distance > CityScene.TILE && dx * fx + dz * fz < -CityScene.TILE * 0.75f) continue
            for ((surface, mesh) in tile.meshes) drawSurface(surface, mesh)
        }
        for ((mesh, texture) in murals) draw(mesh, identity, WHITE, texture)
        border?.let {
            // Drawn without writing depth so what is behind it still shows through.
            GLES20.glDepthMask(false)
            draw(it, identity, BORDER_COLOR, lit = false)
            GLES20.glDepthMask(true)
        }
    }

    private fun drawSurface(surface: Surface, mesh: Mesh) {
        val texture = if (surface.wallStyle >= 0) facadeTextures[surface.wallStyle] else 0
        draw(mesh, identity, surface.color, texture, surface.lit)
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
        Matrix.translateM(base, 0, drop.x, 0f, drop.z)
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
        drawSoldier(statue, lookFor(drop.team), faceTexture(dropFace(drop)), sx, sz, drop.yaw + PI.toFloat())
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
        drawLabels(sx, sz, visual.drop.author, visual.drop.caption.ifBlank { null })
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
        y: Float = 0f, prone: Boolean = false,
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
            rig.model.images.map { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(::uploadAndRecycle) ?: 0
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
        if (look.badgeTexture != 0) {
            for ((k, arm) in rig.badgeBones.withIndex()) {
                if (arm < 0) continue
                anim.pose.nodeMatrix(arm, bone)
                Matrix.multiplyMM(boneWorld, 0, base, 0, bone, 0)
                Matrix.multiplyMM(part, 0, boneWorld, 0, rig.badgeAnchors[k], 0)
                draw(quad, part, WHITE, look.badgeTexture, lit = false)
            }
        }

        // Rifle: held at the right hand, pointing where the soldier faces (+z in model space).
        // Sizes are in metres, times the model's units per metre.
        if (rig.wrist < 0) return
        anim.pose.nodeMatrix(rig.wrist, bone)
        val wx = bone[12]; val wy = bone[13]; val wz = bone[14]
        val u = rig.unit
        // Standing, the rifle points along model +z with +y up; lying flat (the model tipped
        // forwards) it points along +y with -z up, so it lies ahead of the soldier, not in the ground.
        val flat = prone && anim.lieDownForCrawl
        fun gunBox(up: Float, fwd: Float, width: Float, height: Float, length: Float, color: Int) =
            if (flat) partBox(wx, wy + fwd * u, wz - up * u, width * u, length * u, height * u, color)
            else partBox(wx, wy + up * u, wz + fwd * u, width * u, height * u, length * u, color)
        gunBox(0.02f, 0.12f, 0.06f, 0.09f, 0.42f, GUN_COLOR)             // body
        gunBox(0.04f, 0.44f, 0.03f, 0.03f, 0.26f, GUN_COLOR)             // barrel
        gunBox(-0.07f, 0.16f, 0.045f, 0.13f, 0.06f, GUN_COLOR)           // magazine
        gunBox(-0.01f, -0.16f, 0.05f, 0.08f, 0.18f, 0xFF4E3B2A.toInt())  // stock
        if (anim === playerSoldier) {
            if (flat) Mat.transformPoint(base, wx, wy + 0.6f * u, wz - 0.04f * u, muzzle)
            else Mat.transformPoint(base, wx, wy + 0.04f * u, wz + 0.6f * u, muzzle)
            muzzleKnown = true
        }
    }

    /** Colour of a soldier material: uniform and gear follow the team, the rest looks natural. */
    private fun materialColor(role: MaterialRole, own: Int, look: SoldierLook): Int = when (role) {
        MaterialRole.UNIFORM -> look.uniform
        MaterialRole.GEAR -> look.gear
        MaterialRole.DARK -> 0xFF1E1E1E.toInt()
        MaterialRole.SKIN -> 0xFFC9A07E.toInt()
        MaterialRole.HAIR -> 0xFF3B2A1C.toInt()
        MaterialRole.BROWN -> 0xFF4A3322.toInt()
        MaterialRole.OWN -> own
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

    private fun draw(mesh: Mesh, model: FloatArray, color: Int, texture: Int = 0, lit: Boolean = true) {
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
        mesh.draw(shader)
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
        private const val PRONE_BODY_LENGTH = 1.6f
        /** How high the lying body is lifted so it rests on the ground rather than in it. */
        private const val PRONE_LIFT = 0.16f
        private const val PRONE_GUN_HEIGHT = 0.3f
        private const val CRAWL_SPEED = 1.4f
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
        private const val WALL_STYLES = 5
        private const val GROUND_COLOR = 0xFFCDC6B8.toInt()
        private const val FOG_START = 70f
        private const val FOG_END = 230f
        /** City tiles further than this from the player are not drawn (they'd be lost in the fog). */
        private const val DRAW_DISTANCE = 300f
        /** The play-area wall: 3 m of see-through red. */
        private const val BORDER_HEIGHT = 3f
        private const val BORDER_COLOR = 0x55FF3B30

        /** Hits it takes to kill a player. */
        const val MAX_HEALTH = 5
        private const val FIRE_INTERVAL = 0.25f
        private const val BULLET_SPEED = 45f
        private const val BULLET_RANGE = 70f
        private const val BULLET_STEP = 0.4f
        /** Bullets fly at gun height (the raised arm is at shoulder height). */
        private const val BULLET_Y = 1.5f
        private const val MUZZLE_FORWARD = 0.95f
        private const val MUZZLE_RIGHT = 0.32f
        private const val MUZZLE_FLASH_MS = 60L
        private const val AIM_RANGE = 120f
        private const val GUN_COLOR = 0xFF263238.toInt()
        private const val HIT_RADIUS = 0.5f
        private const val FLAG_WIDTH = 0.42f
        private const val HEALTH_BAR_WIDTH = 0.8f
        private const val HEALTH_BAR_HEIGHT = 0.09f
        private const val MAX_MURALS = 16
        private const val MAX_LABELS = 64
        private const val BUBBLE_UNITS_PER_PX = 1f / 190f
        private const val NAME_UNITS_PER_PX = 1f / 170f
    }
}
