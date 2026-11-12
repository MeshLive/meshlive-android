package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Opaque full-duplex forwarding for a private call. Endpoint encryption stays caller-to-callee. */
class BluetoothPrivateCallRelay(
    private val onStatus: (String) -> Unit,
    private val onStopped: () -> Unit,
) : Closeable {
    private data class TransportConnection(
        val input: InputStream,
        val output: OutputStream,
        val closeConnection: () -> Unit,
        val socket: BluetoothSocket? = null,
        /** Current LE PHY summary of this hop ("PHY TX 2M / RX 2M"); empty when unknown. */
        val phySummary: () -> String = { "" },
    ) : Closeable {
        override fun close() = closeConnection()
    }

    /**
     * Experimental relayed calls: the parent link of an on-demand relay, already authenticated by the
     * call endpoint that accepted it (the caller or the previous relay, SETUP read). This relay closes
     * it when the call ends, exactly like the parent connection it accepts itself in [start].
     */
    internal class OnDemandParent(
        val session: SecureBluetoothSession,
        val closeConnection: () -> Unit,
        val phySummary: () -> String = { "" },
    )

    /** Restart guard: the reader of the static relay's child link while it waits for its caller (null when off). */
    @Volatile private var childWatch: RelayChildWatch? = null

    /** Relayed calls only: tears the chain down when the caller never completes the call setup. */
    @Volatile private var chainGuard: RelayChainGuard? = null

    /** Set by close(): a relay closed before it started never starts (relayed calls, on-demand mode). */
    @Volatile private var cancelled = false

    /** PHY this relay requested on its callee hop (it is central there); 2M unless remembered bad. */
    @Volatile private var calleeLinkPhy: BlePhyPreference = BlePhyPreference.TWO_M
    /** System.nanoTime when both hops authenticated and forwarding started (0 = not yet). */
    @Volatile private var forwardingStartNs = 0L
    @Volatile private var lastCallerProgressNs = 0L
    @Volatile private var lastCalleeProgressNs = 0L
    @Volatile private var callRelayTransport: BluetoothTransportOption = BluetoothTransportOption.BLE_GATT

    private val running = AtomicBoolean(false)
    private val closeNotBeforeNanos = AtomicLong(0L)
    @Volatile private var worker: Thread? = null
    @Volatile private var reverseWorker: Thread? = null
    /** Classic: the RFCOMM server socket; LE CoC: a lease on the role's long-lived listener. */
    @Volatile private var upstreamServerSocket: java.io.Closeable? = null
    @Volatile private var downstreamControl: BleL2capControlConnection? = null
    @Volatile private var upstreamControl: BleL2capControlServer? = null
    @Volatile private var upstreamAdvertiser: BleDiscoveryAdvertiser? = null
    @Volatile private var upstreamGattServer: GattVideoServer? = null
    @Volatile private var downstream: TransportConnection? = null
    @Volatile private var upstream: TransportConnection? = null
    @Volatile private var downstreamSession: SecureBluetoothSession? = null
    @Volatile private var upstreamSession: SecureBluetoothSession? = null
    /** A7: the two hops this relay terminates (upstream = caller/previous relay, downstream = next relay/callee). */
    @Volatile private var upHop: HopLink? = null
    @Volatile private var downHop: HopLink? = null

    @SuppressLint("MissingPermission")
    fun start(
        context: Context,
        adapter: BluetoothAdapter,
        callee: BleL2capPeer,
        transport: BluetoothTransportOption,
        /** Relay chains (PrivateCallRelayChain); false keeps the one-relay rule exactly. */
        chainEnabled: Boolean = DebugTunables.callRelayChain,
    ) {
        check(running.compareAndSet(false, true)) { "Private-call relay is already running" }
        callRelayTransport = transport
        worker = thread(name = "BluetoothPrivateCallRelay") {
            try {
                require(callee.announcement.isPrivateCallOffer()) {
                    "Selected phone is not available for a private call"
                }
                require(callee.announcement.transportOption() == transport) {
                    "Selected callee is not using ${transport.shortLabel}"
                }
                val identity = AndroidNodeIdentityStore().getOrCreate()
                val localNodeId = identity.nodeId.toByteArray()
                require(
                    PrivateCallRelayChain.admitsOffer(
                        callee.announcement,
                        localNodeId.copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
                        chainEnabled,
                    ),
                ) { "Private-call offer is outside the relay chain limit" }
                val capabilities = PrivateCallRelayChain.relayCapabilities(
                    LocalNodeCapabilities.forTransport(transport, maxChildren = 1),
                    chainEnabled,
                )
                // Rotating IDs, phase 1b: the hello toward the child also signs link bit 3 (the parent-facing hello
                // never does: a relay cannot open the callee's ID), and a cost-0 version 5 callee must open the RDI
                // this relay mirrors, over its link BDO1 (this relay is the connector and sends none).
                val rotatingOffer = callee.announcement.rotating && RotatingDiscoveryFeature.enabled &&
                    transport != BluetoothTransportOption.CLASSIC_RFCOMM
                val childCapabilities = if (rotatingOffer) {
                    capabilities.copy(transportMask = capabilities.transportMask or RotatingDiscoveryIds.LINK_HELLO_BIT)
                } else {
                    capabilities
                }
                val checkChild = { session: SecureBluetoothSession ->
                    val opensOffer = rotatingOffer && callee.announcement.routeCost == 0 && run {
                        val opening = LinkOpeningExchange.exchange(session, childCapabilities, emptyList())
                        val result = DiscoverySelectionCheck.check(
                            callee.announcement,
                            RotatingDiscoveryIds.ROLE_CALL_OFFER,
                            session.peerNodeId.toByteArray(),
                            opening,
                        )
                        android.util.Log.i(
                            "BluetoothVideo",
                            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotid_open: role=call_relay_child | result=$result",
                        )
                        result == DiscoverySelectionCheck.Result.PASS
                    }
                    PrivateCallRelayChain.childProblem(
                        callee.announcement,
                        session.peerNodeId.toByteArray(),
                        session.peerCapabilities.maxChildren,
                        localNodeId,
                        chainEnabled,
                        session.peerCapabilities.transportMask,
                        openingOpensOffer = opensOffer,
                    )?.let { problem -> throw IllegalArgumentException(problem) }
                    Unit
                }
                val (childConnection, childSession) = if (
                    transport == BluetoothTransportOption.CLASSIC_RFCOMM
                ) {
                    connectAuthenticatedClassicCallee(
                        context, adapter, callee, identity, capabilities,
                        PrivateCallRelayChain.verifyChildLinkPrefix(callee.announcement, chainEnabled), checkChild,
                        PrivateCallRelayChain.classicExactChildOnly(callee.announcement, chainEnabled),
                    )
                } else {
                    // Adaptive PHY memory on the hop this relay opens as central: 1M from the start
                    // for a remembered-bad callee (or, for a chain, the relay advertising its offer).
                    calleeLinkPhy = AdaptivePhyMemory.phyFor(
                        transport,
                        callee.announcement.nodeIdPrefix,
                        BlePhyPreference.TWO_M,
                        "call-relay",
                        relayedOffer = callee.announcement.routeCost > 0,
                    )
                    openStartChild(context, callee, transport, identity, childCapabilities, checkChild)
                }
                downstreamSession = childSession
                // Restart guard: the child is read from now on, so a child link that closes before the caller joins
                // stops this relay instead of being held for up to 180 s and joined to the caller dead.
                childWatch = if (DebugTunables.relayedRestartGuard) RelayChildWatch(childSession) else null
                childWatch?.let { watch -> reverseWorker = watchChild(watch, childSession) }
                onStatus("Private-call relay authenticated callee ${childSession.peerNodeId.shortLabel()}.")
                if (callee.announcement.routeCost > 0) {
                    onStatus(
                        "Private-call relay chain: child relay ${childSession.peerNodeId.shortLabel()} reaches " +
                            "callee ${callee.announcement.nodeIdPrefix.joinToString("") { "%02x".format(it) }} through " +
                            "${callee.announcement.routeCost} relay(s); this relay advertises " +
                            "${callee.announcement.routeCost + 1}.",
                    )
                }

                val callerConnection = acceptCaller(
                    context,
                    adapter,
                    transport,
                    callee.announcement,
                    stopClassicAdvertisingOnAccept = chainEnabled,
                    parentWaitMs = if (chainEnabled) PrivateCallRelayChain.PARENT_WAIT_MS else null,
                )
                upstream = callerConnection
                val callerSession = SecureBluetoothHandshake.respond(
                    BufferedInputStream(callerConnection.input, BUFFER_BYTES),
                    BufferedOutputStream(callerConnection.output, BUFFER_BYTES),
                    identity,
                    capabilities,
                    // Experimental hidden identities (BMH3): null while the switch is off, BMH2 exactly as before.
                    bmh3 = Bmh3Wiring.responder(emptyList(), identity.nodeId.toByteArray(), pseudonyms = false),
                    raiseTag = true,
                )
                upstreamSession = callerSession
                require(callerSession.peerCapabilities.transportMask and transport.capabilityMask != 0) {
                    "Caller did not authenticate ${transport.shortLabel} support"
                }
                PrivateCallRelayChain.parentProblem(
                    callerSession.peerNodeId.toByteArray(),
                    childSession.peerNodeId.toByteArray(),
                    callee.announcement.nodeIdPrefix,
                    localNodeId,
                    chainEnabled,
                )?.let { problem -> throw IllegalArgumentException(problem) }
                learnClassicPeerAddress(callerConnection.socket, callerSession.peerNodeId, "incoming", callerSession.peerPseudonymous)
                onStatus(
                    "Relaying an endpoint-encrypted private call between " +
                        "${callerSession.peerNodeId.shortLabel()} and ${childSession.peerNodeId.shortLabel()}; " +
                        "this relay cannot decrypt call media.",
                )

                val upHop = HopLink(
                    role = HopCapsPlan.HopRole.RELAY_UPSTREAM,
                    session = callerSession,
                    closeRaw = { upstream?.close() },
                    anyLocalSwitchOn = WifiSwitchHub::anyOn,
                    log = WifiLog::emit,
                    owner = relayHopOwner("up"),
                    newAgent = ::newHopAgent,
                )
                val downHop = HopLink(
                    role = HopCapsPlan.HopRole.RELAY_DOWNSTREAM,
                    session = childSession,
                    closeRaw = { downstream?.close() },
                    anyLocalSwitchOn = WifiSwitchHub::anyOn,
                    log = WifiLog::emit,
                    owner = relayHopOwner("down"),
                    newAgent = ::newHopAgent,
                    neighbourIsRelay = false,
                )
                this.upHop = upHop
                this.downHop = downHop
                forwardingStartNs = System.nanoTime()
                lastCallerProgressNs = forwardingStartNs
                lastCalleeProgressNs = forwardingStartNs
                val watch = childWatch
                if (watch != null) {
                    watch.parentJoined()
                } else {
                    reverseWorker = thread(name = "BluetoothPrivateCallRelayReverse") {
                        copyRecords(downHop, upHop, "callee")
                    }
                }
                copyRecords(upHop, downHop, "caller")
            } catch (error: Exception) {
                if (running.get()) {
                    onStatus("Private-call relay stopped: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                running.set(false)
                childWatch?.abandon()
                closeResources()
                worker = null
                onStopped()
            }
        }
    }

    /**
     * Experimental relayed calls (DECISIONS Design C): serves a SETUP that the call endpoint read from its
     * parent. Connects to the next hop as central (the target itself when [nextBudget] is 0, else another
     * helper that receives a regenerated SETUP), then reports READY upstream through [onReady] and
     * copies the ordered end-to-end records exactly as [start] does. [onFailed] runs once, only before
     * READY, with the reason for the FAIL reply; afterwards a failure just closes both links.
     * The hop admission is the existing chain admission ([PrivateCallRelayChain.childProblem] and
     * [PrivateCallRelayChain.parentProblem]). One absolute deadline ([remainingMs]) covers connect,
     * authentication and the next hop's answer: when it passes without a reply, FAIL(timeout) goes upstream
     * and both links close, whatever the next hop is doing (a hop that never answers its hello cannot hold
     * this phone).
     */
    @SuppressLint("MissingPermission")
    internal fun startOnDemand(
        context: Context,
        adapter: BluetoothAdapter,
        child: BleL2capPeer,
        transport: BluetoothTransportOption,
        parent: OnDemandParent,
        target: ByteArray,
        nextBudget: Int,
        /** The prefix of the helper offer selected as next hop (nextBudget > 0); its link identity must equal it. */
        hopPrefix: ByteArray?,
        remainingMs: Int,
        onReady: (relays: Int) -> Unit,
        onFailed: (RelaySetupFailure) -> Unit,
        /** Rotating IDs + relayed calls: [target] and [hopPrefix] are role-0 RDIs, checked over a link `BDO1`; SETUP message 4. */
        rotating: Boolean = false,
    ) {
        // Cancellation is terminal: a close() that arrived before this start (the endpoint closed in between)
        // must not be undone by the start.
        if (cancelled) {
            runCatching { onFailed(RelaySetupFailure.REFUSED) }
            onStopped()
            return
        }
        check(running.compareAndSet(false, true)) { "Private-call relay is already running" }
        if (cancelled) {
            running.set(false)
            runCatching { onFailed(RelaySetupFailure.REFUSED) }
            onStopped()
            return
        }
        callRelayTransport = transport
        val replied = AtomicBoolean(false)
        val replyReady = { relays: Int -> if (replied.compareAndSet(false, true)) onReady(relays) }
        val replyFailed = { reason: RelaySetupFailure -> if (replied.compareAndSet(false, true)) onFailed(reason) }
        val deadline = thread(name = "BluetoothPrivateCallRelayDeadline", isDaemon = true) {
            try {
                Thread.sleep(remainingMs.toLong())
            } catch (_: InterruptedException) {
                return@thread
            }
            if (!replied.get()) {
                runCatching { replyFailed(RelaySetupFailure.TIMEOUT) }
                onStatus("Private-call relay setup missed its deadline")
                close()
            }
        }
        worker = thread(name = "BluetoothPrivateCallRelayOnDemand") {
            var stage = OnDemandStage.CONNECTING
            val startedNs = System.nanoTime()
            try {
                require(transport != BluetoothTransportOption.CLASSIC_RFCOMM) { "Classic is not relayed on demand" }
                require(child.announcement.transportOption() == transport) {
                    "Next hop is not using ${transport.shortLabel}"
                }
                val rotatingHop = rotating && RotatingDiscoveryFeature.enabled
                val childSession = openOnDemandChild(
                    context, transport, parent, child, target, nextBudget, hopPrefix, rotating, startedNs, remainingMs,
                    bmh3 = false,
                ) { stage = it }
                downstreamSession = childSession
                val relaysBelow = if (nextBudget > 0) {
                    stage = OnDemandStage.WAITING_FOR_NEXT
                    val left = RelayedCalls.nextHopRemainingMs(
                        remainingMs,
                        (System.nanoTime() - startedNs) / 1_000_000L,
                    ) ?: throw java.io.IOException("No time left for the next relay")
                    val setupId = RelaySetupCodec.newSetupId()
                    RelaySetupExchange.writeMessage(
                        childSession,
                        RelaySetupMessage.Setup(target, nextBudget, left, setupId, rotating = rotatingHop),
                    )
                    val reply = RelaySetupExchange.awaitReply(
                        childSession,
                        setupId,
                        left + RelaySetupExchange.SETUP_REPLY_GRACE_MS,
                    ) { downstream?.close() }
                    when (reply) {
                        is RelaySetupMessage.Ready -> reply.relays
                        is RelaySetupMessage.Fail -> {
                            replyFailed(reply.reason)
                            return@thread
                        }
                        is RelaySetupMessage.Setup, is RelaySetupMessage.Seek -> throw IllegalArgumentException("Setup reply expected")
                    }
                } else {
                    0
                }
                forwardOnDemand(
                    parent,
                    childSession,
                    relaysBelow,
                    replied,
                    replyReady,
                    beforeReady = { stage = OnDemandStage.RELAYING },
                    afterReady = { deadline.interrupt() },
                )
            } catch (thrown: Throwable) {
                // An Error here (a missing platform method, say) must not take the app down with a stranger's
                // request: it ends this duty and the caller hears FAIL (asRelayDutyException).
                val error = thrown.asRelayDutyException() ?: throw thrown
                if (!replied.get()) {
                    android.util.Log.i(
                        "BluetoothVideo",
                        RelayHopPolicy.hopFailureLine(
                            stage.name, error, (System.nanoTime() - startedNs) / 1_000_000L, hopAttempts, BuildConfig.DEBUG,
                        ),
                    )
                    runCatching { replyFailed(onDemandFailure(stage, error)) }
                }
                if (running.get()) {
                    onStatus("Private-call relay stopped: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                deadline.interrupt()
                running.set(false)
                chainGuard = null
                closeResources()
                worker = null
                onStopped()
            }
        }
    }

    /**
     * Opens and authenticates the on-demand next hop [child] (the target when [nextBudget] is 0, else the next helper whose
     * identity must open [hopPrefix]); shared by SETUP ([startOnDemand], BMH2 onward link as before) and the Design OR
     * search ([startSeek]: BMH3 onward link, [extraCheck] for `RELAY_SEEK`, [allowRetry] false once the dial cap is near).
     */
    private fun openOnDemandChild(
        context: Context,
        transport: BluetoothTransportOption,
        parent: OnDemandParent,
        child: BleL2capPeer,
        target: ByteArray,
        nextBudget: Int,
        hopPrefix: ByteArray?,
        rotating: Boolean,
        startedNs: Long,
        remainingMs: Int,
        bmh3: Boolean,
        allowRetry: Boolean = true,
        extraCheck: (SecureBluetoothSession) -> Unit = {},
        setStage: (OnDemandStage) -> Unit,
    ): SecureBluetoothSession {
        val identity = AndroidNodeIdentityStore().getOrCreate()
        val localNodeId = identity.nodeId.toByteArray()
        val capabilities = RelayedCalls.linkCapabilities(
            PrivateCallRelayChain.relayCapabilities(
                LocalNodeCapabilities.forTransport(transport, maxChildren = 1),
                chainEnabled = true,
            ),
            featureOn = true,
            helperActive = true,
        )
        val rotatingHop = rotating && RotatingDiscoveryFeature.enabled
        val childCapabilities = if (rotatingHop) {
            capabilities.copy(transportMask = capabilities.transportMask or RotatingDiscoveryIds.LINK_HELLO_BIT)
        } else {
            capabilities
        }
        // Amendment CP: the onward edge of a SETUP duty speaks BMH3 with a declared throwaway (never BMH2 in pseudonym mode);
        // decided once for the duty, so no retry falls back to BMH2. A Design OR search ([bmh3]) keeps its own BMH3 onward
        // link and identity checks: the two switches compose by path, not on one edge.
        val pseudonymousEdges = DebugTunables.relayedPseudonymousEdges && !bmh3
        val hopOffer = if (nextBudget > 0) {
            RelayedCalls.hopOfferFor(requireNotNull(hopPrefix), rotatingHop)
        } else {
            child.announcement
        }
        val onwardBmh3 = if (pseudonymousEdges) {
            Bmh3Wiring.relayHop(context, hopOffer, RotatingDiscoveryFeature.enabled, wifiMarker = WifiHopPlumbing.BUILD_HANDLES_BWF1)
        } else {
            null
        }
        val checkChild = { session: SecureBluetoothSession ->
            // A next hop that declared a throwaway is not bound by identity (Amendment CP): the caller's end-to-end
            // check authenticates the target; a real key is still bound to the selected offer.
            val pseudonymousChild = pseudonymousEdges && session.peerPseudonymous
            // Rotating hop: this relay is the connector (count 0) and the next hop's link BDO1 must open the
            // ID this relay selected: the next helper's offer, or the target's own offer for the last hop.
            val openedSelected = rotatingHop && !pseudonymousChild && run {
                val opening = LinkOpeningExchange.exchange(session, childCapabilities, emptyList())
                val expected = if (nextBudget > 0) {
                    RelayedCalls.helperOfferFor(requireNotNull(hopPrefix))
                } else {
                    child.announcement
                }
                val result = DiscoverySelectionCheck.check(
                    expected,
                    RotatingDiscoveryIds.ROLE_CALL_OFFER,
                    session.peerNodeId.toByteArray(),
                    opening,
                )
                android.util.Log.i(
                    "BluetoothVideo",
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotid_open: role=relay_hop | result=$result",
                )
                result == DiscoverySelectionCheck.Result.PASS
            }
            if (!(pseudonymousChild && nextBudget == 0)) {
                PrivateCallRelayChain.childProblem(
                    child.announcement,
                    session.peerNodeId.toByteArray(),
                    session.peerCapabilities.maxChildren,
                    localNodeId,
                    chainEnabled = true,
                    session.peerCapabilities.transportMask,
                    openingOpensOffer = openedSelected && nextBudget == 0,
                )?.let { problem -> throw IllegalArgumentException(problem) }
            }
            // The actual bearer bit, not an intersection of whole masks (extension bits 4 and 6 would pass).
            require(session.peerCapabilities.transportMask and transport.capabilityMask != 0) {
                "Next hop did not authenticate ${transport.shortLabel}"
            }
            // Never splice a parent into its own call (as in start()): the parent is not the child and
            // not the target.
            PrivateCallRelayChain.parentProblem(
                parent.session.peerNodeId.toByteArray(),
                session.peerNodeId.toByteArray(),
                target,
                localNodeId,
                chainEnabled = true,
            )?.let { problem -> throw IllegalArgumentException(problem) }
            extraCheck(session)
            if (nextBudget > 0) {
                require(RelayedCalls.linkAcceptsSetup(session.peerCapabilities)) {
                    "Next relay did not authenticate as a relayed-call helper"
                }
                require(
                    if (pseudonymousChild) {
                        true
                    } else if (rotatingHop) {
                        openedSelected
                    } else {
                        hopPrefix != null && session.peerNodeId.toByteArray()
                            .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES).contentEquals(hopPrefix)
                    },
                ) { "Next relay is not the helper that was selected" }
            }
        }
        // The bad-2M memory key of the next hop's offer, as a caller's dial keys it: a rotating offer recognised as
        // one contact keys by that contact's node (test_phy_memory_contact_key); before, a helper looked up the
        // epoch's rotating ID, which no entry is ever written under, so a remembered pair always started on 2M.
        val childMemoryNode = AdaptivePhyMemory.offerMemoryNode(
            rotating = child.announcement.rotating,
            nodePrefix = child.announcement.nodeIdPrefix,
            contactKey = DebugTunables.phyMemoryContactKey,
            recognisedContact = { RotatingDiscovery.recognize(child.announcement).singleOrNull()?.contact?.shortLabel() },
        )
        calleeLinkPhy = if (DebugTunables.relayedHop1m) {
            // Experimental test_relayed_hop_1m (default off): the requested/effective difference is logged.
            android.util.Log.i(
                "BluetoothVideo",
                "BENCHMARK link_phy requested=2M effective=1M reason=relayed_hop_1m source=experiment link=call-relay",
            )
            BlePhyPreference.ONE_M_ADAPTIVE
        } else {
            AdaptivePhyMemory.phyFor(
                transport,
                child.announcement.nodeIdPrefix,
                BlePhyPreference.TWO_M,
                "call-relay",
                relayedOffer = child.announcement.routeCost > 0,
                memoryNode = childMemoryNode,
            )
        }
        return openNextHop(child, target, hopPrefix, transport, startedNs, remainingMs, allowRetry) { phyRetryPossible ->
            setStage(OnDemandStage.CONNECTING)
            val device = nextHopDevice ?: child.device
            val connection = adoptNextHop(
                connectNextHop(context, device, transport, startedNs, remainingMs, sameRetry = !phyRetryPossible && allowRetry),
            )
            setStage(OnDemandStage.AUTHENTICATING)
            connection to {
                authenticateCallee(
                    connection, identity, childCapabilities, checkChild,
                    bmh3Context = if (bmh3) context else null,
                    selected = if (bmh3) child.announcement else null,
                    bmh3Options = onwardBmh3?.invoke(),
                ).also { session ->
                    AdaptivePhyMemory.bind(
                        runCatching { device.address }.getOrNull(),
                        transport,
                        session.peerNodeId.shortLabel(),
                    )
                }
            }
        }
    }

    /** Design OR: the next hop announced no `RELAY_SEEK` (an older or BMH2 helper): skipped, never sent a SEEK. */
    private class NoSeekException : IllegalStateException("Next relay does not accept a search")

    /**
     * Design OR (DEBUG prototype, DECISIONS "Design OR" revision 3): the search for a `SEEK` (or for the one ID of a plain
     * SETUP, [plainId]) admitted by the endpoint with the phone-wide relay duty held by [owner]. [RelaySeekSearch] decides
     * the order, collection, caps and final reason; this relay dials. The first target link or child READY becomes the
     * relayed call exactly as [startOnDemand]'s; every other outcome answers FAIL. One monotonic deadline covers it all.
     */
    @SuppressLint("MissingPermission")
    internal fun startSeek(
        context: Context,
        adapter: BluetoothAdapter,
        transport: BluetoothTransportOption,
        parent: OnDemandParent,
        budget: Int,
        nonce: ByteArray?,
        token: ByteArray?,
        plainId: ByteArray?,
        skip: List<ByteArray>,
        allowanceMs: Long,
        host: RelayHelperHost,
        owner: Any,
        onReady: (relays: Int) -> Unit,
        onFailed: (RelaySetupFailure) -> Unit,
    ) {
        val startedMs = android.os.SystemClock.elapsedRealtime()
        val deadlineMs = startedMs + allowanceMs
        if (cancelled || !running.compareAndSet(false, true)) {
            runCatching { onFailed(RelaySetupFailure.REFUSED) }
            host.seekFinished(owner, startedMs, ready = false)
            RelayDutyReservation.shared.release(owner)
            onStopped()
            return
        }
        callRelayTransport = transport
        onDial = { host.seekCharge(owner, dial = true) }
        onHandshake = { host.seekCharge(owner, dial = false) }
        val replied = AtomicBoolean(false)
        val replyReady = { relays: Int ->
            if (replied.compareAndSet(false, true)) {
                host.seekFinished(owner, startedMs, ready = true)
                onReady(relays)
            }
        }
        val replyFailed = { reason: RelaySetupFailure ->
            if (replied.compareAndSet(false, true)) {
                host.seekFinished(owner, startedMs, ready = false)
                onFailed(reason)
            }
        }
        val deadline = thread(name = "BluetoothPrivateCallRelaySeekDeadline", isDaemon = true) {
            try {
                Thread.sleep(allowanceMs)
            } catch (_: InterruptedException) {
                return@thread
            }
            if (!replied.get()) {
                runCatching { replyFailed(RelaySetupFailure.TIMEOUT) }
                onStatus("Private-call relay search missed its deadline")
                close()
            }
        }
        worker = thread(name = "BluetoothPrivateCallRelaySeek") {
            var stage = OnDemandStage.CONNECTING
            val startedNs = System.nanoTime()
            val timeLeftMs = { (deadlineMs - android.os.SystemClock.elapsedRealtime()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() }
            val zeroTarget = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
            val dropChild = {
                runCatching { downstreamSession?.close() }
                downstreamSession = null
                runCatching { downstream?.close() }
                downstream = null
                runCatching { downstreamControl?.close() }
                downstreamControl = null
            }
            val effects = object : RelaySeekSearch.Effects<BleL2capPeer> {
                override fun offers(sinceMs: Long): List<SeenOffer<BleL2capPeer>> {
                    val epoch = runCatching { RotatingDiscovery.currentEpoch() }.getOrDefault(-1L)
                    return RelayedCallsRuntime.seekOffers.since(sinceMs).filter {
                        it.announcement.transportOption() == transport && !RelaySeekHelperState.badAddresses.bad(it.address, epoch)
                    }
                }

                override fun scan(untilMs: Long, done: () -> Boolean): Long = host.seekScan(owner, untilMs, done)

                override fun isOwnId(rdi: ByteArray): Boolean =
                    runCatching { RotatingDiscovery.ownRdis(RotatingDiscoveryIds.ROLE_CALL_OFFER) }.getOrDefault(emptyList())
                        .any { it.contentEquals(rdi) }

                override fun stopped(): Boolean = cancelled || !running.get() || replied.get()

                override fun dialTarget(
                    offer: SeenOffer<BleL2capPeer>,
                    allowRetry: Boolean,
                    attempts: (Int) -> Unit,
                ): RelaySeekSearch.DialResult {
                    val before = connectCount.get()
                    return try {
                        downstreamSession = openOnDemandChild(
                            context, transport, parent, offer.peer, offer.rdi, nextBudget = 0, hopPrefix = null,
                            rotating = true, startedNs = System.nanoTime(), remainingMs = timeLeftMs(), bmh3 = true,
                            allowRetry = allowRetry,
                        ) { stage = it }
                        RelaySeekSearch.DialResult.LINKED
                    } catch (error: java.io.IOException) {
                        dropChild()
                        RelaySeekSearch.DialResult.FAILED
                    } catch (error: Exception) {
                        dropChild()
                        runCatching { RelaySeekHelperState.badAddresses.record(offer.address, RotatingDiscovery.currentEpoch()) }
                        RelaySeekSearch.DialResult.IDENTITY
                    } finally {
                        attempts(connectCount.get() - before)
                    }
                }

                override fun dialChild(
                    offer: SeenOffer<BleL2capPeer>,
                    remainingMs: Int,
                    allowRetry: Boolean,
                    attempts: (Int) -> Unit,
                ): RelaySeekSearch.ChildResult {
                    val before = connectCount.get()
                    try {
                        val session = openOnDemandChild(
                            context, transport, parent, offer.peer, zeroTarget, nextBudget = 1, hopPrefix = offer.rdi,
                            rotating = true, startedNs = System.nanoTime(), remainingMs = timeLeftMs(), bmh3 = true,
                            allowRetry = allowRetry,
                            extraCheck = { session ->
                                if (session.peerRelaySeek and RelaySeek.FEATURE_SEEK_V1 == 0) throw NoSeekException()
                            },
                        ) { stage = it }
                        downstreamSession = session
                        stage = OnDemandStage.WAITING_FOR_NEXT
                        // The child's allowance is what is left of this search's one deadline, less the per-hop reserve.
                        val left = (timeLeftMs() - RelayedCalls.PER_HOP_RESERVE_MS).coerceAtMost(RelayedCalls.SETUP_DEADLINE_MS)
                        if (left < RelayedCalls.MIN_REMAINING_MS || left > remainingMs) {
                            dropChild()
                            return RelaySeekSearch.ChildResult.Closed
                        }
                        val setupId = RelaySetupCodec.newSetupId()
                        RelaySetupExchange.writeMessage(
                            session,
                            if (plainId != null) {
                                RelaySetupMessage.Setup(plainId, 1, left, setupId, rotating = true)
                            } else {
                                RelaySetupMessage.Seek(1, left, setupId, requireNotNull(nonce), requireNotNull(token), skip)
                            },
                        )
                        return when (
                            val reply = RelaySetupExchange.awaitReply(session, setupId, left + RelaySetupExchange.SETUP_REPLY_GRACE_MS) {
                                downstream?.close()
                            }
                        ) {
                            is RelaySetupMessage.Ready -> RelaySeekSearch.ChildResult.Ready(reply.relays)
                            is RelaySetupMessage.Fail -> {
                                dropChild()
                                RelaySeekSearch.ChildResult.Fail(reply.reason)
                            }
                            else -> {
                                dropChild()
                                RelaySeekSearch.ChildResult.Closed
                            }
                        }
                    } catch (error: NoSeekException) {
                        dropChild()
                        return RelaySeekSearch.ChildResult.NoSeek
                    } catch (error: java.io.IOException) {
                        dropChild()
                        return RelaySeekSearch.ChildResult.Closed
                    } catch (error: Exception) {
                        dropChild()
                        return RelaySeekSearch.ChildResult.Identity
                    } finally {
                        attempts(connectCount.get() - before)
                    }
                }
            }
            try {
                require(transport != BluetoothTransportOption.CLASSIC_RFCOMM) { "Classic is not relayed on demand" }
                val search = RelaySeekSearch(
                    budget, nonce, token, plainId, skip, deadlineMs, android.os.SystemClock::elapsedRealtime,
                    RelaySeekSearch.Config(RelaySeekFeature.scanMs, RelaySeekFeature.fanout, RelaySeekFeature.connectMs),
                    effects,
                )
                val outcome = search.run()
                val line = { result: String, reason: String? ->
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") + RelaySeekLog.line(
                        "helper", result, budget = budget, scanMs = search.scannedMs, dials = search.dials, reason = reason,
                    ) + " | cache=${search.usedCache}"
                }
                when (outcome) {
                    is RelaySeekSearch.Outcome.TargetLinked -> {
                        android.util.Log.i("BluetoothVideo", line("matched", null))
                        forwardOnDemand(
                            parent, requireNotNull(downstreamSession), 0, replied, replyReady,
                            beforeReady = { stage = OnDemandStage.RELAYING }, afterReady = { deadline.interrupt() },
                        )
                    }
                    is RelaySeekSearch.Outcome.ChildReady -> {
                        android.util.Log.i("BluetoothVideo", line("forwarded", null))
                        forwardOnDemand(
                            parent, requireNotNull(downstreamSession), outcome.relays, replied, replyReady,
                            beforeReady = { stage = OnDemandStage.RELAYING }, afterReady = { deadline.interrupt() },
                        )
                    }
                    is RelaySeekSearch.Outcome.Failed -> {
                        android.util.Log.i("BluetoothVideo", line("failed", outcome.reason.label))
                        replyFailed(outcome.reason)
                    }
                }
            } catch (thrown: Throwable) {
                val error = thrown.asRelayDutyException() ?: throw thrown
                if (!replied.get()) runCatching { replyFailed(onDemandFailure(stage, error)) }
                if (running.get()) onStatus("Private-call relay search stopped: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                deadline.interrupt()
                if (!replied.get()) runCatching { replyFailed(RelaySetupFailure.TIMEOUT) }
                running.set(false)
                chainGuard = null
                closeResources()
                worker = null
                RelayDutyReservation.shared.release(owner)
                onStopped()
            }
        }
    }

    /**
     * The on-demand relay once its child hop is authenticated: READY goes upstream, then the ordered
     * end-to-end records are copied both ways until either side ends. A separate member so JVM tests can
     * run this stage over real record sessions ([markStartedForTest]); they cannot open Bluetooth.
     * [replied] is the once-only reply latch shared with the setup deadline: when the deadline already
     * answered FAIL, nothing is forwarded.
     */
    internal fun forwardOnDemand(
        parent: OnDemandParent,
        childSession: SecureBluetoothSession,
        relaysBelow: Int,
        replied: AtomicBoolean,
        replyReady: (relays: Int) -> Unit,
        beforeReady: () -> Unit = {},
        afterReady: () -> Unit = {},
    ) {
        upstreamSession = parent.session
        // Not InputStream.nullInputStream(): API 33, minSdk is 29 (RelayParentStreams).
        upstream = TransportConnection(
            RelayParentStreams.emptyInput(),
            RelayParentStreams.discardingOutput(),
            parent.closeConnection,
            phySummary = parent.phySummary,
        )
        onStatus(
            "Relaying an endpoint-encrypted private call between ${parent.session.peerNodeId.shortLabel()} " +
                "and ${childSession.peerNodeId.shortLabel()}; this relay cannot decrypt call media.",
        )
        val upHop = HopLink(
            role = HopCapsPlan.HopRole.RELAY_UPSTREAM,
            session = parent.session,
            closeRaw = { upstream?.close() },
            anyLocalSwitchOn = WifiSwitchHub::anyOn,
            log = WifiLog::emit,
            owner = relayHopOwner("up"),
            newAgent = ::newHopAgent,
        )
        val downHop = HopLink(
            role = HopCapsPlan.HopRole.RELAY_DOWNSTREAM,
            session = childSession,
            closeRaw = { downstream?.close() },
            anyLocalSwitchOn = WifiSwitchHub::anyOn,
            log = WifiLog::emit,
            owner = relayHopOwner("down"),
            newAgent = ::newHopAgent,
            neighbourIsRelay = relaysBelow > 0,
        )
        this.upHop = upHop
        this.downHop = downHop
        forwardingStartNs = System.nanoTime()
        lastCallerProgressNs = forwardingStartNs
        lastCalleeProgressNs = forwardingStartNs
        val guard = RelayChainGuard(forwardingStartNs)
        chainGuard = guard
        beforeReady()
        // READY goes upstream only when the child hop is proven; the copy loops start right after.
        if (replied.get()) return
        replyReady(relaysBelow + 1)
        afterReady()
        thread(name = "BluetoothPrivateCallRelayGuard", isDaemon = true) {
            while (running.get() && !guard.satisfied) {
                guard.violation(System.nanoTime())?.let { reason ->
                    if (running.getAndSet(false)) {
                        onStatus("Private-call relay closed: $reason")
                        closeConnections()
                        worker?.interrupt()
                        reverseWorker?.interrupt()
                    }
                    return@thread
                }
                try {
                    Thread.sleep(GUARD_POLL_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
        reverseWorker = thread(name = "BluetoothPrivateCallRelayReverse") {
            copyRecords(downHop, upHop, "callee")
        }
        copyRecords(upHop, downHop, "caller")
    }

    /** JVM tests mark the duty started exactly as [startOnDemand] does, then call [forwardOnDemand]. */
    internal fun markStartedForTest() {
        check(running.compareAndSet(false, true)) { "Private-call relay is already running" }
    }

    internal enum class OnDemandStage { CONNECTING, AUTHENTICATING, WAITING_FOR_NEXT, RELAYING }

    /** The FAIL reason for an error that ended a setup before READY (PROTOCOL: 1 no route, 3 child auth, 4 timeout). */
    internal fun onDemandFailure(
        stage: OnDemandStage,
        error: Exception,
        pseudonymousEdges: Boolean = DebugTunables.relayedPseudonymousEdges,
    ): RelaySetupFailure = RelayIdentityPolicy.failureBeforeReady(
        pseudonymousEdges = pseudonymousEdges,
        dutyRefused = error is RelayDutyError,
        waitingForNext = stage == OnDemandStage.WAITING_FOR_NEXT,
        authenticating = stage == OnDemandStage.AUTHENTICATING,
        ioError = error is java.io.IOException,
        identityProblem = error is IllegalArgumentException,
    )

    /**
     * Restart guard: the callee-to-caller reader of the static relay, started when the child authenticates. A child
     * link that fails before the caller joined stops the relay (and its offer for the caller); otherwise the first
     * record it held is forwarded first and the ordinary forwarding follows.
     */
    private fun watchChild(watch: RelayChildWatch, childSession: SecureBluetoothSession): Thread =
        thread(name = "BluetoothPrivateCallRelayReverse") {
            val first = watch.readFirst()
            if (first is RelayChildWatch.First.ClosedBeforeParent) {
                if (running.get()) {
                    val reason = first.error.message ?: first.error.javaClass.simpleName
                    android.util.Log.i(
                        "BluetoothVideo",
                        (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                            "call_relay_child: event=closed_before_parent | child=${childSession.peerNodeId.shortLabel()} | " +
                            "reason=${first.error.javaClass.simpleName}",
                    )
                    onStatus("Private-call relay stopped: callee link closed before the caller joined ($reason)")
                    close()
                }
                return@thread
            }
            if (!watch.awaitParent()) return@thread
            // The hops are published before parentJoined(); after the first held record the callee is read through its
            // HopLink, so a BWF1 is handled by the hop (never forwarded) exactly as on the reader without the guard.
            val caller = upHop ?: return@thread
            val callee = downHop ?: return@thread
            val firstRead: () -> SecureRecord = when (first) {
                is RelayChildWatch.First.Record -> ({ first.record })
                is RelayChildWatch.First.Failed -> ({ throw first.error })
                is RelayChildWatch.First.ClosedBeforeParent -> return@thread
            }
            copyRecords(callee, caller, "callee", firstRead)
        }

    private fun copyRecords(
        source: HopLink,
        destination: HopLink,
        direction: String,
        /** Restart guard: the record (or failure) [watchChild] already read from [source] before the caller joined. */
        firstRead: (() -> SecureRecord)? = null,
    ) {
        var forwardedRecord = false
        var reading = true
        val counters = RelayForwardCounters()
        var ended: Exception? = null
        var pendingFirst = firstRead
        try {
            while (running.get()) {
                reading = true
                val held = pendingFirst
                pendingFirst = null
                val record = held?.invoke() ?: source.reader.read()
                reading = false
                counters.onRead()
                markProgress(direction)
                require(
                    record.type == SecureRecordType.END_TO_END_HANDSHAKE ||
                        record.type == SecureRecordType.END_TO_END_CONTENT ||
                        record.type == SecureRecordType.LINK_HEARTBEAT,
                ) { "Unexpected $direction private-call record ${record.type}" }
                chainGuard?.onRecord(direction == "caller", record.type)
                // Capacity reports describe one hop; across this relay only liveness is forwarded, as
                // a plain version-1 heartbeat (an empty payload is rejected and closed the call).
                if (record.type == SecureRecordType.LINK_HEARTBEAT) {
                    SecureLinkControlProtocol.writeHeartbeat(destination.writer)
                } else {
                    destination.writer.write(record.type, record.payload)
                }
                destination.writer.flush()
                if (record.type == SecureRecordType.END_TO_END_CONTENT) destination.noteContentForwarded()
                counters.onForwarded(record)
                markProgress(if (direction == "caller") "callee" else "caller")
                forwardedRecord = true
            }
        } catch (error: Exception) {
            ended = error
            if (forwardedRecord) armConnectionCloseDrain()
            // Diagnostics only: a failure in the hop-loss note must never keep this relay from closing its links.
            if (running.get()) {
                runCatching { noteHopLoss(error, if (reading) source.session else destination.session, if (reading) direction else otherSide(direction)) }
            }
            if (running.getAndSet(false)) {
                onStatus("Private-call $direction path closed: ${error.message ?: error.javaClass.simpleName}")
                closeConnections()
                worker?.interrupt()
                reverseWorker?.interrupt()
            }
        } finally {
            // Diagnostics only: per-direction counts at close, so a hop_integrity line on the next phone can be matched
            // to the record this relay sent (its hop sequence is destinationWritten at that moment).
            runCatching {
                android.util.Log.i(
                    "BluetoothVideo",
                    counters.line(
                        direction,
                        stage = if (ended == null) "stopped" else if (reading) "read" else "write",
                        ended = ended,
                        sourceRead = source.session.reader.recordsRead,
                        destinationWritten = destination.session.writer.recordsWritten,
                    ),
                )
            }
        }
    }

    /** [side] ("caller" or "callee") is the hop a record was just read from or written to. */
    private fun markProgress(side: String) {
        if (side == "caller") lastCallerProgressNs = System.nanoTime() else lastCalleeProgressNs = System.nanoTime()
    }

    private fun otherSide(side: String) = if (side == "caller") "callee" else "caller"

    /**
     * A hop failed (IOException): log whether it was an early loss on 2M. The bad-2M memory records it
     * only with `test_relay_phy_guard=false` (relaycall-659efc38 enabled recording; relaycall-b9726176-
     * loop4-1 0004/0005 showed cascaded closes recording healthy S24, M31 and ASUS hops).
     */
    private fun noteHopLoss(error: Exception, session: SecureBluetoothSession, side: String) {
        if (error !is java.io.IOException || callRelayTransport == BluetoothTransportOption.CLASSIC_RFCOMM) return
        val hop = runCatching { session.peerNodeId.shortLabel() }.getOrNull() ?: return
        val connection = if (side == "callee") downstream else upstream
        val phy = runCatching { connection?.phySummary?.invoke() }.getOrNull().orEmpty()
        val requestedTwoM = if (side == "callee") calleeLinkPhy == BlePhyPreference.TWO_M else true
        val stalledAt = callLinkStalledAtNs(
            if (side == "callee") lastCalleeProgressNs else lastCallerProgressNs,
            if (side == "callee") lastCallerProgressNs else lastCalleeProgressNs,
            System.nanoTime(),
        )
        val early = CallRelayHopLoss.isEarly(
            forwardingStartNs, stalledAt, phy, requestedTwoM, NodeLinkLoad.sendLinks(callRelayTransport),
        )
        android.util.Log.i(
            "BluetoothVideo",
            "BENCHMARK adaptive_phy: link=call-relay-$side | node=$hop | early_loss=$early | " +
                "stalledAfterMs=${(stalledAt - forwardingStartNs) / 1_000_000} | phy=${phy.ifEmpty { "unknown" }} | " +
                "error=${error.message ?: error.javaClass.simpleName}",
        )
        if (!early) return
        // A relay's hops share its radio and it closes both when either fails, so a hop loss here is not
        // evidence about that pair's 2M ([RelayUpstream]; `test_relay_phy_guard=false` records it again).
        if (AdaptivePhyMemory.skipsRelayingHop(hopRelays = true, guard = DebugTunables.relayPhyGuard)) {
            android.util.Log.i(
                "BluetoothVideo",
                "BENCHMARK adaptive_phy: link=memory | node=$hop | bad2m=not_recorded | reason=early_loss | hop_relays=true",
            )
            return
        }
        AdaptivePhyMemory.recordBad(callRelayTransport, hop, "early_loss")
    }

    /** Connect attempts of the on-demand next hop (1, or 2 after [connectNextHop]'s retry); for the failure line. */
    @Volatile private var hopAttempts = 1

    /**
     * The on-demand next hop's connect, with one logged retry of the same connect when the first failed fast (no link
     * formed) and enough of the SETUP deadline is left ([RelayHopPolicy.connectRetryAllowed], `test_relayed_hop_retry`).
     * Relay-showcase b08: the M31 helper's CoC connect on its fresh ACL failed after 341 ms ("read failed") and the call
     * failed with 14 s of the deadline unused.
     */
    private fun connectNextHop(
        context: Context,
        device: BluetoothDevice,
        transport: BluetoothTransportOption,
        startedNs: Long,
        remainingMs: Int,
        /** False while a 2M -> 1M retry is still possible: that retry replaces the same-PHY one. */
        sameRetry: Boolean = true,
        config: ConnectRetryConfig = ConnectRetryConfig.current(),
    ): TransportConnection = RelayHopPolicy.connectWithOneRetry(
        // Experimental test_unified_connect_policy: no same-connect retry layer; the client loop retries inside the deadline.
        enabled = DebugTunables.relayedHopRetry && sameRetry && !config.unified,
        startedNs = startedNs,
        remainingMs = remainingMs,
        nowNs = System::nanoTime,
        stopped = { cancelled || !running.get() },
        sleep = Thread::sleep,
        log = { line -> android.util.Log.i("BluetoothVideo", line) },
        debug = BuildConfig.DEBUG,
        beforeRetry = {
            runCatching { downstreamControl?.close() }
            downstreamControl = null
        },
        onAttempt = { hopAttempts = it },
        discard = { connection -> runCatching { connection.close() } },
    ) { connectCallee(context, device, transport, RelayHopPolicy.unifiedHopDeadline(config.unified, startedNs, remainingMs, System.nanoTime())) }

    /**
     * Review P2 (2026-10-26): [downstream] is published before the cancellation check, so either close() sees it and closes
     * it, or this check sees the cancellation (close() sets [cancelled] first) and closes it here; a next hop that connected
     * after the SETUP deadline never reaches an unbounded handshake.
     */
    private fun adoptNextHop(connection: TransportConnection): TransportConnection {
        downstream = connection
        if (cancelled || !running.get()) {
            runCatching { connection.close() }
            downstream = null
            throw IOException(RelayHopPolicy.STOPPED_MESSAGE)
        }
        return connection
    }

    /** The CoC socket of a next-hop connect still in progress: SETUP expiry closes it ([closeResources]). */
    @Volatile private var pendingCocSocket: BluetoothSocket? = null

    /** The next hop's current device after a 2M -> 1M retry rescan; null = the admitted sighting's. */
    @Volatile private var nextHopDevice: BluetoothDevice? = null

    /**
     * AP7 F3 (`test_relay_start_hop_guard`, Experimental, off): the DEBUG static relay's LE child hop. Off, or a link that
     * already starts on 1M, is exactly today's connect + authenticate + bind. On a first 2M attempt, the on-demand hop's 3 s
     * guard and one 1M retry apply; a stalled first attempt records the authenticated peer bad on 2M. No rescan: the static
     * benchmark shape may run with relayed calls off and therefore has no fresh-offer table; the same selected address is
     * retried after [RelayHopPolicy.RETRY_GAP_MS]. Production relayed calls use [startOnDemand] -> [openNextHop], never this.
     */
    private fun openStartChild(
        context: Context,
        callee: BleL2capPeer,
        transport: BluetoothTransportOption,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        checkChild: (SecureBluetoothSession) -> Unit,
    ): Pair<TransportConnection, SecureBluetoothSession> {
        var retried = false
        var firstStalled = false
        while (true) {
            var guard: RelayHopPolicy.HopSetupStallGuard? = null
            try {
                val connection = connectCallee(context, callee.device, transport)
                downstream = connection
                if (RelayHopPolicy.startHopRetry(
                        enabled = DebugTunables.relayStartHopGuard,
                        firstOnTwoM = calleeLinkPhy == BlePhyPreference.TWO_M,
                        retried = retried,
                    )
                ) {
                    guard = RelayHopPolicy.HopSetupStallGuard(RelayHopPolicy.HOP_SETUP_STALL_MS) {
                        runCatching { connection.close() }
                    }.also { it.start() }
                }
                val session = authenticateCallee(
                    connection, identity, capabilities, checkChild, context, callee.announcement,
                )
                if (guard?.settle() == false) {
                    session.close()
                    throw IOException("Relay start hop setup stalled")
                }
                AdaptivePhyMemory.bind(
                    runCatching { callee.device.address }.getOrNull(),
                    transport,
                    session.peerNodeId.shortLabel(),
                )
                if (retried && TwoMRetryMemory.remembers(firstStalled, firstHopAuthenticated = false)) {
                    android.util.Log.i(
                        "BluetoothVideo",
                        "BENCHMARK link_phy requested=2M effective=1M reason=setup_retry source=adaptive " +
                            "link=call-relay-start node=${session.peerNodeId.shortLabel()}",
                    )
                    AdaptivePhyMemory.recordBad(transport, session.peerNodeId.shortLabel(), "relay_start_hop_retry")
                }
                return connection to session
            } catch (error: IOException) {
                if (guard?.settle() == false) firstStalled = true
                if (!RelayHopPolicy.startHopRetry(
                        enabled = DebugTunables.relayStartHopGuard,
                        firstOnTwoM = calleeLinkPhy == BlePhyPreference.TWO_M,
                        retried = retried,
                    )
                ) throw error
                runCatching { downstream?.close() }
                downstream = null
                runCatching { downstreamControl?.close() }
                downstreamControl = null
                retried = true
                calleeLinkPhy = BlePhyPreference.ONE_M_ADAPTIVE
                Thread.sleep(RelayHopPolicy.RETRY_GAP_MS)
            }
        }
    }

    /**
     * Opens and authenticates the on-demand next hop ([attempt] connects and returns the connection plus its authentication).
     * Measured per link, not per device family (`test_relayed_hop_phy_retry`): a first attempt on 2M whose connect fails or
     * whose hop authentication has not finished [RelayHopPolicy.HOP_SETUP_STALL_MS] after the link opened is closed and
     * retried once on 1M, to the next hop's freshest advert, while [RelayHopPolicy.RETRY_MIN_LEFT_MS] of the SETUP deadline
     * is left; a 1M retry that authenticates records the authenticated hop peer bad on 2M, so the next dial of that pair
     * (call, message or relay hop) starts on 1M. Relay-showcase: the A15s -> ASUS child hop set up 0 of 4 times on 2M
     * ("read failed", ASUS EOFException) and 3 of 4 on 1M.
     */
    private fun openNextHop(
        child: BleL2capPeer,
        /** The call's target: the hop itself when [hopPrefix] is null. */
        target: ByteArray,
        /** The next helper's own ID when [child] is a routed dial (its announcement names the target, not the helper). */
        hopPrefix: ByteArray?,
        transport: BluetoothTransportOption,
        startedNs: Long,
        remainingMs: Int,
        /** Design OR: false when the search's dial cap leaves no room for a retry. */
        allowRetry: Boolean = true,
        attempt: (phyRetryPossible: Boolean) -> Pair<TransportConnection, () -> SecureBluetoothSession>,
    ): SecureBluetoothSession {
        nextHopDevice = null
        var phyRetried = false
        // Review A6: only a stalled first 2M attempt is 2M evidence; a connect failure is not.
        var firstStalled = false
        val unified = ConnectRetryConfig.current().unified
        while (true) {
            val phyRetryPossible = allowRetry && !phyRetried &&
                RelayHopPolicy.phyRetryEligible(DebugTunables.relayedHopPhyRetry, calleeLinkPhy == BlePhyPreference.TWO_M)
            var guard: RelayHopPolicy.HopSetupStallGuard? = null
            var connected = false
            try {
                val (connection, authenticate) = attempt(phyRetryPossible)
                connected = true
                if (phyRetryPossible) {
                    guard = RelayHopPolicy.HopSetupStallGuard(RelayHopPolicy.HOP_SETUP_STALL_MS) {
                        runCatching { connection.close() }
                    }.also { it.start() }
                }
                val session = authenticate()
                if (guard?.settle() == false) {
                    session.close()
                    throw IOException("Relay hop setup stalled")
                }
                if (phyRetried && TwoMRetryMemory.remembers(firstStalled, firstHopAuthenticated = false)) {
                    android.util.Log.i(
                        "BluetoothVideo",
                        "BENCHMARK link_phy requested=2M effective=1M reason=setup_retry source=adaptive link=call-relay " +
                            "node=${session.peerNodeId.shortLabel()}",
                    )
                    AdaptivePhyMemory.recordBad(transport, session.peerNodeId.shortLabel(), "relay_hop_setup_retry")
                }
                return session
            } catch (error: IOException) {
                if (guard?.settle() == false) firstStalled = true
                val leftMs = remainingMs - (System.nanoTime() - startedNs) / 1_000_000L
                if (!phyRetryPossible || cancelled || !running.get() || leftMs < RelayHopPolicy.RETRY_MIN_LEFT_MS) throw error
                // Experimental test_unified_connect_policy: a connect failure already had its budgeted retries in the client.
                if (!RelayHopPolicy.phyRetryAfter(unified, connected)) throw error
                val failedAtMs = android.os.SystemClock.elapsedRealtime()
                android.util.Log.i(
                    "BluetoothVideo",
                    RelayHopPolicy.phyRetryLine(
                        RelayHopPolicy.hex(hopPrefix ?: child.announcement.nodeIdPrefix), guard?.stalled == true, leftMs, error, BuildConfig.DEBUG,
                    ),
                )
                runCatching { downstream?.close() }
                downstream = null
                runCatching { downstreamControl?.close() }
                downstreamControl = null
                phyRetried = true
                calleeLinkPhy = BlePhyPreference.ONE_M_ADAPTIVE
                // The next hop drops the dead link and may re-advertise under a new address: the helper's own reach table
                // keeps its freshest sighting; wait briefly for one newer than the failure, else redial the known address.
                // A routed hop is looked up by the helper's own ID, never by the target ID its dial announcement carries.
                val until = failedAtMs + RelayHopPolicy.rescanBudgetMs(leftMs)
                var fresh: BleL2capPeer? = null
                do {
                    Thread.sleep(RelayHopPolicy.RETRY_GAP_MS)
                    fresh = RelayedCallsRuntime.freshOffer(hopPrefix, target, child.announcement.rotating, failedAtMs)
                } while (fresh == null && android.os.SystemClock.elapsedRealtime() < until && running.get() && !cancelled)
                nextHopDevice = fresh?.device
                android.util.Log.i(
                    "BluetoothVideo",
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_hop: role=helper | event=rediscover | " +
                        "found=${fresh != null} | sameAddress=${fresh?.device?.address == child.device.address} | " +
                        "waitedMs=${android.os.SystemClock.elapsedRealtime() - failedAtMs}",
                )
            }
        }
    }

    /** Design OR: physical connection attempts and handshakes of this relay's onward hops (each one is charged). */
    @Volatile private var onDial: () -> Unit = {}
    @Volatile private var onHandshake: () -> Unit = {}
    private val connectCount = java.util.concurrent.atomic.AtomicInteger(0)

    @SuppressLint("MissingPermission")
    private fun connectCallee(
        context: Context,
        device: BluetoothDevice,
        transport: BluetoothTransportOption,
        /** The on-demand next hop's setup deadline (Experimental unified connect policy only), else null. */
        deadline: SetupDeadline? = null,
    ): TransportConnection {
        connectCount.incrementAndGet()
        runCatching { onDial() }
        return when (transport) {
            BluetoothTransportOption.CLASSIC_RFCOMM ->
                error("Classic callees connect through connectAuthenticatedClassicCallee")
            BluetoothTransportOption.BLE_L2CAP -> {
                val socket = BleCocClient.connect(
                    context,
                    device,
                    onStatus,
                    calleeLinkPhy,
                    "call-relay-connect",
                    // Registered before connect() blocks, so the SETUP deadline's close() ends a connect still in progress.
                    onSocket = { socket ->
                        pendingCocSocket = socket
                        if (cancelled || !running.get()) runCatching { socket.close() }
                    },
                    deadline = deadline,
                ) { downstreamControl = it }
                socketConnection(socket) { downstreamControl?.phySummary.orEmpty() }
            }
            BluetoothTransportOption.BLE_GATT -> gattConnection(
                GattVideoClient.open(
                    context,
                    device,
                    calleeLinkPhy,
                    BleConnectionPriority.HIGH,
                    onStatus,
                    GattLinkPolicy.relayAcknowledgedWrites(),
                    deadline = deadline,
                ),
            )
        }
    }

    /**
     * The callee over Classic through the shared connector: class-filtered bonded candidates with
     * per-attempt deadlines. This relay used to page every bonded device (earbuds, cars) with an
     * unbounded secure connect().
     */
    @SuppressLint("MissingPermission")
    private fun connectAuthenticatedClassicCallee(
        context: Context,
        adapter: BluetoothAdapter,
        callee: BleL2capPeer,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        verifyLinkPrefix: Boolean,
        checkChild: (SecureBluetoothSession) -> Unit,
        exactDeviceOnly: Boolean,
    ): Pair<TransportConnection, SecureBluetoothSession> {
        val connected = ClassicConnector.connect(
            context,
            adapter,
            callee.device,
            callee.announcement.nodeIdPrefix,
            ClassicRole.CALL_RELAY,
            verifyLinkPrefix = verifyLinkPrefix,
            totalBudgetMs = CLASSIC_SESSION_BUDGET_MS,
            allowInquiry = !exactDeviceOnly,
            isCancelled = { !running.get() },
            onSocket = { socket -> downstream = socketConnection(socket) },
            handshake = { socket ->
                SecureBluetoothHandshake.initiate(
                    BufferedInputStream(socket.inputStream, BUFFER_BYTES),
                    BufferedOutputStream(socket.mediaOutputStream(forwarding = true), BUFFER_BYTES),
                    identity,
                    capabilities,
                    bmh3 = Bmh3Wiring.initiator(context, callee.announcement, Bmh3Policy.Layer.LINK, wifiMarker = WifiHopPlumbing.BUILD_HANDLES_BWF1),
                    raiseTag = true,
                )
            },
            verify = { session ->
                require(session.peerCapabilities.transportMask and capabilities.transportMask != 0) {
                    "Callee did not authenticate this transport"
                }
                checkChild(session)
            },
            onStatus = onStatus,
            exactDeviceOnly = exactDeviceOnly,
        )
        val connection = socketConnection(connected.socket)
        downstream = connection
        return connection to connected.session
    }

    private fun authenticateCallee(
        connection: TransportConnection,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        checkChild: (SecureBluetoothSession) -> Unit,
        /** Experimental hidden identities (BMH3): the chain relay passes its context and the offer it relays; null = BMH2. */
        bmh3Context: Context? = null,
        selected: DiscoveryAnnouncement? = null,
        /** Amendment CP: the onward relay edge's BMH3 options (a fresh declared throwaway); wins over [bmh3Context]. */
        bmh3Options: Bmh3InitiatorOptions? = null,
    ): SecureBluetoothSession {
        runCatching { onHandshake() }
        val session = SecureBluetoothHandshake.initiate(
            BufferedInputStream(connection.input, BUFFER_BYTES),
            BufferedOutputStream(connection.output, BUFFER_BYTES),
            identity,
            capabilities,
            bmh3 = bmh3Options ?: bmh3Context?.let {
                Bmh3Wiring.initiator(it, selected, Bmh3Policy.Layer.LINK, wifiMarker = WifiHopPlumbing.BUILD_HANDLES_BWF1)
            },
            raiseTag = true,
        )
        try {
            require(session.peerCapabilities.transportMask and capabilities.transportMask != 0) {
                "Callee did not authenticate this transport"
            }
            // Chain off: the child must be the announced callee, exactly as before. Chain on: a
            // route-cost-0 child still must be; a relayed offer's child must be a different relay.
            checkChild(session)
            // test_coc_phy_after_connect (default off): PHY only now, before media (no-op off CoC).
            downstreamControl?.requestPhyAfterConnect("call-relay")
            return session
        } catch (error: Exception) {
            session.close()
            throw error
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptCaller(
        context: Context,
        adapter: BluetoothAdapter,
        transport: BluetoothTransportOption,
        callee: DiscoveryAnnouncement,
        stopClassicAdvertisingOnAccept: Boolean,
        /** Bounded wait for the one parent (chains on); null waits as before, without a deadline. */
        parentWaitMs: Long?,
    ): TransportConnection {
        val noParent = { cause: Throwable? ->
            IOException("No caller connected within ${(parentWaitMs ?: 0L) / 1_000L} s", cause)
        }
        val announcement = PrivateCallRelayChain.parentOffer(callee, transport.discoveryFlag)
        return when (transport) {
            BluetoothTransportOption.CLASSIC_RFCOMM -> {
                val server = ClassicSockets.listen(adapter, "Bluetooth Private Call Relay", ClassicRole.CALL_RELAY)
                upstreamServerSocket = server
                upstreamAdvertiser = BleDiscoveryAdvertiser(adapter, announcement, onStatus)
                    .also(BleDiscoveryAdvertiser::start)
                onStatus("Private-call relay is advertising for its caller.")
                val socket = try {
                    if (parentWaitMs == null) server.accept() else server.accept(parentWaitMs.toInt())
                } catch (error: IOException) {
                    if (parentWaitMs != null && running.get()) throw noParent(error)
                    throw error
                }
                ClassicRfcommCap.note(socket)
                socketConnection(socket).also {
                    // CoC and GATT already leave the air once their one parent connects. A Classic
                    // relay kept advertising, so a chain's caller could pick this hop and skip the
                    // relay above it.
                    if (stopClassicAdvertisingOnAccept) {
                        upstreamAdvertiser?.close()
                        upstreamAdvertiser = null
                    }
                }
            }
            BluetoothTransportOption.BLE_L2CAP -> {
                // One long-lived listener per role (CocListeners): Android 10 never frees a closed LE CoC server.
                val server = CocListeners.listen(adapter, "call-relay")
                upstreamServerSocket = server
                upstreamControl = BleL2capControlServer(
                    context,
                    adapter,
                    server.psm,
                    announcement,
                    onStatus,
                    BlePhyPreference.TWO_M,
                ).also(BleL2capControlServer::start)
                onStatus("Private-call CoC relay is advertising for its caller.")
                val socket = try {
                    if (parentWaitMs == null) server.accept() else server.accept(parentWaitMs)
                } catch (error: IOException) {
                    if (parentWaitMs != null && running.get()) throw noParent(error)
                    throw error
                }
                socketConnection(socket) { upstreamControl?.phySummary.orEmpty() }.also { upstreamControl?.stopAdvertising() }
            }
            BluetoothTransportOption.BLE_GATT -> {
                val connections = LinkedBlockingQueue<GattVideoConnection>(1)
                val server = GattVideoServer(
                    context,
                    adapter,
                    announcement,
                    BlePhyPreference.TWO_M,
                    onStatus,
                ) { connection ->
                    if (!connections.offer(connection)) connection.close()
                }
                upstreamGattServer = server
                server.start()
                onStatus("Private-call GATT relay is advertising for its caller.")
                gattConnection(
                    if (parentWaitMs == null) {
                        connections.take()
                    } else {
                        connections.poll(parentWaitMs, TimeUnit.MILLISECONDS) ?: throw noParent(null)
                    },
                )
            }
        }
    }

    private fun socketConnection(socket: BluetoothSocket, phySummary: () -> String = { "" }) = TransportConnection(
        socket.inputStream,
        socket.mediaOutputStream(forwarding = true),
        socket::close,
        socket,
        phySummary,
    )

    private fun gattConnection(connection: GattVideoConnection) = TransportConnection(
        connection.inputStream,
        connection.outputStream,
        connection::close,
        phySummary = connection.phySummary,
    )

    override fun close() {
        cancelled = true
        if (!running.getAndSet(false)) return
        closeResources()
        worker?.interrupt()
        reverseWorker?.interrupt()
    }

    private fun closeResources() {
        awaitConnectionCloseDrain()
        upHop?.close()
        downHop?.close()
        upHop = null
        downHop = null
        runCatching { pendingCocSocket?.close() }
        pendingCocSocket = null
        closeConnections()
        downstreamSession?.close()
        downstreamSession = null
        upstreamSession?.close()
        upstreamSession = null
        downstreamControl?.close()
        downstreamControl = null
        upstreamControl?.close()
        upstreamControl = null
        upstreamAdvertiser?.close()
        upstreamAdvertiser = null
        upstreamGattServer?.close()
        upstreamGattServer = null
        runCatching { upstreamServerSocket?.close() }
        upstreamServerSocket = null
        reverseWorker?.interrupt()
        reverseWorker = null
    }

    /** A7: what a relay hop's carrier switch reports back to this relay. */
    private fun relayHopOwner(side: String): HopOwner = object : HopOwner {
        override fun sendCarrierChanged(kind: CarrierKind) {
            LinkChangeEvents.carrierChanged()
        }

        override fun localKeyframeNeeded() = Unit

        override fun peerKeyframeNeeded() = Unit

        override fun reassertState() = Unit

        override fun hopEnded(reason: String) {
            if (running.getAndSet(false)) {
                onStatus("Private-call relay path closed: $reason")
                closeConnections()
                worker?.interrupt()
                reverseWorker?.interrupt()
            }
        }

        override fun bluetoothBudgetKbps(): Int? =
            LinkCapacity.measuredLinkKbps(callRelayTransport, CarrierKind.BLUETOOTH)
    }

    private fun closeConnections() {
        awaitConnectionCloseDrain()
        runCatching { downstream?.close() }
        downstream = null
        runCatching { upstream?.close() }
        upstream = null
    }

    private fun armConnectionCloseDrain() {
        closeNotBeforeNanos.compareAndSet(
            0L,
            System.nanoTime() + CONNECTION_CLOSE_DRAIN_MILLIS * 1_000_000L,
        )
    }

    private fun awaitConnectionCloseDrain() {
        while (true) {
            val remainingNanos = closeNotBeforeNanos.get() - System.nanoTime()
            if (remainingNanos <= 0L) return
            try {
                Thread.sleep(
                    remainingNanos / 1_000_000L,
                    (remainingNanos % 1_000_000L).toInt(),
                )
            } catch (_: InterruptedException) {
                // Preserve the short drain even if the other relay worker exits first.
            }
        }
    }

    private companion object {
        const val BUFFER_BYTES = 32 * 1024
        const val CONNECTION_CLOSE_DRAIN_MILLIS = 400L
        const val SETUP_REPLY_GRACE_MS = 2_000
        const val GUARD_POLL_MS = 1_000L
    }
}

private val BluetoothTransportOption.discoveryFlag: Int
    get() = when (this) {
        BluetoothTransportOption.CLASSIC_RFCOMM -> DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT
        BluetoothTransportOption.BLE_GATT -> DiscoveryAnnouncement.FLAG_GATT_TRANSPORT
        BluetoothTransportOption.BLE_L2CAP -> 0
    }
