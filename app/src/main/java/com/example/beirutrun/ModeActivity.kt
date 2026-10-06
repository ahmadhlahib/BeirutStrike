package com.example.beirutrun

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.beirutrun.city.CityMapInfo
import com.example.beirutrun.city.CityMaps
import com.example.beirutrun.online.FirebaseSession
import com.example.beirutrun.online.RoomDirectory
import com.example.beirutrun.solo.BotDifficulty
import com.example.beirutrun.solo.SoloSettings
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Right after the name: play **Solo** against bots on this phone (see solo/SoloMatch), set up
 * here, or **Multiplayer** in an online room (see RoomsActivity).
 */
class ModeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mode)
        SystemBars.keepClear(this)
        StartBackground.applyTo(this)
        findViewById<TextView>(R.id.modeGreeting).text = getString(R.string.mode_greeting, Session.name(this).orEmpty())
        findViewById<View>(R.id.soloCard).setOnClickListener { showSoloSetup() }
        val multiplayer = findViewById<View>(R.id.multiplayerCard)
        multiplayer.setOnClickListener {
            Session.setSolo(this, null)
            startActivity(Intent(this, RoomsActivity::class.java))
        }
        // A build without Firebase has no online rooms.
        if (!FirebaseSession.configured(this)) {
            multiplayer.alpha = 0.5f
            findViewById<TextView>(R.id.multiplayerInfo).setText(R.string.mode_multiplayer_unavailable)
            multiplayer.isEnabled = false
        }
    }

    /** The solo game's map, play area, length, bots and difficulty; starts with the last ones chosen. */
    private fun showSoloSetup() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_solo, null)
        val last = Session.soloChoice(this)

        // One card per map; tapping one selects it.
        val maps = CityMaps.available(this).ifEmpty { listOf(CityMaps.default) }
        var chosen = maps.firstOrNull { it.id == CityMaps.byId(Session.roomMap(this)).id } ?: maps.first()
        val choices = view.findViewById<LinearLayout>(R.id.mapChoices)
        val cards = maps.map { map ->
            val card = LayoutInflater.from(this).inflate(R.layout.item_map_choice, choices, false) as MaterialCardView
            card.findViewById<ImageView>(R.id.mapPreview).setImageBitmap(preview(map))
            card.findViewById<TextView>(R.id.mapName).text = map.name
            choices.addView(card)
            map to card
        }
        val density = resources.displayMetrics.density
        fun highlight() = cards.forEach { (map, card) -> card.strokeWidth = if (map == chosen) (3 * density).toInt() else 0 }
        cards.forEach { (map, card) -> card.setOnClickListener { chosen = map; highlight() } }
        highlight()

        // Play area: smaller is livelier with a few bots.
        var size: Int? = CityMaps.sizeOf(Session.roomMap(this)) ?: SOLO_SIZE
        val sizeGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.sizeChoices)
        for (s in CityMaps.sizes) {
            val button = LayoutInflater.from(this).inflate(R.layout.item_size_choice, sizeGroup, false) as MaterialButton
            button.id = View.generateViewId()
            button.text = if (s == null) getString(R.string.map_size_full) else getString(R.string.map_size_metres, s)
            sizeGroup.addView(button)
            if (s == size) sizeGroup.check(button.id)
            button.setOnClickListener { size = s }
        }

        var duration = last.durationMs.takeIf { it in RoomDirectory.durations } ?: RoomDirectory.DEFAULT_DURATION_MS
        val durationGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.durationChoices)
        for (d in RoomDirectory.durations) {
            val button = LayoutInflater.from(this).inflate(R.layout.item_size_choice, durationGroup, false) as MaterialButton
            button.id = View.generateViewId()
            button.text = durationLabel(d)
            durationGroup.addView(button)
            if (d == duration) durationGroup.check(button.id)
            button.setOnClickListener { duration = d }
        }

        val botsLabel = view.findViewById<TextView>(R.id.soloBotsLabel)
        val bots = view.findViewById<Slider>(R.id.soloBots)
        bots.valueFrom = SoloSettings.MIN_BOTS.toFloat()
        bots.valueTo = SoloSettings.MAX_BOTS.toFloat()
        bots.value = last.bots.toFloat()
        fun showBots() { botsLabel.text = resources.getQuantityString(R.plurals.solo_bots, bots.value.toInt(), bots.value.toInt()) }
        bots.addOnChangeListener { _, _, _ -> showBots() }
        showBots()

        var difficulty = last.difficulty
        val difficultyGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.soloDifficulty)
        val difficultyHelper = view.findViewById<TextView>(R.id.soloDifficultyHelper)
        val buttons = mapOf(BotDifficulty.EASY to R.id.soloEasy, BotDifficulty.MEDIUM to R.id.soloMedium, BotDifficulty.HARD to R.id.soloHard)
        fun showDifficulty() {
            difficultyHelper.setText(when (difficulty) {
                BotDifficulty.EASY -> R.string.solo_easy_info
                BotDifficulty.MEDIUM -> R.string.solo_medium_info
                BotDifficulty.HARD -> R.string.solo_hard_info
            })
        }
        difficultyGroup.check(buttons.getValue(difficulty))
        for ((d, id) in buttons) view.findViewById<MaterialButton>(id).setOnClickListener { difficulty = d; showDifficulty() }
        showDifficulty()

        val allies = view.findViewById<SwitchMaterial>(R.id.soloAllies)
        val alliesHelper = view.findViewById<TextView>(R.id.soloAlliesHelper)
        allies.isChecked = last.allies
        fun showAllies() { alliesHelper.setText(if (allies.isChecked) R.string.solo_allies_on else R.string.solo_allies_off) }
        allies.setOnCheckedChangeListener { _, _ -> showAllies() }
        showAllies()

        // Enemy areas on the map: on unless turned off (here or in the game's menu).
        val enemyAreas = view.findViewById<SwitchMaterial>(R.id.soloEnemyAreas)
        enemyAreas.isChecked = Session.enemyAreas(this)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.solo_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.solo_start) { _, _ ->
                val settings = SoloSettings(bots.value.toInt(), difficulty, allies.isChecked, duration)
                Session.setEnemyAreas(this, enemyAreas.isChecked)
                Session.setSolo(this, settings, CityMaps.roomValue(chosen, size))
                // Then the team, character and guns, as for an online game.
                startActivity(Intent(this, TeamSelectActivity::class.java))
            }
            .show()
    }

    private fun durationLabel(ms: Long): String {
        val seconds = (ms / 1000).toInt()
        return when {
            seconds < 60 -> getString(R.string.duration_seconds, seconds)
            seconds < 3600 -> getString(R.string.duration_minutes, seconds / 60)
            else -> getString(R.string.duration_hours, seconds / 3600)
        }
    }

    private val previews = HashMap<String, Bitmap?>()
    private fun preview(map: CityMapInfo): Bitmap? = previews.getOrPut(map.id) { map.loadPreview(this) }

    private companion object {
        /** The play area a solo game starts with, metres: a few bots soon find you in it. */
        const val SOLO_SIZE = 400
    }
}
