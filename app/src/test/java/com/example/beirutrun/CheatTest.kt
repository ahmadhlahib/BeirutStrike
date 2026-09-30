package com.example.beirutrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheatTest {

    @Test
    fun codesAreRecognised() {
        assertEquals(Cheat.UNLIMITED_AMMO, Cheat.parse("unlimited ammo"))
        assertEquals(Cheat.UNLIMITED_HEALTH, Cheat.parse("unlimited health"))
        assertEquals(Cheat.FIND_SCOPE, Cheat.parse("find a scope"))
        assertEquals(Cheat.FULL_HEALTH, Cheat.parse("full health"))
        assertEquals(Cheat.SUPER_SPEED, Cheat.parse("super speed"))
        assertEquals(Cheat.RAPID_FIRE, Cheat.parse("rapid fire"))
        assertEquals(Cheat.CANCEL, Cheat.parse("cancel cheats"))
    }

    @Test
    fun caseSpacesPunctuationAndTheCommonTypoDontMatter() {
        assertEquals(Cheat.UNLIMITED_AMMO, Cheat.parse("  Unlimited   AMMO! "))
        assertEquals(Cheat.UNLIMITED_AMMO, Cheat.parse("unlimeted ammo"))
        assertEquals(Cheat.CANCEL, Cheat.parse("Cancel cheats."))
    }

    @Test
    fun ordinaryMessagesAreNotCheats() {
        assertNull(Cheat.parse("hello"))
        assertNull(Cheat.parse(""))
        assertNull(Cheat.parse("I need unlimited ammo"))
    }
}
