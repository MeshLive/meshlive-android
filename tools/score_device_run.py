#!/usr/bin/env python3
"""Score scoped Android runs, without mistaking decoder submits for visible frames.

Manifest v1: expected_fps, logs [{path, device, role, pid, start, end}],
planned_intervals [{device, start, end, action}], optional selection_events
[{device, time, action}] and optical_results [{device, aligned,
reference_kind: "raw_fixed_fixture", coverage_matches, vmaf, ssim, psnr_db}].
Times use logcat's MM-DD HH:MM:SS.mmm; logs need threadtime timestamps.
Thresholds are engineering acceptance gates, not a perceptual quality standard.
DEFAULT_THRESHOLDS is device-run-v3 and never changes. acceptance_profiles.USER_THRESHOLDS
overlays device-run-v4-user (user-centred gates; fidelity and FPS fractions become DIAGNOSTIC
rows). Select it explicitly with thresholds={"version": "device-run-v4-user"} or --profile.
"""

import argparse
from datetime import datetime
import json
import math
from pathlib import Path

import acceptance_profiles as acceptance
import app_crash_events
from benchmark_layout import original, output_path, resolve
from evidence_relocation import logical
import re
import statistics


DEFAULT_THRESHOLDS = {
    "version": "device-run-v3",
    "surface_p95_periods": 1.25,
    "surface_p99_periods": 2.0,
    "visible_fps_fraction": 0.95,
    "encoder_fps_fraction": 0.95,
    "max_freeze_events": 0,
    "max_drops": 0,
    "max_unexpected_events": 0,
    "max_startup_ms": 3000,
    "max_discovery_ms": 1500,
    "min_vmaf": 90,
    "min_ssim": 0.98,
    "min_psnr_db": 35,
    "offered_load_fraction": 0.90,
}
ROLLING_CADENCE_GAPS = 256  # RollingLatencyStats capacity behind "surface cadence ... max"
# A rendered interval is a whole number of display refreshes, so at 15 fps on a 60 Hz screen (4 or 5 refreshes per
# frame) a healthy p95 is 83.33 ms plus timer noise: exactly the 1.25-period limit (83.4-85.2 ms read FAIL on calls
# with zero freezes, q26-20261109e 24 of 24). The p95/p99 limits keep their meaning; the comparison allows this
# fraction of one refresh of the receiving screen, the rounding of that grid. A late frame costs a whole refresh.
# `surface_refresh_tolerance_periods` in a thresholds override changes it (0 = the comparison before 2026-11-10).
DISPLAY_REFRESH_TOLERANCE_PERIODS = 0.5
DEFAULT_DISPLAY_REFRESH_HZ = 60.0
NUMBER = r"[0-9]+(?:\.[0-9]+)?"
# The receiving phone's refresh rate when its log states one. No app build logs it yet (2026-11-10).
DISPLAY_REFRESH = re.compile(rf"BENCHMARK display\b.*?\brefresh(?:Hz|RateHz|_hz)=({NUMBER})")
LOG_LINE = re.compile(
    r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+\d+\s+([VDIWEF])\s+([^:]+):\s?(.*)$"
)
CONTINUITY = re.compile(
    rf"(Receiver|Private call) visible continuity( FINAL)?: (\d+) frames \| "
    rf"(\d+) freeze(?: event)?\(s\), ({NUMBER}) ms, ({NUMBER})% \| threshold ({NUMBER}) ms"
)
CADENCE = re.compile(rf"surface cadence p50/p95/p99/max ({NUMBER})/({NUMBER})/({NUMBER})/({NUMBER}) ms")
ENCODER = re.compile(rf"Encoder output: ({NUMBER}) FPS, ({NUMBER}) kbps")
ALLOCATION_LABELS = {
    "Automatic (recommended)": ("automatic", None),
    "Steady bitrate (CBR)": ("cbr", 2),
    "Scene-adaptive bitrate (VBR)": ("vbr", 1),
    "Steady bitrate + frame dropping (CBR-FD)": ("cbr_fd", 3),
}
STARTUP = re.compile(rf"(Receiver|Private call|Discovery) startup: ([\w-]+) ({NUMBER}) ms")
# The end of a call is logged twice in debug builds: "Private call ended: <message>" on the call thread and
# "BENCHMARK call ended_ui: message=<message>" from the UI thread after teardown. ASUS (Android 11 logd) repeatedly
# lost the first one together with the other final reports (calls-noloss-m31asus-51cc8028/0007); either is the same
# app evidence. Which one was used stays recorded in the terminal proof.
ENDED_CALLBACK = "Private call ended:"
ENDED_UI = "BENCHMARK call ended_ui: message="
# VideoStreamDecoder: "BENCHMARK decoder requested=<name|none> effective=<name|none> reason=<token> ...". A requested
# decoder (test_decoder, or a policy-forced software decoder) that is not the one decoding is a failed case, never a
# silent substitution (compatibility review 2026-10-10).
DECODER_LINE = re.compile(r"BENCHMARK decoder requested=(\S+) effective=(\S+) reason=(\S+)")
# AndroidPrivateVideoCall: "BENCHMARK link_phy requested=<phy> effective=<phy> reason=<token> source=<adaptive|run> ...".
# Every link whose effective LE PHY differs from the attempted one is listed in the score; source=run (a PHY the
# run itself requested) that is not effective fails the case like a substituted decoder.
LINK_PHY_LINE = re.compile(r"BENCHMARK link_phy requested=(\S+) effective=(\S+) reason=(\S+) source=(\S+)")
ENDED_EVIDENCE = re.escape(ENDED_CALLBACK) + "|" + re.escape(ENDED_UI)
NORMAL_END = r"(?:Call ended|Peer ended the call)"
# DEBUG builds since B3 append the call's ID to the call-thread record: "Private call ended: <message> | callId=<uuid>".
ENDED_CALL_ID = r"(?: \| callId=[0-9a-fA-F-]+)?"
ENDED_UI_PAIRING_S = 10.0


def ended_proof(ended, available):
    proof = {"ended": ended["time"], "available": available["time"]}
    if ended["message"].startswith(ENDED_UI):
        proof["ended_source"] = "ui_teardown_record (callback record absent from the log)"
    return proof


# BENCHMARK lines that list configuration (registry entries, proposals, switch names) and never report an event.
CONFIGURATION_LISTINGS = ("BENCHMARK device_policy:", "BENCHMARK family_switches:")
UNEXPECTED = re.compile(
    r"FATAL EXCEPTION|Fatal signal|Bluetooth.*(?:crash|died)|"
    r"Private call ended:|(?:connection|stream|link).*?(?:failed|lost|disconnected)|"
    r"\b(?:EOF\w*|LOCAL_CAPACITY)\b|Detached downstream child|connection did not start|"
    r"Withdrawing congested downstream route|Forwarding stopped because|"
    r"Incoming GATT stream stopped:|immediate parent withdrew|BAD_DECRYPT|"
    # Android withheld requested foreground-service types (e.g. camera/microphone in the background) or refused
    # the session service: the session ran without what it requested (BackgroundReliability.kt).
    r"BENCHMARK foreground_service: .*refused=(?!none\b)|"
    # A Wi-Fi carrier that died (`carrier_death`): an unexpected event unless it is the close of the planned hang-up.
    r"BENCHMARK carrier_death:",
    re.IGNORECASE,
)
# The phone that did not hang up logs the carrier's read failing 0.1-0.3 s before its own `call phase ENDED` (the peer closed
# the Wi-Fi socket first; w2v1h-20261110k and w2hi-20261110k: 19 EOFException, 1 SocketException, ageMs 77-199). That
# close belongs to the planned End. A death inside the confirmed planned-end interval, or up to CARRIER_END_LEAD_S before
# it, with this reason is planned; a "write stalled" or "silence" death, or any death elsewhere, stays unexpected.
CARRIER_END_REASON = re.compile(r"BENCHMARK carrier_death: .*\| reason=read failed: (?:EOFException|SocketException)\b")
CARRIER_END_LEAD_S = 1.0
# A planned wifi_off (runner control event, phone clock) takes the Wi-Fi carrier away on purpose. The death it causes is
# planned: the app's own reason names the toggle (`aware_down_wifi_off`, `wifi_off`), or the carrier's socket read fails
# within WIFI_OFF_READ_FAILURE_S of the action (the peer sees the close, not the radio going down; the lead covers
# the two phones' clocks). Any other reason, or a death elsewhere, stays unexpected.
WIFI_OFF_REASON = re.compile(r"BENCHMARK carrier_death: .*\| reason=(?:aware_down_)?wifi_off\b")
WIFI_OFF_READ_FAILURE = re.compile(r"BENCHMARK carrier_death: .*\| reason=read failed: ")
WIFI_OFF_READ_FAILURE_S = 2.0
WIFI_OFF_CLOCK_LEAD_S = 1.0
BACK_ON_BLUETOOTH = re.compile(r"BENCHMARK carrier_switch: to=BLUETOOTH\b")
# The central logs its own clean close of the GATT link as the call ends (`event=link_lost | status=0 | class=clean`, a few
# hundred ms after `call phase ENDED`: un2g-20261110n ASUS > S22, w2hi-20261110k m31). Inside the same planned-end windows
# that is the hang-up, not a lost link; any other status or class, or the same line mid-call, stays unexpected.
LINK_CLOSE_AT_END = re.compile(r"BENCHMARK ll_gate: .*\| event=link_lost \| status=0 \| class=clean\b")


