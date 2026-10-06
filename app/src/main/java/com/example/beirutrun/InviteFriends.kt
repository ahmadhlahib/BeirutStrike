package com.example.beirutrun

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.beirutrun.online.FirebaseSession
import com.example.beirutrun.online.InviteRewards
import com.example.beirutrun.online.InviteRewards.Story
import com.example.beirutrun.online.Referrals
import com.example.beirutrun.progression.Wallet
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The "Invite friends, earn cash" window (see [Referrals] for how friends are counted and paid):
 * the prizes, the friends who joined and the money earned (counting up), an invite through the
 * share menu, and a picture of the game ([StoryCard]) to post to WhatsApp Status, Facebook or
 * Instagram for a bonus each.
 *
 * It opens by itself once each time the game starts (until "Don't show this again"), always when
 * friends have joined since last time, and from the start screen's card.
 */
object InviteFriends {
    private const val TAG = "InviteFriends"
    private const val PREFS = "invite_friends"
    private const val KEY_HIDDEN = "hidden"
    /** Away in the story app at least this long counts as posting (a quick back-out doesn't). */
    private const val MIN_AWAY_MS = 6_000L
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
        var progress = start

        prize(view.findViewById(R.id.invitePrizeFriend), activity.getString(R.string.invite_promo_prize_friend, Wallet.format(InviteRewards.FRIEND)))
        prize(view.findViewById(R.id.invitePrizeStory), activity.getString(R.string.invite_promo_prize_story, Wallet.format(InviteRewards.STORY)))
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

        val buttons = mapOf(
            Story.WHATSAPP to view.findViewById<MaterialButton>(R.id.inviteStoryWhatsapp),
            Story.FACEBOOK to view.findViewById(R.id.inviteStoryFacebook),
            Story.INSTAGRAM to view.findViewById(R.id.inviteStoryInstagram),
        )
        fun label(story: Story) {
            val button = buttons.getValue(story)
            val name = activity.getString(storyName(story))
            button.text = if (story in progress.stories) activity.getString(R.string.invite_story_done, name)
            else activity.getString(R.string.invite_story_reward, name, Wallet.format(InviteRewards.STORY))
            button.alpha = if (packageFor(activity, story) == null) 0.5f else 1f
        }
        for ((story, button) in buttons) {
            label(story)
            button.setOnClickListener {
                shareStory(activity, story, paidAlready = story in progress.stories) {
                    // Back from posting: the bonus, the first time for this app.
                    Referrals.claimStory(activity, uid, story) { paid ->
                        if (gone(activity)) return@claimStory
                        val name = activity.getString(storyName(story))
                        if (!paid) return@claimStory Toast.makeText(activity, activity.getString(R.string.invite_story_already, name), Toast.LENGTH_SHORT).show()
                        val before = progress.earned
                        progress = progress.copy(stories = progress.stories + story)
                        label(story)
                        celebrate(earnedView, gainView, before, progress.earned, InviteRewards.STORY)
                        Toast.makeText(activity, activity.getString(R.string.invite_story_paid, Wallet.format(InviteRewards.STORY)), Toast.LENGTH_SHORT).show()
                        Log.i(TAG, "Story bonus for $name")
                    }
                }
            }
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

    /** Money came in: "+$300" floats up, and the total counts on with a bounce. */
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

    private fun storyName(story: Story) = when (story) {
        Story.WHATSAPP -> R.string.invite_story_whatsapp
        Story.FACEBOOK -> R.string.invite_story_facebook
        Story.INSTAGRAM -> R.string.invite_story_instagram
    }

    /** The app to post [story] with, if installed (WhatsApp Business stands in for WhatsApp). */
    private fun packageFor(activity: AppCompatActivity, story: Story): String? {
        val candidates = if (story == Story.WHATSAPP) listOf(story.packageName, "com.whatsapp.w4b") else listOf(story.packageName)
        return candidates.firstOrNull { pkg -> runCatching { activity.packageManager.getPackageInfo(pkg, 0) }.isSuccess }
    }

    /**
     * Opens [story]'s app with the game's picture and link, to post it (on WhatsApp, "My status" is
     * at the top of the list). Unless [paidAlready], [onPosted] runs on coming back after a while
     * there: the app can't see whether it was posted, so time away stands in for it.
     */
    private fun shareStory(activity: AppCompatActivity, story: Story, paidAlready: Boolean, onPosted: () -> Unit) {
        val name = activity.getString(storyName(story))
        val pkg = packageFor(activity, story)
            ?: return Toast.makeText(activity, activity.getString(R.string.invite_story_not_installed, name), Toast.LENGTH_SHORT).show()
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val image = StoryCard.create(activity, Session.name(activity))
            ?: return Toast.makeText(activity, activity.getString(R.string.invite_story_failed, name), Toast.LENGTH_SHORT).show()
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .setPackage(pkg)
            .putExtra(Intent.EXTRA_STREAM, image)
            .putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.invite_story_caption, Referrals.link(activity, uid)))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(send)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open $pkg", e)
            return Toast.makeText(activity, activity.getString(R.string.invite_story_failed, name), Toast.LENGTH_SHORT).show()
        }
        if (paidAlready) return
        val leftAt = SystemClock.elapsedRealtime()
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            // (A new observer is told "resumed" straight away: only a resume after a pause counts.)
            private var away = false
            override fun onPause(owner: LifecycleOwner) { away = true }
            override fun onResume(owner: LifecycleOwner) {
                if (!away) return
                owner.lifecycle.removeObserver(this)
                if (SystemClock.elapsedRealtime() - leftAt >= MIN_AWAY_MS) onPosted()
            }
        })
    }

    private inline fun ValueAnimator.doOnEnd(crossinline action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = action()
        })
    }
}
