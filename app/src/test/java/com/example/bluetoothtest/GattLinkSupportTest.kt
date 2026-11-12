package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** GattConnectCompat, GattByteWindow, GattLinkLifetime, BluetoothAudioCoexistence and GattSwitches. */
class GattLinkSupportTest {
    @After
    fun reset() {
        GattSwitches.resetToDefaults()
        GattSwitches.applyExtras({ _, d -> d }, { _, d -> d }, { null })
    }

    // ---- GattConnectCompat -------------------------------------------------------------------------------------

    @Test
    fun theWorkerArmCallsConnectOnTheCallingThread() {
        var posted = 0
        val result = GattConnectCompat.connectOn(
            arm = GattConnectCompat.Arm.WORKER, onMainThread = false,
            post = { posted++; true }, call = { "gatt" },
        )
        assertEquals("gatt", result)
        assertEquals(0, posted)
    }

    @Test
    fun theMainArmPostsOnceAndTheWorkerReceivesTheResult() {
        var runs = 0
        val result = GattConnectCompat.connectOn(
            arm = GattConnectCompat.Arm.MAIN_LOOPER, onMainThread = false,
            post = { action -> Thread { action() }.start(); true },
            call = { runs++; "gatt-main" },
            timeoutMs = 2_000L,
        )
        assertEquals("gatt-main", result)
        assertEquals(1, runs)
    }

    @Test
    fun onTheMainThreadItRunsDirectlyWithoutBlocking() {
        var posted = false
        val result = GattConnectCompat.connectOn(
            arm = GattConnectCompat.Arm.MAIN_LOOPER, onMainThread = true,
            post = { posted = true; true }, call = { 7 },
        )
        assertEquals(7, result)
        assertFalse(posted)
    }

    @Test
    fun aMainLooperThatNeverRunsTheCallTimesOutAndALateResultIsDisposed() {
        val pending = ArrayDeque<() -> Unit>()
        val late = mutableListOf<String>()
        val result = GattConnectCompat.connectOn(
            arm = GattConnectCompat.Arm.MAIN_LOOPER, onMainThread = false,
            post = { action -> pending.addLast(action); true },
            call = { "late-gatt" }, timeoutMs = 20L, onLate = { late += it },
        )
        assertNull(result)
        // The main looper finally runs it: the client is handed to onLate (closed), not leaked.
        pending.removeFirst()()
        assertEquals(listOf("late-gatt"), late)
    }

    @Test
    fun aRefusedPostFallsBackToTheWorkerCall() {
        assertEquals("direct", GattConnectCompat.connectOn(
            arm = GattConnectCompat.Arm.MAIN_LOOPER, onMainThread = false, post = { false }, call = { "direct" },
        ))
    }

    // ---- GattByteWindow ----------------------------------------------------------------------------------------

    @Test
    fun theWindowIsInertWhenOff() {
        val window = GattByteWindow(0, { 0L })
        assertFalse(window.enabled)
        assertEquals(0L, window.acquire(500))
    }

    @Test
    fun withADrainModelTheQueueEstimateBlocksSendsUntilItDrains() {
        var clock = 0L
        val slept = mutableListOf<Long>()
        // 8000 B window, link drains 10 kB/s.
        val window = GattByteWindow(8_000, { 10_000L }, { clock }, { ns -> slept += ns; clock += ns })
        repeat(16) { assertEquals(0L, window.acquire(500)) } // 8000 B queued, exactly the window
        assertEquals(8_000, window.queuedEstimate())
        val waited = window.acquire(500)
        // 500 B over at 10 kB/s = 50 ms.
        assertEquals(50_000_000L, waited)
        assertEquals(listOf(50_000_000L), slept)
        assertEquals(8_000, window.peakBytes)
        assertEquals(1L, window.waits)
        // Time passes: the estimate drains and the next send is free again.
        clock += 400_000_000L
        assertEquals(4_000, window.queuedEstimate())
        assertEquals(0L, window.acquire(500))
    }

