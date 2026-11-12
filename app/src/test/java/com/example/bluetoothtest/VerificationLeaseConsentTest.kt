package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design QL: in a verification (BVMID2) session an OFFER or LEASE leaves only for a person this phone's user confirmed and
 * allowed to recognise it during the current Add contact visit; no LEASE follows a withheld OFFER; after a reuse alert or
 * leaving the screen nothing leaves. Whole sessions between two phones, each with the endpoint's ordered dispatch (the
 * actions are produced at once and sent later, on one executor) and the endpoint's send-time checks.
 */
class VerificationLeaseConsentTest {
    private val lease = DiscoveryExchange.PresenceLease(1, 2_000_000, 2962, listOf(ByteArray(32) { 1 }))

    private class Phone(val name: String) {
        val fingerprint = IdentityFingerprint.of(KeyPairSigningIdentity(generateEcKeyPair()))
        val visit = MeetVisit().apply { begin() }
        val verified = HashSet<IdentityFingerprint>()

        /** "Can see when I'm nearby" per person (the vault's switch; not yet asked counts as off, design decision 2026-11-06). */
        val visible = HashMap<IdentityFingerprint, Boolean>()
        var visibilityWriteFails = false

        /** A send-time reason the vault's own lock does not check ([CodeOnlyLeaseRule]). */
        var codeOnlyUnscanned = false
        val sent = ArrayList<String>()
        var deliveries = 0
        var stored = 0
        val queue = ArrayList<Runnable>()
        val dispatch = OrderedPresenceDispatch { queue += it }

        fun runQueued() {
            while (queue.isNotEmpty()) queue.removeAt(0).run()
        }

        /** The user answered "Is this <name>'s screen?" with yes and the shield was stored; [recognise] is the checkbox. */
        fun confirmScan(peer: Phone, recognise: Boolean): Boolean {
            verified += peer.fingerprint
            return QlConsent.confirmScan(visit, peer.fingerprint, recognise, vaultUsable = true) { answer ->
                if (visibilityWriteFails) error("store unavailable")
                visible[peer.fingerprint] = answer
            }
        }
    }

    /** One verification session: [scanner] scanned [displayer]'s code. Mirrors the endpoint's QL wiring. */
    private inner class Session(val scanner: Phone, val displayer: Phone) {
        private val ends = mapOf(scanner to End(scanner, displayer, isScanner = true), displayer to End(displayer, scanner, isScanner = false))

        fun end(of: Phone) = ends.getValue(of)

        inner class End(val me: Phone, val peer: Phone, val isScanner: Boolean) {
            val session = PresenceSession(localEligible = true)
            private var started = false

            /** Displayer: decided when the session opened ([armQrDisplayer]). */
            val mayAnswer = !isScanner &&
                QrLeaseRule.allowed(verificationSession = true, visit = me.visit, peer = peer.fingerprint)

            private val runner = PresenceActionRunner(session, object : PresenceActionRunner.Effects {
                override fun ineligibleReason(): String? = PresenceEligibility.reason(
                    vaultPresent = true,
                    persistent = true,
                    peerState = if (peer.fingerprint in me.verified) VerificationState.VERIFIED else VerificationState.UNVERIFIED,
                    visibleToThem = me.visible[peer.fingerprint] ?: false,
                    codeOnlyUnscanned = me.codeOnlyUnscanned,
                )

                override fun visitAllows(): Boolean = visitAllowsNow()

                override fun sendOffer() {
                    me.sent += "OFFER"
                    other().receive(DiscoveryExchange.RecognitionOffer)
                }

                override fun deliverLease(): PresenceActionRunner.LeaseDelivery {
                    // The vault's locked check (a stored no), then the endpoint's predicate, which needs a yes (AB3 R6).
                    val ok = me.visible[peer.fingerprint] == true && peer.fingerprint in me.verified && visitAllowsNow()
                    if (!ok) return PresenceActionRunner.LeaseDelivery(null)
                    me.deliveries++
                    return PresenceActionRunner.LeaseDelivery(lease)
                }

                override fun sendLease(lease: DiscoveryExchange.PresenceLease) {
                    me.sent += "LEASE"
                    other().receive(lease)
                }

                override fun log(event: String, detail: String) = Unit
            })

            private fun visitAllowsNow() =
                QrLeaseRule.allowed(verificationSession = true, visit = me.visit, peer = peer.fingerprint)

            private fun other() = ends.getValue(peer)

            private fun consume(actions: List<PresenceSession.Action>) {
                runner.run(actions).forEach { if (it is PresenceSession.Action.StoreLease) me.stored++ }
            }

            fun start() {
                if (started) return
                started = true
                me.dispatch.dispatch({ session.start() }, runIfEmpty = true, ::consume)
            }

            fun receive(record: DiscoveryExchange.Record) {
                me.dispatch.dispatch({ session.onRecord(record, peer.fingerprint in me.verified) }, runIfEmpty = false, ::consume)
                // The displayer answers the scanner's OFFER (it never offers first).
                if (record == DiscoveryExchange.RecognitionOffer && !isScanner && mayAnswer) start()
            }
        }

        /** The scanner's user answered; a yes that may offer starts the exchange. Then everything queued runs. */
        fun scannerAnswers(recognise: Boolean) {
            if (scanner.confirmScan(displayer, recognise)) end(scanner).start()
            pump()
        }

        fun pump() {
            while (scanner.queue.isNotEmpty() || displayer.queue.isNotEmpty()) {
                scanner.runQueued()
                displayer.runQueued()
            }
        }
    }

