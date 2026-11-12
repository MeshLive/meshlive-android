package com.example.bluetoothtest

import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four review findings of 2026-10-19, verified against the code and fixed. */
class CallContentGatesTest {
    private fun source(name: String): String = listOf(
        File("src/main/java/com/example/bluetoothtest/$name"),
        File("app/src/main/java/com/example/bluetoothtest/$name"),
    ).first { it.exists() }.readText().replace("\r\n", "\n")

    // ----- P1: no caller media before the answer -----

    @Test
    fun callMediaIsRefusedUntilTheCallIsAnsweredAndDroppedAfterItEnded() {
        listOf(null, PrivateCallPhase.OUTGOING_INVITE, PrivateCallPhase.OUTGOING_RINGING, PrivateCallPhase.INCOMING_RINGING)
            .forEach { assertEquals("$it", CallMediaGate.Decision.REFUSE, CallMediaGate.decide(it)) }
        assertEquals(CallMediaGate.Decision.ALLOW, CallMediaGate.decide(PrivateCallPhase.NEGOTIATING_MEDIA))
        assertEquals(CallMediaGate.Decision.ALLOW, CallMediaGate.decide(PrivateCallPhase.ACTIVE))
        assertEquals(CallMediaGate.Decision.DROP, CallMediaGate.decide(PrivateCallPhase.ENDED))
        assertEquals(
            setOf(EndToEndContentType.STREAM_HEADER, EndToEndContentType.MEDIA_PACKET, EndToEndContentType.VIDEO_FRAGMENT),
            CallMediaGate.MEDIA_TYPES,
        )
    }

    @Test
    fun theEndpointChecksTheGateBeforeItBuildsAReceiverOrDeliversMedia() {
        val handle = source("AndroidPrivateVideoCall.kt").substringAfter("private fun handleContent(").substringBefore("private fun handleSignal(")
        val gate = handle.indexOf("CallMediaGate.decide(state?.phase")
        assertTrue(gate > 0)
        assertTrue("gate before the stream header branch", gate < handle.indexOf("EndToEndContentType.STREAM_HEADER -> {"))
        assertTrue("gate before any media delivery", gate < handle.indexOf("deliverMedia("))
    }

    // ----- P1: a verification code must match at both layers -----

    @Test
    fun aCodeMatchedAtOneLayerOnlyEndsTheConnection() {
        assertTrue(QrLayerBinding.consistent(linkMatched = true, endpointMatched = true))
        assertTrue(QrLayerBinding.consistent(linkMatched = false, endpointMatched = false))
        assertFalse("link tagged, untagged end-to-end handshake: was an ordinary call", QrLayerBinding.consistent(true, false))
        assertFalse(QrLayerBinding.consistent(false, true))
        val call = source("AndroidPrivateVideoCall.kt")
        val check = call.indexOf("QrLayerBinding.consistent(link.qrTagMatched, endpoint.qrTagMatched)")
        assertTrue(check > 0 && check < call.indexOf("if (!initiator && endpoint.qrTagMatched) beginQrVerifySession(endpoint)"))
    }

    // ----- P2: bounded codec configuration -----

    @Test
    fun codecConfigurationIsBoundedAndKeepsTheNewestEntries() {
        val list = mutableListOf<ByteArray>()
        repeat(1_000) { i -> CodecConfigBudget.add(list, ByteArray(1_024) { i.toByte() }) }
        assertEquals(CodecConfigBudget.MAX_ENTRIES, list.size)
        assertTrue(list.sumOf { it.size } <= CodecConfigBudget.MAX_BYTES)
        assertEquals((999).toByte(), list.last()[0])
        repeat(10) { CodecConfigBudget.add(list, ByteArray(100_000)) }
        assertTrue(list.sumOf { it.size } <= CodecConfigBudget.MAX_BYTES)
        CodecConfigBudget.add(list, ByteArray(CodecConfigBudget.MAX_BYTES + 1))
        assertTrue("an oversized entry is never kept", list.none { it.size > CodecConfigBudget.MAX_BYTES })
        val small = mutableListOf<ByteArray>()
        val sps = byteArrayOf(0, 0, 0, 1, 0x67)
        CodecConfigBudget.add(small, sps)
        sps[4] = 0
        assertEquals("a copy is kept", 0x67.toByte(), small.single()[4])
    }

