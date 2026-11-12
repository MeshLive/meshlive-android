"""Exercise real authenticated private calls and score the two media directions separately."""
import argparse
import concurrent.futures
import datetime as dt
import json
import re
import subprocess
import sys
import time
from pathlib import Path

from benchmark_layout import output_path, resolve
from device_log_capture import LogCapture

from run_device_benchmark import COMPONENT, PACKAGE, Runner, raw_fixture_metadata, reference_audio_metadata, media_impairment_metadata, validate_case
from run_device_benchmark import (LOG_DROP_REASON, LogLinesDropped, confirmed_log_loss, drops_in_window,
                                  manifest_log_drops)
import acceptance_profiles as acceptance
import applied_arms
import release_join
import user_launch
import bt_stack_health as stack_health
import score_intercom_call as intercom
from score_device_run import (app_crash_gate, DEFAULT_THRESHOLDS, ENDED_CALLBACK, ENDED_EVIDENCE, ENDED_UI, ENDED_UI_PAIRING_S,
                              aggregate_status, as_diagnostic, demote_user_gates, ended_proof, gate, limits_for, records,
                              scope_records, score_log, timestamp)

PROFILE_KEYS = {"width": "width", "height": "height", "fps": "fps",
                "bitrate_kbps": "bitrateKbps", "codec": "encoder",
                "rate_control": "rateControl", "thermal_reduction": "thermalReduction"}
MEASUREMENT_OVERRUN_S = 5.0  # a planned media window that ran longer than this is recorded as an overrun
PLAYBACK_MS = {"low": 30, "balanced": 250, "stable": 500, "auto": 250}  # auto starts at 250 ms and adapts to 500
FIRST_SURFACE_LINE = "Private call startup: media-to-first-surface"
# The call link's status line names the peer it authenticated. A person's launch (BMH3 pseudonyms, shipped on) shows the
# first as "an unverified stranger" because the real key is withheld until the reveal; the status line the reveal logs
# afterwards ("The other phone's identity is confirmed") names the real, verified node. Both are the app's own status
# lines, logged by the call itself; neither is a stable node ID that rotating IDs or pseudonyms change.
STRANGER_LABEL = "an unverified stranger"
LINK_LINE = r"Encrypted private-call link to ([0-9a-f]{12}|an unverified stranger) over (.+)"
IDENTITY_CONFIRMED = r"The other phone's identity is confirmed: ([0-9a-f]{12})"
# Both lines are logged only from the call TextureView's onSurfaceTextureUpdated after a frame reached it.
# The Oppo A15s (Android 10) device log dropped 80-90% of app lines (stage-record sequence gaps in
# calls-noloss-s24a15s-1756e738, mvp-smoke-s24a15s-b9029e48) including the one-off first-surface line,
# while surviving 'visible continuity: 248 frames' lines proved the picture reached the surface.
VIDEO_SURFACE_EVIDENCE = FIRST_SURFACE_LINE + r"|Private call visible continuity: [1-9]\d* frames"
CONTINUITY_FIRST_SURFACE = re.compile(r"Private call visible continuity: [1-9]\d* frames .*"
                                      r"\| first surface [0-9.]+ ms after start, ([0-9.]+) ms ago")


class StackBlocked(RuntimeError):
    """The phone's Bluetooth stack, not the app, kept the endpoint from becoming available (bt_stack_health)."""

    def __init__(self, phone, reason, detail):
        super().__init__(f"{phone}: BLOCKED {reason}: {detail}")
        self.record = {"device": phone, "reason": reason, "detail": detail}


AVAILABLE_EXPRESSION = "BENCHMARK call available"
STACK_WATCH_LINES = ("createSocketChannel", "Attempting listen", stack_health.ALLOCATED)


CALL_PHASE = "BENCHMARK call phase "
ACTIVE_PHASE = CALL_PHASE + "ACTIVE"


NOT_DIALED_REASON = "The call was not dialed"
# Gates that are UNMEASURED because a line of this endpoint is absent (a profile, identity, first-surface or final
# counter line). A counter the app never logs for the encoder in use (camera_input_drops) is not one of them.
LINE_GATES = frozenset({
    "authenticated_peer", "selected_transport", "call_id_mismatches", "discovery_ms", "selected_profile_mismatches",
    "negotiated_remote_header_mismatches", "actual_allocation_mismatches", "setup_to_first_surface_ms",
    "setup_to_active_ms", "sender_drops", "call_tx_counter_regressions", "encoder_fps", "audio_drops", "decoder_drops",
    "surface_p95_ms", "surface_p99_ms", "visible_fps", "unexpected_freeze_events", "profile_fps_mismatches",
    "profile_resolution_mismatches", "profile_bitrate_mismatches"})
LAUNCH_LINE_GATES = frozenset({"discovery_ms", "selected_profile_mismatches"})  # their lines are due from the launch on


def authenticated_peer_gate(links, confirmed, prefix, manifest, phone, peer):
    """Whether the call authenticated the requested peer, from what the call itself logged.

    A link line that names a node and every identity-confirmed line must equal the requested peer's node. A link to an
    unverified stranger (a person's launch hides the real key until the reveal) proves an encrypted, handshaken link
    but not whose: only the confirmed line after the reveal, which the app logs after it checked the key against the
    discovery offer it selected, names the real peer. Without a named identity the gate stays UNMEASURED, and one
    that names another node FAILS."""
    named = [who for who, _ in links if who != STRANGER_LABEL]
    nodes = [*named, *confirmed]
    if nodes and prefix:
        bound = [item for item in manifest.get("runner_events", []) if item.get("action") == "rotating_peer_bound"
                 and item.get("device") == phone and item.get("peer") == peer]
        return {**gate(sum(node != prefix for node in nodes), 0),
                "evidence": {"link_names": named, "identity_confirmed": confirmed,
                             "pseudonymous_link": len(links) - len(named),
                             **({"discovery_bound_to_peer_rotid_own": len(bound)} if bound else {})}}
    if links and len(named) < len(links):
        return gate(None, 0, reason="The link was to an unverified stranger and no identity-confirmed line "
                                    "(the reveal) names the real peer, so whom it authenticated is not proven")
    return gate(None, 0)


def first_surface_gate(startup_ms, user_launch_phone):
    """Setup to the first remote surface against the 3 s target. In a user launch the target is reported, not a
    release FAIL: the release gate is the join metric (release_join), decided at suite level."""
    result = gate(startup_ms, DEFAULT_THRESHOLDS["max_startup_ms"])
    if not user_launch_phone:
        return result
    return {**as_diagnostic(result), "target_ms": DEFAULT_THRESHOLDS["max_startup_ms"],
            "basis": "Target, not a release FAIL: the release gate is the join metric (release_join_ms, suite level)"}


DIAL_VIA = ("intent", "broadcast")
CALLEE_STATES = ("foreground", "background", "screen_off")
# Planned Wi-Fi toggles on a named endpoint (`adb shell svc wifi disable|enable`): the Wi-Fi carrier (Aware or Direct) is
# taken away mid-call to measure the fall back to Bluetooth. The case's cleanup always puts Wi-Fi back on.
WIFI_ACTIONS = ("wifi_off", "wifi_on")
WIFI_STATE_WAIT_S = 4.0
WIFI_RECOVERY_WAIT_S = 8
# The DEBUG broadcast receiver the app must register (like the clean-shutdown receiver) for `dial_via: broadcast`; see
# docs/dev/testing.md "Dialling by broadcast". The action and the app's own handled line are the contract.
DIAL_BROADCAST_ACTION = "com.example.bluetoothtest.debug.DIAL"
DIAL_BROADCAST_HANDLED = r"BENCHMARK control_broadcast: control={control} \| handled=true"


def validate_dial_and_callee_state(case, devices, relays):
    """ValueError for a malformed `dial_via`, `callee_state` or `launch_order` (nothing touches a phone).

    `dial_via` (default intent: a case without it keeps its meaning and its ID) says how the invite reaches the caller's
    app: the `am start ... test_control` intent that brings the app to the front, or the DEBUG broadcast that does not.
    `callee_state` (default foreground) puts the idle callee in the state a user's call finds it before the dial;
    `screen_off` is refused unless the registry says `secure_lock: false` for the callee: KEYCODE_SLEEP on a phone with a
    pattern or PIN strands it at the keyguard (the ASUS has a pattern and never gets it). `launch_order` names the two
    endpoints in launch order; without it the registry's `slow_start` phones launch first."""
    via = case.get("dial_via", "intent")
    if via not in DIAL_VIA:
        raise ValueError(f"dial_via must be one of {', '.join(DIAL_VIA)}")
    state = case.get("callee_state", "foreground")
    if state not in CALLEE_STATES:
        raise ValueError(f"callee_state must be one of {', '.join(CALLEE_STATES)}")
    if state != "foreground" and relays:
        raise ValueError("callee_state applies to direct calls: a relayed route launches the callee, the relays and the "
                         "caller in its own order")
    callee = case["callee"]
    if state == "screen_off" and devices.get(callee, {}).get("secure_lock") is not False:
        raise ValueError(f"callee_state screen_off is refused for {callee}: the registry does not say secure_lock false "
                         "(KEYCODE_SLEEP on a phone with a pattern or PIN lock strands it at the keyguard; never guess "
                         "or dismiss a secure lock)")
    if "launch_order" in case and (not isinstance(case["launch_order"], list) or
                                   sorted(case["launch_order"]) != sorted([case["caller"], case["callee"]])):
        raise ValueError("launch_order must list the caller and the callee, each once")


def release_join_call(case, manifest, scopes, scores, voice, dialed, device_drops):
    """The call's release join record (tools/release_join.py); adds each receiving phone's diagnostic gate."""
    if voice or case.get("setup_action", "accept") != "accept" or dialed is False or len(scores) != 2:
        return None
    by_phone = {item["spec"]["device"]: item for item in scopes}
    caller, callee = case["caller"], case["callee"]
    if caller not in by_phone or callee not in by_phone:
        return None
    events = {phone: release_join.stage_events(by_phone[phone]["rows"]) for phone in (caller, callee)}
    identities = {phone: {item.get("callId") for item in profile_rows(by_phone[phone]["rows"], "identity")
                          if item.get("callId")} for phone in (caller, callee)}
    ids = identities[caller] | identities[callee]
    call_id = next(iter(ids)) if len(ids) == 1 else None
    directions = []
    for sender, receiver in ((callee, caller), (caller, callee)):
        dropped = None
        reports, pid = device_drops.get(receiver, ([], None))
        anchor = next((item["time"] for item in manifest.get("setup_anchors", []) if item["device"] == receiver), None)
        if drops_in_window(reports, pid, anchor):
            dropped = LOG_DROP_REASON
        directions.append(release_join.direction_record(
            sender, receiver, events[caller], events[receiver], call_id, dropped, by_phone[caller]["rows"], events[sender],
            receiver_shown=release_join.shown_frames(by_phone[receiver]["rows"])))
    users = all(user_launch.is_user_launch(user_launch.phone_tunables(case, phone)) for phone in (caller, callee))
    record = release_join.call_record(directions, manifest.get("callee_idle"), users, call_id)
    for item in scores:
        direction = next(entry for entry in directions if entry["receiver"] == item["device"])
        item["gates"]["release_join_ms"] = release_join.diagnostic_gate(record, direction, gate, as_diagnostic)
    return record


def idle_seconds(now, since):
    """Seconds between two logcat times of one phone (a midnight wrap counts forward)."""
    elapsed = timestamp(now) - timestamp(since)
    return elapsed + 86400 if elapsed < -3600 else elapsed


def call_dialed(manifest):
    """True when the runner sent the invite, False when the run stopped before it, None when the manifest cannot say."""
    if any(item.get("action") in {"invite", "invite_voice"} or str(item.get("action", "")).startswith(("call:", "voice_call:"))
           for item in manifest.get("control_events", [])):
        return True
    return False if manifest.get("call_not_dialed") or manifest.get("runner_error") else None


def not_dialed_record(manifest):
    """Why no call was dialed: the runner's own record, else (older manifests) its stop reason."""
    recorded = manifest.get("call_not_dialed") or {}
    reason = recorded.get("reason") or manifest.get("runner_error")
    device = recorded.get("device") or (manifest.get("runner_error_log_loss") or {}).get("device") or next(
        (phone for phone in manifest.get("devices", {}) if str(reason).startswith(f"{phone}:")), None)
    return {"reason": reason, **({"stage": recorded["stage"]} if recorded.get("stage") else {}),
            **({"device": device} if device else {}),
            "meaning": "No call was attempted, so no call gate is judged; the runner entry carries the verdict"}


def name_log_drops(gates, drops, pid, launched, setup, dialed):
    """Give each gate that is UNMEASURED for an absent line its real reason: the call was never dialed, or logd
    reported dropping this PID's lines where the line was due (after the launch for launch-time lines, after the
    dial for call lines). The status never changes here; the earlier reason is kept as `detail`."""
    for name, item in gates.items():
        if name not in LINE_GATES or item.get("status") != "UNMEASURED" or "planned" in str(item.get("reason")) or \
                item.get("reason") in (LOG_DROP_REASON, NOT_DIALED_REASON):
            continue
        if dialed is False:
            gates[name] = {**item, "reason": NOT_DIALED_REASON, "detail": item.get("reason")}
            continue
        lost = drops_in_window(drops, pid, launched if name in LAUNCH_LINE_GATES else (setup or {}).get("time") or launched)
        if lost:
            gates[name] = {**item, "reason": LOG_DROP_REASON, "detail": item.get("reason"), "reports": lost}


def phase_active(rows, drops, pid, setup=None, peer_rows=None, dialed=None):
    """One endpoint's ACTIVE evidence from its one-time `call phase ACTIVE` line. PASS with the line. Without
    it: UNMEASURED ('log lines dropped by logd') when logd reported dropping this PID's lines after its last
    earlier phase line (else the setup anchor) and before ENDED, because the line may be among them
    (ASUS, Android 11, release-window-pres: ACTIVE lost at camera open while the call showed video);
    FAIL when no such report exists.

    A reported drop does not leave the gate open when the peer's log proves the event: the peer showed this
    phone's call video (`peer_rows`), which the app accepts only from an answered call. A call that was never
    dialed (`dialed` False) has no ACTIVE to judge: UNMEASURED, never a failure."""
    active = next((row for row in rows if row["message"] == ACTIVE_PHASE), None)
    if active:
        return {"status": "PASS", "value": 1, "limit": 1, "comparison": "min", "time": active["time"]}
    if dialed is False:
        return {"status": "UNMEASURED", "value": None, "limit": 1, "reason": NOT_DIALED_REASON}
    ended = next((row for row in rows if row["message"] == CALL_PHASE + "ENDED"), None)
    earlier = [row for row in rows if row["message"].startswith(CALL_PHASE) and row is not ended and
               (ended is None or row["at"] <= ended["at"])]
    after = earlier[-1]["time"] if earlier else (setup or {}).get("time")
    until = ended["time"] if ended else None
    lost = drops_in_window(drops, pid, after, until)
    if lost:
        shown = next((row for row in peer_rows or [] if re.search(VIDEO_SURFACE_EVIDENCE, row["message"])), None)
        if shown:
            return {"status": "PASS", "value": 1, "limit": 1, "comparison": "min", "source": "peer_rendered_frames",
                    "own_line": LOG_DROP_REASON, "peer_time": shown["time"], "after": after, "until": until,
                    "reports": lost, "basis": "This phone's ACTIVE line is among the lines logd dropped; the peer "
                                              "rendered this phone's call video, which proves the answered call"}
        return {"status": "UNMEASURED", "value": None, "limit": 1, "reason": LOG_DROP_REASON,
                "after": after, "until": until, "reports": lost}
    return {"status": "FAIL", "value": 0, "limit": 1, "comparison": "min",
            "reason": "No call phase ACTIVE line, and logd reported no dropped line of this PID where it was due"}


def first_surface_time(rows):
    """App-clock time (seconds) the first call frame reached the remote TextureView, with its source:
    the one-off first-surface line, else a periodic continuity report that dates the same first frame
    ('first surface ... ms ago'). (None, None) when neither survived; never inferred from decoder output."""
    first = next((row for row in rows if FIRST_SURFACE_LINE in row["message"]), None)
    if first:
        return first["at"], "first_surface_line"
    for row in rows:
        match = CONTINUITY_FIRST_SURFACE.search(row["message"])
        if match:
            return row["at"] - float(match[1]) / 1000, "continuity_report"
    return None, None


def broadcast_shape(case, phone):
    peer = case["callee"] if phone == case["caller"] else case["caller"]
    result = {"source": phone, "receivers": [{"phone": peer, "parent": phone}],
            "transport": case["transport"], "profile": case["profiles"][phone],
            "media_trace": case.get("media_trace", False),
            "duration_s": case.get("duration_s", 60),
            "connection_timeout_s": case.get("connection_timeout_s", 30)}
    if "carrier" in case:
        result["carrier"] = case["carrier"]
    if phone in case.get("raw_fixture_files", {}):
        result["raw_fixture_file"] = case["raw_fixture_files"][phone]
        if phone in case.get("raw_fixture_sha256s", {}):
            result["raw_fixture_sha256"] = case["raw_fixture_sha256s"][phone]
        if phone in case.get("raw_fixture_metadatas", {}):
            result["raw_fixture_metadata"] = case["raw_fixture_metadatas"][phone]
    if phone in case.get("reference_audios", {}):
        result["reference_audio"] = case["reference_audios"][phone]
    if phone in case.get("media_impairments", {}):
        result["media_impairment"] = case["media_impairments"][phone]
    if "allow_inert_arms" in case:
        result["allow_inert_arms"] = case["allow_inert_arms"]
    if user_launch.is_app_default(case["profiles"][phone]):
        result["call_endpoint"] = True  # the shape's profile is the app's own choice: validate_case allows it here only
    if "tunables" in case:
        # Debug launch extras apply to both endpoints; dropping them here ran every call A/B arm
        # (for example test_call_ladder_from_request or test_bounded_media_age) at the app default.
        result["tunables"] = dict(case["tunables"])
    per_phone = case.get("phone_tunables")
    if isinstance(per_phone, dict) and isinstance(per_phone.get(phone), dict):
        # One endpoint only (for example the link-renewal A/B's stall hook): merged over the shared tunables.
        # A malformed value is left to validate_intercom_case, which refuses it.
        result["tunables"] = {**result.get("tunables", {}), **per_phone[phone]}
    implied = on_demand_tunables(case, phone)
    if implied:
        result["tunables"] = {**result.get("tunables", {}), **implied}
    return result


# Lines BluetoothPrivateCallRelay logs through MainActivity.setStatus. Prefixes are authenticated link
# identities (each adjacent hop's signed SecureBluetoothHandshake), not endpoint content identities.
RELAY_CALLEE = r"Private-call relay authenticated callee ([0-9a-f]{12})\."
RELAY_ADVERTISING = r"Private-call (?:CoC |GATT )?relay is advertising for its caller\."
RELAY_JOINED = r"Relaying an endpoint-encrypted private call between ([0-9a-f]{12}) and ([0-9a-f]{12});"
RELAY_CLOSED = r"Private-call relay stopped:|Private-call (?:caller|callee) path closed:|Private-call relay could not start:"
CALL_AVAILABLE = r"BENCHMARK call available: node=([0-9a-f]{12})"
# On-demand shape (production's Design C). The caller logs the helper its reach table selected (RelayHopPolicy.callerRouteLine;
# `id=node` is the fixed node prefix) and each candidate it moves to; a helper logs the SETUP it accepted and the next hop it
# will dial (`helper:` = the next relay's offer prefix, `target:` = the callee); the caller logs the relay count READY proved.
# A helper also logs RELAY_JOINED (parent = its authenticated link peer, child = its authenticated next hop) from
# BluetoothPrivateCallRelay.forwardOnDemand, but never RELAY_CALLEE: its child identity is the join line's second prefix.
ROUTE_CALLER = (r"BENCHMARK relayed_route: role=caller \| target=([0-9a-f]{12}) \| helper=([0-9a-f]{12}) \| id=(node|rdi) \| "
                r"budget=(\d+)")
