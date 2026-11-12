package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec

class WifiKeysTest {
    private val keys = WifiVectors.section("keys")
    private val bind = WifiVectors.section("bind")

    private fun k(key: String) = WifiVectors.bytes(keys, key)
    private fun b(key: String) = WifiVectors.bytes(bind, key)
    private fun s(key: String) = WifiVectors.text(keys, key)
    private fun hex(bytes: ByteArray) = WifiVectors.hex(bytes)

    private val curve: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }

    private fun privateKey(scalarHex: String): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(scalarHex, 16), curve))

    private fun fails(block: () -> Unit): Boolean = runCatching(block).isFailure

    @Test
    fun theCallKeyAndEveryNameDerivedFromItMatchTheVectors() {
        val uw = WifiKeys.uwForCall(k("exporter"))
        assertArrayEquals(k("uwCall"), uw)
        val ticket = k("ticket")
        assertEquals(s("serviceName"), WifiKeys.serviceName(uw, ticket))
        assertArrayEquals(k("pmk"), WifiKeys.pmk(uw, ticket))
        assertArrayEquals(k("knock"), WifiKeys.knock(uw, ticket, k("knockNonce")))
        assertArrayEquals(k("knock2"), WifiKeys.knock(uw, ticket, k("knockNonce2")))
        assertArrayEquals(
            k("awareChallenge"),
            WifiKeys.awareChallenge(uw, ticket, k("knockNonce"), k("awareChallengeNonce")),
        )
        assertArrayEquals(
            k("awareChallengeOtherKnock"),
            WifiKeys.awareChallenge(uw, ticket, k("knockNonce2"), k("awareChallengeNonce")),
        )
        assertEquals(s("ssid"), WifiKeys.directSsid(uw, ticket))
        assertEquals(s("psk"), WifiKeys.directPsk(uw, ticket))
        val ticket2 = k("ticket2")
        assertEquals(s("serviceNameTicket2"), WifiKeys.serviceName(uw, ticket2))
        assertEquals(s("ssidTicket2"), WifiKeys.directSsid(uw, ticket2))
        assertEquals(s("pskTicket2"), WifiKeys.directPsk(uw, ticket2))
        // The Aware host's port (PROTOCOL "Keys, names and passwords"), pinned by tools/wifi_reference.py.
        assertEquals(keys.getValue("port"), WifiKeys.port(uw, ticket).toLong())
        assertEquals(keys.getValue("portTicket2"), WifiKeys.port(uw, ticket2).toLong())
    }

    @Test
    fun theDiscoveryAndNativeNamesMatchTheVectors() {
        val epoch = keys.getValue("epoch") as Long
        assertEquals(s("widerServiceName"), WifiKeys.widerServiceName(epoch))
        assertEquals(s("widerServiceNameNextEpoch"), WifiKeys.widerServiceName(epoch + 1))
        assertNotEquals(WifiKeys.widerServiceName(epoch), WifiKeys.widerServiceName(epoch + 1))
        assertArrayEquals(k("nativePmk"), WifiKeys.nativePmk(epoch, k("nativeNonce")))
        assertNotEquals(hex(k("nativePmk")), hex(WifiKeys.nativePmk(epoch + 1, k("nativeNonce"))))
    }

    @Test
    fun aHopKeyIsTheSameFromBothEndsWhateverOrderTheDirectionalKeysAreGivenAndDiffersFromACallKey() {
        val a = k("hopKeyA")
        val bKey = k("hopKeyB")
        val uw = WifiKeys.uwForHop(a, bKey)
        assertArrayEquals(k("uwHop"), uw)
        assertArrayEquals(uw, WifiKeys.uwForHop(bKey, a))
        assertEquals(s("serviceNameHop"), WifiKeys.serviceName(uw, k("ticket")))
        assertEquals(keys.getValue("portHop"), WifiKeys.port(uw, k("ticket")).toLong())
        assertNotEquals(hex(uw), hex(k("uwCall")))
    }

    @Test
    fun wrongSizesAndEqualHopKeysAreRefused() {
        assertTrue(fails { WifiKeys.uwForCall(ByteArray(31)) })
        assertTrue(fails { WifiKeys.uwForHop(ByteArray(32), ByteArray(32)) })
        assertTrue(fails { WifiKeys.uwForHop(ByteArray(31), ByteArray(32) { 1 }) })
        assertTrue(fails { WifiKeys.serviceName(ByteArray(31), k("ticket")) })
        assertTrue(fails { WifiKeys.serviceName(k("uwCall"), ByteArray(7)) })
        assertTrue(fails { WifiKeys.widerServiceName(-1L) })
        assertTrue(fails { WifiKeys.knock(k("uwCall"), k("ticket"), ByteArray(15)) })
        assertTrue(fails { WifiKeys.knock(k("uwCall"), k("ticket"), ByteArray(17)) })
        assertTrue(fails { WifiKeys.awareChallenge(k("uwCall"), k("ticket"), ByteArray(15), ByteArray(16)) })
        assertTrue(fails { WifiKeys.awareChallenge(k("uwCall"), k("ticket"), ByteArray(16), ByteArray(17)) })
    }

    @Test
    fun aServiceNameIsSixteenLowercaseBase32CharactersStartingWithM() {
        val uw = k("uwCall")
        repeat(20) { i ->
            val name = WifiKeys.serviceName(uw, ByteArray(8) { (it * 31 + i).toByte() })
            assertEquals(16, name.length)
            assertTrue(name, name[0] == 'm' && name.all { it in "abcdefghijklmnopqrstuvwxyz234567" })
        }
    }

    @Test
    fun directNamesAndPasswordsPassTheRulesOfShareAppNearby() {
        val uw = k("uwCall")
        repeat(20) { i ->
            val ticket = ByteArray(8) { (it * 17 + i * 5).toByte() }
            val ssid = WifiKeys.directSsid(uw, ticket)
            assertTrue(ssid, Regex("^DIRECT-[abcdefghjkmnpqrstuvwxyz23456789]{2}-[abcdefghjkmnpqrstuvwxyz23456789]{6}$").matches(ssid))
            assertTrue(ssid, OfflineAppSharePolicy.validNetworkName(ssid))
            val psk = WifiKeys.directPsk(uw, ticket)
            assertEquals(43, psk.length)
            assertTrue(OfflineAppSharePolicy.validPassphrase(psk))
            val credentials = WifiKeys.directCredentials(uw, ticket) // [GAP+]
            assertEquals(ssid, credentials.ssid)
            assertEquals(psk, credentials.psk)
            assertFalse(credentials.toString().contains(ssid) || credentials.toString().contains(psk))
        }
    }

    @Test
    fun aKnockIsCheckedInConstantTimeAndOnlyWithTheRightSize() {
        val uw = k("uwCall")
        val ticket = k("ticket")
        val knock = k("knock")
        val knock2 = k("knock2")
        val gN = k("knockNonce")
        val gN2 = k("knockNonce2")
        val challenge = k("awareChallenge")
        assertTrue(WifiKeys.knockMatches(uw, ticket, knock))
        assertTrue(WifiKeys.knockMatches(uw, ticket, knock2))
        assertFalse(WifiKeys.knockMatches(uw, k("ticket2"), knock))
        assertFalse(WifiKeys.knockMatches(uw, ticket, knock.copyOf(31)))
        assertFalse(WifiKeys.knockMatches(uw, ticket, knock + byteArrayOf(0)))
        assertFalse(WifiKeys.knockMatches(uw, ticket, knock.copyOf().also { it[16] = (it[16] + 1).toByte() }))
        assertFalse(WifiKeys.knockMatches(uw, ticket, knock.copyOf().also { it[0] = (it[0] + 1).toByte() }))
        // the challenge is bound to the knock's gN and to the ticket
        assertTrue(WifiKeys.challengeMatches(uw, ticket, gN, challenge))
        assertFalse(WifiKeys.challengeMatches(uw, ticket, gN2, challenge)) // a genuine challenge replayed on another handle
        assertFalse(WifiKeys.challengeMatches(uw, k("ticket2"), gN, challenge))
        assertFalse(WifiKeys.challengeMatches(uw, ticket, gN, challenge.copyOf(31)))
        assertFalse(WifiKeys.challengeMatches(uw, ticket, gN, challenge + byteArrayOf(0)))
        assertFalse(WifiKeys.challengeMatches(uw, ticket, gN, challenge.copyOf().also { it[16] = (it[16] + 1).toByte() }))
    }

    @Test
    fun aPreProofVerifiesForItsChallengeAndNotForAnotherOne() {
        val uw = k("uwCall")
        val frame2 = WifiWire.decodeBwu1Frame2(b("frame2"))
        assertArrayEquals(b("pre"), WifiKeys.pre(uw, frame2.ticket, b("challenge"), frame2.ephemeral, frame2.nonce))
        assertTrue(WifiKeys.preMatches(uw, frame2, b("challenge")))
        // the same frame 2 replayed against the next socket's fresh challenge fails
        assertFalse(WifiKeys.preMatches(uw, frame2, b("challenge2")))
        assertArrayEquals(
            b("preWithSecondChallenge"),
            WifiKeys.pre(uw, frame2.ticket, b("challenge2"), frame2.ephemeral, frame2.nonce),
        )
        // another call's key does not verify it
        assertFalse(WifiKeys.preMatches(k("uwHop"), frame2, b("challenge")))
    }

    @Test
    fun bothEndsDeriveTheVectorTranscriptSecretAndCarrierKeys() {
        val uw = k("uwCall")
        val ticket = k("ticket")
        val dhFromGuest = WifiKeys.dh(privateKey(WifiVectors.text(bind, "guestScalar")), b("hostPoint"))
        val dhFromHost = WifiKeys.dh(privateKey(WifiVectors.text(bind, "hostScalar")), b("guestPoint"))
        assertArrayEquals(b("dh"), dhFromGuest)
        assertArrayEquals(b("dh"), dhFromHost)
        val th = WifiKeys.transcriptHash(b("frame1"), b("frame2"), b("frame3"))
        assertArrayEquals(b("th"), th)
        val salt = WifiKeys.bindSalt(uw, ticket, th)
        assertArrayEquals(b("salt"), salt)
        assertArrayEquals(b("K"), WifiKeys.bindPrk(salt, dhFromGuest))
        WifiKeys.bindKeys(uw, ticket, th, dhFromGuest).use { guest ->
            WifiKeys.bindKeys(uw, ticket, th, dhFromHost).use { host ->
                assertArrayEquals(b("kG2H"), guest.sendKey(isGuest = true))
                assertArrayEquals(b("kH2G"), guest.receiveKey(isGuest = true))
                assertArrayEquals(b("kH2G"), host.sendKey(isGuest = false))
                assertArrayEquals(b("kG2H"), host.receiveKey(isGuest = false))
                // one end's send key is the other end's receive key
                assertArrayEquals(guest.sendKey(true), host.receiveKey(false))
                assertArrayEquals(host.sendKey(false), guest.receiveKey(true))
            }
        }
        assertNotEquals(hex(b("kG2H")), hex(b("kH2G")))
    }

    @Test
    fun aChangedTranscriptOrTicketChangesTheCarrierKeys() {
        val uw = k("uwCall")
        val dh = b("dh")
        val th = b("th")
        val changed = th.copyOf().also { it[0] = (it[0] + 1).toByte() }
        WifiKeys.bindKeys(uw, k("ticket"), th, dh).use { base ->
            WifiKeys.bindKeys(uw, k("ticket"), changed, dh).use { other ->
                assertNotEquals(hex(base.sendKey(true)), hex(other.sendKey(true)))
            }
            WifiKeys.bindKeys(uw, k("ticket2"), th, dh).use { other ->
                assertNotEquals(hex(base.sendKey(true)), hex(other.sendKey(true)))
            }
        }
    }

    @Test
    fun closingTheCarrierKeysWipesThem() {
        val keysForTest = WifiKeys.bindKeys(k("uwCall"), k("ticket"), b("th"), b("dh"))
        keysForTest.close()
        assertTrue(keysForTest.sendKey(true).all { it.toInt() == 0 })
        assertTrue(keysForTest.receiveKey(true).all { it.toInt() == 0 })
    }

    @Test
    fun aPointOffTheCurveIsRefusedByTheSharedCheck() {
        val off = b("offCurveGuestPoint")
        assertTrue(Bmh3Crypto.isOnCurve(b("guestPoint")))
        assertTrue(Bmh3Crypto.isOnCurve(b("hostPoint")))
        assertFalse(Bmh3Crypto.isOnCurve(off))
        assertTrue(runCatching { WifiKeys.dh(privateKey(WifiVectors.text(bind, "hostScalar")), off) }.exceptionOrNull() is Bmh3Failure)
    }
    @Test
    fun theDerivedAwarePortIsStableInRangeAndBoundToTheAttempt() {
        val uw = ByteArray(32) { (it + 1).toByte() }
        val ticket = ByteArray(8) { (it + 20).toByte() }
        val port = WifiKeys.port(uw, ticket)
        assertTrue("port $port", port in AwarePorts.MIN..AwarePorts.MAX)
        assertEquals(port, WifiKeys.port(uw.copyOf(), ticket.copyOf()))
        // Another attempt or another hop key gives another port (16384 values: these fixed inputs differ).
        val others = (0 until 64).map { i -> WifiKeys.port(uw, ByteArray(8) { (it + 20 + i + 1).toByte() }) }
        assertTrue(others.toSet().size > 32)
        assertNotEquals(port, WifiKeys.port(ByteArray(32) { (it + 2).toByte() }, ticket))
        assertTrue(fails { WifiKeys.port(ByteArray(31), ticket) })
    }
}