    // ----- P2: a LEASE never overtakes this side's own OFFER -----

    @Test
    fun actionsAreHandedOnInTheOrderTheSessionProducedThem() {
        val session = PresenceSession(localEligible = true)
        val queued = Collections.synchronizedList(mutableListOf<PresenceSession.Action>())
        val dispatch = OrderedPresenceDispatch { task -> task.run() }
        val startProduced = CountDownLatch(1)
        val starter = thread {
            dispatch.dispatch(
                {
                    val actions = session.start()
                    startProduced.countDown()
                    Thread.sleep(100) // the OFFER's send-time check is slow (Keystore): the peer's OFFER arrives meanwhile
                    actions
                },
                runIfEmpty = true,
            ) { queued += it }
        }
        assertTrue(startProduced.await(5, TimeUnit.SECONDS))
        val reader = thread {
            dispatch.dispatch({ session.onRecord(DiscoveryExchange.RecognitionOffer, true) }, runIfEmpty = false) { queued += it }
        }
        starter.join(5_000)
        reader.join(5_000)
        assertEquals(listOf<Any>(PresenceSession.Action.SendOffer, PresenceSession.Action.SendLease), queued.toList())
    }

    @Test
    fun theEndpointRoutesEveryPresenceEventThroughTheOrderedDispatch() {
        val call = source("AndroidPrivateVideoCall.kt")
        assertTrue(call.contains("presenceDispatch.dispatch({ session.start() }, runIfEmpty = true)"))
        assertTrue(call.contains("presenceDispatch.dispatch({ session.onRecord(record, verifiedNow) }, runIfEmpty = false)"))
        assertFalse(call.contains("thread(name = \"PresenceAction\""))
    }

    /**
     * Review 2026-10-27 (finding 7): with "Allow calls" off and the Meet screen open, only a connection that matched the
     * displayed code may continue, whatever handshake it spoke; and the code flow needs BMH3, which carries the code's tag.
     */
    @Test
    fun theMeetScreenAcceptsOnlyTheCodeAndNeedsHiddenIdentities() {
        assertTrue("a plain (BMH2) caller is refused", MeetScreenGate.refuses(screenOnlyReachable = true, linkMatchedCode = false))
        assertFalse(MeetScreenGate.refuses(screenOnlyReachable = true, linkMatchedCode = true))
        assertFalse("reachable anyway: calls go on as usual", MeetScreenGate.refuses(screenOnlyReachable = false, linkMatchedCode = false))
        assertTrue(MeetBridge.available(bvmid2 = true, rotatingIds = true, bmh3 = true, hostRegistered = true))
        assertFalse(MeetBridge.available(bvmid2 = true, rotatingIds = true, bmh3 = false, hostRegistered = true))
    }

    /**
     * reviewer review 2026-10-27 (finding 2): Accept and the caller's reveal decided separately whether the callee's camera and
     * microphone start, so a blocked caller could get media or an honest call stay held. One decision now: whatever order
     * the two threads run in, media starts exactly once for an admitted caller and never for a blocked one.
     */
    @Test
    fun theCalleesMediaStartsOnceAfterAcceptAndAnAdmittedRevealInEitherOrderAndNeverForABlockedCaller() {
        repeat(200) { round ->
            val gate = CalleeMediaStart(revealRequired = true)
            val starts = java.util.concurrent.atomic.AtomicInteger()
            val ready = CountDownLatch(1)
            val ui = thread { ready.await(); if (gate.onAccepted()) starts.incrementAndGet() }
            val reader = thread { ready.await(); if (gate.onRevealAdmitted()) starts.incrementAndGet() }
            ready.countDown()
            ui.join(); reader.join()
            assertEquals("round $round", 1, starts.get())
            assertFalse(gate.held)
        }
        repeat(200) { round ->
            val gate = CalleeMediaStart(revealRequired = true)
            val starts = java.util.concurrent.atomic.AtomicInteger()
            val ready = CountDownLatch(1)
            val ui = thread { ready.await(); if (gate.onAccepted()) starts.incrementAndGet() }
            // The reader found a blocked person: it never admits the reveal.
            val reader = thread { ready.await(); gate.onBlocked() }
            ready.countDown()
            ui.join(); reader.join()
            assertEquals("round $round: nothing for a blocked caller", 0, starts.get())
        }
        // No reveal is required (a contact, or a real-key caller): Accept starts it, once.
        val plain = CalleeMediaStart(revealRequired = false)
        assertTrue(plain.onAccepted())
        assertFalse(plain.onAccepted())
        // Held until the reveal, then started by it.
        val held = CalleeMediaStart(revealRequired = true)
        assertFalse(held.onAccepted())
        assertTrue(held.held)
        assertTrue(held.onRevealAdmitted())
    }

