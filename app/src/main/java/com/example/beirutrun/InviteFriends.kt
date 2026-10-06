package com.example.beirutrun

import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.beirutrun.online.FirebaseSession
import com.example.beirutrun.online.InviteRewards
import com.example.beirutrun.online.Referrals
import com.example.beirutrun.progression.Wallet
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The "Invite friends, earn cash" window (see [Referrals] for how friends are counted and paid):
 * the prize, the friends who joined and the money earned (counting up), and an invite through
 * the share menu (WhatsApp first).
 *
 * It opens by itself once each time the game starts (until "Don't show this again"), always when
 * friends have joined since last time, and from the start screen's card.
 */
object InviteFriends {
    private const val TAG = "InviteFriends"
    private const val PREFS = "invite_friends"
    private const val KEY_HIDDEN = "hidden"
    private const val GOLD = 0xFFFFC83D.toInt()

    /** Opened by itself already since the app started. */
    private var shownThisRun = false

    private fun hidden(activity: AppCompatActivity) =
        activity.getSharedPreferences(PREFS, 0).getBoolean(KEY_HIDDEN, false)

    /**
     * The start screen opened: records who invited me (first start), pays for friends who joined,
     * and opens the window if they did, or once per start unless turned off.
     */
    fun onStart(activity: AppCompatActivity) {
        if (!FirebaseSession.configured(activity)) return
        FirebaseSession.signIn { uid ->
            if (uid == null || gone(activity)) return@signIn
            Referrals.register(activity, uid)
            Referrals.collect(activity, uid) { progress, paid, joined ->
                if (gone(activity)) return@collect
                if (joined > 0 || (!shownThisRun && !hidden(activity))) show(activity, uid, progress, paid, joined)
                shownThisRun = true
            }
        }
    }

    /** Opens the window (the start screen's card). */
    fun open(activity: AppCompatActivity) {
        val offline = { Toast.makeText(activity, R.string.invite_offline, Toast.LENGTH_SHORT).show() }
        if (!FirebaseSession.configured(activity)) return offline()
        FirebaseSession.signIn { uid ->
            if (gone(activity)) return@signIn
            if (uid == null) return@signIn offline()
            Referrals.collect(activity, uid, onFailed = { if (!gone(activity)) offline() }) { progress, paid, joined ->
                if (!gone(activity)) show(activity, uid, progress, paid, joined)
            }
        }
    }

    private fun gone(activity: AppCompatActivity) = activity.isFinishing || activity.isDestroyed

    /** Shows the window; [gained] was just paid for [joined] new friends (celebrated). */
    private fun show(activity: AppCompatActivity, uid: String, start: Referrals.Progress, gained: Long, joined: Int) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_invite_friends, null)
        view.clipToOutline = true
        runCatching { activity.assets.open("promo/gameplay.jpg").use { BitmapFactory.decodeStream(it) } }
            .getOrNull()?.let { view.findViewById<ImageView>(R.id.inviteHero).setImageBitmap(it) }

        val friendsView = view.findViewById<TextView>(R.id.inviteFriendsCount)
        val earnedView = view.findViewById<TextView>(R.id.inviteEarned)
        val gainView = view.findViewById<TextView>(R.id.inviteGain)
        val progress = start

        prize(view.findViewById(R.id.invitePrizeFriend), activity.getString(R.string.invite_promo_prize_friend, Wallet.format(InviteRewards.FRIEND)))
        prize(view.findViewById(R.id.invitePrizeSpend), activity.getString(R.string.invite_promo_prize_spend))

        // Friends joined since last time: the title says so, and their money comes in on top.
        if (joined > 0) {
            view.findViewById<TextView>(R.id.inviteTitle).setText(R.string.invite_friends_joined_title)
            Toast.makeText(activity, activity.resources.getQuantityString(R.plurals.invite_friends_joined, joined, joined, Wallet.format(gained)), Toast.LENGTH_LONG).show()
        }
        countUp(friendsView, 0, progress.friends.toLong(), 900L, { it.toString() })
        countUp(earnedView, 0, progress.earned - gained, 900L, Wallet::format) {
            if (gained > 0) celebrate(earnedView, gainView, progress.earned - gained, progress.earned, gained)
        }

        view.findViewById<MaterialButton>(R.id.inviteFriendsButton).setOnClickListener {
            RoomInvite.shareText(activity, activity.getString(R.string.invite_friend_message, Referrals.link(activity, uid)))
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setBackground(ColorDrawable(Color.TRANSPARENT))
            .create()
        val dontShow = view.findViewById<CheckBox>(R.id.inviteDontShow)
        dontShow.isChecked = hidden(activity)
        dialog.setOnDismissListener {
            activity.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_HIDDEN, dontShow.isChecked).apply()
        }
        view.findViewById<View>(R.id.inviteClose).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** A prize line with its amount ("+$100") in bold gold. */
    private fun prize(view: TextView, text: String) {
        val s = SpannableString(text)
        Regex("""\+\$[\d,]+""").find(text)?.let { m ->
            s.setSpan(StyleSpan(Typeface.BOLD), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(ForegroundColorSpan(GOLD), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = s
    }

    /** Counts [view] up from [from] to [to], shown with [format]; then [then]. */
    private fun countUp(view: TextView, from: Long, to: Long, ms: Long, format: (Long) -> String, then: () -> Unit = {}) {
        view.text = format(from)
        if (to <= from) return then()
        ValueAnimator.ofFloat(from.toFloat(), to.toFloat()).apply {
            duration = ms
            startDelay = 250L
            interpolator = DecelerateInterpolator()
            addUpdateListener { view.text = format((it.animatedValue as Float).toLong()) }
            doOnEnd { view.text = format(to); then() }
            start()
        }
    }

    /** Money came in: "+$50" floats up, and the total counts on with a bounce. */
    private fun celebrate(earned: TextView, gain: TextView, from: Long, to: Long, amount: Long) {
        gain.text = "+" + Wallet.format(amount)
        gain.animate().cancel()
        gain.translationY = 20f
        gain.alpha = 0f
        gain.scaleX = 0.6f
        gain.scaleY = 0.6f
        gain.animate().alpha(1f).translationY(-10f).scaleX(1.15f).scaleY(1.15f).setDuration(350)
            .setInterpolator(OvershootInterpolator())
            .withEndAction { gain.animate().alpha(0f).translationY(-50f).setStartDelay(900).setDuration(600).start() }
            .start()
        countUp(earned, from, to, 1_000L, Wallet::format)
        earned.animate().scaleX(1.25f).scaleY(1.25f).setDuration(220).withEndAction {
            earned.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(3f)).start()
        }.start()
    }

    private inline fun ValueAnimator.doOnEnd(crossinline action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = action()
        })
    }
}
