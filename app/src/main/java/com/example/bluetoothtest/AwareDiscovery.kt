package com.example.bluetoothtest

import android.net.wifi.ScanResult
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.SystemClock

/**
 * The Wider-reach discovery plane: one publish and one subscribe session per Wi-Fi Aware attach, the sink of
 * sightings, the epoch restart and the rekey rule. All mutable state lives on the A4 wifi thread
 * [WifiRadio.handler] (thread `meshlive-wifi`); the public functions post to it, and every platform call runs there.
 * The radio is A4's singleton object WifiRadio. [API]
 */
internal class AwareDiscovery(
    private val registry: WifiOfferRegistry,
    private val nativeLink: WifiNativeLink,
    private val sink: Sink,
    private val eligibility: () -> WifiEligibility.Decision,   // WifiDiscovery.eligibility (capacity-agnostic, step 7) [R M-7][API]
    private val maxInfoBytes: () -> Int?,                      // WifiRadio.characteristics()?.maxServiceSpecificInfoLength; null = unknown [API]
) {
    interface Sink {
        fun onSighting(sighting: WifiSighting)   // called on the radio thread; the owner posts to the UI thread
        fun onLost(key: String?)                 // key = null: every Wi-Fi leg is gone (plane stopped for good)
    }

    private val handler get() = WifiRadio.handler   // [API]
    private var pubGen = 0L                     // [AM 4] bumped by startPublish/stopPublish
    @Volatile private var subGen = 0L           // [AM 4] bumped by startSubscribe/stopSubscribe; readable by a dial
    private var planeGen = 0L                   // [AM 4] bumped by stopAll
    private var publishStarting = false         // [AM 5]
    private var subscribeStarting = false       // [AM 5]
    private var publishedKey: String? = null    // [AM 9] role:key on the current attach
    private val keyEpochs = PublishedKeyEpochs()  // a key never goes on air under a later epoch's name (A8 review P1-2)
    private var staleKeyLogged: String? = null
    private var rekeyBlockedLogged: String? = null
    private var publishCallback: PublishCallback? = null       // the open publish session's callback (re-stamped when kept)
    private var subscribeCallback: SubscribeCallback? = null   // the open subscribe session's callback (re-stamped when kept)
    private val epochs = WifiEpochTracker()     // the running epoch and F-10's one deferral (A8 review P0-1)
    private val epoch: Long get() = epochs.running   // new sessions' service name; never moves while a restart is deferred
    private var publishEpoch = -1L              // the service-name epoch of the open publish session
    private var subscribeEpoch = -1L            // the service-name epoch of the open subscribe session
    private var hold: AwareHold? = null            // the discovery attach hold (A4); not counted by Facts.awareAttachHeldByThisApp [API]
    private var awareSession: WifiAwareSession? = null   // delivered by holder.onAttached [API]
    @Volatile private var terminated = false      // AwareDownReason.SHUTDOWN: no attach until revive() [API]
    private var publish: PublishDiscoverySession? = null
    private var publishedInfo: ByteArray? = null
    private var subscribe: SubscribeDiscoverySession? = null
    private val handleKeys = HashMap<PeerHandle, String>()
    private val handlesByKey = HashMap<String, MutableSet<PeerHandle>>()   // [AM 31] per subscribe session
    private val copiedKeys = HashSet<String>()                             // [AM 31] copied for the rest of the session
    private val matchCounts = HashMap<String, Int>()                       // [AM 3] the DEBUG `match` count
    private val seenThisGeneration = HashSet<String>()
    private var decision = WifiDiscoveryGate.Decision.NONE
    private var silentUntilMs = 0L
    private var dialsInFlight = 0
    private var attachFailures = 0
    private var noEpochLogged = false
    private val undecodable = HashMap<String, Int>()

    /** The newest sighting per key of the live generation (readable from any thread; cleared by [stopAll]). */
    private val latestByKey = java.util.concurrent.ConcurrentHashMap<String, WifiSighting>()

    fun latest(key: String): WifiSighting? = latestByKey[key]

    /** The live subscribe session; read ONLY on [handler] (the dial posts its sends there). */
    fun subscribeSessionOnHandler(): SubscribeDiscoverySession? = subscribe

    /** The generation of the live subscribe session (a sighting may be dialled only while its own session is live). [AM 3] */
    fun liveSessionGeneration(): Long = subGen

    fun apply(next: WifiDiscoveryGate.Decision) { handler.post { decision = next; reconcile("gate") } }
    fun onBoundary(boundaryEpoch: Long) { handler.post { handleBoundary(boundaryEpoch) } }
    fun onOffersChanged() { handler.post { reconcile("offer") } }
    fun beginDial() { handler.post { dialsInFlight++ } }
    fun endDial() { handler.post { dialsInFlight = (dialsInFlight - 1).coerceAtLeast(0); if (dialsInFlight == 0) reconcile("dial_end") } }   // [BUG step 9.8][AM 8]
    fun onLinksChanged() { handler.post { reconcile("links") } }   // [AM 8][AM 10] WifiNativeLink calls it when liveLinks/admittedLinks change

    fun close() { handler.post { decision = WifiDiscoveryGate.Decision.NONE; stopAll("closed", tellSink = true) } }

    // Pause for Wi-Fi Direct and Share (M-10, A4 DiscoveryPauser). Called ON the wifi thread by WifiDiscovery.pauser, once per lease; owner = A4's unique lease key "<owner>#<n>". [R M-10][API]
    fun pause(owner: String) { stopAll("direct_pause", tellSink = false); nativeLink.cancelAll("paused") }   // synchronous: the discovery hold is released when this returns
    fun resume(owner: String) { reconcile("resume") }       // reconcile re-checks WifiRadio.discoveryPaused(): another active lease keeps the plane stopped
    fun isPaused(): Boolean = WifiRadio.discoveryPaused()
    fun revive() { handler.post { terminated = false; reconcile("revive") } }   // WifiDiscovery.onSwitchesChanged() when the switch is turned on again after a SHUTDOWN

    // --- the reconcile loop ---------------------------------------------------------------------------------------

    private fun reconcile(why: String) {
        if (terminated) return
        if (WifiRadio.discoveryPaused()) { stopAll("direct_pause", tellSink = false); return }   // [R M-10]
        if (SystemClock.elapsedRealtime() < silentUntilMs) return

        val d = decision
        if (d.block != null || (!d.publish && !d.subscribe && dialsInFlight == 0)) {
            stopAll(d.block?.name?.lowercase() ?: "idle", tellSink = d.block != null)
            // A session kept for a live link still carries its epoch's name: F-10 bounds it here too.
            if (sessionsOpen()) epochStep()
            return
        }
        if (epochStep()) return

        val aware = awareSession ?: run { acquire(); return }

        // 5. publish: one registry read, so the bytes and the key are the same offer (A8 review P2-2)
        if (d.publish) {
            val offer = registry.snapshot()
            val wanted = offer?.key
            if (offer == null) {
                stopPublish("no_offer")
            } else if (publishedKey != null && offer.key != publishedKey) {
                if (WifiRadio.activeCount() == 0 && WifiRadio.facts().otherAwareClientAttached != true) {
                    if (WifiRadio.facts().otherAwareClientAttached == null) log("publish", "rekey_unverified")
                    val silence = boundarySilenceMs()
                    stopAll("rekey", tellSink = false)   // a forced stop: the fresh NMI is the point [AM 9]
                    silentUntilMs = SystemClock.elapsedRealtime() + silence
                    WifiRadio.scheduler.cancel(TAG_EPOCH)
                    WifiRadio.scheduler.postDelayed(TAG_EPOCH, silence + 50) { reconcile("rekey") }
                    return
                }
                mutePublish()
                if (rekeyBlockedLogged != offer.key) {   // once per wanted key, not every reconcile
                    rekeyBlockedLogged = offer.key
                    log("publish", "rekey_blocked", "role" to registry.roleName())
                }
            } else if (wanted != null && !keyEpochs.allow(wanted, if (publish != null || publishStarting) publishEpoch else epoch)) {
                // A rotating ID from an earlier epoch (an owner that failed to rotate or clear it): publish nothing new.
                mutePublish()
                if (staleKeyLogged != wanted) {
                    staleKeyLogged = wanted
                    log("publish", "stale_key", "role" to registry.roleName())
                }
            } else if (publish == null && !publishStarting) {
                startPublish(aware, offer)
            } else if (publishedInfo != null && !offer.info.contentEquals(publishedInfo)) {
                updatePublish(offer)   // the same key: a flag change, or the offer back after a mute
            }
        } else {
            stopPublish("not_wanted")
        }

        // 6. subscribe
        if ((d.subscribe || dialsInFlight > 0) && subscribe == null && !subscribeStarting) {
            startSubscribe(aware)
        }
        if (!d.subscribe && dialsInFlight == 0) stopSubscribe("not_wanted")
        if (d.subscribe || dialsInFlight > 0) armSubscribeRefresh()

        // 7. nothing wanted any more: release the attach
        if (!d.publish && subscribe == null && publish == null) releaseHold()
    }

    /**
     * The epoch step of every reconcile: true when it restarted the plane (forced stop, silence, reconcile later). The
     * tracker never re-enters reconcile or the boundary handler, and keeps the running epoch while a restart is deferred;
     * offer removals and key changes are still applied during the deferral [AM 9] (A8 review P0-1).
     */
    private fun epochStep(): Boolean {
        val current = runCatching { RotatingDiscovery.currentEpoch() }.getOrNull() ?: run {
            if (!noEpochLogged) {
                noEpochLogged = true
                log("subscribe", "no_epoch")
            }
            RotatingDiscoveryIds.wallEpoch(DiscoveryWallClock.nowMs() / 1000L)   // [AM 7] no retry loop
        }
        val step = epochs.onReconcile(current, sessionsOpen(), nativeLink.admittedLinks(), boundarySilenceMs())
        if (step !is WifiEpochTracker.Step.Restart) return false
        restartEpoch(step)
        return true
    }

    private fun acquire() {
        if (hold == null) hold = WifiRadio.acquireAware("discovery", holder)   // never blocks; the session arrives in onAttached
    }

    private val holder = object : AwareHolder<WifiAwareSession> {
        override fun onAttached(session: WifiAwareSession) {
            attachFailures = 0
            awareSession = session
            reconcile("attached")
        }

        override fun onAttachFailed(reason: AttachFailure) {
            hold = null
            attachFailures++
            log("publish", "attach_failed", "role" to registry.roleName())
            log("subscribe", "attach_failed")
            val delay = minOf(60_000L, 2_000L shl minOf(attachFailures, 5))
            WifiRadio.scheduler.cancel(TAG_ATTACH_RETRY)
            WifiRadio.scheduler.postDelayed(TAG_ATTACH_RETRY, delay) { reconcile("attach_retry") }
        }

        override fun onAwareDown(reason: AwareDownReason) {
            hold = null
            awareSession = null
            if (reason == AwareDownReason.SHUTDOWN) {
                terminated = true
                stopAll("shutdown", tellSink = true)
            } else {
                stopAll("radio_" + reason.name.lowercase(), tellSink = false)
                WifiRadio.scheduler.cancel(TAG_RECONCILE)
                WifiRadio.scheduler.postDelayed(TAG_RECONCILE, 2_000L) { reconcile("radio") }
            }
        }
    }

    // --- publish --------------------------------------------------------------------------------------------------

    private fun publishConfig(info: ByteArray, sessionEpoch: Long): PublishConfig {
        val builder = PublishConfig.Builder()
            .setServiceName(WifiEpochPlan.serviceName(sessionEpoch))
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .setServiceSpecificInfo(info)
            .setTtlSec(0)
            .setTerminateNotificationEnabled(true)
        // Discovery plane: 2.4 GHz social channel; A4's link sessions ask for 5 GHz [ICM]
        if (icm() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) builder.setInstantCommunicationModeEnabled(true, ScanResult.WIFI_BAND_24_GHZ)
        return builder.build()
    }

    private fun startPublish(session: WifiAwareSession, offer: WifiOfferRegistry.Snapshot) {
        val info = offer.info
        val limit = runCatching { maxInfoBytes() }.getOrNull()
        if (limit != null && info.size > limit) {
            log("publish", "info_too_long", "role" to registry.roleName())
            return
        }
        val pg = ++pubGen
        val plg = planeGen
        val ep = epoch
        publishStarting = true
        publishEpoch = ep
        publishedInfo = info
        publishedKey = offer.key
        try {
            session.publish(publishConfig(info, ep), PublishCallback(pg, plg, ep), handler)
        } catch (e: RuntimeException) {
            platformError("publish")
        }
    }

    private fun updatePublish(offer: WifiOfferRegistry.Snapshot) {
        val session = publish ?: return
        val info = offer.info
        publishedInfo = info
        publishedKey = offer.key
        try {
            session.updatePublish(publishConfig(info, publishEpoch))
        } catch (e: RuntimeException) {
            platformError("publish")
            stopPublish("update_failed")
        }
    }

    /** A non-forced stop under a live native link only mutes the offer; the session and its holders stay. [AM 10] */
    private fun mutePublish() {
        val session = publish ?: return
        if (publishedInfo?.isEmpty() == true) return   // already muted: no update and no `muted` line per reconcile (A8 review P2-1)
        publishedInfo = ByteArray(0)
        try {
            session.updatePublish(publishConfig(ByteArray(0), publishEpoch))
        } catch (e: RuntimeException) {
            platformError("publish")
        }
        log("publish", "muted", "role" to registry.roleName())
    }

    private fun stopPublish(why: String) {
        val session = publish
        if (session == null) {
            pubGen++                     // a start still in flight is dropped when it arrives
            publishStarting = false
            publishedInfo = null
            return
        }
        // Kept for a live native link: only muted, and its generation stays, so knocks are served again once the offer
        // returns (A8 review P2-1). [AM 10]
        if (why !in FORCED_STOPS && nativeLink.liveLinks() > 0) { mutePublish(); return }
        pubGen++
        publishStarting = false
        publishCallback = null
        runCatching { session.close() }
        // The table's own reasons for a failed start/update and a vanished offer; every other stop is stopped_<why>.
        log("publish", if (why == "no_offer" || why == "update_failed") why else "stopped_$why", "role" to registry.roleName())
        publish = null
        publishedInfo = null
    }

    // --- subscribe ------------------------------------------------------------------------------------------------

    private fun subscribeConfig(sessionEpoch: Long): SubscribeConfig {
        val builder = SubscribeConfig.Builder()
            .setServiceName(WifiEpochPlan.serviceName(sessionEpoch))
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .setTtlSec(0)
            .setTerminateNotificationEnabled(true)
        if (icm() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) builder.setInstantCommunicationModeEnabled(true, ScanResult.WIFI_BAND_24_GHZ)
        return builder.build()
    }

    private fun startSubscribe(session: WifiAwareSession) {
        val sg = ++subGen
        val ep = epoch
        subscribeStarting = true
        subscribeEpoch = ep
        try {
            session.subscribe(subscribeConfig(ep), SubscribeCallback(sg, planeGen, ep), handler)
        } catch (e: RuntimeException) {
            platformError("subscribe")
        }
    }

    private fun stopSubscribe(why: String, logIt: Boolean = true) {
        val session = subscribe
        if (session == null) {
            subGen++                     // a start still in flight is dropped when it arrives
            subscribeStarting = false
            return
        }
        // The session a live native link was built on stays, with its generation, so its sightings stay dialable and a
        // wanted subscribe simply goes on with it (A8 review P2-1). [AM 10]
        if (why !in FORCED_STOPS && nativeLink.liveLinks() > 0) return
        subGen++
        subscribeStarting = false
        subscribeCallback = null
        runCatching { session.close() }
        if (logIt) log("subscribe", "stopped_$why")
        forgetSession(subGen - 1)
        subscribe = null
    }

    /** Drops what belonged to one subscribe session (the closed one). */
    private fun forgetSession(gen: Long) {
        latestByKey.entries.removeAll { it.value.sessionGeneration == gen }
        handleKeys.clear()
        handlesByKey.clear()
        copiedKeys.clear()
        matchCounts.clear()
        seenThisGeneration.clear()
    }

    private fun armSubscribeRefresh() {
        WifiRadio.scheduler.cancel(TAG_SUB_REFRESH)
        WifiRadio.scheduler.postDelayed(TAG_SUB_REFRESH, WifiLimits.WIDER_SUBSCRIBE_REFRESH_MS) { refreshSubscribe() }
    }

    private fun refreshSubscribe() {
        if (terminated || decision.block != null) return
        val wanted = decision.subscribe || dialsInFlight > 0
        if (!wanted) return
        if (dialsInFlight > 0 || nativeLink.liveLinks() > 0) { armSubscribeRefresh(); return }
        val aware = awareSession ?: return
        log("subscribe", "refresh")
        val closed = subGen
        stopSubscribe("refresh", logIt = false)
        forgetSession(closed)   // again, after stopSubscribe bumped the generation
        startSubscribe(aware)
        armSubscribeRefresh()
    }

    // --- boundaries and stops -------------------------------------------------------------------------------------

    private fun handleBoundary(boundaryEpoch: Long) {
        if (terminated) return
        when (val step = epochs.onBoundary(boundaryEpoch, sessionsOpen(), nativeLink.admittedLinks(), boundarySilenceMs())) {
            WifiEpochTracker.Step.Reconcile -> reconcile("boundary")
            is WifiEpochTracker.Step.Restart -> restartEpoch(step)
            else -> Unit   // Hold: the restart waits for the last admitted link or the next boundary (F-10)
        }
    }

    private fun restartEpoch(step: WifiEpochTracker.Step.Restart) {
        stopAll("epoch", tellSink = false)   // forced [AM 10]; ends the deferral
        silentUntilMs = SystemClock.elapsedRealtime() + step.silenceMs
        WifiRadio.scheduler.cancel(TAG_EPOCH)
        WifiRadio.scheduler.postDelayed(TAG_EPOCH, step.silenceMs + 50) { reconcile("epoch") }
    }

    /** A session is open or starting: its service name is [epoch]'s. */
    private fun sessionsOpen(): Boolean = publish != null || subscribe != null || publishStarting || subscribeStarting

    private fun boundarySilenceMs(): Long = runCatching { RotatingDiscovery.boundarySilenceMs() }.getOrDefault(2_000L)

    private fun stopAll(why: String, tellSink: Boolean) {
        planeGen++                       // every callback of the closed sessions is ignored [AM 4]
        publishStarting = false
        subscribeStarting = false
        WifiRadio.scheduler.cancel(TAG_SUB_REFRESH)
        val forced = why in FORCED_STOPS
        val live = nativeLink.liveLinks()

        // A session kept for a live native link (a non-forced stop) is re-stamped with the new plane generation: it
        // stays this plane's session, so its knocks and sightings still count (A8 review P2-1). [AM 10]
        if (publish != null) {
            if (!forced && live > 0) {
                mutePublish()
                publishCallback?.plg = planeGen
            } else {
                closePublish(why)
            }
        }
        if (subscribe != null) {
            if (forced || live == 0) closeSubscribe(why) else subscribeCallback?.plg = planeGen
        }
        if (subscribe == null || tellSink) {
            latestByKey.clear()
            handleKeys.clear()
            handlesByKey.clear()
            copiedKeys.clear()
            matchCounts.clear()
            seenThisGeneration.clear()
        }
        if (forced || live == 0) releaseHold()
        if (publish == null && subscribe == null) epochs.onSessionsClosed()   // nothing of the old epoch is on air any more
        if (tellSink) sink.onLost(null)
        if (forced && live > 0) schedulePathCheck()
    }

    private fun closePublish(why: String) {
        val session = publish ?: return
        runCatching { session.close() }
        log("publish", "stopped_$why", "role" to registry.roleName())
        publish = null
        publishCallback = null
        publishedInfo = null
    }

    private fun closeSubscribe(why: String) {
        val session = subscribe ?: return
        runCatching { session.close() }
        log("subscribe", "stopped_$why")
        subscribe = null
        subscribeCallback = null
    }

    /** R-10 measurement: 3 s after a forced stop closed the sessions, every live native link reports whether it survived. [AM 10] */
    private fun schedulePathCheck() {
        val before = nativeLink.openLinkGens()
        if (before.isEmpty()) return
        WifiRadio.scheduler.cancel(TAG_PATH_CHECK)
        WifiRadio.scheduler.postDelayed(TAG_PATH_CHECK, 3_000L) {
            val after = nativeLink.openLinkGens()
            for (gen in before) {
                log("native_path", if (gen in after) "survived_session_close" else "died_with_session_close")
            }
        }
    }

    private fun releaseHold() {
        hold?.let { WifiRadio.releaseAware(it) }
        hold = null
        awareSession = null
        if (WifiRadio.activeCount() == 0) publishedKey = null   // [AM 9] a release with no other holder ends the attach
    }

    // --- platform callbacks ---------------------------------------------------------------------------------------

    private inner class PublishCallback(private val pg: Long, var plg: Long, private val ep: Long) : DiscoverySessionCallback() {
        private fun stale() = pg != pubGen || plg != planeGen

        override fun onPublishStarted(session: PublishDiscoverySession) {
            if (stale()) {
                runCatching { session.close() }
                return
            }
            publish = session
            publishCallback = this
            publishStarting = false
            log("publish", "started", "role" to registry.roleName())
        }

        override fun onSessionConfigUpdated() {
            if (stale()) return
            publishStarting = false
            log("publish", "updated", "role" to registry.roleName())
        }

        override fun onSessionConfigFailed() {
            if (stale()) return
            publishStarting = false
            log("publish", "platform_error", "role" to registry.roleName())
            stopPublish("update_failed")
            scheduleReconcile()
        }

        override fun onSessionTerminated() {
            if (stale()) return
            publish = null
            publishCallback = null
            publishedInfo = null
            publishStarting = false
            scheduleReconcile()
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            if (stale()) return
            nativeLink.onKnock(publish ?: return, peerHandle, message, ep, pg)   // the knocker saw THIS session's name
        }

        override fun onMessageSendSucceeded(messageId: Int) {
            if (stale()) return
            nativeLink.onSendResult(messageId, true)
        }

        override fun onMessageSendFailed(messageId: Int) {
            if (stale()) return
            nativeLink.onSendResult(messageId, false)
        }
    }

    private inner class SubscribeCallback(private val sg: Long, var plg: Long, private val ep: Long) : DiscoverySessionCallback() {
        private fun stale() = sg != subGen || plg != planeGen

        override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
            if (stale()) {
                runCatching { session.close() }
                return
            }
            subscribe = session
            subscribeCallback = this
            subscribeStarting = false
            log("subscribe", "started")
        }

        override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: List<ByteArray>?) {
            if (stale()) return
            val bytes = serviceSpecificInfo?.let { runCatching { WifiWire.decodeMwd1(it).announcement }.getOrNull() }
                ?: return count("undecodable")
            val announcement = runCatching {
                DiscoveryAnnouncementCodec.decode(
                    bytes,
                    acceptRotating = RotatingDiscoveryFeature.enabled,
                    acceptWindow = NamingFeatures.visibleByNameOn(),
                )
            }.getOrNull() ?: return count("undecodable")
            if (announcement.routeCost != 0) return            // issue 10: Wi-Fi never carries relayed offers
            val key = WifiSighting.keyOf(announcement)
            if (key in copiedKeys) return                       // [AM 31] ignored until the next refresh
            val n = (matchCounts[key] ?: 0) + 1
            matchCounts[key] = n
            if (BuildConfig.DEBUG) log("match", null, "node" to key, "n" to n)   // [AM 3][AM 36]
            handleKeys[peerHandle] = key
            val handles = handlesByKey.getOrPut(key) { HashSet() }
            handles += peerHandle
            if (handles.size > 1) {                             // [AM 31] the same key from a second handle: copied
                copiedKeys += key
                if (latestByKey.remove(key) != null) sink.onLost(key)
                if (BuildConfig.DEBUG) log("lost", "copied_id", "node" to key) else log("lost", "copied_id")
                return
            }
            val first = seenThisGeneration.add(key)
            // written BEFORE the sink, so a dial started from the row finds it
            val sighting = WifiSighting(key, announcement, peerHandle, sg, ep, SystemClock.elapsedRealtime())
            latestByKey[key] = sighting
            sink.onSighting(sighting)
            if (first) {
                if (BuildConfig.DEBUG) log("seen", null, "node" to key, "kind" to kindOf(announcement))
                else log("seen", null, "kind" to kindOf(announcement))
            }
        }

        override fun onServiceLost(peerHandle: PeerHandle, reason: Int) {
            if (stale()) return
            val key = handleKeys.remove(peerHandle) ?: return
            handlesByKey[key]?.remove(peerHandle)
            latestByKey.remove(key)
            sink.onLost(key)
            val why = if (reason == 1) "peer_not_visible" else "service_lost"
            if (BuildConfig.DEBUG) log("lost", why, "node" to key) else log("lost", why)
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            if (stale()) return
            nativeLink.onAnswer(peerHandle, message, sg)
        }

        override fun onMessageSendSucceeded(messageId: Int) {
            if (stale()) return
            nativeLink.onSendResult(messageId, true)
        }

        override fun onMessageSendFailed(messageId: Int) {
            if (stale()) return
            nativeLink.onSendResult(messageId, false)
        }

        override fun onSessionTerminated() {
            if (stale()) return
            for (key in handleKeys.values) {   // [AM 30]
                latestByKey.remove(key)
                sink.onLost(key)
            }
            handleKeys.clear()
            handlesByKey.clear()
            copiedKeys.clear()
            matchCounts.clear()
            seenThisGeneration.clear()
            subscribe = null
            subscribeCallback = null
            subscribeStarting = false
            scheduleReconcile()
        }
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    private fun platformError(event: String) {
        if (event == "publish") {
            publishStarting = false
            log(event, "platform_error", "role" to registry.roleName())
        } else {
            subscribeStarting = false
            log(event, "platform_error")
        }
        scheduleReconcile()
    }

    private fun scheduleReconcile() {
        WifiRadio.scheduler.cancel(TAG_RECONCILE)
        WifiRadio.scheduler.postDelayed(TAG_RECONCILE, 1_000L) { reconcile("retry") }
    }

    private fun icm(): Boolean = eligibility().awareIcm

    private fun kindOf(announcement: DiscoveryAnnouncement): String = when {
        announcement.isStreamOffer() -> "stream"
        announcement.isPrivateCallOffer() -> "call"
        else -> "viewer"
    }

    private fun count(reason: String) {
        undecodable[reason] = (undecodable[reason] ?: 0) + 1
    }

    private fun log(event: String, reason: String? = null, vararg extra: Pair<String, Any?>) {
        val fields = ArrayList<Pair<String, Any?>>(3 + extra.size)
        fields += "epoch" to epoch
        if (reason != null) fields += "reason" to reason
        fields += extra
        WifiLog.emit(WifiLog.discovery(event, *fields.toTypedArray()))
    }

    private companion object {
        const val TAG_RECONCILE = "a8-reconcile"
        const val TAG_ATTACH_RETRY = "a8-attach-retry"
        const val TAG_EPOCH = "a8-epoch"
        const val TAG_SUB_REFRESH = "a8-sub-refresh"
        const val TAG_PATH_CHECK = "a8-path-check"

        /** The stops that close the sessions whatever native links exist. [AM 10] */
        val FORCED_STOPS = setOf(
            "switch_off", "direct_pause", "shutdown", "closed", "epoch", "rekey",
            "radio_unavailable", "radio_wifi_off", "radio_session_terminated",
        )
    }
}