def timestamp(value):
    """Date year is deliberately ignored: Android threadtime does not supply it."""
    if re.match(r"^\d{4}-", value):
        value = value[5:]
    return datetime.strptime("2000-" + value, "%Y-%m-%d %H:%M:%S.%f").timestamp()


def records(text):
    result = []
    for line_number, line in enumerate(text.splitlines(), 1):
        match = LOG_LINE.match(line)
        if match:
            time, pid, level, tag, message = match.groups()
            result.append({"time": time, "at": timestamp(time), "pid": int(pid),
                           "level": level, "tag": tag.strip(), "message": message,
                           "line": line_number})
    return result


def scope_records(rows, spec):
    lower = timestamp(spec["start"]) if spec.get("start") else float("-inf")
    upper = timestamp(spec["end"]) if spec.get("end") else float("inf")
    if upper < lower:
        raise ValueError("Run end must not precede start (split midnight runs into dated scopes)")
    selected = [row for row in rows if lower <= row["at"] <= upper]
    pids = sorted({row["pid"] for row in selected if row["tag"] == "BluetoothVideo"})
    pid = spec.get("pid")
    if pid is None and len(pids) != 1:
        return [], {"status": "UNMEASURED", "reason": "Specify PID/time scope; app PIDs are ambiguous", "candidate_pids": pids}
    pid = int(pid if pid is not None else pids[0])
    selected = [row for row in selected if row["pid"] == pid]
    return selected, {"status": "MEASURED" if selected else "UNMEASURED", "pid": pid,
                      "start": spec.get("start"), "end": spec.get("end"), "records": len(selected)}


def planned_wifi_offs(manifest):
    """Each acknowledged wifi_off with the instant it was issued (phone clock) and when Wi-Fi was planned back on."""
    controls = [item for item in manifest.get("control_events", []) if item.get("time")]
    result = []
    for item in controls:
        if item.get("action") != "wifi_off" or item.get("acknowledged") is False:
            continue
        at = timestamp(item["time"])
        back = min((timestamp(other["time"]) for other in controls if other.get("action") == "wifi_on"
                    and other.get("device") == item.get("device") and timestamp(other["time"]) > at),
                   default=float("inf"))
        result.append({"device": item.get("device"), "at": at, "time": item["time"], "until": back,
                       "host_before": item.get("host_before"), "host_after": item.get("host_after")})
    return result


def planned_wifi_off_death(message, at, wifi_offs):
    """True when this `carrier_death` line is the planned wifi_off's doing (see WIFI_OFF_REASON)."""
    for item in wifi_offs:
        if at < item["at"] - WIFI_OFF_CLOCK_LEAD_S:
            continue
        if WIFI_OFF_REASON.search(message) and at <= item["until"] + WIFI_OFF_CLOCK_LEAD_S:
            return True
        if WIFI_OFF_READ_FAILURE.search(message) and at <= item["at"] + WIFI_OFF_READ_FAILURE_S:
            return True
    return False


def wifi_off_report(device, wifi_offs, rows, surface):
    """Per planned wifi_off, from this phone's own log: the carrier death it saw, the time until its carrier was back on
    Bluetooth, and the freeze counters across that stretch. Reported, never a gate: the freeze gates keep counting a freeze
    here as they do anywhere else, and a call that was not on a Wi-Fi carrier has no death or switch to report."""
    reports = []
    for item in wifi_offs:
        lead = item["at"] - WIFI_OFF_CLOCK_LEAD_S
        death = next((row for row in rows if row["at"] >= lead and "BENCHMARK carrier_death:" in row["message"]), None)
        back = next((row for row in rows if row["at"] >= lead and BACK_ON_BLUETOOTH.search(row["message"])), None)
        report = {"toggled_device": item["device"], "role": "toggled" if item["device"] == device else "peer",
                  "action_time": item["time"], "host_before": item["host_before"], "host_after": item["host_after"],
                  "carrier_death": None if death is None else {
                      "time": death["time"], "message": death["message"],
                      "after_action_ms": (death["at"] - item["at"]) * 1000,
                      "planned": planned_wifi_off_death(death["message"], death["at"], [item])},
                  "back_on_bluetooth": None if back is None else {
                      "time": back["time"], "ms": (back["at"] - item["at"]) * 1000}}
        # Freeze counters are cumulative per report segment: take the last report at or before the action and the first
        # report at or after the return to Bluetooth (the end of the capture when it never came), in the same segment.
        before = next((sample for sample in reversed(surface) if sample["at"] <= item["at"]), None)
        horizon = back["at"] if back else item["at"]
        after = next((sample for sample in surface if sample["at"] >= horizon and (
            before is None or sample["at"] > before["at"])), None)
        if before and after and before["label"] == after["label"] and not after["final"] and all(
                after[key] >= before[key] for key in ("frames", "freeze_events", "freeze_duration_ms")):
            report["freeze"] = {"from": before["time"], "to": after["time"],
                                "freeze_events": after["freeze_events"] - before["freeze_events"],
                                "freeze_duration_ms": after["freeze_duration_ms"] - before["freeze_duration_ms"],
                                "resolution": "periodic visible-continuity reports; never gated here"}
        else:
            report["freeze"] = {"status": "UNMEASURED",
                                "reason": "no pair of visible-continuity reports in one segment around the toggle"}
        reports.append(report)
    return reports


def overlaps(intervals, start, end):
    return any(item["from"] <= end and item["to"] >= start for item in intervals)


def gate(value, limit, comparison="max", reason=None, tolerance=None):
    """`tolerance` (a measurement resolution, in the value's unit) widens the comparison, never the recorded limit."""
    if value is None or limit is None:
        return {"status": "UNMEASURED", "value": None, "limit": limit,
                "reason": reason or "Required measurement absent"}
    if any(type(item) not in (int, float) or not math.isfinite(item) for item in (value, limit)):
        return {"status": "UNMEASURED", "value": None, "limit": None,
                "reason": "Measurement and target must be finite numbers"}
    slack = tolerance or 0
    passed = value <= limit + slack if comparison == "max" else value >= limit - slack
    return {"status": "PASS" if passed else "FAIL", "value": value,
            "limit": limit, "comparison": comparison, **({"tolerance": tolerance} if tolerance is not None else {})}


def display_refresh(rows, spec, manifest):
    """(refresh rate in Hz, source) of the phone whose screen showed the frames: declared for the log or the device,
    else the app's own line, else 60 Hz."""
    declared = spec.get("display_refresh_hz") or (
        (manifest.get("devices") or {}).get(spec.get("device")) or {}).get("display_refresh_hz")
    if type(declared) in (int, float) and declared > 0:
        return float(declared), "declared"
    logged = next((float(match[1]) for row in rows if (match := DISPLAY_REFRESH.search(row["message"]))), None)
    if logged:
        return logged, "app_log"
    return DEFAULT_DISPLAY_REFRESH_HZ, "default"


def frame_interval_gate(value, period, periods, refresh):
    """A rolling frame-interval percentile against `periods` source frame periods, compared on the display's grid."""
    if not period:
        return gate(None, None, reason="Expected FPS not specified")
    hz, source, share = refresh
    result = gate(value, period * periods, tolerance=1000 / hz * share)
    return {**result, "display_refresh_hz": hz, "display_refresh_source": source,
            "tolerance_basis": f"{share:g} of one {hz:g} Hz display refresh: rendered intervals are whole refreshes"}


def limits_for(thresholds=None):
    """Full limits for a profile: v3 base, optional v4 overlay chosen by version, then explicit overrides."""
    version = (thresholds or {}).get("version")
    overlay = acceptance.USER_THRESHOLDS if version == acceptance.V4 else {}
    return {**DEFAULT_THRESHOLDS, **overlay, **(thresholds or {})}


def as_diagnostic(item):
    """Keep the measured value and its verdict, but never let it decide."""
    if item.get("status") == "DIAGNOSTIC":
        return item
    return {**item, "status": "DIAGNOSTIC", "measured_status": item["status"], "decides": False}


