package com.example.bluetoothtest

/**
 * The decision of the Android 10-11 entry `android11-gatt-server-connection-untracked` ([GattServerConnectionWatch]),
 * kept free of Android calls: a client that connected to the advertising call listener must get a PHY answer within
 * [timeoutMs]; without one (and with no link admitted meanwhile) the stack lost the connection's server record and the
 * listener must cancel it and register again. One watched connection at a time; a newer connect replaces the older.
 */
internal class ServerConnectionWatch(
    val timeoutMs: Long,
    private val clock: () -> Long,
    /**
     * Stage 2 ([GattServerFirstWriteWatch]): after the client subscribed, its first data write must arrive within this
     * (healthy first writes come 70-140 ms after the subscribe). Null: stage 2 off.
     */
    val firstWriteTimeoutMs: Long? = null,
) {
    private var watched: String? = null
    private var armedAtMs = 0L
    private var subscribed: String? = null
    private var subscribedAtMs = 0L

    /** A CONNECTED callback. Watched only on the advertising listener and while no link is admitted. True when armed. */
    @Synchronized
    fun onConnected(address: String, advertisingListener: Boolean, linkAdmitted: Boolean): Boolean {
        if (!advertisingListener || linkAdmitted) return false
        watched = address
        armedAtMs = clock()
        return true
    }

    /** A PHY read or update answer: the stack knows this connection. */
    @Synchronized
    fun onPhyAnswer(address: String) {
        if (watched.equals(address, ignoreCase = true)) watched = null
    }

    /** The connection ended: nothing to watch, in either stage. */
    @Synchronized
    fun onDisconnected(address: String) {
        onPhyAnswer(address)
        if (subscribed.equals(address, ignoreCase = true)) subscribed = null
    }

    /** Stage 2: the advertising listener accepted the client's subscribe. True when the first-write watch armed. */
    @Synchronized
    fun onSubscribed(address: String, advertisingListener: Boolean): Boolean {
        if (firstWriteTimeoutMs == null || !advertisingListener) return false
        subscribed = address
        subscribedAtMs = clock()
        return true
    }

    /** Stage 2: a valid data write from [address] reached the app: the stack delivers this connection's writes. */
    @Synchronized
    fun onDataReceived(address: String) {
        if (subscribed.equals(address, ignoreCase = true)) subscribed = null
    }

    /**
     * Stage 2, called when its time is up: the subscribed address to cancel and register again for, once
     * [firstWriteTimeoutMs] passed with no data. Never when [dataReceived] (a link that has received data is never cut).
     */
    @Synchronized
    fun firstWriteExpired(dataReceived: Boolean): String? {
        val address = subscribed ?: return null
        val timeout = firstWriteTimeoutMs ?: return null
        if (clock() - subscribedAtMs < timeout) return null
        subscribed = null
        return if (dataReceived) null else address
    }

    /**
     * Called when the watch's time is up. The address to cancel and register again for, once [timeoutMs] passed with no
     * answer and nothing admitted (the watch then clears), else null. Never cuts an admitted link.
     */
    @Synchronized
    fun expired(linkAdmitted: Boolean): String? {
        val address = watched ?: return null
        if (clock() - armedAtMs < timeoutMs) return null
        watched = null
        return if (linkAdmitted) null else address
    }

    @Synchronized
    fun watching(): String? = watched
}

/**
 * AP7 F1 (`test_gatt_first_write_counts_control`): a well-formed `GR1` `Hello` or `High` is a control write the peer
 * actually delivered to this server. A data chunk, an empty value, a malformed type-2 value, a `Nack` and a `Gone` are
 * not. `Gr1.decode` rejects anything else, so a malformed value cannot silence the watch.
 */
internal fun recoveryControlDelivered(value: ByteArray): Boolean =
    value.isNotEmpty() && value[0] == Gr1.TYPE &&
        Gr1.decode(value).let { it is Gr1.Message.Hello || it is Gr1.Message.High }

