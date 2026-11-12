package com.example.bluetoothtest

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log

/**
 * What an incoming call may do under the phone's ringer and Do Not Disturb settings.
 * [reason] names the deciding rule for the `BENCHMARK call_ringer` line.
 */
internal data class RingDecision(val sound: Boolean, val vibrate: Boolean, val reason: String)

/**
 * Ringer policy (bug-fix level, 2026-10-10; on for user sessions, benchmark extra
 * `test_call_ringer_policy`). Before, an incoming call always vibrated and played the default
 * ringtone at USAGE_NOTIFICATION_RINGTONE, even in silent mode, in vibrate mode with sound and under
 * Do Not Disturb. Now:
 * - Do Not Disturb ([NotificationManager.getCurrentInterruptionFilter]): NONE and ALARMS give no
 *   sound and no vibration; PRIORITY rings only when the user's DND policy lets calls from anyone
 *   through (our calls come from no system contact, so "starred" or "contacts" cannot match).
 * - Ringer mode: SILENT gives nothing, VIBRATE vibration only, NORMAL sound plus vibration when
 *   "vibrate when ringing" is on (unknown counts as on, the former behaviour).
 * Pattern from the mined call apps (Signal IncomingRinger, Meshenger initRinging), re-implemented
 * from Android API documentation only.
 */
internal object CallRingerPolicy {
    const val FILTER_UNKNOWN = 0
    const val FILTER_ALL = 1
    const val FILTER_PRIORITY = 2
    const val FILTER_NONE = 3
    const val FILTER_ALARMS = 4

    const val MODE_SILENT = 0
    const val MODE_VIBRATE = 1
    const val MODE_NORMAL = 2

    fun decide(
        ringerMode: Int,
        interruptionFilter: Int,
        priorityAllowsAnyCaller: Boolean,
        vibrateWhenRinging: Boolean?,
        hasVibrator: Boolean,
    ): RingDecision {
        when (interruptionFilter) {
            FILTER_NONE -> return RingDecision(false, false, "dnd_none")
            FILTER_ALARMS -> return RingDecision(false, false, "dnd_alarms")
            FILTER_PRIORITY -> if (!priorityAllowsAnyCaller) return RingDecision(false, false, "dnd_priority")
        }
        return when (ringerMode) {
            MODE_SILENT -> RingDecision(false, false, "ringer_silent")
            MODE_VIBRATE -> RingDecision(false, hasVibrator, "ringer_vibrate")
            else -> RingDecision(true, hasVibrator && vibrateWhenRinging != false, "ringer_normal")
        }
    }

    /** The behaviour before 2026-10-10 (benchmark launches without the extra). */
    val LEGACY = RingDecision(sound = true, vibrate = true, reason = "legacy")

    /**
     * Returns the first ringtone source that yields a value. A source that throws (some Samsung
     * phones throw SecurityException reading the default ringtone setting) or returns null moves
     * on to the next, so the call still rings.
     */
    fun <T : Any> firstAvailable(sources: List<Pair<String, () -> T?>>): Pair<String, T>? {
        for ((name, source) in sources) {
            val value = try {
                source()
            } catch (_: RuntimeException) {
                null
            } ?: continue
            return name to value
        }
        return null
    }
}

/** Where the caller's ringback plays: at the ear for a voice call, from the loudspeaker for a video call. */
internal object RingbackPolicy {
    /**
     * The audio stream for the ringing tone. Before the call's audio session exists the phone is in
     * normal mode, where the voice-call stream plays from the earpiece (the voice-call default, held to
     * the ear) and the music stream from the loudspeaker (the video-call default, held at arm's length).
     */
    fun stream(callType: CallType?): Int =
        if (callType == CallType.VOICE) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC

    /** AB3/AP3 D2: no ringback while a blocked call waits for its ordinary end (the callee's phone still rings). */
    fun plays(enabled: Boolean, phase: PrivateCallPhase?, blockedEndScheduled: Boolean): Boolean =
        enabled && phase == PrivateCallPhase.OUTGOING_RINGING && !blockedEndScheduled
}

/**
 * Incoming-call ringtone/vibration and the caller's outgoing ringback tone.
 *
 * Ringback (Settings > Features "Ringing sound while calling", promoted 2026-10-11, shipped default
 * [CallFeatureDefaults.RINGBACK]; DEBUG `test_call_ringback`): while the callee rings, the caller hears
 * the standard supervisory ringing tone (ToneGenerator.TONE_SUP_RINGTONE, on the stream
 * [RingbackPolicy] picks) instead of silence. Stops on any other phase.
 */
