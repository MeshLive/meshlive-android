package com.example.bluetoothtest

import java.net.Inet4Address
import java.net.InetAddress

/** A9's pure Wi-Fi Direct limits. No Android types: every value is usable from a JVM test. */
internal object DirectLimits {
    const val GROUP_READY_MS = WifiLimits.DATA_PATH_TIMEOUT_MS // createGroup/connect until the group is usable (12_000L) [R S19]
    const val ADDRESS_MS = WifiLimits.ADDRESS_WAIT_MS // group interface address after the group formed (5_000L) [R S19]
    const val FIRST_POLL_MS = 300L // first requestGroupInfo after createGroup success
    const val POLL_MS = 500L
    const val BUSY_RETRIES = 2 // retries after P2P BUSY, delays 1 s then 2 s
    const val CLEANUP_WATCHDOG_MS = 5_000L // upper bound for the removeGroup/closeChannel chain
    const val REMOVAL_POLL_MS = 200L // after removeGroup succeeds, absence is re-read this often (A9 review P2-1)
    const val REMOVAL_CONFIRM_MS = 2_000L // and for at most this long, inside CLEANUP_WATCHDOG_MS
    const val DEVICE_NAME_TIMEOUT_MS = 3_000L
    const val DEVICE_NAME_CACHE_MS = 10 * 60 * 1000L
    const val RECORD_MAX_NAMES = 8
    const val HOST_5GHZ_MAX_MHZ = 5_895 // upper end of the 5 GHz band for hostFrequency
}

/** Why a Wi-Fi Direct attempt ended. [CREDS_OFF_LINK] and [SUBNET_OVERLAP] are F-4. */
internal enum class DirectFailure {
    BUSY,
    NO_PERMISSION,
    UNSUPPORTED,
    PLATFORM_ERROR,
    BAD_CONFIG,
    FOREIGN_GROUP,
    BAND_24,
    NO_ADDRESS,
    TIMEOUT,
    RECORD_UNAVAILABLE,
    LOST,
    CREDS_OFF_LINK,
    SUBNET_OVERLAP,
}

/** A9's pure policy: failure classification, band and address rules. */
internal object DirectPolicy {
    /**
     * Windows host, Android guest (master 9.2). The reviewer sets true only after W0 check (a) passed together with the
     * readable group frequency of W0 check (e). While false, [mayJoin] refuses a peer that offers only the group-owner
     * bit (a Windows PC). Owned here, not in A0 [X12].
     */
    const val WINDOWS_HOST_JOIN_PROVEN = false

    /** P2P failure code -> (what failed, retry delay or null). BUSY retries up to [DirectLimits.BUSY_RETRIES] with 1000 ms shl n. */
    fun classify(reason: Int, busyRetries: Int): Pair<DirectFailure, Long?> = when (reason) {
        OfflineAppSharePolicy.P2P_ERROR -> DirectFailure.PLATFORM_ERROR to null
        OfflineAppSharePolicy.P2P_UNSUPPORTED -> DirectFailure.UNSUPPORTED to null
        OfflineAppSharePolicy.P2P_BUSY ->
            if (busyRetries < DirectLimits.BUSY_RETRIES) {
                DirectFailure.BUSY to (1_000L shl busyRetries)
            } else {
                DirectFailure.BUSY to null
            }
        3 -> DirectFailure.PLATFORM_ERROR to null // NO_SERVICE_REQUESTS
        4 -> DirectFailure.NO_PERMISSION to null
        else -> DirectFailure.PLATFORM_ERROR to null
    }

    /** Only failures with no peer involved count in the phone-wide local-fault ledger (F-2) [AM 6]. */
    fun countsAsLocalFault(failure: DirectFailure): Boolean = when (failure) {
        DirectFailure.PLATFORM_ERROR, DirectFailure.UNSUPPORTED, DirectFailure.BAD_CONFIG -> true
        else -> false
    }

    /** DECLINE reason sent to the peer; wire values 1-7 (A0 owns the numbers). */
    fun declineFor(failure: DirectFailure): WifiEligibility.DeclineReason = when (failure) {
        DirectFailure.BUSY, DirectFailure.FOREIGN_GROUP -> WifiEligibility.DeclineReason.BUSY
        DirectFailure.NO_PERMISSION, DirectFailure.UNSUPPORTED -> WifiEligibility.DeclineReason.NOT_ELIGIBLE
        DirectFailure.BAND_24 -> WifiEligibility.DeclineReason.NOT_5GHZ
        DirectFailure.SUBNET_OVERLAP -> WifiEligibility.DeclineReason.WIFI_UNAVAILABLE
        DirectFailure.PLATFORM_ERROR,
        DirectFailure.BAD_CONFIG,
        DirectFailure.NO_ADDRESS,
        DirectFailure.TIMEOUT,
        DirectFailure.RECORD_UNAVAILABLE,
        DirectFailure.LOST,
        DirectFailure.CREDS_OFF_LINK,
        -> WifiEligibility.DeclineReason.SETUP_FAILED
    }

