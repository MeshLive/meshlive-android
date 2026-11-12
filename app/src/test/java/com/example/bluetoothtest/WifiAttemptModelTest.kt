package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiAttemptModelTest {
    private fun counterRandom(): (ByteArray) -> Unit {
        var n = 0
        return { bytes ->
            n++
            for (i in bytes.indices) bytes[i] = ((n * 31 + i) and 0xff).toByte()
        }
    }

    private fun model(
        role: AttemptRole,
        bearer: WifiBearer = WifiBearer.AWARE,
        acceptsUnverifiedBand: Boolean = false,
        bandStrict: Boolean = false,
    ) = AttemptModel(role, 1L, acceptsUnverifiedBand, counterRandom(), bearer, bandStrict)

    private fun AttemptModel.drive(vararg events: AttemptEvent): List<AttemptEffect> =
        events.flatMap { handle(it) }

    private fun List<AttemptEffect>.nonLog(): List<AttemptEffect> =
        filter { it !is LogEvent && it !is Arm && it !is Cancel }

    private fun List<AttemptEffect>.ends(): List<End> = filterIsInstance<End>()

    private fun List<AttemptEffect>.declines(): List<SendDecline> = filterIsInstance<SendDecline>()

    private fun List<AttemptEffect>.challenges(): List<SendChallenge> = filterIsInstance<SendChallenge>()

    private fun List<AttemptEffect>.knocks(): List<SendKnock> = filterIsInstance<SendKnock>()

    private fun gN(seed: Int): ByteArray = ByteArray(16) { (seed + it).toByte() }

    private fun c(seed: Int): ByteArray = ByteArray(16) { (seed * 7 + it).toByte() }

    private fun AttemptModel.toDiscoveringHost() {
        drive(Start(1L), AttachOk(1L), SessionStarted(1L))
    }

    private fun AttemptModel.toDiscoveringGuest() {
        drive(Start(1L), AttachOk(1L), SessionStarted(1L))
    }

    private fun AttemptModel.toRequestingGuest() {
        toDiscoveringGuest()
        drive(
            PeerDiscovered(1L, 1),
            ChallengeIn(1L, 1, true, c(1)),
            ArmedIn(1L, true),
        )
    }

    private fun AttemptModel.toAddressingGuest() {
        toRequestingGuest()
        drive(NetworkAvailable(1L))
    }

    private fun AttemptModel.toConnectingGuest() {
        toAddressingGuest()
        drive(NetworkReady(1L, PathFacts(listOf(5180), true)))
    }

    private fun AttemptModel.toBindingGuest() {
        toConnectingGuest()
        drive(Connected(1L))
    }

    private fun AttemptModel.toHandingOffGuest() {
        toBindingGuest()
        drive(BindFinished(1L, 1, BindCode.BOUND, "ok"))
    }

    @Test
    fun hostHappyPathEmitsTheMasterSequence() {
        val m = model(AttemptRole.HOST)
        val effects = ArrayList<AttemptEffect>()
        effects += m.handle(Start(1L))
        effects += m.handle(AttachOk(1L))
        effects += m.handle(SessionStarted(1L))
        val knock = m.handle(KnockIn(1L, 7, true, gN(1)))
        effects += knock
        val challenge = knock.challenges().single()
        effects += m.handle(PickIn(1L, true, challenge.c))
        effects += m.handle(RequestFiled(1L, true))
        effects += m.handle(NetworkReady(1L, PathFacts(listOf(5180), false)))
        effects += m.handle(ServerListening(1L))
        effects += m.handle(SocketAccepted(1L, 1))
        effects += m.handle(BindFinished(1L, 1, BindCode.BOUND, "ok"))
        effects += m.handle(HandOffResult(1L, true))

        val types = effects.nonLog()
        assertTrue(types[0] is AcquireAttach)
        assertTrue(types[1] is StartSession)
        assertTrue(types[2] is SendHost)
        val sentChallenge = types[3] as SendChallenge
        assertEquals(7, sentChallenge.ordinal)
        assertEquals(0x01, sentChallenge.gN[0].toInt() and 0xff) // gN(1) first byte
        assertTrue(types[4] is RequestPath)
        assertEquals(7, (types[4] as RequestPath).ordinal)
        assertTrue((types[4] as RequestPath).responder)
        assertTrue(types.toString(), types[5] is SendArmed)
        assertTrue(types.toString(), types[6] is Listen)
        assertTrue(types.toString(), types[7] is StartBind)
        assertTrue(types.toString(), types[8] is HandOff)
        assertTrue(types.toString(), types[9] is End)
        assertEquals(OutcomeKind.SUCCESS, (types[9] as End).outcome.kind)
        assertEquals(1, effects.ends().size)
        assertEquals(types.last(), effects.last())
        // ARMED only after RequestFiled
        val armedIndex = effects.indexOfFirst { it is SendArmed }
        val requestIndex = effects.indexOfFirst { it is RequestPath }
        assertTrue(armedIndex > requestIndex)
    }

    @Test
    fun guestHappyPathEmitsTheMasterSequence() {
        val m = model(AttemptRole.GUEST)
        val effects = ArrayList<AttemptEffect>()
        effects += m.handle(Start(1L))
        effects += m.handle(AttachOk(1L))
        effects += m.handle(SessionStarted(1L))
        effects += m.handle(PeerDiscovered(1L, 1))
        effects += m.handle(ChallengeIn(1L, 1, true, c(1)))
        effects += m.handle(ArmedIn(1L, true))
        effects += m.handle(RequestFiled(1L, true))
        effects += m.handle(NetworkReady(1L, PathFacts(listOf(5745), true)))
        effects += m.handle(Connected(1L))
        effects += m.handle(BindFinished(1L, 1, BindCode.BOUND, "ok"))
        effects += m.handle(HandOffResult(1L, true))

        val types = effects.nonLog()
        assertTrue(types[0] is AcquireAttach)
        assertTrue(types[1] is StartSession)
        assertTrue(types[2] is SendKnock)
        assertTrue(types[3] is SendPick)
        assertTrue(types[4] is RequestPath)
        assertFalse((types[4] as RequestPath).responder)
        assertTrue(types[5] is Connect)
        assertTrue(types[6] is StartBind)
        assertTrue(types[7] is HandOff)
        assertTrue(types[8] is End)
        assertEquals(OutcomeKind.SUCCESS, (types[8] as End).outcome.kind)
    }

    @Test
    fun aRepeatedKnockGetsTheSameChallengeAndTheCapIsEight() {
        val m = model(AttemptRole.HOST)
        m.toDiscoveringHost()
        val first = m.handle(KnockIn(1L, 1, true, gN(1))).challenges().single()
        val second = m.handle(KnockIn(1L, 1, true, gN(1))).challenges().single()
        assertTrue(first.c.contentEquals(second.c))

        val fresh = model(AttemptRole.HOST)
        fresh.toDiscoveringHost()
        val challenges = ArrayList<SendChallenge>()
        for (handle in 1..9) {
            challenges += fresh.handle(KnockIn(1L, handle, true, gN(handle))).challenges()
        }
        assertEquals(8, challenges.size)
        assertEquals(8, challenges.map { it.c.toList() }.toSet().size)
        assertTrue(fresh.handle(KnockIn(1L, 10, true, gN(10))).any { it is LogEvent && it.event == "challenge" && it.fields.contains("capped" to true) })

        // a fourth send for one (handle, gN) gets nothing
        assertTrue(fresh.handle(KnockIn(1L, 1, true, gN(1))).challenges().isNotEmpty())
        assertTrue(fresh.handle(KnockIn(1L, 1, true, gN(1))).challenges().isNotEmpty())
        assertTrue(fresh.handle(KnockIn(1L, 1, true, gN(1))).challenges().isEmpty())

        // the same gN on another handle gets its own new challenge
        val other = model(AttemptRole.HOST)
        other.toDiscoveringHost()
        val a = other.handle(KnockIn(1L, 1, true, gN(4))).challenges().single()
        val b = other.handle(KnockIn(1L, 2, true, gN(4))).challenges().single()
        assertEquals(1, a.ordinal)
        assertEquals(2, b.ordinal)
        assertFalse(a.c.contentEquals(b.c))
    }

    @Test
    fun anInvalidKnockEarnsNothing() {
        val m = model(AttemptRole.HOST)
        m.toDiscoveringHost()
        assertTrue(m.handle(KnockIn(1L, 1, false, null)).challenges().isEmpty())
        for (i in 0 until 31) m.handle(KnockIn(1L, 2, false, null))
        assertTrue(m.handle(KnockIn(1L, 3, true, gN(3))).challenges().isEmpty()) // the 33rd message is ignored
    }

    @Test
    fun pickRules() {
        val m = model(AttemptRole.HOST)
        m.toDiscoveringHost()
        assertEquals(Phase.DISCOVERING, m.phase)
        assertTrue(m.handle(PickIn(1L, true, c(99))).nonLog().isEmpty())
        assertEquals(Phase.DISCOVERING, m.phase)
        assertTrue(m.handle(PickIn(1L, false, c(99))).nonLog().isEmpty())
        m.handle(KnockIn(1L, 1, true, gN(1)))
        val challenge = m.handle(KnockIn(1L, 1, true, gN(1))).challenges().first()
        val pick = m.handle(PickIn(1L, true, challenge.c)).nonLog()
        assertTrue(pick.any { it is RequestPath })
        assertEquals(Phase.REQUESTING, m.phase)
    }

    @Test
    fun staleGenerationEventsChangeNothing() {
        val m = model(AttemptRole.HOST)
        m.toDiscoveringHost()
        val reps: List<AttemptEvent> = listOf(
            Start(2L), AttachOk(2L), AttachFailed(2L, AttachFailure.BUSY_DIRECT), SessionStarted(2L),
            SessionFailed(2L, "config", true), SessionTerminated(2L), PeerDiscovered(2L, 1),
            KnockIn(2L, 1, true, gN(1)), ChallengeIn(2L, 1, true, c(1)), PickIn(2L, true, c(1)),
            ArmedIn(2L, true), DeclineIn(2L, true, 1), SendFailed(2L, "host"), RequestFiled(2L, true),
            NetworkAvailable(2L), NetworkReady(2L, PathFacts(null, true)), NetworkUnavailable(2L, 1, false),
            NetworkLost(2L), AwareDown(2L, AwareDownReason.UNAVAILABLE), ServerListening(2L),
            ServerFailed(2L, "bind"), SocketAccepted(2L, 1), SocketRejected(2L, "peer"),
            Connected(2L), ConnectFailed(2L, "window", true), BindFinished(2L, 1, BindCode.BOUND, "ok"),
            HandOffResult(2L, true), TimerFired(2L, TimerKind.PICK, 0), Command(2L, Cmd.HANGUP),
        )
        for (rep in reps) assertTrue(rep.toString(), m.handle(rep).isEmpty())
        assertEquals(Phase.DISCOVERING, m.phase)
        m.handle(Command(1L, Cmd.HANGUP))
        assertTrue(m.handle(Start(1L)).isEmpty())
        assertTrue(m.handle(Command(1L, Cmd.HANGUP)).isEmpty())
    }

    @Test
    fun aWrongPreCostsOnlyTheSocket() {
        val m = model(AttemptRole.HOST)
        m.toDiscoveringHost()
        m.handle(KnockIn(1L, 1, true, gN(1)))
        val challenge = m.handle(KnockIn(1L, 1, true, gN(1))).challenges().first()
        m.handle(PickIn(1L, true, challenge.c))
        m.handle(RequestFiled(1L, true))
        m.handle(NetworkReady(1L, PathFacts(listOf(5180), false)))
        m.handle(SocketAccepted(1L, 1))
        val rejected = m.handle(BindFinished(1L, 1, BindCode.REJECTED_BEFORE_TICKET, "pre"))
        assertEquals(Phase.BINDING, m.phase)
        assertTrue(rejected.any { it is CloseSocket })
        assertTrue(rejected.ends().isEmpty())
        val ok = m.handle(BindFinished(1L, 2, BindCode.BOUND, "ok"))
        assertTrue(ok.any { it is HandOff && it.ordinal == 2 })
    }

    @Test
    fun afterTheTicketIsBurntAFailureEndsTheAttempt() {
        val m = model(AttemptRole.HOST)
        m.toDiscoveringHost()
        m.handle(KnockIn(1L, 1, true, gN(1)))
        val challenge = m.handle(KnockIn(1L, 1, true, gN(1))).challenges().first()
        m.handle(PickIn(1L, true, challenge.c))
        m.handle(RequestFiled(1L, true))
        m.handle(NetworkReady(1L, PathFacts(listOf(5180), false)))
        m.handle(SocketAccepted(1L, 1))
        m.handle(SocketAccepted(1L, 2))
        val end = m.handle(BindFinished(1L, 1, BindCode.FAILED_AFTER_TICKET, "bind_check")).ends().single()
        assertEquals(OutcomeKind.FAILED_PEER, end.outcome.kind)
        assertEquals(6, end.outcome.decline)
    }

    @Test
    fun fourSocketCap() {
        val m = model(AttemptRole.HOST)
        m.toDiscoveringHost()
        m.handle(KnockIn(1L, 1, true, gN(1)))
        val challenge = m.handle(KnockIn(1L, 1, true, gN(1))).challenges().first()
        m.handle(PickIn(1L, true, challenge.c))
        m.handle(RequestFiled(1L, true))
        m.handle(NetworkReady(1L, PathFacts(listOf(5180), false)))
        for (ordinal in 1..4) {
            val started = m.handle(SocketAccepted(1L, ordinal))
            assertTrue("socket $ordinal", started.any { it is StartBind && it.ordinal == ordinal })
        }
        val fifth = m.handle(SocketAccepted(1L, 5))
        assertTrue(fifth.any { it is CloseSocket && it.ordinal == 5 })
        assertFalse(fifth.any { it is StartBind })
        var end: End? = null
        for (ordinal in 1..4) end = m.handle(BindFinished(1L, ordinal, BindCode.REJECTED_BEFORE_TICKET, "pre")).ends().singleOrNull() ?: end
        assertEquals("bind_sockets_exhausted", end!!.outcome.reason)
    }

    @Test
    fun timersAtEveryStage() {
        fun outcome(effects: List<AttemptEffect>) = effects.ends().single().outcome

        val session = model(AttemptRole.GUEST).apply { handle(Start(1L)) }
        val sessionEnd = outcome(session.handle(TimerFired(1L, TimerKind.SESSION, 0)))
        assertEquals(OutcomeKind.FAILED_LOCAL, sessionEnd.kind)
        assertEquals("session_timeout", sessionEnd.reason)
        assertEquals("session_timeout", sessionEnd.fault)

        val hostPick = model(AttemptRole.HOST).apply { toDiscoveringHost() }
        assertEquals("pick_timeout_no_knock", outcome(hostPick.handle(TimerFired(1L, TimerKind.PICK, 0))).reason)
        val guestPick = model(AttemptRole.GUEST).apply { toDiscoveringGuest() }
        assertEquals("pick_timeout", outcome(guestPick.handle(TimerFired(1L, TimerKind.PICK, 0))).reason)
        assertTrue(guestPick.handle(TimerFired(1L, TimerKind.ATTEMPT, 0)).isEmpty())

        val request = model(AttemptRole.GUEST).apply { toRequestingGuest() }
        assertEquals("ndp_timeout", outcome(request.handle(TimerFired(1L, TimerKind.REQUEST, 0))).reason)

        val address = model(AttemptRole.GUEST).apply { toAddressingGuest() }
        val addressEnd = outcome(address.handle(TimerFired(1L, TimerKind.ADDRESS, 0)))
        assertEquals("address_timeout", addressEnd.reason)
        assertNull(addressEnd.fault)

        val connectHost = model(AttemptRole.HOST).apply {
            toDiscoveringHost()
            handle(KnockIn(1L, 1, true, gN(1)))
            val challenge = handle(KnockIn(1L, 1, true, gN(1))).challenges().first()
            handle(PickIn(1L, true, challenge.c))
            handle(RequestFiled(1L, true))
            handle(NetworkReady(1L, PathFacts(listOf(5180), false)))
        }
        assertEquals("no_connect", outcome(connectHost.handle(TimerFired(1L, TimerKind.CONNECT, 0))).reason)
        val connectGuest = model(AttemptRole.GUEST).apply { toConnectingGuest() }
        assertEquals("connect_timeout", outcome(connectGuest.handle(TimerFired(1L, TimerKind.CONNECT, 0))).reason)

        val bind = model(AttemptRole.GUEST).apply { toBindingGuest() }
        assertEquals("bind_timeout", outcome(bind.handle(TimerFired(1L, TimerKind.BIND, 0))).reason)

        for (phaseModel in listOf(
            model(AttemptRole.GUEST),
            model(AttemptRole.GUEST).apply { handle(Start(1L)) },
            model(AttemptRole.GUEST).apply { toDiscoveringGuest() },
            model(AttemptRole.GUEST).apply { toRequestingGuest() },
            model(AttemptRole.GUEST).apply { toAddressingGuest() },
            model(AttemptRole.GUEST).apply { toConnectingGuest() },
            model(AttemptRole.GUEST).apply { toBindingGuest() },
            model(AttemptRole.GUEST).apply { toHandingOffGuest() },
        )) {
            val end = outcome(phaseModel.handle(TimerFired(1L, TimerKind.ATTEMPT, 0)))
            assertEquals("attempt_timeout", end.reason)
            assertEquals(OutcomeKind.FAILED_PEER, end.kind)
        }
        // The ninth phase, DONE, ignores every event (global rule 2).
        val done = model(AttemptRole.GUEST).apply { handle(Command(1L, Cmd.HANGUP)) }
        assertTrue(done.handle(TimerFired(1L, TimerKind.ATTEMPT, 0)).isEmpty())

        val stuck = model(AttemptRole.GUEST).apply { toDiscoveringGuest() }
        val stuckEnd = outcome(stuck.handle(SessionFailed(1L, "message_stuck", true)))
        assertEquals(OutcomeKind.FAILED_LOCAL, stuckEnd.kind)
        assertNull(stuckEnd.fault)
        assertTrue(stuck.handle(SessionFailed(1L, "message_stuck", true)).isEmpty())

        val configFailed = model(AttemptRole.GUEST).apply { toDiscoveringGuest() }
        val configEnd = outcome(configFailed.handle(SessionFailed(1L, "config_failed", true)))
        assertEquals("session_config_failed", configEnd.reason)
        assertNull(configEnd.fault)

        val terminated = model(AttemptRole.GUEST).apply { toRequestingGuest() }
        assertTrue(terminated.handle(SessionTerminated(1L)).none { it is End })
        assertEquals(Phase.REQUESTING, terminated.phase)
    }

    @Test
    fun aHostSessionFailureAfterHostDeclinesSixButBeforeHostSendsNothing() {
        fun requestingHost() = model(AttemptRole.HOST).apply {
            toDiscoveringHost()
            val challenge = handle(KnockIn(1L, 1, true, gN(1))).challenges().first()
            handle(PickIn(1L, true, challenge.c))
            assertEquals(Phase.REQUESTING, phase)
        }
        for (reason in listOf("message_stuck", "config_failed")) {
            for (m in listOf(model(AttemptRole.HOST).apply { toDiscoveringHost() }, requestingHost())) {
                val effects = m.handle(SessionFailed(1L, reason, true))
                assertEquals(reason, listOf(6), effects.declines().map { it.reason })
                val end = effects.indexOfFirst { it is End }
                assertTrue(effects.indexOfFirst { it is SendDecline } in 0 until end)
                assertNull(effects.ends().single().outcome.fault)
            }
            val attaching = model(AttemptRole.HOST).apply { handle(Start(1L)) }
            val early = attaching.handle(SessionFailed(1L, reason, true))
            assertTrue(early.declines().isEmpty())
            assertEquals(1, early.ends().size)
        }
    }

    @Test
    fun networkUnavailableIsAPeerFailureWithoutAFault() {
        val refused = model(AttemptRole.GUEST).apply { toRequestingGuest() }
        val refusedEnd = refused.handle(NetworkUnavailable(1L, 800, false)).ends().single().outcome
        assertEquals(OutcomeKind.FAILED_PEER, refusedEnd.kind)
        assertEquals("ndp_refused", refusedEnd.reason)
        assertNull(refusedEnd.fault)
        val timeout = model(AttemptRole.GUEST).apply { toRequestingGuest() }
        val timeoutEnd = timeout.handle(NetworkUnavailable(1L, 14_000, true)).ends().single().outcome
        assertEquals("ndp_timeout", timeoutEnd.reason)
        assertNull(timeoutEnd.fault)
    }

    @Test
    fun bandRules() {
        fun connecting(
            channels: List<Int>?,
            accepts: Boolean = false,
            guest: Boolean = true,
            portKnown: Boolean = true,
            strict: Boolean = false,
        ): List<AttemptEffect> {
            val m = model(
                if (guest) AttemptRole.GUEST else AttemptRole.HOST,
                acceptsUnverifiedBand = accepts,
                bandStrict = strict,
            )
            if (guest) {
                m.toRequestingGuest()
            } else {
                m.toDiscoveringHost()
                m.handle(KnockIn(1L, 1, true, gN(1)))
                val challenge = m.handle(KnockIn(1L, 1, true, gN(1))).challenges().first()
                m.handle(PickIn(1L, true, challenge.c))
                m.handle(RequestFiled(1L, true))
            }
            return m.handle(NetworkReady(1L, PathFacts(channels, portKnown)))
        }
        assertTrue(connecting(listOf(5180, 5745)).none { it is End })
        // AP9: the 2.4 GHz NAN social channel beside the 5 GHz data channel is accepted; any other 2.4 GHz channel is not.
        assertTrue(connecting(listOf(5745, 2437)).none { it is End })
        assertEquals("band_low", connecting(listOf(2412, 5745)).ends().single().outcome.reason)
        // The strict control arm restores the old all-channels-5GHz rule.
        assertEquals("band_low", connecting(listOf(5745, 2437), strict = true).ends().single().outcome.reason)
        assertTrue(connecting(listOf(5180, 5745), strict = true).none { it is End })
        assertEquals("band_low", connecting(listOf(2437)).ends().single().outcome.reason)
        assertEquals(7, connecting(listOf(2437)).ends().single().outcome.decline)
        assertEquals("band_low", connecting(emptyList()).ends().single().outcome.reason)
        assertEquals("band_unknown", connecting(null).ends().single().outcome.reason)
        assertTrue(connecting(null, accepts = true).none { it is End })
        assertEquals("no_port", connecting(listOf(5180), portKnown = false).ends().single().outcome.reason)
    }

    @Test
    fun aTwoPointFourOnlyPathFollowsTheSwitchAndTheHopsFact() {
        fun connecting(channels: List<Int>, alone24: Boolean, otherBt: Boolean, guest: Boolean): List<AttemptEffect> {
            val m = model(if (guest) AttemptRole.GUEST else AttemptRole.HOST)
            if (guest) {
                m.toRequestingGuest()
            } else {
                m.toDiscoveringHost()
                m.handle(KnockIn(1L, 1, true, gN(1)))
                val challenge = m.handle(KnockIn(1L, 1, true, gN(1))).challenges().first()
                m.handle(PickIn(1L, true, challenge.c))
                m.handle(RequestFiled(1L, true))
            }
            return m.handle(NetworkReady(1L, PathFacts(channels, true, alone24, otherBt)))
        }
        for (guest in listOf(true, false)) {
            // Switch on, no other Bluetooth media on this phone: a 2.4 GHz-only path connects.
            assertTrue(connecting(listOf(2412), alone24 = true, otherBt = false, guest = guest).none { it is End })
            // Other Bluetooth media on this phone: the 5 GHz rule, declined with the band code and without a penalty.
            val refused = connecting(listOf(2412), alone24 = true, otherBt = true, guest = guest).ends().single().outcome
            assertEquals("band_low", refused.reason)
            assertEquals(7, refused.decline)
            assertEquals(OutcomeKind.NO_PENALTY, refused.kind)
            // A 5 GHz data channel with the social channel is accepted either way.
            assertTrue(connecting(listOf(5745, 2437), alone24 = true, otherBt = true, guest = guest).none { it is End })
            assertTrue(connecting(listOf(5745, 2437), alone24 = true, otherBt = false, guest = guest).none { it is End })
            // 2412 beside 5745 is not a 2.4 GHz-only path: refused as before.
            assertEquals(
                "band_low",
                connecting(listOf(2412, 5745), alone24 = true, otherBt = false, guest = guest).ends().single().outcome.reason,
            )
            // Switch off (the default): the 5 GHz rule even with no other Bluetooth media.
            assertEquals(
                "band_low",
                connecting(listOf(2412), alone24 = false, otherBt = false, guest = guest).ends().single().outcome.reason,
            )
        }
    }

    @Test
    fun peerDeclineMapping() {
        for (reason in listOf(1, 2, 3, 4, 5, 7)) {
            val m = model(AttemptRole.GUEST).apply { toRequestingGuest() }
            val effects = m.handle(DeclineIn(1L, true, reason))
            val end = effects.ends().single()
            assertEquals("reason $reason", OutcomeKind.NO_PENALTY, end.outcome.kind)
            assertTrue(effects.declines().isEmpty())
            assertEquals("reason $reason", reason == 2, end.outcome.busy)
        }
        val failed = model(AttemptRole.GUEST).apply { toRequestingGuest() }
        assertEquals(OutcomeKind.FAILED_PEER, failed.handle(DeclineIn(1L, true, 6)).ends().single().outcome.kind)
        val stale = model(AttemptRole.GUEST).apply { toRequestingGuest() }
        assertTrue(stale.handle(DeclineIn(1L, false, 6)).none { it is End })
        assertEquals(Phase.REQUESTING, stale.phase)
    }

    @Test
    fun cancellationsInEveryPhase() {
        fun reach(phase: Phase): AttemptModel {
            val m = model(AttemptRole.GUEST)
            when (phase) {
                Phase.IDLE -> Unit
                Phase.ATTACHING -> m.handle(Start(1L))
                Phase.DISCOVERING -> m.toDiscoveringGuest()
                Phase.REQUESTING -> m.toRequestingGuest()
                Phase.ADDRESSING -> m.toAddressingGuest()
                Phase.CONNECTING -> m.toConnectingGuest()
                Phase.BINDING -> m.toBindingGuest()
                Phase.HANDING_OFF -> m.toHandingOffGuest()
                // DONE is reached by the very command under test; a second command must stay silent (asserted below).
                Phase.DONE -> m.toHandingOffGuest()
            }
            return m
        }
        for (phase in Phase.entries) {
            for (cmd in Cmd.entries) {
                val m = reach(phase)
                val effects = m.handle(Command(1L, cmd))
                val end = effects.ends().single()
                assertEquals(phase.name + "/" + cmd, OutcomeKind.CANCELLED, end.outcome.kind)
                when (cmd) {
                    Cmd.SWITCH_OFF, Cmd.VIOLATION -> assertEquals(1, effects.declines().single().reason)
                    Cmd.RENEWAL -> assertEquals(2, effects.declines().single().reason)
                    Cmd.HANGUP, Cmd.WIPE, Cmd.SHUTDOWN -> assertTrue(effects.declines().isEmpty())
                }
                assertTrue(m.handle(Command(1L, cmd)).isEmpty())
            }
        }
    }

    @Test
    fun awareDownBeforeHandOffIsALocalFaultButAfterIsIgnored() {
        val m = model(AttemptRole.GUEST).apply { toRequestingGuest() }
        val end = m.handle(AwareDown(1L, AwareDownReason.WIFI_OFF)).ends().single().outcome
        assertEquals(OutcomeKind.FAILED_LOCAL, end.kind)
        assertEquals("wifi_off_setup", end.fault)
        assertEquals(3, end.decline)
        val handing = model(AttemptRole.GUEST).apply { toHandingOffGuest() }
        assertTrue(handing.handle(AwareDown(1L, AwareDownReason.WIFI_OFF)).isEmpty())
    }

    @Test
    fun ourOwnShutdownDuringSetupIsACancellationWithoutAFault() {
        val m = model(AttemptRole.GUEST).apply { toRequestingGuest() }
        val end = m.handle(AwareDown(1L, AwareDownReason.SHUTDOWN)).ends().single().outcome
        assertEquals(OutcomeKind.CANCELLED, end.kind)
        assertNull(end.fault)
        val direct = model(AttemptRole.GUEST, WifiBearer.DIRECT).apply { handle(Start(1L)) }
        val directEnd = direct.handle(AwareDown(1L, AwareDownReason.SHUTDOWN)).ends().single().outcome
        assertEquals(OutcomeKind.CANCELLED, directEnd.kind)
        assertNull(directEnd.fault)
    }

    @Test
    fun aDirectHostIgnoresCreds() {
        val m = model(AttemptRole.HOST, WifiBearer.DIRECT)
        m.handle(Start(1L))
        m.handle(BearerPrepared(1L))
        val effects = m.handle(CredsIn(1L, true, 4444, byteArrayOf(10, 0, 0, 2)))
        assertTrue(effects.none { it is ConnectBearer || it is Cancel || it is End })
        assertTrue(m.handle(BearerJoined(1L, 5180)).filterIsInstance<ConnectBearer>().isEmpty())
    }

    @Test
    fun attachFailureMapping() {
        for (reason in AttachFailure.entries) {
            val noPenalty = reason in setOf(
                AttachFailure.BUSY_DIRECT, AttachFailure.BACKOFF, AttachFailure.DISABLED, AttachFailure.NO_MANAGER,
                AttachFailure.NO_PERMISSION, AttachFailure.WIFI_OFF, AttachFailure.UNAVAILABLE,
            )
            val guest = model(AttemptRole.GUEST).apply { handle(Start(1L)) }
            val guestEffects = guest.handle(AttachFailed(1L, reason))
            val guestEnd = guestEffects.ends().single().outcome
            assertEquals(
                if (noPenalty) OutcomeKind.NO_PENALTY else OutcomeKind.FAILED_LOCAL,
                guestEnd.kind,
            )
            assertEquals(reason.declineReason, guestEffects.declines().single().reason)
            assertNull(guestEnd.fault)
            assertEquals(reason == AttachFailure.BUSY_DIRECT, guestEnd.busy)
            val host = model(AttemptRole.HOST).apply { handle(Start(1L)) }
            val hostEffects = host.handle(AttachFailed(1L, reason))
            assertTrue(hostEffects.declines().isEmpty())
            assertNull(hostEffects.ends().single().outcome.fault)
        }
    }

    @Test
    fun sendFailureEndsQuietly() {
        val m = model(AttemptRole.HOST).apply { toDiscoveringHost() }
        val effects = m.handle(SendFailed(1L, "host"))
        assertEquals(OutcomeKind.CANCELLED, effects.ends().single().outcome.kind)
        assertTrue(effects.declines().isEmpty())
    }

    @Test
    fun guestKnocksFourAtATimeAndEvictsAfterThreeSends() {
        val m = model(AttemptRole.GUEST)
        m.toDiscoveringGuest()
        val discovered = ArrayList<AttemptEffect>()
        for (handle in 1..5) discovered += m.handle(PeerDiscovered(1L, handle))
        assertEquals(listOf(1, 2, 3, 4), discovered.knocks().map { it.ordinal })
        assertTrue(discovered.any { it is LogEvent && it.event == "knock_wait" })

        val first = m.handle(TimerFired(1L, TimerKind.KNOCK_RESEND, 0))
        assertEquals(4, first.knocks().size)
        val second = m.handle(TimerFired(1L, TimerKind.KNOCK_RESEND, 0))
        assertEquals(4, second.knocks().size)
        val third = m.handle(TimerFired(1L, TimerKind.KNOCK_RESEND, 0))
        assertEquals(4, third.filterIsInstance<LogEvent>().count { it.event == "knock_evict" })
        assertEquals(listOf(5), third.knocks().map { it.ordinal })

        val many = model(AttemptRole.GUEST)
        many.toDiscoveringGuest()
        for (handle in 1..17) many.handle(PeerDiscovered(1L, handle))
        assertTrue(many.handle(PeerDiscovered(1L, 18)).any { it is LogEvent && it.event == "handle_cap" })
    }

    @Test
    fun fourSpoofedHandlesDiscoveredBeforeTheGenuineOneStillLetTheGenuineOneBePicked() {
        val m = model(AttemptRole.GUEST)
        m.toDiscoveringGuest()
        for (handle in 1..5) m.handle(PeerDiscovered(1L, handle))
        for (handle in 1..4) m.handle(ChallengeIn(1L, handle, false, null))
        repeat(3) { m.handle(TimerFired(1L, TimerKind.KNOCK_RESEND, 0)) }
        val picked = m.handle(ChallengeIn(1L, 5, true, c(5)))
        assertTrue(picked.any { it is SendPick })
    }

    @Test
    fun aSpoofedHandleWithAnInventedChallengeDoesNotPin() {
        val m = model(AttemptRole.GUEST)
        m.toDiscoveringGuest()
        m.handle(PeerDiscovered(1L, 1))
        m.handle(PeerDiscovered(1L, 2))
        assertTrue(m.handle(ChallengeIn(1L, 1, false, null)).none { it is SendPick })
        assertTrue(m.handle(ChallengeIn(1L, 2, true, c(2))).any { it is SendPick })
    }

    @Test
    fun pickTimeoutAfterSpoofingIsFailedSpoofed() {
        val m = model(AttemptRole.GUEST)
        m.toDiscoveringGuest()
        m.handle(PeerDiscovered(1L, 1))
        m.handle(ChallengeIn(1L, 1, false, null))
        val effects = m.handle(TimerFired(1L, TimerKind.PICK, 0))
        val end = effects.ends().single().outcome
        assertEquals(OutcomeKind.FAILED_SPOOFED, end.kind)
        assertEquals(4, end.decline)
        assertNull(end.fault)
        assertEquals(4, effects.declines().single().reason)

        val clean = model(AttemptRole.GUEST)
        clean.toDiscoveringGuest()
        assertEquals("pick_timeout", clean.handle(TimerFired(1L, TimerKind.PICK, 0)).ends().single().outcome.reason)
        assertTrue(clean.handle(TimerFired(1L, TimerKind.PICK, 0)).isEmpty()) // second timer: nothing, phase DONE

        val verified = model(AttemptRole.GUEST)
        verified.toDiscoveringGuest()
        verified.handle(PeerDiscovered(1L, 1))
        verified.handle(ChallengeIn(1L, 1, true, c(1)))
        assertEquals("pick_timeout", verified.handle(TimerFired(1L, TimerKind.PICK, 0)).ends().single().outcome.reason)
    }

    @Test
    fun aHostDeclineSixAfterSpoofingIsFailedSpoofed() {
        val m = model(AttemptRole.GUEST)
        m.toDiscoveringGuest()
        m.handle(PeerDiscovered(1L, 1))
        m.handle(ChallengeIn(1L, 1, false, null))
        val effects = m.handle(DeclineIn(1L, true, 6))
        val end = effects.ends().single().outcome
        assertEquals(OutcomeKind.FAILED_SPOOFED, end.kind)
        assertEquals("peer_declined_6", end.reason)
        assertNull(end.decline)
        assertNull(end.fault)
    }

    @Test
    fun aHostPickTimeoutWithoutAVerifiedKnockIsNoPenalty() {
        val m = model(AttemptRole.HOST)
        m.toDiscoveringHost()
        val effects = m.handle(TimerFired(1L, TimerKind.PICK, 0))
        val end = effects.ends().single().outcome
        assertEquals(OutcomeKind.NO_PENALTY, end.kind)
        assertEquals("pick_timeout_no_knock", end.reason)
        assertEquals(6, end.decline)
    }

    @Test
    fun theGuestAttemptTimerLeavesAtLeastTwentySecondsAfterHost() {
        for (bearer in WifiBearer.entries) {
            val guest = model(AttemptRole.GUEST, bearer)
            val arms = guest.handle(Start(1L)).filterIsInstance<Arm>()
            val attempt = arms.single { it.kind == TimerKind.ATTEMPT }
            assertTrue("$bearer: ${attempt.ms}", attempt.ms >= 20_000L)
            assertTrue(guest.handle(Start(1L)).isEmpty())
        }
    }

    @Test
    fun directGuestKeepsCredsThatArriveInIdle() {
        val m = model(AttemptRole.GUEST, WifiBearer.DIRECT)
        val creds = m.handle(CredsIn(1L, true, 4444, byteArrayOf(192.toByte(), 168.toByte(), 49, 1)))
        assertTrue(creds.any { it is Cancel && it.kind == TimerKind.PICK })
        m.handle(Start(1L))
        m.handle(BearerPrepared(1L))
        val joined = m.handle(BearerJoined(1L, 5180))
        val connect = joined.filterIsInstance<ConnectBearer>().single()
        assertEquals(4444, connect.port)
        assertEquals(4, connect.ipv4.size)
        assertEquals(Phase.CONNECTING, m.phase)
    }

    @Test
    fun directHostSequenceSkipsDiscovery() {
        val m = model(AttemptRole.HOST, WifiBearer.DIRECT)
        val effects = ArrayList<AttemptEffect>()
        effects += m.handle(Start(1L))
        effects += m.handle(BearerPrepared(1L))
        effects += m.handle(BearerHostReady(1L, 5180, 4545, byteArrayOf(192.toByte(), 168.toByte(), 49, 1)))
        effects += m.handle(SocketAccepted(1L, 1))
        effects += m.handle(BindFinished(1L, 1, BindCode.BOUND, "ok"))
        effects += m.handle(HandOffResult(1L, true))
        val types = effects.nonLog()
        assertTrue(types[0] is PrepareBearer)
        assertTrue(types[1] is StartBearerHost)
        assertTrue(types[2] is SendHost)
        assertTrue(types[3] is SendCreds)
        assertTrue(types[4] is StartBind)
        assertTrue(types[5] is HandOff)
        assertTrue(types[6] is End)
        assertTrue(effects.none { it is AcquireAttach || it is StartSession || it is SendKnock || it is RequestPath || it is Listen })
    }

    @Test
    fun directGuestNeedsHostAndCredsInEitherOrder() {
        fun guest(): AttemptModel {
            val m = model(AttemptRole.GUEST, WifiBearer.DIRECT)
            m.handle(Start(1L))
            m.handle(BearerPrepared(1L))
            return m
        }
        val joinedFirst = guest()
        assertTrue(joinedFirst.handle(BearerJoined(1L, 5180)).filterIsInstance<ConnectBearer>().isEmpty())
        assertEquals(Phase.ADDRESSING, joinedFirst.phase)
        val afterCreds = joinedFirst.handle(CredsIn(1L, true, 4444, byteArrayOf(10, 0, 0, 2)))
        val connect = afterCreds.filterIsInstance<ConnectBearer>().single()
        assertEquals(4444, connect.port)

        val credsFirst = guest()
        credsFirst.handle(CredsIn(1L, true, 4444, byteArrayOf(10, 0, 0, 2)))
        assertEquals(1, credsFirst.handle(BearerJoined(1L, 5180)).filterIsInstance<ConnectBearer>().size)
        assertEquals(Phase.CONNECTING, credsFirst.phase)

        val stale = guest()
        assertTrue(stale.handle(CredsIn(1L, false, 4444, byteArrayOf(10, 0, 0, 2))).filterIsInstance<ConnectBearer>().isEmpty())
    }

    @Test
    fun directFailureMapsToTheDriversDeclineCode() {
        fun endFor(decline: Int, local: Boolean): End {
            val m = model(AttemptRole.GUEST, WifiBearer.DIRECT)
            m.handle(Start(1L))
            val effects = m.handle(BearerFailed(1L, "busy", decline, local))
            return effects.ends().single()
        }
        assertEquals(OutcomeKind.NO_PENALTY, endFor(7, false).outcome.kind)
        assertEquals(7, endFor(7, false).outcome.decline)
        val busy = endFor(2, false).outcome
        assertEquals(OutcomeKind.NO_PENALTY, busy.kind)
        assertTrue(busy.busy)
        assertEquals(OutcomeKind.FAILED_LOCAL, endFor(6, true).outcome.kind)
        assertEquals(OutcomeKind.FAILED_PEER, endFor(6, false).outcome.kind)
        assertNull(endFor(6, true).outcome.fault)
    }

    @Test
    fun aDirectHostThatFailsBeforeHostSendsNoDecline() {
        val m = model(AttemptRole.HOST, WifiBearer.DIRECT)
        m.handle(Start(1L))
        val effects = m.handle(BearerFailed(1L, "prepare", 6, true))
        assertTrue(effects.declines().isEmpty())
        assertEquals("bearer_prepare", effects.ends().single().outcome.reason)
    }

    @Test
    fun aDirectGuestWithoutCredsEndsWithCredsTimeout() {
        val m = model(AttemptRole.GUEST, WifiBearer.DIRECT)
        m.handle(Start(1L))
        m.handle(BearerPrepared(1L))
        m.handle(BearerJoined(1L, 5180))
        val end = m.handle(TimerFired(1L, TimerKind.PICK, 0)).ends().single().outcome
        assertEquals("creds_timeout", end.reason)
        assertEquals(6, end.decline)
    }
}
