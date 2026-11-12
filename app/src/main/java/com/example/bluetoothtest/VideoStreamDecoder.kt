package com.example.bluetoothtest

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Trace
import android.util.Log
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class DecoderPipelineStats(
    val queue: EncodedFrameQueueStats,
    val queuedToDecoderFrames: Int,
    val submittedForRenderFrames: Int,
    val renderedFrames: Int,
    val decoderWait: LatencySnapshot,
    val scheduledRenderDelay: LatencySnapshot,
    val renderedFrameInterval: LatencySnapshot,
)

internal fun decoderCodecSpecificData(
    codecId: Int,
    codecSpecificData: List<ByteArray>,
): List<ByteArray> = if (codecId == StreamProtocol.CODEC_AV1) {
    codecSpecificData.takeLast(1)
} else if (codecId == StreamProtocol.CODEC_H264 || codecId == StreamProtocol.CODEC_HEVC) {
    // Design HC: the csd is the newest keyframe's parameter sets only (H.264 csd-0 SPS + csd-1 PPS, HEVC csd-0
    // VPS+SPS+PPS), already one complete set; never merged with an older encoder's.
    codecSpecificData.map(ByteArray::copyOf)
} else {
    codecSpecificData.fold(mutableListOf<ByteArray>()) { selected, bytes ->
        if (selected.none { it.contentEquals(bytes) }) selected.add(bytes)
        selected
    }
}

/**
 * The size a decoder is configured at: the one the first keyframe carries when [firstPacket] is a keyframe
 * whose bitstream says (the header is the stream's maximum, and a ladder stream starts below it), else the
 * header's.
 */
internal fun initialCodedSize(
    header: StreamProtocol.Header,
    firstPacket: StreamProtocol.Packet?,
    codecSpecificData: List<ByteArray>,
): Pair<Int, Int> {
    val isKeyFrame = firstPacket != null && !firstPacket.isAudio &&
        firstPacket.flags and StreamProtocol.FLAG_KEY_FRAME != 0 &&
        firstPacket.flags and StreamProtocol.FLAG_CODEC_CONFIG == 0
    val parsed = if (isKeyFrame) {
        CodedVideoSize.ofKeyFrame(header.codecId, firstPacket!!.payload, codecSpecificData)
    } else {
        null
    }
    return parsed ?: (header.width to header.height)
}

/** The decoder MIME type for a stream codec ID; null for an ID no decoder handles. */
internal fun videoDecoderMime(codecId: Int): String? = when (codecId) {
    StreamProtocol.CODEC_VP9 -> MediaFormat.MIMETYPE_VIDEO_VP9
    StreamProtocol.CODEC_AV1 -> MediaFormat.MIMETYPE_VIDEO_AV1
    StreamProtocol.CODEC_H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
    StreamProtocol.CODEC_HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
    else -> null
}

