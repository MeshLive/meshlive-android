package com.example.bluetoothtest

import android.bluetooth.BluetoothDevice
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GattClientTeardown: Android 10/11 GattService ContextMap.removeConnectionsByAppId is
 * unsynchronized, and close() straight after disconnect() threw ConcurrentModificationException in
 * the A15s Bluetooth process (run2c case 0017). BalancedSwitchSteps: the low-latency -> balanced
 * advertising step keeps the set (and its address) or falls back to the old restart.
 */
class LeLinkTeardownAndAdvertisingTest {
    @Test
    fun closeIsDeferredOnlyOnStacksWithTheRacyContextMap() {
        assertTrue(GattClientTeardown.deferredFor(stack(29), null)) // A15s, Android 10
        assertTrue(GattClientTeardown.deferredFor(stack(30), null)) // ASUS, Android 11
        assertFalse(GattClientTeardown.deferredFor(stack(31), null)) // AOSP 1a2c1e97d203 (Android 12)
        assertFalse(GattClientTeardown.deferredFor(stack(35), null))
        assertTrue(GattClientTeardown.deferredFor(stack(35), true))
        assertFalse(GattClientTeardown.deferredFor(stack(29), false))
    }

    @Test
    fun sharedCocListenersOnlyWhereClosedServersLeak() {
        assertTrue(CocListeners.sharedEnabledFor(stack(29), null)) // Android 10: no StopServer
        assertFalse(CocListeners.sharedEnabledFor(stack(30), null)) // AOSP d3c996662453 (Android 11)
        assertFalse(CocListeners.sharedEnabledFor(stack(35), null))
        assertTrue(CocListeners.sharedEnabledFor(stack(35), true))
        assertFalse(CocListeners.sharedEnabledFor(stack(29), false))
    }

    @Test
    fun deviceFamilyEntriesCiteTheirAospFixesAndOffSwitches() {
        val leak = DeviceFamilyPolicy.LE_COC_SERVER_LEAK
        assertTrue(leak.sources.any { it.reference.contains("d3c9966624530772fbdc469179726bd9191314c5") })
        assertTrue(leak.legacySwitches.any { it.contains("test_coc_shared_listener") })
        assertEquals("test_device_policy_off=le-coc-server-leak", leak.offSwitch)
        val race = DeviceFamilyPolicy.GATT_CONTEXT_MAP_RACE
        assertTrue(race.sources.any { it.reference.contains("1a2c1e97d203bd8e635104c72abdba16944ee3b6") })
        assertTrue(race.confidenceNote.contains("unproven"))
        val eatt = DeviceFamilyPolicy.LE_COC_EATT_STRANDED_CHANNELS
        assertTrue(eatt.sources.any { it.reference.contains("l2c_ble.cc#654") })
        assertTrue(eatt.sources.any { it.note.contains("l2cble_handle_connect_rsp_neg") })
        assertTrue(eatt.legacySwitches.any { it.contains("test_coc_fresh_acl") })
        assertTrue(DeviceFamilyPolicy.entries.containsAll(listOf(leak, race, eatt)))
    }

    @Test
    fun freshAclCocOnlyOnStacksThatStrandRefusedEattChannels() {
        assertFalse(BleCocFreshAcl.enabledFor(stack(30), null)) // ASUS, Android 11: no EATT client
        assertTrue(BleCocFreshAcl.enabledFor(stack(31), null)) // M31, Android 12
        assertTrue(BleCocFreshAcl.enabledFor(stack(33), null)) // android13-release has the same loop
        assertFalse(BleCocFreshAcl.enabledFor(stack(34), null)) // android14 l2cble_handle_connect_rsp_neg
        assertTrue(BleCocFreshAcl.enabledFor(stack(35), true))
        assertFalse(BleCocFreshAcl.enabledFor(stack(31), false))
    }

    @Test
    fun freshAclWatchIsArmedBeforeTheControlClosesAndAlwaysReleased() {
        val steps = CopyOnWriteArrayList<String>()
        val lines = CopyOnWriteArrayList<String>()
        var now = 0L
        val watch = object : AclDownWatch {
            override fun await(timeoutMs: Long): Boolean {
                steps += "await $timeoutMs"
                now += 1_150_000_000L
                return true
            }
            override fun release() {
                steps += "release"
            }
        }
        val down = BleCocFreshAcl.releaseControlAndAwaitAclDown(
            watch = watch,
            closeControl = { steps += "close" },
            nowNs = { now },
            log = { lines += it },
            owner = "private-call-connect",
        )
        assertTrue(down)
        assertEquals(listOf("close", "await ${BleCocFreshAcl.ACL_DOWN_WAIT_MS}", "release"), steps)
        assertEquals(
            listOf("BENCHMARK coc_fresh_acl: owner=private-call-connect | waitedMs=1150 | aclDown=true"),
            lines,
        )
    }