def aggregate_status(gates):
    # DIAGNOSTIC rows are reported with their measured verdict but never decide.
    states = [item["status"] for item in gates if item["status"] != "DIAGNOSTIC"]
    return "FAIL" if "FAIL" in states else "UNMEASURED" if not states or "UNMEASURED" in states else "PASS"


def value_range(values):
    return {"samples": len(values), "min": min(values) if values else None,
            "median": statistics.median(values) if values else None,
            "max": max(values) if values else None}


def counter_summary(samples, intervals, planned_at=()):
    """Cumulative counters reset into segments; overlapping deltas stay ambiguous. A delta that ends at a time in
    `planned_at` is the planned hang-up's own (see planned_end_freeze_samples): counted as `planned_end`."""
    if not samples:
        return {"observed": None, "segments": [], "lifecycle_overlap": 0, "unexpected": None, "planned_end": 0}
    if len(samples[0]) == 3:
        groups = {}
        for at, value, key in samples:
            groups.setdefault(key, []).append((at, value))
        summaries = {key: counter_summary(values, intervals, planned_at) for key, values in groups.items()}
        return {"observed": sum(item["observed"] for item in summaries.values()),
                "segments": [value for item in summaries.values() for value in item["segments"]],
                "lifecycle_overlap": sum(item["lifecycle_overlap"] for item in summaries.values()),
                "planned_end": sum(item["planned_end"] for item in summaries.values()),
                "unexpected": sum(item["unexpected"] for item in summaries.values()), "by_route_or_segment": summaries}
    totals, total, unexpected, ambiguous, planned_end = [], 0, 0, 0, 0
    previous = None
    for at, value in samples:
        if previous is None or value < previous[1]:
            if previous is not None:
                totals.append(total)
            total = value
            delta = value
            begin = at
        else:
            delta = value - previous[1]
            total = value
            begin = previous[0]
        if previous is not None and at in planned_at and begin == previous[0]:
            planned_end += delta
        elif overlaps(intervals, begin, at):
            ambiguous += delta
        else:
            unexpected += delta
        previous = (at, value)
    totals.append(total)
    return {"observed": sum(totals), "segments": totals, "planned_end": planned_end,
            "lifecycle_overlap": ambiguous, "unexpected": unexpected}


PLANNED_END_FREEZE_EVENTS = 1
# The hang-up gap is the peer's last frame to our snapshot: about one freeze threshold (218-287 ms against a 250 ms
# threshold in w2rm2-20261110a). A report beyond 1.5 thresholds is a freeze in the call, not the hang-up.
PLANNED_END_FREEZE_THRESHOLDS = 1.5
END_PROOF_LOST_REASON = "log lines dropped by logd"  # the wording run_device_benchmark.LOG_DROP_REASON records


def planned_end_freeze_samples(segments, end_intervals):
    """The FINAL continuity report is taken when the call ends, so it counts the gap since the peer's last frame
    as one short freeze. That gap belongs to the planned hang-up (w2rm2-20261110a: 7 of 10 calls, 218-287 ms), not
    to the call. Only a FINAL report logged inside the phone's confirmed planned-end interval qualifies, and only
    for one freeze event no longer than 1.5 freeze thresholds; anything longer, any periodic report before the
    End, and any call with no confirmed planned End stay unexpected. The report interval straddles the End and
    cannot be split, so a shorter, earlier freeze in the same interval is not distinguishable: it is listed here,
    never silently dropped."""
    result = []
    for segment in segments:
        for previous, sample in zip(segment, segment[1:]):
            events = sample["freeze_events"] - previous["freeze_events"]
            duration = sample["freeze_duration_ms"] - previous["freeze_duration_ms"]
            if (sample["final"] and events > 0 and any(item["from"] <= sample["at"] <= item["to"] for item in end_intervals)
                    and events <= PLANNED_END_FREEZE_EVENTS
                    and duration <= PLANNED_END_FREEZE_THRESHOLDS * sample["freeze_threshold_ms"]):
                result.append({"time": sample["time"], "at": sample["at"], "freeze_events": events,
                               "freeze_duration_ms": duration, "reason": "FINAL report after the confirmed planned End"})
    return result


def app_crash_gate(manifest, device, base_path=Path(".")):
    """FAIL when the phone's dumped crash/system buffers hold a crash or ANR of the app inside the case window;
    UNMEASURED when the dump is absent, unreadable or changed since it was taken; None for a manifest recorded
    before this capture existed (it has no `crash_logs`, so no gate is invented for it)."""
    entries = [item for item in manifest.get("crash_logs", []) if item.get("device") == device]
    if not entries:
        return None
    events, problems = [], []
    for entry in entries:
        if entry.get("status") != "MEASURED":
            problems.append(entry.get("reason") or "the crash and system buffers were not read")
            continue
        try:
            data = resolve(entry["path"], base_path).read_bytes()
        except OSError as error:
            problems.append(f"crash dump unreadable: {error}"[:200])
            continue
        if entry.get("sha256") and app_crash_events.sha256(data) != entry["sha256"]:
            problems.append("crash dump differs from its recorded hash")
            continue
        events += app_crash_events.parse_crash_events(data.decode("utf-8", errors="replace"), entry.get("package") or
                                                     app_crash_events.PACKAGE, entry.get("start"), entry.get("end"))
    if events:
        result = gate(len(events), 0)
        result["events"] = events
        return result
    if problems:
        return gate(None, 0, reason="; ".join(problems))
    return gate(0, 0)


def planned_overlap_seconds(planned, begin, end):
    """Seconds of [begin, end] covered by the union of planned intervals."""
    clipped = sorted((max(item["from"], begin), min(item["to"], end)) for item in planned
                     if item["from"] < end and item["to"] > begin)
    total, reach = 0.0, begin
    for lo, hi in clipped:
        lo = max(lo, reach)
        if hi > lo:
            total += hi - lo
            reach = hi
    return total


def rolling_window_covered(segments):
    """True when no report interval added more gaps than the rolling max window can hold."""
    for segment in segments:
        previous = 1
        for sample in segment:
            if sample["frames"] - previous > ROLLING_CADENCE_GAPS:
                return False
            previous = max(previous, sample["frames"])
    return True


def time_weighted_fps(timeline, requested, start, end):
    """(average FPS in force over [start, end], seconds in force below requested); requested before any header."""
    points = [(start, next((value for at, value in reversed(timeline) if at <= start), requested))]
    points += [(at, value) for at, value in timeline if start < at < end]
    expected, below = 0.0, 0.0
    for index, (at, value) in enumerate(points):
        until = points[index + 1][0] if index + 1 < len(points) else end
        if value is not None:
            expected += value * (until - at)
            if requested and value < requested:
                below += until - at
    return (expected / (end - start) if end > start and expected else None), below


def demote_user_gates(gates, link):
    """FPS fractions always, and healthy-link regression guards when the link is impaired, stop deciding."""
    for name in list(gates):
        if name in acceptance.DIAGNOSTIC_GATES or link == "impaired" and name in acceptance.HEALTHY_ONLY_GATES:
            gates[name] = as_diagnostic(gates[name])