ROUTE_CANDIDATE = (r"BENCHMARK relayed_route: role=caller \| attempt=(\d+) \| helper=([0-9a-f]{12}) \| "
                   r"target=([0-9a-f]{12}) \| budget=(\d+)")
SETUP_CALLER_READY = r"BENCHMARK relayed_setup: role=caller \| result=ready \| budget=(\d+) \| relays=(\d+)"
SETUP_HELPER_ACCEPTED = (r"BENCHMARK relayed_setup: role=helper \| result=accepted \| budget=(\d+) \| next_budget=(\d+) \| "
                         r"next=(helper|target):([0-9a-f]{12})")
# A rotating-ID (stranger) endpoint offers its call under a per-epoch discovery ID that no other log binds to it: the
# endpoint's own DEBUG line is the only proof of which scanned ID is this phone (app change: rotid_own, see TESTING).
ROTID_OWN = r"BENCHMARK rotid_own: role=call \| epoch=(-?\d+) \| rdi=([0-9a-f]{12})"
# Relayed call, callee side. A user launch (BMH3) makes the helper's join line name the callee by a per-call pseudonym that
# the callee never logs, so the child is bound from the callee's own lines (bind_relay_child): the DEBUG line
# `pseudonym_own` (the app names the link's pseudonym; the stronger binding), or else the callee's own rotating ID that the
# helper's SETUP targeted plus the callee's own log of an incoming relayed call.
PSEUDONYM_OWN = r"BENCHMARK pseudonym_own: role=call \| link=([^|]+?) \| id=([0-9a-f]{12})"
RELAYED_CAP = r"BENCHMARK call relayed_cap: relayHops=(\d+)"
CALLEE_RINGING = r"BENCHMARK call phase INCOMING_RINGING"
CALL_LINK_UP = r"Encrypted private-call link to "
BINDING_WINDOW_S = 15.0  # the callee's link, relayed_cap and pseudonym lines precede its ringing by well under a second
# The call endpoint's stop line (stopPrivateCallEndpoint); a relayed-calls helper tick that rebuilds the idle endpoint
# logs `relayed_helper: state=RUNNING` (or, from the restart-guard build, `offer_restart=done`) just before it.
ENDPOINT_STOPPED = r"^Stopped\.$"
HELPER_TICK = r"BENCHMARK relayed_helper: (?:state=|offer_restart=)"
# Relayed calls: a callee's helper ticks every 5 s and, on builds before the restart guard (rc-ctl-20261106), its first
# tick after the endpoint came up rebuilt the endpoint and closed a relay's hop that had just connected. The relay is
# launched only after that tick has passed: a restart is waited out (the new endpoint's "call available"), a tick
# without one is quiet for CALLEE_TICK_QUIET_S, and with no tick seen the wait ends CALLEE_SETTLE_S after availability.
CALLEE_SETTLE_S = 8.0
CALLEE_TICK_QUIET_S = 1.5
CALLEE_RESTARTS_MAX = 2
RELAY_COUNTERS_REASON = ("BluetoothPrivateCallRelay logs no forwarded-record counters, queue depth or per-direction "
                         "liveness; relay forwarding and queues remain UNMEASURED")
# Launch extras of the broadcast source path that the opaque relay never uses (it has no camera,
# encoder, decoder or playback): the relay is launched with transport and tunables only.
RELAY_DROPPED_EXTRAS = {"test_auto_broadcast", "test_role", "test_width", "test_height", "test_fps",
                        "test_bitrate_kbps", "test_rate_control", "test_codec", "test_playback_delay",
                        "test_thermal_reduction", "test_media_trace", "test_viewer_scan_mode", "test_audio",
                        "test_stream_flush", "test_gatt_write_timing"}


# Relays the app builds between caller and callee: PrivateCallRelayChain.MAX_RELAYS (relay chains, on by
# default since 2026-10-09). A relay re-advertises its child's offer (callee prefix, route cost + 1) and
# admits a child that is the callee (route cost 0) or another signed relay (route cost 1..MAX-1). Each
# relay is told its exact child offer (test_relay_callee + test_relay_route_cost), so it never takes a
# shortcut offer. Builds before relay chains admitted only one relay; the control arm
# `call_relay_chain: false` restores that rule, so a longer chain is BLOCKED under it.
APP_MAX_CALL_RELAYS = 4
APP_RELAY_CHAIN_LIMIT = (
    f"BLOCKED by the app: PrivateCallRelayChain.MAX_RELAYS admits at most {APP_MAX_CALL_RELAYS} relays between "
    "caller and callee")
APP_RELAY_CHAIN_OFF = (
    "BLOCKED by the app: tunable call_relay_chain=false keeps the one-relay rule (a relay's child must "
    "authenticate as the announced callee), so a relay cannot take another relay as its child")


def relay_launch_case(original_case, phone):
    """The case the opaque call relay is launched with; a carrier case gives the relay the same Wi-Fi extras."""
    return {"source": phone, "receivers": [], "transport": original_case["transport"],
            "profile": original_case["profiles"][original_case["caller"]],
            **({"tunables": dict(original_case["tunables"])} if "tunables" in original_case else {}),
            **({"carrier": original_case["carrier"]} if "carrier" in original_case else {})}


def call_relays(case):
    """Ordered middle hops, caller -> relays[0] -> ... -> relays[-1] -> callee.

    `relays: ["phone", ...]` is the schema; `relay: "phone"` (or a list) is accepted shorthand."""
    if "relay" in case and "relays" in case:
        raise ValueError("Name the relay chain once: `relays` (or the `relay` shorthand)")
    relays = case.get("relays", case.get("relay"))
    if relays is None:
        return []
    relays = [relays] if isinstance(relays, str) else relays
    if not isinstance(relays, list) or not relays or not all(isinstance(item, str) and item for item in relays):
        raise ValueError("A call relay chain must name phone aliases in hop order")
    return list(relays)


def call_route(case):
    return [case["caller"], *call_relays(case), case["callee"]]


def relay_chain_limit(case):
    """Why the current app cannot execute this requested relay chain, or None when it can."""
    relays = len(call_relays(case))
    if relays > APP_MAX_CALL_RELAYS:
        return APP_RELAY_CHAIN_LIMIT
    if relays > 1 and case.get("tunables", {}).get("call_relay_chain") is False:
        return APP_RELAY_CHAIN_OFF
    return None


def relay_child_offer(case, relay):
    """(callee prefix alias, route cost) of the offer this relay must take: its requested child's.

    The callee advertises route cost 0; the relay next to it advertises 1, and so on outwards."""
    relays = call_relays(case)
    return case["callee"], len(relays) - 1 - relays.index(relay)


# How a relayed case builds its middle hops (a requested semantic of the case, default "static"):
#   static     the DEBUG call relay (test_private_call_relay): BluetoothPrivateCallRelay.start() scans for the
#              callee's offer, authenticates it and re-advertises it for its caller. Not a production path.
#   on_demand  production's Design C: every middle phone is an ordinary call-ready helper (no DEBUG relay role);
#              the caller selects the helper from its reach table, sends SETUP, and the helper dials its next hop
#              (AndroidPrivateVideoCall.serveRelaySetup -> BluetoothPrivateCallRelay.startOnDemand). Experimental
#              in the app (RelayedCallsFeature, test_relayed_calls), LE transports only, fixed discovery IDs only.
RELAY_SHAPES = ("static", "on_demand")
APP_DEFAULT_RELAYS = 2  # RelayedCalls.DEFAULT_MAX_RELAYS: the reach-table cap unless test_relayed_calls_max_relays raises it
ON_DEMAND_ROUTE_WAIT_S = 45.0  # how long the caller's reach table may take to show a route of the requested length
PIN_REBIND_MAX = 2  # relaunches of the pinned phones when an epoch boundary rotates a pinned rotating ID before the dial


def relay_shape(case):
    """The case's relay shape: "static" (default; the DEBUG relay) or "on_demand" (production's helper path)."""
    shape = case.get("relay_shape", "static")
    if shape not in RELAY_SHAPES:
        raise ValueError(f"relay_shape must be one of {', '.join(RELAY_SHAPES)}")
    return shape


def on_demand_tunables(case, phone):
    """The launch tunables the on-demand shape implies for `phone` (empty for the static shape and non-participants).

    Every participant runs relayed calls (the callee's consent bit is on by default, a helper's "help" too). The caller
    requires exactly the requested number of relays, so a direct sighting of the callee is never dialled (direct fallback
    is a failed chain). The app's reach tables prefer the shortest path and a co-located lab hears the callee everywhere,
    so a chain of two or more relays masks direct sightings on the caller and on every relay except the last one, which
    dials the callee. Chains above the app's default cap raise it on every phone (helpers refuse a larger budget)."""
    if relay_shape(case) != "on_demand":
        return {}
    relays = call_relays(case)
    if phone not in (case["caller"], case["callee"], *relays):
        return {}
    implied = {"relayed_calls": True}
    if phone == case["caller"]:
        implied["relayed_calls_require"] = len(relays)
    if len(relays) > 1 and (phone == case["caller"] or phone in relays[:-1]):
        implied["relayed_calls_no_direct"] = True
    if len(relays) > APP_DEFAULT_RELAYS:
        implied["relayed_calls_max_relays"] = len(relays)
    return implied


def on_demand_pins(case, phone, prefixes):
    """The DEBUG route pins (AP8) for `phone` in the on-demand shape: the caller's first helper and each helper's next
    hop, from the registered signed-node prefixes. Another helping phone nearby can then never take the requested route
    (the app refuses a pinned route that does not exist; it never falls back). Empty for the static shape."""
    if relay_shape(case) != "on_demand":
        return {}
    route = [case["caller"], *call_relays(case), case["callee"]]
    if phone not in route[:-1]:
        return {}
    following = prefixes[route[route.index(phone) + 1]]
    if not re.fullmatch(r"[0-9a-f]{12}", following or ""):
        raise ValueError(f"On-demand pin needs the registered node prefix of {route[route.index(phone) + 1]}")
    return {"relayed_calls_helper": following} if phone == case["caller"] else {"relayed_calls_next_hop": following}


def on_demand_helper_shape(case, phone):
    """The launch case of a middle phone in the on-demand shape: an ordinary call-ready endpoint, no relay role."""
    tunables = {**case.get("tunables", {}), **(case.get("phone_tunables") or {}).get(phone, {}),
                **on_demand_tunables(case, phone)}
    return {"source": phone, "receivers": [], "transport": case["transport"],
            "profile": case["profiles"][case["caller"]], **({"tunables": tunables} if tunables else {})}


def validate_on_demand_case(case):
    """Refuse an on-demand request the app cannot route or the harness cannot prove, before any phone is touched."""
    relays = call_relays(case)
    if not relays:
        raise ValueError("relay_shape needs a relay chain (`relays`)")
    if case["transport"] == "classic":
        raise ValueError("On-demand relays are LE only (the app does not relay Classic on demand)")
    shared, per_phone = case.get("tunables") or {}, case.get("phone_tunables") or {}
    if any(isinstance(item, dict) and item.get("rotating_ids") is True for item in (shared, *per_phone.values())):
        raise ValueError("On-demand relays are measured with fixed discovery IDs only: a rotating helper or target "
                         "ID cannot be bound to a registered phone by this harness")
    for phone in (case["caller"], case["callee"], *relays):
        explicit = {**shared, **(per_phone.get(phone) or {})}
        for key, value in on_demand_tunables(case, phone).items():
            if key not in explicit:
                continue
            if (explicit[key] < value) if key == "relayed_calls_max_relays" else (explicit[key] != value):
                raise ValueError(f"relay_shape on_demand sets tunable {key}={value!r} on {phone}; "
                                 f"the case requests {explicit[key]!r}")
    wait = case.get("route_wait_s", ON_DEMAND_ROUTE_WAIT_S)
    if isinstance(wait, bool) or not isinstance(wait, (int, float)) or wait < 0:
        raise ValueError("route_wait_s must be a number of seconds")


def relay_launch_args(args, callee_prefix=None, route_cost=None):
    """Turn the broadcast source launch into the app's DEBUG private-call relay launch.

    MainActivity: test_auto_start + test_role=relay + test_private_call_relay scans for one private-call
    offer on test_transport (only the callee prefix `test_relay_callee` at `test_relay_route_cost` when
    given), authenticates that child, then advertises the callee's offer (route cost + 1) for its caller
    (BluetoothPrivateCallRelay)."""
    target = []
    if callee_prefix is not None:
        if not re.fullmatch(r"[0-9a-f]{12}", callee_prefix):
            raise ValueError("A relay target needs the callee's 12-digit signed-node prefix")
        target += ["--es", "test_relay_callee", callee_prefix]
    if route_cost is not None:
        if isinstance(route_cost, bool) or not isinstance(route_cost, int) or not 0 <= route_cost < APP_MAX_CALL_RELAYS:
            raise ValueError("A relay's child route cost must be within the app's relay chain")
        target += ["--ei", "test_relay_route_cost", str(route_cost)]
    args = list(args)
    index = args.index("-n") + 2
    head, tail, kept = args[:index], args[index:], []
    while tail:
        if len(tail) < 3 or not tail[0].startswith("--e"):
            raise ValueError(f"Unexpected launch extra layout: {tail[:3]!r}")
        flag, key, value, tail = tail[0], tail[1], tail[2], tail[3:]
        if key not in RELAY_DROPPED_EXTRAS:
            kept += [flag, key, value]
    return [*head, *kept, "--ez", "test_auto_start", "true", "--es", "test_role", "relay",
            "--ez", "test_private_call_relay", "true", *target]


def validate_call_case(case, devices):
    if {"replay_file", "replay_files", "replay_sha256", "replay_sha256s"}.intersection(case):
        raise ValueError("Encoded replay is not supported for private calls; no camera fallback")
    participants = {case["caller"], case["callee"]}
    if not set(case.get("media_impairments", {})) <= participants:
        raise ValueError("Call impairment must name an outgoing endpoint")
    if len(participants) != 2 or set(case["profiles"]) != participants:
        raise ValueError("A call needs distinct caller/callee and an independent profile for each")
    user_launch.validate_app_default(case)  # an app_default endpoint is a plain user-launch endpoint with no stated value
    if not set(case.get("raw_fixture_files", {})) <= participants:
        raise ValueError("Raw fixtures must belong to a call endpoint")
    if not set(case.get("raw_fixture_sha256s", {})) <= set(case.get("raw_fixture_files", {})):
        raise ValueError("Raw fixture hash guards must belong to a supplied call input")
    if not set(case.get("reference_audios", {})) <= set(case.get("raw_fixture_files", {})):
        raise ValueError("Reference audio must belong to a matching raw-video call endpoint")
    user_launch.resolve(case)  # a malformed user_settings is refused here, before any phone is touched
    relays = call_relays(case)
    user_launch.validate_per_phone(case, {*participants, *relays})
    if set(relays) & participants or len(set(relays)) != len(relays) or not set(relays) <= devices.keys():
        raise ValueError("A call relay must be a distinct registered phone, not an endpoint")
    for relay in relays:
        if not devices[relay].get("serial") or not re.fullmatch(r"[0-9a-f]{12}", devices[relay].get("node_prefix", "")):
            raise ValueError("A call relay needs a serial and a 12-digit signed-node prefix")
    if relays and any(len({devices[phone][field] for phone in {*participants, *relays}}) != len(participants) + len(relays)
                      for field in ("serial", "node_prefix")):
        raise ValueError("Aliases must not assign one physical phone to a relay and an endpoint")
    if relay_shape(case) == "on_demand":
        validate_on_demand_case(case)
    if relays and {"preserve_call_bitrate", "gatt_defer_connection_priority"} & set(case):
        raise ValueError("The call bitrate and GATT priority diagnostics are direct-call only")
    # Exact-rate (CBR/VBR) calls keep the GATT 180 / Classic 250 kbps starting budgets; this DEBUG
    # diagnostic challenges them on GATT and, since 2026-10-09, Classic.
    if "preserve_call_bitrate" in case and (
            not isinstance(case["preserve_call_bitrate"], bool) or case["transport"] not in ("gatt", "classic")):
        raise ValueError("Preserving selected call bitrate is a boolean direct GATT or Classic diagnostic")
    if "gatt_defer_connection_priority" in case and (
            not isinstance(case["gatt_defer_connection_priority"], bool) or case["transport"] != "gatt"):
        raise ValueError("Connection priority ordering is a boolean direct GATT diagnostic")
    if "video_presentation_timing" in case or "release_video_at_playout" in case:
        raise ValueError("Video presentation timing currently measures broadcast receivers only")
    if "call_pipeline_timing" in case and not isinstance(case["call_pipeline_timing"], bool):
        raise ValueError("Call pipeline timing must be a boolean")
    if "callee_idle_s" in case:
        idle = case["callee_idle_s"]
        if isinstance(idle, bool) or not isinstance(idle, (int, float)) or not 0 < idle <= 300:
            raise ValueError("callee_idle_s must be a number of seconds above 0 and at most 300")
        if relays:
            raise ValueError("callee_idle_s applies to direct calls: a relayed route launches the callee, the relays "
                             "and the caller in its own order")
    validate_dial_and_callee_state(case, devices, relays)
    for phone in participants:
        validate_case(broadcast_shape(case, phone), devices)
        profile = case["profiles"][phone]
        if user_launch.is_app_default(profile):
            continue  # the app resolves the profile and its encoder; the manifest records what it chose
        if not isinstance(profile.get("codec"), str) or not profile["codec"]:
            raise ValueError("Every endpoint must explicitly request its encoder")
        if profile["fps"] > 60 or profile["bitrate_kbps"] < 50:
            raise ValueError("Requested profile would be silently clamped by Android launch extras")
        if "thermal_reduction" in profile and not isinstance(profile["thermal_reduction"], bool):
            raise ValueError("Thermal reduction must be a boolean")
    if case.get("setup_action", "accept") not in {"accept", "decline", "cancel"}:
        raise ValueError("Setup action must be accept, decline, or cancel")
    if case.get("setup_action", "accept") != "accept" and case.get("actions"):
        raise ValueError("Media controls need an accepted call")
    for action in case.get("actions", []):
        at = action.get("at_s")
        if (action.get("phone") not in participants or isinstance(at, bool) or
                not isinstance(at, (int, float)) or not 0 <= at < case.get("duration_s", 60)):
            raise ValueError("Actions must target an endpoint during the measurement")
        if action.get("action") not in {"mute", "unmute", "video_off", "video_on", "flip",
                                         "background", "foreground", "end", "kill", *WIFI_ACTIONS, *intercom.PTT_ACTIONS}:
            raise ValueError("Unknown private-call action")
        if action.get("action") == "flip" and action["phone"] in case.get("raw_fixture_files", {}):
            raise ValueError("Recorded raw input cannot measure a physical camera flip")
    validate_wifi_actions(case)
    intercom.validate_intercom_case(case, participants)
    return participants | set(relays)


