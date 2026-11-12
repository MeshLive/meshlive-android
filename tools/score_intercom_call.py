"""Voice-call and push-to-talk requirements and scoring for the private-call benchmark (experimental, default off).

A case may carry call_type "voice" or "video" (default "video"; absent means the existing behaviour and
leaves every existing case ID unchanged) and the call actions ptt_press / ptt_release. Both need their
experimental app switches, supplied through the case tunables voice_calls, push_to_talk and ptt_mode
(debug extras test_voice_calls, test_push_to_talk, test_ptt_mode on both endpoints).

Voice scoring measures no video gate. It proves from the app logs that no video was sent or received
(video packet counters, no camera/encoder/surface lines, voice layout) and scores audio continuity from
the cumulative RX audio packet counters, excusing expected silence (mute, push-to-talk released, PTT mode
before the first press). Everything the logs cannot show stays UNMEASURED: sub-report audio gaps and
acoustic quality, per-packet press-to-audio arrival and a push-to-talk latency target (open product decision).
"""
import re

from score_device_run import aggregate_status, as_diagnostic, gate, timestamp

CALL_TYPES = ("voice", "video")
PTT_ACTIONS = ("ptt_press", "ptt_release")
VIDEO_ONLY_FAMILIES = ("picture", "smoothness")  # Not applicable to a voice call: no video is sent, decoded or shown
VOICE_FORBIDDEN_ACTIONS = {"video_on", "video_off", "flip", "background", "foreground"}
# Gates a voice call keeps from the generic call scorer. Every other generic gate is video, profile or encoder based.
VOICE_KEPT_GATES = ("unexpected_events", "audio_drops", "sender_drops", "call_tx_counter_regressions")
VIDEO_PIPELINE_LINES = (r"Call camera: Streaming", r"Encoder output: ", r"Private call visible continuity",
                        r"BENCHMARK call first_surface:", r"Private call startup: media-to-first-surface")
RX_REPORT_SECONDS = 2  # PrivateCallMedia.METRICS_INTERVAL_NS: audio arrival is only counted in these reports
MIN_ATTRIBUTABLE_HOLD_S = 1.0


def call_type(case):
    return case.get("call_type", "video")


def is_voice(case):
    return call_type(case) == "voice"


def fields(row_message, prefix):
    """key=value pairs of one `BENCHMARK <prefix>: a=1 | b=2` line, or None."""
    match = re.match(rf"BENCHMARK {re.escape(prefix)}: (.*)", row_message)
    if not match:
        return None
    return {key: value.strip() for key, value in re.findall(r"(\w+)=([^|]+?)(?=\s*\||$)", match[1])}


def benchmark_rows(rows, prefix, call_id=None):
    result = []
    for row in rows:
        values = fields(row["message"], prefix)
        if values is not None and (call_id is None or values.get("callId") in (None, call_id)):
            result.append({"time": row["time"], "at": row["at"], **values})
    return result


def number(value):
    return int(value) if isinstance(value, str) and re.fullmatch(r"\d+", value) else None


# -- Request validation -------------------------------------------------------------------------------

