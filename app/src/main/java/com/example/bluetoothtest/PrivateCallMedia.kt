package com.example.bluetoothtest

import android.media.MediaCodec
import android.content.Context
import android.util.Log
import android.view.Surface
import java.io.Closeable

/** In-place call decoder recovery is a codec-component policy entry (DevicePolicyRegistry). */
internal fun canRecoverPrivateCallVideoDecoder(decoderName: String, recoveryAttempts: Int): Boolean =
    DeviceFamilyPolicy.componentEffects<DecoderInPlaceRecovery>(decoderName)
        .maxOfOrNull { it.maxAttempts }
        ?.let { recoveryAttempts in 0 until it } == true

/**
 * Call decoder recovery for one failure: the component rule above, or any decoder whose
 * CodecException reports isRecoverable() (codec-exception-recoverable-reinit, Media3).
 */
internal fun canRecoverPrivateCallVideoDecoderFailure(
    decoderName: String,
    recoveryAttempts: Int,
    recoverable: Boolean,
    traits: DeviceTraits? = null,
): Boolean = canRecoverPrivateCallVideoDecoder(decoderName, recoveryAttempts) ||
    CodecPolicy.recoverableReinitAllowed(recoverable, recoveryAttempts, traits)

internal fun privateCallCameraVideoSettings(
    requested: VideoSettings,
    supportedModes: List<DeviceVideoMode>,
): VideoSettings {
    val candidates = supportedModes.filter { mode -> mode.fpsOptions.any { it <= requested.fps } }
    require(candidates.isNotEmpty()) { "No compatible call camera/encoder mode at ${requested.fps} FPS" }
    val exact = candidates.firstOrNull { mode ->
        mode.resolution.width == requested.resolution.width &&
            mode.resolution.height == requested.resolution.height
    }
    if (exact != null && requested.fps in exact.fpsOptions) return requested
    val requestedPixels = requested.resolution.width.toLong() * requested.resolution.height
    val mode = exact ?: candidates
        .filter { it.resolution.width.toLong() * it.resolution.height <= requestedPixels }
        .maxByOrNull { it.resolution.width.toLong() * it.resolution.height }
        ?: candidates.minBy { it.resolution.width.toLong() * it.resolution.height }
    return requested.copy(
        resolution = mode.resolution,
        fps = mode.fpsOptions.filter { it <= requested.fps }.max(),
    )
}

internal fun privateCallVideoSettings(
    selected: VideoSettings,
    transport: BluetoothTransportOption,
    fingerprint: BluetoothStackFingerprint? = null,
    preserveSelectedBitrate: Boolean = false,
    ignoreFamilyRateCap: Boolean = false,
): VideoSettings {
    val perDirectionKbps = when (transport) {
        BluetoothTransportOption.CLASSIC_RFCOMM -> 250
        BluetoothTransportOption.BLE_L2CAP -> 200
        BluetoothTransportOption.BLE_GATT -> 180
    }
    val transportLimited = selected.copy(
        bitRate = if (preserveSelectedBitrate) selected.bitRate else minOf(selected.bitRate, perDirectionKbps * 1_000),
    )
    // Device-family call caps (DevicePolicyRegistry): the smallest geometry, FPS and rate win.
    val caps = fingerprint?.let { DeviceFamilyPolicy.resolve(it).callVideoCaps(transport) }.orEmpty()
    if (caps.isEmpty()) return transportLimited
    val maxResolution = caps.mapNotNull(CallVideoCap::maxResolution)
        .minByOrNull { it.width.toLong() * it.height }
    val maxFps = caps.mapNotNull(CallVideoCap::maxFps).minOrNull()
    // [A5 Am 16] the exact-rate DEBUG arm treats the family rate cap as absent; geometry and FPS caps still apply.
    val maxKbps = if (ignoreFamilyRateCap) null else caps.mapNotNull(CallVideoCap::maxVideoKbps).minOrNull()
    return transportLimited.copy(
        resolution = if (
            maxResolution != null &&
            transportLimited.resolution.width * transportLimited.resolution.height >
            maxResolution.width * maxResolution.height
        ) {
            maxResolution
        } else {
            transportLimited.resolution
        },
        fps = maxFps?.let { minOf(transportLimited.fps, it) } ?: transportLimited.fps,
        bitRate = if (preserveSelectedBitrate || maxKbps == null) {
            transportLimited.bitRate
        } else {
            minOf(transportLimited.bitRate, maxKbps * 1_000)
        },
    )
}

/**
 * DEBUG `test_preserve_call_bitrate` keeps an exact-rate call at the selected bitrate instead of the
 * per-direction starting budget, to challenge that budget. GATT had it first; Classic (250 kbps
 * budget, EDR measured 1.15-1.19 Mbps payload) got it 2026-10-09. CoC keeps its legacy protections.
 */
internal fun debugPreserveCallBitrateApplies(transport: BluetoothTransportOption): Boolean =
    transport == BluetoothTransportOption.BLE_GATT || transport == BluetoothTransportOption.CLASSIC_RFCOMM

internal fun privateCallCapabilities(
    transport: BluetoothTransportOption,
    fingerprint: BluetoothStackFingerprint,
): NodeCapabilities = LocalNodeCapabilities.forTransport(transport).let { capabilities ->
    capabilities.copy(
        decodeCodecMask = privateCallDecodeCodecMask(
            fingerprint,
            transport,
            capabilities.decodeCodecMask,
        ),
        maxBitrateKbps = privateCallCapabilityMaxBitrateKbps(
            transport,
            fingerprint,
            capabilities.maxBitrateKbps,
        ),
    )
}

/** The legacy Qualcomm VP9-only call decode rule applies on this route ([privateCallDecodeCodecMask]). */
internal fun privateCallDecodeRestricted(
    fingerprint: BluetoothStackFingerprint,
    transport: BluetoothTransportOption,
): Boolean = DeviceFamilyPolicy.resolve(fingerprint).callVp9Compatibility(transport)

