package com.example.bluetoothtest

import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue

/**
 * Two real [HopLink]s over a Bluetooth [HopPair] that both activate, plus a Wi-Fi carrier over in-process pipes offered
 * on both sides through the hop's own `WifiHopPorts.offerCarrier` (what A5's attempt does once its socket is bound).
 * `parent` is `pair.a`, `child` is `pair.b`. No Android type and no radio is involved; the carrier switch, the markers,
 * the fallback and the delivery are the production `CarrierSet`/`CarrierCutover` code.
 */
internal class HopWifiRig(
    parentRole: HopCapsPlan.HopRole = HopCapsPlan.HopRole.STREAM_PARENT,
    childRole: HopCapsPlan.HopRole = HopCapsPlan.HopRole.STREAM_CHILD,
    val parentOwner: HopOwner = RecordingHopOwner(),
    val childOwner: HopOwner = RecordingHopOwner(),
    parentNeighbourIsRelay: Boolean = false,
    parentAgent: (WifiHopPorts) -> WifiHopAgent? = { null },
    childAgent: (WifiHopPorts) -> WifiHopAgent? = { null },
    parentSwitchOn: () -> Boolean = { true },
    childSwitchOn: () -> Boolean = { true },
    val pair: HopPair = HopPair(tagGate = true),
) {
    /** Real elapsed time plus a jump past the 3 s active-before-switch bound when a Wi-Fi carrier is added. */
    private val startNs = System.nanoTime()
    private val offsetNs = AtomicLong(0L)
    fun nowNs(): Long = System.nanoTime() - startNs + offsetNs.get()
    val parentLog: MutableList<String> = Collections.synchronizedList(ArrayList())
    val childLog: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val parentPortsRef = AtomicReference<WifiHopPorts?>()
    private val childPortsRef = AtomicReference<WifiHopPorts?>()
    private val wifiPairs = ArrayList<PipePair>()
    val ledger = WifiLedger { nowNs() / 1_000_000L }

    val parent = HopLink(
        role = parentRole,
        session = pair.a,
        closeRaw = {},
        anyLocalSwitchOn = parentSwitchOn,
        log = { line -> parentLog += line },
        owner = parentOwner,
        newAgent = { ports -> parentPortsRef.set(ports); parentAgent(ports) },
        neighbourIsRelay = parentNeighbourIsRelay,
        localCaps = { Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1) },
        nanoClock = { nowNs() },
    )
    val child = HopLink(
        role = childRole,
        session = pair.b,
        closeRaw = {},
        anyLocalSwitchOn = childSwitchOn,
        log = { line -> childLog += line },
        owner = childOwner,
        newAgent = { ports -> childPortsRef.set(ports); childAgent(ports) },
        localCaps = { Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1) },
        nanoClock = { nowNs() },
    )
    val parentReads = RecordReadThread(parent.reader, "rig-parent-reader")
    val childReads = RecordReadThread(child.reader, "rig-child-reader")

    val parentPorts: WifiHopPorts get() = requireNotNull(parentPortsRef.get()) { "the parent hop never activated" }
    val childPorts: WifiHopPorts get() = requireNotNull(childPortsRef.get()) { "the child hop never activated" }

    /** Both hops send CAPS (the trigger of their role) and both activate on their reading threads. */
    fun activate(trigger: (HopLink, HopLink) -> Unit = { _, child -> child.noteFirstHeader() }): HopWifiRig {
        trigger(parent, child)
        assertTrue("both hops activated", awaitCondition { parentPortsRef.get() != null && childPortsRef.get() != null })
        return this
    }

    /**
     * A bound Wi-Fi carrier of [kind] offered on both hops (same ticket), past the 3 s active-before-switch bound, then
     * waits until both hops send on it and both accept on it.
     *
     * Sending on the new carrier is not enough: each receiver accepts it only when the peer's `SWITCH` arrives through the
     * old link's media queue, and until then it holds the content that arrives early on the new carrier (CarrierCutover,
     * up to 1 s). Hop control (`ROUTE_CONTROL`) is never held, so a flow started in that window saw a withdrawal before
     * envelopes written ahead of it (StreamHopCarrierFlowTest, "but was:<[]>"; nothing was lost). The wait for both
     * accepting sides gives every flow a settled carrier in both directions.
     *
     * The parent offers first, and a `SWITCH` whose ticket the child has not registered yet is burned: the child would then
     * accept the carrier only by promoting its first held content after the 1 s hold (forced in a rig: 1.04-1.07 s, none
     * lost), and never while no content flows. With [awaitSwitch] the parent's Bluetooth bytes are held until both hops
     * have offered, so each `SWITCH` meets a registered carrier. Without it nothing is gated or awaited.
     */
    fun addWifi(kind: CarrierKind = CarrierKind.WIFI_AWARE, ndpOwner: String? = null, awaitSwitch: Boolean = true): PipePair {
        val behind = 3_100_000_000L - nowNs()
        if (behind > 0) offsetNs.addAndGet(behind)
        val wifi = PipePair(kind = kind, label = kind.label, liveness = CarrierLivenessPolicy.WIFI)
        wifiPairs += wifi
        val ticket = ByteArray(WifiWire.TICKET_BYTES) { (wifiPairs.size * 16 + it).toByte() }
        ndpOwner?.let { assertTrue("the data path is reserved", ledger.reserveNdp(it, 2)) }
        val parentCarrier = WifiSocketCarrier(
            kind, Socket(), wifi.a.link, { wifi.cut() }, ledger, ndpOwner, ticket.copyOf(),
            Bwf.Ready(0xFFFF, 0, 0xFFFF), bandVerified = false, channelMhz = null,
        )
        val childCarrier = WifiSocketCarrier(
            kind, Socket(), wifi.b.link, { wifi.cut() }, ledger, null, ticket.copyOf(),
            Bwf.Ready(0xFFFF, 0, 0xFFFF), bandVerified = false, channelMhz = null,
        )
        if (awaitSwitch) pair.blockWritesFromA(true)
        try {
            assertTrue(parentPorts.offerCarrier(parentCarrier) in 1..62)
            assertTrue(childPorts.offerCarrier(childCarrier) in 1..62)
        } finally {
            if (awaitSwitch) pair.blockWritesFromA(false)
        }
        if (awaitSwitch) {
            assertTrue("the parent sends on $kind", awaitCondition { parent.sendKind == kind })
            assertTrue("the child sends on $kind", awaitCondition { child.sendKind == kind })
            assertTrue("the child accepts on $kind", awaitCondition { child.acceptKind == kind })
            assertTrue("the parent accepts on $kind", awaitCondition { parent.acceptKind == kind })
        }
        return wifi
    }

    fun close() {
        parent.close()
        child.close()
        parentReads.stop()
        childReads.stop()
        wifiPairs.forEach { it.cut() }
        pair.cut()
    }

    companion object {
        fun awaitCondition(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (System.nanoTime() < deadline) {
                if (condition()) return true
                Thread.sleep(10)
            }
            return condition()
        }
    }
}
