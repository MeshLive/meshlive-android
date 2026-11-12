package com.example.bluetoothtest

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** The `hop=` value of every `wifi_upgrade` and `carrier:` line. A7 overrides it with STREAM / RELAY. */
internal enum class WifiHopKind(val log: String) { CALL("call"), STREAM("stream"), RELAY("relay") }

/** One carrier sample for the quality monitor, from A3's `CarrierSnapshot` / `CarrierStats`. */
internal class CarrierSample(
    val isSendCarrier: Boolean,
    val bytesWritten: Long,
    val queuedContent: Int,
    val oldestQueuedAgeMs: Long,
)

/**
 * What the hop agent needs from its owner: a call (A5.8) or a stream/relay hop (A7). Every method must be
 * non-blocking. All of it runs on the wifi thread except where noted.
 */
internal interface WifiHopPorts {
    /** True = the initiator side of the link; also the [WifiRoles.choose] tie-break. */
    val isLinkInitiator: Boolean
    val hopKind: WifiHopKind get() = WifiHopKind.CALL

    /** Carrier id <-> ticket registry, shared with the marker codec (A5.9). */
    val tickets: CarrierTickets

    fun optedIn(): Boolean
    fun callActive(): Boolean
    fun revealsDone(): Boolean
    fun renewalRunning(): Boolean
    fun hasWifiCarrier(): Boolean
    fun fallbackCount(): Int

    /** Non-blocking; the BWF1 plaintext ([Bwf.encode]) on carrier 0. */
    fun sendRecord(record: Bwf): Boolean

    /** A fresh copy of the hop's `UW` (`wifiKeyFor(...)?.copyOf()`); the caller wipes it. Null = no key. */
    fun uwCopy(): ByteArray?

    /** A3 carrier id (1..62) or -1; registers `carrier.ticket` in [tickets] BEFORE `wifiCarrierPending(id)`, then `wifiCarrierUp(id)`. */
    fun offerCarrier(carrier: WifiSocketCarrier): Int

    /** The primary link's session copied with the new reader/writer and `hopWifiKey = null`. */
    fun carrierLink(reader: SecureRecordReader, writer: SecureRecordWriter): SecureBluetoothSession

    fun carrierDied(id: Int, reason: String)
    fun localReady(): Bwf.Ready

    /** Called ONLY with `carrierId != 0`: closes that Wi-Fi carrier (A3 `wifiProtocolViolation`). */
    fun protocolViolation(carrierId: Int, reason: String)

    /** `CarrierSet.switchOff()`: the only way the agent reaches the cutover machine. */
    fun carrierSwitchOff()

    /** Null = no such live carrier. */
    fun carrierSample(id: Int): CarrierSample?

    /** The standby's learned Bluetooth budget; null = none learned. */
    fun bluetoothBudgetKbps(): Int?

    /** AP9: the hop's requested media rate in kbps; null = no requested media (or a relay hop). */
    fun requestedMediaKbps(): Int? = null

    /**
     * AP10: true while this phone carries Bluetooth media on a link other than this hop: a relay child, a stream
     * viewer or forwarder child, the parent it watches or forwards from, another call, a call it relays. The hop's
     * own Bluetooth standby link never counts. Read from existing state only, never a radio query; non-blocking, on
     * the wifi thread. The default, and the answer of any owner that cannot tell, is true: the 5 GHz rule.
     */
    fun otherBluetoothMedia(): Boolean = true

    /** `CarrierSet.qualityFallback()`. */
    fun qualityFallback()
}

/** Which key a link uses: a link whose peer is a relay uses the hop key, any other link the end-to-end key. Returns the retained array; callers copy it. [X8] */
internal fun wifiKeyFor(linkPeerIsRelay: Boolean, endpointKey: ByteArray?, hopKey: ByteArray?): ByteArray? =
    if (linkPeerIsRelay) hopKey else endpointKey

/** A link is a relay edge unless both peers are known and equal. Both ends of an edge get the same answer. [Am 12][R-13] */
internal fun linkPeerIsRelay(linkPeer: NodeId?, endpointPeer: NodeId?): Boolean =
    linkPeer == null || endpointPeer == null || linkPeer != endpointPeer

/** AP9: delivered kbps for a byte delta over a window in ms (bytes x 8 / ms = kbps); a zero window counts as 1 ms. */
internal fun kbpsFor(bytes: Long, windowMs: Long): Long = bytes * 8 / maxOf(1L, windowMs)

/**
 * One agent per hop: the CAPS exchange, the role plan, the attempt schedule, record routing, lifecycle and the quality
 * monitor. Everything runs on the wifi thread (public methods post or run inline when already there). An agent created
 * while every Wi-Fi switch was off is inert: it runs inline, never posts, arms no timer and starts no attempt. [Am 5]
 */
