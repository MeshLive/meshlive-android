package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** A peer whose link was lost within seconds of a PHY update connects on 1M next time (Experimental, BACKLOG P-01/P-02). */
class PhyRollbackAtConnectTest {
    private val prefix = byteArrayOf(0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f)
    private val node = "0a0b0c0d0e0f"

    @Before
    fun setUp() {
        LinkChangeRollbacks.epochProvider = { "e1" }
        LinkChangeRollbacks.session.clear()
        LinkChangeRollbacks.persistent = LinkChangeRollback(
            MemoryRollbackStore(), { LinkChangeRollbacks.epochProvider() },
            LinkChangeRollbacks.PERSISTENT_WINDOW_MS, LinkChangeKind.entries.toSet(),
        )
    }

    @After
    fun tearDown() {
        PhySwitches.applyFlags { false }
        LinkChangeRollbacks.persistent = null
        LinkChangeRollbacks.session.clear()
        LinkChangeRollbacks.epochProvider = { LinkChangeRollbacks.defaultEpoch() }
    }

    private fun phy() = AdaptivePhyMemory.phyFor(BluetoothTransportOption.BLE_GATT, prefix, BlePhyPreference.TWO_M, "gatt")

    @Test
    fun withoutAMarkTheDefaultPhyIsKept() {
        PhySwitches.applyFlags { true }
        assertEquals(BlePhyPreference.TWO_M, phy())
    }

    @Test
    fun aRememberedPhyChangeLossMovesTheNextConnectionTo1MWhileTheGateSwitchIsOn() {
        // The rollback switches are Experimental DEBUG arms; a release build keeps the default PHY (theSameMarkDoesNothing...).
        assumeTrue("DEBUG arm", BuildConfig.DEBUG)
        PhySwitches.applyFlags { it == "test_ll_gate" }
        LinkChangeRollbacks.onLinkLost(node, listOf(RecordedChange(LinkChangeKind.PHY, 0)), 3_000, 0x2A, System.currentTimeMillis())
        assertEquals(BlePhyPreference.ONE_M_ADAPTIVE, phy())
    }

    @Test
    fun theSameMarkDoesNothingWhenBothSwitchesAreOff() {
        PhySwitches.applyFlags { it == "test_ll_gate" }
        LinkChangeRollbacks.onLinkLost(node, listOf(RecordedChange(LinkChangeKind.PHY, 0)), 3_000, 8, System.currentTimeMillis())
        PhySwitches.applyFlags { false }
        assertEquals(BlePhyPreference.TWO_M, phy())
    }

    @Test
    fun anMtuMarkDoesNotMoveThePhy() {
        // The rollback switches are Experimental DEBUG arms; a release build keeps the default PHY (theSameMarkDoesNothing...).
        assumeTrue("DEBUG arm", BuildConfig.DEBUG)
        PhySwitches.applyFlags { it == "test_link_change_rollback" }
        LinkChangeRollbacks.onLinkLost(node, listOf(RecordedChange(LinkChangeKind.MTU, 0)), 3_000, 8, System.currentTimeMillis())
        assertEquals(BlePhyPreference.TWO_M, phy())
        assertEquals(185, LinkChangeRollbacks.mtuCap(node))
    }

    @Test
    fun aBluetoothRestartForgetsTheMark() {
        // The rollback switches are Experimental DEBUG arms; a release build keeps the default PHY (theSameMarkDoesNothing...).
        assumeTrue("DEBUG arm", BuildConfig.DEBUG)
        PhySwitches.applyFlags { it == "test_link_change_rollback" }
        LinkChangeRollbacks.onLinkLost(node, listOf(RecordedChange(LinkChangeKind.PHY, 0)), 3_000, 8, System.currentTimeMillis())
        assertEquals(BlePhyPreference.ONE_M_ADAPTIVE, phy())
        LinkChangeRollbacks.onBluetoothRestarted()
        assertEquals(BlePhyPreference.TWO_M, phy())
    }

    @Test
    fun aRelayedOfferIsNotAffected() {
        PhySwitches.applyFlags { it == "test_ll_gate" }
        LinkChangeRollbacks.onLinkLost(node, listOf(RecordedChange(LinkChangeKind.PHY, 0)), 3_000, 8, System.currentTimeMillis())
        val relayed = AdaptivePhyMemory.phyFor(BluetoothTransportOption.BLE_GATT, prefix, BlePhyPreference.TWO_M, "gatt", relayedOffer = true)
        assertTrue("a relayed offer is advertised by the relay, not the far peer", relayed == BlePhyPreference.TWO_M)
    }
}
