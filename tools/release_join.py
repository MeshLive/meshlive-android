"""The release-gate join metric (design decision 2026-11-10, DECISIONS "First-principles audit (2026-11-10)", R3).

"Join time keeps 3 s as the target and ships against a recorded interim line (90% of calls show a real picture within
6 s, none over 15 s), measured as two app-clock stages from an idle callee: dial to ringing, and Accept to the first
non-black picture."

Per call and receiving phone R:

* stage 1, the caller's clock: `dial` -> `ringing` (`BENCHMARK call_join_stage` lines of the caller's PID);
* stage 2, R's own clock: R's `accept` stage -> R's first `first_frame_shown` with `black=false`
  (the callee's Accept is its local tap, the caller's is the arriving ACCEPT signal);
* join = stage 1 + stage 2. Each stage is a difference of `atMs` (SystemClock.elapsedRealtime) values read from one
  phone's lines, so no clock of one phone is ever subtracted from a clock of another.

A call's join is the larger of its two receiving directions (both people have to see the other). A first shown frame
that is black counts only when the app logs a later non-black shown frame; it logs the stage once, so a black first
picture leaves the direction UNMEASURED. A build without the stage lines leaves the metric UNMEASURED, never FAIL; a
build with them whose call never rang or never showed a picture is a failed join (the user's call did not connect),
unless logd reported dropping that phone's lines there.

Diagnostics, never part of the join or the gate (JT2): `tap_to_invite_ms` and `tap_to_ringing_ms` (the app's
`call_join_stage: stage=tap` line, written by the dial that follows a tap, carries a negative `sinceStartMs` on purpose:
the tap came before the dial; both values are differences of the caller's own `atMs`, the invite being the dial stage),
and the stage-1 sub-stage rows (`stage1_substages`: dial to link up, link up to authenticated, authenticated to ringing,
the link description, the first GATT MTU line and when it came after the link, and the count of status-133 setup
failures between the dial and ringing). The camera sub-stages and the first capture's `sensorLagMs`/`aeState`/
`exposureUs`/`iso` of each sender are listed as `sender_camera` for the same reading.

A receiving direction whose picture stayed black is never a join value (`SCENE_DARK`, status UNMEASURED, `reason_code`
SCENE_DARK): the app logged `first_nonblack_shown ... reached=false`, every shown picture was black (mean luma under the
app's 3.0 limit) with no later real one, or the first real picture came only after the app's 15 s dark bound with a black
first picture (the u6rm call that "joined" in 15.4 s because the scene was lit late). The lens of the sender saw nothing
to show; the app's connection and decode were not slow.

The suite level (`suite_gate`) is the release gate: PASS when at least 90% of the user-launch calls dialed from an idle
callee joined within 6 s and none took more than 15 s. Setup-to-first-surface against 3 s stays a per-call diagnostic
against the target.
"""
import math
import re

TARGET_MS = 3000.0            # the join target (kept); per-call setup_to_first_surface_ms is a diagnostic against it
INTERIM_LINE_MS = 6000.0      # the recorded interim line
INTERIM_SHARE_PERCENT = 90    # at least this share of the calls must be within the line
CEILING_MS = 15000.0          # no call may take longer
MINIMUM_CALLS = 20
MINIMUM_REASON = (
    "With fewer than 20 measured calls one slow call moves the 90% share by 5 points or more and a 15 s outlier "
    "has no company, so the share is not a measurement of the population; the release selection is about 120 calls.")

# Idle callee. The app's discovery fast window after an endpoint starts (BleL2capControl.LOW_LATENCY_SCAN_BURST_MS =
# 8 s of full-duty low-latency scan; LOW_LATENCY_ADVERTISE_BURST_MS = 10 s of 100 ms advertising, then the in-place
# balanced step, logged `adv_set_churn: event=balanced_in_place` about 10.1 s after the advertising start): a call
# dialed inside it meets a callee still in its launch burst, not the idle state a user calls into.
APP_FAST_WINDOW_S = 10.0
IDLE_QUALIFIES_S = APP_FAST_WINDOW_S + 1.0   # the balanced step has completed (observed 10.1 s)
RELEASE_CALLEE_IDLE_S = 12.0                 # the default for release-gate cases: just beyond the window