class VideoStreamDecoder(
    private val header: StreamProtocol.Header,
    private val surface: Surface,
    codecSpecificData: List<ByteArray>,
    private val preferLowLatency: Boolean = true,
    private val playbackDelayProfile: PlaybackDelayProfile = PlaybackDelayProfile.BALANCED,
    private val playoutClock: MediaPlayoutClock = playbackDelayProfile.newPlayoutClock(),
    preferredDecoderName: String? = null,
    /** Why [preferredDecoderName] is named; an explicit (test_decoder) request is never substituted. */
    decoderRequest: DecoderRequest = if (preferredDecoderName != null) DecoderRequest.EXPLICIT else DecoderRequest.NONE,
    queueCapacity: Int = playbackDelayProfile.videoQueueCapacity(header.fps),
    debugPresentationTiming: Boolean = false,
    debugReleaseAtPlayout: Boolean = true,
    private val onRecoveryStateChanged: (Boolean) -> Unit = {},
    debugTraceSession: String? = null,
    /**
     * Experimental first call frame on arrival (DebugTunables.callFirstFrameNow, calls only): the
     * first frame is fed and released at once instead of at its playout target. False: unchanged.
     */
    private val presentFirstFrameImmediately: Boolean = false,
    /**
     * Experimental first call frame with a playout ramp (DebugTunables.callFirstFrameRamp, calls only): the first frame
     * is shown at once and the following frames are slowed (bounded) until they meet the playout target
     * ([FirstFrameRamp]). Takes precedence over [presentFirstFrameImmediately]. False: unchanged.
     */
    firstFrameRamp: Boolean = false,
    /**
     * Experimental viewer frame age (null = off): the source timestamp of each frame the codec
     * rendered to the surface. Called on the codec worker thread; it must only record.
     */
    private val onFrameRendered: ((Long) -> Unit)? = null,
    /**
     * The first packet the receiver is about to offer. When it is a keyframe, the decoder is configured at
     * the picture size the bitstream carries (the header names the stream's maximum, and under the Auto
     * ladder the encoder starts at a lower rung), with the header as the adaptive-playback maximum.
     */
    firstPacket: StreamProtocol.Packet? = null,
    /**
     * Size-change handling (DebugTunables.decoderSizeChangeRebuild, default on): a keyframe whose size or
     * codec configuration differs from the decoder's re-creates the decoder. False restores the earlier
     * behaviour: one decoder configured at the header size, a size change only logged.
     */
    private val sizeChangeRebuild: Boolean = DebugTunables.decoderSizeChangeRebuild,
    /** Told (on the offering thread) the new coded size when a keyframe changes it, for the display aspect. */
    private val onCodedSizeChanged: ((width: Int, height: Int) -> Unit)? = null,
    /** `test_decoder_stall_recovery` ([DecoderSwitches.stallRecovery], default on since 2026-11-10); false: a stall is only logged. */
    private val stallRecovery: Boolean = DecoderSwitches.stallRecovery,
    /** Experimental `test_decoder_low_latency_ladder` ([DecoderSwitches.lowLatencyLadder]). */
    private val lowLatencyLadder: Boolean = DecoderSwitches.lowLatencyLadder,
    /** Experimental `test_decoder_priority` ([DecoderSwitches.realtimePriority]). */
    private val realtimePriority: Boolean = DecoderSwitches.realtimePriority,
    /**
     * Experimental call pre-warm (`test_call_prewarm`; null otherwise): the first create of this decoder asks it for a
     * component already allocated under that name (null: create as before). Only the very first attempt asks.
     */
    private val preallocated: ((String) -> MediaCodec?)? = null,
    /** DEBUG call join stages (null for streams): told once, on the codec thread, when the first picture leaves the decoder. */
    private val onFirstDecoded: (() -> Unit)? = null,
    /**
     * Experimental `test_call_video_restart` (calls only, [CallVideoStallRestart]): a watchdog stall is handed to the
     * caller as a [DecoderStalledException] from [offer] (a receiver waiting for queue room gets it at once), and the
     * caller replaces this decoder, instead of the in-place `test_decoder_stall_recovery` arm. False: unchanged.
     */
    private val stallRestartsCaller: Boolean = false,
    /** Experimental `test_call_video_restart`: how long a full queue waits for room; null keeps the queue's 5 s. */
    queueWaitNs: Long? = null,
    /**
     * Experimental `test_call_video_restart`: components a stall excluded earlier in this call. They are skipped for the
     * next decoder that supports the stream ([stallExcludedCandidates]) unless nothing else is left. Empty: unchanged.
     */
    private val stallExcludedDecoders: Set<String> = emptySet(),
) : Closeable {
    private val running = AtomicBoolean(true)
    private val firstDecodedTold = AtomicBoolean(false)
    private val debugStageTrace = DebugStageTrace.startIfRequested(debugTraceSession, "video_decoder")
    private val playoutRamp: FirstFrameRamp? = if (firstFrameRamp) FirstFrameRamp(log = { Log.i("BluetoothVideo", it) }) else null
    private val failure = AtomicReference<Throwable?>()
    private val workerThread = HandlerThread("VideoDecoder").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val availableInputBuffers = ArrayDeque<Int>()
    /**
     * Identity of the codec instance whose callbacks count. Each initialisation attempt has its own
     * number, so a late callback (onError) from a released attempt cannot touch the working codec.
     */
    @Volatile private var activeAttempt = 0
    private var attemptSequence = 0
    private val mime = videoDecoderMime(header.codecId) ?: error("Unsupported codec ID ${header.codecId}")
    /**
     * Design HC (codecs 4 and 5): the fail-closed structural gate every video packet passes before the decoder sees it
     * (PROTOCOL "Access-unit validation"), holding the active parameter sets. Null for VP9 and AV1.
     */
    private val auGate: AccessUnitGate? = AnnexBCodec.of(header.codecId)?.let { codec ->
        AccessUnitGate(codec, header.width, header.height) { System.nanoTime() / 1_000_000L }
    }
    private val hcLabel = VideoCodecMap.label(header.codecId)
    private var hcHoldLogNs = 0L
    private val selectedCodecSpecificData = if (auGate != null) {
        // No codec-config packet exists for codecs 4 and 5: the csd comes from the first keyframe's in-band sets (or
        // the previous decoder's newest sets when recovery starts this one).
        hcFirstKeyFrameCsd(auGate.codec, header, firstPacket) ?: decoderCodecSpecificData(header.codecId, codecSpecificData)
    } else {
        decoderCodecSpecificData(header.codecId, codecSpecificData).map(ByteArray::copyOf)
    }
    /** Codecs 4 and 5 always follow keyframe size and parameter-set changes (the decoder is rebuilt on either). */
    private val followsFormat = sizeChangeRebuild || auGate != null
    private val initialSize: Pair<Int, Int> =
        if (followsFormat) {
            initialCodedSize(header, firstPacket, selectedCodecSpecificData)
        } else {
            header.width to header.height
        }
    /** What the running decoder was configured with; recovery re-queues its current csd, never the stream-start one. */
    private val formatTracker = DecoderFormatTracker(
        header.codecId, selectedCodecSpecificData, initialSize.first, initialSize.second,
    )
    /** Largest picture size configured so far: the adaptive-playback maximum of the next decoder. */
    @Volatile private var maximumWidth = maxOf(header.width, initialSize.first)
    @Volatile private var maximumHeight = maxOf(header.height, initialSize.second)
    private val pendingRebuild = AtomicReference<DecoderFormatTracker.Rebuild?>()
    private var candidateNames: List<String> = emptyList()
    // Batch D (family backlog): policy decided once per decoder; worker-thread state for the watchdog.
    private val policyTraits = DeviceTraits.current()
    private val stallWatchdogOn = CodecPolicy.stallWatchdogEnabled(policyTraits)
    private val boundedReleaseMs: Long? = CodecPolicy.boundedReleaseTimeoutMs(policyTraits)
    private val stallDetector = DecoderStallDetector(header.fps)
    private val stallEscalation = DecoderStallEscalation()
    /** Components a stall excluded for this session (worker thread; also read by the rebuild order). */
    private val excludedDecoders = LinkedHashSet<String>(stallExcludedDecoders)
    /** The next recovery keyframe re-creates the decoder (a stall) even where flush() would be allowed. */
    private val forceRebuildOnRecovery = AtomicBoolean(false)
    private var stallLogLines = 0
    private var inputsQueuedTotal = 0L
    private var outputsTotal = 0L
    private var behaviourLogNs = 0L
    private var lastOutputSize: Pair<Int, Int>? = null
    /** The configure-format rung of the running decoder (`legacy` unless an Experimental key arm is on). */
    @Volatile private var formatRung = "legacy"
    private val stallCheck = object : Runnable {
        override fun run() {
            if (!running.get() || failure.get() != null) return
            try {
                checkForStall()
            } catch (error: Exception) {
                Log.w("BluetoothVideo", "decoder watchdog tick failed: ${error.javaClass.simpleName}: ${error.message}")
            }
            worker.postDelayed(this, DecoderWatchdogLimits.CHECK_INTERVAL_MS)
        }
    }
    private val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
    private val pendingCodecSpecificData = ArrayDeque<ByteArray>()
    private val encodedFrames = EncodedFrameQueue(
        queueCapacity,
        requireInitialKeyFrame = true,
        backpressureTimeoutNs = queueWaitNs ?: EncodedFrameQueue.BACKPRESSURE_TIMEOUT_NS,
        thinDiscardable = DebugTunables.experimentalTemporalLayers,
    )
    private val decoderWait = RollingLatencyStats()
    private val scheduledRenderDelay = RollingLatencyStats()
    private val renderedFrameInterval = RollingLatencyStats()
    private val forwardingFrameInterval = RollingLatencyStats()
    private val queuedToDecoderFrames = AtomicInteger()
    private val submittedForRenderFrames = AtomicInteger()
    private val actuallyRenderedFrames = AtomicInteger()
    private val resetAtRecoveryKeyFrame = AtomicBoolean(false)
    private val decoderInputBlocked = AtomicBoolean(false)
    private val decoderRestartScheduled = AtomicBoolean(false)
    private val recovering = AtomicBoolean(false)
    private val recoveryPresentationTimeUs = AtomicLong(Long.MAX_VALUE)
    private var lastRenderedAtNs = 0L
    private var delayedDrainPending = false
    // Worker-thread only: queueInputBuffer time per PTS, consumed at decoder output.
    private val decoderInputNs = LinkedHashMap<Long, Long>()
    private val decodeLatency = DecoderLatencyWindow()
    @Volatile private var feedAheadMs = DECODER_FEED_AHEAD_MS
    // Releasing each frame at its playout moment (instead of handing the display a timestamp) took
    // ASUS->S24 Classic 1000 kbps from p95/p99 83.7/284 ms to 67.7/84.0 ms at 15.0 FPS (sweep1).
    private val releaseAtPlayout = debugReleaseAtPlayout
    @Volatile private var deferredOutputGeneration = 1L
    @Volatile private var deferredOutputCount = 0
    @Volatile private var deferredOutputHighWater = 0
    @Volatile private var deferredOutputDiscarded = 0L
    private val presentationTiming = if (BuildConfig.DEBUG && debugPresentationTiming) {
        VideoPresentationTiming(generation = 1, capacity = 512)
    } else null
    private val presentationScopeNs = if (presentationTiming != null) System.nanoTime() else 0L
    @Volatile private var presentationGeneration = 1L
    private var presentationLastReportNs = 0L
    private var presentationReport = 0L
    private var presentationFinalReported = false
    // Replaced on the worker thread when a keyframe needs a different decoder (see rebuildDecoder).
    @Volatile private lateinit var codec: MediaCodec

    @Volatile lateinit var decoderName: String
        private set
    @Volatile var lowLatencyEnabled: Boolean = false
        private set
    val renderedFrames: Int
        get() = actuallyRenderedFrames.get()

    /** The picture size the running decoder was configured for (follows keyframe size changes). */
    val codedWidth: Int
        get() = formatTracker.codedSize().first
    val codedHeight: Int
        get() = formatTracker.codedSize().second

    /** The codec configuration of the encoder now sending: what recovery and a replacement decoder start from. */
    fun currentCodecSpecificData(): List<ByteArray> = formatTracker.currentCodecSpecificData()

    init {
        val format = videoFormat(initialSize.first, initialSize.second, selectedCodecSpecificData).also {
            if (header.codecId == StreamProtocol.CODEC_AV1 && codecSpecificData.isNotEmpty()) {
                Log.i(
                    "BluetoothVideo",
                    "Using the final AV1 codec-config packet as csd-0; discarded " +
                        "${codecSpecificData.size - selectedCodecSpecificData.size} earlier packet(s).",
                )
            }
        }
        // Every non-secure decoder that supports the format, in MediaCodecList order (aliases included,
        // as the former requested-decoder lookup); the planner drops aliases only when it falls back.
        val supporting = codecInfos.filter { info ->
            !info.isEncoder && !info.name.endsWith(".secure") &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                runCatching { info.getCapabilitiesForType(mime).isFormatSupported(format) }.getOrDefault(false)
        }.map { info ->
            DecoderOption(
                info.name, info.isAlias, info.isSoftwareOnly, info.isHardwareAccelerated,
                lowLatency = lowLatencyLadder && runCatching {
                    info.getCapabilitiesForType(mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                }.getOrDefault(false),
            )
        }
        val frameworkChoice = MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(format)
        val fallbackEnabled = CodecPolicy.decoderInitFallbackEnabled(DeviceTraits.current())
        // Bounded (CodecPolicy.MAX_DECODER_INIT_ATTEMPTS): callers build this decoder inside their packet
        // path (PrivateCallMedia.offer is synchronized with audio), so a failing start can delay audio by at
        // most three create/configure/start attempts, and only after the first one already failed.
        val initCandidates = CodecPolicy.decoderInitCandidates(
            preferredDecoderName, decoderRequest, frameworkChoice, supporting, fallbackEnabled,
            lowLatencyFirst = lowLatencyLadder,
        )
        // Experimental test_call_video_restart: a component that stalled twice in this call gives way to the next
        // decoder that supports the stream (an explicitly requested decoder is never substituted).
        val candidates = if (stallExcludedDecoders.isEmpty()) {
            initCandidates
        } else {
            stallExcludedCandidates(
                initCandidates,
                CodecPolicy.decoderInitCandidates(
                    preferredDecoderName, decoderRequest, frameworkChoice, supporting, fallback = true,
                    lowLatencyFirst = lowLatencyLadder,
                ),
                stallExcludedDecoders,
            )
        }
        val lowLatencyReordered = lowLatencyLadder && decoderRequest == DecoderRequest.NONE &&
            candidates.firstOrNull()?.equals(frameworkChoice, ignoreCase = true) == false
        val requestedLabel = preferredDecoderName ?: "none"
        fun logSelection(effective: String?, attempt: Int) = Log.i(
            "BluetoothVideo",
            "BENCHMARK decoder requested=$requestedLabel effective=${effective ?: "none"} " +
                "reason=${CodecPolicy.decoderSelectionReason(preferredDecoderName, decoderRequest, effective, attempt, lowLatencyReordered)} " +
                "kind=${decoderRequest.label} fallback=${if (fallbackEnabled) "on" else "off"} " +
                "tried=${if (effective == null) candidates.size else attempt + 1} mime=$mime" +
                (if (lowLatencyLadder || realtimePriority || stallRecovery) " switches=${DecoderSwitches.describe()}" else "") +
                if (stallExcludedDecoders.isNotEmpty()) " stallExcluded=${stallExcludedDecoders.joinToString(",")}" else "",
        )
        fun giveUp(error: Throwable): Nothing {
            activeAttempt = -1
            workerThread.quitSafely()
            debugStageTrace?.close()
            throw error
        }
        if (candidates.isEmpty()) {
            logSelection(null, 0)
            giveUp(
                IllegalStateException(
                    if (preferredDecoderName != null && fallbackEnabled) {
                        "Requested $mime decoder $preferredDecoderName cannot decode ${header.width}x${header.height}"
                    } else {
                        "This phone has no $mime decoder for ${header.width}x${header.height}"
                    },
                ),
            )
        }
        candidateNames = candidates
        val opened = try {
            openFirstWorkingDecoder(candidates, format)
        } catch (error: Exception) {
            logSelection(null, candidates.size)
            giveUp(error)
        }
        codec = opened.codec
        decoderName = opened.name
        lowLatencyEnabled = opened.lowLatency
        formatRung = opened.rung
        logSelection(decoderName, opened.index)
        if (stallWatchdogOn) worker.postDelayed(stallCheck, DecoderWatchdogLimits.CHECK_INTERVAL_MS)
    }

    private class OpenedDecoder(
        val codec: MediaCodec,
        val name: String,
        val index: Int,
        val lowLatency: Boolean,
        /** The format rung that configured (`plain`, `low-latency`, `low-latency+vendor(..)`, ...). */
        val rung: String = "legacy",
    )

    private fun videoFormat(width: Int, height: Int, codecSpecificData: List<ByteArray>): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, header.fps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, StreamProtocol.MAX_PAYLOAD_BYTES)
            codecSpecificData.forEachIndexed { index, bytes ->
                setByteBuffer("csd-$index", ByteBuffer.wrap(bytes))
            }
        }

    /**
     * Creates, configures and starts the first of [candidates] that works (bounded by the planner's
     * candidate list), or throws the first failure. Used for the first decoder and for every rebuild.
     */
    private fun openFirstWorkingDecoder(candidates: List<String>, format: MediaFormat): OpenedDecoder {
        var firstFailure: Exception? = null
        for ((index, candidate) in candidates.withIndex()) {
            val baseFormat = MediaFormat(format)
            val capabilities = runCatching {
                codecInfos.first { it.name == candidate }.getCapabilitiesForType(mime)
            }.getOrNull()
            val featureLowLatency = runCatching {
                capabilities!!.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            }.getOrDefault(false)
            val adaptive = runCatching {
                capabilities!!.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)
            }.getOrDefault(false)
            if (followsFormat && adaptive && runCatching {
                    capabilities!!.videoCapabilities.isSizeSupported(maximumWidth, maximumHeight)
                }.getOrDefault(false)
            ) {
                // The largest picture this stream has had (the header names the top rung): vendors size
                // their output buffers from it, as media3's MediaCodecVideoRenderer does for a ladder.
                baseFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, maximumWidth)
                baseFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, maximumHeight)
            }
            // One rung (the former single attempt) unless an Experimental key arm is on; then the first
            // format that configures wins, dropping the newest key first (DecoderLowLatencyLadder).
            var rungs: List<DecoderLowLatencyLadder.Rung>? = null
            var rungIndex = 0
            while (true) {
                val attemptId = ++attemptSequence
                activeAttempt = attemptId
                var attempt: MediaCodec? = null
                try {
                    attempt = (if (attemptId == 1) preallocated?.invoke(candidate) else null)
                        ?: MediaCodec.createByCodecName(candidate)
                    val planned = rungs ?: planRungs(candidate, attempt, featureLowLatency).also { rungs = it }
                    val rung = planned[rungIndex]
                    val candidateFormat = MediaFormat(baseFormat)
                    rung.keys.forEach { (key, value) -> candidateFormat.setInteger(key, value) }
                    attempt.setCallback(createCodecCallback(attemptId), worker)
                    val startedNs = System.nanoTime()
                    val hangWatch = DecoderConfigureWatch.watch(DecoderWatchdogLimits.CONFIGURE_HANG_MS) {
                        Log.w(
                            "BluetoothVideo",
                            "BENCHMARK decoder_configure_hang: decoder=$candidate | rung=${rung.label} | " +
                                "stillRunningAfterMs=${DecoderWatchdogLimits.CONFIGURE_HANG_MS} | mime=$mime",
                        )
                    }
                    try {
                        traceDecoderOperation("video.decode.configure") { attempt.configure(candidateFormat, surface, null, 0) }
                        attempt.setOnFrameRenderedListener(createFrameRenderedListener(presentationGeneration), worker)
                        traceDecoderOperation("video.decode.start") { attempt.start() }
                    } finally {
                        hangWatch.cancel(false)
                    }
                    val configureMs = (System.nanoTime() - startedNs) / 1_000_000L
                    if (DecoderConfigureWatch.isSlow(configureMs)) {
                        Log.w(
                            "BluetoothVideo",
                            "BENCHMARK decoder_configure_slow: decoder=$candidate | configureStartMs=$configureMs | " +
                                "limitMs=${DecoderWatchdogLimits.CONFIGURE_SLOW_MS} | rung=${rung.label} | mime=$mime",
                        )
                    }
                    if (followsFormat) {
                        Log.i(
                            "BluetoothVideo",
                            (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                                "decoder_format: decoder=$candidate | configured=" +
                                "${candidateFormat.getInteger(MediaFormat.KEY_WIDTH)}x${candidateFormat.getInteger(MediaFormat.KEY_HEIGHT)} | " +
                                "header=${header.width}x${header.height} | adaptive=$adaptive | " +
                                "max=${if (adaptive) "${maximumWidth}x$maximumHeight" else "none"} | " +
                                "csd=${format.containsKey("csd-0")} | configureStartMs=$configureMs | formatKeys=${rung.label}",
                        )
                    }
                    val requestedRung = planned.first()
                    if (DecoderLowLatencyLadder.requestsLowLatency(requestedRung.keys)) {
                        // Requested is the first planned format; effective is the one this decoder configured with.
                        // A key the component accepts is still only a request: decoder_hold measures the effect.
                        Log.i(
                            "BluetoothVideo",
                            (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                                "decoder_low_latency: decoder=$candidate | requested=${requestedRung.label} | " +
                                "effective=${rung.label} | featureLowLatency=$featureLowLatency | sdk=${Build.VERSION.SDK_INT}",
                        )
                    }
                    stallDetector.reset(System.nanoTime())
                    return OpenedDecoder(attempt, candidate, index, DecoderLowLatencyLadder.requestsLowLatency(rung.keys), rung.label)
                } catch (error: Exception) {
                    activeAttempt = -1
                    attempt?.let { releaseCodec("failed-attempt", it, stopFirst = false) }
                    if (firstFailure == null) firstFailure = error else firstFailure.addSuppressed(error)
                    val planned = rungs
                    val moreRungs = planned != null && rungIndex + 1 < planned.size
                    Log.w(
                        "BluetoothVideo",
                        "BENCHMARK decoder_init_fallback: failed=$candidate | next=" +
                            (if (moreRungs) "$candidate(${planned!![rungIndex + 1].label})" else candidates.getOrNull(index + 1) ?: "none") +
                            " | mime=$mime | error=${error.javaClass.simpleName}: ${error.message}",
                    )
                    if (!moreRungs) break
                    rungIndex++
                }
            }
        }
        throw checkNotNull(firstFailure)
    }

    /** The configure formats to try for [candidate], read from the codec instance just created (vendor parameters). */
    private fun planRungs(candidate: String, created: MediaCodec, featureLowLatency: Boolean): List<DecoderLowLatencyLadder.Rung> {
        val vendorParameters = if (lowLatencyLadder && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { created.supportedVendorParameters }.getOrNull()
        } else {
            null
        }
        val rungs = DecoderLowLatencyLadder.plan(
            candidate, featureLowLatency, preferLowLatency, lowLatencyLadder, realtimePriority, vendorParameters,
            defaultVendorKey = CodecPolicy.vendorLowLatencyKey(candidate, policyTraits),
        )
        if (lowLatencyLadder || realtimePriority) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK decoder_format_ladder: decoder=$candidate | rungs=${rungs.joinToString(">") { it.label }} | " +
                    "featureLowLatency=$featureLowLatency | vendorParams=${vendorParameters?.size ?: "unread"} | " +
                    "vendorLatencyParams=${DecoderLowLatencyLadder.interestingVendorParameters(vendorParameters).joinToString("+").ifEmpty { "none" }}",
            )
        }
        return rungs
    }

    /**
     * stop()+release() (or release() only) of a decoder. With `decoder-bounded-release` on (default) it runs on a
     * short-lived thread and this worker waits at most 5 s; a hung codec is abandoned and logged.
     */
    private fun releaseCodec(label: String, target: MediaCodec, stopFirst: Boolean) {
        val release: () -> Unit = {
            if (stopFirst) runCatching { target.stop() }
            runCatching { target.release() }
            Unit
        }
        val limitMs = boundedReleaseMs
        if (limitMs == null) {
            release()
            return
        }
        val startedNs = System.nanoTime()
        if (!BoundedCodecRelease.run(label, limitMs, release)) {
            Log.w(
                "BluetoothVideo",
                "BENCHMARK decoder_release_hang: label=$label | waitedMs=${(System.nanoTime() - startedNs) / 1_000_000L} | " +
                    "limitMs=$limitMs | abandoned=true | mime=$mime",
            )
        }
    }

    fun offer(received: StreamProtocol.Packet): Boolean {
        checkFailure()
        if (!running.get()) return false
        // New configuration is never queued as a codec-config buffer of its own: MediaCodec takes a
        // changed configuration only together with a keyframe, so it waits for the keyframe and
        // becomes the csd of the decoder that keyframe needs. Configuration the decoder already has
        // is queued as before.
        val gate = auGate
        val packet = if (gate != null && !received.isAudio) {
            when (val verdict = gate.check(received.payload, received.flags)) {
                is AuVerdict.Accept -> {
                    if (verdict.unusual.isNotEmpty()) {
                        verdict.unusual.forEach { (field, value) ->
                            Log.i("BluetoothVideo", "BENCHMARK au_unusual: codec=$hcLabel | field=$field | value=$value")
                        }
                    }
                    received.copy(payload = verdict.accessUnit)
                }
                is AuVerdict.Reject -> {
                    Log.w(
                        "BluetoothVideo",
                        "BENCHMARK au_reject: codec=$hcLabel | rule=${verdict.rule} | type=${verdict.nalType} | " +
                            "bytes=${verdict.bytes} | keyframe=${verdict.keyFrame} | reason=${verdict.reason}",
                    )
                    // Dropped as corrupt video: later frames wait for the next IDR, and the receiver's existing
                    // needsKeyFrame path asks the sender for one.
                    encodedFrames.markLoss()
                    resetAtRecoveryKeyFrame.set(true)
                    beginRecovery()
                    return false
                }
                is AuVerdict.Close -> {
                    fail(IllegalStateException("Unsupported $hcLabel stream: ${verdict.reason}"))
                    checkFailure()
                    return false
                }
            }
        } else {
            received
        }
        logDecoderHoldIfDue(final = false)
        val plan = if (followsFormat) formatTracker.plan(packet) else null
        if (plan != null && !plan.queue) return true
        val isDiscontinuityKeyFrame =
            packet.flags and StreamProtocol.FLAG_DISCONTINUITY != 0 &&
                packet.flags and StreamProtocol.FLAG_KEY_FRAME != 0
        val rebuild = plan?.rebuild
        // Frames queued before a keyframe that changes the picture belong to the old decoder.
        if (isDiscontinuityKeyFrame || plan?.resetQueue == true) encodedFrames.resetForDiscontinuity()
        if (rebuild != null) {
            maximumWidth = maxOf(maximumWidth, rebuild.width)
            maximumHeight = maxOf(maximumHeight, rebuild.height)
            decoderInputBlocked.set(true)
            pendingRebuild.set(rebuild)
        }
        val accepted = encodedFrames.offer(packet)
        if (!accepted) {
            if (encodedFrames.stats().needsKeyFrame) {
                resetAtRecoveryKeyFrame.set(true)
                decoderInputBlocked.set(true)
                beginRecovery()
            }
            return false
        }
        val isRecoveryKeyFrame = isDiscontinuityKeyFrame || rebuild != null ||
            packet.flags and StreamProtocol.FLAG_KEY_FRAME != 0 &&
            resetAtRecoveryKeyFrame.getAndSet(false)
        if (isRecoveryKeyFrame) {
            decoderInputBlocked.set(true)
            if (isPlannedSizeChange(isDiscontinuityKeyFrame, recovering.get())) {
                // Planned: the decoder is rebuilt behind the last frame, with no recovery cover.
                Log.i(
                    "BluetoothVideo",
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                        "planned_size_change: keepLastFrame=true | coverShown=false | pts=${packet.presentationTimeUs}",
                )
            } else {
                beginRecovery()
                recoveryPresentationTimeUs.set(packet.presentationTimeUs)
            }
        }
        if (rebuild != null && rebuild.sizeChanged) onCodedSizeChanged?.invoke(rebuild.width, rebuild.height)
        if (isRecoveryKeyFrame) {
            if (decoderRestartScheduled.compareAndSet(false, true)) {
                worker.post(::restartAtRecoveryKeyFrame)
            }
        } else {
            worker.post(::drainInput)
        }
        return accepted
    }

    fun stats(): DecoderPipelineStats {
        if (presentationTiming != null) reportPresentationTiming()
        return DecoderPipelineStats(
            queue = encodedFrames.stats(),
            queuedToDecoderFrames = queuedToDecoderFrames.get(),
            submittedForRenderFrames = submittedForRenderFrames.get(),
            renderedFrames = actuallyRenderedFrames.get(),
            decoderWait = decoderWait.snapshot(),
            scheduledRenderDelay = scheduledRenderDelay.snapshot(),
            renderedFrameInterval = renderedFrameInterval.snapshot(),
        )
    }

    fun recordSurfaceUpdate(timestampNs: Long, callbackNowNs: Long) {
        if (!running.get()) return
        presentationTiming?.recordTexture(presentationGeneration, timestampNs, callbackNowNs)
        // Texture timestamp identity is reported independently; it is not assumed to equal source PTS.
        debugStageTrace?.record("texture_notification", timestampNs / 1_000, atNs = callbackNowNs)
    }

    @Synchronized
    private fun reportPresentationTiming(final: Boolean = false) {
        val timing = presentationTiming ?: return
        if (presentationFinalReported) return
        val nowNs = System.nanoTime()
        if (!final && presentationLastReportNs != 0L && nowNs - presentationLastReportNs < 5_000_000_000L) return
        presentationLastReportNs = nowNs
        presentationFinalReported = final
        Log.i(
            "BluetoothVideo",
            "BENCHMARK decoder output pacing: scope=$presentationScopeNs final=$final " +
                "enabled=$releaseAtPlayout generation=$deferredOutputGeneration pending=$deferredOutputCount " +
                "highWater=$deferredOutputHighWater discarded=$deferredOutputDiscarded " +
                "feedAheadMs=$feedAheadMs decodeP99Ms=${decodeLatency.p99Ms()} targetDelayMs=${playbackDelayProfile.delayMs}",
        )
        timing.snapshot().traceLines(presentationScopeNs, ++presentationReport, final, decoderName)
            .forEach { Log.i("BluetoothVideo", it) }
    }

    /**
     * Never call back into MediaCodec from this listener: below API 30 that can deadlock (Media3
     * MediaCodecVideoRenderer.OnFrameRenderedListener, AOSP change 1156807; API 29 is our minSdk).
     */
    private fun createFrameRenderedListener(observedGeneration: Long) =
        MediaCodec.OnFrameRenderedListener { _, presentationTimeUs, renderedAtNs ->
            debugStageTrace?.record("surface_ack", presentationTimeUs, atNs = renderedAtNs)
            presentationTiming?.recordCodec(observedGeneration, presentationTimeUs, renderedAtNs, System.nanoTime())
            val previousRenderedAtNs = lastRenderedAtNs
            if (previousRenderedAtNs > 0 && renderedAtNs > previousRenderedAtNs) {
                renderedFrameInterval.recordNanos(renderedAtNs - previousRenderedAtNs)
                forwardingFrameInterval.recordNanos(renderedAtNs - previousRenderedAtNs)
            }
            lastRenderedAtNs = renderedAtNs
            actuallyRenderedFrames.incrementAndGet()
            stallEscalation.onFrameRendered()
            onFrameRendered?.invoke(presentationTimeUs)
            val recoveryPtsUs = recoveryPresentationTimeUs.get()
            if (recoveryPtsUs != Long.MAX_VALUE && presentationTimeUs >= recoveryPtsUs &&
                recovering.compareAndSet(true, false)
            ) {
                recoveryPresentationTimeUs.set(Long.MAX_VALUE)
                onRecoveryStateChanged(false)
            }
        }

    fun checkFailure() {
        failure.get()?.let { throw IllegalStateException("Decoder failed", it) }
    }

    fun resetForwardingCadence() = forwardingFrameInterval.reset()

    fun resetRenderedCadence() {
        worker.post {
            lastRenderedAtNs = 0L
            renderedFrameInterval.reset()
            forwardingFrameInterval.reset()
        }
    }

    fun takeForwardingCadence(minimumSamples: Int): LatencySnapshot? =
        forwardingFrameInterval.takeSnapshot(minimumSamples)

    private fun beginRecovery() {
        if (recovering.compareAndSet(false, true)) {
            recoveryPresentationTimeUs.set(Long.MAX_VALUE)
            onRecoveryStateChanged(true)
        }
    }

    private inline fun <T> traceDecoderOperation(name: String, operation: () -> T): T {
        if (!BuildConfig.DEBUG) return operation()
        Trace.beginSection(name)
        return try {
            operation()
        } finally {
            Trace.endSection()
        }
    }

    private fun createCodecCallback(attempt: Int): MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (attempt != activeAttempt || !running.get() || decoderInputBlocked.get()) return
            stallDetector.onInputBufferOffered(System.nanoTime())
            availableInputBuffers.addLast(index)
            drainInput()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (attempt != activeAttempt || !running.get() || decoderInputBlocked.get()) return
            stallDetector.onOutput(System.nanoTime())
            outputsTotal++
            try {
                val render = info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                if (render) {
                    if (onFirstDecoded != null && firstDecodedTold.compareAndSet(false, true)) onFirstDecoded.invoke()
                    val nowNs = System.nanoTime()
                    val presentationTimeUs = info.presentationTimeUs
                    debugStageTrace?.record("decoder_output", presentationTimeUs, info.size)
                    recordDecodeLatency(presentationTimeUs, nowNs)
                    val renderTimeNs = if (playoutRamp != null) {
                        playoutRamp.renderTimeNs(presentationTimeUs, playoutClock.renderTimeNs(presentationTimeUs, nowNs), nowNs)
                    } else if (presentFirstFrameImmediately && submittedForRenderFrames.get() == 0) {
                        nowNs
                    } else {
                        playoutClock.renderTimeNs(presentationTimeUs, nowNs)
                    }
                    scheduledRenderDelay.recordNanos(renderTimeNs - nowNs)
                    val observedGeneration = presentationGeneration
                    val waitMs = if (releaseAtPlayout) {
                        decoderFeedDelayMs(renderTimeNs, System.nanoTime(), decodeAheadMs = 0L)
                    } else 0L
                    if (waitMs > 0L) {
                        val outputGeneration = deferredOutputGeneration
                        deferredOutputCount++
                        deferredOutputHighWater = maxOf(deferredOutputHighWater, deferredOutputCount)
                        worker.postDelayed(object : Runnable {
                            override fun run() {
                                if (outputGeneration != deferredOutputGeneration) return
                                if (!running.get() || decoderInputBlocked.get() || failure.get() != null) {
                                    deferredOutputCount--
                                    deferredOutputDiscarded++
                                    return
                                }
                                val remainingMs = decoderFeedDelayMs(renderTimeNs, System.nanoTime(), decodeAheadMs = 0L)
                                if (remainingMs > 0L) {
                                    worker.postDelayed(this, remainingMs)
                                    return
                                }
                                deferredOutputCount--
                                try {
                                    releaseVideoOutput(codec, index, presentationTimeUs, renderTimeNs, observedGeneration)
                                } catch (error: Exception) {
                                    fail(error)
                                }
                            }
                        }, waitMs)
                    } else {
                        releaseVideoOutput(codec, index, presentationTimeUs, renderTimeNs, observedGeneration)
                    }
                } else {
                    traceDecoderOperation("video.decode.release-output") { codec.releaseOutputBuffer(index, false) }
                }
            } catch (error: Exception) {
                fail(error)
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // Evidence for in-band size changes (call resolution ladder): the header gives the maximum.
            if (attempt != activeAttempt) return
            // A transient 0x0 (or otherwise empty) size is ignored and the previous size kept (Chromium crbug 1207682).
            val (width, height) = decodedOutputSize(format) ?: return
            lastOutputSize = width to height
            val configured = formatTracker.codedSize()
            if (width == configured.first && height == configured.second) return
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "decoder_output_size: decoded=${width}x$height | configured=${configured.first}x${configured.second} | " +
                    "header=${header.width}x${header.height} | decoder=$decoderName",
            )
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            if (attempt == activeAttempt) fail(error)
        }
    }

    private fun releaseVideoOutput(
        activeCodec: MediaCodec,
        index: Int,
        presentationTimeUs: Long,
        renderTimeNs: Long,
        observedGeneration: Long,
    ) {
        submittedForRenderFrames.incrementAndGet()
        val releaseCallNs = if (presentationTiming != null) System.nanoTime() else 0L
        traceDecoderOperation("video.decode.release-output") { activeCodec.releaseOutputBuffer(index, renderTimeNs) }
        debugStageTrace?.record("release", presentationTimeUs)
        presentationTiming?.recordRelease(observedGeneration, presentationTimeUs, renderTimeNs, releaseCallNs)
    }

    private fun drainInput() {
        if (!running.get() || failure.get() != null) return
        if (decoderInputBlocked.get()) return
        if (delayedDrainPending) return
        try {
            while (availableInputBuffers.isNotEmpty()) {
                val index = availableInputBuffers.removeFirst()
                val codecConfig = if (pendingCodecSpecificData.isEmpty()) {
                    null
                } else {
                    pendingCodecSpecificData.removeFirst()
                }
                if (codecConfig != null) {
                    val input = checkNotNull(traceDecoderOperation("video.decode.get-input") { codec.getInputBuffer(index) })
                    input.clear()
                    require(codecConfig.size <= input.remaining()) {
                        "Codec configuration is too large for decoder input (${codecConfig.size} bytes)"
                    }
                    input.put(codecConfig)
                    traceDecoderOperation("video.decode.queue-config") {
                        codec.queueInputBuffer(
                            index,
                            0,
                            codecConfig.size,
                            0,
                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                        )
                    }
                    continue
                }
                val nextPacket = encodedFrames.peek()
                if (nextPacket == null) {
                    availableInputBuffers.addFirst(index)
                    return
                }
                val nowNs = System.nanoTime()
                val aheadMs = adaptiveDecoderFeedAheadMs(decodeLatency.p99Ms(), playoutClock.currentDelayMs)
                feedAheadMs = aheadMs
                val feedDelayMs = if (playoutRamp != null) {
                    videoPacketFeedTarget(nextPacket, playoutClock, nowNs)?.let {
                        playoutRamp.feedTargetNs(nextPacket.presentationTimeUs, it.targetTimeNs)
                            ?.let { targetNs -> decoderFeedDelayMs(targetNs, nowNs, aheadMs) }
                    } ?: 0L
                } else if (presentFirstFrameImmediately && queuedToDecoderFrames.get() == 0) {
                    0L
                } else {
                    videoPacketFeedTarget(nextPacket, playoutClock, nowNs)?.let {
                        decoderFeedDelayMs(it.targetTimeNs, nowNs, aheadMs)
                    } ?: 0L
                }
                if (feedDelayMs > 0L) {
                    availableInputBuffers.addFirst(index)
                    delayedDrainPending = true
                    worker.postDelayed(
                        {
                            delayedDrainPending = false
                            drainInput()
                        },
                        feedDelayMs,
                    )
                    return
                }
                val queued = encodedFrames.poll()
                if (queued == null) {
                    availableInputBuffers.addFirst(index)
                    return
                }
                val (packet, waitedNs) = queued
                val input = checkNotNull(traceDecoderOperation("video.decode.get-input") { codec.getInputBuffer(index) })
                input.clear()
                require(packet.payload.size <= input.remaining()) {
                    "Compressed frame is too large for decoder input (${packet.payload.size} bytes)"
                }
                input.put(packet.payload)
                val codecFlags = if (packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0) {
                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                } else {
                    0
                }
                traceDecoderOperation("video.decode.queue-input") {
                    debugStageTrace?.record("decoder_input", packet.presentationTimeUs, packet.payload.size)
                    codec.queueInputBuffer(index, 0, packet.payload.size, packet.presentationTimeUs, codecFlags)
                }
                if (codecFlags == 0) {
                    decoderInputNs[packet.presentationTimeUs] = System.nanoTime()
                    if (decoderInputNs.size > MAX_TRACKED_DECODER_INPUTS) {
                        decoderInputNs.remove(decoderInputNs.keys.first())
                    }
                    stallDetector.onInputQueued(System.nanoTime())
                    inputsQueuedTotal++
                }
                decoderWait.recordNanos(waitedNs)
                if (codecFlags == 0) queuedToDecoderFrames.incrementAndGet()
            }
        } catch (error: Exception) {
            fail(error)
        }
    }

    /** Input->output time of one decoded frame; inputs the decoder skipped are discarded. */
    private fun recordDecodeLatency(presentationTimeUs: Long, outputNs: Long) {
        val inputNs = decoderInputNs.remove(presentationTimeUs) ?: return
        val iterator = decoderInputNs.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().key < presentationTimeUs) iterator.remove()
        }
        decodeLatency.record((outputNs - inputNs) / 1_000_000L)
    }

    private fun restartAtRecoveryKeyFrame() {
        if (!running.get() || failure.get() != null) return
        pendingRebuild.getAndSet(null)?.let { plan ->
            rebuildDecoder(plan)
            return
        }
        // c2.mtk. decoders lose their SurfaceTexture after flush() (Media3 b/362904942), and a decoder that stalled
        // is not trusted with flush(): release and re-create it instead (the rebuild path used for size changes).
        val stallRebuild = forceRebuildOnRecovery.get()
        if (stallRebuild || CodecPolicy.releaseInsteadOfFlush(decoderName, policyTraits)) {
            val mode = if (stallRebuild) "stall_rebuild" else "release_not_flush"
            Log.i("BluetoothVideo", "BENCHMARK decoder_recovery: recovery=$mode | decoder=$decoderName | mime=$mime")
            rebuildDecoder(formatTracker.recoveryRebuild(mode))
            return
        }
        try {
            availableInputBuffers.clear()
            decoderInputNs.clear()
            delayedDrainPending = false
            pendingCodecSpecificData.clear()
            // The newest encoder's configuration: the stream-start one is stale after a rung change.
            pendingCodecSpecificData.addAll(formatTracker.currentCodecSpecificData())
            if (releaseAtPlayout) {
                deferredOutputGeneration++
                deferredOutputDiscarded += deferredOutputCount
                deferredOutputCount = 0
            }
            if (presentationTiming != null) {
                presentationGeneration++
                presentationTiming.beginGeneration(presentationGeneration)
            }
            traceDecoderOperation("video.decode.flush") { codec.flush() }
            worker.post(::resumeAfterRecoveryFlush)
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun resumeAfterRecoveryFlush() {
        if (!running.get() || failure.get() != null) return
        try {
            // A flush can leave already-posted asynchronous callbacks on vendor codec stacks.
            // Let those callbacks drain while input is blocked, then start a clean generation.
            availableInputBuffers.clear()
            if (presentationTiming != null || debugStageTrace != null) {
                codec.setOnFrameRenderedListener(createFrameRenderedListener(presentationGeneration), worker)
            }
            traceDecoderOperation("video.decode.start") { codec.start() }
            // A keyframe that needs another decoder arrived while this one was restarting.
            pendingRebuild.getAndSet(null)?.let { plan ->
                rebuildDecoder(plan)
                return
            }
            decoderRestartScheduled.set(false)
            decoderInputBlocked.set(false)
            Log.i(
                "BluetoothVideo",
                "Decoder recovery restored ${pendingCodecSpecificData.size} codec-config buffer(s) before the keyframe.",
            )
            drainInput()
            rescheduleIfRebuildPending()
        } catch (error: Exception) {
            fail(error)
        }
    }

    /** A rebuild decided while the worker was between its last check and unblocking input must not be lost. */
    private fun rescheduleIfRebuildPending() {
        if (pendingRebuild.get() != null && decoderRestartScheduled.compareAndSet(false, true)) {
            decoderInputBlocked.set(true)
            worker.post(::restartAtRecoveryKeyFrame)
        }
    }

    /**
     * Worker thread: the keyframe at the head of the queue needs a decoder of another picture size or
     * configuration. Releases the running decoder and creates one for it (libwebrtc AndroidVideoDecoder
     * reinitDecode; media3 re-creates a non-adaptive decoder the same way), so a new-size keyframe is
     * never fed to a decoder configured for another size. If the new configuration cannot be opened it is
     * retried without csd (the keyframe carries the format in-band). Input stays blocked until the new
     * decoder runs; the queue holds the keyframe.
     */
    private fun rebuildDecoder(plan: DecoderFormatTracker.Rebuild) {
        if (!running.get() || failure.get() != null) return
        val startedNs = System.nanoTime()
        try {
            availableInputBuffers.clear()
            decoderInputNs.clear()
            delayedDrainPending = false
            pendingCodecSpecificData.clear()
            if (releaseAtPlayout) {
                deferredOutputGeneration++
                deferredOutputDiscarded += deferredOutputCount
                deferredOutputCount = 0
            }
            if (presentationTiming != null) {
                presentationGeneration++
                presentationTiming.beginGeneration(presentationGeneration)
            }
            val previous = codec
            val previousName = decoderName
            activeAttempt = -1 // late callbacks of the decoder being released are ignored
            forceRebuildOnRecovery.set(false)
            releaseCodec("rebuild", previous, stopFirst = true)
            // A component a stall excluded is skipped unless nothing else is left.
            val fullOrder = (listOf(previousName) + candidateNames).distinct()
            val order = fullOrder.filterNot { it in excludedDecoders }.ifEmpty { fullOrder }
            val opened = try {
                openFirstWorkingDecoder(order, videoFormat(plan.width, plan.height, plan.codecSpecificData))
            } catch (error: Exception) {
                Log.w(
                    "BluetoothVideo",
                    "Decoder rebuild for ${plan.width}x${plan.height} failed (${error.javaClass.simpleName}: ${error.message}); " +
                        "retrying without codec configuration (the keyframe carries it in-band)",
                )
                openFirstWorkingDecoder(order, videoFormat(plan.width, plan.height, emptyList()))
            }
            codec = opened.codec
            decoderName = opened.name
            lowLatencyEnabled = opened.lowLatency
            formatRung = opened.rung
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "decoder_rebuild: reason=${plan.reason} | to=${plan.width}x${plan.height} | " +
                    "csd=${plan.codecSpecificData.size} | decoder=${opened.name} (was $previousName) | " +
                    "ms=${(System.nanoTime() - startedNs) / 1_000_000L}",
            )
            pendingRebuild.getAndSet(null)?.let { again ->
                rebuildDecoder(again)
                return
            }
            decoderRestartScheduled.set(false)
            decoderInputBlocked.set(false)
            drainInput()
            rescheduleIfRebuildPending()
        } catch (error: Exception) {
            fail(error)
        }
    }

    /**
     * Worker tick: a decoder that was queued input and produced no output callback (or offered no input buffer
     * for 5 s) is logged as `decoder_stall`; the recovery (default on) then re-creates it. The signal is the
     * decoder's own callbacks, so a hidden surface or a paused feed does not fire it. Decoder callback timing is
     * a proxy for the decoder, never a screen or glass-to-glass measurement.
     */
    private fun checkForStall() {
        val nowNs = System.nanoTime()
        logBehaviourIfDue(nowNs)
        val suspended = decoderInputBlocked.get() || pendingRebuild.get() != null || decoderRestartScheduled.get() ||
            deferredOutputCount > 0
        val stall = stallDetector.check(
            nowNs,
            suspended = suspended,
            framesWaiting = encodedFrames.peek() != null,
            inputBufferHeld = availableInputBuffers.isNotEmpty(),
        ) ?: return
        stallDetector.reset(nowNs)
        val otherCandidate = candidateNames.any { !it.equals(decoderName, ignoreCase = true) && it !in excludedDecoders }
        // Experimental test_call_video_restart: the call replaces the decoder, so the in-place arm stands aside.
        val action = stallEscalation.next(stallRecovery && !stallRestartsCaller, otherCandidate)
        if (stallLogLines < DecoderWatchdogLimits.MAX_STALL_LOG_LINES) {
            stallLogLines++
            Log.w(
                "BluetoothVideo",
                "BENCHMARK decoder_stall: kind=${stall.kind.label} | decoder=$decoderName | unanswered=${stall.unanswered} | " +
                    "ageMs=${stall.ageMs} | inputs=$inputsQueuedTotal | outputs=$outputsTotal | " +
                    "rendered=${actuallyRenderedFrames.get()} | stalls=${stallEscalation.stallCount} | " +
                    "action=${if (stallRestartsCaller) "call_restart" else action.name.lowercase()} | " +
                    "recovery=${if (stallRecovery) "on" else "off"} | mime=$mime",
            )
        }
        when (action) {
            DecoderStallEscalation.Action.LOG_ONLY, DecoderStallEscalation.Action.GIVE_UP -> Unit
            DecoderStallEscalation.Action.REBUILD_SAME -> startStallRecovery(excludeComponent = false)
            DecoderStallEscalation.Action.EXCLUDE_AND_REBUILD -> startStallRecovery(excludeComponent = true)
        }
        if (stallRestartsCaller) {
            // Nothing more is fed to this codec; the receiver (waiting for room or offering the next frame) gets the stall.
            decoderInputBlocked.set(true)
            encodedFrames.reportStall(
                DecoderStalledException("Video decoder $decoderName stalled (${stall.kind.label}, ${stall.ageMs} ms)"),
            )
        }
    }

    /**
     * Drops the queue, waits for a keyframe (the receiver's needsKeyFrame poll asks the sender for one) and has the
     * recovery keyframe re-create the decoder; [excludeComponent] also removes this component from the session.
     */
    private fun startStallRecovery(excludeComponent: Boolean) {
        if (excludeComponent) excludedDecoders += decoderName
        forceRebuildOnRecovery.set(true)
        decoderInputBlocked.set(true)
        resetAtRecoveryKeyFrame.set(true)
        encodedFrames.resetForDiscontinuity()
        beginRecovery()
    }

    /** Experimental key arms only: how the running format behaved (decoder callbacks, not physical latency). */
    private fun logBehaviourIfDue(nowNs: Long) {
        if (!lowLatencyLadder && !realtimePriority) return
        if (behaviourLogNs != 0L && nowNs - behaviourLogNs < DECODER_BEHAVIOUR_LOG_NS) return
        behaviourLogNs = nowNs
        Log.i(
            "BluetoothVideo",
            "BENCHMARK decoder_behaviour: decoder=$decoderName | formatKeys=$formatRung | inputs=$inputsQueuedTotal | " +
                "outputs=$outputsTotal | submitted=${submittedForRenderFrames.get()} | rendered=${actuallyRenderedFrames.get()} | " +
                "decodeP99Ms=${decodeLatency.p99Ms() ?: -1} | basis=decoder-callbacks",
        )
    }

    private fun fail(error: Throwable) {
        if (failure.compareAndSet(null, error)) {
            Log.e("BluetoothVideo", "Video decoder $decoderName failed", error)
        }
    }

    /** Design HC: `BENCHMARK decoder_hold` (input-to-output time per frame) every 10 s and at close, codecs 4 and 5 only. */
    private fun logDecoderHoldIfDue(final: Boolean) {
        if (auGate == null) return
        val nowNs = System.nanoTime()
        if (hcHoldLogNs == 0L) hcHoldLogNs = nowNs
        if (!final && nowNs - hcHoldLogNs < DECODER_HOLD_LOG_INTERVAL_NS) return
        hcHoldLogNs = nowNs
        worker.post {
            val p50 = decodeLatency.percentileMs(50) ?: return@post
            val p95 = decodeLatency.percentileMs(95) ?: return@post
            Log.i(
                "BluetoothVideo",
                "BENCHMARK decoder_hold: codec=$hcLabel | p50_ms=$p50 | p95_ms=$p95 | decoder=$decoderName | " +
                    "lowLatency=$lowLatencyEnabled | formatKeys=$formatRung | final=$final",
            )
        }
    }

    override fun close() = close(waitForRelease = true)

    /**
     * Experimental `test_call_video_restart`: [close] without waiting for the codec release, for a decoder that stalled
     * (its release can hang). The worker releases it with the bounded release while the caller's receive thread, which
     * also carries the call's audio, goes on at once.
     */
    fun closeWithoutWaiting() = close(waitForRelease = false)

    private fun close(waitForRelease: Boolean) {
        if (running.get()) logDecoderHoldIfDue(final = true)
        if (!running.getAndSet(false)) return
        if (recovering.getAndSet(false)) onRecoveryStateChanged(false)
        encodedFrames.clear()
        worker.removeCallbacksAndMessages(null)
        val release = {
            if (releaseAtPlayout) {
                deferredOutputGeneration++
                deferredOutputDiscarded += deferredOutputCount
                deferredOutputCount = 0
            }
            releaseCodec("close", codec, stopFirst = true)
            if (presentationTiming != null) reportPresentationTiming(final = true)
        }
        if (Looper.myLooper() == workerThread.looper) {
            release()
        } else {
            val released = CountDownLatch(1)
            worker.post {
                release()
                released.countDown()
            }
            if (waitForRelease) runCatching { released.await(2, TimeUnit.SECONDS) }
        }
        workerThread.quitSafely()
        debugStageTrace?.close()
    }
}

