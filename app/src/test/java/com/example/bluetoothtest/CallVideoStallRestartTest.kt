package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Experimental `test_call_video_restart` (audit MEDIA-06): a stalled call video decoder is replaced instead of ending
 * the call (S22 c2.android.av1-dav1d after a Wi-Fi Direct cutover: no output, no input buffers, then the 5 s queue
 * timeout hung up the call).
 */
class CallVideoStallRestartTest {
    private class Call(enabled: Boolean) {
        val statuses = ArrayList<String>()
        var keyFrameRequests = 0
        val remoteVideo = ArrayList<Boolean>()
        var closes = 0
        val restart = CallVideoStallRestart(
            enabled = enabled,
            onStatus = { statuses += it },
            onKeyFrameNeeded = { keyFrameRequests++ },
            onRemoteVideoActive = { remoteVideo += it },
        )

        /** What the receiver does with a stall from its decoder; false = the call ends (LocalMediaPipelineException). */
        fun stall(decoder: String, rendered: Int): Boolean? {
            val error = DecoderStalledException()
            if (!restart.handles(error)) return null
            return restart.onStall(error, decoder, rendered) { closes++ }
        }
    }

    @Test
    fun withTheSwitchOnAStalledDecoderIsReplacedAtTheNextKeyFrameAndTheCallGoesOn() {
        val call = Call(enabled = true)

        assertEquals(true, call.stall("c2.android.av1-dav1d.decoder", rendered = 900))

        assertEquals(1, call.closes)
        assertEquals(1, call.keyFrameRequests) // asked of the peer, hop-locally
        assertEquals(listOf(CallVideoStallRestart.RECOVERING_STATUS), call.statuses)
        assertTrue(call.restart.excludedDecoders.isEmpty()) // the first stall rebuilds the same decoder
        assertFalse(call.restart.videoStopped)
        assertTrue(call.remoteVideo.isEmpty())
    }

    @Test
    fun aSecondStallExcludesTheDecoderAndAThirdStopsVideoWhileVoiceContinues() {
        val call = Call(enabled = true)
        val dav1d = "c2.android.av1-dav1d.decoder"

        assertEquals(true, call.stall(dav1d, rendered = 900))
        assertEquals(true, call.stall(dav1d, rendered = 12))
        assertEquals(setOf(dav1d), call.restart.excludedDecoders) // the next decoder tries another component
        assertEquals(false, call.stall("c2.android.av1.decoder", rendered = 5))

        assertTrue(call.restart.videoStopped)
        assertEquals(3, call.closes)
        assertEquals(2, call.keyFrameRequests) // no keyframe is asked for once video stopped
        assertEquals(listOf(false), call.remoteVideo) // the "Video paused" cover replaces the frozen picture
        assertEquals(CallVideoStallRestart.VIDEO_STOPPED_STATUS, call.statuses.last())
        // The call is not ended: a later stall report is still this arm's, never a call-ending failure.
        assertEquals(false, call.stall("c2.android.av1.decoder", rendered = 0))
        assertEquals(2, call.keyFrameRequests)
    }

    @Test
    fun sixtyHealthyFramesBetweenStallsForgetTheEarlierStall() {
        val call = Call(enabled = true)
        val decoder = "c2.qti.av1.decoder"

        call.stall(decoder, rendered = 900)
        call.stall(decoder, rendered = DecoderWatchdogLimits.HEALTHY_FRAMES) // healthy since the restart: a first stall again
        call.stall(decoder, rendered = DecoderWatchdogLimits.HEALTHY_FRAMES)

        assertTrue(call.restart.excludedDecoders.isEmpty())
        assertFalse(call.restart.videoStopped)
        assertEquals(3, call.keyFrameRequests)
    }

    @Test
    fun withTheSwitchOffAStallStillEndsTheCallAndTheQueueKeepsItsWait() {
        val call = Call(enabled = false)

        assertNull(call.stall("c2.android.av1-dav1d.decoder", rendered = 900)) // the receiver rethrows: the call ends
        assertEquals(0, call.closes)
        assertTrue(call.statuses.isEmpty())
        assertNull(call.restart.queueWaitNs) // the decoder keeps the queue's own 5 s wait
        assertFalse(call.restart.handles(IllegalStateException("Decoder failed")))
    }

    @Test
    fun onlyAStallIsHandledNotOtherDecoderFailures() {
        val restart = Call(enabled = true).restart
        assertTrue(restart.handles(DecoderStalledException()))
        assertFalse(restart.handles(IllegalStateException("Decoder failed")))
    }

