package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROTOCOL `port` (W5, the rule since 2026-11-10) is what a person's launch runs, in a debug build and in a release build
 * alike: the facts a launch produces have the rule on and a host attempt listens on the derived port. Only a DEBUG launch with
 * `test_wifi_aware_derived_port=false` turns it off (the control arm). Runs on both variants:
 * `:app:testGithubReleaseUnitTest --tests '*WifiAwarePortRuleTest'`.
 */
class WifiAwarePortRuleTest {
    private val uw = ByteArray(32) { (it + 1).toByte() }
    private val ticket = byteArrayOf(0xa1.toByte(), 0xb2.toByte(), 0xc3.toByte(), 0xd4.toByte(), 0xe5.toByte(), 0xf6.toByte(), 0x07, 0x18)

    /** A launch as MainActivity performs it: the extra's value, or the default (true) when the launch names none. */
    private fun <T> launchedWith(value: Boolean, block: () -> T): T {
        val before = DebugTunables.wifiAwareDerivedPort
        DebugTunables.wifiAwareDerivedPort = value
        try {
            return block()
        } finally {
            DebugTunables.wifiAwareDerivedPort = before
        }
    }

    /** The port the host attempt passes to the data-path request and to its server socket, and its port log line. */
    private fun hostPorts(facts: WifiFacts): Triple<Int, Int, List<String>> {
        val env = FakeEnv().also { it.factsValue = facts }
        val ports = FakeWifiPorts(isLinkInitiator = false)
        val attempt = WifiAttempt(AttemptRole.HOST, 5L, ticket.copyOf(), uw.copyOf(), env.decide(env.facts()), env, ports, false, null) { _, _, _ -> }
        val link = env.links[5L] ?: error("link")
        attempt.start()
        env.scheduler.drain()
        env.scheduler.drain()
        link.deliver(7, WifiKeys.knock(uw, ticket, ByteArray(16) { 3 }))
        env.scheduler.drain()
        attempt.onPick(true, link.messages.last().second.copyOfRange(0, 16))
        env.scheduler.drain()
        link.ready(listOf(5180), portKnown = false)
        env.scheduler.drain()
        return Triple(link.lastResponderPort, link.lastListenPort, env.lines.filter { it.contains("event=port") })
    }

    @Test
    fun aLaunchThatNamesNoArmHostsOnTheDerivedPort() {
        val facts = launchedWith(true) { WifiDebugArms.adjust(FakeEnv().factsValue) }
        assertTrue(facts.derivedPort)
        val (responder, listen, lines) = hostPorts(facts)
        assertEquals(WifiKeys.port(uw, ticket), responder)
        assertEquals(WifiKeys.port(uw, ticket), listen)
        assertTrue(lines.toString(), lines.any { it.contains("source=derived") })
    }

    @Test
    fun onlyADebugLaunchCanTurnTheRuleOff() {
        val facts = launchedWith(false) { WifiDebugArms.adjust(FakeEnv().factsValue) }
        assertEquals(!BuildConfig.DEBUG, facts.derivedPort)
        val (responder, listen, lines) = hostPorts(facts)
        assertEquals(responder, listen)
        if (BuildConfig.DEBUG) {
            assertTrue(lines.toString(), lines.any { it.contains("source=random") })
        } else {
            assertEquals(WifiKeys.port(uw, ticket), responder)
        }
    }
}
