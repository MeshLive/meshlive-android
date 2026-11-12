package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket

class WifiHopAgentTest {
    private val ticket = ByteArray(8) { (it + 21).toByte() }
    private val uw = ByteArray(32) { (it + 3).toByte() }

    private class Rig(
        val env: FakeEnv = FakeEnv(),
        val ports: FakeWifiPorts = FakeWifiPorts(),
        val initiator: Boolean = false,
        inert: Boolean = false,
    ) {
        val agent = WifiHopAgent(ports, env, inert = inert)
    }

    private fun Rig.responderCaps(peer: Bwf.Caps = Bwf.Caps(1, 2, 2)): Rig {
        agent.onCallActive()
        env.scheduler.drain()
        agent.onRecord(0, peer.encode())
        env.scheduler.drain()
        return this
    }

    private fun Rig.initiatorCaps(peer: Bwf.Caps = Bwf.Caps(1, 2, 2)): Rig {
        agent.onRecord(0, peer.encode())
        env.scheduler.drain()
        agent.onCallActive()
        env.scheduler.drain()
        return this
    }

    private fun Rig.startHostAttempt(): WifiAttempt {
        agent.onVideo(remote = true)
        env.scheduler.advance(WifiAttemptLimits.VIDEO_STABLE_MS)
        return currentAttempt(agent) ?: error("no attempt: ${env.lines}")
    }

    private fun field(agent: WifiHopAgent, name: String): Any? {
        val f = WifiHopAgent::class.java.getDeclaredField(name).also { it.isAccessible = true }
        return f.get(agent)
    }

    private fun currentAttempt(agent: WifiHopAgent): WifiAttempt? = field(agent, "current") as WifiAttempt?

    private fun phaseOf(attempt: WifiAttempt): Phase {
        val modelField = WifiAttempt::class.java.getDeclaredField("model").also { it.isAccessible = true }
        return (modelField.get(attempt) as AttemptModel).phase
    }

    private fun plannerOf(agent: WifiHopAgent): WifiHopPlanner = field(agent, "planner") as WifiHopPlanner

    private fun linkOf(env: FakeEnv, generation: Long): FakeAware = env.links[generation] ?: error("no link $generation")

    private fun send(agent: WifiHopAgent, record: Bwf) = agent.onRecord(0, record.encode())

    private fun statusLine(agent: WifiHopAgent, env: FakeEnv): String {
        agent.logStatus()
        env.scheduler.drain()
        return env.lines.lastOrNull { it.contains("event=status") }.orEmpty()
    }

    private fun sentDeclines(ports: FakeWifiPorts): List<Bwf.Decline> = ports.sent.filterIsInstance<Bwf.Decline>()

    @Test
    fun responderSendsCapsOnlyWhenActiveRevealsDoneAndOptedIn() {
        val rig = Rig()
        rig.ports.callActiveValue = false
        rig.agent.onCallActive()
        rig.env.scheduler.drain()
        assertTrue(rig.ports.sent.isEmpty())

        rig.ports.callActiveValue = true
        rig.ports.revealsDoneValue = false
        rig.agent.onRevealProgress()
        rig.env.scheduler.drain()
        assertTrue(rig.ports.sent.isEmpty())

        rig.ports.revealsDoneValue = true
        rig.ports.optedInValue = false
        rig.agent.onRevealProgress()
        rig.env.scheduler.drain()
        assertTrue(rig.ports.sent.isEmpty())
        assertTrue(rig.env.lines.any { it.contains("not_opted_in") })

        rig.ports.optedInValue = true
        rig.agent.onRevealProgress()
        rig.env.scheduler.drain()
        assertEquals(1, rig.ports.sent.filterIsInstance<Bwf.Caps>().size)
    }

    @Test
    fun initiatorWaitsForThePeersCaps() {
        val rig = Rig(ports = FakeWifiPorts(isLinkInitiator = true), initiator = true)
        rig.agent.onCallActive()
        rig.env.scheduler.drain()
        assertTrue(rig.ports.sent.isEmpty())
        rig.agent.onRecord(0, Bwf.Caps(1, 2, 2).encode())
        rig.env.scheduler.drain()
        assertEquals(1, rig.ports.sent.filterIsInstance<Bwf.Caps>().size)
        assertTrue(statusLine(rig.agent, rig.env).contains("tuple=aware/guest"))
    }

    @Test
    fun capsAreSentWithEveryBitClearWhenTheSwitchIsOff() {
        val env = FakeEnv()
        env.factsValue = env.factsValue.copy(switches = WifiSwitches(carrier = false, widerReach = false, direct = false))
        val ports = FakeWifiPorts()
        val agent = WifiHopAgent(ports, env)
        agent.onCallActive()
        env.scheduler.drain()
        val caps = ports.sent.filterIsInstance<Bwf.Caps>().single()
        assertEquals(0, caps.bearers)
        assertEquals(0, caps.freeHost)
        assertEquals(0, caps.freeGuest)
        assertEquals(9, caps.encode().size) // four body bytes, the fourth reserved 0

        val oldProbe = DebugTunables.wifiProbe
        DebugTunables.wifiProbe = true
        if (!BuildConfig.DEBUG) {
            // The probe is a DEBUG arm: a release build cannot set it.
            assertFalse(DebugTunables.wifiProbe)
            return
        }
        try {
            val probeEnv = FakeEnv()
            val probePorts = FakeWifiPorts()
            val probe = WifiHopAgent(probePorts, probeEnv)
            probe.onCallActive()
            probeEnv.scheduler.drain()
            assertEquals(0, probePorts.sent.filterIsInstance<Bwf.Caps>().single().bearers)
        } finally {
            DebugTunables.wifiProbe = oldProbe
        }
    }

    @Test
    fun aRepeatedPeerCapsEndsWifiForTheHop() {
        val rig = Rig().responderCaps()
        rig.agent.onRecord(0, Bwf.Caps(1, 2, 2).encode())
        assertEquals(1, rig.ports.carrierSwitchOffCalls)
        assertTrue(rig.ports.protocolViolations.isEmpty())
        assertEquals(WifiHopPlanner.Verdict.Never("violation"), plannerOf(rig.agent).canStart(0))
        assertTrue(rig.env.lines.any { it.contains("caps_repeat") })
    }

    @Test
    fun aNonCapsRecordBeforeBothCapsEndsWifiForTheHop() {
        val rig = Rig()
        rig.agent.onRecord(0, Bwf.Host(1, 1, ticket, 20_000, 0).encode())
        assertEquals(1, rig.ports.carrierSwitchOffCalls)
        assertEquals(WifiHopPlanner.Verdict.Never("violation"), plannerOf(rig.agent).canStart(0))
    }

    @Test
    fun anUnexpectedKindEndsWifiForTheHop() {
        val rig = Rig().responderCaps()
        rig.agent.onRecord(0, Bwf.Bind(WifiWire.ROLE_GUEST, ticket).encode())
        assertEquals(1, rig.ports.carrierSwitchOffCalls)
        assertTrue(rig.ports.protocolViolations.isEmpty())
        assertTrue(rig.env.lines.any { it.contains("unexpected_kind_6") })
    }

    @Test
    fun anUnknownKindIsIgnored() {
        val rig = Rig().responderCaps()
        val before = rig.ports.sent.size
        rig.agent.onRecord(0, WifiWire.MAGIC_BWF1 + byteArrayOf(12) + ByteArray(4))
        assertEquals(before, rig.ports.sent.size)
        assertEquals(0, rig.ports.carrierSwitchOffCalls)
        assertTrue(rig.env.lines.any { it.contains("kind_ignored") })
    }

    @Test
    fun aViolationOnAWifiCarrierClosesOnlyThatCarrier() {
        val rig = Rig().responderCaps()
        rig.agent.onRecord(3, Bwf.Host(1, 1, ticket, 20_000, 0).encode())
        assertEquals(listOf(3 to "record_on_carrier"), rig.ports.protocolViolations)
        assertEquals(0, rig.ports.carrierSwitchOffCalls)
    }

    @Test
    fun hostStartsOnlyAfterThreeSecondsOfVideo() {
        val rig = Rig().responderCaps()
        rig.agent.onVideo(remote = true)
        rig.env.scheduler.advance(2_999)
        assertNull(currentAttempt(rig.agent))
        rig.env.scheduler.advance(1)
        assertNotNull(currentAttempt(rig.agent))

        val audio = Rig().responderCaps()
        audio.agent.onVideo(remote = false)
        audio.env.scheduler.advance(10_000)
        assertNull(currentAttempt(audio.agent))

        val off = Rig().responderCaps()
        off.agent.onVideo(remote = true)
        off.env.scheduler.advance(2_000)
        off.agent.onVideo(remote = false)
        off.env.scheduler.advance(10_000)
        assertNull(currentAttempt(off.agent))
    }

    @Test
    fun renewalBlocksAndCancels() {
        val rig = Rig().responderCaps()
        rig.ports.renewalRunningValue = true
        rig.agent.onVideo(remote = true)
        rig.env.scheduler.advance(12_000)
        assertNull(currentAttempt(rig.agent))
        rig.ports.renewalRunningValue = false
        rig.env.scheduler.advance(5_000)
        val attempt = currentAttempt(rig.agent)
        assertNotNull(attempt)
        rig.env.scheduler.drain()
        rig.agent.onRenewalStarting()
        rig.env.scheduler.drain()
        assertTrue(sentDeclines(rig.ports).any { it.reason == 2 })
        assertEquals(0, plannerOf(rig.agent).failedAttempts)
        assertEquals(0, plannerOf(rig.agent).noPenaltyEnds)
        val now = rig.env.scheduler.nowMs()
        val verdict = plannerOf(rig.agent).canStart(0)
        assertTrue(verdict is WifiHopPlanner.Verdict.Wait)
        assertTrue((verdict as WifiHopPlanner.Verdict.Wait).untilMs >= now + 29_000)
    }

