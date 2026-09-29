package com.example.beirutrun

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.beirutrun.online.FirebaseSession
import com.google.android.material.card.MaterialCardView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener

/** Pick a team (see [Teams]); shows how many players in the room are on each one. */
class TeamSelectActivity : AppCompatActivity() {

    private val counts = mutableMapOf<String, TextView>()
    private var playersRef: DatabaseReference? = null
    private var playersListener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_team_select)
        SystemBars.keepClear(this)
        StartBackground.applyTo(this)

        val roomName = Session.roomName(this)
        findViewById<TextView>(R.id.teamRoom).text =
            if (roomName != null) getString(R.string.team_room, roomName) + " · " + com.example.beirutrun.city.CityMaps.byId(Session.roomMap(this)).name
            else getString(R.string.status_offline)

        val list = findViewById<LinearLayout>(R.id.teamList)
        val inflater = LayoutInflater.from(this)
        val current = Session.teamId(this)
        val density = resources.displayMetrics.density
        for (team in Teams.all) {
            val card = inflater.inflate(R.layout.item_team, list, false) as MaterialCardView
            card.findViewById<ImageView>(R.id.teamFlag).setImageBitmap(TeamFlags.load(this, team))
            card.findViewById<TextView>(R.id.teamName).text = team.name
            counts[team.id] = card.findViewById(R.id.teamCount)
            card.strokeColor = team.color
            card.strokeWidth = ((if (team.id == current) 4 else 2) * density).toInt()
            card.setOnClickListener { choose(team) }
            list.addView(card)
        }
        listenTeamCounts()
    }

    override fun onDestroy() {
        super.onDestroy()
        playersListener?.let { playersRef?.removeEventListener(it) }
    }

    private fun listenTeamCounts() {
        val room = Session.roomId(this) ?: return
        val db = if (FirebaseSession.configured(this)) FirebaseSession.database() else null
        val ref = db?.getReference("rooms/$room/players") ?: return
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val byTeam = snapshot.children
                    .mapNotNull { it.child("team").getValue(String::class.java) }
                    .groupingBy { it }.eachCount()
                for ((id, label) in counts) {
                    val n = byTeam[id] ?: 0
                    label.text = resources.getQuantityString(R.plurals.team_players, n, n)
                }
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }
        ref.addValueEventListener(l)
        playersRef = ref
        playersListener = l
    }

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
