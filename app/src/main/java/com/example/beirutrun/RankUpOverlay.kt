package com.example.beirutrun

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.example.beirutrun.progression.Rank

/**
 * The rank-up celebration: "RANK UP", the new badge flying in over a turning burst of light and a
 * pulsing glow, then the rank's names and level. It is laid over the activity's whole screen and
 * goes away by itself.
 */
object RankUpOverlay {
    private const val TAG = "rankUpOverlay"
    private const val SHOW_MS = 4_200L

    /**
     * Shows [rank] reached. [blocking] false (in a game) lets touches through to the screen
     * underneath; true (elsewhere) closes it on a tap. [playSound] is the hook for a rank-up sound
     * (e.g. SoundEffects.rankUp), called as the badge lands.
     */
    fun show(activity: Activity, rank: Rank, blocking: Boolean = false, playSound: (() -> Unit)? = null) {
        if (activity.isFinishing) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        // A newer rank-up replaces one still showing.
        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }

        val view = LayoutInflater.from(activity).inflate(R.layout.view_rank_up, root, false)
        view.tag = TAG
        val badge = view.findViewById<ImageView>(R.id.rankUpBadge)
        RankViews.setBadge(badge, rank)
        view.findViewById<TextView>(R.id.rankUpArabic).text = rank.arabicName
        view.findViewById<TextView>(R.id.rankUpEnglish).text = rank.englishName
        view.findViewById<TextView>(R.id.rankUpLevel).text = activity.getString(R.string.career_level, rank.level)
        var closing = false
        val close = {
            if (!closing && view.parent === root) {
                closing = true
                view.animate().alpha(0f).setStartDelay(0).setDuration(300).withEndAction { root.removeView(view) }.start()
            }
        }
        if (blocking) {
            view.isClickable = true
            view.setOnClickListener { close() }
        } else {
            view.isClickable = false
            view.isFocusable = false
        }
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        view.contentDescription = activity.getString(R.string.rank_up_title) + ": " + RankViews.bothNames(activity, rank)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val loops = animate(view, badge)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = loops.forEach { it.cancel() }
        })
        playSound?.let { sound -> view.postDelayed({ if (view.isAttachedToWindow) sound() }, 250) }
        view.postDelayed({ close() }, SHOW_MS)
    }

    /** Starts the entrance; returns the endless animations (rays, glow pulse) to stop later. */
    private fun animate(view: View, badge: View): List<Animator> {
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(200).start()

        view.findViewById<View>(R.id.rankUpTitle).apply {
            alpha = 0f
            translationY = -40f
            animate().alpha(1f).translationY(0f).setDuration(450).setInterpolator(DecelerateInterpolator()).start()
        }

        // The badge drops in big, turns upright and settles with a little overshoot.
        badge.apply {
            alpha = 0f
            scaleX = 2.2f
            scaleY = 2.2f
            rotation = -18f
            animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(200).setDuration(650)
                .setInterpolator(OvershootInterpolator(1.6f)).start()
        }

        val glow = view.findViewById<View>(R.id.rankUpGlow).apply {
            alpha = 0f
            scaleX = 0.4f
            scaleY = 0.4f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(350).setDuration(600).start()
        }
        val pulse = ObjectAnimator.ofFloat(glow, View.ALPHA, 1f, 0.55f).apply {
            startDelay = 950
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        val rays = view.findViewById<View>(R.id.rankUpRays)
        rays.alpha = 0f
        rays.animate().alpha(1f).setStartDelay(400).setDuration(700).start()
        val turn = ObjectAnimator.ofFloat(rays, View.ROTATION, 0f, 360f).apply {
            duration = 16_000
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        // Then the names, level and "NEW RANK ACHIEVED", one after another.
        listOf(R.id.rankUpArabic, R.id.rankUpEnglish, R.id.rankUpLevel, R.id.rankUpSubtitle).forEachIndexed { i, id ->
            view.findViewById<View>(id).apply {
                alpha = 0f
                translationY = 24f
                animate().alpha(1f).translationY(0f).setStartDelay(700L + i * 150L).setDuration(400)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
        return listOf(pulse, turn)
    }
}
