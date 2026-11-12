package com.example.bluetoothtest

import java.util.ArrayDeque

data class EncodedFrameQueueStats(
    val depth: Int,
    val capacity: Int,
    val queuedBytes: Long,
    val oldestFrameAgeMs: Int,
    val droppedFrames: Long,
    val needsKeyFrame: Boolean,
    /** Experimental temporal layering only: discardable frames skipped on a full queue (0 when off). */
    val thinnedFrames: Long = 0,
)

/**
 * The decoder stopped taking frames: the queue's wait for room ran out, or the decoder watchdog reported a stall
 * ([EncodedFrameQueue.reportStall]). An [IllegalStateException] with the earlier message, so a caller that does not
 * look for it handles it exactly as before.
 */
class DecoderStalledException(message: String = STOPPED_CONSUMING) : IllegalStateException(message) {
    companion object {
        const val STOPPED_CONSUMING = "Video decoder stopped consuming frames"
    }
}

/**
 * Encoded frames waiting for the decoder. Sized for the playback profile's whole adaptive delay;
 * when it is full the receive loop waits for the decoder (backpressure travels back over the link
 * to the sender's rate control) instead of discarding queued frames and waiting for a keyframe
 * (user direction 2026-10-07: no frame loss). Frames before the first keyframe of a stream, or
 * after a sender-marked discontinuity, cannot be decoded and are the only ones skipped.
 */
class EncodedFrameQueue(
    private val capacity: Int,
    requireInitialKeyFrame: Boolean = false,
    private val backpressureTimeoutNs: Long = BACKPRESSURE_TIMEOUT_NS,
    /**
     * Experimental temporal layering (off by default): when full, skip a verified-discardable
     * arrival, or queued discardable frames to make room for a reference frame, instead of waiting
     * for the decoder. Off: a full queue always applies backpressure and nothing is skipped.
     */
    private val thinDiscardable: Boolean = false,
) {
    init {
        require(capacity > 0)
    }

    private data class Entry(
        val packet: StreamProtocol.Packet,
        val queuedAtNs: Long,
    )

    private val lock = Object()
    private val entries = ArrayDeque<Entry>(capacity)
    private var queuedBytes = 0L
    private var droppedFrames = 0L
    private var thinnedFrames = 0L
    private var waitingForKeyFrame = requireInitialKeyFrame
    private var stalled: DecoderStalledException? = null

    /**
     * Returns false only for a frame that cannot be decoded yet (before a keyframe). Throws [DecoderStalledException]
     * when the decoder stopped taking frames: no room within the backpressure timeout, or a reported stall.
     */
    fun offer(packet: StreamProtocol.Packet, nowNs: Long = System.nanoTime()): Boolean = synchronized(lock) {
        stalled?.let { throw it }
        val isConfiguration = packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0
        val isKeyFrame = packet.flags and StreamProtocol.FLAG_KEY_FRAME != 0

        if (waitingForKeyFrame && !isConfiguration && !isKeyFrame) {
            droppedFrames++
            return false
        }
        if (thinDiscardable && entries.size >= capacity) {
            // Discardable frames go first; reference frames survive and no keyframe is needed.
            if (packet.flags and StreamProtocol.FLAG_DISCARDABLE != 0) {
                thinnedFrames++
                return false
            }
            val before = entries.size
            entries.removeIf { queued ->
                (queued.packet.flags and StreamProtocol.FLAG_DISCARDABLE != 0).also { discard ->
                    if (discard) queuedBytes -= queued.packet.payload.size
                }
            }
            thinnedFrames += before - entries.size
        }
        val deadlineNs = System.nanoTime() + backpressureTimeoutNs
        while (entries.size >= capacity) {
            val remainingNs = deadlineNs - System.nanoTime()
            if (remainingNs <= 0) throw DecoderStalledException()
            lock.wait(maxOf(1L, remainingNs / 1_000_000L))
            stalled?.let { throw it }
        }
        // A reset (stall recovery, discontinuity) during the wait restarts decoding at a keyframe: the
        // gate above ran before it, so check again rather than queue this frame ahead of the keyframe.
        if (waitingForKeyFrame && !isConfiguration && !isKeyFrame) {
            droppedFrames++
            return false
        }
        if (isKeyFrame) waitingForKeyFrame = false
        entries.addLast(Entry(packet, nowNs))
        queuedBytes += packet.payload.size
        true
    }

    fun poll(nowNs: Long = System.nanoTime()): Pair<StreamProtocol.Packet, Long>? = synchronized(lock) {
        val entry = entries.pollFirst() ?: return null
        queuedBytes -= entry.packet.payload.size
        lock.notifyAll()
        entry.packet to (nowNs - entry.queuedAtNs).coerceAtLeast(0)
    }

    fun peek(): StreamProtocol.Packet? = synchronized(lock) { entries.peekFirst()?.packet }

    /**
     * Design HC: a received frame failed the access-unit gate and was dropped. Frames already queued are older and still
     * decodable; every later non-keyframe is dropped until the next keyframe ([EncodedFrameQueueStats.needsKeyFrame]).
     */
    fun markLoss() = synchronized(lock) {
        droppedFrames++
        waitingForKeyFrame = true
    }

    /** A sender-marked discontinuity: decoding restarts at the next keyframe. */
    fun resetForDiscontinuity() = synchronized(lock) {
        droppedFrames += entries.count { queued ->
            queued.packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0
        }
        entries.clear()
        queuedBytes = 0
        waitingForKeyFrame = true
        lock.notifyAll()
    }

    /**
     * Experimental `test_call_video_restart` only: the decoder watchdog found the decoder stalled. Queued frames are
     * dropped, and a receiver waiting for room (and every later offer) gets [error] at once instead of waiting out the
     * backpressure timeout, so the call can replace the decoder.
     */
    fun reportStall(error: DecoderStalledException) = synchronized(lock) {
        if (stalled != null) return
        stalled = error
        droppedFrames += entries.count { queued ->
            queued.packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0
        }
        entries.clear()
        queuedBytes = 0
        waitingForKeyFrame = true
        lock.notifyAll()
    }

    fun stats(nowNs: Long = System.nanoTime()): EncodedFrameQueueStats = synchronized(lock) {
        val oldestAgeMs = entries.peekFirst()?.let { entry ->
            ((nowNs - entry.queuedAtNs).coerceAtLeast(0) / 1_000_000L)
                .coerceAtMost(0xffff)
                .toInt()
        } ?: 0
        EncodedFrameQueueStats(
            depth = entries.size,
            capacity = capacity,
            queuedBytes = queuedBytes,
            oldestFrameAgeMs = oldestAgeMs,
            droppedFrames = droppedFrames,
            needsKeyFrame = waitingForKeyFrame,
            thinnedFrames = thinnedFrames,
        )
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        queuedBytes = 0
        lock.notifyAll()
    }

    companion object {
        /** How long a full queue waits for the decoder before [DecoderStalledException]. */
        const val BACKPRESSURE_TIMEOUT_NS = 5_000_000_000L
    }
}
