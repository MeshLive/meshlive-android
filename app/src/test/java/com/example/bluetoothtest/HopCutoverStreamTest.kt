package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A7 step 3.8: stream-shaped traffic (ascending mesh sequences with gaps) through two real [CarrierCutover] machines.
 * The driver is this test's own; it does not call the model test's private helpers and does not rely on consecutive
 * sequence numbers.
 */
class HopCutoverStreamTest {
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private class Endpoint(name: String, activeBeforeSwitchMs: Long) {
        val name = name
        val cutover = CarrierCutover(
            CarrierCutover.Config(activeBeforeSwitchMs = activeBeforeSwitchMs, holdMs = 200L, cooldownMs = 500L),
        )
        val delivered = ArrayList<Long>()
        val ends = ArrayList<String>()
    }

    private fun peerOf(owner: Endpoint, a: Endpoint, b: Endpoint): Endpoint = if (owner === a) b else a

    /** Delivers one endpoint's action batch, following Write actions across the wire to the peer's onArrive. */
    private fun pump(owner: Endpoint, actions: List<CarrierCutover.Action>, a: Endpoint, b: Endpoint, nowMs: Long) {
        val queue = ArrayDeque<Pair<Endpoint, CarrierCutover.Action>>()
        actions.forEach { queue += owner to it }
        while (queue.isNotEmpty()) {
            val (current, action) = queue.removeFirst()
            val peer = peerOf(current, a, b)
            when (action) {
                is CarrierCutover.Action.Write ->
                    peer.cutover.onArrive(action.carrier, action.wire, nowMs).forEach { queue += peer to it }
                is CarrierCutover.Action.Deliver -> current.delivered += action.rec.seq
                is CarrierCutover.Action.EndSession -> current.ends += action.reason
                else -> Unit
            }
        }
    }

    private fun seal(from: Endpoint, sequence: Long, nowMs: Long): List<CarrierCutover.Action> =
        from.cutover.onSealed(CarrierCutover.Rec(sequence, 64, 0), nowMs)

    private fun assertWellOrdered(endpoint: Endpoint) {
        assertEquals(endpoint.delivered.distinct(), endpoint.delivered)
        assertEquals(endpoint.delivered.sorted(), endpoint.delivered)
    }

    /** Both ends have carrier 1 ready; the receiver never moves its own send direction (only the sender's marker moves its accept). */
    private fun readyBoth(sender: Endpoint, receiver: Endpoint, a: Endpoint, b: Endpoint, nowMs: Long) {
        sender.cutover.onCallActive(0)
        receiver.cutover.onCallActive(0)
        sender.cutover.onCarrierPending(1, 0)
        receiver.cutover.onCarrierPending(1, 0)
        pump(sender, sender.cutover.onCarrierUp(1, nowMs), a, b, nowMs)
        pump(receiver, receiver.cutover.onCarrierUp(1, nowMs), a, b, nowMs)
    }

    private fun record(sender: Endpoint, receiver: Endpoint, sequence: Long, nowMs: Long, a: Endpoint, b: Endpoint) {
        pump(sender, seal(sender, sequence, nowMs), a, b, nowMs)
    }

    @Test
    fun aNormalSwitchLosesNothing() {
        val sender = Endpoint("sender", activeBeforeSwitchMs = 0L)
        val receiver = Endpoint("receiver", activeBeforeSwitchMs = 10_000L)
        readyBoth(sender, receiver, sender, receiver, 0)
        var now = 1_000L
        val records = listOf(100L, 101L, 105L, 106L, 110L)
        records.forEach { sequence ->
            record(sender, receiver, sequence, now, sender, receiver)
            now += 10
        }
        assertEquals(records, receiver.delivered)
        assertTrue(receiver.ends.isEmpty())
        assertWellOrdered(receiver)
    }

    @Test
    fun aNewCarrierThatDiesAfterTheSwitchFallsBackWithoutReordering() {
        val sender = Endpoint("sender", activeBeforeSwitchMs = 0L)
        val receiver = Endpoint("receiver", activeBeforeSwitchMs = 10_000L)
        readyBoth(sender, receiver, sender, receiver, 0)
        var now = 1_000L
        listOf(100L, 101L, 105L).forEach { sequence ->
            record(sender, receiver, sequence, now, sender, receiver)
            now += 10
        }
        // A record sealed on the new carrier just before it dies is lost with that carrier (its Write is dropped).
        seal(sender, 106L, now)
        val death = sender.cutover.onCarrierDied(1, now)
        pump(sender, death, sender, receiver, now)
        now += 10
        listOf(107L, 110L).forEach { sequence ->
            record(sender, receiver, sequence, now, sender, receiver)
            now += 10
        }
        assertEquals(listOf(100L, 101L, 105L, 107L, 110L), receiver.delivered)
        assertTrue("a fallback never ends the session while a carrier lives", sender.ends.isEmpty())
        assertWellOrdered(receiver)
    }

    @Test
    fun anOldCarrierThatDiesBeforeTheSwitchContinuesOnTheNewOne() {
        val sender = Endpoint("sender", activeBeforeSwitchMs = 10_000L)
        val receiver = Endpoint("receiver", activeBeforeSwitchMs = 10_000L)
        readyBoth(sender, receiver, sender, receiver, 0)
        assertFalse("the switch waits for its window", sender.cutover.sendOn == 1)

        var now = 1_000L
        record(sender, receiver, 100L, now, sender, receiver)
        now += 10

        val death = sender.cutover.onCarrierDied(0, now)
        pump(sender, death, sender, receiver, now)
        now += 10
        record(sender, receiver, 101L, now, sender, receiver)
        now += 10
        record(sender, receiver, 110L, now, sender, receiver)

        assertEquals(1, sender.cutover.sendOn)
        assertEquals(listOf(100L, 101L, 110L), receiver.delivered)
        assertTrue(sender.ends.isEmpty())
        assertTrue(receiver.ends.isEmpty())
        assertWellOrdered(receiver)
    }

    @Test
    fun aSwitchOnASlowOldCarrierHoldsThenReleasesInOrder() {
        val sender = Endpoint("sender", activeBeforeSwitchMs = 0L)
        val receiver = Endpoint("receiver", activeBeforeSwitchMs = 10_000L)
        sender.cutover.onCallActive(0)
        receiver.cutover.onCallActive(0)
        sender.cutover.onCarrierPending(1, 0)
        receiver.cutover.onCarrierPending(1, 0)
        pump(receiver, receiver.cutover.onCarrierUp(1, 0), sender, receiver, 0)

        var now = 1_000L
        listOf(100L, 101L, 105L).forEach { sequence ->
            record(sender, receiver, sequence, now, sender, receiver)
            now += 10
        }

        // The SWITCH is written on the old carrier but arrives late: the new carrier's records follow it.
        val upActions = sender.cutover.onCarrierUp(1, now)
        val switchWrites = upActions.filterIsInstance<CarrierCutover.Action.Write>()
        assertTrue(switchWrites.isNotEmpty())
        assertTrue(switchWrites.all { it.wire is CarrierCutover.Wire.Switch })
        listOf(106L, 107L, 110L).forEach { sequence ->
            pump(sender, seal(sender, sequence, now), sender, receiver, now)
            now += 10
        }
        assertEquals(
            "the receiver holds the new carrier's records until the marker",
            listOf(100L, 101L, 105L),
            receiver.delivered,
        )
        pump(sender, switchWrites, sender, receiver, now)

        assertEquals(listOf(100L, 101L, 105L, 106L, 107L, 110L), receiver.delivered)
        assertTrue(receiver.ends.isEmpty())
        assertWellOrdered(receiver)
    }
}
