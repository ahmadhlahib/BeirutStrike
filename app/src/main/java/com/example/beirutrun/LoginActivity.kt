package com.example.beirutrun

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        SystemBars.keepClear(this)
        StartBackground.applyTo(this)

        val nameLayout = findViewById<TextInputLayout>(R.id.nameLayout)
        val nameInput = findViewById<TextInputEditText>(R.id.nameInput)
        val enter = findViewById<MaterialButton>(R.id.enterButton)

        nameInput.setText(Session.name(this))

        fun submit() {
            val name = nameInput.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                nameLayout.error = getString(R.string.login_name_required)
                return
            }
            Session.setName(this, name)
            startActivity(Intent(this, RoomsActivity::class.java))
        }

        enter.setOnClickListener { submit() }
        nameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        nameInput.setOnFocusChangeListener { _, _ -> nameLayout.error = null }
    }
}
