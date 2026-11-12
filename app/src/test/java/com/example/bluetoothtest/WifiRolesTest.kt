package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiRolesTest {
    private fun caps(bearers: Int, freeHost: Int = 0, freeGuest: Int = 0) = Bwf.Caps(bearers, freeHost, freeGuest) // [R S1]

    @Test
    fun everyRowOfTheSharedRoleTableIsReproduced() {
        @Suppress("UNCHECKED_CAST")
        val rows = WifiVectors.section("roles").getValue("rows") as List<List<Long>>
        assertTrue(rows.isNotEmpty())
        for (row in rows) {
            val v = row.map { it.toInt() }
            val local = caps(v[0], v[1], v[2])
            val peer = caps(v[3], v[4], v[5])
            val tuple = WifiRoles.choose(local, peer, localIsLinkInitiator = v[6] == 1)
            when (v[7]) {
                0 -> assertNull("row $row", tuple)
                else -> {
                    assertNotNull("row $row", tuple)
                    assertEquals("row $row", v[7], tuple!!.bearer.wire)
                    assertEquals("row $row", v[8] == 1, tuple.localIsHost)
                }
            }
        }
    }

    @Test
    fun bothPhonesAlwaysAgreeOnTheTupleWithOppositeRoles() {
        for (lb in 0..15) for (pb in 0..15) for (lf in 0..3) for (pf in 0..3) for (initiator in listOf(true, false)) {
            val local = caps(lb, lf and 1, lf shr 1)
            val peer = caps(pb, pf and 1, pf shr 1)
            val mine = WifiRoles.choose(local, peer, initiator)
            val theirs = WifiRoles.choose(peer, local, !initiator)
            val context = "local=$lb/$lf peer=$pb/$pf initiator=$initiator"
            assertEquals(context, mine == null, theirs == null)
            if (mine != null) {
                assertEquals(context, mine.bearer, theirs!!.bearer)
                assertFalse(context, mine.localIsHost == theirs.localIsHost)
            }
        }
    }

    @Test
    fun theResponderHostsFirstAndTheReverseIsUsedOnlyWhenTheFreeCountsForbidIt() {
        val aware = Bwf.Caps.BEARER_AWARE
        val a = caps(aware, freeHost = 1, freeGuest = 1)
        val b = caps(aware, freeHost = 1, freeGuest = 1)
        assertTrue(WifiRoles.choose(a, b, localIsLinkInitiator = false)!!.localIsHost) // a is the responder
        assertFalse(WifiRoles.choose(a, b, localIsLinkInitiator = true)!!.localIsHost)
        // the responder cannot host: the initiator hosts
        val responderNoHost = caps(aware, freeHost = 0, freeGuest = 1)
        assertTrue(WifiRoles.choose(a, responderNoHost, localIsLinkInitiator = true)!!.localIsHost)
        // nobody can host or nobody can be a guest: no tuple
        assertNull(WifiRoles.choose(caps(aware, 0, 1), caps(aware, 0, 1), true))
        assertNull(WifiRoles.choose(caps(aware, 1, 0), caps(aware, 1, 0), true))
    }

    @Test
    fun wifiAwareIsPreferredToWifiDirectAndTheTwoAreNeverMixed() {
        val both = Bwf.Caps.BEARER_AWARE or Bwf.Caps.BEARER_DIRECT_GO or Bwf.Caps.BEARER_DIRECT_CLIENT
        val tuple = WifiRoles.choose(caps(both, 1, 1), caps(both, 1, 1), true)!!
        assertEquals(WifiBearer.AWARE, tuple.bearer)
        // Aware has no free data path: Direct is the answer, and only if both support it
        val direct = WifiRoles.choose(caps(both, 0, 0), caps(both, 0, 0), true)!!
        assertEquals(WifiBearer.DIRECT, direct.bearer)
        assertNull(WifiRoles.choose(caps(Bwf.Caps.BEARER_AWARE, 1, 1), caps(Bwf.Caps.BEARER_DIRECT_GO or Bwf.Caps.BEARER_DIRECT_CLIENT), true))
    }

    @Test
    fun aWindowsPcIsAlwaysTheHostWhoeverDialled() {
        val windows = caps(Bwf.Caps.BEARER_DIRECT_GO)
        val phone = caps(Bwf.Caps.BEARER_DIRECT_GO or Bwf.Caps.BEARER_DIRECT_CLIENT)
        for (windowsDialled in listOf(true, false)) {
            val seenByWindows = WifiRoles.choose(windows, phone, localIsLinkInitiator = windowsDialled)!!
            val seenByPhone = WifiRoles.choose(phone, windows, localIsLinkInitiator = !windowsDialled)!!
            assertEquals(WifiBearer.DIRECT, seenByWindows.bearer)
            assertTrue(seenByWindows.localIsHost)
            assertFalse(seenByPhone.localIsHost)
        }
        // two PCs have no tuple
        assertNull(WifiRoles.choose(windows, windows, true))
    }

    @Test
    fun theCapsOfAPhoneWithNothingEligibleHaveEveryBitClear() {
        val none = WifiEligibility.Decision(false, false, false, false, acceptsUnverifiedBand = true, reason = WifiEligibility.DeclineReason.NOT_ELIGIBLE)
        val caps = WifiRoles.capsFor(none, freeHost = 3, freeGuest = 3)
        assertEquals(0, caps.bearers)
        assertEquals(0, caps.freeHost)
        assertEquals(0, caps.freeGuest)
        assertEquals(0, caps.encode().last().toInt()) // the fourth byte is reserved: the Wider reach switch is never on the wire [R S1]
    }

    @Test
    fun theCapsFollowTheDecisionAndClampTheFreeCounts() {
        val decision = WifiEligibility.Decision(true, true, true, true, acceptsUnverifiedBand = true, reason = null)
        val caps = WifiRoles.capsFor(decision, freeHost = 99, freeGuest = -4)
        assertTrue(caps.awareEligible && caps.awareInstantMode && caps.directGroupOwner && caps.directClient)
        assertEquals(WifiLimits.MAX_FREE_PATHS_IN_CAPS, caps.freeHost)
        assertEquals(0, caps.freeGuest)
        assertEquals(0, caps.encode().last().toInt()) // [R S1]
        val plain = WifiRoles.capsFor(WifiEligibility.Decision(true, false, false, false, acceptsUnverifiedBand = false, reason = null), 2, 1)
        assertEquals(Bwf.Caps.BEARER_AWARE, plain.bearers)
        assertEquals(2, plain.freeHost)
    }

    @Test
    fun theCapsRoundTripThroughTheWireAndKeepTheirMeaning() {
        val decision = WifiEligibility.Decision(true, false, false, false, acceptsUnverifiedBand = false, reason = null)
        val sent = WifiRoles.capsFor(decision, 1, 2)
        val received = WifiWire.decodeBwf1(sent.encode()) as Bwf.Caps
        assertEquals(sent, received)
        assertTrue(received.awareEligible)
    }
}