private const val DECODER_HOLD_LOG_INTERVAL_NS = 10_000_000_000L

/**
 * Design HC: the csd of a decoder that starts at [firstPacket], read from its in-band parameter sets after the whole
 * keyframe passes the structural gate; null when it is not such a keyframe.
 */
internal fun hcFirstKeyFrameCsd(codec: AnnexBCodec, header: StreamProtocol.Header, firstPacket: StreamProtocol.Packet?): List<ByteArray>? {
    if (firstPacket == null || firstPacket.isAudio || firstPacket.flags and StreamProtocol.FLAG_KEY_FRAME == 0) return null
    return AccessUnitGate.keyFrameSets(codec, firstPacket.payload, header.width, header.height)?.csd()
}

internal fun videoPacketPlayoutTiming(
    packet: StreamProtocol.Packet,
    playoutClock: MediaPlayoutClock,
    nowNs: Long,
    /** Experimental speech-led call playout only (see [MediaPlayoutClock.timing]); null: unchanged. */
    growthLimitMs: Long? = null,
): MediaPlayoutTiming? = if (packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0) {
    null
} else {
    playoutClock.timing(packet.presentationTimeUs, nowNs, growthLimitMs)
}

/** Post-arrival (decoder feed) lookup: never moves the clock; see [MediaPlayoutClock.targetFor]. */
internal fun videoPacketFeedTarget(
    packet: StreamProtocol.Packet,
    playoutClock: MediaPlayoutClock,
    nowNs: Long,
): MediaPlayoutTiming? = if (packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0) {
    null
} else {
    playoutClock.targetFor(packet.presentationTimeUs, nowNs)
}

