package com.example.beirutrun

import com.example.beirutrun.city.GunSlot
import com.example.beirutrun.city.Weapon
import com.example.beirutrun.progression.CashReward
import com.example.beirutrun.progression.GunPrices
import com.example.beirutrun.progression.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gun prices: the starting guns are free, the rest cost something a few games can earn. */
class GunPricesTest {

    @Test
    fun startingGunsAreFreeAndTheRestCostMoney() {
        assertEquals(Weapon.defaults.values.toSet(), Weapon.entries.filter { GunPrices.free(it) }.toSet())
        for (slot in GunSlot.entries) {
            val guns = Weapon.inSlot(slot)
            assertTrue("$slot: the free gun should be the cheapest", guns.minBy { GunPrices.of(it) } == Weapon.defaults.getValue(slot))
        }
        // A first gun is affordable from the start; the dearest takes a good few games of kills.
        assertTrue(Weapon.entries.any { !GunPrices.free(it) && GunPrices.of(it) <= Wallet.STARTING_CASH })
        val dearest = Weapon.entries.maxOf { GunPrices.of(it) }
        assertTrue("the dearest gun is ${dearest / CashReward.KILL.cash} kills away", dearest / CashReward.KILL.cash in 20..80)
    }

    @Test
    fun moneyLooksRight() {
        assertEquals("$2,000", Wallet.format(2_000))
        assertEquals("$12,000", Wallet.format(12_000))
        assertEquals("$0", Wallet.format(0))
    }
}
