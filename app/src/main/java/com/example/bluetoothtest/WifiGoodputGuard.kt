package com.example.bluetoothtest

/**
 * AP9: the Aware goodput guard. A hop that carries on Wi-Fi is judged over 5 s windows against its requested media
 * rate; three consecutive windows below it (with media offered in each) return the hop to Bluetooth through the
 * existing quality fallback. A good window or a window without offered media resets the count. Pure: bytes and the
 * clock are passed in, so the agent and the JVM tests share the rule.
 */
internal class WifiGoodputGuard(
    private val windowMs: Long = WifiAttemptLimits.GOODPUT_WINDOW_MS,
    private val windowsToFallBack: Int = WifiAttemptLimits.GOODPUT_LOW_WINDOWS,
) {
    enum class Verdict { OK, LOW, FALL_BACK }

    private var windowStartMs = -1L
    private var windowStartBytes = 0L
    private var mediaOfferedInWindow = false
    private var lowWindows = 0

    /** The last completed window's rate, for the log/diagnostics. */
    var lastWindowKbps = 0L
        private set

    /** Consecutive completed low windows. */
    val lowWindowCount: Int get() = lowWindows

    /** Completed windows since the last reset; the agent logs one line per completed window. */
    var completedWindows = 0
        private set

    /** Whether the last completed window had media offered (a window without it is never low). */
    var lastWindowOffered = false
        private set

    /**
     * [deliveredBytes] is the carrier's running total. [mediaOffered] says the send carrier had media queued during
     * this sample; it is OR-ed over the window. [requestedKbps] <= 0 (no requested media or a relay hop) is off.
     */
    fun onSample(nowMs: Long, deliveredBytes: Long, mediaOffered: Boolean, requestedKbps: Int): Verdict {
        if (requestedKbps <= 0) {
            reset()
            return Verdict.OK
        }
        if (windowStartMs < 0L) {
            windowStartMs = nowMs
            windowStartBytes = deliveredBytes
            mediaOfferedInWindow = mediaOffered
            lowWindows = 0
            lastWindowKbps = 0L
            return Verdict.OK
        }
        mediaOfferedInWindow = mediaOfferedInWindow || mediaOffered
        val elapsed = nowMs - windowStartMs
        if (elapsed < windowMs) return Verdict.OK
        lastWindowKbps = kbpsFor(deliveredBytes - windowStartBytes, elapsed)
        val offered = mediaOfferedInWindow
        completedWindows += 1
        lastWindowOffered = offered
        windowStartMs = nowMs
        windowStartBytes = deliveredBytes
        mediaOfferedInWindow = false
        if (!offered) {
            lowWindows = 0
            return Verdict.OK
        }
        if (lastWindowKbps >= requestedKbps) {
            lowWindows = 0
            return Verdict.OK
        }
        lowWindows += 1
        if (lowWindows >= windowsToFallBack) {
            lowWindows = 0
            return Verdict.FALL_BACK
        }
        return Verdict.LOW
    }

    fun reset() {
        windowStartMs = -1L
        windowStartBytes = 0L
        mediaOfferedInWindow = false
        lowWindows = 0
        lastWindowKbps = 0L
        completedWindows = 0
        lastWindowOffered = false
    }
}