    @Test
    fun failedAttemptsFollowTheRetryRule() {
        val rig = Rig().responderCaps()
        val first = rig.startHostAttempt()
        rig.env.scheduler.drain()
        linkOf(rig.env, first.generation).awareDown(AwareDownReason.WIFI_OFF)
        rig.env.scheduler.drain()
        assertEquals(1, plannerOf(rig.agent).failedAttempts)
        val linksAfterFirst = rig.env.links.size
        rig.env.scheduler.advance(29_000)
        assertEquals(linksAfterFirst, rig.env.links.size)
        rig.env.scheduler.advance(1_000)
        val second = currentAttempt(rig.agent) ?: error("no second attempt: ${rig.env.lines}")
        rig.env.scheduler.drain()
        linkOf(rig.env, second.generation).awareDown(AwareDownReason.WIFI_OFF)
        rig.env.scheduler.drain()
        assertEquals(2, plannerOf(rig.agent).failedAttempts)
        val linksAfterSecond = rig.env.links.size
        rig.env.scheduler.advance(600_000)
        assertEquals(linksAfterSecond, rig.env.links.size)
        assertEquals(WifiHopPlanner.Verdict.Never("failed_twice"), plannerOf(rig.agent).canStart(0))
    }

    @Test
    fun noPenaltyEndingsAreCapped() {
        val rig = Rig().responderCaps()
        repeat(3) {
            rig.startHostAttempt()
            rig.env.scheduler.drain()
            val attempt = currentAttempt(rig.agent) ?: return@repeat
            send(rig.agent, Bwf.Decline(attempt.ticket, 1))
            rig.env.scheduler.drain()
            rig.env.scheduler.advance(30_000)
        }
        assertEquals(3, plannerOf(rig.agent).noPenaltyEnds)
        val links = rig.env.links.size
        rig.env.scheduler.advance(600_000)
        assertEquals(links, rig.env.links.size)
        assertEquals(WifiHopPlanner.Verdict.Never("declined_thrice"), plannerOf(rig.agent).canStart(0))
    }

    @Test
    fun aBusyPeerIsRetriedAfterTheCooldown() {
        val rig = Rig().responderCaps()
        rig.agent.onVideo(remote = true)
        rig.env.scheduler.advance(3_000)
        val waits = listOf(30_000L, 60_000L, 120_000L, 240_000L)
        repeat(4) { index ->
            val attempt = currentAttempt(rig.agent) ?: error("attempt $index missing: ${rig.env.lines}")
            send(rig.agent, Bwf.Decline(attempt.ticket, 2))
            rig.env.scheduler.drain()
            assertEquals(0, plannerOf(rig.agent).noPenaltyEnds)
            if (index < waits.size - 1) {
                rig.env.scheduler.advance(waits[index] - 1)
                assertNull("early attempt $index", currentAttempt(rig.agent))
                rig.env.scheduler.advance(1)
            }
        }
        // The fourth busy end waits 240 s; then the fifth attempt starts. [Am 7]
        rig.env.scheduler.advance(240_000 - 1)
        assertNull("early fifth attempt", currentAttempt(rig.agent))
        rig.env.scheduler.advance(1)
        assertNotNull("no fifth attempt: ${rig.env.lines}", currentAttempt(rig.agent))
    }

    private fun Rig.busyEnd() {
        val attempt = currentAttempt(agent) ?: error("attempt missing: ${env.lines}")
        send(agent, Bwf.Decline(attempt.ticket, 2))
        env.scheduler.drain()
        assertNull(currentAttempt(agent))
    }

    private fun Rig.assertNextAttemptAfter(waitMs: Long) {
        env.scheduler.advance(waitMs - 1)
        assertNull("attempt before ${waitMs} ms", currentAttempt(agent))
        env.scheduler.advance(1)
        assertNotNull("no attempt at ${waitMs} ms: ${env.lines}", currentAttempt(agent))
    }

    @Test
    fun aStrangersOrAnotherHopsReleaseDuringAnAttemptDoesNotResetTheBusyBackOff() {
        // Another hop's reservation released while our attempt runs.
        val other = Rig()
        assertTrue(other.env.ledger.reserveNdp("hop-other", 4))
        other.responderCaps()
        other.agent.onVideo(remote = true)
        other.env.scheduler.advance(3_000)
        assertNotNull(currentAttempt(other.agent))
        other.env.ledger.releaseNdp("hop-other")
        other.env.scheduler.drain()
        other.busyEnd()
        other.assertNextAttemptAfter(30_000)
        other.busyEnd()
        other.assertNextAttemptAfter(60_000)

        // A stranger's pending native reservation preempted by our host (F-3); its owner releases it in reaction.
        val stranger = Rig()
        stranger.env.factsValue = stranger.env.factsValue.copy(freeNdp = 1)
        val capacity = WifiFactsReader.reserveCapacity(stranger.env.ledger, 1)
        assertTrue(stranger.env.ledger.reserveUntrustedNdp("stranger", capacity) { stranger.env.ledger.releaseNdp("stranger") })
        stranger.responderCaps()
        stranger.agent.onVideo(remote = true)
        stranger.env.scheduler.advance(3_000)
        assertNotNull("no attempt: ${stranger.env.lines}", currentAttempt(stranger.agent))
        stranger.busyEnd()
        stranger.assertNextAttemptAfter(30_000)
        stranger.busyEnd()
        stranger.assertNextAttemptAfter(60_000)
    }

    @Test
    fun anotherOwnersReleaseBetweenAttemptsResetsTheBusyBackOff() {
        val rig = Rig()
        assertTrue(rig.env.ledger.reserveNdp("hop-other", 4))
        rig.responderCaps()
        rig.agent.onVideo(remote = true)
        rig.env.scheduler.advance(3_000)
        rig.busyEnd()
        rig.assertNextAttemptAfter(30_000)
        rig.busyEnd() // streak 2: the next wait would be 60 s
        rig.env.ledger.releaseNdp("hop-other") // a path freed on this phone between attempts
        rig.env.scheduler.drain()
        rig.assertNextAttemptAfter(60_000)
        rig.busyEnd() // the streak restarted at 1 after the release: 30 s again
        rig.assertNextAttemptAfter(30_000)
    }

    @Test
    fun aReleasedDataPathTriggersAHostCheck() {
        val rig = Rig().responderCaps()
        assertTrue(rig.env.ledger.reserveNdp("other", 4))
        rig.ports.renewalRunningValue = true
        rig.agent.onVideo(remote = true)
        rig.env.scheduler.advance(3_000)
        assertNull(currentAttempt(rig.agent))
        rig.ports.renewalRunningValue = false
        rig.env.ledger.releaseNdp("other")
        rig.env.scheduler.drain()
        assertNotNull("release listener did not schedule a host check: ${rig.env.lines}", currentAttempt(rig.agent))
    }

    @Test
    fun anAgentWantingWifiBlocksTheLastPathForStrangers() {
        val rig = Rig().responderCaps()
        rig.env.factsValue = rig.env.factsValue.copy(freeNdp = 1)
        rig.startHostAttempt()
        rig.env.scheduler.drain()
        assertEquals(1, rig.env.ledger.ndpReserved)
        val capacity = WifiFactsReader.reserveCapacity(rig.env.ledger, rig.env.facts().freeNdp)
        assertFalse(rig.env.ledger.reserveUntrustedNdp("stranger", capacity) {})
        rig.agent.onCallEnded()
        rig.env.scheduler.drain()
        assertTrue(rig.env.ledger.reserveUntrustedNdp("stranger", capacity) {})
        rig.env.ledger.releaseNdp("stranger")
    }

    private fun guestAgent(env: FakeEnv = FakeEnv()): Pair<FakeWifiPorts, WifiHopAgent> {
        val ports = FakeWifiPorts(isLinkInitiator = true)
        val agent = WifiHopAgent(ports, env)
        agent.onRecord(0, Bwf.Caps(1, 2, 2).encode())
        env.scheduler.drain()
        agent.onCallActive()
        env.scheduler.drain()
        return ports to agent
    }

    @Test
    fun guestAnswersWithTheRightDeclineCode() {
        fun declineFor(
            setup: (FakeEnv, FakeWifiPorts) -> Unit = { _, _ -> },
            host: Bwf.Host = Bwf.Host(1, 1, ticket, 20_000, 0),
            notDesignatedGuest: Boolean = false,
        ): Int? {
            val env = FakeEnv()
            val (ports, agent) = if (notDesignatedGuest) {
                val rig = Rig().responderCaps()
                rig.ports to rig.agent
            } else {
                guestAgent(env)
            }
            setup(env, ports)
            agent.onRecord(0, host.encode())
            env.scheduler.drain()
            return sentDeclines(ports).lastOrNull()?.reason
                ?: error("no decline for host $host: ${env.lines}")
        }
        assertEquals(2, declineFor(notDesignatedGuest = true))
        assertEquals(1, declineFor(host = Bwf.Host(1, 2, ticket, 20_000, 5180)))
        assertEquals(1, declineFor(host = Bwf.Host(1, 1, ByteArray(8), 20_000, 0)))
        assertEquals(5, declineFor(setup = { env, _ -> env.factsValue = env.factsValue.copy(batteryPercent = 5) }))
        assertEquals(3, declineFor(setup = { env, _ -> env.factsValue = env.factsValue.copy(wifiEnabled = false) }))
        assertEquals(1, declineFor(setup = { env, _ -> env.factsValue = env.factsValue.copy(switches = WifiSwitches(false, false, false)) }))
        assertEquals(2, declineFor(setup = { _, ports -> ports.renewalRunningValue = true }))
        assertEquals(2, declineFor(setup = { _, ports -> ports.hasWifiCarrierValue = true }))
        assertEquals(1, declineFor(setup = { env, _ -> env.screenOnValue = false }))
        assertEquals(2, declineFor(setup = { env, _ -> env.factsValue = env.factsValue.copy(freeNdp = 0) }))
        assertEquals(1, declineFor(setup = { _, ports -> ports.noKey = true }))
    }

