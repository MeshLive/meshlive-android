package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Registry metadata says what a phone actually runs (switch audit 2026-10-27, A5 and A10). */
class DevicePolicyHygieneTest {
    private val android14 = DeviceTraits("qcom", "kalama", 34, manufacturer = "samsung")

    @After
    fun restoreArms() {
        GattSwitches.busyWedgeRecovery = false
        GattSwitches.relayAcknowledgedWrites = false
    }

    @Test
    fun aSupersededProposalIsNeverReportedAsMatching() {
        val resolved = DeviceFamilyPolicy.resolve(android14, emptySet())
        val proposals = resolved.matchingProposals.map { it.id }
        listOf("ble-scan-start-quota", "android-14-gatt-mtu-517", "background-restriction-visible").forEach { id ->
            assertFalse("$id is a superseded duplicate", id in proposals)
        }
        // Their replacements are the implemented entries.
        assertTrue(resolved.isActive("le-scan-start-quota"))
        assertTrue(resolved.isActive("gatt-mtu-fixed-517"))
    }

    @Test
    fun anEntryWaitingForItsExperimentalArmIsNotReportedActive() {
        val id = "android13-14-gatt-client-wedged-after-busy"
        GattSwitches.busyWedgeRecovery = false
        val off = DeviceFamilyPolicy.resolve(android14, emptySet())
        assertFalse(off.reportedActive.any { it.id == id })
        assertTrue(off.armOff.any { it.id == id })
        val line = DeviceFamilyPolicy.benchmarkLine(off)
        assertFalse(line.substringAfter("active=").substringBefore(" |").split(",").contains(id))
        assertTrue(line.substringAfter("armOff=").substringBefore(" |").split(",").contains(id))

        GattSwitches.busyWedgeRecovery = true // DEBUG unit tests: the arm is settable
        if (!BuildConfig.DEBUG) {
            // A release build ignores the arm: the entry keeps waiting for it.
            assertFalse(GattSwitches.busyWedgeRecovery)
            assertFalse(DeviceFamilyPolicy.resolve(android14, emptySet()).reportedActive.any { it.id == id })
            return
        }
        val on = DeviceFamilyPolicy.resolve(android14, emptySet())
        assertTrue(on.reportedActive.any { it.id == id })
        assertFalse(on.armOff.any { it.id == id })
        assertTrue(GattLinkPolicy.wedgeRecovery(on) != null)
    }

    @Test
    fun aLegacyQualcommCallRelayWritesWithoutResponseUnlessItsExperimentalArmIsOn() {
        val id = "qcom-legacy-call-relay-gatt-acknowledged-writes"
        val asus = DeviceTraits("qcom", "msmnile", 30, manufacturer = "asus")
        GattSwitches.relayAcknowledgedWrites = false
        val off = DeviceFamilyPolicy.resolve(asus, emptySet())
        // The phone matches the family, but the entry waits for its arm and is not what the phone runs.
        assertTrue(off.isActive(id))
        assertTrue(off.armOff.any { it.id == id })
        assertFalse(off.reportedActive.any { it.id == id })
        assertFalse(GattLinkPolicy.relayAcknowledgedWrites(off))
        val line = DeviceFamilyPolicy.benchmarkLine(off)
        assertTrue(line.substringAfter("armOff=").substringBefore(" |").split(",").contains(id))

        GattSwitches.relayAcknowledgedWrites = true // DEBUG unit tests: the arm is settable
        if (!BuildConfig.DEBUG) {
            // A release build cannot turn the arm on: the relay never writes with response.
            assertFalse(GattSwitches.relayAcknowledgedWrites)
            assertFalse(GattLinkPolicy.relayAcknowledgedWrites(DeviceFamilyPolicy.resolve(asus, emptySet())))
            return
        }
        val on = DeviceFamilyPolicy.resolve(asus, emptySet())
        assertTrue(on.reportedActive.any { it.id == id })
        assertTrue(GattLinkPolicy.relayAcknowledgedWrites(on))
        // The arm alone changes nothing on a phone outside the family, or with the entry switched off.
        assertFalse(GattLinkPolicy.relayAcknowledgedWrites(DeviceFamilyPolicy.resolve(android14, emptySet())))
        assertFalse(GattLinkPolicy.relayAcknowledgedWrites(DeviceFamilyPolicy.resolve(asus, setOf(id))))
    }

    @Test
    fun theRelayAcknowledgedWritesLaunchExtraIsTheOnlyWayToTurnTheArmOn() {
        assertFalse(GattSwitches.relayAcknowledgedWrites)
        GattSwitches.applyExtras(
            boolExtra = { key, default -> if (key == "test_relay_gatt_acknowledged_writes") true else default },
            intExtra = { _, default -> default },
            stringExtra = { null },
        )
        assertEquals(BuildConfig.DEBUG, GattSwitches.relayAcknowledgedWrites)
        GattSwitches.resetToDefaults()
        assertFalse(GattSwitches.relayAcknowledgedWrites)
    }

    @Test
    fun everyBatchHonoursTheTraitsViewsOwnOffSwitches() {
        val id = "bluetooth-process-restart-recovery"
        assertTrue(ScanPolicy.isOff(id, emptySet(), viewOff = setOf(id)))
        assertTrue(ScanPolicy.isOff(id, emptySet(), viewOff = setOf("all")))
        assertFalse(ScanPolicy.isOff(id, emptySet()))
        val view = android14.copy(policyOff = setOf("coc-echo-verification"))
        assertTrue(CocPolicy.resolve(view, emptySet()).switchedOff.any { it.id == "coc-echo-verification" })
    }

    @Test
    fun bluetoothRestartRecoveryShipsOnAndItsOffSwitchKeepsTheOtherBluetoothOnConsumers() {
        val resolved = DeviceFamilyPolicy.resolve(android14, emptySet())
        assertTrue(resolved.isActive("bluetooth-process-restart-recovery"))
        assertFalse(resolved.matchingProposals.any { it.id == "bluetooth-process-restart-recovery" })

        assertEquals(
            listOf(ScanRuntime.CONSUMER_SCAN_REGISTRY, ScanRuntime.CONSUMER_ADVERTISING_BUDGET, ScanRuntime.CONSUMER_LINK_CHANGE_ROLLBACK),
            ScanRuntime.epochConsumers(emptySet()),
        )
        listOf(setOf("bluetooth-process-restart-recovery"), setOf("all")).forEach { off ->
            assertEquals(
                listOf(ScanRuntime.CONSUMER_ADVERTISING_BUDGET, ScanRuntime.CONSUMER_LINK_CHANGE_ROLLBACK),
                ScanRuntime.epochConsumers(off),
            )
        }
    }
}