def validate_wifi_actions(case):
    """Wi-Fi toggles alternate per phone, starting with wifi_off (Wi-Fi is on when a case starts): an off while it is
    already off, or an on while it is on, is a case that does not say what it measures."""
    off = set()
    for action in sorted(case.get("actions", []), key=lambda item: item["at_s"]):
        name, phone = action.get("action"), action.get("phone")
        if name == "wifi_off":
            if phone in off:
                raise ValueError(f"{phone}: wifi_off while Wi-Fi is already planned off")
            off.add(phone)
        elif name == "wifi_on":
            if phone not in off:
                raise ValueError(f"{phone}: wifi_on needs an earlier wifi_off on the same endpoint")
            off.discard(phone)


def app_chosen_profile(rows):
    """What the app itself resolved for this endpoint's call, from its own lines (None before it logged any).

    `BENCHMARK call effective` is the profile the call started local media with (encoder, size, FPS, rate, allocation,
    playback buffer); `call selected` is the Settings-resolved request before the peer's negotiation; `encoder_choice
    role=call` is VideoCodecChoice.logLine (hardware, reason). Nothing here is a request: a case that takes the app's
    default records the app's own answer."""
    effective = profile_rows(rows, "effective")
    if not effective:
        return None
    first, selected = effective[0], (profile_rows(rows, "selected") or [{}])[0]
    choice = next((values for row in rows if (match := re.search(r"BENCHMARK encoder_choice: (role=call .*)", row["message"]))
                   and (values := {key: value.strip() for key, value in
                                   re.findall(r"(\w+)=([^|]+?)(?=\s*\||$)", match[1])})), {})

    def number(values, key):
        return int(values[key]) if re.fullmatch(r"\d+", values.get(key, "")) else None
    return {"encoder": first.get("encoder"), "codec": first.get("codec"), "width": number(first, "width"),
            "height": number(first, "height"), "fps": number(first, "fps"), "bitrate_kbps": number(first, "bitrateKbps"),
            "audio_kbps": number(first, "audioKbps"), "playback_ms": number(first, "playbackMs"),
            "rate_control": first.get("rateControl"),
            "selected": {key: value for key, value in {
                "encoder": selected.get("encoder"), "width": number(selected, "width"), "height": number(selected, "height"),
                "fps": number(selected, "fps"), "bitrate_kbps": number(selected, "bitrateKbps"),
                "rate_control": selected.get("rateControl"), "playback_ms": number(selected, "playbackMs")}.items()
                if value is not None},
            "encoder_choice": {key: choice[key] for key in ("encoder", "codec", "hardware", "reason") if key in choice}}


def app_chosen_profiles(manifest, base_path):
    """{phone: app_chosen_profile} for every app_default endpoint, read from the endpoint's own saved logs."""
    chosen = {}
    for phone in user_launch.app_default_phones(manifest["scenario"]):
        for spec in manifest.get("logs", []):
            if spec["device"] != phone:
                continue
            text = resolve(spec["path"], base_path).read_text(encoding="utf-8", errors="replace")
            found = app_chosen_profile(scope_records(records(text), spec)[0])
            if found:
                chosen[phone] = found
                break
    return chosen


def profile_rows(rows, kind):
    result = []
    for row in rows:
        match = re.search(rf"BENCHMARK call {kind}: (.*)", row["message"])
        if match:
            values = dict(re.findall(r"(\w+)=([^|]+?)(?=\s*\||$)", match[1]))
            values = {key: value.strip() for key, value in values.items()}
            result.append({"time": row["time"], **values})
    return result


def requested_matches(requested, observed):
    if not observed:
        return None
    expected = {actual: str(requested.get(key, "cbr" if key == "rate_control" else False
                                         if key == "thermal_reduction" else "")).lower()
                for key, actual in PROFILE_KEYS.items()}
    expected["playbackMs"] = str(PLAYBACK_MS[requested.get("playback", "balanced")])
    # The count of mismatching fields (0 = the selected profile is the requested one). Since fe2acce the app annotates
    # some values ("thermalReduction=false (setting=false)"), so only the value before a note is compared.
    return sum(observed.get(key, "").split(" (", 1)[0].strip().lower() != value for key, value in expected.items())


# Families the app-log gates of a call belong to (run_benchmark_suite.family_for). Every other family of the
# acceptance profile (picture, camera, acoustic, physical latency, endurance, ...) needs its own adapter.
LOG_GATE_FAMILIES = ("smoothness", "connection", "transport", "lifecycle", "topology")


def decided_gates(scores, lifecycle, relay_hops):
    """Every gate that decides a call's log status, flat: [{device, section, gate, status, reason?, value?, limit?}].

    The same rows `log_status` aggregates: each endpoint's own gates and the gates of its two media directions,
    each lifecycle action, and each relay hop. DIAGNOSTIC rows never decide and are left out."""
    rows = []

    def add(device, section, name, item, **extra):
        if item.get("status") == "DIAGNOSTIC":
            return
        rows.append({"device": device, "section": section, "gate": name, "status": item.get("status"),
                     **{key: item[key] for key in ("reason", "value", "limit") if item.get(key) is not None}, **extra})

    for endpoint in scores:
        sections = [("endpoint", endpoint["gates"])]
        if endpoint.get("media_expected", True):
            sections += [(direction, endpoint[direction]["gates"]) for direction in ("outgoing", "incoming")]
        for section, gates in sections:
            for name, item in gates.items():
                add(endpoint["device"], section, name, item)
    for item in lifecycle:
        open_evidence = sorted(name for name, proof in item.get("evidence", {}).items()
                               if isinstance(proof, dict) and proof.get("status") not in ("PASS", "DIAGNOSTIC"))
        # An action that is open because of its evidence says why (for example: log lines dropped by logd).
        why = next((proof["reason"] for proof in item.get("evidence", {}).values()
                    if isinstance(proof, dict) and proof.get("status") not in ("PASS", "DIAGNOSTIC") and proof.get("reason")), None)
        add(item.get("device"), "lifecycle", item["action"],
            {**item["gate"], "reason": item["gate"].get("reason") or item.get("reason") or why},
            **({"evidence": open_evidence} if open_evidence else {}))
    for hop in relay_hops:
        for name, item in hop["gates"].items():
            add(hop["device"], "relay", name, item)
    return rows


def setup_attempts_failed(rows, setup):
    """End lines of attempts that closed before this endpoint's measured call went ACTIVE: [{time, message}].

    The measured call is the first ACTIVE after the runner's dial (`setup`); without an ACTIVE line nothing is
    listed. A UI teardown record counts only when its call-thread record is absent, as everywhere else."""
    lower = timestamp(setup["time"]) if setup else float("-inf")
    active = next((row for row in rows if row["message"] == ACTIVE_PHASE and row["at"] >= lower), None)
    if active is None:
        return []
    callbacks = [row for row in rows if row["message"].startswith(ENDED_CALLBACK) and row["at"] < active["at"]]
    unpaired = [row for row in rows if row["message"].startswith(ENDED_UI) and row["at"] < active["at"] and not any(
        0 <= row["at"] - other["at"] <= ENDED_UI_PAIRING_S for other in callbacks)]
    return [{"time": row["time"], "message": row["message"]}
            for row in sorted([*callbacks, *unpaired], key=lambda item: item["at"])]


def score_call_manifest(manifest, base_path, thresholds=None):
    limits = limits_for(thresholds)
    case, scores = manifest["scenario"], []
    link, _ = acceptance.link_class(manifest)
    shared = {"media_kind": "call", "acceptance": manifest.get("acceptance"),
              "scenario": {"media_impairments": case.get("media_impairments")}}
    scopes = []
    voice = intercom.is_voice(case)
    clocks = intercom.clock_maps(manifest)
    relays, relay_scopes = call_relays(case), []
    dialed, device_drops = call_dialed(manifest), {}
    for spec in manifest.get("logs", []):
        text = resolve(spec["path"], base_path).read_text(encoding="utf-8", errors="replace")
        # The opaque relay holds no call media or keys: it is scored as a hop, never as an endpoint.
        (relay_scopes if spec["device"] in relays else scopes).append(
            {"spec": spec, "text": text, "rows": scope_records(records(text), spec)[0]})
    for scope in scopes:
        spec, text, rows = scope["spec"], scope["text"], scope["rows"]
        phone = spec["device"]
        peer = case["callee"] if phone == case["caller"] else case["caller"]
        requested = case["profiles"][phone]
        app_default = user_launch.is_app_default(requested)
        if app_default:
            requested = {}  # nothing was requested: the app resolved the profile itself (recorded as app_chosen_profile)
        identities = {item.get("callId") for item in profile_rows(rows, "identity") if item.get("callId")}
        peer_scopes = [item for item in scopes if item["spec"]["device"] == peer]
        if len(peer_scopes) > 1:
            peer_scopes = [item for item in peer_scopes if len(identities) == 1 and identities.intersection(
                row.get("callId") for row in profile_rows(item["rows"], "identity"))]
        peer_rows = [row for item in peer_scopes for row in item["rows"]]
        own = profile_rows(rows, "effective")
        remote = profile_rows(peer_rows, "effective")
        own_profile, remote_profile = (own[0] if own else {}), (remote[0] if remote else {})
        audio_profile = own_profile
        if voice:  # A voice call exercises no video profile; none of its video gates or expectations apply.
            own_profile = remote_profile = {}
        selected_profiles = profile_rows(rows, "selected")
        remote_headers = profile_rows(rows, "remote")
        # The endpoint's encoder FPS and the peer's visible FPS are independent.
        outgoing = score_log(text, {**spec, "role": "sender",
                             "expected_fps": int(own_profile["fps"]) if own_profile else None,
                             "expected_width": int(own_profile["width"]) if own_profile else None,
                             "expected_height": int(own_profile["height"]) if own_profile else None,
                             "expected_bitrate_kbps": int(own_profile["bitrateKbps"]) if own_profile else None},
                             {**shared, "planned_intervals": manifest.get("outgoing_intervals", []),
                              "control_events": manifest.get("control_events", []),
                              "source_kind": manifest.get("source_kinds", {}).get(phone,
                                  "live_camera" if manifest.get("source_kind") == "live_camera_bidirectional"
                                  else manifest.get("source_kind"))}, thresholds)
        incoming = score_log(text, {**spec, "role": "receiver",
                             "expected_fps": int(remote_profile["fps"]) if remote_profile else None},
                             {**shared, "planned_intervals": manifest.get("incoming_intervals", []),
                              "control_events": manifest.get("control_events", [])}, thresholds)
        if not voice:
            incoming["gates"]["profile_fps_mismatches"] = gate(
                sum(int(header["fps"]) != incoming["expected_fps"] for header in remote_headers)
                if remote_headers and incoming["expected_fps"] is not None and all(
                    re.fullmatch(r"\d+", header.get("fps", "")) for header in remote_headers) else None, 0,
                reason="A scoped authenticated private-call remote header is required to measure FPS")
        for result in (outgoing, incoming):
            for key in ("discovery_ms", "selection_to_first_surface_ms"):
                result["gates"].pop(key, None)
        peer_identities = {item.get("callId") for item in profile_rows(peer_rows, "identity") if item.get("callId")}
        call_id = next(iter(identities)) if len(identities) == 1 else None
        tx = [item for item in profile_rows(rows, "tx") if call_id and item.get("callId") == call_id]
        measured = [item for item in tx if re.fullmatch(r"\d+", item.get("queueDrops", ""))]
        final = [item for item in measured if item.get("final") == "true"]
        # Queue losses count actual discarded queued messages, before End cleanup.
        # Audio inputs precede batching; writer totals count successful application writes.
        drops = [int(match[1]) for row in rows if
                 (match := re.search(r"Call video recovered at a keyframe after (\d+) media drops", row["message"]))]
        if measured:
            drops = [int(item["queueDrops"]) for item in measured]
        total_drops = max(drops, default=None)
        complete = bool(final and final[-1] == measured[-1])
        outgoing["gates"]["sender_drops"] = gate(total_drops if complete or total_drops else None, 0,
            reason="A final cumulative TX report for the measured call is required to prove zero queue loss")
        outgoing["gates"]["call_tx_counter_regressions"] = gate(
            sum(int(b["queueDrops"]) < int(a["queueDrops"]) for a, b in zip(measured, measured[1:])) if measured else None, 0)
        outgoing["call_tx"] = {"call_id": call_id, "reports": tx, "final_report": final[-1] if complete else None,
            "queue_drop_unit": "Discarded queued media messages, including batched audio/config; excludes deliberate End cleanup",
            "input_unit": "Encoded callbacks before audio batching; muted/inactive callbacks are separate",
            "write_scope": "Successful application writer calls; receiver evidence is required for delivery"}
        rx = any("Call RX " in row["message"] for row in rows)
        if not rx:
            for key in ("audio_drops", "decoder_drops"):
                incoming["gates"][key] = gate(None, 0, reason="No measured Call RX counters")
        rx_final = [item for item in profile_rows(rows, "rx") if call_id and
                    item.get("callId") == call_id and item.get("final") == "true"]
        for key, field in (("audio_drops", "audioDrops"), ("decoder_drops", "decoderDrops")):
            values = [int(item[field]) for item in rx_final if re.fullmatch(r"\d+", item.get(field, ""))]
            prior = incoming["gates"][key].get("value")
            incoming["gates"][key] = gate(max([prior or 0, *values]) if values or prior else None, 0,
                reason="A final RX counter for the measured call is required to prove zero loss")
        incoming["call_rx"] = {"call_id": call_id, "final_reports": rx_final}
        if voice:
            for result in (outgoing, incoming):
                intercom.prune_voice(result)
            planned_loss = [item for item in manifest.get("incoming_intervals", []) if
                            item.get("device") == phone and item.get("action") == "planned_peer_loss"]
            outgoing["gates"].update(voice_video_packets_sent=intercom.video_sent_gate(rows, call_id),
                                     audio_packets_sent=intercom.audio_sent_gate(case, phone, rows, call_id))
            incoming["gates"].update(voice_video_packets_received=intercom.video_received_gate(rows, call_id),
                                     audio_packets_received=intercom.audio_received_gate(case, peer, rows, call_id),
                                     audio_receive_stall_windows=intercom.audio_stall_gate(case, peer, rows, call_id, planned_loss))
        for result in (outgoing, incoming):
            if limits["version"] == acceptance.V4:
                demote_user_gates(result["gates"], link)  # The counters above were rebuilt from final reports.
            result["log_status"] = aggregate_status(result["gates"].values())
            result["overall_status"] = aggregate_status([{"status": result["log_status"]},
                                                        {"status": result["quality_status"]},
                                                        {"status": result["lifecycle_status"]}])
        media_expected = case.get("setup_action", "accept") == "accept"
        authenticated = [match.groups() for row in rows if (match := re.search(LINK_LINE, row["message"]))]
        confirmed = [match[1] for row in rows if (match := re.search(IDENTITY_CONFIRMED, row["message"]))]
        prefix = manifest.get("devices", {}).get(peer, {}).get("node_prefix")
        transport_label = {"classic": "Classic", "ble": "LE L2CAP", "gatt": "BLE GATT"}[case["transport"]]
        peers = authenticated_peer_gate(authenticated, confirmed, prefix, manifest, phone, peer)
        links = gate(sum(transport_label.lower() not in description.lower() for _, description in authenticated)
                     if authenticated else None, 0)
        setup = next((item for item in manifest.get("setup_anchors", []) if item["device"] == phone), None)
        drops, drop_capture = manifest_log_drops(manifest, base_path, phone, spec.get("pid"))
        device_drops[phone] = (drops, spec.get("pid"))
        active_phase = phase_active(rows, drops, spec.get("pid"), setup, peer_rows, dialed)
        first_at, _ = first_surface_time(rows)
        startup_ms = (first_at - timestamp(setup["time"])) * 1000 if first_at is not None and setup else None
        header_mismatches = []
        if remote_profile:
            for header in remote_headers:
                header_mismatches.append(sum(header.get(key) != remote_profile.get(key)
                                             for key in ("codec", "width", "height", "fps")))
        gates = {"authenticated_peer": peers, "selected_transport": links,
                 "call_id_mismatches": gate(0 if identities == peer_identities and len(identities) == 1 else
                                             1 if identities and peer_identities else None, 0),
                 "discovery_ms": intercom.discovery_gate(case, manifest, phone, rows, peer_rows,
                                                         [prefix, *sorted(discovery_id_map(manifest, peer_scopes).get(peer, ()))],
                                                         DEFAULT_THRESHOLDS["max_discovery_ms"], clocks)}
        allocation_modes = [match[1] for row in rows if
                            (match := re.search(r"Call camera: Streaming .* mode (\d+|null)", row["message"]))]
        expected_mode = {"cbr": "2", "vbr": "1", "cbr_fd": "3"}.get(requested.get("rate_control", "cbr"))
        if media_expected and voice:
            gates.update(voice_call_type=intercom.local_call_type_gate(case, phone, rows, call_id),
                         voice_layout=intercom.voice_layout_gate(rows, call_id),
                         voice_no_video_pipeline=intercom.no_video_pipeline_gate(rows),
                         setup_to_active_ms=intercom.setup_to_active_gate(rows, setup, limits.get(intercom.VOICE_SETUP_LIMIT_KEY)))
            if active_phase.get("reason") == LOG_DROP_REASON:
                gates["setup_to_active_ms"]["reason"] = LOG_DROP_REASON
        elif media_expected:
            # An app_default endpoint requested no profile or allocation, so there is nothing for these two gates to match.
            if not app_default:
                gates["selected_profile_mismatches"] = gate(
                    sum(requested_matches(requested, item) for item in selected_profiles) if selected_profiles else None, 0)
            gates["negotiated_remote_header_mismatches"] = gate(sum(header_mismatches) if header_mismatches else None, 0)
            if not app_default:
                gates["actual_allocation_mismatches"] = gate(sum(mode != expected_mode for mode in allocation_modes)
                                                if allocation_modes and expected_mode else 0 if allocation_modes else None, 0)
            gates["setup_to_first_surface_ms"] = first_surface_gate(startup_ms, user_launch.is_user_launch(
                user_launch.phone_tunables(case, phone)))
        changes = {key: {"requested": value, "negotiated": own_profile.get(actual)}
                   for key, actual in PROFILE_KEYS.items() if key not in {"thermal_reduction"} and
                   (value := requested.get(key, "cbr" if key == "rate_control" else None)) is not None and
                   str(value).lower() != own_profile.get(actual, "").lower()} if own_profile and not app_default else {}
        if own_profile and not app_default and int(own_profile["playbackMs"]) != PLAYBACK_MS[requested.get("playback", "balanced")]:
            changes["playback"] = {"requested": requested.get("playback", "balanced"),
                                   "negotiated_ms": int(own_profile["playbackMs"])}
        if audio_profile and int(audio_profile["audioKbps"]) != 32:
            changes["audio_kbps"] = {"requested_default": 32, "negotiated": int(audio_profile["audioKbps"])}
        profile_evidence = [gates.get("selected_profile_mismatches", {}),
                            gates.get("actual_allocation_mismatches", {}),
                            outgoing["gates"].get("profile_resolution_mismatches", {}),
                            outgoing["gates"].get("profile_bitrate_mismatches", {})]
        coverage = "NOT_APPLICABLE_VOICE" if voice else "APP_DEFAULT" if app_default and own_profile else "MEASURED" if (
            own_profile and not changes and all(item.get("status") == "PASS" for item in profile_evidence)) else "UNMEASURED"
        if media_expected and drop_capture is not None:
            # Only with this PID's hash-bound logd drop capture can a missing ACTIVE line be judged at all.
            gates["call_phase_active"] = active_phase
        crash = app_crash_gate(manifest, phone, base_path)
        if crash is not None:  # a crash or ANR ends the call whether or not media was expected
            gates["app_crash_or_anr"] = crash
        saver = [item["spec"]["device"] for item in (*scopes, *relay_scopes) if user_launch.power_save_in_log(item["text"])]
        if saver and any(user_launch.wifi_requested(case, name) for name in (case["caller"], case["callee"], *relays)):
            # The app withholds Wi-Fi while Battery Saver is on (BENCHMARK doze_state powerSave=true): whatever the call
            # did, the Wi-Fi carrier or upgrade it asked for was not exercised, so it is never a Wi-Fi pass.
            gates["wifi_battery_saver"] = gate(None, 0, reason=(
                f"battery_saver_on: the app log of {', '.join(sorted(set(saver)))} shows powerSave=true, and the app "
                "withholds Wi-Fi in Battery Saver; the Wi-Fi request is UNMEASURED, not a pass"))
        for section in (gates, outgoing["gates"], incoming["gates"]):
            name_log_drops(section, drops, spec.get("pid"), spec.get("start"), setup, dialed)
        active_gates = [*gates.values(), *outgoing["gates"].values(), *incoming["gates"].values()] if media_expected else list(gates.values())
        scores.append({"device": phone, "path": spec["path"], "scope": outgoing["scope"],
                       "setup_role": "caller" if phone == case["caller"] else "callee",
                       "media_expected": media_expected, "peer": peer, "requested_profile": case["profiles"][phone], "selected_profiles": selected_profiles,
                       **({"app_chosen_profile": app_chosen_profile(rows)} if app_default and not voice else {}),
                       "effective_profiles": own, "remote_headers": remote_headers, "negotiated_changes": changes,
                       "actual_encoder_allocation_modes": allocation_modes,
                       "call_type": intercom.call_type(case),
                       "requested_profile_coverage": coverage, "authenticated_links": authenticated,
                       "outgoing": outgoing, "incoming": incoming, "gates": gates, "call_phase_active": active_phase,
                       # Counted by unexpected_events like any failure line; listed so a reader can tell a failed
                       # setup attempt from a call that dropped.
                       "setup_attempts_failed": setup_attempts_failed(rows, setup),
                       "log_drops": {"capture": drop_capture.get("path"), "verified": drop_capture.get("verified", True),
                                     "reports": drops} if drop_capture else None,
                       "log_status": aggregate_status(active_gates), "quality_status": "UNMEASURED"})
    release = release_join_call(case, manifest, scopes, scores, voice, dialed, device_drops)
    lifecycle, ptt_records = [], []
    scope_rows = {item["spec"]["device"]: item["rows"] for item in scopes}
    call_ids = {device: (lambda ids: next(iter(ids)) if len(ids) == 1 else None)(
        {item.get("callId") for item in profile_rows(rows, "identity") if item.get("callId")})
        for device, rows in scope_rows.items()}
    ptt_events = [item for item in manifest.get("control_events", []) if item["action"] in intercom.PTT_ACTIONS]
    for action in manifest.get("control_events", []):
        name = action["action"]
        evidence = {"app_ack": {"status": "PASS" if action.get("acknowledged") else "FAIL"}}
        if "acknowledged" not in action:
            # The runner never saw the app answer this control. When logd reported dropping that phone's lines
            # after the request, the answer may be among them: unmeasured, not a failed control.
            lost = drops_in_window(*device_drops.get(action.get("device"), ([], None)), action.get("time"))
            if lost:
                evidence["app_ack"] = {"status": "UNMEASURED", "reason": LOG_DROP_REASON, "reports": lost}
        if action.get("state") == "FAIL":
            evidence["state"] = {"status": "FAIL"}
        invite = name in {"invite", "invite_voice"} or name.startswith(("call:", "voice_call:"))
        if name in {"mute", "unmute", "video_off", "video_on", "kill"} or invite:
            evidence["remote_ack"] = {"status": "PASS" if action.get("remote_ack") else "UNMEASURED"}
        if invite:
            evidence["outgoing_phase"] = {"status": "PASS" if action.get("outgoing_phase") else "UNMEASURED"}
        if name in intercom.PTT_ACTIONS:
            ptt_evidence, record = intercom.ptt_event_evidence(action, case, scope_rows, clocks, call_ids, ptt_events)
            evidence.update(ptt_evidence)
            ptt_records.append(record)
        if name.startswith("voice_call:") or name == "invite_voice":
            offer = action.get("voice_offer")
            evidence["voice_offer_seen"] = {"status": "PASS" if offer else "UNMEASURED"}
        if name in {"video_on", "flip", "foreground"}:
            evidence["surface_recovery_ms"] = gate(action.get("surface_recovery", {}).get("ms"),
                                                    DEFAULT_THRESHOLDS["max_startup_ms"])
        if name in WIFI_ACTIONS:
            evidence["wifi_state"] = {"status": "PASS" if action.get("wifi_state_verified") else "FAIL"}
        if name == "wifi_off":
            # Reported, never a pass by itself: a call that was not on a Wi-Fi carrier has no fall back to measure.
            evidence["back_on_bluetooth"] = {"status": "PASS" if action.get("back_on_bluetooth") else "UNMEASURED",
                                             **({"ms": action["back_on_bluetooth"]["ms"]}
                                                if action.get("back_on_bluetooth") else {})}
        if name == "background":
            evidence["activity_background"] = {"status": "PASS" if action.get("lifecycle_time") else "UNMEASURED"}
        if name == "accept":
            surfaces = action.get("surface_evidence", {})
            # A voice call has no surface; its recorded evidence is each endpoint's ACTIVE phase.
            evidence["both_active_voice_media" if voice else "both_visible_surfaces"] = {"status": "PASS" if all(
                surfaces.get(phone) for phone in (case["caller"], case["callee"])) else "UNMEASURED",
                **({"reason": LOG_DROP_REASON, "log_loss": action["surface_log_loss"]}
                   if action.get("surface_log_loss") else {})}
        if name in {"end", "decline", "cancel", "kill"}:
            terminal = action.get("terminal_evidence", {})
            endpoints = ({case["caller"], case["callee"]} - {action["device"]}) if name == "kill" else {
                case["caller"], case["callee"]}
            evidence["ended_and_available"] = {"status": "PASS" if all(
                terminal.get(phone, {}).get("ended") and terminal.get(phone, {}).get("available")
                for phone in endpoints) else "UNMEASURED",
                **({"reason": LOG_DROP_REASON, "log_loss": action["terminal_log_loss"]}
                   if action.get("terminal_log_loss") else {})}
        lifecycle.append({"device": action["device"], "action": action["action"],
                          "acknowledged": action.get("acknowledged", False),
                          "remote_ack": action.get("remote_ack"), "state": action.get("state"),
                          "surface_recovery": action.get("surface_recovery"), "evidence": evidence,
                          "gate": {"status": aggregate_status(evidence.values())}})
    log_loss = confirmed_log_loss(manifest, base_path)
    # A stack that wedged during this run's own call makes that call FAIL (local_bt_stack_wedged), never BLOCKED.
    blocked = None if manifest.get("bluetooth_stack_wedges") else manifest.get("runner_blocked")
    if manifest.get("runner_error"):
        # A stop on a missing one-time line that logd confirmably dropped is unmeasured, never a failure.
        # A stop the phone's own Bluetooth stack caused is BLOCKED (the suite records it so), never an app FAIL.
        lifecycle.append({"action": "runner", "reason": manifest["runner_error"],
                          **({"gate": {"status": "UNMEASURED", "reason": f"BLOCKED {blocked['reason']}"}, "blocked": blocked}
                             if blocked else
                             {"gate": {"status": "UNMEASURED", "reason": LOG_DROP_REASON}, "log_loss": log_loss}
                             if log_loss else {"gate": {"status": "FAIL"}})})
    for wedge in manifest.get("bluetooth_stack_wedges", []):
        # The call ended as "peer stopped responding", but this phone's own stack main thread had stopped.
        lifecycle.append({"device": wedge["device"], "action": "bluetooth_stack", "reason": wedge["reason"],
                          "evidence": wedge, "gate": {"status": "FAIL", "reason": wedge["reason"]}})
    endpoint_active = {item["device"]: item["call_phase_active"] for item in scores}
    discovery_ids = discovery_id_map(manifest, [*scopes, *relay_scopes])
    relay_hops = [score_relay_hop(manifest, index, [item for item in relay_scopes if item["spec"]["device"] == relay],
                                  scope_rows, base_path, endpoint_active, discovery_ids)
                  for index, relay in enumerate(relays)]
    log_status = aggregate_status([*(dict(status=item["log_status"]) for item in scores),
                                   *(item["gate"] for item in lifecycle),
                                   *(dict(status=item["log_status"]) for item in relay_hops)])
    if not scores:
        log_status = "FAIL" if manifest.get("runner_error") and not log_loss and not blocked else "UNMEASURED"
    if manifest.get("bluetooth_stack_wedges"):
        log_status = "FAIL"
    ptt = {"ptt": intercom.ptt_summary(ptt_records)} if ptt_records else {}
    relayed = {"relays": relay_hops, "requested_route": call_route(case)} if relays else {}
    stack = {**({"blocked": blocked} if blocked else {}),
             **({"failure_reason": manifest["bluetooth_stack_wedges"][0]["reason"]}
                if manifest.get("bluetooth_stack_wedges") else {})}
    decided = decided_gates(scores, lifecycle, relay_hops)
    uncovered = [name for name in acceptance.families_for(limits["version"]) if name not in LOG_GATE_FAMILIES]
    return {"thresholds_version": limits["version"], "call_type": intercom.call_type(case), "log_status": log_status,
            **ptt, **relayed, **stack,
            **({"call_dialed": dialed} if dialed is not None else {}),
            **({"not_dialed": not_dialed_record(manifest)} if dialed is False else {}),
            # The call's state follows its automated gates: PASS only when every one was measured and passed.
            "quality_status": "UNMEASURED", "overall_status": log_status,
            "failed_gates": [item for item in decided if item["status"] == "FAIL"],
            "unmeasured_gates": [item for item in decided if item["status"] == "UNMEASURED"],
            "unmeasured_families": uncovered,
            "full_experience_status": aggregate_status([{"status": log_status}, {"status": "UNMEASURED"}]),
            "status_basis": "overall_status aggregates the automated app-log gates only (failed_gates and "
                            "unmeasured_gates name what decided it). The families in unmeasured_families need their "
                            "own adapters and are not covered by it; full_experience_status includes them",
            "devices": scores, "lifecycle": lifecycle,
            **({"release_join": release} if release else {}),
            "limitations": [*(["Voice call: no video gate is measured; a voice call proves no video was sent by packet counters and "
                               "the absence of camera, encoder and surface log lines. Acoustic quality and gaps under one 2 s RX report are UNMEASURED"]
                              if voice else []),
                            "Live camera is measured; physical motion and aligned optical quality are separate",
                            "Negotiated profiles do not count as execution of a higher requested profile",
                            "Missing final owned private-call TX counters remain unmeasured; writer success is not receiver delivery",
                            "Planned camera gaps affect only that outgoing stream and its peer's incoming view",
                            *(["Relayed call: endpoint gates are end-to-end; the relay is scored only by its authenticated "
                               "adjacent identities and stability. " + RELAY_COUNTERS_REASON] if relays else [])]}


