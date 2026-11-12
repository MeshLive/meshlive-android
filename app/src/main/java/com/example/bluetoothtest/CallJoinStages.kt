package com.example.bluetoothtest

import android.os.SystemClock
import android.util.Log

/**
 * The join stages of one private call on this phone, from dial (caller) or the arriving link (callee) to the first
 * remote picture shown. DEBUG benchmark logging only: one `BENCHMARK call_join_stage:` line per stage per call, on this
 * phone's own clock (`atMs` = SystemClock.elapsedRealtime, `sinceStartMs` = since this phone's first stage of the call).
 *
 * Why (JT1, 2026-11-10): the user-launch calls held but `setup_to_first_surface_ms` failed everywhere (3.1-8.9 s against
 * 3.0 s). The stages existed only partly (phase lines, the authenticated-link line, LE `call_setup hopAuthMs`, the
 * encoder-init line, `call first_surface`), with nothing for the camera's first frame, the first encoded frame handed to
 * the link, the first frame received or decoded, or a black first picture. One line format per stage makes the per-stage
 * breakdown one grep on each phone.
 */
enum class CallJoinStage(val label: String) {
    /**
     * The user's tap (or the DEBUG dial command) that led to this call's dial, logged first, when the dial follows it by under
     * [CallJoinTimeline.TAP_MAX_AGE_MS]. Its `sinceStartMs` is negative (the tap came before the dial, which keeps its meaning).
     */
    TAP("tap"),
    DIAL("dial"),
    LINK_UP("link_up"),
    AUTHENTICATED("authenticated"),
    RINGING("ringing"),
    ACCEPT("accept"),
    ACTIVE("active"),
    ENCODER_STARTED("encoder_started"),
    CAMERA_FIRST_FRAME("camera_first_frame"),
    FIRST_FRAME_SENT("first_frame_sent"),
    FIRST_FRAME_RECEIVED("first_frame_received"),
    FIRST_FRAME_DECODED("first_frame_decoded"),
    FIRST_FRAME_SHOWN("first_frame_shown"),

    /** JT2 camera sub-stages (the local camera of a call): the open request, the preview surface, the opened device, the configured session. */
    CAMERA_OPEN_REQUESTED("camera_open_requested"),
    PREVIEW_SURFACE_READY("preview_surface_ready"),
    CAMERA_OPENED("camera_opened"),
    CAMERA_SESSION_CONFIGURED("camera_session_configured"),

    /** The first shown picture that is not black ([FirstNonBlackWatch]); the moment of FIRST_FRAME_SHOWN when that one is not black. */
    FIRST_NONBLACK_SHOWN("first_nonblack_shown"),
}

