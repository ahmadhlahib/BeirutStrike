package com.example.beirutrun

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.beirutrun.city.GunIcon
import com.example.beirutrun.city.GunPhotos
import com.example.beirutrun.city.GunSlot
import com.example.beirutrun.city.Weapon
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * After choosing a team: pick the three guns to carry, a pistol, a primary and a sniper rifle.
 * Each card shows the gun, its magazine, fire rate, reload time and scope, and bars comparing its
 * damage, fire rate, range, accuracy and mobility with every other gun. Then on to the city
 * (taking a face photo first if there isn't one yet).
 */
class LoadoutActivity : AppCompatActivity() {

    private val chosen = HashMap<GunSlot, Weapon>()
    private val cards = HashMap<Weapon, MaterialCardView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_loadout)
        SystemBars.keepClear(this)
        StartBackground.applyTo(this)

        val sections = findViewById<LinearLayout>(R.id.loadoutSections)
        for (slot in GunSlot.entries) {
            chosen[slot] = Session.gun(this, slot)
            sections.addView(heading(slot))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (gun in Weapon.inSlot(slot)) row.addView(card(gun, row))
            sections.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            })
        }
        highlight()
        findViewById<View>(R.id.loadoutPlay).setOnClickListener { play() }
        findViewById<View>(R.id.loadoutCredits).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.loadout_photo_credits)
                .setMessage(GunPhotos.credits(this))
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun heading(slot: GunSlot) = TextView(this).apply {
        setText(when (slot) {
            GunSlot.PISTOL -> R.string.loadout_pistol
            GunSlot.PRIMARY -> R.string.loadout_primary
            GunSlot.SNIPER -> R.string.loadout_sniper
        })
        setTextColor(Color.WHITE)
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun card(gun: Weapon, parent: LinearLayout): MaterialCardView {
        val card = LayoutInflater.from(this).inflate(R.layout.item_gun, parent, false) as MaterialCardView
        card.findViewById<ImageView>(R.id.gunPicture).setImageBitmap(
            GunPhotos.load(this, gun) ?: GunIcon.draw(gun, dp(206), dp(90)))
        card.findViewById<TextView>(R.id.gunName).text = gun.displayName
        card.findViewById<TextView>(R.id.gunFacts).text = facts(gun)
        val stats = card.findViewById<LinearLayout>(R.id.gunStats)
        stats.addView(bar(R.string.stat_damage, gun.damage / 5f))
        stats.addView(bar(R.string.stat_fire_rate, (1f / gun.fireInterval) / MAX_SHOTS_PER_SECOND))
        stats.addView(bar(R.string.stat_range, gun.range / MAX_RANGE))
        stats.addView(bar(R.string.stat_accuracy, accuracy(gun)))
        stats.addView(bar(R.string.stat_mobility, (gun.moveSpeed - 0.7f) / 0.4f))
        card.contentDescription = gun.displayName
        card.setOnClickListener {
            chosen[gun.slot] = gun
            highlight()
        }
        cards[gun] = card
        return card
    }

    /** "30 × 4 rounds · 600/min · reload 2.6 s · 4× scope" */
    private fun facts(gun: Weapon): String {
        val parts = mutableListOf(getString(R.string.gun_rounds, gun.magazine, gun.magazines))
        parts += if (gun.automatic) getString(R.string.gun_rpm, gun.roundsPerMinute)
            else getString(if (gun.boltAction) R.string.gun_bolt else R.string.gun_semi)
        parts += getString(R.string.gun_reload, gun.reloadSeconds)
        if (gun.hasScope) parts += getString(R.string.gun_scope, gun.zooms.joinToString("/") { "${it.toInt()}×" })
        return parts.joinToString(" · ")
    }

    /** How tight its shots are where it's meant to be used: through the scope for sniper rifles. */
    private fun accuracy(gun: Weapon): Float {
        val spread = if (gun.slot == GunSlot.SNIPER) gun.scopedSpread else gun.hipSpread
        return 1f - spread / WORST_SPREAD
    }

    /** "Range ▮▮▮▮▯▯" as a label and a filled bar, [value] 0..1. */
    private fun bar(label: Int, value: Float): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(1), 0, dp(1))
        }
        row.addView(TextView(this).apply {
            setText(label)
            setTextColor(0xBBFFFFFF.toInt())
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(dp(62), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x33FFFFFF)
            weightSum = 1f
            layoutParams = LinearLayout.LayoutParams(0, dp(6), 1f)
        }
        val v = value.coerceIn(0.05f, 1f)
        track.addView(View(this).apply {
            setBackgroundColor(0xFFFFB300.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, v)
        })
        row.addView(track)
        return row
    }

    /** The chosen gun in each slot is outlined. */
    private fun highlight() {
        for ((gun, card) in cards) card.strokeWidth = if (chosen[gun.slot] == gun) dp(3) else 0
    }

    private fun play() {
        chosen.values.forEach { Session.setGun(this, it) }
        if (Session.faceFile(this).exists()) openCity() else takeFace.launch(Intent(this, FaceCaptureActivity::class.java))
    }

    /** Skipping the photo is fine too: the soldier keeps their own face. */
    private val takeFace = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { openCity() }

    private fun openCity() {
        startActivity(Intent(this, CityActivity::class.java))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** The top of each bar: the fastest-firing gun, the longest range, the widest spread. */
        val MAX_SHOTS_PER_SECOND = Weapon.entries.maxOf { 1f / it.fireInterval }
        val MAX_RANGE = Weapon.entries.maxOf { it.range }
        const val WORST_SPREAD = 0.03f
    }
}
