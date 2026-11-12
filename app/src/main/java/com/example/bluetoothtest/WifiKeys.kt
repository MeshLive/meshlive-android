package com.example.bluetoothtest

import java.io.Closeable
import java.nio.ByteBuffer
import java.security.PrivateKey
import java.util.Base64

/** The two directional `BMS1` keys of a Wi-Fi carrier (PROTOCOL "Binding"). [close] wipes them. */
internal class WifiBindKeys(guestToHost: ByteArray, hostToGuest: ByteArray) : Closeable {
    private val g2h = guestToHost.copyOf()
    private val h2g = hostToGuest.copyOf()

    init {
        require(g2h.size == 32 && h2g.size == 32) { "carrier keys are 32 bytes" }
    }

    /** A copy of the key this phone seals records with (the guest sends with `k_g2h`, the host with `k_h2g`). */
    fun sendKey(isGuest: Boolean): ByteArray = (if (isGuest) g2h else h2g).copyOf()

    /** A copy of the key this phone opens records with. */
    fun receiveKey(isGuest: Boolean): ByteArray = (if (isGuest) h2g else g2h).copyOf()

    override fun close() {
        g2h.fill(0)
        h2g.fill(0)
    }
}

/** The Wi-Fi Direct group name and password of one attempt; never logged or sent. [GAP+] */
internal class DirectCredentials(val ssid: String, val psk: String) {
    override fun toString(): String = "DirectCredentials(redacted)"
}

/**
 * Design WF revision 3 key derivations (PROTOCOL "Keys, names and passwords", "Binding"). Pure functions of byte arrays;
 * the caller owns and wipes `uw`. Wrong sizes are programming errors (`IllegalArgumentException`). `UW` itself is kept by its
 * session from key creation until the hop or call ends and by the emergency wipe; a Wi-Fi switch change never wipes it.
 * [R S6, M-5]
 */
internal object WifiKeys {
    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private val INFO_KEY = ascii("BluetoothVideoMesh/WifiUpgrade/1/key")
    private val SALT_HOP = ascii("BluetoothVideoMesh/WifiUpgrade/1/hop")
    private val LABEL_BIND = ascii("BluetoothVideoMesh/WifiUpgrade/1/bind")
    private val INFO_G2H = ascii("BluetoothVideoMesh/WifiUpgrade/1/g2h")
    private val INFO_H2G = ascii("BluetoothVideoMesh/WifiUpgrade/1/h2g")
    private val DISCOVERY_DOMAIN = ascii("BluetoothVideoMesh/WifiDiscovery/1")
    private val NATIVE_DOMAIN = ascii("BluetoothVideoMesh/WifiNative/1")
    private val SVC = ascii("svc")
    private val PMK = ascii("pmk")
    private val PORT = ascii("port")
    private val KNOCK = ascii("knock")
    private val CHAL = ascii("chal")
    private val PRE = ascii("pre")
    private val BIND = ascii("bind")
    private val SSID = ascii("ssid")
    private val PSK = ascii("psk")

    private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"
    private const val SSID_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789" // 31 characters = OfflineAppShare.UNAMBIGUOUS

    private fun needUw(uw: ByteArray) = require(uw.size == 32) { "UW is 32 bytes" }
    private fun needTicket(ticket: ByteArray) = require(ticket.size == WifiWire.TICKET_BYTES) { "ticket is 8 bytes" }

