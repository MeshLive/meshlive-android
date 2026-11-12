"""Launch-time dependency table of DEBUG switch arms (switch audit 2026-10-27, F2).

The same rules as app SwitchInert.kt: a requested arm (its launch extra present with a value other than its default)
that a parent switch masks changes nothing, so an A/B comparing it measures two identical arms. The app logs
`BENCHMARK switch_inert: <name> | because <parent>`; run_device_benchmark.validate_case refuses such a case unless it sets
`allow_inert_arms` (a deliberate regression control). Keep both tables in step when a mask changes.
"""

NATIVE_OPUS_DEFAULT_LOSS_PERCENT = 10
TIME_QUEUE_DEFAULT_BUDGET_MS = 1500


def _bool(extras, name, default):
    value = extras.get(name)
    return value if isinstance(value, bool) else default


def _mode(extras):
    """CallHeadOfLine.Mode of the extras; an unknown value is no mode, as in the app."""
    value = extras.get("test_call_head_of_line")
    mode = value.strip().lower() if isinstance(value, str) else None
    return mode if mode in ("off", "gatt_default", "on") else None


def _masks(arms, parent, masked):
    return [(arm, default, parent, masked) for arm, default in arms.items()]


RULES = (
    _masks({"test_link_pacer_seed": True, "test_link_pacer_peer_seed": False, "test_join_probe_seed": False,
            "test_on_time_seed_raise": True, "test_seed_raise_drain_guard": False, "test_relay_pace_head": True,
            "test_relay_pace_lead_cap": False},
           "link_pacing=false", lambda e: not _bool(e, "test_link_pacing", True))
    + _masks({"test_link_pacer_peer_seed": False, "test_join_probe_seed": False, "test_on_time_seed_raise": True,
              "test_seed_raise_drain_guard": False},
             "link_pacer_seed=false", lambda e: not _bool(e, "test_link_pacer_seed", True))
    + _masks({"test_seed_raise_drain_guard": False}, "on_time_seed_raise=false",
             lambda e: not _bool(e, "test_on_time_seed_raise", True))
    + _masks({"test_seed_raise_drain_guard": False}, "on_time_reports=false",
             lambda e: not _bool(e, "test_on_time_reports", True))
    + _masks({"test_start_rate_guard": True}, "delivery_reports=false",
             lambda e: not _bool(e, "test_delivery_reports", True))
    + _masks({"test_relay_drain_sustained": False}, "relay_time_queue=false",
             lambda e: not _bool(e, "test_relay_time_queue", False))
    + _masks({"test_relay_time_queue": False, "test_relay_drain_sustained": False},
             "relay_child_media_age=false+bounded_media_age=false",
             lambda e: not _bool(e, "test_relay_child_media_age", True) and not _bool(e, "test_bounded_media_age", False))
    + _masks({"test_relay_child_media_age": True, "test_call_audio_headroom": True}, "bounded_media_age=true",
             lambda e: _bool(e, "test_bounded_media_age", False))
    + _masks({"test_relay_one_pdu_move": False}, "relay_phy_guard=false",
             lambda e: not _bool(e, "test_relay_phy_guard", True))
    + _masks({"test_relay_one_pdu_move": False, "test_phy_direction_split": False}, "adaptive_phy=false",
             lambda e: not _bool(e, "test_adaptive_phy", True))
    + _masks({"test_adaptive_phy_split": False}, "phy_direction_split=true",
             lambda e: _bool(e, "test_phy_direction_split", False))
    + _masks({"test_relay_stall_separate": True}, "relay_child_stall_guard=false",
             lambda e: not _bool(e, "test_relay_child_stall_guard", True))
    + _masks({"test_relay_stall_separate": True}, "relay_establish_fallback=false",
             lambda e: not _bool(e, "test_relay_establish_fallback", True))
    + _masks({"test_relay_hold_in_place": True},
             "relay_establish_fallback=false+relay_stall_holdoff=false+relay_refusal_escalation=false",
             lambda e: not _bool(e, "test_relay_establish_fallback", True) and not _bool(e, "test_relay_stall_holdoff", True)
             and not _bool(e, "test_relay_refusal_escalation", True))
    # B2: the child-side watch follows relay_child_reanchor unless set apart; the same-parent preference is off by default (build 4).
    + _masks({"test_relay_parent_trickle": True}, "relay_parent_watch=false",
             lambda e: not _bool(e, "test_relay_parent_watch", _bool(e, "test_relay_child_reanchor", True)))
    + _masks({"test_relay_recovery_grace": True}, "relay_prefer_parent=false",
             lambda e: not _bool(e, "test_relay_prefer_parent", False))
    + _masks({"test_playout_time_budget_ms": TIME_QUEUE_DEFAULT_BUDGET_MS}, "playout_time_target=false",
             lambda e: not _bool(e, "test_playout_time_target", False))
    + _masks({"test_call_fragment_airtime_ms": 0}, "call_video_fragments=false",
             lambda e: not _bool(e, "test_call_video_fragments", False) and _mode(e) in (None, "off"))
    + _masks({"test_call_handshake_deadline_all": True}, "relayed_calls=true",
             lambda e: _bool(e, "test_relayed_calls", False))
    + _masks({"test_relayed_calls_help": True, "test_relayed_calls_consent": True, "test_relayed_calls_max_relays": 0,
              "test_relayed_calls_require": 0, "test_relayed_calls_no_direct": False},
             "relayed_calls=false", lambda e: not _bool(e, "test_relayed_calls", False))
    + _masks({"test_call_native_opus_fec": True, "test_call_native_opus_dtx": False,
              "test_call_native_opus_loss_pct": NATIVE_OPUS_DEFAULT_LOSS_PERCENT},
             "call_native_opus=false", lambda e: not _bool(e, "test_call_native_opus", False))
    + _masks({"test_adv_inplace_boost": True, "test_adv_inplace_keep_set": True}, "stable_adv_address=false",
             lambda e: not _bool(e, "test_stable_adv_address", True))
    + _masks({"test_wifi_wider_reach": False, "test_wifi_direct": False, "test_wifi_icm": False,
              "test_wifi_ble_standby_low_power": False, "test_wifi_allow_24ghz": False,
              "test_wifi_share_attach": False, "test_wifi_force_carrier": "auto",
              "test_wifi_aware_derived_port": True, "test_wifi_aware_require_channel": False},
             "wifi_carrier=false", lambda e: not _bool(e, "test_wifi_carrier", False))
)


def _same(value, default):
    if isinstance(value, bool) or isinstance(default, bool):
        return value is default if isinstance(value, bool) and isinstance(default, bool) else False
    if isinstance(value, (int, float)) and isinstance(default, (int, float)):
        return float(value) == float(default)
    return value == default


def inert_arms(extras):
    """[(name, parent)] for every arm in launch [extras] that a parent masks; the first masking parent per arm."""
    found, seen = [], set()
    for arm, default, parent, masked in RULES:
        if arm in seen or arm not in extras or _same(extras[arm], default) or not masked(extras):
            continue
        seen.add(arm)
        found.append((arm.removeprefix("test_"), parent))
    return found
