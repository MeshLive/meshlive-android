package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A wedged decoder fills the queue and the call's single receive thread waits in offer(); the
 * watchdog's stall recovery (another thread) resets the queue. Before the fix the waiting offer then queues its
 * non-keyframe at the head of the reset queue (the keyframe gate ran before the wait), so the rebuilt
 * decoder would be fed an inter frame first (seen as the S22 AV1 decoder wedge after a carrier cutover).
 */
class EncodedFrameQueueStallResetTest {
    @Test
    fun aStallResetReleasesTheWaitingReceiverWithoutQueueingAFrameBeforeTheKeyframe() {
        val queue = EncodedFrameQueue(2, backpressureTimeoutNs = 600_000_000_000L)
        assertTrue(queue.offer(packet(StreamProtocol.FLAG_KEY_FRAME, presentationTimeUs = 1L), 1L))
        assertTrue(queue.offer(packet(0, presentationTimeUs = 2L), 2L))
        val returned = CountDownLatch(1)
        val result = AtomicReference<Result<Boolean>>()
        Thread {
            result.set(runCatching { queue.offer(packet(0, presentationTimeUs = 3L), 3L) })
            returned.countDown()
        }.start()
        assertFalse(returned.await(100, TimeUnit.MILLISECONDS)) // the decoder stopped: the receiver waits

        queue.resetForDiscontinuity() // decoder stall recovery, from the decoder worker

        assertTrue(returned.await(5, TimeUnit.SECONDS)) // released long before the 5 s call-ending timeout
        assertFalse(result.get().getOrThrow())          // the dependent frame is skipped, not queued, no exception
        assertEquals(0, queue.stats(4L).depth)
        assertTrue(queue.stats(4L).needsKeyFrame)
        assertTrue(queue.offer(packet(StreamProtocol.FLAG_KEY_FRAME, presentationTimeUs = 5L), 5L))
        assertEquals(5L, queue.peek()?.presentationTimeUs) // the restarted decoder starts at the keyframe
    }

    private fun packet(flags: Int, bytes: Int = 1, presentationTimeUs: Long = 0) = StreamProtocol.Packet(
        flags = flags,
        presentationTimeUs = presentationTimeUs,
        payload = ByteArray(bytes),
    )
}