def validate_intercom_case(case, participants):
    from run_device_benchmark import tunable_extras
    if "tunables" in case:
        if not isinstance(case["tunables"], dict):
            raise ValueError("Call tunables must be an object")
        tunable_extras(case["tunables"])  # Unknown names or wrong types never silently test the default.
    if "phone_tunables" in case:
        per_phone = case["phone_tunables"]
        if not isinstance(per_phone, dict) or not all(isinstance(value, dict) for value in per_phone.values()):
            raise ValueError("Per-phone call tunables must map an endpoint to an object")
        # An on-demand helper launches as an ordinary endpoint and takes its own tunables (for example one helper's
        # device-policy off-switch); the DEBUG static relay launch carries none, so naming it there would test nothing.
        helpers = case.get("relays", case.get("relay")) if case.get("relay_shape") == "on_demand" else None
        helpers = [helpers] if isinstance(helpers, str) else list(helpers or [])
        if not set(per_phone) <= set(participants) | set(helpers):
            raise ValueError("Per-phone call tunables must name call endpoints")
        for value in per_phone.values():
            tunable_extras(value)
    tunables = case.get("tunables", {})
    if case.get("call_type", "video") not in CALL_TYPES:
        raise ValueError('call_type must be "voice" or "video"')
    ptt_actions = [item for item in case.get("actions", []) if item.get("action") in PTT_ACTIONS]
    if is_voice(case):
        if tunables.get("voice_calls") is not True:
            raise ValueError("A voice call needs the voice_calls tunable true on both endpoints (test_voice_calls)")
        if case.get("setup_action", "accept") == "accept":
            for item in case.get("actions", []):
                if item.get("action") in VOICE_FORBIDDEN_ACTIONS:
                    raise ValueError("A voice call carries no video: video, camera-flip and surface lifecycle actions are not measurable")
    if not ptt_actions:
        return
    if tunables.get("push_to_talk") is not True and tunables.get("ptt_mode") is not True:
        raise ValueError("Push-to-talk actions need the push_to_talk or ptt_mode tunable true (test_push_to_talk / test_ptt_mode)")
    for phone in {item["phone"] for item in ptt_actions}:
        if any(item.get("phone") == phone and item.get("action") in {"mute", "unmute"} for item in case.get("actions", [])):
            raise ValueError("Mute actions and push-to-talk on one endpoint share the microphone; use one or the other")
        held = False
        previous_at = None
        for item in sorted((x for x in ptt_actions if x["phone"] == phone), key=lambda x: x["at_s"]):
            if previous_at == item["at_s"]:
                raise ValueError("Push-to-talk press and release need distinct times")
            if (item["action"] == "ptt_press") == held:
                raise ValueError("Push-to-talk presses and releases must alternate, starting with a press")
            held = item["action"] == "ptt_press"
            previous_at = item["at_s"]


# -- Expected talk and silence ------------------------------------------------------------------------

def expected_talk(case, phone):
    """True when this endpoint is expected to send audio at some point in the measured call."""
    if case.get("tunables", {}).get("ptt_mode") is True:
        return any(item.get("phone") == phone and item.get("action") == "ptt_press" for item in case.get("actions", []))
    return True


def silence_intervals(rows, ptt_mode):
    """[(start, end)] seconds in this log's clock during which the peer's authenticated mute state is muted.

    The peer's Microphone muted/unmuted signals arrive as `BENCHMARK call remote_mute muted=<bool>`. In PTT
    mode the peer starts muted, so silence runs from the start of the scope until the first unmute.
    """
    intervals, start = [], (float("-inf") if ptt_mode else None)
    for row in rows:
        match = re.match(r"BENCHMARK call remote_mute muted=(true|false)", row["message"])
        if not match:
            continue
        if match[1] == "true" and start is None:
            start = row["at"]
        elif match[1] == "false" and start is not None:
            intervals.append((start, row["at"]))
            start = None
    if start is not None:
        intervals.append((start, float("inf")))
    return intervals


def rx_reports(rows, call_id):
    """Cumulative RX audio/video packet reports for the call, periodic first then the final one."""
    reports = []
    for row in rows:
        values = fields(row["message"], "media_age")
        if values is None or values.get("role") != "call_rx" or (call_id and values.get("callId") != call_id):
            continue
        audio, video = number(values.get("audioPackets")), number(values.get("videoPackets"))
        if audio is None or video is None:
            continue
        reports.append({"time": row["time"], "at": row["at"], "audio": audio, "video": video,
                        "final": values.get("final") == "true"})
    return reports


# -- Voice gates --------------------------------------------------------------------------------------

def final_report(rows, kind, call_id):
    values = [item for item in benchmark_rows(rows, f"call {kind}", call_id)
              if call_id and item.get("callId") == call_id and item.get("final") == "true"]
    return values[-1] if values else None


