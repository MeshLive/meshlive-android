package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Batch P: roll back the last change after "link lost within seconds of a change" (BACKLOG P-01/P-02), and the drop classifier. */
class LinkChangeRollbackTest {
    private var epoch = "2026-10-05:0"
    private val store = MemoryRollbackStore()
    private val lines = mutableListOf<String>()

    private fun memory(
        windowMs: Long = 10_000L,
        kinds: Set<LinkChangeKind> = LinkChangeKind.entries.toSet(),
        ttlMs: Long = LinkChangeRollback.TTL_MS,
    ) = LinkChangeRollback(store, { epoch }, windowMs, kinds, ttlMs, { lines += it })

    private fun change(kind: LinkChangeKind, at: Long) = RecordedChange(kind, at)

    @Test
    fun theLastChangeInsideTheWindowIsRememberedForThePeer() {
        val rb = memory()
        val kind = rb.onLinkLost(
            "peer", listOf(change(LinkChangeKind.MTU, 1_000), change(LinkChangeKind.PHY, 3_000)),
            lostAtMs = 8_000, status = 8, nowMs = 50_000,
        )
        assertEquals(LinkChangeKind.PHY, kind)
        assertTrue(rb.isDowngraded("peer", LinkChangeKind.PHY, 60_000))
        assertFalse(rb.isDowngraded("peer", LinkChangeKind.MTU, 60_000))
        assertFalse(rb.isDowngraded("other", LinkChangeKind.PHY, 60_000))
        assertTrue(lines.single().contains("remembered=phy"))
    }

    @Test
    fun aLossLongAfterTheChangeOrWithACleanStatusRemembersNothing() {
        val rb = memory()
        assertNull(rb.onLinkLost("peer", listOf(change(LinkChangeKind.PHY, 1_000)), 11_001, 8, 1))
        assertNull("0x16 local close", rb.onLinkLost("peer", listOf(change(LinkChangeKind.PHY, 1_000)), 2_000, 22, 1))
        assertNull("0x13 remote close", rb.onLinkLost("peer", listOf(change(LinkChangeKind.PHY, 1_000)), 2_000, 19, 1))
        assertNull("no peer", rb.onLinkLost(null, listOf(change(LinkChangeKind.PHY, 1_000)), 2_000, 8, 1))
        assertNull("no change", rb.onLinkLost("peer", emptyList(), 2_000, 8, 1))
        assertTrue(store.keys().isEmpty())
    }

    @Test
    fun theSameChangeFailingAgainRollsBackTheNextEarlierOneInReverseOrder() {
        val rb = memory()
        val changes = listOf(
            change(LinkChangeKind.MTU, 1_000), change(LinkChangeKind.PHY, 2_000), change(LinkChangeKind.PRIORITY, 3_000),
        )
        assertEquals(LinkChangeKind.PRIORITY, rb.onLinkLost("p", changes, 5_000, 8, 100))
        assertEquals(LinkChangeKind.PHY, rb.onLinkLost("p", changes, 5_000, 8, 200))
        assertEquals(LinkChangeKind.MTU, rb.onLinkLost("p", changes, 5_000, 8, 300))
        assertNull("everything in the window is already rolled back", rb.onLinkLost("p", changes, 5_000, 8, 400))
    }

    @Test
    fun aMarkExpiresWithTheEpochAndTheTtl() {
        val rb = memory(ttlMs = 1_000)
        rb.onLinkLost("p", listOf(change(LinkChangeKind.PHY, 0)), 1_000, 8, nowMs = 10_000)
        assertTrue(rb.isDowngraded("p", LinkChangeKind.PHY, 10_999))
        assertFalse("ttl", rb.isDowngraded("p", LinkChangeKind.PHY, 11_000))
        assertFalse("a clock that moved back", rb.isDowngraded("p", LinkChangeKind.PHY, 9_999))
        epoch = "2026-11-05:0" // a security-patch update
        assertFalse(rb.isDowngraded("p", LinkChangeKind.PHY, 10_500))
    }

    @Test
    fun aBluetoothRestartClearsEveryMark() {
        val rb = memory()
        rb.onLinkLost("p", listOf(change(LinkChangeKind.PHY, 0)), 1_000, 8, nowMs = 10_000)
        assertEquals(1, rb.clear())
        assertFalse(rb.isDowngraded("p", LinkChangeKind.PHY, 10_001))
    }

    @Test
    fun aMemoryOnlyRemembersTheKindsItWasBuiltFor() {
        val rb = memory(windowMs = 5_000, kinds = setOf(LinkChangeKind.PHY))
        assertNull(rb.onLinkLost("p", listOf(change(LinkChangeKind.MTU, 1_000)), 2_000, 8, 1))
        assertEquals(LinkChangeKind.PHY, rb.onLinkLost("p", listOf(change(LinkChangeKind.MTU, 1_000), change(LinkChangeKind.PHY, 900)), 2_000, 8, 1))
    }

    // ---- the two memories behind their switches ----

    @Before
    fun resetShared() {
        PhySwitches.llGate = false
        PhySwitches.linkChangeRollback = false
        LinkChangeRollbacks.epochProvider = { "e1" }
        LinkChangeRollbacks.session.clear()
        LinkChangeRollbacks.persistent = LinkChangeRollback(
            MemoryRollbackStore(), { LinkChangeRollbacks.epochProvider() },
            LinkChangeRollbacks.PERSISTENT_WINDOW_MS, LinkChangeKind.entries.toSet(),
        )
    }

