package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlin.concurrent.thread

/**
 * The audio-only live broadcast UI and its glue to the activity (Design AL). New code, new layouts, styled
 * per the UI spec; MainActivity holds only small hooks that call this class (search "AUDIO-LIVE hook").
 *
 * Screens: the Go live choice sheet (Video and audio | Audio only, the last choice white), the
 * first-use disclosure, and the broadcast screen (title, quality, level meter, listener count,
 * Mute / Pause / Stop). The listening screen is [AudioLiveListenUi] (slice 3).
 *
 * The broadcast itself reuses the activity's discoverable-stream machinery (advertising, viewer requests,
 * [BluetoothVideoBroadcaster]); this class only supplies the audio source and the screen.
 */
class AudioLiveController(
    private val activity: AppCompatActivity,
    private val host: Host,
) {
    /** What the activity provides. Everything here runs on the main thread. */
    interface Host {
        val preferences: SharedPreferences

        /** Shows the existing video Go live screen. */
        fun showVideoGoLive()

        /** Shows the logical broadcast screen state (appScreen, other screens hidden) without the camera. */
        fun enterAudioBroadcastScreen()

        /** Back to the Live tab. */
        fun leaveToLive()

        /** Arms the discoverable stream in audio mode (foreground service, advertising). */
        fun armAudioBroadcast(title: String?)

        /** Stops everything with a status message (the activity's stopStreaming). */
        fun stopBroadcast(message: String)

        /** Ends the audio broadcast, then starts a video live with the same title (a new stream, never converted in place). */
        fun switchToVideo()

        fun togglePause()

        fun streamPaused(): Boolean

        fun namesAndTitlesOn(): Boolean

        fun hasMicrophonePermission(): Boolean

        /** Asks for the microphone (and the Bluetooth permissions the screen needs), then calls [onGranted]. */
        fun requestMicrophonePermission(onGranted: () -> Unit)

        /** DEBUG launch extra: reference PCM that replaces the microphone (the same file video streams use). */
        fun debugReferenceAudioFile(): java.io.File?

        // --- Listening ---

        /** Stops receiving and returns to the Live tab. */
        fun leaveListening()

        /** Silences or restores the playout of the running receiver (the link stays up). */
        fun setReceiverMuted(muted: Boolean)

        /** Relays between this phone and the source for a route cost advertised in an offer (0 = direct). */
        fun relayCount(routeCost: Int): Int
    }

    /** True from opening the broadcast screen until the broadcast is stopped. */
    var mode: Boolean = false
        private set

    /** True while the listening screen of an audio-only stream is up (joined, reconnecting or just ended). */
    var listening: Boolean = false
        private set

    var source: AudioLiveSource? = null
        private set

    private enum class Phase { SETUP, STARTING, LIVE }

    private val main = Handler(Looper.getMainLooper())
    private var phase = Phase.SETUP
    private var root: View? = null
    private var preset = AudioLive.DEFAULT_PRESET
    private var listeners = 0
    private var liveSinceMs = 0L
    private var muted = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = onBack()
    }

    init {
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
        // "Still broadcasting": opening the app answers the reminder.
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                reminder?.onUserPresent(SystemClock.elapsedRealtime())
                cancelReminder()
            }
        })
    }

    private var reminder: AudioLiveReminderPolicy? = null
    private var actionReceiver: BroadcastReceiver? = null

    /** Mute and Stop from the notification (broadcaster and listener share them). */
    private fun registerNotificationActions() {
        if (actionReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!AudioLiveNotification.isAuthentic(intent.getStringExtra(AudioLiveNotification.EXTRA_TOKEN))) return
                main.post {
                    when (intent.action) {
                        AudioLiveNotification.ACTION_MUTE ->
                            if (mode) toggleMute() else if (listening) toggleListenMute()
                        AudioLiveNotification.ACTION_STOP ->
                            if (mode) host.stopBroadcast("Stopped.") else if (listening) host.leaveListening()
                    }
                }
            }
        }
        actionReceiver = receiver
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter().apply {
                addAction(AudioLiveNotification.ACTION_MUTE)
                addAction(AudioLiveNotification.ACTION_STOP)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unregisterNotificationActions() {
        if (mode || listening) return
        actionReceiver?.let { runCatching { activity.unregisterReceiver(it) } }
        actionReceiver = null
    }

    private fun postReminder() {
        val manager = activity.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                AudioLiveNotification.REMINDER_CHANNEL,
                activity.getString(R.string.audio_live_reminder_title),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val open = PendingIntent.getActivity(
            activity, 0, Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(activity, AudioLiveNotification.REMINDER_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_meshlive)
            .setContentTitle(activity.getString(R.string.audio_live_reminder_title))
            .setContentText(activity.getString(R.string.audio_live_reminder_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, activity.getString(R.string.audio_live_notification_stop), AudioLiveNotification.stopIntent(activity))
            .let { NotificationPrivacy.apply(it, activity, AudioLiveNotification.REMINDER_CHANNEL, NotificationKind.AUDIO_BROADCAST, R.drawable.ic_stat_meshlive, false) }
            .build()
        runCatching { manager.notify(AudioLiveNotification.REMINDER_ID, notification) }
    }

    private fun cancelReminder() {
        runCatching { activity.getSystemService(NotificationManager::class.java)?.cancel(AudioLiveNotification.REMINDER_ID) }
    }

    // --- Go live choice ---------------------------------------------------------------------------------------

    /** The pinned Go live button. With the feature off this is exactly the old behaviour. */
    fun onGoLive() {
        if (!AudioLiveFeatures.enabled) {
            host.showVideoGoLive()
            return
        }
        val sheet = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_go_live_choice, null)
        val video = view.findViewById<MaterialButton>(R.id.goLiveChoiceVideo)
        val audio = view.findViewById<MaterialButton>(R.id.goLiveChoiceAudio)
        val last = host.preferences.getString(AudioLiveSettings.PREF_LAST_CHOICE, AudioLiveSettings.CHOICE_VIDEO)
        highlight(video, last != AudioLiveSettings.CHOICE_AUDIO)
        highlight(audio, last == AudioLiveSettings.CHOICE_AUDIO)
        video.setOnClickListener {
            host.preferences.edit().putString(AudioLiveSettings.PREF_LAST_CHOICE, AudioLiveSettings.CHOICE_VIDEO).apply()
            sheet.dismiss()
            host.showVideoGoLive()
        }
        audio.setOnClickListener {
            host.preferences.edit().putString(AudioLiveSettings.PREF_LAST_CHOICE, AudioLiveSettings.CHOICE_AUDIO).apply()
            sheet.dismiss()
            confirmDisclosureThen { openBroadcastScreen() }
        }
        sheet.setContentView(view)
        sheet.show()
    }

    /** The remembered choice is the white button (primary action), the other the raised one. */
    private fun highlight(button: MaterialButton, primary: Boolean) {
        val context = button.context
        button.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, if (primary) R.color.accent else R.color.surface_raised),
        )
        val content = ContextCompat.getColor(context, if (primary) R.color.on_accent else R.color.text_primary)
        button.setTextColor(content)
        button.iconTint = ColorStateList.valueOf(content)
    }

    private fun confirmDisclosureThen(proceed: () -> Unit) {
        if (host.preferences.getBoolean(AudioLiveSettings.PREF_DISCLOSED, false)) {
            proceed()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.audio_live_disclosure_title)
            .setMessage(R.string.audio_live_disclosure_message)
            .setPositiveButton(R.string.audio_live_disclosure_confirm) { _, _ ->
                host.preferences.edit().putBoolean(AudioLiveSettings.PREF_DISCLOSED, true).apply()
                proceed()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // --- Broadcast screen -------------------------------------------------------------------------------------

    /** DEBUG benchmark launch only: the preset and title of an automatic start (never saved as the user's choice). */
    private var debugPreset: Int? = null
    private var debugTitle: String? = null

    /**
     * `test_audio_live_autostart` (DEBUG): starts a radio without any tap, for phones that ignore injected
     * taps. The first-use disclosure is skipped only here, and the skip is logged. Microphone and Nearby
     * permissions must already be granted (otherwise the usual prompt appears and Start continues after it).
     */
    fun debugAutoStart(request: AudioLiveAutoStart.Request) {
        if (!BuildConfig.DEBUG) return
        if (!AudioLiveFeatures.enabled) {
            AudioLiveLog.info("BENCHMARK audio_live_autostart: result=feature_off | hint=add test_audio_live=true")
            return
        }
        if (mode || listening) {
            AudioLiveLog.info("BENCHMARK audio_live_autostart: result=busy")
            return
        }
        debugPreset = request.preset
        debugTitle = request.title
        AudioLiveLog.info(
            "BENCHMARK audio_live_autostart: result=starting | preset=${request.preset} | " +
                "title=${request.title != null} | disclosureSkipped=${!host.preferences.getBoolean(AudioLiveSettings.PREF_DISCLOSED, false)}",
        )
        openBroadcastScreen()
        main.post { onStart() }
    }

    /** DEBUG `test_control=audio_mute|audio_unmute`: Mute on the broadcast screen or on the listening screen. */
    fun debugSetMuted(wantMuted: Boolean): Boolean {
        if (mode && source != null && phase == Phase.LIVE) {
            if (muted != wantMuted) toggleMute()
            return true
        }
        if (listening) {
            if (listenMuted != wantMuted) toggleListenMute()
            return true
        }
        return false
    }

    private fun openBroadcastScreen() {
        if (mode) return
        mode = true
        phase = Phase.SETUP
        listeners = 0
        muted = false
        preset = debugPreset ?: host.preferences.getInt(AudioLiveSettings.PREF_PRESET, AudioLive.DEFAULT_PRESET)
            .takeIf(AudioLive::isValidPreset) ?: AudioLive.DEFAULT_PRESET
        host.enterAudioBroadcastScreen()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val view = LayoutInflater.from(activity).inflate(R.layout.screen_audio_broadcast, content, false)
        content.addView(view)
        root = view
        backCallback.isEnabled = true
        bindBroadcastScreen(view)
        render()
    }

    private fun bindBroadcastScreen(view: View) {
        view.findViewById<ImageButton>(R.id.audioBackButton).setOnClickListener { onBack() }
        view.findViewById<View>(R.id.audioTitleGroup).visibility = if (host.namesAndTitlesOn()) View.VISIBLE else View.GONE
        view.findViewById<EditText>(R.id.audioTitleInput).setText(debugTitle ?: PeerLabelFeature.localStreamTitle.orEmpty())
        val options = mapOf(
            AudioLive.PRESET_VOICE to R.id.audioQualityVoice,
            AudioLive.PRESET_LOW_DATA to R.id.audioQualityLow,
            AudioLive.PRESET_MUSIC to R.id.audioQualityMusic,
        )
        options.forEach { (value, id) ->
            view.findViewById<TextView>(id).setOnClickListener {
                if (phase != Phase.SETUP) return@setOnClickListener
                preset = value
                host.preferences.edit().putInt(AudioLiveSettings.PREF_PRESET, value).apply()
                renderQuality(view)
            }
        }
        view.findViewById<MaterialButton>(R.id.audioStartButton).setOnClickListener { onStart() }
        view.findViewById<MaterialButton>(R.id.audioMuteButton).setOnClickListener { toggleMute() }
        view.findViewById<MaterialButton>(R.id.audioPauseButton).setOnClickListener { host.togglePause() }
        view.findViewById<MaterialButton>(R.id.audioStopButton).setOnClickListener { host.stopBroadcast("Stopped.") }
        view.findViewById<MaterialButton>(R.id.audioSwitchToVideoButton).setOnClickListener {
            if (phase != Phase.LIVE) return@setOnClickListener
            host.preferences.edit().putString(AudioLiveSettings.PREF_LAST_CHOICE, AudioLiveSettings.CHOICE_VIDEO).apply()
            host.switchToVideo()
        }
    }

    private fun renderQuality(view: View) {
        val options = listOf(
            AudioLive.PRESET_VOICE to R.id.audioQualityVoice,
            AudioLive.PRESET_LOW_DATA to R.id.audioQualityLow,
            AudioLive.PRESET_MUSIC to R.id.audioQualityMusic,
        )
        options.forEach { (value, id) ->
            val label = view.findViewById<TextView>(id)
            val selected = value == preset
            label.setBackgroundResource(if (selected) R.drawable.segment_selected else 0)
            label.setTextColor(ContextCompat.getColor(activity, if (selected) R.color.black else R.color.text_secondary))
            label.isSelected = selected
        }
        val name = activity.getString(
            when (preset) {
                AudioLive.PRESET_LOW_DATA -> R.string.audio_live_quality_low
                AudioLive.PRESET_MUSIC -> R.string.audio_live_quality_music
                else -> R.string.audio_live_quality_voice
            },
        )
        val wireKbps = MeshBandwidthEstimator.audioLiveWireKbps(AudioLive.presetKbps(preset), AudioLive.DEFAULT_BATCH_FRAMES)
        view.findViewById<TextView>(R.id.audioQualityDetail).text =
            activity.getString(R.string.audio_live_quality_detail, name, Math.round(wireKbps).toInt())
    }

    private fun onStart() {
        if (phase != Phase.SETUP) return
        if (!host.hasMicrophonePermission()) {
            host.requestMicrophonePermission { onStart() }
            return
        }
        phase = Phase.STARTING
        render()
        val root = root ?: return
        val titleText = if (host.namesAndTitlesOn()) {
            root.findViewById<EditText>(R.id.audioTitleInput).text?.toString()?.trim().orEmpty()
        } else {
            ""
        }
        val title = titleText.takeIf { it.isNotEmpty() }?.let {
            runCatching { LabelText.normalize(LabelKind.STREAM_TITLE, it) }.getOrNull()
        }
        val chosenPreset = preset
        // The identity key certifies a fresh stream key once (a Keystore signature): off the main thread.
        thread(name = "AudioLivePrepare") {
            val prepared = runCatching {
                AudioLiveSource.prepare(
                    chosenPreset,
                    onLevel = { level -> main.post { renderLevel(level) } },
                    onError = { message -> main.post { fail(message) } },
                    debugReferenceFile = debugReferenceFile(),
                )
            }
            main.post {
                if (!mode || phase != Phase.STARTING) {
                    prepared.getOrNull()?.stop()
                    return@post
                }
                prepared.onSuccess { newSource ->
                    source = newSource
                    liveSinceMs = SystemClock.elapsedRealtime()
                    reminder = AudioLiveReminderPolicy(liveSinceMs)
                    registerNotificationActions()
                    phase = Phase.LIVE
                    host.armAudioBroadcast(title)
                    render()
                    main.post(tick)
                }.onFailure { error ->
                    AudioLiveLog.failure("prepare", error)
                    fail("Audio broadcast could not start: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

    /** DEBUG only: the launch extra `test_reference_audio` already used by video streams feeds the microphone. */
    private fun debugReferenceFile(): java.io.File? = if (BuildConfig.DEBUG) host.debugReferenceAudioFile() else null

    private fun toggleMute() {
        val active = source ?: return
        if (phase != Phase.LIVE) return
        muted = !muted
        if (muted) active.mute() else active.unmute()
        render()
    }

    // --- Hooks called by the activity -------------------------------------------------------------------------

    /** The first listener of a broadcaster is connected: start (or restart) the microphone and the stream. */
    fun onListenerReady(broadcaster: BluetoothVideoBroadcaster) {
        source?.attach(broadcaster)
        render()
    }

    /** Every listener left. */
    fun onAllListenersGone() {
        source?.detach()
        render()
    }

    fun onListenersChanged(count: Int) {
        listeners = count
        reminder?.onListeners(count, SystemClock.elapsedRealtime())
        render()
    }

    /** The broadcast was paused or resumed through the activity (button, notification, or a remote stop). */
    fun onPauseChanged(paused: Boolean) {
        if (paused) source?.pause() else source?.resume()
        render()
    }

    /** The foreground service outcome: a microphone type refused means a silent broadcast, which is not kept. */
    fun onForegroundTypes(effectiveTypes: Int) {
        val holdsMicrophone = effectiveTypes and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0
        source?.onMicrophoneServiceChanged(holdsMicrophone)
    }

    /** The activity stopped the whole stream (user, error or thermal). Tears the screen down. */
    fun onStopped(message: String) {
        if (!mode) return
        mode = false
        phase = Phase.SETUP
        main.removeCallbacks(tick)
        source?.stop()
        source = null
        backCallback.isEnabled = false
        root?.let { (it.parent as? ViewGroup)?.removeView(it) }
        root = null
        reminder = null
        debugPreset = null
        debugTitle = null
        cancelReminder()
        unregisterNotificationActions()
        host.leaveToLive()
        if (message != "Stopped.") {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.audio_live_stopped_title)
                .setMessage(message)
                .setPositiveButton(R.string.audio_live_ok, null)
                .show()
        }
    }

    private fun fail(message: String) {
        if (!mode) return
        host.stopBroadcast(message)
    }

    private fun onBack() {
        when (phase) {
            Phase.SETUP -> {
                mode = false
                backCallback.isEnabled = false
                root?.let { (it.parent as? ViewGroup)?.removeView(it) }
                root = null
                host.leaveToLive()
            }
            else -> host.stopBroadcast("Stopped.")
        }
    }

    // --- Listening --------------------------------------------------------------------------------------------

    private enum class ListenPhase { CONNECTING, LIVE, RECONNECTING, PAUSED, ENDED }

    private var listenRoot: View? = null
    private var listenPhase = ListenPhase.CONNECTING
    private var listenRouteText = ""
    private var listenName = ""
    private var listenStartedMs = 0L
    private var listenMuted = false
    private var listenSourceFingerprint: IdentityFingerprint? = null
    private var focusPolicy: AudioLiveFocusPolicy? = null
    private var focusRequest: android.media.AudioFocusRequest? = null
    private val listenBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = host.leaveListening()
    }
    private var listenBackRegistered = false

    /** True for an offer this build lists as an audio-only stream and can join. */
    fun canListenTo(announcement: DiscoveryAnnouncement): Boolean =
        AudioLiveFeatures.enabled && announcement.isAudioOnlyOffer()

    /** The listener tapped an audio row (or a reconnect picked a new route): show the listening screen. */
    private var listenToken: Int? = null

    /** The route being listened to (a blocked source hides this route, not every offer with its token). */
    private var listenOffer: DiscoveryAnnouncement? = null

    fun onJoin(announcement: DiscoveryAnnouncement, title: String?) {
        listenToken = announcement.logicalStreamToken()
        listenOffer = announcement
        listenSourceFingerprint = null
        listenRoot?.findViewById<View>(R.id.audioListenReport)?.visibility = View.GONE
        if (!canListenTo(announcement)) return
        val relays = host.relayCount(announcement.routeCost)
        listenRouteText = activity.getString(
            when (relays) {
                0 -> R.string.audio_live_route_direct
                1 -> R.string.audio_live_route_via_one
                else -> R.string.audio_live_route_via
            },
            relays,
        )
        if (title != null) listenName = title
        if (!listening) {
            listening = true
            listenMuted = false
            listenPhase = ListenPhase.CONNECTING
            if (!listenBackRegistered) {
                activity.onBackPressedDispatcher.addCallback(activity, listenBackCallback)
                listenBackRegistered = true
            }
            listenBackCallback.isEnabled = true
            // The media session exists before the session service builds its notification (no title, no metadata).
            AudioLiveMediaSession.open(activity) { main.post { toggleListenMute() } }
            registerNotificationActions()
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            val view = LayoutInflater.from(activity).inflate(R.layout.screen_audio_listen, content, false)
            content.addView(view)
            listenRoot = view
            bindListenScreen(view)
        } else if (listenPhase != ListenPhase.ENDED) {
            listenPhase = ListenPhase.CONNECTING
        }
        renderListen()
    }

    private fun bindListenScreen(view: View) {
        view.findViewById<View>(R.id.audioListenReport).apply {
            visibility = View.GONE
            setOnClickListener { CallBlockingUi.report(activity, listenSourceFingerprint, onDone = host::leaveListening) }
        }
        view.findViewById<ImageButton>(R.id.audioListenBack).setOnClickListener { host.leaveListening() }
        view.findViewById<MaterialButton>(R.id.audioListenStop).setOnClickListener { host.leaveListening() }
        view.findViewById<MaterialButton>(R.id.audioListenMute).setOnClickListener { toggleListenMute() }
        view.findViewById<MaterialButton>(R.id.audioListenBlock).setOnClickListener {
            val fingerprint = listenSourceFingerprint ?: return@setOnClickListener
            // The store and the presence revocation of a blocked contact (finding 3) touch the Keystore: off the main thread.
            val context = activity.applicationContext
            thread(name = "AudioLiveBlock", isDaemon = true) { BlockedPeople.block(context, fingerprint) }
            listenOffer?.let(PersonPolicy::hideStream)
            android.widget.Toast.makeText(activity, R.string.audio_live_listen_blocked, android.widget.Toast.LENGTH_LONG).show()
            host.leaveListening()
        }
    }

    private fun toggleListenMute() {
        listenMuted = !listenMuted
        focusPolicy?.setUserMuted(listenMuted)
        applyListenOutput()
        renderListen()
    }

    /** The authenticated header arrived: an audio-only stream is playing here. Takes audio focus. */
    fun onListenFormat(header: StreamProtocol.Header) {
        if (!listening) return
        listenSourceFingerprint = header.audioLiveAuth?.let { IdentityFingerprint.fromPublicKey(it.identitySpki) }
        if (listenSourceFingerprint?.let { LocalReporting.hidden(activity, it.hex) } == true) {
            // AB3: the route that played is hidden for this run only, as for a blocked person; never its bare token.
            listenOffer?.let(PersonPolicy::hideStream)
            host.leaveListening()
            return
        }
        // Review 2026-10-27 (finding 4): the header's identity key names the source; a blocked person's radio (also under a
        // rotating ID the list could not attribute) is left at once and its offer stays hidden.
        if (PersonPolicy.blocked(listenSourceFingerprint)) {
            listenOffer?.let(PersonPolicy::hideStream)
            android.util.Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_blocking: event=stream_left | kind=audio | reason=blocked_person",
            )
            host.leaveListening()
            return
        }
        listenRoot?.findViewById<View>(R.id.audioListenBlock)?.visibility =
            if (listenSourceFingerprint != null) View.VISIBLE else View.GONE
        listenRoot?.findViewById<View>(R.id.audioListenReport)?.visibility =
            if (listenSourceFingerprint != null && DebugTunables.localReporting) View.VISIBLE else View.GONE
        if (focusPolicy == null) startFocus()
        renderListen()
    }

    fun onListenActive(active: Boolean) {
        if (!listening) return
        if (active) {
            if (listenPhase != ListenPhase.PAUSED && listenPhase != ListenPhase.ENDED) {
                if (listenPhase != ListenPhase.LIVE) listenStartedMs = SystemClock.elapsedRealtime()
                listenPhase = ListenPhase.LIVE
            }
        }
        renderListen()
    }

    fun onListenState(state: StreamLifecycleState) {
        if (!listening) return
        listenPhase = when (state) {
            StreamLifecycleState.LIVE,
            StreamLifecycleState.LIVE_MUTED,
            StreamLifecycleState.LIVE_CAMERA_OFF,
            StreamLifecycleState.LIVE_MUTED_CAMERA_OFF -> ListenPhase.LIVE
            StreamLifecycleState.PAUSED -> ListenPhase.PAUSED
            StreamLifecycleState.ENDED -> ListenPhase.ENDED
        }
        renderListen()
    }

    /** The receiver stopped; [reconnecting] is true when the activity keeps looking for the same stream. */
    fun onListenLinkLost(reconnecting: Boolean) {
        if (!listening) return
        if (listenPhase != ListenPhase.ENDED) {
            listenPhase = if (reconnecting) ListenPhase.RECONNECTING else ListenPhase.ENDED
        }
        renderListen()
    }

    fun onListenLevel(level: Float) {
        val meter = listenRoot?.findViewById<LinearProgressIndicator>(R.id.audioListenMeter) ?: return
        meter.setProgressCompat((level * 100).toInt().coerceIn(0, 100), false)
    }

    /** The listening screen goes away (back to the Live tab, or the receiver was replaced). */
    fun endListening() {
        if (!listening) return
        listening = false
        main.removeCallbacks(focusTick)
        abandonFocus()
        focusPolicy = null
        listenBackCallback.isEnabled = false
        AudioLiveMediaSession.close()
        unregisterNotificationActions()
        listenRoot?.let { (it.parent as? ViewGroup)?.removeView(it) }
        listenRoot = null
        listenSourceFingerprint = null
        listenName = ""
    }

    private fun applyListenOutput() {
        val muted = focusPolicy?.verdict()?.outputMuted ?: listenMuted
        host.setReceiverMuted(muted)
    }

    private fun startFocus() {
        val policy = AudioLiveFocusPolicy { SystemClock.elapsedRealtime() }
        policy.setUserMuted(listenMuted)
        focusPolicy = policy
        val audioManager = activity.getSystemService(android.media.AudioManager::class.java)
        val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                main.post {
                    val verdict = policy.onFocusChange(change)
                    host.setReceiverMuted(verdict.outputMuted)
                    if (verdict.leave) host.leaveListening()
                    renderListen()
                }
            }
            .build()
        focusRequest = request
        // A refused request still plays (a call or another app owns focus): the policy mutes on a later loss.
        runCatching { audioManager.requestAudioFocus(request) }
        main.postDelayed(focusTick, 1_000L)
    }

    private val focusTick = object : Runnable {
        override fun run() {
            val policy = focusPolicy ?: return
            if (policy.verdict().leave) {
                host.leaveListening()
                return
            }
            renderListenTimer()
            main.postDelayed(this, 1_000L)
        }
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { activity.getSystemService(android.media.AudioManager::class.java).abandonAudioFocusRequest(request) }
    }

    private fun renderListen() {
        val view = listenRoot ?: return
        view.findViewById<TextView>(R.id.audioListenName).text =
            listenName.ifBlank { activity.getString(R.string.audio_live_listen_name_default) }
        view.findViewById<TextView>(R.id.audioListenRoute).text = listenRouteText
        val live = listenPhase == ListenPhase.LIVE
        view.findViewById<TextView>(R.id.audioListenBadge).visibility = if (live) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.audioListenTimer).visibility =
            if (live || listenPhase == ListenPhase.PAUSED) View.VISIBLE else View.GONE
        val outputMuted = focusPolicy?.verdict()?.outputMuted ?: listenMuted
        view.findViewById<TextView>(R.id.audioListenState).setText(
            when (listenPhase) {
                ListenPhase.CONNECTING -> R.string.audio_live_listen_state_connecting
                ListenPhase.LIVE -> if (outputMuted) R.string.audio_live_listen_state_muted else R.string.audio_live_listen_state_live
                ListenPhase.RECONNECTING -> R.string.audio_live_listen_state_reconnecting
                ListenPhase.PAUSED -> R.string.audio_live_listen_state_paused
                ListenPhase.ENDED -> R.string.audio_live_listen_state_ended
            },
        )
        val mute = view.findViewById<MaterialButton>(R.id.audioListenMute)
        mute.setIconResource(if (listenMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        view.findViewById<TextView>(R.id.audioListenMuteLabel)
            .setText(if (listenMuted) R.string.audio_live_listen_unmute else R.string.audio_live_listen_mute)
        if (!live) onListenLevel(0f)
        renderListenTimer()
    }

    private fun renderListenTimer() {
        val view = listenRoot ?: return
        if (listenPhase != ListenPhase.LIVE && listenPhase != ListenPhase.PAUSED) return
        val seconds = ((SystemClock.elapsedRealtime() - listenStartedMs) / 1_000L).coerceAtLeast(0)
        view.findViewById<TextView>(R.id.audioListenTimer).text =
            if (seconds >= 3_600) "%d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60)
            else "%d:%02d".format(seconds / 60, seconds % 60)
    }

    // --- Rendering --------------------------------------------------------------------------------------------

    private val tick = object : Runnable {
        override fun run() {
            if (!mode || phase != Phase.LIVE) return
            renderTimer()
            when (reminder?.decide(SystemClock.elapsedRealtime())) {
                AudioLiveReminderPolicy.Action.REMIND -> postReminder()
                AudioLiveReminderPolicy.Action.STOP -> {
                    host.stopBroadcast(activity.getString(R.string.audio_live_reminder_stopped))
                    return
                }
                else -> Unit
            }
            main.postDelayed(this, 1_000L)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun render() {
        val view = root ?: return
        val live = phase == Phase.LIVE
        val setup = phase == Phase.SETUP
        view.findViewById<View>(R.id.audioSetupGroup).visibility = if (live) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.audioStartButton).visibility = if (live) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.audioStartButton).isEnabled = setup
        view.findViewById<View>(R.id.audioControls).visibility = if (live) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.audioSwitchToVideoButton).visibility = if (live) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.audioLiveBadge).visibility = if (live) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.audioTimer).visibility = if (live) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.audioNoteText).visibility = if (live || setup) View.VISIBLE else View.GONE
        renderQuality(view)
        val paused = host.streamPaused()
        view.findViewById<TextView>(R.id.audioStatusText).text = activity.getString(
            when {
                phase == Phase.STARTING -> R.string.audio_live_status_starting
                !live -> R.string.audio_live_status_ready
                paused -> R.string.audio_live_status_paused
                muted -> R.string.audio_live_status_muted
                listeners == 0 -> R.string.audio_live_status_waiting
                else -> R.string.audio_live_status_listeners
            },
            listeners,
        )
        val mute = view.findViewById<MaterialButton>(R.id.audioMuteButton)
        mute.setIconResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        mute.alpha = if (paused) 0.5f else 1f
        mute.isEnabled = !paused
        view.findViewById<TextView>(R.id.audioMuteLabel)
            .setText(if (muted) R.string.audio_live_unmute else R.string.audio_live_mute)
        val pause = view.findViewById<MaterialButton>(R.id.audioPauseButton)
        pause.setIconResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        view.findViewById<TextView>(R.id.audioPauseLabel)
            .setText(if (paused) R.string.live_control_resume else R.string.live_control_pause)
        if (paused) renderLevel(0f)
        renderTimer()
    }

    private fun renderTimer() {
        val view = root ?: return
        if (phase != Phase.LIVE) return
        val seconds = ((SystemClock.elapsedRealtime() - liveSinceMs) / 1_000L).coerceAtLeast(0)
        view.findViewById<TextView>(R.id.audioTimer).text =
            if (seconds >= 3_600) "%d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60)
            else "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun renderLevel(level: Float) {
        val meter = root?.findViewById<LinearProgressIndicator>(R.id.audioLevelMeter) ?: return
        meter.setProgressCompat((level * 100).toInt().coerceIn(0, 100), false)
    }

}
