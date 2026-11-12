package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Where call audio plays. A Bluetooth headset is never chosen automatically (see [callOutputFor]); [BLUETOOTH] is
 * reachable only from the output sheet and only behind [CallBluetoothOutputFeature] (default off).
 */
internal enum class CallOutput(val label: String) {
    SPEAKER("Speaker"),
    EARPIECE("Earpiece"),
    WIRED_HEADSET("Headset"),
    BLUETOOTH("Bluetooth"),
}

/** Wired or USB headsets/headphones: they use no radio, so a call may always prefer them. */
internal val WIRED_CALL_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
)

/**
 * The one call-output decision (JVM-tested). A plugged wired/USB headset replaces the earpiece, and
 * also the loudspeaker unless the user explicitly asked for the loudspeaker: on Android 10/11
 * setSpeakerphoneOn(false) already leaves the choice to the platform, which picks the headset, but on
 * Android 12+ setCommunicationDevice(earpiece) overrode a plugged headset. Bluetooth SCO/LE Audio
 * headsets are never chosen: they take airtime from the same radio that carries the call's own
 * Bluetooth media link (PLAN "Bluetooth headsets").
 */
internal fun callOutputFor(
    speakerRequested: Boolean,
    explicitSpeakerChoice: Boolean,
    wiredHeadsetPresent: Boolean,
    earpiecePresent: Boolean,
    /** The user's pick in the output sheet, honoured while that output still exists; null = the rules below. */
    explicitOutput: CallOutput? = null,
    bluetoothPresent: Boolean = false,
): CallOutput {
    when (explicitOutput) {
        CallOutput.SPEAKER -> return CallOutput.SPEAKER
        CallOutput.EARPIECE -> if (earpiecePresent) return CallOutput.EARPIECE
        CallOutput.WIRED_HEADSET -> if (wiredHeadsetPresent) return CallOutput.WIRED_HEADSET
        CallOutput.BLUETOOTH -> if (bluetoothPresent) return CallOutput.BLUETOOTH
        null -> Unit
    }
    return when {
        wiredHeadsetPresent && !(speakerRequested && explicitSpeakerChoice) -> CallOutput.WIRED_HEADSET
        speakerRequested || !earpiecePresent -> CallOutput.SPEAKER
        else -> CallOutput.EARPIECE
    }
}

/**
 * Call audio mode and route. [startOnSpeaker] false starts on the handset earpiece when the phone has
 * one (experimental voice calls); video calls keep the loudspeaker default. A plugged wired headset
 * wins at start and when it is plugged in during the call ([callOutputFor]).
 *
 * Needs MODIFY_AUDIO_SETTINGS: without it AOSP AudioService.setMode() and setSpeakerphoneOn() return
 * silently (checkAudioSettingsPermission, Android 10 through current), so before 2026-10-09 every
 * call ran in MODE_NORMAL and Android 10/11 phones (A15s, ASUS) could not leave the earpiece. The
 * `BENCHMARK call_audio_route` line records the mode the platform actually reports.
 *
 * Routing sequence (batch U-06, [CallRoutingPolicy]): focus, mode, optional mode-change wait, communication device
 * with its boolean result checked and the next candidate tried on false, listeners that re-assert the choice, no
 * watches, and a close that clears the device and restores the former mode. Every open and close is a timed,
 * serialized [AudioCommandQueue] command (U-05); `test_audio_off_main` moves close off the main thread.
 */
