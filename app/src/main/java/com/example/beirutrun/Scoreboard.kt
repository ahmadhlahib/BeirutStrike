package com.example.beirutrun

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.beirutrun.online.PlayerStats

/**
 * The room's scoreboard, full screen over the city: team totals, then every player ranked by
 * kills (fewer deaths first on a tie). It stays up to date while it's open, so it can be watched
 * in the middle of a game as well as at the end.
 */
class Scoreboard(
    private val activity: Activity,
    /** My user id, to highlight my row. */
    private val myUid: () -> String?,
    private val onLeave: () -> Unit,
) {
    private var dialog: Dialog? = null
    private var view: View? = null
    private var stats: List<PlayerStats> = emptyList()
    private var gameOver = false
    private var timeLeft: String? = null

    val isShowing get() = dialog?.isShowing == true

    /** Opens the scoreboard (or updates the open one); [over] shows it as the end-of-game results. */
    fun show(over: Boolean) {
        gameOver = over
        if (dialog == null) {
            val v = LayoutInflater.from(activity).inflate(R.layout.dialog_scoreboard, null)
            val d = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            d.setContentView(v)
            v.findViewById<View>(R.id.scoreClose).setOnClickListener { d.dismiss() }
            v.findViewById<View>(R.id.scoreLeave).setOnClickListener {
                d.dismiss()
                onLeave()
            }
            d.setOnDismissListener { dialog = null; view = null }
            dialog = d
            view = v
            d.show()
        }
        render()
    }

    fun dismiss() = dialog?.dismiss()

    fun update(newStats: List<PlayerStats>) {
        stats = newStats
        render()
    }

    /** The countdown shown under the title while the game is on (null = no time limit). */
    fun setTimeLeft(text: String?) {
        if (text == timeLeft) return
        timeLeft = text
        if (!gameOver) render()
    }

    private fun render() {
        val v = view ?: return
        v.findViewById<TextView>(R.id.scoreTitle)
            .setText(if (gameOver) R.string.score_game_over else R.string.score_title)
        v.findViewById<TextView>(R.id.scoreSubtitle).apply {
            text = if (gameOver) winnerText() else timeLeft?.let { activity.getString(R.string.score_time_left, it) }
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        val players = ranked(stats)
        val teams = teamTotals(stats)
        v.findViewById<View>(R.id.scoreEmpty).visibility = if (players.isEmpty()) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.teamsHeading).visibility = if (teams.isEmpty()) View.GONE else View.VISIBLE

        val teamRows = v.findViewById<LinearLayout>(R.id.teamRows)
        teamRows.removeAllViews()
        if (teams.isNotEmpty()) teamRows.addView(headerRow(teamRows, R.string.score_col_team, R.string.score_col_players))
        teams.forEachIndexed { i, t ->
            teamRows.addView(row(teamRows, i + 1, t.team, Teams.byId(t.team)?.name ?: t.team,
                activity.resources.getQuantityString(R.plurals.team_players, t.players, t.players), t.total, mine = false))
        }

        val playerRows = v.findViewById<LinearLayout>(R.id.playerRows)
        playerRows.removeAllViews()
        if (players.isNotEmpty()) playerRows.addView(headerRow(playerRows, R.string.score_col_name, R.string.score_col_team))
        val me = myUid()
        players.forEachIndexed { i, p ->
            playerRows.addView(row(playerRows, i + 1, p.team, p.name, Teams.byId(p.team)?.name ?: p.team, p, mine = p.uid == me))
        }
    }

    /** "Winner: Ali (7 kills) · Winning team: …", or a draw. */
    private fun winnerText(): String {
        val players = ranked(stats)
        val best = players.firstOrNull()
        if (best == null || best.kills == 0) return activity.getString(R.string.score_draw)
        val parts = mutableListOf<String>()
        val second = players.getOrNull(1)
        parts += if (second != null && second.kills == best.kills && second.deaths == best.deaths) {
            activity.getString(R.string.score_draw)
        } else {
            activity.getString(R.string.score_winner, best.name,
                activity.resources.getQuantityString(R.plurals.score_kills, best.kills, best.kills))
        }
        val teams = teamTotals(stats)
        if (teams.size > 1 && teams[0].total.kills > teams[1].total.kills) {
            parts += activity.getString(R.string.score_winning_team, Teams.byId(teams[0].team)?.name ?: teams[0].team)
        }
        return parts.joinToString(" · ")
    }

    private fun headerRow(parent: ViewGroup, nameLabel: Int, teamLabel: Int): View {
        val r = LayoutInflater.from(activity).inflate(R.layout.item_score_row, parent, false)
        val labels = mapOf(
            R.id.scoreRank to "#",
            R.id.scoreName to activity.getString(nameLabel),
            R.id.scoreTeam to activity.getString(teamLabel),
            R.id.scoreKills to activity.getString(R.string.score_col_kills),
            R.id.scoreDeaths to activity.getString(R.string.score_col_deaths),
            R.id.scoreRatio to activity.getString(R.string.score_col_ratio),
            R.id.scoreShots to activity.getString(R.string.score_col_shots),
            R.id.scoreHits to activity.getString(R.string.score_col_hits),
            R.id.scoreAccuracy to activity.getString(R.string.score_col_accuracy),
            R.id.scoreHitsTaken to activity.getString(R.string.score_col_hits_taken),
        )
        for ((id, label) in labels) r.findViewById<TextView>(id).apply {
            text = label
            setTextColor(HEADER_COLOR)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        }
        r.findViewById<View>(R.id.scoreSwatch).visibility = View.INVISIBLE
        return r
    }

    private fun row(parent: ViewGroup, rank: Int, team: String, name: String, teamLabel: String, s: PlayerStats, mine: Boolean): View {
        val r = LayoutInflater.from(activity).inflate(R.layout.item_score_row, parent, false)
        r.findViewById<TextView>(R.id.scoreRank).text = rank.toString()
        r.findViewById<View>(R.id.scoreSwatch).setBackgroundColor(Teams.byId(team)?.color ?: UNKNOWN_TEAM_COLOR)
        r.findViewById<TextView>(R.id.scoreName).apply {
            text = if (mine) activity.getString(R.string.score_you, name) else name
            setTypeface(typeface, Typeface.BOLD)
        }
        r.findViewById<TextView>(R.id.scoreTeam).text = teamLabel
        r.findViewById<TextView>(R.id.scoreKills).apply {
            text = s.kills.toString()
            setTypeface(typeface, Typeface.BOLD)
        }
        r.findViewById<TextView>(R.id.scoreDeaths).text = s.deaths.toString()
        r.findViewById<TextView>(R.id.scoreRatio).text = ratio(s.kills, s.deaths)
        r.findViewById<TextView>(R.id.scoreShots).text = s.shots.toString()
        r.findViewById<TextView>(R.id.scoreHits).text = s.hits.toString()
        r.findViewById<TextView>(R.id.scoreAccuracy).text = "${(s.accuracy * 100).toInt()}%"
        r.findViewById<TextView>(R.id.scoreHitsTaken).text = s.hitsTaken.toString()
        if (mine) r.setBackgroundColor(MY_ROW_COLOR)
        return r
    }

    private class TeamTotal(val team: String, val players: Int, val total: PlayerStats)

    companion object {
        private const val HEADER_COLOR = 0xAAFFFFFF.toInt()
        private const val MY_ROW_COLOR = 0x33FFC107
        private const val UNKNOWN_TEAM_COLOR = 0xFF777777.toInt()

        /** Most kills first; on a tie, fewer deaths, then more hits. */
        fun ranked(stats: List<PlayerStats>): List<PlayerStats> = stats.sortedWith(
            compareByDescending<PlayerStats> { it.kills }.thenBy { it.deaths }.thenByDescending { it.hits }.thenBy { it.name.lowercase() }
        )

        private fun teamTotals(stats: List<PlayerStats>): List<TeamTotal> = stats
            .filter { it.team.isNotEmpty() }
            .groupBy { it.team }
            .map { (team, members) ->
                TeamTotal(team, members.size, PlayerStats(
                    uid = team, name = team, team = team,
                    kills = members.sumOf { it.kills },
                    deaths = members.sumOf { it.deaths },
                    shots = members.sumOf { it.shots },
                    hits = members.sumOf { it.hits },
                    hitsTaken = members.sumOf { it.hitsTaken },
                ))
            }
            .sortedWith(compareByDescending<TeamTotal> { it.total.kills }.thenBy { it.total.deaths })

        /** Kills per death: "3.5", or just the kills while there are no deaths. */
        fun ratio(kills: Int, deaths: Int): String =
            if (deaths == 0) kills.toString() else "%.1f".format(kills.toFloat() / deaths)
    }
}