    @After
    fun restoreShared() {
        PhySwitches.llGate = false
        PhySwitches.linkChangeRollback = false
        LinkChangeRollbacks.persistent = null
        LinkChangeRollbacks.session.clear()
        LinkChangeRollbacks.epochProvider = { LinkChangeRollbacks.defaultEpoch() }
    }

    @Test
    fun withBothSwitchesOffNothingIsRememberedOrConsulted() {
        LinkChangeRollbacks.onLinkLost("p", listOf(change(LinkChangeKind.PHY, 0)), 1_000, 8, 5_000)
        PhySwitches.llGate = true
        PhySwitches.linkChangeRollback = true
        assertFalse(LinkChangeRollbacks.isDowngraded("p", LinkChangeKind.PHY, 5_001))
    }

    @Test
    fun theGateSwitchRemembersOnlyAPhyChangeWithinFiveSeconds() {
        // The gate and rollback switches are Experimental DEBUG arms; withBothSwitchesOff... states the release behaviour.
        assumeTrue("DEBUG arm", BuildConfig.DEBUG)
        PhySwitches.llGate = true
        LinkChangeRollbacks.onLinkLost("p", listOf(change(LinkChangeKind.MTU, 0)), 1_000, 8, 5_000)
        assertFalse(LinkChangeRollbacks.isDowngraded("p", LinkChangeKind.MTU, 5_001))
        LinkChangeRollbacks.onLinkLost("p", listOf(change(LinkChangeKind.PHY, 0)), 6_000, 8, 5_000)
        assertFalse("six seconds after the change is outside the 5 s window", LinkChangeRollbacks.isDowngraded("p", LinkChangeKind.PHY, 5_001))
        LinkChangeRollbacks.onLinkLost("p", listOf(change(LinkChangeKind.PHY, 0)), 4_900, 0x2A, 5_000)
        assertTrue(LinkChangeRollbacks.isDowngraded("p", LinkChangeKind.PHY, 5_001))
    }

    @Test
    fun theRollbackSwitchRemembersMtuPhyAndPriorityWithinTenSecondsAndOffersTheDowngrades() {
        // The gate and rollback switches are Experimental DEBUG arms; withBothSwitchesOff... states the release behaviour.
        assumeTrue("DEBUG arm", BuildConfig.DEBUG)
        PhySwitches.linkChangeRollback = true
        val now = System.currentTimeMillis()
        LinkChangeRollbacks.onLinkLost("p", listOf(change(LinkChangeKind.MTU, 0)), 9_900, 8, now)
        assertEquals(185, LinkChangeRollbacks.mtuCap("p"))
        assertNull(LinkChangeRollbacks.mtuCap("other"))
        LinkChangeRollbacks.onLinkLost("p", listOf(change(LinkChangeKind.PRIORITY, 0)), 9_900, 8, now)
        assertTrue(LinkChangeRollbacks.isDowngraded("p", LinkChangeKind.PRIORITY))
        LinkChangeRollbacks.onBluetoothRestarted()
        assertNull(LinkChangeRollbacks.mtuCap("p"))
    }

    // ---- the classifier ----

    @Test
    fun aFaultStatusWithinASecondOfConnectingIsSetupOrScheduling() {
        assertEquals(LinkDropClass.SETUP_OR_SCHEDULING, classifyLinkDrop(62, 400, emptyList(), 400))
        assertEquals(LinkDropClass.SETUP_OR_SCHEDULING, classifyLinkDrop(133, 900, emptyList(), 900))
    }

    @Test
    fun collisionCodesAndTimeoutsRightAfterAChangeAreAProcedureRace() {
        assertEquals(LinkDropClass.PROCEDURE_RACE, classifyLinkDrop(42, 30_000, emptyList(), 30_000))
        assertEquals(LinkDropClass.PROCEDURE_RACE, classifyLinkDrop(35, 30_000, emptyList(), 30_000))
        val phy = listOf(RecordedChange(LinkChangeKind.PHY, 26_000))
        assertEquals(LinkDropClass.PROCEDURE_RACE, classifyLinkDrop(8, 30_000, phy, 30_000))
        assertEquals(LinkDropClass.OTHER_FAULT, classifyLinkDrop(8, 30_000, phy, 40_000))
    }

    @Test
    fun aTimeoutAfterAboutFiveSilentSecondsIsTheSupervisionTimeoutThePriorityRequestSet() {
        assertEquals(LinkDropClass.SUPERVISION_TIMEOUT, classifyLinkDrop(8, 60_000, emptyList(), 60_000, silentMs = 5_050, supervisionMs = 5_000))
        assertEquals(LinkDropClass.OTHER_FAULT, classifyLinkDrop(8, 60_000, emptyList(), 60_000, silentMs = 2_000, supervisionMs = 5_000))
    }

    @Test
    fun cleanClosesAreNeverFaults() {
        assertEquals(LinkDropClass.CLEAN, classifyLinkDrop(22, 100, emptyList(), 100))
        assertEquals(LinkDropClass.CLEAN, classifyLinkDrop(0, 100, emptyList(), 100))
        assertTrue(8 in LINK_FAULT_STATUSES && 0x2A in LINK_FAULT_STATUSES && 22 !in LINK_FAULT_STATUSES)
    }
}
