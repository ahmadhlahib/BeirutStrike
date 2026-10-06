package com.example.beirutrun

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.beirutrun.city.CityMapInfo
import com.example.beirutrun.city.CityMaps
import com.example.beirutrun.online.AppVersion
import com.example.beirutrun.online.CareerWallet
import com.example.beirutrun.online.FirebaseSession
import com.example.beirutrun.online.RoomDirectory
import com.example.beirutrun.online.RoomInfo
import com.example.beirutrun.progression.PlayerProgress
import com.example.beirutrun.progression.ProgressState
import com.example.beirutrun.progression.Progression
import com.example.beirutrun.progression.Rank
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

/** Pick a room: create one (named, optionally with a password) or join one from the list. */
class RoomsActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var createButton: MaterialButton
    private val adapter = RoomAdapter()
    private var directory: RoomDirectory? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rooms)
        SystemBars.keepClear(this)
        StartBackground.applyTo(this)
        status = findViewById(R.id.roomsStatus)
        createButton = findViewById(R.id.createRoomButton)
        findViewById<View>(R.id.roomsRank).setOnClickListener {
            startActivity(Intent(this, CareerActivity::class.java))
        }

        val list = findViewById<ListView>(R.id.roomList)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.roomsEmpty)
        list.setOnItemClickListener { _, _, position, _ -> onRoomTapped(adapter.getItem(position)) }
        createButton.setOnClickListener { showCreateDialog() }
        createButton.isEnabled = false
        findViewById<View>(R.id.rankingButton).setOnClickListener {
            startActivity(Intent(this, RankingActivity::class.java))
        }

        if (!FirebaseSession.configured(this)) {
            // Built without Firebase: there are no rooms, just this phone.
            Session.setRoom(this, null, null)
            startActivity(Intent(this, TeamSelectActivity::class.java))
            finish()
            return
        }
        status.setText(R.string.status_connecting)
        FirebaseSession.signIn { uid ->
            if (isFinishing) return@signIn
            if (uid == null) {
                status.setText(R.string.status_failed)
                return@signIn
            }
            val dir = RoomDirectory(uid)
            directory = dir
            createButton.isEnabled = true
            // Rooms with a minimum rank go by my online career's XP: bring it and this phone's
            // together (e.g. XP whose upload failed), so both show the same rank.
            dir.careerXp { careerXp ->
                if (careerXp == null || isFinishing) return@careerXp
                dir.addCareerXp(Session.name(this).orEmpty(), PlayerProgress.syncWithCareer(this, careerXp))
                showRank()
                adapter.notifyDataSetChanged()
            }
            // My money and guns too (see Wallet).
            CareerWallet.settle(this, uid) { if (!isFinishing) showRank() }
            // This phone's version, which the rules check before joining or creating a room.
            AppVersion.register(this)
            AppVersion.check(this)
            // Came from "New room" on a finished game's scoreboard: go straight to creating one.
            if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_CREATE_ROOM, false)) showCreateDialog()
            dir.listen(
                onRooms = { rooms ->
                    status.text = resources.getQuantityString(R.plurals.rooms_count, rooms.size, rooms.size)
                    adapter.rooms = rooms
                    openInvite(rooms)
                },
                onError = { status.setText(R.string.status_failed) },
            )
        }
    }

    /** Redraws the list every second so each room's time left counts down. */
    private val ticker = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            if (adapter.rooms.any { it.endsAt > 0 }) adapter.notifyDataSetChanged()
            ticker.postDelayed(this, 1_000L)
        }
    }

    override fun onResume() {
        super.onResume()
        ticker.post(refresh)
        // The rank may have changed in a game since this screen was last shown.
        showRank()
        // Back from sharing an invite: on into the room created.
        afterInvite?.let { next ->
            afterInvite = null
            next()
        }
    }

    /** What to do once back from sharing an invite (going on at once would cover WhatsApp). */
    private var afterInvite: (() -> Unit)? = null

    private fun showRank() =
        RankViews.bindCard(findViewById(R.id.roomsRank), Session.name(this).orEmpty(), PlayerProgress.state(this))

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(refresh)
    }

    override fun onDestroy() {
        super.onDestroy()
        directory?.stop()
    }

    /** Shows the room's map (and asks for the password if it's locked) before joining. */
    private fun onRoomTapped(room: RoomInfo, password: String? = null) {
        if (isOver(room)) {
            Toast.makeText(this, R.string.room_game_over_join, Toast.LENGTH_LONG).show()
            return
        }
        val me = PlayerProgress.state(this)
        if (!Progression.meetsMinimum(me.totalXp, room.minXp)) {
            val needed = Rank.forXp(room.minXp)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.room_rank_too_low_title)
                .setIcon(needed.badge)
                .setMessage(getString(
                    R.string.room_rank_too_low, RankViews.bothNames(this, needed), needed.level,
                    RankViews.bothNames(this, me.rank), RankViews.number(room.minXp - me.totalXp),
                ))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val map = CityMaps.byId(room.map)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_join_room, null)
        view.findViewById<ImageView>(R.id.joinMapPreview).setImageBitmap(preview(map))
        view.findViewById<TextView>(R.id.joinMapName).text = getString(R.string.room_map_named, mapLabel(room.map))
        view.findViewById<View>(R.id.passwordLayout).visibility = if (room.hasPassword) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.joinCheats).visibility = if (room.cheats) View.VISIBLE else View.GONE
        val input = view.findViewById<EditText>(R.id.passwordInput)
        // From an invite: its password, filled in.
        password?.let { input.setText(it) }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.room_join_title, room.name))
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.room_join) { _, _ -> join(room, input.text.toString()) }
            .show()
    }

    /** A room's map name with its size, e.g. "Hamra (200 m)". */
    private fun mapLabel(roomMap: String) = getString(
        R.string.map_with_size, CityMaps.byId(roomMap).name, sizeLabel(CityMaps.sizeOf(roomMap)),
    )

    private fun sizeLabel(size: Int?) =
        if (size == null) getString(R.string.map_size_full) else getString(R.string.map_size_metres, size)

    /** Map preview pictures, loaded once. */
    private val previews = HashMap<String, Bitmap?>()
    private fun preview(map: CityMapInfo): Bitmap? = previews.getOrPut(map.id) { map.loadPreview(this) }

    private fun join(room: RoomInfo, password: String) {
        val dir = directory ?: return
        status.setText(R.string.room_joining)
        dir.join(room, password) { ok ->
            if (ok) {
                Session.setRoomPassword(this, password)
                enter(room.id, room.name, room.map, !room.noEnemyAreas)
            } else {
                status.text = ""
                Toast.makeText(
                    this,
                    when {
                        room.hasPassword -> R.string.room_wrong_password
                        // This phone says my rank is enough, but the server goes by my career's XP.
                        room.minXp > 0 -> R.string.room_rank_join_failed
                        else -> R.string.room_join_failed
                    },
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun showCreateDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_create_room, null)
        val nameInput = view.findViewById<EditText>(R.id.roomNameInput)
        val passwordInput = view.findViewById<EditText>(R.id.roomPasswordInput)
        nameInput.setText(getString(R.string.room_default_name, Session.name(this).orEmpty()))

        // One card per map; tapping one selects it.
        val maps = CityMaps.available(this).ifEmpty { listOf(CityMaps.default) }
        var chosen = maps.first()
        val choices = view.findViewById<LinearLayout>(R.id.mapChoices)
        val cards = maps.map { map ->
            val card = LayoutInflater.from(this).inflate(R.layout.item_map_choice, choices, false) as MaterialCardView
            card.findViewById<ImageView>(R.id.mapPreview).setImageBitmap(preview(map))
            card.findViewById<TextView>(R.id.mapName).text = map.name
            choices.addView(card)
            map to card
        }
        val density = resources.displayMetrics.density
        fun highlight() = cards.forEach { (map, card) ->
            card.strokeWidth = if (map == chosen) (3 * density).toInt() else 0
        }
        cards.forEach { (map, card) -> card.setOnClickListener { chosen = map; highlight() } }
        highlight()

        // One button per play-area size.
        var size = CityMaps.defaultSize
        val sizeGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.sizeChoices)
        for (s in CityMaps.sizes) {
            val button = LayoutInflater.from(this).inflate(R.layout.item_size_choice, sizeGroup, false) as MaterialButton
            button.id = View.generateViewId()
            button.text = sizeLabel(s)
            sizeGroup.addView(button)
            if (s == size) sizeGroup.check(button.id)
            button.setOnClickListener { size = s }
        }

        // One button per game length, 30 seconds to an hour.
        var duration = RoomDirectory.DEFAULT_DURATION_MS
        val durationGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.durationChoices)
        for (d in RoomDirectory.durations) {
            val button = LayoutInflater.from(this).inflate(R.layout.item_size_choice, durationGroup, false) as MaterialButton
            button.id = View.generateViewId()
            button.text = durationLabel(d)
            durationGroup.addView(button)
            if (d == duration) durationGroup.check(button.id)
            button.setOnClickListener { duration = d }
        }

        // Cheats: off by default. Turning them on says straight away that scores won't count.
        val enemyAreasSwitch = view.findViewById<SwitchMaterial>(R.id.roomEnemyAreasSwitch)
        val cheatsSwitch = view.findViewById<SwitchMaterial>(R.id.roomCheatsSwitch)
        val cheatsHelper = view.findViewById<TextView>(R.id.roomCheatsHelper)
        val helperColor = cheatsHelper.currentTextColor
        cheatsSwitch.setOnCheckedChangeListener { _, on ->
            cheatsHelper.setText(if (on) R.string.room_cheats_on_helper else R.string.room_cheats_off_helper)
            cheatsHelper.setTextColor(if (on) CHEATS_WARNING_COLOR else helperColor)
        }

        // Minimum rank: anyone (Private) unless chosen, and never above my own rank.
        val me = PlayerProgress.state(this)
        var minRank = Rank.PRIVATE
        val minRow = view.findViewById<View>(R.id.roomMinRank)
        fun showMinRank() {
            RankViews.setBadge(view.findViewById(R.id.roomMinRankBadge), minRank)
            view.findViewById<TextView>(R.id.roomMinRankName).text = RankViews.bothNames(this, minRank)
            view.findViewById<TextView>(R.id.roomMinRankLevel).text =
                if (minRank == Rank.PRIVATE) getString(R.string.room_min_rank_everyone)
                else getString(R.string.room_min_rank_level, minRank.level)
        }
        showMinRank()
        view.findViewById<TextView>(R.id.roomMinRankHelper).text =
            getString(R.string.room_min_rank_helper, RankViews.bothNames(this, me.rank))
        minRow.setOnClickListener { pickMinRank(me, minRank) { minRank = it; showMinRank() } }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.room_create_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.room_create) { _, _ ->
                val name = nameInput.text.toString().trim().ifEmpty { getString(R.string.room_untitled) }
                create(name.take(40), passwordInput.text.toString(), CityMaps.roomValue(chosen, size), duration,
                    cheatsSwitch.isChecked, minRank.xpRequired.toLong(), enemyAreasSwitch.isChecked)
            }
            .show()
    }

    /**
     * Lists every rank to pick a room's minimum from; only mine and those below it can be chosen
     * (see Progression.minimumRanksFor), the ones above are shown locked.
     */
    private fun pickMinRank(me: ProgressState, current: Rank, onPick: (Rank) -> Unit) {
        val allowed = Progression.minimumRanksFor(me.totalXp).toSet()
        val ranks = Rank.entries
        val rankAdapter = object : BaseAdapter() {
            override fun getCount() = ranks.size
            override fun getItem(position: Int) = ranks[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = ranks[position] in allowed

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView
                    ?: LayoutInflater.from(parent.context).inflate(R.layout.item_rank_choice, parent, false)
                val r = ranks[position]
                val open = r in allowed
                RankViews.setBadge(row.findViewById(R.id.rankChoiceBadge), r)
                row.findViewById<TextView>(R.id.rankChoiceName).text = RankViews.bothNames(this@RoomsActivity, r)
                val level = getString(R.string.room_min_rank_level, r.level)
                row.findViewById<TextView>(R.id.rankChoiceLevel).text =
                    if (open) level else getString(R.string.room_map_and_players, level, getString(R.string.room_rank_locked))
                row.findViewById<ImageView>(R.id.rankChoiceMark).apply {
                    when {
                        r == current -> setImageResource(R.drawable.ic_rank_check)
                        !open -> setImageResource(R.drawable.ic_lock)
                        else -> setImageDrawable(null)
                    }
                }
                row.alpha = if (open) 1f else LOCKED_RANK_ALPHA
                return row
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.room_min_rank_pick)
            .setAdapter(rankAdapter) { _, which -> ranks[which].takeIf { it in allowed }?.let(onPick) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** "30 s", "1 min", "1 h". */
    private fun durationLabel(ms: Long): String {
        val seconds = (ms / 1000).toInt()
        return when {
            seconds < 60 -> getString(R.string.duration_seconds, seconds)
            seconds < 3600 -> getString(R.string.duration_minutes, seconds / 60)
            else -> getString(R.string.duration_hours, seconds / 3600)
        }
    }

    /** What the room list says about a room's game: its length, the time left, or that it's over. */
    private fun gameLabel(room: RoomInfo): String? {
        if (room.durationMs <= 0) return null
        val dir = directory ?: return null
        return when {
            room.startedAt == 0L -> getString(R.string.room_game_length, durationLabel(room.durationMs))
            dir.serverNow() >= room.endsAt -> getString(R.string.room_game_over)
            else -> getString(R.string.room_time_left, GameClock.format(room.endsAt - dir.serverNow()))
        }
    }

    private fun isOver(room: RoomInfo) = room.endsAt > 0 && (directory?.serverNow() ?: 0L) >= room.endsAt

    /** Creates a room; [map] is its map value (see CityMaps.roomValue). */
    private fun create(name: String, password: String, map: String, durationMs: Long, cheats: Boolean, minXp: Long, enemyAreas: Boolean) {
        val dir = directory ?: return
        status.setText(R.string.room_creating)
        dir.create(name, password, map, durationMs, cheats, minXp, enemyAreas) { id ->
            if (id != null) {
                Session.setRoomPassword(this, password)
                offerInvite(id, name, password) { enter(id, name, map, enemyAreas) }
            } else Toast.makeText(
                this,
                // The server checks a minimum rank against my online career, which may lag behind.
                if (minXp > 0) R.string.room_rank_create_failed else R.string.room_create_failed,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Opened from an invite (see RoomInvite): once the rooms are listed, opens the invited room's
     * join window (its password filled in), or says it has closed.
     */
    private fun openInvite(rooms: List<RoomInfo>) {
        val invite = Session.pendingInvite(this) ?: return
        Session.setPendingInvite(this, null)
        val room = rooms.firstOrNull { it.id == invite.roomId }
        if (room == null) Toast.makeText(this, R.string.invite_room_gone, Toast.LENGTH_LONG).show()
        else onRoomTapped(room, invite.password)
    }

    /** Just created room [id]: offers to invite friends on WhatsApp, then goes on ([then]) either way. */
    private fun offerInvite(id: String, name: String, password: String, then: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.invite_created_title)
            .setMessage(if (password.isEmpty()) R.string.invite_created else R.string.invite_created_password)
            .setCancelable(false)
            .setNegativeButton(R.string.invite_later) { _, _ -> then() }
            .setPositiveButton(R.string.invite_whatsapp) { _, _ ->
                // Into the room once back from WhatsApp, not straight away (that would hide it).
                afterInvite = then
                // The share sheet didn't open: straight on, as there's nothing to come back from.
                if (!RoomInvite.share(this, id, name, password)) {
                    afterInvite = null
                    then()
                }
            }
            .show()
    }

    /** Into room [id]; [enemyAreas]: whether its maps show enemy areas (the creator's choice). */
    private fun enter(id: String, name: String, map: String, enemyAreas: Boolean) {
        Session.setRoomEnemyAreas(this, enemyAreas)
        Session.setRoom(this, id, name, map)
        startActivity(Intent(this, TeamSelectActivity::class.java))
    }

    companion object {
        /** Open the create-room dialog as soon as the screen is ready. */
        const val EXTRA_CREATE_ROOM = "create_room"
        /** The "scores won't count" warning under the cheats switch, and on cheat rooms in the list. */
        private const val CHEATS_WARNING_COLOR = 0xFFFFB300.toInt()
        /** A room's minimum rank in the list: gold when I may join, red when my rank is too low. */
        private const val MIN_RANK_COLOR = 0xFFB8860B.toInt()
        private const val RANK_TOO_LOW_COLOR = 0xFFE53935.toInt()
        /** Ranks above mine in the minimum-rank list. */
        private const val LOCKED_RANK_ALPHA = 0.4f
    }

    private inner class RoomAdapter : BaseAdapter() {
        var rooms: List<RoomInfo> = emptyList()
            set(value) { field = value; notifyDataSetChanged() }

        override fun getCount() = rooms.size
        override fun getItem(position: Int) = rooms[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_room, parent, false)
            val room = rooms[position]
            val map = CityMaps.byId(room.map)
            view.findViewById<TextView>(R.id.roomName).text = room.name
            view.findViewById<ImageView>(R.id.roomMapPreview).setImageBitmap(preview(map))
            val details = getString(
                R.string.room_map_and_players, mapLabel(room.map),
                resources.getQuantityString(R.plurals.room_players, room.online, room.online),
            )
            view.findViewById<TextView>(R.id.roomPlayers).text =
                gameLabel(room)?.let { getString(R.string.room_map_and_players, details, it) } ?: details
            view.findViewById<TextView>(R.id.roomCheats).visibility = if (room.cheats) View.VISIBLE else View.GONE
            view.findViewById<ImageView>(R.id.roomLock).visibility =
                if (room.hasPassword) View.VISIBLE else View.GONE
            // The minimum rank, in red when mine is below it.
            view.findViewById<View>(R.id.roomMinRank).visibility = if (room.minXp > 0) View.VISIBLE else View.GONE
            if (room.minXp > 0) {
                val needed = Rank.forXp(room.minXp)
                RankViews.setBadge(view.findViewById(R.id.roomMinRankBadge), needed)
                view.findViewById<TextView>(R.id.roomMinRankText).apply {
                    text = getString(R.string.room_min_rank_badge, RankViews.localName(this@RoomsActivity, needed))
                    val ok = Progression.meetsMinimum(PlayerProgress.state(this@RoomsActivity).totalXp, room.minXp)
                    setTextColor(if (ok) MIN_RANK_COLOR else RANK_TOO_LOW_COLOR)
                }
            }
            return view
        }
    }
}
