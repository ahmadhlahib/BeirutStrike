package com.example.beirutrun

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.beirutrun.city.CityMap
import com.example.beirutrun.city.CityMapInfo
import com.example.beirutrun.city.CityMaps
import com.example.beirutrun.city.CityRenderer
import com.example.beirutrun.city.CityScene
import com.example.beirutrun.city.DepthConfigChooser
import com.example.beirutrun.city.JoystickView
import com.example.beirutrun.city.MiniMapView
import com.example.beirutrun.city.Pickup
import com.example.beirutrun.city.PickupKind
import com.example.beirutrun.city.Weapon
import com.example.beirutrun.city.SoundEffects
import com.example.beirutrun.city.SoldierRig
import com.example.beirutrun.online.FirebaseSession
import com.example.beirutrun.online.OnlineWorld
import com.example.beirutrun.online.PlayerStats
import com.example.beirutrun.online.RemotePlayer
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Walk the 3D city, say things, and drop photos. With Firebase set up, everyone playing sees each
 * other walking around, their speech bubbles and their photos; without it, the city is local.
 */
class CityActivity : AppCompatActivity(), OnlineWorld.Listener {

    private lateinit var repo: WorldRepository
    private lateinit var online: OnlineWorld
    private lateinit var city: CityMap
    private lateinit var mapInfo: CityMapInfo
    /** Side of the play area in metres; null = the whole map. */
    private var mapSize: Int? = null
    private lateinit var renderer: CityRenderer
    private lateinit var glView: GLSurfaceView
    private lateinit var miniMap: MiniMapView
    private lateinit var statusLabel: TextView
    private lateinit var viewPhotoButton: MaterialButton
    private lateinit var playerName: String
    private lateinit var playerTeam: Team
    private var drops: MutableList<PhotoDrop> = mutableListOf()
    private var playerCount = 0
    private var status = OnlineWorld.Status.CONNECTING
    private var nearby: PhotoDrop? = null
    /** Set when the player chose to leave the room (so an empty room gets deleted). */
    private var leavingRoom = false

    private lateinit var heartsLabel: TextView
    private lateinit var banner: TextView
    private lateinit var damageFlash: View
    private lateinit var crosshair: ImageView
    private var crosshairOnTarget = false
    private lateinit var crawlButton: MaterialButton
    private lateinit var sounds: SoundEffects
    private var health = CityRenderer.MAX_HEALTH
    private var dead = false

    /** The room's game: when it ends (server ms, 0 = no limit or not started yet) and how long it lasts. */
    private var gameEndsAt = 0L
    private var gameDurationMs = 0L
    /** Time's up: no more shooting or scoring, the results are on screen. */
    private var gameOver = false
    private lateinit var gameTimer: TextView
    private lateinit var shootButton: View
    private lateinit var scoreboard: Scoreboard
    private var stats: List<PlayerStats> = emptyList()

    private lateinit var weaponButton: MaterialButton
    private lateinit var scopeButton: MaterialButton
    private lateinit var scopeOverlay: View
    /** Found a scope (lost again on dying): the AK-47 can zoom in. */
    private var hasScope = false
    /** Ammo packs and scopes in the street (shared online; this phone's own when offline). */
    private var pickups: List<Pickup> = emptyList()
    /** Pickup slots I'm trying to take right now (waiting for the server). */
    private val taking = HashSet<Int>()
    private val respawn = Runnable { respawn() }
    private val hideBanner = Runnable { banner.animate().alpha(0f).setDuration(400).start() }

