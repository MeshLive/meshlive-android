package com.example.bluetoothtest

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class BluetoothVideoBroadcaster(
    private val queueCapacity: Int,
    private val onStatus: (String) -> Unit,
    private val onConnectionsChanged: (Int) -> Unit,
    private val onAllDisconnected: () -> Unit,
    private val onFirstConnected: () -> Unit,
    private val onPeerReady: () -> Unit = {},
    private val onRemoteKeyFrameNeeded: () -> Unit = {},
    /** Experimental [RelayJoinKeyFrame]: a keyframe that bypasses the recovery back-off; defaults to the normal request. */
    private val onRemoteJoinKeyFrame: () -> Unit = onRemoteKeyFrameNeeded,
    private val thermalStatusProvider: () -> Int,
    private val onCapacityAvailable: () -> Unit = {},
    private val debugDirectGattHeartbeat: Boolean = true,
    private val debugGattWriteTiming: Boolean = false,
    private val onLinkSample: (Any, LinkRateSample) -> Unit = { _, _ -> },
    /** True while Automatic rate control already sends at its lowest video rate (see [FanOutStarvationPolicy]). */
    private val encoderAtRateFloor: () -> Boolean = { false },
) : Closeable, AudioLiveSink {
    private val closed = AtomicBoolean(false)
    private val streamStarted = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val senders = ConcurrentHashMap<String, BluetoothVideoSender>()
    private val connectedPeers = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var maximumSourceChildren = 0

    /**
     * Rotating IDs (phase 1b): the stream's logical token, set by the owner. A version 5 recovery request
     * carries a hint over it (RecoveryMatching); 0 (unset, or the experiment off) never matches.
     */
    @Volatile var logicalStreamToken = 0
    /**
     * Experimental temporal layering (off by default): once frames prove the stream is layered,
     * admission plans further viewers at the measured base layer. Never fed while the experiment is off.
     */
    private val layeredAdmission = DebugTunables.experimentalTemporalLayers
    private val layerShare = LayerShareMeter()
    @Volatile private var admissionSettings: VideoSettings? = null
    @Volatile private var admissionTransport: BluetoothTransportOption? = null

    /** Experimental audio-only live stream (Design AL): non-null for a radio broadcast. */
    @Volatile private var audioLive: AudioLiveSendConfig? = null
    private val admissionLog = AudioLiveLogLimiter()
    private var lastAdmissionRefreshNs = 0L
    private val capacityRejections = SourceCapacityRejectionPolicy()
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val starvation = FanOutStarvationPolicy()
    private val packetLock = Any()
    private val codecConfiguration = mutableListOf<StreamProtocol.Packet>()
    private val videoConfigBatch = VideoConfigBatch()
    private var pendingAudioPacket: StreamProtocol.Packet? = null
    /**
     * Experimental stream audio batches (`test_stream_audio_batch_frames`, off = 2, the 40 ms pairs below): with 3 to
     * 10, ordinary 20 ms units go to every child as one existing `BTA2` batch record of that many units (see
     * [streamAudioBatchFrames]). Read once per stream.
     */
    private val audioBatchFrames = streamAudioBatchFrames(DebugTunables.streamAudioBatchFrames)
    private val pendingAudioBatch = ArrayList<StreamProtocol.Packet>()
    private val allDisconnectedRunnable = Runnable {
        if (senders.isEmpty() && !closed.get()) onAllDisconnected()
    }

    /**
     * Design SG stage 2 (Experimental, `coc_auto_gatt` + `test_coc_auto_gatt_streams`, off): the keys of the separate
     * GATT child pool of an LE CoC source and the airtime each is charged. Both stay empty unless the pool is enabled,
     * so every count below equals the plain one and the flag-off source behaves exactly as before.
     */
    private val gattPoolKeys = ConcurrentHashMap.newKeySet<String>()
    private val gattCharges = ConcurrentHashMap<String, Double>()
    /** Bounded and swept (Design QV review 1, R3); every access holds its monitor. */
    private val gattRefusals = BoundedExpiringMap<StreamGattFallback.GattChildVerdict>(SourceCapacityRejectionPolicy.MAX_ENTRIES)
    /** R3: new pre-authentication attempts per window and their order ([PreAuthAdmissionGate]). */
    private val preAuthGate = PreAuthAdmissionGate()
    @Volatile private var gattPoolCapacity = 0

    private fun gattChargedKbps(): Double = gattCharges.values.sum()
    private fun primarySenderCount(): Int = senders.keys.count { it !in gattPoolKeys }
    private fun primaryConnectedCount(): Int = connectedPeers.count { it !in gattPoolKeys }

    fun sourceSlotsFull(): Boolean = !closed.get() && maximumSourceChildren > 0 &&
        primaryConnectedCount() >= maximumSourceChildren

    /** True while an admitted receiver is still connecting/authenticating. */
    fun connectionAttemptsPending(): Boolean = !closed.get() && senders.size > connectedPeers.size

    fun connect(
        context: Context,
        peers: List<BleL2capPeer>,
        codec: VideoCodec,
        settings: VideoSettings,
        transport: BluetoothTransportOption,
        rotationDegrees: Int,
        includeAudio: Boolean,
        phyPreference: BlePhyPreference,
        corruptFirstVideoRecordForTest: Boolean = false,
        proveVideoIntegrityForTest: Boolean = false,
        ioConfig: VideoStreamIoConfig = VideoStreamIoConfig.DEFAULT,
        maximumChildrenOverride: Int? = null,
        debugTraceSession: String? = null,
        /** Experimental audio-only live stream: planned with the audio per-link figure and the source cap. */
        audioLiveConfig: AudioLiveSendConfig? = null,
        /** Design SG stage 2: non-null (and only on an LE CoC video source) enables the separate GATT child pool. */
        gattPool: GattChildPoolConfig? = null,
    ) {
        require(peers.isNotEmpty()) { "No viewers requested this stream" }
        audioLive = audioLiveConfig
        val pool = gattPool?.takeIf { transport == BluetoothTransportOption.BLE_L2CAP && audioLiveConfig == null }
        gattPoolCapacity = pool?.capacity ?: 0
        val maximumChildren = maximumChildrenOverride ?: fanOutChildren(settings, transport)
        maximumSourceChildren = maximumChildren
        if (maximumChildrenOverride == null && audioLiveConfig != null) {
            admissionTransport = transport
            val perLink = MeshBandwidthEstimator.audioLivePerLinkKbps(audioLiveConfig.kbps, audioLiveConfig.framesPerRecord)
            val budgetKbps = LinkCapacity.budgetKbps(transport, LinkLoadKind.FAN_OUT, perLink)
            // connect() runs for every viewer request (several per second while slots are open): the line is
            // logged when its content changes, and at most every 30 s otherwise.
            if (admissionLog.shouldLog("$maximumChildren|${audioLiveConfig.maxChildren}|${budgetKbps.toInt()}|${transport.name}", System.nanoTime() / 1_000_000L)) {
                android.util.Log.i(
                    "BluetoothVideo",
                    "BENCHMARK audio_live_admission: children=%d | cap=%d | perLinkKbps=%.1f | budgetKbps=%.0f | transport=%s".format(
                        java.util.Locale.ROOT,
                        maximumChildren,
                        audioLiveConfig.maxChildren,
                        perLink,
                        budgetKbps,
                        transport.shortLabel,
                    ),
                )
            }
        } else if (maximumChildrenOverride == null) {
            admissionSettings = settings
            admissionTransport = transport
            android.util.Log.i(
                "BluetoothVideo",
                "Source fan-out: $maximumChildren children from measured ${LinkCapacity.summary(transport)}" +
                    layerShareLabel(),
            )
            val perLink = MeshBandwidthEstimator.perLinkKbps(settings.bitRate / 1_000, settings.fps)
            android.util.Log.i(
                "BluetoothVideo",
                "BENCHMARK fanout_admission: provenLinks=%s | children=%d | perLinkKbps=%.1f | budgetKbps=%.0f | provenKbps=%d".format(
                    java.util.Locale.ROOT,
                    DebugTunables.fanoutProvenLinks,
                    maximumChildren,
                    perLink,
                    LinkCapacity.budgetKbps(transport, LinkLoadKind.FAN_OUT, perLink),
                    LinkCapacity.learned(transport, LinkLoadKind.FAN_OUT).provenKbps,
                ),
            )
        }
        val disconnectedPreviousKeys = peers.mapNotNull { recoveryKeyOf(it) }.toSet()
        val newPeers = peers.distinctBy(::peerIdentityKey)
            .filterNot { senders.containsKey(peerIdentityKey(it)) }
            .filterNot { peer ->
                // The phone-wide GATT registration pool refused a client registration a moment ago (status 257/128): every
                // LE connect (GATT and the CoC control link) needs one, so a viewer request is not dialled again until the
                // exponential hold (1 s to 30 s) ends. The viewer keeps requesting; one gatt_pool_exhausted line per step.
                (transport != BluetoothTransportOption.CLASSIC_RFCOMM ||
                    peer.announcement.transportOption() == BluetoothTransportOption.BLE_GATT) &&
                    GattPoolBackoff.holdsClientConnect()
            }
            .filterNot { peer ->
                peer.announcement.recoveryFromNodeIdPrefix == null &&
                    peerIdentityKey(peer) in disconnectedPreviousKeys
            }
            .filterNot { peer ->
                val nowNs = System.nanoTime()
                val previousSender = recoveryPreviousSenderKey(peer)?.let(senders::get)
                val gattPeer = pool != null && peer.announcement.transportOption() == BluetoothTransportOption.BLE_GATT
                if (gattPeer && gattRefusalHolds(peerIdentityKey(peer), nowNs, pool!!.capacity)) return@filterNot true
                capacityRejections.shouldSuppress(
                    peerIdentityKey(peer),
                    nowNs,
                    capacityAvailable = if (gattPeer) gattPoolKeys.size < pool!!.capacity else primarySenderCount() < maximumChildren,
                    previousParentStale = previousSender != null &&
                        previousSender.expectsPeriodicChildHeartbeat() &&
                        nowNs - previousSender.lastChildHeartbeatNs() >= RECOVERY_STALE_HEARTBEAT_NS,
                )
            }
            .sortedByDescending { recoveryPreviousSenderKey(it) != null }
            .let { candidates ->
                // R3: at most a few new pre-authentication attempts per window, oldest request key first; the rest wait
                // for the next scan without a capacity cooldown. Recovery requests keep their place and are not counted.
                val selection = preAuthGate.select(
                    candidates, ::peerIdentityKey, { recoveryPreviousSenderKey(it) != null }, maximumChildren, System.nanoTime(),
                )
                if (selection.deferred.isNotEmpty()) {
                    android.util.Log.i(
                        "BluetoothVideo",
                        (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                            "viewer_admission: deferred=${selection.deferred.size} | chosen=${selection.chosen.size} | " +
                            "tracked=${preAuthGate.trackedKeys}",
                    )
                }
                selection.chosen
            }
        var availableSlots = (maximumChildren - primarySenderCount()).coerceAtLeast(0)
        var recoveryProbeAdmitted = false
        val admittedPeers = mutableListOf<BleL2capPeer>()
        // Experimental phone-wide link budget (test_link_budget_admission, default off): a source with fan-out slots left
        // can still be out of LE links. The refusal says "relay full", never "capacity" or "low memory".
        val linkBudget = if (GattSwitches.linkBudgetAdmission) runCatching { GattLinkBudget.shared(context) }.getOrNull() else null
        val budgetRefused = mutableSetOf<String>()
        val gattCandidates = if (pool != null) {
            newPeers.filter { !it.wifiOnly && it.announcement.transportOption() == BluetoothTransportOption.BLE_GATT }
        } else {
            emptyList()
        }
        val primaryCandidates = if (gattCandidates.isEmpty()) newPeers else newPeers - gattCandidates.toSet()
        primaryCandidates.forEach { peer ->
            val budgetFull = linkBudget != null && availableSlots > 0 &&
                !linkBudget.admitChild(upstreamParentLinked = true).allowed
            if (budgetFull) budgetRefused += peerIdentityKey(peer)
            when {
                availableSlots > 0 && !budgetFull -> {
                    admittedPeers += peer
                    availableSlots--
                }
                !recoveryProbeAdmitted && recoveryPreviousSenderKey(peer) != null -> {
                    // This connection carries no media until the old child's authenticated
                    // heartbeat resolves. It may therefore temporarily exceed fan-out by one.
                    admittedPeers += peer
                    recoveryProbeAdmitted = true
                }
            }
        }
        val rejectedCount = primaryCandidates.size - admittedPeers.size
        if (rejectedCount > 0) {
            val admittedKeys = admittedPeers.map(::peerIdentityKey).toSet()
            primaryCandidates.filterNot { peerIdentityKey(it) in admittedKeys }.forEach { peer ->
                val key = peerIdentityKey(peer)
                capacityRejections.reject(
                    key,
                    System.nanoTime(),
                    if (key in budgetRefused) SourceCapacityRejectionPolicy.REASON_RELAY_FULL else SourceCapacityRejectionPolicy.REASON_CAPACITY,
                )
            }
            onStatus(
                "$rejectedCount receiver(s) exceeded the measured ${transport.shortLabel} " +
                    "fan-out budget at ${settings.bitRate / 1_000} kbps.",
            )
        }
        if (pool != null) admitGattPoolPeers(gattCandidates, pool, settings, admittedPeers)
        val connectingAttempts = mutableMapOf<String, BluetoothVideoSender>()
        admittedPeers.forEach { peer ->
            val peerKey = peerIdentityKey(peer)
            val gattChild = peerKey in gattPoolKeys && !peer.wifiOnly
            lateinit var sender: BluetoothVideoSender
            sender = BluetoothVideoSender(
                queueCapacity = queueCapacity,
                onStatus = { message -> onStatus("${peer.label.substringBefore(" (")}: $message") },
                onDisconnected = {
                    if (senders.remove(peerKey, sender)) {
                        // R3: an attempt that never authenticated moves this key behind untried ones for a while.
                        if (!connectedPeers.remove(peerKey)) preAuthGate.onPreAuthFailure(peerKey, System.nanoTime())
                        gattPoolKeys.remove(peerKey)
                        gattCharges.remove(peerKey)
                        onConnectionsChanged(connectedPeers.size)
                        if (primarySenderCount() < maximumChildren && !closed.get()) onCapacityAvailable()
                        if (senders.isEmpty() && !closed.get()) {
                            timeoutHandler.removeCallbacks(allDisconnectedRunnable)
                            timeoutHandler.postDelayed(
                                allDisconnectedRunnable,
                                ROUTE_RECOVERY_GRACE_MS,
                            )
                        }
                    }
                },
                onRemoteKeyFrameNeeded = onRemoteKeyFrameNeeded,
                onRemoteJoinKeyFrame = onRemoteJoinKeyFrame,
                thermalStatusProvider = thermalStatusProvider,
                debugDirectGattHeartbeat = debugDirectGattHeartbeat,
                debugGattWriteTiming = debugGattWriteTiming,
                onLinkSample = { sample ->
                    // A GATT-pool child never feeds the shared rate controller: a link that cannot keep up must not
                    // lower the encoder rate of the L2CAP viewers (Design SG; the starvation guard still sees it).
                    if (peerKey !in gattPoolKeys) onLinkSample(peerKey, sample)
                    starvation.onSample(peerKey, sample, System.nanoTime(), encoderAtRateFloor())?.let { withdrawal ->
                        timeoutHandler.post {
                            withdrawStarvedChild(peerKey, sender, transport, withdrawal.carriedAggregateKbps)
                        }
                    }
                },
            )
            if (senders.putIfAbsent(peerKey, sender) != null) {
                gattPoolKeys.remove(peerKey)
                gattCharges.remove(peerKey)
                return@forEach
            }
            timeoutHandler.removeCallbacks(allDisconnectedRunnable)
            if (recoveryPreviousSenderKey(peer) == null) preAuthGate.noteAttempt(peerKey, System.nanoTime())
            connectingAttempts[peerKey] = sender
            sender.connect(
                context,
                peer.device,
                codec,
                settings,
                if (gattChild) BluetoothTransportOption.BLE_GATT else transport,
                rotationDegrees,
                peer.announcement.nodeIdPrefix,
                includeAudio,
                phyPreference,
                corruptFirstVideoRecordForTest,
                proveVideoIntegrityForTest,
                if (gattChild) pool?.ioConfig ?: ioConfig else ioConfig,
                debugTraceSession = debugTraceSession,
                selectedRotatingRequest = peer.announcement.takeIf { it.rotating },
                audioLiveConfig = audioLiveConfig,
                wifiDial = if (peer.wifiOnly) {
                    ({
                        (WifiDiscovery.nativeLink() ?: throw IOException("native_fail:not_started"))
                            .dial(WifiSighting.keyOf(peer.announcement), "viewer", WifiLimits.WIDER_VIEWER_DIAL_MS)
                    })
                } else {
                    null
                },
            ) {
                handleAuthenticatedPeer(peer, peerKey, sender, maximumChildren)
            }
        }
        if (connectingAttempts.isNotEmpty()) timeoutHandler.postDelayed({
            if (closed.get()) return@postDelayed
            val stalled = broadcasterTimedOutAttempts(connectingAttempts, senders, connectedPeers)
            if (stalled.isNotEmpty()) {
                onStatus("${stalled.size} receiver connection(s) timed out; continuing with connected receivers.")
                stalled.forEach(BluetoothVideoSender::close)
            }
        }, CONNECTION_TIMEOUT_MS)
    }

    private fun fanOutChildren(settings: VideoSettings, transport: BluetoothTransportOption): Int {
        val audio = audioLive
        if (audio != null) {
            // Children = min(floor(budget / planned per-link), cap): bandwidth alone would admit 11-18, but the
            // limit measured on this radio is airtime, so the default cap of 4 stands until the ladder test.
            val perLink = MeshBandwidthEstimator.audioLivePerLinkKbps(audio.kbps, audio.framesPerRecord)
            return MeshBandwidthEstimator.audioLiveSourceChildren(
                LinkCapacity.budgetKbps(transport, LinkLoadKind.FAN_OUT, perLink),
                audio.kbps,
                audio.framesPerRecord,
                audio.maxChildren,
            )
        }
        return videoFanOutChildren(settings, transport)
    }

    private fun videoFanOutChildren(settings: VideoSettings, transport: BluetoothTransportOption): Int =
        MeshBandwidthEstimator.estimate(
            settings,
            transport,
            MeshNodeRole.SOURCE,
            plannedChildren = 0,
            measuredAggregateKbps = { perLink ->
                StreamGattFallback.fanOutBudgetKbps(
                    LinkCapacity.budgetKbps(transport, LinkLoadKind.FAN_OUT, perLink),
                    gattChargedKbps(),
                )
            },
            baseLayerShare = if (layeredAdmission) layerShare.baseShare() else null,
        ).sourceChildren

    /** Experimental temporal layering: empty unless frames proved the stream is layered. */
    private fun layerShareLabel(): String =
        (if (layeredAdmission) layerShare.baseShare() else null)
            ?.let { " | experimental layering, base layer %.0f%% of video bytes".format(it * 100) } ?: ""

    /** Re-plans fan-out from live measurements (capacity reports, expired ceilings); reopens a slot when one fits. */
    private fun refreshAdmissionIfDue() {
        val settings = admissionSettings ?: audioLive?.let { VideoSettings.DEFAULT } ?: return
        val transport = admissionTransport ?: return
        val nowNs = System.nanoTime()
        if (nowNs - lastAdmissionRefreshNs < ADMISSION_REFRESH_NS) return
        lastAdmissionRefreshNs = nowNs
        val children = fanOutChildren(settings, transport)
        if (children == maximumSourceChildren) return
        val wasFull = sourceSlotsFull()
        maximumSourceChildren = children
        android.util.Log.i(
            "BluetoothVideo",
            "Source fan-out re-planned: $children children from measured ${LinkCapacity.summary(transport)}" +
                layerShareLabel(),
        )
        if (wasFull != sourceSlotsFull()) onConnectionsChanged(connectedPeers.size)
    }

    /**
     * Audio-only live: sends one record the source already signed. The Opus configuration is remembered and
     * repeated for every child that joins later; media is dropped while paused. Never paired or batched here.
     */
    override fun sendAudioLiveRecord(record: AudioLiveSignedRecord) {
        synchronized(packetLock) {
            if (record.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0) {
                val configuration = StreamProtocol.Packet(record.flags, record.presentationTimeUs, record.payload.copyOf())
                codecConfiguration.clear()
                codecConfiguration += configuration
            }
            if (closed.get() || paused.get() && record.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) return
            readySenders().forEach { it.send(record.flags, record.presentationTimeUs, record.payload) }
        }
    }

    /** Mute: drops every unsent audio record queued for any listener. */
    override fun discardQueuedAudio(): Int = synchronized(packetLock) {
        senders.values.sumOf(BluetoothVideoSender::discardQueuedAudioRecords)
    }

    fun send(flags: Int, presentationTimeUs: Long, bytes: ByteArray) {
        if (flags and (StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG) == 0) {
            if (layeredAdmission) layerShare.add(bytes.size, flags and StreamProtocol.FLAG_DISCARDABLE != 0)
            refreshAdmissionIfDue()
        }
        synchronized(packetLock) {
            val packetFlags = if (flags and StreamProtocol.FLAG_CODEC_CONFIG != 0) {
                StreamProtocol.FLAG_CODEC_CONFIG or (flags and StreamProtocol.FLAG_AUDIO)
            } else {
                flags
            }
            if (packetFlags and StreamProtocol.FLAG_CODEC_CONFIG != 0) {
                val configuration = StreamProtocol.Packet(packetFlags, presentationTimeUs, bytes.copyOf())
                if (packetFlags and StreamProtocol.FLAG_AUDIO == 0 && videoConfigBatch.onConfig()) {
                    // A restarted encoder (new rung, camera flip): a late joiner gets only the current
                    // encoder's configuration, never csd of two picture sizes.
                    codecConfiguration.removeAll { it.flags and StreamProtocol.FLAG_AUDIO == 0 }
                }
                if (codecConfiguration.none {
                        it.flags == configuration.flags && it.payload.contentEquals(configuration.payload)
                    }
                ) {
                    if (codecConfiguration.size == MAX_CODEC_CONFIGURATION_PACKETS) {
                        codecConfiguration.removeAt(0)
                    }
                    codecConfiguration += configuration
                }
            } else if (packetFlags and (StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_DISPLAY_ROTATION) == 0) {
                val restartKeyFrame = packetFlags and StreamProtocol.FLAG_KEY_FRAME != 0 &&
                    packetFlags and StreamProtocol.FLAG_DISCONTINUITY != 0
                if (videoConfigBatch.onMedia(restartKeyFrame)) {
                    codecConfiguration.removeAll { it.flags and StreamProtocol.FLAG_AUDIO == 0 }
                }
            }
            if (closed.get() || paused.get() && packetFlags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                return
            }
            if (packetFlags == StreamProtocol.FLAG_AUDIO && audioBatchFrames > 2) {
                val current = StreamProtocol.Packet(packetFlags, presentationTimeUs, bytes.copyOf())
                // A unit that cannot share the batch (a timestamp jump) sends what waits first.
                if (!streamAudioBatchAccepts(pendingAudioBatch, current)) flushPendingAudioLocked()
                pendingAudioBatch += current
                if (pendingAudioBatch.size >= audioBatchFrames) flushPendingAudioLocked()
                return
            }
            if (packetFlags == StreamProtocol.FLAG_AUDIO) {
                val current = StreamProtocol.Packet(packetFlags, presentationTimeUs, bytes.copyOf())
                val pending = pendingAudioPacket
                if (pending == null) {
                    pendingAudioPacket = current
                    return
                }
                pendingAudioPacket = null
                val paired = StreamProtocol.pairAudioPackets(pending, current)
                readySenders().forEach {
                    it.send(paired.flags, paired.presentationTimeUs, paired.payload)
                }
                return
            }
            readySenders().forEach { it.send(packetFlags, presentationTimeUs, bytes) }
        }
    }

    fun pauseStream() {
        if (!paused.compareAndSet(false, true)) return
        synchronized(packetLock) {
            flushPendingAudioLocked()
            senders.values.forEach(BluetoothVideoSender::pauseStream)
        }
    }

    fun repeatPausedState() {
        if (!paused.get()) return
        senders.values.forEach(BluetoothVideoSender::repeatPausedState)
    }

    fun resumeStream() {
        if (!paused.compareAndSet(true, false)) return
        senders.values.forEach(BluetoothVideoSender::resumeStream)
        // A resume says plain LIVE; tell the listeners again what is still switched off.
        val state = mediaState
        if (state != StreamLifecycleState.LIVE) senders.values.forEach { it.sendMediaState(state) }
    }

    /**
     * Microphone muted / camera off while live (experimental `live_media_state`). Remembered so a child that
     * joins later is told too; LIVE means everything is on again. Not sent while paused (the resume repeats it).
     */
    fun setMediaState(state: StreamLifecycleState) {
        require(state.isLiveFamily) { "Only live-family records are media states" }
        synchronized(packetLock) {
            if (closed.get() || mediaState == state) return
            mediaState = state
            if (paused.get()) return
            readySenders().forEach { it.sendMediaState(state) }
        }
    }

    @Volatile private var mediaState: StreamLifecycleState = StreamLifecycleState.LIVE

    /** Mute: the unpaired Opus access unit waiting for its partner must not pair with one recorded after Unmute. */
    fun flushPendingAudio() {
        synchronized(packetLock) { flushPendingAudioLocked() }
    }

    fun endStream() {
        val active = synchronized(packetLock) {
            if (!closed.compareAndSet(false, true)) return
            flushPendingAudioLocked()
            timeoutHandler.removeCallbacksAndMessages(null)
            ArrayList(senders.values).also {
                senders.clear()
                connectedPeers.clear()
                gattPoolKeys.clear()
                gattCharges.clear()
                synchronized(gattRefusals) { gattRefusals.clear() }
                capacityRejections.clear()
                preAuthGate.clear()
            }
        }
        active.forEach(BluetoothVideoSender::endStream)
        onConnectionsChanged(0)
    }

    /** See [FanOutStarvationPolicy]: this phone's radio cannot serve this viewer alongside the others. */
    private fun withdrawStarvedChild(
        peerKey: String,
        sender: BluetoothVideoSender,
        transport: BluetoothTransportOption,
        carriedAggregateKbps: Int,
    ) {
        if (closed.get() || senders[peerKey] !== sender || !senders.remove(peerKey, sender)) return
        connectedPeers.remove(peerKey)
        val gattChild = gattPoolKeys.remove(peerKey)
        gattCharges.remove(peerKey)
        onConnectionsChanged(connectedPeers.size)
        capacityRejections.reject(peerKey, System.nanoTime())
        starvation.remove(peerKey)
        // Media bytes only (no framing), so the learned ceiling errs conservative. A GATT-pool child is not an L2CAP
        // fan-out child: its starvation says nothing about the L2CAP ceiling.
        if (!gattChild) LinkCapacity.recordCeiling(transport, LinkLoadKind.FAN_OUT, carriedAggregateKbps)
        sender.withdrawForLocalCapacity()
        android.util.Log.i(
            "BluetoothVideo",
            "Fan-out: withdrew a starved viewer; this phone carried $carriedAggregateKbps kbps across its viewers",
        )
        onStatus("A viewer was getting too little of this phone's Bluetooth airtime; it was asked to find another route.")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        timeoutHandler.removeCallbacksAndMessages(null)
        synchronized(packetLock) {
            pendingAudioPacket = null
            pendingAudioBatch.clear()
        }
        val active = ArrayList(senders.values)
        senders.clear()
        connectedPeers.clear()
        gattPoolKeys.clear()
        gattCharges.clear()
        synchronized(gattRefusals) { gattRefusals.clear() }
        capacityRejections.clear()
        preAuthGate.clear()
        active.forEach(BluetoothVideoSender::close)
        onConnectionsChanged(0)
    }

    private fun flushPendingAudioLocked() {
        if (pendingAudioBatch.isNotEmpty()) {
            val batch = combineCallAudio(pendingAudioBatch.toList())
            pendingAudioBatch.clear()
            readySenders().forEach { it.send(batch.flags, batch.presentationTimeUs, batch.payload) }
        }
        val pending = pendingAudioPacket ?: return
        pendingAudioPacket = null
        readySenders().forEach {
            it.send(pending.flags, pending.presentationTimeUs, pending.payload)
        }
    }

    private fun handleAuthenticatedPeer(
        peer: BleL2capPeer,
        peerKey: String,
        sender: BluetoothVideoSender,
        maximumChildren: Int,
    ) {
        if (peer.announcement.rotating) replaceSameNode(peerKey, sender)
        if (peerKey in gattPoolKeys) {
            admitSender(peerKey, sender)
            return
        }
        val previousKey = recoveryKeyOf(peer)?.takeUnless { it == peerKey }
        val previousSender = previousKey?.let(senders::get)
        if (previousKey == null || previousSender == null ||
            !previousSender.expectsPeriodicChildHeartbeat()
        ) {
            admitSender(peerKey, sender)
            return
        }
        if (primaryConnectedCount() < maximumChildren) {
            onStatus("Authenticated replacement route admitted in an available source slot.")
            admitSender(peerKey, sender)
            return
        }

        val heartbeatAtProbeStart = previousSender.lastChildHeartbeatNs()
        onStatus("Authenticated replacement route; checking the previous immediate node before sending media.")
        timeoutHandler.post(object : Runnable {
            override fun run() {
                if (closed.get() || senders[peerKey] !== sender) return
                if (senders[previousKey] !== previousSender) {
                    admitSender(peerKey, sender)
                    return
                }
                if (previousSender.lastChildHeartbeatNs() > heartbeatAtProbeStart) {
                    if (primaryConnectedCount() < maximumChildren) {
                        onStatus("The previous immediate node is still responsive; keeping both admitted routes.")
                        admitSender(peerKey, sender)
                    } else {
                        senders.remove(peerKey, sender)
                        capacityRejections.reject(peerKey, System.nanoTime())
                        sender.withdrawForLocalCapacity()
                        onStatus(
                            "The previous immediate node is still responsive and source fan-out is full; " +
                                "the viewer will keep searching.",
                        )
                    }
                    return
                }
                val heartbeatAgeNs = System.nanoTime() - previousSender.lastChildHeartbeatNs()
                if (heartbeatAgeNs >= RECOVERY_STALE_HEARTBEAT_NS) {
                    val removed = senders.remove(previousKey, previousSender)
                    if (removed) {
                        connectedPeers.remove(previousKey)
                        previousSender.close()
                        onStatus("Removed the unresponsive previous immediate node during route recovery.")
                    }
                    admitSender(peerKey, sender)
                    return
                }
                timeoutHandler.postDelayed(this, RECOVERY_PROBE_INTERVAL_MS)
            }
        })
    }

    /** True while a refusal of this GATT request still holds (30 s, or until the pool has room again after "pool full"). */
    private fun gattRefusalHolds(key: String, nowNs: Long, capacity: Int): Boolean {
        synchronized(gattRefusals) {
            val untilNs = gattRefusals.deadline(key) ?: return false
            val verdict = gattRefusals.peek(key) ?: return false
            if (nowNs >= untilNs || (verdict == StreamGattFallback.GattChildVerdict.REFUSE_POOL_FULL && gattPoolKeys.size < capacity)) {
                gattRefusals.remove(key)
                return false
            }
            return true
        }
    }

    /**
     * Design SG stage 2: decides each GATT viewer request into the separate pool. An admitted request is charged to the
     * aggregate budget at its planned rate and appended to [admittedPeers]; a refused one is logged and shown, never
     * given another stream. L2CAP children are not touched.
     */
    private fun admitGattPoolPeers(
        candidates: List<BleL2capPeer>,
        pool: GattChildPoolConfig,
        settings: VideoSettings,
        admittedPeers: MutableList<BleL2capPeer>,
    ) {
        if (candidates.isEmpty()) return
        val perLink = MeshBandwidthEstimator.perLinkKbps(settings.bitRate / 1_000, settings.fps)
        val baseShare = if (layeredAdmission) layerShare.baseShare() else null
        candidates.forEach { peer ->
            val key = peerIdentityKey(peer)
            val input = StreamGattFallback.GattChildInput(
                gattChildren = gattPoolKeys.size,
                poolCapacity = pool.capacity,
                gattChargedKbps = gattChargedKbps(),
                l2capCommittedKbps = primarySenderCount() * perLink,
                aggregateBudgetKbps = LinkCapacity.budgetKbps(BluetoothTransportOption.BLE_L2CAP, LinkLoadKind.FAN_OUT, perLink),
                needKbps = perLink * (baseShare ?: 1.0),
                gattLinkCapacityKbps = LinkCapacity.budgetKbps(BluetoothTransportOption.BLE_GATT, LinkLoadKind.FAN_OUT, perLink),
            )
            val admission = StreamGattFallback.admitGattChild(input)
            android.util.Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") + StreamGattFallback.poolLine(input, admission, primarySenderCount()),
            )
            if (admission.verdict.admit) {
                gattPoolKeys += key
                gattCharges[key] = admission.chargeKbps
                synchronized(gattRefusals) { gattRefusals.remove(key) }
                admittedPeers += peer
                lastAdmissionRefreshNs = 0L // re-plan the L2CAP fan-out with the new charge at the next frame
            } else {
                val nowNs = System.nanoTime()
                synchronized(gattRefusals) {
                    gattRefusals.put(key, admission.verdict, nowNs + GATT_REFUSAL_COOLDOWN_NS, nowNs)
                }
                onStatus(StreamGattFallback.refusalStatus(admission.verdict))
            }
        }
    }

    private fun admitSender(peerKey: String, sender: BluetoothVideoSender) {
        synchronized(packetLock) {
            if (closed.get() || senders[peerKey] !== sender || !connectedPeers.add(peerKey)) return
            codecConfiguration.forEach { packet ->
                sender.send(packet.flags, packet.presentationTimeUs, packet.payload)
            }
            onConnectionsChanged(connectedPeers.size)
            if (paused.get()) sender.pauseStream()
            else if (mediaState != StreamLifecycleState.LIVE) sender.sendMediaState(mediaState)
            if (streamStarted.compareAndSet(false, true)) onFirstConnected()
            onPeerReady()
        }
    }

    private fun readySenders(): List<BluetoothVideoSender> =
        connectedPeers.mapNotNull(senders::get)

    /** Short labels of the authenticated direct children (the measured Auto ladder's start evidence). */
    fun connectedPeerLabels(): List<String> =
        readySenders().mapNotNull { it.authenticatedPeerNode()?.shortLabel() }

    private fun recoveryPreviousSenderKey(peer: BleL2capPeer): String? =
        recoveryKeyOf(peer)
            ?.takeUnless { it == peerIdentityKey(peer) }
            ?.takeIf(senders::containsKey)

    /** The key of the child this request says it replaces: a version 2 prefix, or a matched version 5 hint. */
    private fun recoveryKeyOf(peer: BleL2capPeer): String? =
        if (peer.announcement.rotating) {
            rotatingRecoveryKey(peer)
        } else {
            peer.announcement.recoveryFromNodeIdPrefix?.let(::nodePrefixKey)
        }

    /**
     * Rotating IDs: the admission key of the connected child whose recovery hint equals this version 5
     * request's (RV18). Only children this source holds an end-to-end key for can match; a request whose
     * hint matches nobody is an ordinary request.
     */
    private fun rotatingRecoveryKey(peer: BleL2capPeer): String? {
        val hint = peer.announcement.takeIf { it.rotating }?.recoveryFromNodeIdPrefix ?: return null
        val logical = logicalStreamToken.takeIf { it != 0 } ?: return null
        val children = senders.entries.mapNotNull { (key, child) ->
            val node = child.authenticatedPeerNode() ?: return@mapNotNull null
            val mediaKey = child.recoveryMediaKey() ?: return@mapNotNull null
            RecoveryMatching.Child(key, node, mediaKey)
        }
        return RecoveryMatching.match(
            hint, logical, peer.announcement.nodeIdPrefix, peer.announcement.streamToken, children,
        )
    }

    /**
     * Rotating IDs: one authenticated link per node. A second authenticated link from a node that already
     * has one (its request rotated while the first link was dying) replaces the older sender.
     */
    private fun replaceSameNode(peerKey: String, sender: BluetoothVideoSender) {
        val node = sender.authenticatedPeerNode() ?: return
        ArrayList(senders.entries).forEach { (key, other) ->
            if (key != peerKey && other !== sender && other.authenticatedPeerNode() == node) {
                if (senders.remove(key, other)) {
                    connectedPeers.remove(key)
                    other.close()
                    onConnectionsChanged(connectedPeers.size)
                }
            }
        }
    }

    private companion object {
        const val ADMISSION_REFRESH_NS = 2_000_000_000L
        const val GATT_REFUSAL_COOLDOWN_NS = 30_000_000_000L
        const val CONNECTION_TIMEOUT_MS = 12_000L
        const val ROUTE_RECOVERY_GRACE_MS = 6_000L
        const val MAX_CODEC_CONFIGURATION_PACKETS = 4
        const val RECOVERY_PROBE_INTERVAL_MS = 250L
        const val RECOVERY_STALE_HEARTBEAT_NS = 6_000_000_000L
    }
}

/**
 * Version 5 requests are keyed by their announced bytes and advertising address (RD3): a copy of someone
 * else's advert from another address is a separate, failing attempt and cannot starve the real viewer.
 */
private fun peerIdentityKey(peer: BleL2capPeer): String =
    if (peer.wifiOnly) {
        // A placeholder device has one shared address: keys must not collide (A8 step 12.3.3).
        "w:" + nodePrefixKey(peer.announcement.nodeIdPrefix)
    } else if (peer.announcement.rotating) {
        "r:" + nodePrefixKey(peer.announcement.nodeIdPrefix) + "@" + peer.device.address
    } else {
        nodePrefixKey(peer.announcement.nodeIdPrefix)
    }

private fun nodePrefixKey(prefix: ByteArray): String =
    prefix.joinToString("") { "%02x".format(it) }

internal fun <T : Any> broadcasterTimedOutAttempts(
    attempts: Map<String, T>,
    current: Map<String, T>,
    connected: Set<String>,
): List<T> = attempts.mapNotNull { (key, attempt) ->
    attempt.takeIf { current[key] === attempt && key !in connected }
}