def apply_user_profile(gates, optical_gates, limits, context):
    """device-run-v4-user: add the user-centred gates and demote fidelity/FPS fractions to diagnostics."""
    link, link_source = acceptance.link_class(context["manifest"])
    role, is_call = context["role"], context["is_call"]
    segments, planned = context["segments"], context["planned"]
    report = {"profile": acceptance.V4, "link_class": link, "link_class_source": link_source,
              "media": "call" if is_call else "stream",
              "decisive_on_impaired": ["longest_stall_ms", "frozen_time_share_percent", "effective_profile_fps",
                                       "audio_drops", "unexpected_events", "connection and identity gates"],
              "media_age": "UNMEASURED here: capture-to-playout age needs finalized media traces with measured clock "
                           "anchors (score_pipeline_trace media_age); queue_age_proxy_ms is a local backlog proxy only"}
    freeze_gate = dict(gates.get("unexpected_freeze_events", {}))
    if role != "sender":
        stall_limit = limits["max_stall_ms_call"] if is_call else limits["max_stall_ms_stream"]
        cadence = context["clean_cadence"]
        stall = gate(max((item["max"] for item in cadence), default=None), stall_limit)
        covered = rolling_window_covered(segments)
        stall.update(basis="Largest gap between rendered surface updates inside each report's rolling 256-gap window; "
                           "the unterminated tail after the last frame is not included",
                     rolling_window_covered=covered, applies_to="call" if is_call else "stream")
        if stall["status"] == "PASS" and not covered:
            stall = {**stall, "status": "UNMEASURED",
                     "reason": "A report interval added more than 256 gaps, so the rolling window may omit a longer stall"}
        gates["longest_stall_ms"] = stall
        scored_seconds = sum(max(0.0, segment[-1]["at"] - segment[0]["at"] -
                                 planned_overlap_seconds(planned, segment[0]["at"], segment[-1]["at"]))
                             for segment in segments if segment)
        frozen_ms = context["durations"]["unexpected"]
        share = frozen_ms / (scored_seconds * 1000) * 100 if frozen_ms is not None and scored_seconds > 0 else None
        frozen = gate(share, limits["max_frozen_time_share_percent"])
        frozen.update(frozen_ms=frozen_ms, scored_seconds=scored_seconds,
                      app_reported_final_ratio_percent=max((segment[-1]["freeze_ratio_percent"] for segment in segments if segment),
                                                           default=None),
                      basis="Unexpected freeze time (gaps beyond the freeze threshold, less one frame period each) / scored rendered time")
        if frozen["status"] != "UNMEASURED" and (freeze_gate.get("status") == "UNMEASURED" or
                                                  context["durations"]["lifecycle_overlap"]):
            frozen = {**frozen, "status": "UNMEASURED",
                      "reason": freeze_gate.get("reason") or "Freeze time overlaps a planned action; cannot allocate individual gaps"}
        gates["frozen_time_share_percent"] = frozen
        requested = context["fps"]
        timeline = sorted((timestamp(item["time"]), item["fps"]) for item in context["profiles"]
                          if item["kind"] == "received_display")
        ratios, degraded, total = [], 0.0, 0.0
        below_seconds, rated_seconds = 0.0, 0.0
        for item in context["intervals"]:
            if item["planned_overlap"]:
                continue
            start, end = timestamp(item["start"]), timestamp(item["end"])
            effective, below = time_weighted_fps(timeline, requested, start, end)
            if effective:
                ratios.append(item["visible_fps"] / effective)
                rated_seconds += end - start
                if item["visible_fps"] / effective < limits["effective_fps_fraction"]:
                    below_seconds += end - start
            total += end - start
            degraded += below
        # Q6 (2026-10-14): the decisive value is the share of scored time spent below the fraction of the FPS in force;
        # the longest stall is its own decisive gate (longest_stall_ms). The old slowest-window rule is kept below.
        below_share = below_seconds / rated_seconds * 100 if rated_seconds > 0 else None
        cadence_gate = gate(below_share, limits["effective_fps_below_share_percent"], "max",
                            reason="Effective profile FPS or visible-FPS intervals absent")
        cadence_gate.update(
            rule="time_share_below_fraction",
            basis="Share of clean report time whose visible FPS was below "
                  f"{limits['effective_fps_fraction']:.0%} of the FPS in force then (received stream header, time-weighted; "
                  "requested FPS before any header); the time resolution is the report interval. The longest stall is "
                  "judged by longest_stall_ms",
            fraction=limits["effective_fps_fraction"], below_seconds=below_seconds, scored_seconds=rated_seconds,
            longest_stall_ms=max((item["max"] for item in context["clean_cadence"]), default=None),
            declared_effective_changes=len(timeline),
            legacy_slowest_window={
                **gate(min(ratios, default=None), limits["effective_fps_fraction"], "min",
                       reason="Effective profile FPS or visible-FPS intervals absent"),
                "label": "comparison only (rule before Q6): slowest clean report interval's visible FPS / FPS in force "
                         "must reach the fraction; never decides"})
        gates["effective_profile_fps"] = cadence_gate
        visible = [item["visible_fps"] for item in context["intervals"] if not item["planned_overlap"]]
        if requested and visible:
            gates["requested_profile_shortfall"] = {"status": "DIAGNOSTIC", "measured_status": "UNMEASURED", "decides": False,
                "value": statistics.median(visible) / requested, "median_visible_fps": statistics.median(visible),
                "requested_fps": requested, "limit": None,
                "reason": "Median visible FPS as a fraction of the requested FPS; reported, never decisive"}
            gates["degraded_time_share_percent"] = {"status": "DIAGNOSTIC", "measured_status": "UNMEASURED", "decides": False,
                "value": degraded / total * 100 if total else None, "limit": None,
                "reason": "Share of clean report time the in-force FPS was below the requested FPS; reported, never decisive"}
    ages = [item["oldest_ms"] for item in context["queues"] if item.get("oldest_ms") is not None]
    if ages:
        gates["queue_age_proxy_ms"] = {"status": "DIAGNOSTIC", "measured_status": "UNMEASURED", "decides": False,
            "value": max(ages), "limit": None, "proxy": True,
            "reason": "Oldest queued item at a periodic report; a local backlog proxy, not capture-to-playout media age"}
    demote_user_gates(gates, link)
    for name in list(optical_gates):
        optical_gates[name] = as_diagnostic(optical_gates[name])
    return report


