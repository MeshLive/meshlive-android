package com.example.bluetoothtest

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * The Wider-reach process owner: switch + eligibility + gate + epoch listener + registry + sink in one place.
 * Nothing is created and no WifiRadio call is made while `wifi_wider_reach` is off (Private level, X4):
 * [start] only stores the context and the hub listener, and [recompute] returns before its first radio call.
 * [API]
 */
internal object WifiDiscovery {
    enum class Demand { CALLS_LIST, LIVE_LIST, VIEWER_REQUESTS }

    val offers = WifiOfferRegistry()
    private val demands = ConcurrentHashMap.newKeySet<Demand>()
    @Volatile private var started = false
    @Volatile var listener: Listener? = null           // MainActivity sets it in onStart and clears it in onStop

    interface Listener { fun onWifiSighting(s: WifiSighting); fun onWifiLost(key: String?) }

    private var appContext: Context? = null
    @Volatile private var discovery: AwareDiscovery? = null
    @Volatile private var nativeLink: WifiNativeLink? = null
    @Volatile private var activityStarted = false
    /** A user's tap on a Wi-Fi-only entry and every native dial in flight, counted per caller (A8 review P3-2). */
    private val dialIntents = DialIntents(
        nowMs = { SystemClock.elapsedRealtime() },
        tapWindowMs = WifiLimits.DIAL_WAIT_FOR_SIGHTING_MS + WifiLimits.ATTEMPT_MAX_MS,
    )
    private var hubListenerInstalled = false
    private var offerListenerInstalled = false
    @Volatile private var periodicRunning = false   // read on the UI and scheduler threads (A8 review P3-3)
    @Volatile private var planeRunning = false
    private val boundaryListener: (Long) -> Unit = { epoch -> discovery?.onBoundary(epoch) }

