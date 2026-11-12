package com.example.bluetoothtest

import android.content.Intent

/**
 * Switches of device-family batch P (PHY and link-layer procedures). Every arm is DEBUG-only (a launch
 * extra from another app must not change a release build) and default OFF: they are Experimental and
 * listed in SWITCHES ("Experimental, alternative and optional behaviour"). Behaviour that ships
 * ON in this batch (the Android 13 PHY readPhy backstop, the priority-request supervision log, the
 * dual-role probe log) is controlled by its device-policy entry instead: `test_device_policy_off=<id>`.
 *
 * Root wires [applyLaunchExtras] once, next to the other launch-extra parsing in MainActivity.
 */
internal object PhySwitches {
    /**
     * `test_ll_gate=true` (Experimental, BACKLOG P-01): [LlProcedureGate] serialises MTU, discovery, PHY and
     * connection-update requests on one link (no overlap, settle gaps, a peripheral waits for the central's
     * first connection update, one 1M retry on 0x23/0x2A). Off: every request goes straight to the stack as
     * before. Delays the 2M start by seconds.
     */
    @Volatile var llGate: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_link_change_rollback=true` (Experimental, BACKLOG P-02): a link lost with a fault status within
     * 10 s of an MTU, PHY or priority change remembers that change as bad for the peer (persisted, expires
     * with a Bluetooth restart, a security-patch change or 7 days), so the next connection skips it.
     */
    @Volatile var linkChangeRollback: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_priority_reassert=true` (Experimental, aosp-bt-versions CONN-02 and Nordic Android-BLE-Library
     * #537/#558): when the stack reports a connection update whose interval is above the requested
     * priority's maximum (the interval silently reverted toward 45 ms), the same priority is requested again,
     * at most twice per request. Off: the drift is only logged (`BENCHMARK le_priority_drift`).
     */
    @Volatile var priorityReassert: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_call_1m_first=true` (Experimental, JT2-P3, runner tunable `call_1m_first`): a direct outgoing GATT call opens on
     * LE 1M with no PHY request before the call is active, asks for 2M after the first shown picture only when the call's planned
     * load exceeds what 1M carries (a sender link limit holds it on 1M unless [call1mFirstLinkLimit]), falls back to 1M in place on an early loss after that
     * upgrade (remembered by the authenticated node), and offers no 2M -> 1M setup retry. Off: 2M at subscribe, setup retry intact.
     * See [CallOneMFirst].
     */
    @Volatile var call1mFirst: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_call_1m_first_coc=true` (Experimental, runner tunable `call_1m_first_coc`): the same 1M-first start as
     * [call1mFirst] for a direct outgoing BLE L2CAP CoC call: the CoC control link asks for 1M (the path a peer remembered bad
     * on 2M already takes), the call asks that live link for 2M after the first shown picture only when it needs it, falls back
     * to 1M in place on an early loss after that upgrade, and offers no 2M -> 1M setup retry. Independent of [call1mFirst],
     * which covers GATT only. Off: 2M after the PSM read, setup retry intact. See [CallOneMFirst].
     */
    @Volatile var call1mFirstCoc: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_call_1m_first_link_limit=true` (Experimental, runner tunable `call_1m_first_link_limit`): with [call1mFirst] or
     * [call1mFirstCoc], the sender's link limit also asks for 2M after the first picture, as the first version did. Off: the
     * call stays on 1M at its link limit (`BENCHMARK link_phy ... reason=link_limit_held`). See [CallPhyUpgradeGate].
     */
    @Volatile var call1mFirstLinkLimit: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_own_2m_memory=true` (Experimental, runner tunable `own_2m_memory`): after two symmetric 2M call setup stalls in a row
     * within 24 h as the dialing central, this phone starts links to peers with no memory key (unrecognised rotating offers) on
     * full 1M while that local mark is fresh ([OwnControllerTwoM]). Off: those links start on 2M as before.
     */
    @Volatile var own2mMemory: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_slow_2m_in_place=true` (Experimental, runner tunable `slow_2m_in_place`): a direct call to an unrecognised peer whose
     * first symmetric 2M link stalls or crawls during the handshake first asks the same link for 1M and keeps the handshake
     * running; only when 1M is not confirmed or the handshake still makes no progress is the link closed for today's 1M setup
     * retry, whose rediscovery then waits out the callee's incoming-handshake deadline ([SlowTwoMInPlace]). Off: the 3 s guard
     * closes and retries as before.
     */
    @Volatile var slow2mInPlace: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** Reads the launch extras; the testable form of [applyLaunchExtras]. */
    fun applyFlags(flag: (name: String) -> Boolean) {
        call1mFirst = flag("test_call_1m_first")
        call1mFirstCoc = flag("test_call_1m_first_coc")
        call1mFirstLinkLimit = flag("test_call_1m_first_link_limit")
        llGate = flag("test_ll_gate")
        linkChangeRollback = flag("test_link_change_rollback")
        priorityReassert = flag("test_priority_reassert")
        own2mMemory = flag("test_own_2m_memory")
        slow2mInPlace = flag("test_slow_2m_in_place")
    }

    fun applyLaunchExtras(intent: Intent) = applyFlags { name -> intent.getBooleanExtra(name, false) }

    /** Launch extras of the three GATT PHY arms [radioArms] reads; every one is DEBUG-only. */
    val RADIO_ARM_EXTRAS = listOf(
        "test_gatt_phy_split",
        "test_adaptive_phy_split",
        "test_adaptive_phy_split_dir",
        "test_phy_after_auth",
        "test_phy_direction_split",
    )

    /**
     * The GATT PHY split and PHY-after-auth arms of one launch (switch audit 2026-10-27, A2: MainActivity read them outside
     * any DEBUG block and the [DebugTunables] fields had no setter guard, so a release build obeyed another app's extras with
     * no log). A release build ([debug] false) always gets [RadioArms] defaults, whatever the extras say.
     */
    fun radioArms(debug: Boolean, string: (String) -> String?, flag: (String) -> Boolean): RadioArms {
        if (!debug) return RadioArms()
        return RadioArms(
            gattPhySplit = GattPhySplit.fromDebugValue(string("test_gatt_phy_split")),
            adaptivePhySplit = if (flag("test_adaptive_phy_split")) {
                GattPhySplit.fromDebugValue(string("test_adaptive_phy_split_dir")) ?: GattPhySplit.TX_2M_RX_1M
            } else {
                null
            },
            phyAfterAuth = flag("test_phy_after_auth"),
            phyDirectionSplit = flag("test_phy_direction_split"),
        )
    }

    /** Writes [radioArms] into [DebugTunables] (whose setters guard them again). */
    fun applyRadioArms(intent: Intent) {
        val arms = radioArms(BuildConfig.DEBUG, intent::getStringExtra) { intent.getBooleanExtra(it, false) }
        DebugTunables.experimentalPhySplit = arms.gattPhySplit
        DebugTunables.adaptivePhySplit = arms.adaptivePhySplit
        DebugTunables.phyAfterAuth = arms.phyAfterAuth
        DebugTunables.phyDirectionSplit = arms.phyDirectionSplit
    }

    data class RadioArms(
        val gattPhySplit: GattPhySplit? = null,
        val adaptivePhySplit: GattPhySplit? = null,
        val phyAfterAuth: Boolean = false,
        /** Experimental `test_phy_direction_split` ([DebugTunables.phyDirectionSplit]). */
        val phyDirectionSplit: Boolean = false,
    )
}
