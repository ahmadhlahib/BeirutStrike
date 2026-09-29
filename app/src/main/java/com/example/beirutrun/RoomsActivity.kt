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
import com.example.beirutrun.online.FirebaseSession
import com.example.beirutrun.online.RoomDirectory
import com.example.beirutrun.online.RoomInfo
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Pick a room: create one (named, optionally with a password) or join one from the list. */
class RoomsActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var createButton: MaterialButton
    private val adapter = RoomAdapter()
    private var directory: RoomDirectory? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rooms)
        StartBackground.applyTo(this)
        status = findViewById(R.id.roomsStatus)
        createButton = findViewById(R.id.createRoomButton)
        findViewById<TextView>(R.id.roomsGreeting).text =
            getString(R.string.rooms_greeting, Session.name(this).orEmpty())

        val list = findViewById<ListView>(R.id.roomList)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.roomsEmpty)
        list.setOnItemClickListener { _, _, position, _ -> onRoomTapped(adapter.getItem(position)) }
        createButton.setOnClickListener { showCreateDialog() }
        createButton.isEnabled = false

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
            dir.listen(
                onRooms = { rooms ->
                    status.text = resources.getQuantityString(R.plurals.rooms_count, rooms.size, rooms.size)
                    adapter.rooms = rooms
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
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(refresh)
    }

    override fun onDestroy() {
        super.onDestroy()
        directory?.stop()
    }

    /** Shows the room's map (and asks for the password if it's locked) before joining. */
    private fun onRoomTapped(room: RoomInfo) {
        if (isOver(room)) {
            Toast.makeText(this, R.string.room_game_over_join, Toast.LENGTH_LONG).show()
            return
        }
        val map = CityMaps.byId(room.map)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_join_room, null)
        view.findViewById<ImageView>(R.id.joinMapPreview).setImageBitmap(preview(map))
        view.findViewById<TextView>(R.id.joinMapName).text = getString(R.string.room_map_named, mapLabel(room.map))
        view.findViewById<View>(R.id.passwordLayout).visibility = if (room.hasPassword) View.VISIBLE else View.GONE
        val input = view.findViewById<EditText>(R.id.passwordInput)
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
                enter(room.id, room.name, room.map)
            } else {
                status.text = ""
                Toast.makeText(
                    this,
                    if (room.hasPassword) R.string.room_wrong_password else R.string.room_join_failed,
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

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.room_create_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.room_create) { _, _ ->
                val name = nameInput.text.toString().trim().ifEmpty { getString(R.string.room_untitled) }
                create(name.take(40), passwordInput.text.toString(), CityMaps.roomValue(chosen, size), duration)
            }
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
    private fun create(name: String, password: String, map: String, durationMs: Long) {
        val dir = directory ?: return
        status.setText(R.string.room_creating)
        dir.create(name, password, map, durationMs) { id ->
            if (id != null) enter(id, name, map)
            else Toast.makeText(this, R.string.room_create_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun enter(id: String, name: String, map: String) {
        Session.setRoom(this, id, name, map)
        startActivity(Intent(this, TeamSelectActivity::class.java))
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
            view.findViewById<ImageView>(R.id.roomLock).visibility =
                if (room.hasPassword) View.VISIBLE else View.GONE
            return view
        }
    }
}
