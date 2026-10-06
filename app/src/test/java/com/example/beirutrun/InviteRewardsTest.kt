package com.example.beirutrun

import com.example.beirutrun.online.InviteRewards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/** Invite links carry the inviter through Google Play, and the money adds up. */
class InviteRewardsTest {

    @Test
    fun theLinkCarriesTheInviterThroughGooglePlay() {
        val link = InviteRewards.playLink("com.alahib.beirutstrike", "AbC123xyz789")
        assertTrue(link.startsWith("https://play.google.com/store/apps/details?id=com.alahib.beirutstrike&referrer="))
        // Play hands the app the referrer decoded once, as it was before encoding.
        val referrer = URLDecoder.decode(link.substringAfter("&referrer="), "UTF-8")
        assertEquals("utm_source=invite&utm_medium=friend&inviter=AbC123xyz789", referrer)
        assertEquals("AbC123xyz789", InviteRewards.inviterIn(referrer))
    }

    @Test
    fun installsNotFromAnInviteHaveNoInviter() {
        assertNull(InviteRewards.inviterIn(null))
        assertNull(InviteRewards.inviterIn(""))
        // An ordinary Play Store install.
        assertNull(InviteRewards.inviterIn("utm_source=google-play&utm_medium=organic"))
        // Nothing that isn't a user id gets through.
        assertNull(InviteRewards.inviterIn("inviter=../../config"))
        assertNull(InviteRewards.inviterIn("inviter="))
    }

    @Test
    fun theMoneyAddsUp() {
        assertEquals(0L, InviteRewards.earned(0, 0))
        assertEquals(300L, InviteRewards.earned(3, 0))
        assertEquals(1_100L, InviteRewards.earned(2, 3))
        // Friends pay up to the limit, and there are only three stories.
        assertEquals(InviteRewards.MAX_PAID_FRIENDS * 100L + 900L, InviteRewards.earned(500, 7))
    }
}