    fun validNames(ssid: String, psk: String): Boolean =
        OfflineAppSharePolicy.validNetworkName(ssid) && OfflineAppSharePolicy.validPassphrase(psk)

    /** The group frequency must be 5 GHz or higher; 0 (unknown) fails. */
    fun bandOk(frequencyMhz: Int): Boolean = frequencyMhz >= WifiLimits.MIN_5GHZ_MHZ

    /** Host: the phone's Wi-Fi client frequency when it is on 5 GHz outside the DFS range, else 0 (= band 5 GHz, no fixed frequency) [AM 16]. */
    fun hostFrequency(staFrequencyMhz: Int?): Int =
        if (staFrequencyMhz != null &&
            staFrequencyMhz in WifiLimits.MIN_5GHZ_MHZ..DirectLimits.HOST_5GHZ_MAX_MHZ &&
            staFrequencyMhz !in 5_250..5_730
        ) {
            staFrequencyMhz
        } else {
            0
        }

    /** Called by A5's WifiHopAgent.planRoles when the tuple is DIRECT and this phone is the guest. */
    fun mayJoin(peer: Bwf.Caps): Boolean = peer.directClient || WINDOWS_HOST_JOIN_PROVEN

    /**
     * Root amendment (c): on API 29-32 `WifiP2pManager.createGroup` and `connect` fail with ERROR(0) from a process
     * without a visible activity ("while in use"). API 33+ needs no such check.
     */
    fun p2pForegroundOk(api: Int, appVisible: Boolean, locationServiceHeld: Boolean): Boolean =
        api >= 33 || appVisible || locationServiceHeld
}

/** A9's pure address rules: the group interface, the accepted remote and the CREDS sanity checks. */
internal object DirectAddressRules {
    data class LocalAddress(val interfaceName: String, val address: Inet4Address, val prefixLength: Int)

    /** Host: the group interface's IPv4, named by requestGroupInfo. A null name means "not reported yet". */
    fun hostAddress(candidates: List<LocalAddress>, groupInterface: String?): LocalAddress? {
        groupInterface ?: return null
        return candidates.firstOrNull { it.interfaceName == groupInterface }
    }

    /** Host accept filter: the remote must be in the group subnet and must not be the local address. */
    fun acceptRemote(local: LocalAddress, remote: InetAddress): Boolean {
        val v4 = remote as? Inet4Address ?: return false
        if (v4 == local.address) return false
        return OfflineAppSharePolicy.sameSubnet(local.address, local.prefixLength, v4)
    }

    /**
     * Guest "joined" detection: the candidate on the interface named [groupInterface] when given; otherwise the first
     * candidate whose interface name contains "p2p" and is not in [interfacesBefore]. null = not joined yet.
     */
    fun groupAddress(candidates: List<LocalAddress>, groupInterface: String?, interfacesBefore: Set<String>): LocalAddress? {
        if (groupInterface != null) return candidates.firstOrNull { it.interfaceName == groupInterface }
        return candidates.firstOrNull { it.interfaceName.contains("p2p") && it.interfaceName !in interfacesBefore }
    }

    /** True when [host] lies inside the pinned joined interface's subnet and is not its own address (F-4). */
    fun credsOnLink(joined: LocalAddress, host: Inet4Address): Boolean =
        host != joined.address && OfflineAppSharePolicy.sameSubnet(joined.address, joined.prefixLength, host)

    /**
     * True when any address on another interface has the same network as [joined] at the shorter of the two prefixes
     * (routing could leave the group interface) [AM 7].
     */
    fun subnetOverlap(joined: LocalAddress, all: List<LocalAddress>): Boolean =
        all.any { other -> other.interfaceName != joined.interfaceName && sameNetwork(joined, other) }

    /** CREDS sanity: a private IPv4 (10/8, 172.16/12, 192.168/16), never 0, loopback, link-local or multicast. */
    fun credsAddressOk(ipv4: ByteArray): Boolean {
        if (ipv4.size != 4) return false
        val first = ipv4[0].toInt() and 0xff
        val second = ipv4[1].toInt() and 0xff
        return when {
            first == 10 -> true
            first == 172 && second in 16..31 -> true
            first == 192 && second == 168 -> true
            else -> false
        }
    }

    private fun sameNetwork(a: LocalAddress, b: LocalAddress): Boolean {
        val bits = minOf(a.prefixLength, b.prefixLength)
        if (bits < 0 || bits > 32) return false
        var remaining = bits
        for (i in 0 until 4) {
            if (remaining <= 0) return true
            val mask = if (remaining >= 8) 0xff else (0xff shl (8 - remaining)) and 0xff
            if ((a.address.address[i].toInt() and mask) != (b.address.address[i].toInt() and mask)) return false
            remaining -= 8
        }
        return true
    }
}
