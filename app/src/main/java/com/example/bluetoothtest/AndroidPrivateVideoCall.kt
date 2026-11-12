package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class AndroidPrivateVideoCall(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val endpointTransport: BluetoothTransportOption,
    private val codec: VideoCodec,
    private val availableCodecs: List<VideoCodec>,
    private val settings: VideoSettings,
    private val rotationDegrees: Int,
    /** Null while the call view has no surface; asked for only when video needs a decoder (VideoSurfaceWait). */
    private val remoteSurfaceProvider: () -> Surface?,
    private val playbackDelayProfile: PlaybackDelayProfile,
    private val phyPreference: BlePhyPreference = BlePhyPreference.TWO_M,
    private val ioConfig: VideoStreamIoConfig = VideoStreamIoConfig.DEFAULT,
    /** The camera/encoder modes for the requested call video (GPU scale ladder: the rung sizes of that request). */
    private val supportedVideoModes: (VideoCodec, VideoSettings) -> List<DeviceVideoMode>,
    private val onStatus: (String) -> Unit,
    private val onPhase: (PrivateCallState) -> Unit,
    private val onIncoming: (String) -> Unit,
    private val onRemoteVideoFormat: (StreamProtocol.Header) -> Unit,
    private val onRemoteCameraEnabled: (Boolean) -> Unit,
    private val onRemoteMuted: (Boolean) -> Unit,
    private val onStartLocalMedia: (VideoSettings, Int, VideoCodec, PlaybackDelayProfile) -> Unit,
    private val onLocalKeyFrameNeeded: () -> Unit,
    private val onEnded: (String) -> Unit,
    /** About 1 s of this phone's sending on the call link, for Automatic rate control. */
    private val onLinkSample: (LinkRateSample) -> Unit = {},
    /** Experimental stall tolerance (CallStallTolerance): true while the peer is quiet but the call is kept. */
    private val onLinkStall: (Boolean) -> Unit = {},
    private val debugTraceSessionName: String? = null,
    private val debugGattDeferConnectionPriority: Boolean = false,
    debugPipelineTiming: Boolean = false,
    /** Experimental voice calls (IntercomFeatures, default off), read when this endpoint is created. */
    private val voiceCallsEnabled: Boolean = false,
    /** The ringing call's offered type changed (experimental voice calls only). */
    private val onIncomingCallType: (CallType) -> Unit = {},
    /**
     * Experimental store-and-forward messages (StoreForwardFeature, default off). Null: this
     * endpoint never reads or accepts hop type 11 and behaves exactly as before.
     */
    private val storeForwardNode: (() -> StoreForwardNode)? = null,
    /**
     * Messaging v2 (switch `test_messaging_v2`, default off): whether this phone offers a chat session to [NodeId] in a message
     * exchange (HELLO flag CHAT). The policy lives in [ChatSessionController]; false keeps every exchange a plain one.
     */
    private val chatCapable: (NodeId, Boolean) -> Boolean = { _, _ -> false },
    /** A message exchange on this endpoint finished (experimental store-and-forward only). */
    private val onMessageSync: (StoreForwardSyncResult) -> Unit = {},
    /**
     * An outgoing message exchange failed before a BSF1 result existed (link or BMH2 handshake).
     * Reported for the experimental automatic sync backoff; unused by default.
     */
    private val onMessageSyncFailed: (String) -> Unit = {},
    /**
     * An outgoing LE CoC connection (PSM read, channel open or connect) failed before the encrypted
     * handshake. Reported so the experimental CoC -> GATT fallback can retry; unused by default.
     */
    private val onCocConnectFailed: (String) -> Unit = {},
    /**
     * Experimental call encoder fit (CallEncoderFit, default off: returns its input): the geometry
     * this phone's encoder can sustain in a call, chosen before the call header is sent.
     */
    private val fitCallVideo: (VideoSettings, VideoCodec) -> VideoSettings = { requested, _ -> requested },
    /** Experimental peer labels: verified labels for the peer with this 12-hex prefix changed. */
    private val onPeerLabels: (String) -> Unit = {},
    /**
     * Experimental relayed calls (RelayedCallsFeature, default off). Null: this endpoint never reads hop
     * type 12, signs no relay mark and sets no consent bit, and behaves exactly as before.
     */
    private val relayHelper: RelayHelperHost? = null,
    /** Call blocking (CallBlocking.kt): decides at INVITE time whether an incoming call may ring. Local only. */
    private val callGate: CallGate = CallBlocking.gate(context),
    /** AB3/AP3 D2: a blocked call's ordinary end is scheduled (the caller's ringback must stop at once). */
    private val onBlockedEndScheduled: () -> Unit = {},
) : Closeable {
    private sealed interface OutboundControl {
        data class Signal(val message: PrivateCallSignalMessage, val closeAfter: Boolean = false) : OutboundControl
        data object Header : OutboundControl
        data object KeyFrameRequest : OutboundControl
        data class DisplayRotation(val degrees: Int) : OutboundControl
        /** Experimental peer labels: one `BLB1` container (BME1 type 10), ordered with the signals. */
        class Labels(val payload: ByteArray) : OutboundControl

        /** Experimental rotating IDs: one `BDX1` container (BME1 type 11), ordered with the signals. */
        class Discovery(val payload: ByteArray) : OutboundControl

        /**
         * Experimental BMH3 pseudonyms: this phone's identity reveal, built (a Keystore signature) on the writer thread
         * when its turn in the ordered queue comes, so the Accept tap never waits for it.
         */
        class Reveal(val build: () -> ByteArray) : OutboundControl

        /** Experimental in-call link renewal: one `RENEW` record (BME1 type 12), ordered with the signals. */
        class Renew(val payload: ByteArray) : OutboundControl

        /** Wi-Fi upgrade: one `BWF1` hop record for carrier [carrier] (not sealed under BME1), ordered with the other controls. */
        class Wifi(val carrier: Int, val payload: ByteArray) : OutboundControl
    }

    private data class OutboundMedia(
        val flags: Int,
        val presentationTimeUs: Long,
        val payload: ByteArray,
        val debugQueuedAtNs: Long = 0L,
        val queuedAtNs: Long = System.nanoTime(),
    ) {
        val isAudio: Boolean get() = flags and StreamProtocol.FLAG_AUDIO != 0
        val isKeyFrame: Boolean get() = flags and StreamProtocol.FLAG_KEY_FRAME != 0
        val isDiscardable: Boolean get() = flags and StreamProtocol.FLAG_DISCARDABLE != 0

        fun asRecoveryPoint(): OutboundMedia = copy(
            flags = flags or StreamProtocol.FLAG_DISCONTINUITY,
        )
    }

    private class DebugTxCounters {
        val videoInputs = AtomicLong()
        val audioInputs = AtomicLong()
        val configInputs = AtomicLong()
        val videoWrites = AtomicLong()
        val audioWrites = AtomicLong()
        val configWrites = AtomicLong()
        val queueDrops = AtomicLong()
        val inactiveInputs = AtomicLong()
        val mutedAudio = AtomicLong()
        /** Audio records removed by the call queue (queueDrops counts audio and video together). */
        val audioDrops = AtomicLong()
    }

    private data class CallConnection(
        val input: InputStream,
        val output: OutputStream,
        val description: String,
        val closeConnection: () -> Unit,
        /** The RFCOMM/CoC socket, for learning a Classic peer's address after authentication. */
        val socket: BluetoothSocket? = null,
        /** A BMH2 link already authenticated while choosing among Classic candidates. */
        val link: SecureBluetoothSession? = null,
        /** LE peer address, binding the authenticated hop to this link for [AdaptivePhyMemory]. */
        val address: String? = null,
        /** Current LE PHY summary ("PHY TX 2M / RX 2M"); empty when unknown or Classic. */
        val phySummary: () -> String = { "" },
        /** Experimental dual listening: the transport this incoming link arrived on when it is not the endpoint's own. */
        val arrivedOn: BluetoothTransportOption? = null,
        /** The kind of byte stream this connection is; A8's native Wi-Fi call link sets WIFI_AWARE. */
        val carrierKind: CarrierKind = CarrierKind.BLUETOOTH,
        /** A8/F-3: the consumer calls this once the BMH3/BMH2 link handshake on this connection succeeded. */
        val onAuthenticated: () -> Unit = {},
        /**
         * `test_call_1m_first` / `test_call_1m_first_coc`: asks this live link for a PHY (the GATT client, or the CoC control
         * link); null unless it opened 1M-first.
         */
        val requestPhy: ((BlePhyPreference) -> Boolean)? = null,
        /** Experimental `test_slow_2m_in_place`: asks this live outgoing LE link for a PHY now (GATT client, or the CoC control link). */
        val movePhy: ((BlePhyPreference) -> Boolean)? = null,
        /** Experimental `test_slow_2m_in_place`: reads the link's PHY (no LL procedure) so [phySummary] follows a move. */
        val readPhy: (() -> Boolean)? = null,
    ) : Closeable {
        override fun close() = closeConnection()
    }

    /**
     * Experimental dual listening (DualListeningFeature, default off): the transport of the link this endpoint
     * accepted or opened when it is not the endpoint's own (a CoC endpoint answering or dialing over GATT).
     * Null (always, with the switch off) reads exactly the old values. Written when a link starts and cleared
     * when an attempt is discarded; every policy that followed the endpoint's transport follows the link.
     */
    @Volatile private var linkTransportOverride: BluetoothTransportOption? = null
    private val transport: BluetoothTransportOption get() = linkTransportOverride ?: endpointTransport

    private val appContext = context.applicationContext
    private val identity = AndroidNodeIdentityStore().getOrCreate()
    private val stackFingerprint = privateCallStackFingerprint()
    /** Experimental legacy low delay (off by default), read once; direct calls only. */
    private val legacyLowDelay = DebugTunables.callLegacyLowDelay
    /** Experimental call audio coalescing (off by default), read once; see [coalesceQueuedCallAudio]. */
    private val audioCoalesce = DebugTunables.callAudioCoalesce
    /** Experimental call audio pairs (off by default), read once; see [privateCallAudioBatchSizeForRoute]. */
    private val audioPairs = DebugTunables.callAudioPairs
    /** Experimental measured relayed-call cap (off by default), read once; relayed calls only. */
    private val relayedMeasuredCap = DebugTunables.relayedCallMeasuredCap

    /** Relayed route settings: the legacy cap, or (experimental) this phone's measured-link budget. */
    private fun routeSettings(hops: Int): VideoSettings {
        val budget = if (hops > 0 && relayedMeasuredCap) {
            relayedCallMeasuredBudgetBps(
                runCatching { LinkCapacity.measuredLinkKbps(transport) }.getOrNull(),
                privateCallAudioBitRate(transport, stackFingerprint),
            )
        } else {
            null
        }
        val routed = privateCallVideoSettingsForRoute(settings, hops, budget)
        if (hops > 0) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK call relayed_cap: relayHops=$hops | measuredCap=$relayedMeasuredCap | " +
                    "budgetKbps=${budget?.div(1_000) ?: "none"} | fps=${routed.fps} | bitrateKbps=${routed.bitRate / 1_000}",
            )
        }
        return routed
    }
    /** The decode mask this endpoint advertised in its end-to-end hello (privateCallEndpointDecodeMask). */
    @Volatile private var advertisedDecodeMask = 0
    private val capabilitiesByTransport = HashMap<BluetoothTransportOption, NodeCapabilities>()
    private val capabilities: NodeCapabilities
        get() = synchronized(capabilitiesByTransport) {
            capabilitiesByTransport.getOrPut(transport) { privateCallCapabilities(transport, stackFingerprint) }
        }

    /**
     * Experimental rotating discovery IDs (RotatingDiscoveryFeature, default off), fixed for this
     * endpoint: call offers advertise version 5 with the role-0 RDI, link hellos carry bit 3 and
     * the first hop record each way is `BDO1`. Off: [linkCapabilities] equals [capabilities].
     */
    private val rotatingIds = RotatingDiscoveryFeature.enabled.also { on ->
        if (on) RotatingDiscovery.init(context, identity.nodeId)
    }
    /**
     * Classic call availability keeps version 2: the Classic address is permanent and paired peers
     * resolve every address anyway (RV2), and ClassicConnector finds the callee by its fixed prefix.
     */
    private val rotatingAdvert = rotatingIds && transport != BluetoothTransportOption.CLASSIC_RFCOMM
    private val linkCapabilitiesBase: NodeCapabilities
        get() = if (rotatingIds) {
            capabilities.copy(transportMask = capabilities.transportMask or RotatingDiscoveryIds.LINK_HELLO_BIT)
        } else {
            capabilities
        }

    /** Experimental relayed calls: fixed for this endpoint; LE only (Classic is not relayed on demand). */
    private val relayedCallsOn = relayHelper != null && RelayedCallsFeature.enabled &&
        transport != BluetoothTransportOption.CLASSIC_RFCOMM

    /** The link hello signed now: with relayed calls on it follows the helper state (PROTOCOL capability bits). */
    private val linkCapabilities: NodeCapabilities
        get() = if (relayedCallsOn) {
            RelayedCalls.linkCapabilities(
                linkCapabilitiesBase,
                featureOn = true,
                helperActive = relayHelper?.helperActive() == true && linkTransportOverride == null,
            )
        } else {
            linkCapabilitiesBase
        }

    /** The on-demand relay this endpoint runs for a SETUP it accepted (relayed calls only). */
    @Volatile private var relayDuty: BluetoothPrivateCallRelay? = null

    /** A relay duty (not a call) used this endpoint: its end must not open the call screens. */
    @Volatile var endedFromRelayDuty = false
        private set

    /** Call blocking: this endpoint declined a blocked or unknown caller; its end must not open any screen. */
    @Volatile var endedQuietly = false
        private set

    /**
     * Design PP package A: the phases (generation, monotonic milestones and the two default-off incoming timers) of the connection
     * this endpoint accepted last. A new accept replaces it, and a timer only ever closes the connection it was armed for.
     */
    @Volatile private var incomingPhases: IncomingPhaseTimers? = null
    private val incomingPhaseGeneration = java.util.concurrent.atomic.AtomicLong()

    /** Call blocking after a stranger's reveal (review 2026-10-27, finding 2): media is dropped, Recent names nobody. */
    private val blockedCall = BlockedCallLatch()

    /** AB3/AP3 D2: a blocked person ended this call and its ordinary end is pending (no ringback meanwhile). */
    fun blockedEndScheduled(): Boolean = blockedCall.active

    /** The route the current outgoing call asked for (relayed calls only) and when its setup began. */
    @Volatile private var pendingRelayRoute: RelayRoute? = null

    /** A route whose READY arrived but whose target is not yet verified end to end (marked used/failed then). */
    @Volatile private var relayRouteUnverified: RelayRoute? = null
    @Volatile private var relaySetupStartedNs = 0L
    @Volatile private var preSetupWatchdog: Thread? = null

    /** True while this phone relays someone else's call. */
    val relayingNow: Boolean get() = relayDuty != null
    /**
     * True while an incoming (or outgoing) link is accepted, including one that has not delivered its first record yet:
     * the caller's phase is still null then, but rebuilding the endpoint would close the link under its peer.
     */
    val acceptedLinkNow: Boolean get() = callRunning.get() || connection != null
    /** Epoch of the call offer on air (rotating IDs only); -1 when none. */
    @Volatile private var advertisedEpoch = -1L
    /** The offer on air is a Visible-by-handle window (version 2) rather than a rotating ID. */
    @Volatile private var advertisedVisible = false
    /** Advances when a boundary restart closes the listener on purpose, so its accept thread stays quiet. */
    @Volatile private var listenerGeneration = 0
    /** Wider reach, Bluetooth off: only the Wi-Fi offer is on air; no Bluetooth listener or advertiser exists. */
    @Volatile private var wifiOnlyListening = false
    /** Counts stopAvailability calls not caused by an incoming connection (calls, exchanges, close). */
    @Volatile private var fullAvailabilityStops = 0
    private val boundaryListener: (Long) -> Unit = { epoch -> onDiscoveryBoundary(epoch) }
    /**
     * The end-to-end hello's decode mask (privateCallEndpointDecodeMask), fixed when the endpoint is
     * created. Only a VP9-restricted phone scans its platform decoders for it.
     */
    private val decodeMaskByTransport = HashMap<BluetoothTransportOption, Int>()
    private val endpointDecodeMask: Int
        get() = synchronized(decodeMaskByTransport) {
            decodeMaskByTransport.getOrPut(transport) {
                withAv1SoftwareDecodeBit(
                    privateCallEndpointDecodeMask(
                        restrictedMask = capabilities.decodeCodecMask,
                        platformMask = if (privateCallDecodeRestricted(stackFingerprint, transport)) {
                            VideoCodec.availableDecoders().fold(0) { mask, decoder -> mask or codecCapabilityMask(decoder.protocolId) }
                        } else {
                            capabilities.decodeCodecMask
                        },
                        fallback = DebugTunables.callCodecFallback,
                    ),
                    // Design HC end-to-end bit 4: only while a master is on (both off: today's bytes).
                    av1DecoderHardware = if (VideoCodecSwitches.anyNew) {
                        VideoCodec.availableDecoders().firstOrNull { it.protocolId == StreamProtocol.CODEC_AV1 }?.hardwareAccelerated
                    } else {
                        null
                    },
                    enabled = VideoCodecSwitches.anyNew && VideoCodecSwitches.av1SoftDecodeBit,
                )
            }
        }
    private val running = AtomicBoolean(true)
    /** startAvailability publishes under this fence, so a close() that overtook it never leaves its listener up (CallEndpointStarts.kt). */
    private val listenerStartFence = ListenerStartFence(running)
    private val callRunning = AtomicBoolean(false)
    private val endedCallbackSent = AtomicBoolean(false)
    /** Set when this phone's own media pipeline ended the call; reported instead of "Call ended". */
    @Volatile private var localFailureMessage: String? = null
    private val controls = BoundedControlQueue<OutboundControl>(
        MemoryThermalPolicy.controlQueueCapacity(MemoryThermalPolicy.CALL_CONTROL_QUEUE_CAPACITY),
        "call",
    )
    /** Experimental bounded media age, read once before the queue is sized. */
    private val boundedMediaAgeOn = BoundedMediaAge.enabled
    private val mediaCapacity = settings.queueCapacity.coerceAtLeast(16)
    /**
     * Call audio has its own room in the send queue (default on; control arm
     * `test_call_audio_headroom=false`), so audio waiting behind one long video write no longer
     * counts against the video budget. See [privateCallAudioHeadroomPackets].
     */
    private val audioHeadroom = !boundedMediaAgeOn && DebugTunables.callAudioHeadroom
    // Bounded media age adds headroom past the nominal capacity for records it never drops or evicts
    // (audio, codec configuration, recovery keyframes); off, the queue is exactly as before.
    private val media = ArrayBlockingQueue<OutboundMedia>(
        mediaCapacity + when {
            boundedMediaAgeOn -> BoundedMediaAge.criticalHeadroom(mediaCapacity)
            audioHeadroom -> privateCallAudioHeadroomPackets()
            else -> 0
        },
    )
    @Volatile private var state: PrivateCallState? = null
    /** Call-type negotiation of the current call (experimental voice calls; VIDEO when off). */
    @Volatile private var negotiation: VoiceCallNegotiation? = null
    @Volatile private var muted = false
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    /** LE CoC availability: a lease on the role's long-lived listener (CocListeners). */
    @Volatile private var cocListener: CocListenLease<BluetoothSocket>? = null
    @Volatile private var classicAdvertiser: BleDiscoveryAdvertiser? = null
    @Volatile private var cocControlServer: BleL2capControlServer? = null
    /** Experimental dual listening: this CoC endpoint's second listener (the GATT call service), while it exists. */
    @Volatile private var dualSecondary: DualSecondaryHost? = null
    /** Experimental dual listening: the transports the current outgoing attempt may use, in order. */
    @Volatile private var dualPlan: List<BluetoothTransportOption> = emptyList()
    @Volatile private var gattServer: GattVideoServer? = null
    @Volatile private var bleControlConnection: BleL2capControlConnection? = null
    /** System.nanoTime when this call's hop link authenticated (0 = not yet); for early-loss memory. */
    @Volatile private var authenticatedAtNs = 0L
    /** Hop-local measurements of one carrier (the Bluetooth carrier's values are the call's old fields, see below). */
    private class CarrierLinkState {
        @Volatile var lastHeartbeatNs = 0L
        @Volatile var lastSendProgressNs = 0L
        @Volatile var receivedDelivery = LinkDeliveryCounter()
        @Volatile var peerDelivery = PeerDeliveryEstimate()
        @Volatile var deliveryReports = CallDeliveryReports(enabled = true)   // carrier 0: the call's value, set in establish as today; an added carrier: DebugTunables.deliveryReports (stateOf, amendment 18)
        @Volatile var helloSent = false   // amendment 18: the version-1 hello of an added carrier was handed to its writer
    }
    private val carrierState0 = CarrierLinkState()
    private val extraCarrierStates = java.util.concurrent.ConcurrentHashMap<Int, CarrierLinkState>()
    private fun stateOf(id: Int): CarrierLinkState = if (id == 0) carrierState0 else extraCarrierStates.computeIfAbsent(id) { CarrierLinkState().also { it.deliveryReports = CallDeliveryReports(DebugTunables.deliveryReports) } }   // amendments 18, 20 d

    /** The sink sealed content goes to (null = the link's own writer): the carrier router after fan-out. */
    @Volatile private var contentSink: RecordSink? = null

    private fun sinkFor(link: SecureBluetoothSession): RecordSink = contentSink ?: link.writer

    /** Placeholder marker codec: nothing can reach it before A5 installs its codec (it needs a second carrier). [X3] */
    private object CallMarkerCodec : CarrierMarkerCodec {
        override fun encode(wire: CarrierCutover.Wire): ByteArray = throw IllegalStateException("no BWF1 codec yet")

        override fun decode(payload: ByteArray): CarrierCutover.Wire? = null
    }

    /** The call's carrier host: what [CarrierSet] calls. Carrier 0 is the Bluetooth link; every method may run on any thread. */
    private inner class CarrierHost(
        private val endpoint: EndToEndVideoSession,
        private val initiator: Boolean,
    ) : CarrierSet.Host {
        override fun heard(carrierId: Int, generation: Int): Boolean {
            if (carrierId != 0) {
                stateOf(carrierId).lastHeartbeatNs = System.nanoTime()
                return true
            }
            // SW1 F3: with the switch the liveness stamp is taken before the record lock, which a blocked consumer can hold for seconds.
            return heardOnBluetoothLink(
                CutoverSwitches.livenessBeforeRecordLock,
                recordLock,
                generation,
                { linkGeneration.get() },
                { lastHeartbeatNs = System.nanoTime() },
            ) {
                if (generation != reassemblerLinkGen) {
                    reassemblerLinkGen = generation
                    if (videoReassembler.enterLinkGeneration(generation)) {
                        Log.i("BluetoothVideo", "BENCHMARK call_video_fragments: renewal_dropped_partial_frame | gen=$generation")
                    }
                }
                lastHeartbeatNs = System.nanoTime()
                noteRenewedLinkHeard()
            }
        }

        override fun onHeartbeat(carrierId: Int, generation: Int, record: SecureRecord) {
            synchronized(recordLock) {
                if (carrierId == 0 && generation != linkGeneration.get()) return
                if (carrierId == 0) noteRenewalPeerHeard()
                val report = SecureLinkControlProtocol.requireHeartbeat(record)
                if (report == null) notePeerHeartbeat(carrierId, null) else onPeerReport(carrierId, report)
            }
        }

        override fun deliver(carrierId: Int, generation: Int, payload: ByteArray) {
            synchronized(recordLock) {
                if (carrierId == 0 && generation != linkGeneration.get()) return
                val content = endpoint.opener.open(payload)
                if (carrierId == 0 && content.type != EndToEndContentType.RENEW) noteRenewalPeerHeard()
                arrivalCarrier = carrierId
                handleContent(content, initiator)
            }
        }

        override fun onWifiRecord(carrierId: Int, payload: ByteArray) {
            peerSentWifiRecord = true   // an authenticated BWF1 record: the peer reads BWF1 (R-1, a BMH3 initiator's proof)
            val handler = wifiRecordHandler
            if (handler != null) {
                handler(carrierId, payload)
            } else {
                Log.i("BluetoothVideo", "wifi_record_unhandled kind=${payload.firstOrNull() ?: -1}")
            }
        }

        override fun onCutoverAction(action: CarrierCutover.Action) {
            when (action) {
                CarrierCutover.Action.ForceLocalKeyframe -> onLocalKeyFrameNeeded()
                CarrierCutover.Action.RequestPeerKeyframe -> controls.offerKeyed("key-frame-request", OutboundControl.KeyFrameRequest)
                CarrierCutover.Action.ReassertState -> reassertAfterRenewal()
                CarrierCutover.Action.ResetSendFragments -> abandonFrame = true
                CarrierCutover.Action.SuppressFragments -> synchronized(recordLock) {
                    if (videoReassembler.discardUntilFrameStart()) {
                        Log.i("BluetoothVideo", "BENCHMARK call_video_fragments: carrier_dropped_partial_frame")
                    }
                }
                else -> Unit
            }
        }

        override fun onSendCarrierChanged(sendOn: Int, kind: CarrierKind?, reason: String) {
            activeCarrierLabel = if (kind == null || kind == CarrierKind.BLUETOOTH) null else kind.label
            LinkChangeEvents.sendCarrierChanged(reason)
            if (kind != null && !kind.isWifi && reason != "attach") {
                // Back on Bluetooth: the pacer's seed describes a Bluetooth link again.
                if (pacedWrites) LinkPacerSeed.seed(pacer, transport, carrier = CarrierKind.BLUETOOTH)
            }
            if (reason != "attach") onStatus(if (kind != null && kind.isWifi) WifiCallNotices.MOVED_TO_WIFI_STATUS else WifiCallNotices.BACK_ON_BLUETOOTH_STATUS)
            if (BuildConfig.DEBUG && reason != "attach") Log.i("BluetoothVideo", "BENCHMARK carrier_switch: to=${kind?.name ?: "none"} | reason=$reason | live=${carriers?.liveCount()}")
            notifyCarrierListeners(CarrierEvent.SEND_CHANGED, sendOn, kind, reason)
        }

        override fun onEndSession(reason: String) {
            val error = lastLossError
            val current = state
            // SW1 END: a call this phone ended always finishes as "Call ended", whichever carrier failure raced the hang-up.
            when (privateCallEndSessionAction(error != null, callRunning.get(), current?.phase, current?.callId, localEndCallId)) {
                EndSessionAction.FINISH_LOCAL_END -> finish(CALL_ENDED_MESSAGE)
                EndSessionAction.LINK_ERROR -> onCallLinkError(requireNotNull(error), lastLossMessage ?: "Call link lost")
                EndSessionAction.FAIL -> fail(lastLossMessage ?: "Call link lost")
            }
        }

        /**
         * The heartbeat a fan-out carrier writer sends: this phone's view of the media it receives on that carrier.
         * Only the carrier that currently receives media reports the receiver's lateness; a standby reports arrival only.
         */
        override fun heartbeatPayload(carrierId: Int): ByteArray? {
            val set = carriers ?: return null
            if (carrierId == 0 && !set.fanout) return null
            val carrierState = stateOf(carrierId)
            if (carrierId != 0 && carrierState.deliveryReports.sendsHello && !carrierState.helloSent) {
                // The plain version-1 hello tells the peer this hop accepts version 3 reports.
                carrierState.helloSent = true
                return null
            }
            val arrivalKbps = set.linkOf(carrierId)?.reader?.arrivalCapacityKbps() ?: 0
            val report = if (set.acceptCarrier == carrierId) {
                mediaReceiver?.takeLinkReport(arrivalKbps) ?: LinkCapacityReport(arrivalKbps, linkLate = false)
            } else {
                LinkCapacityReport(arrivalKbps, linkLate = false)
            }
            val delivery = carrierState.deliveryReports.deliveryFor(state != null, relayHops) { carrierState.receivedDelivery.snapshot() }
            return SecureLinkControlProtocol.encodeCapacityReport(report.copy(delivery = delivery))
        }

        // Review A3 P1-2 (step 9 item 4): in fan-out the monitor judges the Bluetooth carrier with today's stall rule.
        override fun bluetoothSilent(carrierId: Int): Boolean {
            val s = stateOf(carrierId)
            return CarrierLossPolicy.bluetoothSilent(
                stallTolerance || renewalPossible(),
                System.nanoTime(),
                s.lastHeartbeatNs,
                s.lastSendProgressNs,
                renewalOwnsLiveness(),
            )
        }

        override fun onProgress(carrierId: Int, nowNs: Long) {
            stateOf(carrierId).lastSendProgressNs = nowNs
        }

        override fun onProtocolError(carrierId: Int, message: String) {
            throw IllegalStateException(message)
        }

        override fun onConsumerError(error: Exception) {
            if (error is LocalMediaPipelineException) {
                if (callRunning.get() && state?.phase != PrivateCallPhase.ENDED) endForLocalMediaFailure(error)
            } else {
                // A call whose only carrier is Wi-Fi (A8 step 11): the notice says the Wi-Fi link ended (AM 29).
                val message = if (connection?.description == WifiCallNotices.LABEL) WifiCallNotices.WIFI_NATIVE_ENDED
                else "Call link lost: ${error.message ?: error.javaClass.simpleName}"
                onCallLinkError(error, message)
            }
        }

        override fun requeueUndelivered(cookies: List<Any>) {
            requeueSignals(cookies.filterIsInstance<OutboundControl>())
        }

        override fun atFrameBoundary(): Boolean = !midVideoFrame

        override fun renewalBusy(): Boolean = this@AndroidPrivateVideoCall.renewalBusy()

        override fun onCarrierEvent(event: CarrierEvent, carrierId: Int, kind: CarrierKind?, reason: String) {
            if (event == CarrierEvent.ENDED) extraCarrierStates.remove(carrierId)
            notifyCarrierListeners(event, carrierId, kind, reason)
        }

        override fun log(line: String) {
            Log.i("BluetoothVideo", line)
        }
    }

    /** System.nanoTime of the last call-writer flush that returned (send-side progress). */
    private var lastSendProgressNs: Long
        get() = carrierState0.lastSendProgressNs
        set(value) {
            carrierState0.lastSendProgressNs = value
        }
    /** Advertised prefix of a relayed outgoing offer (null for direct or incoming calls). */
    @Volatile private var offerViaPrefix: String? = null
    /**
     * Experimental test_phy_direction_split: the split remembered for the peer of the current dial, with the address it was
     * looked up for (null: none); the GATT dial to that address asks for it at setup.
     */
    @Volatile private var dialPhySplit: Pair<String, GattPhySplit>? = null
    /** System.nanoTime when this call first became ACTIVE (0 = not yet); the early-loss window start. */
    @Volatile private var activeAtNs = 0L
    /** PHY this endpoint asked for on the current LE link (both roles; 2M unless remembered or retried). */
    @Volatile private var requestedLinkPhy: BlePhyPreference = BlePhyPreference.TWO_M
    /** The Classic candidate socket being opened, so close() can abort a page/SDP in progress. */
    @Volatile private var pendingClassicSocket: BluetoothSocket? = null
    /** Review A2: the direct CoC socket being connected, so close() and a Cancel can abort it. */
    @Volatile private var pendingCocSocket: BluetoothSocket? = null
    /** Review A1: the call this phone ended itself (End/Cancel/Decline); a later link failure finishes it. */
    @Volatile private var localEndCallId: UUID? = null
    @Volatile private var connection: CallConnection? = null

    /** Experimental `test_call_1m_first`: the upgrade/fallback decisions of a call that opened 1M-first; null for every other call. */
    @Volatile private var phyFirstGate: CallPhyUpgradeGate? = null
    @Volatile private var secureSession: SecureBluetoothSession? = null
    @Volatile private var contentSession: EndToEndVideoSession? = null

    // ----- Wi-Fi upgrade (Design WF, package A3): the call's carriers; carrier 0 is the Bluetooth link -----
    /** The call's carriers. Created per link establishment; carrier 0 is the Bluetooth link. */
    @Volatile private var carriers: CarrierSet? = null
    /** The marker codec the cutover uses; A5 installs its `WifiMarkerCodec` from `createWifiHop`. [X3] */
    @Volatile internal var markerCodec: CarrierMarkerCodec = CallMarkerCodec
    private val markerForwarder = object : CarrierMarkerCodec {
        override fun encode(wire: CarrierCutover.Wire) = markerCodec.encode(wire)

        override fun decode(payload: ByteArray) = markerCodec.decode(payload)
    }
    /** Carrier 0's real link generation whose records the reassembler holds (guarded by `recordLock`). */
    private var reassemblerLinkGen = Int.MIN_VALUE
    /** Fan-out: a fragmented video frame is being written (the cutover must wait for a frame boundary). */
    @Volatile private var midVideoFrame = false
    /** `ResetSendFragments`: the fragment writer stops after the fragment it is writing. */
    @Volatile private var abandonFrame = false
    /** The carrier the record being handled arrived on (its own delivery counters). */
    @Volatile private var arrivalCarrier = 0
    /** Why the last carrier died, for `Host.onEndSession` (the cutover holds the end, not the error). */
    @Volatile private var lastLossError: Exception? = null
    @Volatile private var lastLossMessage: String? = null
    /** A5 (Wi-Fi upgrade) sets this before the call is ACTIVE; called on the reader or consumer thread and must not block. [API] */
    @Volatile var wifiRecordHandler: ((carrierId: Int, payload: ByteArray) -> Unit)? = null
    /** The peer sent this call an authenticated `BWF1` record (R-1: what proves a BMH3 initiator's peer reads them). */
    @Volatile private var peerSentWifiRecord = false
    @Volatile private var listenerThread: Thread? = null
    @Volatile private var connectionThread: Thread? = null
    @Volatile private var writerThread: Thread? = null
    @Volatile private var readerThread: Thread? = null
    /** The running store-and-forward exchange, if this link is one (never a call at the same time). */
    @Volatile private var messageSync: StoreForwardLinkSession? = null
    /** A connection that arrived during an exchange; answered as a call once the exchange stops. */
    @Volatile private var pendingCallConnection: CallConnection? = null
    @Volatile private var mediaReceiver: PrivateCallMediaReceiver? = null
    private var lastHeartbeatNs: Long
        get() = carrierState0.lastHeartbeatNs
        set(value) {
            carrierState0.lastHeartbeatNs = value
        }
    /** F19: when this call started ringing (elapsedRealtime), for the ring timeout and the callee's "Missed call". */
    @Volatile private var ringingSinceMs: Long? = null
    /** F17 Stage A (Experimental, `test_call_stall_tolerance`), fixed for this endpoint. */
    private val stallTolerance = DebugTunables.callStallTolerance
    /** The stall currently shown as "Poor connection" (Stage A only). */
    @Volatile private var stallShown = false

    // ----- In-call link renewal (Design SR, default on; `test_call_link_renewal=false` is the control arm) -----
    private val renewalOn = DebugTunables.callLinkRenewal
    /** Both end-to-end hellos of this call carried bit 4. */
    @Volatile private var renewalNegotiated = false

    /** Wi-Fi carrier (A5): the per-call attempt owner; null on verification scans, peers without BWF1 and a call whose carrier 0 is itself Wi-Fi (A8 native link); relayed calls are included. [R M-3][X14] */
    @Volatile private var wifiHop: WifiHopAgent? = null
    /** This endpoint's end-to-end role in the call: 0 caller, 1 callee (collision rule). */
    @Volatile private var renewalRole = 0
    @Volatile private var renewalOfferer = RenewalOfferer(SystemClock::elapsedRealtime)
    @Volatile private var renewalArmed = RenewalArmed(SystemClock::elapsedRealtime)
    @Volatile private var renewalAttempts = RenewalAttempts()
    /** Bumped when the call moves to a renewed link: threads of an older link end quietly and drop what they read. */
    private val linkGeneration = AtomicInteger()
    /** The generation of the call's first link: the DEBUG tx-stall hook acts only there. */
    @Volatile private var firstLinkGeneration = 0
    /** When Stage A's soft state began (elapsedRealtime), for the renewal trigger; null while not soft. */
    @Volatile private var renewalSoftSinceMs: Long? = null
    @Volatile private var renewalRefusalLogged = false
    @Volatile private var renewalScanner: CallRenewalScanner? = null
    /** The armed phone's servers: the attempt in progress and the one carrying the live link (review item 2). */
    @Volatile private var renewalServers = RenewalServers<GattVideoServer>()
    /** The current link's reader or writer ended quietly (review item 1); the supervisor ends the call when no renewal owns it. */
    @Volatile private var renewalLinkDead = false
    /** Records are opened under this lock with the generation check: no old-link record is opened after a switch. */
    private val recordLock = Any()
    /** Last record heard on the old link when a renewal switched links (0: none pending), for `call_renewal_silence`. */
    @Volatile private var renewalSilenceFromNs = 0L
    private val renewalConnections = AtomicLong()
    private val renewalSwitchLock = Any()
    /** The last camera signal this endpoint queued and its last LABELS record: re-asserted after a renewal. */
    @Volatile private var lastCameraSignal: PrivateCallSignal? = null
    @Volatile private var lastLabelsPayload: ByteArray? = null
    @Volatile private var debugTxStallLogged = false
    @Volatile private var renewalSilenceRole = "offerer"
    @Volatile private var activeSettings = settings
    @Volatile private var activeCodec = codec
    /** codec_power_aware: the power state read once, when this call first chooses its codec; never re-read mid-call. */
    @Volatile private var codecPowerAtStart: CodecPowerState? = null
    /** AP5 `test_sw_shortfall_avc`: the remembered software-encoder shortfalls, read once with [codecPowerAtStart]. */
    @Volatile private var slowSoftwareAtStart: Set<String>? = null
    @Volatile private var activePlaybackDelayProfile = playbackDelayProfile
    @Volatile private var relayHops = 0
    @Volatile private var peerMaxBitrateKbps: Int? = null
    private var droppingVideoUntilKeyFrame = false
    private var droppedMediaPackets = 0L
    /**
     * Experimental temporal layering (off by default): skip verified-discardable call video while
     * the call queue is behind or full, before dropping reference frames. Off: nothing is thinned.
     */
    private val thinDiscardable = DebugTunables.experimentalTemporalLayers
    private val thinnedVideoFrames = AtomicLong()

    /**
     * Call video fragments and speech-led playout from one resolver ([CallHeadOfLine]: user default on direct GATT calls,
     * benchmark mode or extras). Fragments are advertised in this endpoint's signed hello and used only when the peer
     * advertised them too on a direct call; speech-led playout of the default follows that negotiation.
     */
    private val headOfLine = CallHeadOfLine.current(endpointTransport)
    private val videoFragmentsOn = headOfLine.videoFragments

    /** Experimental peer labels (PeerLabelFeature, default off), fixed for this endpoint. */
    private val labelsOn = PeerLabelFeature.enabled
    @Volatile private var peerAcceptsLabels = false

    /** Rotating IDs, Phase 2: the OFFER / PRESENCE_LEASE rules of this call session (null: not negotiated). */
    @Volatile private var presenceSession: PresenceSession? = null

    /**
     * Rotating IDs: the callee's `BDX1` OPENING this caller still waits for (it must be the callee's first
     * end-to-end record), with what it has to open. Null: nothing awaited.
     */
    private class AwaitedOpening(val selected: DiscoveryAnnouncement, val endpointNode: NodeId, val linkNode: NodeId)

    /**
     * BMH3 pseudonyms: a caller that selected a rotating offer and met a pseudonymous callee has not yet proved the callee
     * is the owner of the offer; the callee's reveal does (PROTOCOL "When the reveal happens"). Cleared once verified.
     */
    private class AwaitedReveal(val selected: DiscoveryAnnouncement)

    @Volatile private var awaitedReveal: AwaitedReveal? = null

    /** Visible by name (default off): the offer and page sets of an open window, owned by this endpoint. */
    private var windowAdvertiser: NameWindowAdvertiser? = null
    private val windowLock = Any()
    private val windowListener: () -> Unit = { syncWindowAdvertiser() }

    /**
     * BVMID2 (Naming v2, default off with `bvmid2`). Responder: this connection's gate on the displayed verification code and,
     * once the handshake matched it, the verify-only [QrSessionScope]. Scanner: the parsed code this outgoing connection
     * scans and what to tell the screen when the answerer proved to hold the code's key. See [QrCodeDisplay].
     */
    @Volatile private var qrGate: QrConnectionGate? = null
    @Volatile private var qrScope: QrSessionScope? = null
    /** Displayer: the code's reservation was committed by this connection (only then may it take part in Design QL). */
    @Volatile private var qrCommitted = false
    /** The verification session's close is already scheduled (it is scheduled once). */
    private val qrClosing = java.util.concurrent.atomic.AtomicBoolean(false)
    /** This connection is the scanner's side of a verification session. */
    @Volatile private var qrScanner = false
    /** Design QL: this side started its presence exchange (scanner: confirmed; displayer: answered the scanner's OFFER). */
    @Volatile private var qrLeaseStarted = false
    /** Displayer: this scanner may receive an answer (committed, confirmed in this visit, direct, real identity). */
    @Volatile private var qrDisplayerMayAnswer = false
    @Volatile internal var qrScanPayload: Bvmid2Payload? = null
    @Volatile var onQrScanReached: ((IdentityFingerprint, NodeId) -> Unit)? = null

    /**
     * Naming v2: the caller's name in this call, held and released only by [CallerNameRelease] (planned before the Invite;
     * released after a relayed callee's OPENING or after the callee's verified, unblocked REVEAL; design decision
     * 2026-11-06). The container is the same `BLB1` record as before, signed by [endpointSigner] for this call ID.
     */
    private val callerNames = CallerNameRelease(
        signName = {
            LocalLabels.signed(endpointSigner, LabelKind.PERSON_NAME, LabelBinding.call(requireNotNull(state).callId), PeerLabelFeature.localName)
        },
        offerLabels = { container -> controls.offerMust(OutboundControl.Labels(container)) },
        onSent = { count -> Log.i("BluetoothVideo", "BENCHMARK peer_labels call_tx: role=caller | count=$count") },
        onWithheld = { reason ->
            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK call_label_withheld: role=caller | reason=${reason.name.lowercase()}")
        },
    )

    /** The key this phone signed its end-to-end handshake with (the real identity, or the pseudonym it presented). */
    @Volatile private var endpointSigner: SigningIdentity = identity

    /** The callee's pinned opening entries, kept for its reveal (a pseudonymous callee sends no OPENING at the Invite). */
    @Volatile private var revealEntries: List<OpeningEntry> = emptyList()
    @Volatile private var awaitedOpening: AwaitedOpening? = null
    /**
     * Callee, in a stranger session: the name waits for the caller's verified reveal and block check (audience "Only people I
     * verified", or "Everyone" with `test_callee_name_after_reveal` (default on), audit CALL-06). Held by [CalleeNameRelease].
     */
    private val calleeNames = CalleeNameRelease(
        offerLabels = { container ->
            controls.offerMust(OutboundControl.Labels(container))
            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK peer_labels call_tx: role=callee | count=1")
        },
        onWithheld = { reason ->
            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK call_label_withheld: role=callee | reason=${reason.name.lowercase()}")
        },
    )

    /** Callee: the role-0 entries pinned when the connection was accepted, sent once with the answer to the Invite. */
    @Volatile private var openingToSend: List<OpeningEntry>? = null

    /** Both endpoint hellos carried bit 3 in this session: BDX1 records are exchanged. */
    @Volatile private var discoveryNegotiated = false
    /** R3: at most one LABELS record received before this session's Invite, verified on Invite. */
    private var bufferedLabels: ByteArray? = null
    private var callLabels: LabelInbox? = null
    /** R1: this callee's own name, signed at Invite time, sent only with Accept (or at Ringing to a verified contact). */
    @Volatile private var pendingAcceptLabels: ByteArray? = null

    @Volatile private var peerAcceptsVideoFragments = false
    private val videoReassembler = CallVideoReassembler()
    private val fragmentedVideoFrames = AtomicLong()
    private val interleavedAudioPackets = AtomicLong()
    /** Experimental call audio coalescing: records that combined queued units, and the units they carried. */
    private val coalescedAudioRecords = AtomicLong()
    private val coalescedAudioUnits = AtomicLong()

    /**
     * Experimental airtime-sized fragments (off by default, see CallFragmentAirtime): target link time
     * per fragment in ms, 0 = the fixed 2 KB fragments. Counts only with [videoFragmentsOn].
     */
    private val fragmentAirtimeMs = if (videoFragmentsOn) CallFragmentAirtime.clampTargetMs(DebugTunables.callFragmentAirtimeMs) else 0
    /** Delivered-rate peak, fragment statistics and the learned probe: recreated for every link. */
    @Volatile private var fragmentRates = CallFragmentRateEstimator()
    @Volatile private var fragmentStats = CallFragmentStats()
    @Volatile private var fragmentProbeKbps: Int? = null

    /**
     * Experimental bounded media age (off by default, see BoundedMediaAge): call video may wait in
     * this queue only as long as keeps speech-synchronous video near the 400 ms mouth-to-ear budget.
     * Audio is never shed by this policy. Off: unchanged. The queue-age measurements are logged in
     * both arms (BENCHMARK media_age).
     */
    private val boundedAge = boundedMediaAgeOn
    private var criticalOverflowDrops = 0L
    private val callShedder = MediaAgeShedder(BoundedMediaAge.CALL_SHED_COOLDOWN_NS)
    private val pauseGovernor = CallVideoPauseGovernor()
    /** Shed or paused video: overuse evidence for rate control (0 while the experiment is off). */
    private val boundedShedFrames = AtomicLong()
    private var boundedPausedFrames = 0L
    @Volatile private var peerAddedDelayMs = 0
    /**
     * Bounded media age only: every queue change in send() and the writer's take share this monitor,
     * so a shed is atomic for the writer. Unused while the experiment is off.
     */
    private val mediaLock = Object()
    private val videoQueueWaitMax = WindowMax()
    private val audioQueueWaitMax = WindowMax()
    // Hop-local rate evidence, as for streams: what this phone offered and sent, how old its
    // backlog is, and what the peer reports about the media it receives from this phone.
    private val sampleOfferedBytes = AtomicLong()
    @Volatile private var peerMeasuredKbps = 0
    /** Experimental call-link pacing (DebugTunables.callLinkPacing): socket transports only. */
    private val pacer = LinkPacer()
    private val pacedWrites: Boolean
        get() = callLinkPaced(transport, DebugTunables.callLinkPacing, DebugTunables.linkPacing, carriers?.sendKind ?: CarrierKind.BLUETOOTH)
    @Volatile private var peerLateUntilNs = 0L
    private val peerLateness = PeerLatenessTrend()
    // Version 3 delivery reports (hop-local, 2026-10-09): media this phone received from the peer,
    // what the peer reports it received from this phone (against this phone's own write times), and
    // whether the peer accepts version 3. Recreated for every link.
    private var receivedDelivery: LinkDeliveryCounter
        get() = carrierState0.receivedDelivery
        set(value) {
            carrierState0.receivedDelivery = value
        }
    private var peerDelivery: PeerDeliveryEstimate
        get() = carrierState0.peerDelivery
        set(value) {
            carrierState0.peerDelivery = value
        }
    private var deliveryReports: CallDeliveryReports
        get() = carrierState0.deliveryReports
        set(value) {
            carrierState0.deliveryReports = value
        }
    @Volatile private var largeFrameGraceUntilNs = 0L
    private var typicalVideoBytes = 0L
    private val pendingAudioPackets = ArrayList<OutboundMedia>(CONSTRAINED_COC_AUDIO_BATCH_SIZE)
    private var lastRecoveryKeyFrameRequestNs = 0L
    private val debugTxCounters = if (BuildConfig.DEBUG) DebugTxCounters() else null
    private val debugTxFinalReported = AtomicBoolean(false)
    private var lastDebugTxReportNs = 0L
    private val debugPipelineTiming = BuildConfig.DEBUG && debugPipelineTiming
    private val debugAudioBatchWait = if (this.debugPipelineTiming) RollingLatencyStats() else null
    private val debugVideoQueueWait = if (this.debugPipelineTiming) RollingLatencyStats() else null
    private val debugAudioQueueWait = if (this.debugPipelineTiming) RollingLatencyStats() else null
    private val debugMediaWriteWait = if (this.debugPipelineTiming) RollingLatencyStats() else null
    private val debugMediaFlushWait = if (this.debugPipelineTiming) RollingLatencyStats() else null
    private var debugStageTrace: DebugStageTrace? = null

    @SuppressLint("MissingPermission")
    fun startAvailability() {
        check(running.get()) { "Private-call endpoint is closed" }
        check(listenerThread == null && gattServer == null) { "Private-call availability already started" }
        openCallListener()
        // close() may have run while the listener opened (a stream join overtook this start): stop what was opened
        // here instead of leaving a call listener up beside the viewer (STREAM-INTEROP block 1).
        val published = listenerStartFence.publish(
            stopOpened = { stopAvailability(forActiveConnection = false) },
        ) {
            if (rotatingAdvert) RotatingDiscovery.addBoundaryListener(boundaryListener)
            if (rotatingAdvert) NameWindowRuntime.addListener(windowListener)
            // Bluetooth ON recovery (BluetoothOnRecovery.kt): the listener's native parts die with the adapter.
            BluetoothEpoch.register(listenerEpochName, onOff = {}, onOn = ::recoverListenerAfterBluetoothOn)
        }
        check(published) { "Private-call endpoint closed while starting" }
        if (wifiOnlyListening) onStatus("Available for a private call over Wi-Fi")
        else onStatus("Available for a private call over ${endpointTransport.shortLabel}")
    }

    private val listenerEpochName = "call-listener-" + Integer.toHexString(System.identityHashCode(this))

    /**
     * Bluetooth ON recovery (C): turning Bluetooth off and on destroys the call listener's GATT server and its video service,
     * the CoC control server and listener, and the advertising set. The advertising watchdog alone brings back only the set, so a
     * caller discovered the phone and then failed with "GATT video service discovery failed" (lane15 a55m31-edge4-rerun). An
     * idle listener is closed and reopened through the same path as the rotating-ID restart ([onDiscoveryBoundary]): stopAvailability,
     * then openCallListener, with no silence gap. A call or exchange in progress is left to its own end-of-call restart.
     */
    private fun recoverListenerAfterBluetoothOn() {
        if (!BluetoothOnRecovery.enabled) return
        thread(name = "PrivateCallBluetoothOn") {
            val component = BluetoothOnRecovery.COMPONENT_CALL_LISTENER
            val decision = synchronized(this) {
                BluetoothOnRecoveryPolicy.callListener(
                    running = running.get(),
                    idle = state == null && !callRunning.get(),
                    listening = listenerThread != null || gattServer != null || cocControlServer != null || classicAdvertiser != null || wifiOnlyListening,
                )
            }
            if (!decision.restart) {
                BluetoothOnRecovery.log(component, "skipped", decision.reason, "transport=${endpointTransport.shortLabel}")
                return@thread
            }
            synchronized(this) {
                if (!running.get() || state != null || callRunning.get()) return@thread
                listenerGeneration++
                stopAvailability(forActiveConnection = false)
                advertisedEpoch = -1L
            }
            val stopsBefore = synchronized(this) {
                if (!running.get() || state != null || callRunning.get()) return@thread
                if (listenerThread != null || gattServer != null) return@thread
                fullAvailabilityStops
            }
            // Outside the monitor, as in onDiscoveryBoundary: opening waits for advertising callbacks on the main thread.
            val opened = runCatching { openCallListener() }
            synchronized(this) {
                if (fullAvailabilityStops != stopsBefore || !running.get()) {
                    stopAvailability(forActiveConnection = false)
                    BluetoothOnRecovery.log(component, "skipped", "closed_or_stopped_during_reopen", "transport=${endpointTransport.shortLabel}")
                    return@thread
                }
            }
            opened.onSuccess {
                BluetoothOnRecovery.log(component, "restarted", decision.reason, "transport=${endpointTransport.shortLabel}")
                onStatus("Available for a private call over ${endpointTransport.shortLabel}")
            }.onFailure { error ->
                WifiDiscovery.offers.clear(WifiOfferRole.CALL_OFFER)   // availability is paused: nothing stays on Wi-Fi (A8 review P1-2)
                BluetoothOnRecovery.log(
                    component, "skipped", "reopen_failed", "transport=${endpointTransport.shortLabel} | error=${error.message ?: error.javaClass.simpleName}",
                )
                onStatus("Private-call availability paused after Bluetooth came back: ${error.message ?: error.javaClass.simpleName}")
                reportReopenFailure(error)
            }
        }
    }

    /**
     * Rotating IDs: at an epoch boundary an idle listener stops (break-before-make), stays silent
     * for 1-8 s (RV12) and reopens with the new RDI on a new advertising set; on LE CoC stacks with
     * per-session listeners the PSM changes too (RV1). A call or connection in progress keeps its
     * link (availability is already stopped then); the next listener uses the current epoch (RV4).
     * A GATT central that is connected but not admitted yet is a connection in progress too (R1 C9): the
     * old address leaves the air at the boundary and the restart waits for it, bounded (BoundaryRestartPlan).
     */
    /**
     * R1 C9 (2026-11-10): an iPhone's call connected 1.1 s before a boundary and was orphaned when the restart
     * closed the GATT server under it (the call only counts as running once the central's subscribe is admitted,
     * 0.6-2.5 s after it connects). While a central is connected: stop advertising the old ID at once (the
     * address still leaves the air at the boundary) and wait, at most [BoundaryRestartPlan.SETUP_GRACE_MS], for
     * the call to start (keep its link: false) or the link to go (restart: true). Runs on the rotation thread.
     */
    private fun holdBoundaryForLinkInFlight(): Boolean {
        // The GATT call server, or the LE CoC control server (its GATT control link is up from the connect until the L2CAP accept).
        val server = gattServer
        val control = cocControlServer
        if (server == null && control == null) return true
        var waitedMs = 0L
        var held = false
        while (true) {
            val step = synchronized(this) {
                BoundaryRestartPlan.decide(
                    !running.get() || state != null || callRunning.get(),
                    server?.linkInFlight() == true || control?.linkInFlight() == true,
                    waitedMs,
                )
            }
            if (step == BoundaryRestartPlan.Step.STOP_ADVERT_AND_WAIT) {
                if (!held) {
                    runCatching { server?.stopAdvertisingNow() }
                    runCatching { control?.stopAdvertising() }
                }
                held = true
                try {
                    Thread.sleep(BoundaryRestartPlan.POLL_MS)
                } catch (_: InterruptedException) {
                    return false
                }
                waitedMs += BoundaryRestartPlan.POLL_MS
                continue
            }
            if (held && BuildConfig.DEBUG) {
                Log.i("BluetoothVideo", "BENCHMARK rotid_boundary_hold: role=call | waitedMs=$waitedMs | outcome=${BoundaryRestartPlan.outcome(step)}")
            }
            return step != BoundaryRestartPlan.Step.SKIP
        }
    }

    private fun onDiscoveryBoundary(epoch: Long) {
        if (!running.get()) return
        // Also restarts when a Visible-by-handle window starts or ends (always on a new set, RV5).
        if (epoch == advertisedEpoch && RotatingDiscovery.visibleByHandle() == advertisedVisible) return
        thread(name = "PrivateCallIdRotation") {
            val silenceMs = RotatingDiscovery.boundarySilenceMs()
            if (!holdBoundaryForLinkInFlight()) return@thread
            synchronized(this) {
                if (!running.get() || state != null || callRunning.get()) return@thread
                if (listenerThread == null && gattServer == null && !wifiOnlyListening) return@thread
                listenerGeneration++
                stopAvailability(forActiveConnection = false)
                advertisedEpoch = -1L
            }
            try {
                Thread.sleep(silenceMs)
            } catch (_: InterruptedException) {
                return@thread
            }
            val stopsBefore = synchronized(this) {
                if (!running.get() || state != null || callRunning.get()) return@thread
                if (listenerThread != null || gattServer != null) return@thread
                fullAvailabilityStops
            }
            // Outside the monitor: opening waits for advertising callbacks on the main thread, where
            // startOutgoing may be waiting for this monitor (review finding 2026-10-10).
            val opened = runCatching { openCallListener() }
            synchronized(this) {
                // A call, exchange or close stopped availability while this reopen ran: stop what it
                // published, exactly as that stop would have. An incoming connection does not count.
                if (fullAvailabilityStops != stopsBefore || !running.get()) {
                    stopAvailability(forActiveConnection = false)
                    return@thread
                }
            }
            opened.onSuccess {
                if (BuildConfig.DEBUG) {
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK rotid_restart: role=call | epoch=$advertisedEpoch | visible=$advertisedVisible | " +
                            "silenceMs=$silenceMs | transport=${endpointTransport.shortLabel}",
                    )
                }
            }.onFailure { error ->
                // The old ID never comes back (design 4.4), on Wi-Fi either (A8 review P1-2); the status line says availability dropped.
                WifiDiscovery.offers.clear(WifiOfferRole.CALL_OFFER)
                onStatus(
                    "Private-call availability paused while changing discovery ID: " +
                        (error.message ?: error.javaClass.simpleName),
                )
                reportReopenFailure(error)
            }
        }
    }

    private fun closeWindowAdvertiser() {
        synchronized(windowLock) {
            windowAdvertiser?.close()
            windowAdvertiser = null
        }
    }

    /** The window offer: this endpoint's own offer fields on version 8, the role 3 ID and the name hash. */
    private fun windowOffer(rdi: ByteArray, hash: ByteArray): DiscoveryAnnouncement = WindowOffer.announcement(
        rdi,
        hash,
        transportFlags = when (endpointTransport) {
            BluetoothTransportOption.BLE_GATT -> DiscoveryAnnouncement.FLAG_GATT_TRANSPORT
            else -> 0
        },
        capabilitySummary = VideoCodecMap.helloMaskToSummary(capabilities.encodeCodecMask and capabilities.decodeCodecMask),
    )

    /**
     * Starts or stops the window's sets to match the window and this endpoint: wanted only while the window is open, the
     * endpoint is listening and no call or exchange holds it. A phone that cannot start the sets says so and ends the window.
     */
    private fun syncWindowAdvertiser() {
        if (!rotatingAdvert || !running.get()) return
        thread(name = "NameWindowSync", isDaemon = true) {
            synchronized(windowLock) {
                val listening = listenerThread != null || gattServer != null || cocControlServer != null
                val wanted = NameWindowRuntime.active() && state == null && !callRunning.get() && listening
                if (!wanted) {
                    windowAdvertiser?.close()
                    windowAdvertiser = null
                    return@synchronized
                }
                if (windowAdvertiser != null) return@synchronized
                val advertiser = NameWindowAdvertiser(adapter, ::windowOffer, onStatus)
                if (advertiser.start()) {
                    windowAdvertiser = advertiser
                } else {
                    advertiser.close()
                    NameWindowRuntime.reportCannotShow()
                }
            }
        }
    }

    /**
     * Experimental partial-open cleanup (`test_listen_partial_cleanup`, off): an open that fails after the LE CoC listen
     * succeeded (the control server, for one) releases the parts this open created, so an orphaned lease cannot keep the
     * role busy (on Android 10 the next session would open a dedicated listener and leak one more registration). Off,
     * the parts stay open until the endpoint closes, as before.
     */
    private fun openCallListener() {
        val before = listOf<Any?>(serverSocket, cocListener, cocControlServer, classicAdvertiser, gattServer, dualSecondary)
        PartialOpen.run(
            CocAvailabilitySwitches.partialCleanup,
            release = { releasePartialListener(before) },
            log = { Log.i("BluetoothVideo", it) },
        ) { openCallListenerParts() }
    }

    /** Closes what the failed open created: every listener part that differs from [before] (the parts held when it began). */
    private fun releasePartialListener(before: List<Any?>) {
        cocControlServer?.takeIf { it !== before[2] }?.let { cocControlServer = null; runCatching { it.close() } }
        cocListener?.takeIf { it !== before[1] }?.let { cocListener = null; runCatching { it.close() } }
        serverSocket?.takeIf { it !== before[0] }?.let { serverSocket = null; runCatching { it.close() } }
        classicAdvertiser?.takeIf { it !== before[3] }?.let { classicAdvertiser = null; runCatching { it.close() } }
        gattServer?.takeIf { it !== before[4] }?.let { gattServer = null; runCatching { it.close() } }
        dualSecondary?.takeIf { it !== before[5] }?.let { dualSecondary = null; runCatching { it.close() } }
    }

    /**
     * A rotating-ID or Bluetooth-on reopen failed while idle: with the partial-open cleanup (or a watchdog stall) the
     * endpoint stops its availability and reports it unavailable, so the activity can show it and run the CoC -> GATT
     * fallback; otherwise only the status line says so, as before.
     */
    private fun reportReopenFailure(error: Throwable) {
        if (!CocAvailabilitySwitches.partialCleanup && error !is ListenStalledException) return
        synchronized(this) {
            if (!running.get() || state != null || callRunning.get()) return
            stopAvailability(forActiveConnection = false)
        }
        CallAvailabilityFaults.report(this, error)
    }

    @SuppressLint("MissingPermission")
    private fun openCallListenerParts() {
        val generation = listenerGeneration
        val visible = rotatingAdvert && RotatingDiscovery.visibleByHandle()
        val rotatingEntry = if (rotatingAdvert && !visible) RotatingDiscovery.advertise(RotatingDiscoveryIds.ROLE_CALL_OFFER) else null
        advertisedVisible = visible
        advertisedEpoch = rotatingEntry?.epoch ?: if (visible) RotatingDiscovery.currentEpoch() else -1L
        // DEBUG harness proof: which scanned rotating ID is this phone (the only line that binds an ID to its owner).
        if (BuildConfig.DEBUG && rotatingEntry != null) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK rotid_own: role=call | epoch=${rotatingEntry.epoch} | " +
                    "rdi=${rotatingEntry.rdi.joinToString("") { "%02x".format(it) }}",
            )
        }
        val baseAnnouncement = DiscoveryAnnouncement(
            nodeIdPrefix = rotatingEntry?.rdi ?: identity.nodeId.toByteArray()
                .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
            version = if (rotatingEntry != null) {
                DiscoveryAnnouncement.ROTATING_VERSION
            } else {
                DiscoveryAnnouncement.FIXED_PREFIX_VERSION
            },
            flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or
                DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or when (endpointTransport) {
                    BluetoothTransportOption.CLASSIC_RFCOMM ->
                        DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT
                    BluetoothTransportOption.BLE_GATT -> DiscoveryAnnouncement.FLAG_GATT_TRANSPORT
                    BluetoothTransportOption.BLE_L2CAP -> 0
                },
            capabilitySummary = VideoCodecMap.helloMaskToSummary(capabilities.encodeCodecMask and capabilities.decodeCodecMask),
            streamToken = 0,
            routeCost = 0,
            sequence = 0,
        )
        // Experimental relayed calls: the consent bit and the helper flag (identity when off or rotating).
        val relayedAnnouncement = if (relayedCallsOn) {
            val consent = relayHelper?.consentOn() == true
            val helping = relayHelper?.helperActive() == true
            relayHelper?.offerOpened(helping, consent)
            RelayedCalls.offerFor(
                baseAnnouncement,
                consentOn = consent,
                helperActive = helping,
                rotatingCompat = RelayedCallsFeature.rotatingCompat,
            )
        } else {
            baseAnnouncement
        }
        // Experimental hidden identities (BMH3): a version 2 offer carries the hint bit "this endpoint accepts BMH3
        // handshakes" (the same instance when the switch is off, so the offer is byte-identical).
        val announcement = Bmh3Hint.offerFor(relayedAnnouncement, Bmh3Feature.enabled)
        // Wider reach: the same bytes the Bluetooth advertiser sends are offered over Wi-Fi Aware; a peer that dials us there
        // arrives at acceptIncoming() exactly like a Bluetooth connection (BMH3/BMH2 dispatch by first-frame magic in establish()).
        // Offered only once this listener is really open (Wi-Fi only, or after the Bluetooth branch below succeeded): a failed
        // open must never leave this RDI published on Wi-Fi (A8 review P1-2).
        val wifiGeneration = generation
        val wifiAcceptor: (WifiNativeConnection) -> Unit = { native ->
            if (!running.get() || wifiGeneration != listenerGeneration) {
                native.close()
            } else {
                acceptIncoming(
                    CallConnection(
                        native.input, native.output, WifiCallNotices.LABEL, native.close,
                        socket = null, link = null, address = null,
                        carrierKind = CarrierKind.WIFI_AWARE,
                        onAuthenticated = native.admitted,
                    ),
                )
            }
        }
        if (!runCatching { adapter.isEnabled }.getOrDefault(false) && WifiDiscovery.widerReachOn()) {
            wifiOnlyListening = true   // no Bluetooth listener or advertiser exists in this state
            WifiDiscovery.publishOffer(WifiOfferRole.CALL_OFFER, announcement, wifiAcceptor)
            return
        }
        wifiOnlyListening = false
        when (endpointTransport) {
            BluetoothTransportOption.CLASSIC_RFCOMM -> {
                val activeServer = ClassicSockets.listen(adapter, "Bluetooth Private Video Call", ClassicRole.PRIVATE_CALL)
                serverSocket = activeServer
                classicAdvertiser = BleDiscoveryAdvertiser(adapter, announcement, onStatus)
                    .also(BleDiscoveryAdvertiser::start)
                listenerThread = thread(name = "PrivateCallClassicAccept") {
                    try {
                        val accepted = activeServer.accept()
                        ClassicRfcommCap.note(accepted)
                        acceptIncoming(socketConnection(accepted))
                    } catch (error: Exception) {
                        reportListenerFailure(error, generation)
                    }
                }
            }
            BluetoothTransportOption.BLE_L2CAP -> {
                // One long-lived listener per role (CocListeners): Android 10 never frees a closed
                // LE CoC server, so a listener per availability exhausted the A15s stack.
                val activeServer = CocListeners.listen(adapter, "private-call")
                cocListener = activeServer
                // Experimental dual listening (default off): the GATT call service first, the hint only once it
                // runs. Off: no server is created and the control server advertises `announcement` itself.
                CallListenInject.failControlIfRequested()
                val advertisedOffer = dualListeningOffer(announcement)
                cocControlServer = BleL2capControlServer(
                    appContext,
                    adapter,
                    activeServer.psm,
                    advertisedOffer,
                    onStatus,
                    phyPreference,
                ).also(BleL2capControlServer::start)
                listenerThread = thread(name = "PrivateCallCocAccept") {
                    try {
                        val socket = activeServer.accept()
                        cocControlServer?.stopAdvertising()
                        acceptIncoming(socketConnection(socket))
                    } catch (error: Exception) {
                        reportListenerFailure(error, generation)
                    }
                }
            }
            BluetoothTransportOption.BLE_GATT -> {
                gattServer = GattVideoServer(
                    appContext,
                    adapter,
                    announcement,
                    phyPreference,
                    onStatus,
                ) { activeConnection ->
                    // During an experimental message exchange a new connection may be a call that
                    // preempts it (acceptIncoming); otherwise exactly as before.
                    if (!running.get() || (callRunning.get() && messageSync == null)) {
                        activeConnection.close()
                    } else {
                        acceptIncoming(gattConnection(activeConnection))
                    }
                }.also {
                    it.callEndpoint = true
                    it.start()
                }
            }
        }
        WifiDiscovery.publishOffer(WifiOfferRole.CALL_OFFER, announcement, wifiAcceptor)   // the Bluetooth listener is open
        // A Visible-by-name window adds its sets to this listener (and follows its rotation).
        syncWindowAdvertiser()
    }

    /**
     * Experimental dual listening: starts this CoC endpoint's second listener (the GATT call service, in its
     * own server, sharing the control server's one advertising set) and returns the offer to advertise: the
     * hint is set only when that service is running. Never throws; failure means the plain CoC offer.
     */
    private fun dualListeningOffer(offer: DiscoveryAnnouncement): DiscoveryAnnouncement {
        if (!DualListeningFeature.enabled || !DualListeningWire.mayCarry(offer)) return offer
        // A second listener that already exists (it carries a link) is never registered twice.
        if (dualSecondary != null || linkTransportOverride != null) return offer
        val host = DualSecondaryHost(
            create = {
                GattDualSecondaryServer(appContext, adapter, offer, phyPreference, onStatus) { connection ->
                    // As a GATT endpoint does: an exchange may be preempted by a call, otherwise one call at a time.
                    if (!running.get() || (callRunning.get() && messageSync == null)) {
                        connection.close()
                    } else {
                        acceptIncoming(
                            gattConnection(connection, arrivedOn = BluetoothTransportOption.BLE_GATT),
                        )
                    }
                }
            },
            settle = DualListeningRuntime.settle,
            sleepMs = Thread::sleep,
            log = DualListeningRuntime::log,
        )
        dualSecondary = host
        val ready = host.open()
        if (!ready) {
            host.close()
            dualSecondary = null
        }
        val advertised = DualListeningWire.offerFor(offer, enabled = true, secondaryReady = ready)
        DualListeningRuntime.log(
            DualListeningLog.offer(DualListeningWire.hasHint(advertised), advertised.capabilitySummary, if (ready) "ready" else "failed"),
        )
        return advertised
    }

    /**
     * Experimental store-and-forward (second review N3): while an exchange holds the endpoint, a call
     * listener stays open so an incoming call can preempt the exchange instead of being refused.
     * On LE CoC this costs one more listen registration (counted by CocRegistrations).
     */
    private fun reopenListenerForCalls() {
        if (!running.get()) return
        runCatching {
            when (endpointTransport) {
                BluetoothTransportOption.BLE_GATT -> if (gattServer == null) openCallListener()
                else -> if (serverSocket == null && cocListener == null) {
                    cocControlServer?.close()
                    cocControlServer = null
                    openCallListener()
                }
            }
        }.onFailure { Log.w("BluetoothVideo", "Store-forward: call listener during an exchange unavailable", it) }
    }

    /** What this phone sends in the current call: VOICE starts no camera, encoder or video packet. */
    val localCallType: CallType
        get() = negotiation?.localType ?: CallType.VIDEO

    /** A BVMID2 verification scan shows no call screen: its phases are not reported to the UI. */
    @Volatile private var historyAnswered = false
    @Volatile private var historyDeclined = false
    @Volatile internal var endedRecentCall: RecentCall? = null
        private set
    private var historyTarget: RecentCall? = null

    private fun notifyPhase(phase: PrivateCallState) {
        if (phase.phase == PrivateCallPhase.NEGOTIATING_MEDIA || phase.phase == PrivateCallPhase.ACTIVE) historyAnswered = true
        if (qrScanPayload == null) onPhase(phase)
    }

    /** True while this endpoint scans a verification code (it dials like a call but is not one). */
    val isQrScan: Boolean get() = qrScanPayload != null

    /** DEBUG: this call's ID for benchmark lines (`state` is never cleared, so it outlives the end); null in release. */
    val benchmarkCallId: String? get() = if (BuildConfig.DEBUG) state?.callId?.toString() else null

    /**
     * The stalled first link's ACL-down watch (SetupRetryAclWait, test_setup_retry_acl_wait): armed by the stall guard before it
     * closes the link, awaited by the 1M retry before it dials, released by the connect thread in every case.
     */
    private val stallAclWatch = AtomicReference<AclDownWatch?>(null)

    /** The connect-retry switches as one configuration (read at each use, like the DebugTunables it replaces). */
    private val connectConfig: ConnectRetryConfig get() = ConnectRetryConfig.current()

    @Synchronized
    fun startOutgoing(peer: BleL2capPeer, callType: CallType = CallType.VIDEO): Boolean {
        if (!canStartOutgoingPrivateCall(running.get(), state, callRunning.get())) return false
        // An offer above the app's relay limit is refused, not clamped (PrivateCallRelayChain).
        val hops = PrivateCallRelayChain.callerRelayHops(peer.announcement.routeCost) ?: return false
        // Rotating IDs: a version 5 offer needs this phone's switch on (it can only decode one then). A relayed
        // one (phase 1b: relays mirror it) is verified end to end by the BDX1 OPENING. Classic offers never
        // rotate (rotatingAdvert), so a version 5 Classic offer is refused.
        if (peer.announcement.rotating && (
                !rotatingIds ||
                    peer.announcement.flags and DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT != 0
                )
        ) {
            return false
        }
        // A relayed row needs the relayed-call runtime on this endpoint, and a benchmark that requires a chain
        // never accepts a direct (or DEBUG-relay) route: direct fallback is a failed requested chain.
        if (peer.relayRoute != null && !relayedCallsOn) return false
        if (relayedCallsOn && RelayedCallsFeature.requireRelays > 0 && peer.relayRoute == null) return false
        // test_relayed_stranger_calls=false (control arm): a relayed call goes only to a target this phone tags as a contact.
        if (peer.relayRoute != null && !DebugTunables.relayedStrangerCalls &&
            Bmh3ContactTags.initiatorTag(
                peer.announcement,
                RotatingDiscoveryIds.wallEpoch(System.currentTimeMillis() / 1000L),
                PresenceLeaseSource,
            ) == null
        ) {
            return false
        }
        negotiation = VoiceCallNegotiation.outgoing(voiceCallsEnabled, callType)
        relayHops = hops
        pendingRelayRoute = if (relayedCallsOn) peer.relayRoute else null
        // Which helper this relayed dial selected (the "Calling relayed [target] via N" line names only the target):
        // without it a route through a foreign helper phone cannot be told apart in a run.
        pendingRelayRoute?.let { route ->
            val addressType = if (android.os.Build.VERSION.SDK_INT >= 35) runCatching { peer.device.addressType }.getOrNull() else null
            Log.i("BluetoothVideo", RelayHopPolicy.callerRouteLine(route, peer.device.address, addressType, BuildConfig.DEBUG))
        }
        relaySetupStartedNs = System.nanoTime()
        activeSettings = routeSettings(relayHops)
        val initial = PrivateCallState.outgoing()
        state = initial
        CallJoinStages.begin("caller")
        CallJoinStages.current.callId(if (BuildConfig.DEBUG) initial.callId.toString() else null)
        CallJoinStages.mark(CallJoinStage.DIAL)
        historyTarget = if (peer.announcement.rotating) {
            peer.announcement.rotatingId()?.let { StrangerNames.derive(StrangerNames.Input.rdi(it)) }?.let {
                RecentCall("", callType == CallType.VIDEO, 0, strangerName = it.text.lowercase())
            }
        } else RecentCall(peer.announcement.nodeIdPrefix.joinToString("") { "%02x".format(it) }, callType == CallType.VIDEO, 0)
        notifyPhase(initial)
        // Experimental dual listening (default off): the transports this call attempt may use, in order.
        linkTransportOverride = null
        dualPlan = DualCallerPlan.plan(endpointTransport, peer.announcement, DualListeningFeature.enabled)
        if (DualListeningFeature.enabled) DualListeningRuntime.log(DualListeningLog.plan(endpointTransport, dualPlan))
        stopAvailability(forActiveConnection = false)
        val candidates = RelayCandidates(peer.relayRoute?.takeIf { relayedCallsOn }, SystemClock.elapsedRealtime())
        connectionThread = thread(name = "PrivateCallConnect") { dialOutgoing(peer, candidates) }
        return true
    }

    /**
     * One outgoing call attempt to [peer] on the connect thread: the first link (with its bounded 2M -> 1M retry), the
     * relayed SETUP when [peer] is a relayed row, then the call. A relayed route that fails before READY may hand over to
     * the next candidate route ([nextRelayCandidate]); every other failure ends the call as before.
     */
    private fun dialOutgoing(peer: BleL2capPeer, candidates: RelayCandidates) {
        var nextCandidate: BleL2capPeer? = null
        relayReadyThisAttempt = false
        // Design OR (DEBUG prototype): the physical dials of this search request, and the parallel direct check.
        seekDialsThisRequest = 1
        val directWatch = peer.relayRoute?.seek?.let { startSeekDirectWatch(it.attempt) }
        val direct = peer.announcement.routeCost == 0
        offerViaPrefix = if (direct) null else AdaptivePhyMemory.prefixLabel(peer.announcement.nodeIdPrefix)
        // The bad-2M memory key of this offer: a rotating ID recognised as one contact keys by that contact's node, the
        // key the 1M retry records (test_phy_memory_contact_key); the recognition is local only.
        val memoryNode = if (peer.wifiOnly) null else AdaptivePhyMemory.offerMemoryNode(
            rotating = peer.announcement.rotating,
            nodePrefix = peer.announcement.nodeIdPrefix,
            contactKey = DebugTunables.phyMemoryContactKey,
            recognisedContact = { RotatingDiscovery.recognize(peer.announcement).singleOrNull()?.contact?.shortLabel() },
        )
        // Relayed calls (test_relayed_first_hop_direct): before SETUP the first hop of an on-demand route is an ordinary
        // link to the selected, idle helper (it relays nothing yet), so that helper's own bad-2M memory applies, keyed
        // like a direct offer of it (A15s -> ASUS stalled on 2M as the first hop of 4 of 4 relay-showcase calls).
        val helperRoute = peer.relayRoute?.takeIf {
            relayedCallsOn && DebugTunables.relayedFirstHopDirect && transport != BluetoothTransportOption.CLASSIC_RFCOMM
        }
        val helperMemoryNode = helperRoute?.let { route ->
            AdaptivePhyMemory.offerMemoryNode(
                rotating = route.rotating,
                nodePrefix = route.helperPrefix,
                contactKey = DebugTunables.phyMemoryContactKey,
                recognisedContact = {
                    RotatingDiscovery.recognize(RelayedCalls.helperOfferFor(route.helperPrefix))
                        .singleOrNull()?.contact?.shortLabel()
                },
            )
        }
        val firstPhy = if (peer.wifiOnly) phyPreference else if (transport != BluetoothTransportOption.CLASSIC_RFCOMM) {
            val offerPhy = AdaptivePhyMemory.phyFor(
                transport,
                peer.announcement.nodeIdPrefix,
                phyPreference,
                "call",
                relayedOffer = !direct,
                memoryNode = memoryNode,
            )
            if (helperRoute != null && offerPhy == phyPreference) {
                AdaptivePhyMemory.phyFor(
                    transport,
                    helperRoute.helperPrefix,
                    phyPreference,
                    "call-helper",
                    relayedOffer = false,
                    memoryNode = helperMemoryNode,
                )
            } else {
                offerPhy
            }
        } else {
            phyPreference
        }
        // Experimental test_call_1m_first (JT2-P3, default off): a direct GATT dial opens on 1M with no PHY request; the call asks
        // for 2M after its first picture when it needs it (noteRemotePictureShown), and the 2M -> 1M setup retry below is inert
        // for it (setupPhy). Off: firstPhy, the setup retry and the stall guard are exactly as before.
        val oneMFirst = CallOneMFirst.appliesToDial(
            enabled = PhySwitches.call1mFirst,
            gatt = transport == BluetoothTransportOption.BLE_GATT,
            dualListening = dualPlan.size > 1,
            direct = direct,
            wifiOnly = peer.wifiOnly,
            relayedRoute = peer.relayRoute != null,
            firstPhy = firstPhy,
            le2MSupported = LinkCapacity.features?.le2MPhy != false,
            // Experimental test_call_1m_first_coc: the same start for a direct CoC dial (the control link asks for 1M).
            cocEnabled = PhySwitches.call1mFirstCoc,
            coc = transport == BluetoothTransportOption.BLE_L2CAP,
        )
        val setupPhy = CallOneMFirst.setupPhy(firstPhy, oneMFirst)
        phyFirstGate = if (oneMFirst) CallPhyUpgradeGate(linkLimitUpgrade = PhySwitches.call1mFirstLinkLimit) else null
        if (oneMFirst) {
            Log.i(
                "BluetoothVideo",
                CallOneMFirst.linkPhyLine("1M", "1M", "call_1m_first", offerLogLabel(peer.announcement), "deferred=true"),
            )
        }
        val startedAtMs = SystemClock.elapsedRealtime()
        // Experimental test_peer_address_book: a direct callee whose address is stale or replaced is
        // rescanned by node prefix first (same prefix and route cost; the handshake verifies identity).
        val firstPeer = if (PeerAddressBook.enabled() && direct && transport != BluetoothTransportOption.CLASSIC_RFCOMM && !peer.wifiOnly) {
            val key = PeerAddressBook.prefixKey(peer.announcement.nodeIdPrefix)
            val reason = PeerAddressBook.shared.rescanReason(key, peer.device.address)
            if (reason == null) {
                peer
            } else {
                rediscoverPeer(peer, startedAtMs).also { fresh ->
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK peer_address: link=call | node=$key | reason=$reason | " +
                            "rescanned=${fresh !== peer} | sameAddress=${fresh.device.address == peer.device.address}",
                    )
                }
            }
        } else {
            peer
        }
        // Experimental test_phy_direction_split: a direct GATT peer remembered with one failing 2M direction starts split.
        dialPhySplit = if (direct && firstPhy == phyPreference && !oneMFirst && transport == BluetoothTransportOption.BLE_GATT) {
            memoryNode?.let { node -> AdaptivePhyMemory.splitFor(transport, node, "call") }?.let { split -> firstPeer.device.address to split }
        } else {
            null
        }
        // Experimental test_own_2m_memory (OwnControllerTwoM): this dial's outcome is evidence about this phone's own controller
        // only when it is a symmetric 2M setup to a hop that carries nothing else.
        val ownTwoMEvidence = OwnControllerTwoM.symmetricTwoMSetup(
            setupPhy = setupPhy,
            le = transport != BluetoothTransportOption.CLASSIC_RFCOMM && !peer.wifiOnly,
            idleHop = direct || helperRoute != null,
            splitApplied = dialPhySplit?.first == firstPeer.device.address,
            blePhyArm = DebugTunables.experimentalBlePhy != null,
            staticSplit = DebugTunables.experimentalPhySplit != null,
        )
        // Experimental test_slow_2m_in_place (SlowTwoMInPlace): a stalled or crawling first symmetric 2M link of a direct offer
        // with no memory key (an unrecognised rotating offer) asks the same link for 1M before today's close and retry.
        val slowTwoMInPlace = SlowTwoMInPlace.applies(
            enabled = PhySwitches.slow2mInPlace,
            setupPhy = setupPhy,
            le = transport != BluetoothTransportOption.CLASSIC_RFCOMM && !peer.wifiOnly,
            direct = direct,
            hasMemoryKey = memoryNode != null,
            splitApplied = dialPhySplit?.first == firstPeer.device.address,
            blePhyArm = DebugTunables.experimentalBlePhy != null,
            staticSplit = DebugTunables.experimentalPhySplit != null,
        )
        try {
            try {
                // One setup deadline for this dial (SetupDeadline): inner GATT/CoC retries may only use the part of the
                // early-loss window that still leaves room for the 2M -> 1M setup retry below (leSetupRetryAllowed), and the
                // Experimental test_fast_0x3e_dials (off) redials a fast 0x3E on this first dial with jitter (FastEstablishDials).
                val retryPossible = adaptivePhySetupRetryAllowed(setupPhy, 0L)
                val firstDeadline = SetupDeadline.forFirstDial(
                    startedAtMs + AdaptivePhyMemory.EARLY_LOSS_MS,
                    boundsOrdinary = retryPossible,
                    fastDials = connectConfig.fastFirstDial,
                )
                // Experimental test_setup_stall_ms (review A14): the guard's arm, e.g. 2000; 0 keeps SETUP_STALL_MS.
                val stallGuardMs = if (!peer.wifiOnly && retryPossible && DebugTunables.phySetupStallGuard) connectConfig.stallGuardMs() else 0L
                openAndEstablishOutgoing(firstPeer, setupPhy, stallGuardMs, firstDeadline, deferPhy = oneMFirst, slowTwoMInPlace = slowTwoMInPlace)
                // A link moved to 1M in place (test_slow_2m_in_place) was no clean symmetric 2M setup.
                if (ownTwoMEvidence && stallGuardMs > 0L && !slowTwoMMovedThisLink) AdaptivePhyMemory.recordOwnTwoMSuccess()
            } catch (error: IOException) {
                if (PeerAddressBook.enabled()) {
                    val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                    val status = leSetupStatus(error.message)
                    val action = LeConnectFailureClassifier.classify(elapsedMs, status, fastRetriesUsed = 0)
                    Log.i("BluetoothVideo", LeConnectFailureClassifier.line("call", elapsedMs, status, action))
                }
                if (ownTwoMEvidence && error is CallSetupStalledException) AdaptivePhyMemory.recordOwnTwoMStall("setup_stall")
                if (peer.wifiOnly || !adaptivePhySetupRetryAllowed(setupPhy, SystemClock.elapsedRealtime() - startedAtMs)) {
                    throw error
                }
                // Review A6: whether the failed 2M attempt is evidence against 2M (a stall, or a link lost after its hop
                // authenticated), not a connect failure (status 133, discovery, "no channel") that 1M would not avoid.
                val firstAttemptRemembers = TwoMRetryMemory.remembers(
                    firstStalled = error is CallSetupStalledException,
                    firstHopAuthenticated = hopAuthenticatedThisLink,
                )
                val node = offerLogLabel(peer.announcement)
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK adaptive_phy: link=call | node=$node | from=2M to=1M | reason=setup_retry | " +
                        "attempt=2 | error=${error.message ?: error.javaClass.simpleName}",
                )
                onStatus("Call setup on LE 2M failed; retrying once on LE 1M")
                // A stalled 2M setup is the pair's signature (healthy setups authenticate within about 1 s): remember it now.
                // The retry may not find the callee again (b2-weaklink-ab call-a15s-asus-control-r1: no new advertisement
                // within 8 s, then 12 s to the old address) and then recorded nothing, so every later call repeated the stall.
                AdaptivePhyMemory.advertisedPeerToRemember(
                    enabled = DebugTunables.stallRemembersTwoM && DebugTunables.adaptivePhyMemory,
                    stalled = error is CallSetupStalledException,
                    direct = direct,
                    rotating = peer.announcement.rotating,
                    nodePrefix = peer.announcement.nodeIdPrefix,
                    contactNode = if (DebugTunables.phyMemoryContactKey) memoryNode else null,
                )?.let { AdaptivePhyMemory.recordBad(transport, it, "setup_stall") }
                // The same for the selected helper of an on-demand route: the stall guard fires before hop authentication,
                // so before SETUP, while the helper carries no other link (test_relayed_first_hop_direct).
                if (helperRoute != null) {
                    AdaptivePhyMemory.advertisedPeerToRemember(
                        enabled = DebugTunables.stallRemembersTwoM && DebugTunables.adaptivePhyMemory,
                        stalled = error is CallSetupStalledException,
                        direct = true,
                        rotating = helperRoute.rotating,
                        nodePrefix = helperRoute.helperPrefix,
                        contactNode = if (DebugTunables.phyMemoryContactKey) helperMemoryNode else null,
                    )?.let { AdaptivePhyMemory.recordBad(transport, it, "relayed_first_hop_stall") }
                }
                discardFailedOutgoingSetup()
                seekDialsThisRequest = 2
                // The stalled link's ACL may linger 1-4 s after the close; a dial into it fails at once (0x3E/0x0B).
                // Review T1: the wait runs while the retry rescans (no longer one after the other), bounded per stack.
                val retryStartedMs = SystemClock.elapsedRealtime()
                val ownBudget = connectConfig.stallRetryOwnBudget && error is CallSetupStalledException
                val aclWait = stallAclWatch.getAndSet(null)?.let { watch ->
                    // Experimental test_stall_retry_own_budget (review A10): no ACL wait; the stall-retry dials absorb it.
                    if (!StallRetryOwnBudget.waitsForAcl(ownBudget)) {
                        watch.release()
                        return@let null
                    }
                    thread(name = "SetupRetryAclWait", isDaemon = true) {
                        SetupRetryAclWait.await(watch, SetupRetryAclWait.boundMs(android.os.Build.VERSION.SDK_INT))
                    }
                }
                val retryPeer = if (adaptivePhyRetryRediscovers(DebugTunables.phySetupStallGuard, peer.announcement.routeCost)) {
                    rediscoverPeer(
                        peer, startedAtMs, stalled = error is CallSetupStalledException,
                        // test_slow_2m_in_place: after its fallback close, scan until the callee's incoming deadline released it.
                        inPlaceCloseSinceOpenMs = slowTwoMCloseSinceOpenMs.takeIf { it >= 0L && error is CallSetupStalledException },
                    )
                } else if (helperRoute != null && DebugTunables.phySetupStallGuard) {
                    // The helper dropped the dead link and re-advertised under a new address (relay-showcase p01/g04: the
                    // retry redialed the old address until "BLE control did not respond" / "Timed out connecting GATT video").
                    rediscoverPeer(peer, startedAtMs, stalled = error is CallSetupStalledException, helperRoute = helperRoute)
                } else {
                    peer
                }
                aclWait?.join(SetupRetryAclWait.BOUND_MS + ACL_WAIT_JOIN_SLACK_MS)
                // The stall retry's own deadline (StallRetryDials, default on): more fast-0x3E dials inside the early-loss
                // window, or inside its own 10 s with the Experimental test_stall_retry_own_budget.
                val retryDeadline = if (error is CallSetupStalledException && connectConfig.stallRetryDials) {
                    SetupDeadline.forStallRetry(
                        StallRetryOwnBudget.deadlineMs(ownBudget, retryStartedMs, startedAtMs + AdaptivePhyMemory.EARLY_LOSS_MS),
                    )
                } else {
                    null
                }
                openAndEstablishOutgoing(retryPeer, BlePhyPreference.ONE_M_ADAPTIVE, deadline = retryDeadline)
                // Requested vs effective PHY of this call link, explicit for the scorer (2M is the app's
                // adaptive default here: test_ble_phy has no 2M value, so source=adaptive).
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK link_phy requested=2M effective=1M " +
                        "reason=${if (error is CallSetupStalledException) "setup_stall" else "setup_retry"} " +
                        "source=adaptive link=call node=$node",
                )
                // The 1M retry authenticated: remember this hop peer as bad on 2M (not a relaying hop), when the 2M
                // failure was a stall or an early loss (review A6).
                if (firstAttemptRemembers && !hopRelaysSkipsMemory()) {
                    secureSession?.peerNodeId?.shortLabel()?.let {
                        AdaptivePhyMemory.recordBad(transport, it, "setup_retry")
                    }
                    offerViaPrefix?.let { AdaptivePhyMemory.recordBadVia(transport, it, "setup_retry") }
                }
            }
        } catch (error: Exception) {
            relayRouteUnverified?.let { unverified ->
                relayHelper?.routeOutcome(unverified, false, null)
                relayRouteUnverified = null
            }
            // Relayed calls (test_relayed_candidates): a route that failed before READY tries the next-best
            // route through a different helper inside this attempt, instead of ending the call.
            nextCandidate = nextRelayCandidate(peer, error, candidates)
            if (nextCandidate == null) {
                fail(
                    if (error is PcNeedsBmh3Exception) {
                        PcCallTargets.BMH3_OFF_MESSAGE
                    } else if (peer.relayRoute?.seek != null && !relayReadyThisAttempt) {
                        "Could not find this contact through phones nearby"
                    } else {
                        privateCallConnectionFailureMessage(transport, error)
                    },
                )
            }
        } finally {
            stallAclWatch.getAndSet(null)?.release()
            directWatch?.interrupt()
        }
        nextCandidate?.let { next ->
            discardFailedOutgoingSetup()
            dialOutgoing(next, candidates)
        }
    }

    /** The caller's verified route: marked used only after the target's identity checked out (reveal or opening included). */
    private fun markRelayRouteVerified() {
        relayRouteUnverified?.let { verified ->
            relayHelper?.routeOutcome(verified, true, null)
            relayRouteUnverified = null
        }
    }

    /** Set when this attempt's relayed SETUP reached READY: from then on a failure is the call's, never a candidate's. */
    @Volatile private var relayReadyThisAttempt = false

    /** Design OR: physical dials of the current search request (1, or 2 after the 1M retry). */
    @Volatile private var seekDialsThisRequest = 1

    /** Design OR: the contact's own offer appeared during a search request; the request link was closed for a direct call. */
    @Volatile private var seekDirectSeen = false

    /**
     * Design OR (DEBUG prototype, revision 3 caller rule 2): while a search request runs, the contact's own offer appearing
     * before READY closes the request link, and the next candidate is the direct call. Not under a required chain.
     */
    private fun startSeekDirectWatch(attempt: RelaySeekAttempt): Thread? {
        if (RelayedCallsFeature.requireRelays > 0) return null
        seekDirectSeen = false
        return thread(name = "RelaySeekDirectWatch", isDaemon = true) {
            try {
                while (!relayReadyThisAttempt && running.get() && !attempt.finished) {
                    if (attempt.directPeer() != null) {
                        seekDirectSeen = true
                        connection?.let { runCatching { it.close() } }
                        return@thread
                    }
                    Thread.sleep(SEEK_DIRECT_POLL_MS)
                }
            } catch (_: InterruptedException) {
                // The request ended.
            }
        }
    }

    /**
     * Design OR: after a search request failed before READY, the next request of the rings, or the contact's own offer
     * (the parallel direct check), or null when the attempt is over (it then releases the caller's relay duty).
     */
    private fun nextSeekCandidate(seek: RelaySeekRoute, error: Exception): BleL2capPeer? {
        val attempt = seek.attempt
        val stillDialing = running.get() && state?.phase == PrivateCallPhase.OUTGOING_INVITE
        val failure = error as? RelaySetupFailedException
        val outcome = when {
            failure?.seekOutcome == "binding" -> RelaySeekRings.Outcome.BINDING_FAILED
            failure?.seekOutcome == "no_seek" -> RelaySeekRings.Outcome.NO_SEEK
            failure?.seekOutcome == "ambiguous" -> RelaySeekRings.Outcome.AMBIGUOUS
            failure != null -> RelaySeekRings.outcomeFor(failure.reason)
            else -> RelaySeekRings.Outcome.AMBIGUOUS
        }
        attempt.record(seek, outcome, seekDialsThisRequest)
        val prefix = if (BuildConfig.DEBUG) "BENCHMARK " else ""
        Log.i(
            "BluetoothVideo",
            prefix + RelaySeekLog.line(
                "caller", "failed", seek.request.ring, seek.request.budget, seek.request.selector.kind,
                dials = seekDialsThisRequest, candidate = attempt.rings.requests, reason = outcome.name.lowercase(),
            ),
        )
        if (relayReadyThisAttempt || !stillDialing) {
            attempt.finish(found = relayReadyThisAttempt, backoff = false)
            return null
        }
        if (RelayedCallsFeature.requireRelays == 0) {
            attempt.directPeer()?.let { direct ->
                Log.i("BluetoothVideo", prefix + RelaySeekLog.line("caller", "direct", reason = if (seekDirectSeen) "preempted" else "between"))
                attempt.finish(found = true)
                synchronized(this) {
                    relayHops = 0
                    pendingRelayRoute = null
                    relaySetupStartedNs = System.nanoTime()
                    activeSettings = routeSettings(0)
                    dualPlan = DualCallerPlan.plan(endpointTransport, direct.announcement, DualListeningFeature.enabled)
                }
                return direct
            }
        }
        val next = attempt.next(mayWait = true)
        if (next == null) {
            attempt.finish(found = false)
            return null
        }
        val nextRoute = next.relayRoute ?: return null
        synchronized(this) {
            relayHops = nextRoute.relays
            pendingRelayRoute = nextRoute
            relaySetupStartedNs = System.nanoTime()
            activeSettings = routeSettings(relayHops)
            dualPlan = DualCallerPlan.plan(endpointTransport, next.announcement, DualListeningFeature.enabled)
        }
        return next
    }

    /**
     * Relayed calls (DECISIONS Design C "candidate iteration", `test_relayed_candidates`): after [peer]'s relayed route failed
     * before READY (hop open or handshake, setup stall, a helper's FAIL, the helper's pre-setup timeout), the next-best
     * route to the same target through a helper not tried yet, or null. The route is the reach table's own choice
     * ([RelayedCallsRuntime.peerFor]: exact relay count under `require`, failed pairs skipped), every hop is authenticated
     * and checked exactly as on the first try, and the attempt keeps its call state; at most
     * [RelayedCalls.MAX_CANDIDATES] routes inside [RelayedCalls.CANDIDATE_WINDOW_MS].
     */
    private fun nextRelayCandidate(peer: BleL2capPeer, error: Exception, candidates: RelayCandidates): BleL2capPeer? {
        val route = peer.relayRoute ?: return null
        route.seek?.let { seek -> return nextSeekCandidate(seek, error) }
        val stillDialing = running.get() && state?.phase == PrivateCallPhase.OUTGOING_INVITE
        val elapsedMs = SystemClock.elapsedRealtime() - candidates.startedMs
        if (!RelayCandidatePolicy.mayTryNext(
                DebugTunables.relayedCandidates && relayedCallsOn, candidates.attempts, elapsedMs, relayReadyThisAttempt, stillDialing,
            )
        ) {
            return null
        }
        // The failed pair is skipped by the table (a helper FAIL already marked it with its reason, which a second,
        // untyped report would overwrite; a hop failure has not been reported yet). Review A9: one outcome per route.
        if (error !is RelaySetupFailedException) relayHelper?.routeOutcome(route, false, null)
        val next = RelayedCallsRuntime.peerFor(route.target, transport, route.rotating) ?: return null
        val nextRoute = next.relayRoute ?: return null
        if (!RelayCandidatePolicy.acceptable(route, nextRoute, candidates.triedHelpers)) return null
        val hops = PrivateCallRelayChain.callerRelayHops(next.announcement.routeCost) ?: return null
        candidates.tried(nextRoute.helperPrefix)
        Log.i(
            "BluetoothVideo",
            RelayCandidatePolicy.nextLine(candidates.attempts, nextRoute, elapsedMs, error, BuildConfig.DEBUG),
        )
        synchronized(this) {
            relayHops = hops
            pendingRelayRoute = nextRoute
            relaySetupStartedNs = System.nanoTime()
            activeSettings = routeSettings(relayHops)
            dualPlan = DualCallerPlan.plan(endpointTransport, next.announcement, DualListeningFeature.enabled)
        }
        val addressType = if (android.os.Build.VERSION.SDK_INT >= 35) runCatching { next.device.addressType }.getOrNull() else null
        Log.i("BluetoothVideo", RelayHopPolicy.callerRouteLine(nextRoute, next.device.address, addressType, BuildConfig.DEBUG))
        return next
    }

    /**
     * Opens and authenticates one outgoing link. With [stallGuardMs] > 0 (the first 2M attempt that can
     * still retry on 1M), a link whose hop authentication has not finished [stallGuardMs] after it opened
     * is closed so the setup fails fast into the 1M retry (see [AdaptivePhyMemory.SETUP_STALL_MS]).
     */
    private fun openAndEstablishOutgoing(
        peer: BleL2capPeer,
        linkPhy: BlePhyPreference,
        stallGuardMs: Long = 0L,
        deadline: SetupDeadline? = null,
        deferPhy: Boolean = false,
        /** Experimental test_slow_2m_in_place: a stall or crawl of this guarded 2M link first asks it for 1M ([SlowTwoMInPlace]). */
        slowTwoMInPlace: Boolean = false,
    ) {
        hopAuthenticatedThisLink = false
        slowTwoMMovedThisLink = false
        slowTwoMCloseSinceOpenMs = -1L
        // One BMH3 plan for every handshake of this attempt (a Classic connector's included): one choice, one throwaway.
        val plan = attemptPlan(peer.announcement)
        val openedConnection = try {
            openOutgoing(peer, linkPhy, plan, deadline, deferPhy)
        } catch (error: Exception) {
            // A dual attempt already tried GATT too: the safety net's retry would repeat that.
            if (running.get() && transport == BluetoothTransportOption.BLE_L2CAP && dualPlan.size < 2 && !peer.wifiOnly) {
                onCocConnectFailed(error.message ?: error.javaClass.simpleName)
            }
            throw error
        }
        // Experimental test_slow_2m_in_place: the watched attempt notes when the peer's bytes arrive (the bytes pass unchanged).
        val slowWatch = if (slowTwoMInPlace && stallGuardMs > 0L && openedConnection.movePhy != null) SlowTwoMInPlace.Watch(stallGuardMs) else null
        val slowProgress = slowWatch?.let { SlowTwoMInPlace.Progress(SystemClock::elapsedRealtime) }
        val activeConnection = if (slowProgress != null) {
            openedConnection.copy(input = SlowTwoMInPlace.ProgressInputStream(openedConnection.input, slowProgress))
        } else {
            openedConnection
        }
        val setupState = CallSetupStallState()
        val settled = CountDownLatch(1)
        val openedAtMs = SystemClock.elapsedRealtime()
        val node = offerLogLabel(peer.announcement)
        val hopAuthAtMs = AtomicLong(-1L)
        val setupDone = AtomicBoolean(false)
        // The stall guard's close (also the in-place fallback's); the caller has already won setupState.claimStall().
        fun closeStalled(sinceOpenMs: Long) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK adaptive_phy: link=call | node=$node | phy=${linkPhy.label} | reason=setup_stall | " +
                    "sinceOpenMs=$sinceOpenMs | guardMs=$stallGuardMs | action=close",
            )
            // Armed before the close so the ACL_DISCONNECTED broadcast cannot be missed (test_setup_retry_acl_wait).
            if (connectConfig.setupRetryAclWait) {
                runCatching { AclDownWatch.forDevice(appContext, peer.device) }.getOrNull()
                    ?.let { stallAclWatch.getAndSet(it)?.release() }
            }
            runCatching { activeConnection.close() }
        }
        if (stallGuardMs > 0L && slowWatch == null) {
            thread(name = "PrivateCallSetupStall", isDaemon = true) {
                val settledInTime = try {
                    settled.await(stallGuardMs, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                val sinceOpenMs = SystemClock.elapsedRealtime() - openedAtMs
                // The CAS decides a handshake that completes at the deadline: only a still-pending attempt is closed.
                if (running.get() && adaptivePhySetupStalled(stallGuardMs, settledInTime, sinceOpenMs) &&
                    setupState.claimStall()
                ) {
                    closeStalled(sinceOpenMs)
                }
            }
        }
        if (slowWatch != null && slowProgress != null) {
            // Replaces the guard thread for this attempt: the same CAS closes it, never before the guard ([SlowTwoMInPlace.Watch]).
            thread(name = "PrivateCallSlow2m", isDaemon = true) {
                watchSlowTwoM(slowWatch, slowProgress, activeConnection, setupState, openedAtMs, node, hopAuthAtMs, setupDone, ::closeStalled)
            }
        }
        // Every other attempt (the 1M retry, a first attempt without the guard, Classic): an answerer that keeps the
        // link open and never completes the hop handshake is closed after CallSetupDeadlines.HOP_AUTH_MS, so the
        // call fails visibly instead of waiting in OUTGOING (a phone in pseudonym mode that refuses our old-style
        // handshake closes the link; a silent one must not hold the caller either).
        if (stallGuardMs <= 0L) {
            thread(name = "PrivateCallHopAuthDeadline", isDaemon = true) {
                val settledInTime = try {
                    settled.await(CallSetupDeadlines.HOP_AUTH_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                if (!settledInTime && running.get() && setupState.claimTimeout()) {
                    Log.i(
                        "BluetoothVideo",
                        (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                            CallSetupDeadlines.logLine(node, SystemClock.elapsedRealtime() - openedAtMs),
                    )
                    runCatching { activeConnection.close() }
                }
            }
        }
        try {
            establish(activeConnection, initiator = true, peer.announcement, plan) {
                val hopAuthMs = SystemClock.elapsedRealtime() - openedAtMs
                hopAuthAtMs.set(hopAuthMs)
                val kept = setupState.authenticated()
                settled.countDown()
                hopAuthenticatedThisLink = kept
                // AP7 F2 (Experimental `test_stall_slow_auth_ms`, 0 = off): a hop authentication that finished late on 2M is
                // the pair's stall signature even though it finished; take the existing 1M retry before any media can start.
                if (kept && stallGuardMs > 0L && slowWatch?.moved != true &&
                    slowAuthStall(DebugTunables.stallSlowAuthMs, hopAuthMs, linkPhy == BlePhyPreference.TWO_M)
                ) {
                    Log.i(
                        "BluetoothVideo",
                        (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                            "adaptive_phy: link=call | node=$node | phy=${linkPhy.label} | reason=slow_auth_2m | " +
                            "hopAuthMs=$hopAuthMs | armMs=${DebugTunables.stallSlowAuthMs} | action=retry_1m",
                    )
                    throw CallSetupStalledException("LE call setup authenticated after ${hopAuthMs} ms on 2M")
                }
                if (kept) armCallerAnswerDeadline(activeConnection, relayed = pendingRelayRoute != null)
                // Measured hop-authentication time after the link opened, every LE call attempt: the evidence
                // for the setup-stall deadline (AdaptivePhyMemory.SETUP_STALL_MS).
                if (transport != BluetoothTransportOption.CLASSIC_RFCOMM) {
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK call_setup: node=$node | phy=${linkPhy.label} | hopAuthMs=$hopAuthMs | " +
                            "guardMs=$stallGuardMs | kept=$kept",
                    )
                }
                if (!kept) throw CallSetupStalledException("LE call setup stalled")
            }
        } catch (error: Exception) {
            if (setupState.stalled && error !is CallSetupStalledException) {
                throw CallSetupStalledException("LE call setup stalled", error)
            }
            if (setupState.timedOut && error !is CallHandshakeTimeoutException) {
                throw CallHandshakeTimeoutException(CallSetupDeadlines.timeoutMessage(), error)
            }
            // The answerer closed the link before the hop handshake finished (not our guard): say why it may have.
            if (setupState.pending && error is IOException &&
                error !is CallSetupStalledException && error !is CallHandshakeTimeoutException
            ) {
                throw CallHandshakeClosedException(CallSetupDeadlines.closedMessage(), error)
            }
            throw error
        } finally {
            setupState.ended()
            settled.countDown()
            setupDone.set(true)
        }
        if (setupState.stalled) throw CallSetupStalledException("LE call setup stalled")
        // Requested vs effective PHY of a link moved in place (test_slow_2m_in_place), explicit for the scorer.
        if (slowWatch?.confirmed == true) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK link_phy requested=2M effective=1M reason=slow_2m_in_place source=adaptive link=call node=$node",
            )
        }
    }

    /**
     * Experimental test_slow_2m_in_place: the watcher of one guarded 2M attempt, every [SlowTwoMInPlace.POLL_MS] until the setup
     * ended or the watch is done. It asks the link for 1M on a stall or crawl, polls the PHY, and closes the link through the
     * stall guard's own CAS ([closeStalled]) only when [SlowTwoMInPlace.Watch] says so (never before the guard).
     */
    private fun watchSlowTwoM(
        watch: SlowTwoMInPlace.Watch,
        progress: SlowTwoMInPlace.Progress,
        activeConnection: CallConnection,
        setupState: CallSetupStallState,
        openedAtMs: Long,
        node: String,
        hopAuthAtMs: AtomicLong,
        setupDone: AtomicBoolean,
        closeStalled: (Long) -> Unit,
    ) {
        val movePhy = activeConnection.movePhy
        try {
            while (running.get() && !watch.done) {
                Thread.sleep(SlowTwoMInPlace.POLL_MS)
                val sinceOpenMs = SystemClock.elapsedRealtime() - openedAtMs
                val observation = SlowTwoMInPlace.Observation(
                    sinceOpenMs = sinceOpenMs,
                    hopAuthAtMs = hopAuthAtMs.get().takeIf { it >= 0L },
                    lastProgressAtMs = progress.sinceOpen(openedAtMs),
                    phy = SlowTwoMInPlace.LinkPhyView.of(activeConnection.phySummary()),
                    finished = setupDone.get() || !running.get(),
                )
                var decision = watch.decide(observation)
                if (decision.action == SlowTwoMInPlace.Action.REQUEST_1M) {
                    val queued = movePhy != null && runCatching { movePhy(BlePhyPreference.ONE_M_ADAPTIVE) }.getOrDefault(false)
                    if (queued) {
                        requestedLinkPhy = BlePhyPreference.ONE_M_ADAPTIVE
                        slowTwoMMovedThisLink = true
                    }
                    Log.i(
                        "BluetoothVideo",
                        SlowTwoMInPlace.line(
                            node, watch.trigger, if (queued) "moved" else "not_queued", sinceOpenMs,
                            "phyBefore=${observation.phy.name.lowercase()} | hopAuthenticated=${observation.hopAuthAtMs != null}",
                        ),
                    )
                    decision = watch.onRequest(queued, observation)
                }
                when (decision.action) {
                    SlowTwoMInPlace.Action.READ_PHY -> runCatching { activeConnection.readPhy?.invoke() }
                    SlowTwoMInPlace.Action.CONFIRMED, SlowTwoMInPlace.Action.NOT_TWO_M, SlowTwoMInPlace.Action.UNRESOLVED -> Log.i(
                        "BluetoothVideo",
                        SlowTwoMInPlace.line(
                            node, watch.trigger, decision.action.name.lowercase(), sinceOpenMs,
                            if (decision.cause.isEmpty()) "" else "cause=${decision.cause}",
                        ),
                    )
                    SlowTwoMInPlace.Action.FALLBACK_CLOSE -> if (running.get() && setupState.claimStall()) {
                        slowTwoMCloseSinceOpenMs = sinceOpenMs
                        Log.i("BluetoothVideo", SlowTwoMInPlace.line(node, watch.trigger, "fallback_close", sinceOpenMs, "cause=${decision.cause}"))
                        closeStalled(sinceOpenMs)
                    }
                    SlowTwoMInPlace.Action.NONE, SlowTwoMInPlace.Action.REQUEST_1M -> Unit
                }
            }
        } catch (_: InterruptedException) {
            // The attempt ended.
        }
    }

    /** Review A6: the current outgoing link finished its hop handshake (reset per attempt). */
    @Volatile private var hopAuthenticatedThisLink = false

    /** Experimental test_slow_2m_in_place: the current outgoing link was asked for 1M in place (reset per attempt). */
    @Volatile private var slowTwoMMovedThisLink = false

    /** Experimental test_slow_2m_in_place: ms after the link opened when its fallback closed it, -1 when it did not. */
    @Volatile private var slowTwoMCloseSinceOpenMs = -1L

    /** Review A3: the call whose answer deadline closed its link, so the failure explains itself. */
    @Volatile private var answerDeadlineCallId: UUID? = null

    /**
     * Review A3: from hop authentication to the callee's RINGING (or Accept) nothing else bounds the caller: the end-to-end
     * handshake read and the wait for RINGING block without a timeout, so a helper or callee that stops answering held the
     * caller in "Calling" until the user gave up. A link still in OUTGOING_INVITE after [CallSetupDeadlines.callerAnswerMs]
     * is closed; the ordinary failure path ends the attempt (or tries the next relayed candidate).
     */
    private fun armCallerAnswerDeadline(activeConnection: CallConnection, relayed: Boolean) {
        if (qrScanPayload != null || !DebugTunables.callerAnswerDeadline) return
        val callId = state?.callId ?: return
        answerDeadlineCallId = null
        val deadlineMs = CallSetupDeadlines.callerAnswerMs(relayed)
        thread(name = "PrivateCallAnswerDeadline", isDaemon = true) {
            try {
                Thread.sleep(deadlineMs)
            } catch (_: InterruptedException) {
                return@thread
            }
            val current = state
            if (running.get() && connection === activeConnection &&
                CallSetupDeadlines.callerAnswerExpired(current?.phase, current?.callId, callId)
            ) {
                answerDeadlineCallId = callId
                Log.i(
                    "BluetoothVideo",
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") + CallSetupDeadlines.callerAnswerLine(relayed, deadlineMs),
                )
                runCatching { activeConnection.close() }
            }
        }
    }

    /**
     * Finds the current advertisement of [peer] before the 1M retry. A peer whose 2M link failed drops it
     * (at once on our LL_TERMINATE, else after its own supervision timeout), restarts its call listener
     * and advertises again under a new random address (ASUS snoop, xpair-asusa15s-adf8236d-l3p-1 0003:
     * re-advertised 1.6 s after its disconnect as 4B:00:00:00:00:02 while the A15s retried the old
     * 4A:00:00:00:00:01 until its 12 s connect deadline). Same node prefix and route cost only; the
     * end-to-end handshake still verifies the identity. Falls back to [peer] when none is seen in time.
     */
    private fun rediscoverPeer(
        peer: BleL2capPeer,
        startedAtMs: Long,
        stalled: Boolean = false,
        /** An on-demand relayed route: scan for the selected helper's own offer and keep [peer]'s route (RelayHopPolicy). */
        helperRoute: RelayRoute? = null,
        /** Experimental test_slow_2m_in_place: its fallback close came this long after the link opened (null: no such close). */
        inPlaceCloseSinceOpenMs: Long? = null,
    ): BleL2capPeer {
        // Experimental test_stall_rediscover_ms (default 8000 = the clamp below): after a setup stall the scan is not
        // bound by the early-loss window, so a callee that re-advertises 8.6-12.6 s after the close is still found.
        val armMs = DebugTunables.stallRediscoverMs
        // test_rediscover_release_window (on by default since 2026-11-04): wait as long as the callee can take to release
        // the link it never heard us close (StallRediscover.releaseWindowMs), so the retry is not sent to the stale address.
        val releaseWindow = DebugTunables.rediscoverReleaseWindow
        val calleeRestartMs = if (transport == BluetoothTransportOption.BLE_GATT) StallRediscover.CALLEE_GATT_RESTART_MS else 0L
        val baseBudgetMs = StallRediscover.budgetMs(
            armMs, stalled, startedAtMs + AdaptivePhyMemory.EARLY_LOSS_MS - SystemClock.elapsedRealtime(), releaseWindow, calleeRestartMs,
        )
        val budgetMs = SlowTwoMInPlace.rediscoverBudgetMs(baseBudgetMs, inPlaceCloseSinceOpenMs, calleeRestartMs)
        if (inPlaceCloseSinceOpenMs != null) {
            Log.i(
                "BluetoothVideo",
                SlowTwoMInPlace.line(
                    offerLogLabel(peer.announcement), null, "rediscover_window", inPlaceCloseSinceOpenMs,
                    "budgetMs=$budgetMs | baseMs=$baseBudgetMs | calleeRestartMs=$calleeRestartMs",
                ),
            )
        }
        if (budgetMs <= 0L || !running.get()) return peer
        val prefix = peer.announcement.nodeIdPrefix
        val routeCost = peer.announcement.routeCost
        val found = ArrayBlockingQueue<BleL2capPeer>(1)
        val discovery = BleL2capDiscovery(
            adapter,
            onPeer = { found.offer(it) },
            onFinished = { },
            onError = { Log.i("BluetoothVideo", "BENCHMARK adaptive_phy: link=call | rediscover=error | message=$it") },
            // The selected version too (RV15): a version 2 advert with the same bytes never replaces a
            // selected version 5 offer, which would swap the opening check for the prefix check.
            acceptAnnouncement = { offer ->
                if (helperRoute != null) {
                    RelayHopPolicy.helperRetryMatches(helperRoute, peer.announcement.version, offer)
                } else {
                    matchesRediscoveredPeer(prefix, routeCost, offer) && offer.version == peer.announcement.version
                }
            },
            scanDurationMs = budgetMs,
            // Rotating IDs: the same 6 bytes match only within the callee's epoch; this continues the
            // user's own call attempt (RV3 allows no connection started by a match alone).
            acceptRotating = peer.announcement.rotating,
        )
        val scanStartedMs = SystemClock.elapsedRealtime()
        return try {
            discovery.start()
            val fresh = found.poll(budgetMs, TimeUnit.MILLISECONDS)
            Log.i(
                "BluetoothVideo",
                "BENCHMARK adaptive_phy: link=call | node=${offerLogLabel(peer.announcement)} | " +
                    (if (helperRoute != null) "hop=helper:${RelayHopPolicy.hex(helperRoute.helperPrefix)} | " else "") +
                    "rediscover=${if (fresh == null) "none" else "found"} | " +
                    "sameAddress=${fresh?.device?.address == peer.device.address} | " +
                    "waitedMs=${SystemClock.elapsedRealtime() - scanStartedMs} | budgetMs=$budgetMs",
            )
            if (stalled || armMs > StallRediscover.DEFAULT_MS || releaseWindow) {
                Log.i(
                    "BluetoothVideo",
                    StallRediscover.line(
                        budgetMs, SystemClock.elapsedRealtime() - scanStartedMs, fresh != null,
                        fresh?.device?.address == peer.device.address, stalled, armMs, releaseWindow,
                    ),
                )
            }
            if (helperRoute != null) RelayHopPolicy.retryPeer(peer, fresh) else fresh ?: peer
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            peer
        } finally {
            discovery.close()
        }
    }

    /**
     * One bounded 1M retry: LE call, first attempt on 2M, within [AdaptivePhyMemory.EARLY_LOSS_MS]. A
     * relayed offer's hop is the relay; the retry is hop-local, so relayed offers retry too.
     */
    private fun adaptivePhySetupRetryAllowed(firstPhy: BlePhyPreference, elapsedMs: Long): Boolean =
        adaptivePhySetupRetryEligible(
            enabled = DebugTunables.adaptivePhy && DebugTunables.adaptivePhyMemory,
            running = running.get() && state?.phase != PrivateCallPhase.ENDED,
            le = transport != BluetoothTransportOption.CLASSIC_RFCOMM,
            direct = true,
            firstOnTwoM = firstPhy == BlePhyPreference.TWO_M,
            elapsedMs = elapsedMs,
        )

    /** Closes a failed outgoing attempt so the retry starts from a clean link. */
    private fun discardFailedOutgoingSetup() {
        linkTransportOverride = null
        phyFirstGate = null
        connection?.close()
        connection = null
        bleControlConnection?.close()
        bleControlConnection = null
        writerThread?.interrupt()
        readerThread?.interrupt()
        secureSession?.close()
        secureSession = null
        wifiHop?.onCallEnded()
        wifiHop = null
        wifiRecordHandler = null
        markerCodec = CallMarkerCodec
        carriers?.close()
        carriers = null
        contentSink = null
        extraCarrierStates.clear()
        contentSession?.close()
        contentSession = null
        authenticatedAtNs = 0L
        activeAtNs = 0L
        callRunning.set(false)
    }

    /**
     * Experimental `test_call_1m_first`: the first remote picture was shown (MainActivity, once per frame, cheap when nothing is
     * pending). The call is running with media on its 1M link; it asks for 2M now when its planned load exceeds 1M or the sender
     * already reported a link limit ([CallPhyUpgradeGate]).
     */
    fun noteRemotePictureShown() {
        phyFirstGate?.onPictureShown()?.let(::requestCallTwoM)
    }

    /** One 2M request on the live, authenticated 1M link (off the caller's thread; the connection's LL gate lambda queues it). */
    private fun requestCallTwoM(trigger: CallUpgradeTrigger) {
        val gate = phyFirstGate ?: return
        val live = connection ?: return
        val request = live.requestPhy ?: return
        val node = secureSession?.peerNodeId?.shortLabel()
        val label = node ?: "unknown"
        // A peer a rotating ID could not be matched to before connecting is known now: remembered bad stays on 1M.
        if (node != null && AdaptivePhyMemory.isBad(transport, node)) {
            gate.suppress()
            Log.i(
                "BluetoothVideo",
                CallOneMFirst.linkPhyLine("1M", "1M", "remembered", label, "trigger=${trigger.label} | at=after_first_picture"),
            )
            return
        }
        val planned = activeSettings.let { CallOneMFirst.plannedKbps(it.bitRate, it.fps).toInt() }
        thread(name = "CallPhyUpgrade", isDaemon = true) {
            val accepted = runCatching { request(BlePhyPreference.TWO_M) }.getOrDefault(false)
            gate.markRequested(SystemClock.elapsedRealtime())
            if (accepted) requestedLinkPhy = BlePhyPreference.TWO_M
            Log.i(
                "BluetoothVideo",
                CallOneMFirst.linkPhyLine(
                    "2M", if (accepted) "pending" else "refused", trigger.label, label,
                    "plannedKbps=$planned | budgetKbps=${CallOneMFirst.ONE_M_GATT_KBPS} | at=after_first_picture",
                ),
            )
        }
    }

    /**
     * Experimental `test_call_1m_first`: the peer went silent within [CallOneMFirst.EARLY_LOSS_AFTER_UPGRADE_MS] of the 2M
     * upgrade. The central's PHY request still reaches the peer (the stalled 2M direction is the peripheral's), so ask for 1M on
     * the same link, and remember the authenticated hop node as bad on 2M (existing [AdaptivePhyMemory]; not a relaying hop).
     */
    private fun fallBackCallToOneM(silentMs: Long) {
        val request = connection?.requestPhy ?: return
        val node = CallOneMFirst.nodeToRemember(secureSession?.peerNodeId?.shortLabel(), hopRelaysSkipsMemory())
        val accepted = runCatching { request(BlePhyPreference.ONE_M_ADAPTIVE) }.getOrDefault(false)
        if (accepted) requestedLinkPhy = BlePhyPreference.ONE_M_ADAPTIVE
        node?.let { AdaptivePhyMemory.recordBad(transport, it, "call_upgrade_early_loss") }
        Log.i(
            "BluetoothVideo",
            CallOneMFirst.linkPhyLine(
                "1M", if (accepted) "pending" else "refused", "call_upgrade_early_loss", node ?: "unknown",
                "silentMs=$silentMs | remembered=${node != null}",
            ),
        )
    }

    /**
     * A 2M LE link to this call's hop peer failed within [AdaptivePhyMemory.EARLY_LOSS_MS] of
     * authentication: remember the peer as bad on 2M so the next link to it starts on 1M.
     */
    private fun noteEarlyLinkLoss(error: Exception) {
        if (error !is IOException || transport == BluetoothTransportOption.CLASSIC_RFCOMM) return
        val hop = secureSession?.peerNodeId?.shortLabel() ?: return
        val phy = runCatching { connection?.phySummary?.invoke() }.getOrNull().orEmpty()
        val wasTwoM = linkWasTwoM(phy, requestedLinkPhy == BlePhyPreference.TWO_M)
        // Measured from authentication to the moment the link stopped making progress, on both roles:
        // a GATT write timeout reports the loss only after its own timeout (the A15s central logged
        // "GATT video write timed out" 23.3 s after authentication while the ASUS saw the same loss at
        // 14.2 s; xpair-asusa15s-351baa8e-fb2257/0003), so the detection time overstates the window.
        val stalledAtNs = callLinkStalledAtNs(lastHeartbeatNs, lastSendProgressNs, System.nanoTime())
        // Window start: media start (ACTIVE) when reached, else authentication. Ringing is human-paced:
        // the A15s caller reached ACTIVE 16.4 s after authentication and lost the link 7 s later.
        val authMs = callEarlyLossWindowStartNs(authenticatedAtNs, activeAtNs) / 1_000_000
        val stalledMs = maxOf(stalledAtNs / 1_000_000, authMs)
        val early = authenticatedAtNs > 0 &&
            AdaptivePhyMemory.isEarlyLoss(authMs, stalledMs, wasTwoM, NodeLinkLoad.sendLinks(transport))
        Log.i(
            "BluetoothVideo",
            "BENCHMARK adaptive_phy: link=call | node=$hop | early_loss=$early | stalledAfterMs=${stalledMs - authMs} | " +
                "detectedAfterMs=${System.nanoTime() / 1_000_000 - authMs} | phy=${phy.ifEmpty { "unknown" }} | " +
                "requested=${requestedLinkPhy.label} | error=${error.message ?: error.javaClass.simpleName}",
        )
        if (!early) return
        if (hopRelaysSkipsMemory()) {
            Log.i("BluetoothVideo", "BENCHMARK adaptive_phy: link=memory | node=$hop | bad2m=not_recorded | reason=early_loss | hop_relays=true")
            return
        }
        Log.i(
            "BluetoothVideo",
            "BENCHMARK adaptive_phy: link=call | node=$hop | from=2M to=1M | reason=early_loss | " +
                "afterMs=${stalledMs - authMs} | error=${error.message ?: error.javaClass.simpleName} | next=1M",
        )
        AdaptivePhyMemory.recordBad(transport, hop, "early_loss")
        offerViaPrefix?.let { AdaptivePhyMemory.recordBadVia(transport, it, "early_loss") }
    }

    /**
     * This call's hop peer relays it ([AdaptivePhyMemory.callHopRelays]), so a loss on this link is not
     * evidence about the pair's 2M ([RelayUpstream]; `test_relay_phy_guard=false` records it again).
     * A hop known to be the call's own peer ([AdaptivePhyMemory.callHopIsCallPeer]) is not a relay of this
     * call even when it signs the call-relay capability as an idle helper.
     */
    private fun hopRelaysSkipsMemory(): Boolean = AdaptivePhyMemory.skipsRelayingHop(
        AdaptivePhyMemory.callHopRelays(
            offerVia = offerViaPrefix != null,
            relayHops = relayHops,
            hopTransportMask = secureSession?.peerCapabilities?.transportMask ?: 0,
            hopIsCallPeer = AdaptivePhyMemory.callHopIsCallPeer(
                enabled = DebugTunables.directCallHopMemory,
                linkPeerIsEndpointPeer = secureSession?.peerNodeId?.let { it == contentSession?.peerNodeId } == true,
                callerDialedDirect = state?.role == PrivateCallRole.CALLER && offerViaPrefix == null &&
                    pendingRelayRoute == null && relayDuty == null && relayHops == 0,
            ),
        ),
        DebugTunables.relayPhyGuard,
    )

    /** True while this endpoint's link is a message exchange instead of a call. */
    val messageSyncActive: Boolean
        get() = messageSync != null

    /** Messaging v2: the running exchange is a chat session (it stays open while a thread is on screen). */
    val messageSyncIsChat: Boolean
        get() = messageSync?.isChat == true

    /**
     * Experimental: exchanges store-and-forward messages with [peer] over a fresh BMH2 link on the
     * call transport, then ends this endpoint (the activity restarts availability). Returns false
     * when the feature is off or a call/exchange is already running.
     */
    @Synchronized
    fun startMessageSync(peer: BleL2capPeer): Boolean {
        val nodeProvider = storeForwardNode ?: return false
        if (!canStartOutgoingPrivateCall(running.get(), state, callRunning.get())) return false
        // Message exchanges are dialed on the endpoint's own transport only (DECISIONS Design D): a row listed only
        // because of the dual hint (a GATT endpoint, a CoC offer) is not an exchange target.
        if (DualCallerPlan.listsHintedOffer(endpointTransport, peer.announcement, DualListeningFeature.enabled)) return false
        if (!callRunning.compareAndSet(false, true)) return false
        linkTransportOverride = null
        dualPlan = emptyList()
        stopAvailability(forActiveConnection = false)
        connectionThread = thread(name = "MessageSyncConnect") {
            try {
                val setupStartNs = System.nanoTime()
                val (activeConnection, link) = dialMessageLink(peer)
                secureSession = link
                // A message exchange needs a stable identity on both ends (cards, mailbox keys, bundle IDs that stay
                // the same for days): with a stranger it would hand over or link exactly what the pseudonym hides.
                // Contacts still exchange (real key on both ends). PROTOCOL "Message exchanges".
                check(!link.ownPseudonymous && !link.peerPseudonymous) {
                    "A message exchange needs a verified contact: a stranger is only a pseudonym"
                }
                checkSelectedPeer(peer.announcement, link, exchangeLinkOpening(link, initiator = true), messageSync = true)
                runMessageSync(
                    nodeProvider(), link, activeConnection, initiator = true, firstRecord = null,
                    setupMs = (System.nanoTime() - setupStartNs) / 1_000_000,
                )
            } catch (error: Exception) {
                val detail = error.message ?: error.javaClass.simpleName
                runCatching { onMessageSyncFailed(detail) }
                finish("Message exchange failed: $detail")
            }
        }
        return true
    }

    /**
     * Opens and authenticates the link of a message-exchange dial with the call's 2M protections ([MessageDialPhy],
     * switch `test_message_adaptive_phy`): a peer remembered bad on 2M starts on 1M, a first 2M link whose hop authentication
     * stalls is closed after [AdaptivePhyMemory.SETUP_STALL_MS], and one bounded 1M retry (to the peer's current
     * advertisement) follows inside [AdaptivePhyMemory.EARLY_LOSS_MS]. Off: one attempt on the endpoint's PHY, as before.
     */
    private fun dialMessageLink(peer: BleL2capPeer): Pair<CallConnection, SecureBluetoothSession> {
        val le = transport != BluetoothTransportOption.CLASSIC_RFCOMM
        val adaptive = le && DebugTunables.messageAdaptivePhy
        val startedAtMs = SystemClock.elapsedRealtime()
        val node = offerLogLabel(peer.announcement)
        val firstPhy = if (adaptive) {
            AdaptivePhyMemory.phyFor(transport, peer.announcement.nodeIdPrefix, phyPreference, "message")
        } else {
            phyPreference
        }
        var currentPeer = peer
        fun retryAllowed(elapsedMs: Long) = adaptive && MessageDialPhy.retryEligible(
            enabled = DebugTunables.adaptivePhy && DebugTunables.adaptivePhyMemory,
            running = running.get(),
            le = le,
            firstPhy = firstPhy,
            elapsedMs = elapsedMs,
        )
        fun openAndAuthenticate(
            phy: BlePhyPreference,
            guardMs: Long,
            deadline: SetupDeadline? = null,
        ): Pair<CallConnection, SecureBluetoothSession> {
            val plan = attemptPlan(peer.announcement)
            return MessageDialPhy.openAndAuthenticate(
                stallGuardMs = guardMs,
                open = { openOutgoing(currentPeer, phy, plan, deadline).also { connection = it } },
                close = { it.close() },
                authenticate = { opened ->
                    opened.link ?: SecureBluetoothHandshake.initiate(
                        BufferedInputStream(opened.input, ioConfig.bufferBytes),
                        BufferedOutputStream(opened.output, ioConfig.bufferBytes),
                        identity,
                        linkCapabilities,
                        bmh3 = plan.initiator(Bmh3Policy.Layer.LINK),
                    )
                },
                running = running::get,
                nowMs = SystemClock::elapsedRealtime,
                onStall = { sinceOpenMs ->
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK adaptive_phy: link=message | node=$node | phy=${phy.label} | reason=setup_stall | " +
                            "sinceOpenMs=$sinceOpenMs | guardMs=$guardMs | action=close",
                    )
                },
            )
        }
        val stallGuardMs = if (DebugTunables.phySetupStallGuard) AdaptivePhyMemory.SETUP_STALL_MS else 0L
        val dialed = MessageDialPhy.dial(
            firstPhy = firstPhy,
            stallGuardMs = stallGuardMs,
            retryAllowed = ::retryAllowed,
            elapsedMs = { SystemClock.elapsedRealtime() - startedAtMs },
            attempt = { phy, guardMs ->
                // Inner GATT/CoC retries may only use the part of the early-loss window that still leaves room for the 1M retry.
                if (phy == firstPhy && retryAllowed(0L)) {
                    openAndAuthenticate(phy, guardMs, SetupDeadline(startedAtMs + AdaptivePhyMemory.EARLY_LOSS_MS))
                } else {
                    openAndAuthenticate(phy, guardMs)
                }
            },
            beforeRetry = { error ->
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK adaptive_phy: link=message | node=$node | from=2M to=1M | reason=setup_retry | attempt=2 | " +
                        "error=${error.message ?: error.javaClass.simpleName}",
                )
                onStatus("Message exchange setup on LE 2M failed; retrying once on LE 1M")
                discardFailedMessageSetup()
                currentPeer = if (adaptivePhyRetryRediscovers(DebugTunables.phySetupStallGuard, peer.announcement.routeCost)) {
                    rediscoverPeer(peer, startedAtMs)
                } else {
                    peer
                }
            },
        )
        if (dialed.retried) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK link_phy requested=2M effective=1M reason=" +
                    "${if (dialed.firstError is CallSetupStalledException) "setup_stall" else "setup_retry"} " +
                    "source=adaptive link=message node=$node",
            )
            // The 1M retry authenticated where 2M stalled: remember this hop peer as bad on 2M. A 2M connect failure
            // (133, discovery, "no channel") is not 2M evidence (review A6); the attempt returns only once authenticated.
            if (TwoMRetryMemory.remembers(firstStalled = dialed.firstError is CallSetupStalledException, firstHopAuthenticated = false)) {
                AdaptivePhyMemory.recordBad(transport, dialed.link.peerNodeId.shortLabel(), "message_setup_retry")
            }
        }
        return dialed.connection to dialed.link
    }

    /** Closes a failed message-dial attempt so the 1M retry starts from a clean link (the exchange keeps the endpoint). */
    private fun discardFailedMessageSetup() {
        connection?.close()
        connection = null
        bleControlConnection?.close()
        bleControlConnection = null
        secureSession?.close()
        secureSession = null
    }

    /**
     * An authenticated 2M exchange this phone dialed that died within [AdaptivePhyMemory.EARLY_LOSS_MS] with no answer at
     * all (no HELLO, no chunk): remember the peer as bad on 2M so the next dial starts on 1M
     * ([MessageDialPhy.earlyLossWithoutAnswer]).
     */
    private fun noteMessageExchangeEarlyLoss(result: StoreForwardSyncResult, activeConnection: CallConnection, sinceAuthMs: Long) {
        if (transport == BluetoothTransportOption.CLASSIC_RFCOMM || !DebugTunables.messageAdaptivePhy) return
        // An exchange cut because this endpoint is closing (the user left the screen) says nothing about the link.
        if (!running.get() || result.detail == "interrupted") return
        if (!DebugTunables.adaptivePhy || !DebugTunables.adaptivePhyMemory) return
        val phy = runCatching { activeConnection.phySummary() }.getOrDefault("")
        val wasTwoM = linkWasTwoM(phy, requestedLinkPhy == BlePhyPreference.TWO_M)
        val early = MessageDialPhy.earlyLossWithoutAnswer(
            outcome = result.outcome,
            chunksSent = result.chunksSent,
            chunksReceived = result.chunksReceived,
            cardReceived = result.cardOffer != null,
            sinceAuthMs = sinceAuthMs,
            wasTwoM = wasTwoM,
            sendLinks = NodeLinkLoad.sendLinks(transport),
        )
        if (!early) return
        val hop = result.peer.shortLabel()
        Log.i(
            "BluetoothVideo",
            "BENCHMARK adaptive_phy: link=message | node=$hop | from=2M to=1M | reason=early_loss | afterMs=$sinceAuthMs | " +
                "outcome=${result.outcome} | phy=${phy.ifEmpty { "unknown" }} | next=1M",
        )
        AdaptivePhyMemory.recordBad(transport, hop, "message_early_loss")
    }

    /** Live media needs the radio: the exchange stops within one chunk and resumes next time. */
    fun suspendMessageSync(): Boolean {
        val session = messageSync ?: return false
        session.requestSuspend()
        return true
    }

    private fun runMessageSync(
        node: StoreForwardNode,
        link: SecureBluetoothSession,
        activeConnection: CallConnection,
        initiator: Boolean,
        firstRecord: SecureRecord?,
        setupMs: Long = -1,
    ) {
        // Review 2026-10-27 (S9): no message exchange with a blocked person, in either direction (the link identity is the
        // real one here: an exchange never runs with a pseudonym).
        if (PersonPolicy.blockedNode(link.peerNodeId)) {
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_blocking: event=exchange_refused | reason=blocked_person")
            error("A message exchange with a blocked person is refused")
        }
        link.reader.acceptStoreForward = true
        val session = StoreForwardLinkSession(
            link,
            node,
            initiator,
            closeLink = { activeConnection.close() },
            log = { Log.i("BluetoothVideo", it) },
            firstRecord = firstRecord,
            transportLabel = transport.shortLabel,
            setupMs = setupMs,
            appState = { MessageAppState.describe(appContext) },
            chatCapable = runCatching { chatCapable(link.peerNodeId, initiator) }.getOrDefault(false),
        )
        messageSync = session
        reopenListenerForCalls()
        onStatus("Exchanging messages with ${link.peerNodeId.shortLabel()} over ${activeConnection.description}")
        var pendingCall: CallConnection? = null
        val exchangeStartedNs = System.nanoTime()
        val result = try {
            session.run()
        } finally {
            synchronized(this) {
                messageSync = null
                pendingCall = pendingCallConnection
                pendingCallConnection = null
            }
        }
        if (initiator) {
            runCatching { noteMessageExchangeEarlyLoss(result, activeConnection, (System.nanoTime() - exchangeStartedNs) / 1_000_000) }
        }
        onMessageSync(result)
        val call = pendingCall
        if (call != null && running.get()) {
            // An incoming call preempted the exchange: release the exchange link and answer the call
            // on this endpoint as an ordinary incoming call.
            runCatching { activeConnection.close() }
            secureSession?.close()
            secureSession = null
            connection = null
            callRunning.set(false)
            onStatus(storeForwardSyncMessage(result))
            acceptIncoming(call)
            return
        }
        call?.close()
        stopAvailability(forActiveConnection = false)
        finish(storeForwardSyncMessage(result))
    }

    /** A callee at critical heat answers without its camera (DECISIONS 2026-11-06); call before [accept]. */
    fun answerWithoutVideo() {
        negotiation?.takeIf { it.role == PrivateCallRole.CALLEE }?.answerWithoutVideo()
    }

    fun accept() {
        synchronized(this) {
            val next = state?.acceptIfRinging() ?: return
            state = next
            notifyPhase(next)
        }
        CallJoinStages.mark(CallJoinStage.ACCEPT, "by=local")
        // R1: the callee's name goes only with Accept (never on Ringing to a stranger).
        queueRevealOnAccept()
        pendingAcceptLabels?.let { controls.offerMust(OutboundControl.Labels(it)) }
        pendingAcceptLabels = null
        // ACCEPT, then CAMERA_OFF for a voice answer (experimental), in one ordered control queue.
        (negotiation?.acceptSignals() ?: listOf(PrivateCallSignal.ACCEPT)).forEach { enqueueSignal(it) }
        // Owner rule (2026-10-27): in a pseudonym-to-pseudonym call the callee's own camera, microphone and stream header
        // wait until the caller's REVEAL verified and passed the person policy; one synchronized decision ([CalleeMediaStart]).
        if (calleeMedia?.onAccepted() != false) {
            startLocalMedia()
        } else {
            Bmh3Android.log("bmh3: event=reveal | role=callee | local_media=held")
        }
        startPresenceSessionAsync()
    }

    /** When the callee's own media may start (null on the calling side): Accept and the caller's reveal decide together. */
    @Volatile private var calleeMedia: CalleeMediaStart? = null

    fun decline(reason: PrivateCallReason = PrivateCallReason.REJECTED) {
        synchronized(this) {
            val current = requireNotNull(state)
            require(current.phase == PrivateCallPhase.INCOMING_RINGING) { "There is no incoming call to decline" }
            historyDeclined = true
            transition(PrivateCallEvent.LOCAL_DECLINE)
            localEndCallId = current.callId
        }
        enqueueSignal(PrivateCallSignal.DECLINE, reason, closeAfter = true)
    }

    fun cancel() {
        // Review A7: the phase is checked and changed under the call lock, so an Accept arriving in between turns the
        // Cancel into an End instead of an invalid transition thrown on the UI thread.
        synchronized(this) {
            val current = state ?: return
            when (privateCallCancelStep(current.phase)) {
                CallCancelStep.IGNORE -> return
                CallCancelStep.HANG_UP -> {
                    hangUp()
                    return
                }
                CallCancelStep.CANCEL -> Unit
            }
            transition(PrivateCallEvent.LOCAL_CANCEL)
            localEndCallId = current.callId
        }
        // Review A5: no writer runs yet (hop handshake, relayed SETUP, end-to-end handshake), so a CANCEL could never
        // be sent: finish now; finish() closes the link, which unblocks the handshake thread.
        if (privateCallCancelFinishesNow(callRunning.get(), writerThread?.isAlive == true)) {
            finish("Call cancelled")
            return
        }
        enqueueSignal(PrivateCallSignal.CANCEL, closeAfter = true)
    }

    fun hangUp() {
        synchronized(this) {
            val current = state ?: return
            if (current.phase != PrivateCallPhase.NEGOTIATING_MEDIA && current.phase != PrivateCallPhase.ACTIVE) {
                cancel()
                return
            }
            transition(PrivateCallEvent.LOCAL_HANG_UP)
            localEndCallId = current.callId
        }
        enqueueSignal(PrivateCallSignal.HANG_UP, PrivateCallReason.NORMAL, closeAfter = true)
    }

    /** Review A1: one decision for a reader/writer whose link failed. */
    private fun onCallLinkError(error: Exception, failMessage: String) {
        val current = state
        when (privateCallLinkErrorAction(callRunning.get(), current?.phase, current?.callId, localEndCallId)) {
            CallLinkErrorAction.FAIL -> {
                noteEarlyLinkLoss(error)
                fail(failMessage)
            }
            CallLinkErrorAction.FINISH_LOCAL_END -> finish(CALL_ENDED_MESSAGE)
            CallLinkErrorAction.NONE -> Unit
        }
    }

    /**
     * A carrier's reader, writer, supervisor or silence rule failed. Returns true when the call continues on another carrier.
     * [id] 0 is the Bluetooth link. Quiet endings that belong to a renewal are decided first (they never end a carrier).
     * The call itself ends only through [CarrierSet.Host.onEndSession] (no carrier left), which calls [onCallLinkError].
     */
    private fun carrierLost(id: Int, gen: Int, error: Exception?, failMessage: String, reason: String, fromReader: Boolean = false): Boolean {
        if (CarrierLossPolicy.action(quietRenewal = id == 0 && renewalEndsOldLinkQuietly(gen), nothingLeft = false) == CarrierLossPolicy.Action.CONTINUE_QUIETLY) return true   // existing rule, unchanged (amendment 20 c)
        val set = carriers
        if (set == null) {
            if (error != null) onCallLinkError(error, failMessage) else fail(failMessage)
            return false
        }
        val kind = set.kindOf(id)                                              // read before the death: it is null afterwards
        lastLossError = error
        lastLossMessage = failMessage
        val nothingLeft = set.carrierDied(id, reason, if (id == 0) gen else -1, fromReader)   // EndSession -> Host.onEndSession -> onCallLinkError; a stale generation does nothing (amendment 6)
        val onOther = !nothingLeft && set.liveCount() > 0                      // the call goes on over another live carrier; false while a held end waits for a renewal (A6's Bluetooth-lost notice reads this) [GAP+]; A6 step 8 anchors on this line: do not reword it [dir A6]
        if (onOther && kind == CarrierKind.BLUETOOTH) WifiCallNotices.afterCarrierLoss(kind, set.snapshot())?.let { onStatus(it) }   // D6 Bluetooth-lost notice, once [API]; decided from the live carriers, not the send carrier, which in fan-out moves later (review A6 P3-1)
        if (!nothingLeft && kind != null) Log.i("BluetoothVideo", "BENCHMARK carrier_lost: carrier=${kind.name} | reason=$reason | live=${set.liveCount()}")   // once per death (amendment 20 f)
        return CarrierLossPolicy.action(quietRenewal = false, nothingLeft = nothingLeft) != CarrierLossPolicy.Action.END_CALL
    }

    @Synchronized
    fun setMuted(value: Boolean) {
        if (muted == value) return
        muted = value
        // Send the partial batch captured before the mute (constrained links batch up to 20 x 20 ms);
        // clearing it cut a median 104-112 ms, at most 361 ms, off the end of each push-to-talk turn
        // on ASUS CoC calls (voice-ptt-51cc8028 receiver traces).
        if (value) flushPendingAudio()
        if (callRunning.get()) {
            enqueueSignal(
                if (value) PrivateCallSignal.MICROPHONE_MUTED
                else PrivateCallSignal.MICROPHONE_UNMUTED,
            )
        }
    }

    fun setCameraEnabled(enabled: Boolean) {
        if (!callRunning.get()) return
        enqueueSignal(if (enabled) PrivateCallSignal.CAMERA_ON else PrivateCallSignal.CAMERA_OFF)
        wifiHop?.onVideo(local = enabled)
    }

    /** DEBUG `wifi_status`: one line with the attempt owner's state (no secret). */
    fun wifiStatus() {
        wifiHop?.logStatus()
    }

    /** The attempt owner's entry for one `BWF1` record: `payload` is the whole body (`Bwf.encode()`), queued on the control queue. */
    fun offerWifiRecord(carrier: Int, payload: ByteArray): Boolean {
        // R-1 where the record is written (review A3 P2-5): only to a peer proven to read BWF1, on a reader opened to it.
        val link = secureSession
        if (!callRunning.get() || link == null || !WifiHopGate.mayWriteBwf1(link, peerSentWifiRecord)) return false
        controls.offerMust(OutboundControl.Wifi(carrier, payload))
        return true
    }

    // ----- Attempt-owner API (A5) and the carrier view the UI/A6 read [API] -----

    /** Adds a Wi-Fi carrier built by the attempt owner. Returns its id (1..62) or -1 when refused; also -1 while [markerCodec] is still the placeholder. Never blocks. */
    fun addWifiCarrier(carrier: Carrier): Int = if (markerCodec === CallMarkerCodec) -1 else carriers?.addCarrier(carrier) ?: -1

    fun wifiCarrierPending(id: Int) {
        carriers?.carrierPending(id)
    }

    fun wifiCarrierUp(id: Int) {
        carriers?.carrierUp(id)
    }

    fun wifiCarrierDied(id: Int, reason: String) {
        carriers?.carrierDied(id, reason)
    }

    /** What this call's Wi-Fi hop knows about the other phone, for Link health's "Wi-Fi not used" line; null without a hop. Any thread. */
    internal fun wifiPeerView(): WifiPeerView? = wifiHop?.peerView()

    /** Set by the user's switch-off only, so a protocol violation that also ends Wi-Fi never shows the "you turned it off" notice. */
    @Volatile private var userSwitchOffAsked = false

    /** THE only public switch-off entry of the call [X4]. A5.8 replaces this body with the agent-first order. */
    fun wifiSwitchOff() {
        userSwitchOffAsked = true
        val hop = wifiHop
        if (hop != null) hop.onSwitchOff() else switchCarriersOff()
    }

    /**
     * D6: the one place the carriers are switched off (directly, or from the hop agent's port on the wifi thread). The
     * notice follows the cutover's own outcome: the call stays on Wi-Fi because the standby is dead or alive but stale
     * (review A6 P2-1).
     */
    private fun switchCarriersOff() {
        val keptOnWifi = carriers?.switchOff() == true
        if (userSwitchOffAsked) WifiCallNotices.afterSwitchOff(keptOnWifi)?.let { onStatus(it) }
    }

    fun wifiQualityFallback() {
        carriers?.qualityFallback()
    }

    /** A Wi-Fi protocol violation found by the attempt owner: an added carrier dies; on carrier 0 Wi-Fi ends, Bluetooth is never closed. */
    fun wifiProtocolViolation(carrierId: Int, reason: String) {
        if (carrierId != 0) {
            carriers?.carrierDied(carrierId, "protocol")
            return
        }
        Log.i("BluetoothVideo", "wifi_violation | carrier=0 | reason=$reason")
        carriers?.switchOff()
    }

    internal fun carrierSnapshot(): CarrierSnapshot = carriers?.snapshot() ?: CarrierSnapshot.EMPTY

    val effectiveCarrier: CarrierKind get() = carriers?.sendKind ?: CarrierKind.BLUETOOTH

    val bluetoothCarrierAlive: Boolean get() = carriers?.snapshot()?.bluetoothAlive ?: true

    internal fun addCarrierListener(listener: CarrierListener) {
        carrierListeners.add(listener)
    }

    internal fun removeCarrierListener(listener: CarrierListener) {
        carrierListeners.remove(listener)
    }

    /** The retained `UW` array itself: copy it (`copyOf()`), never wipe it; null only for a BMH2 call or a failed derivation. [R M-5] */
    val sessionWifiKey: ByteArray? get() = contentSession?.wifiKey

    /** The carrier label the Link health sheet shows (null = Bluetooth). */
    @Volatile var activeCarrierLabel: String? = null
        private set

    private val carrierListeners = CarrierListeners()

    private fun notifyCarrierListeners(event: CarrierEvent, carrierId: Int, kind: CarrierKind?, reason: String) {
        val change = CarrierChange(event, carrierId, kind, reason, carrierSnapshot())
        carrierListeners.notify(change) { name -> Log.w("BluetoothVideo", "carrier listener failed: $name") }
    }

    /**
     * Experimental push-to-talk: holding unmutes and releasing mutes through [setMuted] (the
     * existing mute path; the microphone encoder keeps running). [muted] is the state to apply, or
     * null when the press/release changes nothing. Logged for later measurement.
     */
    fun pushToTalk(pressed: Boolean, muted: Boolean?, source: String) {
        muted?.let(::setMuted)
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK call_ptt: callId=${state?.callId} | action=${if (pressed) "press" else "release"} | " +
                "muted=${this.muted} | changed=${muted != null} | source=$source | atNs=${System.nanoTime()}",
        )
    }

    /** Logs a local video upgrade/downgrade or a layout change of the current call. */
    fun logCallMode(event: String, localVideo: Boolean, remoteVideo: Boolean, layout: CallLayout) {
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK call_mode: callId=${state?.callId} | event=$event | callType=${localCallType.label} | " +
                "localVideo=$localVideo | remoteVideo=$remoteVideo | layout=${layout.name.lowercase()} | " +
                "atNs=${System.nanoTime()}",
        )
    }

    fun setDisplayRotation(degrees: Int) {
        if (!callRunning.get()) return
        controls.offerKeyed("display-rotation", OutboundControl.DisplayRotation(degrees), replace = true)
    }

    @Synchronized
    fun send(flags: Int, presentationTimeUs: Long, bytes: ByteArray) {
        if (!boundedAge) return sendMedia(flags, presentationTimeUs, bytes)
        // Experimental bounded media age: the writer takes under mediaLock, so nothing it writes can
        // interleave with a shed (no partial GOP, no reordering).
        synchronized(mediaLock) {
            try {
                sendMedia(flags, presentationTimeUs, bytes)
            } finally {
                mediaLock.notifyAll()
            }
        }
    }

    private fun sendMedia(flags: Int, presentationTimeUs: Long, bytes: ByteArray) {
        // Nothing of this phone's goes to a person revealed as blocked while the delayed hang-up is pending.
        if (blockedCall.active) return
        if (flags and (StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG) == 0) {
            debugStageTrace?.record("send_enqueue", presentationTimeUs, bytes.size)
        }
        if (!callRunning.get() || state?.phase !in setOf(
                PrivateCallPhase.NEGOTIATING_MEDIA,
                PrivateCallPhase.ACTIVE,
            )
        ) {
            debugTxCounters?.inactiveInputs?.incrementAndGet()
            return
        }
        var packet = OutboundMedia(
            flags, presentationTimeUs, bytes,
            debugQueuedAtNs = if (debugPipelineTiming) System.nanoTime() else 0L,
        )
        if (packet.isAudio && muted && flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
            debugTxCounters?.mutedAudio?.incrementAndGet()
            return
        }
        debugTxCounters?.let { counters ->
            when {
                flags and StreamProtocol.FLAG_CODEC_CONFIG != 0 -> counters.configInputs
                packet.isAudio -> counters.audioInputs
                else -> counters.videoInputs
            }.incrementAndGet()
        }
        sampleOfferedBytes.addAndGet(bytes.size.toLong())
        if (!packet.isAudio && flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) noteVideoFrame(packet)
        if (packet.flags == StreamProtocol.FLAG_AUDIO) {
            val batchSize = privateCallAudioBatchSizeForRoute(
                transport,
                relayHops,
                stackFingerprint,
                peerMaxBitrateKbps,
                legacyLowDelay,
                alwaysPair = audioPairs,
            )
            if (batchSize > 1) {
                pendingAudioPackets += packet
                if (pendingAudioPackets.size < batchSize) return
                packet = takePendingAudioBatch() ?: return
            }
        }
        enqueueMedia(packet)
    }

    /** The waiting audio access units as one record (single, pair or batch), or null if none wait. */
    private fun takePendingAudioBatch(): OutboundMedia? {
        if (pendingAudioPackets.isEmpty()) return null
        val batchReadyNs = if (debugPipelineTiming) System.nanoTime() else 0L
        debugAudioBatchWait?.recordNanos(batchReadyNs - pendingAudioPackets.first().debugQueuedAtNs)
        val combined = combineCallAudio(pendingAudioPackets.map { it.toStreamPacket() })
        pendingAudioPackets.clear()
        return OutboundMedia(combined.flags, combined.presentationTimeUs, combined.payload, batchReadyNs)
    }

    /** Queues the partial audio batch now (mute, push-to-talk release) instead of discarding it. */
    private fun flushPendingAudio() {
        val batch = takePendingAudioBatch() ?: return
        if (!callRunning.get()) return
        if (!boundedAge) return enqueueMedia(batch)
        synchronized(mediaLock) {
            try {
                enqueueMedia(batch)
            } finally {
                mediaLock.notifyAll()
            }
        }
    }

    private fun enqueueMedia(packet: OutboundMedia) {
        if (boundedAge) {
            // Experimental bounded media age owns the whole queue policy (sendBounded); off, the
            // original path below runs unchanged.
            if (!packet.isAudio && packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0 && applyBoundedMediaAge(packet)) return
            droppedMediaPackets += sendBounded(packet)
            return
        }
        if (droppingVideoUntilKeyFrame) {
            when {
                packet.isAudio -> droppedMediaPackets += offerLatestAudio(packet)
                packet.isKeyFrame -> {
                    droppedMediaPackets += dropQueuedVideoPackets()
                    droppedMediaPackets += offerCritical(packet.asRecoveryPoint())
                    droppingVideoUntilKeyFrame = false
                    Log.i(
                        "BluetoothVideo",
                        "Call video recovered at a keyframe after $droppedMediaPackets media drops",
                    )
                }
                else -> {
                    droppedMediaPackets++
                    debugTxCounters?.queueDrops?.incrementAndGet()
                    requestRecoveryKeyFrameIfDue()
                }
            }
            return
        }
        if (thinDiscardable) {
            // Experimental temporal-layer thinning (see TemporalLayerThinning): skip discardable
            // frames while the call link is behind, instead of dropping references and waiting for
            // a keyframe.
            val oldestAgeMs = media.peek()?.let { (System.nanoTime() - it.queuedAtNs) / 1_000_000L } ?: 0L
            if (TemporalLayerThinning.shouldThin(packet.isDiscardable, oldestAgeMs)) {
                thinnedVideoFrames.incrementAndGet()
                return
            }
        }
        if (offerWithinCallBudget(packet)) return
        if (thinDiscardable) {
            // Full queue: discardable frames go first; reference frames survive without a keyframe.
            if (packet.isDiscardable) {
                thinnedVideoFrames.incrementAndGet()
                return
            }
            if (dropQueuedDiscardablePackets() > 0 && offerWithinCallBudget(packet)) return
        }

        val removedVideo = dropQueuedVideoPackets()
        val additionallyDropped = when {
            packet.isAudio -> offerLatestAudio(packet).also { droppingVideoUntilKeyFrame = true }
            packet.isKeyFrame -> offerCritical(packet.asRecoveryPoint()).also {
                droppingVideoUntilKeyFrame = false
            }
            else -> 1.also {
                droppingVideoUntilKeyFrame = true
                debugTxCounters?.queueDrops?.incrementAndGet()
            }
        }
        droppedMediaPackets += removedVideo + additionallyDropped
        if (droppingVideoUntilKeyFrame) requestRecoveryKeyFrameIfDue(force = true)
    }

    @Synchronized
    private fun acceptIncoming(activeConnection: CallConnection) {
        // A closed endpoint never answers (a late listener of an overtaken start, STREAM-INTEROP block 1).
        if (!running.get()) {
            activeConnection.close()
            return
        }
        // Should never happen with the endpoint stopped at stream join; makes a regression one grep away.
        if (CallListenerViewing.viewing()) {
            Log.w("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + CallListenerViewing.logLine(endpointTransport.shortLabel))
        }
        requestedLinkPhy = phyPreference
        offerViaPrefix = null
        // Experimental store-and-forward (N3): a connection arriving during an exchange is treated as
        // a call; the exchange suspends within one chunk and the call is accepted afterwards.
        if (messageSync != null) {
            if (pendingCallConnection != null) {
                activeConnection.close()
                return
            }
            pendingCallConnection = activeConnection
            messageSync?.requestSuspend()
            onStatus("Incoming connection: pausing the message exchange")
            return
        }
        if (state != null || !callRunning.compareAndSet(false, true)) {
            activeConnection.close()
            return
        }
        // Experimental dual listening: a link that arrived on the second transport makes this endpoint run
        // GATT's hello and policies for it, and only while the second listener really existed.
        val arrivedOn = activeConnection.arrivedOn
        if (arrivedOn != null && arrivedOn != endpointTransport &&
            !DualLinkRule.arrivalAllowed(endpointTransport, dualSecondary?.ready == true, arrivedOn)
        ) {
            callRunning.set(false)
            activeConnection.close()
            return
        }
        linkTransportOverride = arrivedOn?.takeIf { it != endpointTransport }
        val dualEndpoint = DualListeningFeature.enabled && (arrivedOn != null || dualSecondary != null)
        if (DualListeningFeature.enabled) {
            DualListeningRuntime.log(DualListeningLog.link(arrivedOn ?: endpointTransport, endpointTransport))
        }
        stopAvailability(forActiveConnection = true)
        if (relayedCallsOn) startPreSetupWatchdog(activeConnection)
        if (CallSetupDeadlines.incomingDeadlineApplies(dualEndpoint, relayedCallsOn, DebugTunables.callHandshakeDeadlineAll)) {
            startIncomingDeadline(activeConnection, dualEndpoint)
        }
        // Design PP package A (both switches default off): the hop-authentication timer starts at the accept and ends at the
        // verified hop plus opening; the existing 20 s deadline above stays as it was.
        incomingPhases?.cancelAll()
        val phases = IncomingPhaseTimers(
            incomingPhaseGeneration.incrementAndGet(), DebugTunables.callPreauthDeadlineShort, DebugTunables.callPreInviteDeadline,
        )
        incomingPhases = phases
        phases.accepted(transport, activeConnection.carrierKind)?.let {
            startIncomingPhaseTimer(phases, phases.hop, activeConnection, transport)
        }
        logIncomingMilestone(phases, IncomingMilestone.TRANSPORT_ACCEPT)
        CallJoinStages.begin("callee")
        connectionThread = thread(name = "PrivateCallIncomingHandshake") {
            try {
                establish(activeConnection, initiator = false, selected = null)
            } catch (error: Exception) {
                // One line on the answering phone for every refused or failed incoming handshake (a refused old-style
                // handshake of a phone in pseudonym mode included), with the fixed reason vocabulary and no identifiers.
                Log.i(
                    "BluetoothVideo",
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") + CallSetupDeadlines.incomingFailureLine(error),
                )
                fail("Incoming call failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun establish(
        activeConnection: CallConnection,
        initiator: Boolean,
        selected: DiscoveryAnnouncement?,
        /** The outgoing attempt's one BMH3 plan (null on the answering side). */
        plan: Bmh3AttemptPlan? = null,
        onHopAuthenticated: () -> Unit = {},
    ) {
        // Review A2: an outgoing link that completes after close() or a Cancel (or between a failed attempt and its
        // retry) must not revive the ended call.
        if (initiator && !privateCallOutgoingEstablishAllowed(running.get(), state?.phase)) {
            activeConnection.close()
            throw IOException("Call attempt ended before its link was established")
        }
        if (!callRunning.get() && !callRunning.compareAndSet(false, true)) {
            activeConnection.close()
            return
        }
        connection = activeConnection
        // Design PP package A: this accepted connection's phases (the answering side only; never an outgoing attempt).
        val phases = if (initiator) null else incomingPhases
        CallJoinStages.mark(CallJoinStage.LINK_UP, "link=${activeConnection.description}")
        presenceSession = null
        awaitedOpening = null
        openingToSend = null
        discoveryNegotiated = false
        // Rotating IDs: the IDs a callee may open are pinned when the connection is accepted, not when a
        // record is sent (a relay chain can deliver the caller handshake much later; PROTOCOL Phase 1b).
        val pinsAtAccept = if (rotatingIds && !initiator) {
            // While a Visible-by-name window is open the opening holds the current role 0 and role 3 entries (at most two).
            WindowOpening.entries(
                RotatingDiscovery.pinned(RotatingDiscoveryIds.ROLE_CALL_OFFER),
                RotatingDiscovery.pinned(RotatingDiscoveryIds.ROLE_WINDOW_OFFER),
                windowOpen = NameWindowRuntime.active(),
            )
        } else {
            emptyList()
        }
        // Experimental hidden identities (BMH3): null (switch off, or a version 2 peer with no hint and no pin) is
        // BMH2 exactly as before. The end-to-end handshake below makes the same choice.
        // One plan for the caller's link and end-to-end handshakes of this attempt (review S1): one choice and one
        // throwaway, so a callee sees one stranger, also behind a Classic connector that ran the link handshake itself.
        val attemptPlan = if (initiator) plan ?: attemptPlan(selected) else null
        val bmh3Initiator = attemptPlan?.initiator(Bmh3Policy.Layer.LINK)
        // BVMID2: one gate per incoming connection, shared by its link and end-to-end handshakes (single reservation).
        qrGate = if (!initiator && QrMeet.display.hasCode()) QrMeet.display.newGate() else null
        val bmh3Responder = if (initiator) {
            null
        } else {
            Bmh3Wiring.responder(
                pinsAtAccept, identity.nodeId.toByteArray(), rotatingIds,
                qrDisplay = qrGate?.let { QrMeet.display }, qrGate = qrGate,
                relaySeek = relayHelper?.takeIf { RelaySeekFeature.active() }?.let { host -> { host.seekFeatures() } },
            )
        }
        callerNames.reset()
        blockedCall.reset()
        calleeNames.reset()
        awaitedReveal = null
        revealEntries = emptyList()
        endpointSigner = identity
        val link = activeConnection.link ?: run {
            val input = BufferedInputStream(activeConnection.input, ioConfig.bufferBytes)
            val output = BufferedOutputStream(activeConnection.output, ioConfig.bufferBytes)
            if (initiator) {
                SecureBluetoothHandshake.initiate(input, output, identity, linkCapabilities, bmh3 = bmh3Initiator)
            } else {
                SecureBluetoothHandshake.respond(
                    input, output, identity, linkCapabilities, bmh3 = bmh3Responder,
                    // Only a BMH2 first frame reaches here: a legacy hop keeps the conservative bound (Design PP package A).
                    beforeHello = { peer -> phases?.legacyFirstFrame(); strangerTarpit(peer) },
                )
            }
        }
        activeConnection.onAuthenticated()   // [AM 12] F-3: the hop handshake on this connection succeeded
        // Review 2026-10-27 (finding 7): while the phone is call-ready only because the Meet screen is open, it accepts
        // nothing but the code, whatever handshake the peer spoke (the BMH3 responder refused other connections, but a
        // BMH2 hello, accepted when pseudonyms are off, went on as an ordinary call).
        if (!initiator && MeetScreenGate.refuses(QrMeet.display.screenOnlyReachable, link.qrTagMatched)) {
            Bmh3Android.log("bmh3: event=qr_screen_only_refused")
            error("Only the verification code is accepted while the Meet screen is open")
        }
        require(link.peerCapabilities.transportMask and transport.capabilityMask != 0) {
            "Peer did not authenticate ${transport.shortLabel} support"
        }
        // Experimental dual listening: a link on the second transport must be one the selected offer allowed.
        if (initiator && selected != null && DualListeningFeature.enabled &&
            (linkTransportOverride != null || DualListeningWire.hasHint(selected))
        ) {
            require(DualLinkRule.verifyCaller(selected, transport, link.peerCapabilities.transportMask) == DualLinkRule.Result.PASS) {
                "Peer did not offer or authenticate ${transport.shortLabel} support"
            }
        }
        val opening = exchangeLinkOpening(link, initiator, pinsAtAccept)
        if (phases != null) {
            // Hop milestone (verified hop plus the required opening): the hop timer ends here, never at `contentSession`.
            if (!phases.hopVerified()) throw IOException("The hop deadline passed before the link was verified")
            logIncomingMilestone(phases, IncomingMilestone.HOP_VERIFIED)
        }
        // A pseudonymous callee cannot open the selected ID yet; its reveal binds it later (awaitedReveal).
        if (!link.peerPseudonymous) selected?.let { checkSelectedPeer(it, link, opening) }
        onHopAuthenticated()
        // test_coc_phy_after_connect (default off): the CoC central requests the PHY only now.
        if (initiator) bleControlConnection?.requestPhyAfterConnect("call")
        if (!initiator) learnClassicPeerAddress(activeConnection.socket, link.peerNodeId, "incoming", link.peerPseudonymous)
        // Experimental relayed calls: the caller of a relayed route asks the first helper to extend it
        // (SETUP/READY) before the end-to-end handshake runs through the chain.
        // Experimental test_relayed_budget_from_hop_auth (review A13): the relayed SETUP budget starts now, not at the dial,
        // so a slow first hop (scan, 1M retry) does not eat the helpers' share of the 20 s.
        if (initiator && pendingRelayRoute != null && connectConfig.relayedBudgetFromHopAuth) relaySetupStartedNs = System.nanoTime()
        if (initiator) pendingRelayRoute?.let { route -> runRelaySetup(link, activeConnection, route, opening) }
        // Experimental store-and-forward / relayed calls (only when enabled): an incoming link whose first
        // record is a BSF1 message is a message exchange, and a BRS1 SETUP is a relay request, not a call.
        // Any other first record continues into the unchanged call handshake, and types 11 and 12 are
        // rejected again from then on.
        val storeForwardDispatch = storeForwardNode?.takeIf { !initiator }
        val firstRecord = if (storeForwardDispatch != null || (relayedCallsOn && !initiator)) {
            val record = readFirstRecordForDispatch(
                link,
                acceptStoreForward = storeForwardDispatch != null && linkTransportOverride == null,
                acceptRelaySetup = relayedCallsOn,
            )
            preSetupWatchdog?.interrupt()
            preSetupWatchdog = null
            // Review A3: with relayed calls on, the pre-setup watchdog ends at the first record; an ordinary call whose
            // end-to-end handshake then stops is bounded by the incoming deadline again (relay duties keep their own).
            if (record.type != SecureRecordType.STORE_FORWARD && record.type != SecureRecordType.RELAY_SETUP &&
                CallSetupDeadlines.incomingDeadlineAfterDispatch(relayedCallsOn, DebugTunables.callHandshakeDeadlineAll)
            ) {
                startIncomingDeadline(activeConnection, dualEndpoint = activeConnection.arrivedOn != null)
            }
            // A stranger is only a pseudonym here: no card and no offers (PROTOCOL "Message exchanges"). A relay request may
            // come from a throwaway when pseudonymous relay edges are on and this phone helps (Amendment CP).
            if (RelayIdentityPolicy.refusesFirstRecord(
                    record.type, link.ownPseudonymous, link.peerPseudonymous, link.qrTagMatched,
                    pseudonymousEdges = DebugTunables.relayedPseudonymousEdges, helperPresent = relayHelper != null,
                )
            ) {
                error("A message exchange or relay request needs a verified contact")
            }
            if (record.type == SecureRecordType.STORE_FORWARD && storeForwardDispatch != null) {
                // A message exchange is a user-visible action, not a probe (RV8 tarpit).
                if (rotatingIds) runCatching { RotatingDiscovery.tarpit.forgive(RotatingDiscovery.currentEpoch()) }
                secureSession = link
                runMessageSync(storeForwardDispatch(), link, activeConnection, initiator = false, firstRecord = record)
                return
            }
            if (record.type == SecureRecordType.RELAY_SETUP) {
                // Security review 2026-10-27 (item 14): this phone relays nothing for a blocked person (a real-key parent).
                if (!link.peerPseudonymous && PersonPolicy.blockedNode(link.peerNodeId)) {
                    Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_blocking: event=relay_refused | reason=blocked_person")
                    error("A relay request from a blocked person is refused")
                }
                secureSession = link
                serveRelaySetup(link, activeConnection, record)
                return
            }
            record
        } else {
            null
        }
        // Design OR ("Who carries the risk"): an end-to-end handshake carried by a relay (the link peer signed the call-relay
        // mark) is closed unanswered while this phone's current relay consent is off, so no identity leaves it for a
        // replayed consenting advert. The decline before ringing stays as the second check.
        if (!initiator && RelaySeekFeature.active() && firstRecord?.type == SecureRecordType.END_TO_END_HANDSHAKE &&
            link.peerCapabilities.transportMask and PrivateCallRelayChain.CALL_RELAY_CAPABILITY != 0 &&
            relayHelper?.consentOn() != true
        ) {
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_call: consent_refused_link")
            error("A relayed call needs this phone's consent")
        }
        if (endpointDecodeMask != capabilities.decodeCodecMask) {
            Log.i(
                "BluetoothVideo",
                "Private-call compatibility: end-to-end hello prefers VP9 and also offers this phone's other " +
                    "decoders (mask ${capabilities.decodeCodecMask} -> $endpointDecodeMask)",
            )
        }
        advertisedDecodeMask = endpointDecodeMask
        val endpointCapabilities = CallRenewalWire.advertise(
            CallEndpointOpening.advertise(
                PeerLabelFeature.advertise(
                    CallVideoFragments.advertise(
                        capabilities.copy(decodeCodecMask = endpointDecodeMask),
                        videoFragmentsOn,
                    ),
                    labelsOn,
                ),
                rotatingIds,
            ),
            renewalOn,
        )
        val endpointBmh3 = attemptPlan?.takeIf { it.bmh3 }?.initiator(Bmh3Policy.Layer.ENDPOINT)
        val endpoint = if (initiator) {
            EndToEndVideoHandshake.initiate(
                link,
                identity,
                endpointCapabilities,
                // The same choice as the link's (a relay is assumed to run this build). The callee is bound to the
                // selected advert before this phone reveals its end-to-end identity.
                endpointBmh3,
            )
        } else {
            EndToEndVideoHandshake.respond(link, identity, endpointCapabilities, firstRecord, bmh3Responder)
        }
        // BVMID2: the connection matched the displayed code. It exists only to verify: scope it, commit the reservation
        // (the completed end-to-end handshake is the verification event), and end it after a short grace.
        // Review 2026-10-19 (P1): a code matched at one layer only is not a verification session and not a call either.
        if (!initiator && !QrLayerBinding.consistent(link.qrTagMatched, endpoint.qrTagMatched)) {
            Bmh3Android.log("bmh3: event=qr_session | role=displayer | violation=layers")
            error("Verification session scope")
        }
        if (!initiator && endpoint.qrTagMatched) beginQrVerifySession(endpoint)
        if (phases != null) {
            phases.endpointAuthenticated()
            logIncomingMilestone(phases, IncomingMilestone.ENDPOINT_AUTHENTICATED)
        }
        // DEBUG only: the throwaway this callee presented to its link peer, for the benchmark runner's relayed-call binding.
        if (BuildConfig.DEBUG && !initiator) PseudonymOwnLog.line(link)?.let { Log.i("BluetoothVideo", it) }
        // Rotating IDs, Phase 1b: when both endpoint hellos carry bit 3 the callee's first end-to-end record,
        // sent right before its first answer to the Invite, is a BDX1 OPENING (its pinned role-0 entries). A
        // caller that selected a version 5 offer verifies it before anything else from the callee (RD6).
        discoveryNegotiated = CallEndpointOpening.negotiated(rotatingIds, endpoint.peerCapabilities)
        // BMH3 pseudonyms: the key this phone signed the labels with, and (callee) the entries it will open only at Accept.
        val ownPseudonym = endpoint.reveal?.ownPseudonymous == true
        if (ownPseudonym) {
            endpointSigner = endpointBmh3?.strangerIdentity ?: bmh3Responder?.strangerIdentity ?: identity
            Bmh3Android.log(
                "bmh3: event=peer | layer=endpoint | pseudonymous=${endpoint.peerIsPseudonym}" +
                    (debugPeer4(endpoint)?.let { " | peer4=$it" } ?: ""),
            )
        }
        if (discoveryNegotiated && !initiator) {
            if (ownPseudonym) revealEntries = pinsAtAccept else openingToSend = pinsAtAccept
        }
        if (discoveryNegotiated) {
            // The presence rules exist from the handshake on, so an OFFER that arrives before this side's own
            // start (the peer answered first) is recorded, not dropped; nothing is sent before the call is answered.
            // A throwaway peer is judged at its reveal (newPresenceSession again there), not as a stranger now.
            presenceSession = if (qrScope != null || (initiator && qrScanPayload != null)) {
                // Design QL: in a verification session eligibility is decided when this side starts (scanner: after the
                // user's yes; displayer: when the scanner's OFFER arrives) and re-checked when each record is sent.
                PresenceSession(localEligible = true, verification = true)
            } else {
                newPresenceSession(endpoint.peerNodeId, endpoint.peerFingerprint, "handshake", log = !endpoint.peerIsPseudonym)
            }
        }
        selected?.let { announcement ->
            if (endpoint.peerIsPseudonym) {
                // The callee answered with a throwaway (version 5 or version 2 offer alike): the binding to the selected
                // offer arrives inside its reveal, after Accept. A direct call must still reach the same throwaway at
                // both layers (one stranger, no relay in between).
                require(discoveryNegotiated) { "Call peer identity changed between discovery and connection" }
                require(announcement.routeCost > 0 || endpoint.peerNodeId == link.peerNodeId) {
                    "Call peer identity changed between discovery and connection"
                }
                awaitedReveal = AwaitedReveal(announcement)
            } else if (announcement.rotating) {
                // RV15: the check follows the selected version 5 offer. Direct calls also keep the link
                // opening (checkSelectedPeer); a relayed offer is verified end to end, by the OPENING.
                require(discoveryNegotiated) { "Call peer identity changed between discovery and connection" }
                awaitedOpening = AwaitedOpening(announcement, endpoint.peerNodeId, link.peerNodeId)
            } else {
                require(
                    endpoint.peerNodeId.toByteArray()
                        .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
                        .contentEquals(announcement.nodeIdPrefix),
                ) { "Call peer identity changed between discovery and connection" }
                // SEC-B3: a direct call (cost 0) must reach the callee itself, not a link-layer relay advertising its prefix.
                DirectCallPeerBinding.requireBound(
                    announcement, link.peerNodeId, endpoint.peerNodeId, "Call peer identity changed between discovery and connection",
                )
            }
        }
        // Experimental relayed calls: the route proved itself only when the target's identity checked out; a reveal or an
        // end-to-end opening still awaited means not yet (review CP-1 item 9).
        if (!DebugTunables.relayedPseudonymousEdges || (awaitedReveal == null && awaitedOpening == null)) markRelayRouteVerified()
        peerMaxBitrateKbps = endpoint.peerCapabilities.maxBitrateKbps
        peerAcceptsVideoFragments = CallVideoFragments.negotiated(videoFragmentsOn, endpoint.peerCapabilities, relayHops = 0)
        // Type 9 parses only on this endpoint's opener and only after it advertised fragments.
        endpoint.opener.acceptVideoFragments = videoFragmentsOn
        // Type 10 likewise, only after this endpoint advertised labels (hello bit 5).
        endpoint.opener.acceptLabels = labelsOn
        // Type 11 (BDX1) likewise, only after both endpoint hellos carried bit 3.
        endpoint.opener.acceptDiscovery = discoveryNegotiated
        // Experimental in-call link renewal: type 12 parses only after this endpoint signed end-to-end hello bit 4.
        renewalNegotiated = CallRenewalWire.negotiated(renewalOn, endpoint.peerCapabilities)
        // Review item 9: type 12 parses only when both hellos signed bit 4 (a peer that did not sign it never sends it).
        endpoint.opener.acceptRenewal = renewalNegotiated
        renewalRole = if (initiator) 0 else 1
        renewalOfferer = RenewalOfferer(SystemClock::elapsedRealtime)
        renewalArmed = RenewalArmed(SystemClock::elapsedRealtime)
        renewalAttempts = RenewalAttempts()
        renewalSoftSinceMs = null
        renewalLinkDead = false
        renewalServers = RenewalServers()
        renewalSilenceFromNs = 0L
        lastCameraSignal = null
        lastLabelsPayload = null
        debugTxStallLogged = false
        if (renewalOn) CallRenewalAndroid.log(if (initiator) "caller" else "callee", "negotiated", "negotiated=$renewalNegotiated")
        peerAcceptsLabels = PeerLabelFeature.negotiated(labelsOn, endpoint.peerCapabilities)
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK call_head_of_line: ${headOfLine.label(peerAcceptsVideoFragments)}")
        if (videoFragmentsOn) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK call_video_fragments: negotiated=$peerAcceptsVideoFragments | " +
                    "fragmentBytes=${CallVideoFragments.FRAGMENT_BYTES} | " +
                    "minPacketBytes=${CallVideoFragments.MIN_FRAGMENTED_PACKET_BYTES}",
            )
            Log.i(
                "BluetoothVideo",
                "BENCHMARK call_fragment_airtime config: mode=${if (fragmentAirtimeMs > 0) "airtime" else "fixed"} | " +
                    "negotiated=$peerAcceptsVideoFragments | targetMs=$fragmentAirtimeMs | " +
                    "fixedChunkBytes=${CallVideoFragments.FRAGMENT_BYTES} | minChunkBytes=${CallFragmentAirtime.MIN_CHUNK_BYTES} | " +
                    "maxChunkBytes=${CallFragmentAirtime.MAX_CHUNK_BYTES} | " +
                    "perFragmentOverheadBytes=${CallFragmentAirtime.PER_FRAGMENT_OVERHEAD_BYTES} | " +
                    "maxOverheadPct=${CallFragmentAirtime.MAX_OVERHEAD_PERCENT} | " +
                    "minChunkOverheadPct=${"%.1f".format(java.util.Locale.ROOT, CallFragmentAirtime.overheadPercent(CallFragmentAirtime.MIN_CHUNK_BYTES))} | " +
                    "fixedChunkOverheadPct=${"%.1f".format(java.util.Locale.ROOT, CallFragmentAirtime.overheadPercent(CallVideoFragments.FRAGMENT_BYTES))}",
            )
        }
        activePlaybackDelayProfile = currentCallPlaybackProfile()
        // One choice operation for this direction (Design HC F12); both masters off: privateCallCodecForPeer as before.
        val codecPower = codecPowerAtStart ?: CodecPowerAware.read(appContext).also { codecPowerAtStart = it }
        // AP5 test_sw_shortfall_avc: read once, when this call first chooses its codec (empty while the switch is off).
        val slowSoftware = slowSoftwareAtStart
            ?: SoftwareShortfallStore.slowEncoders(appContext, activeSettings).also { slowSoftwareAtStart = it }
        val codecSwitches = VideoCodecSwitches.current(power = codecPower, slowSoftware = slowSoftware)
        val codecChoice = VideoCodecChoice.forCall(
            requested = codec,
            userChoice = AutomaticEncoderChoice.userChoice,
            available = availableCodecs,
            peerDecodeMask = endpoint.peerCapabilities.decodeCodecMask,
            switches = codecSwitches,
            familyVp9 = DeviceFamilyPolicy.resolve(stackFingerprint).callVp9Compatibility(transport),
            session = CodecSession.of(activeSettings),
            excluded = VideoCodecChoice::policyExcluded,
            staticIneligibility = VideoCodec.hcStaticIneligibility,
        )
        activeCodec = requireNotNull(codecChoice.sent) { "Peer cannot decode any locally available call codec" }
        if (activeCodec != codec) {
            Log.i(
                "BluetoothVideo",
                "Private-call negotiation selected ${activeCodec.displayName} for the peer decoder",
            )
        }
        if (BuildConfig.DEBUG) {
            Log.i(
                "BluetoothVideo",
                VideoCodecChoice.logLine("call", codecChoice, codecSwitches, endpoint.peerCapabilities.decodeCodecMask),
            )
        }
        activeSettings = privateCallVideoSettingsForPeer(
            activeSettings,
            transport,
            peerMaxBitrateKbps,
        )
        secureSession = link
        contentSession = endpoint
        val set = CarrierSet(
            cutover = CarrierCutover(),
            markers = markerForwarder,          // forwards to the current markerCodec; the placeholder until A5 installs its codec [X3]
            host = CarrierHost(endpoint, initiator),
            threadPrefix = "PrivateCall",
            hopLabel = "call",
            queueCapacityRecords = { maxOf(64, activeSettings.bitRate / 8 * 2 / 1200) },
            flushIntervalNs = { kind -> privateCallMediaFlushIntervalMs(transport, stackFingerprint, peerMaxBitrateKbps, kind) * 1_000_000L },
        )
        peerSentWifiRecord = false
        carriers = set
        set.attachPrimary(
            SessionCarrier(
                activeConnection.carrierKind,
                activeConnection.description,
                link,
                CarrierLivenessPolicy.of(activeConnection.carrierKind),
                closesLink = false,
            ) { },
            linkGeneration.get(),
        )
        contentSink = set.sink
        // This phone reads type-16 records only where A2's handshake rule allows it.
        link.reader.acceptWifi = link.wifiReadOptIn
        calleeMedia = if (initiator) {
            null
        } else {
            CalleeMediaStart(revealRequired = endpoint.reveal?.let { it.ownPseudonymous && it.peerPseudonymous } == true)
        }
        authenticatedAtNs = System.nanoTime()
        lastSendProgressNs = authenticatedAtNs
        activeAtNs = 0L
        AdaptivePhyMemory.bind(activeConnection.address, transport, link.peerNodeId.shortLabel(), hopRelays = offerViaPrefix != null)
        lastHeartbeatNs = System.nanoTime()
        receivedDelivery = LinkDeliveryCounter()
        peerDelivery = PeerDeliveryEstimate()
        deliveryReports = CallDeliveryReports(DebugTunables.deliveryReports)
        fragmentRates = CallFragmentRateEstimator()
        fragmentStats = CallFragmentStats()
        fragmentProbeKbps = if (fragmentAirtimeMs > 0) runCatching { LinkCapacity.measuredLinkKbps(transport) }.getOrNull() else null
        firstLinkGeneration = linkGeneration.get()
        wifiHop = createWifiHop(link, endpoint, initiator)
        startWriter(link, endpoint)
        if (phases != null) {
            // Pre-INVITE timer (switch default off): armed only for an ordinary incoming call, just before the reader that
            // consumes the INVITE starts; a verification-only (BVMID2) session never gets one.
            val session = if (qrScope != null || endpoint.qrTagMatched) IncomingSessionKind.VERIFICATION_ONLY else IncomingSessionKind.CALL
            val viaRelay = endpoint.peerNodeId != link.peerNodeId
            phases.readerStarting(session, transport, activeConnection.carrierKind, viaRelay)?.let {
                startIncomingPhaseTimer(phases, phases.preInvite, activeConnection, transport)
            }
            logIncomingMilestone(phases, IncomingMilestone.READER_STARTED)
        }
        startReader(link, endpoint, initiator)
        if (renewalNegotiated) startRenewalSupervisor(endpoint)
        val scan = qrScanPayload
        if (initiator && scan != null) {
            // BVMID2 scanner: the answerer proved it holds the code's key at both layers. No Invite, no labels, no call:
            // hand the screen the authenticated peer (it asks "Is this ...'s screen you scanned?"). The session is scoped
            // like the displayer's. Design QL: the presence exchange starts only after the user's yes ([qrScanConfirmed]);
            // until then a displayer's OFFER is only recorded. Eligibility is re-checked when the OFFER is sent.
            val scope = QrSessionScope(SystemClock.elapsedRealtime())
            qrScope = scope
            qrClosing.set(false)
            qrScanner = true
            qrLeaseStarted = false
            endedQuietly = true
            // The displayer sends no OPENING in a verification session (no Invite), so its first record may be its OFFER
            // (Design QL). The RV15 opening wait does not apply: the code's fingerprint check (QrScanPolicy.checkResponder),
            // the tag bound to the code's RDI and the direct route already prove who answered, more strongly.
            awaitedOpening = null
            Bmh3Android.log("bmh3: event=qr_session | role=scanner | negotiated=$discoveryNegotiated")
            armQrSessionLimit(scope)
            armQrConfirmWait(scope)
            onQrScanReached?.invoke(endpoint.peerFingerprint, endpoint.peerNodeId)
            return
        }
        qrScope?.let { scope -> if (!initiator) armQrDisplayer(scope, endpoint, link.peerNodeId) }
        if (initiator) {
            // Experimental peer labels: the caller's name, bound to this call ID, queued
            // before Invite only when the callee's endpoint hello has bit 5 (design 3.4).
            // A relayed version 5 callee is proven only by its OPENING, which it sends after the Invite: the
            // name then follows it (the callee takes labels before or after the Invite alike).
            // Naming v2 (Design G, G1): one decision, [CallLabelPolicy.callerPlan], with the call site's inputs
            // ([CallerNameRelease.plan]). Design decision 2026-11-06: a stranger (a throwaway callee, with or without a
            // selected offer) gets the name only after its verified, unblocked REVEAL; a relayed real-key callee after its
            // OPENING; a real-key callee now unless it is blocked; "Only my contacts" only verified contacts. The
            // Experimental `names_before_answer` arm alone sends a stranger's name now. No call subject is sent.
            val plan = CallerNameRelease.plan(
                labelsNegotiated = peerAcceptsLabels,
                hasName = PeerLabelFeature.localName != null,
                audience = PeerLabelFeature.audience,
                nameBeforeAnswer = PeerLabelFeature.nameBeforeAnswer,
                awaitedReveal = awaitedReveal != null,
                peerIsPseudonym = endpoint.peerIsPseudonym,
                relayedAwaitsOpening = awaitedOpening?.selected?.routeCost?.let { it > 0 } == true,
                calleeIsVerifiedContact = peerIsVerifiedContact(endpoint),
            )
            logLabelDecision("caller", plan.name)
            callerNames.start(plan, realPeer = endpoint.takeUnless { it.peerIsPseudonym }?.peerFingerprint)
            // AB3 (Windows WP6N real-key screen; before, only the name was withheld): a callee whose handshake showed the
            // real key of a person this phone blocked gets nothing more (no name, camera, microphone or presence) and its
            // media is dropped. The INVITE still goes, so its phone rings as for any call and then sees the ordinary
            // cancel (or the ordinary hang-up if it answered) after the human-like delay. Fails open (PersonPolicy).
            val realKeyBlocked = CallBlockingPolicy.endsRealKeyCall(
                caller = true,
                peerPseudonymous = endpoint.peerIsPseudonym,
                blocked = { PersonPolicy.blocked(endpoint.peerFingerprint) },
            )
            if (realKeyBlocked) {
                callerNames.onPeerBlocked()
                endBlockedAfterReveal(Bmh3Reveal.ROLE_CALLER, after = "handshake")
            }
            // INVITE, then CAMERA_OFF for a voice call (experimental), in one ordered control queue.
            (negotiation?.inviteSignals() ?: listOf(PrivateCallSignal.INVITE)).forEach { enqueueSignal(it) }
        }
        CallJoinStages.mark(CallJoinStage.AUTHENTICATED)
        // AB3: a blocked person's identity is not shown (the call ends shortly with the existing blocked message).
        if (!blockedCall.active) onStatus(
            "Encrypted private-call link to " +
                (if (endpoint.peerIsPseudonym) "an unverified stranger" else endpoint.peerNodeId.shortLabel()) +
                " over ${activeConnection.description}",
        )
    }

    /** DEBUG only: the first four bytes of the node ID the peer presented, to compare pseudonyms across epochs. */
    private fun debugPeer4(endpoint: EndToEndVideoSession): String? =
        if (BuildConfig.DEBUG) endpoint.peerNodeId.toString().take(8) else null

    /** The peer's real key (not a throwaway) equals a verified contact's right now. False for any pseudonym. */
    private fun peerIsVerifiedContact(endpoint: EndToEndVideoSession): Boolean =
        runCatching {
            // After a reveal the real identity (the throwaway is never a contact); before it, the same as the peer's key.
            endpoint.peerVerifiedIn { node, fingerprint -> PeerIdentityGate.store?.state(node, fingerprint) }
        }.getOrDefault(false)

    // ----- Experimental in-call link renewal (Design SR revision 2; PROTOCOL "Experimental in-call link renewal") -----

    /** Checked scope: GATT, no relay, the hop peer is the end-to-end peer, not a verification session. */
    private fun renewalFacts(): CallRenewalScope.Facts = CallRenewalScope.Facts(
        gattOnBothEnds = transport == BluetoothTransportOption.BLE_GATT,
        relayHops = relayHops,
        relayedRoute = offerViaPrefix != null || pendingRelayRoute != null || relayDuty != null,
        linkPeerIsEndpointPeer = secureSession?.peerNodeId?.let { it == contentSession?.peerNodeId } == true,
        verificationSession = qrScope != null || qrScanPayload != null,
    )

    private fun renewalPossible(): Boolean = renewalNegotiated && CallRenewalScope.direct(renewalFacts())

    /** A running renewal decides when the call ends; the normal liveness close waits for it. */
    private fun renewalOwnsLiveness(): Boolean = renewalOfferer.ownsLiveness() || renewalArmed.suspendsLiveness()

    /**
     * True while an in-call renewal runs (amendment 10 f): the attempt owner's precedence rule, and the set's 12 s end
     * deferral. NOT `phase != IDLE`, which sticks on WAITING_OLD_LINK/FAILED and would hold every end and block attempts.
     */
    fun renewalBusy(): Boolean = renewalOfferer.ownsLiveness() || renewalArmed.suspendsLiveness()

    /** A thread of [gen] that failed belongs to a link the renewal replaces or owns: end only the thread. */
    private fun renewalEndsOldLinkQuietly(gen: Int): Boolean =
        callRunning.get() && (
            gen != linkGeneration.get() || renewalOwnsLiveness() ||
                renewalOfferer.phase == RenewalOfferer.Phase.BOUND || renewalArmed.phase == RenewalArmed.Phase.BOUND
            )

    /** A reader or writer of the current link ended quietly: the supervisor ends the call once no renewal owns it. */
    private fun noteLinkEndedQuietly(gen: Int, thread: String) {
        if (gen != linkGeneration.get()) return
        if (!renewalLinkDead) CallRenewalAndroid.log(if (renewalArmed.suspendsLiveness()) "armed" else "offerer", "link_dead", "thread=$thread")
        renewalLinkDead = true
    }

    private fun createWifiHop(link: SecureBluetoothSession, endpoint: EndToEndVideoSession, initiator: Boolean): WifiHopAgent? {
        // Not for BVMID2 verification scans, peers without BWF1 (the opt-in below) or a call whose carrier 0 is itself Wi-Fi (A8 native link, X14).
        // Relayed calls ARE included (M-3, X8): their link peer is a relay and the key is the hop key.
        if (qrScanPayload != null) return null
        if (!link.reader.acceptWifi) {
            // A forced Wi-Fi arm that cannot start must say so: without this line the case silently runs on Bluetooth.
            val requested = DebugTunables.wifiForceCarrier
            if (requested == "aware" || requested == "direct") {
                WifiLog.emit(WifiLog.carrier(WifiHopKind.CALL.log, requested, "bluetooth", "no_opt_in"))
            }
            return null
        }
        if (carrierSnapshot().kinds[0]?.isWifi == true) return null
        val relayEdge = linkPeerIsRelay(link.peerNodeId, endpoint.peerNodeId)   // [Am 12][R-13]: the same answer at both ends of the edge
        val ticketRegistry = CarrierTickets()
        markerCodec = WifiMarkerCodec(ticketRegistry)    // before the first addWifiCarrier: A3 refuses a carrier while the placeholder codec is installed [X3]
        val ports = object : WifiHopPorts {
            override val isLinkInitiator = initiator
            override val tickets = ticketRegistry
            override fun optedIn() = link.reader.acceptWifi
            override fun callActive() = state?.phase == PrivateCallPhase.ACTIVE
            override fun revealsDone(): Boolean = endpoint.reveal?.let { r ->
                (!r.peerPseudonymous || r.revealed != null) && (!r.ownPseudonymous || r.ownRevealSent)
            } ?: true
            override fun renewalRunning() = renewalBusy()
            override fun hasWifiCarrier() = carrierSnapshot().hasWifiCarrier
            override fun fallbackCount() = carrierSnapshot().fallbackCount
            override fun sendRecord(record: Bwf) = offerWifiRecord(0, record.encode())
            // A copy the attempt wipes; a key zeroed by close() counts as no key. A BMH2 end-to-end call has no key:
            // the agent then sends all-clear CAPS and declines every HOST.
            override fun uwCopy(): ByteArray? = wifiKeyFor(relayEdge, endpoint.wifiKey, link.hopWifiKey)?.copyOf()?.takeIf { k -> k.any { it != 0.toByte() } }   // relayEdge, not relayHops [Am 12]
            override fun offerCarrier(carrier: WifiSocketCarrier): Int {
                val id = addWifiCarrier(carrier)
                if (id >= 0) { ticketRegistry.register(id, carrier.ticket); wifiCarrierPending(id); wifiCarrierUp(id) }   // register BEFORE pending: a marker can be encoded at once
                return id
            }
            // hopWifiKey = null: closing the carrier must not zero the primary's UW (A2: SecureBluetoothSession.close() calls dropWifiKey())
            override fun carrierLink(reader: SecureRecordReader, writer: SecureRecordWriter) = (secureSession ?: link).copy(reader = reader, writer = writer, hopWifiKey = null)
            override fun carrierDied(id: Int, reason: String) = wifiCarrierDied(id, reason)
            override fun localReady() = Bwf.Ready(0xFFFF, 0, 0xFFFF)     // interim: no receive ceiling, family cap or transport budget known (R-12)
            override fun protocolViolation(carrierId: Int, reason: String) { if (carrierId != 0) wifiProtocolViolation(carrierId, reason) }   // never carrier 0 [Am 6]
            override fun carrierSwitchOff() { switchCarriersOff() }    // the machine is reached only through CarrierSet; NOT wifiSwitchOff(), which would recurse
            override fun carrierSample(id: Int): CarrierSample? {
                val s = carrierSnapshot(); val st = s.stats[id] ?: return null
                return CarrierSample(isSendCarrier = s.sendCarrier == id, bytesWritten = st.bytesWritten, queuedContent = st.queuedContent, oldestQueuedAgeMs = st.oldestQueuedAgeMs)   // [Am 4] A3 CarrierStats field
            }
            override fun bluetoothBudgetKbps() = LinkCapacity.measuredLinkKbps(transport, CarrierKind.BLUETOOTH)
            override fun requestedMediaKbps(): Int? = activeSettings.bitRate.takeIf { it > 0 }?.div(1_000)
            // AP10: other Bluetooth media of this phone, from existing state only (never a radio query); this call's own link is not in any of it.
            override fun otherBluetoothMedia(): Boolean = OtherBluetoothMedia.forCall(relayDuty = relayingNow)
            override fun qualityFallback() = wifiQualityFallback()       // A3: carriers?.qualityFallback()
        }
        return WifiHopAgent(ports, RealWifiEnv).also { agent -> wifiRecordHandler = { id, payload -> agent.onRecord(id, payload) } }
    }

    /**
     * Review item 1: liveness no longer lives only in the reader and writer threads. One tick per call (renewal negotiated)
     * ends a call whose link died while no renewal owns it, and a silence the writer did not end (a blocked writer).
     */
    private fun startRenewalSupervisor(endpoint: EndToEndVideoSession) {
        thread(name = "CallRenewalSupervisor", isDaemon = true) {
            val ticker = RenewalLivenessTicker()
            while (callRunning.get() && contentSession === endpoint) {
                val nowMs = SystemClock.elapsedRealtime()
                val nowNs = System.nanoTime()
                val verdict = CallStallTolerance.verdict(stallTolerance || renewalPossible(), nowNs, lastHeartbeatNs, lastSendProgressNs)
                // In fan-out the producer judges no liveness: the set's monitor does. This tick keeps the renewal trigger
                // and "Poor connection" (which follows the carrier that sends) on carrier 0's measurements.
                val fanoutSet = carriers
                if (fanoutSet != null && fanoutSet.fanout) {
                    if (fanoutSet.sendCarrier == 0) noteStall(verdict, nowNs)
                    maybeStartRenewal(verdict, nowNs)
                }
                when (ticker.tick(verdict == CallStallTolerance.Verdict.END, renewalLinkDead, renewalOwnsLiveness(), nowMs)) {
                    RenewalLiveness.Decision.END_DEAD_LINK -> {
                        CallRenewalAndroid.log("supervisor", "failed", "reason=link_dead")
                        if (!carrierLost(0, linkGeneration.get(), null, "Call link lost", "supervisor")) return@thread
                    }
                    RenewalLiveness.Decision.END_SILENCE -> {
                        CallRenewalAndroid.log("supervisor", "failed", "reason=silence")
                        if (!carrierLost(0, linkGeneration.get(), null, "Call link lost: Call peer stopped responding", "supervisor")) return@thread
                    }
                    RenewalLiveness.Decision.NONE -> Unit
                }
                try {
                    Thread.sleep(RenewalLiveness.TICK_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    private fun renewalRefusal(nowMs: Long): CallRenewalWire.Reason? = CallRenewalScope.refusal(
        enabled = renewalOn,
        negotiated = renewalNegotiated,
        facts = renewalFacts(),
        phaseActive = state?.phase == PrivateCallPhase.ACTIVE,
        activeForMs = if (activeAtNs > 0L) (System.nanoTime() - activeAtNs) / 1_000_000L else 0L,
        attempts = renewalAttempts.count,
        sinceLastAttemptMs = renewalAttempts.sinceLast(nowMs),
    )

    /**
     * Must-deliver records a replaced writer took and had not flushed go back to the head of the queue, in their order:
     * the signals [RenewalRequeue] keeps, and (review 2026-10-27, finding 2) an identity reveal, as the bytes already built
     * (a reveal is signed once; the peer ignores an identical repeat, [Bmh3RevealSession.onPeerReveal]).
     */
    private fun requeueSignals(taken: List<OutboundControl>) {
        val keep = RenewalRequeue.signalsToRequeue(taken.filterIsInstance<OutboundControl.Signal>().map { it.message }).toSet()
        controls.offerMustFirst(taken.filter { it !is OutboundControl.Signal || it.message in keep })
    }

    private fun enqueueRenew(message: CallRenewalWire.Message) {
        controls.offerMust(OutboundControl.Renew(CallRenewalWire.encode(message)))
    }

    /** DEBUG `test_call_tx_stall_after_s`: true while this phone's first link must stop sending. */
    private fun debugTxStalled(gen: Int): Boolean {
        val afterS = DebugTunables.callTxStallAfterS
        if (!BuildConfig.DEBUG || afterS <= 0 || gen != firstLinkGeneration || activeAtNs == 0L) return false
        if (System.nanoTime() - activeAtNs < afterS * 1_000_000_000L) return false
        if (!debugTxStallLogged) {
            debugTxStallLogged = true
            Log.i("BluetoothVideo", "BENCHMARK call_debug_tx_stall: afterS=$afterS | link=first")
        }
        return true
    }

    /** Offerer trigger: Stage A's soft state (no record for 3 s while own writes complete) held for 1 s. */
    private fun maybeStartRenewal(stall: CallStallTolerance.Verdict, nowNs: Long) {
        if (!renewalNegotiated) return
        val soft = stall == CallStallTolerance.Verdict.POOR && nowNs - lastSendProgressNs < CallStallTolerance.SEND_FRESH_NS
        if (!soft) {
            renewalSoftSinceMs = null
            renewalRefusalLogged = false
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        val since = renewalSoftSinceMs ?: nowMs.also { renewalSoftSinceMs = it }
        if (!renewalOfferer.shouldTrigger(since) || renewalArmed.suspendsLiveness()) return
        val refusal = renewalRefusal(nowMs)
        if (refusal != null) {
            if (!renewalRefusalLogged) {
                renewalRefusalLogged = true
                CallRenewalAndroid.log("offerer", "not_offered", "reason=${refusal.label}")
            }
            return
        }
        startRenewalOffer((nowNs - lastHeartbeatNs) / 1_000_000L)
    }

    private fun startRenewalOffer(silentMs: Long) {
        val endpoint = contentSession ?: return
        val key = endpoint.renewalKey ?: return
        val callId = state?.callId ?: return
        val oldLink = secureSession ?: return
        val ticket = CallRenewalAndroid.ticket()
        val offerer = renewalOfferer
        offerer.offer(ticket)
        renewalAttempts.record(SystemClock.elapsedRealtime())
        wifiHop?.onRenewalStarting()
        enqueueRenew(CallRenewalWire.Offer(ticket, renewalRole))
        CallRenewalAndroid.log("offerer", "offer", "attempt=${renewalAttempts.count} | silentMs=$silentMs")
        thread(name = "CallRenewalOfferer") { runOfferer(offerer, endpoint, oldLink, key, callId, ticket) }
    }

    private fun runOfferer(
        offerer: RenewalOfferer,
        endpoint: EndToEndVideoSession,
        oldLink: SecureBluetoothSession,
        key: ByteArray,
        callId: UUID,
        ticket: Long,
    ) {
        val uuid = CallRenewalCrypto.advertUuid(key, callId, ticket)
        val found = ArrayBlockingQueue<BluetoothDevice>(8)
        val scanner = CallRenewalScanner(adapter)
        renewalScanner = scanner
        CallRenewalAndroid.log("offerer", "scan", "started=${scanner.start(uuid) { found.offer(it) }}")
        try {
            while (callRunning.get() && renewalOfferer === offerer) {
                when (offerer.tick()) {
                    RenewalOfferer.Tick.STOP_SCAN -> {
                        scanner.stop()
                        CallRenewalAndroid.log("offerer", "scan_ended", "found=false | ms=${elapsedSinceOffer(offerer)}")
                    }
                    RenewalOfferer.Tick.END_CALL -> {
                        scanner.stop()
                        CallRenewalAndroid.log("offerer", "failed", "reason=bound | ms=${elapsedSinceOffer(offerer)}")
                        // The bound is reached only after the old link was broken (the scan ends at 6 s): no link to hang up on.
                        if (carriers?.fanout == true) {
                            renewalOfferer = RenewalOfferer(SystemClock::elapsedRealtime)
                            carrierLost(0, linkGeneration.get(), null, "Call link lost: the link renewal did not complete", "renewal_failed")
                        } else {
                            fail("Call link lost: the link renewal did not complete")
                        }
                        return
                    }
                    RenewalOfferer.Tick.NONE -> Unit
                }
                // Not found: the old link stays under the normal liveness rules (and the supervisor, if it died meanwhile).
                when (offerer.phase) {
                    RenewalOfferer.Phase.SCANNING, RenewalOfferer.Phase.CONNECTING -> Unit
                    else -> return
                }
                val device = found.poll(RENEWAL_TICK_MS, TimeUnit.MILLISECONDS) ?: continue
                if (offerer.phase == RenewalOfferer.Phase.SCANNING) {
                    if (!offerer.onAdvertFound()) continue
                    scanner.stop()
                    CallRenewalAndroid.log(
                        "offerer", "found",
                        "ms=${elapsedSinceOffer(offerer)} | addressKind=${CallRenewalAndroid.addressKind(runCatching { device.address }.getOrNull())}",
                    )
                    breakOldLink()
                }
                if (offerer.phase != RenewalOfferer.Phase.CONNECTING) continue
                if (connectRenewed(offerer, device, endpoint, oldLink, key, callId, ticket)) return
                // Review item 3: a stale sighting never redials once no attempt is left.
                found.clear()
                if (offerer.mayRetry()) {
                    // A squatter, a copied advert or a failed connect: wait for a new sighting while time remains.
                    scanner.start(uuid) { found.offer(it) }
                }
            }
        } finally {
            scanner.stop()
            if (renewalScanner === scanner) renewalScanner = null
        }
    }

    private fun elapsedSinceOffer(offerer: RenewalOfferer): Long = SystemClock.elapsedRealtime() - offerer.offeredAtMs

    /** Break only after found (review 2, finding 2): stop the old writer, close the old link, keep the call. */
    private fun breakOldLink() {
        linkGeneration.incrementAndGet()
        val oldWriter = writerThread
        runCatching { connection?.close() }
        // Amendment 10 c: in fan-out the producer keeps running across the renewal; the set ends carrier 0 itself.
        if (carriers?.fanout != true) {
            oldWriter?.takeIf { it !== Thread.currentThread() }?.let { writer ->
                writer.interrupt()
                runCatching { writer.join(RENEWAL_JOIN_MS) }
            }
        }
        runCatching { secureSession?.close() }
    }

    /**
     * Review item 3: GattVideoClient.open can block for about 30 s; the attempt gets only what is left of the offerer's
     * connect budget, and a connection that arrives later is closed.
     */
    private fun openRenewalClientWithin(device: BluetoothDevice, budgetMs: Long): GattVideoConnection? {
        val result = AtomicReference<GattVideoConnection?>()
        val failure = AtomicReference<Exception?>()
        val late = AtomicBoolean(false)
        val done = CountDownLatch(1)
        thread(name = "CallRenewalConnect", isDaemon = true) {
            try {
                val opened = GattVideoClient.open(appContext, device, requestedLinkPhy, BleConnectionPriority.HIGH, onStatus, acknowledgedWrites = false)
                synchronized(late) {
                    if (late.get() || !callRunning.get()) runCatching { opened.close() } else result.set(opened)
                }
            } catch (error: Exception) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }
        if (!done.await(budgetMs, TimeUnit.MILLISECONDS)) {
            synchronized(late) {
                late.set(true)
                result.getAndSet(null)?.let { runCatching { it.close() } }
            }
            CallRenewalAndroid.log("offerer", "connect_timeout", "budgetMs=$budgetMs")
            return null
        }
        failure.get()?.let { throw it }
        return result.get()
    }

    /** True when this offer is finished (bound, or the call ended); false lets the caller retry while time remains. */
    private fun connectRenewed(
        offerer: RenewalOfferer,
        device: BluetoothDevice,
        endpoint: EndToEndVideoSession,
        oldLink: SecureBluetoothSession,
        key: ByteArray,
        callId: UUID,
        ticket: Long,
    ): Boolean {
        var active: GattVideoConnection? = null
        val done = AtomicBoolean(false)
        try {
            val budget = offerer.connectBudgetMs()
            if (budget <= 0L) return false
            CallRenewalAndroid.log("offerer", "connecting", "ms=${elapsedSinceOffer(offerer)} | budgetMs=$budget")
            val opened = openRenewalClientWithin(device, budget) ?: return false
            active = opened
            // The opening never waits past the last connect moment: a silent peer is closed, not waited for.
            val waitMs = offerer.connectBudgetMs().coerceAtLeast(1L)
            thread(name = "CallRenewalOpeningWatch", isDaemon = true) {
                runCatching { Thread.sleep(waitMs) }
                if (!done.get()) runCatching { opened.close() }
            }
            val keys = CallRenewalOpening.offerer(opened.inputStream, opened.outputStream, key, callId, ticket, CallRenewalAndroid.randomBytes())
            if (keys == null) {
                done.set(true)
                CallRenewalAndroid.log("offerer", "confirm_refused", "ms=${elapsedSinceOffer(offerer)}")
                opened.close()
                return false
            }
            val (toArmed, fromArmed) = keys
            val newLink = SecureBluetoothSession(
                peerNodeId = oldLink.peerNodeId,
                peerCapabilities = oldLink.peerCapabilities,
                reader = SecureRecordReader(BufferedInputStream(opened.inputStream, ioConfig.bufferBytes), fromArmed),
                writer = SecureRecordWriter(BufferedOutputStream(opened.outputStream, ioConfig.bufferBytes), toArmed),
            )
            toArmed.fill(0)
            fromArmed.fill(0)
            done.set(true)
            CallRenewalAndroid.log("offerer", "proved", "ms=${elapsedSinceOffer(offerer)}")
            // Review item 6: the bound is checked before BIND goes out, so a late BIND never binds the peer alone.
            if (!offerer.onBound()) {
                newLink.close()
                opened.close()
                return false
            }
            try {
                // BIND is the first BMS1 record on the new link; the old writer was joined, so nothing seals in between.
                val bind = endpoint.sealer.sealNext(EndToEndContentType.RENEW, 0) { sequence ->
                    CallRenewalWire.encode(CallRenewalWire.Bind(ticket, sequence + 1))
                }
                newLink.writer.write(SecureRecordType.END_TO_END_CONTENT, bind)
                newLink.writer.flush()
            } catch (error: Exception) {
                CallRenewalAndroid.log("offerer", "failed", "reason=bind_write_${error.javaClass.simpleName}")
                newLink.close()
                opened.close()
                if (carriers?.fanout == true) {
                    renewalOfferer = RenewalOfferer(SystemClock::elapsedRealtime)
                    carrierLost(0, linkGeneration.get(), null, "Call link lost: the link renewal did not complete", "renewal_failed")
                } else {
                    fail("Call link lost: the link renewal did not complete")
                }
                return true
            }
            switchToRenewedLink(gattConnection(opened, address = runCatching { device.address }.getOrNull()), newLink, endpoint, "offerer")
            CallRenewalAndroid.log("offerer", "bound", "ms=${elapsedSinceOffer(offerer)} | attempt=${renewalAttempts.count}")
            offerer.reset()
            return true
        } catch (error: Exception) {
            done.set(true)
            CallRenewalAndroid.log("offerer", "connect_failed", "reason=${error.javaClass.simpleName} | ms=${elapsedSinceOffer(offerer)}")
            runCatching { active?.close() }
            return false
        }
    }

    /** The offerer heard its peer again on the old link: the renewal is abandoned and the old link stays. */
    private fun noteRenewalPeerHeard() {
        val offerer = renewalOfferer
        if (!offerer.onPeerHeard()) return
        renewalScanner?.stop()
        enqueueRenew(CallRenewalWire.Refused(offerer.ticket, CallRenewalWire.Reason.ABANDONED))
        CallRenewalAndroid.log("offerer", "abandoned", "reason=peer_heard | ms=${elapsedSinceOffer(offerer)}")
        offerer.reset()
    }

    /** The first record on a renewed link: the silence the renewal cost (last record on the old link to this one). */
    private fun noteRenewedLinkHeard() {
        val from = renewalSilenceFromNs
        if (from == 0L) return
        renewalSilenceFromNs = 0L
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                "call_renewal_silence: role=$renewalSilenceRole | ms=${(System.nanoTime() - from) / 1_000_000L}",
        )
    }

    private fun onRenewRecord(message: CallRenewalWire.Message) {
        when (message) {
            is CallRenewalWire.Offer -> onRenewOffer(message)
            is CallRenewalWire.Armed -> CallRenewalAndroid.log(
                "offerer", "armed_heard", "current=${message.ticket == renewalOfferer.ticket}",
            )
            is CallRenewalWire.Refused -> if (message.reason == CallRenewalWire.Reason.ABANDONED) {
                if (renewalArmed.onAbandoned(message.ticket)) {
                    renewalServers.abandonAttempt()
                    CallRenewalAndroid.log("armed", "disarmed", "reason=abandoned")
                }
            } else if (message.ticket == renewalOfferer.ticket && renewalOfferer.onRefused()) {
                renewalScanner?.stop()
                CallRenewalAndroid.log("offerer", "refused", "reason=${message.reason.label}")
            }
            // BIND counts only as the first record of the proved new link (runArmedOpening); here it is ignored.
            is CallRenewalWire.Bind -> CallRenewalAndroid.log("armed", "bind_ignored", "reason=not_proved_link")
        }
    }

    private fun onRenewOffer(offer: CallRenewalWire.Offer) {
        val offerer = renewalOfferer
        var yielded = false
        // Review item 8: only an offer that still scans is pending; a finished scan is not a collision.
        if (offerer.ownOfferPending()) {
            if (RenewalCollision.keepOwn(renewalRole)) {
                enqueueRenew(CallRenewalWire.Refused(offer.ticket, CallRenewalWire.Reason.COLLISION))
                CallRenewalAndroid.log("armed", "refused", "reason=collision")
                return
            }
            offerer.yieldToPeer()
            renewalScanner?.stop()
            offerer.reset()
            yielded = true
            CallRenewalAndroid.log("offerer", "yielded", "reason=collision")
        }
        val nowMs = SystemClock.elapsedRealtime()
        // A callee that yielded already counted this renewal when it offered: the caller's attempt is the same renewal.
        val refusal = if (yielded) renewalRefusal(nowMs).takeIf { it != CallRenewalWire.Reason.LIMIT } else renewalRefusal(nowMs)
        if (!renewalArmed.onOffer(offer.ticket, refusal)) {
            enqueueRenew(CallRenewalWire.Refused(offer.ticket, refusal ?: CallRenewalWire.Reason.LIMIT))
            CallRenewalAndroid.log("armed", "refused", "reason=${(refusal ?: CallRenewalWire.Reason.LIMIT).label}")
            return
        }
        if (!yielded) renewalAttempts.record(nowMs)
        wifiHop?.onRenewalStarting()
        CallRenewalAndroid.log("armed", "offer", "attempt=${renewalAttempts.count} | yielded=$yielded")
        val armed = renewalArmed
        thread(name = "CallRenewalArmed") { armRenewal(armed, offer.ticket) }
    }

    /** The armed phone: a dedicated server and advert within 3 s, then its window and bounds (ticks). */
    private fun armRenewal(armed: RenewalArmed, ticket: Long) {
        val endpoint = contentSession
        val key = endpoint?.renewalKey
        val callId = state?.callId
        if (endpoint == null || key == null || callId == null) {
            // Re-review N3: never leave ARMING (liveness suspended) behind.
            armed.onServerFailed()
            enqueueRenew(CallRenewalWire.Refused(ticket, CallRenewalWire.Reason.DISABLED))
            CallRenewalAndroid.log("armed", "refused", "reason=no_session")
            armed.reset()
            return
        }
        val servers = renewalServers
        val uuid = CallRenewalCrypto.advertUuid(key, callId, ticket)
        val nonDiscoverable = NonDiscoverableAdvertising.forNewSet(connectable = true)
        val created = AtomicReference<GattVideoServer?>()
        val adopted = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val ready = CountDownLatch(1)
        val worker = thread(name = "CallRenewalServer") {
            try {
                val server = GattVideoServer(appContext, adapter, renewalPlaceholderAnnouncement(), phyPreference, onStatus) { active ->
                    onRenewalConnection(armed, active, ticket)
                }
                server.useRenewalAdvert(uuid)
                created.set(server)
                server.start()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                ready.countDown()
            }
        }
        val waitMs = (armed.receivedAtMs + RenewalArmed.REGISTRATION_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val inTime = ready.await(waitMs, TimeUnit.MILLISECONDS)
        val server = created.get()
        // Review item 4: adopted under the lock teardown uses; a call that ended meanwhile closes it at once.
        if (inTime && failure.get() == null && server != null && callRunning.get() && armed.onServerReady() && servers.startAttempt(server)) {
            adopted.set(true)
            enqueueRenew(CallRenewalWire.Armed(ticket))
            CallRenewalAndroid.log("armed", "armed", "nonDiscoverable=$nonDiscoverable | ms=${SystemClock.elapsedRealtime() - armed.receivedAtMs}")
        } else {
            armed.onServerFailed()
            val reason = if (failure.get()?.message?.contains("advertis", ignoreCase = true) == true) {
                CallRenewalWire.Reason.ADVERTISING
            } else {
                CallRenewalWire.Reason.SERVER_REGISTRATION
            }
            enqueueRenew(CallRenewalWire.Refused(ticket, reason))
            CallRenewalAndroid.log("armed", "refused", "reason=${reason.label} | inTime=$inTime")
            // A registration that completes after the bound is closed, never adopted.
            thread(name = "CallRenewalLateServer", isDaemon = true) {
                runCatching { worker.join() }
                if (!adopted.get()) created.get()?.close()
            }
            armed.reset()
            return
        }
        try {
            while (callRunning.get() && renewalArmed === armed) {
                when (armed.tick(oldLinkAlive = !renewalLinkDead)) {
                    RenewalArmed.Tick.STOP_ADVERT -> {
                        servers.attempt()?.stopAdvertisingNow()
                        CallRenewalAndroid.log("armed", "advert_stopped", "ms=${SystemClock.elapsedRealtime() - armed.receivedAtMs}")
                    }
                    RenewalArmed.Tick.DISARM -> {
                        servers.abandonAttempt()
                        CallRenewalAndroid.log("armed", "disarmed", "reason=bound_old_link_alive")
                        armed.reset()
                        return
                    }
                    RenewalArmed.Tick.END_CALL -> {
                        servers.abandonAttempt()
                        CallRenewalAndroid.log("armed", "failed", "reason=no_bind")
                        if (carriers?.fanout == true) {
                            renewalArmed = RenewalArmed(SystemClock::elapsedRealtime)
                            carrierLost(0, linkGeneration.get(), null, "Call link lost: the link renewal did not complete", "renewal_failed")
                        } else {
                            fail("Call link lost: the link renewal did not complete")
                        }
                        return
                    }
                    RenewalArmed.Tick.REFUSE_REGISTRATION, RenewalArmed.Tick.NONE -> Unit
                }
                if (armed.phase != RenewalArmed.Phase.ARMED) return
                Thread.sleep(RENEWAL_TICK_MS)
            }
        } finally {
            // Review item 4: a call that ended (or a newer call) never leaves this attempt's server or advert behind.
            if (armed.phase != RenewalArmed.Phase.BOUND && armed.phase != RenewalArmed.Phase.IDLE) servers.abandonAttempt()
            if (!callRunning.get()) servers.abandonAttempt()
        }
    }

    /** Never advertised: the renewal server's set carries only the derived UUID ([GattVideoServer.useRenewalAdvert]). */
    private fun renewalPlaceholderAnnouncement() = DiscoveryAnnouncement(
        nodeIdPrefix = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
        version = DiscoveryAnnouncement.FIXED_PREFIX_VERSION,
        flags = DiscoveryAnnouncement.FLAG_GATT_TRANSPORT,
        capabilitySummary = 0,
        streamToken = 0,
        routeCost = 0,
        sequence = 0,
    )

    private fun onRenewalConnection(armed: RenewalArmed, active: GattVideoConnection, ticket: Long) {
        val id = renewalConnections.incrementAndGet()
        if (!callRunning.get() || renewalArmed !== armed || armed.onConnection(id) is RenewalArmed.Admission.Drop) {
            CallRenewalAndroid.log("armed", "connection_dropped", "reason=admission")
            active.close()
            return
        }
        thread(name = "CallRenewalOpening") { runArmedOpening(armed, active, id, ticket) }
    }

    private fun runArmedOpening(armed: RenewalArmed, active: GattVideoConnection, id: Long, ticket: Long) {
        val endpoint = contentSession
        val key = endpoint?.renewalKey
        val callId = state?.callId
        val oldLink = secureSession
        if (endpoint == null || key == null || callId == null || oldLink == null) {
            active.close()
            return
        }
        // Squatters: no valid proof 1 s after the challenge drops the connection; the set keeps advertising.
        thread(name = "CallRenewalProofWatch", isDaemon = true) {
            runCatching { Thread.sleep(RenewalArmed.PROOF_MS) }
            if (armed.proofOverdue(id)) {
                CallRenewalAndroid.log("armed", "squatter_dropped", "connection=$id")
                active.close()
            }
        }
        try {
            // The tested opening (CallRenewalOpening.armed); the state machine decides on the proof.
            val keys = CallRenewalOpening.armed(active.inputStream, active.outputStream, key, callId, ticket, CallRenewalAndroid.randomBytes()) { proofTicket, valid ->
                armed.onProof(id, proofTicket, valid) == RenewalArmed.ProofResult.CONFIRM
            }
            if (keys == null) {
                CallRenewalAndroid.log("armed", "proof_refused", "connection=$id")
                active.close()
                return
            }
            val (fromOfferer, toOfferer) = keys
            val newLink = SecureBluetoothSession(
                peerNodeId = oldLink.peerNodeId,
                peerCapabilities = oldLink.peerCapabilities,
                reader = SecureRecordReader(BufferedInputStream(active.inputStream, ioConfig.bufferBytes), fromOfferer),
                writer = SecureRecordWriter(BufferedOutputStream(active.outputStream, ioConfig.bufferBytes), toOfferer),
            )
            fromOfferer.fill(0)
            toOfferer.fill(0)
            CallRenewalAndroid.log("armed", "proved", "connection=$id | ms=${SystemClock.elapsedRealtime() - armed.receivedAtMs}")
            // BIND must be the first record of this proved link, with this ticket, once (revision 2, rule 9). It is opened
            // under the record lock, and an accepted BIND moves the generation inside it: no old-link record is opened after it.
            val record = newLink.reader.read()
            val accepted = synchronized(recordLock) {
                val content = if (record.type == SecureRecordType.END_TO_END_CONTENT) {
                    // Amendment 10 d: in fan-out the producer keeps sealing across the renewal, so records sealed after BIND
                    // can reach the opener first; the renewal link's keys already make this BIND fresh and single.
                    runCatching {
                        if (carriers?.fanout == true) endpoint.opener.openOutOfBand(record.payload) else endpoint.opener.open(record.payload)
                    }.getOrNull()
                } else {
                    null
                }
                val bind = CallRenewalOpening.bindFrom(content)
                val ok = bind != null && armed.onBind(id, bind.ticket, firstRecord = true) == RenewalArmed.BindResult.ACCEPT
                if (ok) linkGeneration.incrementAndGet()
                ok
            }
            if (!accepted) {
                CallRenewalAndroid.log("armed", "bind_refused", "connection=$id")
                newLink.close()
                active.close()
                return
            }
            // Review item 2: this attempt's server now carries the live link; an older renewal's server closes after the switch.
            val previous = renewalServers.promote()
            switchToRenewedLink(gattConnection(active), newLink, endpoint, "armed")
            previous?.let { runCatching { it.close() } }
            CallRenewalAndroid.log("armed", "bound", "connection=$id | ms=${SystemClock.elapsedRealtime() - armed.receivedAtMs}")
            armed.reset()
        } catch (error: Exception) {
            armed.onConnectionClosed(id)
            CallRenewalAndroid.log("armed", "opening_failed", "connection=$id | reason=${error.javaClass.simpleName}")
            runCatching { active.close() }
        }
    }

    /** Moves the call to the renewed link: new threads on a new generation; the old link closes, the call continues. */
    private fun switchToRenewedLink(
        newConnection: CallConnection,
        newLink: SecureBluetoothSession,
        endpoint: EndToEndVideoSession,
        role: String,
    ) = synchronized(renewalSwitchLock) {
        if (!callRunning.get()) {
            runCatching { newLink.close() }
            runCatching { newConnection.close() }
            return@synchronized
        }
        // Measured from the last record heard on the old link (the offerer stopped hearing earlier, the armed phone at the break).
        val silenceFromNs = lastHeartbeatNs
        val gen = linkGeneration.incrementAndGet()
        renewalSilenceRole = role
        renewalSilenceFromNs = silenceFromNs
        val oldConnection = connection
        val oldLink = secureSession
        // A renewed link has no marker fields of its own (they are false by construction, A2 step 3): it inherits the
        // old reader's opt-in. The flag is a volatile boolean that survives the old link's close.
        newLink.reader.acceptWifi = oldLink?.reader?.acceptWifi == true
        val oldWriter = writerThread
        connection = newConnection
        secureSession = newLink
        if (oldConnection !== newConnection) runCatching { oldConnection?.close() }
        // Review item 7: the old writer (on the armed phone it may be blocked in the stalled link) is stopped and joined.
        // Amendment 10 c: in fan-out the producer keeps sealing into the set; the set ends carrier 0 itself.
        if (carriers?.fanout != true) {
            oldWriter?.takeIf { it !== Thread.currentThread() }?.let { writer ->
                writer.interrupt()
                runCatching { writer.join(RENEWAL_JOIN_MS) }
            }
        }
        if (oldLink !== newLink) runCatching { oldLink?.close() }
        if (role == "armed") renewalServers.bound()?.stopAdvertisingNow()
        val now = System.nanoTime()
        lastHeartbeatNs = now
        lastSendProgressNs = now
        renewalSoftSinceMs = null
        renewalLinkDead = false
        receivedDelivery = LinkDeliveryCounter()
        peerDelivery = PeerDeliveryEstimate()
        noteStall(CallStallTolerance.Verdict.OK, now)
        // Review A3 P1-1: exactly one re-assert per renewal (fan-out: the machine's, queued behind its marker; inline: here).
        val machineReasserts = carriers?.replacePrimary(
            SessionCarrier(
                newConnection.carrierKind,
                newConnection.description,
                newLink,
                CarrierLivenessPolicy.of(newConnection.carrierKind),
                closesLink = false,
            ) { },
            gen,
        ) == true
        if (carriers?.fanout != true) startWriter(newLink, endpoint, gen)
        startReader(newLink, endpoint, renewalRole == 0, gen)
        if (!machineReasserts) reassertAfterRenewal()
        onStatus("Call link renewed over ${newConnection.description}")
    }

    /** Revision 2, rule 8: what a cut-off old link may have lost is sent once more on the new link. */
    private fun reassertAfterRenewal() {
        controls.offerKeyed("key-frame-request", OutboundControl.KeyFrameRequest)
        onLocalKeyFrameNeeded()
        if (state?.phase != PrivateCallPhase.ACTIVE) return
        enqueueSignal(if (muted) PrivateCallSignal.MICROPHONE_MUTED else PrivateCallSignal.MICROPHONE_UNMUTED)
        lastCameraSignal?.let { enqueueSignal(it) }
        lastLabelsPayload?.let { controls.offerMust(OutboundControl.Labels(it)) }
    }

    private fun startWriter(
        link: SecureBluetoothSession,
        endpoint: EndToEndVideoSession,
        gen: Int = linkGeneration.get(),
    ) {
        writerThread = thread(name = "PrivateCallWriter") {
            // Review item 7 and re-review N2: must-deliver signals this writer took and has not flushed yet go back to the
            // head of the queue when its link is replaced (RenewalRequeue).
            val unflushedSignals = ArrayList<OutboundControl>()
            try {
                var lastHeartbeatWrittenNs = 0L
                var lastFlushNs = System.nanoTime()
                var sampleStartNs = System.nanoTime()
                var sampleSentBytes = 0L
                var sampleThinned = thinnedVideoFrames.get()
                var sampleShed = boundedShedFrames.get()
                // DEBUG controlled impairment test_call_inject_send_pause (CallSendPauseInject); null = unchanged writer.
                val sendPause = CallSendPauseInject.newSchedule { line -> if (BuildConfig.DEBUG) Log.i("BluetoothVideo", line) }
                val mediaFlushIntervalNs = privateCallMediaFlushIntervalMs(
                    transport,
                    stackFingerprint,
                    peerMaxBitrateKbps,
                    carriers?.kindOf(0) ?: CarrierKind.BLUETOOTH,
                ) * 1_000_000L
                // Tell the peer this hop accepts version 3 (delivery) reports. Older endpoints take a
                // version-1 heartbeat as plain liveness and keep sending version 2.
                if (deliveryReports.sendsHello) {
                    SecureLinkControlProtocol.writeHeartbeat(link.writer)
                    link.writer.flush()
                    lastSendProgressNs = System.nanoTime()
                }
                if (pacedWrites) {
                    LinkPacerSeed.seed(pacer, transport)?.let { kbps ->
                        Log.i("BluetoothVideo", "Call link pacer seeded at $kbps kbps until the peer's first report (${transport.shortLabel})")
                    }
                }
                while (callRunning.get() && (gen == linkGeneration.get() || carriers?.fanout == true)) {
                    // DEBUG test_call_tx_stall_after_s: this phone's first link stops sending, as a stalled stack does.
                    if (debugTxStalled(if (carriers?.fanout == true) linkGeneration.get() else gen)) {
                        Thread.sleep(DEBUG_TX_STALL_SLICE_MS)
                        continue
                    }
                    val control = controls.poll()
                    var closeAfter = false
                    when (control) {
                        is OutboundControl.Signal -> {
                            val set = carriers
                            if (set == null || !set.fanout) unflushedSignals += control
                            if (set == null) {
                                writeCallSignal(link, endpoint, control.message)
                            } else {
                                set.withCookie(control) { writeCallSignal(link, endpoint, control.message) }
                            }
                            closeAfter = control.closeAfter
                        }
                        OutboundControl.Header -> EndToEndStreamProtocol.writeHeader(
                            sinkFor(link),
                            endpoint.sealer,
                            activeCodec.protocolId,
                            activeSettings,
                            rotationDegrees,
                            includeAudio = true,
                        )
                        OutboundControl.KeyFrameRequest -> EndToEndStreamProtocol.writeKeyFrameRequest(
                            sinkFor(link),
                            endpoint.sealer,
                        )
                        is OutboundControl.Labels -> {
                            val set = carriers
                            if (set == null) {
                                sinkFor(link).write(
                                    SecureRecordType.END_TO_END_CONTENT,
                                    endpoint.sealer.seal(EndToEndContentType.LABELS, 0, control.payload),
                                )
                            } else {
                                set.withCookie(control) {
                                    sinkFor(link).write(
                                        SecureRecordType.END_TO_END_CONTENT,
                                        endpoint.sealer.seal(EndToEndContentType.LABELS, 0, control.payload),
                                    )
                                }
                            }
                            lastLabelsPayload = control.payload
                        }
                        is OutboundControl.Renew -> {
                            val set = carriers
                            if (set == null) {
                                sinkFor(link).write(
                                    SecureRecordType.END_TO_END_CONTENT,
                                    endpoint.sealer.seal(EndToEndContentType.RENEW, 0, control.payload),
                                )
                            } else {
                                set.withCookie(control) {
                                    sinkFor(link).write(
                                        SecureRecordType.END_TO_END_CONTENT,
                                        endpoint.sealer.seal(EndToEndContentType.RENEW, 0, control.payload),
                                    )
                                }
                            }
                        }
                        is OutboundControl.Discovery -> {
                            val set = carriers
                            if (set == null) {
                                sinkFor(link).write(
                                    SecureRecordType.END_TO_END_CONTENT,
                                    endpoint.sealer.seal(EndToEndContentType.DISCOVERY, 0, control.payload),
                                )
                            } else {
                                set.withCookie(control) {
                                    sinkFor(link).write(
                                        SecureRecordType.END_TO_END_CONTENT,
                                        endpoint.sealer.seal(EndToEndContentType.DISCOVERY, 0, control.payload),
                                    )
                                }
                            }
                        }
                        is OutboundControl.Reveal -> {
                            val reveal = control.build()
                            val set = carriers
                            // Survives a link renewal before it was flushed (as the same bytes: it is signed only once).
                            if (set == null || !set.fanout) unflushedSignals += OutboundControl.Discovery(reveal)
                            val cookie = OutboundControl.Discovery(reveal)
                            if (set == null) {
                                sinkFor(link).write(
                                    SecureRecordType.END_TO_END_CONTENT,
                                    endpoint.sealer.seal(EndToEndContentType.DISCOVERY, 0, reveal),
                                )
                            } else {
                                set.withCookie(cookie) {
                                    sinkFor(link).write(
                                        SecureRecordType.END_TO_END_CONTENT,
                                        endpoint.sealer.seal(EndToEndContentType.DISCOVERY, 0, reveal),
                                    )
                                }
                            }
                        }
                        is OutboundControl.Wifi -> carriers?.sendHop(control.carrier, SecureRecordType.BWF1, control.payload)
                        is OutboundControl.DisplayRotation -> EndToEndStreamProtocol.writePacket(
                            sinkFor(link),
                            endpoint.sealer,
                            StreamProtocol.FLAG_DISPLAY_ROTATION,
                            0L,
                            StreamProtocol.encodeDisplayRotation(control.degrees),
                        )
                        null -> {
                            // Experimental call-link pacing: wait for link credit before video, in
                            // slices so controls and heartbeats keep flowing; audio is never held.
                            // DEBUG test_call_inject_send_pause: all media (audio too) waits in the same slices while it holds.
                            val injectHoldNs = sendPause?.holdNs(System.nanoTime()) ?: 0L
                            val paceWaitNs = if (injectHoldNs > 0L) {
                                injectHoldNs
                            } else if (pacedWrites && media.none(OutboundMedia::isAudio)) {
                                pacer.waitNs()
                            } else {
                                0L
                            }
                            val packet = if (paceWaitNs > 0L) {
                                val sliceNs = minOf(paceWaitNs, CALL_PACING_SLICE_NS)
                                Thread.sleep(sliceNs / 1_000_000L, (sliceNs % 1_000_000L).toInt())
                                null
                            } else {
                                takeNextMedia()?.let(::coalesceQueuedAudio)
                            }
                            if (packet != null) {
                                // Media age spent in this queue (both arms; BENCHMARK media_age).
                                val waitedMs = (System.nanoTime() - packet.queuedAtNs).coerceAtLeast(0L) / 1_000_000L
                                if (packet.isAudio) {
                                    audioQueueWaitMax.record(waitedMs)
                                } else if (packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                                    videoQueueWaitMax.record(waitedMs)
                                }
                                if (!packet.isAudio && packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                                    debugStageTrace?.record("write_begin", packet.presentationTimeUs, packet.payload.size)
                                }
                                val writeStartedNs = if (debugPipelineTiming) System.nanoTime() else 0L
                                if (packet.debugQueuedAtNs > 0L) {
                                    (if (packet.isAudio) debugAudioQueueWait else debugVideoQueueWait)
                                        ?.recordNanos(writeStartedNs - packet.debugQueuedAtNs)
                                }
                                val pacedStartNs = System.nanoTime()
                                sendPause?.onMediaWritten(pacedStartNs)
                                val written = writeMediaRecord(link, endpoint, packet) {
                                    if (carriers?.fanout != true) {
                                        val flushNs = System.nanoTime()
                                        if (mediaFlushIntervalNs == 0L || flushNs - lastFlushNs >= mediaFlushIntervalNs) {
                                            link.writer.flush()
                                            unflushedSignals.clear()
                                            lastSendProgressNs = System.nanoTime()
                                            lastFlushNs = flushNs
                                        }
                                    }
                                }
                                // Charged in payload bytes; record headers add about 50 B, inside PACE_GAIN.
                                if (pacedWrites) pacer.onSent(written.toInt(), pacedStartNs)
                                sampleSentBytes += written
                                debugMediaWriteWait?.recordNanos(System.nanoTime() - writeStartedNs)
                                if (!packet.isAudio && packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                                    debugStageTrace?.record("write_end", packet.presentationTimeUs, packet.payload.size)
                                }
                                debugTxCounters?.let { counters ->
                                    when {
                                        packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0 -> counters.configWrites
                                        packet.isAudio -> counters.audioWrites
                                        else -> counters.videoWrites
                                    }.incrementAndGet()
                                }
                            }
                        }
                    }
                    val nowNs = System.nanoTime()
                    if (BuildConfig.DEBUG && nowNs - lastDebugTxReportNs >= DEBUG_TX_REPORT_INTERVAL_NS) {
                        reportDebugTx()
                        lastDebugTxReportNs = nowNs
                    }
                    reportFragmentStatsIfDue()
                    if (nowNs - sampleStartNs >= LINK_SAMPLE_NS) {
                        val thinnedNow = thinnedVideoFrames.get()
                        val shedNow = boundedShedFrames.get()
                        // Both delivery values from the carrier that sends (review A3 P3); no carrier (-1) reads carrier 0.
                        val sendState = stateOf((carriers?.sendCarrier ?: 0).coerceAtLeast(0))
                        val backlogAgeForPhy = media.peek()?.let { ((nowNs - it.queuedAtNs) / 1_000_000L).toInt() } ?: 0
                        onLinkSample(
                            LinkRateSample(
                                windowNs = nowNs - sampleStartNs,
                                offeredBytes = sampleOfferedBytes.getAndSet(0L),
                                sentBytes = sampleSentBytes,
                                backlogAgeMs = media.peek()?.let { ((nowNs - it.queuedAtNs) / 1_000_000L).toInt() } ?: 0,
                                largeFrameGrace = nowNs < largeFrameGraceUntilNs,
                                measuredLinkKbps = peerMeasuredKbps,
                                childLate = nowNs < peerLateUntilNs,
                                peer = link.peerNodeId.shortLabel(),
                                thinnedFrames = thinnedNow - sampleThinned,
                                shedFrames = shedNow - sampleShed,
                                deliveredKbps = sendState.peerDelivery.deliveredKbps(),
                                peerQueueMs = sendState.peerDelivery.queueMs(),
                                reverseLateMaxMs = mediaReceiver?.takeReverseLateMaxMs(),
                            ),
                        )
                        phyFirstGate?.let { gate ->
                            val congested = (backlogAgeForPhy >= LinkRateController.CONGESTED_BACKLOG_MS && nowNs >= largeFrameGraceUntilNs) ||
                                nowNs < peerLateUntilNs
                            gate.onLinkWindow(congested)?.let(::requestCallTwoM)
                            // Default (test_call_1m_first_link_limit off): the link limit keeps a first-1M call on 1M; logged once.
                            if (gate.takeLinkLimitHeld()) {
                                Log.i(
                                    "BluetoothVideo",
                                    CallOneMFirst.linkPhyLine(
                                        "1M", "1M", "link_limit_held", secureSession?.peerNodeId?.shortLabel() ?: "unknown",
                                        "budgetKbps=${CallOneMFirst.ONE_M_GATT_KBPS}",
                                    ),
                                )
                            }
                        }
                        sampleThinned = thinnedNow
                        sampleShed = shedNow
                        sampleSentBytes = 0L
                        sampleStartNs = nowNs
                    }
                    var heartbeatWritten = false
                    if (carriers?.fanout != true && nowNs - lastHeartbeatWrittenNs >= REPORT_INTERVAL_NS) {
                        // The heartbeat carries this phone's view of the media it receives on this
                        // link, so the peer's sender can adapt (hop-local; a relay forwards liveness only).
                        val arrivalKbps = link.reader.arrivalCapacityKbps() ?: 0
                        val report = mediaReceiver?.takeLinkReport(arrivalKbps)
                            ?: LinkCapacityReport(arrivalKbps, linkLate = false)
                        // Version 3 only to a direct peer that said it accepts it (an older endpoint
                        // closes the call on anything but version 2; a relay would drop it).
                        val delivery = deliveryReports.deliveryFor(state != null, relayHops) { receivedDelivery.snapshot() }
                        SecureLinkControlProtocol.writeCapacityReport(link.writer, report.copy(delivery = delivery))
                        lastHeartbeatWrittenNs = nowNs
                        heartbeatWritten = true
                    }
                    if (
                        carriers?.fanout != true &&
                        (
                            mediaFlushIntervalNs == 0L ||
                                control != null ||
                                heartbeatWritten ||
                                nowNs - lastFlushNs >= mediaFlushIntervalNs
                            )
                    ) {
                        val flushStartedNs = if (debugPipelineTiming) System.nanoTime() else 0L
                        link.writer.flush()
                        unflushedSignals.clear()
                        lastSendProgressNs = System.nanoTime()
                        debugMediaFlushWait?.recordNanos(System.nanoTime() - flushStartedNs)
                        lastFlushNs = nowNs
                    }
                    if (closeAfter) {
                        val closeSet = carriers
                        if (closeSet != null && closeSet.fanout) closeSet.awaitDrained(closeSet.sendCarrier, 1_000)
                        val closeWait = privateCallCloseWait(transport, DebugTunables.callCloseWaitsForPeer, carriers?.sendKind ?: CarrierKind.BLUETOOTH)
                        val outcome = awaitPrivateCallClose(readerThread, closeWait)
                        if (closeWait.maxMs > 0L) {
                            Log.i(
                                "BluetoothVideo",
                                "BENCHMARK call close_drain: waitForPeer=${closeWait.waitForPeer} | " +
                                    "peerClosed=${outcome.peerClosed} | waitedMs=${outcome.waitedMs} | " +
                                    "capMs=${closeWait.maxMs}",
                            )
                        }
                        finish("Call ended")
                        return@thread
                    }
                    // Experimental link renewal implies Stage A on a call that may renew; while a renewal runs, its own
                    // bound replaces the liveness close (Design SR revision 2, rules 5 and 7). In fan-out the producer
                    // serves no carrier: the set's monitor judges liveness (step 9).
                    if (carriers?.fanout != true) {
                        val stall = CallStallTolerance.verdict(stallTolerance || renewalPossible(), nowNs, lastHeartbeatNs, lastSendProgressNs)
                        noteStall(stall, nowNs)
                        phyFirstGate?.let { gate ->
                            val silentMs = (nowNs - lastHeartbeatNs).coerceAtLeast(0L) / 1_000_000L
                            if (gate.onSilence(SystemClock.elapsedRealtime(), silentMs)) fallBackCallToOneM(silentMs)
                        }
                        maybeStartRenewal(stall, nowNs)
                        if (stall == CallStallTolerance.Verdict.END && !renewalOwnsLiveness()) {
                            hangUpBeforeLivenessClose(link, endpoint)
                            throw IOException("Call peer stopped responding")
                        }
                    }
                }
            } catch (interrupted: InterruptedException) {
                if (gen != linkGeneration.get()) requeueSignals(unflushedSignals)
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                // Amendment 11: in fan-out the producer serves no carrier; its exception is a local failure that ends the call.
                if (carriers?.fanout == true) {
                    if (callRunning.get()) onCallLinkError(error, "Call send failed: ${error.message ?: error.javaClass.simpleName}")
                    return@thread
                }
                // Experimental link renewal: a write failing on a link that is being replaced ends only this thread.
                if (renewalEndsOldLinkQuietly(gen)) {
                    requeueSignals(unflushedSignals)
                    noteLinkEndedQuietly(gen, "writer")
                    return@thread
                }
                // A send failing on a link the peer is already tearing down after its HANG_UP (phase ENDED,
                // finish still pending on the reader) is that hang-up, not a call failure (mvp-smoke 0006). When this
                // phone ended the call itself, the failed HANG_UP/CANCEL/DECLINE finishes it (review A1).
                carrierLost(0, gen, error, "Call send failed: ${error.message ?: error.javaClass.simpleName}", "write failed")
            }
        }
    }

    /**
     * The reader's per-record work for carrier [carrierId] (today only carrier 0), under recordLock.
     * Returns false when the record belongs to a replaced link generation (the reader ends).
     */
    @Suppress("UNUSED_PARAMETER")
    private fun dispatchRecord(
        record: SecureRecord,
        endpoint: EndToEndVideoSession,
        initiator: Boolean,
        gen: Int,
        carrierId: Int,
    ): Boolean {
        // Opened under the record lock with the generation check: a record that arrives on a replaced link
        // is dropped unopened, and none is opened after an accepted BIND moved the generation.
        synchronized(recordLock) {
            if (gen != linkGeneration.get()) return false
            if (videoReassembler.enterLinkGeneration(gen)) {
                Log.i("BluetoothVideo", "BENCHMARK call_video_fragments: renewal_dropped_partial_frame | gen=$gen")
            }
            lastHeartbeatNs = System.nanoTime()
            noteRenewedLinkHeard()
            when (record.type) {
                SecureRecordType.LINK_HEARTBEAT -> {
                    noteRenewalPeerHeard()
                    val report = SecureLinkControlProtocol.requireHeartbeat(record)
                    if (report == null) notePeerHeartbeat(0, null) else onPeerReport(0, report)
                }
                SecureRecordType.END_TO_END_CONTENT -> {
                    val content = endpoint.opener.open(record.payload)
                    if (content.type != EndToEndContentType.RENEW) noteRenewalPeerHeard()
                    handleContent(content, initiator)
                }
                else -> error("Unexpected private-call record ${record.type}")
            }
        }
        return true
    }

    private fun startReader(
        link: SecureBluetoothSession,
        endpoint: EndToEndVideoSession,
        initiator: Boolean,
        gen: Int = linkGeneration.get(),
    ) {
        readerThread = thread(name = "PrivateCallReader") {
            try {
                while (callRunning.get() && gen == linkGeneration.get()) {
                    val record = link.reader.read()
                    val set = carriers
                    if (set == null) {
                        if (!dispatchRecord(record, endpoint, initiator, gen, 0)) return@thread
                    } else {
                        set.onInbound(0, gen, record)
                    }
                }
            } catch (error: LocalMediaPipelineException) {
                if (callRunning.get() && state?.phase != PrivateCallPhase.ENDED) endForLocalMediaFailure(error)
            } catch (error: Exception) {
                // Experimental link renewal: the old link failing while the renewal owns it ends only this thread.
                if (renewalEndsOldLinkQuietly(gen)) {
                    noteLinkEndedQuietly(gen, "reader")
                    return@thread
                }
                carrierLost(0, gen, error, "Call link lost: ${error.message ?: error.javaClass.simpleName}", "read failed", fromReader = true)
            }
        }
    }

    /**
     * This phone's decoder/playback failed while the link was healthy: hang up cleanly (the peer
     * sees an error hang-up, not a lost link) and report the local cause, instead of treating it
     * as link loss.
     */
    private fun endForLocalMediaFailure(error: LocalMediaPipelineException) {
        val message = privateCallLocalMediaFailureMessage(error)
        Log.e("BluetoothVideo", "Private call ending on a local media failure (not link loss): stage=${error.stage}", error)
        localFailureMessage = message
        onStatus(message)
        mediaReceiver?.close()
        val phase = state?.phase
        val hungUp = (phase == PrivateCallPhase.NEGOTIATING_MEDIA || phase == PrivateCallPhase.ACTIVE) &&
            runCatching {
                transition(PrivateCallEvent.LOCAL_HANG_UP)
                enqueueSignal(PrivateCallSignal.HANG_UP, PrivateCallReason.ERROR, closeAfter = true)
            }.isSuccess
        if (!hungUp) fail(message)
    }

    /** Responder side of a BVMID2 session: scope, completion event and the 60 s limit (PROTOCOL "Scope of the session"). */
    private fun beginQrVerifySession(endpoint: EndToEndVideoSession) {
        val scope = QrSessionScope(SystemClock.elapsedRealtime())
        qrScope = scope
        qrClosing.set(false)
        qrScanner = false
        qrLeaseStarted = false
        qrDisplayerMayAnswer = false
        endedQuietly = true // a verification session is not a call: its end opens no screen
        val gate = qrGate
        val committed = gate?.complete(endpoint.peerFingerprint) == true
        qrCommitted = committed
        Bmh3Android.log("bmh3: event=qr_session | role=displayer | committed=$committed")
        // The 60 s limit holds from here; the earlier close (2 s grace) is decided in [armQrDisplayer].
        armQrSessionLimit(scope)
    }

    /**
     * Design QL, displayer: after the commit, take part in the presence exchange only when the scanner's authenticated
     * fingerprint is already eligible (this phone verified that person before). Otherwise end after the grace, as before.
     */
    private fun armQrDisplayer(scope: QrSessionScope, endpoint: EndToEndVideoSession, linkPeer: NodeId) {
        // Review R1/R4/R9: the scanner offers first; the displayer answers only a scanner its own user confirmed in this
        // visit, on a direct link (link peer = endpoint peer) with its real identity. Nothing is sent at the commit.
        val mayAnswer = qrCommitted && presenceSession != null && !endpoint.peerIsPseudonym &&
            linkPeer == endpoint.peerNodeId && QrMeet.visit.mayLease(endpoint.peerFingerprint)
        qrDisplayerMayAnswer = mayAnswer
        Bmh3Android.log("bmh3: event=qr_lease | role=displayer | mayAnswer=$mayAnswer")
        if (mayAnswer) armQrConfirmWait(scope) else qrCloseAfterGrace(scope)
    }

    /** Review R5: without the scanner's yes (scanner) or its OFFER (displayer) in time, end without a lease. */
    private fun armQrConfirmWait(scope: QrSessionScope) {
        thread(name = "QrConfirmWait", isDaemon = true) {
            Thread.sleep(QrSessionScope.CONFIRM_WAIT_MS)
            if (qrScope === scope && !qrLeaseStarted) finish("Verification session finished")
        }
    }

    /** The displayer's answer to the scanner's OFFER: start the exchange ([PresenceSession.start] sends OFFER and LEASE). */
    private fun qrDisplayerAnswer() {
        if (qrScanner || !qrDisplayerMayAnswer || qrLeaseStarted) return
        qrLeaseStarted = true
        startPresenceSessionAsync()
    }

    /** Both roles: the per-visit consent is re-checked at send time; nothing leaves after a reuse alert or leaving the screen. */
    private fun qrLeaseAllowed(session: PresenceSession, peer: IdentityFingerprint): Boolean =
        QrLeaseRule.allowed(verificationSession = session.verification, visit = QrMeet.visit, peer = peer)

    /**
     * Design QL, scanner: the user answered "Is this X's screen you scanned?" for [peer]. Yes (the shield is stored): start
     * the presence exchange; its send-time checks decide again. No: end the session at once.
     */
    fun qrScanConfirmed(peer: IdentityFingerprint, yes: Boolean) {
        val scope = qrScope ?: return
        if (qrScanPayload == null || contentSession?.peerFingerprint != peer) return
        Bmh3Android.log("bmh3: event=qr_lease | role=scanner | confirmed=$yes | negotiated=${presenceSession != null}")
        when {
            !yes -> thread(name = "QrScanDeclined", isDaemon = true) {
                if (qrScope === scope) finish("Verification scan declined")
            }
            presenceSession == null -> qrCloseAfterGrace(scope)
            qrLeaseStarted -> Unit
            else -> {
                qrLeaseStarted = true
                startPresenceSessionAsync()
            }
        }
    }

    private fun armQrSessionLimit(scope: QrSessionScope) {
        thread(name = "QrVerifyLimit", isDaemon = true) {
            Thread.sleep(QrSessionScope.MAX_SESSION_MS)
            if (qrScope === scope) finish("Verification session finished")
        }
    }

    private fun qrCloseAfterGrace(scope: QrSessionScope) {
        if (!qrClosing.compareAndSet(false, true)) return
        thread(name = "QrVerifyClose", isDaemon = true) {
            Thread.sleep(QrSessionScope.CLOSE_GRACE_MS)
            if (qrScope === scope) finish("Verification session finished")
        }
    }

    /** After each presence action in a verification session: once nothing more can move, end it after the grace. */
    private fun qrCheckSettled() {
        val scope = qrScope ?: return
        if (presenceSession?.settled() != false) qrCloseAfterGrace(scope)
    }

    private fun handleContent(content: EndToEndContentRecord, initiator: Boolean) {
        qrScope?.let { scope ->
            // A verification session accepts the scanner's name label and the presence OFFER/LEASE once each (Design QL),
            // nothing else (no Invite, OPENING, reveal, media, ...).
            val refused = scope.expired(SystemClock.elapsedRealtime()) || if (content.type == EndToEndContentType.DISCOVERY) {
                scope.refuseDiscovery(content.payload, discoveryNegotiated && (qrScanner || qrCommitted))
            } else {
                scope.refuseContent(content.type)
            }
            if (refused) {
                val role = if (qrScanner) "scanner" else "displayer"
                Bmh3Android.log("bmh3: event=qr_session | role=$role | violation=content")
                error("Verification session scope")
            }
        }
        awaitedOpening?.let { wait ->
            // The callee's first end-to-end record must be its OPENING; anything else, or a mismatch, ends the call.
            awaitedOpening = null
            verifyAwaitedOpening(wait, content)
            markRelayRouteVerified()
            // A relayed real-key callee's name moment: checked again against the block list and the newest audience.
            callerNames.onOpeningVerified(PeerLabelFeature.audience, contentSession?.let { peerIsVerifiedContact(it) } == true)
            return
        }
        // Review 2026-10-19 (P1): nothing from the peer reaches the speaker or the screen before the call is answered.
        // Review 2026-10-27 (finding 1): nor from a pseudonymous peer before its reveal verified (CallerRevealGate).
        if (content.type in CallMediaGate.MEDIA_TYPES) {
            // A revealed blocked person's media never plays while the hang-up goes out (finding 2).
            if (blockedCall.active) return
            when (CallMediaGate.decide(state?.phase, contentSession?.reveal?.peerRevealRequired() == true)) {
                CallMediaGate.Decision.ALLOW -> Unit
                CallMediaGate.Decision.DROP -> return
                CallMediaGate.Decision.REFUSE -> {
                    Log.w("BluetoothVideo", "SECURITY CALL MEDIA REFUSED phase=${state?.phase} | type=${content.type}")
                    error("Peer sent call media before the call was answered")
                }
            }
        }
        when (content.type) {
            EndToEndContentType.PRIVATE_CALL_SIGNAL ->
                handleSignal(PrivateCallSignalCodec.decode(content.payload), initiator)
            EndToEndContentType.DISCOVERY -> handleDiscoveryContent(content.payload)
            EndToEndContentType.LABELS -> {
                val current = state
                if (current == null) {
                    // R3: hold one record until the Invite names the call ID it must be bound to.
                    if (bufferedLabels == null) bufferedLabels = content.payload
                } else {
                    applyCallLabels(content.payload, current.callId)
                }
            }
            EndToEndContentType.STREAM_HEADER -> {
                videoReassembler.requireIdleFor("A call stream header")
                debugStageTrace?.close()
                debugStageTrace = DebugStageTrace.startIfRequested(debugTraceSessionName, "call_transport")
                val header = EndToEndStreamProtocol.decodeHeader(content.payload)
                require(advertisedDecodeMask and codecCapabilityMask(header.codecId) != 0) {
                    "This phone cannot decode the peer's call codec"
                }
                activePlaybackDelayProfile = currentCallPlaybackProfile()
                require(contentSession?.peerCapabilities?.encodeCodecMask
                    ?.and(codecCapabilityMask(header.codecId)) != 0) {
                    "Peer did not authenticate use of its call codec"
                }
                if (activePlaybackDelayProfile != playbackDelayProfile) {
                    onStatus("Private-call compatibility using High stability playback for this Bluetooth route")
                }
                val receiverProfile = privateCallReceiverPlaybackProfile(
                    activePlaybackDelayProfile,
                    localCallType,
                    DebugTunables.voiceCallLowDelay,
                )
                if (receiverProfile != activePlaybackDelayProfile) {
                    Log.i("BluetoothVideo", "BENCHMARK call_playout_profile: profile=${receiverProfile.name} | " +
                        "route=${activePlaybackDelayProfile.name} | callType=${localCallType.label} | experimental=true")
                }
                mediaReceiver = PrivateCallMediaReceiver(
                    remoteSurfaceProvider,
                    receiverProfile,
                    transport,
                    onRemoteVideoFormat,
                    onRemoteCameraEnabled,
                    { controls.offerKeyed("key-frame-request", OutboundControl.KeyFrameRequest) },
                    onStatus,
                    DebugMediaTrace.startIfRequested(
                        appContext, debugTraceSessionName, "receiver", header.codecId,
                        header.width, header.height, header.fps,
                    ),
                    if (BuildConfig.DEBUG) state?.callId?.toString() else null,
                    debugTraceContext = appContext,
                    debugTraceSession = debugTraceSessionName,
                    videoGrowthLimitMs = CallPlayoutPolicy.videoGrowthLimitMs(headOfLine.speechLed(peerAcceptsVideoFragments)),
                ).also { it.setHeader(header) }
                if (state?.phase == PrivateCallPhase.NEGOTIATING_MEDIA) {
                    transition(PrivateCallEvent.MEDIA_READY)
                }
            }
            EndToEndContentType.MEDIA_PACKET -> {
                val receiver = requireNotNull(mediaReceiver) { "Peer sent media before its call header" }
                val packet = EndToEndStreamProtocol.decodePacket(content)
                // The same bytes the peer counts as written (it writes rotation outside its media queue).
                if (packet.flags and StreamProtocol.FLAG_DISPLAY_ROTATION == 0) stateOf(arrivalCarrier).receivedDelivery.add(packet.payload.size)
                if (!packet.isAudio) videoReassembler.requireIdleFor("A whole call video packet")
                deliverMedia(receiver, packet)
            }
            EndToEndContentType.VIDEO_FRAGMENT -> {
                // Experimental call video fragments: accepted only after this endpoint advertised them.
                require(videoFragmentsOn) { "Peer sent call video fragments this endpoint did not advertise" }
                val receiver = requireNotNull(mediaReceiver) { "Peer sent media before its call header" }
                // Counted per fragment on arrival (not at reassembly), exactly as the peer wrote it.
                stateOf(arrivalCarrier).receivedDelivery.add(content.payload.size - CallVideoFragments.HEADER_BYTES)
                val complete = videoReassembler.accept(content.flags, content.payload) ?: return
                deliverMedia(receiver, EndToEndStreamProtocol.decodeReassembledPacket(content.flags, complete))
            }
            EndToEndContentType.KEY_FRAME_REQUEST -> {
                require(content.payload.isEmpty()) { "Invalid call keyframe request" }
                onLocalKeyFrameNeeded()
            }
            EndToEndContentType.RENEW -> {
                require(renewalOn) { "Peer sent a link renewal record this endpoint did not advertise" }
                onRenewRecord(CallRenewalWire.decode(content.payload))
            }
            else -> error("Unexpected private-call content ${content.type}")
        }
    }

    private fun handleSignal(message: PrivateCallSignalMessage, initiator: Boolean) {
        val current = state
        if (current == null) {
            require(!initiator && message.signal == PrivateCallSignal.INVITE) {
                "Expected a private-call invitation"
            }
            // Design PP package A: a valid INVITE ends the pre-INVITE timer; false means the deadline closed this link at the
            // same instant, so exactly one of the two happens and a closed link never rings.
            incomingPhases?.let { phases ->
                if (!phases.inviteArrived()) return
                logIncomingMilestone(phases, IncomingMilestone.FIRST_INVITE)
            }
            val incoming = PrivateCallState.incoming(message.callId)
            negotiation = VoiceCallNegotiation.incoming(voiceCallsEnabled)
            relayHops = message.relayHops
            activeSettings = privateCallVideoSettingsForPeer(
                routeSettings(relayHops),
                transport,
                peerMaxBitrateKbps,
            )
            // Rotating IDs: the OPENING is the callee's first record, ahead of any label or answer.
            openingToSend?.let { entries ->
                openingToSend = null
                controls.offerMust(OutboundControl.Discovery(DiscoveryExchange.encode(DiscoveryExchange.Opening(entries))))
            }
            state = incoming
            // Relayed means the authenticated link peer is not the authenticated call peer; the Invite's own relay count is
            // the caller's claim and can only make the call count as relayed.
            val viaRelay = relayHops > 0 || (contentSession?.peerNodeId != null && contentSession?.peerNodeId != secureSession?.peerNodeId)
            val relayDecision = RelayIdentityPolicy.incoming(
                viaRelay,
                relayedCallsOn,
                RelayedCallsFeature.consentAllowed(rotatingIds),
                callerPseudonymous = contentSession?.peerIsPseudonym == true,
                strangerCallsOn = DebugTunables.relayedStrangerCalls,
            )
            if (relayDecision != RelayIdentityPolicy.Incoming.RING) {
                Log.i(
                    "BluetoothVideo",
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_call: declined | reason=${relayDecision.name.lowercase()}",
                )
                endedQuietly = true
                runCatching { decline(PrivateCallReason.REJECTED) }
                return
            }
            // Design OR (DEBUG prototype): an advert's consent bit is unauthenticated, so a relayed call is declined before it
            // rings unless this phone's relayed calls and its current "Let phones nearby relay calls to me" are on.
            if (RelaySeekFeature.active() && contentSession?.peerNodeId != null &&
                contentSession?.peerNodeId != secureSession?.peerNodeId && relayHelper?.consentOn() != true
            ) {
                Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_call: consent_declined")
                endedQuietly = true
                runCatching { decline(PrivateCallReason.REJECTED) }
                return
            }
            // Call blocking: a blocked person, or any unknown caller while that setting is on, never rings. The
            // caller sees the normal Ringing and then the normal Decline (after a human-like delay); this phone
            // shows nothing, posts nothing and does not wake the screen.
            declineSilentlyIfBlocked(incoming.callId)?.let { return }
            // F14: the user is dialling someone else: this call is declined as busy, the dial goes on (DialCollision).
            declineBusyIfDialling()?.let { return }
            onPhase(incoming)
            CallJoinStages.current.callId(if (BuildConfig.DEBUG) message.callId.toString() else null)
            CallJoinStages.mark(CallJoinStage.RINGING)
            bufferedLabels?.let { applyCallLabels(it, message.callId) }
            bufferedLabels = null
            if (peerAcceptsLabels) prepareCalleeLabels(message.callId)
            enqueueSignal(PrivateCallSignal.RINGING)
            // A stranger whose call rings was not a probe (RV8 tarpit counts only silent handshakes).
            if (rotatingIds) runCatching { RotatingDiscovery.tarpit.forgive(RotatingDiscovery.currentEpoch()) }
            onIncoming(contentSession?.peerNodeId?.shortLabel() ?: "Nearby device")
            if (contentSession?.peerIsPseudonym == true) onStatus(RelayIdentityPolicy.strangerRingStatus(viaRelay))
            // Every relayed ring is capped at 60 s, whatever this phone's own experiment state (review CP-1 item 8).
            if (viaRelay) scheduleRelayedRingingCap(incoming.callId)
            ringingSinceMs = SystemClock.elapsedRealtime()
            scheduleRingTimeout(incoming.callId, outgoing = false)
            return
        }
        require(message.callId == current.callId) { "Private-call ID changed during the call" }
        when (message.signal) {
            PrivateCallSignal.RINGING -> {
                transition(PrivateCallEvent.REMOTE_RINGING)
                CallJoinStages.mark(CallJoinStage.RINGING)
                ringingSinceMs = SystemClock.elapsedRealtime()
                scheduleRingTimeout(current.callId, outgoing = true)
            }
            PrivateCallSignal.ACCEPT -> {
                // A callee revealed as someone this phone blocked: the call ends shortly with an ordinary hang-up and
                // nothing of this phone's (no camera, no microphone) goes to them meanwhile.
                if (blockedCall.active) {
                    transition(PrivateCallEvent.REMOTE_ACCEPT)
                    return
                }
                // A pseudonymous callee's reveal comes before its Accept in the same ordered queue; an Accept without
                // a verified reveal is a callee that could not prove it owns the offer.
                require(awaitedReveal == null) { "Call peer identity changed between discovery and connection" }
                transition(PrivateCallEvent.REMOTE_ACCEPT)
                CallJoinStages.mark(CallJoinStage.ACCEPT, "by=peer")
                startLocalMedia()
                startPresenceSessionAsync()
            }
            PrivateCallSignal.DECLINE -> {
                transition(PrivateCallEvent.REMOTE_DECLINE)
                // F14: BUSY (wire value 2) is the callee dialling someone else or already in a call.
                finish(if (message.reason == PrivateCallReason.BUSY) BUSY_DECLINE_MESSAGE else "Call declined")
            }
            PrivateCallSignal.CANCEL -> {
                transition(PrivateCallEvent.REMOTE_CANCEL)
                val rangMs = ringingSinceMs?.let { SystemClock.elapsedRealtime() - it }
                finish(CallRingTimeout.cancelledMessage(DebugTunables.callRingTimeout, rangMs))
            }
            PrivateCallSignal.HANG_UP -> {
                transition(PrivateCallEvent.REMOTE_HANG_UP)
                finish(
                    if (message.reason == PrivateCallReason.ERROR) {
                        // F17: a peer that stopped hearing us also hangs up with ERROR; our own stalled sends tell which.
                        LivenessHangUp.errorHangUpMessage(System.nanoTime(), lastSendProgressNs)
                    } else {
                        "Peer ended the call"
                    },
                )
            }
            PrivateCallSignal.CAMERA_OFF -> {
                wifiHop?.onVideo(remote = false)
                noteRemoteCameraSignal(current.phase, enabled = false)
                mediaReceiver?.setRemoteCameraEnabled(false) ?: onRemoteCameraEnabled(false)
            }
            PrivateCallSignal.CAMERA_ON -> {
                wifiHop?.onVideo(remote = true)
                noteRemoteCameraSignal(current.phase, enabled = true)
                mediaReceiver?.setRemoteCameraEnabled(true) ?: onRemoteCameraEnabled(true)
            }
            PrivateCallSignal.MICROPHONE_MUTED -> onRemoteMuted(true)
            PrivateCallSignal.MICROPHONE_UNMUTED -> onRemoteMuted(false)
            PrivateCallSignal.INVITE -> error("Duplicate private-call invitation")
        }
    }

    /**
     * Experimental peer labels: verifies one container against this call's ID and the authenticated
     * endpoint peer, then publishes the peer's self-chosen name for this call only. A call subject (kind 3, retired
     * 2026-11-05) from an older build is dropped by [LabelInbox] and never shown.
     */
    private fun applyCallLabels(payload: ByteArray, callId: java.util.UUID) {
        val endpoint = contentSession ?: return
        val inbox = callLabels ?: LabelInbox().also { callLabels = it }
        inbox.accept(payload, endpoint.peerNodeId, LabelBinding.call(callId), "call")
        // Keyed by the identity the screens use (after a reveal the real one), never by a throwaway that is gone at the reveal.
        val prefix = endpoint.effectivePeerNodeId.shortLabel()
        val name = inbox.text(LabelKind.PERSON_NAME)
        SessionPeerLabels.update(prefix) { it.copy(selfName = name) }
        // Naming v2: a verified contact's last claimed name is kept (sealed, local) for its detail and the one change note.
        if (name != null && peerIsVerifiedContact(endpoint)) {
            val fingerprint = endpoint.effectivePeerFingerprint
            thread(name = "ContactClaimRecord", isDaemon = true) {
                runCatching { ContactClaims.store(appContext).recordClaim(fingerprint, name, System.currentTimeMillis()) }
            }
        }
        Log.i("BluetoothVideo", "BENCHMARK peer_labels call_rx: name=${name != null}")
        onPeerLabels(prefix)
    }

    /**
     * Signs this callee's name once at Invite time (off the UI thread). R1: it is sent at Ringing only
     * to a verified contact (full fingerprint), otherwise only with Accept.
     */
    private fun prepareCalleeLabels(callId: java.util.UUID) {
        val endpoint = contentSession ?: return
        val label = LocalLabels.signed(endpointSigner, LabelKind.PERSON_NAME, LabelBinding.call(callId), PeerLabelFeature.localName)
        if (label == null) {
            logLabelDecision("callee", LabelSendMoment.NEVER)
            return
        }
        val container = SignedLabelCodec.encodeContainer(listOf(label))
        // Naming v2: one decision, [CallLabelPolicy.calleeMoment] (audience, verified contact, stranger session).
        val moment = CallLabelPolicy.calleeMoment(
            CalleeLabelContext(
                labelsNegotiated = peerAcceptsLabels,
                hasName = true,
                audience = PeerLabelFeature.audience,
                ownPseudonymous = endpoint.reveal?.ownPseudonymous == true,
                callerIsVerifiedContact = peerIsVerifiedContact(endpoint),
                callerPseudonymous = endpoint.reveal?.peerPseudonymous == true,
                holdUntilReveal = DebugTunables.calleeNameAfterReveal,
            ),
        )
        logLabelDecision("callee", moment)
        when (moment) {
            LabelSendMoment.AT_RINGING -> controls.offerMust(OutboundControl.Labels(container))
            LabelSendMoment.AT_ACCEPT -> pendingAcceptLabels = container
            LabelSendMoment.AFTER_REVEAL, LabelSendMoment.AFTER_REVEAL_IF_VERIFIED -> calleeNames.hold(moment, container)
            else -> Unit
        }
    }

    /**
     * DEBUG only: the [CallLabelPolicy] decision this side applied, so the name-before-answer rule can be checked on phones.
     * No behaviour change.
     */
    private fun logLabelDecision(role: String, name: LabelSendMoment) {
        if (!BuildConfig.DEBUG) return
        Log.i("BluetoothVideo", "BENCHMARK call_label_decision: role=$role | name=${name.name.lowercase()}")
    }

    /** Experimental voice calls: Camera off while ringing is the caller's voice offer. */
    private fun noteRemoteCameraSignal(phase: PrivateCallPhase, enabled: Boolean) {
        val active = negotiation ?: return
        if (!active.onRemoteCameraSignal(phase, enabled)) return
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK call_type offer: callId=${state?.callId} | " +
                "offered=${if (active.peerOfferedVoice) "voice" else "video"}",
        )
        onIncomingCallType(if (active.peerOfferedVoice) CallType.VOICE else CallType.VIDEO)
    }

    /**
     * Experimental call pre-warm ([CallPrewarm]): the video profile [startLocalMedia] would give the camera now (its
     * supported-mode and encoder-fit steps, without their logs or any state change); null for a voice call. Media start
     * still computes its own profile; a pre-warmed encoder is used only when the two match exactly.
     */
    fun prewarmVideoProfile(): Pair<VideoSettings, VideoCodec>? {
        if (localCallType != CallType.VIDEO) return null
        val codec = activeCodec
        var settings = activeSettings
        if (codec.usesSurfaceInput) settings = privateCallCameraVideoSettings(settings, supportedVideoModes(codec, settings))
        return fitCallVideo(settings, codec) to codec
    }

    private fun startLocalMedia() {
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK call identity: callId=${state?.callId}")
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK call_type: callId=${state?.callId} | role=${state?.role?.name?.lowercase()} | " +
                "requested=${negotiation?.requested?.label ?: "video"} | local=${localCallType.label} | " +
                "peerOfferedVoice=${negotiation?.peerOfferedVoice == true} | voiceCallsEnabled=$voiceCallsEnabled",
        )
        if (localCallType == CallType.VIDEO) wifiHop?.onVideo(local = true)
        if (activeCodec.usesSurfaceInput) {
            val compatible = privateCallCameraVideoSettings(
                activeSettings,
                supportedVideoModes(activeCodec, activeSettings),
            )
            if (compatible != activeSettings) {
                onStatus("Call camera selected supported ${compatible.resolution.width}x${compatible.resolution.height}/${compatible.fps} mode")
                activeSettings = compatible
            }
        }
        if (localCallType == CallType.VIDEO) {
            val fitted = fitCallVideo(activeSettings, activeCodec)
            if (fitted != activeSettings) {
                if (BuildConfig.DEBUG) Log.i(
                    "BluetoothVideo",
                    "BENCHMARK call_encoder_fit: requested=${activeSettings.resolution.width}x${activeSettings.resolution.height} | " +
                        "effective=${fitted.resolution.width}x${fitted.resolution.height} | fps=${fitted.fps} | " +
                        "encoder=${activeCodec.encoderName}",
                )
                onStatus(
                    "Call video ${fitted.resolution.width}x${fitted.resolution.height} instead of " +
                        "${activeSettings.resolution.width}x${activeSettings.resolution.height}: this phone's encoder " +
                        "could not keep ${fitted.fps} FPS at that size in an earlier call",
                )
                activeSettings = fitted
            }
        }
        controls.offerMust(OutboundControl.Header)
        activePlaybackDelayProfile = currentCallPlaybackProfile()
        phyFirstGate?.onMediaPlanned(
            if (localCallType == CallType.VIDEO) CallOneMFirst.plannedKbps(activeSettings.bitRate, activeSettings.fps) else 0.0,
        )
        onStartLocalMedia(
            activeSettings,
            privateCallAudioBitRate(transport, stackFingerprint, peerMaxBitrateKbps),
            activeCodec,
            activePlaybackDelayProfile,
        )
    }

    @Synchronized
    private fun transition(event: PrivateCallEvent) {
        val next = requireNotNull(state).transition(event)
        state = next
        if (next.phase == PrivateCallPhase.ACTIVE && activeAtNs == 0L) {
            activeAtNs = System.nanoTime()
            CallJoinStages.mark(CallJoinStage.ACTIVE)
            carriers?.onCallActive()
        }
        if (next.phase == PrivateCallPhase.ACTIVE) {
            wifiHop?.onCallActive()
            wifiHop?.onVideo(remote = negotiation?.let { !it.peerOfferedVoice } ?: (localCallType == CallType.VIDEO))
        }
        notifyPhase(next)
    }

    private fun enqueueSignal(
        signal: PrivateCallSignal,
        reason: PrivateCallReason = PrivateCallReason.NONE,
        closeAfter: Boolean = false,
    ) {
        val callId = requireNotNull(state).callId
        if (signal == PrivateCallSignal.CAMERA_ON || signal == PrivateCallSignal.CAMERA_OFF) lastCameraSignal = signal
        // Hang-up, decline, cancel and every other signal must reach the peer: never refused.
        controls.offerMust(
            OutboundControl.Signal(
                PrivateCallSignalMessage(
                    callId,
                    signal,
                    reason,
                    relayHops = if (signal == PrivateCallSignal.INVITE) relayHops else 0,
                ),
                closeAfter,
            ),
        )
    }

    /** Stage A: one `call_stall` line and one UI change per transition between quiet and normal. */
    private fun noteStall(verdict: CallStallTolerance.Verdict, nowNs: Long) {
        val poor = verdict == CallStallTolerance.Verdict.POOR
        if (poor == stallShown) return
        stallShown = poor
        val phase = when {
            poor -> "soft"
            verdict == CallStallTolerance.Verdict.END -> "ended"
            else -> "resumed"
        }
        Log.i(
            "BluetoothVideo",
            CallStallTolerance.line(
                phase,
                (nowNs - lastHeartbeatNs).coerceAtLeast(0L) / 1_000_000L,
                (nowNs - lastSendProgressNs).coerceAtLeast(0L) / 1_000_000L,
                state?.callId?.toString(),
            ),
        )
        runCatching { onLinkStall(poor) }
    }

    /**
     * F17: before closing a call whose peer went quiet, tell the peer (it may still hear us, as the S24 did) instead of
     * leaving it to its own 8 s timer. Best effort from a helper thread, bounded by [LivenessHangUp.WAIT_MS]; the call
     * then ends as link lost exactly as before. A write still running after the wait fails once the link is closed.
     */
    private fun hangUpBeforeLivenessClose(link: SecureBluetoothSession, endpoint: EndToEndVideoSession) {
        // Experimental link renewal: an armed phone sends no liveness HANG_UP while its renewal window runs.
        if (renewalArmed.suspendsLiveness()) return
        val current = state ?: return
        if (!LivenessHangUp.applies(DebugTunables.callLivenessHangUp, current.phase)) return
        val message = runCatching {
            PrivateCallSignalMessage(current.callId, PrivateCallSignal.HANG_UP, PrivateCallReason.ERROR)
        }.getOrNull() ?: return
        val written = java.util.concurrent.atomic.AtomicBoolean(false)
        val startedNs = System.nanoTime()
        val helper = thread(name = "PrivateCallLivenessHangUp") {
            runCatching {
                writeCallSignal(link, endpoint, message)
                val set = carriers
                if (set != null && set.fanout) set.awaitDrained(set.sendCarrier, LivenessHangUp.WAIT_MS) else link.writer.flush()
                written.set(true)
            }
        }
        runCatching { helper.join(LivenessHangUp.WAIT_MS) }
        Log.i(
            "BluetoothVideo",
            "BENCHMARK call_liveness_hang_up: written=${written.get()} | waitedMs=${(System.nanoTime() - startedNs) / 1_000_000L} | " +
                "capMs=${LivenessHangUp.WAIT_MS}",
        )
    }

    private fun writeCallSignal(
        link: SecureBluetoothSession,
        endpoint: EndToEndVideoSession,
        message: PrivateCallSignalMessage,
    ) {
        sinkFor(link).write(
            SecureRecordType.END_TO_END_CONTENT,
            endpoint.sealer.seal(
                EndToEndContentType.PRIVATE_CALL_SIGNAL,
                0,
                PrivateCallSignalCodec.encode(message),
            ),
        )
    }

    /** Call playout profile for the current route; relay hops are known once the invitation arrived. */
    private fun currentCallPlaybackProfile(): PlaybackDelayProfile = privateCallPlaybackDelayProfile(
        playbackDelayProfile,
        transport,
        stackFingerprint,
        peerMaxBitrateKbps,
        privateCallLegacyLowDelay(legacyLowDelay, relayHops),
    )

    private fun deliverMedia(receiver: PrivateCallMediaReceiver, packet: StreamProtocol.Packet) {
        if (!packet.isAudio && packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
            debugStageTrace?.record("receive", packet.presentationTimeUs, packet.payload.size)
        }
        if (packet.flags and StreamProtocol.FLAG_AUDIO_PAIR != 0) {
            StreamProtocol.splitAudioPair(packet).forEach(receiver::offer)
        } else {
            receiver.offer(packet)
        }
    }

    /**
     * Writes one queued media record and returns the media payload bytes written. With negotiated
     * call video fragments (experimental, direct calls only) a large video frame goes as ordered
     * fragments and queued call audio is written between them ([flushBetween] applies the writer's
     * flush rule); otherwise exactly one whole media packet, as before. The fragment size is the
     * fixed 2 KB, or (experimental, [fragmentAirtimeMs] > 0) sized by the link rate (CallFragmentAirtime).
     */
    private fun writeMediaRecord(
        link: SecureBluetoothSession,
        endpoint: EndToEndVideoSession,
        packet: OutboundMedia,
        flushBetween: () -> Unit,
    ): Long {
        val fragmenting = peerAcceptsVideoFragments && relayHops == 0 &&
            CallFragmentAirtime.shouldFragment(
                packet.payload.size + StreamProtocol.PACKET_HEADER_BYTES,
                packet.flags,
                fragmentAirtimeMs,
                fragmentRate(),
            )
        if (!fragmenting) {
            withSentAccounting(packet.payload.size) {
                EndToEndStreamProtocol.writePacket(
                    sinkFor(link),
                    endpoint.sealer,
                    packet.flags,
                    packet.presentationTimeUs,
                    packet.payload,
                )
            }
            return packet.payload.size.toLong()
        }
        // The cutover may drop the unsent fragments of this frame (ResetSendFragments); the next frame starts at 0.
        abandonFrame = false
        midVideoFrame = true
        try {
            val fragmentWriter = CallFragmentWriter<OutboundMedia>(
                targetMs = fragmentAirtimeMs,
                rate = { fragmentRate() },
                stats = fragmentStats,
                nanoTime = System::nanoTime,
                running = callRunning::get,
                writeFragment = { flags, fragment ->
                    // Each fragment's chunk counts when written and when it arrives, so audio written between
                    // fragments keeps the same byte order on both sides.
                    withSentAccounting(fragment.size - CallVideoFragments.HEADER_BYTES) {
                        EndToEndStreamProtocol.writeVideoFragment(sinkFor(link), endpoint.sealer, flags, fragment)
                    }
                },
                takeAudio = { takeQueuedAudio() },
                audioQueuedAtNs = OutboundMedia::queuedAtNs,
                writeAudio = { audio -> writeInterleavedAudio(link, endpoint, audio) },
                flushBetween = flushBetween,
                afterFragment = { reportFragmentStatsIfDue() },
                abandon = { abandonFrame },
            )
            val audioBytes = fragmentWriter.writeFrame(
                EndToEndStreamProtocol.encodePacket(packet.flags, packet.presentationTimeUs, packet.payload),
                packet.flags,
            )
            fragmentedVideoFrames.incrementAndGet()
            return packet.payload.size.toLong() + audioBytes
        } finally {
            midVideoFrame = false
        }
    }

    /**
     * Delivery accounting (link report version 3) for [payloadBytes] of one record: counted on the carrier that actually
     * carried it (the carrier writer in fan-out), as the peer counts it.
     */
    private inline fun withSentAccounting(payloadBytes: Int, crossinline write: () -> Unit) {
        val set = carriers
        if (set == null) {
            write()
            peerDelivery.onSent(payloadBytes, System.nanoTime())
        } else {
            set.withCookie(null, { id, ns -> stateOf(id).peerDelivery.onSent(payloadBytes, ns) }) { write() }
        }
    }

    /** Experimental call video fragments: one queued call audio packet written between two fragments. */
    private fun writeInterleavedAudio(
        link: SecureBluetoothSession,
        endpoint: EndToEndVideoSession,
        audio: OutboundMedia,
    ): Long {
        audioQueueWaitMax.record((System.nanoTime() - audio.queuedAtNs).coerceAtLeast(0L) / 1_000_000L)
        withSentAccounting(audio.payload.size) {
            EndToEndStreamProtocol.writePacket(
                sinkFor(link),
                endpoint.sealer,
                audio.flags,
                audio.presentationTimeUs,
                audio.payload,
            )
        }
        debugTxCounters?.audioWrites?.incrementAndGet()
        interleavedAudioPackets.incrementAndGet()
        return audio.payload.size.toLong()
    }

    /** The best rate evidence for sizing airtime fragments (unknown = fixed size); see CallFragmentAirtime.pickRate. */
    private fun fragmentRate(): FragmentRate =
        if (fragmentAirtimeMs <= 0) FragmentRate.UNKNOWN
        else CallFragmentAirtime.pickRate(fragmentRates.peakKbps(), peerMeasuredKbps, fragmentProbeKbps)

    /** BENCHMARK call_fragment_airtime: one line per second with fragmented frames (both fragment arms). */
    private fun reportFragmentStatsIfDue(force: Boolean = false) {
        if (!peerAcceptsVideoFragments) return
        fragmentStats.takeWindow(FRAGMENT_STATS_INTERVAL_NS, force)?.let { window ->
            Log.i("BluetoothVideo", window.benchmarkLine(state?.callId?.toString(), fragmentAirtimeMs))
        }
    }

    private fun takeQueuedAudio(): OutboundMedia? = if (boundedAge) {
        synchronized(mediaLock) { takePrivateCallAudio(media, OutboundMedia::isAudio) }
    } else {
        takePrivateCallAudio(media, OutboundMedia::isAudio)
    }?.let(::coalesceQueuedAudio)

    /**
     * Call audio coalescing (`test_call_audio_coalesce`, default on; DEBUG `=false` is the control): [first] plus the ordinary
     * audio units already waiting behind it, as one pair or batch record (see [coalesceQueuedCallAudio]). Off, or
     * with nothing else waiting, [first] is returned unchanged. The combined record keeps the oldest unit's queue
     * times, so media-age statistics still describe the oldest audio it carries.
     */
    private fun coalesceQueuedAudio(first: OutboundMedia): OutboundMedia {
        if (!audioCoalesce || first.flags != StreamProtocol.FLAG_AUDIO) return first
        val takeNextIf: ((OutboundMedia) -> Boolean) -> OutboundMedia? = { accept ->
            takePrivateCallAudioIf(media, OutboundMedia::isAudio, accept)
        }
        val units = if (boundedAge) {
            synchronized(mediaLock) {
                coalesceQueuedCallAudio(first, OutboundMedia::flags, OutboundMedia::presentationTimeUs, { it.payload.size }, takeNextIf)
            }
        } else {
            coalesceQueuedCallAudio(first, OutboundMedia::flags, OutboundMedia::presentationTimeUs, { it.payload.size }, takeNextIf)
        }
        if (units.size < 2) return first
        coalescedAudioRecords.incrementAndGet()
        coalescedAudioUnits.addAndGet(units.size.toLong())
        val combined = combineCallAudio(units.map { it.toStreamPacket() })
        return OutboundMedia(combined.flags, combined.presentationTimeUs, combined.payload, first.debugQueuedAtNs, first.queuedAtNs)
    }

    /** Sealing happens after this selection, so live audio can safely pass queued video. */
    private fun onPeerReport(id: Int, report: LinkCapacityReport) {
        val carrier = carriers?.kindOf(id) ?: CarrierKind.BLUETOOTH
        // Only the carrier this phone sends media on may move its send limit (a standby's numbers would corrupt it).
        if (report.arrivalKbps > 0 && id == (carriers?.sendCarrier ?: 0)) peerMeasuredKbps = report.arrivalKbps
        if (report.arrivalKbps > 0) {
            when (probeTarget(carrier, pacedWrites)) {
                ProbeTarget.BLUETOOTH -> {
                    // The peer measured arrival trains of this phone's media: the same single-link evidence
                    // a stream child reports (experimental for calls).
                    pacer.setMeasuredLinkKbps(report.arrivalKbps)
                    LinkCapacity.recordProbe(transport, report.arrivalKbps)
                }
                // Bluetooth learned capacity is never written from a Wi-Fi carrier (master 4.4).
                ProbeTarget.CARRIER -> LinkCapacity.recordProbe(transport, report.arrivalKbps, carrier)
                ProbeTarget.NONE -> Unit
            }
        }
        peerAddedDelayMs = report.addedDelayMs
        if (peerLateness.onReport(report)) peerLateUntilNs = System.nanoTime() + PeerLatenessTrend.LATE_HOLD_NS
        notePeerHeartbeat(id, report)
        stateOf(id).deliveryReports.accepted(report)?.let { delivery ->
            if (!stateOf(id).peerDelivery.onReport(delivery)) {
                Log.w("BluetoothVideo", "Call link: the peer's delivery counters went backwards; queue measurement off for this link")
            } else if (fragmentAirtimeMs > 0) {
                fragmentRates.onDelivered(stateOf(id).peerDelivery.deliveredKbps())
            }
        }
    }

    /** F11: a verification code was shown: the GATT call listener advertises at low latency / high power meanwhile. */
    fun boostAdvertising() {
        gattServer?.boostAdvertising()
    }

    /** The ended call's peer node ID (hex) when it was a real identity (null for a throwaway): for "Missed call" call back. */
    @Volatile var endedPeerNodeIdHex: String? = null
        private set

    /** Full node ID (hex) of the authenticated end-to-end peer, for the call screen's verification UI. */
    val endToEndPeerNodeId: String?
        get() = contentSession?.effectivePeerNodeId?.toString()

    /**
     * The real fingerprint a pseudonymous peer revealed in this call, kept after the call ends (in memory only) so a stranger's
     * recent entry can be completed if the user verifies that person later. Null when nobody revealed.
     */
    @Volatile var revealedPeerFingerprint: IdentityFingerprint? = null
        private set

    /** BMH3 pseudonyms: the peer is still only a throwaway key (no identity to show, remember or verify yet). */
    val peerStillPseudonym: Boolean
        get() = contentSession?.peerIsPseudonym == true

    /**
     * Naming v2: the generated name of an incoming stranger ("Gold Fox"), made from the throwaway key's node ID on both
     * phones of the call; null once the peer is revealed, when generated names are off or the lists are not loaded.
     */
    /**
     * Naming v2 (review F20): the generated name of the throwaway this call began with, for the recents entry of a person who
     * is NOT a verified contact (null for a real-key peer, and for a stranger who then turned out to be a verified contact).
     * Unlike [strangerGeneratedName] it still answers after the peer revealed its real key.
     */
    fun recentStrangerName(): String? {
        val session = contentSession ?: return null
        if (session.reveal?.peerPseudonymous != true) return null
        val revealedVerified = runCatching {
            session.reveal?.revealed != null &&
                PeerIdentityGate.store?.state(session.effectivePeerNodeId, session.effectivePeerFingerprint) == VerificationState.VERIFIED
        }.getOrDefault(false)
        if (revealedVerified) return null
        val throwawayNode = session.peerNodeId.toByteArray()
        val name = StrangerNames.derive(StrangerNames.Input.throwawayNodeId(throwawayNode)) ?: return null
        return name.text.lowercase()
    }

    fun strangerGeneratedName(): String? {
        if (!peerStillPseudonym || !NamingFeatures.generatedNamesOn()) return null
        val throwawayNode = contentSession?.peerNodeId?.toByteArray() ?: return null
        return StrangerNames.derive(StrangerNames.Input.throwawayNodeId(throwawayNode))?.text
    }

    /** The adjacent hop's node label (a relay on a relayed call), as its link rate samples name it. */
    val adjacentPeerLabel: String
        get() = secureSession?.peerNodeId?.shortLabel() ?: ""

    /** A version-1 heartbeat ([report] null) or a version 3 report says the adjacent hop accepts version 3. */
    private fun notePeerHeartbeat(id: Int, report: LinkCapacityReport?) {
        if (stateOf(id).deliveryReports.onPeerHeartbeat(report)) {
            Log.i("BluetoothVideo", "Call link: the adjacent hop accepts delivery reports (link report version 3)")
        }
    }

    /** Keyframes and scene cuts drain over the link; their backlog is expected, not congestion. */
    private fun noteVideoFrame(packet: OutboundMedia) {
        val size = packet.payload.size.toLong()
        val typical = typicalVideoBytes
        typicalVideoBytes = if (typical == 0L) size else (typical * 15 + size.coerceAtMost(typical * 4)) / 16
        val graceNs = largeFrameDrainGraceNs(size, typical, packet.isKeyFrame, 0L, peerMeasuredKbps * 125L)
            ?: return
        largeFrameGraceUntilNs = maxOf(largeFrameGraceUntilNs, System.nanoTime() + graceNs)
    }

    private fun takeNextMedia(): OutboundMedia? = if (boundedAge) {
        // Experimental bounded media age: take under the shed lock (see mediaLock).
        takeNextUnderLock(mediaLock, media, OutboundMedia::isAudio, WRITER_POLL_MS)
    } else {
        pollNextPrivateCallMedia(media, WRITER_POLL_MS, OutboundMedia::isAudio)
    }

    /**
     * Experimental bounded media age: queues [packet] (after the age policy) and returns the drops.
     * Audio and codec configuration (csd) are always queued and never evicted; while video waits for
     * a keyframe every inter frame is dropped and counted as shed (rate-control overuse evidence).
     */
    private fun sendBounded(packet: OutboundMedia): Int {
        if (packet.isAudio || packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0) return offerBounded(packet)
        if (droppingVideoUntilKeyFrame) {
            if (!packet.isKeyFrame) {
                debugTxCounters?.queueDrops?.incrementAndGet()
                boundedShedFrames.incrementAndGet()
                requestRecoveryKeyFrameIfDue()
                return 1
            }
            val removed = dropQueuedVideoPackets()
            boundedShedFrames.addAndGet(removed.toLong())
            droppingVideoUntilKeyFrame = false
            Log.i("BluetoothVideo", "Call video recovered at a keyframe after ${droppedMediaPackets + removed} media drops")
            return removed + offerBounded(packet.asRecoveryPoint())
        }
        if (thinDiscardable) {
            val oldestAgeMs = media.peek()?.let { (System.nanoTime() - it.queuedAtNs) / 1_000_000L } ?: 0L
            if (TemporalLayerThinning.shouldThin(packet.isDiscardable, oldestAgeMs)) {
                thinnedVideoFrames.incrementAndGet()
                return 0
            }
        }
        return offerBounded(packet)
    }

    /** Experimental bounded media age: [offerCodecSafely] with this call's counters and keyframe wait. */
    private fun offerBounded(packet: OutboundMedia): Int {
        val critical = packet.isAudio || packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0 || packet.isKeyFrame
        val result = offerCodecSafely(mediaLock, media, packet, mediaCapacity, critical) { callMediaShedClass(it.flags) }
        var drops = result.shed.size
        if (!result.queued) drops++
        if (drops > 0) {
            debugTxCounters?.queueDrops?.addAndGet(drops.toLong())
            boundedShedFrames.addAndGet(drops.toLong())
        }
        if (result.criticalOverflow) {
            criticalOverflowDrops++
            Log.w("BluetoothVideo", "Bounded media age: call queue headroom full; refused an arriving ${if (packet.isAudio) "audio" else "critical video"} record")
        }
        if (result.awaitKeyFrame) {
            droppingVideoUntilKeyFrame = true
            requestRecoveryKeyFrameIfDue(force = true)
        }
        return drops
    }

    /**
     * The default (no-loss arm) queue admission. With [audioHeadroom] video and codec configuration
     * are refused only when queued non-audio records fill [mediaCapacity]; audio uses the extra
     * room. Without it (control arm), any record is refused once [mediaCapacity] records of either
     * kind are queued, as before.
     */
    private fun offerWithinCallBudget(packet: OutboundMedia): Boolean {
        if (!audioHeadroom || packet.isAudio) return media.offer(packet)
        if (privateCallVideoBudgetFull(media, mediaCapacity, OutboundMedia::isAudio)) return false
        return media.offer(packet)
    }

    private fun offerLatestAudio(packet: OutboundMedia): Int {
        if (media.offer(packet)) return 0
        val oldestAudio = media.firstOrNull(OutboundMedia::isAudio) ?: media.peek()
        if (oldestAudio != null && media.remove(oldestAudio)) {
            debugTxCounters?.queueDrops?.incrementAndGet()
            if (oldestAudio.isAudio) debugTxCounters?.audioDrops?.incrementAndGet()
        }
        check(media.offer(packet)) { "Could not retain the latest call audio packet" }
        return if (oldestAudio == null) 0 else 1
    }

    private fun offerCritical(packet: OutboundMedia): Int {
        if (media.offer(packet)) return 0
        val removed = media.poll()
        if (removed != null) debugTxCounters?.queueDrops?.incrementAndGet()
        check(media.offer(packet)) { "Could not queue a call video recovery point" }
        return if (removed == null) 0 else 1
    }

    private fun OutboundMedia.toStreamPacket() =
        StreamProtocol.Packet(flags, presentationTimeUs, payload)

    /** Experimental temporal layering only: removes queued discardable call video. */
    private fun dropQueuedDiscardablePackets(): Int {
        var removed = 0
        for (queued in media.toList()) {
            if (queued.isDiscardable && media.remove(queued)) removed++
        }
        thinnedVideoFrames.addAndGet(removed.toLong())
        return removed
    }

    /**
     * [keepCodecConfig]: experimental bounded media age never sheds codec configuration (csd); the
     * default no-loss path keeps its original behaviour (the experiment is off, so it is false).
     */
    private fun dropQueuedVideoPackets(keepCodecConfig: Boolean = boundedAge): Int {
        var removed = 0
        for (queued in media.toList()) {
            val codecConfig = queued.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0
            if (!queued.isAudio && !(keepCodecConfig && codecConfig) && media.remove(queued)) {
                removed++
                debugTxCounters?.queueDrops?.incrementAndGet()
            }
        }
        return removed
    }

    /**
     * Experimental bounded media age for outgoing call video ([packet] is video). Returns true when
     * [packet] is consumed (dropped). Steps after the bitrate/frame-rate ladder (driven by the shed
     * evidence in LinkRateSample): verified non-reference frames are skipped from half the budget;
     * beyond the budget the queue skips to its newest keyframe or waits for a fresh one; when skips
     * keep recurring video pauses (audio only) and resumes at a keyframe after the link recovers.
     * Queued media is plaintext; records are sealed when written, so BME1/BMS1 stay contiguous.
     */
    private fun applyBoundedMediaAge(packet: OutboundMedia): Boolean {
        val nowNs = System.nanoTime()
        val budgetMs = callVideoBudgetMs()
        val ageMs = oldestVideoAgeMs(nowNs)
        if (pauseGovernor.paused) {
            // Audio still queues while video is paused: resume only once all queued media is fresh.
            val mediaAgeMs = oldestMediaAgeMs(nowNs)
            if (!pauseGovernor.shouldResume(nowNs, mediaAgeMs, budgetMs, nowNs < peerLateUntilNs)) {
                boundedPausedFrames++
                boundedShedFrames.incrementAndGet()
                debugTxCounters?.queueDrops?.incrementAndGet()
                droppedMediaPackets++
                return true
            }
            pauseGovernor.onResumed(nowNs)
            droppingVideoUntilKeyFrame = true
            requestRecoveryKeyFrameIfDue(force = true)
            Log.i(
                "BluetoothVideo",
                "BENCHMARK media_age_event: role=call_tx | callId=${state?.callId} | event=video_resumed | " +
                    "pausedMs=${pauseGovernor.pausedMs(nowNs)} | queueAgeMs=$mediaAgeMs | budgetMs=$budgetMs",
            )
            onStatus("Experimental bounded media age: the link recovered; resuming video at a keyframe")
            return false
        }
        if (droppingVideoUntilKeyFrame) return false
        if (packet.isDiscardable && BoundedMediaAge.nonReferenceSkipAllowed(activeCodec.protocolId) &&
            ageMs >= BoundedMediaAge.thinThresholdMs(budgetMs)
        ) {
            thinnedVideoFrames.incrementAndGet()
            return true
        }
        if (!callShedder.shouldShed(ageMs, budgetMs, nowNs < largeFrameGraceUntilNs, nowNs)) return false
        val (plan, removedMedia) = shedAtomically(
            mediaLock,
            media,
            { queuedMedia -> callMediaShedClass(queuedMedia.flags) },
            { queuedMedia -> (nowNs - queuedMedia.queuedAtNs) / 1_000_000L <= budgetMs },
        )
        val removed = removedMedia.size
        callShedder.recordShed(plan, removed, nowNs)
        // The existing keyframe wait (droppingVideoUntilKeyFrame) owns recovery from here.
        callShedder.reset()
        debugTxCounters?.queueDrops?.addAndGet(removed.toLong())
        droppedMediaPackets += removed
        boundedShedFrames.addAndGet(removed.toLong())
        Log.i(
            "BluetoothVideo",
            "BENCHMARK media_age_shed: role=call_tx | callId=${state?.callId} | queueAgeMs=$ageMs | " +
                "budgetMs=$budgetMs | peerAddedDelayMs=$peerAddedDelayMs | removed=$removed | " +
                "awaitKeyFrame=${plan.awaitKeyFrame} | events=${callShedder.shedEvents}",
        )
        if (pauseGovernor.onShed(nowNs)) {
            val more = dropQueuedVideoPackets()
            droppedMediaPackets += more + 1
            boundedPausedFrames++
            boundedShedFrames.addAndGet(more + 1L)
            debugTxCounters?.queueDrops?.incrementAndGet()
            droppingVideoUntilKeyFrame = false
            Log.i(
                "BluetoothVideo",
                "BENCHMARK media_age_event: role=call_tx | callId=${state?.callId} | event=video_paused | " +
                    "pauseEvents=${pauseGovernor.pauseEvents} | backoffMs=${pauseGovernor.currentBackoffMs} | " +
                    "queueAgeMs=$ageMs | budgetMs=$budgetMs",
            )
            onStatus("Experimental bounded media age: video paused because the link is too slow; audio continues")
            return true
        }
        if (plan.awaitKeyFrame) {
            droppingVideoUntilKeyFrame = true
            requestRecoveryKeyFrameIfDue(force = true)
        }
        return false
    }

    private fun callVideoBudgetMs(): Long =
        BoundedMediaAge.callVideoQueueBudgetMs(peerAddedDelayMs, BoundedMediaAge.ASSUMED_PEER_CALL_BASE_DELAY_MS)

    /** Oldest queued media of either kind (audio included). */
    private fun oldestMediaAgeMs(nowNs: Long): Long =
        media.minOfOrNull { it.queuedAtNs }?.let { (nowNs - it).coerceAtLeast(0L) / 1_000_000L } ?: 0L

    private fun oldestVideoAgeMs(nowNs: Long): Long =
        media.firstOrNull { !it.isAudio && it.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0 }
            ?.let { (nowNs - it.queuedAtNs).coerceAtLeast(0L) / 1_000_000L } ?: 0L

    private fun requestRecoveryKeyFrameIfDue(force: Boolean = false) {
        val nowNs = System.nanoTime()
        if (!force && nowNs - lastRecoveryKeyFrameRequestNs < RECOVERY_REQUEST_INTERVAL_NS) return
        lastRecoveryKeyFrameRequestNs = nowNs
        onLocalKeyFrameNeeded()
    }

    @Synchronized
    private fun reportDebugTx(final: Boolean = false) {
        val counters = debugTxCounters ?: return
        val callId = state?.callId ?: return
        if (final && !debugTxFinalReported.compareAndSet(false, true)) return
        Log.i(
            "BluetoothVideo",
            "BENCHMARK call tx: callId=$callId | final=$final | " +
                "queue=${media.size}/${media.size + media.remainingCapacity()} | pendingAudio=${pendingAudioPackets.size} | " +
                "videoInputs=${counters.videoInputs.get()} | audioInputs=${counters.audioInputs.get()} | " +
                "configInputs=${counters.configInputs.get()} | videoWrites=${counters.videoWrites.get()} | " +
                "audioWrites=${counters.audioWrites.get()} | configWrites=${counters.configWrites.get()} | " +
                "queueDrops=${counters.queueDrops.get()} | " +
                (if (thinDiscardable) "thinned=${thinnedVideoFrames.get()} | " else "") +
                "inactiveInputs=${counters.inactiveInputs.get()} | " +
                (if (peerAcceptsVideoFragments) {
                    "fragmentedFrames=${fragmentedVideoFrames.get()} | interleavedAudio=${interleavedAudioPackets.get()} | "
                } else {
                    ""
                }) +
                (if (audioCoalesce) {
                    "coalescedAudioRecords=${coalescedAudioRecords.get()} | coalescedAudioUnits=${coalescedAudioUnits.get()} | "
                } else {
                    ""
                }) +
                "mutedAudio=${counters.mutedAudio.get()} | audioDrops=${counters.audioDrops.get()}",
        )
        val nowNs = System.nanoTime()
        // Both arms: this direction's media age in the call queue and any bounded-age degradation.
        Log.i(
            "BluetoothVideo",
            "BENCHMARK media_age: role=call_tx | callId=$callId | final=$final | " +
                "policy=${BoundedMediaAge.policyLabel(boundedAge)} | " +
                "videoQueueAgeMaxMs=${videoQueueWaitMax.take()} | audioQueueAgeMaxMs=${audioQueueWaitMax.take()} | " +
                "videoQueueAgeNowMs=${oldestVideoAgeMs(nowNs)} | peerAddedDelayMs=$peerAddedDelayMs | " +
                "budgetMs=${callVideoBudgetMs()} | shedEvents=${callShedder.shedEvents} | " +
                "shedFrames=${boundedShedFrames.get()} | thinned=${thinnedVideoFrames.get()} | " +
                "videoPaused=${pauseGovernor.paused} | pauseEvents=${pauseGovernor.pauseEvents} | " +
                "pausedMs=${pauseGovernor.pausedMs(nowNs)} | pausedFrames=$boundedPausedFrames | " +
                "criticalOverflowDrops=$criticalOverflowDrops",
        )
        if (debugPipelineTiming) {
            for ((stage, stats) in listOf(
                    "audio_batch_fill" to debugAudioBatchWait,
                    "video_queue" to debugVideoQueueWait,
                    "audio_queue" to debugAudioQueueWait,
                    "media_write" to debugMediaWriteWait,
                    "media_flush" to debugMediaFlushWait,
                )
            ) {
                val timing = requireNotNull(stats).snapshot()
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK call pipeline: callId=$callId | final=$final | stage=$stage | " +
                        "samples=${timing.samples} | window=last256 | p50Ms=${timing.p50Ms} | " +
                        "p95Ms=${timing.p95Ms} | p99Ms=${timing.p99Ms} | maxMs=${timing.maxMs}",
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openOutgoing(
        peer: BleL2capPeer,
        linkPhy: BlePhyPreference,
        plan: Bmh3AttemptPlan,
        deadline: SetupDeadline? = null,
        deferPhy: Boolean = false,
    ): CallConnection {
        requestedLinkPhy = linkPhy
        return openOutgoingLink(peer, linkPhy, plan, deadline, deferPhy)
    }

    /** The BMH3 plan of one outgoing attempt toward [selected] ([Bmh3AttemptPlan]). */
    private fun attemptPlan(selected: DiscoveryAnnouncement?): Bmh3AttemptPlan {
        // A PC answers only BMH3: with BMH3 off, no BMH2 hello is sent to it (PcCallTargets).
        val toPc = selected != null && PcCallTargets.isPc(selected.nodeIdPrefix, SystemClock.elapsedRealtime())
        if (PcCallTargets.refusesBmh2Dial(Bmh3Feature.enabled || qrScanPayload != null, selected, toPc)) {
            Bmh3Android.log("bmh3: event=choice | version=none | reason=pc_needs_bmh3")
            localFailureMessage = PcCallTargets.BMH3_OFF_MESSAGE
            onStatus(PcCallTargets.BMH3_OFF_MESSAGE)
            throw PcNeedsBmh3Exception()
        }
        // Amendment CP (LE only): the link toward the first helper of a relayed route presents a declared throwaway of its own.
        val relayRoute = pendingRelayRoute?.takeIf {
            relayedCallsOn && DebugTunables.relayedPseudonymousEdges && transport != BluetoothTransportOption.CLASSIC_RFCOMM
        }
        val relayHop = relayRoute?.let { RelayedCalls.hopOfferFor(it.helperPrefix, it.rotating) }
        val contactHelper = relayHop?.takeIf {
            DebugTunables.relayedContactHelper && it.rotating && RotatingDiscovery.recognize(it).size == 1
        }
        return Bmh3Wiring.plan(
            appContext, selected, rotatingIds, qrScan = qrScanPayload,
            relayEdges = relayRoute != null, relayHop = relayHop, contactHelper = contactHelper,
            // A2's build marker: sent by every build that implements BWF1 (a verification scan never carries it). [R M-3]
            wifiMarker = WifiHopPlumbing.BUILD_HANDLES_BWF1 && qrScanPayload == null,
        )
    }

    private fun openOutgoingLink(
        peer: BleL2capPeer,
        linkPhy: BlePhyPreference,
        plan: Bmh3AttemptPlan,
        deadline: SetupDeadline?,
        deferPhy: Boolean = false,
    ): CallConnection {
        if (peer.wifiOnly) return openWifiNativeLink(peer, deadline)
        return when (transport) {
        BluetoothTransportOption.CLASSIC_RFCOMM -> {
            // Same candidate order, deadlines and identity check as the stream sender; a relayed
            // call's immediate peer is the relay, which advertises the callee's prefix.
            val connected = try {
                ClassicConnector.connect(
                    appContext,
                    adapter,
                    peer.device,
                    peer.announcement.nodeIdPrefix,
                    ClassicRole.PRIVATE_CALL,
                    verifyLinkPrefix = peer.announcement.routeCost == 0,
                    totalBudgetMs = CLASSIC_SESSION_BUDGET_MS,
                    allowInquiry = true,
                    isCancelled = { !running.get() },
                    onSocket = { pendingClassicSocket = it },
                    handshake = { socket ->
                        SecureBluetoothHandshake.initiate(
                            BufferedInputStream(socket.inputStream, ioConfig.bufferBytes),
                            BufferedOutputStream(socket.mediaOutputStream(), ioConfig.bufferBytes),
                            identity,
                            linkCapabilities,
                            // The attempt's plan: the same throwaway as the end-to-end handshake that follows (review S1).
                            bmh3 = plan.initiator(Bmh3Policy.Layer.LINK),
                        )
                    },
                    verify = { link ->
                        require(link.peerCapabilities.transportMask and transport.capabilityMask != 0) {
                            "Peer did not authenticate ${transport.shortLabel} support"
                        }
                    },
                    onStatus = onStatus,
                )
            } finally {
                pendingClassicSocket = null
            }
            socketConnection(connected.socket).copy(link = connected.session)
        }
        BluetoothTransportOption.BLE_L2CAP -> if (dualPlan.size > 1) {
            openCocThenGatt(peer, linkPhy, deadline)
        } else {
            val socket = try {
                BleCocClient.connect(
                    appContext,
                    peer.device,
                    onStatus,
                    linkPhy,
                    "private-call-connect",
                    // Review A2: a socket created after close() or a Cancel is closed at once, not left connecting.
                    onSocket = { socket ->
                        pendingCocSocket = socket
                        if (!running.get() || privateCallAlreadyEnded(state?.phase)) runCatching { socket.close() }
                    },
                    deadline = deadline,
                ) { bleControlConnection = it }
            } finally {
                pendingCocSocket = null
            }
            // Experimental test_call_1m_first_coc: linkPhy is 1M here; the call asks the control link for 2M after its first picture.
            socketConnection(socket, phyDeferred = deferPhy)
        }
        BluetoothTransportOption.BLE_GATT -> gattConnection(
            GattVideoClient.open(
                appContext,
                peer.device,
                linkPhy,
                BleConnectionPriority.HIGH,
                onStatus,
                acknowledgedWrites = false,
                deferConnectionPriority = debugGattDeferConnectionPriority,
                deadline = deadline,
                phySplit = dialPhySplit?.takeIf { it.first == peer.device.address }?.second,
                // Experimental test_call_1m_first: no PHY request at subscribe; the call asks after its first picture.
                deferPhy = deferPhy,
            ),
            address = runCatching { peer.device.address }.getOrNull(),
            phyDeferred = deferPhy,
        )
        }
    }

    /** Wider reach: dial a person heard only over Wi-Fi. The BMH3/BMH2 link handshake runs on the returned streams in establish(). */
    private fun openWifiNativeLink(peer: BleL2capPeer, deadline: SetupDeadline?): CallConnection {
        val link = WifiDiscovery.nativeLink() ?: throw IOException("native_fail:not_started")
        val key = WifiSighting.keyOf(peer.announcement)
        val connection = try {
            // The call's setup deadline and its Cancel bound the Wi-Fi dial too (A8 review P3-7).
            link.dial(
                key,
                "call",
                deadlineMs = deadline?.remainingMs()?.coerceIn(1L, WifiLimits.ATTEMPT_MAX_MS) ?: WifiLimits.ATTEMPT_MAX_MS,
                cancelled = { !running.get() || privateCallAlreadyEnded(state?.phase) },
            )
        } catch (error: IOException) {
            onStatus(WifiCallNotices.WIFI_CONNECT_FAILED)
            throw error
        }
        return CallConnection(
            connection.input, connection.output, WifiCallNotices.LABEL, connection.close,
            socket = null, link = null, address = null, phySummary = { "" },
            carrierKind = CarrierKind.WIFI_AWARE,   // A3: CallConnection.carrierKind [API]
            onAuthenticated = connection.admitted,  // [AM 12]
        )
    }

    @SuppressLint("MissingPermission")
    private fun socketConnection(socket: BluetoothSocket, phyDeferred: Boolean = false): CallConnection {
        val mps = runCatching { socket.maxTransmitPacketSize }.getOrDefault(0)
        val oneMFirstRequest: (BlePhyPreference) -> Boolean = { preference ->
            bleControlConnection?.requestPhyInPlace(preference, "call_1m_first") == true
        }
        return CallConnection(
            socket.inputStream,
            socket.mediaOutputStream(),
            "${transport.shortLabel}${if (mps > 0) " (MPS $mps)" else ""}",
            socket::close,
            socket = socket,
            address = runCatching { socket.remoteDevice?.address }.getOrNull(),
            phySummary = { bleControlConnection?.phySummary ?: cocControlServer?.phySummary ?: "" },
            requestPhy = oneMFirstRequest.takeIf { phyDeferred },
            movePhy = { preference -> bleControlConnection?.requestPhyInPlace(preference) == true },
            readPhy = { bleControlConnection?.readPhyNow() == true },
        )
    }

    private fun gattConnection(
        active: GattVideoConnection,
        address: String? = null,
        arrivedOn: BluetoothTransportOption? = null,
        phyDeferred: Boolean = false,
    ) = CallConnection(
        active.inputStream,
        active.outputStream,
        "${(arrivedOn ?: transport).shortLabel} (${active.phySummary()})",
        active::close,
        address = address,
        phySummary = active.phySummary,
        arrivedOn = arrivedOn,
        requestPhy = active.requestPhy.takeIf { phyDeferred },
        movePhy = active.requestPhy,
        readPhy = active.readPhy,
    )

    /**
     * Experimental dual listening: tries the CoC phase, then the GATT call service of the same device inside
     * this attempt (no scan between). The plan is [dualPlan]; a CoC failure that GATT repaired is not a
     * failure of this phone's CoC, so the safety net is not told.
     */
    private fun openCocThenGatt(peer: BleL2capPeer, linkPhy: BlePhyPreference, deadline: SetupDeadline?): CallConnection {
        val runner = DualAttemptRunner<CallConnection>(
            clockMs = SystemClock::elapsedRealtime,
            cancelled = { !running.get() },
            log = DualListeningRuntime::log,
        )
        val outcome = runner.run(dualPlan) { attempt, _ ->
            if (attempt == BluetoothTransportOption.BLE_L2CAP) {
                openCocPhase(peer, linkPhy, deadline)
            } else {
                openGattAfterCoc(peer, linkPhy, deadline)
            }
        }
        if (outcome.fallback) {
            DualListeningRuntime.log(
                DualListeningLog.fallback(BluetoothTransportOption.BLE_L2CAP, outcome.transport, outcome.firstError, outcome.firstPhaseMs),
            )
            onStatus(DualListeningLog.FALLBACK_NOTICE)
        }
        return outcome.link
    }

    /** The CoC phase of [openCocThenGatt]: the unchanged CoC client, cut at [DualSetupBudget.COC_PHASE_MS]. */
    private fun openCocPhase(peer: BleL2capPeer, linkPhy: BlePhyPreference, deadline: SetupDeadline?): CallConnection {
        val startedMs = SystemClock.elapsedRealtime()
        val outerDeadlineMs = deadline?.ordinaryRemainingMs(startedMs)?.let { startedMs + it }
        val deadlineMs = DualSetupBudget.cocDeadline(startedMs, outerDeadlineMs)
        val phaseDeadline = deadline?.narrowed(deadlineMs) ?: SetupDeadline(deadlineMs)
        val pendingSocket = AtomicReference<BluetoothSocket?>()
        val expired = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        thread(name = "DualCocPhase", isDaemon = true) {
            val waitMs = (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            if (!finished.await(waitMs, TimeUnit.MILLISECONDS)) {
                expired.set(true)
                DualListeningRuntime.log(
                    "dual_listening: event=coc_phase_deadline | phaseMs=${SystemClock.elapsedRealtime() - startedMs}",
                )
                runCatching { pendingSocket.get()?.close() }
                runCatching { bleControlConnection?.close() }
            }
        }
        try {
            when (DualListeningFeature.injection(BuildConfig.DEBUG, DualListeningFeature.enabled, DualListeningFeature.inject)) {
                DualListeningFeature.Injection.NONE -> Unit
                DualListeningFeature.Injection.COC_CONNECT_FAIL -> throw IOException("dual_listening: injected CoC connect failure")
                DualListeningFeature.Injection.COC_HANG -> {
                    Thread.sleep((deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 200L)
                    throw IOException("dual_listening: injected CoC hang until the phase deadline")
                }
            }
            val socket = BleCocClient.connect(
                appContext,
                peer.device,
                onStatus,
                linkPhy,
                "private-call-connect",
                // A socket created after the deadline is closed at once, not left connecting.
                onSocket = { socket -> if (expired.get()) runCatching { socket.close() } else pendingSocket.set(socket) },
                deadline = phaseDeadline,
            ) { bleControlConnection = it }
            return socketConnection(socket)
        } catch (error: Exception) {
            runCatching { pendingSocket.get()?.close() }
            runCatching { bleControlConnection?.close() }
            bleControlConnection = null
            throw error
        } finally {
            finished.countDown()
        }
    }

    /** The GATT attempt of [openCocThenGatt]: from here on this link, hello and policies are GATT's. */
    private fun openGattAfterCoc(peer: BleL2capPeer, linkPhy: BlePhyPreference, deadline: SetupDeadline?): CallConnection {
        linkTransportOverride = BluetoothTransportOption.BLE_GATT
        try {
            return gattConnection(
                GattVideoClient.open(
                    appContext,
                    peer.device,
                    linkPhy,
                    BleConnectionPriority.HIGH,
                    onStatus,
                    acknowledgedWrites = false,
                    deferConnectionPriority = debugGattDeferConnectionPriority,
                    deadline = deadline,
                ),
                address = runCatching { peer.device.address }.getOrNull(),
            )
        } catch (error: Exception) {
            linkTransportOverride = null
            throw error
        }
    }

    /** Log label of a selected offer: release builds never log a rotating ID (design section 6). */
    private fun offerLogLabel(announcement: DiscoveryAnnouncement): String =
        if (announcement.rotating && !BuildConfig.DEBUG) "rotating" else AdaptivePhyMemory.prefixLabel(announcement.nodeIdPrefix)

    /**
     * Rotating IDs: the first hop record each way when both link hellos carry bit 3. A responder
     * opens only its pinned call-offer IDs (section 6); an initiator advertised nothing and sends
     * none. Null when not negotiated (switch off here or on the peer): no record is exchanged.
     */
    private fun exchangeLinkOpening(
        link: SecureBluetoothSession,
        initiator: Boolean,
        pinsAtAccept: List<OpeningEntry> = emptyList(),
    ): List<OpeningEntry>? {
        if (!rotatingIds) return null
        // A pseudonymous responder cleared link bit 3 and withholds its salt; a pseudonymous peer's block cleared it too,
        // so neither side sends a BDO1 (an initiator that presented a pseudonym to an ordinary responder still does).
        // Review CP-1 item 5: the responder skips only when it presented a throwaway itself (then its block cleared bit 3);
        // a real responder answering a throwaway caller still exchanges, as the caller does.
        if (RelayIdentityPolicy.skipsLinkOpening(initiator, link.ownPseudonymous, link.peerPseudonymous)) return null
        val entries = if (initiator) emptyList() else pinsAtAccept
        return LinkOpeningExchange.exchange(link, linkCapabilities, entries)
    }

    /** DEBUG shows the ID next to the prefix; release builds never log both (PROTOCOL section 6). */
    private fun logRotatingOpening(
        role: String,
        result: DiscoverySelectionCheck.Result,
        selected: DiscoveryAnnouncement,
        peer: NodeId,
    ) {
        val detail = if (BuildConfig.DEBUG && result == DiscoverySelectionCheck.Result.PASS) {
            " | rdi=${selected.nodeIdPrefix.joinToString("") { "%02x".format(it) }} | prefix=${peer.shortLabel()}"
        } else {
            ""
        }
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotid_open: role=$role | result=$result$detail")
    }

    /**
     * Rotating IDs, Phase 2: starts the OFFER / PRESENCE_LEASE rules once the call is answered (an OFFER to a
     * call nobody answered would tell the peer that this phone holds its fingerprint; RD6). Eligible only
     * with a persistent vault, a peer that is a verified contact right now and the per-contact switch on.
     */
    private fun startPresenceSession() {
        val session = presenceSession ?: return
        val endpoint = contentSession ?: return
        presenceDispatch.dispatch({ session.start() }, runIfEmpty = true) { actions ->
            runCatching { runPresenceActions(actions, endpoint, session) }
        }
    }

    /** Review 2026-10-19 (P2): presence actions leave in the order the session produced them (one executor per call). */
    @Volatile private var presenceExecutor: java.util.concurrent.ExecutorService? = null
    private val presenceExecutorLock = Any() // never the endpoint's own monitor (other paths hold it)

    private val presenceDispatch = OrderedPresenceDispatch { task ->
        val executor = synchronized(presenceExecutorLock) {
            presenceExecutor ?: java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "PresenceActions").apply { isDaemon = true }
            }.also { presenceExecutor = it }
        }
        runCatching { executor.execute(task) }
    }

    /**
     * The user verified this call's peer during the call ([peerNodeIdHex] is the full node ID that was verified). The presence
     * rules were decided at the handshake or the reveal, when the peer was not yet a verified contact, so the call in which
     * two people verify each other never moved a lease. Eligibility is decided again now with the same definition
     * ([presenceIneligibleReason]); an eligible result turns the existing session eligible (records already received, such as
     * the peer's OFFER, are kept) and starts it if the call is answered. Send-time checks are unchanged. Verification
     * sessions (Design QL) and a peer that is still a throwaway are left alone.
     */
    fun onPeerVerified(peerNodeIdHex: String) {
        val endpoint = contentSession ?: return
        if (qrScope != null || qrScanPayload != null || endpoint.peerIsPseudonym) return
        if (!endpoint.effectivePeerNodeId.toString().equals(peerNodeIdHex, ignoreCase = true)) return
        val session = presenceSession ?: return
        if (session.eligible) return
        thread(name = "PresenceReconsider", isDaemon = true) {
            runCatching {
                val reason = presenceIneligibleReason(endpoint.effectivePeerNodeId, endpoint.effectivePeerFingerprint)
                if (reason != null) {
                    logPresence("ineligible", "reason=$reason | source=verified_in_call")
                    return@runCatching
                }
                if (presenceSession !== session || contentSession !== endpoint || !session.becomeEligible()) return@runCatching
                val phase = state?.phase
                val answered = phase == PrivateCallPhase.NEGOTIATING_MEDIA || phase == PrivateCallPhase.ACTIVE
                logPresence("eligible", "source=verified_in_call | answered=$answered")
                // Not answered yet: the answer starts it (accept / REMOTE_ACCEPT), as for any call.
                if (answered) startPresenceSession()
            }
        }
    }

    /** Off the UI thread: the verified-contact store and the vault may touch the Keystore. */
    private fun startPresenceSessionAsync() {
        if (presenceSession == null) return
        thread(name = "PresenceStart", isDaemon = true) { runCatching { startPresenceSession() } }
    }

    /**
     * The OFFER / PRESENCE_LEASE rules of this call, for the peer's identity as known now ([peer] is the real identity:
     * after a BMH3 pseudonym call's reveal it is the revealed contact, never the throwaway). Eligibility has one
     * definition ([PresenceEligibility]); a session that is not eligible logs why, once, so a missing lease exchange is
     * explained rather than silent. Off the UI thread: it may load the vault (Keystore).
     */
    private fun newPresenceSession(peer: NodeId, fingerprint: IdentityFingerprint, source: String, log: Boolean = true): PresenceSession {
        val reason = presenceIneligibleReason(peer, fingerprint)
        if (reason != null && log) logPresence("ineligible", "reason=$reason | source=$source")
        return PresenceSession(reason == null)
    }

    private fun presenceIneligibleReason(peer: NodeId, fingerprint: IdentityFingerprint): String? {
        val vault = RotatingDiscovery.presenceVault()
        return PresenceEligibility.reason(
            vaultPresent = vault != null,
            // A load that failed once (a Keystore not ready at cold start) is retried here, not judged from its first miss.
            persistent = vault?.ensureLoaded() == true,
            peerState = PeerIdentityGate.store?.state(peer, fingerprint),
            visibleToThem = vault?.visibleToThem(peer) ?: false,
            codeOnlyUnscanned = NamingFeatures.bvmid2On() && runCatching {
                CodeOnlyLeaseRule.blocked(
                    bvmid2On = true,
                    method = PeerIdentityGate.store?.all()?.firstOrNull { it.fingerprint == fingerprint }?.method,
                    scannedBothWays = ContactClaims.store(appContext).get(fingerprint)?.scannedBothWays == true,
                    holdsLease = vault?.contact(peer)?.presence != null,
                )
            }.getOrDefault(false),
            blocked = PersonPolicy.blocked(fingerprint),
        )
    }

    private fun runPresenceActions(
        actions: List<PresenceSession.Action>,
        endpoint: EndToEndVideoSession,
        session: PresenceSession,
    ) {
        val vault = RotatingDiscovery.presenceVault()
        // The real identity: after a pseudonym call's reveal the revealed contact, otherwise the authenticated peer.
        val peer = endpoint.effectivePeerNodeId
        val peerFingerprint = endpoint.effectivePeerFingerprint
        // A task queued before the call ended (or a reveal replaced the session) must not send on whatever link is current
        // now: the controls queue belongs to the endpoint, not to this call.
        fun current() = presenceSession === session && contentSession === endpoint
        val runner = PresenceActionRunner(session, object : PresenceActionRunner.Effects {
            override fun ineligibleReason(): String? =
                if (current()) presenceIneligibleReason(peer, peerFingerprint) else "session_ended"

            override fun visitAllows(): Boolean = qrLeaseAllowed(session, peerFingerprint)

            override fun sendOffer() {
                controls.offerMust(OutboundControl.Discovery(DiscoveryExchange.encode(DiscoveryExchange.RecognitionOffer)))
            }

            override fun deliverLease(): PresenceActionRunner.LeaseDelivery {
                // RD1: eligibility is re-checked at send time (a Forget, a switch or a reset since the
                // OFFER), and the delivery is recorded durably BEFORE the lease is queued: if it cannot be
                // stored, nothing is sent, so a revocation can never miss a lease that left the phone.
                // One locked step in the vault (check, durable record, key and generation from one state).
                val epoch = runCatching { RotatingDiscovery.currentEpoch() }.getOrNull()
                if (vault == null || epoch == null) return PresenceActionRunner.LeaseDelivery(null, "no_vault_or_epoch")
                return PresenceActionRunner.LeaseDelivery(
                    vault.deliverLease(peer, epoch) {
                        // AB3 R6: a contact not yet asked ("Can see when I'm nearby") gets no lease, as no OFFER.
                        current() && PeerIdentityGate.store?.state(peer, peerFingerprint) == VerificationState.VERIFIED &&
                            qrLeaseAllowed(session, peerFingerprint) && !PersonPolicy.blocked(peerFingerprint) &&
                            vault.visibleToThem(peer)
                    },
                )
            }

            override fun sendLease(lease: DiscoveryExchange.PresenceLease) {
                controls.offerMust(OutboundControl.Discovery(DiscoveryExchange.encode(lease)))
            }

            override fun log(event: String, detail: String) = logPresence(event, detail)
        })
        runner.run(actions).forEach { action ->
            when (action) {
                is PresenceSession.Action.StoreLease -> {
                    val epoch = runCatching { RotatingDiscovery.currentEpoch() }.getOrNull()
                    val stored = vault != null && epoch != null &&
                        vault.storeLease(peer, action.lease, epoch, System.currentTimeMillis())
                    if (stored) RotatingDiscovery.requestRecognitionRebuild()
                    logPresence("lease_stored", "changed=$stored | generation=${action.lease.generation}")
                    if (stored && qrScope != null) QrMeet.onLeaseStored?.invoke(peerFingerprint)
                }
                is PresenceSession.Action.Reject -> {
                    Log.w("BluetoothVideo", "SECURITY DISCOVERY RECORD REJECTED reason=${action.reason}")
                    logPresence("lease_rejected", "reason=${action.reason}")
                }
                else -> Unit
            }
        }
        if (qrScope != null) qrCheckSettled()
    }

    /** The caller's check of the callee's OPENING against the version 5 offer it selected (RV15, RD4 epoch window). */
    private fun verifyAwaitedOpening(wait: AwaitedOpening, content: EndToEndContentRecord) {
        val result = CallEndpointOpening.verifyFirstRecord(
            wait.selected,
            wait.endpointNode,
            wait.linkNode,
            direct = wait.selected.routeCost == 0,
            first = content,
        )
        logRotatingOpening("call_endpoint", result, wait.selected, wait.endpointNode)
        require(result == DiscoverySelectionCheck.Result.PASS) { "Call peer identity changed between discovery and connection" }
    }

    /**
     * BMH3 pseudonyms: the callee's reveal goes out ahead of its Accept, with the opening entries it withheld at the Invite
     * (PROTOCOL "When the reveal happens"). Nothing is sent to a peer that did not declare a pseudonym itself.
     */
    private fun queueRevealOnAccept() {
        val endpoint = contentSession ?: return
        val reveal = endpoint.reveal ?: return
        if (!reveal.ownPseudonymous || !reveal.peerPseudonymous || reveal.localRole != Bmh3Reveal.ROLE_CALLEE) return
        val entries = revealEntries
        controls.offerMust(OutboundControl.Reveal { reveal.sendOwn(identity, entries) })
        Bmh3Android.log("bmh3: event=reveal | role=callee | result=sent")
        armCallerRevealDeadline(reveal, SystemClock.elapsedRealtime())
    }

    /**
     * Review 2026-10-27 (finding 1): a callee that revealed itself to a pseudonymous caller requires the caller's reveal.
     * Until it verified, the caller's media is refused ([CallMediaGate]); if it has not arrived [CallerRevealGate.deadlineMs]
     * after Accept, this phone hangs up.
     */
    /** Relay hops for the reveal deadline: none when the link peer is the call peer (a direct call), whatever the Invite claims. */
    private fun revealDeadlineHops(): Int = CallerRevealGate.effectiveHops(
        direct = contentSession?.peerNodeId != null && contentSession?.peerNodeId == secureSession?.peerNodeId,
        claimedHops = relayHops,
    )

    private fun armCallerRevealDeadline(reveal: Bmh3RevealSession, acceptedAtMs: Long) {
        thread(name = "CallerRevealDeadline", isDaemon = true) {
            try {
                Thread.sleep(CallerRevealGate.deadlineMs(revealDeadlineHops()))
            } catch (_: InterruptedException) {
                return@thread
            }
            synchronized(this) {
                if (!callRunning.get() || contentSession?.reveal !== reveal) return@thread
                if (!CallerRevealGate.expired(reveal.peerRevealRequired(), acceptedAtMs, SystemClock.elapsedRealtime(), revealDeadlineHops())) return@thread
                Bmh3Android.log("bmh3: event=reveal | role=callee | result=deadline")
                localFailureMessage = CallerRevealGate.MESSAGE
                runCatching { hangUp() }
            }
        }
    }

    /** The peer's reveal: signature and session binding, then the verified-contact gate and (caller) the discovery binding. */
    private fun handleReveal(record: DiscoveryExchange.IdentityReveal) {
        val endpoint = contentSession ?: error("Unexpected identity reveal")
        val session = endpoint.reveal ?: error("Unexpected identity reveal")
        val role = if (session.localRole == Bmh3Reveal.ROLE_CALLER) "caller" else "callee"
        // The same reveal again after a link renewal (the peer's writer had not flushed it): already handled.
        if (session.isRepeatOfVerified(record)) return
        try {
            val verified = session.onPeerReveal(record)
            // Throws when a verified contact's prefix shows another key (refused before this phone reveals anything).
            val revealedFingerprint = PeerIdentityGate.admit(verified.nodeId, verified.spki)
            // Review 2026-10-27 (finding 2): a block follows the real identity, which a stranger's call shows only now.
            // Checked before anything else goes to the person: no label, no presence session, and (caller) no reveal.
            if (PersonPolicy.blocked(revealedFingerprint)) {
                Bmh3Android.log("bmh3: event=reveal | role=$role | result=blocked")
                callerNames.onPeerBlocked()
                calleeNames.onPeerBlocked()
                endBlockedAfterReveal(session.localRole)
                return
            }
            revealedPeerFingerprint = revealedFingerprint
            val revealedVerifiedContact = runCatching {
                PeerIdentityGate.store?.state(verified.nodeId, revealedFingerprint) == VerificationState.VERIFIED
            }.getOrDefault(false)
            // Callee, a stranger caller: the held name goes only now, after the block check above ("Only people I verified":
            // only to a verified contact; audit CALL-06: "Everyone" with the Experimental hold, to any unblocked person).
            if (session.localRole == Bmh3Reveal.ROLE_CALLEE) {
                calleeNames.onRevealVerified(revealedFingerprint, revealedVerifiedContact, PeerLabelFeature.audience)
            }
            awaitedReveal?.let { wait ->
                val result = CallEndpointOpening.check(
                    wait.selected, verified.nodeId, endpoint.peerNodeId, direct = false, opening = verified.entries,
                )
                logRotatingOpening("call_reveal", result, wait.selected, verified.nodeId)
                require(result == DiscoverySelectionCheck.Result.PASS) { "Call peer identity changed between discovery and connection" }
                awaitedReveal = null
                markRelayRouteVerified()
            }
            // Design decision 2026-11-06: the caller's held name goes only now, after the selected-offer binding above
            // passed (when there was one), only if the revealed person is not blocked (checked again inside, against the
            // newest list) and only as the newest "Who sees my name" allows. A callee never started a caller name.
            if (session.localRole == Bmh3Reveal.ROLE_CALLER) {
                callerNames.onRevealVerified(revealedFingerprint, revealedVerifiedContact, PeerLabelFeature.audience)
            }
            Bmh3Android.log("bmh3: event=reveal | role=$role | result=verified")
        } catch (error: Exception) {
            Bmh3Android.log("bmh3: event=reveal | role=$role | result=${error.javaClass.simpleName}")
            throw error
        }
        // The call is now with a person: presence leases may be offered to a verified contact, and the call screen,
        // the history and the verification flow see the real identity (so does the name already received).
        SessionPeerLabels.move(endpoint.peerNodeId.shortLabel(), endpoint.effectivePeerNodeId.shortLabel())
        presenceSession = newPresenceSession(endpoint.effectivePeerNodeId, endpoint.effectivePeerFingerprint, "reveal")
        if (session.localRole == Bmh3Reveal.ROLE_CALLEE) {
            if (calleeMedia?.onRevealAdmitted() == true) {
                Bmh3Android.log("bmh3: event=reveal | role=callee | local_media=started")
                startLocalMedia()
            }
            startPresenceSessionAsync()
        } else if (session.callerMayReveal()) {
            // The caller reveals only now: the callee proved it owns the offer this phone selected.
            controls.offerMust(OutboundControl.Reveal { session.sendOwn(identity, emptyList()) })
            wifiHop?.onRevealProgress()
            Bmh3Android.log("bmh3: event=reveal | role=caller | result=sent")
        }
        wifiHop?.onRevealProgress()
        onStatus("The other phone's identity is confirmed: ${endpoint.effectivePeerNodeId.shortLabel()}")
    }

    /**
     * The revealed person is on this phone's block list. Nothing more goes to them: no media (this phone's media stays held
     * or stops, theirs is dropped), no label, no presence session and, for a caller, no reveal of its own. After the same
     * human-like delay as a blocked call's decline ([CallBlocking.declineDelayMs]) this phone ends the call with the
     * ordinary hang-up (a caller: the ordinary cancel), so the end does not say "blocked" (review 2026-10-27, finding 9).
     * The callee remembers a blocked call that names nobody. A caller's name is still held at this point and never goes
     * ([CallerNameRelease]); only the Experimental `names_before_answer` arm sends it before the ring. A callee answers
     * first (callee-first REVEAL), so the caller it answered already holds the callee's identity (a protocol property).
     * AB3: the same path ends a call whose callee showed a blocked person's real key in the endpoint handshake
     * ([after] = "handshake"; the caller found it before its INVITE, so no reveal is involved).
     */
    private fun endBlockedAfterReveal(localRole: Int, after: String = "reveal") {
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_blocking: event=ended | reason=blocked_person | after=$after | role=" +
                if (localRole == Bmh3Reveal.ROLE_CALLER) "caller" else "callee",
        )
        calleeMedia?.onBlocked()
        blockedCall.block()
        onBlockedEndScheduled()
        localFailureMessage = BLOCKED_AFTER_REVEAL_MESSAGE
        val delayMs = CallBlocking.declineDelayMs()
        thread(name = "BlockedAfterRevealEnd", isDaemon = true) {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return@thread
            }
            synchronized(this) {
                if (callRunning.get()) runCatching { hangUp() }
            }
        }
    }

    private fun handleDiscoveryContent(payload: ByteArray) {
        if (payload.size > DiscoveryExchange.MAGIC.size && payload[DiscoveryExchange.MAGIC.size].toInt() == DiscoveryExchange.KIND_IDENTITY_REVEAL) {
            // A reveal decides whether the call goes on, so unlike the other records a malformed one ends the call.
            val record = DiscoveryExchange.decode(payload) as? DiscoveryExchange.IdentityReveal ?: error("Unexpected identity reveal")
            handleReveal(record)
            return
        }
        // AB3: nothing from a person this call ended for (a blocked reveal or a blocked real key) is examined.
        if (blockedCall.active) return
        val session = presenceSession ?: return
        val endpoint = contentSession ?: return
        val record = try {
            DiscoveryExchange.decode(payload)
        } catch (error: IllegalArgumentException) {
            // Never fails the call (PROTOCOL Phase 2): an undecodable record is dropped and logged.
            Log.w("BluetoothVideo", "SECURITY DISCOVERY RECORD REJECTED reason=undecodable")
            return
        }
        // The real identity: after a pseudonym call's reveal the revealed contact (the throwaway is never a contact).
        val verifiedNow = endpoint.peerVerifiedIn { node, fingerprint -> PeerIdentityGate.store?.state(node, fingerprint) }
        // The vault and the verified-contact store touch the Keystore and the disk: not on the reader thread, and in the
        // order the session produced the actions (a LEASE never overtakes this side's own OFFER).
        presenceDispatch.dispatch({ session.onRecord(record, verifiedNow) }, runIfEmpty = false) { actions ->
            runCatching { runPresenceActions(actions, endpoint, session) }
        }
        // Design QL: the displayer answers the scanner's OFFER (it never offers first).
        if (record == DiscoveryExchange.RecognitionOffer && qrScope != null) qrDisplayerAnswer()
    }

    private fun logPresence(event: String, detail: String = "") {
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotid_presence: event=$event" +
                if (detail.isNotEmpty()) " | $detail" else "",
        )
    }

    /**
     * RV15: the check follows the advert this caller selected, never the peer's hello. Direct
     * version 2 calls keep the end-to-end prefix check in [establish]; message exchanges check the
     * link prefix here as before.
     */
    private fun checkSelectedPeer(
        selected: DiscoveryAnnouncement,
        link: SecureBluetoothSession,
        opening: List<OpeningEntry>?,
        messageSync: Boolean = false,
    ) {
        if (!selected.rotating && !messageSync) return
        // A relayed version 5 offer is verified end to end by the BDX1 OPENING (the relay cannot open the
        // callee's ID and its hello carries no bit 3).
        if (selected.rotating && selected.routeCost > 0 && !messageSync) return
        val result = DiscoverySelectionCheck.check(
            selected,
            RotatingDiscoveryIds.roleFor(selected),
            link.peerNodeId.toByteArray(),
            opening,
        )
        if (selected.rotating) {
            val detail = if (BuildConfig.DEBUG && result == DiscoverySelectionCheck.Result.PASS) {
                // DEBUG only: host tools map ID -> prefix; release never logs both (design section 6).
                " | rdi=${selected.nodeIdPrefix.joinToString("") { "%02x".format(it) }} | prefix=${link.peerNodeId.shortLabel()}"
            } else {
                ""
            }
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotid_open: role=call | result=$result$detail")
        }
        require(result == DiscoverySelectionCheck.Result.PASS) { "Peer identity changed between discovery and connection" }
    }

    /** RV8: a stranger's hello waits 2^(n-6) s (max 30 s) for the n-th silent handshake this epoch. */
    private fun strangerTarpit(peerNodeId: NodeId) {
        if (!rotatingIds) return
        if (PeerIdentityGate.store?.stateForNodeId(peerNodeId) == VerificationState.VERIFIED) return
        val delayMs = runCatching {
            RotatingDiscovery.tarpit.nextDelayMs(RotatingDiscovery.currentEpoch())
        }.getOrDefault(0L)
        if (delayMs >= 1_000L) {
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotid_tarpit: delayMs=$delayMs")
        }
        if (delayMs > 0L) Thread.sleep(delayMs)
    }

    private fun reportListenerFailure(error: Exception, generation: Int = listenerGeneration) {
        if (generation != listenerGeneration) return
        if (running.get() && state == null && !callRunning.get()) {
            val message = "Private-call listener stopped: ${error.message ?: error.javaClass.simpleName}"
            onStatus(message)
            finish(message)
        }
    }

    private fun fail(message: String) {
        val shown = if (answerDeadlineCallId != null && answerDeadlineCallId == state?.callId) {
            CallSetupDeadlines.callerAnswerMessage()
        } else {
            message
        }
        synchronized(this) {
            state?.let { current ->
                if (current.phase != PrivateCallPhase.ENDED) {
                    state = runCatching { current.transition(PrivateCallEvent.LINK_LOST) }
                        .getOrElse { current.copy(phase = PrivateCallPhase.ENDED) }
                    notifyPhase(requireNotNull(state))
                }
            }
        }
        finish(shown)
    }

    @Synchronized
    internal fun captureEndedRecentCall(message: String) {
        if (endedRecentCall == null && state != null && qrScanPayload == null) {
            val direction = if (state?.role == PrivateCallRole.CALLER) CallDirection.OUTGOING else CallDirection.INCOMING
            val stranger = recentStrangerName()
            val identity = when {
                // A stranger who revealed a blocked person: a blocked call, naming nobody (finding 2).
                blockedCall.active && direction == CallDirection.INCOMING ->
                    RecentCall("", false, 0, blocked = true, result = CallResult.DECLINED)
                stranger != null -> RecentCall("", localCallType == CallType.VIDEO, 0, strangerName = stranger)
                !peerStillPseudonym && endToEndPeerNodeId != null -> RecentCall(
                    endToEndPeerNodeId!!.take(12), localCallType == CallType.VIDEO, 0,
                    fingerprintHex = incomingCaller()?.fingerprint?.hex.orEmpty(),
                )
                else -> historyTarget
            }
            endedRecentCall = identity?.let { entry ->
                entry.copy(atMs = System.currentTimeMillis(), direction = direction,
                    result = if (entry.blocked) CallResult.DECLINED
                    else recentCallResult(direction, historyAnswered, historyDeclined, localFailureMessage ?: message))
            }
        }
    }

    private var recentCallTaken = false

    @Synchronized
    internal fun takeEndedRecentCall(): RecentCall? {
        if (recentCallTaken) return null
        return endedRecentCall?.also { recentCallTaken = true }
    }

    private fun finish(message: String) {
        incomingPhases?.cancelAll()
        captureEndedRecentCall(message)
        qrGate?.fail()
        qrGate = null
        qrScope = null
        callRunning.set(false)
        contentSession?.peerNodeId?.shortLabel()?.let(SessionPeerLabels::clear)
        contentSession?.effectivePeerNodeId?.shortLabel()?.let(SessionPeerLabels::clear)
        bufferedLabels = null
        pendingAcceptLabels = null
        calleeNames.reset()
        callLabels = null
        reportDebugTx(final = true)
        reportFragmentStatsIfDue(force = true)
        pendingAudioPackets.clear()
        media.clear()
        controls.clear()
        mediaReceiver?.close()
        mediaReceiver = null
        if (transport == BluetoothTransportOption.BLE_GATT) {
            gattServer?.close()
            gattServer = null
        }
        dualSecondary?.close()
        dualSecondary = null
        renewalScanner?.stop()
        renewalScanner = null
        renewalServers.closeAll()
        wifiHop?.onCallEnded()
        wifiHop = null
        wifiRecordHandler = null
        markerCodec = CallMarkerCodec
        // The set first: its threads see `closed` before the Bluetooth link is torn down (step 10).
        carriers?.close()
        carriers = null
        contentSink = null
        extraCarrierStates.clear()
        pendingCocSocket?.let { runCatching { it.close() } }
        connection?.close()
        connection = null
        // Review A2: the CoC control (PHY/ACL) connection of this call is released with it.
        bleControlConnection?.close()
        bleControlConnection = null
        secureSession?.close()
        secureSession = null
        endedPeerNodeIdHex = contentSession?.takeIf { !it.peerIsPseudonym }?.effectivePeerNodeId?.toString()
        contentSession?.close()
        contentSession = null
        writerThread?.interrupt()
        readerThread?.interrupt()
        // A BVMID2 verification scan is not a call: its end opens no screen (MainActivity treats it as a quiet end).
        if (qrScanPayload != null) endedQuietly = true
        if (endedCallbackSent.compareAndSet(false, true)) onEnded(localFailureMessage ?: message)
    }

    // ----- Experimental relayed calls (DECISIONS Design C) -----

    /** The user's own activity ends a relay in progress at once (PLAN C5). True when one was running. */
    fun preemptRelay(): Boolean {
        val relay = relayDuty ?: return false
        relay.close()
        return true
    }

    private fun runRelaySetup(
        link: SecureBluetoothSession,
        activeConnection: CallConnection,
        route: RelayRoute,
        helperOpening: List<OpeningEntry>?,
    ) {
        route.seek?.let { seek ->
            runSeekRequest(link, activeConnection, route, seek, helperOpening)
            return
        }
        val spentMs = (System.nanoTime() - relaySetupStartedNs) / 1_000_000L
        val remaining = (RelayedCalls.SETUP_DEADLINE_MS - spentMs).coerceIn(0L, RelayedCalls.SETUP_DEADLINE_MS.toLong()).toInt()
        try {
            val relays = RelaySetupExchange.requestRoute(
                link, route, remaining, helperOpening,
                acceptPseudonymousHelper = DebugTunables.relayedPseudonymousEdges,
            ) { activeConnection.close() }
            val required = RelayedCallsFeature.requireRelays
            if (required > 0 && relays != required) {
                throw RelaySetupFailedException(null, "The route used $relays relays, but exactly $required were required")
            }
            relayHops = relays
            relayReadyThisAttempt = true
            activeSettings = routeSettings(relayHops)
            relayRouteUnverified = route
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "relayed_setup: role=caller | result=ready | budget=${route.relays} | relays=$relays",
            )
        } catch (error: RelaySetupFailedException) {
            relayHelper?.routeOutcome(route, false, error.reason)
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "relayed_setup: role=caller | result=failed | budget=${route.relays} | reason=${error.reason?.label ?: "invalid"}",
            )
            throw error
        }
    }

    /**
     * Design OR (DEBUG prototype): one request of the caller's search. Its remaining time is `min(20 s, attempt end - now -
     * 2 s)` of the one attempt deadline; a READY ends the attempt as found, everything else goes back to the rings.
     */
    private fun runSeekRequest(
        link: SecureBluetoothSession,
        activeConnection: CallConnection,
        route: RelayRoute,
        seek: RelaySeekRoute,
        helperOpening: List<OpeningEntry>?,
    ) {
        val request = seek.request
        val remaining = seek.attempt.rings.remainingNow()
        if (remaining < RelayedCalls.MIN_REMAINING_MS) {
            throw RelaySetupFailedException(RelaySetupFailure.TIMEOUT, RelaySetupFailure.TIMEOUT.userText)
        }
        val nonce = RelaySeek.newNonce()
        val message = RelaySetupMessage.Seek(
            request.budget, remaining.toInt(), RelaySetupCodec.newSetupId(), nonce,
            RelaySeek.token(nonce, request.selector.rdi), request.skip,
        )
        val prefix = if (BuildConfig.DEBUG) "BENCHMARK " else ""
        Log.i(
            "BluetoothVideo",
            prefix + RelaySeekLog.line("caller", "sent", request.ring, request.budget, request.selector.kind, candidate = seek.attempt.rings.requests),
        )
        val relays = RelaySetupExchange.requestSeek(link, route, message, helperOpening) { activeConnection.close() }
        val required = RelayedCallsFeature.requireRelays
        if (required > 0 && relays != required) {
            throw RelaySetupFailedException(null, "The route used $relays relays, but exactly $required were required")
        }
        relayHops = relays
        relayReadyThisAttempt = true
        activeSettings = routeSettings(relayHops)
        seek.attempt.record(seek, RelaySeekRings.Outcome.READY, seekDialsThisRequest)
        seek.attempt.finish(found = true)
        Log.i(
            "BluetoothVideo",
            prefix + RelaySeekLog.line("caller", "ready", request.ring, request.budget, request.selector.kind, dials = seekDialsThisRequest) +
                " | relays=$relays",
        )
    }

    /**
     * A link accepted by a call endpoint that has not finished both handshakes within
     * [DualSetupBudget.INCOMING_HANDSHAKE_MS] is closed, so a silent stranger on any door cannot hold the endpoint
     * (on a dual endpoint a GATT central enabling notifications takes the CoC listener and the advertisement with it).
     * Every CoC and GATT endpoint since Q29 (2026-10-14); see [CallSetupDeadlines.incomingDeadlineApplies].
     */
    private fun startIncomingDeadline(activeConnection: CallConnection, dualEndpoint: Boolean) {
        thread(name = "DualIncomingDeadline", isDaemon = true) {
            try {
                Thread.sleep(DualSetupBudget.INCOMING_HANDSHAKE_MS)
            } catch (_: InterruptedException) {
                return@thread
            }
            if (running.get() && callRunning.get() && connection === activeConnection && contentSession == null) {
                if (dualEndpoint) DualListeningRuntime.log("dual_listening: event=incoming_deadline | closed=true")
                Log.i(
                    "BluetoothVideo",
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") + CallSetupDeadlines.incomingDeadlineLine(dualEndpoint),
                )
                runCatching { activeConnection.close() }
            }
        }
    }

    /**
     * Design PP package A: one thread per armed phase timer. At the deadline it settles the timer; only a timer that was still
     * armed closes the one connection it was armed for (silently, as the 20 s deadline does), and the endpoint's normal end
     * handling restores availability. A milestone that arrived first has disarmed the timer, so nothing happens.
     */
    private fun startIncomingPhaseTimer(
        phases: IncomingPhaseTimers,
        timer: PhaseTimer,
        activeConnection: CallConnection,
        armedTransport: BluetoothTransportOption,
    ) {
        thread(name = "IncomingPhase_${timer.name}", isDaemon = true) {
            try {
                timer.awaitDue()
            } catch (_: InterruptedException) {
                return@thread
            }
            if (!timer.expireIfDue()) return@thread
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") + phases.deadlineLine(timer, armedTransport, activeConnection.carrierKind),
            )
            runCatching { activeConnection.close() }
        }
    }

    private fun logIncomingMilestone(phases: IncomingPhaseTimers, milestone: IncomingMilestone) {
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK " + phases.milestoneLine(milestone))
    }

    /** A helper closes an accepted link that has not finished the handshake and delivered its first record. */
    private fun startPreSetupWatchdog(activeConnection: CallConnection) {
        preSetupWatchdog = thread(name = "PrivateCallPreSetupWatchdog", isDaemon = true) {
            try {
                Thread.sleep(RelayedCalls.PRE_SETUP_WAIT_MS)
            } catch (_: InterruptedException) {
                return@thread
            }
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_setup: role=helper | result=pre_setup_timeout",
            )
            runCatching { activeConnection.close() }
        }
    }

    /** The caller as known before the call rings (and after it, once a stranger revealed its real key). */
    fun incomingCaller(): IncomingCaller? {
        val session = contentSession ?: return null
        return if (session.peerIsPseudonym) IncomingCaller(true, null) else IncomingCaller(false, session.effectivePeerFingerprint)
    }

    /**
     * Call blocking: when the gate refuses this caller, answers with the ordinary Ringing, declines through the
     * normal decline after a short random delay and returns Unit; null means the call rings as usual.
     */
    private fun declineSilentlyIfBlocked(callId: UUID): Unit? {
        val caller = incomingCaller() ?: return null
        val decision = callGate.check(caller)
        if (decision == CallGateDecision.RING) return null
        endedQuietly = true
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_blocking: event=declined | reason=" +
                (if (decision == CallGateDecision.DECLINE_BLOCKED_PERSON) "blocked_person" else "unknown_caller") +
                " | pseudonymous=${caller.pseudonymous}",
        )
        if (decision == CallGateDecision.DECLINE_UNKNOWN) {
            runCatching { RecentCalls.recordBlocked(appContext, System.currentTimeMillis()) }
        }
        enqueueSignal(PrivateCallSignal.RINGING)
        // From the caller's side this was a ringing call, not a probe (RV8 tarpit counts only silent handshakes).
        if (rotatingIds) runCatching { RotatingDiscovery.tarpit.forgive(RotatingDiscovery.currentEpoch()) }
        val delayMs = CallBlocking.declineDelayMs()
        thread(name = "BlockedCallDecline", isDaemon = true) {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return@thread
            }
            synchronized(this) {
                val current = state ?: return@thread
                if (current.callId != callId || current.phase != PrivateCallPhase.INCOMING_RINGING) return@thread
                runCatching { decline(PrivateCallReason.REJECTED) }
            }
        }
        return Unit
    }

    /** F14: this incoming call was declined as busy because the user was dialling someone else (the call screen never showed). */
    @Volatile var endedBusyWhileDialling = false
        private set

    /** The 12-hex real prefix of the caller declined as busy, null for a stranger; for the notice only. */
    @Volatile var busyDeclinedCaller: String? = null
        private set

    /**
     * F14 ([DialCollision]): a call that arrives while the user's own dial is still looking for its person. A call from
     * that person rings (it is the conversation the user wanted); any other is declined at once with the existing reason
     * BUSY, quietly (no ring, no call screen), and the endpoint is rebuilt so the dial can go on. Null: ring as usual.
     */
    private fun declineBusyIfDialling(): Unit? {
        val dial = runCatching { DialCollision.ownDial() }.getOrNull() ?: return null
        val session = contentSession
        val callerPrefix = session?.takeIf { !it.peerIsPseudonym }?.effectivePeerNodeId?.shortLabel()
        val action = DialCollision.decide(dial, callerPrefix)
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + DialCollision.line(action, callerPrefix != null))
        if (action == DialCollision.Action.RING) return null
        endedQuietly = true
        endedBusyWhileDialling = true
        busyDeclinedCaller = callerPrefix
        runCatching { decline(PrivateCallReason.BUSY) }
        return Unit
    }

    /**
     * F19: an unanswered call ends. The caller cancels after [CallRingTimeout.CALLER_MS] of ringing ("No answer"); the
     * callee declines after [CallRingTimeout.CALLEE_MS] ("Missed call"). Existing signals only.
     */
    private fun scheduleRingTimeout(callId: UUID, outgoing: Boolean) {
        if (!DebugTunables.callRingTimeout) return
        val since = ringingSinceMs ?: return
        thread(name = "CallRingTimeout", isDaemon = true) {
            try {
                Thread.sleep(if (outgoing) CallRingTimeout.CALLER_MS else CallRingTimeout.CALLEE_MS)
            } catch (_: InterruptedException) {
                return@thread
            }
            synchronized(this) {
                val current = state ?: return@thread
                if (current.callId != callId || ringingSinceMs != since) return@thread
                val nowMs = SystemClock.elapsedRealtime()
                val expired = if (outgoing) {
                    CallRingTimeout.callerExpired(true, current.phase, since, nowMs)
                } else {
                    CallRingTimeout.calleeExpired(true, current.phase, since, nowMs)
                }
                if (!expired) return@thread
                Log.i(
                    "BluetoothVideo",
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                        "call_ring_timeout: role=${if (outgoing) "caller" else "callee"} | rangMs=${nowMs - since}",
                )
                if (outgoing) {
                    localFailureMessage = CallRingTimeout.NO_ANSWER_MESSAGE
                    runCatching { cancel() }
                } else {
                    localFailureMessage = CallRingTimeout.MISSED_MESSAGE
                    runCatching { decline(PrivateCallReason.REJECTED) }
                }
            }
        }
    }

    /** A relayed incoming call that rings unanswered this long is declined, so ringing cannot hold helpers. */
    private fun scheduleRelayedRingingCap(callId: UUID) {
        thread(name = "RelayedRingingCap", isDaemon = true) {
            try {
                Thread.sleep(RelayedCalls.relayedRingingCapMs())
            } catch (_: InterruptedException) {
                return@thread
            }
            synchronized(this) {
                val current = state ?: return@thread
                if (current.callId != callId || current.phase != PrivateCallPhase.INCOMING_RINGING) return@thread
                Log.i(
                    "BluetoothVideo",
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_call: ringing_cap_declined",
                )
                // Review A4: unanswered, not refused by the user: a missed call on this phone.
                localFailureMessage = CallRingTimeout.MISSED_MESSAGE
                runCatching { decline(PrivateCallReason.REJECTED) }
            }
        }
    }

    /** Handles the SETUP that arrived as the first record: refuse it, or relay the call for its caller. */
    private fun serveRelaySetup(link: SecureBluetoothSession, activeConnection: CallConnection, record: SecureRecord) {
        val host = relayHelper
        val decoded = runCatching {
            RelaySetupCodec.decode(
                record.payload,
                acceptRdi = rotatingIds && RelayedCallsFeature.rotatingCompat,
                acceptSeek = rotatingIds && RelaySeekFeature.active() && !link.peerPseudonymous && !link.ownPseudonymous,
            )
        }.getOrNull()
        if (decoded is RelaySetupMessage.Seek && host != null && linkTransportOverride == null &&
            RelayedCalls.linkUnderstandsSetup(link.peerCapabilities)
        ) {
            serveSeek(link, activeConnection, host, decoded.budget, decoded.remainingMs, decoded.setupId, decoded, null)
            return
        }
        val setup = decoded as? RelaySetupMessage.Setup
        // Experimental dual listening: a relay duty runs on the endpoint's own transport only (PROTOCOL).
        if (host == null || setup == null || !RelayedCalls.linkUnderstandsSetup(link.peerCapabilities) ||
            linkTransportOverride != null
        ) {
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_setup: role=helper | result=invalid",
            )
            runCatching { activeConnection.close() }
            endRelayDutyNow("Ignored an invalid relay request")
            return
        }
        val edges = DebugTunables.relayedPseudonymousEdges
        val parentKey = if (edges) {
            RelayIdentityPolicy.limiterKey(link.peerPseudonymous || link.ownPseudonymous, activeConnection.address)
        } else {
            null
        }
        val admission = if (RelayIdentityPolicy.refusesSetupMessage(edges, rotatingIds, setup.rotating)) {
            RelayAdmission.Refuse(RelaySetupFailure.REFUSED)
        } else {
            host.admit(link.peerNodeId, setup.target, setup.budget, transport, setup.rotating, parentKey = parentKey)
        }
        when (admission) {
            is RelayAdmission.Search -> {
                serveSeek(link, activeConnection, host, admission.budget, setup.remainingMs, setup.setupId, null, admission.target)
            }
            is RelayAdmission.Refuse -> {
                runCatching { RelaySetupExchange.writeMessage(link, RelaySetupMessage.Fail(setup.setupId, admission.reason)) }
                Log.i(
                    "BluetoothVideo",
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                        "relayed_setup: role=helper | result=refused | budget=${setup.budget} | reason=${admission.reason.label}",
                )
                // The reply needs a moment to leave before the link closes.
                thread(name = "RelaySetupRefuse", isDaemon = true) {
                    try {
                        Thread.sleep(REFUSE_DRAIN_MS)
                    } catch (_: InterruptedException) {
                        // closing now is fine
                    }
                    endRelayDutyNow("Refused a relay request (${admission.reason.label})")
                }
            }
            is RelayAdmission.Accept -> {
                if (!running.get()) {
                    runCatching { activeConnection.close() }
                    return
                }
                val relay = BluetoothPrivateCallRelay(
                    onStatus = { message -> Log.i("BluetoothVideo", message) },
                    onStopped = { relayDutyStopped() },
                )
                relayDuty = relay
                host.relayStarted(setup.budget)
                Log.i(
                    "BluetoothVideo",
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                        "relayed_setup: role=helper | result=accepted | budget=${setup.budget} | next_budget=${admission.nextBudget} | " +
                        "next=${RelayHopPolicy.helperNextHop(admission.nextBudget, admission.nextHop.announcement.nodeIdPrefix, admission.hopPrefix)}",
                )
                // Experimental test_relay_coc_coalesce: this call link is now a relay hop (default off: no change).
                ForwardingCocCoalescing.applyToHandedOffLink(activeConnection.output, "call-relay-parent")?.let { Log.i("BluetoothVideo", it) }
                relay.startOnDemand(
                    appContext,
                    adapter,
                    admission.nextHop,
                    transport,
                    BluetoothPrivateCallRelay.OnDemandParent(link, activeConnection::close, activeConnection.phySummary),
                    setup.target,
                    admission.nextBudget,
                    admission.hopPrefix,
                    setup.remainingMs,
                    rotating = setup.rotating,
                    onReady = { relays ->
                        RelaySetupExchange.writeMessage(link, RelaySetupMessage.Ready(setup.setupId, relays))
                        Log.i(
                            "BluetoothVideo",
                            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_setup: role=helper | result=ready | relays=$relays",
                        )
                    },
                    onFailed = { reason ->
                        runCatching { RelaySetupExchange.writeMessage(link, RelaySetupMessage.Fail(setup.setupId, reason)) }
                        Log.i(
                            "BluetoothVideo",
                            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_setup: role=helper | result=failed | reason=${reason.label}",
                        )
                    },
                )
            }
        }
    }

    /**
     * Design OR (DEBUG prototype, revision 3 helper rules 1-12): a SEEK ([seek]) or a plain SETUP without a table route
     * ([plainId]) is searched for under the phone-wide relay duty and budgets; a FAIL replayed for the same search within
     * 60 s is answered from the cache with this request's setup ID, without work.
     */
    private fun serveSeek(
        link: SecureBluetoothSession,
        activeConnection: CallConnection,
        host: RelayHelperHost,
        budget: Int,
        remainingMs: Int,
        setupId: ByteArray,
        seek: RelaySetupMessage.Seek?,
        plainId: ByteArray?,
    ) {
        val prefix = if (BuildConfig.DEBUG) "BENCHMARK " else ""
        val refuse = { reason: RelaySetupFailure, result: String, detail: String? ->
            runCatching { RelaySetupExchange.writeMessage(link, RelaySetupMessage.Fail(setupId, reason)) }
            Log.i("BluetoothVideo", prefix + RelaySeekLog.line("helper", result, budget = budget, reason = detail ?: reason.label))
            thread(name = "RelaySeekRefuse", isDaemon = true) {
                try {
                    Thread.sleep(REFUSE_DRAIN_MS)
                } catch (_: InterruptedException) {
                    // closing now is fine
                }
                endRelayDutyNow("Refused a relay search (${reason.label})")
            }
        }
        seek?.let { RelaySeekHelperState.failCache.lookup(it) }?.let { cached ->
            refuse(cached, "cached", null)
            return
        }
        when (val admission = host.admitSeek(budget, remainingMs, transport)) {
            is SeekAdmission.Refuse -> refuse(admission.reason, "refused", admission.detail)
            is SeekAdmission.Accept -> {
                if (!running.get()) {
                    RelayDutyReservation.shared.release(admission.owner)
                    runCatching { activeConnection.close() }
                    return
                }
                val relay = BluetoothPrivateCallRelay(
                    onStatus = { message -> Log.i("BluetoothVideo", message) },
                    onStopped = { relayDutyStopped() },
                )
                relayDuty = relay
                relayDutyOwner = admission.owner
                host.relayStarted(budget)
                Log.i(
                    "BluetoothVideo",
                    prefix + RelaySeekLog.line("helper", "accepted", budget = budget) +
                        " | allowance_ms=${admission.allowanceMs} | plain=${plainId != null}",
                )
                // Experimental test_relay_coc_coalesce: this call link is now a relay hop (default off: no change).
                ForwardingCocCoalescing.applyToHandedOffLink(activeConnection.output, "call-relay-parent")?.let { Log.i("BluetoothVideo", it) }
                relay.startSeek(
                    appContext,
                    adapter,
                    transport,
                    BluetoothPrivateCallRelay.OnDemandParent(link, activeConnection::close, activeConnection.phySummary),
                    budget,
                    seek?.nonce,
                    seek?.token,
                    plainId,
                    seek?.skip.orEmpty(),
                    admission.allowanceMs,
                    host,
                    admission.owner,
                    onReady = { relays ->
                        RelaySetupExchange.writeMessage(link, RelaySetupMessage.Ready(setupId, relays))
                        Log.i("BluetoothVideo", prefix + RelaySeekLog.line("helper", "ready", budget = budget) + " | relays=$relays")
                    },
                    onFailed = { reason ->
                        runCatching { RelaySetupExchange.writeMessage(link, RelaySetupMessage.Fail(setupId, reason)) }
                        seek?.let { RelaySeekHelperState.failCache.store(it, reason) }
                    },
                )
            }
        }
    }

    /** Design OR: the relay-duty owner of the search this endpoint serves (released when the duty stops). */
    @Volatile private var relayDutyOwner: Any? = null

    private fun relayDutyStopped() {
        relayDutyOwner?.let { RelayDutyReservation.shared.release(it) }
        relayDutyOwner = null
        if (relayDuty == null) return
        relayDuty = null
        relayHelper?.relayEnded()
        endRelayDutyNow("Stopped helping a call nearby")
    }

    /** A relay duty (or a refused request) used this endpoint: the activity restarts availability quietly. */
    private fun endRelayDutyNow(message: String) {
        endedFromRelayDuty = true
        finish(message)
    }

    private fun stopAvailability(forActiveConnection: Boolean) {
        wifiOnlyListening = false
        // A Visible-by-name window shows nothing while this phone is in a connection or not listening.
        closeWindowAdvertiser()
        if (!forActiveConnection) fullAvailabilityStops++
        // Rotating IDs: the call-offer ID leaves the air; it stays openable for 60 s (OpeningPins).
        if (rotatingAdvert) RotatingDiscovery.offAir(RotatingDiscoveryIds.ROLE_CALL_OFFER)
        WifiDiscovery.offers.clear(WifiOfferRole.CALL_OFFER)
        serverSocket?.let { active ->
            serverSocket = null
            runCatching { active.close() }
        }
        cocListener?.let { active ->
            cocListener = null
            runCatching { active.close() }
        }
        classicAdvertiser?.close()
        classicAdvertiser = null
        if (forActiveConnection && transport == BluetoothTransportOption.BLE_L2CAP) {
            cocControlServer?.stopAdvertising()
        } else {
            cocControlServer?.close()
            cocControlServer = null
        }
        if (!forActiveConnection) {
            gattServer?.close()
            gattServer = null
        }
        // Experimental dual listening: the second listener goes with the availability, except while it
        // carries the accepted link (a GATT link on a CoC endpoint; finish() closes it then).
        if (!forActiveConnection || linkTransportOverride == null) {
            dualSecondary?.close()
            dualSecondary = null
        }
        listenerThread?.interrupt()
        listenerThread = null
    }

    override fun close() {
        presenceExecutor?.shutdown()
        debugStageTrace?.close()
        debugStageTrace = null
        if (!running.getAndSet(false)) return
        listenerStartFence.stop {
            BluetoothEpoch.unregister(listenerEpochName)
            if (rotatingAdvert) RotatingDiscovery.removeBoundaryListener(boundaryListener)
            NameWindowRuntime.removeListener(windowListener)
        }
        // Experimental relayed calls: closing the endpoint (screen change, switch off) ends a relay duty too.
        relayDuty?.close()
        preSetupWatchdog?.interrupt()
        pendingCallConnection?.close()
        pendingCallConnection = null
        pendingClassicSocket?.let { runCatching { it.close() } }
        pendingClassicSocket = null
        pendingCocSocket?.let { runCatching { it.close() } }
        pendingCocSocket = null
        // Review A8: renewal listeners and the renewal scan end with the endpoint too.
        renewalScanner?.stop()
        renewalScanner = null
        renewalServers.closeAll()
        wifiHop?.onCallEnded()
        wifiHop = null
        wifiRecordHandler = null
        markerCodec = CallMarkerCodec
        carriers?.close()
        carriers = null
        contentSink = null
        extraCarrierStates.clear()
        callRunning.set(false)
        reportDebugTx(final = true)
        reportFragmentStatsIfDue(force = true)
        pendingAudioPackets.clear()
        listenerStartFence.stop { stopAvailability(forActiveConnection = false) }
        bleControlConnection?.close()
        bleControlConnection = null
        connectionThread?.interrupt()
        writerThread?.interrupt()
        readerThread?.interrupt()
        mediaReceiver?.close()
        mediaReceiver = null
        connection?.close()
        connection = null
        contentSession?.close()
        contentSession = null
        secureSession?.close()
        secureSession = null
    }

    private companion object {
        const val REFUSE_DRAIN_MS = 300L
        /** Design OR: how often a search request checks for the contact's own offer (the parallel direct check). */
        const val SEEK_DIRECT_POLL_MS = 250L
        const val CONSTRAINED_COC_AUDIO_BATCH_SIZE = 20
        const val WRITER_POLL_MS = 20L
        const val CALL_PACING_SLICE_NS = 5_000_000L
        /** Heartbeats carry a link report; the peer's rate control acts on its trend. */
        const val REPORT_INTERVAL_NS = 1_000_000_000L
        const val LINK_SAMPLE_NS = 1_000_000_000L
        /** Experimental link renewal: state-machine tick, old-writer join and the DEBUG tx-stall sleep slice. */
        const val RENEWAL_TICK_MS = 100L
        const val RENEWAL_JOIN_MS = 1_000L
        const val DEBUG_TX_STALL_SLICE_MS = 50L
        const val ACL_WAIT_JOIN_SLACK_MS = 500L
        const val RECOVERY_REQUEST_INTERVAL_NS = 750_000_000L
        const val DEBUG_TX_REPORT_INTERVAL_NS = 5_000_000_000L
        const val FRAGMENT_STATS_INTERVAL_NS = 1_000_000_000L
    }
}

internal fun storeForwardSyncMessage(result: StoreForwardSyncResult): String {
    val received = result.opened.size
    val base = when (result.outcome) {
        StoreForwardSyncOutcome.COMPLETE -> "Messages exchanged"
        StoreForwardSyncOutcome.SUSPENDED_LOCAL, StoreForwardSyncOutcome.SUSPENDED_BY_PEER ->
            "Message exchange paused for a call; it resumes next time"
        else -> "Message exchange interrupted; it resumes next time"
    }
    return "$base (${result.chunksSent} chunks sent, ${result.chunksReceived} received, $received new)"
}

/** What a caller sees when the callee declined with BUSY (F14). */
internal const val BUSY_DECLINE_MESSAGE = "Busy: they are calling someone else or are in another call. Try again in a moment."

/** A stranger's call whose reveal showed a person on this phone's block list (review 2026-10-27, finding 2). */
internal const val BLOCKED_AFTER_REVEAL_MESSAGE = "Call ended: this is someone you blocked."

internal fun privateCallConnectionFailureMessage(
    transport: BluetoothTransportOption,
    detail: String,
    bondFree: Boolean = ClassicFeatures.current.bondFree,
): String = if (transport == BluetoothTransportOption.CLASSIC_RFCOMM) {
    if (bondFree) {
        "Classic call could not reach this phone ($detail). Both phones need Connect without pairing turned " +
            "on, and a phone this one has not met must be visible. Or switch both phones to BLE GATT."
    } else {
        "Classic call could not reach this phone ($detail). Pair the phones once, turn on Connect without " +
            "pairing (Settings > Features) on both phones, or switch both phones to BLE GATT."
    }
} else {
    "Call could not connect: ${plainCallConnectDetail(detail)}"
}

/**
 * The reason a BLE call setup failed, in words a user can act on. GATT status 133 (GATT_ERROR) after the 2M -> 1M retry is
 * what a phone that is restarting, busy in another call or just out of range gives; the raw detail stays in the log.
 */
internal fun plainCallConnectDetail(detail: String): String =
    if (Regex("""status[ =]?133\b""").containsMatchIn(detail)) {
        "their phone did not accept the Bluetooth connection. It may be in another call, restarting the app or " +
            "out of range. Try again in a moment."
    } else {
        detail
    }

/** An unresolved Classic peer explains itself; anything else keeps the transport's message. */
internal fun privateCallConnectionFailureMessage(
    transport: BluetoothTransportOption,
    error: Throwable,
    bondFree: Boolean = ClassicFeatures.current.bondFree,
): String = (error as? ClassicPeerUnresolvedException)?.message
    ?: privateCallConnectionFailureMessage(transport, error.message ?: error.javaClass.simpleName, bondFree)

/**
 * Second record of a call's end, logged from the UI thread after the call has been torn down.
 * The first one ("Private call ended: ...") is logged on the reader/writer thread in the same
 * millisecond as the burst of final reports; on ASUS (Android 11 logd) that window repeatedly lost
 * log lines from several threads at once (calls-noloss-m31asus-51cc8028/0007), although the end had
 * been handled. The text deliberately differs from the first record so it is never counted as a
 * second unexpected termination; the benchmark runner/scorer accept either as the same evidence.
 */
internal fun privateCallEndedUiEvidence(message: String): String =
    "BENCHMARK call ended_ui: message=$message"

/**
 * The first record of a call's end. DEBUG builds append ` | callId=<id>` (the same call ID as the `BENCHMARK call tx/rx`
 * lines), so a run with several calls binds each end to its call; the message itself stays the line's first field.
 */
internal fun privateCallEndedLine(message: String, callId: String?): String =
    "Private call ended: $message" + (callId?.let { " | callId=$it" } ?: "")

/** True once the call state reached ENDED (e.g. the peer's HANG_UP), before finish() has run. */
internal fun privateCallAlreadyEnded(phase: PrivateCallPhase?): Boolean = phase == PrivateCallPhase.ENDED

/**
 * Experimental call-link pacing applies to socket transports only: a GATT call write already blocks
 * for its own transmission, so its backlog is visible in the app queue without a pacer.
 */
internal fun callLinkPaced(
    transport: BluetoothTransportOption,
    callLinkPacing: Boolean,
    linkPacing: Boolean,
    /** A Wi-Fi carrier is not paced: it has no Bluetooth airtime to conserve. */
    carrier: CarrierKind = CarrierKind.BLUETOOTH,
): Boolean = callLinkPacing && linkPacing && !carrier.isWifi && transport != BluetoothTransportOption.BLE_GATT

/**
 * How the hang-up/decline/cancel writer waits before closing its link. A socket write only reaches
 * the stack's queue: the fixed 400 ms close lost the HANG_UP and ~1.3 s of media behind a 542 ms
 * CoC peer queue (xpair2-s24m31-431d40dc/0002: S24 closed 0.43 s after "control end applied"; the
 * M31 saw "Call link lost: EOFException"). The peer closes only after reading the in-order HANG_UP,
 * so the writer waits for its reader to see that close, capped. GATT closes at once (its writes
 * complete per packet). DEBUG `test_call_close_wait_peer=false` is the fixed-400 ms control arm.
 */
internal data class PrivateCallCloseWait(val waitForPeer: Boolean, val maxMs: Long)

internal const val PRIVATE_CALL_PEER_CLOSE_WAIT_MS = 3_000L
internal const val PRIVATE_CALL_FIXED_CLOSE_DRAIN_MS = 400L

internal fun privateCallCloseWait(
    transport: BluetoothTransportOption,
    waitForPeer: Boolean,
    /** A Wi-Fi socket write does not wait for the peer's close: the fixed drain. */
    carrier: CarrierKind = CarrierKind.BLUETOOTH,
): PrivateCallCloseWait =
    when {
        carrier.isWifi -> PrivateCallCloseWait(waitForPeer = false, maxMs = PRIVATE_CALL_FIXED_CLOSE_DRAIN_MS)
        transport == BluetoothTransportOption.BLE_GATT -> PrivateCallCloseWait(waitForPeer = false, maxMs = 0L)
        waitForPeer -> PrivateCallCloseWait(waitForPeer = true, maxMs = PRIVATE_CALL_PEER_CLOSE_WAIT_MS)
        else -> PrivateCallCloseWait(waitForPeer = false, maxMs = PRIVATE_CALL_FIXED_CLOSE_DRAIN_MS)
    }

internal data class PrivateCallCloseOutcome(val peerClosed: Boolean, val waitedMs: Long)

/** Waits per [wait]; [reader] ends when the peer closes the link. Interruptible like the old sleep. */
internal fun awaitPrivateCallClose(
    reader: Thread?,
    wait: PrivateCallCloseWait,
    nowNs: () -> Long = System::nanoTime,
): PrivateCallCloseOutcome {
    if (wait.maxMs <= 0L) return PrivateCallCloseOutcome(peerClosed = false, waitedMs = 0L)
    val startedNs = nowNs()
    if (wait.waitForPeer && reader != null && reader !== Thread.currentThread()) {
        reader.join(wait.maxMs)
    } else {
        Thread.sleep(wait.maxMs)
    }
    val peerClosed = wait.waitForPeer && reader != null && !reader.isAlive
    return PrivateCallCloseOutcome(peerClosed, (nowNs() - startedNs) / 1_000_000L)
}

internal fun canStartOutgoingPrivateCall(
    running: Boolean,
    state: PrivateCallState?,
    callRunning: Boolean,
): Boolean = running && state == null && !callRunning

/**
 * Send-queue room reserved for call audio: 3 s of 20 ms packets. A large video frame blocks the
 * call writer for its whole air time; in calls-noloss-m31asus-51cc8028/0002 (GATT) audio waited up
 * to 646 ms behind one write while queued video was at most 57 ms old, so 33 slots sized for 500 ms
 * of audio plus video filled with audio alone. The overflow then dropped every queued video frame
 * and forced a keyframe, whose own long write repeated the cycle (8, 60 and 15 frames dropped within
 * 4.2 s; 104 such recoveries in the 46 no-loss calls of calls-noloss-*-51cc8028). Dropping video
 * cannot shorten the write the audio waits for.
 */
internal fun privateCallAudioHeadroomPackets(
    @Suppress("UNUSED_PARAMETER") carrier: CarrierKind = CarrierKind.BLUETOOTH,
): Int = 150

/** True when queued non-audio records already fill [videoCapacity] (audio is not counted). */
internal fun <T> privateCallVideoBudgetFull(queue: Collection<T>, videoCapacity: Int, isAudio: (T) -> Boolean): Boolean =
    queue.size >= videoCapacity && queue.count { !isAudio(it) } >= videoCapacity

/** The oldest queued audio record, if any (the call writer is the only remover besides overflow). */
internal fun <T> takePrivateCallAudio(
    queue: ArrayBlockingQueue<T>,
    isAudio: (T) -> Boolean,
): T? {
    val waitingAudio = queue.firstOrNull(isAudio) ?: return null
    return if (queue.remove(waitingAudio)) waitingAudio else null
}

internal fun <T> pollNextPrivateCallMedia(
    queue: ArrayBlockingQueue<T>,
    timeoutMs: Long,
    isAudio: (T) -> Boolean,
): T? {
    val waitingAudio = queue.firstOrNull(isAudio)
    return if (waitingAudio != null && queue.remove(waitingAudio)) {
        waitingAudio
    } else {
        queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }
}

/** Pure gate for the one bounded 1M call-setup retry (adaptive PHY memory). */
internal fun adaptivePhySetupRetryEligible(
    enabled: Boolean,
    running: Boolean,
    le: Boolean,
    direct: Boolean,
    firstOnTwoM: Boolean,
    elapsedMs: Long,
): Boolean = enabled && running && le && direct && firstOnTwoM && elapsedMs in 0..AdaptivePhyMemory.EARLY_LOSS_MS

/**
 * The guarded first 2M attempt is stalled: [stallGuardMs] (0 = guard off) passed since the link opened
 * without hop authentication. Hop authentication on a healthy fresh link takes 0.1-0.3 s.
 */
internal fun adaptivePhySetupStalled(stallGuardMs: Long, authenticated: Boolean, sinceOpenMs: Long): Boolean =
    stallGuardMs > 0L && !authenticated && sinceOpenMs >= stallGuardMs

/**
 * AP7 F2 (`test_stall_slow_auth_ms`, Experimental, 0 = off): a hop authentication that finished on 2M no sooner than
 * [thresholdMs] after the link opened is a setup stall even though it succeeded. Only the guarded first 2M attempt passes
 * a non-zero threshold; the 1M retry and the no-guard control arm never take it.
 */
internal fun slowAuthStall(thresholdMs: Long, hopAuthMs: Long, onTwoM: Boolean): Boolean =
    thresholdMs > 0L && onTwoM && hopAuthMs >= thresholdMs

/** A fresh advertisement is the same call peer (same node prefix and route cost) for the 1M retry. */
internal fun matchesRediscoveredPeer(prefix: ByteArray, routeCost: Int, announcement: DiscoveryAnnouncement): Boolean =
    announcement.routeCost == routeCost && announcement.nodeIdPrefix.contentEquals(prefix)

/**
 * The 1M retry rescans only for a direct offer (route cost 0): its advertiser is the callee itself, so
 * the node prefix names the hop and the end-to-end handshake verifies it. A relayed offer's advertiser
 * is a relay that carries the far callee's prefix; any other relay at the same cost advertises the
 * same announcement, so a rescan could silently swap the requested parent. It keeps the original relay.
 */
internal fun adaptivePhyRetryRediscovers(guardOn: Boolean, routeCost: Int): Boolean = guardOn && routeCost == 0

/** The guarded first 2M attempt was closed before its hop authentication finished. */
internal class CallSetupStalledException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The answerer kept the link open and never finished the hop handshake ([CallSetupDeadlines.HOP_AUTH_MS]). */
internal class CallHandshakeTimeoutException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The answerer closed the link before the hop handshake finished (for example a refused old-style handshake). */
internal class CallHandshakeClosedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Who owns a guarded setup attempt: the setup thread when hop authentication finishes (or the attempt
 * ends) first, the stall guard when its deadline claims the still-pending attempt first. Exactly one
 * side wins, so a handshake completing just after the deadline is either kept whole or closed and
 * retried, never both, and an attempt that already ended is never logged as stalled.
 */
internal class CallSetupStallState {
    private val state = java.util.concurrent.atomic.AtomicInteger(PENDING)

    /** Hop authentication finished; false when the guard already closed the link. */
    fun authenticated(): Boolean = state.compareAndSet(PENDING, AUTHENTICATED) || state.get() == AUTHENTICATED

    /** The setup thread left establish (success or failure) without authentication. */
    fun ended() {
        state.compareAndSet(PENDING, ENDED)
    }

    /** The guard deadline passed: true only when the attempt was still pending (the guard closes it). */
    fun claimStall(): Boolean = state.compareAndSet(PENDING, STALLED)

    /** The hop-handshake deadline of an unguarded attempt passed: true only when it was still pending. */
    fun claimTimeout(): Boolean = state.compareAndSet(PENDING, TIMED_OUT)

    val stalled: Boolean get() = state.get() == STALLED

    val timedOut: Boolean get() = state.get() == TIMED_OUT

    /** Neither authenticated nor claimed by a guard nor ended: the hop handshake is still open (or just failed). */
    val pending: Boolean get() = state.get() == PENDING

    private companion object {
        const val PENDING = 0
        const val AUTHENTICATED = 1
        const val STALLED = 2
        const val ENDED = 3
        const val TIMED_OUT = 4
    }
}

/** The link ran on 2M: confirmed by the stack, or 2M was requested and no other PHY was ever reported. */
internal fun linkWasTwoM(phySummary: String, requestedTwoM: Boolean): Boolean =
    "TX 2M" in phySummary ||
        (requestedTwoM && "TX 1M" !in phySummary && "Coded" !in phySummary)

/**
 * When a call link stopped making progress: the earlier of the last received record and the last
 * writer flush that returned (a dead link stops both; a one-way stall stops one), never after [nowNs].
 */
internal fun callLinkStalledAtNs(lastReadNs: Long, lastSendNs: Long, nowNs: Long): Long {
    val known = listOf(lastReadNs, lastSendNs).filter { it > 0 }
    return (known.minOrNull() ?: nowNs).coerceAtMost(nowNs)
}

/** Early-loss window start: when the call became ACTIVE, or authentication while it has not. */
internal fun callEarlyLossWindowStartNs(authenticatedAtNs: Long, activeAtNs: Long): Long =
    if (activeAtNs > 0) activeAtNs else authenticatedAtNs