def bind_relay_child(child, relay_rows, callee_rows, hops, joined_at):
    """Bind the child a relay logged ("Relaying ... between <parent> and <child>") to the callee, from the callee's own log.

    Returns {"method", ...} or {"method": None, "reason"}. `joined_at` is the join line's time on the relay's clock;
    every comparison with the callee uses the callee's own clock only (its ringing and the lines before it).

    * `pseudonym_own`: the callee logged `pseudonym_own: role=call | link=<hop> | id=<child>` for the id the relay named,
      inside the window before one of its INCOMING_RINGING lines that also shows a relayed call (`relayed_cap` with the
      requested hop count). The app names its own link identity: the strongest binding.
    * `rotid_target`: the last helper SETUP accepted before the join named `target:<id>`, `<id>` is the callee's own
      `rotid_own` of the epoch in force at its ringing (the last one logged before it), and the callee's log shows the
      incoming relayed call: its link line, `relayed_cap` with the requested hop count and INCOMING_RINGING in the window.
      The relay never names the pseudonym, so the proof is the callee's own ID in the SETUP's target plus the callee's own
      relayed ring; another phone's ID, an older epoch's ID or a callee that never rang does not bind."""
    rings = [row for row in callee_rows if re.search(CALLEE_RINGING, row["message"])]
    if not rings:
        return {"method": None, "reason": "the callee logged no incoming ring"}

    def window(ring):
        return [row for row in callee_rows if ring["at"] - BINDING_WINDOW_S <= row["at"] <= ring["at"] + 1.0]

    def relayed(rows):
        return any((match := re.search(RELAYED_CAP, row["message"])) and int(match[1]) == hops for row in rows)

    accepted = [(row, match[4]) for row in relay_rows
                if row["at"] <= joined_at and (match := re.search(SETUP_HELPER_ACCEPTED, row["message"])) and match[3] == "target"]
    for ring in reversed(rings):
        rows = window(ring)
        if not relayed(rows):
            continue
        own = [row for row in rows if (match := re.search(PSEUDONYM_OWN, row["message"])) and match[2] == child]
        if own:
            return {"method": "pseudonym_own", "child": child, "ringing_time": ring["time"], "line": own[-1]["message"]}
        if not accepted or not any(re.search(CALL_LINK_UP, row["message"]) for row in rows):
            continue
        target = accepted[-1][1]
        current = [match[2] for row in callee_rows if row["at"] <= ring["at"] and (match := re.search(ROTID_OWN, row["message"]))]
        if current and current[-1] == target:
            return {"method": "rotid_target", "child": child, "target": target, "ringing_time": ring["time"],
                    "setup": accepted[-1][0]["message"]}
    if not accepted:
        return {"method": None, "reason": "the helper logged no accepted SETUP for the callee before it joined"}
    return {"method": None, "reason": f"the callee's own log does not show an incoming relayed call from the ID "
                                      f"{accepted[-1][1]} the helper's SETUP targeted (its own ID, its ring, its link and "
                                      f"the relayed hop count {hops} are all required)"}


def first_hop_pseudonymous(case):
    """True when the caller runs BMH3, so its link to the first helper of an on-demand route is a declared throwaway."""
    merged = {**(case.get("tunables") or {}), **((case.get("phone_tunables") or {}).get(case["caller"]) or {})}
    # A user launch runs BMH3 by default (the shipped state), whatever the benchmark extra says.
    return merged.get("bmh3") is True or user_launch.is_user_launch(merged)


def discovery_id_map(manifest, scopes):
    """{phone: every discovery ID its own log announced}: the registry prefix plus each `rotid_own` rotating ID.

    A rotating phone (user launch, or the rotating_ids benchmark arm) is named in the scan, reach and SETUP lines by its
    current rotating ID; only the phone's own log binds that ID to it, so another phone's ID is never in the set."""
    result = {}
    for scope in scopes:
        phone = scope["spec"]["device"]
        prefix = manifest.get("devices", {}).get(phone, {}).get("node_prefix")
        found = {match[2] for row in scope["rows"] if (match := re.search(ROTID_OWN, row["message"]))}
        result.setdefault(phone, set()).update(found | ({prefix} if prefix else set()))
    return result


def on_demand_hop_gates(case, index, prefixes, relay_rows, caller_rows, ids=None):
    """The requested-parent proof of one hop of an on-demand route, from the helper's and the caller's own logs.

    Every helper must have accepted a SETUP whose budget is its place in the chain and whose next hop is the requested
    child (the next relay's offer, or the callee for the last relay). The first helper is also the one the caller's reach
    table selected: the caller logs the helper, the target and the relay count it asked for, and READY must prove the same
    count. A different helper, a candidate that moved the call to another helper, or a direct route is a failed chain.

    `ids` maps a phone to every discovery ID its own log announced (`discovery_id_map`): a rotating phone is named in
    these lines by its current rotating ID, never by the registry prefix."""
    route, count = call_route(case), len(call_relays(case))
    relay, child, callee = route[index + 1], route[index + 2], case["callee"]
    kind = "target" if index == count - 1 else "helper"
    want = (str(count - index), str(count - index - 1), kind)
    known = lambda phone: (ids or {}).get(phone) or {prefixes[phone]}
    accepted = [match.groups() for row in relay_rows if (match := re.search(SETUP_HELPER_ACCEPTED, row["message"]))]
    gates = {"helper_accepted_requested_next_hop": gate(
        sum(item[:3] != want or item[3] not in known(child) for item in accepted) if accepted and prefixes[child] else None, 0,
        reason="The helper logged no accepted SETUP for this call")}
    if index == 0:
        selected = [match.groups() for row in caller_rows if (match := re.search(ROUTE_CALLER, row["message"]))]
        moved = [match.groups() for row in caller_rows if (match := re.search(ROUTE_CANDIDATE, row["message"]))]
        ready = [match.groups() for row in caller_rows if (match := re.search(SETUP_CALLER_READY, row["message"]))]
        asked = lambda target, helper, budget: (target in known(callee) and helper in known(relay)
                                                and budget == str(count))
        wrong = sum(not asked(target, helper, budget) for target, helper, _, budget in selected) + sum(
            not asked(target, helper, budget) for _, helper, target, budget in moved)
        gates["caller_selected_requested_helper"] = gate(
            wrong if selected and prefixes[callee] and prefixes[relay] else None, 0,
            reason="The caller logged no relayed route selection (a direct call logs none)")
        gates["caller_ready_relay_count"] = gate(
            sum(int(relays) != count for _, relays in ready) if ready else None, 0,
            reason="The caller's relayed SETUP never reached READY")
    return gates


def fold_on_demand_route(route_gate, extra):
    """The requested-route gate of an on-demand hop: the join proof AND the helper/caller proofs."""
    if route_gate["status"] == "FAIL":
        return route_gate
    failed = sorted(name for name, item in extra.items() if item["status"] == "FAIL")
    if failed:
        return {"status": "FAIL", "value": 1, "limit": 0, "failed": failed,
                "reason": "The on-demand route differs from the requested chain: " + ", ".join(failed)}
    missing = sorted(name for name, item in extra.items() if item["status"] != "PASS")
    if route_gate["status"] == "PASS" and missing:
        return {"status": "UNMEASURED", "value": None, "limit": 0, "missing": missing,
                "reason": "On-demand route evidence is incomplete: " + ", ".join(missing)}
    return route_gate


PLANNED_TERMINAL_CONTROLS = ("end", "kill", "decline", "cancel")