    @Test
    fun withoutADrainModelOnlyTheStacksCallbackFreesBytes() {
        var clock = 0L
        val window = GattByteWindow(1_000, { 0L }, { clock }, { ns -> clock += ns })
        assertEquals(0L, window.acquire(600))
        window.onSent(600)
        assertEquals(0, window.queuedEstimate())
        assertEquals(0L, window.acquire(600))
        assertEquals(600, window.peakBytes)
        // Never deadlocks on a packet larger than the window.
        window.onSent(600)
        assertEquals(0L, window.acquire(5_000))
        // A wrong model that never drains is given up after 2 s instead of holding the link.
        val waited = window.acquire(100)
        assertTrue(waited >= 2_000_000_000L)
        assertEquals(0L, window.acquire(900))
    }

    @Test
    fun theWindowComesFromTheSwitchesOnly() {
        assertNull(GattByteWindow.fromSwitches())
        GattSwitches.applyExtras(
            { _, d -> d },
            { key, d -> when (key) { "test_gatt_byte_window" -> 16_384; "test_gatt_window_kbps" -> 240; else -> d } },
            { null },
        )
        if (!BuildConfig.DEBUG) {
            // A release build ignores the launch extras: no byte window.
            assertNull(GattByteWindow.fromSwitches())
            return
        }
        val window = GattByteWindow.fromSwitches()!!
        assertTrue(window.line("gatt-video"), "maxBytes=16384 | drainKbps=240" in window.line("gatt-video"))
    }

    // ---- GattLinkLifetime --------------------------------------------------------------------------------------

    private class FakeWatch(private val dropsAfterMs: Long?, private val onWait: (Long) -> Unit = {}) : AclDownWatch {
        var released = 0
        override fun await(timeoutMs: Long): Boolean {
            onWait(timeoutMs)
            return dropsAfterMs != null && dropsAfterMs <= timeoutMs
        }
        override fun release() { released++ }
    }

    @Test
    fun theAclWaitIsBoundedAndAlwaysReleasesTheWatch() {
        val logs = mutableListOf<String>()
        val dropping = FakeWatch(dropsAfterMs = 300L)
        assertTrue(GattLinkLifetime.awaitAclDown(dropping, nowMs = { 0L }, log = { logs += it }, reason = "t"))
        assertEquals(1, dropping.released)
        val stuck = FakeWatch(dropsAfterMs = null)
        assertFalse(GattLinkLifetime.awaitAclDown(stuck, timeoutMs = 2_000L, nowMs = { 0L }, log = { logs += it }))
        assertEquals(1, stuck.released)
        assertTrue(logs.last(), "dropped=false" in logs.last() && "boundMs=2000" in logs.last())
        val throwing = object : AclDownWatch {
            var released = false
            override fun await(timeoutMs: Long): Boolean = throw IllegalStateException("receiver gone")
            override fun release() { released = true }
        }
        assertFalse(GattLinkLifetime.awaitAclDown(throwing, nowMs = { 0L }, log = {}))
        assertTrue(throwing.released)
    }

    @Test
    fun anAclThatIsAlreadyDownIsNotWaitedFor() {
        val watch = FakeWatch(dropsAfterMs = null, onWait = { error("must not wait") })
        assertTrue(GattLinkLifetime.awaitAclDown(watch, stillUp = { false }, nowMs = { 0L }, log = {}))
        assertEquals(1, watch.released)
    }

    @Test
    fun theMainThreadNeverBlocks() {
        val watch = FakeWatch(dropsAfterMs = 10L, onWait = { error("must not wait") })
        assertFalse(GattLinkLifetime.awaitAclDown(watch, mayBlock = false, nowMs = { 0L }, log = {}))
        assertEquals(1, watch.released)
    }