    @Test
    fun freshAclNeverWaitsOnTheMainThread() {
        val lines = CopyOnWriteArrayList<String>()
        var awaited = false
        var released = false
        val watch = object : AclDownWatch {
            override fun await(timeoutMs: Long): Boolean {
                awaited = true
                return true
            }
            override fun release() {
                released = true
            }
        }
        val down = BleCocFreshAcl.releaseControlAndAwaitAclDown(
            watch = watch,
            closeControl = {},
            nowNs = { 0L },
            log = { lines += it },
            owner = "forwarder-connect",
            mayBlock = false,
        )
        assertFalse(down)
        assertFalse(awaited)
        assertTrue(released)
        assertEquals(
            listOf("BENCHMARK coc_fresh_acl: owner=forwarder-connect | waitedMs=0 | aclDown=false | skipped=main-thread"),
            lines,
        )
    }

    @Test
    fun freshAclTimeoutOrFailingCloseStillProceedsAndReportsAclUp() {
        val lines = CopyOnWriteArrayList<String>()
        var released = false
        val watch = object : AclDownWatch {
            override fun await(timeoutMs: Long): Boolean = false
            override fun release() {
                released = true
            }
        }
        val down = BleCocFreshAcl.releaseControlAndAwaitAclDown(
            watch = watch,
            closeControl = { error("close failed") },
            nowNs = { 0L },
            log = { lines += it },
            owner = "relay-connect",
        )
        assertFalse(down)
        assertTrue(released)
        assertTrue(lines.single().endsWith("aclDown=false"))
    }

    @Test
    fun expiredGattAttemptIgnoresCallbacksButStillReportsItsDisconnect() {
        val attempt = GattAttempt()
        assertTrue(attempt.onConnectionState(disconnected = false))
        attempt.cancel()
        assertFalse(attempt.active)
        assertFalse(attempt.onConnectionState(disconnected = false))
        assertFalse(attempt.onConnectionState(disconnected = true))
        assertTrue("the deferred close still learns of the disconnect", attempt.teardown.await(1))
    }

    @Test
    fun fallbackReplacementOutcomeIsBoundedAndReported() {
        assertEquals(null, StableAdvertisingSet.replacementFailure(0))
        assertTrue(StableAdvertisingSet.replacementFailure(null)!!.contains("did not start within"))
        assertTrue(StableAdvertisingSet.replacementFailure(StableAdvertisingSet.START_THREW)!!.contains("could not be requested"))
        assertEquals("replacement set failed (code 2)", StableAdvertisingSet.replacementFailure(2))
    }

    private fun stack(sdkInt: Int) = BluetoothStackFingerprint(hardware = "mt6765", board = "board", sdkInt = sdkInt)

    @Test
    fun immediateTeardownDisconnectsThenClosesOnTheCallingThread() {
        val steps = CopyOnWriteArrayList<String>()
        GattClientTeardown.run(
            deferred = false,
            disconnect = { steps += "disconnect" },
            awaitDisconnected = { steps += "await"; true },
            close = { steps += "close" },
            async = { steps += "async"; it() },
            log = {},
            owner = "test",
        )
        assertEquals(listOf("disconnect", "close"), steps)
    }

    @Test
    fun deferredTeardownClosesOnlyAfterTheDisconnectWaitOffThread() {
        val steps = CopyOnWriteArrayList<String>()
        val logs = CopyOnWriteArrayList<String>()
        var waitedFor = 0L
        GattClientTeardown.run(
            deferred = true,
            disconnect = { steps += "disconnect" },
            awaitDisconnected = { timeoutMs -> waitedFor = timeoutMs; steps += "await"; true },
            close = { steps += "close" },
            async = { body -> steps += "async"; body() },
            log = { logs += it },
            owner = "gatt-video",
        )
        assertEquals(listOf("disconnect", "async", "await", "close"), steps)
        assertEquals(GattClientTeardown.DISCONNECT_WAIT_MS, waitedFor)
        assertTrue(logs.single().contains("confirmed=true"))
    }