internal fun privateCallDecodeCodecMask(
    fingerprint: BluetoothStackFingerprint,
    transport: BluetoothTransportOption,
    defaultDecodeCodecMask: Int,
): Int {
    val vp9 = defaultDecodeCodecMask and NodeCapabilities.CODEC_VP9
    val needsVp9Compatibility = privateCallDecodeRestricted(fingerprint, transport)
    return if (needsVp9Compatibility && vp9 != 0) {
        vp9
    } else {
        defaultDecodeCodecMask
    }
}

/** The device-family advertised maximum when an entry sets one (smallest wins), else the default. */
internal fun privateCallCapabilityMaxBitrateKbps(
    transport: BluetoothTransportOption,
    fingerprint: BluetoothStackFingerprint,
    defaultMaxBitrateKbps: Int,
): Int = DeviceFamilyPolicy.resolve(fingerprint).callAdvertisedMaxBitrateKbps(transport) ?: defaultMaxBitrateKbps

/** A peer that advertises a constrained call maximum on this transport (capability negotiation). */
private fun peerCallLinkConstrained(transport: BluetoothTransportOption, peerMaxBitrateKbps: Int?): Boolean =
    peerMaxBitrateKbps != null && when (transport) {
        BluetoothTransportOption.BLE_L2CAP -> peerMaxBitrateKbps in 1..CONSTRAINED_COC_BITRATE_KBPS
        BluetoothTransportOption.CLASSIC_RFCOMM -> peerMaxBitrateKbps in 1..CONSTRAINED_RFCOMM_BITRATE_KBPS
        BluetoothTransportOption.BLE_GATT -> false
    }

internal fun privateCallAudioBitRate(
    transport: BluetoothTransportOption,
    fingerprint: BluetoothStackFingerprint,
    peerMaxBitrateKbps: Int? = null,
): Int = listOfNotNull(
    DeviceFamilyPolicy.resolve(fingerprint).callAudioBitRate(transport),
    CONSTRAINED_PEER_CALL_AUDIO_BIT_RATE.takeIf { peerCallLinkConstrained(transport, peerMaxBitrateKbps) },
).minOrNull() ?: StreamProtocol.AUDIO_BIT_RATE

/**
 * Relayed call video. The legacy cap (15 FPS, 110 kbps) predates any relay measurement: it is in the
 * first local commit and DECISIONS records it as a starting budget, not a ceiling (CoC S24 -> M31 ->
 * S22). With [measuredBudgetBps] (experimental, DebugTunables.relayedCallMeasuredCap) the bitrate
 * cap follows [relayedCallMeasuredBudgetBps] instead, never below the legacy cap. FPS stays at 15:
 * every relayed measurement so far ran at 15 FPS, and FPS is not what this cap questions.
 */
internal fun privateCallVideoSettingsForRoute(
    directSettings: VideoSettings,
    relayHops: Int,
    measuredBudgetBps: Int? = null,
): VideoSettings = if (relayHops == 0) {
    directSettings
} else {
    directSettings.copy(
        fps = minOf(directSettings.fps, RELAYED_CALL_MAX_FPS),
        bitRate = minOf(
            directSettings.bitRate,
            maxOf(RELAYED_CALL_LEGACY_BITRATE_BPS, measuredBudgetBps ?: RELAYED_CALL_LEGACY_BITRATE_BPS),
        ),
    )
}

internal const val RELAYED_CALL_MAX_FPS = 15
internal const val RELAYED_CALL_LEGACY_BITRATE_BPS = 110_000

/**
 * A relay's single radio carries both of its adjacent links, each in both call directions: four
 * copies of one direction's rate share its airtime, where a direct call has two
 * ([CallStartRate.MEASURED_LINK_SHARE], half less 15% headroom). Measured basis: a one-way stream
 * relay carried 433-458 kbps in and out (DECISIONS CoC/GATT/Classic CBR 375-400), about half of
 * the 0.88-1.0 Mbps those phones measure, so one relayed flow fits about a quarter of the link.
 */
internal const val RELAYED_CALL_LINK_SHARE = CallStartRate.MEASURED_LINK_SHARE / 2

/** Video budget of one relayed call direction from this phone's fresh measured link, or null. */
internal fun relayedCallMeasuredBudgetBps(measuredLinkKbps: Int?, audioBps: Int): Int? =
    measuredLinkKbps?.takeIf { it > 0 }?.let { (it * 1_000L * RELAYED_CALL_LINK_SHARE).toInt() - audioBps }

internal fun privateCallVideoSettingsForPeer(
    settings: VideoSettings,
    transport: BluetoothTransportOption,
    peerMaxBitrateKbps: Int?,
): VideoSettings {
    val constrainedMaximumFps = when {
        peerMaxBitrateKbps == null -> return settings
        transport == BluetoothTransportOption.BLE_L2CAP &&
            peerMaxBitrateKbps in 1..CONSTRAINED_COC_BITRATE_KBPS ->
            if (peerMaxBitrateKbps <= MSMNILE_COC_BITRATE_KBPS) 15 else 20
        transport == BluetoothTransportOption.CLASSIC_RFCOMM &&
            peerMaxBitrateKbps in 1..CONSTRAINED_RFCOMM_BITRATE_KBPS ->
            if (peerMaxBitrateKbps <= MSMNILE_COC_BITRATE_KBPS) 15 else 30
        else -> return settings
    }
    return settings.copy(
        resolution = if (
            transport == BluetoothTransportOption.CLASSIC_RFCOMM &&
            peerMaxBitrateKbps > MSMNILE_COC_BITRATE_KBPS
        ) {
            settings.resolution
        } else if (settings.resolution.width * settings.resolution.height > 426 * 240) {
            VideoResolution(426, 240, "240p")
        } else {
            settings.resolution
        },
        fps = minOf(settings.fps, constrainedMaximumFps),
        bitRate = minOf(settings.bitRate, peerMaxBitrateKbps * 1_000),
    )
}