/** One call's stages: each is logged once, the first time it happens after [begin]; nothing before [begin] or after [end]. */
internal class CallJoinTimeline(
    private val enabled: Boolean,
    private val clockMs: () -> Long,
    private val log: (String) -> Unit,
) {
    private var startMs = -1L
    private var role = "unknown"
    private var callId: String? = null
    private val marked = java.util.EnumSet.noneOf(CallJoinStage::class.java)
    private val markedAtMs = java.util.EnumMap<CallJoinStage, Long>(CallJoinStage::class.java)
    private val markedDetail = java.util.EnumMap<CallJoinStage, String>(CallJoinStage::class.java)
    private val onceKeys = HashSet<String>()
    private var tapAtMs = -1L
    private var tapSource = "user"

    /**
     * The user's tap (or a DEBUG dial command) on a person to call. Nothing is logged now: the dial that follows within
     * [TAP_MAX_AGE_MS] logs it as the call's first stage ([begin]); a tap no dial follows is never logged. The latest tap wins.
     */
    @Synchronized
    fun noteTap(source: String = "user") {
        if (!enabled) return
        tapAtMs = clockMs()
        tapSource = source
    }

    /** A new call on this phone: the caller's dial, or the callee's arriving link. */
    @Synchronized
    fun begin(role: String) {
        if (!enabled) return
        val now = clockMs()
        val tapAt = tapAtMs
        tapAtMs = -1L // a tap belongs to the next call start only: a callee's start drops it, as does a stale one
        startMs = now
        this.role = role
        callId = null
        marked.clear()
        markedAtMs.clear()
        markedDetail.clear()
        onceKeys.clear()
        if (role == "caller" && tapAt >= 0L && now - tapAt in 0 until TAP_MAX_AGE_MS) {
            marked.add(CallJoinStage.TAP)
            markedAtMs[CallJoinStage.TAP] = tapAt
            log(
                "BENCHMARK call_join_stage: stage=${CallJoinStage.TAP.label} | role=$role | sinceStartMs=${tapAt - now} | atMs=$tapAt" +
                    " | tapToDialMs=${now - tapAt} | source=$tapSource",
            )
        }
    }

    /** True once per call (the first caller) while a call is timed: for one line per call that is not a stage. */
    @Synchronized
    fun claimOnce(key: String): Boolean = enabled && startMs >= 0L && onceKeys.add(key)

    @Synchronized
    fun callId(id: String?) {
        if (enabled && id != null) callId = id
    }

    /** Logs [stage] once per call; false when it was already logged, no call is being timed, or logging is off. */
    @Synchronized
    fun mark(stage: CallJoinStage, detail: String = ""): Boolean {
        if (!enabled || startMs < 0L || !marked.add(stage)) return false
        val now = clockMs()
        markedAtMs[stage] = now
        if (detail.isNotEmpty()) markedDetail[stage] = detail
        log(
            "BENCHMARK call_join_stage: stage=${stage.label} | role=$role | sinceStartMs=${now - startMs} | atMs=$now" +
                (callId?.let { " | callId=$it" } ?: "") + (if (detail.isEmpty()) "" else " | $detail"),
        )
        return true
    }

    /**
     * The call ended: later stages (a late frame, an encoder closing) belong to no call. One `call_join_summary` line
     * repeats every stage of the call with its `atMs` (and the details a scorer reads: black, meanLuma, reached, by).
     * Why (u10c 2026-11-11): the Realme dropped a ~0.3 s burst of log lines at call start in 4 of 12 calls, losing
     * `first_frame_decoded`/`first_frame_shown` although 800 frames were shown, and the scorer could only call the join
     * failed; the end of a call is quiet, so the summary survives such a burst.
     */
    @Synchronized
    fun end() {
        if (enabled && startMs >= 0L && markedAtMs.isNotEmpty()) log(summaryLine())
        startMs = -1L
    }

    private fun summaryLine(): String {
        val stages = markedAtMs.entries.sortedBy { it.value }.joinToString(",") { (stage, atMs) ->
            val kept = markedDetail[stage]?.let { detail ->
                DETAIL_PAIR.findAll(detail).map { it.groupValues[1] to it.groupValues[2].trim() }
                    .filter { (key, _) -> key in SUMMARY_DETAIL_KEYS }
                    .joinToString(";") { (key, value) -> "$key=$value" }
            }.orEmpty()
            "${stage.label}@$atMs" + if (kept.isEmpty()) "" else "[$kept]"
        }
        return "BENCHMARK call_join_summary: role=$role | startMs=$startMs" + (callId?.let { " | callId=$it" } ?: "") +
            " | stages=$stages"
    }

    /** When [stage] was logged on this phone's clock (`atMs`), or null if it was not logged for this call. */
    @Synchronized
    fun atMs(stage: CallJoinStage): Long? = markedAtMs[stage]

    /** True while a call is timed and [stage] has not been logged yet (so a costlier detail is computed once). */
    @Synchronized
    fun pending(stage: CallJoinStage): Boolean = enabled && startMs >= 0L && stage !in marked

    companion object {
        /** A tap older than this at the dial is not that dial's tap (a person who was not reachable, a dialog left open). */
        const val TAP_MAX_AGE_MS = 15_000L

        /** The stage details a join scorer reads, kept in the call's summary line. */
        private val SUMMARY_DETAIL_KEYS = setOf("black", "meanLuma", "reached", "by")
        private val DETAIL_PAIR = Regex("""(\w+)=([^|;,\[\]]+)""")
    }
}

/**
 * The first NON-black remote picture of a call (DEBUG benchmark logging only). `first_frame_shown` is logged once, for the
 * first shown frame, and in a dark scene that frame is black, so it cannot give "accept to the first picture with content"
 * (JT1 jt-20261110r: every first frame black). This keeps sampling the same 8x8 luma read, with the same luma-below-3
 * rule, until a frame is not black, then logs `first_nonblack_shown` once (`reached=true`; the same moment as
 * `first_frame_shown` when that frame is already not black). At [boundMs] after Accept (the first shown frame when
 * Accept was not logged) or at the call's end it logs the stage with `reached=false` instead.
 */
