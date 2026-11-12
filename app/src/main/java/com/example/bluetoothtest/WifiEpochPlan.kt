package com.example.bluetoothtest

internal object WifiEpochPlan {
    /** The Aware service name of [epoch] (A0 `WifiKeys.widerServiceName`); a different epoch must give a different name. */
    fun serviceName(epoch: Long): String = WifiKeys.widerServiceName(epoch)   // [API]

    sealed interface Action {
        object Ignore : Action
        /** Stop both sessions now, stay silent [silenceMs], restart under [toEpoch]. */
        data class Restart(val toEpoch: Long, val silenceMs: Long) : Action
        /** An admitted native link is live: restart when the last one closes or at the next boundary (forced), F-10 [AM 8]. */
        data class Defer(val toEpoch: Long) : Action
    }

    fun onBoundary(runningEpoch: Long?, boundaryEpoch: Long, admittedLinks: Int, alreadyDeferred: Boolean, silenceMs: Long): Action = when {
        runningEpoch == null || boundaryEpoch == runningEpoch -> Action.Ignore
        admittedLinks > 0 && !alreadyDeferred -> Action.Defer(boundaryEpoch)   // at most one epoch [AM 8]
        else -> Action.Restart(boundaryEpoch, silenceMs)
    }
}

/**
 * The epoch the discovery sessions run under and F-10's single deferral, as one state machine (A8 review P0-1).
 * [AwareDiscovery] feeds it the wall epoch, whether any session is open (or starting) and the admitted-link count, and
 * does exactly what the answer says. It never calls back into the plane, so a boundary and a reconcile can no longer
 * re-enter each other, and [running] (the service name of the open sessions) never moves while a restart is deferred.
 * Pure and single-threaded (the wifi thread); the JVM tests drive it directly.
 */
internal class WifiEpochTracker {
    /** The epoch of the open sessions, and of new ones; -1 before the first reconcile. */
    var running: Long = -1L
        private set

    /** The boundary whose restart waits for the last admitted native link; -1 = no deferral. */
    var deferred: Long = -1L
        private set

    sealed interface Step {
        /** Keep or start the sessions under [epoch]. */
        data class Run(val epoch: Long) : Step
        /** Close every session now (a forced stop), stay silent [silenceMs], then reconcile (under the new epoch). */
        data class Restart(val toEpoch: Long, val silenceMs: Long) : Step
        /** Nothing to do now: a deferral started, or the same deferral was seen again while a link lives. */
        object Hold : Step
        /** A boundary with nothing running under an older epoch: an ordinary reconcile. */
        object Reconcile : Step
    }

    /** A boundary callback for [boundaryEpoch]. */
    fun onBoundary(boundaryEpoch: Long, sessionsOpen: Boolean, admittedLinks: Int, silenceMs: Long): Step {
        if (deferred >= 0) {
            if (boundaryEpoch == deferred && admittedLinks > 0) return Step.Hold
            // The deferred boundary with no admitted link left, or a new boundary during the deferral (forced, F-10).
            return restart(boundaryEpoch, silenceMs)
        }
        val runningEpoch = running.takeIf { sessionsOpen && it >= 0 }
        return when (val action = WifiEpochPlan.onBoundary(runningEpoch, boundaryEpoch, admittedLinks, false, silenceMs)) {
            WifiEpochPlan.Action.Ignore -> Step.Reconcile
            is WifiEpochPlan.Action.Defer -> {
                deferred = action.toEpoch
                Step.Hold
            }
            is WifiEpochPlan.Action.Restart -> restart(action.toEpoch, action.silenceMs)
        }
    }

    /** Every reconcile, with the wall epoch [current]. Never answers [Step.Reconcile]. */
    fun onReconcile(current: Long, sessionsOpen: Boolean, admittedLinks: Int, silenceMs: Long): Step {
        if (deferred >= 0) {
            if (!sessionsOpen) {
                // The platform ended the kept sessions: nothing old is on air, so a new session takes the wall epoch.
                deferred = -1L
                running = current
                return Step.Run(current)
            }
            if (admittedLinks == 0) return restart(current, silenceMs)                          // the last admitted link closed
            if (current != deferred && current != running) return restart(current, silenceMs)   // a missed boundary: forced
            return Step.Run(running)                                                              // keep the running epoch
        }
        if (sessionsOpen && running >= 0 && current != running) {
            return when (val step = onBoundary(current, true, admittedLinks, silenceMs)) {
                Step.Hold -> Step.Run(running)
                else -> step
            }
        }
        running = current
        return Step.Run(current)
    }

    /** Every session is closed (a stop that kept nothing, or the restart's forced stop): the deferral, if any, is over. */
    fun onSessionsClosed() {
        deferred = -1L
    }

    private fun restart(toEpoch: Long, silenceMs: Long): Step {
        deferred = -1L
        return Step.Restart(toEpoch, silenceMs)
    }
}

/**
 * Defence in depth for X8 and F-10 (A8 review P1-2): the epoch under which each announcement key was first published.
 * A key first published under an earlier epoch is never published under a later epoch's service name, so a stale
 * rotating ID left in the registry cannot follow the phone from epoch to epoch. Pure; wifi thread only.
 */
internal class PublishedKeyEpochs(private val maxKeys: Int = 256) {
    private val firstEpoch = LinkedHashMap<String, Long>()

    /** True when [key] may go on air under [epoch]'s service name; records the first epoch of a key never seen. */
    fun allow(key: String, epoch: Long): Boolean {
        val first = firstEpoch[key]
        if (first == null) {
            firstEpoch[key] = epoch
            while (firstEpoch.size > maxKeys) firstEpoch.remove(firstEpoch.keys.first())
            return true
        }
        return epoch <= first
    }
}