def video_sent_gate(rows, call_id):
    tx = final_report(rows, "tx", call_id)
    counts = [number(tx.get(key)) for key in ("videoInputs", "videoWrites")] if tx else []
    if not tx or any(value is None for value in counts):
        return gate(None, 0, reason="A final TX report for the measured call is required to prove zero video packets sent")
    result = gate(sum(counts), 0)
    result.update(video_inputs=counts[0], video_writes=counts[1],
                  config_inputs=number(tx.get("configInputs")), config_writes=number(tx.get("configWrites")),
                  basis="Video packets (not codec config) queued and written by this endpoint. The config counters also count the Opus "
                        "audio config packet, so they are reported but cannot be attributed to video. A packet is the only carrier of video "
                        "payload bytes, so zero packets is zero video bytes; the app logs no per-kind byte counter.")
    return result


def video_received_gate(rows, call_id):
    reports = rx_reports(rows, call_id)
    final = final_report(rows, "rx", call_id)
    values = [item["video"] for item in reports]
    final_video = number(final.get("videoPackets")) if final else None
    if final_video is not None:
        values.append(final_video)
    if final_video is None or not values:
        return gate(None, 0, reason="A final RX report for the measured call is required to prove zero video packets received")
    result = gate(max(values), 0)
    result.update(report_samples=len(reports), final_video_packets=final_video,
                  basis="Largest cumulative video packet count in any RX report, including the final one; zero packets is zero video bytes")
    return result


def audio_sent_gate(case, phone, rows, call_id):
    tx = final_report(rows, "tx", call_id)
    writes = number(tx.get("audioWrites")) if tx else None
    if writes is None:
        return gate(None, 1, "min", reason="A final TX report for the measured call is required to prove audio was sent")
    if not expected_talk(case, phone):
        result = gate(writes, 0)
        result.update(basis="PTT mode starts muted and this endpoint never presses: it must send no audio")
        return result
    result = gate(writes, 1, "min")
    result.update(basis="Audio packets written; at least one is required from an endpoint expected to talk",
                  muted_audio_dropped=number(tx.get("mutedAudio")))
    return result


def audio_received_gate(case, peer, rows, call_id):
    reports, final = rx_reports(rows, call_id), final_report(rows, "rx", call_id)
    audio = number(final.get("audioPackets")) if final else None
    if audio is None:
        return gate(None, 1, "min", reason="A final RX report for the measured call is required to prove audio arrived")
    if not expected_talk(case, peer):
        result = gate(audio, 0)
        result.update(basis="The peer starts muted in PTT mode and never presses: no audio is expected")
        return result
    result = gate(audio, 1, "min")
    result.update(basis="Cumulative audio packets received (final RX report)", report_samples=len(reports))
    return result


def audio_stall_gate(case, peer, rows, call_id, planned_loss):
    """Whole RX report windows with no audio packet advance, outside expected silence.

    Resolution is one RX report (2 s). Shorter gaps and speech quality stay UNMEASURED (acoustic family).
    """
    reports = rx_reports(rows, call_id)
    if len(reports) < 2 or not call_id:
        return gate(None, 0, reason="At least two RX audio reports for one identified call are required")
    silence = silence_intervals(rows, case.get("tunables", {}).get("ptt_mode") is True)
    excuse = [*silence, *[(timestamp(item["start"]), timestamp(item["end"])) for item in planned_loss]]
    stalls, longest, run, excused = 0, 0.0, 0.0, 0
    for before, after in zip(reports, reports[1:]):
        if after["final"]:
            continue  # Includes the hang-up tail; the end of a call is not a stall
        window = after["at"] - before["at"]
        if after["audio"] > before["audio"]:
            run = 0.0
            continue
        if any(start <= after["at"] and end >= before["at"] for start, end in excuse):
            excused += 1
            run = 0.0
            continue
        stalls += 1
        run += window
        longest = max(longest, run)
    result = gate(stalls, 0)
    result.update(windows=len(reports) - 1, excused_silent_windows=excused,
                  longest_stalled_run_ms=longest * 1000, report_resolution_ms=RX_REPORT_SECONDS * 1000,
                  basis="Consecutive RX reports whose cumulative audio packet count did not advance, excluding windows that overlap the peer's "
                        "authenticated mute/PTT-released time or a planned peer loss. Gaps shorter than one report and acoustic quality are UNMEASURED.")
    return result