internal class FirstNonBlackWatch(
    private val timeline: CallJoinTimeline,
    private val clockMs: () -> Long,
    private val boundMs: Long = BOUND_MS,
) {
    private var active = false
    private var shownAtMs = 0L
    private var deadlineMs = 0L
    private var samples = 0

    /** A new call: an earlier call's sampling stops. */
    @Synchronized
    fun reset() {
        active = false
    }

    /** The first shown frame of the call was sampled ([luma], null when the copy was unavailable). True: keep sampling. */
    @Synchronized
    fun onFirstShown(luma: Double?): Boolean {
        active = false
        if (!timeline.pending(CallJoinStage.FIRST_NONBLACK_SHOWN)) return false
        val now = clockMs()
        shownAtMs = now
        deadlineMs = (timeline.atMs(CallJoinStage.ACCEPT) ?: now) + boundMs
        samples = 0
        active = true
        return sample(luma, now)
    }

    /** One later sample. True: keep sampling; false: done (or no call is being watched). */
    @Synchronized
    fun onSample(luma: Double?): Boolean = if (active) sample(luma, clockMs()) else false

    /** The call ended while still black: the stage is logged once as not reached. */
    @Synchronized
    fun finish(reason: String) {
        if (!active) return
        active = false
        timeline.mark(CallJoinStage.FIRST_NONBLACK_SHOWN, "black=true | reached=false | reason=$reason | samples=$samples")
    }

    private fun sample(luma: Double?, now: Long): Boolean {
        samples++
        val black = luma == null || CallJoinStages.black(luma)
        val reading = if (luma == null) "black=unknown" else "black=$black | meanLuma=${"%.1f".format(java.util.Locale.US, luma)}"
        if (!black) {
            active = false
            timeline.mark(CallJoinStage.FIRST_NONBLACK_SHOWN, "$reading | reached=true | samples=$samples | sinceFirstShownMs=${now - shownAtMs}")
            return false
        }
        if (now >= deadlineMs) {
            active = false
            timeline.mark(CallJoinStage.FIRST_NONBLACK_SHOWN, "$reading | reached=false | reason=bound | samples=$samples | sinceFirstShownMs=${now - shownAtMs}")
            return false
        }
        return true
    }

    companion object {
        /** After Accept; a first picture later than this is as unusable for the join metric as none. */
        const val BOUND_MS = 15_000L

        /** How often the shown view is sampled while the picture is still black. */
        const val SAMPLE_INTERVAL_MS = 200L
    }
}

/** The process's call timeline (one call at a time) and the cheap black-picture test for the first shown frame. */
internal object CallJoinStages {
    val current = CallJoinTimeline(BuildConfig.DEBUG, SystemClock::elapsedRealtime) { line -> Log.i("BluetoothVideo", line) }

    val nonBlack = FirstNonBlackWatch(current, SystemClock::elapsedRealtime)

    fun begin(role: String) {
        nonBlack.reset()
        current.begin(role)
    }

    fun mark(stage: CallJoinStage, detail: String = "") = current.mark(stage, detail)

    fun noteTap(source: String = "user") = current.noteTap(source)

    /**
     * Mean Rec. 601 luma (0-255) of ARGB pixels, read from a tiny scaled copy of the shown picture. Null for no pixels.
     */
    fun meanLuma(argb: IntArray): Double? {
        if (argb.isEmpty()) return null
        var sum = 0.0
        for (pixel in argb) {
            val r = pixel shr 16 and 0xff
            val g = pixel shr 8 and 0xff
            val b = pixel and 0xff
            sum += 0.299 * r + 0.587 * g + 0.114 * b
        }
        return sum / argb.size
    }

    /** The first shown picture is black when its luma mean is under the encoder-side BLACK limit ([FirstFrameSanity]). */
    fun black(meanLuma: Double): Boolean = meanLuma < BLACK_LUMA_MAX

    private const val BLACK_LUMA_MAX = 3.0
}
