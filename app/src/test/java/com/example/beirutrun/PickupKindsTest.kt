package com.example.beirutrun

import com.example.beirutrun.city.PickupKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pickup kinds stay in step with the database rules, and old pickup spots keep their kinds. */
class PickupKindsTest {

    @Test
    fun theRulesAcceptEveryKind() {
        val rules = File("../firebase/database.rules.json").readText()
        for (kind in PickupKind.entries) {
            assertTrue("firebase/database.rules.json doesn't allow pickup kind '${kind.id}'", "'${kind.id}'" in rules)
        }
    }

    @Test
    fun newKindsOnlyAddSpotsAtTheEnd() {
        // What versions up to 1.2.0 had: they must still agree on these.
        val before = List(5) { PickupKind.AK_AMMO } + List(4) { PickupKind.PISTOL_AMMO } + List(2) { PickupKind.SCOPE } +
            List(2) { PickupKind.SNIPER_AMMO } + List(2) { PickupKind.FRAG_GRENADE } + List(2) { PickupKind.FLASH_GRENADE } +
            PickupKind.SMOKE_GRENADE + PickupKind.MOLOTOV
        assertEquals(before, PickupKind.SLOTS.take(before.size))
        assertEquals(3, PickupKind.SLOTS.count { it == PickupKind.MEDKIT_SMALL })
        assertEquals(1, PickupKind.SLOTS.count { it == PickupKind.MEDKIT_BIG })
    }
}
