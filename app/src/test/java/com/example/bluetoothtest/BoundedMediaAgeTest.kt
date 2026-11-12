package com.example.bluetoothtest

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Experimental bounded media age (off by default): codec-safe shedding, budgets, pause and relay order. */
class BoundedMediaAgeTest {
    @Test
    fun theExperimentIsOffByDefault() {
        assertFalse(DebugTunables.experimentalBoundedMediaAge)
        assertEquals("noloss", BoundedMediaAge.policyLabel())
        assertEquals("bounded-experimental", BoundedMediaAge.policyLabel(true))
    }

    @Test
    fun withAQueuedKeyframeOnlyVideoBeforeTheNewestKeyframeIsShed() {
        val items = listOf("audio", "key1", "p1", "config", "key2", "p2", "audio2")
        val plan = planCodecSafeShed(items, ::classify)
        // key1 and p1 go (stale); audio/config stay; key2 and everything after it stay decodable.
        assertEquals(listOf(1, 2), plan.drop)
        assertFalse(plan.awaitKeyFrame)
    }

    @Test
    fun withoutAQueuedKeyframeAllVideoIsShedAndTheQueueWaitsForOne() {
        val items = listOf("p1", "audio", "p2", "config", "p3")
        val plan = planCodecSafeShed(items, ::classify)
        assertEquals(listOf(0, 2, 4), plan.drop)
        assertTrue(plan.awaitKeyFrame)
        assertEquals(ShedPlan(emptyList(), awaitKeyFrame = true), planCodecSafeShed(listOf("audio"), ::classify))
    }

    @Test
    fun aKeyframeOlderThanTheBudgetIsNotAResumePoint() {
        val items = listOf("key-stale", "p1", "audio", "key-fresh", "p2")
        val usable: (String) -> Boolean = { !it.endsWith("stale") }
        assertEquals(ShedPlan(listOf(0, 1), awaitKeyFrame = false), planCodecSafeShed(items, ::classify, usable))
        val onlyStale = listOf("key-stale", "p1", "audio", "p2")
        assertEquals(ShedPlan(listOf(0, 1, 3), awaitKeyFrame = true), planCodecSafeShed(onlyStale, ::classify, usable))
    }

    @Test
    fun shedderWaitsOutGraceAndCooldownAndDropsUntilAKeyframe() {
        val shedder = MediaAgeShedder(cooldownNs = 4 * SECOND)
        assertFalse(shedder.shouldShed(queueAgeMs = 500, budgetMs = 500, largeFrameGrace = false, nowNs = 0))
        assertFalse(shedder.shouldShed(queueAgeMs = 900, budgetMs = 500, largeFrameGrace = true, nowNs = 0))
        assertTrue(shedder.shouldShed(queueAgeMs = 900, budgetMs = 500, largeFrameGrace = false, nowNs = 0))
        shedder.recordShed(ShedPlan(listOf(0, 1), awaitKeyFrame = true), removed = 2, nowNs = 0)
        assertTrue(shedder.awaitingKeyFrame)
        assertTrue(shedder.dropArrivingVideo(keyFrame = false))
        assertFalse(shedder.shouldShed(queueAgeMs = 900, budgetMs = 500, largeFrameGrace = false, nowNs = 5 * SECOND))
        assertFalse(shedder.dropArrivingVideo(keyFrame = true))
        assertFalse(shedder.awaitingKeyFrame)
        assertFalse(shedder.dropArrivingVideo(keyFrame = false))
        assertEquals(3L, shedder.shedFrames)
        assertEquals(1L, shedder.keyFrameWaitFrames)
        // Cooldown: a second shed needs the first one's keyframe and rate cut to act.
        assertFalse(shedder.shouldShed(queueAgeMs = 900, budgetMs = 500, largeFrameGrace = false, nowNs = 3 * SECOND))
        assertTrue(shedder.shouldShed(queueAgeMs = 900, budgetMs = 500, largeFrameGrace = false, nowNs = 4 * SECOND))
    }

    @Test
    fun budgetsSubtractWhatThePeerPlayoutAlreadyHolds() {
        assertEquals(1_750L, BoundedMediaAge.streamQueueBudgetMs(viewerAddedDelayMs = 0))
        assertEquals(1_250L, BoundedMediaAge.streamQueueBudgetMs(viewerAddedDelayMs = 500))
        // A pinned viewer still leaves the rate controller's congestion backlog before shedding.
        assertEquals(400L, BoundedMediaAge.streamQueueBudgetMs(viewerAddedDelayMs = 1_750))
        assertEquals(150L, BoundedMediaAge.callVideoQueueBudgetMs(peerAddedDelayMs = 0, peerBaseDelayMs = 250))
        assertEquals(100L, BoundedMediaAge.callVideoQueueBudgetMs(peerAddedDelayMs = 300, peerBaseDelayMs = 250))
        assertEquals(370L, BoundedMediaAge.callVideoQueueBudgetMs(peerAddedDelayMs = 0, peerBaseDelayMs = 30))
        assertEquals(75L, BoundedMediaAge.thinThresholdMs(150))
    }

