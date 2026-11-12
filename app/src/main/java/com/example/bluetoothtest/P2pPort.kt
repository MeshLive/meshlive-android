package com.example.bluetoothtest

import java.net.Inet4Address

/** The result of a WifiP2pManager action. */
internal sealed class P2pResult {
    object Success : P2pResult()
    data class Failure(val reason: Int) : P2pResult()
    object Denied : P2pResult() // SecurityException
    object BadConfig : P2pResult() // IllegalArgumentException / UnsupportedOperationException / IllegalStateException from the Builder

    /** Root note (c): API 29-32 and MeshLive not visible; refused before any platform call (local BUSY, no retry). */
    object NotVisible : P2pResult()
}

/** A group as WifiP2pManager reports it; [frequencyMhz] 0 = unreadable. */
internal data class P2pGroup(val networkName: String?, val isOwner: Boolean, val interfaceName: String?, val frequencyMhz: Int)

/** Connection info; [ownerAddress] uses value equality (F-4 compares it with the CREDS host). */
internal data class P2pConnection(val groupFormed: Boolean, val isOwner: Boolean, val ownerAddress: Inet4Address?)

/**
 * The three-way group read (A9 review P1-2). `requestGroupInfo` answers null both for "no group" and for a refused
 * read (below API 33 the Wi-Fi Direct location check: background, Location off; a revoked permission; no channel; an
 * error), so a null read is never "gone" on its own. Absence is confirmed only by `requestConnectionInfo().groupFormed
 * == false`, which the platform answers without a permission or location check.
 */
internal sealed class GroupRead {
    data class Present(val group: P2pGroup) : GroupRead()

    /** groupFormed == false: no group on this phone (confirmed absence). */
    object Absent : GroupRead()

    /** No readable group; [groupFormed] true = a group exists but its name could not be read. */
    data class Unknown(val groupFormed: Boolean) : GroupRead()
}

/** Reads the group, then (only when the group read gave nothing) the connection info. */
internal fun P2pPort.readGroup(done: (GroupRead) -> Unit) {
    requestGroup { group ->
        if (group != null) {
            done(GroupRead.Present(group))
        } else {
            requestConnection { connection ->
                done(
                    when {
                        connection == null -> GroupRead.Unknown(groupFormed = false)
                        !connection.groupFormed -> GroupRead.Absent
                        else -> GroupRead.Unknown(groupFormed = true)
                    },
                )
            }
        }
    }
}

/**
 * The WifiP2pManager surface behind a small interface, so the group state machine stays JVM-testable. Every callback
 * arrives on the `meshlive-wifi` looper the channel was opened with.
 */
internal interface P2pPort {
    /** Opens the channel on the meshlive-wifi looper. false: no WifiP2pManager or initialize returned null. */
    fun open(onChannelLost: () -> Unit): Boolean

    /** >= WifiLimits.MIN_5GHZ_MHZ: setGroupOperatingFrequency; 0: setGroupOperatingBand(GROUP_OWNER_BAND_5GHZ) [R M-11]. */
    fun createGroup(ssid: String, psk: String, frequencyMhz: Int, done: (P2pResult) -> Unit)

    /** frequencyMhz 0: no frequency, no band. */
    fun connect(ssid: String, psk: String, frequencyMhz: Int, done: (P2pResult) -> Unit)

    fun requestGroup(done: (P2pGroup?) -> Unit)
    fun requestConnection(done: (P2pConnection?) -> Unit)
    fun removeGroup(done: (P2pResult) -> Unit)
    fun requestDeviceName(done: (String?) -> Unit)
    fun closeChannel() // idempotent

    /** NetworkInterface scan, IPv4 only. */
    fun localAddresses(): List<DirectAddressRules.LocalAddress>
}