internal class PrivateCallAudioSession(
    private val context: Context,
    private val startOnSpeaker: Boolean = true,
    private val onRouteChanged: (() -> Unit)? = null,
    /** Experimental [CallBluetoothOutputFeature]: Bluetooth audio devices can be picked from the output sheet (API 31+). */
    private val allowBluetoothOutput: Boolean = false,
    /** Non-null: the call holds audio focus while open ([CallAudioFocus]); null: no focus request (before 2026-10-10). */
    onFocusAction: ((FocusAction) -> Unit)? = null,
    /**
     * JT2 `test_call_camera_first` (promoted 2026-11-12, default on): the `call_audio_open` command is queued on the audio command thread instead of
     * running inline on the caller's thread; [onRouteChanged] is told when it finished, and [CallAudioOpenGate] lets the remote
     * audio playback wait for it.
     */
    private val openQueued: Boolean = false,
) : Closeable {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    // Read inside the serialized open command: a queued close of the previous call must finish first, or its
    // not yet restored MODE_IN_COMMUNICATION would be remembered as the mode to restore.
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone = false
    @Volatile private var closed = false
    private var lastSpeakerRequested = startOnSpeaker
    private val reassertLimiter = RouteReassertLimiter()

    /** True while call audio plays from the loudspeaker (the default), false for earpiece/headset. */
    @Volatile var speakerOn = true
        private set

    @Volatile var output = CallOutput.SPEAKER
        private set

    private var explicitSpeakerChoice = false

    /** The user's pick in the output sheet; cleared when they use the Speaker toggle or a headset is plugged in. */
    private var explicitOutput: CallOutput? = null
    private var explicitDeviceId = CallOutputOption.NO_DEVICE

    /** Phones without a handset earpiece (for example tablets) keep the loudspeaker. */
    val earpieceAvailable: Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }

    /** Whether the route button can leave the loudspeaker (earpiece, a plugged headset, or an allowed Bluetooth device). */
    val quietRouteAvailable: Boolean
        get() = earpieceAvailable || wiredHeadsetPresent() || bluetoothOutputPresent()

    /** The rows of the output sheet and the Speaker button's mode ([CallOutputChoices]). */
    fun outputOptions(): List<CallOutputOption> = CallOutputChoices.options(outputSinks(), bluetoothOutputAllowed())

    /** Routes call audio to a row of the output sheet. Returns true when that output is now in use. */
    fun selectOutput(option: CallOutputOption): Boolean {
        explicitOutput = option.output
        explicitDeviceId = option.deviceId
        applyRoute(speakerRequested = option.output == CallOutput.SPEAKER, explicit = true, event = "sheet")
        return output == option.output
    }

    /**
     * Earpiece calls only: while call audio plays from the earpiece the phone is at the ear, so the
     * proximity sensor turns the screen off and a cheek cannot press End or push-to-talk. Held for
     * exactly as long as the earpiece route is active; released on speaker/headset and in [close].
     */
    private val proximity = ProximityScreenOff(PowerManagerProximityLock.create(context))

    private val audioFocus = onFocusAction?.let { CallAudioFocus(context, it) }

    /** Root hook: the call's foreground service holds its types now; a refused focus request is retried at once. */
    fun onForegroundHeld() {
        audioFocus?.onForegroundHeld()
    }

    private var communicationDeviceListener: AudioManager.OnCommunicationDeviceChangedListener? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (output != CallOutput.WIRED_HEADSET &&
                addedDevices.any { it.isSink && it.type in WIRED_CALL_OUTPUT_TYPES }
            ) {
                explicitSpeakerChoice = false
                explicitOutput = null
                applyRoute(speakerRequested = false, explicit = false, event = "headset_added")
                onRouteChanged?.invoke()
            } else if (addedDevices.any { it.isSink }) {
                // Another output appeared (for example a Bluetooth device): the route is unchanged, the button may differ.
                onRouteChanged?.invoke()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (output == CallOutput.WIRED_HEADSET &&
                removedDevices.any { it.isSink && it.type in WIRED_CALL_OUTPUT_TYPES } &&
                !wiredHeadsetPresent()
            ) {
                explicitOutput = null
                applyRoute(speakerRequested = startOnSpeaker, explicit = false, event = "headset_removed")
                onRouteChanged?.invoke()
            } else if (output == CallOutput.BLUETOOTH &&
                removedDevices.any { it.isSink && it.type in BLUETOOTH_CALL_OUTPUT_TYPES } &&
                !bluetoothOutputPresent()
            ) {
                explicitOutput = null
                applyRoute(speakerRequested = startOnSpeaker, explicit = false, event = "bluetooth_removed")
                onRouteChanged?.invoke()
            } else if (removedDevices.any { it.isSink }) {
                onRouteChanged?.invoke()
            }
        }
    }

    /**
     * The device this session last asked for. Reading AudioManager.communicationDevice right after
     * setCommunicationDevice blocks the calling (main) thread until the route change completes: 288-453 ms on the S22
     * (Android 16) at every call start, measured with Perfetto. The listener logs the device actually in use.
     * (Declared before [init]: a queued open can run on the audio thread while the constructor is still finishing.)
     */
    @Volatile private var loggedDeviceType: Int? = null

    init {
        val open = {
            previousMode = audioManager.mode
            previousSpeakerphone = speakerphoneNow()
            // Signal's order: focus first (a refusal is classified by CallFocusArbiter), then the mode.
            audioFocus?.request()
            setCallMode()
            applyRoute(speakerRequested = startOnSpeaker, explicit = false, event = "start")
            // The callback's initial report lists devices already routed above; it changes nothing then.
            runCatching { audioManager.registerAudioDeviceCallback(deviceCallback, null) }
            registerCommunicationDeviceListener()
        }
        if (openQueued) {
            val opened = CallAudioOpenGate.shared.expect()
            AudioCommandQueue.shared.post("call_audio_open") {
                try {
                    // A close that ran first (the call ended before this command did) leaves the audio mode alone.
                    if (!closed) {
                        open()
                        onRouteChanged?.invoke()
                    }
                } finally {
                    opened.countDown()
                }
            }
        } else {
            AudioCommandQueue.shared.run("call_audio_open", open)
        }
    }

    @Suppress("DEPRECATION")
    private fun speakerphoneNow(): Boolean = audioManager.isSpeakerphoneOn

    /** MODE_IN_COMMUNICATION (or the Experimental USB rule), optionally waiting for the platform's mode callback. */
    @SuppressLint("NewApi")
    private fun setCallMode() {
        val usb = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        val target = CallRoutingPolicy.callMode(usb, DeviceTraits.current().oemSkin, AudioSwitches.usbHeadsetModeNormal, startOnSpeaker)
        val waitForCallback = AudioSwitches.modeChangeWait && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            audioManager.mode != target
        if (!waitForCallback) {
            audioManager.mode = target
            return
        }
        val changed = CountDownLatch(1)
        val listener = AudioManager.OnModeChangedListener { mode -> if (mode == target) changed.countDown() }
        val executor = java.util.concurrent.Executor { it.run() }
        runCatching { audioManager.addOnModeChangedListener(executor, listener) }
        val startedAt = SystemClock.elapsedRealtime()
        audioManager.mode = target
        val seen = runCatching { changed.await(CallRoutingPolicy.MODE_CHANGE_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        runCatching { audioManager.removeOnModeChangedListener(listener) }
        Log.i(
            "BluetoothVideo",
            "BENCHMARK call_audio_mode: target=$target | callback=$seen | waited_ms=${SystemClock.elapsedRealtime() - startedAt}",
        )
    }

    /** API 31+: re-assert the call's communication device when the platform or another app changes it (rate limited). */
    @SuppressLint("NewApi")
    private fun registerCommunicationDeviceListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val listener = AudioManager.OnCommunicationDeviceChangedListener { device -> onCommunicationDeviceChanged(device) }
        communicationDeviceListener = listener
        runCatching { audioManager.addOnCommunicationDeviceChangedListener(context.mainExecutor, listener) }
    }

    private fun onCommunicationDeviceChanged(device: AudioDeviceInfo?) {
        // The device actually in use, from the platform's own callback (no blocking read).
        if (!closed) Log.i("BluetoothVideo", "BENCHMARK call_audio_route: event=platform_device | communicationDevice=${device?.type ?: "none"} | output=${output.name}")
        if (closed || !AudioSwitches.routeReassert || !AudioSwitches.policyActive(ROUTE_ENTRY_ID)) return
        if (!CallRoutingPolicy.needsReassert(device?.type, output)) return
        if (!reassertLimiter.allow(SystemClock.elapsedRealtime())) {
            Log.i("BluetoothVideo", "BENCHMARK call_audio_route: event=reassert_skipped | reason=rate_limit | platformDevice=${device?.type ?: "none"} | output=${output.name}")
            return
        }
        applyRoute(speakerRequested = lastSpeakerRequested, explicit = false, event = "reassert")
        onRouteChanged?.invoke()
    }

    private fun wiredHeadsetPresent(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in WIRED_CALL_OUTPUT_TYPES }

    /** Bluetooth call audio is selectable on Android 12+ (setCommunicationDevice) and only behind the experimental switch. */
    private fun bluetoothOutputAllowed(): Boolean = allowBluetoothOutput && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private fun bluetoothOutputPresent(): Boolean = bluetoothOutputAllowed() &&
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in BLUETOOTH_CALL_OUTPUT_TYPES }

    private fun outputSinks(): List<AudioSink> = CallRoutingPolicy.withoutWatches(
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { AudioSink(it.type, it.productName?.toString().orEmpty(), it.id) },
    )

    /**
     * Routes call audio to the loudspeaker, or to the quiet route (a plugged headset, else the
     * handset earpiece). The earpiece is the quiet choice: Android's lowest voice-call volume step is
     * still loud on the speaker. [explicit] marks the user's own choice (a layout change is not).
     * Returns true while the loudspeaker is in use.
     */
    fun setSpeakerOn(speaker: Boolean, explicit: Boolean = true): Boolean {
        if (!speaker && !quietRouteAvailable) return speakerOn
        if (explicit) explicitOutput = null // the toggle replaces an earlier pick from the sheet
        applyRoute(speaker, explicit, if (explicit) "user" else "layout")
        return speakerOn
    }

    @SuppressLint("MissingPermission", "NewApi")
    @Suppress("DEPRECATION")
    private fun applyRoute(speakerRequested: Boolean, explicit: Boolean, event: String) {
        if (explicit) explicitSpeakerChoice = speakerRequested
        lastSpeakerRequested = speakerRequested
        val wanted = callOutputFor(
            speakerRequested,
            explicitSpeakerChoice,
            wiredHeadsetPresent(),
            earpieceAvailable,
            explicitOutput,
            bluetoothOutputPresent(),
        )
        val applied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val checkResult = AudioSwitches.policyActive(ROUTE_ENTRY_ID)
            val ordered = if (checkResult) {
                // Watches are never candidates; every device of the wanted kind is tried until one is accepted.
                val byId = devices.associateBy { it.id }
                CallRoutingPolicy.candidatesFor(
                    wanted,
                    devices.map { CallRoutingPolicy.Candidate(it.type, it.productName?.toString().orEmpty(), it.id) },
                    explicitDeviceId,
                ).mapNotNull { byId[it.id] }
            } else {
                // Former rule (registry entry off): the first device of the wanted kind, once.
                listOfNotNull(
                    when (wanted) {
                        CallOutput.SPEAKER -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        CallOutput.EARPIECE -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                        CallOutput.WIRED_HEADSET -> devices.firstOrNull { it.type in WIRED_CALL_OUTPUT_TYPES }
                        CallOutput.BLUETOOTH -> devices.firstOrNull {
                            it.type in BLUETOOTH_CALL_OUTPUT_TYPES && (explicitDeviceId == CallOutputOption.NO_DEVICE || it.id == explicitDeviceId)
                        } ?: devices.firstOrNull { it.type in BLUETOOTH_CALL_OUTPUT_TYPES }
                    },
                ).take(1)
            }
            val accepted = CallRoutingPolicy.pickFirstAccepted(ordered) { audioManager.setCommunicationDevice(it) }
            loggedDeviceType = accepted?.first?.type
            if (accepted == null || accepted.second > 0) {
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK call_audio_route: event=device_set | wanted=${wanted.name} | candidates=${ordered.size} | " +
                        "rejected=${accepted?.second ?: ordered.size} | accepted=${accepted != null}",
                )
            }
            if (accepted != null) wanted else currentOutputS()
        } else {
            // FORCE_SPEAKER, or FORCE_NONE, where the platform prefers a wired headset to the earpiece.
            audioManager.isSpeakerphoneOn = wanted == CallOutput.SPEAKER
            when {
                audioManager.isSpeakerphoneOn -> CallOutput.SPEAKER
                wanted == CallOutput.SPEAKER -> if (wiredHeadsetPresent()) CallOutput.WIRED_HEADSET else CallOutput.EARPIECE
                wanted == CallOutput.BLUETOOTH -> currentOutputPreS()
                else -> wanted
            }
        }
        output = applied
        speakerOn = applied == CallOutput.SPEAKER
        proximity.onSpeakerChanged(applied != CallOutput.EARPIECE)
        logRoute(event, wanted)
    }

    @SuppressLint("NewApi")
    private fun currentOutputS(): CallOutput {
        val type = audioManager.communicationDevice?.type ?: return CallOutput.SPEAKER
        return when {
            type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> CallOutput.EARPIECE
            type in WIRED_CALL_OUTPUT_TYPES -> CallOutput.WIRED_HEADSET
            type in BLUETOOTH_CALL_OUTPUT_TYPES -> CallOutput.BLUETOOTH
            else -> CallOutput.SPEAKER
        }
    }

    /** Before Android 12 a Bluetooth pick is not supported (the option is never listed); keep what the platform reports. */
    @Suppress("DEPRECATION")
    private fun currentOutputPreS(): CallOutput =
        if (audioManager.isSpeakerphoneOn) CallOutput.SPEAKER else CallOutput.EARPIECE

    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun logRoute(event: String, requested: CallOutput) {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            loggedDeviceType?.let { "requested:$it" } ?: "none"
        } else {
            "n/a"
        }
        Log.i(
            "BluetoothVideo",
            "BENCHMARK call_audio_route: event=$event | requested=${requested.name} | output=${output.name} | " +
                "mode=${audioManager.mode} | modeInCommunication=${audioManager.mode == AudioManager.MODE_IN_COMMUNICATION} | " +
                "communicationDevice=$device | speakerphone=${audioManager.isSpeakerphoneOn} | sdk=${Build.VERSION.SDK_INT}",
        )
    }

    /**
     * Stops listening, then (as one serialized, timed audio command) abandons focus, clears the communication
     * device and restores the former speakerphone state and mode. `test_audio_off_main` runs that command on the
     * audio command thread instead of the caller's; the proximity lock is released at once either way.
     */
    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    override fun close() {
        if (closed) return
        closed = true
        proximity.close()
        AudioCommandQueue.shared.offload("call_audio_close") {
            runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                communicationDeviceListener?.let { runCatching { audioManager.removeOnCommunicationDeviceChangedListener(it) } }
            }
            audioFocus?.abandon()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
            audioManager.isSpeakerphoneOn = previousSpeakerphone
            audioManager.mode = previousMode
        }
    }

    private companion object {
        /** Registry entry (AudioPolicyEntries) for the boolean-result check, re-pick and re-assert. */
        const val ROUTE_ENTRY_ID = "call-route-set-result-and-reassert"
    }
}

