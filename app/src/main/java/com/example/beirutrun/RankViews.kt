package com.example.beirutrun

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.example.beirutrun.progression.ProgressState
import com.example.beirutrun.progression.Progression
import com.example.beirutrun.progression.Rank
import com.example.beirutrun.progression.Wallet
import java.text.NumberFormat

/** How ranks are written and shown on every screen (their names, badges and XP are in Rank). */
object RankViews {

    /** "12,450", grouped the way the phone's language writes numbers. */
    fun number(n: Long): String = NumberFormat.getIntegerInstance().format(n)

    /** The rank's name in the phone's language: Arabic on an Arabic phone, else English. */
    fun localName(context: Context, rank: Rank): String =
        if (context.resources.configuration.locales[0].language == "ar") rank.arabicName else rank.englishName

    /** Both names, Arabic first: "نقيب | Captain". */
    fun bothNames(context: Context, rank: Rank): String =
        context.getString(R.string.rank_names, rank.arabicName, rank.englishName)

    /** A short label for a player with [xp], for lists: "Lv 10 · Captain". */
    fun short(context: Context, xp: Long): String {
        val s = Progression.stateFor(xp)
        return context.getString(R.string.rank_short, s.level, localName(context, s.rank))
    }

    fun badgeDescription(context: Context, rank: Rank): String =
        context.getString(R.string.rank_badge_description, rank.englishName)

    fun setBadge(image: ImageView, rank: Rank) {
        image.setImageResource(rank.badge)
        image.contentDescription = badgeDescription(image.context, rank)
    }

    /** The progress bars here go from 0 to 1000. */
    fun setProgress(bar: ProgressBar, state: ProgressState) {
        bar.progress = (state.progress * bar.max).toInt()
    }

    /** Fills in a `view_rank_card` (the home screen's rank) for [name] at [state]. */
    fun bindCard(card: View, name: String, state: ProgressState) {
        val context = card.context
        setBadge(card.findViewById(R.id.rankCardBadge), state.rank)
        card.findViewById<TextView>(R.id.rankCardName).text = name
        card.findViewById<TextView>(R.id.rankCardRank).text = bothNames(context, state.rank)
        card.findViewById<TextView>(R.id.rankCardLevel).text = context.getString(R.string.career_level, state.level)
        setProgress(card.findViewById(R.id.rankCardProgress), state)
        card.findViewById<TextView>(R.id.rankCardCash)?.text = Wallet.format(Wallet.cash(context))
        card.contentDescription = context.getString(R.string.career_open)
    }
}