STAGE_LINE = re.compile(
    r"BENCHMARK call_join_stage: stage=(\w+) \| role=(caller|callee) \| sinceStartMs=(-?\d+) \| atMs=(-?\d+)"
    r"(?: \| callId=([0-9a-fA-F-]+))?(?: \| (.*))?$")
# The app's end-of-call repeat of every stage (CallJoinTimeline.end, B11): `stages=label@atMs[key=value;...],...`.
SUMMARY_LINE = re.compile(
    r"BENCHMARK call_join_summary: role=(caller|callee) \| startMs=(-?\d+)(?: \| callId=([0-9a-fA-F-]+))?"
    r" \| stages=(\S+)")
SUMMARY_STAGE = re.compile(r"(\w+)@(-?\d+)(?:\[([^\]]*)\])?")
# The receiver's periodic shown-frame count (MainActivity logReceiverContinuity): proof a picture reached the screen.
SHOWN_FRAMES = re.compile(r"BENCHMARK continuity: role=call_rx \|.*?\| frames=(\d+)")
KINDS = ("MEASURED", "FAILED_JOIN", "UNMEASURED")
SCENE_DARK = "SCENE_DARK"
DARK_BOUND_MS = 15000        # the app's FirstNonBlackWatch bound after Accept (CallJoinStages BOUND_MS)
BLACK_LUMA_MAX = 3.0         # the app's black limit (CallJoinStages BLACK_LUMA_MAX): a mean luma under it is black
CAMERA_STAGES = ("camera_open_requested", "preview_surface_ready", "camera_opened", "camera_session_configured",
                 "camera_first_frame")
MTU_LINE = re.compile(r"GATT video MTU (\d+)")
STATUS_133 = re.compile(r"\bstatus[= ]133\b")


def stage_events(rows):
    """The `call_join_stage` lines of one phone's scoped rows, in log order."""
    events = []
    for row in rows:
        match = STAGE_LINE.search(row["message"])
        if not match:
            continue
        stage, role, since, at, call_id, rest = match.groups()
        detail = dict(re.findall(r"(\w+)=([^|]+?)(?=\s*\||$)", rest or ""))
        events.append({"stage": stage, "role": role, "since_ms": int(since), "at_ms": int(at), "call_id": call_id,
                       "detail": {key: value.strip() for key, value in detail.items()},
                       "time": row["time"], "log_at": row["at"]})
    return _with_summary_stages(events, rows)


def _with_summary_stages(events, rows):
    """`events` plus each stage that only the call's end-of-call summary line still names (its own line was lost from
    the phone's log: u10c 2026-11-11, a Realme dropped a ~0.3 s burst at call start). A summary stage carries the same
    `atMs` on the same clock; its `time` is the summary line's, so it is marked `source=summary`."""
    seen = {(item["stage"], item["role"], item["call_id"]) for item in events}
    added = []
    for row in rows:
        match = SUMMARY_LINE.search(row["message"])
        if not match:
            continue
        role, start, call_id, stages = match.groups()
        for stage, at, detail in SUMMARY_STAGE.findall(stages):
            if (stage, role, call_id) in seen:
                continue
            seen.add((stage, role, call_id))
            pairs = dict(item.split("=", 1) for item in detail.split(";") if "=" in item) if detail else {}
            added.append({"stage": stage, "role": role, "since_ms": int(at) - int(start), "at_ms": int(at),
                          "call_id": call_id, "detail": pairs, "time": row["time"], "log_at": row["at"],
                          "source": "summary"})
    if not added:
        return events
    return sorted(events + added, key=lambda item: item["at_ms"])


