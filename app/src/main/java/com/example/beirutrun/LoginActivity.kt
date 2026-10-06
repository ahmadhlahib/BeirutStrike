package com.example.beirutrun

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.beirutrun.online.AppVersion
import com.example.beirutrun.online.CareerWallet
import com.example.beirutrun.online.FirebaseSession
import com.example.beirutrun.online.GoogleAccount
import com.example.beirutrun.progression.PlayerProgress
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class LoginActivity : AppCompatActivity() {

    private lateinit var nameInput: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        SystemBars.keepClear(this)
        StartBackground.applyTo(this)

        val nameLayout = findViewById<TextInputLayout>(R.id.nameLayout)
        nameInput = findViewById(R.id.nameInput)
        val enter = findViewById<MaterialButton>(R.id.enterButton)

        nameInput.setText(Session.name(this))

        // Opened from a room invite: remember it, and with a name already, go straight to the rooms.
        RoomInvite.parse(intent?.data)?.let { invite ->
            Session.setPendingInvite(this, invite)
            if (savedInstanceState == null && !Session.name(this).isNullOrBlank()) {
                Session.setSolo(this, null)
                startActivity(Intent(this, RoomsActivity::class.java))
            }
        }

        fun submit() {
            val name = nameInput.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                nameLayout.error = getString(R.string.login_name_required)
                return
            }
            Session.setName(this, name)
            // An invite waiting: straight to the rooms, to join it; otherwise solo or multiplayer.
            if (Session.pendingInvite(this) != null) {
                Session.setSolo(this, null)
                startActivity(Intent(this, RoomsActivity::class.java))
            } else {
                startActivity(Intent(this, ModeActivity::class.java))
            }
        }

        enter.setOnClickListener { submit() }
        nameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        nameInput.setOnFocusChangeListener { _, _ -> nameLayout.error = null }

        // A Google account (optional) keeps the career on any phone; only with online play.
        if (FirebaseSession.configured(this)) {
            // A version too old to play (see AppVersion) is stopped here, with a way to update.
            FirebaseSession.signIn { uid ->
                if (uid == null || isFinishing) return@signIn
                AppVersion.register(this)
                AppVersion.check(this)
            }
            findViewById<View>(R.id.accountCard).visibility = View.VISIBLE
            findViewById<View>(R.id.googleSignIn).setOnClickListener { signIn() }
            findViewById<View>(R.id.googleSignOut).setOnClickListener { confirmSignOut() }
            showAccount()
        }
    }

    /** "Playing as a guest…" with the sign-in button, or "Signed in as …" with Sign out. */
    private fun showAccount() {
        val email = GoogleAccount.email()
        findViewById<TextView>(R.id.accountStatus).text =
            if (email != null) getString(R.string.account_signed_in, email) else getString(R.string.account_guest)
        findViewById<View>(R.id.googleSignIn).visibility = if (email == null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.googleSignOut).visibility = if (email != null) View.VISIBLE else View.GONE
    }

    private fun signIn() {
        val button = findViewById<MaterialButton>(R.id.googleSignIn)
        button.isEnabled = false
        // As a guest first (if not yet), so the Google account can keep the guest's progress.
        FirebaseSession.signIn { guest ->
            if (isFinishing) return@signIn
            if (guest == null) {
                button.isEnabled = true
                return@signIn Toast.makeText(this, R.string.account_failed, Toast.LENGTH_LONG).show()
            }
            GoogleAccount.signIn(this) { outcome ->
                button.isEnabled = true
                when (outcome) {
                    GoogleAccount.Outcome.LINKED -> {
                        // Same player as before: their career now also belongs to the Google account.
                        CareerWallet.upload(this)
                        Toast.makeText(this, R.string.account_linked, Toast.LENGTH_LONG).show()
                    }
                    GoogleAccount.Outcome.SWITCHED -> {
                        // Another player id: forget this phone's guest progress, the account's
                        // career (XP, money, guns) is loaded when the rooms screen opens.
                        PlayerProgress.clear(this)
                        Toast.makeText(this, R.string.account_switched, Toast.LENGTH_LONG).show()
                    }
                    GoogleAccount.Outcome.CANCELLED -> Unit
                    GoogleAccount.Outcome.FAILED -> Toast.makeText(this, R.string.account_failed, Toast.LENGTH_LONG).show()
                }
                // No name yet: the Google account's first name.
                if (nameInput.text.isNullOrBlank()) GoogleAccount.displayName()?.substringBefore(' ')?.take(24)?.let { nameInput.setText(it) }
                showAccount()
            }
        }
    }

    /** Signing out starts a fresh guest on this phone; the account's progress stays with the account. */
    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_sign_out)
            .setMessage(R.string.account_sign_out_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.account_sign_out) { _, _ ->
                GoogleAccount.signOut(this)
                PlayerProgress.clear(this)
                showAccount()
            }
            .show()
    }
}