    @Test
    fun recurringShedsPauseVideoAndRecoveryBacksOffExponentially() {
        val governor = CallVideoPauseGovernor()
        assertFalse(governor.onShed(0))
        assertFalse(governor.onShed(4 * SECOND))
        // The first shed left the 10 s window: still two in it.
        assertFalse(governor.onShed(11 * SECOND))
        assertTrue(governor.onShed(12 * SECOND))
        assertTrue(governor.paused)
        assertEquals(2_000L, governor.currentBackoffMs)
        // Not before the backoff, not with a backlog, not while the peer reports late media.
        assertFalse(governor.shouldResume(13 * SECOND, queueAgeMs = 0, budgetMs = 150, peerLate = false))
        assertFalse(governor.shouldResume(14 * SECOND, queueAgeMs = 100, budgetMs = 150, peerLate = false))
        assertFalse(governor.shouldResume(14 * SECOND, queueAgeMs = 0, budgetMs = 150, peerLate = true))
        assertTrue(governor.shouldResume(14 * SECOND, queueAgeMs = 0, budgetMs = 150, peerLate = false))
        governor.onResumed(14 * SECOND)
        assertEquals(2_000L, governor.pausedMs(20 * SECOND))
        // Pausing again soon after resuming doubles the wait.
        governor.onShed(15 * SECOND)
        governor.onShed(16 * SECOND)
        assertTrue(governor.onShed(17 * SECOND))
        assertEquals(4_000L, governor.currentBackoffMs)
        assertEquals(2L, governor.pauseEvents)
        assertEquals(3_000L, governor.pausedMs(18 * SECOND))
    }

    @Test
    fun theRelayChildOnlyExperimentEnablesRelayChildSheddingWithoutTheGlobalPolicy() {
        // Both experiments off: the relay child queue stays no-loss (default).
        assertFalse(BoundedMediaAge.relayChildEnabled(global = false, relayChildOnly = false))
        // Relay-child-only: the forwarder's child queue sheds; calls and senders still read the
        // global flag, which stays off.
        assertTrue(BoundedMediaAge.relayChildEnabled(global = false, relayChildOnly = true))
        assertTrue(BoundedMediaAge.relayChildEnabled(global = true, relayChildOnly = false))
    }

    @Test
    fun relayChildSheddingIsOnByDefaultAndLeavesTheGlobalPolicyOff() {
        // Design decision 2026-10-09: a slow relay child sheds to a keyframe instead of being withdrawn.
        assertTrue(DebugTunables.experimentalRelayChildMediaAge)
        assertTrue(BoundedMediaAge.relayChildEnabled())
        assertEquals("noloss", BoundedMediaAge.policyLabel())
    }

    @Test
    fun relayRecoveryRequestsBackOffToTheCapAndResetAfterACalmMinute() {
        val backoff = RelayRecoveryBackoff()
        val ms = 1_000_000L
        assertTrue(backoff.tryRequest(0))
        assertFalse(backoff.tryRequest(1_000 * ms))
        assertTrue(backoff.tryRequest(1_500 * ms))
        // Interval now 3 s.
        assertFalse(backoff.tryRequest(4_000 * ms))
        assertTrue(backoff.tryRequest(4_500 * ms))
        var nowNs = 4_500 * ms
        repeat(6) {
            nowNs += backoff.intervalNs
            assertTrue(backoff.tryRequest(nowNs))
        }
        assertEquals(BoundedMediaAge.RELAY_RECOVERY_MAX_INTERVAL_NS, backoff.intervalNs)
        assertFalse(backoff.tryRequest(nowNs + BoundedMediaAge.RELAY_RECOVERY_MAX_INTERVAL_NS - 1))
        assertEquals(3L, backoff.suppressed)
        // A calm minute: the next request goes at once and the interval is short again.
        nowNs += BoundedMediaAge.RELAY_RECOVERY_CALM_NS
        assertTrue(backoff.tryRequest(nowNs))
        assertEquals(BoundedMediaAge.KEY_FRAME_REREQUEST_NS, backoff.intervalNs)
    }

    @Test
    fun aPersistentlySlowChildCostsAtMostOneRecoveryKeyframePerCapInterval() {
        // Review finding 2026-10-09: a child that sheds again and again must not make the
        // source send a keyframe on every branch every 1.5 s.
        val key = ByteArray(MeshAead.KEY_BYTES) { 8 }
        val sealer = EndToEndContentSealer(key)
        var nowNs = 0L
        val bound = RelayMediaAgeBound()
        val queue = RelayChildQueue(mediaAgeBound = bound, nanoTime = { nowNs })
        var sequence = 0L
        val frame = 66_666_667L
        // 120 s at 15 FPS; the link never drains (nothing is taken); natural keyframes every 60 s (the app default).
        while (nowNs < 120 * SECOND) {
            val keyFrame = sequence % 900 == 0L
            queue.offer(sealed(sealer, sequence, EndToEndContentType.MEDIA_PACKET, if (keyFrame) MeshEnvelope.FLAG_KEY_FRAME else 0))
            sequence++
            nowNs += frame
        }
        // Backoff 0, 1.5, 4.5, 10.5, 22.5 s then every 15 s: at most about 11 requests, against ~80 at a fixed 1.5 s.
        assertTrue("requests ${bound.keyFrameRequests}", bound.keyFrameRequests in 1L..12L)
        assertEquals(BoundedMediaAge.RELAY_RECOVERY_MAX_INTERVAL_NS, bound.recovery.intervalNs)
        assertTrue(queue.mediaAgeSummary().contains("recoveryIntervalMs=15000"))
    }