def shown_frames(rows):
    """The most frames the phone's call receiver reported shown (`continuity: role=call_rx`), 0 when none."""
    counts = [int(match.group(1)) for match in (SHOWN_FRAMES.search(row["message"]) for row in rows) if match]
    return max(counts, default=0)


def _for_call(events, call_id):
    """The events that belong to the measured call: its callId, or lines that carry none."""
    if call_id is None:
        ids = {item["call_id"] for item in events if item["call_id"]}
        if len(ids) > 1:
            return None
        return events
    return [item for item in events if item["call_id"] in (None, call_id)]


def _first(events, stage, after_ms=None):
    return next((item for item in events if item["stage"] == stage and (after_ms is None or item["at_ms"] >= after_ms)),
                None)


def caller_stage(events, call_id, rows=None):
    """(record, None) for dial -> ringing on the caller's clock, else (None, (kind, reason)).

    The record also carries the diagnostics (never the join): the tap (`tap_to_invite_ms`, `tap_to_ringing_ms`, `tap_source`)
    and the stage-1 sub-stage rows (`stage1_substages`; `rows` are the caller's scoped log rows, for the MTU and status-133
    lines)."""
    chosen = _for_call(events, call_id)
    if chosen is None:
        return None, ("UNMEASURED", "The caller's stage lines name more than one call and no call identity picks one")
    dial = _first([item for item in chosen if item["role"] == "caller"], "dial")
    if dial is None:
        return None, ("UNMEASURED", "The caller logged no dial stage for this call")
    ring = _first([item for item in chosen if item["role"] == "caller"], "ringing", dial["at_ms"])
    if ring is None:
        return None, ("FAILED_JOIN", "The caller never logged ringing after its dial: the call did not ring")
    record = {"dial_at_ms": dial["at_ms"], "ringing_at_ms": ring["at_ms"], "dial_to_ringing_ms": ring["at_ms"] - dial["at_ms"],
              "dial_time": dial["time"], "ringing_time": ring["time"]}
    record.update(tap_diagnostics(chosen, dial, ring))
    record["stage1_substages"] = stage1_substages(chosen, dial, ring, rows)
    return record, None


def tap_diagnostics(events, dial, ring):
    """tap_to_invite_ms (tap -> the dial stage, the invite going out) and tap_to_ringing_ms on the caller's clock. The tap
    stage's `sinceStartMs` is negative; only its `atMs` is used. None when the build logged no tap for this dial (a tap
    older than the app's 15 s window, or a build before JT2)."""
    tap = next((item for item in reversed(events) if item["stage"] == "tap" and item["role"] == "caller"
                and item["at_ms"] <= dial["at_ms"]), None)
    if tap is None:
        return {"tap_to_invite_ms": None, "tap_to_ringing_ms": None, "tap_source": None}
    return {"tap_to_invite_ms": dial["at_ms"] - tap["at_ms"], "tap_to_ringing_ms": ring["at_ms"] - tap["at_ms"],
            "tap_source": tap["detail"].get("source")}


def stage1_substages(events, dial, ring, rows=None):
    """Dial -> ringing split at the app's own stages, on the caller's clock (a missing stage leaves its rows None).

    connect = dial -> link_up (the transport connect), auth = link_up -> authenticated (MTU exchange and the
    authenticated handshake), ring = authenticated -> ringing. `mtu` is the first `GATT video MTU` line after the dial
    and `mtu_after_link_up_ms` its log time after the link_up line (the same phone's log clock); `status_133` counts the
    `ll_gate ... status=133` lines (else the GATT setup `(status 133)` finish lines) between the dial and ringing."""
    caller = [item for item in events if item["role"] == "caller"]
    link = _first(caller, "link_up", dial["at_ms"])
    auth = _first(caller, "authenticated", link["at_ms"] if link else dial["at_ms"])
    result = {"dial_to_link_up_ms": link["at_ms"] - dial["at_ms"] if link else None,
              "link_up_to_authenticated_ms": auth["at_ms"] - link["at_ms"] if link and auth else None,
              "authenticated_to_ringing_ms": ring["at_ms"] - auth["at_ms"] if auth else None,
              "link": link["detail"].get("link") if link else None,
              "mtu": None, "mtu_after_link_up_ms": None, "status_133": None}
    if rows is None:
        return result
    window = [row for row in rows if dial["log_at"] <= row["at"] <= ring["log_at"]]
    mtu = next(((row, MTU_LINE.search(row["message"])) for row in window if MTU_LINE.search(row["message"])), None)
    if mtu:
        result["mtu"] = int(mtu[1][1])
        if link:
            result["mtu_after_link_up_ms"] = round((mtu[0]["at"] - link["log_at"]) * 1000)
    gate_lines = [row for row in window if "ll_gate:" in row["message"] and STATUS_133.search(row["message"])]
    finish_lines = [row for row in window if "stage=finish" in row["message"] and "(status 133)" in row["message"]]
    result["status_133"] = len(gate_lines) or len(finish_lines)
    return result


