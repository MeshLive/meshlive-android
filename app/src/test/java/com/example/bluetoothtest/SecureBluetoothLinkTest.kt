package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureBluetoothLinkTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private val initiatorCapabilities = NodeCapabilities(
        transportMask = NodeCapabilities.TRANSPORT_BLE_L2CAP,
        encodeCodecMask = NodeCapabilities.CODEC_VP9,
        decodeCodecMask = NodeCapabilities.CODEC_VP9,
        maxBitrateKbps = 1_100,
        maxChildren = 0,
    )
    private val responderCapabilities = initiatorCapabilities.copy(
        encodeCodecMask = 0,
        decodeCodecMask = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
    )

    @Test
    fun authenticatedHopWithdrawalStopsOnlyTheSelectedRoute() {
        val linkKey = ByteArray(MeshAead.KEY_BYTES) { (it + 1).toByte() }
        val contentKey = ByteArray(MeshAead.KEY_BYTES) { (it + 33).toByte() }
        val sourceNode = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { 7 })
        val bytes = ByteArrayOutputStream()
        val linkWriter = SecureRecordWriter(bytes, linkKey)
        val streamWriter = EndToEndStreamWriter(
            linkWriter,
            EndToEndContentSealer(contentKey),
            sourceNode,
            useMeshEnvelope = true,
        )
        streamWriter.writeHeader(
            StreamProtocol.CODEC_VP9,
            VideoSettings.DEFAULT,
            rotationDegrees = 0,
            includeAudio = true,
        )
        linkWriter.write(
            SecureRecordType.ROUTE_CONTROL,
            StreamRouteControlProtocol.encode(StreamRouteWithdrawal.LOCAL_CAPACITY),
        )
        linkWriter.flush()

        val reader = EndToEndStreamReader(
            SecureRecordReader(ByteArrayInputStream(bytes.toByteArray()), linkKey),
            EndToEndContentOpener(contentKey),
            sourceNode,
        )
        reader.readHeader()

        val error = assertThrows(StreamRouteWithdrawnException::class.java) {
            reader.readStreamContent()
        }
        assertEquals(StreamRouteWithdrawal.LOCAL_CAPACITY, error.reason)
    }

    @Test
    fun sourceControlParserRecognizesAuthenticatedCapacityWithdrawalAcknowledgement() {
        val linkKey = ByteArray(MeshAead.KEY_BYTES) { (it + 1).toByte() }
        val contentKey = ByteArray(MeshAead.KEY_BYTES) { (it + 33).toByte() }
        val bytes = ByteArrayOutputStream()
        SecureRecordWriter(bytes, linkKey).apply {
            write(
                SecureRecordType.ROUTE_CONTROL_ACK,
                StreamRouteControlProtocol.encode(StreamRouteWithdrawal.LOCAL_CAPACITY),
            )
            flush()
        }

        assertEquals(
            SourceControlMessage.ROUTE_WITHDRAWAL_ACK,
            EndToEndStreamProtocol.readSourceControl(
                SecureRecordReader(ByteArrayInputStream(bytes.toByteArray()), linkKey),
                EndToEndContentOpener(contentKey),
            ),
        )
    }

    @Test
    fun authenticatedHopWithdrawalAcknowledgementRoundTrips() {
        val linkKey = ByteArray(MeshAead.KEY_BYTES) { (it + 1).toByte() }
        val bytes = ByteArrayOutputStream()
        SecureRecordWriter(bytes, linkKey).apply {
            write(
                SecureRecordType.ROUTE_CONTROL_ACK,
                StreamRouteControlProtocol.encode(StreamRouteWithdrawal.LOCAL_CAPACITY),
            )
            flush()
        }

        val record = SecureRecordReader(
            ByteArrayInputStream(bytes.toByteArray()),
            linkKey,
        ).read()
        assertEquals(SecureRecordType.ROUTE_CONTROL_ACK, record.type)
        assertEquals(
            StreamRouteWithdrawal.LOCAL_CAPACITY,
            StreamRouteControlProtocol.decode(record.payload),
        )
    }

    @Test
    fun serializedMeshStreamPreservesEndpointContentAcrossTwoTtlDecrements() {
        val linkKey = ByteArray(MeshAead.KEY_BYTES) { (it + 1).toByte() }
        val contentKey = ByteArray(MeshAead.KEY_BYTES) { (it + 33).toByte() }
        val sourceNode = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { 7 })
        val sourceBytes = ByteArrayOutputStream()
        val sourceLinkWriter = SecureRecordWriter(sourceBytes, linkKey)
        val streamWriter = EndToEndStreamWriter(
            sourceLinkWriter,
            EndToEndContentSealer(contentKey),
            sourceNode,
            useMeshEnvelope = true,
        )
        streamWriter.writeHeader(
            StreamProtocol.CODEC_VP9,
            VideoSettings.DEFAULT,
            rotationDegrees = 0,
            includeAudio = true,
        )
        streamWriter.writePacket(
            StreamProtocol.FLAG_KEY_FRAME,
            123L,
            "mesh-frame".toByteArray(),
        )
        streamWriter.writeState(StreamLifecycleState.ENDED)
        sourceLinkWriter.flush()

        val afterFirstRelay = forwardMeshRecords(sourceBytes.toByteArray(), linkKey, sourceNode)
        val afterSecondRelay = forwardMeshRecords(afterFirstRelay, linkKey, sourceNode)
        val finalLinkReader = SecureRecordReader(ByteArrayInputStream(afterSecondRelay), linkKey)
        val streamReader = EndToEndStreamReader(
            finalLinkReader,
            EndToEndContentOpener(contentKey),
            sourceNode,
        )

        assertEquals(StreamProtocol.CODEC_VP9, streamReader.readHeader().codecId)
        val packet = streamReader.readStreamContent() as ReceivedStreamContent.Media
        assertEquals("mesh-frame", String(packet.packet.payload))
        val state = streamReader.readStreamContent() as ReceivedStreamContent.State
        assertEquals(StreamLifecycleState.ENDED, state.state)
        assertEquals(streamWriter.meshStreamId, streamReader.meshStreamId)

        val finalRecordReader = SecureRecordReader(ByteArrayInputStream(afterSecondRelay), linkKey)
        val firstFinalRecord = finalRecordReader.read()
        assertEquals(SecureRecordType.MESH_ENVELOPE, firstFinalRecord.type)
        assertEquals(6, MeshEnvelopeCodec.decode(firstFinalRecord.payload).ttl)
        finalRecordReader.read()
        val terminalEnvelope = MeshEnvelopeCodec.decode(finalRecordReader.read().payload)
        assertTrue(terminalEnvelope.flags and MeshEnvelope.FLAG_TERMINAL != 0)
    }

    @Test
    fun fullDuplexHandshakeCarriesVideoHeartbeatAndKeyFrameRequest() {
        val initiatorInput = PipedInputStream(16 * 1024)
        val responderOutput = PipedOutputStream(initiatorInput)
        val responderInput = PipedInputStream(16 * 1024)
        val initiatorOutput = PipedOutputStream(responderInput)
        val responderFailure = AtomicReference<Throwable?>()
        val receivedHeader = AtomicReference<StreamProtocol.Header>()
        val receivedPacket = AtomicReference<StreamProtocol.Packet>()
        val receivedState = AtomicReference<StreamLifecycleState>()
        val responder = thread {
            runCatching {
                val identity = KeyPairSigningIdentity(generateEcKeyPair())
                val session = SecureBluetoothHandshake.respond(
                    responderInput,
                    responderOutput,
                    identity,
                    responderCapabilities,
                )
                val content = EndToEndVideoHandshake.respond(session, identity, responderCapabilities)
                try {
                    receivedHeader.set(EndToEndStreamProtocol.readHeader(session.reader, content.opener))
                    receivedPacket.set(EndToEndStreamProtocol.readPacket(session.reader, content.opener))
                    val state = EndToEndStreamProtocol.readStreamContent(session.reader, content.opener)
                    receivedState.set((state as ReceivedStreamContent.State).state)
                    EndToEndStreamProtocol.writeKeyFrameRequest(
                        session.writer,
                        content.sealer,
                    )
                    SecureLinkControlProtocol.writeHeartbeat(session.writer)
                    EndToEndStreamProtocol.writeStreamEndAck(session.writer, content.sealer)
                    session.writer.flush()
                } finally {
                    content.close()
                    session.close()
                }
            }.onFailure(responderFailure::set)
        }
        val initiatorIdentity = KeyPairSigningIdentity(generateEcKeyPair())
        val initiatorSession = SecureBluetoothHandshake.initiate(
            initiatorInput,
            initiatorOutput,
            initiatorIdentity,
            initiatorCapabilities,
        )
        val contentSession = EndToEndVideoHandshake.initiate(
            initiatorSession,
            initiatorIdentity,
            initiatorCapabilities,
        )
        val settings = VideoSettings.DEFAULT
        EndToEndStreamProtocol.writeHeader(
            initiatorSession.writer,
            contentSession.sealer,
            StreamProtocol.CODEC_VP9,
            settings,
            rotationDegrees = 0,
            includeAudio = true,
        )
        EndToEndStreamProtocol.writePacket(
            initiatorSession.writer,
            contentSession.sealer,
            StreamProtocol.FLAG_KEY_FRAME,
            123L,
            "vp9-frame".toByteArray(),
        )
        EndToEndStreamProtocol.writeState(
            initiatorSession.writer,
            contentSession.sealer,
            StreamLifecycleState.PAUSED,
        )
        initiatorSession.writer.flush()
        assertEquals(
            SourceControlMessage.KEY_FRAME_REQUEST,
            EndToEndStreamProtocol.readSourceControl(initiatorSession.reader, contentSession.opener),
        )
        assertEquals(
            SourceControlMessage.LINK_HEARTBEAT,
            EndToEndStreamProtocol.readSourceControl(initiatorSession.reader, contentSession.opener),
        )
        assertEquals(
            SourceControlMessage.STREAM_END_ACK,
            EndToEndStreamProtocol.readSourceControl(initiatorSession.reader, contentSession.opener),
        )
        responder.join(120_000)
        responderFailure.get()?.let { throw it }
        assertEquals(StreamProtocol.CODEC_VP9, checkNotNull(receivedHeader.get()).codecId)
        assertEquals("vp9-frame", String(checkNotNull(receivedPacket.get()).payload))
        assertEquals(StreamLifecycleState.PAUSED, receivedState.get())
        contentSession.close()
        initiatorSession.close()
    }

    @Test
    fun streamLifecycleCodecRejectsUnknownOrMalformedStates() {
        StreamLifecycleState.entries.forEach { state ->
            assertEquals(state, StreamLifecycleState.decode(state.encode()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StreamLifecycleState.decode(byteArrayOf())
        }
        assertThrows(IllegalStateException::class.java) {
            StreamLifecycleState.decode(byteArrayOf(99))
        }
    }

    @Test
    fun modifiedEncryptedRecordIsRejected() {
        val key = ByteArray(MeshAead.KEY_BYTES) { it.toByte() }
        val output = ByteArrayOutputStream()
        SecureRecordWriter(output, key).apply {
            write(SecureRecordType.END_TO_END_CONTENT, "frame".toByteArray())
            flush()
        }
        val modified = output.toByteArray().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }

        val failure = assertThrows(GeneralSecurityException::class.java) {
            SecureRecordReader(ByteArrayInputStream(modified), key).read()
        }
        // The details moved to the hop_integrity log line (HopIntegrityLogTest); the message reaches the user's screen.
        assertEquals("Secure link decrypt failed", failure.message)
        assertTrue(failure.cause is GeneralSecurityException)
    }

    @Test
    fun retiredCongestionFeedbackWireTypesAreRejected() {
        val key = ByteArray(MeshAead.KEY_BYTES) { it.toByte() }
        val linkBytes = ByteArrayOutputStream().also { output ->
            SecureRecordWriter(output, key).apply {
                write(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(1))
                flush()
            }
        }.toByteArray().also { encoded ->
            encoded[4] = 3
        }
        assertThrows(IllegalStateException::class.java) {
            SecureRecordReader(ByteArrayInputStream(linkBytes), key).read()
        }

        val contentBytes = EndToEndContentSealer(key).use { sealer ->
            sealer.seal(EndToEndContentType.KEY_FRAME_REQUEST, 0, ByteArray(0))
        }.also { encoded ->
            encoded[4] = 3
        }
        assertThrows(IllegalStateException::class.java) {
            EndToEndContentProtocol.inspect(contentBytes)
        }
    }

    @Test
    fun endToEndContentRejectsTamperingReplayAndWrongRecipient() {
        val key = ByteArray(MeshAead.KEY_BYTES) { it.toByte() }
        val wrongKey = ByteArray(MeshAead.KEY_BYTES) { (it + 1).toByte() }
        val plaintext = "encoded VP9 access unit".toByteArray()
        val encoded = EndToEndContentSealer(key).use {
            it.seal(EndToEndContentType.MEDIA_PACKET, StreamProtocol.FLAG_KEY_FRAME, plaintext)
        }

        val metadata = EndToEndContentProtocol.inspect(encoded)
        assertEquals(EndToEndContentType.MEDIA_PACKET, metadata.type)
        assertEquals(StreamProtocol.FLAG_KEY_FRAME, metadata.flags)
        assertEquals(-1, encoded.toList().windowed(plaintext.size).indexOf(plaintext.toList()))

        val opener = EndToEndContentOpener(key)
        assertEquals("encoded VP9 access unit", String(opener.open(encoded).payload))
        assertThrows(IllegalArgumentException::class.java) { opener.open(encoded) }
        opener.close()

        assertThrows(Exception::class.java) {
            EndToEndContentOpener(wrongKey).use { it.open(encoded) }
        }
        val modified = encoded.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        val failure = assertThrows(GeneralSecurityException::class.java) {
            EndToEndContentOpener(key).use { it.open(modified) }
        }
        assertTrue(failure.message.orEmpty().contains("End-to-end decrypt failed: type=MEDIA_PACKET sequence=0"))
        assertTrue(failure.cause is GeneralSecurityException)
    }

    @Test
    fun endToEndContentAllowsAuthenticatedRelayDropsButRejectsOlderRecords() {
        val key = ByteArray(MeshAead.KEY_BYTES) { it.toByte() }
        val records = EndToEndContentSealer(key).use { sealer ->
            List(3) { sequence ->
                sealer.seal(
                    EndToEndContentType.MEDIA_PACKET,
                    0,
                    "packet-$sequence".toByteArray(),
                )
            }
        }

        EndToEndContentOpener(key).use { opener ->
            assertEquals("packet-0", String(opener.open(records[0]).payload))
            assertEquals("packet-2", String(opener.open(records[2]).payload))
            assertThrows(IllegalArgumentException::class.java) { opener.open(records[1]) }
        }
    }

    @Test
    fun aWifiRecordIsAnUnknownTypeUntilTheReaderOptsIn() {
        val key = ByteArray(MeshAead.KEY_BYTES) { (it + 5).toByte() }
        val bytes = ByteArrayOutputStream().also { output ->
            SecureRecordWriter(output, key).apply {
                write(SecureRecordType.BWF1, byteArrayOf(1, 2, 3))
                flush()
            }
        }.toByteArray()
        val failure = assertThrows(IllegalStateException::class.java) {
            SecureRecordReader(ByteArrayInputStream(bytes), key).read()
        }
        assertEquals("Unknown secure record type 16", failure.message)
        val reader = SecureRecordReader(ByteArrayInputStream(bytes), key)
        reader.acceptWifi = true
        val record = reader.read()
        assertEquals(SecureRecordType.BWF1, record.type)
        assertArrayEquals(byteArrayOf(1, 2, 3), record.payload)
    }

    @Test
    fun theWifiOptInDoesNotChangeAnyOtherType() {
        val key = ByteArray(MeshAead.KEY_BYTES) { (it + 6).toByte() }
        fun encoded(type: SecureRecordType): ByteArray = ByteArrayOutputStream().also { output ->
            SecureRecordWriter(output, key).apply {
                write(type, byteArrayOf(7))
                flush()
            }
        }.toByteArray()
        for (type in listOf(SecureRecordType.STORE_FORWARD, SecureRecordType.RELAY_SETUP, SecureRecordType.DISCOVERY_OPENING)) {
            val reader = SecureRecordReader(ByteArrayInputStream(encoded(type)), key)
            reader.acceptWifi = true
            assertThrows(IllegalStateException::class.java) { reader.read() }
        }
        for (type in listOf(SecureRecordType.END_TO_END_CONTENT, SecureRecordType.LINK_HEARTBEAT)) {
            for (acceptWifi in listOf(false, true)) {
                val reader = SecureRecordReader(ByteArrayInputStream(encoded(type)), key)
                reader.acceptWifi = acceptWifi
                assertEquals(type, reader.read().type)
            }
        }
    }

    @Test
    fun aWifiRecordKeepsTheExactNextSequenceRule() {
        val key = ByteArray(MeshAead.KEY_BYTES) { (it + 7).toByte() }
        fun firstRecord(): ByteArray = ByteArrayOutputStream().also { output ->
            SecureRecordWriter(output, key).apply {
                write(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(1))
                flush()
            }
        }.toByteArray()
        val bytes = ByteArrayOutputStream().also { output ->
            SecureRecordWriter(output, key).apply {
                write(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(1))
                write(SecureRecordType.BWF1, byteArrayOf(2))
                write(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(3))
                flush()
            }
        }.toByteArray()
        val reader = SecureRecordReader(ByteArrayInputStream(bytes), key)
        reader.acceptWifi = true
        assertEquals(
            listOf(SecureRecordType.LINK_HEARTBEAT, SecureRecordType.BWF1, SecureRecordType.LINK_HEARTBEAT),
            List(3) { reader.read().type },
        )
        val skipped = SecureRecordReader(ByteArrayInputStream(bytes.copyOfRange(firstRecord().size, bytes.size)), key)
        skipped.acceptWifi = true
        val failure = assertThrows(IllegalArgumentException::class.java) { skipped.read() }
        assertEquals("Unexpected secure record sequence", failure.message)
    }

    private fun forwardMeshRecords(
        encodedLink: ByteArray,
        linkKey: ByteArray,
        sourceNode: NodeId,
    ): ByteArray {
        val input = ByteArrayInputStream(encodedLink)
        val reader = SecureRecordReader(input, linkKey)
        val output = ByteArrayOutputStream()
        val writer = SecureRecordWriter(output, linkKey)
        while (input.available() > 0) {
            val record = reader.read()
            assertEquals(SecureRecordType.MESH_ENVELOPE, record.type)
            val envelope = MeshEnvelopeCodec.decode(record.payload)
            assertEquals(sourceNode, envelope.sourceNodeId)
            writer.write(
                SecureRecordType.MESH_ENVELOPE,
                MeshEnvelopeCodec.encode(requireNotNull(envelope.forwarded())),
            )
        }
        writer.flush()
        return output.toByteArray()
    }
}