/** A proximity wake lock the call can hold and release; separated so the policy is JVM-testable. */
internal interface ProximityLock {
    val held: Boolean
    fun acquire()
    fun release()
}

/**
 * Holds the proximity lock while call audio is on the earpiece and nowhere else. A null lock (the
 * phone has no proximity sensor, or the platform refuses the lock) leaves the screen behaviour
 * exactly as before.
 */
internal class ProximityScreenOff(private val lock: ProximityLock?) {
    /** True while the screen-off lock is held. */
    val active: Boolean get() = lock?.held == true

    @Synchronized
    fun onSpeakerChanged(speakerOn: Boolean) {
        val lock = lock ?: return
        if (speakerOn) {
            if (lock.held) lock.release()
        } else if (!lock.held) {
            lock.acquire()
        }
    }

    @Synchronized
    fun close() {
        val lock = lock ?: return
        if (lock.held) lock.release()
    }
}

/** PROXIMITY_SCREEN_OFF_WAKE_LOCK, only where this phone supports it. */
internal class PowerManagerProximityLock private constructor(
    private val wakeLock: PowerManager.WakeLock,
) : ProximityLock {
    override val held: Boolean get() = wakeLock.isHeld

    @SuppressLint("WakelockTimeout")
    override fun acquire() {
        runCatching {
            wakeLock.acquire()
            Log.i("BluetoothVideo", "Call proximity screen-off lock: held")
        }.onFailure { Log.w("BluetoothVideo", "Call proximity screen-off lock could not be held", it) }
    }

    override fun release() {
        runCatching {
            wakeLock.release()
            Log.i("BluetoothVideo", "Call proximity screen-off lock: released")
        }
    }

    companion object {
        fun create(context: Context): ProximityLock? {
            val power = context.getSystemService(PowerManager::class.java) ?: return null
            if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return null
            val wakeLock = power.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "BluetoothTest:CallProximity",
            ).apply { setReferenceCounted(false) }
            return PowerManagerProximityLock(wakeLock)
        }
    }
}
