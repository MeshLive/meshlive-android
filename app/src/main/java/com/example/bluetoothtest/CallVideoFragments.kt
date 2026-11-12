package com.example.bluetoothtest

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Call video fragments: on by default for direct BLE GATT calls (promoted 2026-10-11), decided per endpoint by
 * [CallHeadOfLine]; DEBUG `test_call_head_of_line` or the separate arm `test_call_video_fragments` (benchmark tunables
 * `call_head_of_line`, `call_video_fragments`).
 *
 * Problem (calls-noloss-m31asus-51cc8028, GATT): one encrypted record carries one whole video
 * frame, and a GATT write blocks for the frame's air time, so call audio queued behind a large
 * frame waited 620-1193 ms in the sender queue (`BENCHMARK media_age role=call_tx
 * audioQueueAgeMaxMs`) while the video queue itself waited 57-367 ms. The receiver's shared
 * playout delay then sat at its 600 ms call maximum in every GATT call.
 *
 * With the experiment on at both endpoints of a direct call, a video packet larger than
 * [MIN_FRAGMENTED_PACKET_BYTES] is sent as ordered `VIDEO_FRAGMENT` endpoint records of at most
 * [FRAGMENT_BYTES] plaintext each, and the writer sends any queued call audio between fragments.
 * Each fragment is its own authenticated, ordered `BME1` record (the same keys, nonce sequence and
 * replay/reorder rejection as every endpoint record); the receiver reassembles the original packet
 * plaintext and decodes it exactly like a `MEDIA_PACKET`, including the flag check. Only audio may
 * interleave with an unfinished frame; anything else fails closed.
 *
 * Negotiation: an endpoint that accepts fragments sets [CAPABILITY_BIT] in the transport mask of
 * its signed end-to-end hello. Older endpoints ignore unknown transport bits and never receive a
 * fragment; with the experiment off neither the bit nor any fragment is sent (unchanged wire).
 * Fragments are used on direct calls only (the opaque call relay is not taught the new type).
 */
internal object CallVideoFragments {
    /** Fragment plaintext bytes after the 8-byte fragment header: ~55 ms of air time at 300 kbps. */
    const val FRAGMENT_BYTES = 2_048

    /** Packets up to this encoded size go whole, exactly as before (most inter frames). */
    const val MIN_FRAGMENTED_PACKET_BYTES = 3_072

    const val HEADER_BYTES = 8

    /** Signed end-to-end hello: "this endpoint reassembles call video fragments" (transport mask bit 7). */
    const val CAPABILITY_BIT = 1 shl 7

    /** Largest reassembled packet: the largest whole media packet an endpoint record may carry. */
    const val MAX_PACKET_BYTES = EndToEndContentProtocol.MAX_PLAINTEXT_BYTES

    /** True when [packetPlaintextBytes] should be fragmented on a link where both sides agreed. */
    fun shouldFragment(packetPlaintextBytes: Int, flags: Int): Boolean =
        flags and (StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG) == 0 &&
            packetPlaintextBytes > MIN_FRAGMENTED_PACKET_BYTES

    /** Endpoint hello capabilities with the fragment bit set when this endpoint accepts them. */
    fun advertise(capabilities: NodeCapabilities, enabled: Boolean): NodeCapabilities =
        if (enabled) capabilities.copy(transportMask = capabilities.transportMask or CAPABILITY_BIT) else capabilities

    /** Fragments are sent only when this endpoint enabled them, the peer advertised them and the call is direct. */
    fun negotiated(localEnabled: Boolean, peerCapabilities: NodeCapabilities, relayHops: Int): Boolean =
        localEnabled && relayHops == 0 && peerCapabilities.transportMask and CAPABILITY_BIT != 0

    /**
     * Splits one encoded media packet (`EndToEndStreamProtocol.encodePacket` output) into fragment
     * payloads: big-endian total length, offset, then up to [chunkBytes] bytes.
     */
    fun split(packetPlaintext: ByteArray, chunkBytes: Int = FRAGMENT_BYTES): List<ByteArray> {
        require(chunkBytes > 0) { "Fragment size must be positive" }
        require(packetPlaintext.isNotEmpty() && packetPlaintext.size <= MAX_PACKET_BYTES) {
            "Invalid fragmented packet size ${packetPlaintext.size}"
        }
        val fragments = ArrayList<ByteArray>((packetPlaintext.size + chunkBytes - 1) / chunkBytes)
        var offset = 0
        while (offset < packetPlaintext.size) {
            val length = minOf(chunkBytes, packetPlaintext.size - offset)
            fragments += fragmentAt(packetPlaintext, offset, length)
            offset += length
        }
        return fragments
    }

