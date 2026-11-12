package com.example.bluetoothtest

/** The local `CAPS` builder: what this phone would send now, from the adjusted facts and the DEBUG arms. */
internal object WifiLocalCaps {
    /**
     * The `CAPS` this phone would send now: the value the call agent sends; A7's `HopLink` default `localCaps` calls
     * `WifiLocalCaps.build(RealWifiEnv, keyAvailable = session.hopWifiKey != null)`. Logs one `wifi_caps: dir=local` line.
     *
     * AP10: [otherBluetoothMedia] is the hop's own fact (`WifiHopPorts.otherBluetoothMedia()`). It is asked only with the
     * 2.4 GHz-alone switch on, where a phone beside a 2.4 GHz client connection offers Aware when it carries no other
     * Bluetooth media link. A caller that does not pass it (a stream or relay hop) keeps the 5 GHz rule. The record's
     * format is unchanged; only when the existing Aware bit is set changes, and only under the switch.
     */
    fun build(env: WifiEnv, keyAvailable: Boolean = true, otherBluetoothMedia: () -> Boolean = { true }): Bwf.Caps {
        val read = env.facts() // already adjusted by WifiDebugArms
        val facts = if (read.aware24GhzAlone) read.copy(otherBluetoothMedia = otherBluetoothMedia()) else read
        val decision = env.decide(facts)
        val force = DebugTunables.wifiForceCarrier
        val allClear = !keyAvailable || DebugTunables.wifiProbe || force == "bluetooth"
        val aware = !allClear && decision.aware && force != "direct"
        val direct = !allClear && force != "aware" && env.supportsBearer(WifiBearer.DIRECT)
        val masked = decision.copy(
            aware = aware,
            awareIcm = aware && decision.awareIcm,
            directGo = direct && decision.directGo,
            directClient = direct && decision.directClient,
        )
        env.log(
            WifiLog.caps(
                "dir" to "local",
                "aware" to decision.aware,
                "reason" to decision.reason?.name,
                "api" to facts.api,
                "staMhz" to facts.staFrequencyMhz,
                "freeNdp" to facts.freeNdp,
                "otherClient" to facts.otherAwareClientAttached,
                "paused" to (facts.localFaultPauseUntilMs > facts.nowMs),
                "keyAvailable" to keyAvailable,
                "alone24" to facts.aware24GhzAlone,
                "otherBtMedia" to (if (facts.aware24GhzAlone) facts.otherBluetoothMedia else "unread"),
            ),
        )
        return WifiRoles.capsFor(masked, facts.freeNdp, facts.freeNdp)
    }
}