def local_call_type_gate(case, phone, rows, call_id):
    callee = phone == case["callee"]
    entries = [item for item in benchmark_rows(rows, "call_type", call_id)]
    if not entries:
        return gate(None, 0, reason="No BENCHMARK call_type line from the measured call")
    wrong = sum(item.get("local") != "voice" or
                (callee and item.get("peerOfferedVoice") != "true") or
                (not callee and item.get("requested") != "voice") for item in entries)
    offers = benchmark_rows(rows, "call_type offer", call_id)
    if callee:
        wrong += 0 if offers and offers[-1].get("offered") == "voice" else 1
    result = gate(wrong, 0)
    result.update(basis="Caller requested voice and runs voice; callee saw the voice offer, answered as voice and runs voice")
    return result


def voice_layout_gate(rows, call_id):
    entries = benchmark_rows(rows, "call_mode", call_id)
    if not entries:
        return gate(None, 0, reason="No BENCHMARK call_mode line from the measured call")
    wrong = sum(item.get("callType") != "voice" or item.get("localVideo") != "false" or
                item.get("remoteVideo") != "false" or item.get("layout") != "voice" for item in entries)
    return gate(wrong, 0)


def no_video_pipeline_gate(rows):
    # Every call logs a final visible-continuity line; only one reporting rendered frames is video.
    hits = [row for row in rows if any(re.search(pattern, row["message"]) for pattern in VIDEO_PIPELINE_LINES)
            and not re.search(r"Private call visible continuity.*?:\s*0 frames", row["message"])]
    result = gate(len(hits), 0)
    result.update(basis="Camera/encoder start, encoder output, surface or visible-continuity lines in this endpoint's scoped log",
                  examples=[row["message"][:120] for row in hits[:3]])
    return result


# Voice setup-to-active has its own threshold key. It is undecided (None): the value is measured and reported but
# never decides until a target is set through the thresholds argument. It must not borrow the video startup limit
# (max_startup_ms, 3000 ms), which was chosen for first-surface video and has no voice basis.
VOICE_SETUP_LIMIT_KEY = "max_voice_setup_to_active_ms"
VOICE_SETUP_BASIS = (
    "ACTIVE minus the runner's setup anchor. The anchor is read before the invite, so the value also contains the "
    "runner's invite/accept adb command latency, the GATT/CoC connect (about 1.2 s) and MTU exchange (about 0.7 s). "
    "voice-ptt-51cc8028 measured 1.96-2.94 s over 12 endpoint samples on one phone pair and transport; that is too narrow, "
    "and too host-command-inflated, to set a target. Undecided: reported, never decisive.")


def setup_to_active_gate(rows, setup, limit=None):
    active = next((row for row in rows if row["message"] == "BENCHMARK call phase ACTIVE"), None)
    if not active or not setup:
        return gate(None, limit, reason="Setup anchor and the call's ACTIVE phase are required")
    value = (active["at"] - timestamp(setup["time"])) * 1000
    if limit is None:
        return {**as_diagnostic({"status": "UNMEASURED", "value": value, "limit": None}), "basis": VOICE_SETUP_BASIS}
    return gate(value, limit)


# -- Discovery ----------------------------------------------------------------------------------------

def launch_order(case, manifest):
    """[first, second] endpoint by app launch: the order of the scoped logs, else the runner's callee-first order."""
    seen = []
    for spec in manifest.get("logs", []):
        if spec.get("device") in (case["caller"], case["callee"]) and spec["device"] not in seen:
            seen.append(spec["device"])
    return seen if len(seen) == 2 else [case["callee"], case["caller"]]


