package com.example.bluetoothtest

import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBearerDriverTest {
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

    private class FakeP2p : P2pPort {
        var openResult = true
        var openCalls = 0
        var closeCalls = 0
        val creates = ArrayList<Pair<String, Int>>()
        val connects = ArrayList<Pair<String, Int>>()
        var createDone: ((P2pResult) -> Unit)? = null
        var connectDone: ((P2pResult) -> Unit)? = null
        var group: P2pGroup? = null
        var connection: P2pConnection? = null
        var addresses: List<DirectAddressRules.LocalAddress> = emptyList()
        var requestGroupCalls = 0
        var requestConnectionCalls = 0
        var removeAnswer = true
        var removeResult: P2pResult = P2pResult.Success
        var lost: (() -> Unit)? = null

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

        override fun requestGroup(done: (P2pGroup?) -> Unit) {
            requestGroupCalls++
            done(group)
        }

        /** Follows the group unless set (groupFormed is what the platform reports without the location check). */
        override fun requestConnection(done: (P2pConnection?) -> Unit) {
            requestConnectionCalls++
            done(connection ?: P2pConnection(group != null, group?.isOwner == true, null))
        }

        override fun removeGroup(done: (P2pResult) -> Unit) {
            if (!removeAnswer) return
            if (removeResult == P2pResult.Success) {
                group = null
                connection = null
            }
            done(removeResult)
        }

        override fun requestDeviceName(done: (String?) -> Unit) = done(null)

        override fun closeChannel() {
            closeCalls++
        }

        override fun localAddresses(): List<DirectAddressRules.LocalAddress> = addresses
    }

    private class FakeSocket(private val remote: InetAddress) : Socket() {
        @Volatile
        private var closedFlag = false

        override fun getInetAddress(): InetAddress = remote
        override fun close() {
            closedFlag = true
        }

        override fun isClosed(): Boolean = closedFlag
    }

    private class FakeServerSocket : ServerSocket() {
        val queue = LinkedBlockingQueue<Socket>()

        @Volatile
        private var closedFlag = false

        override fun accept(): Socket {
            while (true) {
                if (closedFlag) throw SocketException("closed")
                val socket = queue.poll(5, TimeUnit.MILLISECONDS)
                if (socket != null) return socket
            }
        }

        override fun close() {
            closedFlag = true
        }

        override fun isClosed(): Boolean = closedFlag
        override fun getLocalPort(): Int = 4545
        override fun getInetAddress(): InetAddress = InetAddress.getByName("192.168.49.1")
    }

    private class FakeSockets : DirectSocketPort {
        val server = FakeServerSocket()
        var listenError: IOException? = null
        var admitResult: (Socket) -> Boolean = { true }
        val connects = ArrayDeque<Result<Socket>>()
        var connectError: RuntimeException? = null

        @Volatile
        var connectCalls = 0
        var listened: DirectAddressRules.LocalAddress? = null

        override fun listen(local: DirectAddressRules.LocalAddress): ServerSocket {
            listened = local
            listenError?.let { throw it }
            return server
        }

        override fun admit(accepted: Socket, local: DirectAddressRules.LocalAddress): Boolean = admitResult(accepted)

        override fun connect(local: DirectAddressRules.LocalAddress, host: Inet4Address, port: Int): Socket {
            connectCalls++
            connectError?.let { throw it }
            val result = if (connects.isEmpty()) Result.failure(ConnectException("refused")) else connects.removeFirst()
            return result.getOrThrow()
        }
    }

    private class Events : BearerEvents {
        val hostReady = ArrayList<Triple<Int, ByteArray, Int>>()
        val joined = ArrayList<Int?>()
        val failed = ArrayList<BearerFailure>()
        val accepted = ArrayList<Pair<Socket, Int>>()
        val rejected = ArrayList<String>()
        val connected = ArrayList<Socket>()
        val connectFailed = ArrayList<Pair<String, Boolean>>()
        val lost = ArrayList<AwareDownReason>()

        override fun onHostReady(gen: Long, freqMhz: Int, ipv4: ByteArray, port: Int) {
            hostReady += Triple(freqMhz, ipv4, port)
        }

        override fun onJoined(gen: Long, freqMhz: Int?) {
            joined += freqMhz
        }

        override fun onFailed(gen: Long, failure: BearerFailure) {
            failed += failure
        }

        override fun onSocketAccepted(gen: Long, socket: Socket, ordinal: Int, acceptedAtNs: Long) {
            accepted += socket to ordinal
        }

        override fun onSocketRejected(gen: Long, reason: String) {
            rejected += reason
        }

        override fun onConnected(gen: Long, socket: Socket, connectedAtNs: Long) {
            connected += socket
        }

        override fun onConnectFailed(gen: Long, reason: String, refusedOnly: Boolean) {
            connectFailed += reason to refusedOnly
        }

        override fun onLost(gen: Long, reason: AwareDownReason) {
            lost += reason
        }
    }

    private class Rig(enabled: Boolean = true) {
        val scheduler = ManualScheduler()
        val adapter = DirectSchedulerAdapter(scheduler)
        val p2p = FakeP2p()
        val sockets = FakeSockets()
        val record = DirectGroupRecord(MemoryStorage())
        val ledger = WifiLedger { scheduler.nowMs }
        val events = Events()
        val quarantinePort = FakeP2p()
        val quarantine = DirectQuarantine({ quarantinePort }, record, ledger, adapter, {}, {})
        val resumes = ArrayList<String>()
        val actions = ArrayList<String>()
        val clock = AtomicLong(0)
        val gateScheduler = ManualScheduler()
        val gate = DiscoveryPauseGate(gateScheduler) {}
        var enabledValue = enabled

        @Volatile
        var sleepHook: ((Long) -> Unit)? = null

        init {
            gate.register(
                object : DiscoveryPauser {
                    override fun pauseDiscovery(owner: String) = Unit
                    override fun resumeDiscovery(owner: String) {
                        resumes += owner
                        actions += "resume:${ledger.directGroupActive}"
                    }
                },
            )
        }

        val driver = DirectBearerDriver(
            generation = 7,
            events = events,
            p2p = p2p,
            record = record,
            ledger = ledger,
            scheduler = adapter,
            post = scheduler::post,
            clock = { clock.get() },
            pauseDiscovery = { gate.pause(it) },
            staFrequencyMhz = { 5180 },
            allow24Ghz = false,
            enabled = { enabledValue },
            register = { actions += "register" },
            unregister = { actions += "unregister" },
            quarantine = quarantine,
            sockets = sockets,
            sleep = { ms ->
                val hook = sleepHook
                if (hook != null) hook(ms) else clock.addAndGet(ms)
            },
        )

        fun prepare() {
            assertNull(driver.prepare())
            scheduler.drain()
        }
    }

    private fun addr(text: String): Inet4Address = InetAddress.getByName(text) as Inet4Address

    private fun local(name: String, text: String, prefix: Int = 24) =
        DirectAddressRules.LocalAddress(name, addr(text), prefix)

    private fun group(freq: Int = 5180, owner: Boolean = true, iface: String? = "p2p-wlan0-0") =
        P2pGroup(SSID, owner, iface, freq)

    private fun hostReady(rig: Rig, groupFrequency: Int = 5180) {
        rig.prepare()
        rig.driver.startHost(SSID, PSK)
        rig.scheduler.drain()
        rig.p2p.completeCreate(P2pResult.Success)
        rig.p2p.group = group(groupFrequency)
        rig.p2p.addresses = listOf(local("p2p-wlan0-0", "192.168.49.1"))
        rig.scheduler.advance(DirectLimits.FIRST_POLL_MS)
        rig.scheduler.drain()
    }

    private fun guestJoined(rig: Rig, hostFrequency: Int = 5180, groupFrequency: Int = 5180) {
        rig.prepare()
        rig.p2p.addresses = listOf(local("wlan0", "10.0.0.8"))
        rig.driver.startGuest(SSID, PSK, hostFrequency)
        rig.scheduler.drain()
        assertEquals("the guest join was issued", 1, rig.p2p.connects.size)
        rig.p2p.completeConnect(P2pResult.Success)
        rig.scheduler.advance(DirectLimits.POLL_MS)
        rig.p2p.addresses = listOf(local("wlan0", "10.0.0.8"), local("p2p-wlan0-0", "192.168.49.2"))
        rig.p2p.group = group(groupFrequency, owner = false)
        rig.p2p.connection = P2pConnection(true, false, addr("192.168.49.1"))
        rig.scheduler.advance(2 * DirectLimits.POLL_MS)
        rig.scheduler.drain()
        assertTrue("the guest poll ran: ${rig.p2p.requestGroupCalls}", rig.p2p.requestGroupCalls >= 3)
    }

    @Test
    fun prepareBusySwitchOffAndClosed() {
        val busy = Rig()
        busy.ledger.reserveDirect("share")
        assertEquals(BearerFailure("busy", 2, false).reason, busy.driver.prepare()?.reason)
        assertTrue(busy.resumes.isEmpty())

        val off = Rig(enabled = false)
        assertEquals("switch_off", off.driver.prepare()?.reason)
        assertTrue(off.resumes.isEmpty())

        val closed = Rig()
        closed.driver.close()
        closed.scheduler.drain()
        assertEquals("closed", closed.driver.prepare()?.reason)
    }

    @Test
    fun prepareTakesOneLeaseAndRegistersForShutdown() {
        val rig = Rig()
        rig.prepare()
        assertEquals(listOf("register"), rig.actions)
        rig.driver.close()
        rig.scheduler.drain()
        assertEquals(1, rig.resumes.size)
        assertEquals("resume:false", rig.actions.last())
    }

    @Test
    fun closeWithoutStartHostStillResumesTheLeaseOnce() {
        val rig = Rig()
        rig.prepare()
        rig.driver.close()
        rig.driver.close()
        rig.scheduler.drain()
        assertEquals(1, rig.resumes.size)
        assertTrue(rig.actions.contains("unregister"))
    }

    @Test
    fun hostSendsTheHostFrequencyAndReportsOnlyAfterListening() {
        val rig = Rig()
        rig.prepare()
        rig.driver.startHost(SSID, PSK)
        rig.scheduler.drain()
        assertEquals(5180, rig.p2p.creates.single().second)
        rig.p2p.completeCreate(P2pResult.Success)
        rig.p2p.group = group()
        rig.p2p.addresses = listOf(local("p2p-wlan0-0", "192.168.49.1"))
        rig.scheduler.advance(DirectLimits.FIRST_POLL_MS)
        rig.scheduler.drain()
        assertEquals(1, rig.events.hostReady.size)
        assertEquals(5180, rig.events.hostReady.single().first)
        assertEquals(4545, rig.events.hostReady.single().third)
        assertTrue(rig.events.hostReady.single().second.contentEquals(byteArrayOf(192.toByte(), 168.toByte(), 49, 1)))
        assertEquals("p2p-wlan0-0", rig.sockets.listened?.interfaceName)
    }

    @Test
    fun hostFrequencyZeroForDfsOr24() {
        val dfs = Rig()
        val driver = DirectBearerDriver(
            generation = 8,
            events = dfs.events,
            p2p = dfs.p2p,
            record = dfs.record,
            ledger = dfs.ledger,
            scheduler = dfs.adapter,
            post = dfs.scheduler::post,
            clock = { dfs.clock.get() },
            pauseDiscovery = { dfs.gate.pause(it) },
            staFrequencyMhz = { 5260 },
            allow24Ghz = false,
            enabled = { true },
            register = {},
            unregister = {},
            quarantine = dfs.quarantine,
            sockets = dfs.sockets,
            sleep = { dfs.clock.addAndGet(it) },
        )
        driver.startHost(SSID, PSK)
        dfs.scheduler.drain()
        assertEquals(0, dfs.p2p.creates.single().second)
    }

    @Test
    fun theAcceptLoopAdmitsFourAndRejectsTheFifthAndOutsideSubnet() {
        val rig = Rig()
        hostReady(rig)
        val sockets = (1..5).map { FakeSocket(addr("192.168.49.5")) }
        sockets.forEach { rig.sockets.server.queue.add(it) }
        assertTrue(rig.scheduler.runUntil(2_000) { rig.events.accepted.size == 4 && rig.events.rejected.contains("too_many") })
        assertEquals(listOf(1, 2, 3, 4), rig.events.accepted.map { it.second })
        assertTrue(rig.events.rejected.contains("too_many"))

        val rig2 = Rig()
        hostReady(rig2)
        rig2.sockets.admitResult = { false }
        rig2.sockets.server.queue.add(FakeSocket(addr("10.0.0.9")))
        assertTrue(rig2.scheduler.runUntil(2_000) { rig2.events.rejected.contains("outside_subnet") })
    }

    @Test
    fun aWindowsShapedGuestJoinIsBand24UnlessAllow24() {
        val refused = Rig()
        guestJoined(refused, hostFrequency = 0, groupFrequency = 0)
        assertEquals("band_24", refused.events.failed.single().reason)
        assertEquals(7, refused.events.failed.single().decline)
        assertFalse(refused.events.failed.single().local)
    }

    @Test
    fun guestJoinedReportsTheFrequencyAndConnectChecksTheCredsAddress() {
        val rig = Rig()
        guestJoined(rig)
        assertEquals(listOf(5180), rig.events.joined)

        rig.driver.connect(byteArrayOf(10, 0, 0, 1), 4545)
        rig.scheduler.drain()
        assertEquals("creds_off_link", rig.events.failed.single().reason)
        assertEquals(6, rig.events.failed.single().decline)
        assertFalse(rig.events.failed.single().local)
    }

    @Test
    fun connectRefusesANonPrivateOwnerMismatchAndAnOverlap() {
        val notPrivate = Rig()
        guestJoined(notPrivate)
        notPrivate.driver.connect(byteArrayOf(8, 8, 8, 8), 4545)
        notPrivate.scheduler.drain()
        assertEquals("creds_off_link", notPrivate.events.failed.single().reason)

        val mismatch = Rig()
        guestJoined(mismatch)
        mismatch.driver.connect(byteArrayOf(192.toByte(), 168.toByte(), 49, 3), 4545)
        mismatch.scheduler.drain()
        assertEquals("creds_off_link", mismatch.events.failed.single().reason)

        val overlap = Rig()
        guestJoined(overlap)
        overlap.p2p.addresses = listOf(
            local("wlan0", "192.168.49.30"),
            local("p2p-wlan0-0", "192.168.49.2"),
        )
        overlap.driver.connect(byteArrayOf(192.toByte(), 168.toByte(), 49, 1), 4545)
        overlap.scheduler.drain()
        assertEquals("subnet_overlap", overlap.events.failed.single().reason)
        assertEquals(3, overlap.events.failed.single().decline)
    }

    @Test
    fun aRefusedConnectRetriesInsideTheWindowThenTimesOut() {
        val rig = Rig()
        guestJoined(rig)
        rig.driver.connect(byteArrayOf(192.toByte(), 168.toByte(), 49, 1), 4545)
        assertTrue(rig.scheduler.runUntil(2_000) { rig.events.connectFailed.isNotEmpty() })
        assertEquals("timeout" to true, rig.events.connectFailed.single())
        assertTrue(rig.sockets.connectCalls >= 20)
    }

    @Test
    fun aSuccessfulConnectReportsConnected() {
        val rig = Rig()
        guestJoined(rig)
        val socket = FakeSocket(addr("192.168.49.1"))
        rig.sockets.connects.add(Result.success(socket))
        rig.driver.connect(byteArrayOf(192.toByte(), 168.toByte(), 49, 1), 4545)
        assertTrue(rig.scheduler.runUntil(2_000) { rig.events.connected.isNotEmpty() })
        assertTrue(rig.events.connected.single() === socket)
    }

    @Test
    fun aJoinTimeoutIsNeverAPhoneFault() {
        val rig = Rig()
        rig.prepare()
        rig.p2p.addresses = listOf(local("wlan0", "10.0.0.8"))
        rig.driver.startGuest(SSID, PSK, 0)
        rig.scheduler.drain()
        rig.p2p.completeConnect(P2pResult.Success)
        rig.clock.set(DirectLimits.GROUP_READY_MS + 1)
        rig.scheduler.advance(DirectLimits.POLL_MS)
        rig.scheduler.drain()
        assertEquals("timeout", rig.events.failed.single().reason)
        assertFalse(rig.events.failed.single().local)
        assertEquals(0L, rig.ledger.pauseUntilMs)
    }

    @Test
    fun aLocalListenFailureIsRecordedAndReportedLocal() {
        val rig = Rig()
        rig.prepare()
        rig.sockets.listenError = IOException("no socket")
        rig.driver.startHost(SSID, PSK)
        rig.scheduler.drain()
        rig.p2p.completeCreate(P2pResult.Success)
        rig.p2p.group = group()
        rig.p2p.addresses = listOf(local("p2p-wlan0-0", "192.168.49.1"))
        rig.scheduler.advance(DirectLimits.FIRST_POLL_MS)
        rig.scheduler.drain()
        assertEquals("platform_error", rig.events.failed.single().reason)
        assertTrue(rig.events.failed.single().local)
    }

    @Test
    fun handOffStopsTheAcceptLoopAndRoutesALaterLossOnce() {
        val rig = Rig()
        hostReady(rig)
        var losses = 0
        assertTrue(rig.driver.handOff { losses++ })
        rig.p2p.lost?.invoke()
        assertEquals(1, losses)

        rig.enabledValue = false
        assertFalse(rig.driver.handOff { })
        rig.driver.close()
        assertFalse(rig.driver.handOff { })
    }

    @Test
    fun aLossBeforeHandOffGoesToTheEventsAndOnlyOnce() {
        val rig = Rig()
        hostReady(rig)
        rig.p2p.lost?.invoke()
        rig.scheduler.drain()
        assertEquals(listOf(AwareDownReason.UNAVAILABLE), rig.events.lost)
        rig.p2p.lost?.invoke()
        rig.scheduler.drain()
        assertEquals(1, rig.events.lost.size)
    }

    @Test
    fun aQuarantinedDriverRefusesAndHandsItsLeaseToTheQuarantine() {
        val rig = Rig()
        rig.ledger.reserveDirect("share")
        rig.quarantine.enter(SSID, "share")
        assertEquals("busy", rig.driver.prepare()?.reason)
        assertTrue(rig.resumes.isEmpty())
    }

    @Test
    fun closeHandsAPreparedLeaseToTheQuarantineAndNeverResumesIt() {
        val rig = Rig()
        rig.prepare()
        rig.quarantine.enter(SSID, "direct")
        rig.driver.close()
        rig.scheduler.drain()
        assertTrue(rig.quarantine.active)
        assertTrue(rig.resumes.isEmpty())
    }

    @Test
    fun aCloseFromAnotherThreadDuringPrepareResumesTheLeaseOnce() {
        val rig = Rig()
        rig.scheduler.strictThreads = true
        rig.prepare()
        assertEquals(0, rig.resumes.size)
        rig.driver.close()
        rig.scheduler.drain()
        assertEquals(1, rig.resumes.size)
    }

    /** Runs [block] with a default uncaught-exception handler that records any thread death (A9 review P1-1, P2-4). */
    private fun recordingUncaught(block: (deaths: List<Throwable>) -> Unit) {
        val deaths = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> deaths += error }
        try {
            block(deaths)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun aCloseDuringTheRetrySleepEndsTheConnectThreadQuietly() = recordingUncaught { deaths ->
        val rig = Rig()
        guestJoined(rig)
        val sleeper = LinkedBlockingQueue<Thread>()
        // The host's server is gone, so the connect is refused; the attempt closes while the thread sleeps: close()
        // interrupts it and the production Thread.sleep throws InterruptedException.
        rig.sleepHook = { _ ->
            sleeper.add(Thread.currentThread())
            rig.driver.close("declined")
            throw InterruptedException("sleep interrupted")
        }
        rig.driver.connect(byteArrayOf(192.toByte(), 168.toByte(), 49, 1), 4545)
        val thread = sleeper.poll(2, TimeUnit.SECONDS)
        assertTrue("the connect thread reached its retry sleep", thread != null)
        thread!!.join(2_000)
        assertFalse(thread.isAlive)
        rig.scheduler.drain()
        assertTrue("the thread never dies uncaught: $deaths", deaths.isEmpty())
        assertTrue("nothing is reported after close", rig.events.connectFailed.isEmpty())
        assertEquals(1, rig.resumes.size)
    }

    @Test
    fun aThrowingConnectNeverKillsTheProcessAndEndsTheAttempt() = recordingUncaught { deaths ->
        val rig = Rig()
        guestJoined(rig)
        rig.sockets.connectError = SecurityException("bind refused")
        rig.driver.connect(byteArrayOf(192.toByte(), 168.toByte(), 49, 1), 4545)
        assertTrue(rig.scheduler.runUntil(2_000) { rig.events.connectFailed.isNotEmpty() })
        assertEquals("io" to false, rig.events.connectFailed.single())
        assertTrue(deaths.isEmpty())
    }

    @Test
    fun aSocketClosedBeforeAdmitIsRejectedAndTheAcceptLoopLivesOn() = recordingUncaught { deaths ->
        val rig = Rig()
        hostReady(rig)
        var calls = 0
        rig.sockets.admitResult = {
            calls++
            if (calls == 1) throw java.net.SocketException("Socket is closed") else true
        }
        rig.sockets.server.queue.add(FakeSocket(addr("192.168.49.5")))
        rig.sockets.server.queue.add(FakeSocket(addr("192.168.49.6")))
        assertTrue(rig.scheduler.runUntil(2_000) { rig.events.accepted.size == 1 })
        assertEquals(listOf("error"), rig.events.rejected)
        assertTrue(deaths.isEmpty())
    }

    @Test
    fun aConnectThatNeverAnswersEndsAsATimeoutNotAnError() {
        val rig = Rig()
        guestJoined(rig)
        // An always-on VPN that blocks connections outside it: the 5 s connect times out (AM 22: connect_timeout).
        rig.sockets.connects.add(Result.failure(java.net.SocketTimeoutException("connect timed out")))
        rig.driver.connect(byteArrayOf(192.toByte(), 168.toByte(), 49, 1), 4545)
        assertTrue(rig.scheduler.runUntil(2_000) { rig.events.connectFailed.isNotEmpty() })
        assertEquals("timeout", rig.events.connectFailed.single().first)
    }

    @Test
    fun theGuestLogsWhereItsFrequencyCameFromAndDriverFailuresAreLogged() {
        val lines = ArrayList<String>()
        val previous = WifiLog.sink
        WifiLog.sink = { lines += it }
        try {
            val rig = Rig()
            guestJoined(rig, hostFrequency = 5180, groupFrequency = 0)
            assertTrue(lines.toString(), lines.any { it.contains("event=ready") && it.contains("freq_source=host") })
            rig.driver.connect(byteArrayOf(10, 0, 0, 1), 4545)
            rig.scheduler.drain()
            assertTrue(lines.toString(), lines.any { it.contains("event=failed") && it.contains("reason=creds_off_link") })
        } finally {
            WifiLog.sink = previous
        }
    }
}
