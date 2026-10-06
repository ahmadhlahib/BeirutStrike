package com.example.beirutrun

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.example.beirutrun.city.CarModels
import com.example.beirutrun.city.CityMap
import com.example.beirutrun.city.CityMapInfo
import com.example.beirutrun.city.CityMaps
import com.example.beirutrun.city.CityRenderer
import com.example.beirutrun.city.CityScene
import com.example.beirutrun.city.DepthConfigChooser
import com.example.beirutrun.city.EnemyAreas
import com.example.beirutrun.city.JoystickView
import com.example.beirutrun.city.MiniMapView
import com.example.beirutrun.city.Pickup
import com.example.beirutrun.city.PickupKind
import com.example.beirutrun.city.Characters
import com.example.beirutrun.city.GunMeshes
import com.example.beirutrun.city.GrenadeKind
import com.example.beirutrun.city.GunSlot
import com.example.beirutrun.city.Weapon
import com.example.beirutrun.city.SoundEffects
import com.example.beirutrun.city.SoldierRig
import com.example.beirutrun.online.CareerWallet
import com.example.beirutrun.online.FirebaseSession
import com.example.beirutrun.online.OnlineWorld
import com.example.beirutrun.online.PlayerStats
import com.example.beirutrun.online.RemotePlayer
import com.example.beirutrun.online.RoomTeams
import com.example.beirutrun.online.VoiceChat
import com.example.beirutrun.progression.PlayerProgress
import com.example.beirutrun.progression.StoreItem
import com.example.beirutrun.progression.XpGain
import com.example.beirutrun.progression.CashReward
import com.example.beirutrun.progression.Wallet
import com.example.beirutrun.progression.XpReward
import com.example.beirutrun.solo.BotDifficulty
import com.example.beirutrun.solo.SoloMatch
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
    /** A team was added to the room: its players now get their flag, colour and name. */
    private val onTeamsChanged: () -> Unit = {
        renderer.reloadTeams()
        scoreboard.update(stats)
    }
    private lateinit var glView: GLSurfaceView
    private lateinit var miniMap: MiniMapView
    private lateinit var statusLabel: TextView
    private lateinit var viewPhotoButton: MaterialButton
    private lateinit var playerName: String
    private lateinit var playerTeam: Team
    private var drops: MutableList<PhotoDrop> = mutableListOf()
    private var playerCount = 0
    private var status = OnlineWorld.Status.CONNECTING
    /** Talking with teammates; null when not playing online. */
    private var voice: VoiceChat? = null
    private lateinit var micButton: MaterialButton
    private lateinit var speakerButton: MaterialButton
    /** Between onResume and onPause: voice chat only runs then. */
    private var inForeground = false
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
    /** The second Shoot button, above the joystick for the left thumb. */
    private lateinit var shootButtonLeft: View
    /** The Shoot buttons being held: firing goes on while either is. */
    private val shootHeld = HashSet<View>()
    private lateinit var scoreboard: Scoreboard
    private var stats: List<PlayerStats> = emptyList()
    /** Whether my last bullet to hit each player (by uid) hit the head: a kill by it is a headshot. */
    private val lastHitHeadshot = HashMap<String, Boolean>()

    private lateinit var weaponButton: MaterialButton
    private lateinit var reloadButton: MaterialButton
    private lateinit var scopeButton: MaterialButton
    private lateinit var zoomInButton: MaterialButton
    private lateinit var zoomOutButton: MaterialButton
    private lateinit var scopeOverlay: View
    private lateinit var climbButton: MaterialButton
    private lateinit var grenadeButton: MaterialButton
    private lateinit var grenadeKindButton: MaterialButton
    /** White over everything while a flashbang has me blinded. */
    private lateinit var flashOverlay: View
    /** The pistol, primary and sniper rifle the player carries (see LoadoutActivity). */
    private lateinit var guns: Map<GunSlot, Weapon>
    /** The character I play as (see Characters): its dances are for winning. */
    private lateinit var myCharacter: com.example.beirutrun.city.Character
    /** Found a scope (lost again on dying): a primary without a built-in one can zoom in. */
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
            online.updatePose(renderer.playerX, renderer.playerZ, renderer.heading, renderer.isWalking, renderer.prone, renderer.jumpSeq, renderer.floorY, renderer.climbing)
            updateClimbButton()
            updateCrosshair()
            updateGameTimer()
            updateWeaponButtons()
            updateGrenadeButtons()
            updateMedkitButtons()
            updateStoreButton()
            checkPickups()
            // Now and then, re-check who is still around (hides players whose phone went quiet).
            if (++ticks % 25 == 0) online.publishPlayers()
            ticker.postDelayed(this, POSE_INTERVAL_MS)
        }
    }

    /** A solo game against bots (see ModeActivity), or null online. */
    private var solo: SoloMatch? = null
    private var soloTicks = 0
    private var soloLast = 0L

    /** Solo: moves the bots on, shows their shots, takes their hits, and keeps the score. */
    private val soloTick = object : Runnable {
        override fun run() {
            val game = solo ?: return
            val now = SystemClock.uptimeMillis()
            val dt = if (soloLast == 0L) 0f else ((now - soloLast) / 1000f).coerceAtMost(0.25f)
            soloLast = now
            if (!gameOver) {
                val me = SoloMatch.Player(
                    playerName, playerTeam.id, renderer.playerX, renderer.playerY, renderer.playerZ, renderer.prone, dead,
                )
                for (e in game.update(dt, me)) when (e) {
                    is SoloMatch.Event.Shot -> {
                        renderer.addRemoteShot(e.uid, e.x, e.y, e.z, e.dx, e.dy, e.dz, e.gun)
                        shotSound(e.x, e.z, e.gun)
                    }
                    is SoloMatch.Event.PlayerHit ->
                        takeHit(e.fromUid, getString(R.string.killed_by, e.fromName), e.damage)
                }
            }
            // The bots are drawn like other players; the scoreboard keeps up once a second.
            if (++soloTicks % 2 == 0) onPlayers(game.players(System.currentTimeMillis()))
            if (soloTicks % 20 == 0) onStats(game.stats())
            ticker.postDelayed(this, SOLO_TICK_MS)
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

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) setMic(true)
        else Toast.makeText(this, R.string.voice_mic_denied, Toast.LENGTH_LONG).show()
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
        // A solo game against bots has no room (see ModeActivity).
        val soloSettings = Session.solo(this)
        // Teams added to this room (their flags and colours), usually already loaded by the team screen.
        RoomTeams.follow(if (FirebaseSession.configured(this)) roomId else null)
        val team = Teams.byId(Session.teamId(this))
        val missing = when {
            FirebaseSession.configured(this) && roomId == null && soloSettings == null -> ModeActivity::class.java
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
        if (online.configured && roomId != null) voice = VoiceChat(applicationContext, roomId)
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
        // Solo: the bots, on the other teams (and half on mine if chosen), and a game clock of our own.
        val start = Session.position(this, mapInfo.id) ?: Triple(city.spawnX, city.spawnZ, mapInfo.startYaw)
        solo = soloSettings?.let { s ->
            SoloMatch(
                city, s, playerTeam.id, Teams.builtIn.map { it.id }.filter { it != playerTeam.id },
                label = { getString(R.string.bot_name, it) }, startX = start.first, startZ = start.second,
            )
        }
        soloSettings?.let {
            gameDurationMs = it.durationMs
            gameEndsAt = System.currentTimeMillis() + it.durationMs
        }
        online.onStat = { key -> solo?.count(key) }
        showEnemyAreas = Session.enemyAreas(this)
        val scene = sceneBuilder.submit(Callable { CityScene.build(city, mapInfo.look) })
        // Each character's animated model (see Characters), loaded in the background the first
        // time a player uses it: mine, and whichever other players choose.
        val rigLoads = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Future<SoldierRig>>()
        val rigFor: (String) -> java.util.concurrent.Future<SoldierRig> = { id ->
            rigLoads.getOrPut(id) {
                val c = Characters.byId(this, id)
                sceneBuilder.submit(Callable {
                    SoldierRig.load(c.folder, c.dances, army = c.army) { path -> runCatching { assets.open(path).use { it.readBytes() } }.getOrNull() }
                })
            }
        }
        // Character models for people in the street (assets/models/pedestrians/), in the background.
        val pedestrianRigs = Characters.pedestrians(this).mapIndexed { i, folder ->
            sceneBuilder.submit(Callable {
                SoldierRig.load(folder) { path ->
                    Characters.pedestrianFile(i, path).firstNotNullOfOrNull { p -> runCatching { assets.open(p).use { it.readBytes() } }.getOrNull() }
                }
            })
        }
        // The detailed gun models (see GunMeshes), read in the background; until then guns are drawn from boxes.
        val gunMeshes = sceneBuilder.submit(Callable {
            GunMeshes.loadAll { path -> runCatching { assets.open(path).use { it.readBytes() } }.getOrNull() }
        })
        // Car models for the traffic (assets/models/cars/), in the background; until then the built-in shapes.
        val carModels = sceneBuilder.submit(Callable {
            val files = runCatching { assets.list(CarModels.DIR)?.toList() }.getOrNull().orEmpty().filter { it.endsWith(".glb") }.sorted()
            CarModels.loadAll(files) { path -> runCatching { assets.open("${CarModels.DIR}/$path").use { it.readBytes() } }.getOrNull() }
        })
        val character = Characters.byId(this, Session.character(this))
        myCharacter = character
        // My face photo goes on my character only if I chose it and the character has no face of its own.
        val showMyFace = Session.faceOnCharacter(this) && !character.ownFace
        // Offline, drops live on this phone. Online, they come from Firebase (see onDrops);
        // anything saved here meanwhile is waiting to be uploaded, so show it too.
        drops = repo.drops()

        val myFace = Session.faceFile(this)
        renderer = CityRenderer(
            city = city,
            sceneSource = scene,
            repo = repo,
            playerName = playerName,
            rigFor = rigFor,
            pedestrianRigs = pedestrianRigs,
            playerCharacter = character.id,
            gunMeshSource = gunMeshes,
            carModelSource = carModels,
            // Where you last stood on this map, or its start point facing its view.
            start = start,
            streetPhotos = repo.streets(),
            playerFace = { if (showMyFace) myFace else NO_FACE },
            dropFace = ::faceFileFor,
            // Other players' faces too only when they chose it, on a character without its own face.
            remoteFace = { p ->
                if (p.showFace && !Characters.byId(this, p.character).ownFace) online.remoteFaceFile(p.uid) else NO_FACE
            },
            onNearbyDrop = ::onNearbyDrop,
            onShot = { x, y, z, dx, dy, dz ->
                sounds.shoot(weapon = renderer.weapon)
                online.sendShot(x, y, z, dx, dy, dz)
                if (!gameOver) online.countShot()
                // Bots within earshot turn to look.
                solo?.heardShot(x, z)
            },
            onHitPlayer = { uid, damage, headshot ->
                sounds.ouch()
                if (!gameOver) online.countHit()
                lastHitHeadshot[uid] = headshot
                val bots = solo
                if (bots != null && bots.isBot(uid)) {
                    // A bot is hit on this phone, where it lives; its last heart is my kill.
                    if (!gameOver) bots.hitByPlayer(uid, damage)?.let { name -> onKilled(uid, name) }
                } else {
                    online.sendHit(uid, damage)
                }
            },
            playerTeam = playerTeam.id,
            teamFlag = { id -> Teams.byId(id)?.let { TeamFlags.load(applicationContext, it) } },
            teamColor = { id -> Teams.byId(id)?.color },
            teamUniform = { id -> Teams.byId(id)?.let { it.uniform to it.gear } },
            onOutOfAmmo = { gun -> showBanner(getString(R.string.out_of_ammo, gun.displayName)) },
            onReloadStart = { gun ->
                sounds.reload(gun)
                setScoped(false)
                updateWeaponButtons()
            },
            onReloaded = { updateWeaponButtons() },
            pickupLabel = ::pickupLabel,
            onGrenadeThrown = { kind, v ->
                sounds.grenadeThrow()
                online.sendGrenade(kind.id, v[0], v[1], v[2], v[3], v[4], v[5])
                // A grenade counts as a shot, so it shows in accuracy like any other.
                if (!gameOver) online.countShot()
                updateGrenadeButtons()
            },
            onHoldGrenade = { kind -> online.setGrenadeHold(kind?.id.orEmpty()) },
            onNoGrenade = { kind -> showBanner(getString(R.string.no_grenades, kind.displayName)) },
            onGrenadeBurst = { kind, x, _, z, _ ->
                val hearing = GRENADE_HEARING_RANGE * if (kind == GrenadeKind.SMOKE) 0.3f else 1f
                val distance = hypot(x - renderer.playerX, z - renderer.playerZ)
                sounds.grenadeBurst(kind, 1f - distance / hearing)
            },
            onFlashed = ::flashed,
            onSelfHit = { damage -> takeHit("", getString(R.string.killed_by_own_grenade), damage, ownGrenade = true) },
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
        bindSayOrDrop()
        setupVoiceButtons()
        findViewById<MaterialButton>(R.id.viewModeButton).apply {
            renderer.firstPerson = Session.firstPerson(this@CityActivity)
            fun label() = setText(if (renderer.firstPerson) R.string.view_gun else R.string.view_3d_person)
            label()
            setOnClickListener {
                renderer.firstPerson = !renderer.firstPerson
                Session.setFirstPerson(this@CityActivity, renderer.firstPerson)
                label()
            }
        }
        shootButton = findViewById(R.id.shootButton)
        shootButtonLeft = findViewById(R.id.shootButtonLeft)
        bindShootButton(shootButton)
        bindShootButton(shootButtonLeft)
        weaponButton = findViewById(R.id.weaponButton)
        weaponButton.setOnClickListener { switchWeapon() }
        reloadButton = findViewById(R.id.reloadButton)
        reloadButton.setOnClickListener { if (!dead && !gameOver) renderer.reload() }
        scopeButton = findViewById(R.id.scopeButton)
        scopeButton.setOnClickListener { setScoped(!renderer.scoped) }
        zoomInButton = findViewById(R.id.zoomInButton)
        zoomInButton.setOnClickListener { changeZoom(+1) }
        zoomOutButton = findViewById(R.id.zoomOutButton)
        zoomOutButton.setOnClickListener { changeZoom(-1) }
        scopeOverlay = findViewById(R.id.scopeOverlay)
        flashOverlay = findViewById(R.id.flashOverlay)
        grenadeButton = findViewById(R.id.grenadeButton)
        grenadeButton.setOnClickListener { throwGrenade() }
        grenadeKindButton = findViewById(R.id.grenadeKindButton)
        grenadeKindButton.setOnClickListener { switchGrenade() }
        climbButton = findViewById(R.id.climbButton)
        climbButton.setOnClickListener { climb() }
        storeButton = findViewById(R.id.storeButton)
        storeButton.setOnClickListener { openShop() }
        // The three guns chosen on the loadout screen; the primary in hand to start with.
        guns = GunSlot.entries.associateWith { Session.gun(this, it) }
        renderer.weapon = guns.getValue(GunSlot.PRIMARY)
        online.setWeapon(renderer.weapon.id)
        online.setCharacter(character.id, showMyFace)
        online.pickupSpot = { city.randomStreetPoint() }
        if (!online.configured) scatterLocalPickups()
        // Offline there is no ranking to protect, so cheats always work.
        cheatsAllowed = !online.configured
        updateWeaponButtons()
        gameTimer = findViewById(R.id.gameTimer)
        gameTimer.setOnClickListener { showScoreboard() }
        scoreboard = Scoreboard(
            this,
            myUid = ::myUid,
            onLeave = { if (solo != null) leaveSolo() else leaveRoom() },
            // Solo: the same game again, from the start.
            onNewRoom = { if (solo != null) recreate() else leaveRoom(createNext = true) },
            onRanking = ::showRanking,
            nextLabel = if (soloSettings != null) R.string.score_play_again else R.string.score_new_room,
        )
        crosshair = findViewById(R.id.crosshair)
        findViewById<MaterialButton>(R.id.jumpButton).setOnClickListener { if (!dead) renderer.jump() }
        crawlButton = findViewById(R.id.crawlButton)
        crawlButton.setOnClickListener { if (!renderer.climbing) setProne(!renderer.prone) }
        heartsLabel = findViewById(R.id.hearts)
        medkitSmallButton = findViewById(R.id.medkitSmallButton)
        medkitBigButton = findViewById(R.id.medkitBigButton)
        medkitSmallButton.setOnClickListener { useMedkit(big = false) }
        medkitBigButton.setOnClickListener { useMedkit(big = true) }
        banner = findViewById(R.id.banner)
        damageFlash = findViewById(R.id.damageFlash)
        updateHearts()
        viewPhotoButton = findViewById(R.id.viewPhotoButton)
        viewPhotoButton.setOnClickListener { nearby?.let(::showPhotoDialog) }
        RoomTeams.watch(onTeamsChanged)

        online.start(playerName)
    }

    override fun onResume() {
        super.onResume()
        if (!::glView.isInitialized) return
        glView.onResume()
        online.resume()
        ticker.post(tick)
        soloLast = 0L
        ticker.post(soloTick)
        inForeground = true
        startVoice()
    }

    override fun onPause() {
        super.onPause()
        if (!::glView.isInitialized) return
        ticker.removeCallbacks(tick)
        ticker.removeCallbacks(soloTick)
        glView.onPause()
        online.pause()
        // In the background nobody hears me and I don't record: the mic goes off.
        inForeground = false
        voice?.stop()
        updateVoiceButtons()
        Session.savePosition(this, mapInfo.id, renderer.playerX, renderer.playerZ, renderer.yaw)
    }

    override fun onDestroy() {
        super.onDestroy()
        RoomTeams.unwatch(onTeamsChanged)
        ticker.removeCallbacks(respawn)
        sceneBuilder.shutdown()
        ticker.removeCallbacks(hideBanner)
        // Also offline pickups waiting to come back.
        ticker.removeCallbacksAndMessages(null)
        if (::scoreboard.isInitialized) scoreboard.dismiss()
        // Only "Leave room" and "Log out" delete an empty room here; if the app is just closed,
        // the room list cleans it up after a few minutes.
        if (::online.isInitialized) online.stop(leavingRoom)
        voice?.stop()
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

    /**
     * Dragging on the city (outside the joystick and buttons) turns the camera. One finger
     * steers at a time: when it lifts, another still down takes over without a jump.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindLookAround() {
        var pointerId = MotionEvent.INVALID_POINTER_ID
        var lastX = 0f
        var lastY = 0f
        fun follow(event: MotionEvent, index: Int) {
            pointerId = event.getPointerId(index)
            lastX = event.getX(index)
            lastY = event.getY(index)
        }
        glView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> follow(event, 0)
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(pointerId)
                    if (index >= 0) {
                        renderer.look(event.getX(index) - lastX, event.getY(index) - lastY)
                        lastX = event.getX(index)
                        lastY = event.getY(index)
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointerId) {
                    follow(event, if (event.actionIndex == 0) 1 else 0)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointerId = MotionEvent.INVALID_POINTER_ID
            }
            true
        }
    }

    /** One button for Say and Drop photo: it opens the two beside it, and picking one closes them. */
    private fun bindSayOrDrop() {
        val choices = findViewById<View>(R.id.sayOrDropChoices)
        fun close() { choices.visibility = View.GONE }
        findViewById<MaterialButton>(R.id.sayOrDropButton).setOnClickListener {
            choices.visibility = if (choices.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<MaterialButton>(R.id.sayButton).setOnClickListener { close(); showSayDialog() }
        findViewById<MaterialButton>(R.id.dropButton).setOnClickListener { close(); takeDropPhoto() }
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
        if (status == OnlineWorld.Status.ONLINE) startVoice()
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
        // The maps show teammates where they are; enemies only as a rough area (below).
        val teammates = shown.filter { it.team == playerTeam.id }
        miniMap.players = teammates
        fullMap?.players = teammates
        // Enemies only as a rough area: a red circle they are somewhere inside.
        val areas = if (!showEnemyAreas) emptyList() else enemyAreas.update(
            shown.filter { it.team != playerTeam.id && !it.dead }.map { Triple(it.uid, it.x, it.z) },
            SystemClock.uptimeMillis(),
        )
        miniMap.enemyAreas = areas
        fullMap?.enemyAreas = areas
        updateStatusLabel()
        startVoice()
        voice?.setTeammates(teammates.mapTo(HashSet()) { it.uid })
    }

    /** The latest drops and players from the server, before blocked players are filtered out. */
    private var sharedDrops: List<PhotoDrop> = emptyList()
    private var remotePlayers: List<RemotePlayer> = emptyList()
    /** Where enemies roughly are, for the maps (see EnemyAreas). */
    private val enemyAreas = EnemyAreas()
    /** Whether the maps show them (a choice kept on this phone, see the menu). */
    private var showEnemyAreas = true

    override fun onFacesChanged() = renderer.reloadFaces()

    override fun onRemoteShot(player: RemotePlayer) {
        val gun = Weapon.byId(player.weapon)
        renderer.addRemoteShot(player.uid, player.shotX, player.shotY, player.shotZ, player.shotDX, player.shotDY, player.shotDZ, gun)
        shotSound(player.shotX, player.shotZ, gun)
    }

    /**
     * Another soldier's shot from (x, z): quieter the further away it is, silent beyond ~60 m, or
     * three times that for a sniper rifle's boom.
     */
    private fun shotSound(x: Float, z: Float, gun: Weapon) {
        val hearing = HEARING_RANGE * if (gun.slot == GunSlot.SNIPER) 3f else 1f
        val distance = hypot(x - renderer.playerX, z - renderer.playerZ)
        sounds.shoot(volume = 0.8f * (1f - distance / hearing), weapon = gun)
    }

    override fun onRemoteGrenade(player: RemotePlayer) {
        // A kind from a newer version of the app is left out.
        val kind = GrenadeKind.byId(player.grenade) ?: return
        renderer.addRemoteGrenade(
            player.uid, kind, player.grenadeX, player.grenadeY, player.grenadeZ,
            player.grenadeVX, player.grenadeVY, player.grenadeVZ,
        )
        val distance = hypot(player.grenadeX - renderer.playerX, player.grenadeZ - renderer.playerZ)
        sounds.grenadeThrow(0.7f * (1f - distance / HEARING_RANGE))
    }

    override fun onHitBy(fromUid: String, fromName: String, damage: Int) =
        takeHit(fromUid, getString(R.string.killed_by, fromName.ifBlank { getString(R.string.someone) }), damage)

    /**
     * A bullet or grenade took [damage] hearts: from [fromUid] (credited with the kill), or from my
     * own grenade ([ownGrenade], counted as a death but nobody's kill). [killedText] says who did it.
     */
    private fun takeHit(fromUid: String, killedText: String, damage: Int, ownGrenade: Boolean = false) {
        if (dead || gameOver) return
        if (!ownGrenade) online.countHitTaken()
        if (!unlimitedHealth) health = (health - damage).coerceAtLeast(0)
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
        solo?.playerKilledBy(fromUid)
        renderer.down = true
        renderer.triggerHeld = false
        sounds.cancelReload()
        // The scope is lost with your life; find another.
        hasScope = false
        cheatScope = false
        setScoped(false)
        online.setHealth(0, true, fromUid)
        showBanner(killedText)
        ticker.postDelayed(respawn, RESPAWN_MS)
    }

    /**
     * A flashbang went off where I could see it: the screen goes white, stays white longer the
     * worse it was ([strength] 0..1), then clears; bad ones leave the ears ringing.
     */
    private fun flashed(strength: Float) {
        if (dead || gameOver) return
        flashOverlay.animate().cancel()
        flashOverlay.alpha = maxOf(flashOverlay.alpha, strength.coerceIn(0.35f, 1f))
        flashOverlay.animate()
            .setStartDelay((strength * FLASH_HOLD_MS).toLong())
            .setDuration(FLASH_FADE_MS)
            .alpha(0f)
            .start()
        if (strength > 0.3f) sounds.earRinging(strength * 0.6f)
    }

    // ---- Ladders ------------------------------------------------------------------------------

    /** Up or down the ladder within reach: standing up and lowering the scope first, since both hands are needed. */
    private fun climb() {
        if (dead || gameOver) return
        setProne(false)
        setScoped(false)
        renderer.triggerHeld = false
        renderer.climb()
    }

    /** The Climb button shows at a ladder's foot ("Climb up") or beside its top on a roof ("Climb down"). */
    private fun updateClimbButton() {
        val action = if (dead || gameOver) CityRenderer.LadderAction.NONE else renderer.ladderAction
        climbButton.visibility = if (action == CityRenderer.LadderAction.NONE) View.GONE else View.VISIBLE
        if (action != CityRenderer.LadderAction.NONE) {
            climbButton.setText(if (action == CityRenderer.LadderAction.UP) R.string.climb_up else R.string.climb_down)
        }
    }

    // ---- Grenades -----------------------------------------------------------------------------

    private fun throwGrenade() {
        if (dead || gameOver) return
        // The scope comes down to throw.
        setScoped(false)
        renderer.throwGrenade()
    }

    /** The next kind of grenade: frag → flashbang → smoke → molotov → frag. */
    private fun switchGrenade() {
        val kinds = GrenadeKind.entries
        val next = kinds[(renderer.grenadeKind.ordinal + 1) % kinds.size]
        renderer.grenadeKind = next
        updateGrenadeButtons()
        showBanner(
            if (renderer.unlimitedAmmo) getString(R.string.grenade_selected_unlimited, next.displayName)
            else getString(R.string.grenade_selected, next.displayName, renderer.grenades(next))
        )
    }

    /** The grenade button in the colour of the kind chosen (dim when there's none left), the kind and its count below. */
    private fun updateGrenadeButtons() {
        val kind = renderer.grenadeKind
        val left = renderer.grenades(kind)
        val unlimited = renderer.unlimitedAmmo
        val text = if (unlimited) getString(R.string.grenade_count_unlimited, kind.displayName)
            else getString(R.string.grenade_count, kind.displayName, left)
        if (grenadeKindButton.text.toString() != text) grenadeKindButton.text = text
        grenadeButton.iconTint = ColorStateList.valueOf(kind.color)
        grenadeButton.alpha = if (unlimited || (left > 0 && !dead && !gameOver)) 1f else 0.4f
    }

    override fun onKilled(victimUid: String, victimName: String) {
        if (gameOver) return
        online.countKill()
        sounds.death(volume = 0.8f)
        val headshot = lastHitHeadshot.remove(victimUid) == true
        val gain = if (headshot) award(XpReward.ELIMINATION, XpReward.HEADSHOT) else award(XpReward.ELIMINATION)
        val cash = if (headshot) pay(CashReward.KILL, CashReward.HEADSHOT) else pay(CashReward.KILL)
        val killed = getString(R.string.you_killed, victimName)
        showBanner(if (gain != null) {
            val reward = getString(R.string.xp_gain, gain.xp.toInt()) + (cash?.let { " · +" + Wallet.format(it) } ?: "")
            getString(R.string.kill_with_xp, killed, reward)
        } else killed)
    }

    /**
     * Gives me the money for [rewards] (see CashReward), like [award] only in games that count
     * toward careers (not solo or cheat rooms): saved on this phone and in my career. Returns how
     * much, or null.
     */
    private fun pay(vararg rewards: CashReward): Long? {
        if (!online.countsForCareer) return null
        val cash = rewards.sumOf { it.cash }
        Wallet.earn(this, cash)
        CareerWallet.upload(this)
        return cash
    }

    /**
     * Gives me the XP for [rewards] (see XpConfig), on this phone and in my online career, and
     * celebrates a new rank. Only games that count toward careers give XP (not rooms that allow
     * cheats): otherwise nothing happens and this returns null.
     */
    private fun award(vararg rewards: XpReward): XpGain? {
        if (!online.countsForCareer) return null
        val xp = rewards.sumOf { it.xp }
        val gain = PlayerProgress.addXp(this, xp)
        online.countXp(xp)
        if (gain.rankedUp) RankUpOverlay.show(this, gain.after.rank, playSound = { sounds.rankUp() })
        return gain
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

    override fun onCareerXp(xp: Map<String, Long>) = scoreboard.updateCareer(xp)

    override fun onPickups(pickups: List<Pickup>) = showPickups(pickups)

    override fun onCheatsAllowed(allowed: Boolean) {
        if (allowed && !cheatsAllowed) showBanner(getString(R.string.cheats_room_notice))
        cheatsAllowed = allowed
    }

    // ---- Guns and pickups ---------------------------------------------------------------------

    private fun pickupLabel(kind: PickupKind) = getString(when (kind) {
        PickupKind.SCOPE -> R.string.pickup_scope
        PickupKind.PISTOL_AMMO -> R.string.pickup_pistol_mag
        PickupKind.AK_AMMO -> R.string.pickup_primary_mag
        PickupKind.SNIPER_AMMO -> R.string.pickup_sniper_mag
        PickupKind.FRAG_GRENADE -> R.string.pickup_frag
        PickupKind.FLASH_GRENADE -> R.string.pickup_flashbang
        PickupKind.SMOKE_GRENADE -> R.string.pickup_smoke
        PickupKind.MOLOTOV -> R.string.pickup_molotov
        PickupKind.MEDKIT_SMALL -> R.string.pickup_medkit_small
        PickupKind.MEDKIT_BIG -> R.string.pickup_medkit_big
    })

    /** A grenade is only picked up with room for it (see GrenadeKind.most): otherwise it stays for someone else. */
    private fun canTake(p: Pickup) = when (p.kind) {
        PickupKind.MEDKIT_SMALL -> smallMedkits < MAX_SMALL_MEDKITS
        PickupKind.MEDKIT_BIG -> bigMedkits < MAX_BIG_MEDKITS
        else -> p.kind.grenade?.let { renderer.unlimitedAmmo || renderer.canCarry(it) } ?: true
    }

    /** The next of the three guns carried: pistol → primary → sniper rifle → pistol. */
    private fun switchWeapon() {
        val slots = GunSlot.entries
        val next = guns.getValue(slots[(renderer.weapon.slot.ordinal + 1) % slots.size])
        renderer.weapon = next
        renderer.triggerHeld = false
        sounds.cancelReload()
        setScoped(false)
        online.setWeapon(next.id)
        updateWeaponButtons()
    }

    /** Whether the gun in hand has a scope: built in (sniper rifles, the M4), or one found for a primary. */
    private fun canScope(gun: Weapon = renderer.weapon) = gun.hasScope || (gun.slot == GunSlot.PRIMARY && hasScope)

    /** The magnifications the gun in hand's scope offers, lowest first. */
    private fun zoomLevels(gun: Weapon = renderer.weapon) = gun.zooms.ifEmpty { listOf(Weapon.PICKUP_SCOPE_ZOOM) }

    /** Which of [zoomLevels] the scope is set to; each time the scope is raised it starts at the lowest. */
    private var zoomIndex = 0

    /** Looks through the scope, or back out. There's no aiming through a scope while reloading. */
    private fun setScoped(on: Boolean) {
        val scoped = on && canScope() && !dead && !gameOver && renderer.reloading == null
        if (scoped && !renderer.scoped) zoomIndex = 0
        renderer.zoom = zoomLevels()[zoomIndex.coerceIn(0, zoomLevels().lastIndex)]
        renderer.scoped = scoped
        scopeOverlay.visibility = if (scoped) View.VISIBLE else View.GONE
        showToggle(scopeButton, scoped, if (scoped) R.string.scope_off else R.string.scope)
        updateZoomButtons()
        updateCrosshair()
    }

    /** Zooms the scope in (+1) or out (-1) through its magnifications. */
    private fun changeZoom(step: Int) {
        val levels = zoomLevels()
        zoomIndex = (zoomIndex + step).coerceIn(0, levels.lastIndex)
        renderer.zoom = levels[zoomIndex]
        updateZoomButtons()
        showBanner(getString(R.string.zoom_level, levels[zoomIndex].toInt()))
    }

    /** Zoom in and out show while looking through a scope that has more than one magnification. */
    private fun updateZoomButtons() {
        val levels = zoomLevels()
        val show = renderer.scoped && levels.size > 1
        zoomInButton.visibility = if (show) View.VISIBLE else View.GONE
        zoomOutButton.visibility = if (show) View.VISIBLE else View.GONE
        zoomInButton.alpha = if (zoomIndex < levels.lastIndex) 1f else 0.4f
        zoomOutButton.alpha = if (zoomIndex > 0) 1f else 0.4f
    }

    /**
     * The gun button shows the gun in hand, the rounds in its magazine and in its spare
     * magazines (red when there's nothing left), or that it's reloading.
     */
    private fun updateWeaponButtons() {
        val gun = renderer.weapon
        val loaded = renderer.loaded(gun)
        val spare = renderer.spare(gun)
        val unlimited = renderer.unlimitedAmmo
        val text = when {
            unlimited -> getString(R.string.weapon_ammo_unlimited, gun.displayName)
            renderer.reloading == gun -> getString(R.string.weapon_reloading, gun.displayName)
            else -> getString(R.string.weapon_ammo, gun.displayName, loaded, spare)
        }
        if (weaponButton.text.toString() != text) weaponButton.text = text
        weaponButton.setTextColor(if (unlimited || loaded > 0 || spare > 0) 0xFFFFFFFF.toInt() else 0xFFFF5252.toInt())
        // Reloading only does something with room in the magazine and a spare one to put in.
        reloadButton.alpha = if (!unlimited && loaded < gun.magazine && spare > 0 && renderer.reloading == null) 1f else 0.4f
        scopeButton.visibility = if (canScope(gun)) View.VISIBLE else View.GONE
    }

    /** Shows the pickups that lie in the streets (magazines; the rest are bought at the stores). */
    private fun showPickups(list: List<Pickup>) {
        pickups = list.filter { it.kind.inStreets }
        renderer.setPickups(pickups)
    }

    /** Walking over a pickup takes it; online the server decides who got there first. */
    private fun checkPickups() {
        if (dead || gameOver) return
        val px = renderer.playerX
        val pz = renderer.playerZ
        val p = pickups.firstOrNull { it.slot !in taking && canTake(it) && hypot(it.x - px, it.z - pz) < PICKUP_RADIUS } ?: return
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

    /** A magazine goes to the gun carried in its slot; a scope fits the primary (until death); a grenade joins the others. */
    private fun collect(p: Pickup) {
        val slot = p.kind.slot
        val grenade = p.kind.grenade
        if (p.kind.medkit) {
            if (p.kind == PickupKind.MEDKIT_BIG) bigMedkits++ else smallMedkits++
            sounds.grenadeThrow(0.5f)
            showBanner(getString(
                if (p.kind == PickupKind.MEDKIT_BIG) R.string.picked_medkit_big else R.string.picked_medkit_small,
                if (p.kind == PickupKind.MEDKIT_BIG) bigMedkits else smallMedkits,
            ))
            updateMedkitButtons()
        } else if (grenade != null) {
            renderer.addGrenade(grenade)
            sounds.grenadeThrow(0.5f)
            showBanner(getString(R.string.picked_grenade, grenade.displayName, renderer.grenades(grenade)))
            updateGrenadeButtons()
        } else if (slot != null) {
            val gun = guns.getValue(slot)
            renderer.addMagazine(gun)
            showBanner(getString(R.string.picked_magazine, gun.displayName, gun.magazine))
        } else {
            val primary = guns.getValue(GunSlot.PRIMARY)
            hasScope = true
            cheatScope = false // a real scope: kept when cheats are cancelled
            showBanner(if (primary.hasScope) getString(R.string.picked_scope_has_one, primary.displayName)
                else getString(R.string.picked_scope))
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
        sounds.cancelReload()
        renderer.triggerHeld = false
        setScoped(false)
        shootButton.alpha = 0.4f
        shootButtonLeft.alpha = 0.4f
        gameTimer.visibility = View.VISIBLE
        gameTimer.setText(R.string.score_game_over)
        gameTimer.setTextColor(0xFFFFC107.toInt())
        sounds.death(volume = 0.5f)
        scoreboard.update(stats)
        val won = iWon()
        // A win counts, with its XP, once per game (coming back into a finished game doesn't
        // count it again), and only where careers count.
        var promoted = false
        if (won && online.countsForCareer && PlayerProgress.recordWin(this, "${Session.roomId(this)}@$gameEndsAt")) {
            online.countWin()
            promoted = award(XpReward.VICTORY)?.rankedUp == true
            pay(CashReward.VICTORY)?.let { showBanner(getString(R.string.cash_won, Wallet.format(it))) }
        }
        // The winner's character dances (if it has dances) before the results come up; a
        // promotion gets its moment too.
        val wait = when {
            won && victoryDance() -> VICTORY_DANCE_MS
            promoted -> RANK_UP_MS
            else -> 0L
        }
        if (wait > 0) ticker.postDelayed({ if (!isFinishing) scoreboard.show(over = true) }, wait)
        else scoreboard.show(over = true)
    }

    /** Whether I won the game outright: the top score, above zero and not shared. */
    private fun iWon(): Boolean {
        val ranked = Scoreboard.ranked(stats)
        val best = ranked.firstOrNull() ?: return false
        val second = ranked.getOrNull(1)
        return best.uid == myUid() && best.score > 0 && (second == null || second.score < best.score)
    }

    /**
     * If my character has dances (I won): switch to the 3D-person view, where my soldier turns to
     * face the camera, and dance one of them, picked at random; the other players see it too.
     * Returns whether there's a dance.
     */
    private fun victoryDance(): Boolean {
        val dance = myCharacter.dances.randomOrNull() ?: return false
        renderer.firstPerson = false
        findViewById<MaterialButton>(R.id.viewModeButton).setText(R.string.view_3d_person)
        renderer.playerDance = dance.clip
        online.setDance(dance.clip)
        showBanner(getString(R.string.victory_dance))
        return true
    }

    private fun showScoreboard() {
        scoreboard.update(stats)
        scoreboard.show(over = gameOver)
    }

    // ---- Shooting -----------------------------------------------------------------------------

    /**
     * Press to fire, hold to keep firing (with either Shoot button). Sliding the finger while it's
     * down aims, just like dragging on the city, so you can follow a target without letting go of
     * the trigger.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindShootButton(button: View) {
        var lastX = 0f
        var lastY = 0f
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    shootHeld += v
                    lastX = event.rawX
                    lastY = event.rawY
                    if (!dead && !gameOver) {
                        renderer.triggerHeld = true
                        renderer.pullTrigger()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    renderer.look(event.rawX - lastX, event.rawY - lastY)
                    lastX = event.rawX
                    lastY = event.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    shootHeld -= v
                    if (shootHeld.isEmpty()) renderer.triggerHeld = false
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
            enemyAreas = miniMap.enemyAreas
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
        // A new life starts with the usual medkits; the ones carried were lost.
        smallMedkits = START_SMALL_MEDKITS
        bigMedkits = START_BIG_MEDKITS
        health = CityRenderer.MAX_HEALTH
        dead = false
        renderer.health = health
        renderer.down = false
        setProne(false)
        online.setHealth(health, false, "")
        updateHearts()
        showBanner(getString(R.string.respawned))
    }

    /** Lie down to crawl, or stand back up; the button lights up while lying down. */
    private fun setProne(on: Boolean) {
        renderer.prone = on
        showToggle(crawlButton, on, if (on) R.string.stand_up else R.string.crawl)
    }

    /** An icon-only button that stays on (scope, crawl): lit while [on], and says what a tap will do. */
    private fun showToggle(button: MaterialButton, on: Boolean, action: Int) {
        button.backgroundTintList = ColorStateList.valueOf(if (on) TOGGLE_ON_COLOR else TOGGLE_OFF_COLOR)
        button.contentDescription = getString(action)
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
        updateMedkitButtons()
    }

    // ---- Medkits ------------------------------------------------------------------------------

    /** Medkits carried: small ones give a heart back, big ones fill every heart. */
    private var smallMedkits = START_SMALL_MEDKITS
    private var bigMedkits = START_BIG_MEDKITS
    private lateinit var medkitSmallButton: MaterialButton
    private lateinit var medkitBigButton: MaterialButton

    /** "+1 ×2" and "Full ×1"; dimmed with none left, or nothing to heal. */
    private fun updateMedkitButtons() {
        if (!::medkitSmallButton.isInitialized) return
        val hurt = !dead && !gameOver && health < CityRenderer.MAX_HEALTH
        medkitSmallButton.text = getString(R.string.medkit_small_button, smallMedkits)
        medkitBigButton.text = getString(R.string.medkit_big_button, bigMedkits)
        medkitSmallButton.alpha = if (hurt && smallMedkits > 0) 1f else 0.45f
        medkitBigButton.alpha = if (hurt && bigMedkits > 0) 1f else 0.45f
    }

    // ---- Arms stores --------------------------------------------------------------------------

    private lateinit var storeButton: MaterialButton
    private var shopDialog: androidx.appcompat.app.AlertDialog? = null
    private var shopRows: LinearLayout? = null
    private var shopCash: TextView? = null

    /** At a store's counter (alive, game on): the Store button; walking away closes the shop. */
    private fun updateStoreButton() {
        val at = if (dead || gameOver) null else renderer.nearStore
        storeButton.visibility = if (at != null) View.VISIBLE else View.GONE
        if (at == null) shopDialog?.dismiss()
    }

    /** What I pay at the stores: nothing in a solo game (it pays nothing either). */
    private fun priceOf(item: StoreItem) = if (solo != null) 0L else item.price

    /** The shop: magazines for my guns, a scope, medkits and grenades, each with its price; the game goes on meanwhile. */
    private fun openShop() {
        if (shopDialog != null) return
        val pad = (16 * resources.displayMetrics.density).toInt()
        val cash = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF7CD67C.toInt())
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, 0, pad, pad) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(cash)
            addView(android.widget.ScrollView(this@CityActivity).apply { addView(rows) })
        }
        shopCash = cash
        shopRows = rows
        shopDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.store_title)
            .setView(body)
            .setPositiveButton(R.string.close, null)
            .setOnDismissListener { shopDialog = null; shopRows = null; shopCash = null }
            .show()
        fillShop()
    }

    /** One row per thing for sale: what it is, how many I have, and Buy with the price (greyed out when I can't). */
    private fun fillShop() {
        val rows = shopRows ?: return
        shopCash?.text = if (solo != null) getString(R.string.store_free) else getString(R.string.store_cash, Wallet.format(Wallet.cash(this)))
        rows.removeAllViews()
        fun row(item: StoreItem, name: String, have: String, full: Boolean, give: () -> Unit) {
            val price = priceOf(item)
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            line.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@CityActivity).apply { text = name; textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
                addView(TextView(this@CityActivity).apply { text = have; textSize = 12f; alpha = 0.7f })
            })
            val affordable = solo != null || Wallet.cash(this) >= price
            line.addView(MaterialButton(this).apply {
                text = when {
                    full -> getString(R.string.store_full)
                    price == 0L -> getString(R.string.store_take)
                    else -> getString(R.string.buy_button, Wallet.format(price))
                }
                isEnabled = !full && affordable && !dead
                setOnClickListener { buy(item, give) }
            })
            rows.addView(line)
        }
        for (slot in GunSlot.entries) {
            val gun = guns.getValue(slot)
            val item = when (slot) { GunSlot.PISTOL -> StoreItem.PISTOL_MAG; GunSlot.PRIMARY -> StoreItem.PRIMARY_MAG; GunSlot.SNIPER -> StoreItem.SNIPER_MAG }
            row(item, getString(R.string.store_magazine, gun.displayName, gun.magazine),
                getString(R.string.store_rounds, renderer.spare(gun)),
                full = renderer.unlimitedAmmo || renderer.spare(gun) >= gun.startAmmo + gun.magazine) { renderer.addMagazine(gun) }
        }
        val primary = guns.getValue(GunSlot.PRIMARY)
        row(StoreItem.SCOPE, getString(R.string.store_scope, primary.displayName),
            getString(if (canScope(primary)) R.string.store_scope_have else R.string.store_scope_none),
            full = canScope(primary)) { hasScope = true; cheatScope = false }
        row(StoreItem.MEDKIT_SMALL, getString(R.string.store_medkit_small), getString(R.string.store_carried, smallMedkits, MAX_SMALL_MEDKITS),
            full = smallMedkits >= MAX_SMALL_MEDKITS) { smallMedkits++ }
        row(StoreItem.MEDKIT_BIG, getString(R.string.store_medkit_big), getString(R.string.store_carried, bigMedkits, MAX_BIG_MEDKITS),
            full = bigMedkits >= MAX_BIG_MEDKITS) { bigMedkits++ }
        for ((kind, item) in listOf(GrenadeKind.FRAG to StoreItem.FRAG, GrenadeKind.FLASH to StoreItem.FLASHBANG,
            GrenadeKind.SMOKE to StoreItem.SMOKE, GrenadeKind.MOLOTOV to StoreItem.MOLOTOV)) {
            row(item, kind.displayName, getString(R.string.store_carried, renderer.grenades(kind), kind.most),
                full = !renderer.canCarry(kind)) { renderer.addGrenade(kind) }
        }

        // Swap guns: any other gun I own, for the slot it fits (free: I bought it already).
        rows.addView(TextView(this).apply {
            setText(R.string.store_swap_title)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
        })
        var any = false
        for (slot in GunSlot.entries) {
            for (gun in Weapon.inSlot(slot).filter { it != guns.getValue(slot) && Wallet.owns(this, it) }) {
                any = true
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
                }
                line.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@CityActivity).apply { text = gun.displayName; textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
                    addView(TextView(this@CityActivity).apply {
                        text = getString(R.string.store_swap_instead, guns.getValue(slot).displayName)
                        textSize = 12f
                        alpha = 0.7f
                    })
                })
                line.addView(MaterialButton(this).apply {
                    setText(R.string.store_swap)
                    isEnabled = !dead
                    setOnClickListener { swapGun(gun) }
                })
                rows.addView(line)
            }
        }
        if (!any) rows.addView(TextView(this).apply {
            setText(R.string.store_swap_none)
            textSize = 12f
            alpha = 0.7f
        })
    }

    /**
     * Carries [gun] instead of the gun in its slot (one I own), from now on: in hand if that slot
     * was, and kept as my choice for next time. Each gun keeps its own rounds.
     */
    private fun swapGun(gun: Weapon) {
        if (dead || gameOver || !Wallet.owns(this, gun)) return
        guns = guns + (gun.slot to gun)
        Session.setGun(this, gun)
        if (renderer.weapon.slot == gun.slot) {
            renderer.weapon = gun
            renderer.triggerHeld = false
            sounds.cancelReload()
            setScoped(false)
            online.setWeapon(gun.id)
        }
        sounds.reload(gun)
        showBanner(getString(R.string.store_swapped, gun.displayName))
        updateWeaponButtons()
        fillShop()
    }

    /** Pays for [item] (nothing in solo) and hands it over with [give]; then the shop shows what's changed. */
    private fun buy(item: StoreItem, give: () -> Unit) {
        if (dead || gameOver) return
        val price = priceOf(item)
        if (!Wallet.spend(this, price)) {
            showBanner(getString(R.string.store_not_enough))
            return fillShop()
        }
        if (price > 0) CareerWallet.upload(this)
        give()
        sounds.grenadeThrow(0.5f)
        updateMedkitButtons()
        updateGrenadeButtons()
        updateWeaponButtons()
        fillShop()
    }

    /** Uses a small medkit (a heart back) or a big one (every heart), if I have one and I'm hurt. */
    private fun useMedkit(big: Boolean) {
        if (dead || gameOver) return
        when {
            health >= CityRenderer.MAX_HEALTH -> return showBanner(getString(R.string.medkit_full_health))
            big && bigMedkits <= 0 -> return showBanner(getString(R.string.medkit_none_big))
            !big && smallMedkits <= 0 -> return showBanner(getString(R.string.medkit_none_small))
        }
        if (big) bigMedkits-- else smallMedkits--
        health = if (big) CityRenderer.MAX_HEALTH else (health + 1).coerceAtMost(CityRenderer.MAX_HEALTH)
        renderer.health = health
        online.setHealth(health, false, "")
        sounds.grenadeThrow(0.6f)
        updateHearts()
        showBanner(getString(if (big) R.string.medkit_used_big else R.string.medkit_used_small))
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
            OnlineWorld.Status.NOT_CONFIGURED -> solo?.let { soloStatus(it) } ?: getString(R.string.status_offline)
            OnlineWorld.Status.CONNECTING -> getString(R.string.status_connecting)
            OnlineWorld.Status.FAILED -> getString(R.string.status_failed)
            OnlineWorld.Status.ONLINE ->
                if (playerCount == 0) getString(R.string.status_online_alone)
                else resources.getQuantityString(R.plurals.status_online, playerCount, playerCount)
        }
        val room = Session.roomName(this)
        statusLabel.text = if (room != null) getString(R.string.status_in_room, room, text) else text
    }

    // ---- Team voice chat ----------------------------------------------------------------------

    private fun setupVoiceButtons() {
        micButton = findViewById(R.id.micButton)
        speakerButton = findViewById(R.id.speakerButton)
        val voice = voice ?: return
        findViewById<View>(R.id.voiceButtons).visibility = View.VISIBLE
        micButton.setOnClickListener {
            when {
                voice.micOn -> setMic(false)
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED -> setMic(true)
                else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        speakerButton.setOnClickListener {
            voice.setAllMuted(!voice.allMuted)
            updateVoiceButtons()
            Toast.makeText(this, if (voice.allMuted) R.string.voice_all_muted else R.string.voice_all_unmuted,
                Toast.LENGTH_SHORT).show()
        }
        updateVoiceButtons()
    }

    private fun setMic(on: Boolean) {
        val voice = voice ?: return
        voice.setMic(on)
        updateVoiceButtons()
        Toast.makeText(this, if (on) R.string.voice_mic_on else R.string.voice_mic_off, Toast.LENGTH_SHORT).show()
    }

    /** Green mic while my team can hear me; crossed-out speaker while they're muted. */
    private fun updateVoiceButtons() {
        val voice = voice ?: return
        micButton.setIconResource(if (voice.micOn) R.drawable.ic_mic else R.drawable.ic_mic_off)
        micButton.backgroundTintList = ColorStateList.valueOf(if (voice.micOn) MIC_ON_COLOR else HUD_COLOR)
        speakerButton.setIconResource(if (voice.allMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
    }

    /** Joins the team's voice chat once signed in, while the game is on screen. */
    private fun startVoice() {
        if (!inForeground) return
        val uid = online.uid ?: return
        voice?.start(uid)
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
        val voice = voice
        // Teammates' voices can be muted one by one (players on other teams can't be heard anyway).
        fun canMute(p: RemotePlayer) = voice != null && p.team == playerTeam.id && !Blocklist.isBlocked(this, p.uid)
        val names = others.map {
            if (canMute(it) && voice!!.isMuted(it.uid)) getString(R.string.voice_muted_name, it.name) else it.name
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_players)
            .setItems(names.toTypedArray()) { _, which ->
                val p = others[which]
                val dialog = MaterialAlertDialogBuilder(this)
                    .setTitle(p.name)
                    .setMessage(if (p.say.isNotBlank()) getString(R.string.player_last_said, p.say) else null)
                if (voice != null && canMute(p)) {
                    val muted = voice.isMuted(p.uid)
                    dialog.setNegativeButton(if (muted) R.string.voice_unmute else R.string.voice_mute) { _, _ ->
                        voice.setMuted(p.uid, !muted)
                    }
                } else {
                    dialog.setNegativeButton(android.R.string.cancel, null)
                }
                dialog
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
            .setPositiveButton(R.string.say) { _, _ ->
                // A cheat code is never said out loud.
                val text = input.text.toString().trim()
                Cheat.parse(text)?.let(::applyCheat) ?: say(text)
            }
            .show()
        input.requestFocus()
    }

    // ---- Cheat codes --------------------------------------------------------------------------

    /**
     * Whether cheat codes work here: in rooms created with "Allow cheats" (whose scores don't count
     * toward the ranking, see OnlineWorld), and offline.
     */
    private var cheatsAllowed = false
    /** Cheat: hits still flash and hurt, but never cost a heart. */
    private var unlimitedHealth = false
    /** The scope came from the "find a scope" cheat, so cancelling cheats takes it back. */
    private var cheatScope = false

    /** Turns on the cheat typed in the Say box (see [Cheat]). Cheats only change this phone's game. */
    private fun applyCheat(cheat: Cheat) {
        if (!cheatsAllowed) return showBanner(getString(R.string.cheat_not_allowed))
        if (dead || gameOver) return showBanner(getString(R.string.cheat_not_now))
        when (cheat) {
            Cheat.UNLIMITED_AMMO -> renderer.unlimitedAmmo = true
            Cheat.UNLIMITED_HEALTH -> {
                unlimitedHealth = true
                healFully()
            }
            Cheat.FIND_SCOPE -> if (!hasScope) {
                hasScope = true
                cheatScope = true
            }
            Cheat.FULL_HEALTH -> healFully()
            Cheat.SUPER_SPEED -> renderer.speedBoost = SUPER_SPEED
            Cheat.RAPID_FIRE -> renderer.rapidFire = true
            Cheat.CANCEL -> cancelCheats()
        }
        updateWeaponButtons()
        showBanner(getString(cheat.message))
    }

    /** Turns every cheat off. Health and bullets stay as they are; a cheat scope is taken back. */
    private fun cancelCheats() {
        renderer.unlimitedAmmo = false
        renderer.rapidFire = false
        renderer.speedBoost = 1f
        unlimitedHealth = false
        if (cheatScope) {
            hasScope = false
            setScoped(false)
        }
        cheatScope = false
    }

    private fun healFully() {
        health = CityRenderer.MAX_HEALTH
        renderer.health = health
        online.setHealth(health, false, "")
        updateHearts()
    }

    // ---- Player menu --------------------------------------------------------------------------

    private fun showPlayerMenu() {
        val hasStreets = repo.streets().isNotEmpty()
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        if (online.configured || solo != null) actions += R.string.menu_scoreboard to { showScoreboard() }
        if (online.configured) actions += R.string.menu_players to { showPlayersDialog() }
        // Enemies as rough red circles on the maps (see EnemyAreas): on or off, kept on this phone.
        actions += (if (showEnemyAreas) R.string.menu_enemy_areas_hide else R.string.menu_enemy_areas_show) to {
            showEnemyAreas = !showEnemyAreas
            Session.setEnemyAreas(this, showEnemyAreas)
            onPlayers(remotePlayers)
            showBanner(getString(if (showEnemyAreas) R.string.enemy_areas_on else R.string.enemy_areas_off))
        }
        actions += listOf<Pair<Int, () -> Unit>>(
            R.string.menu_change_team to {
                startActivity(Intent(this, TeamSelectActivity::class.java))
                finish()
            },
            R.string.menu_change_character to {
                startActivity(Intent(this, CharacterActivity::class.java))
                finish()
            },
            R.string.menu_change_guns to {
                startActivity(Intent(this, LoadoutActivity::class.java))
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
        if (solo != null) actions += R.string.menu_leave_solo to { leaveSolo() }
        else if (FirebaseSession.configured(this)) actions += R.string.menu_leave_room to { leaveRoom() }
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
    /** My id on the scoreboard: my online uid, or the solo game's "me". */
    private fun myUid(): String? = if (solo != null) SoloMatch.ME else online.uid

    /** Ends a solo game: back to choosing solo or multiplayer. */
    private fun leaveSolo() {
        Session.setSolo(this, null)
        startActivity(Intent(this, ModeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** "Solo · 4 bots, Hard". */
    private fun soloStatus(game: SoloMatch): String {
        val s = game.settings
        val level = getString(when (s.difficulty) {
            BotDifficulty.EASY -> R.string.solo_easy
            BotDifficulty.MEDIUM -> R.string.solo_medium
            BotDifficulty.HARD -> R.string.solo_hard
        })
        return getString(R.string.status_solo, resources.getQuantityString(R.plurals.solo_bots, s.bots, s.bots) + ", " + level)
    }

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
        /** How often a solo game moves its bots on, ms. */
        private const val SOLO_TICK_MS = 50L
        /** Medkits: small ones each life starts with, and the most of each that can be carried. */
        private const val START_SMALL_MEDKITS = 3
        private const val START_BIG_MEDKITS = 2
        private const val MAX_SMALL_MEDKITS = 5
        private const val MAX_BIG_MEDKITS = 3
        private const val RESPAWN_MS = 4_000L
        private const val BANNER_MS = 2_500L
        /** HUD button background (as in the HudButton style), and the mic button's while it's on. */
        private const val HUD_COLOR = 0x99000000.toInt()
        private const val MIC_ON_COLOR = 0xCC2E7D32.toInt()
        private const val HEARING_RANGE = 60f
        /** A frag or flashbang can be heard this far away (smoke much less), metres. */
        private const val GRENADE_HEARING_RANGE = 150f
        /** Blinded by a flashbang: the screen stays white up to this long (worst case), then clears over the fade. */
        private const val FLASH_HOLD_MS = 2_500f
        private const val FLASH_FADE_MS = 1_500L
        private const val FLAG_ICON_DP = 22f
        /** Scope and crawl buttons: amber while on, the usual translucent black while off. */
        private const val TOGGLE_ON_COLOR = 0xDDFFB300.toInt()
        private const val TOGGLE_OFF_COLOR = 0x99000000.toInt()
        /** Super speed cheat: walking and running twice as fast. */
        private const val SUPER_SPEED = 2f
        /** After winning, the victory dance plays this long before the results come up. */
        private const val VICTORY_DANCE_MS = 6_000L
        /** How long the results wait for a rank-up at the end of a game (see RankUpOverlay). */
        private const val RANK_UP_MS = 4_500L
        /** A file that never exists: no face photo on this character. */
        private val NO_FACE = File("")
    }
}
