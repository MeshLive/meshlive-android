package com.example.bluetoothtest

/**
 * A7 (master 4.3): the cutover sequence of one hop record. Pure functions of the wire bytes; never interprets an
 * end-to-end content type (a relay forwards content types it does not know).
 */
internal object HopSequence {
    /** The machine-governed sequence of a record, or null when the record is not machine-governed (hop control, handshake, heartbeat). */
    fun of(type: SecureRecordType, payload: ByteArray): Long? = when (type) {
        SecureRecordType.MESH_ENVELOPE -> MeshEnvelopeCodec.sequenceOf(payload)
        SecureRecordType.END_TO_END_CONTENT -> EndToEndContentProtocol.sequenceOf(payload)   // A7 Am 12: never interprets the type byte
        else -> null
    }
}

/** May this hop ever carry `BWF1`? A7. */
internal object HopWifiGate {
    /**
     * OFF: no `BWF1` at all (this side's reader does not accept it). PASSIVE: `BWF1` allowed, local switches off:
     * answer `CAPS` all-clear, never attempt. ACTIVE: may attempt.
     */
    enum class Mode { OFF, PASSIVE, ACTIVE }

    /** [readOpen] = `session.wifiReadOptIn`; [hopCarrier0IsBluetooth] false on A8's Wi-Fi-native hops (stay OFF). */
    fun mode(readOpen: Boolean, anyLocalSwitchOn: Boolean, hopCarrier0IsBluetooth: Boolean): Mode = when {
        !readOpen || !hopCarrier0IsBluetooth -> Mode.OFF
        anyLocalSwitchOn -> Mode.ACTIVE
        else -> Mode.PASSIVE
    }
}

/**
 * When this side sends its `CAPS` and when an attempt may start (R-1; A7 Am 2). One instance per hop; no state in a
 * companion. Every member that reads or writes [sentOwn] or [peer] is `@Synchronized`.
 */
internal class HopCapsPlan(
    private val role: HopRole,
    private val mode: HopWifiGate.Mode,
    private val peerProvenCapable: Boolean,
    private val bmh2TagGate: Boolean = false,        // session.wifiBuildTagGate: true only on a BMH2 link whose two tags opened the gate
    private val neighbourIsRelay: Boolean = false,   // RELAY_DOWNSTREAM only: the onward neighbour is another relay (A7 Am 2)
) {
    enum class HopRole { STREAM_PARENT, STREAM_CHILD, RELAY_UPSTREAM, RELAY_DOWNSTREAM }

    /** The tie-break argument of `WifiRoles.choose` (localIsLinkInitiator) and of `WifiHopPorts.isLinkInitiator`; NOT "who waits". */
    val localIsInitiator: Boolean = role == HopRole.STREAM_CHILD || role == HopRole.RELAY_DOWNSTREAM

    @get:Synchronized
    var sentOwn = false
        private set

    @get:Synchronized
    var peer: Bwf.Caps? = null
        private set

    private var chose = false

    /**
     * True exactly once, when this side should write its `CAPS` now. Rules in this order, first match wins:
     * (a) OFF false; (b) already sent false; (c) the peer's `CAPS` arrived: answer; (d) peer not proven to read
     * `BWF1`: wait for its `CAPS`; (e) stream child after the first header; (f) stream parent never first;
     * (g) relay upstream (BMH3 responder) after forwarding content; (h) relay downstream (BMH2 relay-to-relay)
     * after forwarding content.
     */
    @Synchronized
    fun shouldSendOwn(firstHeaderSeen: Boolean, contentForwarded: Boolean, peerCapsSeen: Boolean): Boolean {
        if (mode == HopWifiGate.Mode.OFF) return false
        if (sentOwn) return false
        if (peerCapsSeen) {
            sentOwn = true
            return true
        }
        if (!peerProvenCapable) return false
        val send = when (role) {
            HopRole.STREAM_CHILD -> firstHeaderSeen
            HopRole.STREAM_PARENT -> false
            HopRole.RELAY_UPSTREAM -> !bmh2TagGate && contentForwarded
            HopRole.RELAY_DOWNSTREAM -> bmh2TagGate && neighbourIsRelay && contentForwarded
        }
        if (send) sentOwn = true
        return send
    }

    /** False for a repeat (the caller logs `bwf1_unexpected`); a second `CAPS` on a hop is never used. */
    @Synchronized
    fun onPeerCaps(caps: Bwf.Caps): Boolean {
        if (peer != null) return false
        peer = caps
        return true
    }

    /** Non-null exactly once, when both `CAPS` are known and an attempt is feasible. The activation trigger only. */
    @Synchronized
    fun attemptChoice(own: Bwf.Caps): RoleTuple? {
        if (mode != HopWifiGate.Mode.ACTIVE || !sentOwn || chose) return null
        val peerCaps = peer ?: return null
        val tuple = WifiRoles.choose(own, peerCaps, localIsInitiator) ?: return null
        chose = true
        return tuple
    }
}

/** Which record types a hop's `CarrierSet` governs (orders by sequence) and which pass in arrival order (A7). */
internal object HopRecordTypes {
    /** `CarrierSet.contentTypes` of a role: the types the machine orders by [HopSequence]. */
    fun content(role: HopCapsPlan.HopRole): Set<SecureRecordType> = when (role) {
        HopCapsPlan.HopRole.STREAM_PARENT, HopCapsPlan.HopRole.STREAM_CHILD ->
            setOf(SecureRecordType.MESH_ENVELOPE, SecureRecordType.END_TO_END_CONTENT)
        HopCapsPlan.HopRole.RELAY_UPSTREAM, HopCapsPlan.HopRole.RELAY_DOWNSTREAM ->
            setOf(SecureRecordType.END_TO_END_CONTENT)
    }

    /** `CarrierSet.passTypes`: hop records that bypass the machine and stay ordered on the consumer path (`Host.onHopRecord`). */
    val PASS: Set<SecureRecordType> =
        setOf(SecureRecordType.ROUTE_CONTROL, SecureRecordType.ROUTE_CONTROL_ACK, SecureRecordType.END_TO_END_HANDSHAKE)
}
