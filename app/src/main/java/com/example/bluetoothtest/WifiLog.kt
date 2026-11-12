package com.example.bluetoothtest

import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The single logger of the Wi-Fi carrier (S11): every Wi-Fi line goes through here, no address, name, SSID,
 * service name, ticket, key or PMK ever reaches a log. Addresses appear only as [hash] values (`h:` + 16 hex).
 * A logging helper never throws: a value of an unexpected type is cleaned, not rejected.
 */
internal object WifiLog {
    @Volatile
    var sink: (String) -> Unit = { body ->
        runCatching { Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + body) } // android.util.Log throws on the JVM; tests swap the sink [Am 11]
    }

    private val salt: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) } // process lifetime, never written to disk

    /**
     * Keys whose value is logged as "redacted" unless it is already a `hash()` result (`h:` + 16 hex), so
     * `"addr" to WifiLog.hash(x)` (A5's `ndp_up` line) is kept and a raw address under that key is not (a logging
     * helper never throws, a wrong key must not crash a call). [GAP+][A5]
     */
    val FORBIDDEN_KEYS: Set<String> =
        setOf("ssid", "name", "mac", "addr", "address", "ip", "ipv6", "ticket", "key", "pmk", "psk", "svc", "service")

    /** "h:" + SHA-256(salt | value)[0:8] as 16 lowercase hex: the only form of an address in a log. */
    fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").also {
            it.update(salt)
            it.update(value.toByteArray(Charsets.UTF_8))
        }.digest()
        val hex = StringBuilder(16)
        for (i in 0 until 8) {
            val b = digest[i].toInt() and 0xff
            hex.append("0123456789abcdef"[b ushr 4]).append("0123456789abcdef"[b and 0x0f])
        }
        return "h:$hex"
    }

    /** "<name>: k=v | k=v"; null values are omitted; every value goes through [clean]; a key in [FORBIDDEN_KEYS] gets "redacted" unless the value has the `hash()` shape. */
    fun line(name: String, vararg fields: Pair<String, Any?>): String = name + ": " + join(fields)

    /** "wifi_upgrade: event=<event> | k=v | ..." */
    fun upgrade(event: String, vararg fields: Pair<String, Any?>): String = line("wifi_upgrade", "event" to event, *fields)

    /** "wifi_caps: k=v | k=v" (ONE space after the colon, like every other line) [API] */
    fun caps(vararg fields: Pair<String, Any?>): String = line("wifi_caps", *fields)

    /** "carrier: hop=<h> | requested=<r> | effective=<e> | reason=<x>": all four values cleaned [API] */
    fun carrier(hop: String, requested: String, effective: String, reason: String): String =
        line("carrier", "hop" to hop, "requested" to requested, "effective" to effective, "reason" to reason)

    /** "wifi_discovery: event=<event> | k=v | ..." (A8; callers pass epoch, then reason, then the rest, in the order of A8's log table) [GAP+][X11] */
    fun discovery(event: String, vararg fields: Pair<String, Any?>): String = line("wifi_discovery", "event" to event, *fields)

    /** "wifi_switches: carrier=on|off | wider=on|off | direct=on|off" (A6 start-up line) [GAP+] */
    fun switches(carrier: Boolean, widerReach: Boolean, direct: Boolean): String =
        line("wifi_switches", "carrier" to onOff(carrier), "wider" to onOff(widerReach), "direct" to onOff(direct))

    fun emit(body: String) = sink(body)

    /** Removes anything that must never be logged: '|' and whitespace become '_', MAC-, IPv6- and long-hex-shaped text becomes "redacted", at most 64 characters. */
    fun clean(value: Any?): String {
        val text = value?.toString() ?: return ""
        if (HASH_SHAPE.matches(text)) return text // the only exempt form: a hash() result [API]
        val scrubbed = buildString(text.length) {
            for (c in text) {
                append(
                    when (c) {
                        '\r', '\n', '|', ' ', '\t' -> '_'
                        else -> c
                    },
                )
            }
        }
        if (REDACTED_SHAPES.any { it.containsMatchIn(scrubbed) }) return "redacted"
        return if (scrubbed.length > 64) scrubbed.substring(0, 64) else scrubbed
    }

    private val HASH_SHAPE = Regex("^h:[0-9a-f]{16}$") // exactly what hash() returns
    private val REDACTED_SHAPES = listOf(
        Regex("(?i)([0-9a-f]{2}[:-]){5}[0-9a-f]{2}"), // MAC
        Regex("(?i)(?<![0-9a-z])[0-9a-f]{12}(?![0-9a-z])"), // MAC without separators, as a standalone token [A4 review P3-4]
        Regex("(?i)[0-9a-f]{0,4}(:[0-9a-f]{0,4}){2,}"), // IPv6, including fe80::
        Regex("(?i)[0-9a-f]{16,}"), // ticket / key shaped hex
    )

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    private fun join(fields: Array<out Pair<String, Any?>>): String =
        fields.filter { it.second != null }.joinToString(" | ") { field ->
            val value = field.second.toString()
            "${field.first}=${if (field.first in FORBIDDEN_KEYS && !HASH_SHAPE.matches(value)) "redacted" else clean(field.second)}"
        }
}