    @Test
    fun aWedgedClientAlwaysWaitsOnceAndARecentCloseOnlyWithTheExperimentalSwitch() {
        assertNull(GattLinkLifetime.redialWaitReason("AA", aclWaitOnRedial = false, nowMs = 1_000L))
        GattLinkLifetime.requireAclDrop("AA", "wedged-client")
        assertTrue(GattLinkLifetime.aclDropRequired("AA"))
        assertEquals("wedged-client", GattLinkLifetime.redialWaitReason("AA", aclWaitOnRedial = false, nowMs = 1_000L))
        assertNull(GattLinkLifetime.redialWaitReason("AA", aclWaitOnRedial = false, nowMs = 1_000L)) // consumed
        GattLinkLifetime.noteClosed("BB", nowMs = 10_000L)
        assertNull(GattLinkLifetime.redialWaitReason("BB", aclWaitOnRedial = false, nowMs = 10_500L))
        assertEquals("recent-close", GattLinkLifetime.redialWaitReason("BB", aclWaitOnRedial = true, nowMs = 10_500L))
        assertNull(GattLinkLifetime.redialWaitReason("BB", aclWaitOnRedial = true, nowMs = 14_000L))
        assertNull(GattLinkLifetime.redialWaitReason("CC", aclWaitOnRedial = true, nowMs = 10_500L))
        assertNull(GattLinkLifetime.redialWaitReason(null, aclWaitOnRedial = true, nowMs = 0L))
    }

    // ---- BluetoothAudioCoexistence -----------------------------------------------------------------------------