    /** One fragment payload: big-endian total length, offset, then [length] bytes from [offset]. */
    fun fragmentAt(packetPlaintext: ByteArray, offset: Int, length: Int): ByteArray {
        require(offset >= 0 && length > 0 && offset + length <= packetPlaintext.size) {
            "Invalid fragment range $offset+$length of ${packetPlaintext.size}"
        }
        return ByteBuffer.allocate(HEADER_BYTES + length)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(packetPlaintext.size)
            .putInt(offset)
            .put(packetPlaintext, offset, length)
            .array()
    }
}

/**
 * Receiver side of [CallVideoFragments]: accepts fragments in order and returns the complete packet
 * plaintext when the last one arrives. Every inconsistency (gap, overlap, changed total or flags,
 * audio flag, oversize, empty chunk) throws, which ends the call as a protocol failure.
 */
internal class CallVideoReassembler(private val maxPacketBytes: Int = CallVideoFragments.MAX_PACKET_BYTES) {
    private var buffer: ByteArray? = null
    private var received = 0
    private var flags = 0

    /** A frame has started and not finished; only call audio may arrive until it does. */
    val inProgress: Boolean
        get() = buffer != null

    fun accept(recordFlags: Int, fragment: ByteArray): ByteArray? {
        require(recordFlags and (StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG) == 0) {
            "Call video fragment carries audio or codec-configuration flags"
        }
        require(fragment.size > CallVideoFragments.HEADER_BYTES) { "Empty call video fragment" }
        val header = ByteBuffer.wrap(fragment, 0, CallVideoFragments.HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        val total = header.int
        val offset = header.int
        val length = fragment.size - CallVideoFragments.HEADER_BYTES
        val current = buffer
        if (current == null && discarding) {
            if (offset != 0) return null
            discarding = false
        }
        if (current == null) {
            require(offset == 0) { "Call video fragment starts at offset $offset" }
            require(total in 1..maxPacketBytes) { "Invalid fragmented call video size $total" }
            buffer = ByteArray(total)
            received = 0
            flags = recordFlags
        } else {
            require(total == current.size) { "Call video fragment changed its total size" }
            require(offset == received) { "Call video fragment out of order: offset $offset, expected $received" }
            require(recordFlags == flags) { "Call video fragment changed its flags" }
        }
        val target = requireNotNull(buffer)
        require(length <= target.size - received) { "Call video fragment overruns its frame" }
        System.arraycopy(fragment, CallVideoFragments.HEADER_BYTES, target, received, length)
        received += length
        if (received < target.size) return null
        buffer = null
        received = 0
        return target
    }

    /** The link generation whose records this reassembler holds (null before the first link). */
    private var generation: Int? = null

    /** Wi-Fi upgrade cutover: fragments are discarded until the next frame starts at offset 0 (see [discardUntilFrameStart]). */
    private var discarding = false

    /**
     * Wi-Fi upgrade cutover (fragment suppress mode): the frame in progress is dropped and fragments are discarded until the
     * next frame starts at offset 0 (its first fragments went to the other carrier). Returns true when a partial frame was dropped.
     */
    fun discardUntilFrameStart(): Boolean {
        val dropped = inProgress
        buffer = null
        received = 0
        flags = 0
        discarding = true
        return dropped
    }

    /**
     * Call link renewal moved the call to a new link ([gen] changed): a frame left unfinished on the old link can never
     * complete, so it is dropped and the new link starts at a whole frame. Returns true when a partial frame was dropped.
     * Without this, the first video record after a renewal that cut a fragmented frame failed the call as "Call link lost"
     * (switch audit 2026-10-27, A3; the renewal re-asks the peer for a keyframe, which covers the dropped frame). The
     * sender needs no counterpart: a frame's fragments are written on one link, and the next frame starts at offset 0.
     */
    fun enterLinkGeneration(gen: Int): Boolean {
        if (generation == gen) return false
        val dropped = inProgress
        generation = gen
        buffer = null
        received = 0
        flags = 0
        return dropped
    }

    /** Fails closed when anything other than audio interrupts an unfinished fragmented frame. */
    fun requireIdleFor(what: String) {
        require(!inProgress) { "$what arrived inside a fragmented call video frame" }
    }
}
