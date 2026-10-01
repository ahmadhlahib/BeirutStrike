package com.example.beirutrun

import android.animation.ObjectAnimator
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.beirutrun.online.FirebaseSession
import com.example.beirutrun.online.PlayerStats
import com.example.beirutrun.progression.PlayerProgress
import com.example.beirutrun.progression.ProgressState
import com.example.beirutrun.progression.Rank
import com.example.beirutrun.progression.XpGain
import com.example.beirutrun.progression.XpReward
import com.google.android.material.imageview.ShapeableImageView

/**
 * Ranks / career: the player's military rank, level and XP towards the next rank (see
 * PlayerProgress), their career stats from `career/{uid}`, and all 20 ranks: completed, current
 * and locked. Tapping the badge replays the rank-up; in debug builds a long press adds XP, to try
 * rank-ups out.
 */
class CareerActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private val onXp = PlayerProgress.Listener { gain -> onXpGained(gain) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_career)
        SystemBars.keepClear(this)
        progressBar = findViewById(R.id.careerProgress)

        findViewById<TextView>(R.id.careerName).text = Session.name(this).orEmpty()
        Session.loadFace(this)?.let { findViewById<ShapeableImageView>(R.id.careerFace).setImageBitmap(it) }
        findViewById<TextView>(R.id.careerXpRules).text = getString(
            R.string.career_xp_rules, XpReward.ELIMINATION.xp, XpReward.HEADSHOT.xp, XpReward.VICTORY.xp,
        )

        val badge = findViewById<ImageView>(R.id.careerBadge)
        badge.setOnClickListener { RankUpOverlay.show(this, PlayerProgress.state(this).rank, blocking = true) }
        badge.tooltipText = getString(R.string.career_replay_hint)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) badge.setOnLongClickListener {
            PlayerProgress.addXp(this, DEBUG_XP)
            Toast.makeText(this, getString(R.string.career_debug_xp, DEBUG_XP), Toast.LENGTH_SHORT).show()
            true
        }

        val state = PlayerProgress.state(this)
        show(state)
        showStats(null)
        loadStats()
        animateIn(state)
    }

    override fun onStart() {
        super.onStart()
        PlayerProgress.observe(onXp)
    }

    override fun onStop() {
        super.onStop()
        PlayerProgress.stopObserving(onXp)
    }

    private fun onXpGained(gain: XpGain) {
        show(gain.after)
        if (gain.rankedUp) RankUpOverlay.show(this, gain.after.rank, blocking = true)
    }

    /** The header (rank, level, XP bar, next rank) and the list of ranks for [state]. */
    private fun show(state: ProgressState) {
        val rank = state.rank
        RankViews.setBadge(findViewById(R.id.careerBadge), rank)
        findViewById<TextView>(R.id.careerRankArabic).text = rank.arabicName
        findViewById<TextView>(R.id.careerRankEnglish).text = rank.englishName
        findViewById<TextView>(R.id.careerLevel).text = getString(R.string.career_level, state.level)
        findViewById<TextView>(R.id.careerTotalXp).text = getString(R.string.career_xp_total, RankViews.number(state.totalXp))
        RankViews.setProgress(progressBar, state)

        val xp = findViewById<TextView>(R.id.careerXp)
        val toNext = findViewById<TextView>(R.id.careerXpToNext)
        val label = findViewById<TextView>(R.id.careerNextLabel)
        val nextArabic = findViewById<TextView>(R.id.careerNextArabic)
        val nextEnglish = findViewById<TextView>(R.id.careerNextEnglish)
        val nextBadge = findViewById<ImageView>(R.id.careerNextBadge)
        val next = state.nextRank
        if (next == null) {
            xp.text = getString(R.string.career_xp_total, RankViews.number(state.totalXp))
            toNext.setText(R.string.career_max_rank)
            label.setText(R.string.career_max_rank)
            nextArabic.text = rank.arabicName
            nextEnglish.setText(R.string.career_max_rank_detail)
            nextEnglish.isAllCaps = false
            nextBadge.visibility = View.GONE
        } else {
            xp.text = getString(R.string.career_xp_progress, RankViews.number(state.totalXp), RankViews.number(next.xpRequired.toLong()))
            toNext.text = getString(R.string.career_xp_to_next, RankViews.number(state.xpToNextLevel))
            label.setText(R.string.career_next_rank)
            nextArabic.text = next.arabicName
            nextEnglish.text = next.englishName
            nextEnglish.isAllCaps = true
            nextBadge.visibility = View.VISIBLE
            RankViews.setBadge(nextBadge, next)
        }
        showRanks(state)
    }

    /** Every rank, lowest first: completed ones ticked, the current one in gold, the rest locked. */
    private fun showRanks(state: ProgressState) {
        val list = findViewById<LinearLayout>(R.id.careerRanks)
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val gold = ContextCompat.getColor(this, R.color.rank_gold)
        val dim = ContextCompat.getColor(this, R.color.rank_text_dim)
        for (r in Rank.entries) {
            val row = inflater.inflate(R.layout.item_rank_row, list, false)
            RankViews.setBadge(row.findViewById(R.id.rankBadge), r)
            row.findViewById<TextView>(R.id.rankLevel).text = getString(R.string.career_level, r.level)
            row.findViewById<TextView>(R.id.rankArabic).text = r.arabicName
            row.findViewById<TextView>(R.id.rankEnglish).text = r.englishName
            val icon = row.findViewById<ImageView>(R.id.rankStatusIcon)
            val status = row.findViewById<TextView>(R.id.rankStatus)
            when {
                r.level < state.level -> {
                    icon.setImageResource(R.drawable.ic_rank_check)
                    icon.setColorFilter(ContextCompat.getColor(this, R.color.rank_silver))
                    status.setText(R.string.rank_status_completed)
                    status.setTextColor(dim)
                }
                r.level == state.level -> {
                    row.setBackgroundResource(R.drawable.bg_rank_row_current)
                    icon.setImageResource(R.drawable.ic_rank_star)
                    icon.setColorFilter(gold)
                    status.setText(R.string.rank_status_current)
                    status.setTextColor(gold)
                    row.findViewById<TextView>(R.id.rankLevel).setTextColor(gold)
                    row.findViewById<TextView>(R.id.rankArabic).setTextColor(ContextCompat.getColor(this, R.color.rank_gold_light))
                }
                else -> {
                    icon.setImageResource(R.drawable.ic_lock)
                    icon.setColorFilter(dim)
                    status.text = getString(R.string.rank_status_required, RankViews.number(r.xpRequired.toLong()))
                    status.setTextColor(dim)
                    // Dimmed, the badge greyer still, so what's still to earn reads as locked.
                    row.alpha = LOCKED_ALPHA
                    row.findViewById<View>(R.id.rankBadge).alpha = 0.6f
                }
            }
            list.addView(row)
        }
    }

    /** Kills, deaths, K/D, accuracy and wins; [stats] null until the career has loaded (or offline). */
    private fun showStats(stats: PlayerStats?) {
        val row = findViewById<LinearLayout>(R.id.careerStats)
        row.removeAllViews()
        val none = "–"
        val cells = listOf(
            R.string.score_col_kills to (stats?.kills?.toString() ?: none),
            R.string.score_col_deaths to (stats?.deaths?.toString() ?: none),
            R.string.score_col_ratio to (stats?.let { Scoreboard.ratio(it.kills, it.deaths) } ?: none),
            R.string.score_col_accuracy to (stats?.let { Scoreboard.percent(it.accuracy) } ?: none),
            // Wins are counted on this phone too, so they show before the career loads.
            R.string.career_stat_wins to maxOf(stats?.wins ?: 0, PlayerProgress.wins(this)).toString(),
        )
        for ((label, value) in cells) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            cell.addView(TextView(this).apply {
                text = value
                textSize = 18f
                setTextColor(ContextCompat.getColor(this@CareerActivity, R.color.white))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                fontFeatureSettings = "tnum"
            })
            cell.addView(TextView(this).apply {
                setText(label)
                textSize = 11f
                isAllCaps = true
                letterSpacing = 0.08f
                setTextColor(ContextCompat.getColor(this@CareerActivity, R.color.rank_text_dim))
            })
            row.addView(cell)
        }
    }

    /** Reads my `career/{uid}` once for the stats (the same counts the Ranking screen shows). */
    private fun loadStats() {
        val note = findViewById<View>(R.id.careerStatsNote)
        if (!FirebaseSession.configured(this)) {
            note.visibility = View.VISIBLE
            return
        }
        FirebaseSession.signIn { uid ->
            if (isFinishing || isDestroyed) return@signIn
            val database = FirebaseSession.database()
            if (uid == null || database == null) {
                note.visibility = View.VISIBLE
                return@signIn
            }
            database.getReference("career/$uid").get()
                .addOnSuccessListener { snap ->
                    if (isFinishing || isDestroyed) return@addOnSuccessListener
                    val stats = if (snap.exists()) PlayerStats.from(snap) else null
                    showStats(stats)
                    note.visibility = if (stats == null) View.VISIBLE else View.GONE
                }
                .addOnFailureListener { if (!isFinishing) note.visibility = View.VISIBLE }
        }
    }

    /** The badge lands, the glow swells and the XP bar fills up to where the player is. */
    private fun animateIn(state: ProgressState) {
        findViewById<View>(R.id.careerBadge).apply {
            scaleX = 0.6f
            scaleY = 0.6f
            alpha = 0f
            animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(550).setInterpolator(OvershootInterpolator(1.4f)).start()
        }
        findViewById<View>(R.id.careerGlow).apply {
            alpha = 0f
            animate().alpha(1f).setStartDelay(200).setDuration(700).start()
        }
        val target = (state.progress * progressBar.max).toInt()
        ObjectAnimator.ofInt(progressBar, "progress", 0, target).apply {
            startDelay = 300
            duration = 900
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private companion object {
        const val LOCKED_ALPHA = 0.5f
        /** XP a long press on the badge adds, in debug builds. */
        const val DEBUG_XP = 500
    }
}