    @Test
    fun aShedKeepsSpeechSoTheWithdrawalPolicyStillSeesASpeechBacklog() {
        // Shedding removes video only: a link that cannot carry even the audio keeps an old backlog,
        // so RelayChildHealthPolicy can still withdraw it.
        val key = ByteArray(MeshAead.KEY_BYTES) { 9 }
        val sealer = EndToEndContentSealer(key)
        var nowNs = 0L
        val queue = RelayChildQueue(mediaAgeBound = RelayMediaAgeBound(), nanoTime = { nowNs })
        queue.offer(sealed(sealer, 1, EndToEndContentType.MEDIA_PACKET, MeshEnvelope.FLAG_AUDIO))
        queue.offer(sealed(sealer, 2, EndToEndContentType.MEDIA_PACKET, MeshEnvelope.FLAG_KEY_FRAME))
        queue.offer(sealed(sealer, 3, EndToEndContentType.MEDIA_PACKET))
        nowNs = 3 * SECOND
        assertEquals(ChildQueueOffer.SHED_WAITING_FOR_KEYFRAME, queue.offer(sealed(sealer, 4, EndToEndContentType.MEDIA_PACKET)))
        assertEquals(1, queue.size())
        assertEquals(3_000L, queue.backlogAgeMs())
        assertTrue(RelayChildHealthPolicy().shouldWithdraw(RelayChildHealthPolicy.HARD_BACKLOG_MS, nowNs))
    }

    @Test
    fun theRelaySkipSettingFollowsTheBenchmarkExtraElseTheStoredChoiceWithOrWithoutAdvanced() {
        assertFalse(relayChildMediaAgeSetting(benchmarkLaunch = true, benchmarkExtra = false, storedChoice = true))
        assertTrue(relayChildMediaAgeSetting(benchmarkLaunch = true, benchmarkExtra = true, storedChoice = false))
        // Switch audit B14: a user's OFF stays off when Advanced is turned off (it used to revert to on).
        assertFalse(relayChildMediaAgeSetting(benchmarkLaunch = false, benchmarkExtra = true, storedChoice = false))
        assertTrue(relayChildMediaAgeSetting(benchmarkLaunch = false, benchmarkExtra = false, storedChoice = true))
    }

