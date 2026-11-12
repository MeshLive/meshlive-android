package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiMarkerCodecTest {
    private val tickets = CarrierTickets()
    private val ticket1 = ByteArray(8) { (it + 1).toByte() }
    private val ticket2 = ByteArray(8) { (it + 2).toByte() }
    private val codec = WifiMarkerCodec(tickets)

    init {
        tickets.register(1, ticket1)
        tickets.register(2, ticket2)
    }

    @Test
    fun switchAndResumeRoundTripThroughBwf1() {
        val switch = CarrierCutover.Wire.Switch(7L, 1234L, 2)
        val decodedSwitch = codec.decode(codec.encode(switch))
        assertTrue(decodedSwitch is CarrierCutover.Wire.Switch)
        decodedSwitch as CarrierCutover.Wire.Switch
        assertEquals(7L, decodedSwitch.gen)
        assertEquals(1234L, decodedSwitch.boundary)
        assertEquals(2, decodedSwitch.carrier)

        val resume = CarrierCutover.Wire.Resume(3L, 99L, 1)
        val decodedResume = codec.decode(codec.encode(resume))
        assertTrue(decodedResume is CarrierCutover.Wire.Resume)
        decodedResume as CarrierCutover.Wire.Resume
        assertEquals(3L, decodedResume.gen)
        assertEquals(99L, decodedResume.next)
        assertEquals(1, decodedResume.carrier)
    }

    @Test
    fun carrierZeroUsesTheAllZeroTicket() {
        val encoded = codec.encode(CarrierCutover.Wire.Switch(1L, 10L, 0))
        val record = WifiWire.decodeBwf1(encoded) as Bwf.Switch
        assertTrue(record.ticket.all { it == 0.toByte() })
        assertEquals(0, (codec.decode(encoded) as CarrierCutover.Wire.Switch).carrier)
    }

    @Test
    fun aNonMarkerBwf1PayloadDecodesToNull() {
        assertNull(codec.decode(Bwf.Caps(1, 2, 3).encode()))
        assertNull(codec.decode(Bwf.Host(1, WifiWire.BEARER_AWARE, ticket1, 20_000, 0).encode()))
    }

    @Test
    fun anUnknownTicketDecodesToTheBurnedCarrier() {
        val encoded = Bwf.Switch(4L, ByteArray(8) { 0x55 }, 1L, 0L).encode()
        val decoded = codec.decode(encoded)
        assertTrue(decoded is CarrierCutover.Wire.Switch)
        assertEquals(CarrierCutover.NONE, (decoded as CarrierCutover.Wire.Switch).carrier)
    }

    @Test
    fun aMalformedMarkerDecodesToNull() {
        assertNull(codec.decode(byteArrayOf(0x42, 0x57, 0x46, 0x31, 8)))
        assertNull(codec.decode("BWF1".toByteArray() + byteArrayOf(8) + ByteArray(12)))
        assertNull(codec.decode(ByteArray(4)))
    }

    @Test
    fun aTicketRegisteredBeforePendingStillDecodesAfterTheCarrierDied() {
        val registry = CarrierTickets()
        registry.register(9, ticket2)
        val local = WifiMarkerCodec(registry)
        val encoded = local.encode(CarrierCutover.Wire.Resume(5L, 77L, 9))
        assertEquals(9, (local.decode(encoded) as CarrierCutover.Wire.Resume).carrier)
        // no unregister call exists: the history stays for the hop's life
        assertEquals(9, (local.decode(encoded) as CarrierCutover.Wire.Resume).carrier)
    }

    @Test
    fun aTicketComparisonDoesNotStopAtTheFirstDifference() {
        val registry = CarrierTickets()
        val lastByteDiffers = ticket1.copyOf().also { it[7] = (it[7] + 1).toByte() }
        val firstByteDiffers = ticket1.copyOf().also { it[0] = (it[0] + 1).toByte() }
        registry.register(1, ticket1)
        registry.register(2, lastByteDiffers)
        // A ticket is found only when every byte matches, wherever the difference is.
        assertEquals(1, registry.idOf(ticket1))
        assertEquals(2, registry.idOf(lastByteDiffers))
        assertNull(registry.idOf(firstByteDiffers))
        assertArrayEquals(ticket1, registry.ticketOf(1))
        assertNull(registry.idOf(ByteArray(8) { 9 }))
    }
}
