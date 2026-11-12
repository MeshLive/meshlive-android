package com.example.bluetoothtest

import java.util.concurrent.atomic.AtomicReference

/**
 * One stream parent at a time, for both radios (F-7): a worker wins the token before it touches shared state, and
 * only the token owner resets the media flags, calls `onStreamActive(false)` or closes the decoders. Ungated it hands
 * out a shared [UNGATED] token and never blocks, so with Wider reach off every Bluetooth path behaves as today (X4).
 * [AM 16]
 *
 * A join's end is part of the same state (A8 review P2-5): the Bluetooth worker whose join wait ran out ends the join
 * with [endJoinIfUnowned], which succeeds only while nobody owns it, and after which no worker can claim it. So a Wi-Fi
 * parent can never start on a join whose receiver is already releasing its surface.
 */
internal class ParentOwnership {
    @Volatile var gated: Boolean = false
        private set

    private val ref = AtomicReference<Any?>(null)

    /** A new join (or receiver run): sets [gated] and reopens an ended join. A live owner's token is never freed here. */
    fun beginJoin(gated: Boolean) {
        this.gated = gated
        ref.compareAndSet(ENDED, null)
    }

    /** The claim token, or null while another worker owns it or the join has ended. A fresh token per claim, so a release of an old token cannot free a newer owner. */
    fun claim(): Any? {
        if (!gated) return UNGATED
        val token = Any()
        return if (ref.compareAndSet(null, token)) token else null
    }

    /** Releases [token]; a token that is not the owner (or [UNGATED]) is ignored. */
    fun release(token: Any) {
        if (token !== UNGATED) ref.compareAndSet(token, null)
    }

    /**
     * Ends the join when nobody owns it: true when it ended now (or already had), false while a parent owns it (keep
     * waiting). Atomic with [claim]. Ungated nothing is shared, so the caller's own join wait decides (true).
     */
    fun endJoinIfUnowned(): Boolean {
        if (!gated) return true
        return ref.compareAndSet(null, ENDED) || ref.get() === ENDED
    }

    /** A worker owns the join now. */
    fun owned(): Boolean = ref.get().let { it != null && it !== ENDED }

    companion object {
        val UNGATED = Any()
        private val ENDED = Any()
    }
}