    /** Lowercase RFC 4648 base32 without padding. */
    internal fun base32Lower(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(BASE32[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1) // keep only the bits not yet written
        }
        if (bits > 0) out.append(BASE32[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    private fun epochBytes(epoch: Long): ByteArray {
        require(epoch >= 0) { "epoch is not negative" }
        return ByteBuffer.allocate(8).putLong(epoch).array()
    }

    /** `UW` of a call: `HKDF-Expand(exporter, "BluetoothVideoMesh/WifiUpgrade/1/key", 32)`; [exporter] is the call's BMH3 end-to-end exporter. */
    fun uwForCall(exporter: ByteArray): ByteArray {
        require(exporter.size == 32) { "the exporter is 32 bytes" }
        return Bmh3Crypto.expand(exporter, INFO_KEY)
    }

    /**
     * `UW` of a stream or relayed-call hop from the hop's two directional `BMS1` keys, in either order (they are ordered
     * bytewise, unsigned, so both ends agree). Equal keys are refused.
     */
    fun uwForHop(keyA: ByteArray, keyB: ByteArray): ByteArray {
        require(keyA.size == 32 && keyB.size == 32) { "hop keys are 32 bytes" }
        val order = compareUnsigned(keyA, keyB)
        require(order != 0) { "the two directional keys of a hop differ" }
        val (low, high) = if (order < 0) keyA to keyB else keyB to keyA
        val concatenated = low + high
        return try {
            val prk = Bmh3Crypto.extract(SALT_HOP, concatenated)
            try {
                Bmh3Crypto.expand(prk, INFO_KEY)
            } finally {
                prk.fill(0)
            }
        } finally {
            concatenated.fill(0)
        }
    }

    /** Unsigned lexicographic order over every byte without an early exit (the keys are secret; review A0, reviewer 5). */
    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        var result = 0
        for (i in a.indices) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            val unset = ((result or -result) shr 31).inv() // -1 while no difference was seen, else 0
            result = result or (d and unset)
        }
        return result
    }

    /** Wi-Fi Aware service name of an attempt: `"m" | b32(HMAC(UW, "svc" | ticket))[0:15]`, 16 characters. */
    fun serviceName(uw: ByteArray, ticket: ByteArray): String {
        needUw(uw); needTicket(ticket)
        return "m" + base32Lower(Bmh3Crypto.hmac(uw, SVC, ticket)).take(15)
    }

    /** The data-path PMK: `HMAC(UW, "pmk" | ticket)`. */
    fun pmk(uw: ByteArray, ticket: ByteArray): ByteArray {
        needUw(uw); needTicket(ticket)
        return Bmh3Crypto.hmac(uw, PMK, ticket)
    }

    /**
     * The attempt's Wi-Fi Aware server port (PROTOCOL "Keys, names and passwords" `port`, the rule since 2026-11-10; DEBUG
     * `test_wifi_aware_derived_port=false` is the random-port control arm): `49152 + (u32be(HMAC(UW, "port" | ticket)[0:4]) mod 16384)`.
     * Both ends know `UW` and the ticket, so the guest can connect even when the platform reports no port. The port is not a secret (the
     * data path and the BWU1 handshake carry the authentication); deriving it adds no exposure and removes no check.
     */
    fun port(uw: ByteArray, ticket: ByteArray): Int {
        needUw(uw); needTicket(ticket)
        val mac = Bmh3Crypto.hmac(uw, PORT, ticket)
        val word = ((mac[0].toLong() and 0xff) shl 24) or ((mac[1].toLong() and 0xff) shl 16) or
            ((mac[2].toLong() and 0xff) shl 8) or (mac[3].toLong() and 0xff)
        return AwarePorts.MIN + (word % (AwarePorts.MAX - AwarePorts.MIN + 1)).toInt()
    }

    /**
     * The guest's knock to ONE discovered handle: `gN (16 fresh random bytes) | HMAC(UW, "knock" | ticket | gN)[0:16]`,
     * 32 bytes. The nonce is per handle, so a spoofed handle's answer cannot be replayed onto the genuine one. [F-1]
     */
    fun knock(uw: ByteArray, ticket: ByteArray, gN: ByteArray): ByteArray {
        needUw(uw); needTicket(ticket)
        require(gN.size == 16) { "the knock nonce is 16 bytes" }
        return gN + Bmh3Crypto.hmac(uw, KNOCK, ticket, gN).copyOf(16)
    }

    /**
     * The host's answer to one verified knock: `c (16 fresh random bytes) | HMAC(UW, "chal" | ticket | gN | c)[0:16]`,
     * 32 bytes, bound to the `gN` of the knock it answers. [F-1]
     */
    fun awareChallenge(uw: ByteArray, ticket: ByteArray, gN: ByteArray, c: ByteArray): ByteArray {
        needUw(uw); needTicket(ticket)
        require(gN.size == 16 && c.size == 16) { "the challenge nonces are 16 bytes" }
        return c + Bmh3Crypto.hmac(uw, CHAL, ticket, gN, c).copyOf(16)
    }

    /** Constant-time check of a received 32-byte knock; false for any other size. Recomputes with `gN = received[0:16]`. [F-1] */
    fun knockMatches(uw: ByteArray, ticket: ByteArray, received: ByteArray): Boolean {
        if (received.size != 32) return false
        val expected = knock(uw, ticket, received.copyOfRange(0, 16))
        return Bmh3Crypto.constantTimeEquals(expected, received)
    }

    /**
     * Constant-time check that a received 32-byte challenge answers [gN]; false for any other size. The guest calls it with
     * the nonce it sent to that same handle, so a replayed genuine challenge on another handle fails. [F-1]
     */
    fun challengeMatches(uw: ByteArray, ticket: ByteArray, gN: ByteArray, received: ByteArray): Boolean {
        if (gN.size != 16 || received.size != 32) return false
        val expected = awareChallenge(uw, ticket, gN, received.copyOfRange(0, 16))
        return Bmh3Crypto.constantTimeEquals(expected, received)
    }

    /** Wi-Fi Direct network name: `"DIRECT-" | a2 | "-" | a6`, character i picked by the big-endian u16 of bytes 2i and 2i+1 of `HMAC(UW, "ssid" | ticket)` mod 31. */
    fun directSsid(uw: ByteArray, ticket: ByteArray): String {
        needUw(uw); needTicket(ticket)
        val bytes = Bmh3Crypto.hmac(uw, SSID, ticket)
        val chars = CharArray(8) { SSID_ALPHABET[(((bytes[2 * it].toInt() and 0xff) shl 8) or (bytes[2 * it + 1].toInt() and 0xff)) % 31] }
        return "DIRECT-" + String(chars, 0, 2) + "-" + String(chars, 2, 6)
    }

    /** Wi-Fi Direct password: `base64url(HMAC(UW, "psk" | ticket))[0:43]` (no padding; 43 characters). */
    fun directPsk(uw: ByteArray, ticket: ByteArray): String {
        needUw(uw); needTicket(ticket)
        val digest = Bmh3Crypto.hmac(uw, PSK, ticket)
        return try {
            Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(43)
        } finally {
            digest.fill(0) // review A0 (reviewer 4): the raw password digest never outlives the call
        }
    }

    /** The Wi-Fi Direct name and password of one attempt as one redacted object. [GAP+] */
    fun directCredentials(uw: ByteArray, ticket: ByteArray): DirectCredentials =
        DirectCredentials(directSsid(uw, ticket), directPsk(uw, ticket))

    /** Wider reach discovery service name of a rotating-ID epoch: `"m" | b32(SHA-256(domain | epoch_be8))[0:15]`. */
    fun widerServiceName(epoch: Long): String =
        "m" + base32Lower(Bmh3Crypto.sha256(DISCOVERY_DOMAIN + epochBytes(epoch))).take(15)

    /** PMK of a Wi-Fi-native data path: `HMAC(SHA-256(domain | epoch_be8), "pmk" | nonce)`; public-derived on purpose. */
    fun nativePmk(epoch: Long, nonce: ByteArray): ByteArray {
        require(nonce.size == WifiWire.NATIVE_NONCE_BYTES) { "nonce is 8 bytes" }
        return Bmh3Crypto.hmac(Bmh3Crypto.sha256(NATIVE_DOMAIN + epochBytes(epoch)), PMK, nonce)
    }

    /** The guest's pre-proof: `HMAC(UW, "pre" | ticket | challenge | e_G | n_G)[0:16]`. */
    fun pre(uw: ByteArray, ticket: ByteArray, challenge: ByteArray, ephemeral: ByteArray, nonce: ByteArray): ByteArray {
        needUw(uw); needTicket(ticket)
        require(challenge.size == WifiWire.CHALLENGE_BYTES && ephemeral.size == WifiWire.POINT_BYTES && nonce.size == WifiWire.NONCE_BYTES)
        return Bmh3Crypto.hmac(uw, PRE, ticket, challenge, ephemeral, nonce).copyOf(WifiWire.PRE_BYTES)
    }

    /**
     * Host side, constant time: does [frame2]'s `pre` prove knowledge of `UW` for [challenge]? The host calls this BEFORE it
     * consumes the ticket, validates the point or does any other work.
     */
    fun preMatches(uw: ByteArray, frame2: Bwu1Frame2, challenge: ByteArray): Boolean =
        Bmh3Crypto.constantTimeEquals(pre(uw, frame2.ticket, challenge, frame2.ephemeral, frame2.nonce), frame2.pre)

    /** `th = SHA-256("BluetoothVideoMesh/WifiUpgrade/1/bind" | 0x00 | frame1 | frame2 | frame3)`, whole frames with their magic. */
    fun transcriptHash(frame1: ByteArray, frame2: ByteArray, frame3: ByteArray): ByteArray =
        Bmh3Crypto.sha256(LABEL_BIND + byteArrayOf(0) + frame1 + frame2 + frame3)

    /** ECDH x coordinate (32 bytes). Throws `Bmh3Failure` for a peer point off the curve; call it only after `preMatches`. */
    fun dh(privateKey: PrivateKey, peerPoint: ByteArray): ByteArray = Bmh3Crypto.agree(privateKey, peerPoint)

    /** The HKDF-Extract salt `HMAC(UW, "bind" | ticket | th)`. */
    fun bindSalt(uw: ByteArray, ticket: ByteArray, th: ByteArray): ByteArray {
        needUw(uw); needTicket(ticket)
        return Bmh3Crypto.hmac(uw, BIND, ticket, th)
    }

    /** `K = HKDF-Extract(salt, dh)`. */
    fun bindPrk(salt: ByteArray, dh: ByteArray): ByteArray = Bmh3Crypto.extract(salt, dh)

    /** The carrier keys `k_g2h`, `k_h2g` from `K`. Wipes [prk]. */
    fun carrierKeys(prk: ByteArray): WifiBindKeys {
        val g2h = Bmh3Crypto.expand(prk, INFO_G2H)
        val h2g = Bmh3Crypto.expand(prk, INFO_H2G)
        val keys = WifiBindKeys(g2h, h2g) // copies both
        g2h.fill(0)
        h2g.fill(0)
        prk.fill(0)
        return keys
    }

    /** All of the above in one call; wipes the intermediate secrets. */
    fun bindKeys(uw: ByteArray, ticket: ByteArray, th: ByteArray, dh: ByteArray): WifiBindKeys {
        val salt = bindSalt(uw, ticket, th)
        val keys = carrierKeys(bindPrk(salt, dh))
        salt.fill(0)
        return keys
    }
}
