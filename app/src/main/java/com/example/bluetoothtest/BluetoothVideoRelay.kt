package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class BluetoothVideoRelay(
    private val onStatus: (String) -> Unit,
    private val onStopped: () -> Unit,
) : Closeable {
    private val running = AtomicBoolean(false)
    @Volatile private var workerThread: Thread? = null
    @Volatile private var writerThread: Thread? = null
    @Volatile private var childControlThread: Thread? = null
    @Volatile private var upstreamControlThread: Thread? = null
    /** Classic: the RFCOMM server socket; LE CoC: a lease on the role's long-lived listener. */
    @Volatile private var upstreamServerSocket: java.io.Closeable? = null
    @Volatile private var upstreamSocket: BluetoothSocket? = null
    @Volatile private var downstreamSocket: BluetoothSocket? = null
    @Volatile private var upstreamControlServer: BleL2capControlServer? = null
    @Volatile private var downstreamControlConnection: BleL2capControlConnection? = null
    @Volatile private var upstreamGattServer: GattVideoServer? = null
    @Volatile private var upstreamGattConnection: GattVideoConnection? = null
    @Volatile private var downstreamGattConnection: GattVideoConnection? = null
    @Volatile private var upstreamDiscoveryAdvertiser: BleDiscoveryAdvertiser? = null
    @Volatile private var upstreamSession: SecureBluetoothSession? = null
    @Volatile private var downstreamSession: SecureBluetoothSession? = null
    private val downstreamSockets = CopyOnWriteArrayList<BluetoothSocket>()
    private val downstreamGattConnections = CopyOnWriteArrayList<GattVideoConnection>()
    private val downstreamSessions = CopyOnWriteArrayList<SecureBluetoothSession>()
    private val groupWriterThreads = CopyOnWriteArrayList<Thread>()
    private val groupControlThreads = CopyOnWriteArrayList<Thread>()

    @SuppressLint("MissingPermission")
    fun start(
        context: Context,
        adapter: BluetoothAdapter,
        child: BleL2capPeer,
        transport: BluetoothTransportOption,
    ) = start(context, adapter, listOf(child), transport)

    @SuppressLint("MissingPermission")
    fun start(
        context: Context,
        adapter: BluetoothAdapter,
        children: List<BleL2capPeer>,
        transport: BluetoothTransportOption,
    ) {
        require(children.isNotEmpty()) { "A relay needs at least one receiver" }
        require(children.distinctBy { it.announcement.nodeIdPrefix.contentHashCode() }.size == children.size) {
            "Relay receivers must be distinct"
        }
        check(running.compareAndSet(false, true)) { "Relay is already running" }
        workerThread = thread(name = "BluetoothVideoRelay") {
            OtherBluetoothMedia.manualRelayStarted()   // AP10: a call on this phone sees this relay; paired with the finally below
            try {
                require(children.all { it.announcement.transportOption() == transport }) {
                    "Every selected receiver must advertise ${transport.shortLabel}"
                }
                val identity = AndroidNodeIdentityStore().getOrCreate()
                val capabilities = LocalNodeCapabilities.forTransport(
                    transport,
                    maxChildren = children.size,
                )
                val childSessions = children.map { child ->
                    connectChild(context, adapter, child, transport, identity, capabilities)
                }
                val sourceSession = acceptSource(context, adapter, transport, identity, capabilities)
                if (childSessions.size == 1) {
                    relayStream(sourceSession, childSessions.single(), identity.nodeId, transport)
                } else {
                    relayGroupStream(sourceSession, childSessions, identity.nodeId, transport)
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                if (running.get()) {
                    onStatus("Relay stopped: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                OtherBluetoothMedia.manualRelayStopped()
                running.set(false)
                closeResources()
                workerThread = null
                onStopped()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectChild(
        context: Context,
        adapter: BluetoothAdapter,
        child: BleL2capPeer,
        transport: BluetoothTransportOption,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
    ): SecureBluetoothSession {
        onStatus("Relay connecting to next receiver ${child.label}…")
        if (transport == BluetoothTransportOption.CLASSIC_RFCOMM) {
            return connectClassicChild(context, adapter, child, transport, identity, capabilities)
        }
        val device = child.device
        val socket = when (transport) {
            BluetoothTransportOption.CLASSIC_RFCOMM -> error("Classic children use connectClassicChild")
            BluetoothTransportOption.BLE_L2CAP -> {
                BleCocClient.connect(
                    context,
                    device,
                    onStatus,
                    BlePhyPreference.TWO_M,
                    "relay-connect",
                ) { downstreamControlConnection = it }
            }
            BluetoothTransportOption.BLE_GATT -> null
        }
        downstreamSocket = socket
        socket?.let(downstreamSockets::add)
        // CoC sockets come back connected (BleCocClient).
        if (socket?.isConnected == false) socket.connect()
        if (transport == BluetoothTransportOption.BLE_L2CAP) {
            onStatus("Relay is keeping downstream GATT control active for CoC link priority.")
        }
        val gatt = if (transport == BluetoothTransportOption.BLE_GATT) {
            GattVideoClient.open(
                context,
                device,
                BlePhyPreference.TWO_M,
                BleConnectionPriority.HIGH,
                onStatus,
            ).also { downstreamGattConnection = it }
                .also(downstreamGattConnections::add)
        } else {
            null
        }
        val input = gatt?.inputStream ?: socket?.inputStream
            ?: error("Relay child input is unavailable")
        val output = gatt?.outputStream ?: socket?.mediaOutputStream(forwarding = true)
            ?: error("Relay child output is unavailable")
        val session = SecureBluetoothHandshake.initiate(
            BufferedInputStream(input, BUFFER_BYTES),
            BufferedOutputStream(output, BUFFER_BYTES),
            identity,
            capabilities,
        )
        // A7 (X4): this harness relay keeps no HopLink and never writes BWF1; its children's CAPS are skipped below.
        session.reader.acceptWifi = session.wifiReadOptIn
        require(session.peerCapabilities.transportMask and transport.capabilityMask != 0) {
            "Next receiver did not authenticate ${transport.shortLabel} support"
        }
        require(
            session.peerNodeId.toByteArray()
                .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
                .contentEquals(child.announcement.nodeIdPrefix),
        ) { "Next receiver identity changed after discovery" }
        downstreamSession = session
        downstreamSessions += session
        // test_coc_phy_after_connect (default off): PHY only now, before media (no-op off CoC).
        downstreamControlConnection?.requestPhyAfterConnect("relay")
        onStatus("Relay authenticated next receiver ${session.peerNodeId.shortLabel()}.")
        return session
    }

    /** The next receiver over Classic through the shared connector (candidate order, deadlines, identity). */
    @SuppressLint("MissingPermission")
    private fun connectClassicChild(
        context: Context,
        adapter: BluetoothAdapter,
        child: BleL2capPeer,
        transport: BluetoothTransportOption,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
    ): SecureBluetoothSession {
        val connected = ClassicConnector.connect(
            context,
            adapter,
            child.device,
            child.announcement.nodeIdPrefix,
            ClassicRole.STREAM_RELAY,
            verifyLinkPrefix = true,
            totalBudgetMs = CLASSIC_SESSION_BUDGET_MS,
            allowInquiry = false,
            isCancelled = { !running.get() },
            onSocket = { socket ->
                downstreamSocket = socket
                downstreamSockets += socket
            },
            handshake = { socket ->
                SecureBluetoothHandshake.initiate(
                    BufferedInputStream(socket.inputStream, BUFFER_BYTES),
                    BufferedOutputStream(socket.mediaOutputStream(forwarding = true), BUFFER_BYTES),
                    identity,
                    capabilities,
                )
            },
            verify = { session ->
                require(session.peerCapabilities.transportMask and transport.capabilityMask != 0) {
                    "Next receiver did not authenticate ${transport.shortLabel} support"
                }
            },
            onStatus = onStatus,
        )
        downstreamSocket = connected.socket
        val session = connected.session
        // A7 (X4): a child's CAPS is skipped by readSkippingBwf1, never an error.
        session.reader.acceptWifi = session.wifiReadOptIn
        downstreamSession = session
        downstreamSessions += session
        // test_coc_phy_after_connect (default off): PHY only now, before media (no-op off CoC).
        downstreamControlConnection?.requestPhyAfterConnect("relay")
        onStatus("Relay authenticated next receiver ${session.peerNodeId.shortLabel()}.")
        return session
    }

    @SuppressLint("MissingPermission")
    private fun acceptSource(
        context: Context,
        adapter: BluetoothAdapter,
        transport: BluetoothTransportOption,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
    ): SecureBluetoothSession {
        val announcement = DiscoveryAnnouncement(
            nodeIdPrefix = identity.nodeId.toByteArray()
                .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
            flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or
                DiscoveryAnnouncement.FLAG_RELAY_CAPABLE or transport.discoveryFlag,
            capabilitySummary = VideoCodecMap.helloMaskToSummary(capabilities.decodeCodecMask),
            streamToken = 0,
            routeCost = 1,
            sequence = 0,
        )
        val streams = when (transport) {
            BluetoothTransportOption.CLASSIC_RFCOMM -> acceptClassicSource(adapter, announcement)
            BluetoothTransportOption.BLE_L2CAP -> acceptCocSource(context, adapter, announcement)
            BluetoothTransportOption.BLE_GATT -> acceptGattSource(context, adapter, announcement)
        }
        val session = SecureBluetoothHandshake.respond(
            BufferedInputStream(streams.first, BUFFER_BYTES),
            BufferedOutputStream(streams.second, BUFFER_BYTES),
            identity,
            capabilities,
        )
        require(session.peerCapabilities.transportMask and transport.capabilityMask != 0) {
            "Source did not authenticate ${transport.shortLabel} support"
        }
        if (transport == BluetoothTransportOption.CLASSIC_RFCOMM) {
            learnClassicPeerAddress(upstreamSocket, session.peerNodeId, "incoming", session.peerPseudonymous)
        }
        upstreamSession = session
        onStatus("Relay authenticated source ${session.peerNodeId.shortLabel()}.")
        return session
    }

    @SuppressLint("MissingPermission")
    private fun acceptClassicSource(
        adapter: BluetoothAdapter,
        announcement: DiscoveryAnnouncement,
    ): Pair<InputStream, OutputStream> {
        val serverSocket = ClassicSockets.listen(adapter, "Bluetooth Video Relay", ClassicRole.STREAM_RELAY)
        upstreamServerSocket = serverSocket
        upstreamDiscoveryAdvertiser = BleDiscoveryAdvertiser(
            adapter,
            announcement,
            onStatus,
        ).also(BleDiscoveryAdvertiser::start)
        onStatus("Classic relay is advertising. Start the source phone now.")
        val socket = serverSocket.accept()
        ClassicRfcommCap.note(socket)
        upstreamSocket = socket
        upstreamDiscoveryAdvertiser?.close()
        upstreamDiscoveryAdvertiser = null
        return socket.inputStream to socket.mediaOutputStream(forwarding = true)
    }

    @SuppressLint("MissingPermission")
    private fun acceptCocSource(
        context: Context,
        adapter: BluetoothAdapter,
        announcement: DiscoveryAnnouncement,
    ): Pair<InputStream, OutputStream> {
        // One long-lived listener per role (CocListeners): Android 10 never frees a closed LE CoC server.
        val serverSocket = CocListeners.listen(adapter, "relay")
        upstreamServerSocket = serverSocket
        val controlServer = BleL2capControlServer(
            context = context,
            adapter = adapter,
            psm = serverSocket.psm,
            announcement = announcement,
            onLinkStatus = onStatus,
        )
        upstreamControlServer = controlServer
        controlServer.start()
        onStatus("CoC relay is advertising. Start the source phone now.")
        val socket = serverSocket.accept()
        upstreamSocket = socket
        controlServer.stopAdvertising()
        onStatus("Relay is keeping upstream GATT control active for CoC link priority.")
        return socket.inputStream to socket.mediaOutputStream(forwarding = true)
    }

    private fun acceptGattSource(
        context: Context,
        adapter: BluetoothAdapter,
        announcement: DiscoveryAnnouncement,
    ): Pair<InputStream, OutputStream> {
        val connections = LinkedBlockingQueue<GattVideoConnection>(1)
        val server = GattVideoServer(
            context,
            adapter,
            announcement,
            BlePhyPreference.TWO_M,
            onStatus,
            connections::offer,
        )
        upstreamGattServer = server
        server.start()
        onStatus("GATT relay is advertising. Start the source phone now.")
        val connection = connections.poll(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            ?: error("Timed out waiting for the GATT source")
        upstreamGattConnection = connection
        return connection.inputStream to connection.outputStream
    }

    private fun relayStream(
        source: SecureBluetoothSession,
        child: SecureBluetoothSession,
        localNodeId: NodeId,
        transport: BluetoothTransportOption,
    ) {
        val sourceHelloRecord = source.reader.read()
        require(sourceHelloRecord.type == SecureRecordType.END_TO_END_HANDSHAKE) {
            "Source did not start an end-to-end handshake"
        }
        val sourceHello = HandshakeHelloCodec.decode(sourceHelloRecord.payload)
        require(sourceHello.role == HandshakeRole.INITIATOR) { "Invalid end-to-end source role" }
        MeshHandshake.verify(sourceHello, HandshakePurpose.END_TO_END)
        child.writer.write(SecureRecordType.END_TO_END_HANDSHAKE, sourceHelloRecord.payload)
        child.writer.flush()

        val viewerHelloRecord = readSkippingBwf1(child.reader)
        require(viewerHelloRecord.type == SecureRecordType.END_TO_END_HANDSHAKE) {
            "Final receiver did not answer the end-to-end handshake"
        }
        val viewerHello = HandshakeHelloCodec.decode(viewerHelloRecord.payload)
        require(viewerHello.role == HandshakeRole.RESPONDER) { "Invalid end-to-end receiver role" }
        MeshHandshake.verify(viewerHello, HandshakePurpose.END_TO_END)
        source.writer.write(SecureRecordType.END_TO_END_HANDSHAKE, viewerHelloRecord.payload)
        source.writer.flush()

        val queueCapacity = RELAY_QUEUE_CAPACITY
        // Experimental temporal layering (off by default): child queues may thin discardable frames.
        val thinDiscardable = DebugTunables.experimentalTemporalLayers
        val relayCore = MeshRelayCore(localNodeId, thinDiscardable = thinDiscardable)
        relayCore.addChild(child.peerNodeId)
        val keyFrameRequests = ArrayBlockingQueue<ByteArray>(1)
        val terminalAckForwarded = CountDownLatch(1)
        val lastChildHeartbeatNs = AtomicLong(System.nanoTime())
        startChildWriter(child, relayCore)
        startChildControlReader(child, keyFrameRequests, lastChildHeartbeatNs)
        startUpstreamControlWriter(
            source,
            keyFrameRequests,
            lastChildHeartbeatNs,
            terminalAckForwarded,
            transport,
        )

        val headerEnvelope = decodeSourceEnvelope(source.reader.read(), sourceHello.nodeId)
        require(
            EndToEndContentProtocol.inspect(headerEnvelope.payload).type == EndToEndContentType.STREAM_HEADER,
        ) { "First end-to-end content record is not a stream header" }
        val headerResult = relayCore.receive(sourceHello.nodeId, headerEnvelope)
        require(headerResult.rejection == null) {
            "Relay rejected the stream header: ${headerResult.rejection}"
        }

        var receivedPackets = 0L
        onStatus(
            "Relaying opaque end-to-end encrypted media from ${sourceHello.nodeId.shortLabel()} " +
                "to ${viewerHello.nodeId.shortLabel()} without decrypting or re-encoding it. " +
                "Mesh ${headerEnvelope.streamId}, TTL ${headerEnvelope.ttl - 1}.",
        )
        while (running.get()) {
            val envelope = decodeSourceEnvelope(source.reader.read(), sourceHello.nodeId)
            require(envelope.streamId == headerEnvelope.streamId) { "Mesh stream ID changed at relay" }
            val metadata = EndToEndContentProtocol.inspect(envelope.payload)
            require(
                metadata.type == EndToEndContentType.MEDIA_PACKET ||
                    metadata.type == EndToEndContentType.STREAM_STATE,
            ) {
                "Unexpected source content ${metadata.type}"
            }
            val result = relayCore.receive(sourceHello.nodeId, envelope)
            require(result.rejection == null) { "Relay rejected source packet: ${result.rejection}" }
            receivedPackets++
            if (envelope.flags and MeshEnvelope.FLAG_TERMINAL != 0) {
                flushTerminalEnvelope(child, relayCore, child.peerNodeId)
                val acknowledged = terminalAckForwarded.await(
                    TERMINAL_ACK_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS,
                )
                onStatus(
                    if (acknowledged) {
                        "Relay forwarded the authenticated terminal state and receiver acknowledgement."
                    } else {
                        "Relay flushed the terminal state; receiver acknowledgement timed out."
                    },
                )
                return
            }
            if (receivedPackets % STATUS_INTERVAL_PACKETS == 0L) {
                onStatus(
                    "Relay received $receivedPackets packets | queue ${relayCore.queueDepth(child.peerNodeId)}/$queueCapacity " +
                        "| backlog ${relayCore.backlogAgeMs(child.peerNodeId)} ms | TTL ${envelope.ttl - 1}" +
                        if (thinDiscardable) " | thinned ${relayCore.thinnedFrames(child.peerNodeId)}" else "",
                )
            }
        }
    }

    private fun relayGroupStream(
        source: SecureBluetoothSession,
        children: List<SecureBluetoothSession>,
        localNodeId: NodeId,
        transport: BluetoothTransportOption,
    ) {
        val sourceNodeIds = children.map { child -> proxyGroupReceiverHandshake(source, child) }.toSet()
        require(sourceNodeIds.size == 1) { "Group source endpoint identity changed between receivers" }
        val authenticatedSourceNodeId = sourceNodeIds.single()

        val queueCapacity = RELAY_QUEUE_CAPACITY
        // Experimental temporal layering (off by default): child queues may thin discardable frames.
        val thinDiscardable = DebugTunables.experimentalTemporalLayers
        val relayCore = MeshRelayCore(localNodeId, thinDiscardable = thinDiscardable)
        val activeChildren = ConcurrentHashMap<NodeId, SecureBluetoothSession>()
        val lastChildHeartbeats = ConcurrentHashMap<NodeId, AtomicLong>()
        val receiverControls = BoundedControlQueue<GroupRelayControlMessage>(
            MemoryThermalPolicy.controlQueueCapacity(MemoryThermalPolicy.RELAY_CONTROL_QUEUE_CAPACITY),
            "group relay",
        )
        val terminalAcksForwarded = CountDownLatch(children.size)
        children.forEach { child ->
            relayCore.addChild(child.peerNodeId)
            activeChildren[child.peerNodeId] = child
            lastChildHeartbeats[child.peerNodeId] = AtomicLong(System.nanoTime())
            startGroupChildWriter(
                child,
                relayCore,
                activeChildren,
                receiverControls,
                terminalAcksForwarded,
            )
            startGroupChildControlReader(
                child,
                relayCore,
                activeChildren,
                lastChildHeartbeats,
                receiverControls,
                terminalAcksForwarded,
            )
        }
        startGroupUpstreamControlWriter(
            source,
            relayCore,
            activeChildren,
            lastChildHeartbeats,
            receiverControls,
            terminalAcksForwarded,
            transport,
        )

        val sourceHelloRecord = source.reader.read()
        require(sourceHelloRecord.type == SecureRecordType.MESH_ENVELOPE) {
            "Group source did not send a mesh stream header"
        }
        val headerEnvelope = MeshEnvelopeCodec.decode(sourceHelloRecord.payload)
        require(headerEnvelope.sourceNodeId == authenticatedSourceNodeId) {
            "Group mesh source does not match the authenticated endpoint"
        }
        require(headerEnvelope.sourceNodeId != localNodeId) { "Relay received its own group stream" }
        require(
            EndToEndContentProtocol.inspect(headerEnvelope.payload).type == EndToEndContentType.STREAM_HEADER,
        ) { "First group content record is not a stream header" }
        val headerResult = relayCore.receive(source.peerNodeId, headerEnvelope)
        require(headerResult.rejection == null) {
            "Relay rejected the group stream header: ${headerResult.rejection}"
        }

        var receivedPackets = 0L
        onStatus(
            "Relaying group-encrypted mesh ${headerEnvelope.streamId} to ${children.size} receivers " +
                "without decrypting or re-encoding it; TTL ${headerEnvelope.ttl - 1}.",
        )
        while (running.get()) {
            val record = source.reader.read()
            require(record.type == SecureRecordType.MESH_ENVELOPE) {
                "Unexpected group source record ${record.type}"
            }
            val envelope = MeshEnvelopeCodec.decode(record.payload)
            require(envelope.streamId == headerEnvelope.streamId) { "Group mesh stream ID changed" }
            require(envelope.sourceNodeId == headerEnvelope.sourceNodeId) { "Group mesh source changed" }
            val metadata = EndToEndContentProtocol.inspect(envelope.payload)
            require(
                metadata.type == EndToEndContentType.MEDIA_PACKET ||
                    metadata.type == EndToEndContentType.STREAM_STATE,
            ) { "Unexpected group content ${metadata.type}" }
            val result = relayCore.receive(source.peerNodeId, envelope)
            if (result.rejection == RelayRejection.NO_CHILDREN) {
                error("Every group receiver disconnected")
            }
            require(result.rejection == null) { "Relay rejected group packet: ${result.rejection}" }
            receivedPackets++
            if (envelope.flags and MeshEnvelope.FLAG_TERMINAL != 0) {
                activeChildren.forEach { (nodeId, child) ->
                    flushTerminalEnvelope(child, relayCore, nodeId)
                }
                val acknowledged = terminalAcksForwarded.await(
                    TERMINAL_ACK_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS,
                )
                onStatus(
                    if (acknowledged) {
                        "Relay forwarded every authenticated group terminal acknowledgement."
                    } else {
                        "Relay flushed the group terminal state; a receiver acknowledgement timed out."
                    },
                )
                return
            }
            if (receivedPackets % STATUS_INTERVAL_PACKETS == 0L) {
                val childMetrics = activeChildren.keys.sortedBy(NodeId::toString).joinToString { child ->
                    "${child.shortLabel()} q${relayCore.queueDepth(child)}/$queueCapacity backlog ${relayCore.backlogAgeMs(child)} ms" +
                        if (thinDiscardable) " t${relayCore.thinnedFrames(child)}" else ""
                }
                onStatus("Group relay $receivedPackets packets | $childMetrics | TTL ${envelope.ttl - 1}")
            }
        }
    }

    private fun proxyGroupReceiverHandshake(
        source: SecureBluetoothSession,
        child: SecureBluetoothSession,
    ): NodeId {
        writeGroupControl(
            source,
            GroupRelayControlMessage(GroupRelayControlType.JOIN, child.peerNodeId),
        )
        val sourceHello = readGroupControl(source, GroupRelayControlType.SOURCE_HELLO, child.peerNodeId)
        val decodedSource = HandshakeHelloCodec.decode(sourceHello.payload)
        require(decodedSource.role == HandshakeRole.INITIATOR) { "Expected group source initiator" }
        child.writer.write(SecureRecordType.END_TO_END_HANDSHAKE, sourceHello.payload)
        child.writer.flush()

        val receiverHello = readSkippingBwf1(child.reader)
        require(receiverHello.type == SecureRecordType.END_TO_END_HANDSHAKE) {
            "Group receiver did not answer the endpoint handshake"
        }
        val decodedReceiver = HandshakeHelloCodec.decode(receiverHello.payload)
        require(decodedReceiver.nodeId == child.peerNodeId) { "Group receiver identity changed" }
        writeGroupControl(
            source,
            GroupRelayControlMessage(
                GroupRelayControlType.RECEIVER_HELLO,
                child.peerNodeId,
                receiverHello.payload,
            ),
        )

        val wrappedKey = readGroupControl(source, GroupRelayControlType.WRAPPED_KEY, child.peerNodeId)
        child.writer.write(SecureRecordType.END_TO_END_CONTENT, wrappedKey.payload)
        child.writer.flush()
        onStatus("Group receiver ${child.peerNodeId.shortLabel()} received its endpoint-wrapped key.")
        return decodedSource.nodeId
    }

    private fun startGroupChildWriter(
        child: SecureBluetoothSession,
        relayCore: MeshRelayCore,
        activeChildren: ConcurrentHashMap<NodeId, SecureBluetoothSession>,
        receiverControls: BoundedControlQueue<GroupRelayControlMessage>,
        terminalAcksForwarded: CountDownLatch,
    ) {
        groupWriterThreads += thread(name = "BluetoothGroupRelayWriter-${child.peerNodeId.shortLabel()}") {
            try {
                while (running.get() && activeChildren.containsKey(child.peerNodeId)) {
                    val envelope = relayCore.take(child.peerNodeId) ?: break
                    child.writer.write(SecureRecordType.MESH_ENVELOPE, MeshEnvelopeCodec.encode(envelope))
                    child.writer.flush()
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                removeGroupChild(
                    child,
                    relayCore,
                    activeChildren,
                    receiverControls,
                    terminalAcksForwarded,
                    "writer",
                    error,
                )
            }
        }
    }

    private fun startGroupChildControlReader(
        child: SecureBluetoothSession,
        relayCore: MeshRelayCore,
        activeChildren: ConcurrentHashMap<NodeId, SecureBluetoothSession>,
        lastChildHeartbeats: ConcurrentHashMap<NodeId, AtomicLong>,
        receiverControls: BoundedControlQueue<GroupRelayControlMessage>,
        terminalAcksForwarded: CountDownLatch,
    ) {
        groupControlThreads += thread(name = "BluetoothGroupRelayControl-${child.peerNodeId.shortLabel()}") {
            try {
                while (running.get() && activeChildren.containsKey(child.peerNodeId)) {
                    val record = readSkippingBwf1(child.reader)
                    when (record.type) {
                        SecureRecordType.LINK_HEARTBEAT ->
                            SecureLinkControlProtocol.requireHeartbeat(record)
                        SecureRecordType.END_TO_END_CONTENT -> {
                            val type = EndToEndContentProtocol.inspect(record.payload).type
                            require(
                                type == EndToEndContentType.KEY_FRAME_REQUEST ||
                                    type == EndToEndContentType.STREAM_END_ACK,
                            ) { "Unexpected group receiver control" }
                            val message = GroupRelayControlMessage(
                                GroupRelayControlType.RECEIVER_CONTROL,
                                child.peerNodeId,
                                record.payload,
                            )
                            if (type == EndToEndContentType.STREAM_END_ACK) {
                                // The terminal ack must reach the source: never refused.
                                receiverControls.offerMust(message)
                            } else {
                                // One pending keyframe request per child; later ones are absorbed.
                                receiverControls.offerKeyed(child.peerNodeId to "key-frame-request", message)
                            }
                            if (type == EndToEndContentType.STREAM_END_ACK) return@thread
                        }
                        else -> error("Unexpected group child record ${record.type}")
                    }
                    lastChildHeartbeats[child.peerNodeId]?.set(System.nanoTime())
                }
            } catch (error: Exception) {
                removeGroupChild(
                    child,
                    relayCore,
                    activeChildren,
                    receiverControls,
                    terminalAcksForwarded,
                    "control",
                    error,
                )
            }
        }
    }

    private fun startGroupUpstreamControlWriter(
        source: SecureBluetoothSession,
        relayCore: MeshRelayCore,
        activeChildren: ConcurrentHashMap<NodeId, SecureBluetoothSession>,
        lastChildHeartbeats: ConcurrentHashMap<NodeId, AtomicLong>,
        receiverControls: BoundedControlQueue<GroupRelayControlMessage>,
        terminalAcksForwarded: CountDownLatch,
        transport: BluetoothTransportOption,
    ) {
        upstreamControlThread = thread(name = "BluetoothGroupRelayUpstreamControl") {
            try {
                while (running.get()) {
                    val control = receiverControls.poll(LINK_HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    control?.let {
                        source.writer.write(SecureRecordType.GROUP_CONTROL, GroupRelayControlCodec.encode(control))
                        if (control.type == GroupRelayControlType.RECEIVER_CONTROL &&
                            EndToEndContentProtocol.inspect(control.payload).type ==
                            EndToEndContentType.STREAM_END_ACK
                        ) {
                            terminalAcksForwarded.countDown()
                        }
                    }
                    val periodicHeartbeat = usesPeriodicReceiverHeartbeat(transport)
                    if (periodicHeartbeat) SecureLinkControlProtocol.writeHeartbeat(source.writer)
                    if (control != null || periodicHeartbeat) source.writer.flush()
                    if (periodicHeartbeat) {
                        val now = System.nanoTime()
                        lastChildHeartbeats.entries.toList().forEach { (nodeId, heartbeat) ->
                            if (now - heartbeat.get() >= LINK_HEARTBEAT_TIMEOUT_NS) {
                                activeChildren[nodeId]?.let { child ->
                                    removeGroupChild(
                                        child,
                                        relayCore,
                                        activeChildren,
                                        receiverControls,
                                        terminalAcksForwarded,
                                        "heartbeat",
                                        IllegalStateException("Immediate child heartbeat timed out"),
                                    )
                                }
                            }
                        }
                    }
                    if (activeChildren.isEmpty()) error("Every group receiver disconnected")
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                failLink("Group relay upstream control stopped: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun removeGroupChild(
        child: SecureBluetoothSession,
        relayCore: MeshRelayCore,
        activeChildren: ConcurrentHashMap<NodeId, SecureBluetoothSession>,
        receiverControls: BoundedControlQueue<GroupRelayControlMessage>,
        terminalAcksForwarded: CountDownLatch,
        component: String,
        error: Exception,
    ) {
        if (!activeChildren.remove(child.peerNodeId, child)) return
        relayCore.removeChild(child.peerNodeId)
        child.close()
        receiverControls.offerMust(
            GroupRelayControlMessage(GroupRelayControlType.RECEIVER_LEFT, child.peerNodeId),
        )
        terminalAcksForwarded.countDown()
        onStatus(
            "Removed group receiver ${child.peerNodeId.shortLabel()} after $component stopped: " +
                (error.message ?: error.javaClass.simpleName),
        )
        if (activeChildren.isEmpty()) failLink("Every group receiver disconnected")
    }

    private fun readGroupControl(
        session: SecureBluetoothSession,
        expectedType: GroupRelayControlType,
        expectedReceiver: NodeId,
    ): GroupRelayControlMessage {
        val record = session.reader.read()
        require(record.type == SecureRecordType.GROUP_CONTROL) { "Expected group relay control" }
        return GroupRelayControlCodec.decode(record.payload).also {
            require(it.type == expectedType) { "Expected $expectedType group control" }
            require(it.receiverNodeId == expectedReceiver) { "Group control receiver changed" }
        }
    }

    private fun writeGroupControl(
        session: SecureBluetoothSession,
        message: GroupRelayControlMessage,
    ) {
        session.writer.write(SecureRecordType.GROUP_CONTROL, GroupRelayControlCodec.encode(message))
        session.writer.flush()
    }

    private fun decodeSourceEnvelope(record: SecureRecord, sourceNodeId: NodeId): MeshEnvelope {
        require(record.type == SecureRecordType.MESH_ENVELOPE) {
            "Source did not send the serialized mesh envelope"
        }
        val envelope = MeshEnvelopeCodec.decode(record.payload)
        require(envelope.sourceNodeId == sourceNodeId) {
            "Mesh source identity does not match the endpoint handshake"
        }
        val metadata = EndToEndContentProtocol.inspect(envelope.payload)
        val terminalFlag = envelope.flags and MeshEnvelope.FLAG_TERMINAL
        require(terminalFlag == 0 || metadata.type == EndToEndContentType.STREAM_STATE) {
            "Only stream state can be terminal"
        }
        val expectedFlags = meshPacketFlags(metadata.flags) or terminalFlag or if (metadata.type == EndToEndContentType.STREAM_STATE) {
            MeshEnvelope.FLAG_CONTROL
        } else {
            0
        }
        require(envelope.flags == expectedFlags) { "Mesh flags do not match endpoint metadata" }
        return envelope
    }

    private fun flushTerminalEnvelope(
        child: SecureBluetoothSession,
        relayCore: MeshRelayCore,
        childNodeId: NodeId,
    ) {
        val deadlineNs = System.nanoTime() + TERMINAL_FLUSH_TIMEOUT_NS
        while (relayCore.queueDepth(childNodeId) > 0 && System.nanoTime() < deadlineNs) {
            Thread.sleep(5)
        }
        require(relayCore.queueDepth(childNodeId) == 0) { "Terminal relay queue did not drain" }
        child.writer.flush()
    }

    private fun startChildWriter(child: SecureBluetoothSession, relayCore: MeshRelayCore) {
        writerThread = thread(name = "BluetoothRelayChildWriter") {
            try {
                while (running.get()) {
                    val envelope = relayCore.take(child.peerNodeId) ?: break
                    child.writer.write(SecureRecordType.MESH_ENVELOPE, MeshEnvelopeCodec.encode(envelope))
                    child.writer.flush()
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                failLink("Relay child link stopped: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun startChildControlReader(
        child: SecureBluetoothSession,
        keyFrameRequests: ArrayBlockingQueue<ByteArray>,
        lastChildHeartbeatNs: AtomicLong,
    ) {
        childControlThread = thread(name = "BluetoothRelayChildControl") {
            try {
                while (running.get()) {
                    val downstream = readSkippingBwf1(child.reader)
                    when (downstream.type) {
                        SecureRecordType.LINK_HEARTBEAT ->
                            SecureLinkControlProtocol.requireHeartbeat(downstream)
                        SecureRecordType.END_TO_END_CONTENT -> {
                            val type = EndToEndContentProtocol.inspect(downstream.payload).type
                            require(
                                type == EndToEndContentType.KEY_FRAME_REQUEST ||
                                    type == EndToEndContentType.STREAM_END_ACK,
                            ) { "Unexpected end-to-end child control" }
                            if (!keyFrameRequests.offer(downstream.payload)) {
                                keyFrameRequests.poll()
                                keyFrameRequests.offer(downstream.payload)
                            }
                            if (type == EndToEndContentType.STREAM_END_ACK) return@thread
                        }
                        else -> error("Unexpected child control record ${downstream.type}")
                    }
                    lastChildHeartbeatNs.set(System.nanoTime())
                }
            } catch (error: Exception) {
                failLink("Relay child control stopped: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun startUpstreamControlWriter(
        source: SecureBluetoothSession,
        keyFrameRequests: ArrayBlockingQueue<ByteArray>,
        lastChildHeartbeatNs: AtomicLong,
        terminalAckForwarded: CountDownLatch,
        transport: BluetoothTransportOption,
    ) {
        upstreamControlThread = thread(name = "BluetoothRelayUpstreamControl") {
            try {
                while (running.get()) {
                    val request = keyFrameRequests.poll(LINK_HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    if (request != null) {
                        source.writer.write(SecureRecordType.END_TO_END_CONTENT, request)
                        if (EndToEndContentProtocol.inspect(request).type == EndToEndContentType.STREAM_END_ACK) {
                            terminalAckForwarded.countDown()
                        }
                    }
                    val periodicHeartbeat = usesPeriodicReceiverHeartbeat(transport)
                    if (periodicHeartbeat) SecureLinkControlProtocol.writeHeartbeat(source.writer)
                    if (request != null || periodicHeartbeat) source.writer.flush()
                    if (periodicHeartbeat &&
                        System.nanoTime() - lastChildHeartbeatNs.get() >= LINK_HEARTBEAT_TIMEOUT_NS
                    ) {
                        error("Immediate child heartbeat timed out")
                    }
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                failLink("Relay upstream control stopped: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun failLink(message: String) {
        if (!running.getAndSet(false)) return
        onStatus(message)
        closeLinks()
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        closeResources()
        workerThread?.interrupt()
    }

    private fun closeResources() {
        closeLinks()
        downstreamSessions.forEach { runCatching { it.close() } }
        downstreamSessions.clear()
        upstreamSession?.close()
        upstreamSession = null
        downstreamSession?.close()
        downstreamSession = null
        upstreamControlServer?.close()
        upstreamControlServer = null
        downstreamControlConnection?.close()
        downstreamControlConnection = null
        downstreamGattConnections.forEach { runCatching { it.close() } }
        downstreamGattConnections.clear()
        upstreamDiscoveryAdvertiser?.close()
        upstreamDiscoveryAdvertiser = null
        upstreamGattConnection?.close()
        upstreamGattConnection = null
        downstreamGattConnection?.close()
        downstreamGattConnection = null
        upstreamGattServer?.close()
        upstreamGattServer = null
        writerThread?.interrupt()
        writerThread = null
        childControlThread?.interrupt()
        childControlThread = null
        upstreamControlThread?.interrupt()
        upstreamControlThread = null
        groupWriterThreads.forEach(Thread::interrupt)
        groupWriterThreads.clear()
        groupControlThreads.forEach(Thread::interrupt)
        groupControlThreads.clear()
    }

    private fun closeLinks() {
        runCatching { upstreamSocket?.close() }
        upstreamSocket = null
        runCatching { downstreamSocket?.close() }
        downstreamSocket = null
        downstreamSockets.forEach { runCatching { it.close() } }
        downstreamSockets.clear()
        runCatching { upstreamServerSocket?.close() }
        upstreamServerSocket = null
        upstreamGattConnection?.close()
        downstreamGattConnection?.close()
    }

    private companion object {
        const val BUFFER_BYTES = 32 * 1024
        const val RELAY_QUEUE_CAPACITY = 40
        const val STATUS_INTERVAL_PACKETS = 75L
        const val CONNECTION_TIMEOUT_SECONDS = 30L
        const val LINK_HEARTBEAT_INTERVAL_MS = 2_000L
        const val LINK_HEARTBEAT_TIMEOUT_NS = 8_000_000_000L
        const val TERMINAL_FLUSH_TIMEOUT_NS = 1_000_000_000L
        const val TERMINAL_ACK_TIMEOUT_MS = 2_000L
    }
}

private val BluetoothTransportOption.discoveryFlag: Int
    get() = when (this) {
        BluetoothTransportOption.CLASSIC_RFCOMM -> DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT
        BluetoothTransportOption.BLE_GATT -> DiscoveryAnnouncement.FLAG_GATT_TRANSPORT
        BluetoothTransportOption.BLE_L2CAP -> 0
    }