def sender_camera(events):
    """The sender's camera sub-stages for the reading of a slow or dark start: each stage's `sinceStartMs` on the sender's
    own clock and its details (the first capture's sensorLagMs, aeState, exposureUs and iso among them)."""
    found = {}
    for item in events:
        if item["stage"] in CAMERA_STAGES and item["stage"] not in found:
            found[item["stage"]] = {"since_start_ms": item["since_ms"], "at_ms": item["at_ms"], **item["detail"]}
    return found or None


def picture_stage(events, call_id):
    """(record, None) for Accept -> first non-black shown picture on this phone's clock, else (None, (kind, reason))."""
    chosen = _for_call(events, call_id)
    if chosen is None:
        return None, ("UNMEASURED", "This phone's stage lines name more than one call and no call identity picks one")
    accept = _first(chosen, "accept")
    if accept is None:
        return None, ("FAILED_JOIN", "This phone logged no accept stage: the call was never answered here")
    shown = [item for item in chosen if item["stage"] == "first_frame_shown" and item["at_ms"] >= accept["at_ms"]]
    if not shown:
        return None, ("FAILED_JOIN", "This phone logged no shown picture after the accept stage")
    # B6 and later also log `first_nonblack_shown` (the app keeps sampling a black first picture, bounded at 15 s after
    # Accept): `reached=true` names the first real picture; `reached=false` means none appeared within the bound.
    nonblack = [item for item in chosen if item["stage"] == "first_nonblack_shown" and item["at_ms"] >= accept["at_ms"]]
    candidates = sorted(shown + [item for item in nonblack if item["detail"].get("reached", "true") == "true"],
                        key=lambda item: item["at_ms"])
    real = next((item for item in candidates if item["detail"].get("black") == "false"), None)
    first = shown[0]
    if real is None:
        black = first["detail"].get("black")
        lumas = [item["detail"].get("meanLuma") for item in shown if item["detail"].get("meanLuma")]
        seen = f" (mean luma {', '.join(lumas)})" if lumas else ""
        if any(item["detail"].get("reached") == "false" for item in nonblack):
            return None, ("UNMEASURED", f"{SCENE_DARK}: no non-black picture appeared within the app's bound after the "
                                        f"accept stage{seen}: a dark scene (or the picture never arrived), so a real "
                                        "picture is not proven and no join value is reported")
        if black == "true":
            return None, ("UNMEASURED", f"{SCENE_DARK}: every shown picture was black{seen} and the app logs no later "
                                        "non-black evidence: a dark scene, so no join value is reported")
        why = "the app could not read the first shown picture (black=unknown), so a real picture is not proven"
        return None, ("UNMEASURED", why[0].upper() + why[1:])
    if first is not real and first["detail"].get("black") == "true" and real["at_ms"] - accept["at_ms"] >= DARK_BOUND_MS:
        # Black for the app's whole dark bound, then a real picture: the scene was lit late, the app was not slow.
        return None, ("UNMEASURED", f"{SCENE_DARK}: the shown picture stayed black for the app's {DARK_BOUND_MS / 1000:g} s "
                                    f"bound (the first real picture came {real['at_ms'] - accept['at_ms']} ms after Accept): "
                                    "a dark scene lit late, so no join value is reported")
    return {"accept_at_ms": accept["at_ms"], "picture_at_ms": real["at_ms"],
            "accept_to_picture_ms": real["at_ms"] - accept["at_ms"], "accept_by": accept["detail"].get("by"),
            "accept_time": accept["time"], "picture_time": real["time"],
            "first_picture_black": first is not real, "mean_luma": real["detail"].get("meanLuma")}, None


