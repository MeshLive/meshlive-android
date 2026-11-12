package com.example.bluetoothtest

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectGroupTest {
    private companion object {
        const val SSID = "DIRECT-xy-abcdef"
        val PSK = "a".repeat(43)
        const val P2P = "p2p-wlan0-0"
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
     * A fake WifiP2pManager. [scheduler] set and [async] true: every answer is posted (the looper's order, not inline).
     * The connection info follows the group unless set ([connection]); [groupReadable] false models the API 29-32
     * location gate (group info null while groupFormed is true); [removeLagMs] keeps a removed group reported for a while.
     */
    private class Port(private val scheduler: ManualScheduler? = null, var async: Boolean = false) : P2pPort {
        var groupReadable = true
        var connectionReadable = true
        var groupAnswer = true
        var removeLagMs = 0L
        var openResult = true
        var openCalls = 0
        var closeCalls = 0
        val creates = ArrayList<Pair<String, Int>>()
        val connects = ArrayList<Pair<String, Int>>()
        var createDone: ((P2pResult) -> Unit)? = null
        var connectDone: ((P2pResult) -> Unit)? = null
        var group: P2pGroup? = null
        var connection: P2pConnection? = null
        val groups = ArrayDeque<P2pGroup?>()
        var addresses: List<DirectAddressRules.LocalAddress> = emptyList()
        var removeResult: P2pResult = P2pResult.Success
        var removeAnswer = true
        var removeCalls = 0
        var requestGroupCalls = 0
        var requestConnectionCalls = 0
        var deviceName: String? = "Phone"
        var lost: (() -> Unit)? = null
        var removeDone: ((P2pResult) -> Unit)? = null

        fun completeCreate(result: P2pResult) {
            val done = createDone
            createDone = null
            done?.invoke(result)
        }

        fun completeConnect(result: P2pResult) {
            val done = connectDone
            connectDone = null
            done?.invoke(result)
        }

        fun completeRemove(result: P2pResult = P2pResult.Success) {
            val done = removeDone
            removeDone = null
            done?.invoke(result)
        }

        override fun open(onChannelLost: () -> Unit): Boolean {
            openCalls++
            lost = onChannelLost
            return openResult
        }

        override fun createGroup(ssid: String, psk: String, frequencyMhz: Int, done: (P2pResult) -> Unit) {
            creates += ssid to frequencyMhz
            createDone = done
        }

        override fun connect(ssid: String, psk: String, frequencyMhz: Int, done: (P2pResult) -> Unit) {
            connects += ssid to frequencyMhz
            connectDone = done
        }

        private fun answer(block: () -> Unit) {
            val posted = scheduler
            if (async && posted != null) posted.post(block) else block()
        }

        override fun requestGroup(done: (P2pGroup?) -> Unit) {
            requestGroupCalls++
            if (!groupAnswer) return
            answer {
                val value = if (groups.isEmpty()) group else groups.removeFirst()
                done(if (groupReadable) value else null)
            }
        }

        override fun requestConnection(done: (P2pConnection?) -> Unit) {
            requestConnectionCalls++
            answer {
                done(if (connectionReadable) connection ?: P2pConnection(group != null, group?.isOwner == true, null) else null)
            }
        }

        override fun removeGroup(done: (P2pResult) -> Unit) {
            removeCalls++
            if (removeAnswer) {
                val result = removeResult
                answer {
                    if (result == P2pResult.Success) {
                        val lag = removeLagMs
                        val clock = scheduler
                        if (lag > 0 && clock != null) clock.postDelayed(Any(), lag) { gone() } else gone()
                    }
                    done(result)
                }
            } else {
                removeDone = done
            }
        }

        fun gone() {
            group = null
            connection = null
        }

        override fun requestDeviceName(done: (String?) -> Unit) = done(deviceName)

        override fun closeChannel() {
            closeCalls++
        }

        override fun localAddresses(): List<DirectAddressRules.LocalAddress> = addresses
    }

    private class Events : DirectGroup.Events {
        val ready = ArrayList<DirectGroup.HostReady>()
        val joined = ArrayList<DirectGroup.Joined>()
        val failed = ArrayList<DirectFailure>()
        var lost = 0

        override fun onHostReady(ready: DirectGroup.HostReady) {
            this.ready += ready
        }

        override fun onJoined(joined: DirectGroup.Joined) {
            this.joined += joined
        }

        override fun onFailed(failure: DirectFailure) {
            failed += failure
        }

        override fun onLost() {
            lost++
        }
    }

    /** The sealed file during an emergency wipe: the Keystore key is gone, so every read and write throws. */
    private class WipeableStorage : DirectGroupRecord.Storage {
        var text: String? = null
        var wiped = false
        override fun read(): String? {
            if (wiped) throw SealedTextUnavailable(IllegalStateException("Emergency wipe: key deleted"))
            return text
        }

        override fun write(text: String) {
            if (wiped) throw IllegalStateException("Emergency wipe: sealed memory refused")
            this.text = text
        }

        override fun delete() {
            text = null
        }
    }

    private class Rig(allow24: Boolean = false, async: Boolean = false, val storage: DirectGroupRecord.Storage = MemoryStorage()) {
        val scheduler = ManualScheduler()
        val adapter = DirectSchedulerAdapter(scheduler)
        val port = Port(scheduler, async)
        val quarantinePort = Port(scheduler, async)
        val record = DirectGroupRecord(storage)
        val ledger = WifiLedger { scheduler.nowMs }
        val events = Events()
        val notices = ArrayList<Boolean>()
        val quarantine = DirectQuarantine({ quarantinePort }, record, ledger, adapter, { notices += it }, {})
        val group = DirectGroup(port, record, ledger, adapter, { scheduler.nowMs }, events, quarantine, allow24)
    }

    private fun addr(text: String): Inet4Address = InetAddress.getByName(text) as Inet4Address

    private fun local(name: String, text: String, prefix: Int = 24) =
        DirectAddressRules.LocalAddress(name, addr(text), prefix)

    private fun p2pGroup(
        name: String = SSID,
        owner: Boolean = true,
        iface: String? = P2P,
        freq: Int = 5180,
    ) = P2pGroup(name, owner, iface, freq)

    private fun readyHost(rig: Rig, hostFrequency: Int = 5180, groupFrequency: Int = 5180): Rig {
        rig.port.addresses = listOf(local(P2P, "192.168.49.1"))
        rig.group.host(SSID, PSK, hostFrequency)
        rig.scheduler.drain()
        rig.port.completeCreate(P2pResult.Success)
        rig.port.group = p2pGroup(freq = groupFrequency)
        rig.scheduler.advance(DirectLimits.FIRST_POLL_MS)
        return rig
    }

    private fun joinedGuest(rig: Rig, hostFrequency: Int = 5180, groupFrequency: Int = 5180): Rig {
        rig.port.addresses = listOf(local("wlan0", "10.0.0.8"))
        rig.group.join(SSID, PSK, hostFrequency)
        rig.scheduler.drain()
        rig.port.completeConnect(P2pResult.Success)
        rig.scheduler.advance(DirectLimits.POLL_MS)
        rig.port.addresses = listOf(local("wlan0", "10.0.0.8"), local(P2P, "192.168.49.2"))
        rig.port.group = p2pGroup(owner = false, freq = groupFrequency)
        rig.port.connection = P2pConnection(true, false, addr("192.168.49.1"))
        rig.scheduler.advance(DirectLimits.POLL_MS)
        return rig
    }

    @Test
    fun hostHappyPathRecordsFirstThenReportsTheRealFrequencyAndReleasesOnClose() {
        val rig = Rig()
        rig.port.addresses = listOf(local(P2P, "192.168.49.1"))
        rig.group.host(SSID, PSK, 5180)
        rig.scheduler.drain()
        assertTrue("the name is recorded before createGroup", rig.record.contains(SSID))
        assertEquals(5180, rig.port.creates.single().second)
        rig.port.completeCreate(P2pResult.Success)
        rig.port.group = p2pGroup(freq = 5180)
        rig.scheduler.advance(DirectLimits.FIRST_POLL_MS)
        assertEquals(1, rig.events.ready.size)
        assertEquals(5180, rig.events.ready.single().frequencyMhz)
        assertEquals("192.168.49.1", rig.events.ready.single().local.address.hostAddress)
        assertTrue(rig.ledger.directGroupActive)

        rig.group.close()
        rig.scheduler.drain()
        assertEquals(1, rig.port.removeCalls)
        assertEquals(1, rig.port.closeCalls)
        assertFalse(rig.ledger.directGroupActive)
        assertFalse(rig.record.contains(SSID))
    }

    @Test
    fun createGroupBusyRetriesOneThenTwoSecondsAndThenFailsBusy() {
        val rig = Rig()
        rig.group.host(SSID, PSK, 0)
        rig.scheduler.drain()
        rig.port.completeCreate(P2pResult.Failure(2))
        rig.scheduler.advance(1_000)
        assertEquals(2, rig.port.creates.size)
        rig.port.completeCreate(P2pResult.Failure(2))
        rig.scheduler.advance(2_000)
        assertEquals(3, rig.port.creates.size)
        rig.port.completeCreate(P2pResult.Failure(2))
        rig.scheduler.drain()
        assertEquals(listOf(DirectFailure.BUSY), rig.events.failed)
        assertTrue(rig.port.closeCalls >= 1)
        assertFalse(rig.ledger.directGroupActive)
    }

    @Test
    fun createGroupErrorIsOneLocalFaultAndPlatformError() {
        val rig = Rig()
        rig.group.host(SSID, PSK, 0)
        rig.scheduler.drain()
        rig.port.completeCreate(P2pResult.Failure(0))
        rig.scheduler.drain()
        assertEquals(listOf(DirectFailure.PLATFORM_ERROR), rig.events.failed)
        assertTrue(rig.port.closeCalls >= 1)
    }

    @Test
    fun threePlatformErrorsPauseThePhone() {
        val scheduler = ManualScheduler()
        val ledger = WifiLedger { scheduler.nowMs }
        repeat(3) {
            val port = Port()
            val record = DirectGroupRecord(MemoryStorage())
            val quarantine = DirectQuarantine({ Port() }, record, ledger, DirectSchedulerAdapter(scheduler), {}, {})
            val group = DirectGroup(port, record, ledger, DirectSchedulerAdapter(scheduler), { scheduler.nowMs }, Events(), quarantine)
            group.host(SSID, PSK, 0)
            scheduler.drain()
            port.completeCreate(P2pResult.Failure(0))
            scheduler.drain()
        }
        assertTrue("three local faults start the pause", ledger.pauseUntilMs > 0)
    }

    @Test
    fun a24GhzGroupIsRemovedUnlessAllow24Ghz() {
        val refused = readyHost(Rig(), groupFrequency = 2437)
        assertEquals(listOf(DirectFailure.BAND_24), refused.events.failed)
        assertEquals(1, refused.port.removeCalls)
        assertFalse(refused.record.contains(SSID))

        val allowed = Rig(allow24 = true)
        readyHost(allowed, groupFrequency = 2437)
        assertEquals(1, allowed.events.ready.size)
        assertEquals(2437, allowed.events.ready.single().frequencyMhz)
    }

    @Test
    fun anUnreadableHostFrequencyFailsUnlessAllow24Ghz() {
        val refused = readyHost(Rig(), groupFrequency = 0)
        assertEquals(listOf(DirectFailure.BAND_24), refused.events.failed)

        val allowed = Rig(allow24 = true)
        readyHost(allowed, groupFrequency = 0)
        assertEquals(1, allowed.events.ready.size)
    }

    @Test
    fun aRefusedFrequencyRetriesOnTheBandWithoutAFault() {
        val rig = Rig()
        rig.group.host(SSID, PSK, 5180)
        rig.scheduler.drain()
        rig.port.completeCreate(P2pResult.Failure(0))
        rig.scheduler.drain()
        assertEquals(2, rig.port.creates.size)
        assertEquals(0, rig.port.creates[1].second)
        rig.port.addresses = listOf(local(P2P, "192.168.49.1"))
        rig.port.completeCreate(P2pResult.Success)
        rig.port.group = p2pGroup(freq = 5180)
        rig.scheduler.advance(DirectLimits.FIRST_POLL_MS)
        assertEquals(1, rig.events.ready.size)
        assertTrue(rig.events.failed.isEmpty())
    }

    @Test
    fun bothRefusedCountOneFault() {
        val rig = Rig()
        rig.group.host(SSID, PSK, 5180)
        rig.scheduler.drain()
        rig.port.completeCreate(P2pResult.Failure(0))
        rig.scheduler.drain()
        rig.port.completeCreate(P2pResult.Failure(0))
        rig.scheduler.drain()
        assertEquals(listOf(DirectFailure.PLATFORM_ERROR), rig.events.failed)
    }

    @Test
    fun sweepRemovesOurRecordedGroupAndThenProceeds() {
        val rig = Rig()
        rig.record.add(SSID)
        rig.port.group = p2pGroup()
        rig.port.addresses = listOf(local(P2P, "192.168.49.1"))
        rig.group.host(SSID, PSK, 5180)
        rig.scheduler.drain()
        assertEquals(1, rig.port.removeCalls)
        assertEquals(1, rig.port.creates.size)
        assertTrue("the attempt records its own name again", rig.record.contains(SSID))
    }

    @Test
    fun aForeignGroupIsNeverRemovedAndFailsTheAttempt() {
        val rig = Rig()
        rig.port.group = p2pGroup(name = "DIRECT-zz-999999")
        rig.group.host(SSID, PSK, 5180)
        rig.scheduler.drain()
        assertEquals(0, rig.port.removeCalls)
        assertEquals(listOf(DirectFailure.FOREIGN_GROUP), rig.events.failed)
        assertTrue(rig.port.creates.isEmpty())
        assertTrue(rig.record.names().isEmpty())
    }

    @Test
    fun aSweepRemovalFailureKeepsTheClaimInTheQuarantineAndFailsBusy() {
        val rig = Rig()
        rig.record.add(SSID)
        rig.port.group = p2pGroup()
        rig.port.removeResult = P2pResult.Failure(0)
        rig.group.host(SSID, PSK, 5180)
        rig.scheduler.drain()
        assertEquals(listOf(DirectFailure.BUSY), rig.events.failed)
        assertTrue(rig.quarantine.active)
        assertTrue(rig.ledger.directGroupActive)
    }

    @Test
    fun sweepOnStartupReturnsEveryResult() {
        fun startup(record: DirectGroupRecord, port: Port, ledger: WifiLedger = WifiLedger { 0 }): Pair<DirectGroup.SweepResult, DirectQuarantine> {
            val quarantine = DirectQuarantine({ Port() }, record, ledger, DirectSchedulerAdapter(ManualScheduler()), {}, {})
            lateinit var result: DirectGroup.SweepResult
            DirectGroup.sweepOnStartup(port, record, ledger, quarantine, DirectSchedulerAdapter(ManualScheduler())) { result = it }
            return result to quarantine
        }

        val empty = DirectGroupRecord(MemoryStorage())
        assertEquals(DirectGroup.SweepResult.SKIPPED, startup(empty, Port()).first)

        val clearRecord = DirectGroupRecord(MemoryStorage()).also { it.add(SSID) }
        assertEquals(DirectGroup.SweepResult.CLEAR, startup(clearRecord, Port()).first)

        val removedRecord = DirectGroupRecord(MemoryStorage()).also { it.add(SSID) }
        val removedPort = Port().also { it.group = p2pGroup() }
        assertEquals(DirectGroup.SweepResult.REMOVED, startup(removedRecord, removedPort).first)
        assertTrue(removedRecord.names().isEmpty())

        val foreignRecord = DirectGroupRecord(MemoryStorage()).also { it.add(SSID) }
        val foreignPort = Port().also { it.group = p2pGroup(name = "DIRECT-zz-999999") }
        assertEquals(DirectGroup.SweepResult.FOREIGN, startup(foreignRecord, foreignPort).first)
        assertTrue(foreignRecord.names().isEmpty())

        val failedRecord = DirectGroupRecord(MemoryStorage()).also { it.add(SSID) }
        val failedPort = Port().also { it.group = p2pGroup(); it.removeResult = P2pResult.Failure(0) }
        val (failedResult, quarantine) = startup(failedRecord, failedPort)
        assertEquals(DirectGroup.SweepResult.REMOVE_FAILED, failedResult)
        assertTrue(quarantine.active)

        val busy = WifiLedger { 0 }.also { it.foreignDirectGroup = true }
        val busyRecord = DirectGroupRecord(MemoryStorage()).also { it.add(SSID) }
        assertEquals(DirectGroup.SweepResult.SKIPPED, startup(busyRecord, Port(), busy).first)
    }

    @Test
    fun aRecordThatCannotBeWrittenNeverCreatesAGroup() {
        val storage = object : DirectGroupRecord.Storage {
            var text: String? = null
            override fun read(): String? = text
            override fun write(text: String) {
                throw IllegalStateException("wipe")
            }

            override fun delete() {
                text = null
            }
        }
        val rig = Rig()
        val record = DirectGroupRecord(storage)
        val group = DirectGroup(rig.port, record, rig.ledger, rig.adapter, { rig.scheduler.nowMs }, rig.events, rig.quarantine)
        group.host(SSID, PSK, 5180)
        rig.scheduler.drain()
        assertEquals(listOf(DirectFailure.RECORD_UNAVAILABLE), rig.events.failed)
        assertTrue(rig.port.creates.isEmpty())
    }

    @Test
    fun aGuestJoinsWhenANewP2pAddressAppears() {
        val rig = joinedGuest(Rig())
        assertEquals(1, rig.events.joined.size)
        assertEquals(P2P, rig.events.joined.single().local.interfaceName)
        assertEquals(5180, rig.events.joined.single().frequencyMhz)
    }

    @Test
    fun aGuestBecomingTheOwnerIsForeign() {
        val rig = Rig()
        rig.port.addresses = listOf(local("wlan0", "10.0.0.8"))
        rig.group.join(SSID, PSK, 5180)
        rig.scheduler.drain()
        rig.port.completeConnect(P2pResult.Success)
        rig.scheduler.advance(DirectLimits.POLL_MS)
        rig.port.connection = P2pConnection(true, true, addr("192.168.49.1"))
        rig.scheduler.advance(DirectLimits.POLL_MS)
        assertEquals(listOf(DirectFailure.FOREIGN_GROUP), rig.events.failed)
    }

    @Test
    fun aGuestWithoutAnAddressTimesOutWithoutAPhoneFault() {
        val rig = Rig()
        rig.port.addresses = listOf(local("wlan0", "10.0.0.8"))
        rig.group.join(SSID, PSK, 5180)
        rig.scheduler.drain()
        rig.port.completeConnect(P2pResult.Success)
        rig.scheduler.advance(DirectLimits.GROUP_READY_MS + DirectLimits.POLL_MS)
        assertEquals(listOf(DirectFailure.TIMEOUT), rig.events.failed)
        assertEquals(0L, rig.ledger.pauseUntilMs)
    }

    @Test
    fun aGuestReadingZeroUsesTheHostFrequency() {
        val rig = joinedGuest(Rig(), hostFrequency = 5180, groupFrequency = 0)
        assertEquals(5180, rig.events.joined.single().frequencyMhz)
    }

    @Test
    fun aGuestReadingZeroWithHostZeroIsBand24() {
        val refused = joinedGuest(Rig(), hostFrequency = 0, groupFrequency = 0)
        assertEquals(listOf(DirectFailure.BAND_24), refused.events.failed)
        assertTrue(refused.events.joined.isEmpty())

        val allowed = joinedGuest(Rig(allow24 = true), hostFrequency = 0, groupFrequency = 0)
        assertEquals(1, allowed.events.joined.size)
        assertEquals(0, allowed.events.joined.single().frequencyMhz)
    }

    @Test
    fun theGroupReadingWinsOverTheHost() {
        val rig = joinedGuest(Rig(), hostFrequency = 5180, groupFrequency = 2437)
        assertEquals(listOf(DirectFailure.BAND_24), rig.events.failed)
        assertEquals(1, rig.port.removeCalls)
    }

    @Test
    fun anOverlappingGuestSubnetIsRefused() {
        val rig = Rig()
        rig.port.addresses = listOf(local("wlan0", "192.168.49.30"))
        rig.group.join(SSID, PSK, 5180)
        rig.scheduler.drain()
        rig.port.completeConnect(P2pResult.Success)
        rig.scheduler.advance(DirectLimits.POLL_MS)
        rig.port.addresses = listOf(local("wlan0", "192.168.49.30"), local(P2P, "192.168.49.2"))
        rig.scheduler.advance(DirectLimits.POLL_MS)
        assertEquals(listOf(DirectFailure.SUBNET_OVERLAP), rig.events.failed)
    }

    @Test
    fun aJoinedGuestRemovesItsGroupOnClose() {
        val rig = joinedGuest(Rig())
        rig.group.close()
        rig.scheduler.drain()
        assertEquals(1, rig.port.removeCalls)
        assertFalse(rig.record.contains(SSID))
        assertFalse(rig.ledger.directGroupActive)
    }

    @Test
    fun closeDuringCreatingRemovesTheLateGroupAndFinishesOnce() {
        val rig = Rig()
        rig.port.addresses = listOf(local(P2P, "192.168.49.1"))
        rig.group.host(SSID, PSK, 5180)
        rig.scheduler.drain()
        var done = 0
        rig.group.close { done++ }
        rig.scheduler.drain()
        assertEquals(0, done)
        rig.port.group = p2pGroup()
        rig.port.completeCreate(P2pResult.Success)
        rig.scheduler.drain()
        assertEquals(1, done)
        assertEquals(1, rig.port.removeCalls)
        assertEquals(1, rig.port.closeCalls)
        assertFalse(rig.ledger.directGroupActive)
    }

    @Test
    fun aSecondCloseRunsAfterTheFirstChain() {
        val rig = readyHost(Rig())
        var first = 0
        var second = 0
        rig.group.close { first++ }
        rig.group.close { second++ }
        rig.scheduler.drain()
        assertEquals(1, first)
        assertEquals(1, second)
    }

    @Test
    fun channelLossReportsOnceAndQuarantinesTheGroup() {
        val rig = readyHost(Rig())
        rig.port.lost?.invoke()
        assertEquals(1, rig.events.lost)
        assertTrue(rig.quarantine.active)
        assertTrue(rig.ledger.directGroupActive)
        rig.port.lost?.invoke()
        assertEquals(1, rig.events.lost)
    }

    @Test
    fun theLedgerRefusalMakesNoPortCallAtAll() {
        val rig = Rig()
        rig.ledger.foreignDirectGroup = true
        rig.group.host(SSID, PSK, 5180)
        rig.scheduler.drain()
        assertEquals(listOf(DirectFailure.BUSY), rig.events.failed)
        assertEquals(0, rig.port.openCalls)
        assertTrue(rig.port.creates.isEmpty())
    }

    @Test
    fun theCleanupWatchdogQuarantinesAndRunsDoneOnce() {
        val rig = readyHost(Rig())
        rig.port.removeAnswer = false
        var done = 0
        rig.group.close { done++ }
        rig.scheduler.drain()
        assertEquals(0, done)
        rig.scheduler.advance(DirectLimits.CLEANUP_WATCHDOG_MS)
        assertEquals(1, done)
        assertTrue(rig.quarantine.active)
        assertTrue(rig.ledger.directGroupActive)
        assertEquals(1, rig.port.closeCalls)
        rig.port.completeRemove(P2pResult.Success)
        rig.scheduler.drain()
        assertEquals(1, done)
    }

    @Test
    fun aFailedRemovalKeepsTheClaimUntilAbsenceIsConfirmed() {
        val rig = readyHost(Rig())
        rig.port.removeResult = P2pResult.Failure(0)
        var done = 0
        rig.group.close { done++ }
        rig.scheduler.drain()
        assertEquals(1, done)
        assertTrue(rig.quarantine.active)
        assertTrue(rig.ledger.directGroupActive)
        assertEquals(1, rig.port.closeCalls)

        rig.port.group = null
        rig.quarantinePort.group = null
        rig.scheduler.advance(DirectQuarantine.RETRY_MS)
        assertFalse(rig.quarantine.active)
        assertFalse(rig.ledger.directGroupActive)
        assertFalse(rig.record.contains(SSID))
    }

    @Test
    fun lateDuplicateCallbacksAfterCloseChangeNothing() {
        val rig = readyHost(Rig())
        rig.group.close()
        rig.scheduler.drain()
        val closeCalls = rig.port.closeCalls
        val removes = rig.port.removeCalls
        rig.port.completeCreate(P2pResult.Success)
        rig.port.lost?.invoke()
        rig.scheduler.drain()
        assertEquals(closeCalls, rig.port.closeCalls)
        assertEquals(removes, rig.port.removeCalls)
        assertTrue(rig.events.failed.isEmpty())
    }

    @Test
    fun aRandomSequenceNeverLeavesTheLedgerHeldAfterClose() {
        val random = java.util.Random(7)
        repeat(30) {
            val rig = Rig()
            rig.group.host(SSID, PSK, 0)
            rig.scheduler.drain()
            repeat(20) {
                when (random.nextInt(5)) {
                    0 -> rig.scheduler.drain()
                    1 -> rig.port.completeCreate(if (random.nextBoolean()) P2pResult.Success else P2pResult.Failure(0))
                    2 -> rig.port.lost?.invoke()
                    3 -> rig.port.group = p2pGroup()
                    4 -> rig.scheduler.advance(random.nextInt(300).toLong())
                }
            }
            rig.group.close()
            rig.scheduler.drain()
            rig.scheduler.advance(DirectLimits.CLEANUP_WATCHDOG_MS + 1)
            if (!rig.quarantine.active) {
                assertFalse("the ledger is released once the cleanup chain finishes", rig.ledger.directGroupActive)
            }
        }
    }

    // ----- A9 review P1-2: a null group read is never "gone" -----

    @Test
    fun anUnreadableOwnGroupIsRemovedOnCloseAndItsAbsenceConfirmedByGroupFormed() {
        val rig = readyHost(Rig(async = true))
        rig.scheduler.drain()
        rig.port.groupReadable = false // the app went to the background on API 29-32: group info reads null
        var done = 0
        rig.group.close { done++ }
        rig.scheduler.advance(DirectLimits.REMOVAL_CONFIRM_MS)
        assertEquals("our own group is removed although its name cannot be read", 1, rig.port.removeCalls)
        assertEquals(1, done)
        assertFalse(rig.quarantine.active)
        assertFalse(rig.ledger.directGroupActive)
        assertFalse(rig.record.contains(SSID))
    }

    @Test
    fun aNullReadWithoutConnectionInfoKeepsTheClaimAndTheRecord() {
        val rig = readyHost(Rig())
        rig.port.groupReadable = false
        rig.port.connectionReadable = false // no answer at all: nothing says the group is gone
        var done = 0
        rig.group.close { done++ }
        rig.scheduler.drain()
        assertEquals(1, done)
        assertEquals("nothing is removed without groupFormed", 0, rig.port.removeCalls)
        assertTrue(rig.quarantine.active)
        assertTrue(rig.ledger.directGroupActive)
        assertTrue("the record keeps the name until absence is confirmed", rig.record.contains(SSID))

        // Back in the foreground the quarantine reads our name and removes the group.
        rig.quarantinePort.group = p2pGroup()
        rig.scheduler.advance(DirectQuarantine.RETRY_MS)
        assertEquals(1, rig.quarantinePort.removeCalls)
        assertFalse(rig.quarantine.active)
        assertFalse(rig.record.contains(SSID))
    }

    @Test
    fun aStartUpSweepInTheBackgroundPrunesNothingAndRemovesNothing() {
        val scheduler = ManualScheduler()
        val ledger = WifiLedger { scheduler.nowMs }
        val record = DirectGroupRecord(MemoryStorage()).also { it.add(SSID) }
        val quarantinePort = Port(scheduler).also { it.group = p2pGroup(); it.groupReadable = false }
        val quarantine = DirectQuarantine({ quarantinePort }, record, ledger, DirectSchedulerAdapter(scheduler), {}, {})
        val port = Port(scheduler).also { it.group = p2pGroup(); it.groupReadable = false }
        val results = ArrayList<DirectGroup.SweepResult>()
        DirectGroup.sweepOnStartup(port, record, ledger, quarantine, DirectSchedulerAdapter(scheduler)) { results += it }
        scheduler.drain()
        assertEquals(listOf(DirectGroup.SweepResult.UNKNOWN), results)
        assertEquals(0, port.removeCalls)
        assertEquals(listOf(SSID), record.names())
        assertTrue("a possibly stranded group of ours blocks new work", quarantine.active)
        assertEquals(1, port.closeCalls)

        // Still unreadable: the quarantine never removes a group it cannot name (nothing in this process created it).
        scheduler.advance(DirectQuarantine.RETRY_MS)
        assertEquals(0, quarantinePort.removeCalls)
        assertEquals(listOf(SSID), record.names())
        assertTrue(quarantine.active)

        // Readable again (the app is visible): our recorded name is removed and the claim released.
        quarantinePort.groupReadable = true
        scheduler.advance(DirectQuarantine.RETRY_MS)
        assertEquals(1, quarantinePort.removeCalls)
        assertFalse(quarantine.active)
        assertTrue(record.names().isEmpty())
        assertEquals(DirectReserve.OK, ledger.reserveDirect("direct"))
    }

    @Test
    fun aStartUpSweepThatSeesNoGroupFormedClearsTheRecord() {
        val scheduler = ManualScheduler()
        val ledger = WifiLedger { scheduler.nowMs }
        val record = DirectGroupRecord(MemoryStorage()).also { it.add(SSID) }
        val quarantine = DirectQuarantine({ Port(scheduler) }, record, ledger, DirectSchedulerAdapter(scheduler), {}, {})
        val port = Port(scheduler).also { it.groupReadable = false } // no group: groupFormed false
        val results = ArrayList<DirectGroup.SweepResult>()
        DirectGroup.sweepOnStartup(port, record, ledger, quarantine, DirectSchedulerAdapter(scheduler)) { results += it }
        assertEquals(listOf(DirectGroup.SweepResult.CLEAR), results)
        assertTrue(record.names().isEmpty())
        assertFalse(quarantine.active)
    }

    // ----- A9 review P2-1: the removal is confirmed by a short poll -----

    @Test
    fun aRemovalTheFrameworkReportsLateIsConfirmedWithoutQuarantine() {
        val rig = readyHost(Rig(async = true))
        rig.scheduler.drain()
        rig.port.removeLagMs = 600 // REMOVE_GROUP_SUCCEEDED arrives before the supplicant's group-removed event
        var done = 0
        rig.group.close { done++ }
        rig.scheduler.advance(DirectLimits.REMOVAL_CONFIRM_MS)
        assertEquals(1, done)
        assertFalse("a normal call end never enters the quarantine", rig.quarantine.active)
        assertFalse(rig.ledger.directGroupActive)
        assertFalse(rig.record.contains(SSID))
    }

    @Test
    fun aGroupStillReportedAfterTheConfirmWindowIsQuarantined() {
        val rig = readyHost(Rig(async = true))
        rig.scheduler.drain()
        rig.port.removeLagMs = 60_000
        var done = 0
        rig.group.close { done++ }
        rig.scheduler.advance(DirectLimits.REMOVAL_CONFIRM_MS - DirectLimits.REMOVAL_POLL_MS)
        assertEquals("still inside the confirm window", 0, done)
        rig.scheduler.advance(2 * DirectLimits.REMOVAL_POLL_MS)
        assertEquals(1, done)
        assertTrue(rig.quarantine.active)
        assertTrue(rig.ledger.directGroupActive)
        assertTrue(rig.record.contains(SSID))
    }

    // ----- A9 review P1-3: record I/O in asynchronous callbacks never throws -----

    @Test
    fun anEmergencyWipeDuringCloseStillRemovesTheGroupAndNeverThrows() {
        val storage = WipeableStorage()
        val rig = readyHost(Rig(async = true, storage = storage))
        rig.scheduler.drain()
        assertTrue(rig.record.contains(SSID))
        // EmergencyWipe.run deletes the Keystore keys first, then the terminators close the driver and its group.
        storage.wiped = true
        var done = 0
        rig.group.close { done++ }
        rig.scheduler.advance(DirectLimits.CLEANUP_WATCHDOG_MS)
        assertEquals("the group is removed although the record cannot be read", 1, rig.port.removeCalls)
        assertEquals(1, done)
        assertFalse(rig.quarantine.active)
        assertFalse(rig.ledger.directGroupActive)
    }

    @Test
    fun anEmergencyWipeDuringAQuarantineRetryStillConfirmsAndNeverThrows() {
        val storage = WipeableStorage()
        val rig = readyHost(Rig(async = true, storage = storage))
        rig.scheduler.drain()
        rig.port.removeResult = P2pResult.Failure(0)
        rig.group.close()
        rig.scheduler.drain()
        assertTrue(rig.quarantine.active)

        storage.wiped = true
        rig.quarantinePort.group = p2pGroup()
        rig.scheduler.advance(DirectQuarantine.RETRY_MS + DirectLimits.REMOVAL_CONFIRM_MS)
        assertEquals("the quarantine removes the group it remembers by name", 1, rig.quarantinePort.removeCalls)
        assertFalse(rig.quarantine.active)
        assertFalse(rig.ledger.directGroupActive)
    }

    @Test
    fun anUnreadableRecordKeepsAnotherNamedGroupAndNeverThrows() {
        val storage = WipeableStorage()
        val rig = Rig(async = true, storage = storage)
        storage.text = SSID
        storage.wiped = true
        rig.port.group = p2pGroup(name = "DIRECT-zz-999999")
        rig.group.host("DIRECT-ab-cdefgh", PSK, 5180)
        rig.scheduler.advance(DirectLimits.CLEANUP_WATCHDOG_MS)
        assertEquals("a name the record cannot judge is never removed", 0, rig.port.removeCalls)
        assertEquals(listOf(DirectFailure.BUSY), rig.events.failed)
        assertTrue(rig.port.creates.isEmpty())
        assertEquals("nothing pruned", SSID, storage.text)
    }

    // ----- A9 review P2-2: the start-up sweep is bounded -----

    @Test
    fun aStartUpSweepWhoseCallbackNeverComesReleasesEverythingOnce() {
        val scheduler = ManualScheduler()
        val ledger = WifiLedger { scheduler.nowMs }
        val record = DirectGroupRecord(MemoryStorage()).also { it.add(SSID) }
        val quarantine = DirectQuarantine({ Port(scheduler) }, record, ledger, DirectSchedulerAdapter(scheduler), {}, {})
        val port = Port(scheduler).also { it.groupAnswer = false }
        val results = ArrayList<DirectGroup.SweepResult>()
        DirectGroup.sweepOnStartup(port, record, ledger, quarantine, DirectSchedulerAdapter(scheduler)) { results += it }
        assertTrue(ledger.directGroupActive)
        scheduler.advance(DirectLimits.CLEANUP_WATCHDOG_MS)
        assertEquals(listOf(DirectGroup.SweepResult.TIMEOUT), results)
        assertEquals(1, port.closeCalls)
        assertFalse(quarantine.active)
        assertEquals(DirectReserve.OK, ledger.reserveDirect("direct"))
        assertEquals("nothing pruned", listOf(SSID), record.names())
    }

    @Test
    fun aStartUpSweepThatSawOurGroupAndTimesOutQuarantinesIt() {
        val scheduler = ManualScheduler()
        val ledger = WifiLedger { scheduler.nowMs }
        val record = DirectGroupRecord(MemoryStorage()).also { it.add(SSID) }
        val quarantine = DirectQuarantine({ Port(scheduler) }, record, ledger, DirectSchedulerAdapter(scheduler), {}, {})
        val port = Port(scheduler).also { it.group = p2pGroup(); it.removeAnswer = false }
        val results = ArrayList<DirectGroup.SweepResult>()
        DirectGroup.sweepOnStartup(port, record, ledger, quarantine, DirectSchedulerAdapter(scheduler)) { results += it }
        scheduler.advance(DirectLimits.CLEANUP_WATCHDOG_MS)
        assertEquals(listOf(DirectGroup.SweepResult.REMOVE_FAILED), results)
        assertTrue(quarantine.active)
        assertEquals(DirectReserve.BUSY_OWN, ledger.reserveDirect("direct"))
        assertEquals(1, port.closeCalls)
    }

    // ----- A9 review P2-3: the guest keeps the host's frequency -----

    @Test
    fun aGuestJoinThatFailsIsNotRetriedWithoutTheHostFrequency() {
        val rig = Rig()
        rig.port.addresses = listOf(local("wlan0", "10.0.0.8"))
        rig.group.join(SSID, PSK, 5180)
        rig.scheduler.drain()
        rig.port.completeConnect(P2pResult.Failure(0))
        rig.scheduler.drain()
        assertEquals(listOf(SSID to 5180), rig.port.connects)
        assertEquals(listOf(DirectFailure.PLATFORM_ERROR), rig.events.failed)
    }

    @Test
    fun aGuestThatJoinsAfterABusyRetryAndReadsZeroUsesTheHostFrequency() {
        val rig = Rig()
        rig.port.addresses = listOf(local("wlan0", "10.0.0.8"))
        rig.group.join(SSID, PSK, 5180)
        rig.scheduler.drain()
        rig.port.completeConnect(P2pResult.Failure(OfflineAppSharePolicy.P2P_BUSY))
        rig.scheduler.advance(1_000)
        rig.port.completeConnect(P2pResult.Success)
        rig.scheduler.advance(DirectLimits.POLL_MS)
        rig.port.addresses = listOf(local("wlan0", "10.0.0.8"), local(P2P, "192.168.49.2"))
        rig.port.group = p2pGroup(owner = false, freq = 0)
        rig.port.connection = P2pConnection(true, false, addr("192.168.49.1"))
        rig.scheduler.advance(DirectLimits.POLL_MS)
        assertEquals(listOf(5180, 5180), rig.port.connects.map { it.second })
        assertEquals(5180, rig.events.joined.single().frequencyMhz)
        assertEquals("host", rig.events.joined.single().frequencySource)
    }

    // ----- A9 review P3s -----

    @Test
    fun aCreateWhileNotVisibleFailsBusyAtOnceWithoutRetriesOrFault() {
        val rig = Rig()
        rig.group.host(SSID, PSK, 5180)
        rig.scheduler.drain()
        rig.port.completeCreate(P2pResult.NotVisible)
        rig.scheduler.advance(10_000)
        assertEquals(1, rig.port.creates.size)
        assertEquals(listOf(DirectFailure.BUSY), rig.events.failed)
        assertEquals(0L, rig.ledger.pauseUntilMs)
        assertFalse(rig.ledger.directGroupActive)
    }

    @Test
    fun theWatchdogDuringAPendingCreateQuarantinesTheLateGroup() {
        val rig = Rig()
        rig.group.host(SSID, PSK, 5180)
        rig.scheduler.drain()
        rig.group.close()
        rig.scheduler.advance(DirectLimits.CLEANUP_WATCHDOG_MS)
        assertTrue("the create may still form a group", rig.quarantine.active)
        assertTrue(rig.ledger.directGroupActive)

        // The group forms after the channel closed; the quarantine finds it by its recorded name.
        rig.quarantinePort.group = p2pGroup()
        rig.scheduler.advance(DirectQuarantine.RETRY_MS)
        assertEquals(1, rig.quarantinePort.removeCalls)
        assertFalse(rig.quarantine.active)
        assertFalse(rig.ledger.directGroupActive)
    }

    @Test
    fun aHostWhoseGroupSubnetOverlapsAnotherInterfaceIsRefused() {
        val rig = Rig()
        rig.port.addresses = listOf(local(P2P, "192.168.49.1"), local("wlan0", "192.168.49.30"))
        rig.group.host(SSID, PSK, 5180)
        rig.scheduler.drain()
        rig.port.completeCreate(P2pResult.Success)
        rig.port.group = p2pGroup()
        rig.scheduler.advance(DirectLimits.FIRST_POLL_MS)
        assertEquals(listOf(DirectFailure.SUBNET_OVERLAP), rig.events.failed)
        assertTrue(rig.events.ready.isEmpty())
        assertEquals(1, rig.port.removeCalls)
    }
}