def relay_closure_verdict(manifest, relay, scopes):
    """Every RELAY_CLOSED line of the relay's own log, split into planned and unplanned on the host clock.

    The live health check only sees a line while it polls, so a closure it missed (or one in a log read later)
    never became a health event and the gate read PASS (l2q27ab-20261110g cases 0001/0003/0005). A closure is
    planned when it follows a planned terminal control (end, kill, decline, cancel). The relay's clock and the
    controls' differ, so the closure's host time is the relay's launch (host clock stamped just after the phone's
    own time was read, so the mapping errs late, never early) plus the line's offset in that log. Returns
    (unplanned rows, rows that could not be placed)."""
    launches = {(item["device"], item["time"]): item["host_monotonic_ns"] for item in manifest.get("runner_events", [])
                if item.get("action") == "launch" and item.get("host_monotonic_ns") is not None}
    controls = [item for item in manifest.get("control_events", [])
                if item.get("action") in PLANNED_TERMINAL_CONTROLS and item.get("acknowledged") is True]
    first_planned = min((item["host_monotonic_ns"] for item in controls if item.get("host_monotonic_ns") is not None),
                        default=None)
    unclocked = any(item.get("host_monotonic_ns") is None for item in controls)
    unplanned, unplaced = [], []
    for item in scopes:
        start = item["spec"].get("start")
        launched = launches.get((relay, start))
        for row in item["rows"]:
            if not re.search(RELAY_CLOSED, row["message"]):
                continue
            if launched is None or start is None:
                unplaced.append(row)
                continue
            at_ns = launched + (row["at"] - timestamp(start)) * 1e9
            if first_planned is not None and at_ns >= first_planned:
                continue
            (unplaced if first_planned is None and unclocked else unplanned).append(row)
    return unplanned, unplaced


def relay_closure_gate(manifest, relay, scopes):
    if not scopes:
        return gate(None, 0, reason="No relay log was captured")
    unplanned, unplaced = relay_closure_verdict(manifest, relay, scopes)
    # A fatal exception (or any other health event) the live check recorded is not a RELAY_CLOSED line.
    others = [item for item in manifest.get("health_events", [])
              if item.get("device") == relay and not item.get("planned") and not re.search(RELAY_CLOSED, item.get("message", ""))]
    count = len(unplanned) + len(others)
    if count:
        result = gate(count, 0)
        result["lines"] = [row["message"] for row in unplanned] + [item.get("message") for item in others]
        return result
    if unplaced:
        return {"status": "UNMEASURED", "value": None, "limit": 0,
                "reason": "A relay closure is logged but cannot be placed against the planned End on the host clock "
                          "(no launch or control host timestamp)", "lines": [row["message"] for row in unplaced]}
    return gate(0, 0)


def score_relay_hop(manifest, index, scopes, endpoint_rows, base_path=None, endpoint_active=None, discovery_ids=None):
    """Prove one requested opaque hop: route[index] -> relay -> route[index + 2], with no shortcut.

    Each relay logs the authenticated link identities of its child (after connecting to it) and of its
    parent (when it joins them). The callee accepts exactly one call connection and stops advertising once
    it holds it, and each relay stops advertising once its parent connects, so a chain in which every relay
    joined its requested parent to its requested child carries the only call link of both endpoints. An
    active call without every join cannot have used the requested chain: a shortcut or direct fallback is a
    failed requested chain, never a pass.

    The on-demand shape (production's helper path) logs the same join line but no RELAY_CALLEE one, so the child
    identity is the join line's second prefix, and the hop is additionally proven from the caller's selected helper
    and each helper's accepted SETUP (`on_demand_hop_gates`)."""
    case = manifest["scenario"]
    route = call_route(case)
    on_demand = relay_shape(case) == "on_demand"
    relay, parent, child = route[index + 1], route[index], route[index + 2]
    prefixes = {phone: manifest.get("devices", {}).get(phone, {}).get("node_prefix") for phone in route}
    rows = [row for item in scopes for row in item["rows"]]
    join_rows = [(row, match.groups()) for row in rows if (match := re.search(RELAY_JOINED, row["message"]))]
    joined = [groups for _, groups in join_rows]
    child_auth = [pair[1] for pair in joined] if on_demand else [
        match[1] for row in rows if (match := re.search(RELAY_CALLEE, row["message"]))]
    # A user launch's callee is named by a per-call pseudonym only its own log can bind (bind_relay_child).
    bindings = {}
    if on_demand and child == case["callee"]:
        for row, groups in join_rows:
            if groups[1] != prefixes[child]:
                bindings[groups[1]] = bind_relay_child(groups[1], rows, endpoint_rows.get(child, []),
                                                       len(call_relays(case)), row["at"])
    bound = {node for node, found in bindings.items() if found["method"]}
    # BMH3 makes the caller's link to its first helper present a declared throwaway (Bmh3Policy.relayLinkOptions): that
    # helper cannot know its parent, so the parent identity is unmeasurable there (the call's endpoints still prove each other).
    parent_provable = not (on_demand and index == 0 and first_hop_pseudonymous(case))
    closures = [row for row in rows if re.search(RELAY_CLOSED, row["message"])]
    # ACTIVE counts on both endpoints. A line logd dropped makes the gate UNMEASURED, never FAIL or PASS.
    active = {phone: any(ACTIVE_PHASE in row["message"] for row in endpoint_rows.get(phone, []))
              for phone in (case["caller"], case["callee"])}
    phase_evidence = endpoint_active or {}
    endpoint_loss = [phone for phone in (case["caller"], case["callee"])
                     if (phase_evidence.get(phone) or {}).get("reason") == LOG_DROP_REASON]
    relay_loss = []
    for item in scopes:
        drops, _ = manifest_log_drops(manifest, base_path, relay, item["spec"].get("pid"))
        own = [row for row in item["rows"] if re.search(SETUP_HELPER_ACCEPTED if on_demand else RELAY_CALLEE, row["message"])]
        relay_loss += drops_in_window(drops, item["spec"].get("pid"), own[-1]["time"] if own else item["spec"].get("start"))
    expected_child, expected_parent = prefixes[child], prefixes[parent]
    gates = {
        "relay_adjacent_child_identity": gate(
            sum(node != expected_child and node not in bound for node in child_auth) if child_auth and expected_child else None, 0,
            reason="The relay logged no authenticated child link"),
        "relay_adjacent_parent_identity": gate(
            sum(pair[0] != expected_parent or (pair[1] != expected_child and pair[1] not in bound) for pair in joined)
            if joined and expected_parent and expected_child else None, 0,
            reason="The relay logged no authenticated parent joined to its child") if parent_provable else {
            "status": "UNMEASURED", "value": None, "limit": 0,
            "reason": "BMH3 gives the first helper's parent link a throwaway identity: the helper cannot name its caller"},
        "relay_unplanned_closures": relay_closure_gate(manifest, relay, scopes),
    }
    if joined:
        route_gate = gate(0 if gates["relay_adjacent_child_identity"]["status"] == "PASS" and
                          (gates["relay_adjacent_parent_identity"]["status"] == "PASS" or not parent_provable) else 1, 0)
    elif any(active.values()) and relay_loss:
        route_gate = {"status": "UNMEASURED", "value": None, "limit": 0, "reason": LOG_DROP_REASON,
                      "detail": "The relay's one-time join line is absent and logd dropped relay lines after its child link",
                      "reports": relay_loss}
    elif any(active.values()):
        route_gate = {"status": "FAIL", "value": 1, "limit": 0,
                      "reason": "The call became active without this requested relay joining it: a shortcut, "
                                "direct fallback or unknown path is a failed requested chain"}
    else:
        route_gate = gate(None, 0, reason=LOG_DROP_REASON if endpoint_loss else
                          "The call never became active through the requested chain")
    if on_demand:
        extra = on_demand_hop_gates(case, index, prefixes, rows, endpoint_rows.get(case["caller"], []), discovery_ids)
        gates.update(extra)
        route_gate = fold_on_demand_route(route_gate, extra)
    gates["requested_relay_route"] = route_gate
    crash = app_crash_gate(manifest, relay, base_path)
    if crash is not None:
        gates["app_crash_or_anr"] = crash
    counters = {"relay_forwarded_records": gate(None, 0, reason=RELAY_COUNTERS_REASON),
                "relay_queue_depth": gate(None, 0, reason=RELAY_COUNTERS_REASON)}
    return {"device": relay, "role": "call_relay", "hop": index + 1, "parent": parent, "child": child,
            "node_prefix": prefixes[relay], "paths": [item["spec"]["path"] for item in scopes],
            "authenticated_child_links": child_auth, "authenticated_joins": [list(item) for item in joined],
            **({"relay_child_binding": {node: found for node, found in bindings.items()}} if bindings else {}),
            "closure_lines": [{"time": row["time"], "message": row["message"]} for row in closures],
            "endpoint_active": active, "endpoint_phase_active": phase_evidence, "gates": gates, "forwarding": counters,
            "log_status": aggregate_status(gates.values())}


