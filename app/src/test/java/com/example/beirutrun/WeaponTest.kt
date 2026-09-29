package com.example.beirutrun

import com.example.beirutrun.city.PickupKind
import com.example.beirutrun.city.Weapon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeaponTest {

    @Test
    fun pistolIsOneShotASecondAndAkIsAutomatic() {
        assertEquals(1f, Weapon.PISTOL.fireInterval)
        assertFalse(Weapon.PISTOL.automatic)
        assertEquals(0.1f, Weapon.AK47.fireInterval)
        assertTrue(Weapon.AK47.automatic)
        assertEquals(12, Weapon.PISTOL.startAmmo)
        assertEquals(30, Weapon.AK47.startAmmo)
    }

    @Test
    fun unknownWeaponIdsFallBackToTheAk() {
        assertEquals(Weapon.PISTOL, Weapon.byId("pistol"))
        assertEquals(Weapon.AK47, Weapon.byId(""))
        assertEquals(Weapon.AK47, Weapon.byId(null))
    }

    @Test
    fun ammoPacksMatchTheirGun() {
        assertEquals(Weapon.AK47, PickupKind.AK_AMMO.weapon)
        assertEquals(Weapon.PISTOL, PickupKind.PISTOL_AMMO.weapon)
        assertNull(PickupKind.SCOPE.weapon)
        assertEquals(10, PickupKind.PACK_SIZE)
        assertEquals(PickupKind.SCOPE, PickupKind.byId("scope"))
    }

    @Test
    fun roomHasPacksForBothGunsAndTwoScopes() {
        val slots = PickupKind.SLOTS
        assertEquals(5, slots.count { it == PickupKind.AK_AMMO })
        assertEquals(4, slots.count { it == PickupKind.PISTOL_AMMO })
        assertEquals(2, slots.count { it == PickupKind.SCOPE })
    }
}