    @Test
    fun guestServesAGoodHostAndStaysUnderTheLimits() {
        val env = FakeEnv()
        val (ports, agent) = guestAgent(env)
        repeat(6) { index ->
            send(agent, Bwf.Host(1, 1, ByteArray(8) { (it + 30 + index).toByte() }, 20_000, 0))
            env.scheduler.drain()
            val attempt = currentAttempt(agent) ?: error("host $index not served")
            attempt.command(Cmd.HANGUP)
            env.scheduler.drain()
            assertEquals(index + 1, plannerOf(agent).guestHostsAccepted)
            env.scheduler.advance(29_000)
        }
        send(agent, Bwf.Host(1, 1, ByteArray(8) { (it + 60).toByte() }, 20_000, 0))
        env.scheduler.drain()
        assertEquals(4, sentDeclines(ports).last().reason)
    }

    @Test
    fun staleRecordsAreIgnored() {
        val rig = Rig().responderCaps()
        val before = rig.ports.sent.size
        send(rig.agent, Bwf.Pick(ticket, ByteArray(16)))
        send(rig.agent, Bwf.Armed(ticket))
        send(rig.agent, Bwf.Decline(ticket, 1))
        rig.env.scheduler.drain()
        assertEquals(before, rig.ports.sent.size)
        assertTrue(rig.env.lines.any { it.contains("pick_stale") })
        assertTrue(rig.env.lines.any { it.contains("decline_stale") })
    }

    @Test
    fun switchOffMidAttempt() {
        val rig = Rig().responderCaps()
        val attempt = rig.startHostAttempt()
        rig.env.scheduler.drain()
        assertEquals(1, rig.env.ledger.ndpReserved)
        rig.agent.onSwitchOff()
        rig.env.scheduler.drain()
        assertTrue(sentDeclines(rig.ports).any { it.reason == 1 })
        assertEquals(1, linkOf(rig.env, attempt.generation).closeCount)
        assertEquals(0, rig.env.ledger.ndpReserved)
        assertEquals(1, rig.ports.carrierSwitchOffCalls)
        assertEquals(WifiHopPlanner.Verdict.Never("switch_off"), plannerOf(rig.agent).canStart(0))
        val links = rig.env.links.size
        rig.env.scheduler.advance(600_000)
        assertEquals(links, rig.env.links.size)
    }

    /** A host hop whose Wi-Fi carrier (generation 1) was handed and is now lost; returns the loss time. */
    private fun Rig.hostAfterCarrierLoss(): Long {
        agent.onVideo(remote = true)
        ports.hasWifiCarrierValue = true
        env.scheduler.advance(WifiAttemptLimits.VIDEO_STABLE_MS)
        assertNull(currentAttempt(agent))
        val handed = WifiHopAgent::class.java.getDeclaredField("handed").also { it.isAccessible = true }
        handed.set(agent, HandedCarrier(1, true, 5180, ByteArray(8) { 7 }))
        ports.hasWifiCarrierValue = false
        val lostAt = env.scheduler.nowMs
        agent.onCarrierLost()
        env.scheduler.drain()
        return lostAt
    }

    @Test
    fun aRecheckThatFiresAfterTheCallEndedStartsNoAttemptAndTakesNoKey() {
        val rig = Rig().responderCaps()
        val lostAt = rig.hostAfterCarrierLoss()
        rig.env.scheduler.advance(WifiLimits.RETRY_DELAY_MS - 10)
        assertNull(currentAttempt(rig.agent))
        // The call ends on its own thread: the phase is ENDED at once, the posted onCallEnded runs later. The key
        // is still there (uwCopy would return it), so only the call state can stop the recheck.
        rig.ports.callActiveValue = false
        val keysBefore = rig.ports.issuedCopies.size
        val linksBefore = rig.env.links.size
        rig.env.scheduler.advance(10)
        assertEquals(lostAt + WifiLimits.RETRY_DELAY_MS, rig.env.scheduler.nowMs)
        assertNull(currentAttempt(rig.agent))
        assertEquals(linksBefore, rig.env.links.size)
        assertEquals("uwCopy must not be called after the call ended", keysBefore, rig.ports.issuedCopies.size)
        rig.agent.onCallEnded()
        rig.env.scheduler.advance(60_000)
        assertNull(currentAttempt(rig.agent))
        assertEquals(keysBefore, rig.ports.issuedCopies.size)
        assertEquals(1, rig.env.lines.count { it.contains("event=host_skip") && it.contains("reason=call_ended") })
    }

    @Test
    fun whileTheCallIsActiveTheNextAttemptStartsThirtySecondsAfterTheLoss() {
        val rig = Rig().responderCaps()
        val lostAt = rig.hostAfterCarrierLoss()
        rig.env.scheduler.advance(WifiLimits.RETRY_DELAY_MS - 10)
        assertNull(currentAttempt(rig.agent))
        rig.env.scheduler.advance(10)
        val attempt = currentAttempt(rig.agent) ?: error("no attempt at loss + 30 s: ${rig.env.lines}")
        assertEquals(lostAt + WifiLimits.RETRY_DELAY_MS, rig.env.scheduler.nowMs)
        assertTrue(rig.env.lines.none { it.contains("reason=call_ended") })
        attempt.command(Cmd.HANGUP)
        rig.env.scheduler.drain()
    }

    @Test
    fun aGuestWhoseCallEndedDeclinesAHostWithoutTakingTheKey() {
        val env = FakeEnv()
        val (ports, agent) = guestAgent(env)
        ports.callActiveValue = false
        val keysBefore = ports.issuedCopies.size
        send(agent, Bwf.Host(1, 1, ticket, 20_000, 0))
        env.scheduler.drain()
        assertNull(currentAttempt(agent))
        assertEquals(1, sentDeclines(ports).last().reason)
        assertEquals(keysBefore, ports.issuedCopies.size)
        assertTrue(env.lines.any { it.contains("event=host_skip") && it.contains("reason=call_ended") })
    }

    @Test
    fun hangUpMidAttempt() {
        val rig = Rig().responderCaps()
        val attempt = rig.startHostAttempt()
        rig.env.scheduler.drain()
        val declinesBefore = sentDeclines(rig.ports).size
        rig.agent.onCallEnded()
        rig.env.scheduler.drain()
        assertEquals(1, linkOf(rig.env, attempt.generation).closeCount)
        assertEquals(declinesBefore, sentDeclines(rig.ports).size)
        assertEquals(0, rig.env.ledger.ndpReserved)
        linkOf(rig.env, attempt.generation).awareDown(AwareDownReason.UNAVAILABLE)
        rig.env.scheduler.drain()
        assertEquals(1, linkOf(rig.env, attempt.generation).closeCount)
        rig.agent.onCallEnded()
        rig.env.scheduler.drain()
        assertEquals(1, linkOf(rig.env, attempt.generation).closeCount)
    }

    @Test
    fun wipeEqualsHangUp() {
        val rig = Rig().responderCaps()
        val attempt = rig.startHostAttempt()
        rig.env.scheduler.drain()
        rig.agent.onWipe()
        rig.env.scheduler.drain()
        assertEquals(1, linkOf(rig.env, attempt.generation).closeCount)
        assertTrue(rig.env.lines.any { it.contains("reason=wipe") })
    }

    @Test
    fun forceCarrierBluetoothNeverStartsAndLogsTheRequestedCarrier() {
        // test_wifi_force_carrier is a DEBUG arm; a release build always tries the normal carriers.
        assumeTrue("DEBUG arm", BuildConfig.DEBUG)
        val old = DebugTunables.wifiForceCarrier
        DebugTunables.wifiForceCarrier = "bluetooth"
        try {
            val rig = Rig().responderCaps()
            rig.agent.onVideo(remote = true)
            rig.env.scheduler.advance(30_000)
            assertNull(currentAttempt(rig.agent))
            val forced = rig.env.lines.filter { it.startsWith("carrier:") && it.contains("reason=forced") }
            assertEquals(1, forced.size)
            assertTrue(forced.single().contains("requested=bluetooth"))
            assertTrue(forced.single().contains("effective=bluetooth"))
        } finally {
            DebugTunables.wifiForceCarrier = old
        }
    }

    @Test
    fun levelIsWiderWhenTheWiderReachSwitchIsOnLogOnly() {
        val env = FakeEnv()
        env.factsValue = env.factsValue.copy(switches = WifiSwitches(carrier = true, widerReach = true, direct = false))
        val ports = FakeWifiPorts()
        val agent = WifiHopAgent(ports, env)
        agent.onCallActive()
        env.scheduler.drain()
        assertTrue(env.lines.any { it.contains("level=wider") })
        val caps = ports.sent.filterIsInstance<Bwf.Caps>().single()
        assertEquals(0, caps.encode()[8].toInt()) // fourth CAPS byte stays reserved
    }

    @Test
    fun anAgentCreatedWithEverySwitchOffRunsInlineAndNeverPosts() {
        val env = FakeEnv()
        env.factsValue = env.factsValue.copy(switches = WifiSwitches(false, false, false))
        val ports = FakeWifiPorts()
        val agent = WifiHopAgent(ports, env)
        agent.onCallActive()
        agent.onRevealProgress()
        assertEquals(1, ports.sent.filterIsInstance<Bwf.Caps>().size)
        agent.onRecord(0, Bwf.Caps(1, 2, 2).encode())
        agent.onRecord(0, Bwf.Host(1, 1, ticket, 20_000, 0).encode())
        assertEquals(1, sentDeclines(ports).single().reason)
        agent.onRecord(0, Bwf.Caps(1, 2, 2).encode())
        assertEquals(1, ports.carrierSwitchOffCalls)
        agent.onCallEnded()
        assertEquals(0, env.scheduler.postCount)
        assertEquals(0, env.scheduler.postDelayedCount)
        assertTrue(env.links.isEmpty())
    }