    private fun assertNothingMoved(vararg phones: Phone) = phones.forEach { phone ->
        assertEquals("${phone.name} sent", emptyList<String>(), phone.sent)
        assertEquals("${phone.name} delivered", 0, phone.deliveries)
        assertEquals("${phone.name} stored", 0, phone.stored)
    }

    @Test
    fun bothConsentingMovesExactlyOneOfferAndOneLeaseEachWay() {
        val a = Phone("A")
        val b = Phone("B")
        // Scan 1: B scans A. A has not confirmed B in this visit: nothing moves.
        Session(scanner = b, displayer = a).scannerAnswers(recognise = true)
        assertNothingMoved(a)
        // Scan 2: A scans B. Both confirmed and allowed each other: one OFFER and one LEASE each way.
        b.sent.clear()
        Session(scanner = a, displayer = b).scannerAnswers(recognise = true)
        for (phone in listOf(a, b)) {
            assertEquals(phone.name, listOf("OFFER", "LEASE"), phone.sent)
            assertEquals(phone.name, 1, phone.deliveries)
            assertEquals(phone.name, 1, phone.stored)
        }
    }

    @Test
    fun aDeclinedRecognitionSendsNothingInEitherScanOrder() {
        for (declinerScansFirst in listOf(true, false)) {
            for (writeFails in listOf(false, true)) {
                val decliner = Phone("decliner")
                val other = Phone("other")
                // A "No" that could not be stored must withhold as well (fails closed).
                decliner.visibilityWriteFails = writeFails
                val label = "declinerScansFirst=$declinerScansFirst writeFails=$writeFails"
                if (declinerScansFirst) {
                    Session(scanner = decliner, displayer = other).scannerAnswers(recognise = false)
                    Session(scanner = other, displayer = decliner).scannerAnswers(recognise = true)
                } else {
                    Session(scanner = other, displayer = decliner).scannerAnswers(recognise = true)
                    Session(scanner = decliner, displayer = other).scannerAnswers(recognise = false)
                }
                assertEquals(label, emptyList<String>(), decliner.sent)
                assertEquals(label, 0, decliner.deliveries)
                assertEquals(label, 0, other.stored)
                // The other side never holds a lease of the decliner and stores none from it.
                assertEquals(label, 0, decliner.stored)
            }
        }
    }

    @Test
    fun aWithheldOfferIsNeverFollowedByALeaseWhenThePeerOfferedFirst() {
        // Displayer B: the scanner's OFFER arrives first, so B's own start yields OFFER and LEASE in one batch. B's OFFER
        // is withheld at its send time (a reason the vault's own check does not cover): the LEASE must stay too.
        val a = Phone("A")
        val b = Phone("B")
        Session(scanner = b, displayer = a).scannerAnswers(recognise = true)
        b.sent.clear()
        b.codeOnlyUnscanned = true
        Session(scanner = a, displayer = b).scannerAnswers(recognise = true)
        assertEquals(listOf("OFFER"), a.sent)
        assertEquals(emptyList<String>(), b.sent)
        assertEquals(0, b.deliveries)
        assertEquals(0, a.stored)
    }

