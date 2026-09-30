package com.example.beirutrun

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.beirutrun.online.FirebaseSession
import com.example.beirutrun.online.RoomTeams
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import java.util.concurrent.Executors

/**
 * Pick a team: the built-in ones and the ones added to this room (see [Teams], [RoomTeams]), with
 * how many players in the room are on each. Online, players can add a team of their own: a name, a
 * flag picture from the phone and a colour. Added teams can be reported with a long press.
 */
class TeamSelectActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private val counts = mutableMapOf<String, TextView>()
    /** Players per team id, from the last update (the list is rebuilt when teams are added). */
    private var byTeam: Map<String, Int> = emptyMap()
    private var playersRef: DatabaseReference? = null
    private var playersListener: ValueEventListener? = null
    private val online get() = FirebaseSession.configured(this) && Session.roomId(this) != null
    private val onTeamsChanged: () -> Unit = { showTeams() }
    private val io = Executors.newSingleThreadExecutor()

    /** The add-team dialog while it's open: the flag picked so far, and its preview. */
    private var newFlag: ByteArray? = null
    private var newFlagPreview: ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_team_select)
        SystemBars.keepClear(this)
        StartBackground.applyTo(this)

        val roomName = Session.roomName(this)
        findViewById<TextView>(R.id.teamRoom).text =
            if (roomName != null) getString(R.string.team_room, roomName) + " · " + com.example.beirutrun.city.CityMaps.byId(Session.roomMap(this)).name
            else getString(R.string.status_offline)

        list = findViewById(R.id.teamList)
        RoomTeams.follow(if (online) Session.roomId(this) else null)
        RoomTeams.watch(onTeamsChanged)
        showTeams()
        listenTeamCounts()
    }

    override fun onDestroy() {
        super.onDestroy()
        RoomTeams.unwatch(onTeamsChanged)
        playersListener?.let { playersRef?.removeEventListener(it) }
        io.shutdown()
    }

    /** One card per team, then the Add a team button (online only). */
    private fun showTeams() {
        list.removeAllViews()
        counts.clear()
        val inflater = LayoutInflater.from(this)
        val current = Session.teamId(this)
        val density = resources.displayMetrics.density
        for (team in Teams.inRoom()) {
            val card = inflater.inflate(R.layout.item_team, list, false) as MaterialCardView
            card.findViewById<ImageView>(R.id.teamFlag).setImageBitmap(TeamFlags.load(this, team))
            card.findViewById<TextView>(R.id.teamName).text = team.name
            counts[team.id] = card.findViewById(R.id.teamCount)
            card.strokeColor = team.color
            card.strokeWidth = ((if (team.id == current) 4 else 2) * density).toInt()
            card.setOnClickListener { choose(team) }
            if (RoomTeams.byId(team.id) != null) {
                card.findViewById<TextView>(R.id.teamAddedBy).visibility = View.VISIBLE
                card.setOnLongClickListener { confirmReport(team); true }
            }
            list.addView(card)
        }
        showCounts()
        if (online) {
            val add = inflater.inflate(R.layout.item_add_team, list, false) as MaterialButton
            add.setOnClickListener {
                if (RoomTeams.canAdd) showAddTeamDialog()
                else Toast.makeText(this, getString(R.string.add_team_full, RoomTeams.MAX_TEAMS), Toast.LENGTH_LONG).show()
            }
            list.addView(add)
        }
    }

    private fun showCounts() {
        for ((id, label) in counts) {
            val n = byTeam[id] ?: 0
            label.text = resources.getQuantityString(R.plurals.team_players, n, n)
        }
    }

    private fun listenTeamCounts() {
        val room = Session.roomId(this) ?: return
        val db = if (FirebaseSession.configured(this)) FirebaseSession.database() else null
        val ref = db?.getReference("rooms/$room/players") ?: return
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                byTeam = snapshot.children
                    .mapNotNull { it.child("team").getValue(String::class.java) }
                    .groupingBy { it }.eachCount()
                showCounts()
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }
        ref.addValueEventListener(l)
        playersRef = ref
        playersListener = l
    }

    // ---- Adding a team ------------------------------------------------------------------------

    private val pickFlag = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) readFlag(uri)
    }

    private fun showAddTeamDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_team, null)
        val nameInput = view.findViewById<EditText>(R.id.teamNameInput)
        newFlag = null
        newFlagPreview = view.findViewById(R.id.teamFlagPreview)
        val pick = {
            pickFlag.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        view.findViewById<View>(R.id.teamFlagPick).setOnClickListener { pick() }
        newFlagPreview?.setOnClickListener { pick() }

        // Colour swatches; the first colour no team in the room has yet is chosen to start with.
        val taken = Teams.inRoom().map { it.color }.toSet()
        var color = RoomTeams.colors.firstOrNull { it !in taken } ?: RoomTeams.colors.first()
        val swatches = view.findViewById<LinearLayout>(R.id.teamColors)
        val density = resources.displayMetrics.density
        val size = (36 * density).toInt()
        for (c in RoomTeams.colors) {
            swatches.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = (8 * density).toInt() }
                contentDescription = getString(R.string.add_team_color)
                setOnClickListener { color = c; highlightSwatches(swatches, c) }
                tag = c
            })
        }
        highlightSwatches(swatches, color)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_team_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.add_team_create, null)
            .setOnDismissListener { newFlagPreview = null }
            .show()
        // Checked before closing, so a missing name or flag doesn't throw the form away.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameInput.text.toString().trim().replace(Regex("\\s+"), " ")
            val flag = newFlag
            when {
                name.isEmpty() -> nameInput.error = getString(R.string.add_team_need_name)
                flag == null -> Toast.makeText(this, R.string.add_team_need_flag, Toast.LENGTH_SHORT).show()
                else -> {
                    it.isEnabled = false
                    addTeam(name, color, flag) { ok -> if (ok) dialog.dismiss() else it.isEnabled = true }
                }
            }
        }
    }

    /** Draws each swatch as a circle in its colour, the chosen one with a thick white ring. */
    private fun highlightSwatches(swatches: LinearLayout, chosen: Int) {
        val density = resources.displayMetrics.density
        for (i in 0 until swatches.childCount) {
            val v = swatches.getChildAt(i)
            val c = v.tag as Int
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke(((if (c == chosen) 4 else 1) * density).toInt(), if (c == chosen) 0xFFFFFFFF.toInt() else 0x66000000)
            }
        }
    }

    /** Makes the picked picture into a flag (in the background) and shows it in the form. */
    private fun readFlag(uri: Uri) {
        io.execute {
            val flag = RoomTeams.encodeFlag(applicationContext, uri)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (flag == null) {
                    Toast.makeText(this, R.string.add_team_bad_picture, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                newFlag = flag
                newFlagPreview?.setImageBitmap(BitmapFactory.decodeByteArray(flag, 0, flag.size))
            }
        }
    }

    private fun addTeam(name: String, color: Int, flag: ByteArray, onDone: (Boolean) -> Unit) {
        FirebaseSession.signIn { uid ->
            if (uid == null) {
                Toast.makeText(this, R.string.add_team_failed, Toast.LENGTH_LONG).show()
                return@signIn onDone(false)
            }
            RoomTeams.create(uid, name, color, flag) { team ->
                if (isFinishing) return@create
                onDone(team != null)
                if (team == null) Toast.makeText(this, R.string.add_team_failed, Toast.LENGTH_LONG).show()
                else choose(team)
            }
        }
    }

    // ---- Reporting an added team --------------------------------------------------------------

    private fun confirmReport(team: Team) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.report_team_title, team.name))
            .setMessage(R.string.report_team_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.report) { _, _ ->
                FirebaseSession.signIn { uid ->
                    if (uid == null) return@signIn Toast.makeText(this, R.string.report_failed, Toast.LENGTH_LONG).show()
                    RoomTeams.report(uid, team) { ok ->
                        Toast.makeText(this, if (ok) R.string.report_sent else R.string.report_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    // ---- Joining ------------------------------------------------------------------------------

    /** Your team decides your soldier; take a face photo first if there isn't one yet. */
    private fun choose(team: Team) {
        Session.setTeamId(this, team.id)
        if (Session.faceFile(this).exists()) openCity() else takeFace.launch(Intent(this, FaceCaptureActivity::class.java))
    }

    /** Skipping the photo is fine too: the soldier keeps their own face. */
    private val takeFace = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { openCity() }

    private fun openCity() {
        startActivity(Intent(this, CityActivity::class.java))
    }
}
