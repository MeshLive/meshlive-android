package com.example.bluetoothtest

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * lane11 f3-thin-asus-m31-a15s (ASUS, Android 11): the phone-wide registration pool (32) was exhausted and
 * `gatt_api.cc(975) can't Register GATT client, MAX client reached: 32` appeared 1,575 times in one 3-minute run
 * (3 stack lines per attempt, about 3 attempts a second): each viewer request re-dialled at once. Scanner starts
 * failed with code 2 and GattVideoServer.start() with "Could not open GATT video server", and the debug dials
 * that followed expired as `peer_not_seen`.
 */
class GattPoolBackoffTest {
    private var now = 1_000L

    @Before
    fun setUp() {
        now = 1_000L
        GattSwitches.resetToDefaults()
        GattPoolBackoff.resetForTest(PoolBackoff({ now }), PoolExhaustionPolicy { now })
    }

    @After
    fun tearDown() {
        GattSwitches.resetToDefaults()
        GattPoolBackoff.resetForTest()
    }

    // ---- pure back-off decisions ----

    @Test
    fun theHoldDoublesFromOneSecondToACapOfThirtySeconds() {
        val backoff = PoolBackoff({ now })
        val delays = (1..9).map { backoff.delayFor(it) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L, 30_000L), delays)
        assertEquals(0L, backoff.delayFor(0))
        // A huge failure count never overflows the shift.
        assertEquals(30_000L, backoff.delayFor(10_000))
    }

    @Test
    fun eachFailureAfterTheHoldTakesTheNextStep() {
        val backoff = PoolBackoff({ now })
        val seen = mutableListOf<Long>()
        repeat(7) {
            val step = backoff.onFailure(PoolSource.CLIENT, 257)
            assertNotNull(step)
            seen += step!!.delayMs
            assertEquals(step.delayMs, backoff.holdRemainingMs(PoolSource.CLIENT))
            now += step.delayMs
            assertEquals(0L, backoff.holdRemainingMs(PoolSource.CLIENT))
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), seen)
        assertEquals(7, backoff.failures(PoolSource.CLIENT))
    }

    @Test
    fun aFailureInsideARunningHoldTakesNoStepAndIsNotLogged() {
        val backoff = PoolBackoff({ now })
        assertNotNull(backoff.onFailure(PoolSource.CLIENT, 257))
        now += 400
        // A second attempt that was already in flight: same step, no new line, hold not extended.
        assertNull(backoff.onFailure(PoolSource.CLIENT, 257))
        assertEquals(600L, backoff.holdRemainingMs(PoolSource.CLIENT))
        assertEquals(1, backoff.failures(PoolSource.CLIENT))
    }

    @Test
    fun successResetsTheStepAndTheSourcesAreIndependent() {
        val backoff = PoolBackoff({ now })
        backoff.onFailure(PoolSource.CLIENT, 257)
        now += 1_000
        backoff.onFailure(PoolSource.CLIENT, 257)
        backoff.onFailure(PoolSource.SERVER, null)
        assertEquals(2, backoff.failures(PoolSource.CLIENT))
        assertEquals(1, backoff.failures(PoolSource.SERVER))
        assertEquals(2, backoff.onSuccess(PoolSource.CLIENT))
        assertEquals(0, backoff.failures(PoolSource.CLIENT))
        assertEquals(0L, backoff.holdRemainingMs(PoolSource.CLIENT))
        assertEquals(1, backoff.failures(PoolSource.SERVER))
        // The next failure after a success starts at 1 s again.
        assertEquals(1_000L, backoff.onFailure(PoolSource.CLIENT, 257)!!.delayMs)
        assertEquals(0, backoff.onSuccess(PoolSource.SCANNER))
    }

    @Test
    fun skippedAttemptsAreCountedIntoTheNextStepNotLoggedOneByOne() {
        val backoff = PoolBackoff({ now })
        backoff.onFailure(PoolSource.CLIENT, 257)
        repeat(5) { backoff.noteSkipped(PoolSource.CLIENT) }
        now += 1_000
        val step = backoff.onFailure(PoolSource.CLIENT, 257)!!
        assertEquals(5, step.skipped)
        now += step.delayMs
        assertEquals(0, backoff.onFailure(PoolSource.CLIENT, 257)!!.skipped)
    }

    @Test
    fun aRecentScannerFailureExpiresAndSuccessClearsIt() {
        val backoff = PoolBackoff({ now })
        assertNull(backoff.recentFailureCode(PoolSource.SCANNER, 60_000L))
        backoff.onFailure(PoolSource.SCANNER, 2)
        assertEquals(2, backoff.recentFailureCode(PoolSource.SCANNER, 60_000L))
        now += 60_001
        assertNull("a failure older than the window no longer explains a missing sighting", backoff.recentFailureCode(PoolSource.SCANNER, 60_000L))
        backoff.onFailure(PoolSource.SCANNER, 2)
        backoff.onSuccess(PoolSource.SCANNER)
        assertFalse(backoff.inBackoff(PoolSource.SCANNER, 60_000L))
    }

    // ---- the shared recorder: one line per step, the hold gate, the control arm ----

    @Test
    fun onlyPoolStatusesAreRegistrationFailures() {
        assertTrue(GattPoolBackoff.isPoolStatus(257))
        assertTrue(GattPoolBackoff.isPoolStatus(128))
        assertFalse(GattPoolBackoff.isPoolStatus(133))
        assertFalse(GattPoolBackoff.isPoolStatus(null))
        assertNull(GattPoolBackoff.noteConnectFailure("BLE control connection failed (status 133)", "x"))
        assertNull(GattPoolBackoff.noteConnectFailure("Timed out", "x"))
        assertNotNull(GattPoolBackoff.noteConnectFailure("GATT video connection failed (status 257)", "gatt-video-client"))
    }

    @Test
    fun aRefusedRegistrationHoldsAutomaticConnectsUntilTheStepEnds() {
        assertFalse(GattPoolBackoff.holdsClientConnect())
        GattPoolBackoff.noteConnectFailure("GATT video connection failed (status 257)", "gatt-video-client")
        assertTrue(GattPoolBackoff.holdsClientConnect())
        assertTrue(GattPoolBackoff.holdsClientConnect())
        now += 1_000
        assertFalse("the hold ended: one probe attempt is allowed", GattPoolBackoff.holdsClientConnect())
        val second = GattPoolBackoff.noteConnectFailure("GATT video connection failed (status 257)", "gatt-video-client")!!
        assertEquals(2_000L, second.delayMs)
        assertEquals("the two held attempts are reported with the next step", 2, second.skipped)
        GattPoolBackoff.recordSuccess(PoolSource.CLIENT)
        assertFalse(GattPoolBackoff.holdsClientConnect())
    }

    @Test
    fun theControlArmNeverHolds() {
        GattSwitches.applyExtras({ key, d -> if (key == "test_gatt_pool_backoff") false else d }, { _, d -> d }, { null })
        if (!BuildConfig.DEBUG) {
            // =false is a DEBUG control arm: a release build keeps the back-off on.
            assertTrue(GattSwitches.poolBackoff)
            return
        }
        assertFalse(GattSwitches.poolBackoff)
        assertTrue(GattSwitches.describeNonDefaults().contains("pool_backoff=off"))
        GattPoolBackoff.noteConnectFailure("GATT video connection failed (status 257)", "gatt-video-client")
        assertFalse(GattPoolBackoff.holdsClientConnect())
        // Evidence is independent of the arm.
        assertEquals(1, GattPoolBackoff.shared.failures(PoolSource.CLIENT))
    }

    @Test
    fun theStepLineIsOnePerStepAndCarriesTheEvidence() {
        val step = PoolBackoff.Step(PoolSource.SCANNER, failures = 3, delayMs = 4_000L, code = 2, skipped = 7)
        assertEquals(
            "BENCHMARK gatt_pool_exhausted: source=scanner | event=backoff | owner=scanner-ab12 | failures=3 | " +
                "backoffMs=4000 | code=2 | skippedAttempts=7 | heldRegistrations=5/30",
            GattPoolBackoff.stepLine(step, "scanner-ab12", 5),
        )
        assertEquals(setOf("server", "client", "scanner"), PoolSource.entries.map { it.label }.toSet())
    }

    // ---- the reliability card is fed by scanner and server failures too ----

    @Test
    fun threeScannerStepsRaiseTheBluetoothOffAndOnGuidance() {
        val policy = PoolExhaustionPolicy { now }
        GattPoolBackoff.resetForTest(PoolBackoff({ now }), policy)
        assertFalse(policy.restartGuidanceDue())
        repeat(2) {
            GattPoolBackoff.recordFailure(PoolSource.SCANNER, 2, "scanner-x")
            now += 30_000
        }
        assertFalse(policy.restartGuidanceDue())
        GattPoolBackoff.recordFailure(PoolSource.SCANNER, 2, "scanner-x")
        assertTrue("the stack refused the scanner three times: no second peer is needed", policy.restartGuidanceDue())
        assertTrue(GattPoolBackoff.scannerFailing)
        GattPoolBackoff.recordSuccess(PoolSource.SCANNER)
        assertFalse(policy.restartGuidanceDue())
        assertFalse(GattPoolBackoff.scannerFailing)
    }

    @Test
    fun serverFailuresRaiseItAndStaleOnesFade() {
        val policy = PoolExhaustionPolicy { now }
        GattPoolBackoff.resetForTest(PoolBackoff({ now }), policy)
        repeat(3) {
            GattPoolBackoff.recordFailure(PoolSource.SERVER, null, "gatt-video-server")
            now += 30_000
        }
        assertTrue(policy.restartGuidanceDue())
        now += PoolExhaustionPolicy.REGISTRATION_STALE_MS + 1
        assertFalse("a Bluetooth restart may have cleared the pool: old failures fade", policy.restartGuidanceDue())
    }

    @Test
    fun aConnectSuccessAlsoClearsTheClientRegistrationCount() {
        val policy = PoolExhaustionPolicy { now }
        policy.onRegistrationFailure(PoolSource.CLIENT)
        policy.onRegistrationFailure(PoolSource.CLIENT)
        policy.onRegistrationFailure(PoolSource.CLIENT)
        assertTrue(policy.restartGuidanceDue())
        policy.onConnectSuccess()
        assertFalse(policy.restartGuidanceDue())
    }

    // ---- the debug dial names the scanner, not the peer ----

    @Test
    fun aDialThatExpiresWhileTheScannerCannotRegisterSaysScannerUnavailable() {
        assertEquals("peer_not_seen", DebugDialWait.expiryReason(null))
        assertEquals("scanner_unavailable", DebugDialWait.expiryReason(2))
        assertEquals(
            "BENCHMARK call_invite: failed | node=d1a10000a001 | reason=scanner_unavailable | waitedMs=10000 | " +
                "lastScanFailureCode=2 | scanFailureName=application_registration_failed",
            DebugDialWait.line(
                "failed", "d1a10000a001", "reason" to DebugDialWait.expiryReason(2),
                *DebugDialWait.expiryDetails(2, 10_000L),
            ),
        )
        assertEquals(
            "BENCHMARK call_invite: failed | node=d1a10000a001 | reason=peer_not_seen | waitedMs=10000",
            DebugDialWait.line(
                "failed", "d1a10000a001", "reason" to DebugDialWait.expiryReason(null),
                *DebugDialWait.expiryDetails(null, 10_000L),
            ),
        )
    }

    @Test
    fun theScannerFailureCodeComesFromTheSharedBackoffOnlyWhileItFails() {
        assertNull(GattPoolBackoff.scannerFailureCode())
        GattPoolBackoff.recordFailure(PoolSource.SCANNER, 2, "scanner-x")
        assertEquals(2, GattPoolBackoff.scannerFailureCode())
        now += GattPoolBackoff.SCANNER_RECENT_MS + 1
        assertNull(GattPoolBackoff.scannerFailureCode())
        GattPoolBackoff.recordFailure(PoolSource.SCANNER, 2, "scanner-x")
        GattPoolBackoff.recordSuccess(PoolSource.SCANNER)
        assertNull(GattPoolBackoff.scannerFailureCode())
    }

    // ---- source guards: the retry paths go through the back-off ----

    private fun source(name: String): String {
        val file = listOf(
            File("src/main/java/com/example/bluetoothtest/$name"),
            File("app/src/main/java/com/example/bluetoothtest/$name"),
        ).first { it.exists() }
        return file.readText().replace("\r\n", "\n")
    }

    @Test
    fun theBroadcasterConsultsTheHoldBeforeDialling() {
        val broadcaster = source("BluetoothVideoBroadcaster.kt")
        val connect = broadcaster.substringAfter("val newPeers = peers.distinctBy").substringBefore("sender.connect(")
        assertTrue("viewer requests wait out the hold", connect.contains("GattPoolBackoff.holdsClientConnect()"))
        assertTrue(connect.indexOf("holdsClientConnect") < connect.indexOf("capacityRejections.shouldSuppress"))
    }

    @Test
    fun everyClientConnectPathRecordsRefusedRegistrationsAndSuccess() {
        val gatt = source("GattVideoTransport.kt")
        val open = gatt.substringAfter("object GattVideoClient {").substringBefore("private fun poolFailureRetry")
        assertTrue(open.contains("GattPoolBackoff.noteConnectFailure(error.message, \"gatt-video-client\")"))
        assertTrue(open.contains("GattPoolBackoff.recordSuccess(PoolSource.CLIENT)"))
        assertTrue(
            "the failure is recorded before the one-shot retry decision",
            open.indexOf("noteConnectFailure") < open.indexOf("poolFailureRetry(device"),
        )
        val control = source("BleL2capControl.kt")
        assertTrue(control.contains("GattPoolBackoff.noteConnectFailure(error.message, \"ble-control-client\")"))
        assertTrue(control.contains("GattPoolBackoff.recordSuccess(PoolSource.CLIENT)"))
    }

    @Test
    fun scannerAndServerRegistrationFailuresAreRecorded() {
        val control = source("BleL2capControl.kt")
        val callback = control.substringAfter("override fun onScanFailed(errorCode: Int)").substringBefore("fun start()")
        assertTrue(callback.contains("GattPoolBackoff.recordFailure(PoolSource.SCANNER, errorCode"))
        assertTrue(callback.contains("ScanFailurePolicy.APPLICATION_REGISTRATION_FAILED"))
        val result = control.substringAfter("private fun addResult(result: ScanResult)").substringBefore("val announcementBytes")
        assertTrue(result.contains("GattPoolBackoff.recordSuccess(PoolSource.SCANNER)"))
        val controlServer = control.substringAfter("class BleL2capControlServer(").substringBefore("fun startAdvertising() =")
        assertTrue(controlServer.contains("GattPoolBackoff.recordFailure(PoolSource.SERVER"))
        val gatt = source("GattVideoTransport.kt")
        val videoServer = gatt.substringAfter("class GattVideoServer(").substringBefore("fun startAdvertising() =")
        assertTrue(videoServer.contains("GattPoolBackoff.recordFailure(PoolSource.SERVER"))
    }

    @Test
    fun aServerThatFailsToStartClosesItselfInsteadOfLeakingItsRegistration() {
        // `gattServer = GattVideoServer(..).also(GattVideoServer::start)`: the instance is lost when start() throws.
        for ((file, owner) in listOf("GattVideoTransport.kt" to "class GattVideoServer(", "BleL2capControl.kt" to "class BleL2capControlServer(")) {
            val server = source(file).substringAfter(owner).substringBefore("fun startAdvertising() =")
            val start = server.substringAfter("fun start() {").substringBefore("private fun startOpened(")
            assertTrue("$owner start closes on failure", start.contains("startOpened(") && start.contains("close()") && start.contains("throw error"))
        }
    }

}
