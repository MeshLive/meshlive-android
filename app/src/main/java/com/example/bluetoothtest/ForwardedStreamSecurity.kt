package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

data class ForwardedStreamGrant(
    val sourceNodeId: NodeId,
    val streamId: UUID,
    val key: ByteArray,
) {
    init {
        require(key.size == MeshAead.KEY_BYTES)
    }
}

object ForwardedStreamGrantCodec {
    private val magic = byteArrayOf('B'.code.toByte(), 'F'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte())
    private const val ENCODED_BYTES = 4 + NodeId.BYTE_COUNT + 16 + MeshAead.KEY_BYTES

    fun encode(value: ForwardedStreamGrant): ByteArray =
        ByteArrayOutputStream(ENCODED_BYTES).also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(magic)
                output.write(value.sourceNodeId.toByteArray())
                output.writeLong(value.streamId.mostSignificantBits)
                output.writeLong(value.streamId.leastSignificantBits)
                output.write(value.key)
            }
        }.toByteArray()

    fun decode(bytes: ByteArray): ForwardedStreamGrant {
        require(bytes.size == ENCODED_BYTES) { "Invalid forwarded-stream grant length" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val receivedMagic = ByteArray(magic.size).also(input::readFully)
        require(receivedMagic.contentEquals(magic)) { "Invalid forwarded-stream grant" }
        return ForwardedStreamGrant(
            NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT).also(input::readFully)),
            UUID(input.readLong(), input.readLong()),
            ByteArray(MeshAead.KEY_BYTES).also(input::readFully),
        )
    }
}