    @Test
    fun deferredTeardownStillClosesWhenTheDisconnectIsNeverConfirmedOrThrows() {
        val closes = CopyOnWriteArrayList<String>()
        GattClientTeardown.run(
            deferred = true,
            disconnect = { throw IllegalStateException("already disconnected") },
            awaitDisconnected = { throw InterruptedException() },
            close = { closes += "close" },
            async = { it() },
            log = {},
            owner = "ble-control",
        )
        assertEquals(1, closes.size)
    }

    @Test
    fun teardownSignalReportsTheDisconnectCallback() {
        val signal = GattTeardownSignal()
        assertFalse(signal.await(1))
        signal.onDisconnected()
        assertTrue(signal.await(1))
    }

    @Test
    fun inPlaceBalancedStepIsDisableThenParametersThenEnable() {
        val steps = BalancedSwitchSteps()
        assertTrue(steps.begin())
        assertFalse("one step per advertising set", steps.begin())
        assertEquals(BalancedSwitchSteps.Action.SET_PARAMETERS, steps.onEnabled(enable = false, success = true))
        assertEquals(BalancedSwitchSteps.Action.ENABLE, steps.onParametersUpdated(success = true))
        assertEquals(BalancedSwitchSteps.Action.DONE, steps.onEnabled(enable = true, success = true))
        assertEquals(BalancedSwitchSteps.Step.DONE, steps.step)
        assertEquals("a late timeout does nothing", BalancedSwitchSteps.Action.NONE, steps.onTimeout())
    }

    @Test
    fun anUnconfirmedDisableKeepsTheSameSetAndLaterStepsAreSkipped() {
        // win-userflow asus-winvideo: the ASUS's stack disabled the set but its callback died (DeadObjectException),
        // the step timed out and the set was replaced: a new address 12 s after every low-latency start.
        InPlaceStepHealth.reset()
        try {
            assertFalse(InPlaceStepHealth.skipBalanced(enabled = true))
            assertEquals(
                "a later step that timed out restarts as before",
                InPlaceStepHealth.Recovery.RESTART,
                InPlaceStepHealth.onTimeout(BalancedSwitchSteps.Step.UPDATING, enabled = true),
            )
            assertFalse(InPlaceStepHealth.skipBalanced(enabled = true))
            assertEquals(
                "control arm",
                InPlaceStepHealth.Recovery.RESTART,
                InPlaceStepHealth.onTimeout(BalancedSwitchSteps.Step.DISABLING, enabled = false),
            )
            assertFalse(InPlaceStepHealth.skipBalanced(enabled = true))
            assertEquals(
                InPlaceStepHealth.Recovery.REENABLE_SAME_SET,
                InPlaceStepHealth.onTimeout(BalancedSwitchSteps.Step.DISABLING, enabled = true),
            )
            assertTrue("the next balanced step would fail the same way", InPlaceStepHealth.skipBalanced(enabled = true))
            assertFalse("control arm", InPlaceStepHealth.skipBalanced(enabled = false))
            // The step machine itself still reports a stalled disable as a restart; the recovery decides.
            BalancedSwitchSteps().run {
                begin()
                assertEquals(BalancedSwitchSteps.Action.RESTART, onTimeout())
            }
            assertTrue(AdvertisingSwitches.inPlaceKeepSet)
        } finally {
            InPlaceStepHealth.reset()
        }
    }

    @Test
    fun onlyOurOwnStepIsLoggedAsBalancedInPlace() {
        val steps = BalancedSwitchSteps()
        steps.begin()
        steps.onEnabled(enable = false, success = true)
        steps.onParametersUpdated(success = true)
        assertEquals("balanced_in_place", BalancedSwitchSteps.churnEvent(steps.onEnabled(enable = true, success = true)))
        // The stack re-arms the legacy set after every inbound connection: not our churn.
        assertEquals("stack_enable", BalancedSwitchSteps.churnEvent(steps.onEnabled(enable = true, success = true)))
        assertEquals("stack_enable", BalancedSwitchSteps.churnEvent(BalancedSwitchSteps().onEnabled(enable = true, success = true)))
    }

    @Test
    fun anyFailedOrStalledStepFallsBackToTheOldRestart() {
        BalancedSwitchSteps().run {
            begin()
            assertEquals(BalancedSwitchSteps.Action.RESTART, onEnabled(enable = false, success = false))
        }
        BalancedSwitchSteps().run {
            begin()
            onEnabled(enable = false, success = true)
            assertEquals(BalancedSwitchSteps.Action.RESTART, onParametersUpdated(success = false))
        }
        BalancedSwitchSteps().run {
            begin()
            onEnabled(enable = false, success = true)
            onParametersUpdated(success = true)
            assertEquals(BalancedSwitchSteps.Action.RESTART, onEnabled(enable = true, success = false))
        }
        BalancedSwitchSteps().run {
            begin()
            onEnabled(enable = false, success = true)
            assertEquals("stalled after disable", BalancedSwitchSteps.Action.RESTART, onTimeout())
            assertEquals(BalancedSwitchSteps.Step.FAILED, step)
            assertEquals("restart requested once", BalancedSwitchSteps.Action.NONE, onTimeout())
        }
    }

