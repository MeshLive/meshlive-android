package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PeerLabelTest {
    @Test
    fun handleVectorsFromTheNamingDesign() {
        assertEquals("04106-1050R", IdentityHandle.fromPrefixHex("010203040506"))
        assertEquals("M6SC7-N75YR", IdentityHandle.fromPrefixHex("a1b2c3d4e5f6"))
        assertEquals("00000-00000", IdentityHandle.fromPrefixHex("000000000000"))
        assertEquals("ZZZZZ-ZZZZW", IdentityHandle.fromPrefixHex("FFFFFFFFFFFF"))
    }

    @Test
    fun contactNameElseHandleNeverHex() {
        val stranger = PeerLabels.forPeer("a1b2c3d4e5f6")
        assertNull(stranger.name)
        assertEquals("M6SC7-N75YR", stranger.displayName)
        // R5: strangers always show the full handle (a 25-bit short handle is cheap to grind).
        assertEquals("M6SC7-N75YR", stranger.listName())
        assertFalse(stranger.displayName.contains("a1b2"))
        val contact = PeerLabels.forPeer("a1b2c3d4e5f6", "Asha")
        assertEquals("Asha", contact.displayName)
        assertEquals(PeerLabel.NameKind.CONTACT, contact.nameKind)
    }

    @Test
    fun collidingShortHandlesShowTheFullHandle() {
        val a = "a1b2c3d4e5f6"
        val b = "a1b2c3d40000" // same first 25 bits
        val c = "010203040506"
        assertEquals(setOf(a, b), PeerLabels.collidingShortHandles(listOf(a, b, c)))
    }

    @Test
    fun handleParsingAcceptsAliasesAndRejectsNonCanonicalTail() {
        assertEquals("010203040506", IdentityHandle.parse("04106-1050R"))
        assertEquals("010203040506", IdentityHandle.parse("o41o6 io5or"))
        assertEquals("a1b2c3d4e5f6", IdentityHandle.parse("m6sc7n75yr"))
        assertEquals("ffffffffffff", IdentityHandle.parse("ZZZZZ-ZZZZW"))
        assertEquals("M6SC7-N75YR", IdentityHandle.canonical("m6sc7 n75yr"))
        assertNull(IdentityHandle.parse("ZZZZZ-ZZZZZ")) // last symbol's low 2 bits must be zero
        assertNull(IdentityHandle.parse("04106-1050"))
        assertNull(IdentityHandle.parse("04106-1050U")) // U is not a Crockford symbol
        assertEquals("M6SC7", IdentityHandle.parseShort("m6sc7"))
    }

    @Test
    fun selfClaimedNameIsQuotedBesideTheFullHandleAndClearedWithTheSession() {
        val prefix = "a1b2c3d4e5f6"
        SessionPeerLabels.update(prefix) { it.copy(selfName = "Asha") }
        try {
            val label = PeerLabels.forPeer(prefix)
            assertEquals(PeerLabel.NameKind.SELF_NAMED, label.nameKind)
            assertEquals("\u201CAsha\u201D \u00B7 M6SC7-N75YR", label.listName())
            // A verified contact's own label always wins over the claimed name.
            assertEquals("Mum", PeerLabels.forPeer(prefix, "Mum").listName())
        } finally {
            SessionPeerLabels.clear(prefix)
        }
        assertNull(PeerLabels.forPeer(prefix).name)
    }
}
