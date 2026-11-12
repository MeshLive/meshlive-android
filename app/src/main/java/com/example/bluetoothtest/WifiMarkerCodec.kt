package com.example.bluetoothtest

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Carrier id <-> ticket, per hop. Carrier 0 has the all-zero ticket; the ticket of a Wi-Fi carrier is its attempt's
 * ticket. History is kept for the life of the hop, so a late marker about a carrier that just died still decodes.
 * Tickets are public per-attempt handles, not secrets (no wipe).
 */
internal class CarrierTickets {
    private val byId = ConcurrentHashMap<Int, ByteArray>()

    fun register(id: Int, ticket: ByteArray) {
        require(id in 1..62 && ticket.size == WifiWire.TICKET_BYTES)
        byId[id] = ticket.copyOf()
    }

    fun ticketOf(id: Int): ByteArray? = if (id == 0) ByteArray(WifiWire.TICKET_BYTES) else byId[id]?.copyOf()

    fun idOf(ticket: ByteArray): Int? {
        if (ticket.size != WifiWire.TICKET_BYTES) return null
        if (ticket.all { it == 0.toByte() }) return 0
        var found: Int? = null
        for ((id, known) in byId) { // every entry, no early exit
            if (MessageDigest.isEqual(known, ticket)) found = id
        }
        return found
    }
}

/**
 * A3's [CarrierMarkerCodec] for a call or hop: `SWITCH`/`RESUME` markers are `Bwf.Switch`/`Bwf.Resume` records whose
 * ticket names the carrier (the sender's local id resolved through [CarrierTickets] on the receiver). A payload that
 * is not a marker decodes to null; a marker with an unknown ticket decodes to the burned carrier
 * [CarrierCutover.NONE] and is logged, never thrown.
 */
internal class WifiMarkerCodec(private val tickets: CarrierTickets) : CarrierMarkerCodec {
    override fun encode(wire: CarrierCutover.Wire): ByteArray = when (wire) {
        is CarrierCutover.Wire.Switch -> Bwf.Switch(
            wire.gen,
            tickets.ticketOf(wire.carrier) ?: throw IllegalStateException("no ticket for carrier ${wire.carrier}"),
            wire.boundary,
            0L,
        ).encode()
        is CarrierCutover.Wire.Resume -> Bwf.Resume(
            wire.gen,
            tickets.ticketOf(wire.carrier) ?: throw IllegalStateException("no ticket for carrier ${wire.carrier}"),
            wire.next,
            0L,
        ).encode()
        else -> throw IllegalStateException("not a marker")
    }

    override fun decode(payload: ByteArray): CarrierCutover.Wire? {
        if (payload.size < 5) return null
        if (!payload.copyOfRange(0, 4).contentEquals(WifiWire.MAGIC_BWF1)) return null
        val kind = payload[4].toInt() and 0xff
        if (kind != WifiWire.KIND_SWITCH && kind != WifiWire.KIND_RESUME) return null
        val record = try {
            WifiWire.decodeBwf1(payload)
        } catch (_: WifiWireException) {
            return null
        }
        return when (record) {
            is Bwf.Switch -> {
                val carrier = carrierOf(record.ticket)
                CarrierCutover.Wire.Switch(record.gen, record.e2eBoundary, carrier)
            }
            is Bwf.Resume -> {
                val carrier = carrierOf(record.ticket)
                CarrierCutover.Wire.Resume(record.gen, record.e2eNext, carrier)
            }
            else -> null
        }
    }

    private fun carrierOf(ticket: ByteArray): Int {
        val id = tickets.idOf(ticket)
        if (id == null) {
            WifiLog.emit(WifiLog.upgrade("marker_unknown_ticket"))
            return CarrierCutover.NONE
        }
        return id
    }
}