def score_log(text, spec, manifest=None, thresholds=None):
    manifest = manifest or {}
    limits = limits_for(thresholds)
    fps = spec.get("expected_fps", manifest.get("expected_fps"))
    if fps is not None and fps <= 0:
        raise ValueError("Expected FPS must be positive")
    period = 1000 / fps if fps else None
    selected, scope = scope_records(records(text), spec)
    device = spec.get("device", "unknown")
    evaluation_intervals = [item for item in manifest.get("planned_intervals", []) if item.get("device") == device]
    capture_end = spec.get("end") or (selected[-1]["time"] if selected else None)
    pause_start = None
    for row in selected:
        if row["message"] == "Stream paused by broadcaster." and pause_start is None:
            pause_start = row["time"]
        elif row["message"] == "Broadcaster resumed the stream." and pause_start is not None:
            evaluation_intervals.append({"device": device, "start": pause_start, "end": row["time"],
                                         "action": "received_pause", "source": "authenticated_media_state"})
            pause_start = None
    if pause_start and capture_end:
        evaluation_intervals.append({"device": device, "start": pause_start, "end": capture_end,
                                     "action": "received_pause", "source": "authenticated_media_state"})
    source = manifest.get("scenario", {}).get("source")
    deliberate_end = source is not None and any(item.get("device") == source and item.get("action") == "end"
                                               for item in manifest.get("selection_events", []))
    completed = next((row for row in selected if row["message"] ==
                      "Stream ended; local and downstream completion acknowledged."), None)
    if deliberate_end and completed and capture_end:
        evaluation_intervals.append({"device": device, "start": completed["time"], "end": capture_end,
                                     "action": "received_deliberate_end", "source": "authenticated_media_completion"})
    for control in manifest.get("control_events", []):
        terminal = control.get("terminal_evidence", {})
        if (control.get("action") != "end" or control.get("acknowledged") is not True or
                control.get("state") == "FAIL" or not control.get("ack_time") or not capture_end or
                len(terminal) < 2 or device not in terminal or control.get("device") not in terminal or
                any(not proof.get("ended") or not proof.get("available") for proof in terminal.values())):
            continue
        proof = terminal[device]
        ended = next((row for row in selected if row["time"] == proof["ended"] and
                      re.fullmatch(rf"(?:{re.escape(ENDED_CALLBACK)} |{re.escape(ENDED_UI)}){NORMAL_END}{ENDED_CALL_ID}",
                                   row["message"])), None)
        available = next((row for row in selected if row["time"] == proof["available"] and
                          row["message"].startswith("BENCHMARK call available:")), None)
        phases = [row for row in selected if ended and row["at"] <= ended["at"] and
                  row["message"].startswith("BENCHMARK call phase ")]
        if (not ended or not available or available["at"] < ended["at"] or not phases or
                phases[-1]["message"] != "BENCHMARK call phase ENDED" or
                any(row["at"] > ended["at"] and row["message"] == "BENCHMARK call phase ACTIVE" for row in selected)):
            continue
        if control["device"] == device and not any(row["time"] == control["ack_time"] and
                                                  row["message"] == "BENCHMARK control end applied" for row in selected):
            continue
        evaluation_intervals.append({"device": device, "start": phases[-1]["time"], "end": capture_end,
                                     "action": "received_deliberate_private_end", "source": "planned_control_and_local_terminal",
                                     "ended": ended["time"], "available": available["time"]})
    parent_recoveries = []
    for change in manifest.get("topology_changes", []):
        if change.get("device") != device:
            continue
        at = timestamp(change["time"])
        source = manifest.get("scenario", {}).get("source")
        source_prefix = manifest.get("devices", {}).get(source, {}).get("node_prefix")
        prior_routes = [match for row in selected if row["at"] < at and
                        (match := re.search(r"Receiving .* from [0-9a-f]{12} over link ([0-9a-f]{12}):", row["message"]))]
        if source_prefix and prior_routes and prior_routes[-1][1] == source_prefix and change.get("removed_ancestor") != source:
            parent_recoveries.append({"action": "planned_parent_change", "time": change["time"],
                "removed_ancestor": change.get("removed_ancestor"), "exercised": False,
                "first_surface_ms": gate(None, limits["max_startup_ms"],
                    reason="Receiver already authenticated direct source before requested middle removal; ancestor-loss transition not exercised")})
            continue
        first = next((row for row in selected if row["at"] >= at and
                      "Receiver startup: selection-to-first-surface " in row["message"]), None)
        end = first["time"] if first else capture_end
        if end:
            evaluation_intervals.append({"device": device, "start": change["time"], "end": end,
                                         "action": "planned_parent_change", "source": "runner"})
        elapsed = (first["at"] - at) * 1000 if first else None
        recovery = gate(elapsed, limits["max_startup_ms"])
        if not first and capture_end and (timestamp(capture_end) - at) * 1000 >= limits["max_startup_ms"]:
            recovery = {"status": "FAIL", "value": None, "limit": limits["max_startup_ms"],
                        "reason": "No first-surface recovery marker after planned ancestor loss within the captured deadline"}
        parent_recoveries.append({"action": "planned_parent_change", "time": change["time"],
                                  "exercised": True,
                                  "removed_ancestor": change.get("removed_ancestor"), "first_surface_ms": recovery})
    planned = [{**item, "from": timestamp(item["start"]), "to": timestamp(item["end"])}
               for item in evaluation_intervals]
    # A confirmed private hang-up bounds sampled FPS; it cannot locate cumulative
    # losses or remove measured rolling cadence, encoder output or fatal events.
    lifecycle_planned = [item for item in planned if item.get("action") != "received_deliberate_private_end"]
    carrier_end_windows = [(item["from"] - CARRIER_END_LEAD_S, item["to"]) for item in planned
                           if item.get("action") == "received_deliberate_private_end"]
    # The proof above needs both phones' terminal records; a log that lost one still shows this phone's own end: the runner's
    # End control was acknowledged and the log's last phase is ENDED (no ACTIVE after it). The hang-up phone's ENDED must also
    # sit next to its own control acknowledgement (same clock); the peer has no same-clock reference, only the structure.
    ended = [row for row in selected if row["message"] == "BENCHMARK call phase ENDED"]
    end_controls = [item for item in manifest.get("control_events", []) if item.get("action") == "end"
                    and item.get("acknowledged") is True and item.get("state") != "FAIL"]
    if ended and end_controls and not any(row["at"] > ended[-1]["at"] and row["message"] == "BENCHMARK call phase ACTIVE"
                                          for row in selected):
        own = next((item for item in end_controls if item.get("device") == device and item.get("ack_time")), None)
        if own is None or abs(ended[-1]["at"] - timestamp(own["ack_time"])) <= 2.0:
            carrier_end_windows.append((ended[-1]["at"] - CARRIER_END_LEAD_S, ended[-1]["at"] + CARRIER_END_LEAD_S))
    wifi_offs = planned_wifi_offs(manifest)
    surface, encoder, startup, queues, events, thermal, profiles = [], [], [], [], [], [], []
    route_connections, authenticated_streams, encoder_configurations = [], [], []
    route_cutoff = min((timestamp(item["time"]) for item in manifest.get("topology_changes", [])
                        if item.get("device") == device), default=float("inf"))
    counters = {"decoder_drops": [], "audio_drops": [], "sender_drops": [], "forwarding_drops": [],
                "camera_input_drops": [], "raw_input_drops": []}
    encoder_phase = 0
    for row in selected:
        message = row["message"]
        connection = re.search(r"Receiving .* from ([0-9a-f]{12}) over link ([0-9a-f]{12}):", message)
        if connection:
            route_connections.append({"time": row["time"], "source_prefix": connection[1],
                                      "authenticated_endpoint_prefix": connection[1],
                                      "source_prefix_kind": "authenticated_adjacent_endpoint; original source proof separate",
                                      "parent_prefix": connection[2],
                                      "after_planned_parent_change": row["at"] >= route_cutoff})
        identity = re.search(r"Authenticated stream identity: original=([0-9a-f]{12}) \| "
                             r"parent=([0-9a-f]{12}) \| stream=(\S+) \| forwarded=(true|false)", message)
        if identity:
            authenticated_streams.append({"time": row["time"], "original_source_prefix": identity[1],
                "parent_prefix": identity[2], "stream": identity[3], "forwarded": identity[4] == "true",
                "after_planned_parent_change": row["at"] >= route_cutoff})
        route = re.match(r"(.+?\[[0-9a-f]+\]):", message)
        route = route[1] if route else "direct"
        configured_encoder = re.match(r"Encoder statistics (?:enabled|unavailable) for (\S+)$", message)
        if configured_encoder:
            encoder_phase += 1
            encoder_configurations.append({"time": row["time"], "encoder": configured_encoder[1],
                                           "evidence": "encoder initialized"})
        capture_loss = re.search(r"capture drops (\d+)|Native encoder dropped (\d+) camera frame\(s\)", message)
        if capture_loss:
            counters["camera_input_drops"].append((row["at"], int(capture_loss[1] or capture_loss[2]), f"encoder-phase-{encoder_phase}"))
        raw_loss = re.search(r"raw input drops (\d+)", message)
        if raw_loss:
            counters["raw_input_drops"].append((row["at"], int(raw_loss[1]), f"encoder-phase-{encoder_phase}"))
        raw = re.match(r"Benchmark raw source: .+?, \d+ looping I420 frames, (\d+)x(\d+) at "
                       r"(\d+) FPS, (\S+), (\d+) kbps, (.+)", message)
        profile = re.search(r"Streaming .* at (\d+)[×x](\d+), (\d+) FPS, (\d+) kbps", message)
        if raw or profile:
            actual = raw or profile
            applied = {"time": row["time"], "kind": "raw_encoder_target" if raw else "encoder_target",
                       "width": int(actual[1]), "height": int(actual[2]), "fps": int(actual[3]),
                       "bitrate_kbps": int(actual[5] if raw else actual[4])}
            if raw:
                encoder_configurations.append({"time": row["time"], "encoder": raw[4],
                                               "evidence": "raw encoder started"})
            for label, (allocation, _) in ALLOCATION_LABELS.items():
                if label in message:
                    applied["rate_control"] = allocation
                    numeric = re.search(re.escape(label) + r" mode (\d+)", message)
                    applied["bitrate_mode"] = int(numeric[1]) if numeric else None
                    break
            profiles.append(applied)
        else:
            profile = re.search(r"Receiving .*: \S+ (\d+)[×x](\d+) at (\d+) FPS", message)
            if profile:
                profiles.append({"time": row["time"], "kind": "received_display", "width": int(profile[1]),
                                 "height": int(profile[2]), "fps": int(profile[3])})
        match = CONTINUITY.search(message)
        if match:
            label, final, frames, freezes, duration, ratio, threshold = match.groups()
            cadence = CADENCE.search(message)
            surface.append({"time": row["time"], "at": row["at"], "label": label,
                            "final": bool(final), "frames": int(frames), "freeze_events": int(freezes),
                            "freeze_duration_ms": float(duration), "freeze_ratio_percent": float(ratio),
                            "freeze_threshold_ms": float(threshold),
                            "cadence_ms": dict(zip(("p50", "p95", "p99", "max"), map(float, cadence.groups()))) if cadence else None})
        match = ENCODER.search(message)
        if match:
            encoder.append({"time": row["time"], "fps": float(match[1]), "kbps": float(match[2]),
                            "planned": overlaps(lifecycle_planned, row["at"] - 5, row["at"])})
        match = STARTUP.search(message)
        if match:
            startup.append({"time": row["time"], "kind": match[2], "ms": float(match[3])})
        if "Call RX " in message:
            match = re.search(r"audio drops (\d+)", message)
            if match:
                counters["audio_drops"].append((row["at"], int(match[1])))
            match = re.search(r"queue (\d+)/(\d+), drops (\d+)", message)
            if match:
                queues.append({"time": row["time"], "kind": "decoder", "depth": int(match[1]), "capacity": int(match[2])})
                counters["decoder_drops"].append((row["at"], int(match[3])))
        if "decoder " in message and "BT queue " in message:
            match = re.search(r"decoder (\d+)/(\d+) \((\d+) ms, drops (\d+)\)", message)
            if match:
                queues.append({"time": row["time"], "kind": "decoder", "depth": int(match[1]), "capacity": int(match[2]), "oldest_ms": int(match[3])})
                counters["decoder_drops"].append((row["at"], int(match[4])))
            match = re.search(r"audio via [^,]+, drops (\d+)", message)
            if match:
                counters["audio_drops"].append((row["at"], int(match[1])))
        if " TX: " in message:
            relay = re.search(r"^Relay .* TX: .* queue (\d+)/(\d+) \| drops (\d+)", message)
            if relay:
                queues.append({"time": row["time"], "kind": "relay", "depth": int(relay[1]), "capacity": int(relay[2])})
                counters["forwarding_drops"].append((row["at"], int(relay[3])))
            # Since 2026-10-07 relays never drop media: the line reports depth and backlog instead.
            relay = re.search(r"^Relay .* TX: .* queue (\d+) \| backlog (\d+) ms", message)
            if relay:
                queues.append({"time": row["time"], "kind": "relay", "depth": int(relay[1]), "oldest_ms": int(relay[2])})
                counters["forwarding_drops"].append((row["at"], 0))
            match = re.search(r"queue (\d+)/(\d+), (\d+) KB, (\d+) ms \| drops (\d+) \| thermal (\d+)", message)
            if match:
                queues.append({"time": row["time"], "kind": "sender", "depth": int(match[1]), "capacity": int(match[2]), "kb": int(match[3]), "oldest_ms": int(match[4])})
                counters["sender_drops"].append((row["at"], int(match[5]), route))
                thermal.append({"time": row["time"], "status": int(match[6])})
        sender_loss = re.search(
            r"Bluetooth (?:reached its latency limit|is saturated).*?\((\d+) packets dropped\)|"
            r"Bluetooth caught up at a keyframe after dropping (\d+) packets\.", message)
        if sender_loss:
            counters["sender_drops"].append((row["at"], int(sender_loss[1] or sender_loss[2]), route))
        no_loss_withdrawal = re.search(r"Withdrawing congested downstream route: (\d+) queued, (\d+) ms behind live", message)
        if no_loss_withdrawal:
            queues.append({"time": row["time"], "kind": "relay", "depth": int(no_loss_withdrawal[1]),
                           "oldest_ms": int(no_loss_withdrawal[2])})
            counters["forwarding_drops"].append((row["at"], 0))
        withdrawal = re.search(r"Withdrawing congested downstream route: queue (\d+)/(\d+), drops (\d+)", message)
        if withdrawal:
            queues.append({"time": row["time"], "kind": "relay", "depth": int(withdrawal[1]),
                           "capacity": int(withdrawal[2])})
            counters["forwarding_drops"].append((row["at"], int(withdrawal[3])))
        if message.startswith(ENDED_UI):
            # Teardown record of an end already logged by the call thread: counted once, there. Without that
            # record (lost in the log) it stands in for it, so an abnormal end is still an unexpected event.
            if any(other["message"].startswith(ENDED_CALLBACK) and 0 <= row["at"] - other["at"] <= ENDED_UI_PAIRING_S
                   for other in selected):
                continue
            message = f"{ENDED_CALLBACK} {message[len(ENDED_UI):]}"
        if message.startswith(CONFIGURATION_LISTINGS):
            # Launch-time inventories name protections and proposals ("link-lost-after-change-rollback" since
            # 71ef65b, 2026-10-14), not events: matching them counted one false unexpected event per launch.
            continue
        if UNEXPECTED.search(message):
            # A normal hang-up is not proof of an unexpected failure.
            normal_end = re.search(r"Private call ended: (?:Call ended|Peer ended)", message, re.IGNORECASE)
            carrier_end = bool(CARRIER_END_REASON.search(message) or LINK_CLOSE_AT_END.search(message)) and any(
                low <= row["at"] <= high for low, high in carrier_end_windows)
            wifi_off_death = "BENCHMARK carrier_death:" in message and planned_wifi_off_death(message, row["at"], wifi_offs)
            events.append({"time": row["time"], "message": message,
                           "planned": bool(normal_end) or carrier_end or wifi_off_death or
                                      overlaps(lifecycle_planned, row["at"], row["at"])})

    segments = []
    for sample in surface:
        if not segments or segments[-1][-1]["label"] != sample["label"] or segments[-1][-1]["final"] or any(
            sample[key] < segments[-1][-1][key] for key in ("frames", "freeze_events", "freeze_duration_ms")
        ):
            segments.append([])
        segments[-1].append(sample)
    intervals, clean_cadence, freeze_rows, duration_rows = [], [], [], []
    for segment_id, segment in enumerate(segments):
        previous = None
        for sample in segment:
            at = sample["at"]
            freeze_rows.append((at, sample["freeze_events"], str(segment_id)))
            duration_rows.append((at, sample["freeze_duration_ms"], str(segment_id)))
            if previous and at > previous["at"]:
                planned_gap = overlaps(planned, previous["at"], at)
                intervals.append({"start": previous["time"], "end": sample["time"],
                                  "visible_fps": (sample["frames"] - previous["frames"]) / (at - previous["at"]),
                                  "planned_overlap": planned_gap})
            cadence = sample["cadence_ms"]
            # Rolling 256-gap percentiles have no individual timestamps. If this
            # segment includes a planned pause, attribution cannot be reconstructed.
            sample["cadence_lifecycle_overlap"] = overlaps(lifecycle_planned, segment[0]["at"], at)
            if sample["frames"] > 1 and cadence and cadence["p50"] > 0 and not sample["cadence_lifecycle_overlap"]:
                clean_cadence.append(cadence)
            previous = sample
    confirmed_end = [item for item in planned if item.get("action") == "received_deliberate_private_end"]
    planned_end_freezes = planned_end_freeze_samples(segments, confirmed_end)
    # When logd dropped an endpoint's end line, no planned-end interval can be confirmed for either phone. A FINAL report
    # the rule would have judged if the proof existed (one short freeze) is then neither excused nor blamed: it is set
    # aside here and the gate is UNMEASURED. Freezes in earlier periodic reports are still counted.
    end_loss = [item["terminal_log_loss"] for item in manifest.get("control_events", [])
                if item.get("action") == "end" and item.get("terminal_log_loss")]
    unjudged_end_freezes = [] if confirmed_end or not end_loss else planned_end_freeze_samples(
        segments, [{"from": float("-inf"), "to": float("inf")}])
    planned_at = {item["at"] for item in planned_end_freezes + unjudged_end_freezes}
    freezes = counter_summary(freeze_rows, lifecycle_planned, planned_at)
    durations = counter_summary(duration_rows, lifecycle_planned, planned_at)
    clean_fps = [item["visible_fps"] for item in intervals if not item["planned_overlap"]]
    clean_encoder = [item for item in encoder if not item["planned"]]
    drop_totals = {key: counter_summary(values, lifecycle_planned) for key, values in counters.items()}
    refresh = (*display_refresh(selected, spec, manifest),
               limits.get("surface_refresh_tolerance_periods", DISPLAY_REFRESH_TOLERANCE_PERIODS))
    gates = {
        "surface_p95_ms": frame_interval_gate(max((item["p95"] for item in clean_cadence), default=None), period,
                                              limits["surface_p95_periods"], refresh),
        "surface_p99_ms": frame_interval_gate(max((item["p99"] for item in clean_cadence), default=None), period,
                                              limits["surface_p99_periods"], refresh),
        "visible_fps": gate(min(clean_fps, default=None), fps * limits["visible_fps_fraction"] if fps else None, "min"),
        "unexpected_freeze_events": gate(freezes["unexpected"], limits["max_freeze_events"]),
        "unexpected_events": gate(sum(not item["planned"] for item in events) if selected else None, limits["max_unexpected_events"]),
    }
    if not fps:
        for key in ("surface_p95_ms", "surface_p99_ms", "visible_fps"):
            gates[key] = gate(None, None, reason="Expected FPS not specified")
    if unjudged_end_freezes and gates["unexpected_freeze_events"]["status"] == "PASS":
        gates["unexpected_freeze_events"] = {**gate(None, limits["max_freeze_events"], reason=END_PROOF_LOST_REASON),
                                             "detail": "The freeze in the FINAL report cannot be placed against a planned End: "
                                                       "logd dropped the end line of at least one endpoint",
                                             "log_loss": end_loss, "unjudged": unjudged_end_freezes}
    if freezes["lifecycle_overlap"] and gates["unexpected_freeze_events"]["status"] == "PASS":
        gates["unexpected_freeze_events"] = gate(None, limits["max_freeze_events"], reason="Freeze delta overlaps a planned action; cannot allocate individual gaps")
    if any(segment[0]["frames"] != 1 for segment in segments) and gates["unexpected_freeze_events"]["status"] == "PASS":
        gates["unexpected_freeze_events"] = gate(None, limits["max_freeze_events"], reason="Capture lacks first-surface baseline for a continuity segment")
    role = spec.get("role", "receiver")
    for key, value in drop_totals.items():
        if key in {"camera_input_drops", "raw_input_drops"}:
            expected_kind = "live_camera" if key == "camera_input_drops" else "raw_fixture_live_encoder"
            source_kind = manifest.get("source_kind")
            if role != "sender" or source_kind is not None and source_kind != expected_kind or source_kind is None and not counters[key]:
                continue
            gates[key] = gate(value["unexpected"], limits["max_drops"],
                              reason="Explicit cumulative source input-loss evidence absent; timing samples or zero network drops cannot substitute")
            if value["lifecycle_overlap"] and gates[key]["status"] == "PASS":
                gates[key] = gate(None, limits["max_drops"], reason="Source input-loss counter delta overlaps planned lifecycle action")
            continue
        if key == "forwarding_drops" and not counters[key] and not any(
            item.get("parent") == device for item in manifest.get("scenario", {}).get("receivers", [])
        ):
            continue
        if key == "sender_drops" and role == "receiver" or key != "sender_drops" and role == "sender":
            continue
        gates[key] = gate(value["unexpected"], limits["max_drops"])
        if value["lifecycle_overlap"] and gates[key]["status"] == "PASS":
            gates[key] = gate(None, limits["max_drops"], reason="Counter delta overlaps planned lifecycle action")
    if role == "sender":
        for key in ("surface_p95_ms", "surface_p99_ms", "visible_fps", "unexpected_freeze_events"):
            gates.pop(key)
    if role in ("sender", "private"):
        gates["encoder_fps"] = gate(min((item["fps"] for item in clean_encoder), default=None), fps * limits["encoder_fps_fraction"] if fps else None, "min") if fps else gate(None, None, reason="Expected FPS not specified")
    discovery = [item["ms"] for item in startup if item["kind"] == "browser-to-first-offer"]
    selection = [item["ms"] for item in startup if item["kind"] == "selection-to-first-surface"]
    if role != "sender":
        # A broadcast source never browses for offers; requiring this made every sender UNMEASURED.
        gates["discovery_ms"] = gate(max(discovery, default=None), limits["max_discovery_ms"])
        gates["selection_to_first_surface_ms"] = gate(max(selection, default=None), limits["max_startup_ms"])
    width = spec.get("expected_width", manifest.get("expected_width"))
    height = spec.get("expected_height", manifest.get("expected_height"))
    if width is not None and height is not None:
        # A display rotation may swap width/height, but must not change pixel shape.
        mismatches = sum(sorted((item["width"], item["height"])) != sorted((width, height)) for item in profiles)
        gates["profile_resolution_mismatches"] = gate(mismatches if profiles else None, 0)
    if fps is not None and role in {"sender", "receiver"}:
        fps_profiles = [item for item in profiles if (item["kind"] == "received_display") == (role == "receiver")]
        gates["profile_fps_mismatches"] = gate(sum(item["fps"] != fps for item in fps_profiles) if fps_profiles else None, 0)
    requested_profile = manifest.get("scenario", {}).get("profile")
    if role == "sender" and requested_profile is not None:
        requested_encoder = requested_profile.get("codec")
        exact_encoder = requested_encoder and requested_encoder not in {"vp9", "av1", "native_av1", "automatic"}
        gates["profile_encoder_mismatches"] = gate(
            sum(item["encoder"] != requested_encoder for item in encoder_configurations)
            if encoder_configurations and exact_encoder else None, 0,
            reason="Exact requested encoder and post-initialization runtime evidence required; a codec alias or test-launch intent is insufficient")
        allocation = requested_profile.get("rate_control", "cbr")
        numeric_mode = next((mode for name, mode in ALLOCATION_LABELS.values() if name == allocation), None)
        applied = [item for item in profiles if item["kind"] in {"encoder_target", "raw_encoder_target"}
                   and "rate_control" in item]
        mismatches = sum(item["rate_control"] != allocation or
                         numeric_mode is not None and item["bitrate_mode"] is not None and item["bitrate_mode"] != numeric_mode
                         for item in applied)
        gates["profile_allocation_mismatches"] = gate(mismatches if applied else None, 0,
            reason="Post-start source allocation label/mode required; test-launch intent is insufficient")
    expected_bitrate = spec.get("expected_bitrate_kbps", manifest.get("expected_bitrate_kbps"))
    median_load = statistics.median(item["kbps"] for item in clean_encoder) if clean_encoder else None
    load_fraction = median_load / expected_bitrate if median_load is not None and expected_bitrate else None
    offered_load = {"status": "PASS" if load_fraction is not None and load_fraction >= limits["offered_load_fraction"] else "UNMEASURED",
                    "median_kbps": median_load, "requested_kbps": expected_bitrate,
                    "target_fraction": load_fraction, "required_fraction": limits["offered_load_fraction"],
                    "reason": "Measured encoder payload, not the selected target; insufficient or absent load does not prove requested transport capacity"}
    if expected_bitrate is not None:
        targets = [item["bitrate_kbps"] for item in profiles if "bitrate_kbps" in item]
        gates["profile_bitrate_mismatches"] = gate(sum(value != expected_bitrate for value in targets) if targets else None, 0,
                                                  reason="Receiver logs lack configured source bitrate; join sender evidence")
    parent = next((item["parent"] for item in manifest.get("scenario", {}).get("receivers", [])
                   if item["phone"] == device), None)
    if parent is not None:
        expected_parent = manifest.get("devices", {}).get(parent, {}).get("node_prefix")
        parent_evidence = authenticated_streams or route_connections
        checked_routes = [item for item in parent_evidence if not item["after_planned_parent_change"]]
        mismatches = sum(item["parent_prefix"] != expected_parent for item in checked_routes)
        gates["requested_parent_identity"] = gate(mismatches if expected_parent and checked_routes else None, 0,
                                                  reason="Requested parent identity and authenticated connection logs required; fallback is not chain coverage")
    if role == "receiver" and manifest.get("scenario", {}).get("source"):
        source = manifest["scenario"]["source"]
        expected_source = manifest.get("devices", {}).get(source, {}).get("node_prefix")
        mismatches = sum(item["original_source_prefix"] != expected_source for item in authenticated_streams)
        gates["requested_original_source_identity"] = gate(
            mismatches if authenticated_streams and expected_source else None, 0,
            reason="Explicit authenticated original-source marker or source identity is absent; adjacent signer is not source proof")
    decoder_rows = [match for row in selected if (match := DECODER_LINE.search(row["message"]))]
    requested_decoders = [match for match in decoder_rows if match[1] != "none"]
    if requested_decoders:
        gates["requested_decoder_mismatches"] = gate(
            sum(match[1].lower() != match[2].lower() for match in requested_decoders), 0,
            reason="Every BENCHMARK decoder line with a requested decoder must report it as effective")
    link_phy = [{"time": row["time"], "requested": match[1], "effective": match[2], "reason": match[3],
                 "source": match[4]}
                for row in selected if (match := LINK_PHY_LINE.search(row["message"]))]
    run_requested_phy = [item for item in link_phy if item["source"] == "run"]
    if run_requested_phy:
        gates["requested_link_phy_mismatches"] = gate(
            sum(item["requested"].lower() != item["effective"].lower() for item in run_requested_phy), 0,
            reason="A link PHY requested by the run must be the effective PHY")
    optical = [item for item in manifest.get("optical_results", []) if item.get("device") == device
               and item.get("aligned") is True and item.get("reference_kind") == "raw_fixed_fixture"
               and item.get("coverage_matches") is True]
    optical_gates = {metric: gate(min((item[metric] for item in optical if metric in item), default=None), limits[limit], "min",
                                "Aligned raw-fixture optical result with matching run coverage required")
                     for metric, limit in (("vmaf", "min_vmaf"), ("ssim", "min_ssim"), ("psnr_db", "min_psnr_db"))}
    acceptance_report = None
    if limits["version"] == acceptance.V4:
        acceptance_report = apply_user_profile(gates, optical_gates, limits, {
            "manifest": manifest, "role": role, "segments": segments, "planned": planned, "fps": fps,
            "clean_cadence": clean_cadence, "durations": durations, "intervals": intervals, "profiles": profiles,
            "queues": queues,
            "is_call": manifest.get("media_kind") == "call" or manifest.get("mode") == "private_call" or
                       any(sample["label"] == "Private call" for sample in surface)})
    action_results = []
    # First-surface gates need this device's own rendered surface; a broadcast source has none, so its
    # pause/resume/end commands are scored on receivers, not as permanently absent sender gates.
    # Harness bookkeeping events (adb_retry, log_capture_restarted, log_dump_merged, ...) carry no phone
    # clock time and are not controls; reading their "time" raised KeyError (mesh4-7a6c401c).
    actions = [item for item in manifest.get("selection_events", [])
               if item.get("device") == device and role != "sender" and "time" in item]
    for action_index, action in enumerate(actions):
        if action.get("device") != device:
            continue
        at = timestamp(action["time"])
        # Require a first-surface snapshot, not the next periodic report.
        following = timestamp(actions[action_index + 1]["time"]) if action_index + 1 < len(actions) else float("inf")
        first = next((sample for sample in surface if at <= sample["at"] < following and sample["frames"] == 1), None)
        elapsed = (first["at"] - at) * 1000 if first else None
        action_gate = gate(elapsed, limits["max_startup_ms"])
        if action["action"] == "launch" and limits.get("join_gate_basis") == "selection":
            # Design decision 2026-11-06 (Q-STARTUP-GATE): launch (cold start plus browsing) is reported, not gated;
            # the 3 s join gate is selection_to_first_surface_ms.
            action_gate = {**as_diagnostic(action_gate), "metric": "launch_to_first_surface_ms",
                           "reason": "Launch-to-first-surface is reported separately; the join gate is selection_to_first_surface_ms"}
        if action["action"] == "connection_timeout":
            action_gate = {"status": "FAIL", "value": elapsed, "limit": limits["max_startup_ms"],
                           "reason": "Runner recorded a connection timeout; later frames do not erase the failure"}
        action_results.append({"action": action["action"], "time": action["time"],
                               "first_surface_ms": action_gate})
    action_results.extend(parent_recoveries)
    action_gates = [item["first_surface_ms"] for item in action_results]
    lifecycle_status = aggregate_status(action_gates)
    if action_gates and all(item["status"] == "DIAGNOSTIC" for item in action_gates):
        # Only the reported-not-gated launch row exists: no lifecycle action decides, and the join itself is judged
        # by the selection_to_first_surface_ms gate (absent that, it stays UNMEASURED there, never a pass).
        lifecycle_status = "PASS"
    return {"device": device, "role": role, "scope": scope, "expected_fps": fps,
            "thresholds_version": limits["version"], "acceptance": acceptance_report,
            "expected_width": width, "expected_height": height, "expected_bitrate_kbps": expected_bitrate,
            "log_status": aggregate_status(gates.values()), "quality_status": aggregate_status(optical_gates.values()),
            "lifecycle_status": lifecycle_status,
            "overall_status": aggregate_status([*gates.values(), *optical_gates.values(), {"status": lifecycle_status}]),
            "gates": gates, "optical_gates": optical_gates, "optical_results": optical,
            "link_phy": link_phy,
            "surface_segments": segments, "visible_fps_intervals": intervals,
            "surface_percentile_summary": "Worst observed rolling p95/p99; never averaged or represented as pooled percentiles",
            "freezes": freezes, "freeze_duration_ms": durations, "planned_end_freezes": planned_end_freezes,
            "unjudged_end_freezes": unjudged_end_freezes,
            "drops": drop_totals,
            "input_loss_attribution": {"camera_input_drops": "Reported native camera input-pool cumulative loss; not every possible Camera2 miss",
                                       "raw_input_drops": "Cumulative raw scheduled slots missed or input buffers unavailable",
                                       "sender_drops": "Independent transport admission/recovery loss; never a substitute for source input loss"},
            "encoder": {"windows": encoder, "fps": value_range([item["fps"] for item in encoder]),
                        "kbps": value_range([item["kbps"] for item in encoder])},
            "offered_load": offered_load, "route_connections": route_connections,
            "authenticated_streams": authenticated_streams,
            "encoder_configurations": encoder_configurations,
            "capacity_status": aggregate_status([*gates.values(), offered_load]),
            "queues": queues, "thermal": thermal, "startup": startup, "events": events, "profiles": profiles,
            **({"wifi_off_events": wifi_off_report(device, wifi_offs, selected, surface)} if wifi_offs else {}),
            "coverage": {"actions_exercised": [item["action"] for item in action_results],
                         "optical_latency": "UNMEASURED", "aligned_fixture_fidelity": aggregate_status(optical_gates.values()),
                         "motion_blur": "UNMEASURED", "blockiness": "UNMEASURED"},
            "actions": action_results, "planned_intervals": manifest.get("planned_intervals", []),
            "evaluation_intervals": evaluation_intervals,
            "limitations": ["TextureView updates are app-visible continuity, not optical glass-to-glass latency",
                            "Decoder shown/submitted cadence never substitutes for surface cadence",
                            "No blur/blockiness claim from bitrate, FPS, or logs",
                            "Unexercised switching/recovery actions and physical optical latency remain UNMEASURED"]}


