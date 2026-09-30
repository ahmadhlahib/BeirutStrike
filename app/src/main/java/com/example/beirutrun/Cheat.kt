package com.example.beirutrun

import androidx.annotation.StringRes

/**
 * Cheat codes, typed in the Say box (see CityActivity.applyCheat). A code is never said out loud:
 * other players don't see it, and cheats only change the game on this phone.
 *
 * @property message The banner shown when the code is typed.
 * @property codes What can be typed, in lower case (a common misspelling included).
 */
enum class Cheat(@StringRes val message: Int, vararg val codes: String) {
    UNLIMITED_AMMO(R.string.cheat_unlimited_ammo, "unlimited ammo", "unlimeted ammo"),
    UNLIMITED_HEALTH(R.string.cheat_unlimited_health, "unlimited health", "unlimeted health"),
    FIND_SCOPE(R.string.cheat_find_scope, "find a scope", "find scope"),
    FULL_HEALTH(R.string.cheat_full_health, "full health"),
    SUPER_SPEED(R.string.cheat_super_speed, "super speed"),
    RAPID_FIRE(R.string.cheat_rapid_fire, "rapid fire"),
    CANCEL(R.string.cheat_cancel, "cancel cheats", "cancel cheat");

    companion object {
        /** The cheat [text] is the code for, ignoring case, extra spaces and a final "!" or "."; else null. */
        fun parse(text: String): Cheat? {
            val typed = text.trim().trimEnd('!', '.').trim().lowercase().replace(Regex("\\s+"), " ")
            return entries.firstOrNull { typed in it.codes }
        }
    }
}
