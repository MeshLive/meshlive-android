package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class BluetoothVideoSender(
    queueCapacity: Int,
    private val onStatus: (String) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onRemoteKeyFrameNeeded: () -> Unit = {},
    /** A keyframe that bypasses the recovery back-off (Experimental [RelayJoinKeyFrame]); defaults to the normal request. */
    private val onRemoteJoinKeyFrame: () -> Unit = onRemoteKeyFrameNeeded,
    private val thermalStatusProvider: () -> Int = { PowerManager.THERMAL_STATUS_NONE },
    private val debugDirectGattHeartbeat: Boolean = true,
    private val debugGattWriteTiming: Boolean = false,
    private val onLinkSample: (LinkRateSample) -> Unit = {},
) : Closeable {
    private data class Packet(
        val flags: Int,
        val timeUs: Long,
        val bytes: ByteArray,
        val queuedAtNs: Long,
        val streamState: StreamLifecycleState? = null,
    ) {
        val isAudio: Boolean
            get() = flags and StreamProtocol.FLAG_AUDIO != 0
        val isKeyFrame: Boolean
            get() = flags and StreamProtocol.FLAG_KEY_FRAME != 0
        val isDiscardable: Boolean
            get() = flags and StreamProtocol.FLAG_DISCARDABLE != 0
    }

    /**
     * Experimental temporal layering (off by default): skip verified-discardable frames while this
     * link's queue is [TemporalLayerThinning.THIN_BACKLOG_MS] behind. Off: nothing is ever skipped.
     */
    private val thinDiscardable = DebugTunables.experimentalTemporalLayers
    private val thinnedFrames = AtomicLong()

    /**
     * Experimental bounded media age (off by default, see BoundedMediaAge): this link's queue sheds
     * stale video codec-safely once media would be older than about 2 s at the child. Off: nothing is
     * shed. The queue-age measurements below are logged in both arms.
     */
    private val boundedAge = BoundedMediaAge.enabled
    private val shedder = MediaAgeShedder(BoundedMediaAge.STREAM_SHED_COOLDOWN_NS)
    private val shedFramesTotal = AtomicLong()
    /**
     * Bounded media age only: enqueue, shed and the writer's take share this monitor, so a shed is
     * atomic for the writer (it can never write a frame whose reference was just shed). Unused while
     * the experiment is off: the writer keeps its original lock-free take.
     */
    private val queueLock = Object()
    private var lastShedKeyFrameRequestNs = 0L
    /** The child's reported added playout delay on this link (hop-local). */
    @Volatile private var childAddedDelayMs = 0
    private val videoQueueWaitMax = WindowMax()
    private val audioQueueWaitMax = WindowMax()

    /** Nominal depth shown in status; the queue itself never drops (user direction 2026-10-07). */
    private val normalQueueCapacity = queueCapacity
    // Unbounded and ordered: a slow link builds latency (absorbed by the viewer's adaptive playout
    // and answered by rate control), never loss. The previous bounded queue dropped all queued
    // video on overflow and waited for a recovery keyframe, the largest frame of the stream.
    private val packets = LinkedBlockingQueue<Packet>()
    @Volatile private var largeFrameGraceUntilNs = 0L
    /** Large-frame timing for samples (diagnostic) and the experimental burst-aware drain. */
    private val burstDrain = BurstDrainTracker()
    private val burstAwareDrain = DebugTunables.experimentalBurstDrain
    /** The last large video packet queued; its write completion ends the burst's blocking write. */
    @Volatile private var lastLargePacket: Packet? = null
    @Volatile private var measuredBytesPerSecond = 0L
    @Volatile private var typicalVideoBytes = 0L
    private val offeredWindowBytes = AtomicLong()
    private val running = AtomicBoolean(false)
    /** Wider reach: the native streams this sender dialled (closed by closeRawLink and close). */
    @Volatile private var wifiConnection: WifiNativeConnection? = null
    private val streamPaused = AtomicBoolean(false)
    private val ending = AtomicBoolean(false)
    private val withdrawingForCapacity = AtomicBoolean(false)
    private val routeWithdrawalAcknowledgement = CountDownLatch(1)
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var bleControlConnection: BleL2capControlConnection? = null
    @Volatile private var gattConnection: GattVideoConnection? = null
    @Volatile private var secureSession: SecureBluetoothSession? = null
    @Volatile private var endToEndSession: EndToEndVideoSession? = null
    @Volatile private var broadcastGroupSession: BroadcastGroupSourceSession? = null
    @Volatile private var workerThread: Thread? = null
    @Volatile private var controlThread: Thread? = null
    @Volatile private var flushThread: Thread? = null
    @Volatile private var lastHeartbeatReceivedNs = 0L
    @Volatile private var linkLoad: HopSendLoad? = null
    @Volatile private var periodicChildHeartbeatExpected = false
    @Volatile private var hopLink: HopLink? = null
    @Volatile private var targetStreamKbps = 0

    /**
     * What [noteStreamPhyLoss] needs once the link is gone. Created when the GATT link opens (the peer is then known only by
     * its advertised prefix, [node] null for a rotating pseudonym) and completed when the hop peer authenticated.
     */
    private class PhyLossProbe(
        val address: String,
        val openedAtMs: Long,
        @Volatile var requestedTwoM: Boolean,
        val gatt: GattVideoConnection?,
        @Volatile var node: String?,
    ) {
        @Volatile var authenticatedAtMs = 0L
        @Volatile var peerRelays = false
    }

    @Volatile private var phyLossProbe: PhyLossProbe? = null

    /** Elapsed-realtime ms when the stream became connected (end-to-end handshake done); 0 while still setting up. */
    @Volatile private var streamConnectedAtMs = 0L

    /** test_phy_move_skip_backlog (default on for fixed-rate streams, PhyMoveSkip.enabled): arriving non-key video is dropped after a skip left no usable keyframe queued. */
    private val moveSkipAwaitKeyFrame = PhyMoveSkip.AwaitKeyFrame()
    private val moveSkipRecords = AtomicLong()

    /** The exception that ended the link (worker or control thread), read by [noteStreamPhyLoss]. */
    @Volatile private var linkFailure: Throwable? = null
    @Volatile private var currentThermalStatus = PowerManager.THERMAL_STATUS_NONE
    @Volatile private var endAcknowledgements: CountDownLatch? = null
    private val acknowledgedGroupReceivers = ConcurrentHashMap.newKeySet<NodeId>()
    private val queuedBytes = AtomicLong()
    private val flushLatency = RollingLatencyStats()
    private val flushPending = AtomicBoolean(false)
    private val bytesPendingFlush = AtomicLong()
    private val flushSignal = Object()
    @Volatile private var peerMeasuredKbps = 0
    @Volatile private var childLateUntilNs = 0L
    private val childLateness = PeerLatenessTrend()
    // Experimental stream delivery reports (version 3; both phones must run this build): what the
    // child received of what this link wrote. Used only under this phone's own switch.
    private val childDeliveryReports = DebugTunables.streamDeliveryReports
    // The pacer seed policy (PacerSeedPolicy.kt): on-time reports, the raise, the Experimental drain guard and join probe.
    private val pacerConfig = PacerQueueConfig.current()
    private val joinProbeSeed = pacerConfig.joinProbeSeed
    @Volatile private var childDelivery = PeerDeliveryEstimate()
    private val pacer = LinkPacer()
    @Volatile private var linkTransport: BluetoothTransportOption? = null
    private var debugIntegrityEnabled = false
    private var debugStageTrace: DebugStageTrace? = null

    /** Experimental audio-only live stream (Design AL): non-null while this child is sent a radio stream. */
    @Volatile private var audioLive: AudioLiveSendConfig? = null
    @Volatile private var audioSendQueue: AudioLiveSendQueue<Packet>? = null

    @SuppressLint("MissingPermission")
    fun connect(
        context: Context,
        device: BluetoothDevice,
        codec: VideoCodec,
        settings: VideoSettings,
        transport: BluetoothTransportOption,
        rotationDegrees: Int,
        expectedPeerNodeIdPrefix: ByteArray?,
        includeAudio: Boolean = true,
        phyPreference: BlePhyPreference = BlePhyPreference.TWO_M,
        corruptFirstVideoRecordForTest: Boolean = false,
        proveVideoIntegrityForTest: Boolean = false,
        ioConfig: VideoStreamIoConfig = VideoStreamIoConfig.DEFAULT,
        debugTraceSession: String? = null,
        /**
         * Rotating IDs (phase 1b): the viewer request this connection answers, when it is a version 5 request.
         * Its identity is then proven by the listener's link opening (role 2) instead of the prefix.
         */
        selectedRotatingRequest: DiscoveryAnnouncement? = null,
        /** Experimental audio-only live stream: no video codec, header BTL1, every record signed by the source. */
        audioLiveConfig: AudioLiveSendConfig? = null,
        /** Wider reach (A8): dials a Wi-Fi-only viewer; the returned streams replace the Bluetooth socket. */
        wifiDial: (() -> WifiNativeConnection)? = null,
        onConnected: () -> Unit,
    ) {
        audioLive = audioLiveConfig
        audioSendQueue = audioLiveConfig?.let { config ->
            AudioLiveSendQueue(
                packets,
                sheddable = { it.streamState == null && AudioLiveShed.isSourceRecordSheddable(it.flags) },
                queuedAtNs = { it.queuedAtNs },
                sizeOf = { it.bytes.size },
                ageBound = config.ageBound,
            )
        }
        debugIntegrityEnabled = BuildConfig.DEBUG && proveVideoIntegrityForTest
        linkTransport = transport
        debugStageTrace = DebugStageTrace.startIfRequested(debugTraceSession, "broadcast_transport")
        phyLossProbe = null
        linkFailure = null
        streamConnectedAtMs = 0L
        moveSkipAwaitKeyFrame.reset()
        workerThread = thread(name = "BluetoothConnect") {
            try {
                val identity = AndroidNodeIdentityStore().getOrCreate()
                val wifi = wifiDial?.invoke()
                wifiConnection = wifi
                if (wifi != null && ending.get()) {
                    runCatching { wifi.close() }
                    return@thread
                }
                val capabilities = LocalNodeCapabilities.forTransport(transport)
                var classicSession: SecureBluetoothSession? = null
                // Adaptive PHY memory: 1M from the start for a hop peer remembered bad on 2M.
                val linkPhy = expectedPeerNodeIdPrefix
                    ?.takeIf { transport != BluetoothTransportOption.CLASSIC_RFCOMM }
                    ?.let { AdaptivePhyMemory.phyFor(transport, it, phyPreference, "stream") }
                    ?: phyPreference
                // Experimental test_phy_direction_split: a peer remembered with one failing 2M direction starts split (GATT).
                val linkSplit = expectedPeerNodeIdPrefix
                    ?.takeIf { transport == BluetoothTransportOption.BLE_GATT && linkPhy == phyPreference }
                    ?.let { AdaptivePhyMemory.splitFor(transport, AdaptivePhyMemory.prefixLabel(it), "stream") }
                val connectedSocket = if (wifi != null) null else when (transport) {
                    BluetoothTransportOption.CLASSIC_RFCOMM -> {
                        val adapter = requireNotNull(context.getSystemService(BluetoothManager::class.java).adapter) {
                            "Bluetooth is unavailable"
                        }
                        onStatus("Resolving and authenticating the Classic receiver...")
                        val connected = ClassicConnector.connect(
                            context,
                            adapter,
                            device,
                            expectedPeerNodeIdPrefix,
                            ClassicRole.STREAM_SENDER,
                            verifyLinkPrefix = true,
                            // Inside the broadcaster's 12 s connection timeout, so a failure reports its reason.
                            totalBudgetMs = CLASSIC_STREAM_SENDER_BUDGET_MS,
                            allowInquiry = false,
                            isCancelled = { ending.get() || Thread.currentThread().isInterrupted },
                            onSocket = { socket = it },
                            handshake = { candidateSocket ->
                                SecureBluetoothHandshake.initiate(
                                    BufferedInputStream(candidateSocket.inputStream, ioConfig.bufferBytes),
                                    BufferedOutputStream(candidateSocket.mediaOutputStream(), ioConfig.bufferBytes),
                                    identity,
                                    capabilities,
                                    raiseTag = true,
                                )
                            },
                            verify = { candidateSession ->
                                require(candidateSession.peerCapabilities.transportMask and transport.capabilityMask != 0) {
                                    "Peer did not authenticate support for ${transport.shortLabel}"
                                }
                            },
                            onStatus = onStatus,
                        )
                        classicSession = connected.session
                        secureSession = connected.session
                        connected.socket
                    }
                    BluetoothTransportOption.BLE_L2CAP -> {
                        onStatus("Reading the receiver's BLE video channel...")
                        BleCocClient.connect(
                            context,
                            device,
                            onStatus,
                            linkPhy,
                            "sender-connect",
                        ) { bleControlConnection = it }
                    }
                    BluetoothTransportOption.BLE_GATT -> null
                }
                socket = connectedSocket
                if (wifi != null) {
                    onStatus("Connecting over Wi-Fi…")
                } else {
                    onStatus("Connecting to ${device.name ?: device.address} over ${transport.shortLabel}…")
                    // CoC sockets come back connected (BleCocClient).
                    if (transport != BluetoothTransportOption.CLASSIC_RFCOMM && connectedSocket?.isConnected == false) {
                        connectedSocket.connect()
                    }
                    if (transport == BluetoothTransportOption.BLE_L2CAP) {
                        onStatus("Keeping the GATT control connection active for CoC link priority.")
                    }
                }

                val gattPriority = DebugTunables.sourceGattPriority
                    ?: if (audioLiveConfig != null && AudioLiveTunables.balancedLinkPriority) {
                        BleConnectionPriority.BALANCED // Experimental arm test_audio_live_link_priority=balanced
                    } else {
                        gattVideoPriority(settings.bitRate / 1_000)
                    }
                // Experimental test_phy_after_auth (default off): the GATT link opens on 1M and asks for 2M only once the hop
                // peer is authenticated, so the memory is read by the authenticated node (a rotating advertised prefix cannot
                // be looked up) and the hop handshake runs on 1M.
                val deferPhy = DebugTunables.phyAfterAuth && transport == BluetoothTransportOption.BLE_GATT
                val activeGattConnection = if (wifi == null && transport == BluetoothTransportOption.BLE_GATT) {
                    GattVideoClient.open(
                        context,
                        device,
                        linkPhy,
                        gattPriority,
                        onStatus,
                        deferPhy = deferPhy,
                        onPhyMove = ::skipBacklogAfterPhyMove,
                        phySplit = linkSplit,
                        onWriteTiming = if (BuildConfig.DEBUG && debugGattWriteTiming) {
                            { summary -> android.util.Log.i("BluetoothVideo", summary) }
                        } else {
                            null
                        },
                    ).also {
                        gattConnection = it
                    }
                } else {
                    null
                }
                if (activeGattConnection != null) {
                    phyLossProbe = PhyLossProbe(
                        address = device.address,
                        openedAtMs = SystemClock.elapsedRealtime(),
                        requestedTwoM = streamRequestedTwoM(linkPhy, deferPhy, deferredRequested = false),
                        gatt = activeGattConnection,
                        node = expectedPeerNodeIdPrefix?.takeIf { selectedRotatingRequest == null }
                            ?.let(AdaptivePhyMemory::prefixLabel),
                    )
                }
                val rawInput = wifi?.input ?: activeGattConnection?.inputStream ?: connectedSocket?.inputStream
                    ?: error("Bluetooth input is unavailable")
                val rawOutput = wifi?.output ?: activeGattConnection?.outputStream ?: connectedSocket?.mediaOutputStream()
                    ?: error("Bluetooth output is unavailable")

                onStatus("Authenticating the ${transport.shortLabel} link…")
                // Rotating IDs, phase 1b: an LE stream hop signs link bit 3 and exchanges BDO1 (this connector
                // sends its pinned role-1 entries). Off: linkCapabilities is capabilities, nothing is exchanged.
                val rotatingOn = RotatingDiscoveryFeature.enabled
                val linkCapabilities = StreamHopOpening.linkCapabilities(capabilities, rotatingOn, transport)
                val session = classicSession ?: SecureBluetoothHandshake.initiate(
                    BufferedInputStream(rawInput, ioConfig.bufferBytes),
                    BufferedOutputStream(rawOutput, ioConfig.bufferBytes),
                    identity,
                    linkCapabilities,
                    raiseTag = true,
                )
                require(session.peerCapabilities.transportMask and transport.capabilityMask != 0) {
                    "Peer did not authenticate support for ${transport.shortLabel}"
                }
                val rotatingSelection = selectedRotatingRequest?.takeIf { it.rotating }
                if (rotatingOn && transport != BluetoothTransportOption.CLASSIC_RFCOMM) {
                    val opening = LinkOpeningExchange.exchange(
                        session,
                        linkCapabilities,
                        RotatingDiscovery.pinned(RotatingDiscoveryIds.ROLE_STREAM_OFFER),
                    )
                    if (rotatingSelection != null) {
                        val result = StreamHopOpening.check(
                            rotatingSelection,
                            RotatingDiscoveryIds.ROLE_VIEWER_REQUEST,
                            session.peerNodeId,
                            opening,
                        )
                        android.util.Log.i(
                            "BluetoothVideo",
                            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotid_open: role=viewer | result=$result",
                        )
                        require(result == DiscoverySelectionCheck.Result.PASS) {
                            "Peer identity changed between discovery and connection"
                        }
                    }
                } else {
                    require(rotatingSelection == null) { "A rotating viewer request needs the rotating-ID experiment" }
                }
                if (expectedPeerNodeIdPrefix != null && rotatingSelection == null) {
                    require(
                        session.peerNodeId.toByteArray()
                            .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
                            .contentEquals(expectedPeerNodeIdPrefix),
                    ) { "Peer identity changed between discovery and connection" }
                }
                secureSession = session
                if (wifi == null) AdaptivePhyMemory.bind(device.address, transport, session.peerNodeId.shortLabel())
                wifi?.admitted?.invoke()   // [AM 12] F-3: the BMH3/BMH2 handshake on these streams succeeded
                val sendLoad = HopSendLoad { NodeLinkLoad.open(transport, LinkDirection.SEND) }
                linkLoad = sendLoad
                val hop = HopLink(
                    role = HopCapsPlan.HopRole.STREAM_PARENT,
                    session = session,
                    closeRaw = ::closeRawLink,
                    anyLocalSwitchOn = WifiSwitchHub::anyOn,
                    log = WifiLog::emit,
                    owner = sourceHopOwner(sendLoad),
                    newAgent = ::newHopAgent,
                    carrier0IsBluetooth = session.peerCapabilities.maxChildren <= 1 && wifi == null,
                )
                hopLink = hop
                targetStreamKbps = settings.bitRate / 1_000
                phyLossProbe?.let { probe ->
                    probe.node = session.peerNodeId.shortLabel()
                    probe.peerRelays = session.peerCapabilities.maxChildren > 0
                    probe.authenticatedAtMs = SystemClock.elapsedRealtime()
                }
                if (deferPhy && activeGattConnection != null) {
                    val wanted = phyAfterAuth(linkPhy, AdaptivePhyMemory.isBad(transport, session.peerNodeId.shortLabel()))
                    val requested = wanted == BlePhyPreference.TWO_M && activeGattConnection.requestPhy(wanted)
                    phyLossProbe?.requestedTwoM = streamRequestedTwoM(linkPhy, deferPhy = true, deferredRequested = requested)
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK phy_after_auth: link=stream | node=${session.peerNodeId.shortLabel()} | wanted=${wanted.label} | " +
                            "requested=$requested | remembered=${wanted != linkPhy}",
                    )
                }
                // test_coc_phy_after_connect (default off): PHY only now, before the end-to-end handshake.
                bleControlConnection?.requestPhyAfterConnect("stream")

                onStatus("Authenticating the final video receiver…")
                // SEC-B2: only the DEBUG manual relay may be a group hop (a peer cannot claim it to relay the handshake).
                StreamHopPeerBinding.requireGroupAllowed(
                    "source",
                    rotatingSelection != null,
                    session.peerCapabilities.maxChildren,
                    "Peer identity changed between discovery and connection",
                )
                val groupSession = if (session.peerCapabilities.maxChildren > 1) {
                    require(audioLiveConfig == null) { "A group relay cannot take an audio-only stream" }
                    BroadcastGroupHandshake.initiate(
                        context,
                        session,
                        identity,
                        capabilities,
                        session.peerCapabilities.maxChildren,
                    ).also { group ->
                        require(group.allReceiversSupportCodec(codec.capabilityMask)) {
                            "A group receiver cannot decode ${codec.displayName.substringBefore(" - ")}"
                        }
                        broadcastGroupSession = group
                    }
                } else {
                    null
                }
                // Experimental peer labels (default off): bit 5 in this endpoint hello only.
                val labelsOn = PeerLabelFeature.enabled
                val contentSession = if (groupSession == null) {
                    EndToEndVideoHandshake.initiate(session, identity, PeerLabelFeature.advertise(capabilities, labelsOn)).also { endpoint ->
                        // SEC-B2: the end-to-end peer is the link peer on every offer version and transport (the
                        // v2 branch above checks only the link prefix), so a phone that dialled the viewer's request
                        // cannot relay the viewer's handshake. Only the DEBUG manual relay is exempt.
                        StreamHopPeerBinding.requireBound(
                            "source",
                            rotatingSelection != null,
                            session.peerNodeId,
                            endpoint.peerNodeId,
                            session.peerCapabilities.maxChildren,
                            "Peer identity changed between discovery and connection",
                        )
                        require(audioLiveConfig != null || endpoint.peerCapabilities.decodeCodecMask and codec.capabilityMask != 0) {
                            "Final receiver cannot decode ${codec.displayName.substringBefore(" - ")}"
                        }
                        // Security review 2026-10-27 (item 14): a blocked person neither watches nor forwards this stream.
                        require(!PersonPolicy.blockedNode(endpoint.peerNodeId)) { "Viewer refused" }
                        endToEndSession = endpoint
                    }
                } else {
                    null
                }
                activeGattConnection?.let { connection ->
                    // A DEBUG override also applies to a relay-capable child, so the relay airtime A/B
                    // (source_gatt_priority) can test DCK on the upstream link; production is unchanged.
                    val childCanRelayLink = session.peerCapabilities.maxChildren > 0
                    val authenticatedPriority = DebugTunables.sourceGattPriority
                        ?: relayUpstreamPriority(
                            planned = if (childCanRelayLink) BleConnectionPriority.HIGH else gattPriority,
                            childCanRelay = childCanRelayLink,
                            sdkInt = android.os.Build.VERSION.SDK_INT,
                            enabled = DebugTunables.relayUpstreamDck,
                        )
                    if (DebugTunables.relayUpstreamDck) {
                        android.util.Log.i(
                            "BluetoothVideo",
                            "BENCHMARK relay_upstream_priority: role=source | transport=gatt | childCanRelay=$childCanRelayLink | " +
                                "priority=${authenticatedPriority.label}",
                        )
                    }
                    onStatus(
                        "GATT video ${authenticatedPriority.label} priority retry " +
                            if (connection.requestConnectionPriority(authenticatedPriority)) "queued" else "failed",
                    )
                }
                if (transport == BluetoothTransportOption.BLE_L2CAP && DebugTunables.relayUpstreamDck) {
                    // CoC requested HIGH on its control connection before the child was known; a relay-capable
                    // child moves to the experimental DCK upstream priority (test_relay_upstream_dck).
                    val childCanRelayLink = session.peerCapabilities.maxChildren > 0
                    val cocPriority = relayUpstreamPriority(
                        BleConnectionPriority.HIGH,
                        childCanRelayLink,
                        android.os.Build.VERSION.SDK_INT,
                        enabled = true,
                    )
                    val queued = cocPriority != BleConnectionPriority.HIGH &&
                        bleControlConnection?.requestConnectionPriority(cocPriority) == true
                    android.util.Log.i(
                        "BluetoothVideo",
                        "BENCHMARK relay_upstream_priority: role=source | transport=coc | childCanRelay=$childCanRelayLink | " +
                            "priority=${cocPriority.label} | queued=$queued",
                    )
                }
                val streamWriter = EndToEndStreamWriter(
                    hop.writer,
                    groupSession?.streamSealer ?: requireNotNull(contentSession).sealer,
                    identity.nodeId,
                    // An audio-only stream is always a mesh stream with one shared stream ID, so the source signs
                    // each record once for every child (the signature binds the stream ID).
                    useMeshEnvelope = audioLiveConfig != null || session.peerCapabilities.maxChildren > 0,
                    meshStreamId = audioLiveConfig?.signer?.streamId ?: groupSession?.streamId,
                )
                acknowledgedGroupReceivers.clear()
                endAcknowledgements = CountDownLatch(groupSession?.receiverCount ?: 1)
                if (contentSession != null &&
                    LabelRecords.streamHopNegotiated(labelsOn, session.peerNodeId, contentSession.peerNodeId, contentSession.peerCapabilities)
                ) {
                    // One LABELS record before the header, count 0..3 (R4); the title is bound to this
                    // stream ID, or to the direct-stream binding when no mesh stream ID exists (R2).
                    val labels = listOfNotNull(
                        LocalLabels.signed(
                            identity,
                            LabelKind.STREAM_TITLE,
                            LabelBinding.stream(streamWriter.meshStreamId),
                            PeerLabelFeature.localStreamTitle,
                        ),
                    )
                    LabelRecords.write(hop.writer, contentSession.sealer, labels)
                    android.util.Log.i("BluetoothVideo", "BENCHMARK peer_labels stream_tx: count=${labels.size} | peer=${contentSession.peerNodeId.shortLabel()}")
                }
                if (audioLiveConfig != null) {
                    streamWriter.writeAudioLiveHeader(audioLiveConfig.signer)
                } else {
                    streamWriter.writeHeader(
                        codec.protocolId,
                        settings,
                        rotationDegrees,
                        includeAudio,
                    )
                }
                hop.writer.flush()
                if (BuildConfig.DEBUG && corruptFirstVideoRecordForTest) {
                    (groupSession?.streamSealer ?: requireNotNull(contentSession).sealer)
                        .corruptNextRecordForTest()
                    onStatus("Test mode: the first end-to-end video record will be corrupted")
                }
                lastHeartbeatReceivedNs = System.nanoTime()
                val expectPeriodicChildHeartbeat = shouldExpectPeriodicChildHeartbeat(
                    transport,
                    childCanRelay = session.peerCapabilities.maxChildren > 0,
                    debugDirectGattHeartbeat = !BuildConfig.DEBUG || debugDirectGattHeartbeat,
                )
                periodicChildHeartbeatExpected = expectPeriodicChildHeartbeat
                if (BuildConfig.DEBUG && debugDirectGattHeartbeat && transport == BluetoothTransportOption.BLE_GATT) {
                    android.util.Log.i("BluetoothVideo", "BENCHMARK direct GATT heartbeat expect enabled childMaxChildren=${session.peerCapabilities.maxChildren}")
                }
                running.set(true)
                startPeriodicFlusher(hop, ioConfig.flushPolicy)
                onStatus(
                    if (audioLiveConfig != null) {
                        "Connected over ${transport.shortLabel}. Starting an audio-only stream…"
                    } else {
                        "Connected over ${transport.shortLabel}. Starting " +
                            "${settings.resolution.width}×${settings.resolution.height} " +
                            "${settings.fps} FPS ${codec.displayName} stream…"
                    },
                )
                onStatus(
                    "Authenticated link peer ${session.peerNodeId.shortLabel()} and final receiver " +
                        "${contentSession?.peerNodeId?.shortLabel() ?: "group-${groupSession?.receiverCount}"}; " +
                        "media is end-to-end encrypted. " +
                        "I/O ${ioConfig.bufferBytes / 1_024} KiB/${ioConfig.flushPolicy.debugValue}.",
                )
                streamWriter.meshStreamId?.let {
                    onStatus("Mesh stream $it uses serialized identity, sequence, and TTL.")
                }
                if (groupSession != null) {
                    startGroupSourceControlReader(hop, groupSession)
                } else {
                    startSourceControlReader(session, hop, requireNotNull(contentSession))
                }
                streamConnectedAtMs = SystemClock.elapsedRealtime()
                onConnected()

                val startedNs = System.nanoTime()
                var lastReportNs = startedNs
                var bytesWritten = 0L
                var rateWindowBytes = 0L
                var rateWindowStartNs = startedNs
                var sampleSentBytes = 0L
                var sampleStartNs = startedNs
                var sampleThinned = thinnedFrames.get()
                var sampleShed = shedFramesTotal.get()
                var lastReportedThinned = thinnedFrames.get()
                var lastReportedShed = shedFramesTotal.get()
                offeredWindowBytes.set(0L)
                childDelivery = PeerDeliveryEstimate()
                sendLoad.start()
                // Pace from the first write; the child's first arrival report replaces the seed.
                LinkPacerSeed.seed(pacer, transport, session.peerNodeId.shortLabel())?.let { kbps ->
                    Log.i("BluetoothVideo", "Link pacer seeded at $kbps kbps until the child's first report (${transport.shortLabel})")
                    if (joinProbeSeed && pacer.beginJoinProbe()) {
                        Log.i("BluetoothVideo", "Join probe: the link is unpaced for up to 1.5 s or 32 KiB so the child can time it")
                    }
                }
                val writeLatency = RollingLatencyStats()
                val transmitMps = connectedSocket?.let {
                    runCatching { it.maxTransmitPacketSize }.getOrDefault(0)
                } ?: 0
                while (running.get()) {
                    if (joinProbeSeed) {
                        pacer.expireJoinProbe()?.let {
                            Log.i("BluetoothVideo", it.benchmarkLine("stream_tx", session.peerNodeId.shortLabel()))
                        }
                    }
                    // Wait for link credit before choosing the packet, so audio that arrives
                    // meanwhile still goes first.
                    // Audio is never held for credit: paced audio arrived late (96 drops).
                    var heldNs = 0L
                    while (running.get() && packets.none(Packet::isAudio)) {
                        val waitNs = pacer.waitNs()
                        if (waitNs <= 0L) break
                        val sliceNs = minOf(waitNs, PACING_SLICE_NS)
                        Thread.sleep(sliceNs / 1_000_000L, (sliceNs % 1_000_000L).toInt())
                        heldNs += sliceNs
                    }
                    val packet = takeNextPacket()
                    queuedBytes.addAndGet(-packet.bytes.size.toLong())
                    val writeStartedNs = System.nanoTime()
                    if (packet.streamState == null) {
                        // Media age spent in this queue (both arms; BENCHMARK media_age).
                        val waitedMs = (writeStartedNs - packet.queuedAtNs).coerceAtLeast(0L) / 1_000_000L
                        if (packet.isAudio) {
                            audioQueueWaitMax.record(waitedMs)
                        } else if (packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                            videoQueueWaitMax.record(waitedMs)
                        }
                    }
                    if (packet.streamState == null && !packet.isAudio && packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                        debugStageTrace?.record("write_begin", packet.timeUs, packet.bytes.size, writeStartedNs)
                    }
                    val wireBytes = packet.streamState?.let { state ->
                        streamWriter.writeState(state)
                    } ?: if (audioLiveConfig != null) {
                        streamWriter.writeAudioLiveRecord(AudioLiveSignedRecord(packet.flags, packet.timeUs, packet.bytes))
                    } else {
                        streamWriter.writePacket(
                            packet.flags,
                            packet.timeUs,
                            packet.bytes,
                        )
                    }
                    pacer.onSent(wireBytes, writeStartedNs)
                    if (pacerConfig.drainGuard) pacer.noteWrite(heldNs, System.nanoTime() - writeStartedNs)
                    if (packet === lastLargePacket) {
                        burstDrain.onLargeFrameWritten(System.nanoTime())
                        lastLargePacket = null
                    }
                    val pendingFlushBytes = bytesPendingFlush.addAndGet(wireBytes.toLong())
                    if (packet.streamState == null && !packet.isAudio && packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                        debugStageTrace?.record("write_end", packet.timeUs, packet.bytes.size)
                    }
                    if (ioConfig.flushPolicy.periodicFlushMs > 0) markFlushPending()
                    val flushNow = packet.streamState != null ||
                        ioConfig.flushPolicy == VideoFlushPolicy.EVERY_FRAME ||
                        pendingFlushBytes >= ioConfig.flushPolicy.byteThreshold
                    if (flushNow) {
                        flushPending.set(false)
                        bytesPendingFlush.set(0)
                        val flushStartedNs = System.nanoTime()
                        hop.writer.flush()
                        flushLatency.recordNanos(System.nanoTime() - flushStartedNs)
                    }
                    if (packet.streamState == StreamLifecycleState.ENDED) {
                        val acknowledged = endAcknowledgements?.await(
                            STREAM_END_ACK_TIMEOUT_MS,
                            TimeUnit.MILLISECONDS,
                        ) == true
                        onStatus(
                            if (acknowledged) {
                                "Every final receiver acknowledged the authenticated stream End."
                            } else {
                                "Stream End was flushed, but its final receiver acknowledgement timed out."
                            },
                        )
                    }
                    val writeNs = System.nanoTime() - writeStartedNs
                    rateWindowBytes += wireBytes
                    sampleSentBytes += packet.bytes.size
                    // The same bytes the child counts as received (media packet payloads).
                    if (childDeliveryReports && packet.streamState == null) childDelivery.onSent(packet.bytes.size, System.nanoTime())
                    val rateNowNs = System.nanoTime()
                    if (rateNowNs - sampleStartNs >= LINK_SAMPLE_NS) {
                        val thinnedNow = thinnedFrames.get()
                        val shedNow = shedFramesTotal.get()
                        // Experimental burst-aware drain extends grace while the burst still drains.
                        val burstDraining = burstDrain.draining(rateNowNs, queuedBytes.get()) && burstAwareDrain
                        onLinkSample(
                            LinkRateSample(
                                windowNs = rateNowNs - sampleStartNs,
                                offeredBytes = offeredWindowBytes.getAndSet(0L),
                                sentBytes = sampleSentBytes,
                                backlogAgeMs = oldestPacketAgeMs(rateNowNs),
                                largeFrameGrace = rateNowNs < largeFrameGraceUntilNs || burstDraining,
                                largeFrameAgeMs = burstDrain.largeFrameAgeMs(rateNowNs),
                                measuredLinkKbps = peerMeasuredKbps,
                                childLate = rateNowNs < childLateUntilNs,
                                peer = session.peerNodeId.shortLabel(),
                                thinnedFrames = thinnedNow - sampleThinned,
                                shedFrames = shedNow - sampleShed,
                                deliveredKbps = childDelivery.deliveredKbps(),
                                peerQueueMs = childDelivery.queueMs(),
                            ),
                        )
                        sampleThinned = thinnedNow
                        sampleShed = shedNow
                        sampleSentBytes = 0L
                        sampleStartNs = rateNowNs
                    }
                    if (rateNowNs - rateWindowStartNs >= RATE_WINDOW_NS) {
                        val windowRate = rateWindowBytes * 1_000_000_000L / (rateNowNs - rateWindowStartNs)
                        measuredBytesPerSecond = if (measuredBytesPerSecond == 0L) windowRate else (measuredBytesPerSecond + windowRate) / 2
                        rateWindowBytes = 0L
                        rateWindowStartNs = rateNowNs
                    }
                    bytesWritten += wireBytes
                    linkLoad?.add(wireBytes)
                    writeLatency.recordNanos(writeNs)
                    val nowNs = System.nanoTime()
                    if (packet.streamState == StreamLifecycleState.ENDED) {
                        running.set(false)
                        break
                    }
                    if (nowNs - lastReportNs >= METRICS_INTERVAL_NS) {
                        if (expectPeriodicChildHeartbeat &&
                            nowNs - lastHeartbeatReceivedNs >= receiverHeartbeatTimeoutNs(transport)
                        ) {
                            throw IOException(StreamPhyLoss.HEARTBEAT_TIMED_OUT)
                        }
                        // A backlog or a late child means this link fell behind its offer: capacity evidence.
                        // Experimental thinning counts the same way (always 0 while the experiment is off).
                        val thinned = thinnedFrames.get()
                        val shed = shedFramesTotal.get()
                        if (oldestPacketAgeMs(nowNs) >= LinkRateController.CONGESTED_BACKLOG_MS ||
                            nowNs < childLateUntilNs || thinned > lastReportedThinned || shed > lastReportedShed
                        ) {
                            linkLoad?.saturated()
                        }
                        lastReportedThinned = thinned
                        lastReportedShed = shed
                        if (BuildConfig.DEBUG) {
                            Log.i("BluetoothVideo", boundedAgeBenchmarkLine(nowNs, session.peerNodeId.shortLabel()))
                        }
                        currentThermalStatus = thermalStatusProvider().coerceIn(0, 0xff)
                        val thermalStatus = currentThermalStatus
                        val seconds = (nowNs - startedNs) / 1_000_000_000.0
                        val kbps = bytesWritten * 8 / seconds / 1_000
                        val latency = writeLatency.snapshot()
                        val flush = flushLatency.snapshot()
                        val link = when (transport) {
                            BluetoothTransportOption.BLE_L2CAP ->
                                " | ${bleControlConnection?.phySummary ?: "${phyPreference.label} PHY requested"} | TX MPS $transmitMps"
                            BluetoothTransportOption.BLE_GATT ->
                                " | ${gattConnection?.phySummary?.invoke() ?: "PHY pending"}"
                            // Android reports 0 for RFCOMM packet sizes; frames are at most 990 B (BTA_JV_DEF_RFC_MTU).
                            BluetoothTransportOption.CLASSIC_RFCOMM -> " | RFCOMM MTU not exposed"
                        }
                        onStatus(
                            "End-to-end encrypted ${transport.shortLabel} TX: %.0f kbps | frame-call p50/p95/p99 %.1f/%.1f/%.1f ms | flush p95 %.1f ms | queue %d/%d, %d KB, %d ms | drops %d | thermal %d%s".format(
                                kbps,
                                latency.p50Ms,
                                latency.p95Ms,
                                latency.p99Ms,
                                flush.p95Ms,
                                packets.size,
                                normalQueueCapacity,
                                queuedBytes.get() / 1_024,
                                oldestPacketAgeMs(nowNs),
                                // Queue drops: only experimental bounded media age sheds (0 while off).
                                shed,
                                thermalStatus,
                                link,
                            ) + if (thinDiscardable) " | thinned $thinned" else "",
                        )
                        lastReportNs = nowNs
                    }
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                linkFailure = error
                if (running.get() || socket != null || gattConnection != null) {
                    if (audioLive != null) AudioLiveLog.failure("sender:${Thread.currentThread().name}", error)
                    onStatus("Bluetooth stopped: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                runCatching { noteStreamPhyLoss() }
                running.set(false)
                linkLoad?.close()
                linkLoad = null
                clearPackets()
                hopLink?.close()
                hopLink = null
                closeSocket()
                closeSecureSession()
                closeBleControlConnection()
                closeGattConnection()
                controlThread?.interrupt()
                controlThread = null
                flushThread?.interrupt()
                flushThread = null
                endAcknowledgements = null
                acknowledgedGroupReceivers.clear()
                periodicChildHeartbeatExpected = false
                workerThread = null
                onDisconnected()
            }
        }
    }

    internal fun expectsPeriodicChildHeartbeat(): Boolean = periodicChildHeartbeatExpected

    /**
     * A direct GATT stream whose 2M link went silent soon after hop authentication marks the hop peer bad on 2M, so the next
     * link to it starts on 1M ([StreamPhyLoss], `test_stream_adaptive_phy`). Runs once, from the worker's cleanup, before the
     * link objects are released.
     */
    private fun noteStreamPhyLoss() {
        val probe = phyLossProbe ?: return
        phyLossProbe = null
        val linkKind = linkTransport ?: return
        val nowMs = SystemClock.elapsedRealtime()
        val status = probe.gatt?.disconnectStatus?.invoke() ?: -1
        val cause = StreamPhyLoss.cause(
            status,
            linkFailure,
            connected = streamConnectedAtMs > 0L,
            sinceOpenMs = nowMs - probe.openedAtMs,
        )
        if (cause == StreamPhyLoss.Cause.OTHER) return
        val windowStartMs = if (probe.authenticatedAtMs > 0L) probe.authenticatedAtMs else probe.openedAtMs
        val supervisionMs = LeConnectionParameterLog.latest(probe.address)?.supervisionTimeoutMs?.toLong() ?: 0L
        val heartbeatMs = lastHeartbeatReceivedNs.takeIf { it > 0L }
            ?.let { nowMs - (System.nanoTime() - it) / 1_000_000L } ?: 0L
        val silentSinceMs = StreamPhyLoss.silentSinceMs(cause, nowMs, supervisionMs, heartbeatMs)
        val phy = runCatching { probe.gatt?.phySummary?.invoke() }.getOrNull().orEmpty()
        val node = probe.node
        val verdict = StreamPhyLoss.verdict(
            enabled = DebugTunables.streamAdaptivePhy && DebugTunables.adaptivePhy && DebugTunables.adaptivePhyMemory,
            // A setup timeout is the owner closing a link that never finished; only a loss after the stream connected can be a deliberate stop.
            endedLocally = ending.get() && cause != StreamPhyLoss.Cause.SETUP_TIMEOUT,
            gatt = linkKind == BluetoothTransportOption.BLE_GATT,
            cause = cause,
            authenticatedAtMs = windowStartMs,
            silentSinceMs = silentSinceMs,
            wasTwoM = linkWasTwoM(phy, probe.requestedTwoM),
            sendLinks = NodeLinkLoad.sendLinks(linkKind),
            peerRelays = probe.peerRelays,
            peerKnown = node != null,
        )
        Log.i(
            "BluetoothVideo",
            "BENCHMARK adaptive_phy: link=stream | node=${node ?: "unknown"} | early_loss=${verdict.record} | reason=${verdict.reason} | " +
                "cause=${cause.name.lowercase()} | gattStatus=$status | hopAuthenticated=${probe.authenticatedAtMs > 0L} | " +
                "silentAfterMs=${maxOf(silentSinceMs, windowStartMs) - windowStartMs} | " +
                "detectedAfterMs=${nowMs - windowStartMs} | supervisionMs=$supervisionMs | " +
                "phy=${phy.ifEmpty { "unknown" }} | requested2M=${probe.requestedTwoM}",
        )
        if (!verdict.record || node == null) return
        Log.i(
            "BluetoothVideo",
            "BENCHMARK adaptive_phy: link=stream | node=$node | from=2M to=1M | reason=early_loss | next=1M",
        )
        AdaptivePhyMemory.recordBad(linkKind, node, "stream_early_loss")
    }

    /** The authenticated link peer (null before the link handshake). */
    internal fun authenticatedPeerNode(): NodeId? = secureSession?.peerNodeId

    /**
     * The forwarded-stream key this child receives in a `BFG1` grant if it forwards: the key sealed for it.
     * Null before the end-to-end handshake and for a group hop. Feeds the recovery hint (RV18).
     */
    internal fun recoveryMediaKey(): ByteArray? = endToEndSession?.sealer?.copyKeyForForwarding()

    internal fun lastChildHeartbeatNs(): Long = lastHeartbeatReceivedNs

    @Synchronized
    fun send(flags: Int, presentationTimeUs: Long, bytes: ByteArray) {
        if (!running.get() || streamPaused.get() || ending.get()) return
        audioLive?.let { config ->
            sendAudioLive(config, flags, presentationTimeUs, bytes)
            return
        }
        if (flags and StreamProtocol.FLAG_AUDIO == 0 && flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
            debugStageTrace?.record("send_enqueue", presentationTimeUs, bytes.size)
        }
        val streamPacket = StreamProtocol.Packet(flags, presentationTimeUs, bytes)
        val protectedPacket = if (
            debugIntegrityEnabled && !streamPacket.isAudio &&
            flags and (StreamProtocol.FLAG_CODEC_CONFIG or StreamProtocol.FLAG_DISPLAY_ROTATION) == 0
        ) {
            StreamProtocol.attachDebugIntegrity(streamPacket)
        } else {
            streamPacket
        }
        val packet = Packet(
            protectedPacket.flags,
            protectedPacket.presentationTimeUs,
            protectedPacket.payload,
            System.nanoTime(),
        )
        offeredWindowBytes.addAndGet(packet.bytes.size.toLong())
        if (!packet.isAudio) noteVideoFrame(packet)
        if (boundedAge && shedForMediaAge(packet)) return
        if (moveSkipAwaitKeyFrame.armed && !packet.isAudio &&
            packet.flags and (StreamProtocol.FLAG_CODEC_CONFIG or StreamProtocol.FLAG_DISPLAY_ROTATION) == 0 &&
            moveSkipAwaitKeyFrame.dropArrivingVideo(packet.isKeyFrame, SystemClock.elapsedRealtime())
        ) {
            moveSkipRecords.incrementAndGet()
            requestShedKeyFrameIfDue(System.nanoTime())
            return
        }
        if (thinDiscardable &&
            TemporalLayerThinning.shouldThin(packet.isDiscardable, oldestPacketAgeMs(System.nanoTime()).toLong())
        ) {
            // Experimental temporal layering: a verified-discardable frame skipped while behind.
            thinnedFrames.incrementAndGet()
            return
        }
        enqueue(packet)
        if (queuedBytes.get() > MemoryThermalPolicy.senderQueueBytes) {
            // Memory safety only: tens of seconds behind live means the link is gone. Release the
            // viewer (it reconnects or reroutes) rather than discard frames it is still waiting for.
            onStatus("This viewer fell ${oldestPacketAgeMs(System.nanoTime())} ms behind live; releasing it to reconnect.")
            withdrawForLocalCapacity()
        }
    }

    /**
     * Audio-only live: [bytes] is a record the source already signed. Queue it, shed stale whole records of
     * this child's queue (Design AL age bound; the Opus configuration and markers never match), and withdraw
     * the child when it is hopelessly behind (more than 20% shed over 10 s, or a record older than 30 s).
     */
    private fun sendAudioLive(config: AudioLiveSendConfig, flags: Int, presentationTimeUs: Long, bytes: ByteArray) {
        val sendQueue = audioSendQueue ?: return
        val packet = Packet(flags, presentationTimeUs, bytes, System.nanoTime())
        offeredWindowBytes.addAndGet(bytes.size.toLong())
        enqueue(packet)
        val nowNs = System.nanoTime()
        val outcome = sendQueue.afterEnqueue(packet, nowNs)
        if (outcome.removed > 0) {
            queuedBytes.addAndGet(-outcome.removedBytes)
            shedFramesTotal.addAndGet(outcome.removed.toLong())
            Log.i(
                "BluetoothVideo",
                "BENCHMARK audio_live_shed: role=stream_tx | removed=${outcome.removed} | total=${outcome.shedTotal} | " +
                    "queueAgeMs=${outcome.oldestAgeMs}",
            )
        }
        val reason = outcome.verdict
        if (reason != null) {
            onStatus("This listener fell too far behind ($reason); releasing it to reconnect.")
            Log.i("BluetoothVideo", "BENCHMARK audio_live_withdraw: role=stream_tx | reason=$reason")
            withdrawForLocalCapacity()
            return
        }
        if (queuedBytes.get() > MemoryThermalPolicy.senderQueueBytes) {
            onStatus("This listener fell ${outcome.oldestAgeMs} ms behind live; releasing it to reconnect.")
            withdrawForLocalCapacity()
        }
    }

    /** Mute: nothing recorded before the tap goes out afterwards, so unsent audio records are dropped. */
    fun discardQueuedAudioRecords(): Int {
        val dropped = audioSendQueue?.discardSheddable() ?: return 0
        dropped.forEach { queuedBytes.addAndGet(-it.bytes.size.toLong()) }
        return dropped.size
    }

    fun pauseStream() {
        if (!running.get() || ending.get() || !streamPaused.compareAndSet(false, true)) return
        clearPackets()
        enqueue(Packet(0, 0, ByteArray(0), System.nanoTime(), StreamLifecycleState.PAUSED))
    }

    fun repeatPausedState() {
        if (!running.get() || ending.get() || !streamPaused.get()) return
        enqueue(Packet(0, 0, ByteArray(0), System.nanoTime(), StreamLifecycleState.PAUSED))
    }

    fun resumeStream() {
        if (!running.get() || ending.get() || !streamPaused.compareAndSet(true, false)) return
        enqueue(Packet(0, 0, ByteArray(0), System.nanoTime(), StreamLifecycleState.LIVE))
    }

    /**
     * Live with the microphone muted and/or the camera off (experimental `live_media_state`), or plain LIVE
     * when everything is back on. One record in queue order; ignored while paused or ending.
     */
    fun sendMediaState(state: StreamLifecycleState) {
        require(state.isLiveFamily) { "Only live-family records are media states" }
        if (!running.get() || ending.get() || streamPaused.get()) return
        enqueue(Packet(0, 0, ByteArray(0), System.nanoTime(), state))
    }

    fun endStream() {
        if (!ending.compareAndSet(false, true)) return
        streamPaused.set(true)
        clearPackets()
        if (!running.get()) {
            close()
            return
        }
        enqueue(Packet(0, 0, ByteArray(0), System.nanoTime(), StreamLifecycleState.ENDED))
        thread(name = "BluetoothStreamEndTimeout") {
            try {
                Thread.sleep(STREAM_END_GRACE_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (running.get()) close()
        }
    }

    fun withdrawForLocalCapacity() {
        if (!ending.compareAndSet(false, true)) return
        withdrawingForCapacity.set(true)
        clearPackets()
        val timeoutHandler = Handler(Looper.getMainLooper())
        val forcedClose = Runnable(::close)
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ROUTE_WITHDRAWAL_ACK_TIMEOUT_MS)
        timeoutHandler.postDelayed(forcedClose, ROUTE_WITHDRAWAL_ACK_TIMEOUT_MS)
        thread(name = "BluetoothCapacityWithdrawal") {
            try {
                val hop = hopLink
                if (running.get() && hop != null) {
                    hop.sendHopControl(
                        SecureRecordType.ROUTE_CONTROL,
                        StreamRouteControlProtocol.encode(StreamRouteWithdrawal.LOCAL_CAPACITY),
                    )
                    hop.writer.flush()
                    if (!routeWithdrawalAcknowledgement.await(
                            (deadlineNs - System.nanoTime()).coerceAtLeast(0L), TimeUnit.NANOSECONDS,
                        ) &&
                        running.get()
                    ) {
                        onStatus("Capacity rejection acknowledgement timed out; closing this route.")
                    }
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                if (running.get()) onStatus("Could not notify this viewer that source capacity is full: ${error.message}")
            } finally {
                timeoutHandler.removeCallbacks(forcedClose)
                close()
            }
        }
    }

    override fun close() {
        debugStageTrace?.close()
        debugStageTrace = null
        ending.set(true)
        running.set(false)
        clearPackets()
        workerThread?.interrupt()
        hopLink?.close()
        hopLink = null
        closeSocket()
        closeSecureSession()
        closeBleControlConnection()
        closeGattConnection()
        runCatching { wifiConnection?.close?.invoke() }
        controlThread?.interrupt()
        controlThread = null
        flushThread?.interrupt()
        flushThread = null
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
            // The socket is already unusable.
        } finally {
            socket = null
        }
    }

    private fun closeRawLink() {
        closeSocket()
        closeBleControlConnection()
        closeGattConnection()
        runCatching { wifiConnection?.close?.invoke() }
    }

    private fun closeBleControlConnection() {
        bleControlConnection?.close()
        bleControlConnection = null
    }

    private fun closeGattConnection() {
        gattConnection?.close()
        gattConnection = null
    }

    private fun startSourceControlReader(
        session: SecureBluetoothSession,
        hop: HopLink,
        contentSession: EndToEndVideoSession,
    ) {
        // Experimental test_relay_join_key_frame (off): a relay child's request after a calm gap is a likely downstream join.
        val joinKeyFrame = RelayJoinKeyFrame(SystemClock.elapsedRealtime())
        val childRelays = session.peerCapabilities.maxChildren > 0
        controlThread = thread(name = "BluetoothSourceControl") {
            try {
                while (running.get()) {
                    when (EndToEndStreamProtocol.readSourceControl(
                        hop.reader,
                        contentSession.opener,
                        ::onPeerCapacityReport,
                    )) {
                        SourceControlMessage.LINK_HEARTBEAT -> {
                            lastHeartbeatReceivedNs = System.nanoTime()
                            if (BuildConfig.DEBUG && debugDirectGattHeartbeat && gattConnection != null) {
                                android.util.Log.i("BluetoothVideo", "BENCHMARK direct GATT heartbeat authenticated receipt")
                            }
                        }
                        // An audio-only stream has no keyframes: the request is ignored, never passed to an encoder.
                        SourceControlMessage.KEY_FRAME_REQUEST -> if (audioLive == null) {
                            val forced = joinKeyFrame.onRequest(
                                SystemClock.elapsedRealtime(), DebugTunables.relayJoinKeyFrame, childRelays,
                            )
                            if (BuildConfig.DEBUG) android.util.Log.i(
                                "BluetoothVideo",
                                "BENCHMARK relay_join_key_frame: childRelays=$childRelays | forced=$forced | " +
                                    "switch=${DebugTunables.relayJoinKeyFrame}",
                            )
                            if (forced) onRemoteJoinKeyFrame() else onRemoteKeyFrameNeeded()
                        }
                        SourceControlMessage.STREAM_END_ACK -> {
                            endAcknowledgements?.countDown()
                            return@thread
                        }
                        SourceControlMessage.ROUTE_WITHDRAWAL_ACK -> {
                            require(withdrawingForCapacity.get()) { "Unexpected route withdrawal acknowledgement" }
                            routeWithdrawalAcknowledgement.countDown()
                            return@thread
                        }
                    }
                }
            } catch (error: Exception) {
                if (linkFailure == null) linkFailure = error
                if (running.compareAndSet(true, false)) {
                    onStatus(
                        "Immediate child control channel stopped; closing the link: " +
                            (error.message ?: error.javaClass.simpleName),
                    )
                    clearPackets()
                    closeSocket()
                    closeBleControlConnection()
                    closeGattConnection()
                    workerThread?.interrupt()
                }
            }
        }
    }

    /**
     * The immediate child measured how fast this link delivered large records (workstream B). The
     * best recent value becomes this phone's measured single-link capacity for the transport, in
     * place of an assumed prior, and stays visible for diagnosis.
     */
    private fun onPeerCapacityReport(report: LinkCapacityReport) {
        val transport = linkTransport ?: return
        if (childDeliveryReports) report.delivery?.let { childDelivery.onReport(it) }
        // A delivery-only report repeats the last full report's lateness fields: not a new trend step.
        if (report.deliveryOnly) return
        if (childLateness.onReport(report)) childLateUntilNs = System.nanoTime() + PeerLatenessTrend.LATE_HOLD_NS
        childAddedDelayMs = report.addedDelayMs
        val kbps = report.arrivalKbps
        if (kbps <= 0) {
            // On-time report: media reaches the child without lateness, so the link carries the offered
            // rate; hold the seeded pace instead of decaying it blind (DebugTunables.onTimeReports).
            if (pacerConfig.onTimeReports && !report.linkLate && System.nanoTime() >= childLateUntilNs) {
                PacerSeedPolicy.onTimeReport(
                    pacer,
                    pacerConfig,
                    backlogMs = { oldestPacketAgeMs(System.nanoTime()).toLong() },
                    droppedMedia = { shedder.shedFrames },
                )?.let { hold ->
                    if (hold.raised) Log.i("BluetoothVideo", hold.raiseLine("stream_tx", null))
                    hold.guardBlock?.let {
                        Log.i("BluetoothVideo", it.benchmarkLine("stream_tx", secureSession?.peerNodeId?.shortLabel() ?: "?", hold.toKbps))
                    }
                }
            }
            return
        }
        if (joinProbeSeed) {
            val probe = pacer.resolveJoinProbe(kbps, oldestPacketAgeMs(System.nanoTime()) >= LinkPacer.SEED_RAISE_BACKLOG_MS)
            if (probe != null) {
                Log.i("BluetoothVideo", probe.benchmarkLine("stream_tx", secureSession?.peerNodeId?.shortLabel() ?: "?"))
                onStatus("Join probe: child timed this ${transport.shortLabel} link's first records at $kbps kbps")
                // Not backlogged: the burst only shows what was offered was carried, so it seeds the pace
                // (resolveJoinProbe) but is not this link's capacity.
                if (!probe.linkBound) return
            }
        }
        val previous = peerMeasuredKbps
        peerMeasuredKbps = kbps
        val hop = hopLink
        if (hop == null || learnsBluetoothCapacity(hop.acceptKind, hop.sendKind)) {
            LinkCapacity.recordProbe(transport, kbps)
            secureSession?.peerNodeId?.shortLabel()?.let { LinkCapacity.recordPeerLinkKbps(transport, it, kbps) }
        }
        if (DebugTunables.linkPacing) pacer.setMeasuredLinkKbps(kbps)
        if (previous == 0 || kotlin.math.abs(kbps - previous) * 10 >= previous) {
            onStatus("Child measured this ${transport.shortLabel} link at $kbps kbps")
        }
    }

    private fun startGroupSourceControlReader(
        hop: HopLink,
        groupSession: BroadcastGroupSourceSession,
    ) {
        controlThread = thread(name = "BluetoothGroupSourceControl") {
            try {
                while (running.get()) {
                    val record = hop.reader.read()
                    when (record.type) {
                        SecureRecordType.LINK_HEARTBEAT -> {
                            SecureLinkControlProtocol.requireHeartbeat(record)?.let(::onPeerCapacityReport)
                            lastHeartbeatReceivedNs = System.nanoTime()
                        }
                        SecureRecordType.ROUTE_CONTROL_ACK -> {
                            require(withdrawingForCapacity.get()) { "Unexpected route withdrawal acknowledgement" }
                            StreamRouteControlProtocol.decode(record.payload)
                            routeWithdrawalAcknowledgement.countDown()
                            return@thread
                        }
                        SecureRecordType.GROUP_CONTROL -> {
                            val message = GroupRelayControlCodec.decode(record.payload)
                            when (message.type) {
                                GroupRelayControlType.RECEIVER_CONTROL -> {
                                    // A receiver refused by the identity gate holds no key: ignore it.
                                    if (groupSession.isRefusedReceiver(message.receiverNodeId)) continue
                                    when (groupSession.openReceiverControl(message.receiverNodeId, message.payload).type) {
                                        EndToEndContentType.KEY_FRAME_REQUEST -> onRemoteKeyFrameNeeded()
                                        EndToEndContentType.STREAM_END_ACK -> {
                                            if (acknowledgedGroupReceivers.add(message.receiverNodeId)) {
                                                endAcknowledgements?.countDown()
                                            }
                                            if (endAcknowledgements?.count == 0L) return@thread
                                        }
                                        else -> error("Unexpected authenticated group receiver control")
                                    }
                                }
                                GroupRelayControlType.RECEIVER_LEFT -> {
                                    require(message.payload.isEmpty()) { "Invalid receiver-left control" }
                                    if (groupSession.markRefusedReceiverLeft(message.receiverNodeId)) {
                                        onStatus("Refused group receiver ${message.receiverNodeId.shortLabel()} left.")
                                        continue
                                    }
                                    require(groupSession.markReceiverDisconnected(message.receiverNodeId)) {
                                        "Unknown or duplicate departed group receiver"
                                    }
                                    if (acknowledgedGroupReceivers.add(message.receiverNodeId)) {
                                        endAcknowledgements?.countDown()
                                    }
                                    onStatus(
                                        "Group receiver ${message.receiverNodeId.shortLabel()} disconnected; " +
                                            "remaining receivers continue.",
                                    )
                                }
                                else -> error("Unexpected group source control ${message.type}")
                            }
                        }
                        else -> error("Unexpected group source record ${record.type}")
                    }
                }
            } catch (error: Exception) {
                if (running.compareAndSet(true, false)) {
                    onStatus(
                        "Immediate group relay control stopped; closing the link: " +
                            (error.message ?: error.javaClass.simpleName),
                    )
                    clearPackets()
                    closeSocket()
                    closeBleControlConnection()
                    closeGattConnection()
                    workerThread?.interrupt()
                }
            }
        }
    }

    /** A7: what the hop's carrier switch reports back to this source. */
    private fun sourceHopOwner(sendLoad: HopSendLoad): HopOwner = object : HopOwner {
        override fun sendCarrierChanged(kind: CarrierKind) {
            LinkChangeEvents.carrierChanged()
            // This hop's own accounting object: after the worker closed it (any order), a late change reopens nothing.
            sendLoad.onSendCarrier(kind)
            val transport = linkTransport ?: return
            LinkPacerSeed.seed(pacer, transport, secureSession?.peerNodeId?.shortLabel(), kind)
        }

        override fun localKeyframeNeeded() = onRemoteKeyFrameNeeded()

        override fun peerKeyframeNeeded() = Unit

        override fun reassertState() = Unit

        override fun hopEnded(reason: String) {
            if (running.compareAndSet(true, false)) {
                onStatus("The child's link closed: $reason")
                clearPackets()
                workerThread?.interrupt()
            }
        }

        override fun bluetoothBudgetKbps(): Int? =
            linkTransport?.let { LinkCapacity.measuredLinkKbps(it, CarrierKind.BLUETOOTH) }

        override fun targetKbps(): Int? = targetStreamKbps
    }

    private fun closeSecureSession() {
        hopLink?.close()
        hopLink = null
        broadcastGroupSession?.close()
        broadcastGroupSession = null
        endToEndSession?.close()
        endToEndSession = null
        secureSession?.close()
        secureSession = null
        lastHeartbeatReceivedNs = 0L
    }

    private fun startPeriodicFlusher(
        hop: HopLink,
        policy: VideoFlushPolicy,
    ) {
        if (policy.periodicFlushMs <= 0) return
        flushThread = thread(name = "BluetoothVideoFlush") {
            try {
                while (running.get()) {
                    synchronized(flushSignal) {
                        while (running.get() && !flushPending.get()) flushSignal.wait()
                    }
                    if (!running.get()) break
                    Thread.sleep(policy.periodicFlushMs)
                    if (!flushPending.compareAndSet(true, false)) continue
                    bytesPendingFlush.getAndSet(0)
                    val flushStartedNs = System.nanoTime()
                    hop.writer.flush()
                    flushLatency.recordNanos(System.nanoTime() - flushStartedNs)
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                if (running.compareAndSet(true, false)) {
                    onStatus("Bluetooth flush failed: ${error.message ?: error.javaClass.simpleName}")
                    closeSocket()
                    closeGattConnection()
                    workerThread?.interrupt()
                }
            }
        }
    }

    private fun markFlushPending() {
        if (!flushPending.compareAndSet(false, true)) return
        synchronized(flushSignal) { flushSignal.notifyAll() }
    }

    /**
     * A keyframe or scene-cut frame can take longer to send than the whole latency budget (a 130 KB
     * coverage keyframe needs ~1.7 s at 600 kbps). Treating that drain as congestion dropped video
     * and requested another equally large recovery keyframe, looping. Such frames get grace sized
     * to their measured drain time; sustained overload still drops and recovers.
     */
    private fun noteVideoFrame(packet: Packet) {
        val size = packet.bytes.size.toLong()
        val typical = typicalVideoBytes
        typicalVideoBytes = if (typical == 0L) size else (typical * 15 + size.coerceAtMost(typical * 4)) / 16
        val graceNs = largeFrameDrainGraceNs(size, typical, packet.isKeyFrame, queuedBytes.get(), measuredBytesPerSecond)
            ?: return
        val nowNs = System.nanoTime()
        largeFrameGraceUntilNs = maxOf(largeFrameGraceUntilNs, nowNs + graceNs)
        burstDrain.onLargeFrameQueued(nowNs)
        lastLargePacket = packet
    }

    /**
     * Experimental bounded media age: sheds this link's stale video codec-safely and returns true when
     * [packet] itself must be dropped. Runs under the send lock, so no arrival races the plan; the
     * writer only takes from the head (audio may pass video), so whatever it already wrote is a prefix
     * of the planned video. Records are sealed when written, so the child still sees contiguous
     * BMS1/BME1 sequences; only plaintext packets that were never sealed are removed.
     */
    private fun shedForMediaAge(packet: Packet): Boolean {
        if (packet.isAudio || packet.flags and (StreamProtocol.FLAG_CODEC_CONFIG or StreamProtocol.FLAG_DISPLAY_ROTATION) != 0) {
            return false
        }
        val nowNs = System.nanoTime()
        val before = shedder.shedFrames
        var drop = shedder.dropArrivingVideo(packet.isKeyFrame)
        if (drop) requestShedKeyFrameIfDue(nowNs)
        if (!drop) {
            val budgetMs = BoundedMediaAge.streamQueueBudgetMs(childAddedDelayMs)
            val ageMs = oldestVideoAgeMs(nowNs)
            if (shedder.shouldShed(ageMs, budgetMs, nowNs < largeFrameGraceUntilNs, nowNs)) {
                val (plan, removedPackets) = shedAtomically(queueLock, packets, ::shedClass) { candidate ->
                    (nowNs - candidate.queuedAtNs) / 1_000_000L <= budgetMs
                }
                removedPackets.forEach { queuedBytes.addAndGet(-it.bytes.size.toLong()) }
                val removed = removedPackets.size
                shedder.recordShed(plan, removed, nowNs)
                if (plan.awaitKeyFrame) {
                    lastShedKeyFrameRequestNs = 0L
                    drop = shedder.dropArrivingVideo(packet.isKeyFrame)
                    requestShedKeyFrameIfDue(nowNs)
                }
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK media_age_shed: role=stream_tx | queueAgeMs=$ageMs | budgetMs=$budgetMs | " +
                        "viewerAddedDelayMs=$childAddedDelayMs | removed=$removed | " +
                        "awaitKeyFrame=${plan.awaitKeyFrame} | events=${shedder.shedEvents}",
                )
                onStatus(
                    "Experimental bounded media age: skipped $removed stale video frames ($ageMs ms queued)" +
                        if (plan.awaitKeyFrame) "; waiting for a keyframe" else "; resuming at a queued keyframe",
                )
            }
        }
        shedFramesTotal.addAndGet(shedder.shedFrames - before)
        return drop
    }

    /**
     * This link just moved itself from 2M to 1M ([AdaptivePhyLink]). Two reasons to drop what is queued, both for live streams only:
     * (1) the flood guard (default on, `test_phy_move_flood_guard`): media older than [PhyMoveSkip.FLOOD_STALE_MS] is not written
     * into the viewer at link speed (video before a fresh keyframe, audio older than the viewer's playout); (2) the
     * backlog skip (default on for a fixed-rate stream, [PhyMoveSkip.enabled]; `test_phy_move_skip_backlog` overrides it): video older
     * than [PhyMoveSkip.thresholdMs] is dropped once, audio kept. Codec configuration
     * and lifecycle records are never dropped; the video resumes at a queued keyframe that is still fresh or waits for a requested one.
     */
    private fun skipBacklogAfterPhyMove() {
        val nowNs = System.nanoTime()
        val playoutMs = BoundedMediaAge.ASSUMED_VIEWER_BASE_DELAY_MS + childAddedDelayMs.coerceAtLeast(0)
        val ageMs = oldestVideoAgeMs(nowNs)
        val audioAgeMs = oldestAudioAgeMs(nowNs)
        val stream = audioLive == null
        val skipEnabled = PhyMoveSkip.enabled(DebugTunables.phyMoveSkipBacklog, PhyMoveSkip.fixedRateStream)
        val experimental = PhyMoveSkip.shouldSkip(
            enabled = skipEnabled && !ending.get(),
            isStream = stream,
            oldestVideoAgeMs = ageMs,
            viewerPlayoutMs = playoutMs,
        )
        val flood = PhyMoveSkip.floodGuard(
            enabled = DebugTunables.phyMoveFloodGuard && !ending.get(),
            isStream = stream,
            oldestVideoAgeMs = ageMs,
            oldestAudioAgeMs = audioAgeMs,
        )
        if (!experimental && !flood) {
            if (skipEnabled) {
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK phy_move_skip: role=stream_tx | skipped=false | queueAgeMs=$ageMs | " +
                        "thresholdMs=${PhyMoveSkip.thresholdMs(playoutMs)} | viewerPlayoutMs=$playoutMs",
                )
            }
            return
        }
        val (plan, removedPackets) = shedAtomically(queueLock, packets, ::shedClass) { candidate ->
            (nowNs - candidate.queuedAtNs) / 1_000_000L <= playoutMs
        }
        removedPackets.forEach { queuedBytes.addAndGet(-it.bytes.size.toLong()) }
        var audioRemoved = 0
        if (flood) {
            // Audio older than the viewer's playout is a sender gap to it (the Opus decoder realigns on the timestamp jump).
            synchronized(queueLock) {
                val removedAudio = PhyMoveSkip.removeStale(packets) { candidate ->
                    candidate.isAudio && candidate.streamState == null &&
                        candidate.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0 &&
                        (nowNs - candidate.queuedAtNs) / 1_000_000L > playoutMs
                }
                removedAudio.forEach { queuedBytes.addAndGet(-it.bytes.size.toLong()) }
                audioRemoved = removedAudio.size
            }
        }
        moveSkipRecords.addAndGet((removedPackets.size + audioRemoved).toLong())
        if (plan.awaitKeyFrame) {
            moveSkipAwaitKeyFrame.arm(SystemClock.elapsedRealtime())
            lastShedKeyFrameRequestNs = 0L
            requestShedKeyFrameIfDue(nowNs)
        }
        Log.i(
            "BluetoothVideo",
            "BENCHMARK phy_move_skip: role=stream_tx | skipped=true | kind=${if (flood) "flood_guard" else "experimental"} | " +
                "skippedRecords=${removedPackets.size} | skippedAudio=$audioRemoved | skippedAgeMs=$ageMs | audioAgeMs=$audioAgeMs | " +
                "thresholdMs=${if (flood) PhyMoveSkip.FLOOD_STALE_MS else PhyMoveSkip.thresholdMs(playoutMs)} | " +
                "viewerPlayoutMs=$playoutMs | awaitKeyFrame=${plan.awaitKeyFrame} | queueAfter=${packets.size}",
        )
        onStatus(
            "Skipped ${removedPackets.size} queued video frames ($ageMs ms old)" +
                (if (audioRemoved > 0) " and $audioRemoved audio packets" else "") +
                " after the move to 1M" +
                if (plan.awaitKeyFrame) "; waiting for a keyframe" else "; resuming at a queued keyframe",
        )
    }

    private fun requestShedKeyFrameIfDue(nowNs: Long) {
        if (lastShedKeyFrameRequestNs != 0L && nowNs - lastShedKeyFrameRequestNs < BoundedMediaAge.KEY_FRAME_REREQUEST_NS) return
        lastShedKeyFrameRequestNs = nowNs
        // The same path as a child's authenticated keyframe request (the encoder applies its backoff).
        onRemoteKeyFrameNeeded()
    }

    private fun shedClass(packet: Packet): ShedClass = when {
        packet.streamState != null || packet.isAudio -> ShedClass.KEEP
        packet.flags and (StreamProtocol.FLAG_CODEC_CONFIG or StreamProtocol.FLAG_DISPLAY_ROTATION) != 0 -> ShedClass.KEEP
        packet.isKeyFrame -> ShedClass.KEY_FRAME
        else -> ShedClass.VIDEO
    }

    private fun oldestAudioAgeMs(nowNs: Long): Long = packets.firstOrNull { packet ->
        packet.streamState == null && packet.isAudio && packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0
    }?.let { (nowNs - it.queuedAtNs).coerceAtLeast(0L) / 1_000_000L } ?: 0L

    private fun oldestVideoAgeMs(nowNs: Long): Long = packets.firstOrNull { packet ->
        packet.streamState == null && !packet.isAudio && packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0
    }?.let { (nowNs - it.queuedAtNs).coerceAtLeast(0L) / 1_000_000L } ?: 0L

    /** Both arms: this link's media-age contribution and the budget bounded media age would apply. */
    @Synchronized
    private fun boundedAgeBenchmarkLine(nowNs: Long, peer: String): String =
        "BENCHMARK media_age: role=stream_tx | policy=${BoundedMediaAge.policyLabel(boundedAge)} | peer=$peer | " +
            "videoQueueAgeMaxMs=${videoQueueWaitMax.take()} | audioQueueAgeMaxMs=${audioQueueWaitMax.take()} | " +
            "videoQueueAgeNowMs=${oldestVideoAgeMs(nowNs)} | viewerAddedDelayMs=$childAddedDelayMs | " +
            "budgetMs=${BoundedMediaAge.streamQueueBudgetMs(childAddedDelayMs)} | shedEvents=${shedder.shedEvents} | " +
            "shedFrames=${shedder.shedFrames} | keyFrameWaitFrames=${shedder.keyFrameWaitFrames} | " +
            "awaitingKeyFrame=${shedder.awaitingKeyFrame}"

    private fun enqueue(packet: Packet) {
        queuedBytes.addAndGet(packet.bytes.size.toLong())
        if (boundedAge) putUnderLock(queueLock, packets, packet) else packets.put(packet)
    }

    /** Audio is sealed in transmission order, so it can safely pass queued video without wire reordering. */
    private fun takeNextPacket(): Packet = if (boundedAge) {
        // Experimental bounded media age: take under the shed lock (see queueLock).
        requireNotNull(takeNextUnderLock(queueLock, packets, Packet::isAudio, timeoutMs = null))
    } else {
        takeNextPrioritizing(packets, Packet::isAudio)
    }

    private fun clearPackets(): Int {
        var cleared = 0
        while (true) {
            val packet = packets.poll() ?: break
            queuedBytes.addAndGet(-packet.bytes.size.toLong())
            cleared++
        }
        return cleared
    }

    private fun oldestPacketAgeMs(nowNs: Long): Int = packets.peek()?.let { packet ->
        ((nowNs - packet.queuedAtNs).coerceAtLeast(0) / 1_000_000L)
            .coerceAtMost(0xffff)
            .toInt()
    } ?: 0

    private companion object {
        /** ~2 min at 600 kbps: beyond any playout buffer; only a vanished link gets here. */
        const val PACING_SLICE_NS = 5_000_000L
        /** Reports arrive every 2 s; one late report keeps the evidence for two windows of rate control. */
        const val RATE_WINDOW_NS = 500_000_000L
        const val LINK_SAMPLE_NS = 1_000_000_000L
        const val METRICS_INTERVAL_NS = 5_000_000_000L
        const val STREAM_END_GRACE_MS = 5_000L
        const val ROUTE_WITHDRAWAL_ACK_TIMEOUT_MS = 4_000L
        const val STREAM_END_ACK_TIMEOUT_MS = 2_000L
    }
}

internal fun <T> takeNextPrioritizing(
    queue: BlockingQueue<T>,
    isPriority: (T) -> Boolean,
): T {
    val waitingPriority = queue.firstOrNull(isPriority)
    return if (waitingPriority != null && queue.remove(waitingPriority)) {
        waitingPriority
    } else {
        queue.take()
    }
}

/** Unknown classes stay eligible; audio, car, wearable and peripheral classes cannot host the app. */
internal fun classicMajorClassCanHostApp(majorDeviceClass: Int?): Boolean =
    majorDeviceClass == null || majorDeviceClass in setOf(
        BluetoothClass.Device.Major.PHONE,
        BluetoothClass.Device.Major.COMPUTER,
        BluetoothClass.Device.Major.UNCATEGORIZED,
    )

@SuppressLint("MissingPermission")
internal fun classicCandidateCanHostApp(device: BluetoothDevice): Boolean =
    classicMajorClassCanHostApp(runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull())

private const val LARGE_FRAME_FACTOR = 4
private const val FALLBACK_DRAIN_BYTES_PER_SECOND = 40_000L
private const val MAX_LARGE_FRAME_GRACE_NS = 3_000_000_000L

/**
 * Grace for a keyframe or a frame [LARGE_FRAME_FACTOR]x the typical size: 1.5x the measured time
 * to drain it plus the current backlog, capped at 3 s. Null for ordinary frames.
 */
internal fun largeFrameDrainGraceNs(
    sizeBytes: Long,
    typicalBytes: Long,
    keyFrame: Boolean,
    queuedBytes: Long,
    measuredBytesPerSecond: Long,
): Long? {
    val large = keyFrame || typicalBytes > 0 && sizeBytes > typicalBytes * LARGE_FRAME_FACTOR
    if (!large) return null
    val rate = measuredBytesPerSecond.takeIf { it > 0 } ?: FALLBACK_DRAIN_BYTES_PER_SECOND
    val drainNs = (sizeBytes + queuedBytes) * 1_000_000_000L / rate
    return (drainNs * 3 / 2).coerceAtMost(MAX_LARGE_FRAME_GRACE_NS)
}
