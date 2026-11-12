package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectQuarantineTest {
    private companion object {
        const val SSID = "DIRECT-xy-abcdef"
        val PSK = "a".repeat(43)
    }

    private class MemoryStorage(var text: String? = null) : DirectGroupRecord.Storage {
        override fun read(): String? = text
        override fun write(text: String) {
            this.text = text
        }

        override fun delete() {
            text = null
        }
    }

    /**
     * Connection info follows the group (groupFormed); [groupReadable] false models the API 29-32 location gate; [holdReads]
     * keeps group-read callbacks until [answerHeld] (a callback that arrives late, or never).
     */
    private class Port : P2pPort {
        var openResult = true
        var openCalls = 0
        var closeCalls = 0
        var group: P2pGroup? = null
        var groupReadable = true
        var holdReads = false
        val heldReads = ArrayList<(P2pGroup?) -> Unit>()
        var lost: (() -> Unit)? = null
        var removeResult: P2pResult = P2pResult.Success
        var removeAnswer = true
        var removeCalls = 0
        var addresses: List<DirectAddressRules.LocalAddress> = emptyList()

        override fun open(onChannelLost: () -> Unit): Boolean {
            openCalls++
            lost = onChannelLost
            return openResult
        }

        override fun createGroup(ssid: String, psk: String, frequencyMhz: Int, done: (P2pResult) -> Unit) = done(P2pResult.Success)

        override fun connect(ssid: String, psk: String, frequencyMhz: Int, done: (P2pResult) -> Unit) = done(P2pResult.Success)

        override fun requestGroup(done: (P2pGroup?) -> Unit) {
            if (holdReads) heldReads += done else done(if (groupReadable) group else null)
        }

        fun answerHeld() {
            val held = heldReads.toList()
            heldReads.clear()
            held.forEach { it(if (groupReadable) group else null) }
        }

        override fun requestConnection(done: (P2pConnection?) -> Unit) = done(P2pConnection(group != null, group?.isOwner == true, null))

        override fun removeGroup(done: (P2pResult) -> Unit) {
            removeCalls++
            if (!removeAnswer) return
            if (removeResult == P2pResult.Success) group = null
            done(removeResult)
        }

        override fun requestDeviceName(done: (String?) -> Unit) = done(null)

        override fun closeChannel() {
            closeCalls++
        }

        override fun localAddresses(): List<DirectAddressRules.LocalAddress> = addresses
    }

    private class Rig {
        val scheduler = ManualScheduler()
        val adapter = DirectSchedulerAdapter(scheduler)
        val port = Port()
        val storage = MemoryStorage()
        val record = DirectGroupRecord(storage)
        val ledger = WifiLedger { scheduler.nowMs }
        val notices = ArrayList<Boolean>()
        val quarantine = DirectQuarantine({ port }, record, ledger, adapter, { notices += it }, {})
        val resumes = ArrayList<String>()
        private lateinit var gate: DiscoveryPauseGate

        init {
            val gate = DiscoveryPauseGate(ManualScheduler()) {}
            gate.register(
                object : DiscoveryPauser {
                    override fun pauseDiscovery(owner: String) = Unit
                    override fun resumeDiscovery(owner: String) {
                        resumes += owner
                    }
                },
            )
            this.gate = gate
        }

        fun lease(owner: String = "direct"): DiscoveryPauseLease = gate.pause(owner)
    }

    private fun p2pGroup(name: String = SSID) = P2pGroup(name, true, "p2p-wlan0-0", 5180)

    @Test
    fun failureThenSuccessResumesDiscoveryOnlyAfterConfirmation() {
        val rig = Rig()
        assertEquals(DirectReserve.OK, rig.ledger.reserveDirect("direct"))
        rig.record.add(SSID)
        rig.port.group = p2pGroup()
        val lease = rig.lease()
        rig.quarantine.enter(SSID, "direct")
        rig.quarantine.holdLease(lease)

        rig.port.removeResult = P2pResult.Failure(0)
        rig.scheduler.advance(DirectQuarantine.RETRY_MS)
        assertTrue(rig.quarantine.active)
        assertTrue(rig.ledger.directGroupActive)
        assertTrue(rig.resumes.isEmpty())

        rig.port.removeResult = P2pResult.Success
        rig.scheduler.advance(DirectQuarantine.RETRY_MS)
        assertFalse(rig.quarantine.active)
        assertEquals(1, rig.resumes.size)
        assertEquals(DirectReserve.OK, rig.ledger.reserveDirect("direct"))
        assertTrue(rig.record.names().isEmpty())
        assertEquals(listOf(false), rig.notices)
    }

    @Test
    fun aMissingCallbackQuarantinesAfterFiveSeconds() {
        val rig = Rig()
        val events = object : DirectGroup.Events {
            override fun onHostReady(ready: DirectGroup.HostReady) = Unit
            override fun onJoined(joined: DirectGroup.Joined) = Unit
            override fun onFailed(failure: DirectFailure) = Unit
            override fun onLost() = Unit
        }
        val group = DirectGroup(rig.port, rig.record, rig.ledger, rig.adapter, { rig.scheduler.nowMs }, events, rig.quarantine)
        rig.port.addresses = listOf(
            DirectAddressRules.LocalAddress(
                "p2p-wlan0-0",
                java.net.InetAddress.getByName("192.168.49.1") as java.net.Inet4Address,
                24,
            ),
        )
        group.host(SSID, PSK, 5180)
        rig.scheduler.drain()
        rig.port.group = p2pGroup()
        rig.scheduler.advance(DirectLimits.FIRST_POLL_MS)
        assertTrue(rig.ledger.directGroupActive)

        rig.port.removeAnswer = false
        group.close()
        rig.scheduler.drain()
        assertFalse("the removal has not answered", rig.quarantine.active)
        rig.scheduler.advance(DirectLimits.CLEANUP_WATCHDOG_MS)
        assertTrue(rig.quarantine.active)
        assertTrue(rig.ledger.directGroupActive)

        rig.port.group = null
        rig.scheduler.advance(DirectQuarantine.RETRY_MS)
        assertFalse(rig.quarantine.active)
        assertFalse(rig.ledger.directGroupActive)
    }

    @Test
    fun sixFailuresShowTheNoticeThenASlowCheckLiftsIt() {
        val rig = Rig()
        assertEquals(DirectReserve.OK, rig.ledger.reserveDirect("direct"))
        rig.record.add(SSID)
        rig.port.group = p2pGroup()
        rig.port.removeResult = P2pResult.Failure(0)
        val lease = rig.lease()
        rig.quarantine.enter(SSID, "direct")
        rig.quarantine.holdLease(lease)

        repeat(6) { rig.scheduler.advance(DirectQuarantine.RETRY_MS) }
        assertTrue(rig.quarantine.active)
        assertTrue(rig.notices.contains(true))

        rig.port.group = null
        rig.scheduler.advance(DirectQuarantine.SLOW_CHECK_MS)
        assertFalse(rig.quarantine.active)
        assertEquals(1, rig.resumes.size)
        assertEquals(listOf(false), rig.notices.filterNot { it })
    }

    @Test
    fun anotherAppsGroupIsNeverRemovedAndConfirmsOurAbsence() {
        val rig = Rig()
        assertEquals(DirectReserve.OK, rig.ledger.reserveDirect("direct"))
        rig.record.add(SSID)
        rig.port.group = p2pGroup("DIRECT-zz-999999")
        val lease = rig.lease()
        rig.quarantine.enter(SSID, "direct")
        rig.quarantine.holdLease(lease)

        rig.scheduler.advance(DirectQuarantine.RETRY_MS)
        assertEquals(0, rig.port.removeCalls)
        assertFalse(rig.quarantine.active)
        assertEquals(1, rig.resumes.size)
        assertTrue(rig.record.names().isEmpty())
        assertEquals(DirectReserve.OK, rig.ledger.reserveDirect("direct"))
    }

    @Test
    fun aRecordedNameAtStartUpIsQuarantinedUntilConfirmed() {
        val rig = Rig()
        assertEquals(DirectReserve.OK, rig.ledger.reserveDirect("direct-sweep"))
        rig.record.add(SSID)
        rig.port.group = p2pGroup()
        rig.port.removeResult = P2pResult.Failure(0)
        rig.quarantine.enter(SSID, "direct-sweep")
        rig.scheduler.advance(DirectQuarantine.RETRY_MS)
        assertTrue(rig.quarantine.active)

        rig.port.group = null
        rig.scheduler.advance(DirectQuarantine.RETRY_MS)
        assertFalse(rig.quarantine.active)
        assertFalse(rig.ledger.directGroupActive)
    }

    @Test
    fun aShutdownKeepsTheQuarantineClaim() {
        val rig = Rig()
        assertEquals(DirectReserve.OK, rig.ledger.reserveDirect("direct"))
        rig.quarantine.enter(SSID, "direct")
        rig.ledger.releaseAllClaims()
        assertEquals(DirectReserve.BUSY_OWN, rig.ledger.reserveDirect("direct"))
    }

    // ----- A9 review P2-2: each retry is bounded -----

    @Test
    fun aRetryWhoseCallbackNeverComesClosesItsChannelAndRetriesAgain() {
        val rig = Rig()
        assertEquals(DirectReserve.OK, rig.ledger.reserveDirect("direct"))
        rig.record.add(SSID)
        rig.port.group = p2pGroup()
        rig.port.holdReads = true
        val lease = rig.lease()
        rig.quarantine.enter(SSID, "direct")
        rig.quarantine.holdLease(lease)

        rig.scheduler.advance(DirectQuarantine.RETRY_MS)
        assertEquals(1, rig.port.openCalls)
        assertEquals(0, rig.port.closeCalls)
        rig.scheduler.advance(DirectQuarantine.RETRY_TIMEOUT_MS)
        assertEquals("the timed-out retry closes its channel", 1, rig.port.closeCalls)
        assertTrue(rig.quarantine.active)

        // The late answer of the expired retry changes nothing.
        rig.port.group = null
        rig.port.answerHeld()
        assertTrue(rig.quarantine.active)
        assertEquals(1, rig.port.closeCalls)

        // The next retry is scheduled and confirms.
        rig.port.holdReads = false
        rig.scheduler.advance(DirectQuarantine.RETRY_MS)
        assertEquals(2, rig.port.openCalls)
        assertFalse(rig.quarantine.active)
        assertEquals(1, rig.resumes.size)
        assertEquals(DirectReserve.OK, rig.ledger.reserveDirect("direct"))
    }

    @Test
    fun aLostChannelEndsTheRetryAndTheNextOneConfirms() {
        val rig = Rig()
        assertEquals(DirectReserve.OK, rig.ledger.reserveDirect("direct"))
        rig.record.add(SSID)
        rig.port.group = p2pGroup()
        rig.port.holdReads = true
        rig.quarantine.enter(SSID, "direct")
        rig.scheduler.advance(DirectQuarantine.RETRY_MS)
        rig.port.lost?.invoke()
        assertEquals(1, rig.port.closeCalls)
        assertTrue(rig.quarantine.active)

        rig.port.holdReads = false
        rig.port.group = null
        rig.scheduler.advance(DirectQuarantine.RETRY_MS)
        assertFalse(rig.quarantine.active)
    }

    // ----- A9 review P1-2: an unreadable group is "unknown" -----

    @Test
    fun anUnreadableGroupIsRemovedOnlyByAQuarantineThatOwnsIt() {
        val owned = Rig()
        owned.ledger.reserveDirect("direct")
        owned.record.add(SSID)
        owned.port.group = p2pGroup()
        owned.port.groupReadable = false
        owned.quarantine.enter(SSID, "direct", owned = true)
        owned.scheduler.advance(DirectQuarantine.RETRY_MS)
        assertEquals("this process created it and the record names it", 1, owned.port.removeCalls)
        assertFalse(owned.quarantine.active)
        assertTrue(owned.record.names().isEmpty())

        val unowned = Rig()
        unowned.ledger.reserveDirect("direct-sweep")
        unowned.record.add(SSID)
        unowned.port.group = p2pGroup()
        unowned.port.groupReadable = false
        unowned.quarantine.enter(null, "direct-sweep", owned = false)
        repeat(3) { unowned.scheduler.advance(DirectQuarantine.RETRY_MS) }
        assertEquals("never a group we cannot name", 0, unowned.port.removeCalls)
        assertTrue(unowned.quarantine.active)
        assertEquals(listOf(SSID), unowned.record.names())
    }

    // ----- A9 review P1-3: the record may throw -----

    @Test
    fun aRecordThatThrowsNeverStopsTheConfirmation() {
        val storage = object : DirectGroupRecord.Storage {
            override fun read(): String? {
                throw SealedTextUnavailable(IllegalStateException("key deleted"))
            }

            override fun write(text: String) {
                throw IllegalStateException("Emergency wipe")
            }

            override fun delete() {}
        }
        val scheduler = ManualScheduler()
        val port = Port().also { it.group = p2pGroup() }
        val ledger = WifiLedger { scheduler.nowMs }
        val errors = ArrayList<String>()
        val record = DirectGroupRecord(storage, onError = { errors += it })
        val quarantine = DirectQuarantine({ port }, record, ledger, DirectSchedulerAdapter(scheduler), {}, {})
        ledger.reserveDirect("direct")
        quarantine.enter(SSID, "direct")
        scheduler.advance(DirectQuarantine.RETRY_MS)
        assertEquals(1, port.removeCalls)
        assertFalse(quarantine.active)
        assertEquals(DirectReserve.OK, ledger.reserveDirect("direct"))
        assertTrue("the failures were reported, not thrown", errors.isNotEmpty())
    }
}
