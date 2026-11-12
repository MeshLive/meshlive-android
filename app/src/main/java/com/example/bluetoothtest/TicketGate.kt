package com.example.bluetoothtest

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The attempt's one-shot ticket gate. [matches] is a constant-time comparison that never spends the ticket;
 * [tryConsume] is the only way the ticket is spent: equal AND not spent before, atomically. Never restored.
 */
internal class TicketGate(ticket: ByteArray) {
    private val ticket = ticket.copyOf()
    private val consumedFlag = AtomicBoolean(false)

    val consumed: Boolean get() = consumedFlag.get()

    /** Constant-time equality with the attempt's ticket; does not consume. */
    fun matches(candidate: ByteArray): Boolean = MessageDigest.isEqual(ticket, candidate)

    /** The only way the ticket is spent: equal AND not spent before, atomically. Never restored. */
    fun tryConsume(candidate: ByteArray): Boolean =
        MessageDigest.isEqual(ticket, candidate) && consumedFlag.compareAndSet(false, true)

    fun wipe() {
        ticket.fill(0)
    }
}
