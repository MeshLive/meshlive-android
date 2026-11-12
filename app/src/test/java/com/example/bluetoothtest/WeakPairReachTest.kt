package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F11 weak-pair reach (ql-ef96bbf0 a15s-xiaomi: the Redmi 12 heard the A15s's code advert 0 times in a 10 s Meet scan). */
class WeakPairReachTest {
    private fun source(name: String): String = listOf(
        File("src/main/java/com/example/bluetoothtest/$name"),
        File("app/src/main/java/com/example/bluetoothtest/$name"),
    ).first { it.exists() }.readText().replace("\r\n", "\n")

    @Test
    fun aSlowScannerGetsALongerContactCallWaitBoundedAtThirtySeconds() {
        // About 3 sightings per phone per second on most phones: the old 10 s.
        assertEquals(10_000L, WeakPairReach.dialWaitMs(typicalGapMs = 330L, on = true))
        // The Redmi 12: 0.3-0.5 sightings per phone per second.
        assertEquals(12_500L, WeakPairReach.dialWaitMs(typicalGapMs = 2_500L, on = true))
        assertEquals(30_000L, WeakPairReach.dialWaitMs(typicalGapMs = 9_000L, on = true))
        assertEquals("no measurement yet", 10_000L, WeakPairReach.dialWaitMs(typicalGapMs = null, on = true))
        assertEquals("control arm", 10_000L, WeakPairReach.dialWaitMs(typicalGapMs = 9_000L, on = false))
    }

    @Test
    fun theMeetScanLooksThirtySecondsWithOneFreshScannerHalfWay() {
        assertEquals(30_000L, WeakPairReach.meetSearchMs(on = true))
        assertEquals(15_000L, WeakPairReach.meetRestartAtMs(on = true))
        assertEquals(10_000L, WeakPairReach.meetSearchMs(on = false))
        assertNull(WeakPairReach.meetRestartAtMs(on = false))
    }

    @Test
    fun aLiveCodeHoldsTheCallListenerAtLowLatency() {
        assertTrue(AdvertisingBoost.holding(codeShown = true, on = true))
        assertFalse(AdvertisingBoost.holding(codeShown = false, on = true))
        assertFalse(AdvertisingBoost.holding(codeShown = true, on = false))
    }

    @Test
    fun theBalancedStepWaitsWhileACodeIsOnScreenForBothCallListenersEvenWithTheIdleHoldOff() {
        // The real chain: the Add contact screen's live code (QrMeet.display) is what AdvertisingBoost.holding() reads.
        val fingerprint = IdentityFingerprint.fromHex("ab".repeat(32))
        QrMeet.display.leave()
        try {
            assertFalse(AdvertisingBoost.holding())
            QrMeet.display.show(fingerprint, ByteArray(6) { it.toByte() })
            assertTrue(AdvertisingBoost.holding())
            val gatt = CallReadyAdvertisingHold.decide(
                enabled = false, callEndpoint = true, closed = false, advertising = true, linkActive = false,
                boostHolding = AdvertisingBoost.holding(),
            )
            assertEquals(CallReadyAdvertisingHold.Action.RETRY_LATER, gatt)
            assertEquals(
                CallReadyAdvertisingHold.Action.RETRY_LATER,
                CallReadyAdvertisingHold.decideControlListener(closed = false, boostHolding = AdvertisingBoost.holding()),
            )
            QrMeet.display.leave()
            assertFalse("leaving the screen ends the hold", AdvertisingBoost.holding())
            assertEquals(
                CallReadyAdvertisingHold.Action.STEP,
                CallReadyAdvertisingHold.decideControlListener(closed = false, boostHolding = AdvertisingBoost.holding()),
            )
        } finally {
            QrMeet.display.leave()
        }
    }

    @Test
    fun theControlListenerStepsDownWhenNothingHoldsItAndIgnoresAClosedServer() {
        assertEquals(CallReadyAdvertisingHold.Action.STEP, CallReadyAdvertisingHold.decideControlListener(closed = false, boostHolding = false))
        assertEquals(CallReadyAdvertisingHold.Action.IGNORE, CallReadyAdvertisingHold.decideControlListener(closed = true, boostHolding = false))
        assertEquals(CallReadyAdvertisingHold.Action.IGNORE, CallReadyAdvertisingHold.decideControlListener(closed = true, boostHolding = true))
    }

    @Test
    fun scansAskForLe1mOnlyBecauseEveryAdvertOfTheAppIsLegacy() {
        val scan = source("BleL2capControl.kt").substringAfter("private fun scanSettings(mode: Int)").substringBefore(".build()")
        // The settings are described by DiscoveryScanPlan: legacy on LE 1M only, unless the Find PCs scan needs extended adverts.
        assertTrue(scan.contains("DiscoveryScanPlan.settings(mode, scanOnLe1m(), pcDiscovery)"))
        assertTrue(scan.contains("spec.legacy?.let(builder::setLegacy)"))
        assertTrue(scan.contains("spec.phy?.let(builder::setPhy)"))
        assertEquals(ScanSettingsSpec(2, true, 1), DiscoveryScanPlan.settings(2, le1mOnly = true, pcExtended = false))
        assertEquals(1, DiscoveryScanPlan.PHY_LE_1M) // BluetoothDevice.PHY_LE_1M
    }

    @Test
    fun theScannerRateIsTheMedianGapOverEveryPhoneAndSurvivesRowExpiry() {
        val cadence = CallPeerCadence()
        assertNull(cadence.typicalGapMs())
        var t = 0L
        repeat(4) { cadence.observe("a", t); t += 2_500L }
        repeat(4) { cadence.observe("b", t); t += 3_000L }
        assertEquals(3_000L, cadence.typicalGapMs())
        cadence.forget("a")
        cadence.forget("b")
        assertEquals("a row that expired keeps the measured rate", 3_000L, cadence.typicalGapMs())
    }

    @Test
    fun theMeetScanMatchesInSoftwareAndNoOtherScannerDoes() {
        // ql-8143c157 f11-snoop / f11-raw-observe: the Redmi 12's offloaded filter never reported the A15s's code advert,
        // its unfiltered raw observer did. Software matching for the Meet scanner only, the S-06 stage unchanged.
        val before = ScanFilterFallback.stage
        try {
            ScanFilterFallback.setStage(ScanFilterStage.FILTERED)
            assertTrue(ScanFilterFallback.softwareFor(scannerOption = true))
            assertFalse(ScanFilterFallback.softwareFor(scannerOption = false))
            ScanFilterFallback.setStage(ScanFilterStage.SOFTWARE)
            assertTrue("the S-06 stage still covers every scanner", ScanFilterFallback.softwareFor(scannerOption = false))
        } finally {
            ScanFilterFallback.setStage(before)
        }
        // The A15s's on-air record lists the service UUID (1107 c8d5a101-...): the software match keeps it.
        val uuid = BleL2capControlProtocol.SERVICE_UUID
        assertTrue(ScanFilterFallback.matchesService(listOf(uuid), uuid))
        assertFalse(ScanFilterFallback.matchesService(null, uuid))

        // The Meet scan, and the Nearby call list only through its own Experimental switch (default off).
        assertFalse("the call list keeps its hardware filter by default", DebugTunables.callListSoftwareFilter)

        assertTrue("the Meet scan matches in software by default", DebugTunables.meetSoftwareFilter)
    }

    @Test
    fun aContactCallWaitsLongEnoughForATwoRelayRouteOnlyWithRelayedCallsOn() {
        assertEquals(WeakPairReach.RELAYED_CONTACT_WAIT_MS, WeakPairReach.contactDialWaitMs(10_000L, relayedCalls = true))
        assertEquals(10_000L, WeakPairReach.contactDialWaitMs(10_000L, relayedCalls = false))
        assertEquals(60_000L, WeakPairReach.contactDialWaitMs(60_000L, relayedCalls = true))
    }
}