/**
 * How far ahead of its render time a frame is fed: the measured decoder input->output p99 plus a
 * margin, never below [DECODER_FEED_AHEAD_MS] and never past the current playout delay minus
 * [DECODER_FEED_AHEAD_SAFETY_MS]. A decoder with periodic stalls (S24 c2.exynos.av1.decoder,
 * 163-179 ms every 61 frames) then gets slack instead of late output. The margin stays below one
 * frame interval (33 ms at 30 FPS), so output back-pressure from held frames, which makes measured
 * latency trail the lead by about a frame per held buffer, converges instead of ratcheting.
 */
internal fun adaptiveDecoderFeedAheadMs(decodeP99Ms: Long?, playoutDelayMs: Long): Long {
    val wantedMs = (decodeP99Ms ?: 0L) + DECODER_FEED_AHEAD_MARGIN_MS
    val ceilingMs = playoutDelayMs - DECODER_FEED_AHEAD_SAFETY_MS
    return maxOf(DECODER_FEED_AHEAD_MS, minOf(wantedMs, ceilingMs))
}

/** Rolling decoder input->output latency (last [capacity] frames). Not thread-safe. */
internal class DecoderLatencyWindow(private val capacity: Int = 128) {
    private val samples = LongArray(capacity)
    private var count = 0
    private var next = 0
    private var cachedP99: Long? = null
    private var dirty = false