    @Test
    fun aWatchdogStallReleasesTheWaitingReceiverBeforeTheQueueWaitEnds() {
        val queue = EncodedFrameQueue(1, backpressureTimeoutNs = 60_000_000_000L)
        assertTrue(queue.offer(packet(StreamProtocol.FLAG_KEY_FRAME), 1L))
        val waiting = waitingOffer(queue)
        assertFalse(waiting.done.await(100, TimeUnit.MILLISECONDS)) // the decoder took nothing: the receiver waits

        queue.reportStall(DecoderStalledException("Video decoder stalled (no_input_buffer, 5000 ms)")) // decoder worker

        assertTrue(waiting.done.await(1, TimeUnit.SECONDS)) // long before the queue's own wait would end
        val error = waiting.result.get().exceptionOrNull()
        assertTrue(error is DecoderStalledException)
        assertEquals("Video decoder stalled (no_input_buffer, 5000 ms)", error?.message)
        try {
            queue.offer(packet(StreamProtocol.FLAG_KEY_FRAME), 2L)
            fail("a stalled decoder takes no more frames")
        } catch (_: DecoderStalledException) {
        }
    }

    @Test
    fun withTheSwitchOnTheReceiverWaitsAtMostThePlayoutBudgetForAStuckDecoder() {
        val queue = EncodedFrameQueue(1, backpressureTimeoutNs = requireNotNull(Call(enabled = true).restart.queueWaitNs))
        assertTrue(queue.offer(packet(StreamProtocol.FLAG_KEY_FRAME), 1L))
        val startedNs = System.nanoTime()
        val waiting = waitingOffer(queue)

        assertTrue(waiting.done.await(4, TimeUnit.SECONDS))
        val waitedMs = (System.nanoTime() - startedNs) / 1_000_000L
        assertTrue(waiting.result.get().exceptionOrNull() is DecoderStalledException)
        assertTrue("waited $waitedMs ms", waitedMs >= CallVideoStallRestart.QUEUE_WAIT_MS - 100)
        assertTrue("waited $waitedMs ms", waitedMs < EncodedFrameQueue.BACKPRESSURE_TIMEOUT_NS / 1_000_000L - 1_000)
    }

    @Test
    fun withTheSwitchOffTheReceiverStillWaitsBeyondThePlayoutBudget() {
        val wait = Call(enabled = false).restart.queueWaitNs ?: EncodedFrameQueue.BACKPRESSURE_TIMEOUT_NS
        val queue = EncodedFrameQueue(1, backpressureTimeoutNs = wait)
        assertTrue(queue.offer(packet(StreamProtocol.FLAG_KEY_FRAME), 1L))
        val waiting = waitingOffer(queue)

        assertFalse(waiting.done.await(CallVideoStallRestart.QUEUE_WAIT_MS + 500, TimeUnit.MILLISECONDS))
        queue.poll() // the decoder takes a frame: the waiting frame is queued, no frame is lost
        assertTrue(waiting.done.await(1, TimeUnit.SECONDS))
        assertEquals(true, waiting.result.get().getOrThrow())
    }

    @Test
    fun anExcludedDecoderGivesWayToTheNextOneThatSupportsTheStream() {
        val dav1d = "c2.android.av1-dav1d.decoder"
        val gav1 = "c2.android.av1.decoder"
        // Decoder fallback off (one candidate): the wider list supplies the next decoder.
        assertEquals(listOf(gav1), stallExcludedCandidates(listOf(dav1d), listOf(dav1d, gav1), setOf(dav1d)))
        // Nothing else supports the stream (or an explicitly requested decoder): the usual order is kept.
        assertEquals(listOf(dav1d), stallExcludedCandidates(listOf(dav1d), listOf(dav1d), setOf(dav1d)))
        // Names compare like MediaCodec component names, without case.
        assertEquals(listOf(gav1), stallExcludedCandidates(listOf(dav1d, gav1), emptyList(), setOf(dav1d.uppercase())))
    }

    private class Waiting(val done: CountDownLatch, val result: AtomicReference<Result<Boolean>>)

    private fun waitingOffer(queue: EncodedFrameQueue): Waiting {
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<Boolean>>()
        Thread {
            result.set(runCatching { queue.offer(packet(0), 3L) })
            done.countDown()
        }.apply { isDaemon = true }.start()
        return Waiting(done, result)
    }

    private fun packet(flags: Int) = StreamProtocol.Packet(flags = flags, presentationTimeUs = 0L, payload = ByteArray(1))
}
