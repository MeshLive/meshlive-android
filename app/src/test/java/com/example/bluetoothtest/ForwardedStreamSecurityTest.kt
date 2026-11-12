package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardedStreamSecurityTest {
    @Test
    fun mediaRecoveryAndIntegrityFlagsNeverBecomeMeshLifecycleFlags() {
        val source = node(8)
        val linkKey = ByteArray(MeshAead.KEY_BYTES) { (it + 1).toByte() }
        val mediaKey = ByteArray(MeshAead.KEY_BYTES) { (it + 41).toByte() }
        val bytes = ByteArrayOutputStream()
        val linkWriter = SecureRecordWriter(bytes, linkKey)
        val writer = EndToEndStreamWriter(linkWriter, EndToEndContentSealer(mediaKey), source, true)
        writer.writeHeader(StreamProtocol.CODEC_VP9, VideoSettings.DEFAULT, 0, false)
        val flags = StreamProtocol.FLAG_KEY_FRAME or StreamProtocol.FLAG_DISCONTINUITY or
            StreamProtocol.FLAG_DEBUG_INTEGRITY
        writer.writePacket(flags, 123L, byteArrayOf(1, 2))
        writer.writeState(StreamLifecycleState.ENDED)
        linkWriter.flush()

        val observed = mutableListOf<MeshEnvelope>()
        val reader = EndToEndStreamReader(
            SecureRecordReader(ByteArrayInputStream(bytes.toByteArray()), linkKey),
            EndToEndContentOpener(mediaKey), source, observed::add,
        )
        reader.readHeader()
        val packet = reader.readStreamContent() as ReceivedStreamContent.Media
        assertEquals(flags, packet.packet.flags)
        assertEquals(StreamProtocol.FLAG_KEY_FRAME, observed.last().flags)
        assertFalse(observed.last().isControl)
        assertEquals(0, observed.last().flags and MeshEnvelope.FLAG_TERMINAL)
        assertEquals(StreamLifecycleState.ENDED, (reader.readStreamContent() as ReceivedStreamContent.State).state)
        assertTrue(observed.last().isControl)
        assertTrue(observed.last().flags and MeshEnvelope.FLAG_TERMINAL != 0)
    }

    @Test
    fun forwardedGrantRoundTripsAndRejectsTrailingData() {
        val value = ForwardedStreamGrant(
            node(1),
            UUID.fromString("c7da2c06-e0de-4d7b-98ed-4916c2f208a3"),
            ByteArray(MeshAead.KEY_BYTES) { (it + 9).toByte() },
        )

        val encoded = ForwardedStreamGrantCodec.encode(value)
        val decoded = ForwardedStreamGrantCodec.decode(encoded)

        assertEquals(value.sourceNodeId, decoded.sourceNodeId)
        assertEquals(value.streamId, decoded.streamId)
        assertArrayEquals(value.key, decoded.key)
        assertThrows(IllegalArgumentException::class.java) {
            ForwardedStreamGrantCodec.decode(encoded + 0)
        }
    }

    @Test
    fun resyncChildMakesTheNextNonKeyframeVideoRecordWaitForAKeyframe() {
        val source = node(21)
        val upstream = node(22)
        val local = node(23)
        val child = node(24)
        val streamId = UUID.fromString("7ab6355e-1c56-4a4e-bd0e-24cc0f78aa11")
        val sealer = EndToEndContentSealer(ByteArray(MeshAead.KEY_BYTES) { (it + 11).toByte() })
        val core = ReceiverNodeForwardingCore(local, upstream, streamId, source, queueCapacity = 4)
        val header = envelope(streamId, source, 0, 5, 0, sealer.seal(
            EndToEndContentType.STREAM_HEADER, 0,
            EndToEndStreamProtocol.encodeHeader(StreamProtocol.CODEC_VP9, VideoSettings.DEFAULT, 0, false),
        ))
        assertEquals(RelayRejection.NO_CHILDREN, core.receive(header).rejection)
        assertTrue(core.addChild(child).isNotEmpty())

        val keyFrame = mediaEnvelope(sealer, streamId, source, 1, StreamProtocol.FLAG_KEY_FRAME, "key".toByteArray())
        assertEquals(setOf(child), core.receive(keyFrame).queuedChildren)
        assertEquals(1L, requireNotNull(core.take(child)).sequence)
        val delta = mediaEnvelope(sealer, streamId, source, 2, 0, "delta".toByteArray())
        assertEquals(setOf(child), core.receive(delta).queuedChildren)
        assertEquals(2L, requireNotNull(core.take(child)).sequence)

        core.resyncChild(child)
        val nextDelta = mediaEnvelope(sealer, streamId, source, 3, 0, "next".toByteArray())
        val waiting = core.receive(nextDelta)
        assertEquals(setOf(child), waiting.laggingChildren)
        assertTrue(waiting.queuedChildren.isEmpty())

        val freshKey = mediaEnvelope(sealer, streamId, source, 4, StreamProtocol.FLAG_KEY_FRAME, "fresh".toByteArray())
        assertEquals(setOf(child), core.receive(freshKey).queuedChildren)
        assertEquals(4L, requireNotNull(core.take(child)).sequence)
        sealer.close()
    }

    @Test
    fun resyncChildDoesNothingForAnotherChildOrAnAudioOnlyCore() {
        val source = node(31)
        val upstream = node(32)
        val local = node(33)
        val child = node(34)
        val streamId = UUID.fromString("0c9fcc09-1f5b-49e7-8d67-8cf7330cbb22")
        val sealer = EndToEndContentSealer(ByteArray(MeshAead.KEY_BYTES) { (it + 12).toByte() })
        val core = ReceiverNodeForwardingCore(local, upstream, streamId, source, queueCapacity = 4)
        val header = envelope(streamId, source, 0, 5, 0, sealer.seal(
            EndToEndContentType.STREAM_HEADER, 0,
            EndToEndStreamProtocol.encodeHeader(StreamProtocol.CODEC_VP9, VideoSettings.DEFAULT, 0, false),
        ))
        core.receive(header)
        core.addChild(child)
        val keyFrame = mediaEnvelope(sealer, streamId, source, 1, StreamProtocol.FLAG_KEY_FRAME, "key".toByteArray())
        core.receive(keyFrame)
        requireNotNull(core.take(child))

        core.resyncChild(node(99))
        val delta = mediaEnvelope(sealer, streamId, source, 2, 0, "delta".toByteArray())
        assertEquals(setOf(child), core.receive(delta).queuedChildren)
        requireNotNull(core.take(child))

        val audioCore = ReceiverNodeForwardingCore(
            local, upstream, streamId, source, queueCapacity = 4,
            audioLive = AudioLiveRelaySettings(AudioLiveTunables.ageBound),
        )
        audioCore.receive(header)
        audioCore.addChild(child)
        audioCore.resyncChild(child)
        val audio = mediaEnvelope(sealer, streamId, source, 3, StreamProtocol.FLAG_AUDIO, "audio".toByteArray())
        assertEquals(setOf(child), audioCore.receive(audio).queuedChildren)
        sealer.close()
    }

    @Test
    fun lateChildGetsConfigurationThenWaitsForFreshKeyframe() {
        val source = node(1)
        val upstream = node(2)
        val local = node(3)
        val child = node(4)
        val streamId = UUID.fromString("1c090a97-86ad-4d66-8884-75ead075e623")
        val sealer = EndToEndContentSealer(ByteArray(MeshAead.KEY_BYTES) { it.toByte() })
        val core = ReceiverNodeForwardingCore(local, upstream, streamId, source, queueCapacity = 4)
        val header = envelope(
            streamId,
            source,
            0,
            5,
            0,
            sealer.seal(
                EndToEndContentType.STREAM_HEADER,
                0,
                EndToEndStreamProtocol.encodeHeader(
                    StreamProtocol.CODEC_VP9,
                    VideoSettings.DEFAULT,
                    rotationDegrees = 0,
                    includeAudio = true,
                ),
            ),
        )
        val videoConfig = mediaEnvelope(
            sealer,
            streamId,
            source,
            1,
            StreamProtocol.FLAG_CODEC_CONFIG,
            "config".toByteArray(),
        )
        val oldDelta = mediaEnvelope(sealer, streamId, source, 2, 0, "delta".toByteArray())
        val keyFrame = mediaEnvelope(
            sealer,
            streamId,
            source,
            3,
            StreamProtocol.FLAG_KEY_FRAME,
            "key".toByteArray(),
        )
        listOf(header, videoConfig, oldDelta, keyFrame).forEach {
            assertEquals(RelayRejection.NO_CHILDREN, core.receive(it).rejection)
        }

        val bootstrap = core.addChild(child)

        assertEquals(listOf(0L, 1L), bootstrap.map(MeshEnvelope::sequence))
        assertTrue(bootstrap.all { it.ttl == 4 })
        assertArrayEquals(header.payload, bootstrap[0].payload)
        assertArrayEquals(videoConfig.payload, bootstrap[1].payload)
        assertFalse(bootstrap.any { it.sequence == oldDelta.sequence })
        assertFalse(bootstrap.any { it.sequence == keyFrame.sequence })

        val live = mediaEnvelope(
            sealer,
            streamId,
            source,
            4,
            StreamProtocol.FLAG_AUDIO,
            "audio".toByteArray(),
        )
        assertEquals(setOf(child), core.receive(live).queuedChildren)
        val forwardedLive = requireNotNull(core.take(child))
        assertEquals(4L, forwardedLive.sequence)
        assertEquals(4, forwardedLive.ttl)
        assertArrayEquals(live.payload, forwardedLive.payload)

        val nextDelta = mediaEnvelope(sealer, streamId, source, 5, 0, "next-delta".toByteArray())
        assertEquals(setOf(child), core.receive(nextDelta).laggingChildren)
        val freshKeyFrame = mediaEnvelope(
            sealer,
            streamId,
            source,
            6,
            StreamProtocol.FLAG_KEY_FRAME,
            "fresh-key".toByteArray(),
        )
        assertEquals(setOf(child), core.receive(freshKeyFrame).queuedChildren)
        assertEquals(6L, requireNotNull(core.take(child)).sequence)
        sealer.close()
    }

    @Test
    fun localReaderOpensMediaWhileForwardingCallbackReceivesIdenticalCiphertext() {
        val source = node(8)
        val streamId = UUID.fromString("2a24730d-e89c-4ba6-b18d-e494a5fa69d2")
        val linkKey = ByteArray(MeshAead.KEY_BYTES) { (it + 1).toByte() }
        val mediaKey = ByteArray(MeshAead.KEY_BYTES) { (it + 41).toByte() }
        val linkBytes = ByteArrayOutputStream()
        val linkWriter = SecureRecordWriter(linkBytes, linkKey)
        val streamWriter = EndToEndStreamWriter(
            linkWriter,
            EndToEndContentSealer(mediaKey),
            source,
            useMeshEnvelope = true,
            meshStreamId = streamId,
        )
        streamWriter.writeHeader(
            StreamProtocol.CODEC_VP9,
            VideoSettings.DEFAULT,
            rotationDegrees = 0,
            includeAudio = false,
        )
        streamWriter.writePacket(StreamProtocol.FLAG_KEY_FRAME, 77, "frame".toByteArray())
        linkWriter.flush()

        val observed = mutableListOf<MeshEnvelope>()
        val reader = EndToEndStreamReader(
            SecureRecordReader(ByteArrayInputStream(linkBytes.toByteArray()), linkKey),
            EndToEndContentOpener(mediaKey),
            source,
            observed::add,
        )

        assertEquals(StreamProtocol.CODEC_VP9, reader.readHeader().codecId)
        val packet = reader.readStreamContent() as ReceivedStreamContent.Media
        assertEquals("frame", String(packet.packet.payload))
        assertEquals(2, observed.size)
        val encodedRecords = SecureRecordReader(ByteArrayInputStream(linkBytes.toByteArray()), linkKey)
        assertArrayEquals(
            MeshEnvelopeCodec.decode(encodedRecords.read().payload).payload,
            observed[0].payload,
        )
        assertArrayEquals(
            MeshEnvelopeCodec.decode(encodedRecords.read().payload).payload,
            observed[1].payload,
        )
    }

    @Test
    fun localReaderSplitsAudioPairWhileRelayObservesOneEncryptedEnvelope() {
        val source = node(8)
        val streamId = UUID.fromString("2b24730d-e89c-4ba6-b18d-e494a5fa69d2")
        val linkKey = ByteArray(MeshAead.KEY_BYTES) { (it + 1).toByte() }
        val mediaKey = ByteArray(MeshAead.KEY_BYTES) { (it + 41).toByte() }
        val linkBytes = ByteArrayOutputStream()
        val linkWriter = SecureRecordWriter(linkBytes, linkKey)
        val streamWriter = EndToEndStreamWriter(
            linkWriter,
            EndToEndContentSealer(mediaKey),
            source,
            useMeshEnvelope = true,
            meshStreamId = streamId,
        )
        streamWriter.writeHeader(
            StreamProtocol.CODEC_VP9,
            VideoSettings.DEFAULT,
            rotationDegrees = 0,
            includeAudio = true,
        )
        val paired = StreamProtocol.pairAudioPackets(
            StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, 1_000_000, byteArrayOf(1, 2)),
            StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, 1_020_000, byteArrayOf(3, 4)),
        )
        streamWriter.writePacket(paired.flags, paired.presentationTimeUs, paired.payload)
        linkWriter.flush()

        val observed = mutableListOf<MeshEnvelope>()
        val reader = EndToEndStreamReader(
            SecureRecordReader(ByteArrayInputStream(linkBytes.toByteArray()), linkKey),
            EndToEndContentOpener(mediaKey),
            source,
            observed::add,
        )

        reader.readHeader()
        val first = (reader.readStreamContent() as ReceivedStreamContent.Media).packet
        val second = (reader.readStreamContent() as ReceivedStreamContent.Media).packet

        assertEquals(2, observed.size)
        assertArrayEquals(byteArrayOf(1, 2), first.payload)
        assertEquals(1_000_000, first.presentationTimeUs)
        assertArrayEquals(byteArrayOf(3, 4), second.payload)
        assertEquals(1_020_000, second.presentationTimeUs)
    }

    @Test
    fun forwardingRejectsExpiredTtlAndInvalidChildDirection() {
        val source = node(1)
        val upstream = node(2)
        val local = node(3)
        val streamId = UUID.randomUUID()
        val core = ReceiverNodeForwardingCore(local, upstream, streamId, source, queueCapacity = 2)
        val sealer = EndToEndContentSealer(ByteArray(MeshAead.KEY_BYTES) { 6 })
        val header = envelope(
            streamId,
            source,
            0,
            1,
            0,
            sealer.seal(
                EndToEndContentType.STREAM_HEADER,
                0,
                EndToEndStreamProtocol.encodeHeader(
                    StreamProtocol.CODEC_VP9,
                    VideoSettings.DEFAULT,
                    0,
                    false,
                ),
            ),
        )

        assertEquals(RelayRejection.TTL_EXPIRED, core.receive(header).rejection)
        assertThrows(IllegalArgumentException::class.java) { core.addChild(upstream) }
        sealer.close()
    }

    @Test
    fun removingChildWakesBlockedForwardingWriter() {
        val source = node(1)
        val upstream = node(2)
        val local = node(3)
        val child = node(4)
        val core = ReceiverNodeForwardingCore(
            local,
            upstream,
            UUID.randomUUID(),
            source,
            queueCapacity = 2,
        )
        core.addChild(child)
        val completed = CountDownLatch(1)
        val result = AtomicReference<MeshEnvelope?>()
        val writer = thread {
            result.set(core.take(child))
            completed.countDown()
        }

        assertFalse(completed.await(100, TimeUnit.MILLISECONDS))
        core.removeChild(child)
        assertTrue(completed.await(120, TimeUnit.SECONDS))
        assertEquals(null, result.get())
        writer.join(1_000)
    }

    @Test
    fun authenticatedParentWrapsExistingMediaKeyAndChildControlReturnsHopByHop() {
        val parentInput = PipedInputStream(32 * 1024)
        val childOutput = PipedOutputStream(parentInput)
        val childInput = PipedInputStream(32 * 1024)
        val parentOutput = PipedOutputStream(childInput)
        val parentIdentity = KeyPairSigningIdentity(generateEcKeyPair())
        val childIdentity = KeyPairSigningIdentity(generateEcKeyPair())
        val capabilities = NodeCapabilities(
            NodeCapabilities.TRANSPORT_BLE_L2CAP,
            NodeCapabilities.CODEC_VP9,
            NodeCapabilities.CODEC_VP9,
            1_100,
            1,
        )
        val sourceNode = node(9)
        val streamId = UUID.randomUUID()
        val mediaKey = ByteArray(MeshAead.KEY_BYTES) { (it + 17).toByte() }
        val childFailure = AtomicReference<Throwable?>()
        val childPacket = AtomicReference<StreamProtocol.Packet>()
        val child = thread {
            runCatching {
                val link = SecureBluetoothHandshake.respond(
                    childInput,
                    childOutput,
                    childIdentity,
                    capabilities,
                )
                val endpoint = EndToEndVideoHandshake.respond(link, childIdentity, capabilities)
                try {
                    assertEquals(parentIdentity.nodeId, endpoint.peerNodeId)
                    assertEquals(1, endpoint.peerCapabilities.maxChildren)
                    val wrapped = link.reader.read()
                    assertEquals(SecureRecordType.END_TO_END_CONTENT, wrapped.type)
                    val openedGrant = endpoint.opener.open(wrapped.payload)
                    assertEquals(EndToEndContentType.GROUP_KEY, openedGrant.type)
                    val grant = ForwardedStreamGrantCodec.decode(openedGrant.payload)
                    assertEquals(sourceNode, grant.sourceNodeId)
                    assertEquals(streamId, grant.streamId)
                    val reader = EndToEndStreamReader(
                        link.reader,
                        EndToEndContentOpener(grant.key),
                        grant.sourceNodeId,
                    )
                    grant.key.fill(0)
                    assertEquals(StreamProtocol.CODEC_VP9, reader.readHeader().codecId)
                    childPacket.set((reader.readStreamContent() as ReceivedStreamContent.Media).packet)
                    EndToEndStreamProtocol.writeKeyFrameRequest(link.writer, endpoint.sealer)
                    link.writer.flush()
                } finally {
                    endpoint.close()
                    link.close()
                }
            }.onFailure(childFailure::set)
        }

        val parentLink = SecureBluetoothHandshake.initiate(
            parentInput,
            parentOutput,
            parentIdentity,
            capabilities,
        )
        val endpoint = EndToEndVideoHandshake.initiate(parentLink, parentIdentity, capabilities)
        val grant = endpoint.sealer.seal(
            EndToEndContentType.GROUP_KEY,
            0,
            ForwardedStreamGrantCodec.encode(ForwardedStreamGrant(sourceNode, streamId, mediaKey)),
        )
        parentLink.writer.write(SecureRecordType.END_TO_END_CONTENT, grant)
        val streamWriter = EndToEndStreamWriter(
            parentLink.writer,
            EndToEndContentSealer(mediaKey),
            sourceNode,
            useMeshEnvelope = true,
            meshStreamId = streamId,
        )
        streamWriter.writeHeader(StreamProtocol.CODEC_VP9, VideoSettings.DEFAULT, 0, false)
        streamWriter.writePacket(StreamProtocol.FLAG_KEY_FRAME, 123, "same-ciphertext".toByteArray())
        parentLink.writer.flush()

        assertEquals(
            SourceControlMessage.KEY_FRAME_REQUEST,
            EndToEndStreamProtocol.readSourceControl(parentLink.reader, endpoint.opener),
        )
        child.join(120_000)
        childFailure.get()?.let { throw it }
        assertEquals("same-ciphertext", String(requireNotNull(childPacket.get()).payload))
        endpoint.close()
        parentLink.close()
    }

    @Test
    fun unauthenticatedContentIsNotOfferedToTheForwardingBranch() {
        val source = node(1)
        val streamId = UUID.randomUUID()
        val linkKey = ByteArray(MeshAead.KEY_BYTES) { 2 }
        val mediaKey = ByteArray(MeshAead.KEY_BYTES) { 3 }
        val corrupted = EndToEndContentSealer(mediaKey).use { sealer ->
            sealer.seal(
                EndToEndContentType.STREAM_HEADER,
                0,
                EndToEndStreamProtocol.encodeHeader(
                    StreamProtocol.CODEC_VP9,
                    VideoSettings.DEFAULT,
                    0,
                    false,
                ),
            )
        }.also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val output = ByteArrayOutputStream()
        SecureRecordWriter(output, linkKey).apply {
            write(
                SecureRecordType.MESH_ENVELOPE,
                MeshEnvelopeCodec.encode(MeshEnvelope(streamId, source, 0, 8, 0, corrupted)),
            )
            flush()
        }
        val observed = mutableListOf<MeshEnvelope>()
        val reader = EndToEndStreamReader(
            SecureRecordReader(ByteArrayInputStream(output.toByteArray()), linkKey),
            EndToEndContentOpener(mediaKey),
            source,
            observed::add,
        )

        assertThrows(Exception::class.java) { reader.readHeader() }
        assertTrue(observed.isEmpty())
    }

    @Test
    fun lateChildGetsOnlyTheCurrentEncodersVideoConfiguration() {
        val source = node(1)
        val streamId = UUID.fromString("6f0d4c2a-5a64-4c61-9d34-0a5f6f9b2d11")
        val sealer = EndToEndContentSealer(ByteArray(MeshAead.KEY_BYTES) { it.toByte() })
        val core = ReceiverNodeForwardingCore(node(3), node(2), streamId, source, queueCapacity = 8)
        var sequence = 0L
        fun send(flags: Int, payload: String) {
            core.receive(mediaEnvelope(sealer, streamId, source, sequence++, flags, payload.toByteArray()))
        }
        val audioConfig = StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG
        // First encoder (rung A): its configuration comes before its frames, and the Opus configuration too.
        send(audioConfig, "opus")
        send(StreamProtocol.FLAG_CODEC_CONFIG, "csd-rung-a")
        send(StreamProtocol.FLAG_DISPLAY_ROTATION, "rot") // not media: does not start a new batch
        send(StreamProtocol.FLAG_CODEC_CONFIG, "csd-rung-a-2") // a second packet of the same encoder
        send(StreamProtocol.FLAG_KEY_FRAME, "key-a")
        send(0, "delta-a")
        send(StreamProtocol.FLAG_AUDIO, "audio") // audio frames never start a video batch
        // The Auto ladder restarts the encoder at rung B: new configuration after media.
        send(StreamProtocol.FLAG_CODEC_CONFIG, "csd-rung-b")
        send(StreamProtocol.FLAG_KEY_FRAME, "key-b")

        val bootstrap = core.addChild(node(4))
        val configs = bootstrap.map { String(packetPayloadOf(it)) }

        assertEquals(listOf("opus", "csd-rung-b"), configs)
    }

    /** Opens a test envelope sealed with the key 0, 1, 2, ... and returns the media packet's payload. */
    private fun packetPayloadOf(envelope: MeshEnvelope): ByteArray {
        val opener = EndToEndContentOpener(ByteArray(MeshAead.KEY_BYTES) { it.toByte() })
        val record = opener.open(envelope.payload)
        return EndToEndStreamProtocol.decodePacket(record).payload
    }

    private fun mediaEnvelope(
        sealer: EndToEndContentSealer,
        streamId: UUID,
        source: NodeId,
        sequence: Long,
        flags: Int,
        payload: ByteArray,
    ): MeshEnvelope = envelope(
        streamId,
        source,
        sequence,
        5,
        flags,
        sealer.seal(
            EndToEndContentType.MEDIA_PACKET,
            flags,
            EndToEndStreamProtocol.encodePacket(flags, sequence * 1_000, payload),
        ),
    )

    private fun envelope(
        streamId: UUID,
        source: NodeId,
        sequence: Long,
        ttl: Int,
        flags: Int,
        payload: ByteArray,
    ) = MeshEnvelope(streamId, source, sequence, ttl, flags, payload)

    private fun node(seed: Int): NodeId =
        NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { (seed + it).toByte() })
}