class ReceiverNodeForwardingCore(
    private val localNodeId: NodeId,
    private val upstreamPeerNodeId: NodeId,
    private val streamId: UUID,
    private val sourceNodeId: NodeId,
    private val queueCapacity: Int,
    /** Experimental temporal layering (off by default): the child queue may thin discardable frames. */
    private val thinDiscardable: Boolean = false,
    /** Experimental bounded media age (off by default): the child queue may shed stale video. */
    private val boundedMediaAge: Boolean = false,
    /**
     * Audio-only live stream (null: video). Non-null: a joining child gets the cached header and Opus
     * configuration and starts at the next record, with no keyframe wait; its queue sheds stale audio when
     * [AudioLiveRelayQueue.ageBound] and reports withdrawal verdicts.
     */
    private val audioLive: AudioLiveRelaySettings? = null,
    /**
     * Experimental relay child thinning (off by default; RelayChildLayerPolicy): the child queue accepts
     * a level (full / base layer only / audio only). Off: no record is ever dropped for a layer.
     */
    private val layerThinning: Boolean = false,
    /**
     * Experimental time-based shed target (Q31; null = the earlier fixed budget). Applies only to a queue that
     * sheds ([boundedMediaAge]); see [RelayTimeQueueSizer].
     */
    private val timeQueue: RelayTimeQueueConfig? = null,
    /** The stream's frame interval, ms, for the time-based target's minimum cushion. */
    private val frameIntervalMs: Long = 66L,
) {
    private val duplicates = DuplicateWindow()
    private val bootstrap = mutableListOf<MeshEnvelope>()
    private val videoConfigBatch = VideoConfigBatch()
    private var latestControl: MeshEnvelope? = null
    private var childNodeId: NodeId? = null
    private var childQueue: RelayChildQueue? = null
    private var childMediaAgeBound: RelayMediaAgeBound? = null

    init {
        require(queueCapacity > 0)
    }

    @Synchronized
    fun receive(envelope: MeshEnvelope): RelayResult {
        if (envelope.sourceNodeId == localNodeId) return rejected(RelayRejection.LOCAL_LOOP)
        require(envelope.sourceNodeId == sourceNodeId) { "Forwarded stream source changed" }
        require(envelope.streamId == streamId) { "Forwarded stream ID changed" }
        if (!duplicates.markIfNew(envelope)) return rejected(RelayRejection.DUPLICATE)
        remember(envelope)
        val forwarded = envelope.forwarded() ?: return rejected(RelayRejection.TTL_EXPIRED)
        val child = childNodeId ?: return rejected(RelayRejection.NO_CHILDREN)
        if (child == upstreamPeerNodeId) return rejected(RelayRejection.NO_CHILDREN)
        val queue = childQueue ?: return rejected(RelayRejection.NO_CHILDREN)
        return when (queue.offer(forwarded)) {
            ChildQueueOffer.QUEUED,
            ChildQueueOffer.STARTED_AT_KEYFRAME,
            -> RelayResult(forwarded, setOf(child), emptySet(), null)
            ChildQueueOffer.WAITING_FOR_JOIN_KEYFRAME ->
                RelayResult(forwarded, emptySet(), setOf(child), null)
            // Thinning keeps the child decodable, so it must not trigger an upstream keyframe.
            ChildQueueOffer.THINNED -> RelayResult(forwarded, emptySet(), emptySet(), null)
            // Bounded media age shed this child's stale video: it needs the next keyframe, as after a join.
            ChildQueueOffer.SHED_WAITING_FOR_KEYFRAME ->
                RelayResult(forwarded, emptySet(), setOf(child), null, keyFrameReRequest = true)
            ChildQueueOffer.SHED_DROPPED -> RelayResult(forwarded, emptySet(), emptySet(), null)
            // Relay child thinning: the base layer stays decodable, and a paused child asks for nothing.
            ChildQueueOffer.LAYER_THINNED, ChildQueueOffer.LAYER_PAUSED ->
                RelayResult(forwarded, emptySet(), emptySet(), null)
        }
    }

    @Synchronized
    fun addChild(child: NodeId): List<MeshEnvelope> {
        require(child != localNodeId && child != upstreamPeerNodeId) { "Invalid downstream child" }
        require(childNodeId == null) { "A downstream child is already connected" }
        childNodeId = child
        val bound = if (boundedMediaAge) {
            RelayMediaAgeBound(
                timeQueue?.let { RelayTimeQueueSizer(it, frameIntervalMs) },
                sustainedDrain = DebugTunables.relayDrainSustained,
            )
        } else {
            null
        }
        childMediaAgeBound = bound
        childQueue = if (audioLive != null) {
            // No keyframes in an audio-only stream: the child starts at the next record after the bootstrap list.
            RelayChildQueue(audioLive = AudioLiveRelayQueue(audioLive.ageBound))
        } else {
            RelayChildQueue(thinDiscardable, bound, layerThinning = layerThinning).also(RelayChildQueue::waitForKeyFrame)
        }
        return (bootstrap + listOfNotNull(latestControl))
            .distinctBy(MeshEnvelope::sequence)
            .sortedBy(MeshEnvelope::sequence)
            .mapNotNull(MeshEnvelope::forwarded)
    }

    @Synchronized
    fun removeChild(child: NodeId) {
        if (childNodeId != child) return
        val removedQueue = childQueue
        childNodeId = null
        childQueue = null
        childMediaAgeBound = null
        removedQueue?.close()
    }

    /** A7: a lossy carrier fallback may have dropped records; the child resumes at the next keyframe, like after a bounded-age shed. */
    @Synchronized
    fun resyncChild(child: NodeId) {
        if (audioLive == null && childNodeId == child) childQueue?.waitForKeyFrame()
    }

    @Throws(InterruptedException::class)
    fun take(child: NodeId): MeshEnvelope? = synchronized(this) {
        if (childNodeId == child) childQueue else null
    }?.take()

    @Synchronized
    fun queueDepth(child: NodeId): Int = if (childNodeId == child) childQueue?.size() ?: 0 else 0

    /** How far behind live this child's queue is (no media is dropped for a slow child). */
    @Synchronized
    fun backlogAgeMs(child: NodeId): Long =
        if (childNodeId == child) childQueue?.backlogAgeMs() ?: 0 else 0

    /** Experimental temporal layering: discardable frames skipped for [child] (0 when off). */
    @Synchronized
    fun thinnedFrames(child: NodeId): Long =
        if (childNodeId == child) childQueue?.thinnedFrameCount() ?: 0 else 0

    /** Media records [child]'s queue has dropped so far (shed or thinned; see [RelayChildQueue.droppedMediaCount]). */
    @Synchronized
    fun droppedMedia(child: NodeId): Long =
        if (childNodeId == child) childQueue?.droppedMediaCount() ?: 0 else 0

    /** Relay child thinning: moves [child] to [level]; false when nothing changed (or thinning is off). */
    fun setChildLayerLevel(child: NodeId, level: RelayChildLevel): Boolean =
        synchronized(this) { childQueue.takeIf { childNodeId == child } }?.setLayerLevel(level) ?: false

    /** Relay child thinning: [child]'s queue counters, or null without that child. */
    fun childLayerStats(child: NodeId): RelayChildLayerStats? =
        synchronized(this) { childQueue.takeIf { childNodeId == child } }?.layerStats()

    fun hasQueuedAudio(child: NodeId): Boolean =
        synchronized(this) { childQueue.takeIf { childNodeId == child } }?.hasQueuedAudio() == true

    /** The next record the child writer takes is audio (the queue is strictly ordered). */
    fun headIsAudio(child: NodeId): Boolean =
        synchronized(this) { childQueue.takeIf { childNodeId == child } }?.headIsAudio() == true

    @Synchronized
    fun hasChild(): Boolean = childNodeId != null

    /** Audio-only: why [child] should be withdrawn now (more than 20% shed over 10 s, or a record older than 30 s). */
    fun audioVerdict(child: NodeId, nowMs: Long): AudioLiveChildHealth.Reason? =
        synchronized(this) { childQueue.takeIf { childNodeId == child } }?.audioVerdict(nowMs)

    /** Audio-only: whole records shed for [child] so far. */
    fun audioShedRecords(child: NodeId): Long =
        synchronized(this) { childQueue.takeIf { childNodeId == child } }?.audioShedRecords() ?: 0L

    /** Hop-local: [child]'s reported added playout delay on this link, for bounded media age. */
    @Synchronized
    fun setChildAddedDelayMs(child: NodeId, delayMs: Int) {
        if (childNodeId == child) childMediaAgeBound?.childAddedDelayMs = delayMs
    }

    /** Hop-local: [child]'s measured delivery rate on this link (its arrival report), the time-based target's drain. */
    @Synchronized
    fun setChildDrainKbps(child: NodeId, kbps: Int) {
        if (childNodeId == child) childMediaAgeBound?.setDrainKbps(kbps)
    }

    /** Hop-local: this relay's own sustained delivery rate to [child] (0 clears it), the drain when the experimental switch is on. */
    @Synchronized
    fun setChildSustainedDrainKbps(child: NodeId, kbps: Int) {
        if (childNodeId == child) childMediaAgeBound?.setSustainedDrainKbps(kbps)
    }

    /** Both arms: `BENCHMARK relay_time_queue` fields for [child] (null without a shedding queue). */
    @Synchronized
    fun timeQueueSummary(child: NodeId): String? =
        childQueue.takeIf { childNodeId == child }?.timeQueueSummary()

    @Synchronized
    fun timeQueueState(child: NodeId): Pair<String, Boolean>? =
        childQueue.takeIf { childNodeId == child }?.timeQueueState()

    /** Both arms: [child]'s media age in this relay queue and any bounded-age shedding. */
    @Synchronized
    fun mediaAgeSummary(child: NodeId): String? = childQueue.takeIf { childNodeId == child }?.let { queue ->
        "budgetMs=${childMediaAgeBound?.budgetMs() ?: BoundedMediaAge.streamQueueBudgetMs(0)} | ${queue.mediaAgeSummary()}"
    }

    private fun remember(envelope: MeshEnvelope) {
        val type = EndToEndContentProtocol.inspect(envelope.payload).type
        when {
            type == EndToEndContentType.STREAM_HEADER -> {
                require(bootstrap.none { EndToEndContentProtocol.inspect(it.payload).type == type }) {
                    "Forwarded stream sent more than one header"
                }
                bootstrap += envelope
            }
            type == EndToEndContentType.MEDIA_PACKET && envelope.flags and MeshEnvelope.FLAG_CODEC_CONFIG != 0 -> {
                if (envelope.flags and MeshEnvelope.FLAG_AUDIO == 0 && videoConfigBatch.onConfig()) {
                    // The source restarted its encoder (new rung, camera flip): a joining child gets only the
                    // current encoder's video configuration, never csd of two picture sizes.
                    bootstrap.removeAll { it.isVideoConfigRecord() }
                }
                bootstrap += envelope
                while (bootstrap.count {
                        EndToEndContentProtocol.inspect(it.payload).type == EndToEndContentType.MEDIA_PACKET &&
                            it.flags and MeshEnvelope.FLAG_CODEC_CONFIG != 0
                    } > MAX_CODEC_CONFIG_PACKETS
                ) {
                    val index = bootstrap.indexOfFirst {
                        EndToEndContentProtocol.inspect(it.payload).type == EndToEndContentType.MEDIA_PACKET &&
                            it.flags and MeshEnvelope.FLAG_CODEC_CONFIG != 0
                    }
                    bootstrap.removeAt(index)
                }
            }
            type == EndToEndContentType.MEDIA_PACKET &&
                envelope.flags and (MeshEnvelope.FLAG_AUDIO or StreamProtocol.FLAG_DISPLAY_ROTATION) == 0 ->
                videoConfigBatch.onMedia()
            type == EndToEndContentType.STREAM_STATE -> latestControl = envelope
        }
    }

    private fun MeshEnvelope.isVideoConfigRecord(): Boolean =
        EndToEndContentProtocol.inspect(payload).type == EndToEndContentType.MEDIA_PACKET &&
            flags and MeshEnvelope.FLAG_CODEC_CONFIG != 0 && flags and MeshEnvelope.FLAG_AUDIO == 0

    private fun rejected(reason: RelayRejection) = RelayResult(null, emptySet(), emptySet(), reason)

    private companion object {
        const val MAX_CODEC_CONFIG_PACKETS = 8
    }
}
