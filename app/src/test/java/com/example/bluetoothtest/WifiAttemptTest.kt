package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class WifiAttemptTest {
    private val uw = ByteArray(32) { (it + 5).toByte() }
    private val ticket = ByteArray(8) { (it + 9).toByte() }

    private fun socketPair(): Pair<Socket, Socket> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket()
        client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), 2_000)
        val accepted = server.accept()
        server.close()
        return accepted to client
    }

    private fun assertNoSecretShapes(lines: List<String>) {
        val hash = Regex("h:[0-9a-f]{16}")
        for (raw in lines) {
            val line = hash.replace(raw, "")
            assertFalse("MAC in: $raw", Regex("(?i)([0-9a-f]{2}[:-]){5}[0-9a-f]{2}").containsMatchIn(line))
            assertFalse("IPv6 in: $raw", Regex("(?i)([0-9a-f]{0,4}:){2,}[0-9a-f]{0,4}").containsMatchIn(line))
            assertFalse("long hex in: $raw", Regex("(?i)[0-9a-f]{16,}").containsMatchIn(line))
        }
    }

    private class GuestSide(
        val env: FakeEnv,
        val ports: FakeWifiPorts,
        val link: FakeAware?,
        val attempt: WifiAttempt,
        val key: ByteArray,
        val outcome: () -> Outcome?,
        val internalErrors: MutableList<String> = ArrayList(),
    )

    private fun guestSide(
        env: FakeEnv = FakeEnv(),
        gen: Long = 1L,
        reserve: Boolean = true,
        binder: WifiBinder = RealBinder,
        bearer: WifiBearer = WifiBearer.AWARE,
        driver: FakeBearerDriver? = null,
    ): GuestSide {
        env.binderValue = binder
        if (reserve && bearer == WifiBearer.AWARE) env.ledger.reserveNdp("wifi-$gen", 4)
        if (driver != null) {
            env.supported += WifiBearer.DIRECT
            env.driverFactory = { _, _ -> driver }
        }
        val ports = FakeWifiPorts(isLinkInitiator = true)
        val key = uw.copyOf()
        var end: Outcome? = null
        val internalErrors = ArrayList<String>()
        val decision = env.decide(env.facts())
        val attempt = WifiAttempt(
            role = AttemptRole.GUEST,
            generation = gen,
            ticket = ticket.copyOf(),
            uw = key,
            decision = decision,
            env = env,
            ports = ports,
            icm = false,
            ndpOwner = if (bearer == WifiBearer.AWARE) "wifi-$gen" else null,
            bearer = bearer,
            onEnded = { outcome, _, _ -> end = outcome },
            onInternalError = { internalErrors += it },
        )
        val link = if (bearer == WifiBearer.AWARE) env.links[gen] ?: error("no link") else null
        return GuestSide(env, ports, link, attempt, key, { end }, internalErrors)
    }

    private fun phaseOf(attempt: WifiAttempt): Phase {
        val field = WifiAttempt::class.java.getDeclaredField("model").also { it.isAccessible = true }
        return (field.get(attempt) as AttemptModel).phase
    }

    private fun GuestSide.toRequesting(): GuestSide {
        val gen = attempt.generation
        val link = link ?: error("aware guest expected")
        attempt.start()
        env.scheduler.drain()
        env.scheduler.drain()
        link.discover(1)
        env.scheduler.drain()
        attempt.dispatch(ChallengeIn(gen, 1, true, ByteArray(16) { 7 }))
        attempt.dispatch(ArmedIn(gen, true))
        assertEquals(Phase.REQUESTING, phaseOf(attempt))
        return this
    }

    private fun GuestSide.toAddressing(): GuestSide {
        toRequesting()
        attempt.dispatch(NetworkAvailable(attempt.generation))
        assertEquals(Phase.ADDRESSING, phaseOf(attempt))
        return this
    }

    private fun GuestSide.toConnecting(): GuestSide {
        toAddressing()
        val aware = link ?: error("aware guest expected")
        aware.ready(listOf(5180))
        env.scheduler.drain()
        assertEquals(Phase.CONNECTING, phaseOf(attempt))
        return this
    }

    private fun bindResult(socket: Socket): WifiBinding.Bound {
        val reader = SecureRecordReader(socket.getInputStream(), ByteArray(32) { 1 })
        val writer = SecureRecordWriter(socket.getOutputStream(), ByteArray(32) { 2 })
        return WifiBinding.Bound(socket, reader, writer, Bwf.Ready(1, 0, 1), DeadlineInputStream(socket) { 0 })
    }

    private fun okBinder(bound: WifiBinding.Bound) = object : WifiBinder {
        override fun host(socket: Socket, acceptedAtNs: Long, uw: ByteArray, gate: TicketGate, ticket: ByteArray, ready: Bwf.Ready): WifiBinding.HostResult =
            WifiBinding.HostResult.Ok(bound)
        override fun guest(socket: Socket, connectedAtNs: Long, uw: ByteArray, ticket: ByteArray, ready: Bwf.Ready): WifiBinding.GuestResult =
            WifiBinding.GuestResult.Ok(bound)
    }

    private fun fixedKeyBound(socket: Socket): WifiBinding.Bound {
        // One symmetric test key on both ends: this double exists to drive the state machine, not to test BWU1
        // (WifiBindingTest covers the real handshake).
        val key = ByteArray(32) { 0x5a }
        return WifiBinding.Bound(
            socket,
            SecureRecordReader(socket.getInputStream(), key).also { it.acceptWifi = true },
            SecureRecordWriter(socket.getOutputStream(), key),
            Bwf.Ready(1, 0, 1),
            DeadlineInputStream(socket) { 0 },
        )
    }

    private fun fixedBinder() = object : WifiBinder {
        override fun host(socket: Socket, acceptedAtNs: Long, uw: ByteArray, gate: TicketGate, ticket: ByteArray, ready: Bwf.Ready): WifiBinding.HostResult =
            WifiBinding.HostResult.Ok(fixedKeyBound(socket))
        override fun guest(socket: Socket, connectedAtNs: Long, uw: ByteArray, ticket: ByteArray, ready: Bwf.Ready): WifiBinding.GuestResult =
            WifiBinding.GuestResult.Ok(fixedKeyBound(socket))
    }

    @Test
    fun pairHappyPath() {
        val env = FakeEnv()
        env.binderValue = fixedBinder()
        env.ledger.reserveNdp("wifi-101", 4)
        env.ledger.reserveNdp("wifi-102", 4)
        val hostKey = uw.copyOf()
        val guestKey = uw.copyOf()
        val hostPorts = FakeWifiPorts(isLinkInitiator = false)
        val guestPorts = FakeWifiPorts(isLinkInitiator = true)
        val decision = env.decide(env.facts())
        var hostEnd: Outcome? = null
        var guestEnd: Outcome? = null
        val hostAttempt = WifiAttempt(
            AttemptRole.HOST, 101, ticket.copyOf(), hostKey, decision, env, hostPorts, false, "wifi-101",
        ) { outcome, _, _ -> hostEnd = outcome }
        val guestAttempt = WifiAttempt(
            AttemptRole.GUEST, 102, ticket.copyOf(), guestKey, decision, env, guestPorts, false, "wifi-102",
        ) { outcome, _, _ -> guestEnd = outcome }
        val hostLink = env.links[101] ?: error("host link")
        val guestLink = env.links[102] ?: error("guest link")
        FakeAwarePair(hostLink, guestLink)

        hostAttempt.start()
        guestAttempt.start()
        env.scheduler.drain()
        env.scheduler.drain()
        val hostRecord = hostPorts.sent.filterIsInstance<Bwf.Host>().single()
        assertEquals(WifiWire.BEARER_AWARE, hostRecord.bearer)

        guestLink.discover(1)
        env.scheduler.drain()
        env.scheduler.drain()
        val challengeMessage = hostLink.messages.last().second
        assertEquals(32, challengeMessage.size)
        val pick = guestPorts.sent.filterIsInstance<Bwf.Pick>().single()
        hostAttempt.onPick(true, pick.challenge)
        env.scheduler.drain()
        val armed = hostPorts.sent.filterIsInstance<Bwf.Armed>().single()
        guestAttempt.onArmed(true)
        env.scheduler.drain()

        val done = env.scheduler.runUntil(15_000) {
            hostPorts.carriers.isNotEmpty() && guestPorts.carriers.isNotEmpty()
        }
        assertTrue("attempts did not hand off: ${env.lines}", done)
        assertEquals(OutcomeKind.SUCCESS, hostEnd?.kind)
        assertEquals(OutcomeKind.SUCCESS, guestEnd?.kind)
        assertEquals(1, hostPorts.carriers.size)
        assertEquals(1, guestPorts.carriers.size)
        assertTrue(hostLink.calls.contains("handOff"))
        assertTrue(guestLink.calls.contains("handOff"))
        assertFalse(hostLink.calls.any { it.startsWith("close") })
        assertFalse(guestLink.calls.any { it.startsWith("close") })
        assertEquals(2, env.ledger.ndpReserved)

        val hostCarrier = hostPorts.carriers.single()
        val guestCarrier = guestPorts.carriers.single()
        hostCarrier.link.writer.write(SecureRecordType.BWF1, "carrier-record".toByteArray())
        hostCarrier.link.writer.flush()
        assertEquals("carrier-record", String(guestCarrier.link.reader.read().payload))

        assertTrue(hostKey.all { it == 0.toByte() })
        assertTrue(guestKey.all { it == 0.toByte() })
        assertTrue(hostPorts.issuedCopies.all { copy -> copy.all { it == 0.toByte() } })
        assertTrue(guestPorts.issuedCopies.all { copy -> copy.all { it == 0.toByte() } })

        for (event in listOf(
            "event=host", "event=knock", "event=challenge", "event=pick", "event=armed",
            "event=ndp_request", "event=ndp_up", "event=band", "event=socket", "event=bind",
            "event=ready", "event=release",
        )) {
            assertTrue("$event missing in ${env.lines}", env.lines.any { it.contains(event) })
        }
        assertTrue(env.lines.any { it.contains("release") && it.contains("outcome=success") && it.contains("setupMs=") })
        assertNoSecretShapes(env.lines)
    }

    @Test
    fun releaseOnEveryFailureClosesTheLinkOnceAndFreesTheReservation() {
        val bandLow = guestSide().toAddressing()
        bandLow.attempt.dispatch(NetworkReady(1L, PathFacts(listOf(2437), true)))
        assertEquals(OutcomeKind.NO_PENALTY, bandLow.outcome()?.kind)
        assertEquals("band_low", bandLow.outcome()?.reason)
        assertEquals(1, bandLow.link!!.closeCount)
        assertEquals(0, bandLow.env.ledger.ndpReserved)
        assertTrue(bandLow.ports.issuedCopies.all { it.all { b -> b == 0.toByte() } })
        assertEquals(7, bandLow.outcome()?.decline)
        assertTrue(bandLow.ports.sent.filterIsInstance<Bwf.Decline>().any { it.reason == 7 })

        val timedOut = guestSide().toRequesting()
        timedOut.attempt.dispatch(TimerFired(1L, TimerKind.ATTEMPT, 0))
        assertEquals("attempt_timeout", timedOut.outcome()?.reason)
        assertEquals(1, timedOut.link!!.closeCount)
        assertEquals(0, timedOut.env.ledger.ndpReserved)

        val refused = guestSide().toRequesting()
        refused.attempt.dispatch(NetworkUnavailable(1L, 300, false))
        assertEquals("ndp_refused", refused.outcome()?.reason)
        assertNull(refused.outcome()?.fault)

        val awareDown = guestSide().toRequesting()
        awareDown.attempt.dispatch(AwareDown(1L, AwareDownReason.WIFI_OFF))
        assertEquals(OutcomeKind.FAILED_LOCAL, awareDown.outcome()?.kind)
        assertEquals(1, awareDown.link!!.closeCount)
        assertEquals(0, awareDown.env.ledger.ndpReserved)
    }

    @Test
    fun onlyLocalFaultsReachTheLedger() {
        val env = FakeEnv()
        repeat(3) { index ->
            val gen = (index + 1).toLong()
            val side = guestSide(env = env, gen = gen).toRequesting() // requestInitiator succeeds
            side.attempt.dispatch(TimerFired(gen, TimerKind.ATTEMPT, 0)) // peer timeout: no local fault
        }
        assertEquals(0L, env.ledger.pauseUntilMs)

        repeat(3) { index ->
            val gen = (index + 10).toLong()
            env.ledger.reserveNdp("wifi-$gen", 4)
            val ports = FakeWifiPorts(isLinkInitiator = true)
            val decision = env.decide(env.facts())
            val attempt = WifiAttempt(AttemptRole.GUEST, gen, ticket.copyOf(), uw.copyOf(), decision, env, ports, false, "wifi-$gen") { _, _, _ -> }
            val link = env.links[gen] ?: error("link")
            attempt.start()
            env.scheduler.drain()
            env.scheduler.drain()
            link.requestResult = false
            link.discover(1)
            env.scheduler.drain()
            attempt.dispatch(ChallengeIn(gen, 1, true, ByteArray(16) { 7 }))
            attempt.dispatch(ArmedIn(gen, true))
        }
        assertTrue("pauseUntilMs=${env.ledger.pauseUntilMs}", env.ledger.pauseUntilMs > 0L)
    }

    @Test
    fun threePeerRefusalsOnThreeHopsLeaveAFourthHopEligibleAndTheCapUnchanged() {
        val env = FakeEnv()
        for (index in 1..3) {
            val gen = index.toLong()
            env.ledger.reserveNdp("wifi-$gen", 4)
            val ports = FakeWifiPorts(isLinkInitiator = true)
            val decision = env.decide(env.facts())
            var outcome: Outcome? = null
            val attempt = WifiAttempt(AttemptRole.GUEST, gen, ticket.copyOf(), uw.copyOf(), decision, env, ports, false, "wifi-$gen") { o, _, _ -> outcome = o }
            val link = env.links[gen] ?: error("link")
            attempt.start()
            env.scheduler.drain()
            env.scheduler.drain()
            link.discover(1)
            env.scheduler.drain()
            attempt.dispatch(ChallengeIn(gen, 1, true, ByteArray(16) { 7 }))
            attempt.dispatch(ArmedIn(gen, true))
            attempt.dispatch(NetworkUnavailable(gen, 300, false))
            assertEquals(OutcomeKind.FAILED_PEER, outcome?.kind)
        }
        assertEquals(0L, env.ledger.pauseUntilMs)
        assertNull(env.ledger.observedMaxNdp)
        val planner = WifiHopPlanner { env.scheduler.nowMs() }
        assertEquals(WifiHopPlanner.Verdict.Go, planner.canStart(0))
    }

    @Test
    fun staleCallbacksAfterTheAttemptEndedAreIgnored() {
        val side = guestSide()
        side.attempt.start()
        side.env.scheduler.drain()
        side.env.scheduler.drain()
        side.attempt.dispatch(TimerFired(1L, TimerKind.ATTEMPT, 0))
        val sentAfterEnd = side.ports.sent.size
        side.link!!.deliver(1, ByteArray(32))
        side.link!!.ready()
        side.link!!.lost()
        side.env.scheduler.drain()
        assertEquals(sentAfterEnd, side.ports.sent.size)
        assertEquals(1, side.link!!.closeCount)
    }

    @Test
    fun aSecondAttemptIsNotAffectedByTheFirstOnesCallbacks() {
        val env = FakeEnv()
        val decision = env.decide(env.facts())
        val ports1 = FakeWifiPorts(isLinkInitiator = true)
        val attempt1 = WifiAttempt(AttemptRole.GUEST, 1L, ticket.copyOf(), uw.copyOf(), decision, env, ports1, false, null) { _, _, _ -> }
        val link1 = env.links[1L] ?: error("link 1")
        attempt1.start()
        env.scheduler.drain()
        env.scheduler.drain()
        attempt1.dispatch(TimerFired(1L, TimerKind.ATTEMPT, 0))
        val ports2 = FakeWifiPorts(isLinkInitiator = true)
        val attempt2 = WifiAttempt(AttemptRole.GUEST, 2L, ticket.copyOf(), uw.copyOf(), decision, env, ports2, false, null) { _, _, _ -> }
        attempt2.start()
        env.scheduler.drain()
        env.scheduler.drain()
        val before = phaseOf(attempt2)
        link1.deliver(1, ByteArray(32))
        link1.ready()
        link1.lost()
        env.scheduler.drain()
        assertEquals(before, phaseOf(attempt2))
        assertEquals(0, ports2.sent.size)
    }

    @Test
    fun reentrantEventsAreQueued() {
        val side = guestSide()
        side.link!!.synchronousSession = true
        side.attempt.start()
        side.env.scheduler.drain()
        assertEquals(Phase.DISCOVERING, phaseOf(side.attempt))
        assertTrue(side.ports.sent.isEmpty())
        assertEquals(0, side.link!!.closeCount)
    }

    @Test
    fun hostPortIsReservedBeforeTheRequestAndUsedForTheListener() {
        val env = FakeEnv()
        val ports = FakeWifiPorts(isLinkInitiator = false)
        val decision = env.decide(env.facts())
        val attempt = WifiAttempt(AttemptRole.HOST, 5L, ticket.copyOf(), uw.copyOf(), decision, env, ports, false, null) { _, _, _ -> }
        val link = env.links[5L] ?: error("link")
        attempt.start()
        env.scheduler.drain()
        env.scheduler.drain()
        link.deliver(7, WifiKeys.knock(uw, ticket, ByteArray(16) { 3 }))
        env.scheduler.drain()
        val challenge = link.messages.last().second.copyOfRange(0, 16)
        attempt.onPick(true, challenge)
        env.scheduler.drain()
        link.ready(listOf(5180), portKnown = false)
        env.scheduler.drain()
        val respondPort = link.lastResponderPort
        assertTrue("port=$respondPort", respondPort in 49_152..65_535)
        assertEquals(respondPort, link.lastListenPort)
    }

    @Test
    fun bindThreadUsesItsOwnKeyCopy() {
        val captured = ArrayList<ByteArray>()
        val blocker = object : WifiBinder {
            override fun host(socket: Socket, acceptedAtNs: Long, uw: ByteArray, gate: TicketGate, ticket: ByteArray, ready: Bwf.Ready): WifiBinding.HostResult =
                throw IllegalStateException()
            override fun guest(socket: Socket, connectedAtNs: Long, uw: ByteArray, ticket: ByteArray, ready: Bwf.Ready): WifiBinding.GuestResult {
                captured += uw
                Thread.sleep(300)
                return WifiBinding.GuestResult.Failed("blocked")
            }
        }
        val side = guestSide(binder = blocker)
        side.toConnecting()
        val (server, client) = socketPair()
        side.attempt.onConnected(1L, client, System.nanoTime())
        side.attempt.command(Cmd.HANGUP)
        side.env.scheduler.drain()
        Thread.sleep(500)
        side.env.scheduler.drain()
        assertTrue(captured.isNotEmpty())
        assertTrue(captured.all { it.all { b -> b == 0.toByte() } })
        assertEquals(1, side.link!!.closeCount)
        server.close()
    }

    @Test
    fun handOffRefusedClosesEverything() {
        val (server, client) = socketPair()
        val bound = bindResult(client)
        val side = guestSide(binder = okBinder(bound))
        side.ports.offerResult = -1
        side.toConnecting()
        side.attempt.onConnected(1L, client, System.nanoTime())
        side.env.scheduler.runUntil(5_000) { side.outcome() != null }
        assertEquals(OutcomeKind.CANCELLED, side.outcome()?.kind)
        assertEquals("handoff_refused", side.outcome()?.reason)
        assertEquals(1, side.link!!.closeCount)
        assertTrue(client.isClosed)
        assertEquals(0, side.env.ledger.ndpReserved)
        server.close()
    }

    @Test
    fun wifiTurnedOffRightAfterTheBindResultIsPostedLeavesBluetoothRunning() {
        val (server, client) = socketPair()
        val bound = bindResult(client)
        val side = guestSide(binder = okBinder(bound))
        side.toConnecting()
        side.attempt.onConnected(1L, client, System.nanoTime())
        // The bind worker posts its READY result; it has not run yet when Aware goes down.
        val deadline = System.currentTimeMillis() + 5_000
        while (side.env.scheduler.dueCount() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(2)
        assertTrue("bind result never posted", side.env.scheduler.dueCount() > 0)
        // Wi-Fi off: the platform side closes the attempt's sockets at once and posts the event behind the result.
        client.close()
        side.link!!.awareDown(AwareDownReason.WIFI_OFF)
        side.env.scheduler.drain() // an exception here would end the process on a phone (and the Bluetooth call)
        val outcome = side.outcome()
        assertNotNull(outcome)
        assertTrue(outcome!!.kind != OutcomeKind.SUCCESS)
        assertTrue(side.ports.carriers.isEmpty())
        assertEquals(1, side.link!!.closeCount)
        assertEquals(0, side.env.ledger.ndpReserved)
        assertTrue(side.internalErrors.isEmpty())
        server.close()
    }

    @Test
    fun anExceptionWhileOfferingTheCarrierEndsTheAttemptAndReleasesEverythingOnce() {
        val (server, client) = socketPair()
        val bound = bindResult(client)
        val side = guestSide(binder = okBinder(bound))
        side.ports.offerError = IllegalStateException("carrier set at /data/user/0 refused 10.1.2.3")
        side.toConnecting()
        side.attempt.onConnected(1L, client, System.nanoTime())
        side.env.scheduler.runUntil(5_000) { side.outcome() != null }
        assertEquals(OutcomeKind.CANCELLED, side.outcome()?.kind)
        assertEquals("internal_error", side.outcome()?.reason)
        assertEquals(listOf("IllegalStateException"), side.internalErrors)
        assertEquals(1, side.link!!.closeCount)
        assertEquals(0, side.env.ledger.ndpReserved)
        assertTrue(client.isClosed)
        assertEquals(1, side.ports.sent.filterIsInstance<Bwf.Decline>().count { it.reason == 1 })
        // Later events are ignored and nothing is released twice.
        side.attempt.dispatch(TimerFired(1L, TimerKind.ATTEMPT, 0))
        side.env.scheduler.drain()
        assertEquals(1, side.link!!.closeCount)
        // Only the class name is logged, never the message.
        assertTrue(side.env.lines.any { it.contains("internal_error") && it.contains("IllegalStateException") })
        assertTrue(side.env.lines.none { it.contains("/data/") || it.contains("10.1.2.3") })
        server.close()
    }

    @Test
    fun aLateSocketAfterTheEndIsClosedNotStored() {
        val side = guestSide().toRequesting()
        side.attempt.dispatch(TimerFired(1L, TimerKind.ATTEMPT, 0))
        val (server, client) = socketPair()
        side.attempt.onSocketAccepted(1L, client, 1, System.nanoTime())
        side.env.scheduler.drain()
        assertTrue(client.isClosed)
        server.close()
    }

    @Test
    fun knockMessagesAreVerifiedWithTheRealKey() {
        val env = FakeEnv()
        val ports = FakeWifiPorts(isLinkInitiator = false)
        val decision = env.decide(env.facts())
        val attempt = WifiAttempt(AttemptRole.HOST, 9L, ticket.copyOf(), uw.copyOf(), decision, env, ports, false, null) { _, _, _ -> }
        val link = env.links[9L] ?: error("link")
        attempt.start()
        env.scheduler.drain()
        env.scheduler.drain()
        val g = ByteArray(16) { (it + 31).toByte() }
        link.deliver(5, WifiKeys.knock(uw, ticket, g))
        env.scheduler.drain()
        val challenge = link.messages.last().second
        assertEquals(32, challenge.size)
        assertTrue(WifiKeys.challengeMatches(uw, ticket, g, challenge))

        link.deliver(6, ByteArray(32) { 0x55 })
        link.deliver(6, ByteArray(16) { 0x66 })
        env.scheduler.drain()
        assertTrue(link.messages.none { it.first == 6 })
    }

    @Test
    fun aReplayedGenuineChallengeOnAnotherHandleFailsTheMac() {
        val side = guestSide()
        side.attempt.start()
        side.env.scheduler.drain()
        side.env.scheduler.drain()
        side.link!!.discover(1)
        side.link!!.discover(2)
        side.env.scheduler.drain()
        val knocks = side.link!!.messages.filter { it.second.size == 32 }.associateBy { it.first }
        val g1 = knocks.getValue(1).second.copyOfRange(0, 16)
        val g2 = knocks.getValue(2).second.copyOfRange(0, 16)
        val c1 = ByteArray(16) { 0x42 }
        side.link!!.deliver(2, WifiKeys.awareChallenge(uw, ticket, g1, c1))
        side.env.scheduler.drain()
        assertTrue(side.ports.sent.filterIsInstance<Bwf.Pick>().isEmpty())
        assertTrue(side.env.lines.any { it.contains("challenge_bad") })
        side.link!!.deliver(1, WifiKeys.awareChallenge(uw, ticket, g1, c1))
        side.env.scheduler.drain()
        assertEquals(1, side.ports.sent.filterIsInstance<Bwf.Pick>().size)
        assertFalse(g1.contentEquals(g2))
    }

    @Test
    fun carrierCloseIsIdempotentAndReleasesEverythingOnce() {
        val (server, client) = socketPair()
        val bound = bindResult(client)
        val side = guestSide(binder = okBinder(bound))
        side.toConnecting()
        side.attempt.onConnected(1L, client, System.nanoTime())
        side.env.scheduler.runUntil(5_000) { side.ports.carriers.isNotEmpty() }
        val carrier = side.ports.carriers.single()
        assertEquals(1, side.env.ledger.ndpReserved)
        carrier.close()
        carrier.close()
        assertEquals(1, side.link!!.closeCount)
        assertEquals(0, side.env.ledger.ndpReserved)
        assertTrue(client.isClosed)
        server.close()
    }

    @Test
    fun aLossBeforeTheHandlerIsSetFiresWhenItIsSet() {
        val (server, client) = socketPair()
        val reader = SecureRecordReader(client.getInputStream(), ByteArray(32) { 1 })
        val writer = SecureRecordWriter(client.getOutputStream(), ByteArray(32) { 2 })
        val carrier = WifiSocketCarrier(
            CarrierKind.WIFI_AWARE, client,
            SecureBluetoothSession(NodeId.fromBytes(ByteArray(16)), NodeCapabilities(0, 0, 0, 0, 0), reader, writer),
            { }, WifiLedger { 0L }, null, ticket.copyOf(), Bwf.Ready(1, 0, 1), true, 5180,
        )
        carrier.platformLoss(AwareDownReason.UNAVAILABLE)
        var seen: AwareDownReason? = null
        carrier.lossHandler = { seen = it }
        assertEquals(AwareDownReason.UNAVAILABLE, seen)
        carrier.close()
        server.close()
    }

    @Test
    fun aDirectHostSendsHostThenCredsInThatOrder() {
        val env = FakeEnv()
        env.supported += WifiBearer.DIRECT
        lateinit var driver: FakeBearerDriver
        env.driverFactory = { gen, events ->
            FakeBearerDriver(generation = gen, events = events, scheduler = env.scheduler).also { driver = it }
        }
        val ports = FakeWifiPorts()
        val decision = env.decide(env.facts())
        val attempt = WifiAttempt(AttemptRole.HOST, 21L, ticket.copyOf(), uw.copyOf(), decision, env, ports, false, null, WifiBearer.DIRECT) { _, _, _ -> }
        attempt.start()
        env.scheduler.drain()
        assertTrue(driver.calls.contains("startHost"))
        driver.hostReady(5180, 4545)
        env.scheduler.drain()
        val records = ports.sent.filter { it is Bwf.Host || it is Bwf.Creds }
        assertTrue(records[0] is Bwf.Host)
        assertTrue(records[1] is Bwf.Creds)
        assertEquals(4545, (records[1] as Bwf.Creds).port)
        assertEquals(5180, (records[0] as Bwf.Host).freqMhz)
    }

    @Test
    fun aDirectGuestJoinsOnHostAndConnectsOnlyWithCreds() {
        val env = FakeEnv()
        env.supported += WifiBearer.DIRECT
        lateinit var driver: FakeBearerDriver
        env.driverFactory = { gen, events ->
            FakeBearerDriver(generation = gen, events = events, scheduler = env.scheduler).also { driver = it }
        }
        val side = guestSide(env = env, gen = 22L, bearer = WifiBearer.DIRECT, reserve = false)
        side.attempt.start()
        side.env.scheduler.drain()
        assertTrue(driver.calls.any { it.startsWith("startGuest") })
        driver.joined(5180)
        side.env.scheduler.drain()
        assertFalse(driver.calls.any { it.startsWith("connect") })
        side.attempt.onCreds(true, 4545, byteArrayOf(192.toByte(), 168.toByte(), 49, 1))
        side.env.scheduler.drain()
        assertTrue(driver.calls.any { it == "connect:4545" })
    }

    @Test
    fun aDirectFailureClosesTheDriverOnceAndRecordsNoLedgerFault() {
        val env = FakeEnv()
        env.supported += WifiBearer.DIRECT
        lateinit var driver: FakeBearerDriver
        env.driverFactory = { gen, events ->
            FakeBearerDriver(generation = gen, events = events, scheduler = env.scheduler)
                .also { it.prepareResult = BearerFailure("busy", 2, false); driver = it }
        }
        val ports = FakeWifiPorts()
        val decision = env.decide(env.facts())
        var outcome: Outcome? = null
        val attempt = WifiAttempt(AttemptRole.HOST, 23L, ticket.copyOf(), uw.copyOf(), decision, env, ports, false, null, WifiBearer.DIRECT) { o, _, _ -> outcome = o }
        attempt.start()
        env.scheduler.drain()
        assertEquals(1, driver.closeCount)
        assertEquals(OutcomeKind.NO_PENALTY, outcome?.kind)
        assertEquals("bearer_busy", outcome?.reason)
        assertTrue(outcome?.busy == true)
        assertNull(outcome?.fault)
        assertEquals(0L, env.ledger.pauseUntilMs)
    }

    @Test
    fun aDirectHandOffBuildsAWifiDirectCarrierWithoutAnNdpReservation() {
        val env = FakeEnv()
        env.supported += WifiBearer.DIRECT
        lateinit var driver: FakeBearerDriver
        env.driverFactory = { gen, events ->
            FakeBearerDriver(generation = gen, events = events, scheduler = env.scheduler).also { driver = it }
        }
        val (server, client) = socketPair()
        env.binderValue = okBinder(bindResult(client))
        val ports = FakeWifiPorts()
        val decision = env.decide(env.facts())
        var outcome: Outcome? = null
        val attempt = WifiAttempt(AttemptRole.HOST, 24L, ticket.copyOf(), uw.copyOf(), decision, env, ports, false, null, WifiBearer.DIRECT) { o, _, _ -> outcome = o }
        attempt.start()
        env.scheduler.drain()
        driver.hostReady(5180, 4545)
        env.scheduler.drain()
        attempt.onSocketAccepted(24L, client, 1, System.nanoTime())
        env.scheduler.runUntil(5_000) { outcome != null }
        assertEquals(OutcomeKind.SUCCESS, outcome?.kind)
        val carrier = ports.carriers.single()
        assertEquals(CarrierKind.WIFI_DIRECT, carrier.kind)
        assertTrue(carrier.bandVerified)
        assertEquals(0, env.ledger.ndpReserved)
        server.close()
    }
    // ----- PROTOCOL `port` (W5, the rule since 2026-11-10): the Aware host's port is derived from UW and the ticket -----

    private fun derivedPortEnv(on: Boolean): FakeEnv = FakeEnv().also { it.factsValue = it.factsValue.copy(derivedPort = on) }

    private class PairRun(val env: FakeEnv, val host: FakeAware, val guest: FakeAware, val hostEnd: Outcome?, val guestEnd: Outcome?, val hostCarriers: Int, val guestCarriers: Int)

    /** One end-to-end Aware attempt pair in which the guest reaches the host only on the port the host listens on. */
    private fun derivedPortPair(env: FakeEnv, guestPortKnown: Boolean): PairRun {
        env.binderValue = fixedBinder()
        env.ledger.reserveNdp("wifi-201", 4)
        env.ledger.reserveNdp("wifi-202", 4)
        val hostPorts = FakeWifiPorts(isLinkInitiator = false)
        val guestPorts = FakeWifiPorts(isLinkInitiator = true)
        val decision = env.decide(env.facts())
        var hostEnd: Outcome? = null
        var guestEnd: Outcome? = null
        val hostAttempt = WifiAttempt(AttemptRole.HOST, 201, ticket.copyOf(), uw.copyOf(), decision, env, hostPorts, false, "wifi-201") { o, _, _ -> hostEnd = o }
        val guestAttempt = WifiAttempt(AttemptRole.GUEST, 202, ticket.copyOf(), uw.copyOf(), decision, env, guestPorts, false, "wifi-202") { o, _, _ -> guestEnd = o }
        val hostLink = env.links[201] ?: error("host link")
        val guestLink = env.links[202] ?: error("guest link")
        FakeAwarePair(hostLink, guestLink, routeByPort = true, guestPortKnown = guestPortKnown)
        hostAttempt.start()
        guestAttempt.start()
        env.scheduler.drain()
        env.scheduler.drain()
        guestLink.discover(1)
        env.scheduler.drain()
        env.scheduler.drain()
        hostAttempt.onPick(true, guestPorts.sent.filterIsInstance<Bwf.Pick>().single().challenge)
        env.scheduler.drain()
        guestAttempt.onArmed(true)
        env.scheduler.drain()
        env.scheduler.runUntil(15_000) { guestEnd != null && (hostPorts.carriers.isNotEmpty() || guestEnd?.kind != OutcomeKind.SUCCESS) }
        return PairRun(env, hostLink, guestLink, hostEnd, guestEnd, hostPorts.carriers.size, guestPorts.carriers.size)
    }

    @Test
    fun aGuestWhosePlatformReportsNoPortConnectsToTheDerivedPortAndHandsOff() {
        val run = derivedPortPair(FakeEnv(), guestPortKnown = false)
        assertEquals("pair did not hand off: ${run.env.lines}", OutcomeKind.SUCCESS, run.guestEnd?.kind)
        assertEquals(OutcomeKind.SUCCESS, run.hostEnd?.kind)
        assertEquals(1, run.hostCarriers)
        assertEquals(1, run.guestCarriers)
        assertEquals(WifiKeys.port(uw, ticket), run.host.lastResponderPort)
        assertEquals(WifiKeys.port(uw, ticket), run.host.lastListenPort)
        assertEquals(WifiKeys.port(uw, ticket), run.guest.lastConnectPort)
        assertTrue(run.env.lines.any { it.contains("event=port") && it.contains("role=host") && it.contains("source=derived") })
        assertTrue(run.env.lines.any { it.contains("event=port") && it.contains("role=guest") && it.contains("source=derived") })
        assertFalse(run.env.lines.any { it.contains("event=port") && it.contains("${WifiKeys.port(uw, ticket)}") })
    }

    @Test
    fun aPlatformPortThatIsTheDerivedOneIsUsedAsGivenAndLoggedAsAMatch() {
        val run = derivedPortPair(FakeEnv(), guestPortKnown = true)
        assertEquals(OutcomeKind.SUCCESS, run.guestEnd?.kind)
        assertEquals(1, run.guestCarriers)
        assertEquals(WifiKeys.port(uw, ticket), run.guest.lastConnectPort)
        assertTrue(run.env.lines.any { it.contains("event=port") && it.contains("source=platform") && it.contains("match=true") })
    }

    @Test
    fun underTheControlArmTheHostDrawsARandomPortAndAGuestWithoutAPlatformPortEndsWithNoPort() {
        val run = derivedPortPair(derivedPortEnv(false), guestPortKnown = false)
        assertEquals("no_port", run.guestEnd?.reason)
        assertEquals(0, run.guestCarriers)
        assertFalse(run.guest.calls.contains("connect"))
        assertTrue(run.host.lastResponderPort in AwarePorts.MIN..AwarePorts.MAX)
        assertTrue(run.env.lines.any { it.contains("event=port") && it.contains("role=host") && it.contains("source=random") })
    }

    @Test
    fun aGuestWhoseDataPathReportsNoPortEndsWithNoPortUnderTheControlArm() {
        val side = guestSide(derivedPortEnv(false)).toAddressing()
        side.link!!.ready(listOf(5745), portKnown = false)
        side.env.scheduler.drain()
        assertEquals("no_port", side.outcome()?.reason)
        assertFalse(side.link.calls.contains("connect"))
        assertTrue(side.ports.sent.filterIsInstance<Bwf.Decline>().any { it.reason == 6 })
        assertTrue(side.env.lines.any { it.contains("event=port") && it.contains("source=none") })
    }

    @Test
    fun aGuestWhoseDataPathReportsNoPortConnectsToTheDerivedPortByDefault() {
        val side = guestSide(FakeEnv()).toAddressing()
        side.link!!.ready(listOf(5745), portKnown = false)
        side.env.scheduler.drain()
        assertEquals(Phase.CONNECTING, phaseOf(side.attempt))
        assertTrue(side.link.calls.contains("connect"))
        assertEquals(WifiKeys.port(uw, ticket), side.link.lastConnectPort)
        assertTrue(side.env.lines.any { it.contains("event=port") && it.contains("source=derived") })
    }

    @Test
    fun aPortTheDataPathDoesReportWinsOverTheDerivedOne() {
        val side = guestSide(FakeEnv()).toAddressing()
        assertNotEquals(4242, WifiKeys.port(uw, ticket))
        side.link!!.ready(listOf(5745), portKnown = true)
        side.env.scheduler.drain()
        assertEquals(4242, side.link.lastConnectPort)
        assertEquals(1, side.link.calls.count { it == "connect" })
        assertTrue(side.env.lines.any { it.contains("event=port") && it.contains("source=platform") && it.contains("match=false") })
    }

    @Test
    fun theDerivedPortNeverRescuesADataPathBelow5GHz() {
        val side = guestSide(FakeEnv()).toAddressing()
        side.link!!.ready(listOf(2437), portKnown = false)
        side.env.scheduler.drain()
        assertEquals("band_low", side.outcome()?.reason)
        assertFalse(side.link.calls.contains("connect"))
        assertTrue(side.ports.sent.filterIsInstance<Bwf.Decline>().any { it.reason == 7 })
    }

    // ----- AP10: a 2.4 GHz-only data path when the phone carries no other Bluetooth media -----

    private fun alone24Env(on: Boolean = true): FakeEnv =
        FakeEnv().also { it.factsValue = it.factsValue.copy(aware24GhzAlone = on) }

    private fun GuestSide.bandLine(): String = env.lines.lastOrNull { it.contains("event=band") && it.contains("chMhz=") }.orEmpty()

    @Test
    fun aTwoPointFourOnlyDataPathConnectsWhenThePhoneCarriesNoOtherBluetoothMedia() {
        val side = guestSide(alone24Env())
        side.ports.otherBluetoothMediaValue = false
        side.toAddressing()
        side.link!!.ready(listOf(2412))
        side.env.scheduler.drain()
        assertEquals(Phase.CONNECTING, phaseOf(side.attempt))
        assertTrue(side.link.calls.contains("connect"))
        assertTrue(side.ports.sent.filterIsInstance<Bwf.Decline>().isEmpty())
        val line = side.bandLine()
        assertTrue(line, line.contains("chMhz=2412") && line.contains("alone24=true") && line.contains("otherBtMedia=false"))
        assertTrue(line, line.contains("verdict=ok") && line.contains("band=2g4_alone"))
    }

    @Test
    fun aTwoPointFourOnlyDataPathIsRefusedWhileThePhoneCarriesOtherBluetoothMedia() {
        val side = guestSide(alone24Env())
        side.ports.otherBluetoothMediaValue = true
        side.toAddressing()
        side.link!!.ready(listOf(2412))
        side.env.scheduler.drain()
        assertEquals("band_low", side.outcome()?.reason)
        assertFalse(side.link.calls.contains("connect"))
        assertTrue(side.ports.sent.filterIsInstance<Bwf.Decline>().any { it.reason == 7 })
        val line = side.bandLine()
        assertTrue(line, line.contains("otherBtMedia=true") && line.contains("band=refused_24_bt_media"))
    }

    @Test
    fun withTheSwitchOffATwoPointFourOnlyDataPathIsRefusedEvenOnAPhoneWithNoOtherBluetoothMedia() {
        val side = guestSide(alone24Env(on = false))
        side.ports.otherBluetoothMediaValue = false
        side.toAddressing()
        side.link!!.ready(listOf(2412))
        side.env.scheduler.drain()
        assertEquals("band_low", side.outcome()?.reason)
        assertFalse(side.link.calls.contains("connect"))
        assertTrue(side.ports.sent.filterIsInstance<Bwf.Decline>().any { it.reason == 7 })
        val line = side.bandLine()
        assertTrue(line, line.contains("alone24=false") && line.contains("band=refused_low"))
    }

    @Test
    fun theOtherBluetoothMediaOfThePhoneIsReadWhenTheDataPathComesUpNotWhenTheAttemptStarts() {
        val joined = guestSide(alone24Env())
        joined.ports.otherBluetoothMediaValue = false
        joined.toAddressing()
        joined.ports.otherBluetoothMediaValue = true // a Bluetooth viewer joined after the attempt started
        joined.link!!.ready(listOf(2412))
        joined.env.scheduler.drain()
        assertEquals("band_low", joined.outcome()?.reason)
        assertFalse(joined.link.calls.contains("connect"))

        val left = guestSide(alone24Env())
        left.ports.otherBluetoothMediaValue = true
        left.toAddressing()
        left.ports.otherBluetoothMediaValue = false // the only other Bluetooth media ended meanwhile
        left.link!!.ready(listOf(2412))
        left.env.scheduler.drain()
        assertEquals(Phase.CONNECTING, phaseOf(left.attempt))
    }

    @Test
    fun aFiveGhzDataPathConnectsWithAndWithoutOtherBluetoothMedia() {
        for (other in listOf(true, false)) {
            val side = guestSide(alone24Env())
            side.ports.otherBluetoothMediaValue = other
            side.toAddressing()
            side.link!!.ready(listOf(5745, 2437))
            side.env.scheduler.drain()
            assertEquals("other=$other", Phase.CONNECTING, phaseOf(side.attempt))
            assertTrue(side.bandLine(), side.bandLine().contains("band=5g"))
        }
    }

    @Test
    fun aPathWithAnotherLowChannelBesideFiveGhzIsRefusedEvenOnAPhoneWithNoOtherBluetoothMedia() {
        val side = guestSide(alone24Env())
        side.ports.otherBluetoothMediaValue = false
        side.toAddressing()
        side.link!!.ready(listOf(2412, 5745))
        side.env.scheduler.drain()
        assertEquals("band_low", side.outcome()?.reason)
    }

    @Test
    fun theHostListensOnTheDerivedPortByDefaultAndOnARandomOneUnderTheControlArm() {
        for (arm in listOf(true, false)) {
            val env = derivedPortEnv(arm)
            val ports = FakeWifiPorts(isLinkInitiator = false)
            val decision = env.decide(env.facts())
            val attempt = WifiAttempt(AttemptRole.HOST, 5L, ticket.copyOf(), uw.copyOf(), decision, env, ports, false, null) { _, _, _ -> }
            val link = env.links[5L] ?: error("link")
            attempt.start()
            env.scheduler.drain()
            env.scheduler.drain()
            link.deliver(7, WifiKeys.knock(uw, ticket, ByteArray(16) { 3 }))
            env.scheduler.drain()
            attempt.onPick(true, link.messages.last().second.copyOfRange(0, 16))
            env.scheduler.drain()
            link.ready(listOf(5180), portKnown = false)
            env.scheduler.drain()
            assertEquals(link.lastResponderPort, link.lastListenPort)
            if (arm) assertEquals(WifiKeys.port(uw, ticket), link.lastResponderPort)
            else assertTrue(link.lastResponderPort in AwarePorts.MIN..AwarePorts.MAX)
            val source = if (arm) "source=derived" else "source=random"
            assertTrue(env.lines.any { it.contains("event=port") && it.contains("role=host") && it.contains(source) })
        }
    }
}
