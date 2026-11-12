package com.example.bluetoothtest

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.Surface
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.viewfinder.core.ScaleType
import androidx.camera.viewfinder.view.ViewfinderView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private enum class SessionRole(val label: String) {
    SEND("Send camera"),
    RECEIVE("Receive video"),
    RELAY("Relay video"),
}

private enum class AppScreen {
    HOME,
    CAMERA,
    RECEIVER_BROWSER,
    PLAYBACK,
    CALL_BROWSER,
    CALL,
    SETTINGS,
}

internal fun classicTargetMatches(
    deviceName: String?,
    deviceAddress: String,
    requestedTarget: String,
): Boolean = deviceName?.trim().equals(requestedTarget.trim(), ignoreCase = true) ||
    deviceAddress.equals(requestedTarget, ignoreCase = true)

internal fun debugTransportOverride(requested: String?): BluetoothTransportOption? = when (requested) {
    "classic" -> BluetoothTransportOption.CLASSIC_RFCOMM
    "ble" -> BluetoothTransportOption.BLE_L2CAP
    "gatt" -> BluetoothTransportOption.BLE_GATT
    else -> null
}

internal fun shouldRevealPrivateCallRemotePreview(
    isCallScreen: Boolean,
    remoteVideoStarted: Boolean,
): Boolean = isCallScreen && remoteVideoStarted

internal const val PRIVATE_CALL_PEER_STALE_MS = 12_000L

/**
 * Suffix for every visible-continuity line: when the first frame reached this surface, as the
 * startup time and its age at this report. A report that survives device log loss then still proves
 * and dates the first surface; nothing is printed before a frame reached the surface.
 */
internal fun continuityFirstSurfaceSuffix(snapshot: RenderedFrameContinuitySnapshot): String {
    val startupMs = snapshot.startupMs ?: return ""
    val agoMs = snapshot.firstFrameAgoMs ?: return ""
    if (snapshot.renderedFrames <= 0L) return ""
    return " | first surface %.1f ms after start, %.1f ms ago".format(Locale.US, startupMs, agoMs)
}

/**
 * DEBUG: true when this activity was created only to deliver the runner's `shutdown` control (no
 * saved state), i.e. the app had already exited. Such a start must not bring the app up.
 */
internal fun isColdStartShutdown(debug: Boolean, restoring: Boolean, control: String?): Boolean =
    debug && !restoring && control == "shutdown"

internal fun privateCallPeerIsFresh(lastSeenMs: Long, nowMs: Long): Boolean =
    nowMs - lastSeenMs <= PRIVATE_CALL_PEER_STALE_MS

internal fun privateCallPeerWasRefreshed(observedAtMs: Long, selectedAtMs: Long): Boolean =
    observedAtMs >= selectedAtMs

internal fun streamRouteSuppressionDeadline(existingDeadlineMs: Long?, requestedDeadlineMs: Long): Long =
    maxOf(existingDeadlineMs ?: 0L, requestedDeadlineMs)

private val UNEXPECTED_LOSS_RETRY_DELAYS_MS = longArrayOf(0L, 1_000L, 2_000L, 4_000L, 8_000L)

/**
 * Backoff before retrying the same authenticated route after an active stream was lost.
 * A direct viewer's only route is its source, so a fixed 8 s cooldown made recovery
 * exceed the 5 s target by construction; repeated failures still back off to 8 s.
 */
internal fun unexpectedLossRetryDelayMs(consecutiveFailures: Int): Long =
    UNEXPECTED_LOSS_RETRY_DELAYS_MS[(consecutiveFailures - 1).coerceIn(0, UNEXPECTED_LOSS_RETRY_DELAYS_MS.lastIndex)]

internal fun recoveryParentAfterRouteFailure(existing: ByteArray?, wasActive: Boolean, failedParent: ByteArray): ByteArray? =
    existing ?: failedParent.copyOf().takeIf { wasActive }

internal fun streamCapacityGenerationAdvanced(previous: Int, current: Int): Boolean =
    ((current - previous) and DiscoveryAnnouncement.SEQUENCE_GENERATION_MASK) in 1..63

internal fun streamCapacityWithdrawalNotice(routeCost: Int?): String = when (routeCost) {
    0 -> "No free connection slot on this source. Finding another route..."
    null -> "The parent stopped forwarding. Finding another route..."
    else -> "The relay connection became unstable. Finding another route..."
}

internal data class StreamReconnectTarget(
    val sourcePrefix: String,
    val streamToken: Int,
    val transport: BluetoothTransportOption,
) {
    fun matches(announcement: DiscoveryAnnouncement): Boolean =
        sourcePrefix == announcement.nodeIdPrefix.toHexKey() &&
            streamToken == announcement.streamToken &&
            transport == announcement.transportOption()

    companion object {
        fun from(peer: BleL2capPeer): StreamReconnectTarget = StreamReconnectTarget(
            peer.announcement.nodeIdPrefix.toHexKey(),
            peer.announcement.streamToken,
            peer.announcement.transportOption(),
        )
    }
}

private fun ByteArray.toHexKey(): String = joinToString("") { "%02x".format(it) }

class MainActivity : AppCompatActivity() {
    private lateinit var cameraScreen: View
    private lateinit var receiverBrowserScreen: View
    private lateinit var callBrowserScreen: View
    private lateinit var callScreen: View
    private lateinit var preview: TextureView
    private lateinit var cameraViewfinder: ViewfinderView
    private lateinit var callRemotePreview: TextureView
    private lateinit var callLocalViewfinder: ViewfinderView
    private lateinit var callBrowserStatus: TextView
    private lateinit var callPeerList: ListView
    private lateinit var callStatusText: TextView
    private lateinit var callProfileText: TextView
    private lateinit var incomingCallControls: View
    private lateinit var activeCallControls: View
    private lateinit var cancelCallButton: View
    private lateinit var callMuteButton: Button
    private lateinit var callCameraButton: Button
    private lateinit var callSwitchCameraButton: Button
    private lateinit var callSpeakerButton: Button
    private lateinit var callRemoteCameraOff: View
    private lateinit var callScreenController: CallScreenController
    private lateinit var settingsScreen: View
    private lateinit var homeConfigurationSummary: TextView
    private lateinit var privateCallProfileSummary: TextView
    private lateinit var cameraSwitchButton: View
    private lateinit var doneSettingsButton: View
    private lateinit var transportSpinner: Spinner
    private lateinit var destinationGroup: View
    private lateinit var destinationLabel: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var codecSpinner: Spinner
    /** A DEBUG `test_codec` launch picked the encoder (logged as user_choice, never Automatic). */
    private var debugCodecRequested = false
    private lateinit var rateControlSpinner: Spinner
    private lateinit var advancedSettingsSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var advancedSettingsDescriptionText: TextView
    private lateinit var rateControlLabel: TextView
    private lateinit var rateControlDescriptionText: TextView
    private lateinit var videoScaleModeSpinner: Spinner
    private lateinit var videoScaleModeDescriptionText: TextView
    private lateinit var receiverPlaybackDelaySlider: Slider
    private lateinit var playbackDelayValueText: TextView
    private lateinit var playbackStabilityText: TextView
    private lateinit var refreshButton: Button
    private lateinit var capabilityText: TextView
    private lateinit var decoderSupportText: TextView
    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var bitrateText: TextView
    private lateinit var bitrateSeekBar: SeekBar
    private lateinit var streamSummaryText: TextView
    private lateinit var streamToggleButton: LabeledMaterialButton
    private var toggleIdlePadding: IntArray? = null
    private var broadcastTimerRunning = false
    private lateinit var broadcastPauseButton: LabeledRoundButton
    private lateinit var broadcastMuteButton: LabeledRoundButton
    private lateinit var broadcastCameraButton: LabeledRoundButton
    private lateinit var liveMediaStateCover: View
    private lateinit var liveMediaStateChip: TextView

    /** Muted / camera off while live: the broadcaster's own state; viewers get it as a record when `live_media_state` is on. */
    private val liveMedia = LiveMediaState()
    private var viewerMediaNotice = ViewerMediaNotice.NONE
    private var liveCameraBusy = false
    private lateinit var statusText: TextView
    private lateinit var connectionStatusDot: View
    private lateinit var deviceHealthOverlay: View
    private lateinit var thermalStatusBadge: TextView
    private lateinit var batteryTemperatureBadge: TextView
    private lateinit var thermalReductionSwitch: MaterialSwitch
    private lateinit var deviceHealthOverlaySwitch: MaterialSwitch
    private lateinit var forwardReceivedStreamsSwitch: MaterialSwitch
    private lateinit var experimentalTemporalLayersSwitch: MaterialSwitch
    private lateinit var relayChildMediaAgeSwitch: MaterialSwitch
    private lateinit var relayChildMediaAgeDescriptionText: TextView
    private lateinit var experimentalTemporalLayersDescriptionText: TextView
    private lateinit var rotatingIdsVisibleButton: com.google.android.material.button.MaterialButton
    private lateinit var rotatingIdsResetKeyButton: com.google.android.material.button.MaterialButton
    private lateinit var rotatingIdsLegacyNoteText: TextView
    private lateinit var neutralBluetoothNameSwitch: MaterialSwitch
    private lateinit var neutralBluetoothNameDescriptionText: TextView
    private lateinit var neutralBluetoothNameStatusText: TextView
    private var neutralNameSwitchBinding = false
    /** "Hide phone name" as the last applyNeutralName() resolved it for the radio (false until the user answered). */
    private var neutralNameOptionOn = false
    /** Android refused the last neutral-name write. */
    private var neutralNameRefused = false
    /** What the Settings switch shows (off with a reason while the name cannot be hidden). */
    private var neutralNameShown = NeutralNameChoice.Shown.OFF
    /** The one-time question after an update was shown in this process. */
    private var neutralNameQuestionShown = false
    private var gapNameProbeStarted = false
    private lateinit var experimentalRelayedCallsSwitch: MaterialSwitch
    private lateinit var experimentalRelayedCallsDescriptionText: TextView
    private lateinit var relayedCallsHelpSwitch: MaterialSwitch
    private lateinit var relayedCallsConsentSwitch: MaterialSwitch
    private lateinit var relayedCallsStatusText: TextView

    /** Experimental relayed calls (RelayedCallsFeature, default off): exists only while the switch is on. */
    private var relayedCalls: RelayedCallsController? = null
    private var relayStopReceiverRegistered = false
    private val relayStopReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (!RelayedCallsNotification.isAuthentic(intent.getStringExtra(RelayedCallsNotification.EXTRA_TOKEN))) return
            runOnUiThread { privateCall?.preemptRelay() }
        }
    }
    /** Settings > Features for connectivity (FeatureSettingsBlock over the FeatureCatalog's connectivity group). */
    private var connectivityFeatureBlock: FeatureSettingsBlock? = null

    private var wifiFeatureBlock: FeatureSettingsBlock? = null

    private val wifiSwitchListener = WifiSwitchHub.Listener { old, new ->
        if (WifiSwitchHub.carrierTurnedOff(old, new)) {
            privateCall?.wifiSwitchOff()   // A3's entry point (A5 replaced its body); the call shows the D6 notice from the cutover's outcome
            HopLinks.switchOffAll()
        }
    }

    /**
     * A9's Direct-only switch-off (R-3): releases a Direct carrier of the call and of stream/relay hops. Skips when the
     * carrier switch-off already handled it, so the notice is not shown twice. [AM 5]
     */
    private val directSwitchListener = WifiSwitchHub.Listener { old, new ->
        if (old.direct && !new.direct && new.carrier && !WifiSwitchHub.carrierTurnedOff(old, new)) {
            privateCall?.let { call ->
                if (call.carrierSnapshot().kinds.containsValue(CarrierKind.WIFI_DIRECT)) call.wifiSwitchOff()
            }
            HopLinks.switchOffDirect()
        }
    }
    /** Settings > Features for stream viewers (ViewerFeatures). */
    private var viewerFeatureBlock: FeatureSettingsBlock? = null
    /** Settings > Features for updates (UpdateFeatures) and the home update note (UpdatesHome). */
    private var updateFeatureBlock: FeatureSettingsBlock? = null
    private var updatesHome: UpdatesHome? = null
    /** Experimental viewer frame age (default off; ViewerFrameAgeController.kt). Built when the Watch screen first opens. */
    private var viewerFrameAge: ViewerFrameAgeController? = null
    /** Bond-free Classic asks Android for visibility at most once per process (system dialog). */
    private var classicVisibilityRequested = false
    private lateinit var videoBackButton: View
    private lateinit var receiverWaitingOverlay: View
    private lateinit var receiverWaitingProgress: View
    private lateinit var receiverWaitingText: TextView
    private lateinit var receiverBrowserStatus: TextView
    private lateinit var streamList: ListView
    // Redesigned UI (2026-10-10): views and state that only drive presentation.
    private lateinit var homeReadinessIcon: ImageView
    private lateinit var homeReadinessTitle: TextView
    private lateinit var homeReadinessText: TextView
    private lateinit var homeReadinessAction: com.google.android.material.button.MaterialButton
    private lateinit var homeIdentityText: TextView
    private lateinit var callSecurityChip: TextView
    private lateinit var callLockButton: ImageButton
    private lateinit var callPartyPanel: View
    private lateinit var callPartyName: TextView
    private lateinit var callPartyState: TextView
    private lateinit var callMuteLabel: TextView
    private lateinit var callCameraLabel: TextView
    private lateinit var callSpeakerLabel: TextView
    private lateinit var broadcastStatusText: TextView
    private lateinit var streamSecurityText: TextView
    private lateinit var watchRelaySwitch: MaterialSwitch
    // UI redesign: bottom navigation (Live / Calls / People) and the settings pages.
    private lateinit var mainNav: com.google.android.material.bottomnavigation.BottomNavigationView
    private lateinit var callsTabScreen: View
    private lateinit var contactsTabScreen: View
    /** The call browser serves two tabs: Calls > Nearby (false) and Contacts (true). */
    private var callBrowserContacts = false
    /** The contacts list shows chats (the Chats tab, [ChatTab]); only with Messaging v2. */
    private var callBrowserChats = false
    private var settingsPage = SettingsPage.MAIN
    private lateinit var experimentsHiddenHint: View
    private lateinit var callChromeAutoHide: CallChromeAutoHide
    private val panicTapDetector = MultiTapDetector()
    private var homePermissionsAsked = false
    /** The camera screen's own permission request ran in this process ([LiveStartGate.action]). */
    private var startPermissionsAsked = false
    private var bluetoothStateReceiverRegistered = false
    private var lastConnectedCount = 0
    /** Route cost of the advertisement this phone dialled (relays on the way), for the call chip. */
    private var privateCallDialledRelays: Int? = null
    private var privateCallPartyName: String = ""
    /** The latest technical link statuses of this call (PHY, link, failures) for Link health. */
    private val privateCallLinkDetails = ArrayDeque<String>()

    private fun notePrivateCallLinkDetail(message: String) {
        if (privateCallLinkDetails.lastOrNull() == message) return
        privateCallLinkDetails.addLast(message)
        while (privateCallLinkDetails.size > LINK_DETAIL_LINES) privateCallLinkDetails.removeFirst()
    }

    /** Quick wipe waiting out its Undo window (null when none). */
    private var pendingPanicWipe: Runnable? = null
    /** Verification of the watched stream's authenticated source, looked up off the main thread. */
    private var streamSourceVerification: Pair<String, ContactVerification>? = null
    private var streamSourceLookup: String? = null
    private val bluetoothStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            // The stack reported the adapter's new name: confirms a "Hide phone name" write (it is asynchronous).
            if (intent.action == BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED) {
                applyNeutralName()
                return
            }
            // Wider reach (registered only with the switch on): Wi-Fi or Location changed, so Wi-Fi Aware eligibility and the
            // Bluetooth-off "Available over Wi-Fi" claim are recomputed (A8 review P2-4).
            if (intent.action == android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION ||
                intent.action == android.location.LocationManager.MODE_CHANGED_ACTION
            ) {
                refreshCallReachability()
                WifiDiscovery.recompute()
                return
            }
            updateHomeReadiness()
            refreshCallReachability()
            WifiDiscovery.recompute()
            // Wider reach: the Bluetooth-off edge rebuilds an idle endpoint so it listens over Wi-Fi only (A8 step 13).
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF &&
                wifiNativeReachable()
            ) {
                restartIdleCallEndpointForRelayedCalls()
            }
            // The camera screen's "Bluetooth is off" notice follows the adapter too.
            if (::streamToggleButton.isInitialized && !startupAborted) updateStartEnabled()
            // A rename that could not be written while Bluetooth was off is applied once it is on (never per session).
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) applyNeutralName()
        }
    }
    private val homePermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        homePermissionsAsked = true
        updateHomeReadiness()
        refreshCallReachability()
    }
    private var wifiPermissionAnswered: (() -> Unit)? = null
    private val wifiPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        wifiPermissionAnswered?.invoke()
        wifiPermissionAnswered = null
    }
    private val homeBluetoothEnableRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        updateHomeReadiness()
        refreshCallReachability()
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        getSystemService(BluetoothManager::class.java).adapter
    }
    private val powerManager: PowerManager by lazy {
        getSystemService(PowerManager::class.java)
    }
    private val deviceVideoCapabilities by lazy { DeviceVideoCapabilities(this) }
    private val appPreferences by lazy { getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE) }
    private var pairedDevices = emptyList<BluetoothDevice>()
    private var blePeers = emptyList<BleL2capPeer>()
    private var bleDiscovery: BleL2capDiscovery? = null
    private var codecs = emptyList<VideoCodec>()
    private var decoders = emptyList<VideoDecoderSupport>()
    private var deviceModes = emptyList<DeviceVideoMode>()
    private var updatingModePickers = false
    private var updatingRateControlPicker = false
    private var rateControlOptions = listOf(EncoderRateControl.AUTOMATIC)
    private var settings = VideoSettings.DEFAULT
    private var encoderRateControl = EncoderRateControl.AUTOMATIC
    /**
     * Auto (false): the app picks every technical value from measurements (Automatic rate control,
     * adaptive playout, speed governor, learned capacity). Advanced (true): the user's exact manual
     * choices apply and nothing adapts; no Auto option is mixed into the manual pickers.
     */
    private var advancedSettings = false
    private var videoScaleMode = VideoScaleMode.FILL
    private var receiverPlaybackDelay = PlaybackDelayProfile.AUTO
    private var showDeviceHealthOverlay = true
    private var forwardReceivedStreams = false
    private var sender: BluetoothVideoSender? = null
    private var broadcaster: BluetoothVideoBroadcaster? = null

    // ---- AUDIO-LIVE hook (Design AL): the audio-only broadcast screen. Logic and UI live in AudioLiveController. ----
    private var audioAfterPermissions: (() -> Unit)? = null
    /** DEBUG: `test_transport` forced the transport of a UI-started radio (not persisted as the user's choice). */
    private var audioTransportForced = false
    private val audioLiveHost = object : AudioLiveController.Host {
        override val preferences get() = appPreferences

        override fun showVideoGoLive() = showStreamScreen()

        override fun enterAudioBroadcastScreen() {
            stopPrivateCallEndpoint()
            stopNearbyStreamDiscovery()
            sessionRole = SessionRole.SEND
            appScreen = AppScreen.CAMERA
            receiverBrowserScreen.visibility = View.GONE
            callBrowserScreen.visibility = View.GONE
            callsTabScreen.visibility = View.GONE
            contactsTabScreen.visibility = View.GONE
            callScreen.visibility = View.GONE
            settingsScreen.visibility = View.GONE
            cameraScreen.visibility = View.GONE
            updateConnectionDot(0)
            ensurePermissionsAndRefresh()
        }

        override fun leaveToLive() = showReceiverBrowser()

        override fun armAudioBroadcast(title: String?) {
            if (PeerLabelFeature.enabled) PeerLabelFeature.localStreamTitle = title
            armDiscoverableStream()
        }

        override fun stopBroadcast(message: String) = stopStreaming(message)

        override fun switchToVideo() = switchAudioLiveToVideo()

        override fun togglePause() = toggleBroadcastPause()

        override fun streamPaused(): Boolean = this@MainActivity.streamPaused

        override fun namesAndTitlesOn(): Boolean = PeerLabelFeature.enabled

        override fun hasMicrophonePermission(): Boolean = hasRequiredPermissions()

        override fun requestMicrophonePermission(onGranted: () -> Unit) {
            audioAfterPermissions = onGranted
            ensurePermissionsAndRefresh()
        }

        override fun debugReferenceAudioFile(): File? = this@MainActivity.debugReferenceAudioFile

        override fun leaveListening() = showReceiverBrowser()

        override fun setReceiverMuted(muted: Boolean) {
            receiver?.setListenerMuted(muted)
        }

        override fun relayCount(routeCost: Int): Int = advertisedRelayCount(routeCost) ?: 0
    }

    /** Titles of the rows last drawn in the Live list, in row order: the listening screen shows the same title. */
    private var nearbyStreamRowTitles: List<String> = emptyList()

    /** Audio-only offers whose marker disagreed with the stream: penalised per attempted route only (review 17). */
    private val streamRoutePenalties = StreamRoutePenalties()

    private fun streamRouteKey(peer: BleL2capPeer) = StreamRoutePenalties.Route(
        peer.device.address.orEmpty(),
        peer.announcement.nodeIdPrefix.joinToString("") { "%02x".format(it) },
        peer.announcement.streamToken,
    )

    /** An establishment-failing parent, or a route whose marker disagreed with its stream a moment ago. */
    private fun routeFailing(peer: BleL2capPeer): Boolean =
        establishmentFailing(peer.announcement) ||
            streamRoutePenalties.isPenalized(streamRouteKey(peer), null, SystemClock.elapsedRealtime())

    private fun audioListenTitle(peer: BleL2capPeer): String? {
        val index = nearbyStreamRows.indexOfFirst { group ->
            group.routes.any {
                it.announcement.streamToken == peer.announcement.streamToken &&
                    it.announcement.nodeIdPrefix.contentEquals(peer.announcement.nodeIdPrefix)
            }
        }
        return nearbyStreamRowTitles.getOrNull(index)
    }
    private val audioLive: AudioLiveController by lazy { AudioLiveController(this, audioLiveHost) }
    // ---- end AUDIO-LIVE hook ----
    private var receiver: AndroidVideoReceiver? = null
    private var relay: BluetoothVideoRelay? = null
    private var privateCallRelay: BluetoothPrivateCallRelay? = null
    /** First run (audit 2026-10-21): after onboarding the app opens on Calls, not Live. */
    private var landOnCallsAfterOnboarding = false
    /**
     * Calls page (user feedback 2026-10-21): the status line shows only an actionable state (a scanner or endpoint
     * failure, a hint about where to look); searching is a small indicator, outcomes and errors are snackbars.
     */
    private var callsActionableNotice: String? = null
    /** When the Calls search started showing nothing found yet; the empty-state explanation waits a little. */
    private var callsSearchStartedMs = 0L
    /** When the current discovery round started; the searching indicator settles after a while ([CallsQuiet.indicatorVisible]). */
    private var callsIndicatorStartedMs = 0L
    /** The call or stream that waited while the user was in Wi-Fi settings; it continues on return. */
    private var pendingAfterWifiSettings: (() -> Unit)? = null
    /** The type of the incoming call that is ringing (a missed-call Call back uses it). */
    private var incomingCallType: CallType? = null
    @Volatile private var linkRateLimitBps: Int? = null
    /** Automatic rate control for this phone's outgoing call video (hop-local, like streams). */
    @Volatile private var privateCallRateController: LinkRateController? = null
    /** F16 fix 1 (Experimental, `test_call_link_share`): the cap that leaves airtime for the received direction. */
    @Volatile private var privateCallLinkShare: CallLinkShare? = null
    @Volatile private var privateCallLinkRateLimitBps: Int? = null
    @Volatile private var privateCallLinkFps: Int? = null
    private var debugBenchmarkCoordinator: DebugBenchmarkCoordinator? = null
    private val bluetoothRestartProbe: () -> String? = { activeBluetoothSession() }
    private var debugSessionTelemetry: DebugSessionTelemetry? = null
    private var cameraEncoder: CameraVideoEncoder? = null
    /** Experimental [SourceMediaPrewarm]: the media runs for a broadcaster whose first viewer has not authenticated yet. */
    private var streamMediaPrewarmed = false
    private var microphoneEncoder: MicrophoneOpusEncoder? = null
    private var privateCall: AndroidPrivateVideoCall? = null
    private var privateCallPhase: PrivateCallPhase? = null
    private var privateCallAvailabilityStarting = false
    /** Endpoint starts in flight; a stop cancels them and the viewer waits for them (CallEndpointStarts.kt, STREAM-INTEROP block 1). */
    private val callEndpointStarts = CallEndpointStartGate()
    private val callEndpointSettleTimeout = Runnable {
        val released = callEndpointStarts.abandonInFlight()
        if (released > 0) {
            Log.w(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_reachability: viewer_start_released | reason=endpoint_start_timeout",
            )
        }
    }
    /** Diagnostics for the call listener (CallListenerViewing): a stream selected or a viewer running. */
    private val viewingProbe: () -> Boolean = { selectedStreamPeer != null || receiver != null }
    /** The endpoint finished startAvailability and is advertising + listening (CallReachability). */
    private var privateCallAvailable = false
    /** The current call arrived through Home reachability: go back to Home when it ends. */
    private var privateCallReturnHome = false
    /** The incoming call interrupted the Live tab (reachable while browsing): return there when it ends. */
    private var privateCallReturnLive = false
    /** Between onStart and onStop: a foreground service may be started now (Android 12+). */
    private var activityStarted = false
    private var lastReachabilityLog: String? = null
    private var privateCallDiscovery: BleL2capDiscovery? = null
    private var privateCallPeers = emptyList<BleL2capPeer>()
    private val privateCallPeersLastSeen = mutableMapOf<String, Pair<BleL2capPeer, Long>>()
    /** How long each Nearby call row stays unheard, from how often this scanner hears it ([CallPeerCadence]). */
    private val callPeerCadence = CallPeerCadence()
    /** Per key: the Bluetooth leg and the Wi-Fi leg of a call-list row (Wider reach). privateCallPeersLastSeen is the display cache rebuilt from it. */
    private val callSightings = PeerSightings<BleL2capPeer, WifiSighting>(
        bleFresh = { key, seenMs, nowMs -> callPeerCadence.isFresh(key, seenMs, nowMs) },
    )
    /** The scanner's local prefix, kept for the Wi-Fi call-offer path (one Keystore read per scanner restart). [AM 22] */
    private var callListLocalPrefix: ByteArray? = null
    @Volatile private var pendingPrivateCallPeerKey: String? = null
    /** Experimental automatic store-and-forward sync (StoreForwardAutoSyncFeature, default off). */
    private var autoSyncScheduler: StoreForwardAutoSyncScheduler? = null
    private val autoSyncEvaluating = java.util.concurrent.atomic.AtomicBoolean(false)
    private val autoSyncLastEvalMs = mutableMapOf<String, Long>()
    private val autoSyncLastLog = mutableMapOf<String, String>()
    /** The peer an automatic exchange is refreshing before it connects; a user tap replaces it. */
    private var autoSyncPendingKey: String? = null
    /** Messaging v2 chat session (phase 1d, switch test_messaging_v2 + test_mv2_chat_session): the contact the last dial was for. */
    private var chatDialPeer: NodeId? = null
    private val chatLastEvalMs = mutableMapOf<String, Long>()
    private val chatLastLog = mutableMapOf<String, String>()
    private val chatTickHandler = Handler(Looper.getMainLooper())
    /** Version of the selected offer: a refresh must match it (rotating IDs, RV15). */
    private var pendingPrivateCallPeerVersion = DiscoveryAnnouncement.FIXED_PREFIX_VERSION
    /** SEC-B3: the route cost of the selected offer; the address refresh never turns a direct selection into a relayed one (DialRefreshGate). */
    private var pendingPrivateCallRouteCost = 0

    /** The row the pending dial was started from: its key, recognised contact and address ([DialPersonMatch]). */
    private var pendingPrivateCallTarget: DialPersonMatch.Target? = null
    private var pendingPrivateCallSelectedAtMs = 0L
    @Volatile private var privateCallCameraEncoder: CameraVideoEncoder? = null
    private val privateCallCameraExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PrivateCallCameraLifecycle")
    }
    /** Experimental call pre-warm ([CallPrewarm], `test_call_prewarm`, off): an encoder prepared while the call rings. */
    private val privateCallPrewarm by lazy {
        CallEncoderPrewarm<CallPrewarmKey, CameraVideoEncoder>(
            run = { task -> privateCallCameraExecutor.execute(task) },
            nowMs = SystemClock::elapsedRealtime,
            log = { line -> Log.i("BluetoothVideo", line) },
            prepare = CameraVideoEncoder::prepare,
            release = CameraVideoEncoder::close,
        )
    }
    private var privateCallMicrophoneEncoder: MicrophoneOpusEncoder? = null
    private var privateCallAudioSession: PrivateCallAudioSession? = null
    /** DEBUG benchmark self-view of the raw encoder input; null with a camera or when disabled. */
    private var privateCallRawSelfView: CallRawSelfView? = null
    private var privateCallSettings: VideoSettings? = null
    /** This call's resolution ladder (CallResolutionLadderFeature); null while off or not applicable. */
    private var privateCallResolutionLadder: CallResolutionLadder? = null

    /** GPU scale ladder (GpuScaleFeature): set while this call's camera runs through the GPU path. */
    private var privateCallGpuScale: GpuScaleRequest? = null
    /** Size of the last call encoder that sent video: a restart at another size marks its keyframe (RestartDiscontinuityMarker). */
    private var privateCallSentResolution: VideoResolution? = null
    private var privateCallCodec: VideoCodec? = null
    private var privateCallActiveAudioBitRate: Int? = null
    private var privateCallMuted = false
    private var privateCallCameraEnabled = true
    /** Experimental voice calls and push-to-talk (IntercomFeatures, default off). */
    private lateinit var intercomCallUi: IntercomCallUi
    private var pendingPrivateCallType = CallType.VIDEO
    /** Experimental store-and-forward: the pending peer selection is a message exchange, not a call. */
    private var pendingPrivateCallMessageSync = false
    /** Incoming ringtone/vibration and the experimental ringback (CallRinger). */
    private val callRinger by lazy { CallRinger(this) }
    /** The user's mute plus the audio-focus hold (CallFocusHold); replaced at each call's media start. */
    private var privateCallFocusHold = CallFocusHold()
    /** Experimental camera pause at the ear (test_call_proximity_camera_pause); null while off. */
    private var privateCallProximityPause: CallProximityCameraPause? = null
    /** True while the camera is stopped only because the phone is at the ear. */
    private var privateCallCameraPausedAtEar = false

    /** Measured Auto ladder (calls): the camera is paused because the link cannot carry the lowest video. */
    private var privateCallCameraPausedByLink = false
    private var privateCallAudioFirstGate: AudioFirstGate? = null
    private var privateCallFpsHysteresis: FpsHysteresis? = null
    private var privateCallClimbGuard: ClimbGuard? = null
    /** Experimental lock-screen call presentation (off by default; see CallLockScreen). */
    private val callLockScreen by lazy { CallLockScreen(this) }
    private var callNotificationPermissionAsked = false
    private val callNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> Log.i("BluetoothVideo", "Call alert notifications permission granted=$granted") }
    @Volatile private var privateCallRemoteSurface: Surface? = null
    private var privateCallSurfaceFormat: SurfaceVideoFormat? = null
    /** This call's double-tap framing choice for the other person's video. [RemoteVideoFit] */
    private val privateCallRemoteFit = RemoteVideoFitState()
    private var privateCallRemoteShownMode: VideoScaleMode? = null
    private var privateCallRemoteVideoStarted = false
    private var debugPrivateCallSurfacePending = false
    private var privateCallMediaStartedAtNs = 0L
    private var privateCallContinuityTracker: RenderedFrameContinuityTracker? = null
    private var privateCallContinuityLastReportNs = 0L
    private var pendingDebugPrivateCallInvite: Pair<String, String?>? = null
    /** A dial (user tap or DEBUG) whose target is not in the list yet: waits for its next sighting, bounded (DebugDialWait). */
    @Volatile private var debugDialWait: DebugDialWait.Pending? = null
    /** F14: the user's own dial (a tapped row or a contact call), for [DialCollision]; read through [currentOwnDial]. */
    @Volatile private var ownDial: OwnDial? = null
    /** Start (elapsedRealtime) of a dial that is waiting in the normal address-refresh selection, else 0 (not for automatic exchanges). */
    private var dialSelectionStartedAtMs = 0L
    /** Shown name of the user dial waiting in the address-refresh selection (status line "Looking for ..."). */
    private var pendingPrivateCallPeerName: String? = null
    /** "Not found nearby" notice of the last user dial that ran out of time; later sightings must not wipe it at once. */
    private var dialFailureNotice: String? = null
    private var dialFailureNoticeKey: String? = null
    private var dialFailureNoticeAtMs = 0L
    private val debugDialTimeout = Runnable { expireDebugDialWait() }
    private var ivfReplay: IvfVideoReplay? = null
    private var activeStreamSettings: VideoSettings? = null
    private var cameraPreview: CameraPreview? = null
    private var activeCameraViewfinderNeedsRefresh = false
    private var streamAdvertiser: BleDiscoveryAdvertiser? = null
    /** The stream offer last advertised over Bluetooth, published on Wi-Fi Aware while it is on air (A8 step 10.6). */
    @Volatile private var lastStreamOfferAnnouncement: DiscoveryAnnouncement? = null
    /** Experimental peer labels: public title pages beside the stream offer (closed with it). */
    private var streamTitleAdvertiser: TitleAdvertiser? = null
    private val loggedTitleLookups = HashSet<Int>() // DEBUG title_lookup: once per offer token
    private var streamDiscovery: BleL2capDiscovery? = null
    private var nearbyStreamDiscovery: BleL2capDiscovery? = null
    private var pendingViewerPeers = emptyList<BleL2capPeer>()
    private val viewerPeersLastSeen = mutableMapOf<String, Pair<BleL2capPeer, Long>>()
    private var nearbyStreamPeers = emptyList<BleL2capPeer>()
    /** Watch list rows: one per broadcast, its routes best first (groupStreamRoutes). */
    private var nearbyStreamRows = emptyList<StreamRouteGroup<BleL2capPeer>>()
    private val nearbyStreamsLastSeen = mutableMapOf<String, Pair<BleL2capPeer, Long>>()
    private var selectedStreamPeer: BleL2capPeer? = null
    private var streamToken = 0
    private var streamCapacityGeneration = 0
    private var streamAdvertisedFull = false
    private var streamRouteToken = 0

    /**
     * Rotating IDs, phase 1b (default off): the viewer-request tokens this source accepts (current plus the
     * previous one for 60 s) and the boundary listener that restarts the stream offer on a new set. Off: the
     * window holds the one route token and behaves as the old exact-token test.
     */
    private val streamRequestTokens = RouteTokenWindow()
    private var streamBoundaryListening = false
    private val streamBoundaryListener: (Long) -> Unit = { _ -> rotateStreamPresenceForBoundary() }
    private var streamPresenceStarting = false
    private var streamArmed = false
    private var streamPaused = false
    private var receiverStreamEnded = false
    /** Review B2: per-attempt and per-session "became active" ([ReceiverActivityFlags]). */
    private val receiverActivity = ReceiverActivityFlags()
    /** This receiver attempt became active (reset by every startReceiving). */
    private var receiverWasActive by receiverActivity::attempt
    /** A live join that failed before it became active: Try again and one automatic retry ([StreamJoinRetry]). */
    private val streamJoinRetry = StreamJoinRetry<LogicalStreamTarget>()
    private var streamJoinSnackbar: com.google.android.material.snackbar.Snackbar? = null
    private var receiverRouteWithdrawal: StreamRouteWithdrawal? = null
    private var receiverBrowserStartedAtNs = 0L
    private var receiverFirstOfferLogged = false
    private var receiverJoinStartedAtNs = 0L
    private var receiverFirstSurfaceForJoinPending = false
    private var receiverPlaybackPaused = false
    private val receiverRecoveryNoticePolicy = ReceiverRecoveryNoticePolicy(
        holdMs = { if (DebugTunables.receiverUiStability) ReceiverRecoveryNoticePolicy.REPEAT_HOLD_MS else 0L },
    )
    /** Releases a held "Recovering video" cover once a repeating recovery has stayed quiet. */
    private var receiverRecoveryCoverRelease: Runnable? = null
    private var receiverRecoveryNoticeRunnable: Runnable? = null
    private var viewerDiscoveryContinuousLowLatency: Boolean? = null
    private var receiverContinuityTracker: RenderedFrameContinuityTracker? = null
    private var receiverContinuityLastReportNs = 0L
    private var activeStreamTarget: LogicalStreamTarget? = null
    @Volatile private var activeStreamSourceNodeId: NodeId? = null
    @Volatile private var activeStreamSourceAuthenticated = false
    private var reconnectTarget: LogicalStreamTarget? = null
    /** The viewer's search for a lost stream ends after 60 s (audit CALL-04): [StreamSearchBound], [giveUpStreamSearch]. */
    private val streamSearch = StreamSearchBound()
    private val streamSearchGiveUp = Runnable { giveUpStreamSearch() }
    private var reconnectPreferredTransport: BluetoothTransportOption? = null
    private var reconnectFromNodeIdPrefix: ByteArray? = null
    /** After a link loss of an active relayed route, that relay is tried first (RelayChildReanchor.kt). */
    private var preferredRecoveryParent: ByteArray? = null
    /** Until this elapsedRealtime, reconnection waits for [preferredRecoveryParent]'s offer (0 = no wait). */
    private var preferredRecoveryGraceUntilMs = 0L
    private var preferredRecoveryGraceLogged = false
    private val preferredRecoveryGraceExpired = Runnable {
        if (reconnectTarget != null && receiver == null) refreshNearbyStreamPeers()
    }
    private val suppressedStreamRoutes = mutableMapOf<StreamReconnectTarget, Long>()
    private val capacitySuppressedRouteGenerations = mutableMapOf<StreamReconnectTarget, Int>()
    private val consecutiveRouteFailures = mutableMapOf<StreamReconnectTarget, Int>()
    /** Parents whose links failed to establish (hop-local; ranks them last for a bounded time). */
    private val parentEstablishment = EstablishmentFailureTracker()

    private fun establishmentFailing(announcement: DiscoveryAnnouncement): Boolean =
        DebugTunables.relayEstablishFallback && parentEstablishment.isMarked(
            AdaptivePhyMemory.prefixLabel(announcement.nodeIdPrefix),
            SystemClock.elapsedRealtime(),
        )

    /** Logs when the establishment rule moved the choice away from a marked parent. */
    private fun logEstablishmentFallback(candidates: List<BleL2capPeer>, chosen: BleL2capPeer?) {
        if (chosen == null || establishmentFailing(chosen.announcement)) return
        val avoided = candidates.filter { routeFailing(it) }
        if (avoided.isEmpty()) return
        Log.i(
            "BluetoothVideo",
            "BENCHMARK relay_parent_fallback: reason=establishment | side=child | avoided=" +
                avoided.joinToString(",") { AdaptivePhyMemory.prefixLabel(it.announcement.nodeIdPrefix) } +
                " | selected=${AdaptivePhyMemory.prefixLabel(chosen.announcement.nodeIdPrefix)} | " +
                "hops=${chosen.announcement.routeCost}",
        )
    }
    /** Same-signature receiver failures; survives a reconnect becoming briefly active. */
    private val repeatedReceiverFailures = RepeatedFailureBackoff()
    /** A local decoder/playback failure stopped playback; debug auto-select must not loop on it. */
    private var receiverLocalMediaFailure: String? = null
    private var preserveReceiverContinuityOnNextFormat = false
    private var receiverBrowserNotice: String? = null
    private var receiverBrowserNoticeDeadlineMs = 0L
    private val discoveryHandler = Handler(Looper.getMainLooper())
    private val privateCallDiscoveryHandler = Handler(Looper.getMainLooper())
    private val sessionHandler = Handler(Looper.getMainLooper())
    private val pausedHeartbeatRunnable = object : Runnable {
        override fun run() {
            if (!streamPaused) return
            broadcaster?.repeatPausedState()
            sessionHandler.postDelayed(this, PAUSED_HEARTBEAT_MS)
        }
    }
    private val receiverBrowserNoticeTimeoutRunnable = Runnable {
        receiverBrowserNotice = null
        receiverBrowserNoticeDeadlineMs = 0L
        if (appScreen == AppScreen.RECEIVER_BROWSER) refreshNearbyStreamPeers()
    }
    private var activityResumed = false
    private var localTestRunning = false
    private var sessionRole = SessionRole.SEND
    private var appScreen = AppScreen.HOME
        set(value) {
            field = value
            updateMainNav()
        }
    private var settingsReturnScreen = AppScreen.HOME
    private var cameraLens = CameraLens.BACK
    private var availableCameraLenses = emptyList<CameraLens>()
    private var outgoingTransport = BluetoothTransportOption.DEFAULT
    /**
     * Experimental CoC -> GATT fallback (CocAutoGatt.kt, default off). The transport the current call
     * endpoint was created with; null outside a call-browser session. With the flag off it always
     * equals [outgoingTransport], so [currentCallTransport] is exactly the old behaviour.
     */
    private var callEndpointTransport: BluetoothTransportOption? = null
    private var callTransportChoice: CocTransportChoice? = null
    /** A call to retry over GATT once the GATT endpoint is available (set after a CoC connect failure). */
    private var cocRetryPeer: Pair<BleL2capPeer, CallType>? = null
    /** A GATT peer chosen from an L2CAP session: the endpoint is rebuilt on GATT, then this peer is called. */
    private var cocRebuildPeer: Pair<BleL2capPeer, CallType>? = null
    /** The peer of the outgoing call in progress, kept so a CoC connect failure can retry it over GATT. */
    private var lastOutgoingCallPeer: Pair<BleL2capPeer, CallType>? = null
    private val currentCallTransport: BluetoothTransportOption
        get() = callEndpointTransport ?: outgoingTransport
    private val debugBlePhyPreference = BlePhyPreference.TWO_M
    private val debugCorruptRecord: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_corrupt_record", false)
    }
    private val debugPreserveCallBitrate: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_preserve_call_bitrate", false)
    }
    private val debugGattDeferConnectionPriority: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_gatt_defer_connection_priority", false)
    }
    private val debugVideoPresentationTiming: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_video_presentation_timing", false)
    }
    /** On by default; a debug launch may turn it off for A/B. */
    private val debugReleaseVideoAtPlayout: Boolean by lazy {
        !BuildConfig.DEBUG || intent.getBooleanExtra("test_release_video_at_playout", true)
    }
    private val debugCallPipelineTiming: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_call_pipeline_timing", false)
    }
    /** DEBUG `test_raw_observer=<hex>` (+ `test_raw_observer_seconds`): extra unfiltered raw advert observer, see DebugRawObserver. */
    private val debugRawObserver: DebugRawObserver? by lazy {
        DebugRawObserver.create(
            BuildConfig.DEBUG, bluetoothAdapter, intent.getStringExtra("test_raw_observer"),
            intent.extras?.get("test_raw_observer_seconds").let { (it as? Number)?.toLong() ?: it?.toString()?.toLongOrNull() },
        )
    }
    private val debugMediaTraceSession: String? by lazy {
        // DEBUG `test_stage_trace_v1=true`: log stage rows as legacy one-line `stage v1` instead of batched `stage v2`.
        if (BuildConfig.DEBUG) DebugStageTrace.legacyV1 = intent.getBooleanExtra("test_stage_trace_v1", false)
        intent.getStringExtra("test_media_trace")?.takeIf { BuildConfig.DEBUG && it.isNotBlank() }
    }
    /** On by default where the decoder supports it; a debug launch may turn it off for A/B. */
    private val debugLowLatencyDecoder: Boolean by lazy {
        !BuildConfig.DEBUG || intent.getBooleanExtra("test_decoder_low_latency", true)
    }
    private val debugBroadcastAudioEnabled: Boolean by lazy {
        !BuildConfig.DEBUG || intent.getBooleanExtra("test_audio", true)
    }
    private val debugPreferredDecoderName: String? by lazy {
        intent.getStringExtra("test_decoder")?.takeIf { BuildConfig.DEBUG && it.isNotBlank() }
    }
    private val debugStreamIoConfig: VideoStreamIoConfig by lazy {
        if (!BuildConfig.DEBUG) return@lazy VideoStreamIoConfig.DEFAULT
        val bufferKiB = intent.getIntExtra(
            "test_stream_buffer_kib",
            VideoStreamIoConfig.DEFAULT_BUFFER_BYTES / 1_024,
        ).coerceIn(
            VideoStreamIoConfig.MIN_BUFFER_BYTES / 1_024,
            VideoStreamIoConfig.MAX_BUFFER_BYTES / 1_024,
        )
        VideoStreamIoConfig(
            bufferBytes = intent.getIntExtra("test_stream_buffer_bytes", 0)
                .takeIf { it > 0 }
                ?.coerceIn(VideoStreamIoConfig.MIN_BUFFER_BYTES, VideoStreamIoConfig.MAX_BUFFER_BYTES)
                ?: (bufferKiB * 1_024),
            flushPolicy = VideoFlushPolicy.fromDebugValue(
                intent.getStringExtra("test_stream_flush"),
            ),
        )
    }

    private fun streamIoConfigFor(transport: BluetoothTransportOption): VideoStreamIoConfig {
        if (BuildConfig.DEBUG && (
                intent.hasExtra("test_stream_buffer_kib") || intent.hasExtra("test_stream_buffer_bytes")
            )
        ) {
            return debugStreamIoConfig
        }
        return debugStreamIoConfig.copy(bufferBytes = defaultVideoStreamBufferBytes(transport))
    }
    private val debugReplayPath: String? by lazy {
        intent.getStringExtra("test_replay_ivf")?.takeIf { BuildConfig.DEBUG && it.isNotBlank() }
    }
    /**
     * The raw fixture's declared geometry: `test_raw_width` and `test_raw_height` from the runner, else the
     * launch's requested `test_width` and `test_height` before any camera-mode mapping ([RawFixtureGeometry]).
     */
    private val debugRawGeometry: Pair<Int, Int>? by lazy {
        if (debugRawInputFile == null) {
            null
        } else {
            RawFixtureGeometry.declared(
                intent.getIntExtra("test_raw_width", 0), intent.getIntExtra("test_raw_height", 0),
                intent.getIntExtra("test_width", 0), intent.getIntExtra("test_height", 0),
            )
        }
    }

    private val debugRawInputFile: File? by lazy {
        intent.getStringExtra("test_raw_yuv")?.takeIf { BuildConfig.DEBUG && it.isNotBlank() }?.let(::File)
    }
    /** DEBUG raw-input call self-view rate: 5 FPS by default, 0 turns it off. */
    private val debugCallRawSelfViewFps: Int by lazy {
        if (BuildConfig.DEBUG) intent.getIntExtra("test_call_raw_self_view_fps", 5).coerceIn(0, 15) else 0
    }
    private val debugReferenceAudioFile: File? by lazy {
        intent.getStringExtra("test_reference_audio")?.takeIf { BuildConfig.DEBUG && it.isNotBlank() }?.let(::File)
    }
    private val debugMediaImpairmentFile: File? by lazy {
        intent.getStringExtra("test_media_impairment")?.takeIf { BuildConfig.DEBUG && it.isNotBlank() }?.let(::File)
    }
    private var debugBroadcastImpairment: DebugMediaImpairment? = null
    private var debugCallImpairment: DebugMediaImpairment? = null

    private fun debugImpairedSend(
        send: (Int, Long, ByteArray) -> Unit,
        call: Boolean = false,
    ): (Int, Long, ByteArray) -> Unit {
        if (debugMediaImpairmentFile == null) return send
        val impairment = if (call) {
            debugCallImpairment ?: DebugMediaImpairment.startIfRequested(this, debugMediaTraceSession, debugMediaImpairmentFile)
                .also { debugCallImpairment = it }
        } else {
            debugBroadcastImpairment ?: DebugMediaImpairment.startIfRequested(this, debugMediaTraceSession, debugMediaImpairmentFile)
                .also { debugBroadcastImpairment = it }
        }
        return if (impairment == null) send else { flags, ptsUs, bytes -> impairment.deliver(flags, ptsUs, bytes, send) }
    }
    private var debugControlSequence = 0L
    /** DEBUG: the launch asked for an encoder or allocation this phone lacks and was refused (BenchmarkSetupRefusal). */
    private var debugSetupRefused = false
    private var debugPendingControl: Pair<Long, Long>? = null

    private fun logDebugControlCompletion(control: String, applied: Boolean, pending: Pair<Long, Long>) {
        if (debugMediaTraceSession == null) return
        Log.i("BluetoothVideo", "BENCHMARK lifecycle v1 " + JSONObject()
            .put("session", debugMediaTraceSession).put("pid", android.os.Process.myPid())
            .put("clock", "System.nanoTime").put("action_id", pending.first).put("action", control)
            .put("kind", "ack").put("authenticated", false)
            .put("command_ns", pending.second).put("ack_ns", System.nanoTime())
            .put("applied", applied).put("scope", "Local app acceptance; remote completion requires its own acknowledgement")
            .toString())
    }

    private fun referenceAudioVideoFrames(selected: VideoSettings = activeStreamSettings ?: settings): Long {
        if (debugReferenceAudioFile == null) return 0L
        val raw = requireNotNull(debugRawInputFile) { "Reference audio requires the matching native raw video" }
        val frameBytes = selected.resolution.width.toLong() * selected.resolution.height * 3 / 2
        require(frameBytes > 0 && raw.length() % frameBytes == 0L) { "Reference raw video has incomplete native frames" }
        return raw.length() / frameBytes
    }
    private val debugAutoBroadcastRequested: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_auto_broadcast", false)
    }
    private val debugAutoSelectStream: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_auto_select_stream", false)
    }
    private val debugStreamNodePrefix: String? by lazy {
        intent.getStringExtra("test_stream_node")?.lowercase(Locale.US)
            ?.takeIf { BuildConfig.DEBUG && it.matches(Regex("[0-9a-f]{12}")) }
    }
    private val debugMinimumRouteCost: Int by lazy {
        if (BuildConfig.DEBUG) {
            intent.getIntExtra("test_min_route_cost", 0).coerceAtLeast(0)
        } else {
            0
        }
    }
    private val debugClassicAddressNodePrefix: String? by lazy {
        intent.getStringExtra("test_classic_address_node")
            ?.lowercase(Locale.US)
            ?.takeIf { BuildConfig.DEBUG && it.length == DiscoveryAnnouncement.NODE_PREFIX_BYTES * 2 }
    }
    private val debugClassicAddress: String? by lazy {
        intent.getStringExtra("test_classic_address")
            ?.takeIf { BuildConfig.DEBUG && BluetoothAdapter.checkBluetoothAddress(it) }
    }
    private val debugMaximumChildren: Int? by lazy {
        intent.getIntExtra("test_max_children", 0)
            .takeIf { BuildConfig.DEBUG && it > 0 }
            ?.coerceAtMost(8)
    }
    private val debugRequireRelay: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_require_relay", false)
    }
    private val debugRelayChildCount: Int by lazy {
        if (BuildConfig.DEBUG) {
            intent.getIntExtra("test_relay_children", 1).coerceIn(1, 3)
        } else {
            1
        }
    }
    private val debugPrivateCallRelay: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_private_call_relay", false)
    }
    /** DEBUG call relay: take only the offer for this 12-hex-digit callee prefix. */
    private val debugRelayCalleePrefix: String? by lazy {
        intent.getStringExtra("test_relay_callee")?.lowercase(Locale.US)
            ?.takeIf { BuildConfig.DEBUG && it.matches(Regex("[0-9a-f]{12}")) }
    }
    /** DEBUG call relay: take only an offer at this route cost (0 = the callee, n = the n-th relay). */
    /** DEBUG call relay with rotating IDs: the callee advertises version 5, so take the one rotating offer (no prefix to match). */
    private val debugRelayCalleeRotating: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_relay_callee_rotating", false)
    }
    private val debugRelayRouteCost: Int? by lazy {
        intent.getIntExtra("test_relay_route_cost", -1).takeIf { BuildConfig.DEBUG && it >= 0 }
    }
    private val debugReceiverPacketDelayMs: Long by lazy {
        if (BuildConfig.DEBUG) {
            intent.getLongExtra("test_receiver_delay_ms", 0L).coerceIn(0L, 1_000L)
        } else {
            0L
        }
    }
    private val debugSourceGattPriority: BleConnectionPriority? by lazy {
        intent.getStringExtra("test_source_gatt_priority")
            ?.takeIf { BuildConfig.DEBUG && it in setOf("high", "dck", "balanced") }
            ?.let(BleConnectionPriority::fromDebugValue)
    }
    private val debugForwarderGattPriority: BleConnectionPriority? by lazy {
        intent.getStringExtra("test_forwarder_gatt_priority")
            ?.takeIf { BuildConfig.DEBUG && it in setOf("high", "dck", "balanced", "low") }
            ?.let(BleConnectionPriority::fromDebugValue)
    }
    private val debugDirectGattHeartbeat: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_direct_gatt_heartbeat", true)
    }
    private val debugGattWriteTiming: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_gatt_write_timing", false)
    }
    private val debugPlaybackDelayMs: Long? by lazy {
            intent.getIntExtra("test_playback_delay_ms", -1)
                .takeIf { BuildConfig.DEBUG && it >= 0 }
                ?.coerceAtMost(500)
                ?.toLong()
    }
    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var thermalListenerRegistered = false
    private var lastThermalHeadroomSampleMs = -THERMAL_HEADROOM_SAMPLE_MS
    private var lastBatteryTemperatureSampleMs = -BATTERY_TEMPERATURE_SAMPLE_MS
    private var cachedBatteryTemperatureC: Float? = null
    private var automaticThermalReductionEnabled = true
    private var thermalAdaptationApplied = false

    /** The automatic-cooling setting AND the registry entry (`test_device_policy_off=thermal-status-ladder`). */
    private val thermalReductionOn: Boolean
        get() = automaticThermalReductionEnabled && MemoryThermalPolicy.ladderActive()

    /** The forecast experiment acts only while thermal reduction is on (benchmark profiles turn it off). */
    private val thermalForecastOn: Boolean
        get() = MemoryThermalPolicy.headroomForecast && thermalReductionOn
    private var thermalForecastHeadroom: Float? = null

    /**
     * codec_power_aware ([CodecPowerAware]): the session-start power state from this activity's thermal policy inputs
     * (the reported status with the battery fallback and the Experimental forecast, as the thermal adaptation reads it).
     */
    private val codecPowerProvider: () -> CodecPowerState = {
        CodecPowerAware.state(
            runCatching { powerManager.isPowerSaveMode }.getOrDefault(false),
            MemoryThermalPolicy.effectiveStatus(
                thermalReportedWithFallback(runCatching { powerManager.currentThermalStatus }.getOrDefault(thermalStatus)),
                thermalForecastHeadroom,
                thermalForecastOn,
            ),
        )
    }
    private var lastThermalBenchmarkLine = ""
    private val thermalHandler = Handler(Looper.getMainLooper())

    /**
     * The 2 s headroom sampler of the corrected forecast path and the battery-temperature fallback (batch T). Created
     * only when an Experimental switch needs it; the default path never builds it.
     */
    private val thermalForecastSampler by lazy {
        ThermalHeadroomSampler.forPowerManager(powerManager, SystemClock::elapsedRealtime, ThermalSwitches.samplerConfig())?.let { sampler ->
            ThermalHeadroomSamplerDriver(
                sampler,
                ThermalHeadroomSamplerDriver.handlerScheduler(thermalHandler),
                { MemoryThermalPolicy.headroomSamplerNeeded(thermalReductionOn) && thermalVideoActive() },
                ::onThermalSamplerDecision,
            )
        }
    }
    private val thermalDisplayRunnable = object : Runnable {
        override fun run() {
            sampleThermalHeadroomIfDue()
            updateThermalBadge()
            if (activityResumed) thermalHandler.postDelayed(this, THERMAL_BADGE_REFRESH_MS)
        }
    }
    private val thermalRecoveryRunnable = Runnable {
        if (thermalAdaptationApplied && !ThermalGuardPolicy.shouldBlockStreaming(thermalStatus)) {
            val percent = applyThermalAdaptation(thermalStatus)
                ?: return@Runnable
            setStatus(
                if (percent == 100) {
                    "Phone cooled down: restored the selected stream limits."
                } else {
                    "Phone cooled down: automatic cooling now uses $percent% stream limits."
                },
            )
        }
    }
    private var surfaceVideoFormat: SurfaceVideoFormat? = null
    private val thermalStatusListener = PowerManager.OnThermalStatusChangedListener { status ->
        val previousStatus = thermalStatus
        thermalStatus = status
        updateThermalBadge()
        val videoActive = thermalVideoActive()
        logThermalDecision(
            status,
            MemoryThermalPolicy.effectiveStatus(status, thermalForecastHeadroom, thermalForecastOn),
            0,
            "listener",
        )
        when {
            ThermalGuardPolicy.shouldBlockStreaming(status) && videoActive -> {
                thermalHandler.removeCallbacks(thermalRecoveryRunnable)
                // Calls at critical heat (DECISIONS 2026-11-06): a call goes on as voice with its camera off and the
                // idle call endpoint stays reachable; streams still stop.
                if (privateCallPhase == PrivateCallPhase.ACTIVE || privateCallPhase == PrivateCallPhase.NEGOTIATING_MEDIA) {
                    stopPrivateCallVideoForHeat()
                }
                if (privateCall == null || thermalStreamActive()) {
                    stopStreaming("Stopped automatically: the phone reached critical thermal stress.")
                }
            }
            status < previousStatus && videoActive && thermalAdaptationApplied -> {
                thermalHandler.removeCallbacks(thermalRecoveryRunnable)
                thermalHandler.postDelayed(thermalRecoveryRunnable, THERMAL_RECOVERY_DELAY_MS)
            }
            ThermalGuardPolicy.shouldReduceWorkload(
                status,
                thermalReductionOn,
            ) && videoActive && activeStreamSettings != null -> {
                thermalHandler.removeCallbacks(thermalRecoveryRunnable)
                val percent = applyThermalAdaptation(status) ?: 100
                setStatus(
                    "Automatic cooling: FPS and bitrate are limited to $percent% of your selections.",
                )
            }
            else -> {
                if (status >= PowerManager.THERMAL_STATUS_MODERATE && videoActive) {
                    setStatus(
                        if (!thermalReductionOn) {
                            "Phone is hot; automatic cooling is off."
                        } else {
                            "Phone is hot; this device has no active camera encoder to reduce."
                        },
                    )
                }
                updateStartEnabled()
            }
        }
    }
    private val settingsBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when (appScreen) {
                AppScreen.SETTINGS -> settingsBack()
                AppScreen.CAMERA -> showReceiverBrowser()
                // The Live tab is the root: back leaves the app as it did from Home.
                AppScreen.RECEIVER_BROWSER -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
                AppScreen.PLAYBACK -> showReceiverBrowser()
                AppScreen.CALL_BROWSER -> showReceiverBrowser()
                AppScreen.CALL -> endPrivateCall()
                // The People tab is the idle screen with the contacts showing: back returns to Live.
                AppScreen.HOME -> if (callsTabScreen.visibility == View.VISIBLE) showReceiverBrowser()
            }
        }
    }
    private val benchmarkFrames: Int by lazy {
        if (BuildConfig.DEBUG) intent.getIntExtra("benchmark_frames", 0).coerceAtLeast(0) else 0
    }
    private val requestedTransportExperiment: String? by lazy {
        intent.getStringExtra("test_experiment")?.lowercase()?.takeIf { BuildConfig.DEBUG }
    }
    private val debugPrivateCallBrowser: Boolean by lazy {
        BuildConfig.DEBUG && intent.getBooleanExtra("test_private_call_browser", false)
    }
    private var transportExperimentPending = true
    private val debugLaunchRequested: Boolean by lazy {
        BuildConfig.DEBUG &&
            (intent.getBooleanExtra("test_auto_start", false) || debugAutoBroadcastRequested ||
                debugAutoSelectStream || debugPrivateCallBrowser)
    }
    private var debugAutoStartPending = false

    /** Audio live -> Switch to video: the new video live starts as soon as the camera screen is ready to go live. */
    private var pendingVideoSwitch = false
    private var afterCameraPermission: (() -> Unit)? = null

    /** The call media request in flight: what the user tapped, and its voice form when it has one (UI-01). */
    private class CallMediaAsk(val use: CallMediaAccess.Use, val video: Boolean, val voiceInstead: (() -> Boolean)?)
    private var callMediaAsk: CallMediaAsk? = null
    private val videoCameraPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val action = afterCameraPermission
        afterCameraPermission = null
        val ask = callMediaAsk
        callMediaAsk = null
        if (ask != null) onCallMediaAnswered(ask, action)
    }

    private fun requestCallMedia(
        video: Boolean,
        use: CallMediaAccess.Use,
        voiceInstead: (() -> Boolean)? = null,
        action: () -> Unit,
    ): Boolean {
        if (!DebugTunables.callReadinessPermissions) return false
        val missing = RuntimePermissionPlan.required(Build.VERSION.SDK_INT, true, video, true).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return false
        afterCameraPermission = action
        callMediaAsk = CallMediaAsk(use, video, voiceInstead)
        videoCameraPermission.launch(missing.toTypedArray())
        return true
    }

    /**
     * The answer to a call's camera/microphone request (UI audit 2026-11-10, UI-01; see CallMediaAccess). The state is
     * read from the system, not from the result map (an interrupted request returns an empty one). A refusal is always
     * said; a refused camera alone turns the call into a voice call (control arm
     * `test_call_voice_when_camera_refused=false`: it is only said).
     */
    private fun onCallMediaAnswered(ask: CallMediaAsk, action: (() -> Unit)?) {
        fun access(permissions: List<String>): CallMediaAccess.Access {
            val refused = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            return CallMediaAccess.Access(granted = refused.isEmpty(), canAskAgain = refused.all { shouldShowRequestPermissionRationale(it) })
        }
        val media = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val outcome = CallMediaAccess.decide(
            use = ask.use,
            video = ask.video,
            camera = access(listOf(Manifest.permission.CAMERA)),
            microphone = access(listOf(Manifest.permission.RECORD_AUDIO)),
            nearby = access(RuntimePermissionPlan.required(Build.VERSION.SDK_INT, true, ask.video, true) - media),
            voiceCallsOn = IntercomFeatures.current.voiceCalls,
            voiceWhenCameraRefused = DebugTunables.callVoiceWhenCameraRefused && ask.voiceInstead != null,
        )
        if (outcome.step == CallMediaAccess.Step.PROCEED) {
            action?.invoke()
            return
        }
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                CallMediaAccess.line(ask.use, ask.video, outcome, DebugTunables.callVoiceWhenCameraRefused),
        )
        if (outcome.step == CallMediaAccess.Step.VOICE_INSTEAD) {
            // The voice form checks that its call is still there (a ringing call may have ended behind the prompt).
            if (ask.voiceInstead?.invoke() == true) {
                // Shown like the heat notice (a video call that goes on as voice): status line and toast, nothing over the call buttons.
                val text = getString(
                    if (ask.use == CallMediaAccess.Use.ANSWER) R.string.call_answered_voice_camera_off
                    else R.string.call_placed_voice_camera_off,
                )
                setCallStatus(text)
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            }
            return
        }
        val names = outcome.missing.map { need ->
            getString(
                when (need) {
                    CallMediaAccess.Need.NEARBY ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.permission_name_nearby else R.string.permission_name_location
                    CallMediaAccess.Need.CAMERA -> R.string.permission_name_camera
                    CallMediaAccess.Need.MICROPHONE -> R.string.permission_name_microphone
                },
            )
        }
        showCallMediaRefusal(
            getString(
                when (ask.use) {
                    CallMediaAccess.Use.ANSWER -> R.string.call_media_needed_answer
                    CallMediaAccess.Use.PLACE -> R.string.call_media_needed_place
                    CallMediaAccess.Use.CAMERA_ON -> R.string.call_media_needed_camera_on
                },
                MissingPermissions.join(names, getString(R.string.list_separator), getString(R.string.list_last_separator)),
            ),
            outcome.openSettings,
        )
    }

    /**
     * What a call is missing, as a snackbar (with "Open settings" when the system no longer asks). On the call screen
     * it sits above the call buttons, never over Accept or Decline, and the call's status line says the same.
     */
    private fun showCallMediaRefusal(text: String, openSettings: Boolean) {
        if (isFinishing || isDestroyed) return
        val bar = com.google.android.material.snackbar.Snackbar.make(
            findViewById(android.R.id.content), text, com.google.android.material.snackbar.Snackbar.LENGTH_LONG,
        )
        if (appScreen == AppScreen.CALL) {
            showCallStatusText(text)
            listOf(incomingCallControls, activeCallControls, cancelCallButton)
                .firstOrNull { it.visibility == View.VISIBLE }?.let { bar.setAnchorView(it) }
        }
        if (openSettings) {
            bar.setAction(R.string.action_open_settings) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
            }
        }
        bar.show()
    }

    private fun acceptPrivateCall() {
        val endpoint = privateCall ?: return
        if (endpoint.localCallType == CallType.VIDEO && !ThermalGuardPolicy.callVideoAllowed(thermalStatus)) {
            // Calls at critical heat (DECISIONS 2026-11-06): answer as voice; the camera is not asked for or started.
            endpoint.answerWithoutVideo()
            showCallHeatNotice("answer")
        }
        if (requestCallMedia(
                endpoint.localCallType == CallType.VIDEO,
                CallMediaAccess.Use.ANSWER,
                voiceInstead = {
                    // Camera refused (`test_call_voice_when_camera_refused`, on): the camera-off answer, as at critical heat.
                    val ringing = privateCall === endpoint && privateCallPhase == PrivateCallPhase.INCOMING_RINGING
                    if (ringing) {
                        endpoint.answerWithoutVideo()
                        runCatching { acceptPrivateCall() }.onFailure { setCallStatus(it.message ?: "Could not accept") }
                    }
                    ringing
                },
            ) {
                if (privateCall === endpoint && privateCallPhase == PrivateCallPhase.INCOMING_RINGING) {
                    runCatching { acceptPrivateCall() }.onFailure { setCallStatus(it.message ?: "Could not accept") }
                }
            }) return
        endpoint.accept()
    }

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        startPermissionsAsked = true
        if (hasRequiredPermissions()) {
            ensureBluetoothEnabledAndRefresh()
        } else {
            setStatus(missingPermissionsText())
            updateStartEnabled()
        }
    }
    private val bluetoothEnableRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (bluetoothAdapter?.isEnabled == true) {
            onPermissionsReady()
        } else {
            setStatus(getString(R.string.status_line_bluetooth_off))
            updateStartEnabled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startupTimer = StartupTimer(SystemClock::elapsedRealtime)
        // Another app's unknown Parcelable extra must not crash the exported launcher activity (SafeLaunchExtras).
        intent?.let { setIntent(SafeLaunchExtras.sanitize(it)) }
        super.onCreate(savedInstanceState)
        startupTimer.mark("super")
        CallListenerViewing.probe = viewingProbe
        if (isColdStartShutdown(BuildConfig.DEBUG, savedInstanceState != null, intent.getStringExtra("test_control"))) {
            // A runner's shutdown control reached an app that had already exited, so Android started
            // a fresh process for it (graceful_stop's am start; a new ASUS process after cleanup
            // quarantined phones in bmaraw-ab-call-9c121b30-run1 and calls-noloss-m31asus-1756e738
            // 0008). Leave without opening Bluetooth, camera or services, and end the process so
            // cleanup finds none.
            Log.i("BluetoothVideo", "BENCHMARK control shutdown applied: cold start exits")
            finishAndRemoveTask()
            android.os.Process.killProcess(android.os.Process.myPid())
            return
        }
        if (EmergencyWipe.isPending(this)) {
            val resumed = EmergencyWipe.resumePendingWipe(this)
            if (!resumed.complete) {
                // An interrupted wipe still cannot finish: start no transport, key or store, and say so.
                Log.w("BluetoothVideo", "EMERGENCY_WIPE pending and incomplete: showing the wipe-incomplete screen")
                startupAborted = true
                startActivity(WipeIncompleteActivity.intent(this, resumed))
                finish()
                return
            }
        }
        if (debugEmergencyWipeAuthorized(intent)) {
            // DEBUG-only control: wipe before any key, store or radio opens; the process then ends.
            finishAndRemoveTask()
            EmergencyWipe.wipe(this, WipeReason.DEBUG_CONTROL)
            return
        }
        if (BuildConfig.DEBUG && intent.hasExtra("test_crash_reports")) {
            CrashReports.debugDisabled = !intent.getBooleanExtra("test_crash_reports", true)
        }
        val extraKeys = runCatching { intent.extras?.keySet()?.toSet() }.getOrNull().orEmpty()
        if (CrashReports.shouldPrompt(CrashReports.enabled(this), CrashReports.pending(CrashReports.dir(this)) != null, extraKeys)) {
            // The previous process crashed: ask before opening any radio, camera or service, so a crash in this start
            // cannot hide the prompt. CrashReportActivity continues to this screen whatever the answer.
            startupAborted = true
            startActivity(CrashReportActivity.intent(this))
            finish()
            return
        }
        if (EncoderBenchLaunch.requested(intent)) {
            // DEBUG radio-free encoder bench (test_encoder_bench, default off): hand the launch to
            // EncoderBenchActivity before any view, service or Bluetooth object exists, and end this one
            // (the same early exit as the wipe-incomplete screen above).
            startupAborted = true
            Log.i("BluetoothVideo", "BENCHMARK encoder_bench: hand over to EncoderBenchActivity | MainActivity opens no radio")
            EncoderBenchLaunch.forward(this, intent)
            finish()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        startupTimer.mark("layout")
        if (BuildConfig.DEBUG) {
            // Exported for the shell (test drivers); DEBUG builds only.
            debugCleanShutdownRegistered = runCatching {
                ContextCompat.registerReceiver(
                    this, debugCleanShutdownReceiver, IntentFilter(CleanShutdown.ACTION), ContextCompat.RECEIVER_EXPORTED,
                )
            }.isSuccess
        }
        LinkCapacity.attach(this, bluetoothAdapter)
        if (BuildConfig.DEBUG) {
            // Design QV gate W2: dial one exact address through the production GATT client, then close (DebugGattDial).
            DebugGattDial.request(
                intent.getStringExtra(DebugGattDial.EXTRA_ADDRESS),
                intent.getStringExtra(DebugGattDial.EXTRA_SERVICE),
                intent.getStringExtra(DebugGattDial.EXTRA_ADDRESS_TYPE),
            )?.let { DebugGattDial.start(this, bluetoothAdapter, it) }
        }
        DeviceTraits.attachBluetoothAdapter(bluetoothAdapter)
        // Read-only runtime traits (ROM version properties, ABIs, memory, Bluetooth package versions); idempotent.
        DeviceTraitsProbe.attach(applicationContext)
        DebugTunables.devicePolicyOff = parseDevicePolicySwitches(intent.getStringExtra("test_device_policy_off"))
        // DEBUG harness for the rotating-ID phone tests (DebugContactControl): day-roll clock offset (cold start only).
        DebugContactControl.applyDayOffset(this, intent)
        // DEBUG harness: shorter rotating-ID epoch (test_rotid_epoch_s=60..900), set once before RotatingDiscovery.init.
        DebugContactControl.applyEpochLength(this, intent)
        // Verified contacts are checked by every end-to-end handshake; this opens no key until one exists.
        IdentityVerification.attach(this)
        PersonPolicy.ensureInstalled(this) // the block list for calls, reveals, streams, exchanges and leases
        // After a pending emergency wipe was completed above: an earlier build's plain pin file is sealed and deleted.
        runCatching { Bmh3Android.migrateLegacyPins(this) }
        // DEBUG harness: one-shot token-gated verify/forget of a contact; the identity line the harness reads.
        DebugContactControl.handleIntent(this, intent)
        DebugContactControl.logOwnIdentity(this)
        EmergencyWipe.registerSessionTerminator(emergencyWipeTerminator)
        runCatching { AppShare.cleanupExpired(this) } // Shared APK copies live at most AppSharePolicy.COPY_TTL_MS.
        runCatching { DozeWatchdog.attach(this) } // Observes and logs Doze/standby only (DozeWatchdog).
        if (BuildConfig.DEBUG) BluetoothRestart.busyProbe = bluetoothRestartProbe
        DebugTunables.sourceGattPriority = debugSourceGattPriority
        DebugTunables.encoderRateTrim = intent.getBooleanExtra("test_encoder_rate_trim", true)
        DebugTunables.encoderBitrateAdjuster = intent.getBooleanExtra("test_encoder_bitrate_adjuster", true)
        DebugTunables.encoderFramerateAdjuster = intent.getBooleanExtra("test_encoder_framerate_adjuster", false)
        DebugTunables.benchmarkLaunch = benchmarkExtrasPresent()
        // Every run states which test_ extras reached the app, so an A/B can tell an arm that never arrived.
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", LaunchExtrasEcho.line(intent.extras))
        // The display's refresh rate: the scorer's smoothness tolerance is half a refresh period.
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", DisplayRefreshLine.line(this))
        // A requested arm a parent switch masks changes nothing: say so, so an A/B cannot compare two identical arms.
        if (BuildConfig.DEBUG) SwitchInert.inert(intent.extras).forEach { Log.i("BluetoothVideo", it.line()) }
        UpdateRuntime.readDebugExtras(intent)
        DebugTunables.linkPacing = intent.getBooleanExtra("test_link_pacing", true)
        DebugTunables.playBatteryGuidance = intent.getBooleanExtra("test_play_battery_guidance", true)
        DebugTunables.localReporting = intent.getBooleanExtra("test_local_reporting", true)
        DebugTunables.nearbyListUx = intent.getBooleanExtra("test_nearby_list_ux", true)
        DebugTunables.strangerCallFollowup = intent.getBooleanExtra("test_stranger_call_followup", true)
        DebugTunables.completeRecentCalls = intent.getBooleanExtra("test_complete_recent_calls", true)
        DebugTunables.fullScreenCallGuidance = intent.getBooleanExtra("test_full_screen_call_guidance", true)
        DebugTunables.callReadinessPermissions = intent.getBooleanExtra("test_call_readiness_permissions", true)
        DebugTunables.honestCallStatus = intent.getBooleanExtra("test_honest_call_status", true)
        DebugTunables.scanRecovery = intent.getBooleanExtra("test_scan_recovery", true)
        DebugTunables.scanLe1mOnly = intent.getBooleanExtra("test_scan_le_1m_only", true)
        DebugTunables.meetSoftwareFilter = intent.getBooleanExtra("test_meet_software_filter", true)
        DebugTunables.callListSoftwareFilter = intent.getBooleanExtra("test_call_list_software_filter", false)
        DebugTunables.callStallTolerance = intent.getBooleanExtra("test_call_stall_tolerance", false)
        DebugTunables.callLivenessHangUp = intent.getBooleanExtra("test_call_liveness_hang_up", true)
        DebugTunables.callRingTimeout = intent.getBooleanExtra("test_call_ring_timeout", true)
        DebugTunables.remoteVideoFit = intent.getBooleanExtra("test_remote_video_fit", true)
        DebugTunables.skipRedundantPriority = intent.getBooleanExtra("test_skip_redundant_priority", false)
        DebugTunables.streamJoinRetry = intent.getBooleanExtra("test_stream_join_retry", true)
        DebugTunables.streamSearchBound = intent.getBooleanExtra("test_stream_search_bound", true)
        DebugTunables.calleeNameAfterReveal = intent.getBooleanExtra("test_callee_name_after_reveal", true)
        DebugTunables.viewerAdmissionBounds = intent.getBooleanExtra("test_viewer_admission_bounds", true)
        DebugTunables.sourceMediaPrewarm = intent.getBooleanExtra("test_source_media_prewarm", true)
        DebugTunables.callPrewarm = intent.getBooleanExtra("test_call_prewarm", false)
        DebugTunables.callCameraFirst = intent.getBooleanExtra("test_call_camera_first", true)
        DebugTunables.dialRecentSighting = intent.getBooleanExtra("test_dial_recent_sighting", false)
        DebugTunables.relayJoinKeyFrame = intent.getBooleanExtra("test_relay_join_key_frame", false)
        DebugTunables.fastEstablishDials = intent.getBooleanExtra("test_fast_0x3e_dials", false)
        DebugTunables.dropLeadingBlankFrames = intent.getBooleanExtra("test_drop_leading_blank_frames", false)
        DebugTunables.injectBlankFrames = intent.getIntExtra("test_inject_blank_frames", 0)
        DebugTunables.gattGapFastFail = intent.getBooleanExtra("test_gatt_gap_fast_fail", true)
        DebugTunables.gattChunkRecovery = intent.getBooleanExtra("test_gatt_chunk_recovery", true)
        DebugTunables.gattDropReceivedEvery = intent.getIntExtra("test_gatt_drop_received_every", 0)
        DialPersonMatch.enabled = intent.getBooleanExtra("test_dial_follows_rotation", true)
        ScanRestartHint.enabled = intent.getBooleanExtra("test_scan_restart_hint", true)
        CopiedIdOverlapRule.enabled = intent.getBooleanExtra("test_copied_id_overlap", true)
        CopiedIdPcPair.enabled = intent.getBooleanExtra("test_copied_id_pc_pair", true)
        DebugTunables.weakPairReach = intent.getBooleanExtra("test_weak_pair_reach", true)
        DebugTunables.callLinkShare = intent.getBooleanExtra("test_call_link_share", false)
        DebugTunables.wifiCarrier = intent.getBooleanExtra("test_wifi_carrier", false)
        DebugTunables.wifiWiderReach = intent.getBooleanExtra("test_wifi_wider_reach", false)
        DebugTunables.wifiDirect = intent.getBooleanExtra("test_wifi_direct", false)
        DebugTunables.wifiIcm = intent.getBooleanExtra("test_wifi_icm", false)
        DebugTunables.wifiAllow24ghz = intent.getBooleanExtra("test_wifi_allow_24ghz", false)
        DebugTunables.wifiShareAttach = intent.getBooleanExtra("test_wifi_share_attach", false)
        DebugTunables.wifiAwareDerivedPort = intent.getBooleanExtra("test_wifi_aware_derived_port", true)
        DebugTunables.wifiAwareRequireChannel = intent.getBooleanExtra("test_wifi_aware_require_channel", false)
        DebugTunables.awareBandStrict = intent.getBooleanExtra("test_aware_band_strict", false)
        DebugTunables.aware24GhzAlone = intent.getBooleanExtra("test_aware_24ghz_alone", false)
        DebugTunables.awareGoodputGuard = intent.getBooleanExtra("test_aware_goodput_guard", false)
        DebugTunables.wifiProbe = intent.getBooleanExtra("test_wifi_probe", false)
        DebugTunables.wifiForceCarrier = intent.getStringExtra("test_wifi_force_carrier").orEmpty()
        DebugTunables.wifiExactKbps = intent.getIntExtra("test_wifi_exact_kbps", 0)
        DebugTunables.wifiBleStandbyLowPower = intent.getBooleanExtra("test_wifi_ble_standby_low_power", false)
        DebugTunables.callFpsFollow = intent.getBooleanExtra("test_call_fps_follow", false)
        DebugTunables.reachableScanYield = intent.getBooleanExtra("test_reachable_scan_yield", true)
        DebugTunables.bluetoothOnRecovery = intent.getBooleanExtra("test_bluetooth_on_recovery", true)
        DebugTunables.callHandshakeDeadlineAll = intent.getBooleanExtra("test_call_handshake_deadline_all", true)
        DebugTunables.callerAnswerDeadline = intent.getBooleanExtra("test_caller_answer_deadline", true)
        DebugTunables.callPreauthDeadlineShort = intent.getBooleanExtra("test_call_preauth_deadline_short", false)
        DebugTunables.callPreInviteDeadline = intent.getBooleanExtra("test_call_pre_invite_deadline", false)
        DebugTunables.cocAttachSingle = intent.getBooleanExtra("test_coc_attach_single", true)
        DebugTunables.fastFailRedialAll = intent.getBooleanExtra("test_fast_fail_redial_all", false)
        DebugTunables.fastFirstRedial = intent.getBooleanExtra("test_fast_first_redial", true)
        DebugTunables.callAdvIdleFast = intent.getBooleanExtra("test_call_adv_idle_fast", true)
        DebugTunables.callsIndicatorSettles = intent.getBooleanExtra("test_calls_indicator_settles", true)
        DebugTunables.stallRetryOwnBudget = intent.getBooleanExtra("test_stall_retry_own_budget", false)
        DebugTunables.setupStallMs = intent.getIntExtra("test_setup_stall_ms", 0).toLong()
        DebugTunables.stallSlowAuthMs = intent.getIntExtra("test_stall_slow_auth_ms", 0).toLong()
        DebugTunables.relayedBudgetFromHopAuth = intent.getBooleanExtra("test_relayed_budget_from_hop_auth", false)
        DebugTunables.unifiedConnectPolicy = intent.getBooleanExtra("test_unified_connect_policy", false)
        DebugTunables.linkPacerSeed = intent.getBooleanExtra("test_link_pacer_seed", true)
        DebugTunables.experimentalPeerLinkSeed = intent.getBooleanExtra("test_link_pacer_peer_seed", false)
        DebugTunables.callLinkPacing = intent.getBooleanExtra("test_call_link_pacing", false)
        DebugTunables.classicPageRandomAddress = intent.getBooleanExtra("test_classic_page_random_address", false)
        DebugTunables.fanOutEarlyRelease = intent.getBooleanExtra("test_fanout_early_release", true)
        DebugTunables.playbackGain = intent.getFloatExtra("test_playback_gain", -1f).takeIf { it >= 0f }
        MemoryThermalPolicy.headroomForecast = intent.getBooleanExtra("test_thermal_forecast", false)
        MemoryThermalPolicy.speedFloor = intent.getBooleanExtra("test_thermal_speed_floor", false)
        // Device-family batches (integration 2026-10-14): each batch's launch switches. Every non-default arm is
        // DEBUG-only (the setters ignore it in release) and listed in SWITCHES; each launch starts from the defaults.
        ThermalSwitches.applyLaunchExtras(intent)
        Log.i("BluetoothVideo", ThermalSwitches.benchmarkLine())
        AudioSwitches.applyLaunchExtras(intent)
        ScanSwitches.applyLaunchExtras(intent)
        ServiceSwitches.applyLaunchExtras(intent, this)
        GattSwitches.applyLaunchExtras(intent)
        DecoderSwitches.applyLaunchExtras(intent)
        CutoverSwitches.applyLaunchExtras(intent)
        CocSwitches.applyLaunchExtras(intent)
        CameraSwitches.applyLaunchExtras(intent) // after DebugTunables.benchmarkLaunch (encoder-init fallback is user sessions only)
        BackgroundSwitches.applyLaunchExtras(intent)
        AdvertisingSwitches.applyLaunchExtras(intent)
        PhySwitches.applyLaunchExtras(intent)
        // The boot receiver follows the stored opt-in (default off = disabled). A PackageManager write (a binder call that
        // can take hundreds of ms): off the main thread, same result (UX fix 2026-10-21, startup jank).
        val appContextForBoot = applicationContext
        thread(name = "BootResumeSync", isDaemon = true) { BootResume.syncComponent(appContextForBoot) }
        startupTimer.mark("extras")
        Log.i(
            "BluetoothVideo",
            "BENCHMARK family_switches: gatt=${GattSwitches.describeNonDefaults()} | coc=${CocSwitches.label()} | " +
                "audio=${AudioSwitches.describe()} | decoder=${DecoderSwitches.describe()} | cutover=${CutoverSwitches.describe()}",
        )
        MemoryThermalPolicy.configureFromSystem(this)?.let { Log.i("BluetoothVideo", it) }
        if (ThermalSwitches.simdSafeMode) {
            // Before the first libaom initialisation: a reduced SIMD mask is applied when the previous process crashed at init.
            val simdStore = NativeSimdSafeMode.FileStore(filesDir)
            val simdDecision = NativeSimdSafeMode.prepare(
                simdStore,
                true,
                Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a",
                { marker -> NativeSimdSafeMode.platformExitFor(this, marker) },
            )
            Log.i(
                "BluetoothVideo",
                NativeSimdSafeMode.logLine(simdDecision, simdStore.readState(), NativeSimdSafeMode.safeModeActive(simdDecision)),
            )
        }
        Log.i("BluetoothVideo", Userspace32Tier.logLine(ThermalSwitches.userspace32Tier))
        Log.i("BluetoothVideo", NativeCompat.currentReportLine())
        DebugTunables.callLadderFromRequest = intent.getBooleanExtra("test_call_ladder_from_request", false)
        // Measured Auto ladder (experimental, default off, DEBUG only): see AutoQualityLadder.
        AutoQualityFeature.enabled = FeatureCatalog.resolve(
            AutoQualityFeature.spec, benchmarkExtrasPresent(), ::launchExtra, ::storedFeatureChoice,
        )
        AutoQualityFeature.encoderClassOverride = if (BuildConfig.DEBUG) {
            AutoQualityFeature.parseClass(intent.getStringExtra(AutoQualityFeature.CLASS_EXTRA))
        } else {
            null
        }
        if (BuildConfig.DEBUG) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK auto_ladder: enabled=${AutoQualityFeature.enabled} | " +
                    "class=${AutoQualityFeature.encoderClassOverride ?: "standard"}",
            )
        }
        // GPU scale ladder (experimental, default off, DEBUG only): see GpuScaleMath.
        GpuScaleFeature.enabled = FeatureCatalog.resolve(
            GpuScaleFeature.spec, benchmarkExtrasPresent(), ::launchExtra, ::storedFeatureChoice,
        )
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK gpu_scale: enabled=${GpuScaleFeature.enabled}")
        // DEBUG: the app creates its own external files folder, so benchmark inputs pushed over adb land in an
        // app-owned folder. A folder created by `adb push` is owned by shell: the Android 16 A55 then read a full raw
        // fixture as 0 bytes (2026-10-16), and HyperOS refuses the runner's `run-as mkdir` fallback.
        if (BuildConfig.DEBUG) getExternalFilesDir(null)
        DebugTunables.deliveryRateControl = intent.getBooleanExtra("test_delivery_rate_control", false)
        DebugTunables.deliveryReports = intent.getBooleanExtra("test_delivery_reports", true)
        DebugTunables.onTimeReports = intent.getBooleanExtra("test_on_time_reports", true)
        DebugTunables.onTimeSeedRaise = intent.getBooleanExtra("test_on_time_seed_raise", true)
        DebugTunables.receiverNodeBaselineP95 = intent.getBooleanExtra("test_receiver_node_baseline_p95", false)
        DebugTunables.joinProbeSeed = intent.getBooleanExtra("test_join_probe_seed", false)
        DebugTunables.seedRaiseDrainGuard = intent.getBooleanExtra("test_seed_raise_drain_guard", false)
        DebugTunables.relayEstablishKeep2M = intent.getBooleanExtra("test_relay_establish_keep_2m", false)
        DebugTunables.streamDeliveryReports = intent.getBooleanExtra("test_stream_delivery_reports", false)
        DebugTunables.relayChildThinning = intent.getBooleanExtra("test_relay_child_thinning", false)
        if (BuildConfig.DEBUG) {
            Log.i("BluetoothVideo", "BENCHMARK relay_child_thinning: enabled=${DebugTunables.relayChildThinning}")
        }
        DebugTunables.startRateGuard = intent.getBooleanExtra("test_start_rate_guard", true)
        DebugTunables.callNativeOpusFec = intent.getBooleanExtra("test_call_native_opus_fec", true)
        DebugTunables.callNativeOpusLossPercent =
            intent.getIntExtra("test_call_native_opus_loss_pct", NativeOpusSettings.DEFAULT_LOSS_PERCENT)
        DebugTunables.callNativeOpusDtx = intent.getBooleanExtra("test_call_native_opus_dtx", false)
        if (BuildConfig.DEBUG) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK rate_control_policy: deliveryReports=${DebugTunables.deliveryReports} | " +
                    "deliveryProbing=${DebugTunables.deliveryRateControl} | " +
                    "streamDeliveryReports=${DebugTunables.streamDeliveryReports} | " +
                    "startRateGuard=${startRateGuardOn()} | " +
                    "bitrateAdjuster=${DebugTunables.encoderBitrateAdjuster}",
            )
            // A/B arms start from the same learned start rates (24 h, per peer), and every launch
            // records what it starts from.
            if (intent.getBooleanExtra("test_reset_learned_rates", false)) {
                Log.i("BluetoothVideo", "BENCHMARK learned_rates: reset | cleared=${LinkCapacity.clearSustainedRates()}")
            }
            Log.i("BluetoothVideo", "BENCHMARK learned_rates: ${LinkCapacity.sustainedRatesSummary()}")
            // Learned limits (ceilings, bad-2M marks) persist across runs and builds: record them on
            // every launch, and let A/B arms start clean (mesh4-b9726176-loop4-1: stale M31 relay and
            // GATT fan-out ceilings and an ASUS bad-2M mark refused four viewers).
            DebugTunables.ceilingProbeRecovery = intent.getBooleanExtra("test_ceiling_probe_recovery", false)
            if (intent.getBooleanExtra("test_reset_learned_state", false)) {
                Log.i("BluetoothVideo", "BENCHMARK learned_state: reset | cleared=${LinkCapacity.resetLearnedState()}")
            }
            Log.i(
                "BluetoothVideo",
                "BENCHMARK learned_state: ${LinkCapacity.learnedStateSummary()} | " +
                    "ceilingProbeRecovery=${DebugTunables.ceilingProbeRecovery}",
            )
        }
        DebugTunables.experimentalBoundedMediaAge = intent.getBooleanExtra("test_bounded_media_age", false)
        applyRelayChildMediaAge()
        DebugTunables.callVideoFragments = intent.getBooleanExtra("test_call_video_fragments", false)
        DebugTunables.callFragmentAirtimeMs = intent.getIntExtra("test_call_fragment_airtime_ms", 0)
        DebugTunables.callSpeechLedPlayout = intent.getBooleanExtra("test_call_speech_led_playout", false)
        DebugTunables.callHeadOfLineMode = CallHeadOfLine.Mode.fromValue(intent.getStringExtra(CallHeadOfLine.EXTRA))
        DebugTunables.callAudioHeadroom = intent.getBooleanExtra("test_call_audio_headroom", true)
        DebugTunables.callCodecFallback = intent.getBooleanExtra("test_call_codec_fallback", true)
        DebugTunables.callLegacyLowDelay = intent.getBooleanExtra("test_call_legacy_low_delay", false)
        DebugTunables.callAudioCoalesce = intent.getBooleanExtra("test_call_audio_coalesce", true)
        DebugTunables.callAudioPairs = intent.getBooleanExtra("test_call_audio_pairs", false)
        DebugTunables.aeadCipherReuse = intent.getBooleanExtra("test_aead_cipher_reuse", false)
        DebugTunables.streamAudioBatchFrames = intent.getIntExtra("test_stream_audio_batch_frames", 2)
        DebugTunables.callLegacyRelief = intent.getBooleanExtra("test_call_legacy_relief", false)
        applyCallUxFeatures()
        registerCallNotificationReceiver()
        NotificationPrivacy.debugPeerNames = if (intent.hasExtra("test_lock_screen_peer_names")) {
            intent.getBooleanExtra("test_lock_screen_peer_names", false)
        } else {
            null
        }
        DebugTunables.callFirstFrameNow = intent.getBooleanExtra("test_call_first_frame_now", false)
        DebugTunables.callFirstFrameRamp = intent.getBooleanExtra("test_call_first_frame_ramp", true)
        DebugTunables.callVoiceWhenCameraRefused = intent.getBooleanExtra("test_call_voice_when_camera_refused", true)
        DebugTunables.callQuietPreRingFailure = intent.getBooleanExtra(
            "test_call_quiet_pre_ring_failure", DebugTunables.CALL_QUIET_PRE_RING_FAILURE_DEFAULT,
        )
        DebugTunables.callEncoderFit = intent.getBooleanExtra("test_call_encoder_fit", false)
        DebugTunables.swShortfallAvc = intent.getBooleanExtra("test_sw_shortfall_avc", false)
        if (BuildConfig.DEBUG) {
            // AP5 test_sw_shortfall_avc: the remembered software-encoder shortfalls are learned state (a reset A/B arm
            // starts without them); every launch records the switch and what it starts from.
            val shortfallsCleared = if (intent.getBooleanExtra("test_reset_learned_state", false)) SoftwareShortfallStore.clear(this) else 0
            Log.i(
                "BluetoothVideo",
                "BENCHMARK sw_shortfall_policy: on=${DebugTunables.swShortfallAvc} | cleared=$shortfallsCleared | " +
                    "memory=${SoftwareShortfallStore.summary(this)}",
            )
        }
        DebugTunables.callRelayChain = intent.getBooleanExtra("test_call_relay_chain", true)
        DebugTunables.relayedCallMeasuredCap = intent.getBooleanExtra("test_relayed_call_measured_cap", false)
        DebugTunables.relayedFirstHopDirect = intent.getBooleanExtra("test_relayed_first_hop_direct", true)
        DebugTunables.relayedHopRetry = intent.getBooleanExtra("test_relayed_hop_retry", true)
        DebugTunables.relayedHop1m = intent.getBooleanExtra("test_relayed_hop_1m", false)
        DebugTunables.relayedCandidates = intent.getBooleanExtra("test_relayed_candidates", true)
        DebugTunables.relayedHopPhyRetry = intent.getBooleanExtra("test_relayed_hop_phy_retry", true)
        DebugTunables.relayStartHopGuard = intent.getBooleanExtra("test_relay_start_hop_guard", false)
        DebugTunables.relayedPseudonymousEdges = intent.getBooleanExtra("test_relayed_pseudonymous_edges", true)
        DebugTunables.relayedStrangerCalls = intent.getBooleanExtra("test_relayed_stranger_calls", true)
        DebugTunables.relayedContactHelper = intent.getBooleanExtra("test_relayed_contact_helper", false)
        DebugTunables.callCloseWaitsForPeer = intent.getBooleanExtra("test_call_close_wait_peer", true)
        DebugTunables.relayChildReanchor = intent.getBooleanExtra("test_relay_child_reanchor", true)
        DebugTunables.relayChildStallGuard = intent.getBooleanExtra("test_relay_child_stall_guard", true)
        DebugTunables.relayStallHoldoff = intent.getBooleanExtra("test_relay_stall_holdoff", true)
        DebugTunables.relayStallSeparate = intent.getBooleanExtra("test_relay_stall_separate", true)
        DebugTunables.relayRefusalEscalation = intent.getBooleanExtra("test_relay_refusal_escalation", true)
        DebugTunables.relayHoldInPlace = intent.getBooleanExtra("test_relay_hold_in_place", true)
        DebugTunables.gattInputFailureEndsLink = intent.getBooleanExtra("test_gatt_input_failure_ends_link", true)
        DebugTunables.callLinkRenewal = intent.getBooleanExtra("test_call_link_renewal", true)
        DebugTunables.callTxStallAfterS = intent.getIntExtra("test_call_tx_stall_after_s", 0)
        DebugTunables.gattReregisterKeepAddress = intent.getBooleanExtra("test_gatt_reregister_keep_address", true)
        DebugTunables.gattFirstWriteCountsControl = intent.getBooleanExtra("test_gatt_first_write_counts_control", true)
        DebugTunables.hopIntegrityResendDiff = intent.getBooleanExtra("test_hop_integrity_resend_diff", false)
        DebugTunables.phyMoveFloodGuard = intent.getBooleanExtra("test_phy_move_flood_guard", true)
        DebugTunables.decoderSizeChangeRebuild = intent.getBooleanExtra("test_decoder_size_rebuild", true)
        DebugTunables.relayUpstreamDck = intent.getBooleanExtra("test_relay_upstream_dck", false)
        DebugTunables.streamRelayPassthrough = intent.getBooleanExtra("test_stream_relay_passthrough", debugRequireRelay)
        DebugTunables.relayRecoveryGrace = intent.getBooleanExtra("test_relay_recovery_grace", true)
        DebugTunables.relayParentTrickle = intent.getBooleanExtra("test_relay_parent_trickle", true)
        DebugTunables.viewerLinkSilence = intent.getBooleanExtra("test_viewer_link_silence", true)
        // B2: the child-side watch and same-parent preference, apart from relay_child_reanchor (absent: follow it).
        DebugTunables.relayParentWatch = if (intent.hasExtra("test_relay_parent_watch")) {
            intent.getBooleanExtra("test_relay_parent_watch", true)
        } else {
            null
        }
        DebugTunables.relayPreferParent = intent.getBooleanExtra("test_relay_prefer_parent", false)
        DebugTunables.relayPaceHeadOnly = intent.getBooleanExtra("test_relay_pace_head", true)
        DebugTunables.relayPaceLeadCap = intent.getBooleanExtra("test_relay_pace_lead_cap", false)
        // Time-based relay queue and viewer playout target (Q31; both off by default, DEBUG only).
        DebugTunables.relayTimeQueue = intent.getBooleanExtra("test_relay_time_queue", false)
        DebugTunables.playoutTimeTarget = intent.getBooleanExtra("test_playout_time_target", false)
        DebugTunables.relayDrainSustained = intent.getBooleanExtra("test_relay_drain_sustained", false)
        DebugTunables.timeQueueBudgetMs = intent.getIntExtra(
            "test_time_queue_budget_ms",
            TimeBasedSizing.DEFAULT_LATENCY_BUDGET_MS.toInt(),
        ).toLong()
        DebugTunables.timeQueueFloorMs = intent.getIntExtra(
            "test_time_queue_floor_ms",
            TimeBasedSizing.DEFAULT_FLOOR_MS.toInt(),
        ).toLong()
        // B12: the viewer's playout budget alone (absent: test_time_queue_budget_ms sets both, as before).
        DebugTunables.playoutTimeBudgetMs = if (intent.hasExtra("test_playout_time_budget_ms")) {
            intent.getIntExtra("test_playout_time_budget_ms", TimeBasedSizing.DEFAULT_LATENCY_BUDGET_MS.toInt()).toLong()
        } else {
            null
        }
        if (BuildConfig.DEBUG) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK time_queue_arms: relay_time_queue=${DebugTunables.relayTimeQueue} | " +
                    "relay_drain_sustained=${DebugTunables.relayDrainSustained} | " +
                    "playout_time_target=${DebugTunables.playoutTimeTarget} | " +
                    "budgetMs=${DebugTunables.timeQueueBudgetMs} | floorMs=${DebugTunables.timeQueueFloorMs} | " +
                    "relay_pace_lead_cap=${DebugTunables.relayPaceLeadCap} | " +
                    "playoutBudgetMs=${TimeBasedSizing.playoutBudgetMs()}",
            )
        }
        if (BuildConfig.DEBUG) {
            DebugTunables.inboundProbe = intent.getStringExtra("test_inbound_probe")
            DebugTunables.inboundProbeDials = intent.getIntExtra("test_inbound_probe_dials", -1).takeIf { it > 0 }
            DebugTunables.inboundProbeHoldMs = intent.getIntExtra("test_inbound_probe_hold_ms", -1).takeIf { it > 0 }
            DebugTunables.inboundProbeGapMs = intent.getIntExtra("test_inbound_probe_gap_ms", -1).takeIf { it >= 0 }
            DebugTunables.inboundProbeKbps = intent.getIntExtra("test_inbound_probe_kbps", -1).takeIf { it > 0 }
            InboundProbe.startIfRequested(applicationContext)
        }
        if (BuildConfig.DEBUG) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK call_policy: videoFragments=${DebugTunables.callVideoFragments} | " +
                    "fragmentAirtimeMs=${DebugTunables.callFragmentAirtimeMs} | " +
                    "speechLedPlayout=${DebugTunables.callSpeechLedPlayout} | " +
                    "headOfLine=${DebugTunables.callHeadOfLineMode?.value ?: if (DebugTunables.benchmarkLaunch) "extras" else "user_default"} | " +
                    "audioHeadroom=${DebugTunables.callAudioHeadroom} | codecFallback=${DebugTunables.callCodecFallback} | " +
                    "legacyLowDelay=${DebugTunables.callLegacyLowDelay} | legacyRelief=${DebugTunables.callLegacyRelief} | " +
                    "audioCoalesce=${DebugTunables.callAudioCoalesce} | audioPairs=${DebugTunables.callAudioPairs} | " +
                    "aeadCipherReuse=${DebugTunables.aeadCipherReuse} | " +
                    "streamAudioBatchFrames=${streamAudioBatchFrames(DebugTunables.streamAudioBatchFrames)} | " +
                    "lockScreen=${DebugTunables.callLockScreen} | firstFrameNow=${DebugTunables.callFirstFrameNow} | " +
                    "firstFrameRamp=${DebugTunables.callFirstFrameRamp} | " +
                    "encoderFit=${DebugTunables.callEncoderFit} | relayChain=${DebugTunables.callRelayChain} | " +
                    "relayedMeasuredCap=${DebugTunables.relayedCallMeasuredCap} | " +
                    "relayedFirstHopDirect=${DebugTunables.relayedFirstHopDirect} | " +
                    "relayedHopRetry=${DebugTunables.relayedHopRetry} | relayedHop1m=${DebugTunables.relayedHop1m} | " +
                    "relayedCandidates=${DebugTunables.relayedCandidates} | relayedHopPhyRetry=${DebugTunables.relayedHopPhyRetry} | " +
                    "relayStartHopGuard=${DebugTunables.relayStartHopGuard} | " +
                    "relayedPseudonymousEdges=${DebugTunables.relayedPseudonymousEdges} | " +
                    "relayedStrangerCalls=${DebugTunables.relayedStrangerCalls} | relayedContactHelper=${DebugTunables.relayedContactHelper} | " +
                    "closeWaitsForPeer=${DebugTunables.callCloseWaitsForPeer} | ${CallUx.label()}",
            )
        }
        if (BuildConfig.DEBUG) {
            Log.i("BluetoothVideo", "BENCHMARK media_age_policy: policy=${BoundedMediaAge.policyLabel()} | " +
                    "relayChild=${DebugTunables.experimentalRelayChildMediaAge}")
        }
        DebugTunables.hardwareQpMin = intent.getIntExtra("test_hw_qp_min", -1).takeIf { it >= 0 }
        DebugTunables.hardwareQpMax = intent.getIntExtra("test_hw_qp_max", -1).takeIf { it >= 0 }
        DebugTunables.bitrateModeOverride = intent.getIntExtra("test_bitrate_mode", -1).takeIf { it in 0..2 }
        DebugTunables.keyFrameShaping = intent.getFloatExtra("test_keyframe_shaping", -1f)
            .takeIf { it > 0f && it < 1f }?.toDouble()
        DebugTunables.hardwareTemporalLayers = intent.getBooleanExtra("test_hardware_temporal_layers", false)
        DualListeningFeature.inject = if (BuildConfig.DEBUG) intent.getStringExtra(DualListeningFeature.EXTRA_INJECT) else null
        CocListenInject.value = if (BuildConfig.DEBUG) intent.getStringExtra(CocListenInject.EXTRA) else null
        CocAvailabilitySwitches.readLaunchExtras(intent)
        CallSendPauseInject.readLaunchExtras(intent)
        CallAvailabilityFaults.listener = { source, error -> runOnUiThread { onCallAvailabilityLost(source, error) } }
        DebugTunables.sharedCocListener = if (intent.hasExtra("test_coc_shared_listener")) {
            intent.getBooleanExtra("test_coc_shared_listener", false)
        } else {
            null
        }
        DebugTunables.audioGapAlignment = intent.getBooleanExtra("test_audio_gap_align", true)
        DebugTunables.voiceCallLowDelay = intent.getBooleanExtra("test_voice_call_low_delay", false)
        DebugTunables.callAudioOwnClock = intent.getBooleanExtra("test_call_audio_own_clock", false)
        DebugTunables.callStaleAudioDrop = intent.getBooleanExtra("test_call_stale_audio_drop", false)
        DebugTunables.callFastRecovery = intent.getBooleanExtra("test_call_fast_recovery", true)
        DebugTunables.callVideoRestart = intent.getBooleanExtra("test_call_video_restart", false)
        if (BuildConfig.DEBUG) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK audio_policy: gapAlign=${DebugTunables.audioGapAlignment} | " +
                    "voiceCallLowDelay=${DebugTunables.voiceCallLowDelay} | " +
                    "callAudioOwnClock=${DebugTunables.callAudioOwnClock} | " +
                    "callStaleAudioDrop=${DebugTunables.callStaleAudioDrop} | " +
                    "callFastRecovery=${DebugTunables.callFastRecovery} | " +
                    "callVideoRestart=${DebugTunables.callVideoRestart}",
            )
        }
        DebugTunables.stableAdvertisingAddress = intent.getBooleanExtra("test_stable_adv_address", true)
        DebugTunables.advNonDiscoverable = intent.getBooleanExtra(NonDiscoverableAdvertising.EXTRA, true)
        DebugTunables.discoverableWhenNeutral = if (intent.hasExtra(DiscoverableWhenNeutral.EXTRA)) {
            intent.getBooleanExtra(DiscoverableWhenNeutral.EXTRA, false)
        } else {
            null
        }
        DebugTunables.deferredGattClose = if (intent.hasExtra("test_deferred_gatt_close")) {
            intent.getBooleanExtra("test_deferred_gatt_close", false)
        } else {
            null
        }
        DebugTunables.cocFreshAcl = if (intent.hasExtra("test_coc_fresh_acl")) {
            intent.getBooleanExtra("test_coc_fresh_acl", false)
        } else {
            null
        }
        DebugTunables.cocSequencedPhy = intent.getBooleanExtra("test_coc_sequenced_phy", true)
        DebugTunables.phyAfterPriority = intent.getBooleanExtra("test_phy_after_priority", false)
        DebugTunables.psmReadLinkQuiet = intent.getBooleanExtra("test_psm_read_link_quiet", false)
        DebugTunables.gattMtuProbe = intent.getBooleanExtra("test_gatt_mtu_probe", true)
        DebugTunables.receiverUiStability = intent.getBooleanExtra("test_receiver_ui_stability", true)
        DebugTunables.streamRowGrouping = intent.getBooleanExtra("test_stream_row_grouping", true)
        DebugTunables.cocPhyAfterConnect = intent.getBooleanExtra("test_coc_phy_after_connect", false)
        DebugTunables.peerAddressBook = intent.getBooleanExtra("test_peer_address_book", false)
        DebugTunables.cocCoalesceSdus = intent.getBooleanExtra("test_coc_coalesce_sdus", false)
        DebugTunables.relayCocCoalesce = intent.getBooleanExtra("test_relay_coc_coalesce", false)
        DebugTunables.lePriorityRetry = intent.getBooleanExtra("test_le_priority_retry", true)
        DebugTunables.forwarderChildIntervalUnits = intent.getIntExtra("test_forwarder_child_interval_units", -1).takeIf { it > 0 }
        DebugTunables.forwarderCocPriority = intent.getStringExtra("test_forwarder_coc_priority")
            ?.takeIf { it in setOf("high", "dck", "balanced", "low") }
            ?.let(BleConnectionPriority::fromDebugValue)
        DebugTunables.adaptivePhy = intent.getBooleanExtra("test_adaptive_phy", true)
        DebugTunables.relayPhyGuard = intent.getBooleanExtra("test_relay_phy_guard", true)
        DebugTunables.directCallHopMemory = intent.getBooleanExtra("test_direct_call_hop_memory", true)
        QlConsent.enabled = intent.getBooleanExtra("test_ql_consent_ui", true)
        DebugTunables.relayOnePduMove = intent.getBooleanExtra("test_relay_one_pdu_move", false)
        DebugTunables.relayColdStart = intent.getBooleanExtra("test_relay_cold_start", true)
        DebugTunables.relayNoCeilingFloor = intent.getBooleanExtra("test_relay_no_ceiling_floor", false)
        DebugTunables.relayedRestartGuard = intent.getBooleanExtra("test_relayed_restart_guard", true)
        DebugTunables.relayEstablishFallback = intent.getBooleanExtra("test_relay_establish_fallback", true)
        DebugTunables.relayEstablishBudget = intent.getBooleanExtra("test_relay_establish_budget", false)
        DebugTunables.fanoutProvenLinks = intent.getBooleanExtra("test_fanout_proven_links", true)
        DebugTunables.relayReanchor = intent.getBooleanExtra("test_relay_reanchor", false)
        DebugTunables.adaptivePhyMemory = intent.getBooleanExtra("test_adaptive_phy_memory", true)
        DebugTunables.phySetupStallGuard = intent.getBooleanExtra("test_phy_setup_stall", true)
        DebugTunables.messageAdaptivePhy = intent.getBooleanExtra("test_message_adaptive_phy", true)
        DebugTunables.streamAdaptivePhy = intent.getBooleanExtra("test_stream_adaptive_phy", true)
        DebugTunables.stallRemembersTwoM = intent.getBooleanExtra("test_stall_remembers_2m", true)
        DebugTunables.stallRediscoverMs = intent.getIntExtra("test_stall_rediscover_ms", StallRediscover.DEFAULT_MS.toInt()).toLong()
        DebugTunables.rediscoverReleaseWindow = intent.getBooleanExtra("test_rediscover_release_window", true)
        DebugTunables.setupRetryAclWait = intent.getBooleanExtra("test_setup_retry_acl_wait", true)
        DebugTunables.stallRetryDials = intent.getBooleanExtra("test_stall_retry_dials", true)
        DebugTunables.phyMemoryContactKey = intent.getBooleanExtra("test_phy_memory_contact_key", true)
        PhySwitches.applyRadioArms(intent) // DEBUG-only GATT PHY split and PHY-after-auth arms
        DebugTunables.stalledTwoMMove = intent.getBooleanExtra("test_stalled_2m_move", true)
        // null (extra absent) follows the default policy: on for fixed-rate streams (PhyMoveSkip.enabled).
        DebugTunables.phyMoveSkipBacklog = if (intent.hasExtra("test_phy_move_skip_backlog")) intent.getBooleanExtra("test_phy_move_skip_backlog", false) else null
        DebugTunables.messageProgressTotal = intent.getBooleanExtra("test_mv2_progress_total", true)
        DebugTunables.cocSinglePduSdus = intent.getBooleanExtra("test_coc_single_pdu_sdus", true)
        DebugTunables.experimentalBlePhy = ExperimentalBlePhy.fromDebugValue(intent.getStringExtra("test_ble_phy"))
        DebugTunables.gattRequestMtu = intent.getIntExtra("test_gatt_request_mtu", -1).takeIf { it > 0 }
        DebugTunables.experimentalAlignedGattWrites = intent.getBooleanExtra("test_gatt_aligned_writes", false)
        if (BuildConfig.DEBUG) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK le_link_policy: sharedCocListener=${CocListeners.sharedEnabled()} | " +
                    "callLinkPacing=${DebugTunables.callLinkPacing} | " +
                    "stableAdvertisingAddress=${DebugTunables.stableAdvertisingAddress} | " +
                    "deferredGattClose=${GattClientTeardown.deferred()} | " +
                    "cocFreshAcl=${BleCocFreshAcl.enabled()} | " +
                    "blePhy=${BlePhyPreference.TWO_M.label} | " +
                    "cocSequencedPhy=${DebugTunables.cocSequencedPhy} | " +
                    "phyAfterPriority=${DebugTunables.phyAfterPriority} | " +
                    "psmReadLinkQuiet=${DebugTunables.psmReadLinkQuiet} | " +
                    "cocPhyAfterConnect=${DebugTunables.cocPhyAfterConnect} | " +
                    "peerAddressBook=${DebugTunables.peerAddressBook} | " +
                    "cocCoalesceSdus=${DebugTunables.cocCoalesceSdus}(effective=${CocSduCoalescing.effective()}) | " +
                    "relayCocCoalesce=${DebugTunables.relayCocCoalesce}(effective=${ForwardingCocCoalescing.effective()}) | " +
                    "lePriorityRetry=${DebugTunables.lePriorityRetry} | " +
                    "gattMtuProbe=${DebugTunables.gattMtuProbe} | " +
                    "receiverUiStability=${DebugTunables.receiverUiStability} | " +
                    "adaptivePhy=${DebugTunables.adaptivePhy} | " +
                    "adaptivePhyMemory=${DebugTunables.adaptivePhyMemory} | " +
                    "phySetupStall=${DebugTunables.phySetupStallGuard} | " +
                    "relayEstablishKeep2m=${DebugTunables.relayEstablishKeep2M} | " +
                    "messageAdaptivePhy=${DebugTunables.messageAdaptivePhy} | " +
                    "streamAdaptivePhy=${DebugTunables.streamAdaptivePhy} | " +
                    "stallRemembers2m=${DebugTunables.stallRemembersTwoM} | " +
                    "directCallHopMemory=${DebugTunables.directCallHopMemory} | " +
                    "stallRediscoverMs=${DebugTunables.stallRediscoverMs} | " +
                    "setupRetryAclWait=${DebugTunables.setupRetryAclWait} | " +
                    "stallRetryDials=${DebugTunables.stallRetryDials} | " +
                    "unifiedConnectPolicy=${DebugTunables.unifiedConnectPolicy} | " +
                    "phyMemoryContactKey=${DebugTunables.phyMemoryContactKey} | " +
                    "gattPhySplit=${DebugTunables.experimentalPhySplit?.debugValue ?: "off"} | " +
                    "adaptivePhySplit=${DebugTunables.adaptivePhySplit?.debugValue ?: "off"} | " +
                    "phyDirectionSplit=${DebugTunables.phyDirectionSplit} | " +
                    "own2mMemory=${PhySwitches.own2mMemory} | " +
                    "slow2mInPlace=${PhySwitches.slow2mInPlace} | " +
                    "call1mFirst=${PhySwitches.call1mFirst} | " +
                    "call1mFirstCoc=${PhySwitches.call1mFirstCoc} | " +
                    "call1mFirstLinkLimit=${PhySwitches.call1mFirstLinkLimit} | " +
                    "phyAfterAuth=${DebugTunables.phyAfterAuth} | " +
                    "stalledTwoMMove=${DebugTunables.stalledTwoMMove} | " +
                    "phyMoveSkipBacklog=${DebugTunables.phyMoveSkipBacklog ?: "default"} | " +
                    "messageProgressTotal=${DebugTunables.messageProgressTotal} | " +
                    "relayOnePduMove=${DebugTunables.relayOnePduMove} | " +
                    "relayParentTrickle=${DebugTunables.relayParentTrickle} | " +
                    "viewerLinkSilence=${DebugTunables.viewerLinkSilence} | " +
                    "relayChildStallGuard=${DebugTunables.relayChildStallGuard} | " +
                    "relayStallHoldoff=${DebugTunables.relayStallHoldoff} | " +
                    "relayStallSeparate=${DebugTunables.relayStallSeparate} | " +
                    "relayRefusalEscalation=${DebugTunables.relayRefusalEscalation} | " +
                    "relayHoldInPlace=${DebugTunables.relayHoldInPlace} | " +
                    "gattInputFailureEndsLink=${DebugTunables.gattInputFailureEndsLink} | " +
                    "callLinkRenewal=${DebugTunables.callLinkRenewal} | callTxStallAfterS=${DebugTunables.callTxStallAfterS} | " +
                    "gattReregisterKeepAddress=${DebugTunables.gattReregisterKeepAddress} | " +
                    "stallSlowAuthMs=${DebugTunables.stallSlowAuthMs} | " +
                    "gattFirstWriteCountsControl=${DebugTunables.gattFirstWriteCountsControl} | " +
                    "hopIntegrityResendDiff=${DebugTunables.hopIntegrityResendDiff} | " +
                    "phyMoveFloodGuard=${DebugTunables.phyMoveFloodGuard} | " +
                    "relayEstablishBudget=${DebugTunables.relayEstablishBudget} | " +
                    "relayUpstreamDck=${DebugTunables.relayUpstreamDck} | " +
                    "cocSinglePduSdus=${DebugTunables.cocSinglePduSdus} | " +
                    "gattRequestMtu=${DebugTunables.gattRequestMtu ?: "default"} | " +
                    "gattAlignedWrites=${DebugTunables.experimentalAlignedGattWrites}",
            )
            // One effective configuration of relay-child recovery (F3) and of the pacer seed / per-child queue (F4).
            Log.i("BluetoothVideo", RelayRecoveryConfig.current().line())
            Log.i("BluetoothVideo", PacerQueueConfig.current().line())
            // AP5: the relay admission floors in force (relay_admission adds floor= only while the Experimental one is on).
            Log.i(
                "BluetoothVideo",
                "BENCHMARK relay_floor_policy: coldStart=${DebugTunables.relayColdStart} | " +
                    "noCeilingFloor=${DebugTunables.relayNoCeilingFloor}",
            )
        }
        DeviceFamilyPolicy.current().let { policy ->
            // DEBUG only: the line carries the build fingerprint and codec component names, which a release
            // build must not write to logcat (Log.i is also stripped by proguard-release-logs.pro).
            ReleaseLogGate.debugOnly(BuildConfig.DEBUG) {
                DeviceFamilyPolicy.benchmarkLine(policy) + " | " +
                    runCatching { BluetoothAudioCoexistence.benchmarkField() }.getOrDefault("btAudio=unread") + " | " +
                    runCatching { DualRoleProbes.summary() }.getOrDefault("dual_role=unread")
            }?.let { Log.i("BluetoothVideo", it) }
            policy.switchedOff.forEach { Log.w("BluetoothVideo", "Device policy entry switched off: ${it.id}") }
            if (DebugTunables.callLegacyRelief) {
                Log.i("BluetoothVideo", "Device policy call view: legacy relief switches off " +
                    DevicePolicyRegistry.LEGACY_QUALCOMM_CALL.filter(policy::isActive).ifEmpty { listOf("none") }.joinToString(","))
            }
        }
        // Kill reasons since the last start (ApplicationExitInfo, kept in a local log); the status reads the last unexpected one.
        runCatching { ProcessExitReport.recordAtStart(this) }
        // An always-on role (relaying, reachable for calls) makes a missing battery exemption ACTION_NEEDED instead of ADVISORY.
        BackgroundReliability.alwaysOnRole = { forwardReceivedStreams || CallReachabilityFeature.enabled }
        BackgroundReliability.logStatus(this)
        // Requested vs effective foreground-service types (bg-fgs-start-fallback): tell the user when
        // Android withheld the camera/microphone or the whole background session.
        ActiveVideoSessionService.outcomeListener = { outcome ->
            runOnUiThread {
                if (!isDestroyed) {
                    setStatus(outcome.userNotice())
                    Toast.makeText(this, outcome.userNotice(), Toast.LENGTH_LONG).show()
                    if (audioLive.mode) audioLive.onForegroundTypes(outcome.effectiveTypes) // AUDIO-LIVE hook
                }
            }
        }
        // Scan quota (le-scan-start-quota): the stack counts per UID across our restarts; seed the gate
        // from this boot's persisted starts and say when Android's limit delays a search.
        ScanStartGate.shared.attach(PreferencesScanStartStore(this))
        // Scan renewal, silence watchdog, Location-mode and Bluetooth-state receivers (batch S). It restarts only what the
        // ScanRegistry holds, through the gate; a call that is dialling or ringing defers a renewal.
        ScanRuntime.dialInProgress = {
            privateCallPhase.let { it != null && it != PrivateCallPhase.ACTIVE && it != PrivateCallPhase.ENDED }
        }
        ScanRuntime.attach(this)
        // Bluetooth ON recovery (BluetoothOnRecovery.kt): a live broadcast offers its stream again after Bluetooth is back.
        BluetoothEpoch.register(streamEpochName, onOff = ::streamPresenceBluetoothOff, onOn = ::streamPresenceBluetoothOn)
        ScanStartGate.deferralListener = { waitMs ->
            runOnUiThread {
                if (!isDestroyed) setStatus(getString(R.string.status_search_wait, ((waitMs + 999) / 1_000).toInt()))
            }
        }
        // Workstream C experiments (all off by default; see DebugTunables).
        DebugTunables.sceneCutShaping = SceneCutShapingMode.fromDebugValue(intent.getIntExtra("test_scene_cut_shaping", 0))
        DebugTunables.vp9QuantizerFloor = intent.getIntExtra("test_vp9_q_floor", -1).takeIf { it > 0 }
        DebugTunables.experimentalEncoderRanking = intent.getBooleanExtra("test_encoder_ranking", false)
        DebugTunables.preferHardwareAv1 = intent.getBooleanExtra("test_prefer_hw_av1", true)
        // Design HC control arms (DEBUG; release builds always prefer and send bit 4 while a master is on).
        VideoCodecSwitches.preferHevc = intent.getBooleanExtra(VideoCodecFeatures.EXTRA_PREFER_HEVC, true)
        VideoCodecSwitches.preferHwAvc = intent.getBooleanExtra(VideoCodecFeatures.EXTRA_PREFER_HW_AVC, true)
        VideoCodecSwitches.av1SoftDecodeBit = intent.getBooleanExtra(VideoCodecFeatures.EXTRA_AV1_SOFT_DECODE_BIT, true)
        VideoCodecSwitches.bit7HwHevc = intent.getBooleanExtra(VideoCodecFeatures.EXTRA_BIT7_HW_HEVC, true)
        VideoCodecSwitches.hcSessionLevel = intent.getBooleanExtra(VideoCodecFeatures.EXTRA_HC_SESSION_LEVEL, true)
        // Design VX (DEBUG): absent keeps the shipped LIBVPX_AUTOMATIC; true/false decides this launch.
        // A benchmark launch without the extra keeps libvpx a manual choice (extras-only, like the HC masters), so cases
        // measured before 0.4.2 keep their Automatic encoder.
        VideoCodecSwitches.preferLibvpxOverride = when {
            intent.hasExtra(VideoCodecFeatures.EXTRA_PREFER_LIBVPX) -> intent.getBooleanExtra(VideoCodecFeatures.EXTRA_PREFER_LIBVPX, false)
            DebugTunables.benchmarkLaunch -> false
            else -> null
        }
        // codec_power_aware (DEBUG control arm test_codec_power_aware=false; a benchmark launch needs the extra).
        CodecPowerAware.enabled = intent.getBooleanExtra(CodecPowerAware.EXTRA, !DebugTunables.benchmarkLaunch)
        CodecPowerAware.provider = codecPowerProvider
        HcTestVectorCapture.requested = BuildConfig.DEBUG && intent.getBooleanExtra(HcTestVectorCapture.EXTRA, false)
        DebugTunables.experimentalBurstDrain = intent.getBooleanExtra("test_burst_drain", false)
        DebugTunables.playoutBurstGrowth = intent.getFloatExtra("test_playout_burst_growth", -1f)
            .takeIf { it >= 1f }?.toDouble()
        DebugTunables.intraRefreshFrames = intent.getIntExtra("test_intra_refresh_frames", -1).takeIf { it > 0 }
        // AUDIO-LIVE hook: the experimental arms of the audio-only stream (DEBUG only; release keeps the defaults).
        AudioLiveTunables.applyExtras(intent::getBooleanExtra, intent::getIntExtra, intent::getStringExtra)
        if (BuildConfig.DEBUG) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK burst_experiments: scene_cut_shaping=${DebugTunables.sceneCutShaping.label} | " +
                    "vp9_q_floor=${DebugTunables.vp9QuantizerFloor ?: "off"} | " +
                    "encoder_ranking=${DebugTunables.experimentalEncoderRanking} | " +
                    "burst_drain=${DebugTunables.experimentalBurstDrain} | " +
                    "playout_burst_growth=${DebugTunables.playoutBurstGrowth ?: "off"} | " +
                    "intra_refresh_frames=${DebugTunables.intraRefreshFrames ?: "off"}",
            )
        }

        cameraScreen = findViewById(R.id.cameraScreen)
        receiverBrowserScreen = findViewById(R.id.receiverBrowserScreen)
        callBrowserScreen = findViewById(R.id.callBrowserScreen)
        callScreen = findViewById(R.id.callScreen)
        preview = findViewById(R.id.preview)
        cameraViewfinder = findViewById(R.id.cameraViewfinder)
        callRemotePreview = findViewById(R.id.callRemotePreview)
        callLocalViewfinder = findViewById(R.id.callLocalViewfinder)
        callBrowserStatus = findViewById(R.id.callBrowserStatus)
        callPeerList = findViewById(R.id.callPeerList)
        callStatusText = findViewById(R.id.callStatusText)
        callProfileText = findViewById(R.id.callProfileText)
        incomingCallControls = findViewById(R.id.incomingCallControls)
        activeCallControls = findViewById(R.id.activeCallControls)
        cancelCallButton = findViewById(R.id.cancelCallButton)
        callMuteButton = findViewById(R.id.callMuteButton)
        callCameraButton = findViewById(R.id.callCameraButton)
        callSwitchCameraButton = findViewById(R.id.callSwitchCameraButton)
        callSpeakerButton = findViewById(R.id.callSpeakerButton)
        callRemoteCameraOff = findViewById(R.id.callRemoteCameraOff)
        callScreenController = CallScreenController(
            callScreen = findViewById(R.id.callScreen),
            remoteContainer = findViewById(R.id.callRemoteContainer),
            remoteCameraOff = findViewById(R.id.callRemoteCameraOff),
            remoteStateText = findViewById(R.id.callRemoteStateText),
            localContainer = findViewById(R.id.callLocalContainer),
            localViewfinder = callLocalViewfinder,
            localRawPreview = findViewById<ImageView>(R.id.callLocalRawPreview),
            topBar = findViewById(R.id.callTopBar),
            infoButton = findViewById(R.id.callInfoButton),
            profileText = callProfileText,
            bottomBars = listOf(
                incomingCallControls,
                activeCallControls,
                cancelCallButton,
                findViewById(R.id.callPushToTalkButton),
            ),
        )
        intercomCallUi = IntercomCallUi(
            voicePanel = findViewById(R.id.callVoicePanel),
            voicePeerText = findViewById(R.id.callVoicePeerText),
            voiceTypeText = findViewById(R.id.callVoiceTypeText),
            voiceTimer = findViewById(R.id.callVoiceTimer),
            pushToTalkButton = findViewById(R.id.callPushToTalkButton),
            onPushToTalk = ::onPrivateCallPushToTalk,
        )
        settingsScreen = findViewById(R.id.settingsScreen)
        callsTabScreen = findViewById(R.id.callsTabScreen)
        contactsTabScreen = findViewById(R.id.contactsTabScreen)
        mainNav = findViewById(R.id.mainNav)
        homeConfigurationSummary = findViewById(R.id.homeConfigurationSummary)
        privateCallProfileSummary = findViewById(R.id.privateCallProfileSummary)
        cameraSwitchButton = findViewById(R.id.cameraSwitchButton)
        doneSettingsButton = findViewById(R.id.doneSettingsButton)
        transportSpinner = findViewById(R.id.transportSpinner)
        destinationGroup = findViewById(R.id.destinationGroup)
        destinationLabel = findViewById(R.id.destinationLabel)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        codecSpinner = findViewById(R.id.codecSpinner)
        rateControlSpinner = findViewById(R.id.rateControlSpinner)
        advancedSettingsSwitch = findViewById(R.id.advancedSettingsSwitch)
        advancedSettingsDescriptionText = findViewById(R.id.advancedSettingsDescriptionText)
        rateControlLabel = findViewById(R.id.rateControlLabel)
        rateControlDescriptionText = findViewById(R.id.rateControlDescriptionText)
        videoScaleModeSpinner = findViewById(R.id.videoScaleModeSpinner)
        videoScaleModeDescriptionText = findViewById(R.id.videoScaleModeDescriptionText)
        receiverPlaybackDelaySlider = findViewById(R.id.receiverPlaybackDelaySlider)
        playbackDelayValueText = findViewById(R.id.playbackDelayValueText)
        playbackStabilityText = findViewById(R.id.playbackStabilityText)
        refreshButton = findViewById(R.id.refreshButton)
        capabilityText = findViewById(R.id.capabilityText)
        decoderSupportText = findViewById(R.id.decoderSupportText)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        fpsSpinner = findViewById(R.id.fpsSpinner)
        bitrateText = findViewById(R.id.bitrateText)
        bitrateSeekBar = findViewById(R.id.bitrateSeekBar)
        streamSummaryText = findViewById(R.id.streamSummaryText)
        streamToggleButton = findViewById(R.id.streamToggleButton)
        streamToggleButton.label = findViewById(R.id.streamToggleLabel)
        findViewById<LabeledRoundButton>(R.id.cameraSwitchButton).label = findViewById(R.id.cameraSwitchLabel)
        findViewById<LabeledRoundButton>(R.id.broadcastPauseButton).label = findViewById(R.id.broadcastPauseLabel)
        broadcastPauseButton = findViewById(R.id.broadcastPauseButton)
        broadcastMuteButton = findViewById(R.id.broadcastMuteButton)
        broadcastMuteButton.label = findViewById(R.id.broadcastMuteLabel)
        broadcastCameraButton = findViewById(R.id.broadcastCameraButton)
        broadcastCameraButton.label = findViewById(R.id.broadcastCameraLabel)
        liveMediaStateCover = findViewById(R.id.liveMediaStateCover)
        liveMediaStateChip = findViewById(R.id.liveMediaStateChip)
        statusText = findViewById(R.id.statusText)
        connectionStatusDot = findViewById(R.id.connectionStatusDot)
        deviceHealthOverlay = findViewById(R.id.deviceHealthOverlay)
        thermalStatusBadge = findViewById(R.id.thermalStatusBadge)
        batteryTemperatureBadge = findViewById(R.id.batteryTemperatureBadge)
        thermalReductionSwitch = findViewById(R.id.thermalReductionSwitch)
        deviceHealthOverlaySwitch = findViewById(R.id.deviceHealthOverlaySwitch)
        forwardReceivedStreamsSwitch = findViewById(R.id.forwardReceivedStreamsSwitch)
        experimentalTemporalLayersSwitch = findViewById(R.id.experimentalTemporalLayersSwitch)
        relayChildMediaAgeSwitch = findViewById(R.id.relayChildMediaAgeSwitch)
        relayChildMediaAgeDescriptionText = findViewById(R.id.relayChildMediaAgeDescriptionText)
        experimentalTemporalLayersDescriptionText = findViewById(R.id.experimentalTemporalLayersDescriptionText)
        rotatingIdsVisibleButton = findViewById(R.id.rotatingIdsVisibleButton)
        rotatingIdsResetKeyButton = findViewById(R.id.rotatingIdsResetKeyButton)
        rotatingIdsLegacyNoteText = findViewById(R.id.rotatingIdsLegacyNoteText)
        neutralBluetoothNameSwitch = findViewById(R.id.neutralBluetoothNameSwitch)
        neutralBluetoothNameDescriptionText = findViewById(R.id.neutralBluetoothNameDescriptionText)
        neutralBluetoothNameStatusText = findViewById(R.id.neutralBluetoothNameStatusText)
        experimentalRelayedCallsSwitch = findViewById(R.id.experimentalRelayedCallsSwitch)
        experimentalRelayedCallsDescriptionText = findViewById(R.id.experimentalRelayedCallsDescriptionText)
        relayedCallsHelpSwitch = findViewById(R.id.relayedCallsHelpSwitch)
        relayedCallsConsentSwitch = findViewById(R.id.relayedCallsConsentSwitch)
        relayedCallsStatusText = findViewById(R.id.relayedCallsStatusText)
        videoBackButton = findViewById(R.id.videoBackButton)
        receiverWaitingOverlay = findViewById(R.id.receiverWaitingOverlay)
        receiverWaitingProgress = findViewById(R.id.receiverWaitingProgress)
        receiverWaitingText = findViewById(R.id.receiverWaitingText)
        receiverBrowserStatus = findViewById(R.id.receiverBrowserStatus)
        streamList = findViewById(R.id.streamList)
        automaticThermalReductionEnabled = appPreferences.getBoolean(
            PREF_AUTOMATIC_THERMAL_REDUCTION,
            true,
        )
        showDeviceHealthOverlay = appPreferences.getBoolean(PREF_SHOW_DEVICE_HEALTH, false)
        forwardReceivedStreams = appPreferences.getBoolean(
            PREF_FORWARD_RECEIVED_STREAMS,
            relayDefaultOn(benchmarkExtrasPresent()),
        )
        outgoingTransport = runCatching {
            BluetoothTransportOption.valueOf(
                appPreferences.getString(
                    PREF_OUTGOING_TRANSPORT,
                    BluetoothTransportOption.DEFAULT.name,
                ) ?: BluetoothTransportOption.DEFAULT.name,
            )
        }.getOrDefault(BluetoothTransportOption.DEFAULT)
        restoreVideoScaleMode()
        receiverPlaybackDelay = PlaybackDelayProfile.fromStoredValue(
            appPreferences.getString(PREF_RECEIVER_PLAYBACK_DELAY, null),
        )
        advancedSettings = appPreferences.getBoolean(PREF_ADVANCED_SETTINGS, false)
        applySettingsModeValues()
        applyExperimentalTemporalLayers()
        applyRelayChildMediaAge()
        applyIntercomFeatures()
        bindCallFeatureSettings()
        CocRegistrations.install(this)
        applyCocAutoGatt()
        ClassicAddressBook.attach(this)
        applyClassicFeatures()
        applyCompactDiscovery()
        applyFindPcs()
        applyRotatingIds()
        applyNeutralName()
        applyRelayedCalls()
        applyCallReachability()
        refreshFeatureCatalog()
        WifiDiscovery.start(applicationContext)
        if (BuildConfig.DEBUG) {
            val switches = WifiSwitchHub.current()
            WifiLog.emit(
                WifiLog.switches(switches.carrier, switches.widerReach, switches.direct) +
                    " | probe=${DebugTunables.wifiProbe} | icm=${DebugTunables.wifiIcm} | " +
                    "bleStandbyLowPower=${DebugTunables.wifiBleStandbyLowPower} | allow24Ghz=${DebugTunables.wifiAllow24ghz} | " +
                    "shareAttach=${DebugTunables.wifiShareAttach} | awareDerivedPort=${DebugTunables.wifiAwareDerivedPort} | " +
                    "awareRequireChannel=${DebugTunables.wifiAwareRequireChannel} | awareBandStrict=${DebugTunables.awareBandStrict} | " +
                    "aware24GhzAlone=${DebugTunables.aware24GhzAlone} | awareGoodputGuard=${DebugTunables.awareGoodputGuard} | " +
                    "forceCarrier=${DebugTunables.wifiForceCarrier.ifEmpty { "off" }}",
            )
        }
        applyOfflineAppShare()
        applyNativeOpus()
        applyCallResolutionLadder()
        applyVideoScaleMode()
        applyDeviceHealthOverlayVisibility()
        restoreVideoSettings()
        applyDebugLaunchConfiguration()
        debugSessionTelemetry = DebugSessionTelemetry.startIfRequested(
            this,
            intent,
            sessionRole.label,
        )

        // Tab screens sit above the bottom navigation, which handles the bottom system inset itself.
        listOf(settingsScreen, receiverBrowserScreen, callBrowserScreen, callsTabScreen, contactsTabScreen).forEach { screen ->
            ViewCompat.setOnApplyWindowInsetsListener(screen) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val bottom = if (mainNav.visibility == View.VISIBLE) 0 else systemBars.bottom
                view.setPadding(view.paddingLeft, systemBars.top, view.paddingRight, bottom)
                insets
            }
        }
        val incomingControlsBottomPadding = incomingCallControls.paddingBottom
        val activeControlsBottomPadding = activeCallControls.paddingBottom
        val cancelButtonBottomMargin =
            (cancelCallButton.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        val pushToTalkButton = findViewById<View>(R.id.callPushToTalkButton)
        val pushToTalkBottomMargin = (pushToTalkButton.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(callScreen) { _, insets ->
            val systemBars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            incomingCallControls.setPadding(
                incomingCallControls.paddingLeft,
                incomingCallControls.paddingTop,
                incomingCallControls.paddingRight,
                incomingControlsBottomPadding + systemBars.bottom,
            )
            activeCallControls.setPadding(
                activeCallControls.paddingLeft,
                activeCallControls.paddingTop,
                activeCallControls.paddingRight,
                activeControlsBottomPadding + systemBars.bottom,
            )
            (cancelCallButton.layoutParams as ViewGroup.MarginLayoutParams).let { params ->
                params.bottomMargin = cancelButtonBottomMargin + systemBars.bottom
                cancelCallButton.layoutParams = params
            }
            (pushToTalkButton.layoutParams as ViewGroup.MarginLayoutParams).let { params ->
                params.bottomMargin = pushToTalkBottomMargin + systemBars.bottom
                pushToTalkButton.layoutParams = params
            }
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            callScreenController.setInsets(
                maxOf(systemBars.left, cutout.left),
                maxOf(systemBars.top, cutout.top),
                maxOf(systemBars.right, cutout.right),
                maxOf(systemBars.bottom, cutout.bottom),
            )
            insets
        }

        onBackPressedDispatcher.addCallback(this, settingsBackCallback)
        // Added after the screens' callback, so an open thread (phase 1c) takes Back first.
        onBackPressedDispatcher.addCallback(this, threadBackCallback)
        findViewById<View>(R.id.streamModeButton).setOnClickListener { audioLive.onGoLive() } // AUDIO-LIVE hook
        bindStatusIndicators()
        findViewById<View>(R.id.homeChangeSettingsButton).setOnClickListener { showSettings(true) }
        videoBackButton.setOnClickListener {
            showReceiverBrowser()
        }
        streamList.setOnItemClickListener { _, _, position, _ ->
            nearbyStreamRows.getOrNull(position)?.best?.let { peer ->
                withWifiCheck {
                    // An explicit choice is a fresh attempt: forget local failures and repeat counts.
                    receiverLocalMediaFailure = null
                    repeatedReceiverFailures.reset()
                    connectToStream(peer)
                }
            }
        }
        findViewById<android.widget.EditText>(R.id.nearbyPeopleSearch).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                nearbySearchQuery = s?.toString().orEmpty()
                updatePrivateCallPeerList()
            }
        })
        callPeerList.setOnItemClickListener { _, _, position, _ ->
            peerForListPosition(position)?.let { peer -> withWifiCheck { choosePrivateCallType(peer) } }
        }
        callPeerList.setOnItemLongClickListener { _, _, position, _ ->
            val peer = peerForListPosition(position) ?: return@setOnItemLongClickListener false
            showPrivateCallPeerDetails(peer)
            true
        }
        findViewById<View>(R.id.acceptCallButton).setOnClickListener {
            stopPrivateCallRinging()
            // A dialog here could let the ringing call time out; advise without blocking.
            WifiCoexistence.advice()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
            runCatching { acceptPrivateCall() }.onFailure { setCallStatus(it.message ?: "Could not accept") }
        }
        findViewById<View>(R.id.declineCallButton).setOnClickListener {
            stopPrivateCallRinging()
            val oneTimeCaller = privateCall?.incomingCaller()?.pseudonymous == true // read before the decline closes the session
            runCatching { privateCall?.decline() }.onFailure { setCallStatus(it.message ?: "Could not decline") }
            if (oneTimeCaller) CallBlockingUi.offerBlockUnknownAfterDecline(this)
        }
        findViewById<View>(R.id.declineAndBlockButton).setOnClickListener {
            stopPrivateCallRinging()
            val caller = privateCall?.incomingCaller() // read before the decline closes the session
            runCatching { privateCall?.decline() }.onFailure { setCallStatus(it.message ?: "Could not decline") }
            CallBlockingUi.afterDeclineAndBlock(this, caller)
        }
        findViewById<View>(R.id.declineAndReportButton).setOnClickListener {
            val caller = privateCall?.incomingCaller()
            stopPrivateCallRinging()
            runCatching { privateCall?.decline() }
            CallBlockingUi.report(this, caller?.takeUnless { it.pseudonymous }?.fingerprint, onDone = ::updateRecentCalls)
        }
        cancelCallButton.setOnClickListener {
            stopPrivateCallRinging()
            privateCall?.cancel()
        }
        findViewById<View>(R.id.endCallButton).setOnClickListener {
            stopPrivateCallRinging()
            privateCall?.hangUp()
        }
        callMuteButton.setOnClickListener { togglePrivateCallMute() }
        bindHoldMuteToTalk()
        callCameraButton.setOnClickListener { togglePrivateCallCamera() }
        callSwitchCameraButton.setOnClickListener { switchPrivateCallCamera() }
        callSpeakerButton.setOnClickListener { onCallSpeakerTapped() }
        availableCameraLenses = deviceVideoCapabilities.availableCameraLenses()
        if (cameraLens !in availableCameraLenses) {
            cameraLens = availableCameraLenses.firstOrNull() ?: CameraLens.BACK
        }
        cameraSwitchButton.setOnClickListener { switchCamera() }
        broadcastPauseButton.setOnClickListener { toggleBroadcastPause() }
        broadcastMuteButton.setOnClickListener { toggleBroadcastMute() }
        broadcastCameraButton.setOnClickListener { toggleBroadcastCamera() }
        doneSettingsButton.setOnClickListener { settingsBack() }
        bindRedesignedUi()

        configureTransport()
        configureSettingsControls()
        WifiSwitchHub.addListener(wifiSwitchListener)
        WifiSwitchHub.addListener(directSwitchListener)
        refreshCodecs()
        applyDebugCodecSelection()
        codecSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                AutomaticEncoderChoice.userChoice = debugCodecRequested || position != 0
                refreshRateControlPicker()
                refreshVideoModes()
                updateDeviceCapabilities()
                updateStreamSummary()
                updateStartEnabled()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                applyVideoSurfaceTransform()
                if (appScreen == AppScreen.PLAYBACK && receiver == null) {
                    if (reconnectTarget != null) refreshNearbyStreams() else startReceiving()
                } else {
                    startIdleCameraPreview()
                }
                updateStartEnabled()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                applyVideoSurfaceTransform()
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopIdleCameraPreview()
                // AUDIO-LIVE hook: an audio-only listener has no picture; leaving the screen must not end the radio.
                if (audioLive.listening && broadcaster == null && sender == null) return true
                if (sender != null || broadcaster != null || receiver != null) {
                    stopStreaming("Video surface was closed.")
                }
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                if (appScreen != AppScreen.PLAYBACK) return
                viewerFrameAge?.onFramePresented()
                val tracker = receiverContinuityTracker ?: return
                val nowNs = System.nanoTime()
                if (debugVideoPresentationTiming) receiver?.recordSurfaceUpdate(surface.timestamp, nowNs)
                tracker.recordFrame(nowNs)
                if (receiverWaitingOverlay.visibility == View.VISIBLE &&
                    receiver != null && receiverWasActive &&
                    !receiverRecoveryNoticePolicy.coverHeld(SystemClock.elapsedRealtime()) &&
                    !receiverPlaybackPaused && !receiverStreamEnded
                ) hideReceiverOverlay()
                if (receiverFirstSurfaceForJoinPending && receiverWasActive) {
                    receiverFirstSurfaceForJoinPending = false
                    Log.i(
                        "BluetoothVideo",
                        "Receiver startup: selection-to-first-surface %.1f ms".format(
                            Locale.US,
                            (nowNs - receiverJoinStartedAtNs) / 1_000_000.0,
                        ),
                    )
                }
                if (receiverContinuityLastReportNs == 0L || nowNs - receiverContinuityLastReportNs >= CONTINUITY_REPORT_NS) {
                    val snapshot = tracker.snapshot(nowNs)
                    if (snapshot.renderedFrames > 0L) {
                        logReceiverContinuity(snapshot)
                        receiverContinuityLastReportNs = nowNs
                    }
                }
            }
        }

        callRemotePreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                privateCallRemoteSurface?.release()
                privateCallRemoteSurface = Surface(surface)
                applyPrivateCallSurfaceTransform()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                applyPrivateCallSurfaceTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                privateCallRemoteSurface?.release()
                privateCallRemoteSurface = null
                privateCallRemoteVideoStarted = false
                if (appScreen == AppScreen.CALL) privateCall?.hangUp()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                if (shouldRevealPrivateCallRemotePreview(
                        appScreen == AppScreen.CALL,
                        privateCallRemoteVideoStarted,
                    )
                ) {
                    if (callRemotePreview.alpha != 1f) callRemotePreview.alpha = 1f
                    callScreenController.onRemoteFrame(System.nanoTime())
                    // Experimental test_call_1m_first: the first shown picture lets a 1M-first call ask for 2M (no-op otherwise).
                    privateCall?.noteRemotePictureShown()
                    if (intercomCallUi.onRemoteFrame()) onPrivateCallLayoutChanged("remote_frame")
                    if (intercomCallUi.consumePeerLacksVoiceNotice()) showPeerLacksVoiceNotice()
                    if (CallJoinStages.current.pending(CallJoinStage.FIRST_FRAME_SHOWN)) markPrivateCallFirstShown()
                    val tracker = privateCallContinuityTracker ?: return
                    val nowNs = System.nanoTime()
                    tracker.recordFrame(nowNs)
                    val reportDue = privateCallContinuityLastReportNs == 0L ||
                        nowNs - privateCallContinuityLastReportNs >= CONTINUITY_REPORT_NS
                    if (!reportDue && !debugPrivateCallSurfacePending) return
                    val snapshot = tracker.snapshot(nowNs)
                    if (snapshot.renderedFrames == 0L) return
                    if (debugPrivateCallSurfacePending) {
                        debugPrivateCallSurfacePending = false
                        Log.i("BluetoothVideo", "BENCHMARK call first_surface: frames=${snapshot.renderedFrames}")
                    }
                    if (privateCallContinuityLastReportNs == 0L) {
                        Log.i("BluetoothVideo", "Private call startup: media-to-first-surface %.1f ms".format(
                            Locale.US,
                            snapshot.startupMs ?: 0.0,
                        ))
                    }
                    if (reportDue) {
                        logReceiverContinuity(snapshot, label = "Private call")
                        privateCallContinuityLastReportNs = nowNs
                    }
                }
            }
        }

        refreshButton.setOnClickListener { ensurePermissionsAndRefresh() }
        streamToggleButton.setOnClickListener {
            if (usesBroadcastDiscovery()) {
                if (streamArmed) {
                    confirmEndLive { stopStreaming("Stopped.") }
                } else {
                    withWifiCheck {
                        // Experimental peer labels: optional public title, fixed for this stream. Save goes live with the
                        // title, Skip without one; Back or a tap outside does not go live and keeps what was typed.
                        promptLabelText(
                            LabelKind.STREAM_TITLE,
                            R.string.stream_title_prompt,
                            R.string.stream_title_hint,
                            streamTitleDraft ?: PeerLabelFeature.localStreamTitle,
                            positiveRes = R.string.go_live_with_title,
                            negativeRes = R.string.go_live_without_title,
                            onCancel = { typed ->
                                streamTitleDraft = StreamTitleChoice.draftAfter(StreamTitleChoice.Action.CANCEL, typed, null)
                            },
                            onSkip = { typed ->
                                streamTitleDraft = StreamTitleChoice.draftAfter(StreamTitleChoice.Action.SKIP, typed, null)
                                if (PeerLabelFeature.enabled || PeerLabelFeature.titlePagesEnabled) PeerLabelFeature.localStreamTitle = null
                                armDiscoverableStream()
                            },
                        ) { title ->
                            streamTitleDraft = StreamTitleChoice.draftAfter(StreamTitleChoice.Action.SAVE, title.orEmpty(), title)
                            if (PeerLabelFeature.enabled || PeerLabelFeature.titlePagesEnabled) PeerLabelFeature.localStreamTitle = title
                            armDiscoverableStream()
                        }
                    }
                }
            } else if (sender != null || broadcaster != null || receiver != null || relay != null ||
                privateCallRelay != null
            ) {
                stopStreaming("Stopped.")
            } else if (sessionRole == SessionRole.SEND) {
                withWifiCheck { startStreaming() }
            } else if (sessionRole == SessionRole.RELAY) {
                withWifiCheck { startRelaying() }
            } else {
                withWifiCheck { startReceiving() }
            }
        }
        if (BuildConfig.DEBUG && debugSetupRefused) {
            // The launch was refused (setup_refused) and the task is finishing: start nothing.
        } else if (BuildConfig.DEBUG && intent.getBooleanExtra("offline_benchmark", false)) {
            showStreamScreen()
            startDebugBenchmark { it.runOffline(intent) }
        } else if (debugPrivateCallBrowser) {
            showPrivateCallBrowser()
        } else if (requestedTransportExperiment != null) {
            showDebugLaunchScreen()
            ensurePermissionsAndRefresh()
        } else if (debugAutoSelectStream) {
            showReceiverBrowser()
        } else if (debugAutoBroadcastRequested) {
            showStreamCamera()
            armDebugSetupWatchdog()
        } else if (debugAutoStartPending) {
            showDebugLaunchScreen()
            ensurePermissionsAndRefresh()
            armDebugSetupWatchdog()
        } else {
            // First run: explain the app and its permissions. Never for automation launches
            // (they always carry extras), never on recreation.
            val onboarding = shouldShowOnboarding(
                OnboardingActivity.isDone(this),
                // Only our own test/automation extras skip onboarding: launchers (e.g. Samsung One UI) add their own.
                launchHasExtras = launchSkipsOnboarding(),
                recreated = savedInstanceState != null,
            )
            // The Live tab (the stream browser) is the landing screen; its scan and the permission
            // prompts wait for the first-run explanation, also when this activity is recreated
            // underneath it (release smoke 2026-10-28: a Location prompt over the Welcome page).
            if (onboarding) OnboardingState.open = true
            val waiting = onboardingHolds()
            showReceiverBrowser(askPermissions = !waiting)
            if (onboarding || waiting) landOnCallsAfterOnboarding = true
            if (onboarding) startActivity(Intent(this, OnboardingActivity::class.java))
        }
        // A launch with benchmark/control extras is automation: do not show onboarding later.
        if (launchSkipsOnboarding()) {
            OnboardingActivity.markDone(this)
        }
        window.decorView.post { debugAudioLiveAutoStart(intent) } // AUDIO-LIVE hook (DEBUG)
        window.decorView.post { DebugMeetControl.handleIntent(this, intent) } // BVMID2 Meet harness (DEBUG)
        registerThermalGuard()
        startupTimer.mark("rest")
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + startupTimer.line("onCreate"))
        handleMissedCallBack(intent)
    }

    /** AUDIO-LIVE hook (DEBUG): `test_audio_live_autostart=true` starts a radio without taps; see AudioLiveAutoStart. */
    private fun debugAudioLiveAutoStart(source: Intent) {
        if (!BuildConfig.DEBUG) return
        AudioLiveAutoStart.parse(source::getBooleanExtra, source::getStringExtra)?.let(audioLive::debugAutoStart)
    }

    /** Views and handlers added by the UI redesign. Presentation only: no media/protocol changes. */
    private fun bindRedesignedUi() {
        homeReadinessIcon = findViewById(R.id.homeReadinessIcon)
        homeReadinessTitle = findViewById(R.id.homeReadinessTitle)
        homeReadinessText = findViewById(R.id.homeReadinessText)
        homeReadinessAction = findViewById(R.id.homeReadinessAction)
        verifiedStoreBanner = VerifiedStoreBannerController(
            this,
            findViewById(R.id.homeVerifiedStoreBanner),
            findViewById(R.id.homeVerifiedStoreText),
            findViewById(R.id.homeVerifiedStoreReset),
        )
        homeIdentityText = findViewById(R.id.homeIdentityText)
        callSecurityChip = findViewById(R.id.callSecurityChip)
        callPartyPanel = findViewById(R.id.callPartyPanel)
        callPartyName = findViewById(R.id.callPartyName)
        // The person's name lives in the call top bar; the caller card and the voice panel feed it.
        val mirrorCallName = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                findViewById<TextView>(R.id.callTopName).text = s
            }
        }
        callPartyName.addTextChangedListener(mirrorCallName)
        findViewById<TextView>(R.id.callVoicePeerText).addTextChangedListener(mirrorCallName)
        callPartyState = findViewById(R.id.callPartyState)
        callMuteLabel = findViewById(R.id.callMuteLabel)
        callCameraLabel = findViewById(R.id.callCameraLabel)
        callSpeakerLabel = findViewById(R.id.callSpeakerLabel)
        broadcastStatusText = findViewById(R.id.broadcastStatusText)
        streamSecurityText = findViewById(R.id.streamSecurityText)
        watchRelaySwitch = findViewById(R.id.watchRelaySwitch)
        experimentsHiddenHint = findViewById(R.id.experimentsHiddenHint)
        streamList.emptyView = findViewById(R.id.streamEmptyView)
        callPeerList.emptyView = findViewById(R.id.callEmptyView)
        val callTopBar = findViewById<View>(R.id.callTopBar)
        val callInfoButton = findViewById<View>(R.id.callInfoButton)
        callChromeAutoHide = CallChromeAutoHide(
            views = { listOf(callTopBar, activeCallControls) },
            // Call details moved into the call info sheet: a tap only shows or hides the controls.
            detailsShown = { true },
            toggleDetails = {},
        )
        // The info and lock buttons open the call info sheet (encryption, route, verification, details).
        callInfoButton.setOnClickListener { showLinkHealthSheet() }
        callLockButton = findViewById(R.id.callLockButton)
        callLockButton.setOnClickListener { showLinkHealthSheet() }
        findViewById<View>(R.id.broadcastTitleText).setOnClickListener {
            if (sessionRole == SessionRole.RECEIVE) showStreamInfo()
        }
        callPeerList.adapter = peopleAdapter
        refreshContactNames()
        // A tap on the video brings the call controls back (or hides them); a double-tap on the other person's
        // large video switches between the whole picture and filling the screen ([RemoteVideoFit]).
        callScreen.setOnClickListener { callChromeAutoHide.toggle() }
        bindRemoteVideoFitGesture()
        callSecurityChip.setOnClickListener { showLinkHealthSheet() }
        homeReadinessAction.setOnClickListener { onHomeReadinessAction() }
        findViewById<View>(R.id.homePushToTalkButton).setOnClickListener { onHomePushToTalk() }
        findViewById<View>(R.id.homeIdentityCard).setOnClickListener {
            startActivity(VerifyContactActivity.intent(this))
        }
        CallBlockingUi.bindSettings(this)
        findViewById<View>(R.id.contactUsRow).setOnClickListener { ContactEmailUi.open(this) }
        findViewById<View>(R.id.aboutRow).setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }
        findViewById<View>(R.id.emergencyWipeButton).setOnClickListener { confirmEmergencyWipe() }
        // A title page can complete after its stream row was drawn: redraw the Live list then.
        StreamTitles.onNewTitle = { runOnUiThread { if (!isFinishing) updateNearbyStreamList() } }
        findViewById<View>(R.id.exportDiagnosticsButton).setOnClickListener { exportDiagnostics() }
        findViewById<View>(R.id.shareAppButton).apply {
            visibility = if (DistributionUiBackend.current.shareAvailable) View.VISIBLE else View.GONE
            setOnClickListener { runCatching { DistributionUiBackend.current.shareApk(this@MainActivity) } }
        }
        findViewById<View>(R.id.shareAppNearbyButton).setOnClickListener {
            OfflineAppShareActivity.startOrExplain(this@MainActivity)
        }
        // Wi-Fi Direct shares the radio with Bluetooth: the share screen refuses (and stops) while
        // any stream, call, relay or pending reconnect exists.
        OfflineAppShareGate.liveSessionCheck = ::liveSessionBlocksAppShare
        findViewById<View>(R.id.reportBugButton).apply {
            val reportBug = SupportLinks.reportBugIntent()
            visibility = if (reportBug != null) View.VISIBLE else View.GONE
            setOnClickListener {
                runCatching { startActivity(reportBug) }
                    .onFailure { Toast.makeText(this@MainActivity, R.string.help_no_browser, Toast.LENGTH_LONG).show() }
            }
        }
        val panicSwitch = findViewById<MaterialSwitch>(R.id.panicGestureSwitch)
        panicSwitch.isChecked = appPreferences.getBoolean(PREF_PANIC_GESTURE, false)
        panicSwitch.setOnCheckedChangeListener { _, enabled ->
            appPreferences.edit().putBoolean(PREF_PANIC_GESTURE, enabled).apply()
        }
        findViewById<View>(R.id.homeTitle).setOnClickListener {
            if (appPreferences.getBoolean(PREF_PANIC_GESTURE, false)) {
                // The title is the quick-wipe gesture while that is on: no sheet in its way.
                if (panicTapDetector.onTap(SystemClock.elapsedRealtime())) startPanicWipeCountdown()
            } else {
                showStatusSheet()
            }
        }
        bindSettingsPages()
        bindMainNav()
        // "Relay for others" on the Watch screen mirrors the Settings switch (same preference).
        watchRelaySwitch.isChecked = forwardReceivedStreams
        watchRelaySwitch.setOnCheckedChangeListener { _, enabled ->
            if (enabled != forwardReceivedStreams) forwardReceivedStreamsSwitch.isChecked = enabled
        }
        applyExperimentsHint()
        updateHomeActions()
        updateHomeReadiness()
        // Empty states lead somewhere (skill: "turn empty states into opportunities with a CTA").
        findViewById<View>(R.id.streamEmptyView).apply {
            findViewById<ImageView>(R.id.emptyStateIcon).setImageResource(R.drawable.ic_live)
            findViewById<TextView>(R.id.emptyStateTitle).setText(R.string.watch_empty_title)
            findViewById<TextView>(R.id.emptyStateText).setText(R.string.watch_empty_text)
            findViewById<View>(R.id.emptyStateAction).visibility = View.GONE
        }
        bindCallEmptyState()
        GattPoolBackoff.onScannerChanged = { runOnUiThread { if (!isFinishing && !isDestroyed) bindCallEmptyState() } }
        bindBrowserStatus(
            receiverBrowserStatus,
            streamList,
            setOf("No nearby streams yet. Scanning continues.", "Scanning for nearby streams…", getString(R.string.watch_scanning)),
        )
        bindBrowserStatus(
            callBrowserStatus,
            callPeerList,
            setOf(getString(R.string.people_starting), "No available people yet. Scanning continues."),
        )
    }

    private fun applyExperimentsHint() {
        if (::experimentsHiddenHint.isInitialized) {
            // Design decision 2026-11-06 (GAP-AUDIT-2 Q2): the Experiments rows no longer wait for Manual video settings.
            experimentsHiddenHint.visibility = View.VISIBLE
        }
    }

    /** Readiness card: Bluetooth support, permissions and Bluetooth state, with one action. */
    private fun updateHomeReadiness() {
        if (!::homeReadinessTitle.isInitialized) return
        val adapter = bluetoothAdapter
        val permissionsGranted = RuntimePermissionPlan.readiness(Build.VERSION.SDK_INT, DebugTunables.callReadinessPermissions)
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        val bluetoothOn = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        val state = homeReadiness(adapter != null, permissionsGranted, bluetoothOn)
        val (icon, tint, title, text) = when (state) {
            HomeReadiness.UNSUPPORTED -> HomeCard(R.drawable.ic_bluetooth_disabled, R.color.danger,
                R.string.readiness_unsupported_title, R.string.readiness_unsupported_text)
            HomeReadiness.PERMISSIONS_NEEDED -> HomeCard(R.drawable.ic_warning, R.color.warning,
                R.string.readiness_permissions_title, R.string.readiness_permissions_text)
            HomeReadiness.BLUETOOTH_OFF -> HomeCard(R.drawable.ic_bluetooth_disabled, R.color.warning,
                R.string.readiness_off_title, R.string.readiness_off_text)
            HomeReadiness.READY -> HomeCard(R.drawable.ic_bluetooth, R.color.success,
                R.string.readiness_ready_title, R.string.readiness_ready_text)
        }
        homeReadinessIcon.setImageResource(icon)
        homeReadinessIcon.imageTintList = ContextCompat.getColorStateList(this, tint)
        homeReadinessTitle.setText(title)
        if (state == HomeReadiness.READY) {
            // "Visible to nearby phones" is true only while the call endpoint really advertises.
            homeReadinessText.setText(
                when (homeReachability(currentReachabilityPlan(), privateCallAvailable, privateCallAvailabilityStarting)) {
                    HomeReachability.REACHABLE -> R.string.readiness_reachable_text
                    HomeReachability.STARTING -> R.string.readiness_reachable_starting_text
                    HomeReachability.NOT_REACHABLE -> R.string.readiness_not_reachable_text
                },
            )
        } else {
            homeReadinessText.setText(text)
        }
        homeReadinessAction.visibility = when (state) {
            HomeReadiness.PERMISSIONS_NEEDED, HomeReadiness.BLUETOOTH_OFF -> View.VISIBLE
            else -> View.GONE
        }
        homeReadinessAction.setText(
            when {
                state == HomeReadiness.BLUETOOTH_OFF -> R.string.readiness_off_action
                homePermissionsAsked -> R.string.readiness_permissions_settings_action
                else -> R.string.readiness_permissions_action
            },
        )
        homeReadinessAction.tag = state
        // Experimental `test_calls_unavailable_status`: Home says calls cannot reach the phone and offers Bluetooth settings.
        val callsUnavailable = if (state == HomeReadiness.READY) callsUnavailableNow() else null
        if (callsUnavailable != null) {
            homeReadinessIcon.setImageResource(R.drawable.ic_warning)
            homeReadinessIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.white)
            homeReadinessTitle.setText(R.string.status_calls_unavailable)
            homeReadinessText.setText(callsUnavailable.valueRes)
            homeReadinessAction.visibility = View.VISIBLE
            homeReadinessAction.setText(R.string.action_bluetooth_settings)
            homeReadinessAction.tag = CallsUnavailableAction.BLUETOOTH_SETTINGS
        }
        updateStatusIndicators()
        val deepSleep = runCatching { DistributionUiBackend.current.deepSleepBanner(this) }.getOrNull()
        findViewById<TextView>(R.id.homeDeepSleepBanner).apply {
            setText(deepSleep)
            visibility = if (deepSleep != null) View.VISIBLE else View.GONE
        }
    }

    private data class HomeCard(val icon: Int, val tint: Int, val title: Int, val text: Int)

    private fun onHomeReadinessAction() {
        if (homeReadinessAction.tag == CallsUnavailableAction.BLUETOOTH_SETTINGS) {
            openBluetoothSettings()
            return
        }
        when (homeReadinessAction.tag as? HomeReadiness) {
            HomeReadiness.BLUETOOTH_OFF ->
                runCatching { homeBluetoothEnableRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            HomeReadiness.PERMISSIONS_NEEDED -> if (homePermissionsAsked) {
                runCatching {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", packageName, null)),
                    )
                }
            } else {
                homePermissionRequest.launch(RuntimePermissionPlan.readiness(Build.VERSION.SDK_INT, DebugTunables.callReadinessPermissions).toTypedArray())
            }
            else -> Unit
        }
    }

    private fun updateHomeActions() {
        if (!::homeIdentityText.isInitialized) return
        val context = applicationContext
        Thread {
            val fingerprint = SecurityUiBackend.current.ownFingerprint(context)
            runOnUiThread {
                if (fingerprint != null && ::homeIdentityText.isInitialized) {
                    homeIdentityText.text = getString(R.string.home_identity_text_id, IdentityHandle.fromPrefixHex(fingerprint.take(12)))
                }
            }
        }.start()
    }

    /** Push-to-talk tile: the existing experimental PTT switches, then the people list. */
    /** The (hidden) Home row explains how talking works in a call: hold Mute while muted. */
    private fun onHomePushToTalk() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_mic)
            .setTitle(R.string.ptt_enable_title)
            .setMessage(R.string.ptt_enable_message)
            .setPositiveButton(R.string.ptt_enable_confirm, null)
            .showTracked()
    }

    private fun loadIdentityIntoSettings() {
        refreshIdentityName()
        refreshReliabilityCard()
        val apkInfo = runCatching { DistributionUiBackend.current.apkInfo(this) }.getOrNull()
        findViewById<TextView>(R.id.shareAppInfoText).apply {
            text = apkInfo
            visibility = if (apkInfo != null) View.VISIBLE else View.GONE
        }
    }

    private fun refreshReliabilityCard() {
        val container = findViewById<ViewGroup>(R.id.reliabilityContainer)
        val summary = findViewById<TextView>(R.id.reliabilitySummary)
        while (container.childCount > 1) container.removeViewAt(1)
        val issues = runCatching { ReliabilityUiBackend.current.issues(this) }.getOrDefault(emptyList())
        if (issues.isEmpty()) {
            summary.setText(R.string.reliability_ok)
            summary.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_check_circle, 0, 0, 0)
            summary.compoundDrawableTintList = ContextCompat.getColorStateList(this, R.color.success)
            return
        }
        summary.text = resources.getQuantityString(R.plurals.reliability_issues_count, issues.size, issues.size)
        summary.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_warning, 0, 0, 0)
        summary.compoundDrawableTintList = ContextCompat.getColorStateList(this, R.color.warning)
        // One compact card per issue: title, one line of why, and Fix when Android can open the setting (2026-10-17).
        val inflater = layoutInflater
        issues.forEach { issue ->
            val card = inflater.inflate(R.layout.item_reliability_issue, container, false)
            card.findViewById<TextView>(R.id.reliabilityIssueTitle).text = issue.title
            val intent = issue.settingsIntent
            // With a Fix button the manual "Settings > Apps > ..." steps are redundant: keep the first sentence.
            card.findViewById<TextView>(R.id.reliabilityIssueText).apply {
                text = if (intent != null) ReliabilityText.firstSentence(issue.guidance) else issue.guidance
                // Two lines; a tap shows the whole explanation.
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener { maxLines = if (maxLines == 2) Int.MAX_VALUE else 2 }
            }
            card.findViewById<View>(R.id.reliabilityIssueFix).apply {
                visibility = if (intent != null) View.VISIBLE else View.GONE
                setOnClickListener { runCatching { startActivity(intent) } }
            }
            container.addView(card)
        }
    }

    private fun exportDiagnostics() {
        val context = applicationContext
        Thread {
            val report = runCatching { DiagnosticsUiBackend.current.build(context) }
                .getOrElse { CrashReports.diagnosticsFallback(it, CrashReports.dir(context)) }
            runOnUiThread {
                getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
                    android.content.ClipData.newPlainText(getString(R.string.help_export_subject), report),
                )
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.help_export_subject))
                    .putExtra(Intent.EXTRA_TEXT, report)
                runCatching { startActivity(Intent.createChooser(send, getString(R.string.help_export))) }
            }
        }.start()
    }

    /** Settings wipe: the user types WIPE; the button stays disabled until they do. */
    private fun confirmEmergencyWipe() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.wipe_type_hint)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val frame = android.widget.FrameLayout(this).apply {
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_warning)
            .setTitle(R.string.wipe_confirm_title)
            .setMessage(R.string.wipe_confirm_message)
            .setView(frame)
            .setPositiveButton(R.string.wipe_confirm_action) { _, _ ->
                performEmergencyWipe(panic = false, confirmation = input.text?.toString()?.trim())
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showTracked()
        val erase = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        erase.isEnabled = false
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                erase.isEnabled = s?.toString()?.trim() == WIPE_CONFIRMATION_TEXT
            }
        })
    }

    /** Quick wipe from the home-title gesture: a 3-second Undo, then the same wipe. */
    private fun startPanicWipeCountdown() {
        if (pendingPanicWipe != null) return
        val wipe = Runnable {
            pendingPanicWipe = null
            performEmergencyWipe(panic = true, confirmation = null)
        }
        pendingPanicWipe = wipe
        sessionHandler.postDelayed(wipe, PANIC_WIPE_DELAY_MS)
        com.google.android.material.snackbar.Snackbar
            .make(findViewById<View>(R.id.main), R.string.wipe_panic_countdown, PANIC_WIPE_DELAY_MS.toInt())
            .setAction(R.string.wipe_panic_undo) {
                sessionHandler.removeCallbacks(wipe)
                pendingPanicWipe = null
                Log.i("BluetoothVideo", "Emergency wipe: panic gesture undone")
            }
            .show()
    }

    private fun performEmergencyWipe(panic: Boolean, confirmation: String?) {
        Log.i("BluetoothVideo", "Emergency wipe requested: ${if (panic) "panic_gesture" else "settings"}")
        // Onboarding comes back on the next launch (the wipe normally ends the process).
        OnboardingActivity.reset(this)
        val context = applicationContext
        Thread {
            val error = runCatching { SecurityUiBackend.current.wipe(context, panic, confirmation) }
                .getOrElse { it.message ?: it.javaClass.simpleName }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (error != null) {
                    OnboardingActivity.markDone(this)
                    Toast.makeText(this, getString(R.string.wipe_failed, error), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                startActivity(
                    Intent(this, OnboardingActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
                finish()
            }
        }.start()
    }

    /** In-call chip: encryption, route and verification of the end-to-end peer. */
    private fun updateCallSecurityChip() {
        if (!::callSecurityChip.isInitialized) return
        val endpoint = privateCall
        val peer = endpoint?.endToEndPeerNodeId
        if (endpoint == null || peer == null) {
            callSecurityChip.visibility = View.GONE
            return
        }
        val route = callRoute(endpoint.adjacentPeerLabel, peer, privateCallDialledRelays)
        val routeText = when (route) {
            is CallRoute.Relayed -> when (route.relays) {
                null -> getString(R.string.call_security_relayed_unknown)
                1 -> getString(R.string.call_security_relayed_one)
                else -> getString(R.string.call_security_relayed, route.relays)
            }
            CallRoute.Direct -> getString(R.string.call_security_direct)
            null -> getString(R.string.call_security_encrypted)
        }
        val context = applicationContext
        Thread {
            val state = runCatching { SecurityUiBackend.current.verificationState(context, peer) }
                .getOrDefault(ContactVerification.UNVERIFIED)
            runOnUiThread {
                if (privateCall !== endpoint) return@runOnUiThread
                val stateText = getString(
                    when (state) {
                        ContactVerification.VERIFIED -> R.string.verification_verified
                        ContactVerification.UNVERIFIED -> R.string.verification_unverified
                        ContactVerification.MISMATCH -> R.string.verification_mismatch
                    },
                )
                callSecurityChip.text = "$routeText. $stateText"
                callSecurityChip.contentDescription = getString(R.string.call_security_description, routeText, stateText)
                val mismatch = state == ContactVerification.MISMATCH
                callSecurityChip.setBackgroundResource(
                    if (mismatch) R.drawable.mismatch_banner_background else R.drawable.call_overlay_background,
                )
                callSecurityChip.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    when (state) {
                        ContactVerification.VERIFIED -> R.drawable.ic_verified_user
                        ContactVerification.UNVERIFIED -> R.drawable.ic_lock
                        ContactVerification.MISMATCH -> R.drawable.ic_warning
                    },
                    0, 0, 0,
                )
                // The text stays off the call screen (it is the first line of the call info sheet);
                // only the small lock shows, red when the identity does not match.
                callSecurityChip.visibility = View.GONE
                callLockButton.visibility = if (mismatch) View.VISIBLE else View.GONE
                callLockButton.contentDescription = callSecurityChip.contentDescription
                callLockButton.setImageResource(if (mismatch) R.drawable.ic_warning else R.drawable.ic_lock)
                callLockButton.setBackgroundResource(
                    if (mismatch) R.drawable.mismatch_banner_background else R.drawable.call_overlay_background,
                )
                if (mismatch && !isFinishing && !isDestroyed) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setIcon(R.drawable.ic_warning)
                        .setTitle(R.string.call_mismatch_title)
                        .setMessage(R.string.call_mismatch_message)
                        .setPositiveButton(R.string.action_close, null)
                        .showTracked()
                }
            }
        }.start()
    }

    /** Link health: connection, route, requested vs current video, security; read-only. */
    private fun showLinkHealthSheet() {
        val endpoint = privateCall ?: return
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_link_health, null)
        val settings = privateCallSettings
        val limitBps = privateCallLinkRateLimitBps
        var wifiNotUsedAt = -1
        val rows = mutableListOf<String>().apply {
            callSecurityChip.text?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
            val carrier = endpoint.effectiveCarrier
            if (!carrier.isWifi) wifiNotUsedAt = size + 1   // right under the Connection line (filled in below when a reason is known)
            add(
                when {
                    !carrier.isWifi -> getString(R.string.link_health_connection, currentCallTransport.shortLabel)
                    endpoint.bluetoothCarrierAlive -> getString(R.string.link_health_connection_wifi, currentCallTransport.shortLabel)
                    else -> getString(R.string.link_health_connection_wifi_only)
                },
            )
            callProfileText.text?.toString()?.takeIf { it.isNotBlank() }?.let { addAll(it.lines()) }
            if (settings != null) {
                add(
                    getString(
                        R.string.link_health_video,
                        settings.resolution.width,
                        settings.resolution.height,
                        settings.fps,
                        settings.bitRate / 1_000,
                    ),
                )
                if (limitBps != null) add(getString(R.string.link_health_limit, limitBps / 1_000))
            }
            privateCallActiveAudioBitRate?.let { add(getString(R.string.link_health_audio, it / 1_000)) }
            if (privateCallLinkDetails.isNotEmpty()) {
                add("")
                add(getString(R.string.link_health_details))
                addAll(privateCallLinkDetails)
            }
        }
        val rowsView = view.findViewById<TextView>(R.id.linkHealthRows)
        rowsView.text = rows.joinToString("\n")
        if (wifiNotUsedAt >= 0) {
            // "Wi-Fi not used: <reason>" from the app's own Wi-Fi eligibility verdict (UI-W7). Read off the main thread: it asks the platform.
            Thread {
                val reason = runCatching {
                    val facts = WifiRadio.facts()
                    WifiNotUsed.reason(facts, WifiEligibility.decide(facts), endpoint.wifiPeerView())
                }.getOrNull()
                if (reason != null) runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    rows.add(wifiNotUsedAt.coerceAtMost(rows.size), getString(R.string.link_health_wifi_not_used, getString(reason.res)))
                    rowsView.text = rows.joinToString("\n")
                }
            }.start()
        }
        view.findViewById<View>(R.id.linkHealthVerify).setOnClickListener {
            sheet.dismiss()
            openCallVerification()
        }
        view.findViewById<View>(R.id.linkHealthCopy).setOnClickListener {
            val context = applicationContext
            val callText = rows.joinToString("\n")
            Thread {
                val report = runCatching { DiagnosticsUiBackend.current.build(context) }.getOrDefault("")
                runOnUiThread {
                    getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            getString(R.string.help_export_subject),
                            "$report\ncall:\n$callText",
                        ),
                    )
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
        if (privateCall === endpoint) {
            sheet.setContentView(view)
            sheet.show()
        }
    }

    private fun openCallVerification() {
        if (privateCall?.peerStillPseudonym == true) {
            // BMH3 pseudonyms: nothing to verify until the callee answered and its identity was confirmed.
            Toast.makeText(this, R.string.call_verify_after_reveal, Toast.LENGTH_LONG).show()
            return
        }
        val peer = privateCall?.endToEndPeerNodeId ?: return
        startActivity(VerifyContactActivity.intent(this, peer, privateCallPartyName, inCall = true))
    }

    /** Caller card before media: [incoming] ringing or this phone calling. */
    private fun showCallParty(name: String, stateRes: Int) {
        if (!::callPartyPanel.isInitialized) return
        privateCallPartyName = name
        callPartyName.text = name
        callPartyState.setText(stateRes)
        callPartyPanel.visibility = View.VISIBLE
        // One name line and one state line, like a phone app: no extra status bubble.
        callStatusText.visibility = View.GONE
    }

    /**
     * What the current call's screen names: the real name provider, and for an incoming call the label the
     * state line comes from. Null when no call is on screen.
     */
    private var callRealName: (() -> String)? = null
    /** Q15: the "Verified contacts couldn't be loaded" banner on the home screen. */
    private var verifiedStoreBanner: VerifiedStoreBannerController? = null
    private var callIncomingLabel: String? = null

    /**
     * Starts keeping the call screen's names in step with the lock state (Q16-A): the real name while the
     * phone is unlocked, "Encrypted call" over a secure lock screen, and back again after an unlock or a
     * relock. [incomingLabel] is the caller's 12-hex label for an incoming call, else null.
     */
    private fun watchCallPeerName(incomingLabel: String?, realName: () -> String) {
        callRealName = realName
        callIncomingLabel = incomingLabel
        callLockScreen.watchLockState { applyCallPeerName() }
        applyCallPeerName()
    }

    private fun applyCallPeerName() {
        val real = callRealName ?: return
        if (privateCall == null) return
        val masked = callLockScreen.namesMasked()
        val shown = if (masked) getString(R.string.call_name_hidden_locked) else real()
        intercomCallUi.setPeerLabel(shown)
        if (::callPartyPanel.isInitialized) {
            privateCallPartyName = shown
            callPartyName.text = shown
        }
        val incoming = callIncomingLabel
        val ringing = privateCallPhase == null || privateCallPhase == PrivateCallPhase.INCOMING_RINGING
        if (incoming != null && ringing && ::callPartyState.isInitialized) {
            if (masked) {
                callPartyState.setText(R.string.call_name_hidden_locked_hint)
            } else {
                callPartyState.text = incomingStateText(incoming, video = true)
                findViewById<View>(R.id.declineAndBlockButton)?.visibility =
                    if (privateCall?.incomingCaller()?.pseudonymous == true) View.GONE else View.VISIBLE
                findViewById<View>(R.id.declineAndReportButton)?.visibility =
                    if (DebugTunables.localReporting && privateCall?.incomingCaller()?.pseudonymous != true) View.VISIBLE else View.GONE
            }
        }
    }

    private fun clearCallPeerName() {
        callRealName = null
        callIncomingLabel = null
    }

    /** On air: the live dot blinks once a second (the one deliberate motion; off with animations off). */
    private var liveDotAnimator: android.animation.ObjectAnimator? = null

    private fun setLiveDotBlinking(on: Boolean) {
        if (!::connectionStatusDot.isInitialized) return
        if (on && liveDotAnimator == null) {
            liveDotAnimator = android.animation.ObjectAnimator.ofFloat(connectionStatusDot, View.ALPHA, 1f, 0.25f).apply {
                duration = 500L
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        } else if (!on) {
            liveDotAnimator?.cancel()
            liveDotAnimator = null
            connectionStatusDot.alpha = 1f
        }
    }

    private fun hideCallParty() {
        if (::callPartyPanel.isInitialized) callPartyPanel.visibility = View.GONE
    }

    /** Live/watch chip on the shared video screen; the dot keeps its earlier colour and description. */
    /** The top bar of the video screens: title, viewers, LIVE badge, timer (broadcasting only). */
    private fun updateLiveTopBar(state: LiveChipState) {
        val live = state == LiveChipState.LIVE || state == LiveChipState.PAUSED
        (broadcastStatusText.parent as View).visibility = if (state == LiveChipState.NOT_LIVE) View.GONE else View.VISIBLE
        connectionStatusDot.visibility =
            if (live || state == LiveChipState.WATCHING) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.broadcastTitleText).apply {
            when (sessionRole) {
                SessionRole.SEND -> setText(if (live) R.string.broadcast_title_live else R.string.broadcast_title_idle)
                SessionRole.RECEIVE -> text = watchedStreamTitle()
                SessionRole.RELAY -> setText(R.string.broadcast_title_relay)
            }
        }
        findViewById<TextView>(R.id.broadcastSubtitleText).apply {
            if (sessionRole == SessionRole.SEND && live) {
                val viewers = getString(
                    if (state == LiveChipState.PAUSED) R.string.live_status_paused else R.string.live_viewers_count,
                    lastConnectedCount,
                )
                // Your own stream title, when you set one, goes under "You are live".
                val ownTitle = PeerLabelFeature.localStreamTitle?.takeIf { it.isNotBlank() }
                text = if (ownTitle != null) "$ownTitle \u00b7 $viewers" else viewers
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        val timer = findViewById<android.widget.Chronometer>(R.id.broadcastTimer)
        if (sessionRole == SessionRole.SEND && live) {
            if (!broadcastTimerRunning) {
                timer.base = SystemClock.elapsedRealtime()
                timer.start()
                broadcastTimerRunning = true
            }
            timer.visibility = View.VISIBLE
        } else {
            timer.stop()
            broadcastTimerRunning = false
            timer.visibility = View.GONE
        }
    }

    /**
     * The title of the stream being watched: its signed title, else the title it advertises (shown as "~Title", not yet
     * confirmed), else the broadcaster's name from the naming precedence function (contact label, "~Name", generated
     * name, else "Someone nearby"). Never a raw handle.
     */
    private fun watchedStreamTitle(): String {
        val source = activeStreamSourceNodeId?.toString()?.take(12)
        val label = source?.let { PeerLabels.forPeer(it, contactNames[it]) }
        label?.streamTitle?.takeIf { label.streamTitleConfirmed }?.let { return it }
        val token = selectedStreamPeer?.announcement?.streamToken
        if (PeerLabelFeature.titlePagesEnabled && token != null) {
            StreamTitles.board.title(token)?.let { return it } // shown plainly (design decision 2026-10-17)
        }
        // A stranger's generated name comes from the offer's current rotating ID, as in the Live list.
        val offer = selectedStreamPeer?.takeIf { it.announcement.rotating }
        val generated = offer?.let { rotatingGeneratedNames(listOf(it))[it.announcement.nodeIdPrefix.toHexKey()] }
        return label?.let { peerRowName(it, generated) } ?: generated ?: getString(R.string.watch_title_live)
    }

    private fun updateBroadcastStatusChip() {
        if (!::broadcastStatusText.isInitialized) return
        val streaming = streamArmed || sender != null || broadcaster != null
        val state = liveChipState(sessionRole.name, streaming, streamPaused, lastConnectedCount)
        broadcastStatusText.text = when (state) {
            LiveChipState.NOT_LIVE -> getString(R.string.live_status_off)
            LiveChipState.LIVE -> getString(R.string.badge_live)
            LiveChipState.PAUSED -> getString(R.string.badge_paused)
            LiveChipState.WATCH_WAITING -> getString(R.string.watch_status_waiting)
            LiveChipState.WATCHING -> getString(R.string.badge_live)
            LiveChipState.RELAY_WAITING -> getString(R.string.relay_status_off)
            LiveChipState.RELAYING -> getString(R.string.relay_status_on)
        }
        // Viewer frame age: never "Watching ..." over a frozen picture.
        if (sessionRole == SessionRole.RECEIVE) viewerFrameAge?.chipText()?.let { broadcastStatusText.text = it }
        setLiveDotBlinking(state == LiveChipState.LIVE)
        updateLiveTopBar(state)
        // Watching: whose stream this is (authenticated source) and whether it is verified.
        val source = activeStreamSourceNodeId?.toString()
        if (state == LiveChipState.WATCHING && source != null && activeStreamSourceAuthenticated) {
            val cached = streamSourceVerification?.takeIf { it.first == source }?.second
            if (cached == null) {
                if (streamSourceLookup != source) {
                    streamSourceLookup = source
                    val context = applicationContext
                    Thread {
                        val result = runCatching { SecurityUiBackend.current.verificationState(context, source) }
                            .getOrDefault(ContactVerification.UNVERIFIED)
                        runOnUiThread {
                            streamSourceLookup = null
                            streamSourceVerification = source to result
                            if (!isDestroyed) updateBroadcastStatusChip()
                        }
                    }.start()
                }
                streamSecurityText.visibility = View.GONE
                return
            }
            val verification = cached
            val label = getString(
                when (verification) {
                    ContactVerification.VERIFIED -> R.string.verification_verified
                    ContactVerification.UNVERIFIED -> R.string.verification_unverified
                    ContactVerification.MISMATCH -> R.string.verification_mismatch
                },
            )
            // Encryption is always on: only a lock (tap for details); words appear only on a problem.
            val mismatch = verification == ContactVerification.MISMATCH
            streamSecurityText.text = if (mismatch) getString(R.string.verification_mismatch) else ""
            streamSecurityText.contentDescription = getString(R.string.stream_security, label)
            streamSecurityText.setBackgroundResource(R.drawable.mismatch_banner_background)
            streamSecurityText.setOnClickListener { showStreamInfo() }
            streamSecurityText.visibility = if (mismatch) View.VISIBLE else View.GONE
        } else {
            streamSecurityText.visibility = View.GONE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        @Suppress("NAME_SHADOWING") val intent = SafeLaunchExtras.sanitize(intent)
        // A tap on a Messaging v2 notification opens that conversation (validated against the accepted contacts).
        if (intent.hasExtra(ConversationRoute.EXTRA_CONVERSATION)) {
            openConversationFromIntent(intent)
            return
        }
        if (handleMissedCallBack(intent)) return
        // Experimental CallStyle Answer (CallNotificationActions); a token-less intent is ignored.
        if (intent.hasExtra(CallNotificationActions.EXTRA_ACTION)) {
            onCallNotificationAction(intent)
            return
        }
        if (!BuildConfig.DEBUG) return
        if (EncoderBenchLaunch.requested(intent)) {
            // The bench must start in a process that has opened no radio; a running app is refused, not bridged.
            Log.i(
                "BluetoothVideo",
                "BENCHMARK encoder_bench: event=summary | result=refused | " +
                    "reason=app_already_running: force-stop the app, then launch with test_encoder_bench",
            )
            return
        }
        if (debugEmergencyWipeAuthorized(intent)) {
            EmergencyWipe.wipe(this, WipeReason.DEBUG_CONTROL)
            return
        }
        DebugContactControl.handleIntent(this, intent)
        DebugMeetControl.handleIntent(this, intent)
        debugAudioLiveAutoStart(intent)
        val control = intent.getStringExtra("test_control") ?: return
        val pendingControl = ++debugControlSequence to System.nanoTime()
        if (debugMediaTraceSession != null) Log.i("BluetoothVideo", "BENCHMARK lifecycle v1 " + JSONObject()
            .put("session", debugMediaTraceSession).put("pid", android.os.Process.myPid())
            .put("clock", "System.nanoTime").put("action_id", pendingControl.first).put("action", control)
            .put("kind", "command").put("command_ns", pendingControl.second).toString())
        if (control == "invite" || control.startsWith("call:") ||
            control == "invite_voice" || control.startsWith("voice_call:") || control.startsWith("sf_sync:")
        ) {
            // onResume restarts discovery and clears its old selection. Queue after that restart.
            // invite_voice / voice_call:PREFIX are the experimental voice-call variants.
            pendingDebugPrivateCallInvite = control to if (control == "invite" || control == "invite_voice") {
                intent.getStringExtra("test_call_peer")
            } else control.substringAfter(':')
            debugPendingControl = pendingControl
            return
        }
        val applied = when (control) {
            "pause" -> if (broadcaster != null && !streamPaused) { toggleBroadcastPause(); true } else false
            "resume" -> if (broadcaster != null && streamPaused) { toggleBroadcastPause(); true } else false
            "flip" -> if (debugRawInputFile != null) false
                else if (privateCallPhase == PrivateCallPhase.ACTIVE && privateCallCameraEnabled &&
                privateCallCameraEncoder != null && availableCameraLenses.size > 1
            ) { switchPrivateCallCamera(); true }
                else if (cameraEncoder != null) { switchCamera(); true } else false
            // A benchmark's video call is not turned into voice at critical heat: the answer is refused instead.
            "accept" -> if (privateCall != null && privateCallPhase == PrivateCallPhase.INCOMING_RINGING &&
                debugCallHeatAllows(privateCall?.localCallType ?: CallType.VIDEO)
            ) {
                stopPrivateCallRinging()
                runCatching { acceptPrivateCall() }.isSuccess
            } else false
            "decline" -> if (privateCall != null && privateCallPhase == PrivateCallPhase.INCOMING_RINGING) {
                stopPrivateCallRinging()
                runCatching { privateCall?.decline() }.isSuccess
            } else false
            "cancel" -> if (privateCall != null && privateCallPhase in setOf(
                    PrivateCallPhase.OUTGOING_INVITE, PrivateCallPhase.OUTGOING_RINGING,
                )
            ) { stopPrivateCallRinging(); privateCall?.cancel(); true } else false
            "wifi_switch_off" -> privateCall?.let { it.wifiSwitchOff(); true } ?: false
            "wifi_status" -> privateCall?.let { it.wifiStatus(); true } ?: false
            "mute", "unmute" -> if (privateCall != null && privateCallPhase == PrivateCallPhase.ACTIVE &&
                privateCallMuted != (control == "mute")
            ) { togglePrivateCallMute(); true } else false
            "video_off", "video_on" -> if (privateCall != null && privateCallPhase == PrivateCallPhase.ACTIVE &&
                privateCallCameraEnabled != (control == "video_on")
            ) { togglePrivateCallCamera(); true } else false
            "speaker", "earpiece" -> if (privateCall != null && privateCallPhase == PrivateCallPhase.ACTIVE &&
                privateCallAudioSession?.speakerOn == (control == "earpiece")
            ) togglePrivateCallSpeaker() else false
            "ptt_press", "ptt_release" -> onPrivateCallPushToTalk(control == "ptt_press", "debug")
            // Experimental store-and-forward (needs test_store_forward): pause a running exchange,
            // accept every pending card, or queue a generated message (sf_send:PREFIX12:BYTES).
            "sf_suspend" -> StoreForwardFeature.enabled && privateCall?.suspendMessageSync() == true
            "sf_accept_cards" -> StoreForwardFeature.enabled && debugAcceptStoreForwardCards()
            // Phase 0 (DECISIONS Design M): measurement without taps. sf_status logs counts and the newest
            // received messages, sf_clear empties the message stores (contacts stay), sf_background moves the
            // task to the back (background reception, T-M8).
            "sf_status" -> StoreForwardFeature.enabled && debugStoreForwardStatus()
            "sf_clear" -> StoreForwardFeature.enabled && debugStoreForwardClear()
            "sf_background" -> StoreForwardFeature.enabled && moveTaskToBack(true)
            // Messaging v2 (test_messaging_v2): approve every waiting download, mark incoming read, log the state.
            "sf2_approve" -> MessagingV2Feature.enabled && debugMessagingV2Approve()
            "sf2_read" -> MessagingV2Feature.enabled && debugMessagingV2Read()
            "sf2_status" -> MessagingV2Feature.enabled && debugMessagingV2Status()
            "background" -> if (appScreen == AppScreen.CALL && privateCallPhase == PrivateCallPhase.ACTIVE) {
                moveTaskToBack(true)
            } else false
            "foreground" -> appScreen == AppScreen.CALL && privateCallPhase == PrivateCallPhase.ACTIVE
            "end" -> if (privateCall != null && privateCallPhase in setOf(
                    PrivateCallPhase.NEGOTIATING_MEDIA, PrivateCallPhase.ACTIVE,
                )
            ) { stopPrivateCallRinging(); privateCall?.hangUp(); true }
                else if (broadcaster != null || audioLive.mode) { stopStreaming("Stopped."); true }
                else if (audioLive.listening) { showReceiverBrowser(); true } else false
            // Audio-only live (DEBUG): the listener leaves, the source or the listener mutes and unmutes.
            "leave" -> if (audioLive.listening || receiver != null) { showReceiverBrowser(); true } else false
            "audio_mute", "audio_unmute" -> audioLive.debugSetMuted(control == "audio_mute")
            // Graceful exit before a runner force-stop: onDestroy closes the call endpoint, CoC
            // listeners and discovery. It does not stop the Android 10 LE CoC leak: that stack
            // never stops a closed LE CoC server, gracefully closed or killed ("Error starting
            // l2cap_listen", bmaraw-ab-call-e9c421d9-r1); CocListenerHub bounds it per role.
            "shutdown" -> {
                // CleanShutdown: the end of onDestroy waits until nothing is registered and logs clean_shutdown: done.
                CleanShutdown.begin(intent.getStringExtra(CleanShutdown.EXTRA_NONCE))
                stopPrivateCallRinging()
                privateCall?.hangUp()
                // A force-stop gives the app no callback: Android 10-11 can keep the registration of a scanner that was
                // running when the process died (lane11 ASUS dumpsys). Release them while the process still can.
                val scansStopped = ServiceCleanup.stopScans(ServiceSwitches.stopScansOnExit, ServiceCleanup.Reason.TASK_REMOVED, appVisible = false)
                Log.i("BluetoothVideo", "BENCHMARK service_exit: event=shutdown_control | scans_stopped=$scansStopped")
                finishAndRemoveTask()
                true
            }
            "sf2_chat_close", "sf2_chat_status" -> MessagingV2Feature.enabled && debugMessagingV2Chat(control)
            // Messaging v2 conversation UI: leave the open thread (the screenshots of a run).
            "sf2_thread_close" -> MessagingV2Feature.enabled && threadScreenDelegate.isInitialized() && threadScreen.isOpen &&
                run { threadScreen.close(); true }
            else -> (control.startsWith("sf2_thread:") && MessagingV2Feature.enabled && debugOpenThread(control.substringAfter(':'))) ||
                (control.startsWith("sf_send:") && StoreForwardFeature.enabled && debugStoreForwardSend(control)) ||
                (control.startsWith("sf2_send:") && MessagingV2Feature.enabled && debugMessagingV2Send(control)) ||
                ((control.startsWith("sf2_chat_open:") || control.startsWith("sf2_chat_close:") || control.startsWith("sf2_keys:")) &&
                    MessagingV2Feature.enabled && debugMessagingV2Chat(control))
        }
        Log.i("BluetoothVideo", "BENCHMARK control $control ${if (applied) "applied" else "rejected"}")
        logDebugControlCompletion(control, applied, pendingControl)
    }

    override fun onPostResume() {
        super.onPostResume()
        if (!BuildConfig.DEBUG) return
        val (control, peerPrefix) = pendingDebugPrivateCallInvite ?: return
        pendingDebugPrivateCallInvite = null
        val voice = control == "invite_voice" || control.startsWith("voice_call:")
        val applied = debugInvitePrivateCall(
            peerPrefix,
            if (voice) CallType.VOICE else CallType.VIDEO,
            messageSync = control.startsWith("sf_sync:"),
        )
        Log.i("BluetoothVideo", "BENCHMARK control $control ${if (applied) "applied" else "rejected"}")
        debugPendingControl?.let { logDebugControlCompletion(control, applied, it) }
        debugPendingControl = null
    }

    /**
     * A DEBUG dial is accepted (true) when it starts or waits a bounded time for its target's next sighting; every
     * refusal and every expiry logs `BENCHMARK call_invite: ...` with its reason ([DebugDialWait]). Before this a dial
     * whose address-refresh wait ran out was dropped without a line (lane10 b1b-calls-a15s-s24 audio-a15s-s24-r1).
     */
    private fun debugInvitePrivateCall(
        requestedPrefix: String?,
        callType: CallType = CallType.VIDEO,
        messageSync: Boolean = false,
    ): Boolean {
        if (!BuildConfig.DEBUG) return false
        val node = requestedPrefix?.lowercase(Locale.US) ?: "none"
        fun refuse(reason: String): Boolean {
            Log.i("BluetoothVideo", DebugDialWait.line("refused", node, "reason" to reason))
            return false
        }
        if (callType == CallType.VOICE && !IntercomFeatures.current.voiceCalls) return refuse("voice_calls_off")
        if (messageSync && !StoreForwardFeature.enabled) return refuse("store_forward_off")
        // Experimental automatic sync: a requested call or exchange replaces a pending automatic one,
        // and a running automatic exchange is suspended (this request is rejected; retry after it stops).
        autoSyncPendingKey?.let { cancelAutoSync(it, "debug_invite") }
        if (StoreForwardAutoSyncFeature.enabled && privateCall?.suspendMessageSync() == true) {
            return refuse("message_exchange_suspended_retry_later")
        }
        val prefix = requestedPrefix?.lowercase(Locale.US)?.takeIf { it.matches(Regex("[0-9a-f]{12}")) }
            ?: return refuse("bad_peer_prefix")
        cancelDebugDialWait() // a new dial replaces a waiting one
        val peer = privateCallPeers.singleOrNull { it.announcement.nodeIdPrefix.toHexKey() == prefix }
        val block = debugDialBlockReason()
        val endpointStarting = block == "endpoint_starting" || block == "endpoint_missing"
        if (block != null && !endpointStarting) return refuse(block)
        if (peer == null || endpointStarting) {
            // The target is briefly absent (a lifecycle scan restart, an endpoint still starting): wait for its next
            // sighting, bounded, instead of dropping the dial.
            debugDialWait = DebugDialWait.Pending(prefix, callType, messageSync, SystemClock.elapsedRealtime())
            privateCallDiscoveryHandler.postDelayed(debugDialTimeout, DebugDialWait.WAIT_MS)
            Log.i(
                "BluetoothVideo",
                DebugDialWait.line(
                    "waiting", prefix,
                    "reason" to if (peer == null) "peer_not_listed" else block,
                    "timeoutMs" to DebugDialWait.WAIT_MS,
                ),
            )
            return true
        }
        if (!messageSync && !debugCallHeatAllows(callType)) return refuse("thermal_not_ready")
        startOutgoingPrivateCall(
            peer, callType, messageSync,
            refreshTimeoutMs = DebugDialWait.refreshTimeoutMs(auto = false), debugDial = true,
        )
        return true
    }

    /** Why a DEBUG dial cannot start right now, or null. "endpoint_*" are transient: the dial may wait for them. */
    private fun debugDialBlockReason(): String? = when {
        appScreen != AppScreen.CALL_BROWSER -> "not_on_call_browser"
        privateCall == null -> "endpoint_missing"
        privateCallAvailabilityStarting -> "endpoint_starting"
        privateCallPhase != null -> "call_in_progress"
        pendingPrivateCallPeerKey != null -> "selection_pending"
        else -> null
    }

    private fun cancelDebugDialWait() {
        debugDialWait = null
        privateCallDiscoveryHandler.removeCallbacks(debugDialTimeout)
        dialWaitSnackbar?.dismiss()
        dialWaitSnackbar = null
    }

    private fun expireDebugDialWait() {
        val dial = debugDialWait ?: return
        debugDialWait = null
        dialWaitSnackbar?.dismiss()
        dialWaitSnackbar = null
        // A scanner in registration-failure back-off (GATT/scanner pool exhausted) explains the missing sighting.
        val scanFailure = GattPoolBackoff.scannerFailureCode()
        logCallInvite(
            DebugDialWait.line(
                "failed", dial.prefix, "reason" to DebugDialWait.expiryReason(scanFailure),
                *DebugDialWait.expiryDetails(scanFailure, dial.waitedMs(SystemClock.elapsedRealtime())),
            ),
        )
        // A contact dial: the not-found guidance now, after its wait (they are recognisable, so Open Nearby keeps looking).
        val contact = if (dial.contact) savedContacts.firstOrNull { it.prefixHex == dial.prefix } else null
        if (contact != null && scanFailure == null) {
            showContactNotFound(contact, dial.callType == CallType.VIDEO, ContactReach.Hint.RECOGNISABLE)
            return
        }
        dial.displayName?.let { showDialNotFound(dial.prefix, it, scanFailure) }
    }

    /** `BENCHMARK call_invite:` lines are DEBUG-build evidence for user dials and DEBUG dials alike; release builds log none. */
    private fun logCallInvite(line: String) {
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", line)
    }

    /**
     * DEBUG setup timing: `BENCHMARK call_ui_lag: event= | lagMs=` is how long a call event posted from the endpoint thread
     * waited for the UI thread. Saved runs (2026-10-26) show the remote header handled a median 584 ms (p90 988 ms) after
     * it arrived, while the UI thread started the local microphone (`test_audio_off_main` is the A/B for that).
     */
    private fun logCallUiLag(event: String, postedAtNs: Long) {
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK call_ui_lag: event=$event | lagMs=${(System.nanoTime() - postedAtNs) / 1_000_000L}")
    }

    /** The user dial ran out of time: a visible "not found nearby" message with the reason, kept while sightings refresh the line. */
    private fun showDialNotFound(key: String, name: String, scanFailure: Int?) {
        if (appScreen != AppScreen.CALL_BROWSER) return
        val text = DebugDialWait.notFoundStatus(name, scanFailure)
        dialFailureNotice = text
        dialFailureNoticeKey = key
        dialFailureNoticeAtMs = SystemClock.elapsedRealtime()
        callsSnackbar(text)
    }

    /** Status of a dial that waits for, or just failed to find, its person; null when no dial needs the line. */
    private fun dialStatusOverride(): String? {
        debugDialWait?.displayName?.let { return DebugDialWait.lookingStatus(it) }
        if (pendingPrivateCallPeerKey != null) pendingPrivateCallPeerName?.let { return DebugDialWait.lookingStatus(it) }
        return dialFailureNotice?.takeIf { DebugDialWait.noticeVisible(dialFailureNoticeAtMs, SystemClock.elapsedRealtime()) }
    }

    /** A sighting of the phone a waiting DEBUG dial is for: the call starts now, from this fresh result. */
    private fun resumeDebugDialWait(dial: DebugDialWait.Pending, peer: BleL2capPeer) {
        val waitedMs = dial.waitedMs(SystemClock.elapsedRealtime())
        if (dial.expired(SystemClock.elapsedRealtime())) {
            expireDebugDialWait()
            return
        }
        val block = debugDialBlockReason()
        if (block != null) {
            if (block == "endpoint_starting" || block == "endpoint_missing") return // keep waiting for the endpoint
            cancelDebugDialWait()
            logCallInvite(DebugDialWait.line("failed", dial.prefix, "reason" to block, "waitedMs" to waitedMs))
            return
        }
        cancelDebugDialWait()
        if (!dial.messageSync && !debugCallHeatAllows(dial.callType)) {
            logCallInvite(DebugDialWait.line("failed", dial.prefix, "reason" to "thermal_not_ready", "waitedMs" to waitedMs))
            return
        }
        logCallInvite(DebugDialWait.line("peer_seen", dial.prefix, "waitedMs" to waitedMs))
        pendingPrivateCallType = dial.callType
        pendingPrivateCallMessageSync = dial.messageSync && StoreForwardFeature.enabled
        connectOutgoingPrivateCall(peer)
    }

    /** DEBUG: accepts every pending store-and-forward card and opens messages that waited for it. */
    private fun debugAcceptStoreForwardCards(): Boolean {
        if (!BuildConfig.DEBUG) return false
        val node = runCatching { StoreForwardRuntime.node(applicationContext) }.getOrNull() ?: return false
        val pending = node.contacts.all().filter { it.state == ContactState.PENDING }
        pending.forEach { contact ->
            if (runCatching { node.contacts.accept(contact.nodeId, "") }.isFailure) return@forEach
            val opened = node.openPendingFrom(contact.nodeId)
            Log.i("BluetoothVideo", "BENCHMARK store_forward accepted node=${contact.nodeId} opened=${opened.size}")
        }
        return pending.isNotEmpty()
    }

    private fun debugMessagingV2Engine(): MessagingV2Engine? =
        if (!BuildConfig.DEBUG) null else runCatching { StoreForwardRuntime.node(applicationContext).v2 }.getOrNull()

    /** DEBUG: `sf2_send:PREFIX12:BYTES[:COUNT]` queues COUNT (1 to 20) Messaging v2 messages: text up to 8000 bytes, else a generated file. */
    private fun debugMessagingV2Send(control: String): Boolean {
        val engine = debugMessagingV2Engine() ?: return false
        val parts = control.split(':')
        val prefix = parts.getOrNull(1)?.lowercase(Locale.US) ?: return false
        val size = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..MessagingV2Format.MAX_FILE_BYTES.toInt() } ?: return false
        val count = if (parts.size > 3) parts[3].toIntOrNull()?.takeIf { it in 1..20 } ?: return false else 1
        val node = runCatching { StoreForwardRuntime.node(applicationContext) }.getOrNull() ?: return false
        val contact = node.contacts.accepted().singleOrNull { it.nodeId.toString().startsWith(prefix) } ?: return false
        // Sealing a 64 MB file takes seconds: off the main thread. The control is answered "applied" once the request is valid.
        Thread {
            runCatching { MessagingV2Debug.send(engine, contact.nodeId, size, count) }.onSuccess { sent ->
                sent.forEach {
                    Log.i("BluetoothVideo", "BENCHMARK message_v2 queued id=${it.messageIdHex.take(16)} bytes=$size kind=${if (it.attachment != null) "file" else "text"} " +
                        "sha256=${it.attachment?.sha256?.sfHex() ?: "-"} to=${contact.nodeId}")
                }
            }.onFailure { Log.w("BluetoothVideo", "BENCHMARK message_v2 send failed: ${it.message}") }
        }.apply { name = "DebugMessagingV2Send"; isDaemon = true }.start()
        return true
    }

    private fun debugMessagingV2Approve(): Boolean {
        val engine = debugMessagingV2Engine() ?: return false
        Log.i("BluetoothVideo", "BENCHMARK message_v2 approved=${MessagingV2Debug.approveAll(engine)}")
        return true
    }

    private fun debugMessagingV2Read(): Boolean {
        val engine = debugMessagingV2Engine() ?: return false
        Log.i("BluetoothVideo", "BENCHMARK message_v2 read=${MessagingV2Debug.readAll(engine)}")
        return true
    }

    private fun debugMessagingV2Status(): Boolean {
        if (!BuildConfig.DEBUG) return false
        val node = runCatching { StoreForwardRuntime.node(applicationContext) }.getOrNull() ?: return false
        MessagingV2Debug.statusLines(node).forEach { Log.i("BluetoothVideo", it) }
        return node.v2 != null
    }

    /** DEBUG: logs the message stores' counts and the newest received messages (phase 0 measurement). */
    private fun debugStoreForwardStatus(): Boolean {
        if (!BuildConfig.DEBUG) return false
        val node = runCatching { StoreForwardRuntime.node(applicationContext) }.getOrNull() ?: return false
        Log.i("BluetoothVideo", StoreForwardDebug.statusLine(node, MessageAppState.describe(applicationContext)))
        StoreForwardDebug.inboxLines(node).forEach { Log.i("BluetoothVideo", it) }
        return true
    }

    /** DEBUG: removes every own, inbox and archived message so the next measurement starts empty. */
    private fun debugStoreForwardClear(): Boolean {
        if (!BuildConfig.DEBUG) return false
        val node = runCatching { StoreForwardRuntime.node(applicationContext) }.getOrNull() ?: return false
        Log.i("BluetoothVideo", "BENCHMARK store_forward cleared removed=${StoreForwardDebug.clearMessages(node)}")
        return true
    }

    /**
     * DEBUG: `sf_send:PREFIX12:BYTES[:COUNT]` queues COUNT (default 1, at most 50) generated messages of BYTES
     * to the accepted contact.
     */
    private fun debugStoreForwardSend(control: String): Boolean {
        if (!BuildConfig.DEBUG) return false
        val parts = control.split(':')
        val prefix = parts.getOrNull(1)?.lowercase(Locale.US) ?: return false
        val size = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..BundleMessage.MAX_BODY_BYTES } ?: return false
        val count = if (parts.size > 3) parts[3].toIntOrNull()?.takeIf { it in 1..50 } ?: return false else 1
        val node = runCatching { StoreForwardRuntime.node(applicationContext) }.getOrNull() ?: return false
        val contact = node.contacts.accepted().singleOrNull { it.nodeId.toString().startsWith(prefix) } ?: return false
        val kind = when {
            size <= 2_000 -> MessageKind.TEXT
            size <= 400_000 -> MessageKind.PHOTO
            else -> MessageKind.VIDEO
        }
        repeat(count) {
            val body = if (kind == MessageKind.TEXT) {
                ByteArray(size) { ('a'.code + it % 26).toByte() }
            } else {
                ByteArray(size).also(java.security.SecureRandom()::nextBytes)
            }
            val stored = runCatching {
                node.send(
                    contact.nodeId,
                    BundleMessage(
                        kind, System.currentTimeMillis(), ByteArray(16), null,
                        if (kind == MessageKind.TEXT) "text/plain" else "application/octet-stream", body,
                    ),
                )
            }.getOrElse {
                Log.w("BluetoothVideo", "BENCHMARK store_forward send failed: ${it.message}")
                return false
            }
            Log.i(
                "BluetoothVideo",
                "BENCHMARK store_forward queued id=${stored.idHex} kind=${kind.label} bytes=$size " +
                    "chunks=${stored.chunkCount} to=${contact.nodeId}",
            )
        }
        return true
    }

    private fun applyDebugLaunchConfiguration() {
        if (!BuildConfig.DEBUG) return
        // AUDIO-LIVE hook (DEBUG): a UI-started audio broadcast honours test_transport, so CoC can be tested
        // without an auto-start flag. The choice is for this launch only and is never saved as a preference.
        if (AudioLiveFeatures.enabled && intent.hasExtra("test_transport")) {
            debugTransportOverride(intent.getStringExtra("test_transport"))?.let {
                outgoingTransport = it
                audioTransportForced = true
                Log.i("BluetoothVideo", "BENCHMARK audio_live_transport: forced=${it.name} | source=test_transport")
            }
        }
        require(debugRawInputFile == null || debugReplayPath == null) {
            "Raw encoder motion and preencoded IVF replay are separate benchmark sources"
        }
        debugRawInputFile?.let { Log.i("BluetoothVideo", "Test raw encoder input: ${it.path}; physical camera motion unmeasured") }
        // Raw input is accepted for the whole session, with or without temporal layers: say so at launch, because
        // BENCHMARK raw_source is only logged when the encoder starts on the first receiver (see RawInputGuard).
        debugRawInputFile?.let {
            Log.i(
                "BluetoothVideo",
                RawInputGuard.launchLine(
                    it.name, DebugTunables.experimentalTemporalLayers, DebugTunables.hardwareTemporalLayers,
                    DebugTunables.relayChildThinning,
                ),
            )
        }
        val autoStart = intent.getBooleanExtra("test_auto_start", false)
        val autoBroadcast = debugAutoBroadcastRequested
        val autoSelect = debugAutoSelectStream
        // A Live-tab callee for the CoC reachability arm carries no auto flag but still needs test_transport.
        val reachableWhileBrowsing = intent.hasExtra(ReachableWhileBrowsingFeature.EXTRA)
        if (!autoStart && !autoBroadcast && !autoSelect && !debugPrivateCallBrowser &&
            requestedTransportExperiment == null && !reachableWhileBrowsing
        ) return
        debugAutoStartPending = autoStart || autoBroadcast
        if (intent.hasExtra("test_thermal_reduction")) {
            automaticThermalReductionEnabled = intent.getBooleanExtra(
                "test_thermal_reduction",
                automaticThermalReductionEnabled,
            )
        }
        if (intent.hasExtra("test_forward_received_streams")) {
            forwardReceivedStreams = intent.getBooleanExtra(
                "test_forward_received_streams",
                forwardReceivedStreams,
            )
        }
        if (autoStart || autoBroadcast || autoSelect) {
            sessionRole = when (intent.getStringExtra("test_role")) {
                "receive" -> SessionRole.RECEIVE
                "relay" -> SessionRole.RELAY
                else -> SessionRole.SEND
            }
        }
        debugTransportOverride(intent.getStringExtra("test_transport"))?.let { outgoingTransport = it }
        val bitRateKbps = intent.getIntExtra(
            "test_bitrate_kbps",
            settings.bitRate / 1_000,
        ).coerceIn(VideoSettings.MIN_BIT_RATE_KBPS, outgoingTransport.maxVideoBitrateKbps)
        val width = intent.getIntExtra("test_width", settings.resolution.width).coerceAtLeast(1)
        val height = intent.getIntExtra("test_height", settings.resolution.height).coerceAtLeast(1)
        val fps = intent.getIntExtra("test_fps", settings.fps).coerceIn(1, 60)
        settings = settings.copy(
            resolution = VideoResolution(width, height, "${height}p"),
            fps = fps,
            bitRate = bitRateKbps * 1_000,
        )
        intent.getStringExtra("test_playback_delay")?.let { requested ->
            receiverPlaybackDelay = PlaybackDelayProfile.fromStoredValue(requested)
        }
        Log.i(
            "BluetoothVideo",
            "Test launch: ${sessionRole.label}, ${outgoingTransport.shortLabel}, ${width}x$height @ $fps, $bitRateKbps kbps",
        )
    }

    /**
     * DEBUG benchmark launch asked for something this phone cannot do: one `BENCHMARK setup_refused` line and a clean
     * finish of the task. Never a crash, and never another encoder, mode or geometry in its place.
     */
    private fun refuseDebugBenchmarkSetup(line: String) {
        Log.w("BluetoothVideo", line)
        debugSetupRefused = true
        finishAndRemoveTask()
    }

    private fun applyDebugCodecSelection() {
        if (!BuildConfig.DEBUG || (sessionRole != SessionRole.SEND && !debugPrivateCallBrowser)) return
        intent.getStringExtra("test_codec")?.let { requested ->
            val codecIndex = codecs.indexOfFirst { codec ->
                BenchmarkSetupRefusal.codecMatches(
                    requested,
                    isAv1 = codec.protocolId == StreamProtocol.CODEC_AV1,
                    isVp9 = codec.protocolId == StreamProtocol.CODEC_VP9,
                    encoderName = codec.encoderName,
                    isHevc = codec.protocolId == StreamProtocol.CODEC_HEVC,
                    isH264 = codec.protocolId == StreamProtocol.CODEC_H264,
                )
            }
            if (codecIndex < 0) {
                refuseDebugBenchmarkSetup(BenchmarkSetupRefusal.codecRefusalLine(requested))
                return
            }
            codecSpinner.setSelection(codecIndex, false)
            debugCodecRequested = true
            AutomaticEncoderChoice.userChoice = true
            refreshVideoModes()
            Log.i("BluetoothVideo", "Test encoder: ${codecs[codecIndex].displayName} / ${codecs[codecIndex].encoderName}")
        }
        intent.getStringExtra("test_rate_control")?.let { requested ->
            val codec = codecs.getOrNull(codecSpinner.selectedItemPosition)
            BenchmarkSetupRefusal.rateControlRefusalLine(
                requested, codec?.encoderName, codec?.availableRateControls().orEmpty(),
            )?.let { refusal ->
                refuseDebugBenchmarkSetup(refusal)
                return
            }
            val control = checkNotNull(BenchmarkSetupRefusal.rateControlFor(requested))
            checkNotNull(codec)
            // The temporary default encoder's picker must not overwrite the requested encoder's allocation.
            // A manual allocation is an Advanced-mode request for this session (not persisted).
            advancedSettings = control != EncoderRateControl.AUTOMATIC
            encoderRateControl = control
            refreshRateControlPicker()
            Log.i(
                "BluetoothVideo",
                "BENCHMARK encoder allocation selection: requested=${control.name} | " +
                    "encoder=${codec.encoderName} | selected=${encoderRateControl.name} | " +
                    "mode=${codec.resolveBitrateMode(encoderRateControl)}",
            )
            if (DebugTunables.wifiExactKbps > 0 && control != EncoderRateControl.AUTOMATIC) {
                settings = settings.copy(bitRate = DebugTunables.wifiExactKbps * 1_000)
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK wifi_exact: requestedKbps=${DebugTunables.wifiExactKbps} | rateControl=${control.name}",
                )
            }
        }
        // A raw fixture does not need a camera mode, but the selected encoder must support its declared size.
        // If it cannot, the launch is refused and logged; no other size or encoder takes its place.
        if (debugRawInputFile != null && !debugSetupRefused) {
            val codec = codecs.getOrNull(codecSpinner.selectedItemPosition)
            val declared = debugRawGeometry
            if (codec != null && declared != null &&
                !runCatching { deviceVideoCapabilities.encoderAllowsSize(codec, declared.first, declared.second, settings.bitRate) }
                    .getOrDefault(true)
            ) {
                refuseDebugBenchmarkSetup(
                    BenchmarkSetupRefusal.rawEncoderSizeLine(codec.encoderName, declared.first, declared.second),
                )
            } else if (codec != null && declared != null &&
                !runCatching {
                    deviceVideoCapabilities.encoderAllowsRate(codec, declared.first, declared.second, settings.fps)
                }.getOrDefault(true)
            ) {
                refuseDebugBenchmarkSetup(
                    BenchmarkSetupRefusal.rawEncoderRateLine(
                        codec.encoderName, declared.first, declared.second, settings.fps,
                    ),
                )
            }
        }
    }

    /**
     * A DEBUG launch with an auto-start pending must never stay silent. If the Start gate has not opened about
     * [BenchmarkSetupRefusal.STALL_WATCHDOG_MS] after the launch screen is shown, log every term of the gate and refuse
     * the launch (lane 11: a raw launch whose size was not the first camera mode sat idle with no line at all).
     */
    private fun armDebugSetupWatchdog() {
        if (!BuildConfig.DEBUG || !debugAutoStartPending || debugSetupRefused) return
        window.decorView.postDelayed({
            if (!BenchmarkSetupRefusal.stallWatchdogShouldFire(debugAutoStartPending, debugSetupRefused, isFinishing || isDestroyed)) {
                return@postDelayed
            }
            val idle = sender == null && broadcaster == null && receiver == null && relay == null &&
                privateCallRelay == null && !localTestRunning
            val destinationReady = usesBroadcastDiscovery() || selectedDestination() != null
            val peersReady = when (sessionRole) {
                SessionRole.SEND -> destinationReady
                SessionRole.RECEIVE -> true
                SessionRole.RELAY -> destinationReady && blePeers.size >= debugRelayChildCount
            }
            val modeOk = RawFixtureGeometry.startGateModeOk(debugRawInputFile != null, selectedDeviceMode(), settings)
            val bluetoothOk = bluetoothAdapter?.isEnabled == true
            val transportOk = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) ||
                (outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM && debugLaunchRequested)
            val permissionsOk = hasRequiredPermissions()
            val thermalOk = !ThermalGuardPolicy.shouldBlockStreaming(thermalStatus)
            val controlsIdle = idle && (sessionRole != SessionRole.SEND || !streamArmed)
            val localOk = bluetoothOk && permissionsOk && thermalOk && controlsIdle &&
                (sessionRole == SessionRole.RELAY || transportOk) &&
                (sessionRole != SessionRole.SEND || (modeOk && codecs.isNotEmpty())) &&
                (sessionRole != SessionRole.RECEIVE || preview.isAvailable)
            val verdict = BenchmarkSetupRefusal.stallVerdict(localOk, peersReady)
            val line = BenchmarkSetupRefusal.setupStalledLine(
                verdict,
                listOf(
                    "role" to sessionRole.name,
                    "canStart" to false,
                    "destinationReady" to destinationReady,
                    "codecs" to codecs.size,
                    "controlsIdle" to controlsIdle,
                    "modeSupported" to modeOk,
                    "bluetooth" to bluetoothOk,
                    "transport" to "${outgoingTransport.name}:$transportOk",
                    "permissions" to permissionsOk,
                    "thermal" to thermalOk,
                ),
            )
            if (verdict == BenchmarkSetupRefusal.StallVerdict.REFUSE) {
                refuseDebugBenchmarkSetup(line)
            } else {
                Log.w("BluetoothVideo", line)
                armDebugSetupWatchdog()
            }
        }, BenchmarkSetupRefusal.STALL_WATCHDOG_MS)
    }

    private fun usesBroadcastDiscovery(): Boolean =
        sessionRole == SessionRole.SEND && (!debugLaunchRequested || debugAutoBroadcastRequested)

    private fun configureTransport() {
        transportSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            BluetoothTransportOption.entries.map(BluetoothTransportOption::label),
        )
        transportSpinner.setSelection(outgoingTransport.ordinal, false)
        transportSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = BluetoothTransportOption.entries[position]
                // The initial selection is also reported, after this listener is set, whenever an accessibility
                // service is on (AdapterView.selectionChanged posts it): that is not a choice, so it asks nothing.
                val changed = outgoingTransport != selected
                if (changed) {
                    stopStreamPresence()
                    bleDiscovery?.close()
                    bleDiscovery = null
                    blePeers = emptyList()
                    outgoingTransport = selected
                    applyTransportBitrateLimit(persist = true)
                    if (!debugLaunchRequested && requestedTransportExperiment == null && !audioTransportForced) {
                        appPreferences.edit()
                            .putString(PREF_OUTGOING_TRANSPORT, selected.name)
                            .apply()
                    }
                }
                destinationLabel.text = when {
                    sessionRole == SessionRole.RELAY && debugRelayChildCount > 1 ->
                        "Next $debugRelayChildCount BLE receivers"
                    sessionRole == SessionRole.RELAY -> "Next BLE receiver"
                    outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM -> "Paired destination"
                    else -> "Active BLE receiver"
                }
                refreshButton.text = if (outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM) {
                    "Refresh paired devices"
                } else {
                    "Scan for BLE receivers"
                }
                updateDestinationControlsVisibility()
                updateSettingsLabels()
                if (changed && appScreen != AppScreen.HOME &&
                    (appScreen != AppScreen.SETTINGS || debugLaunchRequested)
                ) {
                    ensurePermissionsAndRefresh()
                }
                updateStartEnabled()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun showHomeScreen(stopActive: Boolean = true, reachabilityDelayMs: Long = 0L) {
        if (stopActive) stopStreaming("Stopped.")
        resetReceiverRecoveryNotice()
        hideReceiverOverlay()
        // Always reachable (CallReachability): an idle endpoint from the Call screen keeps
        // advertising and listening on Home; only the people scan stops.
        val homePlan = CallReachability.plan(
            currentReachabilityPlanInputs(ReachabilityScreen.HOME),
        )
        if (homePlan.endpoint && privateCallEndpointIdle() && privateCallAvailable) {
            stopPrivateCallDiscovery()
            privateCallPeers = emptyList()
            privateCallPeersLastSeen.clear()
            callSightings.clear()
        } else {
            // A call that ended through Home reachability handed its service back (onEnded).
            stopPrivateCallEndpoint(
                keepService = homePlan.endpoint && privateCall == null && ActiveVideoSessionService.holdsConnectedDevice(),
            )
        }
        CocRegistrations.session.reset()
        cancelStreamReconnect()
        endStreamSearch()
        reconnectFromNodeIdPrefix = null
        preferredRecoveryParent = null
        selectedStreamPeer = null
        stopStreamPresence()
        stopNearbyStreamDiscovery()
        stopIdleCameraPreview()
        appScreen = AppScreen.HOME
        cameraScreen.visibility = View.GONE
        receiverBrowserScreen.visibility = View.GONE
        callBrowserScreen.visibility = View.GONE
        callsTabScreen.visibility = View.GONE
        contactsTabScreen.visibility = View.GONE
        callScreen.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        settingsBackCallback.isEnabled = false
        updateSystemBars(visible = true)
        updateHomeReadiness()
        updateHomeActions()
        updateRecentCalls()
        updatesHome?.refreshNotice()
        refreshCallReachability(reachabilityDelayMs)
        updateMainNav()
    }

    private fun showStreamScreen() {
        stopPrivateCallEndpoint()
        stopNearbyStreamDiscovery()
        sessionRole = SessionRole.SEND
        showStreamCamera()
    }

    private fun showStreamCamera() {
        appScreen = AppScreen.CAMERA
        receiverBrowserScreen.visibility = View.GONE
        callBrowserScreen.visibility = View.GONE
        callsTabScreen.visibility = View.GONE
        contactsTabScreen.visibility = View.GONE
        callScreen.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        cameraScreen.visibility = View.VISIBLE
        cameraViewfinder.visibility = if (debugRawInputFile == null) View.VISIBLE else View.GONE
        preview.visibility = View.GONE
        cameraSwitchButton.visibility = View.VISIBLE
        streamToggleButton.visibility = View.VISIBLE
        updatePauseButton()
        connectionStatusDot.visibility = View.VISIBLE
        videoBackButton.visibility = View.VISIBLE
        videoBackButton.contentDescription = "Back to Home"
        if (debugRawInputFile != null) {
            showReceiverOverlay("Automated motion test\nRecorded scene · camera inactive", showProgress = false)
        } else hideReceiverOverlay()
        settingsBackCallback.isEnabled = true
        updateConnectionDot(0)
        updateSystemBars(visible = false)
        ensurePermissionsAndRefresh()
        startIdleCameraPreview()
    }

    private fun showReceiverBrowser(
        cancelReconnect: Boolean = true,
        askPermissions: Boolean = true,
        reachabilityDelayMs: Long = 0L,
    ) {
        audioLive.endListening() // AUDIO-LIVE hook: the listening screen goes with the receiver
        finishReceiverContinuity()
        if (receiver != null || relay != null || privateCallRelay != null) stopStreaming("Stopped receiving.")
        if (cancelReconnect) {
            cancelStreamReconnect()
            endStreamSearch()
            reconnectFromNodeIdPrefix = null
            preferredRecoveryParent = null
            activeStreamTarget = null
            activeStreamSourceNodeId = null
            activeStreamSourceAuthenticated = false
            preserveReceiverContinuityOnNextFormat = false
            clearReceiverBrowserNotice()
        }
        selectedStreamPeer = null
        stopStreamPresence()
        stopIdleCameraPreview()
        // Reachable while browsing (ReachableWhileBrowsingFeature): scanning needs no advertising set,
        // so an idle call endpoint keeps advertising and listening while this screen only browses; it is
        // closed when a stream is joined (connectToStream) or the user goes live (showStreamScreen).
        // Off, or no endpoint plan: the old behaviour, the endpoint is closed here.
        val browsePlan = CallReachability.plan(currentReachabilityPlanInputs(ReachabilityScreen.LIVE_BROWSING))
        val keepEndpoint = browsePlan.endpoint && privateCall != null && privateCallPhase == null &&
            appScreen != AppScreen.CALL && (privateCallAvailable || privateCallAvailabilityStarting)
        if (keepEndpoint) {
            stopPrivateCallDiscovery()
            privateCallPeers = emptyList()
            privateCallPeersLastSeen.clear()
            callSightings.clear()
        } else {
            stopPrivateCallEndpoint(
                keepService = browsePlan.endpoint && privateCall == null && ActiveVideoSessionService.holdsConnectedDevice(),
            )
        }
        sessionRole = SessionRole.RECEIVE
        appScreen = AppScreen.RECEIVER_BROWSER
        receiverBrowserStartedAtNs = System.nanoTime()
        receiverFirstOfferLogged = false
        cameraScreen.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        receiverBrowserScreen.visibility = View.VISIBLE
        callBrowserScreen.visibility = View.GONE
        callsTabScreen.visibility = View.GONE
        contactsTabScreen.visibility = View.GONE
        callScreen.visibility = View.GONE
        if (::watchRelaySwitch.isInitialized) watchRelaySwitch.isChecked = forwardReceivedStreams
        hideReceiverOverlay()
        // The Live tab is the root screen: back leaves the app (unless Settings or a sub-screen is open).
        settingsBackCallback.isEnabled = false
        updateSystemBars(visible = true)
        if (askPermissions) ensurePermissionsAndRefresh()
        // Restores the endpoint after a stream was left or a call ended; a GATT server needs its pause.
        refreshCallReachability(
            when {
                reachabilityDelayMs > 0L -> reachabilityDelayMs
                !keepEndpoint && browsePlan.endpoint && outgoingTransport == BluetoothTransportOption.BLE_GATT ->
                    GATT_CALL_RESTART_DELAY_MS
                else -> 0L
            },
        )
        updateMainNav()
    }

    private fun showPlaybackScreen() {
        appScreen = AppScreen.PLAYBACK
        receiverBrowserScreen.visibility = View.GONE
        callBrowserScreen.visibility = View.GONE
        callsTabScreen.visibility = View.GONE
        contactsTabScreen.visibility = View.GONE
        callScreen.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        cameraScreen.visibility = View.VISIBLE
        cameraViewfinder.visibility = View.GONE
        preview.visibility = View.VISIBLE
        cameraSwitchButton.visibility = View.GONE
        streamToggleButton.visibility = View.GONE
        broadcastPauseButton.visibility = View.GONE
        connectionStatusDot.visibility = View.VISIBLE
        updateConnectionDot(0)
        videoBackButton.visibility = View.VISIBLE
        videoBackButton.contentDescription = "Back to nearby broadcasts"
        viewerFrameAgeController().startViewing(FeatureCatalog.isEnabled(ViewerFeatures.frameAge.id))
        showReceiverOverlay("Connecting to stream", showProgress = true)
        settingsBackCallback.isEnabled = true
        updateSystemBars(visible = false)
    }

    private fun showDebugLaunchScreen() {
        when (sessionRole) {
            SessionRole.SEND -> {
                appScreen = AppScreen.CAMERA
                receiverBrowserScreen.visibility = View.GONE
                callBrowserScreen.visibility = View.GONE
                callsTabScreen.visibility = View.GONE
                contactsTabScreen.visibility = View.GONE
                callScreen.visibility = View.GONE
                settingsScreen.visibility = View.GONE
                cameraScreen.visibility = View.VISIBLE
                connectionStatusDot.visibility = View.VISIBLE
                videoBackButton.visibility = View.VISIBLE
                videoBackButton.contentDescription = "Back to Home"
                updateConnectionDot(0)
                if (debugRawInputFile != null) {
                    cameraViewfinder.visibility = View.GONE
                    showReceiverOverlay("Automated motion test\nRecorded scene · camera inactive", showProgress = false)
                }
            }
            SessionRole.RECEIVE -> showPlaybackScreen()
            SessionRole.RELAY -> {
                appScreen = AppScreen.CAMERA
                receiverBrowserScreen.visibility = View.GONE
                callBrowserScreen.visibility = View.GONE
                callsTabScreen.visibility = View.GONE
                contactsTabScreen.visibility = View.GONE
                callScreen.visibility = View.GONE
                settingsScreen.visibility = View.GONE
                cameraScreen.visibility = View.VISIBLE
                cameraViewfinder.visibility = View.GONE
                preview.visibility = View.GONE
                cameraSwitchButton.visibility = View.GONE
                broadcastPauseButton.visibility = View.GONE
                streamToggleButton.visibility = View.VISIBLE
                connectionStatusDot.visibility = View.VISIBLE
                videoBackButton.visibility = View.VISIBLE
                videoBackButton.contentDescription = "Back to Home"
                updateConnectionDot(0)
            }
        }
        settingsBackCallback.isEnabled = true
        updateSystemBars(visible = false)
    }

    private fun showSettings(show: Boolean) {
        if (show) {
            settingsReturnScreen = appScreen
            appScreen = AppScreen.SETTINGS
            settingsReturnCalls = callsTabScreen.visibility == View.VISIBLE
            cameraScreen.visibility = if (settingsReturnScreen == AppScreen.CAMERA) View.VISIBLE else View.GONE
            receiverBrowserScreen.visibility = View.GONE
            callBrowserScreen.visibility = View.GONE
            callsTabScreen.visibility = View.GONE
            contactsTabScreen.visibility = View.GONE
            callScreen.visibility = View.GONE
            settingsScreen.visibility = View.VISIBLE
            showSettingsPage(SettingsPage.MAIN)
            loadIdentityIntoSettings()
            updateDestinationControlsVisibility()
        } else {
            settingsScreen.visibility = View.GONE
            when (settingsReturnScreen) {
                AppScreen.HOME -> if (settingsReturnCalls) showCallsScreen() else showReceiverBrowser()
                AppScreen.CAMERA -> {
                    appScreen = AppScreen.CAMERA
                    cameraScreen.visibility = View.VISIBLE
                    if (usesBroadcastDiscovery()) {
                        ensurePermissionsAndRefresh()
                    }
                    settingsBackCallback.isEnabled = true
                    updateSystemBars(visible = false)
                }
                AppScreen.RECEIVER_BROWSER -> showReceiverBrowser()
                AppScreen.PLAYBACK -> showPlaybackScreen()
                AppScreen.CALL_BROWSER -> showPrivateCallBrowser()
                AppScreen.CALL -> Unit
                AppScreen.SETTINGS -> showReceiverBrowser()
            }
        }
        if (show) {
            settingsBackCallback.isEnabled = true
            updateSystemBars(visible = true)
        }
    }

    private var settingsReturnCalls = false

    /** Shows one settings page; the title bar names it. */
    private fun showSettingsPage(page: SettingsPage) {
        settingsPage = page
        SettingsPage.entries.forEach { findViewById<View>(it.viewId).visibility = if (it == page) View.VISIBLE else View.GONE }
        findViewById<TextView>(R.id.settingsTitle).setText(page.titleRes)
        findViewById<View>(R.id.doneSettingsButton).visibility = if (page.parent != null) View.VISIBLE else View.GONE
        findViewById<View>(page.viewId).scrollTo(0, 0)
        // Messaging v2 (phase 1c): Read receipts and Download files automatically, shown with the feature on.
        if (page == SettingsPage.PRIVACY) MessagingV2PrivacyRows(this, findViewById(R.id.mv2PrivacyRows), appPreferences).bind()
    }

    // ---- status indicator ------------------------------------------------------------------------
    private var statusKindNow = StatusKind.READY

    /** Experimental `test_calls_unavailable_status`: the call endpoint's start as the status sees it. */
    private val callsHealth = CallsAvailabilityTracker()

    private fun callsUnavailableNow(): CallsUnavailableReason? = callsHealth.reason(
        CocAvailabilitySwitches.callsUnavailableStatus,
        appPreferences.getBoolean(PREF_REACHABLE_FOR_CALLS, true) && currentReachabilityPlan().reachable,
        privateCallAvailabilityStarting,
        CocAvailabilitySwitches.stallMs,
    )

    private fun callsUnavailableReason(error: Throwable): CallsUnavailableReason = CallsUnavailableReason.of(
        error,
        DeviceFamilyPolicy.leCocServerLeaks(BluetoothStackFingerprint.current()),
        error === CocListeners.lastListenFailure,
    )

    /** Re-checks the status when a start that is still running would count as stalled. */
    private fun scheduleCallsStallCheck() {
        if (!CocAvailabilitySwitches.callsUnavailableStatus || !::mainNav.isInitialized) return
        val waitMs = callsHealth.msUntilStalled(CocAvailabilitySwitches.stallMs) ?: return
        mainNav.postDelayed({ updateHomeReadiness(); updateStatusIndicators() }, waitMs + 50L)
    }

    private fun openBluetoothSettings() {
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    /**
     * An available endpoint lost its availability without a call (a reopen failed or its listen stalled; reported only
     * with `test_listen_partial_cleanup` or the listen watchdog): as a failed start, it may retry over GATT, else shows.
     */
    private fun onCallAvailabilityLost(source: Any, error: Throwable) {
        val endpoint = privateCall
        if (endpoint == null || endpoint !== source) return
        Log.w("BluetoothVideo", "Private call availability lost", error)
        privateCallAvailable = false
        callsHealth.failed(callsUnavailableReason(error))
        endpoint.close()
        privateCall = null
        val gattRetry = CocRegistrations.session.onCocFailure(
            outgoingTransport, CocAutoGatt.current, CocFallbackReason.AVAILABILITY_FAILED,
        )
        if (gattRetry != null) {
            logCocTransportChoice("availability_lost", gattRetry, error.message)
            startPrivateCallEndpoint()
            return
        }
        updateHomeReadiness()
        updateStatusIndicators()
    }

    private fun statusChips(): List<View> = listOfNotNull(
        R.id.tabStatusReceiver, R.id.tabStatusCalls, R.id.tabStatusNearby, R.id.tabStatusContacts, R.id.tabStatusSettings,
    ).map { findViewById(it) }

    private fun bindStatusIndicators() {
        statusChips().forEach { chip ->
            chip.setOnClickListener { onStatusChipClick() }
            (chip.parent as? View)?.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) chip.post { fitStatusChip(chip) }
            }
        }
        updateStatusIndicators()
    }

    /**
     * The screen title always shows in full: the status word shows beside it only if both fit on one line; otherwise
     * only the dot stays (a tap still opens the status sheet with the full text). Decided from text widths, not from the
     * current layout, so hiding the word cannot make it reappear on the next pass.
     */
    private fun fitStatusChip(chip: View) {
        val label = chip.findViewById<TextView>(R.id.statusChipLabel) ?: return
        if (!statusKindNow.problem) { label.visibility = View.GONE; return }
        val row = chip.parent as? ViewGroup ?: return
        if (row.width == 0) return
        var others = 0
        var title: TextView? = null
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child === chip || child.visibility == View.GONE) continue
            val params = child.layoutParams as? android.widget.LinearLayout.LayoutParams
            if (title == null && child is TextView && params != null && params.weight > 0f) { title = child; continue }
            others += child.width + (params?.leftMargin ?: 0) + (params?.rightMargin ?: 0)
        }
        val titleView = title ?: return
        val titleWidth = titleView.paint.measureText(titleView.text.toString()) + titleView.paddingStart + titleView.paddingEnd
        val dot = chip.findViewById<View>(R.id.statusChipDot)
        val labelMargin = (label.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0
        val chipWidth = chip.paddingStart + chip.paddingEnd + (dot?.layoutParams?.width ?: 0) + labelMargin +
            label.paint.measureText(label.text.toString())
        val available = row.width - row.paddingStart - row.paddingEnd - others
        label.visibility = if (titleWidth + chipWidth <= available) View.VISIBLE else View.GONE
    }

    private fun readinessNow(): HomeReadiness {
        val adapter = bluetoothAdapter
        val permissionsGranted = RuntimePermissionPlan.readiness(Build.VERSION.SDK_INT, DebugTunables.callReadinessPermissions)
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        val bluetoothOn = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        return homeReadiness(adapter != null, permissionsGranted, bluetoothOn)
    }

    /** Forwarding is wanted but paused by the existing battery rule (below 20 % not charging, or Battery Saver). */
    private fun relayPausedByBatteryNow(): Boolean {
        if (!forwardReceivedStreams || isBenchmarkLaunch()) return false
        val battery = getSystemService(android.os.BatteryManager::class.java)
        val level = battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return relayAutoPaused(level, battery?.isCharging == true, powerManager.isPowerSaveMode)
    }

    private fun hotNow(): Boolean =
        automaticThermalReductionEnabled && thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE

    /**
     * Not reachable for calls. Only judged on the screens that are meant to be reachable (Home, People, Calls,
     * Settings from them): while watching or broadcasting the stream owns the radio by design.
     */
    private fun hiddenNow(): Boolean {
        val screen = reachabilityScreen()
        if (screen != ReachabilityScreen.HOME && screen != ReachabilityScreen.CALL_BROWSER &&
            screen != ReachabilityScreen.LIVE_BROWSING
        ) return false
        return homeReachability(currentReachabilityPlan(), privateCallAvailable, privateCallAvailabilityStarting) ==
            HomeReachability.NOT_REACHABLE
    }

    private fun updateStatusIndicators() {
        if (!::mainNav.isInitialized) return
        val callsUnavailable = callsUnavailableNow()
        val kind = statusKind(readinessNow(), hiddenNow(), relayPausedByBatteryNow(), hotNow(), reachabilityScreen(), DebugTunables.honestCallStatus,
            callsUnavailable = callsUnavailable != null)
        if (BuildConfig.DEBUG && CocAvailabilitySwitches.callsUnavailableStatus && kind != statusKindNow) {
            Log.i("BluetoothVideo", "BENCHMARK calls_status: kind=${kind.name} | reason=${callsUnavailable?.label ?: "none"}")
        }
        statusKindNow = kind
        val dot = when (kind) {
            StatusKind.READY -> R.color.success
            StatusKind.BLUETOOTH_OFF, StatusKind.BLUETOOTH_UNAVAILABLE -> R.color.live_red
            else -> R.color.white
        }
        statusChips().forEach { chip ->
            chip.findViewById<TextView>(R.id.statusChipLabel).apply {
                setText(kind.labelRes)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
                // All well: only the green dot. A problem adds its short word when it fits beside the title (fitStatusChip).
                visibility = if (kind.problem) View.VISIBLE else View.GONE
            }
            chip.post { fitStatusChip(chip) }
            chip.findViewById<View>(R.id.statusChipDot).backgroundTintList =
                ContextCompat.getColorStateList(this, dot)
            chip.contentDescription = getString(if (kind.problem) kind.labelRes else R.string.status_ready_description)
        }
    }

    /** Bluetooth off and missing permissions are fixed on a tap (the existing readiness action); the rest opens the sheet. */
    private fun onStatusChipClick() {
        // UX fix 2026-10-21: every status tap opens the sheet; its button still fixes Bluetooth off or missing access.
        showStatusSheet()
    }

    /**
     * What others nearby call this phone and until when: the generated name of this phone's current role 0 ID (the value
     * a scanner computes from the same public bytes) and the next ID change. Null when generated names are off, rotating
     * IDs are off, the phone is not call-ready, or nothing is on air to show.
     */
    private fun ownGeneratedNameLine(): String? {
        if (!NamingFeatures.generatedNamesOn() || !RotatingDiscoveryFeature.enabled) return null
        if (!appPreferences.getBoolean(PREF_REACHABLE_FOR_CALLS, true)) return null
        val rdi = runCatching { RotatingDiscovery.ownRdis(RotatingDiscoveryIds.ROLE_CALL_OFFER).firstOrNull() }.getOrNull() ?: return null
        val name = StrangerNames.derive(StrangerNames.Input.rdi(rdi)) ?: return null
        val untilMs = System.currentTimeMillis() + RotatingDiscoveryIds.msUntilBoundary(DiscoveryWallClock.nowMs())
        val time = android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(untilMs))
        return getString(R.string.status_nameplate_value, name.text, time)
    }

    private fun showStatusSheet() {
        val view = layoutInflater.inflate(R.layout.sheet_status, null)
        fun row(id: Int, label: Int, value: CharSequence) {
            val row = view.findViewById<View>(id)
            row.findViewById<TextView>(R.id.statusRowLabel).setText(label)
            row.findViewById<TextView>(R.id.statusRowValue).text = value
        }
        val readiness = readinessNow()
        row(
            R.id.statusRowBluetooth, R.string.status_row_bluetooth,
            getString(
                when (readiness) {
                    HomeReadiness.READY -> R.string.status_on
                    HomeReadiness.BLUETOOTH_OFF -> R.string.status_off
                    HomeReadiness.PERMISSIONS_NEEDED -> R.string.status_value_needs_permission
                    HomeReadiness.UNSUPPORTED -> R.string.status_value_unavailable
                },
            ),
        )
        val allowCalls = appPreferences.getBoolean(PREF_REACHABLE_FOR_CALLS, true)
        val callsUnavailable = callsUnavailableNow()
        row(R.id.statusRowCalls, R.string.status_row_calls, getString(when {
            callsUnavailable != null && readiness == HomeReadiness.READY -> callsUnavailable.valueRes
            DebugTunables.honestCallStatus && reachabilityScreen() == ReachabilityScreen.WATCH -> R.string.status_calls_paused_watch
            DebugTunables.honestCallStatus && reachabilityScreen() == ReachabilityScreen.LIVE -> R.string.status_calls_paused_live
            allowCalls -> R.string.status_on
            else -> R.string.status_value_hidden
        }))
        row(
            R.id.statusRowRelay, R.string.status_row_relay,
            getString(
                when {
                    !forwardReceivedStreams -> R.string.status_off
                    relayPausedByBatteryNow() -> R.string.status_value_relay_paused
                    else -> R.string.status_on
                },
            ),
        )
        row(
            R.id.statusRowBattery, R.string.status_row_battery,
            run {
                val battery = getSystemService(android.os.BatteryManager::class.java)
                val level = battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
                val charge = (level?.let { "$it%" } ?: "") + if (battery?.isCharging == true) " charging" else ""
                val temperature = batteryTemperatureBadge.text.toString().removePrefix("Battery: ")
                val heat = thermalStatusBadge.text.toString().removePrefix("Heat: ")
                getString(R.string.status_battery_line, charge.trim().ifEmpty { temperature }, "$temperature, heat ${heat.lowercase()}")
            },
        )
        row(
            R.id.statusRowPermissions, R.string.status_row_permissions,
            if (readiness != HomeReadiness.PERMISSIONS_NEEDED) getString(R.string.status_value_all_allowed) else getString(R.string.readiness_permissions_text),
        )
        view.findViewById<View>(R.id.statusFullScreenWarning).apply {
            visibility = if (FullScreenIntentAccess.showGuidance(Build.VERSION.SDK_INT, DebugTunables.fullScreenCallGuidance, FullScreenIntentAccess.canUse(this@MainActivity))) View.VISIBLE else View.GONE
            setOnClickListener { runCatching { FullScreenIntentAccess.openSettings(this@MainActivity) } }
        }
        view.findViewById<View>(R.id.statusNotificationsWarning).visibility =
            if (DebugTunables.callReadinessPermissions && !MissedCallNotification.allowed(Build.VERSION.SDK_INT,
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) View.VISIBLE else View.GONE
        row(R.id.statusRowIds, R.string.status_row_ids, getString(if (RotatingDiscoveryFeature.enabled) R.string.status_on else R.string.status_off))
        // Naming v2: "Nearby you appear as Gold Fox until 14:15", only while this phone is advertising a rotating call ID.
        ownGeneratedNameLine()?.let { line ->
            view.findViewById<View>(R.id.statusRowNameplate).visibility = View.VISIBLE
            row(R.id.statusRowNameplate, R.string.status_row_nameplate, line)
        }
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.statusActionButton).apply {
            val fixable = readiness == HomeReadiness.BLUETOOTH_OFF || readiness == HomeReadiness.PERMISSIONS_NEEDED
            val bluetoothSettings = !fixable && readiness == HomeReadiness.READY && callsUnavailable != null
            visibility = if (fixable || bluetoothSettings) View.VISIBLE else View.GONE
            text = if (bluetoothSettings) getString(R.string.action_bluetooth_settings) else homeReadinessAction.text
            setOnClickListener {
                dialog.dismiss()
                if (bluetoothSettings) openBluetoothSettings() else onHomeReadinessAction()
            }
        }
        dialog.setContentView(view)
        dialog.show()
    }

    /** Back inside Settings goes up one page; from the top page it leaves Settings. */
    private fun settingsBack() {
        val parent = settingsPage.parent
        if (parent != null) showSettingsPage(parent) else showSettings(false)
    }

    /** Opens each page from its row and wires the (i) buttons. */
    private fun bindSettingsPages() {
        mapOf(
            R.id.settingsRowVideo to SettingsPage.VIDEO,
            R.id.settingsRowPrivacy to SettingsPage.PRIVACY,
            R.id.settingsRowCalls to SettingsPage.CALLS,
            R.id.settingsRowShare to SettingsPage.SHARE,
            R.id.advancedRowConnection to SettingsPage.CONNECTION,
            R.id.advancedRowManual to SettingsPage.MANUAL,
            R.id.advancedRowPlayback to SettingsPage.PLAYBACK,
            R.id.advancedRowExperiments to SettingsPage.EXPERIMENTS,
            R.id.advancedRowHelp to SettingsPage.HELP,
        ).forEach { (rowId, page) -> findViewById<View>(rowId).setOnClickListener { showSettingsPage(page) } }
        // Developer rows of Connection (transport picker, paired destination, the two summaries): DEBUG builds only (UI-W7).
        // The picker itself stays in a release build while a non-default transport is stored, so it can be changed back (decided once, here).
        findViewById<View>(R.id.connectionTransportCard).visibility = if (
            ConnectionPageRows.transportPickerShown(BuildConfig.DEBUG, appPreferences.getString(PREF_OUTGOING_TRANSPORT, null))
        ) View.VISIBLE else View.GONE
        findViewById<View>(R.id.connectionSummaries).visibility =
            if (ConnectionPageRows.developerRowsShown(BuildConfig.DEBUG)) View.VISIBLE else View.GONE
        // Connection: the two summaries show two lines; a tap shows all of it (2026-10-17 redesign).
        findViewById<View>(R.id.settingsRowYourCode).setOnClickListener {
            startActivity(VerifyContactActivity.intent(this, showCode = true))
        }
        listOf(R.id.homeConfigurationSummary, R.id.privateCallProfileSummary).forEach { id ->
            findViewById<TextView>(id).setOnClickListener { view ->
                (view as TextView).maxLines = if (view.maxLines == 2) Int.MAX_VALUE else 2
            }
        }
        // Manual video: the raw capability and summary lines sit behind Details (2026-10-17 redesign).
        findViewById<View>(R.id.manualDetailsRow).setOnClickListener {
            val show = findViewById<View>(R.id.capabilityText).visibility != View.VISIBLE
            listOf(R.id.capabilityText, R.id.streamSummaryText).forEach {
                findViewById<View>(it).visibility = if (show) View.VISIBLE else View.GONE
            }
        }
        fun info(buttonId: Int, title: Int, message: () -> CharSequence) =
            findViewById<View>(buttonId).setOnClickListener { SettingsInfo.show(this, getString(title), message()) }
        info(R.id.neutralBluetoothNameSwitchInfo, R.string.neutral_bluetooth_name) {
            getString(R.string.neutral_bluetooth_name_description) + "\n\n" + neutralBluetoothNameStatusText.text
        }
        info(R.id.panicGestureSwitchInfo, R.string.panic_gesture) { getString(R.string.panic_gesture_description) }
        info(R.id.blockUnknownCallersSwitchInfo, R.string.block_unknown_callers) {
            getString(R.string.block_unknown_callers_description)
        }
        info(R.id.experimentalTemporalLayersSwitchInfo, R.string.settings_experiments_title) {
            experimentalTemporalLayersDescriptionText.text
        }
        info(R.id.relayChildMediaAgeSwitchInfo, R.string.settings_experiments_title) {
            relayChildMediaAgeDescriptionText.text
        }
        info(R.id.experimentalRelayedCallsSwitchInfo, R.string.experimental_relayed_calls) {
            getString(R.string.experimental_relayed_calls_description)
        }
        neutralBluetoothNameStatusText.visibility = View.GONE
        updateVideoRowValue()
    }

    /** "Video quality" on the top page shows Auto, or Manual while manual video settings are on. */
    private fun updateVideoRowValue() {
        findViewById<TextView>(R.id.settingsRowVideoValue).setText(
            if (advancedSettings) R.string.settings_video_manual else R.string.settings_video_auto,
        )
    }

    // ---- bottom navigation ---------------------------------------------------------------------
    private fun bindMainNav() {
        findViewById<View>(R.id.contactsAddButton).setOnClickListener {
            startActivity(VerifyContactActivity.intent(this))
        }
        SegmentedControl.bind(
            findViewById(R.id.tabSegmentsNearby), R.string.calls_nearby, R.string.calls_recent,
            selectedFirst = true, onFirst = {}, onSecond = { showCallsScreen() },
        )
        SegmentedControl.bind(
            findViewById(R.id.tabSegmentsRecent), R.string.calls_nearby, R.string.calls_recent,
            selectedFirst = false, onFirst = { showCallsNearby() }, onSecond = {},
        )
        findViewById<View>(R.id.contactsEmptyView).apply {
            findViewById<ImageView>(R.id.emptyStateIcon).setImageResource(R.drawable.ic_group)
            findViewById<TextView>(R.id.emptyStateTitle).setText(R.string.contacts_empty_title)
            findViewById<TextView>(R.id.emptyStateText).setText(R.string.contacts_empty_text)
            findViewById<View>(R.id.emptyStateAction).visibility = View.GONE
        }
        findViewById<android.widget.ListView>(R.id.contactsList).setOnItemClickListener { _, _, position, _ ->
            if (MessagingV2Feature.enabled && callBrowserChats) {
                // The Chats tab: a row is a conversation.
                conversationRows.nodeIdAt(position)?.let(::openThread)
                return@setOnItemClickListener
            }
            savedContacts.getOrNull(position)?.let { contactActions.show(it) }
        }
        findViewById<android.widget.ListView>(R.id.contactsList).setOnItemLongClickListener { _, _, position, _ ->
            if (!(MessagingV2Feature.enabled && callBrowserChats)) return@setOnItemLongClickListener false
            conversationRows.nodeIdAt(position)?.let(::showChatRowActions)
            true
        }
        mainNav.menu.findItem(R.id.navChats)?.isVisible = ChatTab.visible(StoreForwardFeature.enabled)
        mainNav.setOnItemSelectedListener { item ->
            val onLive = appScreen == AppScreen.RECEIVER_BROWSER
            when (item.itemId) {
                R.id.navLive -> if (!onLive) showReceiverBrowser()
                R.id.navCalls -> if (currentTab() != MainTab.CALLS) showCallsNearby()
                R.id.navChats -> if (currentTab() != MainTab.CHATS) showChatsTab()
                R.id.navSettings -> if (appScreen != AppScreen.SETTINGS) showSettings(true)
                R.id.navContacts -> if (currentTab() != MainTab.CONTACTS) showContactsTab()
            }
            clearNavTooltips()
            true
        }
        updateMainNav()
    }

    /** The tab the current screen belongs to, or null on a full-screen view. */
    private fun currentTab(): MainTab? = when (appScreen) {
        AppScreen.HOME -> if (callsTabScreen.visibility == View.VISIBLE) MainTab.CALLS else null
        AppScreen.RECEIVER_BROWSER -> MainTab.LIVE
        AppScreen.CALL_BROWSER -> when {
            callBrowserChats -> MainTab.CHATS
            callBrowserContacts -> MainTab.CONTACTS
            else -> MainTab.CALLS
        }
        AppScreen.SETTINGS -> MainTab.SETTINGS
        else -> null
    }

    /** The navigation shows on the four tab screens only, with the current tab marked. */
    private fun updateMainNav() {
        if (!::mainNav.isInitialized || !::callsTabScreen.isInitialized) return
        val tab = currentTab()
        val show = tab != null
        val changed = (mainNav.visibility == View.VISIBLE) != show
        mainNav.visibility = if (show) View.VISIBLE else View.GONE
        if (tab != null) {
            val itemId = when (tab) {
                MainTab.LIVE -> R.id.navLive
                MainTab.CALLS -> R.id.navCalls
                MainTab.CHATS -> R.id.navChats
                MainTab.CONTACTS -> R.id.navContacts
                MainTab.SETTINGS -> R.id.navSettings
            }
            mainNav.menu.findItem(itemId)?.isChecked = true
        }
        if (changed) ViewCompat.requestApplyInsets(findViewById(R.id.main))
        clearNavTooltips()
    }

    /**
     * S22 review 2026-10-21: a "Settings" tooltip stayed over the Live tab's Go live button. Material gives every tab a
     * long-press tooltip with its title and sets it again whenever the bar updates; the labels are always shown here, so
     * the tooltips add nothing. Clearing a tooltip also hides one that is showing.
     */
    private fun clearNavTooltips() {
        if (!::mainNav.isInitialized) return
        mainNav.post {
            for (index in 0 until mainNav.menu.size()) {
                mainNav.findViewById<View>(mainNav.menu.getItem(index).itemId)?.let {
                    androidx.appcompat.widget.TooltipCompat.setTooltipText(it, null)
                }
            }
        }
    }

    /** Calls > Nearby: everyone in range now, discovery running. */
    private fun showCallsNearby() {
        callBrowserContacts = false
        callBrowserChats = false
        showPrivateCallBrowser()
        // F13: the Calls tab opens on its Nearby segment, so the one-time full-screen offer belongs here too.
        checkFullScreenCallAccess(offerPrompt = true)
    }

    /** Contacts: the saved people; discovery runs too so "Nearby now" is live. */
    private fun showContactsTab() {
        callBrowserContacts = true
        callBrowserChats = false
        bindChatsHeader(chats = false)
        showPrivateCallBrowser()
        checkFullScreenCallAccess(offerPrompt = true)
    }

    /** The Chats tab: conversations in place with Messaging v2; with the older Messages only, that screen. */
    private fun showChatsTab() {
        if (!ChatTab.inPlace(MessagingV2Feature.enabled)) {
            if (StoreForwardFeature.enabled) startActivity(Intent(this, MessagesActivity::class.java))
            updateMainNav()
            return
        }
        callBrowserContacts = true
        callBrowserChats = true
        bindChatsHeader(chats = true)
        showPrivateCallBrowser()
        refreshMessagesUnread(notify = false)
    }

    /** The shared list screen as Contacts or Chats: title and empty state ("No chats yet", "Message a contact"). */
    private fun bindChatsHeader(chats: Boolean) {
        findViewById<TextView>(R.id.contactsTitle)?.setText(if (chats) R.string.chats_title else R.string.contacts_title)
        findViewById<View>(R.id.contactsEmptyView)?.apply {
            findViewById<ImageView>(R.id.emptyStateIcon).setImageResource(if (chats) R.drawable.ic_chat else R.drawable.ic_group)
            findViewById<TextView>(R.id.emptyStateTitle).setText(if (chats) R.string.chats_empty_title else R.string.contacts_empty_title)
            findViewById<TextView>(R.id.emptyStateText).setText(if (chats) R.string.chats_empty_text else R.string.contacts_empty_text)
            findViewById<com.google.android.material.button.MaterialButton>(R.id.emptyStateAction).apply {
                visibility = if (chats) View.VISIBLE else View.GONE
                setText(R.string.chats_empty_action)
                setOnClickListener { pickContactToMessage() }
            }
        }
        findViewById<View>(R.id.contactsAddButton)?.visibility = if (chats) View.GONE else View.VISIBLE
    }

    /** "Message a contact": a plain list of the saved contacts; the chosen one's thread opens. */
    private fun pickContactToMessage() {
        val contacts = savedContacts
        if (contacts.isEmpty()) {
            Toast.makeText(this, R.string.chats_no_contacts, Toast.LENGTH_LONG).show()
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chats_pick_contact)
            .setItems(contacts.map { it.title }.toTypedArray()) { _, index ->
                ChatEntry.threadFor(ChatEntry.Source.CONTACT_ROW, contacts[index].nodeIdHex, MessagingV2Feature.enabled)?.let(::openThread)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showTracked()
    }

    /** Long-press on a chat: Mute or Unmute, and Delete chat (every message with that person, on this phone only). */
    private fun showChatRowActions(nodeIdHex: String) {
        val name = savedContacts.firstOrNull { it.nodeIdHex.equals(nodeIdHex, ignoreCase = true) }?.title ?: return
        val muted = ChatMute.isMuted(this, nodeIdHex)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(name)
            .setItems(arrayOf(getString(if (muted) R.string.chat_unmute else R.string.chat_mute), getString(R.string.chat_delete))) { _, which ->
                if (which == 0) {
                    ChatMute.setMuted(this, nodeIdHex, !muted)
                    ConversationNotifier.update(applicationContext, nodeIdHex)
                } else {
                    confirmDeleteChat(nodeIdHex, name)
                }
            }
            .showTracked()
    }

    private fun confirmDeleteChat(nodeIdHex: String, name: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_delete_title)
            .setMessage(getString(R.string.chat_delete_text, name))
            .setPositiveButton(R.string.chat_delete) { _, _ ->
                val context = applicationContext
                contactWorker.execute {
                    runCatching {
                        val engine = StoreForwardRuntime.node(context).v2 ?: return@runCatching
                        // The engine's own per-message delete (record, files, sealed segments, key): no new store operation.
                        engine.threads.forPeer(nodeIdHex.lowercase()).forEach { engine.deleteMessage(it.id) }
                    }
                    ConversationNotifier.update(context, nodeIdHex)
                    runOnUiThread { if (!isDestroyed) updateContactsList() }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showTracked()
    }

    /** People tab: the idle Home state (reachable, nothing scanning) with the contacts in front. */
    private fun showCallsScreen(stopActive: Boolean = true, reachabilityDelayMs: Long = 0L) {
        showHomeScreen(stopActive, reachabilityDelayMs)
        callsTabScreen.visibility = View.VISIBLE
        settingsBackCallback.isEnabled = true
        updateRecentCalls()
        updateMainNav()
        checkFullScreenCallAccess(offerPrompt = true)
    }

    /** The last `call_fsi` line, so it is logged only when the access or the prompt state changes. */
    private var lastFullScreenAccessLine: String? = null

    /**
     * Android 14+ "full-screen incoming calls" access ([FullScreenIntentAccess]): re-checked on every resume (the user may
     * come back from the Android page) and when the calls area opens. When Android refuses it, the calls area offers it
     * once, with a link to the system page; Settings keeps the permanent link. Never asked again after that one time.
     */
    private fun checkFullScreenCallAccess(offerPrompt: Boolean) {
        val allowed = runCatching { FullScreenIntentAccess.canUse(this) }.getOrDefault(true)
        val prompted = appPreferences.getBoolean(FullScreenIntentAccess.PREF_PROMPTED, false)
        val line = FullScreenIntentAccess.logLine(allowed, prompted)
        if (line != lastFullScreenAccessLine) {
            lastFullScreenAccessLine = line
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + line)
        }
        callFeatureBinder?.refresh()
        // Automated benchmark launches never get the offer in their way; a ringing or running call never gets it at all.
        if (!offerPrompt || isBenchmarkLaunch() || privateCallPhase != null) return
        if (!FullScreenIntentAccess.offerOnce(Build.VERSION.SDK_INT, CallUx.settings.lockScreen, allowed, prompted)) return
        appPreferences.edit().putBoolean(FullScreenIntentAccess.PREF_PROMPTED, true).apply()
        lastFullScreenAccessLine = FullScreenIntentAccess.logLine(allowed, true)
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + lastFullScreenAccessLine)
        // F15: a non-blocking banner, never a modal: the list stays usable and an incoming call is never covered. Swiping it
        // away is "Not now"; the Settings link stays.
        fullScreenAccessBanner = com.google.android.material.snackbar.Snackbar
            .make(findViewById(android.R.id.content), R.string.fsi_banner_text, com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.fsi_banner_allow) { runCatching { FullScreenIntentAccess.openSettings(this) } }
            .also { it.show() }
    }

    /** F15: the one-time full-screen offer (a banner); closed when a call rings or the call screen opens. */
    private var fullScreenAccessBanner: com.google.android.material.snackbar.Snackbar? = null

    private fun updateDestinationControlsVisibility() {
        if (!::destinationLabel.isInitialized) return
        val directSendTest = BuildConfig.DEBUG &&
            intent.getBooleanExtra("test_auto_start", false) &&
            !debugAutoBroadcastRequested &&
            sessionRole == SessionRole.SEND
        destinationGroup.visibility = if (
            directSendTest || sessionRole == SessionRole.RELAY
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun switchCamera() {
        if (debugRawInputFile != null) return
        if (broadcaster != null && cameraEncoder != null && !audioLive.mode) {
            flipLiveCamera()
            return
        }
        if (sender != null || broadcaster != null || receiver != null || relay != null ||
            privateCallRelay != null ||
            cameraEncoder != null
        ) return
        val currentIndex = availableCameraLenses.indexOf(cameraLens)
        cameraLens = availableCameraLenses[(currentIndex + 1) % availableCameraLenses.size]
        refreshVideoModes()
        setStatus("${cameraLens.displayName} camera selected.")
        updateCameraSwitchButton()
    }

    private fun updateCameraSwitchButton() {
        if (!::cameraSwitchButton.isInitialized) return
        val idle = sender == null && broadcaster == null && receiver == null && relay == null &&
            privateCallRelay == null &&
            cameraEncoder == null && !localTestRunning
        cameraSwitchButton.visibility = if (
            LiveControlSlots.shown(
                LiveSlot.FLIP,
                pauseAvailable = true,
                cameraCount = if (sessionRole == SessionRole.SEND) availableCameraLenses.size else 0,
                rawInput = debugRawInputFile != null,
            )
        ) {
            View.VISIBLE
        } else {
            View.INVISIBLE
        }
        // Before going live the Flip column exists only when there is a Flip; while live it always keeps its slot.
        findViewById<View>(R.id.broadcastFlipColumn).visibility =
            if ((broadcaster != null && streamArmed) || cameraSwitchButton.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        // Flip works while idle, and while live with the camera on (switchCamera restarts the stream camera).
        cameraSwitchButton.isEnabled = idle
        val nextLens = availableCameraLenses.firstOrNull { it != cameraLens }
        cameraSwitchButton.contentDescription = nextLens?.let { "Switch to ${it.displayName.lowercase()} camera" }
    }

    private fun updateSystemBars(visible: Boolean) {
        WindowCompat.getInsetsController(window, findViewById(R.id.main)).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // Light screens get dark bar icons in light mode; video screens keep light icons.
            val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            // Monochrome dark in every mode: light bar icons always (night kept for clarity).
            isAppearanceLightStatusBars = visible && !night && resources.getBoolean(R.bool.light_system_bars)
            isAppearanceLightNavigationBars = visible && !night && resources.getBoolean(R.bool.light_system_bars)
            if (visible) {
                show(WindowInsetsCompat.Type.systemBars())
            } else {
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun startDebugBenchmark(run: (DebugBenchmarkCoordinator) -> Unit) {
        if (!BuildConfig.DEBUG || localTestRunning) return
        localTestRunning = true
        updateStartEnabled()
        lateinit var coordinator: DebugBenchmarkCoordinator
        coordinator = DebugBenchmarkCoordinator(this, ::setStatus) {
            runOnUiThread {
                if (debugBenchmarkCoordinator === coordinator) debugBenchmarkCoordinator = null
                localTestRunning = false
                updateStartEnabled()
            }
        }
        debugBenchmarkCoordinator?.close()
        debugBenchmarkCoordinator = coordinator
        run(coordinator)
    }

    private fun configureSettingsControls() {
        val scaleModeLabels = VideoScaleMode.entries.map(VideoScaleMode::label)
        videoScaleModeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            scaleModeLabels,
        )
        videoScaleModeSpinner.setSelection(
            VideoScaleMode.entries.indexOf(videoScaleMode),
            false,
        )
        videoScaleModeDescriptionText.text = videoScaleMode.description
        // "Fill screen with video": on = FILL, off = FIT. The spinner (hidden) still owns the stored value.
        val fillSwitch = findViewById<MaterialSwitch>(R.id.videoFillSwitch)
        fillSwitch.isChecked = videoScaleMode == VideoScaleMode.FILL
        fillSwitch.setOnCheckedChangeListener { _, fill ->
            val target = if (fill) VideoScaleMode.FILL else VideoScaleMode.FIT
            videoScaleModeSpinner.setSelection(VideoScaleMode.entries.indexOf(target))
        }
        videoScaleModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = VideoScaleMode.entries.getOrNull(position) ?: return
                videoScaleMode = selected
                if (fillSwitch.isChecked != (selected == VideoScaleMode.FILL)) fillSwitch.isChecked = selected == VideoScaleMode.FILL
                videoScaleModeDescriptionText.text = selected.description
                appPreferences.edit()
                    .putString(PREF_VIDEO_SCALE_MODE, selected.name)
                    .apply()
                applyVideoScaleMode()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        deviceHealthOverlaySwitch.isChecked = showDeviceHealthOverlay
        deviceHealthOverlaySwitch.setOnCheckedChangeListener { _, enabled ->
            showDeviceHealthOverlay = enabled
            appPreferences.edit().putBoolean(PREF_SHOW_DEVICE_HEALTH, enabled).apply()
            applyDeviceHealthOverlayVisibility()
        }
        forwardReceivedStreamsSwitch.isChecked = forwardReceivedStreams
        forwardReceivedStreamsSwitch.setOnCheckedChangeListener { button, enabled ->
            forwardReceivedStreams = enabled
            appPreferences.edit().putBoolean(PREF_FORWARD_RECEIVED_STREAMS, enabled).apply()
            if (::watchRelaySwitch.isInitialized && watchRelaySwitch.isChecked != enabled) watchRelaySwitch.isChecked = enabled
            // Relaying is an always-on role: offer the battery exemption once, with the reason (user taps only).
            if (enabled && button.isPressed) BatteryExemptionPrompt.offerWithExplanation(this, BatteryOfferTrigger.ALWAYS_ON_ROLE)
        }
        receiverPlaybackDelaySlider.value = receiverPlaybackDelay.sliderValue
        experimentalTemporalLayersSwitch.isChecked =
            appPreferences.getBoolean(PREF_EXPERIMENTAL_TEMPORAL_LAYERS, false)
        experimentalTemporalLayersSwitch.setOnCheckedChangeListener { _, enabled ->
            appPreferences.edit().putBoolean(PREF_EXPERIMENTAL_TEMPORAL_LAYERS, enabled).apply()
            applyExperimentalTemporalLayers()
        }
        relayChildMediaAgeSwitch.isChecked = appPreferences.getBoolean(PREF_RELAY_CHILD_MEDIA_AGE, true)
        relayChildMediaAgeSwitch.setOnCheckedChangeListener { _, enabled ->
            appPreferences.edit().putBoolean(PREF_RELAY_CHILD_MEDIA_AGE, enabled).apply()
            applyRelayChildMediaAge()
        }
        findViewById<MaterialSwitch>(R.id.reachableForCallsSwitch).apply {
            isChecked = appPreferences.getBoolean(PREF_REACHABLE_FOR_CALLS, true)
            setOnCheckedChangeListener { button, enabled ->
                appPreferences.edit().putBoolean(PREF_REACHABLE_FOR_CALLS, enabled).apply()
                applyCallReachability()
                refreshCallReachability()
                // Being reachable for calls keeps a foreground service up: offer the battery exemption once (user taps only).
                if (enabled && button.isPressed) BatteryExemptionPrompt.offerWithExplanation(this@MainActivity, BatteryOfferTrigger.ALWAYS_ON_ROLE)
            }
        }
        bindConnectivityFeatures()
        bindViewerFeatures()
        bindUpdateFeatures()
        rotatingIdsVisibleButton.setOnClickListener {
            if (!RotatingDiscoveryFeature.enabled) {
                Toast.makeText(this, R.string.rotating_ids_visible_off, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val endsAt = RotatingDiscovery.startVisibleByHandle()
            val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(endsAt))
            Toast.makeText(this, getString(R.string.rotating_ids_visible_until, time), Toast.LENGTH_LONG).show()
        }
        rotatingIdsResetKeyButton.setOnClickListener {
            val vault = if (RotatingDiscoveryFeature.enabled) RotatingDiscovery.presenceVault() else null
            if (vault == null) {
                Toast.makeText(this, R.string.rotating_ids_reset_off, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setMessage(R.string.rotating_ids_reset_confirm)
                .setPositiveButton(R.string.rotating_ids_reset_key) { _, _ ->
                    Thread {
                        runCatching { vault.resetKey() }
                        RotatingDiscovery.requestRecognitionRebuild()
                        runOnUiThread { Toast.makeText(this, R.string.rotating_ids_reset_done, Toast.LENGTH_SHORT).show() }
                    }.start()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .showTracked()
        }
        neutralNameSwitchBinding = true
        neutralBluetoothNameSwitch.isChecked = neutralNameShown.checked
        neutralNameSwitchBinding = false
        neutralBluetoothNameSwitch.setOnCheckedChangeListener { _, enabled ->
            if (neutralNameSwitchBinding) return@setOnCheckedChangeListener
            if (!enabled) {
                NeutralNameChoice.record(appPreferences, hide = false)
                applyNeutralName()
                // Still "Phone" with no own name to restore: ask for it rather than leave the phone stuck.
                if (needsOwnBluetoothName()) showOwnBluetoothNameDialog(hideAfter = false)
                return@setOnCheckedChangeListener
            }
            // Ask first: this renames the phone for everything, not only for this app.
            neutralNameSwitchBinding = true
            neutralBluetoothNameSwitch.isChecked = false
            neutralNameSwitchBinding = false
            // Already "Phone" with no own name saved: ask for the name to restore later instead of "Your name now: Phone".
            if (needsOwnBluetoothName()) {
                showOwnBluetoothNameDialog(hideAfter = true)
                return@setOnCheckedChangeListener
            }
            val name = NeutralBluetoothNameController.currentName(bluetoothAdapter)
                ?: getString(R.string.neutral_bluetooth_name_unknown)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.neutral_bluetooth_name_confirm, name))
                .setPositiveButton(R.string.neutral_bluetooth_name_turn_on) { _, _ ->
                    NeutralNameChoice.record(appPreferences, hide = true)
                    applyNeutralName()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .showTracked()
        }
        experimentalRelayedCallsSwitch.isChecked = appPreferences.getBoolean(RelayedCallsFeature.PREF, false)
        experimentalRelayedCallsSwitch.setOnCheckedChangeListener { _, enabled ->
            if (enabled && !appPreferences.getBoolean(RelayedCallsFeature.PREF_PROMPTED, false)) {
                // Asked once, both choices preselected on (DECISIONS 2026-10-10, points 2 and 3).
                experimentalRelayedCallsSwitch.isChecked = false
                showRelayedCallsPrompt()
                return@setOnCheckedChangeListener
            }
            appPreferences.edit().putBoolean(RelayedCallsFeature.PREF, enabled).apply()
            applyRelayedCalls()
        }
        relayedCallsHelpSwitch.isChecked = appPreferences.getBoolean(RelayedCallsFeature.PREF_HELP, true)
        relayedCallsHelpSwitch.setOnCheckedChangeListener { _, enabled ->
            appPreferences.edit().putBoolean(RelayedCallsFeature.PREF_HELP, enabled).apply()
            applyRelayedCalls()
        }
        relayedCallsConsentSwitch.isChecked = appPreferences.getBoolean(RelayedCallsFeature.PREF_CONSENT, true)
        relayedCallsConsentSwitch.setOnCheckedChangeListener { _, enabled ->
            appPreferences.edit().putBoolean(RelayedCallsFeature.PREF_CONSENT, enabled).apply()
            applyRelayedCalls()
        }
        advancedSettingsSwitch.isChecked = advancedSettings
        applySettingsModeVisibility()
        advancedSettingsSwitch.setOnCheckedChangeListener { _, enabled ->
            advancedSettings = enabled
            appPreferences.edit().putBoolean(PREF_ADVANCED_SETTINGS, enabled).apply()
            applySettingsModeValues()
            applyExperimentalTemporalLayers()
            applyRelayChildMediaAge()
            applyIntercomFeatures()
            applyRotatingIds()
            applyRelayedCalls()
            refreshFeatureCatalog()
            receiverPlaybackDelaySlider.value = receiverPlaybackDelay.sliderValue
            refreshRateControlPicker()
            applySettingsModeVisibility()
            updatePrivateCallProfileSummary()
            updateSettingsLabels()
        }
        receiverPlaybackDelaySlider.addOnChangeListener { _, value, _ ->
            val selected = PlaybackDelayProfile.fromSliderValue(value)
            if (selected == receiverPlaybackDelay) return@addOnChangeListener
            receiverPlaybackDelay = selected
            updatePlaybackDelayLabels()
            updatePrivateCallProfileSummary()
            appPreferences.edit()
                .putString(PREF_RECEIVER_PLAYBACK_DELAY, selected.name)
                .apply()
        }
        thermalReductionSwitch.isChecked = automaticThermalReductionEnabled
        thermalReductionSwitch.setOnCheckedChangeListener { _, enabled ->
            automaticThermalReductionEnabled = enabled
            appPreferences.edit()
                .putBoolean(PREF_AUTOMATIC_THERMAL_REDUCTION, enabled)
                .apply()
            thermalHandler.removeCallbacks(thermalRecoveryRunnable)
            if (activeStreamSettings != null) {
                applyThermalAdaptation(thermalStatus)
                setStatus(
                    if (enabled) {
                        "Automatic cooling enabled for hot conditions."
                    } else {
                        "Automatic cooling disabled. Critical heat will still stop streaming."
                    },
                )
            }
        }
        rateControlSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingRateControlPicker) return
                encoderRateControl = rateControlOptions.getOrNull(position)
                    ?: EncoderRateControl.AUTOMATIC
                if (advancedSettings && encoderRateControl != EncoderRateControl.AUTOMATIC) {
                    appPreferences.edit()
                        .putString(PREF_MANUAL_RATE_CONTROL, encoderRateControl.name)
                        .apply()
                }
                updateRateControlDescription()
                updateSettingsLabels()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        resolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingModePickers) return
                // A raw fixture's declared geometry is never replaced by a camera mode, even by the deferred
                // selection callback that setSelection() queues after refreshVideoModes() has finished.
                if (!RawFixtureGeometry.pickersMayChangeSettings(debugRawInputFile != null)) return
                val mode = deviceModes.getOrNull(position) ?: return
                settings = settings.copy(resolution = mode.resolution)
                persistVideoSettings()
                updatingModePickers = true
                refreshFpsPicker(settings.fps)
                updatingModePickers = false
                updateSettingsLabels()
                restartIdleCameraPreview()
                updateStartEnabled()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        fpsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingModePickers) return
                if (!RawFixtureGeometry.pickersMayChangeSettings(debugRawInputFile != null)) return
                val mode = selectedDeviceMode() ?: return
                val fps = mode.fpsOptions.getOrNull(position) ?: return
                settings = settings.copy(fps = fps)
                persistVideoSettings()
                updateSettingsLabels()
                updateStartEnabled()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        configureSeekBar(
            bitrateSeekBar,
            (bitrateSliderMaximumKbps() - VideoSettings.MIN_BIT_RATE_KBPS) /
                VideoSettings.BIT_RATE_STEP_KBPS,
            (settings.bitRate / 1_000 - VideoSettings.MIN_BIT_RATE_KBPS) /
                VideoSettings.BIT_RATE_STEP_KBPS,
            { progress ->
                val kbps = VideoSettings.MIN_BIT_RATE_KBPS + progress * VideoSettings.BIT_RATE_STEP_KBPS
                settings = settings.copy(bitRate = kbps * 1_000)
                updateSettingsLabels()
            },
            {
                refreshVideoModes()
                persistVideoSettings()
            },
        )
        updateSettingsLabels()
    }

    /**
     * Experimental temporal layering (off by default; see DebugTunables.experimentalTemporalLayers).
     * A DEBUG benchmark launch (any `test_` extra) uses only its `test_temporal_layers` extra, default
     * off, so a stored UI choice never leaks into a measured case. Otherwise the persisted Experiments
     * setting applies, with or without Manual video settings (design decision 2026-11-06). Read when a stream starts.
     */
    private fun applyExperimentalTemporalLayers() {
        applyExperimentalTemporalLayersOnly()
    }

    /**
     * Relay-child skip-to-keyframe (on by default; DebugTunables.experimentalRelayChildMediaAge). A
     * DEBUG benchmark launch uses only its `test_relay_child_media_age` extra (default on). Otherwise
     * the Advanced setting applies in any build, and the default (on) applies while Advanced settings
     * are off. Read when forwarding starts.
     */
    private fun applyRelayChildMediaAge() {
        val benchmarkLaunch = benchmarkExtrasPresent()
        DebugTunables.experimentalRelayChildMediaAge = relayChildMediaAgeSetting(
            benchmarkLaunch = benchmarkLaunch,
            benchmarkExtra = intent.getBooleanExtra("test_relay_child_media_age", true),
            storedChoice = appPreferences.getBoolean(PREF_RELAY_CHILD_MEDIA_AGE, true),
        )
    }

    private fun applyExperimentalTemporalLayersOnly() {
        val benchmarkLaunch = benchmarkExtrasPresent()
        DebugTunables.experimentalTemporalLayers = if (benchmarkLaunch) {
            intent.getBooleanExtra("test_temporal_layers", false)
        } else {
            appPreferences.getBoolean(PREF_EXPERIMENTAL_TEMPORAL_LAYERS, false)
        }
    }

    /**
     * Experimental voice calls and push-to-talk (off by default; see IntercomFeatureSettings). Same
     * rule as temporal layering: a DEBUG benchmark launch uses only its `test_voice_calls`,
     * `test_push_to_talk` and `test_ptt_mode` extras; otherwise the Advanced settings apply. Read
     * when the call endpoint is created and when a call's media starts.
     */
    private fun applyIntercomFeatures() {
        val benchmarkLaunch = callFeatureBenchmarkLaunch()
        val resolved = IntercomFeatureSettings.resolve(
            benchmarkLaunch,
            { intent.getBooleanExtra(it, false) },
            ::storedCallFeatureChoice,
        )
        if (resolved != IntercomFeatures.current && BuildConfig.DEBUG) {
            Log.i("BluetoothVideo", "BENCHMARK intercom_features: ${IntercomFeatures.label(resolved)}")
        }
        IntercomFeatures.current = resolved
        applyCallUxFeatures()
        applyStoreForwardFeature(benchmarkLaunch)
        applyPeerLabelFeature(benchmarkLaunch)
        updateHomeActions()
        callFeatureBinder?.refresh()
    }

    /** A DEBUG build launched with any `test_` extra: features read only their extras (default off). */
    private fun callFeatureBenchmarkLaunch(): Boolean = benchmarkExtrasPresent()

    /** The user's explicit choice for a promoted feature switch, or null when never touched. */
    private fun storedCallFeatureChoice(key: String): Boolean? = CallFeatureResolver.storedChoice(appPreferences, key)

    /**
     * Call UX switches (CallUx) and the lock-screen mirror used by the benchmark tunables: audio focus
     * and the ringer policy, plus the promoted ringback, call-notification, lock-screen and ear-pause
     * switches. Read again when a call starts, so a Settings change applies to the next call.
     */
    private fun applyCallUxFeatures() {
        CallUx.settings = CallUxSettings.resolve(
            BuildConfig.DEBUG,
            DebugTunables.benchmarkLaunch,
            ::storedCallFeatureChoice,
        ) { intent.getBooleanExtra(it, false) }
        DebugTunables.callLockScreen = CallUx.settings.lockScreen
        DebugTunables.callAudioConceal = CallUx.settings.audioConcealment
        // The Decline / Hang up buttons need their receiver: register it when the switch turns on at runtime.
        registerCallNotificationReceiver()
    }

    private var callFeatureBinder: CallFeatureSettingsBinder? = null

    /** Settings > Features (calls and messages): binds `settings_features_calls.xml`. */
    private fun bindCallFeatureSettings() {
        val root = findViewById<View>(R.id.settingsScreen) ?: return
        callFeatureBinder = CallFeatureSettingsBinder(
            root,
            appPreferences,
            object : CallFeatureHost {
                override val benchmarkLaunch: Boolean get() = callFeatureBenchmarkLaunch()

                override fun onCallFeaturesChanged() {
                    val before = callFeatureSet()
                    applyIntercomFeatures()
                    rebuildIdleCallEndpointForFeatures(before)
                }

                override fun requestNotificationPermission() {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        callNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            },
            current = { callFeatureSet() },
        ).also { it.bind() }
    }

    /**
     * The call endpoint reads voice calls and the message hook when it is created. After a Settings change
     * an idle endpoint (Home, waiting for calls) is rebuilt so the next call, in either direction, follows
     * the switch; a ringing or connected call keeps its endpoint and the next one picks the change up.
     */
    private fun rebuildIdleCallEndpointForFeatures(before: CallFeatureSet) {
        val now = callFeatureSet()
        if (before.voiceCalls == now.voiceCalls && before.messages == now.messages) return
        if (!privateCallEndpointIdle()) return
        Log.i(
            "BluetoothVideo",
            "Call endpoint rebuilt for features: voiceCalls=${now.voiceCalls} | messages=${now.messages}",
        )
        stopPrivateCallEndpoint()
        refreshCallReachability(delayMs = FEATURE_ENDPOINT_REBUILD_DELAY_MS)
    }

    private fun callFeatureSet(): CallFeatureSet = CallFeatureSet(
        voiceCalls = IntercomFeatures.current.voiceCalls,
        ringback = CallUx.settings.ringback,
        earCameraPause = CallUx.settings.proximityCameraPause,
        lockScreenCalls = CallUx.settings.lockScreen,
        callNotifications = CallUx.settings.callStyleNotifications,
        messages = StoreForwardFeature.enabled,
        autoSync = StoreForwardAutoSyncFeature.enabled,
        namesAndTitles = PeerLabelFeature.enabled,
    )

    /**
     * Experimental peer labels (PeerLabelFeature, default off): same launch rule as the intercom
     * switches (`test_peer_labels`). A DEBUG benchmark launch may also give `test_peer_label_name`
     * and `test_stream_title` (strings) so phone cases need no typing. Off: no
     * hello bit, no LABELS record, no title pages, and no name field.
     */
    private fun applyPeerLabelFeature(benchmarkLaunch: Boolean) {
        val enabled = PeerLabelFeature.resolve(
            benchmarkLaunch,
            { intent.getBooleanExtra(it, false) },
            ::storedCallFeatureChoice,
        )
        if (enabled != PeerLabelFeature.enabled) {
            Log.i("BluetoothVideo", "BENCHMARK peer_labels_feature: enabled=$enabled")
        }
        PeerLabelFeature.enabled = enabled
        // Naming v2: the public title pages, the audience and the timing of the caller's name are their own settings.
        PeerLabelFeature.titlePagesEnabled = NamingFeatures.resolveTitlePages(
            benchmarkLaunch,
            { intent.getBooleanExtra(it, false) },
            ::storedCallFeatureChoice,
        )
        PeerLabelFeature.audience = NamingSettings.audience(
            benchmarkLaunch,
            { intent.getStringExtra(it) },
            { appPreferences.getString(it, null) },
        )
        PeerLabelFeature.nameBeforeAnswer = NamingSettings.nameBeforeAnswer(
            benchmarkLaunch,
            { intent.getBooleanExtra(it, false) },
            ::storedCallFeatureChoice,
        )
        PeerLabels.knownNames = { contactNames.values + listOfNotNull(PeerLabelFeature.localName) }
        if (enabled) {
            // The UTS #39 table (85 KB) loads once, off the main thread; names compare with the minimal table until then.
            val appContext = applicationContext
            Thread { Confusables.install(appContext) }.start()
        }
        val storedName = if (benchmarkLaunch) {
            intent.getStringExtra("test_peer_label_name")
        } else {
            appPreferences.getString(PeerLabelFeature.PREF_NAME, null)
        }
        PeerLabelFeature.localName = storedName?.let { runCatching { LabelText.normalize(LabelKind.PERSON_NAME, it) }.getOrNull() }
        if (benchmarkLaunch) {
            PeerLabelFeature.localStreamTitle = intent.getStringExtra("test_stream_title")
                ?.let { runCatching { LabelText.normalize(LabelKind.STREAM_TITLE, it) }.getOrNull() }
        }
        if (!enabled) SessionPeerLabels.clearAll()
        if (!PeerLabelFeature.titlePagesEnabled) StreamTitles.board.clear()
        refreshIdentityName()
    }

    /** Settings > Your ID: the optional name button (experimental peer labels only). */
    private fun refreshIdentityName() {
        val button = findViewById<View>(R.id.identityNameButton) ?: return
        // "Your name": a row with the current name as its value (only while names and titles is on).
        button.visibility = if (PeerLabelFeature.enabled) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.identityNameValue).text = PeerLabelFeature.localName
            ?: OwnNearbyName.nameNotSet(this)
        refreshIdentityAudience()
        button.setOnClickListener {
            // Back, a tap outside, Cancel and a rejected name keep the current name; only Save with an empty field clears it
            // (before 2026-11-04 Back and Skip saved "no name" and erased the name and its "Who sees my name" row).
            promptLabelText(
                LabelKind.PERSON_NAME, R.string.identity_name_title, R.string.identity_name_hint, PeerLabelFeature.localName,
                onCancel = {}, onSkip = {}, negativeRes = R.string.action_cancel, onInvalid = {},
            ) { name ->
                PeerLabelFeature.localName = name
                appPreferences.edit().apply {
                    if (name == null) remove(PeerLabelFeature.PREF_NAME) else putString(PeerLabelFeature.PREF_NAME, name)
                }.apply()
                refreshIdentityName()
            }
        }
    }

    /** Settings > Your ID: "Who sees my name" (Naming v2), shown only while names are on and a name is set. */
    private fun refreshIdentityAudience() {
        val row = findViewById<View>(R.id.identityAudienceButton) ?: return
        row.visibility = if (PeerLabelFeature.enabled && PeerLabelFeature.localName != null) View.VISIBLE else View.GONE
        val labels = intArrayOf(R.string.names_audience_everyone, R.string.names_audience_verified, R.string.names_audience_nobody)
        findViewById<TextView>(R.id.identityAudienceValue).text = getString(labels[PeerLabelFeature.audience.ordinal])
        row.setOnClickListener {
            if (DebugTunables.benchmarkLaunch) return@setOnClickListener
            val choices = Array(labels.size) { getString(labels[it]) }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.names_audience_title)
                // No setMessage: an AlertDialog with a message hides its choice list (0.4.4 showed no options at all);
                // the explanation is the note under the name rows (identity_name_note).
                .setSingleChoiceItems(choices, PeerLabelFeature.audience.ordinal) { dialog, which ->
                    val chosen = NamesAudience.entries[which]
                    PeerLabelFeature.audience = chosen
                    appPreferences.edit().putString(NamingSettings.PREF_AUDIENCE, chosen.prefValue).apply()
                    dialog.dismiss()
                    refreshIdentityAudience()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .showTracked()
        }
    }

    /** The Go live title typed last (kept after Back, Skip or the end of a stream) to prefill the next title dialog. */
    private var streamTitleDraft: String? = null

    /**
     * One optional text field (name or stream title) checked with [LabelText]. Empty
     * means none. With peer labels off, [onDone] runs at once with null (no dialog, unchanged flow).
     * [onCancel] (Back or a tap outside) and [onSkip] get the typed text; when absent, both mean [onDone] with null.
     * [onInvalid] runs after the rejected-text message instead of [onDone] with null.
     */
    private fun promptLabelText(
        kind: LabelKind,
        titleRes: Int,
        hintRes: Int,
        initial: String?,
        onCancel: ((String) -> Unit)? = null,
        onSkip: ((String) -> Unit)? = null,
        positiveRes: Int = R.string.action_save,
        negativeRes: Int = R.string.action_skip,
        onInvalid: (() -> Unit)? = null,
        onDone: (String?) -> Unit,
    ) {
        // Naming v2: the stream title also works with only the public title pages switched on.
        if (!PeerLabelFeature.enabled && !(kind == LabelKind.STREAM_TITLE && PeerLabelFeature.titlePagesEnabled)) {
            onDone(null)
            return
        }
        val input = android.widget.EditText(this).apply {
            hint = getString(hintRes)
            setText(initial.orEmpty())
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(kind.maxBytes))
        }
        val container = android.widget.FrameLayout(this).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(positiveRes) { _, _ ->
                val text = input.text?.toString().orEmpty()
                if (text.isBlank()) {
                    onDone(null)
                } else {
                    val result = runCatching { LabelText.normalize(kind, text) }
                    result.onSuccess(onDone).onFailure { error ->
                        android.widget.Toast.makeText(
                            this,
                            getString(R.string.identity_name_invalid, error.message ?: "invalid"),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        if (onInvalid != null) onInvalid() else onDone(null)
                    }
                }
            }
            .setNegativeButton(negativeRes) { _, _ ->
                if (onSkip != null) onSkip(input.text?.toString().orEmpty()) else onDone(null)
            }
            .setOnCancelListener {
                if (onCancel != null) onCancel(input.text?.toString().orEmpty()) else onDone(null)
            }
            .showTracked()
    }

    /**
     * Experimental store-and-forward messages (StoreForwardFeature, default off): same launch rule
     * as the intercom switches (`test_store_forward`). Off: no Messages entry and every call
     * endpoint is created without the message-exchange hook.
     */
    private fun applyStoreForwardFeature(benchmarkLaunch: Boolean) {
        val enabled = StoreForwardFeature.resolve(
            benchmarkLaunch,
            { intent.getBooleanExtra(it, false) },
            ::storedCallFeatureChoice,
        )
        if (enabled != StoreForwardFeature.enabled && BuildConfig.DEBUG) {
            Log.i("BluetoothVideo", "BENCHMARK store_forward_feature: enabled=$enabled")
        }
        StoreForwardFeature.enabled = enabled
        // Messaging v2 (DECISIONS Design M): on with Messages (design decision 2026-10-21); DEBUG test_messaging_v2=false is v1.
        val v2 = enabled && FeatureCatalog.resolve(MessagingV2Feature.spec, benchmarkLaunch, ::launchExtra, ::storedCallFeatureChoice)
        if (v2 != MessagingV2Feature.enabled && BuildConfig.DEBUG) {
            Log.i("BluetoothVideo", "BENCHMARK store_forward_feature: messaging_v2=$v2")
        }
        MessagingV2Feature.enabled = v2
        MessagingV2Options.configure(
            benchmarkLaunch,
            { key -> if (intent.hasExtra(key)) intent.getIntExtra(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null },
            { key -> if (intent.hasExtra(key)) intent.getBooleanExtra(key, false) else null },
            MessagingV2PrivacyRows.readChoices(appPreferences),
        )
        // The thread link is a sub-switch (default on inside the feature; false is the control arm).
        MessagingV2ChatFeature.enabled =
            v2 && FeatureCatalog.resolve(MessagingV2ChatFeature.spec, benchmarkLaunch, ::launchExtra, ::storedCallFeatureChoice)
        scheduleChatTick()
        // Chats are a tab now (ChatTab), never a button on the Live page.
        findViewById<View>(R.id.homeMessagesButton)?.visibility = View.GONE
        if (::mainNav.isInitialized) mainNav.menu.findItem(R.id.navChats)?.isVisible = ChatTab.visible(enabled)
        // Automatic sync (Settings > Features, or DEBUG `test_sf_auto_sync`), on top of the above.
        val auto = StoreForwardAutoSyncFeature.resolve(
            benchmarkLaunch,
            { intent.getBooleanExtra(it, false) },
            ::storedCallFeatureChoice,
            enabled,
        )
        if (auto != StoreForwardAutoSyncFeature.enabled && BuildConfig.DEBUG) {
            Log.i("BluetoothVideo", "BENCHMARK sf_sync auto=feature enabled=$auto")
        }
        StoreForwardAutoSyncFeature.enabled = auto
        refreshMessagesUnread(notify = false)
    }

    /**
     * Messages tile on Home: the unread count (messages received since the Messages screen was last
     * left). With [notify], also a quiet notification when the app is not in front and Messages is
     * not open (MessageNotifications). Counted off the main thread.
     */
    private fun refreshMessagesUnread(notify: Boolean) {
        val tile = findViewById<com.google.android.material.button.MaterialButton>(R.id.homeMessagesButton) ?: return
        if (!StoreForwardFeature.enabled) {
            tile.setText(R.string.home_messages)
            return
        }
        val context = applicationContext
        Thread {
            val count = runCatching {
                val node = StoreForwardRuntime.node(context)
                val seen = InboxSeenStore.forRoot(StoreForwardRuntime.root(context)).read()
                UnreadMessages.count(node.archive.page(0, UNREAD_SCAN_LIMIT, MessageDirection.IN).filterNot { LocalReporting.hidden(context, it.peerNode) }, seen)
            }.getOrDefault(0)
            // The Chats tab's badge: per-chat unread counts with Messaging v2, else the Messages count.
            val badge = if (MessagingV2Feature.enabled) {
                runCatching {
                    val node = StoreForwardRuntime.node(context)
                    if (node.v2 == null) null else ChatTab.badge(ConversationIndex(node).summaries(showPreview = false, sending = { false }).filterNot { LocalReporting.hidden(context, it.peer.toString()) }.map { it.unread })
                }.getOrNull()
            } else {
                ChatTab.badge(listOf(count))
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (::mainNav.isInitialized) {
                    if (badge == null) {
                        mainNav.removeBadge(R.id.navChats)
                    } else {
                        mainNav.getOrCreateBadge(R.id.navChats).apply {
                            number = badge
                            isVisible = true
                        }
                    }
                }
                tile.text = if (count > 0) getString(R.string.home_messages_unread, count) else getString(R.string.home_messages)
                // Messaging v2 posts one notification per conversation instead (MessagingV2Notifications.kt).
                if (notify && !MessagingV2Feature.enabled && MessageNotifications.shouldPost(count, MessagesScreenState.visible, activityResumed)) {
                    MessageNotifications.post(context, count)
                }
            }
        }.start()
    }

    /**
     * Automatic CoC -> GATT fallback (Settings > Features, see CocAutoGattSettings). A DEBUG benchmark
     * launch uses only `test_coc_auto_gatt` and `test_coc_gatt_threshold`; every other launch the user's
     * choice or the shipped default, with no dependence on Manual settings. Read when a call endpoint is
     * created.
     */
    private fun applyCocAutoGatt() {
        val resolved = CocAutoGattSettings.resolve(
            DebugTunables.benchmarkLaunch,
            intent.getBooleanExtra(CocAutoGattSettings.EXTRA_ENABLED, false),
            intent.getIntExtra(CocAutoGattSettings.EXTRA_THRESHOLD, CocAutoGattSettings.DEFAULT_THRESHOLD),
            null, // no Settings switch since 2026-11-04: the shipped default (a stored choice is ignored)
            DeviceFamilyPolicy.leCocServerLeaks(BluetoothStackFingerprint.current()),
        )
        val streamsSwitch = StreamGattFallback.resolveSwitch(
            BuildConfig.DEBUG,
            DebugTunables.benchmarkLaunch,
            intent.getBooleanExtra(StreamGattFallback.EXTRA_STREAMS, false),
        )
        if (resolved != CocAutoGatt.current) {
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "coc_auto_gatt: enabled=${resolved.enabled} | threshold=${resolved.threshold} | " +
                    "thresholdActive=${resolved.thresholdActive}",
            )
        }
        if (streamsSwitch != StreamGattFallback.streamsSwitch) {
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "coc_auto_gatt_streams: enabled=$streamsSwitch | coc_auto_gatt=${resolved.enabled}",
            )
        }
        StreamGattFallback.streamsSwitch = streamsSwitch
        CocAutoGatt.current = resolved
    }

    /**
     * Bond-free Classic, "Connect without pairing" (ClassicFeatureSettings). A DEBUG benchmark launch
     * uses only `test_classic_bond_free`, `test_classic_bounded_connect` and `test_classic_request_visible`;
     * every other launch the user's choice or the shipped default. DEBUG address overrides
     * (`test_classic_addresses`, or `test_classic_address` + `test_classic_address_node`) let a run
     * supply each peer's Classic address read over adb; they are never stored. Read when a Classic
     * socket is created.
     */
    private fun applyClassicFeatures() {
        val resolved = ClassicFeatureSettings.resolve(
            DebugTunables.benchmarkLaunch,
            { intent.getBooleanExtra(it, false) },
            ::storedFeatureChoice,
        )
        if (resolved != ClassicFeatures.current) {
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "classic_features: ${ClassicFeatures.label(resolved)}")
        }
        ClassicFeatures.current = resolved
        if (BuildConfig.DEBUG) {
            val overrides = parseClassicAddressOverrides(intent.getStringExtra("test_classic_addresses")).toMutableMap()
            val legacyPrefix = debugClassicAddressNodePrefix
            val legacyAddress = debugClassicAddress
            if (legacyPrefix != null && legacyAddress != null) overrides[legacyPrefix] = legacyAddress
            ClassicAddressBook.setDebugOverrides(overrides)
            if (overrides.isNotEmpty()) Log.i("BluetoothVideo", "BENCHMARK classic_address_overrides: count=${overrides.size}")
        }
    }

    /**
     * iPhone compact discovery, "Find iPhones" (CompactDiscoveryFeature). A DEBUG benchmark launch uses
     * only `test_ios_compact_discovery`; every other launch the user's choice or the shipped default.
     * Read when a discovery scan is created.
     */
    private fun applyCompactDiscovery() {
        val enabled = CompactDiscoveryFeature.resolve(
            DebugTunables.benchmarkLaunch,
            { intent.getBooleanExtra(it, false) },
            ::storedFeatureChoice,
        )
        if (enabled != CompactDiscoveryFeature.enabled) {
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "compact_discovery: enabled=$enabled")
            // Switching it on again is a new try: an earlier refused filter is forgotten.
            if (enabled) CompactDiscoveryFeature.filterRejected = false
        }
        CompactDiscoveryFeature.enabled = enabled
    }

    /**
     * "Find PCs" (PcAnnouncementFeature, Experimental, default off). A DEBUG benchmark launch uses only `test_find_pcs`;
     * every other launch the user's choice or the shipped default. Read when a discovery scan is created.
     */
    private fun applyFindPcs() {
        val enabled = PcAnnouncementFeature.resolve(
            DebugTunables.benchmarkLaunch,
            { intent.getBooleanExtra(it, false) },
            ::storedFeatureChoice,
        )
        if (enabled != PcAnnouncementFeature.enabled) {
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "find_pcs: enabled=$enabled")
            // Switching it on again is a new try: an earlier refused filter is forgotten.
            if (enabled) PcAnnouncementFeature.filterRejected = false
        }
        PcAnnouncementFeature.enabled = enabled
        PcStreamsFeature.enabled = FeatureCatalog.resolve(
            ConnectivityFeatures.pcStreams, DebugTunables.benchmarkLaunch,
            { intent.getBooleanExtra(it, false) }, ::storedFeatureChoice,
        )
    }

    /**
     * Rotating discovery IDs (RotatingDiscoveryFeature, default [RotatingDiscoveryFeature.DEFAULT_ENABLED] for
     * user launches; a DEBUG benchmark launch reads only `test_rotating_ids`). Read when a call
     * endpoint or call scan is created. RV1: while on, the Bluetooth name is the neutral one; turning
     * it off restores the user's name (NeutralBluetoothNameController).
     */
    private fun applyRotatingIds() {
        val benchmarkLaunch = benchmarkExtrasPresent()
        val enabled = FeatureCatalog.resolve(RotatingDiscoveryFeature.spec, benchmarkLaunch, ::launchExtra, ::storedFeatureChoice)
        val changed = enabled != RotatingDiscoveryFeature.enabled
        if (changed) {
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotating_ids: enabled=$enabled")
        }
        RotatingDiscoveryFeature.enabled = enabled
        if (enabled) runCatching { RotatingDiscovery.init(this, AndroidNodeIdentityStore().getOrCreate().nodeId) }
        // DEBUG benchmark launch: `test_rotating_visible_window` starts a window (old-build interop cases).
        if (enabled && benchmarkLaunch && intent.getBooleanExtra("test_rotating_visible_window", false)) {
            runCatching { RotatingDiscovery.startVisibleByHandle() }
        }
        // Hardening 2026-10-11: no automatic rename. Connectable sets become non-discoverable on API 34+ (see
        // NonDiscoverableAdvertising) and the neutral name is the explicit option of applyNeutralName().
        if (enabled) runCatching { AdvertisingPrivacy.logOnce(bluetoothAdapter) }
        refreshNeutralNameText()
    }

    /**
     * The option "Hide phone name" (NeutralNameOption, default on since 2026-10-27, independent of the rotating switch).
     * The name is written only when the option changes and when the app starts, gets its permissions or Bluetooth comes
     * on; never per call, advertising session or epoch. A normal launch renames the phone only after the user answered
     * the question (onboarding page, or [showNeutralNameQuestion] once after an update): [NeutralNameChoice]. A DEBUG
     * benchmark launch changes the name only with `test_neutral_bluetooth_name` (alias `test_rotating_neutral_name`):
     * test phones keep their names, and the legacy Classic candidate plan, which matches bonded phones by name, never
     * sees several phones called "Phone". With the option off a name left by a build before 2026-10-11 (automatic
     * rename) is restored once.
     */
    private fun applyNeutralName() {
        val benchmarkLaunch = benchmarkExtrasPresent()
        // Builds before 2026-10-11 renamed the phone automatically with the rotating switch. A normal launch keeps
        // that name by turning the option on (nothing changes on the radio) and tells the user once; a DEBUG
        // benchmark launch restores the test phone's own name instead (the option is off there).
        if (!benchmarkLaunch && !appPreferences.contains(NeutralNameOption.PREF) &&
            NeutralBluetoothNameController.savedOriginal(this) != null &&
            NeutralBluetoothNameController.currentName(bluetoothAdapter) == NeutralBluetoothName.NEUTRAL
        ) {
            NeutralNameChoice.record(appPreferences, hide = true)
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotid_name: migrated=kept")
            showNeutralNameMigrationNotice()
        }
        val stored = NeutralNameChoice.storedChoice(appPreferences)
        val resolved = FeatureCatalog.resolve(NeutralNameOption.spec, benchmarkLaunch, ::launchExtra, ::storedFeatureChoice)
        // Default on, but never renamed before the user answered (the question is the rename consent).
        var optionOn = NeutralNameChoice.renameOn(resolved, stored, benchmarkLaunch)
        // B2: the default of DiscoverableWhenNeutral follows the option; each set start still reads the actual name.
        DiscoverableWhenNeutral.neutralOptionOn = optionOn
        val canRename = canRenameAdapter()
        if (!canRename) neutralNameRefused = false
        if (canRename && bluetoothAdapter?.isEnabled == true) {
            val outcome = runCatching {
                NeutralBluetoothNameController.apply(this, bluetoothAdapter, optionOn, appPreferences.contains(NeutralNameOption.PREF))
            }.getOrNull()
            neutralNameRefused = outcome?.refused == true
            if (outcome?.paused == true && !benchmarkLaunch) {
                // The user renamed the phone after the option was applied: their name wins; the option goes off.
                NeutralNameChoice.record(appPreferences, hide = false)
                optionOn = false
                DiscoverableWhenNeutral.neutralOptionOn = false
                Toast.makeText(this, R.string.neutral_bluetooth_name_paused, Toast.LENGTH_LONG).show()
            }
        }
        neutralNameOptionOn = optionOn
        neutralNameShown = NeutralNameChoice.shown(optionOn, canRename, neutralNameRefused)
        if (::neutralBluetoothNameSwitch.isInitialized && neutralBluetoothNameSwitch.isChecked != neutralNameShown.checked) {
            neutralNameSwitchBinding = true
            neutralBluetoothNameSwitch.isChecked = neutralNameShown.checked
            neutralNameSwitchBinding = false
        }
        refreshNeutralNameText()
        // An update from a build where the option was off by default: ask once (a new install asks in onboarding).
        if (NeutralNameChoice.needsAsking(stored, benchmarkLaunch) && OnboardingActivity.isDone(this)) showNeutralNameQuestion()
    }

    /** Renaming the adapter needs Nearby devices (BLUETOOTH_CONNECT) on Android 12+; older Android grants it at install. */
    private fun canRenameAdapter(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** The onboarding question "Hide your phone's name" as a dialog, once per process while it is unanswered. */
    private fun showNeutralNameQuestion() {
        if (neutralNameQuestionShown) return
        neutralNameQuestionShown = true
        runOnUiThread {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ob_name_title)
                .setMessage(R.string.ob_name_text)
                .setCancelable(false)
                .setPositiveButton(R.string.ob_name_hide) { _, _ ->
                    NeutralNameChoice.record(appPreferences, hide = true)
                    applyNeutralName()
                }
                .setNegativeButton(R.string.ob_name_keep) { _, _ ->
                    NeutralNameChoice.record(appPreferences, hide = false)
                    applyNeutralName()
                }
                .showTracked()
        }
    }

    /** The phone reads "Phone" and MeshLive has no own name saved to restore (NeutralBluetoothName.needsOwnName). */
    private fun needsOwnBluetoothName(): Boolean =
        canRenameAdapter() && bluetoothAdapter?.isEnabled == true &&
            NeutralBluetoothName.needsOwnName(
                NeutralBluetoothNameController.currentName(bluetoothAdapter),
                NeutralBluetoothNameController.savedOriginal(this),
            )

    /**
     * Recovery for a phone called "Phone" with no own name saved (app data cleared, or the lost-original state of the
     * 0.4.1 release candidate). [hideAfter]: the option is being turned on (or is on), so the name is saved and the
     * option restores it when turned off; otherwise the phone is renamed now. Prefilled with Android's device name.
     */
    private fun showOwnBluetoothNameDialog(hideAfter: Boolean) {
        val suggestion = NeutralBluetoothName.suggestedOwnName(
            runCatching { android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME) }
                .getOrNull(),
            Build.MODEL,
        )
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.neutral_bluetooth_name_own_title)
            setText(suggestion.orEmpty())
            setSelection(text.length)
            isSingleLine = true
        }
        val container = android.widget.FrameLayout(this).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.neutral_bluetooth_name_own_title)
            .setMessage(if (hideAfter) R.string.neutral_bluetooth_name_own_save else R.string.neutral_bluetooth_name_own_rename)
            .setView(container)
            .setPositiveButton(
                if (hideAfter) R.string.action_save else R.string.neutral_bluetooth_name_own_rename_action,
            ) { _, _ ->
                val name = input.text?.toString().orEmpty()
                if (NeutralBluetoothName.ownName(name.trim()) == null) {
                    Toast.makeText(this, R.string.neutral_bluetooth_name_own_invalid, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (hideAfter) {
                    NeutralBluetoothNameController.saveOwnName(this, name)
                    NeutralNameChoice.record(appPreferences, hide = true)
                    applyNeutralName()
                } else if (!NeutralBluetoothNameController.renameToOwnName(this, bluetoothAdapter, name)) {
                    Toast.makeText(this, R.string.neutral_bluetooth_name_own_failed, Toast.LENGTH_LONG).show()
                }
                // A rename is confirmed by ACTION_LOCAL_NAME_CHANGED, which refreshes the switch and its text.
                refreshNeutralNameText()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showTracked()
    }

    /** Once, after an update from a build that renamed the phone automatically: the name stays, the choice is theirs. */
    private fun showNeutralNameMigrationNotice() {
        if (appPreferences.getBoolean(PREF_NEUTRAL_NAME_NOTICE_SHOWN, false)) return
        appPreferences.edit().putBoolean(PREF_NEUTRAL_NAME_NOTICE_SHOWN, true).apply()
        val saved = NeutralBluetoothNameController.savedOriginal(this) ?: return
        runOnUiThread {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.neutral_bluetooth_name_migrated, saved))
                .setPositiveButton(R.string.neutral_bluetooth_name_keep, null)
                .setNegativeButton(R.string.neutral_bluetooth_name_restore) { _, _ ->
                    NeutralNameChoice.record(appPreferences, hide = false)
                    applyNeutralName()
                }
                .showTracked()
        }
    }

    /** The advice and saved-name lines under the neutral-name switch, and the legacy-controller note. */
    private fun refreshNeutralNameText(optionOn: Boolean = neutralNameOptionOn) {
        if (!::neutralBluetoothNameStatusText.isInitialized || !::rotatingIdsLegacyNoteText.isInitialized) return
        val reuse = AdvertisingPrivacy.addressReuse(bluetoothAdapter)
        val advice = AdvertisingPrivacy.neutralNameAdvice(
            sdkInt = Build.VERSION.SDK_INT,
            addressReuse = reuse,
            classicInUse = outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM || ClassicFeatures.current.bondFree,
            nonDiscoverableActive = RotatingDiscoveryFeature.enabled && NonDiscoverableAdvertising.forNewSet(connectable = true),
            stackRefused = NonDiscoverableAdvertising.rejected,
        )
        val adviceText = getString(
            when (advice) {
                AdvertisingPrivacy.NeutralNameAdvice.RECOMMENDED_OLD_ANDROID -> R.string.neutral_name_advice_old_android
                AdvertisingPrivacy.NeutralNameAdvice.RECOMMENDED_LEGACY_CONTROLLER -> R.string.neutral_name_advice_legacy
                AdvertisingPrivacy.NeutralNameAdvice.RECOMMENDED_CLASSIC_IN_USE -> R.string.neutral_name_advice_classic
                AdvertisingPrivacy.NeutralNameAdvice.RECOMMENDED_STACK_REFUSED -> R.string.neutral_name_advice_refused
                AdvertisingPrivacy.NeutralNameAdvice.RECOMMENDED_ROTATING_OFF -> R.string.neutral_name_advice_rotating_off
                AdvertisingPrivacy.NeutralNameAdvice.NOT_NEEDED_FOR_LE -> R.string.neutral_name_advice_not_needed
            },
        )
        val saved = NeutralBluetoothNameController.savedOriginal(this)
        val needsOwn = needsOwnBluetoothName()
        neutralBluetoothNameStatusText.text = buildString {
            // Option on but the name cannot be hidden: the switch shows off, with the reason first.
            when (neutralNameShown) {
                NeutralNameChoice.Shown.OFF_NO_PERMISSION -> append(getString(R.string.neutral_bluetooth_name_off_permission)).append('\n')
                NeutralNameChoice.Shown.OFF_REFUSED -> append(getString(R.string.neutral_bluetooth_name_off_refused)).append('\n')
                else -> Unit
            }
            append(adviceText)
            // Shown while a restore is pending too (switched off while Bluetooth was off), not only while it is on.
            if (saved != null) {
                append('\n')
                append(getString(R.string.neutral_bluetooth_name_saved, saved))
            } else if (needsOwn) {
                append('\n')
                append(getString(R.string.neutral_bluetooth_name_own_needed))
            } else if (optionOn) {
                append('\n')
                append(getString(R.string.neutral_bluetooth_name_no_saved))
            }
        }
        // Normally behind the info button; shown under the switch, and tappable, while the phone is stuck at "Phone".
        neutralBluetoothNameStatusText.visibility = if (needsOwn) View.VISIBLE else View.GONE
        neutralBluetoothNameStatusText.setOnClickListener(
            if (needsOwn) View.OnClickListener { showOwnBluetoothNameDialog(hideAfter = optionOn) } else null,
        )
        if (!needsOwn) neutralBluetoothNameStatusText.isClickable = false
        rotatingIdsLegacyNoteText.visibility =
            if (advancedSettings && reuse == AdvertisingPrivacy.AddressReuse.SUSPECTED) View.VISIBLE else View.GONE
    }

    /**
     * Always reachable for calls (CallReachabilityFeature, default on): a DEBUG benchmark launch
     * uses only `test_always_reachable`, so cases that request a role keep their advertising.
     */
    /**
     * Call resolution ladder (CallResolutionLadderFeature, default on for user calls): a DEBUG
     * benchmark launch keeps its exact geometry unless it carries `test_call_resolution_ladder=true`.
     */
    private fun applyCallResolutionLadder() {
        val ladderOff = BuildConfig.DEBUG && launchExtra(CallResolutionLadderFeature.EXTRA) == false
        // The measured Auto ladder needs the call resolution ladder to move along its rungs; an explicit
        // test_call_resolution_ladder=false still turns it off (a documented control arm).
        val enabled = FeatureCatalog.resolve(
            CallResolutionLadderFeature.spec, benchmarkExtrasPresent(), ::launchExtra, ::storedFeatureChoice,
        ) || (AutoQualityFeature.enabled && !ladderOff)
        CallResolutionLadderFeature.enabled = enabled
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_resolution_ladder: enabled=$enabled")
    }

    /**
     * Experimental relayed calls (RelayedCallsFeature, default off). Same launch rule as the other
     * experiments: a DEBUG benchmark launch uses only its `test_relayed_calls*` extras, otherwise the
     * Advanced setting applies. The controller (reach table, idle scan, reach pages) exists only while on.
     */
    private fun applyRelayedCalls() {
        val benchmarkLaunch = benchmarkExtrasPresent()
        val enabled = FeatureCatalog.resolve(
            RelayedCallsFeature.spec, benchmarkLaunch, ::launchExtra, ::storedFeatureChoice, advancedSettings,
        )
        val changed = enabled != RelayedCallsFeature.enabled
        RelayedCallsFeature.enabled = enabled
        RelayedCallsFeature.helpEnabled = if (benchmarkLaunch) {
            intent.getBooleanExtra(RelayedCallsFeature.EXTRA_HELP, true)
        } else {
            appPreferences.getBoolean(RelayedCallsFeature.PREF_HELP, true)
        }
        RelayedCallsFeature.consentEnabled = if (benchmarkLaunch) {
            intent.getBooleanExtra(RelayedCallsFeature.EXTRA_CONSENT, true)
        } else {
            appPreferences.getBoolean(RelayedCallsFeature.PREF_CONSENT, true)
        }
        RelayedCallsFeature.maxRelays =
            RelayedCallsFeature.resolveMaxRelays(benchmarkLaunch, intent.getIntExtra(RelayedCallsFeature.EXTRA_MAX_RELAYS, 0))
        // Rotating IDs + relayed calls work together (reach pages version 6); `test_relayed_rotating=false` restores the exclusion.
        RelayedCallsFeature.rotatingCompat =
            !benchmarkLaunch || intent.getBooleanExtra(RelayedCallsFeature.EXTRA_ROTATING_COMPAT, true)
        // Lab route controls (chain requirement, AP8 pins of exactly 12 hex digits, no-direct mask): any DEBUG launch, a
        // person's launch included; never a release build (RelayedCallsFeature.resolveRouteControls).
        val routeControls = RelayedCallsFeature.resolveRouteControls(
            BuildConfig.DEBUG, intent::getIntExtra, intent::getBooleanExtra, intent::getStringExtra,
        )
        RelayedCallsFeature.requireRelays = routeControls.requireRelays
        RelayedCallsFeature.helperPin = routeControls.helperPin
        RelayedCallsFeature.nextHopPin = routeControls.nextHopPin
        RelayedCallsFeature.noDirectSightings = routeControls.noDirectSightings
        RelayedCallsFeature.listRoutedStrangers =
            benchmarkLaunch && intent.getBooleanExtra(RelayedCallsFeature.EXTRA_LIST_STRANGERS, false)
        // Design OR (DEBUG prototype): on-demand route search, DEBUG launch extras only (no release or Advanced setting).
        RelaySeekFeature.apply(
            benchmarkLaunch,
            { key, default -> intent.getBooleanExtra(key, default) },
            { key, default -> intent.getIntExtra(key, default) },
            { key -> intent.getStringExtra(key) },
        )
        if (BuildConfig.DEBUG && (RelaySeekFeature.onDemand || RelaySeekFeature.keepTables)) {
            Log.i("BluetoothVideo", "BENCHMARK " + RelaySeekFeature.line())
        }
        if (changed || BuildConfig.DEBUG) {
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "relayed_calls: enabled=$enabled | help=${RelayedCallsFeature.helpEnabled} | " +
                    "consent=${RelayedCallsFeature.consentEnabled} | maxRelays=${RelayedCallsFeature.maxRelays} | " +
                    "require=${RelayedCallsFeature.requireRelays} | no_direct=${RelayedCallsFeature.noDirectSightings}" +
                    (if (BuildConfig.DEBUG) {
                        " | helperPin=${RelayedCallsFeature.pinLabel(RelayedCallsFeature.helperPin)} | " +
                            "nextHopPin=${RelayedCallsFeature.pinLabel(RelayedCallsFeature.nextHopPin)}"
                    } else {
                        ""
                    }),
            )
        }
        // Switching the experiment or "Help calls nearby" off ends a relay in progress (the user's choice).
        if (!RelayedCallsFeature.helperAllowed(RotatingDiscoveryFeature.enabled)) privateCall?.preemptRelay()
        if (enabled && relayedCalls == null) {
            relayedCalls = RelayedCallsController(
                adapterProvider = { bluetoothAdapter },
                deviceState = ::relayedCallsDeviceState,
                onRelayNotification = ::updateRelayNotification,
                onChanged = { runOnUiThread { updateRelayedCallsSettingsUi() } },
                onOfferStale = {
                    // The tick runs on the main thread: the answer says whether the endpoint was really stopped.
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        restartIdleCallEndpointForRelayedCalls()
                    } else {
                        runOnUiThread { restartIdleCallEndpointForRelayedCalls() }
                        true
                    }
                },
            ).also { it.start() }
            if (!relayStopReceiverRegistered) {
                ContextCompat.registerReceiver(
                    this,
                    relayStopReceiver,
                    android.content.IntentFilter(RelayedCallsNotification.ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                relayStopReceiverRegistered = true
            }
        } else if (!enabled && relayedCalls != null) {
            relayedCalls?.stop()
            relayedCalls = null
            if (relayStopReceiverRegistered) {
                runCatching { unregisterReceiver(relayStopReceiver) }
                relayStopReceiverRegistered = false
            }
        } else {
            relayedCalls?.refresh()
        }
        updateRelayedCallsSettingsUi()
        // The endpoint reads the switches when it is created: an idle one is rebuilt so the change applies.
        if (changed && ::codecSpinner.isInitialized) restartIdleCallEndpointForRelayedCalls()
    }

    /**
     * Rebuilds an idle call endpoint (no call, no exchange, no relay, no accepted link) so its offer and hello carry the
     * current bits. True when the endpoint was stopped for the rebuild. An accepted incoming link that has not signalled
     * a call yet is not idle: rebuilding closed a relay's hop to this callee 5 s after launch (rc-ctl-20261106); the
     * stale offer is then asked again at the next tick ([RelayedOfferTracker]).
     */
    private fun restartIdleCallEndpointForRelayedCalls(): Boolean {
        if (isFinishing || isDestroyed || !::codecSpinner.isInitialized) return false
        val endpoint = privateCall ?: return false
        val acceptedLink = endpoint.acceptedLinkNow
        if (!RelayedEndpointRestart.allowed(
                endpointIdle = privateCallEndpointIdle(),
                relaying = endpoint.relayingNow,
                messageSync = endpoint.messageSyncActive,
                acceptedLink = acceptedLink,
            )
        ) {
            if (acceptedLink && DebugTunables.relayedRestartGuard) {
                Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_helper: offer_restart=deferred | reason=accepted_link")
            }
            return false
        }
        // Restart guard: the stale offer is asked again at later ticks, so never stop an endpoint on a screen that
        // restartCallEndpointQuietly would not bring it back on.
        if (DebugTunables.relayedRestartGuard && appScreen != AppScreen.CALL_BROWSER && !idleEndpointScreen()) return false
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                "relayed_helper: offer_restart=done | acceptedLink=$acceptedLink | guard=${DebugTunables.relayedRestartGuard}",
        )
        stopPrivateCallEndpoint(keepService = true)
        restartCallEndpointQuietly(RELAYED_CALLS_RESTART_MS)
        return true
    }

    /** Brings the call endpoint back for the screen the user is on, without toasts or screen changes. */
    private fun restartCallEndpointQuietly(delayMs: Long) {
        if (isFinishing || isDestroyed) return
        when {
            appScreen == AppScreen.CALL_BROWSER -> showPrivateCallBrowser(delayMs)
            idleEndpointScreen() -> refreshCallReachability(delayMs)
            else -> Unit
        }
    }

    private fun relayedCallsDeviceState(): HelperDeviceState {
        val battery = getSystemService(BatteryManager::class.java)
        val level = runCatching { battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrNull() ?: -1
        val ownActivity = privateCallPhase != null || sender != null || broadcaster != null || receiver != null ||
            relay != null || privateCallRelay != null || cameraEncoder != null ||
            appScreen == AppScreen.CAMERA || appScreen == AppScreen.RECEIVER_BROWSER ||
            appScreen == AppScreen.PLAYBACK || appScreen == AppScreen.CALL ||
            privateCall?.messageSyncActive == true
        return HelperDeviceState(
            bluetoothReady = callReachabilityReady(),
            callReady = privateCall != null && privateCallAvailable,
            transport = callEndpointTransport,
            batteryPercent = level,
            charging = battery?.isCharging == true,
            batterySaver = runCatching { powerManager.isPowerSaveMode }.getOrDefault(false),
            thermalSevere = thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE,
            ownActivity = ownActivity,
            screenOn = runCatching { powerManager.isInteractive }.getOrDefault(true),
            rotatingIdsOn = RotatingDiscoveryFeature.enabled,
        )
    }

    /** "Helping a call nearby" while relaying; the ordinary "reachable" text again afterwards. */
    private fun updateRelayNotification(relaying: Boolean, relays: Int) {
        if (isFinishing || isDestroyed) return
        runCatching {
            if (relaying) {
                ActiveVideoSessionService.start(
                    this,
                    usesCamera = false,
                    statusText = getString(R.string.relay_helping_text),
                    relayStopAction = true,
                )
            } else if (ActiveVideoSessionService.holdsConnectedDevice()) {
                ActiveVideoSessionService.start(this, usesCamera = false, statusText = getString(R.string.reachable_service_text))
            }
        }.onFailure { Log.w("BluetoothVideo", "Relay notification update failed", it) }
    }

    /** A relay duty ended: rebuild the call endpoint quietly (no toast, no screen change) and tidy up. */
    /** A call declined by call blocking (blocked person or unknown caller): no screen, no toast; Recent is refreshed. */
    // ----- BVMID2 Meet flow (Naming v2, `bvmid2`, on by default): the host the Add contact screen talks to through MeetBridge -----

    /** The Add contact screen is open: the call endpoint is kept on air for it (see [MeetBridge]). */
    private var meetHold = false
    private var meetDiscovery: BleL2capDiscovery? = null
    private var meetListener: MeetBridge.ScanListener? = null
    private var meetPayload: Bvmid2Payload? = null
    private val meetPeers = LinkedHashMap<String, BleL2capPeer>()
    private val meetTried = HashSet<String>()
    private var meetAttemptActive = false
    private var meetReached = false
    private var meetSearchOver = false
    private var meetDeadlineMs = 0L
    /** The endpoint whose verification scan reached the code's phone (Design QL: it learns the user's confirmation). */
    @Volatile private var meetScanEndpoint: AndroidPrivateVideoCall? = null

    private val meetHost = object : MeetBridge.Host {
        override fun begin() {
            meetHold = true
            QrMeet.display.screenOnlyReachable = !CallReachabilityFeature.enabled
            refreshCallReachability()
        }

        override fun end() {
            meetHold = false
            meetScanEndpoint = null
            QrMeet.display.screenOnlyReachable = false
            QrMeet.display.leave()
            meetCancelScan()
            refreshCallReachability()
        }

        override fun currentRdi(): ByteArray? {
            if (privateCall == null || !privateCallAvailable) return null
            return runCatching { RotatingDiscovery.pinned(RotatingDiscoveryIds.ROLE_CALL_OFFER).firstOrNull()?.rdi }.getOrNull()
        }

        override fun reachableWithoutScreen(): Boolean = CallReachabilityFeature.enabled

        override fun scan(payload: Bvmid2Payload, listener: MeetBridge.ScanListener) = meetStartScan(payload, listener)

        override fun scanConfirmed(peer: IdentityFingerprint, yes: Boolean) {
            meetScanEndpoint?.qrScanConfirmed(peer, yes)
        }

        override fun cancelScan() = meetCancelScan()

        override fun callContact(peer: IdentityFingerprint) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val prefix = peer.nodePrefixHex.lowercase(Locale.US)
                callPerson(prefix, video = !IntercomFeatures.current.voiceCalls, name = contactNames[prefix] ?: getString(R.string.call_someone))
            }
        }

        override fun codeShown() {
            // F11: the displayer's call listener advertises at low latency / high power while the code is live.
            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK meet_code: shown | boost=${AdvertisingBoost.holding()}")
            privateCall?.boostAdvertising()
        }
    }

    private val meetHandler = Handler(Looper.getMainLooper())

    /** Naming v2: the same advertised ID heard on two addresses at once and repeatedly is probably a copy (finding F4). */
    private val copiedIds = CopiedIdTracker()

    private fun meetStartScan(payload: Bvmid2Payload, listener: MeetBridge.ScanListener) {
        meetCancelScan()
        val adapter = bluetoothAdapter
        if (adapter == null || !hasRequiredPermissions() || privateCall == null || !privateCallAvailable) {
            listener.onFailed(MeetBridge.Failure.NOT_READY)
            return
        }
        meetPayload = payload
        meetListener = listener
        meetReached = false
        meetAttemptActive = false
        meetSearchOver = false
        val searchMs = MeetBridge.searchMs()
        val limitMs = MeetBridge.scanLimitMs()
        meetDeadlineMs = SystemClock.elapsedRealtime() + limitMs
        // F11: the code's ID, to compare with the scanner's scan_reject lines (id=...) of every refused candidate.
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK meet_scan: start | codeRdi=${payload.rdi.toHexKey()} | searchMs=$searchMs | limitMs=$limitMs | " +
                "filter=${if (ScanFilterFallback.softwareFor(DebugTunables.meetSoftwareFilter)) "software" else "hardware"}",
        )
        listener.onSearching()
        startMeetDiscovery(adapter, payload, searchMs + 2_000L)
        // F11: a fresh scanner half-way if nothing was heard yet (some stacks report an address once per scanner).
        WeakPairReach.meetRestartAtMs(DebugTunables.weakPairReach)?.let { restartAtMs ->
            meetHandler.postDelayed({
                if (meetPayload === payload && meetPeers.isEmpty() && !meetReached) {
                    if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK meet_scan: restart | afterMs=$restartAtMs")
                    startMeetDiscovery(adapter, payload, searchMs - restartAtMs + 2_000L)
                }
            }, restartAtMs)
        }
        meetHandler.postDelayed({ meetSearchOver = true; meetTryNext() }, searchMs)
        meetHandler.postDelayed({ meetFinish(MeetBridge.Failure.COULD_NOT_CONNECT) }, limitMs)
    }

    /** One Meet scanner for the code's advert ([durationMs]); a new one replaces the running one. */
    private fun startMeetDiscovery(adapter: android.bluetooth.BluetoothAdapter, payload: Bvmid2Payload, durationMs: Long) {
        meetDiscovery?.close()
        lateinit var discovery: BleL2capDiscovery
        discovery = BleL2capDiscovery(
            adapter,
            { peer ->
                runOnUiThread {
                    if (meetDiscovery !== discovery) return@runOnUiThread
                    // Every address that carries the code's ID is tried: a copy of the advert can answer first and fail.
                    meetPeers.putIfAbsent(peer.device.address, peer)
                    meetTryNext()
                }
            },
            { },
            { runOnUiThread { if (meetDiscovery === discovery) meetFinish(MeetBridge.Failure.NOT_FOUND) } },
            acceptAnnouncement = { ad ->
                ad.rotating && !ad.isWindowOffer && ad.routeCost == 0 && ad.isPrivateCallOffer() &&
                    ad.flags and DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT == 0 &&
                    ad.nodeIdPrefix.contentEquals(payload.rdi)
            },
            scanDurationMs = durationMs,
            reportDuplicates = true,
            continuousLowLatency = true,
            acceptRotating = true,
            softwareFilter = DebugTunables.meetSoftwareFilter,
        )
        meetDiscovery = discovery
        discovery.start()
    }

    /** Dials the next untried address, once the endpoint is idle and listening again (a failed attempt ends it quietly). */
    private fun meetTryNext() {
        val payload = meetPayload ?: return
        if (meetReached || meetAttemptActive) return
        val next = meetPeers.entries.firstOrNull { it.key !in meetTried }
        if (next == null) {
            if (meetSearchOver) {
                meetFinish(if (meetTried.isEmpty()) MeetBridge.Failure.NOT_FOUND else MeetBridge.Failure.COULD_NOT_CONNECT)
            }
            return
        }
        val endpoint = privateCall
        if (endpoint == null || !privateCallAvailable) {
            // The endpoint is being rebuilt after the previous attempt: wait for it, inside the overall limit.
            if (SystemClock.elapsedRealtime() < meetDeadlineMs) meetHandler.postDelayed(::meetTryNext, 500L)
            return
        }
        meetTried += next.key
        endpoint.qrScanPayload = payload
        endpoint.onQrScanReached = { fingerprint, node ->
            runOnUiThread {
                if (meetPayload === payload && !meetReached) {
                    meetReached = true
                    meetScanEndpoint = endpoint
                    meetListener?.onReached(fingerprint, node)
                }
            }
        }
        meetAttemptActive = true
        if (!endpoint.startOutgoing(next.value)) {
            endpoint.qrScanPayload = null
            meetAttemptActive = false
            meetHandler.post(::meetTryNext)
        }
    }

    /** The scanning endpoint ended (quietly): after a success there is nothing to do; after a failure try the next address. */
    private fun meetScanAttemptEnded() {
        meetAttemptActive = false
        if (meetPayload != null && !meetReached) meetHandler.postDelayed(::meetTryNext, 300L)
    }

    private fun meetFinish(failure: MeetBridge.Failure) {
        val listener = meetListener
        val reached = meetReached
        if (BuildConfig.DEBUG && meetPayload != null) Log.i(
            "BluetoothVideo",
            "BENCHMARK meet_scan: end | reached=$reached | failure=${failure.name.lowercase()} | " +
                "candidates=${meetPeers.size} | tried=${meetTried.size}",
        )
        meetCancelScan()
        if (!reached) listener?.onFailed(failure)
    }

    private fun meetCancelScan() {
        meetHandler.removeCallbacksAndMessages(null)
        meetDiscovery?.close()
        meetDiscovery = null
        meetPayload = null
        meetListener = null
        meetPeers.clear()
        meetTried.clear()
        meetAttemptActive = false
    }

    private fun handleBlockedCallEnded(endpoint: AndroidPrivateVideoCall) {
        if (endpoint.endedBusyWhileDialling) {
            // F14: the user's own dial goes on; a sighting during the endpoint rebuild waits for it.
            deferPendingDialToWait("busy_declined_incoming")
            val caller = endpoint.busyDeclinedCaller?.let { incomingPeerName(it) } ?: getString(R.string.people_someone_nearby)
            val dialling = currentOwnDial()?.name?.takeIf { it.isNotBlank() }
            Toast.makeText(
                this,
                if (dialling != null) getString(R.string.call_busy_declined_while_dialling, caller, dialling)
                else getString(R.string.call_busy_declined, caller),
                Toast.LENGTH_LONG,
            ).show()
        }
        endpoint.close()
        privateCall = null
        privateCallAvailable = false
        privateCallAvailabilityStarting = false
        restartCallEndpointQuietly(
            if (callEndpointTransport == BluetoothTransportOption.BLE_GATT) GATT_CALL_RESTART_DELAY_MS else 0L,
        )
        updateRecentCalls()
    }

    private fun handleRelayDutyEnded(endpoint: AndroidPrivateVideoCall) {
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_helper: relay_ended")
        endpoint.close()
        privateCall = null
        privateCallAvailable = false
        privateCallAvailabilityStarting = false
        relayedCalls?.refresh()
        restartCallEndpointQuietly(
            if (callEndpointTransport == BluetoothTransportOption.BLE_GATT) GATT_CALL_RESTART_DELAY_MS else 0L,
        )
    }

    /**
     * Call audit 2026-11-10, CALL-02 (`test_call_quiet_pre_ring_failure`): an incoming link failed before the phone rang.
     * Nothing was shown, so no screen changes and nothing is said; the endpoint is rebuilt for the screen the user is
     * on after the same pause as the ordinary end, as after a relay duty.
     */
    private fun handleQuietPreRingFailure(endpoint: AndroidPrivateVideoCall, message: String, restartDelayMs: Long) {
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + PreRingFailure.line(message, appScreen.name))
        endpoint.close()
        privateCall = null
        privateCallAvailable = false
        privateCallAvailabilityStarting = false
        restartCallEndpointQuietly(restartDelayMs)
    }

    /** First switch-on: what the experiment does, with both choices preselected on (design decisions 2 and 3). */
    private fun showRelayedCallsPrompt() {
        val checked = booleanArrayOf(true, true)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.relayed_calls_prompt_title)
            .setMessage(R.string.relayed_calls_prompt_body)
            .setMultiChoiceItems(
                arrayOf(getString(R.string.relayed_calls_help), getString(R.string.relayed_calls_consent)),
                checked,
            ) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.relayed_calls_prompt_ok) { _, _ ->
                appPreferences.edit()
                    .putBoolean(RelayedCallsFeature.PREF_PROMPTED, true)
                    .putBoolean(RelayedCallsFeature.PREF_HELP, checked[0])
                    .putBoolean(RelayedCallsFeature.PREF_CONSENT, checked[1])
                    .apply()
                relayedCallsHelpSwitch.isChecked = checked[0]
                relayedCallsConsentSwitch.isChecked = checked[1]
                experimentalRelayedCallsSwitch.isChecked = true
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showTracked()
    }

    private fun updateRelayedCallsSettingsUi() {
        if (!::relayedCallsHelpSwitch.isInitialized) return
        val subSettings = if (RelayedCallsFeature.enabled) View.VISIBLE else View.GONE
        relayedCallsHelpSwitch.visibility = subSettings
        relayedCallsConsentSwitch.visibility = subSettings
        relayedCallsStatusText.visibility = subSettings
        relayedCallsStatusText.text = relayedCalls?.statusLine().orEmpty()
    }

    private fun applyCallReachability() {
        val benchmarkLaunch = benchmarkExtrasPresent()
        val enabled = FeatureCatalog.resolve(CallReachabilityFeature.spec, benchmarkLaunch, ::launchExtra, ::storedFeatureChoice)
        CallReachabilityFeature.enabled = enabled
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_reachability: enabled=$enabled")
        val browsing = FeatureCatalog.resolve(ReachableWhileBrowsingFeature.spec, benchmarkLaunch, ::launchExtra, ::storedFeatureChoice)
        ReachableWhileBrowsingFeature.enabled = browsing
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_reachability: reachable_while_browsing=$browsing",
        )
        // The call's audio focus request is retried at once when the foreground service starts holding its types
        // (Android 15 refuses focus to a background app without one; batch U). Reachability stays off in benchmark launches.
        ActiveVideoSessionService.heldTypesListener = {
            if (ActiveVideoSessionService.heldTypes != 0) sessionHandler.post { privateCallAudioSession?.onForegroundHeld() }
            if (enabled) sessionHandler.post(callReachabilityServiceChanged)
            WifiDiscovery.recompute()   // A8: a background gate input changed
        }
    }

    /**
     * The session service's held types changed after start() returned (Android refused the type in
     * onStartCommand, or the service ended). A Home endpoint in the background must not outlive the
     * connectedDevice service it depends on, so stop it when the plan no longer allows it. This
     * never starts an endpoint: starts keep their own triggers and delays (GATT restart pause).
     */
    private val callReachabilityServiceChanged = Runnable {
        if (isFinishing || isDestroyed || !::codecSpinner.isInitialized) return@Runnable
        if (idleEndpointScreen() && !currentReachabilityPlan().endpoint &&
            (privateCallEndpointIdle() || privateCallAvailabilityStarting)
        ) {
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "call_reachability: endpoint stopped | reason=foreground_service_lost",
            )
            stopPrivateCallEndpoint()
        }
        updateHomeReadiness()
    }

    private fun reachabilityScreen(): ReachabilityScreen {
        val screen = if (appScreen == AppScreen.SETTINGS) settingsReturnScreen else appScreen
        return when (screen) {
            AppScreen.HOME, AppScreen.SETTINGS -> ReachabilityScreen.HOME
            AppScreen.CALL_BROWSER -> ReachabilityScreen.CALL_BROWSER
            AppScreen.CALL -> ReachabilityScreen.CALL
            AppScreen.CAMERA -> ReachabilityScreen.LIVE
            // Browsing only (no stream joined, none being rejoined): the endpoint may stay. Flag off: Watch.
            AppScreen.RECEIVER_BROWSER -> liveTabReachabilityScreen(
                ReachableWhileBrowsingFeature.enabled,
                streamJoinedOrRejoining = receiver != null || relay != null || selectedStreamPeer != null ||
                    reconnectTarget != null || activeStreamTarget != null,
            )
            AppScreen.PLAYBACK -> ReachabilityScreen.WATCH
        }
    }

    /** Home or the browsing Live tab: the screens where an idle call endpoint waits for calls. */
    private fun idleEndpointScreen(): Boolean =
        reachabilityScreen().let { it == ReachabilityScreen.HOME || it == ReachabilityScreen.LIVE_BROWSING }

    /** Bluetooth on and endpoint permissions granted; notification denial stays a readiness warning. */
    private fun callReachabilityReady(): Boolean {
        val adapter = bluetoothAdapter ?: return false
        val bluetoothOn = runCatching { adapter.isEnabled }.getOrDefault(false)
        return bluetoothOn && RuntimePermissionPlan.endpoint(Build.VERSION.SDK_INT, DebugTunables.callReadinessPermissions)
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun currentReachabilityPlan(): ReachabilityPlan =
        CallReachability.plan(currentReachabilityPlanInputs(reachabilityScreen()))

    private fun currentReachabilityPlanInputs(screen: ReachabilityScreen): ReachabilityInputs {
        val batteryPercent = runCatching {
            getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()
        return ReachabilityInputs(
            // The Add contact screen (BVMID2) keeps the endpoint on air while it is open, even with "Allow calls" off.
            featureEnabled = CallReachabilityFeature.enabled || meetHold,
            screen = screen,
            foreground = activityStarted || meetHold,
            foregroundServiceHeld = ActiveVideoSessionService.holdsConnectedDevice(),
            bluetoothReady = callReachabilityReady(),
            wifiNativeReady = wifiNativeReachable(),
            batteryLow = batteryPercent != null && batteryPercent in 0 until CallReachability.LOW_BATTERY_PERCENT,
            batterySaver = runCatching { powerManager.isPowerSaveMode }.getOrDefault(false),
            reachableWhileBrowsing = ReachableWhileBrowsingFeature.enabled,
        )
    }

    /**
     * Wider reach: Bluetooth is unusable (adapter off) but the permissions are granted, so the endpoint may listen over
     * Wi-Fi only, provided Wi-Fi Aware is eligible now and this phone can carry a native hop (A8 review P2-4).
     */
    private fun wifiNativeReachable(): Boolean {
        val widerReach = WifiDiscovery.widerReachOn()
        return CallReachability.wifiNativeReady(
            widerReach = widerReach,
            bluetoothReady = widerReach && callReachabilityReady(),
            permissionsGranted = widerReach &&
                RuntimePermissionPlan.endpoint(Build.VERSION.SDK_INT, DebugTunables.callReadinessPermissions)
                    .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED },
            adapterOff = widerReach && runCatching { bluetoothAdapter?.isEnabled == false }.getOrDefault(false),
            awareEligible = { WifiDiscovery.eligibility().aware },   // read last, only with the switch on (X4)
            nativeHopsPossible = NativeBandRule.canVerify(Build.VERSION.SDK_INT),
        )
    }

    /** An idle endpoint that only waits for incoming calls (no call ringing or connected). */
    private fun privateCallEndpointIdle(): Boolean =
        privateCall != null && privateCallPhase == null && appScreen != AppScreen.CALL

    /**
     * Applies [CallReachability] on Home (and Settings-from-Home); other screens keep managing the
     * endpoint themselves. Never touches a ringing or connected call.
     */
    private fun refreshCallReachability(delayMs: Long = 0L) {
        if (delayMs > 0L) {
            sessionHandler.removeCallbacks(callReachabilityRefresh)
            sessionHandler.postDelayed(callReachabilityRefresh, delayMs)
            return
        }
        if (isFinishing || isDestroyed || !::codecSpinner.isInitialized) return
        val plan = currentReachabilityPlan()
        val line = plan.logLine()
        if (CallReachabilityFeature.enabled && line != lastReachabilityLog) {
            lastReachabilityLog = line
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_reachability: $line")
        }
        if (idleEndpointScreen()) {
            if (plan.endpoint) {
                if (privateCall == null && !privateCallAvailabilityStarting) {
                    startPrivateCallEndpoint()
                } else if (privateCallAvailable) {
                    ensureReachabilityService()
                }
            } else if (privateCallEndpointIdle() || privateCallAvailabilityStarting) {
                stopPrivateCallEndpoint()
            }
        }
        updateHomeReadiness()
        relayedCalls?.refresh()
    }

    private val callReachabilityRefresh = Runnable { refreshCallReachability() }

    /**
     * Keeps the connectedDevice foreground service for an idle available endpoint (stopStreaming
     * stops the service when Home or the Call screen opens). Started only while visible, as
     * Android 12+ requires; a refusal is reported by the service (BENCHMARK foreground_service).
     */
    private fun ensureReachabilityService() {
        if (!CallReachabilityFeature.enabled || !activityStarted || !privateCallAvailable) return
        if (ActiveVideoSessionService.holdsConnectedDevice()) return
        runCatching {
            ActiveVideoSessionService.start(this, usesCamera = false, statusText = getString(R.string.reachable_service_text))
        }.onFailure { Log.w("BluetoothVideo", "Call reachability service start failed", it) }
    }

    /**
     * "Share app nearby" (OfflineAppShareFeature). A DEBUG benchmark launch uses only
     * `test_offline_app_share`; every other launch the user's choice or the shipped default. It only
     * shows the Help button; nothing runs until that screen opens.
     */
    private fun applyOfflineAppShare() {
        val enabled = OfflineAppShareFeature.resolve(
            DebugTunables.benchmarkLaunch,
            { intent.getBooleanExtra(it, false) },
            ::storedFeatureChoice,
        )
        if (enabled != OfflineAppShareFeature.enabled) {
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "offline_app_share: enabled=$enabled")
        }
        OfflineAppShareFeature.enabled = enabled
        findViewById<View>(R.id.shareAppNearbyButton)?.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    /**
     * Native libopus call audio, "Call audio with loss recovery" (NativeOpusSettings). A DEBUG benchmark
     * launch uses only `test_call_native_opus`; every other launch the user's choice or the shipped
     * default, in release builds too. Read when a call's audio encoder or decoder starts.
     */
    private fun applyNativeOpus() {
        val enabled = NativeOpusSettings.resolveEnabled(
            DebugTunables.benchmarkLaunch,
            { intent.getBooleanExtra(it, false) },
            ::storedFeatureChoice,
        )
        if (enabled != DebugTunables.callNativeOpus) {
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "native_opus: enabled=$enabled")
        }
        DebugTunables.callNativeOpus = enabled
    }

    /** The user's explicit choice for a Settings > Features switch; null while the switch was never touched. */
    private fun storedFeatureChoice(key: String): Boolean? =
        if (appPreferences.contains(key)) appPreferences.getBoolean(key, false) else null

    /** A boolean launch extra; null when this launch does not carry it ([FeatureCatalog.resolve]). */
    private fun launchExtra(name: String): Boolean? = if (intent.hasExtra(name)) intent.getBooleanExtra(name, false) else null

    /** Registers every feature spec and re-resolves the whole catalog, logging a change once (one snapshot line). */
    private fun refreshFeatureCatalog() {
        AppFeatures.registerAll()
        MeetBridge.host = meetHost
        VerificationEvents.listener = verificationListener
        DialCollision.ownDial = ::currentOwnDial
        applicationContext.let { appContext -> Thread { StrangerNames.install(appContext) }.start() }
        val before = FeatureCatalog.current
        val snapshot = FeatureCatalog.refresh(DebugTunables.benchmarkLaunch, ::launchExtra, ::storedFeatureChoice, advancedSettings)
        if (snapshot != before) {
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "features: ${snapshot.label()}")
        }
        // Hidden identities (BMH3): its sub-options exist only in a DEBUG benchmark launch.
        val bmh3Debug = BuildConfig.DEBUG && DebugTunables.benchmarkLaunch
        Bmh3Feature.configureDebug(
            only = bmh3Debug && intent.getBooleanExtra(Bmh3Feature.EXTRA_ONLY, false),
            contactsOnly = bmh3Debug && intent.getBooleanExtra(Bmh3Feature.EXTRA_CONTACTS_ONLY, false),
            testLeaseHex = if (bmh3Debug) intent.getStringExtra(Bmh3Feature.EXTRA_TEST_LEASE) else null,
        )
        WifiSwitchHub.publish()
    }

    /**
     * Builds the connectivity features from the catalog, split by the Settings card each one lives on; the Wi-Fi
     * switches have their own card on the Connection page.
     */
    private fun bindConnectivityFeatures() {
        refreshFeatureCatalog()
        val host = object : FeatureSettingsBlock.Host {
            override val benchmarkLaunch: Boolean get() = DebugTunables.benchmarkLaunch

            override fun extra(name: String): Boolean? = launchExtra(name)

            override fun confirmEnable(spec: FeatureSpec, proceed: () -> Unit): Boolean {
                if (spec in ConnectivityFeatures.wifi) return wifiEnableDriver.confirm(spec.id, proceed)
                if (spec != ConnectivityFeatures.classicWithoutPairing) return false
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.feat_classic_dialog_title)
                    .setMessage(R.string.feat_classic_dialog_message)
                    .setPositiveButton(R.string.feat_classic_dialog_confirm) { _, _ -> proceed() }
                    .setNegativeButton(R.string.action_cancel, null)
                    .showTracked()
                return true
            }

            override fun describe(spec: FeatureSpec, deliver: (CharSequence?) -> Unit) {
                when {
                    spec == ConnectivityFeatures.wifiCarrier && WifiRowPolicy.carrierNeedsWiderReach(Build.VERSION.SDK_INT) ->
                        deliver(getString(R.string.feat_wifi_carrier_description) + " " + getString(R.string.feat_wifi_carrier_needs_wider_reach))
                    spec == ConnectivityFeatures.wifiDirect -> describeDirect(deliver)
                    else -> deliver(null)
                }
            }

            override fun rowNote(spec: FeatureSpec, on: Boolean, snapshot: FeatureSnapshot): CharSequence? =
                when (wifiEnableFlow.note(spec.id, on, widerReachOn = snapshot.isOn(WifiFeatures.ID_WIDER_REACH))) {
                    WifiRowNote.NEEDS_PERMISSION -> getString(R.string.wifi_needs_permission)
                    WifiRowNote.NEEDS_LOCATION -> getString(R.string.wifi_needs_location)
                    WifiRowNote.NEEDS_WIDER_REACH -> getString(R.string.wifi_needs_wider_reach)
                    null -> null
                }

            override fun onChanged(spec: FeatureSpec, enabled: Boolean) =
                onConnectivityFeatureChanged(spec, enabled)
        }
        val privacy = listOf(ConnectivityFeatures.bmh3, ConnectivityFeatures.bmh3Pseudonyms)
        // The Google Play flavour offers no "Share app nearby", so it has no settings row there either.
        // Settings > Experiments since 2026-11-04: Share app nearby (where available), Find PCs and PC live streams.
        // The Wi-Fi carrier switches have their own rows (Settings > Advanced > Connection Wi-Fi), not this block.
        val pcs = listOf(ConnectivityFeatures.findPcs, ConnectivityFeatures.pcStreams)
        val share = listOf(ConnectivityFeatures.shareAppNearby).filter { OfflineAppShareFeature.availableInThisBuild } + pcs
        val connection = ConnectivityFeatures.all.filter {
            it !in privacy && it !in pcs && it != ConnectivityFeatures.shareAppNearby && it !in ConnectivityFeatures.wifi
        }
        connectivityFeatureBlock = FeatureSettingsBlock(
            findViewById(R.id.connFeaturesContainer), connection, appPreferences, host,
        ).also { it.bind() }
        shareFeatureBlock = FeatureSettingsBlock(
            findViewById(R.id.shareFeaturesContainer), share, appPreferences, host,
        ).also { it.bind() }

        val wifiRows = WifiRowPolicy.rows(ConnectivityFeatures.wifi, Build.VERSION.SDK_INT)
        findViewById<View>(R.id.wifiCard).visibility = if (wifiRows.isEmpty()) View.GONE else View.VISIBLE
        wifiFeatureBlock = FeatureSettingsBlock(
            findViewById(R.id.wifiFeaturesContainer), wifiRows, appPreferences, host, indentDependents = true,
        ).also { it.bind() }
        NameWindowRuntime.addListener(nameWindowChanged)
        namingFeatureBlock = FeatureSettingsBlock(
            findViewById(R.id.namingFeaturesContainer), NamingFeatures.settingsRows, appPreferences,
            object : FeatureSettingsBlock.Host {
                override val benchmarkLaunch: Boolean get() = DebugTunables.benchmarkLaunch

                override fun extra(name: String): Boolean? = launchExtra(name)

                override fun onChanged(spec: FeatureSpec, enabled: Boolean) {
                    // Apply to the running app: the title-pages and timing flags and the rows that show names.
                    refreshFeatureCatalog()
                    applyPeerLabelFeature(DebugTunables.benchmarkLaunch)
                    updatePrivateCallPeerList()
                    if (spec == NamingFeatures.VISIBLE_BY_NAME && !enabled) NameWindowRuntime.stop(this@MainActivity, "switch_off")
                    refreshVisibleByNameRow()
                }
            },
        ).also { it.bind() }
    }

    private var namingFeatureBlock: FeatureSettingsBlock? = null

    // ----- Visible by name (Naming v2, `visible_by_name`, off): the Settings > Privacy row and its dialogs -----

    private val nameWindowChanged: () -> Unit = { runOnUiThread { if (!isDestroyed) refreshVisibleByNameRow() } }

    private fun refreshVisibleByNameRow() {
        val row = findViewById<View>(R.id.visibleByNameButton) ?: return
        row.visibility = if (NameWindowRuntime.available()) View.VISIBLE else View.GONE
        val active = NameWindowRuntime.active()
        findViewById<TextView>(R.id.visibleByNameValue).text = if (active) {
            getString(
                R.string.visible_name_value_on,
                android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(NameWindowRuntime.endsAtWallMs())),
            )
        } else {
            getString(R.string.visible_name_value_off)
        }
        if (NameWindowRuntime.cannotShow) {
            Toast.makeText(this, R.string.visible_name_cannot_show, Toast.LENGTH_LONG).show()
        }
        row.setOnClickListener { if (NameWindowRuntime.active()) askStopVisibleByName() else askStartVisibleByName() }
    }

    private fun askStopVisibleByName() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.visible_name_stop_title)
            .setPositiveButton(R.string.visible_name_stop) { _, _ ->
                NameWindowRuntime.stop(this, "stop")
                Toast.makeText(this, R.string.visible_name_stopped, Toast.LENGTH_SHORT).show()
                refreshVisibleByNameRow()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showTracked()
    }

    private fun askStartVisibleByName() {
        if (privateCall == null || !privateCallAvailable) {
            Toast.makeText(this, R.string.visible_name_needs_calls, Toast.LENGTH_LONG).show()
            return
        }
        // One name (design decision 2026-11-04): "Your name" is filled in; it can still be changed to a nickname here.
        val ownName = PeerLabelFeature.localName.orEmpty()
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.visible_name_hint)
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(LabelKind.PERSON_NAME.maxBytes))
            setText(ownName)
        }
        val blockOn = CallBlocking.blockUnknown(this)
        val allow = android.widget.CheckBox(this).apply {
            text = getString(R.string.visible_name_allow_anyone)
            visibility = if (blockOn) View.VISIBLE else View.GONE
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
            addView(allow)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.visible_name_title)
            .setMessage(R.string.visible_name_message)
            .setView(container)
            .setPositiveButton(R.string.visible_name_start) { _, _ ->
                val name = input.text?.toString().orEmpty()
                // Keeping the filled-in "Your name" is the choice itself, so it is not asked again.
                startVisibleByName(name, allow.isChecked, confirmedSameAsCall = ownName.isNotBlank() && name.trim() == ownName)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showTracked()
    }

    private fun startVisibleByName(name: String, allowAnyone: Boolean, confirmedSameAsCall: Boolean) {
        when (NameWindowRuntime.start(this, name, PeerLabelFeature.localName, confirmedSameAsCall, allowAnyone)) {
            VisibleNameWindow.Result.STARTED -> {
                Toast.makeText(
                    this,
                    getString(
                        R.string.visible_name_started,
                        android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(NameWindowRuntime.endsAtWallMs())),
                    ),
                    Toast.LENGTH_LONG,
                ).show()
                // The sets start on the endpoint's thread; a phone without free advertising sets says so a moment later.
                sessionHandler.postDelayed({ if (!isDestroyed) refreshVisibleByNameRow() }, 4_000L)
            }
            VisibleNameWindow.Result.NAME_SAME_AS_CALL_NAME_UNCONFIRMED ->
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.visible_name_same_title)
                    .setMessage(R.string.visible_name_same_message)
                    .setPositiveButton(R.string.visible_name_same_use) { _, _ -> startVisibleByName(name, allowAnyone, confirmedSameAsCall = true) }
                    .setNegativeButton(R.string.action_cancel, null)
                    .showTracked()
            VisibleNameWindow.Result.NAME_TOO_LONG_FOR_PAGES ->
                Toast.makeText(this, R.string.visible_name_too_long, Toast.LENGTH_LONG).show()
            VisibleNameWindow.Result.NAME_INVALID, VisibleNameWindow.Result.NAME_HASH_ZERO ->
                Toast.makeText(this, R.string.visible_name_invalid, Toast.LENGTH_LONG).show()
            null -> Toast.makeText(this, R.string.visible_name_needs_calls, Toast.LENGTH_LONG).show()
        }
        refreshVisibleByNameRow()
    }

    private var shareFeatureBlock: FeatureSettingsBlock? = null

    /** The connectivity and privacy rows again: a feature they depend on (rotating IDs) changed outside them. */
    private fun refreshConnectivityFeatureRows() {
        connectivityFeatureBlock?.refresh()
    }

    /** Builds the Settings > Features card for stream viewers from the catalog. */
    private fun bindViewerFeatures() {
        refreshFeatureCatalog()
        viewerFeatureBlock = FeatureSettingsBlock(
            findViewById(R.id.viewerFeaturesContainer),
            ViewerFeatures.all,
            appPreferences,
            object : FeatureSettingsBlock.Host {
                override val benchmarkLaunch: Boolean get() = DebugTunables.benchmarkLaunch

                override fun extra(name: String): Boolean? = launchExtra(name)

                override fun onChanged(spec: FeatureSpec, enabled: Boolean) {
                    // A viewer switch applies to the next viewing; the one on screen keeps its mode.
                    refreshFeatureCatalog()
                    Log.i(
                        "BluetoothVideo",
                        (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                            "feature_changed: id=${spec.id} | enabled=$enabled | explicit=true",
                    )
                }
            },
        ).also { it.bind() }
    }

    /** Builds the Settings > Features card for updates and starts the home update note (UpdatesHome). */
    private fun bindUpdateFeatures() {
        refreshFeatureCatalog()
        val home = UpdatesHome(
            this,
            appPreferences,
            findViewById(R.id.homeUpdateNotice),
        ) { receiverBrowserScreen.visibility == View.VISIBLE && !liveSessionBlocksAppShare() }
        updatesHome = home
        updateFeatureBlock = FeatureSettingsBlock(
            findViewById(R.id.updateFeaturesContainer),
            UpdateFeatures.all(BuildConfig.UPDATER_AVAILABLE),
            appPreferences,
            object : FeatureSettingsBlock.Host {
                override val benchmarkLaunch: Boolean get() = DebugTunables.benchmarkLaunch

                override fun extra(name: String): Boolean? = launchExtra(name)

                override fun confirmEnable(spec: FeatureSpec, proceed: () -> Unit): Boolean {
                    // The same explanation as the one-time question: who is contacted and what they see.
                    if (spec != UpdateFeatures.checkForUpdates) return false
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.update_ask_title)
                        .setMessage(R.string.update_ask_message)
                        .setPositiveButton(R.string.update_ask_yes) { _, _ -> proceed() }
                        .setNegativeButton(R.string.action_cancel, null)
                        .showTracked()
                    return true
                }

                override fun onChanged(spec: FeatureSpec, enabled: Boolean) {
                    refreshFeatureCatalog()
                    Log.i(
                        "BluetoothVideo",
                        (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                            "feature_changed: id=${spec.id} | enabled=$enabled | explicit=true",
                    )
                    home.onSettingChanged(spec)
                }
            },
        ).also { it.bind() }
        findViewById<View>(R.id.updateOpenButton).apply {
            visibility = if (Updater.backend.available) View.VISIBLE else View.GONE
            setOnClickListener { Updater.backend.open(this@MainActivity, 0) }
        }
        home.apply()
    }

    /**
     * The stale-picture controller of the Watch screen (null-safe: built on first use, inert while the
     * switch is off). Its views are in activity_main.xml next to the older waiting overlay.
     */
    private fun viewerFrameAgeController(): ViewerFrameAgeController =
        viewerFrameAge ?: ViewerFrameAgeController(
            sessionHandler,
            ViewerFrameAgeController.Views(
                scrim = findViewById(R.id.viewerStaleScrim),
                badge = findViewById(R.id.viewerStaleBadge),
                progress = findViewById(R.id.viewerStaleProgress),
                title = findViewById(R.id.viewerStaleTitle),
                detail = findViewById(R.id.viewerStaleDetail),
                note = findViewById(R.id.viewerStaleNote),
                label = findViewById(R.id.viewerFrameAgeLabel),
            ),
            object : ViewerFrameAgeController.Texts {
                override fun signalLost() = getString(R.string.viewer_signal_lost)
                override fun pictureStalled() = getString(R.string.viewer_picture_stalled)
                override fun lastPicture(age: String) = getString(R.string.viewer_last_picture, age)
                override fun lastUpdate(age: String) = getString(R.string.viewer_last_update, age)
                override fun chipSignalLost() = getString(R.string.watch_status_signal_lost)
                override fun chipStalled() = getString(R.string.watch_status_stalled)
            },
            isWatchScreen = { appScreen == AppScreen.PLAYBACK },
            isForeground = { activityResumed },
            onStateChanged = { updateBroadcastStatusChip() },
            hideLegacyOverlay = { receiverWaitingOverlay.visibility = View.GONE },
        ).also { viewerFrameAge = it }

    private val wifiEnableFlow by lazy {
        WifiEnableFlow(
            sdkInt = Build.VERSION.SDK_INT,
            granted = { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED },
            locationOn = { runCatching { getSystemService(LocationManager::class.java)?.isLocationEnabled == true }.getOrDefault(false) },
        )
    }

    private val wifiEnableDriver by lazy {
        WifiEnableDriver(
            flow = wifiEnableFlow,
            sdkInt = Build.VERSION.SDK_INT,
            askPermissions = { permissions, answered ->
                wifiPermissionAnswered = answered
                wifiPermissionRequest.launch(permissions.toTypedArray())
            },
            showLocationOff = {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.wifi_location_title)
                    .setMessage(R.string.wifi_location_message)
                    .setPositiveButton(R.string.wifi_location_open) { _, _ ->
                        runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .showTracked()
            },
            showDenied = { approximateOnly ->   // Reviewer amendment 4: a dialog with a way out, not a toast (a permanent denial cannot be asked again)
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setMessage(if (approximateOnly) R.string.wifi_permission_precise else R.string.wifi_permission_denied)
                    .setPositiveButton(R.string.wifi_open_app_settings) { _, _ ->
                        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .showTracked()
            },
            confirmDirect = ::confirmWifiDirectOn,
        )
    }

    /**
     * UI-W7: the confirmation before Wi-Fi Direct is switched on. Says plainly what it does and shows this phone's own Wi-Fi
     * Direct name, as other phones will see it. [proceed] runs only on Turn on; Cancel leaves the switch off.
     */
    private fun confirmWifiDirectOn(proceed: () -> Unit) {
        readDirectName { name ->
            if (isFinishing || isDestroyed) return@readDirectName
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wifi_direct_confirm_title)
                .setMessage(
                    if (WifiRowPolicy.directDescriptionUsesName(name)) getString(R.string.wifi_direct_confirm_message, name)
                    else getString(R.string.wifi_direct_confirm_message_unnamed),
                )
                .setPositiveButton(R.string.wifi_direct_confirm_turn_on) { _, _ -> proceed() }
                .setNegativeButton(R.string.action_cancel, null)
                .showTracked()
        }
    }

    /** Info-sheet text of the Direct row. The name comes from [DirectNameSource] (A9 step 8); until then it is null and the text says "its Wi-Fi Direct name". Never logged, never sent. [R M-10] */
    private fun describeDirect(deliver: (CharSequence?) -> Unit) = readDirectName { name -> deliver(directDescription(name)) }

    /** This phone's own Wi-Fi Direct name, or null when it could not be read in time. [deliver] runs exactly once, on the main thread. */
    private fun readDirectName(deliver: (String?) -> Unit) {
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        val answer: (String?) -> Unit = { name ->
            if (once.compareAndSet(false, true)) runOnUiThread { deliver(name) }
        }
        Handler(mainLooper).postDelayed({ answer(null) }, WifiFeatures.DIRECT_NAME_WAIT_MS)
        runCatching { DirectNameSource.read(answer) }.onFailure { answer(null) }   // a throwing name source never crashes the tap
    }

    private fun directDescription(name: String?): CharSequence =
        if (WifiRowPolicy.directDescriptionUsesName(name)) getString(R.string.feat_wifi_direct_description, name)
        else getString(R.string.feat_wifi_direct_description_unnamed)

    /** A Settings > Features switch changed: apply it to the running app. */
    private fun onConnectivityFeatureChanged(spec: FeatureSpec, enabled: Boolean) {
        refreshFeatureCatalog()
        when (spec) {
            ConnectivityFeatures.cocAutoGatt -> applyCocAutoGatt()
            ConnectivityFeatures.classicWithoutPairing -> {
                applyClassicFeatures()
                if (!enabled) ClassicAddressBook.clear()
                // An idle Classic call listener was opened with the old socket security; rebuild it so
                // the next call, in either direction, follows the switch.
                if (currentCallTransport == BluetoothTransportOption.CLASSIC_RFCOMM) {
                    restartIdleCallEndpointForRelayedCalls()
                }
            }
            // An idle CoC endpoint was opened with the old switch (its second listener and offer bit); rebuild it.
            ConnectivityFeatures.dualListening -> if (currentCallTransport == BluetoothTransportOption.BLE_L2CAP) {
                restartIdleCallEndpointForRelayedCalls()
            }
            ConnectivityFeatures.iphoneDiscovery -> applyCompactDiscovery()
            ConnectivityFeatures.findPcs, ConnectivityFeatures.pcStreams -> applyFindPcs()
            ConnectivityFeatures.shareAppNearby -> applyOfflineAppShare()
            ConnectivityFeatures.nativeOpus -> applyNativeOpus()
            // An idle call endpoint was opened with the old switch (its offer hint); rebuild it.
            ConnectivityFeatures.bmh3 -> restartIdleCallEndpointForRelayedCalls()
            // Read per connection (the epoch pseudonym is chosen when a connection is accepted): nothing to rebuild.
            ConnectivityFeatures.bmh3Pseudonyms -> Unit
            ConnectivityFeatures.wifiCarrier, ConnectivityFeatures.wifiWiderReach, ConnectivityFeatures.wifiDirect -> {
                // Empty on purpose [dir A8]: A6 reacts to nothing here. A7 (hop switch-off) and A9 (Direct) use hub listeners (item 4b); A8 (Wider reach) adds its lines after publish(), below. [R M-10]
            }
        }
        WifiSwitchHub.publish()
        if (spec === ConnectivityFeatures.wifiWiderReach) {
            WifiDiscovery.onSwitchesChanged()
            restartIdleCallEndpointForRelayedCalls()
        }
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "feature_changed: id=${spec.id} | enabled=$enabled | explicit=true",
        )
    }

    private val classicVisibilityRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // RESULT_CANCELED means refused; otherwise the result code is the granted duration in seconds.
        Log.i("BluetoothVideo", "Classic visibility request result: ${result.resultCode}")
    }

    /**
     * Bond-free Classic: a first contact needs the listening phone to be discoverable, because Android
     * reveals a phone's Classic address to an unpaired phone only through inquiry. Asks Android's
     * system dialog (the user decides) at most once per process, when this phone starts listening.
     */
    @SuppressLint("MissingPermission")
    private fun requestClassicVisibilityIfNeeded(transport: BluetoothTransportOption) {
        if (transport != BluetoothTransportOption.CLASSIC_RFCOMM || classicVisibilityRequested) return
        if (!ClassicFeatures.current.requestVisibility) return
        val adapter = bluetoothAdapter ?: return
        if (runCatching { adapter.scanMode }.getOrNull() == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) return
        classicVisibilityRequested = true
        runCatching {
            classicVisibilityRequest.launch(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, CLASSIC_VISIBILITY_SECONDS),
            )
        }.onFailure { Log.w("BluetoothVideo", "Classic visibility request unavailable", it) }
    }

    /** Applies the mode's effective technical values; manual choices stay stored for Advanced. */
    private fun applySettingsModeValues() {
        if (advancedSettings) {
            encoderRateControl = runCatching {
                EncoderRateControl.valueOf(appPreferences.getString(PREF_MANUAL_RATE_CONTROL, null) ?: "CBR")
            }.getOrDefault(EncoderRateControl.CBR)
            receiverPlaybackDelay = PlaybackDelayProfile.fromStoredValue(
                appPreferences.getString(PREF_RECEIVER_PLAYBACK_DELAY, null),
            ).takeIf { it != PlaybackDelayProfile.AUTO } ?: PlaybackDelayProfile.BALANCED
        } else {
            encoderRateControl = EncoderRateControl.AUTOMATIC
            receiverPlaybackDelay = PlaybackDelayProfile.AUTO
        }
    }

    private fun applySettingsModeVisibility() {
        val manual = if (advancedSettings) View.VISIBLE else View.GONE
        rateControlLabel.visibility = manual
        rateControlSpinner.visibility = manual
        receiverPlaybackDelaySlider.visibility = manual
        playbackStabilityText.visibility = manual
        // Design decision 2026-11-06 (GAP-AUDIT-2 Q2): the Experiments rows are shown with or without Manual video
        // settings (Apple shows them always); each row owns its (i) button.
        (experimentalTemporalLayersSwitch.parent as View).visibility = View.VISIBLE
        (relayChildMediaAgeSwitch.parent as View).visibility = View.VISIBLE
        (experimentalRelayedCallsSwitch.parent as View).visibility = View.VISIBLE
        // Private Bluetooth ID and Hide phone name moved to Settings > Privacy and are always shown there.
        refreshNeutralNameText()
        updateRelayedCallsSettingsUi()
        applyExperimentsHint()
        if (::mainNav.isInitialized) updateVideoRowValue()
        advancedSettingsDescriptionText.text = if (advancedSettings) {
            "On: every technical value below is exactly what you choose; nothing adapts automatically."
        } else {
            "Off (recommended): this phone tunes buffering, link rate, encoder speed and viewer capacity " +
                "from what it measures. " + if (AutoQualityFeature.enabled) {
                "Experimental Auto ladder is on: it also picks resolution, frame rate and bitrate from the measured link."
            } else {
                "You still choose codec, resolution, frame rate and bitrate."
            }
        }
        updatePlaybackDelayLabels()
    }

    private fun updatePlaybackDelayLabels() {
        playbackDelayValueText.text = if (receiverPlaybackDelay == PlaybackDelayProfile.AUTO) {
            "Playback buffer: automatic, ${receiverPlaybackDelay.delayMs}-${receiverPlaybackDelay.adaptiveMaxDelayMs} ms; " +
                "no frames are dropped"
        } else {
            "Playback buffer: ${receiverPlaybackDelay.delayMs}-${receiverPlaybackDelay.adaptiveMaxDelayMs} ms " +
                "(grows only while the link is late)"
        }
        playbackStabilityText.text =
            "Playback stability: ${receiverPlaybackDelay.stabilityLabel}"
    }

    private fun applyDeviceHealthOverlayVisibility() {
        if (::deviceHealthOverlay.isInitialized) {
            deviceHealthOverlay.visibility = if (showDeviceHealthOverlay) View.VISIBLE else View.GONE
        }
    }

    private fun restoreVideoSettings() {
        val default = VideoSettings.DEFAULT
        val width = appPreferences.getInt(PREF_VIDEO_WIDTH, default.resolution.width)
            .coerceIn(1, 4_096)
        val height = appPreferences.getInt(PREF_VIDEO_HEIGHT, default.resolution.height)
            .coerceIn(1, 4_096)
        settings = default.copy(
            resolution = VideoResolution(width, height, "${height}p"),
            fps = appPreferences.getInt(PREF_VIDEO_FPS, default.fps).coerceIn(1, 60),
            bitRate = appPreferences.getInt(PREF_VIDEO_BITRATE, default.bitRate).coerceIn(
                VideoSettings.MIN_BIT_RATE_KBPS * 1_000,
                maxOf(
                    outgoingTransport.maxVideoBitrateKbps,
                    LinkCapacity.measuredLinkKbps(outgoingTransport) ?: 0,
                ) * 1_000,
            ),
        )
    }

    /**
     * Workstream D: the highest video bitrate this phone measured its link can carry on the selected
     * transport (from children's packet-train reports and probes). The fixed per-transport number is
     * only the fallback before any measurement.
     */
    private fun measuredBitrateCeilingKbps(): Int? = LinkCapacity.measuredLinkKbps(outgoingTransport)?.let { link ->
        val video = MeshBandwidthEstimator.videoKbpsForLink(link.toDouble(), settings.fps)
        (video - (video - VideoSettings.MIN_BIT_RATE_KBPS).mod(VideoSettings.BIT_RATE_STEP_KBPS))
            .coerceAtLeast(VideoSettings.MIN_BIT_RATE_KBPS)
    }

    /** A saved choice above the measured ceiling stays selectable; it is labelled, never lowered. */
    private fun bitrateSliderMaximumKbps(): Int =
        maxOf(measuredBitrateCeilingKbps() ?: outgoingTransport.maxVideoBitrateKbps, settings.bitRate / 1_000)

    /** Workstream C: each viewer's share of this phone's measured link bounds one frame's size. */
    private fun updateEncoderFrameBudget(viewers: Int) {
        lastFrameBudgetViewers = viewers
        val linkKbps = LinkCapacity.measuredLinkKbps(outgoingTransport)
            ?: LinkCapacity.priorKbps(outgoingTransport).toInt()
        cameraEncoder?.updateLinkFrameBudget(linkKbps / viewers.coerceAtLeast(1))
    }

    private fun applyTransportBitrateLimit(persist: Boolean) {
        val maximumKbps = measuredBitrateCeilingKbps() ?: outgoingTransport.maxVideoBitrateKbps
        // Only the hard transport bound (raised by a higher measurement) lowers a saved choice.
        val hardMaximumKbps = maxOf(outgoingTransport.maxVideoBitrateKbps, measuredBitrateCeilingKbps() ?: 0)
        val clampedBitrate = settings.bitRate.coerceAtMost(hardMaximumKbps * 1_000)
        val changed = clampedBitrate != settings.bitRate
        if (changed) settings = settings.copy(bitRate = clampedBitrate)
        if (::bitrateSeekBar.isInitialized) {
            bitrateSeekBar.max = (maxOf(maximumKbps, settings.bitRate / 1_000) - VideoSettings.MIN_BIT_RATE_KBPS) /
                VideoSettings.BIT_RATE_STEP_KBPS
            bitrateSeekBar.progress = (settings.bitRate / 1_000 - VideoSettings.MIN_BIT_RATE_KBPS) /
                VideoSettings.BIT_RATE_STEP_KBPS
        }
        if (changed && persist) persistVideoSettings()
    }

    private fun persistVideoSettings() {
        if (debugLaunchRequested || requestedTransportExperiment != null) return
        if (autoLadderSavedSettings != null) return // the Auto plan is not the user's choice
        appPreferences.edit()
            .putInt(PREF_VIDEO_WIDTH, settings.resolution.width)
            .putInt(PREF_VIDEO_HEIGHT, settings.resolution.height)
            .putInt(PREF_VIDEO_FPS, settings.fps)
            .putInt(PREF_VIDEO_BITRATE, settings.bitRate)
            .apply()
    }

    private fun configureSeekBar(
        seekBar: SeekBar,
        maximum: Int,
        initial: Int,
        onChanged: (Int) -> Unit,
        onStopped: () -> Unit = {},
    ) {
        seekBar.max = maximum
        seekBar.progress = initial
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                onChanged(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = onStopped()
        })
    }

    private fun updateSettingsLabels() {
        val measured = measuredBitrateCeilingKbps()
        val limitLabel = when {
            measured == null -> "${outgoingTransport.maxVideoBitrateKbps} kbps max until this phone measures the link"
            settings.bitRate / 1_000 > measured -> "above the $measured kbps this phone measured; may stall"
            else -> "this phone measured up to $measured kbps"
        }
        bitrateText.text = "Broadcast video bitrate: ${settings.bitRate / 1_000} kbps ($limitLabel)" +
            (manualStarvationWarning()?.let { "\n$it" } ?: "")
        if (::streamSummaryText.isInitialized) updateStreamSummary()
        if (::capabilityText.isInitialized) updateDeviceCapabilities()
        updatePrivateCallProfileSummary()
    }

    private fun updatePrivateCallProfileSummary() {
        if (!::privateCallProfileSummary.isInitialized) return
        val selectedCodec = codecs.getOrNull(codecSpinner.selectedItemPosition)
        if (selectedCodec == null) {
            privateCallProfileSummary.text = "No compatible private-call video encoder on this phone."
            return
        }
        val fingerprint = privateCallStackFingerprint()
        val callCodec = privateCallCodec(callRequestedCodec(selectedCodec), codecs, fingerprint, outgoingTransport)
        val callSettings = privateCallVideoSettings(
            settings, outgoingTransport, fingerprint,
            preserveSelectedBitrate = callBitrateAdapts() || DebugTunables.wifiExactKbps > 0,
            ignoreFamilyRateCap = DebugTunables.wifiExactKbps > 0,
        )
        val audioBitRate = privateCallAudioBitRate(outgoingTransport, fingerprint)
        val playback = privateCallPlaybackDelayProfile(
            receiverPlaybackDelay,
            outgoingTransport,
            fingerprint,
            null,
            legacyLowDelay = DebugTunables.callLegacyLowDelay,
        )
        privateCallProfileSummary.text =
            "Selected video: ${VideoCodecMap.label(selectedCodec.protocolId)} | " +
                "${settings.resolution.width}x${settings.resolution.height} @ ${settings.fps} FPS | " +
                "${settings.bitRate / 1_000} kbps | ${receiverPlaybackDelay.delayMs} ms buffer\n" +
                "Call on this phone: ${privateCallProfileLabel(callSettings, audioBitRate, callCodec, playback)} " +
                "(${playback.delayMs} ms buffer)\n" +
                "The other phone may require a different profile, shown during the call."
    }

    private fun privateCallProfileLabel(
        callSettings: VideoSettings,
        audioBitRate: Int,
        callCodec: VideoCodec,
        playback: PlaybackDelayProfile,
    ): String {
        val codecName = VideoCodecMap.label(callCodec.protocolId)
        return "$codecName | ${callSettings.resolution.width}x${callSettings.resolution.height} @ " +
            "${callSettings.fps} FPS | ${callSettings.bitRate / 1_000} kbps video | " +
            "${audioBitRate / 1_000} kbps audio | ${playback.label}"
    }

    private fun updateDeviceCapabilities() {
        if (!::capabilityText.isInitialized) return
        val codec = codecs.getOrNull(codecSpinner.selectedItemPosition)
        if (codec == null) {
            capabilityText.text = "No AV1 or VP9 encoder"
            return
        }
        val selectedMode = selectedDeviceMode()
        capabilityText.text = buildList {
            add(
                "Camera input: " + when {
                    codec.usesSurfaceInput -> "direct surface"
                    codec.backend == VideoEncoderBackend.NATIVE_LIBAOM -> "YUV to native libaom"
                    codec.backend == VideoEncoderBackend.NATIVE_LIBVPX -> "YUV to native libvpx"
                    else -> "YUV copy (native size)"
                },
            )
            if (deviceModes.isEmpty()) {
                add("Native modes: none at ${settings.bitRate / 1_000} kbps")
            } else {
                val smallest = deviceModes.first().resolution
                val largest = deviceModes.last().resolution
                add(
                    "Native sizes: ${deviceModes.size} " +
                        "(${smallest.width}×${smallest.height} to ${largest.width}×${largest.height})",
                )
            }
            selectedMode?.let { mode ->
                add("FPS: ${mode.fpsOptions.joinToString(" / ")}")
                mode.encoderFpsEstimate?.let { estimate ->
                    add("OEM codec estimate: %.0f-%.0f FPS".format(estimate.lower, estimate.upper))
                }
            }
        }.joinToString("\n")
    }

    private fun updateStreamSummary() {
        if (!::streamSummaryText.isInitialized) return
        val estimate = MeshBandwidthEstimator.estimate(
            settings,
            outgoingTransport,
            MeshNodeRole.SOURCE,
            plannedChildren = 1,
        )
        val budgetKbps = LinkCapacity.budgetKbps(outgoingTransport, LinkLoadKind.FAN_OUT, estimate.perLinkKbps)
        val relayBudgetKbps = LinkCapacity.budgetKbps(outgoingTransport, LinkLoadKind.RELAY, estimate.perLinkKbps)
        streamSummaryText.text = "Bandwidth/stream: ~%.0f kbps\n".format(estimate.perLinkKbps) +
            "Video: ${settings.bitRate / 1_000} kbps\n" +
            "Mode: ${settings.resolution.width}×${settings.resolution.height} @ ${settings.fps} FPS\n" +
            "This phone on ${outgoingTransport.shortLabel}: ~%.0f kbps total, ".format(budgetKbps) +
            "${(budgetKbps / estimate.perLinkKbps).toInt()} viewer link(s), " +
            (if (relayFits(relayBudgetKbps, estimate.perLinkKbps)) "can relay" else "cannot relay") +
            (WifiCoexistence.advice()?.let { "\n$it" } ?: "")
        if (::homeConfigurationSummary.isInitialized) {
            homeConfigurationSummary.text =
                "${settings.resolution.width}×${settings.resolution.height} | ${settings.fps} FPS | " +
                "~%.0f kbps\n%s".format(estimate.perLinkKbps, outgoingTransport.shortLabel)
        }
    }

    private fun refreshCodecs() {
        val selectedEncoder = codecs.getOrNull(codecSpinner.selectedItemPosition)?.encoderName
        codecs = rankEncoders(VideoCodec.availableEncoders())
        decoders = VideoCodec.availableDecoders()
        codecSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            codecs.map(VideoCodec::displayName).ifEmpty { listOf("No compatible AV1 or VP9 encoder") },
        )
        codecs.indexOfFirst { it.encoderName == selectedEncoder }
            .takeIf { it >= 0 }
            ?.let(codecSpinner::setSelection)
        decoderSupportText.text = decoders.joinToString("\n") { decoder ->
            "${decoder.codecName} - ${if (decoder.hardwareAccelerated) "hardware" else "software"}"
        }.ifEmpty { "No compatible AV1 or VP9 decoder" }
        refreshRateControlPicker()
        refreshVideoModes()
        updateDeviceCapabilities()
        updateStreamSummary()
        updateStartEnabled()
    }

    /**
     * Experimental encoder ranking (off by default): the encoder measured on this phone
     * to keep scene cuts small and keep up with the frame rate becomes the default choice. Off: the
     * static hardware-first order. The order and records are logged in both arms (DEBUG).
     */
    /**
     * The encoder a stream session starts with: the picker's entry, except that `codec_power_aware` (battery saver or a
     * warm phone, read here once at session start) may swap an Automatic software first choice for a hardware encoder.
     * Logs one `BENCHMARK encoder_choice` line per stream session (DEBUG); calls log theirs after negotiation.
     */
    private fun streamSessionCodec(codec: VideoCodec): VideoCodec {
        val switches = VideoCodecSwitches.current(
            power = CodecPowerAware.read(this),
            // AP5 test_sw_shortfall_avc: read once here, at the session start (empty while the switch is off).
            slowSoftware = SoftwareShortfallStore.slowEncoders(this, settings),
        )
        val choice = VideoCodecChoice.forStream(
            requested = codec,
            userChoice = AutomaticEncoderChoice.userChoice,
            available = codecs,
            switches = switches,
            session = CodecSession.of(settings),
            excluded = VideoCodecChoice::policyExcluded,
            staticIneligibility = VideoCodec.hcStaticIneligibility,
        )
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", VideoCodecChoice.logLine("stream", choice, switches, null))
        return if (choice.powerApplied || choice.shortfallApplied) choice.sent ?: codec else codec
    }

    /**
     * AP5 `test_sw_shortfall_avc` (Experimental, off; SoftwareEncoderShortfall): what a camera encoder reports a software
     * encoder shortfall to. Null in release builds. With the switch on the shortfall is remembered for the next call or
     * stream start (`mode=record`); off (DEBUG) it is only logged (`mode=observe`), so the control arm shows the same events.
     */
    private fun softwareShortfallListener(role: String): ((SoftwareShortfallMark, SoftwareShortfallVerdict) -> Unit)? {
        if (!BuildConfig.DEBUG) return null
        val context = applicationContext
        return { mark, verdict ->
            val on = DebugTunables.swShortfallAvc
            if (on) SoftwareShortfallStore.record(context, mark)
            fun ms(value: Double?) = value?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "-"
            Log.i(
                "BluetoothVideo",
                "BENCHMARK sw_encoder_shortfall: role=$role | mode=${if (on) "record" else "observe"} | " +
                    "encoder=${mark.encoderName} | profile=${mark.width}x${mark.height}@${mark.fps} | " +
                    "targetFps=${verdict.targetFps} | outputFps=${ms(verdict.outputFps)} | " +
                    "encodeP95Ms=${ms(verdict.encodeP95Ms)} | sourceFrameMs=${ms(verdict.sourceFrameMs)} | " +
                    "inputDrops=${verdict.inputDrops} | windows=${verdict.windows}",
            )
        }
    }

    /** The encoder of the current stream session, chosen once when it starts (direct start or going live). */
    private var streamSessionEncoder: VideoCodec? = null

    /** Starts a stream session's encoder choice ([streamSessionCodec]); every later step of the session reuses it. */
    private fun beginStreamSessionCodec(): VideoCodec? {
        val picked = codecs.getOrNull(codecSpinner.selectedItemPosition) ?: return null
        return streamSessionCodec(picked).also { streamSessionEncoder = it }
    }

    /**
     * The stream's encoder: the session's own while it holds one (a power-aware choice may differ from the picker, and
     * a viewer joining, a camera flip or a resume must not change the codec mid-stream), else the picker's entry.
     */
    private fun activeStreamCodec(): VideoCodec? =
        streamSessionEncoder?.takeIf { it in codecs } ?: codecs.getOrNull(codecSpinner.selectedItemPosition)

    /**
     * The codec a call starts from: the picker's entry when chosen by hand, else (Design HC, a master on) the first
     * entry of the call Automatic order, which differs from the stream order the picker shows. Both masters off: the
     * picker's entry, as before.
     */
    private fun callRequestedCodec(selected: VideoCodec): VideoCodec {
        val switches = VideoCodecSwitches.current()
        if (!switches.anyNew || AutomaticEncoderChoice.userChoice) return selected
        return VideoCodecChoice.automatic(
            codecs, CodecChoiceMode.CALL, switches, CodecSession.of(settings), VideoCodecChoice::policyExcluded,
        ).firstOrNull() ?: selected
    }

    private fun rankEncoders(detected: List<VideoCodec>): List<VideoCodec> {
        // Design decision 2026-10-27: Automatic prefers an eligible hardware AV1 encoder (control arm
        // test_prefer_hw_av1=false). A phone without one keeps the static order unchanged. Design HC (a master on):
        // the stream Automatic order first, then the encoders only a manual choice may use.
        val available = VideoCodecChoice.pickerOrder(
            detected,
            VideoCodecSwitches.current(),
            CodecSession.of(settings),
            VideoCodecChoice::policyExcluded,
        )
        val store = EncoderBurstProfileStore(this)
        val records = available.associate { it.encoderName to runCatching { store.load(it.encoderName) }.getOrNull() }
        val ranked = if (DebugTunables.experimentalEncoderRanking) {
            EncoderBurstRanking.order(available) { name -> records[name] }
        } else {
            available
        }
        if (BuildConfig.DEBUG) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK encoder_ranking: experiment=${DebugTunables.experimentalEncoderRanking} | order=" +
                    ranked.joinToString(",") { it.encoderName } + " | records=" +
                    available.joinToString(",") { codec ->
                        val record = records[codec.encoderName]
                        if (record == null) {
                            "${codec.encoderName}=unmeasured"
                        } else {
                            "${codec.encoderName}=burst ${roundedForLog(record.burstSeconds, 2)} s" +
                                (record.encodeLoad?.let { " load ${roundedForLog(it, 2)}" } ?: "") +
                                " fits ${record.fits} (${record.sessions} sessions)"
                        }
                    },
            )
        }
        return ranked
    }

    private fun refreshRateControlPicker() {
        if (!::rateControlSpinner.isInitialized) return
        val codec = codecs.getOrNull(codecSpinner.selectedItemPosition)
        val available = codec?.availableRateControls() ?: listOf(EncoderRateControl.AUTOMATIC)
        rateControlOptions = if (advancedSettings) {
            available.filter { it != EncoderRateControl.AUTOMATIC }.ifEmpty { available }
        } else {
            listOf(EncoderRateControl.AUTOMATIC)
        }
        val selectedIndex = rateControlOptions.indexOf(encoderRateControl).takeIf { it >= 0 } ?: 0
        updatingRateControlPicker = true
        rateControlSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            rateControlOptions.map(EncoderRateControl::label),
        )
        rateControlSpinner.setSelection(selectedIndex, false)
        encoderRateControl = rateControlOptions[selectedIndex]
        updatingRateControlPicker = false
        updateRateControlDescription()
    }

    private fun updateRateControlDescription() {
        if (::rateControlDescriptionText.isInitialized) {
            rateControlDescriptionText.text = encoderRateControl.description
        }
    }

    private fun refreshVideoModes() {
        val preferredResolution = settings.resolution
        val preferredFps = settings.fps
        val codec = codecs.getOrNull(codecSpinner.selectedItemPosition)
        deviceModes = codec?.let { selected ->
            runCatching { deviceVideoCapabilities.modesFor(selected, settings.bitRate, cameraLens) }
                .getOrDefault(emptyList())
        }.orEmpty()

        updatingModePickers = true
        resolutionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            deviceModes.map { mode ->
                "${mode.resolution.width}×${mode.resolution.height} (${mode.resolution.label})"
            }.ifEmpty { listOf("No native modes") },
        )
        val selectedIndex = deviceModes.indexOfFirst { it.resolution == preferredResolution }
            .takeIf { it >= 0 }
            ?: deviceModes.indices.minByOrNull { index ->
                val resolution = deviceModes[index].resolution
                kotlin.math.abs(
                    resolution.width.toLong() * resolution.height -
                        preferredResolution.width.toLong() * preferredResolution.height,
                )
            }
        selectedIndex?.let { index ->
            // A raw fixture has its own declared geometry: camera modes never constrain it, and the picker is
            // not moved either (its deferred selection callback is also ignored, see the listeners).
            if (RawFixtureGeometry.pickersMayChangeSettings(debugRawInputFile != null)) {
                resolutionSpinner.setSelection(index, false)
            }
            val mapped = deviceModes[index].resolution
            if (debugRawInputFile == null) {
                if (mapped.width != preferredResolution.width || mapped.height != preferredResolution.height) {
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK mode_mapping: requested=${preferredResolution.width}x${preferredResolution.height} " +
                            "effective=${mapped.width}x${mapped.height} | reason=nearest_camera_mode | " +
                            "encoder=${codec?.encoderName ?: "none"}",
                    )
                }
                settings = settings.copy(resolution = mapped)
            }
        }
        refreshFpsPicker(preferredFps)
        updatingModePickers = false
        updateSettingsLabels()
        restartIdleCameraPreview()
        updateStartEnabled()
    }

    private fun refreshFpsPicker(preferredFps: Int) {
        val options = selectedDeviceMode()?.fpsOptions.orEmpty()
        fpsSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options.map { "$it FPS" }.ifEmpty { listOf("No supported FPS") },
        )
        val selectedIndex = options.indexOf(preferredFps)
            .takeIf { it >= 0 }
            ?: options.indices.minByOrNull { index -> kotlin.math.abs(options[index] - preferredFps) }
        selectedIndex?.let { index ->
            if (RawFixtureGeometry.pickersMayChangeSettings(debugRawInputFile != null)) {
                fpsSpinner.setSelection(index, false)
            }
            if (debugRawInputFile == null) {
                if (options[index] != preferredFps) {
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK mode_mapping: requestedFps=$preferredFps effectiveFps=${options[index]} | reason=nearest_camera_fps",
                    )
                }
                settings = settings.copy(fps = options[index])
            }
        }
    }

    private fun selectedDeviceMode(): DeviceVideoMode? =
        deviceModes.getOrNull(resolutionSpinner.selectedItemPosition)

    /**
     * DEBUG phone test P7: the unpaired-central probe (GapNameProbe), once per process, only with its launch extra
     * (any type). Asked from onResume as well as onPermissionsReady, because a launch that stays on Home never reaches
     * the latter; a refusal is logged with its reason (`gap_name_probe: event=skipped`).
     */
    private fun maybeStartGapNameProbe(source: String) {
        if (!BuildConfig.DEBUG || gapNameProbeStarted) return
        if (GapNameProbe.maybeStart(this, bluetoothAdapter, intent.extras, source)) gapNameProbeStarted = true
    }

    private fun onPermissionsReady() {
        audioAfterPermissions?.let { callback -> // AUDIO-LIVE hook
            audioAfterPermissions = null
            if (hasRequiredPermissions()) callback()
        }
        // The explicit neutral-name option may have been skipped while permissions were missing.
        applyNeutralName()
        maybeStartGapNameProbe("permissions_ready")
        if (RotatingDiscoveryFeature.enabled) applyRotatingIds()
        when {
            requestedTransportExperiment != null -> {
                if (transportExperimentPending) runRequestedTransportExperiment()
            }
            appScreen == AppScreen.CAMERA && usesBroadcastDiscovery() -> {
                startIdleCameraPreview()
                startStreamPresence()
            }
            appScreen == AppScreen.RECEIVER_BROWSER -> refreshNearbyStreams()
            appScreen == AppScreen.CALL_BROWSER -> startPrivateCallEndpoint()
            appScreen == AppScreen.PLAYBACK && reconnectTarget != null -> refreshNearbyStreams()
            appScreen == AppScreen.PLAYBACK &&
                (selectedStreamPeer != null || debugLaunchRequested) ->
                preview.post { if (receiver == null) startReceiving() }
            else -> {
                refreshDevices()
                startIdleCameraPreview()
            }
        }
        updateStartEnabled()
    }

    private fun startStreamPresence() {
        val adapter = bluetoothAdapter ?: return
        if (appScreen != AppScreen.CAMERA || !usesBroadcastDiscovery() ||
            !streamArmed || streamAdvertiser != null ||
            streamPresenceStarting || !hasRequiredPermissions()
        ) {
            return
        }
        if (BluetoothOnRecoveryPolicy.deferStreamOfferStart(BluetoothOnRecovery.enabled, BluetoothEpoch.core.recoveryPending)) {
            // Bluetooth is off or just came back: the offer would run against a dead stack. The ON callback starts it.
            BluetoothOnRecovery.log(BluetoothOnRecovery.COMPONENT_STREAM_ADVERTISE, "skipped", "start_deferred_until_bluetooth_on")
            setStatus(getString(R.string.live_status_bluetooth_off))
            return
        }
        if (streamToken == 0) streamToken = StreamTokenGenerator.next()
        streamPresenceStarting = true
        val activeToken = streamToken
        val advertisedGeneration = streamCapacityGeneration
        val advertisedFull = broadcaster?.sourceSlotsFull() == true
        val activeTransport = outgoingTransport
        val audioOffer = audioLive.source?.sendConfig?.takeIf { audioLive.mode } // AUDIO-LIVE hook
        val activeCodec = codecs.getOrNull(codecSpinner.selectedItemPosition)
            ?: if (audioOffer != null) AudioLiveSendConfig.PLACEHOLDER_CODEC else return
        val advertisedSettings = settings
        // The epoch the offer's RDI belongs to (-1: version 2): a boundary during this start must not leave it on air.
        val advertisedEpoch = LongArray(1) { -1L }
        thread(name = "BleStreamAdvertiser") {
            val result = runCatching {
                val identity = AndroidNodeIdentityStore().getOrCreate()
                // Rotating IDs, phase 1b: a version 5 offer with the role-1 RDI (LE only; version 2 inside a
                // Visible-by-handle window). Off: rotatingEntry is null and nothing below changes.
                val rotatingEntry = if (RotatingStreamAnnouncements.offerRotates(RotatingDiscoveryFeature.enabled, activeTransport) &&
                    !RotatingDiscovery.visibleByHandle()
                ) {
                    RotatingDiscovery.advertise(RotatingDiscoveryIds.ROLE_STREAM_OFFER).also { advertisedEpoch[0] = it.epoch }
                } else {
                    null
                }
                val nodePrefix = rotatingEntry?.rdi ?: identity.nodeId.toByteArray()
                    .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
                val routeToken = StreamRouteToken.forAdvertiser(activeToken, nodePrefix)
                val streamOfferAnnouncement = RotatingAdvertFields.forStreamOffer(DiscoveryAnnouncement(
                        nodeIdPrefix = nodePrefix,
                        version = if (rotatingEntry != null) {
                            DiscoveryAnnouncement.ROTATING_VERSION
                        } else {
                            DiscoveryAnnouncement.FIXED_PREFIX_VERSION
                        },
                        flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE or
                            (if (streamPaused) DiscoveryAnnouncement.FLAG_STREAM_PAUSED else 0) or
                            when (activeTransport) {
                                BluetoothTransportOption.CLASSIC_RFCOMM ->
                                    DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT
                                BluetoothTransportOption.BLE_GATT ->
                                    DiscoveryAnnouncement.FLAG_GATT_TRANSPORT
                                BluetoothTransportOption.BLE_L2CAP -> 0
                            },
                        capabilitySummary = if (audioOffer != null) {
                            DiscoveryAnnouncement.SUMMARY_AUDIO_ONLY // AUDIO-LIVE hook: audio-only marker, no geometry
                        } else {
                            VideoCodecMap.summaryBit(activeCodec.protocolId)
                        },
                        streamToken = routeToken,
                        routeCost = 0,
                        sequence = (advertisedGeneration and DiscoveryAnnouncement.SEQUENCE_GENERATION_MASK) or
                            if (advertisedFull) DiscoveryAnnouncement.SEQUENCE_SOURCE_FULL else 0,
                        streamWidth = if (audioOffer != null) 0 else advertisedSettings.resolution.width,
                        streamHeight = if (audioOffer != null) 0 else advertisedSettings.resolution.height,
                        streamFps = if (audioOffer != null) 0 else advertisedSettings.fps,
                        streamBitrateKbps = audioOffer?.kbps ?: (advertisedSettings.bitRate / 1_000),
                    ))
                lastStreamOfferAnnouncement = streamOfferAnnouncement
                BleDiscoveryAdvertiser(
                    adapter,
                    streamOfferAnnouncement,
                    { message ->
                        if (streamPaused) {
                            setStatus(getString(R.string.live_status_paused_viewers))
                        } else {
                            setStatus(message)
                        }
                    },
                ).also(BleDiscoveryAdvertiser::start) to routeToken
            }
            // Experimental peer labels: public title pages (unconfirmed for viewers until they join).
            // Started after the stream offer so a refused extra set never affects the stream.
            val titlePages = result.getOrNull()?.second?.let { routeToken ->
                PeerLabelFeature.localStreamTitle?.takeIf { PeerLabelFeature.titlePagesEnabled }?.let { title ->
                    if (AdvertisingSetProbe.lastFree == null) runCatching { AdvertisingSetProbe.run(adapter) }
                    runCatching {
                        TitleAdvertiser(
                            adapter, routeToken, title,
                            selfRotate = RotatingDiscoveryFeature.enabled &&
                                !RotatingStreamAnnouncements.offerRotates(true, activeTransport),
                        )
                    }.getOrNull()?.takeIf {
                        it.start() == android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_SUCCESS
                    }
                }
            }
            runOnUiThread {
                streamPresenceStarting = false
                result.onSuccess { (advertiser, routeToken) ->
                    if (appScreen == AppScreen.CAMERA && streamArmed && streamToken == activeToken &&
                        streamCapacityGeneration == advertisedGeneration
                    ) {
                        streamTitleAdvertiser?.close()
                        streamTitleAdvertiser = titlePages
                        streamAdvertiser?.takeIf { it !== advertiser }?.close()
                        streamAdvertiser = advertiser
                        lastStreamOfferAnnouncement?.let { WifiDiscovery.publishOffer(WifiOfferRole.STREAM_OFFER, it) }
                        streamAdvertisedFull = advertisedFull
                        streamRouteToken = routeToken
                        streamRequestTokens.advance(activeToken, routeToken, SystemClock.elapsedRealtime())
                        if (RotatingStreamAnnouncements.offerRotates(RotatingDiscoveryFeature.enabled, activeTransport) &&
                            !streamBoundaryListening
                        ) {
                            streamBoundaryListening = true
                            RotatingDiscovery.addBoundaryListener(streamBoundaryListener)
                        }
                        startViewerRequestDiscovery()
                        if (advertisedEpoch[0] >= 0L &&
                            runCatching { RotatingDiscovery.currentEpoch() }.getOrDefault(advertisedEpoch[0]) != advertisedEpoch[0]
                        ) {
                            // A boundary passed while this set was starting (the listener did not exist yet).
                            rotateStreamPresenceForBoundary()
                        }
                    } else {
                        advertiser.close()
                        titlePages?.close()
                        if (appScreen == AppScreen.CAMERA && streamArmed && streamToken == activeToken) {
                            startStreamPresence()
                        }
                    }
                }.onFailure { error ->
                    if (BluetoothOnRecoveryPolicy.keepStreamArmedAfterAdvertiseFailure(
                            BluetoothOnRecovery.enabled, BluetoothEpoch.core.recoveryPending,
                        )
                    ) {
                        // The adapter is down or just came back: the failure is Bluetooth's, not the stream's. Keep the
                        // broadcast (and its encoder session) and let the ON callback advertise again.
                        BluetoothOnRecovery.log(
                            BluetoothOnRecovery.COMPONENT_STREAM_ADVERTISE, "skipped", "advertise_failed_while_bluetooth_down_kept_armed",
                            "error=${error.message ?: error.javaClass.simpleName}",
                        )
                        setStatus(getString(R.string.live_status_bluetooth_off))
                        return@onFailure
                    }
                    streamArmed = false
                    stopForegroundSessionIfIdle()
                    Log.w("BluetoothVideo", "Could not advertise this stream", error)
                    setStatus(getString(R.string.live_status_failed))
                    updateStartEnabled()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startViewerRequestDiscovery() {
        val adapter = bluetoothAdapter ?: return
        if (streamDiscovery != null || streamToken == 0 || streamRouteToken == 0 ||
            appScreen != AppScreen.CAMERA ||
            sender != null
        ) {
            return
        }
        val activeToken = streamToken
        val activeRouteToken = streamRouteToken
        val sourceSlotsFull = broadcaster?.sourceSlotsFull() == true
        val classicSetupPending = classicSourceConnectionPending()
        val radioBusy = sourceSlotsFull || classicSetupPending
        val testScanMode = if (BuildConfig.DEBUG) {
            intent.getStringExtra("test_viewer_scan_mode")?.takeIf {
                outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM ||
                    outgoingTransport == BluetoothTransportOption.BLE_GATT && it == "off_when_full"
            }
        } else null
        val pauseAtCapacity = when (testScanMode) {
            "off_when_full" -> true
            "balanced", "low_latency" -> false
            else -> pauseClassicViewerDiscoveryAtCapacity(outgoingTransport, BluetoothStackFingerprint.current())
        }
        if (pauseAtCapacity && sourceSlotsFull) return
        val continuousLowLatency = when (testScanMode) {
            "balanced" -> false
            "low_latency" -> true
            else -> useContinuousLowLatencyViewerDiscovery(
                outgoingTransport, BluetoothStackFingerprint.current(), radioBusy,
            )
        }
        lateinit var discovery: BleL2capDiscovery
        discovery = BleL2capDiscovery(
            adapter,
            { peer ->
                runOnUiThread {
                    if (streamDiscovery !== discovery) return@runOnUiThread
                    viewerPeersLastSeen[peer.device.address] = peer to SystemClock.elapsedRealtime()
                    viewerPeersLastSeen.remove("wifi:" + WifiSighting.keyOf(peer.announcement))   // [AM 17] one person, one entry
                    refreshPendingViewerPeers()
                    if (streamArmed) startRequestedBroadcast()
                }
            },
            {
                runOnUiThread {
                    if (streamDiscovery !== discovery) return@runOnUiThread
                    streamDiscovery = null
                    refreshPendingViewerPeers()
                    if (appScreen == AppScreen.CAMERA && streamToken == activeToken) {
                        discoveryHandler.postDelayed(::startViewerRequestDiscovery, DISCOVERY_RESTART_MS)
                    }
                }
            },
            { message ->
                runOnUiThread {
                    if (streamDiscovery !== discovery) return@runOnUiThread
                    streamDiscovery = null
                    refreshPendingViewerPeers()
                    setStatus(message)
                    if (appScreen == AppScreen.CAMERA && streamToken == activeToken) {
                        discoveryHandler.postDelayed(::startViewerRequestDiscovery, SCAN_ERROR_RETRY_MS)
                    }
                }
            },
            { announcement ->
                viewerRequestAccepts(announcement)
            },
            scanDurationMs = CONTINUOUS_SCAN_MS,
            reportDuplicates = true,
            acceptRotating = RotatingDiscoveryFeature.enabled,
            continuousLowLatency = continuousLowLatency,
            startLowLatency = when (testScanMode) {
                "balanced" -> false
                "low_latency" -> true
                else -> !(outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM && radioBusy)
            },
        )
        streamDiscovery = discovery
        viewerDiscoveryContinuousLowLatency = continuousLowLatency
        discovery.start()
        WifiDiscovery.setDemand(WifiDiscovery.Demand.VIEWER_REQUESTS, true)
        if (testScanMode == "balanced" || testScanMode == "low_latency") {
            Log.i("BluetoothVideo", "DEBUG: Classic viewer discovery scan mode $testScanMode")
        } else if (classicSetupPending && !sourceSlotsFull) {
            Log.i("BluetoothVideo", "Classic connection pending; balanced viewer discovery frees the radio for setup")
        } else if (sourceSlotsFull && outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM) {
            Log.i("BluetoothVideo", "Classic source slots full; balanced viewer discovery protects active media")
        } else if (!continuousLowLatency) {
            Log.i(
                "BluetoothVideo",
                "Legacy Qualcomm Classic viewer discovery will switch to balanced scan while streaming.",
            )
        }
        discoveryHandler.postDelayed(::expireViewerRequests, VIEWER_EXPIRY_CHECK_MS)
    }

    private fun refreshViewerDiscoveryForCapacity() {
        val testScanMode = if (BuildConfig.DEBUG) {
            intent.getStringExtra("test_viewer_scan_mode")?.takeIf {
                outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM ||
                    outgoingTransport == BluetoothTransportOption.BLE_GATT && it == "off_when_full"
            }
        } else null
        if (testScanMode == null && classicSourceConnectionPending() && streamDiscovery?.isLowLatency() == true) {
            // Measured ASUS->S24 Classic setup: low-latency viewer scanning during page/SDP/RFCOMM
            // took 3.7-3.9 s versus 1.9 s with balanced scanning. Request detection stays fast
            // because the low-latency scan only yields while a connection is in flight.
            discoveryHandler.removeCallbacksAndMessages(null)
            streamDiscovery?.close()
            streamDiscovery = null
            startViewerRequestDiscovery()
            return
        }
        val pauseAtCapacity = when (testScanMode) {
            "off_when_full" -> true
            "balanced", "low_latency" -> false
            else -> pauseClassicViewerDiscoveryAtCapacity(outgoingTransport, BluetoothStackFingerprint.current())
        }
        if (pauseAtCapacity) {
            if (broadcaster?.sourceSlotsFull() == true) {
                if (streamDiscovery != null) {
                    streamDiscovery?.close()
                    streamDiscovery = null
                    val transportLabel = outgoingTransport.shortLabel
                    Log.i("BluetoothVideo", "$transportLabel source slots full; viewer discovery stopped")
                }
            } else if (streamDiscovery == null) {
                val transportLabel = outgoingTransport.shortLabel
                Log.i("BluetoothVideo", "$transportLabel source slot free; viewer discovery restarting")
                startViewerRequestDiscovery()
            }
            return
        }
        if (outgoingTransport != BluetoothTransportOption.CLASSIC_RFCOMM ||
            streamDiscovery == null || BuildConfig.DEBUG &&
            intent.getStringExtra("test_viewer_scan_mode") in setOf("balanced", "low_latency")
        ) return
        val continuous = useContinuousLowLatencyViewerDiscovery(
            outgoingTransport, BluetoothStackFingerprint.current(), broadcaster?.sourceSlotsFull() == true,
        )
        if (viewerDiscoveryContinuousLowLatency == continuous) return
        discoveryHandler.removeCallbacksAndMessages(null)
        streamDiscovery?.close()
        streamDiscovery = null
        Log.i("BluetoothVideo", "Source slot availability changed; refreshing Classic viewer discovery")
        startViewerRequestDiscovery()
    }

    private fun classicSourceConnectionPending(): Boolean =
        outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM &&
            broadcaster?.connectionAttemptsPending() == true

    private fun expireViewerRequests() {
        if (streamDiscovery == null || appScreen != AppScreen.CAMERA) return
        refreshPendingViewerPeers()
        discoveryHandler.postDelayed(::expireViewerRequests, VIEWER_EXPIRY_CHECK_MS)
    }

    /** [AM 18] a Wi-Fi-only row stays while the platform's last report of its key is inside the Wi-Fi window. */
    private fun freshWifiSighting(peer: BleL2capPeer): WifiSighting? {
        if (!peer.wifiOnly) return null
        val sighting = WifiDiscovery.latest(WifiSighting.keyOf(peer.announcement)) ?: return null
        return sighting.takeIf { SystemClock.elapsedRealtime() - it.seenMs <= WifiLimits.SIGHTING_STALE_MS }
    }

    private fun refreshPendingViewerPeers() {
        viewerPeersLastSeen.entries.toList().forEach { (key, value) ->
            if (freshWifiSighting(value.first) != null) viewerPeersLastSeen[key] = value.first to SystemClock.elapsedRealtime()
        }
        val cutoff = SystemClock.elapsedRealtime() - VIEWER_REQUEST_STALE_MS
        viewerPeersLastSeen.entries.removeAll { it.value.second < cutoff }
        pendingViewerPeers = viewerPeersLastSeen.values.map { it.first }.sortedBy(BleL2capPeer::label)
        if (broadcaster == null) updateConnectionDot(pendingViewerPeers.size)
        updateStartEnabled()
    }

    private val streamEpochName = "stream-presence-" + Integer.toHexString(System.identityHashCode(this))

    /**
     * Bluetooth ON recovery, off edge: the stream offer's advertising set (and title pages) died with the adapter. Close the
     * wrappers now (this also stops their restart watchdog from racing the rebuild); the broadcast stays armed.
     */
    private fun streamPresenceBluetoothOff() {
        if (isDestroyed || !BluetoothOnRecovery.enabled || !streamArmed || appScreen != AppScreen.CAMERA) return
        streamAdvertiser?.close()
        streamAdvertiser = null
        WifiDiscovery.offers.clear(WifiOfferRole.STREAM_OFFER)
        streamTitleAdvertiser?.close()
        streamTitleAdvertiser = null
        streamPresenceStarting = false
        setStatus(getString(R.string.live_status_bluetooth_off))
    }

    /**
     * Bluetooth ON recovery, on edge (registered after the scan registry, so the viewer-request discovery scanner is restarted
     * first): advertise the stream again through the normal start path, which also restarts viewer-request discovery. Viewers
     * that lost their link while Bluetooth was down find the offer again and request it; the encoder session was never stopped.
     */
    private fun streamPresenceBluetoothOn() {
        if (isDestroyed || !BluetoothOnRecovery.enabled) return
        val decision = BluetoothOnRecoveryPolicy.streamOffer(
            armed = streamArmed,
            cameraScreen = appScreen == AppScreen.CAMERA,
            broadcastDiscovery = usesBroadcastDiscovery(),
            permissions = hasRequiredPermissions(),
        )
        if (!decision.restart) {
            if (streamArmed) BluetoothOnRecovery.log(BluetoothOnRecovery.COMPONENT_STREAM_ADVERTISE, "skipped", decision.reason)
            return
        }
        streamAdvertiser?.close()
        streamAdvertiser = null
        WifiDiscovery.offers.clear(WifiOfferRole.STREAM_OFFER)
        streamTitleAdvertiser?.close()
        streamTitleAdvertiser = null
        streamPresenceStarting = false
        BluetoothOnRecovery.log(BluetoothOnRecovery.COMPONENT_STREAM_ADVERTISE, "restarted", decision.reason)
        setStatus(getString(R.string.live_status_bluetooth_back))
        startStreamPresence()
    }

    /**
     * Rotating IDs (RV6, RV12): a boundary (or a Visible-by-handle window change) stops the stream offer and
     * its title pages, waits 1-8 s and starts them on new sets with the new role-1 RDI. Connected viewers keep
     * their links; the token window keeps the previous token valid for 60 s.
     */
    private fun rotateStreamPresenceForBoundary() {
        if (streamAdvertiser == null || appScreen != AppScreen.CAMERA || !streamArmed) return
        RotatingDiscovery.offAir(RotatingDiscoveryIds.ROLE_STREAM_OFFER)
        streamAdvertiser?.close()
        streamAdvertiser = null
        WifiDiscovery.offers.clear(WifiOfferRole.STREAM_OFFER)
        streamTitleAdvertiser?.close()
        streamTitleAdvertiser = null
        streamPresenceStarting = false
        val silenceMs = RotatingDiscovery.boundarySilenceMs()
        discoveryHandler.postDelayed({ startStreamPresence() }, silenceMs)
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK rotid_restart: role=stream | silenceMs=$silenceMs")
    }

    private fun stopStreamPresence(resetToken: Boolean = true) {
        // Rotating IDs: the stream offer leaves the air; its ID stays openable for 60 s only (OpeningPins).
        RotatingDiscovery.offAir(RotatingDiscoveryIds.ROLE_STREAM_OFFER)
        if (streamBoundaryListening) {
            streamBoundaryListening = false
            RotatingDiscovery.removeBoundaryListener(streamBoundaryListener)
        }
        WifiDiscovery.setDemand(WifiDiscovery.Demand.VIEWER_REQUESTS, false)
        discoveryHandler.removeCallbacksAndMessages(null)
        streamDiscovery?.close()
        streamDiscovery = null
        streamAdvertiser?.close()
        streamAdvertiser = null
        WifiDiscovery.offers.clear(WifiOfferRole.STREAM_OFFER)
        streamTitleAdvertiser?.close()
        streamTitleAdvertiser = null
        streamPresenceStarting = false
        pendingViewerPeers = emptyList()
        viewerPeersLastSeen.clear()
        if (resetToken) {
            streamToken = 0
            streamCapacityGeneration = 0
            streamRouteToken = 0
            streamRequestTokens.clear()
        }
        if (::connectionStatusDot.isInitialized) updateConnectionDot(0)
    }

    private fun refreshStreamPresenceAdvertisement() {
        streamAdvertiser?.close()
        streamAdvertiser = null
        WifiDiscovery.offers.clear(WifiOfferRole.STREAM_OFFER)
        streamTitleAdvertiser?.close()
        streamTitleAdvertiser = null
        streamPresenceStarting = false
        startStreamPresence()
    }

    @SuppressLint("MissingPermission")
    private fun refreshNearbyStreams() {
        if (onboardingHolds()) return
        val adapter = bluetoothAdapter ?: return
        if (!isSeekingStreamRoute() || !hasRequiredPermissions()) return
        if (nearbyStreamDiscovery != null) return
        refreshNearbyStreamPeers()
        if (!isSeekingStreamRoute() || receiver != null) return
        lateinit var discovery: BleL2capDiscovery
        discovery = BleL2capDiscovery(
            adapter,
            { peer ->
                runOnUiThread {
                    if (nearbyStreamDiscovery !== discovery) return@runOnUiThread
                    if (!receiverFirstOfferLogged && receiverBrowserStartedAtNs > 0L) {
                        receiverFirstOfferLogged = true
                        Log.i(
                            "BluetoothVideo",
                            "Discovery startup: browser-to-first-offer %.1f ms".format(
                                Locale.US,
                                (System.nanoTime() - receiverBrowserStartedAtNs) / 1_000_000.0,
                            ),
                        )
                    }
                    val streamKey = peer.announcement.nodeIdPrefix.joinToString("") { "%02x".format(it) } +
                        ":${peer.announcement.transportOption().name}"
                    nearbyStreamsLastSeen[streamKey] = peer to SystemClock.elapsedRealtime()
                    refreshNearbyStreamPeers()
                }
            },
            {
                runOnUiThread {
                    if (nearbyStreamDiscovery !== discovery) return@runOnUiThread
                    nearbyStreamDiscovery = null
                    if (isSeekingStreamRoute()) {
                        discoveryHandler.postDelayed(::refreshNearbyStreams, DISCOVERY_RESTART_MS)
                    }
                }
            },
            { message ->
                runOnUiThread {
                    if (nearbyStreamDiscovery !== discovery) return@runOnUiThread
                    nearbyStreamDiscovery = null
                    if (appScreen == AppScreen.RECEIVER_BROWSER) receiverBrowserStatus.text = message
                    if (isSeekingStreamRoute()) {
                        discoveryHandler.postDelayed(
                            ::refreshNearbyStreams,
                            if (reconnectTarget != null) ROUTE_SCAN_ERROR_RETRY_MS else SCAN_ERROR_RETRY_MS,
                        )
                    }
                }
            },
            DiscoveryAnnouncement::isStreamOffer,
            scanDurationMs = CONTINUOUS_SCAN_MS,
            reportDuplicates = true,
            // F11 item 5 (Experimental, off): a reachable phone's list scan steps down to balanced after its 8 s burst,
            // leaving the radio free to answer a caller's connection request (DebugTunables.reachableScanYield).
            continuousLowLatency = !DebugTunables.reachableScanYield,
            acceptRotating = RotatingDiscoveryFeature.enabled,
            // A visible list: a scan the stack silently left without results is restarted (ScanStallWatch).
            stallRecovery = true,
        )
        nearbyStreamDiscovery = discovery
        discovery.start()
        WifiDiscovery.setDemand(WifiDiscovery.Demand.LIVE_LIST, true)
        discoveryHandler.postDelayed(::expireNearbyStreams, NEARBY_EXPIRY_CHECK_MS)
    }

    private fun restartNearbyStreams() {
        nearbyStreamsLastSeen.clear()
        nearbyStreamPeers = emptyList()
        updateNearbyStreamList()
        receiverBrowserStatus.text = getString(R.string.watch_scanning)
        if (nearbyStreamDiscovery == null) refreshNearbyStreams()
    }

    /** A refresh of the Watch list is queued for when the sealed report lists are read. */
    private var streamListWaitsForReports = false

    private fun refreshNearbyStreamPeers() {
        val nowMs = SystemClock.elapsedRealtime()
        nearbyStreamsLastSeen.entries.toList().forEach { (key, value) ->
            if (freshWifiSighting(value.first) != null) nearbyStreamsLastSeen[key] = value.first to nowMs
        }
        nearbyStreamsLastSeen.entries.removeAll {
            !streamOfferIsFresh(it.value.second, nowMs)
        }
        suppressedStreamRoutes.entries.removeAll { it.value <= nowMs }
        capacitySuppressedRouteGenerations.keys.retainAll(suppressedStreamRoutes.keys)
        nearbyStreamsLastSeen.values.forEach { (peer, _) ->
            val refreshed = capacitySuppressedRouteGenerations.filter { (route, generation) ->
                route.matches(peer.announcement) && streamCapacityGenerationAdvanced(generation, peer.announcement.capacityGeneration())
            }.keys
            refreshed.forEach { route ->
                capacitySuppressedRouteGenerations.remove(route)
                suppressedStreamRoutes.remove(route)
                Log.i("BluetoothVideo", "Source capacity changed; retrying the authenticated route instead of waiting for cooldown")
            }
        }
        val updatedPeers = nearbyStreamsLastSeen.values.map { it.first }
            .filterNot { peer ->
                suppressedStreamRoutes.keys.any { route -> route.matches(peer.announcement) }
            }
            .filter { !AudioLiveFeatures.enabled || it.announcement.streamOfferKind() != StreamOfferKind.MALFORMED } // AUDIO-LIVE hook
            .sortedBy(BleL2capPeer::label)
            .let { CallBlocking.hideBlockedStreams(applicationContext, it) }
            .filterNot { LocalReporting.hiddenStream(this, it.announcement, it.device.address) }
        // Re-review S3: a stream hidden in an earlier run shows until the sealed list is read; the list is redone then.
        if (!LocalReporting.isLoaded(this) && !streamListWaitsForReports) {
            streamListWaitsForReports = true
            LocalReporting.whenLoaded(this) {
                runOnUiThread {
                    streamListWaitsForReports = false
                    if (!isFinishing && !isDestroyed) refreshNearbyStreamPeers()
                }
            }
        }
        val listChanged = nearbyStreamPeers.size != updatedPeers.size ||
            nearbyStreamPeers.zip(updatedPeers).any { (old, new) ->
                old.announcement.streamToken != new.announcement.streamToken ||
                    old.announcement.flags != new.announcement.flags ||
                    !old.announcement.nodeIdPrefix.contentEquals(new.announcement.nodeIdPrefix)
            }
        nearbyStreamPeers = updatedPeers
        if (listChanged) updateNearbyStreamList()
        val activeNotice = receiverBrowserNotice?.takeIf {
            SystemClock.elapsedRealtime() <= receiverBrowserNoticeDeadlineMs
        }
        receiverBrowserStatus.text = activeNotice ?: if (nearbyStreamPeers.isEmpty()) {
            val coolingRetryMs = nearbyStreamsLastSeen.values.asSequence()
                .mapNotNull { (peer, _) ->
                    suppressedStreamRoutes.entries
                        .filter { (route, _) -> route.matches(peer.announcement) }
                        .minOfOrNull { (_, deadlineMs) -> deadlineMs - nowMs }
                }
                .minOrNull()
            receiverBrowserEmptyStatus(coolingRetryMs, DebugTunables.receiverUiStability)
        } else {
            "" // the list shows them; no count line
        }
        val pendingReconnect = reconnectTarget
        if (pendingReconnect != null) {
            val reconnectCandidates = withPreferredParentGrace(
                nearbyStreamPeers
                    .filter { pendingReconnect.matches(it.announcement) }
                    .filter { supportsAdvertisedCodec(it.announcement) },
            )
            reconnectCandidates.asSequence()
                .sortedWith(
                    compareBy<BleL2capPeer> {
                        establishmentRank(routeFailing(it), DebugTunables.relayEstablishFallback)
                    }.thenBy {
                        preferredRecoveryRank(it.announcement.nodeIdPrefix, preferredRecoveryParent)
                    }.thenBy {
                        streamRouteRank(it.announcement, reconnectPreferredTransport).first
                    }.thenBy {
                        streamRouteRank(it.announcement, reconnectPreferredTransport).second
                    }.thenBy {
                        streamRouteRank(it.announcement, reconnectPreferredTransport).third
                    },
                )
                .firstOrNull()
                ?.also { logEstablishmentFallback(reconnectCandidates, it) }
                ?.let { peer ->
                    if (appScreen == AppScreen.RECEIVER_BROWSER) {
                        receiverBrowserStatus.text = getString(R.string.watch_rejoining)
                    } else {
                        setStatus(getString(R.string.watch_rejoining))
                    }
                    connectToStream(peer)
                    return
                }
            if (appScreen == AppScreen.RECEIVER_BROWSER) {
                receiverBrowserStatus.text = getString(R.string.watch_connection_lost)
            }
        }
        if (pendingReconnect == null && receiver == null && appScreen == AppScreen.RECEIVER_BROWSER) {
            val due = streamJoinRetry.pending
            streamJoinRetry.take(nowMs, nearbyStreamPeers) { target, candidate ->
                target.matches(candidate.announcement) && supportsAdvertisedCodec(candidate.announcement) &&
                    !candidate.announcement.isFullStreamOffer()
            }?.let { peer ->
                Log.i(
                    "BluetoothVideo",
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                        "stream_join: retry | auto=${due?.auto} | waitedMs=${due?.let { nowMs - it.notBeforeMs }}",
                )
                connectToStream(peer)
                return
            }
        }
        if (debugAutoSelectStream && selectedStreamPeer == null && receiverLocalMediaFailure == null) {
            nearbyStreamPeers.firstOrNull {
                !it.announcement.isFullStreamOffer() &&
                it.announcement.routeCost >= debugMinimumRouteCost &&
                    (debugStreamNodePrefix == null ||
                        it.announcement.nodeIdPrefix.joinToString("") { byte -> "%02x".format(byte) } == debugStreamNodePrefix)
            }?.let(::connectToStream)
        }
    }

    private fun expireNearbyStreams() {
        if (nearbyStreamDiscovery == null || !isSeekingStreamRoute()) return
        refreshNearbyStreamPeers()
        discoveryHandler.postDelayed(::expireNearbyStreams, NEARBY_EXPIRY_CHECK_MS)
    }

    private fun isSeekingStreamRoute(): Boolean =
        appScreen == AppScreen.RECEIVER_BROWSER ||
            (appScreen == AppScreen.PLAYBACK && reconnectTarget != null)

    private fun updateNearbyStreamList() {
        if (!::streamList.isInitialized) return
        nearbyStreamRows = if (DebugTunables.streamRowGrouping) {
            groupStreamRoutes(
                nearbyStreamPeers,
                announcement = BleL2capPeer::announcement,
                joinable = { supportsAdvertisedCodec(it.announcement) && !it.announcement.isFullStreamOffer() },
                establishmentRank = {
                    establishmentRank(routeFailing(it), DebugTunables.relayEstablishFallback)
                },
            )
        } else {
            nearbyStreamPeers.map { StreamRouteGroup(listOf(it)) }
        }
        // A stranger's generated name, from the offer's current rotating ID (same derivation as the people rows).
        val generatedStreamNames = rotatingGeneratedNames(nearbyStreamPeers)
        val rows = nearbyStreamRows.map { group ->
            val peer = group.best
            // The broadcaster's own offer names the stream; a relay's identity is not the broadcaster's.
            val origin = group.routes.firstOrNull { it.announcement.routeCost == 0 }
                ?: peer.takeUnless { DebugTunables.streamRowGrouping }
            val relays = advertisedRelayCount(peer.announcement.routeCost) ?: 0
            val supported = supportsAdvertisedCodec(peer.announcement)
            val paused = peer.announcement.flags and DiscoveryAnnouncement.FLAG_STREAM_PAUSED != 0
            val full = peer.announcement.isFullStreamOffer()
            val isAudioRow = AudioLiveFeatures.enabled && peer.announcement.isAudioOnlyOffer() // AUDIO-LIVE hook
            val parts = buildList {
                if (isAudioRow) add(getString(R.string.audio_live_row_audio_only))
                add(
                    when (relays) {
                        0 -> getString(R.string.people_reach_nearby)
                        1 -> getString(R.string.people_reach_via_one)
                        else -> getString(R.string.people_reach_via, relays)
                    },
                )
                if (paused) add(getString(R.string.stream_paused))
                if (full) add(getString(R.string.stream_full))
                if (!supported) add(getString(R.string.stream_unsupported))
            }
            // Broadcaster: a title when one exists (unconfirmed until joined), else "Live from <name>".
            val prefix = origin?.announcement?.nodeIdPrefix?.toHexKey()
            val label = prefix?.let { PeerLabels.forPeer(it, contactNames[it]) }
            val advertisedTitle = if (PeerLabelFeature.titlePagesEnabled) {
                group.routes.firstNotNullOfOrNull { StreamTitles.board.title(it.announcement.streamToken) }
            } else {
                null
            }
            if (BuildConfig.DEBUG && PeerLabelFeature.titlePagesEnabled && loggedTitleLookups.add(peer.announcement.streamToken)) {
                Log.i("BluetoothVideo", "BENCHMARK title_lookup: tokens=${group.routes.joinToString("/") { "%08x".format(it.announcement.streamToken) }} | found=${advertisedTitle != null} | pages=${StreamTitles.pagesReceived}")
            }
            val signedTitle = label?.streamTitle?.takeIf { label.streamTitleConfirmed }
            val title = when {
                signedTitle != null -> getString(R.string.stream_row_title, signedTitle)
                advertisedTitle != null -> getString(R.string.stream_title_unconfirmed, advertisedTitle)
                origin == null || label == null ->
                    getString(if (isAudioRow) R.string.audio_live_row_from_further else R.string.stream_row_live_from_further)
                origin.announcement.rotating -> {
                    val hit = RotatingDiscovery.recognize(origin.announcement).singleOrNull()
                    val generated = generatedStreamNames[origin.announcement.nodeIdPrefix.toHexKey()]
                    if (hit == null && generated != null) {
                        // No title: "<generated name> is live".
                        getString(if (isAudioRow) R.string.audio_live_row_from_name else R.string.stream_row_name_live, generated)
                    } else if (hit == null) {
                        getString(if (isAudioRow) R.string.audio_live_row_nearby else R.string.stream_row_live_from_nearby)
                    } else {
                        val contact = hit.contact.toString().take(12)
                        getString(
                            if (isAudioRow) R.string.audio_live_row_from_seen else R.string.stream_row_live_from_seen,
                            PeerLabels.forPeer(contact, contactNames[contact]).listName(),
                        )
                    }
                }
                label.nameKind == PeerLabel.NameKind.NONE ->
                    getString(if (isAudioRow) R.string.audio_live_row_someone else R.string.stream_row_someone_live)
                else -> getString(
                    if (isAudioRow) R.string.audio_live_row_from_name else R.string.stream_row_name_live,
                    peerRowName(label),
                )
            }
            ListRow(
                title = title,
                subtitle = parts.joinToString(", "),
                // The page is "Live nearby": no LIVE badge per row; only a radio row has an icon (user request 2026-10-17).
                iconRes = if (isAudioRow) R.drawable.ic_radio else R.drawable.ic_videocam,
                iconTintRes = if (supported && !full) R.color.text_primary else R.color.text_secondary,
                enabled = supported && !full,
                live = false,
            )
        }
        nearbyStreamRowTitles = rows.map { it.title } // AUDIO-LIVE hook
        streamList.adapter = ListRowAdapter(this, rows)
    }

    private fun stopNearbyStreamDiscovery() {
        discoveryHandler.removeCallbacksAndMessages(null)
        nearbyStreamDiscovery?.close()
        nearbyStreamDiscovery = null
        WifiDiscovery.setDemand(WifiDiscovery.Demand.LIVE_LIST, false)
    }

    private fun showPrivateCallBrowser(restartDelayMs: Long = 0L) {
        stopStreaming("Stopped.")
        stopNearbyStreamDiscovery()
        stopIdleCameraPreview()
        appScreen = AppScreen.CALL_BROWSER
        sessionRole = SessionRole.SEND
        cameraScreen.visibility = View.GONE
        receiverBrowserScreen.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        callScreen.visibility = View.GONE
        callBrowserScreen.visibility = if (callBrowserContacts) View.GONE else View.VISIBLE
        callsTabScreen.visibility = View.GONE
        contactsTabScreen.visibility = if (callBrowserContacts) View.VISIBLE else View.GONE
        settingsBackCallback.isEnabled = true
        updateSystemBars(visible = true)
        if (restartDelayMs > 0L) {
            privateCallDiscoveryHandler.postDelayed(
                {
                    if (appScreen == AppScreen.CALL_BROWSER && privateCall == null) {
                        ensurePermissionsAndRefresh()
                    }
                },
                restartDelayMs,
            )
        } else {
            ensurePermissionsAndRefresh()
        }
    }

    private fun showPrivateCallScreen(message: String) {
        // F15: nothing may cover the call screen (an incoming call's Accept button or an outgoing call's Cancel).
        dismissOpenDialogs("call_screen")
        stopPrivateCallDiscovery()
        appScreen = AppScreen.CALL
        cameraScreen.visibility = View.GONE
        receiverBrowserScreen.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        callBrowserScreen.visibility = View.GONE
        callsTabScreen.visibility = View.GONE
        contactsTabScreen.visibility = View.GONE
        callScreen.visibility = View.VISIBLE
        privateCallRemoteVideoStarted = false
        privateCallRemoteFit.reset()
        privateCallRemoteShownMode = null
        callRemotePreview.alpha = 0f
        callScreenController.reset()
        callScreenController.setForeground(activityResumed)
        findViewById<android.widget.Chronometer>(R.id.callTopTimer).apply {
            stop()
            visibility = View.GONE
        }
        intercomCallUi.reset()
        showCallStatusText(message)
        callRemoteCameraOff.visibility = View.GONE
        callChromeAutoHide.reset()
        privateCallLinkDetails.clear()
        callSecurityChip.visibility = View.GONE
        hideCallParty()
        settingsBackCallback.isEnabled = true
        updateSystemBars(visible = false)
        callRemotePreview.post {
            callRemotePreview.surfaceTexture?.let { texture ->
                privateCallRemoteSurface?.release()
                privateCallRemoteSurface = Surface(texture)
            }
        }
    }

    @SuppressLint("MissingPermission")
    /** Logs an experimental CoC -> GATT choice: requested and effective are always both stated. */
    private fun logCocTransportChoice(event: String, choice: CocTransportChoice, detail: String? = null) {
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                "call transport: event=$event | ${choice.logLine()}" + (detail?.let { " | detail=$it" } ?: ""),
        )
    }

    /** Shows the visible "Using BLE GATT" notice for an automatic choice. */
    private fun showCocTransportNotice(choice: CocTransportChoice) {
        val notice = choice.notice ?: return
        Log.i("BluetoothVideo", "Call transport notice (not shown, user feedback 2026-10-21): $notice")
    }

    /**
     * [forcedChoice] is a one-call GATT choice for a GATT peer from an L2CAP session (experimental
     * CoC -> GATT fallback); otherwise the transport follows the flag, the CoC registration count
     * and earlier failures, which with the flag off is exactly the selected transport.
     */
    private fun startPrivateCallEndpoint(forcedChoice: CocTransportChoice? = null) {
        // First run: no advertising before onboarding (and its "Hide your phone's name" answer).
        if (onboardingHolds()) return
        val onCallScreen = appScreen == AppScreen.CALL_BROWSER
        // Always reachable (CallReachability): Home starts the same endpoint, without scanning.
        val homeStart = !onCallScreen && idleEndpointScreen() &&
            currentReachabilityPlan().endpoint
        if (onCallScreen && !hasRequiredPermissions()) return
        if (!onCallScreen && !homeStart) return
        if (onCallScreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && callAlertNeedsNotificationPermission(
                CallUx.settings,
                Build.VERSION.SDK_INT,
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
            ) && !callNotificationPermissionAsked
        ) {
            callNotificationPermissionAsked = true
            callNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val adapter = bluetoothAdapter ?: return
        if (privateCall == null) {
            val selectedCodec = codecs.getOrNull(codecSpinner.selectedItemPosition)
            if (selectedCodec == null) {
                callsSnackbar(getString(R.string.call_no_video_support))
                return
            }
            val transportChoice = forcedChoice
                ?: CocRegistrations.session.choose(outgoingTransport, CocAutoGatt.current)
            val endpointTransport = transportChoice.effective
            callTransportChoice = transportChoice
            callEndpointTransport = endpointTransport
            if (transportChoice.autoGattEnabled) {
                logCocTransportChoice("endpoint", transportChoice)
                if (transportChoice.automatic && forcedChoice == null) showCocTransportNotice(transportChoice)
            }
            val fingerprint = privateCallStackFingerprint()
            val codec = privateCallCodec(
                callRequestedCodec(selectedCodec),
                codecs,
                fingerprint,
                endpointTransport,
            )
            if (codec != selectedCodec) {
                Log.i(
                    "BluetoothVideo",
                    "Private-call compatibility selected ${codec.displayName} for the legacy Qualcomm stack",
                )
            }
            val callSettings = privateCallVideoSettings(
                autoCallRequestedSettings(codec, endpointTransport) ?: settings,
                endpointTransport,
                fingerprint,
                preserveSelectedBitrate = callBitrateAdapts() || DebugTunables.wifiExactKbps > 0,
                ignoreFamilyRateCap = DebugTunables.wifiExactKbps > 0,
            )
            if (BuildConfig.DEBUG) Log.i(
                "BluetoothVideo",
                "BENCHMARK call selected: encoder=${selectedCodec.encoderName} | " +
                    "width=${settings.resolution.width} | height=${settings.resolution.height} | " +
                    "fps=${settings.fps} | bitrateKbps=${settings.bitRate / 1_000} | " +
                    "playbackMs=${receiverPlaybackDelay.delayMs} | rateControl=${encoderRateControl.name} | " +
                    "thermalReduction=$thermalReductionOn (setting=$automaticThermalReductionEnabled)",
            )
            privateCallCodec = codec
            privateCallSettings = callSettings
            privateCallPhase = null
            lateinit var endpoint: AndroidPrivateVideoCall
            val callTransport = endpointTransport
            // Relayed calls: the new offer's helper bits come from the state now, not a snapshot from before this endpoint.
            relayedCalls?.endpointStarting(callTransport)
            endpoint = AndroidPrivateVideoCall(
                this,
                adapter,
                callTransport,
                codec,
                codecs,
                callSettings,
                outgoingVideoRotationDegrees(),
                remoteSurfaceProvider = {
                    // Null until the call view has a surface (a voice call answered from the lock screen has none):
                    // the receiver then waits for it instead of ending the call (VideoSurfaceWait).
                    privateCallRemoteSurface
                        ?: callRemotePreview.surfaceTexture?.let(::Surface)?.also {
                            privateCallRemoteSurface = it
                        }
                },
                playbackDelayProfile = receiverPlaybackDelay,
                relayHelper = relayedCalls,
                phyPreference = debugBlePhyPreference,
                ioConfig = streamIoConfigFor(outgoingTransport),
                supportedVideoModes = { activeCodec, requested ->
                    // GPU scale ladder: the call's rungs are GPU sizes in the request's aspect, so the
                    // negotiated size is not replaced by the nearest camera mode.
                    val gpuModes = if (gpuScaleOffReason(activeCodec, referenceFrames = false) == null) {
                        gpuScaleModes(activeCodec, requested.bitRate, cameraLens, requested.resolution, requested.fps)
                    } else {
                        emptyList()
                    }
                    gpuModes.ifEmpty { deviceVideoCapabilities.modesFor(activeCodec, requested.bitRate, cameraLens) }
                },
                debugTraceSessionName = debugMediaTraceSession,
                debugGattDeferConnectionPriority = debugGattDeferConnectionPriority,
                debugPipelineTiming = debugCallPipelineTiming,
                voiceCallsEnabled = IntercomFeatures.current.voiceCalls,
                onIncomingCallType = { type ->
                    runOnUiThread { if (privateCall === endpoint) onIncomingPrivateCallType(type) }
                },
                storeForwardNode = if (StoreForwardFeature.enabled) {
                    { StoreForwardRuntime.node(applicationContext) }
                } else {
                    null
                },
                // Messaging v2 (default off): whether this phone offers a chat session to the peer of an exchange.
                chatCapable = { peer, dialer ->
                    MessagingV2Feature.enabled && StoreForwardRuntime.chat(applicationContext)?.chatCapable(peer, dialer) == true
                },
                onMessageSync = { result ->
                    Log.i("BluetoothVideo", "Store-forward exchange: ${result.summary()}")
                    if (MessagingV2Feature.enabled) runOnUiThread {
                        // The chat controller keeps the retry state of every exchange, in either role.
                        StoreForwardRuntime.chatIfCreated()?.onExchangeResult(result)
                        chatDialPeer = null
                    }
                    if (StoreForwardAutoSyncFeature.enabled) runOnUiThread { onAutoSyncExchangeResult(result) }
                    val opened = result.opened.size
                    if (opened > 0) runOnUiThread {
                        Toast.makeText(this, getString(R.string.messages_toast_new, opened), Toast.LENGTH_LONG).show()
                        refreshMessagesUnread(notify = true)
                    }
                },
                onMessageSyncFailed = { detail ->
                    if (MessagingV2Feature.enabled) runOnUiThread {
                        chatDialPeer?.let { StoreForwardRuntime.chatIfCreated()?.onConnectFailed(it) }
                        chatDialPeer = null
                    }
                    if (StoreForwardAutoSyncFeature.enabled) runOnUiThread {
                        val scheduler = autoSyncScheduler
                        val key = scheduler?.inFlight()
                        if (scheduler != null && key != null) {
                            scheduler.onConnectFailed(key, SystemClock.elapsedRealtime())
                            logAutoSync("connect_failed", key, "detail=${detail.replace(' ', '_').take(80)}")
                        }
                    }
                },
                onStatus = { message ->
                    Log.i("BluetoothVideo", message)
                    runOnUiThread {
                        if (privateCall === endpoint) {
                            // Technical diagnostics stay in the log line above, not on screen.
                            // A carrier move reads "Now on Wi-Fi" / "Back on Bluetooth" on screen (UI-04); the log keeps the status.
                            val shown = when (WifiCallNotices.move(message)) {
                                WifiCallNotices.Move.NOW_ON_WIFI -> getString(R.string.call_now_on_wifi)
                                WifiCallNotices.Move.BACK_ON_BLUETOOTH -> getString(R.string.call_back_on_bluetooth)
                                null -> displayStatus(message)
                            }
                            if (shown == null) notePrivateCallLinkDetail(message)
                            if (shown != null) {
                                // Off the call screen nothing narrates the call (user feedback 2026-10-21).
                                if (appScreen == AppScreen.CALL) showCallStatusText(shown)
                            }
                        }
                    }
                },
                onPhase = { callState ->
                    val postedAtNs = System.nanoTime()
                    runOnUiThread {
                        // DEBUG setup timing: how long the phase waited for the UI thread (local media start runs there).
                        if (BuildConfig.DEBUG) logCallUiLag("phase_${callState.phase.name.lowercase()}", postedAtNs)
                        if (privateCall === endpoint) updatePrivateCallUi(callState)
                    }
                },
                onIncoming = { peerLabel ->
                    runOnUiThread {
                        if (privateCall !== endpoint) return@runOnUiThread
                        dismissOpenDialogs("incoming_call")
                        // F14: a call that rings while the user dials is from the person being dialled (any other was
                        // declined as busy): it is the conversation, so the user's own dial stops here.
                        currentOwnDial()?.let { dial ->
                            cancelDebugDialWait()
                            pendingPrivateCallPeerKey = null
                            dialSelectionStartedAtMs = 0L
                            ownDial = null
                            logCallInvite(DebugDialWait.line("cancelled", dial.advertKey, "reason" to "target_called_first"))
                        }
                        val interruptedScreen = reachabilityScreen()
                        privateCallReturnHome = interruptedScreen == ReachabilityScreen.HOME ||
                            interruptedScreen == ReachabilityScreen.LIVE_BROWSING
                        privateCallReturnLive = interruptedScreen == ReachabilityScreen.LIVE_BROWSING
                        // Browsing is interrupted cleanly: the stream scan stops before the call screen shows.
                        if (privateCallReturnLive) {
                            stopNearbyStreamDiscovery()
                            Log.i(
                                "BluetoothVideo",
                                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                                    "call_reachability: incoming call interrupts Live browsing",
                            )
                        }
                        // Q16-A: over a secure lock screen the caller stays unnamed until the phone is unlocked.
                        val shownName = callLockScreen.displayName(incomingPeerName(peerLabel))
                        showPrivateCallScreen("Incoming encrypted call from $shownName")
                        privateCallDialledRelays = null
                        showCallParty(shownName, R.string.call_incoming_video)
                        watchCallPeerName(peerLabel) { incomingPeerName(peerLabel) }
                        incomingCallControls.visibility = View.VISIBLE
                        activeCallControls.visibility = View.GONE
                        cancelCallButton.visibility = View.GONE
                        ActiveVideoSessionService.start(
                            this,
                            usesCamera = false,
                            statusText = "Incoming encrypted call. Tap to answer.",
                            peerName = incomingPeerName(peerLabel),
                        )
                        startPrivateCallRinging()
                        callLockScreen.showIncomingAlert(incomingPeerName(peerLabel))
                    }
                },
                onRemoteVideoFormat = { header ->
                    val postedAtNs = System.nanoTime()
                    runOnUiThread {
                        if (privateCall !== endpoint) return@runOnUiThread
                        if (BuildConfig.DEBUG) logCallUiLag("remote_header", postedAtNs)
                        if (BuildConfig.DEBUG) Log.i(
                            "BluetoothVideo",
                            "BENCHMARK call remote: codec=${header.codecId} | width=${header.width} | " +
                                "height=${header.height} | fps=${header.fps} | rotation=${header.rotationDegrees}",
                        )
                        privateCallSurfaceFormat = SurfaceVideoFormat(
                            header.width,
                            header.height,
                            header.rotationDegrees,
                            videoScaleMode,
                        )
                        privateCallRemoteVideoStarted = true
                        debugPrivateCallSurfacePending = BuildConfig.DEBUG
                        if (privateCallContinuityTracker == null) {
                            privateCallContinuityTracker = RenderedFrameContinuityTracker(
                                header.fps,
                                privateCallMediaStartedAtNs.takeIf { it > 0L } ?: System.nanoTime(),
                            )
                            privateCallContinuityTracker?.setActive(
                                activityResumed && callRemoteCameraOff.visibility != View.VISIBLE,
                            )
                        }
                        applyPrivateCallSurfaceTransform()
                    }
                },
                onRemoteCameraEnabled = { enabled ->
                    runOnUiThread {
                        if (privateCall === endpoint) {
                            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK call remote_camera enabled=$enabled")
                            callRemoteCameraOff.visibility = if (enabled) View.GONE else View.VISIBLE
                            callScreenController.setRemoteCameraEnabled(enabled)
                            if (enabled) debugPrivateCallSurfacePending = BuildConfig.DEBUG
                            privateCallContinuityTracker?.setActive(enabled && activityResumed)
                            if (intercomCallUi.onRemoteCamera(enabled)) {
                                onPrivateCallLayoutChanged(if (enabled) "remote_video_on" else "remote_video_off")
                            }
                        }
                    }
                },
                onRemoteMuted = { muted ->
                    runOnUiThread {
                        if (privateCall === endpoint) {
                            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK call remote_mute muted=$muted")
                            setCallStatus(if (muted) "Peer microphone muted" else "Peer microphone active")
                        }
                    }
                },
                onStartLocalMedia = { activeCallSettings, activeAudioBitRate, activeCallCodec, activePlayback ->
                    runOnUiThread {
                        if (privateCall === endpoint) {
                            privateCallSettings = activeCallSettings
                            privateCallActiveAudioBitRate = activeAudioBitRate
                            privateCallCodec = activeCallCodec
                            if (BuildConfig.DEBUG) Log.i(
                                "BluetoothVideo",
                                "BENCHMARK call effective: encoder=${activeCallCodec.encoderName} | " +
                                    "codec=${activeCallCodec.protocolId} | width=${activeCallSettings.resolution.width} | " +
                                    "height=${activeCallSettings.resolution.height} | fps=${activeCallSettings.fps} | " +
                                    "bitrateKbps=${activeCallSettings.bitRate / 1_000} | audioKbps=${activeAudioBitRate / 1_000} | " +
                                    "playbackMs=${activePlayback.delayMs} | rateControl=${encoderRateControl.name}",
                            )
                            val profileLabel = privateCallProfileLabel(
                                activeCallSettings,
                                activeAudioBitRate,
                                activeCallCodec,
                                activePlayback,
                            ) + if (debugRawInputFile != null) "\nAutomated motion test · recorded scene" else ""
                            // Hidden behind the info button; the details stay in the log.
                            Log.i("BluetoothVideo", "Call details: " + profileLabel.replace('\n', ' '))
                            callScreenController.setProfileText(profileLabel)
                            privateCallLinkRateLimitBps = null
                            privateCallLinkFps = null
                            privateCallRateController = if (encoderRateControl == EncoderRateControl.AUTOMATIC) {
                                Log.i(
                                    "BluetoothVideo",
                                    "Call rate adaptation armed (Automatic): requested ${activeCallSettings.bitRate / 1_000} kbps",
                                )
                                LinkRateController(
                                    activeCallSettings.bitRate,
                                    audioBps = activeAudioBitRate,
                                    // A first call with this peer starts at about half the measured link
                                    // (both directions share it), not at the full request; with the
                                    // learned start on, an unmeasured link starts at 400 kbps too.
                                    memory = autoCallMemory(callTransport, activeCallCodec, activeAudioBitRate, if (startRateGuardOn()) {
                                        LinkCapacity.sustainedRateMemory(callTransport, "call") {
                                            callUnknownPeerStartBps(callTransport, activeCallCodec, activeAudioBitRate)
                                        }
                                    } else {
                                        LinkCapacity.sustainedRateMemory(callTransport, "call", buildKeyed = true) {
                                            LinkCapacity.measuredLinkKbps(callTransport)?.let { kbps ->
                                                (kbps * 1_000 * CALL_LINK_SHARE).toInt() - activeAudioBitRate
                                            }
                                        }
                                    }),
                                    // Experimental bounded media age: the call's video age budget is congestion.
                                    congestedBacklogMs = if (DebugTunables.experimentalBoundedMediaAge) {
                                        BoundedMediaAge.callVideoQueueBudgetMs(
                                            0,
                                            BoundedMediaAge.ASSUMED_PEER_CALL_BASE_DELAY_MS,
                                        ).toInt()
                                    } else {
                                        LinkRateController.CONGESTED_BACKLOG_MS
                                    },
                                    frameRateLadder = autoCallFrameRateLadder(activeCallSettings, activeCallCodec) ?: FrameRateLadder(
                                        activeCallSettings.fps,
                                        maxOf(MIN_CALL_FPS, activeCallSettings.fps / 3),
                                        floorBps = if (DebugTunables.callLadderFromRequest) {
                                            null
                                        } else {
                                            FrameRateLadder.floorBpsFor(
                                                activeCallSettings.resolution.width,
                                                activeCallSettings.resolution.height,
                                                activeCallSettings.fps,
                                            )
                                        },
                                    ),
                                    deliveryProbing = DebugTunables.deliveryRateControl,
                                    rememberCuts = !DebugTunables.deliveryReports,
                                    smallClimbSteps = autoLadderActive(),
                                    deliveredEvidence = autoLadderActive(),
                                ).also { controller ->
                                    if (startRateGuardOn()) {
                                        // The learned start applies from the first frame (startPrivateCallMedia).
                                        val startBps = controller.seed(PRIVATE_CALL_RATE_LINK, endpoint.adjacentPeerLabel, System.nanoTime())
                                        privateCallLinkRateLimitBps = startBps.takeIf { it < activeCallSettings.bitRate }
                                        privateCallLinkFps = controller.frameRateFor(startBps).takeIf { it < activeCallSettings.fps }
                                        Log.i(
                                            "BluetoothVideo",
                                            "Call rate adaptation: starting at ${startBps / 1_000} of ${activeCallSettings.bitRate / 1_000} kbps " +
                                                "(learned or conservative start)",
                                        )
                                    }
                                }
                            } else {
                                null
                            }
                            privateCallFpsHysteresis = if (autoLadderActive()) FpsHysteresis() else null
                            privateCallClimbGuard = if (autoLadderActive()) ClimbGuard() else null
                            privateCallAudioFirstGate = if (autoLadderActive()) AudioFirstGate() else null
                            privateCallCameraPausedByLink = false
                            privateCallResolutionLadder = newPrivateCallResolutionLadder(activeCallSettings, activeCallCodec)
                            startPrivateCallMedia()
                        }
                    }
                },
                onLocalKeyFrameNeeded = { privateCallCameraEncoder?.requestKeyFrame() },
                onLinkSample = ::onPrivateCallLinkSample,
                onLinkStall = { poor ->
                    runOnUiThread {
                        if (privateCall !== endpoint) return@runOnUiThread
                        if (poor) {
                            showCallStatusText(getString(R.string.call_poor_connection))
                        } else if (privateCallPhase == PrivateCallPhase.ACTIVE) {
                            setCallStatus(ACTIVE_CALL_STATUS)
                        }
                    }
                },
                fitCallVideo = { requested, activeCallCodec ->
                    // Experimental call encoder fit (default off). Raw benchmark inputs fix the geometry.
                    if (!DebugTunables.callEncoderFit || debugRawInputFile != null) {
                        requested
                    } else {
                        val fitStore = CallEncoderFitPreferences(this)
                        CallEncoderFit.fitted(
                            requested,
                            deviceVideoCapabilities.modesFor(activeCallCodec, requested.bitRate, cameraLens),
                        ) { width, height, fps -> fitStore.isOverloaded(nativeEncoderSpeedKey(width, height, fps)) }
                    }
                },
                onPeerLabels = { prefix ->
                    // Experimental peer labels: the other person's verified name arrived (the callee's
                    // only with Accept, R1); refresh the shown name without changing the call state.
                    runOnUiThread {
                        if (privateCall !== endpoint || !::callPartyPanel.isInitialized) return@runOnUiThread
                        applyCallPeerName()
                    }
                },
                onCocConnectFailed = { detail ->
                    runOnUiThread {
                        if (privateCall !== endpoint) return@runOnUiThread
                        val retry = CocRegistrations.session.onCocFailure(
                            outgoingTransport, CocAutoGatt.current, CocFallbackReason.CONNECT_FAILED,
                        ) ?: return@runOnUiThread
                        cocRetryPeer = lastOutgoingCallPeer
                        logCocTransportChoice("connect_failed", retry, detail)
                    }
                },
                onEnded = { message ->
                    Log.i("BluetoothVideo", privateCallEndedLine(message, endpoint.benchmarkCallId))
                    CallsAvailabilityTracker.noteCallEnded(message)
                    runOnUiThread {
                        if (privateCall !== endpoint) return@runOnUiThread
                        if (endpoint.isQrScan) meetScanAttemptEnded() // a verification scan, not a call
                        if (endpoint.endedQuietly) {
                            handleBlockedCallEnded(endpoint) // call blocking: nothing to show
                            return@runOnUiThread
                        }
                        if (endpoint.endedFromRelayDuty) {
                            // This endpoint relayed someone else's call (or refused a request): no call
                            // screen, no toast; the endpoint is rebuilt quietly.
                            handleRelayDutyEnded(endpoint)
                            return@runOnUiThread
                        }
                        // Call audit 2026-11-10, CALL-02 (`test_call_quiet_pre_ring_failure`, off): an incoming link that
                        // failed before the phone rang is not a call the user saw. No phase reached the screen for it.
                        val idleScreen = (appScreen == AppScreen.CALL_BROWSER || idleEndpointScreen()) &&
                            currentOwnDial() == null && pendingPrivateCallPeerKey == null
                        if (PreRingFailure.presentation(
                                CallEndText.kind(message), privateCallPhase, idleScreen, DebugTunables.callQuietPreRingFailure,
                            ) == PreRingFailure.Presentation.QUIET
                        ) {
                            handleQuietPreRingFailure(
                                endpoint,
                                message,
                                if (callTransport == BluetoothTransportOption.BLE_GATT) GATT_CALL_RESTART_DELAY_MS else 0L,
                            )
                            return@runOnUiThread
                        }
                        recordEndedRecentCall(endpoint)
                        val returnHome = privateCallReturnHome && CallReachabilityFeature.enabled
                        val returnLive = returnHome && privateCallReturnLive && ReachableWhileBrowsingFeature.enabled
                        privateCallReturnHome = false
                        privateCallReturnLive = false
                        stopPrivateCallRinging()
                        callLockScreen.apply(null)
                        cleanupPrivateCallMedia(keepService = returnHome)
                        // Home reachability: hand the running service back to connectedDevice only
                        // instead of stopping it, so a call that ended in the background leaves the
                        // phone reachable (a background start after a stop would be refused).
                        if (returnHome) keepReachabilityServiceAfterCall()
                        endpoint.close()
                        privateCall = null
                        // Logged after the teardown, outside the log-loss window that follows the final reports.
                        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", privateCallEndedUiEvidence(message))
                        // UX fix 2026-10-21: an unanswered incoming call leaves a "Missed call from <name>" notification.
                        if (MissedCallNotification.isMissed(message)) {
                            MissedCallNotification.post(
                                this, callRealName?.invoke(), endpoint.endedPeerNodeIdHex?.take(12),
                                video = incomingCallType != CallType.VOICE,
                            )
                        }
                        incomingCallType = null
                        privateCallSettings = null
                        privateCallResolutionLadder = null
                        privateCallGpuScale = null
                        privateCallSentResolution = null
                        privateCallRateController = null
                        privateCallLinkShare = null
                        privateCallFpsHysteresis = null
                        privateCallClimbGuard = null
                        privateCallAudioFirstGate = null
                        privateCallCameraPausedByLink = false
                        privateCallLinkRateLimitBps = null
                        privateCallLinkFps = null
                        privateCallCodec = null
                        privateCallAvailable = false
                        if (returnLive && !isFinishing && !isDestroyed) {
                            // The call interrupted Live browsing: back to the Live tab (scan restarts),
                            // available again after the same GATT server pause as the Call screen. A call
                            // that ended in the background must not start a scan there: onResume does.
                            showReceiverBrowser(
                                askPermissions = activityResumed,
                                reachabilityDelayMs = if (callTransport == BluetoothTransportOption.BLE_GATT) {
                                    GATT_CALL_RESTART_DELAY_MS
                                } else {
                                    0L
                                },
                            )
                            showCallOutcome(message)
                        } else if (returnHome && !isFinishing && !isDestroyed) {
                            // The call came in through Home reachability: back to Home, available again
                            // after the same GATT server pause as the Call screen.
                            showCallsScreen(
                                stopActive = false,
                                reachabilityDelayMs = if (callTransport == BluetoothTransportOption.BLE_GATT) {
                                    GATT_CALL_RESTART_DELAY_MS
                                } else {
                                    0L
                                },
                            )
                            showCallOutcome(message)
                        } else if (!isFinishing && !isDestroyed) {
                            showPrivateCallBrowser(
                                if (callTransport == BluetoothTransportOption.BLE_GATT) {
                                    GATT_CALL_RESTART_DELAY_MS
                                } else {
                                    0L
                                },
                            )
                            val contactEnded = MessagingV2Feature.enabled && savedContacts.any {
                                it.nodeIdHex.equals(endpoint.endedPeerNodeIdHex ?: "", ignoreCase = true)
                            }
                            if (!CallEndText.isConnectFailure(message) && !contactEnded) showCallOutcome(message)
                            // UX fix 2026-10-21: a connect failure says who could not be reached and offers Call again;
                            // the technical detail stays in the log ("Private call ended: ...").
                            val failedPeer = lastOutgoingCallPeer
                            val endedContact = endpoint.endedPeerNodeIdHex?.takeIf { hex ->
                                MessagingV2Feature.enabled && savedContacts.any { it.nodeIdHex.equals(hex, ignoreCase = true) }
                            }
                            if (!CallEndText.isConnectFailure(message) && endedContact != null) {
                                // Chat entry point (2026-10-21): "Message" right after a call with a contact.
                                com.google.android.material.snackbar.Snackbar
                                    .make(callBrowserStatus, CallEndText.forUser(this, message), com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                                    .setAction(R.string.contact_message) { messageContact(endedContact, ChatEntry.Source.CALL_ENDED) }
                                    .show()
                            }
                            if (CallEndText.isConnectFailure(message) && failedPeer != null) {
                                val name = peerName(failedPeer.first)
                                com.google.android.material.snackbar.Snackbar
                                    .make(callBrowserStatus, getString(R.string.call_couldnt_reach, name), com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                                    .setAction(R.string.action_call_again) {
                                        callBack(failedPeer.first.announcement.nodeIdPrefix.toHexKey(), failedPeer.second == CallType.VIDEO, name)
                                    }
                                    .show()
                            }
                            // The CoC retry's transport notice is logged, not shown (user feedback 2026-10-21).
                        }
                        val endedCall = endpoint.endedRecentCall
                        if (!isFinishing && !isDestroyed && !isBenchmarkLaunch() &&
                            StrangerCallFollowup.offer(endedCall, contactNames.keys, DebugTunables.strangerCallFollowup)) {
                            val name = recentCallName(requireNotNull(endedCall))
                            com.google.android.material.snackbar.Snackbar
                                .make(findViewById(R.id.mainNav), getString(R.string.add_after_call, name), com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                                .setAction(R.string.action_add_contact_short) { openAddContact(name) }
                                .show()
                        }
                    }
                },
                onBlockedEndScheduled = {
                    runOnUiThread { if (privateCall === endpoint) callRinger.stopRingback() }
                },
            )
            privateCall = endpoint
            privateCallAvailabilityStarting = true
            val startToken = callEndpointStarts.begin()
            callsHealth.starting()
            scheduleCallsStallCheck()
            thread(name = "PrivateCallAvailability") {
                val result = runCatching { endpoint.startAvailability() }
                runOnUiThread {
                    // A stop (stream join, screen change) that overtook this start cancelled it: close, never publish.
                    // A viewer waiting for this start runs after the close.
                    if (!callEndpointStarts.finish(startToken, stillCurrent = privateCall === endpoint) { endpoint.close() }) {
                        return@runOnUiThread
                    }
                    // Work released by finish() (a viewer start) may have stopped this endpoint: publish nothing then.
                    if (privateCall !== endpoint) {
                        endpoint.close()
                        return@runOnUiThread
                    }
                    privateCallAvailabilityStarting = false
                    privateCallAvailable = result.isSuccess
                    result.onFailure { callsHealth.failed(callsUnavailableReason(it)) }.onSuccess { callsHealth.available() }
                    if (CocAvailabilitySwitches.callsUnavailableStatus) updateHomeReadiness()
                    result.onFailure { error ->
                        // The A15s stack stops accepting LE CoC listens after ~15 registrations per
                        // Bluetooth process (bmaraw-ab-call-9c121b30-*); this used to fail silently and
                        // the phone neither became callable nor scanned for others.
                        Log.w("BluetoothVideo", "Private call availability failed over ${callTransport.shortLabel}", error)
                        endpoint.close()
                        privateCall = null
                        // Experimental CoC -> GATT fallback (default off): retry this session over GATT.
                        val gattRetry = if (callTransport == BluetoothTransportOption.BLE_L2CAP) {
                            CocRegistrations.session.onCocFailure(
                                outgoingTransport, CocAutoGatt.current, CocFallbackReason.AVAILABILITY_FAILED,
                            )
                        } else {
                            null
                        }
                        if (gattRetry != null) {
                            logCocTransportChoice("availability_failed", gattRetry, error.message)
                            startPrivateCallEndpoint()
                            return@onFailure
                        }
                        setCallsNotice(
                            if (callTransport == BluetoothTransportOption.BLE_L2CAP) {
                                "Can't receive calls over L2CAP right now (${error.message}). " +
                                    "Switch to BLE GATT or restart Bluetooth."
                            } else {
                                "Private calls unavailable: ${error.message}"
                            },
                        )
                    }.onSuccess {
                        // The Classic "make visible" dialog belongs to the Call screen, never Home.
                        val homeEndpoint = idleEndpointScreen()
                        if (!homeEndpoint) requestClassicVisibilityIfNeeded(callTransport)
                        if (BuildConfig.DEBUG) Log.i(
                            "BluetoothVideo",
                            "BENCHMARK call available: node=" + AndroidNodeIdentityStore().getOrCreate().nodeId
                                .toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES).toHexKey(),
                        )
                        if (!homeEndpoint) {
                            ActiveVideoSessionService.start(
                                this,
                                usesCamera = false,
                                statusText = "Available for private Bluetooth calls",
                            )
                        } else {
                            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_reachability: available on ${reachabilityScreen().name}")
                            // Holds the connectedDevice service while visible; started in the
                            // background without that service, the endpoint stops again.
                            refreshCallReachability()
                        }
                        val rebuildPeer = cocRebuildPeer
                        cocRebuildPeer = null
                        if (rebuildPeer != null) {
                            // The GATT peer chosen from an L2CAP session: the endpoint now uses GATT.
                            pendingPrivateCallType = rebuildPeer.second
                            connectOutgoingPrivateCall(rebuildPeer.first)
                            return@onSuccess
                        }
                        restartPrivateCallDiscovery()
                        val retryPeer = cocRetryPeer
                        cocRetryPeer = null
                        if (retryPeer != null) startOutgoingPrivateCall(retryPeer.first, retryPeer.second)
                    }
                }
            }
            return
        }
        if (privateCallAvailabilityStarting) return
        // An endpoint kept from Home reachability: stopStreaming stopped its service.
        if (onCallScreen && privateCallAvailable) ensureReachabilityService()
        restartPrivateCallDiscovery()
    }

    private fun restartPrivateCallDiscovery() {
        if (appScreen != AppScreen.CALL_BROWSER || !hasRequiredPermissions()) return
        stopPrivateCallDiscovery()
        // Searching shows as the small indicator, never as text (user feedback 2026-10-21).
        callsActionableNotice = null
        if (privateCallPeers.isEmpty()) callsSearchStartedMs = SystemClock.elapsedRealtime()
        callsIndicatorStartedMs = SystemClock.elapsedRealtime()
        val adapter = bluetoothAdapter ?: return
        val localPrefix = AndroidNodeIdentityStore().getOrCreate().nodeId.toByteArray()
            .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
        callListLocalPrefix = localPrefix
        lateinit var discovery: BleL2capDiscovery
        discovery = BleL2capDiscovery(
            adapter,
            { peer ->
                runOnUiThread {
                    if (privateCallDiscovery !== discovery) return@runOnUiThread
                    val key = peer.announcement.nodeIdPrefix.toHexKey()
                    if (BuildConfig.DEBUG && key !in privateCallPeersLastSeen) Log.i(
                        "BluetoothVideo",
                        "BENCHMARK call discovered: node=$key | transport=${peer.announcement.transportOption().name}",
                    )
                    if (BuildConfig.DEBUG && key !in privateCallPeersLastSeen) Log.i(
                        "BluetoothVideo",
                        "BENCHMARK dual_listening: event=seen | node=$key | hint=${DualListeningWire.hasHint(peer.announcement)} | " +
                            "summary=0x${"%02x".format(peer.announcement.capabilitySummary)}",
                    )
                    val observedAtMs = SystemClock.elapsedRealtime()
                    if (peer.announcement.rotating) {
                        copiedIds.observe(key, peer.device.address, peer.announcement.routeCost, observedAtMs, peer.twinForm)
                    }
                    writeCallRow(key, callSightings.observeBle(key, peer, observedAtMs))
                    callPeerCadence.observe(key, observedAtMs)
                    if (dialFailureNoticeKey == key) dialFailureNotice = null // the person is nearby after all
                    refreshPrivateCallPeers()
                    // One phone, one row: a rotating ID announced from the address another rotating row used is that phone's
                    // next epoch (the stack kept the address); the old row goes instead of staying tappable until it expires.
                    if (DialPersonMatch.enabled && peer.announcement.rotating) {
                        val stale = privateCallPeersLastSeen.entries.firstOrNull { (otherKey, seen) ->
                            otherKey != key && !seen.first.wifiOnly && seen.first.announcement.rotating &&
                                seen.first.device.address.equals(peer.device.address, ignoreCase = true)
                        }?.key
                        if (stale != null && stale != pendingPrivateCallPeerKey) {
                            privateCallPeersLastSeen.remove(stale)
                            callPeerCadence.forget(stale)
                            callSightings.forget(stale)
                            refreshPrivateCallPeers()
                        }
                    }
                    val waitingDial = debugDialWait
                    // A pending user dial follows its person across a rotating-ID epoch (DialPersonMatch): the same key, the
                    // same recognised contact, or the same advertising address.
                    val dialledKey = pendingPrivateCallPeerKey
                    val dialMatch = if (dialledKey == null) null else DialPersonMatch.matches(
                        pendingPrivateCallTarget?.takeIf { it.key == dialledKey } ?: DialPersonMatch.Target(dialledKey, null, null),
                        key,
                        if (peer.announcement.rotating) recognisedContactPrefix(peer) else null,
                        peer.device.address,
                    )
                    if (waitingDial != null && labVisible(peer) &&
                        waitingDial.matchesPerson(key, if (waitingDial.contact) recognisedContactPrefix(peer) else null)
                    ) {
                        resumeDebugDialWait(waitingDial, peer)
                    } else if (
                        dialMatch != null &&
                        DialRefreshGate.accepts(pendingPrivateCallPeerVersion, pendingPrivateCallRouteCost, peer.announcement) &&
                        privateCallPeerWasRefreshed(observedAtMs, pendingPrivateCallSelectedAtMs)
                    ) {
                        pendingPrivateCallPeerKey = null
                        pendingPrivateCallTarget = null
                        if (dialMatch != DialPersonMatch.By.KEY && dialledKey != null) {
                            // The same person under the next epoch's ID: the busy rule (F14) and the logs follow the new key.
                            ownDial = ownDial?.copy(advertKey = key)
                            privateCallPeersLastSeen.remove(dialledKey)
                            callPeerCadence.forget(dialledKey)
                            callSightings.forget(dialledKey)
                        }
                        if (dialSelectionStartedAtMs != 0L) {
                            logCallInvite(
                                DebugDialWait.line(
                                    "peer_seen", key, "waitedMs" to (observedAtMs - dialSelectionStartedAtMs),
                                    "matchedBy" to dialMatch.name.lowercase(), "dialled" to dialledKey,
                                ),
                            )
                            dialSelectionStartedAtMs = 0L
                        }
                        connectOutgoingPrivateCall(peer)
                    } else {
                        if (StoreForwardAutoSyncFeature.enabled) maybeAutoSync(peer, observedAtMs)
                        if (MessagingV2Feature.enabled && MessagingV2ChatFeature.enabled) maybeChatSession(peer, observedAtMs)
                    }
                }
            },
            {
                runOnUiThread {
                    if (privateCallDiscovery !== discovery) return@runOnUiThread
                    privateCallDiscovery = null
                    if (appScreen == AppScreen.CALL_BROWSER) {
                        privateCallDiscoveryHandler.postDelayed(
                            ::restartPrivateCallDiscovery,
                            DISCOVERY_RESTART_MS,
                        )
                    }
                }
            },
            { message ->
                runOnUiThread {
                    if (privateCallDiscovery !== discovery) return@runOnUiThread
                    privateCallDiscovery = null
                    setCallsNotice(message)
                    privateCallDiscoveryHandler.postDelayed(
                        ::restartPrivateCallDiscovery,
                        SCAN_ERROR_RETRY_MS,
                    )
                }
            },
            acceptAnnouncement = { announcement ->
                callListAccepts(announcement, localPrefix)
            },
            scanDurationMs = CONTINUOUS_SCAN_MS,
            reportDuplicates = true,
            // F11 item 5 (Experimental, off): a reachable phone's list scan steps down to balanced after its 8 s burst,
            // leaving the radio free to answer a caller's connection request (DebugTunables.reachableScanYield).
            continuousLowLatency = !DebugTunables.reachableScanYield,
            acceptRotating = RotatingDiscoveryFeature.enabled,
            // A visible list: a scan the stack silently left without results is restarted (ScanStallWatch).
            stallRecovery = true,
            // F11 follow-up (Experimental, off): no offloaded filter for the visible call list (A/B on the Redmi 12).
            softwareFilter = DebugTunables.callListSoftwareFilter,
        )
        privateCallDiscovery = discovery
        discovery.start()
        WifiDiscovery.setDemand(WifiDiscovery.Demand.CALLS_LIST, true)
        privateCallDiscoveryHandler.postDelayed(::expirePrivateCallPeers, NEARBY_EXPIRY_CHECK_MS)
    }

    /** The call list's acceptance rule, shared by the Bluetooth scanner and the Wi-Fi call-offer path. [AM 22] */
    private fun callListAccepts(announcement: DiscoveryAnnouncement, localPrefix: ByteArray): Boolean =
        announcement.isPrivateCallOffer() &&
            // No relay of this app advertises a longer route (PrivateCallRelayChain.MAX_RELAYS).
            PrivateCallRelayChain.callerRelayHops(announcement.routeCost) != null &&
            // Rotating IDs, phase 1: only direct LE version 5 offers can be verified (startOutgoing).
            (!announcement.rotating || (announcement.routeCost == 0 &&
                announcement.flags and DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT == 0)) &&
            (CocAutoGattSession.acceptsAdvertised(
                outgoingTransport, currentCallTransport, announcement.transportOption(), CocAutoGatt.current,
            ) || DualCallerPlan.listsHintedOffer(currentCallTransport, announcement, DualListeningFeature.enabled)) &&
            !announcement.nodeIdPrefix.contentEquals(localPrefix)

    /** Rebuilds the displayed peer of [key] from its legs and stores it (the single place that writes privateCallPeersLastSeen for sightings). */
    private fun writeCallRow(key: String, entry: PeerSightings.Entry<BleL2capPeer, WifiSighting>) {
        val ble = entry.ble
        val shown = when {
            ble != null -> WifiOnlyPeer.merged(ble, entry.wifi)
            entry.wifi != null -> WifiOnlyPeer.of(bluetoothAdapter ?: return, entry.wifi)
            else -> {
                privateCallPeersLastSeen.remove(key)
                return
            }
        }
        privateCallPeersLastSeen[key] = shown to entry.seenMs
    }

    private val wifiListener = object : WifiDiscovery.Listener {
        override fun onWifiSighting(s: WifiSighting) = this@MainActivity.onWifiSighting(s)
        override fun onWifiLost(key: String?) = this@MainActivity.onWifiLost(key)
    }

    private fun onWifiSighting(s: WifiSighting) {
        runOnUiThread {
            val a = s.announcement
            when {
                a.isStreamOffer() -> onWifiStreamOffer(s)
                a.isPrivateCallOffer() -> onWifiCallOffer(s)
                else -> onWifiViewerRequest(s)
            }
        }
    }

    private fun onWifiCallOffer(s: WifiSighting) {
        val a = s.announcement
        // Only the Calls list shows these; a direct offer only; the same acceptance as a Bluetooth row; never our own ID.
        if (appScreen != AppScreen.CALL_BROWSER || a.routeCost != 0 || !callListAccepts(a, callListLocalPrefix ?: return)) return   // [AM 22]
        if (RotatingDiscovery.ownRdis(RotatingDiscoveryIds.ROLE_CALL_OFFER).any { it.contentEquals(a.nodeIdPrefix) }) return
        val now = SystemClock.elapsedRealtime()
        val entry = callSightings.observeWifi(s.key, s, now)
        if (BuildConfig.DEBUG && entry.ble == null && privateCallPeersLastSeen[s.key] == null) Log.i(
            "BluetoothVideo",
            "BENCHMARK call discovered: node=${s.key} | transport=${a.transportOption().name} | radio=wifi",
        )
        writeCallRow(s.key, entry)
        refreshPrivateCallPeers()
    }

    private fun onWifiLost(key: String?) {
        runOnUiThread {
            val changes = if (key == null) callSightings.dropAllWifi() else listOfNotNull(callSightings.dropWifi(key))
            changes.forEach { change ->
                val entry = callSightings.entry(change.key)
                if (entry != null) writeCallRow(change.key, entry) else privateCallPeersLastSeen.remove(change.key)
            }
            refreshPrivateCallPeers()
            onWifiLostFromLists(key)
        }
    }

    /** [AM 18] a lost Wi-Fi leg drops the Wi-Fi-only rows it stood for (key null: every Wi-Fi-only row). */
    private fun onWifiLostFromLists(key: String?) {
        nearbyStreamsLastSeen.entries.removeAll { (streamKey, value) ->
            value.first.wifiOnly && (key == null || streamKey == key || streamKey.startsWith("$key:"))
        }
        viewerPeersLastSeen.entries.removeAll { (entryKey, value) ->
            value.first.wifiOnly && (key == null || entryKey == "wifi:$key")
        }
        refreshNearbyStreamPeers()
        refreshPendingViewerPeers()
    }

    private fun onWifiStreamOffer(s: WifiSighting) {
        val a = s.announcement
        // Only a source (route cost 0): a forwarder is Bluetooth-only in this plan (issue 5), a listed dead row would only time out.
        if (a.routeCost != 0 || (nearbyStreamDiscovery == null && !isSeekingStreamRoute())) return
        val adapter = bluetoothAdapter ?: return
        val streamKey = s.key + ":" + a.transportOption().name
        val existing = nearbyStreamsLastSeen[streamKey]?.first
        val peer = existing?.takeIf { !it.wifiOnly }?.copy(wifi = s) ?: WifiOnlyPeer.of(adapter, s)
        nearbyStreamsLastSeen[streamKey] = peer to SystemClock.elapsedRealtime()
        refreshNearbyStreamPeers()
    }

    private fun onWifiViewerRequest(s: WifiSighting) {
        val a = s.announcement
        if (appScreen != AppScreen.CAMERA || streamToken == 0 || a.routeCost != 0 || !viewerRequestAccepts(a)) return
        val adapter = bluetoothAdapter ?: return
        // [AM 17] one person, one entry: a Bluetooth entry of the same prefix takes the Wi-Fi leg; only otherwise a "wifi:" entry
        val ble = viewerPeersLastSeen.entries.firstOrNull {
            !it.value.first.wifiOnly && it.value.first.announcement.nodeIdPrefix.contentEquals(a.nodeIdPrefix)
        }
        if (ble != null) viewerPeersLastSeen[ble.key] = ble.value.first.copy(wifi = s) to SystemClock.elapsedRealtime()
        else viewerPeersLastSeen["wifi:" + s.key] = WifiOnlyPeer.of(adapter, s) to SystemClock.elapsedRealtime()
        refreshPendingViewerPeers()
        WifiDiscovery.noteDialIntent()   // keeps the subscribe session alive until the source's dial ends [BUG]
        if (streamArmed) startRequestedBroadcast()
    }

    /** The viewer-request acceptance of the Bluetooth scanner, shared with the Wi-Fi path (same body). */
    private fun viewerRequestAccepts(announcement: DiscoveryAnnouncement): Boolean =
        announcement.flags and DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS != 0 &&
            streamRequestTokens.accepts(announcement.streamToken, SystemClock.elapsedRealtime()) &&
            // Design SG stage 2 (Experimental, off): an LE CoC source also takes GATT viewer requests into its
            // separate GATT child pool. Off, this is exactly the old equality.
            StreamGattFallback.acceptsViewerRequest(announcement.transportOption(), outgoingTransport, StreamGattFallback.active())

    private fun refreshPrivateCallPeers() {
        val nowMs = SystemClock.elapsedRealtime()
        if (WifiDiscovery.widerReachOn()) {
            callSightings.expire(nowMs).forEach { change ->
                val entry = callSightings.entry(change.key)
                if (entry != null) {
                    writeCallRow(change.key, entry)          // one radio is left: the row is rebuilt from it
                } else {
                    privateCallPeersLastSeen.remove(change.key)
                    callPeerCadence.forget(change.key)
                }
                if (PeerSightings.Leg.WIFI in change.lost) WifiLog.emit(WifiLog.discovery("lost", "reason" to "stale"))
                // [AM 23] the same DEBUG line a Bluetooth-cadence expiry prints, for a row that lost its last leg here
                if (PeerSightings.Leg.BLE in change.lost && change.remaining.isEmpty()) {
                    privateCallPeersLastSeen[change.key]?.let { shown ->
                        if (BuildConfig.DEBUG) Log.i(
                            "BluetoothVideo",
                            "BENCHMARK call_row_expired: node=${change.key} | unheardMs=${nowMs - shown.second} | " +
                                "windowMs=${callPeerCadence.staleAfterMs(change.key)}",
                        )
                    }
                }
            }
        }
        privateCallPeersLastSeen.entries.removeAll {
            val stale = !it.value.first.wifiOnly && !callPeerCadence.isFresh(it.key, it.value.second, nowMs)
            if (stale) {
                if (BuildConfig.DEBUG) Log.i(
                    "BluetoothVideo",
                    "BENCHMARK call_row_expired: node=${it.key} | unheardMs=${nowMs - it.value.second} | " +
                        "windowMs=${callPeerCadence.staleAfterMs(it.key)}",
                )
                callPeerCadence.forget(it.key)
                callSightings.forget(it.key)
            }
            stale
        }
        val directPeers = privateCallPeersLastSeen.values.map { it.first }
        // Experimental relayed calls: targets known only through helpers get a row of their own (a benchmark
        // that requires a chain replaces the direct row, since a direct call would be a failed chain).
        val relayedPeers = if (RelayedCallsFeature.enabled) RelayedCallsRuntime.relayedPeers(currentCallTransport) else emptyList()
        val relayedKeys = relayedPeers.map { it.announcement.nodeIdPrefix.toHexKey() }.toSet()
        val basePeers = if (RelayedCallsFeature.requireRelays > 0) {
            directPeers.filterNot { it.announcement.nodeIdPrefix.toHexKey() in relayedKeys }
        } else {
            directPeers
        }
        val extraPeers = relayedPeers.filterNot { relayed ->
            RelayedCallsFeature.requireRelays == 0 &&
                directPeers.any { it.announcement.nodeIdPrefix.contentEquals(relayed.announcement.nodeIdPrefix) }
        }
        val updatedPeers = (basePeers + extraPeers).sortedBy(BleL2capPeer::label).filterNot { peer ->
            val prefix = recognisedContactPrefix(peer) ?: peer.announcement.nodeIdPrefix.toHexKey().takeIf { !peer.announcement.rotating }
            prefix != null && LocalReporting.hidden(this, prefix)
        }
        val listChanged = privateCallPeers.size != updatedPeers.size ||
            privateCallPeers.zip(updatedPeers).any { (old, new) ->
                old.label != new.label ||
                    old.device.address != new.device.address ||
                    !old.announcement.nodeIdPrefix.contentEquals(new.announcement.nodeIdPrefix)
            }
        privateCallPeers = updatedPeers
        if (listChanged) updatePrivateCallPeerList()
        completePendingCallBack()
        completeContactDialWaitFromRoutes()
        // Only an actionable state stays on the line; the list, the indicator and the empty state say the rest.
        val statusWithNotice = CallsQuiet.line(callsActionableNotice)
        if (callBrowserStatus.text.toString() != statusWithNotice) callBrowserStatus.text = statusWithNotice
        updateNearbySelfLine()
        updateCallsSearching()
    }

    /**
     * Calls > Nearby only: "You appear as Gold Fox · changes every 15 min" (OwnNearbyName). Refreshed with the list, whose
     * expiry tick runs every second, so it follows the rotating-ID boundary.
     */
    private fun updateNearbySelfLine() {
        val line = findViewById<TextView>(R.id.nearbySelfLine) ?: return
        val shown = if (callBrowserContacts || callBrowserChats) null else OwnNearbyName.current(this)
        line.visibility = if (shown == null) View.GONE else View.VISIBLE
        if (shown == null) return
        line.setOnClickListener { SettingsInfo.show(this, R.string.appear_as_info_title, R.string.appear_as_info) }
        // One line (user feedback 2026-10-21): "You appear as <name>" and the (i); a long name is shortened, never wrapped.
        val width = line.width - line.paddingStart - line.paddingEnd
        if (width <= 0) {
            line.post { if (!isDestroyed) updateNearbySelfLine() }
            return
        }
        val markRoom = 30 * resources.displayMetrics.density
        val room = width - line.paint.measureText(getString(R.string.appear_as, "")) - markRoom
        val name = android.text.TextUtils.ellipsize(shown.name, line.paint, room.coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END)
        val text = getString(R.string.appear_as, name)
        if (line.text.toString().trimEnd('\u00A0') != text) line.text = text
    }

    private fun expirePrivateCallPeers() {
        if (appScreen != AppScreen.CALL_BROWSER || privateCallDiscovery == null) return
        refreshPrivateCallPeers()
        privateCallDiscoveryHandler.postDelayed(::expirePrivateCallPeers, NEARBY_EXPIRY_CHECK_MS)
    }

    /** People nearby as a call list: Contacts (saved when verified) first, then Nearby. */
    private fun updatePrivateCallPeerList() {
        if (!::callPeerList.isInitialized) return
        val voice = IntercomFeatures.current.voiceCalls
        val colliding = PeerLabels.collidingShortHandles(
            privateCallPeers.filterNot { it.announcement.rotating }.map { it.announcement.nodeIdPrefix.toHexKey() },
        )
        val generatedNames = rotatingGeneratedNames(privateCallPeers)
        // A person reached only through other phones is listed when they are a saved contact (Contacts calls search
        // every route on tap); strangers several phones away stay off the list unless the earlier arm is on.
        val listed = privateCallPeers.filter { peer ->
            peer.relayRoute == null || RelayedCallsFeature.showsRoutedRow(
                peer.announcement.nodeIdPrefix.toHexKey() in contactNames || recognisedContactPrefix(peer) in contactNames,
            )
        }
        val people = listed.map { peer ->
            val prefix = peer.announcement.nodeIdPrefix.toHexKey()
            val relays = advertisedRelayCount(peer.announcement.routeCost) ?: 0
            PeopleItem.Person(
                key = prefix,
                // Rotating IDs (design 5.4): a stranger's 15-minute ID is never shown as a handle or
                // matched to a contact before the handshake ("Nearby phone", no handle, no check mark).
                name = if (peer.announcement.rotating) {
                    rotatingPeerRowName(peer.announcement, generatedNames[prefix])
                } else {
                    peerRowName(PeerLabels.forPeer(prefix, contactNames[prefix]))
                },
                warning = nearbyRowWarning(peer, prefix, generatedNames[prefix]),
                reach = when {
                    peer.wifiOnly -> getString(R.string.people_reach_wifi)
                    // Found through helpers, not heard by this phone: said plainly, never as verified.
                    peer.relayRoute != null && relays == 1 -> getString(R.string.people_reach_relayed_one)
                    peer.relayRoute != null -> getString(R.string.people_reach_relayed, relays)
                    relays == 0 -> getString(R.string.people_reach_nearby)
                    relays == 1 -> getString(R.string.people_reach_via_one)
                    else -> getString(R.string.people_reach_via, relays)
                },
                relayed = relays > 0,
                avatarColor = identityColor(prefix),
                showVoice = voice,
                isContact = prefix in contactNames || (DebugTunables.nearbyListUx && recognisedContactPrefix(peer) in contactNames),
            )
        }
        updateContactsList()
        findViewById<View>(R.id.nearbyPeopleSearch).visibility =
            if (DebugTunables.nearbyListUx && nearbyPeopleSession.showSearch(people, nearbySearchQuery)) View.VISIBLE else View.GONE
        peopleAdapter.submit(
            if (DebugTunables.nearbyListUx) nearbyPeopleSession.rows(people, people.filter { it.isContact }.mapTo(HashSet()) { it.key }, nearbySearchQuery)
            else nearbyRows(people, contactNames.keys),
        )
    }

    /**
     * The name in a list row: a contact's own name, a self-claimed name in quotes, otherwise "Someone
     * nearby". IDs and handles are not shown in lists; the full ID is on the details sheet (long press).
     */
    /**
     * One name for a row, from the single precedence function ([DisplayIdentityResolver], Naming v2). Your label for a
     * contact, the generated name of a rotating-ID row, "~Name" for a name the person gave themselves (the tilde is the
     * claim mark; the reserved-form text rules keep names free of it), else "Someone nearby".
     */
    private fun peerRowName(label: PeerLabel, generatedName: String? = null): String {
        val display = label.display(generatedName = generatedName)
        return when (display.rank) {
            DisplayRank.SELF_CLAIMED -> "~" + display.title
            DisplayRank.HANDLE -> getString(R.string.people_someone_nearby)
            else -> display.title
        }
    }

    /** Generated names of the rotating rows now in range, one deterministic label per row (collisions get digits). */
    private fun rotatingGeneratedNames(peers: List<BleL2capPeer>): Map<String, String> {
        if (!NamingFeatures.generatedNamesOn()) return emptyMap()
        val rows = LinkedHashMap<String, GeneratedName>()
        peers.forEach { peer ->
            val ad = peer.announcement
            if (!ad.rotating) return@forEach
            val key = ad.nodeIdPrefix.toHexKey()
            if (key !in rows) ad.rotatingId()?.let { id -> StrangerNames.derive(StrangerNames.Input.rdi(id))?.let { rows[key] = it } }
            // A window offer's claimed name (Visible by name) is shown as "~Name" beside the generated name, never instead of it.
        }
        return GeneratedNameRows.labels(rows)
    }

    /**
     * A neutral warning for a nearby row (Naming v2): the ID is on two phones at once, or a name shown beside a window ID
     * imitates one of your contacts. Before any connection nothing can be checked, so the wording never accuses.
     */
    private fun nearbyRowWarning(peer: BleL2capPeer, key: String, generatedName: String?): String? {
        val ad = peer.announcement
        if (!ad.rotating) return null
        if (copiedIds.warned(key)) return getString(R.string.people_copied_id)
        val claimed = if (ad.isWindowOffer) NameWindowBoard.claimedName(ad) else null
        return claimed?.let {
            ImitationCheck.evaluate(
                claimed = it,
                contacts = contactNames.values.map { label -> ImitationCheck.ContactNames(label) },
                ownName = PeerLabelFeature.localName,
                generatedNames = listOfNotNull(generatedName),
                otherVisibleClaims = emptyList(),
                afterReveal = false,
            )
        }?.let(DisplayIdentityText::warningText)
    }

    /** The row's display for the peer of an incoming call (the throwaway's generated name while it is still a stranger). */
    private fun incomingDisplay(prefix: String): DisplayIdentity =
        PeerLabels.forPeer(prefix, contactNames[prefix]).display(generatedName = privateCall?.strangerGeneratedName())

    private val nearbyPeopleSession = NearbyPeopleSession()
    private var nearbySearchQuery = ""

    private val peopleAdapter by lazy {
        PeopleAdapter(this) { key, video ->
            privateCallPeers.firstOrNull { it.announcement.nodeIdPrefix.toHexKey() == key }?.let { peer ->
                // A copied ID needs a second tap: the call still meets the real key check, but the name may not be who you mean.
                if (peer.announcement.rotating && copiedIds.warned(key)) {
                    // Logged so a driver that taps the call button sees why no call phase follows (2026-10-20: a PC
                    // announcing one compact ID from two addresses raised this prompt on the A15s).
                    if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK people_call: copied_id_prompt | node=$key | video=$video")
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setIcon(R.drawable.ic_warning)
                        .setTitle(R.string.people_copied_id)
                        .setMessage(R.string.people_copied_id_message)
                        .setPositiveButton(R.string.people_copied_id_call) { _, _ -> startPeopleCall(peer, video) }
                        .setNegativeButton(R.string.action_cancel, null)
                        .showTracked()
                    return@let
                }
                startPeopleCall(peer, video)
            }
        }
    }

    private fun startPeopleCall(peer: BleL2capPeer, video: Boolean) {
        run {
            run {
                withWifiCheck(voice = !video) {
                    // A call dials at once. The call subject is retired (2026-11-05): none is sent, and one sent by
                    // an older version is dropped unseen.
                    callPeerDirectly(peer, if (video) CallType.VIDEO else CallType.VOICE)
                }
            }
        }
    }

    private fun peerForListPosition(position: Int): BleL2capPeer? {
        val key = peopleAdapter.personAt(position)?.key ?: return null
        return privateCallPeers.firstOrNull { it.announcement.nodeIdPrefix.toHexKey() == key }
    }

    /** The row's call buttons: the chosen call type directly (same checks as choosing from the menu). */
    private fun callPeerDirectly(peer: BleL2capPeer, type: CallType) {
        if (privateCall?.suspendMessageSync() == true) {
            callsSnackbar(getString(R.string.call_waiting_for_messages))
            return
        }
        startOutgoingPrivateCall(peer, if (IntercomFeatures.current.voiceCalls) type else CallType.VIDEO)
    }

    /** Every verified contact, for the People tab's Saved section (read off the main thread). */
    @Volatile private var savedContacts: List<ContactEntry> = emptyList()

    private val contactWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val contactActions by lazy {
        ContactActions(
            this,
            contactWorker,
            ::refreshContactNames,
            onCall = { contact, video -> callContact(contact, video) },
            hintFor = ::contactHint,
            onMessage = {
                if (MessagingV2Feature.enabled) { contact -> messageContact(contact.nodeIdHex, ChatEntry.Source.CONTACT_DETAIL) } else null
            },
        )
    }

    // ----- Messaging v2 conversations (phase 1c, switch test_messaging_v2) -----

    /** The Contacts rows as conversation rows (preview, time, unread, ticks). Created only with the feature on. */
    private val conversationRows: ConversationRowsController by lazy {
        ConversationRowsController(applicationContext, findViewById(R.id.contactsList), findViewById(R.id.contactsEmptyView))
    }

    /**
     * The thread screen is a view over this activity, not an activity of its own: the thread link is dialled from the discovery
     * that runs while this activity shows the Call screen, and a second activity would stop that scan.
     */
    private val threadScreenDelegate: Lazy<ThreadScreen> = lazy {
        ThreadScreen(
            this,
            findViewById(R.id.threadRoot),
            object : ThreadHost {
                override fun nearbyPrefixes(): Set<String> = privateCallPeers.map { it.announcement.nodeIdPrefix.toHexKey() }.toSet()
                override fun contactEntry(nodeIdHex: String): ContactEntry? = savedContacts.firstOrNull { it.nodeIdHex == nodeIdHex }
                override fun contactNames(): Map<String, String> = contactNames
                override fun call(prefixHex: String, video: Boolean, name: String) = callPerson(prefixHex, video, name)
                override fun showContactActions(contact: ContactEntry) = contactActions.show(contact)
                override fun openVerify(nodeIdHex: String, name: String) {
                    startActivity(VerifyContactActivity.intent(this@MainActivity, nodeIdHex, name))
                }

                override fun threadClosed() {
                    threadBackCallback.isEnabled = false
                    updateContactsList()
                    refreshMessagesUnread(notify = false)
                }
            },
        )
    }
    private val threadScreen: ThreadScreen by threadScreenDelegate
    private val threadBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (threadScreenDelegate.isInitialized()) threadScreen.close()
        }
    }

    /** Every chat entry point lands here: the Chats tab in front, then that person's thread. */
    private fun messageContact(nodeIdHex: String?, source: ChatEntry.Source) {
        val target = ChatEntry.threadFor(source, nodeIdHex, MessagingV2Feature.enabled) ?: return
        if (!callBrowserChats) showChatsTab()
        openThread(target)
    }

    private fun openThread(nodeIdHex: String) {
        if (!MessagingV2Feature.enabled) return
        threadBackCallback.isEnabled = true
        threadScreen.open(nodeIdHex)
        askMessageNotificationPermissionOnce()
    }

    /**
     * The first time a conversation opens, Android 13+ is asked once for the notification permission, so that a new message can
     * reach the user. A refusal is respected: nothing is posted and nothing asks again.
     */
    private fun askMessageNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || BuildConfig.DEBUG && DebugTunables.benchmarkLaunch) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (appPreferences.getBoolean(PREF_MV2_NOTIFICATION_ASKED, false)) return
        appPreferences.edit().putBoolean(PREF_MV2_NOTIFICATION_ASKED, true).apply()
        callNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Opens the conversation named by a message notification. The extra is the conversation ID; it is checked against the accepted
     * contacts, so a deleted contact or a forged intent opens nothing but the app. A live call, stream or camera screen is never
     * switched away from. The extra is consumed so a later resume does not open it again.
     */
    private fun openConversationFromIntent(source: Intent) {
        val extra = source.getStringExtra(ConversationRoute.EXTRA_CONVERSATION) ?: return
        source.removeExtra(ConversationRoute.EXTRA_CONVERSATION)
        if (!MessagingV2Feature.enabled) return
        val context = applicationContext
        Thread {
            val node = runCatching { StoreForwardRuntime.node(context) }.getOrNull()
            val peer = node?.takeIf { it.v2 != null }
                ?.let { ConversationRoute.resolve(extra, it.nodeId, it.contacts.accepted().map { contact -> contact.nodeId }) }
            if (peer != null) runOnUiThread {
                val live = appScreen == AppScreen.CAMERA || appScreen == AppScreen.PLAYBACK || appScreen == AppScreen.CALL ||
                    privateCallPhase != null
                if (!isDestroyed && !live) messageContact(peer.toString(), ChatEntry.Source.NOTIFICATION)
            }
        }.apply { name = "mv2-open-conversation"; isDaemon = true }.start()
    }

    /** The Contacts tab: saved people only, "Nearby now" for those in range. */
    private fun updateContactsList() {
        if (!::contactsTabScreen.isInitialized) return
        if (MessagingV2Feature.enabled && callBrowserChats) {
            conversationRows.update(savedContacts) { privateCallPeers.map { it.announcement.nodeIdPrefix.toHexKey() }.toSet() }
            ConversationNotifier.contactsChanged(applicationContext)
            if (threadScreenDelegate.isInitialized() && threadScreen.isOpen) threadScreen.contactsChanged()
            return
        }
        val inRange = contactsInRange()
        // Name with video call, voice call and info buttons; no initial or check mark (every contact is verified).
        findViewById<android.widget.ListView>(R.id.contactsList).adapter = ContactRowAdapter(
            this, savedContacts, inRange, contactHints,
            onCall = { contact, video -> callContact(contact, video) },
            onInfo = { contactActions.show(it) },
            onMessage = if (MessagingV2Feature.enabled) { contact -> messageContact(contact.nodeIdHex, ChatEntry.Source.CONTACT_ROW) } else null,
        )
        findViewById<View>(R.id.contactsEmptyView).visibility = if (savedContacts.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Saved names of verified contacts by node-ID prefix (read off the main thread). */
    @Volatile private var contactNames: Map<String, String> = emptyMap()

    /** What the Contacts rows say about a contact that cannot be recognised nearby yet, by prefix ([ContactReach]). */
    @Volatile private var contactHints: Map<String, ContactReach.Hint> = emptyMap()

    private fun refreshContactNames() {
        val context = applicationContext
        Thread {
            val names = runCatching { SecurityUiBackend.current.contactNames(context) }.getOrDefault(emptyMap())
            val saved = runCatching { ContactNames.sorted(SecurityUiBackend.current.contacts(context)) }
                .getOrDefault(emptyList())
            val hints = saved.associate { it.prefixHex to contactHint(it) }
            runOnUiThread {
                if (names != contactNames || saved != savedContacts || hints != contactHints) {
                    contactNames = names
                    savedContacts = saved
                    contactHints = hints
                    updatePrivateCallPeerList()
                    updateContactsList()
                }
            }
        }.start()
    }

    /**
     * Whether this phone can recognise [contact] nearby and, if not, what the user can do ([ContactReach]). Off the main
     * thread: the presence vault and the claims file may touch the Keystore and the disk.
     */
    private fun contactHint(contact: ContactEntry): ContactReach.Hint = runCatching {
        val vault = if (RotatingDiscoveryFeature.enabled) RotatingDiscovery.presenceVault() else null
        val fingerprint = IdentityFingerprint.fromHex(contact.fingerprintHex)
        ContactReach.hint(
            recognitionOn = vault?.ensureLoaded() == true,
            holdsLease = vault?.contact(fingerprint.nodeId)?.presence != null,
            scannedBothWays = runCatching { ContactClaims.store(applicationContext).get(fingerprint)?.scannedBothWays == true }
                .getOrDefault(false),
            meetAvailable = MeetBridge.available(),
        )
    }.getOrDefault(ContactReach.Hint.RECOGNISABLE)

    /**
     * The user verified [nodeIdHex] ([fingerprintHex]) on any screen ([VerificationEvents]). A call with that person may now
     * exchange presence leases (its session was judged at the handshake), and a stranger's recent entry for them becomes the
     * contact's entry, so Recent calls them like Contacts does.
     */
    private fun onContactVerified(nodeIdHex: String, fingerprintHex: String) {
        privateCall?.onPeerVerified(nodeIdHex)
        val inCall = privateCallStrangerRecent?.takeIf { privateCall?.revealedPeerFingerprint?.hex == fingerprintHex }
        if (inCall != null) privateCallStrangerRecent = null
        val stranger = StrangerRecentLinks.take(fingerprintHex) ?: inCall
        refreshContactNames()
        if (stranger == null) return
        val context = applicationContext
        Thread {
            val promoted = runCatching { RecentCalls.promoteStranger(context, stranger, fingerprintHex) }.getOrDefault(false)
            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK recent_promoted: changed=$promoted")
            runOnUiThread { if (!isDestroyed) updateRecentCalls() }
        }.start()
    }

    private val verificationListener: (String, String) -> Unit = { nodeIdHex, fingerprintHex ->
        runOnUiThread { if (!isDestroyed) onContactVerified(nodeIdHex, fingerprintHex) }
    }

    /**
     * Rotating IDs: "Nearby phone", or - when a lease-derived ID of exactly one contact matches (Phase 2) -
     * that contact's own label with "seen nearby, unconfirmed". Never a check mark, never the handle of a
     * stranger, and a match starts no connection (PROTOCOL "Recognition and presence leases").
     */
    private fun rotatingPeerRowName(announcement: DiscoveryAnnouncement, generatedName: String? = null): String {
        if (announcement.isWindowOffer) {
            val base = generatedName ?: getString(R.string.people_rotating_phone)
            val claimed = NameWindowBoard.claimedName(announcement)
            return if (claimed != null) getString(R.string.people_window_claimed, claimed, base) else base
        }
        val hit = RotatingDiscovery.recognize(announcement).singleOrNull()
            ?: return generatedName ?: getString(R.string.people_rotating_phone)
        val prefix = hit.contact.toString().take(12)
        return getString(R.string.people_seen_nearby, PeerLabels.forPeer(prefix, contactNames[prefix]).listName())
    }

    /** One clean name for a discovered person (never raw hex). */
    private fun peerName(peer: BleL2capPeer): String {
        val prefix = peer.announcement.nodeIdPrefix.toHexKey()
        // The same name the nearby list shows: contact label, generated name, "~Name", else "Someone nearby".
        if (peer.announcement.rotating) {
            return rotatingPeerRowName(peer.announcement, rotatingGeneratedNames(privateCallPeers)[prefix])
        }
        return peerRowName(PeerLabels.forPeer(prefix, contactNames[prefix]))
    }

    /** One clean name for the authenticated peer of an incoming call ([label] is its 12-hex node label). */
    private fun incomingPeerName(label: String): String {
        val prefix = label.lowercase().take(12)
        val display = incomingDisplay(prefix)
        return when (display.rank) {
            DisplayRank.SELF_CLAIMED -> "~" + display.title
            // A fixed-prefix caller with no generated name: never the raw handle on the ring screen.
            DisplayRank.HANDLE -> getString(R.string.people_someone_nearby)
            else -> display.title
        }
    }

    /** A self-claimed name is never shown without the handle (R5): it goes on the state line. */
    private fun incomingStateText(label: String, video: Boolean): String {
        val prefix = label.lowercase().take(12)
        val peerLabel = PeerLabels.forPeer(prefix, contactNames[prefix])
        val state = getString(if (video) R.string.call_incoming_video else R.string.call_incoming_voice)
        val stranger = privateCall?.peerStillPseudonym == true
        val display = incomingDisplay(prefix)
        val lines = mutableListOf(state)
        when (display.rank) {
            // "calls itself" with the generated name below: the claim is shown, never presented as checked.
            DisplayRank.SELF_CLAIMED -> {
                lines += getString(R.string.call_incoming_claims, display.titleNote.orEmpty())
                display.subtitle?.let { lines += it }
            }
            DisplayRank.GENERATED -> if (stranger) lines += getString(R.string.call_incoming_stranger_note)
            else -> Unit
        }
        // A name that imitates a contact, you or a generated name: an alarm only after the reveal (a contact whose lease
        // expired arrives as a stranger), a neutral note otherwise.
        if (display.rank == DisplayRank.SELF_CLAIMED) {
            ImitationCheck.evaluate(
                claimed = display.title,
                contacts = contactNames.values.map { ImitationCheck.ContactNames(it) },
                ownName = PeerLabelFeature.localName,
                generatedNames = listOfNotNull(privateCall?.strangerGeneratedName()),
                otherVisibleClaims = emptyList(),
                afterReveal = !stranger,
            )?.let { lines += DisplayIdentityText.warningText(it) }
        }
        // So two people meeting can match up: the made-up name this phone shows nearby (user request 2026-10-21).
        OwnNearbyName.currentLine(this)?.let { lines += it }
        return lines.joinToString("\n")
    }

    // ----- Relay control (design decision 2026-10-10) -----

    private fun isBenchmarkLaunch(): Boolean = benchmarkExtrasPresent()

    /**
     * A DEBUG build launched with any `test_` extra, unless `test_user_launch=true` asks for a person's launch
     * ([BenchmarkLaunch], which holds the DEBUG guard: release builds never see a benchmark launch).
     */
    private fun benchmarkExtrasPresent(): Boolean = BenchmarkLaunch.fromExtras(
        intent.extras?.keySet().orEmpty(),
        userLaunch = intent.getBooleanExtra(BenchmarkLaunch.EXTRA_USER_LAUNCH, false),
    )

    /** Automation that must not show onboarding later ([BenchmarkLaunch.skipsOnboarding]). */
    private fun launchSkipsOnboarding(): Boolean = BenchmarkLaunch.skipsOnboarding(
        intent.extras?.keySet().orEmpty(),
        userLaunch = intent.getBooleanExtra(BenchmarkLaunch.EXTRA_USER_LAUNCH, false),
    )

    /**
     * Forwarding for a join of [streamToken]: the per-stream choice, else the global default.
     * Normal use pauses it below 20 % battery (not charging) or in Battery Saver; benchmark launches
     * are unaffected (they set test_forward_received_streams explicitly).
     */
    private fun effectiveForwarding(streamToken: Int): Boolean {
        val wanted = forwardReceivedStreams
        if (!wanted || isBenchmarkLaunch()) return wanted
        val battery = getSystemService(android.os.BatteryManager::class.java)
        val level = battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = battery?.isCharging == true
        val saver = powerManager.isPowerSaveMode
        val pause = relayAutoPaused(level, charging, saver)
        if (pause) Log.i("BluetoothVideo", "Relay auto-pause: battery=$level% charging=$charging saver=$saver; not forwarding this stream")
        return !pause
    }

    // ----- Recent calls (Home) -----

    /** A call back waits for that person to appear in discovery: prefix, video, deadline. */
    private var pendingCallBack: Triple<String, Boolean, Long>? = null

    /** The generated name of the current call's stranger entry, until it is linked to the revealed fingerprint. */
    private var privateCallStrangerRecent: String? = null

    private fun recordEndedRecentCall(endpoint: AndroidPrivateVideoCall) {
        if (!DebugTunables.completeRecentCalls || isBenchmarkLaunch()) return
        val call = endpoint.takeEndedRecentCall() ?: return
        if (call.strangerName.isNotEmpty()) endpoint.revealedPeerFingerprint?.let {
            StrangerRecentLinks.remember(it.hex, call.strangerName)
        }
        contactWorker.execute {
            // Never throws (review 2026-10-27, finding 1); the guard keeps a future change from crashing the worker.
            runCatching { RecentCalls.record(applicationContext, call) }
            runOnUiThread { if (!isDestroyed) updateRecentCalls() }
        }
    }

    private fun recordRecentCall() {
        val endpoint = privateCall ?: return
        if (isBenchmarkLaunch()) return // automated runs leave no history
        privateCallStrangerRecent = null
        // F20: a stranger who is not (yet) a verified contact is remembered by the generated name and the time only.
        endpoint.recentStrangerName()?.let { stranger ->
            // In memory only: if the user verifies this person later, the entry becomes theirs (onContactVerified).
            privateCallStrangerRecent = stranger
            endpoint.revealedPeerFingerprint?.let { StrangerRecentLinks.remember(it.hex, stranger) }
            val context = applicationContext
            val call = RecentCall("", endpoint.localCallType == CallType.VIDEO, System.currentTimeMillis(), strangerName = stranger)
            Thread { runCatching { RecentCalls.record(context, call) } }.start()
            return
        }
        if (endpoint.peerStillPseudonym) return // a throwaway key is no one to call back
        val prefix = endpoint.endToEndPeerNodeId?.take(12) ?: return
        val call = RecentCall(
            prefix,
            endpoint.localCallType == CallType.VIDEO,
            System.currentTimeMillis(),
            fingerprintHex = endpoint.incomingCaller()?.fingerprint?.hex.orEmpty(), // call blocking: lets the row block them
        )
        val context = applicationContext
        Thread { runCatching { RecentCalls.record(context, call) } }.start()
    }

    internal fun localReportChanged() {
        updateRecentCalls()
        updateContactsList()
        refreshMessagesUnread(notify = false)
        refreshNearbyStreamPeers()
        refreshPrivateCallPeers()
    }

    private fun updateRecentCalls() {
        val list = findViewById<ViewGroup>(R.id.homeRecentList) ?: return
        val empty = findViewById<View>(R.id.homeRecentEmpty)
        val context = applicationContext
        Thread {
            val calls = runCatching { RecentCalls.load(context) }.getOrDefault(emptyList())
                .filterNot { LocalReporting.hidden(context, it.fingerprintHex.ifEmpty { it.nodePrefixHex }) }
            // A history this phone cannot open now is never overwritten; the user may clear it (review 2026-10-27, finding 7).
            val unreadable = calls.isEmpty() && runCatching { RecentCalls.isUnreadable(context) }.getOrDefault(false)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                list.removeAllViews()
                calls.forEach { call -> list.addView(recentRow(list, call)) }
                empty.visibility = if (calls.isEmpty()) View.VISIBLE else View.GONE
                (empty as? TextView)?.text = getString(if (unreadable) R.string.recent_unreadable else R.string.recent_empty)
                empty.setOnClickListener(if (!unreadable) null else View.OnClickListener { confirmClearRecentHistory() })
            }
        }.start()
    }

    /** "Clear history" for a Recent list this phone cannot open (it is never written over by itself). */
    private fun confirmClearRecentHistory() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setMessage(R.string.recent_clear_message)
            .setPositiveButton(R.string.recent_clear_action) { _, _ ->
                val context = applicationContext
                Thread {
                    runCatching { RecentCalls.clear(context) }
                    runOnUiThread { if (!isDestroyed) updateRecentCalls() }
                }.start()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showTracked()
    }

    private fun recentCallName(call: RecentCall): String =
        if (call.strangerName.isNotEmpty()) call.strangerName.split(' ', '-').take(2).joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        else peerRowName(PeerLabels.forPeer(call.nodePrefixHex, contactNames[call.nodePrefixHex]))

    private fun openAddContact(name: String) {
        startActivity(VerifyContactActivity.intent(this, peerName = name))
    }

    private fun showStrangerRecentActions(name: String) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply { setText(R.string.recent_stranger_actions) })
            addView(com.google.android.material.button.MaterialButton(context).apply {
                setText(R.string.recent_open_calls)
                setOnClickListener { sheet.dismiss(); showPrivateCallBrowser() }
            })
            addView(com.google.android.material.button.MaterialButton(context).apply {
                setText(R.string.action_add_contact)
                setOnClickListener { sheet.dismiss(); openAddContact(name) }
            })
        }
        sheet.setContentView(column)
        sheet.show()
    }

    private fun bindRecentOutcome(row: View, call: RecentCall) {
        if (!DebugTunables.completeRecentCalls) return
        row.findViewById<TextView>(R.id.personReach).apply {
            val arrow = when (call.direction) {
                CallDirection.INCOMING -> getString(R.string.recent_incoming_arrow)
                CallDirection.OUTGOING -> getString(R.string.recent_outgoing_arrow)
                CallDirection.UNKNOWN -> ""
            }
            text = getString(R.string.recent_outcome, arrow, getString(call.result.labelRes), text)
            setTextColor(ContextCompat.getColor(this@MainActivity,
                if (call.result == CallResult.MISSED) R.color.danger else R.color.text_secondary))
        }
    }

    /** F20: a stranger's recent entry: the generated name and the time, nothing to call back or block. */
    private fun strangerRecentRow(parent: ViewGroup, call: RecentCall): View {
        val row = layoutInflater.inflate(R.layout.item_person_row, parent, false)
        val name = call.strangerName.split(' ', '-').take(2).joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        val time = android.text.format.DateUtils.getRelativeTimeSpanString(
            call.atMs, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
        ).toString()
        row.findViewById<TextView>(R.id.personAvatar).apply {
            text = ""
            backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.surface_raised)
        }
        row.findViewById<View>(R.id.personAvatarIcon).visibility = View.VISIBLE
        row.findViewById<View>(R.id.personVerified).visibility = View.GONE
        row.findViewById<TextView>(R.id.personName).text = name
        row.findViewById<TextView>(R.id.personReach).text =
            getString(R.string.recent_stranger, getString(if (call.video) R.string.recent_video else R.string.recent_voice, time))
        row.findViewById<View>(R.id.personVoiceButton).visibility = View.GONE
        row.findViewById<View>(R.id.personVideoButton).visibility = View.GONE
        row.setOnClickListener {
            if (DebugTunables.strangerCallFollowup) showStrangerRecentActions(name)
            else Toast.makeText(this, R.string.recent_stranger_note, Toast.LENGTH_LONG).show()
        }
        bindRecentOutcome(row, call)
        row.contentDescription = getString(R.string.recent_stranger_row_description, name, row.findViewById<TextView>(R.id.personReach).text)
        return row
    }

    /** The shared person row (item_person_row), filled for a recent call. */
    private fun recentRow(parent: ViewGroup, call: RecentCall): View {
        if (call.blocked) return CallBlockingUi.blockedRecentRow(this, parent, call) // declined by Block unknown callers
        if (call.strangerName.isNotEmpty()) return strangerRecentRow(parent, call)
        val row = layoutInflater.inflate(R.layout.item_person_row, parent, false)
        val label = PeerLabels.forPeer(call.nodePrefixHex, contactNames[call.nodePrefixHex])
        val name = peerRowName(label)
        val time = android.text.format.DateUtils.getRelativeTimeSpanString(
            call.atMs, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
        ).toString()
        val named = label.nameKind != PeerLabel.NameKind.NONE
        row.findViewById<TextView>(R.id.personAvatar).apply {
            text = if (named) name.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty() else ""
            backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.surface_raised)
        }
        row.findViewById<View>(R.id.personAvatarIcon).visibility = if (named) View.GONE else View.VISIBLE
        row.findViewById<View>(R.id.personVerified).visibility =
            if (label.nameKind == PeerLabel.NameKind.CONTACT) View.VISIBLE else View.GONE
        row.findViewById<TextView>(R.id.personName).text = name
        row.findViewById<TextView>(R.id.personReach).apply {
            text = getString(if (call.video) R.string.recent_video else R.string.recent_voice, time)
            // The call type as a small icon before the time.
            val size = (16 * resources.displayMetrics.density).toInt()
            val icon = ContextCompat.getDrawable(this@MainActivity, if (call.video) R.drawable.ic_videocam else R.drawable.ic_call)
                ?.mutate()?.apply {
                    setBounds(0, 0, size, size)
                    setTint(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                }
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
        }
        // A history row: a tap calls back (long press blocks). A saved contact's row also has the call buttons and is
        // dialled exactly like Contacts (fixed prefix or a recognised rotating ID, callPerson).
        val voiceAllowed = IntercomFeatures.current.voiceCalls
        val contactRow = label.nameKind == PeerLabel.NameKind.CONTACT
        row.findViewById<View>(R.id.personVoiceButton).apply {
            visibility = if (contactRow && voiceAllowed) View.VISIBLE else View.GONE
            contentDescription = getString(R.string.people_voice_call, name)
            setOnClickListener { callPerson(call.nodePrefixHex, video = false, name = name) }
        }
        row.findViewById<View>(R.id.personVideoButton).apply {
            visibility = if (contactRow) View.VISIBLE else View.GONE
            contentDescription = getString(R.string.people_video_call, name)
            setOnClickListener { callPerson(call.nodePrefixHex, video = true, name = name) }
        }
        row.setOnClickListener {
            if (DebugTunables.strangerCallFollowup && !contactRow) showStrangerRecentActions(name)
            else callPerson(call.nodePrefixHex, video = call.video || !voiceAllowed, name = name)
        }
        row.setOnLongClickListener { CallBlockingUi.blockFromRecent(this, call, name); true } // call blocking
        bindRecentOutcome(row, call)
        row.contentDescription = getString(R.string.recent_row_description, name, row.findViewById<TextView>(R.id.personReach).text)
        return row
    }

    /** "Missed call" notification, Call back action ([MissedCallNotification]): the same path as a Recent call back. */
    private fun handleMissedCallBack(intent: Intent?): Boolean {
        // Re-review S3: from a cold start the sealed tokens may not be read yet; the tap is handled once they are.
        if (intent != null && MissedCallNotification.isCallBack(intent) && !MissedCallNotification.isLoaded(this)) {
            MissedCallNotification.whenLoaded(this) {
                runOnUiThread { if (!isFinishing && !isDestroyed) handleMissedCallBack(intent) }
            }
            return true
        }
        val target = intent?.let { MissedCallNotification.takeCallBack(this, it) } ?: return false
        if (!::callBrowserStatus.isInitialized) return false
        callBack(target.prefix, video = target.video, name = target.name.ifEmpty { getString(R.string.call_someone) })
        return true
    }

    /** One tap call back: open People and call this person as soon as discovery sees them. */
    private fun callBack(prefix: String, video: Boolean, name: String) {
        // A call back from an open thread (phase 1c) leaves the thread first.
        if (threadScreenDelegate.isInitialized() && threadScreen.isOpen) threadScreen.close()
        pendingCallBack = Triple(prefix, video, SystemClock.elapsedRealtime() + CALL_BACK_WAIT_MS)
        callBrowserContacts = false
        callBrowserChats = false
        showPrivateCallBrowser()
        privateCallDiscoveryHandler.postDelayed({
            val pending = pendingCallBack ?: return@postDelayed
            if (pending.first == prefix && SystemClock.elapsedRealtime() >= pending.third) {
                pendingCallBack = null
                if (appScreen == AppScreen.CALL_BROWSER) callsSnackbar(getString(R.string.recent_not_nearby, name))
            }
        }, CALL_BACK_WAIT_MS)
    }

    /**
     * Contact prefixes nearby now: a plain announcement's node prefix, or a rotating ID recognised as exactly one
     * contact (presence leases; recognition starts after the first answered call that follows a mutual scan).
     */
    private fun contactsInRange(): Set<String> = privateCallPeers.mapNotNullTo(HashSet()) { peer ->
        if (peer.announcement.rotating) {
            RotatingDiscovery.recognize(peer.announcement).singleOrNull()?.contact?.toString()?.take(12)
        } else {
            peer.announcement.nodeIdPrefix.toHexKey()
        }
    }

    /**
     * Contacts call buttons: dial at once when the contact is recognised nearby, without leaving Contacts; otherwise say
     * so right away and offer the Nearby list (recognition needs one answered call after the mutual scan).
     */
    private fun callContact(contact: ContactEntry, video: Boolean) {
        val peer = findPersonNearby(contact.prefixHex)
        if (BuildConfig.DEBUG) {
            // Design QL phone test: whether a Contacts call found the contact (by its lease-derived rotating ID or prefix).
            Log.i(
                "BluetoothVideo",
                "BENCHMARK contact_call: found=${peer != null} | rotating=${peer?.announcement?.rotating} | " +
                    "video=$video | nearbyCallPeers=${privateCallPeers.size} | peers=" +
                    privateCallPeers.joinToString(",") { candidate ->
                        val ad = candidate.announcement
                        val hits = if (ad.rotating) RotatingDiscovery.recognize(ad).size else -1
                        "${if (ad.rotating) "v5" else "v2"}:${ad.nodeIdPrefix.toHexKey().take(4)}:cost${ad.routeCost}:hits$hits"
                    },
            )
        }
        if (peer != null) {
            withWifiCheck(voice = !video) { callPeerDirectly(peer, if (video) CallType.VIDEO else CallType.VOICE) }
            return
        }
        // Design OR (DEBUG prototype): a contact not heard directly is searched for through phones nearby on this tap.
        if (RelaySeekFeature.active()) {
            withWifiCheck(voice = !video) { startContactSearch(contact, video) }
            return
        }
        // Not in the list now. A contact this phone can recognise may simply not be listed yet (the call scan restarts after
        // a call, or does not run on Recent): wait for its sighting. Otherwise say why it cannot be found (the vault and
        // claims are read off the main thread).
        contactWorker.execute {
            val hint = contactHint(contact)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (ContactReach.mayWaitForCallBack(hint)) {
                    withWifiCheck(voice = !video) { startContactDialWait(contact, video) }
                } else {
                    showContactNotFound(contact, video, hint)
                }
            }
        }
    }

    /**
     * Design OR (DEBUG prototype, revision 3 caller rules): one search for [contact] through the helpers this phone hears.
     * Without a lease, helpers, scope or while backed off, the ordinary contact wait for a sighting runs instead.
     */
    private fun startContactSearch(contact: ContactEntry, video: Boolean) {
        val type = if (video || !IntercomFeatures.current.voiceCalls) CallType.VIDEO else CallType.VOICE
        val prefix = if (BuildConfig.DEBUG) "BENCHMARK " else ""
        when (
            val start = RelaySeekCaller.start(
                contact.prefixHex, currentCallTransport, RelayedCallsFeature.maxRelays, RelayedCallsFeature.requireRelays,
            )
        ) {
            is RelaySeekCaller.Start.Search -> {
                Log.i("BluetoothVideo", prefix + RelaySeekLog.line("caller", "tap") + " | contact_call=true")
                callBrowserStatus.text = getString(R.string.relayed_seek_searching, contact.title)
                callPeerDirectly(start.peer, type)
                return
            }
            is RelaySeekCaller.Start.BackedOff -> {
                Log.i("BluetoothVideo", prefix + RelaySeekLog.line("caller", "backed_off") + " | wait_ms=${start.waitMs}")
                callsSnackbar(getString(R.string.relayed_seek_backed_off, contact.title, ((start.waitMs + 999L) / 1_000L).toInt()))
            }
            is RelaySeekCaller.Start.NotPossible ->
                Log.i("BluetoothVideo", prefix + RelaySeekLog.line("caller", "not_started", reason = start.reason))
        }
        // The direct route only (the backoff, or nothing to search through).
        contactWorker.execute {
            val hint = contactHint(contact)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (ContactReach.mayWaitForCallBack(hint)) startContactDialWait(contact, video) else showContactNotFound(contact, video, hint)
            }
        }
    }

    /** "Looking for <name>…" with Cancel while a contact dial waits for its sighting (any screen, also Contacts). */
    private var dialWaitSnackbar: com.google.android.material.snackbar.Snackbar? = null

    /**
     * A contact called from Contacts, Recent or a conversation that is not in the call list right now: the same bounded wait
     * as a tapped row ([DebugDialWait], 10 s) for a sighting of its fixed prefix or of a rotating ID recognised as exactly this
     * contact, with the call scan running. The call starts from that sighting; only when the wait runs out does the
     * "not found nearby" guidance appear. Phone runs userflow-b57d0eaf-20261019: Recent and Contacts calls right after a
     * call found an empty list (`nearbyCallPeers=0`) and failed at once.
     */
    private fun startContactDialWait(contact: ContactEntry, video: Boolean) {
        if (threadScreenDelegate.isInitialized() && threadScreen.isOpen) threadScreen.close()
        cancelDebugDialWait()
        dialFailureNotice = null
        pendingCallBack = null
        val type = if (video || !IntercomFeatures.current.voiceCalls) CallType.VIDEO else CallType.VOICE
        // F11: room for five sightings at this scanner's measured rate (10-30 s), not a fixed 10 s on a slow scanner.
        val typicalGapMs = callPeerCadence.typicalGapMs()
        val waitMs = WeakPairReach.contactDialWaitMs(
            WeakPairReach.dialWaitMs(typicalGapMs, DebugTunables.weakPairReach), RelayedCallsFeature.enabled,
        )
        val dial = DebugDialWait.Pending(
            contact.prefixHex, type, messageSync = false, startedAtMs = SystemClock.elapsedRealtime(),
            waitMs = waitMs, displayName = contact.title, contact = true,
        )
        debugDialWait = dial
        ownDial = OwnDial(advertKey = contact.prefixHex, targetPrefix = contact.prefixHex, name = contact.title)
        privateCallDiscoveryHandler.postDelayed(debugDialTimeout, waitMs)
        logCallInvite(
            DebugDialWait.line(
                "waiting", dial.prefix, "reason" to "contact_not_listed", "timeoutMs" to waitMs,
                "typicalGapMs" to (typicalGapMs ?: "none"),
                "nearbyCallPeers" to privateCallPeers.size, "scanRunning" to (privateCallDiscovery != null),
            ),
        )
        // The call scan runs only on the Call screens: open Contacts there (from Recent), or restart a scan that is down.
        if (appScreen != AppScreen.CALL_BROWSER) {
            callBrowserContacts = true
            callBrowserChats = false
            showPrivateCallBrowser()
        } else if (privateCallDiscovery == null) {
            restartPrivateCallDiscovery()
        }
        dialWaitSnackbar = com.google.android.material.snackbar.Snackbar
            .make(
                findViewById(android.R.id.content),
                DebugDialWait.lookingStatus(contact.title),
                com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE,
            )
            .setAction(R.string.action_cancel) {
                if (debugDialWait === dial) {
                    cancelDebugDialWait()
                    logCallInvite(DebugDialWait.line("cancelled", dial.prefix, "waitedMs" to dial.waitedMs(SystemClock.elapsedRealtime())))
                    if (appScreen == AppScreen.CALL_BROWSER) callBrowserStatus.text = ""
                }
            }
            .also { it.show() }
    }

    /**
     * F14: the user's own dial while it is still looking for its person (address refresh of a tapped row, or a dial wait);
     * null once it connected, ended or was cancelled. Read on a call's reader thread (volatile fields).
     */
    private fun currentOwnDial(): OwnDial? {
        val dial = ownDial ?: return null
        val pending = pendingPrivateCallPeerKey != null || debugDialWait?.displayName != null
        return dial.takeIf { pending }
    }

    /**
     * F14: a user dial in its address refresh becomes a bounded wait (the same conversion as a discovery restart), so a
     * sighting that comes while the endpoint is being rebuilt after a busy decline waits for it instead of being lost.
     */
    private fun deferPendingDialToWait(reason: String) {
        val key = pendingPrivateCallPeerKey ?: return
        if (dialSelectionStartedAtMs == 0L) return
        val startedAtMs = dialSelectionStartedAtMs
        dialSelectionStartedAtMs = 0L
        pendingPrivateCallPeerKey = null
        val wait = DebugDialWait.Pending(
            key, pendingPrivateCallType, pendingPrivateCallMessageSync, startedAtMs,
            displayName = pendingPrivateCallPeerName,
        )
        debugDialWait = wait
        privateCallDiscoveryHandler.removeCallbacks(debugDialTimeout)
        privateCallDiscoveryHandler.postDelayed(debugDialTimeout, wait.remainingMs(SystemClock.elapsedRealtime()))
        logCallInvite(DebugDialWait.line("waiting", key, "reason" to reason))
    }

    /** The rotating ID of [peer] recognised as exactly one contact: that contact's prefix, else null. */
    private fun recognisedContactPrefix(peer: BleL2capPeer): String? =
        if (!peer.announcement.rotating) {
            null
        } else {
            RotatingDiscovery.recognize(peer.announcement).singleOrNull()?.contact?.toString()?.take(12)
        }

    /**
     * A saved contact that Contacts cannot dial now. A contact this phone can recognise is "not found nearby yet" and
     * Open Nearby waits for them. Without their presence lease the wait could never match (they advertise a changing ID),
     * so the dialog says how the phones learn to recognise each other and Nearby opens for browsing only.
     */
    private fun showContactNotFound(contact: ContactEntry, video: Boolean, hint: ContactReach.Hint) {
        val name = contact.title
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setNegativeButton(R.string.action_cancel, null)
        val openMeet = { startActivity(VerifyContactActivity.intent(this)) }
        val browse = { browseNearby(getString(R.string.contact_browse_nearby, name)) }
        when (hint) {
            ContactReach.Hint.RECOGNISABLE -> dialog
                .setTitle(getString(R.string.contact_not_found_title, name))
                .setMessage(getString(R.string.contact_not_found_text, name))
                .setPositiveButton(R.string.contact_not_found_nearby) { _, _ ->
                    if (ContactReach.mayWaitForCallBack(hint)) callBack(contact.prefixHex, video, name) else browse()
                }
            ContactReach.Hint.ASK_TO_SCAN -> dialog
                .setTitle(getString(R.string.contact_ask_scan_title, name))
                .setMessage(getString(R.string.contact_ask_scan_text, name))
                .setPositiveButton(R.string.contact_show_my_code) { _, _ -> startActivity(VerifyContactActivity.intent(this, showCode = true)) }
                .setNeutralButton(R.string.contact_not_found_nearby) { _, _ -> browse() }
            ContactReach.Hint.SCAN_AGAIN -> dialog
                .setTitle(getString(R.string.contact_not_recognised_title, name))
                .setMessage(getString(R.string.contact_scan_again_text, name))
                .setPositiveButton(R.string.contact_scan_again) { _, _ -> openMeet() }
                .setNeutralButton(R.string.contact_not_found_nearby) { _, _ -> browse() }
            ContactReach.Hint.CALL_FROM_NEARBY -> dialog
                .setTitle(getString(R.string.contact_not_recognised_title, name))
                .setMessage(getString(R.string.contact_call_once_text, name))
                .setPositiveButton(R.string.contact_not_found_nearby) { _, _ -> browse() }
        }
        val shown = dialog.showTracked()
        // F15: a stale "not found" question never lingers: it closes itself after a minute.
        Handler(Looper.getMainLooper()).postDelayed({ if (shown.isShowing) shown.dismiss() }, NOT_FOUND_DIALOG_MS)
    }

    /** F15: every dialog this activity shows, so an incoming call or the call screen is never left under one. */
    private val openDialogs = LinkedHashSet<android.app.Dialog>()

    private fun androidx.appcompat.app.AlertDialog.Builder.showTracked(): androidx.appcompat.app.AlertDialog {
        val dialog = show()
        openDialogs += dialog
        dialog.setOnDismissListener { openDialogs -= dialog }
        return dialog
    }

    /** F15: closes every open dialog of this activity (the call screen or an incoming call takes the screen). */
    private fun dismissOpenDialogs(reason: String) {
        val open = openDialogs.filter { it.isShowing }
        openDialogs.clear()
        open.forEach { runCatching { it.dismiss() } }
        runCatching { contactActions.dismissOpen() }
        fullScreenAccessBanner?.dismiss()
        fullScreenAccessBanner = null
        if (open.isNotEmpty() && BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK dialogs_dismissed: count=${open.size} | reason=$reason")
    }

    /** Nearby for browsing: no pending call back (it could not match), [status] says what to look for. */
    private fun browseNearby(status: String) {
        if (threadScreenDelegate.isInitialized() && threadScreen.isOpen) threadScreen.close()
        pendingCallBack = null
        callBrowserContacts = false
        callBrowserChats = false
        showPrivateCallBrowser()
        setCallsNotice(status)
    }

    /** Every "call this person" tap (a Recent row, a conversation): a saved contact goes the Contacts way ([callContact]). */
    private fun callPerson(prefix: String, video: Boolean, name: String) {
        val contact = savedContacts.firstOrNull { it.prefixHex == prefix }
        if (contact != null) callContact(contact, video) else callBack(prefix, video, name)
    }

    /**
     * The phone in range that is this person: a plain announcement with their fixed prefix, or a rotating ID recognised as
     * exactly this contact (presence leases). One rule for Contacts, Recent and the call back.
     */
    private fun findPersonNearby(prefix: String): BleL2capPeer? {
        val peers = privateCallPeers.filter(::labVisible)
        return peers.firstOrNull { it.announcement.nodeIdPrefix.toHexKey() == prefix }
            ?: peers.firstOrNull { candidate ->
                candidate.announcement.rotating &&
                    RotatingDiscovery.recognize(candidate.announcement).singleOrNull()?.contact?.toString()?.take(12) == prefix
            }
    }

    /**
     * DEBUG lab mask: with `test_relayed_calls_no_direct` and a required relay count, a direct sighting is treated as out of
     * range for contact calls too (the reach tables already ignore it), so a co-located benchmark measures the relayed path.
     */
    private fun labVisible(peer: BleL2capPeer): Boolean =
        !(RelayedCallsFeature.noDirectSightings && RelayedCallsFeature.requireRelays > 0 && peer.relayRoute == null)

    /**
     * A contact call waiting for its person ([startContactDialWait]) also resumes when a route through other phones to
     * that contact appears; a direct sighting resumes it from the scan results as before.
     */
    private fun completeContactDialWaitFromRoutes() {
        val dial = debugDialWait?.takeIf { it.contact } ?: return
        val peer = findPersonNearby(dial.prefix)?.takeIf { it.relayRoute != null } ?: return
        resumeDebugDialWait(dial, peer)
    }

    /** Called when the people list changes: completes a pending call back. */
    private fun completePendingCallBack() {
        val (prefix, video, deadline) = pendingCallBack ?: return
        if (SystemClock.elapsedRealtime() > deadline) {
            pendingCallBack = null
            return
        }
        // Rotating IDs (default since 0.2.0): a contact advertises a changing ID, so also match the phone whose
        // lease-derived ID is recognised as exactly this contact (the same rule the Calls list uses for its names).
        val peer = findPersonNearby(prefix) ?: return
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_back: matched | rotating=${peer.announcement.rotating}")
        pendingCallBack = null
        withWifiCheck(voice = !video) { callPeerDirectly(peer, if (video) CallType.VIDEO else CallType.VOICE) }
    }

    /** Fills the shared empty-state component (view_empty_state) of one screen. */
    /**
     * The Nearby list's empty state: the ordinary "No one found yet", or, while the scanner cannot even register
     * ([ScanRestartHint]), "Bluetooth needs a restart" with a button to the Bluetooth settings.
     */
    private var callEmptyShowsRestart: Boolean? = null

    private fun bindCallEmptyState() {
        // A startup that stopped before the layout (permissions) has no list to update.
        if (findViewById<View?>(R.id.callEmptyView) == null) return
        val restart = ScanRestartHint.due()
        if (restart == callEmptyShowsRestart) return
        callEmptyShowsRestart = restart
        if (restart) {
            Log.i("BluetoothVideo", "BENCHMARK scan_restart_hint: event=shown | failures=${GattPoolBackoff.shared.failures(PoolSource.SCANNER)}")
            bindEmptyState(
                R.id.callEmptyView, R.drawable.ic_group, R.string.people_bluetooth_restart_title,
                R.string.people_bluetooth_restart_text, R.string.people_bluetooth_restart_action,
            ) {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
            }
        } else {
            bindEmptyState(R.id.callEmptyView, R.drawable.ic_group, R.string.people_empty_title, R.string.people_empty_text, R.string.people_empty_action) {
                restartPrivateCallDiscovery()
            }
        }
    }

    private fun bindEmptyState(rootId: Int, icon: Int, title: Int, text: Int, action: Int, onAction: () -> Unit) {
        val root = findViewById<View>(rootId)
        root.findViewById<ImageView>(R.id.emptyStateIcon).setImageResource(icon)
        root.findViewById<TextView>(R.id.emptyStateTitle).setText(title)
        root.findViewById<TextView>(R.id.emptyStateText).setText(text)
        root.findViewById<TextView>(R.id.emptyStateAction).apply {
            setText(action)
            setOnClickListener { onAction() }
        }
    }

    /** Hides a browser's status line while its list is empty and the line only repeats the empty state. */
    private fun bindBrowserStatus(status: TextView, list: android.widget.ListView, generic: Set<String>) {
        val sync = {
            val empty = list.adapter == null || list.adapter.isEmpty
            val text = status.text?.toString().orEmpty()
            status.visibility = if (text.isBlank() || (empty && text in generic)) View.GONE else View.VISIBLE
        }
        status.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = sync()
        })
        list.viewTreeObserver.addOnGlobalLayoutListener { sync() }
        sync()
    }

    /** Nearby actions; the DEBUG control retains the earlier technical details. */
    private fun showPrivateCallPeerDetails(peer: BleL2capPeer) {
        if (DebugTunables.nearbyListUx) {
            val name = peerName(peer)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(name)
                .setItems(arrayOf(getString(R.string.nearby_action_call), getString(R.string.action_add_contact), getString(R.string.nearby_action_block)).let { if (DebugTunables.localReporting) it + getString(R.string.report_action) else it }) { _, which ->
                    when (which) {
                        0 -> withWifiCheck { choosePrivateCallType(peer) }
                        1 -> openAddContact(name)
                        2, 3 -> {
                            val prefix = recognisedContactPrefix(peer) ?: peer.announcement.nodeIdPrefix.toHexKey().takeIf { !peer.announcement.rotating }
                            val fingerprint = savedContacts.firstOrNull { it.nodeIdHex.startsWith(prefix ?: "-") }
                                ?.fingerprintHex?.let { runCatching { IdentityFingerprint.fromHex(it) }.getOrNull() }
                            if (which == 3) CallBlockingUi.report(this, fingerprint, onDone = ::updateRecentCalls)
                            else if (fingerprint != null) CallBlockingUi.confirmBlock(this, fingerprint, name)
                            else CallBlockingUi.afterDeclineAndBlock(this, null)
                        }
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .showTracked()
            return
        }
        val prefix = peer.announcement.nodeIdPrefix.toHexKey()
        val handle = if (peer.announcement.rotating) {
            getString(R.string.people_rotating_handle)
        } else {
            PeerLabels.forPeer(prefix, contactNames[prefix]).handle
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(peerName(peer))
            .setMessage(
                getString(
                    R.string.people_details,
                    handle,
                    if (peer.wifiOnly) "—" else peer.device.address,
                    peer.announcement.transportOption().shortLabel,
                    prefix,
                ),
            )
            .setPositiveButton(R.string.action_close, null)
            .showTracked()
    }

    private fun stopPrivateCallDiscovery() {
        autoSyncPendingKey?.let { cancelAutoSync(it, "discovery_stopped") }
        if (dialSelectionStartedAtMs != 0L && pendingPrivateCallPeerKey != null) {
            // A discovery restart (onResume, lifecycle, scan error) must not silently drop a dial (user tap or DEBUG)
            // that waits for its address refresh: it keeps waiting for the next sighting with the time it has left.
            val startedAtMs = dialSelectionStartedAtMs
            dialSelectionStartedAtMs = 0L
            val key = pendingPrivateCallPeerKey.orEmpty()
            debugDialWait = DebugDialWait.Pending(
                key, pendingPrivateCallType, pendingPrivateCallMessageSync, startedAtMs,
                displayName = pendingPrivateCallPeerName,
            )
            logCallInvite(DebugDialWait.line("waiting", key, "reason" to "discovery_restarted"))
        }
        pendingPrivateCallPeerKey = null
        privateCallDiscoveryHandler.removeCallbacksAndMessages(null)
        debugDialWait?.let {
            privateCallDiscoveryHandler.postDelayed(debugDialTimeout, it.remainingMs(SystemClock.elapsedRealtime()))
        }
        privateCallDiscovery?.close()
        privateCallDiscovery = null
        WifiDiscovery.setDemand(WifiDiscovery.Demand.CALLS_LIST, false)
    }

    private fun chatGate(): ChatGate {
        val gate = autoSyncGate()
        return ChatGate(
            callActive = gate.callActive, liveMedia = gate.liveMedia, exchangeRunning = gate.exchangeRunning,
            endpointBusy = !gate.endpointIdle || gate.userSelectionPending, thermalBlocked = gate.thermalBlocked,
        )
    }

    private fun logChatChange(key: String, state: String) {
        if (chatLastLog[key] == state) return
        chatLastLog[key] = state
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK message_v2 chat dial peer=$key $state")
    }

    /**
     * Messaging v2 chat session (phase 1d): called from the Call screen's discovery for each sighting. When that contact's
     * thread is open the exchange is started now (the lower node ID dials first; the other waits a few seconds, then dials
     * itself) and stays open as a chat session if the contact offers one. Live calls and streams always win through the gate.
     */
    private fun maybeChatSession(peer: BleL2capPeer, observedAtMs: Long) {
        if (peer.announcement.rotating) return
        val key = peer.announcement.nodeIdPrefix.toHexKey()
        val last = chatLastEvalMs[key]
        if (last != null && observedAtMs - last < CHAT_EVAL_INTERVAL_MS) return
        val controller = StoreForwardRuntime.chatIfCreated()
        if (controller == null) {
            // First use: the node opens off the UI thread, the next sighting decides.
            autoSyncExecutor.execute { runCatching { StoreForwardRuntime.chat(applicationContext) } }
            return
        }
        if (controller.presence.openPeers().isEmpty()) return
        chatLastEvalMs[key] = observedAtMs
        val node = StoreForwardRuntime.nodeIfOpen() ?: return
        val contact = StoreForwardAutoSyncPlanner.contactFor(node, peer.announcement.nodeIdPrefix) ?: return
        when (val decision = controller.dialDecision(contact.nodeId, chatGate())) {
            is ChatDial.Skip -> logChatChange(key, "skip why=${decision.why}")
            is ChatDial.Wait -> logChatChange(key, "wait why=${decision.why}")
            ChatDial.Start -> {
                if (pendingPrivateCallPeerKey != null) return
                chatLastLog.remove(key)
                controller.onDialStarted(contact.nodeId)
                chatDialPeer = contact.nodeId
                autoSyncPendingKey = key
                if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK message_v2 chat dial peer=$key start")
                startOutgoingPrivateCall(peer, messageSync = true, auto = true)
            }
        }
    }

    /** Every few seconds: end the chat sessions whose thread was left for 2 minutes. */
    private fun scheduleChatTick() {
        chatTickHandler.removeCallbacksAndMessages(null)
        if (!(MessagingV2Feature.enabled && MessagingV2ChatFeature.enabled)) return
        chatTickHandler.postDelayed({
            runCatching { StoreForwardRuntime.chatIfCreated()?.tick() }
            scheduleChatTick()
        }, CHAT_TICK_MS)
    }

    /**
     * DEBUG (test_messaging_v2): `sf2_thread:PREFIX12` opens the conversation screen of that saved contact (the Contacts tab
     * first), the way a tap on its row does; `sf2_thread_close` leaves it. Add `test_allow_screenshots` to the launch for
     * screenshots (the thread is `FLAG_SECURE` otherwise).
     */
    private fun debugOpenThread(prefix: String): Boolean {
        if (!BuildConfig.DEBUG) return false
        // The cached list is filled off the main thread and only on some events; a control may arrive before it (or after a
        // verification that was written while the app ran), so a miss reads the verified-contact store itself.
        val lookup = DebugContactLookup.find(
            prefix, savedContacts,
            { runCatching { ContactNames.sorted(SecurityUiBackend.current.contacts(applicationContext)) }.getOrDefault(emptyList()) },
        )
        val entry = lookup.entry
        if (entry == null) {
            Log.i("BluetoothVideo", "BENCHMARK message_v2 thread open rejected why=${lookup.why} prefix=${prefix.take(12)} cached=${savedContacts.size}")
            return false
        }
        if (lookup.fromStore) refreshContactNames()
        showContactsTab()
        openThread(entry.nodeIdHex)
        Log.i("BluetoothVideo", "BENCHMARK message_v2 thread open peer=${entry.prefixHex} fromStore=${lookup.fromStore}")
        return true
    }

    /**
     * DEBUG (test_messaging_v2): `sf2_chat_open:PREFIX12` marks that contact's thread as on screen (the discovery on the Call
     * screen then dials it), `sf2_chat_close[:PREFIX12]` leaves it (the session ends after 2 minutes),
     * `sf2_chat_status` logs the chat state, `sf2_keys:PREFIX12:accept|dismiss` resolves a key change.
     */
    private fun debugMessagingV2Chat(control: String): Boolean {
        if (!BuildConfig.DEBUG) return false
        val parts = control.split(':')
        val node = runCatching { StoreForwardRuntime.node(applicationContext) }.getOrNull() ?: return false
        val engine = node.v2 ?: return false
        fun contact(prefix: String?) =
            prefix?.lowercase(Locale.US)?.let { p -> node.contacts.accepted().singleOrNull { it.nodeId.toString().startsWith(p) } }
        when (parts[0]) {
            "sf2_chat_open" -> {
                val controller = StoreForwardRuntime.chat(applicationContext) ?: return false
                val peer = contact(parts.getOrNull(1)) ?: return false
                controller.presence.threadOpened(peer.nodeId)
                chatLastEvalMs.clear()
                Log.i("BluetoothVideo", "BENCHMARK message_v2 chat open peer=${peer.nodeId.shortLabel()}")
                return true
            }
            "sf2_chat_close" -> {
                val controller = StoreForwardRuntime.chat(applicationContext) ?: return false
                val targets = parts.getOrNull(1)?.let { listOfNotNull(contact(it)?.nodeId) } ?: controller.presence.openPeers()
                targets.forEach(controller.presence::threadClosed)
                Log.i("BluetoothVideo", "BENCHMARK message_v2 chat close peers=${targets.size}")
                return true
            }
            "sf2_chat_status" -> {
                val controller = StoreForwardRuntime.chat(applicationContext)
                val sessions = node.chatSessions().joinToString(",") { it.peerId.shortLabel() }
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK message_v2 chat status enabled=${controller != null} open=${controller?.presence?.openPeers()?.size ?: 0} " +
                        "sessions=[$sessions] foreground=${controller?.presence?.appForeground} " +
                        node.contacts.accepted().joinToString(" ") {
                            "${it.nodeId.shortLabel()}:${engine.linkState(it.nodeId).name.lowercase()}"
                        },
                )
                return true
            }
            "sf2_keys" -> {
                val peer = contact(parts.getOrNull(1)) ?: return false
                return when (parts.getOrNull(2)) {
                    "accept" -> engine.acceptKeyChange(peer.nodeId).also {
                        Log.i("BluetoothVideo", "BENCHMARK message_v2 keys accept adopted=${it.adopted} resealed=${it.resealedTexts} notSent=${it.notSent}")
                    }.adopted
                    "dismiss" -> node.contacts.dismissKeyChange(peer.nodeId)
                    else -> false
                }
            }
        }
        return false
    }

    private fun autoSyncGate(): AutoSyncGate = AutoSyncGate(
        onCallScreen = appScreen == AppScreen.CALL_BROWSER,
        endpointIdle = privateCall != null && privateCallAvailable && !privateCallAvailabilityStarting &&
            cocRebuildPeer == null && cocRetryPeer == null,
        callActive = privateCallPhase != null,
        liveMedia = sender != null || broadcaster != null || receiver != null || relay != null ||
            privateCallRelay != null || cameraEncoder != null || microphoneEncoder != null,
        exchangeRunning = privateCall?.messageSyncActive == true,
        userSelectionPending = pendingPrivateCallPeerKey != null,
        thermalBlocked = ThermalGuardPolicy.shouldBlockStreaming(thermalStatus),
    )

    private fun logAutoSync(event: String, key: String, detail: String = "") {
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK sf_sync auto=$event peer=$key${if (detail.isEmpty()) "" else " $detail"}")
    }

    /** Logs a wait/skip only when it changes for that peer, so continuous discovery does not flood the log. */
    private fun logAutoSyncChange(key: String, state: String) {
        if (autoSyncLastLog[key] == state) return
        autoSyncLastLog[key] = state
        logAutoSync(state.substringBefore(' '), key, state.substringAfter(' ', ""))
    }

    private fun autoSyncSchedulerOrCreate(): StoreForwardAutoSyncScheduler = autoSyncScheduler ?: StoreForwardAutoSyncScheduler(
        AutoSyncPolicy(),
        AndroidNodeIdentityStore().getOrCreate().nodeId.toByteArray()
            .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES).toHexKey(),
    ).also { autoSyncScheduler = it }

    /**
     * Experimental automatic sync: called from the Call screen's existing discovery for each sighting.
     * Gates on the UI thread, reads the store on one background thread (at most one evaluation at a
     * time, each peer at most every few seconds), then re-checks the gate before dialling.
     */
    private fun maybeAutoSync(peer: BleL2capPeer, observedAtMs: Long) {
        val key = peer.announcement.nodeIdPrefix.toHexKey()
        // Rotating IDs (RV3): a rotating ID never starts a connection by itself, before BMH3.
        if (peer.announcement.rotating) return
        val blocked = autoSyncGate().blockedBy()
        if (blocked != null) {
            logAutoSyncChange(key, "skip why=$blocked")
            return
        }
        val lastEval = autoSyncLastEvalMs[key]
        if (lastEval != null && observedAtMs - lastEval < AUTO_SYNC_EVAL_INTERVAL_MS) return
        if (!autoSyncEvaluating.compareAndSet(false, true)) return
        autoSyncLastEvalMs[key] = observedAtMs
        val scheduler = autoSyncSchedulerOrCreate()
        val prefix = peer.announcement.nodeIdPrefix.copyOf()
        val context = applicationContext
        autoSyncExecutor.execute {
            val outcome = runCatching {
                val node = StoreForwardRuntime.node(context)
                val ledger = StoreForwardRuntime.outboxLedger(context)
                val policy = AutoSyncPolicy()
                val need = StoreForwardAutoSyncPlanner.need(node, ledger, StoreForwardAutoSyncPlanner.contactFor(node, prefix), policy)
                need to scheduler.decide(key, SystemClock.elapsedRealtime(), need.reason(policy.pullEnabled), null)
            }
            autoSyncEvaluating.set(false)
            runOnUiThread {
                val (need, decision) = outcome.getOrElse { error ->
                    logAutoSyncChange(key, "skip why=error_${error.javaClass.simpleName}")
                    return@runOnUiThread
                }
                when (decision) {
                    is AutoSyncDecision.Skip -> logAutoSyncChange(key, "skip why=${decision.why}")
                    is AutoSyncDecision.Wait -> logAutoSyncChange(
                        key,
                        "wait reason=${decision.reason} failures=${scheduler.failures(key)} queued=${need.queued} failed=${need.failed}",
                    )
                    is AutoSyncDecision.Start -> {
                        val nowBlocked = autoSyncGate().blockedBy()
                        val current = privateCallPeers.firstOrNull { it.announcement.nodeIdPrefix.toHexKey() == key }
                        if (nowBlocked != null || current == null || !StoreForwardAutoSyncFeature.enabled) {
                            logAutoSyncChange(key, "skip why=${nowBlocked ?: "peer_gone"}")
                            return@runOnUiThread
                        }
                        autoSyncLastLog.remove(key)
                        scheduler.begin(key, SystemClock.elapsedRealtime())
                        autoSyncPendingKey = key
                        logAutoSync(
                            "start", key,
                            "reason=${decision.reason} failures=${scheduler.failures(key)} queued=${need.queued} " +
                                "failed=${need.failed} carried=${need.carried}",
                        )
                        startOutgoingPrivateCall(current, messageSync = true, auto = true)
                    }
                }
            }
        }
    }

    /** A user tap, a requested call or discovery stopping replaced an automatic start before it connected. */
    private fun cancelAutoSync(key: String, why: String) {
        if (autoSyncPendingKey != key) return
        autoSyncPendingKey = null
        if (pendingPrivateCallPeerKey == key) {
            pendingPrivateCallPeerKey = null
            pendingPrivateCallMessageSync = false
        }
        autoSyncScheduler?.cancel(key)
        logAutoSync("preempted", key, "why=$why")
    }

    /** Any exchange result, either role: attempt counts for still-queued messages, then the peer's backoff. */
    private fun onAutoSyncExchangeResult(result: StoreForwardSyncResult) {
        val context = applicationContext
        val key = StoreForwardAutoSyncScheduler.keyOf(result.peer)
        val scheduler = autoSyncSchedulerOrCreate()
        val auto = scheduler.inFlight() == key
        val progressed = StoreForwardAutoSyncScheduler.progressed(result)
        scheduler.onResult(key, SystemClock.elapsedRealtime(), result.outcome, progressed)
        autoSyncExecutor.execute {
            val attempts = runCatching {
                StoreForwardAutoSyncPlanner.recordExchange(
                    StoreForwardRuntime.node(context), StoreForwardRuntime.outboxLedger(context), result,
                )
            }.getOrElse { emptyMap() }
            val maxAttempts = AutoSyncPolicy().maxAttempts
            logAutoSync(
                "result", key,
                "initiated=$auto outcome=${result.outcome} progressed=$progressed " +
                    "failures=${scheduler.failures(key)} still_queued=${attempts.size} " +
                    "newly_failed=${attempts.values.count { it == maxAttempts }}",
            )
        }
    }

    /** Experimental voice calls: asks Voice call or Video call; otherwise a video call as before. */
    private fun choosePrivateCallType(peer: BleL2capPeer) {
        // Experimental automatic sync: the user's tap replaces an automatic start still refreshing.
        autoSyncPendingKey?.let { cancelAutoSync(it, "user_tap") }
        // Experimental store-and-forward: a live call takes the radio from a running exchange.
        if (privateCall?.suspendMessageSync() == true) {
            callsSnackbar(getString(R.string.call_waiting_for_messages))
            return
        }
        val messages = StoreForwardFeature.enabled
        if (!IntercomFeatures.current.voiceCalls && !messages) {
            startOutgoingPrivateCall(peer)
            return
        }
        val name = peerName(peer)
        if (!messages) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.call_choose_title, name))
                .setItems(arrayOf(getString(R.string.people_voice_call_generic), getString(R.string.people_video_call_generic))) { _, which ->
                    startOutgoingPrivateCall(peer, if (which == 0) CallType.VOICE else CallType.VIDEO)
                }
                .setNegativeButton(R.string.action_cancel, null)
                .showTracked()
            return
        }
        val options = buildList<Pair<String, () -> Unit>> {
            if (IntercomFeatures.current.voiceCalls) add("Voice call" to { startOutgoingPrivateCall(peer, CallType.VOICE) })
            add("Video call" to { startOutgoingPrivateCall(peer, CallType.VIDEO) })
            add("Exchange messages" to { startOutgoingPrivateCall(peer, messageSync = true) })
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Cancel", null)
            .showTracked()
    }

    @SuppressLint("MissingPermission")
    private fun startOutgoingPrivateCall(
        discoveredPeer: BleL2capPeer,
        callType: CallType = CallType.VIDEO,
        messageSync: Boolean = false,
        auto: Boolean = false,
        refreshTimeoutMs: Long = DebugDialWait.refreshTimeoutMs(auto),
        debugDial: Boolean = false,
    ) {
        // JT2 DEBUG: the tap (or the DEBUG dial command) is the first stage of the call this leads to; a dial of an
        // automatic exchange or of message sync is no tap (CallJoinStages.noteTap logs nothing until a dial follows).
        if (!auto && !messageSync) CallJoinStages.noteTap(if (debugDial) "debug" else "user")
        val heatType = ThermalGuardPolicy.callTypeAtHeat(callType, thermalStatus)
        if (!messageSync && heatType != callType) {
            // Calls at critical heat (DECISIONS 2026-11-06): a video call goes on as voice, without asking for the camera.
            showCallHeatNotice("place")
            startOutgoingPrivateCall(discoveredPeer, heatType, messageSync, auto, refreshTimeoutMs, debugDial)
            return
        }
        if (!messageSync && requestCallMedia(
                callType == CallType.VIDEO,
                CallMediaAccess.Use.PLACE,
                voiceInstead = {
                    // Camera refused (`test_call_voice_when_camera_refused`, on): the same call, placed as a voice call.
                    startOutgoingPrivateCall(discoveredPeer, CallType.VOICE, messageSync, auto, refreshTimeoutMs, debugDial)
                    true
                },
            ) {
                startOutgoingPrivateCall(discoveredPeer, callType, messageSync, auto, refreshTimeoutMs, debugDial)
            }) return
        if (pendingPrivateCallPeerKey != null) return
        // A new user tap replaces a dial still waiting for its person after a discovery restart.
        if (!auto) {
            cancelDebugDialWait()
            dialFailureNotice = null
        }
        if (discoveredPeer.relayRoute?.seek != null) {
            // Design OR: a search request is dialled as built (its first hop was revalidated at dial time).
            pendingPrivateCallType = callType
            pendingPrivateCallMessageSync = false
            connectOutgoingPrivateCall(discoveredPeer)
            return
        }
        if (discoveredPeer.relayRoute != null) {
            // Experimental relayed calls: the row comes from the reach table, not from a fresh scan result,
            // so use the freshest route to that target now instead of waiting for an address refresh.
            val fresh = RelayedCallsRuntime.peerFor(
                discoveredPeer.announcement.nodeIdPrefix,
                currentCallTransport,
                rotating = discoveredPeer.announcement.rotating,
            )
            if (fresh == null) {
                callsSnackbar(getString(R.string.relayed_route_gone))
                return
            }
            pendingPrivateCallType = callType
            pendingPrivateCallMessageSync = false
            connectOutgoingPrivateCall(fresh)
            return
        }
        if (discoveredPeer.wifiOnly) {
            WifiDiscovery.noteDialIntent()   // FIRST statement (main thread): keeps the Aware subscribe session alive after stopPrivateCallDiscovery() removes the Calls demand [BUG step 9.8]
            pendingPrivateCallType = callType
            pendingPrivateCallMessageSync = false
            connectOutgoingPrivateCall(discoveredPeer)
            return
        }
        pendingPrivateCallType = callType
        pendingPrivateCallMessageSync = messageSync && StoreForwardFeature.enabled
        val key = discoveredPeer.announcement.nodeIdPrefix.toHexKey()
        pendingPrivateCallPeerKey = key
        pendingPrivateCallTarget = DialPersonMatch.Target(
            key,
            if (discoveredPeer.announcement.rotating) recognisedContactPrefix(discoveredPeer) else null,
            discoveredPeer.device.address,
        )
        pendingPrivateCallPeerVersion = discoveredPeer.announcement.version
        pendingPrivateCallRouteCost = discoveredPeer.announcement.routeCost
        pendingPrivateCallSelectedAtMs = SystemClock.elapsedRealtime()
        // Every non-automatic dial (a user tap or a DEBUG dial) keeps waiting through a discovery restart.
        dialSelectionStartedAtMs = if (auto) 0L else pendingPrivateCallSelectedAtMs
        val dialName = if (auto) null else peerName(discoveredPeer)
        pendingPrivateCallPeerName = dialName
        // F14: a user's own dial (not an automatic exchange, not a DEBUG dial) makes other incoming calls busy meanwhile.
        ownDial = if (!auto && !debugDial) {
            OwnDial(
                advertKey = key,
                targetPrefix = if (discoveredPeer.announcement.rotating) recognisedContactPrefix(discoveredPeer) else key,
                name = dialName.orEmpty(),
                fixedPrefix = !discoveredPeer.announcement.rotating,
            )
        } else {
            null
        }
        val lastSeen = privateCallPeersLastSeen[key]
        val nowMs = SystemClock.elapsedRealtime()
        // JT2 DEBUG: the scan mode and how long ago this person was last sighted say what the tap waits for.
        logCallInvite(
            DebugDialWait.line(
                "refreshing_address", key, "timeoutMs" to refreshTimeoutMs, "debugDial" to debugDial, "userDial" to (!auto && !debugDial),
                "scanMode" to (privateCallDiscovery?.scanModeLabel() ?: "none"),
                "lastSeenAgeMs" to (lastSeen?.let { nowMs - it.second } ?: "never"),
            ),
        )
        // Experimental test_dial_recent_sighting (off): a row heard under 1.5 s ago is dialled now (RecentSightingDial).
        if (RecentSightingDial.decide(
                enabled = DebugTunables.dialRecentSighting,
                lastSeenAtMs = lastSeen?.second,
                nowMs = nowMs,
                rotating = discoveredPeer.announcement.rotating,
                wallMs = DiscoveryWallClock.nowMs(),
                epochSeconds = RotatingDiscoveryIds.epochSeconds,
            ) == RecentSightingDial.Decision.DIAL_NOW
        ) {
            val freshest = lastSeen?.first?.takeIf {
                DialRefreshGate.accepts(discoveredPeer.announcement.version, discoveredPeer.announcement.routeCost, it.announcement)
            } ?: discoveredPeer
            pendingPrivateCallPeerKey = null
            pendingPrivateCallTarget = null
            dialSelectionStartedAtMs = 0L
            logCallInvite(
                DebugDialWait.line(
                    "peer_seen", key, "waitedMs" to 0L, "matchedBy" to "recent",
                    "ageMs" to (nowMs - (lastSeen?.second ?: nowMs)), "dialled" to key,
                ),
            )
            connectOutgoingPrivateCall(freshest)
            return
        }
        privateCallDiscoveryHandler.postDelayed(
            {
                if (pendingPrivateCallPeerKey == key) {
                    pendingPrivateCallPeerKey = null
                    dialSelectionStartedAtMs = 0L
                    // A scanner in registration-failure back-off (GATT/scanner pool exhausted) explains a missing sighting.
                    val scanFailure = GattPoolBackoff.scannerFailureCode()
                    logCallInvite(
                        DebugDialWait.line(
                            "failed", key,
                            "reason" to if (scanFailure != null) "scanner_unavailable" else "refresh_timeout",
                            *DebugDialWait.expiryDetails(scanFailure, SystemClock.elapsedRealtime() - pendingPrivateCallSelectedAtMs),
                        ),
                    )
                    if (auto && autoSyncPendingKey == key) {
                        autoSyncPendingKey = null
                        pendingPrivateCallMessageSync = false
                        autoSyncScheduler?.onConnectFailed(key, SystemClock.elapsedRealtime())
                        logAutoSync("connect_failed", key, "detail=refresh_timeout")
                        refreshPrivateCallPeers()
                    } else {
                        // Their phone stopped offering calls: the row goes at once, and the status says so.
                        if (scanFailure == null) {
                            privateCallPeersLastSeen.remove(key)
                            callPeerCadence.forget(key)
                            callSightings.forget(key)
                            refreshPrivateCallPeers()
                        }
                        showDialNotFound(key, dialName ?: key, scanFailure)
                    }
                }
            },
            refreshTimeoutMs,
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectOutgoingPrivateCall(discoveredPeer: BleL2capPeer) {
        val peerKey = discoveredPeer.announcement.nodeIdPrefix.toHexKey()
        val autoSync = autoSyncPendingKey == peerKey
        if (autoSync && (autoSyncGate().copy(userSelectionPending = false).blockedBy() != null || privateCall == null)) {
            // Something took the radio or the endpoint between the decision and the refresh.
            cancelAutoSync(peerKey, "gate_closed")
            pendingPrivateCallMessageSync = false
            return
        }
        if (!pendingPrivateCallMessageSync) {
            // Calls at critical heat (DECISIONS 2026-11-06): the phone heated up while the dial refreshed; voice goes on.
            val heatType = ThermalGuardPolicy.callTypeAtHeat(pendingPrivateCallType, thermalStatus)
            if (heatType != pendingPrivateCallType) {
                pendingPrivateCallType = heatType
                showCallHeatNotice("dial")
            }
        }
        val endpoint = privateCall ?: return
        // A benchmark that requires a chain never calls directly (direct fallback is a failed chain).
        if (RelayedCallsFeature.enabled && RelayedCallsFeature.requireRelays > 0 && discoveredPeer.relayRoute == null) {
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "relayed_call: refused | reason=no_route_with_required_relays | require=${RelayedCallsFeature.requireRelays}",
            )
            callBrowserStatus.text = getString(R.string.relayed_chain_required)
            return
        }
        // Experimental relayed calls: this phone's own call ends a relay in progress first (PLAN C5); the call
        // starts as soon as the endpoint is available again (the same retry the CoC fallback uses).
        if (endpoint.relayingNow) {
            cocRetryPeer = discoveredPeer to pendingPrivateCallType
            endpoint.preemptRelay()
            return
        }
        // Experimental CoC -> GATT fallback (default off): a peer that advertises GATT (it fell back)
        // is called over GATT, so this endpoint is rebuilt on GATT for this call.
        if (CocAutoGatt.current.enabled && discoveredPeer.announcement.transportOption() ==
            BluetoothTransportOption.BLE_GATT && currentCallTransport != BluetoothTransportOption.BLE_GATT
        ) {
            if (autoSync) {
                // An automatic exchange never rebuilds the call endpoint; a tapped Exchange still can.
                cancelAutoSync(peerKey, "needs_transport_rebuild")
                pendingPrivateCallMessageSync = false
                refreshPrivateCallPeers()
                return
            }
            val gattChoice = CocRegistrations.session.onPeerAdvertisesGatt(outgoingTransport, CocAutoGatt.current)
            if (gattChoice != null) {
                logCocTransportChoice("peer_advertises_gatt", gattChoice, discoveredPeer.announcement.nodeIdPrefix.toHexKey())
                val type = pendingPrivateCallType
                stopPrivateCallEndpoint()
                cocRebuildPeer = discoveredPeer to type
                showCocTransportNotice(gattChoice)
                startPrivateCallEndpoint(gattChoice)
                return
            }
        }
        val peer = if (outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM && !discoveredPeer.wifiOnly) {
            resolveClassicViewerPeers(listOf(discoveredPeer)).first()
        } else {
            discoveredPeer
        }
        autoSyncPendingKey = null
        stopPrivateCallDiscovery()
        if (pendingPrivateCallMessageSync) {
            pendingPrivateCallMessageSync = false
            val peerName = peerName(peer)
            val syncing = runCatching { endpoint.startMessageSync(peer) }.getOrDefault(false)
            if (!syncing) callsSnackbar(getString(R.string.call_end_messages_failed))
            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "message exchange with $peerName: started=$syncing")
            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK store_forward sync_start peer=${peer.label} started=$syncing")
            if (autoSync) {
                logAutoSync("connecting", peerKey, "started=$syncing")
                if (!syncing) autoSyncScheduler?.onConnectFailed(peerKey, SystemClock.elapsedRealtime())
            }
            return
        }
        // Not a message exchange (an automatic start never reaches here): release its slot.
        if (autoSync) autoSyncScheduler?.cancel(peerKey)
        // Only calls are recorded for the experimental CoC -> GATT call retry, never a message exchange.
        lastOutgoingCallPeer = discoveredPeer to pendingPrivateCallType
        val started = runCatching { endpoint.startOutgoing(peer, pendingPrivateCallType) }.getOrElse { error ->
            if (privateCall === endpoint) {
                stopPrivateCallEndpoint()
                showPrivateCallBrowser()
                Log.w("BluetoothVideo", "Call could not start", error)
                callsSnackbar(getString(R.string.call_couldnt_start))
            }
            return
        }
        if (!started) {
            if (privateCall === endpoint && appScreen == AppScreen.CALL_BROWSER) {
                callsSnackbar(getString(R.string.call_incoming_busy))
            }
            return
        }
        Log.i("BluetoothVideo", "Calling ${peer.label}")
        val peerName = peerName(peer)
        showPrivateCallScreen("Calling $peerName...")
        privateCallDialledRelays = advertisedRelayCount(discoveredPeer.announcement.routeCost)
        val voice = endpoint.localCallType == CallType.VOICE
        if (!voice) showCallParty(peerName, R.string.call_outgoing)
        val dialledPrefix = discoveredPeer.announcement.nodeIdPrefix.toHexKey()
        watchCallPeerName(null) {
            if (discoveredPeer.announcement.rotating) peerName else incomingPeerName(dialledPrefix)
        }
        if (voice) intercomCallUi.showPendingVoice(incoming = false)
        incomingCallControls.visibility = View.GONE
        activeCallControls.visibility = View.GONE
        cancelCallButton.visibility = View.VISIBLE
        ActiveVideoSessionService.start(
            this,
            usesCamera = !voice,
            statusText = "Calling over encrypted Bluetooth",
            usesMicrophone = true,
            ongoingCallStyle = CallUx.settings.callStyleNotifications,
            peerName = peerName,
        )
    }

    private fun updatePrivateCallUi(callState: PrivateCallState) {
        privateCallPhase = callState.phase
        VoiceNoteGate.callPhase = callState.phase.takeIf { it != PrivateCallPhase.ENDED }
        callLockScreen.apply(callState.phase.takeIf { it != PrivateCallPhase.ENDED })
        updatePrivateCallRingback(callState.phase)
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK call phase ${callState.phase.name}")
        callScreenController.setMediaPhase(
            callState.phase == PrivateCallPhase.NEGOTIATING_MEDIA || callState.phase == PrivateCallPhase.ACTIVE,
        )
        if (DebugTunables.callPrewarm) {
            if (CallPrewarm.releasesAt(callState.phase)) {
                privateCallPrewarm.release(callState.phase.name.lowercase())
            } else if (CallPrewarm.preparesAt(true, callState.phase, privateCall?.localCallType == CallType.VIDEO)) {
                prewarmPrivateCallEncoder()
            }
        }
        when (callState.phase) {
            PrivateCallPhase.OUTGOING_INVITE -> {
                setCallStatus("Connecting encrypted call...")
                if (::callPartyState.isInitialized) callPartyState.setText(R.string.call_outgoing)
            }
            PrivateCallPhase.OUTGOING_RINGING -> {
                setCallStatus(getString(R.string.call_status_ringing))
                if (::callPartyState.isInitialized) callPartyState.setText(R.string.call_ringing)
            }
            PrivateCallPhase.INCOMING_RINGING -> Unit
            PrivateCallPhase.NEGOTIATING_MEDIA -> {
                if (privateCallMediaStartedAtNs == 0L) privateCallMediaStartedAtNs = System.nanoTime()
                hideCallParty()
                stopPrivateCallRinging()
                incomingCallControls.visibility = View.GONE
                cancelCallButton.visibility = View.GONE
                activeCallControls.visibility = View.VISIBLE
                setCallStatus(
                    getString(
                        if (privateCall?.localCallType == CallType.VOICE) R.string.call_status_starting_voice
                        else R.string.call_status_starting_video,
                    ),
                )
            }
            PrivateCallPhase.ACTIVE -> {
                if (!DebugTunables.completeRecentCalls) recordRecentCall()
                findViewById<android.widget.Chronometer>(R.id.callTopTimer).apply {
                    if (visibility != View.VISIBLE) {
                        base = SystemClock.elapsedRealtime()
                        start()
                        visibility = View.VISIBLE
                    }
                }
                stopPrivateCallRinging()
                incomingCallControls.visibility = View.GONE
                cancelCallButton.visibility = View.GONE
                activeCallControls.visibility = View.VISIBLE
                setCallStatus("Encrypted private call")
                hideCallParty()
                updateCallSecurityChip()
                // Bars fade while video plays; voice-only calls keep them (nothing to look at).
                callChromeAutoHide.enabled = privateCall?.localCallType != CallType.VOICE
            }
            PrivateCallPhase.ENDED -> {
                callRemotePreview.removeCallbacks(privateCallNonBlackSampler)
                CallJoinStages.nonBlack.finish("call_ended")
                CallJoinStages.current.end()
                callChromeAutoHide.reset()
                hideCallParty()
                // A stranger who revealed only after the entry was recorded: keep the link for a later verification.
                privateCallStrangerRecent?.let { stranger ->
                    privateCall?.revealedPeerFingerprint?.let { StrangerRecentLinks.remember(it.hex, stranger) }
                }
                privateCallStrangerRecent = null
            }
        }
    }

    private fun startPrivateCallMedia() {
        if (privateCallCameraEncoder != null || privateCallMicrophoneEncoder != null) return
        val endpoint = privateCall ?: return
        val codec = privateCallCodec ?: return
        val callSettings = privateCallSettings ?: return
        val referenceFrames = runCatching {
            if (debugRawInputFile != null) requireNativeRawVideoMode(settings, callSettings)
            referenceAudioVideoFrames(callSettings)
        }.getOrElse { error ->
            Log.i("BluetoothVideo", "BENCHMARK native call input rejected: ${error.message}")
            if (debugRawInputFile != null && (settings.resolution.width != callSettings.resolution.width ||
                    settings.resolution.height != callSettings.resolution.height || settings.fps != callSettings.fps)) {
                Log.w(
                    "BluetoothVideo",
                    BenchmarkSetupRefusal.rawFixtureGeometryLine(
                        settings.resolution.width, settings.resolution.height,
                        callSettings.resolution.width, callSettings.resolution.height,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
            stopPrivateCallEndpoint("Call native input could not start: ${error.message}")
            return
        }
        activeStreamSettings = callSettings
        // Experimental voice calls: VOICE starts no camera or video encoder (VIDEO when off).
        val callType = endpoint.localCallType
        val startMuted = intercomCallUi.startMedia(callType, IntercomFeatures.current)
        // JT2 (test_call_camera_first, promoted 2026-11-12, default on): the camera before the audio route, the route open queued on the audio
        // command thread behind it and the microphone after that ([CallMediaStartOrder]). Off: the order of before, step for
        // step. Either way this runs only after Accept and the block check (the caller of startPrivateCallMedia is unchanged).
        val cameraFirst = DebugTunables.callCameraFirst
        val started = CallMediaStartOrder.run(cameraFirst, video = callType == CallType.VIDEO) { step ->
            when (step) {
                CallMediaStartOrder.Step.FOREGROUND_SERVICE -> {
                    ActiveVideoSessionService.start(
                        this,
                        usesCamera = callType == CallType.VIDEO,
                        statusText = if (callType == CallType.VOICE) "Encrypted private voice call" else "Encrypted private video call",
                        usesMicrophone = true,
                        ongoingCallStyle = CallUx.settings.callStyleNotifications,
                        peerName = callRealName?.invoke(),
                    )
                    privateCallFocusHold = CallFocusHold().also { it.onUserMuted(startMuted) }
                    true
                }
                CallMediaStartOrder.Step.AUDIO_SESSION -> {
                    privateCallAudioSession = PrivateCallAudioSession(
                        this,
                        startOnSpeaker = intercomCallUi.routePolicy?.initialSpeakerOn ?: true,
                        onRouteChanged = { runOnUiThread { updatePrivateCallControlLabels() } },
                        allowBluetoothOutput = CallBluetoothOutputFeature.enabled,
                        // Audio focus (CallAudioFocus): on for user sessions; benchmark launches keep the former
                        // no-focus behaviour unless test_call_audio_focus opts in.
                        onFocusAction = if (CallUx.settings.audioFocus) ::onPrivateCallFocusAction else null,
                        openQueued = cameraFirst,
                    )
                    // Volume keys change the call volume even in a silent moment of the call.
                    volumeControlStream = AudioManager.STREAM_VOICE_CALL
                    true
                }
                CallMediaStartOrder.Step.MUTE_STATE -> {
                    // PTT mode (experimental) starts muted on the existing mute path, before the microphone runs.
                    privateCallMuted = startMuted
                    if (startMuted) endpoint.setMuted(true)
                    true
                }
                CallMediaStartOrder.Step.CAMERA_FLAGS -> {
                    privateCallCameraEnabled = callType == CallType.VIDEO
                    privateCallCameraPausedAtEar = false
                    privateCallProximityPause = if (CallUx.settings.proximityCameraPause) {
                        CallProximityCameraPause(this, ::onPrivateCallProximityPause).also { it.start() }
                    } else {
                        null
                    }
                    intercomCallUi.layout?.let { layout ->
                        endpoint.logCallMode("media_start", intercomCallUi.localVideo, intercomCallUi.remoteVideo, layout)
                    }
                    true
                }
                CallMediaStartOrder.Step.LOCAL_PREVIEW -> {
                    showPrivateCallLocalPreview()
                    true
                }
                CallMediaStartOrder.Step.CONTROL_LABELS -> {
                    updatePrivateCallControlLabels()
                    true
                }
                CallMediaStartOrder.Step.MICROPHONE_START -> {
                    lateinit var audioEncoder: MicrophoneOpusEncoder
                    audioEncoder = MicrophoneOpusEncoder(
                        debugImpairedSend(endpoint::send, call = true),
                        { message -> Log.i("BluetoothVideo", message) },
                        { message ->
                            runOnUiThread {
                                if (privateCallMicrophoneEncoder === audioEncoder) {
                                    stopPrivateCallEndpoint("Call microphone stopped: $message")
                                }
                            }
                        },
                        communicationMode = true,
                        bitRate = privateCallActiveAudioBitRate ?: privateCallAudioBitRate(
                            outgoingTransport,
                            privateCallStackFingerprint(),
                        ),
                        debugReferenceFile = debugReferenceAudioFile,
                        debugReferenceVideoFrames = referenceFrames,
                        debugReferenceVideoFps = callSettings.fps,
                        // A voice call has no camera encoder to supply the raw-fixture clock; the reference
                        // audio then waited forever and sent no audio at all (voice-ptt-e151a61f). Voice calls
                        // start their own audio-only clock.
                        debugReferenceClock = if (privateCall?.localCallType == CallType.VOICE) {
                            val voiceClock = RawReferenceClock(ptsOriginUs = 0L, monotonicOriginNs = System.nanoTime())
                            ({ voiceClock })
                        } else {
                            ({ privateCallCameraEncoder?.benchmarkRawClock })
                        },
                        debugTraceContext = this,
                        debugTraceSession = debugMediaTraceSession,
                        hooks = AudioCaptureHooks(
                            context = this,
                            routeIsLoudspeaker = { privateCallAudioSession?.output == CallOutput.SPEAKER },
                            // Called on the capture thread: notices (silent microphone, privacy switch, no echo cancellation) go to a toast.
                            onNotice = { notice ->
                                runOnUiThread { if (!isDestroyed) Toast.makeText(this, notice.text, Toast.LENGTH_LONG).show() }
                            },
                        ),
                    )
                    privateCallMicrophoneEncoder = audioEncoder
                    if (cameraFirst) {
                        // JT2: always queued, so it follows the queued call_audio_open (the capture opens in the call's audio
                        // mode: platform echo cancellation) even when the off-main switch is off; a failure ends the call on the UI thread.
                        audioEncoder.startQueued { error ->
                            if (error != null) runOnUiThread { stopPrivateCallEndpoint("Call microphone could not start: ${error.message}") }
                        }
                        true
                    } else if (AudioSwitches.offMainThread) {
                        // Experimental (test_audio_off_main): the microphone opens on the audio command thread; the call goes on and a
                        // failure ends it on the UI thread.
                        audioEncoder.startOffMain { error ->
                            if (error != null) runOnUiThread { stopPrivateCallEndpoint("Call microphone could not start: ${error.message}") }
                        }
                        true
                    } else {
                        runCatching { audioEncoder.start() }.onFailure { error ->
                            stopPrivateCallEndpoint("Call microphone could not start: ${error.message}")
                        }.isSuccess
                    }
                }
                CallMediaStartOrder.Step.CAMERA_START -> {
                    startPrivateCallCamera(codec, callSettings)
                    true
                }
            }
        }
        if (!started) return
        // A voice answer, the camera off or too hot: a pre-warmed encoder nobody took is released now.
        if (DebugTunables.callPrewarm) privateCallPrewarm.release("not_used")
    }

    private fun startPrivateCallCamera(codec: VideoCodec, callSettings: VideoSettings) {
        val endpoint = privateCall ?: return
        if (!privateCallCameraEnabled || privateCallCameraEncoder != null) return
        if (!ThermalGuardPolicy.callVideoAllowed(thermalStatus)) {
            // Media start, an ear or link resume, a lens switch or a ladder restart at critical heat: voice goes on.
            stopPrivateCallVideoForHeat()
            return
        }
        val corruptTestFrame = if (BuildConfig.DEBUG) intent.getIntExtra("test_corrupt_call_video_frame", 0) else 0
        var testFrameCount = 0
        val send = debugImpairedSend(endpoint::send, call = true)
        // Call resolution ladder: the rung for the current link; the call header keeps callSettings.
        val encoderSettings = privateCallResolutionLadder?.let { callSettings.copy(resolution = it.current) } ?: callSettings
        // A ladder restart changes the coded size: its first keyframe tells the viewer that every frame
        // before it belongs to the old picture (the viewer re-creates its decoder; see DecoderFormatTracker).
        val sentBefore = privateCallSentResolution
        val restartMarker = RestartDiscontinuityMarker(
            sentBefore != null && (sentBefore.width != encoderSettings.resolution.width ||
                sentBefore.height != encoderSettings.resolution.height),
        )
        var sentVideo = false
        lateinit var encoder: CameraVideoEncoder
        val onPacket: (Int, Long, ByteArray) -> Unit = { flags, timeUs, bytes ->
            if (privateCallCameraEncoder === encoder) {
                if (flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) testFrameCount++
                if (!sentVideo && flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                    sentVideo = true
                    privateCallSentResolution = encoderSettings.resolution
                }
                if (corruptTestFrame > 0 && testFrameCount == corruptTestFrame &&
                    flags and StreamProtocol.FLAG_CODEC_CONFIG == 0
                ) {
                    Log.i("BluetoothVideo", "DEBUG: injecting one invalid call video frame $testFrameCount")
                    send(flags, timeUs, byteArrayOf(0))
                } else {
                    send(restartMarker.flagsFor(flags), timeUs, bytes)
                }
                if (flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                    CallJoinStages.mark(CallJoinStage.FIRST_FRAME_SENT, "keyFrame=${flags and StreamProtocol.FLAG_KEY_FRAME != 0}")
                }
            }
        }
        val onFailure: (Throwable) -> Unit = { error ->
            runOnUiThread {
                if (privateCallCameraEncoder === encoder) {
                    stopPrivateCallEndpoint("Call camera stopped: ${error.message}")
                }
            }
        }
        // Experimental call pre-warm (test_call_prewarm, off): the encoder prepared while ringing, for exactly this
        // profile; otherwise (and always with the switch off) the encoder is built here as before.
        val prewarmed = if (DebugTunables.callPrewarm) {
            privateCallPrewarm.take(callPrewarmKey(codec, encoderSettings, privateCallGpuScale))
        } else {
            null
        }
        encoder = prewarmed?.encoder ?: newPrivateCallCameraEncoder(codec, encoderSettings, privateCallGpuScale, onPacket, onFailure)
        // F16 fix 2 (Experimental, off): an Automatic call follows the camera's real rate in sustained low light.
        if (DebugTunables.callFpsFollow && encoderRateControl == EncoderRateControl.AUTOMATIC) {
            encoder.fpsFollow = CallFpsFollow(callSettings.fps)
        }
        privateCallCameraEncoder = encoder
        // A pre-warmed encoder's held codec configuration goes out now, through the same identity check as every packet.
        prewarmed?.sink?.attach(onPacket, onFailure)
        // The learned call start (and any thermal limit) before the encoder configures, not after a second.
        // (A pre-warmed encoder is already configured: the same calls apply the start rate before its first frame.)
        if (startRateGuardOn() && privateCallLinkRateLimitBps != null) applyThermalAdaptation(thermalStatus)
        startPrivateCallRawSelfView(encoder, encoderSettings)
        privateCallCameraExecutor.execute {
            if (privateCallCameraEncoder !== encoder) return@execute
            val startedNs = System.nanoTime()
            runCatching { encoder.start() }.onSuccess {
                Log.i("BluetoothVideo", "Private call camera start: %.1f ms on %s".format(
                    (System.nanoTime() - startedNs) / 1_000_000.0, Thread.currentThread().name,
                ))
                runOnUiThread {
                    if (privateCallCameraEncoder === encoder) applyThermalAdaptation(thermalStatus)
                }
            }.onFailure { error ->
                encoder.close()
                runOnUiThread {
                    if (privateCallCameraEncoder === encoder) {
                        privateCallCameraEncoder = null
                        if (encoderSettings.resolution != callSettings.resolution && privateCallResolutionLadder != null) {
                            // A ladder rung failed: drop the ladder for this call and keep the start geometry.
                            privateCallResolutionLadder = null
                            logCallResolution(
                                "requested=${callSettings.resolution.width}x${callSettings.resolution.height} " +
                                    "effective=${callSettings.resolution.width}x${callSettings.resolution.height} " +
                                    "reason=rung_failed(${encoderSettings.resolution.width}x${encoderSettings.resolution.height})",
                            )
                            startPrivateCallCamera(codec, callSettings)
                        } else {
                            stopPrivateCallEndpoint("Call camera could not start: ${error.message}")
                        }
                    }
                }
            }
        }
    }

    /** The call's camera encoder for [encoderSettings]; the one constructor for media start and the call pre-warm. */
    private fun newPrivateCallCameraEncoder(
        codec: VideoCodec,
        encoderSettings: VideoSettings,
        gpuScale: GpuScaleRequest?,
        onPacket: (Int, Long, ByteArray) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): CameraVideoEncoder = CameraVideoEncoder(
        this,
        callLocalViewfinder,
        cameraLens,
        codec,
        encoderSettings,
        referenceFrameLimit = 0,
        debugTraceSessionName = debugMediaTraceSession,
        onPacket = onPacket,
        onStatus = { message -> Log.i("BluetoothVideo", "Call camera: $message") },
        onFailure = onFailure,
        rateControl = encoderRateControl,
        scaleMode = videoScaleMode,
        debugRawInputFile = debugRawInputFile,
        rawGeometry = debugRawGeometry,
        gpuScale = gpuScale,
        onSoftwareShortfall = softwareShortfallListener("call"),
        onNativeOverloadAtFastest = if (DebugTunables.callEncoderFit) {
            { key ->
                CallEncoderFitPreferences(this).recordOverload(key)
                Log.i("BluetoothVideo", "BENCHMARK call_encoder_fit: overloaded=$key | remembered for later calls")
            }
        } else {
            null
        },
        onJoinStage = if (BuildConfig.DEBUG) { stage, detail -> CallJoinStages.mark(stage, detail) } else null,
        parallelOpen = DebugTunables.callCameraFirst,
    )

    /** Everything [newPrivateCallCameraEncoder] builds from: a pre-warmed encoder is used only for an exact match. */
    private fun callPrewarmKey(codec: VideoCodec, encoderSettings: VideoSettings, gpuScale: GpuScaleRequest?) = CallPrewarmKey(
        codec, encoderSettings, cameraLens, encoderRateControl, videoScaleMode, gpuScale, debugRawInputFile?.path,
    )

    /**
     * Experimental call pre-warm ([CallPrewarm]): while this video call rings, the encoder for the profile media start
     * would use (the endpoint's camera mode and encoder fit, the call ladder's start rung and GPU scale as they would be
     * chosen now) is created and prepared on the camera executor. No camera opens here: only [startPrivateCallCamera],
     * after Accept, starts one. A learned start rate below the request may pick a lower ladder rung at media start; the
     * keys then differ and the call builds its encoder as today.
     */
    private fun prewarmPrivateCallEncoder() {
        val endpoint = privateCall ?: return
        if (privateCallCameraEncoder != null || !ThermalGuardPolicy.callVideoAllowed(thermalStatus)) return
        val (callSettings, codec) = endpoint.prewarmVideoProfile() ?: return
        val gpuScale = if (gpuScaleOffReason(codec, referenceFrames = false) == null &&
            gpuScaleModes(codec, callSettings.bitRate, cameraLens, callSettings.resolution, callSettings.fps).isNotEmpty()
        ) {
            GpuScaleRequest(callSettings.resolution, callSettings.fps)
        } else {
            null
        }
        val ladderOn = CallResolutionLadderFeature.enabled && encoderRateControl == EncoderRateControl.AUTOMATIC &&
            debugRawInputFile == null
        val startRung = if (ladderOn) {
            buildPrivateCallResolutionLadder(callSettings, codec, gpuScale, logFailure = false)?.takeIf { it.active }?.start(
                ThermalGuardPolicy.maximumBitrate(callSettings.bitRate, thermalStatus, thermalReductionOn),
                SystemClock.elapsedRealtime(),
            )?.resolution
        } else {
            null
        }
        val encoderSettings = startRung?.let { callSettings.copy(resolution = it) } ?: callSettings
        val offered = privateCallPrewarm.offer(callPrewarmKey(codec, encoderSettings, gpuScale)) { sink ->
            newPrivateCallCameraEncoder(codec, encoderSettings, gpuScale, sink::onPacket, sink::onFailure)
        }
        if (offered) privateCallDiscoveryHandler.postDelayed({ privateCallPrewarm.expire() }, CallPrewarm.MAX_HOLD_MS)
    }

    /**
     * DEBUG raw-input self-view: the raw fixture frame the encoder is reading, at a low rate on its
     * own background thread. `test_call_raw_self_view_fps` (default 5, 0 = off) controls it.
     */
    private fun startPrivateCallRawSelfView(encoder: CameraVideoEncoder, callSettings: VideoSettings) {
        privateCallRawSelfView?.close()
        privateCallRawSelfView = null
        val file = debugRawInputFile ?: return
        if (!BuildConfig.DEBUG || debugCallRawSelfViewFps <= 0) return
        privateCallRawSelfView = CallRawSelfView(
            file,
            callSettings.resolution.width,
            callSettings.resolution.height,
            callSettings.fps,
            debugCallRawSelfViewFps,
            clock = { encoder.benchmarkRawClock },
            onFrame = { bitmap ->
                if (privateCallCameraEncoder === encoder) callScreenController.showRawFrame(bitmap)
            },
        ).also { selfView ->
            selfView.setPaused(!activityResumed)
            selfView.start()
        }
    }

    /**
     * DEBUG join stage: the first remote picture reached the call's TextureView. Whether it was black is read once from an
     * 8x8 scaled copy (one small GPU readback per call); null when the copy is unavailable.
     */
    private fun markPrivateCallFirstShown() {
        val luma = readPrivateCallRemoteLuma()
        CallJoinStages.mark(
            CallJoinStage.FIRST_FRAME_SHOWN,
            if (luma == null) "black=unknown" else "black=${CallJoinStages.black(luma)} | meanLuma=${"%.1f".format(Locale.US, luma)}",
        )
        // A black first picture (a dark scene) keeps being sampled until one has content: first_nonblack_shown.
        if (CallJoinStages.nonBlack.onFirstShown(luma)) {
            callRemotePreview.postDelayed(privateCallNonBlackSampler, FirstNonBlackWatch.SAMPLE_INTERVAL_MS)
        }
    }

    /** The 8x8 mean-luma read of the shown remote picture; null when the copy is unavailable. */
    private fun readPrivateCallRemoteLuma(): Double? = runCatching {
        callRemotePreview.getBitmap(8, 8)?.let { bitmap ->
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()
            CallJoinStages.meanLuma(pixels)
        }
    }.getOrNull()

    /** DEBUG: the 200 ms sampler behind first_nonblack_shown; it stops itself when the stage is logged or the call ends. */
    private val privateCallNonBlackSampler = object : Runnable {
        override fun run() {
            if (CallJoinStages.nonBlack.onSample(readPrivateCallRemoteLuma())) {
                callRemotePreview.postDelayed(this, FirstNonBlackWatch.SAMPLE_INTERVAL_MS)
            }
        }
    }

    /** Shows the self-view the current input supports: the camera viewfinder or the raw input. */
    private fun showPrivateCallLocalPreview() {
        val preview = when {
            !privateCallCameraEnabled -> CallScreenController.LocalPreview.NONE
            debugRawInputFile == null -> CallScreenController.LocalPreview.CAMERA
            BuildConfig.DEBUG && debugCallRawSelfViewFps > 0 -> CallScreenController.LocalPreview.RAW
            else -> CallScreenController.LocalPreview.NONE
        }
        callScreenController.setLocalPreview(preview, videoScaleMode)
    }

    private fun stopPrivateCallCamera() {
        privateCallRawSelfView?.close()
        privateCallRawSelfView = null
        val encoder = privateCallCameraEncoder ?: return
        privateCallCameraEncoder = null
        privateCallCameraExecutor.execute {
            val startedNs = System.nanoTime()
            encoder.close()
            Log.i("BluetoothVideo", "Private call camera close: %.1f ms on %s".format(
                (System.nanoTime() - startedNs) / 1_000_000.0, Thread.currentThread().name,
            ))
        }
    }

    /**
     * Experimental push-to-talk (touch, accessibility toggle or DEBUG `ptt_press`/`ptt_release`):
     * holding unmutes and releasing mutes through the existing mute path. Returns true if applied.
     */
    private fun onPrivateCallPushToTalk(pressed: Boolean, source: String): Boolean {
        val endpoint = privateCall ?: return false
        val ptt = intercomCallUi.pushToTalk ?: return false
        if (privateCallPhase != PrivateCallPhase.NEGOTIATING_MEDIA && privateCallPhase != PrivateCallPhase.ACTIVE) {
            return false
        }
        val result = if (pressed) ptt.press() else ptt.release()
        if (!result.accepted) return false
        privateCallMuted = ptt.muted
        endpoint.pushToTalk(pressed, result.mutedChange?.let(privateCallFocusHold::onUserMuted), source)
        intercomCallUi.playCue(pressed)
        updatePrivateCallControlLabels()
        return true
    }

    /**
     * A voice call whose other phone answered with its camera and never signalled its camera state:
     * its app has no voice calls (an older build, or the switch off). Said once per call; the call
     * simply continues as a call where only the other person has video.
     */
    private fun showPeerLacksVoiceNotice() {
        val name = privateCall?.endToEndPeerNodeId?.take(12)?.let { callLockScreen.displayName(incomingPeerName(it)) } ?: return
        Log.i("BluetoothVideo", "Call voice: peer answered a voice call with video (no voice-call support)")
        com.google.android.material.snackbar.Snackbar
            .make(callScreen, getString(R.string.call_peer_no_voice, name), com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .show()
    }

    /** Voice calls: the ringing call's caller offered (or withdrew) a voice call. */
    private fun onIncomingPrivateCallType(type: CallType) {
        incomingCallType = type
        if (privateCallPhase != PrivateCallPhase.INCOMING_RINGING) return
        val acceptButton = findViewById<View>(R.id.acceptCallButton)
        if (type == CallType.VOICE) {
            intercomCallUi.showPendingVoice(incoming = true)
            hideCallParty() // the voice panel shows the caller
            setCallStatus("Incoming encrypted voice call")
            acceptButton.contentDescription = "Accept the voice call. Your camera stays off."
        } else {
            intercomCallUi.hidePendingVoice()
            setCallStatus("Incoming encrypted call")
            acceptButton.contentDescription = null
        }
    }

    /** Experimental voice calls: voice/video layout changed; the route follows unless chosen. */
    private fun onPrivateCallLayoutChanged(event: String) {
        val layout = intercomCallUi.layout ?: return
        intercomCallUi.routePolicy?.speakerForLayout(layout)?.let { speaker ->
            privateCallAudioSession?.setSpeakerOn(speaker, explicit = false)
        }
        privateCall?.logCallMode(event, intercomCallUi.localVideo, intercomCallUi.remoteVideo, layout)
        updatePrivateCallControlLabels()
    }

    /**
     * While muted, press and hold Mute to talk; releasing mutes again (the existing mute path). Part of
     * every call (no setting). Not used while the launch-extra push-to-talk button owns the microphone.
     * Screen readers: "double tap and hold" reaches the touch listener as a real hold, and the custom
     * action "Talk" starts and stops talking with two activations.
     */
    private var holdToTalkGesture: HoldToTalkGesture? = null

    private fun bindHoldMuteToTalk() {
        val gesture = HoldToTalkGesture(
            canStart = { privateCallMuted && intercomCallUi.pushToTalk == null && privateCall != null },
            onTalk = { talking ->
                val endpoint = privateCall ?: return@HoldToTalkGesture
                // While another call holds the microphone (audio focus hold) the call stays muted.
                val effectiveMuted = privateCallFocusHold.onUserMuted(!talking)
                endpoint.setMuted(effectiveMuted)
                val open = talking && !effectiveMuted
                Log.i(
                    "BluetoothVideo",
                    "Call hold-mute-to-talk: ${if (open) "talking" else if (talking) "blocked_by_focus_hold" else "muted"}",
                )
                callMuteLabel.setText(if (open) R.string.call_talking else R.string.call_unmute)
                callMuteButton.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                if (talking && effectiveMuted) setCallStatus("On hold: another call is using the microphone")
                // TalkBack reads a state change on the focused button.
                androidx.core.view.ViewCompat.setStateDescription(
                    callMuteButton,
                    getString(if (open) R.string.call_talking else R.string.call_muted_again),
                )
            },
        )
        holdToTalkGesture = gesture
        callMuteButton.setOnTouchListener(gesture)
        androidx.core.view.ViewCompat.addAccessibilityAction(callMuteButton, getString(R.string.call_talk_action)) { _, _ ->
            if (privateCallMuted && intercomCallUi.pushToTalk == null && privateCall != null) {
                gesture.toggleLatched()
                true
            } else {
                false
            }
        }
    }

    /** The first time the user mutes, a one-line tip about holding Mute to talk. */
    private fun maybeShowHoldMuteTip() {
        val prefs = getSharedPreferences(OnboardingActivity.PREFERENCES, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_HOLD_MUTE_TIP_SHOWN, false)) return
        prefs.edit().putBoolean(PREF_HOLD_MUTE_TIP_SHOWN, true).apply()
        com.google.android.material.snackbar.Snackbar
            .make(callScreen, R.string.call_hold_mute_tip, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .show()
    }

    /**
     * Audio focus changed during the call (CallAudioFocus): a transient loss, such as a cellular
     * call, holds the call on the existing mute path (the peer gets Microphone muted, signal 9);
     * regaining focus restores the user's own mute choice.
     */
    private fun onPrivateCallFocusAction(action: FocusAction) {
        val endpoint = privateCall ?: return
        val muted = privateCallFocusHold.onAction(action) ?: return
        endpoint.setMuted(muted)
        if (privateCallPhase == PrivateCallPhase.ACTIVE) {
            setCallStatus(
                if (privateCallFocusHold.held) "On hold: another call is using the microphone"
                else "Encrypted private call",
            )
        }
        updatePrivateCallControlLabels()
    }

    private fun togglePrivateCallMute() {
        // Experimental push-to-talk: while the talk button is held it owns the microphone.
        val ptt = intercomCallUi.pushToTalk
        if (ptt?.held == true) return
        privateCallMuted = !privateCallMuted
        ptt?.setMuted(privateCallMuted)
        // While another call holds the microphone (audio focus hold) the call stays muted.
        privateCall?.setMuted(privateCallFocusHold.onUserMuted(privateCallMuted))
        updatePrivateCallControlLabels()
        if (privateCallMuted && ptt == null) maybeShowHoldMuteTip()
    }

    private fun togglePrivateCallCamera() {
        val endpoint = privateCall ?: return
        if (!privateCallCameraEnabled && !ThermalGuardPolicy.callVideoAllowed(thermalStatus)) {
            showCallHeatNotice("camera_on")
            return
        }
        if (!privateCallCameraEnabled && requestCallMedia(video = true, use = CallMediaAccess.Use.CAMERA_ON) {
                if (privateCall === endpoint && privateCallPhase in setOf(PrivateCallPhase.ACTIVE, PrivateCallPhase.NEGOTIATING_MEDIA)) togglePrivateCallCamera()
            }) return
        privateCallCameraEnabled = !privateCallCameraEnabled
        // A call that began as a voice call runs a service without the camera type. Adding video must
        // widen the service first (Android 14 refuses camera use from a service without the type once
        // the app is in the background); removing video narrows it again.
        if (privateCallCameraEnabled && privateCall?.localCallType == CallType.VOICE) {
            ActiveVideoSessionService.start(
                this,
                usesCamera = true,
                statusText = "Encrypted private video call",
                ongoingCallStyle = CallUx.settings.callStyleNotifications,
                peerName = callRealName?.invoke(),
            )
        }
        if (!privateCallCameraEnabled) {
            stopPrivateCallCamera()
            showPrivateCallLocalPreview()
        } else {
            showPrivateCallLocalPreview()
            val codec = privateCallCodec ?: return
            val callSettings = privateCallSettings ?: return
            startPrivateCallCamera(codec, callSettings)
        }
        privateCall?.setCameraEnabled(privateCallCameraEnabled)
        if (!privateCallCameraEnabled && privateCall?.localCallType == CallType.VOICE) {
            ActiveVideoSessionService.start(
                this,
                usesCamera = false,
                statusText = "Encrypted private voice call",
                usesMicrophone = true,
                ongoingCallStyle = CallUx.settings.callStyleNotifications,
                peerName = callRealName?.invoke(),
            )
        }
        val layoutChanged = intercomCallUi.onLocalVideo(privateCallCameraEnabled)
        val event = if (privateCallCameraEnabled) "local_video_on" else "local_video_off"
        if (layoutChanged) {
            onPrivateCallLayoutChanged(event)
        } else {
            intercomCallUi.layout?.let { layout ->
                privateCall?.logCallMode(event, intercomCallUi.localVideo, intercomCallUi.remoteVideo, layout)
            }
        }
        updatePrivateCallControlLabels()
    }

    private fun switchPrivateCallCamera() {
        if (debugRawInputFile != null) return
        if (!privateCallCameraEnabled || availableCameraLenses.size < 2) return
        stopPrivateCallCamera()
        val currentIndex = availableCameraLenses.indexOf(cameraLens)
        cameraLens = availableCameraLenses[(currentIndex + 1) % availableCameraLenses.size]
        privateCall?.setDisplayRotation(
            CameraOrientation.currentCameraRotationDegrees(this, cameraLens),
        )
        val codec = privateCallCodec ?: return
        val callSettings = privateCallSettings ?: return
        startPrivateCallCamera(codec, callSettings)
        privateCall?.setCameraEnabled(true)
    }

    /**
     * Experimental camera pause at the ear (CallProximityCameraPause): the outgoing camera stops
     * while the phone is near on the earpiece and restarts when it leaves; the peer sees the
     * ordinary Camera off/on signals and the user's camera choice is unchanged.
     */
    private fun onPrivateCallProximityPause(paused: Boolean) {
        if (privateCallPhase != PrivateCallPhase.ACTIVE && privateCallPhase != PrivateCallPhase.NEGOTIATING_MEDIA) return
        if (paused) {
            if (!privateCallCameraEnabled || privateCallCameraEncoder == null) return
            privateCallCameraPausedAtEar = true
            stopPrivateCallCamera()
            privateCall?.setCameraEnabled(false)
        } else if (privateCallCameraPausedAtEar) {
            privateCallCameraPausedAtEar = false
            if (!privateCallCameraEnabled) return
            val codec = privateCallCodec ?: return
            val callSettings = privateCallSettings ?: return
            startPrivateCallCamera(codec, callSettings)
            privateCall?.setCameraEnabled(true)
        }
    }

    /** Measured Auto ladder, audio first: call video pauses (camera-off path) while the link cannot carry it. */
    private fun onPrivateCallAudioFirst(pause: Boolean, sample: LinkRateSample) {
        if (privateCallPhase != PrivateCallPhase.ACTIVE && privateCallPhase != PrivateCallPhase.NEGOTIATING_MEDIA) return
        val detail = "backlog=${sample.backlogAgeMs} ms | delivered=${sample.deliveredKbps} kbps | peerLate=${sample.childLate}"
        if (pause) {
            if (!privateCallCameraEnabled || privateCallCameraEncoder == null) return
            privateCallCameraPausedByLink = true
            stopPrivateCallCamera()
            privateCall?.setCameraEnabled(false)
            autoLog("scope=call_audio_first | action=pause | $detail")
        } else if (privateCallCameraPausedByLink) {
            privateCallCameraPausedByLink = false
            // The user turned the camera off, or back on, meanwhile: nothing to resume.
            if (!privateCallCameraEnabled || privateCallCameraEncoder != null) return
            val codec = privateCallCodec ?: return
            val callSettings = privateCallSettings ?: return
            startPrivateCallCamera(codec, callSettings)
            privateCall?.setCameraEnabled(true)
            autoLog("scope=call_audio_first | action=resume | $detail")
        }
    }

    private fun updatePrivateCallControlLabels() {
        privateCallProximityPause?.update(
            userCameraOn = privateCallCameraEnabled,
            earpiece = privateCallAudioSession?.output == CallOutput.EARPIECE,
        )
        if (!::callMuteButton.isInitialized) return
        // Round icon buttons: the label is the TextView below; checked = muted / video off / earpiece.
        callMuteLabel.setText(if (privateCallMuted) R.string.call_unmute else R.string.call_mute)
        callCameraLabel.setText(if (privateCallCameraEnabled) R.string.call_video_off else R.string.call_video_on)
        (callMuteButton as? com.google.android.material.button.MaterialButton)?.let {
            it.isChecked = privateCallMuted
            it.setIconResource(if (privateCallMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        }
        (callCameraButton as? com.google.android.material.button.MaterialButton)?.let {
            it.isChecked = !privateCallCameraEnabled
            it.setIconResource(if (privateCallCameraEnabled) R.drawable.ic_videocam else R.drawable.ic_videocam_off)
        }
        callSwitchCameraButton.isEnabled = privateCallCameraEnabled && availableCameraLenses.size > 1 &&
            debugRawInputFile == null
        val audio = privateCallAudioSession
        val speakerOn = audio?.speakerOn ?: true
        val output = audio?.output ?: CallOutput.SPEAKER
        // Speaker button (voice and video calls alike): a toggle, or the output sheet once a headset is present.
        val outputOptions = audio?.outputOptions().orEmpty()
        val opensOutputSheet = CallOutputChoices.opensSheet(outputOptions)
        callSpeakerLabel.text = output.label
        (callSpeakerButton as? com.google.android.material.button.MaterialButton)?.let {
            it.isChecked = speakerOn
            it.setIconResource(
                when {
                    speakerOn -> R.drawable.ic_volume_up
                    output == CallOutput.WIRED_HEADSET || output == CallOutput.BLUETOOTH -> R.drawable.ic_headphones
                    else -> R.drawable.ic_volume_down
                },
            )
        }
        callSpeakerButton.isEnabled = audio != null && CallOutputChoices.buttonEnabled(outputOptions)
        callSpeakerButton.contentDescription = when {
            opensOutputSheet -> "Sound plays from the ${output.label.lowercase()}. Tap to choose the audio output."
            speakerOn -> "Sound plays from the speaker. Tap to use the earpiece."
            else -> "Sound plays from the ${output.label.lowercase()}. Tap to use the speaker."
        }
        callMuteButton.contentDescription = if (privateCallMuted) {
            getString(R.string.call_unmute_description)
        } else {
            getString(R.string.call_mute_description)
        }
        callCameraButton.contentDescription = if (privateCallCameraEnabled) "Turn your video off" else "Turn your video on"
        callSwitchCameraButton.contentDescription = "Switch between front and back camera"
        // Voice calls show the same five controls as video calls (End stays in the middle); Flip stays disabled
        // until the camera is on, and Camera is the add-video action.
        val voiceLayout = intercomCallUi.layout == CallLayout.VOICE
        if (voiceLayout) {
            (callCameraButton as? com.google.android.material.button.MaterialButton)?.isChecked = false
            callCameraLabel.setText(R.string.call_add_video)
            callCameraButton.contentDescription =
                "Turn on your video. The other person chooses whether to turn on theirs."
        }
        intercomCallUi.updatePushToTalkButton()
    }

    /** The Speaker button: the output sheet when a headset is present, otherwise the speaker/earpiece toggle. */
    private fun onCallSpeakerTapped() {
        val audio = privateCallAudioSession ?: return
        val options = audio.outputOptions()
        if (!CallOutputChoices.opensSheet(options)) {
            togglePrivateCallSpeaker()
            return
        }
        CallOutputSheet.show(this, options, audio.output) { option ->
            intercomCallUi.routePolicy?.onUserChoice()
            val applied = audio.selectOutput(option)
            Log.i("BluetoothVideo", "Call audio route: sheet pick ${option.output} (${option.name}) applied=$applied")
            updatePrivateCallControlLabels()
        }
    }

    /** Loudspeaker (the default) or the quieter handset earpiece for call audio. */
    private fun togglePrivateCallSpeaker(): Boolean {
        val audio = privateCallAudioSession ?: return false
        intercomCallUi.routePolicy?.onUserChoice()
        val wanted = !audio.speakerOn
        val speakerOn = audio.setSpeakerOn(wanted)
        Log.i("BluetoothVideo", "Call audio route: ${if (speakerOn) "speaker" else "earpiece"} (requested ${if (wanted) "speaker" else "earpiece"})")
        updatePrivateCallControlLabels()
        return speakerOn == wanted
    }

    private fun setCallStatus(message: String) {
        Log.i("BluetoothVideo", message)
        if (message == ACTIVE_CALL_STATUS) {
            // The security chip already says "Encrypted"; keep the text for TalkBack only.
            callStatusText.text = message
            callStatusText.visibility = View.GONE
            return
        }
        displayStatus(message)?.let(::showCallStatusText)
    }

    private fun showCallStatusText(text: String) {
        callStatusText.text = text
        if (::callPartyPanel.isInitialized && callPartyPanel.visibility == View.VISIBLE) {
            // The caller card's state line carries the status; the name is already shown.
            if (!text.startsWith("Calling") && !text.startsWith("Incoming")) callPartyState.text = text
            callStatusText.visibility = View.GONE
            return
        }
        callStatusText.visibility = View.VISIBLE
    }

    private fun applyPrivateCallSurfaceTransform() {
        val format = privateCallSurfaceFormat ?: return
        if (callRemotePreview.width == 0 || callRemotePreview.height == 0) return
        val mode = RemoteVideoFit.mode(
            enabled = DebugTunables.remoteVideoFit,
            chosenMode = format.scaleMode,
            override = privateCallRemoteFit.override,
            encodedWidth = format.width,
            encodedHeight = format.height,
            rotationDegrees = format.rotationDegrees,
            viewWidth = callRemotePreview.width,
            viewHeight = callRemotePreview.height,
        )
        if (mode != privateCallRemoteShownMode) {
            privateCallRemoteShownMode = mode
            Log.i(
                "BluetoothVideo",
                "Call screen: remote video ${mode.name.lowercase()} | video=${format.width}x${format.height}" +
                    " rot=${format.rotationDegrees} | view=${callRemotePreview.width}x${callRemotePreview.height}" +
                    " | override=${privateCallRemoteFit.override?.name?.lowercase() ?: "none"}",
            )
        }
        VideoSurfaceTransform.apply(
            callRemotePreview,
            format.width,
            format.height,
            format.rotationDegrees,
            mode,
            debugTraceSession = debugMediaTraceSession,
        )
    }

    /** Double-tap framing toggle applies only while the other person's video is large and decoding. */
    private fun remoteVideoFitToggleAvailable(): Boolean =
        DebugTunables.remoteVideoFit && privateCallSurfaceFormat != null && callScreenController.remoteLarge &&
            callRemoteCameraOff.visibility != View.VISIBLE

    private fun toggleRemoteVideoFit() {
        val current = privateCallRemoteShownMode ?: return
        val next = privateCallRemoteFit.toggle(current)
        applyPrivateCallSurfaceTransform()
        callScreen.announceForAccessibility(
            if (next == VideoScaleMode.FIT) "Showing the whole picture" else "Filling the screen",
        )
    }

    /**
     * A single tap still shows or hides the call controls. While a double-tap can switch framing, the single tap
     * waits for the double-tap timeout first (as video players do); otherwise it acts at once.
     */
    private fun bindRemoteVideoFitGesture() {
        var tapHandled = false
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                tapHandled = false
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!remoteVideoFitToggleAvailable()) {
                    tapHandled = true
                    callScreen.performClick()
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!tapHandled) callScreen.performClick()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (remoteVideoFitToggleAvailable()) {
                    tapHandled = true
                    toggleRemoteVideoFit()
                } else if (!tapHandled) {
                    callScreen.performClick()
                }
                return true
            }
        })
        @Suppress("ClickableViewAccessibility") // Taps reach performClick(), so accessibility clicks still work.
        callScreen.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
        ViewCompat.addAccessibilityAction(callScreen, "Switch between whole picture and fill screen") { _, _ ->
            if (remoteVideoFitToggleAvailable()) {
                toggleRemoteVideoFit()
                true
            } else {
                false
            }
        }
    }

    private fun endPrivateCall() {
        val endpoint = privateCall
        if (endpoint == null) {
            showPrivateCallBrowser()
            return
        }
        endpoint.hangUp()
    }

    private fun cleanupPrivateCallMedia(keepService: Boolean = false) {
        // Experimental call pre-warm: every call end (decline, cancel, timeout, failure, hang-up) releases what it prepared.
        if (DebugTunables.callPrewarm) privateCallPrewarm.release("call_cleanup")
        val impairment = debugCallImpairment
        impairment?.cancel()
        debugCallImpairment = null
        privateCallContinuityTracker?.let {
            logReceiverContinuity(it.snapshot(System.nanoTime()), final = true, label = "Private call")
        }
        privateCallContinuityTracker = null
        privateCallMediaStartedAtNs = 0L
        privateCallContinuityLastReportNs = 0L
        holdToTalkGesture?.reset() // A hold in progress belonged to this call.
        stopPrivateCallRinging()
        privateCallProximityPause?.stop()
        privateCallProximityPause = null
        privateCallCameraPausedAtEar = false
        stopPrivateCallCamera()
        privateCallMicrophoneEncoder?.close()
        privateCallMicrophoneEncoder = null
        // Camera shutdown is serialized on its executor; late cancelled offers remain visible.
        privateCallCameraExecutor.execute { impairment?.close() }
        privateCallAudioSession?.close()
        privateCallAudioSession = null
        privateCallActiveAudioBitRate = null
        privateCallRemoteSurface?.release()
        privateCallRemoteSurface = null
        privateCallSurfaceFormat = null
        privateCallRemoteFit.reset()
        privateCallRemoteShownMode = null
        privateCallRemoteVideoStarted = false
        debugPrivateCallSurfacePending = false
        activeStreamSettings = null
        if (::callRemotePreview.isInitialized) {
            callRemotePreview.alpha = 0f
            callRemotePreview.setTransform(null)
        }
        if (::callScreenController.isInitialized) {
            callScreenController.reset()
            callScreenController.setForeground(false)
        }
        if (::intercomCallUi.isInitialized) intercomCallUi.close()
        volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
        if (!keepService) ActiveVideoSessionService.stop(this)
    }

    /** See the onEnded caller: connectedDevice only, or stop if Android refuses. */
    private fun keepReachabilityServiceAfterCall() {
        val refused = runCatching {
            ActiveVideoSessionService.start(this, usesCamera = false, statusText = getString(R.string.reachable_service_text)).refused
        }.getOrElse { it.message ?: it.javaClass.simpleName }
        if (refused != null) {
            Log.w("BluetoothVideo", "Call reachability service after call refused: $refused")
            ActiveVideoSessionService.stop(this)
        }
    }

    private fun stopPrivateCallEndpoint(message: String? = null, keepService: Boolean = false) {
        // A start still opening its listener closes it itself and never publishes "available".
        callEndpointStarts.cancelAll()
        stopPrivateCallRinging()
        callRinger.stopRingback()
        stopPrivateCallDiscovery()
        cleanupPrivateCallMedia(keepService)
        privateCall?.let { endpoint ->
            if (!endpoint.endedQuietly && !endpoint.endedFromRelayDuty) {
                endpoint.captureEndedRecentCall(message ?: "Call ended")
                recordEndedRecentCall(endpoint)
            }
            endpoint.close()
        }
        privateCall = null
        privateCallPhase = null
        VoiceNoteGate.callPhase = null
        clearCallPeerName()
        privateCallAvailabilityStarting = false
        privateCallAvailable = false
        privateCallReturnHome = false
        privateCallReturnLive = false
        privateCallSettings = null
        privateCallResolutionLadder = null
        privateCallGpuScale = null
        privateCallSentResolution = null
        privateCallRateController = null
        privateCallLinkShare = null
        privateCallFpsHysteresis = null
        privateCallClimbGuard = null
        privateCallAudioFirstGate = null
        privateCallCameraPausedByLink = false
        privateCallLinkRateLimitBps = null
        privateCallLinkFps = null
        privateCallCodec = null
        privateCallPeers = emptyList()
        privateCallPeersLastSeen.clear()
        callSightings.clear()
        callEndpointTransport = null
        callTransportChoice = null
        cocRetryPeer = null
        cocRebuildPeer = null
        message?.let(::setStatus)
    }

    private fun startPrivateCallRinging() {
        stopPrivateCallRinging()
        // Ringer policy (CallRinger): silent/vibrate/DND and a fallback ringtone; benchmark launches
        // keep the former ring unless test_call_ringer_policy opts in.
        callRinger.startIncoming(CallUx.settings.ringerPolicy)
    }

    private fun stopPrivateCallRinging() {
        callLockScreen.cancelIncomingAlert()
        callRinger.stopIncoming()
    }

    /** Ringback (Settings > Features, or test_call_ringback): the caller hears ringing while the callee rings. */
    private fun updatePrivateCallRingback(phase: PrivateCallPhase?) {
        // AB3/AP3 D2: no ringback while a blocked call waits for its ordinary end.
        if (RingbackPolicy.plays(CallUx.settings.ringback, phase, privateCall?.blockedEndScheduled() == true)) {
            callRinger.startRingback(RingbackPolicy.stream(privateCall?.localCallType))
        } else {
            callRinger.stopRingback()
        }
    }

    private fun connectToStream(peer: BleL2capPeer) {
        if (!supportsAdvertisedCodec(peer.announcement)) {
            receiverBrowserStatus.text = getString(R.string.watch_cannot_play)
            return
        }
        if (peer.announcement.isFullStreamOffer() && reconnectTarget?.matches(peer.announcement) != true) {
            receiverBrowserStatus.text = getString(R.string.watch_stream_full)
            return
        }
        val preservingPlayback = appScreen == AppScreen.PLAYBACK &&
            reconnectTarget?.matches(peer.announcement) == true && receiverActivity.session
        // Any join replaces a pending retry and its message.
        streamJoinRetry.cancel()
        streamJoinSnackbar?.dismiss()
        streamJoinSnackbar = null
        resetReceiverRecoveryNotice()
        val logicalTarget = LogicalStreamTarget.from(peer.announcement)
        Log.i(
            "BluetoothVideo",
            "Selected route token=${peer.announcement.streamToken.toUInt().toString(16)}, " +
                "logical=${logicalTarget.token.toUInt().toString(16)}, " +
                "hops=${peer.announcement.routeCost}",
        )
        cancelStreamReconnect()
        stopNearbyStreamDiscovery()
        // Joining owns the advertising sets (viewer request, forwarder): the browsing endpoint closes, always, including a
        // start still in flight (its listener would otherwise come up beside the viewer's, with the same GATT service
        // UUIDs, and take the source's connect-back: STREAM-INTEROP block 1). startReceiving waits for that start to end.
        val startsInFlight = callEndpointStarts.cancelAll()
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_reachability: endpoint stopped | reason=joining_stream | " +
                "endpoint=${privateCall != null} | starts_in_flight=$startsInFlight",
        )
        if (privateCall != null || privateCallAvailabilityStarting) stopPrivateCallEndpoint()
        receiverJoinStartedAtNs = System.nanoTime()
        if (!preservingPlayback) {
            finishReceiverContinuity()
            receiverContinuityTracker = null
            receiverContinuityLastReportNs = 0L
            receiverActivity.newSession()
            activeStreamSourceNodeId = null
            activeStreamSourceAuthenticated = false
            reconnectFromNodeIdPrefix = null
            preferredRecoveryParent = null
        }
        preserveReceiverContinuityOnNextFormat = preservingPlayback
        activeStreamTarget = logicalTarget
        receiverRouteWithdrawal = null
        selectedStreamPeer = peer
        if (preservingPlayback) {
            showReceiverOverlay("Reconnecting to stream", showProgress = true)
            updateConnectionDot(0)
        } else {
            showPlaybackScreen()
        }
        audioLive.onJoin(peer.announcement, audioListenTitle(peer)) // AUDIO-LIVE hook
        setStatus(getString(R.string.watch_joining, peer.label))
        if (hasRequiredPermissions()) {
            preview.post { if (receiver == null) startReceiving() }
        } else {
            ensurePermissionsAndRefresh()
        }
    }

    private fun supportsAdvertisedCodec(announcement: DiscoveryAnnouncement): Boolean {
        // AUDIO-LIVE hook: an audio-only offer needs no video decoder; with the feature off it is unsupported, like on an older build.
        when (announcement.streamOfferKind()) {
            StreamOfferKind.AUDIO_ONLY -> return AudioLiveFeatures.enabled
            StreamOfferKind.MALFORMED -> if (AudioLiveFeatures.enabled) return false
            StreamOfferKind.VIDEO -> Unit
        }
        val localDecoderMask = decoders.fold(0) { mask, decoder ->
            mask or codecCapabilityMask(decoder.protocolId)
        }
        return VideoCodecMap.summaryDecodable(announcement.capabilitySummary, localDecoderMask)
    }

    private fun connectToCachedStreamRoute(target: LogicalStreamTarget): Boolean {
        val nowMs = SystemClock.elapsedRealtime()
        suppressedStreamRoutes.entries.removeAll { it.value <= nowMs }
        val peer = withPreferredParentGrace(
            nearbyStreamsLastSeen.values.asSequence()
                .filter { (_, lastSeenMs) -> nowMs - lastSeenMs <= CACHED_ROUTE_MAX_AGE_MS }
                .map { it.first }
                .filter { target.matches(it.announcement) }
                .filter { supportsAdvertisedCodec(it.announcement) }
                .filterNot { candidate ->
                    suppressedStreamRoutes.keys.any { route -> route.matches(candidate.announcement) }
                }
                .toList(),
        )
        val chosen = peer.asSequence()
            .sortedWith(
                compareBy<BleL2capPeer> {
                    establishmentRank(routeFailing(it), DebugTunables.relayEstablishFallback)
                }.thenBy {
                    preferredRecoveryRank(it.announcement.nodeIdPrefix, preferredRecoveryParent)
                }.thenBy {
                    streamRouteRank(it.announcement, reconnectPreferredTransport).first
                }.thenBy {
                    streamRouteRank(it.announcement, reconnectPreferredTransport).second
                }.thenBy {
                    streamRouteRank(it.announcement, reconnectPreferredTransport).third
                },
            )
            .firstOrNull() ?: return false
        logEstablishmentFallback(peer, chosen)
        setStatus(getString(R.string.watch_reconnecting))
        connectToStream(chosen)
        return true
    }

    /** Holds reconnection briefly for the previous relay after a re-anchor (RelayChildReanchor.kt). */
    private fun withPreferredParentGrace(candidates: List<BleL2capPeer>): List<BleL2capPeer> {
        val nowMs = SystemClock.elapsedRealtime()
        val preferred = preferredRecoveryParent
        val allowed = recoveryCandidatesDuringGrace(
            candidates,
            { it.announcement.nodeIdPrefix },
            preferred,
            preferredRecoveryGraceUntilMs,
            nowMs,
        )
        if (preferred != null && allowed.isEmpty() && candidates.isNotEmpty() && !preferredRecoveryGraceLogged) {
            preferredRecoveryGraceLogged = true
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "relay_recovery_grace: waitingFor=${AdaptivePhyMemory.prefixLabel(preferred)} | " +
                    "held=${candidates.joinToString(",") { AdaptivePhyMemory.prefixLabel(it.announcement.nodeIdPrefix) }} | " +
                    "remainingMs=${preferredRecoveryGraceUntilMs - nowMs}",
            )
        }
        return allowed
    }

    private fun returnToStreamBrowser(
        peer: BleL2capPeer?,
        streamEnded: Boolean,
        allowReconnect: Boolean,
        failureNotice: String? = null,
        localMediaFailure: Boolean = false,
        minimumRetryDelayMs: Long = 0L,
    ) {
        val logicalTarget = activeStreamTarget
        // A local decoder/playback failure says nothing about the route: no cooldown for it.
        if (!streamEnded && peer != null && !localMediaFailure) {
            val failedRoute = StreamReconnectTarget.from(peer)
            val failures = (consecutiveRouteFailures[failedRoute] ?: 0) + 1
            consecutiveRouteFailures[failedRoute] = failures
            val suppressionMs = maxOf(
                if (allowReconnect && receiverWasActive) {
                    unexpectedLossRetryDelayMs(failures)
                } else {
                    FAILED_ROUTE_SUPPRESSION_MS
                },
                minimumRetryDelayMs,
            )
            suppressedStreamRoutes[failedRoute] = streamRouteSuppressionDeadline(
                suppressedStreamRoutes[failedRoute],
                SystemClock.elapsedRealtime() + suppressionMs,
            )
            // A failed cached offer needs a fresh advertisement before it can be retried.
            nearbyStreamsLastSeen.entries.removeAll { failedRoute.matches(it.value.first.announcement) }
            Log.i(
                "BluetoothVideo",
                "Receiver route cooldown: parent=${failedRoute.sourcePrefix} | " +
                    "deadlineMs=${suppressedStreamRoutes[failedRoute]} | wasActive=$receiverWasActive | " +
                    "sameFailureRepeats=${repeatedReceiverFailures.repeats} | repeatBackoffMs=$minimumRetryDelayMs",
            )
        }
        val nowForSearch = SystemClock.elapsedRealtime()
        val searchGaveUp = !streamEnded && allowReconnect && peer != null && logicalTarget != null &&
            streamSearch.expired(nowForSearch)
        if (!streamEnded && allowReconnect && peer != null && logicalTarget != null && !searchGaveUp) {
            beginStreamSearch(nowForSearch)
            reconnectTarget = logicalTarget
            reconnectPreferredTransport = peer.announcement.transportOption()
            reconnectFromNodeIdPrefix = recoveryParentAfterRouteFailure(
                reconnectFromNodeIdPrefix, receiverWasActive, peer.announcement.nodeIdPrefix,
            )
            selectedStreamPeer = null
            updateConnectionDot(0)
            val notice = failureNotice ?: "Connection lost. Finding another route..."
            setStatus(notice)
            if (connectToCachedStreamRoute(logicalTarget)) return
            showReceiverOverlay(notice, showProgress = true)
            refreshNearbyStreams()
            return
        } else {
            cancelStreamReconnect()
            endStreamSearch()
        }
        activeStreamTarget = null
        activeStreamSourceNodeId = null
        activeStreamSourceAuthenticated = false
        reconnectFromNodeIdPrefix = null
        preferredRecoveryParent = null
        preserveReceiverContinuityOnNextFormat = false
        showReceiverBrowser(cancelReconnect = false)
        if (searchGaveUp) {
            announceStreamSearchGaveUp()
        } else if (streamEnded) {
            showReceiverBrowserNotice("Stream ended. Scanning for nearby broadcasts.")
        } else if (allowReconnect && peer != null) {
            receiverBrowserStatus.text = getString(R.string.watch_connection_lost)
        } else {
            showReceiverBrowserNotice(failureNotice ?: "Could not connect. Scanning for nearby broadcasts.")
            if (peer != null && !localMediaFailure) onStreamJoinFailed(peer)
        }
    }

    /** A live join (radio or video) that never became active: say so, offer Try again, retry once by itself. */
    private fun onStreamJoinFailed(peer: BleL2capPeer) {
        val target = LogicalStreamTarget.from(peer.announcement)
        val title = if (PeerLabelFeature.titlePagesEnabled) StreamTitles.board.title(peer.announcement.streamToken) else null
        val auto = streamJoinRetry.onJoinFailed(target, SystemClock.elapsedRealtime())
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                "stream_join: failed | title=${title != null} | autoRetry=${auto != null} | enabled=${DebugTunables.streamJoinRetry}",
        )
        if (!DebugTunables.streamJoinRetry) return
        streamJoinSnackbar?.dismiss()
        streamJoinSnackbar = com.google.android.material.snackbar.Snackbar
            .make(findViewById<View>(R.id.main), StreamJoinRetry.failureMessage(title), STREAM_JOIN_SNACKBAR_MS)
            .setAction(R.string.stream_join_try_again) {
                streamJoinSnackbar = null
                // The user asked: the failed route's cooldown does not hold this one back.
                suppressedStreamRoutes.remove(StreamReconnectTarget.from(peer))
                streamJoinRetry.onTryAgain(target, SystemClock.elapsedRealtime())
                Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "stream_join: try_again")
                refreshNearbyStreams()
                refreshNearbyStreamPeers()
            }
            .also { it.show() }
    }

    private fun updateConnectionDot(receiverCount: Int) {
        if (!::connectionStatusDot.isInitialized) return
        lastConnectedCount = receiverCount
        updateBroadcastStatusChip()
        val connected = receiverCount > 0
        connectionStatusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.live_red),
        )
        connectionStatusDot.contentDescription = when (sessionRole) {
            SessionRole.RECEIVE -> if (connected) "Stream connected" else "Waiting for stream"
            SessionRole.SEND -> if (connected) {
                "$receiverCount receiver(s) connected"
            } else {
                "No receiver connected"
            }
            SessionRole.RELAY -> if (connected) "Relay connected" else "Relay disconnected"
        }
    }

    private fun showReceiverOverlay(message: String, showProgress: Boolean, noticeAlpha: Float = 1f) {
        // A stale-picture badge (viewer frame age) replaces this overlay and shows the message itself.
        if (viewerFrameAge?.interceptOverlay(message) == true) return
        if (receiverWaitingOverlay.visibility != View.VISIBLE || receiverWaitingText.text.toString() != message ||
            (receiverWaitingProgress.visibility == View.VISIBLE) != showProgress
        ) {
            Log.i("BluetoothVideo", "Receiver overlay: visible | text=$message | progress=$showProgress | noticeAlpha=$noticeAlpha")
        }
        receiverWaitingText.text = message
        receiverWaitingText.alpha = noticeAlpha
        receiverWaitingProgress.alpha = noticeAlpha
        receiverWaitingProgress.visibility = if (showProgress) View.VISIBLE else View.GONE
        receiverWaitingOverlay.visibility = View.VISIBLE
    }

    private fun hideReceiverOverlay() {
        if (receiverWaitingOverlay.visibility == View.VISIBLE) {
            Log.i("BluetoothVideo", "Receiver overlay: hidden | text=${receiverWaitingText.text}")
        }
        receiverWaitingOverlay.visibility = View.GONE
    }

    private fun resetReceiverRecoveryNotice() {
        receiverRecoveryNoticeRunnable?.let(sessionHandler::removeCallbacks)
        receiverRecoveryNoticeRunnable = null
        receiverRecoveryCoverRelease?.let(sessionHandler::removeCallbacks)
        receiverRecoveryCoverRelease = null
        receiverRecoveryNoticePolicy.reset()
    }

    /**
     * Before going live: Flip (when the phone has two cameras) and the wide Go live button. While live: five equal
     * columns, Mute, Camera, End, Flip, Pause, with End (a red 72 dp circle and its label) exactly in the middle.
     */
    private fun applyLiveToggleLook(live: Boolean) {
        val button = streamToggleButton
        val column = findViewById<android.widget.LinearLayout>(R.id.streamToggleColumn)
        val density = resources.displayMetrics.density
        val padding = toggleIdlePadding
            ?: intArrayOf(button.paddingLeft, button.paddingTop, button.paddingRight, button.paddingBottom)
                .also { toggleIdlePadding = it }
        val columnParams = column.layoutParams as android.widget.LinearLayout.LayoutParams
        val params = button.layoutParams as android.widget.LinearLayout.LayoutParams
        applyLiveRow(live)
        if (live) {
            columnParams.width = 0
            columnParams.weight = 1f
            params.width = (72 * density).toInt()
            params.height = params.width
            button.text = ""
            button.setPadding(0, 0, 0, 0)
            button.iconPadding = 0
            button.cornerRadius = (36 * density).toInt()
            button.setIconResource(R.drawable.ic_stop)
        } else {
            columnParams.width = 0
            columnParams.weight = 1f
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = (64 * density).toInt()
            button.setText(R.string.action_go_live)
            button.setPadding(padding[0], padding[1], padding[2], padding[3])
            button.iconPadding = (10 * density).toInt()
            button.cornerRadius = (28 * density).toInt()
            button.setIconResource(R.drawable.ic_live)
        }
        button.layoutParams = params
        column.layoutParams = columnParams
        button.labelWanted = live
    }

    /** The side columns of the live row: all five equal while live; before going live only a Flip beside Go live. */
    private fun applyLiveRow(live: Boolean) {
        val density = resources.displayMetrics.density
        fun column(id: Int) = findViewById<android.widget.LinearLayout>(id)
        val flip = column(R.id.broadcastFlipColumn)
        listOf(R.id.broadcastMuteColumn, R.id.broadcastCameraColumn, R.id.broadcastPauseColumn).forEach {
            column(it).visibility = if (live) View.VISIBLE else View.GONE
        }
        val flipParams = flip.layoutParams as android.widget.LinearLayout.LayoutParams
        if (live) {
            flipParams.width = 0
            flipParams.weight = 1f
            flipParams.marginEnd = 0
            flip.setPadding(0, (4 * density).toInt(), 0, 0)
        } else {
            flipParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
            flipParams.weight = 0f
            flipParams.marginEnd = (20 * density).toInt()
            flip.setPadding(0, 0, 0, 0)
        }
        flip.layoutParams = flipParams
        // Go live is the 64 dp wide button on the left of the 72 dp End; keep the circles on one centre line.
        findViewById<android.widget.LinearLayout>(R.id.liveControls).gravity =
            if (live) android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            else android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.CENTER_HORIZONTAL
    }

    private fun updatePauseButton() {
        if (!::broadcastPauseButton.isInitialized) return
        val available = sessionRole == SessionRole.SEND && appScreen == AppScreen.CAMERA &&
            ((broadcaster != null && streamArmed) || (BuildConfig.DEBUG && sender != null))
        // A hidden slot keeps its place (INVISIBLE) so End stays in the middle of the row.
        broadcastPauseButton.visibility = if (available) View.VISIBLE else View.INVISIBLE
        broadcastPauseButton.setIconResource(
            if (streamPaused) R.drawable.ic_play else R.drawable.ic_pause,
        )
        findViewById<TextView>(R.id.broadcastPauseLabel)
            .setText(if (streamPaused) R.string.live_control_resume else R.string.live_control_pause)
        broadcastPauseButton.contentDescription = if (streamPaused) {
            "Resume broadcast"
        } else {
            "Pause broadcast"
        }
        updateLiveMediaButtons(available)
    }

    /** Mute and Camera of a video broadcast (the microphone and the camera, independently of Pause). */
    private fun updateLiveMediaButtons(live: Boolean) {
        if (!::broadcastMuteButton.isInitialized) return
        val shown = live && broadcaster != null && !audioLive.mode
        val visibility = if (shown) View.VISIBLE else View.INVISIBLE
        broadcastMuteButton.visibility = visibility
        broadcastCameraButton.visibility = visibility
        val muteOn = LiveControlSlots.enabled(LiveSlot.MUTE, streamPaused, liveMedia.cameraOff)
        val cameraOn = LiveControlSlots.enabled(LiveSlot.CAMERA, streamPaused, liveMedia.cameraOff) && !liveCameraBusy
        broadcastMuteButton.isEnabled = muteOn
        broadcastCameraButton.isEnabled = cameraOn
        broadcastMuteButton.isChecked = liveMedia.micMuted
        broadcastMuteButton.setIconResource(if (liveMedia.micMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        findViewById<TextView>(R.id.broadcastMuteLabel)
            .setText(if (liveMedia.micMuted) R.string.live_control_unmute else R.string.live_control_mute)
        broadcastMuteButton.contentDescription =
            if (liveMedia.micMuted) "Unmute your microphone" else "Mute your microphone. The video goes on."
        broadcastCameraButton.isChecked = liveMedia.cameraOff
        broadcastCameraButton.setIconResource(if (liveMedia.cameraOff) R.drawable.ic_videocam_off else R.drawable.ic_videocam)
        findViewById<TextView>(R.id.broadcastCameraLabel)
            .setText(if (liveMedia.cameraOff) R.string.live_control_camera_on else R.string.live_control_camera_off)
        broadcastCameraButton.contentDescription =
            if (liveMedia.cameraOff) "Turn your camera on" else "Turn your camera off. The sound goes on."
        // Flip needs the camera (and shows only with two cameras); it keeps its slot when hidden.
        if (::cameraSwitchButton.isInitialized && shown) {
            cameraSwitchButton.isEnabled = LiveControlSlots.enabled(LiveSlot.FLIP, streamPaused, liveMedia.cameraOff) &&
                !liveCameraBusy && cameraEncoder != null && debugRawInputFile == null
        }
        renderLiveMediaNotice(if (shown) ViewerMediaNotice(liveMedia.micMuted, liveMedia.cameraOff) else viewerMediaNotice)
    }

    /**
     * Flip while live: the other camera, if it can deliver this stream's size and frame rate. The stream camera is
     * released and started again like after a resume (keyframe first); the audio goes on. Nothing changes when the
     * other camera cannot match (the picture size is part of the request and is never changed to make a flip work).
     */
    private fun flipLiveCamera() {
        val activeBroadcaster = broadcaster ?: return
        if (sessionRole != SessionRole.SEND || streamPaused || audioLive.mode || liveCameraBusy || liveMedia.cameraOff) return
        val old = cameraEncoder ?: return
        val codec = activeStreamCodec() ?: return
        val activeSettings = activeStreamSettings ?: return
        val nextLens = availableCameraLenses.firstOrNull { it != cameraLens } ?: return
        val current = streamEncoderSettings(activeSettings)
        val nextModes = if (streamGpuScale != null) {
            gpuScaleModes(codec, activeSettings.bitRate, nextLens, activeSettings.resolution, activeSettings.fps)
        } else {
            deviceVideoCapabilities.modesFor(codec, activeSettings.bitRate, nextLens)
        }
        val fits = nextModes
            .any {
                it.resolution.width == current.resolution.width && it.resolution.height == current.resolution.height &&
                    activeSettings.fps in it.fpsOptions
            }
        if (!fits) {
            setStatus(getString(R.string.live_camera_size, nextLens.displayName.lowercase()))
            return
        }
        cameraLens = nextLens
        cameraEncoder = null
        liveCameraBusy = true
        Log.i("BluetoothVideo", "Live camera flip: ${nextLens.displayName} | ${current.resolution.width}x${current.resolution.height} @${activeSettings.fps}")
        updateLiveMediaButtons(true)
        streamCameraExecutor.execute {
            runCatching { old.close() }
            runOnUiThread {
                liveCameraBusy = false
                if (broadcaster === activeBroadcaster && !streamPaused && !liveMedia.cameraOff && cameraEncoder == null) {
                    startBroadcastMedia(activeBroadcaster, codec, activeSettings)
                    setStatus("${nextLens.displayName} camera selected.")
                }
                updateLiveMediaButtons(true)
                updateCameraSwitchButton()
            }
        }
    }

    /** Tells viewers (when the switch is on) what the broadcaster switched off; the broadcaster sees the same labels. */
    private fun announceLiveMediaState() {
        if (!FeatureCatalog.isEnabled(ViewerFeatures.liveMediaState.id)) return
        broadcaster?.setMediaState(liveMedia.record)
    }

    private fun toggleBroadcastMute() {
        val activeBroadcaster = broadcaster ?: return
        if (sessionRole != SessionRole.SEND || streamPaused || audioLive.mode) return
        liveMedia.toggleMic()
        // Opus access units wait for a partner before they are sent; drop the lone one so it cannot pair after Unmute.
        if (liveMedia.micMuted) activeBroadcaster.flushPendingAudio()
        Log.i("BluetoothVideo", "Live microphone: ${if (liveMedia.micMuted) "muted" else "on"} | record=${liveMedia.record}")
        announceLiveMediaState()
        updateLiveMediaButtons(true)
    }

    private fun toggleBroadcastCamera() {
        val activeBroadcaster = broadcaster ?: return
        if (sessionRole != SessionRole.SEND || streamPaused || audioLive.mode || liveCameraBusy) return
        val codec = activeStreamCodec() ?: return
        val activeSettings = activeStreamSettings ?: return
        val turningOff = !liveMedia.cameraOff
        liveMedia.toggleCamera()
        Log.i("BluetoothVideo", "Live camera: ${if (liveMedia.cameraOff) "off" else "on"} | record=${liveMedia.record}")
        liveCameraBusy = true
        announceLiveMediaState()
        updateLiveMediaButtons(true)
        if (turningOff) {
            // Off: the camera and encoder are released (no frames are produced or sent); the sound goes on.
            val old = cameraEncoder
            cameraEncoder = null
            streamCameraExecutor.execute {
                runCatching { old?.close() }
                runOnUiThread {
                    liveCameraBusy = false
                    updateLiveMediaButtons(true)
                }
            }
        } else {
            // On: the same start as after a resume; the encoder's first frame is a keyframe.
            streamCameraExecutor.execute {
                runOnUiThread {
                    liveCameraBusy = false
                    if (broadcaster === activeBroadcaster && !streamPaused && !liveMedia.cameraOff && cameraEncoder == null) {
                        startBroadcastMedia(activeBroadcaster, codec, activeSettings)
                    }
                    updateLiveMediaButtons(true)
                }
            }
        }
    }

    /** The "Camera off" cover and the "Muted" chip, for the broadcaster's own screen and for viewers. */
    private fun renderLiveMediaNotice(notice: ViewerMediaNotice) {
        if (!::liveMediaStateCover.isInitialized) return
        liveMediaStateCover.visibility = if (notice.cameraOff) View.VISIBLE else View.GONE
        liveMediaStateChip.visibility = if (notice.micMuted) View.VISIBLE else View.GONE
        liveMediaStateChip.setText(R.string.watch_state_muted)
    }

    /** A viewer's state record: show Muted / Camera off, and do not call a still picture a stalled stream. */
    private fun applyViewerMediaState(state: StreamLifecycleState) {
        val previous = viewerMediaNotice
        val next = ViewerMediaNotice.of(state)
        viewerMediaNotice = next
        renderLiveMediaNotice(next)
        if (next.cameraOff && !previous.cameraOff) viewerFrameAge?.onPaused()
        if (!next.cameraOff && previous.cameraOff && state.isLiveFamily) viewerFrameAge?.onResumed()
    }

    private fun cancelStreamReconnect() {
        reconnectTarget = null
        reconnectPreferredTransport = null
    }

    /**
     * A loss starts the 60 s search clock (a failed reconnect attempt inside the same search does not restart it) and arms
     * the one timer that ends the search when no attempt is running ([giveUpStreamSearch]).
     */
    private fun beginStreamSearch(nowMs: Long) {
        streamSearch.begin(nowMs)
        val remaining = streamSearch.remainingMs(nowMs) ?: return
        sessionHandler.removeCallbacks(streamSearchGiveUp)
        sessionHandler.postDelayed(streamSearchGiveUp, remaining)
    }

    /** The search is over (the stream plays again, or the viewer is back in the list): clock and timer off. */
    private fun endStreamSearch() {
        streamSearch.end()
        sessionHandler.removeCallbacks(streamSearchGiveUp)
    }

    /**
     * The 60 s are up with no route found. While a reconnect attempt is running ([reconnectTarget] is cleared by the join) the
     * attempt's own failure ends the search; otherwise the viewer returns to the stream list, which also stops the scan
     * that kept the phone closed to calls.
     */
    private fun giveUpStreamSearch() {
        runOnUiThread {
            if (reconnectTarget == null || !streamSearch.expired(SystemClock.elapsedRealtime())) return@runOnUiThread
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "stream_search: gave_up | boundMs=${StreamSearchBound.DEFAULT_BOUND_MS}",
            )
            activeStreamTarget = null
            activeStreamSourceNodeId = null
            activeStreamSourceAuthenticated = false
            showReceiverBrowser() // clears the reconnect state and the search clock, stops the receiver, rescans
            announceStreamSearchGaveUp()
        }
    }

    /** "The stream was lost" in the list, with Try again (a fresh scan; the stream is listed again when it is back). */
    private fun announceStreamSearchGaveUp() {
        endStreamSearch()
        showReceiverBrowserNotice(getString(R.string.watch_stream_lost))
        streamJoinSnackbar?.dismiss()
        streamJoinSnackbar = com.google.android.material.snackbar.Snackbar
            .make(findViewById<View>(R.id.main), getString(R.string.watch_stream_lost), STREAM_JOIN_SNACKBAR_MS)
            .setAction(R.string.stream_join_try_again) {
                streamJoinSnackbar = null
                refreshNearbyStreams()
                refreshNearbyStreamPeers()
            }
        streamJoinSnackbar?.show()
    }

    private fun showReceiverBrowserNotice(message: String) {
        receiverBrowserNotice = message
        receiverBrowserNoticeDeadlineMs = SystemClock.elapsedRealtime() + RECEIVER_NOTICE_MS
        receiverBrowserStatus.text = message
        sessionHandler.removeCallbacks(receiverBrowserNoticeTimeoutRunnable)
        sessionHandler.postDelayed(receiverBrowserNoticeTimeoutRunnable, RECEIVER_NOTICE_MS)
    }

    private fun clearReceiverBrowserNotice() {
        receiverBrowserNotice = null
        receiverBrowserNoticeDeadlineMs = 0L
        sessionHandler.removeCallbacks(receiverBrowserNoticeTimeoutRunnable)
    }

    /** First run: onboarding is in front, so nothing is asked, scanned or advertised here (RuntimePermissionPlan.mainScreenWaits). */
    private fun onboardingHolds(): Boolean = RuntimePermissionPlan.mainScreenWaits(OnboardingState.open, launchSkipsOnboarding())

    /** Refused on onboarding's permission page; consumed by the first request after it ([RuntimePermissionPlan.afterOnboarding]). */
    private var postOnboardingRefused: Set<String> = emptySet()

    private fun ensurePermissionsAndRefresh() {
        if (onboardingHolds()) return
        val needed = requiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        val ask = RuntimePermissionPlan.afterOnboarding(needed, postOnboardingRefused)
        postOnboardingRefused = emptySet()
        when {
            needed.isEmpty() -> ensureBluetoothEnabledAndRefresh()
            ask.isNotEmpty() -> permissionRequest.launch(ask.toTypedArray())
            else -> {
                setStatus(missingPermissionsText())
                updateStartEnabled()
            }
        }
    }

    private fun ensureBluetoothEnabledAndRefresh() {
        val adapter = bluetoothAdapter
        when {
            adapter == null -> onPermissionsReady()
            adapter.isEnabled -> onPermissionsReady()
            else -> {
                setStatus(getString(R.string.status_turn_on_bluetooth))
                bluetoothEnableRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        }
    }

    private fun requiredPermissions(): List<String> {
        val capturesMedia = RuntimePermissionPlan.screenCapturesMedia(
            sending = sessionRole == SessionRole.SEND,
            onCallScreen = appScreen == AppScreen.CALL,
            onCallList = appScreen == AppScreen.CALL_BROWSER,
            callReadiness = DebugTunables.callReadinessPermissions,
            transportExperiment = requestedTransportExperiment != null,
        )
        val needsBleAccess = outgoingTransport.isBle || requestedTransportExperiment != null ||
            usesBroadcastDiscovery() || debugAutoSelectStream || sessionRole == SessionRole.RELAY ||
            appScreen == AppScreen.CALL_BROWSER || appScreen == AppScreen.CALL
        // AUDIO-LIVE hook: a radio broadcast never opens the camera. The rules are in RuntimePermissionPlan (JVM-tested).
        return RuntimePermissionPlan.required(Build.VERSION.SDK_INT, capturesMedia, !audioLive.mode && (appScreen != AppScreen.CALL || privateCall?.localCallType != CallType.VOICE), needsBleAccess)
    }

    @SuppressLint("MissingPermission")
    private fun runRequestedTransportExperiment() {
        if (!BuildConfig.DEBUG) return
        requestedTransportExperiment ?: return
        if (!transportExperimentPending) return
        transportExperimentPending = false
        debugAutoStartPending = false
        val adapter = bluetoothAdapter ?: return
        startDebugBenchmark { coordinator ->
            coordinator.runTransport(intent, adapter, outgoingTransport)
        }
    }

    private fun hasRequiredPermissions(): Boolean = requiredPermissions().all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Names only the access that is still missing ([MissingPermissions]), from string resources. */
    private fun missingPermissionsText(): String {
        val missing = requiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        val names = MissingPermissions.groups(missing).map { group ->
            getString(
                when (group) {
                    MissingPermissions.Group.NEARBY_DEVICES -> R.string.permission_name_nearby
                    MissingPermissions.Group.CAMERA -> R.string.permission_name_camera
                    MissingPermissions.Group.MICROPHONE -> R.string.permission_name_microphone
                    MissingPermissions.Group.LOCATION -> R.string.permission_name_location
                },
            )
        }
        if (names.isEmpty()) return getString(R.string.permissions_needed_generic)
        return getString(
            R.string.permissions_needed,
            MissingPermissions.join(names, getString(R.string.list_separator), getString(R.string.list_last_separator)),
        )
    }

    @SuppressLint("MissingPermission")
    private fun refreshDevices() {
        bleDiscovery?.close()
        bleDiscovery = null
        val adapter = bluetoothAdapter
        when {
            adapter == null -> setStatus(getString(R.string.status_no_bluetooth))
            !adapter.isEnabled -> setStatus(getString(R.string.status_bluetooth_off_retry))
            appScreen == AppScreen.RECEIVER_BROWSER -> refreshNearbyStreams()
            appScreen == AppScreen.CALL_BROWSER -> startPrivateCallEndpoint()
            appScreen == AppScreen.CAMERA && usesBroadcastDiscovery() ->
                startStreamPresence()
            sessionRole == SessionRole.RECEIVE -> {
                setStatus(
                    if (outgoingTransport.isBle) {
                        "Direct BLE receiver test is ready."
                    } else {
                        "Direct Classic receiver test is ready."
                    },
                )
            }
            sessionRole == SessionRole.RELAY -> refreshBleReceivers(adapter)
            debugLaunchRequested && outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM ->
                refreshClassicDestinations(adapter)
            debugLaunchRequested -> refreshBleReceivers(adapter)
            else -> setStatus(getString(R.string.status_ready_live))
        }
        if (ThermalGuardPolicy.shouldBlockStreaming(thermalStatus)) {
            setStatus(getString(R.string.status_too_hot))
        } else if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            setStatus(
                if (thermalReductionOn) {
                    "Phone is hot. Automatic cooling will reduce FPS and bitrate."
                } else {
                    "Phone is hot. Automatic cooling is off."
                },
            )
        }
        updateStartEnabled()
    }

    @SuppressLint("MissingPermission")
    private fun refreshClassicDestinations(adapter: BluetoothAdapter) {
        pairedDevices = adapter.bondedDevices.sortedBy { it.name ?: it.address }
        val labels = pairedDevices.map { "${it.name ?: "Unnamed device"} (${it.address})" }
        deviceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels.ifEmpty { listOf("No paired devices") },
        )
        val requestedTarget = if (BuildConfig.DEBUG) intent.getStringExtra("test_target") else null
        val requestedTargetIndex = requestedTarget?.let { target ->
            pairedDevices.indexOfFirst { device ->
                classicTargetMatches(device.name, device.address, target)
            }
        } ?: -1
        if (requestedTargetIndex >= 0) deviceSpinner.setSelection(requestedTargetIndex, false)

        val message = when {
            labels.isEmpty() -> "No paired receivers. Pair the other device in Android Bluetooth settings first."
            requestedTarget != null && requestedTargetIndex < 0 ->
                "Requested Classic receiver '$requestedTarget' is not paired."
            else -> "Direct Classic test target ready."
        }
        setStatus(message)
    }

    @SuppressLint("MissingPermission")
    private fun refreshBleReceivers(adapter: BluetoothAdapter) {
        blePeers = emptyList()
        deviceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Scanning for active BLE receivers…"),
        )
        setStatus("Scanning for phones with this app's BLE receiver turned on…")
        // A call relay never takes an offer for itself (PrivateCallRelayChain.admitsOffer).
        val localRelayPrefix = if (debugPrivateCallRelay) {
            AndroidNodeIdentityStore().getOrCreate().nodeId.toByteArray()
                .copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
        } else {
            ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
        }
        lateinit var discovery: BleL2capDiscovery
        discovery = BleL2capDiscovery(
            adapter,
            { peer ->
                runOnUiThread {
                    if (bleDiscovery !== discovery || blePeers.any { it.device.address == peer.device.address }) {
                        return@runOnUiThread
                    }
                    blePeers = (blePeers + peer).sortedBy(BleL2capPeer::label)
                    deviceSpinner.adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        blePeers.map(BleL2capPeer::label),
                    )
                    setStatus("Found ${blePeers.size} active BLE receiver(s). Choose one and connect.")
                    updateStartEnabled()
                }
            },
            { count ->
                runOnUiThread {
                    if (bleDiscovery !== discovery) return@runOnUiThread
                    bleDiscovery = null
                    if (count == 0) {
                        deviceSpinner.adapter = ArrayAdapter(
                            this,
                            android.R.layout.simple_spinner_dropdown_item,
                            listOf("No active BLE receivers found"),
                        )
                        setStatus("No BLE receiver found. Turn Receiver ON on the other Android phone, then refresh.")
                    }
                    updateStartEnabled()
                }
            },
            { message ->
                runOnUiThread {
                    if (bleDiscovery !== discovery) return@runOnUiThread
                    bleDiscovery = null
                    setStatus(message)
                    updateStartEnabled()
                }
            },
            acceptAnnouncement = { announcement ->
                (if (debugPrivateCallRelay) {
                    PrivateCallRelayChain.admitsOffer(announcement, localRelayPrefix, DebugTunables.callRelayChain) &&
                        (if (announcement.rotating) {
                            debugRelayCalleeRotating
                        } else {
                            debugRelayCalleePrefix == null || announcement.nodeIdPrefix.toHexKey() == debugRelayCalleePrefix
                        }) &&
                        (debugRelayRouteCost == null || announcement.routeCost == debugRelayRouteCost)
                } else {
                    announcement.flags and DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS != 0 &&
                        !announcement.isPrivateCallOffer()
                }) &&
                    announcement.transportOption() == outgoingTransport &&
                    (!debugRequireRelay ||
                        announcement.flags and DiscoveryAnnouncement.FLAG_RELAY_CAPABLE != 0)
            },
            // The DEBUG call relay decodes version 5 offers (it mirrors them); the stream picker stays version 2.
            acceptRotating = debugPrivateCallRelay && RotatingDiscoveryFeature.enabled,
        )
        bleDiscovery = discovery
        discovery.start()
    }

    @SuppressLint("MissingPermission")
    private fun selectedDestination(): BluetoothDevice? = if (sessionRole == SessionRole.RELAY) {
        selectedBlePeer()?.device
    } else {
        when (outgoingTransport) {
            BluetoothTransportOption.CLASSIC_RFCOMM ->
                if (BuildConfig.DEBUG && intent.hasExtra("test_target")) {
                    val requestedTarget = intent.getStringExtra("test_target").orEmpty()
                    pairedDevices.firstOrNull { device ->
                        classicTargetMatches(device.name, device.address, requestedTarget)
                    }
                } else {
                    pairedDevices.getOrNull(deviceSpinner.selectedItemPosition)
                }
            BluetoothTransportOption.BLE_L2CAP -> blePeers.getOrNull(deviceSpinner.selectedItemPosition)?.device
            BluetoothTransportOption.BLE_GATT -> blePeers.getOrNull(deviceSpinner.selectedItemPosition)?.device
        }
    }

    private fun selectedBlePeer(): BleL2capPeer? =
        blePeers.getOrNull(deviceSpinner.selectedItemPosition)

    /** The visible reason the start is blocked ([LiveStartGate]); before, the text went only to the hidden status line. */
    private fun renderLiveStartNotice(block: LiveStartBlock) {
        val notice = findViewById<View>(R.id.liveStartNotice) ?: return
        notice.visibility = if (block == LiveStartBlock.NONE) View.GONE else View.VISIBLE
        if (block == LiveStartBlock.NONE) return
        val missing = requiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        val action = LiveStartGate.action(
            block,
            askedThisProcess = startPermissionsAsked || homePermissionsAsked,
            rationaleDue = missing.any { shouldShowRequestPermissionRationale(it) },
        )
        findViewById<TextView>(R.id.liveStartNoticeText).text = when (block) {
            LiveStartBlock.PERMISSIONS -> missingPermissionsText()
            LiveStartBlock.BLUETOOTH_OFF -> getString(R.string.live_blocked_bluetooth_off)
            LiveStartBlock.NO_BLUETOOTH -> getString(R.string.readiness_unsupported_text)
            LiveStartBlock.NONE -> ""
        }
        findViewById<android.widget.Button>(R.id.liveStartNoticeAction).apply {
            visibility = if (action == LiveStartAction.NONE) View.GONE else View.VISIBLE
            when (action) {
                LiveStartAction.ASK -> setText(R.string.readiness_permissions_action)
                LiveStartAction.OPEN_SETTINGS -> setText(R.string.action_open_settings)
                LiveStartAction.TURN_ON_BLUETOOTH -> setText(R.string.readiness_off_action)
                LiveStartAction.NONE -> Unit
            }
            setOnClickListener { onLiveStartAction(action) }
        }
    }

    private fun onLiveStartAction(action: LiveStartAction) {
        when (action) {
            LiveStartAction.ASK -> ensurePermissionsAndRefresh()
            LiveStartAction.OPEN_SETTINGS -> runCatching {
                startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.fromParts("package", packageName, null)),
                )
            }
            LiveStartAction.TURN_ON_BLUETOOTH ->
                runCatching { bluetoothEnableRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            LiveStartAction.NONE -> Unit
        }
    }

    private fun updateStartEnabled() {
        val idle = sender == null && broadcaster == null && receiver == null && relay == null &&
            privateCallRelay == null &&
            !localTestRunning
        val controlsIdle = idle && !streamArmed
        val thermalReady = !ThermalGuardPolicy.shouldBlockStreaming(thermalStatus)
        val selectedModeSupported = RawFixtureGeometry.startGateModeOk(
            debugRawInputFile != null, selectedDeviceMode(), settings,
        )
        val bluetoothReady = bluetoothAdapter?.isEnabled == true
        val transportReady = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) ||
            (outgoingTransport == BluetoothTransportOption.CLASSIC_RFCOMM && debugLaunchRequested)
        val destinationReady = if (
            usesBroadcastDiscovery()
        ) {
            true
        } else {
            selectedDestination() != null
        }
        val permissionsReady = hasRequiredPermissions()
        val canStart = when (sessionRole) {
            SessionRole.SEND -> destinationReady && codecs.isNotEmpty() && controlsIdle &&
                selectedModeSupported && bluetoothReady && transportReady &&
                permissionsReady && thermalReady
            SessionRole.RECEIVE -> preview.isAvailable && idle && bluetoothReady && transportReady &&
                permissionsReady && thermalReady
            SessionRole.RELAY -> destinationReady && blePeers.size >= debugRelayChildCount &&
                idle && bluetoothReady &&
                permissionsReady && thermalReady
        }
        val streamActive = streamArmed || sender != null || broadcaster != null ||
            receiver != null || relay != null || privateCallRelay != null
        streamToggleButton.isEnabled = streamActive || canStart
        renderLiveStartNotice(LiveStartGate.block(streamActive || !idle, transportReady, permissionsReady, bluetoothReady))
        applyLiveToggleLook(streamActive)
        streamToggleButton.contentDescription = when (sessionRole) {
            SessionRole.SEND -> if (streamActive) "Stop broadcast" else "Start broadcast"
            SessionRole.RECEIVE -> if (streamActive) "Stop receiving" else "Start receiving"
            SessionRole.RELAY -> if (streamActive) "Stop relay" else "Start relay"
        }
        streamToggleButton.alpha = if (streamToggleButton.isEnabled) 1f else 0.5f
        updateBroadcastStatusChip()
        refreshButton.isEnabled = controlsIdle
        transportSpinner.isEnabled = controlsIdle && sessionRole != SessionRole.RELAY
        val destinationControlsEnabled = controlsIdle && sessionRole != SessionRole.RECEIVE
        val sendControlsEnabled = controlsIdle
        deviceSpinner.isEnabled = destinationControlsEnabled
        codecSpinner.isEnabled = sendControlsEnabled
        rateControlSpinner.isEnabled = sendControlsEnabled && rateControlOptions.isNotEmpty()
        resolutionSpinner.isEnabled = sendControlsEnabled && deviceModes.isNotEmpty()
        fpsSpinner.isEnabled = sendControlsEnabled && selectedDeviceMode()?.fpsOptions?.isNotEmpty() == true
        bitrateSeekBar.isEnabled = sendControlsEnabled
        updateCameraSwitchButton()
        updatePauseButton()
        if (pendingVideoSwitch && canStart && sessionRole == SessionRole.SEND && usesBroadcastDiscovery() &&
            !streamArmed && broadcaster == null && !audioLive.mode
        ) {
            pendingVideoSwitch = false
            streamToggleButton.post {
                if (!streamArmed && broadcaster == null) withWifiCheck { armDiscoverableStream() }
            }
        }
        if (debugAutoStartPending && canStart) {
            debugAutoStartPending = false
            streamToggleButton.post {
                if (sender == null && broadcaster == null && receiver == null && relay == null &&
                    privateCallRelay == null
                ) {
                    when (sessionRole) {
                        SessionRole.SEND -> startStreaming()
                        SessionRole.RECEIVE -> startReceiving()
                        SessionRole.RELAY -> startRelaying()
                    }
                }
            }
        }
    }

    /**
     * Audio-only live "Switch to video": ends the radio (microphone, advertising, listeners), then goes live with video
     * under the same title. A new stream with a new key, never a conversion in place; listeners rejoin from the list.
     */
    private fun switchAudioLiveToVideo() {
        stopStreaming("Stopped.")
        pendingVideoSwitch = true
        showStreamScreen()
        updateStartEnabled()
    }

    private fun startStreaming() {
        if (!ensureThermalReady()) return
        if (usesBroadcastDiscovery()) {
            if (streamArmed) startRequestedBroadcast() else armDiscoverableStream()
            return
        }
        val device = selectedDestination() ?: return
        val codec = beginStreamSessionCodec() ?: return
        if (debugReplayPath != null) stopIdleCameraPreview()
        ActiveVideoSessionService.start(this, usesCamera = debugReplayPath == null)
        bleDiscovery?.close()
        bleDiscovery = null
        val activeTransport = outgoingTransport
        applyAutoLadderToStreamSettings()
        val activeSettings = settings
        val rotationDegrees = outgoingVideoRotationDegrees()
        activeStreamSettings = activeSettings
        applyThermalAdaptation(thermalStatus)
        val rateController = newLinkRateController(activeSettings, activeTransport)
        newStreamResolutionLadder(activeSettings, codec)
        lateinit var newSender: BluetoothVideoSender
        newSender = BluetoothVideoSender(
            queueCapacity = activeSettings.queueCapacity,
            onLinkSample = { sample -> onLinkRateSample(rateController, "direct", sample) },
            debugDirectGattHeartbeat = debugDirectGattHeartbeat,
            debugGattWriteTiming = debugGattWriteTiming,
            onStatus = { message ->
                if (sender === newSender) setStatus(message)
            },
            onDisconnected = {
                runOnUiThread {
                    if (sender !== newSender) return@runOnUiThread
                    cameraEncoder?.close()
                    cameraEncoder = null
                    microphoneEncoder?.close()
                    microphoneEncoder = null
                    ivfReplay?.close()
                    ivfReplay = null
                    activeStreamSettings = null
                    thermalAdaptationApplied = false
                    thermalHandler.removeCallbacks(thermalRecoveryRunnable)
                    sender = null
                    updateConnectionDot(0)
                    stopForegroundSessionIfIdle()
                    startIdleCameraPreview()
                    updateStartEnabled()
                }
            },
            onRemoteKeyFrameNeeded = { cameraEncoder?.requestKeyFrame() },
            onRemoteJoinKeyFrame = { cameraEncoder?.requestKeyFrame(force = true) },
            thermalStatusProvider = { thermalStatus },
        )
        sender = newSender
        updateStartEnabled()
        val expectedPeerNodeIdPrefix = if (activeTransport.isBle) {
            selectedBlePeer()?.announcement?.nodeIdPrefix
        } else {
            null
        }
        newSender.connect(
            this,
            device,
            codec,
            activeSettings,
            activeTransport,
            rotationDegrees,
            expectedPeerNodeIdPrefix,
            debugReplayPath == null && debugBroadcastAudioEnabled,
            debugBlePhyPreference,
            debugCorruptRecord,
            debugMediaTraceSession != null,
            streamIoConfigFor(activeTransport),
            debugTraceSession = debugMediaTraceSession,
        ) {
            runOnUiThread {
                if (sender !== newSender) return@runOnUiThread
                updateConnectionDot(1)
                debugReplayPath?.let { replayPath ->
                    runCatching {
                        IvfVideoReplay(
                            File(replayPath),
                            codec.protocolId,
                            activeSettings,
                            debugImpairedSend(newSender::send),
                            { message -> if (sender === newSender) setStatus(message) },
                        ).also {
                            ivfReplay = it
                            it.start()
                        }
                    }.onFailure { error ->
                        if (sender === newSender) {
                            stopStreaming("Replay could not start: ${error.message ?: error.javaClass.simpleName}")
                        }
                    }
                    return@runOnUiThread
                }
                refineAutoStreamStart(listOfNotNull(newSender.authenticatedPeerNode()?.shortLabel()))
                val reusableViewfinderSurface = detachIdleCameraViewfinderSurface()
                clearVideoSurfaceTransform()
                lateinit var encoder: CameraVideoEncoder
                encoder = CameraVideoEncoder(
                    this,
                    cameraViewfinder,
                    cameraLens,
                    codec,
                    streamEncoderSettings(activeSettings),
                    benchmarkFrames,
                    debugMediaTraceSession,
                    debugImpairedSend(newSender::send),
                    { message ->
                        if (sender === newSender && cameraEncoder === encoder) setStatus(message)
                    },
                    reusableViewfinderSurface,
                    encoderRateControl,
                    videoScaleMode,
                    {
                        runOnUiThread {
                            if (
                                sender !== newSender || microphoneEncoder != null ||
                                !debugBroadcastAudioEnabled
                            ) return@runOnUiThread
                            runCatching { startMicrophoneAudio(newSender::send) }
                                .onFailure { error ->
                                    if (sender === newSender) {
                                        stopStreaming(
                                            "Microphone could not start: " +
                                                (error.message ?: error.javaClass.simpleName),
                                        )
                                    }
                                }
                        }
                    },
                    debugRawInputFile = debugRawInputFile,
            rawGeometry = debugRawGeometry,
                    gpuScale = streamGpuScale,
                    onSoftwareShortfall = softwareShortfallListener("stream"),
                )
                cameraEncoder = encoder
                runCatching {
                    encoder.start()
                    applyThermalAdaptation(thermalStatus)
                }.onFailure { error ->
                    if (sender === newSender) {
                        stopStreaming("Camera or microphone could not start: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private fun outgoingVideoRotationDegrees(): Int =
        if (debugReplayPath != null || debugRawInputFile != null) 0
        else CameraOrientation.currentCameraRotationDegrees(this, cameraLens)

    private fun armDiscoverableStream() {
        if (!ensureThermalReady()) return
        // Audit 2026-10-21: the first VIDEO live gets the same one-time "Before you go live" as audio only (never for automation).
        if (!audioLive.mode && !benchmarkExtrasPresent() && LiveDisclosure.needed(appPreferences.getBoolean(LiveDisclosure.PREF_VIDEO, false))) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.audio_live_disclosure_title)
                .setMessage(R.string.video_live_disclosure_message)
                .setPositiveButton(R.string.video_live_disclosure_confirm) { _, _ ->
                    appPreferences.edit().putBoolean(LiveDisclosure.PREF_VIDEO, true).apply()
                    armDiscoverableStream()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .showTracked()
            return
        }
        streamArmed = true
        if (!audioLive.mode) beginStreamSessionCodec()
        applyAutoLadderToStreamSettings()
        if (audioLive.mode) { // AUDIO-LIVE hook: microphone service type, no camera
            ActiveVideoSessionService.start(
                this, usesCamera = false, statusText = getString(R.string.audio_live_notification_broadcasting),
                usesMicrophone = true, audioLive = AudioLiveServiceMode.BROADCAST,
            )
        } else {
            ActiveVideoSessionService.start(this, usesCamera = true)
        }
        updateConnectionDot(0)
        setStatus(getString(R.string.live_status_waiting))
        updateStartEnabled()
        startStreamPresence()
    }

    @SuppressLint("MissingPermission")
    private fun startRequestedBroadcast() {
        if (!streamArmed || !ensureThermalReady()) return
        val peers = resolveClassicViewerPeers(pendingViewerPeers)
        if (peers.isEmpty()) {
            setStatus(getString(R.string.live_status_no_viewer))
            return
        }
        val audioConfig = audioLive.source?.sendConfig?.takeIf { audioLive.mode } // AUDIO-LIVE hook
        // Video: the encoder chosen when the stream went live (armDiscoverableStream), the same for every viewer.
        val codec = (if (audioConfig == null) activeStreamCodec() else codecs.getOrNull(codecSpinner.selectedItemPosition))
            ?: if (audioConfig != null) AudioLiveSendConfig.PLACEHOLDER_CODEC else return
        val activeSettings = activeStreamSettings ?: settings
        val rotationDegrees = if (audioConfig != null) 0 else outgoingVideoRotationDegrees()
        broadcaster?.let { activeBroadcaster ->
            activeBroadcaster.connect(
                this,
                peers,
                codec,
                streamAdmissionSettings(activeSettings),
                outgoingTransport,
                rotationDegrees,
                debugReplayPath == null && debugBroadcastAudioEnabled,
                debugBlePhyPreference,
                debugCorruptRecord,
                debugMediaTraceSession != null,
                streamIoConfigFor(outgoingTransport),
                debugMaximumChildren,
                debugTraceSession = debugMediaTraceSession,
                audioLiveConfig = audioConfig,
                gattPool = streamGattPool(),
            )
            refreshViewerDiscoveryForCapacity()
            return
        }
        activeStreamSettings = activeSettings
        applyThermalAdaptation(thermalStatus)
        val broadcastRateController = if (audioConfig != null) null else newLinkRateController(activeSettings, outgoingTransport)
        if (audioConfig == null) newStreamResolutionLadder(activeSettings, codec)
        lateinit var newBroadcaster: BluetoothVideoBroadcaster
        newBroadcaster = BluetoothVideoBroadcaster(
            queueCapacity = activeSettings.queueCapacity,
            onLinkSample = { peer, sample -> onLinkRateSample(broadcastRateController, peer, sample) },
            // Audio only has no encoder rate to lower, so a starved listener may be released at once.
            encoderAtRateFloor = { audioConfig != null || broadcastRateController?.atRateFloor == true },
            debugDirectGattHeartbeat = debugDirectGattHeartbeat,
            debugGattWriteTiming = debugGattWriteTiming,
            onStatus = { message -> if (broadcaster === newBroadcaster) setStatus(message) },
            onConnectionsChanged = { count ->
                runOnUiThread {
                    if (broadcaster === newBroadcaster) {
                        audioLive.onListenersChanged(count) // AUDIO-LIVE hook
                        updateConnectionDot(count)
                        updateEncoderFrameBudget(count)
                        updateStartEnabled()
                        refreshViewerDiscoveryForCapacity()
                        if (streamAdvertiser != null && streamArmed &&
                            newBroadcaster.sourceSlotsFull() != streamAdvertisedFull
                        ) {
                            // Tell viewers the source is full (or has a slot again) instead of
                            // letting them wait on "Connecting" and loop through cooldowns.
                            refreshStreamPresenceAdvertisement()
                        }
                    }
                }
            },
            onAllDisconnected = {
                runOnUiThread {
                    if (broadcaster !== newBroadcaster) return@runOnUiThread
                    streamMediaPrewarmed = false
                    audioLive.onAllListenersGone() // AUDIO-LIVE hook
                    cameraEncoder?.close()
                    cameraEncoder = null
                    microphoneEncoder?.close()
                    microphoneEncoder = null
                    ivfReplay?.close()
                    ivfReplay = null
                    activeStreamSettings = null
                    thermalAdaptationApplied = false
                    thermalHandler.removeCallbacks(thermalRecoveryRunnable)
                    sessionHandler.removeCallbacks(pausedHeartbeatRunnable)
                    streamPaused = false
                    broadcaster = null
                    updateConnectionDot(0)
                    startIdleCameraPreview()
                    startStreamPresence()
                    updateStartEnabled()
                }
            },
            onFirstConnected = {
                runOnUiThread {
                    if (broadcaster !== newBroadcaster) return@runOnUiThread
                    when (SourceMediaPrewarm.onFirstViewer(streamMediaPrewarmed, cameraEncoder != null)) {
                        SourceMediaPrewarm.FirstViewer.START_MEDIA -> startBroadcastMedia(newBroadcaster, codec, activeSettings)
                        SourceMediaPrewarm.FirstViewer.KEY_FRAME_AND_MICROPHONE -> {
                            // The encoder is already running: admitSender replays its codec configuration and onPeerReady
                            // forces the key frame; only the microphone, which waited for this viewer, starts here.
                            streamMediaPrewarmed = false
                            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK source_prewarm: event=first_viewer")
                            startBroadcastMicrophone(newBroadcaster)
                        }
                    }
                }
            },
            onPeerReady = { cameraEncoder?.requestKeyFrame(force = true) },
            onRemoteKeyFrameNeeded = { cameraEncoder?.requestKeyFrame() },
            onRemoteJoinKeyFrame = { cameraEncoder?.requestKeyFrame(force = true) },
            thermalStatusProvider = { thermalStatus },
            onCapacityAvailable = {
                runOnUiThread {
                    if (broadcaster === newBroadcaster && streamArmed) {
                        streamCapacityGeneration =
                            (streamCapacityGeneration + 1) and DiscoveryAnnouncement.SEQUENCE_GENERATION_MASK
                        if (streamAdvertiser != null) refreshStreamPresenceAdvertisement()
                    }
                }
            },
        )
        broadcaster = newBroadcaster
        newBroadcaster.logicalStreamToken = streamToken
        updateStartEnabled()
        newBroadcaster.connect(
            this,
            peers,
            codec,
            streamAdmissionSettings(activeSettings),
            outgoingTransport,
            rotationDegrees,
            debugReplayPath == null && debugBroadcastAudioEnabled,
            debugBlePhyPreference,
            debugCorruptRecord,
            debugMediaTraceSession != null,
            streamIoConfigFor(outgoingTransport),
            debugMaximumChildren,
            debugTraceSession = debugMediaTraceSession,
            audioLiveConfig = audioConfig,
            gattPool = streamGattPool(),
        )
        refreshViewerDiscoveryForCapacity()
        // Experimental (test_source_media_prewarm, off): camera and encoder warm up while the first viewer's link is set up.
        streamMediaPrewarmed = false
        if (SourceMediaPrewarm.applies(DebugTunables.sourceMediaPrewarm, audioLive.mode, autoLadderActive(), debugReplayPath != null)) {
            streamMediaPrewarmed = true
            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK source_prewarm: event=start")
            startBroadcastMedia(newBroadcaster, codec, activeSettings)
        }
    }

    /**
     * Design SG stage 2: the separate GATT child pool of an LE CoC video source, or null (the default: `coc_auto_gatt`
     * and `test_coc_auto_gatt_streams` both on). Null leaves the broadcaster byte-identical to before.
     */
    private fun streamGattPool(): GattChildPoolConfig? =
        if (outgoingTransport == BluetoothTransportOption.BLE_L2CAP && StreamGattFallback.active()) {
            GattChildPoolConfig(StreamGattFallback.GATT_POOL_CAPACITY, streamIoConfigFor(BluetoothTransportOption.BLE_GATT))
        } else {
            null
        }

    private fun toggleBroadcastPause() {
        if (sessionRole != SessionRole.SEND) return
        if (audioLive.mode) { // AUDIO-LIVE hook
            toggleAudioBroadcastPause()
            return
        }
        val activeBroadcaster = broadcaster
        val activeSender = sender
        if (activeBroadcaster == null && activeSender == null) return
        if (activeBroadcaster == null) {
            streamPaused = !streamPaused
            if (streamPaused) activeSender?.pauseStream() else activeSender?.resumeStream()
            setStatus(if (streamPaused) "Test stream paused." else "Test stream resumed.")
            updatePauseButton()
            updateStartEnabled()
            return
        }
        if (!streamPaused) {
            streamPaused = true
            liveMedia.onPaused() // a pause stops the camera anyway; the mute is kept and announced again on resume
            activeBroadcaster.pauseStream()
            cameraEncoder?.close()
            cameraEncoder = null
            microphoneEncoder?.close()
            microphoneEncoder = null
            ivfReplay?.close()
            ivfReplay = null
            startIdleCameraPreview()
            sessionHandler.removeCallbacks(pausedHeartbeatRunnable)
            sessionHandler.postDelayed(pausedHeartbeatRunnable, PAUSED_HEARTBEAT_MS)
            refreshStreamPresenceAdvertisement()
            setStatus(getString(R.string.live_status_paused_viewers))
        } else {
            val codec = activeStreamCodec() ?: return
            val activeSettings = activeStreamSettings ?: return
            streamPaused = false
            sessionHandler.removeCallbacks(pausedHeartbeatRunnable)
            activeBroadcaster.resumeStream()
            refreshStreamPresenceAdvertisement()
            stopIdleCameraPreview()
            setStatus(getString(R.string.live_status_resuming))
            startBroadcastMedia(activeBroadcaster, codec, activeSettings)
        }
        updatePauseButton()
        updateStartEnabled()
    }

    /** AUDIO-LIVE hook: pause releases the microphone; the lifecycle state travels through the broadcaster as for video. */
    private fun toggleAudioBroadcastPause() {
        val activeBroadcaster = broadcaster ?: return
        if (!streamPaused) {
            streamPaused = true
            activeBroadcaster.pauseStream()
            sessionHandler.removeCallbacks(pausedHeartbeatRunnable)
            sessionHandler.postDelayed(pausedHeartbeatRunnable, PAUSED_HEARTBEAT_MS)
            refreshStreamPresenceAdvertisement()
            setStatus(getString(R.string.live_status_paused_audio))
        } else {
            streamPaused = false
            sessionHandler.removeCallbacks(pausedHeartbeatRunnable)
            activeBroadcaster.resumeStream()
            refreshStreamPresenceAdvertisement()
            setStatus(getString(R.string.live_status_resumed))
        }
        audioLive.onPauseChanged(streamPaused)
        updateStartEnabled()
    }

    private fun startBroadcastMedia(
        activeBroadcaster: BluetoothVideoBroadcaster,
        codec: VideoCodec,
        activeSettings: VideoSettings,
    ) {
        if (broadcaster !== activeBroadcaster || streamPaused) return
        if (audioLive.mode) { // AUDIO-LIVE hook: the audio source replaces the camera and microphone encoders
            audioLive.onListenerReady(activeBroadcaster)
            return
        }
        debugReplayPath?.let { replayPath ->
            runCatching {
                IvfVideoReplay(
                    File(replayPath),
                    codec.protocolId,
                    activeSettings,
                    debugImpairedSend(activeBroadcaster::send),
                    { message -> if (broadcaster === activeBroadcaster) setStatus(message) },
                ).also {
                    ivfReplay = it
                    it.start()
                }
            }.onFailure { error ->
                if (broadcaster === activeBroadcaster) {
                    stopStreaming("Replay could not start: ${error.message ?: error.javaClass.simpleName}")
                }
            }
            return
        }
        refineAutoStreamStart(activeBroadcaster.connectedPeerLabels())
        val reusableViewfinderSurface = detachIdleCameraViewfinderSurface()
        clearVideoSurfaceTransform()
        lateinit var encoder: CameraVideoEncoder
        encoder = CameraVideoEncoder(
            this,
            cameraViewfinder,
            cameraLens,
            codec,
            streamEncoderSettings(activeSettings),
            benchmarkFrames,
            debugMediaTraceSession,
            debugImpairedSend(activeBroadcaster::send),
            { message ->
                if (broadcaster === activeBroadcaster && cameraEncoder === encoder) setStatus(message)
            },
            reusableViewfinderSurface,
            encoderRateControl,
            videoScaleMode,
            {
                runOnUiThread {
                    // Pre-warmed media: the microphone waits for an authenticated viewer (onFirstConnected starts it).
                    if (SourceMediaPrewarm.holdMicrophone(streamMediaPrewarmed, activeBroadcaster.connectedPeerLabels().size)) {
                        return@runOnUiThread
                    }
                    startBroadcastMicrophone(activeBroadcaster)
                }
            },
            debugRawInputFile = debugRawInputFile,
            rawGeometry = debugRawGeometry,
            gpuScale = streamGpuScale,
            onSoftwareShortfall = softwareShortfallListener("stream"),
        )
        cameraEncoder = encoder
        layeringPolicy?.let { policy -> encoder.setDroppableFrames(policy.layered) }
        runCatching {
            encoder.start()
            updateEncoderFrameBudget(1)
            applyThermalAdaptation(thermalStatus)
        }.onFailure { error ->
            if (broadcaster === activeBroadcaster) {
                stopStreaming("Camera or microphone could not start: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun startBroadcastMicrophone(activeBroadcaster: BluetoothVideoBroadcaster) {
        if (
            broadcaster !== activeBroadcaster || streamPaused || microphoneEncoder != null ||
                !debugBroadcastAudioEnabled
        ) return
        runCatching { startMicrophoneAudio(activeBroadcaster::send) }
            .onFailure { error ->
                if (broadcaster === activeBroadcaster) {
                    stopStreaming(
                        "Microphone could not start: " +
                            (error.message ?: error.javaClass.simpleName),
                    )
                }
            }
    }

    @SuppressLint("MissingPermission")
    private fun resolveClassicViewerPeers(peers: List<BleL2capPeer>): List<BleL2capPeer> {
        if (outgoingTransport != BluetoothTransportOption.CLASSIC_RFCOMM) return peers
        val bondedDevices = bluetoothAdapter?.bondedDevices.orEmpty()
        return peers.map { peer ->
            val nodePrefix = peer.announcement.nodeIdPrefix.joinToString("") { "%02x".format(it) }
            val debugAddressMatch = debugClassicAddress?.takeIf {
                nodePrefix == debugClassicAddressNodePrefix
            }?.let { bluetoothAdapter?.getRemoteDevice(it) }
            val addressMatch = bondedDevices.firstOrNull { device ->
                device.address.equals(peer.device.address, ignoreCase = true)
            }
            val discoveredName = runCatching { peer.device.name }.getOrNull()
            val uniqueNameMatch = if (addressMatch == null && !discoveredName.isNullOrBlank()) {
                bondedDevices.filter { device ->
                    runCatching { device.name }.getOrNull()
                        .equals(discoveredName, ignoreCase = true)
                }.singleOrNull()
            } else {
                null
            }
            peer.copy(device = debugAddressMatch ?: addressMatch ?: uniqueNameMatch ?: peer.device)
        }
    }

    /**
     * The call listener must be down before the viewer registers its GATT server or CoC listener (STREAM-INTEROP block 1):
     * an idle endpoint still up here is stopped, and while an endpoint start is still opening its listener the viewer
     * start waits for it (it then closes itself), at most [CALL_ENDPOINT_SETTLE_TIMEOUT_MS]. Returns true when deferred.
     * A ringing or connected call is never touched.
     */
    private fun callEndpointBeforeViewer(): Boolean {
        if (privateCallEndpointIdle() || (privateCallAvailabilityStarting && privateCallPhase == null)) {
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_reachability: endpoint stopped | reason=viewer_start",
            )
            stopPrivateCallEndpoint()
        }
        if (!callEndpointStarts.startInFlight) return false
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_reachability: viewer_start_deferred | reason=endpoint_start_in_flight",
        )
        callEndpointStarts.whenSettled { if (!isFinishing && !isDestroyed) startReceiving() }
        sessionHandler.removeCallbacks(callEndpointSettleTimeout)
        sessionHandler.postDelayed(callEndpointSettleTimeout, CALL_ENDPOINT_SETTLE_TIMEOUT_MS)
        return true
    }

    private fun startReceiving() {
        if (appScreen != AppScreen.PLAYBACK || receiver != null || !receiverStartAllowed(
                selectedStreamPeer != null,
                reconnectTarget != null,
                debugLaunchRequested && !debugAutoSelectStream,
            )
        ) return
        if (callEndpointBeforeViewer()) return
        if (!ensureThermalReady()) return
        val adapter = bluetoothAdapter ?: return
        val texture = preview.surfaceTexture ?: return
        // Review B2: this attempt has not become active yet (a reconnect no longer inherits the last receiver's flag).
        receiverActivity.startAttempt()
        val offeredTransport = selectedStreamPeer?.announcement?.transportOption() ?: outgoingTransport
        // Design SG stage 1 (Experimental, off): once this adapter session forced GATT, an L2CAP offer is joined over GATT.
        val forcedStreamChoice = CocRegistrations.session.forcedFor(offeredTransport, CocAutoGatt.current)
        val activeTransport = StreamGattFallback.receiverTransport(offeredTransport, StreamGattFallback.active(), forcedStreamChoice)
        if (activeTransport != offeredTransport && forcedStreamChoice != null) {
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    StreamGattFallback.restartLine(StreamGattFallback.ReceiverRestart.RESTART_ON_GATT, forcedStreamChoice.copy(effective = activeTransport), selectedStreamPeer?.announcement?.streamToken ?: 0),
            )
            StreamGattFallback.restartNotice(forcedStreamChoice).let { notice ->
                setStatus(notice)
                Toast.makeText(this, notice, Toast.LENGTH_LONG).show()
            }
        }
        ActiveVideoSessionService.start(
            this, usesCamera = false,
            // AUDIO-LIVE hook: a radio keeps playing with the screen off under a media-playback service.
            audioLive = if (audioLive.listening) AudioLiveServiceMode.LISTEN else AudioLiveServiceMode.NONE,
            // Q16: the stream's name shows on an unlocked phone only (NotificationPrivacy).
            peerName = selectedStreamPeer?.let { peerName(it) },
        )
        receiverStreamEnded = false
        receiverPlaybackPaused = false
        receiverRouteWithdrawal = null
        resetReceiverRecoveryNotice()
        if (receiverJoinStartedAtNs <= 0L) receiverJoinStartedAtNs = System.nanoTime()
        receiverFirstSurfaceForJoinPending = true
        lateinit var newReceiver: AndroidVideoReceiver
        newReceiver = AndroidVideoReceiver(
            Surface(texture),
            { header ->
                runOnUiThread {
                    if (receiver !== newReceiver) return@runOnUiThread
                    if (!preserveReceiverContinuityOnNextFormat) {
                        if (receiverContinuityTracker != null) finishReceiverContinuity()
                        if (receiverJoinStartedAtNs <= 0L) receiverJoinStartedAtNs = System.nanoTime()
                        receiverContinuityTracker = RenderedFrameContinuityTracker(
                            header.fps,
                            receiverJoinStartedAtNs,
                        )
                    }
                    setVideoSurfaceFormat(header.width, header.height, header.rotationDegrees)
                }
            },
            { message ->
                runOnUiThread {
                    if (receiver === newReceiver) setStatus(message)
                }
            },
            { active ->
                runOnUiThread {
                    if (receiver === newReceiver && appScreen == AppScreen.PLAYBACK) {
                        audioLive.onListenActive(active) // AUDIO-LIVE hook
                        if (active) {
                            receiverActivity.onActive()
                            selectedStreamPeer?.let { streamJoinRetry.onJoined(LogicalStreamTarget.from(it.announcement)) }
                            consecutiveRouteFailures.clear()
                            endStreamSearch()
                            reconnectFromNodeIdPrefix = null
                            preferredRecoveryParent = null
                            preserveReceiverContinuityOnNextFormat = false
                        }
                        updateConnectionDot(if (active) 1 else 0)
                    }
                }
            },
            { state ->
                runOnUiThread {
                    if (receiver !== newReceiver || appScreen != AppScreen.PLAYBACK) return@runOnUiThread
                    audioLive.onListenState(state) // AUDIO-LIVE hook
                    applyViewerMediaState(state)
                    when (state) {
                        StreamLifecycleState.LIVE -> {
                            viewerFrameAge?.onResumed()
                            receiverPlaybackPaused = false
                            receiverContinuityTracker?.setActive(true)
                            showReceiverOverlay("Resuming stream", showProgress = true)
                        }
                        // Muted / camera off while live: the notice is already drawn; only a paused stream resumes.
                        StreamLifecycleState.LIVE_MUTED,
                        StreamLifecycleState.LIVE_CAMERA_OFF,
                        StreamLifecycleState.LIVE_MUTED_CAMERA_OFF -> {
                            if (receiverPlaybackPaused) {
                                viewerFrameAge?.onResumed()
                                receiverPlaybackPaused = false
                                receiverContinuityTracker?.setActive(true)
                                hideReceiverOverlay()
                            }
                        }
                        StreamLifecycleState.PAUSED -> {
                            viewerFrameAge?.onPaused()
                            receiverPlaybackPaused = true
                            resetReceiverRecoveryNotice()
                            receiverContinuityTracker?.setActive(false)
                            showReceiverOverlay("Paused by broadcaster", showProgress = false)
                        }
                        StreamLifecycleState.ENDED -> {
                            viewerFrameAge?.onEnded()
                            resetReceiverRecoveryNotice()
                            receiverContinuityTracker?.setActive(false)
                            receiverStreamEnded = true
                            updateConnectionDot(0)
                            showReceiverOverlay("Stream ended", showProgress = false)
                        }
                    }
                }
            },
            preferLowLatencyDecoder = debugLowLatencyDecoder,
            playbackDelayProfile = receiverPlaybackDelay,
            playbackDelayMs = debugPlaybackDelayMs ?: receiverPlaybackDelay.delayMs,
            preferredDecoderName = debugPreferredDecoderName,
            debugPresentationTiming = debugVideoPresentationTiming,
            debugReleaseAtPlayout = debugReleaseVideoAtPlayout,
            debugPacketDelayMs = debugReceiverPacketDelayMs,
            debugForwarderGattPriority = debugForwarderGattPriority,
            debugDirectGattHeartbeat = debugDirectGattHeartbeat,
            debugTraceSessionName = debugMediaTraceSession,
            viewerFrameAge = viewerFrameAge?.takeIf { it.enabled }?.monitor,
            forwardReceivedStreams = effectiveForwarding(selectedStreamPeer?.announcement?.streamToken ?: 0),
            allowAudioOnly = AudioLiveFeatures.enabled, // AUDIO-LIVE hook
            onAudioLiveStream = { header ->
                runOnUiThread { if (receiver === newReceiver) audioLive.onListenFormat(header) }
            },
            onAudioLiveLevel = { level -> runOnUiThread { if (receiver === newReceiver) audioLive.onListenLevel(level) } },
            expectedMediaSourceNodeId = activeStreamSourceNodeId,
            onMediaSourceAuthenticated = { sourceNodeId, authenticated ->
                if (receiver === newReceiver) {
                    if (authenticated && LocalReporting.hidden(applicationContext, sourceNodeId.toString())) {
                        // AB3: this route is hidden for this run only, as for a blocked person; never its bare token.
                        selectedStreamPeer?.announcement?.let(PersonPolicy::hideStream)
                        runOnUiThread { if (receiver === newReceiver) showReceiverBrowser() }
                        throw java.io.IOException("Reported source")
                    }
                    // Review 2026-10-27 (finding 4): a blocked person's stream is left as soon as its source is known: the
                    // direct source's handshake identity, or the BFG1 source a forwarder names. Only an authenticated source
                    // hides the offer (also under a rotating ID); a forwarder's bare claim only ends this route, so a lying
                    // forwarder cannot hide a genuine source's stream from this viewer (owner rule 2026-10-27).
                    if (PersonPolicy.blockedNode(sourceNodeId)) {
                        if (authenticated) selectedStreamPeer?.announcement?.let(PersonPolicy::hideStream)
                        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_blocking: event=stream_left | kind=video | reason=blocked_person")
                        runOnUiThread { if (receiver === newReceiver) showReceiverBrowser() }
                        throw java.io.IOException("Blocked source")
                    }
                    activeStreamSourceAuthenticated = authenticated
                    activeStreamSourceNodeId = sourceNodeId
                }
            },
            onVideoRecovery = { recovering ->
                runOnUiThread {
                    if (receiver !== newReceiver || appScreen != AppScreen.PLAYBACK) {
                        return@runOnUiThread
                    }
                    receiverRecoveryNoticeRunnable?.let(sessionHandler::removeCallbacks)
                    receiverRecoveryNoticeRunnable = null
                    receiverRecoveryCoverRelease?.let(sessionHandler::removeCallbacks)
                    receiverRecoveryCoverRelease = null
                    val nowMs = SystemClock.elapsedRealtime()
                    receiverRecoveryNoticePolicy.update(recovering, nowMs)
                    Log.i(
                        "BluetoothVideo",
                        "Receiver recovery UI: recovering=$recovering | firstSurfacePending=$receiverFirstSurfaceForJoinPending | " +
                            "noticeAlpha=${receiverWaitingText.alpha}",
                    )
                    if (receiverPlaybackPaused || receiverStreamEnded || receiverFirstSurfaceForJoinPending) {
                        return@runOnUiThread
                    }
                    if (recovering) {
                        // Keep the historical corruption cover; brief recovery needs no flashing label.
                        if (receiverRecoveryNoticePolicy.noticeDelayRemainingMs(nowMs) != 0L) {
                            showReceiverOverlay("", showProgress = false)
                        }
                        val notice = object : Runnable {
                            override fun run() {
                                if (receiverRecoveryNoticeRunnable !== this || receiver !== newReceiver ||
                                    appScreen != AppScreen.PLAYBACK || !receiverRecoveryNoticePolicy.recovering ||
                                    receiverPlaybackPaused || receiverStreamEnded
                                ) return
                                val alpha = receiverRecoveryNoticePolicy.noticeAlpha(SystemClock.elapsedRealtime())
                                showReceiverOverlay("Recovering video", showProgress = true, noticeAlpha = alpha)
                                if (alpha < 1f) {
                                    sessionHandler.postDelayed(this, 16L)
                                } else {
                                    receiverRecoveryNoticeRunnable = null
                                    Log.i("BluetoothVideo", "Receiver recovery notice: fully visible")
                                }
                            }
                        }
                        receiverRecoveryNoticeRunnable = notice
                        sessionHandler.postDelayed(
                            notice,
                            receiverRecoveryNoticePolicy.noticeDelayRemainingMs(nowMs) ?: 0L,
                        )
                    } else if (receiverWaitingText.text.toString() in setOf("Recovering video", "")) {
                        val holdMs = receiverRecoveryNoticePolicy.holdRemainingMs(nowMs)
                        if (holdMs > 0L) {
                            // A repeating recovery: keep the cover through the gap so it does not flash
                            // (ReceiverRecoveryNoticePolicy). An isolated one clears at once, as before.
                            val release = object : Runnable {
                                override fun run() {
                                    if (receiverRecoveryCoverRelease !== this) return
                                    receiverRecoveryCoverRelease = null
                                    if (receiver === newReceiver && appScreen == AppScreen.PLAYBACK &&
                                        !receiverRecoveryNoticePolicy.coverHeld(SystemClock.elapsedRealtime()) &&
                                        receiverWaitingText.text.toString() in setOf("Recovering video", "")
                                    ) {
                                        Log.i("BluetoothVideo", "Receiver recovery UI: held cover released after $holdMs ms")
                                        hideReceiverOverlay()
                                    }
                                }
                            }
                            receiverRecoveryCoverRelease = release
                            sessionHandler.postDelayed(release, holdMs)
                            Log.i("BluetoothVideo", "Receiver recovery UI: repeating episode, cover held for $holdMs ms")
                        } else {
                            hideReceiverOverlay()
                        }
                    }
                }
            },
            onVideoPausedWeakLink = { paused ->
                // Experimental relay child thinning: the relay put this link at the audio-only level.
                runOnUiThread {
                    if (receiver !== newReceiver || appScreen != AppScreen.PLAYBACK) return@runOnUiThread
                    if (paused) {
                        if (!receiverPlaybackPaused && !receiverStreamEnded) {
                            showReceiverOverlay(WEAK_LINK_PAUSE_TEXT, showProgress = false)
                        }
                    } else if (receiverWaitingText.text.toString() == WEAK_LINK_PAUSE_TEXT) {
                        hideReceiverOverlay()
                    }
                }
            },
            onRouteWithdrawn = { reason ->
                runOnUiThread {
                    if (receiver !== newReceiver) return@runOnUiThread
                    receiverRouteWithdrawal = reason
                    selectedStreamPeer?.let { peer ->
                        val withdrawnRoute = StreamReconnectTarget.from(peer)
                        suppressedStreamRoutes[withdrawnRoute] = streamRouteSuppressionDeadline(
                            suppressedStreamRoutes[withdrawnRoute],
                            SystemClock.elapsedRealtime() + WITHDRAWN_ROUTE_SUPPRESSION_MS,
                        )
                        if (reason == StreamRouteWithdrawal.LOCAL_CAPACITY) {
                            capacitySuppressedRouteGenerations[withdrawnRoute] = peer.announcement.capacityGeneration()
                        }
                    }
                }
            },
        ) { failureNotice ->
            runOnUiThread {
                if (receiver !== newReceiver) return@runOnUiThread
                val peer = selectedStreamPeer
                val ended = receiverStreamEnded
                val wasActive = receiverWasActive
                val routeWithdrawal = receiverRouteWithdrawal
                val stopCause = newReceiver.stopCause
                // Design SG stage 1 (Experimental, off): this receiver's own LE CoC listen failed, so join over GATT.
                val listenFailure = newReceiver.listenFailure
                val restartVerdict = StreamGattFallback.receiverRestart(
                    active = StreamGattFallback.active(),
                    receiverTransport = activeTransport,
                    stopKind = stopCause?.kind,
                    listenFailed = listenFailure != null,
                    wasActive = wasActive,
                    ended = ended,
                    ledgerAlreadyForced = CocRegistrations.session.forcedFor(offeredTransport, CocAutoGatt.current) != null,
                )
                if (listenFailure != null) {
                    Log.i(
                        "BluetoothVideo",
                        (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                            StreamGattFallback.restartLine(restartVerdict, null, peer?.announcement?.streamToken ?: 0),
                    )
                }
                if (restartVerdict.restart && appScreen == AppScreen.PLAYBACK) {
                    val gattChoice = CocRegistrations.session.onCocFailure(
                        offeredTransport, CocAutoGatt.current, CocFallbackReason.AVAILABILITY_FAILED,
                    )
                    if (gattChoice != null) {
                        resetReceiverRecoveryNotice()
                        receiver = null
                        startReceiving()
                        return@runOnUiThread
                    }
                }
                val recoveryConfig = RelayRecoveryConfig.current()
                preferredRecoveryParent = recoveryParentPreference(
                    wasActive = wasActive,
                    linkLoss = routeWithdrawal == null && stopCause?.kind == ReceiverStopKind.LINK && !ended,
                    parentRouteCost = peer?.announcement?.routeCost,
                    parentPrefix = peer?.announcement?.nodeIdPrefix,
                    enabled = recoveryConfig.preferParent,
                )
                preferredRecoveryGraceUntilMs = preferredParentGraceDeadline(
                    preferredRecoveryParent,
                    SystemClock.elapsedRealtime(),
                    recoveryConfig.recoveryGrace,
                )
                preferredRecoveryGraceLogged = false
                sessionHandler.removeCallbacks(preferredRecoveryGraceExpired)
                if (preferredRecoveryGraceUntilMs > 0L) {
                    // Offers keep arriving while scanning; this re-check covers a quiet scan.
                    sessionHandler.postDelayed(preferredRecoveryGraceExpired, PREFERRED_PARENT_GRACE_MS)
                }
                val localMediaFailure = stopCause?.retriesAutomatically == false
                // AUDIO-LIVE hook: a marker that disagreed with the authenticated header costs only this route, for 2 minutes.
                if (newReceiver.markerMismatchMarkedAudio != null && peer != null) {
                    streamRoutePenalties.onMismatch(streamRouteKey(peer), null, SystemClock.elapsedRealtime())
                    Log.i("BluetoothVideo", "BENCHMARK audio_live_marker_mismatch: route=${streamRouteKey(peer).prefixHex} | marked=${newReceiver.markerMismatchMarkedAudio}")
                }
                var establishFallback = false
                if (DebugTunables.relayEstablishFallback && peer != null) {
                    val parentKey = AdaptivePhyMemory.prefixLabel(peer.announcement.nodeIdPrefix)
                    val nowMs = SystemClock.elapsedRealtime()
                    if (wasActive) {
                        parentEstablishment.onSuccess(parentKey)
                    } else if (!ended && isChildEstablishmentFailure(wasActive, stopCause?.signature) &&
                        parentEstablishment.onFailure(parentKey, nowMs)
                    ) {
                        // The chosen parent cannot establish a link: look for the next-best route to
                        // the same stream now instead of waiting for another explicit choice.
                        establishFallback = true
                        Log.i(
                            "BluetoothVideo",
                            "BENCHMARK relay_parent_fallback: reason=establishment | side=child | parent=$parentKey | " +
                                "hops=${peer.announcement.routeCost} | failures=${EstablishmentFailureTracker.THRESHOLD} | " +
                                "windowMs=${EstablishmentFailureTracker.WINDOW_MS} | markMs=${EstablishmentFailureTracker.MARK_MS}",
                        )
                    }
                }
                val repeatBackoffMs = if (!ended && !localMediaFailure && stopCause != null) {
                    repeatedReceiverFailures.record(stopCause.signature, SystemClock.elapsedRealtime())
                } else {
                    0L
                }
                if (localMediaFailure) {
                    receiverLocalMediaFailure = failureNotice
                    Log.w(
                        "BluetoothVideo",
                        "BENCHMARK receiver local media failure: no reconnect | cause=${stopCause?.signature}",
                    )
                } else if (repeatBackoffMs > 0L) {
                    Log.w(
                        "BluetoothVideo",
                        "Receiver failure repeated ${repeatedReceiverFailures.repeats}x: backing off " +
                            "$repeatBackoffMs ms | cause=${stopCause?.signature}",
                    )
                }
                resetReceiverRecoveryNotice()
                receiver = null
                // The picture on screen is now frozen: say so before the older overlay is asked to draw.
                if (!ended && !localMediaFailure && appScreen == AppScreen.PLAYBACK) viewerFrameAge?.onLinkLost()
                stopForegroundSessionIfIdle()
                updateStartEnabled()
                if (appScreen == AppScreen.PLAYBACK) {
                    returnToStreamBrowser(
                        peer,
                        ended,
                        allowReconnect = !localMediaFailure &&
                            (wasActive || routeWithdrawal == StreamRouteWithdrawal.LOCAL_CAPACITY || establishFallback),
                        failureNotice = if (!localMediaFailure && routeWithdrawal == StreamRouteWithdrawal.LOCAL_CAPACITY) {
                            streamCapacityWithdrawalNotice(peer?.announcement?.routeCost)
                        } else {
                            failureNotice
                        },
                        localMediaFailure = localMediaFailure,
                        minimumRetryDelayMs = repeatBackoffMs,
                    )
                }
                audioLive.onListenLinkLost(reconnecting = appScreen == AppScreen.PLAYBACK) // AUDIO-LIVE hook
            }
        }
        activeStreamSourceAuthenticated = false
        receiver = newReceiver
        viewerFrameAge?.onLinkStarted()
        updateStartEnabled()
        val streamPeer = selectedStreamPeer
        runCatching {
            newReceiver.start(
                this,
                adapter,
                activeTransport,
                streamToken = streamPeer?.announcement?.streamToken ?: 0,
                routeCost = streamPeer?.announcement?.routeCost ?: 0,
                streamBitrateKbps = streamPeer?.announcement?.streamBitrateKbps ?: 0,
                expectedSourceNodeIdPrefix = streamPeer?.announcement?.nodeIdPrefix,
                recoveryFromNodeIdPrefix = reconnectFromNodeIdPrefix,
                phyPreference = debugBlePhyPreference,
                ioConfig = streamIoConfigFor(activeTransport),
                selectedOfferRotating = streamPeer?.announcement?.rotating == true,
                selectedOfferAudioOnly = streamPeer?.announcement?.isAudioOnlyOffer() == true, // AUDIO-LIVE hook
            )
        }.onFailure { error ->
            if (receiver === newReceiver) {
                receiver = null
                newReceiver.close()
                stopForegroundSessionIfIdle()
                Log.w("BluetoothVideo", "Receiver could not start", error)
                setStatus(getString(R.string.watch_failed))
                updateStartEnabled()
            }
        }
    }

    private fun startRelaying() {
        if (!ensureThermalReady()) return
        val adapter = bluetoothAdapter ?: return
        val children = if (debugRelayChildCount > 1) {
            blePeers.take(debugRelayChildCount)
        } else {
            listOfNotNull(selectedBlePeer())
        }
        if (children.size != debugRelayChildCount) {
            setStatus("Waiting for $debugRelayChildCount distinct receivers.")
            return
        }
        ActiveVideoSessionService.start(this, usesCamera = false)
        bleDiscovery?.close()
        bleDiscovery = null
        if (debugPrivateCallRelay) {
            val callee = children.singleOrNull() ?: run {
                setStatus("A private call relay requires one callee.")
                return
            }
            lateinit var newRelay: BluetoothPrivateCallRelay
            newRelay = BluetoothPrivateCallRelay(
                { message -> if (privateCallRelay === newRelay) setStatus(message) },
            ) {
                runOnUiThread {
                    if (privateCallRelay !== newRelay) return@runOnUiThread
                    privateCallRelay = null
                    stopForegroundSessionIfIdle()
                    updateStartEnabled()
                }
            }
            privateCallRelay = newRelay
            updateStartEnabled()
            runCatching { newRelay.start(this, adapter, callee, outgoingTransport) }.onFailure { error ->
                if (privateCallRelay === newRelay) {
                    privateCallRelay = null
                    newRelay.close()
                    stopForegroundSessionIfIdle()
                    setStatus("Private-call relay could not start: ${error.message ?: error.javaClass.simpleName}")
                    updateStartEnabled()
                }
            }
            return
        }
        lateinit var newRelay: BluetoothVideoRelay
        newRelay = BluetoothVideoRelay(
            { message ->
                if (relay === newRelay) setStatus(message)
            },
        ) {
            runOnUiThread {
                if (relay !== newRelay) return@runOnUiThread
                relay = null
                stopForegroundSessionIfIdle()
                updateStartEnabled()
            }
        }
        relay = newRelay
        updateStartEnabled()
        runCatching { newRelay.start(this, adapter, children, outgoingTransport) }.onFailure { error ->
            if (relay === newRelay) {
                relay = null
                newRelay.close()
                stopForegroundSessionIfIdle()
                Log.w("BluetoothVideo", "Relay could not start", error)
                setStatus(getString(R.string.relay_failed))
                updateStartEnabled()
            }
        }
    }

    private fun stopStreaming(message: String) {
        streamSessionEncoder = null
        val impairment = debugBroadcastImpairment
        impairment?.cancel()
        debugBroadcastImpairment = null
        resetReceiverRecoveryNotice()
        finishReceiverContinuity()
        thermalHandler.removeCallbacks(thermalRecoveryRunnable)
        sessionHandler.removeCallbacks(pausedHeartbeatRunnable)
        thermalAdaptationApplied = false
        streamArmed = false
        streamPaused = false
        liveMedia.reset()
        liveCameraBusy = false
        viewerMediaNotice = ViewerMediaNotice.NONE
        renderLiveMediaNotice(ViewerMediaNotice.NONE)
        if (usesBroadcastDiscovery()) {
            stopStreamPresence()
        }
        localTestRunning = false
        val activeSender = sender
        sender = null
        activeSender?.endStream()
        val activeBroadcaster = broadcaster
        broadcaster = null
        activeBroadcaster?.endStream()
        val activeReceiver = receiver
        receiver = null
        activeReceiver?.close()
        val activeRelay = relay
        relay = null
        activeRelay?.close()
        val activePrivateCallRelay = privateCallRelay
        privateCallRelay = null
        activePrivateCallRelay?.close()
        ActiveVideoSessionService.stop(this)
        cameraEncoder?.close()
        cameraEncoder = null
        microphoneEncoder?.close()
        microphoneEncoder = null
        ivfReplay?.close()
        ivfReplay = null
        impairment?.close()
        activeStreamSettings = null
        restoreManualStreamSettings()
        clearVideoSurfaceTransform()
        audioLive.onStopped(message) // AUDIO-LIVE hook
        if (appScreen == AppScreen.CAMERA) {
            startIdleCameraPreview()
            if (usesBroadcastDiscovery()) {
                startStreamPresence()
            }
        }
        setStatus(message)
        updateStartEnabled()
    }

    private fun finishReceiverContinuity() {
        val tracker = receiverContinuityTracker ?: return
        logReceiverContinuity(tracker.snapshot(System.nanoTime()), final = true)
        receiverContinuityTracker = null
        receiverJoinStartedAtNs = 0L
        receiverContinuityLastReportNs = 0L
    }

    private fun logReceiverContinuity(
        snapshot: RenderedFrameContinuitySnapshot,
        final: Boolean = false,
        label: String = "Receiver",
    ) {
        Log.i(
            "BluetoothVideo",
            "$label visible continuity${if (final) " FINAL" else ""}: " +
                "${snapshot.renderedFrames} frames | ${snapshot.freezeEvents} freeze event(s), " +
                "%.1f ms, %.2f%% | threshold %.1f ms".format(
                    Locale.US,
                    snapshot.freezeDurationMs,
                    snapshot.freezeRatioPercent,
                    snapshot.freezeThresholdMs,
                ) + " | surface cadence p50/p95/p99/max %.1f/%.1f/%.1f/%.1f ms".format(
                    Locale.US,
                    snapshot.surfaceFrameInterval.p50Ms,
                    snapshot.surfaceFrameInterval.p95Ms,
                    snapshot.surfaceFrameInterval.p99Ms,
                    snapshot.surfaceFrameInterval.maxMs,
                ) + continuityFirstSurfaceSuffix(snapshot),
        )
        // Both arms: user-centred continuity (frozen share, longest stall) for the media-age A/B.
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK continuity: role=${if (label == "Receiver") "stream_rx" else "call_rx"} | final=$final | " +
                "policy=${BoundedMediaAge.policyLabel()} | frames=${snapshot.renderedFrames} | " +
                "freezeEvents=${snapshot.freezeEvents} | " +
                "frozenMs=%.1f | frozenPct=%.2f | longestStallMs=%.1f | activeMs=%.1f".format(
                    Locale.US,
                    snapshot.freezeDurationMs,
                    snapshot.freezeRatioPercent,
                    snapshot.longestGapMs,
                    snapshot.observedActiveMs,
                ),
        )
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophoneAudio(
        onPacket: (flags: Int, timeUs: Long, bytes: ByteArray) -> Unit,
    ) {
        check(microphoneEncoder == null) { "Microphone audio is already active" }
        lateinit var newEncoder: MicrophoneOpusEncoder
        newEncoder = MicrophoneOpusEncoder(
            debugImpairedSend({ flags, timeUs, bytes ->
                if (liveMedia.passesAudio(flags)) onPacket(flags, timeUs, bytes)
            }),
            { message -> if (microphoneEncoder === newEncoder) setStatus(message) },
            { message ->
                runOnUiThread {
                    if (microphoneEncoder === newEncoder) {
                        stopStreaming("Microphone audio stopped: $message")
                    }
                }
            },
            debugReferenceFile = debugReferenceAudioFile,
            debugReferenceVideoFrames = referenceAudioVideoFrames(),
            debugReferenceVideoFps = (activeStreamSettings ?: settings).fps,
            debugReferenceClock = { cameraEncoder?.benchmarkRawClock },
            debugTraceContext = this,
            debugTraceSession = debugMediaTraceSession,
        )
        microphoneEncoder = newEncoder
        runCatching { newEncoder.start() }.onFailure {
            if (microphoneEncoder === newEncoder) microphoneEncoder = null
            newEncoder.close()
            throw it
        }
    }

    private fun stopForegroundSessionIfIdle() {
        if (!streamArmed && sender == null && broadcaster == null && receiver == null && relay == null &&
            privateCallRelay == null
        ) {
            ActiveVideoSessionService.stop(this)
        }
    }

    private fun setVideoSurfaceFormat(
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ) {
        surfaceVideoFormat = SurfaceVideoFormat(
            width,
            height,
            rotationDegrees,
            videoScaleMode,
        )
        applyVideoSurfaceTransform()
    }

    private fun applyVideoSurfaceTransform() {
        val format = surfaceVideoFormat ?: return
        VideoSurfaceTransform.apply(
            preview,
            format.width,
            format.height,
            format.rotationDegrees,
            format.scaleMode,
            debugTraceSession = debugMediaTraceSession,
        )
    }

    private fun restoreVideoScaleMode() {
        videoScaleMode = VideoScaleMode.fromUnifiedOrLegacyValues(
            appPreferences.getString(PREF_VIDEO_SCALE_MODE, null),
            appPreferences.getString(PREF_SENDER_PREVIEW_SCALE_MODE, null),
            appPreferences.getString(PREF_RECEIVER_PLAYBACK_SCALE_MODE, null),
        )
        appPreferences.edit()
            .putString(PREF_VIDEO_SCALE_MODE, videoScaleMode.name)
            .remove(PREF_SENDER_PREVIEW_SCALE_MODE)
            .remove(PREF_RECEIVER_PLAYBACK_SCALE_MODE)
            .apply()
    }

    private fun applyVideoScaleMode() {
        cameraViewfinder.scaleType = when (videoScaleMode) {
            VideoScaleMode.FILL -> ScaleType.FILL_CENTER
            VideoScaleMode.FIT -> ScaleType.FIT_CENTER
        }
        surfaceVideoFormat = surfaceVideoFormat?.copy(scaleMode = videoScaleMode)
        applyVideoSurfaceTransform()
        privateCallSurfaceFormat = privateCallSurfaceFormat?.copy(scaleMode = videoScaleMode)
        callLocalViewfinder.scaleType = when (videoScaleMode) {
            VideoScaleMode.FILL -> ScaleType.FILL_CENTER
            VideoScaleMode.FIT -> ScaleType.FIT_CENTER
        }
        applyPrivateCallSurfaceTransform()
    }

    private fun clearVideoSurfaceTransform() {
        surfaceVideoFormat = null
        if (::preview.isInitialized) preview.setTransform(null)
    }

    private fun startIdleCameraPreview() {
        if (audioLive.mode || !RawInputGuard.cameraMayStart(debugRawInputFile != null) || !activityResumed || appScreen != AppScreen.CAMERA || sessionRole != SessionRole.SEND ||
            sender != null || (broadcaster != null && !streamPaused) || receiver != null ||
            cameraEncoder != null || cameraPreview != null ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        lateinit var newPreview: CameraPreview
        newPreview = CameraPreview(
            this,
            cameraViewfinder,
            cameraLens,
            settings.resolution,
            videoScaleMode,
        ) { message -> if (cameraPreview === newPreview) setStatus(message) }
        cameraPreview = newPreview
        runCatching { newPreview.start() }.onFailure { error ->
            if (cameraPreview === newPreview) cameraPreview = null
            newPreview.close()
            Log.w("BluetoothVideo", "Camera preview could not start", error)
            setStatus(getString(R.string.camera_failed))
        }
    }

    private fun stopIdleCameraPreview() {
        cameraPreview?.close()
        cameraPreview = null
    }

    private fun detachIdleCameraViewfinderSurface(): Pair<Surface, CameraViewfinderSurface>? {
        val activePreview = cameraPreview ?: return null
        cameraPreview = null
        val surface = activePreview.detachViewfinderSurface()
        activePreview.close()
        return surface
    }

    private fun restartIdleCameraPreview() {
        stopIdleCameraPreview()
        startIdleCameraPreview()
    }

    override fun onResume() {
        val resumeTimer = StartupTimer(SystemClock::elapsedRealtime)
        super.onResume()
        wifiFeatureBlock?.refresh()
        logMessageLifecycle("resume")
        // Back from the Bluetooth settings (or after the 2-minute staleness): the hint follows the scanner's state.
        bindCallEmptyState()
        // Design decision 2026-11-06: an unreadable block list shows its note under Settings > Blocked people.
        CallBlockingUi.refreshBlockListNote(this)
        activityResumed = true
        if (landOnCallsAfterOnboarding && OnboardingActivity.isDone(this)) {
            landOnCallsAfterOnboarding = false
            // The onboarding question "Hide your phone's name" was answered: apply it now.
            applyNeutralName()
            // Ask only for what is still missing; what was just refused on the permission page waits for a tap.
            postOnboardingRefused = OnboardingState.takeRefused()
            showCallsNearby()
        }
        // Back from Wi-Fi settings: the call or stream the Wi-Fi advice paused goes on (audit 2026-10-21).
        pendingAfterWifiSettings?.let { pending ->
            pendingAfterWifiSettings = null
            window.decorView.post { if (!isFinishing && !isDestroyed) pending() }
        }
        if (!startupAborted) debugRawObserver?.start()
        if (!bluetoothStateReceiverRegistered) {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED)
                if (WifiDiscovery.widerReachOn()) {   // X4: nothing new is registered with the switch off
                    addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
                    addAction(android.location.LocationManager.MODE_CHANGED_ACTION)
                }
            }
            registerReceiver(bluetoothStateReceiver, filter)
            bluetoothStateReceiverRegistered = true
        }
        updateHomeReadiness()
        // Back from the app's settings page: the camera screen's access notice follows what was granted.
        if (::streamToggleButton.isInitialized && !startupAborted) updateStartEnabled()
        IncomingUiWatch.shared.onCallScreenShown() // no-op unless an incoming-call full-screen alert is waiting for its screen
        verifiedStoreBanner?.start()
        // Names saved or changed in the verify flow or Contacts screen show in the lists at once.
        refreshContactNames()
        refreshMessagesUnread(notify = false)
        // The launch itself came from a message notification (the activity was not running).
        openConversationFromIntent(intent)
        if (::callsTabScreen.isInitialized && callsTabScreen.visibility == View.VISIBLE) updateRecentCalls()
        // The calls area: Calls (Nearby or Recent segment) and Contacts.
        checkFullScreenCallAccess(
            offerPrompt = ::callsTabScreen.isInitialized && currentTab().let { it == MainTab.CALLS || it == MainTab.CONTACTS },
        )
        updatesHome?.onResume()
        maybeStartGapNameProbe("resume")
        // Pick up link capacity measured since the picker was configured (workstream D).
        if (::bitrateSeekBar.isInitialized) {
            applyTransportBitrateLimit(persist = false)
            updateSettingsLabels()
        }
        if (BuildConfig.DEBUG && privateCall != null) Log.i("BluetoothVideo", "BENCHMARK call lifecycle foreground applied")
        if (appScreen == AppScreen.PLAYBACK) receiverContinuityTracker?.setActive(true)
        viewerFrameAge?.onForeground(true)
        if (appScreen == AppScreen.CALL && callRemoteCameraOff.visibility != View.VISIBLE) {
            debugPrivateCallSurfacePending = BuildConfig.DEBUG
            privateCallContinuityTracker?.setActive(true)
        }
        if (appScreen == AppScreen.CALL) callScreenController.setForeground(true)
        privateCallRawSelfView?.setPaused(false)
        thermalHandler.removeCallbacks(thermalDisplayRunnable)
        thermalHandler.post(thermalDisplayRunnable)
        if (activeCameraViewfinderNeedsRefresh) {
            activeCameraViewfinderNeedsRefresh = false
            cameraEncoder?.refreshViewfinderSurface()
            privateCallCameraEncoder?.refreshViewfinderSurface()
        }
        startIdleCameraPreview()
        if (appScreen == AppScreen.RECEIVER_BROWSER && hasRequiredPermissions()) {
            refreshNearbyStreams()
        }
        if (appScreen == AppScreen.CALL_BROWSER && hasRequiredPermissions()) {
            startPrivateCallEndpoint()
        }
        if (threadScreenDelegate.isInitialized()) threadScreen.onResume()
        resumeTimer.mark("all")
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + resumeTimer.line("onResume"))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Sessions are owned by this activity, so recreating it would end a live stream or call
        // (theme schedule, font/display size, split-screen, or Android 16 large-screen rotation).
        // Apply new resources by recreating only when nothing is live.
        val live = streamArmed || sender != null || broadcaster != null || receiver != null ||
            relay != null || privateCallRelay != null ||
            (privateCall != null && !(idleEndpointScreen() && privateCallEndpointIdle()))
        Log.i("BluetoothVideo", "Configuration changed | liveSession=$live | recreate=${!live}")
        if (!live) recreate()
    }

    override fun onPause() {
        logMessageLifecycle("pause")
        activityResumed = false
        verifiedStoreBanner?.stop()
        if (bluetoothStateReceiverRegistered) {
            runCatching { unregisterReceiver(bluetoothStateReceiver) }
            bluetoothStateReceiverRegistered = false
        }
        if (BuildConfig.DEBUG && privateCall != null) Log.i("BluetoothVideo", "BENCHMARK call lifecycle background applied")
        if (appScreen == AppScreen.PLAYBACK) receiverContinuityTracker?.setActive(false)
        viewerFrameAge?.onForeground(false)
        if (appScreen == AppScreen.CALL) privateCallContinuityTracker?.setActive(false)
        if (::callScreenController.isInitialized) callScreenController.setForeground(false)
        privateCallRawSelfView?.setPaused(true)
        thermalHandler.removeCallbacks(thermalDisplayRunnable)
        if (appScreen == AppScreen.RECEIVER_BROWSER) stopNearbyStreamDiscovery()
        if (appScreen == AppScreen.CALL_BROWSER) stopPrivateCallDiscovery()
        stopIdleCameraPreview()
        if (threadScreenDelegate.isInitialized()) threadScreen.onPause()
        super.onPause()
    }

    /** Phase 0 (T-M8): app lifecycle markers beside the `message_transfer` lines, for background reception. */
    private fun logMessageLifecycle(event: String) {
        if (BuildConfig.DEBUG && StoreForwardFeature.enabled) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK message_transfer app event=$event ${MessageAppState.describe(applicationContext)} atNs=${System.nanoTime()}",
            )
        }
    }

    override fun onStart() {
        super.onStart()
        logMessageLifecycle("start")
        activityStarted = true
        WifiDiscovery.listener = wifiListener
        WifiDiscovery.setActivityStarted(true)
        DirectQuarantineNotice.listener = { stuck ->
            if (stuck) runOnUiThread { Toast.makeText(this, R.string.wifi_direct_group_stuck, Toast.LENGTH_LONG).show() }
        }
        if (DirectQuarantineNotice.stuck) {
            Toast.makeText(this, R.string.wifi_direct_group_stuck, Toast.LENGTH_LONG).show()
        }
        if (!startupAborted) refreshCallReachability()
    }

    override fun onStop() {
        logMessageLifecycle("stop")
        DirectQuarantineNotice.listener = null
        debugRawObserver?.stop()
        if (cameraEncoder != null || privateCallCameraEncoder != null) {
            activeCameraViewfinderNeedsRefresh = true
        }
        activityStarted = false
        if (WifiDiscovery.listener === wifiListener) WifiDiscovery.listener = null
        WifiDiscovery.setActivityStarted(false)
        // Home reachability continues in the background only with the connectedDevice service.
        if (!startupAborted) refreshCallReachability()
        super.onStop()
    }

    /**
     * Before a user starts streaming, viewing or calling, recommends turning off 2.4 GHz Wi-Fi,
     * which time-shares the radio with Bluetooth: S24 -> M31 GATT 600 kbps ran at 1.9 FPS with
     * the M31 on 2.4 GHz Wi-Fi and 13 FPS with it off. The user may continue anyway. With any Wi-Fi
     * switch on, the advice is to leave the 2.4 GHz network and keep Wi-Fi on (WifiAdvicePolicy.variant).
     */
    private fun withWifiCheck(voice: Boolean = false, action: () -> Unit) {
        val advice = WifiCoexistence.advice()
        val answeredAtMs = appPreferences.getLong(WifiAdvicePolicy.PREF_ANSWERED_AT, 0L)
        if (advice == null || !WifiAdvicePolicy.shouldAsk(voice, System.currentTimeMillis(), answeredAtMs)) {
            action()
            return
        }
        fun answered() = appPreferences.edit().putLong(WifiAdvicePolicy.PREF_ANSWERED_AT, System.currentTimeMillis()).apply()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(WifiCoexistence.adviceTitleRes())
            .setMessage(advice)
            .setPositiveButton(R.string.share_nearby_open_wifi) { _, _ ->
                answered()
                pendingAfterWifiSettings = action
                val panel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(android.provider.Settings.Panel.ACTION_WIFI)
                } else {
                    Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                }
                runCatching { startActivity(panel) }
            }
            .setNegativeButton(R.string.wifi_advice_continue) { _, _ ->
                answered()
                action()
            }
            .showTracked()
    }

    /** Audit 2026-10-21: ending a live stream that people are watching asks first ("End live for 3 viewers?"). */
    private fun confirmEndLive(end: () -> Unit) {
        val viewers = lastConnectedCount
        if (!LiveEndConfirm.ask(viewers) || benchmarkExtrasPresent()) {
            end()
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(resources.getQuantityString(R.plurals.live_end_confirm_title, viewers, viewers))
            .setPositiveButton(R.string.live_end_confirm_action) { _, _ -> end() }
            .setNegativeButton(R.string.action_cancel, null)
            .showTracked()
    }

    /** Calls page: an error or outcome as a short snackbar (with an action when one helps), never as a status line. */
    private fun callsSnackbar(text: CharSequence, actionRes: Int = 0, action: (() -> Unit)? = null) {
        if (isFinishing || isDestroyed) return
        val bar = com.google.android.material.snackbar.Snackbar.make(
            findViewById(android.R.id.content), text, com.google.android.material.snackbar.Snackbar.LENGTH_LONG,
        )
        if (action != null && actionRes != 0) bar.setAction(actionRes) { action() }
        bar.show()
    }

    /** An actionable state for the Calls status line; null clears it. */
    private fun setCallsNotice(text: String?) {
        callsActionableNotice = text
        if (::callBrowserStatus.isInitialized) callBrowserStatus.text = CallsQuiet.line(text)
    }

    /** After a call: an outcome worth knowing (no answer, busy, declined, dropped) as a snackbar; "Call ended" says nothing. */
    private fun showCallOutcome(message: String) {
        val kind = CallEndText.kind(message)
        if (CallEndText.worthTelling(kind)) callsSnackbar(getString(kind.res))
    }

    /**
     * Calls > Nearby: a small indeterminate indicator while discovery runs (like Android's Bluetooth settings), and the
     * empty state only after [CallsQuiet.EMPTY_HINT_AFTER_MS] without anyone found.
     */
    private fun updateCallsSearching() {
        val nearby = appScreen == AppScreen.CALL_BROWSER && !callBrowserContacts && !callBrowserChats
        val indicator = CallsQuiet.indicatorVisible(
            nearby = nearby,
            discovering = privateCallDiscovery != null,
            searchingForMs = SystemClock.elapsedRealtime() - callsIndicatorStartedMs,
            settles = DebugTunables.callsIndicatorSettles,
        )
        findViewById<View>(R.id.callsSearchingIndicator)?.visibility = if (indicator) View.VISIBLE else View.GONE
        findViewById<View>(R.id.callEmptyView)?.alpha =
            if (CallsQuiet.emptyHintVisible(SystemClock.elapsedRealtime() - callsSearchStartedMs)) 1f else 0f
    }

    private fun setStatus(message: String) {
        Log.i("BluetoothVideo", message)
        runOnUiThread { statusText.text = message }
    }

    private fun registerThermalGuard() {
        thermalStatus = powerManager.currentThermalStatus
        powerManager.addThermalStatusListener(mainExecutor, thermalStatusListener)
        thermalListenerRegistered = true
        updateThermalBadge()
        if (ThermalGuardPolicy.shouldBlockStreaming(thermalStatus)) {
            setStatus(getString(R.string.status_too_hot))
        } else if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            setStatus(
                if (thermalReductionOn) {
                    "Phone is hot. Streams will start with reduced FPS and bitrate."
                } else {
                    "Phone is hot. Automatic cooling is off."
                },
            )
        }
        updateStartEnabled()
    }

    /** Automatic rate control adapts to measured delivery; CBR/VBR/CBR-FD keep the exact selection. */
    /**
     * Automatic calls fit the measured link instead of the fixed per-direction caps (GATT 180, CoC
     * 200, Classic 250, legacy GATT 150 kbps): uncapped GATT calls M31<->ASUS ran 300 and 500 kbps per
     * direction with 0 freezes and adapted 800 down to 450-700 kbps (lane-b-calls-529f36cc). Exact
     * CBR/VBR modes have no adaptation and keep the caps; a DEBUG run can still force preservation.
     */
    private fun callBitrateAdapts(): Boolean =
        encoderRateControl == EncoderRateControl.AUTOMATIC ||
            debugPreserveCallBitrate && debugPreserveCallBitrateApplies(outgoingTransport)

    private fun newLinkRateController(selected: VideoSettings, transport: BluetoothTransportOption): LinkRateController? {
        linkRateLimitBps = null
        layeringPolicy = if (DebugTunables.experimentalTemporalLayers) {
            LayeringPolicy(
                priorLinkKbps = LinkCapacity.measuredLinkKbps(transport),
                initialOfferedKbps = (selected.bitRate + StreamProtocol.AUDIO_BIT_RATE) / 1_000,
            )
        } else {
            null
        }
        return if (encoderRateControl == EncoderRateControl.AUTOMATIC) {
            Log.i("BluetoothVideo", "Rate adaptation armed (Automatic): requested ${selected.bitRate / 1_000} kbps")
            LinkRateController(
                selected.bitRate,
                memory = autoStreamMemory(
                    LinkCapacity.sustainedRateMemory(transport, "stream", buildKeyed = !startRateGuardOn()) {
                        // Measured Auto ladder: a peer this phone has not measured starts at the evidence start.
                        if (autoLadderActive() && autoStreamStartKbps > 0) autoStreamStartKbps * 1_000 else null
                    },
                ),
                frameRateLadder = autoStreamFrameRateLadder(selected),
                burstAwareDrain = DebugTunables.experimentalBurstDrain,
                deliveryProbing = DebugTunables.deliveryRateControl,
                rememberCuts = !DebugTunables.deliveryReports,
                smallClimbSteps = autoLadderActive() && autoStreamStartKbps > 0,
                deliveredEvidence = autoLadderActive() && autoStreamStartKbps > 0,
            )
        } else {
            null
        }
    }

    /** Learned call start and build-independent learned rates; off in the delivery-reports control arm too. */
    private fun startRateGuardOn(): Boolean = DebugTunables.startRateGuard && DebugTunables.deliveryReports

    private fun onPrivateCallLinkSample(sample: LinkRateSample) {
        val controller = privateCallRateController ?: return
        val restarted = privateCallCodec?.let { codec ->
            autoRestartCallAfterLinkChange(controller, codec, privateCallActiveAudioBitRate ?: StreamProtocol.AUDIO_BIT_RATE)
        }
        val controllerChanged = controller.onSample(PRIVATE_CALL_RATE_LINK, sample, System.nanoTime()) ?: restarted
        // F16 fix 1 (Experimental, off): leave airtime for the direction this phone receives (CallLinkShare).
        val share = if (DebugTunables.callLinkShare) {
            privateCallLinkShare ?: CallLinkShare(CALL_REVERSE_SHARE_FLOOR_BPS).also { privateCallLinkShare = it }
        } else {
            null
        }
        val requestedBps = privateCallSettings?.bitRate ?: controller.effectiveVideoBps
        val shareChanged = share?.onWindow(sample.reverseLateMaxMs, controller.effectiveVideoBps, requestedBps) == true
        if (shareChanged && share != null) {
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + share.line(requestedBps, controller.effectiveVideoBps))
        }
        val changed = (controllerChanged ?: if (shareChanged) controller.effectiveVideoBps else null)
            ?.let { target -> share?.apply(target) ?: target }
        privateCallClimbGuard?.onSample(sample.backlogAgeMs, sample.peerQueueMs, sample.childLate, SystemClock.elapsedRealtime())
        privateCallAudioFirstGate?.let { gate ->
            // Floor congestion: the controller sends the least video it can and the link is still behind.
            val congested = controller.atLowestRate &&
                (sample.backlogAgeMs >= LinkRateController.CONGESTED_BACKLOG_MS || sample.childLate)
            when (gate.onSample(congested, SystemClock.elapsedRealtime())) {
                AudioFirstGate.Action.PAUSE -> runOnUiThread { onPrivateCallAudioFirst(pause = true, sample) }
                AudioFirstGate.Action.RESUME -> runOnUiThread { onPrivateCallAudioFirst(pause = false, sample) }
                AudioFirstGate.Action.NONE -> Unit
            }
        }
        if (changed == null) {
            // The ladder's hold times need every sample, not only target changes.
            if (privateCallResolutionLadder != null) runOnUiThread { evaluatePrivateCallResolution(controller) }
            return
        }
        runOnUiThread {
            if (privateCallRateController !== controller) return@runOnUiThread
            val selected = privateCallSettings ?: return@runOnUiThread
            privateCallLinkRateLimitBps = changed.takeIf { it < selected.bitRate }
            val rawFps = controller.frameRateFor(changed).coerceAtMost(selected.fps)
            // Measured Auto ladder: a lower rate after 2 s of asking, a higher one only after 10 s, one level.
            val nowMs = SystemClock.elapsedRealtime()
            val linkFps = privateCallFpsHysteresis?.update(
                rawFps, nowMs,
                allowUp = privateCallClimbGuard?.climbAllowed(nowMs) ?: true,
                congested = privateCallClimbGuard?.severeCongestion(nowMs, AutoQualityLadder.FPS_SEVERE_CONGESTION_MS) ?: false,
            )?.coerceAtMost(selected.fps) ?: rawFps
            if (autoLadderActive() && linkFps.takeIf { it < selected.fps } != privateCallLinkFps) {
                autoLog(
                    "scope=call_fps | from=${privateCallLinkFps ?: selected.fps} | to=$linkFps | " +
                        "targetKbps=${changed / 1_000}",
                )
            }
            privateCallLinkFps = linkFps.takeIf { it < selected.fps }
            val thermalMaximum = ThermalGuardPolicy.maximumBitrate(
                selected.bitRate,
                thermalStatus,
                thermalReductionOn,
            )
            val thermalFps = ThermalGuardPolicy.maximumFps(selected.fps, thermalStatus, thermalReductionOn)
            privateCallCameraEncoder?.updateBitrate(minOf(changed, thermalMaximum))
            privateCallCameraEncoder?.updateFrameRate(minOf(linkFps, thermalFps))
            Log.i(
                "BluetoothVideo",
                "Call rate adaptation: sending ${changed / 1_000} of ${selected.bitRate / 1_000} kbps video at $linkFps FPS " +
                    "(backlog ${sample.backlogAgeMs} ms, peer late ${sample.childLate}, measured ${sample.measuredLinkKbps} kbps, " +
                    "delivered ${sample.deliveredKbps} kbps, peer queue ${sample.peerQueueMs} ms)",
            )
            evaluatePrivateCallResolution(controller)
        }
    }

    // ---- measured Auto ladder (AutoQualityFeature; experimental, default off) --------------------------

    /** Automatic video quality with the switch on; manual requests and raw benchmark inputs are never touched. */
    private fun autoLadderActive(): Boolean = AutoQualityFeature.enabled && !advancedSettings &&
        encoderRateControl == EncoderRateControl.AUTOMATIC && debugRawInputFile == null

    /**
     * A software encoder's measured p95 encode time per megapixel (ms), recorded by earlier sessions on
     * this phone ([EncoderBurstProfileStore]); null for a hardware encoder or before any session.
     */
    private fun autoEncodeMsPerMpx(codec: VideoCodec): Double? =
        if (codec.backend.isNative) {
            runCatching { EncoderBurstProfileStore(this).msPerMegapixel(codec.encoderName) }.getOrNull()
        } else {
            null
        }

    /**
     * The encoder class: a DEBUG launch's override, else from the measured encode time (a CPU-bound
     * software encoder such as the A15s libaom, 1.5-1.9 load at 720x480, is weak and offered only the
     * sizes it can encode in time), else standard.
     */
    private fun autoEncoderClass(codec: VideoCodec): AutoEncoderClass =
        AutoQualityFeature.encoderClassOverride
            ?: AutoQualityLadder.encoderClassFromLoad(autoEncodeMsPerMpx(codec))
            ?: AutoEncoderClass.STANDARD

    private fun autoLog(detail: String) {
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "auto_ladder: $detail")
    }

    // ---- GPU scale ladder (GpuScaleFeature; experimental, default off) ---------------------------

    /** Why this session does not use the GPU path (logged), or null when it does. */
    private fun gpuScaleOffReason(codec: VideoCodec, referenceFrames: Boolean): String? = GpuScaleMath.offReason(
        featureOn = GpuScaleFeature.enabled,
        autoLadderOn = autoLadderActive(),
        usesSurfaceInput = codec.usesSurfaceInput,
        rawInput = debugRawInputFile != null,
        referenceFrames = referenceFrames,
        gpuSupported = !GpuScaleFeature.enabled || GpuScaleSupport.supported,
    )

    /** The rung modes of a GPU-scaled session; empty when the camera offers no capture mode or the encoder rejects every rung. */
    private fun gpuScaleModes(
        codec: VideoCodec,
        bitRate: Int,
        lens: CameraLens,
        session: VideoResolution,
        fps: Int,
    ): List<DeviceVideoMode> =
        runCatching { deviceVideoCapabilities.gpuModesFor(codec, bitRate, lens, session, fps) }.getOrDefault(emptyList())

    private fun logGpuScale(detail: String) {
        Log.i("BluetoothVideo", "BENCHMARK gpu_scale: $detail")
    }

    /** The call's start plan for this transport's measured link (a direction gets half of it). */
    private fun autoCallPlan(codec: VideoCodec, transport: BluetoothTransportOption): AutoPlan =
        // Before any delivered evidence: the probe-only rule (one rung down, 15 FPS at most), which also
        // sets the header, so a call never starts above it.
        AutoQualityLadder.evidencePlan(
            LinkCapacity.measuredLinkKbps(transport)?.let { (it * AutoQualityLadder.CALL_DIRECTION_SHARE).toInt() },
            null, null, codec.protocolId, autoEncoderClass(codec),
            AutoQualityLadder.audioWireKbps(privateCallAudioBitRate(transport, privateCallStackFingerprint())),
            transport.maxVideoBitrateKbps, autoEncodeMsPerMpx(codec),
        )

    /**
     * The call's outgoing request under the Auto ladder, or null (use [settings]). The header is the
     * maximum geometry and frame rate; the request is the transport maximum, which the rate controller
     * climbs toward from the plan's start and never exceeds. The call resolution ladder then starts at
     * the plan's rung and moves up and down inside the header.
     */
    private fun autoCallRequestedSettings(codec: VideoCodec, transport: BluetoothTransportOption): VideoSettings? {
        if (!autoLadderActive()) return null
        val plan = autoCallPlan(codec, transport)
        val (resolution, fps) = AutoQualityLadder.callHeader(codec.protocolId, autoEncoderClass(codec), plan, autoEncodeMsPerMpx(codec))
        val requested = settings.copy(
            resolution = resolution,
            fps = fps,
            bitRate = transport.maxVideoBitrateKbps * 1_000,
        )
        // GPU scale ladder: fit against GPU rung sizes (header aspect), not the camera's mode list.
        val gpuModes = if (gpuScaleOffReason(codec, referenceFrames = false) == null) {
            gpuScaleModes(codec, requested.bitRate, cameraLens, requested.resolution, requested.fps)
        } else {
            emptyList()
        }
        val modes = gpuModes.ifEmpty {
            runCatching { deviceVideoCapabilities.modesFor(codec, requested.bitRate, cameraLens) }
                .getOrDefault(emptyList())
        }
        val fitted = if (modes.isEmpty()) {
            requested
        } else {
            runCatching { AutoQualityLadder.fitSettings(requested, modes, autoEncodeMsPerMpx(codec)) }.getOrDefault(requested)
        }
        autoLog(
            "scope=call | codec=${codec.protocolId} | class=${autoEncoderClass(codec)} | msPerMpx=${autoEncodeMsPerMpx(codec)} | ${plan.log()} | " +
                "manual=${settings.resolution.width}x${settings.resolution.height}@${settings.fps}/" +
                "${settings.bitRate / 1_000} | header=${fitted.resolution.width}x${fitted.resolution.height}@${fitted.fps} | " +
                "requestKbps=${fitted.bitRate / 1_000}",
        )
        return fitted
    }

    /**
     * Under the Auto ladder no call starts above the evidence start for an unknown peer: a remembered
     * rate is a target that may never have been filled (the first weak-pair A/B started M31 to A15s at a
     * remembered 571 kbps on a link that delivered 435, and the receiver's playout delay went to 600 ms).
     */
    private fun autoCallMemory(
        transport: BluetoothTransportOption,
        codec: VideoCodec,
        audioBps: Int,
        base: SustainedRateMemory,
    ): SustainedRateMemory {
        if (!autoLadderActive()) return base
        return object : SustainedRateMemory {
            override fun startBps(peer: String): Int? =
                base.startBps(peer)?.let { minOf(it, autoCallEvidenceStartBps(transport, codec, audioBps)) }

            override fun record(peer: String, bps: Int) = base.record(peer, bps)
        }
    }

    private fun autoCallEvidenceStartBps(transport: BluetoothTransportOption, codec: VideoCodec, audioBps: Int): Int =
        maxOf(
            AutoQualityLadder.evidencePlan(
                LinkCapacity.measuredLinkKbps(transport)?.let { (it * AutoQualityLadder.CALL_DIRECTION_SHARE).toInt() },
                null, null, codec.protocolId, autoEncoderClass(codec), AutoQualityLadder.audioWireKbps(audioBps),
                transport.maxVideoBitrateKbps, autoEncodeMsPerMpx(codec),
            ).videoKbps,
            AutoQualityLadder.MIN_VIDEO_KBPS,
        ) * 1_000

    private fun callUnknownPeerStartBps(
        transport: BluetoothTransportOption,
        codec: VideoCodec,
        audioBps: Int,
    ): Int {
        if (!autoLadderActive()) {
            return CallStartRate.unknownPeerStartBps(LinkCapacity.measuredLinkKbps(transport), audioBps)
        }
        // A peer with no remembered rate: the probe is the peer's burst arrival rate, not goodput, so
        // the start is one rung down (evidencePlan); a remembered peer starts at what it carried.
        val plan = AutoQualityLadder.evidencePlan(
            LinkCapacity.measuredLinkKbps(transport)?.let { (it * AutoQualityLadder.CALL_DIRECTION_SHARE).toInt() },
            null, null, codec.protocolId, autoEncoderClass(codec), AutoQualityLadder.audioWireKbps(audioBps),
            transport.maxVideoBitrateKbps, autoEncodeMsPerMpx(codec),
        )
        val start = maxOf(plan.videoKbps, AutoQualityLadder.MIN_VIDEO_KBPS) * 1_000
        autoLog("scope=call_start | ${plan.log()} | startBps=$start")
        return start
    }

    private fun autoCallFrameRateLadder(selected: VideoSettings, codec: VideoCodec): FrameRateLadder? {
        if (!autoLadderActive()) return null
        val encoderClass = autoEncoderClass(codec)
        val minimumFps = minOf(10, selected.fps)
        return FrameRateLadder(
            selected.fps,
            minimumFps,
            floorBps = AutoQualityLadder.MIN_VIDEO_KBPS * 1_000 * selected.fps / minimumFps,
            curve = { bps ->
                AutoQualityLadder.fpsForVideoKbps(
                    codec.protocolId, encoderClass, selected.resolution.width, selected.resolution.height,
                    selected.fps, bps / 1_000,
                )
            },
        )
    }

    /** The user's own picker values while a stream runs with the Auto plan; restored when the stream stops. */
    private var autoLadderSavedSettings: VideoSettings? = null

    /**
     * A stream's start rung from the measured link (the stream geometry is fixed for the session, so
     * there is no mid-stream step; bitrate adaptation is unchanged). Not applied to manual requests.
     */
    private fun applyAutoLadderToStreamSettings() {
        streamGpuScale = null
        // Q-SKIP1: the backlog skip at a PHY move is on by default only for a fixed-rate stream (not Auto, not audio-only live).
        PhyMoveSkip.fixedRateStream = !autoLadderActive() && !audioLive.mode
        if (!autoLadderActive() || audioLive.mode) return
        val codec = activeStreamCodec() ?: return
        val manual = autoLadderSavedSettings ?: settings
        // Arm time has no peer yet, so no delivered evidence: the probe-only rule sets the start and the
        // header (one rung down, 15 FPS at most); refineAutoStreamStart lowers it further when a peer's
        // history says so.
        val plan = AutoQualityLadder.evidencePlan(
            LinkCapacity.measuredLinkKbps(outgoingTransport), null, null, codec.protocolId, autoEncoderClass(codec),
            AutoQualityLadder.OPUS32_WIRE_KBPS, outgoingTransport.maxVideoBitrateKbps, autoEncodeMsPerMpx(codec),
        )
        val (headerResolution, headerFps) = AutoQualityLadder.callHeader(codec.protocolId, autoEncoderClass(codec), plan, autoEncodeMsPerMpx(codec))
        autoStreamStartKbps = maxOf(plan.videoKbps, AutoQualityLadder.MIN_VIDEO_KBPS)
        // The request is not a fixed ceiling: the rate controller starts at the plan's rate and climbs
        // toward it only on clean evidence. Its bound reuses the learned fan-out budget (proven rate,
        // fresh ceiling with headroom, or the transport prior) for one link, and never exceeds the
        // transport maximum.
        val fanOutBudgetKbps = LinkCapacity.budgetKbps(
            outgoingTransport, LinkLoadKind.FAN_OUT,
            MeshBandwidthEstimator.perLinkKbps(autoStreamStartKbps, headerFps),
        )
        val requestKbps = minOf(
            outgoingTransport.maxVideoBitrateKbps,
            maxOf(autoStreamStartKbps, MeshBandwidthEstimator.videoKbpsForLink(fanOutBudgetKbps, headerFps)),
        )
        val requested = manual.copy(
            resolution = headerResolution,
            fps = headerFps,
            bitRate = requestKbps * 1_000,
        )
        // GPU scale ladder: the rungs are GPU sizes in the header's aspect, not the camera's mode list.
        val gpuReason = gpuScaleOffReason(codec, referenceFrames = benchmarkFrames > 0)
        val gpuModes = if (gpuReason == null) {
            gpuScaleModes(codec, requested.bitRate, cameraLens, requested.resolution, requested.fps)
        } else {
            emptyList()
        }
        val usesGpu = gpuModes.isNotEmpty()
        val fitted = when {
            usesGpu -> runCatching { AutoQualityLadder.fitSettings(requested, gpuModes, autoEncodeMsPerMpx(codec)) }.getOrDefault(requested)
            deviceModes.isEmpty() -> requested
            else -> runCatching { AutoQualityLadder.fitSettings(requested, deviceModes, autoEncodeMsPerMpx(codec)) }.getOrDefault(requested)
        }
        streamGpuScale = if (usesGpu) GpuScaleRequest(fitted.resolution, fitted.fps) else null
        if (GpuScaleFeature.enabled) {
            logGpuScale(
                "scope=stream | active=$usesGpu | reason=${gpuReason ?: if (usesGpu) "ok" else "no_gpu_modes"} | " +
                    "requested=${requested.resolution.width}x${requested.resolution.height}@${requested.fps} | " +
                    "session=${fitted.resolution.width}x${fitted.resolution.height}@${fitted.fps}",
            )
        }
        autoLadderSavedSettings = manual
        settings = fitted
        autoLog(
            "scope=stream | codec=${codec.protocolId} | class=${autoEncoderClass(codec)} | msPerMpx=${autoEncodeMsPerMpx(codec)} | ${plan.log()} | " +
                "startKbps=$autoStreamStartKbps" +
                (if (plan.audioOnly) " | audio_only_recommended=true" else "") +
                " | manual=${manual.resolution.width}x${manual.resolution.height}@${manual.fps}/${manual.bitRate / 1_000}" +
                " | effective=${fitted.resolution.width}x${fitted.resolution.height}@${fitted.fps}/${fitted.bitRate / 1_000}",
        )
    }

    /**
     * Fan-out admission plans the children a source can serve from the stream's rate. Under Auto it
     * plans at the start rate (the request is only the climb bound), so a source admits as many
     * children as the fixed-rate plan would; the weakest child then limits the climb, and the existing
     * overload rollback, withdrawal and relay budgets still apply.
     */
    private fun streamAdmissionSettings(selected: VideoSettings): VideoSettings =
        if (autoLadderSavedSettings != null && autoStreamStartKbps > 0) {
            selected.copy(bitRate = minOf(selected.bitRate, autoStreamStartKbps * 1_000))
        } else {
            selected
        }

    private fun restoreManualStreamSettings() {
        autoLadderSavedSettings?.let { settings = it }
        autoLadderSavedSettings = null
        streamResolutionLadder = null
        streamGpuScale = null
        autoStreamStartKbps = 0
        streamLinkFps = null
        streamFpsHysteresis = null
        streamClimbGuard = null
    }

    /** Measured Auto ladder for a stream: the same rungs and holds as a call, moved along while it runs. */
    private var streamResolutionLadder: CallResolutionLadder? = null

    /** GPU scale ladder (GpuScaleFeature): set while this stream's camera runs through the GPU path. */
    private var streamGpuScale: GpuScaleRequest? = null
    private var autoStreamStartKbps = 0
    private var streamAutoCodec: VideoCodec? = null
    private var streamFpsHysteresis: FpsHysteresis? = null
    private var streamClimbGuard: ClimbGuard? = null
    private var lastFrameBudgetViewers = 1
    private var streamCameraRestarting = false
    @Volatile private var streamLinkFps: Int? = null
    private val streamCameraExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "StreamCameraLifecycle")
    }

    private fun autoStreamFrameRateLadder(selected: VideoSettings): FrameRateLadder? {
        val codec = activeStreamCodec() ?: return null
        return if (autoStreamStartKbps > 0 && autoLadderActive()) autoCallFrameRateLadder(selected, codec) else null
    }

    /**
     * Starts the stream's resolution ladder at the plan's rung (the largest rung the start rate supports)
     * and applies the start rate before the encoder is configured. Null while off or for audio-only live.
     */
    private fun newStreamResolutionLadder(selected: VideoSettings, codec: VideoCodec): CallResolutionLadder? {
        streamResolutionLadder = null
        streamLinkFps = null
        streamFpsHysteresis = null
        streamClimbGuard = null
        if (!autoLadderActive() || autoStreamStartKbps <= 0 || audioLive.mode) return null
        streamFpsHysteresis = FpsHysteresis()
        streamClimbGuard = ClimbGuard()
        streamAutoCodec = codec
        val startBps = autoStreamStartKbps * 1_000
        linkRateLimitBps = startBps.takeIf { it < selected.bitRate }
        val size = "${selected.resolution.width}x${selected.resolution.height}"
        val ladder = runCatching {
            val lenses = availableCameraLenses.ifEmpty { listOf(cameraLens) }
            val common = lenses.map { lens ->
                (if (streamGpuScale != null) {
                    gpuScaleModes(codec, selected.bitRate, lens, selected.resolution, selected.fps)
                } else {
                    deviceVideoCapabilities.modesFor(codec, selected.bitRate, lens)
                })
                    .filter { selected.fps in it.fpsOptions }
                    .map { it.resolution.width to it.resolution.height }
                    .toSet()
            }.reduce { all, lens -> all intersect lens }
            CallResolutionLadder(
                selected.resolution,
                common.map { (width, height) -> VideoResolution(width, height, "${height}p") },
                codec.protocolId,
                limits = AutoQualityLadder.callLimits(codec.protocolId, autoEncoderClass(codec), selected.fps),
                downHoldMs = AutoQualityLadder.STREAM_DOWN_HOLD_MS,
                upHoldMs = AutoQualityLadder.STREAM_UP_HOLD_MS,
                upMinIntervalMs = AutoQualityLadder.STREAM_UP_MIN_INTERVAL_MS,
                downMinIntervalMs = AutoQualityLadder.STREAM_DOWN_MIN_INTERVAL_MS,
                startLimit = AutoQualityLadder.callStartLimit(codec.protocolId, autoEncoderClass(codec), selected.fps),
            )
        }.getOrNull()
        if (ladder == null || !ladder.active) {
            autoLog("scope=stream_resolution | requested=$size | effective=$size | reason=no_smaller_rung")
            return null
        }
        streamClimbGuard?.let { guard ->
            ladder.climbGate = { now -> guard.climbAllowed(now, AutoQualityLadder.STREAM_UP_CLEAN_MS) }
            ladder.downGate = { now -> guard.sustainedCongestion(now, AutoQualityLadder.STREAM_CONGESTION_MS) }
        }
        val decision = ladder.start(startBps, SystemClock.elapsedRealtime())
        streamResolutionLadder = ladder
        autoLog(
            "scope=stream_resolution | requested=$size | " +
                "effective=${decision.resolution.width}x${decision.resolution.height} | reason=${decision.reason} | " +
                "fps=${selected.fps} | rungs=${ladder.rungs.joinToString(",") { "${it.width}x${it.height}" }}",
        )
        return ladder
    }

    /** The stream encoder's first configuration: the ladder's rung at the plan's start rate (the header is the maximum). */
    private fun streamEncoderSettings(selected: VideoSettings): VideoSettings {
        val ladder = streamResolutionLadder ?: return selected
        return selected.copy(
            resolution = ladder.current,
            bitRate = minOf(selected.bitRate, autoStreamStartKbps * 1_000),
        )
    }

    /**
     * Once the first viewer is authenticated (before the encoder exists), restarts the stream's ladder at
     * the evidence start: the lowest of the phone's probe and the 1M ceiling of a remembered bad-2M peer,
     * capped at what each peer's link last carried cleanly, one rung down when only the probe is known.
     * The rate controller starts from the same value (its unknown-peer start) and climbs from delivered
     * windows. The weakest peer decides.
     */
    private fun refineAutoStreamStart(peerLabels: List<String>) {
        if (!autoLadderActive() || autoStreamStartKbps <= 0 || audioLive.mode) return
        val codec = streamAutoCodec ?: return
        val transport = outgoingTransport
        val phyCeiling = if (transport.isBle && peerLabels.any { AdaptivePhyMemory.isBad(transport, it) }) {
            AutoQualityLadder.phyCeilingKbps(AdaptivePhyPolicy.DEFAULT_INTERVAL_MS)
        } else {
            null
        }
        val sustained = peerLabels.takeIf { it.isNotEmpty() }
            ?.map { LinkCapacity.sustainedVideoBps(transport, "stream", it, buildKeyed = !startRateGuardOn()) }
            ?.takeIf { rates -> rates.all { it != null } }
            ?.minOf { it!! }?.div(1_000)
        val plan = AutoQualityLadder.evidencePlan(
            LinkCapacity.measuredLinkKbps(transport), phyCeiling, sustained, codec.protocolId, autoEncoderClass(codec),
            AutoQualityLadder.OPUS32_WIRE_KBPS, transport.maxVideoBitrateKbps, autoEncodeMsPerMpx(codec),
        )
        autoStreamStartKbps = maxOf(plan.videoKbps, AutoQualityLadder.MIN_VIDEO_KBPS)
        val startBps = autoStreamStartKbps * 1_000
        val selected = activeStreamSettings
        if (selected != null) linkRateLimitBps = startBps.takeIf { it < selected.bitRate }
        val decision = streamResolutionLadder?.start(startBps, SystemClock.elapsedRealtime())
        autoLog(
            "scope=stream_evidence | peers=${peerLabels.size} | probeKbps=${LinkCapacity.measuredLinkKbps(transport)} | " +
                "phyCeilingKbps=$phyCeiling | sustainedKbps=$sustained | ${plan.log()} | startKbps=$autoStreamStartKbps" +
                (decision?.let { " | effective=${it.resolution.width}x${it.resolution.height}" } ?: ""),
        )
    }

    /**
     * Under the Auto ladder no peer starts above the evidence start ([refineAutoStreamStart]): a
     * remembered rate is a target that may never have been filled (first phone A/B: 300 kbps remembered,
     * about 170 carried), so it can lower the start but not raise it past the evidence.
     */
    private fun autoStreamMemory(base: SustainedRateMemory): SustainedRateMemory {
        if (!autoLadderActive() || autoStreamStartKbps <= 0) return base
        return object : SustainedRateMemory {
            override fun startBps(peer: String): Int? =
                base.startBps(peer)?.let { minOf(it, autoStreamStartKbps * 1_000) }

            override fun record(peer: String, bps: Int) = base.record(peer, bps)
        }
    }

    private var streamLinkEpoch = LinkChangeEvents.epoch
    private var privateCallLinkEpoch = LinkChangeEvents.epoch

    /**
     * The link's PHY moved (LinkChangeEvents): what the rate controller learned about it is stale, so under
     * the Auto ladder it restarts its climb from 60% of the evidence start instead of staying at the floor
     * the old link forced.
     */
    private fun autoRestartStreamAfterLinkChange(controller: LinkRateController): Int? {
        val epoch = LinkChangeEvents.epoch
        if (epoch == streamLinkEpoch) return null
        streamLinkEpoch = epoch
        if (!autoLadderActive() || autoStreamStartKbps <= 0 || streamResolutionLadder == null) return null
        val restartKbps = AutoQualityLadder.linkChangeRestartKbps(autoStreamStartKbps)
        val changed = controller.restartAfterLinkChange(restartKbps * 1_000, System.nanoTime())
        autoLog("scope=link_change_stream | epoch=$epoch | restartKbps=$restartKbps | targetKbps=${changed?.div(1_000)}")
        return changed
    }

    private fun autoRestartCallAfterLinkChange(controller: LinkRateController, codec: VideoCodec, audioBps: Int): Int? {
        val epoch = LinkChangeEvents.epoch
        if (epoch == privateCallLinkEpoch) return null
        privateCallLinkEpoch = epoch
        if (!autoLadderActive()) return null
        val restartKbps = AutoQualityLadder.linkChangeRestartKbps(
            autoCallEvidenceStartBps(callEndpointTransport ?: outgoingTransport, codec, audioBps) / 1_000,
        )
        val changed = controller.restartAfterLinkChange(restartKbps * 1_000, System.nanoTime())
        autoLog("scope=link_change_call | epoch=$epoch | restartKbps=$restartKbps | targetKbps=${changed?.div(1_000)}")
        return changed
    }

    private fun streamVideoTargetBps(selected: VideoSettings): Int = minOf(
        linkRateLimitBps ?: selected.bitRate,
        ThermalGuardPolicy.maximumBitrate(selected.bitRate, thermalStatus, thermalReductionOn),
    )

    /**
     * One ladder look at the current video target (the weakest child's rate the controller follows,
     * so a source never outruns its tightest direct child; relays handle their own downstream hop).
     * Safe points only: an unpaused stream with a running encoder and no restart in progress. The
     * ladder's holds (down after the target stays under the rung's limit, up one rung after 1.25x
     * headroom for 10 s, 15 s between changes) keep it from oscillating.
     */
    private fun evaluateStreamResolution() {
        val ladder = streamResolutionLadder ?: return
        val selected = activeStreamSettings ?: return
        if (streamPaused || cameraEncoder == null || streamCameraRestarting) return
        val now = SystemClock.elapsedRealtime()
        val decision = ladder.onTarget(streamVideoTargetBps(selected), now)
            ?: ladder.onEncoderLoad(cameraEncoder?.liveEncodeLoad(minOf(streamLinkFps ?: selected.fps, selected.fps)), now)
            ?: return
        autoLog(
            "scope=stream_resolution | requested=${ladder.requested.width}x${ladder.requested.height} | " +
                "effective=${decision.resolution.width}x${decision.resolution.height} | reason=${decision.reason} | " +
                "fps=${streamLinkFps ?: selected.fps}",
        )
        restartStreamCamera(selected, decision.resolution)
    }

    /** Restarts the stream camera encoder at a new rung; its first frame is a keyframe carrying the new size in-band. */
    private fun restartStreamCamera(selected: VideoSettings, resolution: VideoResolution) {
        // A raw fixture has no camera to restart; the Auto ladder is off for it, and this stays a hard stop.
        if (!RawInputGuard.cameraMayStart(debugRawInputFile != null)) return
        val codec = streamAutoCodec ?: return
        val send: (Int, Long, ByteArray) -> Unit = broadcaster?.let { b -> b::send } ?: sender?.let { s -> s::send } ?: return
        val old = cameraEncoder ?: return
        cameraEncoder = null
        streamCameraRestarting = true
        val encoderSettings = selected.copy(
            resolution = resolution,
            bitRate = minOf(selected.bitRate, linkRateLimitBps ?: selected.bitRate),
        )
        // The new encoder's first keyframe is marked as a discontinuity when the picture size changes, so
        // every viewer and relay queue drops what came before it; viewers re-create their decoder at the
        // keyframe (DecoderFormatTracker). The coded size itself travels in the keyframe's bitstream.
        val oldResolution = old.outputResolution
        val restartMarker = RestartDiscontinuityMarker(
            oldResolution.width != resolution.width || oldResolution.height != resolution.height,
        )
        lateinit var encoder: CameraVideoEncoder
        encoder = CameraVideoEncoder(
            this,
            cameraViewfinder,
            cameraLens,
            codec,
            encoderSettings,
            benchmarkFrames,
            debugMediaTraceSession,
            debugImpairedSend(restartMarker.wrap(send)),
            { message -> if (cameraEncoder === encoder) setStatus(message) },
            null,
            encoderRateControl,
            videoScaleMode,
            {},
            onFailure = { error ->
                runOnUiThread {
                    if (cameraEncoder === encoder) {
                        stopStreaming("Camera stopped after a resolution change: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
            },
            gpuScale = streamGpuScale,
            onSoftwareShortfall = softwareShortfallListener("stream"),
        )
        cameraEncoder = encoder
        streamCameraExecutor.execute {
            // GPU scale ladder: the camera stays open across the rung change; only the encoder is rebuilt.
            runCatching {
                if (streamGpuScale != null) old.retainCaptureForRestart()
                old.close()
            }
            runCatching { encoder.start() }.onSuccess {
                runOnUiThread {
                    streamCameraRestarting = false
                    if (cameraEncoder === encoder) {
                        layeringPolicy?.let { policy -> encoder.setDroppableFrames(policy.layered) }
                        updateEncoderFrameBudget(lastFrameBudgetViewers)
                        applyThermalAdaptation(thermalStatus)
                    }
                }
            }.onFailure { error ->
                encoder.close()
                runOnUiThread {
                    streamCameraRestarting = false
                    if (cameraEncoder === encoder) {
                        stopStreaming("Camera could not restart: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    /** Warning for a starved manual combination (switch on, Manual video settings only); never changes the request. */
    private fun manualStarvationWarning(): String? {
        if (!AutoQualityFeature.enabled || !advancedSettings) return null
        val codec = codecs.getOrNull(codecSpinner.selectedItemPosition) ?: return null
        val check = AutoQualityLadder.starvation(
            codec.protocolId, settings.resolution.width, settings.resolution.height, settings.fps,
            settings.bitRate / 1_000,
        )
        return AutoQualityLadder.starvationWarning(check, settings.resolution.width, settings.resolution.height, settings.fps)
    }

    /** The video target the call encoder now has: the link limit and the thermal limit, the lower. */
    private fun privateCallVideoTargetBps(selected: VideoSettings): Int = minOf(
        privateCallLinkRateLimitBps ?: selected.bitRate,
        ThermalGuardPolicy.maximumBitrate(selected.bitRate, thermalStatus, thermalReductionOn),
    )

    /**
     * Call resolution ladder (CallResolutionLadderFeature, default on for user calls). Built from
     * the modes every lens offers at the call's FPS, below the call's start geometry (already
     * limited by DeviceFamilyPolicy call caps and the encoder fit). Exact rate control, raw inputs
     * and benchmark launches keep the requested geometry and log requested == effective.
     */
    private fun newPrivateCallResolutionLadder(settings: VideoSettings, codec: VideoCodec): CallResolutionLadder? {
        val size = "${settings.resolution.width}x${settings.resolution.height}"
        // GPU scale ladder: one camera capture, rungs drawn by the GPU; set whether or not a ladder results.
        privateCallGpuScale = null
        val gpuReason = gpuScaleOffReason(codec, referenceFrames = false)
        if (gpuReason == null &&
            gpuScaleModes(codec, settings.bitRate, cameraLens, settings.resolution, settings.fps).isNotEmpty()
        ) {
            privateCallGpuScale = GpuScaleRequest(settings.resolution, settings.fps)
        }
        if (GpuScaleFeature.enabled) {
            logGpuScale(
                "scope=call | active=${privateCallGpuScale != null} | " +
                    "reason=${gpuReason ?: if (privateCallGpuScale != null) "ok" else "no_gpu_modes"} | session=$size@${settings.fps}",
            )
        }
        val offReason = when {
            !CallResolutionLadderFeature.enabled -> if (DebugTunables.benchmarkLaunch) "off_benchmark" else "off"
            privateCallRateController == null -> "off_exact_rate_control"
            debugRawInputFile != null -> "off_raw_input"
            else -> null
        }
        if (offReason != null) {
            logCallResolution("requested=$size effective=$size reason=$offReason")
            return null
        }
        val ladder = buildPrivateCallResolutionLadder(settings, codec, privateCallGpuScale)
        if (ladder == null || !ladder.active) {
            logCallResolution("requested=$size effective=$size reason=no_smaller_rung")
            return null
        }
        // Measured Auto ladder: no step up while queues are over their bounds (ClimbGuard).
        privateCallClimbGuard?.let { guard -> ladder.climbGate = { now -> guard.climbAllowed(now) } }
        val decision = ladder.start(privateCallVideoTargetBps(settings), SystemClock.elapsedRealtime())
        logCallResolution(
            "requested=$size effective=${decision.resolution.width}x${decision.resolution.height} " +
                "reason=${decision.reason} | codec=${codec.protocolId} | fps=${settings.fps} | " +
                "rungs=${ladder.rungs.joinToString(",") { "${it.width}x${it.height}" }}",
        )
        return ladder
    }

    /** The call resolution ladder's rungs for [settings] (no logs or state; null when it cannot be built). */
    private fun buildPrivateCallResolutionLadder(
        settings: VideoSettings,
        codec: VideoCodec,
        gpuScale: GpuScaleRequest?,
        logFailure: Boolean = true,
    ): CallResolutionLadder? = runCatching {
        val lenses = availableCameraLenses.ifEmpty { listOf(cameraLens) }
        val common = lenses.map { lens ->
            (if (gpuScale != null) {
                gpuScaleModes(codec, settings.bitRate, lens, settings.resolution, settings.fps)
            } else {
                deviceVideoCapabilities.modesFor(codec, settings.bitRate, lens)
            })
                .filter { settings.fps in it.fpsOptions }
                .map { it.resolution.width to it.resolution.height }
                .toSet()
        }.reduce { all, lens -> all intersect lens }
        CallResolutionLadder(
            settings.resolution,
            common.map { (width, height) -> VideoResolution(width, height, "${height}p") },
            codec.protocolId,
            limits = if (autoLadderActive()) {
                AutoQualityLadder.callLimits(codec.protocolId, autoEncoderClass(codec), settings.fps)
            } else {
                null
            },
            downHoldMs = if (autoLadderActive()) AutoQualityLadder.AUTO_DOWN_HOLD_MS else CallResolutionLadder.DOWN_HOLD_MS,
            startLimit = if (autoLadderActive()) {
                AutoQualityLadder.callStartLimit(codec.protocolId, autoEncoderClass(codec), settings.fps)
            } else {
                null
            },
        )
    }.getOrElse { error ->
        if (logFailure) Log.w("BluetoothVideo", "Call resolution ladder unavailable: ${error.message}")
        null
    }

    private fun logCallResolution(detail: String) {
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_resolution $detail")
    }

    /** Applies a ladder change by restarting the call camera encoder; its first frame is a keyframe. */
    private fun evaluatePrivateCallResolution(controller: LinkRateController) {
        if (privateCallRateController !== controller) return
        val ladder = privateCallResolutionLadder ?: return
        val selected = privateCallSettings ?: return
        val now = SystemClock.elapsedRealtime()
        // Measured Auto ladder: a software encoder over its frame budget steps the picture down instead of
        // letting the frame rate flip (the encoder load is null for hardware encoders and when off).
        val decision = ladder.onTarget(privateCallVideoTargetBps(selected), now)
            ?: run {
                if (!autoLadderActive()) return
                ladder.onEncoderLoad(
                    privateCallCameraEncoder?.liveEncodeLoad(minOf(privateCallLinkFps ?: selected.fps, selected.fps)), now,
                )
            }
            ?: return
        logCallResolution(
            "requested=${ladder.requested.width}x${ladder.requested.height} " +
                "effective=${decision.resolution.width}x${decision.resolution.height} reason=${decision.reason}",
        )
        // Camera off: the next camera start uses the new rung.
        if (!privateCallCameraEnabled || privateCallCameraEncoder == null) return
        val codec = privateCallCodec ?: return
        // GPU scale ladder: the camera stays open across the rung change; only the encoder is rebuilt.
        if (privateCallGpuScale != null) privateCallCameraEncoder?.retainCaptureForRestart()
        stopPrivateCallCamera()
        startPrivateCallCamera(codec, selected)
    }

    /** Experimental temporal layering only (null otherwise): droppable frames while a link is tight. */
    @Volatile private var layeringPolicy: LayeringPolicy? = null

    private fun onLinkRateSample(controller: LinkRateController?, link: Any, sample: LinkRateSample) {
        layeringPolicy?.onSample(sample)?.let { layered ->
            cameraEncoder?.setDroppableFrames(layered)
            Log.i(
                "BluetoothVideo",
                if (layered) {
                    "Droppable frames on (experimental): a link is tight or not yet measured"
                } else {
                    "Droppable frames off (experimental): every link has had room for 20 s; all frames are references"
                },
            )
        }
        if (BuildConfig.DEBUG && controller != null &&
            (sample.childLate || sample.backlogAgeMs >= LinkRateController.CONGESTED_BACKLOG_MS ||
                sample.peerQueueMs >= LinkRateController.QUEUE_PRESENT_MS)
        ) {
            Log.i(
                "BluetoothVideo",
                "Rate evidence: offered ${sample.offeredBytes * 8 / 1_000} kbit, sent ${sample.sentBytes * 8 / 1_000} kbit, " +
                    "backlog ${sample.backlogAgeMs} ms, child late ${sample.childLate}, " +
                    "measured ${sample.measuredLinkKbps} kbps, large-frame grace ${sample.largeFrameGrace}, " +
                    "large frame ${sample.largeFrameAgeMs} ms ago, burst-attributed " +
                    "${DebugTunables.experimentalBurstDrain && sample.childLate && LinkRateController.burstExplainsLateness(sample)}, " +
                    "delivered ${sample.deliveredKbps} kbps, peer queue ${sample.peerQueueMs} ms",
            )
        }
        val restarted = if (controller != null) autoRestartStreamAfterLinkChange(controller) else null
        val changed = controller?.onSample(link, sample, System.nanoTime()) ?: restarted
        if (controller != null) {
            streamClimbGuard?.onSample(sample.backlogAgeMs, sample.peerQueueMs, sample.childLate, SystemClock.elapsedRealtime())
        }
        if (changed == null) {
            // The ladder's hold times need every sample, not only target changes.
            if (controller != null && streamResolutionLadder != null) runOnUiThread { evaluateStreamResolution() }
            return
        }
        runOnUiThread {
            val selected = activeStreamSettings ?: return@runOnUiThread
            linkRateLimitBps = changed.takeIf { it < selected.bitRate }
            if (streamResolutionLadder != null && controller != null) {
                val rawFps = controller.frameRateFor(changed).coerceAtMost(selected.fps)
                val nowMs = SystemClock.elapsedRealtime()
                val linkFps = streamFpsHysteresis?.update(
                    rawFps, nowMs,
                    allowUp = streamClimbGuard?.climbAllowed(nowMs) ?: true,
                    congested = streamClimbGuard?.severeCongestion(nowMs, AutoQualityLadder.FPS_SEVERE_CONGESTION_MS) ?: false,
                )?.coerceAtMost(selected.fps) ?: rawFps
                val newFps = linkFps.takeIf { it < selected.fps }
                if (newFps != streamLinkFps) {
                    autoLog(
                        "scope=stream_fps | from=${streamLinkFps ?: selected.fps} | to=${newFps ?: selected.fps} | " +
                            "targetKbps=${changed / 1_000}",
                    )
                }
                streamLinkFps = newFps
            }
            applyThermalAdaptation(thermalStatus)
            if (streamResolutionLadder != null) evaluateStreamResolution()
            val message = if (linkRateLimitBps != null) {
                "Link limited: sending ${changed / 1_000} of ${selected.bitRate / 1_000} kbps video"
            } else {
                "Link recovered: sending the selected ${selected.bitRate / 1_000} kbps video"
            }
            Log.i("BluetoothVideo", "Rate adaptation: $message")
            setStatus(message)
        }
    }

    /** Emits the `BENCHMARK thermal:` evidence line when its content changes (not on every rate sample). */
    private fun logThermalDecision(reported: Int, effective: Int, speedFloorValue: Int, source: String) {
        val line = MemoryThermalPolicy.benchmarkLine(
            reported, thermalForecastHeadroom, effective, thermalReductionOn, speedFloorValue, source,
        )
        val content = line.substringBefore(" | source=")
        if (content == lastThermalBenchmarkLine) return
        lastThermalBenchmarkLine = content
        Log.i("BluetoothVideo", line)
    }

    private fun applyThermalAdaptation(
        reportedStatus: Int,
        source: String = "adapt",
    ): Int? {
        val selected = activeStreamSettings ?: return null
        val status = MemoryThermalPolicy.effectiveStatus(
            thermalReportedWithFallback(reportedStatus),
            thermalForecastHeadroom,
            thermalForecastOn,
        )
        val speedFloorValue = MemoryThermalPolicy.nativeSpeedFloor(
            status,
            thermalReductionOn,
            MemoryThermalPolicy.speedFloor,
        )
        cameraEncoder?.updateThermalSpeedFloor(speedFloorValue)
        privateCallCameraEncoder?.updateThermalSpeedFloor(speedFloorValue)
        logThermalDecision(reportedStatus, status, speedFloorValue, source)
        val maximumFps = ThermalGuardPolicy.maximumFps(
            selected.fps,
            status,
            thermalReductionOn,
        )
        val maximumBitrate = ThermalGuardPolicy.maximumBitrate(
            selected.bitRate,
            status,
            thermalReductionOn,
        )
        // The stricter of the thermal and measured-link limits applies to the stream encoder.
        cameraEncoder?.updateBitrate(linkRateLimitBps?.let { minOf(it, maximumBitrate) } ?: maximumBitrate)
        cameraEncoder?.updateFrameRate(streamLinkFps?.let { minOf(it, maximumFps) } ?: maximumFps)
        privateCallCameraEncoder?.updateBitrate(privateCallLinkRateLimitBps?.let { minOf(it, maximumBitrate) } ?: maximumBitrate)
        privateCallCameraEncoder?.updateFrameRate(privateCallLinkFps?.let { minOf(it, maximumFps) } ?: maximumFps)
        thermalAdaptationApplied = maximumFps < selected.fps || maximumBitrate < selected.bitRate
        return ThermalGuardPolicy.workloadPercent(status, thermalReductionOn)
    }

    /**
     * Experimental "Share app nearby" gate: a stream, call, relay or reconnect exists. The idle
     * always-reachable call endpoint (a [privateCall] with no phase) only advertises and listens, so
     * it does not block sharing; an incoming call gives it a phase and stops the share.
     */
    private fun liveSessionBlocksAppShare(): Boolean =
        audioLive.mode || audioLive.listening || streamArmed || sender != null || broadcaster != null || receiver != null || relay != null ||
            privateCallRelay != null || cameraEncoder != null || privateCallCameraEncoder != null ||
            (privateCall != null && privateCallPhase != null) || reconnectTarget != null

    private fun thermalVideoActive(): Boolean = streamArmed || sender != null || broadcaster != null ||
        receiver != null || relay != null || privateCallRelay != null || cameraEncoder != null ||
        privateCall != null || privateCallCameraEncoder != null

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val action = MemoryThermalPolicy.trimAction(level)
        if (action == MemoryThermalPolicy.TrimAction.NONE || !MemoryThermalPolicy.trimReleaseActive()) return
        var released = 0
        // Expired suppressions are rebuilt for free; they are only dropped earlier than the next refresh.
        val nowMs = SystemClock.elapsedRealtime()
        val before = suppressedStreamRoutes.size
        suppressedStreamRoutes.entries.removeAll { it.value <= nowMs }
        capacitySuppressedRouteGenerations.keys.retainAll(suppressedStreamRoutes.keys)
        released += before - suppressedStreamRoutes.size
        // A pending reconnect still needs its candidates and failure counters: it counts as active.
        val active = thermalVideoActive() || reconnectTarget != null
        if (action == MemoryThermalPolicy.TrimAction.RELEASE_IDLE_DISCOVERY && !active) {
            released += viewerPeersLastSeen.size + nearbyStreamsLastSeen.size + privateCallPeersLastSeen.size
            viewerPeersLastSeen.clear()
            nearbyStreamsLastSeen.clear()
            privateCallPeersLastSeen.clear()
            callSightings.clear()
        }
        Log.i("BluetoothVideo", MemoryThermalPolicy.trimLine(level, action, active, released))
    }

    /** Sessions that stop at critical heat besides a call (the idle call endpoint and a call itself go on). */
    private fun thermalStreamActive(): Boolean = streamArmed || sender != null || broadcaster != null ||
        receiver != null || relay != null || cameraEncoder != null

    /** Critical heat in a call (DECISIONS 2026-11-06): the camera turns off through the user's camera-off path. */
    private fun stopPrivateCallVideoForHeat() {
        if (privateCall == null || !privateCallCameraEnabled) return
        togglePrivateCallCamera()
        showCallHeatNotice("camera_off")
    }

    /** The existing heat text, shown when critical heat keeps a call's video off. */
    private fun showCallHeatNotice(action: String) {
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                "call_heat: action=$action | status=${MemoryThermalPolicy.statusName(thermalStatus)} | video=off",
        )
        val text = getString(R.string.status_too_hot)
        setCallStatus(text)
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    /** A DEBUG dial or answer keeps its requested type: video at critical heat is refused, never turned into voice. */
    private fun debugCallHeatAllows(callType: CallType): Boolean {
        if (callType == CallType.VOICE || ThermalGuardPolicy.callVideoAllowed(thermalStatus)) return true
        setStatus(getString(R.string.status_too_hot))
        return false
    }

    private fun ensureThermalReady(): Boolean {
        if (!ThermalGuardPolicy.shouldBlockStreaming(thermalStatus)) return true
        setStatus(getString(R.string.status_too_hot))
        return false
    }

    private fun updateThermalBadge() {
        if (!::thermalStatusBadge.isInitialized || !::batteryTemperatureBadge.isInitialized) return
        val (label, color) = when {
            thermalStatus >= PowerManager.THERMAL_STATUS_SHUTDOWN ->
                "Shutdown" to Color.rgb(183, 28, 28)
            thermalStatus >= PowerManager.THERMAL_STATUS_EMERGENCY ->
                "Emergency" to Color.rgb(183, 28, 28)
            thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL ->
                "Critical" to Color.rgb(198, 40, 40)
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ->
                "Very hot" to Color.rgb(211, 47, 47)
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ->
                "Hot" to Color.rgb(230, 107, 0)
            thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT ->
                "Warm" to Color.rgb(196, 139, 0)
            else -> "Normal" to Color.rgb(46, 160, 67)
        }
        val heatText = "Heat: $label"
        val batteryText = batteryTemperatureC()?.let { temperature ->
            "Battery: ${String.format(Locale.US, "%.1f", temperature)}°C"
        } ?: "Battery: --"
        thermalStatusBadge.text = heatText
        thermalStatusBadge.contentDescription = heatText
        thermalStatusBadge.backgroundTintList = ColorStateList.valueOf(color)
        batteryTemperatureBadge.text = batteryText
        batteryTemperatureBadge.contentDescription = batteryText
        updateVideoHealthNotice()
    }

    /** One short, actionable line on the video screens: heat reduction or low battery, else nothing. */
    private fun updateVideoHealthNotice() {
        val notice = findViewById<TextView>(R.id.videoHealthNotice) ?: return
        val battery = getSystemService(android.os.BatteryManager::class.java)
        val level = battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val text = videoHealthNotice(
            thermalReductionActive = automaticThermalReductionEnabled && thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE,
            batteryPercent = level,
            charging = battery?.isCharging == true,
        )?.let(::getString)
        notice.text = text
        notice.visibility = if (text != null && appScreen in setOf(AppScreen.CAMERA, AppScreen.PLAYBACK)) View.VISIBLE else View.GONE
        updateStatusIndicators()
    }

    /** Watch: the lock explains encryption and verification of the stream source. */
    private fun showStreamInfo() {
        val source = activeStreamSourceNodeId?.toString()?.take(12)
        val name = source?.let { peerRowName(PeerLabels.forPeer(it, contactNames[it])) } ?: "-"
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_lock)
            .setTitle(R.string.stream_info_title)
            .setMessage(getString(R.string.stream_info_message, name))
            .apply {
                if (DebugTunables.localReporting && activeStreamSourceNodeId != null) {
                    val id = requireNotNull(activeStreamSourceNodeId)
                    val fingerprint = PeerIdentityGate.authenticatedFingerprint(id)
                        ?: savedContacts.firstOrNull { it.nodeIdHex == id.toString() }?.fingerprintHex?.let(IdentityFingerprint::fromHex)
                    if (StreamModerationPolicy.canModerateIdentity(activeStreamSourceAuthenticated, fingerprint != null)) {
                        setNegativeButton(R.string.block_action) { _, _ ->
                            CallBlockingUi.block(this@MainActivity, requireNotNull(fingerprint), name) { showReceiverBrowser() }
                        }
                        setNeutralButton(R.string.report_action) { _, _ ->
                            CallBlockingUi.report(this@MainActivity, fingerprint) { showReceiverBrowser() }
                        }
                    } else {
                        setNegativeButton(R.string.hide_livestream) { _, _ ->
                            // AB3: the route is kept only when this viewer's hop proved its fixed ID, else only this
                            // advertiser's radio address is hidden until the app restarts.
                            selectedStreamPeer?.let { peer ->
                                LocalReporting.hideStream(
                                    applicationContext, peer.announcement, peer.device.address,
                                    hopAuthenticated = activeStreamSourceNodeId != null,
                                )
                            }
                            showReceiverBrowser()
                        }
                    }
                }
            }
            .setPositiveButton(R.string.action_close, null)
            .showTracked()
    }

    /**
     * A decision from [thermalForecastSampler] (every 10 s): the post-warm-up forecast (or null) feeds the same
     * adaptation as before, and with the battery fallback on and no skin thresholds the battery temperature does too.
     */
    private fun onThermalSamplerDecision(headroom: Float?, sampler: ThermalHeadroomSampler) {
        var changed = false
        thermalSamplerNoSkin = sampler.noSkinThresholds
        if (thermalForecastOn) {
            val before = MemoryThermalPolicy.effectiveStatus(thermalStatus, thermalForecastHeadroom, true)
            thermalForecastHeadroom = headroom
            val after = MemoryThermalPolicy.effectiveStatus(thermalStatus, headroom, true)
            changed = after != before
        } else {
            thermalForecastHeadroom = null
        }
        if (ThermalSwitches.batteryFallback && sampler.noSkinThresholds) {
            val effective = ThermalBatteryFallback.effectiveStatus(thermalStatus, batteryTemperatureC(), true, true)
            if (effective != lastThermalFallbackStatus) {
                lastThermalFallbackStatus = effective
                changed = true
            }
            Log.i("BluetoothVideo", ThermalBatteryFallback.benchmarkLine(thermalStatus, batteryTemperatureC(), true, sampler))
        }
        Log.i("BluetoothVideo", sampler.benchmarkLine())
        if (changed && thermalVideoActive() && activeStreamSettings != null &&
            !ThermalGuardPolicy.shouldBlockStreaming(thermalStatus)
        ) {
            applyThermalAdaptation(thermalStatus, "forecast")
        }
    }

    /** Last status the battery-temperature fallback produced; a change re-runs the thermal adaptation. */
    private var lastThermalFallbackStatus = -1

    /** The sampler's latched "no skin thresholds" verdict (the fallback may only act on it). */
    @Volatile private var thermalSamplerNoSkin = false

    /** The platform status, raised by the Experimental battery-temperature fallback only when no skin thresholds exist. */
    private fun thermalReportedWithFallback(reported: Int): Int =
        if (ThermalSwitches.batteryFallback && thermalSamplerNoSkin) {
            ThermalBatteryFallback.effectiveStatus(reported, batteryTemperatureC(), true, true)
        } else {
            reported
        }

    private fun sampleThermalHeadroomIfDue() {
        if (MemoryThermalPolicy.headroomSamplerNeeded(thermalReductionOn)) {
            // Experimental forecast or fallback: the 2 s sampler owns the headroom reads (the 10 s path below stays unchanged).
            thermalForecastSampler?.ensureRunning()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastThermalHeadroomSampleMs < THERMAL_HEADROOM_SAMPLE_MS) return
        lastThermalHeadroomSampleMs = nowMs
        // The ADPF forecast (10 s ahead) is read only when the experiment is on; otherwise the current value.
        val forecastSeconds = if (thermalForecastOn) THERMAL_FORECAST_SECONDS else 0
        val headroom = powerManager.getThermalHeadroom(forecastSeconds)
        if (!headroom.isFinite()) {
            thermalForecastHeadroom = null
            return
        }
        Log.d("BluetoothVideo", "Thermal headroom ${String.format(Locale.US, "%.2f", headroom)}")
        if (!thermalForecastOn) {
            thermalForecastHeadroom = null
            return
        }
        val before = MemoryThermalPolicy.effectiveStatus(thermalStatus, thermalForecastHeadroom, true)
        thermalForecastHeadroom = headroom
        val after = MemoryThermalPolicy.effectiveStatus(thermalStatus, headroom, true)
        if (after != before && thermalVideoActive() && activeStreamSettings != null &&
            !ThermalGuardPolicy.shouldBlockStreaming(thermalStatus)
        ) {
            applyThermalAdaptation(thermalStatus, "forecast")
        }
    }

    private fun batteryTemperatureC(): Float? {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastBatteryTemperatureSampleMs < BATTERY_TEMPERATURE_SAMPLE_MS) {
            return cachedBatteryTemperatureC
        }
        lastBatteryTemperatureSampleMs = nowMs
        cachedBatteryTemperatureC = runCatching {
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return@runCatching null
            val tenthsCelsius = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (tenthsCelsius == Int.MIN_VALUE) null else tenthsCelsius / 10f
        }.getOrNull()
        return cachedBatteryTemperatureC
    }

    /**
     * DEBUG Bluetooth restart guard (DebugBluetoothRestartReceiver; main thread): the first session
     * that holds Bluetooth objects, or null when none does.
     */
    private fun activeBluetoothSession(): String? = when {
        privateCall != null -> "private_call"
        privateCallRelay != null -> "call_relay"
        broadcaster != null -> "broadcast"
        receiver != null -> "receiver"
        relay != null -> "relay"
        debugBenchmarkCoordinator != null -> "benchmark"
        else -> null
    }

    /**
     * DEBUG only. The launcher activity is exported, so the extra alone is not enough: it must carry
     * the one-shot token adb wrote into internal storage (EmergencyWipe.DEBUG_TOKEN_FILE). The panic
     * and confirmed wipes are reachable only from in-app UI.
     */
    private fun debugEmergencyWipeAuthorized(intent: Intent): Boolean =
        BuildConfig.DEBUG && EmergencyWipe.consumeDebugWipeToken(this, intent.getStringExtra(DEBUG_EMERGENCY_WIPE_EXTRA))

    /**
     * Emergency wipe step 4 (main thread): end every call, stream, relay and discovery this activity
     * owns, as onDestroy does, after a best-effort hang-up. The wipe then ends the process.
     */
    /** True when onCreate left early for the wipe-incomplete screen. */
    private var startupAborted = false

    private val emergencyWipeTerminator = WipeSessionTerminator {
        runCatching { privateCall?.hangUp() }
        thermalHandler.removeCallbacksAndMessages(null)
        sessionHandler.removeCallbacksAndMessages(null)
        runCatching { stopIdleCameraPreview() }
        runCatching { stopStreamPresence() }
        runCatching { stopNearbyStreamDiscovery() }
        runCatching { stopPrivateCallEndpoint() }
        runCatching { WifiRadio.shutdownAll("wipe") }
        runCatching { bleDiscovery?.close() }
        bleDiscovery = null
        runCatching { stopStreaming("Stopped.") }
        ActiveVideoSessionService.stop(this)
        finishAndRemoveTask()
    }

    /**
     * A CallStyle notification action (experimental, CallNotificationActions). Answer arrives as an
     * activity intent, Decline and Hang up as not-exported broadcasts; each must carry this process's
     * token and only acts in the matching call phase.
     */
    private fun onCallNotificationAction(intent: Intent) {
        val action = intent.getStringExtra(CallNotificationActions.EXTRA_ACTION)
        if (!CallUx.settings.callStyleNotifications ||
            !CallNotificationActions.isAuthentic(action, intent.getStringExtra(CallNotificationActions.EXTRA_TOKEN))
        ) {
            Log.w("BluetoothVideo", "Ignored a call notification action without a valid token")
            return
        }
        val command = CallNotificationActions.commandFor(action, privateCallPhase)
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK call_notification_action: action=$action | phase=${privateCallPhase?.name ?: "none"} | command=${command.name}",
        )
        when (command) {
            CallNotificationCommand.ACCEPT -> {
                stopPrivateCallRinging()
                runCatching { acceptPrivateCall() }.onFailure { setCallStatus(it.message ?: "Could not accept") }
            }
            CallNotificationCommand.DECLINE -> {
                stopPrivateCallRinging()
                runCatching { privateCall?.decline() }.onFailure { setCallStatus(it.message ?: "Could not decline") }
            }
            CallNotificationCommand.CANCEL -> {
                stopPrivateCallRinging()
                privateCall?.cancel()
            }
            CallNotificationCommand.HANG_UP -> {
                stopPrivateCallRinging()
                privateCall?.hangUp()
            }
            CallNotificationCommand.IGNORE -> Unit
        }
    }

    /**
     * DEBUG: `am broadcast -a CleanShutdown.ACTION -p <package> --es nonce <n>` runs the shutdown control in a running app.
     * Unlike `am start ... --es test_control shutdown` it never starts a fresh process.
     */
    private val debugCleanShutdownReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (!BuildConfig.DEBUG || isFinishing || isDestroyed) return
            onNewIntent(
                Intent(this@MainActivity, MainActivity::class.java)
                    .putExtra("test_control", "shutdown")
                    .putExtra(CleanShutdown.EXTRA_NONCE, intent.getStringExtra(CleanShutdown.EXTRA_NONCE)),
            )
        }
    }
    private var debugCleanShutdownRegistered = false

    private val callNotificationReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            onCallNotificationAction(intent)
        }
    }
    private var callNotificationReceiverRegistered = false

    private fun registerCallNotificationReceiver() {
        if (!CallUx.settings.callStyleNotifications || callNotificationReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            callNotificationReceiver,
            android.content.IntentFilter(CallNotificationActions.BROADCAST_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        callNotificationReceiverRegistered = true
    }

    override fun onDestroy() {
        if (CodecPowerAware.provider === codecPowerProvider) CodecPowerAware.provider = null
        if (CallListenerViewing.probe === viewingProbe) CallListenerViewing.probe = null
        sessionHandler.removeCallbacks(callEndpointSettleTimeout)
        GattPoolBackoff.onScannerChanged = null
        BluetoothEpoch.unregister(streamEpochName)
        verifiedStoreBanner?.close()
        WifiSwitchHub.removeListener(wifiSwitchListener)
        WifiSwitchHub.removeListener(directSwitchListener)
        chatTickHandler.removeCallbacksAndMessages(null)
        if (threadScreenDelegate.isInitialized()) threadScreen.onDestroy()
        if (MessagingV2Feature.enabled) conversationRows.stop()
        NameWindowRuntime.removeListener(nameWindowChanged)
        if (MeetBridge.host === meetHost) MeetBridge.host = null
        if (VerificationEvents.listener === verificationListener) VerificationEvents.listener = null
        DialCollision.ownDial = { null }
        meetCancelScan()
        updatesHome?.stop()
        if (callNotificationReceiverRegistered) {
            runCatching { unregisterReceiver(callNotificationReceiver) }
            callNotificationReceiverRegistered = false
        }
        if (debugCleanShutdownRegistered) {
            runCatching { unregisterReceiver(debugCleanShutdownReceiver) }
            debugCleanShutdownRegistered = false
        }
        if (startupAborted) {
            contactWorker.shutdown()
            // onCreate stopped before any view, service or radio existed.
            super.onDestroy()
            return
        }
        callLockScreen.release()
        relayedCalls?.stop()
        relayedCalls = null
        if (relayStopReceiverRegistered) {
            runCatching { unregisterReceiver(relayStopReceiver) }
            relayStopReceiverRegistered = false
        }
        ActiveVideoSessionService.outcomeListener = null
        ActiveVideoSessionService.heldTypesListener = null
        sessionHandler.removeCallbacks(callReachabilityServiceChanged)
        ScanStartGate.deferralListener = null
        OfflineAppShareGate.liveSessionCheck = null
        EmergencyWipe.unregisterSessionTerminator(emergencyWipeTerminator)
        if (BuildConfig.DEBUG && BluetoothRestart.busyProbe === bluetoothRestartProbe) BluetoothRestart.busyProbe = null
        thermalHandler.removeCallbacksAndMessages(null)
        sessionHandler.removeCallbacksAndMessages(null)
        stopIdleCameraPreview()
        stopStreamPresence()
        stopNearbyStreamDiscovery()
        stopPrivateCallEndpoint()
        // Drain the ended-call history write queued by endpoint cleanup.
        contactWorker.shutdown()
        bleDiscovery?.close()
        bleDiscovery = null
        debugBenchmarkCoordinator?.close()
        debugBenchmarkCoordinator = null
        debugSessionTelemetry?.close()
        debugSessionTelemetry = null
        if (thermalListenerRegistered) {
            powerManager.removeThermalStatusListener(thermalStatusListener)
            thermalListenerRegistered = false
        }
        stopStreaming("Stopped.")
        privateCallCameraExecutor.shutdown()
        // A quick wipe still inside its Undo window was not undone: run it now rather than let
        // the activity teardown cancel it silently (it uses only the application context).
        pendingPanicWipe?.let { wipe ->
            sessionHandler.removeCallbacks(wipe)
            pendingPanicWipe = null
            Log.i("BluetoothVideo", "Emergency wipe: panic gesture runs at activity teardown")
            OnboardingActivity.reset(this)
            val context = applicationContext
            Thread { runCatching { SecurityUiBackend.current.wipe(context, panic = true, confirmationText = null) } }.start()
        }
        if (::callChromeAutoHide.isInitialized) callChromeAutoHide.release()
        if (CleanShutdown.requested) {
            // DEBUG clean shutdown: the teardown above closed the endpoint, discovery, advertisers and streams. Stop any
            // scanner left and the session service, then log clean_shutdown: done once nothing is registered (or 2.5 s).
            ScanRegistry.stopAll()
            runCatching { ActiveVideoSessionService.stop(applicationContext) }
            runCatching { WifiRadio.shutdownAll("clean_shutdown") }
            CleanShutdown.awaitAndLog(Handler(Looper.getMainLooper()))
        }
        super.onDestroy()
    }

    private data class SurfaceVideoFormat(
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val scaleMode: VideoScaleMode,
    )

    private companion object {
        /** Wait before an idle call endpoint is rebuilt after a feature switch (the listener must close first). */
        const val FEATURE_ENDPOINT_REBUILD_DELAY_MS = 1_000L
        /** How many received messages the Home Messages tile scans for its unread count. */
        const val UNREAD_SCAN_LIMIT = 200
        /** Key of the single call link in its rate controller. */
        const val PRIVATE_CALL_RATE_LINK = "private-call"
        /** The call frame-rate ladder never goes below this (faces stay readable). */
        const val MIN_CALL_FPS = 5
        /** One direction's share of a two-way call link: half, less the 15% rate-control headroom. */
        const val CALL_LINK_SHARE = 0.425
        /** Android's default visibility duration; a first contact needs about one 10 s inquiry. */
        const val CLASSIC_VISIBILITY_SECONDS = 120
        const val APP_PREFERENCES = "bluetooth_video_settings"
        /** DEBUG launch/new-intent string extra: the adb-written token; runs EmergencyWipe and ends the process. */
        const val DEBUG_EMERGENCY_WIPE_EXTRA = "test_emergency_wipe"
        const val PREF_AUTOMATIC_THERMAL_REDUCTION = "automatic_thermal_reduction"
        const val PREF_MV2_NOTIFICATION_ASKED = "mv2_notification_permission_asked"
        const val PREF_SHOW_DEVICE_HEALTH = "show_device_health"
        const val PREF_FORWARD_RECEIVED_STREAMS = "forward_received_streams"
        const val PREF_MANUAL_RATE_CONTROL = "manual_rate_control"
        const val PREF_ADVANCED_SETTINGS = com.example.bluetoothtest.PREF_ADVANCED_SETTINGS
        private const val PREF_NEUTRAL_NAME_NOTICE_SHOWN = "neutral_name_migration_notice_shown"
        const val PREF_EXPERIMENTAL_TEMPORAL_LAYERS = "experimental_temporal_layers"
        const val PREF_RELAY_CHILD_MEDIA_AGE = "relay_child_media_age"
        const val PREF_OUTGOING_TRANSPORT = "outgoing_transport"
        const val PREF_VIDEO_WIDTH = "video_width"
        const val PREF_VIDEO_HEIGHT = "video_height"
        const val PREF_VIDEO_FPS = "video_fps"
        const val PREF_VIDEO_BITRATE = "video_bitrate"
        const val PREF_SENDER_PREVIEW_SCALE_MODE = "sender_preview_scale_mode"
        const val PREF_RECEIVER_PLAYBACK_SCALE_MODE = "receiver_playback_scale_mode"
        const val PREF_RECEIVER_PLAYBACK_DELAY = "receiver_playback_delay"
        const val PREF_VIDEO_SCALE_MODE = "video_scale_mode"
        const val THERMAL_BADGE_REFRESH_MS = 5_000L
        const val THERMAL_HEADROOM_SAMPLE_MS = 10_000L
        const val THERMAL_FORECAST_SECONDS = 10
        const val BATTERY_TEMPERATURE_SAMPLE_MS = 30_000L
        const val CONTINUITY_REPORT_NS = 5_000_000_000L
        const val THERMAL_RECOVERY_DELAY_MS = 30_000L
        const val CONTINUOUS_SCAN_MS = 0L
        const val DISCOVERY_RESTART_MS = 500L
        const val GATT_CALL_RESTART_DELAY_MS = 1_500L
        /** The longest a viewer start waits for an overtaken call-endpoint start to close itself (usually well under 1 s). */
        const val CALL_ENDPOINT_SETTLE_TIMEOUT_MS = 3_000L
        const val RELAYED_CALLS_RESTART_MS = 600L
        /** Experimental automatic sync: how often one peer's sightings may query the message store. */
        const val AUTO_SYNC_EVAL_INTERVAL_MS = 5_000L
        /** Messaging v2 chat session: how often one peer's sightings may start a dial decision, and the idle-end tick. */
        const val CHAT_EVAL_INTERVAL_MS = 1_000L
        const val CHAT_TICK_MS = 2_000L
        /** One process-wide thread for automatic-sync store reads, so activity recreation never adds threads. */
        private val autoSyncExecutor by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
        const val VIEWER_REQUEST_STALE_MS = 12_000L
        const val VIEWER_EXPIRY_CHECK_MS = 4_000L
        const val CACHED_ROUTE_MAX_AGE_MS = 10_000L
        const val NEARBY_EXPIRY_CHECK_MS = 1_000L

        /** F16 fix 1: the reverse-direction share never caps this side's video below this. */
        const val CALL_REVERSE_SHARE_FLOOR_BPS = 60_000

        /** F15: a "not found nearby" dialog closes itself after this long. */
        const val NOT_FOUND_DIALOG_MS = 60_000L
        const val SCAN_ERROR_RETRY_MS = 30_000L
        const val ROUTE_SCAN_ERROR_RETRY_MS = 2_000L
        const val PAUSED_HEARTBEAT_MS = 2_000L
        const val FAILED_ROUTE_SUPPRESSION_MS = 8_000L

        /** "Couldn't join" stays up long enough to read and press Try again. */
        const val STREAM_JOIN_SNACKBAR_MS = 10_000
        const val WITHDRAWN_ROUTE_SUPPRESSION_MS = 30_000L
        const val RECEIVER_NOTICE_MS = 3_000L
    }
}