    /** Called on the main thread from MainActivity.onCreate, after refreshFeatureCatalog() and WifiSwitchHub.publish(). [API] */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        if (!hubListenerInstalled) {
            hubListenerInstalled = true
            WifiSwitchHub.addListener { _, _ -> onSwitchesChanged() }
        }
        if (widerReachOn()) startPlane()
    }

    /** Sets the flag and, only while the plane is started, asks for a debounced [recompute]. [AM 24] */
    fun setDemand(demand: Demand, on: Boolean) {
        if (on) demands.add(demand) else demands.remove(demand)
        if (planeRunning) requestRecompute()
    }

    /** Sets the flag and, only while the plane is started, asks for a debounced [recompute]. [AM 24] */
    fun setActivityStarted(startedNow: Boolean) {
        activityStarted = startedNow
        if (planeRunning) requestRecompute()
    }

    /**
     * The one gate input computation. Returns before any WifiRadio call while the switch is off or the plane is not
     * started: with the Private level nothing of A8 runs (X4, [AM 24]).
     */
    fun recompute() {
        if (!widerReachOn()) return
        val plane = discovery ?: return
        if (!started) return
        val input = WifiDiscoveryGate.Inputs(
            widerReach = true,
            awareAllowed = eligibility().aware,
            sdkInt = Build.VERSION.SDK_INT,
            activityStarted = activityStarted,
            connectedDeviceServiceHeld = ActiveVideoSessionService.holdsConnectedDevice(),
            locationServiceHeld = ServiceTypePolicy.hasLocation(ActiveVideoSessionService.heldTypes),
            locationServiceAvailable = BuildConfig.LOCATION_FGS_AVAILABLE,
            wantPublish = offers.current() != null,
            wantSubscribe = demands.isNotEmpty() || dialIntents.active(),
        )
        plane.apply(WifiDiscoveryGate.decide(input))
    }

    /** A stop-then-start of the same demand in one UI pass changes nothing: the posted run reads the current flags. [AM 24] */
    private fun requestRecompute() {
        WifiRadio.scheduler.cancel(TAG_RECOMPUTE)
        WifiRadio.scheduler.postDelayed(TAG_RECOMPUTE, 300L) { recompute() }
    }

    fun latest(key: String): WifiSighting? = discovery?.latest(key)

    fun nativeLink(): WifiNativeLink? = nativeLink

    fun widerReachOn(): Boolean = WifiSwitchHub.current().widerReach   // the single read of the switch for steps 11, 12 and 13

    /** Every offer source calls this; with the switch off nothing is stored and the role is cleared. [R self-contradiction 6][AM 9] */
    fun publishOffer(role: WifiOfferRole, announcement: DiscoveryAnnouncement, acceptor: ((WifiNativeConnection) -> Unit)? = null) {
        if (!widerReachOn()) {
            offers.clear(role)
        } else if (!offers.publish(role, announcement, acceptor)) {
            WifiLog.emit(WifiLog.discovery("publish", "reason" to "not_rotating"))
        }
    }

    /** Capacity-agnostic eligibility: A0 rule 6g must not stop the discovery a native link is set up on (C-A8-2). [R M-7][R M-8] */
    fun eligibility(): WifiEligibility.Decision {
        val f = WifiRadio.facts()
        return WifiEligibility.decide(f.copy(freeNdp = maxOf(f.freeNdp, 1)))
    }

    fun freeDataPaths(): Int = WifiRadio.freeNdp()

    /**
     * A tap on a Wi-Fi-only entry (UI thread): keeps `wantSubscribe` true until that tap's dial starts, or for the tap
     * window if it never does. The immediate recompute and the expiry recompute use separate tags, so the expiry post
     * no longer cancels the immediate one (A8 review P3-1). [BUG step 9.8]
     */
    fun noteDialIntent() {
        dialIntents.noteTap()
        if (!planeRunning) return
        requestRecompute()
        WifiRadio.scheduler.cancel(TAG_DIAL_INTENT)
        WifiRadio.scheduler.postDelayed(TAG_DIAL_INTENT, WifiLimits.DIAL_WAIT_FOR_SIGHTING_MS + WifiLimits.ATTEMPT_MAX_MS) { recompute() }
    }

    /** A native dial starts (its own intent until [endDialIntent]); it takes over the oldest pending tap. */
    fun beginDialIntent() {
        dialIntents.beginDial()
        if (planeRunning) requestRecompute()
    }

    /** That dial ended: another dial in flight or another tap still keeps the subscribe session (A8 review P3-2). */
    fun endDialIntent() {
        dialIntents.endDial()
        if (planeRunning) requestRecompute()
    }

    /** A4's interface; called on the wifi thread once per lease, owner = "<owner>#<n>". [R M-10] */
    val pauser: DiscoveryPauser = object : DiscoveryPauser {
        override fun pauseDiscovery(owner: String) { discovery?.pause(owner) }
        override fun resumeDiscovery(owner: String) { discovery?.resume(owner) }
    }

    /** Hub listener (UI thread) and step 15.1. An open native connection is NOT closed (D6). */
    @Synchronized
    fun onSwitchesChanged() {
        if (widerReachOn()) {
            startPlane()
            discovery?.revive()
            recompute()
        } else {
            stopPlane()
        }
    }

    @Synchronized
    private fun startPlane() {
        if (!widerReachOn()) return
        val link = nativeLink ?: WifiNativeLink(offers, ::eligibility, ::freeDataPaths).also { nativeLink = it }
        val plane = discovery ?: AwareDiscovery(
            registry = offers,
            nativeLink = link,
            sink = object : AwareDiscovery.Sink {
                override fun onSighting(sighting: WifiSighting) { listener?.onWifiSighting(sighting) }
                override fun onLost(key: String?) { listener?.onWifiLost(key) }
            },
            eligibility = ::eligibility,
            maxInfoBytes = { WifiRadio.characteristics()?.maxServiceSpecificInfoLength },
        ).also { discovery = it }
        link.discovery = plane
        if (!planeRunning) {
            planeRunning = true
            if (!offerListenerInstalled) {
                offerListenerInstalled = true
                offers.addListener { plane.onOffersChanged(); recompute() }
            }
            WifiRadio.registerDiscoveryPauser(pauser)
            RotatingDiscovery.addBoundaryListener(boundaryListener)
            schedulePeriodicRecompute()
        }
        recompute()
    }

    private fun stopPlane() {
        if (!planeRunning) {
            offers.clear(WifiOfferRole.CALL_OFFER)
            offers.clear(WifiOfferRole.STREAM_OFFER)
            offers.clear(WifiOfferRole.VIEWER_REQUEST)
            return
        }
        planeRunning = false
        discovery?.apply(WifiDiscoveryGate.Decision(false, false, WifiDiscoveryGate.Block.SWITCH_OFF))   // [AM 21] drops every row, logs stopped_switch_off
        WifiRadio.scheduler.cancel(TAG_PERIODIC)
        periodicRunning = false
        offers.clear(WifiOfferRole.CALL_OFFER)
        offers.clear(WifiOfferRole.STREAM_OFFER)
        offers.clear(WifiOfferRole.VIEWER_REQUEST)
        RotatingDiscovery.removeBoundaryListener(boundaryListener)
    }

    private fun schedulePeriodicRecompute() {
        periodicRunning = true
        WifiRadio.scheduler.cancel(TAG_PERIODIC)
        WifiRadio.scheduler.postDelayed(TAG_PERIODIC, 30_000L) {
            if (!periodicRunning) return@postDelayed
            recompute()
            schedulePeriodicRecompute()
        }
    }

    /** Test/debug: the app context stored by [start]. */
    internal fun context(): Context? = appContext

    private const val TAG_RECOMPUTE = "a8-recompute"
    private const val TAG_DIAL_INTENT = "a8-dial-intent"
    private const val TAG_PERIODIC = "a8-recompute-periodic"
}

/**
 * Who still wants the Wider-reach subscribe session for a dial: each tap on a Wi-Fi-only entry until its dial starts
 * (or [tapWindowMs] passes), and each native dial until it ends. Counted per caller, so one dial's end never clears
 * another dial or a pending tap (A8 review P3-2). Pure and thread-safe.
 */
internal class DialIntents(private val nowMs: () -> Long, private val tapWindowMs: Long) {
    private var dials = 0
    private val taps = ArrayDeque<Long>()   // the expiry of each tap whose dial has not started yet, oldest first

    @Synchronized
    fun noteTap() {
        prune()
        taps.addLast(nowMs() + tapWindowMs)
    }

    @Synchronized
    fun beginDial() {
        prune()
        dials++
        taps.removeFirstOrNull()
    }

    @Synchronized
    fun endDial() {
        if (dials > 0) dials--
    }

    @Synchronized
    fun active(): Boolean {
        prune()
        return dials > 0 || taps.isNotEmpty()
    }

    private fun prune() {
        val now = nowMs()
        while (taps.isNotEmpty() && taps.first() <= now) taps.removeFirst()
    }
}