    @Test
    fun anExceptionInAnAgentEntryPointEndsWifiForTheHopAndKeepsBluetooth() {
        val rig = Rig()
        rig.ports.revealsError = IllegalStateException("owner state at 10.9.8.7")
        rig.agent.onCallActive() // runs inline on the wifi thread: must not throw
        rig.env.scheduler.drain()
        assertEquals(1, rig.ports.carrierSwitchOffCalls)
        assertTrue(rig.ports.protocolViolations.isEmpty())
        assertEquals(WifiHopPlanner.Verdict.Never("violation"), plannerOf(rig.agent).canStart(0))
        assertTrue(rig.env.lines.any { it.contains("internal_error") && it.contains("IllegalStateException") })
        assertTrue(rig.env.lines.none { it.contains("10.9.8.7") })
        // The hop keeps answering on Bluetooth: a later HOST is declined, nothing throws.
        rig.ports.revealsError = null
        rig.agent.onRecord(0, Bwf.Caps(1, 2, 2).encode())
        rig.env.scheduler.drain()
        rig.agent.onCallEnded()
        rig.env.scheduler.drain()
        assertEquals(1, rig.ports.carrierSwitchOffCalls)
    }

    @Test
    fun anExceptionInAnAgentTimerEndsWifiForTheHop() {
        val rig = Rig()
        rig.ports.revealsDoneValue = false
        rig.agent.onCallActive()
        rig.env.scheduler.drain()
        rig.ports.revealsError = IllegalArgumentException()
        rig.env.scheduler.advance(WifiAttemptLimits.CAPS_POLL_MS) // the CAPS poll timer body throws
        assertEquals(1, rig.ports.carrierSwitchOffCalls)
        assertTrue(rig.env.lines.any { it.contains("internal_error") && it.contains("IllegalArgumentException") })
        val posted = rig.env.scheduler.postDelayedCount
        rig.env.scheduler.advance(60_000) // no timer keeps running after Wi-Fi ended for the hop
        assertEquals(posted, rig.env.scheduler.postDelayedCount)
    }

    @Test
    fun aCallFromAnotherThreadIsPostedAndTheAttemptRefusesForeignThreads() {
        val rig = Rig()
        rig.env.scheduler.strictThreads = true
        rig.agent.onCallActive() // the call's thread: queued for the wifi thread, nothing runs inline
        assertTrue(rig.ports.sent.isEmpty())
        rig.env.scheduler.drain()
        assertEquals(1, rig.ports.sent.filterIsInstance<Bwf.Caps>().size)
        rig.agent.onRecord(0, Bwf.Caps(1, 2, 2).encode())
        rig.env.scheduler.drain()
        rig.agent.onVideo(remote = true)
        rig.env.scheduler.advance(WifiAttemptLimits.VIDEO_STABLE_MS)
        val attempt = currentAttempt(rig.agent) ?: error("no attempt: ${rig.env.lines}")
        // X7: an attempt is driven only from the wifi thread.
        var refused = false
        try {
            attempt.command(Cmd.HANGUP)
        } catch (_: IllegalStateException) {
            refused = true
        }
        assertTrue(refused)
        assertNotNull(currentAttempt(rig.agent))
        rig.agent.onCallEnded()
        assertNotNull(currentAttempt(rig.agent)) // still queued
        rig.env.scheduler.drain()
        assertNull(currentAttempt(rig.agent))
    }

    @Test
    fun aRelaysCapsReadBeforeTheFirstHookIsAnswered() {
        val ports = FakeWifiPorts(isLinkInitiator = true)
        ports.callActiveValue = false
        val env = FakeEnv()
        val agent = WifiHopAgent(ports, env)
        agent.onRecord(0, Bwf.Caps(1, 2, 2).encode())
        env.scheduler.drain()
        assertTrue(ports.sent.filterIsInstance<Bwf.Caps>().isEmpty())
        ports.callActiveValue = true
        agent.onCallActive()
        env.scheduler.drain()
        assertEquals(1, ports.sent.filterIsInstance<Bwf.Caps>().size)
    }

    @Test
    fun bothEndsOfARelayEdgeChooseTheHopKeyAndBothEndsOfADirectEdgeTheEndToEndKey() {
        val endpoint = ByteArray(32) { 1 }
        val hop = ByteArray(32) { 2 }
        val relay = NodeId.fromBytes(ByteArray(16) { 3 })
        val caller = NodeId.fromBytes(ByteArray(16) { 4 })
        assertTrue(linkPeerIsRelay(relay, caller))
        assertTrue(linkPeerIsRelay(caller, relay))
        assertFalse(linkPeerIsRelay(caller, caller))
        assertTrue(linkPeerIsRelay(null, caller))
        assertTrue(wifiKeyFor(true, endpoint, hop) === hop)
        assertTrue(wifiKeyFor(false, endpoint, hop) === endpoint)
        assertTrue(wifiKeyFor(true, null, null) == null)
    }

    @Test
    fun onCapsExchangedAdoptsTheOwnersCapsAndSendsNone() {
        val env = FakeEnv()
        val ports = FakeWifiPorts(isLinkInitiator = false, hopKind = WifiHopKind.STREAM)
        val agent = WifiHopAgent(ports, env)
        env.scheduler.advance(5_000) // the CAPS poll runs, but a stream hop never writes CAPS
        assertTrue(ports.sent.isEmpty())
        agent.onCapsExchanged(Bwf.Caps(1, 2, 2), Bwf.Caps(1, 2, 2))
        assertTrue(ports.sent.isEmpty())
        assertEquals("aware/host", statusLine(agent, env).substringAfter("tuple=").substringBefore(" "))
        agent.onVideo(remote = true)
        env.scheduler.advance(3_000)
        assertNotNull(currentAttempt(agent))
        assertTrue(ports.sent.filterIsInstance<Bwf.Caps>().isEmpty())
    }

    @Test
    fun aSecondCapsAfterOnCapsExchangedEndsWifiForTheHop() {
        val env = FakeEnv()
        val ports = FakeWifiPorts(hopKind = WifiHopKind.STREAM)
        val agent = WifiHopAgent(ports, env)
        agent.onCapsExchanged(Bwf.Caps(1, 2, 2), Bwf.Caps(1, 2, 2))
        agent.onRecord(0, Bwf.Caps(1, 2, 2).encode())
        assertEquals(1, ports.carrierSwitchOffCalls)
    }

    @Test
    fun aCreditedDirectBitIsMaskedWithoutADriver() {
        val env = FakeEnv()
        env.factsValue = env.factsValue.copy(
            featureDirect = true,
            switches = WifiSwitches(carrier = true, widerReach = false, direct = true),
        )
        val ports = FakeWifiPorts()
        val agent = WifiHopAgent(ports, env)
        agent.onCallActive()
        env.scheduler.drain()
        val caps = ports.sent.filterIsInstance<Bwf.Caps>().single()
        assertEquals(0, caps.bearers and Bwf.Caps.BEARER_DIRECT_GO)
        assertEquals(0, caps.bearers and Bwf.Caps.BEARER_DIRECT_CLIENT)
    }

    @Test
    fun aWindowsOnlyGroupOwnerBitIsNotJoinedWhileWindowsHostJoinIsUnproven() {
        val env = FakeEnv()
        env.factsValue = env.factsValue.copy(
            featureDirect = true,
            switches = WifiSwitches(carrier = true, widerReach = false, direct = true),
        )
        env.supported += WifiBearer.DIRECT
        val ports = FakeWifiPorts(isLinkInitiator = true)
        val agent = WifiHopAgent(ports, env)
        agent.onRecord(0, Bwf.Caps(Bwf.Caps.BEARER_DIRECT_GO, 0, 0).encode())
        env.scheduler.drain()
        agent.onCallActive()
        env.scheduler.drain()
        assertNull("no Direct tuple when only the Windows group-owner bit is offered", currentAttempt(agent))
        assertFalse(env.lines.any { it.contains("tuple=direct") })
    }

    @Test
    fun aPeerClientBitStillAllowsTheDirectTuple() {
        val env = FakeEnv()
        env.factsValue = env.factsValue.copy(
            featureDirect = true,
            switches = WifiSwitches(carrier = true, widerReach = false, direct = true),
        )
        env.supported += WifiBearer.DIRECT
        val ports = FakeWifiPorts(isLinkInitiator = true)
        val agent = WifiHopAgent(ports, env)
        agent.onRecord(0, Bwf.Caps(Bwf.Caps.BEARER_DIRECT_CLIENT, 0, 0).encode())
        env.scheduler.drain()
        agent.onCallActive()
        env.scheduler.drain()
        assertTrue(env.lines.any { it.contains("tuple=direct") })
    }

    @Test
    fun aCallWithoutAKeyDeclinesAndSendsAllClearCaps() {
        val env = FakeEnv()
        val ports = FakeWifiPorts().also { it.noKey = true }
        val agent = WifiHopAgent(ports, env)
        agent.onCallActive()
        env.scheduler.drain()
        assertEquals(0, ports.sent.filterIsInstance<Bwf.Caps>().single().bearers)
        agent.onRecord(0, Bwf.Caps(1, 2, 2).encode())
        env.scheduler.drain()
        agent.onRecord(0, Bwf.Host(1, 1, ticket, 20_000, 0).encode())
        assertEquals(1, sentDeclines(ports).single().reason)
        assertNull(currentAttempt(agent))
    }

    @Test
    fun kindsSixAndSevenOnCarrierZeroEndWifiForTheHop() {
        for (record in listOf(
            Bwf.Bind(WifiWire.ROLE_GUEST, ticket) as Bwf,
            Bwf.Ready(1, 0, 1) as Bwf,
        )) {
            val rig = Rig().responderCaps()
            rig.agent.onRecord(0, record.encode())
            assertEquals(1, rig.ports.carrierSwitchOffCalls)
        }
    }

    @Test
    fun aCredsRecordWithoutADirectAttemptIsRejected() {
        val rig = Rig().responderCaps()
        rig.agent.onRecord(0, Bwf.Creds(ticket, 4545, byteArrayOf(10, 0, 0, 1)).encode())
        assertEquals(1, rig.ports.carrierSwitchOffCalls)
        assertTrue(rig.env.lines.any { it.contains("unexpected_kind_11") })
    }

