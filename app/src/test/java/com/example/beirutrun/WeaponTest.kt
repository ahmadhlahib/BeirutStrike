package com.example.beirutrun

import com.example.beirutrun.city.GunModels
import com.example.beirutrun.city.GunSlot
import com.example.beirutrun.city.PickupKind
import com.example.beirutrun.city.Weapon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeaponTest {

    @Test
    fun akCarries120RoundsIn30RoundMagazines() {
        assertEquals(30, Weapon.AK47.magazine)
        assertEquals(120, Weapon.AK47.startAmmo)
        assertTrue(Weapon.AK47.automatic)
        assertEquals(0.1f, Weapon.AK47.fireInterval) // 600 rounds a minute
    }

    @Test
    fun m9Carries90RoundsIn15RoundMagazines() {
        assertEquals(15, Weapon.M9.magazine)
        assertEquals(90, Weapon.M9.startAmmo)
        assertFalse(Weapon.M9.automatic)
    }

    @Test
    fun threePistolsFivePrimariesAndFourSniperRifles() {
        assertEquals(3, Weapon.inSlot(GunSlot.PISTOL).size)
        assertEquals(5, Weapon.inSlot(GunSlot.PRIMARY).size)
        assertEquals(4, Weapon.inSlot(GunSlot.SNIPER).size)
        for ((slot, gun) in Weapon.defaults) assertEquals(slot, gun.slot)
    }

    @Test
    fun sniperRiflesHaveAScopeFourMagazinesAndOutrangeEverythingElse() {
        val longestOther = Weapon.entries.filter { it.slot != GunSlot.SNIPER }.maxOf { it.range }
        for (gun in Weapon.inSlot(GunSlot.SNIPER)) {
            assertTrue(gun.displayName, gun.hasScope)
            assertEquals(gun.displayName, 4, gun.magazines)
            assertTrue(gun.displayName, gun.range > longestOther)
            // Accurate through the scope, not from the hip.
            assertTrue(gun.displayName, gun.scopedSpread < gun.hipSpread / 10f)
        }
    }

    @Test
    fun theM4ComesWithAScopeButShorterRangeThanTheSniperRifles() {
        assertTrue(Weapon.M4.hasScope)
        assertTrue(Weapon.M4.range < Weapon.inSlot(GunSlot.SNIPER).minOf { it.range })
        assertFalse(Weapon.AK47.hasScope)
    }

    @Test
    fun zoomLevelsGoFromLowestToHighest() {
        for (gun in Weapon.entries) assertEquals(gun.displayName, gun.zooms.sorted(), gun.zooms)
    }

    @Test
    fun damageNeverExceedsAPlayersHealth() {
        for (gun in Weapon.entries) assertTrue(gun.displayName, gun.damage in 1..5)
    }

    @Test
    fun unknownWeaponIdsFallBackToTheAkAndOldPistolIdIsTheM9() {
        assertEquals(Weapon.M9, Weapon.byId("pistol"))
        assertEquals(Weapon.AWM, Weapon.byId("awm"))
        assertEquals(Weapon.AK47, Weapon.byId(""))
        assertEquals(Weapon.AK47, Weapon.byId(null))
    }

    @Test
    fun ammoPacksFillTheGunInTheirSlot() {
        assertEquals(GunSlot.PRIMARY, PickupKind.AK_AMMO.slot)
        assertEquals(GunSlot.PISTOL, PickupKind.PISTOL_AMMO.slot)
        assertEquals(GunSlot.SNIPER, PickupKind.SNIPER_AMMO.slot)
        assertNull(PickupKind.SCOPE.slot)
        assertEquals(PickupKind.SCOPE, PickupKind.byId("scope"))
    }

    @Test
    fun roomPickupsKeepTheOldSlotsFirst() {
        val slots = PickupKind.SLOTS
        // Older versions of the app know the first 11 slots: 5 rifle, 4 pistol, 2 scopes.
        assertEquals(List(5) { PickupKind.AK_AMMO } + List(4) { PickupKind.PISTOL_AMMO } + List(2) { PickupKind.SCOPE },
            slots.take(11))
        // Magazines lie in the streets (more spots since 1.2.x added the arms stores).
        assertEquals(8, slots.count { it == PickupKind.AK_AMMO })
        assertEquals(6, slots.count { it == PickupKind.PISTOL_AMMO })
        assertEquals(3, slots.count { it == PickupKind.SNIPER_AMMO })
    }

    @Test
    fun everyGunHasAModelWithTheMuzzleAheadOfTheGrip() {
        for (gun in Weapon.entries) {
            val model = GunModels.of(gun)
            assertTrue(gun.displayName, model.parts.isNotEmpty())
            assertTrue(gun.displayName, model.muzzleZ < model.gripZ)
        }
    }
}