/** One call audio record from 1 (as is), 2 (pair) or 3-20 (batch) consecutive Opus access units. */
internal fun combineCallAudio(packets: List<StreamProtocol.Packet>): StreamProtocol.Packet = when (packets.size) {
    0 -> throw IllegalArgumentException("No call audio to combine")
    1 -> packets[0]
    2 -> StreamProtocol.pairAudioPackets(packets[0], packets[1])
    else -> StreamProtocol.batchAudioPackets(packets)
}

/**
 * Call audio coalescing (`test_call_audio_coalesce`, default on; DEBUG `=false` is the control). When the call writer takes one
 * ordinary Opus access unit while more ordinary units already wait in its queue (the link is behind, typically
 * audio that queued during a blocking video write), it sends them as one existing `BTA1` pair or `BTA2` batch
 * record instead of one record each. Nothing waits for audio that has not been captured yet, so the added
 * delay is zero; with an empty queue the record is exactly the single unit, as before.
 *
 * Every unit added must be an ordinary unit (flags exactly `AUDIO`: never codec configuration or an
 * already-combined record), within the format's unit-size limit and within the format's 1 s timestamp span of
 * the first unit, at most [StreamProtocol.MAX_AUDIO_BATCH_PACKETS] units. [takeNextIf] removes and returns the
 * oldest queued audio record only when the predicate accepts it, else null (the record stays queued).
 */
internal fun <T> coalesceQueuedCallAudio(
    first: T,
    flagsOf: (T) -> Int,
    timeUsOf: (T) -> Long,
    sizeOf: (T) -> Int,
    takeNextIf: ((T) -> Boolean) -> T?,
    maxUnits: Int = StreamProtocol.MAX_AUDIO_BATCH_PACKETS,
): List<T> {
    if (!callAudioCoalescible(flagsOf(first), sizeOf(first), 0L) || maxUnits < 2) return listOf(first)
    val firstTimeUs = timeUsOf(first)
    val units = arrayListOf(first)
    while (units.size < minOf(maxUnits, StreamProtocol.MAX_AUDIO_BATCH_PACKETS)) {
        val next = takeNextIf { candidate ->
            callAudioCoalescible(flagsOf(candidate), sizeOf(candidate), timeUsOf(candidate) - firstTimeUs)
        } ?: break
        units += next
    }
    return units
}

private fun callAudioCoalescible(flags: Int, size: Int, deltaUs: Long): Boolean =
    flags == StreamProtocol.FLAG_AUDIO &&
        size <= StreamProtocol.MAX_AUDIO_ACCESS_UNIT_BYTES &&
        deltaUs in 0..StreamProtocol.MAX_AUDIO_PAIR_GAP_US

/** Removes and returns the oldest queued audio record if [accept] takes it; otherwise leaves the queue unchanged. */
internal fun <T> takePrivateCallAudioIf(
    queue: java.util.concurrent.ArrayBlockingQueue<T>,
    isAudio: (T) -> Boolean,
    accept: (T) -> Boolean,
): T? {
    val waitingAudio = queue.firstOrNull(isAudio) ?: return null
    if (!accept(waitingAudio)) return null
    return if (queue.remove(waitingAudio)) waitingAudio else null
}

/**
 * Experimental stream audio batches (`test_stream_audio_batch_frames`): units per stream audio record. 2 (default,
 * and any value outside 3..10) keeps the 40 ms `BTA1` pairs every stream sends today; 3..10 sends 60-200 ms `BTA2`
 * batches, which every stream receiver and relay already accepts (audio-only live streams send 200 ms batches).
 * The newest unit of a batch waits up to (frames - 1) x 20 ms longer before it is sent; a stream viewer already
 * plays several hundred milliseconds behind the source.
 */
internal fun streamAudioBatchFrames(requested: Int): Int = if (requested in 3..10) requested else 2

/** A pending stream audio batch can take [next]: same 1 s timestamp span as the format, never backwards. */
internal fun streamAudioBatchAccepts(pending: List<StreamProtocol.Packet>, next: StreamProtocol.Packet): Boolean {
    val first = pending.firstOrNull() ?: return true
    val last = pending.last()
    return next.presentationTimeUs >= last.presentationTimeUs &&
        next.presentationTimeUs - first.presentationTimeUs <= StreamProtocol.MAX_AUDIO_PAIR_GAP_US &&
        next.payload.size <= StreamProtocol.MAX_AUDIO_ACCESS_UNIT_BYTES
}

internal fun pairPrivateCallAudioForRoute(
    transport: BluetoothTransportOption,
    relayHops: Int,
    fingerprint: BluetoothStackFingerprint? = null,
    peerMaxBitrateKbps: Int? = null,
): Boolean =
    transport == BluetoothTransportOption.BLE_L2CAP && relayHops > 0 ||
        fingerprint != null && DeviceFamilyPolicy.resolve(fingerprint).callAudioPairing(transport) ||
        peerCallLinkConstrained(transport, peerMaxBitrateKbps)

internal fun privateCallAudioBatchSizeForRoute(
    transport: BluetoothTransportOption,
    relayHops: Int,
    fingerprint: BluetoothStackFingerprint? = null,
    peerMaxBitrateKbps: Int? = null,
    /** Experimental legacy low delay (see [privateCallLegacyLowDelay]); false keeps the 20-packet batch. */
    legacyLowDelay: Boolean = false,
    /**
     * Experimental call audio pairs (`test_call_audio_pairs`, off by default): a route that would send single
     * 20 ms units sends 40 ms `BTA1` pairs instead (every peer already accepts them; legacy routes send them today).
     * Routes that already pair or batch are unchanged.
     */
    alwaysPair: Boolean = false,
): Int = when {
    transport == BluetoothTransportOption.BLE_L2CAP &&
        !privateCallLegacyLowDelay(legacyLowDelay, relayHops) &&
        pairPrivateCallAudioForRoute(transport, relayHops, fingerprint, peerMaxBitrateKbps) -> 20
    pairPrivateCallAudioForRoute(transport, relayHops, fingerprint, peerMaxBitrateKbps) -> 2
    alwaysPair -> 2
    else -> 1
}

