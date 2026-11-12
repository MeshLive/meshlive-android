package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class TicketGateTest {
    private val ticket = ByteArray(8) { (it + 1).toByte() }
    private val other = ByteArray(8) { (it + 2).toByte() }

    @Test
    fun aWrongTicketNeverConsumes() {
        val gate = TicketGate(ticket)
        assertFalse(gate.tryConsume(other))
        assertFalse(gate.consumed)
        assertTrue(gate.tryConsume(ticket))
        assertTrue(gate.consumed)
    }

    @Test
    fun theRightTicketConsumesOnce() {
        val gate = TicketGate(ticket)
        assertTrue(gate.tryConsume(ticket))
        assertFalse(gate.tryConsume(ticket))
        assertTrue(gate.consumed)
    }

    @Test
    fun eightRacingThreadsConsumeExactlyOnce() {
        val gate = TicketGate(ticket)
        val start = CountDownLatch(1)
        val wins = AtomicInteger(0)
        val threads = (0 until 8).map {
            Thread {
                start.await()
                if (gate.tryConsume(ticket)) wins.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(5_000) }
        assertTrue(wins.get() == 1)
    }

    @Test
    fun matchesDoesNotConsume() {
        val gate = TicketGate(ticket)
        assertTrue(gate.matches(ticket))
        assertFalse(gate.matches(other))
        assertFalse(gate.consumed)
        gate.wipe()
        assertFalse(gate.matches(ticket))
    }
}
