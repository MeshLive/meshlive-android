package com.example.bluetoothtest

import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Dual listening logic: the caller's plan, the CoC-then-GATT sequence inside one attempt, the second
 * listener's lifetime and resource bounds, the transport half of the identity binding, and the real BMH2
 * handshake on both transports. The wire hint has its own test ([DualListeningWireTest]).
 */
class DualListeningTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private val coc = BluetoothTransportOption.BLE_L2CAP
    private val gatt = BluetoothTransportOption.BLE_GATT
    private val classic = BluetoothTransportOption.CLASSIC_RFCOMM
    private val callFlags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE
    private val prefix = byteArrayOf(1, 2, 3, 4, 5, 6)

    private fun offer(
        flags: Int = callFlags,
        summary: Int = NodeCapabilities.CODEC_VP9,
        routeCost: Int = 0,
        node: ByteArray = prefix,
        version: Int = DiscoveryAnnouncement.FIXED_PREFIX_VERSION,
    ) = DiscoveryAnnouncement(node, flags, summary, 0, routeCost, 0, version = version)

    private val plainCoc = offer()
    private val hintedCoc = DualListeningWire.offerFor(offer(), enabled = true, secondaryReady = true)
    private val gattOffer = offer(flags = callFlags or DiscoveryAnnouncement.FLAG_GATT_TRANSPORT)

    // ---- the caller's plan -------------------------------------------------------------------------------

    @Test
    fun theSwitchOffPlanIsAlwaysTheEndpointsOwnTransport() {
        for (endpoint in BluetoothTransportOption.entries) for (o in listOf(plainCoc, hintedCoc, gattOffer)) {
            assertEquals(listOf(endpoint), DualCallerPlan.plan(endpoint, o, enabled = false))
            assertFalse(DualCallerPlan.listsHintedOffer(endpoint, o, enabled = false))
        }
    }

    @Test
    fun aCoCEndpointTriesCoCThenGattOnlyForAHintedOffer() {
        assertEquals(listOf(coc, gatt), DualCallerPlan.plan(coc, hintedCoc, true))
        assertEquals(listOf(coc), DualCallerPlan.plan(coc, plainCoc, true))
        // A GATT offer seen by a CoC endpoint keeps the safety net's endpoint rebuild: no dual attempt here.
        assertEquals(listOf(coc), DualCallerPlan.plan(coc, gattOffer, true))
        // A forged bit on a shape that cannot carry it is not a second transport.
        val forgedGatt = gattOffer.copy(capabilitySummary = gattOffer.capabilitySummary or DualListeningWire.SUMMARY_BIT)
        assertEquals(listOf(coc), DualCallerPlan.plan(coc, forgedGatt, true))
        val relayed = hintedCoc.copy(routeCost = 1)
        assertEquals(listOf(coc), DualCallerPlan.plan(coc, relayed, true))
    }

    @Test
    fun aGattEndpointNeverAddsAnAttemptAndListsOnlyAHintedCoCOffer() {
        assertEquals(listOf(gatt), DualCallerPlan.plan(gatt, hintedCoc, true))
        assertEquals(listOf(gatt), DualCallerPlan.plan(gatt, gattOffer, true))
        assertTrue(DualCallerPlan.listsHintedOffer(gatt, hintedCoc, true))
        assertFalse(DualCallerPlan.listsHintedOffer(gatt, plainCoc, true))
        assertFalse(DualCallerPlan.listsHintedOffer(gatt, gattOffer, true))
        assertFalse(DualCallerPlan.listsHintedOffer(coc, hintedCoc, true))
        assertFalse(DualCallerPlan.listsHintedOffer(gatt, hintedCoc.copy(routeCost = 1), true))
    }

    @Test
    fun classicIsNeverDual() {
        assertEquals(listOf(classic), DualCallerPlan.plan(classic, hintedCoc, true))
        assertEquals(listOf(classic), DualCallerPlan.plan(classic, offer(flags = callFlags or DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT), true))
        assertFalse(DualCallerPlan.listsHintedOffer(classic, hintedCoc, true))
    }

    @Test
    fun noPlanHasMoreThanTwoAttemptsOrAnyRepeat() {
        for (endpoint in BluetoothTransportOption.entries) for (o in listOf(plainCoc, hintedCoc, gattOffer)) {
            val plan = DualCallerPlan.plan(endpoint, o, true)
            assertTrue(plan.size in 1..DualAttemptRunner.MAX_ATTEMPTS)
            assertEquals(plan.size, plan.toSet().size)
            // The first attempt is always the endpoint's own transport: the preferred one never changes.
            assertEquals(endpoint, plan.first())
        }
    }

    // ---- the sequence inside one attempt ----------------------------------------------------------------

    private class Link(val transport: BluetoothTransportOption)

    private fun runner(cancelled: () -> Boolean = { false }, lines: MutableList<String> = mutableListOf(), now: () -> Long = { 0L }) =
        DualAttemptRunner<Link>(now, cancelled) { lines += it }

    @Test
    fun aFirstAttemptSuccessNeverOpensTheSecondTransport() {
        val events = mutableListOf<String>()
        val result = runner().run(listOf(coc, gatt)) { transport, index ->
            events += "open ${transport.name}#$index"
            Link(transport)
        }
        assertEquals(listOf("open BLE_L2CAP#0"), events)
        assertEquals(coc, result.transport)
        assertFalse(result.fallback)
        assertNull(result.firstError)
    }

    @Test
    fun aCoCFailureFallsBackToGattInTheSameRunWithNothingBetweenTheAttempts() {
        val events = mutableListOf<String>()
        val lines = mutableListOf<String>()
        var clock = 0L
        val result = runner(lines = lines, now = { clock }).run(listOf(coc, gatt)) { transport, index ->
            events += "open ${transport.name}#$index"
            if (transport == coc) {
                clock += 4_321L
                throw IOException("PSM read failed")
            }
            Link(transport)
        }
        // Exactly the two opens, in order; the runner has no rescan, discovery or selection step to call.
        assertEquals(listOf("open BLE_L2CAP#0", "open BLE_GATT#1"), events)
        assertEquals(gatt, result.transport)
        assertTrue(result.fallback)
        assertEquals(coc, result.fellBackFrom)
        assertEquals("PSM read failed", result.firstError?.message)
        assertEquals(4_321L, result.firstPhaseMs)
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("dual_listening: event=attempt_failed | transport=BLE_L2CAP | attempt=1"))
    }

    @Test
    fun bothFailuresEndTheCallOnceWithBothCauses() {
        val calls = AtomicInteger()
        val thrown = assertThrows(DualSetupFailedException::class.java) {
            runner().run(listOf(coc, gatt)) { transport, _ ->
                calls.incrementAndGet()
                throw IOException(if (transport == coc) "coc down" else "gatt down")
            }
        }
        assertEquals(2, calls.get())
        assertEquals(listOf(coc, gatt), thrown.causes.map { it.first })
        assertTrue(thrown.message!!.contains("coc down") && thrown.message!!.contains("gatt down"))
        assertTrue(thrown is IOException)
    }

    @Test
    fun aCancelledEndpointDoesNotStartTheSecondAttempt() {
        var cancelled = false
        val calls = AtomicInteger()
        val thrown = assertThrows(DualSetupFailedException::class.java) {
            runner(cancelled = { cancelled }).run(listOf(coc, gatt)) { _, _ ->
                calls.incrementAndGet()
                cancelled = true
                throw IOException("closed")
            }
        }
        assertEquals(1, calls.get())
        assertEquals(1, thrown.causes.size)
    }

    @Test
    fun anInterruptionIsNotAFailureThatFallsBack() {
        val calls = AtomicInteger()
        assertThrows(InterruptedException::class.java) {
            runner().run(listOf(coc, gatt)) { _, _ ->
                calls.incrementAndGet()
                throw InterruptedException()
            }
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun aPlanIsBoundedAndNeverRepeatsATransport() {
        assertThrows(IllegalArgumentException::class.java) { runner().run(emptyList()) { t, _ -> Link(t) } }
        assertThrows(IllegalArgumentException::class.java) { runner().run(listOf(coc, coc)) { t, _ -> Link(t) } }
        assertThrows(IllegalArgumentException::class.java) { runner().run(listOf(coc, gatt, classic)) { t, _ -> Link(t) } }
    }

    @Test
    fun theCoCPhaseIsCutAtNineSecondsOrTheOuterDeadlineWhicheverIsEarlier() {
        assertEquals(1_000L + 9_000L, DualSetupBudget.cocDeadline(1_000L, null))
        assertEquals(1_000L + 9_000L, DualSetupBudget.cocDeadline(1_000L, 100_000L))
        assertEquals(4_000L, DualSetupBudget.cocDeadline(1_000L, 4_000L))
        assertEquals(1_000L, DualSetupBudget.cocDeadline(1_000L, 500L)) // an expired outer deadline never goes backwards
    }

    @Test
    fun theFallbackRecordStatesRequestedAndEffective() {
        val line = DualListeningLog.fallback(coc, gatt, IOException("x"), 4_321L)
        assertEquals(
            "call transport: event=dual_fallback | requested=BLE_L2CAP | effective=BLE_GATT | automatic=true | " +
                "reason=dual_coc_failed | cocError=IOException | cocPhaseMs=4321",
            line,
        )
        assertEquals("Using BLE GATT for this call: L2CAP did not connect", DualListeningLog.FALLBACK_NOTICE)
    }

    // ---- the second listener's lifetime and resource bounds ---------------------------------------------

    private class FakeServer(val log: MutableList<String>, private val failStart: Boolean = false) : DualSecondaryServer {
        var started = false
        var closed = false
        override fun start() {
            log += "start"
            if (failStart) throw IOException("Could not register GATT video service")
            started = true
        }
        override fun close() {
            log += "close"
            closed = true
        }
    }

    private fun host(
        creates: AtomicInteger,
        clock: () -> Long,
        sleeps: MutableList<Long>,
        servers: MutableList<FakeServer>,
        log: MutableList<String> = mutableListOf(),
        failStart: Boolean = false,
        gate: DualSettleGate = DualSettleGate(clock),
    ) = DualSecondaryHost(
        create = {
            creates.incrementAndGet()
            FakeServer(log, failStart).also(servers::add)
        },
        settle = gate,
        sleepMs = { sleeps += it },
        log = { log += it },
    )

    @Test
    fun openStartsExactlyOneServerAndANewAvailabilityOpensAFreshOne() {
        val creates = AtomicInteger()
        val servers = mutableListOf<FakeServer>()
        val host = host(creates, { 10_000L }, mutableListOf(), servers)
        assertFalse(host.ready)
        assertTrue(host.open())
        assertTrue(host.ready)
        assertEquals(1, host.serverCount)
        // Opening again changes nothing (resource bound: at most one second server per endpoint).
        assertTrue(host.open())
        assertEquals(1, creates.get())
        assertEquals(1, host.serverCount)
        host.close()
        assertTrue(servers.single().closed)
        assertFalse(host.ready)
        assertEquals(0, host.serverCount)
        // A closed host is finished; the next availability builds a new host (and a new server).
        assertFalse(host.open())
        assertEquals(1, creates.get())
    }

    @Test
    fun aServerThatCannotStartMeansNoHintAndNeverThrows() {
        val creates = AtomicInteger()
        val servers = mutableListOf<FakeServer>()
        val log = mutableListOf<String>()
        val host = host(creates, { 0L }, mutableListOf(), servers, log, failStart = true)
        assertFalse(host.open())
        assertFalse(host.ready)
        assertEquals(0, host.serverCount)
        assertTrue(log.any { it.startsWith("dual_listening: event=secondary | state=failed") })
        // No hint without a running second listener.
        assertSame(plainCoc, DualListeningWire.offerFor(plainCoc, enabled = true, secondaryReady = host.ready))
    }

    @Test
    fun theSecondServerWaitsForTheGattSettleTimeAfterTheLastClose() {
        var now = 100_000L
        val sleeps = mutableListOf<Long>()
        val gate = DualSettleGate({ now })
        val servers = mutableListOf<FakeServer>()
        val first = host(AtomicInteger(), { now }, sleeps, servers, gate = gate)
        assertTrue(first.open())
        assertTrue(sleeps.isEmpty()) // nothing was closed before
        now += 200L
        first.close()
        now += 400L
        val second = host(AtomicInteger(), { now }, sleeps, servers, gate = gate)
        assertTrue(second.open())
        assertEquals(listOf(DualSettleGate.SETTLE_MS - 400L), sleeps)
        // Settled: no wait.
        second.close()
        now += DualSettleGate.SETTLE_MS + 1
        sleeps.clear()
        val third = host(AtomicInteger(), { now }, sleeps, servers, gate = gate)
        assertTrue(third.open())
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun aCloseDuringTheStartLeavesNoServerOpen() {
        val servers = mutableListOf<FakeServer>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val host = DualSecondaryHost(
            create = {
                FakeServer(log).also {
                    servers += it
                    entered.countDown()
                    release.await(120, TimeUnit.SECONDS)
                }
            },
            settle = DualSettleGate({ 0L }),
            sleepMs = {},
            log = { log += it },
        )
        val result = AtomicReference<Boolean>()
        val opener = thread { result.set(host.open()) }
        assertTrue(entered.await(120, TimeUnit.SECONDS))
        host.close()
        release.countDown()
        opener.join(120_000)
        assertEquals(false, result.get())
        assertTrue(servers.single().closed)
        assertFalse(host.ready)
    }

    @Test
    fun theResourceCountsMatchThePlanAndTheSwitchOffEndpointIsTheOldOne() {
        assertEquals(1, DualListeningBudget.gattServers(secondaryOpen = false))
        assertEquals(2, DualListeningBudget.gattServers(secondaryOpen = true))
        assertEquals(1, DualListeningBudget.advertisingSets(secondaryOpen = false))
        assertEquals(1, DualListeningBudget.advertisingSets(secondaryOpen = true)) // the control server's set carries the hint
        assertEquals(0, DualListeningBudget.SECONDARY_SCAN_FILTERS)
        assertEquals(0, DualListeningBudget.SECONDARY_COC_REGISTRATIONS)
        assertEquals(0, DualListeningBudget.SECONDARY_ANNOUNCEMENT_BYTES)
        assertEquals(1, DualListeningBudget.SECONDARY_GATT_SERVERS)
        val line = DualListeningLog.secondary("started", 1, null)
        assertEquals("dual_listening: event=secondary | state=started | gattServers=2 | adSets=1", line)
        assertEquals("dual_listening: event=offer | hint=true | summary=0x0b | secondary=ready", DualListeningLog.offer(true, 0x0b, "ready"))
    }

    // ---- identity binding: the transport half and the real handshakes -----------------------------------

    @Test
    fun aLinkIsAcceptedOnlyOnATransportTheSelectedOfferAllowedAndTheHelloSigned() {
        val cocBit = NodeCapabilities.TRANSPORT_BLE_L2CAP
        val gattBit = NodeCapabilities.TRANSPORT_BLE_GATT
        assertEquals(DualLinkRule.Result.PASS, DualLinkRule.verifyCaller(hintedCoc, coc, cocBit))
        assertEquals(DualLinkRule.Result.PASS, DualLinkRule.verifyCaller(hintedCoc, gatt, gattBit or 0x08))
        assertEquals(DualLinkRule.Result.NOT_SIGNED, DualLinkRule.verifyCaller(hintedCoc, gatt, cocBit))
        assertEquals(DualLinkRule.Result.NOT_OFFERED, DualLinkRule.verifyCaller(plainCoc, gatt, gattBit))
        assertEquals(DualLinkRule.Result.NOT_OFFERED, DualLinkRule.verifyCaller(gattOffer, coc, cocBit))
        assertEquals(DualLinkRule.Result.NOT_OFFERED, DualLinkRule.verifyCaller(hintedCoc.copy(routeCost = 1), gatt, gattBit))
        assertEquals(DualLinkRule.Result.PASS, DualLinkRule.verifyCaller(gattOffer, gatt, gattBit))
    }

    @Test
    fun theCalleeAcceptsASecondTransportOnlyWhileTheSecondListenerExists() {
        assertTrue(DualLinkRule.arrivalAllowed(coc, secondaryOpen = false, arrived = coc))
        assertFalse(DualLinkRule.arrivalAllowed(coc, secondaryOpen = false, arrived = gatt))
        assertTrue(DualLinkRule.arrivalAllowed(coc, secondaryOpen = true, arrived = gatt))
        assertFalse(DualLinkRule.arrivalAllowed(gatt, secondaryOpen = true, arrived = coc)) // no reverse dual
        assertFalse(DualLinkRule.arrivalAllowed(coc, secondaryOpen = true, arrived = classic))
    }

    @Before
    fun fixTheEpochClock() {
        DiscoverySelectionCheck.unixSeconds = { 1_791_590_400L + 100L }
    }

    @After
    fun restoreTheEpochClock() {
        DiscoverySelectionCheck.unixSeconds = { System.currentTimeMillis() / 1000L }
    }

    private class Pipes {
        val initiatorInput = PipedInputStream(64 * 1024)
        val responderOutput = PipedOutputStream(initiatorInput)
        val responderInput = PipedInputStream(64 * 1024)
        val initiatorOutput = PipedOutputStream(responderInput)
    }

    private fun caps(transport: BluetoothTransportOption) = NodeCapabilities(
        transportMask = transport.capabilityMask or RotatingDiscoveryIds.LINK_HELLO_BIT,
        encodeCodecMask = NodeCapabilities.CODEC_VP9,
        decodeCodecMask = NodeCapabilities.CODEC_VP9,
        maxBitrateKbps = transport.maxVideoBitrateKbps,
        maxChildren = 0,
    )

    /** A caller authenticates [callee] over a BMH2 link whose hellos sign [transport]; returns what it saw. */
    private fun linkTo(callee: KeyPairSigningIdentity, transport: BluetoothTransportOption, entries: List<OpeningEntry>): Pair<SecureBluetoothSession, List<OpeningEntry>?> {
        val pipes = Pipes()
        val failure = AtomicReference<Throwable?>()
        val calleeThread = thread {
            runCatching {
                val link = SecureBluetoothHandshake.respond(pipes.responderInput, pipes.responderOutput, callee, caps(transport)) {}
                LinkOpeningExchange.exchange(link, caps(transport), entries)
            }.onFailure(failure::set)
        }
        val link = SecureBluetoothHandshake.initiate(
            pipes.initiatorInput,
            pipes.initiatorOutput,
            KeyPairSigningIdentity(generateEcKeyPair()),
            caps(transport),
        )
        val opening = LinkOpeningExchange.exchange(link, caps(transport), emptyList())
        calleeThread.join(120_000)
        failure.get()?.let { throw AssertionError("callee failed", it) }
        return link to opening
    }

    @Test
    fun theSignedHelloNamesTheTransportInUseAndTheVersion2PrefixCheckIsTheSameOnBoth() {
        val callee = KeyPairSigningIdentity(generateEcKeyPair())
        val calleeNode = NodeId.fromPublicKey(callee.publicKeyEncoded)
        val selected = offer(node = calleeNode.toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)).let {
            DualListeningWire.offerFor(it, enabled = true, secondaryReady = true)
        }
        val impostor = KeyPairSigningIdentity(generateEcKeyPair())
        for (transport in listOf(coc, gatt)) {
            val (link, opening) = linkTo(callee, transport, emptyList())
            // Each hello signs the bit of the link it ran on; the dual hint adds none.
            assertEquals(transport.capabilityMask, link.peerCapabilities.transportMask and (NodeCapabilities.TRANSPORT_BLE_L2CAP or NodeCapabilities.TRANSPORT_BLE_GATT))
            assertEquals(DualLinkRule.Result.PASS, DualLinkRule.verifyCaller(selected, transport, link.peerCapabilities.transportMask))
            assertEquals(calleeNode, link.peerNodeId)
            assertEquals(
                transport.name,
                DiscoverySelectionCheck.Result.PASS,
                DiscoverySelectionCheck.check(selected, 0, link.peerNodeId.toByteArray(), opening),
            )
            // Another phone answering at that address on the same transport fails the same prefix check.
            val (impostorLink, impostorOpening) = linkTo(impostor, transport, emptyList())
            assertEquals(
                transport.name,
                DiscoverySelectionCheck.Result.PREFIX_MISMATCH,
                DiscoverySelectionCheck.check(selected, 0, impostorLink.peerNodeId.toByteArray(), impostorOpening),
            )
        }
    }

    @Test
    fun theVersion5OpeningMustOpenTheSelectedIdOnWhicheverTransportAnswers() {
        val presenceKey = ByteArray(32) { (it * 3).toByte() }
        val callee = KeyPairSigningIdentity(generateEcKeyPair())
        val calleeNode = NodeId.fromPublicKey(callee.publicKeyEncoded)
        val entry = RotatingDiscoveryIds.ownEntry(presenceKey, calleeNode.toByteArray(), 1_990_656L, RotatingDiscoveryIds.ROLE_CALL_OFFER)
        val selected = DualListeningWire.offerFor(
            offer(node = entry.rdi, version = DiscoveryAnnouncement.ROTATING_VERSION),
            enabled = true,
            secondaryReady = true,
        )
        assertTrue(DualListeningWire.hasHint(selected))
        val impostor = KeyPairSigningIdentity(generateEcKeyPair())
        val impostorNode = NodeId.fromPublicKey(impostor.publicKeyEncoded)
        val impostorEntry = RotatingDiscoveryIds.ownEntry(presenceKey, impostorNode.toByteArray(), 1_990_656L, RotatingDiscoveryIds.ROLE_CALL_OFFER)
        for (transport in listOf(coc, gatt)) {
            val (link, opening) = linkTo(callee, transport, listOf(entry))
            assertEquals(DualLinkRule.Result.PASS, DualLinkRule.verifyCaller(selected, transport, link.peerCapabilities.transportMask))
            assertEquals(
                transport.name,
                DiscoverySelectionCheck.Result.PASS,
                DiscoverySelectionCheck.check(selected, RotatingDiscoveryIds.ROLE_CALL_OFFER, link.peerNodeId.toByteArray(), opening),
            )
            // An impostor that opens its own RDI, or none, never opens the selected one.
            val (impostorLink, impostorOpening) = linkTo(impostor, transport, listOf(impostorEntry))
            assertEquals(
                transport.name,
                DiscoverySelectionCheck.Result.OPENING_MISMATCH,
                DiscoverySelectionCheck.check(selected, RotatingDiscoveryIds.ROLE_CALL_OFFER, impostorLink.peerNodeId.toByteArray(), impostorOpening),
            )
            val (silentLink, silentOpening) = linkTo(impostor, transport, emptyList())
            assertEquals(
                DiscoverySelectionCheck.Result.OPENING_MISMATCH,
                DiscoverySelectionCheck.check(selected, RotatingDiscoveryIds.ROLE_CALL_OFFER, silentLink.peerNodeId.toByteArray(), silentOpening),
            )
        }
    }

    // ---- the switch -------------------------------------------------------------------------------------

    @Test
    fun theFeatureIsRegisteredOffByDefaultWithItsBenchmarkExtra() {
        val spec = ConnectivityFeatures.dualListening
        assertEquals("dual_listening", spec.id)
        assertEquals("test_dual_listening", spec.benchmarkExtra)
        assertTrue("no Settings switch since 2026-11-04", spec.constant)
        assertEquals(ConnectivityFeatures.GROUP, spec.group)
        assertEquals("T12", spec.phoneTest)
        assertFalse(ConnectivityFeatureDefaults.DUAL_LISTENING)
        assertEquals(ConnectivityFeatureDefaults.DUAL_LISTENING, spec.defaultEnabled)
        assertTrue(ConnectivityFeatures.all.contains(spec))
        // A benchmark launch reads only its extra; an ordinary launch the default (a stored choice is ignored).
        assertFalse(FeatureCatalog.resolve(spec, true, { false }) { true })
        assertTrue(FeatureCatalog.resolve(spec, true, { it == "test_dual_listening" }) { false })
        assertFalse(FeatureCatalog.resolve(spec, false, { true }) { null })
        assertEquals(ConnectivityFeatureDefaults.DUAL_LISTENING, FeatureCatalog.resolve(spec, false, { false }) { true })
    }

    @Test
    fun theInjectionRunsOnlyInADebugBuildWithTheSwitchOnAndAnExactValue() {
        val fail = DualListeningFeature.INJECT_COC_CONNECT
        val hang = DualListeningFeature.INJECT_COC_HANG
        assertEquals(DualListeningFeature.Injection.COC_CONNECT_FAIL, DualListeningFeature.injection(true, true, fail))
        assertEquals(DualListeningFeature.Injection.COC_HANG, DualListeningFeature.injection(true, true, hang))
        for (value in listOf(fail, hang, null, "coc", "")) {
            assertEquals(DualListeningFeature.Injection.NONE, DualListeningFeature.injection(false, true, value))
            assertEquals(DualListeningFeature.Injection.NONE, DualListeningFeature.injection(true, false, value))
        }
        assertEquals(DualListeningFeature.Injection.NONE, DualListeningFeature.injection(true, true, null))
        assertEquals(DualListeningFeature.Injection.NONE, DualListeningFeature.injection(true, true, "coc"))
    }

    @Test
    fun theHandshakeDeadlineAndPlanLineAreFixed() {
        assertEquals(20_000L, DualSetupBudget.INCOMING_HANDSHAKE_MS)
        assertEquals(
            "dual_listening: event=plan | endpoint=BLE_L2CAP | attempts=BLE_L2CAP>BLE_GATT",
            DualListeningLog.plan(coc, listOf(coc, gatt)),
        )
    }
}