internal class WifiHopAgent(
    private val ports: WifiHopPorts,
    private val env: WifiEnv,
    private val planner: WifiHopPlanner = WifiHopPlanner(env.scheduler::nowMs),
    private val inert: Boolean = !env.facts().switches.carrier,
) {
    companion object {
        val nextGeneration = AtomicLong(0)
        val nextAgentId = AtomicLong(0)
    }

    private val hopOwner = "hop-" + nextAgentId.incrementAndGet()

    private var capsSent = false
    private var peerCaps: Bwf.Caps? = null
    private var localCaps: Bwf.Caps? = null
    private var tuple: RoleTuple? = null
    private var closed = false
    private var switchedOff = false
    private var current: WifiAttempt? = null
    private var localVideo = false
    private var remoteVideo = false
    private var videoSinceMs = -1L

    @Volatile
    private var handed: HandedCarrier? = null

    // Read-only for Link health ("Wi-Fi not used: ..."): what the other phone told this hop and why the hop stopped trying.
    // Written on the wifi thread, read from any thread; never sent and never logged.
    @Volatile private var peerBearers: Int? = null
    @Volatile private var peerDeclineWire: Int? = null
    @Volatile private var stoppedBecause: String? = null

    /** What this hop knows about the other phone and about its own retries, for [WifiNotUsed]. Any thread. */
    fun peerView(): WifiPeerView = WifiPeerView(peerBearers, peerDeclineWire, stoppedBecause)

    private var lowSeconds = 0
    private var slowSeconds = 0
    private var lastBytes = 0L
    private var lastSampleMs = 0L
    private val goodputGuard = WifiGoodputGuard()

    /** AP10: whether the goodput guard acts (the Experimental arm) or only logs (default); read once per handed carrier. */
    private var goodputGuardActs: Boolean? = null

    /** AP10: `band_24_with_bt_media` was logged for the Bluetooth media that is beside this 2.4 GHz hop now. */
    private var band24BtMediaLogged = false

    /** AP10 (audit WIFI-19): this hop holds the ledger's 2.4 GHz Aware mark. */
    private var band24Marked = false
    private var lastStaBandLog: String? = null
    private var capsPollStartedMs = 0L
    private val servedTickets = ArrayList<ByteArray>()
    private var spoofCapLogged = false
    private var forcedCarrierLogged = false
    private var screenOffLogged = false
    private var callEndedLogged = false
    /** NDP owners (`wifi-<gen>`) of this hop's own attempts whose release the listener has not heard yet. [P2-2] */
    private val ownNdpOwners = HashSet<String>()
    private var internalErrorHandled = false
    private val timers = HashMap<String, Any>()

    private val level: String = if (!inert && env.facts().switches.widerReach) "wider" else "private"

    private val releaseListener: (String) -> Unit = { owner ->
        if (!inert) {
            env.scheduler.post {
                synchronized(this) {
                    contained {
                        if (closed) return@contained
                        // This hop's own release (an attempt that just ended, or its handed carrier closing) never
                        // resets the busy back-off its outcome has recorded; only another owner's release frees a
                        // path for us. Identified by owner, so a stranger's or another hop's release cannot stand in
                        // for ours. [Am 7][A5 review P2-2]
                        if (ownNdpOwners.remove(owner)) return@contained
                        if (current == null) {
                            planner.onDataPathReleased()
                            scheduleHostCheck()
                        }
                    }
                }
            }
        }
    }

    init {
        if (!inert) {
            env.ledger.addNdpReleaseListener(releaseListener)
            armCapsPoll()
        }
    }

    // ----- public entry points -----

    fun onCallActive() = run { tryExchangeCaps() }

    fun onRevealProgress() = run { tryExchangeCaps() }

    fun onVideo(local: Boolean? = null, remote: Boolean? = null) = run {
        val now = env.scheduler.nowMs()
        val wasOn = localVideo || remoteVideo
        if (local != null) localVideo = local
        if (remote != null) remoteVideo = remote
        val isOn = localVideo || remoteVideo
        if (isOn && !wasOn) videoSinceMs = now
        if (!isOn) {
            videoSinceMs = -1L
            cancelTimer("video")
        }
        scheduleHostCheck()
    }

    fun onRecord(carrierId: Int, payload: ByteArray) = run { handleRecord(carrierId, payload) }

    fun onCapsExchanged(local: Bwf.Caps, peer: Bwf.Caps) = run {
        if (closed || capsSent) return@run
        localCaps = local
        peerCaps = peer
        peerBearers = peer.bearers
        capsSent = true
        cancelTimer("capsPoll")
        logCaps("sent", local, level)
        logCaps("recv", peer, level)
        maybeLogForcedBluetooth()
        planRoles()
    }

    fun onRenewalStarting() = run { current?.command(Cmd.RENEWAL) }

    fun onCarrierLost() = run {
        if (closed) return@run
        handed?.let { carrier ->
            carrier.ticket.fill(0)
            handed = null
        }
        clearBand24Mark()
        cancelTimer("monitor")
        planner.onCarrierLost()
        if (planner.canStart(ports.fallbackCount()) !is WifiHopPlanner.Verdict.Never) {
            env.ledger.setAuthenticatedWant(hopOwner, true)
        }
        scheduleHostCheck()
    }

    fun onSwitchOff() = run {
        if (closed || switchedOff) return@run
        switchedOff = true
        planner.neverBecause("switch_off")
        cancelAllTimers()
        current?.command(Cmd.SWITCH_OFF)
        ports.carrierSwitchOff()
        env.ledger.setAuthenticatedWant(hopOwner, false)
        log("switch_off")
    }

    // Posted like every other entry point: the call ends on its own thread, and an attempt may be driven only from the
    // wifi thread (X7; found by the strict test scheduler, A5 review P3-10).
    fun onCallEnded() = run { endHop(wipe = false) }

    fun onWipe() = run { endHop(wipe = true) }

    fun logStatus() = run {
        val verdict = planner.canStart(ports.fallbackCount())
        val next = (verdict as? WifiHopPlanner.Verdict.Wait)?.untilMs ?: 0L
        val never = (verdict as? WifiHopPlanner.Verdict.Never)?.why.orEmpty()
        log(
            "status",
            "capsSent" to capsSent,
            "peerCaps" to (peerCaps != null),
            "tuple" to tuple?.let { "${it.bearer.name.lowercase()}/${if (it.localIsHost) "host" else "guest"}" },
            "attempt" to (current?.generation ?: 0L),
            "failed" to planner.failedAttempts,
            "noPenalty" to planner.noPenaltyEnds,
            "spoofed" to planner.spoofedAttempts,
            "next" to next,
            "never" to never,
            "handed" to (handed?.id ?: -1),
            "slowSeconds" to slowSeconds,
            "lowSeconds" to lowSeconds,
        )
    }

    // ----- CAPS -----

    private fun armCapsPoll() {
        if (inert || closed || capsSent) return
        if (capsPollStartedMs == 0L) capsPollStartedMs = env.scheduler.nowMs()
        if (env.scheduler.nowMs() - capsPollStartedMs > WifiAttemptLimits.CAPS_POLL_BUDGET_MS) return
        cancelTimer("capsPoll")
        postTimer("capsPoll", WifiAttemptLimits.CAPS_POLL_MS) {
            tryExchangeCaps()
            armCapsPoll()
        }
    }

    private fun tryExchangeCaps() {
        if (closed || capsSent) return
        if (ports.hopKind != WifiHopKind.CALL) return
        if (!ports.optedIn()) {
            log("caps", "skipped" to "not_opted_in")
            cancelTimer("capsPoll")
            return
        }
        if (!ports.callActive() || !ports.revealsDone()) return
        if (ports.isLinkInitiator && peerCaps == null) return
        val caps = buildLocalCaps()
        localCaps = caps
        // Set before the write: a port that delivers synchronously (a test pair) must never see a second CAPS.
        capsSent = true
        if (!ports.sendRecord(caps)) {
            capsSent = false
            return
        }
        cancelTimer("capsPoll")
        logCaps("sent", caps, level)
        maybeLogForcedBluetooth()
        planRoles()
    }

    private fun buildLocalCaps(): Bwf.Caps {
        val key = ports.uwCopy()
        val keyAvailable = key != null
        key?.fill(0)
        return WifiLocalCaps.build(env, keyAvailable) { ports.otherBluetoothMedia() }
    }

    private fun logCaps(dir: String, caps: Bwf.Caps, level: String) {
        env.log(
            WifiLog.caps(
                "dir" to dir,
                "bearers" to "0x%02x".format(caps.bearers),
                "freeHost" to caps.freeHost,
                "freeGuest" to caps.freeGuest,
            ),
        )
        log("caps", "dir" to dir, "bearers" to "0x%02x".format(caps.bearers), "tuple" to tuple?.bearer?.name?.lowercase())
    }

    private fun maybeLogForcedBluetooth() {
        if (forcedCarrierLogged) return
        if (DebugTunables.wifiForceCarrier != "bluetooth") return
        forcedCarrierLogged = true
        env.log(WifiLog.carrier(ports.hopKind.log, "bluetooth", "bluetooth", "forced"))
    }

    private fun planRoles() {
        val local = localCaps ?: return
        val peer = peerCaps ?: return
        var chosen = WifiRoles.choose(local, peer, ports.isLinkInitiator)
        if (chosen != null && chosen.bearer == WifiBearer.DIRECT && !chosen.localIsHost && !DirectPolicy.mayJoin(peer)) chosen = null
        tuple = chosen
        log("caps", "tuple" to chosen?.bearer?.name?.lowercase(), "host" to chosen?.localIsHost)
        if (chosen == null || !env.supportsBearer(chosen.bearer)) {
            planner.neverBecause("no_tuple")
            stoppedBecause = "no_tuple"
            val requested = DebugTunables.wifiForceCarrier
            if (requested == "aware" || requested == "direct") {
                env.log(WifiLog.carrier(ports.hopKind.log, requested, "bluetooth", "no_tuple"))
            }
            return
        }
        env.ledger.setAuthenticatedWant(hopOwner, true)
        scheduleHostCheck()
    }

    // ----- attempt scheduling (host) -----

    private fun scheduleHostCheck() {
        if (inert || closed || switchedOff) return
        val now = env.scheduler.nowMs()
        val delay = if (localVideo || remoteVideo) {
            if (videoSinceMs < 0L) WifiAttemptLimits.VIDEO_STABLE_MS else maxOf(0L, videoSinceMs + WifiAttemptLimits.VIDEO_STABLE_MS - now)
        } else {
            WifiAttemptLimits.RECHECK_MS
        }
        cancelTimer("recheck")
        postTimer("recheck", delay) { hostCheck() }
    }

    private fun hostCheck() {
        if (closed || switchedOff) return
        // PROTOCOL "When an upgrade is attempted": only while the hop is ACTIVE. The call ends on its own thread and
        // posts onCallEnded, so a recheck already queued can run after the phase is ENDED and before (or after) the
        // key wipe; it must neither judge eligibility nor take a key copy for a call that is over.
        if (!ports.callActive()) {
            logCallEnded()
            return
        }
        val chosen = tuple ?: return
        if (!chosen.localIsHost) return
        if (current != null) return
        if (ports.hasWifiCarrier()) return
        val force = DebugTunables.wifiForceCarrier
        val requested = if (chosen.bearer == WifiBearer.AWARE) "aware" else "direct"
        if (force.isNotEmpty() && force != requested) return
        if (!localVideo && !remoteVideo) return
        val now = env.scheduler.nowMs()
        if (videoSinceMs < 0L || now - videoSinceMs < WifiAttemptLimits.VIDEO_STABLE_MS) {
            scheduleHostCheck()
            return
        }
        if (ports.renewalRunning()) {
            postTimer("recheck", WifiAttemptLimits.RECHECK_MS) { hostCheck() }
            return
        }
        if (!env.screenOn()) {
            if (!screenOffLogged) {
                screenOffLogged = true
                log("host_wait", "reason" to "screen_off")
            }
            postTimer("recheck", WifiAttemptLimits.RECHECK_MS) { hostCheck() }
            return
        }
        when (val verdict = planner.canStart(ports.fallbackCount())) {
            is WifiHopPlanner.Verdict.Never -> {
                logHostSkip(verdict.why)
                stoppedBecause = verdict.why
                env.ledger.setAuthenticatedWant(hopOwner, false)
                maybeLogSpoofCap(verdict.why)
                return
            }
            is WifiHopPlanner.Verdict.Wait -> {
                postTimer("recheck", maxOf(0L, verdict.untilMs - env.scheduler.nowMs())) { hostCheck() }
                return
            }
            WifiHopPlanner.Verdict.Go -> Unit
        }
        val facts = hopFacts()
        val decision = env.decide(facts)
        if (chosen.bearer == WifiBearer.AWARE) logStaBand(facts, decision)
        val allowed = if (chosen.bearer == WifiBearer.AWARE) decision.aware else decision.directGo
        if (!allowed) {
            logHostSkip(decision.reason?.name ?: "bearer_not_eligible")
            postTimer("recheck", WifiAttemptLimits.RECHECK_MS) { hostCheck() }
            return
        }
        val uw = ports.uwCopy()
        if (uw == null) {
            logHostSkip("no_uw")
            env.ledger.setAuthenticatedWant(hopOwner, false)
            return
        }
        val gen = nextGeneration.incrementAndGet()
        if (chosen.bearer == WifiBearer.AWARE) {
            val capacity = WifiFactsReader.reserveCapacity(env.ledger, facts.freeNdp)
            if (!env.ledger.reserveNdp("wifi-$gen", capacity)) {
                uw.fill(0)
                logHostSkip("no_ndp")
                postTimer("recheck", WifiAttemptLimits.RECHECK_MS) { hostCheck() }
                return
            }
            ownNdpOwners += "wifi-$gen"
        }
        val ticket = drawTicket()
        servedTickets += ticket.copyOf()
        val attempt = WifiAttempt(
            role = AttemptRole.HOST,
            generation = gen,
            ticket = ticket,
            uw = uw,
            decision = decision,
            env = env,
            ports = ports,
            icm = decision.awareIcm && (peerCaps?.awareInstantMode == true),
            ndpOwner = if (chosen.bearer == WifiBearer.AWARE) "wifi-$gen" else null,
            bearer = chosen.bearer,
            onEnded = ::onAttemptEnded,
            onInternalError = { errorClass -> attemptInternalError(gen, errorClass) },
        )
        current = attempt
        attempt.start()
    }

    private fun drawTicket(): ByteArray {
        while (true) {
            val ticket = ByteArray(WifiWire.TICKET_BYTES)
            env.random().nextBytes(ticket)
            if (ticket.all { it == 0.toByte() }) continue
            if (servedTickets.any { MessageDigest.isEqual(it, ticket) }) continue
            return ticket
        }
    }

    // ----- serving a HOST (guest) -----

    private fun onHost(h: Bwf.Host) {
        if (inert) {
            decline(h.ticket, 1)
            return
        }
        // A HOST that arrives after the call ended (phase no longer ACTIVE, onCallEnded still queued) is declined
        // as "not eligible now" before any eligibility check or key copy. [PROTOCOL "When an upgrade is attempted"]
        if (!ports.callActive()) {
            logCallEnded()
            if (h.status == 1) decline(h.ticket, 1)
            return
        }
        // A call without a UW (a BMH2 end-to-end call) advertises nothing and declines every HOST with 1. [Am 5]
        val keyProbe = ports.uwCopy()
        if (keyProbe == null) {
            decline(h.ticket, 1)
            return
        }
        keyProbe.fill(0)
        val chosen = tuple
        if (chosen?.localIsHost != false) {
            decline(h.ticket, 2)
            return
        }
        if (h.status != 1) {
            log("host_none")
            planner.onAttemptEnded(Outcome(OutcomeKind.NO_PENALTY, "host_none"))
            return
        }
        if (h.bearer != chosen.bearer.wire || !env.supportsBearer(chosen.bearer)) {
            decline(h.ticket, 1)
            return
        }
        if (h.validMs !in 1..WifiLimits.HOST_VALID_MS) {
            decline(h.ticket, 1)
            return
        }
        val ticket = h.ticket
        val currentTicket = current?.ticket
        val reused = ticket.all { it == 0.toByte() } ||
            servedTickets.any { known ->
                MessageDigest.isEqual(known, ticket) &&
                    (currentTicket == null || !MessageDigest.isEqual(currentTicket, ticket))
            }
        if (reused) {
            log("host_ticket_reused")
            decline(h.ticket, 1)
            return
        }
        current?.let { attempt ->
            if (MessageDigest.isEqual(attempt.ticket, ticket)) return
            decline(h.ticket, 2)
            return
        }
        if (closed || switchedOff) {
            decline(h.ticket, 1)
            return
        }
        if (ports.renewalRunning()) {
            decline(h.ticket, 2)
            return
        }
        if (ports.hasWifiCarrier()) {
            decline(h.ticket, 2)
            return
        }
        when (val verdict = planner.guestMayServe()) {
            is WifiHopPlanner.GuestVerdict.Decline -> {
                maybeLogSpoofCap(verdict.why)
                decline(h.ticket, verdict.reason)
                return
            }
            WifiHopPlanner.GuestVerdict.Serve -> Unit
        }
        if (!env.screenOn()) {
            decline(h.ticket, 1)
            return
        }
        val facts = hopFacts()
        val decision = env.decide(facts)
        if (chosen.bearer == WifiBearer.AWARE) logStaBand(facts, decision)
        val allowed = if (chosen.bearer == WifiBearer.AWARE) decision.aware else decision.directClient
        if (!allowed) {
            decline(h.ticket, decision.reason?.wire ?: 1)
            return
        }
        val uw = ports.uwCopy()
        if (uw == null) {
            decline(h.ticket, 1)
            return
        }
        val gen = nextGeneration.incrementAndGet()
        if (chosen.bearer == WifiBearer.AWARE) {
            val capacity = WifiFactsReader.reserveCapacity(env.ledger, facts.freeNdp)
            if (!env.ledger.reserveNdp("wifi-$gen", capacity)) {
                uw.fill(0)
                decline(h.ticket, 2)
                return
            }
            ownNdpOwners += "wifi-$gen"
        }
        planner.onGuestHostAccepted()
        servedTickets += ticket.copyOf()
        val attempt = WifiAttempt(
            role = AttemptRole.GUEST,
            generation = gen,
            ticket = ticket,
            uw = uw,
            decision = decision,
            env = env,
            ports = ports,
            icm = decision.awareIcm && (peerCaps?.awareInstantMode == true),
            ndpOwner = if (chosen.bearer == WifiBearer.AWARE) "wifi-$gen" else null,
            bearer = chosen.bearer,
            hostFreqMhz = h.freqMhz,
            onEnded = ::onAttemptEnded,
            onInternalError = { errorClass -> attemptInternalError(gen, errorClass) },
        )
        current = attempt
        attempt.start()
    }

    private fun decline(ticket: ByteArray, code: Int) {
        ports.sendRecord(Bwf.Decline(ticket, code))
        log("decline", "dir" to "sent", "reason" to code)
    }

    // ----- attempt end, carrier events, monitor -----

    private fun onAttemptEnded(outcome: Outcome, setupMs: Long?, handed: HandedCarrier?) {
        current = null
        planner.onAttemptEnded(outcome)
        if (outcome.kind == OutcomeKind.SUCCESS && handed != null) {
            this.handed = handed
            lastSampleMs = env.scheduler.nowMs()
            lastBytes = ports.carrierSample(handed.id)?.bytesWritten ?: 0L
            slowSeconds = 0
            lowSeconds = 0
            goodputGuard.reset()
            goodputGuardActs = null
            band24BtMediaLogged = false
            if (handed.band24Alone) {
                // AP10 (audit WIFI-19): while this 2.4 GHz-only path is live, Bluetooth capacity on this phone is
                // learned and read under the 2.4 GHz coexistence key, never the clean-radio key.
                band24Marked = true
                env.ledger.setBand24Path(hopOwner, true)
            }
            env.ledger.setAuthenticatedWant(hopOwner, false)
            armMonitor()
        } else {
            scheduleHostCheck()
        }
    }

    private fun armMonitor() {
        if (closed || switchedOff) return
        cancelTimer("monitor")
        postTimer("monitor", WifiAttemptLimits.GOODPUT_POLL_MS) { monitorTick() }
    }

    private fun monitorTick() {
        if (closed || switchedOff) return
        val carrier = handed ?: return
        val sample = ports.carrierSample(carrier.id)
        if (sample == null) {
            onCarrierLost()
            return
        }
        if (carrier.band24Alone) {
            // AP10: Bluetooth media that appears beside a live 2.4 GHz hop never moves the hop mid-call. It is logged
            // once per appearance; the next Aware attempt reads the fact again, so the 5 GHz rule applies to it.
            val otherBt = ports.otherBluetoothMedia()
            if (otherBt && !band24BtMediaLogged) log("band_24_with_bt_media", "chMhz" to carrier.channelMhz)
            band24BtMediaLogged = otherBt
        }
        val now = env.scheduler.nowMs()
        val elapsed = now - lastSampleMs
        if (elapsed < WifiAttemptLimits.GOODPUT_SAMPLE_MIN_MS) {
            armMonitor()
            return
        }
        if (elapsed > WifiAttemptLimits.GOODPUT_SAMPLE_MAX_MS) {
            slowSeconds = 0
            lowSeconds = 0
            goodputGuard.reset()
            lastSampleMs = now
            lastBytes = sample.bytesWritten
            log("goodput", "gap" to true)
            armMonitor()
            return
        }
        val slow = sample.isSendCarrier && sample.oldestQueuedAgeMs > WifiAttemptLimits.QUEUE_SLOW_AGE_MS
        slowSeconds = if (slow) slowSeconds + 1 else 0
        // AP9: the log reports the delivered rate for every carrier, not only the unverified-band arm.
        val kbps = kbpsFor(sample.bytesWritten - lastBytes, elapsed)
        var low = false
        var budget: Int? = null
        if (!carrier.bandVerified) {
            budget = ports.bluetoothBudgetKbps()
            if (budget == null) {
                lowSeconds = 0
            } else {
                low = sample.isSendCarrier && sample.queuedContent > 0 &&
                    kbps < WifiAttemptLimits.GOODPUT_BUDGET_FACTOR.toLong() * budget
                lowSeconds = if (low) lowSeconds + 1 else 0
            }
        }
        lastSampleMs = now
        lastBytes = sample.bytesWritten
        log(
            "goodput",
            "queueAgeMs" to sample.oldestQueuedAgeMs,
            "slow" to slow,
            "slowSeconds" to slowSeconds,
            "kbps" to kbps,
            "btBudgetKbps" to budget,
            "low" to low,
            "seconds" to lowSeconds,
        )
        val reason = when {
            slowSeconds >= WifiAttemptLimits.QUEUE_SLOW_SECONDS -> "queue_slow"
            lowSeconds >= WifiAttemptLimits.GOODPUT_LOW_SECONDS -> "goodput_low"
            else -> null
        }
        if (reason != null) {
            log("quality_fallback", "reason" to reason)
            env.log(
                WifiLog.carrier(
                    ports.hopKind.log,
                    if (tuple?.bearer == WifiBearer.DIRECT) "direct" else "aware",
                    "bluetooth",
                    reason,
                ),
            )
            ports.qualityFallback()
            slowSeconds = 0
            lowSeconds = 0
            goodputGuard.reset()
        } else if (carrier.bearer == WifiBearer.AWARE) {
            // AP9: an Aware hop that carries is judged by the requested media rate over 5 s windows. AP10: by default
            // three low ones are only logged; under the Experimental arm they return the hop to Bluetooth through the
            // existing quality fallback and skip the next attempt in this call.
            val requested = ports.requestedMediaKbps() ?: 0
            val mediaOffered = sample.isSendCarrier && sample.queuedContent > 0
            val windowsBefore = goodputGuard.completedWindows
            val verdict = goodputGuard.onSample(now, sample.bytesWritten, mediaOffered, requested)
            if (goodputGuard.completedWindows > windowsBefore) {
                // One line per completed 5 s window, so a phone run shows how close a healthy hop came to the guard.
                log(
                    "goodput_window",
                    "kbps" to goodputGuard.lastWindowKbps,
                    "requestedKbps" to requested,
                    "offered" to goodputGuard.lastWindowOffered,
                    "verdict" to verdict.name.lowercase(),
                    "lowWindows" to goodputGuard.lowWindowCount,
                )
            }
            if (verdict == WifiGoodputGuard.Verdict.FALL_BACK) {
                val why = "aware_goodput_low"
                val acts = goodputGuardActs ?: env.facts().awareGoodputGuardActs.also { goodputGuardActs = it }
                if (!acts) {
                    // AP10 (audit WIFI-01): the guard compares what was sent with the configured rate, so a healthy
                    // link under a ramping or undershooting encoder reads low. Until it is redesigned it only
                    // measures by default: the hop stays on Wi-Fi and the queue_slow rule above stays the protection.
                    log("quality_fallback_suppressed", "reason" to why)
                } else {
                    // Experimental arm `test_aware_goodput_guard`: the hop returns to Bluetooth.
                    log("quality_fallback", "reason" to why)
                    val fallbacksBefore = ports.fallbackCount()
                    ports.qualityFallback()
                    // Audit WIFI-03: with a stale or dead standby the cutover machine does nothing. The carrier line
                    // and the skipped next attempt follow only a fallback that happened.
                    val after = ports.carrierSample(carrier.id)
                    val happened = ports.fallbackCount() > fallbacksBefore || after == null ||
                        (sample.isSendCarrier && !after.isSendCarrier)
                    if (happened) {
                        env.log(WifiLog.carrier(ports.hopKind.log, "aware", "bluetooth", why))
                        planner.neverBecause(why)
                    } else {
                        log("quality_fallback_noop", "reason" to why)
                    }
                    goodputGuard.reset()
                    slowSeconds = 0
                    lowSeconds = 0
                }
            }
        }
        armMonitor()
    }

    // ----- record routing -----

    private fun handleRecord(carrierId: Int, payload: ByteArray) {
        if (closed) return
        val record = try {
            WifiWire.decodeBwf1(payload)
        } catch (error: WifiWireException) {
            if (error.error == WifiWireError.KIND) {
                log("kind_ignored", "carrier" to carrierId)
            } else {
                violation(carrierId, "record_format")
            }
            return
        }
        if (record is Bwf.Close) {
            log("close_ignored", "carrier" to carrierId)
            return
        }
        if (carrierId != 0) {
            violation(carrierId, "record_on_carrier")
            return
        }
        if (record is Bwf.Caps) {
            if (peerCaps != null) {
                violation(0, "caps_repeat")
                return
            }
            peerCaps = record
            peerBearers = record.bearers
            logCaps("recv", record, level)
            maybeLogForcedBluetooth()
            // Plan once per CAPS: a sending tryExchangeCaps plans itself; never plan on CAPS we have not sent. [P3-8]
            if (capsSent) planRoles() else tryExchangeCaps()
            return
        }
        if (!capsSent || peerCaps == null) {
            violation(0, "record_before_caps")
            return
        }
        when (record) {
            is Bwf.Host -> onHost(record)
            is Bwf.Decline -> {
                peerDeclineWire = record.reason
                current?.onDecline(ticketEquals(record.ticket), record.reason) ?: log("decline_stale")
            }
            is Bwf.Pick -> current?.onPick(ticketEquals(record.ticket), record.challenge) ?: log("pick_stale")
            is Bwf.Armed -> current?.onArmed(ticketEquals(record.ticket))
            is Bwf.Creds -> {
                if (env.supportsBearer(WifiBearer.DIRECT)) {
                    current?.onCreds(ticketEquals(record.ticket), record.port, record.ipv4)
                        ?: log("creds", "dir" to "recv", "stale" to true)
                } else {
                    violation(0, "unexpected_kind_${record.kind}")
                }
            }
            else -> violation(0, "unexpected_kind_${record.kind}")
        }
    }

    private fun ticketEquals(ticket: ByteArray): Boolean {
        val attempt = current ?: return false
        return MessageDigest.isEqual(attempt.ticket, ticket)
    }

    private fun violation(carrierId: Int, reason: String) {
        if (carrierId != 0) {
            ports.protocolViolation(carrierId, reason)
            return
        }
        endWifiForHop(reason)
    }

    private fun endWifiForHop(reason: String, logViolation: Boolean = true) {
        if (closed) return
        if (logViolation) log("violation", "reason" to reason, "carrier" to 0)
        switchedOff = true
        planner.neverBecause("violation")
        cancelAllTimers()
        current?.command(Cmd.VIOLATION)
        ports.carrierSwitchOff()
        env.ledger.setAuthenticatedWant(hopOwner, false)
    }

    // ----- lifecycle helpers -----

    private fun endHop(wipe: Boolean) {
        if (closed) return
        closed = true
        cancelAllTimers()
        current?.command(if (wipe) Cmd.WIPE else Cmd.HANGUP)
        current = null
        handed?.let { it.ticket.fill(0) }
        handed = null
        clearBand24Mark()
        peerCaps = null
        ownNdpOwners.clear()
        env.ledger.setAuthenticatedWant(hopOwner, false)
        if (!inert) env.ledger.removeNdpReleaseListener(releaseListener)
        for (ticket in servedTickets) ticket.fill(0)
        servedTickets.clear()
    }

    private fun cancelAllTimers() {
        for (name in timers.keys.toList()) cancelTimer(name)
    }

    private fun maybeLogSpoofCap(why: String) {
        if (why != "spoof_cap" || spoofCapLogged) return
        spoofCapLogged = true
        log("spoof_cap")
    }

    private fun logHostSkip(reason: String) {
        log("host_skip", "reason" to reason)
    }

    /** One `host_skip | reason=call_ended` per hop, however many queued rechecks or HOSTs meet the ended call. */
    private fun logCallEnded() {
        if (callEndedLogged) return
        callEndedLogged = true
        logHostSkip("call_ended")
    }

    /**
     * AP10: the phone-wide facts plus this hop's own fact, for the attempt decision (wifi thread). With the
     * 2.4 GHz-alone switch off the owner is not asked and the facts are exactly AP9's.
     */
    private fun hopFacts(): WifiFacts {
        val facts = env.facts()
        return if (facts.aware24GhzAlone) facts.copy(otherBluetoothMedia = ports.otherBluetoothMedia()) else facts
    }

    /**
     * AP10: the facts behind an Aware attempt decision beside a 2.4 GHz client connection, so a phone run shows which
     * rule applied. One line per change of the facts, not one per recheck.
     */
    private fun logStaBand(facts: WifiFacts, decision: WifiEligibility.Decision) {
        val sta = facts.staFrequencyMhz ?: return
        if (sta >= WifiLimits.MIN_5GHZ_MHZ) return
        val band = when {
            decision.reason == WifiEligibility.DeclineReason.NOT_5GHZ -> "refused_not_5ghz"
            facts.debugAllow24Ghz -> "allowed_debug"
            facts.aware24GhzAlone && !facts.otherBluetoothMedia -> "2g4_alone"
            else -> "not_reached" // an earlier rule declined
        }
        val otherBt: Any = if (facts.aware24GhzAlone) facts.otherBluetoothMedia else "unread"
        val key = "$sta|${facts.aware24GhzAlone}|$otherBt|$band"
        if (key == lastStaBandLog) return
        lastStaBandLog = key
        log("sta_band", "staMhz" to sta, "alone24" to facts.aware24GhzAlone, "otherBtMedia" to otherBt, "band" to band)
    }

    private fun clearBand24Mark() {
        if (!band24Marked) return
        band24Marked = false
        env.ledger.setBand24Path(hopOwner, false)
    }

    private fun run(block: () -> Unit) {
        if (inert) {
            synchronized(this) { contained(block) }
            return
        }
        if (env.scheduler.isWifiThread()) {
            synchronized(this) { contained(block) }
        } else {
            env.scheduler.post { synchronized(this) { contained(block) } }
        }
    }

    /**
     * No exception of the agent may reach the wifi thread's Looper (that ends the process, the Bluetooth call too):
     * it is logged by class name only and ends Wi-Fi for the hop; Bluetooth carrier 0 stays. [P1-1]
     */
    private inline fun contained(block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            internalError(error.javaClass.simpleName.ifEmpty { "Exception" })
        }
    }

    /** The attempt [gen] has already released itself and logged its error; Wi-Fi ends for the hop. */
    private fun attemptInternalError(gen: Long, errorClass: String) = synchronized(this) {
        if (current?.generation == gen) current = null
        endWifiAfterError()
    }

    private fun internalError(errorClass: String) {
        runCatching { log("internal_error", "error" to errorClass) }
        endWifiAfterError()
    }

    private fun endWifiAfterError() {
        if (internalErrorHandled) return
        internalErrorHandled = true
        runCatching { endWifiForHop("internal_error", logViolation = false) }
    }

    private fun log(event: String, vararg fields: Pair<String, Any?>) {
        env.log(
            WifiLog.upgrade(
                event,
                "hop" to ports.hopKind.log,
                "level" to level,
                "carrier" to (tuple?.bearer?.let { if (it == WifiBearer.AWARE) "aware" else "direct" } ?: "aware"),
                "role" to (tuple?.let { if (it.localIsHost) "host" else "guest" } ?: "host"),
                *fields,
            ),
        )
    }

    private fun postTimer(name: String, delayMs: Long, body: () -> Unit) {
        val tag = timers.getOrPut(name) { Any() }
        env.scheduler.postDelayed(tag, delayMs) {
            synchronized(this) {
                contained { if (!closed) body() }
            }
        }
    }

    private fun cancelTimer(name: String) {
        timers.remove(name)?.let { env.scheduler.cancel(it) }
    }
}
