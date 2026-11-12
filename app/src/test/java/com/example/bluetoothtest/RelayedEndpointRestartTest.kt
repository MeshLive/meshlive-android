package com.example.bluetoothtest

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relayed calls (rc-ctl-20261106): a callee with relayed calls on rebuilt its "idle" call endpoint at the first helper
 * tick after launch, closing the hop a call relay had just opened to it. These tests drive the stale-offer tick, the
 * endpoint's restart rule and the static relay's child watch the way the app does, with a simulated endpoint and real
 * record sessions.
 */
class RelayedEndpointRestartTest {
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(120)

    /** A call endpoint as the activity sees it: idle (no call phase) but possibly holding an accepted link. */
    private class Endpoint(var acceptedLink: Boolean = false) {
        var restarts = 0
        var linkClosedUnderPeer = false
        var offerOpenedAgain = false

        fun restart(guardOn: Boolean): Boolean {
            if (!RelayedEndpointRestart.allowed(endpointIdle = true, relaying = false, messageSync = false, acceptedLink = acceptedLink, guardOn = guardOn)) {
                return false
            }
            if (acceptedLink) linkClosedUnderPeer = true
            acceptedLink = false
            restarts++
            return true
        }
    }

    private fun tick(tracker: RelayedOfferTracker, endpoint: Endpoint, guardOn: Boolean, liveHelping: Boolean = true) =
        tracker.check(callReady = true, relaying = false, liveHelping = { liveHelping }, consent = { true }, restart = { endpoint.restart(guardOn) })

    @Test
    fun aRelayHopAcceptedBeforeTheFirstTickSurvivesAndTheOfferIsRefreshedOnceTheLinkEnds() {
        // The endpoint opened its offer without the helper flag; a relay connected; then the first tick saw "helping".
        val tracker = RelayedOfferTracker { true }
        val endpoint = Endpoint(acceptedLink = true)
        tracker.opened(helping = false, consent = true)

        assertTrue(tick(tracker, endpoint, guardOn = true))
        assertFalse("the relay's hop must not be closed", endpoint.linkClosedUnderPeer)
        assertEquals(0, endpoint.restarts)

        // Still stale and still busy at the next tick: asked again, still refused.
        assertTrue(tick(tracker, endpoint, guardOn = true))
        assertEquals(0, endpoint.restarts)

        // The link ended (the call is over): the next tick rebuilds the endpoint once.
        endpoint.acceptedLink = false
        assertTrue(tick(tracker, endpoint, guardOn = true))
        assertEquals(1, endpoint.restarts)
        assertFalse(endpoint.linkClosedUnderPeer)
    }

    @Test
    fun controlArmClosesTheAcceptedHopAtTheFirstTick() {
        // The defect as measured: an accepted link that has not signalled a call counted as idle.
        val tracker = RelayedOfferTracker { false }
        val endpoint = Endpoint(acceptedLink = true)
        tracker.opened(helping = false, consent = true)

        tick(tracker, endpoint, guardOn = false)
        assertTrue(endpoint.linkClosedUnderPeer)
        assertEquals(1, endpoint.restarts)
    }

    @Test
    fun controlArmForgetsARefusedRestartForGood() {
        // Off: the offer is forgotten before asking, so a busy endpoint keeps its stale offer until something else restarts it.
        val tracker = RelayedOfferTracker { false }
        var busy = true
        var restarts = 0
        tracker.opened(helping = false, consent = true)
        val restart = { if (busy) false else { restarts++; true } }
        tracker.check(true, false, { true }, { true }, restart)
        busy = false
        assertFalse(tracker.check(true, false, { true }, { true }, restart))
        assertEquals(0, restarts)
    }

    @Test
    fun aRestartIsAskedOnceUntilTheNewEndpointReportsItsOffer() {
        val tracker = RelayedOfferTracker { true }
        val endpoint = Endpoint()
        tracker.opened(helping = false, consent = true)

        tick(tracker, endpoint, guardOn = true)
        assertEquals(1, endpoint.restarts)
        // An endpoint that opens no relayed offer (Classic) never reports one: no restart loop.
        assertFalse(tick(tracker, endpoint, guardOn = true))
        assertEquals(1, endpoint.restarts)

        // A new LE endpoint reports the live bits: nothing to do.
        tracker.opened(helping = true, consent = true)
        assertFalse(tick(tracker, endpoint, guardOn = true))
        assertEquals(1, endpoint.restarts)
    }

    @Test
    fun anOfferReportedWhileTheRestartRunsIsKept() {
        // The new endpoint may report its offer before the restart call returns: that offer must not be forgotten.
        val tracker = RelayedOfferTracker { true }
        tracker.opened(helping = false, consent = true)
        var restarts = 0
        val restart = {
            restarts++
            tracker.opened(helping = false, consent = true)
            true
        }
        tracker.check(true, false, { true }, { true }, restart)
        tracker.check(true, false, { true }, { true }, restart)
        assertEquals("the newly reported (still stale) offer is checked again", 2, restarts)
    }

    @Test
    fun anAgreeingOfferABusyRelayOrANotReadyEndpointAsksNothing() {
        val tracker = RelayedOfferTracker { true }
        var asked = 0
        val restart = { asked++; true }
        tracker.opened(helping = true, consent = true)
        assertFalse(tracker.check(true, false, { true }, { true }, restart))
        tracker.opened(helping = false, consent = true)
        assertFalse(tracker.check(true, true, { true }, { true }, restart))
        assertFalse(tracker.check(false, false, { true }, { true }, restart))
        assertEquals(0, asked)
        assertTrue(tracker.check(true, false, { false }, { false }, restart))
        assertEquals(1, asked)
    }

