package com.example.bluetoothtest

import java.util.concurrent.CountDownLatch

/**
 * The static call relay's watch of its authenticated child (callee) link while it waits for its caller (restart guard,
 * [DebugTunables.relayedRestartGuard]). Nothing read the child before the caller joined, so a child that closed early
 * was held for up to 180 s (`PrivateCallRelayChain.PARENT_WAIT_MS`) and later joined to the caller, who then heard
 * nothing (rc-ctl-20261106 0002, 0011). This reader is the relay's callee-to-caller reader from the start: it reads
 * the child at once, drops link heartbeats until the caller joins (they only prove the link alive), holds the first
 * other record for the caller, and reports a failure before the join so the relay can stop and leave the air.
 * One thread reads the child, as before; record order is unchanged.
 */
internal class RelayChildWatch(private val child: SecureBluetoothSession) {
    sealed interface First {
        /** The child's first record that the caller must receive (forwarded first once the caller joined). */
        class Record(val record: SecureRecord) : First

        /** The child link failed after the caller joined: the ordinary forwarding path ends the call. */
        class Failed(val error: Exception) : First

        /** The child link failed while no caller was joined: the relay stops instead of holding a dead child. */
        class ClosedBeforeParent(val error: Exception) : First
    }

    private val parent = CountDownLatch(1)
    @Volatile private var abandoned = false

    /** The caller joined and forwarding may start. */
    fun parentJoined() = parent.countDown()

    /** The relay stopped before forwarding: a waiting [awaitParent] returns false. */
    fun abandon() {
        abandoned = true
        parent.countDown()
    }

    private val joined: Boolean get() = parent.count == 0L && !abandoned

    /** Blocks until the child sends a record the caller must receive, or its link fails. */
    fun readFirst(): First {
        while (true) {
            val record = try {
                child.reader.read()
            } catch (error: Exception) {
                return if (joined) First.Failed(error) else First.ClosedBeforeParent(error)
            }
            if (record.type == SecureRecordType.LINK_HEARTBEAT && !joined) continue
            return First.Record(record)
        }
    }

    /** Waits for the caller; false when the relay stopped first (or this thread was interrupted by its close). */
    fun awaitParent(): Boolean = try {
        parent.await()
        !abandoned
    } catch (_: InterruptedException) {
        false
    }
}
