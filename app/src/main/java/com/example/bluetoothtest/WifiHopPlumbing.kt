package com.example.bluetoothtest

/**
 * Wi-Fi upgrade, hop plumbing constants and gates (PROTOCOL "Wi-Fi transport"). Pure Kotlin.
 */
internal object WifiHopPlumbing {
    /**
     * True only in a build whose call (and, from A7, hop) readers handle a type-16 record. A build that sends the BMH3
     * `WIFI` marker invites `CAPS` from a capable peer, so this stays false until the reader intercepts `BWF1`
     * (package A3 flips it in its last step, A3-10, after the interception exists) [R S13]. It also gates
     * [WifiHopGate.effectiveOwnTag] (step 5).
     */
    const val BUILD_HANDLES_BWF1 = true

    /** `UW_hop` of a hop, or null on any failure (A0 refuses equal keys). Never wipes or retains its inputs. */
    fun hopUwOrNull(keyA: ByteArray, keyB: ByteArray): ByteArray? =
        runCatching { WifiKeys.uwForHop(keyA, keyB) }.getOrNull()

    /** `UW` of a call from its BMH3 end-to-end exporter, or null on any failure. Never wipes its input. */
    fun callUwOrNull(exporter: ByteArray): ByteArray? =
        runCatching { WifiKeys.uwForCall(exporter) }.getOrNull()
}

/** Pure Kotlin except the two providers `switchSource` and `debugBuild`. */
internal object WifiHopGate {
    /**
     * BMH2 hops (master 5.2): both signed link hellos carry a build tag and both release numbers are at least
     * [WifiWire.WIFI_FIRST_RELEASE]. A missing tag on either side, or a lower number, is no `BWF1` on that hop, ever.
     * `own` is the tag this phone actually sent ([ownTagFor], amendment 3). The `>=` rule lives once, in A0. [API]
     */
    fun bmh2TagsAllow(own: BuildTag?, peer: BuildTag?): Boolean =
        WifiWire.buildTagAllowsBwf1(own) && WifiWire.buildTagAllowsBwf1(peer)

    /**
     * Is the PEER proven, by the handshake alone, to implement `BWF1`? True when both BMH2 hellos carried allowed tags
     * (both ends evaluate the same pair, so they agree) or when this side is a BMH3 responder that saw the `WIFI` marker.
     * False for a BMH3 initiator: it signed the marker, but an older responder ignores that non-critical extension, so
     * only a `BWF1` record from the peer proves it. Necessary, not sufficient, for sending `CAPS` first: A5/A7 also apply
     * R-1's timing and send `BWF1` only on a session whose own reader was opened with `acceptWifi` before the peer can send
     * (X4). `session.wifiReadOptIn` is the different question "may my reader accept `BWF1`?". [GAP+][R M-4]
     */
    fun peerCapable(session: SecureBluetoothSession): Boolean = session.wifiBuildTagGate || session.peerWifiMarker

    /**
     * R-1 at the write (review A3 P2-5): this side may write a `BWF1` record on [session]'s hop only when its own reader
     * was opened to `BWF1` and the peer is proven to read it, by the handshake ([peerCapable]) or, for a BMH3 initiator,
     * by an authenticated `BWF1` record the peer already sent ([peerSentBwf1]).
     */
    fun mayWriteBwf1(session: SecureBluetoothSession, peerSentBwf1: Boolean): Boolean =
        session.reader.acceptWifi && (peerCapable(session) || peerSentBwf1)

    /** Live switch source; package A6 sets it once at app start (contract in item 5). Must not block. */
    @Volatile var switchSource: () -> WifiSwitches = { WifiSwitches(carrier = false, widerReach = false, direct = false) }

    /** Tests override and restore. */
    @Volatile var debugBuild: Boolean = BuildConfig.DEBUG

    /**
     * The tag a BMH2 hello carries (amendment 3, X8): the raised tag only where the caller asked for it (A7's stream and
     * relay `HopLink` sites pass `raiseTag = true`); every other hello (calls, messages, group relay, strangers) carries [base].
     */
    fun ownTagFor(base: BuildTag?, raiseTag: Boolean): BuildTag? = if (raiseTag) effectiveOwnTag(base) else base

    /** Pure rule of [ownTagFor] (tested without providers). */
    fun ownTagFor(
        base: BuildTag?, raiseTag: Boolean, realBuild: BuildTag?, debugBuild: Boolean, switches: WifiSwitches,
        firstRelease: Int, handlesBwf1: Boolean,
    ): BuildTag? = if (raiseTag) effectiveOwnTag(base, realBuild, debugBuild, switches, firstRelease, handlesBwf1) else base

    /** The raised tag from the live providers (item 5); used only through [ownTagFor]. BMH3 hops never use it. */
    fun effectiveOwnTag(base: BuildTag?): BuildTag? = effectiveOwnTag(
        base, LinkBuildTags.build, debugBuild,
        runCatching { switchSource() }.getOrDefault(WifiSwitches(carrier = false, widerReach = false, direct = false)),
        WifiWire.WIFI_FIRST_RELEASE, WifiHopPlumbing.BUILD_HANDLES_BWF1,
    )

    /** Pure rule (tested without providers). */
    fun effectiveOwnTag(
        base: BuildTag?, realBuild: BuildTag?, debugBuild: Boolean, switches: WifiSwitches,
        firstRelease: Int, handlesBwf1: Boolean,
    ): BuildTag? {
        if (!handlesBwf1) return base // nothing changes on air before A3's flip
        val wanted = if (debugBuild) switches.carrier || switches.widerReach || switches.direct else switches.carrier
        if (!wanted) return base
        val own = base ?: realBuild
        if (!debugBuild) return own // release: the real tag, even when the nearby-notice switch is off
        return if (own != null && own.releaseNumber >= firstRelease) own else BuildTag(firstRelease, own?.releaseKey ?: false)
    }
}