    @Test
    fun aCloseRecordIsLoggedAndIgnored() {
        val rig = Rig()
        rig.agent.onRecord(0, Bwf.Close(1).encode())
        rig.agent.onRecord(2, Bwf.Close(1).encode())
        assertEquals(0, rig.ports.carrierSwitchOffCalls)
        assertEquals(0, rig.ports.protocolViolations.size)
        assertEquals(2, rig.env.lines.count { it.contains("close_ignored") })
    }

    @Test
    fun anAllZeroTicketIsNeverDrawn() {
        val rig = Rig().responderCaps()
        rig.startHostAttempt()
        rig.env.scheduler.drain()
        val host = rig.ports.sent.filterIsInstance<Bwf.Host>().single()
        assertFalse(host.ticket.all { it == 0.toByte() })
    }

    @Test
    fun aDirectGuestStillConnectsNineteenSecondsAfterHost() {
        val env = FakeEnv()
        env.factsValue = env.factsValue.copy(
            featureDirect = true,
            switches = WifiSwitches(carrier = true, widerReach = false, direct = true),
        )
        env.supported += WifiBearer.DIRECT
        lateinit var driver: FakeBearerDriver
        env.driverFactory = { gen, events ->
            FakeBearerDriver(generation = gen, events = events, scheduler = env.scheduler).also { driver = it }
        }
        val (server, client) = directSocketPair()
        env.binderValue = okBinder(bindBound(client))
        val ports = FakeWifiPorts(isLinkInitiator = true)
        val agent = WifiHopAgent(ports, env)
        agent.onRecord(0, Bwf.Caps(Bwf.Caps.BEARER_AWARE or Bwf.Caps.BEARER_DIRECT_GO or Bwf.Caps.BEARER_DIRECT_CLIENT, 0, 0).encode())
        env.scheduler.drain()
        agent.onCallActive()
        env.scheduler.drain()
        send(agent, Bwf.Host(1, WifiWire.BEARER_DIRECT, ticket, 20_000, 5180))
        env.scheduler.drain()
        env.scheduler.advance(1_000)
        send(agent, Bwf.Creds(ticket, 4545, byteArrayOf(192.toByte(), 168.toByte(), 49, 1)))
        env.scheduler.drain()
        env.scheduler.advance(12_000) // now 13 s
        driver.joined(5180)
        env.scheduler.drain()
        env.scheduler.advance(6_000) // now 19 s
        driver.connected(client)
        env.scheduler.drain()
        val done = env.scheduler.runUntil(10_000) { ports.carriers.isNotEmpty() }
        assertTrue("no Direct carrier: ${env.lines}", done)
        assertEquals(CarrierKind.WIFI_DIRECT, ports.carriers.single().kind)
        server.close()
    }

    @Test
    fun credsRightBehindHostIsNotLost() {
        val env = FakeEnv()
        env.factsValue = env.factsValue.copy(
            featureDirect = true,
            switches = WifiSwitches(carrier = true, widerReach = false, direct = true),
        )
        env.supported += WifiBearer.DIRECT
        lateinit var driver: FakeBearerDriver
        env.driverFactory = { gen, events ->
            FakeBearerDriver(generation = gen, events = events, scheduler = env.scheduler).also { driver = it }
        }
        val ports = FakeWifiPorts(isLinkInitiator = true)
        val agent = WifiHopAgent(ports, env)
        agent.onRecord(0, Bwf.Caps(Bwf.Caps.BEARER_AWARE or Bwf.Caps.BEARER_DIRECT_GO or Bwf.Caps.BEARER_DIRECT_CLIENT, 0, 0).encode())
        env.scheduler.drain()
        agent.onCallActive()
        env.scheduler.drain()
        agent.onRecord(0, Bwf.Host(1, WifiWire.BEARER_DIRECT, ticket, 20_000, 5180).encode())
        agent.onRecord(0, Bwf.Creds(ticket, 4545, byteArrayOf(192.toByte(), 168.toByte(), 49, 1)).encode())
        env.scheduler.drain()
        driver.joined(5180)
        env.scheduler.drain()
        assertEquals(1, driver.calls.count { it == "connect:4545" })
        assertFalse(env.lines.any { it.contains("creds_timeout") })
    }

