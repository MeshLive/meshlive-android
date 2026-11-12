package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `test_call_1m_first`: a direct GATT call opens on 1M and asks for 2M only after the first picture, and only when it needs it. */
class CallOneMFirstTest {
    private fun applies(
        enabled: Boolean = true,
        gatt: Boolean = true,
        dual: Boolean = false,
        direct: Boolean = true,
        wifiOnly: Boolean = false,
        relayed: Boolean = false,
        firstPhy: BlePhyPreference = BlePhyPreference.TWO_M,
        le2M: Boolean = true,
        cocEnabled: Boolean = false,
        coc: Boolean = false,
    ) = CallOneMFirst.appliesToDial(enabled, gatt, dual, direct, wifiOnly, relayed, firstPhy, le2M, cocEnabled, coc)

    @Test
    fun onlyADirectGattDialOfAStrangerOrContactStartsOnOneM() {
        assertTrue(applies())
        assertFalse("switch off: today's 2M-at-subscribe path", applies(enabled = false))
        assertFalse("CoC and Classic keep their path", applies(gatt = false))
        assertFalse("dual listening dials CoC first", applies(dual = true))
        assertFalse("a relayed offer's hop is the relay", applies(direct = false))
        assertFalse(applies(wifiOnly = true))
        assertFalse(applies(relayed = true))
        assertFalse("a peer remembered bad on 2M already asks for 1M, the old path", applies(firstPhy = BlePhyPreference.ONE_M_ADAPTIVE))
        assertFalse("a phone without LE 2M never upgrades", applies(le2M = false))
    }

    @Test
    fun aDirectCocDialStartsOnOneMOnlyUnderItsOwnSwitch() {
        fun cocDial(gattSwitch: Boolean, cocSwitch: Boolean, dual: Boolean = false, direct: Boolean = true) =
            applies(enabled = gattSwitch, gatt = false, cocEnabled = cocSwitch, coc = true, dual = dual, direct = direct)
        assertTrue("CoC switch on: a direct CoC dial opens on 1M", cocDial(gattSwitch = false, cocSwitch = true))
        assertFalse("the GATT switch alone leaves CoC on today's path", cocDial(gattSwitch = true, cocSwitch = false))
        assertFalse("the CoC switch alone leaves GATT on today's path", applies(enabled = false, cocEnabled = true))
        assertFalse("dual listening keeps its own CoC-then-GATT plan", cocDial(gattSwitch = false, cocSwitch = true, dual = true))
        assertFalse("a relayed offer's hop is the relay", cocDial(gattSwitch = false, cocSwitch = true, direct = false))
        assertFalse(
            "a CoC peer remembered bad on 2M already asks for 1M, the old path",
            applies(enabled = false, gatt = false, cocEnabled = true, coc = true, firstPhy = BlePhyPreference.ONE_M_ADAPTIVE),
        )
        assertFalse("Classic is neither", applies(enabled = true, gatt = false, cocEnabled = true, coc = false))
    }

    @Test
    fun noSetupRetryIsOfferedToAFirstOneMDial() {
        fun retry(oneMFirst: Boolean) = adaptivePhySetupRetryEligible(
            enabled = true, running = true, le = true, direct = true,
            firstOnTwoM = CallOneMFirst.setupPhy(BlePhyPreference.TWO_M, oneMFirst) == BlePhyPreference.TWO_M,
            elapsedMs = 4_000,
        )
        assertFalse("first-1M dial: the 2M -> 1M setup retry (and the stall guard that feeds it) is inert", retry(true))
        assertTrue("switch off: the setup retry is intact", retry(false))
        assertEquals(BlePhyPreference.TWO_M, CallOneMFirst.setupPhy(BlePhyPreference.TWO_M, oneMFirst = false))
    }

