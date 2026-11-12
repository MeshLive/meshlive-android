package com.example.bluetoothtest

/**
 * Experimental `test_sw_shortfall_avc` (AP5, 2026-11-07; default off, DEBUG launch only): hardware H.264 when a software
 * encoder cannot keep up.
 *
 * Problem: a software encoder that Automatic puts first (the bundled libvpx VP9 ranks above hardware H.264, Design VX) can
 * send far fewer frames than asked on a slow phone while the phone's hardware H.264 encoder would hold the rate (the A15s's
 * libvpx records an encode load of 1.76). Nothing remembers it, so every call repeats it.
 *
 * Measured, not assumed: [SoftwareShortfallWindows] reads the native encoder's own 5 s cadence windows. A window is short
 * when the encoder sent under [SHORT_RATIO] of the frame rate in force at that moment (the request, lowered by any link or
 * thermal limit and the low-light follower), its input delivered that rate (camera sensor frame within [SOURCE_SLACK] of
 * the frame interval; a raw fixture paces itself), and the encoder itself was the limit (encode p95 longer than the frame
 * interval, or input frames dropped while it was busy). [SHORT_WINDOWS] short windows in a row after [WARMUP_WINDOWS]
 * warm-up window (about 20 s) record a shortfall for this encoder at this session's size and frame rate for [MEMORY_MS].
 *
 * The rule ([reorder]): with the switch on, when a later call or stream would start with a software encoder remembered
 * short at the same or a lower pixel rate, and the Automatic list (already filtered by what the peer decodes) holds a
 * usable hardware H.264 encoder, the hardware H.264 encoders go first. It changes the codec choice only: the requested
 * size, frame rate and bitrate stay as asked; a hand-picked encoder, a family VP9 rule, a peer's "prefers VP9" bit and
 * the power-aware start are never overridden; it is decided once at the start, never during a call or stream. No wire
 * change.
 */
internal object SoftwareEncoderShortfall {
    /** A window is short when the encoder sent less than this share of the frame rate in force. */
    const val SHORT_RATIO = 0.8
    /** Short windows in a row (5 s encoder windows, after [WARMUP_WINDOWS]) that record a shortfall: 15 s. */
    const val SHORT_WINDOWS = 3
    /** Windows ignored at the start while the encoder's speed governor settles. */
    const val WARMUP_WINDOWS = 1
    /** The input delivered the rate when the camera's sensor frame is at most 10 % longer than the frame interval. */
    const val SOURCE_SLACK = 1.1
    /** A recorded shortfall is remembered this long (as CallEncoderFit). */
    const val MEMORY_MS = 7L * 24 * 60 * 60 * 1_000
    /** `encoder_choice` reason when the rule picked hardware H.264. */
    const val REASON = "sw_shortfall_prefers_hw_avc"

    /** The mark stored under [key] ([SoftwareShortfallMark.key]), or null when [key] is not one. */
    fun parse(key: String): SoftwareShortfallMark? {
        val name = key.substringBeforeLast('|', "")
        val profile = key.substringAfterLast('|', "")
        val size = profile.substringBefore('@', "")
        val fps = profile.substringAfter('@', "").toIntOrNull() ?: return null
        val width = size.substringBefore('x', "").toIntOrNull() ?: return null
        val height = size.substringAfter('x', "").toIntOrNull() ?: return null
        if (name.isEmpty() || width <= 0 || height <= 0 || fps <= 0) return null
        return SoftwareShortfallMark(name, width, height, fps)
    }

    /**
     * The software encoders remembered short for a session of [width]x[height] at [fps]: a mark (key -> recorded at, epoch
     * ms) younger than [MEMORY_MS] of the same encoder at the same or a lower pixel rate (fewer pixels per second already
     * did not keep up). A mark dated after [nowMs] (the clock moved back) does not count.
     */
    fun slowEncoders(marks: Map<String, Long>, width: Int, height: Int, fps: Int, nowMs: Long): Set<String> {
        val sessionRate = width.toLong() * height * fps
        return marks.mapNotNull { (key, atMs) ->
            val mark = parse(key) ?: return@mapNotNull null
            mark.encoderName.takeIf { nowMs - atMs in 0 until MEMORY_MS && mark.pixelRate <= sessionRate }
        }.toSet()
    }

    private fun isHardware(codec: VideoCodec) = codec.backend == VideoEncoderBackend.MEDIA_CODEC && codec.hardwareAccelerated

    /**
     * The rule on an Automatic list (already filtered for the peer): when [enabled], the first entry is a software encoder
     * named in [slow] and the list holds a hardware H.264 encoder that is not [excluded], the hardware H.264 encoders move
     * to the front (their order kept) and every other entry follows in order. Null when the rule leaves the list as it is.
     */
    fun reorder(list: List<VideoCodec>, enabled: Boolean, slow: Set<String>, excluded: (VideoCodec) -> Boolean): List<VideoCodec>? {
        if (!enabled) return null
        val first = list.firstOrNull() ?: return null
        if (isHardware(first) || first.encoderName !in slow) return null
        val avc = list.filter { isHardware(it) && it.protocolId == StreamProtocol.CODEC_H264 && !excluded(it) }
        if (avc.isEmpty()) return null
        return avc + list.filter { it !in avc }
    }
}

