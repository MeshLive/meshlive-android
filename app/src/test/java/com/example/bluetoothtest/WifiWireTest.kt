package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiWireTest {
    private fun hex(text: String) = WifiVectors.hex(text)
    private fun hex(bytes: ByteArray) = WifiVectors.hex(bytes)

    /** The fields of a record under the names the vector file uses (numbers as Long). */
    private fun fieldsOf(record: Bwf): Map<String, Any?> = when (record) {
        is Bwf.Caps -> mapOf(
            "bearers" to record.bearers.toLong(), "freeHost" to record.freeHost.toLong(),
            "freeGuest" to record.freeGuest.toLong(),
        )
        is Bwf.Host -> mapOf(
            "status" to record.status.toLong(), "bearer" to record.bearer.toLong(), "ticket" to hex(record.ticket),
            "validMs" to record.validMs.toLong(), "freqMhz" to record.freqMhz.toLong(),
        )
        is Bwf.Decline -> mapOf("ticket" to hex(record.ticket), "reason" to record.reason.toLong())
        is Bwf.Pick -> mapOf("ticket" to hex(record.ticket), "challenge" to hex(record.challenge))
        is Bwf.Armed -> mapOf("ticket" to hex(record.ticket))
        is Bwf.Bind -> mapOf("role" to record.role.toLong(), "ticket" to hex(record.ticket))
        is Bwf.Ready -> mapOf(
            "recvCeilingKbps" to record.recvCeilingKbps.toLong(), "familyCapKbps" to record.familyCapKbps.toLong(),
            "transportBudgetKbps" to record.transportBudgetKbps.toLong(),
        )
        is Bwf.Switch -> mapOf(
            "gen" to record.gen, "ticket" to hex(record.ticket), "e2eBoundary" to record.e2eBoundary, "meshBoundary" to record.meshBoundary,
        )
        is Bwf.Resume -> mapOf(
            "gen" to record.gen, "ticket" to hex(record.ticket), "e2eNext" to record.e2eNext, "meshNext" to record.meshNext,
        )
        is Bwf.Close -> mapOf("reason" to record.reason.toLong())
        is Bwf.Creds -> mapOf("ticket" to hex(record.ticket), "port" to record.port.toLong(), "ipv4" to record.ipv4Text)
    }

    private fun errorOf(block: () -> Unit): WifiWireError {
        val failure = runCatching(block).exceptionOrNull()
        return (failure as? WifiWireException)?.error ?: error("expected a WifiWireException but got $failure")
    }

    @Test
    fun everyValidBwf1VectorDecodesToItsFieldsAndEncodesBackToTheSameBytes() {
        val vectors = WifiVectors.list("bwf1")
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors) {
            val name = vector["name"] as String
            val raw = hex(vector["hex"] as String)
            val record = WifiWire.decodeBwf1(raw)
            assertEquals(name, (vector["kind"] as Long).toInt(), record.kind)
            @Suppress("UNCHECKED_CAST")
            val expected = vector["decoded"] as Map<String, Any?>
            assertEquals(name, expected, fieldsOf(record))
            // Forward compatibility (X5): the ignored bits/bytes are written as 0 again, so only the round-trip records match.
            if (vector["roundTrip"] as Boolean) {
                assertArrayEquals(name, raw, record.encode())
            } else {
                assertFalse(name, raw.contentEquals(record.encode()))
            }
        }
    }

    @Test
    fun everyInvalidBwf1VectorIsRefusedWithItsListedError() {
        for (vector in WifiVectors.list("bwf1Invalid")) {
            val name = vector["name"] as String
            val raw = hex(vector["hex"] as String)
            assertEquals(name, WifiWireError.valueOf(vector["error"] as String), errorOf { WifiWire.decodeBwf1(raw) })
        }
    }

    @Test
    fun theLengthIsJudgedFirstSoAnOversizedRecordNeverReachesTheOtherChecks() {
        assertEquals(WifiWireError.LENGTH, errorOf { WifiWire.decodeBwf1(ByteArray(97) { 0x42 }) })
        assertEquals(WifiWireError.LENGTH, errorOf { WifiWire.decodeBwf1(ByteArray(4) { 0x42 }) })
        assertEquals(WifiWireError.LENGTH, errorOf { WifiWire.decodeBwf1(ByteArray(0)) })
        assertEquals(WifiWireError.MAGIC, errorOf { WifiWire.decodeBwf1(ByteArray(96) { 0x42 }) })
    }

    @Test
    fun theCiphertextLengthIsJudgedBeforeAnythingIsAllocated() {
        assertFalse(WifiWire.acceptsBwf1CiphertextLength(Int.MAX_VALUE))
        assertFalse(WifiWire.acceptsBwf1CiphertextLength(4_194_304))
        assertFalse(WifiWire.acceptsBwf1CiphertextLength(-1))
        assertFalse(WifiWire.acceptsBwf1CiphertextLength(0))
        assertFalse(WifiWire.acceptsBwf1CiphertextLength(20)) // 4 bytes of plaintext
        assertTrue(WifiWire.acceptsBwf1CiphertextLength(21)) // the shortest record, 5 bytes
        assertTrue(WifiWire.acceptsBwf1CiphertextLength(112)) // 96 bytes
        assertFalse(WifiWire.acceptsBwf1CiphertextLength(113))
    }

    @Test
    fun constructorsEnforceTheSameLimitsAsTheDecoder() {
        val ticket = ByteArray(8) { it.toByte() }
        val bad: List<() -> Unit> = listOf(
            { Bwf.Caps(0x10, 0, 0, 0) },
            { Bwf.Caps(1, 8, 0, 0) },
            { Bwf.Caps(1, 0, 8, 0) },
            { Bwf.Caps(1, 0, 0, 1) },
            { Bwf.Host(2, 1, ticket, 1, 0) },
            { Bwf.Host(1, 1, ticket, 20_001, 0) },
            { Bwf.Host(1, 1, ticket, 1, 5745) },
            { Bwf.Host(1, 2, ticket, 1, 2437) },
            { Bwf.Host(1, 1, ByteArray(7), 1, 0) },
            { Bwf.Decline(ticket, 0) },
            { Bwf.Decline(ticket, 8) },
            { Bwf.Bind(3, ticket) },
            { Bwf.Ready(1, 2, 3, reserved = 1) },
            { Bwf.Switch(0L, ticket, 0L, 0L) },
            { Bwf.Switch(1L shl 32, ticket, 0L, 0L) },
            { Bwf.Switch(1L, ticket, -1L, 0L) },
            { Bwf.Resume(1L, ticket, 0L, -1L) },
            { Bwf.Creds(ticket, 0, byteArrayOf(10, 0, 0, 1)) },
            { Bwf.Creds(ticket, 80, ByteArray(4)) },
            { Bwf.Pick(ticket, ByteArray(15)) },
        )
        for ((index, make) in bad.withIndex()) {
            assertEquals("case $index", WifiWireError.FIELD, errorOf(make))
        }
    }

    @Test
    fun aRecordKeepsItsOwnCopyOfTheTicket() {
        val ticket = ByteArray(8) { 1 }
        val record = Bwf.Armed(ticket)
        ticket[0] = 9
        assertEquals(1, record.ticket[0].toInt())
        val encoded = record.encode()
        encoded[5] = 7
        assertEquals(1, record.encode()[5].toInt())
    }

    @Test
    fun recordsAreEqualExactlyWhenTheirBytesAreEqual() {
        val t1 = ByteArray(8) { 1 }
        val t2 = ByteArray(8) { 2 }
        assertEquals(Bwf.Armed(t1), Bwf.Armed(t1.copyOf()))
        assertEquals(Bwf.Armed(t1).hashCode(), Bwf.Armed(t1.copyOf()).hashCode())
        assertNotEquals(Bwf.Armed(t1), Bwf.Armed(t2))
        assertNotEquals(Bwf.Armed(t1), Bwf.Decline(t1, 1))
    }

    @Test
    fun theThreeBindingFramesMatchTheVectorsAndRoundTrip() {
        val bind = WifiVectors.section("bind")
        val f1 = Bwu1Frame1(WifiVectors.bytes(bind, "challenge"))
        val f2 = Bwu1Frame2(
            WifiVectors.bytes(WifiVectors.section("keys"), "ticket"), WifiVectors.bytes(bind, "guestPoint"),
            WifiVectors.bytes(bind, "guestNonce"), WifiVectors.bytes(bind, "pre"),
        )
        val f3 = Bwu1Frame3(WifiVectors.bytes(bind, "hostPoint"), WifiVectors.bytes(bind, "hostNonce"))
        assertArrayEquals(WifiVectors.bytes(bind, "frame1"), f1.encode())
        assertArrayEquals(WifiVectors.bytes(bind, "frame2"), f2.encode())
        assertArrayEquals(WifiVectors.bytes(bind, "frame3"), f3.encode())
        assertEquals(WifiWire.BWU1_FRAME1_BYTES, f1.encode().size)
        assertEquals(WifiWire.BWU1_FRAME2_BYTES, f2.encode().size)
        assertEquals(WifiWire.BWU1_FRAME3_BYTES, f3.encode().size)
        assertArrayEquals(f1.challenge, WifiWire.decodeBwu1Frame1(f1.encode()).challenge)
        val d2 = WifiWire.decodeBwu1Frame2(f2.encode())
        assertArrayEquals(f2.ticket, d2.ticket)
        assertArrayEquals(f2.ephemeral, d2.ephemeral)
        assertArrayEquals(f2.nonce, d2.nonce)
        assertArrayEquals(f2.pre, d2.pre)
        val d3 = WifiWire.decodeBwu1Frame3(f3.encode())
        assertArrayEquals(f3.ephemeral, d3.ephemeral)
        assertArrayEquals(f3.nonce, d3.nonce)
    }

    @Test
    fun malformedBindingAndDiscoveryMessagesAreRefusedWithTheirListedError() {
        for (vector in WifiVectors.list("wireInvalid")) {
            val name = vector["name"] as String
            val raw = hex(vector["hex"] as String)
            val expected = WifiWireError.valueOf(vector["error"] as String)
            val actual = when {
                name.startsWith("bwu1 frame 1") -> errorOf { WifiWire.decodeBwu1Frame1(raw) }
                name.startsWith("bwu1 frame 2") -> errorOf { WifiWire.decodeBwu1Frame2(raw) }
                name.startsWith("bwu1 frame 3") -> errorOf { WifiWire.decodeBwu1Frame3(raw) }
                name.startsWith("mwd1") -> errorOf { WifiWire.decodeMwd1(raw) }
                name.startsWith("mwk1") -> errorOf { WifiWire.decodeMwk1(raw) }
                name.startsWith("mwa1") -> errorOf { WifiWire.decodeMwa1(raw) }
                else -> error("unknown vector $name")
            }
            assertEquals(name, expected, actual)
        }
    }

    @Test
    fun discoveryMessagesMatchTheVectorsAndTheAnnouncementIsARealBleAnnouncement() {
        val discovery = WifiVectors.section("discovery")
        val announcement = WifiVectors.bytes(discovery, "announcement")
        assertEquals(DiscoveryAnnouncementCodec.ENCODED_BYTES, announcement.size)
        // the 22 bytes are exactly what the Bluetooth announcement codec produces and accepts (rotating, version 5)
        val decoded = DiscoveryAnnouncementCodec.decode(announcement, acceptRotating = true)
        assertEquals(DiscoveryAnnouncement.ROTATING_VERSION, decoded.version)
        assertArrayEquals(announcement, DiscoveryAnnouncementCodec.encode(decoded))
        val mwd = Mwd1(announcement)
        assertArrayEquals(WifiVectors.bytes(discovery, "mwd1"), mwd.encode())
        assertArrayEquals(announcement, WifiWire.decodeMwd1(mwd.encode()).announcement)
        // MWD1 carries only rotating announcements: a fixed-prefix (version 2) one cannot be built nor decoded
        val fixedPrefix = announcement.copyOf().also { it[0] = DiscoveryAnnouncement.FIXED_PREFIX_VERSION.toByte() }
        assertThrows(IllegalArgumentException::class.java) { Mwd1(fixedPrefix) }
        val rawFixed = WifiVectors.bytes(discovery, "mwd1").copyOf().also { it[5] = DiscoveryAnnouncement.FIXED_PREFIX_VERSION.toByte() }
        assertEquals(WifiWireError.FIELD, errorOf { WifiWire.decodeMwd1(rawFixed) })
        // the Visible-by-name window offer (version 8) is a rotating ID too and travels like version 5
        val window = WifiVectors.bytes(discovery, "announcementWindow")
        assertArrayEquals(WifiVectors.bytes(discovery, "mwd1Window"), Mwd1(window).encode())
        assertArrayEquals(window, WifiWire.decodeMwd1(Mwd1(window).encode()).announcement)
        val nonce = WifiVectors.bytes(WifiVectors.section("keys"), "nativeNonce")
        assertArrayEquals(WifiVectors.bytes(discovery, "mwk1"), Mwk1(nonce).encode())
        assertArrayEquals(nonce, WifiWire.decodeMwk1(Mwk1(nonce).encode()).nonce)
        val port = (discovery["mwa1Port"] as Long).toInt()
        assertArrayEquals(WifiVectors.bytes(discovery, "mwa1"), Mwa1(nonce, port).encode())
        val back = WifiWire.decodeMwa1(Mwa1(nonce, port).encode())
        assertEquals(port, back.port)
        assertArrayEquals(nonce, back.nonce)
    }

    @Test
    fun changingAnArrayReadFromARecordNeverChangesWhatTheRecordSends() {
        val creds = Bwf.Creds(ByteArray(8) { 1 }, 1234, byteArrayOf(192.toByte(), 168.toByte(), 1, 1))
        val before = creds.encode()
        creds.ipv4.fill(0)
        creds.ticket.fill(9)
        assertArrayEquals(before, creds.encode())
        assertArrayEquals(before, WifiWire.decodeBwf1(creds.encode()).let { (it as Bwf.Creds).encode() })
    }

    @Test
    fun aBmh2HopMayCarryBwf1OnlyWithASignedBuildTagAtOrAboveTheFirstWifiRelease() {
        assertFalse(WifiWire.buildTagAllowsBwf1(null))
        assertTrue(WifiWire.buildTagAllowsBwf1(BuildTag(WifiWire.WIFI_FIRST_RELEASE, releaseKey = false)))
        assertTrue(WifiWire.buildTagAllowsBwf1(BuildTag(WifiWire.WIFI_FIRST_RELEASE + 1, releaseKey = true)))
        assertFalse(WifiWire.buildTagAllowsBwf1(BuildTag(WifiWire.WIFI_FIRST_RELEASE - 1, releaseKey = true))) // [R S2]
    }
}
