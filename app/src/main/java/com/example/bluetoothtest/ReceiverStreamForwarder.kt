package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ReceiverStreamForwarder(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val transport: BluetoothTransportOption,
    private val identity: SigningIdentity,
    private val upstreamPeerNodeId: NodeId,
    private val sourceNodeId: NodeId,
    private val streamId: UUID,
    private val mediaKey: ByteArray,
    private val streamToken: Int,
    private val routeCost: Int,
    private val streamBitrateKbps: Int,
    private val header: StreamProtocol.Header,
    private val phyPreference: BlePhyPreference,
    private val ioConfig: VideoStreamIoConfig,
    private val queueCapacity: Int,
    private val onStatus: (String) -> Unit,
    private val onChildCountChanged: (Int) -> Unit,
    private val onChildKeyFrameNeeded: () -> Unit,
    private val debugGattConnectionPriority: BleConnectionPriority? = null,
    /**
     * Experimental peer labels (null = off, unchanged wire): the labels this phone verified from the
     * source for this stream, forwarded byte for byte after GROUP_KEY when the child's hello has bit 5.
     */
    private val forwardLabels: (() -> List<ByteArray>)? = null,
    /**
     * Rotating IDs (phase 1b): the announced bytes of the version 5 offer this forwarder joined (an RDI),
     * from which the logical stream token is derived. Null: the node prefix, as before.
     */
    private val upstreamAnnouncedBytes: ByteArray? = null,
    /** Rotating IDs: this forwarder's own offers are version 5 (role-1 RDI) on LE, and rotate at boundaries. */
    private val rotatingOffers: Boolean = false,
) : Closeable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val terminalSeen = AtomicBoolean(false)
    private val withdrawing = AtomicBoolean(false)

    /** DEBUG setup timing: when the current child was added, until its first queued media (0 = not waiting). */
    private val childJoinedAtNs = AtomicLong(0L)
    @Volatile private var withdrawalReason: StreamRouteWithdrawal? = null
    private val logicalStreamToken = StreamRouteToken.logicalFrom(
        streamToken,
        upstreamAnnouncedBytes ?: upstreamPeerNodeId.toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
    )
    @Volatile private var downstreamStreamToken = StreamRouteToken.forAdvertiser(
        logicalStreamToken,
        identity.nodeId.toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
    )

    /** The viewer-request tokens this forwarder accepts: the current one, plus the previous for 60 s (PROTOCOL Phase 1b). */
    private val requestTokens = RouteTokenWindow().also { it.reset(logicalStreamToken, downstreamStreamToken) }
    private val rotateOnBoundary = rotatingOffers && RotatingDiscoveryFeature.enabled &&
        transport != BluetoothTransportOption.CLASSIC_RFCOMM
    private val boundaryListener: (Long) -> Unit = { _ -> rotatePresence() }
    private val rotating = AtomicBoolean(false)
    private val linkLoadSaturatedByChild = AtomicBoolean(false)
    @Volatile private var activeLinkLoad: HopSendLoad? = null

    /** Experimental temporal layering (off by default): this relay may thin discardable frames. */
    private val thinDiscardable = DebugTunables.experimentalTemporalLayers
    /**
     * Experimental bounded media age, global or relay-child-only (both off by default): the child
     * queue may shed stale video.
     */
    private val boundedMediaAge = BoundedMediaAge.relayChildEnabled()
    /**
     * Experimental relay child thinning (off by default; RelayChildLayerPolicy): this relay decides per
     * direct child between all frames, the base layer only, or audio only. Video streams only.
     */
    private val relayChildThinning = DebugTunables.relayChildThinning && !header.isAudioOnly
    /** Relay child re-anchoring, the relay-side starved-link close (default on; RelayRecoveryConfig.starvationClose). */
    private val reanchorEnabled = RelayRecoveryConfig.current().starvationClose
    /** The pacer seed and per-child queue configuration (PacerSeedPolicy.kt), read when the forwarder starts. */
    private val pacerConfig = PacerQueueConfig.current()
    private val childQueuePolicy = RelayChildQueuePolicy.of(pacerConfig, header.isAudioOnly)
    /**
     * Experimental time-based child queue target (Q31, DebugTunables.relayTimeQueue, off by default; video
     * streams only): the shed budget follows the child's measured drain and its jitter inside a latency budget.
     */
    private val timeQueueConfig = childQueuePolicy.timeQueue
    private val reanchorBudget = RelayReanchorBudget()
    /**
     * Set when the stall watch closes a starved child on purpose (re-anchor), so the writer's
     * resulting I/O error is reported as the re-anchor and not as "Downstream flush failed".
     */
    private val reanchorClosedChild = AtomicBoolean(false)
    private val core = ReceiverNodeForwardingCore(
        identity.nodeId,
        upstreamPeerNodeId,
        streamId,
        sourceNodeId,
        queueCapacity,
        thinDiscardable,
        boundedMediaAge,
        // Audio-only live: the child starts at the next record (no keyframes), stale audio is shed per hop.
        audioLive = if (header.isAudioOnly) AudioLiveRelaySettings(AudioLiveTunables.ageBound) else null,
        layerThinning = relayChildThinning,
        timeQueue = timeQueueConfig,
        frameIntervalMs = (1_000L / header.fps.coerceAtLeast(1)).coerceAtLeast(10L),
    )
    private val stateLock = Any()
    @Volatile private var advertiser: BleDiscoveryAdvertiser? = null
    /** Experimental peer labels: this forwarder's title pages for a title it verified from the source. */
    @Volatile private var titleAdvertiser: TitleAdvertiser? = null
    @Volatile private var discovery: BleL2capDiscovery? = null
    @Volatile private var child: ChildConnection? = null
    @Volatile private var childWriterThread: Thread? = null

    init {
        RelayUpstream.enter()
        require(streamToken != 0) { "A forwarded stream needs its discovery token" }
    }

    fun start() {
        if (rotateOnBoundary) RotatingDiscovery.addBoundaryListener(boundaryListener)
        startPresence()
    }

    /**
     * Rotating IDs (RV6, RV12): a boundary stops the offer, the viewer-request scan and the title pages,
     * stays silent for 1-8 s and starts them again on new sets with the new role-1 RDI. A forwarder with a
     * child advertises nothing, and its child link does not depend on any set. One rotation at a time.
     */
    private fun rotatePresence() {
        if (closed.get() || terminalSeen.get() || core.hasChild()) return
        if (!rotating.compareAndSet(false, true)) return
        thread(name = "ForwarderIdRotation") {
            try {
                stopPresence()
                Thread.sleep(RotatingDiscovery.boundarySilenceMs())
                // A start that began before the boundary may still be running and may publish a set of the old
                // epoch: let it finish, close whatever it left, then start on the new epoch.
                var waitedMs = 0
                while (connecting.get() && waitedMs < 5_000 && !closed.get()) {
                    Thread.sleep(100)
                    waitedMs += 100
                }
                stopPresence()
                startPresence()
                if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK rotid_restart: role=stream_forwarder")
            } catch (_: InterruptedException) {
            } finally {
                rotating.set(false)
            }
        }
    }

    fun onEnvelope(envelope: MeshEnvelope): RelayResult {
        val result = core.receive(envelope)
        if (BuildConfig.DEBUG && result.queuedChildren.isNotEmpty()) {
            // DEBUG setup timing: a joined child waits for a keyframe from upstream (its queue starts at one).
            val joinedAtNs = childJoinedAtNs.get()
            if (joinedAtNs != 0L && childJoinedAtNs.compareAndSet(joinedAtNs, 0L)) {
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK relay_child_join: event=first_media | waitMs=${(System.nanoTime() - joinedAtNs) / 1_000_000L} | " +
                        "audioOnly=${header.isAudioOnly}",
                )
            }
        }
        if (envelope.flags and MeshEnvelope.FLAG_TERMINAL != 0) {
            terminalSeen.set(true)
            stopPresence()
        }
        return result
    }

    fun awaitTerminalAck(timeoutMs: Long): Boolean {
        val active = child ?: return true
        return active.terminalAck.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * [congested]: this node could not receive and forward together (its own playback degraded
     * after the child joined); the relay ceiling is recorded so it is not retried at once.
     */
    fun withdrawForLocalCapacity(congested: Boolean = false) {
        if (congested) activeLinkLoad?.withdrawnForLocalCapacity()
        if (!withdrawing.compareAndSet(false, true)) return
        withdrawalReason = StreamRouteWithdrawal.LOCAL_CAPACITY
        thread(name = "ReceiverNodeWithdraw") { close() }
    }

    private fun startPresence() {
        if (closed.get() || terminalSeen.get() || core.hasChild() || !connecting.compareAndSet(false, true)) return
        thread(name = "ReceiverNodePresence") {
            try {
                if (closed.get() || terminalSeen.get() || core.hasChild()) return@thread
                val baseAnnouncement = DiscoveryAnnouncement(
                    nodeIdPrefix = identity.nodeId.toByteArray()
                        .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
                    flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE or
                        DiscoveryAnnouncement.FLAG_RELAY_CAPABLE or transport.discoveryFlag,
                    // The marker follows the authenticated header, never the parent's advertisement.
                    capabilitySummary = AudioLive.offerSummary(header, VideoCodecMap.summaryBit(header.codecId)),
                    streamToken = downstreamStreamToken,
                    routeCost = (routeCost + 1).coerceAtMost(0xff),
                    sequence = 0,
                    streamWidth = header.displayWidth,
                    streamHeight = header.displayHeight,
                    streamFps = header.fps,
                    streamBitrateKbps = streamBitrateKbps,
                )
                // Rotating IDs: a version 5 offer with the role-1 RDI (version 2 only inside a Visible-by-handle
                // window); the route token follows the advertised bytes and the previous one keeps its grace.
                val announcement = if (rotateOnBoundary && !RotatingDiscovery.visibleByHandle()) {
                    RotatingStreamAnnouncements.streamOffer(
                        baseAnnouncement,
                        RotatingDiscovery.advertise(RotatingDiscoveryIds.ROLE_STREAM_OFFER),
                        logicalStreamToken,
                    )
                } else if (rotateOnBoundary) {
                    baseAnnouncement.copy(
                        streamToken = StreamRouteToken.forAdvertiser(logicalStreamToken, baseAnnouncement.nodeIdPrefix),
                    )
                } else {
                    baseAnnouncement
                }
                downstreamStreamToken = announcement.streamToken
                if (rotateOnBoundary) requestTokens.advance(logicalStreamToken, announcement.streamToken, android.os.SystemClock.elapsedRealtime())
                Log.i(
                    "BluetoothVideo",
                    "Relay route tokens: upstream=${streamToken.toUInt().toString(16)}, " +
                        "logical=${logicalStreamToken.toUInt().toString(16)}, " +
                        "downstream=${downstreamStreamToken.toUInt().toString(16)}",
                )
                val newAdvertiser = BleDiscoveryAdvertiser(
                    adapter,
                    announcement,
                    onStatus,
                    startLowLatency = transport != BluetoothTransportOption.CLASSIC_RFCOMM,
                )
                newAdvertiser.start()
                if (closed.get() || terminalSeen.get() || core.hasChild()) {
                    newAdvertiser.close()
                    return@thread
                }
                synchronized(stateLock) {
                    // Never overwrite a live set: an overlapping start would leak it (and its scanner).
                    advertiser?.close()
                    discovery?.close()
                    titleAdvertiser?.close()
                    discovery = null
                    titleAdvertiser = null
                    advertiser = newAdvertiser
                }
                lateinit var newDiscovery: BleL2capDiscovery
                newDiscovery = BleL2capDiscovery(
                    adapter,
                    { peer ->
                        if (discovery !== newDiscovery || child != null) return@BleL2capDiscovery
                        // Default on (test_relay_hold_in_place=false is the control arm): a child this relay will not dial is
                        // dropped here, before the presence is stopped, so the offer and the scan are not restarted for every
                        // sighting.
                        if (deferChildSighting(peer)) return@BleL2capDiscovery
                        if (!connecting.compareAndSet(false, true)) return@BleL2capDiscovery
                        stopPresence()
                        connectChild(peer)
                    },
                    {},
                    { message ->
                        if (!closed.get()) {
                            onStatus("Forwarding discovery stopped: $message")
                            restartPresence()
                        }
                    },
                    { value ->
                        val transportMatches = value.transportOption() == transport
                        value.flags and DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS != 0 &&
                            requestTokens.accepts(value.streamToken, android.os.SystemClock.elapsedRealtime()) &&
                            transportMatches
                    },
                    scanDurationMs = 0L,
                    reportDuplicates = true,
                    // The middle is receiving live media on the same controller. A continuous
                    // low-latency LE scan for child requests stalled its upstream link for up to
                    // ~1 s and cost the source 278-965 dropped packets while a child was joining;
                    // balanced scanning still sees the child's continuous request advertisement.
                    continuousLowLatency = false,
                    startLowLatency = false,
                    acceptRotating = rotateOnBoundary,
                )
                discovery = newDiscovery
                newDiscovery.start()
                // Experimental title pages after child discovery is up (a first-use probe or a slow
                // start never delays it). Kept only while this presence is still current: a child,
                // close or restart during the blocking start would otherwise leak a running set.
                verifiedTitle()?.takeIf { PeerLabelFeature.titlePagesEnabled }?.let { title ->
                    runCatching { TitleAdvertiser(adapter, downstreamStreamToken, title, selfRotate = false) }.getOrNull()?.let { pages ->
                        if (pages.start() == android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                            val kept = synchronized(stateLock) {
                                (advertiser === newAdvertiser && !closed.get()).also { current ->
                                    if (current) titleAdvertiser = pages
                                }
                            }
                            if (!kept) pages.close()
                        }
                    }
                }
                onStatus("Forwarding is on. This receiver is available as the next node.")
            } catch (error: Exception) {
                if (!closed.get()) onStatus(
                    "Could not offer the received stream: ${error.message ?: error.javaClass.simpleName}",
                )
                stopPresence()
            } finally {
                connecting.set(false)
            }
        }
    }

    private fun restartPresence() {
        if (closed.get() || terminalSeen.get() || core.hasChild()) return
        stopPresence()
        startPresence()
    }

    private fun stopPresence() {
        if (rotateOnBoundary) RotatingDiscovery.offAir(RotatingDiscoveryIds.ROLE_STREAM_OFFER)
        synchronized(stateLock) {
            discovery?.close()
            discovery = null
            advertiser?.close()
            advertiser = null
            titleAdvertiser?.close()
            titleAdvertiser = null
        }
    }

    /** The source's stream title this phone verified (signed, bound to this stream), if any. */
    private fun verifiedTitle(): String? = forwardLabels?.invoke()
        ?.mapNotNull { runCatching { SignedLabelCodec.decode(it) }.getOrNull() }
        ?.firstOrNull { it.kind == LabelKind.STREAM_TITLE }
        ?.text

    private val dialDeferLog = DialDeferLog()

    /**
     * True when the relay holds in place ([RelayRecoveryConfig.holdInPlace]) and [peer] is marked by the establishment tracker
     * or inside the stall/refusal hold-off: the sighting is dropped ([RelayChildDialGate]) and logged with the same text
     * `connectChild` uses, at most once per [RelayChildDialGate.LOG_EVERY_MS].
     */
    private fun deferChildSighting(peer: BleL2capPeer): Boolean {
        val recovery = RelayChildRecoveryPolicy.live()
        if (!recovery.config.holdInPlace) return false
        val childKey = AdaptivePhyMemory.prefixLabel(peer.announcement.nodeIdPrefix)
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val decision = recovery.dialDecision(childKey, nowMs)
        if (decision.verdict == RelayChildDialGate.Verdict.DIAL) {
            dialDeferLog.reset(childKey)
            return false
        }
        val covered = dialDeferLog.onDeferred(childKey, nowMs)
        if (covered > 0) Log.i("BluetoothVideo", "${dialDeferLine(childKey, decision)} | held_in_place=true | sightings=$covered")
        return true
    }

    /** The line for a deferred dial: the same text in the discovery callback and at the start of `connectChild`. */
    private fun dialDeferLine(childKey: String, decision: RelayChildRecoveryPolicy.DialDecision): String =
        if (decision.verdict == RelayChildDialGate.Verdict.DEFER_MARKED) {
            "BENCHMARK relay_parent_fallback: reason=establishment | side=relay | child=$childKey | action=skip_connect"
        } else {
            "BENCHMARK relay_child_stall: child=$childKey | action=holdoff | remainingMs=${decision.heldMs}"
        }

    /** A7: what the child hop's carrier switch reports back to this forwarder. */
    private fun childHopOwner(sendLoad: HopSendLoad, connection: () -> ChildConnection?): HopOwner = object : HopOwner {
        override fun sendCarrierChanged(kind: CarrierKind) {
            LinkChangeEvents.carrierChanged()
            // The writer adds to this same object (A7 review P1-2): Wi-Fi bytes are never Bluetooth load.
            sendLoad.onSendCarrier(kind)
            connection()?.let { LinkPacerSeed.seed(it.pacer, transport, it.nodeId.shortLabel(), kind) }
        }

        override fun localKeyframeNeeded() {
            val active = connection() ?: return
            core.resyncChild(active.endpoint.peerNodeId)
            if (!header.isAudioOnly) onChildKeyFrameNeeded()
        }

        override fun peerKeyframeNeeded() = Unit

        override fun reassertState() = Unit

        override fun hopEnded(reason: String) {
            val active = connection() ?: return
            // A late end of a connection that is no longer the child (A7 review P3-2): close it, keep the new child.
            val current = child === active
            removeChild(active)
            if (current) restartPresence()
        }

        override fun bluetoothBudgetKbps(): Int? = LinkCapacity.measuredLinkKbps(transport, CarrierKind.BLUETOOTH)

        override fun targetKbps(): Int? = streamBitrateKbps
    }

    private fun connectChild(peer: BleL2capPeer) {
        val writerThread = thread(start = false, name = "ReceiverNodeChildConnect") {
            // One recovery policy for this attempt: the dial decision, the stall guard and the failure classification.
            val recovery = RelayChildRecoveryPolicy.live()
            var attemptPhy: BlePhyPreference? = null
            var hopAuthenticated = false
            // Set once the child link (socket / GATT) is up, before the hop handshake (Experimental relay_establish_keep_2m).
            var childLinkConnected = false
            val stalled = AtomicBoolean(false)
            var stallGuard: RelayChildStallGuard? = null
            var pending: ChildConnection? = null
            var pendingSocket: BluetoothSocket? = null
            var pendingGatt: GattVideoConnection? = null
            var pendingHop: HopLink? = null
            var childConnection: ChildConnection? = null
            try {
                if (closed.get() || terminalSeen.get()) return@thread
                val childKey = AdaptivePhyMemory.prefixLabel(peer.announcement.nodeIdPrefix)
                // A marked child (offer withdrawn: it times out and falls back to another parent) or one inside a stall or
                // refusal hold-off (still tearing down its dead link and receiver) is not dialled.
                val dial = recovery.dialDecision(childKey, android.os.SystemClock.elapsedRealtime())
                if (dial.verdict != RelayChildDialGate.Verdict.DIAL) {
                    Log.i("BluetoothVideo", dialDeferLine(childKey, dial))
                    return@thread
                }
                val device = peer.device
                // Classic: the shared connector authenticates the link while choosing a candidate.
                var classicSecure: SecureBluetoothSession? = null
                val socket = if (transport == BluetoothTransportOption.CLASSIC_RFCOMM) {
                    val connected = ClassicConnector.connect(
                        appContext,
                        adapter,
                        peer.device,
                        peer.announcement.nodeIdPrefix,
                        ClassicRole.STREAM_FORWARDER,
                        verifyLinkPrefix = true,
                        totalBudgetMs = CLASSIC_SESSION_BUDGET_MS,
                        allowInquiry = false,
                        isCancelled = { closed.get() || terminalSeen.get() },
                        onSocket = { pendingSocket = it },
                        handshake = { candidate ->
                            SecureBluetoothHandshake.initiate(
                                BufferedInputStream(candidate.inputStream, ioConfig.bufferBytes),
                                BufferedOutputStream(candidate.mediaOutputStream(forwarding = true), ioConfig.bufferBytes),
                                identity,
                                LocalNodeCapabilities.forTransport(transport, maxChildren = 1),
                                raiseTag = true,
                            )
                        },
                        onStatus = onStatus,
                    )
                    classicSecure = connected.session
                    connected.socket
                } else {
                    connectSocket(device, childLinkPhy(peer.announcement.nodeIdPrefix).also { attemptPhy = it })
                }
                pendingSocket = socket
                val control = if (transport == BluetoothTransportOption.BLE_L2CAP) {
                    pendingControlConnection
                } else {
                    null
                }
                val gattPriority = if (header.isAudioOnly && AudioLiveTunables.balancedLinkPriority) {
                    BleConnectionPriority.BALANCED // Experimental arm test_audio_live_link_priority=balanced
                } else {
                    forwarderGattPriority(
                        streamBitrateKbps,
                        debugGattConnectionPriority,
                        BuildConfig.DEBUG,
                    )
                }
                val gatt = if (transport == BluetoothTransportOption.BLE_GATT) {
                    GattVideoClient.open(
                        appContext,
                        device,
                        childLinkPhy(peer.announcement.nodeIdPrefix).also { attemptPhy = it },
                        gattPriority,
                        onStatus,
                        establishBudget = RelayRecoveryConfig.current().establishBudget,
                    )
                } else {
                    null
                }
                pendingGatt = gatt
                childLinkConnected = true
                DebugTunables.forwarderChildIntervalUnits?.let { LeChildInterval.requestLater(device.address, it) }
                val linkInput = gatt?.inputStream ?: socket?.inputStream
                    ?: error("Downstream Bluetooth input is unavailable")
                // Relay child stall guard (LE only): see RelayChildStallGuard.
                val guard = if (recovery.config.stallGuard && transport != BluetoothTransportOption.CLASSIC_RFCOMM) {
                    RelayChildStallGuard(android.os.SystemClock.elapsedRealtime())
                } else {
                    null
                }
                stallGuard = guard
                val rawInput = guard?.let { watched ->
                    ProgressInputStream(linkInput) { watched.onProgress(android.os.SystemClock.elapsedRealtime()) }
                } ?: linkInput
                guard?.let { watch ->
                    thread(name = "ReceiverNodeChildStallGuard", isDaemon = true) {
                        try {
                            while (watch.isOpen() && !closed.get() && !terminalSeen.get()) {
                                Thread.sleep(RelayChildStallGuard.POLL_MS)
                                val silentMs = watch.stalledFor(android.os.SystemClock.elapsedRealtime()) ?: continue
                                stalled.set(true)
                                recovery.onStallClosed(
                                    AdaptivePhyMemory.prefixLabel(peer.announcement.nodeIdPrefix),
                                    android.os.SystemClock.elapsedRealtime(),
                                )
                                Log.i(
                                    "BluetoothVideo",
                                    "BENCHMARK relay_child_stall: child=${AdaptivePhyMemory.prefixLabel(peer.announcement.nodeIdPrefix)} | " +
                                        "transport=${transport.shortLabel} | stage=${if (hopAuthenticated) "e2e" else "hop"} | " +
                                        "silentMs=$silentMs | action=close",
                                )
                                onStatus("The next phone's link carried nothing for ${silentMs / 1_000.0} s; closing it to try again.")
                                runCatching { pendingGatt?.close() }
                                runCatching { pendingSocket?.close() }
                                return@thread
                            }
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }
                val rawOutput = gatt?.outputStream ?: socket?.mediaOutputStream(forwarding = true)
                    ?: error("Downstream Bluetooth output is unavailable")
                val childLinkCapabilities = StreamHopOpening.linkCapabilities(
                    LocalNodeCapabilities.forTransport(transport, maxChildren = 1),
                    RotatingDiscoveryFeature.enabled,
                    transport,
                )
                val secure = classicSecure ?: SecureBluetoothHandshake.initiate(
                    BufferedInputStream(rawInput, ioConfig.bufferBytes),
                    BufferedOutputStream(rawOutput, ioConfig.bufferBytes),
                    identity,
                    childLinkCapabilities,
                    raiseTag = true,
                )
                if (peer.announcement.rotating) {
                    // RV15: the child's version 5 viewer request must open for the link node (role 2). This
                    // forwarder is the connector and sends its pinned role-1 entries.
                    val opening = LinkOpeningExchange.exchange(
                        secure,
                        childLinkCapabilities,
                        RotatingDiscovery.pinned(RotatingDiscoveryIds.ROLE_STREAM_OFFER),
                    )
                    val result = StreamHopOpening.check(
                        peer.announcement,
                        RotatingDiscoveryIds.ROLE_VIEWER_REQUEST,
                        secure.peerNodeId,
                        opening,
                    )
                    Log.i(
                        "BluetoothVideo",
                        (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotid_open: role=viewer | result=$result",
                    )
                    require(result == DiscoverySelectionCheck.Result.PASS) {
                        "Downstream identity changed between discovery and connection"
                    }
                } else {
                    if (RotatingDiscoveryFeature.enabled && transport != BluetoothTransportOption.CLASSIC_RFCOMM) {
                        // Both hellos may carry bit 3: the first record each way is a BDO1, read and ignored
                        // (the selected request is version 2, so the prefix check below applies).
                        LinkOpeningExchange.exchange(
                            secure,
                            childLinkCapabilities,
                            RotatingDiscovery.pinned(RotatingDiscoveryIds.ROLE_STREAM_OFFER),
                        )
                    }
                    require(
                        secure.peerNodeId.toByteArray()
                            .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
                            .contentEquals(peer.announcement.nodeIdPrefix),
                    ) { "Downstream identity changed between discovery and connection" }
                }
                hopAuthenticated = true
                recovery.onHopAuthenticated(AdaptivePhyMemory.prefixLabel(peer.announcement.nodeIdPrefix))
                val sendLoad = HopSendLoad { NodeLinkLoad.open(transport, LinkDirection.SEND) }
                val hop = HopLink(
                    role = HopCapsPlan.HopRole.STREAM_PARENT,
                    session = secure,
                    closeRaw = {
                        runCatching { gatt?.close() }
                        runCatching { socket?.close() }
                        runCatching { control?.close() }
                    },
                    anyLocalSwitchOn = WifiSwitchHub::anyOn,
                    log = WifiLog::emit,
                    owner = childHopOwner(sendLoad) { childConnection },
                    newAgent = ::newHopAgent,
                )
                pendingHop = hop
                AdaptivePhyMemory.bind(device.address, transport, secure.peerNodeId.shortLabel())
                // test_coc_phy_after_connect (default off): PHY only now, before the end-to-end handshake.
                control?.requestPhyAfterConnect("relay")
                val endpoint = EndToEndVideoHandshake.initiate(
                    secure,
                    identity,
                    PeerLabelFeature.advertise(
                        LocalNodeCapabilities.forTransport(transport, maxChildren = 1),
                        forwardLabels != null,
                    ),
                )
                require(endpoint.peerNodeId == secure.peerNodeId) {
                    "Downstream link and media identities do not match"
                }
                // Security review 2026-10-27 (item 14): no stream goes on to a blocked person through this phone.
                require(!PersonPolicy.blockedNode(endpoint.peerNodeId)) { "Downstream viewer refused" }
                guard?.let { done ->
                    recovery.onHandshakeComplete(AdaptivePhyMemory.prefixLabel(peer.announcement.nodeIdPrefix))
                    done.complete(android.os.SystemClock.elapsedRealtime())
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK relay_child_stall: child=${AdaptivePhyMemory.prefixLabel(peer.announcement.nodeIdPrefix)} | " +
                            "transport=${transport.shortLabel} | result=ok | handshakeMs=${done.handshakeMs()} | " +
                            "maxSilentMs=${done.maxSilentMs()}",
                    )
                }
                require(header.isAudioOnly || endpoint.peerCapabilities.decodeCodecMask and codecCapabilityMask(header.codecId) != 0) {
                    "Downstream child cannot decode this stream"
                }
                val grant = endpoint.sealer.seal(
                    EndToEndContentType.GROUP_KEY,
                    0,
                    ForwardedStreamGrantCodec.encode(
                        ForwardedStreamGrant(sourceNodeId, streamId, mediaKey),
                    ),
                )
                hop.writer.write(SecureRecordType.END_TO_END_CONTENT, grant)
                forwardLabels?.takeIf {
                    LabelRecords.streamHopNegotiated(true, secure.peerNodeId, endpoint.peerNodeId, endpoint.peerCapabilities)
                }?.let { provider ->
                    // R4: always one record (count 0 when nothing was verified upstream).
                    LabelRecords.write(hop.writer, endpoint.sealer, provider())
                }
                hop.writer.flush()
                // test_relay_upstream_dck (default off): a child that can relay gets DCK instead of HIGH on this
                // link, so its own HIGH child link has two events per event of this one.
                val childCanRelayLink = endpoint.peerCapabilities.maxChildren > 0
                val linkPriority = relayUpstreamPriority(
                    planned = gattPriority,
                    childCanRelay = childCanRelayLink,
                    sdkInt = android.os.Build.VERSION.SDK_INT,
                    enabled = DebugTunables.relayUpstreamDck,
                )
                gatt?.let { connection ->
                    onStatus(
                        "Downstream GATT ${linkPriority.label} priority retry " +
                            if (connection.requestConnectionPriority(linkPriority)) "queued" else "failed",
                    )
                }
                if (DebugTunables.relayUpstreamDck) {
                    val cocPlanned = DebugTunables.forwarderCocPriority ?: BleConnectionPriority.HIGH
                    val cocPriority = relayUpstreamPriority(
                        cocPlanned,
                        childCanRelayLink,
                        android.os.Build.VERSION.SDK_INT,
                        enabled = true,
                    )
                    val cocQueued = control != null && cocPriority != cocPlanned &&
                        control.requestConnectionPriority(cocPriority)
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK relay_upstream_priority: role=relay | transport=${transport.shortLabel} | " +
                            "childCanRelay=$childCanRelayLink | priority=" +
                            "${if (gatt != null) linkPriority.label else cocPriority.label}" +
                            if (control != null) " | queued=$cocQueued" else "",
                    )
                }
                val connection = ChildConnection(
                    endpoint.peerNodeId,
                    socket,
                    control,
                    gatt,
                    secure,
                    endpoint,
                    hop,
                )
                pending = connection
                childConnection = connection
                pendingHop = null
                pendingSocket = null
                pendingGatt = null
                pendingControlConnection = null
                if (terminalSeen.get()) throw IOException("The upstream stream already ended")
                val bootstrap = core.addChild(endpoint.peerNodeId)
                if (BuildConfig.DEBUG) childJoinedAtNs.set(System.nanoTime())
                if (closed.get()) throw IOException("Forwarding was turned off")
                child = connection
                onChildCountChanged(1)
                var lastSequence = -1L
                bootstrap.forEach { envelope ->
                    hop.writer.write(SecureRecordType.MESH_ENVELOPE, MeshEnvelopeCodec.encode(envelope))
                    lastSequence = envelope.sequence
                }
                hop.writer.flush()
                // Pace the child link from join; its first arrival report replaces the seed.
                LinkPacerSeed.seed(connection.pacer, transport, connection.nodeId.shortLabel())?.let { kbps ->
                    Log.i("BluetoothVideo", "Child link pacer seeded at $kbps kbps until the child's first report (${transport.shortLabel})")
                    if (DebugTunables.joinProbeSeed && connection.pacer.beginJoinProbe()) {
                        Log.i("BluetoothVideo", "Join probe: the child link is unpaced for up to 1.5 s or 32 KiB so the child can time it")
                    }
                }
                startChildControlReader(connection)
                startChildHeartbeatWatchdog(connection)
                // An audio-only stream has no keyframes: a joining child never waits for one.
                if (!header.isAudioOnly) onChildKeyFrameNeeded()
                onStatus(
                    if (header.isAudioOnly) {
                        "Forwarding the original encrypted audio-only stream to ${endpoint.peerNodeId.shortLabel()} " +
                            "without decoding or re-encoding the forwarded copy."
                    } else {
                        "Forwarding the original encrypted ${header.displayWidth}x${header.displayHeight} stream " +
                            "to ${endpoint.peerNodeId.shortLabel()} without decoding or re-encoding the forwarded copy."
                    },
                )
                val childHealthPolicy = RelayChildHealthPolicy(sheddingActive = boundedMediaAge)
                // Experimental relay child thinning: one controller per child; ticked by the stall watch below.
                val layerController = if (relayChildThinning) {
                    RelayChildLayerController(
                        readStats = { core.childLayerStats(endpoint.peerNodeId) },
                        applyLevel = { level -> core.setChildLayerLevel(endpoint.peerNodeId, level) },
                    )
                } else {
                    null
                }
                var lastLayerLogNs = 0L
                // Audio-only: more than 20% of the child's records shed over 10 s, or a record older than 30 s.
                fun childShouldWithdraw(backlogAgeMs: Long, nowNs: Long): Boolean =
                    if (header.isAudioOnly) {
                        core.audioVerdict(endpoint.peerNodeId, nowNs / 1_000_000L).also { reason ->
                            if (reason != null) {
                                Log.i(
                                    "BluetoothVideo",
                                    "BENCHMARK audio_live_withdraw: role=relay_tx | reason=$reason | backlogMs=$backlogAgeMs | " +
                                        "shed=${core.audioShedRecords(endpoint.peerNodeId)}",
                                )
                            }
                        } != null
                    } else {
                        childHealthPolicy.shouldWithdraw(backlogAgeMs, nowNs)
                    }
                val congestionWithdrawn = AtomicBoolean(false)
                fun withdrawCongested(queueDepth: Int, backlogAgeMs: Long, thinnedFrames: Long, stalled: Boolean) {
                    if (!congestionWithdrawn.compareAndSet(false, true)) return
                    activeLinkLoad?.withdrawnForCongestion()
                    Log.w(
                        "BluetoothVideo",
                        "Withdrawing congested downstream route: $queueDepth queued, " +
                            "$backlogAgeMs ms behind live " + (if (thinDiscardable) {
                                "($thinnedFrames experimental discardable frames thinned)"
                            } else {
                                "(nothing was dropped)"
                            }) + if (stalled) " | write stalled" else "",
                    )
                    onStatus(
                        "Forwarding stopped because this immediate child link could not keep up. " +
                            "Local viewing and the source stream continue.",
                    )
                    withdrawForLocalCapacity()
                }
                var lastThinned = 0L
                val writeLatency = RollingLatencyStats()
                val flushLatency = RollingLatencyStats()
                val metricsStartedNs = System.nanoTime()
                var lastMetricsNs = metricsStartedNs
                val timeQueueGate = TimeQueueLogGate()
                var bytesWritten = 0L
                val deliveredBytes = AtomicLong(0)
                val starvation = RelayChildStarvation()
                // Q31 follow-up (experimental test_relay_drain_sustained): this relay's own sustained delivery to the
                // child, for the time queue's drain when the child's arrival trains are too short to report one.
                // Always measured and logged; the switch only decides whether it feeds the drain.
                val sustainedMeter = SustainedDeliveryMeter()
                var pushedSustainedKbps = 0
                val paceHeadOnly = childQueuePolicy.paceHeadOnly
                var paceHeldNs = 0L
                var paceExemptWrites = 0L
                var paceExemptWaitNs = 0L
                val linkLoad = sendLoad.also {
                    it.start()
                    activeLinkLoad = it
                }
                // The writer judges the child only after a write returns, and a write into a stalled
                // link can block for many seconds (mesh4-7a6c401c 0001: ASUS -> M31 CoC at 8 kbps was
                // withdrawn 12.7 s behind live, M31 -> A15s likewise). Judge the same policy on a
                // timer too, so a stalled child is released within the policy's own limits.
                val stallWatch = thread(name = "ReceiverNodeChildStall") {
                    try {
                        while (!closed.get() && child === connection && !congestionWithdrawn.get()) {
                            Thread.sleep(STALL_CHECK_INTERVAL_MS)
                            var backlogAgeMs = core.backlogAgeMs(endpoint.peerNodeId)
                            val tickNs = System.nanoTime()
                            // A blocked write silences the writer's own line: log from this timer on a change of drain
                            // source or overload, and at least every 10 s while the child queue is non-empty.
                            if (BuildConfig.DEBUG) core.timeQueueState(endpoint.peerNodeId)?.let { (source, overload) ->
                                val trigger = timeQueueGate.trigger(
                                    tickNs, core.queueDepth(endpoint.peerNodeId) > 0, source, overload,
                                )
                                if (trigger != null) core.timeQueueSummary(endpoint.peerNodeId)?.let { summary ->
                                    Log.i(
                                        "BluetoothVideo",
                                        "BENCHMARK relay_time_queue: role=relay_tx | peer=${endpoint.peerNodeId.shortLabel()} | " +
                                            "trigger=$trigger | $summary",
                                    )
                                }
                            }
                            if (layerController != null) {
                                val report = layerController.onTick(
                                    tickNs / 1_000_000L,
                                    deliveredBytes.get(),
                                    backlogAgeMs,
                                    connection.linkLate,
                                )
                                if (report != null) {
                                    val changed = report.decision.changed
                                    if (changed || tickNs - lastLayerLogNs >= METRICS_INTERVAL_NS) {
                                        lastLayerLogNs = tickNs
                                        Log.i(
                                            "BluetoothVideo",
                                            relayChildLayerLine(
                                                endpoint.peerNodeId.shortLabel(),
                                                if (changed) "change" else "window",
                                                report,
                                            ),
                                        )
                                    }
                                    // The level change purged queued video: judge the child on the new queue.
                                    if (changed) backlogAgeMs = core.backlogAgeMs(endpoint.peerNodeId)
                                }
                            }
                            val starvedKbps = if (reanchorEnabled) {
                                starvation.onTick(tickNs, deliveredBytes.get(), backlogAgeMs)
                            } else {
                                null
                            }
                            val attempt = starvedKbps?.let {
                                reanchorBudget.tryAttempt(endpoint.peerNodeId.toString(), tickNs)
                            }
                            if (starvedKbps != null && attempt != null) {
                                // A new connection draws a new anchor; the child keeps its identity
                                // and reconnects through its normal link-loss recovery.
                                Log.i(
                                    "BluetoothVideo",
                                    "BENCHMARK relay_reanchor: child=${endpoint.peerNodeId.shortLabel()} | " +
                                        "attempt=$attempt | kbpsBefore=$starvedKbps | backlogMs=$backlogAgeMs",
                                )
                                onStatus("The next phone's link was starved; reconnecting it on a fresh connection.")
                                reanchorClosedChild.set(true)
                                removeChild(connection)
                                restartPresence()
                                return@thread
                            }
                            if (childShouldWithdraw(backlogAgeMs, tickNs)) {
                                withdrawCongested(
                                    core.queueDepth(endpoint.peerNodeId),
                                    backlogAgeMs,
                                    core.thinnedFrames(endpoint.peerNodeId),
                                    stalled = true,
                                )
                                return@thread
                            }
                        }
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                val flushRunning = AtomicBoolean(ioConfig.flushPolicy.periodicFlushMs > 0)
                val flushPending = AtomicBoolean(false)
                val pendingFlushBytes = AtomicLong(0)
                val flushFailure = AtomicReference<Exception?>(null)
                val flushSignal = Object()

                fun flushNow() {
                    flushPending.set(false)
                    pendingFlushBytes.set(0)
                    val startedNs = System.nanoTime()
                    hop.writer.flush()
                    flushLatency.recordNanos(System.nanoTime() - startedNs)
                }

                fun accountWrite(wireBytes: Int, force: Boolean) {
                    flushFailure.get()?.let { throw IOException("Downstream flush failed", it) }
                    val pendingBytes = pendingFlushBytes.addAndGet(wireBytes.toLong())
                    if (
                        force || ioConfig.flushPolicy == VideoFlushPolicy.EVERY_FRAME ||
                        pendingBytes >= ioConfig.flushPolicy.byteThreshold
                    ) {
                        flushNow()
                    } else if (flushPending.compareAndSet(false, true)) {
                        synchronized(flushSignal) { flushSignal.notifyAll() }
                    }
                }

                val flushThread = if (ioConfig.flushPolicy.periodicFlushMs > 0) {
                    thread(name = "ReceiverNodeChildFlush") {
                        try {
                            while (flushRunning.get()) {
                                synchronized(flushSignal) {
                                    while (flushRunning.get() && !flushPending.get()) flushSignal.wait()
                                }
                                if (!flushRunning.get()) break
                                Thread.sleep(ioConfig.flushPolicy.periodicFlushMs)
                                if (flushPending.compareAndSet(true, false)) flushNow()
                            }
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                        } catch (error: Exception) {
                            flushFailure.compareAndSet(null, error)
                        }
                    }
                } else {
                    null
                }
                try {
                    while (!closed.get() && child === connection) {
                        if (DebugTunables.joinProbeSeed) {
                            connection.pacer.expireJoinProbe()?.let {
                                Log.i("BluetoothVideo", it.benchmarkLine("relay_tx", connection.nodeId.shortLabel()))
                            }
                        }
                        // Audio is never held for link credit. The child queue is strictly ordered, so a
                        // video record ahead of queued audio is paced like any other (relayPaceHeadOnly);
                        // the earlier any-audio-queued test skipped the pacer for the whole backlog.
                        val credit = awaitLinkCredit(
                            connection.pacer,
                            audioExempt = {
                                if (paceHeadOnly) core.headIsAudio(endpoint.peerNodeId)
                                else core.hasQueuedAudio(endpoint.peerNodeId)
                            },
                            running = { !closed.get() },
                        )
                        paceHeldNs += credit.sleptNs
                        if (credit.exemptedWaitNs > 0L) {
                            paceExemptWrites++
                            paceExemptWaitNs += credit.exemptedWaitNs
                        }
                        val envelope = core.take(endpoint.peerNodeId) ?: break
                        if (envelope.sequence <= lastSequence) continue
                        val encodedEnvelope = MeshEnvelopeCodec.encode(envelope)
                        val writeStartedNs = System.nanoTime()
                        val wireBytes = hop.writer.write(
                            SecureRecordType.MESH_ENVELOPE,
                            encodedEnvelope,
                        )
                        connection.pacer.onSent(wireBytes, writeStartedNs)
                        if (pacerConfig.drainGuard) {
                            connection.pacer.noteWrite(credit.sleptNs, System.nanoTime() - writeStartedNs)
                        }
                        val terminal = envelope.flags and MeshEnvelope.FLAG_TERMINAL != 0
                        accountWrite(wireBytes, force = terminal)
                        writeLatency.recordNanos(System.nanoTime() - writeStartedNs)
                        bytesWritten += wireBytes
                        deliveredBytes.addAndGet(wireBytes.toLong())
                        linkLoad.add(wireBytes)
                        lastSequence = envelope.sequence
                        val nowNs = System.nanoTime()
                        val queueDepth = core.queueDepth(endpoint.peerNodeId)
                        val backlogAgeMs = core.backlogAgeMs(endpoint.peerNodeId)
                        sustainedMeter.onWritten(
                            nowNs,
                            wireBytes,
                            queueDepth > 0,
                            credit.sleptNs,
                            // The Experimental lead cap held this writer: keep the last drain instead of letting it expire (B3).
                            leadCapped = credit.sleptNs > 0L && connection.pacer.leadCapMs() > 0L,
                        )
                        val sustainedKbps = sustainedMeter.estimateKbps(nowNs) ?: 0
                        if (sustainedDrainChanged(pushedSustainedKbps, sustainedKbps)) {
                            pushedSustainedKbps = sustainedKbps
                            core.setChildSustainedDrainKbps(endpoint.peerNodeId, sustainedKbps)
                        }
                        // Experimental temporal layering: thinning means this child link fell behind
                        // (capacity evidence for this relay only; always 0 while the experiment is off).
                        val thinnedFrames = core.thinnedFrames(endpoint.peerNodeId)
                        val thinnedNow = thinnedFrames > lastThinned
                        lastThinned = thinnedFrames
                        if (backlogAgeMs >= LinkRateController.CONGESTED_BACKLOG_MS ||
                            linkLoadSaturatedByChild.getAndSet(false) || thinnedNow
                        ) {
                            linkLoad.saturated()
                        }
                        if (congestionWithdrawn.get()) break
                        if (childShouldWithdraw(backlogAgeMs, nowNs)) {
                            withdrawCongested(queueDepth, backlogAgeMs, thinnedFrames, stalled = false)
                            break
                        }
                        if (nowNs - lastMetricsNs >= METRICS_INTERVAL_NS) {
                            val seconds = (nowNs - metricsStartedNs) / 1_000_000_000.0
                            val latency = writeLatency.snapshot()
                            val flush = flushLatency.snapshot()
                            Log.i(
                                "BluetoothVideo",
                                ("Relay ${transport.shortLabel} TX: %.0f kbps | write p50/p95/p99/max " +
                                    "%.1f/%.1f/%.1f/%.1f ms | flush p95 %.1f ms | queue %d | backlog %d ms").format(
                                        bytesWritten * 8 / seconds / 1_000,
                                        latency.p50Ms,
                                        latency.p95Ms,
                                        latency.p99Ms,
                                        latency.maxMs,
                                        flush.p95Ms,
                                        queueDepth,
                                        backlogAgeMs,
                                    ) + if (thinDiscardable) " | thinned $thinnedFrames" else "",
                            )
                            if (BuildConfig.DEBUG) core.mediaAgeSummary(endpoint.peerNodeId)?.let { summary ->
                                Log.i(
                                    "BluetoothVideo",
                                    "BENCHMARK media_age: role=relay_tx | policy=${BoundedMediaAge.policyLabel(boundedMediaAge)} | " +
                                        "peer=${endpoint.peerNodeId.shortLabel()} | backlogMs=$backlogAgeMs | $summary",
                                )
                            }
                            if (BuildConfig.DEBUG) core.timeQueueSummary(endpoint.peerNodeId)?.let { summary ->
                                core.timeQueueState(endpoint.peerNodeId)?.let { (source, overload) ->
                                    timeQueueGate.noteLogged(nowNs, source, overload)
                                }
                                Log.i(
                                    "BluetoothVideo",
                                    "BENCHMARK relay_time_queue: role=relay_tx | peer=${endpoint.peerNodeId.shortLabel()} | $summary",
                                )
                            }
                            if (BuildConfig.DEBUG) {
                                val pacer = connection.pacer
                                Log.i(
                                    "BluetoothVideo",
                                    "BENCHMARK relay_pacing: headOnly=$paceHeadOnly | leadCap=${childQueuePolicy.leadCap} | " +
                                        "capMs=${pacer.leadCapMs()} | paceKbps=${pacer.bytesPerSecond * 8 / 1_000} | " +
                                        "measured=${pacer.measured} | paceLeadMs=${pacer.paceLeadMs()} | " +
                                        "modelLeadMs=${pacer.modelLeadMs()} | childLate=${connection.linkLate} | " +
                                        "heldMs=${paceHeldNs / 1_000_000L} | exemptWrites=$paceExemptWrites | " +
                                        "exemptWaitMs=${paceExemptWaitNs / 1_000_000L} | backlogMs=$backlogAgeMs",
                                )
                                paceHeldNs = 0L
                                paceExemptWrites = 0L
                                paceExemptWaitNs = 0L
                            }
                            lastMetricsNs = nowNs
                        }
                        if (terminal) {
                            connection.terminalAck.await(TERMINAL_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            return@thread
                        }
                    }
                } finally {
                    stallWatch.interrupt()
                    linkLoad.close()
                    flushRunning.set(false)
                    synchronized(flushSignal) { flushSignal.notifyAll() }
                    flushThread?.interrupt()
                    runCatching { flushThread?.join(500L) }
                    if (flushPending.get()) runCatching { flushNow() }
                }
                withdrawalReason?.let { reason ->
                    hop.sendHopControl(
                        SecureRecordType.ROUTE_CONTROL,
                        StreamRouteControlProtocol.encode(reason),
                    )
                    hop.writer.flush()
                    if (!connection.routeWithdrawalAck.await(
                            ROUTE_WITHDRAWAL_ACK_TIMEOUT_MS,
                            TimeUnit.MILLISECONDS,
                        )
                    ) {
                        Log.w("BluetoothVideo", "Downstream route-withdrawal acknowledgement timed out")
                    }
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                if (!closed.get() && !terminalSeen.get()) {
                    // The attempt is classified once (RelayChildRecoveryPolicy): the tracker, the refusal hold-off and the PHY
                    // after the failure all follow from that one classification.
                    val childKey = AdaptivePhyMemory.prefixLabel(peer.announcement.nodeIdPrefix)
                    val outcome = recovery.onAttemptFailed(
                        childKey,
                        android.os.SystemClock.elapsedRealtime(),
                        hopAuthenticated,
                        error.message,
                        stalled.get(),
                        budgetExhausted = error is EstablishmentBudgetExhausted,
                        attemptPhy = attemptPhy,
                        reachedConnection = childLinkConnected || error is LinkReachedSetupFailure,
                    )
                    val stallKind = outcome.kind == RelayChildFailureKind.STALL
                    if (outcome.marked) {
                        Log.i(
                            "BluetoothVideo",
                            "BENCHMARK relay_parent_fallback: reason=establishment | side=relay | child=$childKey | " +
                                "action=withdraw_offer | kind=${if (stallKind) "stall" else "refusal"} | " +
                                "failures=${if (stallKind) EstablishmentFailureTracker.STALL_THRESHOLD else EstablishmentFailureTracker.THRESHOLD} | " +
                                "markMs=${outcome.markMs}",
                        )
                    } else if (outcome.counted && stallKind) {
                        Log.i(
                            "BluetoothVideo",
                            "BENCHMARK relay_parent_fallback: reason=establishment | side=relay | child=$childKey | " +
                                "action=count_stall | kind=stall",
                        )
                    }
                    val setupPhy = outcome.setupPhy
                    if (setupPhy != RelayChildSetupPhy.UNCHANGED) {
                        val node = AdaptivePhyMemory.prefixLabel(peer.announcement.nodeIdPrefix)
                        val errorName = ((error as? LinkReachedSetupFailure)?.cause ?: error).javaClass.simpleName
                        if (setupPhy == RelayChildSetupPhy.PIN_1M) twoMSetupFailures += node
                        Log.i(
                            "BluetoothVideo",
                            "BENCHMARK link_phy: link=relay | node=$node | requested=2M | effective=failed | " +
                                if (setupPhy == RelayChildSetupPhy.PIN_1M) {
                                    "reason=setup_failed | next=1M | error=$errorName"
                                } else {
                                    "reason=establishment_failed | next=2M | error=$errorName"
                                },
                        )
                    }
                    val cause = error.message ?: error.javaClass.simpleName
                    onStatus(
                        if (reanchorClosedChild.get()) {
                            // ASUS 2026-10-10 09:52:08: the re-anchor's own close surfaced as "Downstream flush failed".
                            "Downstream node re-anchored: its starved link was closed so it reconnects on a fresh connection ($cause)"
                        } else {
                            "Downstream node disconnected: $cause"
                        },
                    )
                }
            } finally {
                // A failed attempt must not leave its watchdog polling a link that is already closed.
                stallGuard?.complete(android.os.SystemClock.elapsedRealtime())
                reanchorClosedChild.set(false)
                pending?.let(::removeChild)
                runCatching { pendingHop?.close() }
                runCatching { pendingGatt?.close() }
                runCatching { pendingSocket?.close() }
                pendingControlConnection?.let {
                    runCatching { it.close() }
                    pendingControlConnection = null
                }
                connecting.set(false)
                restartPresence()
                if (childWriterThread === Thread.currentThread()) childWriterThread = null
            }
        }
        childWriterThread = writerThread
        writerThread.start()
    }

    @Volatile private var pendingControlConnection: BleL2capControlConnection? = null

    @SuppressLint("MissingPermission")
    /** Adaptive PHY memory: 1M from the start for a child remembered bad on 2M (the child is the hop). */
    private fun childLinkPhy(childPrefix: ByteArray): BlePhyPreference {
        if (transport == BluetoothTransportOption.CLASSIC_RFCOMM) return phyPreference
        val node = AdaptivePhyMemory.prefixLabel(childPrefix)
        if (DebugTunables.phySetupStallGuard && node in twoMSetupFailures) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK link_phy: link=relay | node=$node | requested=1M | effective=pending | reason=setup_retry",
            )
            return BlePhyPreference.ONE_M_ADAPTIVE
        }
        return AdaptivePhyMemory.phyFor(transport, childPrefix, phyPreference, "relay")
    }

    /**
     * Children whose 2M child-link setup failed before hop authentication in this forwarding session.
     * Hop-local and session-only: a relay's child link is not bad-2M memory evidence (relay guard).
     */
    private val twoMSetupFailures: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @SuppressLint("MissingPermission")
    private fun connectSocket(device: BluetoothDevice, linkPhy: BlePhyPreference = phyPreference): BluetoothSocket? = when (transport) {
        BluetoothTransportOption.CLASSIC_RFCOMM -> error("Classic children connect through ClassicConnector")
        BluetoothTransportOption.BLE_L2CAP -> {
            try {
                BleCocClient.connect(
                    appContext,
                    device,
                    onStatus,
                    linkPhy,
                    "forwarder-connect",
                    DebugTunables.forwarderCocPriority ?: BleConnectionPriority.HIGH,
                    establishBudget = RelayRecoveryConfig.current().establishBudget,
                ) {
                    pendingControlConnection = it
                }
            } catch (error: Exception) {
                pendingControlConnection?.close()
                pendingControlConnection = null
                throw error
            }
        }
        BluetoothTransportOption.BLE_GATT -> null
    }

    private fun startChildControlReader(connection: ChildConnection) {
        connection.controlThread = thread(name = "ReceiverNodeChildControl") {
            try {
                while (
                    (!closed.get() && child === connection) ||
                    (withdrawalReason != null && connection.routeWithdrawalAck.count > 0L)
                ) {
                    val record = connection.hop.reader.read()
                    when (record.type) {
                        SecureRecordType.LINK_HEARTBEAT -> {
                            // A delivery-only report repeats the last full report (this relay does not use delivery).
                            SecureLinkControlProtocol.requireHeartbeat(record)?.takeUnless { it.deliveryOnly }?.let { report ->
                                // The child's own link is late: capacity evidence for this relay only.
                                if (report.linkLate) linkLoadSaturatedByChild.set(true)
                                connection.linkLate = report.linkLate
                                connection.pacer.setLeadCapNs(childQueuePolicy.leadCapNs(report.linkLate))
                                core.setChildAddedDelayMs(connection.endpoint.peerNodeId, report.addedDelayMs)
                                // On-time report from an unmeasured child: hold the seeded pace (PacerSeedPolicy, LinkPacer.holdSeed).
                                if (report.arrivalKbps <= 0 && !report.linkLate && pacerConfig.onTimeReports) {
                                    val peer = connection.endpoint.peerNodeId
                                    PacerSeedPolicy.onTimeReport(
                                        connection.pacer,
                                        pacerConfig,
                                        backlogMs = { core.backlogAgeMs(peer) },
                                        droppedMedia = { core.droppedMedia(peer) },
                                    )?.let { hold ->
                                        if (hold.raised) Log.i("BluetoothVideo", hold.raiseLine("relay_tx", peer.shortLabel()))
                                        hold.guardBlock?.let {
                                            Log.i("BluetoothVideo", it.benchmarkLine("relay_tx", connection.nodeId.shortLabel(), hold.toKbps))
                                        }
                                    }
                                }
                                // Experimental join probe: the child's first rate within the window seeds the pace.
                                val joinProbe = if (report.arrivalKbps > 0 && DebugTunables.joinProbeSeed) {
                                    connection.pacer.resolveJoinProbe(
                                        report.arrivalKbps,
                                        core.backlogAgeMs(connection.endpoint.peerNodeId) >= LinkPacer.SEED_RAISE_BACKLOG_MS,
                                    )?.also { Log.i("BluetoothVideo", it.benchmarkLine("relay_tx", connection.nodeId.shortLabel())) }
                                } else {
                                    null
                                }
                                // Not backlogged: the burst is only a lower bound, so it seeds the pace and is not a measurement.
                                if (report.arrivalKbps > 0 && joinProbe?.linkBound != false) {
                                    core.setChildDrainKbps(connection.endpoint.peerNodeId, report.arrivalKbps)
                                    if (learnsBluetoothCapacity(connection.hop.acceptKind, connection.hop.sendKind)) {
                                        LinkCapacity.recordProbe(transport, report.arrivalKbps)
                                        LinkCapacity.recordPeerLinkKbps(
                                            transport,
                                            connection.nodeId.shortLabel(),
                                            report.arrivalKbps,
                                        )
                                    }
                                    if (DebugTunables.linkPacing) {
                                        connection.pacer.setMeasuredLinkKbps(report.arrivalKbps)
                                    }
                                }
                            }
                            connection.lastHeartbeatReceivedNs.set(System.nanoTime())
                        }
                        SecureRecordType.ROUTE_CONTROL_ACK -> {
                            require(
                                StreamRouteControlProtocol.decode(record.payload) == withdrawalReason,
                            ) { "Unexpected route-withdrawal acknowledgement" }
                            connection.routeWithdrawalAck.countDown()
                            return@thread
                        }
                        SecureRecordType.END_TO_END_CONTENT -> {
                            val opened = connection.endpoint.opener.open(record.payload)
                            require(opened.payload.isEmpty()) { "Invalid downstream control payload" }
                            when (opened.type) {
                                // Audio only: no keyframes exist, so the request is ignored, never sent upstream.
                                EndToEndContentType.KEY_FRAME_REQUEST -> if (!header.isAudioOnly) onChildKeyFrameNeeded()
                                EndToEndContentType.STREAM_END_ACK -> {
                                    connection.terminalAck.countDown()
                                    return@thread
                                }
                                else -> error("Unexpected downstream control ${opened.type}")
                            }
                        }
                        else -> error("Unexpected downstream link record ${record.type}")
                    }
                }
            } catch (error: Exception) {
                if (!closed.get() && child === connection && !terminalSeen.get()) {
                    onStatus("Downstream control stopped: ${error.message ?: error.javaClass.simpleName}")
                    removeChild(connection)
                    restartPresence()
                }
            }
        }
    }

    private fun startChildHeartbeatWatchdog(connection: ChildConnection) {
        connection.heartbeatThread = thread(name = "ReceiverNodeChildHeartbeat") {
            try {
                val timeoutNs = receiverHeartbeatTimeoutNs(transport)
                while (!closed.get() && child === connection) {
                    Thread.sleep(receiverHeartbeatIntervalMs(transport))
                    if (System.nanoTime() - connection.lastHeartbeatReceivedNs.get() < timeoutNs) {
                        continue
                    }
                    onStatus("Downstream node stopped responding; reopening this relay slot.")
                    removeChild(connection)
                    restartPresence()
                    return@thread
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun removeChild(connection: ChildConnection) {
        detachChild(connection)
        connection.close()
        pendingControlConnection = null
    }

    private fun detachChild(connection: ChildConnection) {
        if (child !== connection) return
        child = null
        core.removeChild(connection.nodeId)
        onChildCountChanged(0)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        RelayUpstream.exit()
        if (rotateOnBoundary) RotatingDiscovery.removeBoundaryListener(boundaryListener)
        stopPresence()
        val activeChild = child
        activeChild?.let(::detachChild)
        val activeWriter = childWriterThread
        if (activeWriter != null && activeWriter !== Thread.currentThread()) {
            // Review S3: an upstream loss (no terminal record, no withdrawal) closes the child link at once.
            val timeoutMs = ForwarderChildClose.writerWaitMs(
                withdrawing = withdrawalReason != null,
                terminalForwarded = terminalSeen.get(),
                withdrawalAckMs = ROUTE_WITHDRAWAL_ACK_TIMEOUT_MS + 1_000L,
                gracefulMs = GRACEFUL_CHILD_CLOSE_TIMEOUT_MS,
            )
            if (timeoutMs > 0L) runCatching { activeWriter.join(timeoutMs) }
        }
        activeChild?.close()
        pendingControlConnection?.close()
        pendingControlConnection = null
        mediaKey.fill(0)
    }

    private class ChildConnection(
        val nodeId: NodeId,
        private val socket: BluetoothSocket?,
        private val control: BleL2capControlConnection?,
        private val gatt: GattVideoConnection?,
        val secure: SecureBluetoothSession,
        val endpoint: EndToEndVideoSession,
        val hop: HopLink,
    ) : Closeable {
        val terminalAck = CountDownLatch(1)
        val routeWithdrawalAck = CountDownLatch(1)
        val lastHeartbeatReceivedNs = AtomicLong(System.nanoTime())
        /** Paces this child link at the rate the child measured (see LinkPacer). */
        val pacer = LinkPacer()
        /** The child's last full report said its playout is pinned at the maximum and media is still late. */
        @Volatile var linkLate = false
        @Volatile var controlThread: Thread? = null
        @Volatile var heartbeatThread: Thread? = null
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            controlThread?.interrupt()
            heartbeatThread?.interrupt()
            runCatching { hop.close() }
            runCatching { endpoint.close() }
            runCatching { gatt?.close() }
            runCatching { socket?.close() }
            runCatching { control?.close() }
        }
    }

    private val BluetoothTransportOption.discoveryFlag: Int
        get() = when (this) {
            BluetoothTransportOption.CLASSIC_RFCOMM -> DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT
            BluetoothTransportOption.BLE_GATT -> DiscoveryAnnouncement.FLAG_GATT_TRANSPORT
            BluetoothTransportOption.BLE_L2CAP -> 0
        }

    private companion object {
        const val METRICS_INTERVAL_NS = 5_000_000_000L
        /** How often a relay child's backlog is judged while its writer may be blocked. */
        const val STALL_CHECK_INTERVAL_MS = 500L
        const val GRACEFUL_CHILD_CLOSE_TIMEOUT_MS = 2_000L
        const val ROUTE_WITHDRAWAL_ACK_TIMEOUT_MS = 4_000L
        const val TERMINAL_ACK_TIMEOUT_MS = 2_000L
    }
}

/**
 * A relay's child-link attempt that failed before hop authentication on a 2M request is retried on 1M
 * for that child (mesh4-c0200edf-loop7-1 0004: the ASUS relay's CoC connect to the A15s failed within
 * 6 ms while its 2M PHY update was in flight, twice; the same pair's call link dies after the 2M PHY
 * update, ec20607). The child re-advertises and is rediscovered under its current address. DEBUG
 * `test_phy_setup_stall=false` is the control arm.
 */
internal fun relayChildSetupRetriesOn1M(
    guardOn: Boolean,
    attemptPhy: BlePhyPreference?,
    hopAuthenticated: Boolean,
    keepOnEstablishment: Boolean = false,
    reachedConnection: Boolean = true,
): Boolean = relayChildSetupPhyDecision(guardOn, attemptPhy, hopAuthenticated, keepOnEstablishment, reachedConnection) ==
    RelayChildSetupPhy.PIN_1M

/** What a failed relay child setup does to that child's PHY for the rest of the session. */
internal enum class RelayChildSetupPhy { UNCHANGED, PIN_1M, KEEP_2M_ESTABLISHMENT }

/**
 * [relayChildSetupRetriesOn1M] with the Experimental establishment exemption (DebugTunables.relayEstablishKeep2M,
 * [keepOnEstablishment]): a failure before any connection to the child existed ([reachedConnection] false) keeps the 2M
 * request, because connection establishment runs on the advertising PHY and 1M would not change it (lane15 chain2
 * 19:37:46: status 133 at the connection callback, state 0, 296 ms, then 1M for the whole stream). A failure after the
 * link existed (connected, 2M PHY update requested or in flight) is the protected case above and still pins 1M.
 * With [keepOnEstablishment] false the result is exactly the pre-switch rule.
 */
internal fun relayChildSetupPhyDecision(
    guardOn: Boolean,
    attemptPhy: BlePhyPreference?,
    hopAuthenticated: Boolean,
    keepOnEstablishment: Boolean = false,
    reachedConnection: Boolean = true,
): RelayChildSetupPhy = when {
    !(guardOn && !hopAuthenticated && attemptPhy == BlePhyPreference.TWO_M) -> RelayChildSetupPhy.UNCHANGED
    keepOnEstablishment && !reachedConnection -> RelayChildSetupPhy.KEEP_2M_ESTABLISHMENT
    else -> RelayChildSetupPhy.PIN_1M
}

/** Largest sleep slice while the child writer waits for link credit. */
private const val PACE_SLICE_NS = 5_000_000L

/**
 * Experimental in-flight cap while the child reports late media (DebugTunables.relayPaceLeadCap): the
 * relay keeps at most this much measured-rate link time ahead of the link. About four frames at
 * 15 FPS and many connection events (15 ms each), so the link stays busy through a scheduling gap
 * and the train meter keeps reading the link rate, but a fraction of the 400 ms the relay-child
 * media-age budget sheds at, so the queue the shed reads is the delay the child sees (no 3-4 s of
 * bytes hidden in the socket buffer).
 */
internal const val RELAY_PACE_LEAD_CAP_NS = 250_000_000L

/** Lead cap for [LinkPacer.setLeadCapNs]: set only while the experiment is on and the child reports late media. */
internal fun relayLeadCapNs(enabled: Boolean, childLate: Boolean): Long =
    if (enabled && childLate) RELAY_PACE_LEAD_CAP_NS else 0L

/** Result of [awaitLinkCredit]. */
internal class LinkCreditWait(
    /** Time the writer slept for the pacer. */
    val sleptNs: Long,
    /** The pacer would have held the writer this long, but the next record is exempt (audio). */
    val exemptedWaitNs: Long,
)

/**
 * Waits for the child link's pacer before the writer takes its next record. Audio is never held for
 * credit, so the wait ends at once when the next record is audio ([audioExempt] is read again every
 * slice, because audio can arrive at the head of an empty queue meanwhile).
 */
internal fun awaitLinkCredit(
    pacer: LinkPacer,
    audioExempt: () -> Boolean,
    running: () -> Boolean,
    sleepNs: (Long) -> Unit = { ns -> Thread.sleep(ns / 1_000_000L, (ns % 1_000_000L).toInt()) },
): LinkCreditWait {
    var sleptNs = 0L
    while (running()) {
        val waitNs = pacer.waitNs()
        if (waitNs <= 0L) return LinkCreditWait(sleptNs, 0L)
        if (audioExempt()) return LinkCreditWait(sleptNs, waitNs)
        val sliceNs = minOf(waitNs, PACE_SLICE_NS)
        sleepNs(sliceNs)
        sleptNs += sliceNs
    }
    return LinkCreditWait(sleptNs, 0L)
}