    fun record(latencyMs: Long) {
        samples[next] = latencyMs.coerceAtLeast(0L)
        next = (next + 1) % capacity
        if (count < capacity) count++
        dirty = true
    }

    /** Nearest-rank [percent] of the window (any sample count), or null before the first sample (Design HC decoder_hold). */
    fun percentileMs(percent: Int): Long? {
        if (count == 0) return null
        val sorted = samples.copyOf(count).also { it.sort() }
        return sorted[((count * percent + 99) / 100 - 1).coerceIn(0, count - 1)]
    }

    /** p99 once [MIN_DECODE_LATENCY_SAMPLES] frames were measured, else null (fixed lead). */
    fun p99Ms(): Long? {
        if (count < MIN_DECODE_LATENCY_SAMPLES) return null
        if (dirty) {
            val sorted = samples.copyOf(count).also { it.sort() }
            cachedP99 = sorted[(count * 99 + 99) / 100 - 1] // nearest rank
            dirty = false
        }
        return cachedP99
    }
}

internal fun decoderFeedDelayMs(
    targetTimeNs: Long,
    nowNs: Long,
    decodeAheadMs: Long = DECODER_FEED_AHEAD_MS,
): Long {
    val waitNs = targetTimeNs - nowNs - decodeAheadMs * 1_000_000L
    return if (waitNs <= 0L) 0L else (waitNs + 999_999L) / 1_000_000L
}

