package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Range
import androidx.camera.viewfinder.view.ViewfinderView
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

class CameraVideoEncoder(
    private val context: Context,
    private val viewfinder: ViewfinderView,
    private val cameraLens: CameraLens,
    private val videoCodec: VideoCodec,
    private val settings: VideoSettings,
    referenceFrameLimit: Int,
    private val debugTraceSessionName: String?,
    private val onPacket: (flags: Int, timeUs: Long, bytes: ByteArray) -> Unit,
    private val onStatus: (String) -> Unit,
    private val reusableViewfinderSurface: Pair<Surface, CameraViewfinderSurface>? = null,
    private val rateControl: EncoderRateControl = EncoderRateControl.AUTOMATIC,
    private val scaleMode: VideoScaleMode = VideoScaleMode.FILL,
    private val onFirstVideoFrame: () -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {},
    private val debugRawInputFile: File? = null,
    /**
     * The raw fixture's declared geometry (DEBUG): frames are read at this size only, and the encoder must
     * run at it ([RawFixtureGeometry]). Null falls back to the encoder size, which is still checked for
     * whole frames.
     */
    private val rawGeometry: Pair<Int, Int>? = null,
    /**
     * Experimental call encoder fit (CallEncoderFit; null for streams and while off): told once,
     * with this geometry's speed key, when the native encoder stays over its frame budget at its
     * fastest preset with the fast tool profile for CallEncoderFit.OVERLOAD_WINDOWS windows.
     */
    private val onNativeOverloadAtFastest: ((key: String) -> Unit)? = null,
    /**
     * GPU scale ladder (GpuScaleFeature; null while off or not applicable): the camera belongs to a
     * shared [GpuScaleCapture] and this encoder's own size is drawn into its input surface, so a rung
     * change rebuilds only the encoder. Ignored for software (YUV input) encoders and raw fixtures.
     */
    private val gpuScale: GpuScaleRequest? = null,
    /** DEBUG encoder bench (EncoderBenchSpec): per-run overrides; null in every product path. */
    private val bench: EncoderBenchOverrides? = null,
    /**
     * AP5 `test_sw_shortfall_avc` (SoftwareEncoderShortfall; null in release builds): told once per session, with this
     * encoder's name, size and requested FPS, when the native (software) encoder stayed short of the frame rate in force
     * for SoftwareEncoderShortfall.SHORT_WINDOWS windows while its input kept up.
     */
    private val onSoftwareShortfall: ((SoftwareShortfallMark, SoftwareShortfallVerdict) -> Unit)? = null,
    /** DEBUG call join stages ([CallJoinStages]; null for streams): encoder started and the camera's first frame, once each. */
    private val onJoinStage: ((CallJoinStage, String) -> Unit)? = null,
    /**
     * JT2 `test_call_camera_first` (promoted 2026-11-12, default on): the camera opens while its preview surface is requested, and the first
     * capture session is configured when both are ready ([CameraOpenJoin]); false: the surface first, then the open (as before).
     */
    parallelOpen: Boolean = false,
) : Closeable {
    private val openJoin = if (parallelOpen) CameraOpenJoin() else null
    private var openRequestedNs = 0L
    private var sessionCreateNs = 0L
    private var sessionQueryMs = -1L
    /** The coded size this encoder produces; a restart that changes it marks its first keyframe (RestartDiscontinuityMarker). */
    val outputResolution: VideoResolution
        get() = settings.resolution
    private var cameraInputWidth = settings.resolution.width
    private var cameraInputHeight = settings.resolution.height
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val workerThread = HandlerThread("CameraEncoder").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val closed = AtomicBoolean(false)
    /** A bundled software encoder (libaom AV1 or, Design VX, libvpx VP9) fed I420 by this class. */
    private val usesNativeEncoder = videoCodec.backend.isNative
    /** Design VX: the native encoder is libvpx VP9 (no temporal layering or tool-profile escalation on this path). */
    private val usesNativeVpx = videoCodec.backend == VideoEncoderBackend.NATIVE_LIBVPX
    /** Log label of the native encoder. */
    private val nativeLabel = if (usesNativeVpx) "libvpx" else "libaom"
    private val nativeEncoderThread = if (usesNativeEncoder) {
        HandlerThread(if (usesNativeVpx) "NativeVpxEncoder" else "NativeAomEncoder").apply { start() }
    } else {
        null
    }
    private val nativeWorker = nativeEncoderThread?.let { thread -> Handler(thread.looper) }

    private var codec: MediaCodec? = null
    private var nativeEncoder: NativeVideoEncoder? = null
    private var nativeInputBuffers: ArrayBlockingQueue<ByteBuffer>? = null
    private var nativeInputDrops = 0
    private val nativeCopyLatency = RollingLatencyStats()
    private val nativeEncodeLatency = RollingLatencyStats()
    private var nativeSpeedGovernor: NativeEncoderSpeedGovernor? = null
    private var nativeSpeedLearner: NativeEncoderSpeedLearner? = null
    private var nativeSpeedStore: NativeEncoderSpeedStore? = null
    private var nativeOverloadReported = false
    /** AP5 SoftwareEncoderShortfall windows of this session: native (software) encoders with a listener only. */
    private val softwareShortfall = if (usesNativeEncoder && onSoftwareShortfall != null) SoftwareShortfallWindows() else null
    private var softwareShortfallDropsAtWindowStart = 0L
    /** libvpx presets use their own scale, so they are remembered under their own key. */
    private val nativeSpeedKey = (if (usesNativeVpx) "libvpx:" else "") +
        nativeEncoderSpeedKey(settings.resolution.width, settings.resolution.height, settings.fps)
    /** Tool profile of the live native encoder: the mode table's, or learned on this phone. */
    private var nativeFastTools = nativeUsesFastToolProfile(settings)
    /**
     * Experimental temporal layering (off by default, DebugTunables.experimentalTemporalLayers):
     * every other frame refreshes nothing and is discardable. Fixed for this encoder's lifetime.
     */
    private val experimentalTemporalLayers = DebugTunables.experimentalTemporalLayers || DebugTunables.relayChildThinning
    /** Design HC: codec 4 or 5 (Annex B, in-band parameter sets); null for VP9 and AV1, whose path is unchanged. */
    private val hcCodec: AnnexBCodec? = AnnexBCodec.of(videoCodec.protocolId)
    /** Design HC: one packetiser per MediaCodec instance (a re-created encoder emits its own sets). Worker thread. */
    private var hcPacketiser: AnnexBPacketiser? = hcCodec?.let(::AnnexBPacketiser)
    /** Design HC: the sender's own copy of the receivers' structural gate; nothing it rejects is sent. Worker thread. */
    private val hcSelfCheck: AccessUnitGate? = hcCodec?.let { codec ->
        AccessUnitGate(codec, settings.resolution.width, settings.resolution.height) { System.nanoTime() / 1_000_000L }
    }
    /** Design HC: a keyframe request must be answered by an IDR within 1 s, three misses end video (`no_idr`). */
    private val hcIdrDeadline: IdrRequestDeadline? = hcCodec?.let { IdrRequestDeadline() }
    private var hcFailed = false
    private var hcVectors: HcTestVectorCapture? = null
    private var nativeTemporalLayers = false
    private var nativeLayerFrameIndex = 0L
    /** LayeringPolicy: produce droppable frames only while a link needs them. */
    @Volatile private var droppableFramesWanted = true
    /** Explicit libaom references are active; only a new encoder returns to libaom's own. */
    private var nativeExplicitReferences = false
    private var nativeLastInterQuantizer = -1
    private var nativeInterBitratePct = 0
    /** Native frames are marked droppable only after their AV1 headers prove it (experiment only). */
    private val nativeDroppability by lazy { BitstreamDroppability(MediaFormat.MIMETYPE_VIDEO_AV1) }
    private var nativeUnverifiedDroppable = 0L
    private val nativeKeyFrameRequested = AtomicBoolean(true)
    private var lastNativeKeyFramePtsUs = Long.MIN_VALUE
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private val gpuActive = gpuScale != null && videoCodec.usesSurfaceInput && debugRawInputFile == null &&
        referenceFrameLimit == 0
    private var gpuCapture: GpuScaleCapture? = null
    /**
     * DEBUG: camera-side losses for a hardware encoder's own input surface ([SurfaceCaptureLoss] says what it counts
     * and cannot see), logged as the native path's `capture drops N`. Null on the native, raw-input and GPU paths.
     */
    private val surfaceCaptureLoss = if (BuildConfig.DEBUG && videoCodec.usesSurfaceInput && !usesNativeEncoder &&
        debugRawInputFile == null && !gpuActive) SurfaceCaptureLoss() else null
    /**
     * DEBUG: camera-side losses on the YUV copy path (a MediaCodec encoder fed copied camera images, for example
     * c2.android.vp9.encoder), matched by sensor timestamp ([CopyPathCaptureLoss] says what it counts and cannot see).
     */
    private val copyCaptureLoss = if (BuildConfig.DEBUG && !videoCodec.usesSurfaceInput && !usesNativeEncoder &&
        debugRawInputFile == null) CopyPathCaptureLoss() else null
    private var gpuStartedNs = 0L
    private var gpuResetMs = -1.0
    @Volatile private var retainGpuCaptureOnClose = false
    private var previewSurface: Surface? = null
    private var viewfinderSurface: CameraViewfinderSurface? = null
    private var encoderSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var referenceImageReader: ImageReader? = null
    private val referenceRecorder = if (referenceFrameLimit > 0) {
        ReferenceRecorder(
            File(context.getExternalFilesDir(null), "benchmark"),
            settings.resolution.width,
            settings.resolution.height,
            referenceFrameLimit,
        )
    } else {
        null
    }
    private var pendingImage: Image? = null
    private val pendingInputBuffers = ArrayDeque<Int>()
    private var availableFpsRanges = emptyList<Range<Int>>()
    private var targetFpsRange: Range<Int>? = null
    private var captureTargets = emptyList<Surface>()
    private var captureSessionGeneration = 0
    /** The encoder component in use: the requested one unless the init fallback chain moved to another (V-09). */
    @Volatile private var activeEncoderName = videoCodec.encoderName
    private val deviceTraits = DeviceTraits.current()
    private var openedCameraId: String? = null
    private var hardwareLevel: Int? = null
    private var rotateAndCropNoneSupported = false
    /** Repeating-request template; the Experimental frame-stall response switches it to TEMPLATE_PREVIEW once. */
    private var captureTemplate = CameraDevice.TEMPLATE_RECORD
    private val rebuildMode = CameraRebuildPolicy.modeFor(deviceTraits)
    private var closingSession: CameraCaptureSession? = null
    private var activePlan = CameraStreamPlan("full", false, false)
    private val openRetry = CameraOpenRetryState { SystemClock.elapsedRealtime() }
    private val rebuildHost: CameraRebuildHost = object : CameraRebuildHost {
        override fun hasSession(): Boolean = session != null

        override fun closeSession() {
            val old = session
            session = null
            captureSessionGeneration++
            closingSession = old
            runCatching { old?.close() }
        }

        override fun closeDevice() {
            val old = camera
            camera = null
            if (old == null) {
                worker.post { rebuilder.onDeviceClosed() }
            } else {
                runCatching { old.close() }
            }
        }

        @SuppressLint("MissingPermission")
        override fun openDevice() {
            val id = openedCameraId
            if (id == null) {
                rebuildFailed("Camera id unknown; cannot reopen.")
                return
            }
            runCatching { cameraManager.openCamera(id, cameraCallback, worker) }
                .exceptionOrNull()
                ?.let { error -> rebuildFailed("Camera could not reopen: ${error.message}") }
        }

        override fun createSession() {
            configureCaptureSession()
        }

        override fun log(message: String) {
            Log.i("BluetoothVideo", "BENCHMARK camera_rebuild: mode=$rebuildMode | $message")
        }

        override fun rebuildFailed(message: String) {
            notifyFailure(message)
        }
    }
    private val rebuilder: CameraSessionRebuilder = CameraSessionRebuilder(
        rebuildHost,
        object : CameraRebuildScheduler {
            override fun postDelayed(delayMs: Long, action: Runnable) {
                worker.postDelayed(action, delayMs)
            }

            override fun remove(action: Runnable) {
                worker.removeCallbacks(action)
            }
        },
        { SystemClock.elapsedRealtime() },
    )
    /** Encoded (non-config) frames out of the encoder; the frame-stall watchdog reads it. */
    private val framesEncoded = java.util.concurrent.atomic.AtomicLong(0L)
    private val stallLadder = FrameStallLadder(CameraSwitches.starvationFallback, DebugTunables.benchmarkLaunch)
    private val stallChecks = mutableListOf<Runnable>()
    private var stallReportedGeneration = -1
    private val captureIntervals = CaptureIntervalMonitor()
    private var rotateAndCropLogged = false
    private val bitrateProbe = DynamicBitrateProbe()
    private var qpScaleLogged = false
    private var tailClampLogged = false
    private val blackFrameDetector = BlackFrameDetector()
    private var lastHealthSampleNs = 0L
    private var firstFramesChecked = 0
    /** Experimental: the camera's leading blank buffers (S22) are dropped before the encoder ([LeadingBlankFrames]). */
    private val leadingBlankFrames = LeadingBlankFrames()
    private var blankFramesInjected = 0
    /** V-19 first-frames check: 8 frames in DEBUG (the S22 sent 4 blank buffers; 3 hid the fourth), 3 otherwise. */
    private val firstFramesToCheck = if (BuildConfig.DEBUG) FIRST_FRAMES_CHECKED_DEBUG else FIRST_FRAMES_CHECKED
    private var leadingBlankLogged = false
    private val privacyNoticeOn = CameraPolicy.isOn("camera-privacy-toggle-notice", deviceTraits)
    private var privacyNoticeShown = false
    private var continuousAutoFocusAvailable = false
    @Volatile private var activeBitrate = settings.bitRate
    /** Bitrate last handed to the hardware encoder (worker thread). */
    private var encoderTargetBps = settings.bitRate
    /** A shaped keyframe was requested; the full target returns once it is encoded. */
    @Volatile private var shapedKeyFramePending = false
    private val restoreShapedTarget = Runnable {
        if (!shapedKeyFramePending) return@Runnable
        shapedKeyFramePending = false
        if (sceneCutShaper.pending) return@Runnable
        val activeCodec = codec ?: return@Runnable
        runCatching {
            activeCodec.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, codecTargetBps()) },
            )
        }
    }

    /**
     * The rate controller's target for the MediaCodec encoder: the trimmed target, lowered further
     * only by the experimental quantizer headroom (factor 1 while it is off).
     */
    private fun baseCodecTargetBps(): Int =
        (encoderTargetBps * (quantizerHeadroom?.factor ?: 1.0)).toInt().coerceAtLeast(1)

    /**
     * The bitrate the MediaCodec encoder is given: [baseCodecTargetBps] scaled down by the measured
     * [bitrateAdjuster] correction (never above it).
     */
    private fun codecTargetBps(): Int {
        val base = baseCodecTargetBps()
        if (framerateAdjusterArm) return CodecPolicy.framerateAdjustedBps(base, settings.fps, frameRateLimitFps)
        return bitrateAdjuster?.adjustedBps(base) ?: base
    }

    /** Why [codecTargetBps] differs from the rate controller's [bitRate], for status lines. */
    private fun codecTargetCorrections(): String = listOfNotNull(
        bitrateAdjuster?.takeIf { it.scaleExponent != 0 }?.let { "bitrate adjuster x${roundedForLog(it.scale, 2)}" },
        frameRateLimitFps.takeIf { framerateAdjusterArm && it in 1 until settings.fps }
            ?.let { "experimental framerate adjuster x${roundedForLog(settings.fps.toDouble() / it, 2)}" },
        quantizerHeadroom?.takeIf { it.factor != 1.0 }?.let { "experimental quantizer headroom x${roundedForLog(it.factor, 2)}" },
    ).joinToString(", ")

    /** Re-applies [codecTargetBps] unless a shaped keyframe or scene cut still holds a lower one. */
    private fun applyCodecTarget(reason: String) {
        if (shapedKeyFramePending || sceneCutShaper.pending) return
        val activeCodec = codec ?: return
        val target = codecTargetBps()
        runCatching {
            activeCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, target) })
        }.onSuccess {
            Log.i("BluetoothVideo", "Encoder target: ${target / 1_000} kbps ($reason)")
        }.onFailure { error ->
            onStatus("Encoder rejected bitrate update: ${error.message ?: error.javaClass.simpleName}")
        }
    }
    /** Automatic only: corrects this encoder's measured output toward [activeBitrate]. */
    private val rateTrim = EncoderRateTrim()
    private val isVp9 = videoCodec.mimeType == MediaFormat.MIMETYPE_VIDEO_VP9
    /** Diagnostic (on): base_q_idx parsed from each VP9 output frame (worker thread). */
    private var lastVp9QIdx: Int? = null
    private val quantizerWindow = QuantizerWindow()
    /** Per-viewer link share last handed to [updateLinkFrameBudget] (0 = not measured yet). */
    @Volatile private var frameBudgetLinkKbps = 0
    /**
     * Experimental scene-cut shaping (DebugTunables.sceneCutShaping, off by default). The detector
     * also runs in benchmark-traced sessions with the experiment off, so both A/B arms log the same
     * cut decisions. Worker thread only.
     */
    private val sceneCutShaper = SceneCutShaper(
        if (usesNativeEncoder) SceneCutShapingMode.OFF else DebugTunables.sceneCutShaping,
    )
    private val sceneCutDetection = sceneCutShaper.mode != SceneCutShapingMode.OFF ||
        BuildConfig.DEBUG && !debugTraceSessionName.isNullOrBlank()
    private var sceneCutDetector: SceneCutDetector? = null
    private var sceneCutsDetected = 0
    /** Experimental VP9 quantizer headroom (DebugTunables.vp9QuantizerFloor, off by default). */
    private val quantizerHeadroom = DebugTunables.vp9QuantizerFloor
        ?.takeIf { isVp9 && !usesNativeEncoder && rateControl == EncoderRateControl.AUTOMATIC }
        ?.let(::QuantizerHeadroomGovernor)
    /**
     * Default-on measured output correction for MediaCodec encoders under Automatic rate control
     * (EncoderBitrateAdjuster, a WebRTC DynamicBitrateAdjuster port); null when switched off with
     * the DEBUG extra `test_encoder_bitrate_adjuster=false`, for native libaom (its own rate control
     * and the output-rate trim) and for explicitly chosen bitrate modes. Worker thread only.
     */
    /**
     * Experimental A/B arm (off): WebRTC FramerateBitrateAdjuster within its exact scope (OMX.Exynos.
     * VP9/H.264, CodecPolicy.framerateAdjusterScope). It replaces [bitrateAdjuster] for the session.
     */
    private val framerateAdjusterArm = DebugTunables.encoderFramerateAdjuster && !usesNativeEncoder &&
        rateControl == EncoderRateControl.AUTOMATIC &&
        CodecPolicy.framerateAdjusterScope(videoCodec.encoderName, videoCodec.mimeType)
    /**
     * Delivered frame rate the arm uses: encoded frames per second of presentation time (camera,
     * link/thermal limit or raw fixture alike); the codec stays configured at settings.fps.
     */
    @Volatile private var frameRateLimitFps = settings.fps
    private val deliveredFrameRate = DeliveredFrameRate()
    private var framerateAppliedNs = 0L
    /** yuv-image-crop-rect (YuvPolicy): copies start from Image.getCropRect(). */
    private val honourCropRect = YuvPolicy.cropRectHonoured(DeviceTraits.current())
    private val bitrateAdjuster = EncoderBitrateAdjuster().takeIf {
        DebugTunables.encoderBitrateAdjuster && !usesNativeEncoder && rateControl == EncoderRateControl.AUTOMATIC &&
            !framerateAdjusterArm
    }
    private var bitrateAdjusterLogStartedNs = 0L
    private var bitrateAdjusterLogBytes = 0L
    /** Measured burst behaviour of this encoder (recorded every session; ranking is experimental). */
    private val burstAccumulator = EncoderBurstAccumulator()
    private val activeBitrateMode = DebugTunables.bitrateModeOverride
        .takeIf { !videoCodec.backend.isNative }
        ?: videoCodec.resolveBitrateMode(rateControl)
    private val activeComplexity = videoCodec.realtimeComplexity(settings)
    private val keyFrameRequestBackoff = KeyFrameRequestBackoff()
    private var firstVideoFrameSeen = false
    private val encodedFrameInterval = RollingLatencyStats()
    private val captureToEncodedOutput = RollingLatencyStats()
    private val inputToEncodedOutput = RollingLatencyStats()
    private val captureToCodecInput = RollingLatencyStats()
    private val yuvCopyLatency = RollingLatencyStats()
    private var exposureTimeNs: Long? = null
    private var sensorFrameDurationNs: Long? = null
    private var cameraPipelineDepth: Byte? = null
    private val queuedInputTimesNs = LinkedHashMap<Long, Long>()
    private var cameraTimestampSource: Int? = null
    private var lastEncodedPresentationTimeUs = Long.MIN_VALUE
    private var cadenceReportStartedNs = 0L
    private var cadenceReportFrames = 0
    private var cadenceReportBytes = 0L
    private var encodingStatisticsEnabled = false
    /**
     * Experimental temporal layering only: MediaCodec frames proven droppable from their bitstream.
     * Null (nothing is ever marked) while the experiment is off and for native AV1.
     */
    private var bitstreamDroppability: BitstreamDroppability? = null
    private var temporalLayeringRequested = false
    private var layeringFormatLogged = false
    private var debugMediaTrace: DebugMediaTrace? = null
    private val debugStageTrace = DebugStageTrace.startIfRequested(debugTraceSessionName, "video_encoder")
    private var codecPresentationTimeOffsetUs: Long? = null
    private var rawInput: RandomAccessFile? = null
    private var rawFrame: ByteArray? = null
    private var rawStartedNs = 0L
    @Volatile private var rawStartedUs = 0L
    private var rawNextFrameNs = 0L
    private var rawInputFps = settings.fps
    private var rawInputDrops = 0L
    /** The key-frame interval in seconds: the app's own unless the DEBUG encoder bench overrides it. */
    private val keyFrameIntervalSeconds = bench?.keyFrameIntervalSeconds ?: settings.keyFrameIntervalSeconds
    /** The MediaCodec output format last reported (encoder bench read-back). */
    @Volatile private var lastOutputFormat: MediaFormat? = null
    val benchmarkRawClock: RawReferenceClock? get() =
        if (BuildConfig.DEBUG && rawStartedUs > 0L) RawReferenceClock(rawStartedUs, rawStartedNs) else null

    @SuppressLint("MissingPermission")
    /** Experimental call pre-warm ([CallEncoderPrewarm]): [prepareEncoder] ran ahead of [start]. */
    @Volatile private var encoderPrepared = false
    private var preparedCameraId: String? = null
    private var preparedCameraCharacteristics: CameraCharacteristics? = null

    /**
     * Experimental call pre-warm (`test_call_prewarm`): only the encoder part of [start] (camera metadata, encoder
     * create, configure, input surface or buffers, start). Opens no camera device and starts no capture or raw input;
     * a later [start] does only that. Runs on the thread [start] runs on.
     */
    fun prepare() {
        if (closed.get() || encoderPrepared) return
        prepareEncoder()
    }

    fun start() {
        if (!encoderPrepared) prepareEncoder()
        startCapture()
    }

    private fun prepareEncoder() {
        check(debugRawInputFile == null || BuildConfig.DEBUG) { "Raw fixture input is debug-only" }
        gpuStartedNs = System.nanoTime()
        val selectedCameraId = if (RawInputGuard.cameraMayStart(debugRawInputFile != null)) {
            cameraManager.cameraIdFor(cameraLens)
                ?: error("${cameraLens.displayName} camera is unavailable")
        } else null
        openedCameraId = selectedCameraId
        val cameraCharacteristics = selectedCameraId?.let(cameraManager::getCameraCharacteristics)
        cameraTimestampSource = cameraCharacteristics?.let {
            safeCameraRead("SENSOR_INFO_TIMESTAMP_SOURCE") { it.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) }
        }
        hardwareLevel = cameraCharacteristics?.let {
            safeCameraRead("INFO_SUPPORTED_HARDWARE_LEVEL") { it.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) }
        }
        if (!videoCodec.usesSurfaceInput && cameraCharacteristics != null) {
            val yuvSizes = safeCameraRead("SCALER_STREAM_CONFIGURATION_MAP") {
                cameraCharacteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
            }.orEmpty()
            (yuvSizes.firstOrNull { size ->
                size.width == settings.resolution.width && size.height == settings.resolution.height
            } ?: yuvSizes.filter { size ->
                    size.width >= settings.resolution.width && size.height >= settings.resolution.height
                }
                .minByOrNull { size -> size.width.toLong() * size.height })
                ?.let { size ->
                    cameraInputWidth = size.width
                    cameraInputHeight = size.height
                }
        }
        if (cameraCharacteristics != null) {
            Log.i("BluetoothVideo", "Camera clock: id=$selectedCameraId, timestamp source=$cameraTimestampSource (0=UNKNOWN, 1=REALTIME), input=${cameraInputWidth}x$cameraInputHeight")
        }
        availableFpsRanges = cameraCharacteristics?.let {
            safeCameraRead("CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES") {
                it.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            }
        }.orEmpty().toList()
        targetFpsRange = selectFpsRange(settings.fps)
        continuousAutoFocusAvailable = cameraCharacteristics?.let {
            safeCameraRead("CONTROL_AF_AVAILABLE_MODES") { it.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) }
        }?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true
        if (cameraCharacteristics != null) {
            rotateAndCropNoneSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                safeCameraRead("SCALER_AVAILABLE_ROTATE_AND_CROP_MODES") {
                    cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
                }?.contains(CameraMetadata.SCALER_ROTATE_AND_CROP_NONE) == true
            logCameraGeometry(selectedCameraId, cameraCharacteristics)
        }

        reusableViewfinderSurface?.let { (surface, owner) ->
            previewSurface = surface
            viewfinderSurface = owner
        }

        if (usesNativeEncoder) {
            val defaultCpuUsed = checkNotNull(videoCodec.nativeCpuUsed(settings))
            val store = NativeEncoderSpeedPreferences(context)
            nativeSpeedStore = store
            val remembered = runCatching { store.load(nativeSpeedKey) }.getOrNull()
            // libvpx has no tool profile to escalate to: it counts as already "fast" and only the preset adapts.
            val learner = NativeEncoderSpeedLearner(
                defaultCpuUsed,
                usesNativeVpx || nativeUsesFastToolProfile(settings),
                remembered,
                maxCpuUsed = nativeMaxCpuUsed,
            )
            nativeSpeedLearner = learner
            nativeFastTools = learner.fastTools
            // The bench refuses a libvpx pin outside 5..9 before the encoder starts; a libaom pin is used as given.
            val cpuUsed = bench?.libaomCpuUsed ?: learner.startCpuUsed
            val frameBytes = YuvPlaneCopy.i420Bytes(settings.resolution.width, settings.resolution.height)
            // A slow frame waits in a buffer (adds latency) instead of losing the next camera frame.
            nativeInputBuffers = ArrayBlockingQueue<ByteBuffer>(NATIVE_INPUT_BUFFERS).apply {
                repeat(NATIVE_INPUT_BUFFERS) { add(ByteBuffer.allocateDirect(frameBytes)) }
            }
            val threadCount = nativeThreadCount(settings.resolution)
            nativeSpeedGovernor = NativeEncoderSpeedGovernor(cpuUsed, settings.fps, nativeMinCpuUsed, nativeMaxCpuUsed)
            nativeEncoder = createNativeEncoder(cpuUsed, activeBitrate)
            // The experimental temporal layering is AV1-only (explicit libaom references, AV1 header check).
            nativeTemporalLayers = experimentalTemporalLayers && !usesNativeVpx
            nativeLayerFrameIndex = 0L
            if (usesNativeVpx) Log.i(
                "BluetoothVideo",
                "Native libvpx ${NativeVpxBridge.nativeVersion()}: cpu-used=$cpuUsed " +
                    "(table $defaultCpuUsed, remembered ${remembered?.cpuUsed ?: "none"} for $nativeSpeedKey), " +
                    "inputBuffers=$NATIVE_INPUT_BUFFERS, threads=$threadCount, lag=0, AQ=cyclic-refresh, " +
                    "error-resilient, row-mt, VP9 real-time",
            ) else Log.i(
                "BluetoothVideo",
                "Native libaom ${NativeAomBridge.nativeVersion()}: cpu-used=$cpuUsed " +
                    "(table $defaultCpuUsed, remembered ${remembered?.cpuUsed ?: "none"} for $nativeSpeedKey), " +
                    "fastTools=$nativeFastTools (table ${nativeUsesFastToolProfile(settings)}, " +
                    "remembered ${remembered?.fastTools ?: "none"}), inputBuffers=$NATIVE_INPUT_BUFFERS, " +
                    "threads=$threadCount, lag=0, AQ=cyclic-refresh, WebRTC real-time tools",
            )
        } else {
            startMediaCodecEncoder()
        }
        debugMediaTrace = DebugMediaTrace.startIfRequested(
            context,
            debugTraceSessionName,
            "sender",
            videoCodec.protocolId,
            settings.resolution.width,
            settings.resolution.height,
            settings.fps,
            activeEncoderName,
        )
        Log.i(
            "BluetoothVideo",
            "Encoder statistics ${if (encodingStatisticsEnabled) "enabled" else "unavailable"} " +
                "for ${activeEncoderName}",
        )
        onJoinStage?.invoke(
            CallJoinStage.ENCODER_STARTED,
            "encoder=$activeEncoderName | path=${inputPathLabel()} | initMs=${(System.nanoTime() - gpuStartedNs) / 1_000_000L}",
        )
        preparedCameraId = selectedCameraId
        preparedCameraCharacteristics = cameraCharacteristics
        encoderPrepared = true
    }

    /** The camera part of [start]: the only place this class opens the camera (or starts the GPU or raw input). */
    private fun startCapture() {
        val selectedCameraId = preparedCameraId
        val cameraCharacteristics = preparedCameraCharacteristics
        if (gpuActive) {
            startGpuCapture()
            return
        }
        if (debugRawInputFile != null) {
            startRawInput(debugRawInputFile)
            return
        }
        if (!videoCodec.usesSurfaceInput) {
            imageReader = ImageReader.newInstance(
                cameraInputWidth,
                cameraInputHeight,
                android.graphics.ImageFormat.YUV_420_888,
                2,
            ).also { reader ->
                reader.setOnImageAvailableListener({ availableReader ->
                    if (closed.get()) {
                        availableReader.acquireLatestImage()?.close()
                        return@setOnImageAvailableListener
                    }
                    if (usesNativeEncoder) {
                        availableReader.acquireLatestImage()?.use { image ->
                            injectBlankIfRequested(image)
                            if (!dropLeadingBlank(image)) queueNativeFrame(image)
                        }
                    } else {
                        val image = availableReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                        injectBlankIfRequested(image)
                        if (dropLeadingBlank(image)) {
                            copyCaptureLoss?.onDeliberatelyDropped(image.timestamp)
                            image.close()
                            return@setOnImageAvailableListener
                        }
                        pendingImage?.close()
                        pendingImage = image
                        queuePendingFrame()
                    }
                }, worker)
            }
            if (BuildConfig.DEBUG) {
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK camera_leading_blank: enabled=${DebugTunables.dropLeadingBlankFrames} | " +
                        "inject=${DebugTunables.injectBlankFrames} | path=${if (usesNativeEncoder) "native" else "image"} | " +
                        "encoder=$activeEncoderName",
                )
            }
        } else if (referenceRecorder != null) {
            referenceImageReader = ImageReader.newInstance(
                settings.resolution.width,
                settings.resolution.height,
                android.graphics.ImageFormat.YUV_420_888,
                3,
            ).also { reader ->
                reader.setOnImageAvailableListener({ availableReader ->
                    availableReader.acquireLatestImage()?.use(referenceRecorder::record)
                }, worker)
            }
        }

        val join = openJoin
        if (previewSurface != null) {
            markCameraOpenRequested("surface=ready")
            cameraManager.openCamera(checkNotNull(selectedCameraId), cameraCallback, worker)
        } else if (join != null) {
            // JT2 camera-first: the viewfinder surface is requested (it hops to the main thread) and the camera opens at once;
            // the session is configured when both are ready ([CameraOpenJoin]).
            viewfinderSurface = CameraViewfinderSurface(
                context,
                viewfinder,
                checkNotNull(cameraCharacteristics),
                settings.resolution.width,
                settings.resolution.height,
                scaleMode,
                { surface ->
                    worker.post {
                        if (!closed.get()) {
                            previewSurface = surface
                            onJoinStage?.invoke(CallJoinStage.PREVIEW_SURFACE_READY, "sinceOpenRequestedMs=${sinceOpenRequestedMs()} | order=parallel")
                            if (join.onSurfaceReady() == CameraOpenJoin.Action.CONFIGURE) configureCaptureSession()
                        }
                    }
                },
                { error ->
                    if (join.onSurfaceFailed() == CameraOpenJoin.Action.FAIL) {
                        notifyFailure("Camera preview surface failed: ${error.message ?: error.javaClass.simpleName}", error)
                    }
                },
            ).also(CameraViewfinderSurface::request)
            markCameraOpenRequested("surface=requested")
            cameraManager.openCamera(checkNotNull(selectedCameraId), cameraCallback, worker)
        } else {
            viewfinderSurface = CameraViewfinderSurface(
                context,
                viewfinder,
                checkNotNull(cameraCharacteristics),
                settings.resolution.width,
                settings.resolution.height,
                scaleMode,
                { surface ->
                    worker.post {
                        if (!closed.get()) {
                            previewSurface = surface
                            onJoinStage?.invoke(CallJoinStage.PREVIEW_SURFACE_READY, "sinceOpenRequestedMs=n/a | order=surface_first")
                            runCatching {
                                markCameraOpenRequested("surface=ready")
                                cameraManager.openCamera(checkNotNull(selectedCameraId), cameraCallback, worker)
                            }.onFailure { error ->
                                notifyFailure("Camera could not open: ${error.message}", error)
                            }
                        }
                    }
                },
                { error -> notifyFailure("Camera preview surface failed: ${error.message ?: error.javaClass.simpleName}", error) },
            ).also(CameraViewfinderSurface::request)
        }
    }

    /** DEBUG join stage: the camera open is about to be asked for (the first time only; a retry or rebuild reopens silently). */
    private fun markCameraOpenRequested(detail: String) {
        if (openRequestedNs == 0L) openRequestedNs = System.nanoTime()
        onJoinStage?.invoke(CallJoinStage.CAMERA_OPEN_REQUESTED, "$detail | camera=$openedCameraId")
    }

    private fun sinceOpenRequestedMs(): Long =
        if (openRequestedNs == 0L) -1L else (System.nanoTime() - openRequestedNs) / 1_000_000L

    /** The camera-to-encoder path, for the DEBUG join-stage lines. */
    private fun inputPathLabel(): String = when {
        debugRawInputFile != null -> "raw"
        gpuActive -> "gpu"
        usesNativeEncoder -> "native"
        videoCodec.usesSurfaceInput -> "surface"
        else -> "copy"
    }

    /** The camera's first completed capture of this encoder's own session (the GPU path's shared session reports none). */
    @Volatile private var cameraFirstFrameTold = false

    // ---- MediaCodec encoder initialisation (V-09, V-11, V-12, V-14, V-16) ----------------------------

    /** Other MediaCodec encoders for the same codec and input mode, best first (init fallback candidates). */
    private fun alternateEncoderNames(): List<String> {
        if (videoCodec.backend != VideoEncoderBackend.MEDIA_CODEC) return emptyList()
        return runCatching {
            VideoCodec.allMediaCodecEncoders().filter { other ->
                other.protocolId == videoCodec.protocolId &&
                    other.usesSurfaceInput == videoCodec.usesSurfaceInput &&
                    other.encoderName != videoCodec.encoderName &&
                    (activeBitrateMode == null || activeBitrateMode in other.supportedBitrateModes) &&
                    (!other.isHcCodec || other.automaticBlock == null && other.hardwareAccelerated == videoCodec.hardwareAccelerated)
            }.map { it.encoderName }.take(MAX_ALTERNATE_ENCODERS)
        }.getOrDefault(emptyList())
    }

    /**
     * The MediaCodec encoder is created, configured, given its input surface and started. User sessions go
     * through the encoder-init-fallback chain; benchmark launches, the DEBUG encoder bench and a switched-off
     * entry keep the former single attempt (plus the temporal-layering retry).
     */
    private fun startMediaCodecEncoder() {
        // Experimental bounded media age also verifies AV1 frames an encoder makes droppable on
        // its own (no layering is requested), so call queues can skip proven non-reference frames.
        // VP9 is excluded (see BoundedMediaAge.nonReferenceSkipAllowed).
        if (hcCodec == null && (experimentalTemporalLayers ||
                DebugTunables.experimentalBoundedMediaAge && videoCodec.mimeType == MediaFormat.MIMETYPE_VIDEO_AV1)
        ) {
            bitstreamDroppability = BitstreamDroppability(videoCodec.mimeType)
        }
        if (hcCodec != null) {
            hcVectors = HcTestVectorCapture.startIfRequested(context, videoCodec, settings, activeBitrateMode, debugRawInputFile != null)
            if (experimentalTemporalLayers && DebugTunables.hardwareTemporalLayers) {
                // Design HC: codecs 4 and 5 never get the DEBUG temporal-layer request (android.generic.2).
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK temporal_layering: encoder=$activeEncoderName | mime=${videoCodec.mimeType} | " +
                        "requested=$TEMPORAL_LAYERING_SCHEMA | effective=none | reason=hc_codec_excluded",
                )
            }
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "hc_encoder: codec=${hcCodec.label} | encoder=$activeEncoderName | profile=${videoCodec.profile ?: "none"} | " +
                    "level=${hcRequestedLevel() ?: "none"} | level_idc=${hcRequestedLevel()?.let { HcEncoderEligibility.levelIdc(videoCodec.protocolId, it) } ?: "none"} | " +
                    "level_listed=${videoCodec.level ?: "none"} | rate_mode_requested=CBR | " +
                    "rate_mode_effective=${HcBitrateModes.label(activeBitrateMode)} | " +
                    "declared=${videoCodec.supportedBitrateModes.joinToString("+") { HcBitrateModes.label(it) }.ifEmpty { "none" }} | " +
                    "prepend_header_key=unused | b_frames=0",
            )
        }
        if (CameraPolicy.encoderInitFallbackEnabled(deviceTraits, bench = bench != null)) {
            startWithInitFallback()
        } else {
            startSingleAttempt()
        }
    }

    /**
     * The MediaFormat for one attempt. [dropped] optional key groups are left out ([EncoderOptionalKey] order is
     * the drop order); [lowPriority] sets KEY_PRIORITY 1 (only after resource errors). Returns the format and
     * the optional key groups it carries. Required keys never change.
     */
    private fun buildEncoderFormat(
        dropped: Set<EncoderOptionalKey>,
        lowPriority: Boolean,
    ): Pair<MediaFormat, List<EncoderOptionalKey>> {
        val applied = mutableListOf<EncoderOptionalKey>()
        val format = MediaFormat.createVideoFormat(
            videoCodec.mimeType,
            settings.resolution.width,
            settings.resolution.height,
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                if (videoCodec.usesSurfaceInput && debugRawInputFile == null) {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                } else {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                },
            )
            val startupShaping = keyFrameShapingFraction()
            setInteger(
                MediaFormat.KEY_BIT_RATE,
                startupShaping?.let { (activeBitrate * it).toInt() } ?: activeBitrate,
            )
            if (startupShaping != null) shapedKeyFramePending = true
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, settings.fps.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSeconds)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            // Design HC: HEVC Main / H.264 High, Main or Constrained Baseline as the encoder lists them. Never
            // KEY_PREPEND_HEADER_TO_SYNC_FRAMES: the packetiser prepends the cached parameter sets itself.
            videoCodec.profile?.let { profile ->
                setInteger(MediaFormat.KEY_PROFILE, profile)
                hcRequestedLevel()?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
            }
            // KEY_OPERATING_RATE stays unset on purpose: a huge value crashes configure on several Qualcomm
            // and UniSoc parts (Media3 #810 / b/311206113), and a finite one has no measured benefit here.
            setInteger(MediaFormat.KEY_PRIORITY, if (lowPriority) 1 else 0)
            activeBitrateMode?.let { setInteger(MediaFormat.KEY_BITRATE_MODE, it) }
            if (EncoderOptionalKey.COMPLEXITY !in dropped) {
                activeComplexity?.let {
                    setInteger(MediaFormat.KEY_COMPLEXITY, it)
                    applied += EncoderOptionalKey.COMPLEXITY
                }
            }
            // Experimental (off): cyclic intra refresh instead of periodic keyframes, where the
            // encoder supports it; the applied period is read back from the output format.
            if (EncoderOptionalKey.INTRA_REFRESH !in dropped) {
                DebugTunables.intraRefreshFrames?.let {
                    setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, it)
                    applied += EncoderOptionalKey.INTRA_REFRESH
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && EncoderOptionalKey.QP_BOUNDS !in dropped) {
                var qpBounds = false
                DebugTunables.hardwareQpMin?.let { qp ->
                    setInteger(MediaFormat.KEY_VIDEO_QP_I_MIN, qp)
                    setInteger(MediaFormat.KEY_VIDEO_QP_P_MIN, qp)
                    qpBounds = true
                }
                DebugTunables.hardwareQpMax?.let { qp ->
                    setInteger(MediaFormat.KEY_VIDEO_QP_I_MAX, qp)
                    setInteger(MediaFormat.KEY_VIDEO_QP_P_MAX, qp)
                    qpBounds = true
                }
                if (qpBounds) applied += EncoderOptionalKey.QP_BOUNDS
            }
            encodingStatisticsEnabled = EncoderOptionalKey.STATISTICS !in dropped && supportsEncodingStatistics()
            if (encodingStatisticsEnabled) {
                setInteger(
                    MediaFormat.KEY_VIDEO_ENCODING_STATISTICS_LEVEL,
                    MediaFormat.VIDEO_ENCODING_STATISTICS_LEVEL_1,
                )
                applied += EncoderOptionalKey.STATISTICS
            }
            // Experimental temporal layering: two layers are requested from the hardware
            // encoder only with the extra DEBUG switch (error-resilient layered VP9 cost ~11 VMAF
            // on the M31); output frames are only marked droppable after their bitstream proves it.
            // Relay child thinning asks hardware AV1 for layers by itself; hardware VP9 (error-resilient
            // layered VP9 cost ~11 VMAF on the M31) only with the extra DEBUG switch.
            temporalLayeringRequested = EncoderOptionalKey.TEMPORAL_LAYERING !in dropped &&
                hcCodec == null && experimentalTemporalLayers && (
                DebugTunables.hardwareTemporalLayers ||
                    DebugTunables.relayChildThinning && videoCodec.mimeType == MediaFormat.MIMETYPE_VIDEO_AV1
                )
            if (temporalLayeringRequested) {
                setString(MediaFormat.KEY_TEMPORAL_LAYERING, TEMPORAL_LAYERING_SCHEMA)
                applied += EncoderOptionalKey.TEMPORAL_LAYERING
            }
            // Experimental (off, V-14): KEY_LATENCY=1, never for H.264 Baseline (crbug 409110228); first key dropped.
            if (EncoderOptionalKey.LATENCY !in dropped && CameraSwitches.encoderKeyLatency &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                encoderKeyLatencyAllowed(videoCodec.mimeType, Build.VERSION.SDK_INT, activeEncoderName)
            ) {
                setInteger(MediaFormat.KEY_LATENCY, 1)
                applied += EncoderOptionalKey.LATENCY
            }
            // Experimental (off, V-11): explicit BT.709 limited-range SDR so no stage infers the data space.
            if (EncoderOptionalKey.COLOUR !in dropped && CameraSwitches.encoderColourKeys) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                applied += EncoderOptionalKey.COLOUR
            }
        }
        // Experimental (off, V-12): the vendor QP range is set after configure; listed here so the ladder knows.
        if (EncoderOptionalKey.VENDOR_QP !in dropped && CameraSwitches.exynosQpRange != null &&
            ExynosQpRange.scopedComponent(activeEncoderName)
        ) {
            applied += EncoderOptionalKey.VENDOR_QP
        }
        return format to applied
    }

    /** The former behaviour: one create/configure/start (benchmark launches, encoder bench, entry off). */
    private fun startSingleAttempt() {
        val (format, applied) = buildEncoderFormat(emptySet(), false)
        val experimental = applied.filter { it == EncoderOptionalKey.LATENCY || it == EncoderOptionalKey.COLOUR || it == EncoderOptionalKey.VENDOR_QP }
        try {
            createConfigureStartSingle(format)
        } catch (error: Exception) {
            if (error is TemporalLayeringRefused || experimental.isEmpty()) throw error
            // An Experimental key was requested and the encoder refused it: once more without them, never silently.
            Log.w(
                "BluetoothVideo",
                "BENCHMARK encoder_experimental_keys: encoder=$activeEncoderName | requested=${experimental.joinToString("+") { it.label }} | " +
                    "effective=none | reason=${error.javaClass.simpleName}: ${error.message.orEmpty().take(120)}",
            )
            releaseFailedCodec()
            val retry = buildEncoderFormat(experimental.toSet(), false).first
            createConfigureStartSingle(retry)
        }
    }

    /**
     * The one `BENCHMARK temporal_layering` line per encoder start (requested vs effective, never silent). A DEBUG
     * benchmark launch that requested layers the encoder did not take is refused (`setup_refused:
     * reason=temporal_layering_unsupported`) instead of measuring a single layer; a user session stays best effort.
     */
    private fun reportTemporalLayering(requested: Boolean, effective: Boolean, encoder: String, reason: String) {
        val requestedSchema = if (requested) TEMPORAL_LAYERING_SCHEMA else null
        val effectiveSchema = if (requested && effective) TEMPORAL_LAYERING_SCHEMA else null
        val action = TemporalLayeringReport.action(requestedSchema, effectiveSchema, DebugTunables.benchmarkLaunch)
        val line = TemporalLayeringReport.line(requestedSchema, effectiveSchema, encoder, reason)
        if (action == TemporalLayeringReport.Action.SINGLE_LAYER_BEST_EFFORT) Log.w("BluetoothVideo", line)
        else Log.i("BluetoothVideo", line)
        if (action == TemporalLayeringReport.Action.REFUSE) {
            val refusal = BenchmarkSetupRefusal.temporalLayeringRefusalLine(TEMPORAL_LAYERING_SCHEMA, encoder, reason)
            Log.w("BluetoothVideo", refusal)
            throw TemporalLayeringRefused(refusal)
        }
    }

    private fun releaseFailedCodec() {
        val failed = codec
        codec = null
        failed?.let { BoundedCall.runQuietly("encoder-release", ENCODER_RELEASE_TIMEOUT_MS, { it.release() }) { msg -> Log.w("BluetoothVideo", msg) } }
        encoderSurface?.let { runCatching { it.release() } }
        encoderSurface = null
    }

    private fun createConfigureStartSingle(format: MediaFormat) {
        codec = MediaCodec.createByCodecName(activeEncoderName)
        hcPacketiser = hcCodec?.let(::AnnexBPacketiser)
        var configured = false
        val layeringAskedFor = temporalLayeringRequested
        if (temporalLayeringRequested) {
            try {
                checkNotNull(codec).setCallback(codecCallback, worker)
                timedConfigure(checkNotNull(codec), format)
                configured = true
            } catch (error: Exception) {
                // An encoder may reject the schema outright. A user session encodes single-layer (logged); a DEBUG
                // benchmark launch that asked for layers is refused instead (the case would test no layering).
                Log.w(
                    "BluetoothVideo",
                    "$activeEncoderName rejected temporal layering: ${error.message}; " +
                        "encoding a single layer",
                )
                codec?.release()
                codec = null
                temporalLayeringRequested = false
                format.removeKey(MediaFormat.KEY_TEMPORAL_LAYERING)
                reportTemporalLayering(
                    requested = true, effective = false, encoder = activeEncoderName,
                    reason = "configure_rejected: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
                codec = MediaCodec.createByCodecName(activeEncoderName)
            }
        }
        if (configured) {
            reportTemporalLayering(requested = true, effective = true, encoder = activeEncoderName, reason = "configure_accepted")
        } else if (!layeringAskedFor) {
            reportTemporalLayering(requested = false, effective = false, encoder = activeEncoderName, reason = "not_requested")
        }
        checkNotNull(codec).let { encoder ->
            if (!configured) {
                encoder.setCallback(codecCallback, worker)
                timedConfigure(encoder, format)
            }
            if (videoCodec.usesSurfaceInput && debugRawInputFile == null) {
                encoderSurface = encoder.createInputSurface()
            }
            applyVendorQpRange(encoder, activeEncoderName)
            logEncoderCapabilities(encoder)
            encoder.start()
        }
    }

    /** Chain: drop optional keys newest first, retry resource errors, then the next encoder of the same codec. */
    private fun startWithInitFallback() {
        val (_, requestedKeys) = buildEncoderFormat(emptySet(), false)
        val chain = EncoderInitChain(listOf(videoCodec.encoderName) + alternateEncoderNames(), requestedKeys)
        val outcome = try {
            chain.run({ attempt -> performEncoderInit(attempt) }) { error -> (error as? MediaCodec.CodecException)?.errorCode }
        } catch (exhausted: EncoderInitExhausted) {
            Log.w("BluetoothVideo", EncoderInitChain.benchmarkLine(videoCodec.encoderName, requestedKeys, null, exhausted))
            if (EncoderOptionalKey.TEMPORAL_LAYERING in requestedKeys) {
                Log.w(
                    "BluetoothVideo",
                    TemporalLayeringReport.line(TEMPORAL_LAYERING_SCHEMA, null, videoCodec.encoderName, "encoder_init_failed"),
                )
            }
            throw exhausted.cause ?: exhausted
        }
        Log.i("BluetoothVideo", EncoderInitChain.benchmarkLine(videoCodec.encoderName, requestedKeys, outcome, null))
        val effective = outcome.effective
        // Layering-specific line: the chain may have dropped the key (it is optional, newest first) without saying so.
        val layeringRequested = EncoderOptionalKey.TEMPORAL_LAYERING in requestedKeys
        val layeringDropped = layeringRequested && EncoderOptionalKey.TEMPORAL_LAYERING in effective.dropped
        reportTemporalLayering(
            requested = layeringRequested,
            effective = layeringRequested && !layeringDropped,
            encoder = effective.encoder,
            reason = TemporalLayeringReport.initFallbackReason(
                layeringRequested, layeringDropped, outcome.records.firstOrNull { it.error != null }?.error,
            ),
        )
        if (effective.encoder != videoCodec.encoderName || effective.dropped.isNotEmpty() || effective.lowPriority) {
            // Requested vs effective stays visible: a different component or fewer keys than asked for.
            onStatus(
                "Encoder fallback: requested ${videoCodec.encoderName}, using ${effective.encoder}" +
                    (effective.dropped.takeIf { it.isNotEmpty() }?.let { ", without ${it.joinToString("+") { key -> key.label }}" } ?: "") +
                    if (effective.lowPriority) ", low priority" else "",
            )
        }
    }

    /** One chain attempt: create, configure (bounded), input surface, vendor keys, start (bounded). Throws on any stage. */
    private fun performEncoderInit(attempt: EncoderInitAttempt) {
        activeEncoderName = attempt.encoder
        val (format, _) = buildEncoderFormat(attempt.dropped, attempt.lowPriority)
        var created: MediaCodec? = null
        var surface: Surface? = null
        try {
            // Published before start(), as the former path did: input-buffer callbacks use [codec] at once.
            val encoder = MediaCodec.createByCodecName(attempt.encoder).also { created = it; codec = it }
            hcPacketiser = hcCodec?.let(::AnnexBPacketiser)
            encoder.setCallback(codecCallback, worker)
            BoundedCall.run("encoder-configure", ENCODER_INIT_STAGE_TIMEOUT_MS) { timedConfigure(encoder, format) }
            if (videoCodec.usesSurfaceInput && debugRawInputFile == null) {
                surface = encoder.createInputSurface()
                encoderSurface = surface
            }
            if (EncoderOptionalKey.VENDOR_QP !in attempt.dropped) applyVendorQpRange(encoder, attempt.encoder)
            logEncoderCapabilities(encoder)
            BoundedCall.run("encoder-start", ENCODER_INIT_STAGE_TIMEOUT_MS) { encoder.start() }
        } catch (error: Throwable) {
            codec = null
            encoderSurface = null
            surface?.let { runCatching { it.release() } }
            created?.let { failed ->
                BoundedCall.runQuietly("encoder-release", ENCODER_RELEASE_TIMEOUT_MS, { failed.release() }) { msg ->
                    Log.w("BluetoothVideo", msg)
                }
            }
            throw error
        }
    }

    /** configure() with the first-call timing Chromium treats as suspicious above 800 ms. */
    private fun timedConfigure(encoder: MediaCodec, format: MediaFormat) {
        val startedNs = System.nanoTime()
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val ms = (System.nanoTime() - startedNs) / 1_000_000L
        if (ms > CONFIGURE_SLOW_MS) {
            Log.w("BluetoothVideo", "BENCHMARK encoder_configure_slow: encoder=$activeEncoderName | ms=$ms | thresholdMs=$CONFIGURE_SLOW_MS")
        }
    }

    /**
     * Experimental (V-12): the Exynos vendor QP range, only for the parameter names the component lists
     * (getSupportedVendorParameters on API 31+, or the input/output format keys), after configure and before start.
     */
    private fun applyVendorQpRange(encoder: MediaCodec, name: String) {
        val range = CameraSwitches.exynosQpRange ?: return
        if (!ExynosQpRange.scopedComponent(name)) return
        val listed = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { listed += encoder.supportedVendorParameters }
        runCatching { listed += encoder.inputFormat.keys }
        runCatching { listed += encoder.outputFormat.keys }
        val parameters = ExynosQpRange.parameters(listed, range)
        if (parameters.isEmpty()) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK vendor_qp_range: encoder=$name | requested=${range.first}-${range.last} | applied=none | " +
                    "reason=component lists no sec-ext-enc-qp-range keys",
            )
            return
        }
        val result = runCatching {
            encoder.setParameters(Bundle().apply { parameters.forEach { (key, value) -> putInt(key, value) } })
        }
        Log.i(
            "BluetoothVideo",
            "BENCHMARK vendor_qp_range: encoder=$name | requested=${range.first}-${range.last} | " +
                "applied=${parameters.entries.joinToString(",") { "${it.key}=${it.value}" }} | ok=${result.isSuccess}",
        )
    }

    private fun startRawInput(file: File) {
        val width = settings.resolution.width
        val height = settings.resolution.height
        val declared = rawGeometry ?: (width to height)
        val check = RawFixtureGeometry.check(file.length(), declared.first, declared.second, width, height)
        Log.i("BluetoothVideo", RawFixtureGeometry.line(file.name, file.length(), rawGeometry, width, height, check))
        if (!check.ok) {
            // Visible refusal (never a silent change of geometry): requested = the fixture's declared size.
            Log.w(
                "BluetoothVideo",
                BenchmarkSetupRefusal.rawFixtureGeometryLine(declared.first, declared.second, width, height, check.reason),
            )
        }
        require(check.ok) { "Raw fixture refused: ${check.reason}" }
        val frameBytes = checkNotNull(RawFixtureGeometry.frameBytes(width, height))
        require(frameBytes in 1..Int.MAX_VALUE.toLong()) { "Raw frame is too large" }
        rawFrame = ByteArray(frameBytes.toInt())
        rawInput = RandomAccessFile(file, "r")
        worker.post(rawFeed)
    }

    private val rawFeed = object : Runnable {
        override fun run() {
            if (closed.get()) return
            if (rawStartedUs == 0L) {
                if (!usesNativeEncoder && pendingInputBuffers.isEmpty()) {
                    worker.postDelayed(this, 1L)
                    return
                }
                rawStartedNs = System.nanoTime()
                rawStartedUs = SystemClock.elapsedRealtimeNanos() / 1_000L
                onJoinStage?.invoke(CallJoinStage.CAMERA_FIRST_FRAME, "path=raw")
                rawNextFrameNs = rawStartedNs
                val file = checkNotNull(debugRawInputFile)
                val width = settings.resolution.width
                val height = settings.resolution.height
                val frameBytes = width.toLong() * height * 3 / 2
                Log.i("BluetoothVideo", "Benchmark raw source: ${file.name}, ${file.length() / frameBytes} looping I420 frames, ${width}x$height at ${settings.fps} FPS, ${activeEncoderName}, ${settings.bitRate / 1_000} kbps, ${rateControl.label}, frame-zero PTS $rawStartedUs us; Camera2 and physical motion unmeasured")
                onStatus("Streaming ${videoCodec.displayName} at ${width}x$height, ${settings.fps} FPS, " +
                    "${settings.bitRate / 1_000} kbps, ${rateControl.label} mode $activeBitrateMode (recorded raw motion fixture)")
            }
            val nowNs = System.nanoTime()
            val periodNs = 1_000_000_000L / rawInputFps
            if (nowNs < rawNextFrameNs) {
                worker.postDelayed(this, maxOf(1L, (rawNextFrameNs - nowNs) / 1_000_000L))
                return
            }
            val missedFrames = (nowNs - rawNextFrameNs) / periodNs
            rawInputDrops += missedFrames
            rawNextFrameNs += missedFrames * periodNs
            val sourceFrame = ((rawNextFrameNs - rawStartedNs) * settings.fps /
                1_000_000_000.0).roundToLong()
            val presentationTimeUs = rawStartedUs + sourceFrame * 1_000_000L / settings.fps
            debugStageTrace?.record("raw_capture", presentationTimeUs, checkNotNull(rawFrame).size)
            try {
                val input = checkNotNull(rawInput)
                val frame = checkNotNull(rawFrame)
                val fixtureFrame = sourceFrame % (input.length() / frame.size)
                if (usesNativeEncoder) {
                    val buffers = checkNotNull(nativeInputBuffers)
                    val target = buffers.poll()
                    if (target == null) {
                        rawInputDrops++
                    } else {
                        try {
                            input.seek(fixtureFrame * frame.size)
                            input.readFully(frame)
                            // Detection only: libaom already caps inter frames at the link budget.
                            inspectEncoderInput(presentationTimeUs, width = settings.resolution.width,
                                height = settings.resolution.height) {
                                lumaPlaneOf(frame, 0, settings.resolution.width)
                            }
                            target.clear()
                            target.put(frame).flip()
                            if (nativeWorker?.post {
                                    try { encodeNativeFrame(target, presentationTimeUs) }
                                    finally { target.clear(); buffers.offer(target) }
                                } != true
                            ) buffers.offer(target)
                        } catch (error: Exception) {
                            buffers.offer(target)
                            throw error
                        }
                    }
                } else {
                    val inputIndex = pendingInputBuffers.pollFirst()
                    if (inputIndex == null) {
                        rawInputDrops++
                    } else {
                        input.seek(fixtureFrame * frame.size)
                        input.readFully(frame)
                        val encoder = checkNotNull(codec)
                        val image = encoder.getInputImage(inputIndex)
                            ?: error("${encoder.name} does not expose a raw YUV input image")
                        copyRawI420(frame, image)
                        inspectEncoderInput(presentationTimeUs, width = settings.resolution.width,
                            height = settings.resolution.height) {
                            lumaPlaneOf(frame, 0, settings.resolution.width)
                        }
                        if (queuedInputTimesNs.size >= 32) queuedInputTimesNs.remove(queuedInputTimesNs.keys.first())
                        queuedInputTimesNs[presentationTimeUs] = System.nanoTime()
                        debugStageTrace?.record("encode_input", presentationTimeUs, frame.size)
                        encoder.queueInputBuffer(inputIndex, 0, frame.size, presentationTimeUs, 0)
                    }
                }
            } catch (error: Exception) {
                notifyFailure("Raw fixture input failed: ${error.message}", error)
                return
            }
            rawNextFrameNs += periodNs
            worker.postDelayed(this, maxOf(1L, (rawNextFrameNs - System.nanoTime()) / 1_000_000L))
        }
    }

    private fun copyRawI420(frame: ByteArray, image: Image) {
        var sourceOffset = 0
        image.planes.forEachIndexed { index, plane ->
            val width = settings.resolution.width / if (index == 0) 1 else 2
            val height = settings.resolution.height / if (index == 0) 1 else 2
            val target = plane.buffer.duplicate()
            val targetOffset = target.position()
            repeat(height) { row ->
                val targetRow = targetOffset + row * plane.rowStride
                if (plane.pixelStride == 1) {
                    target.position(targetRow)
                    target.put(frame, sourceOffset + row * width, width)
                } else {
                    repeat(width) { column ->
                        target.put(targetRow + column * plane.pixelStride, frame[sourceOffset + row * width + column])
                    }
                }
            }
            sourceOffset += width * height
        }
    }

    private val cameraCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(openedCamera: CameraDevice) {
            if (closed.get()) {
                openedCamera.close()
                return
            }
            camera = openedCamera
            openRetry.reset()
            // A reopen for a session rebuild creates its session through the rebuilder (V-01).
            if (rebuilder.onDeviceOpened()) return
            onJoinStage?.invoke(CallJoinStage.CAMERA_OPENED, "openMs=${sinceOpenRequestedMs()} | joined=${openJoin != null}")
            // Camera-first: the session waits here for the preview surface when it is not ready yet (CameraOpenJoin).
            if (openJoin != null && openJoin.onCameraOpened() != CameraOpenJoin.Action.CONFIGURE) return
            configureCaptureSession()
        }

        override fun onClosed(closedCamera: CameraDevice) {
            openJoin?.onCameraGone()
            rebuilder.onDeviceClosed()
        }

        override fun onDisconnected(disconnectedCamera: CameraDevice) {
            openJoin?.onCameraGone()
            disconnectedCamera.close()
            notifyFailure("Camera disconnected.")
        }

        override fun onError(failedCamera: CameraDevice, error: Int) {
            openJoin?.onCameraGone()
            failedCamera.close()
            if (closed.get()) return
            // Transient errors are retried, policy errors explained, anything else reported as before (V-05).
            val decision = openRetry.next(error)
            Log.i(
                "BluetoothVideo",
                CameraOpenPolicy.benchmarkLine(decision, error, openRetry.attemptOf(error), openRetry.elapsedMs),
            )
            val delayMs = decision.retryDelayMs
            val id = openedCameraId
            if (delayMs == null || id == null) {
                notifyFailure(decision.message)
                return
            }
            captureSessionGeneration++
            session = null
            if (camera === failedCamera) camera = null
            rebuilder.cancel()
            onStatus(decision.message)
            worker.postDelayed({
                if (!closed.get()) {
                    @SuppressLint("MissingPermission")
                    val reopen = runCatching { cameraManager.openCamera(id, cameraCallback, worker) }
                    reopen.exceptionOrNull()?.let { failure -> notifyFailure("Camera could not reopen: ${failure.message}", failure) }
                }
            }, delayMs)
        }
    }

    /** Replaces a SurfaceView surface that Android discarded while the activity was backgrounded. */
    fun refreshViewfinderSurface() {
        gpuCapture?.let { capture ->
            if (!closed.get()) capture.refreshViewfinderSurface()
            return
        }
        if (closed.get() || (camera == null && !rebuilder.busy)) return
        val cameraId = cameraManager.cameraIdFor(cameraLens) ?: return
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        viewfinderSurface?.close()
        viewfinderSurface = null
        previewSurface = null
        CameraViewfinderSurface(
            context,
            viewfinder,
            characteristics,
            settings.resolution.width,
            settings.resolution.height,
            scaleMode,
            { surface ->
                if (!closed.get()) {
                    previewSurface = surface
                    worker.post {
                        if (!closed.get()) rebuildCaptureSession()
                    }
                }
            },
            { error ->
                if (!closed.get()) {
                    notifyFailure(
                        "Camera preview surface failed: ${error.message ?: error.javaClass.simpleName}",
                        error,
                    )
                }
            },
        ).also { replacement ->
            viewfinderSurface = replacement
            replacement.request()
        }
    }

    /**
     * Rebuilds the session after the surface set changed (viewfinder refresh, template change). The mode comes
     * from the device-family policy: the former immediate re-create, or waiting for onClosed, or a device
     * reopen (V-01); see [CameraSessionRebuilder].
     */
    private fun rebuildCaptureSession() {
        if (closed.get()) return
        rebuilder.rebuild(rebuildMode)
    }

    private fun surfacesFor(plan: CameraStreamPlan, videoInputSurface: Surface): List<Surface> = listOfNotNull(
        previewSurface.takeIf { plan.preview },
        videoInputSurface,
        referenceImageReader?.surface.takeIf { plan.reference },
    )

    /**
     * Diagnostic (V-02): whether the platform says [targets] are a supported combination; true, false, or
     * null when it cannot say (API below 29, an HAL without the query, any failure).
     */
    private fun sessionConfigurationSupported(device: CameraDevice, targets: List<Surface>): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val configuration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                targets.map { OutputConfiguration(it) },
                java.util.concurrent.Executor { command -> worker.post(command) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) = Unit
                    override fun onConfigureFailed(session: CameraCaptureSession) = Unit
                },
            )
            device.isSessionConfigurationSupported(configuration)
        }.getOrNull()
    }

    /** Creates the capture session on the open camera: the first session, or the next one after a rebuild closed the old. */
    private fun configureCaptureSession() {
        val openedCamera = camera ?: return
        val videoInputSurface = encoderSurface ?: imageReader?.surface ?: return
        val generation = ++captureSessionGeneration
        val requested = CameraStreamPlan("full", previewSurface != null, referenceImageReader != null)
        val queryStartNs = System.nanoTime()
        val requestedSupported = sessionConfigurationSupported(openedCamera, surfacesFor(requested, videoInputSurface))
        sessionQueryMs = (System.nanoTime() - queryStartNs) / 1_000_000L
        if (BuildConfig.DEBUG && onJoinStage != null && CallJoinStages.current.claimOnce("camera_caps")) logCameraCaps(openedCamera)
        var plan = requested
        val viaSessionConfiguration = CameraSwitches.sessionConfiguration
        if (viaSessionConfiguration) {
            // Experimental: an unsupported set drops the reference reader, then the preview; recorded as requested != effective.
            val chosen = CameraStreamPlanner.firstSupported(
                CameraStreamPlanner.plans(requested.preview, requested.reference),
            ) { candidate ->
                if (candidate == requested) requestedSupported else sessionConfigurationSupported(openedCamera, surfacesFor(candidate, videoInputSurface))
            }
            if (chosen == null) {
                Log.w("BluetoothVideo", "BENCHMARK camera_session: " + CameraStreamPlanner.effectiveNote(requested, null))
                notifyFailure("Camera stream combination ${requested.streams} is not supported.")
                return
            }
            plan = chosen
        }
        activePlan = plan
        val targets = surfacesFor(plan, videoInputSurface)
        captureTargets = targets
        logCameraSession(requested, plan, requestedSupported, viaSessionConfiguration)
        val callback = captureSessionCallback(generation)
        sessionCreateNs = System.nanoTime()
        runCatching {
            if (viaSessionConfiguration) {
                val configuration = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    targets.map { OutputConfiguration(it) },
                    java.util.concurrent.Executor { command -> worker.post(command) },
                    callback,
                )
                // The AE fps range is a session parameter: the first request then needs no reconfiguration.
                configuration.sessionParameters = buildCaptureRequest(openedCamera, targets)
                openedCamera.createCaptureSession(configuration)
            } else {
                @Suppress("DEPRECATION")
                openedCamera.createCaptureSession(targets, callback, worker)
            }
        }.onFailure { error ->
            notifyFailure("Camera capture session could not start: ${error.message}", error)
        }
    }

    private fun captureSessionCallback(generation: Int): CameraCaptureSession.StateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(configuredSession: CameraCaptureSession) {
            if (closed.get() || generation != captureSessionGeneration) {
                configuredSession.close()
                return
            }
            session = configuredSession
            onJoinStage?.invoke(
                CallJoinStage.CAMERA_SESSION_CONFIGURED,
                "createToConfiguredMs=${(System.nanoTime() - sessionCreateNs) / 1_000_000L} | targets=${captureTargets.size} | " +
                    "sinceOpenRequestedMs=${sinceOpenRequestedMs()}",
            )
            applyRepeatingRequest()
            val cameraFps = targetFpsRange?.let { "camera ${it.lower}–${it.upper} FPS" }
                ?: "camera default FPS"
            onStatus(streamingStatus(cameraFps))
            armStallWatchdog(generation)
        }

        override fun onConfigureFailed(failedSession: CameraCaptureSession) {
            failedSession.close()
            if (generation == captureSessionGeneration && !closed.get()) {
                notifyFailure("Camera capture session could not be configured.")
            }
        }

        override fun onClosed(closedSession: CameraCaptureSession) {
            if (closedSession === closingSession) {
                closingSession = null
                rebuilder.onSessionClosed()
            }
        }
    }

    /**
     * DEBUG (JT2): what the camera's first completed capture says about the start: how long after the exposure began the result
     * arrived (sensor timestamp on the real-time clock only), the auto-exposure state and the exposure and gain it chose.
     */
    private fun firstCaptureDetail(result: TotalCaptureResult): String {
        val sensorNs = result.get(CaptureResult.SENSOR_TIMESTAMP)
        val lagMs = if (cameraTimestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME && sensorNs != null) {
            (SystemClock.elapsedRealtimeNanos() - sensorNs) / 1_000_000L
        } else {
            null
        }
        val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        return "sensorLagMs=${lagMs ?: "unknown"} | aeState=${aeStateName(result.get(CaptureResult.CONTROL_AE_STATE))} | " +
            "exposureUs=${exposureNs?.let { it / 1_000L } ?: "unknown"} | iso=${result.get(CaptureResult.SENSOR_SENSITIVITY) ?: "unknown"}"
    }

    private fun aeStateName(state: Int?): String = when (state) {
        null -> "unknown"
        CaptureResult.CONTROL_AE_STATE_INACTIVE -> "inactive"
        CaptureResult.CONTROL_AE_STATE_SEARCHING -> "searching"
        CaptureResult.CONTROL_AE_STATE_CONVERGED -> "converged"
        CaptureResult.CONTROL_AE_STATE_LOCKED -> "locked"
        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "flash_required"
        CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "precapture"
        else -> "other($state)"
    }

    /**
     * DEBUG, once per call (JT2): what the camera HAL offers for a faster, steadier start. `sessionKeys` are the request keys the
     * HAL wants at session creation (the AE fps range among them avoids a reconfiguration at the first request), the stabilization
     * lists are the modes the phone offers, `recordDefault*` what the RECORD template sets by itself, and `postOpenQueryMs` how
     * long the post-open `isSessionConfigurationSupported` query took (it runs before the session is created).
     */
    private fun logCameraCaps(openedCamera: CameraDevice) {
        val id = openedCameraId ?: return
        runCatching {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val sessionKeys = safeCameraRead("AVAILABLE_SESSION_KEYS") { characteristics.availableSessionKeys }.orEmpty()
            val sessionKeyNames = sessionKeys.map { it.name }
            fun sessionKey(key: CaptureRequest.Key<*>) = key.name in sessionKeyNames
            val videoStabilization = safeCameraRead("CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES") {
                characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            }
            val opticalStabilization = safeCameraRead("LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION") {
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            }
            val pipelineMaxDepth = safeCameraRead("REQUEST_PIPELINE_MAX_DEPTH") {
                characteristics.get(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH)
            }
            val record = runCatching { openedCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD) }.getOrNull()
            val deviceSetup = if (Build.VERSION.SDK_INT >= 35) {
                runCatching { cameraManager.isCameraDeviceSetupSupported(id) }.getOrNull()
            } else {
                null
            }
            Log.i(
                "BluetoothVideo",
                "BENCHMARK camera_caps: id=$id | hardwareLevel=${CameraLevelCodes.name(hardwareLevel)} | " +
                    "sessionKeys=${sessionKeys.size} | sessionKeyAeTargetFpsRange=${sessionKey(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)} | " +
                    "sessionKeyVideoStabilization=${sessionKey(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE)} | " +
                    "sessionKeyOpticalStabilization=${sessionKey(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)} | " +
                    "videoStabilizationModes=${videoStabilization?.joinToString("+") ?: "unknown"} | " +
                    "opticalStabilizationModes=${opticalStabilization?.joinToString("+") ?: "unknown"} | " +
                    "recordDefaultVideoStabilization=${record?.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE) ?: "unset"} | " +
                    "recordDefaultOpticalStabilization=${record?.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE) ?: "unset"} | " +
                    "recordDefaultAeRange=${record?.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE) ?: "unset"} | " +
                    "pipelineMaxDepth=${pipelineMaxDepth ?: "unknown"} | cameraDeviceSetupSupported=${deviceSetup ?: "n/a"} | " +
                    "postOpenQueryMs=$sessionQueryMs | sinceOpenRequestedMs=${sinceOpenRequestedMs()}",
            )
        }
    }

    private fun templateName(): String = if (captureTemplate == CameraDevice.TEMPLATE_PREVIEW) "PREVIEW" else "RECORD"

    /** BENCHMARK camera_session: hardware level, stream set requested vs effective, the platform's answer, the AE range. */
    private fun logCameraSession(
        requested: CameraStreamPlan,
        effective: CameraStreamPlan,
        supported: Boolean?,
        sessionConfiguration: Boolean,
    ) {
        val fpsRange = targetFpsRange?.let { FpsRange(it.lower, it.upper) }
        val effectiveFps = CameraFpsRanges.effectiveMaxFps(fpsRange, streamMinFrameDurationsNs(effective))
        Log.i(
            "BluetoothVideo",
            "BENCHMARK camera_session: id=$openedCameraId | hardwareLevel=${CameraLevelCodes.name(hardwareLevel)} | " +
                "${CameraStreamPlanner.effectiveNote(requested, effective)} | " +
                "isSessionConfigurationSupported=${supported ?: "unknown"} | " +
                "path=${if (sessionConfiguration) "session_configuration" else "legacy_list"} | template=${templateName()} | " +
                "rebuildMode=$rebuildMode | aeRange=${fpsRange ?: "default"} | requestedFps=${settings.fps} | " +
                "effectiveMaxFps=${effectiveFps ?: "unknown"}",
        )
    }

    /** Minimum frame durations (ns) of the configured streams: a big extra stream caps the frame rate (V-03). */
    private fun streamMinFrameDurationsNs(plan: CameraStreamPlan): List<Long> {
        val id = openedCameraId ?: return emptyList()
        val map = runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()?.let { characteristics ->
            safeCameraRead("SCALER_STREAM_CONFIGURATION_MAP") {
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            }
        } ?: return emptyList()
        val durations = mutableListOf<Long>()
        runCatching {
            durations += if (videoCodec.usesSurfaceInput) {
                map.getOutputMinFrameDuration(MediaCodec::class.java, android.util.Size(settings.resolution.width, settings.resolution.height))
            } else {
                map.getOutputMinFrameDuration(android.graphics.ImageFormat.YUV_420_888, android.util.Size(cameraInputWidth, cameraInputHeight))
            }
        }
        if (plan.reference) {
            runCatching {
                durations += map.getOutputMinFrameDuration(
                    android.graphics.ImageFormat.YUV_420_888,
                    android.util.Size(settings.resolution.width, settings.resolution.height),
                )
            }
        }
        if (plan.preview) {
            runCatching {
                durations += map.getOutputMinFrameDuration(
                    android.view.SurfaceHolder::class.java,
                    android.util.Size(settings.resolution.width, settings.resolution.height),
                )
            }
        }
        return durations
    }

    /** BENCHMARK camera_geometry (V-21): the facts framing and rotation depend on, incl. the largest output size. */
    private fun logCameraGeometry(cameraId: String?, characteristics: CameraCharacteristics) {
        val sensorOrientation = safeCameraRead("SENSOR_ORIENTATION") { characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) }
        val facing = safeCameraRead("LENS_FACING") { characteristics.get(CameraCharacteristics.LENS_FACING) }
        val largest = runCatching {
            val map = safeCameraRead("SCALER_STREAM_CONFIGURATION_MAP") {
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            }
            val sizes = if (videoCodec.usesSurfaceInput) map?.getOutputSizes(MediaCodec::class.java)
            else map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
            sizes?.maxByOrNull { it.width.toLong() * it.height }?.let { "${it.width}x${it.height}" }
        }.getOrNull()
        Log.i(
            "BluetoothVideo",
            "BENCHMARK camera_geometry: id=$cameraId | lensFacing=${facing ?: "unknown"} | sensorOrientation=${sensorOrientation ?: "unknown"} | " +
                "largestOutput=${largest ?: "unknown"} | requested=${settings.resolution.width}x${settings.resolution.height} | " +
                "encoderInput=${cameraInputWidth}x$cameraInputHeight | hardwareLevel=${CameraLevelCodes.name(hardwareLevel)}",
        )
    }

    // ---- Frame-stall watchdog (V-06) ------------------------------------------------------------

    private fun cancelStallChecks() {
        stallChecks.forEach(worker::removeCallbacks)
        stallChecks.clear()
    }

    private fun armStallWatchdog(generation: Int) {
        cancelStallChecks()
        if (debugRawInputFile != null || gpuActive || !CameraPolicy.isOn("camera-frame-stall-watchdog", deviceTraits)) return
        val baseFrames = framesEncoded.get()
        val armedMs = SystemClock.elapsedRealtime()
        for (delayMs in longArrayOf(FrameStallWatchdog.FIRST_FRAME_MS, FrameStallWatchdog.WINDOW_MS)) {
            val check = Runnable { checkFrameStall(generation, baseFrames, armedMs) }
            stallChecks += check
            worker.postDelayed(check, delayMs)
        }
    }

    private fun checkFrameStall(generation: Int, baseFrames: Long, armedMs: Long) {
        if (closed.get() || generation != captureSessionGeneration || stallReportedGeneration == generation) return
        val elapsedMs = SystemClock.elapsedRealtime() - armedMs
        val frames = (framesEncoded.get() - baseFrames).toInt()
        val verdict = FrameStallWatchdog.verdict(elapsedMs, frames)
        if (verdict == FrameStallWatchdog.Verdict.OK) return
        stallReportedGeneration = generation
        val step = stallLadder.next()
        Log.w(
            "BluetoothVideo",
            FrameStallWatchdog.benchmarkLine(verdict, elapsedMs, frames, templateName(), activePlan.streams, stallLadder.responseLabel(step)),
        )
        if (step == FrameStallLadder.Step.TEMPLATE_PREVIEW) {
            // Experimental response: requested TEMPLATE_RECORD, effective TEMPLATE_PREVIEW; the log above says so.
            captureTemplate = CameraDevice.TEMPLATE_PREVIEW
            onStatus("Camera delivered no frames; retrying with the preview template.")
            rebuildCaptureSession()
        }
    }

    private fun streamingStatus(cameraFps: String): String = "Streaming ${videoCodec.displayName} at " +
        "${settings.resolution.width}×${settings.resolution.height}, ${settings.fps} FPS, " +
        "${settings.bitRate / 1_000} kbps, ${settings.latencyMs} ms budget, " +
        "${rateControl.label} mode $activeBitrateMode" +
        (activeComplexity?.let { ", complexity $it" } ?: "") +
        (nativeSpeedGovernor?.cpuUsed?.let { ", $nativeLabel cpu-used $it" } ?: "") +
        " ($cameraFps)"

    /**
     * Marks the next [close] as a ladder rung change: the shared GPU capture then stays open for a
     * moment so the next encoder finds the camera running (no camera restart). Any other close
     * releases the camera at once.
     */
    fun retainCaptureForRestart() {
        retainGpuCaptureOnClose = true
    }

    /**
     * GPU scale ladder: this encoder's input surface joins the shared camera capture, which draws every
     * frame into it at [VideoSettings.resolution] (the rung). Logs the encoder reset time (codec create,
     * configure, start and attach) and, at the first frame, the time to the first encoded output.
     */
    private fun startGpuCapture() {
        val request = checkNotNull(gpuScale)
        val surface = checkNotNull(encoderSurface) { "GPU scale needs the encoder's input surface" }
        val capture = try {
            GpuScaleCapture.obtain(context, viewfinder, cameraLens, scaleMode, request, reusableViewfinderSurface)
        } catch (error: Throwable) {
            reusableViewfinderSurface?.second?.close()
            throw error
        }
        gpuCapture = capture
        capture.attach(this, surface, settings.resolution.width, settings.resolution.height, gpuListener)
        gpuResetMs = (System.nanoTime() - gpuStartedNs) / 1_000_000.0
        Log.i(
            "BluetoothVideo",
            "BENCHMARK gpu_scale: rung=${settings.resolution.width}x${settings.resolution.height} | " +
                "encoderResetMs=${"%.1f".format(gpuResetMs)} (codec create+configure+start+attach) | " +
                "session=${request.session.width}x${request.session.height}@${request.fps} | ${capture.statsLine()}",
        )
    }

    private val gpuListener = object : GpuScaleCapture.Listener {
        override fun onStatus(message: String) {
            if (!closed.get()) this@CameraVideoEncoder.onStatus(message)
        }

        override fun onFailure(error: Throwable) {
            notifyFailure(error.message ?: error.javaClass.simpleName, error)
        }

        override fun onConfigured(cameraFpsText: String) {
            if (!closed.get()) this@CameraVideoEncoder.onStatus(streamingStatus(cameraFpsText))
        }
    }

    private fun notifyFailure(message: String, error: Throwable = IllegalStateException(message)) {
        if (closed.get()) return
        onStatus(message)
        onFailure(error)
    }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (closed.get()) return
            if (!videoCodec.usesSurfaceInput || debugRawInputFile != null) {
                pendingInputBuffers.addLast(index)
                if (debugRawInputFile == null) queuePendingFrame()
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (closed.get()) return
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val encoded = ByteArray(info.size)
                buffer.get(encoded)
                // Design HC: Annex B packetising (no config packet, sets prepended to IDRs, keyframe from the NAL
                // type); null means nothing is sent for this buffer.
                val hc = if (hcCodec != null) {
                    hcOutput(encoded, info) ?: run {
                        codec.releaseOutputBuffer(index, false)
                        return
                    }
                } else {
                    null
                }
                val bytes = hc?.payload ?: encoded
                // The droppability verifier (experiments only, null otherwise) also sees configuration
                // and keyframes so it knows the AV1 sequence header before judging inter frames.
                val flags = encodedBufferFlags(
                    bitstreamDroppability,
                    codecConfig = hc == null && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0,
                    keyFrame = hc?.keyFrame ?: (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0),
                    bytes = bytes,
                )
                if (flags and StreamProtocol.FLAG_KEY_FRAME != 0) {
                    val shaped = shapedKeyFramePending
                    if (shaped) {
                        worker.removeCallbacks(restoreShapedTarget)
                        worker.post(restoreShapedTarget)
                    }
                    onStatus("Encoded keyframe: ${info.size} bytes${if (shaped) " (shaped)" else ""}")
                }
                if (!firstVideoFrameSeen && flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                    firstVideoFrameSeen = true
                    if (gpuActive) {
                        Log.i(
                            "BluetoothVideo",
                            "BENCHMARK gpu_scale: rung=${settings.resolution.width}x${settings.resolution.height} | " +
                                "firstFrameMs=${"%.1f".format((System.nanoTime() - gpuStartedNs) / 1_000_000.0)} " +
                                "(encoder start to first output) | keyframe=${flags and StreamProtocol.FLAG_KEY_FRAME != 0}",
                        )
                    }
                    onFirstVideoFrame()
                }
                if (flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                    framesEncoded.incrementAndGet()
                    bitrateProbe.onFrame(System.nanoTime(), info.size)?.let { response ->
                        Log.i("BluetoothVideo", bitrateProbe.line(response, activeEncoderName, videoCodec.mimeType))
                    }
                    queuedInputTimesNs.remove(info.presentationTimeUs)?.let { queuedAtNs ->
                        inputToEncodedOutput.recordNanos(System.nanoTime() - queuedAtNs)
                    }
                    val transportPresentationTimeUs = normalizedCodecPresentationTimeUs(
                        info.presentationTimeUs,
                        SystemClock.elapsedRealtimeNanos() / 1_000L,
                    )
                    // Diagnostic (on): this frame's VP9 quantizer, parsed from its uncompressed header.
                    val vp9Frame = if (isVp9) Vp9FrameHeader.accessUnit(bytes) else null
                    vp9Frame?.let { frame ->
                        lastVp9QIdx = frame.baseQIdx
                        quantizerWindow.record(frame.baseQIdx)
                    }
                    burstAccumulator.record(info.size, activeBitrate)
                    recordBitrateAdjusterFrame(info.presentationTimeUs, info.size, vp9Frame)
                    afterEncodedFrame(info.presentationTimeUs, info.size, flags, vp9Frame)
                    val frameFormat = if (encodingStatisticsEnabled) {
                        runCatching { codec.getOutputFormat(index) }.getOrNull()
                    } else {
                        null
                    }
                    val qp = frameFormat?.takeIf { it.containsKey(MediaFormat.KEY_VIDEO_QP_AVERAGE) }
                        ?.getInteger(MediaFormat.KEY_VIDEO_QP_AVERAGE)
                    val pictureType = frameFormat?.takeIf { it.containsKey(MediaFormat.KEY_PICTURE_TYPE) }
                        ?.getInteger(MediaFormat.KEY_PICTURE_TYPE)
                    if (qp != null && !qpScaleLogged) {
                        qpScaleLogged = true
                        Log.i("BluetoothVideo", QpScale.benchmarkLine(activeEncoderName, videoCodec.mimeType, qp))
                    }
                    val encodeLatencyUs =
                        SystemClock.elapsedRealtimeNanos() / 1_000L - info.presentationTimeUs
                    val encodeLatencyMs = encodeLatencyUs.takeIf { it in 0..MAX_TRACE_ENCODE_LATENCY_US }
                        ?.div(1_000.0)
                    if (encodeLatencyMs != null) captureToEncodedOutput.recordNanos(encodeLatencyUs * 1_000L)
                    debugMediaTrace?.record(
                        transportPresentationTimeUs,
                        flags,
                        bytes,
                        qp,
                        pictureType,
                        encodeLatencyMs,
                        activeBitrate,
                        "pre_transport",
                    )
                    val previousPresentationTimeUs = lastEncodedPresentationTimeUs
                    if (previousPresentationTimeUs != Long.MIN_VALUE &&
                        info.presentationTimeUs > previousPresentationTimeUs
                    ) {
                        encodedFrameInterval.recordNanos(
                            (info.presentationTimeUs - previousPresentationTimeUs) * 1_000L,
                        )
                    }
                    lastEncodedPresentationTimeUs = info.presentationTimeUs
                    if (cadenceReportStartedNs == 0L) cadenceReportStartedNs = System.nanoTime()
                    cadenceReportFrames++
                    cadenceReportBytes += info.size
                    reportEncoderCadenceIfDue()
                    debugStageTrace?.record(
                        "encode_output",
                        transportPresentationTimeUs,
                        bytes.size,
                        extra = vp9Frame?.let { mapOf("q_idx" to it.baseQIdx, "frame_type" to it.label) },
                    )
                    onPacket(flags, transportPresentationTimeUs, bytes)
                } else {
                    onPacket(flags, info.presentationTimeUs, bytes)
                }
            }
            codec.releaseOutputBuffer(index, false)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            lastOutputFormat = format
            debugMediaTrace?.setCodecFormat(format)
            if (closed.get()) return
            if (temporalLayeringRequested && !layeringFormatLogged) {
                layeringFormatLogged = true
                Log.i(
                    "BluetoothVideo",
                    "${activeEncoderName} temporal layering requested $TEMPORAL_LAYERING_SCHEMA, " +
                        "reported ${format.getStringOrNull(MediaFormat.KEY_TEMPORAL_LAYERING) ?: "none"}",
                )
                if (DebugTunables.relayChildThinning) {
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK temporal_layering: encoder=${activeEncoderName} | mime=${videoCodec.mimeType} | " +
                            "requested=$TEMPORAL_LAYERING_SCHEMA | " +
                            "reported=${format.getStringOrNull(MediaFormat.KEY_TEMPORAL_LAYERING) ?: "none"}",
                    )
                }
            }
            if (hcCodec != null) {
                // Design HC: no codec-config packet; the format's csd only fills the packetiser cache.
                listOf("csd-0", "csd-1", "csd-2").forEach { key ->
                    format.getByteBuffer(key)?.let { data ->
                        val bytes = ByteArray(data.remaining())
                        data.get(bytes)
                        runCatching { hcPacketiser?.onCodecConfig(bytes) }
                    }
                }
                return
            }
            format.getByteBuffer("csd-0")?.let { data ->
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                onPacket(StreamProtocol.FLAG_CODEC_CONFIG, 0, bytes)
            }
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            if (closed.get()) return
            notifyFailure("Encoder error: ${error.diagnosticInfo}", error)
        }
    }

    private fun normalizedCodecPresentationTimeUs(
        presentationTimeUs: Long,
        elapsedRealtimeUs: Long,
    ): Long {
        val monotonicTimeUs = System.nanoTime() / 1_000L
        val offsetUs = codecPresentationTimeOffsetUs ?: codecPresentationTimeEpochOffsetUs(
            presentationTimeUs,
            elapsedRealtimeUs,
            monotonicTimeUs,
        ).also { selectedOffsetUs ->
            codecPresentationTimeOffsetUs = selectedOffsetUs
            Log.i("BluetoothVideo", "Encoder clock: raw PTS=$presentationTimeUs us, elapsed=$elapsedRealtimeUs us, nano=$monotonicTimeUs us, offset=$selectedOffsetUs us")
            if (selectedOffsetUs != 0L) {
                Log.i(
                    "BluetoothVideo",
                    "Normalized ${activeEncoderName} video timestamps to the elapsed-realtime A/V epoch.",
                )
            }
        }
        return presentationTimeUs + offsetUs
    }

    private fun reportEncoderCadenceIfDue() {
        val nowNs = System.nanoTime()
        val elapsedNs = nowNs - cadenceReportStartedNs
        if (elapsedNs < CADENCE_REPORT_INTERVAL_NS) return
        fpsFollow?.let { follow ->
            val frameMs = sensorFrameDurationNs?.div(1_000_000.0)
            val exposureMs = exposureTimeNs?.div(1_000_000.0)
            if (follow.onWindow(frameMs, exposureMs)) {
                Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + follow.line(frameMs, exposureMs))
                updateFrameRate(lastMaximumFps)
            }
        }
        val elapsedSeconds = elapsedNs / 1_000_000_000.0
        val cadence = encodedFrameInterval.snapshot()
        val outputLatency = captureToEncodedOutput.snapshot()
        val outputLabel = if (debugRawInputFile != null) "raw-input-to-output" else if (cameraTimestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME &&
            codecPresentationTimeOffsetUs == 0L) "capture-to-output" else "raw-PTS age (clock unverified)"
        val outputTiming = if (outputLatency.samples > 0) {
            "$outputLabel p50/p95/max %.1f/%.1f/%.1f ms".format(outputLatency.p50Ms, outputLatency.p95Ms, outputLatency.maxMs)
        } else "$outputLabel unavailable"
        val inputLatency = inputToEncodedOutput.snapshot()
        val inputTiming = if (inputLatency.samples > 0) {
            ", codec input-to-output p50/p95/max %.1f/%.1f/%.1f ms".format(inputLatency.p50Ms, inputLatency.p95Ms, inputLatency.maxMs)
        } else ", codec input-to-output unavailable"
        val cameraTiming = if (BuildConfig.DEBUG) {
            val inputAge = captureToCodecInput.snapshot()
            val copy = yuvCopyLatency.snapshot()
            ", capture-to-input p50/p95 %.1f/%.1f ms (%d samples), YUV copy p95 %.1f ms, exposure %s ms, sensor frame %s ms, pipeline depth %s".format(
                inputAge.p50Ms, inputAge.p95Ms, inputAge.samples, copy.p95Ms,
                exposureTimeNs?.div(1_000_000.0) ?: "unavailable",
                sensorFrameDurationNs?.div(1_000_000.0) ?: "unavailable",
                cameraPipelineDepth ?: "unavailable",
            )
        } else ""
        val nativeTiming = if (usesNativeEncoder) {
            val copy = nativeCopyLatency.snapshot()
            val encode = nativeEncodeLatency.snapshot()
            ", native copy p95 %.1f ms, encode p95 %.1f ms, capture drops %d".format(
                copy.p95Ms,
                encode.p95Ms,
                nativeInputDrops,
            )
        } else {
            (surfaceCaptureLoss?.periodicField() ?: copyCaptureLoss?.periodicField()).orEmpty()
        } + layeringSummary() + quantizerSummary()
        Log.i(
            "BluetoothVideo",
            ("Encoder output: %.1f FPS, %.0f kbps, PTS cadence p50/p95/max %.1f/%.1f/%.1f ms, " +
                "%s%s%s%s%s").format(
                cadenceReportFrames / elapsedSeconds,
                cadenceReportBytes * 8.0 / elapsedSeconds / 1_000.0,
                cadence.p50Ms,
                cadence.p95Ms,
                cadence.maxMs,
                outputTiming,
                inputTiming,
                nativeTiming,
                cameraTiming,
                if (debugRawInputFile != null) ", raw input drops $rawInputDrops" else "",
            ),
        )
        gpuCapture?.let { capture ->
            Log.i(
                "BluetoothVideo",
                "BENCHMARK gpu_scale: rung=${settings.resolution.width}x${settings.resolution.height} | " +
                    "encoderResetMs=${"%.1f".format(gpuResetMs)} | ${capture.statsLine()}",
            )
        }
        // The bitrate adjuster replaces the 5 s output-rate trim for MediaCodec encoders while it is on.
        if (rateControl == EncoderRateControl.AUTOMATIC && DebugTunables.encoderRateTrim && bitrateAdjuster == null) {
            val requested = activeBitrate
            rateTrim.onWindow(requested, cadenceReportBytes * 8.0 / elapsedSeconds)?.let { target ->
                Log.i(
                    "BluetoothVideo",
                    "Encoder rate trim: output %.0f kbps for %d kbps requested; encoder target now %d kbps".format(
                        cadenceReportBytes * 8.0 / elapsedSeconds / 1_000.0,
                        requested / 1_000,
                        target / 1_000,
                    ),
                )
                applyEncoderTarget(target)
            }
        }
        softwareShortfall?.let { tracker ->
            val drops = nativeInputDrops + rawInputDrops
            val windowDrops = drops - softwareShortfallDropsAtWindowStart
            softwareShortfallDropsAtWindowStart = drops
            val encode = nativeEncodeLatency.snapshot()
            tracker.onWindow(
                outputFps = cadenceReportFrames / elapsedSeconds,
                // The rate in force: the request, lowered by the link/thermal limit and the low-light follower.
                targetFps = minOf(settings.fps, lastMaximumFps, fpsFollow?.effectiveFps ?: Int.MAX_VALUE).coerceAtLeast(1),
                // A raw fixture paces its own input; the camera's sensor frame applies to live input only.
                sourceFrameMs = if (debugRawInputFile != null) null else sensorFrameDurationNs?.div(1_000_000.0),
                encodeP95Ms = encode.p95Ms.takeIf { encode.samples > 0 },
                inputDrops = windowDrops,
            )?.let { verdict ->
                onSoftwareShortfall?.invoke(
                    SoftwareShortfallMark(activeEncoderName, settings.resolution.width, settings.resolution.height, settings.fps),
                    verdict,
                )
            }
        }
        cadenceReportStartedNs = nowNs
        cadenceReportFrames = 0
        cadenceReportBytes = 0
    }

    /**
     * Diagnostic (on, once per MediaCodec encoder start): the burst-control knobs this encoder
     * actually offers, read from the platform instead of assumed (BENCHMARK encoder_capabilities).
     * Framework facts (android-frameworks-av): on the ACodec/OMX path (M31 OMX.Exynos.VP9)
     * getSupportedVendorParameters() is always empty because ACodec::querySupportedParameters lists
     * nothing, video-qp-* bounds are never passed to the component, and OMX vendor extensions show
     * only as vendor.* keys of the port formats; intra-refresh-period is applied through
     * OMX_IndexConfigAndroidIntraRefresh (or cyclic macroblocks at configure) and reported back in
     * the output format only when the component accepted it. Codec2 (S24 c2.exynos.vp9) lists its
     * vendor parameters and maps QP bounds and intra refresh to C2 tunings.
     */
    /** DEBUG encoder bench: what the encoder reports it applied, and the live libaom state. */
    internal fun benchReadback(): EncoderBenchReadback {
        val format = lastOutputFormat
        fun intOf(key: String): Int? = format?.takeIf { it.containsKey(key) }?.let {
            runCatching { it.getInteger(key) }.getOrNull() ?: runCatching { it.getFloat(key).toInt() }.getOrNull()
        }
        return EncoderBenchReadback(
            outputIntraRefreshPeriod = intOf(MediaFormat.KEY_INTRA_REFRESH_PERIOD),
            outputBitrateMode = intOf(MediaFormat.KEY_BITRATE_MODE),
            outputBitrateBps = intOf(MediaFormat.KEY_BIT_RATE),
            outputFrameRate = intOf(MediaFormat.KEY_FRAME_RATE),
            outputIFrameInterval = intOf(MediaFormat.KEY_I_FRAME_INTERVAL),
            libaomCpuUsed = if (usesNativeEncoder) nativeSpeedGovernor?.cpuUsed else null,
            libaomThreads = if (usesNativeEncoder) nativeThreadCount(settings.resolution) else null,
            rawInputDrops = rawInputDrops,
        )
    }

    private fun logEncoderCapabilities(encoder: MediaCodec) {
        val line = runCatching {
            val capabilities = encoder.codecInfo.getCapabilitiesForType(videoCodec.mimeType)
            fun feature(name: String) = runCatching { capabilities.isFeatureSupported(name) }.getOrDefault(false)
            val path = when {
                activeEncoderName.startsWith("OMX.", ignoreCase = true) -> "omx"
                activeEncoderName.startsWith("c2.", ignoreCase = true) -> "codec2"
                else -> "other"
            }
            val vendorParameters = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    JSONArray().apply {
                        encoder.supportedVendorParameters.sorted().forEach { name ->
                            val type = runCatching { encoder.getParameterDescriptor(name)?.type }.getOrNull()
                            put(if (type == null) name else "$name:$type")
                        }
                    }
                }.getOrElse { error -> JSONArray().put("error: ${error.message}") }
            } else {
                null
            }
            val input = runCatching { encoder.inputFormat }.getOrNull()
            val output = runCatching { encoder.outputFormat }.getOrNull()
            fun MediaFormat?.keysMatching(predicate: (String) -> Boolean): JSONObject {
                val values = JSONObject()
                if (this == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return values
                keys.filter(predicate).sorted().forEach { key ->
                    values.put(key, runCatching { getInteger(key).toString() }.getOrNull()
                        ?: runCatching { getString(key) }.getOrNull()
                        ?: runCatching { getFloat(key).toString() }.getOrNull()
                        ?: "?")
                }
                return values
            }
            val burstKeys: (String) -> Boolean = { key ->
                key.startsWith("vendor.") || "qp" in key || key == "intra-refresh-period" ||
                    key == MediaFormat.KEY_BITRATE_MODE || key == MediaFormat.KEY_BIT_RATE ||
                    key == MediaFormat.KEY_STRIDE || key == MediaFormat.KEY_SLICE_HEIGHT
            }
            JSONObject()
                .put("encoder", activeEncoderName)
                .put("path", path)
                .put("api", Build.VERSION.SDK_INT)
                .put("hardware", videoCodec.hardwareAccelerated)
                .put("surface_input", videoCodec.usesSurfaceInput && debugRawInputFile == null)
                .put("feature_intra_refresh", feature(MediaCodecInfo.CodecCapabilities.FEATURE_IntraRefresh))
                .put("feature_qp_bounds", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    feature(MediaCodecInfo.CodecCapabilities.FEATURE_QpBounds))
                .put("feature_encoding_statistics", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    feature(MediaCodecInfo.CodecCapabilities.FEATURE_EncodingStatistics))
                .put("bitrate_modes", JSONArray(videoCodec.supportedBitrateModes.sorted()))
                .put("requested_bitrate_mode", activeBitrateMode ?: JSONObject.NULL)
                .put("requested_intra_refresh_frames", DebugTunables.intraRefreshFrames ?: JSONObject.NULL)
                .put("vendor_parameters", vendorParameters ?: "unavailable before API 31")
                .put("input_format", input.keysMatching(burstKeys))
                .put("output_format", output.keysMatching(burstKeys))
                .toString()
        }.getOrElse { error -> "{\"encoder\":\"${activeEncoderName}\",\"error\":\"${error.javaClass.simpleName}\"}" }
        Log.i("BluetoothVideo", "BENCHMARK encoder_capabilities v1 $line")
        runCatching {
            val video = encoder.codecInfo.getCapabilitiesForType(videoCodec.mimeType).videoCapabilities
            Log.i(
                "BluetoothVideo",
                EncoderAlignment.line(
                    activeEncoderName,
                    EncoderAlignment.Report(video.widthAlignment, video.heightAlignment, settings.resolution.width, settings.resolution.height),
                ),
            )
        }
    }

    private fun supportsEncodingStatistics(): Boolean {
        if (usesNativeEncoder) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .first { it.name == activeEncoderName }
                .getCapabilitiesForType(videoCodec.mimeType)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_EncodingStatistics)
        }.getOrDefault(false)
    }

    private fun queueNativeFrame(source: Image) {
        val buffers = nativeInputBuffers ?: return
        val input = buffers.poll()
        if (input == null) {
            nativeInputDrops++
            if (nativeInputDrops == 1 || nativeInputDrops % 30 == 0) {
                Log.w("BluetoothVideo", "Native encoder dropped $nativeInputDrops camera frame(s)")
            }
            return
        }
        try {
            val startedCopyNs = System.nanoTime()
            val planes = source.planes
            // The visible region (Image.getCropRect) shifts each plane's start; the full image keeps
            // the former call exactly.
            val visible = visibleRegion(source)
            fun start(index: Int): Int {
                val area = YuvPlaneCopy.planeRegion(visible, index)
                return planes[index].buffer.position() + area.top * planes[index].rowStride +
                    area.left * planes[index].pixelStride
            }
            val nativeCopied = planes.size >= 3 && NativeAomBridge.available && NativeAomBridge.nativeCopyYuv420ToI420(
                planes[0].buffer,
                start(0),
                planes[0].buffer.limit(),
                planes[0].rowStride,
                planes[0].pixelStride,
                planes[1].buffer,
                start(1),
                planes[1].buffer.limit(),
                planes[1].rowStride,
                planes[1].pixelStride,
                planes[2].buffer,
                start(2),
                planes[2].buffer.limit(),
                planes[2].rowStride,
                planes[2].pixelStride,
                visible.width,
                visible.height,
                input,
                settings.resolution.width,
                settings.resolution.height,
            )
            if (!nativeCopied) copyYuv420ToI420(source, input)
            nativeCopyLatency.recordNanos(System.nanoTime() - startedCopyNs)
            if (firstFramesChecked < firstFramesToCheck) checkFirstFramesI420(input)
            referenceRecorder?.record(source, visible)
            val presentationTimeUs = source.timestamp / 1_000
            inspectEncoderInput(presentationTimeUs, width = settings.resolution.width, height = settings.resolution.height) {
                lumaPlaneOf(input, 0, settings.resolution.width, 1)
            }
            nativeWorker?.post {
                try {
                    encodeNativeFrame(input, presentationTimeUs)
                } finally {
                    input.clear()
                    buffers.offer(input)
                }
            } ?: buffers.offer(input)
        } catch (error: Exception) {
            buffers.offer(input)
            onStatus("Native $nativeLabel input error: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private val nativeMinCpuUsed: Int
        get() = if (usesNativeVpx) NativeVpxBridge.MIN_REALTIME_CPU_USED else NativeEncoderSpeedGovernor.MIN_REALTIME_CPU_USED
    private val nativeMaxCpuUsed: Int
        get() = if (usesNativeVpx) NativeVpxBridge.MAX_REALTIME_CPU_USED else NativeEncoderSpeedGovernor.MAX_REALTIME_CPU_USED

    private fun createNativeEncoder(cpuUsed: Int, bitrate: Int): NativeVideoEncoder {
        markNativeInit()
        return buildNativeEncoder(cpuUsed, bitrate)
    }

    /**
     * Experimental `test_native_simd_safe_mode` (default off): a marker is written right before libaom initialises and
     * cleared after the crash window without a crash, so the next start can tell a SIMD crash at init from any other
     * (NativeSimdSafeMode). A no-op unless the switch is on.
     */
    private fun markNativeInit() {
        if (!ThermalSwitches.simdSafeMode) return
        val store = NativeSimdSafeMode.FileStore(context.filesDir)
        NativeSimdSafeMode.markInit(store, true, System.currentTimeMillis(), android.os.Process.myPid())
        Handler(Looper.getMainLooper()).postDelayed({ NativeSimdSafeMode.markStable(store) }, NativeSimdSafeMode.CRASH_WINDOW_MS)
    }

    private fun buildNativeEncoder(cpuUsed: Int, bitrate: Int): NativeVideoEncoder = if (usesNativeVpx) {
        NativeVpxEncoder(
            width = settings.resolution.width,
            height = settings.resolution.height,
            fps = settings.fps,
            bitrate = bitrate,
            cpuUsed = cpuUsed,
            threads = nativeThreadCount(settings.resolution),
            variableBitrate = activeBitrateMode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            // Same rule as libaom: Automatic keeps every frame; only an explicit CBR-FD allows encoder drops.
            dropFrames = rateControl == EncoderRateControl.CBR_FD,
        )
    } else NativeAomEncoder(
        width = settings.resolution.width,
        height = settings.resolution.height,
        fps = settings.fps,
        bitrate = bitrate,
        cpuUsed = cpuUsed,
        threads = nativeThreadCount(settings.resolution),
        variableBitrate = activeBitrateMode ==
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
        // Automatic keeps every frame: libaom frame dropping froze the hardest coverage
        // segment for up to 533 ms; rate control raises quantization instead.
        dropFrames = rateControl == EncoderRateControl.CBR_FD,
        tuning = NativeAomTuning(realtimeToolProfile = nativeFastTools),
    )

    /**
     * The governor is pinned at the fastest preset and still over budget with the full tool set:
     * re-create the encoder with the real-time fast tool profile (sequence-level tools cannot
     * change on a live libaom encoder), costing one keyframe.
     */
    private fun recreateNativeEncoderWithFastTools(): NativeVideoEncoder? {
        val previous = nativeEncoder ?: return null
        val cpuUsed = nativeSpeedGovernor?.cpuUsed ?: checkNotNull(videoCodec.nativeCpuUsed(settings))
        val target = if (rateControl == EncoderRateControl.AUTOMATIC) {
            rateTrim.encoderTargetBps(activeBitrate)
        } else {
            activeBitrate
        }
        nativeFastTools = true
        val replacement = runCatching { createNativeEncoder(cpuUsed, target) }.getOrElse { error ->
            nativeFastTools = false
            Log.w("BluetoothVideo", "Native encoder could not be re-created with the fast tool profile", error)
            return previous
        }
        runCatching { previous.close() }
        if (nativeLastInterQuantizer >= 0) replacement.setKeyFrameQuantizerReference(nativeLastInterQuantizer)
        if (nativeInterBitratePct > 0) replacement.updateMaxInterBitratePct(nativeInterBitratePct)
        nativeEncoder = replacement
        nativeExplicitReferences = false
        nativeKeyFrameRequested.set(true)
        return replacement
    }

    /**
     * Experimental temporal layering: back to libaom's own reference structure (full quality) after
     * the links calmed down. libaom clears explicit references only for a new encoder, so this costs
     * one keyframe, floored near the current quantizer, at a time when every link has had room for
     * 20 s.
     */
    private fun recreateNativeEncoderForFullReferences(): NativeVideoEncoder? {
        val previous = nativeEncoder ?: return null
        val cpuUsed = nativeSpeedGovernor?.cpuUsed ?: checkNotNull(videoCodec.nativeCpuUsed(settings))
        val target = if (rateControl == EncoderRateControl.AUTOMATIC) {
            rateTrim.encoderTargetBps(activeBitrate)
        } else {
            activeBitrate
        }
        val replacement = runCatching { createNativeEncoder(cpuUsed, target) }.getOrElse { error ->
            Log.w("BluetoothVideo", "Native encoder could not be re-created; keeping explicit references", error)
            return previous
        }
        runCatching { previous.close() }
        if (nativeLastInterQuantizer >= 0) replacement.setKeyFrameQuantizerReference(nativeLastInterQuantizer)
        if (nativeInterBitratePct > 0) replacement.updateMaxInterBitratePct(nativeInterBitratePct)
        nativeEncoder = replacement
        nativeExplicitReferences = false
        Log.i(
            "BluetoothVideo",
            "Native encoder back to libaom's own references (links calm); one keyframe floored near QP $nativeLastInterQuantizer",
        )
        return replacement
    }

    private fun encodeNativeFrame(input: ByteBuffer, presentationTimeUs: Long) {
        debugStageTrace?.record("encode_input", presentationTimeUs, input.remaining())
        var encoder = nativeEncoder ?: return
        try {
            val keyFrameIntervalUs = keyFrameIntervalSeconds * 1_000_000L
            // Experimental temporal layering only; without it wantLayers is false, no encoder is
            // re-created and every frame is encoded with layer -1 (libaom's own references).
            val wantLayers = nativeTemporalLayers && droppableFramesWanted
            var recreated = false
            if (wantLayers) {
                nativeExplicitReferences = true
            } else if (nativeExplicitReferences) {
                encoder = recreateNativeEncoderForFullReferences() ?: return
                recreated = !nativeExplicitReferences
            }
            val forceKeyFrame = recreated || nativeKeyFrameRequested.getAndSet(false) ||
                lastNativeKeyFramePtsUs == Long.MIN_VALUE ||
                presentationTimeUs - lastNativeKeyFramePtsUs >= keyFrameIntervalUs
            if (forceKeyFrame) nativeLayerFrameIndex = 0L
            val temporalLayer = nativeTemporalLayer(wantLayers, nativeLayerFrameIndex)
            nativeLayerFrameIndex++
            val startedNs = System.nanoTime()
            val packets = encoder.encode(
                input,
                presentationTimeUs,
                forceKeyFrame,
                layer = if (nativeExplicitReferences) temporalLayer else -1,
            )
            val encodeLatencyMs = (System.nanoTime() - startedNs) / 1_000_000.0
            nativeEncodeLatency.recordNanos(System.nanoTime() - startedNs)
            var escalateToFastTools = false
            // A pinned cpu-used (encoder bench) is never adapted, thermally raised or remembered.
            nativeSpeedGovernor?.takeIf { bench?.libaomCpuUsed == null }?.let { governor ->
                val previous = governor.cpuUsed
                governor.applyThermalFloor(thermalSpeedFloor)?.let { raised ->
                    encoder.updateCpuUsed(raised)
                    Log.i("BluetoothVideo", "Native $nativeLabel speed: cpu-used $previous -> $raised (thermal floor $thermalSpeedFloor)")
                }
                governor.record(encodeLatencyMs, System.nanoTime())?.let { next ->
                    encoder.updateCpuUsed(next)
                    Log.i(
                        "BluetoothVideo",
                        "Native $nativeLabel speed: cpu-used $previous -> $next " +
                            "(frame budget %.1f ms)".format(1_000.0 / settings.fps),
                    )
                }
                val overloadReport = onNativeOverloadAtFastest
                if (overloadReport != null && !nativeOverloadReported && nativeFastTools &&
                    governor.overloadedAtFastestWindows >= CallEncoderFit.OVERLOAD_WINDOWS
                ) {
                    nativeOverloadReported = true
                    overloadReport(nativeSpeedKey)
                }
                nativeSpeedLearner?.let { learner ->
                    escalateToFastTools = learner.shouldEscalateToFastTools(governor)
                    // A thermally raised preset is not this phone's settled speed.
                    learner.settledRecord(governor)?.takeIf { thermalSpeedFloor == 0 }?.let { record ->
                        runCatching { nativeSpeedStore?.save(nativeSpeedKey, record) }
                        Log.i(
                            "BluetoothVideo",
                            "Native $nativeLabel settled: cpu-used ${record.cpuUsed}, fastTools=${record.fastTools} " +
                                "remembered for $nativeSpeedKey",
                        )
                    }
                }
            }
            packets.forEach { packet ->
                val flags = if (nativeTemporalLayers) {
                    if (!packet.keyFrame && packet.quantizer >= 0) nativeLastInterQuantizer = packet.quantizer
                    val verifiedDroppable = nativeDroppability.isDroppable(packet.bytes, packet.keyFrame)
                    if (temporalLayer == 1 && !packet.keyFrame && !verifiedDroppable) {
                        nativeUnverifiedDroppable++
                        if (nativeUnverifiedDroppable == 1L || nativeUnverifiedDroppable % 100 == 0L) {
                            Log.w(
                                "BluetoothVideo",
                                "Native frame requested droppable but its AV1 header refreshes references; " +
                                    "sent as a reference frame ($nativeUnverifiedDroppable so far)",
                            )
                        }
                    }
                    nativePacketFlags(packet.keyFrame, if (verifiedDroppable) 1 else 0)
                } else {
                    if (packet.keyFrame) StreamProtocol.FLAG_KEY_FRAME else 0
                }
                val transportPresentationTimeUs = normalizedCodecPresentationTimeUs(
                    packet.presentationTimeUs,
                    SystemClock.elapsedRealtimeNanos() / 1_000L,
                )
                if (packet.keyFrame) lastNativeKeyFramePtsUs = packet.presentationTimeUs
                burstAccumulator.record(packet.bytes.size, activeBitrate)
                framesEncoded.incrementAndGet()
                if (!firstVideoFrameSeen) {
                    firstVideoFrameSeen = true
                    onFirstVideoFrame()
                }
                debugMediaTrace?.record(
                    transportPresentationTimeUs,
                    flags,
                    packet.bytes,
                    packet.quantizer,
                    if (packet.keyFrame) 1 else 2,
                    encodeLatencyMs,
                    activeBitrate,
                    "pre_transport",
                )
                val previousPresentationTimeUs = lastEncodedPresentationTimeUs
                if (previousPresentationTimeUs != Long.MIN_VALUE &&
                    packet.presentationTimeUs > previousPresentationTimeUs
                ) {
                    encodedFrameInterval.recordNanos(
                        (packet.presentationTimeUs - previousPresentationTimeUs) * 1_000L,
                    )
                }
                lastEncodedPresentationTimeUs = packet.presentationTimeUs
                if (cadenceReportStartedNs == 0L) cadenceReportStartedNs = System.nanoTime()
                cadenceReportFrames++
                cadenceReportBytes += packet.bytes.size
                reportEncoderCadenceIfDue()
                debugStageTrace?.record("encode_output", transportPresentationTimeUs, packet.bytes.size)
                onPacket(flags, transportPresentationTimeUs, packet.bytes)
            }
            if (escalateToFastTools) {
                val overloadedWindows = nativeSpeedGovernor?.overloadedAtFastestWindows ?: 0
                recreateNativeEncoderWithFastTools()
                Log.i(
                    "BluetoothVideo",
                    "Native libaom fast tool profile ${if (nativeFastTools) "enabled" else "unavailable"}: " +
                        "cpu-used ${nativeSpeedGovernor?.cpuUsed} still over the %.1f ms budget for $overloadedWindows windows; "
                            .format(1_000.0 / settings.fps) +
                        "encoder re-created (one keyframe)",
                )
            }
        } catch (error: Exception) {
            onStatus("Native $nativeLabel input error: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /**
     * A raw frame is about to enter the encoder (worker thread). Scene-cut detection runs when the
     * shaping experiment is on or the session is benchmark-traced; with the experiment on, a costly
     * cut lowers the MediaCodec target (and, in SYNC mode, requests a sync frame) before the frame
     * is queued; both the ACodec/OMX and CCodec paths deliver it to the component ahead of the next
     * queued input (see SceneCutShaper). The full target returns when that frame is encoded.
     */
    private inline fun inspectEncoderInput(
        presentationTimeUs: Long,
        width: Int,
        height: Int,
        plane: () -> LumaPlane,
    ) {
        val nowNs = System.nanoTime()
        if (debugRawInputFile == null && privacyNoticeOn && !privacyNoticeShown && nowNs - lastHealthSampleNs >= HEALTH_SAMPLE_INTERVAL_NS) {
            lastHealthSampleNs = nowNs
            sampleFeedForPrivacyToggle(nowNs / 1_000_000L, width, height, plane())
        }
        if (sceneCutShaper.timedOut(nowNs)) {
            Log.i("BluetoothVideo", "Scene-cut shaping: the shaped frame was not returned within 1 s; target restored")
            applyCodecTarget("scene-cut restore after timeout")
        }
        if (!sceneCutDetection) return
        val detector = sceneCutDetector?.takeIf { it.width == width && it.height == height }
            ?: runCatching { SceneCutDetector(width, height) }.getOrNull()?.also { sceneCutDetector = it }
            ?: return
        val result = runCatching { detector.analyze(plane()) }.getOrNull() ?: return
        if (!result.cut) return
        sceneCutsDetected++
        val targetBps = if (usesNativeEncoder) activeBitrate else codecTargetBps()
        val shapingPlan = sceneCutShaper.plan(
            presentationTimeUs,
            nowNs,
            result,
            lastVp9QIdx,
            frameBudgetLinkKbps,
            targetBps,
            settings.fps,
        )
        var applied = false
        if (shapingPlan != null) {
            applied = runCatching {
                checkNotNull(codec).setParameters(
                    Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, shapingPlan.shapedBps)
                        if (shapingPlan.requestSync) putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    },
                )
            }.isSuccess
            if (!applied) sceneCutShaper.cancel()
        }
        Log.i(
            "BluetoothVideo",
            "BENCHMARK scene_cut v1 " + JSONObject()
                .put("pts_us", presentationTimeUs)
                .put("encoder", activeEncoderName)
                .put("changed_fraction", roundedForLog(result.changedFraction, 3))
                .put("mean_delta", roundedForLog(result.meanDelta, 1))
                .put("recent_median_delta", roundedForLog(result.recentMedianDelta, 1))
                .put("detail", roundedForLog(result.detail, 1))
                .put("previous_detail", roundedForLog(result.previousDetail, 1))
                .put("detail_rise", result.detailRise)
                .put("last_q_idx", lastVp9QIdx ?: JSONObject.NULL)
                .put("mode", sceneCutShaper.mode.label)
                .put("action", when {
                    shapingPlan == null -> "none"
                    !applied -> "rejected"
                    shapingPlan.requestSync -> "sync"
                    else -> "target"
                })
                .put("skip_reason", sceneCutShaper.skipReason ?: JSONObject.NULL)
                .put("target_kbps", targetBps / 1_000)
                .put("shaped_kbps", shapingPlan?.shapedBps?.div(1_000) ?: JSONObject.NULL)
                .put("link_kbps", frameBudgetLinkKbps)
                .put("budget_bytes", shapingPlan?.budgetBytes ?: SceneCutShaper.cutBudgetBytes(frameBudgetLinkKbps, targetBps))
                .put("expected_bytes", shapingPlan?.expectedBytes ?: JSONObject.NULL)
                .put("learned_cut_frames", roundedForLog(sceneCutShaper.cutFrames, 1))
                .toString(),
        )
    }

    /**
     * A MediaCodec frame came out (worker thread): feeds [bitrateAdjuster], re-applies the codec
     * target when its scale changed and logs it at most once per second.
     */
    private fun recordBitrateAdjusterFrame(ptsUs: Long, bytes: Int, vp9: Vp9FrameInfo?) {
        if (framerateAdjusterArm) recordDeliveredFrameRate(ptsUs)
        val adjuster = bitrateAdjuster ?: return
        adjuster.setTarget(baseCodecTargetBps())
        val atCeiling = vp9 != null && vp9.baseQIdx >= Vp9FrameHeader.MAX_Q_IDX - 5
        if (adjuster.onEncodedFrame(ptsUs, bytes, settings.fps, atCeiling)) {
            applyCodecTarget("bitrate adjuster x${roundedForLog(adjuster.scale, 2)}")
        }
        val nowNs = System.nanoTime()
        if (bitrateAdjusterLogStartedNs == 0L) {
            bitrateAdjusterLogStartedNs = nowNs
            bitrateAdjusterLogBytes = 0L
            return
        }
        bitrateAdjusterLogBytes += bytes
        val elapsedNs = nowNs - bitrateAdjusterLogStartedNs
        if (elapsedNs < BITRATE_ADJUSTER_LOG_INTERVAL_NS) return
        Log.i(
            "BluetoothVideo",
            "BENCHMARK bitrate_adjuster: target=${adjuster.targetBps / 1_000} | " +
                "requested=${codecTargetBps() / 1_000} | " +
                "actualKbps=${(bitrateAdjusterLogBytes * 8_000_000.0 / elapsedNs).toInt()} | " +
                "scale=${roundedForLog(adjuster.scale, 3)} | exponent=${adjuster.scaleExponent} | " +
                "deviationKB=${roundedForLog(adjuster.deviationBytes / 1_000.0, 1)} | " +
                "adjustments=${adjuster.adjustments} | ceilingHolds=${adjuster.ceilingHolds} | " +
                "encoder=${activeEncoderName}",
        )
        bitrateAdjusterLogStartedNs = nowNs
        bitrateAdjusterLogBytes = 0L
    }

    /** A MediaCodec frame came out (worker thread): scene-cut restore and quantizer headroom. */
    private fun afterEncodedFrame(ptsUs: Long, bytes: Int, flags: Int, vp9: Vp9FrameInfo?) {
        if (sceneCutShaper.pending) {
            val shapedPtsUs = sceneCutShaper.pendingPtsUs
            if (sceneCutShaper.onEncoded(ptsUs, bytes, settings.fps)) {
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK scene_cut_result v1 " + JSONObject()
                        .put("pts_us", ptsUs)
                        .put("shaped_pts_us", shapedPtsUs)
                        .put("same_frame", ptsUs == shapedPtsUs)
                        .put("bytes", bytes)
                        .put("key_frame", flags and StreamProtocol.FLAG_KEY_FRAME != 0)
                        .put("q_idx", vp9?.baseQIdx ?: JSONObject.NULL)
                        .put("budget_bytes", SceneCutShaper.cutBudgetBytes(frameBudgetLinkKbps, codecTargetBps()))
                        .put("learned_cut_frames", roundedForLog(sceneCutShaper.cutFrames, 1))
                        .toString(),
                )
                applyCodecTarget("scene cut encoded")
            }
        }
        val governor = quantizerHeadroom ?: return
        val qIdx = vp9?.baseQIdx ?: return
        governor.onFrame(qIdx, System.nanoTime())?.let { factor ->
            Log.i(
                "BluetoothVideo",
                "Experimental VP9 quantizer headroom: q_idx median ${governor.lastWindowMedian} vs floor " +
                    "${DebugTunables.vp9QuantizerFloor}; target factor ${roundedForLog(factor, 2)}",
            )
            applyCodecTarget("quantizer headroom x${roundedForLog(factor, 2)}")
        }
    }

    /**
     * V-07: a feed that stays black while the phone has a camera privacy toggle is almost certainly the toggle (no error is
     * delivered). A notice only; the stream is untouched. Software-encoder YUV path only (surface input has no pixels).
     */
    private fun sampleFeedForPrivacyToggle(nowMs: Long, width: Int, height: Int, plane: LumaPlane) {
        val mean = BlackFrameDetector.sampledMeanLuma(width, height, plane)
        if (!blackFrameDetector.onSample(nowMs, mean)) return
        val toggle = cameraPrivacyToggleSupported()
        Log.i("BluetoothVideo", "BENCHMARK camera_black_feed: meanLuma=${"%.1f".format(java.util.Locale.US, mean)} | toggleSupported=$toggle")
        if (toggle) {
            privacyNoticeShown = true
            onStatus("Camera access is turned off in Quick Settings.")
        }
    }

    private fun cameraPrivacyToggleSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            context.getSystemService(android.hardware.SensorPrivacyManager::class.java)
                ?.supportsSensorToggle(android.hardware.SensorPrivacyManager.Sensors.CAMERA) == true
        }.getOrDefault(false)
    }

    /** DEBUG [BlankFrameInjection]: blanks this camera image in place while the requested count lasts. */
    private fun injectBlankIfRequested(image: Image) {
        if (!BuildConfig.DEBUG || !BlankFrameInjection.shouldInject(blankFramesInjected, DebugTunables.injectBlankFrames)) return
        blankFramesInjected++
        val written = runCatching { BlankFrameInjection.blank(image.planes.map { it.buffer }) }.getOrDefault(false)
        Log.i("BluetoothVideo", "BENCHMARK camera_blank_injected: frame=$blankFramesInjected | written=$written")
    }

    /** Experimental [LeadingBlankFrames]: true when this camera frame is a leading blank buffer to drop. */
    private fun dropLeadingBlank(image: Image): Boolean {
        if (!leadingBlankFrames.checking) return false
        val drop = runCatching {
            val planes = image.planes
            val chromaWidth = YuvPlaneCopy.chromaSize(image.width)
            val chromaHeight = YuvPlaneCopy.chromaSize(image.height)
            val y = PlaneSampler.mean(planes[0].buffer, planes[0].buffer.position(), planes[0].rowStride, planes[0].pixelStride, image.width, image.height)
            val u = PlaneSampler.mean(planes[1].buffer, planes[1].buffer.position(), planes[1].rowStride, planes[1].pixelStride, chromaWidth, chromaHeight)
            val v = PlaneSampler.mean(planes[2].buffer, planes[2].buffer.position(), planes[2].rowStride, planes[2].pixelStride, chromaWidth, chromaHeight)
            leadingBlankFrames.drop(y, u, v, SystemClock.elapsedRealtime())
        }.getOrDefault(false)
        if (!drop && leadingBlankFrames.dropped > 0 && !leadingBlankLogged) {
            leadingBlankLogged = true
            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK camera_leading_blank: dropped=${leadingBlankFrames.dropped} | encoder=$activeEncoderName")
        }
        return drop
    }

    /** V-19: average luma and chroma of the first YUV frames; only a suspect (black, purple, green) is logged. */
    private fun checkFirstFramesImage(source: Image) {
        firstFramesChecked++
        runCatching {
            val planes = source.planes
            val chromaWidth = YuvPlaneCopy.chromaSize(source.width)
            val chromaHeight = YuvPlaneCopy.chromaSize(source.height)
            val y = PlaneSampler.mean(planes[0].buffer, planes[0].buffer.position(), planes[0].rowStride, planes[0].pixelStride, source.width, source.height)
            val u = PlaneSampler.mean(planes[1].buffer, planes[1].buffer.position(), planes[1].rowStride, planes[1].pixelStride, chromaWidth, chromaHeight)
            val v = PlaneSampler.mean(planes[2].buffer, planes[2].buffer.position(), planes[2].rowStride, planes[2].pixelStride, chromaWidth, chromaHeight)
            reportFirstFrame(y, u, v)
        }
    }

    private fun checkFirstFramesI420(input: ByteBuffer) {
        firstFramesChecked++
        runCatching {
            val width = settings.resolution.width
            val height = settings.resolution.height
            val chromaWidth = YuvPlaneCopy.chromaSize(width)
            val chromaHeight = YuvPlaneCopy.chromaSize(height)
            val y = PlaneSampler.mean(input, 0, width, 1, width, height)
            val u = PlaneSampler.mean(input, width * height, chromaWidth, 1, chromaWidth, chromaHeight)
            val v = PlaneSampler.mean(input, width * height + chromaWidth * chromaHeight, chromaWidth, 1, chromaWidth, chromaHeight)
            reportFirstFrame(y, u, v)
        }
    }

    private fun reportFirstFrame(y: Double, u: Double, v: Double) {
        val verdict = FirstFrameSanity.assess(y, u, v)
        if (verdict != FirstFrameSanity.Verdict.OK) {
            Log.w("BluetoothVideo", FirstFrameSanity.benchmarkLine(firstFramesChecked, y, u, v, verdict, activeEncoderName))
        }
    }

    private fun queuePendingFrame() {
        if (closed.get()) {
            pendingImage?.close()
            pendingImage = null
            return
        }
        val source = pendingImage ?: return
        val inputIndex = pendingInputBuffers.pollFirst() ?: return
        pendingImage = null
        val sourcePresentationTimeUs = source.timestamp / 1_000L
        try {
            val target = codec?.getInputImage(inputIndex)
                ?: throw IllegalStateException("Encoder did not expose a flexible YUV input image")
            val copyStartedNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
            copyYuv420(source, target)
            if (firstFramesChecked < firstFramesToCheck && debugRawInputFile == null) checkFirstFramesImage(source)
            if (BuildConfig.DEBUG) {
                yuvCopyLatency.recordNanos(System.nanoTime() - copyStartedNs)
                if (cameraTimestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
                    captureToCodecInput.recordNanos(SystemClock.elapsedRealtimeNanos() - source.timestamp)
                }
            }
            referenceRecorder?.record(source, visibleRegion(source))
            inspectEncoderInput(sourcePresentationTimeUs, width = source.width, height = source.height) {
                val luma = source.planes[0]
                lumaPlaneOf(luma.buffer, luma.buffer.position(), luma.rowStride, luma.pixelStride)
            }
            if (queuedInputTimesNs.size >= 32) queuedInputTimesNs.remove(queuedInputTimesNs.keys.first())
            queuedInputTimesNs[sourcePresentationTimeUs] = System.nanoTime()
            codec?.queueInputBuffer(
                inputIndex,
                0,
                settings.resolution.width * settings.resolution.height * 3 / 2,
                source.timestamp / 1_000,
                0,
            )
            copyCaptureLoss?.onHanded(source.timestamp)
        } catch (error: Exception) {
            queuedInputTimesNs.remove(sourcePresentationTimeUs)
            if (!closed.get()) {
                runCatching {
                    codec?.queueInputBuffer(inputIndex, 0, 0, source.timestamp / 1_000, 0)
                }
                onStatus("${videoCodec.displayName} input error: ${error.message ?: error.javaClass.simpleName}")
            }
        } finally {
            source.close()
        }
    }

    private fun copyYuv420ToI420(source: Image, target: ByteBuffer) {
        target.clear()
        val visible = visibleRegion(source)
        var planeOffset = 0
        source.planes.forEachIndexed { planeIndex, sourcePlane ->
            val targetWidth = planeSize(settings.resolution.width, planeIndex)
            val targetHeight = planeSize(settings.resolution.height, planeIndex)
            val sourceCrop = YuvPlaneCopy.centerCropWithin(
                YuvPlaneCopy.planeRegion(visible, planeIndex), targetWidth, targetHeight,
            )
            val sourceBuffer = sourcePlane.buffer
            check(
                copyPlaneOrClampTail(
                    sourceBuffer, sourceBuffer.position(), sourcePlane.rowStride, sourcePlane.pixelStride, sourceCrop,
                    target, planeOffset, targetWidth, 1, targetWidth, targetHeight,
                ),
            ) { "YUV plane $planeIndex layout is outside its buffer (row ${sourcePlane.rowStride}, pixel ${sourcePlane.pixelStride})" }
            planeOffset += targetWidth * targetHeight
        }
        target.position(0)
        target.limit(planeOffset)
    }

    /**
     * [YuvPlaneCopy.copyPlane]; when it refuses (the layout reads past the buffer) and the Experimental
     * `test_yuv_tail_clamp` is on, a plane short by only its last few samples is copied with the tail clamped (V-24).
     */
    private fun copyPlaneOrClampTail(
        source: ByteBuffer, sourceOffset: Int, sourceRowStride: Int, sourcePixelStride: Int, region: PlaneRegion,
        target: ByteBuffer, targetOffset: Int, targetRowStride: Int, targetPixelStride: Int, targetWidth: Int, targetHeight: Int,
    ): Boolean {
        if (YuvPlaneCopy.copyPlane(
                source, sourceOffset, sourceRowStride, sourcePixelStride, region,
                target, targetOffset, targetRowStride, targetPixelStride, targetWidth, targetHeight,
            )
        ) return true
        if (!CameraSwitches.yuvTailClamp) return false
        val clamped = YuvTailClamp.copyPlaneClamped(
            source, sourceOffset, sourceRowStride, sourcePixelStride, region,
            target, targetOffset, targetRowStride, targetPixelStride, targetWidth, targetHeight,
        )
        if (clamped && !tailClampLogged) {
            tailClampLogged = true
            Log.w("BluetoothVideo", "BENCHMARK yuv_tail_clamped: encoder=$activeEncoderName | pixelStride=$sourcePixelStride | rowStride=$sourceRowStride | limit=${source.limit()}")
        }
        return clamped
    }

    /** Luma size for plane 0, 4:2:0 chroma size otherwise. */
    private fun planeSize(lumaSamples: Int, planeIndex: Int): Int =
        if (planeIndex == 0) lumaSamples else YuvPlaneCopy.chromaSize(lumaSamples)

    /** The frame's valid region: Image.getCropRect() under the yuv-image-crop-rect entry, else the whole image. */
    private fun visibleRegion(source: Image): PlaneRegion = YuvPlaneCopy.visibleRegion(
        source.width,
        source.height,
        if (honourCropRect) source.cropRect?.let { intArrayOf(it.left, it.top, it.right, it.bottom) } else null,
    )

    /**
     * Workstream C: one inter frame may use at most [FRAME_AIRTIME_MS] of the link it is sent on
     * ([linkKbps], already divided among viewers). Scene-cut P frames reached 50-62 KB (16x the
     * average) and froze links for over a second; libaom then clamps that frame's target.
     */
    fun updateLinkFrameBudget(linkKbps: Int) {
        if (linkKbps > 0) frameBudgetLinkKbps = linkKbps
        if (!usesNativeEncoder || rateControl != EncoderRateControl.AUTOMATIC || linkKbps <= 0) return
        val percent = maxInterFramePercent(linkKbps, activeBitrate, settings.fps)
        nativeWorker?.post {
            nativeInterBitratePct = percent
            runCatching { nativeEncoder?.updateMaxInterBitratePct(percent) }
                .onSuccess {
                    Log.i(
                        "BluetoothVideo",
                        "Native frame budget: inter frames <= $percent% of average ($FRAME_AIRTIME_MS ms of $linkKbps kbps)",
                    )
                }
        }
    }

    fun updateBitrate(bitRate: Int) {
        if (bitRate <= 0 || bitRate == activeBitrate) return
        bitrateProbe.onRequest(System.nanoTime(), activeBitrate, bitRate)
        activeBitrate = bitRate
        rateTrim.onRequestChanged()
        applyEncoderTarget(if (rateControl == EncoderRateControl.AUTOMATIC) rateTrim.encoderTargetBps(bitRate) else bitRate)
    }

    /** Sets the bitrate the encoder itself targets; [activeBitrate] stays the app's request. */
    private fun applyEncoderTarget(bitRate: Int) {
        if (usesNativeEncoder) {
            nativeWorker?.post {
                runCatching { nativeEncoder?.updateBitrate(bitRate) }
                    .onSuccess {
                        onStatus("Encoder target: ${bitRate / 1_000} kbps")
                    }
                    .onFailure { error ->
                        onStatus(
                            "Native $nativeLabel rejected bitrate update: " +
                                (error.message ?: error.javaClass.simpleName),
                        )
                    }
            }
            return
        }
        worker.post {
            encoderTargetBps = bitRate
            // A shaped keyframe or scene cut is in flight: its restore applies this target.
            if (shapedKeyFramePending || sceneCutShaper.pending) return@post
            val activeCodec = codec ?: return@post
            val codecTarget = codecTargetBps()
            runCatching {
                activeCodec.setParameters(
                    Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, codecTarget) },
                )
            }.onSuccess {
                onStatus(
                    "Encoder target: ${codecTarget / 1_000} kbps" +
                        if (codecTarget != bitRate) " (${codecTargetCorrections()} of ${bitRate / 1_000})" else "",
                )
            }.onFailure { error ->
                onStatus("Encoder rejected bitrate update: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    /** Thermal speed floor for the native encoder (MemoryThermalPolicy.nativeSpeedFloor); 0 clears it. */
    @Volatile private var thermalSpeedFloor = 0

    fun updateThermalSpeedFloor(floor: Int) {
        thermalSpeedFloor = floor
    }

    /**
     * Experimental frame-rate arm: re-applies the codec target when the delivered FPS (measured over
     * the last second of output timestamps) changes, at most once per second.
     */
    private fun recordDeliveredFrameRate(ptsUs: Long) {
        val fps = deliveredFrameRate.record(ptsUs)?.coerceIn(1, settings.fps) ?: return
        val nowNs = System.nanoTime()
        if (fps == frameRateLimitFps || nowNs - framerateAppliedNs < 1_000_000_000L) return
        frameRateLimitFps = fps
        framerateAppliedNs = nowNs
        applyCodecTarget("experimental framerate adjuster at $fps delivered FPS")
    }

    /**
     * The software encoder's live p95 encode time as a fraction of the frame interval at [effectiveFps],
     * or null (hardware encoder, or fewer than 30 frames measured since this encoder started).
     */
    fun liveEncodeLoad(effectiveFps: Int): Double? {
        if (!usesNativeEncoder || effectiveFps <= 0) return null
        val encode = nativeEncodeLatency.snapshot()
        return if (encode.samples >= LIVE_LOAD_MIN_SAMPLES) encode.p95Ms * effectiveFps / 1_000.0 else null
    }

    /**
     * F16 fix 2 (Experimental, `test_call_fps_follow`; set only for an Automatic call): follows the camera's real rate in
     * sustained low light. Its rate is one more limit beside [updateFrameRate]'s.
     */
    @Volatile internal var fpsFollow: CallFpsFollow? = null

    /** The last limit [updateFrameRate] was given (the link's), so the follower can be combined with it. */
    @Volatile private var lastMaximumFps = Int.MAX_VALUE

    fun updateFrameRate(maximumFps: Int) {
        lastMaximumFps = maximumFps
        val requestedFps = minOf(settings.fps, maximumFps, fpsFollow?.effectiveFps ?: Int.MAX_VALUE).coerceAtLeast(1)
        worker.post {
            if (debugRawInputFile != null) {
                rawInputFps = requestedFps
                onStatus("Raw fixture frame rate: $requestedFps FPS")
                return@post
            }
            gpuCapture?.let { capture ->
                capture.setMaxFps(requestedFps)
                onStatus("Frame rate limit: $requestedFps FPS (GPU scale capture)")
                return@post
            }
            val nextRange = selectFpsRange(requestedFps)
            if (nextRange == targetFpsRange) return@post
            targetFpsRange = nextRange
            applyRepeatingRequest()
            onStatus("Frame rate limit: ${nextRange?.upper ?: settings.fps} FPS")
        }
    }

    private fun keyFrameShapingFraction(): Double? =
        DebugTunables.keyFrameShaping?.takeIf { !usesNativeEncoder && rateControl == EncoderRateControl.AUTOMATIC }

    /**
     * Experimental temporal layering: LayeringPolicy turns droppable frames on while a link of this
     * source is tight. Without the experiment it has no effect.
     */
    fun setDroppableFrames(wanted: Boolean) {
        droppableFramesWanted = wanted
    }

    /** Parsed VP9 quantizers of the last report window, plus the active burst experiments. */
    private fun quantizerSummary(): String {
        val quantizers = quantizerWindow.summaryAndReset()?.let { ", $it" }.orEmpty()
        val headroom = quantizerHeadroom?.let { ", experimental q-floor ${DebugTunables.vp9QuantizerFloor} " +
            "factor ${roundedForLog(it.factor, 2)}" }.orEmpty()
        val cuts = if (sceneCutsDetected > 0 || sceneCutShaper.shapedCuts > 0) {
            ", scene cuts $sceneCutsDetected detected/${sceneCutShaper.shapedCuts} shaped (${sceneCutShaper.mode.label})"
        } else {
            ""
        }
        return quantizers + headroom + cuts
    }

    private fun layeringSummary(): String = when {
        !experimentalTemporalLayers -> ""
        usesNativeEncoder -> ", experimental temporal layers ${if (nativeTemporalLayers) 2 else 1}, droppable " +
            "${nativeDroppability.droppableFrames}/${nativeDroppability.examinedFrames} verified"
        else -> bitstreamDroppability?.let {
            ", experimental droppable ${it.droppableFrames}/${it.examinedFrames} verified" +
                if (temporalLayeringRequested) " (layering requested)" else ""
        } ?: ""
    }

    /**
     * The back-off verdict for one request, with a DEBUG setup-timing line `BENCHMARK key_frame_request: honored= | force= |
     * sinceLastMs= | intervalMs=`: a joining viewer two hops away waits for the next honoured request (stream join gap,
     * 2026-10-26: authenticated-to-first-header median 402 ms direct, 1127 ms through one relay).
     */
    private fun honorKeyFrameRequest(force: Boolean, path: String): Boolean {
        val nowNs = System.nanoTime()
        val sinceLastMs = keyFrameRequestBackoff.sinceLastHonoredMs(nowNs)
        val honored = keyFrameRequestBackoff.shouldHonor(nowNs, force)
        if (BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK key_frame_request: honored=$honored | force=$force | path=$path | " +
                "sinceLastMs=${sinceLastMs ?: "none"} | intervalMs=${keyFrameRequestBackoff.currentIntervalMs()}",
        )
        return honored
    }

    /** Design HC: the IDR deadline of a pending keyframe request (worker thread). */
    private val hcIdrCheck: Runnable = Runnable {
        val deadline = hcIdrDeadline ?: return@Runnable
        if (closed.get() || hcFailed) return@Runnable
        when (deadline.poll(System.nanoTime() / 1_000_000L)) {
            IdrRequestDeadline.Action.NONE -> if (deadline.pending) worker.postDelayed(hcIdrCheck, IdrRequestDeadline.TIMEOUT_MS)
            IdrRequestDeadline.Action.REPEAT -> {
                Log.w("BluetoothVideo", "BENCHMARK hc_idr_request: encoder=$activeEncoderName | action=repeat | misses=${deadline.misses}")
                requestKeyFrame(force = true)
            }
            IdrRequestDeadline.Action.GIVE_UP -> hcEndVideo("no_idr")
        }
    }

    /**
     * Design HC: the packetised form of one encoder output buffer, or null when nothing is sent for it (a codec-config
     * buffer, which only fills the cache; a buffer the packetiser or the sender's own gate refuses; video already ended).
     */
    private fun hcOutput(encoded: ByteArray, info: MediaCodec.BufferInfo): PacketiserResult.Packet? {
        if (hcFailed) return null
        val packetiser = hcPacketiser ?: return null
        val configBuffer = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        hcVectors?.onEncoderBuffer(encoded, configBuffer, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, info.presentationTimeUs)
        if (configBuffer) {
            runCatching { packetiser.onCodecConfig(encoded) }.onFailure { error ->
                Log.w("BluetoothVideo", "BENCHMARK hc_packetiser: encoder=$activeEncoderName | error=config_${error.javaClass.simpleName}")
            }
            return null
        }
        val result = runCatching { packetiser.onAccessUnit(encoded, info.presentationTimeUs) }
            .getOrElse { PacketiserResult.Error("malformed") }
        val packet = when (result) {
            is PacketiserResult.Packet -> result
            is PacketiserResult.Error -> {
                Log.w(
                    "BluetoothVideo",
                    "BENCHMARK hc_packetiser: encoder=$activeEncoderName | error=${result.reason} | bytes=${encoded.size} | " +
                        "pts=${info.presentationTimeUs}",
                )
                if (result.reason == "reorder") {
                    hcEndVideo("reorder")
                } else {
                    requestKeyFrame(force = true)
                }
                return null
            }
        }
        // Never send what the receivers' gate would reject: the same rules run here first.
        when (val verdict = hcSelfCheck?.check(packet.payload, if (packet.keyFrame) StreamProtocol.FLAG_KEY_FRAME else 0)) {
            is AuVerdict.Reject -> {
                Log.w(
                    "BluetoothVideo",
                    "BENCHMARK au_reject: side=sender | codec=${hcCodec?.label} | rule=${verdict.rule} | type=${verdict.nalType} | " +
                        "bytes=${verdict.bytes} | keyframe=${verdict.keyFrame} | reason=${verdict.reason} | encoder=$activeEncoderName",
                )
                requestKeyFrame(force = true)
                return null
            }
            is AuVerdict.Close -> {
                hcEndVideo("unsupported_bitstream:${verdict.reason}")
                return null
            }
            else -> Unit
        }
        hcIdrDeadline?.onAccessUnit(packet.keyFrame)
        if (packet.keyFrame && packet.codedSize != null && BuildConfig.DEBUG) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK hc_keyframe: encoder=$activeEncoderName | coded=${packet.codedSize.first}x${packet.codedSize.second} | " +
                    "bytes=${packet.payload.size} | encoderKeyFlag=${info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0}",
            )
        }
        hcVectors?.let { capture ->
            if (capture.onAccessUnit(packet.payload, packet.keyFrame, info.presentationTimeUs, packet.codedSize)) {
                requestKeyFrame(force = true)
            }
        }
        return packet
    }

    /**
     * Design HC: the `KEY_LEVEL` this session requests: the lowest standard level covering its size, FPS and bitrate,
     * capped at the encoder's highest listed level ([VideoCodecSwitches.hcSessionLevel]; off: the listed maximum).
     */
    private fun hcRequestedLevel(): Int? = videoCodec.level?.let { listed ->
        if (!VideoCodecSwitches.hcSessionLevel) {
            listed
        } else {
            HcEncoderEligibility.levelFor(
                videoCodec.protocolId, listed, settings.resolution.width, settings.resolution.height, settings.fps,
                maxOf(settings.bitRate, activeBitrate),
            )
        }
    }

    /** Design HC: ends this encoder's video (`no_idr`, `reorder`, unsupported bitstream); the component is ineligible for this process. */
    private fun hcEndVideo(reason: String) {
        if (hcFailed) return
        hcFailed = true
        HcComponentHealth.markIneligible(activeEncoderName, reason)
        hcVectors?.finish("ended_$reason")
        Log.w("BluetoothVideo", "BENCHMARK hc_video_end: encoder=$activeEncoderName | reason=$reason")
        notifyFailure("Video ended: ${hcCodec?.label} encoder $reason")
    }

    fun requestKeyFrame(force: Boolean = false) {
        if (usesNativeEncoder) {
            if (!honorKeyFrameRequest(force, "native")) return
            nativeKeyFrameRequested.set(true)
            onStatus("Requested a native $nativeLabel recovery keyframe")
            return
        }
        worker.post {
            val activeCodec = codec ?: return@post
            if (!honorKeyFrameRequest(force, "mediacodec")) return@post
            val shaping = keyFrameShapingFraction()
            runCatching {
                activeCodec.setParameters(
                    Bundle().apply {
                        shaping?.let { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, (codecTargetBps() * it).toInt()) }
                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    },
                )
            }.onSuccess {
                if (shaping != null) {
                    shapedKeyFramePending = true
                    worker.removeCallbacks(restoreShapedTarget)
                    worker.postDelayed(restoreShapedTarget, SHAPED_KEY_FRAME_TIMEOUT_MS)
                }
                hcIdrDeadline?.let { deadline ->
                    deadline.onRequest(System.nanoTime() / 1_000_000L)
                    worker.removeCallbacks(hcIdrCheck)
                    worker.postDelayed(hcIdrCheck, IdrRequestDeadline.TIMEOUT_MS)
                }
                onStatus("Requested a recovery keyframe")
            }.onFailure { error ->
                onStatus("Encoder rejected keyframe request: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    /**
     * The AE target range for a frame-rate limit ([CameraFpsRanges]). The LEGACY-level smallest-lower rule is the
     * Experimental `test_camera_legacy_ae_range` arm; otherwise the former rule.
     */
    private fun selectFpsRange(maximumFps: Int): Range<Int>? {
        val legacy = CameraSwitches.legacyAeRange && CameraLevelCodes.isLegacy(hardwareLevel)
        val chosen = CameraFpsRanges.select(availableFpsRanges.map { FpsRange(it.lower, it.upper) }, maximumFps, legacy)
            ?: return null
        return availableFpsRanges.first { it.lower == chosen.lower && it.upper == chosen.upper }
    }

    private fun applyRepeatingRequest() {
        val activeCamera = camera ?: return
        val activeSession = session ?: return
        if (captureTargets.isEmpty()) return
        runCatching {
            val request = buildCaptureRequest(activeCamera, captureTargets)
            // The completion callback costs one call per frame: DEBUG, or the Experimental release monitor (V-22/V-23).
            val callback = if (BuildConfig.DEBUG || CameraSwitches.releaseCaptureTiming || CameraSwitches.rotateAndCropNone) {
                captureTimingCallback
            } else {
                null
            }
            activeSession.setRepeatingRequest(request, callback, worker)
        }.onFailure { error ->
            notifyFailure("Camera frame-rate update failed: ${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    private fun buildCaptureRequest(activeCamera: CameraDevice, targets: List<Surface>): CaptureRequest =
        activeCamera.createCaptureRequest(captureTemplate).apply {
            targets.forEach(::addTarget)
            if (continuousAutoFocusAvailable) {
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                )
            }
            targetFpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            // Experimental (V-23): no system rotate-and-crop on large-screen / folded layouts (the encoder rotates itself).
            if (CameraSwitches.rotateAndCropNone && rotateAndCropNoneSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                set(CaptureRequest.SCALER_ROTATE_AND_CROP, CameraMetadata.SCALER_ROTATE_AND_CROP_NONE)
            }
        }.build()

    private val captureTimingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (closed.get()) return
            if (!cameraFirstFrameTold && onJoinStage != null) {
                cameraFirstFrameTold = true
                onJoinStage.invoke(
                    CallJoinStage.CAMERA_FIRST_FRAME,
                    "path=${inputPathLabel()} | " + firstCaptureDetail(result),
                )
            }
            copyCaptureLoss?.let { loss -> result.get(CaptureResult.SENSOR_TIMESTAMP)?.let(loss::onDelivered) }
            if (BuildConfig.DEBUG) {
                exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                sensorFrameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION)
                cameraPipelineDepth = result.get(CaptureResult.REQUEST_PIPELINE_DEPTH)
            }
            if (CameraSwitches.releaseCaptureTiming) {
                captureIntervals.onCapture(System.nanoTime())?.let { window ->
                    Log.i("BluetoothVideo", captureIntervals.line(window))
                }
            }
            if (CameraSwitches.rotateAndCropNone && !rotateAndCropLogged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                rotateAndCropLogged = true
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK camera_rotate_and_crop: requested=NONE | supported=$rotateAndCropNoneSupported | " +
                        "effective=${result.get(CaptureResult.SCALER_ROTATE_AND_CROP) ?: "unreported"}",
                )
            }
        }

        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
            if (closed.get()) return
            surfaceCaptureLoss?.onBufferLost(encoderTarget = target === encoderSurface)
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            if (closed.get()) return
            copyCaptureLoss?.onCaptureFailed()
            surfaceCaptureLoss?.onCaptureFailed(
                flushed = failure.reason == CaptureFailure.REASON_FLUSHED,
                imageCaptured = failure.wasImageCaptured(),
            )
        }
    }

    private fun copyYuv420(source: Image, target: Image) {
        val visible = visibleRegion(source)
        source.planes.zip(target.planes).forEachIndexed { planeIndex, (sourcePlane, targetPlane) ->
            val targetWidth = planeSize(settings.resolution.width, planeIndex)
            val targetHeight = planeSize(settings.resolution.height, planeIndex)
            val sourceCrop = YuvPlaneCopy.centerCropWithin(
                YuvPlaneCopy.planeRegion(visible, planeIndex), targetWidth, targetHeight,
            )
            val sourceBuffer = sourcePlane.buffer
            val targetBuffer = targetPlane.buffer
            val sourceOffset = sourceBuffer.position()
            val targetOffset = targetBuffer.position()
            if (sourceCrop.width == targetWidth && sourceCrop.height == targetHeight &&
                !targetBuffer.isReadOnly && NativeAomBridge.available && NativeAomBridge.nativeCopyPlane(
                    sourceBuffer, sourceOffset + sourceCrop.top * sourcePlane.rowStride +
                        sourceCrop.left * sourcePlane.pixelStride, sourceBuffer.limit(),
                    sourcePlane.rowStride, sourcePlane.pixelStride,
                    targetBuffer, targetOffset, targetBuffer.limit(),
                    targetPlane.rowStride, targetPlane.pixelStride, targetWidth, targetHeight,
                )
            ) return@forEachIndexed
            check(
                copyPlaneOrClampTail(
                    sourceBuffer, sourceOffset, sourcePlane.rowStride, sourcePlane.pixelStride, sourceCrop,
                    targetBuffer, targetOffset, targetPlane.rowStride, targetPlane.pixelStride, targetWidth, targetHeight,
                ),
            ) { "YUV plane $planeIndex layout is outside its buffer (row ${sourcePlane.rowStride}/${targetPlane.rowStride})" }
        }
    }

    private fun nativeThreadCount(resolution: VideoResolution): Int {
        bench?.libaomThreads?.let { return it }
        val cores = Runtime.getRuntime().availableProcessors()
        val pixels = resolution.width.toLong() * resolution.height
        val count = when {
            pixels <= 640L * 360L && cores > 2 -> 2
            // Row-MT uses the extra cores; the speed governor turns the headroom into quality.
            pixels <= 720L * 480L && cores >= 8 -> 4
            pixels <= 720L * 480L && cores > 3 -> 3
            pixels > 720L * 480L && cores > 4 -> 4
            pixels >= 320L * 180L && cores > 2 -> 2
            else -> 1
        }
        // Experimental test_userspace32_tier (default off): a 32-bit userspace on a 64-bit CPU caps the libaom threads.
        return Userspace32Tier.nativeThreadCap(count, ThermalSwitches.userspace32Tier, android.os.Process.is64Bit())
    }

    private companion object {
        /** Encode samples needed before the live load or the per-megapixel time means anything. */
        const val LIVE_LOAD_MIN_SAMPLES = 30

        /**
         * Camera frames that may wait for the native encoder. Two dropped source frames on the A15s
         * whenever one encode overran the 66.7 ms budget (lane-a 2026-10-08); four turn such a burst
         * into latency instead of frame loss. Phones within budget never hold more than one.
         */
        const val NATIVE_INPUT_BUFFERS = 4
        /** An encoder that ignores the keyframe request gets its full target back after this. */
        const val SHAPED_KEY_FRAME_TIMEOUT_MS = 1_000L
        const val CADENCE_REPORT_INTERVAL_NS = 5_000_000_000L
        const val BITRATE_ADJUSTER_LOG_INTERVAL_NS = 1_000_000_000L
        const val MAX_TRACE_ENCODE_LATENCY_US = 60_000_000L
        const val CODEC_CLOSE_TIMEOUT_SECONDS = 5L
        /** configure() and start() of one init attempt; a hung vendor component is abandoned (V-16). */
        const val ENCODER_INIT_STAGE_TIMEOUT_MS = 10_000L
        const val ENCODER_RELEASE_TIMEOUT_MS = 5_000L
        /** A first configure() above this is logged as suspicious (Chromium's threshold). */
        const val CONFIGURE_SLOW_MS = 800L
        const val MAX_ALTERNATE_ENCODERS = 2
        const val HEALTH_SAMPLE_INTERVAL_NS = 500_000_000L
        const val FIRST_FRAMES_CHECKED = 3
        const val FIRST_FRAMES_CHECKED_DEBUG = 8
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        openJoin?.onEncoderClosed()
        hcVectors?.finish("closed")
        if (debugRawInputFile != null && rawStartedUs > 0L) {
            val frameBytes = settings.resolution.width.toLong() * settings.resolution.height * 3 / 2
            debugMediaTrace?.setRawTimeline(debugRawInputFile.name, debugRawInputFile.length() / frameBytes,
                rawStartedUs, rawStartedUs + (System.nanoTime() - rawStartedNs) / 1_000L)
        }
        worker.removeCallbacks(rawFeed)
        imageReader?.setOnImageAvailableListener(null, null)
        referenceImageReader?.setOnImageAvailableListener(null, null)
        // The EGL surface of this encoder's input surface goes before the codec is released.
        gpuCapture?.detach(this, if (retainGpuCaptureOnClose) GpuScaleCapture.RESTART_KEEP_ALIVE_MS else 0L)
        gpuCapture = null
        closeCameraAndCodecOnWorker()
        rawInput?.close()
        rawInput = null
        rawFrame = null
        pendingImage?.close()
        pendingImage = null
        imageReader?.close()
        referenceImageReader?.close()
        nativeEncoder?.let {
            val closed = CountDownLatch(1)
            nativeWorker?.post {
                runCatching { nativeEncoder?.close() }
                nativeEncoder = null
                closed.countDown()
            }
            runCatching { closed.await(5, TimeUnit.SECONDS) }
        }
        nativeEncoderThread?.quitSafely()
        runCatching { nativeEncoderThread?.join(5_000) }
        nativeInputBuffers?.clear()
        nativeInputBuffers = null
        referenceRecorder?.close()
        if (BuildConfig.DEBUG && (debugRawInputFile != null && rawStartedUs > 0L ||
                debugRawInputFile == null && usesNativeEncoder && firstVideoFrameSeen)) Log.i(
            "BluetoothVideo",
            "Encoder input FINAL: capture drops $nativeInputDrops" +
                if (debugRawInputFile != null) ", raw input drops $rawInputDrops" else "",
        )
        if (firstVideoFrameSeen) surfaceCaptureLoss?.let { Log.i("BluetoothVideo", it.finalLine()) }
        if (firstVideoFrameSeen) copyCaptureLoss?.let { Log.i("BluetoothVideo", it.finalLine()) }
        recordBurstProfile()
        debugMediaTrace?.close()
        debugStageTrace?.close()
        debugMediaTrace = null
        previewSurface = null
        captureTargets = emptyList()
        // A GPU-scaled capture owns the preview surface (it outlives this encoder across rung changes).
        if (!gpuActive) (viewfinderSurface ?: reusableViewfinderSurface?.second)?.close()
        viewfinderSurface = null
        encoderSurface?.release()
        workerThread.quitSafely()
    }

    /**
     * Records this session's largest frame (in seconds of its own stream) and, for libaom, its encode
     * load, for the experimental encoder ranking. Recorded in every session so the
     * experiment has measurements when it is turned on; nothing reads them while it is off.
     */
    private fun recordBurstProfile() {
        val sessionBurst = burstAccumulator.sessionBurstSeconds(settings.fps * EncoderBurstRanking.MIN_SESSION_SECONDS)
            ?: return
        val encodeLoad = if (usesNativeEncoder) {
            nativeEncodeLatency.snapshot().takeIf { it.samples > 0 }?.let { it.p95Ms * settings.fps / 1_000.0 }
        } else {
            null
        }
        runCatching {
            val store = EncoderBurstProfileStore(context)
            // Encode time per megapixel (ms), for the Auto ladder's per-size load prediction.
            nativeEncodeLatency.snapshot().takeIf { usesNativeEncoder && it.samples >= LIVE_LOAD_MIN_SAMPLES }?.let { encode ->
                val megapixels = settings.resolution.width.toLong() * settings.resolution.height / 1_000_000.0
                if (megapixels > 0.0 && encode.p95Ms > 0.0) {
                    store.saveMsPerMegapixel(
                        activeEncoderName,
                        EncoderBurstRanking.mergeMsPerMegapixel(store.msPerMegapixel(activeEncoderName), encode.p95Ms / megapixels),
                    )
                }
            }
            val record = EncoderBurstRanking.merge(
                store.load(activeEncoderName),
                sessionBurst,
                encodeLoad,
                System.currentTimeMillis(),
            )
            store.save(activeEncoderName, record)
            Log.i(
                "BluetoothVideo",
                "BENCHMARK encoder_burst_profile v1 " + JSONObject()
                    .put("encoder", activeEncoderName)
                    .put("session_burst_s", roundedForLog(sessionBurst, 3))
                    .put("largest_bytes", burstAccumulator.largestBytes)
                    .put("frames", burstAccumulator.frames)
                    .put("encode_load", encodeLoad?.let { roundedForLog(it, 2) } ?: JSONObject.NULL)
                    .put("record_burst_s", roundedForLog(record.burstSeconds, 3))
                    .put("record_fits", record.fits)
                    .put("sessions", record.sessions)
                    .toString(),
            )
        }.onFailure { error -> Log.w("BluetoothVideo", "Encoder burst profile not saved", error) }
    }

    private fun closeCameraAndCodecOnWorker() {
        val released = CountDownLatch(1)
        val releaseCodec = Runnable {
            try {
                captureSessionGeneration++
                rebuilder.cancel()
                cancelStallChecks()
                val activeSession = session
                val activeCamera = camera
                val activeCodec = codec
                session = null
                camera = null
                codec = null
                // The camera stops feeding the codec Surface first, then the encoder stops and is released
                // (never stop() while the camera still targets the Surface); codec calls are bounded (V-15/V-16).
                EncoderShutdown { message -> Log.w("BluetoothVideo", message) }.run(
                    EncoderShutdown.steps(
                        stopRepeating = { activeSession?.stopRepeating() },
                        closeSession = { activeSession?.close() },
                        closeCamera = { activeCamera?.close() },
                        stopCodec = { activeCodec?.stop() },
                        releaseCodec = { activeCodec?.release() },
                    ),
                )
            } finally {
                released.countDown()
            }
        }
        if (Looper.myLooper() == workerThread.looper || !worker.post(releaseCodec)) {
            releaseCodec.run()
            return
        }
        runCatching { released.await(CODEC_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
    }
}

/** Locale-independent rounding for JSON log values (a comma decimal separator must not throw). */
internal fun roundedForLog(value: Double, decimals: Int): Double {
    var scale = 1.0
    repeat(decimals) { scale *= 10 }
    return Math.round(value * scale) / scale
}

internal fun codecPresentationTimeEpochOffsetUs(
    presentationTimeUs: Long,
    elapsedRealtimeUs: Long,
    monotonicTimeUs: Long,
): Long {
    val deltaUs = elapsedRealtimeUs - presentationTimeUs
    if (kotlin.math.abs(deltaUs) <= MAX_CODEC_PRESENTATION_EPOCH_DELTA_US) return 0L
    return if (kotlin.math.abs(monotonicTimeUs - presentationTimeUs) <= MAX_CODEC_PRESENTATION_EPOCH_DELTA_US) {
        // Preserve capture age when converting CLOCK_MONOTONIC to the audio's boot-time epoch.
        elapsedRealtimeUs - monotonicTimeUs
    } else {
        deltaUs
    }
}

private const val MAX_CODEC_PRESENTATION_EPOCH_DELTA_US = 5_000_000L

/**
 * Recovery keyframes on hard content can be 40-130 KB; when one overflowed a link, an immediate
 * second request produced another equally large keyframe (2-27 keyframes per 30 s run on the same
 * input). Unforced requests are spaced 1 s apart, doubling to 8 s while they keep arriving back to
 * back, and reset after 10 calm seconds. Forced requests (a new viewer joining) are never delayed.
 */
internal class KeyFrameRequestBackoff(
    private val minIntervalNs: Long = 1_000_000_000L,
    private val maxIntervalNs: Long = 8_000_000_000L,
    private val calmResetNs: Long = 10_000_000_000L,
) {
    private var lastHonoredNs: Long? = null
    private var intervalNs = minIntervalNs

    /** Milliseconds since the last honoured request, or null before the first (diagnostics only). */
    @Synchronized
    fun sinceLastHonoredMs(nowNs: Long): Long? = lastHonoredNs?.let { (nowNs - it) / 1_000_000L }

    /** The current minimum spacing between honoured unforced requests (diagnostics only). */
    @Synchronized
    fun currentIntervalMs(): Long = intervalNs / 1_000_000L

    @Synchronized
    fun shouldHonor(nowNs: Long, force: Boolean): Boolean {
        val last = lastHonoredNs
        if (!force && last != null) {
            val sinceNs = nowNs - last
            if (sinceNs < intervalNs) return false
            intervalNs = when {
                sinceNs >= calmResetNs -> minIntervalNs
                sinceNs < intervalNs * 2 -> (intervalNs * 2).coerceAtMost(maxIntervalNs)
                else -> intervalNs
            }
        }
        lastHonoredNs = nowNs
        return true
    }
}

/** Experimental temporal layering: the MediaCodec schema requested from hardware encoders. */
private const val TEMPORAL_LAYERING_SCHEMA = "android.generic.2"

private fun MediaFormat.getStringOrNull(key: String): String? =
    if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null

/** Layer 0 on even frames since the last keyframe; always 0 without layering. */
internal fun nativeTemporalLayer(enabled: Boolean, frameIndexSinceKey: Long): Int =
    if (enabled && frameIndexSinceKey % 2 == 1L) 1 else 0

internal fun nativePacketFlags(keyFrame: Boolean, temporalLayer: Int): Int = when {
    keyFrame -> StreamProtocol.FLAG_KEY_FRAME
    temporalLayer == 1 -> StreamProtocol.FLAG_DISCARDABLE
    else -> 0
}

private const val FRAME_AIRTIME_MS = 250L

/** Largest inter frame as a percentage of the average frame that crosses [linkKbps] in FRAME_AIRTIME_MS. */
internal fun maxInterFramePercent(linkKbps: Int, bitrateBps: Int, fps: Int): Int =
    (100L * linkKbps * FRAME_AIRTIME_MS * fps / bitrateBps.coerceAtLeast(1)).toInt().coerceAtLeast(MIN_INTER_FRAME_PERCENT)

/** Below this the rate control cannot follow normal motion. */
private const val MIN_INTER_FRAME_PERCENT = 150