def direction_record(sender, receiver, caller_events, receiver_events, call_id, dropped=None, caller_rows=None,
                     sender_events=None, receiver_shown=0):
    """One receiving direction's two stages and their sum. `dropped` names a logd-drop reason for a missing line;
    `receiver_shown` is the frames the receiving phone reported shown: a picture that reached its screen means the call
    joined, so a missing stage line there is a lost line (UNMEASURED), never a failed join.

    `caller_rows` (the caller's scoped log rows) feed the stage-1 MTU and status-133 rows; `sender_events` the sender's
    camera sub-stages. Both only add diagnostics."""
    record = {"sender": sender, "receiver": receiver, "status": "UNMEASURED", "join_ms": None}
    if not caller_events and not receiver_events:
        return {**record, "reason": "The build logged no call_join_stage lines (it predates them)"}
    first, problem = caller_stage(caller_events, call_id, caller_rows)
    camera = sender_camera(sender_events) if sender_events else None
    record = {**record, **({"sender_camera": camera} if camera else {})}
    second, trouble = (None, None)
    if problem is None:
        second, trouble = (picture_stage(receiver_events, call_id) if receiver_events else
                           (None, ("UNMEASURED", "The receiving phone logged no call_join_stage lines")))
    problem = problem or trouble
    if problem:
        kind, reason = problem
        if kind == "FAILED_JOIN" and dropped:
            kind, reason = "UNMEASURED", f"{dropped} ({reason})"
        elif kind == "FAILED_JOIN" and receiver_shown > 0:
            kind, reason = "UNMEASURED", (f"The receiving phone showed {receiver_shown} frames, so the call joined, but a "
                                          f"stage line is missing from its log (lost line): {reason}")
        return {**record, "status": kind, "reason": reason,
                **({"reason_code": SCENE_DARK} if reason.startswith(SCENE_DARK) else {}),
                **({k: first[k] for k in ("tap_to_invite_ms", "tap_to_ringing_ms", "tap_source", "stage1_substages")}
                   if first else {})}
    return {**record, "status": "MEASURED", **{f"caller_{key}": value for key, value in first.items()},
            **{key: first[key] for key in ("tap_to_invite_ms", "tap_to_ringing_ms", "tap_source", "stage1_substages")},
            "stage_dial_to_ringing_ms": first["dial_to_ringing_ms"],
            "stage_accept_to_picture_ms": second["accept_to_picture_ms"],
            "receiver_stage": second, "join_ms": first["dial_to_ringing_ms"] + second["accept_to_picture_ms"]}


