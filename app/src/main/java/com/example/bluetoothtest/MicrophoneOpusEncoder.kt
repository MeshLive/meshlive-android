package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.SensorPrivacyManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Optional platform facts and callbacks for [MicrophoneOpusEncoder] (batch U root hook: MainActivity passes these in
 * `startPrivateCallMedia`). Every default keeps the encoder's former behaviour.
 */
class AudioCaptureHooks(
    /** Lets the encoder ask SensorPrivacyManager whether the phone has a microphone privacy toggle. */
    val context: Context? = null,
    /** True while call audio plays from the loudspeaker (for the one-time echo hint). */
    val routeIsLoudspeaker: () -> Boolean = { false },
    /** A user-visible notice (toast or banner); also sent through the encoder's status callback. */
    val onNotice: (AudioCaptureNotice) -> Unit = {},
)

class MicrophoneOpusEncoder(
    private val onPacket: (flags: Int, timeUs: Long, bytes: ByteArray) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val communicationMode: Boolean = false,
    private val bitRate: Int = StreamProtocol.AUDIO_BIT_RATE,
    private val debugReferenceFile: File? = null,
    private val debugReferenceVideoFrames: Long = 0,
    private val debugReferenceVideoFps: Int = 0,
    private val debugReferenceClock: () -> RawReferenceClock? = { null },
    private val debugTraceContext: Context? = null,
    private val debugTraceSession: String? = null,
    private val hooks: AudioCaptureHooks = AudioCaptureHooks(),
) : Closeable {
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var nativeEncoder: NativeOpusEncoder? = null
    @Volatile private var captureWorker: Thread? = null
    @Volatile private var encoderWorker: Thread? = null
    private val pcmFrames = ArrayBlockingQueue<PcmFrame>(PCM_QUEUE_CAPACITY)
    private val audioEffects = mutableListOf<AudioEffect>()
    private var effectSummary = ""
    private var referenceInput: ReferencePcmInput? = null
    private var debugAudioTrace: DebugAudioTrace? = null
    private val effectOutcomes = mutableListOf<EffectOutcome>()
    private var effectDetail = ""
    @Volatile private var captureSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    private var captureBufferBytes = 0
    @Volatile private var startAttempts = 0
    @Volatile private var clientSilenced: Boolean? = null
    @Volatile private var aecInFramework: Boolean? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var callbackRecorder: AudioRecord? = null
    private val noticeLimiter = NoticeLimiter()
    private val callbackExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MicRecordingCallback").apply { isDaemon = true }
    }

    private data class PcmFrame(
        val bytes: ByteArray,
        val presentationTimeUs: Long,
    )

    @SuppressLint("MissingPermission")
    fun start() {
        check(running.compareAndSet(false, true)) { "Microphone encoder is already running" }
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            StreamProtocol.AUDIO_SAMPLE_RATE,
            StreamProtocol.AUDIO_CHANNEL_COUNT,
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_BYTES_PER_FRAME)
            // AOSP c2.android.opus.encoder defaults to 10, but media_codecs_google_c2_audio.xml
            // advertises default 5; libopus below 7 skips its speech/music analysis and costs about
            // 0.4-0.6 PESQ-WB on speech-v1 at 24-32 kbps (offline sweep 2026-10-09). Ask for 10.
            setInteger(MediaFormat.KEY_COMPLEXITY, OPUS_COMPLEXITY)
        }
        try {
            check(debugReferenceFile == null || BuildConfig.DEBUG) { "Reference audio is debug-only" }
            referenceInput = debugReferenceFile?.let { file ->
                ReferencePcmInput(file).also { input ->
                    try { input.requireVideoTimeline(debugReferenceVideoFrames, debugReferenceVideoFps) }
                    catch (error: Throwable) { input.close(); throw error }
                }
            }
            var activeNative = if (communicationMode && DebugTunables.callNativeOpus) startNativeEncoder() else null
            var encoderName = ""
            var activeCodec: MediaCodec? = null
            if (activeNative == null) {
                val created = runCatching { createMediaCodecOpusEncoder(format) }
                if (created.isSuccess) {
                    val (name, codecInstance) = created.getOrThrow()
                    encoderName = name
                    activeCodec = codecInstance
                    codec = codecInstance
                    if (communicationMode) {
                        Log.i(
                            "BluetoothVideo",
                            "BENCHMARK audio_codec: role=encoder | impl=mediacodec | " +
                                "requested=${if (DebugTunables.callNativeOpus) "native" else "mediacodec"} | " +
                                "name=$name | fec=off | plc=n/a | kbps=${bitRate / 1_000}",
                        )
                    }
                } else {
                    // U-08: a phone without a working MediaCodec Opus encoder (CDD makes it mandatory, so rare)
                    // falls back to the bundled libopus instead of ending the call or stream audio.
                    val failure = created.exceptionOrNull() ?: IllegalStateException("No Opus audio encoder")
                    activeNative = if (AudioSwitches.policyActive(OPUS_ENCODER_FALLBACK_ID)) {
                        startNativeEncoder(requested = "mediacodec", reason = "mediacodec_unavailable:${failure.message}")
                    } else {
                        null
                    }
                    if (activeNative == null) throw failure
                }
            }
            nativeEncoder = activeNative
            val nativeActive = activeNative
            val codecActive = activeCodec
            if (nativeActive != null) encoderName = "libopus ${NativeOpusBridge.nativeOpusVersion()}"
            val startEncoding: () -> Unit = {
                if (nativeActive != null) nativeEncodeLoop(nativeActive) else encodeLoop(checkNotNull(codecActive))
            }

            debugAudioTrace = DebugAudioTrace.startIfRequested(debugTraceContext, debugTraceSession, "sender")
            referenceInput?.let { input ->
                onStatus("BENCHMARK reference audio: ${debugReferenceFile?.name}, ${input.sampleCount} samples, PCM16 mono 48000 Hz through $encoderName at ${bitRate / 1_000} kbps; microphone/acoustics unmeasured")
                captureWorker = thread(name = "ReferencePcmCapture") { referenceCaptureLoop(input) }
                encoderWorker = thread(name = "ReferenceOpusEncoder") { startEncoding() }
                return
            }

            val minimumBuffer = AudioRecord.getMinBufferSize(
                StreamProtocol.AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            MinBufferCheck.captureProblem(minimumBuffer)?.let { error(it) }
            captureBufferBytes = maxOf(minimumBuffer, PCM_BYTES_PER_FRAME * 4)
            val source = CaptureSourceChain.forMode(communicationMode).first()
            val activeRecorder = buildRecorder(source)
            captureSource = source
            recorder = activeRecorder
            registerRecordingCallback(activeRecorder)
            if (communicationMode) enableCallAudioEffects(activeRecorder.audioSessionId)
            val firstStartOk = startRecordingOnce(activeRecorder, attempt = 1)
            val retryEnabled = AudioSwitches.policyActive(CAPTURE_WATCHDOG_ENTRY_ID)
            if (!firstStartOk && !retryEnabled) error("Microphone did not start recording")
            // U-04: a failed first start is retried on the capture thread (two more attempts, 250 ms apart) so the
            // caller (the UI thread in a call) never sleeps; the call keeps going and the encoder is ready.
            val announce: () -> Unit = { announceCapture(encoderName, codecActive) }
            if (firstStartOk) announce()

            captureWorker = thread(name = "MicrophoneCapture") {
                captureLoop(activeRecorder, retryStart = !firstStartOk, announce = announce)
            }
            encoderWorker = thread(name = "MicrophoneOpusEncoder") {
                startEncoding()
            }
        } catch (error: Exception) {
            running.set(false)
            releaseResources()
            throw error
        }
    }

    /**
     * [start] on the audio command thread when `test_audio_off_main` is on and the caller is the main thread,
     * otherwise inline. [onResult] gets the failure or null; with the switch on it runs on the audio command
     * thread, so a UI caller must hop to its own thread.
     */
    fun startOffMain(onResult: (Throwable?) -> Unit) {
        AudioCommandQueue.shared.offloadForResult("mic_encoder_start", { start() }) { result ->
            onResult(result.exceptionOrNull())
        }
    }

    /**
     * Always queued behind the commands already on the audio command thread, whatever the off-main switch says (JT2 camera-first:
     * after the call's queued `call_audio_open`, so the capture opens in the call's audio mode). [onResult] runs on that thread.
     */
    fun startQueued(onResult: (Throwable?) -> Unit) {
        AudioCommandQueue.shared.postForResult("mic_encoder_start", { start() }) { result ->
            onResult(result.exceptionOrNull())
        }
    }

    private fun createMediaCodecOpusEncoder(format: MediaFormat): Pair<String, MediaCodec> {
        val encoderName = MediaCodecList(MediaCodecList.ALL_CODECS).findEncoderForFormat(format)
            ?: error("This phone has no Opus audio encoder")
        val created = MediaCodec.createByCodecName(encoderName)
        try {
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
        } catch (error: Exception) {
            runCatching { created.release() }
            throw error
        }
        return encoderName to created
    }

    @SuppressLint("MissingPermission")
    private fun buildRecorder(source: Int): AudioRecord {
        val built = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(StreamProtocol.AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(captureBufferBytes)
            .build()
        if (built.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { built.release() }
            error("Microphone could not be initialized")
        }
        return built
    }

    /** One start attempt: stop a half-started recorder, start, and report whether it records. Never throws. */
    private fun startRecordingOnce(target: AudioRecord, attempt: Int): Boolean {
        if (attempt > 1) runCatching { target.stop() }
        val failure = runCatching { target.startRecording() }.exceptionOrNull()
        val recording = failure == null && target.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (attempt > 1 || !recording) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK audio_capture_start: attempt=$attempt | recording=$recording | source=${CaptureSourceChain.label(captureSource)} | " +
                    "error=${failure?.let { it.javaClass.simpleName + ":" + it.message } ?: "none"}",
            )
        }
        startAttempts = attempt
        return recording
    }

    private fun announceCapture(encoderName: String, activeCodec: MediaCodec?) {
        onStatus(
            "Microphone audio: Opus mono at ${bitRate / 1_000} kbps " +
                "via $encoderName" + if (communicationMode) " with call echo control" else "",
        )
        Log.i(
            "BluetoothVideo",
            "BENCHMARK audio_capture: source=${CaptureSourceChain.label(captureSource)} | " +
                "encoder=$encoderName | kbps=${bitRate / 1_000} | " +
                "complexity=${activeCodec?.let(::encoderComplexity) ?: OPUS_COMPLEXITY.toString()} | " +
                "effects=${effectSummary.ifEmpty { "none-requested" }} | " +
                "effectState=${effectDetail.ifEmpty { "none-requested" }} | startAttempts=$startAttempts",
        )
    }

    /** The recording callback tells whether the platform silenced this client and which effects it really applies. */
    @SuppressLint("NewApi")
    private fun registerRecordingCallback(target: AudioRecord) {
        unregisterRecordingCallback()
        val sessionId = target.audioSessionId
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == sessionId } ?: return
                val silenced = mine.isClientSilenced
                val aec = (mine.effects.orEmpty() + mine.clientEffects.orEmpty()).any { it.type == AudioEffect.EFFECT_TYPE_AEC }
                val before = clientSilenced
                clientSilenced = silenced
                aecInFramework = aec
                if (before != silenced) {
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK audio_capture_state: source=${CaptureSourceChain.label(captureSource)} | " +
                            "clientSilenced=${before ?: "?"}->$silenced | aecInFramework=$aec",
                    )
                }
            }
        }
        val registered = runCatching { target.registerAudioRecordingCallback(callbackExecutor, callback) }.isSuccess
        if (registered) {
            recordingCallback = callback
            callbackRecorder = target
        }
    }

    private fun unregisterRecordingCallback() {
        val callback = recordingCallback ?: return
        runCatching { callbackRecorder?.unregisterAudioRecordingCallback(callback) }
        recordingCallback = null
        callbackRecorder = null
    }

    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun micToggleSupported(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@runCatching false
        val context = hooks.context ?: return@runCatching false
        context.getSystemService(SensorPrivacyManager::class.java)
            ?.supportsSensorToggle(SensorPrivacyManager.Sensors.MICROPHONE) == true
    }.getOrDefault(false)

    private fun emitNotice(notice: AudioCaptureNotice) {
        if (!noticeLimiter.allow(notice, SystemClock.elapsedRealtime())) return
        Log.i("BluetoothVideo", "BENCHMARK audio_capture_notice: notice=${notice.name}")
        runCatching { hooks.onNotice(notice) }
        runCatching { onStatus(notice.text) }
    }

    /** One-time echo hint, evaluated about one second after the start when the platform's effect report has arrived. */
    private fun checkEchoRisk() {
        if (!communicationMode || !AudioSwitches.policyActive(ECHO_HINT_ENTRY_ID)) return
        val aec = effectOutcomes.firstOrNull { it.name == "aec" }
        val aecEnabledByApp = aec?.let { it.created && it.finalEnabled == true }
        val loudspeaker = runCatching { hooks.routeIsLoudspeaker() }.getOrDefault(false)
        val risk = EchoRisk.atRisk(true, aecEnabledByApp, aecInFramework, loudspeaker)
        Log.i(
            "BluetoothVideo",
            "BENCHMARK audio_echo_risk: risk=$risk | aecEnabledByApp=${aecEnabledByApp ?: "?"} | " +
                "aecInFramework=${aecInFramework ?: "?"} | loudspeaker=$loudspeaker",
        )
        if (risk) emitNotice(AudioCaptureNotice.ECHO_RISK)
    }

    /**
     * Digital silence for two seconds: classify, tell the user, and (Experimental, `test_capture_source_fallback`)
     * relax the source one step. Returns the recorder to read from next.
     */
    private fun onCaptureSilence(current: AudioRecord): AudioRecord {
        val cause = SilenceClassifier.classify(clientSilenced)
        val toggle = micToggleSupported()
        val evidence = AudioSwitches.captureSilenceEvidence
        val notice = SilenceClassifier.episodeNotice(cause, toggle, evidence)
        Log.i(
            "BluetoothVideo",
            "BENCHMARK audio_capture_silence: cause=${cause.name} | source=${CaptureSourceChain.label(captureSource)} | " +
                "clientSilenced=${clientSilenced ?: "?"} | micToggleSupported=$toggle | " +
                "sourceFallback=${AudioSwitches.captureSourceFallback} | notice=${notice?.name ?: "awaiting_evidence"}",
        )
        notice?.let(::emitNotice)
        if (evidence) return current
        return relaxSource(current, cause, "digital_silence_${CaptureSilenceDetector.THRESHOLD_MS}ms")
    }

    /** F18: a blank capture confirmed by [BlankCaptureEvidence]; the platform's own silencing was told at the episode. */
    private fun onBlankConfirmed(current: AudioRecord, verdict: BlankCaptureEvidence.Verdict, zeroMs: Long): AudioRecord {
        val cause = SilenceClassifier.classify(clientSilenced)
        Log.i(
            "BluetoothVideo",
            "BENCHMARK audio_capture_blank: evidence=${verdict.name.lowercase()} | zeroMs=$zeroMs | cause=${cause.name} | " +
                "source=${CaptureSourceChain.label(captureSource)} | clientSilenced=${clientSilenced ?: "?"}",
        )
        if (cause != SilenceCause.BLANK_CAPTURE) return current
        emitNotice(AudioCaptureNotice.CAPTURE_SILENT)
        return relaxSource(current, cause, "blank_${verdict.name.lowercase()}_${zeroMs}ms")
    }

    private fun relaxSource(current: AudioRecord, cause: SilenceCause, reason: String): AudioRecord {
        if (!SilenceClassifier.mayChangeSource(cause) || !AudioSwitches.captureSourceFallback) return current
        val next = CaptureSourceChain.next(communicationMode, captureSource) ?: return current
        return switchSource(current, next, reason) ?: current
    }

    /**
     * Rebuilds the AudioRecord on [source] (a relaxed source, or the same one after ERROR_DEAD_OBJECT). Effects are
     * only attached to VOICE_COMMUNICATION. Returns the new recorder, or null when it could not be built (the old
     * one then stays in place).
     */
    private fun switchSource(old: AudioRecord, source: Int, reason: String): AudioRecord? {
        val requested = CaptureSourceChain.label(captureSource)
        val next = runCatching { buildRecorder(source) }.getOrElse { error ->
            Log.i("BluetoothVideo", "BENCHMARK audio_capture_fallback: requested=$requested | effective=$requested | reason=$reason | failed=${error.message}")
            return null
        }
        recorder = next
        captureSource = source
        unregisterRecordingCallback()
        audioEffects.forEach { effect -> runCatching { effect.release() } }
        audioEffects.clear()
        runCatching { old.stop() }
        runCatching { old.release() }
        registerRecordingCallback(next)
        if (communicationMode && source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
            enableCallAudioEffects(next.audioSessionId)
        } else {
            effectSummary = "none-source-${CaptureSourceChain.label(source)}"
            effectDetail = ""
            effectOutcomes.clear()
        }
        val started = startRecordingOnce(next, attempt = 2)
        Log.i(
            "BluetoothVideo",
            "BENCHMARK audio_capture_fallback: requested=${CaptureSourceChain.label(CaptureSourceChain.forMode(communicationMode).first())} | " +
                "effective=${CaptureSourceChain.label(source)} | reason=$reason | recording=$started | effects=$effectSummary",
        )
        if (started && source != CaptureSourceChain.forMode(communicationMode).first()) emitNotice(AudioCaptureNotice.SOURCE_FALLBACK)
        // A recorder that did not start fails its first read and ends the call with the usual microphone error.
        return next
    }

    private fun referenceCaptureLoop(input: ReferencePcmInput) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        try {
            var origin: RawReferenceClock? = null
            while (running.get() && origin == null) {
                origin = debugReferenceClock()
                if (origin == null) Thread.sleep(1)
            }
            val clock = origin ?: return
            val originUs = clock.ptsOriginUs
            Log.i("BluetoothVideo", "BENCHMARK reference audio clock: origin_us=$originUs monotonic_origin_ns=${clock.monotonicOriginNs}")
            var observedVideoOrigin = originUs
            var samples = 0L
            while (running.get()) {
                debugReferenceClock()?.let { videoClock ->
                    if (videoClock.ptsOriginUs != observedVideoOrigin) {
                        observedVideoOrigin = videoClock.ptsOriginUs
                        Log.i("BluetoothVideo", "BENCHMARK reference audio independent video epoch: audio_origin_us=$originUs video_origin_us=$observedVideoOrigin; A/V reference pairing requires an explicit phase mapping")
                    }
                }
                val nowNs = System.nanoTime()
                val dueSamples = clock.duePacketSample(nowNs)
                if (dueSamples > samples) {
                    Log.i("BluetoothVideo", "BENCHMARK reference audio input gap: first_sample=$samples end_sample=$dueSamples origin_us=$originUs")
                    samples = dueSamples
                    input.seekSample(samples)
                }
                val ptsUs = originUs + samples * 1_000_000L / StreamProtocol.AUDIO_SAMPLE_RATE
                val remainingNs = clock.packetDeadlineNs(samples) - nowNs
                if (remainingNs > 0) {
                    java.util.concurrent.locks.LockSupport.parkNanos(minOf(remainingNs, 20_000_000L))
                    continue
                }
                val bytes = ByteArray(PCM_BYTES_PER_FRAME)
                input.readPacket(bytes)
                debugAudioTrace?.record("reference_input", ptsUs, bytes)
                val frame = PcmFrame(bytes, ptsUs)
                if (!pcmFrames.offer(frame)) {
                    pcmFrames.poll()?.let { Log.i("BluetoothVideo", "BENCHMARK reference audio queue drop: dropped_pts_us=${it.presentationTimeUs}") }
                    if (!pcmFrames.offer(frame)) Log.i("BluetoothVideo", "BENCHMARK reference audio queue rejected: pts_us=$ptsUs sample=$samples")
                }
                samples += SAMPLES_PER_FRAME
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        catch (error: Exception) { if (running.get()) fail(error) }
        finally { captureWorker = null }
    }

    private fun captureLoop(initialRecorder: AudioRecord, retryStart: Boolean, announce: () -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var current = initialRecorder
        var firstPresentationTimeUs = 0L
        var capturedSamples = 0L
        val captureTimestamp = AudioTimestamp()
        var captureTimestampLogged = false
        var captureTimestampAttempts = 0
        val watchdogOn = AudioSwitches.policyActive(CAPTURE_WATCHDOG_ENTRY_ID)
        val silence = CaptureSilenceDetector(FRAME_DURATION_US / 1_000L)
        val blank = BlankCaptureEvidence(FRAME_DURATION_US / 1_000L)
        var deadObjectRebuilds = 0
        var frames = 0L
        try {
            if (retryStart) {
                val started = CaptureStartRetry().runFrom(2, { running.get() }) { attempt ->
                    startRecordingOnce(current, attempt)
                }
                if (started == null) {
                    if (running.get()) error("Microphone did not start recording after ${CaptureStartRetry.ATTEMPTS} attempts")
                    return
                }
                announce()
            }
            while (running.get()) {
                val bytes = ByteArray(PCM_BYTES_PER_FRAME)
                var frameBytes = 0
                while (running.get() && frameBytes < PCM_BYTES_PER_FRAME) {
                    val bytesRead = current.read(
                        bytes,
                        frameBytes,
                        PCM_BYTES_PER_FRAME - frameBytes,
                        AudioRecord.READ_BLOCKING,
                    )
                    if (bytesRead < 0) {
                        // ERROR_DEAD_OBJECT: audioserver restarted; rebuild the recorder (and effects) a few times.
                        if (bytesRead == AudioRecord.ERROR_DEAD_OBJECT && watchdogOn && running.get() &&
                            deadObjectRebuilds < MAX_DEAD_OBJECT_REBUILDS
                        ) {
                            deadObjectRebuilds++
                            val rebuilt = switchSource(current, captureSource, "dead_object_$deadObjectRebuilds")
                            if (rebuilt != null) {
                                current = rebuilt
                                frameBytes = 0
                                silence.reset()
                                continue
                            }
                        }
                        error("Microphone read failed ($bytesRead)")
                    }
                    frameBytes += bytesRead
                }
                if (frameBytes != PCM_BYTES_PER_FRAME) continue
                if (firstPresentationTimeUs == 0L) {
                    firstPresentationTimeUs =
                        SystemClock.elapsedRealtimeNanos() / 1_000L - FRAME_DURATION_US
                }
                val presentationTimeUs = firstPresentationTimeUs +
                    capturedSamples * 1_000_000L / StreamProtocol.AUDIO_SAMPLE_RATE
                if (!captureTimestampLogged && captureTimestampAttempts < 20) {
                    val status = runCatching {
                        current.getTimestamp(captureTimestamp, AudioTimestamp.TIMEBASE_BOOTTIME)
                    }.getOrDefault(AudioRecord.ERROR_INVALID_OPERATION)
                    captureTimestampAttempts++
                    if (status == AudioRecord.SUCCESS) {
                        val hardwareCaptureNs = captureTimestamp.nanoTime +
                            (capturedSamples - captureTimestamp.framePosition) * 1_000_000_000L / StreamProtocol.AUDIO_SAMPLE_RATE
                        Log.i("BluetoothVideo", "Microphone clock: BOOTTIME frame=${captureTimestamp.framePosition}, nano=${captureTimestamp.nanoTime}, read sample=$capturedSamples, assigned-minus-hardware=${(presentationTimeUs * 1_000L - hardwareCaptureNs) / 1_000_000.0} ms")
                        captureTimestampLogged = true
                    } else if (captureTimestampAttempts == 1) {
                        Log.i("BluetoothVideo", "Microphone hardware timestamp unavailable (status $status); waiting for first valid BOOTTIME sample")
                    } else if (captureTimestampAttempts == 20) {
                        Log.i("BluetoothVideo", "Microphone hardware timestamp unavailable after 20 startup samples (status $status); stopping diagnostic probes")
                    }
                }
                capturedSamples += frameBytes / BYTES_PER_SAMPLE
                frames++
                if (watchdogOn) {
                    val peak = PcmLevel.peak(bytes)
                    val before = current
                    if (silence.onFrame(peak)) current = onCaptureSilence(current)
                    if (AudioSwitches.captureSilenceEvidence) {
                        val verdict = blank.onFrame(peak)
                        if (verdict != BlankCaptureEvidence.Verdict.NONE) current = onBlankConfirmed(current, verdict, blank.zeroMs)
                    }
                    // A relaxed source is a new stream: it has to show signal again.
                    if (current !== before) blank.reset()
                    if (frames == ECHO_CHECK_FRAME) checkEchoRisk()
                }
                val frame = PcmFrame(bytes, presentationTimeUs)
                debugAudioTrace?.record("microphone_input", presentationTimeUs, bytes)
                if (!pcmFrames.offer(frame)) {
                    pcmFrames.poll()
                    pcmFrames.offer(frame)
                }
            }
        } catch (error: Exception) {
            fail(error)
        } finally {
            captureWorker = null
        }
    }

    private fun encodeLoop(activeCodec: MediaCodec) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var codecConfigSent = false
        var pendingFrame: PcmFrame? = null
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                if (pendingFrame == null) {
                    pendingFrame = pcmFrames.poll(ACTIVE_POLL_MS, TimeUnit.MILLISECONDS)
                }
                val frame = pendingFrame
                if (frame != null) {
                    val inputIndex = activeCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = checkNotNull(activeCodec.getInputBuffer(inputIndex))
                        input.clear()
                        require(input.remaining() >= frame.bytes.size) {
                            "Opus encoder input is smaller than one audio frame"
                        }
                        input.put(frame.bytes)
                        activeCodec.queueInputBuffer(
                            inputIndex,
                            0,
                            frame.bytes.size,
                            frame.presentationTimeUs,
                            0,
                        )
                        pendingFrame = null
                    }
                }

                while (running.get()) {
                    val outputIndex = activeCodec.dequeueOutputBuffer(info, 0)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!codecConfigSent) {
                                val config = activeCodec.outputFormat.getByteBuffer("csd-0")
                                    ?.duplicate()
                                    ?: error("Opus encoder did not provide codec configuration")
                                val bytes = ByteArray(config.remaining()).also(config::get)
                                onPacket(
                                    StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG,
                                    0,
                                    bytes,
                                )
                                codecConfigSent = true
                            }
                        }
                        outputIndex >= 0 -> {
                            val output = checkNotNull(activeCodec.getOutputBuffer(outputIndex))
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size).also(output::get)
                            val isConfiguration =
                                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (isConfiguration && !codecConfigSent) {
                                onPacket(
                                    StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG,
                                    info.presentationTimeUs,
                                    bytes,
                                )
                                codecConfigSent = true
                            } else if (!isConfiguration && bytes.isNotEmpty()) {
                                check(codecConfigSent) { "Opus audio arrived before codec configuration" }
                                onPacket(StreamProtocol.FLAG_AUDIO, info.presentationTimeUs, bytes)
                            }
                            activeCodec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            fail(error)
        } finally {
            encoderWorker = null
        }
    }

    /**
     * Experimental native libopus call encoder (DEBUG `test_call_native_opus`), and the U-08 fallback when no
     * MediaCodec Opus encoder works ([requested] = "mediacodec"). Null, with the reason logged as
     * `impl=mediacodec | requested=native`, when the library or encoder is unavailable, so the call keeps its
     * audio and the effective codec stays visible.
     */
    private fun startNativeEncoder(
        complexity: Int = OPUS_COMPLEXITY,
        requested: String = "native",
        reason: String? = null,
    ): NativeOpusEncoder? {
        val settings = DebugTunables.nativeOpusSettings()
        val failure = NativeOpusBridge.loadError ?: run {
            val encoder = runCatching {
                NativeOpusEncoder(
                    StreamProtocol.AUDIO_SAMPLE_RATE,
                    StreamProtocol.AUDIO_CHANNEL_COUNT,
                    bitRate,
                    complexity,
                    settings,
                )
            }
            encoder.getOrNull()?.let { active ->
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK audio_codec: role=encoder | impl=native | requested=$requested | " +
                        "name=libopus ${NativeOpusBridge.nativeOpusVersion()} | application=voip | " +
                        "fec=${if (settings.fec) "on" else "off"} | loss_pct=${settings.lossPercent} | " +
                        "dtx=${if (settings.dtx) "on" else "off"} | plc=n/a | kbps=${bitRate / 1_000} | " +
                        "complexity=$complexity | vbr=constrained | frame_ms=${StreamProtocol.AUDIO_PACKET_DURATION_MS} | " +
                        "pre_skip=${active.lookaheadSamples}" + if (reason != null) " | reason=$reason" else "",
                )
                return active
            }
            encoder.exceptionOrNull()
        }
        Log.w(
            "BluetoothVideo",
            "BENCHMARK audio_codec: role=encoder | impl=mediacodec | requested=native | " +
                "reason=native_unavailable | error=${failure?.message ?: failure?.javaClass?.simpleName}",
        )
        return null
    }

    private fun nativeEncodeLoop(initialEncoder: NativeOpusEncoder) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var encoder = initialEncoder
        var packets = 0L
        var dtxSkipped = 0L
        // U-09 (Experimental, default off): complexity 10 -> 8 -> 5 when the measured encode time stays high.
        val governor = if (AudioSwitches.complexityGovernor) OpusComplexityGovernor(FRAME_DURATION_US * 1_000L) else null
        try {
            onPacket(
                StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG,
                0,
                NativeOpusConfig.codec2Block(
                    StreamProtocol.AUDIO_CHANNEL_COUNT,
                    encoder.lookaheadSamples,
                    StreamProtocol.AUDIO_SAMPLE_RATE,
                ),
            )
            while (running.get()) {
                val frame = pcmFrames.poll(ACTIVE_POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                val startedNs = System.nanoTime()
                val bytes = encoder.encode(frame.bytes, SAMPLES_PER_FRAME)
                governor?.onFrame(System.nanoTime() - startedNs)?.let { complexity ->
                    startNativeEncoder(complexity = complexity, requested = "native", reason = "complexity_governor")?.let { replacement ->
                        val old = encoder
                        encoder = replacement
                        nativeEncoder = replacement
                        runCatching { old.close() }
                        Log.i("BluetoothVideo", "BENCHMARK audio_complexity_governor: complexity=$complexity | kbps=${bitRate / 1_000}")
                    }
                }
                // DTX (sub-switch, default off): a 1-2 byte packet means "no transmission needed".
                if (encoder.settings.dtx && bytes.size <= DTX_SILENT_PACKET_BYTES) {
                    dtxSkipped++
                    continue
                }
                if (bytes.isNotEmpty()) {
                    onPacket(StreamProtocol.FLAG_AUDIO, frame.presentationTimeUs, bytes)
                    packets++
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            fail(error)
        } finally {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK audio_codec_stats: role=encoder | impl=native | packets=$packets | dtx_skipped=$dtxSkipped",
            )
            encoderWorker = null
        }
    }

    private fun fail(error: Throwable) {
        if (!running.getAndSet(false)) return
        runCatching { recorder?.stop() }
        captureWorker?.interrupt()
        encoderWorker?.interrupt()
        onError(error.message ?: error.javaClass.simpleName)
    }

    /**
     * Stops the workers and releases the microphone and codecs. This is a timed, serialized [AudioCommandQueue]
     * command (it joins two threads for up to a second each and stops an AudioRecord); with `test_audio_off_main`
     * it runs on the audio command thread instead of blocking a main-thread caller. [running] is cleared at once.
     */
    override fun close() {
        running.set(false)
        AudioCommandQueue.shared.offload("mic_encoder_close") {
            runCatching { recorder?.stop() }
            captureWorker?.interrupt()
            encoderWorker?.interrupt()
            runCatching { captureWorker?.join(1_000) }
            runCatching { encoderWorker?.join(1_000) }
            pcmFrames.clear()
            releaseResources()
        }
    }

    @Synchronized
    private fun releaseResources() {
        unregisterRecordingCallback()
        callbackExecutor.shutdown()
        audioEffects.forEach { effect -> runCatching { effect.release() } }
        audioEffects.clear()
        runCatching { referenceInput?.close() }
        referenceInput = null
        debugAudioTrace?.close()
        debugAudioTrace = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { nativeEncoder?.close() }
        nativeEncoder = null
    }

    /**
     * The platform voice-communication pre-processing (CDD 5.4.4: an AEC SHOULD be applied to
     * VOICE_COMMUNICATION capture and is STRONGLY RECOMMENDED to be exposed via
     * AcousticEchoCanceler.isAvailable()). Each effect's default state (read BEFORE it is enabled), descriptor
     * UUID, implementor, control ownership, setEnabled status and final state go to the `BENCHMARK audio_capture`
     * line (`effectState=`; `effects=` keeps its former format), so per-OEM behaviour is evidence, not an
     * assumption. No effect is ever switched off here, and the AOSP software AEC stays enabled: MeshLive has no
     * echo canceller of its own (WebRTC skips that effect only because it ships a better one; question Q-N4).
     */
    private fun enableCallAudioEffects(audioSessionId: Int) {
        effectOutcomes.clear()
        fun add(name: String, available: Boolean, create: () -> AudioEffect?) {
            val (outcome, handle) = AudioEffectProbe.enable(name, available) { create()?.let(::PlatformEffectHandle) }
            effectOutcomes += outcome
            (handle as? PlatformEffectHandle)?.let { audioEffects += it.effect }
        }
        add("aec", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(audioSessionId) }
        add("ns", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(audioSessionId) }
        add("agc", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(audioSessionId) }
        effectSummary = AudioEffectProbe.summary(effectOutcomes)
        effectDetail = if (AudioSwitches.policyActive(EFFECT_LOGGING_ENTRY_ID)) AudioEffectProbe.detail(effectOutcomes) else ""
    }

    private fun encoderComplexity(activeCodec: MediaCodec): String = runCatching {
        val format = activeCodec.inputFormat
        if (format.containsKey(MediaFormat.KEY_COMPLEXITY)) format.getInteger(MediaFormat.KEY_COMPLEXITY).toString()
        else "requested-$OPUS_COMPLEXITY"
    }.getOrDefault("requested-$OPUS_COMPLEXITY")

    private companion object {
        const val FRAME_DURATION_US = StreamProtocol.AUDIO_PACKET_DURATION_MS * 1_000L
        const val SAMPLES_PER_FRAME = StreamProtocol.AUDIO_SAMPLE_RATE *
            StreamProtocol.AUDIO_PACKET_DURATION_MS / 1_000
        const val BYTES_PER_SAMPLE = 2 * StreamProtocol.AUDIO_CHANNEL_COUNT
        const val PCM_BYTES_PER_FRAME = SAMPLES_PER_FRAME * BYTES_PER_SAMPLE
        const val PCM_QUEUE_CAPACITY = 5
        const val OPUS_COMPLEXITY = 10
        const val ACTIVE_POLL_MS = 20L
        const val INPUT_TIMEOUT_US = 2_000L
        const val DTX_SILENT_PACKET_BYTES = 2
        const val MAX_DEAD_OBJECT_REBUILDS = 3
        /** About one second after the first frame the platform's effect report has normally arrived. */
        const val ECHO_CHECK_FRAME = 50L
    }
}

/** Wraps a platform [AudioEffect] for [AudioEffectProbe]. */
internal class PlatformEffectHandle(val effect: AudioEffect) : EffectHandle {
    override fun uuid(): String? = effect.descriptor?.uuid?.toString()
    override fun implementor(): String? = effect.descriptor?.implementor
    override fun hasControl(): Boolean? = effect.hasControl()
    override fun enabled(): Boolean? = effect.enabled
    override fun setEnabled(enabled: Boolean): Int? = effect.setEnabled(enabled)
    override fun release() = effect.release()
}