/**
 * Experimental legacy low delay (default off; DEBUG `test_call_legacy_low_delay`), direct calls
 * only. On a legacy-constrained call route (a legacy Qualcomm phone, or a peer that declares a
 * constrained CoC/Classic receive limit) audio is sent in 2-packet (40 ms) records instead of
 * 20-packet (400 ms) batches, and incoming media plays with the normal call delay (250-600 ms)
 * instead of the constrained one (500-800 ms).
 *
 * Measured cost of the protection (calls-noloss-m31asus-51cc8028, CoC, both directions and voice
 * calls): audio batch fill p50 380 ms, playout 500 ms growing to 800 ms, so about 0.9-1.2 s one way
 * before radio time, against ITU-T G.114's 400 ms planning limit. The 20-packet batch has no
 * measured reason of its own: the record-rate overload that justified batching was on a relayed
 * CoC route, and 2-packet pairs fixed it there (25 audio records/s, 641/647 frames at 15 FPS,
 * decoder 0; DECISIONS "Legacy-family protections"). The same ASUS carries 50 audio records/s
 * plus video in streams. Relayed calls keep the batch.
 */
internal fun privateCallLegacyLowDelay(enabled: Boolean, relayHops: Int): Boolean = enabled && relayHops == 0

internal fun privateCallVideoQueueCapacity(
    fps: Int,
    playbackDelayProfile: PlaybackDelayProfile,
    transport: BluetoothTransportOption,
    fingerprint: BluetoothStackFingerprint? = null,
): Int = playbackDelayProfile.videoQueueCapacity(fps) + maxOf(
    if (transport == BluetoothTransportOption.BLE_L2CAP) 8 else 0,
    fingerprint?.let { DeviceFamilyPolicy.resolve(it).callExtraVideoQueueFrames(transport) } ?: 0,
)

internal fun privateCallMediaFlushIntervalMs(
    transport: BluetoothTransportOption,
    fingerprint: BluetoothStackFingerprint,
    peerMaxBitrateKbps: Int?,
    /** A Wi-Fi carrier flushes after every record (a socket write does not block for its air time). */
    carrier: CarrierKind = CarrierKind.BLUETOOTH,
): Long = if (carrier.isWifi) {
    0L
} else {
    maxOf(
        DeviceFamilyPolicy.resolve(fingerprint).callMediaFlushIntervalMs(transport) ?: 0L,
        if (peerCallLinkConstrained(transport, peerMaxBitrateKbps)) 20L else 0L,
    )
}

internal fun privateCallPlaybackDelayProfile(
    selected: PlaybackDelayProfile,
    transport: BluetoothTransportOption,
    fingerprint: BluetoothStackFingerprint,
    peerMaxBitrateKbps: Int?,
    /** Experimental legacy low delay for this (direct) call; see [privateCallLegacyLowDelay]. */
    legacyLowDelay: Boolean = false,
): PlaybackDelayProfile = selected.forCall(
    constrainedLink = !legacyLowDelay && (
        DeviceFamilyPolicy.resolve(fingerprint).callConstrainedPlayback(transport) ||
            peerCallLinkConstrained(transport, peerMaxBitrateKbps)
        ),
)

/**
 * Decode mask this phone advertises in its END-TO-END call hello (the link hello keeps
 * [privateCallDecodeCodecMask]). A legacy Qualcomm phone restricted to VP9 advertises its real
 * decoders plus [NodeCapabilities.DECODE_PREFERS_VP9]; senders honour the preference whenever they
 * can encode VP9 ([privateCallCodecForPeer]; with Design HC on, a hardware HEVC encoder this phone
 * decodes still goes first, [VideoCodecChoice.forCall]), so VP9-capable peers send VP9, and AV1-only peers
 * (A15s, Xiaomi: no VP9 encoder; Android's CDD requires VP9 decoding, not encoding) send AV1 instead
 * of failing with "Peer cannot decode any locally available call codec".
 *
 * The decision uses only this phone's own decoders and the end-to-end hello, which relays copy
 * opaquely, so direct and relayed calls agree in both setup roles. The first version keyed on the
 * peer's link hello, which on a relayed call is the relay's (relaycall-915d7390b/0004: A15s -> S24
 * relay -> ASUS still failed). The ASUS decodes AV1 640x480 with 0 freezes in 12 stream cases
 * (bmaraw-ab-stream-9c121b30-m31asus-r1). [fallback] false is the control arm
 * (`test_call_codec_fallback=false`): VP9 only, exactly as before.
 */
internal fun privateCallEndpointDecodeMask(
    restrictedMask: Int,
    platformMask: Int,
    fallback: Boolean,
): Int {
    val extra = platformMask and restrictedMask.inv() and CALL_CODEC_MASK
    return if (fallback && extra != 0) restrictedMask or extra or NodeCapabilities.DECODE_PREFERS_VP9 else restrictedMask
}

/** Every call codec (Design HC adds H.264 and HEVC; their bits exist only while their masters are on). */
private const val CALL_CODEC_MASK = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1 or
    NodeCapabilities.CODEC_H264 or NodeCapabilities.CODEC_HEVC

/**
 * End-to-end decode-mask bit 4 (Design HC, "my AV1 decoding is software"): set when every AV1 decoder this phone
 * would use is software ([av1DecoderHardware] false; null when there is no AV1 decoder) and it also decodes HEVC or
 * VP9. [enabled] is "a Design HC master is on and the `test_av1_soft_decode_bit` arm is not off"; off, the mask is
 * returned unchanged, so a build with both masters off sends today's bytes.
 */