    @Test
    fun nothingIsAskedForBeforeTheFirstPictureWhateverTheCallNeeds() {
        val gate = CallPhyUpgradeGate()
        gate.onMediaPlanned(CallOneMFirst.plannedKbps(600_000, 30))
        assertNull(gate.onLinkWindow(congested = true))
        assertNull(gate.onLinkWindow(congested = true))
        assertFalse(gate.requested())
        assertEquals(CallUpgradeTrigger.RATE, gate.onPictureShown())
    }

    @Test
    fun aCallAboveTheOneMBudgetAsksOnceAfterTheFirstPicture() {
        val gate = CallPhyUpgradeGate()
        gate.onMediaPlanned(CallOneMFirst.plannedKbps(400_000, 15))
        assertTrue(CallOneMFirst.plannedKbps(400_000, 15) > CallOneMFirst.ONE_M_GATT_KBPS)
        assertEquals(CallUpgradeTrigger.RATE, gate.onPictureShown())
        // Every later frame and sample window reports nothing: one request per link.
        assertNull(gate.onPictureShown())
        assertNull(gate.onLinkWindow(congested = true))
    }

    @Test
    fun aCallThatFitsOneMStaysOnOneMAtItsLinkLimit() {
        // The call's own starting budget (180 kbps video, privateCallVideoSettings) is inside the measured 1M capacity.
        val planned = CallOneMFirst.plannedKbps(180_000, 15)
        assertTrue("planned $planned", planned <= CallOneMFirst.ONE_M_GATT_KBPS)
        val gate = CallPhyUpgradeGate()
        gate.onMediaPlanned(planned)
        assertNull(gate.onPictureShown())
        assertNull(gate.onLinkWindow(congested = false))
        // One congested window is a burst, a second in a row is the link's limit: it holds the call on 1M, reported once.
        assertNull(gate.onLinkWindow(congested = true))
        assertNull(gate.onLinkWindow(congested = false))
        assertFalse(gate.takeLinkLimitHeld())
        assertNull(gate.onLinkWindow(congested = true))
        assertNull(gate.onLinkWindow(congested = true))
        assertTrue(gate.takeLinkLimitHeld())
        assertFalse("reported once", gate.takeLinkLimitHeld())
        assertNull(gate.onLinkWindow(congested = true))
        assertFalse(gate.requested())
    }

    @Test
    fun theLinkLimitStillAsksForTwoMUnderItsExperimentalSwitch() {
        val gate = CallPhyUpgradeGate(linkLimitUpgrade = true)
        gate.onMediaPlanned(CallOneMFirst.plannedKbps(180_000, 15))
        assertNull(gate.onPictureShown())
        assertNull(gate.onLinkWindow(congested = true))
        assertEquals(CallUpgradeTrigger.LINK_LIMIT, gate.onLinkWindow(congested = true))
        assertFalse("nothing held when it asks", gate.takeLinkLimitHeld())
    }

    @Test
    fun aCallAboveTheOneMBudgetStillAsksWhateverTheLinkLimitRule() {
        val gate = CallPhyUpgradeGate()
        gate.onMediaPlanned(CallOneMFirst.plannedKbps(400_000, 15))
        assertNull(gate.onLinkWindow(congested = true))
        assertNull(gate.onLinkWindow(congested = true))
        assertEquals(CallUpgradeTrigger.RATE, gate.onPictureShown())
        assertFalse(gate.takeLinkLimitHeld())
    }

    @Test
    fun aRememberedBadPeerSuppressesTheUpgrade() {
        val gate = CallPhyUpgradeGate()
        gate.onMediaPlanned(CallOneMFirst.plannedKbps(400_000, 15))
        gate.suppress()
        assertNull(gate.onPictureShown())
    }