/** One remembered shortfall: this phone's [encoderName] could not keep up at [width]x[height] and [fps] ([SoftwareEncoderShortfall]). */
data class SoftwareShortfallMark(val encoderName: String, val width: Int, val height: Int, val fps: Int) {
    /** The stored key, `<encoder>|<width>x<height>@<fps>`. */
    val key: String get() = "$encoderName|${width}x$height@$fps"
    val pixelRate: Long get() = width.toLong() * height * fps
}

/** What the last window measured when a shortfall was recorded ([SoftwareShortfallWindows.onWindow]). */
data class SoftwareShortfallVerdict(
    /** Short windows in a row. */
    val windows: Int,
    val outputFps: Double,
    /** The frame rate in force (the request, lowered by any limit). */
    val targetFps: Int,
    val encodeP95Ms: Double?,
    /** Camera sensor frame duration; null for a raw fixture or when the camera does not report it. */
    val sourceFrameMs: Double?,
    /** Input frames dropped in the last window because the encoder was busy. */
    val inputDrops: Long,
)

/**
 * One encoder session's windows ([SoftwareEncoderShortfall]); pure, so a JVM test replays it window by window. The encoder
 * calls [onWindow] once per 5 s cadence report; the first non-null answer is the only one (one record per session).
 */
internal class SoftwareShortfallWindows {
    enum class Window { WARMUP, ON_RATE, SOURCE_LIMITED, NOT_ENCODER_LIMITED, SHORT, DONE }

    var seen = 0
        private set
    var shortRun = 0
        private set
    var last: Window? = null
        private set
    var recorded = false
        private set

    fun onWindow(outputFps: Double, targetFps: Int, sourceFrameMs: Double?, encodeP95Ms: Double?, inputDrops: Long): SoftwareShortfallVerdict? {
        seen++
        val intervalMs = 1_000.0 / targetFps.coerceAtLeast(1)
        val window = when {
            recorded -> Window.DONE
            seen <= SoftwareEncoderShortfall.WARMUP_WINDOWS -> Window.WARMUP
            outputFps >= SoftwareEncoderShortfall.SHORT_RATIO * targetFps -> Window.ON_RATE
            sourceFrameMs != null && sourceFrameMs > intervalMs * SoftwareEncoderShortfall.SOURCE_SLACK -> Window.SOURCE_LIMITED
            !(encodeP95Ms != null && encodeP95Ms > intervalMs) && inputDrops <= 0 -> Window.NOT_ENCODER_LIMITED
            else -> Window.SHORT
        }
        last = window
        shortRun = when (window) {
            Window.SHORT -> shortRun + 1
            Window.DONE -> shortRun
            else -> 0
        }
        if (window != Window.SHORT || shortRun < SoftwareEncoderShortfall.SHORT_WINDOWS) return null
        recorded = true
        return SoftwareShortfallVerdict(shortRun, outputFps, targetFps, encodeP95Ms, sourceFrameMs, inputDrops)
    }
}

/**
 * This phone's remembered shortfalls ([SoftwareEncoderShortfall]): SharedPreferences [PREFS], mark key -> recorded at
 * (epoch ms). Encoder names and sizes only. Cleared by `test_reset_learned_state` and by the emergency wipe.
 */
internal object SoftwareShortfallStore {
    const val PREFS = "sw_encoder_shortfall"

    private fun prefs(context: android.content.Context) =
        context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private fun marks(context: android.content.Context): Map<String, Long> =
        prefs(context).all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()

    /** The software encoders remembered short for [settings]; empty (nothing is read) while the switch is off. */
    fun slowEncoders(context: android.content.Context, settings: VideoSettings, nowMs: Long = System.currentTimeMillis()): Set<String> {
        if (!DebugTunables.swShortfallAvc) return emptySet()
        return runCatching {
            SoftwareEncoderShortfall.slowEncoders(marks(context), settings.resolution.width, settings.resolution.height, settings.fps, nowMs)
        }.getOrDefault(emptySet())
    }

    fun record(context: android.content.Context, mark: SoftwareShortfallMark, nowMs: Long = System.currentTimeMillis()) {
        if (WipeGuard.active) return
        prefs(context).edit().putLong(mark.key, nowMs).apply()
    }

    /** DEBUG `test_reset_learned_state`: removes every mark; returns how many there were. */
    fun clear(context: android.content.Context): Int {
        val store = prefs(context)
        val count = store.all.size
        if (count > 0) store.edit().clear().commit()
        return count
    }

    /** DEBUG launch record: the fresh marks with their age in minutes, or `none`. */
    fun summary(context: android.content.Context, nowMs: Long = System.currentTimeMillis()): String = runCatching {
        marks(context).filter { (_, atMs) -> nowMs - atMs in 0 until SoftwareEncoderShortfall.MEMORY_MS }
            .entries.sortedBy { it.key }
            .joinToString(",") { "${it.key}(%.0fmin)".format(java.util.Locale.ROOT, (nowMs - it.value) / 60_000.0) }
            .ifEmpty { "none" }
    }.getOrDefault("unavailable")
}
