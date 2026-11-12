package com.example.bluetoothtest

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattLinkPolicyTest {
    @After
    fun reset() {
        DebugTunables.devicePolicyOff = emptySet()
        GattSwitches.resetToDefaults()
    }

    private fun traits(sdk: Int, manufacturer: String = "samsung", soc: String = "Samsung") =
        DeviceTraits("s5e9945", "erd9945", sdk, manufacturer = manufacturer, socManufacturer = soc)

    // ---- registry entries --------------------------------------------------------------------------------------

    @Test
    fun everyEntryIsSourcedWithAFallbackConfidenceNoteAndAnOffSwitch() {
        val ids = GattLinkPolicy.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (entry in GattLinkPolicy.entries) {
            assertTrue(entry.id, entry.sources.isNotEmpty())
            assertTrue(entry.id, entry.sources.all { it.reference.startsWith("http") && it.note.isNotBlank() })
            assertTrue(entry.id, entry.fallback.isNotBlank())
            assertTrue(entry.id, entry.confidenceNote.isNotBlank())
            assertTrue(entry.id, entry.evidence.isNotEmpty())
            assertEquals("test_device_policy_off=${entry.id}", entry.offSwitch)
            assertEquals(entry.id, PolicyArea.BLUETOOTH_TRANSPORT, entry.area)
            // A real review date, never in the future (entries are reviewed on different days).
            val reviewed = java.time.LocalDate.parse(entry.reviewed)
            assertTrue(entry.id, !reviewed.isBefore(java.time.LocalDate.of(2026, 1, 1)) && !reviewed.isAfter(java.time.LocalDate.now()))
            if (entry.proposed) assertTrue(entry.id, entry.effects.isEmpty()) else assertTrue(entry.id, entry.effects.isNotEmpty())
        }
    }

    @Test
    fun theEntriesReachTheRegistryThroughTheTransportPolicyList() {
        val registered = BluetoothTransportPolicy.entries.map { it.id }.toSet()
        assertTrue(registered.containsAll(GattLinkPolicy.entries.map { it.id }))
        assertEquals(BluetoothTransportPolicy.entries.size, BluetoothTransportPolicy.entries.map { it.id }.toSet().size)
    }

    @Test
    fun activeEntriesFollowTheirSdkWindowsAndAreInertProposalsOtherwise() {
        val a14 = DeviceFamilyPolicy.resolve(traits(34), emptySet())
        assertNotNull(GattLinkPolicy.wedgeRecovery(a14))
        assertNotNull(GattLinkPolicy.closeAfterRegistration(a14))
        assertTrue(GattLinkPolicy.staleConnectedCleanup(a14))
        assertNotNull(GattLinkPolicy.silenceDetector(a14))
        assertNotNull(GattLinkPolicy.poolGuard(a14))
        assertNotNull(GattLinkPolicy.linkSlotLimit(a14))
        assertTrue(GattLinkPolicy.bluetoothAudioDiagnostics(a14))
        assertTrue(a14.isActive("gatt-one-client-per-remote"))
        assertTrue(a14.matchingProposals.any { it.id == "server-only-link-idle-drop" })
        assertFalse(a14.isActive("server-only-link-idle-drop"))

        // Android 15+: the stuck-busy defect and the registration-leak window are over; the diagnostics stay.
        val a15 = DeviceFamilyPolicy.resolve(traits(35), emptySet())
        assertNull(GattLinkPolicy.wedgeRecovery(a15))
        assertNull(GattLinkPolicy.closeAfterRegistration(a15))
        assertNotNull(GattLinkPolicy.silenceDetector(a15))
        assertNotNull(GattLinkPolicy.linkSlotLimit(a15))
        assertTrue(a15.isActive("gatt-one-client-per-remote"))

        // Android 16: the one-client-per-remote bugs are fixed from 15 QPR1; the matcher ends at SDK 35.
        assertFalse(DeviceFamilyPolicy.resolve(traits(36), emptySet()).isActive("gatt-one-client-per-remote"))

        // Android 12 miscount window and the pre-API-33 wedge.
        val a12 = DeviceFamilyPolicy.resolve(traits(31), emptySet())
        assertTrue(a12.matchingProposals.any { it.id == "android12-le-acl-segment-miscount" })
        assertNotNull(GattLinkPolicy.wedgeRecovery(a12))
        assertFalse(DeviceFamilyPolicy.resolve(traits(34), emptySet()).matchingProposals.any { it.id == "android12-le-acl-segment-miscount" })
    }

    @Test
    fun theOffSwitchTurnsEachActiveEntryOff() {
        for (entry in GattLinkPolicy.entries.filterNot { it.proposed }) {
            // A phone on which the entry applies (some entries match only the Android 10/11 stack or one SoC family).
            val phone = listOf(
                traits(33), traits(30), traits(29), traits(33, "Xiaomi", "Mediatek"),
                // A MediaTek Realme UI phone on Android 16 (mediatek-coloros-native-gatt-client-wedge, API 36+ only).
                traits(36, "realme", "Mediatek"),
            ).firstOrNull {
                DeviceFamilyPolicy.resolve(it, emptySet()).isActive(entry.id)
            }
            assertNotNull("${entry.id} applies to no test phone", phone)
            val off = DeviceFamilyPolicy.resolve(phone!!, setOf(entry.id))
            assertFalse(entry.id, off.isActive(entry.id))
            assertTrue(entry.id, off.switchedOff.any { it.id == entry.id })
        }
        val allOff = DeviceFamilyPolicy.resolve(traits(33), setOf("all"))
        assertNull(GattLinkPolicy.wedgeRecovery(allOff))
        assertNull(GattLinkPolicy.poolGuard(allOff))
    }

    @Test
    fun theShippedWriteBusyRetryStepStaysTwoMillisecondsAndIsBounded() {
        // Root review: the 25 ms step belongs to the Experimental test_gatt_busy_wedge=recover arm only.
        val retry = BluetoothTransportPolicy.writeBusyRetry(DeviceFamilyPolicy.resolve(traits(33), emptySet()))!!
        assertEquals(GattWriteBusyPolicy.LEGACY_STEP_MS, retry.stepMs)
        assertEquals(2L, retry.stepMs)
        assertEquals(500L, retry.maxWaitMs)
        assertEquals(25L, GattWriteBusyPolicy.STEP_MS)
    }

    @Test
    fun theSamsungFiveSecondEntryIsAReclassifiedProposalWithAnAospSource() {
        val entry = BluetoothTransportPolicy.SAMSUNG_DIRECT_CONNECT_TIMEOUT
        assertTrue(entry.proposed)
        assertTrue(entry.behaviour, "AOSP has no 5 s direct-connect timer" in entry.behaviour)
        assertTrue(entry.sources.any { it.kind == PolicySourceKind.AOSP_SOURCE })
        assertTrue(DeviceFamilyPolicy.resolve(traits(34), emptySet()).matchingProposals.any { it.id == entry.id })
    }

    // ---- wedge recovery ----------------------------------------------------------------------------------------

    private val busy = 201

    @Test
    fun aBusyAnswerWithNothingInFlightEndsTheLinkAtOnceWithoutSpinning() {
        var writes = 0
        var sleeps = 0
        val outcome = retryBusyGattWrite(
            busy, GattWriteBusyRetry(500L, 25L), { writes++; busy }, { sleeps++ }, { 0L }, { false },
            wedge = BusyWedgeCheck(ownOutstanding = { false }, otherLocalHolder = { false }),
        )
        assertTrue(outcome.wedged)
        assertEquals(busy, outcome.result)
        assertEquals(0, outcome.retries)
        assertEquals(1, writes)
        assertEquals(0, sleeps)
    }

    @Test
    fun anOwnOutstandingWriteOrAnotherLocalHolderKeepsTheBoundedRetry() {
        for (check in listOf(
            BusyWedgeCheck(ownOutstanding = { true }, otherLocalHolder = { false }),
            BusyWedgeCheck(ownOutstanding = { false }, otherLocalHolder = { true }),
        )) {
            var clockNs = 0L
            val answers = ArrayDeque(listOf(busy, busy, 0))
            var writes = 0
            val outcome = retryBusyGattWrite(
                busy, GattWriteBusyRetry(500L, 25L), { writes++; answers.removeFirst() },
                { clockNs += it * 1_000_000L }, { clockNs }, { false }, wedge = check,
            )
            assertFalse(outcome.wedged)
            assertEquals(0, outcome.result)
            assertEquals(2, outcome.retries)
            assertEquals(3, writes)
        }
    }

    @Test
    fun theRetryUsesTheNewStepAndTheSameDeadline() {
        var clockNs = 0L
        var writes = 0
        val outcome = retryBusyGattWrite(
            busy, GattWriteBusyRetry(500L, 25L), { writes++; busy }, { clockNs += it * 1_000_000L }, { clockNs }, { false },
            wedge = BusyWedgeCheck({ true }, { false }),
        )
        assertEquals(20, outcome.retries) // 500 ms / 25 ms, against 250 retries at 2 ms
        assertEquals(21, writes)
        assertFalse(outcome.wedged)
    }

    @Test
    fun withoutAWedgeCheckTheLegacyBehaviourIsUnchanged() {
        var writes = 0
        val outcome = retryBusyGattWrite(busy, GattWriteBusyRetry(10L, 2L), { writes++; busy }, {}, { 0L }, { true })
        assertFalse(outcome.wedged)
        assertEquals(1, writes)
        // A non-busy failure is never a wedge, whatever the check says.
        val failed = retryBusyGattWrite(
            busy, GattWriteBusyRetry(500L, 25L), { 200 }, {}, { 0L }, { false },
            wedge = BusyWedgeCheck({ false }, { false }),
        )
        assertEquals(BusyWriteOutcome(200, 0, wedged = false), failed)
        // A success is never a wedge.
        assertEquals(BusyWriteOutcome(0, 0), retryBusyGattWrite(busy, null, { 0 }, {}, { 0L }, { false }, BusyWedgeCheck({ false }, { false })))
    }

    @Test
    fun theWedgeCheckNeedsBothNoOwnOpAndNoOtherHolder() {
        assertTrue(BusyWedgeCheck({ false }, { false }).wedged())
        assertFalse(BusyWedgeCheck({ true }, { false }).wedged())
        assertFalse(BusyWedgeCheck({ false }, { true }).wedged())
    }

    // ---- close after registration ------------------------------------------------------------------------------

    @Test
    fun aClientWithNoCallbackYetIsClosedOnlyAfterItsFirstCallbackOrTheBound() {
        val steps = mutableListOf<String>()
        var waitedFor = -1L
        val queue = ArrayDeque<() -> Unit>()
        GattClientTeardown.run(
            deferred = false,
            disconnect = { steps += "disconnect" },
            awaitDisconnected = { false },
            close = { steps += "close" },
            async = { body -> queue.addLast(body) },
            log = { steps += "log:" + it.substringBefore(':') },
            owner = "t",
            awaitRegistration = { timeout -> waitedFor = timeout; true },
        )
        // The caller's thread only disconnected; the close is queued behind the registration wait.
        assertEquals(listOf("disconnect"), steps)
        queue.removeFirst()()
        assertEquals(GattClientTeardown.REGISTRATION_WAIT_MS, waitedFor)
        assertEquals(listOf("disconnect", "close", "log:GATT client closed after registration"), steps)
    }

    @Test
    fun aClientThatAlreadyHadACallbackClosesImmediatelyAsBefore() {
        val steps = mutableListOf<String>()
        GattClientTeardown.run(
            deferred = false, disconnect = { steps += "disconnect" }, awaitDisconnected = { false },
            close = { steps += "close" }, async = { error("must not defer") }, log = {}, owner = "t",
        )
        assertEquals(listOf("disconnect", "close"), steps)
    }

    @Test
    fun theRegistrationWaitComposesWithTheAndroid1011DisconnectWait() {
        val steps = mutableListOf<String>()
        val queue = ArrayDeque<() -> Unit>()
        GattClientTeardown.run(
            deferred = true, disconnect = { steps += "disconnect" },
            awaitDisconnected = { steps += "await-disconnect"; true }, close = { steps += "close" },
            async = { queue.addLast(it) }, log = { steps += "log:" + it.substringBefore(':') }, owner = "t",
            awaitRegistration = { steps += "await-registration"; true },
        )
        queue.removeFirst()()
        assertEquals(
            listOf("disconnect", "await-registration", "await-disconnect", "close", "log:GATT client closed after registration+disconnect"),
            steps,
        )
    }

    @Test
    fun theTeardownSignalKnowsWhenTheStackHasRegisteredTheClient() {
        val signal = GattTeardownSignal()
        assertFalse(signal.callbackSeen)
        assertFalse(signal.awaitCallback(5L))
        signal.onCallback()
        assertTrue(signal.callbackSeen)
        assertTrue(signal.awaitCallback(5L))
        assertFalse(signal.disconnected)
        // A disconnect callback is a callback too.
        val other = GattTeardownSignal()
        other.onDisconnected()
        assertTrue(other.callbackSeen)
        assertTrue(other.disconnected)
        // GattAttempt marks the first callback before deciding whether to act.
        val attempt = GattAttempt()
        attempt.onConnectionState(disconnected = false)
        assertTrue(attempt.teardown.callbackSeen)
    }

    @Test
    fun theRegistrationWaitIsOnlyNeededWhenTheEntryAndTheSwitchAreOnAndNoCallbackWasSeen() {
        val fresh = GattTeardownSignal()
        val seen = GattTeardownSignal().also { it.onCallback() }
        assertFalse(GattClientTeardown.registrationWaitFor(null))
        assertFalse(GattClientTeardown.registrationWaitFor(seen))
        GattSwitches.applyExtras({ key, d -> if (key == "test_gatt_close_deferral") false else d }, { _, d -> d }, { null })
        if (!BuildConfig.DEBUG) {
            // =false is a DEBUG control arm: a release build keeps close deferral on.
            assertTrue(GattSwitches.closeDeferral)
            return
        }
        assertFalse(GattClientTeardown.registrationWaitFor(fresh))
    }

    // ---- payload sizing ----------------------------------------------------------------------------------------

    @Test
    fun theDefaultValueSizeIsUnchangedAndTheExperimentalArmsFillWholeLePdus() {
        assertEquals(512, gattValueBytes(517, false))
        assertEquals(244, gattValueBytes(517, false, GattSwitches.Payload.ONE_PDU))
        assertEquals(495, gattValueBytes(517, false, GattSwitches.Payload.TWO_PDUS))
        assertEquals(gattValueBytes(517, true), gattValueBytes(517, false, GattSwitches.Payload.TWO_PDUS))
        // MTU 247: one PDU fits (244), two do not, so the two-PDU arm falls back to the aligned size.
        assertEquals(244, gattValueBytes(247, false, GattSwitches.Payload.ONE_PDU))
        assertEquals(244, gattValueBytes(247, false, GattSwitches.Payload.TWO_PDUS))
        // A small MTU is never exceeded.
        assertEquals(20, gattValueBytes(23, false, GattSwitches.Payload.ONE_PDU))
        assertEquals(239, gattChunkPayloadBytes(517, false, GattSwitches.Payload.ONE_PDU))
        assertEquals(507, gattChunkPayloadBytes(517, false))
    }

    @Test
    fun onAndroid12AnAttValueJustOverABufferMultipleIsShortenedToTheMultiple() {
        // value 245..248 -> L2CAP 252..255 = (251, 255]: shortened so the PDU is exactly 251 (value 244).
        for (value in 245..248) assertEquals("value $value", 244, gattMiscountSafeValueBytes(value, 31))
        // 249 -> L2CAP 256: outside the window.
        assertEquals(249, gattMiscountSafeValueBytes(249, 31))
        // Second window: L2CAP 503..510 -> value 496..503 -> 495.
        for (value in 496..503) assertEquals("value $value", 495, gattMiscountSafeValueBytes(value, 32))
        assertEquals(504, gattMiscountSafeValueBytes(504, 32))
        // At or under one PDU, and on other releases, nothing changes.
        assertEquals(244, gattMiscountSafeValueBytes(244, 31))
        assertEquals(246, gattMiscountSafeValueBytes(246, 30))
        assertEquals(246, gattMiscountSafeValueBytes(246, 33))
        assertEquals(1, gattMiscountSafeValueBytes(1, 31))
    }

    @Test
    fun theSizesTheTwoArmsProduceNeverFallInAnAndroid12MiscountWindow() {
        for (value in listOf(gattValueBytes(517, false, GattSwitches.Payload.ONE_PDU), gattValueBytes(517, false, GattSwitches.Payload.TWO_PDUS))) {
            assertEquals(value, gattMiscountSafeValueBytes(value, 31))
        }
    }

    // ---- stale CONNECTED ---------------------------------------------------------------------------------------

    @Test
    fun aStaleConnectedCallbackClosesAClientNobodyIsClosing() {
        val registry = GattResourceRegistry { 0L }
        val client = Any()
        val closed = AtomicBoolean(false)
        registry.registerObject(client, "gatt-video", GattResourceRegistry.Kind.CLIENT, "AA:BB", closer = { closed.set(true) })
        assertTrue(registry.needsStaleCleanup(client))
        registry.markClosing(client) // a normal cancel already started the teardown
        assertFalse(registry.needsStaleCleanup(client))
    }
}
