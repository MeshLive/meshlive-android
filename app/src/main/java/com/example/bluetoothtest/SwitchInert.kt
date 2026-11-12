package com.example.bluetoothtest

import android.os.Bundle

/**
 * Launch-time dependency table (switch audit 2026-10-27, F2): a requested DEBUG arm that a parent switch masks changes
 * nothing, and an A/B that compares it measures two identical arms (setup-retry, thinning and time-queue A/Bs). A DEBUG
 * launch logs one `BENCHMARK switch_inert: <name> | because <parent>` line per masked arm it asked for, and
 * tools/switch_inert.py (the same table) lets the runner refuse such a case before any phone starts.
 *
 * An arm counts as requested when its extra is present with a value other than its default (an explicit default is no
 * arm). Each rule reads the extras with the defaults MainActivity uses for a benchmark launch. Masks that depend on the
 * call/stream kind or the transport (delivery-rate control needs stream delivery reports only on streams) are not listed:
 * the table never refuses a case that could be valid.
 */
internal object SwitchInert {
    fun interface Extras {
        fun value(name: String): Any?
    }

    class Rule(val arm: String, val armDefault: Any, val parent: String, val masked: (Extras) -> Boolean)

    data class Inert(val name: String, val parent: String) {
        fun line(): String = "BENCHMARK switch_inert: $name | because $parent"
    }

    private fun Extras.bool(name: String, default: Boolean): Boolean = value(name) as? Boolean ?: default

    private fun Extras.string(name: String): String? = value(name) as? String

    private fun masks(arms: Map<String, Any>, parent: String, masked: (Extras) -> Boolean): List<Rule> =
        arms.map { (arm, default) -> Rule(arm, default, parent, masked) }