def first_row(rows, text):
    return next((row for row in rows if text in row["message"]), None)


def discovery_gate(case, manifest, phone, rows, peer_rows, node_prefix, limit, clocks=None):
    """Discovery time from this endpoint's own availability to the peer's first appearance in its scan.

    The runner launches the first endpoint and waits until it is available, then launches the second. The
    second endpoint therefore scans an already-advertising peer, and its own launch-to-discovery time is the
    discovery stack time: that value decides. The first endpoint can only discover the peer once the runner has
    launched it, so its value contains the runner's launch gap (seconds) and cannot be judged against the limit:
    it is DIAGNOSTIC. When clock brackets exist for both phones the discovery time from the later of the two
    availabilities is also reported (diagnostic, with the bracket uncertainty).
    """
    available = first_row(rows, "BENCHMARK call available:")
    # `node_prefix` is the peer's registry prefix, or that prefix plus every rotating ID the peer's own log announced
    # (a rotating peer is scanned under its discovery ID, never its fixed prefix).
    names = [node_prefix] if isinstance(node_prefix, str) else [item for item in (node_prefix or ()) if item]
    offer = next((row for row in rows if any(f"BENCHMARK call discovered: node={name}" in row["message"] for name in names)), None)
    value = (offer["at"] - available["at"]) * 1000 if available and offer else None
    if phone == launch_order(case, manifest)[1]:
        result = gate(value, limit)
        result["basis"] = ("Scan of an already-advertising peer: the runner launches this endpoint only after the peer "
                           "logged availability, so the value is not inflated by the launch gap")
        return result
    result = as_diagnostic(gate(value, limit))
    result["basis"] = ("Launched first: the runner launches the peer only after this endpoint is available, so this value "
                       "includes the runner's launch gap. The second-launched endpoint's value decides.")
    peer = case["callee"] if phone == case["caller"] else case["caller"]
    peer_available = first_row(peer_rows, "BENCHMARK call available:")
    own_map, peer_map = (clocks or {}).get(phone), (clocks or {}).get(peer)
    mapped = [own_map.host_time(available["at"]) if own_map and available else None,
              peer_map.host_time(peer_available["at"]) if peer_map and peer_available else None,
              own_map.host_time(offer["at"]) if own_map and offer else None]
    if all(mapped):
        ready = max(mapped[0][0], mapped[1][0])
        result.update(peer_ready_to_discovery_ms=(mapped[2][0] - ready) * 1000,
                      peer_ready_uncertainty_ms=(mapped[2][1] + max(mapped[0][1], mapped[1][1])) * 1000,
                      peer_ready_basis="Discovery measured from the later endpoint's availability, joined through adb clock brackets. Near zero or "
                                         "slightly negative means the scan found the peer as soon as it advertised: the peer starts "
                                         "advertising just before it logs available (voice-ptt-51cc8028: -76 to -136 ms)")
    else:
        result["peer_ready_to_discovery_ms"] = None
        result["peer_ready_reason"] = "Launch-gap-corrected discovery needs adb clock brackets for both phones (taken only for PTT cases)"
    return result


def prune_voice(result):
    """Remove every video/profile gate from a generic section; a voice call measures none of them."""
    result["gates"] = {name: item for name, item in result["gates"].items() if name in VOICE_KEPT_GATES}
    result["optical_gates"], result["optical_results"] = {}, []
    result["quality_status"] = "UNMEASURED"
    result["expected_fps"] = result["expected_width"] = result["expected_height"] = result["expected_bitrate_kbps"] = None
    result["offered_load"] = {"status": "UNMEASURED", "reason": "A voice call offers no video load"}
    result["capacity_status"] = aggregate_status(result["gates"].values())
    result["surface_segments"], result["visible_fps_intervals"], result["profiles"] = [], [], []
    result["voice"] = True
    return result


