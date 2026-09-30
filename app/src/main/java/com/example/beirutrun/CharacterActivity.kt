package com.example.beirutrun

import android.content.Intent
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.beirutrun.city.Character
import com.example.beirutrun.city.CharacterPreview
import com.example.beirutrun.city.Characters
import com.example.beirutrun.city.DepthConfigChooser
import com.example.beirutrun.city.SoldierRig
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * After choosing a team: pick who to play as (see [Characters]), shown turning in 3D in the team's
 * colours, and whether your face photo goes on the character's head. It's off unless you turn it
 * on (then the photo is taken if there isn't one yet), and not offered for characters with a real
 * face of their own. Then on to choosing guns.
 */
class CharacterActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var preview: CharacterPreview
    private lateinit var nameLabel: TextView
    private lateinit var loading: View
    private lateinit var faceSwitch: SwitchMaterial
    private lateinit var faceHelper: TextView
    private val cards = HashMap<String, MaterialCardView>()
    private val loader = Executors.newSingleThreadExecutor()
    private val rigs = HashMap<String, Future<SoldierRig>>()
    private lateinit var chosen: Character

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_character)
        SystemBars.keepClear(this)
        StartBackground.applyTo(this)

        val team = Teams.byId(Session.teamId(this))
        preview = CharacterPreview(team?.uniform ?: 0xFF5E6266.toInt(), team?.gear ?: 0xFF3A3D40.toInt())
        glView = findViewById(R.id.characterPreview)
        glView.setEGLContextClientVersion(2)
        glView.setEGLConfigChooser(DepthConfigChooser())
        glView.setRenderer(preview)
        nameLabel = findViewById(R.id.characterName)
        loading = findViewById(R.id.characterLoading)
        faceSwitch = findViewById(R.id.characterFaceSwitch)
        faceHelper = findViewById(R.id.characterFaceHelper)

        val list = findViewById<LinearLayout>(R.id.characterList)
        val characters = Characters.all(this)
        for (c in characters) list.addView(card(c, list))
        chosen = Characters.byId(this, Session.character(this))

        faceSwitch.isChecked = Session.faceOnCharacter(this)
        faceSwitch.setOnCheckedChangeListener { _, on ->
            Session.setFaceOnCharacter(this, on)
            // Turning it on without a photo yet: take one now.
            if (on && !Session.faceFile(this).exists()) takeFace.launch(Intent(this, FaceCaptureActivity::class.java))
            showFaceOption()
        }
        findViewById<View>(R.id.characterNext).setOnClickListener {
            Session.setCharacter(this, chosen.id)
            startActivity(Intent(this, LoadoutActivity::class.java))
        }
        choose(chosen)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdownNow()
    }

    private fun card(c: Character, parent: LinearLayout): MaterialCardView {
        val density = resources.displayMetrics.density
        val card = MaterialCardView(this).apply {
            radius = 14 * density
            setCardBackgroundColor(0xE6101418.toInt())
            strokeColor = 0xFFFFB300.toInt()
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (10 * density).toInt() }
            contentDescription = c.name
            setOnClickListener { choose(c) }
        }
        card.addView(TextView(this).apply {
            text = c.name
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val pad = (14 * density).toInt()
            setPadding(pad, pad, pad, pad)
        })
        cards[c.id] = card
        return card
    }

    /** Shows [c] in the preview (loading its model the first time) and outlines its card. */
    private fun choose(c: Character) {
        chosen = c
        val density = resources.displayMetrics.density
        for ((id, card) in cards) card.strokeWidth = if (id == c.id) (3 * density).toInt() else 0
        nameLabel.text = c.name
        showFaceOption()
        val source = rigs.getOrPut(c.id) {
            loader.submit<SoldierRig> {
                SoldierRig.load(c.folder) { path -> runCatching { assets.open(path).use { it.readBytes() } }.getOrNull() }
            }
        }
        loading.visibility = View.VISIBLE
        preview.rig = null
        loader.execute {
            val rig = runCatching { source.get() }.getOrNull()
            runOnUiThread {
                if (isFinishing || chosen.id != c.id) return@runOnUiThread
                preview.rig = rig
                loading.visibility = View.GONE
            }
        }
    }

    /** The face switch, or why it's not offered for a character with a face of its own. */
    private fun showFaceOption() {
        if (chosen.ownFace) {
            faceSwitch.isEnabled = false
            faceHelper.text = getString(R.string.character_own_face, chosen.name)
        } else {
            faceSwitch.isEnabled = true
            faceHelper.setText(if (faceSwitch.isChecked) R.string.character_face_on else R.string.character_face_off)
        }
    }

    /** Coming back without a photo (cancelled): leave the face off. */
    private val takeFace = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!Session.faceFile(this).exists()) faceSwitch.isChecked = false
    }
}