internal fun withAv1SoftwareDecodeBit(decodeMask: Int, av1DecoderHardware: Boolean?, enabled: Boolean): Int {
    val decodesAlternative = decodeMask and (NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_HEVC) != 0
    val softwareAv1 = av1DecoderHardware == false && decodeMask and NodeCapabilities.CODEC_AV1 != 0
    return if (enabled && softwareAv1 && decodesAlternative) decodeMask or NodeCapabilities.DECODE_AV1_SOFTWARE else decodeMask
}

/**
 * The receiver's playout profile for this call. Experimental [lowDelayVoice]: a voice-only call on an
 * unconstrained link uses CALL_VOICE; video calls and constrained links (CALL_STABLE, whose audio
 * arrives in batches of up to 400 ms) keep [routeProfile].
 */
internal fun privateCallReceiverPlaybackProfile(
    routeProfile: PlaybackDelayProfile,
    localCallType: CallType,
    lowDelayVoice: Boolean,
): PlaybackDelayProfile = if (
    lowDelayVoice && localCallType == CallType.VOICE && routeProfile != PlaybackDelayProfile.CALL_STABLE
) {
    PlaybackDelayProfile.CALL_VOICE
} else {
    routeProfile
}

/** Peer-advertised call maxima that mark a constrained peer (the values legacy-family phones advertise). */
private const val MSMNILE_COC_BITRATE_KBPS = DevicePolicyRegistry.MSMNILE_CALL_KBPS
private const val CONSTRAINED_COC_BITRATE_KBPS = DevicePolicyRegistry.LEGACY_COC_CALL_KBPS
private const val CONSTRAINED_RFCOMM_BITRATE_KBPS = DevicePolicyRegistry.LEGACY_CLASSIC_ADVERTISED_KBPS
private const val CONSTRAINED_PEER_CALL_AUDIO_BIT_RATE = 16_000