    private fun directSocketPair(): Pair<Socket, Socket> {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val client = Socket()
        client.connect(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), server.localPort), 2_000)
        val accepted = server.accept()
        server.close()
        return accepted to client
    }

    private fun bindBound(socket: Socket): WifiBinding.Bound = WifiBinding.Bound(
        socket,
        SecureRecordReader(socket.getInputStream(), ByteArray(32) { 1 }),
        SecureRecordWriter(socket.getOutputStream(), ByteArray(32) { 2 }),
        Bwf.Ready(1, 0, 1),
        DeadlineInputStream(socket) { 0 },
    )

    private fun okBinder(bound: WifiBinding.Bound) = object : WifiBinder {
        override fun host(socket: Socket, acceptedAtNs: Long, uw: ByteArray, gate: TicketGate, ticket: ByteArray, ready: Bwf.Ready): WifiBinding.HostResult =
            WifiBinding.HostResult.Ok(bound)
        override fun guest(socket: Socket, connectedAtNs: Long, uw: ByteArray, ticket: ByteArray, ready: Bwf.Ready): WifiBinding.GuestResult =
            WifiBinding.GuestResult.Ok(bound)
    }

    @Test
    fun endToEndPairOverFakeAwarePair() {
        val env = FakeEnv()
        val hostPorts = FakeWifiPorts(isLinkInitiator = false)
        val guestPorts = FakeWifiPorts(isLinkInitiator = true)
        val hostAgent = WifiHopAgent(hostPorts, env)
        val guestAgent = WifiHopAgent(guestPorts, env)
        hostPorts.deliverScheduler = env.scheduler
        guestPorts.deliverScheduler = env.scheduler
        hostPorts.connectTo(guestAgent)
        guestPorts.connectTo(hostAgent)
        hostAgent.onCallActive()
        env.scheduler.drain()
        guestAgent.onCallActive()
        env.scheduler.drain()
        assertTrue(hostPorts.sent.filterIsInstance<Bwf.Caps>().isNotEmpty())
        assertTrue(guestPorts.sent.filterIsInstance<Bwf.Caps>().isNotEmpty())

        hostAgent.onVideo(remote = true)
        guestAgent.onVideo(remote = true)
        env.scheduler.advance(3_000)
        val hostAttempt = currentAttempt(hostAgent) ?: error("host attempt missing: ${env.lines}")
        val hostLink = linkOf(env, hostAttempt.generation)
        // The guest attempt is created by the HOST record.
        val guestAttempt = currentAttempt(guestAgent) ?: error("guest attempt missing: ${env.lines}")
        val guestLink = linkOf(env, guestAttempt.generation)
        FakeAwarePair(hostLink, guestLink)
        val discovering = env.scheduler.runUntil(5_000) {
            phaseOf(hostAttempt) == Phase.DISCOVERING && phaseOf(guestAttempt) == Phase.DISCOVERING
        }
        assertTrue("not discovering: ${env.lines}", discovering)
        guestLink.discover(1)
        val done = env.scheduler.runUntil(20_000) {
            hostPorts.carriers.isNotEmpty() && guestPorts.carriers.isNotEmpty()
        }
        assertTrue("no carriers: ${env.lines}", done)
        assertTrue(env.lines.any { it.contains("release") && it.contains("outcome=success") })
        val hostTicket = hostPorts.sent.filterIsInstance<Bwf.Host>().single().ticket
        assertFalse(hostTicket.all { it == 0.toByte() })

        val linksBefore = env.links.size
        hostAgent.onCarrierLost()
        env.scheduler.advance(29_000)
        assertEquals(linksBefore, env.links.size)
        env.scheduler.advance(1_000)
        assertTrue(env.links.size > linksBefore)
        currentAttempt(hostAgent)?.command(Cmd.HANGUP)
        env.scheduler.drain()
        hostPorts.fallbackCountValue = 2
        val linksAtCap = env.links.size
        hostAgent.onCarrierLost()
        env.scheduler.advance(600_000)
        assertEquals(linksAtCap, env.links.size)
    }

    @Test
    fun aHostWhoseSessionFailsAfterHostTellsTheGuestWhichReleasesAtOnce() {
        for (reason in listOf("message_stuck", "config_failed")) {
            val env = FakeEnv()
            val hostPorts = FakeWifiPorts(isLinkInitiator = false)
            val guestPorts = FakeWifiPorts(isLinkInitiator = true)
            val hostAgent = WifiHopAgent(hostPorts, env)
            val guestAgent = WifiHopAgent(guestPorts, env)
            hostPorts.deliverScheduler = env.scheduler
            guestPorts.deliverScheduler = env.scheduler
            hostPorts.connectTo(guestAgent)
            guestPorts.connectTo(hostAgent)
            hostAgent.onCallActive()
            env.scheduler.drain()
            guestAgent.onCallActive()
            env.scheduler.drain()
            hostAgent.onVideo(remote = true)
            guestAgent.onVideo(remote = true)
            env.scheduler.advance(3_000)
            val hostAttempt = currentAttempt(hostAgent) ?: error("host attempt missing: ${env.lines}")
            val guestAttempt = currentAttempt(guestAgent) ?: error("guest attempt missing: ${env.lines}")
            assertTrue(env.scheduler.runUntil(5_000) {
                phaseOf(hostAttempt) == Phase.DISCOVERING && phaseOf(guestAttempt) == Phase.DISCOVERING
            })
            assertEquals(2, env.ledger.ndpReserved)
            // The host's discovery session dies after it sent HOST (A4's follow-up watchdog or a chip refusal).
            linkOf(env, hostAttempt.generation).sessionFailed(reason)
            env.scheduler.drain()
            assertEquals(reason, 6, hostPorts.sent.filterIsInstance<Bwf.Decline>().single().reason)
            // The guest hears it at once: no 14 s request watchdog or 25 s attempt timer holds its data path.
            assertNull(reason, currentAttempt(guestAgent))
            assertEquals(reason, 0, env.ledger.ndpReserved)
            assertEquals(reason, 1, linkOf(env, guestAttempt.generation).closeCount)
            hostAgent.onCallEnded()
            guestAgent.onCallEnded()
            env.scheduler.drain()
        }
    }

    // ----- quality monitor -----

    private fun monitorAgent(
        bandVerified: Boolean,
        bearer: WifiBearer = WifiBearer.AWARE,
        /** AP10: the Experimental `test_aware_goodput_guard` arm; off (the default) = the guard only logs. */
        guardActs: Boolean = false,
        channelMhz: Int = 5180,
        band24Alone: Boolean = false,
    ): Triple<WifiHopAgent, FakeEnv, FakeWifiPorts> {
        val env = FakeEnv()
        env.factsValue = env.factsValue.copy(awareGoodputGuardActs = guardActs)
        val ports = FakeWifiPorts()
        val agent = WifiHopAgent(ports, env, inert = false)
        val handed = WifiHopAgent::class.java.getDeclaredField("handed").also { it.isAccessible = true }
        handed.set(agent, HandedCarrier(1, bandVerified, channelMhz, ByteArray(8), bearer, band24Alone))
        val lastSample = WifiHopAgent::class.java.getDeclaredField("lastSampleMs").also { it.isAccessible = true }
        lastSample.setLong(agent, env.scheduler.nowMs)
        val arm = WifiHopAgent::class.java.getDeclaredMethod("armMonitor").also { it.isAccessible = true }
        arm.invoke(agent)
        return Triple(agent, env, ports)
    }

    private fun tick(agent: WifiHopAgent, env: FakeEnv, ports: FakeWifiPorts, sample: CarrierSample?) {
        if (sample == null) ports.carrierSamples.remove(1) else ports.carrierSamples[1] = sample
        env.scheduler.advance(1_000)
    }

    private fun sample(ageMs: Long, bytes: Long, queued: Int = 3, send: Boolean = true) =
        CarrierSample(send, bytes, queued, ageMs)

    @Test
    fun aSlowCarrierFallsBackAfterFiveSlowSeconds() {
        val (agent, env, ports) = monitorAgent(bandVerified = true)
        repeat(5) { tick(agent, env, ports, sample(400, 1_000L * (it + 1))) }
        assertEquals(1, ports.qualityFallbackCalls)
        assertTrue(env.lines.any { it.contains("quality_fallback") && it.contains("reason=queue_slow") })
        tick(agent, env, ports, sample(400, 10_000))
        assertEquals(1, ports.qualityFallbackCalls) // still running, counter reset
    }

    @Test
    fun aKeyframeBurstUnder300msNeverCounts() {
        val (agent, env, ports) = monitorAgent(bandVerified = true)
        repeat(20) { index ->
            tick(agent, env, ports, sample(if (index % 2 == 0) 250 else 400, 1_000L * (index + 1)))
        }
        assertEquals(0, ports.qualityFallbackCalls)
    }

    @Test
    fun aVerifiedCarrierUsesOnlyTheQueueAgeRule() {
        val (agent, env, ports) = monitorAgent(bandVerified = true)
        ports.btBudgetKbps = 400
        repeat(8) { tick(agent, env, ports, sample(0, 500L * (it + 1))) }
        assertEquals(0, ports.qualityFallbackCalls)
    }

    @Test
    fun theGoodputRateIsTheDeliveredBytesOfTheWindow() {
        assertEquals(500L, kbpsFor(62_500L, 1_000L))
        assertEquals(0L, kbpsFor(0L, 1_000L))
        assertEquals(800L, kbpsFor(100L, 0L))
    }

    @Test
    fun aVerifiedCarrierLogsTheDeliveredRate() {
        val (agent, env, ports) = monitorAgent(bandVerified = true)
        // 62,500 bytes in the 1,000 ms window = 500 kbps.
        tick(agent, env, ports, sample(0, 62_500L))
        assertTrue(env.lines.any { it.contains("event=goodput") && it.contains("kbps=500") })
    }

    @Test
    fun anAwareCarrierWithThreeLowGoodputWindowsFallsBack() {
        // The acting arm (Experimental since AP10; by default the guard only logs, see the AP10 tests below).
        val (agent, env, ports) = monitorAgent(bandVerified = true, guardActs = true)
        ports.requestedKbps = 500
        // The guard seeds on the first 1 s tick; windows close at 5 s, 10 s and 15 s (16 ticks).
        repeat(16) { tick(agent, env, ports, sample(0, 0L)) }
        assertEquals(1, ports.qualityFallbackCalls)
        assertTrue(env.lines.any { it.contains("reason=aware_goodput_low") })
    }

    @Test
    fun twoLowGoodputWindowsDoNotFallBack() {
        val (agent, env, ports) = monitorAgent(bandVerified = true, guardActs = true)
        ports.requestedKbps = 500
        // Seed plus two completed windows (5 s and 10 s).
        repeat(11) { tick(agent, env, ports, sample(0, 0L)) }
        assertEquals(0, ports.qualityFallbackCalls)
        // Each completed window is visible in the log with its rate and verdict, so a phone run can show a near miss.
        val windows = env.lines.filter { it.contains("event=goodput_window") }
        assertEquals(2, windows.size)
        assertTrue(windows.all { it.contains("kbps=0") && it.contains("requestedKbps=500") && it.contains("verdict=low") })
        assertTrue(windows.last().contains("lowWindows=2"))
    }

    @Test
    fun noOfferedMediaDoesNotFallBack() {
        val (agent, env, ports) = monitorAgent(bandVerified = true, guardActs = true)
        ports.requestedKbps = 500
        repeat(16) { tick(agent, env, ports, sample(0, 0L, queued = 0)) }
        assertEquals(0, ports.qualityFallbackCalls)
        assertTrue(env.lines.any { it.contains("event=goodput_window") && it.contains("offered=false") && it.contains("verdict=ok") })
    }

    @Test
    fun aDirectCarrierIsNotJudgedByTheAwareGoodputGuard() {
        val (agent, env, ports) = monitorAgent(bandVerified = true, bearer = WifiBearer.DIRECT, guardActs = true)
        ports.requestedKbps = 500
        repeat(16) { tick(agent, env, ports, sample(0, 0L)) }
        assertEquals(0, ports.qualityFallbackCalls)
    }

    @Test
    fun aWifiCarrierWithoutBandVerificationFallsBackAfterFiveLowSeconds() {
        val (agent, env, ports) = monitorAgent(bandVerified = false)
        ports.btBudgetKbps = 400
        repeat(5) { tick(agent, env, ports, sample(0, 500L * 1_000 / 8 * (it + 1))) }
        assertEquals(1, ports.qualityFallbackCalls)
        assertTrue(env.lines.any { it.contains("quality_fallback") && it.contains("reason=goodput_low") })
        tick(agent, env, ports, sample(0, 5L * 1_000 / 8 * 6))
        assertEquals(1, ports.qualityFallbackCalls)
    }

    @Test
    fun aSecondAboveTwiceTheBudgetResetsTheCount() {
        val (agent, env, ports) = monitorAgent(bandVerified = false)
        ports.btBudgetKbps = 400
        val step = 500L * 1_000 / 8
        var bytes = 0L
        repeat(4) { bytes += step; tick(agent, env, ports, sample(0, bytes)) }
        bytes += 900L * 1_000 / 8
        tick(agent, env, ports, sample(0, bytes))
        repeat(4) { bytes += step; tick(agent, env, ports, sample(0, bytes)) }
        assertEquals(0, ports.qualityFallbackCalls)
    }

    @Test
    fun anIdleCarrierWithAnEmptyQueueIsNotLow() {
        val (agent, env, ports) = monitorAgent(bandVerified = false)
        ports.btBudgetKbps = 400
        repeat(8) { tick(agent, env, ports, sample(0, 100L * (it + 1), queued = 0)) }
        assertEquals(0, ports.qualityFallbackCalls)
        val (agent2, env2, ports2) = monitorAgent(bandVerified = false)
        ports2.btBudgetKbps = 400
        repeat(8) { tick(agent2, env2, ports2, sample(0, 100L * (it + 1), send = false)) }
        assertEquals(0, ports2.qualityFallbackCalls)
    }

    @Test
    fun noLearnedBudgetMeansNoFallback() {
        val (agent, env, ports) = monitorAgent(bandVerified = false)
        ports.btBudgetKbps = null
        repeat(8) { tick(agent, env, ports, sample(0, 10L * (it + 1))) }
        assertEquals(0, ports.qualityFallbackCalls)
    }

    @Test
    fun aGapOverThreeSecondsResetsTheSlowCount() {
        val (agent, env, ports) = monitorAgent(bandVerified = true)
        val lastSample = WifiHopAgent::class.java.getDeclaredField("lastSampleMs").also { it.isAccessible = true }
        val monitorTick = WifiHopAgent::class.java.getDeclaredMethod("monitorTick").also { it.isAccessible = true }
        val cancelTimer = WifiHopAgent::class.java.getDeclaredMethod("cancelTimer", String::class.java).also { it.isAccessible = true }
        var bytes = 0L
        repeat(4) { bytes += 100; tick(agent, env, ports, sample(400, bytes)) }
        // A four-second gap between samples: the next tick resets the count instead of continuing it.
        cancelTimer.invoke(agent, "monitor")
        lastSample.setLong(agent, env.scheduler.nowMs - 4_000)
        bytes += 100
        ports.carrierSamples[1] = sample(400, bytes)
        monitorTick.invoke(agent)
        repeat(4) { bytes += 100; tick(agent, env, ports, sample(400, bytes)) }
        assertEquals(0, ports.qualityFallbackCalls)
        bytes += 100
        tick(agent, env, ports, sample(400, bytes))
        assertEquals(1, ports.qualityFallbackCalls)
    }

    @Test
    fun theSamplerKeepsRunningAfterAFallback() {
        val (agent, env, ports) = monitorAgent(bandVerified = true)
        var bytes = 0L
        repeat(5) { bytes += 100; tick(agent, env, ports, sample(400, bytes)) }
        assertEquals(1, ports.qualityFallbackCalls)
        repeat(5) { bytes += 100; tick(agent, env, ports, sample(400, bytes)) }
        assertEquals(2, ports.qualityFallbackCalls)
    }

    @Test
    fun qualityFallbackLogsTheCarrierLineAndTheCooldownFollows() {
        val (agent, env, ports) = monitorAgent(bandVerified = true)
        var bytes = 0L
        repeat(5) { bytes += 100; tick(agent, env, ports, sample(400, bytes)) }
        assertEquals(1, ports.qualityFallbackCalls)
        assertTrue(env.lines.any { it.startsWith("carrier:") && it.contains("reason=queue_slow") })
        ports.carrierSamples.remove(1)
        env.scheduler.advance(1_000)
        assertTrue(statusLine(agent, env).contains("next="))
        val verdict = plannerOf(agent).canStart(0)
        assertTrue(verdict is WifiHopPlanner.Verdict.Wait)
    }

    @Test
    fun aVanishedCarrierResumesPlanningAfterThirtySeconds() {
        val (agent, env, ports) = monitorAgent(bandVerified = true)
        tick(agent, env, ports, sample(0, 100))
        ports.carrierSamples.remove(1)
        env.scheduler.advance(1_000)
        assertTrue(plannerOf(agent).canStart(0) is WifiHopPlanner.Verdict.Wait)
        env.scheduler.advance(30_000)
        assertEquals(WifiHopPlanner.Verdict.Go, plannerOf(agent).canStart(0))
    }

    // ----- AP10: the goodput guard only logs by default -----

    @Test
    fun byDefaultTheGoodputGuardOnlyLogsAndTheHopStaysOnWifi() {
        val (agent, env, ports) = monitorAgent(bandVerified = true)
        ports.requestedKbps = 500
        repeat(16) { tick(agent, env, ports, sample(0, 0L)) }
        assertEquals(0, ports.qualityFallbackCalls)
        assertTrue(env.lines.any { it.contains("event=quality_fallback_suppressed") && it.contains("reason=aware_goodput_low") })
        assertFalse(env.lines.any { it.startsWith("carrier:") })
        // Every window is still measured and logged, and the next attempt is not skipped.
        assertTrue(env.lines.any { it.contains("event=goodput_window") && it.contains("verdict=fall_back") })
        assertEquals(WifiHopPlanner.Verdict.Go, plannerOf(agent).canStart(0))
        // It keeps measuring: three more low windows are reported again, and the hop is still on Wi-Fi.
        repeat(15) { tick(agent, env, ports, sample(0, 0L)) }
        assertEquals(0, ports.qualityFallbackCalls)
        assertEquals(2, env.lines.count { it.contains("event=quality_fallback_suppressed") })
    }

    @Test
    fun aHealthyLinkUnderAnEncoderThatSendsLessThanItsConfiguredRateNeverFallsBack() {
        // Audit WIFI-01: 300 kbps sent against a configured 1100 kbps, every queued record written (the queue holds
        // one record in flight, never an old one). The link is healthy; by default the hop stays on Wi-Fi.
        val (agent, env, ports) = monitorAgent(bandVerified = true)
        ports.requestedKbps = 1_100
        var bytes = 0L
        repeat(120) {
            bytes += 300L * 1_000 / 8
            tick(agent, env, ports, sample(ageMs = 0, bytes = bytes, queued = 1))
        }
        assertEquals(0, ports.qualityFallbackCalls)
        assertFalse(env.lines.any { it.startsWith("carrier:") })
        assertEquals(WifiHopPlanner.Verdict.Go, plannerOf(agent).canStart(0))
        assertTrue(env.lines.any { it.contains("event=goodput_window") && it.contains("kbps=300") && it.contains("requestedKbps=1100") })
    }

    @Test
    fun theQueueRuleStillReturnsAStuckHopToBluetoothWhileTheGoodputGuardOnlyLogs() {
        val (agent, env, ports) = monitorAgent(bandVerified = true)
        ports.requestedKbps = 500
        repeat(5) { tick(agent, env, ports, sample(400, 1_000L * (it + 1))) }
        assertEquals(1, ports.qualityFallbackCalls)
        assertTrue(env.lines.any { it.contains("quality_fallback") && it.contains("reason=queue_slow") })
    }

    @Test
    fun aGoodputFallbackThatDidNothingLogsNoCarrierMoveAndSkipsNoAttempt() {
        // Audit WIFI-03: with a stale standby the cutover machine ignores the fallback; the hop is still on Wi-Fi.
        val (agent, env, ports) = monitorAgent(bandVerified = true, guardActs = true)
        ports.requestedKbps = 500
        ports.qualityFallbackHappens = false
        repeat(16) { tick(agent, env, ports, sample(0, 0L)) }
        assertEquals(1, ports.qualityFallbackCalls)
        assertFalse(env.lines.any { it.startsWith("carrier:") })
        assertTrue(env.lines.any { it.contains("event=quality_fallback_noop") && it.contains("reason=aware_goodput_low") })
        assertEquals(WifiHopPlanner.Verdict.Go, plannerOf(agent).canStart(0))
    }

    @Test
    fun aGoodputFallbackThatHappenedLogsTheCarrierMoveAndSkipsTheNextAttempt() {
        val (agent, env, ports) = monitorAgent(bandVerified = true, guardActs = true)
        ports.requestedKbps = 500
        repeat(16) { tick(agent, env, ports, sample(0, 0L)) }
        assertEquals(1, ports.qualityFallbackCalls)
        assertTrue(env.lines.any { it.startsWith("carrier:") && it.contains("effective=bluetooth") && it.contains("reason=aware_goodput_low") })
        assertEquals(WifiHopPlanner.Verdict.Never("aware_goodput_low"), plannerOf(agent).canStart(0))
    }

    // ----- AP10: a 2.4 GHz-only Aware hop when the phone carries no other Bluetooth media -----

    private fun alone24Env(on: Boolean = true): FakeEnv =
        FakeEnv().also { it.factsValue = it.factsValue.copy(aware24GhzAlone = on) }

    private class Hops(
        val env: FakeEnv,
        val hostPorts: FakeWifiPorts,
        val guestPorts: FakeWifiPorts,
        val hostAgent: WifiHopAgent,
        val guestAgent: WifiHopAgent,
    )

    /** A host and a guest agent that run one whole Aware attempt over a data path with [channels]. */
    private fun attemptOver(channels: List<Int>, env: FakeEnv, hostOtherBt: Boolean, guestOtherBt: Boolean): Hops {
        val hostPorts = FakeWifiPorts(isLinkInitiator = false).also { it.otherBluetoothMediaValue = hostOtherBt }
        val guestPorts = FakeWifiPorts(isLinkInitiator = true).also { it.otherBluetoothMediaValue = guestOtherBt }
        // The monitor of a handed carrier reads its sample from the first tick on.
        hostPorts.carrierSamples[1] = sample(0, 0L, queued = 0)
        guestPorts.carrierSamples[1] = sample(0, 0L, queued = 0)
        val hostAgent = WifiHopAgent(hostPorts, env)
        val guestAgent = WifiHopAgent(guestPorts, env)
        hostPorts.deliverScheduler = env.scheduler
        guestPorts.deliverScheduler = env.scheduler
        hostPorts.connectTo(guestAgent)
        guestPorts.connectTo(hostAgent)
        hostAgent.onCallActive()
        env.scheduler.drain()
        guestAgent.onCallActive()
        env.scheduler.drain()
        hostAgent.onVideo(remote = true)
        guestAgent.onVideo(remote = true)
        env.scheduler.advance(3_000)
        val hostAttempt = currentAttempt(hostAgent) ?: error("host attempt missing: ${env.lines}")
        val guestAttempt = currentAttempt(guestAgent) ?: error("guest attempt missing: ${env.lines}")
        val hostLink = linkOf(env, hostAttempt.generation)
        val guestLink = linkOf(env, guestAttempt.generation)
        FakeAwarePair(hostLink, guestLink, channels)
        val discovering = env.scheduler.runUntil(5_000) {
            phaseOf(hostAttempt) == Phase.DISCOVERING && phaseOf(guestAttempt) == Phase.DISCOVERING
        }
        assertTrue("not discovering: ${env.lines}", discovering)
        guestLink.discover(1)
        env.scheduler.runUntil(20_000) {
            (hostPorts.carriers.isNotEmpty() && guestPorts.carriers.isNotEmpty()) ||
                (currentAttempt(hostAgent) == null && currentAttempt(guestAgent) == null)
        }
        return Hops(env, hostPorts, guestPorts, hostAgent, guestAgent)
    }

    private fun Hops.end() {
        hostAgent.onCallEnded()
        guestAgent.onCallEnded()
        env.scheduler.drain()
    }

    @Test
    fun twoPhonesWithNoOtherBluetoothMediaMoveTheirHopToATwoPointFourOnlyAwarePath() {
        val hops = attemptOver(listOf(2412), alone24Env(), hostOtherBt = false, guestOtherBt = false)
        assertEquals("host carrier: ${hops.env.lines}", 1, hops.hostPorts.carriers.size)
        assertEquals(1, hops.guestPorts.carriers.size)
        assertTrue(hops.env.lines.any { it.contains("event=band") && it.contains("chMhz=2412") && it.contains("band=2g4_alone") })
        // Audit WIFI-19: while the 2.4 GHz path is live, Bluetooth capacity is learned under the coexistence key.
        assertTrue(hops.env.ledger.band24PathLive)
        hops.end()
        assertFalse("the mark ends with the hops", hops.env.ledger.band24PathLive)
    }

    @Test
    fun aPhoneWithOtherBluetoothMediaRefusesTheTwoPointFourOnlyPathAndBothStayOnBluetooth() {
        // The host relays to a Bluetooth child (or serves another viewer); the guest is alone.
        val hops = attemptOver(listOf(2412), alone24Env(), hostOtherBt = true, guestOtherBt = false)
        assertTrue(hops.hostPorts.carriers.isEmpty())
        assertTrue(hops.guestPorts.carriers.isEmpty())
        assertTrue(sentDeclines(hops.hostPorts).any { it.reason == 7 })
        assertTrue(hops.env.lines.any { it.contains("event=band") && it.contains("otherBtMedia=true") && it.contains("band=refused_24_bt_media") })
        assertFalse(hops.env.ledger.band24PathLive)
        hops.end()
    }

    @Test
    fun aFiveGhzPathWithTheSocialChannelIsAcceptedWithAndWithoutOtherBluetoothMedia() {
        for (other in listOf(true, false)) {
            val hops = attemptOver(listOf(5745, 2437), alone24Env(), hostOtherBt = other, guestOtherBt = other)
            assertEquals("other=$other: ${hops.env.lines}", 1, hops.hostPorts.carriers.size)
            assertEquals("other=$other", 1, hops.guestPorts.carriers.size)
            assertFalse("a 5 GHz path is no 2.4 GHz coexistence", hops.env.ledger.band24PathLive)
            hops.end()
        }
    }

    @Test
    fun withTheSwitchOffTwoPhonesWithNoOtherBluetoothMediaStillRefuseATwoPointFourOnlyPath() {
        val hops = attemptOver(listOf(2412), alone24Env(on = false), hostOtherBt = false, guestOtherBt = false)
        assertTrue(hops.hostPorts.carriers.isEmpty())
        assertTrue(hops.guestPorts.carriers.isEmpty())
        assertTrue(hops.env.lines.any { it.contains("event=band") && it.contains("alone24=false") && it.contains("band=refused_low") })
        hops.end()
    }

    @Test
    fun aSlowTwoPointFourHopStillReturnsToBluetoothThroughTheQueueRule() {
        val hops = attemptOver(listOf(2412), alone24Env(), hostOtherBt = false, guestOtherBt = false)
        assertEquals(1, hops.hostPorts.carriers.size)
        // The send queue's oldest record stays older than 300 ms for five seconds: the existing queue rule fires.
        repeat(6) {
            hops.hostPorts.carrierSamples[1] = sample(400, 1_000L * (it + 1))
            hops.env.scheduler.advance(1_000)
        }
        assertEquals(1, hops.hostPorts.qualityFallbackCalls)
        assertTrue(hops.env.lines.any { it.startsWith("carrier:") && it.contains("effective=bluetooth") && it.contains("reason=queue_slow") })
        hops.end()
    }

    @Test
    fun underItsArmTheGoodputGuardStillReturnsASlowTwoPointFourHopToBluetooth() {
        val env = alone24Env()
        env.factsValue = env.factsValue.copy(awareGoodputGuardActs = true)
        val hops = attemptOver(listOf(2412), env, hostOtherBt = false, guestOtherBt = false)
        assertEquals(1, hops.hostPorts.carriers.size)
        hops.hostPorts.requestedKbps = 500
        // Media is offered and nothing is delivered: three low 5 s windows.
        repeat(17) {
            hops.hostPorts.carrierSamples[1] = sample(0, 0L)
            hops.env.scheduler.advance(1_000)
        }
        assertEquals(1, hops.hostPorts.qualityFallbackCalls)
        assertTrue(hops.env.lines.any { it.startsWith("carrier:") && it.contains("reason=aware_goodput_low") })
        hops.end()
    }

    @Test
    fun bluetoothMediaThatAppearsBesideALiveTwoPointFourHopIsLoggedOnceAndTheHopIsKept() {
        val hops = attemptOver(listOf(2412), alone24Env(), hostOtherBt = false, guestOtherBt = false)
        assertEquals(1, hops.hostPorts.carriers.size)
        hops.env.scheduler.advance(3_000)
        assertFalse(hops.env.lines.any { it.contains("event=band_24_with_bt_media") })
        hops.hostPorts.otherBluetoothMediaValue = true // a Bluetooth viewer joins this phone
        hops.env.scheduler.advance(5_000)
        assertEquals(1, hops.env.lines.count { it.contains("event=band_24_with_bt_media") })
        assertEquals("the hop is not moved mid-call", 0, hops.hostPorts.qualityFallbackCalls)
        assertEquals(0, hops.hostPorts.carrierSwitchOffCalls)
        assertTrue("its capacity stays under the coexistence key", hops.env.ledger.band24PathLive)
        // The viewer leaves and another joins: a new appearance is logged again.
        hops.hostPorts.otherBluetoothMediaValue = false
        hops.env.scheduler.advance(2_000)
        hops.hostPorts.otherBluetoothMediaValue = true
        hops.env.scheduler.advance(2_000)
        assertEquals(2, hops.env.lines.count { it.contains("event=band_24_with_bt_media") })
        hops.end()
    }

    @Test
    fun theTwoPointFourMarkEndsWhenTheCarrierIsLost() {
        val hops = attemptOver(listOf(2412), alone24Env(), hostOtherBt = false, guestOtherBt = false)
        assertTrue(hops.env.ledger.band24PathLive)
        hops.hostPorts.carrierSamples.remove(1)
        hops.guestPorts.carrierSamples.remove(1)
        hops.env.scheduler.advance(1_000)
        assertFalse(hops.env.ledger.band24PathLive)
        hops.end()
    }

    // ----- AP10: an Aware attempt beside a 2.4 GHz client connection -----

    @Test
    fun twoPhonesOnTwoPointFourWifiWithNoOtherBluetoothMediaOfferAwareAndMoveTheirHop() {
        val env = alone24Env()
        env.factsValue = env.factsValue.copy(staFrequencyMhz = 2437)
        val hops = attemptOver(listOf(2437), env, hostOtherBt = false, guestOtherBt = false)
        assertEquals("host carrier: ${env.lines}", 1, hops.hostPorts.carriers.size)
        assertEquals(1, hops.guestPorts.carriers.size)
        hops.end()
    }

    @Test
    fun aPhoneOnTwoPointFourWifiWithOtherBluetoothMediaOffersNoAware() {
        val env = alone24Env()
        env.factsValue = env.factsValue.copy(staFrequencyMhz = 2437)
        val rig = Rig(env = env)
        rig.ports.otherBluetoothMediaValue = true
        rig.responderCaps()
        val caps = rig.ports.sent.filterIsInstance<Bwf.Caps>().single()
        assertEquals(0, caps.bearers and Bwf.Caps.BEARER_AWARE)
        rig.agent.onVideo(remote = true)
        rig.env.scheduler.advance(WifiAttemptLimits.VIDEO_STABLE_MS + WifiAttemptLimits.RECHECK_MS)
        assertNull(currentAttempt(rig.agent))
    }

    @Test
    fun aHostBesideATwoPointFourClientConnectionAttemptsOnlyWithNoOtherBluetoothMedia() {
        // The client connection moves to 2.4 GHz after CAPS, so only the attempt decision is exercised here.
        val alone = Rig(env = alone24Env()).responderCaps()
        alone.env.factsValue = alone.env.factsValue.copy(staFrequencyMhz = 2437)
        alone.ports.otherBluetoothMediaValue = false
        alone.agent.onVideo(remote = true)
        alone.env.scheduler.advance(WifiAttemptLimits.VIDEO_STABLE_MS)
        assertNotNull("no attempt: ${alone.env.lines}", currentAttempt(alone.agent))
        assertTrue(alone.env.lines.any { it.contains("event=sta_band") && it.contains("otherBtMedia=false") && it.contains("band=2g4_alone") })

        val busy = Rig(env = alone24Env()).responderCaps()
        busy.env.factsValue = busy.env.factsValue.copy(staFrequencyMhz = 2437)
        busy.ports.otherBluetoothMediaValue = true
        busy.agent.onVideo(remote = true)
        busy.env.scheduler.advance(WifiAttemptLimits.VIDEO_STABLE_MS + 3 * WifiAttemptLimits.RECHECK_MS)
        assertNull(currentAttempt(busy.agent))
        assertTrue(busy.env.lines.any { it.contains("event=host_skip") && it.contains("reason=NOT_5GHZ") })
        // The facts are logged once, not at every recheck.
        assertEquals(1, busy.env.lines.count { it.contains("event=sta_band") && it.contains("otherBtMedia=true") && it.contains("band=refused_not_5ghz") })
        // The other media ends: the next recheck starts the attempt.
        busy.ports.otherBluetoothMediaValue = false
        busy.env.scheduler.advance(WifiAttemptLimits.RECHECK_MS)
        assertNotNull("no attempt after the other media ended: ${busy.env.lines}", currentAttempt(busy.agent))

        val switchOff = Rig(env = alone24Env(on = false)).responderCaps()
        switchOff.env.factsValue = switchOff.env.factsValue.copy(staFrequencyMhz = 2437)
        switchOff.ports.otherBluetoothMediaValue = false
        switchOff.agent.onVideo(remote = true)
        switchOff.env.scheduler.advance(WifiAttemptLimits.VIDEO_STABLE_MS + WifiAttemptLimits.RECHECK_MS)
        assertNull(currentAttempt(switchOff.agent))
    }

    @Test
    fun aGuestBesideATwoPointFourClientConnectionServesOnlyWithNoOtherBluetoothMedia() {
        fun serve(switchOn: Boolean, otherBt: Boolean): Pair<FakeWifiPorts, WifiHopAgent> {
            val env = alone24Env(on = switchOn)
            val (ports, agent) = guestAgent(env)
            env.factsValue = env.factsValue.copy(staFrequencyMhz = 2437)
            ports.otherBluetoothMediaValue = otherBt
            agent.onRecord(0, Bwf.Host(1, 1, ticket, 20_000, 0).encode())
            env.scheduler.drain()
            return ports to agent
        }
        val (alonePorts, aloneAgent) = serve(switchOn = true, otherBt = false)
        assertNotNull(currentAttempt(aloneAgent))
        assertTrue(sentDeclines(alonePorts).isEmpty())

        val (busyPorts, busyAgent) = serve(switchOn = true, otherBt = true)
        assertNull(currentAttempt(busyAgent))
        assertEquals(7, sentDeclines(busyPorts).last().reason)

        val (offPorts, offAgent) = serve(switchOn = false, otherBt = false)
        assertNull(currentAttempt(offAgent))
        assertEquals(7, sentDeclines(offPorts).last().reason)
    }
}