# -- Clock brackets and push-to-talk ------------------------------------------------------------------

class ClockMap:
    """Device logcat clock to the host clock, from adb `date` calls bracketed by host time.

    Each sample gives offset = device - host midpoint with an uncertainty of half its round trip. The
    best (smallest-RTT) sample before and after the call is linearly interpolated, so drift is bounded.
    Without both ends the offset is unusable: drift would be unbounded.
    """

    def __init__(self, brackets):
        groups = {}
        for item in brackets:
            before, after, device = (timestamp(item[key]) for key in ("host_before", "host_after", "device_time"))
            rtt = after - before
            if rtt < 0:
                continue
            groups.setdefault(item.get("label"), []).append({"device": device, "offset": device - (before + after) / 2, "rtt": rtt})
        self.points = [min(groups[label], key=lambda sample: sample["rtt"]) for label in ("start", "end") if groups.get(label)]
        self.complete = len(self.points) == 2 and self.points[0]["device"] < self.points[1]["device"]

    def host_time(self, device_seconds):
        if not self.complete:
            return None
        first, last = self.points
        fraction = min(1.0, max(0.0, (device_seconds - first["device"]) / (last["device"] - first["device"])))
        offset = first["offset"] + (last["offset"] - first["offset"]) * fraction
        return device_seconds - offset, max(first["rtt"], last["rtt"]) / 2


def clock_maps(manifest):
    grouped = {}
    for item in manifest.get("clock_brackets", []):
        grouped.setdefault(item["device"], []).append(item)
    return {device: ClockMap(items) for device, items in grouped.items()}


def ptt_event_evidence(event, case, scope_rows, clocks, call_ids, events):
    """Evidence gates for one ptt_press/ptt_release control event. Returns (evidence, record)."""
    phone, name = event["device"], event["action"]
    peer = case["callee"] if phone == case["caller"] else case["caller"]
    pressed = name == "ptt_press"
    logged = event.get("ptt")
    evidence = {"ptt_log": {"status": "PASS" if logged else "UNMEASURED"}}
    record = {"device": phone, "action": name, "peer": peer, "state_changed": bool(logged and logged.get("changed"))}
    if not logged or not logged.get("changed"):
        record["note"] = "No mute transition: no authenticated signal is expected" if logged else "No call_ptt line recorded"
        return evidence, record
    remote = event.get("remote_ack")
    evidence["remote_ack"] = {"status": "PASS" if remote else "UNMEASURED"}
    if not remote:
        return evidence, record
    sender_mapped = clocks.get(phone) and clocks[phone].host_time(timestamp(logged["time"]))
    receiver_mapped = clocks.get(peer) and clocks[peer].host_time(timestamp(remote["time"]))
    key = "press_to_remote_unmute_ms" if pressed else "release_to_remote_mute_ms"
    if not sender_mapped or not receiver_mapped:
        evidence[key] = {"status": "UNMEASURED", "value": None,
                         "reason": "Cross-phone latency needs adb clock brackets before and after the call (clock_brackets in the manifest)"}
    else:
        value = (receiver_mapped[0] - sender_mapped[0]) * 1000
        uncertainty = (sender_mapped[1] + receiver_mapped[1]) * 1000
        if value + uncertainty < 0:
            evidence[key] = {"status": "UNMEASURED", "value": value, "uncertainty_ms": uncertainty,
                             "reason": "Negative latency beyond the clock uncertainty; the brackets are inconsistent"}
        else:
            evidence[key] = {**as_diagnostic({"status": "UNMEASURED", "value": value, "limit": None}),
                             "uncertainty_ms": uncertainty,
                             "reason": "Measured authenticated signal latency; no acceptance target is decided (PLAN QB3), so it is reported, never decisive"}
        record[key] = evidence[key]
    if pressed:
        arrival = audio_arrival(event, case, peer, remote, scope_rows.get(peer, []), call_ids.get(peer), events)
        evidence["audio_arrival_observed"] = arrival["gate"]
        record["audio_arrival"] = {key: value for key, value in arrival.items() if key != "gate"}
        report_ms, base = arrival.get("unmute_to_audio_report_ms"), evidence.get(key, {})
        if report_ms is not None and base.get("status") == "DIAGNOSTIC":
            evidence["press_to_audio_report_upper_bound_ms"] = {
                **as_diagnostic({"status": "UNMEASURED", "value": base["value"] + report_ms, "limit": None}),
                "uncertainty_ms": base.get("uncertainty_ms"),
                "reason": "Upper bound: authenticated unmute signal latency plus the wait for the first RX report that shows new audio "
                          "(reports are 2 s apart). Exact per-packet press-to-audio arrival is UNMEASURED without the media-trace audio adapter."}
        else:
            evidence["press_to_audio_report_upper_bound_ms"] = {
                "status": "UNMEASURED", "value": None,
                "reason": "Needs both the clock-bracketed signal latency and an RX report that shows new audio"}
    return evidence, record