    @Test
    fun anEarlyLossAfterTheUpgradeFallsBackOnceAndTheNodeIsRemembered() {
        val gate = CallPhyUpgradeGate()
        gate.onMediaPlanned(CallOneMFirst.plannedKbps(400_000, 15))
        assertEquals(CallUpgradeTrigger.RATE, gate.onPictureShown())
        assertFalse("no request yet, nothing to undo", gate.onSilence(10_000, silentMs = 9_000))
        gate.markRequested(20_000)
        assertFalse("heartbeats arrive about every second", gate.onSilence(21_500, silentMs = 1_200))
        assertTrue(gate.onSilence(23_000, silentMs = CallOneMFirst.SILENCE_MS + 200))
        assertFalse("one fallback per link", gate.onSilence(23_500, silentMs = 3_000))
        // The authenticated node is what the memory keys by (AdaptivePhyMemory.key); a relaying hop is no evidence.
        assertEquals("a1b2c3d4e5f6", CallOneMFirst.nodeToRemember("a1b2c3d4e5f6", hopRelaysSkipsMemory = false))
        assertNull(CallOneMFirst.nodeToRemember("a1b2c3d4e5f6", hopRelaysSkipsMemory = true))
        assertNull(CallOneMFirst.nodeToRemember(null, hopRelaysSkipsMemory = false))
        assertEquals(
            "bad2m2.BLE_GATT.a1b2c3d4e5f6",
            AdaptivePhyMemory.key(BluetoothTransportOption.BLE_GATT, CallOneMFirst.nodeToRemember("a1b2c3d4e5f6", false)!!),
        )
    }

    @Test
    fun aSilenceLongAfterTheUpgradeIsTheCallsOwnLivenessNotThisFallback() {
        val gate = CallPhyUpgradeGate()
        gate.onMediaPlanned(CallOneMFirst.plannedKbps(400_000, 15))
        gate.onPictureShown()
        gate.markRequested(20_000)
        assertFalse(gate.onSilence(20_000 + CallOneMFirst.EARLY_LOSS_AFTER_UPGRADE_MS + 1, silentMs = 5_000))
    }

    @Test
    fun theLinkPhyLineNamesRequestedAndEffective() {
        assertEquals(
            "BENCHMARK link_phy requested=1M effective=1M reason=call_1m_first source=experiment link=call node=n1",
            CallOneMFirst.linkPhyLine("1M", "1M", "call_1m_first", "n1"),
        )
        assertEquals(
            "BENCHMARK link_phy requested=2M effective=pending reason=rate_above_budget source=experiment link=call node=n1 plannedKbps=345",
            CallOneMFirst.linkPhyLine("2M", "pending", "rate_above_budget", "n1", "plannedKbps=345"),
        )
    }

    @Test
    fun theSwitchIsOffByDefaultAndFollowsItsLaunchExtra() {
        assertFalse(PhySwitches.call1mFirst)
        try {
            PhySwitches.applyFlags { it == "test_call_1m_first" }
            assertEquals(BuildConfig.DEBUG, PhySwitches.call1mFirst)
        } finally {
            PhySwitches.applyFlags { false }
        }
        assertFalse(PhySwitches.call1mFirst)
    }

    @Test
    fun theLinkLimitSwitchIsOffByDefaultAndFollowsItsLaunchExtra() {
        assertFalse(PhySwitches.call1mFirstLinkLimit)
        try {
            PhySwitches.applyFlags { it == "test_call_1m_first_link_limit" }
            assertEquals(BuildConfig.DEBUG, PhySwitches.call1mFirstLinkLimit)
        } finally {
            PhySwitches.applyFlags { false }
        }
        assertFalse(PhySwitches.call1mFirstLinkLimit)
    }

    @Test
    fun theCocSwitchIsOffByDefaultAndFollowsOnlyItsOwnLaunchExtra() {
        assertFalse(PhySwitches.call1mFirstCoc)
        try {
            PhySwitches.applyFlags { it == "test_call_1m_first" }
            assertFalse("the GATT extra does not turn on CoC", PhySwitches.call1mFirstCoc)
            PhySwitches.applyFlags { it == "test_call_1m_first_coc" }
            assertEquals(BuildConfig.DEBUG, PhySwitches.call1mFirstCoc)
            assertFalse(PhySwitches.call1mFirst)
        } finally {
            PhySwitches.applyFlags { false }
        }
        assertFalse(PhySwitches.call1mFirstCoc)
    }
}
