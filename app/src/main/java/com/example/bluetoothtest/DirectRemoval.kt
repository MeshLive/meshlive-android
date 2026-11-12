package com.example.bluetoothtest

/**
 * One removal rule for the close chain, both sweeps and the quarantine (A9 review P1-2, P2-1). Runs on the wifi looper.
 *
 * - The group is read three ways ([readGroup]): a null group read is never "gone" by itself; only
 *   `groupFormed == false` confirms absence.
 * - A readable group is removed only when [run]'s `isOurs` says so; another name is never removed (one group per
 *   phone, so ours is not on air).
 * - An unreadable group that `groupFormed` reports is removed only when `removeUnreadable` is set: this process created
 *   or joined it and its record matches. Otherwise nothing is removed and the outcome is [Outcome.Unknown].
 * - After `removeGroup` succeeds, absence is confirmed by a short poll ([DirectLimits.REMOVAL_POLL_MS] up to
 *   [DirectLimits.REMOVAL_CONFIRM_MS]): the platform clears its group only when the supplicant reports the removal, so
 *   an immediate read can still show it. A refused `removeGroup` is checked once.
 *
 * Every callback first checks [alive]; a timed-out owner stops the chain by making it false.
 */
internal class DirectRemoval(
    private val port: P2pPort,
    private val scheduler: DirectGroup.Scheduler,
    private val alive: () -> Boolean,
    private val track: (DirectGroup.Cancelable) -> Unit = {},
) {
    sealed class Outcome {
        /** `groupFormed == false`: no group at all. */
        object Absent : Outcome()

        /** Another readable group is up, so ours is not on air; it was not touched. */
        data class Other(val name: String?) : Outcome()

        /** Our group was removed and its absence confirmed; [name] null = removed while unreadable. */
        data class Removed(val name: String?) : Outcome()

        /** Removal was issued but the group was still reported at the end of the confirm window. */
        data class Unconfirmed(val name: String?) : Outcome()

        /** Nothing confirmed and nothing removed (an unreadable group, or a name the record cannot tell). */
        object Unknown : Outcome()
    }

    /**
     * [isOurs]: true / false for a readable name; null = cannot tell (the record is unreadable), which keeps the group.
     * [onRemoving] runs once just before `removeGroup` with the name being removed (null when unreadable).
     */
    fun run(
        isOurs: (String) -> Boolean?,
        removeUnreadable: Boolean,
        onRemoving: (String?) -> Unit = {},
        done: (Outcome) -> Unit,
    ) {
        port.readGroup { read ->
            if (!alive()) return@readGroup
            when (read) {
                GroupRead.Absent -> done(Outcome.Absent)
                is GroupRead.Present -> {
                    val name = read.group.networkName
                    when (if (name == null) false else isOurs(name)) {
                        true -> remove(name, isOurs, onRemoving, done)
                        false -> done(Outcome.Other(name))
                        null -> done(Outcome.Unknown)
                    }
                }
                is GroupRead.Unknown ->
                    if (read.groupFormed && removeUnreadable) remove(null, isOurs, onRemoving, done) else done(Outcome.Unknown)
            }
        }
    }

    private fun remove(name: String?, isOurs: (String) -> Boolean?, onRemoving: (String?) -> Unit, done: (Outcome) -> Unit) {
        onRemoving(name)
        port.removeGroup { result ->
            if (!alive()) return@removeGroup
            val reads = if (result == P2pResult.Success) 1 + (DirectLimits.REMOVAL_CONFIRM_MS / DirectLimits.REMOVAL_POLL_MS).toInt() else 1
            confirm(name, isOurs, reads, done)
        }
    }

    private fun confirm(name: String?, isOurs: (String) -> Boolean?, readsLeft: Int, done: (Outcome) -> Unit) {
        port.readGroup { read ->
            if (!alive()) return@readGroup
            val gone = when (read) {
                GroupRead.Absent -> true
                is GroupRead.Present -> read.group.networkName.let { it == null || (it != name && isOurs(it) == false) }
                is GroupRead.Unknown -> false
            }
            when {
                gone -> done(Outcome.Removed(name))
                readsLeft > 1 -> track(scheduler.post(DirectLimits.REMOVAL_POLL_MS) { if (alive()) confirm(name, isOurs, readsLeft - 1, done) })
                else -> done(Outcome.Unconfirmed(name))
            }
        }
    }
}