    @Test
    fun aScannerWhoseOfferIsWithheldSendsNoLeaseWhenThePeerOfferArrivesBeforeItsOfferIsSent() {
        // The scanner's start is produced (OFFER) but not yet sent when the displayer's OFFER arrives: the session then
        // produces a LEASE in a later batch. The OFFER is withheld at its send time, so the LEASE must stay too.
        val a = Phone("A")
        val b = Phone("B")
        Session(scanner = b, displayer = a).scannerAnswers(recognise = true) // A displayer: nothing (not confirmed yet)
        b.sent.clear()
        val session = Session(scanner = a, displayer = b)
        assertTrue(a.confirmScan(b, recognise = true))
        session.end(a).start() // queued on A, not run
        a.codeOnlyUnscanned = true // changes before the OFFER's send time
        // A peer that offers first (as in an ordinary call): B answers with OFFER and LEASE before A's queue runs.
        session.end(b).receive(DiscoveryExchange.RecognitionOffer)
        b.runQueued()
        session.pump()
        assertFalse(a.sent.contains("LEASE"))
        assertEquals(0, a.deliveries)
        assertEquals(0, b.stored)
        // Nor does it keep the peer's lease: it never offered (Phase 2 item 2).
        assertEquals(0, a.stored)
    }

    @Test
    fun nothingLeavesAfterAReuseAlertOrAfterLeavingTheScreen() {
        for (stop in listOf("reuse_displayer", "reuse_scanner", "left_displayer", "left_scanner")) {
            val a = Phone("A")
            val b = Phone("B")
            Session(scanner = b, displayer = a).scannerAnswers(recognise = true)
            b.sent.clear()
            val stopped = if (stop.endsWith("displayer")) b else a
            val session = Session(scanner = a, displayer = b)
            assertTrue(stop, a.confirmScan(b, recognise = true))
            // The alert or the screen closing comes after both confirmed, before anything is sent.
            if (stop.startsWith("reuse")) stopped.visit.onReuseAlert() else stopped.visit.end()
            session.end(a).start()
            session.pump()
            assertEquals(stop, emptyList<String>(), stopped.sent)
            assertEquals(stop, 0, stopped.deliveries)
            val peer = if (stopped === a) b else a
            assertEquals(stop, 0, peer.stored)
        }
    }

    @Test
    fun theRecognitionAnswerIsStoredBeforeTheVisitLetsThePeerBeAnswered() {
        val a = Phone("A")
        val b = Phone("B")
        var allowedDuringWrite: Boolean? = null
        a.verified += b.fingerprint
        QlConsent.confirmScan(a.visit, b.fingerprint, recognise = false, vaultUsable = true) {
            allowedDuringWrite = QrLeaseRule.allowed(verificationSession = true, visit = a.visit, peer = b.fingerprint)
        }
        assertEquals(false, allowedDuringWrite)
        assertFalse(QrLeaseRule.allowed(verificationSession = true, visit = a.visit, peer = b.fingerprint))
    }

    @Test
    fun anAnswerOrAnEndFromAnotherVisitChangesNothingInTheCurrentOne() {
        val visit = MeetVisit()
        val peer = Phone("P").fingerprint
        val first = visit.begin()
        // A second Add contact screen begins its own visit; the first screen leaving later does not end it.
        val second = visit.begin()
        visit.end(first)
        assertTrue(visit.isOpen(second))
        // An answer written for the first visit after it was replaced does not count in the second.
        visit.confirm(peer, recognise = true, visit = first)
        assertFalse(visit.mayLease(peer))
        visit.confirm(peer, recognise = true, visit = second)
        assertTrue(visit.mayLease(peer))
        // A later "No" for the same person replaces the yes.
        visit.confirm(peer, recognise = false, visit = second)
        assertFalse(visit.mayLease(peer))
        visit.confirm(peer, recognise = true, visit = second)
        visit.end(second)
        assertFalse(visit.mayLease(peer))
        assertFalse(visit.isOpen(second))
    }
}
