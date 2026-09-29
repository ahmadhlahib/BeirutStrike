package com.example.beirutrun

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.beirutrun.online.FirebaseSession
import com.example.beirutrun.online.PlayerStats
import com.google.android.material.card.MaterialCardView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener

/**
 * Every player who has played online, ranked by career score (successful shots over all games),
 * from `career/{uid}` (see OnlineWorld). Shows their face, army rank (see [Army]), shots, kills
 * and accuracy, and keeps up to date while open.
 */
class RankingActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private val adapter = RankingAdapter()
    private var query: Query? = null
    private var listener: ValueEventListener? = null
    private var myUid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ranking)
        StartBackground.applyTo(this)
        status = findViewById(R.id.rankingStatus)
        findViewById<TextView>(R.id.rankingRule).text = getString(R.string.ranking_rule, Army.COMMANDER_HITS)
        val list = findViewById<ListView>(R.id.rankingList)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.rankingEmpty)

        if (!FirebaseSession.configured(this)) {
            status.setText(R.string.status_offline)
            return
        }
        status.setText(R.string.status_connecting)
        FirebaseSession.signIn { uid ->
            if (isFinishing) return@signIn
            if (uid == null) return@signIn status.setText(R.string.status_failed)
            myUid = uid
            listen()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.let { query?.removeEventListener(it) }
    }

    private fun listen() {
        val database = FirebaseSession.database() ?: return status.setText(R.string.status_failed)
        val q = database.getReference("career").orderByChild("hits").limitToLast(MAX_PLAYERS)
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                adapter.players = Scoreboard.ranked(snapshot.children.mapNotNull(PlayerStats::from))
                status.text = resources.getQuantityString(R.plurals.ranking_count, adapter.players.size, adapter.players.size)
            }

            override fun onCancelled(error: DatabaseError) {
                status.setText(R.string.status_failed)
            }
        }
        q.addValueEventListener(l)
        query = q
        listener = l
    }

    private inner class RankingAdapter : BaseAdapter() {
        var players: List<PlayerStats> = emptyList()
            set(value) { field = value; notifyDataSetChanged() }

        override fun getCount() = players.size
        override fun getItem(position: Int) = players[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_ranking, parent, false)
            val p = players[position]
            val mine = p.uid == myUid
            view.findViewById<TextView>(R.id.rankingPosition).apply {
                text = (position + 1).toString()
                setTextColor(if (position < PODIUM) PODIUM_COLOR else WHITE)
            }
            FaceBadge.bind(view.findViewById(R.id.rankingFace), p.uid, myUid, p.score) { notifyDataSetChanged() }
            view.findViewById<TextView>(R.id.rankingName).text =
                if (mine) getString(R.string.score_you, p.name) else p.name
            view.findViewById<TextView>(R.id.rankingArmyRank).text = Army.title(this@RankingActivity, p.score)
            view.findViewById<TextView>(R.id.rankingScore).text = p.score.toString()
            view.findViewById<TextView>(R.id.rankingDetails).text = getString(
                R.string.ranking_details, p.shots, p.kills, Scoreboard.percent(p.accuracy),
            )
            // My own card is outlined.
            (view as MaterialCardView).strokeWidth = if (mine) (2 * resources.displayMetrics.density).toInt() else 0
            return view
        }
    }

    companion object {
        /** The ranking shows at most this many players (the best ones). */
        private const val MAX_PLAYERS = 500
        /** The top three get a gold position number. */
        private const val PODIUM = 3
        private const val PODIUM_COLOR = 0xFFFFC107.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
    }
}
