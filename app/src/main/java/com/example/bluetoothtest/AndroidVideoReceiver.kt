package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import android.view.Surface
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AndroidVideoReceiver(
    private val surface: Surface,
    private val onVideoFormat: (StreamProtocol.Header) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onStreamActive: (Boolean) -> Unit,
    private val onStreamState: (StreamLifecycleState) -> Unit = {},
    private val preferLowLatencyDecoder: Boolean = true,
    private val playbackDelayProfile: PlaybackDelayProfile = PlaybackDelayProfile.BALANCED,
    private val playbackDelayMs: Long = playbackDelayProfile.delayMs,
    private val preferredDecoderName: String? = null,
    private val debugPacketDelayMs: Long = 0,
    private val debugTraceSessionName: String? = null,
    private val forwardReceivedStreams: Boolean = false,
    private val expectedMediaSourceNodeId: NodeId? = null,
    private val onMediaSourceAuthenticated: (NodeId, Boolean) -> Unit = { _, _ -> },
    private val onForwardedChildrenChanged: (Int) -> Unit = {},
    private val onVideoRecovery: (Boolean) -> Unit = {},
    /**
     * Experimental relay child thinning (DebugTunables.relayChildThinning): true while audio keeps arriving
     * but this parent sends no video (its relay put this link at the audio-only level), false when video
     * returns. Never called while the switch is off.
     */
    private val onVideoPausedWeakLink: (Boolean) -> Unit = {},
    private val onRouteWithdrawn: (StreamRouteWithdrawal) -> Unit = {},
    private val debugForwarderGattPriority: BleConnectionPriority? = null,
    private val debugDirectGattHeartbeat: Boolean = true,
    private val debugPresentationTiming: Boolean = false,
    private val debugReleaseAtPlayout: Boolean = true,
    /**
     * Experimental viewer frame age (Settings > Features, `test_viewer_frame_age`; null = off, and
     * nothing below changes): told what this link receives and renders so the stale-picture state and
     * the `viewer_frame_age` log can be computed (ViewerFrameAge.kt).
     */
    private val viewerFrameAge: ViewerFrameAgeMonitor? = null,
    /** Audio-only live (Design AL): true when this phone offers audio-only streams (the feature is on). */
    private val allowAudioOnly: Boolean = false,
    /** Audio-only live: the authenticated header of an audio-only stream (instead of onVideoFormat). */
    private val onAudioLiveStream: (StreamProtocol.Header) -> Unit = {},
    /** Audio-only live: the 0..1 level of the audio being played, for the listening meter. */
    private val onAudioLiveLevel: (Float) -> Unit = {},
    private val onStopped: (String?) -> Unit,
) : Closeable {
    private val running = AtomicBoolean(false)
    /** Audio-only live: the offer this receiver selected was marked audio only. */
    @Volatile private var selectedAudioOnly = false
    /** Audio-only live: set (true = the offer was marked) when the marker and the header disagreed. */
    @Volatile internal var markerMismatchMarkedAudio: Boolean? = null
        private set
    @Volatile private var listenerMuted = false
    @Volatile private var parentTrickle: RelayParentTrickle? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    /** Wider reach: native parents handed over by the Wi-Fi viewer-request acceptor (at most two). [AM 16] */
    private val wifiParents = java.util.concurrent.LinkedBlockingQueue<WifiNativeConnection>(2)
    /** One stream parent at a time, for both radios (F-7). Ungated and inert with Wider reach off. [AM 16] */
    private val parentOwnership = ParentOwnership()

    /** The one acceptor of this receiver's Wi-Fi viewer request: its identity marks the registry entry as this receiver's. */
    private val wifiViewerAcceptor: (WifiNativeConnection) -> Unit = { native ->
        if (running.get() && !wifiParents.offer(native)) native.close() else if (!running.get()) native.close()
    }
    private val wifiViewerLock = Any()
    /** The viewer request last offered over Wi-Fi (null: none), and whether it is wanted on air now (A8 review P1-1). */
    private var wifiViewerAnnouncement: DiscoveryAnnouncement? = null
    private var wifiViewerOnAir = false
    /** The current parent said the stream is paused: silence is expected, not a dead link. */
    @Volatile private var parentStreamPaused = false
    /** The parent's stream header arrived on this link: media can flow, so the trickle test may judge. */
    @Volatile private var parentMediaStarted = false
    /** The parent's media state says the camera is off: no video needs to flow ([ViewerLinkSilenceWatch]). */
    @Volatile private var parentVideoOff = false
    /** The authenticated header is audio-only live, which [ViewerLinkSilenceWatch] does not judge. */
    @Volatile private var parentAudioOnly = false
    /**
     * Experimental relay child thinning only: the relay paused this child's video for a weak link (audio still arrives). The
     * trickle close treats such a window like the parent's own pause (B10): audio alone is far below bitrate/8 of the video
     * stream, and judging it would close the link the relay deliberately thinned, again and again.
     */
    @Volatile private var weakLinkPaused = false
    /** A7: the hop of the current parent link (null while none). */
    @Volatile private var parentHop: HopLink? = null
    /** A7: the current stream's one-slot keyframe request queue, for the hop owner. */
    @Volatile private var parentKeyFrameRequests: ArrayBlockingQueue<Unit>? = null
    /** A7: serialises the two child-to-parent sealing writers that share one sealer (A7 Am 11). */
    private val controlSealLock = Any()
    /** LE CoC: a lease on the role's long-lived listener (CocListeners). */
    @Volatile private var cocListener: CocListenLease<BluetoothSocket>? = null
    @Volatile private var clientSocket: BluetoothSocket? = null
    /** The accepted GATT parent link while one is being received (Bluetooth ON recovery closes it when the adapter goes off). */
    @Volatile private var gattParentLink: GattVideoConnection? = null
    private val epochName = "viewer-link-" + Integer.toHexString(System.identityHashCode(this))
    @Volatile private var decoder: VideoStreamDecoder? = null
    @Volatile private var audioDecoder: OpusAudioDecoder? = null
    @Volatile private var workerThread: Thread? = null
    @Volatile private var bleControlServer: BleL2capControlServer? = null
    @Volatile private var gattVideoServer: GattVideoServer? = null
    @Volatile private var classicViewerAdvertiser: BleDiscoveryAdvertiser? = null

    /**
     * Rotating IDs, phase 1b (RotatingDiscoveryFeature, default off): the selected offer was a version 5
     * offer, so this viewer's request is version 5 too (it mirrors the offer) and the connecting parent
     * must open the offer's RDI over its link BDO1 (role 1).
     */
    @Volatile private var expectedSourceRotating = false
    @Volatile private var viewerRequestTransport: BluetoothTransportOption? = null
    @Volatile private var viewerLogicalToken = 0
    @Volatile private var viewerRecoveryRequested = false
    private val viewerBoundaryListener: (Long) -> Unit = { _ -> reannounceViewerRequest() }
    private val debugStageTrace = DebugStageTrace.startIfRequested(debugTraceSessionName, "broadcast_receive")

    /** Why the last stream on this receiver ended; set before [onStopped] runs. */
    /**
     * Design SG stage 0/1: set when this receiver's LE CoC listen failed (the real stack refusing it or the DEBUG
     * `test_coc_listen_inject=stream` injection); the activity decides from it whether to restart on GATT.
     */
    @Volatile internal var listenFailure: StreamListenFailure? = null
        private set

    @Volatile internal var stopCause: ReceiverStopCause? = null
        private set

    /** Audio-only live: silences or restores the output without touching the link (Mute, audio focus). */
    fun setListenerMuted(muted: Boolean) {
        listenerMuted = muted
        audioDecoder?.setMuted(muted)
    }

    fun recordSurfaceUpdate(timestampNs: Long, callbackNowNs: Long) {
        decoder?.recordSurfaceUpdate(timestampNs, callbackNowNs)
    }

    @SuppressLint("MissingPermission")
    fun start(
        context: Context,
        adapter: BluetoothAdapter,
        transport: BluetoothTransportOption,
        streamToken: Int = 0,
        routeCost: Int = 0,
        streamBitrateKbps: Int = 0,
        expectedSourceNodeIdPrefix: ByteArray? = null,
        recoveryFromNodeIdPrefix: ByteArray? = null,
        phyPreference: BlePhyPreference = BlePhyPreference.TWO_M,
        ioConfig: VideoStreamIoConfig = VideoStreamIoConfig.DEFAULT,
        /** Rotating IDs: the selected stream offer is version 5 (its announced bytes are an RDI). */
        selectedOfferRotating: Boolean = false,
        /** Audio-only live: the selected offer carried the audio-only marker (it must match the header). */
        selectedOfferAudioOnly: Boolean = false,
    ) {
        check(running.compareAndSet(false, true)) { "Receiver is already running" }
        BluetoothEpoch.register(epochName, onOff = ::onAdapterOff, onOn = {})
        listenFailure = null
        selectedAudioOnly = selectedOfferAudioOnly
        markerMismatchMarkedAudio = null
        expectedSourceRotating = selectedOfferRotating && RotatingDiscoveryFeature.enabled &&
            transport != BluetoothTransportOption.CLASSIC_RFCOMM && streamToken != 0
        viewerRequestTransport = transport
        viewerRecoveryRequested = recoveryFromNodeIdPrefix != null
        viewerLogicalToken = if (expectedSourceRotating && expectedSourceNodeIdPrefix != null) {
            StreamRouteToken.logicalFrom(streamToken, expectedSourceNodeIdPrefix)
        } else {
            0
        }
        workerThread = thread(name = "BluetoothVideoReceiver") {
            var failureNotice: String? = null
            try {
                val identity = AndroidNodeIdentityStore().getOrCreate()
                val capabilities = LocalNodeCapabilities.forTransport(
                    transport,
                    maxChildren = if (forwardReceivedStreams && streamToken != 0) 1 else 0,
                )
                parentOwnership.beginJoin(gated = streamToken != 0 && WifiDiscovery.widerReachOn())
                if (parentOwnership.gated) {   // [AM 16] gated before the offer is published
                    val wifiFlags = when (transport) {
                        BluetoothTransportOption.CLASSIC_RFCOMM -> DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT
                        BluetoothTransportOption.BLE_GATT -> DiscoveryAnnouncement.FLAG_GATT_TRANSPORT
                        BluetoothTransportOption.BLE_L2CAP -> 0
                    }
                    publishWifiViewerRequest(
                        viewerAnnouncement(identity, capabilities, wifiFlags, streamToken, routeCost, streamBitrateKbps, recoveryFromNodeIdPrefix),
                    )
                    thread(name = "ReceiverWifiParents", isDaemon = true) {
                        serveWifiParents(
                            context, adapter, transport, identity, capabilities, expectedSourceNodeIdPrefix,
                            streamToken, routeCost, streamBitrateKbps, phyPreference, ioConfig,
                        )
                    }
                }
                when (transport) {
                    BluetoothTransportOption.CLASSIC_RFCOMM -> {
                        serverSocket = ClassicSockets.listen(adapter, "Bluetooth Video", ClassicRole.STREAM_RECEIVER)
                        classicViewerAdvertiser = BleDiscoveryAdvertiser(
                            adapter,
                            DiscoveryAnnouncement(
                                nodeIdPrefix = identity.nodeId.toByteArray()
                                    .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
                                flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or
                                    DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT or
                                    recoveryDiscoveryFlag(recoveryFromNodeIdPrefix),
                                capabilitySummary = VideoCodecMap.helloMaskToSummary(capabilities.decodeCodecMask),
                                streamToken = streamToken,
                                routeCost = routeCost,
                                sequence = 0,
                                streamBitrateKbps = streamBitrateKbps,
                                recoveryFromNodeIdPrefix = recoveryFromNodeIdPrefix,
                            ),
                            onStatus,
                        ).also(BleDiscoveryAdvertiser::start)
                        onStatus(
                            if (streamToken != 0) {
                                "Classic stream requested. Waiting for the sender's RFCOMM connection."
                            } else {
                                "Classic receiver is advertising and waiting for an RFCOMM connection."
                            },
                        )
                    }
                    BluetoothTransportOption.BLE_L2CAP -> {
                        // One long-lived listener per role (CocListeners): Android 10 never frees a
                        // closed LE CoC server, so a listener per stream exhausted the A15s stack.
                        val activeServerSocket = try {
                            CocListenInject.failIfRequested(CocListenInject.STREAM)
                            CocListeners.listen(adapter, "receiver")
                        } catch (error: Exception) {
                            val failure = StreamListenFailure(failureSignature(error), error is CocListenInject.InjectedListenFailure)
                            listenFailure = failure
                            Log.w(
                                "BluetoothVideo",
                                (if (BuildConfig.DEBUG) "BENCHMARK " else "") + StreamGattFallback.listenFailedLine(
                                    transport, streamToken, failure.signature, CocRegistrations.ledger.count,
                                    CocAutoGatt.current, StreamGattFallback.streamsSwitch, failure.injected,
                                ),
                            )
                            throw error
                        }
                        cocListener = activeServerSocket
                        val controlServer = BleL2capControlServer(
                            context,
                            adapter,
                            activeServerSocket.psm,
                            viewerAnnouncement(
                                identity, capabilities, 0, streamToken, routeCost, streamBitrateKbps,
                                recoveryFromNodeIdPrefix,
                            ),
                            onStatus,
                            phyPreference,
                        )
                        bleControlServer = controlServer
                        controlServer.start()
                        watchViewerBoundaries(identity, capabilities, 0, streamToken, routeCost, streamBitrateKbps, recoveryFromNodeIdPrefix)
                        onStatus(
                            "BLE receiver is advertising. On the other phone, choose BLE L2CAP CoC, " +
                                "refresh receivers, and connect.",
                        )
                    }
                    BluetoothTransportOption.BLE_GATT -> {
                        receiveGattLoop(
                            context,
                            adapter,
                            streamToken,
                            routeCost,
                            streamBitrateKbps,
                            identity,
                            capabilities,
                            expectedSourceNodeIdPrefix,
                            recoveryFromNodeIdPrefix,
                            phyPreference,
                            ioConfig,
                        )
                        return@thread
                    }
                }
                acceptLoop@ while (running.get()) {
                    val activeCocListener = cocListener
                    val socket: BluetoothSocket? = try {
                        if (activeCocListener != null) {
                            if (streamToken == 0) {
                                activeCocListener.accept()
                            } else {
                                activeCocListener.accept(CONNECTION_WAIT_TIMEOUT_MS)
                            }
                        } else {
                            val activeServerSocket = serverSocket ?: break@acceptLoop
                            if (streamToken == 0) {
                                activeServerSocket.accept()
                            } else {
                                activeServerSocket.accept(CONNECTION_WAIT_TIMEOUT_MS.toInt())
                            }
                        }
                    } catch (error: IOException) {
                        // A join that waits for its Wi-Fi parent keeps waiting: the Bluetooth accept timeout is not an end.
                        // Ending is atomic with the Wi-Fi worker's claim, so a Wi-Fi parent can never start on a join that is
                        // ending (A8 review P2-5).
                        if (streamToken != 0 && parentOwnership.gated && !parentOwnership.endJoinIfUnowned()) {
                            try {
                                Thread.sleep(250)
                            } catch (_: InterruptedException) {
                                return@thread
                            }
                            continue@acceptLoop
                        }
                        throw error
                    }
                    if (socket == null) {
                        if (streamToken != 0 && parentOwnership.gated && !parentOwnership.endJoinIfUnowned()) continue@acceptLoop
                        break@acceptLoop
                    }
                    val token = parentOwnership.claim()
                    if (token == null) {
                        runCatching { socket.close() }
                        Log.i("BluetoothVideo", "BENCHMARK receiver_join: event=second_parent_refused | radio=bluetooth")
                        continue@acceptLoop
                    }
                    try {
                    takeWifiViewerRequestOffAir()   // a Bluetooth parent took the join (A8 review P1-1)
                    if (activeCocListener == null) ClassicRfcommCap.note(socket) // an accepted Classic RFCOMM peer counts towards the cap log
                    // Review B12: a Classic join takes one sender; its BLE request advert stops once that sender connected.
                    if (transport == BluetoothTransportOption.CLASSIC_RFCOMM && streamToken != 0) {
                        classicViewerAdvertiser?.close()
                        classicViewerAdvertiser = null
                    }
                    if (transport == BluetoothTransportOption.BLE_L2CAP) {
                        bleControlServer?.stopAdvertising()
                        RotatingDiscovery.offAir(RotatingDiscoveryIds.ROLE_VIEWER_REQUEST)
                    }
                    clientSocket = socket
                    try {
                        receive(
                            context,
                            adapter,
                            socket,
                            transport,
                            identity,
                            capabilities,
                            expectedSourceNodeIdPrefix,
                            streamToken,
                            routeCost,
                            streamBitrateKbps,
                            phyPreference,
                            ioConfig,
                        )
                    } catch (error: LocalMediaPipelineException) {
                        // Local decoder/playback failure: stop the receiver (no retry loop).
                        throw error
                    } catch (error: StreamRouteWithdrawnException) {
                        stopCause = ReceiverStopCause.of(ReceiverStopKind.ROUTE_WITHDRAWN, error)
                        if (running.get()) {
                            onRouteWithdrawn(error.reason)
                            onStatus("The immediate parent withdrew this stream route.")
                        }
                    } catch (error: EOFException) {
                        stopCause = ReceiverStopCause.of(ReceiverStopKind.LINK, error)
                        if (running.get()) onStatus(
                            if (streamToken == 0) {
                                "Sender disconnected. Still listening for another sender."
                            } else {
                                "Sender disconnected."
                            },
                        )
                    } catch (error: IOException) {
                        stopCause = ReceiverStopCause.of(ReceiverStopKind.LINK, error)
                        if (running.get()) onStatus(
                            if (streamToken == 0) {
                                "Sender disconnected. Still listening for another sender."
                            } else {
                                "Sender disconnected."
                            },
                        )
                    } catch (error: Exception) {
                        stopCause = ReceiverStopCause.of(ReceiverStopKind.OTHER, error)
                        if (running.get()) {
                            Log.e("BluetoothVideo", "Incoming stream failed: transport=$transport token=$streamToken", error)
                            onStatus(
                                "Incoming stream stopped: ${error.message ?: error.javaClass.simpleName}." +
                                    if (streamToken == 0) " Still listening." else "",
                            )
                        }
                    } finally {
                        onStreamActive(false)
                        closeDecoders()
                        runCatching { socket.close() }
                        clientSocket = null
                        if (running.get() && streamToken == 0 &&
                            transport == BluetoothTransportOption.BLE_L2CAP
                        ) {
                            bleControlServer?.startAdvertising()
                            onStatus("Sender disconnected. BLE receiver is advertising again.")
                        }
                    }
                    } finally {
                        parentOwnership.release(token)   // [AM 16] last
                    }
                    if (streamToken != 0) break
                }
            } catch (error: LocalMediaPipelineException) {
                stopCause = ReceiverStopCause.of(ReceiverStopKind.LOCAL_MEDIA, error)
                if (running.get()) {
                    Log.e(
                        "BluetoothVideo",
                        "Receiver stopped on a local media failure (not link loss; no reconnect): " +
                            "stage=${error.stage} | transport=$transport | token=$streamToken",
                        error,
                    )
                    failureNotice = localMediaFailureNotice(error)
                    onStatus(requireNotNull(failureNotice))
                }
            } catch (error: Exception) {
                stopCause = ReceiverStopCause.of(ReceiverStopKind.STARTUP, error)
                if (running.get() && selectedAudioOnly) {
                    // Audio-only live: every failure is logged with its stack and a BENCHMARK line.
                    AudioLiveLog.failure("receiver:${Thread.currentThread().name}", error)
                }
                if (running.get()) {
                    failureNotice = listenFailure?.let {
                        StreamGattFallback.listenFailedNotice(
                            error.message ?: error.javaClass.simpleName,
                            StreamGattFallback.active(),
                        )
                    } ?: receiverStartupFailureNotice(
                        transport,
                        streamToken,
                        error.message ?: error.javaClass.simpleName,
                    )
                    onStatus(requireNotNull(failureNotice))
                }
            } finally {
                running.set(false)
                // Review B7: a receiver that stopped itself leaves the Bluetooth epoch registry now, not at close().
                BluetoothEpoch.unregister(epochName)
                RotatingDiscovery.removeBoundaryListener(viewerBoundaryListener)
                RotatingDiscovery.offAir(RotatingDiscoveryIds.ROLE_VIEWER_REQUEST)
                stopWifiViewerRequest()   // every stop path, not only close() (A8 review P1-1)
                viewerInputs = null
                closeSockets()
                bleControlServer?.close()
                bleControlServer = null
                gattVideoServer?.close()
                gattVideoServer = null
                classicViewerAdvertiser?.close()
                classicViewerAdvertiser = null
                workerThread = null
                surface.release()
                onStopped(failureNotice)
            }
        }
    }

    /**
     * The viewer request this phone advertises. Off, or a version 2 offer, or Classic: exactly the old
     * announcement (fixed prefix; failed parent's prefix in the recovery bytes). A version 5 offer: the
     * request mirrors it with the role-2 RDI, the offer's route token and, after a lost forwarder parent,
     * the recovery hint (PROTOCOL Phase 1b items 2 and 5).
     */
    private fun viewerAnnouncement(
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        transportFlags: Int,
        streamToken: Int,
        routeCost: Int,
        streamBitrateKbps: Int,
        recoveryFromNodeIdPrefix: ByteArray?,
    ): DiscoveryAnnouncement {
        val transport = viewerRequestTransport
        if (transport != null && streamToken != 0 &&
            RotatingStreamAnnouncements.viewerRequestRotates(RotatingDiscoveryFeature.enabled, transport, expectedSourceRotating)
        ) {
            val entry = RotatingDiscovery.advertise(RotatingDiscoveryIds.ROLE_VIEWER_REQUEST)
            val hint = if (viewerRecoveryRequested) {
                ViewerRecoveryMemory.hint(viewerLogicalToken, entry.rdi, streamToken)
            } else {
                null
            }
            val base = DiscoveryAnnouncement(
                nodeIdPrefix = entry.rdi,
                flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or transportFlags,
                capabilitySummary = VideoCodecMap.helloMaskToSummary(capabilities.decodeCodecMask),
                streamToken = streamToken,
                routeCost = routeCost,
                sequence = 0,
                streamBitrateKbps = streamBitrateKbps,
                version = DiscoveryAnnouncement.ROTATING_VERSION,
            )
            return RotatingStreamAnnouncements.viewerRequest(base, entry, hint)
        }
        if (transport != null && streamToken != 0 && RotatingDiscoveryFeature.enabled &&
            transport != BluetoothTransportOption.CLASSIC_RFCOMM
        ) {
            // Joining a version 2 offer: a version 2 source cannot decode version 5, so the request shows the
            // fixed prefix (stated residual, PROTOCOL Phase 1b item 2).
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotid_downgrade: role=viewer | reason=selected_version_2",
            )
            onStatus("This source does not support private discovery. Your phone's fixed ID is visible while you join.")
        }
        return DiscoveryAnnouncement(
            nodeIdPrefix = identity.nodeId.toByteArray()
                .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
            flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or transportFlags or
                recoveryDiscoveryFlag(recoveryFromNodeIdPrefix),
            capabilitySummary = VideoCodecMap.helloMaskToSummary(capabilities.decodeCodecMask),
            streamToken = streamToken,
            routeCost = routeCost,
            sequence = 0,
            streamBitrateKbps = streamBitrateKbps,
            recoveryFromNodeIdPrefix = recoveryFromNodeIdPrefix,
        )
    }

    /** What a boundary re-announcement needs; set once the first request is on air. */
    private class ViewerAnnouncementInputs(
        val identity: SigningIdentity,
        val capabilities: NodeCapabilities,
        val transportFlags: Int,
        val streamToken: Int,
        val routeCost: Int,
        val streamBitrateKbps: Int,
        val recoveryFromNodeIdPrefix: ByteArray?,
    )

    @Volatile private var viewerInputs: ViewerAnnouncementInputs? = null

    /**
     * Rotating IDs: while this viewer still waits for its parent, a boundary restarts its request set on a
     * new advertising set with the new role-2 RDI (RV6), after the usual silence. Only a version 5 request
     * rotates; everything else is unchanged.
     */
    private fun watchViewerBoundaries(
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        transportFlags: Int,
        streamToken: Int,
        routeCost: Int,
        streamBitrateKbps: Int,
        recoveryFromNodeIdPrefix: ByteArray?,
    ) {
        val transport = viewerRequestTransport ?: return
        if (streamToken == 0 ||
            !RotatingStreamAnnouncements.viewerRequestRotates(RotatingDiscoveryFeature.enabled, transport, expectedSourceRotating)
        ) {
            return
        }
        viewerInputs = ViewerAnnouncementInputs(
            identity, capabilities, transportFlags, streamToken, routeCost, streamBitrateKbps, recoveryFromNodeIdPrefix,
        )
        RotatingDiscovery.addBoundaryListener(viewerBoundaryListener)
    }

    private fun reannounceViewerRequest() {
        val inputs = viewerInputs ?: return
        if (!running.get()) return
        val control = bleControlServer
        val gatt = gattVideoServer
        if (control == null && gatt == null) return
        thread(name = "ViewerIdRotation") {
            val announcement = viewerAnnouncement(
                inputs.identity, inputs.capabilities, inputs.transportFlags, inputs.streamToken, inputs.routeCost,
                inputs.streamBitrateKbps, inputs.recoveryFromNodeIdPrefix,
            )
            updateWifiViewerRequest(announcement)   // [AM 27] through publishOffer; only while the request is still on air
            val silenceMs = RotatingDiscovery.boundarySilenceMs()
            // Review T12: both servers sit out the same silence at once (one after the other, the GATT advert came
            // back a whole silence after the control one).
            val controlThread = control?.let { thread(name = "ViewerIdRotationControl") { it.reannounce(announcement, silenceMs) } }
            gatt?.reannounce(announcement, silenceMs)
            runCatching { controlThread?.join() }
            if (BuildConfig.DEBUG) {
                Log.i("BluetoothVideo", "BENCHMARK rotid_restart: role=viewer | silenceMs=$silenceMs")
            }
        }
    }

    /** Offers [announcement] as this join's Wi-Fi viewer request (Wider reach, the join is gated). */
    private fun publishWifiViewerRequest(announcement: DiscoveryAnnouncement) = synchronized(wifiViewerLock) {
        wifiViewerAnnouncement = announcement
        wifiViewerOnAir = true
        WifiDiscovery.publishOffer(WifiOfferRole.VIEWER_REQUEST, announcement, wifiViewerAcceptor)
    }

    /** A boundary's new request: published over Wi-Fi only while the request is still wanted on air there. */
    private fun updateWifiViewerRequest(announcement: DiscoveryAnnouncement) = synchronized(wifiViewerLock) {
        if (!wifiViewerOnAir) return@synchronized
        wifiViewerAnnouncement = announcement
        WifiDiscovery.publishOffer(WifiOfferRole.VIEWER_REQUEST, announcement, wifiViewerAcceptor)
    }

    /** A parent (Bluetooth or Wi-Fi) took the join: sources stop dialling this viewer over Wi-Fi. */
    private fun takeWifiViewerRequestOffAir() = synchronized(wifiViewerLock) {
        wifiViewerOnAir = false
        clearOwnWifiViewerRequest()
    }

    /** The join waits again (an unauthenticated link was dropped): the last request goes back on air. */
    private fun resumeWifiViewerRequest() = synchronized(wifiViewerLock) {
        val announcement = wifiViewerAnnouncement ?: return@synchronized
        if (!running.get()) return@synchronized
        wifiViewerOnAir = true
        WifiDiscovery.publishOffer(WifiOfferRole.VIEWER_REQUEST, announcement, wifiViewerAcceptor)
    }

    /** The receiver stops: the request leaves Wi-Fi for good (a stale rotating ID must never stay published, F-10/X8). */
    private fun stopWifiViewerRequest() = synchronized(wifiViewerLock) {
        wifiViewerOnAir = false
        wifiViewerAnnouncement = null
        clearOwnWifiViewerRequest()
    }

    /** Clears the viewer request only when it is this receiver's (another receiver's newer request stays). */
    private fun clearOwnWifiViewerRequest() {
        WifiDiscovery.offers.clearIf(WifiOfferRole.VIEWER_REQUEST) { it.acceptor === wifiViewerAcceptor }
    }

    /** Wider reach: one Wi-Fi-native parent per stream join (A8 step 12.1.5), with the same ownership token as the Bluetooth workers. */
    private fun serveWifiParents(
        context: Context,
        adapter: BluetoothAdapter,
        transport: BluetoothTransportOption,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        expectedSourceNodeIdPrefix: ByteArray?,
        streamToken: Int,
        routeCost: Int,
        streamBitrateKbps: Int,
        phyPreference: BlePhyPreference,
        ioConfig: VideoStreamIoConfig,
    ) {
        while (running.get()) {
            val connection = try {
                wifiParents.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
            } catch (_: InterruptedException) {
                return
            }
            val token = parentOwnership.claim()
            if (token == null) {
                connection.close()
                Log.i("BluetoothVideo", "BENCHMARK receiver_join: event=second_parent_refused | radio=wifi")
                continue
            }
            var silenceWatch: Thread? = null
            try {
                RotatingDiscovery.offAir(RotatingDiscoveryIds.ROLE_VIEWER_REQUEST)
                takeWifiViewerRequestOffAir()
                parentStreamPaused = false
                parentMediaStarted = false
                parentVideoOff = false
                parentAudioOnly = false
                val reads = SocketReadGapStats()
                silenceWatch = startViewerLinkSilenceWatch(transport, routeCost, relayParentWatchRunning = false, reads) {
                    runCatching { connection.close() }
                }
                try {
                    receiveSecure(
                        context, adapter,
                        BufferedInputStream(reads.wrap(connection.input), ioConfig.bufferBytes),
                        BufferedOutputStream(connection.output, ioConfig.bufferBytes),
                        transport, identity, capabilities, expectedSourceNodeIdPrefix, streamToken, routeCost, streamBitrateKbps,
                        phyPreference, ioConfig,
                        carrier0IsBluetooth = false,                       // A7's parameter, by name: no HopLink upgrade [X14][A7]
                        onLinkAuthenticated = { connection.admitted() },    // F-3 admission [AM 12]
                    ) { " | " + WifiCallNotices.LABEL + " peer=" + connection.peerHash }
                } catch (error: StreamRouteWithdrawnException) {
                    stopCause = ReceiverStopCause.of(ReceiverStopKind.ROUTE_WITHDRAWN, error)
                    if (running.get()) {
                        onRouteWithdrawn(error.reason)
                        onStatus("The immediate parent withdrew this stream route.")
                    }
                } catch (error: IOException) {
                    stopCause = ReceiverStopCause.of(ReceiverStopKind.LINK, error)
                    if (running.get()) onStatus("Wi-Fi sender disconnected (${error.message ?: "I/O failure"}).")
                } catch (error: Exception) {
                    stopCause = ReceiverStopCause.of(ReceiverStopKind.OTHER, error)
                    if (running.get()) onStatus("Incoming Wi-Fi stream stopped: ${error.message ?: error.javaClass.simpleName}")
                } finally {
                    silenceWatch?.interrupt()
                    onStreamActive(false)   // this worker owns the token here
                    closeDecoders()
                    connection.close()
                }
                if (streamToken != 0) {           // [AM 16] a join ends with its parent; the Bluetooth worker's outer finally runs once
                    running.set(false)
                    stopWifiViewerRequest()
                    closeSockets()
                    gattVideoServer?.close()
                }
            } finally {
                parentOwnership.release(token)   // [AM 16] last
            }
        }
    }

    private fun receiveGattLoop(
        context: Context,
        adapter: BluetoothAdapter,
        streamToken: Int,
        routeCost: Int,
        streamBitrateKbps: Int,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        expectedSourceNodeIdPrefix: ByteArray?,
        recoveryFromNodeIdPrefix: ByteArray?,
        phyPreference: BlePhyPreference,
        ioConfig: VideoStreamIoConfig,
    ) {
        val connections = LinkedBlockingQueue<GattVideoConnection>()
        val server = GattVideoServer(
            context,
            adapter,
            viewerAnnouncement(
                identity, capabilities, DiscoveryAnnouncement.FLAG_GATT_TRANSPORT, streamToken, routeCost,
                streamBitrateKbps, recoveryFromNodeIdPrefix,
            ),
            phyPreference,
            onStatus,
            connections::offer,
        )
        gattVideoServer = server
        server.readvertiseOnLocalClose = true
        server.start()
        watchViewerBoundaries(
            identity, capabilities, DiscoveryAnnouncement.FLAG_GATT_TRANSPORT, streamToken, routeCost,
            streamBitrateKbps, recoveryFromNodeIdPrefix,
        )
        // Review B3/B9: one join wait across connections, extended once for a link still in setup.
        var joinWait = if (streamToken == 0) null else ReceiverJoinWait(android.os.SystemClock.elapsedRealtime(), CONNECTION_WAIT_TIMEOUT_MS)
        while (running.get()) {
            val connection = if (joinWait == null) {
                connections.take()
            } else {
                val taken = pollJoinConnection(connections, joinWait, server)
                if (taken == null) {
                    // [AM 16] a Wi-Fi parent may own the join: the Bluetooth wait starts over instead of ending the receiver.
                    if (parentOwnership.gated && !parentOwnership.endJoinIfUnowned()) {   // atomic with a Wi-Fi claim (P2-5)
                        joinWait = ReceiverJoinWait(android.os.SystemClock.elapsedRealtime(), CONNECTION_WAIT_TIMEOUT_MS)
                        continue
                    }
                    // Review B4: a typed cause, so the "avoid a failing parent" fallback counts this parent.
                    if (running.get()) stopCause = ReceiverStopCause.joinTimeout()
                    return
                }
                taken
            }
            val token = parentOwnership.claim()
            if (token == null) {
                runCatching { connection.close() }
                Log.i("BluetoothVideo", "BENCHMARK receiver_join: event=second_parent_refused | radio=bluetooth")
                continue
            }
            try {
            RotatingDiscovery.offAir(RotatingDiscoveryIds.ROLE_VIEWER_REQUEST)
            takeWifiViewerRequestOffAir()
            gattParentLink = connection
            var linkAuthenticated = false
            parentStreamPaused = false
            parentMediaStarted = false
            parentVideoOff = false
            parentAudioOnly = false
            // The GATT read has no watch of its own: the viewer link silence watch closes a link that delivers nothing.
            val gattReads = SocketReadGapStats()
            val gattSilenceWatch = startViewerLinkSilenceWatch(
                BluetoothTransportOption.BLE_GATT,
                routeCost,
                relayParentWatchRunning = false,
                gattReads,
            ) { runCatching { connection.close() } }
            try {
                receiveSecure(
                    context,
                    adapter,
                    BufferedInputStream(gattReads.wrap(connection.inputStream), ioConfig.bufferBytes),
                    BufferedOutputStream(connection.outputStream, ioConfig.bufferBytes),
                    BluetoothTransportOption.BLE_GATT,
                    identity,
                    capabilities,
                    expectedSourceNodeIdPrefix,
                    streamToken,
                    routeCost,
                    streamBitrateKbps,
                    phyPreference,
                    ioConfig,
                    connection.receiveQueueState,
                    onLinkAuthenticated = { linkAuthenticated = true },
                ) { " | ${connection.phySummary()}" }
            } catch (error: LocalMediaPipelineException) {
                throw error
            } catch (error: StreamRouteWithdrawnException) {
                stopCause = ReceiverStopCause.of(ReceiverStopKind.ROUTE_WITHDRAWN, error)
                if (running.get()) {
                    onRouteWithdrawn(error.reason)
                    onStatus("The immediate parent withdrew this stream route.")
                }
            } catch (error: EOFException) {
                stopCause = ReceiverStopCause.of(ReceiverStopKind.LINK, error)
                if (running.get()) onStatus(
                    if (streamToken == 0) {
                        "GATT sender disconnected (${error.message ?: "end of stream"}). Advertising again."
                    } else {
                        "GATT sender disconnected (${error.message ?: "end of stream"})."
                    },
                )
            } catch (error: IOException) {
                stopCause = ReceiverStopCause.of(ReceiverStopKind.LINK, error)
                if (running.get()) onStatus(
                    if (streamToken == 0) {
                        "GATT sender disconnected (${error.message ?: "I/O failure"}). Advertising again."
                    } else {
                        "GATT sender disconnected (${error.message ?: "I/O failure"})."
                    },
                )
            } catch (error: Exception) {
                stopCause = ReceiverStopCause.of(ReceiverStopKind.OTHER, error)
                if (running.get()) {
                    Log.e("BluetoothVideo", "Incoming GATT stream failed: token=$streamToken", error)
                    onStatus("Incoming GATT stream stopped: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                gattSilenceWatch?.interrupt()
                gattParentLink = null
                onStreamActive(false)
                closeDecoders()
                connection.close()
            }
            if (joinWait != null) {
                // Review B9: a connection that never authenticated was not this join's parent: drop it, keep waiting.
                if (!running.get() || !joinWait.keepWaitingAfter(linkAuthenticated, android.os.SystemClock.elapsedRealtime())) return
                Log.i("BluetoothVideo", "BENCHMARK receiver_join: event=unauthenticated_link_dropped | action=keep_waiting")
                resumeWifiViewerRequest()   // the join still waits: its Wi-Fi request goes back on air
            }
            } finally {
                parentOwnership.release(token)   // [AM 16] last
            }
        }
    }

    /**
     * Review B8 (2026-10-27): a GATT control write that failed because the parent link died left the viewer waiting on a
     * silent input until a read timeout. When the parent also stops being heard for [GattControlFailure.QUIET_MS] after
     * the failure, the link is closed now, so the ordinary link-loss recovery runs; a parent still sending keeps the old
     * behaviour (forward media continues without reverse control).
     */
    private fun watchGattParentAfterControlFailure(error: Exception, controlRunning: AtomicBoolean) {
        val link = gattParentLink ?: return
        val pipe = link.inputStream as? GattBytePipe ?: return
        thread(name = "GattControlFailureWatch", isDaemon = true) {
            try {
                Thread.sleep(GattControlFailure.QUIET_MS)
            } catch (_: InterruptedException) {
                return@thread
            }
            if (running.get() && controlRunning.get() && gattParentLink === link &&
                GattControlFailure.endsLink(error is IOException, pipe.heardWithin(GattControlFailure.QUIET_MS))
            ) {
                Log.i("BluetoothVideo", "BENCHMARK viewer_control: event=gatt_control_failed | parentHeard=false | action=close_link")
                runCatching { link.close() }
            }
        }
    }

    /** Review B3: the join's wait for its parent's connection, extended once while a central is still in setup. */
    private fun pollJoinConnection(
        connections: LinkedBlockingQueue<GattVideoConnection>,
        joinWait: ReceiverJoinWait,
        server: GattVideoServer,
    ): GattVideoConnection? {
        while (running.get()) {
            val remainingMs = joinWait.remainingMs(android.os.SystemClock.elapsedRealtime())
            if (remainingMs > 0L) {
                connections.poll(remainingMs, TimeUnit.MILLISECONDS)?.let { return it }
                continue
            }
            val nowMs = android.os.SystemClock.elapsedRealtime()
            if (!joinWait.extendForLinkInSetup(server.setupCentralSinceMs(), nowMs)) return null
            Log.i(
                "BluetoothVideo",
                "BENCHMARK receiver_join: event=deadline_extended | reason=link_in_setup | " +
                    "extensionMs=${ReceiverJoinWait.SETUP_EXTENSION_MS}",
            )
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun receive(
        context: Context,
        adapter: BluetoothAdapter,
        socket: BluetoothSocket,
        transport: BluetoothTransportOption,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        expectedSourceNodeIdPrefix: ByteArray?,
        streamToken: Int,
        routeCost: Int,
        streamBitrateKbps: Int,
        phyPreference: BlePhyPreference,
        ioConfig: VideoStreamIoConfig,
    ) {
        val readGaps = SocketReadGapStats()
        val input = BufferedInputStream(readGaps.wrap(socket.inputStream), ioConfig.bufferBytes)
        val output = BufferedOutputStream(socket.mediaOutputStream(), ioConfig.bufferBytes)
        parentStreamPaused = false
        parentMediaStarted = false
        parentVideoOff = false
        parentAudioOnly = false
        weakLinkPaused = false
        // A relay's re-anchor closes this link, but on a starved link (mesh4-659efc38 0001/0005:
        // 0 kbps) even the close may never arrive and this child waited 30 s, unseen by the relay's
        // fresh scan. A live stream always carries audio, so a relayed parent link that delivers no
        // byte at all for PARENT_SILENCE_NS is dead: close it and let link-loss recovery reconnect
        // (to the same relay first) on a new connection.
        val startedNs = System.nanoTime()
        // A trickle defeats the silence test: mesh4-8211dc8f 0001 (M31 -> A15s, 2M) delivered ~12 kbps
        // (read gaps <= 510 ms) for 17 s; the relay's re-anchor closed its socket, but the L2CAP channel
        // stayed up while the stack drained and this child never reconnected.
        val trickle = if (DebugTunables.relayParentTrickle) RelayParentTrickle(streamBitrateKbps, startedNs) else null
        parentTrickle = trickle
        // The child-side parent-link watch (RelayRecoveryConfig.parentWatch; follows relay_child_reanchor unless set apart).
        val relayParentWatchRunning = routeCost > 0 && RelayRecoveryConfig.current().parentWatch
        val silenceWatch = if (relayParentWatchRunning) {
            thread(name = "ReceiverParentSilence") {
                try {
                    while (running.get()) {
                        Thread.sleep(PARENT_SILENCE_CHECK_MS)
                        val nowNs = System.nanoTime()
                        val paused = parentStreamPaused
                        val onWifi = parentHop?.acceptKind ?: CarrierKind.BLUETOOTH
                        val silent = relayParentSilent(nowNs, readGaps.lastReadAtNs, startedNs, parentLinkJudgedPaused(paused, onWifi))
                        val trickleKbps = if (silent) {
                            null
                        } else {
                            // A weak-link pause (relay child thinning at audio only) is a pause for the trickle test (B10);
                            // the silence test still closes a link that delivers nothing at all.
                            trickle?.onTick(
                                nowNs,
                                readGaps.bytesRead,
                                parentLinkJudgedPaused(paused || weakLinkPaused, onWifi),
                                armed = parentMediaStarted,
                            )
                        }
                        if (silent || trickleKbps != null) {
                            Log.i(
                                "BluetoothVideo",
                                "BENCHMARK relay_parent_silence: silentMs=" +
                                    "${(nowNs - maxOf(readGaps.lastReadAtNs, startedNs)) / 1_000_000L} | action=close" +
                                    if (trickleKbps != null) {
                                        " | reason=trickle | kbps=$trickleKbps | streamKbps=$streamBitrateKbps"
                                    } else {
                                        " | reason=silent"
                                    },
                            )
                            onStatus("The relay link delivered nothing; reconnecting.")
                            runCatching { socket.close() }
                            return@thread
                        }
                    }
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        } else {
            null
        }
        // A direct link (or a relayed one whose parent watch is off) gets the viewer link silence watch instead.
        val linkSilenceWatch = startViewerLinkSilenceWatch(transport, routeCost, relayParentWatchRunning, readGaps) {
            runCatching { socket.close() }
        }
        try {
            receiveSecureWithDetails(context, adapter, input, output, socket, readGaps, transport, identity,
                capabilities, expectedSourceNodeIdPrefix, streamToken, routeCost, streamBitrateKbps,
                phyPreference, ioConfig)
        } finally {
            silenceWatch?.interrupt()
            linkSilenceWatch?.interrupt()
        }
    }

    /**
     * Viewer link silence watch ([ViewerLinkSilenceWatch], `viewer_link_silence`): closes a started, live, camera-on video
     * link that delivered no byte for [ViewerLinkSilenceWatch.SILENCE_NS], so link-loss recovery reconnects. Null when it
     * does not run on this link (switch off, or the relay parent-link watch already watches it).
     */
    private fun startViewerLinkSilenceWatch(
        transport: BluetoothTransportOption,
        routeCost: Int,
        relayParentWatchRunning: Boolean,
        reads: SocketReadGapStats,
        closeLink: () -> Unit,
    ): Thread? {
        if (!ViewerLinkSilenceWatch.applies(DebugTunables.viewerLinkSilence, relayParentWatchRunning)) return null
        val watch = ViewerLinkSilenceWatch()
        return thread(name = "ViewerLinkSilence", isDaemon = true) {
            try {
                while (running.get()) {
                    Thread.sleep(ViewerLinkSilenceWatch.CHECK_MS)
                    val silentMs = watch.onTick(
                        System.nanoTime(),
                        reads.lastReadAtNs,
                        mediaStarted = parentMediaStarted,
                        silenceExpected = parentLinkJudgedPaused(
                            parentStreamPaused || parentVideoOff || parentAudioOnly || weakLinkPaused,
                            parentHop?.acceptKind ?: CarrierKind.BLUETOOTH,
                        ),
                    ) ?: continue
                    Log.i("BluetoothVideo", ViewerLinkSilenceWatch.benchmarkLine(silentMs, transport, routeCost))
                    onStatus("No video arrived for ${silentMs / 1_000L} s; reconnecting.")
                    closeLink()
                    return@thread
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun receiveSecureWithDetails(
        context: Context,
        adapter: BluetoothAdapter,
        input: InputStream,
        output: OutputStream,
        socket: BluetoothSocket,
        readGaps: SocketReadGapStats,
        transport: BluetoothTransportOption,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        expectedSourceNodeIdPrefix: ByteArray?,
        streamToken: Int,
        routeCost: Int,
        streamBitrateKbps: Int,
        phyPreference: BlePhyPreference,
        ioConfig: VideoStreamIoConfig,
    ) {
        receiveSecure(
            context,
            adapter,
            input,
            output,
            transport,
            identity,
            capabilities,
            expectedSourceNodeIdPrefix,
            streamToken,
            routeCost,
            streamBitrateKbps,
            phyPreference = phyPreference,
            ioConfig = ioConfig,
            onLinkAuthenticated = { session -> learnClassicPeerAddress(socket, session.peerNodeId, "incoming", session.peerPseudonymous) },
        ) { linkDetails(transport, socket) + readGaps.summary() }
    }

    private fun receiveSecure(
        context: Context,
        adapter: BluetoothAdapter,
        linkInput: InputStream,
        output: OutputStream,
        transport: BluetoothTransportOption,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        expectedSourceNodeIdPrefix: ByteArray?,
        streamToken: Int,
        routeCost: Int,
        streamBitrateKbps: Int,
        phyPreference: BlePhyPreference,
        ioConfig: VideoStreamIoConfig,
        receiveQueueState: () -> Pair<Int, Int>? = { null },
        onLinkAuthenticated: (SecureBluetoothSession) -> Unit = {},
        /** A7: a Wi-Fi-native parent passes false (mode OFF, no CAPS, no attempt); every Bluetooth parent keeps true. */
        carrier0IsBluetooth: Boolean = true,
        linkDetails: () -> String,
    ) {
        val linkLoad = NodeLinkLoad.open(transport, LinkDirection.RECEIVE)
        val input = linkLoad.countingInput(linkInput)
        val linkCapabilities = StreamHopOpening.linkCapabilities(capabilities, RotatingDiscoveryFeature.enabled, transport)
        val session = try {
            SecureBluetoothHandshake.respond(
                input,
                output,
                identity,
                linkCapabilities,
                raiseTag = true,
            )
        } catch (error: Throwable) {
            linkLoad.close()
            throw error
        }
        var contentSession: EndToEndVideoSession? = null
        var groupSession: BroadcastGroupReceiverSession? = null
        var forwardedMediaOpener: EndToEndContentOpener? = null
        var streamForwarder: ReceiverStreamForwarder? = null
        var labelSourcePrefix: String? = null
        var debugMediaTrace: DebugMediaTrace? = null
        var hopLink: HopLink? = null
        val controlRunning = AtomicBoolean(false)
        var controlThread: Thread? = null
        try {
            require(session.peerCapabilities.transportMask and transport.capabilityMask != 0) {
                "Sender did not authenticate support for ${transport.shortLabel}"
            }
            onLinkAuthenticated(session)
            // Rotating IDs, phase 1b: this listener sends its pinned role-2 entries and reads the connector's
            // role-1 entries (both only when both link hellos carry bit 3).
            val linkOpening = if (RotatingDiscoveryFeature.enabled && transport != BluetoothTransportOption.CLASSIC_RFCOMM) {
                LinkOpeningExchange.exchange(
                    session,
                    linkCapabilities,
                    RotatingDiscovery.pinned(RotatingDiscoveryIds.ROLE_VIEWER_REQUEST),
                )
            } else {
                null
            }
            val hop = HopLink(
                role = HopCapsPlan.HopRole.STREAM_CHILD,
                session = session,
                closeRaw = {},
                anyLocalSwitchOn = WifiSwitchHub::anyOn,
                log = WifiLog::emit,
                owner = receiverHopOwner(transport, streamBitrateKbps) { hopLink },
                newAgent = ::newHopAgent,
                carrier0IsBluetooth = carrier0IsBluetooth,
            )
            hopLink = hop
            parentHop = hop
            // Experimental peer labels (default off): bit 5 in this endpoint hello only, never in
            // the link hello and never for the group (BGK1) harness.
            val labelsOn = PeerLabelFeature.enabled
            // SEC-B2: the selected version 2 offer belongs to the link peer itself (a forwarder offers under its own node
            // and hands the media key to this child), so a link peer with another node cannot relay the source's handshake.
            // Only the DEBUG manual relay is exempt: its offer belongs to the source behind it.
            val relayExempt = StreamHopPeerBinding.relayExempt(
                session.peerCapabilities.maxChildren,
                rotating = expectedSourceNodeIdPrefix != null && expectedSourceRotating,
            )
            if (expectedSourceNodeIdPrefix != null && !expectedSourceRotating) {
                require(StreamHopPeerBinding.linkMatchesOffer(session.peerNodeId, expectedSourceNodeIdPrefix, relayExempt)) {
                    "Stream source identity changed between discovery and connection"
                }
            }
            StreamHopPeerBinding.requireGroupAllowed(
                "viewer",
                expectedSourceNodeIdPrefix != null && expectedSourceRotating,
                session.peerCapabilities.maxChildren,
                "Stream source identity changed between discovery and connection",
            )
            if (session.peerCapabilities.maxChildren > 1) {
                groupSession = BroadcastGroupHandshake.respond(session, identity, capabilities)
            } else {
                contentSession = EndToEndVideoHandshake.respond(session, identity, PeerLabelFeature.advertise(capabilities, labelsOn))
                    .also { it.opener.acceptLabels = labelsOn }
            }
            val endpointSession = groupSession?.endpoint ?: requireNotNull(contentSession)
            // SEC-B2: the authenticated end-to-end peer is the authenticated link peer on every version and transport.
            StreamHopPeerBinding.requireBound(
                "viewer",
                expectedSourceNodeIdPrefix != null && expectedSourceRotating,
                session.peerNodeId,
                endpointSession.peerNodeId,
                session.peerCapabilities.maxChildren,
                "Stream source identity changed between discovery and connection",
            )
            if (expectedSourceNodeIdPrefix != null) {
                if (expectedSourceRotating) {
                    // RV15: the check follows the selected version 5 offer: its RDI must open for the link
                    // node (role 1), and the endpoint must be that same node.
                    val selectedOffer = DiscoveryAnnouncement(
                        nodeIdPrefix = expectedSourceNodeIdPrefix,
                        flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE,
                        capabilitySummary = 0,
                        streamToken = streamToken,
                        routeCost = routeCost,
                        sequence = 0,
                        version = DiscoveryAnnouncement.ROTATING_VERSION,
                    )
                    val result = StreamHopOpening.check(
                        selectedOffer,
                        RotatingDiscoveryIds.ROLE_STREAM_OFFER,
                        session.peerNodeId,
                        linkOpening,
                        endpointSession.peerNodeId,
                    )
                    Log.i(
                        "BluetoothVideo",
                        (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotid_open: role=stream | result=$result",
                    )
                    require(result == DiscoverySelectionCheck.Result.PASS) {
                        "Stream source identity changed between discovery and connection"
                    }
                } else {
                    require(
                        endpointSession.peerNodeId.toByteArray()
                            .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
                            .contentEquals(expectedSourceNodeIdPrefix),
                    ) { "Stream source identity changed between discovery and connection" }
                }
            }
            if (DebugTunables.joinProbeSeed) session.reader.enableJoinBurstMeasure()
            val forwardedGrant = if (endpointSession.peerCapabilities.maxChildren > 0) {
                val wrapped = hop.reader.read()
                require(wrapped.type == SecureRecordType.END_TO_END_CONTENT) {
                    "Expected a forwarded-stream key grant"
                }
                val opened = endpointSession.opener.open(wrapped.payload)
                require(opened.type == EndToEndContentType.GROUP_KEY) {
                    "Expected a forwarded-stream group key"
                }
                ForwardedStreamGrantCodec.decode(opened.payload)
            } else {
                null
            }
            // Exactly one LABELS record when both endpoint hellos carry bit 5 on this hop (R4): first
            // for a direct stream, right after GROUP_KEY for a forwarded hop. Verified after the header.
            val labelsPayload = contentSession?.takeIf {
                groupSession == null &&
                    LabelRecords.streamHopNegotiated(labelsOn, session.peerNodeId, it.peerNodeId, it.peerCapabilities)
            }?.let { LabelRecords.read(hop.reader, it.opener) }
            val mediaOpener = forwardedGrant?.let { grant ->
                EndToEndContentOpener(grant.key).also {
                    forwardedMediaOpener = it
                    grant.key.fill(0)
                }
            } ?: groupSession?.streamOpener ?: endpointSession.opener
            if (RotatingDiscoveryFeature.enabled && viewerLogicalToken != 0) {
                if (forwardedGrant != null) {
                    // The key a source also holds for this forwarder (its sealer key): the base of the recovery
                    // hint if this forwarder is lost (RV18). Memory only; a direct-source link clears it.
                    ViewerRecoveryMemory.remember(session.peerNodeId, mediaOpener.copyKeyForForwarding(), viewerLogicalToken)
                } else {
                    ViewerRecoveryMemory.clear()
                }
            }
            val mediaSourceNodeId = forwardedGrant?.sourceNodeId ?: endpointSession.peerNodeId
            // The forwarder's own full fingerprint was checked in its handshake; the original source is
            // known only by the 128-bit node ID in the grant, compared with verified contacts here.
            forwardedGrant?.let { PeerIdentityGate.admitForwardedSource(it.sourceNodeId) }
            require(expectedMediaSourceNodeId == null || mediaSourceNodeId == expectedMediaSourceNodeId) {
                "The replacement route belongs to a different stream source"
            }
            val pendingForwardingEnvelopes = mutableListOf<MeshEnvelope>()
            val keyFrameRequests = ArrayBlockingQueue<Unit>(1)
            parentKeyFrameRequests = keyFrameRequests
            val forwardedChildCount = AtomicInteger(0)
            val forwardedChildAudioDropsAtJoin = AtomicLong(0)
            val downstreamKeyFramePending = AtomicBoolean(false)
            var decoderForForwardingHealth: VideoStreamDecoder? = null
            var relayAllowed = forwardReceivedStreams && streamToken != 0
            // Experimental temporal layering (off by default): when the full stream does not fit
            // receive+forward, the decision waits for real frames; a layered stream needs to
            // guarantee its child only the measured base layer. Unused while the experiment is off.
            val layeredRelayAdmission = DebugTunables.experimentalTemporalLayers
            val relayLayerShare = LayerShareMeter()
            var relayDecisionDueNs = 0L
            var layeredRelayLinkKbps = 0.0
            var layeredRelayBudgetKbps = 0.0

            val streamReader = EndToEndStreamReader(
                hop.reader,
                mediaOpener,
                mediaSourceNodeId,
            ) { envelope ->
                if (layeredRelayAdmission && !envelope.isAudio && !envelope.isControl &&
                    envelope.flags and MeshEnvelope.FLAG_CODEC_CONFIG == 0
                ) {
                    relayLayerShare.add(envelope.payload.size, envelope.isDiscardable)
                }
                val activeForwarder = streamForwarder
                if (activeForwarder == null) {
                    if (relayAllowed) {
                        pendingForwardingEnvelopes += envelope
                    }
                } else {
                    val result = activeForwarder.onEnvelope(envelope)
                    if (result.queuedChildren.isNotEmpty() &&
                        envelope.flags and MeshEnvelope.FLAG_KEY_FRAME != 0
                    ) {
                        downstreamKeyFramePending.set(false)
                    }
                    if (result.keyFrameReRequest) {
                        // Experimental bounded media age: the child queue paces these (about every
                        // 1.5 s while its shed waits); the one-slot queue and the source encoder's
                        // backoff still coalesce them with local and join requests.
                        downstreamKeyFramePending.set(true)
                        keyFrameRequests.offer(Unit)
                    } else if (result.laggingChildren.isNotEmpty() &&
                        downstreamKeyFramePending.compareAndSet(false, true)
                    ) {
                        keyFrameRequests.offer(Unit)
                    }
                }
            }
            var header = streamReader.readHeader(allowAudioOnly)
            try {
                // Audio-only live: the offer's marker and the authenticated header must describe the same kind.
                if (allowAudioOnly) requireAudioLiveMarkerAgreement(selectedAudioOnly, header)
            } catch (mismatch: AudioLiveMarkerMismatchException) {
                markerMismatchMarkedAudio = mismatch.offerMarkedAudioOnly
                throw mismatch
            }
            hop.noteFirstHeader()
            parentAudioOnly = header.isAudioOnly
            parentMediaStarted = true
            forwardedGrant?.let { grant ->
                require(streamReader.meshStreamId == grant.streamId) {
                    "Forwarded-stream grant does not match its media stream"
                }
            }
            // Direct sources are handshake-bound. Forwarded audio verifies its source certificate in readHeader;
            // forwarded video has only the relay's grant, not proof from the claimed source.
            onMediaSourceAuthenticated(mediaSourceNodeId, forwardedGrant == null || header.isAudioOnly)
            val streamLabels = LabelInbox()
            labelsPayload?.let { payload ->
                // The all-zero direct-stream binding is accepted only from the direct endpoint peer
                // (PROTOCOL); a forwarded grant naming the zero stream ID gets no labels, so a
                // forwarder cannot replay a source's direct-stream title into a forwarded stream.
                val binding = if (forwardedGrant != null) {
                    LabelBinding.stream(forwardedGrant.streamId).takeIf { it != LabelBinding.DIRECT_STREAM }
                } else {
                    LabelBinding.stream(streamReader.meshStreamId)
                }
                if (binding != null) streamLabels.accept(payload, mediaSourceNodeId, binding, "stream")
                val title = streamLabels.text(LabelKind.STREAM_TITLE)
                val sourcePrefix = mediaSourceNodeId.toString().take(DiscoveryAnnouncement.NODE_PREFIX_BYTES * 2)
                if (title != null) {
                    labelSourcePrefix = sourcePrefix
                    SessionPeerLabels.update(sourcePrefix) { it.copy(streamTitle = title) }
                }
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK peer_labels stream_rx: received=${SignedLabelCodec.decodeContainerCountOrNull(payload)} | " +
                        "title=${title != null} | forwarded=${forwardedGrant != null}",
                )
            }
            if (BuildConfig.DEBUG) {
                Log.i("BluetoothVideo", "Authenticated stream identity: original=${mediaSourceNodeId.shortLabel()} | parent=${session.peerNodeId.shortLabel()} | stream=${streamReader.meshStreamId ?: "direct"} | forwarded=${forwardedGrant != null}")
            }
            if (header.isAudioOnly) {
                // No video codec, decoder or picture: the audio-only listener needs only Opus (every Android 10+ phone has it).
                onAudioLiveStream(header)
                parentTrickle?.streamBitrateKbps = AudioLiveRelayAdmission.trickleRateKbps(header.audioLivePreset)
                // A relay that had to drop its child because its own inbound fell away does not offer to relay on
                // this transport again for a while (AudioLiveRelayMemory): the child would only cost it its parent.
                val refusedForMs = AudioLiveRelayMemory.blockedForMs(transport.name, System.nanoTime() / 1_000_000L)
                if (relayAllowed && refusedForMs > 0) {
                    relayAllowed = false
                    pendingForwardingEnvelopes.clear()
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK audio_live_relay_inbound: action=refuse_relay | transport=${transport.name} | remainingMs=$refusedForMs",
                    )
                    onStatus("This phone could not carry a listener and its own link at once; it is not relaying for now.")
                }
            } else {
                require(
                    forwardedGrant != null ||
                        endpointSession.peerCapabilities.encodeCodecMask and codecCapabilityMask(header.codecId) != 0,
                ) {
                    "Sender did not authenticate support for stream codec ${header.codecId}"
                }
                require(capabilities.decodeCodecMask and codecCapabilityMask(header.codecId) != 0) {
                    "This phone cannot decode stream codec ${header.codecId}"
                }
                onVideoFormat(header)
                viewerFrameAge?.setFrameRate(header.fps)
            }
            val receiverNodeHealthPolicy = ReceiverNodeHealthPolicy(maxOf(1, header.fps))
            // Audio only: admission uses the preset in the authenticated header; an advertised rate of 0 never skips it.
            if (relayAllowed && (streamBitrateKbps > 0 || header.isAudioOnly)) {
                val relayLinkKbps = if (header.isAudioOnly) {
                    AudioLiveRelayAdmission.perLinkKbps(header.audioLivePreset)
                } else {
                    MeshBandwidthEstimator.perLinkKbps(streamBitrateKbps, header.fps)
                }
                val measuredRelayBudgetKbps = LinkCapacity.budgetKbps(transport, LinkLoadKind.RELAY, relayLinkKbps)
                // The relay floors (LinkCapacityPolicy.relayFloor): the default-on cold start, then the Experimental
                // no-ceiling floor (test_relay_no_ceiling_floor, design decision 2026-11-06); off, the cold start alone.
                val relayFloor = LinkCapacityPolicy.relayFloor(
                    LinkCapacity.learned(transport, LinkLoadKind.RELAY),
                    System.currentTimeMillis(),
                    coldStartOn = DebugTunables.relayColdStart,
                    noCeilingOn = DebugTunables.relayNoCeilingFloor,
                )
                val coldStart = relayFloor == LinkCapacityPolicy.FLOOR_COLD_START
                val relayBudgetKbps = LinkCapacityPolicy.relayBudgetKbps(
                    measuredRelayBudgetKbps,
                    relayLinkKbps,
                    relayFloor != LinkCapacityPolicy.FLOOR_NONE,
                )
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK relay_admission: cold_start=%s | needKbps=%.0f | budgetKbps=%.0f | measuredKbps=%.0f | fits=%s%s | %s".format(
                        java.util.Locale.ROOT,
                        coldStart,
                        2 * relayLinkKbps,
                        relayBudgetKbps,
                        measuredRelayBudgetKbps,
                        relayFits(relayBudgetKbps, relayLinkKbps),
                        // Only while the Experimental floor is on, so every other line keeps its exact format.
                        if (DebugTunables.relayNoCeilingFloor) " | floor=$relayFloor" else "",
                        LinkCapacity.summary(transport),
                    ),
                )
                if (!relayFits(relayBudgetKbps, relayLinkKbps) && layeredRelayAdmission) {
                    layeredRelayLinkKbps = relayLinkKbps
                    layeredRelayBudgetKbps = relayBudgetKbps
                    relayDecisionDueNs = System.nanoTime() + RELAY_LAYER_EVIDENCE_NS
                    Log.i(
                        "BluetoothVideo",
                        ("Relay decision deferred (experimental layering): receiving and forwarding the full " +
                            "stream need %.0f kbps of %.0f; measuring whether the stream is layered").format(
                            2 * relayLinkKbps,
                            relayBudgetKbps,
                        ),
                    )
                } else if (!relayFits(relayBudgetKbps, relayLinkKbps)) {
                    relayAllowed = false
                    pendingForwardingEnvelopes.clear()
                    Log.i(
                        "BluetoothVideo",
                        "Not relaying: receiving and forwarding need %.0f kbps; measured %s".format(
                            2 * relayLinkKbps,
                            LinkCapacity.summary(transport),
                        ),
                    )
                    onStatus(
                        "This phone measured that it cannot receive and forward this stream on " +
                            "${transport.shortLabel} smoothly, so it is watching without relaying.",
                    )
                }
            }
            fun startForwarder() {
                val activeStreamId = requireNotNull(streamReader.meshStreamId) {
                    "The upstream source did not provide a forwardable mesh stream"
                }
                require(streamToken != 0) { "Forwarding needs a discovered stream token" }
                val forwardingKey = mediaOpener.copyKeyForForwarding()
                streamForwarder = try {
                    ReceiverStreamForwarder(
                        context,
                        adapter,
                        transport,
                        identity,
                        session.peerNodeId,
                        mediaSourceNodeId,
                        activeStreamId,
                        forwardingKey,
                        streamToken,
                        routeCost,
                        if (header.isAudioOnly) AudioLive.presetKbps(header.audioLivePreset) else streamBitrateKbps,
                        header,
                        phyPreference,
                        ioConfig,
                        forwardingQueueCapacity(header.fps),
                        onStatus,
                        { count ->
                            val activeDecoder = decoderForForwardingHealth
                            downstreamKeyFramePending.set(false)
                            if (count > 0) {
                                forwardedChildAudioDropsAtJoin.set(
                                    audioDecoder?.droppedPackets ?: 0L,
                                )
                                receiverNodeHealthPolicy.childConnected(
                                    activeDecoder?.stats()?.renderedFrameInterval
                                        ?: LatencySnapshot(0, 0.0, 0.0, 0.0, 0.0, 0.0),
                                )
                                activeDecoder?.resetForwardingCadence()
                            } else {
                                receiverNodeHealthPolicy.childDisconnected()
                            }
                            forwardedChildCount.set(count)
                            onForwardedChildrenChanged(count)
                        },
                        onChildKeyFrameNeeded = { keyFrameRequests.offer(Unit) },
                        debugGattConnectionPriority = debugForwarderGattPriority,
                        // Experimental peer labels: forward only labels verified here, byte for byte.
                        forwardLabels = if (labelsOn) streamLabels::verifiedEncoded else null,
                        upstreamAnnouncedBytes = expectedSourceNodeIdPrefix?.takeIf { expectedSourceRotating },
                        rotatingOffers = RotatingStreamAnnouncements.offerRotates(RotatingDiscoveryFeature.enabled, transport),
                    )
                } catch (error: Exception) {
                    forwardingKey.fill(0)
                    throw error
                }.also { forwarder ->
                    pendingForwardingEnvelopes.forEach { forwarder.onEnvelope(it) }
                    pendingForwardingEnvelopes.clear()
                    forwarder.start()
                }
            }
            if (relayAllowed && relayDecisionDueNs == 0L) {
                startForwarder()
            } else if (!relayAllowed) {
                pendingForwardingEnvelopes.clear()
            }
            debugMediaTrace = if (header.isAudioOnly) null else DebugMediaTrace.startIfRequested(
                context,
                debugTraceSessionName,
                "receiver",
                header.codecId,
                header.width,
                header.height,
                header.fps,
            )
            val codecName = VideoCodecMap.label(header.codecId)
            streamReader.meshStreamId?.let {
                onStatus("Receiving serialized mesh stream $it with authenticated source and TTL.")
            }
            // A fresh clock for every link, at the base delay (ViewerLinkPlayout.kt). Latency over stalls
            // (user direction 2026-10-07): a viewer that may relay starts with room for the
            // parent-link stall when its child joins.
            // Radio (audio-only live) has its own playout profile: start 600 ms, adaptive up to 3 s.
            val streamProfile = if (header.isAudioOnly) PlaybackDelayProfile.RADIO else playbackDelayProfile
            val streamDelayMs = if (header.isAudioOnly) streamProfile.delayMs else playbackDelayMs
            val playoutClock = newViewerLinkPlayoutClock(
                streamProfile,
                streamDelayMs,
                relaying = streamForwarder != null,
                relayJoinReserveMs = RELAY_JOIN_RESERVE_MS,
            )
            viewerFrameAge?.playoutClockStarted(playoutClock.currentDelayMs)
            val videoConfig = mutableListOf<ByteArray>()
            val audioConfig = mutableListOf<ByteArray>()
            var firstAudioPresentationTimeUs: Long? = null
            var firstVideoPresentationTimeUs: Long? = null
            var timestampAlignmentLogged = false
            var audioPackets = 0L
            var bytes = 0L
            // DEBUG: stream audio counters for audio A/Bs (StreamAudioStats); the audio decoder writes the lines.
            val streamAudioStats = if (BuildConfig.DEBUG && header.hasAudio) {
                StreamAudioStats(
                    stream = streamReader.meshStreamId?.toString() ?: "direct",
                    hops = {
                        streamReader.lastMeshTtl?.let { EndToEndStreamWriter.DEFAULT_MESH_TTL - it }
                            ?: if (streamReader.meshStreamId == null) 0 else null
                    },
                    clockMs = { System.nanoTime() / 1_000_000L },
                )
            } else {
                null
            }
            val controlFailure = AtomicReference<Throwable?>()
            val controlContentSession = endpointSession

            fun requestKeyFrame() {
                keyFrameRequests.offer(Unit)
            }

            controlRunning.set(true)
            controlThread = thread(name = "BluetoothReceiverControl") {
                try {
                    val sendPeriodicHeartbeat = shouldSendPeriodicReceiverHeartbeat(
                        transport,
                        meshLink = forwardedGrant != null || forwardReceivedStreams,
                        debugDirectGattHeartbeat = !BuildConfig.DEBUG || debugDirectGattHeartbeat,
                    )
                    val heartbeatIntervalMs = receiverHeartbeatIntervalMs(transport)
                    if (BuildConfig.DEBUG && debugDirectGattHeartbeat && transport == BluetoothTransportOption.BLE_GATT) {
                        Log.i("BluetoothVideo", "BENCHMARK direct GATT heartbeat send enabled intervalMs=$heartbeatIntervalMs")
                    }
                    if (sendPeriodicHeartbeat) {
                        SecureLinkControlProtocol.writeHeartbeat(hop.writer)
                        hop.writer.flush()
                    }
                    var lastCapacityReportNs = 0L
                    var reportedTrains = 0L
                    var reportedTight = false
                    var reportedAddedDelayMs = 0L
                    // Experimental stream delivery reports: what arrived, every second (link report
                    // version 3). A stream parent cannot advertise that it accepts version 3, so this
                    // needs the parent on this build too; off, reports stay version 2 and unchanged.
                    // Reports sent only for delivery carry the delivery-only flag, so the parent's
                    // lateness trend still sees full reports exactly when they were sent before.
                    val deliveryReports = DebugTunables.streamDeliveryReports
                    val onTimeReports = DebugTunables.onTimeReports
                    var lastDeliveryReportNs = 0L
                    var onTimeReportedBytes = 0L
                    while (controlRunning.get()) {
                        val keyFrameRequested = keyFrameRequests.poll(
                            minOf(heartbeatIntervalMs, CAPACITY_REPORT_INTERVAL_MS),
                            TimeUnit.MILLISECONDS,
                        ) != null
                        if (keyFrameRequested) {
                            synchronized(controlSealLock) {
                                EndToEndStreamProtocol.writeKeyFrameRequest(
                                    hop.writer,
                                    controlContentSession.sealer,
                                )
                                hop.writer.flush()
                            }
                        }
                        // Workstream B: tell the parent what this link measured and whether a link
                        // below this relay is tight (new trains or a changed state only).
                        val nowNs = System.nanoTime()
                        val trains = hop.measureReader().measuredTrains
                        // Hop-local: media on the link from the parent reaches this phone late (its
                        // adaptive playout delay is pinned at the maximum); the stack can hide that
                        // backlog from the parent. Children's links are this phone's own business.
                        val tight = playoutClock.saturated
                        val addedDelayMs = playoutClock.addedDelayMs
                        val delayChanged = kotlin.math.abs(addedDelayMs - reportedAddedDelayMs) >= DELAY_REPORT_STEP_MS
                        val changed = trains > reportedTrains || tight != reportedTight || delayChanged
                        val reportIntervalNs = CAPACITY_REPORT_INTERVAL_MS * 1_000_000L
                        // Unmeasured link with media arriving on time: say so, or the parent decays its pace blind.
                        val receivedBytes = streamReader.delivery.snapshot().receivedBytes
                        val onTimeDue = onTimeReportDue(
                            onTimeReports,
                            hop.measureReader().genuineTrains,
                            tight,
                            receivedBytes > onTimeReportedBytes,
                            nowNs - lastCapacityReportNs,
                        )
                        val fullDue = (changed || onTimeDue) && nowNs - lastCapacityReportNs >= reportIntervalNs
                        val deliveryDue = deliveryReports && nowNs - lastDeliveryReportNs >= reportIntervalNs
                        if (fullDue || deliveryDue) {
                            val kbps = hop.measureReader().arrivalCapacityKbps() ?: 0
                            val full = fullDue && (kbps > 0 || tight != reportedTight || delayChanged || onTimeDue)
                            if (full || deliveryDue) {
                                SecureLinkControlProtocol.writeCapacityReport(
                                    hop.writer,
                                    LinkCapacityReport(
                                        kbps,
                                        tight,
                                        addedDelayMs.toInt(),
                                        if (deliveryReports) streamReader.delivery.snapshot() else null,
                                        deliveryOnly = !full,
                                    ),
                                )
                                hop.writer.flush()
                                lastDeliveryReportNs = nowNs
                                if (full) {
                                    reportedAddedDelayMs = addedDelayMs
                                    Log.i(
                                        "BluetoothVideo",
                                        "Link self-measurement: ${transport.shortLabel} from parent arrives at " +
                                            "$kbps kbps ($trains large-record trains)" +
                                            (if (tight) "; media on this link arrives late" else "") +
                                            if (onTimeDue && kbps == 0) "; media on time (unmeasured)" else "",
                                    )
                                    lastCapacityReportNs = nowNs
                                    onTimeReportedBytes = receivedBytes
                                    reportedTrains = trains
                                    if (kbps > 0) hop.measureReader().joinBurstReported()
                                    reportedTight = tight
                                }
                            }
                        }
                        if (sendPeriodicHeartbeat) {
                            SecureLinkControlProtocol.writeHeartbeat(hop.writer)
                            hop.writer.flush()
                        }
                    }
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (error: Exception) {
                    if (transport == BluetoothTransportOption.BLE_GATT) {
                        Log.w(
                            "BluetoothVideo",
                            "GATT reverse control unavailable; continuing forward media without it",
                            error,
                        )
                        watchGattParentAfterControlFailure(error, controlRunning)
                    } else {
                        controlFailure.compareAndSet(null, error)
                    }
                }
            }

            var streamEnded = false
            var streamPaused = false
            var resumePending = false
            // Audio-only live: counts pause/resume/end records, so the relay's inbound guard restarts its grace.
            var lifecycleRecords = 0
            // Experimental relay child thinning: audio keeps coming, video does not (null while the switch is off).
            val weakLinkWatch = if (DebugTunables.relayChildThinning && !header.isAudioOnly) WeakLinkPauseWatch() else null

            fun weakLinkChanged(paused: Boolean?) {
                if (paused == null) return
                weakLinkPaused = paused
                onVideoPausedWeakLink(paused)
                onStatus(if (paused) "Video paused: weak link. Audio continues." else "Video resumed.")
                Log.i("BluetoothVideo", "BENCHMARK weak_link_pause: role=stream_rx | paused=$paused")
            }

            fun logTimestampAlignmentIfReady() {
                if (timestampAlignmentLogged) return
                val audioPtsUs = firstAudioPresentationTimeUs ?: return
                val videoPtsUs = firstVideoPresentationTimeUs ?: return
                timestampAlignmentLogged = true
                Log.i(
                    "BluetoothVideo",
                    "Initial A/V timestamp delta: %.1f ms (video minus audio)".format(
                        java.util.Locale.US,
                        (videoPtsUs - audioPtsUs) / 1_000.0,
                    ),
                )
            }

            fun readMediaPacket(): StreamProtocol.Packet? {
                while (running.get() && !streamEnded) {
                    val content = try {
                        streamReader.readStreamContent()
                    } catch (withdrawal: StreamRouteWithdrawnException) {
                        try {
                            hop.sendHopControl(
                                SecureRecordType.ROUTE_CONTROL_ACK,
                                StreamRouteControlProtocol.encode(withdrawal.reason),
                            )
                            hop.writer.flush()
                        } catch (acknowledgementError: Exception) {
                            Log.w(
                                "BluetoothVideo",
                                "Could not acknowledge immediate route withdrawal",
                                acknowledgementError,
                            )
                        }
                        throw withdrawal
                    }
                    when (content) {
                        is ReceivedStreamContent.Media -> {
                            val integrity = StreamProtocol.verifyAndStripDebugIntegrity(content.packet)
                            val packet = integrity.packet
                            if (packet.isAudio && packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                                streamAudioStats?.onRecord(content.recordUnits)
                            }
                            if (!packet.isAudio &&
                                packet.flags and (
                                    StreamProtocol.FLAG_CODEC_CONFIG or StreamProtocol.FLAG_DISPLAY_ROTATION
                                ) == 0
                            ) {
                                debugMediaTrace?.record(
                                    packet.presentationTimeUs,
                                    packet.flags,
                                    packet.payload,
                                    null,
                                    null,
                                    null,
                                    null,
                                    if (integrity.verified) "verified" else "absent",
                                )
                            }
                            if (!packet.isAudio && packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                                debugStageTrace?.record("receive", packet.presentationTimeUs, packet.payload.size)
                            }
                            return packet
                        }
                        is ReceivedStreamContent.State -> {
                            lifecycleRecords++
                            parentVideoOff = content.state.cameraOff
                            if (BuildConfig.DEBUG && debugPresentationTiming) {
                                Log.i(
                                    "BluetoothVideo",
                                    "BENCHMARK received stream control: state=${content.state} nowNs=${System.nanoTime()}",
                                )
                            }
                            onStreamState(content.state)
                            weakLinkWatch?.let { watch ->
                                // A source that paused or turned its camera off sends no video on purpose.
                                val videoOff = content.state == StreamLifecycleState.PAUSED ||
                                    content.state == StreamLifecycleState.LIVE_CAMERA_OFF ||
                                    content.state == StreamLifecycleState.LIVE_MUTED_CAMERA_OFF
                                weakLinkChanged(watch.setSourceVideoOff(videoOff, System.nanoTime() / 1_000_000L))
                            }
                            when (content.state) {
                                StreamLifecycleState.LIVE -> {
                                    decoder?.resetRenderedCadence()
                                    resumePending = streamPaused && decoder != null
                                    streamPaused = false
                                    parentStreamPaused = false
                                    onStatus("Broadcaster resumed the stream.")
                                }
                                // Muted / camera off while live: not a resume unless the stream was paused; the
                                // activity shows the notice from onStreamState.
                                StreamLifecycleState.LIVE_MUTED,
                                StreamLifecycleState.LIVE_CAMERA_OFF,
                                StreamLifecycleState.LIVE_MUTED_CAMERA_OFF -> {
                                    if (streamPaused) {
                                        decoder?.resetRenderedCadence()
                                        resumePending = decoder != null
                                        streamPaused = false
                                    }
                                    // Nothing flows at all while the microphone is muted and the camera is off: the
                                    // relay's parent-silence check must not call that a dead link.
                                    parentStreamPaused = content.state == StreamLifecycleState.LIVE_MUTED_CAMERA_OFF
                                }
                                StreamLifecycleState.PAUSED -> {
                                    decoder?.resetRenderedCadence()
                                    streamPaused = true
                                    parentStreamPaused = true
                                    resumePending = false
                                    onStreamActive(false)
                                    onStatus("Stream paused by broadcaster.")
                                }
                                StreamLifecycleState.ENDED -> {
                                    val childAcknowledged = streamForwarder?.awaitTerminalAck(
                                        FORWARDED_END_ACK_TIMEOUT_MS,
                                    ) ?: true
                                    synchronized(controlSealLock) {
                                        EndToEndStreamProtocol.writeStreamEndAck(
                                            hop.writer,
                                            controlContentSession.sealer,
                                        )
                                        hop.writer.flush()
                                    }
                                    streamEnded = true
                                    onStreamActive(false)
                                    onStatus(
                                        if (childAcknowledged) {
                                            "Stream ended; local and downstream completion acknowledged."
                                        } else {
                                            "Stream ended locally; downstream acknowledgement timed out."
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                return null
            }

            fun handleAudioPacket(packet: StreamProtocol.Packet) {
                require(header.hasAudio) { "Received audio that was not declared in the stream header" }
                if (packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0) {
                    if (audioDecoder == null) audioConfig += packet.payload
                    return
                }
                playoutClock.timing(packet.presentationTimeUs)
                weakLinkWatch?.let { weakLinkChanged(it.onAudio(System.nanoTime() / 1_000_000L)) }
                val activeAudioDecoder = audioDecoder ?: inLocalMediaStage(LocalMediaStage.AUDIO_DECODER_SETUP) {
                    OpusAudioDecoder(
                        header,
                        audioConfig,
                        streamProfile,
                        playoutClock,
                        debugTraceContext = context,
                        debugTraceSession = debugTraceSessionName,
                        onLevel = onAudioLiveLevel.takeIf { header.isAudioOnly },
                        streamAudioStats = streamAudioStats,
                    )
                }.also {
                    audioDecoder = it
                    it.setMuted(listenerMuted)
                }
                inLocalMediaStage(LocalMediaStage.AUDIO_PIPELINE) { activeAudioDecoder.offer(packet) }
                if (firstAudioPresentationTimeUs == null) {
                    firstAudioPresentationTimeUs = packet.presentationTimeUs
                    logTimestampAlignmentIfReady()
                }
                audioPackets++
                bytes += packet.payload.size
            }

            if (header.isAudioOnly) {
                // Audio-only live (Design AL): no video decoder, surface or keyframe; the Opus decoder plays on the
                // RADIO clock and the relay forwarder (if any) copies the same signed records.
                onStatus(
                    "Receiving an end-to-end encrypted audio-only stream from ${endpointSession.peerNodeId.shortLabel()} " +
                        "over link ${session.peerNodeId.shortLabel()} | ${streamProfile.label} ${playoutClock.currentDelayMs} ms target" +
                        linkDetails(),
                )
                var activeNow = false
                var windowStartNs = System.nanoTime()
                var windowStartBytes = 0L
                // A relay must not pay for its child with its parent: see AudioLiveRelayInboundGuard.
                val inboundGuard = AudioLiveRelayInboundGuard(AudioLive.presetKbps(header.audioLivePreset).toDouble())
                var guardChildren = 0
                var guardLifecycle = 0
                var childHealthyReported = false
                var audioPacket = readMediaPacket()
                while (running.get() && audioPacket != null) {
                    controlFailure.get()?.let { throw IOException("Could not send child control", it) }
                    require(audioPacket.isAudio) { "A non-audio packet arrived on an audio-only stream" }
                    handleAudioPacket(audioPacket)
                    val guardNowMs = System.nanoTime() / 1_000_000L
                    if (audioPacket.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                        inboundGuard.onBytes(audioPacket.payload.size, guardNowMs)
                    }
                    if (guardLifecycle != lifecycleRecords) {
                        guardLifecycle = lifecycleRecords
                        inboundGuard.onPausedChanged(false, guardNowMs)
                    }
                    val children = forwardedChildCount.get()
                    if (children != guardChildren) {
                        guardChildren = children
                        inboundGuard.onChildCount(children, guardNowMs)
                        childHealthyReported = false
                    }
                    if (children > 0) {
                        if (inboundGuard.verdict(guardNowMs) == AudioLiveRelayInboundGuard.Verdict.DROP_CHILD) {
                            val cooldownMs = AudioLiveRelayMemory.onDropped(transport.name, guardNowMs)
                            Log.i(
                                "BluetoothVideo",
                                "BENCHMARK audio_live_relay_inbound: action=drop_child | transport=${transport.name} | " +
                                    "baselineKbps=%.1f | recentKbps=%.1f | cooldownMs=$cooldownMs".format(
                                        java.util.Locale.ROOT, inboundGuard.baselineKbps, inboundGuard.lastRecentKbps,
                                    ),
                            )
                            onStatus(
                                "Forwarding stopped on this node because carrying its listener made this phone's own " +
                                    "stream arrive too slowly. Local listening continues; the listener will find another route.",
                            )
                            streamForwarder?.withdrawForLocalCapacity(congested = true)
                            inboundGuard.onChildCount(0, guardNowMs)
                        } else if (!childHealthyReported && inboundGuard.childCarriedHealthily(guardNowMs)) {
                            childHealthyReported = true
                            AudioLiveRelayMemory.onHealthy(transport.name)
                        }
                    }
                    if (streamPaused) {
                        activeNow = false
                    } else if (!activeNow && audioPacket.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                        activeNow = true
                        onStreamActive(true)
                    }
                    val nowNs = System.nanoTime()
                    if (nowNs - windowStartNs >= METRICS_INTERVAL_NS) {
                        val kbps = ((bytes - windowStartBytes) * 8 * 1_000_000_000L / (nowNs - windowStartNs) / 1_000)
                            .toInt().coerceIn(0, 0xffff)
                        onStatus(
                            "End-to-end encrypted audio-only: $audioPackets audio packets, $kbps kbps, " +
                                "playout ${playoutClock.currentDelayMs} ms" +
                                (audioDecoder?.let { ", via ${it.decoderName}, drops ${it.droppedPackets}" } ?: "") +
                                linkDetails(),
                        )
                        if (BuildConfig.DEBUG) {
                            Log.i(
                                "BluetoothVideo",
                                "BENCHMARK media_age: role=audio_rx | playoutDelayMs=${playoutClock.currentDelayMs} | " +
                                    "addedDelayMs=${playoutClock.addedDelayMs} | saturated=${playoutClock.saturated} | " +
                                    "kbps=$kbps | drops=${audioDecoder?.droppedPackets ?: 0L}",
                            )
                        }
                        windowStartNs = nowNs
                        windowStartBytes = bytes
                    }
                    audioPacket = readMediaPacket() ?: break
                }
                return
            }
            var firstPacket: StreamProtocol.Packet? = null
            while (firstPacket == null && running.get() && !streamEnded) {
                val packet = readMediaPacket() ?: break
                when {
                    packet.isAudio -> handleAudioPacket(packet)
                    packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0 -> {
                        VideoCodecMap.requireNoCodecConfig(header.codecId, packet.flags)
                        videoConfig += packet.payload
                    }
                    packet.flags and StreamProtocol.FLAG_DISPLAY_ROTATION != 0 -> {
                        header = header.copy(rotationDegrees = StreamProtocol.decodeDisplayRotation(packet))
                        onVideoFormat(header)
                    }
                    else -> firstPacket = packet
                }
            }
            if (firstPacket == null) return
            firstVideoPresentationTimeUs = requireNotNull(firstPacket).presentationTimeUs
            logTimestampAlignmentIfReady()

            playoutClock.timing(requireNotNull(firstPacket).presentationTimeUs)
            val activeDecoder = inLocalMediaStage(LocalMediaStage.VIDEO_DECODER_SETUP) {
                VideoStreamDecoder(
                    header,
                    surface,
                    videoConfig,
                    preferLowLatency = preferLowLatencyDecoder,
                    playbackDelayProfile = playbackDelayProfile,
                    playoutClock = playoutClock,
                    preferredDecoderName = preferredDecoderName,
                    debugPresentationTiming = debugPresentationTiming,
                    debugReleaseAtPlayout = debugReleaseAtPlayout,
                    onRecoveryStateChanged = onVideoRecovery,
                    debugTraceSession = debugTraceSessionName,
                    onFrameRendered = viewerFrameAge?.let { monitor -> monitor::onFrameRendered },
                    firstPacket = firstPacket,
                    onCodedSizeChanged = { width, height ->
                        // A rung change (Auto ladder) or encoder restart: the display follows the new picture.
                        header = header.copy(width = width, height = height)
                        onVideoFormat(header)
                    },
                )
            }
            decoder = activeDecoder
            decoderForForwardingHealth = activeDecoder
            if (activeDecoder.codedWidth != header.width || activeDecoder.codedHeight != header.height) {
                // The stream started below its header's (maximum) size.
                header = header.copy(width = activeDecoder.codedWidth, height = activeDecoder.codedHeight)
                onVideoFormat(header)
            }
            onStreamActive(!streamPaused)
            onStatus(
                "Receiving end-to-end encrypted stream" +
                    (if (header.hasAudio) " with Opus audio" else "") +
                    " from ${endpointSession.peerNodeId.shortLabel()} " +
                    "over link ${session.peerNodeId.shortLabel()}: " +
                    "$codecName ${header.displayWidth}x${header.displayHeight} at ${header.fps} FPS via ${activeDecoder.decoderName}" +
                    (if (activeDecoder.lowLatencyEnabled) " (low latency)" else "") +
                    " | ${playbackDelayProfile.label} ${playoutClock.currentDelayMs} ms A/V target" +
                    linkDetails(),
            )

            var frames = 0
            var lastReportedBytes = 0L
            var lastReportNs = System.nanoTime()
            val videoReceiveInterval = RollingLatencyStats()
            val videoArrivalLateness = RollingLatencyStats()
            var previousVideoReceivedNs = 0L
            var windowLateMaxMs = 0L
            var packet = requireNotNull(firstPacket)
            while (running.get()) {
                controlFailure.get()?.let { throw IOException("Could not send child control", it) }
                inLocalMediaStage(LocalMediaStage.VIDEO_DECODER) { activeDecoder.checkFailure() }
                if (packet.isAudio) {
                    handleAudioPacket(packet)
                    packet = readMediaPacket() ?: break
                    continue
                }
                if (packet.flags and StreamProtocol.FLAG_DISPLAY_ROTATION != 0) {
                    header = header.copy(rotationDegrees = StreamProtocol.decodeDisplayRotation(packet))
                    onVideoFormat(header)
                    packet = readMediaPacket() ?: break
                    continue
                }
                // Network arrival is the only point where video may grow the adaptive delay; the
                // decoder feed and output release only look the target up (MediaPlayoutClock.targetFor).
                val arrivalTiming = videoPacketPlayoutTiming(packet, playoutClock, System.nanoTime())
                // Before the decoder gets it, so a rendered frame never trails the newest received one.
                if (packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                    viewerFrameAge?.onVideoReceived(packet.presentationTimeUs, System.nanoTime())
                }
                inLocalMediaStage(LocalMediaStage.VIDEO_DECODER) { activeDecoder.offer(packet) }
                if (packet.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                    if (resumePending) {
                        resumePending = false
                        onStreamActive(true)
                    }
                    frames++
                    bytes += packet.payload.size
                    val nowNs = System.nanoTime()
                    weakLinkWatch?.let { weakLinkChanged(it.onVideo(nowNs / 1_000_000L)) }
                    if (BuildConfig.DEBUG) {
                        if (previousVideoReceivedNs > 0) videoReceiveInterval.recordNanos(nowNs - previousVideoReceivedNs)
                        previousVideoReceivedNs = nowNs
                        arrivalTiming?.let {
                            videoArrivalLateness.recordNanos(it.latenessMs * 1_000_000L)
                            if (it.latenessMs > windowLateMaxMs) windowLateMaxMs = it.latenessMs
                        }
                    }
                    if (relayDecisionDueNs != 0L && nowNs >= relayDecisionDueNs) {
                        // Experimental temporal layering only (relayDecisionDueNs stays 0 otherwise).
                        relayDecisionDueNs = 0L
                        val baseShare = relayLayerShare.baseShare()
                        val baseLinkKbps = baseShare?.let { share ->
                            MeshBandwidthEstimator.perLinkKbps((streamBitrateKbps * share).toInt(), header.fps)
                        }
                        if (baseLinkKbps != null && layeredRelayBudgetKbps >= layeredRelayLinkKbps + baseLinkKbps) {
                            Log.i(
                                "BluetoothVideo",
                                ("Relaying a layered stream (experimental): base layer %.0f%% of video bytes; " +
                                    "receive %.0f + guaranteed forward %.0f kbps fit %.0f; the child gets the rest " +
                                    "when airtime allows").format(
                                    baseShare * 100,
                                    layeredRelayLinkKbps,
                                    baseLinkKbps,
                                    layeredRelayBudgetKbps,
                                ),
                            )
                            startForwarder()
                        } else {
                            relayAllowed = false
                            pendingForwardingEnvelopes.clear()
                            Log.i(
                                "BluetoothVideo",
                                "Not relaying: receiving and forwarding need %.0f kbps (base layer %s); measured %s".format(
                                    layeredRelayLinkKbps + (baseLinkKbps ?: layeredRelayLinkKbps),
                                    baseShare?.let { "%.0f%%".format(it * 100) } ?: "not layered",
                                    LinkCapacity.summary(transport),
                                ),
                            )
                            onStatus(
                                "This phone measured that it cannot receive and forward this stream on " +
                                    "${transport.shortLabel} smoothly, so it is watching without relaying.",
                            )
                        }
                    }
                    if (nowNs - lastReportNs >= METRICS_INTERVAL_NS) {
                        val seconds = (nowNs - lastReportNs) / 1_000_000_000.0
                        val kbps = ((bytes - lastReportedBytes) * 8 / seconds / 1_000)
                            .toInt().coerceIn(0, 0xffff)
                        val decoderStats = activeDecoder.stats()
                        val audioDropsSinceChild = (
                            (audioDecoder?.droppedPackets ?: 0L) -
                                forwardedChildAudioDropsAtJoin.get()
                            ).coerceAtLeast(0L)
                        val forwardingCadence = if (forwardedChildCount.get() > 0) {
                            activeDecoder.takeForwardingCadence(receiverNodeHealthPolicy.minimumSamples)
                        } else null
                        if (forwardedChildCount.get() > 0 &&
                            receiverNodeHealthPolicy.shouldDetachChild(
                                forwardingCadence,
                                audioDropsSinceChild,
                            )
                        ) {
                            val observedP95Ms = forwardingCadence?.p95Ms
                                ?: decoderStats.renderedFrameInterval.p95Ms
                            requestKeyFrame()
                            streamForwarder?.withdrawForLocalCapacity(congested = true)
                            Log.w(
                                "BluetoothVideo",
                                ("Detached downstream child after sustained receiver-node instability: " +
                                    "p95 %.1f ms at ${header.fps} FPS, " +
                                    "$audioDropsSinceChild audio drops since child join").format(observedP95Ms),
                            )
                            onStatus(
                                "Forwarding stopped on this node because adding its child made the received " +
                                    "audio/video unstable. Local viewing continues; the source stream is unchanged.",
                            )
                        }
                        val renderedFrames = decoderStats.renderedFrames
                        val averageFrameIntervalMs = decoderStats.renderedFrameInterval.averageMs
                        val renderedFps = if (averageFrameIntervalMs > 0.0) {
                            1_000.0 / averageFrameIntervalMs
                        } else {
                            0.0
                        }
                        val transportQueue = receiveQueueState()?.let { "${it.first}/${it.second}" }
                            ?: "unmeasured"
                        if (decoderStats.queue.needsKeyFrame) requestKeyFrame()
                        onStatus(
                            ("End-to-end encrypted $codecName ${header.displayWidth}x${header.displayHeight}: $frames video frames, " +
                                "$audioPackets audio packets, " +
                                "$renderedFrames rendered/${decoderStats.submittedForRenderFrames} submitted, $kbps kbps, %.1f FPS, " +
                                "BT queue $transportQueue, " +
                                "decoder ${decoderStats.queue.depth}/${decoderStats.queue.capacity} " +
                                "(${decoderStats.queue.oldestFrameAgeMs} ms, drops ${decoderStats.queue.droppedFrames}), " +
                                "input wait p95 %.1f ms, playout p95 %.1f ms, " +
                                "shown cadence p50/p95/p99/max %.1f/%.1f/%.1f/%.1f ms via ${activeDecoder.decoderName}" +
                                (audioDecoder?.let {
                                    ", audio via ${it.decoderName}, drops ${it.droppedPackets}"
                                }
                                    ?: "")).format(
                                renderedFps,
                                decoderStats.decoderWait.p95Ms,
                                decoderStats.scheduledRenderDelay.p95Ms,
                                decoderStats.renderedFrameInterval.p50Ms,
                                decoderStats.renderedFrameInterval.p95Ms,
                                decoderStats.renderedFrameInterval.p99Ms,
                                decoderStats.renderedFrameInterval.maxMs,
                            ) + linkDetails(),
                        )
                        if (BuildConfig.DEBUG) audioDecoder?.let {
                            Log.i("BluetoothVideo", "Audio drop reasons: ${it.dropReasonSummary()}")
                        }
                        if (BuildConfig.DEBUG) {
                            val arrival = videoReceiveInterval.snapshot()
                            val late = videoArrivalLateness.snapshot()
                            Log.i("BluetoothVideo", "Media delivery: receive cadence p50/p95/p99/max %.1f/%.1f/%.1f/%.1f ms | arrival lateness p50/p95/max %.1f/%.1f/%.1f ms | playout delay %d ms".format(arrival.p50Ms, arrival.p95Ms, arrival.p99Ms, arrival.maxMs, late.p50Ms, late.p95Ms, late.maxMs, playoutClock.currentDelayMs))
                            // Both arms: media age this viewer holds (playout delay) plus how late video
                            // arrived beyond it in this window; upstream queue age is in stream_tx/relay_tx.
                            Log.i(
                                "BluetoothVideo",
                                "BENCHMARK media_age: role=stream_rx | policy=${BoundedMediaAge.policyLabel()} | " +
                                    "playoutDelayMs=${playoutClock.currentDelayMs} | addedDelayMs=${playoutClock.addedDelayMs} | " +
                                    "lateMaxMs=$windowLateMaxMs | heldAgeMaxMs=${playoutClock.currentDelayMs + windowLateMaxMs} | " +
                                    "saturated=${playoutClock.saturated}",
                            )
                            // Time-based playout target (Q31, experimental): only while the arm is on.
                            playoutClock.timeTargetSummary()?.let { summary ->
                                Log.i(
                                    "BluetoothVideo",
                                    "BENCHMARK playout_time_target: role=stream_rx | playoutDelayMs=${playoutClock.currentDelayMs} | " +
                                        "addedDelayMs=${playoutClock.addedDelayMs} | lateMaxMs=$windowLateMaxMs | $summary",
                                )
                            }
                            windowLateMaxMs = 0L
                        }
                        viewerFrameAge?.onPlayoutDelay(playoutClock.currentDelayMs)
                        lastReportedBytes = bytes
                        lastReportNs = nowNs
                    }
                }
                if (debugPacketDelayMs > 0) Thread.sleep(debugPacketDelayMs)
                packet = readMediaPacket() ?: break
            }
        } finally {
            linkLoad.close()
            controlRunning.set(false)
            controlThread?.interrupt()
            runCatching { controlThread?.join(1_000) }
            streamForwarder?.close()
            onForwardedChildrenChanged(0)
            debugMediaTrace?.close()
            forwardedMediaOpener?.close()
            labelSourcePrefix?.let { prefix -> SessionPeerLabels.update(prefix) { it.copy(streamTitle = null) } }
            parentKeyFrameRequests = null
            parentHop = null
            hopLink?.close()
            hopLink = null
            if (groupSession != null) groupSession?.close() else contentSession?.close()
            session.close()
        }
    }

    /** A7: what the hop's carrier switch reports back to this viewer. */
    /** [hop] is this receiveSecure call's own hop (null once its `finally` ran), never the field of a later parent. */
    private fun receiverHopOwner(
        transport: BluetoothTransportOption,
        streamBitrateKbps: Int,
        hop: () -> HopLink?,
    ): HopOwner = object : HopOwner {
        override fun sendCarrierChanged(kind: CarrierKind) {
            LinkChangeEvents.carrierChanged()
        }

        override fun localKeyframeNeeded() = Unit

        override fun peerKeyframeNeeded() {
            val own = hop() ?: return
            if (parentHop === own) parentKeyFrameRequests?.offer(Unit)
        }

        override fun reassertState() = Unit

        override fun hopEnded(reason: String) {
            Log.i("BluetoothVideo", "BENCHMARK hop_end: role=stream_child | reason=${WifiLog.clean(reason)}")
            // A7 review P3-1: a late `hop-<id>-end` thread closes the hop that ended, never the next parent's.
            hop()?.close()
        }

        override fun bluetoothBudgetKbps(): Int? = LinkCapacity.measuredLinkKbps(transport, CarrierKind.BLUETOOTH)

        override fun targetKbps(): Int? = streamBitrateKbps
    }

    private fun linkDetails(
        transport: BluetoothTransportOption,
        socket: BluetoothSocket,
    ): String = if (transport == BluetoothTransportOption.BLE_L2CAP) {
        val mps = runCatching { socket.maxReceivePacketSize }.getOrDefault(0)
        " | ${bleControlServer?.phySummary ?: "PHY pending"} | RX MPS $mps"
    } else if (transport == BluetoothTransportOption.CLASSIC_RFCOMM) {
        // Android reports 0 for RFCOMM packet sizes; frames are at most 990 B (BTA_JV_DEF_RFC_MTU).
        " | RFCOMM MTU not exposed"
    } else {
        ""
    }

    override fun close() {
        debugStageTrace?.close()
        BluetoothEpoch.unregister(epochName)
        stopWifiViewerRequest()   // before the guard: a worker that already ended may not have cleared it (A8 review P1-1)
        if (!running.getAndSet(false)) return
        wifiParents.forEach { runCatching { it.close() } }
        wifiParents.clear()
        closeDecoders()
        closeSockets()
        workerThread?.interrupt()
    }

    /**
     * Bluetooth ON recovery (viewer side). The adapter going off kills the parent link in the stack, but the Java sockets and the
     * GATT pipe stay open and silent (lane15 btrec-fixon-r1 bt_rx_d2: epoch off 03:28:32, no "Connection lost", frames frozen
     * for good). Close the active parent link now, so the read fails exactly as a link loss does (stop cause LINK) and the
     * activity's route recovery runs; its scanners restart on ON through the scan registry. A forwarding viewer's downstream
     * children follow the existing forwarder shutdown. With no active link nothing is closed.
     */
    private fun onAdapterOff() {
        if (!running.get()) {
            BluetoothEpoch.unregister(epochName)
            return
        }
        if (!BluetoothOnRecovery.enabled) return
        val gatt = gattParentLink
        val socket = clientSocket
        val decision = BluetoothOnRecoveryPolicy.viewerLink(running = true, activeLink = gatt != null || socket != null)
        if (!decision.restart) {
            BluetoothOnRecovery.log(BluetoothOnRecovery.COMPONENT_VIEWER_LINK, "skipped", decision.reason)
            return
        }
        BluetoothOnRecovery.log(BluetoothOnRecovery.COMPONENT_VIEWER_LINK, "closed", decision.reason)
        // Off the main thread: closing a link of a stack that is going down can block.
        thread(name = "ReceiverBluetoothOff", isDaemon = true) {
            runCatching { gatt?.close() }
            runCatching { socket?.close() }
        }
    }

    private fun closeDecoders() {
        decoder?.close()
        decoder = null
        audioDecoder?.close()
        audioDecoder = null
    }

    private fun closeSockets() {
        runCatching { clientSocket?.close() }
        clientSocket = null
        closeServerSocket()
        bleControlServer?.close()
        bleControlServer = null
        gattVideoServer?.close()
        gattVideoServer = null
        classicViewerAdvertiser?.close()
        classicViewerAdvertiser = null
    }

    /** Closes the listener (or releases the CoC lease) exactly once even when close() and the worker race. */
    private fun closeServerSocket() {
        val lease = synchronized(this) { cocListener.also { cocListener = null } }
        runCatching { lease?.close() }
        val active = synchronized(this) { serverSocket.also { serverSocket = null } } ?: return
        runCatching { active.close() }
    }

    private companion object {
        /** Reports go out at most this often, so a delay trend reaches the parent within a second. */
        const val CAPACITY_REPORT_INTERVAL_MS = 1_000L
        const val DELAY_REPORT_STEP_MS = 50L
        /** The measured child-join arrival gap on the relay's parent link (1.7 s, trend-3641df77). */
        const val RELAY_JOIN_RESERVE_MS = 1_500L
        const val METRICS_INTERVAL_NS = 1_000_000_000L
        /** Experimental temporal layering: frames observed before a deferred relay decision. */
        const val RELAY_LAYER_EVIDENCE_NS = 2_000_000_000L
        const val CONNECTION_WAIT_TIMEOUT_MS = 15_000L
        const val FORWARDED_END_ACK_TIMEOUT_MS = 2_000L

        fun forwardingQueueCapacity(fps: Int): Int = maxOf(8, (fps + 50) / 2)
    }
}

internal fun receiverStartupFailureNotice(
    transport: BluetoothTransportOption,
    streamToken: Int,
    detail: String,
    bondFree: Boolean = ClassicFeatures.current.bondFree,
): String = if (transport == BluetoothTransportOption.CLASSIC_RFCOMM && streamToken != 0) {
    "Classic connection did not start: $detail. The sender may be busy/full or out of reach. " +
        if (bondFree) {
            "Both phones need Connect without pairing turned on, and a stream reaches only a phone this one has " +
                "already met. Or switch the broadcaster to BLE GATT."
        } else {
            "Pair the phones if they are unpaired, turn on Connect without pairing (Settings > Features) on both " +
                "phones, or switch the broadcaster to BLE GATT."
        }
} else {
    "Receiver stopped: $detail"
}

internal fun usesPeriodicReceiverHeartbeat(transport: BluetoothTransportOption): Boolean =
    transport != BluetoothTransportOption.BLE_GATT

internal fun shouldSendPeriodicReceiverHeartbeat(
    transport: BluetoothTransportOption,
    meshLink: Boolean,
    debugDirectGattHeartbeat: Boolean = true,
): Boolean = usesPeriodicReceiverHeartbeat(transport) || meshLink ||
    (transport == BluetoothTransportOption.BLE_GATT && debugDirectGattHeartbeat)

internal fun shouldExpectPeriodicChildHeartbeat(
    transport: BluetoothTransportOption,
    childCanRelay: Boolean,
    debugDirectGattHeartbeat: Boolean = true,
): Boolean = usesPeriodicReceiverHeartbeat(transport) || childCanRelay ||
    (transport == BluetoothTransportOption.BLE_GATT && debugDirectGattHeartbeat)

/**
 * Upper bound of the receiver's keepalive wait. The control loop also wakes every [CAPACITY_REPORT_INTERVAL_MS]
 * (1 s) for link reports and writes a keepalive on every pass, so the period on the air is about 1 s (PROTOCOL
 * "Lifecycle, liveness and route withdrawal"); the timeouts below were measured with that period.
 */
internal fun receiverHeartbeatIntervalMs(@Suppress("UNUSED_PARAMETER") transport: BluetoothTransportOption): Long =
    2_000L

internal fun receiverHeartbeatTimeoutNs(transport: BluetoothTransportOption): Long =
    if (transport == BluetoothTransportOption.BLE_GATT) 6_000_000_000L else 8_000_000_000L

private fun recoveryDiscoveryFlag(previousUpstreamNodeIdPrefix: ByteArray?): Int =
    if (previousUpstreamNodeIdPrefix == null) 0 else DiscoveryAnnouncement.FLAG_ROUTE_RECOVERY

/** How often, and after how much total silence, a child judges its relay parent link dead. */
internal const val PARENT_SILENCE_CHECK_MS = 500L
internal const val PARENT_SILENCE_NS = 5_000_000_000L

/**
 * A7: a relayed parent's link watches judge raw Bluetooth bytes. Once Wi-Fi accepts the parent's media, the standby is
 * idle on purpose (A3 keeps it alive with heartbeats); the watches must not close a healthy parent for that silence.
 */
internal fun parentLinkJudgedPaused(paused: Boolean, acceptKind: CarrierKind): Boolean = paused || acceptKind.isWifi

/**
 * A relayed parent link is dead when no byte arrived for [PARENT_SILENCE_NS] since the later of the
 * last read and the link start, unless the parent said the stream is paused.
 */
internal fun relayParentSilent(nowNs: Long, lastReadNs: Long, startedNs: Long, paused: Boolean): Boolean =
    !paused && nowNs - maxOf(lastReadNs, startedNs) >= PARENT_SILENCE_NS

/**
 * A relayed parent link that delivers fewer than 1/[RelayParentTrickle.FLOOR_DIVISOR] of the stream's
 * bitrate over a whole [PARENT_SILENCE_NS] window is starved, though not silent: mesh4-8211dc8f 0001
 * M31 -> A15s carried ~12 kbps of a 300 kbps stream (first keyframe after 17 s) with read gaps under
 * 0.5 s. HCI snoop (relaylink-*) put a starved relay child at 4-37 kbps, a healthy one at 296-378, and
 * the 2M one-PDU-per-event pair (ASUS -> A15s) at ~120. A floor of bitrate/8 (37.5 kbps at 300) closes
 * only the starved band. Windows that saw the parent's pause, or an unknown bitrate, never judge; the caller also passes
 * the relay's weak-link pause (Experimental relay child thinning at audio only) as a pause (switch audit B10).
 * Pure (bytes and clock passed in). DEBUG `test_relay_parent_trickle=false` is the control arm; the whole test runs only
 * inside the child's parent-link watch (RelayRecoveryConfig.parentWatch).
 */
internal class RelayParentTrickle(@Volatile var streamBitrateKbps: Int, startedNs: Long) {
    private var windowStartNs = startedNs
    private var windowStartBytes = 0L
    private var pausedInWindow = false

    /**
     * Returns the window's kbps when the link is starved, else null. [bytesRead] is the running total.
     * Until [armed] (the parent's stream header arrived) the window restarts every tick: connection
     * setup, authentication and the relay's grant are not media delivery. mesh4ab-52681dbf-loop14b-1
     * 0002/0011/0012 closed healthy relayed children 2.8-3.6 s after authentication (8-29 kbps) because
     * the first window began at socket start.
     */
    fun onTick(nowNs: Long, bytesRead: Long, paused: Boolean, armed: Boolean = true): Int? {
        if (!armed) {
            windowStartNs = nowNs
            windowStartBytes = bytesRead
            pausedInWindow = false
            return null
        }
        if (paused) pausedInWindow = true
        val elapsedNs = nowNs - windowStartNs
        if (elapsedNs < PARENT_SILENCE_NS) return null
        val kbps = ((bytesRead - windowStartBytes) * 8_000_000L / elapsedNs).toInt()
        val judged = !pausedInWindow && streamBitrateKbps > 0
        windowStartNs = nowNs
        windowStartBytes = bytesRead
        pausedInWindow = paused
        return if (judged && kbps.toLong() * FLOOR_DIVISOR < streamBitrateKbps) kbps else null
    }

    companion object {
        const val FLOOR_DIVISOR = 8
    }
}

/** A relay carries its incoming link and one child link on the same radio. */
internal fun relayFits(budgetKbps: Double, perLinkKbps: Double): Boolean = budgetKbps >= 2 * perLinkKbps

/**
 * Gaps between socket reads that returned data, below all app buffering: tells radio/stack
 * delivery bursts apart from bunching inside the receiver.
 */
internal class SocketReadGapStats {
    private val gaps = RollingLatencyStats()
    @Volatile private var lastReadNs = 0L
    private val bytes = java.util.concurrent.atomic.AtomicLong(0)
    /** When a read last returned data (System.nanoTime), 0 before the first. */
    val lastReadAtNs: Long get() = lastReadNs
    /** Bytes the socket has returned so far. */
    val bytesRead: Long get() = bytes.get()

    fun wrap(input: java.io.InputStream): java.io.InputStream = object : java.io.FilterInputStream(input) {
        // Not `mark(...)`: inside a FilterInputStream that name is InputStream.mark(readlimit), a no-op here
        // (b0171c5 shipped that shadowing; mesh4-f40e9f42 read gaps were 0.0 and every relayed child
        // closed a healthy parent 5 s after start as "silent").
        override fun read(): Int = super.read().also { if (it >= 0) onRead(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) onRead(it) }
    }

    private fun onRead(count: Int) {
        bytes.addAndGet(count.toLong())
        val now = System.nanoTime()
        val last = lastReadNs
        if (last != 0L) gaps.recordNanos(now - last)
        lastReadNs = now
    }

    fun summary(): String {
        val snapshot = gaps.snapshot()
        return " | socket read gap p95/p99/max %.1f/%.1f/%.1f ms".format(snapshot.p95Ms, snapshot.p99Ms, snapshot.maxMs)
    }
}