    @Test
    fun callbacksOutsideTheStepAreIgnored() {
        val steps = BalancedSwitchSteps()
        assertEquals(BalancedSwitchSteps.Action.NONE, steps.onEnabled(enable = true, success = true))
        assertEquals(BalancedSwitchSteps.Action.NONE, steps.onParametersUpdated(success = true))
        assertEquals(BalancedSwitchSteps.Action.NONE, steps.onTimeout())
        assertEquals(BalancedSwitchSteps.Step.IDLE, steps.step)
    }

    @Test
    fun scanStatsSeparateAnEmptyScannerFromFilteredResults() {
        val stats = ScanStats("a1")
        stats.raw = 12
        stats.withoutAnnouncement = 9
        stats.rejected = 2
        stats.accepted = 1
        val line = stats.line("running")
        assertTrue(line.startsWith("scan_stats: scanner=a1 | state=running | elapsedMs="))
        assertTrue(line.endsWith("raw=12 | withoutAnnouncement=9 | rejected=2 | accepted=1"))
        stats.reset()
        assertTrue(stats.line("stopped").endsWith("raw=0 | withoutAnnouncement=0 | rejected=0 | accepted=0"))
    }

    @Test
    fun bluetoothRestartOnlyWhereAppsMayToggleIdleAndOneAtATime() {
        assertEquals(null, BluetoothRestart.refusal(29, busyReason = null, alreadyRunning = false)) // A15s
        assertEquals(null, BluetoothRestart.refusal(32, busyReason = null, alreadyRunning = false))
        assertEquals("sdk_33", BluetoothRestart.refusal(33, busyReason = null, alreadyRunning = false))
        assertEquals("busy_receiver", BluetoothRestart.refusal(29, busyReason = "receiver", alreadyRunning = false))
        assertEquals("already_running", BluetoothRestart.refusal(29, busyReason = null, alreadyRunning = true))
        assertTrue(BluetoothRestart.tryBegin())
        try {
            assertFalse("single flight", BluetoothRestart.tryBegin())
        } finally {
            BluetoothRestart.end()
        }
    }

    @Test
    fun bluetoothRestartReportsWhetherTheLeStackReallyWentDown() {
        // Full off passes BLE_ON (intermediate) and BLE_TURNING_OFF (16) before OFF (10).
        assertEquals("verified", BluetoothRestart.leReset(listOf(13, 15, 16, 10, 14, 15, 11, 12)))
        // A background BLE app keeps the stack at BLE_ON although the public state reads OFF.
        assertEquals("le_kept_on", BluetoothRestart.leReset(listOf(13, 15, 11, 12)))
        assertEquals("unverified", BluetoothRestart.leReset(emptyList()))
    }

    @Test
    fun ble1mPhyIsAnOffByDefaultExperimentOn2MOnlySelection() {
        assertEquals(BluetoothDevice.PHY_LE_2M_MASK, BlePhyPreference.TWO_M.mask)
        assertEquals("2M", BlePhyPreference.TWO_M.label)
        try {
            DebugTunables.experimentalBlePhy = ExperimentalBlePhy.LE_1M
            if (BuildConfig.DEBUG) {
                assertEquals(BluetoothDevice.PHY_LE_1M_MASK, BlePhyPreference.TWO_M.mask)
                assertEquals("1M (experimental)", BlePhyPreference.TWO_M.label)
            } else {
                // A release build ignores the experiment: 2M stays.
                assertEquals(BluetoothDevice.PHY_LE_2M_MASK, BlePhyPreference.TWO_M.mask)
                assertEquals("2M", BlePhyPreference.TWO_M.label)
            }
            // 2M is the only user-facing PHY; ONE_M_ADAPTIVE is chosen internally for a peer remembered bad on 2M.
        assertEquals(listOf(BlePhyPreference.TWO_M, BlePhyPreference.ONE_M_ADAPTIVE), BlePhyPreference.entries)
        } finally {
            DebugTunables.experimentalBlePhy = null
        }
        assertEquals(BluetoothDevice.PHY_LE_2M_MASK, BlePhyPreference.TWO_M.mask)
    }
}