def audio_arrival(event, case, peer, remote, peer_rows, call_id, events):
    """Did audio reach the peer after an accepted press? Cumulative RX counters, 2 s report resolution."""
    reports = rx_reports(peer_rows, call_id) if call_id else []
    unmuted_at = timestamp(remote["time"])
    release = next((item for item in events if item["device"] == event["device"] and item["action"] == "ptt_release" and
                    item.get("remote_ack") and timestamp(item["remote_ack"]["time"]) > unmuted_at), None)
    released_at = timestamp(release["remote_ack"]["time"]) if release else None
    result = {"hold_s": (released_at - unmuted_at) if released_at else None}
    if not reports:
        result["gate"] = {"status": "UNMEASURED", "reason": "No RX audio reports for one identified call"}
        return result
    before = [item for item in reports if item["at"] <= unmuted_at]
    if before:
        baseline = before[-1]["audio"]
    elif case.get("tunables", {}).get("ptt_mode") is True and not any(
            item["device"] == event["device"] and item["action"] == "ptt_press" and item is not event and
            item.get("remote_ack") and timestamp(item["remote_ack"]["time"]) < unmuted_at for item in events):
        baseline = 0  # PTT mode starts muted; no audio can have arrived before the first press
    else:
        result["gate"] = {"status": "UNMEASURED", "reason": "No RX report before the unmute to baseline the audio counter"}
        return result
    horizon = max(unmuted_at, released_at or unmuted_at)
    after = [item for item in reports if item["at"] >= horizon]
    advanced = next((item for item in reports if item["at"] > unmuted_at and item["audio"] > baseline), None)
    if advanced:
        result["unmute_to_audio_report_ms"] = (advanced["at"] - unmuted_at) * 1000
        result["gate"] = {"status": "PASS", "value": advanced["audio"] - baseline, "limit": 1, "comparison": "min",
                          "basis": "Audio packets arrived after the authenticated unmute (RX counter advance)"}
    elif released_at is not None and released_at - unmuted_at < MIN_ATTRIBUTABLE_HOLD_S:
        result["gate"] = {"status": "UNMEASURED", "reason": "Hold shorter than 1 s; report cadence cannot attribute audio to it"}
    elif after:
        result["gate"] = {"status": "FAIL", "value": 0, "limit": 1, "comparison": "min",
                          "reason": "An accepted press and authenticated unmute produced no new RX audio by the next report"}
    else:
        result["gate"] = {"status": "UNMEASURED", "reason": "No RX report after the press to observe audio"}
    return result


def ptt_summary(records):
    return {"events": records,
            "exact_press_to_audio_arrival": "UNMEASURED: audio arrival has no per-packet log line",
            "latency_target": "Undecided (PLAN QB3); latencies are diagnostic",
            "clock_basis": "adb date brackets around the call; uncertainty is half the best round trip of each phone"}
