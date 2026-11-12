package com.example.bluetoothtest

/**
 * A3 step 9: one decision for a carrier whose reader, writer, supervisor or silence rule failed, as a pure table.
 *
 * - A quiet renewal ending (the old link the renewal already replaced) continues the call quietly: it is not a failure.
 * - A failure with another carrier left (or with a carrier-death end held for a running renewal) continues on that carrier.
 * - A failure with nothing left ends the call, through `CarrierSet.Host.onEndSession` and `onCallLinkError`.
 */
internal object CarrierLossPolicy {
    enum class Action { CONTINUE_QUIETLY, CONTINUE_ON_OTHER_CARRIER, END_CALL }

    fun action(quietRenewal: Boolean, nothingLeft: Boolean): Action = when {
        quietRenewal -> Action.CONTINUE_QUIETLY
        nothingLeft -> Action.END_CALL
        else -> Action.CONTINUE_ON_OTHER_CARRIER
    }

    /**
     * Fan-out (A3 step 9 item 4): the Bluetooth carrier's silence verdict, the same rule the producer applies to the one
     * link before fan-out (`CallStallTolerance`), unless a running renewal owns carrier 0's liveness.
     */
    fun bluetoothSilent(
        tolerant: Boolean,
        nowNs: Long,
        lastHeardNs: Long,
        lastSendProgressNs: Long,
        renewalOwnsLiveness: Boolean,
    ): Boolean = !renewalOwnsLiveness &&
        CallStallTolerance.verdict(tolerant, nowNs, lastHeardNs, lastSendProgressNs) == CallStallTolerance.Verdict.END
}