    @Test
    fun withoutTheExperimentARelayChildQueueNeverSheds() {
        var nowNs = 0L
        val queue = RelayChildQueue(nanoTime = { nowNs })
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(media(0, key = true)))
        nowNs = 10 * SECOND
        repeat(5) { assertEquals(ChildQueueOffer.QUEUED, queue.offer(media(1L + it))) }
        assertEquals(6, queue.size())
    }

    @Test
    fun aRelayShedSkipsToTheNextKeyframeAndTheChildStillOpensItInOrder() {
        val key = ByteArray(MeshAead.KEY_BYTES) { 7 }
        val sealer = EndToEndContentSealer(key)
        var nowNs = 0L
        val queue = RelayChildQueue(mediaAgeBound = RelayMediaAgeBound(), nanoTime = { nowNs })
        val header = sealed(sealer, 0, EndToEndContentType.STREAM_HEADER)
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(header))
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(sealed(sealer, 1, EndToEndContentType.MEDIA_PACKET, MeshEnvelope.FLAG_KEY_FRAME)))
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(sealed(sealer, 2, EndToEndContentType.MEDIA_PACKET)))
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(sealed(sealer, 3, EndToEndContentType.MEDIA_PACKET, MeshEnvelope.FLAG_AUDIO)))
        // The child link stalls for 3 s: the queue is far older than the 1.75 s budget.
        nowNs = 3 * SECOND
        assertEquals(
            ChildQueueOffer.SHED_WAITING_FOR_KEYFRAME,
            queue.offer(sealed(sealer, 4, EndToEndContentType.MEDIA_PACKET)),
        )
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(sealed(sealer, 5, EndToEndContentType.STREAM_STATE, MeshEnvelope.FLAG_CONTROL)))
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(sealed(sealer, 6, EndToEndContentType.MEDIA_PACKET, MeshEnvelope.FLAG_AUDIO)))
        // The request at the shed is still outstanding: dropped without another request.
        assertEquals(
            ChildQueueOffer.SHED_DROPPED,
            queue.offer(sealed(sealer, 7, EndToEndContentType.MEDIA_PACKET)),
        )
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(sealed(sealer, 8, EndToEndContentType.MEDIA_PACKET, MeshEnvelope.FLAG_KEY_FRAME)))
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(sealed(sealer, 9, EndToEndContentType.MEDIA_PACKET)))
        val delivered = generateSequence { queue.poll() }.toList()
        // Header, audio and lifecycle survive; stale video before the next keyframe is gone.
        assertEquals(listOf(0L, 3L, 5L, 6L, 8L, 9L), delivered.map(MeshEnvelope::sequence))
        val opener = EndToEndContentOpener(key)
        delivered.forEach { opener.open(it.payload) }
        // Replay protection is intact: a shed (older) record can never be accepted afterwards.
        assertThrows(IllegalArgumentException::class.java) {
            opener.open(sealedPayload(EndToEndContentSealer(key), 10, EndToEndContentType.MEDIA_PACKET, skip = 7))
        }
        assertTrue(queue.mediaAgeSummary().contains("shedEvents=1"))
    }

    @Test
    fun relayShedsWaitForTheKeyframeGraceAfterAKeyframeLeft() {
        val key = ByteArray(MeshAead.KEY_BYTES) { 3 }
        val sealer = EndToEndContentSealer(key)
        var nowNs = 0L
        val queue = RelayChildQueue(mediaAgeBound = RelayMediaAgeBound(), nanoTime = { nowNs })
        queue.offer(sealed(sealer, 0, EndToEndContentType.MEDIA_PACKET, MeshEnvelope.FLAG_KEY_FRAME))
        queue.offer(sealed(sealer, 1, EndToEndContentType.MEDIA_PACKET))
        nowNs = 1 * SECOND
        assertEquals(0L, queue.poll()?.sequence)
        nowNs = 3 * SECOND
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(sealed(sealer, 2, EndToEndContentType.MEDIA_PACKET)))
        nowNs = 4_100_000_000L
        assertEquals(
            ChildQueueOffer.SHED_WAITING_FOR_KEYFRAME,
            queue.offer(sealed(sealer, 3, EndToEndContentType.MEDIA_PACKET)),
        )
    }

    @Test
    fun aStreamHeaderWithZeroOuterFlagsIsNeverTreatedAsVideo() {
        val bound = RelayMediaAgeBound()
        val sealer = EndToEndContentSealer(ByteArray(MeshAead.KEY_BYTES))
        assertEquals(ShedClass.KEEP, bound.classify(sealed(sealer, 0, EndToEndContentType.STREAM_HEADER)))
        assertEquals(ShedClass.VIDEO, bound.classify(sealed(sealer, 1, EndToEndContentType.MEDIA_PACKET)))
        assertEquals(
            ShedClass.KEEP,
            bound.classify(sealed(sealer, 2, EndToEndContentType.MEDIA_PACKET, StreamProtocol.FLAG_DISPLAY_ROTATION)),
        )
        assertEquals(ShedClass.KEEP, bound.classify(media(3).copy(payload = byteArrayOf(1, 2, 3))))
    }

    @Test
    fun shedFramesAreOveruseEvidenceAndTheDefaultThresholdIsUnchanged() {
        val controller = LinkRateController(600_000)
        assertNull(controller.onSample("link", sample(backlogMs = 0, shed = 0), 0))
        val cut = controller.onSample("link", sample(backlogMs = 0, shed = 4), SECOND)
        assertTrue(cut != null && cut < 600_000)
        // Default: a growing 200-300 ms backlog is not congestion.
        val noLoss = LinkRateController(600_000)
        assertNull(noLoss.onSample("link", sample(backlogMs = 200, shed = 0), 0))
        assertNull(noLoss.onSample("link", sample(backlogMs = 300, shed = 0), SECOND))
        // A bounded call with a 150 ms budget treats the same growth as congestion.
        val bounded = LinkRateController(600_000, congestedBacklogMs = 150)
        assertTrue(bounded.onSample("link", sample(backlogMs = 200, shed = 0), 0) != null)
    }

    @Test
    fun continuityReportsTheLongestStallIncludingAnOpenOne() {
        val tracker = RenderedFrameContinuityTracker(fps = 15, startupStartedAtNs = 0)
        tracker.recordFrame(1 * SECOND)
        tracker.recordFrame(1_066_000_000L)
        tracker.recordFrame(1_766_000_000L)
        assertEquals(700.0, tracker.snapshot(1_800_000_000L).longestGapMs, 0.5)
        assertEquals(1_234.0, tracker.snapshot(3_000_000_000L).longestGapMs, 0.5)
    }

    @Test
    fun aRelayShedReRequestsTheKeyframeOnABoundedScheduleUntilOneArrives() {
        val key = ByteArray(MeshAead.KEY_BYTES) { 5 }
        val sealer = EndToEndContentSealer(key)
        var nowNs = 0L
        val bound = RelayMediaAgeBound()
        val queue = RelayChildQueue(mediaAgeBound = bound, nanoTime = { nowNs })
        queue.offer(sealed(sealer, 0, EndToEndContentType.MEDIA_PACKET))
        nowNs = 3 * SECOND
        val video = { sequence: Long -> queue.offer(sealed(sealer, sequence, EndToEndContentType.MEDIA_PACKET)) }
        // The encoder refused or coalesced the first request: frames keep arriving at 15 FPS.
        val results = (1L..60L).map { sequence ->
            nowNs = 3 * SECOND + (sequence - 1) * 66_666_667L
            nowNs to video(sequence)
        }
        val requests = results.filter { it.second == ChildQueueOffer.SHED_WAITING_FOR_KEYFRAME }.map { it.first }
        assertEquals(ChildQueueOffer.SHED_WAITING_FOR_KEYFRAME, results.first().second)
        assertTrue(results.drop(1).all { it.second in setOf(ChildQueueOffer.SHED_DROPPED, ChildQueueOffer.SHED_WAITING_FOR_KEYFRAME) })
        // At the shed, then backing off from 1.5 s (1.5, 3, 6 ... s): 4 s of waiting gives 2 requests,
        // not one per frame.
        assertEquals(2, requests.size)
        requests.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= BoundedMediaAge.KEY_FRAME_REREQUEST_NS) }
        assertEquals(2L, bound.keyFrameRequests)
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(sealed(sealer, 61, EndToEndContentType.MEDIA_PACKET, MeshEnvelope.FLAG_KEY_FRAME)))
        assertEquals(ChildQueueOffer.QUEUED, video(62))
    }

    @Test
    fun theForwardingCoreSendsDueReRequestsPastAnOutstandingRequest() {
        val key = ByteArray(MeshAead.KEY_BYTES) { 6 }
        val sealer = EndToEndContentSealer(key)
        val child = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { 2 })
        val core = ReceiverNodeForwardingCore(
            NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { 1 }),
            NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { 3 }),
            STREAM_ID,
            SOURCE,
            queueCapacity = 8,
            boundedMediaAge = true,
        )
        core.addChild(child)
        val start = core.receive(sealed(sealer, 0, EndToEndContentType.MEDIA_PACKET, MeshEnvelope.FLAG_KEY_FRAME))
        assertEquals(setOf(child), start.queuedChildren)
        assertFalse(start.keyFrameReRequest)
        // Without the experiment the same core never asks for re-requests.
        val noLoss = ReceiverNodeForwardingCore(
            NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { 1 }),
            NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { 3 }),
            STREAM_ID,
            SOURCE,
            queueCapacity = 8,
        )
        noLoss.addChild(child)
        val noLossSealer = EndToEndContentSealer(key)
        assertFalse(noLoss.receive(sealed(noLossSealer, 0, EndToEndContentType.MEDIA_PACKET, MeshEnvelope.FLAG_KEY_FRAME)).keyFrameReRequest)
        assertTrue(noLoss.mediaAgeSummary(child)!!.contains("keyFrameRequests=0"))
    }

    @Test
    fun callCodecConfigurationIsNeverShed() {
        assertEquals(ShedClass.KEEP, callMediaShedClass(StreamProtocol.FLAG_CODEC_CONFIG))
        assertEquals(ShedClass.KEEP, callMediaShedClass(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG))
        assertEquals(ShedClass.KEEP, callMediaShedClass(StreamProtocol.FLAG_AUDIO))
        assertEquals(ShedClass.KEY_FRAME, callMediaShedClass(StreamProtocol.FLAG_KEY_FRAME))
        assertEquals(ShedClass.VIDEO, callMediaShedClass(StreamProtocol.FLAG_DISCARDABLE))
        val csd = StreamProtocol.FLAG_CODEC_CONFIG
        val key = StreamProtocol.FLAG_KEY_FRAME
        val queued = listOf(0, csd, 0, StreamProtocol.FLAG_AUDIO, key, csd, 0)
        val withKey = planCodecSafeShed(queued, ::callMediaShedClass)
        assertEquals(listOf(0, 2), withKey.drop)
        val withoutKey = planCodecSafeShed(listOf(0, csd, 0, csd, 0), ::callMediaShedClass)
        assertEquals(listOf(0, 2, 4), withoutKey.drop)
        assertTrue(withoutKey.drop.none { listOf(0, csd, 0, csd, 0)[it] == csd })
    }

    @Test
    fun theNonReferenceStepIsAv1OnlyAndNonResilientVp9IsNeverMarked() {
        assertTrue(BoundedMediaAge.nonReferenceSkipAllowed(StreamProtocol.CODEC_AV1))
        assertFalse(BoundedMediaAge.nonReferenceSkipAllowed(StreamProtocol.CODEC_VP9))
        // A non-error-resilient VP9 inter frame (hardware output) with refresh_frame_flags == 0.
        val droppability = BitstreamDroppability(android.media.MediaFormat.MIMETYPE_VIDEO_VP9)
        assertEquals(0, BitstreamDroppability.vp9Header(byteArrayOf(0x86.toByte(), 0x00, 0x00, 0x55))!!.refreshFrameFlags)
        val nonResilientNoRefresh = byteArrayOf(0x86.toByte(), 0x00, 0x00, 0x55)
        assertFalse(droppability.isDroppable(nonResilientNoRefresh, keyFrame = false))
        // Once one frame was not error resilient, nothing in the session is ever droppable.
        assertFalse(droppability.isDroppable(byteArrayOf(0x87.toByte(), 0x00, 0x55), keyFrame = false))
    }

    @Test
    fun aShedIsAtomicForTheWriterSoEveryWrittenFrameStaysDecodable() {
        val lock = Object()
        val queue = java.util.concurrent.LinkedBlockingQueue<Int>()
        val written = java.util.Collections.synchronizedList(ArrayList<Int>())
        // Video is written in increasing order, so the newest written video frame says how far the writer got.
        val newestVideo = java.util.concurrent.atomic.AtomicInteger(-1)
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val writer = Thread {
            while (true) {
                val frame = takeNextUnderLock(lock, queue, { it < 0 }, timeoutMs = 5) ?: if (done.get()) break else continue
                written += frame
                if (frame >= 0) newestVideo.set(frame)
            }
        }
        writer.start()
        val shedder = MediaAgeShedder(cooldownNs = 0)
        val classify: (Int) -> ShedClass = { if (it < 0) ShedClass.KEEP else if (it % 10 == 0) ShedClass.KEY_FRAME else ShedClass.VIDEO }
        // Between checkpoints the producer races the writer freely. The writer takes audio first, so on a busy host it can
        // fall behind and every queued video frame is shed before it is reached: a correct run that writes no video and
        // checks nothing. At a checkpoint keyframe k the producer waits, right after each put, until the writer has written
        // k and then k + 1, so every run checks real writes. k is never a multiple of 7: no shed runs at k or before k + 1
        // is queued, so neither can be shed or skipped while the producer waits.
        val checkpointKeys = (0 until 20_000 step 10).filter { it % 500 == 0 && it % 7 != 0 }
        val checkpointFrames = checkpointKeys.flatMap { listOf(it, it + 1) }.toSet()
        for (frame in 0 until 20_000) {
            val queued = !shedder.dropArrivingVideo(frame % 10 == 0)
            if (queued) putUnderLock(lock, queue, frame)
            if (frame in checkpointFrames) {
                assertTrue("checkpoint frame $frame was queued", queued)
                val deadline = System.nanoTime() + 120_000_000_000L
                while (newestVideo.get() < frame && System.nanoTime() < deadline) Thread.sleep(1)
                assertTrue("the writer reached checkpoint frame $frame", newestVideo.get() >= frame)
            }
            if (frame % 3 == 0) putUnderLock(lock, queue, -frame - 1)
            if (frame % 7 == 0) {
                // Stale keyframes are not resume points: the shed waits for the next one.
                val (plan, removed) = shedAtomically(lock, queue, classify) { false }
                shedder.recordShed(plan, removed.size, frame.toLong())
            }
        }
        done.set(true)
        // The writer drains up to ~23k queued items; on a saturated host that can take longer than any short join, and a
        // truncated drain would be misread as lost frames. The generous bound only catches a writer that is truly stuck.
        writer.join(120_000)
        assertFalse("the writer must finish draining", writer.isAlive)
        val video = written.filter { it >= 0 }
        for (key in checkpointKeys) {
            assertTrue("checkpoint keyframe $key and frame ${key + 1} were written", key in video && key + 1 in video)
        }
        // Each written inter frame directly follows its reference; a keyframe may follow a gap.
        video.zipWithNext().forEach { (previous, next) ->
            assertTrue("frame $next written after $previous", next % 10 == 0 || next == previous + 1)
        }
        assertEquals(written.filter { it < 0 }.size, (0 until 20_000).count { it % 3 == 0 })
    }

    @Test
    fun aFullCallQueueMakesRoomFromVideoOnlyAndCodecSafely() {
        val csd = StreamProtocol.FLAG_CODEC_CONFIG
        val audio = StreamProtocol.FLAG_AUDIO
        val audioCsd = audio or csd
        val key = StreamProtocol.FLAG_KEY_FRAME
        val lock = Any()
        val classify: (Item) -> ShedClass = { callMediaShedClass(it.flags) }
        // Configuration arriving at a full queue: video before the newest keyframe goes first.
        var queue = items(capacity = 6, headroom = 4, audioCsd, csd, 0, key, 0, audio)
        var result = offerCodecSafely(lock, queue, Item(csd, 99), 6, critical = true, classify = classify)
        assertTrue(result.queued)
        assertEquals(listOf(2), result.shed.map(Item::id))
        assertFalse(result.awaitKeyFrame)
        assertEquals(listOf(0, 1, 3, 4, 5, 99), queue.map(Item::id))
        // Without a queued keyframe all video goes and the stream waits for one.
        queue = items(capacity = 5, headroom = 4, audioCsd, 0, csd, 0, audio)
        result = offerCodecSafely(lock, queue, Item(audio, 99), 5, critical = true, classify = classify)
        assertEquals(listOf(1, 3), result.shed.map(Item::id))
        assertTrue(result.awaitKeyFrame)
        assertEquals(listOf(0, 2, 4, 99), queue.map(Item::id))
        // An inter frame whose reference was shed is dropped too, and the wait starts.
        queue = items(capacity = 3, headroom = 4, audio, 0, 0)
        result = offerCodecSafely(lock, queue, Item(0, 99), 3, critical = false, classify = classify)
        assertFalse(result.queued)
        assertTrue(result.awaitKeyFrame)
        assertEquals(listOf(0), queue.map(Item::id))
        // A keyframe is the recovery point: it is queued and nothing waits.
        queue = items(capacity = 3, headroom = 4, audio, 0, 0)
        result = offerCodecSafely(lock, queue, Item(key, 99), 3, critical = true, classify = classify)
        assertTrue(result.queued)
        assertFalse(result.awaitKeyFrame)
    }

    @Test
    fun audioAndCodecConfigurationAreNeverEvictedAndUseTheHeadroom() {
        val lock = Any()
        val audio = StreamProtocol.FLAG_AUDIO
        val csd = StreamProtocol.FLAG_CODEC_CONFIG
        val classify: (Item) -> ShedClass = { callMediaShedClass(it.flags) }
        val queue = items(capacity = 3, headroom = 3, audio or csd, csd, audio)
        assertTrue(offerCodecSafely(lock, queue, Item(audio, 10), 3, critical = true, classify = classify).queued)
        assertTrue(offerCodecSafely(lock, queue, Item(StreamProtocol.FLAG_KEY_FRAME, 11), 3, critical = true, classify = classify).queued)
        // Once queued, a keyframe is ordinary video: arriving audio sheds it (audio first) and video waits.
        val audioFirst = offerCodecSafely(lock, queue, Item(audio, 12), 3, critical = true, classify = classify)
        assertTrue(audioFirst.queued)
        assertEquals(listOf(11), audioFirst.shed.map(Item::id))
        assertTrue(audioFirst.awaitKeyFrame)
        assertTrue(offerCodecSafely(lock, queue, Item(csd, 13), 3, critical = true, classify = classify).queued)
        // Headroom exhausted with only audio and configuration queued: the arriving record is refused;
        // nothing queued is evicted.
        val refused = offerCodecSafely(lock, queue, Item(audio, 14), 3, critical = true, classify = classify)
        assertFalse(refused.queued)
        assertTrue(refused.criticalOverflow)
        assertTrue(refused.shed.isEmpty())
        assertEquals(listOf(0, 1, 2, 10, 12, 13), queue.map(Item::id))
        // Video cannot take headroom: an inter frame is dropped and video waits for a keyframe.
        val video = offerCodecSafely(lock, items(capacity = 2, headroom = 2, audio, csd), Item(0, 13), 2, critical = false, classify = classify)
        assertFalse(video.queued)
        assertTrue(video.awaitKeyFrame)
        assertTrue(BoundedMediaAge.criticalHeadroom(16) >= 256)
    }

    @Test
    fun dropsWhileWaitingForAKeyframeKeepRateControlFromClimbing() {
        // The rate was cut to 240 kbps; then the queue is empty while video waits for a keyframe.
        val waiting = LinkRateController(600_000)
        assertTrue(waiting.onSample("link", sample(backlogMs = 0, shed = 3), 0) != null)
        for (window in 1..6) assertNull(waiting.onSample("link", sample(backlogMs = 0, shed = 2), window * SECOND))
        assertEquals(240_000, waiting.effectiveVideoBps)
        // Uncounted, the same empty windows look clean and the rate climbs during the wait.
        val uncounted = LinkRateController(600_000)
        uncounted.onSample("link", sample(backlogMs = 0, shed = 3), 0)
        val climbed = (1..6).mapNotNull { uncounted.onSample("link", sample(backlogMs = 0, shed = 0), it * SECOND) }
        assertTrue(climbed.isNotEmpty() && climbed.last() > 240_000)
    }

    @Test
    fun theCallBudgetAssumesTheLargestPeerCallBaseDelay() {
        // Call setup does not exchange the peer's playout profile.
        assertEquals(PlaybackDelayProfile.CALL_STABLE.delayMs, BoundedMediaAge.ASSUMED_PEER_CALL_BASE_DELAY_MS)
        assertEquals(500L, BoundedMediaAge.ASSUMED_PEER_CALL_BASE_DELAY_MS)
        assertEquals(100L, BoundedMediaAge.callVideoQueueBudgetMs(0, BoundedMediaAge.ASSUMED_PEER_CALL_BASE_DELAY_MS))
    }

    @Test
    fun theAudioOnlyResumeGateCountsQueuedAudio() {
        val governor = CallVideoPauseGovernor()
        repeat(3) { governor.onShed(it * SECOND) }
        assertTrue(governor.paused)
        // Video queue empty but audio has waited 400 ms: the link has not recovered.
        assertFalse(governor.shouldResume(10 * SECOND, queueAgeMs = 400, budgetMs = 100, peerLate = false))
        assertTrue(governor.shouldResume(10 * SECOND, queueAgeMs = 40, budgetMs = 100, peerLate = false))
    }

    @Test
    fun av1VerificationLearnsTheSequenceHeaderFromCodecConfiguration() {
        val sequence = av1Obu(1, av1SequenceHeader())
        val keyFrame = av1Obu(2, ByteArray(0)) + av1Obu(6, byteArrayOf(0x10, 0x3f.toByte()))
        val interNoRefresh = av1Obu(2, ByteArray(0)) + av1Obu(6, av1InterFrameHeader(refresh = 0))
        val interRefresh = av1Obu(2, ByteArray(0)) + av1Obu(6, av1InterFrameHeader(refresh = 1))
        // Before the fix the verifier never saw configuration or keyframes: nothing was ever discardable.
        val blind = BitstreamDroppability(android.media.MediaFormat.MIMETYPE_VIDEO_AV1)
        assertEquals(0, encodedBufferFlags(null, false, false, interNoRefresh))
        assertFalse(blind.isDroppable(interNoRefresh, keyFrame = false))
        for (config in listOf(sequence, byteArrayOf(0x81.toByte(), 0x08, 0x0c, 0x00) + sequence)) {
            val verifier = BitstreamDroppability(android.media.MediaFormat.MIMETYPE_VIDEO_AV1)
            assertEquals(StreamProtocol.FLAG_CODEC_CONFIG, encodedBufferFlags(verifier, true, false, config))
            assertEquals(StreamProtocol.FLAG_KEY_FRAME, encodedBufferFlags(verifier, false, true, keyFrame))
            assertEquals(StreamProtocol.FLAG_DISCARDABLE, encodedBufferFlags(verifier, false, false, interNoRefresh))
            assertEquals(0, encodedBufferFlags(verifier, false, false, interRefresh))
        }
        // A sequence header carried in-band by the keyframe is learned from the keyframe buffer.
        val inBand = BitstreamDroppability(android.media.MediaFormat.MIMETYPE_VIDEO_AV1)
        encodedBufferFlags(inBand, false, true, av1Obu(2, ByteArray(0)) + sequence + av1Obu(6, byteArrayOf(0x10, 0x3f.toByte())))
        assertEquals(StreamProtocol.FLAG_DISCARDABLE, encodedBufferFlags(inBand, false, false, interNoRefresh))
        // VP9 configuration is ignored and VP9 output stays unmarked.
        BitstreamDroppability(android.media.MediaFormat.MIMETYPE_VIDEO_VP9).observeCodecConfig(sequence)
    }

    private class Item(val flags: Int, val id: Int)

    private fun items(capacity: Int, headroom: Int, vararg flags: Int) =
        java.util.concurrent.ArrayBlockingQueue<Item>(capacity + headroom).apply {
            flags.forEachIndexed { index, value -> add(Item(value, index)) }
        }

    private fun av1Obu(type: Int, payload: ByteArray): ByteArray =
        byteArrayOf(((type shl 3) or 0x2).toByte(), payload.size.toByte()) + payload

    private fun bits(vararg fields: Pair<Long, Int>): ByteArray {
        val bits = ArrayList<Int>()
        for ((value, count) in fields) for (i in count - 1 downTo 0) bits += ((value shr i) and 1).toInt()
        while (bits.size % 8 != 0) bits += 0
        return ByteArray(bits.size / 8) { index -> (0 until 8).fold(0) { acc, bit -> acc shl 1 or bits[index * 8 + bit] }.toByte() }
    }

    // libaom real-time shape (as in Av1DroppabilityTest): 640x480, 7-bit order hints, SELECT tools.
    private fun av1SequenceHeader() = bits(
        0L to 3, 0L to 1, 0L to 1, 0L to 1, 0L to 1, 0L to 5, 0L to 12, 8L to 5, 0L to 1,
        10L to 4, 9L to 4, 639L to 11, 479L to 10, 0L to 1, 0L to 1, 1L to 1, 1L to 1, 0L to 4,
        1L to 1, 0L to 1, 1L to 1, 1L to 1, 1L to 1, 6L to 3,
    )

    private fun av1InterFrameHeader(refresh: Int) = bits(
        0L to 1, 1L to 2, 1L to 1, 0L to 1, 0L to 1, 0L to 1, 0L to 1, 37L to 7, 0L to 3,
        refresh.toLong() to 8, 0x5aL to 8,
    )

    private fun classify(item: String): ShedClass = when {
        item.startsWith("key") -> ShedClass.KEY_FRAME
        item.startsWith("p") -> ShedClass.VIDEO
        else -> ShedClass.KEEP
    }

    private fun sample(backlogMs: Int, shed: Long) = LinkRateSample(
        windowNs = SECOND,
        offeredBytes = 75_000,
        sentBytes = 40_000,
        backlogAgeMs = backlogMs,
        largeFrameGrace = false,
        shedFrames = shed,
    )

    private fun sealed(sealer: EndToEndContentSealer, sequence: Long, type: EndToEndContentType, flags: Int = 0) =
        MeshEnvelope(
            streamId = STREAM_ID,
            sourceNodeId = SOURCE,
            sequence = sequence,
            ttl = 3,
            flags = flags,
            payload = sealer.seal(type, flags and MEDIA_FLAG_MASK, byteArrayOf(sequence.toByte())),
        )

    /** A record sealed after [skip] earlier records: its BME1 sequence is [skip]. */
    private fun sealedPayload(sealer: EndToEndContentSealer, sequence: Long, type: EndToEndContentType, skip: Int): ByteArray {
        repeat(skip) { sealer.seal(type, 0, byteArrayOf()) }
        return sealer.seal(type, 0, byteArrayOf(sequence.toByte()))
    }

    private fun media(sequence: Long, key: Boolean = false) = MeshEnvelope(
        streamId = STREAM_ID,
        sourceNodeId = SOURCE,
        sequence = sequence,
        ttl = 3,
        flags = if (key) MeshEnvelope.FLAG_KEY_FRAME else 0,
        payload = EndToEndContentProtocol.encodeHeader(EndToEndContentType.MEDIA_PACKET, sequence, 0, 0),
    )

    private companion object {
        const val SECOND = 1_000_000_000L
        val STREAM_ID: UUID = UUID(1, 2)
        val SOURCE: NodeId = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { 9 })
        /** Inner BME1 flags for media packets; mesh-only control bits are not content flags. */
        const val MEDIA_FLAG_MASK = MeshEnvelope.FLAG_KEY_FRAME or MeshEnvelope.FLAG_AUDIO or StreamProtocol.FLAG_DISPLAY_ROTATION
    }
}