def score_manifest(manifest, base_path=Path("."), thresholds=None):
    devices = []
    for spec in manifest.get("logs", []):
        path = resolve(spec["path"], base_path)
        text = path.read_text(encoding="utf-8-sig", errors="replace")
        devices.append({"path": str(path), **score_log(text, spec, manifest, thresholds)})
        crash = app_crash_gate(manifest, spec.get("device"), base_path)
        if crash is not None:
            devices[-1]["gates"]["app_crash_or_anr"] = crash
            devices[-1]["log_status"] = aggregate_status(devices[-1]["gates"].values())
            devices[-1]["overall_status"] = aggregate_status([
                *devices[-1]["gates"].values(), *devices[-1]["optical_gates"].values(),
                {"status": devices[-1]["lifecycle_status"]}])
    execution = {"runner_error": {"status": "FAIL" if manifest.get("runner_error") else "PASS",
                                  "value": manifest.get("runner_error")},
                 "connection_timeouts": gate(sum(item.get("action") == "connection_timeout"
                                                  for item in manifest.get("selection_events", [])), 0),
                 "captured_logs": gate(len(devices) if devices else None, 1, "min",
                                       reason="No device logs were captured")}
    if manifest.get("scenario", {}).get("media_trace"):
        traces = manifest.get("media_traces", [])
        participants = {manifest["scenario"]["source"],
                        *(node["phone"] for node in manifest["scenario"]["receivers"])}
        measured = {trace["device"] for trace in traces if trace["state"] == "MEASURED"}
        state = "FAIL" if any(trace["state"] == "FAIL" for trace in traces) else "PASS" if measured == participants and all(
            trace["state"] == "MEASURED" for trace in traces) else "UNMEASURED"
        execution["media_traces"] = {"status": state, "value": len(measured), "limit": len(participants),
                                     "reason": "Requested finalized trace evidence must be hash-verified for every participant and every captured phase"}
    return {"schema_version": 1, "run_id": manifest.get("run_id"),
            "thresholds": limits_for(thresholds), "thresholds_version": limits_for(thresholds)["version"],
            "devices": devices,
            "execution_gates": execution,
            "overall_status": aggregate_status([*execution.values(), *[{"status": item["overall_status"]} for item in devices]])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", nargs="*", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--thresholds", type=Path, help="JSON overrides; retain a distinct version when changing gates")
    parser.add_argument("--profile", choices=(acceptance.V3, acceptance.V4), default=None,
                        help="Acceptance profile; default keeps device-run-v3")
    parser.add_argument("--fps", type=float)
    parser.add_argument("--pid", type=int)
    parser.add_argument("--start")
    parser.add_argument("--end")
    parser.add_argument("--role", choices=("sender", "receiver", "private"), default="receiver")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.output:
        args.output = output_path(args.output, "runs")
        if args.output.exists():
            raise FileExistsError(args.output)
    if args.manifest:
        args.manifest = resolve(args.manifest)
    if args.thresholds:
        args.thresholds = resolve(args.thresholds)
    args.logs = [resolve(path) for path in args.logs]
    manifest = json.loads(args.manifest.read_text(encoding="utf-8-sig")) if args.manifest else {
        "expected_fps": args.fps, "logs": [{"path": str(logical(path)), "device": path.stem,
                                            "pid": args.pid, "start": args.start, "end": args.end,
                                            "role": args.role} for path in args.logs]}
    if not manifest.get("logs"):
        parser.error("Supply at least one log or a manifest with logs")
    thresholds = json.loads(args.thresholds.read_text(encoding="utf-8-sig")) if args.thresholds else None
    if args.profile:
        thresholds = {**(thresholds or {}), "version": args.profile}
    result = score_manifest(manifest, original(args.manifest).parent if args.manifest else Path("."), thresholds)
    encoded = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8")
        print(f"{result['overall_status']} -> {args.output}")
        for item in result["devices"]:
            failures = [key for key, value in item["gates"].items() if value["status"] == "FAIL"]
            print(f"{item['device']}: logs {item['log_status']}, optical {item['quality_status']}; failures: {', '.join(failures) or 'none observed'}")
    else:
        print(encoded, end="")


if __name__ == "__main__":
    main()