    private val ticker = Handler(Looper.getMainLooper())
    private val sceneBuilder = Executors.newSingleThreadExecutor()
    private var ticks = 0
    private val tick = object : Runnable {
        override fun run() {
            online.updatePose(renderer.playerX, renderer.playerZ, renderer.heading, renderer.isWalking, renderer.prone, renderer.jumpSeq)
            updateCrosshair()
            updateGameTimer()
            updateWeaponButtons()
            checkPickups()
            // Now and then, re-check who is still around (hides players whose phone went quiet).
            if (++ticks % 25 == 0) online.publishPlayers()
            ticker.postDelayed(this, POSE_INTERVAL_MS)
        }
    }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) importAndShowDropDialog()
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(this, R.string.photo_permission_denied, Toast.LENGTH_LONG).show()
    }

    private val takeFace = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            renderer.reloadFaces()
            online.uploadFace()
        }
    }

    private val pickStreets = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_STREETS)
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val added = uris.count { uri -> runCatching { repo.addStreet(uri) }.isSuccess }
        Toast.makeText(this, getString(R.string.streets_added, added), Toast.LENGTH_SHORT).show()
        // The murals are built with the world, so rebuild it.
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = Session.name(this)
        if (name == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        // Online play needs a room, and everyone needs a team; send the player back to choose.
        val roomId = Session.roomId(this)
        val team = Teams.byId(Session.teamId(this))
        val missing = when {
            FirebaseSession.configured(this) && roomId == null -> RoomsActivity::class.java
            team == null -> TeamSelectActivity::class.java
            else -> null
        }
        if (missing != null) {
            startActivity(Intent(this, missing))
            finish()
            return
        }
        playerName = name
        playerTeam = team!!
        setContentView(R.layout.activity_city)
        hideSystemBars()

        repo = WorldRepository(this)
        sounds = SoundEffects(this)
        online = OnlineWorld(applicationContext, repo, roomId, playerTeam.id)
        online.listener = this
        // The real Downtown Beirut (OpenStreetMap); its 3D geometry is built in the background.
        // The room's map; Downtown if this build doesn't have it (e.g. an older app version).
        mapInfo = CityMaps.byId(Session.roomMap(this))
        city = runCatching { mapInfo.load(this) }.getOrElse {
            mapInfo = CityMaps.default
            mapInfo.load(this)
        }
        // The room's map size: players stay in a square that big around the start point.
        mapSize = CityMaps.sizeOf(Session.roomMap(this))
        mapSize?.let { city.limitTo(it.toFloat()) }
        val scene = sceneBuilder.submit(Callable { CityScene.build(city, mapInfo.look) })
        // The animated soldier everyone plays as, dressed in their team's uniform.
        // (The Mixamo soldier in assets/models/mixamo/ if present, otherwise the built-in one.)
        val soldier = sceneBuilder.submit(Callable {
            SoldierRig.load { path -> runCatching { assets.open(path).use { it.readBytes() } }.getOrNull() }
        })
        // Offline, drops live on this phone. Online, they come from Firebase (see onDrops);
        // anything saved here meanwhile is waiting to be uploaded, so show it too.
        drops = repo.drops()

        val myFace = Session.faceFile(this)
        renderer = CityRenderer(
            city = city,
            sceneSource = scene,
            repo = repo,
            playerName = playerName,
            soldierSource = soldier,
            // Where you last stood on this map, or its start point facing its view.
            start = Session.position(this, mapInfo.id) ?: Triple(city.spawnX, city.spawnZ, mapInfo.startYaw),
            streetPhotos = repo.streets(),
            playerFace = { myFace },
            dropFace = ::faceFileFor,
            remoteFace = { online.remoteFaceFile(it.uid) },
            onNearbyDrop = ::onNearbyDrop,
            onShot = { x, y, z, dx, dy, dz ->
                sounds.shoot(weapon = renderer.weapon)
                online.sendShot(x, y, z, dx, dy, dz)
                if (!gameOver) online.countShot()
            },
            onHitPlayer = { uid ->
                sounds.ouch()
                online.sendHit(uid)
                if (!gameOver) online.countHit()
            },
            playerTeam = playerTeam.id,
            teamFlag = { id -> Teams.byId(id)?.let { TeamFlags.load(applicationContext, it) } },
            teamColor = { id -> Teams.byId(id)?.color },
            teamUniform = { id -> Teams.byId(id)?.let { it.uniform to it.gear } },
            onOutOfAmmo = { gun -> showBanner(getString(R.string.out_of_ammo, weaponName(gun))) },
            pickupLabel = ::pickupLabel,
        )
        renderer.setDrops(drops)

        glView = findViewById(R.id.glView)
        glView.setEGLContextClientVersion(2)
        glView.setEGLConfigChooser(DepthConfigChooser())
        glView.preserveEGLContextOnPause = true
        glView.setRenderer(renderer)
        bindLookAround()

        findViewById<JoystickView>(R.id.joystick).onMove = { x, y ->
            renderer.joyX = x
            renderer.joyY = y
        }

        miniMap = findViewById(R.id.miniMap)
        miniMap.city = city
        miniMap.renderer = renderer
        miniMap.drops = drops.toList()
        miniMap.teamColor = { id -> Teams.byId(id)?.color }
        miniMap.setOnClickListener { showFullMap() }

        statusLabel = findViewById(R.id.statusLabel)
        findViewById<MaterialButton>(R.id.playerButton).apply {
            text = getString(R.string.player_and_team, playerName, playerTeam.name)
            icon = flagIcon(playerTeam)
            iconTint = null
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { showPlayerMenu() }
        }
        findViewById<MaterialButton>(R.id.sayButton).setOnClickListener { showSayDialog() }
        findViewById<MaterialButton>(R.id.viewModeButton).apply {
            renderer.firstPerson = Session.firstPerson(this@CityActivity)
            fun label() = setText(if (renderer.firstPerson) R.string.view_first_person else R.string.view_third_person)
            label()
            setOnClickListener {
                renderer.firstPerson = !renderer.firstPerson
                Session.setFirstPerson(this@CityActivity, renderer.firstPerson)
                label()
            }
        }
        shootButton = findViewById(R.id.shootButton)
        bindShootButton(shootButton)
        weaponButton = findViewById(R.id.weaponButton)
        weaponButton.setOnClickListener { switchWeapon() }
        scopeButton = findViewById(R.id.scopeButton)
        scopeButton.setOnClickListener { setScoped(!renderer.scoped) }
        scopeOverlay = findViewById(R.id.scopeOverlay)
        online.setWeapon(renderer.weapon.id)
        online.pickupSpot = { city.randomStreetPoint() }
        if (!online.configured) scatterLocalPickups()
        updateWeaponButtons()
        gameTimer = findViewById(R.id.gameTimer)
        gameTimer.setOnClickListener { showScoreboard() }
        scoreboard = Scoreboard(
            this,
            myUid = { online.uid },
            onLeave = { leaveRoom() },
            onNewRoom = { leaveRoom(createNext = true) },
            onRanking = ::showRanking,
        )
        crosshair = findViewById(R.id.crosshair)
        findViewById<MaterialButton>(R.id.jumpButton).setOnClickListener { if (!dead) renderer.jump() }
        crawlButton = findViewById(R.id.crawlButton)
        crawlButton.setOnClickListener { setProne(!renderer.prone) }
        heartsLabel = findViewById(R.id.hearts)
        banner = findViewById(R.id.banner)
        damageFlash = findViewById(R.id.damageFlash)
        updateHearts()
        findViewById<MaterialButton>(R.id.dropButton).setOnClickListener { takeDropPhoto() }
        viewPhotoButton = findViewById(R.id.viewPhotoButton)
        viewPhotoButton.setOnClickListener { nearby?.let(::showPhotoDialog) }

        online.start(playerName)
    }

    override fun onResume() {
        super.onResume()
        if (!::glView.isInitialized) return
        glView.onResume()
        online.resume()
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        if (!::glView.isInitialized) return
        ticker.removeCallbacks(tick)
        glView.onPause()
        online.pause()
        Session.savePosition(this, mapInfo.id, renderer.playerX, renderer.playerZ, renderer.yaw)
    }

    override fun onDestroy() {
        super.onDestroy()
        ticker.removeCallbacks(respawn)
        sceneBuilder.shutdown()
        ticker.removeCallbacks(hideBanner)
        // Also offline pickups waiting to come back.
        ticker.removeCallbacksAndMessages(null)
        if (::scoreboard.isInitialized) scoreboard.dismiss()
        // Only "Leave room" and "Log out" delete an empty room here; if the app is just closed,
        // the room list cleans it up after a few minutes.
        if (::online.isInitialized) online.stop(leavingRoom)
        if (::sounds.isInitialized) sounds.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Dragging on the city (outside the joystick and buttons) turns the camera. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindLookAround() {
        var lastX = 0f
        var lastY = 0f
        glView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
                MotionEvent.ACTION_MOVE -> {
                    renderer.look(event.x - lastX, event.y - lastY)
                    lastX = event.x
                    lastY = event.y
                }
            }
            true
        }
    }

    /** The team flag shrunk to button-icon size (flag images can be any size, even thousands of pixels). */
    private fun flagIcon(team: Team): BitmapDrawable {
        val flag = TeamFlags.load(this, team)
        val height = (FLAG_ICON_DP * resources.displayMetrics.density).toInt()
        val width = (height * flag.width / flag.height.toFloat()).toInt().coerceIn(height, height * 2)
        val scaled = Bitmap.createScaledBitmap(flag, width, height, true)
        if (scaled != flag) flag.recycle()
        // Pixels are already device-sized; stop Android scaling them again.
        scaled.density = resources.displayMetrics.densityDpi
        return BitmapDrawable(resources, scaled)
    }

    /**
     * My own drops use my local face; other players' drops use the face downloaded for them.
     * Called by the renderer every frame, so only builds file paths (no disk access).
     */
    private fun faceFileFor(drop: PhotoDrop): File {
        val me = online.uid
        return if (drop.authorId.isNotEmpty() && drop.authorId != me) online.remoteFaceFile(drop.authorId)
        else Session.faceFileFor(applicationContext, drop.author)
    }

    private fun isMine(drop: PhotoDrop) =
        drop.author == playerName && (drop.authorId.isEmpty() || drop.authorId == online.uid)

    private fun onNearbyDrop(drop: PhotoDrop?) {
        nearby = drop
        viewPhotoButton.visibility = if (drop != null) View.VISIBLE else View.GONE
        if (drop != null) viewPhotoButton.text = getString(R.string.view_photo_by, drop.author)
    }

    private fun showDrops(list: List<PhotoDrop>) {
        drops = list.toMutableList()
        renderer.setDrops(drops)
        miniMap.drops = drops.toList()
        fullMap?.drops = miniMap.drops
    }

    // ---- OnlineWorld.Listener -----------------------------------------------------------------

    override fun onStatus(status: OnlineWorld.Status) {
        this.status = status
        updateStatusLabel()
    }

    override fun onDrops(drops: List<PhotoDrop>) {
        sharedDrops = drops
        // Shared drops (minus those by players I blocked), plus any of mine still waiting to upload.
        val shown = drops.filterNot { Blocklist.isBlocked(this, it.authorId) }
        val waiting = repo.drops().filter { local -> drops.none { it.id == local.id } }
        showDrops(shown + waiting)
    }

    override fun onPlayers(players: List<RemotePlayer>) {
        remotePlayers = players
        playerCount = players.size
        // Players I blocked still walk around (they're in the game), but their words don't show.
        val shown = players.map { if (Blocklist.isBlocked(this, it.uid)) it.copy(say = "") else it }
        renderer.setRemotePlayers(shown)
        miniMap.players = shown
        fullMap?.players = shown
        updateStatusLabel()
    }

    /** The latest drops and players from the server, before blocked players are filtered out. */
    private var sharedDrops: List<PhotoDrop> = emptyList()
    private var remotePlayers: List<RemotePlayer> = emptyList()

    override fun onFacesChanged() = renderer.reloadFaces()

    override fun onRemoteShot(player: RemotePlayer) {
        renderer.addRemoteShot(player.uid, player.shotX, player.shotY, player.shotZ, player.shotDX, player.shotDY, player.shotDZ)
        // Other players' shots are quieter the further away they are, silent beyond ~60 m.
        val distance = hypot(player.shotX - renderer.playerX, player.shotZ - renderer.playerZ)
        sounds.shoot(volume = 0.8f * (1f - distance / HEARING_RANGE), weapon = Weapon.byId(player.weapon))
    }

    override fun onHitBy(fromUid: String, fromName: String) {
        if (dead || gameOver) return
        online.countHitTaken()
        health -= 1
        renderer.health = health
        updateHearts()
        if (health > 0) sounds.ouch() else sounds.death()
        damageFlash.animate().cancel()
        damageFlash.alpha = 1f
        damageFlash.animate().alpha(0f).setDuration(350).start()
        if (health > 0) {
            online.setHealth(health, false, "")
            return
        }
        // Fifth hit: down for a few seconds, then back at a random crossroads.
        dead = true
        online.countDeath()
        renderer.down = true
        renderer.triggerHeld = false
        // The scope is lost with your life; find another.
        hasScope = false
        setScoped(false)
        online.setHealth(0, true, fromUid)
        showBanner(getString(R.string.killed_by, fromName.ifBlank { getString(R.string.someone) }))
        ticker.postDelayed(respawn, RESPAWN_MS)
    }

    override fun onKilled(victimName: String) {
        if (gameOver) return
        online.countKill()
        sounds.death(volume = 0.8f)
        showBanner(getString(R.string.you_killed, victimName))
    }

    override fun onGameClock(startedAt: Long, durationMs: Long) {
        gameDurationMs = durationMs
        gameEndsAt = if (startedAt > 0 && durationMs > 0) startedAt + durationMs else 0L
        updateGameTimer()
    }

    override fun onStats(stats: List<PlayerStats>) {
        this.stats = stats
        scoreboard.update(stats)
    }

    override fun onCareerScores(scores: Map<String, Int>) = scoreboard.updateCareer(scores)

    override fun onPickups(pickups: List<Pickup>) = showPickups(pickups)

    // ---- Guns and pickups ---------------------------------------------------------------------

    private fun weaponName(gun: Weapon) =
        getString(if (gun == Weapon.PISTOL) R.string.weapon_pistol else R.string.weapon_ak47)

    private fun pickupLabel(kind: PickupKind) = when (kind) {
        PickupKind.SCOPE -> getString(R.string.pickup_scope)
        else -> getString(R.string.pickup_ammo, weaponName(kind.weapon!!), PickupKind.PACK_SIZE)
    }

    /** Pistol ⇄ AK-47. The scope only fits the AK-47, so switching to the pistol lowers it. */
    private fun switchWeapon() {
        val next = if (renderer.weapon == Weapon.AK47) Weapon.PISTOL else Weapon.AK47
        renderer.weapon = next
        renderer.triggerHeld = false
        if (next != Weapon.AK47) setScoped(false)
        online.setWeapon(next.id)
        updateWeaponButtons()
    }

    /** Looks through the scope (AK-47 with a scope only), or back out. */
    private fun setScoped(on: Boolean) {
        val scoped = on && hasScope && renderer.weapon == Weapon.AK47 && !dead && !gameOver
        renderer.scoped = scoped
        scopeOverlay.visibility = if (scoped) View.VISIBLE else View.GONE
        scopeButton.setText(if (scoped) R.string.scope_off else R.string.scope)
        updateCrosshair()
    }

    /** The gun button shows the gun in hand and its bullets (red when empty). */
    private fun updateWeaponButtons() {
        val gun = renderer.weapon
        val left = renderer.ammo(gun)
        val text = getString(R.string.weapon_ammo, weaponName(gun), left)
        if (weaponButton.text.toString() != text) weaponButton.text = text
        weaponButton.setTextColor(if (left > 0) 0xFFFFFFFF.toInt() else 0xFFFF5252.toInt())
        scopeButton.visibility = if (hasScope && gun == Weapon.AK47) View.VISIBLE else View.GONE
    }

    private fun showPickups(list: List<Pickup>) {
        pickups = list
        renderer.setPickups(list)
    }

    /** Walking over a pickup takes it; online the server decides who got there first. */
    private fun checkPickups() {
        if (dead || gameOver) return
        val px = renderer.playerX
        val pz = renderer.playerZ
        val p = pickups.firstOrNull { it.slot !in taking && hypot(it.x - px, it.z - pz) < PICKUP_RADIUS } ?: return
        if (!online.configured) {
            collect(p)
            showPickups(pickups - p)
            ticker.postDelayed({ showPickups(pickups + localPickup(p.slot, p.kind)) }, PickupKind.respawnMs(p.kind))
            return
        }
        taking += p.slot
        online.takePickup(p) { got ->
            taking -= p.slot
            if (got) collect(p)
        }
    }

    private fun collect(p: Pickup) {
        val gun = p.kind.weapon
        if (gun != null) {
            renderer.addAmmo(gun, PickupKind.PACK_SIZE)
            showBanner(getString(R.string.picked_ammo, PickupKind.PACK_SIZE, weaponName(gun)))
        } else {
            hasScope = true
            showBanner(getString(R.string.picked_scope))
        }
        updateWeaponButtons()
    }

    /** Offline there's no one to share with: this phone scatters its own pickups. */
    private fun scatterLocalPickups() =
        showPickups(PickupKind.SLOTS.mapIndexed { slot, kind -> localPickup(slot, kind) })

    private fun localPickup(slot: Int, kind: PickupKind): Pickup {
        val (x, z) = city.randomStreetPoint()
        return Pickup(slot, kind, x, z)
    }

    // ---- Game clock and scoreboard ------------------------------------------------------------

    /** Counts down to the end of the room's game, and ends it when the time is up. */
    private fun updateGameTimer() {
        if (gameOver) return
        if (gameDurationMs <= 0) {
            gameTimer.visibility = View.GONE
            scoreboard.setTimeLeft(null)
            return
        }
        // Until the start time comes back from the server, show the full length.
        val left = if (gameEndsAt > 0) gameEndsAt - online.serverNow() else gameDurationMs
        if (left <= 0) return endGame()
        val text = GameClock.format(left)
        gameTimer.visibility = View.VISIBLE
        gameTimer.text = text
        gameTimer.setTextColor(if (left <= FINAL_SECONDS_MS) 0xFFFF5252.toInt() else 0xFFFFFFFF.toInt())
        scoreboard.setTimeLeft(text)
    }

    /** Time's up: stop the fighting and show everyone the results. */
    private fun endGame() {
        gameOver = true
        renderer.triggerHeld = false
        setScoped(false)
        shootButton.alpha = 0.4f
        gameTimer.visibility = View.VISIBLE
        gameTimer.setText(R.string.score_game_over)
        gameTimer.setTextColor(0xFFFFC107.toInt())
        sounds.death(volume = 0.5f)
        scoreboard.update(stats)
        scoreboard.show(over = true)
    }

    private fun showScoreboard() {
        scoreboard.update(stats)
        scoreboard.show(over = gameOver)
    }

    // ---- Shooting -----------------------------------------------------------------------------

    /** Press to fire, hold to keep firing. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindShootButton(button: View) {
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    if (!dead && !gameOver) {
                        renderer.triggerHeld = true
                        renderer.pullTrigger()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    renderer.triggerHeld = false
                }
            }
            true
        }
    }

    /** The full map open on screen, if any (kept up to date like the minimap). */
    private var fullMap: MiniMapView? = null

    /** Shows the whole play area full screen: streets, its border, players and drops. Tap to close. */
    private fun showFullMap() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_full_map, null)
        val map = view.findViewById<MiniMapView>(R.id.fullMap).apply {
            full = true
            renderer = this@CityActivity.renderer
            players = miniMap.players
            drops = miniMap.drops
            teamColor = miniMap.teamColor
            city = this@CityActivity.city
        }
        val size = mapSize?.let { getString(R.string.map_size_metres, it) } ?: getString(R.string.map_size_full)
        view.findViewById<TextView>(R.id.fullMapTitle).text = getString(R.string.full_map_title, mapInfo.name, size)
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(view)
        view.setOnClickListener { dialog.dismiss() }
        map.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { fullMap = null }
        fullMap = map
        dialog.show()
    }

    private fun respawn() {
        val (x, z) = city.randomStreetPoint()
        renderer.respawn(x, z)
        renderer.refillToStart()
        health = CityRenderer.MAX_HEALTH
        dead = false
        renderer.health = health
        renderer.down = false
        setProne(false)
        online.setHealth(health, false, "")
        updateHearts()
        showBanner(getString(R.string.respawned))
    }

    /** Lie down to crawl, or stand back up; the button shows what tapping it will do. */
    private fun setProne(on: Boolean) {
        renderer.prone = on
        crawlButton.setText(if (on) R.string.stand_up else R.string.crawl)
    }

    /** The centre crosshair turns red while it's over an enemy; hidden when dead or scoped in. */
    private fun updateCrosshair() {
        crosshair.visibility = if (dead || renderer.scoped) View.GONE else View.VISIBLE
        val onTarget = renderer.aimOnTarget
        if (onTarget != crosshairOnTarget) {
            crosshairOnTarget = onTarget
            crosshair.setImageResource(if (onTarget) R.drawable.ic_crosshair_target else R.drawable.ic_crosshair)
        }
    }

    private fun updateHearts() {
        heartsLabel.text = "♥".repeat(health.coerceAtLeast(0)) +
            "♡".repeat((CityRenderer.MAX_HEALTH - health).coerceAtLeast(0))
    }

    private fun showBanner(text: String) {
        ticker.removeCallbacks(hideBanner)
        banner.text = text
        banner.animate().cancel()
        banner.alpha = 1f
        ticker.postDelayed(hideBanner, BANNER_MS)
    }

    private fun updateStatusLabel() {
        val text = when (status) {
            OnlineWorld.Status.NOT_CONFIGURED -> getString(R.string.status_offline)
            OnlineWorld.Status.CONNECTING -> getString(R.string.status_connecting)
            OnlineWorld.Status.FAILED -> getString(R.string.status_failed)
            OnlineWorld.Status.ONLINE ->
                if (playerCount == 0) getString(R.string.status_online_alone)
                else resources.getQuantityString(R.plurals.status_online, playerCount, playerCount)
        }
        val room = Session.roomName(this)
        statusLabel.text = if (room != null) getString(R.string.status_in_room, room, text) else text
    }

    // ---- Dropping photos ----------------------------------------------------------------------

    private fun takeDropPhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val file = repo.captureFile().apply { delete() }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        try {
            takePhoto.launch(uri)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.photo_no_camera, Toast.LENGTH_LONG).show()
        }
    }

    private fun importAndShowDropDialog() {
        val id = UUID.randomUUID().toString()
        Thread {
            val ok = runCatching { repo.importCapture(repo.captureFile(), id) }.getOrDefault(false)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (ok) showDropDialog(id)
                else Toast.makeText(this, R.string.photo_failed, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun showDropDialog(id: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_drop, null)
        view.findViewById<ImageView>(R.id.dropPreview)
            .setImageBitmap(BitmapFactory.decodeFile(repo.photoFile(id).path))
        val captionInput = view.findViewById<EditText>(R.id.captionInput)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.drop_title)
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> repo.photoFile(id).delete() }
            .setPositiveButton(R.string.drop_here) { _, _ ->
                val caption = captionInput.text.toString().trim()
                dropPhoto(id, caption)
            }
            .show()
    }

    private fun dropPhoto(id: String, caption: String) {
        // Stand the photo a couple of steps in front of the player, facing them.
        val yaw = renderer.yaw
        val px = renderer.playerX
        val pz = renderer.playerZ
        val (x, z) = listOf(2.2f, 1.2f)
            .map { d -> (px + sin(yaw) * d) to (pz - cos(yaw) * d) }
            .firstOrNull { (x, z) -> !city.isBlocked(x, z, 0.4f) }
            ?: (px to pz)

        val drop = PhotoDrop(
            id = id, x = x, z = z, yaw = yaw,
            caption = caption,
            author = playerName,
            team = playerTeam.id,
            time = System.currentTimeMillis(),
            authorId = online.uid.orEmpty(),
        )
        // Show it straight away; online it is also shared, and kept here until the upload works.
        showDrops(drops + drop)
        val waiting = repo.drops().apply { add(drop.copy(authorId = "")) }
        repo.saveDrops(waiting)
        online.publishDrop(drop) { ok ->
            if (ok) repo.saveDrops(repo.drops().filterNot { it.id == drop.id })
        }
        if (caption.isNotEmpty()) say(caption)
    }

    private fun showPhotoDialog(drop: PhotoDrop) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_photo, null)
        view.findViewById<ImageView>(R.id.photoImage)
            .setImageBitmap(WorldRepository.decodeScaled(repo.photoFile(drop.id), 1280))
        view.findViewById<TextView>(R.id.photoCaption).apply {
            text = drop.caption
            visibility = if (drop.caption.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.photoMeta).text = getString(
            R.string.photo_meta, drop.author, DateUtils.getRelativeTimeSpanString(drop.time),
        )

        val builder = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton(R.string.close, null)
        if (isMine(drop)) {
            builder.setNeutralButton(R.string.remove_photo) { _, _ ->
                if (drop.authorId.isNotEmpty()) online.deleteDrop(drop)
                val local = repo.drops()
                repo.deleteDrop(local, drop)
                showDrops(drops.filterNot { it.id == drop.id })
            }
        } else if (online.configured && drop.authorId.isNotEmpty()) {
            // Someone else's photo: it can be reported, or its author blocked.
            builder.setNeutralButton(R.string.report) { _, _ ->
                report("photo", drop.authorId, drop.author, "${drop.id} ${drop.caption}")
            }
            builder.setNegativeButton(R.string.block) { _, _ -> confirmBlock(drop.authorId, drop.author) }
        }
        builder.show()
    }

    // ---- Reporting and blocking ---------------------------------------------------------------

    /** The other players in the room, to report or block one of them. */
    private fun showPlayersDialog() {
        val others = remotePlayers.sortedBy { it.name.lowercase() }
        if (others.isEmpty()) {
            Toast.makeText(this, R.string.players_none, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_players)
            .setItems(others.map { it.name }.toTypedArray()) { _, which ->
                val p = others[which]
                MaterialAlertDialogBuilder(this)
                    .setTitle(p.name)
                    .setMessage(if (p.say.isNotBlank()) getString(R.string.player_last_said, p.say) else null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setNeutralButton(R.string.report) { _, _ ->
                        report(if (p.say.isNotBlank()) "message" else "player", p.uid, p.name, p.say)
                    }
                    .setPositiveButton(R.string.block) { _, _ -> confirmBlock(p.uid, p.name) }
                    .show()
            }
            .show()
    }

    private fun report(kind: String, uid: String, name: String, detail: String) {
        online.report(kind, uid, name, detail) { ok ->
            Toast.makeText(this, if (ok) R.string.report_sent else R.string.report_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Blocks [uid] on this phone after asking: their messages and photos stop showing here. */
    private fun confirmBlock(uid: String, name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.block_title, name))
            .setMessage(R.string.block_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.block) { _, _ ->
                Blocklist.block(this, uid)
                onDrops(sharedDrops)
                onPlayers(remotePlayers)
                Toast.makeText(this, getString(R.string.blocked, name), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ---- Deleting my data ---------------------------------------------------------------------

    /**
     * Deletes the player's data everywhere after asking: on the server (face, career, stats,
     * photos, account) and on this phone (name, face photos, drops, street photos, blocklist).
     */
    private fun confirmDeleteData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_data_title)
            .setMessage(R.string.delete_data_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_data_confirm) { _, _ ->
                if (online.configured) online.deleteMyData { ok -> finishDeletingData(ok) }
                else finishDeletingData(true)
            }
            .show()
    }

    private fun finishDeletingData(serverOk: Boolean) {
        leavingRoom = true
        repo.deleteAllPhotos()
        Session.deleteAll(this)
        Blocklist.clear(this)
        Toast.makeText(this, if (serverOk) R.string.delete_data_done else R.string.delete_data_failed, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- Saying things ------------------------------------------------------------------------

    private fun say(text: String) {
        renderer.say(text)
        online.say(text)
    }

    private fun showSayDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_say, null)
        val input = view.findViewById<EditText>(R.id.sayInput)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.say_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.say) { _, _ -> say(input.text.toString().trim()) }
            .show()
        input.requestFocus()
    }

    // ---- Player menu --------------------------------------------------------------------------

    private fun showPlayerMenu() {
        val hasStreets = repo.streets().isNotEmpty()
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        if (online.configured) {
            actions += R.string.menu_scoreboard to { showScoreboard() }
            actions += R.string.menu_players to { showPlayersDialog() }
        }
        actions += listOf<Pair<Int, () -> Unit>>(
            R.string.menu_change_team to {
                startActivity(Intent(this, TeamSelectActivity::class.java))
                finish()
            },
            R.string.face_retake to { takeFace.launch(Intent(this, FaceCaptureActivity::class.java)) },
            R.string.menu_add_streets to {
                pickStreets.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
        if (hasStreets) actions += R.string.menu_remove_streets to {
            repo.removeAllStreets()
            recreate()
        }
        if (FirebaseSession.configured(this)) actions += R.string.menu_leave_room to { leaveRoom() }
        actions += R.string.menu_logout to {
            leavingRoom = true
            Session.logout(this)
            startActivity(Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        actions += R.string.menu_delete_data to { confirmDeleteData() }

        MaterialAlertDialogBuilder(this)
            .setTitle(playerName)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .show()
    }

    /** Back to the room list; with [createNext], it opens the create-room dialog straight away. */
    private fun leaveRoom(createNext: Boolean = false) {
        leavingRoom = true
        Session.setRoom(this, null, null)
        startActivity(Intent(this, RoomsActivity::class.java)
            .putExtra(RoomsActivity.EXTRA_CREATE_ROOM, createNext)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * All players' career ranking, over the city (back returns to it). Only offered once the game
     * is over: while it's open this player vanishes from the others' cities, like in the background.
     */
    private fun showRanking() = startActivity(Intent(this, RankingActivity::class.java))

    companion object {
        /** How close to a pickup the player must walk to take it, metres. */
        private const val PICKUP_RADIUS = 1.6f
        /** The timer turns red for the last half minute. */
        private const val FINAL_SECONDS_MS = 30_000L
        private const val MAX_STREETS = 16
        private const val POSE_INTERVAL_MS = 200L
        private const val RESPAWN_MS = 4_000L
        private const val BANNER_MS = 2_500L
        private const val HEARING_RANGE = 60f
        private const val FLAG_ICON_DP = 22f
    }
}