internal class PrivateCallMediaReceiver(
    /**
     * The remote picture's surface, asked for only when a video packet needs a decoder; null while the call view has
     * none (a voice call answered from the lock screen: F17 second instance, [VideoSurfaceWait]).
     */
    private val surfaceProvider: () -> Surface?,
    private val playbackDelayProfile: PlaybackDelayProfile,
    private val transport: BluetoothTransportOption,
    private val onVideoFormat: (StreamProtocol.Header) -> Unit,
    private val onRemoteVideoActive: (Boolean) -> Unit,
    private val onKeyFrameNeeded: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val debugMediaTrace: DebugMediaTrace? = null,
    private val debugCallId: String? = null,
    private val debugTraceContext: Context? = null,
    private val debugTraceSession: String? = null,
    /**
     * Experimental speech-led call playout (CallPlayoutPolicy, default off = null): late video may
     * grow the shared call delay only this far; audio keeps the full adaptive range.
     */
    private val videoGrowthLimitMs: Long? = null,
) : Closeable {
    private var closed = false
    private var header: StreamProtocol.Header? = null
    private val videoConfig = mutableListOf<ByteArray>()
    private val audioConfig = mutableListOf<ByteArray>()
    private var decoder: VideoStreamDecoder? = null
    private var videoDecoderRecoveryAttempts = 0
    /** DEBUG call join stages: the first call video packet offered here. */
    private var firstVideoMarked = false
    /** Experimental call pre-warm ([CallPrewarm]): the decoder component allocated when the peer's header arrived. */
    private var preparedDecoder: PreparedDecoderComponent<MediaCodec>? = null
    private var debugPriorDecoderDrops = 0L
    private var awaitingVideoRecoveryKeyFrame = false
    /** Experimental `test_call_video_restart` (default off): a stalled video decoder is replaced, not the call ended. */
    private val videoStallRestart = CallVideoStallRestart(
        enabled = DebugTunables.callVideoRestart,
        onStatus = { status -> onStatus(status) },
        onKeyFrameNeeded = { onKeyFrameNeeded() },
        onRemoteVideoActive = { active -> onRemoteVideoActive(active) },
        log = { line -> Log.w("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + line) },
    )
    private val surfaceWait = VideoSurfaceWait()
    private var audioDecoder: OpusAudioDecoder? = null
    private var playoutClock: MediaPlayoutClock? = null
    /** Experimental (`test_call_audio_own_clock`): audio's own delay, grown only by late audio. */
    private var audioPlayoutClock: MediaPlayoutClock? = null
    /** Experimental `test_call_stale_audio_drop`: after a stall, audio older than the playout budget ([CallStaleAudio]). */
    private val staleAudio = CallStaleAudio(
        enabled = DebugTunables.callStaleAudioDrop,
        budgetMs = playbackDelayProfile.adaptiveMaxDelayMs,
        log = { line -> Log.i("BluetoothVideo", line) },
    )
    private var videoPackets = 0L
    private var audioPackets = 0L
    private var bytes = 0L
    private var lastReportBytes = 0L
    private var lastReportNs = System.nanoTime()
    private val videoArrivalInterval = RollingLatencyStats()
    private val videoArrivalLead = RollingLatencyStats()
    private val videoArrivalLateness = RollingLatencyStats()
    private var lastVideoArrivalNs = 0L
    private val windowMaxLatenessMs = java.util.concurrent.atomic.AtomicLong()
    /** Same lateness, read by the BENCHMARK media_age line instead of the peer report. */
    private val windowLateMaxForAgeMs = java.util.concurrent.atomic.AtomicLong()
    /** F16: the largest video lateness since the call sender last took it; -1 = no video arrived. */
    private val reverseLateMaxMs = java.util.concurrent.atomic.AtomicLong(-1L)

    /**
     * Hop-local report for the peer's sender: how much this phone delays playback, or how late
     * video arrives when the call delay is fixed, since the previous report.
     */
    /** F16 ([CallLinkShare]): the largest lateness of the received video since the last call, or null when none arrived. */
    fun takeReverseLateMaxMs(): Long? = reverseLateMaxMs.getAndSet(-1L).takeIf { it >= 0L }

    fun takeLinkReport(arrivalKbps: Int): LinkCapacityReport {
        val clock = playoutClock
        val lateness = windowMaxLatenessMs.getAndSet(0L)
        val addedDelay = maxOf(clock?.addedDelayMs ?: 0L, lateness)
        return LinkCapacityReport(arrivalKbps, clock?.saturated == true, addedDelay.coerceIn(0L, 0xffffL).toInt())
    }

    fun setHeader(value: StreamProtocol.Header) {
        require(header == null) { "The peer sent more than one call stream header" }
        header = value
        // test_call_fast_recovery (promoted 2026-11-12, default on): attached to the clock audio follows, since audio does the catching up.
        val fastRecovery = CallFastRecovery.forProfile(playbackDelayProfile, DebugTunables.callFastRecovery) { line ->
            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", line)
        }
        playoutClock = playbackDelayProfile.newPlayoutClock(
            fastRecovery = fastRecovery.takeUnless { DebugTunables.callAudioOwnClock },
        )
        audioPlayoutClock = if (DebugTunables.callAudioOwnClock) {
            playbackDelayProfile.newPlayoutClock(fastRecovery = fastRecovery)
        } else {
            null
        }
        onVideoFormat(value)
        if (DebugTunables.callPrewarm) prepareDecoderComponent(value)
        onStatus(
            "Remote ${VideoCodecMap.label(value.codecId)} " +
                "${value.displayWidth}x${value.displayHeight}/${value.fps} with Opus audio",
        )
    }

    /**
     * Experimental call pre-warm: the peer named its codec and size (it sends the header at its Accept, ahead of its
     * camera start), so the decoder component the first key frame will configure is allocated now, off this thread.
     */
    private fun prepareDecoderComponent(value: StreamProtocol.Header) {
        val mime = videoDecoderMime(value.codecId) ?: return
        preparedDecoder = PreparedDecoderComponent<MediaCodec>(
            runAsync = { task -> Thread(task, "CallDecoderPrewarm").start() },
            allocate = {
                val chosen = privateCallPreferredDecoderName(value.codecId, privateCallStackFingerprint())
                    ?: android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).findDecoderForFormat(
                        android.media.MediaFormat.createVideoFormat(mime, value.width, value.height),
                    )
                chosen?.let { name -> name to MediaCodec.createByCodecName(name) }
            },
            release = { codec -> codec.release() },
            log = { line -> Log.i("BluetoothVideo", line) },
        ).also { it.prepare() }
    }

    @Synchronized
    fun offer(received: StreamProtocol.Packet) {
        if (closed) return
        val activeHeader = header ?: error("Call media arrived before its stream header")
        val packet = StreamProtocol.verifyAndStripDebugIntegrity(received).packet
        if (!packet.isAudio && packet.flags and (StreamProtocol.FLAG_CODEC_CONFIG or StreamProtocol.FLAG_DISPLAY_ROTATION) == 0) {
            debugMediaTrace?.record(packet.presentationTimeUs, packet.flags, packet.payload,
                null, null, null, null, "authenticated")
        }
        when {
            packet.isAudio -> offerAudio(activeHeader, packet)
            packet.flags and StreamProtocol.FLAG_DISPLAY_ROTATION != 0 -> {
                val updated = activeHeader.copy(
                    rotationDegrees = StreamProtocol.decodeDisplayRotation(packet),
                )
                header = updated
                onVideoFormat(updated)
            }
            // Review 2026-10-19 (P2): bounded until the decoder exists (CodecConfigBudget).
            packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0 && decoder == null -> {
                VideoCodecMap.requireNoCodecConfig(activeHeader.codecId, packet.flags)
                CodecConfigBudget.add(videoConfig, packet.payload)
            }
            else -> offerVideo(activeHeader, packet)
        }
        reportIfDue()
    }

    /** The remote picture changed size (call resolution ladder): the surface transform follows the new size. */
    private fun followCodedSize(width: Int, height: Int) {
        val current = header ?: return
        if (current.width == width && current.height == height) return
        val updated = current.copy(width = width, height = height)
        header = updated
        onVideoFormat(updated)
    }

    fun setRemoteCameraEnabled(enabled: Boolean) {
        // Experimental test_call_video_restart: video stopped on this phone; the peer's camera choice cannot restart it.
        if (videoStallRestart.videoStopped) return
        onRemoteVideoActive(enabled)
        if (enabled) onKeyFrameNeeded()
    }

    private fun offerAudio(
        activeHeader: StreamProtocol.Header,
        packet: StreamProtocol.Packet,
    ) {
        require(activeHeader.hasAudio) { "Peer sent undeclared call audio" }
        if (packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0) {
            if (audioDecoder == null) CodecConfigBudget.add(audioConfig, packet.payload)
            return
        }
        // Experimental own audio clock: video bursts no longer hold speech back for lip sync.
        val clock = audioPlayoutClock ?: requireNotNull(playoutClock)
        val timing = clock.timing(packet.presentationTimeUs)
        // Experimental test_call_stale_audio_drop: after a stall, audio older than the playout budget is not played.
        val decision = staleAudio.onAudio(System.nanoTime() / 1_000_000L, packet.presentationTimeUs, timing.latenessMs)
        if (decision == CallStaleAudio.Decision.DROP) {
            bytes += packet.payload.size
            return
        }
        val activeDecoder = audioDecoder ?: inLocalMediaStage(LocalMediaStage.AUDIO_DECODER_SETUP) {
            // JT2 camera-first (off by default): the AudioTrack (voice-communication usage) is created only after this call's
            // queued audio open set the communication mode; nothing is queued, so nothing waits, with the switch off.
            if (DebugTunables.callCameraFirst && !CallAudioOpenGate.shared.await(CallAudioOpenGate.WAIT_MS)) {
                Log.i("BluetoothVideo", "BENCHMARK call_audio_open_wait: event=timeout | waitedMs=${CallAudioOpenGate.WAIT_MS}")
            }
            OpusAudioDecoder(
                activeHeader,
                audioConfig,
                playbackDelayProfile,
                clock,
                communicationMode = true,
                debugTraceContext = debugTraceContext,
                debugTraceSession = debugTraceSession,
                concealment = DebugTunables.callAudioConceal,
            )
        }.also { audioDecoder = it }
        inLocalMediaStage(LocalMediaStage.AUDIO_PIPELINE) { activeDecoder.offer(packet) }
        audioPackets++
        bytes += packet.payload.size
    }

    private fun offerVideo(
        activeHeader: StreamProtocol.Header,
        packet: StreamProtocol.Packet,
    ) {
        if (videoStallRestart.videoStopped) return
        if (awaitingVideoRecoveryKeyFrame && packet.flags and StreamProtocol.FLAG_KEY_FRAME == 0) return
        awaitingVideoRecoveryKeyFrame = false
        if (!firstVideoMarked) {
            firstVideoMarked = true
            CallJoinStages.mark(CallJoinStage.FIRST_FRAME_RECEIVED, "keyFrame=${packet.flags and StreamProtocol.FLAG_KEY_FRAME != 0}")
        }
        val clock = requireNotNull(playoutClock)
        val arrivalNs = System.nanoTime()
        val arrivalTiming = videoPacketPlayoutTiming(packet, clock, arrivalNs, videoGrowthLimitMs)
        if (arrivalTiming != null) {
            if (lastVideoArrivalNs > 0L) videoArrivalInterval.recordNanos(arrivalNs - lastVideoArrivalNs)
            lastVideoArrivalNs = arrivalNs
            videoArrivalLead.recordNanos(arrivalTiming.targetTimeNs - arrivalNs)
            videoArrivalLateness.recordNanos(arrivalTiming.latenessMs * 1_000_000L)
            windowMaxLatenessMs.accumulateAndGet(arrivalTiming.latenessMs, ::maxOf)
            windowLateMaxForAgeMs.accumulateAndGet(arrivalTiming.latenessMs, ::maxOf)
            reverseLateMaxMs.accumulateAndGet(arrivalTiming.latenessMs, ::maxOf)
        }
        val activeDecoder = decoder ?: run {
            val surface = surfaceProvider()
            // After a skip the decoder must start at a key frame; the stream's first packet starts it as before.
            val startable = packet.flags and StreamProtocol.FLAG_KEY_FRAME != 0 || surfaceWait.skipped == 0L
            when (surfaceWait.onVideoPacket(surface != null && startable, System.nanoTime())) {
                VideoSurfaceWait.Action.DECODE -> if (surfaceWait.skipped > 0L) {
                    Log.i("BluetoothVideo", "BENCHMARK call_video_surface: event=ready | skipped=${surfaceWait.skipped}")
                }
                VideoSurfaceWait.Action.SKIP -> return
                VideoSurfaceWait.Action.SKIP_AND_REQUEST_KEY_FRAME -> {
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK call_video_surface: event=waiting | surface=${surface != null} | skipped=${surfaceWait.skipped}",
                    )
                    onKeyFrameNeeded()
                    return
                }
            }
            val preferredDecoderName = privateCallPreferredDecoderName(
                activeHeader.codecId,
                privateCallStackFingerprint(),
            )
            inLocalMediaStage(LocalMediaStage.VIDEO_DECODER_SETUP) {
                VideoStreamDecoder(
                    activeHeader,
                    requireNotNull(surface),
                    videoConfig,
                    playbackDelayProfile = playbackDelayProfile,
                    playoutClock = clock,
                    preferredDecoderName = preferredDecoderName,
                    // A policy-forced software decoder may only fall back to another software decoder.
                    decoderRequest = if (preferredDecoderName != null) {
                        DecoderRequest.SOFTWARE_PROTECTION
                    } else {
                        DecoderRequest.NONE
                    },
                    debugTraceSession = debugTraceSession,
                    queueCapacity = privateCallVideoQueueCapacity(
                        activeHeader.fps,
                        playbackDelayProfile,
                        transport,
                        privateCallStackFingerprint(),
                    ),
                    presentFirstFrameImmediately = DebugTunables.callFirstFrameNow,
                    firstFrameRamp = DebugTunables.callFirstFrameRamp,
                    firstPacket = packet,
                    onCodedSizeChanged = { width, height -> followCodedSize(width, height) },
                    onFirstDecoded = { CallJoinStages.mark(CallJoinStage.FIRST_FRAME_DECODED) },
                    preallocated = preparedDecoder?.let { prepared -> { name: String -> prepared.take(name) } },
                    stallRestartsCaller = videoStallRestart.enabled,
                    queueWaitNs = videoStallRestart.queueWaitNs,
                    stallExcludedDecoders = videoStallRestart.excludedDecoders,
                )
            }.also {
                decoder = it
                // The call header names the maximum; the encoder may already be on a lower rung.
                if (it.codedWidth != activeHeader.width || it.codedHeight != activeHeader.height) {
                    followCodedSize(it.codedWidth, it.codedHeight)
                }
                // Announce the decoder actually running, not the one the policy asked for.
                preferredDecoderName?.let { requested ->
                    onStatus(
                        if (it.decoderName.equals(requested, ignoreCase = true)) {
                            "Private-call compatibility using software VP9 decoder ${it.decoderName}"
                        } else {
                            "Private-call compatibility asked for $requested; decoding with ${it.decoderName}"
                        },
                    )
                }
                onRemoteVideoActive(true)
            }
        }
        try {
            activeDecoder.offer(packet)
        } catch (stall: DecoderStalledException) {
            // Off (the default): the call ends as before. On: this decoder is replaced at the next keyframe.
            if (!videoStallRestart.handles(stall)) throw LocalMediaPipelineException(LocalMediaStage.VIDEO_DECODER, stall)
            if (BuildConfig.DEBUG) debugPriorDecoderDrops += activeDecoder.stats().queue.droppedFrames
            decoder = null
            videoConfig.clear()
            videoConfig += activeDecoder.currentCodecSpecificData()
            awaitingVideoRecoveryKeyFrame = videoStallRestart.onStall(
                stall, activeDecoder.decoderName, activeDecoder.renderedFrames, activeDecoder::closeWithoutWaiting,
            )
        } catch (error: IllegalStateException) {
            val codecError = error.cause as? MediaCodec.CodecException
            if (codecError == null ||
                !canRecoverPrivateCallVideoDecoderFailure(
                    activeDecoder.decoderName,
                    videoDecoderRecoveryAttempts,
                    recoverable = codecError.isRecoverable,
                    traits = DeviceTraits.current(),
                )
            ) {
                throw LocalMediaPipelineException(LocalMediaStage.VIDEO_DECODER, error)
            }
            videoDecoderRecoveryAttempts++
            if (BuildConfig.DEBUG) debugPriorDecoderDrops += activeDecoder.stats().queue.droppedFrames
            decoder = null
            awaitingVideoRecoveryKeyFrame = true
            // The next decoder starts from the configuration of the encoder now sending (a rung change
            // may have replaced the call-start one), not the one collected before the first decoder.
            videoConfig.clear()
            videoConfig += activeDecoder.currentCodecSpecificData()
            activeDecoder.close()
            Log.w(
                "BluetoothVideo",
                "Call video decoder restart $videoDecoderRecoveryAttempts (recoverable=${codecError.isRecoverable}, " +
                    "transient=${codecError.isTransient}); waiting for a fresh keyframe",
                error,
            )
            onStatus("Recovering remote video; audio and call remain connected")
            onKeyFrameNeeded()
        }
        videoPackets++
        bytes += packet.payload.size
    }

    private fun reportIfDue(force: Boolean = false) {
        val nowNs = System.nanoTime()
        if (!force && nowNs - lastReportNs < METRICS_INTERVAL_NS) return
        val seconds = (nowNs - lastReportNs) / 1_000_000_000.0
        val kbps = (bytes - lastReportBytes) * 8 / seconds / 1_000
        val stats = decoder?.stats()
        val audioDrops = audioDecoder?.droppedPackets ?: 0L
        if (!force && stats?.queue?.needsKeyFrame == true) onKeyFrameNeeded()
        val decoderSummary = stats?.let {
            val cadence = it.renderedFrameInterval
            val arrivalCadence = videoArrivalInterval.snapshot()
            val arrivalLead = videoArrivalLead.snapshot()
            val arrivalLateness = videoArrivalLateness.snapshot()
            "%d/%d shown, cadence p50/p95/p99/max %.1f/%.1f/%.1f/%.1f ms, queue %d/%d, drops %d | ".format(
                it.renderedFrames,
                it.submittedForRenderFrames,
                cadence.p50Ms,
                cadence.p95Ms,
                cadence.p99Ms,
                cadence.maxMs,
                it.queue.depth,
                it.queue.capacity,
                it.queue.droppedFrames + debugPriorDecoderDrops,
            ) + "video arrival p95/max %.1f/%.1f ms, lead p50 %.1f ms, late p95/max %.1f/%.1f ms, ".format(
                arrivalCadence.p95Ms,
                arrivalCadence.maxMs,
                arrivalLead.p50Ms,
                arrivalLateness.p95Ms,
                arrivalLateness.maxMs,
            ) + "decoder wait p95/max %.1f/%.1f ms, render ahead p95/max %.1f/%.1f ms".format(
                it.decoderWait.p95Ms,
                it.decoderWait.maxMs,
                it.scheduledRenderDelay.p95Ms,
                it.scheduledRenderDelay.maxMs,
            )
        } ?: "starting"
        Log.i(
            "BluetoothVideo",
            "Call RX %.0f kbps | %d video/%d audio | audio drops %d | %s".format(
                kbps,
                videoPackets,
                audioPackets,
                audioDrops,
                decoderSummary,
            ),
        )
        if (BuildConfig.DEBUG) {
            // Both arms: the playout delay this phone holds for the peer's media plus how late video
            // arrived beyond it since the last line (the peer's queue age is in its call_tx line).
            val clock = playoutClock
            val lateMax = windowLateMaxForAgeMs.getAndSet(0L)
            Log.i(
                "BluetoothVideo",
                "BENCHMARK media_age: role=call_rx | callId=$debugCallId | final=$force | " +
                    "policy=${BoundedMediaAge.policyLabel()} | " +
                    (if (videoGrowthLimitMs != null) "speechLedVideoLimitMs=$videoGrowthLimitMs | " else "") +
                    "playoutDelayMs=${clock?.currentDelayMs ?: 0} | " +
                    "addedDelayMs=${clock?.addedDelayMs ?: 0} | lateMaxMs=$lateMax | " +
                    "heldAgeMaxMs=${(clock?.currentDelayMs ?: 0L) + lateMax} | videoPackets=$videoPackets | " +
                    "audioPackets=$audioPackets",
            )
        }
        if (force && BuildConfig.DEBUG) Log.i(
            "BluetoothVideo",
            "BENCHMARK call rx: callId=$debugCallId | final=true | " +
                "audioDrops=${audioDecoder?.droppedPackets ?: "unavailable"} | " +
                "decoderDrops=${stats?.queue?.droppedFrames?.plus(debugPriorDecoderDrops)
                    ?: debugPriorDecoderDrops.takeIf { it > 0 } ?: "unavailable"} | " +
                "videoPackets=$videoPackets | audioPackets=$audioPackets",
        )
        lastReportBytes = bytes
        lastReportNs = nowNs
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (BuildConfig.DEBUG) reportIfDue(force = true)
        debugMediaTrace?.close()
        preparedDecoder?.close()
        decoder?.close()
        decoder = null
        audioDecoder?.close()
        audioDecoder = null
        staleAudio.close()
    }

    private companion object {
        const val METRICS_INTERVAL_NS = 2_000_000_000L
    }
}