def call_record(directions, idle=None, user_launch=False, call_id=None):
    """The call's join: the slower direction, only when both directions are measured; failures name the call."""
    kinds = [item["status"] for item in directions]
    if not directions:
        status, reason = "UNMEASURED", "No receiving direction"
    elif "FAILED_JOIN" in kinds:
        status = "FAILED_JOIN"
        reason = next(item["reason"] for item in directions if item["status"] == "FAILED_JOIN")
    elif "UNMEASURED" in kinds:
        status = "UNMEASURED"
        reason = next(item["reason"] for item in directions if item["status"] == "UNMEASURED")
    else:
        status, reason = "MEASURED", None
    join = max((item["join_ms"] for item in directions), default=None) if status == "MEASURED" else None
    dark = status == "UNMEASURED" and any(item.get("reason_code") == SCENE_DARK for item in directions)
    if dark:  # a dark scene names the call even when another direction is unmeasured for another reason
        reason = next(item["reason"] for item in directions if item.get("reason_code") == SCENE_DARK)
    # The caller's own diagnostics (the same caller for both directions): tap -> invite, tap -> ringing, stage-1 rows.
    own = next((item for item in directions if "tap_to_invite_ms" in item), {})
    return {"schema": "release-join-call-v1", "status": status, "join_ms": join, **({"reason": reason} if reason else {}),
            **({"reason_code": SCENE_DARK} if dark else {}),
            "tap_to_invite_ms": own.get("tap_to_invite_ms"), "tap_to_ringing_ms": own.get("tap_to_ringing_ms"),
            "stage1_substages": own.get("stage1_substages"),
            "diagnostics_basis": "tap_to_invite_ms and tap_to_ringing_ms (caller clock, from the tap stage) and stage1_substages "
                                 "are diagnostics: the join and the gate do not use them",
            "call_id": call_id, "user_launch": bool(user_launch), "directions": directions,
            "idle_callee": idle_record(idle),
            "target_ms": TARGET_MS, "interim_line_ms": INTERIM_LINE_MS, "ceiling_ms": CEILING_MS,
            "basis": "dial->ringing on the caller's clock + Accept->first non-black picture on the receiving phone's "
                     "clock; the call's value is the slower receiving direction"}


def idle_record(idle):
    """The runner's `callee_idle` manifest record with whether it reached past the app's fast window."""
    if not isinstance(idle, dict):
        return {"requested_s": None, "actual_s": None, "idle": False,
                "reason": "The case did not ask for an idle callee (no callee_idle_s)"}
    actual = idle.get("actual_s")
    qualifies = isinstance(actual, (int, float)) and not isinstance(actual, bool) and actual >= IDLE_QUALIFIES_S
    return {"requested_s": idle.get("requested_s"), "actual_s": actual, "idle": bool(qualifies),
            "qualifies_at_s": IDLE_QUALIFIES_S, "app_fast_window_s": APP_FAST_WINDOW_S,
            **({"reason": f"The callee was idle {actual} s before the dial; the app's fast window ends after "
                          f"{APP_FAST_WINDOW_S:g} s and the balanced step by {IDLE_QUALIFIES_S:g} s"}
               if not qualifies else {})}


def diagnostic_gate(record, direction, gate, as_diagnostic):
    """The per-call, per-receiving-phone row for the score's gates: shown with its value, never deciding (the suite
    gate decides). `gate` and `as_diagnostic` are score_device_run's."""
    value = direction.get("join_ms") if direction else None
    row = gate(value, CEILING_MS, reason=(direction or {}).get("reason") or "Required measurement absent")
    if direction and direction["status"] == "FAILED_JOIN":
        row = {"status": "FAIL", "value": None, "limit": CEILING_MS, "comparison": "max", "reason": direction["reason"]}
    return {**as_diagnostic(row), "interim_line_ms": INTERIM_LINE_MS, "target_ms": TARGET_MS,
            **({"within_interim_line": value <= INTERIM_LINE_MS,
                "stage_dial_to_ringing_ms": direction["stage_dial_to_ringing_ms"],
                "stage_accept_to_picture_ms": direction["stage_accept_to_picture_ms"]} if value is not None else {}),
            **({"reason_code": direction["reason_code"]} if direction and direction.get("reason_code") else {}),
            **({key: direction[key] for key in ("tap_to_invite_ms", "tap_to_ringing_ms", "stage1_substages", "sender_camera")
                if direction.get(key) is not None} if direction else {}),
            "basis": "Release join metric: decided at suite level (release_join_gate), not per call"}


def percentile(values, share):
    """Nearest-rank percentile of non-empty values (share in 0-100)."""
    ordered = sorted(values)
    return ordered[max(0, math.ceil(share / 100 * len(ordered)) - 1)]