/** AP7 F1: [recoveryControlDelivered] behind the Experimental switch; false while the switch is off. */
internal fun controlCountsAsFirstWrite(enabled: Boolean, value: ByteArray): Boolean =
    enabled && recoveryControlDelivered(value)

/**
 * Keep-address part of the same entry ([GattServerReregisterKeepsAddress]): what a re-registration does with the call
 * listener's advertising set. Re-registering only replaces the GATT server; the set is a separate stack object, so a set
 * that is still running keeps advertising (same set, same address) and the client's retry finds it at once instead of
 * waiting for a new set's advert (A15s 2026-10-20: the retry came after a rediscovery).
 */
internal object ReregisterAdvertising {
    enum class Action {
        /** The running set stays as it is (same address); the new server only registers and adds its service. */
        KEEP_SET,

        /** The old behaviour (keep-address off): stop the running set before re-registering and start a new one after. */
        REPLACE_SET,

        /** Nothing runs: start a set after re-registering (a new address, as before). */
        START_SET,

        /** A rotating-ID silence is running: the new ID's set starts when the silence ends ([GattVideoServer.reannounce]). */
        LEAVE_TO_REANNOUNCE,
    }

    fun decide(keepAddress: Boolean, setRunning: Boolean, epochSilence: Boolean): Action = when {
        setRunning && keepAddress -> Action.KEEP_SET
        setRunning -> Action.REPLACE_SET
        epochSilence -> Action.LEAVE_TO_REANNOUNCE
        else -> Action.START_SET
    }
}

/**
 * Rotating IDs (RV6) and a kept set: an epoch change stops whatever set runs, whether or not a link is admitted (a set
 * kept on through an admission belongs to the old ID and its address must never carry into the new epoch). Only a phone
 * with nothing admitted advertises the new ID after the silence; one with an admitted link advertises again when it ends.
 */
internal object ReannounceStep {
    data class Plan(val stopRunningSet: Boolean, val restartAfterSilence: Boolean)

    fun plan(advertising: Boolean, linkAdmitted: Boolean): Plan =
        Plan(stopRunningSet = advertising, restartAfterSilence = advertising && !linkAdmitted)
}

/**
 * Stage 2 with keep-address: the call listener used to stop its advertising set as soon as it admitted a subscribe, so a
 * re-registration for a missing first write had to start a new set (a new address). With [hold] the set stays on until
 * the admitted client's first data write arrives (70-140 ms when healthy, at most the 1.2 s watch) and stops then, so
 * a re-registration finds the set running and keeps it. Exactly one stop per held admission; a link that ends while held
 * leaves the set advertising for the next caller.
 */
internal class AdmissionAdvertisingHold {
    private var held: String? = null

    /** A subscribe was admitted. True: stop advertising now (the earlier behaviour); false: the set is held. */
    @Synchronized
    fun onAdmitted(address: String, hold: Boolean): Boolean {
        held = if (hold) address else null
        return !hold
    }

    /** A valid data write from [address]: true once, when its held set must stop now. */
    @Synchronized
    fun onFirstWrite(address: String): Boolean = release(address)

    /** The first-write watch ended without a re-registration (or could not proceed): true when the held set must stop. */
    @Synchronized
    fun onWatchEnded(address: String): Boolean = release(address)

    /** The held link ended (disconnect, or a re-registration that keeps the set): the set keeps advertising. */
    @Synchronized
    fun onLinkEnded(address: String) {
        if (held.equals(address, ignoreCase = true)) held = null
    }

    /** The set was stopped for another reason (a new rotating-ID epoch, close): nothing is held. */
    @Synchronized
    fun clear() {
        held = null
    }

    @Synchronized
    fun holding(): String? = held

    private fun release(address: String): Boolean {
        if (!held.equals(address, ignoreCase = true)) return false
        held = null
        return true
    }
}