internal class CallRinger(private val context: Context) {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var ringback: ToneGenerator? = null

    fun startIncoming(policy: Boolean) {
        stopIncoming()
        val decision = if (policy) currentDecision() else CallRingerPolicy.LEGACY
        var source = "none"
        if (decision.sound) {
            if (policy) {
                // Each source plays its ringtone; one that throws moves on to the next.
                val found = CallRingerPolicy.firstAvailable(ringtoneSources())
                source = found?.first ?: "none"
                ringtone = found?.second
            } else {
                ringtone = runCatching { defaultRingtone()?.also(::play) }.getOrNull()
                source = if (ringtone != null) "default" else "none"
            }
        }
        val vibratorService = context.getSystemService(Vibrator::class.java)
        if (decision.vibrate && vibratorService?.hasVibrator() == true) {
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0)
            if (policy && Build.VERSION.SDK_INT >= 33) {
                vibratorService.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
            } else if (policy) {
                @Suppress("DEPRECATION")
                vibratorService.vibrate(effect, ringtoneAttributes())
            } else {
                vibratorService.vibrate(effect)
            }
            vibrator = vibratorService
        }
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK call_ringer: event=incoming | policy=$policy | sound=${decision.sound} | " +
                "vibrate=${decision.vibrate} | reason=${decision.reason} | source=$source",
        )
    }

    fun stopIncoming() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    fun startRingback(stream: Int = AudioManager.STREAM_VOICE_CALL) {
        if (ringback != null) return
        ringback = runCatching {
            ToneGenerator(stream, RINGBACK_VOLUME).also {
                it.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        }.getOrNull()
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK call_ringer: event=ringback_start | started=${ringback != null}")
    }

    fun stopRingback() {
        val tone = ringback ?: return
        ringback = null
        runCatching { tone.stopTone() }
        runCatching { tone.release() }
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK call_ringer: event=ringback_stop")
    }

    fun stopAll() {
        stopIncoming()
        stopRingback()
    }

    private fun currentDecision(): RingDecision {
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val filter = runCatching { notifications?.currentInterruptionFilter }.getOrNull()
            ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val anyCaller = filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY && runCatching {
            val policy = notifications?.notificationPolicy ?: return@runCatching false
            policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS != 0 &&
                policy.priorityCallSenders == NotificationManager.Policy.PRIORITY_SENDERS_ANY
        }.getOrDefault(false)
        val vibrateWhenRinging = runCatching {
            Settings.System.getInt(context.contentResolver, VIBRATE_WHEN_RINGING, -1)
        }.getOrNull()?.takeIf { it >= 0 }?.let { it != 0 }
        return CallRingerPolicy.decide(
            ringerMode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL,
            interruptionFilter = filter,
            priorityAllowsAnyCaller = anyCaller,
            vibrateWhenRinging = vibrateWhenRinging,
            hasVibrator = context.getSystemService(Vibrator::class.java)?.hasVibrator() == true,
        )
    }

    private fun ringtoneSources(): List<Pair<String, () -> Ringtone?>> = listOf(
        "default" to { defaultRingtone()?.also(::play) },
        "settings_default" to {
            RingtoneManager.getRingtone(context, Settings.System.DEFAULT_RINGTONE_URI)?.also(::play)
        },
        "bundled" to {
            RingtoneManager.getRingtone(
                context,
                Uri.parse("android.resource://${context.packageName}/${R.raw.call_ring_fallback}"),
            )?.also(::play)
        },
    )

    private fun defaultRingtone(): Ringtone? {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE) ?: return null
        return RingtoneManager.getRingtone(context, uri)
    }

    private fun play(tone: Ringtone) {
        tone.audioAttributes = ringtoneAttributes()
        tone.isLooping = true
        DebugTunables.playbackGain?.let { tone.volume = it }
        tone.play()
    }

    private fun ringtoneAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private companion object {
        /** Settings.System.VIBRATE_WHEN_RINGING (hidden constant name, readable without permission). */
        const val VIBRATE_WHEN_RINGING = "vibrate_when_ringing"
        const val RINGBACK_VOLUME = 80
    }
}