def suite_gate(results, minimum=MINIMUM_CALLS, require_idle=True):
    """The release gate over the suite's call results: each result carries its `release_join` call record.

    Counted: user-launch calls dialed from an idle callee whose join is MEASURED or FAILED_JOIN (a failed join is a
    call that did not connect, over the ceiling). Listed but not counted: UNMEASURED calls, calls from a callee that
    was not idle, benchmark-launch calls (control arms). PASS: at least 90% within 6 s and none over 15 s. FAIL: less,
    or any call over 15 s or never joined (a measured outlier decides even before the minimum). UNMEASURED: otherwise
    when fewer than `minimum` calls are counted."""
    counted, listed = [], {"unmeasured": [], "not_idle": [], "not_user_launch": []}
    for result in results:
        record = result.get("release_join")
        if not isinstance(record, dict):
            continue
        name = {"case_id": result.get("case_id"), "run_id": result.get("run_id")}
        if not record.get("user_launch"):
            listed["not_user_launch"].append(name)
        elif require_idle and not (record.get("idle_callee") or {}).get("idle"):
            listed["not_idle"].append({**name, "idle_s": (record.get("idle_callee") or {}).get("actual_s")})
        elif record["status"] == "UNMEASURED":
            listed["unmeasured"].append({**name, "reason": record.get("reason"),
                                       **({"reason_code": record["reason_code"]} if record.get("reason_code") else {})})
        elif record["status"] in ("MEASURED", "FAILED_JOIN"):
            counted.append({**name, "join_ms": record.get("join_ms"), "status": record["status"],
                            **({"reason": record["reason"]} if record.get("reason") else {})})
    never = [item for item in counted if item["join_ms"] is None]
    joined = [item["join_ms"] for item in counted if item["join_ms"] is not None]
    within = sum(value <= INTERIM_LINE_MS for value in joined)
    over_line = [item for item in counted if item["join_ms"] is None or item["join_ms"] > INTERIM_LINE_MS]
    over_ceiling = [item for item in counted if item["join_ms"] is None or item["join_ms"] > CEILING_MS]
    total = len(counted)
    share_ok = total > 0 and within * 100 >= INTERIM_SHARE_PERCENT * total
    if over_ceiling:
        state, why = "FAIL", f"{len(over_ceiling)} call(s) took over {CEILING_MS / 1000:g} s or never joined"
    elif total < minimum:
        state, why = "UNMEASURED", (f"{total} counted call(s), fewer than the minimum of {minimum}. {MINIMUM_REASON}")
    elif not share_ok:
        state, why = "FAIL", (f"{within} of {total} calls within {INTERIM_LINE_MS / 1000:g} s "
                              f"({within * 100 / total:.1f}%), under {INTERIM_SHARE_PERCENT}%")
    else:
        state, why = "PASS", (f"{within} of {total} calls within {INTERIM_LINE_MS / 1000:g} s "
                              f"({within * 100 / total:.1f}%), none over {CEILING_MS / 1000:g} s")
    return {"schema": "release-join-gate-v1", "status": state, "reason": why,
            "rule": {"share_percent": INTERIM_SHARE_PERCENT, "within_ms": INTERIM_LINE_MS, "ceiling_ms": CEILING_MS,
                     "minimum_calls": minimum, "target_ms": TARGET_MS, "require_idle_callee": bool(require_idle)},
            "calls_counted": total, "calls_within_interim_line": within,
            "share_within_interim_line_percent": round(within * 100 / total, 2) if total else None,
            "p90_ms": percentile(joined, 90) if joined else None, "max_ms": max(joined) if joined else None,
            "median_ms": percentile(joined, 50) if joined else None,
            "calls_within_target": sum(value <= TARGET_MS for value in joined),
            "calls_never_joined": never,
            "calls_over_interim_line": over_line, "calls_over_ceiling": over_ceiling,
            "not_counted": {key: value for key, value in listed.items() if value},
            "scope": "App-clock stages of user-launch calls from an idle callee; physical screen latency is separate"}