    /**
     * AB3/AP3 step 2b: a blocked call must not carry into the next call on the same endpoint object. The call resets its
     * per-call latch at every new connection (`establish`); with the latch clear, the next call's name plan, media gate
     * and presence eligibility are those of an unblocked peer.
     */
    @Test
    fun aBlockedCallCannotCarryIntoTheNextCall() {
        val latch = BlockedCallLatch()
        latch.block()
        assertTrue("the first call is blocked", latch.active)
        latch.reset() // what establish does when a new connection starts
        assertFalse("the next call starts clear", latch.active)
        val plan = CallLabelPolicy.callerPlan(
            CallerLabelContext(
                labelsNegotiated = true,
                hasName = true,
                audience = NamesAudience.EVERYONE,
                nameBeforeAnswer = false,
                calleeAwaitsReveal = true,
                relayedAwaitsOpening = false,
                calleeIsVerifiedContact = false,
            ),
        )
        assertEquals(LabelSendMoment.AFTER_REVEAL, plan.name)
        assertEquals(CallMediaGate.Decision.ALLOW, CallMediaGate.decide(PrivateCallPhase.ACTIVE, peerRevealPending = false))
        assertNull(PresenceEligibility.reason(
            vaultPresent = true,
            persistent = true,
            peerState = VerificationState.VERIFIED,
            visibleToThem = true,
            blocked = latch.active,
        ))
    }

    /**
     * JT2 (`test_call_camera_first`): the camera-first order changes only what runs after the gate. The media start is entered
     * through [CalleeMediaStart] exactly as before, so with the switch on nothing (no camera, route or microphone step) runs
     * before Accept and, when the caller's reveal is required, before it is admitted, and a blocked caller opens no camera.
     */
    @Test
    fun cameraFirstStartsNothingBeforeAcceptAndAnAdmittedRevealAndNeverOpensACameraForABlockedCaller() {
        fun enter(decided: Boolean, ran: MutableList<CallMediaStartOrder.Step>) {
            if (decided) CallMediaStartOrder.run(cameraFirst = true, video = true) { ran += it; true }
        }

        val ran = mutableListOf<CallMediaStartOrder.Step>()
        val gate = CalleeMediaStart(revealRequired = true)
        assertTrue("ringing: nothing has started", ran.isEmpty())
        enter(gate.onAccepted(), ran)
        assertTrue("accepted, the caller's reveal still pending: nothing has started", ran.isEmpty())
        enter(gate.onRevealAdmitted(), ran)
        assertEquals(CallMediaStartOrder.plan(cameraFirst = true, video = true), ran)
        enter(gate.onRevealAdmitted(), ran) // a repeated signal does not start the media again
        assertEquals(CallMediaStartOrder.plan(cameraFirst = true, video = true).size, ran.size)

        val blockedRan = mutableListOf<CallMediaStartOrder.Step>()
        val blocked = CalleeMediaStart(revealRequired = true)
        blocked.onBlocked()
        enter(blocked.onAccepted(), blockedRan)
        enter(blocked.onRevealAdmitted(), blockedRan)
        assertFalse(CallMediaStartOrder.Step.CAMERA_START in blockedRan)
        assertTrue("a blocked caller gets no camera, route or microphone", blockedRan.isEmpty())

        val answeredFirst = CalleeMediaStart(revealRequired = false)
        val plainRan = mutableListOf<CallMediaStartOrder.Step>()
        enter(answeredFirst.onAccepted(), plainRan)
        assertTrue("no reveal required: Accept alone starts it", CallMediaStartOrder.Step.CAMERA_START in plainRan)
        assertTrue(plainRan.indexOf(CallMediaStartOrder.Step.CAMERA_START) < plainRan.indexOf(CallMediaStartOrder.Step.AUDIO_SESSION))
    }
}