internal fun plannedDecoderQueueDepth(fps: Int, playbackDelayMs: Long): Int =
    (kotlin.math.ceil(
        fps * (playbackDelayMs - DECODER_FEED_AHEAD_MS).coerceAtLeast(0L) / 1_000.0,
    ).toInt() + PlaybackDelayProfile.VIDEO_QUEUE_HEADROOM_FRAMES)

internal const val DECODER_FEED_AHEAD_MS = 80L
internal const val DECODER_FEED_AHEAD_MARGIN_MS = 20L
internal const val DECODER_FEED_AHEAD_SAFETY_MS = 50L
private const val MIN_DECODE_LATENCY_SAMPLES = 8
private const val MAX_TRACKED_DECODER_INPUTS = 64
private const val DECODER_BEHAVIOUR_LOG_NS = 10_000_000_000L

/**
 * The decoded picture size of a decoder output format (crop rectangle when present), or null. Reads the
 * crop-left/right/top/bottom integers, never the `crop` Rect (MediaTek returns a default Rect), and ignores a
 * transient empty size ([validOutputSize]).
 */
internal fun decodedOutputSize(format: MediaFormat): Pair<Int, Int>? = runCatching {
    if (format.containsKey("crop-right") && format.containsKey("crop-left") &&
        format.containsKey("crop-bottom") && format.containsKey("crop-top")
    ) {
        validOutputSize(
            format.getInteger("crop-right") - format.getInteger("crop-left") + 1,
            format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1,
        )
    } else {
        validOutputSize(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT))
    }
}.getOrNull()

/**
 * Decoders sometimes announce a 0x0 output format, some output-available signals, and only then the real size
 * (Chromium codec_wrapper.cc, crbug 1207682); such a size is not a picture size and the previous one stands.
 */
internal fun validOutputSize(width: Int, height: Int): Pair<Int, Int>? =
    if (width > 0 && height > 0) width to height else null