    @Test
    fun theRestartRuleKeepsItsOtherGuardsInBothArms() {
        for (guard in listOf(true, false)) {
            assertFalse(RelayedEndpointRestart.allowed(endpointIdle = false, relaying = false, messageSync = false, acceptedLink = false, guardOn = guard))
            assertFalse(RelayedEndpointRestart.allowed(endpointIdle = true, relaying = true, messageSync = false, acceptedLink = false, guardOn = guard))
            assertFalse(RelayedEndpointRestart.allowed(endpointIdle = true, relaying = false, messageSync = true, acceptedLink = false, guardOn = guard))
            assertTrue(RelayedEndpointRestart.allowed(endpointIdle = true, relaying = false, messageSync = false, acceptedLink = false, guardOn = guard))
        }
    }

    // ----- the static relay's child watch, over real record sessions -----

    private class Pipe {
        private val queue = LinkedBlockingQueue<Int>()
        @Volatile var closed = false
        val input = object : InputStream() {
            override fun read(): Int {
                while (true) {
                    val next = queue.poll(20, TimeUnit.MILLISECONDS)
                    if (next != null) return next
                    if (closed && queue.isEmpty()) throw IOException("Pipe closed")
                }
            }
        }
        val output = object : OutputStream() {
            override fun write(b: Int) {
                if (closed) throw IOException("Pipe closed")
                queue.put(b and 0xff)
            }
        }
    }

    /** relay <-> callee: [relay] is the relay's session, [callee] the callee's; [close] ends the callee's link. */
    private class ChildLink(val relay: SecureBluetoothSession, val callee: SecureBluetoothSession, private val pipes: List<Pipe>) {
        fun close() = pipes.forEach { it.closed = true }
    }

    private fun childLink(): ChildLink {
        val toCallee = Pipe()
        val toRelay = Pipe()
        val keyDown = ByteArray(32).also(SecureRandom()::nextBytes)
        val keyUp = ByteArray(32).also(SecureRandom()::nextBytes)
        val caps = NodeCapabilities(NodeCapabilities.TRANSPORT_BLE_GATT, 3, 3, 500, 0)
        val relayId = NodeId.fromBytes(ByteArray(16) { (0x10 + it).toByte() })
        val calleeId = NodeId.fromBytes(ByteArray(16) { (0x30 + it).toByte() })
        val relay = SecureBluetoothSession(calleeId, caps, SecureRecordReader(toRelay.input, keyUp), SecureRecordWriter(toCallee.output, keyDown))
        val callee = SecureBluetoothSession(relayId, caps, SecureRecordReader(toCallee.input, keyDown), SecureRecordWriter(toRelay.output, keyUp))
        return ChildLink(relay, callee, listOf(toCallee, toRelay))
    }

    private fun readFirstAsync(watch: RelayChildWatch): AtomicReference<RelayChildWatch.First?> {
        val result = AtomicReference<RelayChildWatch.First?>(null)
        thread(isDaemon = true) { result.set(watch.readFirst()) }
        return result
    }

    private fun await(result: AtomicReference<RelayChildWatch.First?>): RelayChildWatch.First {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (result.get() == null && System.nanoTime() < deadline) Thread.sleep(10)
        return result.get() ?: error("the child watch never returned")
    }

    @Test
    fun aCalleeThatClosesBeforeTheCallerJoinsIsReportedAtOnce() {
        // rc-ctl-20261106 0002/0011: the callee's endpoint closed the hop; the relay held it for minutes.
        val link = childLink()
        val watch = RelayChildWatch(link.relay)
        val result = readFirstAsync(watch)
        SecureLinkControlProtocol.writeHeartbeat(link.callee.writer)
        link.callee.writer.flush()
        link.close()
        assertTrue(await(result) is RelayChildWatch.First.ClosedBeforeParent)
    }

    @Test
    fun heartbeatsBeforeTheJoinAreDroppedAndTheFirstEndToEndRecordWaitsForTheCaller() {
        val link = childLink()
        val watch = RelayChildWatch(link.relay)
        val result = readFirstAsync(watch)
        SecureLinkControlProtocol.writeHeartbeat(link.callee.writer)
        val payload = byteArrayOf(1, 2, 3)
        link.callee.writer.write(SecureRecordType.END_TO_END_CONTENT, payload)
        link.callee.writer.flush()
        val first = await(result) as RelayChildWatch.First.Record
        assertEquals(SecureRecordType.END_TO_END_CONTENT, first.record.type)
        assertArrayEquals(payload, first.record.payload)

        val joined = AtomicReference<Boolean?>(null)
        thread(isDaemon = true) { joined.set(watch.awaitParent()) }
        Thread.sleep(100)
        assertEquals("forwarding waits for the caller", null, joined.get())
        watch.parentJoined()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (joined.get() == null && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(true, joined.get())
    }

    @Test
    fun aChildFailureAfterTheJoinIsLeftToTheOrdinaryForwardingPath() {
        val link = childLink()
        val watch = RelayChildWatch(link.relay)
        watch.parentJoined()
        val result = readFirstAsync(watch)
        link.close()
        assertTrue(await(result) is RelayChildWatch.First.Failed)
    }

    @Test
    fun aRelayThatStopsReleasesItsWaitingReader() {
        val link = childLink()
        val watch = RelayChildWatch(link.relay)
        val result = readFirstAsync(watch)
        link.callee.writer.write(SecureRecordType.END_TO_END_HANDSHAKE, byteArrayOf(9))
        link.callee.writer.flush()
        assertTrue(await(result) is RelayChildWatch.First.Record)
        watch.abandon()
        assertFalse(watch.awaitParent())
    }
}
