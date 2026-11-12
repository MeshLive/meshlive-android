package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CocAutoGattTest {
    private val l2cap = BluetoothTransportOption.BLE_L2CAP
    private val gatt = BluetoothTransportOption.BLE_GATT
    private val classic = BluetoothTransportOption.CLASSIC_RFCOMM
    private val on = CocAutoGattSettings(enabled = true)
    private val off = CocAutoGattSettings(enabled = false)

    private fun session(registrations: Int = 0): Pair<CocRegistrationLedger, CocAutoGattSession> {
        val ledger = CocRegistrationLedger()
        repeat(registrations) { ledger.record() }
        return ledger to CocAutoGattSession(ledger)
    }

    @Test
    fun ledgerCountsRegistrationsAndResetStartsANewSession() {
        val ledger = CocRegistrationLedger()
        assertEquals(1, ledger.record())
        assertEquals(2, ledger.record())
        assertEquals(2, ledger.count)
        val generation = ledger.generation
        ledger.reset()
        assertEquals(0, ledger.count)
        assertEquals(generation + 1, ledger.generation)
        assertEquals(1, ledger.record())
    }

    @Test
    fun flagOffNeverChangesTheTransportWhateverTheCountOrFailures() {
        val (_, session) = session(registrations = 40)
        for (requested in BluetoothTransportOption.entries) {
            val choice = session.choose(requested, off)
            assertEquals(requested, choice.effective)
            assertEquals(CocFallbackReason.NONE, choice.reason)
            assertFalse(choice.automatic)
            assertNull(choice.notice)
        }
        assertNull(session.onCocFailure(l2cap, off, CocFallbackReason.AVAILABILITY_FAILED))
        assertNull(session.onCocFailure(l2cap, off, CocFallbackReason.CONNECT_FAILED))
        assertNull(session.onPeerAdvertisesGatt(l2cap, off))
        assertEquals(l2cap, session.choose(l2cap, off).effective)
    }

    @Test
    fun belowTheThresholdStaysOnL2capAndAtTheThresholdPrefersGatt() {
        val (ledger, session) = session()
        repeat(11) { ledger.record() }
        val below = session.choose(l2cap, on)
        assertEquals(l2cap, below.effective)
        assertEquals(CocFallbackReason.NONE, below.reason)
        assertEquals(11, below.registrations)

        ledger.record()
        val atThreshold = session.choose(l2cap, on)
        assertEquals(gatt, atThreshold.effective)
        assertEquals(l2cap, atThreshold.requested)
        assertEquals(CocFallbackReason.THRESHOLD, atThreshold.reason)
        assertTrue(atThreshold.automatic)
        assertEquals(12, atThreshold.registrations)
        assertNotNull(atThreshold.notice)
    }

    @Test
    fun theThresholdIsConfigurableAndClamped() {
        val (_, session) = session(registrations = 5)
        assertEquals(gatt, session.choose(l2cap, CocAutoGattSettings(true, 5)).effective)
        assertEquals(l2cap, session.choose(l2cap, CocAutoGattSettings(true, 6)).effective)
        assertEquals(CocAutoGattSettings.DEFAULT_THRESHOLD, 12)
        assertEquals(1, CocAutoGattSettings.clampThreshold(0))
        assertEquals(64, CocAutoGattSettings.clampThreshold(1000))
    }

    @Test
    fun gattAndClassicRequestsAreNeverChanged() {
        val (_, session) = session(registrations = 50)
        assertEquals(gatt, session.choose(gatt, on).effective)
        assertEquals(classic, session.choose(classic, on).effective)
        assertNull(session.onCocFailure(gatt, on, CocFallbackReason.AVAILABILITY_FAILED))
        assertNull(session.onCocFailure(classic, on, CocFallbackReason.CONNECT_FAILED))
        assertNull(session.onPeerAdvertisesGatt(gatt, on))
    }

    @Test
    fun anAvailabilityFailureSwitchesTheSessionToGattWithTheStandardNotice() {
        val (_, session) = session(registrations = 3)
        val retry = session.onCocFailure(l2cap, on, CocFallbackReason.AVAILABILITY_FAILED)
        assertNotNull(retry)
        assertEquals(gatt, retry!!.effective)
        assertEquals(l2cap, retry.requested)
        assertEquals(CocFallbackReason.AVAILABILITY_FAILED, retry.reason)
        assertTrue(retry.notice!!.startsWith("Using BLE GATT: L2CAP unavailable"))
        assertTrue("Bluetooth off and on" in retry.notice!!)
        // It sticks for later endpoints of the same adapter session.
        assertEquals(gatt, session.choose(l2cap, on).effective)
        // A second failure on a session already on GATT has no further retry.
        assertNull(session.onCocFailure(l2cap, on, CocFallbackReason.CONNECT_FAILED))
    }

    @Test
    fun aConnectFailureSwitchesTheSessionToGatt() {
        val (_, session) = session()
        val retry = session.onCocFailure(l2cap, on, CocFallbackReason.CONNECT_FAILED)!!
        assertEquals(CocFallbackReason.CONNECT_FAILED, retry.reason)
        assertEquals(gatt, session.choose(l2cap, on).effective)
    }

    @Test
    fun anAdapterResetEndsAFailureFallbackAndTheCount() {
        val (ledger, session) = session(registrations = 14)
        session.onCocFailure(l2cap, on, CocFallbackReason.AVAILABILITY_FAILED)
        assertEquals(gatt, session.choose(l2cap, on).effective)
        ledger.reset()
        val fresh = session.choose(l2cap, on)
        assertEquals(l2cap, fresh.effective)
        assertEquals(0, fresh.registrations)
        assertEquals(CocFallbackReason.NONE, fresh.reason)
    }

    @Test
    fun resetOfTheSessionClearsARememberedFailure() {
        val (_, session) = session()
        session.onCocFailure(l2cap, on, CocFallbackReason.AVAILABILITY_FAILED)
        session.reset()
        assertEquals(l2cap, session.choose(l2cap, on).effective)
    }

    @Test
    fun aGattPeerIsCalledOverGattForThatCallOnly() {
        val (_, session) = session(registrations = 2)
        val choice = session.onPeerAdvertisesGatt(l2cap, on)!!
        assertEquals(gatt, choice.effective)
        assertEquals(l2cap, choice.requested)
        assertEquals(CocFallbackReason.PEER_GATT, choice.reason)
        assertEquals("Using BLE GATT: the other phone uses GATT", choice.notice)
        assertEquals(l2cap, session.choose(l2cap, on).effective)
    }

    @Test
    fun thresholdNoticeStatesTheCountAndLimit() {
        val (_, session) = session(registrations = 12)
        val notice = session.choose(l2cap, on).notice!!
        assertTrue(notice, notice.startsWith("Using BLE GATT"))
        assertTrue(notice, "12 of 12" in notice)
    }

    @Test
    fun logLineAlwaysStatesRequestedAndEffectiveTransports() {
        val (_, session) = session(registrations = 12)
        val line = session.choose(l2cap, on).logLine()
        assertTrue(line, "requested=BLE_L2CAP" in line)
        assertTrue(line, "effective=BLE_GATT" in line)
        assertTrue(line, "automatic=true" in line)
        assertTrue(line, "reason=registration_threshold" in line)
        assertTrue(line, "cocRegistrations=12" in line)
        assertTrue(line, "threshold=12" in line)
        val plain = session.choose(gatt, on).logLine()
        assertTrue(plain, "requested=BLE_GATT | effective=BLE_GATT | automatic=false | reason=none" in plain)
    }

    @Test
    fun scanningListsOnlyTheEffectiveTransportUnlessTheFlagIsOnForL2cap() {
        // Flag off: exactly the old filter (advertised == the selected transport).
        for (requested in BluetoothTransportOption.entries) {
            for (advertised in BluetoothTransportOption.entries) {
                assertEquals(
                    advertised == requested,
                    CocAutoGattSession.acceptsAdvertised(requested, requested, advertised, off),
                )
            }
        }
        assertTrue(CocAutoGattSession.acceptsAdvertised(l2cap, l2cap, l2cap, on))
        assertTrue(CocAutoGattSession.acceptsAdvertised(l2cap, l2cap, gatt, on))
        assertFalse(CocAutoGattSession.acceptsAdvertised(l2cap, l2cap, classic, on))
        // On GATT after a fallback: still lists GATT phones, not L2CAP-only phones.
        assertTrue(CocAutoGattSession.acceptsAdvertised(l2cap, gatt, gatt, on))
        assertFalse(CocAutoGattSession.acceptsAdvertised(l2cap, gatt, l2cap, on))
        // A GATT request with the flag on is unchanged.
        assertFalse(CocAutoGattSession.acceptsAdvertised(gatt, gatt, l2cap, on))
    }

    @Test
    fun settingsResolveByTheSharedFeatureRule() {
        // Benchmark launch: only its own extras, never the stored choice, and the threshold always counts.
        assertEquals(
            CocAutoGattSettings(false, 12),
            CocAutoGattSettings.resolve(true, false, 12, true, leakyStack = false),
        )
        assertEquals(
            CocAutoGattSettings(true, 8),
            CocAutoGattSettings.resolve(true, true, 8, false, leakyStack = false),
        )
        // Normal launch: the shipped default; a choice stored before 2026-11-04 (no Settings switch since) is ignored.
        assertEquals(
            CocAutoGattSettings(ConnectivityFeatureDefaults.COC_AUTO_GATT, CocAutoGattSettings.DEFAULT_THRESHOLD, thresholdActive = true),
            CocAutoGattSettings.resolve(false, false, 5, !ConnectivityFeatureDefaults.COC_AUTO_GATT, leakyStack = true),
        )
        // Never decided: the shipped default (off until phone test T6), whatever the launch extras say.
        assertEquals(
            ConnectivityFeatureDefaults.COC_AUTO_GATT,
            CocAutoGattSettings.resolve(false, true, 12, null, leakyStack = true).enabled,
        )
        assertFalse(CocAutoGattSettings().enabled)
    }

    @Test
    fun theDefaultConstantDecidesAndAnOldStoredChoiceIsIgnored() {
        // No Settings switch since 2026-11-04.
        val spec = ConnectivityFeatures.cocAutoGatt.copy(defaultEnabled = true)
        assertTrue(FeatureCatalog.resolve(spec, false, { false }, { null }))
        assertTrue(FeatureCatalog.resolve(spec, false, { true }, { false }))
        // A benchmark launch ignores both the default and the stored choice.
        assertFalse(FeatureCatalog.resolve(spec, true, { false }, { true }))
    }

    @Test
    fun theRegistrationThresholdAppliesOnlyOnStacksThatLeakRegistrations() {
        val (_, session) = session(registrations = 40)
        val healthy = CocAutoGattSettings(enabled = true, thresholdActive = false)
        val choice = session.choose(l2cap, healthy)
        assertEquals(l2cap, choice.effective)
        assertEquals(CocFallbackReason.NONE, choice.reason)
        // A real failure still moves a healthy stack to GATT.
        assertEquals(gatt, session.onCocFailure(l2cap, healthy, CocFallbackReason.CONNECT_FAILED)!!.effective)
        // The leaky stack counts.
        val (_, leaky) = session(registrations = 12)
        assertEquals(gatt, leaky.choose(l2cap, CocAutoGattSettings(enabled = true, thresholdActive = true)).effective)
        assertTrue("thresholdActive=false" in choice.logLine())
    }
}