    @Test
    fun outputTypesAreClassifiedIntoRadioUsers() {
        val a2dp = android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        val sco = android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        assertEquals(BluetoothAudioKind.A2DP, BluetoothAudioCoexistence.classifyOutputType(a2dp))
        assertEquals(BluetoothAudioKind.SCO, BluetoothAudioCoexistence.classifyOutputType(sco))
        assertEquals(BluetoothAudioKind.LE_AUDIO, BluetoothAudioCoexistence.classifyOutputType(26))
        assertEquals(BluetoothAudioKind.LE_AUDIO, BluetoothAudioCoexistence.classifyOutputType(27))
        assertEquals(BluetoothAudioKind.LE_AUDIO, BluetoothAudioCoexistence.classifyOutputType(30))
        assertEquals(BluetoothAudioKind.HEARING_AID, BluetoothAudioCoexistence.classifyOutputType(23))
        assertNull(BluetoothAudioCoexistence.classifyOutputType(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertNull(BluetoothAudioCoexistence.classifyOutputType(android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
    }

    @Test
    fun theStateDescribesWhatIsActiveAndAdviceOnlyAppearsWhenItIs() {
        val speaker = BluetoothAudioCoexistence.stateFor(listOf(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), scoOn = false)
        assertFalse(speaker.active)
        assertEquals("none", speaker.describe())
        assertNull(BluetoothAudioCoexistence.advice(speaker))
        val earbuds = BluetoothAudioCoexistence.stateFor(listOf(8, 7), scoOn = true)
        assertTrue(earbuds.active)
        assertEquals("a2dp+sco+sco_on", earbuds.describe())
        assertTrue(BluetoothAudioCoexistence.advice(earbuds)!!.contains("share the radio"))
        assertEquals("btAudio=a2dp+sco+sco_on", BluetoothAudioCoexistence.benchmarkField(earbuds))
    }

    @Test
    fun capacityKeyAndLoadCutAreExperimentalAndOffByDefault() {
        val earbuds = BluetoothAudioCoexistence.stateFor(listOf(8), scoOn = false)
        assertEquals("", BluetoothAudioCoexistence.capacityKeySuffix(earbuds))
        assertEquals(1.0, BluetoothAudioCoexistence.loadFactor(earbuds), 0.0)
        GattSwitches.applyExtras({ key, d -> if (key == "test_bt_audio_capacity_key" || key == "test_bt_audio_load_cut") true else d }, { _, d -> d }, { null })
        if (!BuildConfig.DEBUG) {
            // A release build ignores the launch extras: both experiments stay off.
            assertEquals("", BluetoothAudioCoexistence.capacityKeySuffix(earbuds))
            assertEquals(1.0, BluetoothAudioCoexistence.loadFactor(earbuds), 0.0)
            return
        }
        assertEquals(".btaudio", BluetoothAudioCoexistence.capacityKeySuffix(earbuds))
        assertEquals(BluetoothAudioCoexistence.LOAD_CUT_FACTOR, BluetoothAudioCoexistence.loadFactor(earbuds), 0.0)
        val none = BluetoothAudioCoexistence.stateFor(emptyList(), scoOn = false)
        assertEquals("", BluetoothAudioCoexistence.capacityKeySuffix(none))
        assertEquals(1.0, BluetoothAudioCoexistence.loadFactor(none), 0.0)
    }

    // ---- GattSwitches ------------------------------------------------------------------------------------------

    @Test
    fun defaultsAreTheShippedBehaviour() {
        GattSwitches.resetToDefaults()
        assertFalse("root review: wedge detection is the Experimental arm, legacy is the default", GattSwitches.busyWedgeRecovery)
        assertTrue(GattSwitches.closeDeferral)
        assertFalse(GattSwitches.linkBudgetAdmission)
        assertFalse(GattSwitches.serverHold)
        assertEquals(0, GattSwitches.settleMs)
        assertFalse(GattSwitches.connectOnMain)
        assertSame(GattSwitches.Payload.DEFAULT, GattSwitches.payload)
        assertFalse(GattSwitches.btAudioLoadCut)
        assertFalse(GattSwitches.btAudioCapacityKey)
        assertFalse(GattSwitches.gattRefresh)
        assertFalse(GattSwitches.aclWaitOnRedial)
        assertFalse(GattSwitches.remoteFullFailFast)
        assertEquals(0, GattSwitches.byteWindowBytes)
        assertEquals("none", GattSwitches.describeNonDefaults())
    }

    @Test
    fun everyArmIsReachableByItsLaunchExtra() {
        GattSwitches.applyExtras(
            boolExtra = { key, default ->
                when (key) {
                    "test_gatt_close_deferral" -> false
                    "test_link_budget_admission", "test_gatt_server_hold", "test_connect_gatt_main", "test_bt_audio_load_cut",
                    "test_bt_audio_capacity_key", "test_gatt_refresh", "test_gatt_acl_wait", "test_gatt_remote_full_failfast" -> true
                    else -> default
                }
            },
            intExtra = { key, default -> when (key) { "test_gatt_settle_ms" -> 5_000; "test_gatt_byte_window" -> 8_192; else -> default } },
            stringExtra = { key -> when (key) { "test_gatt_busy_wedge" -> "recover"; "test_gatt_payload" -> "244"; else -> null } },
        )
        if (!BuildConfig.DEBUG) {
            // A release build ignores every launch extra: each arm keeps its shipped default.
            assertEquals("none", GattSwitches.describeNonDefaults())
            assertTrue(GattSwitches.closeDeferral)
            return
        }
        assertTrue(GattSwitches.busyWedgeRecovery)
        assertFalse(GattSwitches.closeDeferral)
        assertTrue(GattSwitches.linkBudgetAdmission && GattSwitches.serverHold && GattSwitches.connectOnMain)
        assertTrue(GattSwitches.btAudioLoadCut && GattSwitches.btAudioCapacityKey && GattSwitches.gattRefresh)
        assertTrue(GattSwitches.aclWaitOnRedial && GattSwitches.remoteFullFailFast)
        assertEquals(3_000, GattSwitches.settleMs) // clamped
        assertEquals(8_192, GattSwitches.byteWindowBytes)
        assertSame(GattSwitches.Payload.ONE_PDU, GattSwitches.payload)
        val description = GattSwitches.describeNonDefaults()
        assertTrue(description, "busy_wedge=recover" in description && "payload=244" in description && "settle_ms=3000" in description)
    }
}