class CallRunner(Runner):
    def __init__(self, adb, devices, case, output):
        validate_call_case(case, devices)
        if relay_chain_limit(case):
            raise ValueError(relay_chain_limit(case))  # never touch a phone for a chain the app cannot build
        super().__init__(adb, devices, broadcast_shape(case, case["caller"]), output)
        self.case = self.settings_case = case
        self.pin_records = []  # every route pin set at a launch: who pins whom, by which ID, and where the ID came from
        self.endpoints = set(self.participants)
        self.relays = call_relays(case)
        self.relay_launch = None
        self.relay_target = None
        self.relay_events = []
        # Relays are participants for inventory, APK identity, health, log capture and cleanup only.
        self.participants = self.endpoints | set(self.relays)
        self.raw_fixtures = {phone: raw_fixture_metadata(broadcast_shape(case, phone)) for phone in self.endpoints}
        self.reference_audios = {phone: reference_audio_metadata(broadcast_shape(case, phone)) for phone in self.endpoints}
        self.media_impairments = {phone: media_impairment_metadata(broadcast_shape(case, phone)) for phone in self.endpoints}
        self.control_events, self.setup_anchors = [], []
        self.outgoing_intervals, self.incoming_intervals = [], []
        self.video_off_intervals, self.background_intervals = {}, {}
        # Every endpoint a planned wifi_off names gets Wi-Fi back at the case's end, executed or not.
        self.wifi_restore_phones = {item["phone"] for item in case.get("actions", []) if item.get("action") == "wifi_off"}
        self.wifi_restored = []
        self.expected_terminal = False
        self.brackets = []
        self.dump_polled = {}
        self.stack_tails, self.stalled_seen = {}, {}
        self.dial_ids = {}
        self.child_bindings = {}  # relay -> how its logged child was bound to the callee (bind_relay_child)
        self.callee_idle = None  # the idle callee record of the manifest (`callee_idle_s`)
        self.callee_state_record = None  # what `callee_state` did to the callee and the phone's own proof of it
        # Per phone, on its own clock: the first sign of the dialled attempt, and the first media of the active call.
        self.attempt_seen, self.call_evidence = {}, {}

    def ptt_scheduled(self):
        return any(item.get("action") in intercom.PTT_ACTIONS for item in self.case.get("actions", []))

    def device_log(self, phone):
        """The newest log entry of a phone: a pin rebind relaunches a phone, which then has more than one."""
        return next(item for item in reversed(self.logs) if item["device"] == phone)

    def log_text(self, phone):
        return (self.output / self.device_log(phone)["path"]).read_text(encoding="utf-8", errors="replace")

    def wait_for(self, phone, expression, since=None, timeout=None, drops_after=None):
        deadline = time.monotonic() + (timeout or self.case.get("connection_timeout_s", 30))
        while time.monotonic() < deadline:
            self.check_cancelled()
            found = [row for row in records(self.log_text(phone)) if
                     (since is None or row["at"] >= timestamp(since)) and re.search(expression, row["message"])]
            if found:
                return found[0]
            for endpoint in sorted({row["device"] for row in self.logs}):
                rejected = next((row for row in records(self.log_text(endpoint)) if
                                 row["message"].startswith("BENCHMARK native call input rejected:")), None)
                if rejected:
                    raise RuntimeError(f"{endpoint}: {rejected['message']}")
            if "FATAL EXCEPTION" in self.log_text(phone):
                raise RuntimeError(f"{phone}: call application crashed")
            self.supervise_captures()
            found = self.poll_dump_evidence(phone, expression, since)
            if found:
                return found
            if AVAILABLE_EXPRESSION in expression:
                self.check_availability_blocked(phone, since)
            time.sleep(0.2)
        # The live stream is not a clock: it can attach after an early line, and on the A15s (Android 10)
        # it ran 4-21 s behind the app (voice-speech-s24a15s-51cc8028), longer than a 5 s acknowledgement
        # wait. Before failing, take one bounded `logcat -d --pid` dump and search (a) the live capture
        # again, because the line may have arrived while the dump ran, and (b) the complete dump. Both
        # keep the same gates as the live wait: this execution's PID and a time after `since`.
        found = self.late_evidence(phone, expression, since)
        if found:
            return found
        # A one-time line logd dropped (its liblog report follows `since`) is unmeasured, not absent.
        message = f"{phone}: no app evidence for {expression!r} within the deadline"
        raise self.log_loss_error(phone, message, expression, drops_after or since) or RuntimeError(message)

    def stack_watch_text(self, phone):
        """The listen-related lines of the phone's live Bluetooth stack capture, read incrementally (it can be 30 MB)."""
        log = next((item for item in reversed(self.stack_logs) if item["device"] == phone and item.get("path")), None)
        if log is None:
            return ""
        path = self.output / log["path"]
        offset, kept = self.stack_tails.get(path, (0, []))
        try:
            with path.open("rb") as handle:
                handle.seek(offset)
                chunk = handle.read()
        except OSError:
            return "\n".join(kept)
        end = chunk.rfind(b"\n") + 1
        for line in chunk[:end].decode("utf-8", errors="replace").splitlines():
            if any(marker in line for marker in STACK_WATCH_LINES):
                kept.append(line)
        self.stack_tails[path] = (offset + end, kept)
        return "\n".join(kept)

    def check_availability_blocked(self, phone, since):
        """Fail fast instead of spending the availability deadline: the app reported a stalled or exhausted CoC
        listen, or the stack began a listen for this app process and allocated nothing within 3 s."""
        lower = None if since is None else timestamp(since)
        pid = int(self.pids.get(phone) or 0) or None
        found = stack_health.app_availability_block(self.log_text(phone), lower, pid)
        if found:
            raise StackBlocked(phone, *found)
        pending = stack_health.pending_listen(self.stack_watch_text(phone), pid, lower)
        if pending is None:
            return
        first = self.stalled_seen.setdefault((phone, pending["time"]), time.monotonic())
        if pending["log_past_grace"] or time.monotonic() - first >= stack_health.LISTEN_GRACE_S:
            raise StackBlocked(phone, stack_health.LISTEN_STALLED,
                               f"stack began an LE CoC listen at {pending['time']} (binder thread {pending['tid']}) "
                               f"and allocated no socket within {stack_health.LISTEN_GRACE_S:g} s")

    def detect_stack_wedges(self):
        """Post-run: a "peer stopped responding" end that this phone's own wedged stack main thread explains."""
        wedges = []
        for stack in self.stack_logs:
            if not stack.get("path"):
                continue
            phone = stack["device"]
            try:
                stack_text = (self.output / stack["path"]).read_text(encoding="utf-8", errors="replace")
                app_text = "".join((self.output / log["path"]).read_text(encoding="utf-8", errors="replace")
                                   for log in self.logs if log["device"] == phone)
            except OSError:
                continue
            wedge = stack_health.detect_wedge(stack_text, app_text)
            if wedge:
                wedges.append({"device": phone, "stack_log": stack["path"], **wedge})
        return wedges

    DUMP_POLL_S = 3.0

    def live_capture(self, phone):
        log = next((item for item in reversed(self.logs) if item["device"] == phone), None)
        capture = self.captures[self.logs.index(log)][0] if log is not None and self.logs.index(log) < len(self.captures) else None
        return capture if isinstance(capture, LogCapture) else None

    def poll_dump_evidence(self, phone, expression, since):
        """While the live stream has been silent for more than 3 s, look in the device buffer every 3 s
        rather than only at the deadline (the A15s stream stalls or runs seconds behind the app)."""
        capture = self.live_capture(phone)
        now = time.perf_counter()
        if capture is None or capture.silent_s() <= self.DUMP_POLL_S or \
                now - self.dump_polled.get(phone, float("-inf")) < self.DUMP_POLL_S:
            return None
        found = self.late_evidence(phone, expression, since, phase="poll")
        self.dump_polled[phone] = time.perf_counter()
        return found

    def late_evidence(self, phone, expression, since, phase="deadline"):
        pid = int(self.pids.get(phone) or 0)
        lower = None if since is None else timestamp(since)
        def matches(row):
            return ((not pid or row["pid"] == pid) and (lower is None or row["at"] >= lower)
                    and re.search(expression, row["message"]))
        every = self.dump_log_lines(phone)[0]
        live = next((row for row in records(self.log_text(phone)) if matches(row)), None)
        source, row = ("live_capture_after_deadline", live) if live else (
            "logcat_dump", next((row for row in records(chr(10).join(every)) if matches(row)), None))
        if row is None:
            return None
        action = "evidence_after_deadline" if phase == "deadline" else "evidence_from_dump_poll"
        self.events.append({"device": phone, "action": action, "source": source, "pid": pid,
                            "pattern": expression, "app_time": row["time"], "since": since,
                            "reason": "Live logcat stream lagged behind the app; the line carries the app's own timestamp",
                            "host_monotonic_ns": time.monotonic_ns()})
        return {**row, "source": source}

    def launch(self, phone, source=False):
        # Reuse inventory/wake safety and scoped log capture from the broadcast runner.
        original_case = self.case
        shaped = broadcast_shape(original_case, phone)
        pins = on_demand_pins(original_case, phone, self.pin_prefixes(original_case, phone))
        if pins:
            shaped = {**shaped, "tunables": {**shaped.get("tunables", {}), **pins}}
        self.case = {**shaped, "media_trace": original_case.get("media_trace", False),
                     "preserve_call_bitrate": original_case.get("preserve_call_bitrate", False),
                     "gatt_defer_connection_priority": original_case.get("gatt_defer_connection_priority", False),
                     "call_pipeline_timing": original_case.get("call_pipeline_timing", False)}
        self.raw_fixture = self.raw_fixtures[phone]
        self.reference_audio = self.reference_audios[phone]
        self.media_impairment = self.media_impairments[phone]
        try:
            self.check_cancelled()
            super().launch(phone, source=True)
        finally:
            self.case = original_case

    def launch_relay(self, phone):
        """Launch the opaque call relay through the shared wake/launch/log-capture path."""
        original_case = self.case
        self.case = relay_launch_case(original_case, phone)
        self.raw_fixture = self.reference_audio = self.media_impairment = None
        trace_session, self.trace_session = self.trace_session, None  # the relay decodes no media
        callee, route_cost = relay_child_offer(original_case, phone)
        # The relay takes only its requested child's offer: the callee's prefix at that child's route cost.
        self.relay_target = (self.devices[callee]["node_prefix"], route_cost)
        self.relay_launch = phone
        try:
            self.check_cancelled()
            Runner.launch(self, phone, source=True)
        finally:
            self.case, self.relay_launch, self.trace_session = original_case, None, trace_session
            self.relay_target = None
        self.logs[-1]["role"] = "call_relay"

    def launch_helper(self, phone):
        """Launch a middle phone as an ordinary call-ready helper (on-demand shape): the endpoint every phone runs, with
        relayed calls on. No DEBUG relay role: it relays only when a caller's SETUP arrives."""
        original_case = self.case
        pins = on_demand_pins(original_case, phone, self.pin_prefixes(original_case, phone))
        self.case = on_demand_helper_shape(original_case, phone)
        self.case["tunables"] = {**self.case.get("tunables", {}), **pins}
        self.raw_fixture = self.reference_audio = self.media_impairment = None
        trace_session, self.trace_session = self.trace_session, None  # a helper decodes no media
        try:
            self.check_cancelled()
            Runner.launch(self, phone, source=True)
        finally:
            self.case, self.trace_session = original_case, trace_session
        self.logs[-1]["role"] = "call_relay"

    def adb(self, phone, *args, timeout=20):
        # The existing launch path supplies exact settings; replace only its mode flag.
        args = list(args)
        if "test_auto_broadcast" in args and phone == self.relay_launch:
            return super().adb(phone, *relay_launch_args(args, *(self.relay_target or (None, None))), timeout=timeout)
        if "test_auto_broadcast" in args:
            args[args.index("test_auto_broadcast")] = "test_private_call_browser"
            if self.case.get("preserve_call_bitrate"):
                args.extend(("--ez", "test_preserve_call_bitrate", "true"))
            if self.case.get("gatt_defer_connection_priority"):
                args.extend(("--ez", "test_gatt_defer_connection_priority", "true"))
            if self.case.get("call_pipeline_timing"):
                args.extend(("--ez", "test_call_pipeline_timing", "true"))
        return super().adb(phone, *args, timeout=timeout)

    def control(self, phone, action, via="intent"):
        when = self.phone_time(phone)
        event = {"device": phone, "action": action, "time": when, "host_monotonic_ns": time.monotonic_ns()}
        if via != "intent":
            event["via"] = via
        self.control_events.append(event)
        if via == "broadcast":
            # Reaches the running app without bringing it to the front. Only an app that logs it handled the broadcast
            # counts: a build without the DEBUG receiver would otherwise leave the invite unsent for the 5 s ack wait.
            self.adb(phone, "shell", "am", "broadcast", "-a", DIAL_BROADCAST_ACTION, "-p", PACKAGE,
                     "--es", "test_control", action)
            try:
                handled = self.wait_for(phone, DIAL_BROADCAST_HANDLED.format(control=re.escape(action)), when, timeout=5)
            except LogLinesDropped:
                raise
            except RuntimeError as error:
                self.control_events.remove(event)  # nothing was dialed: the run must not look dialed
                raise applied_arms.ArmBlocked(
                    phone, "dial_via_not_handled",
                    f"dial_via broadcast: the app logged no `BENCHMARK control_broadcast: control={action} | handled=true` "
                    f"line for the DEBUG broadcast {DIAL_BROADCAST_ACTION} (a build without the receiver ignores it): "
                    f"{error}") from error
            event["broadcast_handled"] = {"time": handled["time"], "message": handled["message"]}
        else:
            self.adb(phone, "shell", "am", "start", "-n", COMPONENT, "--es", "test_control", action)
        result = self.wait_for(phone, rf"BENCHMARK control {re.escape(action)} (?:applied|rejected)", when, timeout=5)
        event.update(acknowledged=result["message"].endswith("applied"), ack_time=result["time"])
        if result.get("source"):
            event["ack_source"] = result["source"]  # absent = seen on the live capture inside the deadline
        if not event["acknowledged"]:
            event["state"] = "FAIL"
            raise RuntimeError(f"{phone}: application rejected {action}")
        return event

    def invite(self):
        caller, callee = self.case["caller"], self.case["callee"]
        voice = intercom.is_voice(self.case)
        # voice_call:PREFIX is the experimental voice invite (Invite then Camera off); call:PREFIX is a video call.
        via = self.case.get("dial_via", "intent")  # a case without dial_via sends the intent exactly as before
        event = self.control(caller, ("voice_call:" if voice else "call:") + self.dial_prefix(caller, callee),
                             **({"via": via} if via != "intent" else {}))
        # From the control request, not its ack: a relayed call logs the phase 30-70 ms before the ack.
        outgoing = self.wait_for(caller, r"BENCHMARK call phase (?:OUTGOING_INVITE|OUTGOING_RINGING)", event["time"])
        event["outgoing_phase"] = {"time": outgoing["time"], "message": outgoing["message"]}
        ringing = self.wait_for(callee, r"BENCHMARK call phase INCOMING_RINGING")
        event["remote_ack"] = {"device": callee, "time": ringing["time"], "message": ringing["message"]}
        self.attempt_seen = {caller: outgoing["time"], callee: ringing["time"]}
        if voice:
            # The authenticated Camera off that follows the invite is the callee's voice offer.
            # The callee logs the offer on its link thread, sometimes just before INCOMING_RINGING on the
            # UI thread (M31 24 ms earlier, voice-ptt-51cc8028), so search from the invite, not the ring.
            offer = self.wait_for(callee, r"BENCHMARK call_type offer: .*offered=voice", event["ack_time"])
            event["voice_offer"] = {"device": callee, "time": offer["time"], "message": offer["message"]}
        return event

    def clock_brackets(self, phone, label, samples=5):
        """adb `date` calls bracketed by host time; PTT latency joins two phones' clocks only through these."""
        stamp = lambda: dt.datetime.now().strftime("%m-%d %H:%M:%S.%f")[:-3]
        result = []
        for _ in range(samples):
            before = stamp()
            value = self.phone_time(phone)
            result.append({"device": phone, "label": label, "host_before": before, "host_after": stamp(),
                           "device_time": value})
        return result

    VOLUME_DOWN_PRESSES = 8

    def lower_volume(self, phone):
        """Owner rule: every test phone stays at its lowest volume. One `input keyevent` with several key codes is one
        adb round trip, so the presses cost almost nothing inside the measured window. Volume-down only (never
        up, never the alarm stream); an OEM that refuses shell input is recorded and the call goes on."""
        event = {"device": phone, "action": "volume_down", "presses": self.VOLUME_DOWN_PRESSES}
        try:
            self.adb(phone, "shell", "input", "keyevent", *["KEYCODE_VOLUME_DOWN"] * self.VOLUME_DOWN_PRESSES, timeout=10)
        except Exception as error:
            event.update(action="volume_down_failed", reason=str(error)[:200])
        self.events.append({**event, "host_monotonic_ns": time.monotonic_ns()})

    def action(self, action):
        phone, name = action["phone"], action["action"]
        peer = self.case["callee"] if phone == self.case["caller"] else self.case["caller"]
        before_remote = self.phone_time(peer)
        print(f"{phone}: planned call {name}", flush=True)
        if name == "kill":
            self.expected_terminal = True
            event = {"device": phone, "action": name, "time": self.phone_time(phone), "acknowledged": True,
                     "host_monotonic_ns": time.monotonic_ns()}
            self.control_events.append(event)
            self.adb(phone, "shell", "am", "force-stop", PACKAGE)
            self.expected_stopped.add(phone)
            ended = self.wait_for(peer, ENDED_EVIDENCE, before_remote)
            available = self.wait_for(peer, r"BENCHMARK call available:", ended["time"], timeout=10)
            for intervals in (self.incoming_intervals, self.outgoing_intervals):
                intervals.append({"device": peer, "action": "planned_peer_loss", "start": before_remote,
                                  "end": ended["time"], "source": "runner_planned_kill"})
            event["remote_ack"] = {"device": peer, "time": ended["time"], "message": ended["message"]}
            event["terminal_evidence"] = {peer: ended_proof(ended, available)}
            return
        if name in WIFI_ACTIONS:
            self.wifi_action(phone, name)
            return
        if name == "end":
            self.expected_terminal = True
        event = self.control(phone, name)
        if name in intercom.PTT_ACTIONS:
            pressed = name == "ptt_press"
            logged = self.wait_for(phone, rf"BENCHMARK call_ptt: .*action={'press' if pressed else 'release'} ", event["time"], timeout=5)
            changed = "changed=true" in logged["message"]
            event["ptt"] = {"time": logged["time"], "message": logged["message"], "changed": changed}
            if changed:  # Only a real mute transition sends an authenticated Microphone signal.
                remote = self.wait_for(peer, rf"BENCHMARK call remote_mute muted={str(not pressed).lower()}",
                                       before_remote, timeout=5)
                event["remote_ack"] = {"device": peer, "time": remote["time"], "message": remote["message"]}
        elif name in {"mute", "unmute", "video_off", "video_on"}:
            marker = (f"BENCHMARK call remote_mute muted={str(name == 'mute').lower()}" if name in {"mute", "unmute"}
                      else f"BENCHMARK call remote_camera enabled={str(name == 'video_on').lower()}")
            remote = self.wait_for(peer, re.escape(marker), before_remote, timeout=5)
            event["remote_ack"] = {"device": peer, "time": remote["time"], "message": remote["message"]}
            if name == "video_off":
                self.video_off_intervals[phone] = (event["ack_time"], remote["time"])
            elif name == "video_on" and phone in self.video_off_intervals:
                surface = self.wait_for(peer, r"BENCHMARK call first_surface:", remote["time"], timeout=5)
                local_start, remote_start = self.video_off_intervals.pop(phone)
                self.outgoing_intervals.append({"device": phone, "action": "camera_off", "start": local_start,
                                                "end": event["ack_time"], "source": "app_control_ack"})
                self.incoming_intervals.append({"device": peer, "action": "remote_camera_off", "start": remote_start,
                                                "end": surface["time"], "source": "authenticated_remote_camera_and_surface"})
                event["surface_recovery"] = {"device": peer, "time": surface["time"],
                                             "ms": (surface["at"] - remote["at"]) * 1000}
        elif name == "flip":
            surface = self.wait_for(peer, r"BENCHMARK call first_surface:", before_remote, timeout=5)
            self.outgoing_intervals.append({"device": phone, "action": "camera_flip", "start": event["time"],
                                            "end": self.phone_time(phone), "source": "app_control_and_peer_surface"})
            self.incoming_intervals.append({"device": peer, "action": "remote_camera_flip", "start": before_remote,
                                            "end": surface["time"], "source": "peer_surface"})
            event["surface_recovery"] = {"device": peer, "time": surface["time"],
                                         "ms": (surface["at"] - timestamp(before_remote)) * 1000}
        elif name == "background":
            marker = self.wait_for(phone, r"BENCHMARK call lifecycle background applied", event["time"], timeout=5)
            self.background_intervals[phone] = marker["time"]
            event["lifecycle_time"] = marker["time"]
        elif name == "foreground" and phone in self.background_intervals:
            marker = self.wait_for(phone, r"BENCHMARK call lifecycle foreground applied", event["time"], timeout=5)
            surface = self.wait_for(phone, r"BENCHMARK call first_surface:", marker["time"], timeout=5)
            self.incoming_intervals.append({"device": phone, "action": "background",
                                            "start": self.background_intervals.pop(phone), "end": surface["time"],
                                            "source": "activity_lifecycle"})
            event["surface_recovery"] = {"device": phone, "time": surface["time"],
                                         "ms": (surface["at"] - marker["at"]) * 1000}
        elif name == "end":
            self.terminal_evidence(event, ((phone, event["time"]), (peer, before_remote)), ended_timeout=5)

    def wifi_state(self, phone):
        return self.adb(phone, "shell", "settings", "get", "global", "wifi_on", timeout=10).strip()

    def wifi_action(self, phone, name):
        """Turn the phone's Wi-Fi off or on through `svc wifi` and record host and device times around the command.

        `time` is the phone's clock just before the command (the scorer's reference for the carrier's death); the event
        is acknowledged only when the phone's own `wifi_on` setting reads back the requested state. After a wifi_off the
        phone's own `carrier_switch: to=BLUETOOTH` line gives the time back on Bluetooth (None when the call was not on a
        Wi-Fi carrier or the line never came: reported, not a failure of the action)."""
        stamp = lambda: dt.datetime.now().strftime("%m-%d %H:%M:%S.%f")[:-3]
        want = "0" if name == "wifi_off" else "1"
        event = {"device": phone, "action": name, "time": self.phone_time(phone), "host_before": stamp(),
                 "host_monotonic_ns": time.monotonic_ns()}
        self.control_events.append(event)
        if name == "wifi_off":
            self.wifi_restore_phones.add(phone)
        self.adb(phone, "shell", "svc", "wifi", "disable" if name == "wifi_off" else "enable", timeout=15)
        event.update(host_after=stamp(), command_done_host_monotonic_ns=time.monotonic_ns())
        deadline, state = time.monotonic() + WIFI_STATE_WAIT_S, None
        while True:
            state = self.wifi_state(phone)
            if state == want or time.monotonic() >= deadline:
                break
            time.sleep(0.2)
        event.update(ack_time=self.phone_time(phone), wifi_state=state, wifi_state_verified=state == want,
                     acknowledged=state == want)
        if state != want:
            event["state"] = "FAIL"
            raise RuntimeError(f"{phone}: Wi-Fi did not reach the requested state after {name} (wifi_on={state!r})")
        if name == "wifi_off":
            try:
                switched = self.wait_for(phone, r"BENCHMARK carrier_switch: to=BLUETOOTH", event["time"],
                                         timeout=WIFI_RECOVERY_WAIT_S)
                event["back_on_bluetooth"] = {"device": phone, "time": switched["time"],
                                              "ms": (switched["at"] - timestamp(event["time"])) * 1000}
            except LogLinesDropped as error:
                event["back_on_bluetooth_log_loss"] = error.record
            except RuntimeError as error:
                if "no app evidence" not in str(error):
                    raise  # a crash or rejected input is not "the carrier never switched"
                event["back_on_bluetooth_reason"] = "no carrier_switch to BLUETOOTH line after the Wi-Fi was turned off"

    def restore_wifi(self):
        """Wi-Fi back on for every endpoint a wifi_off named, whatever ended the case (failure and interruption included).
        Each phone is read back through its own `wifi_on` setting; an unverified phone is recorded UNVERIFIED and the suite
        quarantines it like an unrestored settings file."""
        for phone in sorted(self.wifi_restore_phones):
            record = {"device": phone, "action": "wifi_restore", "status": "UNVERIFIED", "attempts": 0}
            for _ in range(3):
                record["attempts"] += 1
                try:
                    self.adb(phone, "shell", "svc", "wifi", "enable", timeout=15)
                    deadline = time.monotonic() + WIFI_STATE_WAIT_S
                    while self.wifi_state(phone) != "1" and time.monotonic() < deadline:
                        time.sleep(0.2)
                    if self.wifi_state(phone) == "1":
                        record.update(status="RESTORED")
                        record.pop("error", None)
                        break
                    record["error"] = "wifi_on still not 1 after svc wifi enable"
                except Exception as error:
                    record["error"] = str(error)[:200]
                time.sleep(1)
            self.wifi_restored.append(record)
            self.events.append({"device": phone, "action": "wifi_restore", "status": record["status"],
                                "host_monotonic_ns": time.monotonic_ns()})

    def terminal_evidence(self, event, endpoints, ended_timeout=None):
        """Record each endpoint's proof that the call ended and the endpoint became available again.

        A line that logd confirmably dropped (`LogLinesDropped`) leaves that endpoint without proof and is recorded in
        `terminal_log_loss`: the call itself was measured to its end, so only the gate that needs the lost line is
        UNMEASURED. A missing line with no confirmed drop stays a runner error."""
        event["terminal_evidence"] = {}
        for endpoint, since in endpoints:
            try:
                ended = self.wait_for(endpoint, ENDED_EVIDENCE, since, timeout=ended_timeout)
                available = self.wait_for(endpoint, r"BENCHMARK call available:", ended["time"], timeout=10)
            except LogLinesDropped as error:
                event.setdefault("terminal_log_loss", {})[endpoint] = error.record
                continue
            event["terminal_evidence"][endpoint] = ended_proof(ended, available)

    def health_check(self, phone):
        if phone in self.relays:
            return self.relay_health_check(phone)
        if self.adb(phone, "get-state", timeout=5) != "device":
            raise RuntimeError(f"{phone}: wireless debugging disconnected")
        if phone in self.expected_stopped:
            return
        if self.adb(phone, "shell", "pidof", PACKAGE, timeout=5) != self.pids[phone]:
            raise RuntimeError(f"{phone}: application exited or restarted unexpectedly")
        log = self.device_log(phone)
        self.supervise_captures()
        if self.captures[self.logs.index(log)][0].poll() is not None:
            raise RuntimeError(f"{phone}: log capture stopped unexpectedly")
        lines = self.log_text(phone).splitlines()
        since = None
        for line in lines[self.observed_lines.get(log["path"], 0):]:
            ended_line = "Private call ended:" in line or "BENCHMARK call ended_ui:" in line
            if ended_line and not self.expected_terminal and "FATAL EXCEPTION" not in line:
                since = since or self.measured_call_since(phone, records("\n".join(lines)))
                row = next(iter(records(line)), None)
                if since and row and row["at"] < since["at"]:
                    self.note_superseded_attempt(phone, row, since)
                    continue
            if ended_line and not self.expected_terminal or "FATAL EXCEPTION" in line:
                self.beep()
                self.health_events.append({"device": phone, "message": line, "planned": False})
                raise RuntimeError(f"{phone}: unexpected call termination: {line}")
        self.observed_lines[log["path"]] = len(lines)

    def measured_call_since(self, phone, rows):
        """{at, time, source}: the phone-clock moment from which an end line can belong to the measured call, or None.

        The measured call is the first one that went ACTIVE after the runner's dial. A call that is ACTIVE at some
        moment did not end before it, so an end line stamped earlier closed an earlier attempt (A15s to ASUS GATT:
        the callee logs 'Incoming call failed: EOFException' for the stalled first attempt, seconds before the retry
        rings). The end lines carry no call ID, so the binding is by this endpoint's own clock. Proof, in order:
        its first `call phase ACTIVE` line after the dial; the first media evidence the runner waited for on this
        phone (logd can drop the ACTIVE line); the dial itself (no line from before the dial can end this call)."""
        anchor = next((item["time"] for item in self.setup_anchors if item["device"] == phone), None)
        lower = timestamp(anchor) if anchor else float("-inf")
        active = next((row for row in rows if row["message"] == ACTIVE_PHASE and row["at"] >= lower), None)
        if active:
            return {"at": active["at"], "time": active["time"], "source": "call_phase_active"}
        if phone in self.call_evidence:
            return {"at": timestamp(self.call_evidence[phone]), "time": self.call_evidence[phone],
                    "source": "first_media_evidence"}
        return {"at": lower, "time": anchor, "source": "dial"} if anchor else None

    def note_superseded_attempt(self, phone, row, since):
        """Record once the end of an attempt that never became the measured call (both of its end lines are one end)."""
        message = row["message"]
        text = message[len(ENDED_UI):] if message.startswith(ENDED_UI) else message.partition(ENDED_CALLBACK)[2].strip()
        if any(item.get("action") == "setup_attempt_failed" and item["device"] == phone and item["end_reason"] == text
               and abs(timestamp(item["time"]) - row["at"]) <= ENDED_UI_PAIRING_S for item in self.events):
            return
        self.events.append({"device": phone, "action": "setup_attempt_failed", "time": row["time"], "message": message,
                            "end_reason": text, "call_active_since": since["time"], "bound_by": since["source"],
                            "reason": "This end line precedes the measured call on this phone: it closed an earlier "
                                      "setup attempt, not the call that went ACTIVE",
                            "host_monotonic_ns": time.monotonic_ns()})

    def relay_health_check(self, phone):
        """The relay must keep its process, log capture and both adjacent links until the planned End."""
        if self.adb(phone, "get-state", timeout=5) != "device":
            raise RuntimeError(f"{phone}: wireless debugging disconnected")
        if self.adb(phone, "shell", "pidof", PACKAGE, timeout=5) != self.pids[phone]:
            raise RuntimeError(f"{phone}: call relay exited or restarted unexpectedly")
        log = self.device_log(phone)
        self.supervise_captures()
        if self.captures[self.logs.index(log)][0].poll() is not None:
            raise RuntimeError(f"{phone}: log capture stopped unexpectedly")
        lines = self.log_text(phone).splitlines()
        for line in lines[self.observed_lines.get(log["path"], 0):]:
            if re.search(RELAY_CLOSED, line) and not self.expected_terminal or "FATAL EXCEPTION" in line:
                self.beep()
                self.health_events.append({"device": phone, "message": line, "planned": False})
                raise RuntimeError(f"{phone}: call relay closed before the planned end: {line}")
        self.observed_lines[log["path"]] = len(lines)

    def start_relayed_route(self, caller, callee):
        """BluetoothPrivateCallRelay is downstream-first: it scans once (8 s) for a private-call offer,
        authenticates that child, and only then advertises the callee's offer at route cost + 1 for its
        parent. The chain is therefore built from the callee outwards: callee, the relay next to it, ...,
        the relay next to the caller, then the caller. Each relay takes only the callee's offer at its
        requested child's route cost, and each child stops advertising once its parent holds its only call
        connection (a Classic relay too, with relay chains on), so each phone can reach the callee only
        through its requested child."""
        if relay_shape(self.case) == "on_demand":
            return self.start_on_demand_route(caller, callee)
        route = call_route(self.case)
        self.launch(callee)
        self.callee_settled = self.settle_relayed_callee(callee, self.endpoint_ready(callee))
        for index in reversed(range(len(self.relays))):
            relay, child = route[index + 1], route[index + 2]
            self.launch_relay(relay)
            authenticated = self.relay_wait(relay, RELAY_CALLEE, callee)
            node = re.search(RELAY_CALLEE, authenticated["message"])[1]
            self.relay_events.append({"device": relay, "action": "relay_child_authenticated", "hop": index + 1,
                                      "child": child, "time": authenticated["time"], "node": node,
                                      "message": authenticated["message"]})
            if node != self.devices[child]["node_prefix"]:
                # The offer filter selects by announced callee and route cost, which are unauthenticated
                # BLE data: the authenticated child identity is the proof of the requested hop.
                raise RuntimeError(f"{relay}: relay authenticated {node}, not its requested child {child}")
            advertising = self.relay_wait(relay, RELAY_ADVERTISING, callee, authenticated["time"])
            self.relay_events.append({"device": relay, "action": "relay_advertising", "hop": index + 1,
                                      "time": advertising["time"], "message": advertising["message"]})
        self.launch(caller)
        self.endpoint_ready(caller)
        # This discovery is the first relay's re-advertisement: its child left the air when it connected.
        self.wait_for(caller, rf"BENCHMARK call discovered: node={self.devices[callee]['node_prefix']}")

    def start_on_demand_route(self, caller, callee):
        """Production's helper path (Design C): every phone is an ordinary call-ready endpoint with relayed calls on.

        The callee comes up first and settles, then the helpers from the callee outwards (a helper hears the offer or reach
        page of the phone behind it), then the caller, whose reach table must learn a route of exactly the requested length.
        Nothing connects before the dial: the caller sends SETUP to the helper it selects, and each helper dials its next
        hop. The harness only proves afterwards which helper that was (confirm_on_demand_route, and the scorer).

        The route pins name each phone by the discovery ID it announces: its registry prefix, or, while it rotates (a user
        launch), the ID its own `rotid_own` line announced (pin_prefixes). A pin is read at cold start only, so when an
        epoch boundary rotates a pinned phone's ID before the dial the pinned phones are relaunched with the new IDs
        (rebind_rotated_pins, at most PIN_REBIND_MAX times); the dial never goes out with a pin that names a past ID."""
        self.launch(callee)
        self.callee_settled = self.settle_relayed_callee(callee, self.endpoint_ready(callee))
        self.launch_on_demand_chain(caller, callee)
        self.wait_on_demand_route_hint(caller)
        self.rebind_rotated_pins(caller, callee)

    def launch_on_demand_chain(self, caller, callee, relaunch=False):
        """Launch the helpers from the callee outwards, then the caller; a relaunch stops each phone cleanly first."""
        for index in reversed(range(len(self.relays))):
            helper = self.relays[index]
            if relaunch:
                self.graceful_stop(helper)
            self.launch_helper(helper)
            ready = self.endpoint_ready(helper)
            self.logs[-1]["role"] = "call_relay"  # endpoint_ready labels the first log of a launch "private"
            self.relay_events.append({"device": helper, "action": "helper_ready", "hop": index + 1,
                                      "time": ready["time"], "message": ready["message"]})
            self.settle_relayed_callee(helper, ready)
        if relaunch:
            self.graceful_stop(caller)
        self.launch(caller)
        self.endpoint_ready(caller)

    def pin_prefixes(self, case, phone):
        """{phone: ID a pin names it by}: the registry node prefix, except the one phone this phone's pin points at when
        that phone rotates, which is named by the newest ID its own `rotid_own` line announced.

        The app compares a pin with the announced discovery prefix itself (ReachTable relayPinAllows: the first relay of a
        route, or a helper's next hop), and a rotating phone announces its per-epoch ID only, so a registry prefix could
        never match it. The wait is the dial's: the endpoint logs the line once its offer is out. A phone that logs none
        BLOCKS the case here, before `phone` is launched and long before the dial."""
        prefixes = {p: d.get("node_prefix") for p, d in self.devices.items()}
        if relay_shape(case) != "on_demand":
            return prefixes
        route = [case["caller"], *call_relays(case), case["callee"]]
        if phone not in route[:-1]:
            return prefixes
        following = route[route.index(phone) + 1]
        record = {"phone": phone, "kind": "helper" if phone == case["caller"] else "next_hop", "peer": following,
                  "id": prefixes[following], "source": "registry_node_prefix"}
        if self.rotating(following):
            own = self.wait_own_rotating_id(following, phone)
            log_time, epoch, rdi = own[-1]
            prefixes[following] = rdi
            record.update(id=rdi, source="rotid_own", epoch=epoch, peer_log_time=log_time)
        self.pin_records.append(record)
        return prefixes

    def wait_own_rotating_id(self, peer, pinned_phone):
        """The peer's `rotid_own` lines once it has logged one; ArmBlocked when it logs none within the connection timeout."""
        deadline = time.monotonic() + self.case.get("connection_timeout_s", 30)
        while True:
            self.check_cancelled()
            own = self.own_rotating_ids(peer)
            if own:
                return own
            if time.monotonic() >= deadline:
                raise applied_arms.ArmBlocked(
                    peer, "rotating_pin_unbound",
                    f"{peer} rotates its discovery ID but its app logged no 'rotid_own' line, so the pin of {pinned_phone} "
                    f"cannot name it (the registry prefix is never announced while it rotates); {pinned_phone} was not "
                    f"launched and no call was dialed")
            self.supervise_captures()
            time.sleep(0.2)

    def stale_rotating_pins(self):
        """The newest pin of each pinned phone whose peer announces another rotating ID now (an epoch boundary passed)."""
        latest = {}
        for record in self.pin_records:
            latest[record["phone"]] = record
        stale = []
        for record in latest.values():
            if record["source"] != "rotid_own":
                continue
            own = self.own_rotating_ids(record["peer"])
            if own and own[-1][2] != record["id"]:
                stale.append({**record, "now": own[-1][2], "now_epoch": own[-1][1]})
        return stale

    def rebind_rotated_pins(self, caller, callee):
        """Relaunch the pinned phones with the IDs their peers announce now, before the dial, while a boundary has passed."""
        for attempt in range(1, PIN_REBIND_MAX + 1):
            stale = self.stale_rotating_pins()
            if not stale:
                return
            self.relay_events.append({
                "device": caller, "action": "rotating_pin_rebind", "attempt": attempt,
                "stale": [{"phone": item["phone"], "peer": item["peer"], "pinned": item["id"], "now": item["now"],
                           "pinned_epoch": item["epoch"], "now_epoch": item["now_epoch"]} for item in stale],
                "time": self.phone_time(caller),
                "reason": "an epoch boundary rotated a pinned phone's discovery ID after the pin was set at launch"})
            self.launch_on_demand_chain(caller, callee, relaunch=True)
            self.wait_on_demand_route_hint(caller)
        stale = self.stale_rotating_pins()
        if stale:
            raise applied_arms.ArmBlocked(
                "case", "rotating_pin_stale",
                "; ".join(f"{item['phone']} pins {item['peer']} as {item['id']} (epoch {item['epoch']}) but it announces "
                          f"{item['now']} (epoch {item['now_epoch']})" for item in stale) +
                f" after {PIN_REBIND_MAX} relaunches; the dial was not sent")

    def wait_on_demand_route_hint(self, caller):
        """Wait for the caller's reach table to count a route of the requested length (a hint, never a pass).

        The app logs its table every 30 s (`relayed_reach_table ... costs=c<relays>=<n>`, counted over every target), and
        the dial itself waits only 10 s for its target's row, so a route that is still being learned would otherwise
        be a "peer not listed" refusal. A hint that never shows is recorded and the dial proceeds: the app's own
        selection, the helper's accepted SETUP and the join lines decide the result."""
        count = len(self.relays)
        wait = self.case.get("route_wait_s", ON_DEMAND_ROUTE_WAIT_S)
        event = {"device": caller, "action": "route_table_hint", "relays": count, "wait_s": wait, "found": False}
        try:
            if wait:
                row = self.wait_for(caller, rf"BENCHMARK relayed_reach_table: .*\| costs=(?:[^|]*,)?c{count}=\d+", timeout=wait)
                event.update(found=True, time=row["time"], message=row["message"])
        except RuntimeError as error:
            if not isinstance(error, LogLinesDropped) and "no app evidence" not in str(error):
                raise
            event["reason"] = str(error)
        self.relay_events.append(event)

    def confirm_on_demand_route(self, caller, callee):
        """The caller's selected helper and relay count must be the requested ones (a wrong helper is a failed chain).

        The caller logs `relayed_route: role=caller | target | helper | budget` for the row it dialled. A wrong helper stops
        the run here; a missing line (a direct dial logs none) is left to the scorer, which fails an active call that
        lacks it."""
        first = self.relays[0]
        selected = self.wait_for(caller, ROUTE_CALLER)
        target, helper, _, budget = re.search(ROUTE_CALLER, selected["message"]).groups()
        asked = (self.devices[callee]["node_prefix"], self.devices[first]["node_prefix"], str(len(self.relays)))
        self.relay_events.append({"device": caller, "action": "caller_selected_helper", "hop": 1, "time": selected["time"],
                                  "target": target, "helper": helper, "budget": int(budget),
                                  "message": selected["message"]})
        # A rotating phone is named by an ID its own log announced (never another phone's), so match that set.
        if not (target in self.route_ids(callee) and helper in self.route_ids(first) and budget == asked[2]):
            raise RuntimeError(f"{caller}: relayed route selected helper {helper} for {target} with {budget} relays, not "
                               f"the requested {first} ({asked[1]}) for {callee} with {asked[2]}")

    def confirm_relayed_join(self, caller, callee):
        route = call_route(self.case)
        on_demand = relay_shape(self.case) == "on_demand"
        for index, relay in enumerate(self.relays):
            parent, child = route[index], route[index + 2]
            joined = self.relay_wait(relay, RELAY_JOINED, callee)
            nodes = re.search(RELAY_JOINED, joined["message"]).groups()
            self.relay_events.append({"device": relay, "action": "relay_joined", "hop": index + 1, "time": joined["time"],
                                      "parent": nodes[0], "child": nodes[1], "message": joined["message"]})
            child_ok = nodes[1] == self.devices[child]["node_prefix"]
            if not child_ok and on_demand and child == callee and self.rotating(callee):
                # A user launch names the callee by a per-call pseudonym: bind it from the callee's own log.
                binding = bind_relay_child(nodes[1], records(self.log_text(relay)), records(self.log_text(callee)),
                                           len(self.relays), timestamp(joined["time"]))
                self.relay_events[-1]["relay_child_binding"] = binding.get("method")
                self.relay_events[-1]["relay_child_binding_detail"] = binding
                if binding["method"]:
                    self.child_bindings[relay] = binding["method"]
                    child_ok = True
                else:
                    raise RuntimeError(f"{relay}: relay joined its caller to {nodes[1]}, which is not bound to the "
                                       f"requested child {child}: {binding['reason']}")
            if on_demand and index == 0 and first_hop_pseudonymous(self.case):
                # BMH3: the caller's first link is a declared throwaway; the helper can only name its child.
                if not child_ok:
                    raise RuntimeError(f"{relay}: relay joined its caller to {nodes[1]}, not its requested child {child}")
                continue
            if nodes[0] != self.devices[parent]["node_prefix"] or not child_ok:
                raise RuntimeError(f"{relay}: relay joined {nodes[0]} to {nodes[1]}, not its requested hop "
                                   f"{parent} -> {relay} -> {child}")

    def rotating(self, phone):
        """The phone offers its call under rotating discovery IDs: the benchmark extra test_rotating_ids=true, or a user
        launch, which runs them as shipped (on, whatever the extras say). Endpoints and helpers alike."""
        tunables = user_launch.phone_tunables(self.case, phone)
        return user_launch.is_user_launch(tunables) or tunables.get("rotating_ids") is True

    def route_ids(self, phone):
        """Every discovery ID that names this phone in other phones' lines: its registry prefix and, while it rotates,
        each rotating ID its own log announced."""
        found = {self.devices[phone]["node_prefix"]}
        if self.rotating(phone):
            found.update(rdi for _, _, rdi in self.own_rotating_ids(phone))
        return found

    def own_rotating_ids(self, phone):
        """Discovery IDs this phone logged for its own call offer, oldest first: (log time, epoch, rdi)."""
        rows = []
        for row in records(self.log_text(phone)):
            found = re.search(ROTID_OWN, row["message"])
            if found:
                rows.append((row["time"], int(found[1]), found[2]))
        return rows

    def discover_peer(self, phone, peer):
        """Wait until `phone` scans `peer`'s call offer; return the scan row.

        A fixed-ID peer is matched by its registry node prefix. A rotating peer is matched only by the discovery ID
        its own log announced (`rotid_own`), newest first: another phone's rotating ID never counts, and when the
        peer's ID changes at an epoch boundary during the wait, the wait follows the new ID."""
        if not self.rotating(peer):
            return self.wait_for(phone, rf"BENCHMARK call discovered: node={self.devices[peer]['node_prefix']}")
        deadline = time.monotonic() + self.case.get("connection_timeout_s", 30)
        while True:
            self.check_cancelled()
            own = self.own_rotating_ids(peer)
            if own:
                log_time, epoch, rdi = own[-1]
                found = next((row for row in records(self.log_text(phone))
                              if re.search(rf"BENCHMARK call discovered: node={rdi}", row["message"])), None)
                if found:
                    self.events.append({"device": phone, "action": "rotating_peer_bound", "peer": peer, "rdi": rdi,
                                        "epoch": epoch, "peer_log_time": log_time,
                                        "discovered_time": found["time"],
                                        "reason": "scanned ID equals the peer's own logged rotid_own"})
                    if phone == self.case["caller"]:
                        self.dial_ids[peer] = rdi
                    return found
            if time.monotonic() >= deadline:
                break
            self.supervise_captures()
            time.sleep(0.2)
        if not self.own_rotating_ids(peer):
            raise RuntimeError(f"{peer}: rotating IDs are on but the app logged no 'rotid_own' line, so no scanned "
                               "ID can be bound to this phone (the build lacks the DEBUG own-ID line)")
        # Same failure surface as every other wait: late device-buffer evidence, log-loss and the standard error.
        return self.wait_for(phone, rf"BENCHMARK call discovered: node={self.own_rotating_ids(peer)[-1][2]}",
                             timeout=0.01)

    def launch_order(self):
        """The order the two direct endpoints launch in: the case's `launch_order`; else the registry's `slow_start`
        phones (the A15s) first, so the slower phone's start-up overlaps the other's; else callee then caller as before."""
        caller, callee = self.case["caller"], self.case["callee"]
        if self.case.get("launch_order"):
            return list(self.case["launch_order"])
        slow = [phone for phone in (callee, caller) if self.devices.get(phone, {}).get("slow_start") is True]
        return slow + [phone for phone in (callee, caller) if phone not in slow]

    CALLEE_STATE_SETTLE_S = 6.0

    def callee_state_facts(self, phone):
        """(wakefulness, foreground app) read from `dumpsys power` and `dumpsys window`; None for what cannot be read."""
        power = self.adb(phone, "shell", "dumpsys", "power", timeout=15)
        window = self.adb(phone, "shell", "dumpsys", "window", timeout=15)
        wake = re.search(r"mWakefulness=(\w+)", power)
        focus = re.search(r"mCurrentFocus=(.*)", window)
        return (wake[1] if wake else None), (focus[1].strip() if focus else None)

    def apply_callee_state(self, callee):
        """Put the idle callee in `callee_state` before the dial and prove it (never assume a key press worked).

        background: the Home key; the app must no longer hold the window focus. screen_off: KEYCODE_SLEEP, only on a phone
        the registry marks `secure_lock: false` (planning refused the case otherwise; checked again here); the phone must
        report Asleep. A state the phone did not reach in a few seconds blocks the case before the dial."""
        state = self.case.get("callee_state", "foreground")
        if state == "foreground":
            return
        if state == "screen_off" and self.devices.get(callee, {}).get("secure_lock") is not False:
            raise applied_arms.ArmBlocked(callee, "callee_state_refused",
                                          "screen_off needs a registry secure_lock false; never sleep a secured phone")
        key = "KEYCODE_HOME" if state == "background" else "KEYCODE_SLEEP"
        record = {"device": callee, "requested": state, "key": key, "key_time": self.phone_time(callee)}
        self.adb(callee, "shell", "input", "keyevent", key, timeout=10)
        deadline = time.monotonic() + self.CALLEE_STATE_SETTLE_S
        while True:
            wake, focus = self.callee_state_facts(callee)
            reached = (focus is not None and PACKAGE not in focus and wake == "Awake") if state == "background" else (
                wake in ("Asleep", "Dozing"))
            if reached or time.monotonic() >= deadline:
                break
            time.sleep(0.5)
        record.update(wakefulness=wake, focus=focus, reached=bool(reached), proof_time=self.phone_time(callee))
        self.callee_state_record = record
        self.events.append({"device": callee, "action": "callee_state", "requested": state, "reached": bool(reached),
                            "host_monotonic_ns": time.monotonic_ns()})
        if not reached:
            raise applied_arms.ArmBlocked(callee, "callee_state_not_reached",
                                          f"callee_state {state}: after {key} the phone reports wakefulness={wake}, "
                                          f"focus={focus}")

    def restore_callee_state(self):
        """Wake a screen the case put to sleep (a plain wake key; never a sleep key, never a secure-lock dismissal)."""
        record = self.callee_state_record
        if not record or record.get("requested") != "screen_off":
            return
        try:
            self.adb(record["device"], "shell", "input", "keyevent", "KEYCODE_WAKEUP", timeout=10)
            record["restored"] = True
        except Exception as error:
            record.update(restored=False, restore_error=str(error)[:200])

    def hold_callee_idle(self, callee, ready):
        """Let the callee sit idle for `callee_idle_s` before the caller dials, so the call goes into the state a user
        calls into and not the launch burst (the app scans at full duty for 8 s and advertises at 100 ms for 10 s after
        an endpoint starts; BleL2capControl.LOW_LATENCY_*_BURST_MS, release_join.APP_FAST_WINDOW_S).

        The wait starts at the later of the callee's `call available` line and, when it rotates, its first `rotid_own`
        line (the app's own proof that its offer is out), on the callee's own clock. The caller is already launched and
        has discovered the callee, so the wait costs nothing but the idle time itself."""
        wanted = self.case.get("callee_idle_s")
        if wanted is None:
            return
        anchors = [("call available", ready["time"])]
        if self.rotating(callee):
            own = self.own_rotating_ids(callee)
            if not own:
                raise RuntimeError(f"{callee}: rotating IDs are on but the app logged no 'rotid_own' line to start "
                                   "the idle wait from")
            anchors.append(("rotid_own", own[0][0]))
        name, anchor = max(anchors, key=lambda item: timestamp(item[1]))
        self.callee_idle = {"requested_s": wanted, "anchor": name, "anchor_time": anchor,
                            "available_time": ready["time"], "app_fast_window_s": release_join.APP_FAST_WINDOW_S}
        while True:
            self.check_cancelled()
            elapsed = idle_seconds(self.phone_time(callee), anchor)
            if elapsed >= wanted:
                return
            self.supervise_captures()
            time.sleep(min(1.0, max(0.05, wanted - elapsed)))

    def close_callee_idle(self, callee):
        """Record how long the callee had been idle when the dial was about to go out (its setup anchor)."""
        if not self.callee_idle:
            return
        anchor = next(item["time"] for item in self.setup_anchors if item["device"] == callee)
        self.callee_idle.update(dial_time=anchor, actual_s=round(idle_seconds(anchor, self.callee_idle["anchor_time"]), 3))

    def dial_prefix(self, caller, callee):
        """The node prefix the Invite names: the registry prefix, or the callee's current rotating ID."""
        if not self.rotating(callee):
            return self.devices[callee]["node_prefix"]
        own = self.own_rotating_ids(callee)
        if self.relays:
            # The caller never scans the callee's own offer on a relayed route (a helper does); the ID the callee's own
            # log announced is the one its helpers carry in their reach pages.
            if not own:
                raise RuntimeError(f"{callee}: rotating IDs are on but the app logged no 'rotid_own' line, so no "
                                   "relayed target ID can be bound to this phone")
            self.dial_ids[callee] = own[-1][2]
            return own[-1][2]
        if not own or self.dial_ids.get(callee) != own[-1][2]:
            # The callee's ID changed after discovery (epoch boundary): dial only what the caller has scanned now.
            self.discover_peer(caller, callee)
        return self.dial_ids[callee]

    def endpoint_ready(self, phone, since=None):
        ready = self.wait_for(phone, CALL_AVAILABLE, since)
        if not ready["message"].endswith(self.devices[phone]["node_prefix"]):
            raise RuntimeError(f"{phone}: current app identity differs from the registry")
        if since is None:
            self.logs[-1]["role"] = "private"
        return ready

    def relayed_calls_on(self, phone):
        # A helper has no camera profile in the case (only the two endpoints do), so its own launch shape decides.
        shape = on_demand_helper_shape(self.case, phone) if phone in self.relays else broadcast_shape(self.case, phone)
        # A user launch ignores the extra: the person's stored choice decides (tools/user_launch.py).
        stored = self.settings_for(phone).get("relayed_calls") if user_launch.is_user_launch(shape.get("tunables", {})) else None
        return bool(shape.get("tunables", {}).get("relayed_calls")) if stored is None else stored

    def callee_restart(self, callee, since):
        """The first callee endpoint stop after `since` (a log record), or None."""
        if not isinstance(since, dict) or not since.get("time"):
            return None
        return next((row for row in records(self.log_text(callee)) if row["at"] > timestamp(since["time"])
                     and re.search(ENDPOINT_STOPPED, row["message"])), None)

    def settle_relayed_callee(self, callee, ready):
        """Wait out the callee's first relayed-calls helper tick before any relay connects to it (see CALLEE_SETTLE_S).

        Returns the `call available` record of the endpoint the relay will meet. Each restart seen is recorded as a
        `callee_endpoint_restarted` route event; more than CALLEE_RESTARTS_MAX restarts fail the setup explicitly."""
        # `callee_settle: false` launches the relay at once, as before (an app A/B of the restart guard needs the window).
        if not ready or not self.relayed_calls_on(callee) or self.case.get("callee_settle") is False:
            return ready
        restarts = 0
        settle_until = time.monotonic() + CALLEE_SETTLE_S
        quiet_until = None
        while True:
            self.check_cancelled()
            stopped = self.callee_restart(callee, ready)
            if stopped:
                restarts += 1
                self.relay_events.append({"device": callee, "action": "callee_endpoint_restarted", "time": stopped["time"],
                                          "message": "callee endpoint restarted before the relay connected"})
                if restarts > CALLEE_RESTARTS_MAX:
                    raise RuntimeError(f"{callee}: callee endpoint restarted {restarts} times before the relay connected")
                ready = self.endpoint_ready(callee, stopped["time"])
                settle_until, quiet_until = time.monotonic() + CALLEE_SETTLE_S, None
                continue
            if quiet_until is None and any(row["at"] > timestamp(ready["time"]) and re.search(HELPER_TICK, row["message"])
                                           for row in records(self.log_text(callee))):
                quiet_until = time.monotonic() + CALLEE_TICK_QUIET_S
            now = time.monotonic()
            if now >= settle_until or (quiet_until is not None and now >= quiet_until):
                self.relay_events.append({"device": callee, "action": "callee_settled", "time": ready["time"],
                                          "restarts": restarts})
                return ready
            time.sleep(0.2)

    def relay_wait(self, relay, expression, callee, since=None):
        """wait_for on a relay; a timeout names a callee endpoint restart after the callee settled when the log shows one."""
        try:
            return self.wait_for(relay, expression, since)
        except Exception as error:
            if type(error) is not RuntimeError:
                raise
            stopped = self.callee_restart(callee, getattr(self, "callee_settled", None))
            if not stopped:
                raise
            self.relay_events.append({"device": callee, "action": "callee_endpoint_restarted", "time": stopped["time"],
                                      "message": "callee endpoint restarted while the relay held its hop"})
            raise RuntimeError(f"{error}; callee endpoint restarted at {stopped['time']} "
                               f"({callee} logged 'Stopped.' after its settled 'call available')") from error

    def user_launch_precheck(self):
        """BLOCKED before any phone is touched when this user-launch request cannot work (tools/user_launch.py): a
        needed opt-in setting not stated, a shipped privacy default turned off, a static relay chain under rotating IDs.
        The case would otherwise run to its discovery or dial deadline and fail."""
        found = user_launch.blockers(self.case, [self.case["caller"], self.case["callee"], *self.relays])
        if found:
            raise applied_arms.ArmBlocked("case", found[0][0], "; ".join(detail for _, detail in found))

    def run(self):
        self.output.mkdir(parents=True, exist_ok=False)
        manifest = {"schema_version": 1, "run_id": self.run_id, "mode": "private_call",
                    "scenario": self.case, "source_kind": "live_camera_bidirectional", "optical_results": []}
        manifest["source_kinds"] = {phone: "raw_fixture_live_encoder" if self.raw_fixtures[phone] else "live_camera"
                                    for phone in self.endpoints}
        manifest["source_fixtures"] = {phone: fixture for phone, fixture in self.raw_fixtures.items() if fixture}
        manifest["reference_audios"] = {phone: entry for phone, entry in self.reference_audios.items() if entry}
        manifest["media_trace_session"] = self.trace_session
        manifest["media_impairments"] = {phone: entry for phone, entry in self.media_impairments.items() if entry}
        if manifest["source_fixtures"]:
            manifest["source_kind"] = "recorded_motion_bidirectional" if len(manifest["source_fixtures"]) == 2 else "mixed_camera_recorded_motion"
        caller, callee = self.case["caller"], self.case["callee"]
        stage = "precheck"
        try:
            self.check_cancelled()
            self.user_launch_precheck()
            stage = "inventory"
            with concurrent.futures.ThreadPoolExecutor() as executor:
                phones = sorted(self.participants)
                manifest["devices"] = dict(zip(phones, executor.map(self.inventory, phones)))
            if len({item["apk_sha256"] for item in manifest["devices"].values()}) != 1:
                raise RuntimeError("Call participants have different APK hashes")
            if self.case.get("expected_apk_sha256") and any(
                item["apk_sha256"].lower() != self.case["expected_apk_sha256"].lower() for item in manifest["devices"].values()
            ):
                raise RuntimeError("A call participant does not have the required frozen APK hash")
            stage = "launch"
            if self.relays:
                if self.rotating(callee) and relay_shape(self.case) != "on_demand":
                    raise RuntimeError("Rotating discovery IDs are not supported on static relay routes by this harness")
                stage = "relayed_route"
                self.start_relayed_route(caller, callee)
            else:
                ready = {}
                for phone in self.launch_order():
                    self.launch(phone)
                    ready[phone] = self.endpoint_ready(phone)
                stage = "discovery"
                for phone, peer in ((caller, callee), (callee, caller)):
                    self.discover_peer(phone, peer)
                stage = "idle"
                self.hold_callee_idle(callee, ready[callee])
                self.apply_callee_state(callee)
            stage = "dial"
            self.setup_anchors = [{"device": phone, "action": "call_setup", "time": self.phone_time(phone)}
                                  for phone in (callee, caller)]
            self.close_callee_idle(callee)
            if self.ptt_scheduled():
                for phone in (callee, caller):
                    self.brackets += self.clock_brackets(phone, "start")
            self.invite()
            if self.relays:
                if relay_shape(self.case) == "on_demand":
                    self.confirm_on_demand_route(caller, callee)
                self.confirm_relayed_join(caller, callee)
            setup_action = self.case.get("setup_action", "accept")
            if setup_action in {"decline", "cancel"}:
                self.expected_terminal = True
            setup_event = self.control(callee if setup_action != "cancel" else caller, setup_action)
            accepted_at = time.monotonic()
            if setup_action == "accept":
                setup_event["surface_evidence"] = {}
                for phone in (caller, callee):
                    anchor = next((item["time"] for item in self.setup_anchors if item["device"] == phone), None)
                    try:
                        # A voice call has no surface: its media is ready when the call turns ACTIVE.
                        surface = self.wait_for(phone, r"BENCHMARK call phase ACTIVE" if intercom.is_voice(self.case)
                                                else VIDEO_SURFACE_EVIDENCE, drops_after=anchor)
                    except LogLinesDropped as error:
                        # The evidence line may be among the lines logd dropped: keep measuring the call.
                        setup_event.setdefault("surface_log_loss", {})[phone] = error.record
                        continue
                    setup_event["surface_evidence"][phone] = surface["time"]
                    self.call_evidence[phone] = surface["time"]
                for phone in sorted(self.participants):
                    self.lower_volume(phone)
                start, start_ns = time.monotonic(), time.monotonic_ns()
                planned_s = self.case.get("duration_s", 60)
                window = manifest["measurement_window"] = {
                    "planned_s": planned_s, "start_host_monotonic_ns": start_ns,
                    # Accept -> window start: the surface wait and the volume presses stay outside the measured window.
                    "pre_window_s": round(start - accepted_at, 3), "slowest_health_check_s": 0.0}
                actions = iter(sorted(self.case.get("actions", []), key=lambda item: item["at_s"]))
                next_action = next(actions, None)
                last_health = start
                while time.monotonic() < start + planned_s:
                    self.check_cancelled()
                    if next_action and time.monotonic() - start >= next_action["at_s"]:
                        self.action(next_action)
                        next_action = next(actions, None)
                    if time.monotonic() - last_health >= 3:
                        for phone in sorted(self.participants):
                            if time.monotonic() >= start + planned_s:
                                break  # a slow phone must not stretch the planned window by the checks of the others
                            began = time.monotonic()
                            self.health_check(phone)
                            window["slowest_health_check_s"] = round(max(window["slowest_health_check_s"],
                                                                         time.monotonic() - began), 3)
                        last_health = time.monotonic()
                    time.sleep(0.2)
                window.update(end_host_monotonic_ns=time.monotonic_ns(), actual_s=round(time.monotonic() - start, 3))
                window["overrun_s"] = round(max(0.0, window["actual_s"] - planned_s), 3)
                if window["overrun_s"] > MEASUREMENT_OVERRUN_S:
                    self.events.append({"action": "measurement_window_overrun", **window,
                                        "reason": "the host was slow inside the window (see slowest_health_check_s)"})
                if next_action:
                    raise RuntimeError("Measurement ended before every planned action executed")
                if not self.expected_terminal:
                    self.action({"phone": caller, "action": "end"})
            else:
                # Only an end after this phone saw the dialled attempt answers the decline or cancel.
                self.terminal_evidence(setup_event, [(phone, self.attempt_seen.get(phone)) for phone in (caller, callee)])
        except Exception as error:
            manifest["runner_error"] = str(error) or type(error).__name__
            if isinstance(error, LogLinesDropped):
                manifest["runner_error_log_loss"] = error.record
            if isinstance(error, (StackBlocked, applied_arms.ArmBlocked)):
                manifest["runner_blocked"] = error.record
            if call_dialed({"control_events": self.control_events}) is not True:
                # Never a silent skip: the result says that no call was dialed, at which step, and why.
                manifest["call_not_dialed"] = {"stage": stage, "reason": manifest["runner_error"]}
                print(f"Call not dialed (stopped at {stage})", flush=True)
            self.beep()
            print(f"FAIL: {manifest['runner_error']}", flush=True)
        finally:
            if self.trace_session:
                for phone in sorted(self.endpoints):
                    for role in ("sender", "receiver"):
                        self.collect_media_traces(phone, role)
            if self.ptt_scheduled() and self.brackets:
                for phone in sorted(self.endpoints):
                    try:
                        self.brackets += self.clock_brackets(phone, "end")
                    except Exception:
                        pass  # Missing end brackets leave PTT latency UNMEASURED; never invent drift bounds
            end_times = {}
            for phone in sorted(self.participants):
                try:
                    when = self.phone_time(phone)
                    end_times[phone] = when
                    for log in self.logs:
                        if log["device"] == phone:
                            log["end"] = when
                except Exception:
                    pass
            for phone, (local_start, remote_start) in self.video_off_intervals.items():
                peer = callee if phone == caller else caller
                if phone in end_times:
                    self.outgoing_intervals.append({"device": phone, "action": "camera_off", "start": local_start,
                                                    "end": end_times[phone], "source": "app_control_ack"})
                if peer in end_times:
                    self.incoming_intervals.append({"device": peer, "action": "remote_camera_off", "start": remote_start,
                                                    "end": end_times[peer], "source": "authenticated_remote_camera"})
            for phone, start in self.background_intervals.items():
                if phone in end_times:
                    self.incoming_intervals.append({"device": phone, "action": "background", "start": start,
                                                    "end": end_times[phone], "source": "activity_lifecycle"})
            for process, handle in [*self.captures, *self.stack_captures, *self.drop_captures]:
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=3)
                handle.close()
            self.restore_log_buffers()
            self.collect_crash_logs()
            for phone in sorted(self.participants):
                self.graceful_stop(phone)
            self.restore_callee_state()
            self.restore_wifi()
            self.restore_user_settings()
            self.merge_device_logs()
            self.finalize_drop_logs()
            wedges = self.detect_stack_wedges()
            if wedges:
                manifest["bluetooth_stack_wedges"] = wedges
            manifest.update(logs=self.logs, setup_anchors=self.setup_anchors, control_events=self.control_events,
                            health_events=self.health_events, outgoing_intervals=self.outgoing_intervals,
                            incoming_intervals=self.incoming_intervals, media_traces=self.trace_artifacts,
                            bluetooth_stack_logs=self.stack_logs, log_buffers=self.log_buffers,
                            log_drops=self.drop_logs, crash_logs=self.crash_logs,
                            applied_arms=self.arm_checks,
                            **({"callee_idle": self.callee_idle} if self.callee_idle else {}),
                            **({"callee_state": self.callee_state_record} if self.callee_state_record else {}),
                            **({"user_settings": self.user_setting_records} if self.user_setting_records else {}),
                            **({"user_settings_restored": self.user_settings_restored} if self.user_settings_restored else {}),
                            **({"wifi_restored": self.wifi_restored} if self.wifi_restored else {}))
            manifest["call_type"] = intercom.call_type(self.case)
            if self.child_bindings:
                manifest["relay_child_binding"] = dict(self.child_bindings)
            if user_launch.app_default_phones(self.case):
                # What the app itself chose for each app_default endpoint, from its own saved lines (not a request).
                manifest["app_chosen_profiles"] = app_chosen_profiles(manifest, self.output)
            if self.relays:
                manifest["relays"] = list(self.relays)
                manifest["relay_route_events"] = self.relay_events
                if relay_shape(self.case) == "on_demand":
                    manifest["relay_shape"] = "on_demand"
                    manifest["on_demand_tunables"] = {phone: on_demand_tunables(self.case, phone)
                                                      for phone in (caller, *self.relays, callee)}
                    if self.pin_records:
                        manifest["route_pins"] = self.pin_records
            if self.events:
                manifest["runner_events"] = self.events
            if self.brackets:
                manifest["clock_brackets"] = self.brackets
            (self.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        score = score_call_manifest(manifest, self.output)
        (self.output / "score.json").write_text(json.dumps(score, indent=2) + "\n", encoding="utf-8")
        print(f"Objective call score: {score['overall_status']}", flush=True)
        return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--devices", type=Path, required=True)
    parser.add_argument("--suite", type=Path, required=True)
    parser.add_argument("--case", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--apk-sha256", help="Require this frozen APK on both endpoints before launch")
    parser.add_argument("--output", type=Path, help="Explicit run-owned output directory; required for execution")
    parser.add_argument("--plan", action="store_true")
    args = parser.parse_args()
    if not args.plan and args.output is None:
        parser.error("Execution requires --output with a run-owned directory")
    if args.output is not None:
        args.output = output_path(args.output, "runs")
    args.devices, args.suite = resolve(args.devices), resolve(args.suite)
    devices = json.loads(args.devices.read_text(encoding="utf-8"))["devices"]
    case = next((item for item in json.loads(args.suite.read_text(encoding="utf-8"))["cases"] if item["id"] == args.case), None)
    if case is None:
        parser.error(f"Unknown call scenario: {args.case}")
    if args.apk_sha256:
        case = {**case, "expected_apk_sha256": args.apk_sha256}
    validate_call_case(case, devices)
    if args.plan:
        print(json.dumps(case, indent=2))
        return
    if relay_chain_limit(case):
        parser.error(relay_chain_limit(case))
    folder = args.output
    manifest = CallRunner(args.adb, devices, case, folder).run()
    print(f"Evidence: {folder / 'manifest.json'}", flush=True)
    if manifest.get("runner_error"):
        sys.exit(1)


if __name__ == "__main__":
    main()