    val RULES: List<Rule> =
        masks(
            mapOf(
                "test_link_pacer_seed" to true, "test_link_pacer_peer_seed" to false, "test_join_probe_seed" to false,
                "test_on_time_seed_raise" to true, "test_seed_raise_drain_guard" to false, "test_relay_pace_head" to true,
                "test_relay_pace_lead_cap" to false,
            ),
            "link_pacing=false",
        ) { !it.bool("test_link_pacing", true) } +
            masks(
                mapOf(
                    "test_link_pacer_peer_seed" to false, "test_join_probe_seed" to false,
                    "test_on_time_seed_raise" to true, "test_seed_raise_drain_guard" to false,
                ),
                "link_pacer_seed=false",
            ) { !it.bool("test_link_pacer_seed", true) } +
            masks(mapOf("test_seed_raise_drain_guard" to false), "on_time_seed_raise=false") {
                !it.bool("test_on_time_seed_raise", true)
            } +
            masks(mapOf("test_seed_raise_drain_guard" to false), "on_time_reports=false") {
                !it.bool("test_on_time_reports", true)
            } +
            masks(mapOf("test_start_rate_guard" to true), "delivery_reports=false") { !it.bool("test_delivery_reports", true) } +
            masks(mapOf("test_relay_drain_sustained" to false), "relay_time_queue=false") {
                !it.bool("test_relay_time_queue", false)
            } +
            masks(
                mapOf("test_relay_time_queue" to false, "test_relay_drain_sustained" to false),
                "relay_child_media_age=false+bounded_media_age=false",
            ) { !it.bool("test_relay_child_media_age", true) && !it.bool("test_bounded_media_age", false) } +
            masks(mapOf("test_relay_child_media_age" to true, "test_call_audio_headroom" to true), "bounded_media_age=true") {
                it.bool("test_bounded_media_age", false)
            } +
            masks(mapOf("test_relay_one_pdu_move" to false), "relay_phy_guard=false") { !it.bool("test_relay_phy_guard", true) } +
            masks(mapOf("test_relay_one_pdu_move" to false, "test_phy_direction_split" to false), "adaptive_phy=false") {
                !it.bool("test_adaptive_phy", true)
            } +
            masks(mapOf("test_adaptive_phy_split" to false), "phy_direction_split=true") {
                it.bool("test_phy_direction_split", false)
            } +
            masks(mapOf("test_relay_stall_separate" to true), "relay_child_stall_guard=false") {
                !it.bool("test_relay_child_stall_guard", true)
            } +
            masks(mapOf("test_relay_stall_separate" to true), "relay_establish_fallback=false") {
                !it.bool("test_relay_establish_fallback", true)
            } +
            masks(
                mapOf("test_relay_hold_in_place" to true),
                "relay_establish_fallback=false+relay_stall_holdoff=false+relay_refusal_escalation=false",
            ) {
                !it.bool("test_relay_establish_fallback", true) && !it.bool("test_relay_stall_holdoff", true) &&
                    !it.bool("test_relay_refusal_escalation", true)
            } +
            // B2: the child-side watch follows relay_child_reanchor unless set apart; the same-parent preference is off by default (build 4).
            masks(mapOf("test_relay_parent_trickle" to true), "relay_parent_watch=false") {
                !it.bool("test_relay_parent_watch", it.bool("test_relay_child_reanchor", true))
            } +
            masks(mapOf("test_relay_recovery_grace" to true), "relay_prefer_parent=false") {
                !it.bool("test_relay_prefer_parent", false)
            } +
            masks(
                mapOf("test_playout_time_budget_ms" to TimeBasedSizing.DEFAULT_LATENCY_BUDGET_MS.toInt()),
                "playout_time_target=false",
            ) { !it.bool("test_playout_time_target", false) } +
            masks(mapOf("test_call_fragment_airtime_ms" to 0), "call_video_fragments=false") {
                !it.bool("test_call_video_fragments", false) &&
                    CallHeadOfLine.Mode.fromValue(it.string(CallHeadOfLine.EXTRA)).let { mode ->
                        mode == null || mode == CallHeadOfLine.Mode.OFF
                    }
            } +
            masks(mapOf("test_call_handshake_deadline_all" to true), "relayed_calls=true") {
                it.bool(RelayedCallsFeature.EXTRA, false)
            } +
            masks(
                mapOf(
                    RelayedCallsFeature.EXTRA_HELP to true, RelayedCallsFeature.EXTRA_CONSENT to true,
                    RelayedCallsFeature.EXTRA_MAX_RELAYS to 0, RelayedCallsFeature.EXTRA_REQUIRE to 0,
                    "test_relayed_calls_no_direct" to false,
                ),
                "relayed_calls=false",
            ) { !it.bool(RelayedCallsFeature.EXTRA, false) } +
            masks(
                mapOf(
                    "test_call_native_opus_fec" to true, "test_call_native_opus_dtx" to false,
                    "test_call_native_opus_loss_pct" to NativeOpusSettings.DEFAULT_LOSS_PERCENT,
                ),
                "call_native_opus=false",
            ) { !it.bool(NativeOpusSettings.EXTRA_ENABLED, false) } +
            masks(mapOf("test_adv_inplace_boost" to true, "test_adv_inplace_keep_set" to true), "stable_adv_address=false") {
                !it.bool("test_stable_adv_address", true)
            } +
            masks(
                mapOf(
                    "test_wifi_wider_reach" to false, "test_wifi_direct" to false, "test_wifi_icm" to false,
                    "test_wifi_ble_standby_low_power" to false, "test_wifi_allow_24ghz" to false,
                    "test_wifi_share_attach" to false, "test_wifi_force_carrier" to "auto",
                    "test_wifi_aware_derived_port" to true, "test_wifi_aware_require_channel" to false,
                ),
                "wifi_carrier=false",
            ) { !it.bool("test_wifi_carrier", false) }

    /** The arms [extras] asked for that a parent masks; the first masking parent per arm. */
    fun inert(extras: Extras): List<Inert> = RULES
        .filter { rule -> extras.value(rule.arm).let { it != null && !sameValue(it, rule.armDefault) } && rule.masked(extras) }
        .distinctBy { it.arm }
        .map { Inert(it.arm.removePrefix("test_"), it.parent) }

    /** The masked arms of a launch's extras (an unreadable extra counts as absent). */
    @Suppress("DEPRECATION")
    fun inert(extras: Bundle?): List<Inert> = inert { key -> extras?.let { runCatching { it.get(key) }.getOrNull() } }

    private fun sameValue(value: Any, default: Any): Boolean = when {
        value is Number && default is Number -> value.toDouble() == default.toDouble()
        else -> value == default
    }
}
