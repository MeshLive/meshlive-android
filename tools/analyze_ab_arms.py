"""Compare the bounded-media-age arm with the no-loss arm from finalized benchmark runs.

Offline only: reads saved run directories (NNNN-*/physical/manifest.json, score.json and the app
logs) and never touches a phone. Cases are paired by their exact request minus the tunable
`bounded_media_age` (true = bounded arm, false = no-loss arm). Each directory given on the command
line is one repeat; cases with no physical score, blocked cases, quarantined phones (unconfirmed
cleanup) and arm/log-policy mismatches are counted as setup failures and kept out of the metrics.

Usage: python tools/analyze_ab_arms.py RUN_DIR [RUN_DIR ...] --out OUTPUT_DIR
"""
import argparse
import copy
import hashlib
import json
import re
import statistics
import sys
from pathlib import Path

sys.dont_write_bytecode = True

SCHEMA = "benchmark-ab-arms-v1"
ARM_NAMES = {True: "bounded", False: "noloss"}
POLICY_FOR_ARM = {"bounded": "bounded-experimental", "noloss": "noloss"}
# Keys that identify a run or build, not the request; removed (with the tunable) before pairing.
NON_REQUEST_KEYS = ("id", "state", "eligibility", "environment", "expected_apk_sha256")

LOG_LINE = re.compile(r"BENCHMARK (media_age_policy|media_age_shed|media_age_event|media_age|continuity|"
                      r"call rx|call tx|call pipeline): ?(.*)$")
RATE_STEP = re.compile(r"(?:Call rate adaptation|Rate adaptation: Link limited|Link limited): sending (\d+) of (\d+) kbps")
KEYFRAME_REQUEST = re.compile(r"Requested a (?:native libaom )?recovery keyframe")
AUDIO_PARTIAL_WRITE = re.compile(r"Audio drop reasons: partial write (\d+)")

# metric -> better direction ("lower", "higher") or None when it is descriptive only.
METRICS = {
    # sender side, per direction (what the app logged about the queue before the link)
    "tx_video_queue_age_win_p50_ms": "lower",
    "tx_video_queue_age_win_p95_ms": "lower",
    "tx_video_queue_age_win_max_ms": "lower",
    "tx_audio_queue_age_win_p95_ms": "lower",
    "tx_audio_queue_age_win_max_ms": "lower",
    "tx_video_queue_logged_p50_ms": "lower",
    "tx_video_queue_logged_p95_ms": "lower",
    "tx_video_queue_logged_max_ms": "lower",
    "tx_audio_queue_logged_p95_ms": "lower",
    "tx_audio_queue_logged_max_ms": "lower",
    "tx_peer_added_delay_max_ms": "lower",
    "tx_queue_drops": None,
    "tx_shed_events": None,
    "tx_shed_frames": None,
    "tx_shed_lines": None,
    "tx_thinned_frames": None,
    "tx_pause_events": None,
    "tx_paused_ms": None,
    "tx_critical_overflow_drops": "lower",
    "tx_keyframe_requests": None,
    "tx_rate_cuts": None,
    "tx_rate_min_fraction": None,
    "tx_link_limited_lines": None,
    "tx_rate_evidence_lines": None,
    # receiver side, per direction
    "rx_playout_delay_max_ms": "lower",
    "rx_held_age_win_p50_ms": "lower",
    "rx_held_age_win_p95_ms": "lower",
    "rx_held_age_win_max_ms": "lower",
    "rx_late_max_ms": "lower",
    "rx_added_delay_max_ms": "lower",
    "rx_longest_stall_ms": "lower",
    "rx_frozen_pct": "lower",
    "rx_frozen_ms": "lower",
    "rx_freeze_events": "lower",
    "rx_rendered_frames": "higher",
    "rx_visible_fps": "higher",
    "rx_score_freeze_events": "lower",
    "rx_score_freeze_ms": "lower",
    "rx_audio_drops": "lower",
    "rx_decoder_drops": "lower",
}
# One short column set for the readable table; the JSON carries everything.
TABLE_METRICS = (
    "rx_held_age_win_p95_ms", "tx_video_queue_age_win_p95_ms", "rx_longest_stall_ms", "rx_frozen_pct",
    "rx_visible_fps", "rx_freeze_events", "rx_audio_drops", "tx_shed_events", "tx_pause_events",
    "tx_rate_cuts", "tx_keyframe_requests",
)


# ---------------------------------------------------------------- log parsing

def parse_fields(text):
    """`k=v | k=v` body to a dict; numeric values become numbers, others stay strings."""
    fields = {}
    for part in text.split(" | "):
        key, sep, value = part.partition("=")
        if not sep:
            continue
        value = value.strip()
        try:
            fields[key.strip()] = int(value)
        except ValueError:
            try:
                fields[key.strip()] = float(value)
            except ValueError:
                fields[key.strip()] = value
    return fields


def read_log(path):
    """Structured BENCHMARK rows plus the plain-text counters one phone log provides."""
    rows = {name: [] for name in ("media_age_policy", "media_age_shed", "media_age_event", "media_age",
                                  "continuity", "call rx", "call tx", "call pipeline")}
    rate_steps, keyframes, audio_partial, rate_evidence, link_limited = [], 0, [], 0, 0
    for line in Path(path).read_text(encoding="utf-8", errors="replace").splitlines():
        if "BENCHMARK" in line:
            match = LOG_LINE.search(line)
            if match:
                rows[match.group(1)].append(parse_fields(match.group(2)))
                continue
        if "Rate evidence:" in line:
            rate_evidence += 1
        step = RATE_STEP.search(line)
        if step:
            rate_steps.append((int(step.group(1)), int(step.group(2))))
            if "Link limited" in line:
                link_limited += 1
        if KEYFRAME_REQUEST.search(line):
            keyframes += 1
        partial = AUDIO_PARTIAL_WRITE.search(line)
        if partial:
            audio_partial.append(int(partial.group(1)))
    return {"rows": rows, "rate_steps": rate_steps, "keyframe_requests": keyframes,
            "audio_partial_writes": audio_partial, "rate_evidence_lines": rate_evidence,
            "link_limited_lines": link_limited}


def percentile(values, fraction):
    """Linear-interpolated percentile of a non-empty list."""
    ordered = sorted(values)
    if len(ordered) == 1:
        return float(ordered[0])
    position = (len(ordered) - 1) * fraction
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    return float(ordered[low] + (ordered[high] - ordered[low]) * (position - low))


def window_stats(rows, field):
    """p50/p95/max of the per-window maxima the app logged for `field` (None when absent)."""
    values = [row[field] for row in rows if isinstance(row.get(field), (int, float))]
    if not values:
        return None, None, None
    return percentile(values, 0.5), percentile(values, 0.95), float(max(values))


def maximum(rows, field):
    values = [row[field] for row in rows if isinstance(row.get(field), (int, float))]
    return max(values) if values else None


def last_value(rows, field):
    for row in reversed(rows):
        if isinstance(row.get(field), (int, float)):
            return row[field]
    return None


def tx_metrics(log, relevant_peers=None):
    """What the sending phone logged about its own outgoing queue and degradation."""
    roles = ("call_tx", "stream_tx", "relay_tx")
    ages = [row for row in log["rows"]["media_age"] if row.get("role") in roles]
    if relevant_peers:
        filtered = [row for row in ages if row.get("role") == "call_tx" or row.get("peer") in relevant_peers]
        ages = filtered or ages
    metrics = {name: None for name in METRICS if name.startswith("tx_")}
    metrics["tx_video_queue_age_win_p50_ms"], metrics["tx_video_queue_age_win_p95_ms"], \
        metrics["tx_video_queue_age_win_max_ms"] = window_stats(ages, "videoQueueAgeMaxMs")
    _, metrics["tx_audio_queue_age_win_p95_ms"], metrics["tx_audio_queue_age_win_max_ms"] = \
        window_stats(ages, "audioQueueAgeMaxMs")
    if ages:
        metrics["tx_peer_added_delay_max_ms"] = max(
            [row.get("peerAddedDelayMs", row.get("viewerAddedDelayMs", 0)) for row in ages if isinstance(
                row.get("peerAddedDelayMs", row.get("viewerAddedDelayMs", 0)), (int, float))] or [0])
        metrics["tx_shed_events"] = maximum(ages, "shedEvents")
        metrics["tx_shed_frames"] = maximum(ages, "shedFrames")
        metrics["tx_thinned_frames"] = maximum(ages, "thinned")
        metrics["tx_pause_events"] = maximum(ages, "pauseEvents")
        metrics["tx_paused_ms"] = maximum(ages, "pausedMs")
        metrics["tx_critical_overflow_drops"] = maximum(ages, "criticalOverflowDrops")
    pipeline = [row for row in log["rows"]["call pipeline"]]
    for stage, name in (("video_queue", "video"), ("audio_queue", "audio")):
        stage_rows = [row for row in pipeline if row.get("stage") == stage and row.get("samples", 0) > 0]
        final = [row for row in stage_rows if str(row.get("final")) == "true"] or stage_rows[-1:]
        if final:
            if name == "video":
                metrics["tx_video_queue_logged_p50_ms"] = final[-1].get("p50Ms")
            metrics[f"tx_{name}_queue_logged_p95_ms"] = final[-1].get("p95Ms")
            metrics[f"tx_{name}_queue_logged_max_ms"] = final[-1].get("maxMs")
    metrics["tx_queue_drops"] = maximum(log["rows"]["call tx"], "queueDrops")
    metrics["tx_shed_lines"] = sum(1 for row in log["rows"]["media_age_shed"] if row.get("role") in roles)
    event_pauses = [row for row in log["rows"]["media_age_event"] if row.get("event") == "video_paused"]
    if metrics["tx_pause_events"] is None and (event_pauses or ages):
        metrics["tx_pause_events"] = len(event_pauses)
    metrics["tx_keyframe_requests"] = log["keyframe_requests"]
    cuts, previous, lowest = 0, None, None
    for sending, requested in log["rate_steps"]:
        if previous is None:
            previous = requested
        if sending < previous:
            cuts += 1
        previous = sending
        lowest = min(sending / requested, lowest if lowest is not None else 1.0)
    metrics["tx_rate_cuts"] = cuts
    metrics["tx_rate_min_fraction"] = lowest
    metrics["tx_link_limited_lines"] = log["link_limited_lines"]
    metrics["tx_rate_evidence_lines"] = log["rate_evidence_lines"]
    return metrics


def rx_metrics(log, score_side, role_names):
    """What the receiving phone logged and scored about incoming media."""
    ages = [row for row in log["rows"]["media_age"] if row.get("role") in role_names]
    continuity = [row for row in log["rows"]["continuity"] if row.get("role") in role_names]
    metrics = {name: None for name in METRICS if name.startswith("rx_")}
    metrics["rx_playout_delay_max_ms"] = maximum(ages, "playoutDelayMs")
    metrics["rx_held_age_win_p50_ms"], metrics["rx_held_age_win_p95_ms"], metrics["rx_held_age_win_max_ms"] = \
        window_stats(ages, "heldAgeMaxMs")
    metrics["rx_late_max_ms"] = maximum(ages, "lateMaxMs")
    metrics["rx_added_delay_max_ms"] = maximum(ages, "addedDelayMs")
    if continuity:
        final = [row for row in continuity if str(row.get("final")) == "true"] or continuity[-1:]
        last = final[-1]
        metrics["rx_longest_stall_ms"] = maximum(continuity, "longestStallMs")
        metrics["rx_frozen_pct"] = last.get("frozenPct")
        metrics["rx_frozen_ms"] = last.get("frozenMs")
        metrics["rx_freeze_events"] = last.get("freezeEvents")
        metrics["rx_rendered_frames"] = last.get("frames")
    call_rx = [row for row in log["rows"]["call rx"] if str(row.get("final")) == "true"]
    if call_rx:
        for field, name in (("audioDrops", "rx_audio_drops"), ("decoderDrops", "rx_decoder_drops")):
            if isinstance(call_rx[-1].get(field), (int, float)):
                metrics[name] = call_rx[-1][field]
    elif log["audio_partial_writes"]:
        metrics["rx_audio_drops"] = max(log["audio_partial_writes"])
    if score_side:
        fps = (score_side.get("gates") or {}).get("visible_fps", {}).get("value")
        if fps is None:
            intervals = [row["visible_fps"] for row in score_side.get("visible_fps_intervals", [])
                         if isinstance(row.get("visible_fps"), (int, float))]
            fps = statistics.fmean(intervals) if intervals else None
        metrics["rx_visible_fps"] = fps
        freezes = score_side.get("freezes") or {}
        metrics["rx_score_freeze_events"] = freezes.get("observed")
        metrics["rx_score_freeze_ms"] = (score_side.get("freeze_duration_ms") or {}).get("observed")
    return metrics


# ------------------------------------------------------------- case discovery

def read_json(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def request_key(scenario):
    """Exact request minus identity bookkeeping and the tunable under test."""
    request = copy.deepcopy(scenario)
    for key in NON_REQUEST_KEYS:
        request.pop(key, None)
    tunables = request.get("tunables")
    if isinstance(tunables, dict):
        tunables.pop("bounded_media_age", None)
        if not tunables:
            request.pop("tunables")
    return hashlib.sha256(json.dumps(request, sort_keys=True).encode("utf-8")).hexdigest()[:16]


def arm_of(scenario):
    value = (scenario.get("tunables") or {}).get("bounded_media_age")
    return ARM_NAMES.get(value) if isinstance(value, bool) else None


def pair_label(scenario):
    """Short readable name of the request without the arm."""
    transport = scenario.get("transport", "?")
    if "caller" in scenario:
        profiles = scenario.get("profiles") or {}
        rates = sorted({"app" if profile.get("profile_source") == "app_default" else str(profile.get("bitrate_kbps"))
                        for profile in profiles.values()})
        return f"call {scenario['caller']}->{scenario['callee']} {transport} {'/'.join(rates)}k"
    receivers = ",".join(f"{r.get('parent')}>{r.get('phone')}" for r in scenario.get("receivers", []))
    kbps = (scenario.get("profile") or {}).get("bitrate_kbps")
    return f"stream {scenario.get('source')} [{receivers}] {transport} {kbps}k"


def slug(text):
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def case_record(run_dir, case_dir):
    """Everything one case folder offers; failures stay explicit instead of being dropped."""
    physical = case_dir / "physical"
    attempt = read_json(case_dir / "attempt.json") or {}
    manifest = read_json(physical / "manifest.json") or {}
    scenario = manifest.get("scenario") or attempt.get("scenario")
    record = {"run": run_dir.name, "case": case_dir.name, "case_id": (scenario or {}).get("id"),
              "scenario": scenario}
    if not scenario:
        return {**record, "classification": "no_scenario", "reason": "no scenario in manifest.json or attempt.json (case still running or not started)"}
    record["arm"] = arm_of(scenario)
    record["key"] = request_key(scenario)
    cleanup = (read_json(case_dir / "cleanup.json") or {}).get("status") or \
        (manifest.get("phone_cleanup") or {}).get("status") or attempt.get("cleanup_status")
    record["cleanup_status"] = cleanup
    record["attempt_status"] = attempt.get("status")
    if record["arm"] is None:
        return {**record, "classification": "no_arm", "reason": "scenario has no boolean bounded_media_age tunable"}
    if cleanup == "UNCONFIRMED":
        return {**record, "classification": "quarantined",
                "reason": attempt.get("reason") or "phone cleanup unconfirmed; case excluded"}
    score = read_json(physical / "score.json")
    if score is None:
        return {**record, "classification": "setup_failure",
                "reason": attempt.get("reason") or f"no physical score ({attempt.get('status') or 'no attempt record'})"}
    record["score_status"] = score.get("overall_status")
    record["apk_sha256"] = sorted({d.get("apk_sha256") for d in (manifest.get("devices") or {}).values()
                                   if isinstance(d, dict) and d.get("apk_sha256")})
    logs = {}
    for device in score.get("devices", []):
        name = Path(str(device.get("path", "")).replace("\\", "/")).name
        if name and (physical / name).is_file():
            logs[device["device"]] = read_log(physical / name)
    if not logs:
        return {**record, "classification": "setup_failure", "reason": "score lists no readable phone log"}
    policies = {row.get("policy") for log in logs.values() for row in log["rows"]["media_age_policy"]}
    expected = POLICY_FOR_ARM[record["arm"]]
    if policies != {expected}:
        return {**record, "classification": "arm_mismatch",
                "reason": f"logs report policy {sorted(map(str, policies))}, scenario requested {expected}"}
    directions = build_directions(scenario, score, manifest, logs)
    if not any(m.get("rx_rendered_frames") for m in directions.values()):
        return {**record, "classification": "setup_failure", "reason": "no rendered media in any direction"}
    pcm = sorted(str(p.relative_to(case_dir)) for p in case_dir.rglob("*") if p.is_file() and
                 ("pcm" in p.name.lower() or "audio-track" in p.name.lower()))
    return {**record, "classification": "measured", "directions": directions, "pcm_observation_files": pcm}


def build_directions(scenario, score, manifest, logs):
    """Per direction (sender->receiver) metrics: sender log for the queue, receiver log for playout."""
    score_devices = {device["device"]: device for device in score.get("devices", [])}
    prefixes = {name: info.get("node_prefix") for name, info in (manifest.get("devices") or {}).items()
                if isinstance(info, dict)}
    directions = {}
    if "caller" in scenario:
        phones = [scenario["caller"], scenario["callee"]]
        for sender, receiver in (phones, phones[::-1]):
            if sender not in logs or receiver not in logs:
                continue
            side = (score_devices.get(receiver) or {}).get("incoming")
            metrics = tx_metrics(logs[sender])
            metrics.update(rx_metrics(logs[receiver], side, ("call_rx",)))
            directions[f"{sender}->{receiver}"] = metrics
        return directions
    for receiver in scenario.get("receivers", []):
        phone, parent = receiver.get("phone"), receiver.get("parent") or scenario.get("source")
        if phone not in logs or parent not in logs:
            continue
        side = score_devices.get(phone)
        peers = {prefixes.get(phone)} - {None}
        metrics = tx_metrics(logs[parent], peers)
        metrics.update(rx_metrics(logs[phone], side, ("stream_rx",)))
        directions[f"{parent}->{phone}"] = metrics
    return directions


def discover_cases(run_dirs):
    cases = []
    for run_dir in run_dirs:
        run_dir = Path(run_dir)
        for case_dir in sorted(p for p in run_dir.iterdir() if p.is_dir() and re.match(r"\d{4}-", p.name)):
            cases.append(case_record(run_dir, case_dir))
    return cases


# ------------------------------------------------------------------ statistics

def arm_stats(values):
    values = [v for v in values if isinstance(v, (int, float))]
    if not values:
        return {"n": 0, "values": [], "mean": None, "min": None, "max": None, "spread": None}
    return {"n": len(values), "values": values, "mean": statistics.fmean(values), "min": min(values),
            "max": max(values), "spread": max(values) - min(values)}


def compare(metric, bounded, noloss):
    """One metric of one pair/direction; same-arm repeat spread is the noise floor."""
    better = METRICS[metric]
    b, n = arm_stats(bounded), arm_stats(noloss)
    result = {"bounded": b, "noloss": n, "delta": None, "verdict": "no data", "confidence": None}
    if not b["n"] or not n["n"]:
        return result
    result["delta"] = b["mean"] - n["mean"]
    if better is None:
        result["verdict"] = "descriptive"
        return result
    if b["n"] < 2 or n["n"] < 2:
        direction = "equal" if result["delta"] == 0 else (
            "bounded better" if (result["delta"] < 0) == (better == "lower") else "bounded worse")
        result["verdict"] = f"single run ({direction}); not conclusive"
        result["confidence"] = "single-run"
        return result
    noise = max(b["spread"], n["spread"])
    disjoint = b["max"] < n["min"] or b["min"] > n["max"]
    result["noise"] = noise
    if not disjoint or abs(result["delta"]) <= noise:
        result["verdict"] = "within noise"
    else:
        result["verdict"] = "bounded better" if (result["delta"] < 0) == (better == "lower") else "bounded worse"
    result["confidence"] = "consistent" if min(b["n"], n["n"]) >= 3 else "suggestive"
    return result


def group_pairs(cases):
    pairs = {}
    for case in cases:
        if "key" not in case or case.get("arm") is None:
            continue
        pair = pairs.setdefault(case["key"], {"key": case["key"], "label": pair_label(case["scenario"]),
                                              "scenario": case["scenario"], "samples": {"bounded": [], "noloss": []},
                                              "excluded": []})
        if case["classification"] == "measured":
            pair["samples"][case["arm"]].append(case)
        else:
            pair["excluded"].append({k: case.get(k) for k in ("run", "case", "case_id", "arm", "classification",
                                                               "reason", "cleanup_status", "attempt_status")})
    return list(pairs.values())


def summarize_pair(pair):
    samples = pair["samples"]
    directions = sorted({d for arm in samples.values() for case in arm for d in case["directions"]})
    runs = {arm: sorted({case["run"] for case in samples[arm]}) for arm in samples}
    apks = {arm: sorted({a for case in samples[arm] for a in case.get("apk_sha256", [])}) for arm in samples}
    out = {"label": pair["label"], "key": pair["key"], "request": {
        k: v for k, v in pair["scenario"].items() if k not in NON_REQUEST_KEYS},
        "repeats": {arm: len(samples[arm]) for arm in samples}, "runs": runs, "apk_sha256": apks,
        "excluded_cases": pair["excluded"], "directions": {}}
    out["caveats"] = []
    if len(set(sum(apks.values(), []))) > 1:
        out["caveats"].append("arms or repeats ran on different APK builds")
    if not samples["bounded"] or not samples["noloss"]:
        out["caveats"].append("incomplete pair: " + ("bounded" if not samples["bounded"] else "no-loss") +
                              " arm has no measured case")
    for direction in directions:
        per_metric = {}
        for metric in METRICS:
            series = {arm: [case["directions"].get(direction, {}).get(metric) for case in samples[arm]]
                      for arm in samples}
            if any(isinstance(v, (int, float)) for arm in series.values() for v in arm):
                per_metric[metric] = compare(metric, series["bounded"], series["noloss"])
        out["directions"][direction] = per_metric
    pcm = sorted({f for arm in samples.values() for case in arm for f in case.get("pcm_observation_files", [])})
    out["received_pcm_continuity"] = {"status": "UNMEASURED" if not pcm else "files present, not parsed",
                                      "files": pcm}
    return out


def overall_verdicts(pair_summaries):
    """Per metric: counts across pair-directions and an honest label with the repeat count."""
    verdicts = {}
    for metric, better in METRICS.items():
        tally = {"bounded better": 0, "bounded worse": 0, "within noise": 0, "single run": 0, "descriptive": 0}
        single = {"bounded lower": 0, "bounded higher": 0, "equal": 0}
        items, repeats = 0, []
        for pair in pair_summaries:
            for direction in pair["directions"].values():
                entry = direction.get(metric)
                if not entry or entry["verdict"] == "no data":
                    continue
                items += 1
                repeats.append(min(entry["bounded"]["n"], entry["noloss"]["n"]))
                verdict = entry["verdict"]
                if verdict.startswith("single run"):
                    tally["single run"] += 1
                    single["equal" if entry["delta"] == 0 else
                           ("bounded lower" if entry["delta"] < 0 else "bounded higher")] += 1
                else:
                    tally[verdict] = tally.get(verdict, 0) + 1
        if not items:
            continue
        repeat_count = max(repeats)
        if better is None:
            label = f"descriptive only ({items} pair-directions, repeats up to {repeat_count})"
        elif repeat_count < 2:
            label = f"NOT CONCLUSIVE: single repeat (n=1); bounded lower in {single['bounded lower']}, " \
                    f"higher in {single['bounded higher']}, equal in {single['equal']} of {items}"
        else:
            decided = tally["bounded better"] + tally["bounded worse"]
            label = (f"bounded better in {tally['bounded better']}, worse in {tally['bounded worse']}, "
                     f"within noise in {tally['within noise']}, single-run in {tally['single run']} of {items} "
                     f"(max repeats {repeat_count}"
                     f"{'; suggestive only' if repeat_count < 3 else ''})")
            if not decided:
                label = "no difference beyond same-arm spread; " + label
        verdicts[metric] = {"better_direction": better, "items": items, "max_repeats": repeat_count,
                            "tally": tally, "single_run_signs": single, "verdict": label}
    return verdicts


# --------------------------------------------------------------------- output

def fmt(value):
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:.1f}" if abs(value) >= 10 else f"{value:.2f}"
    return str(value)


def cell(entry):
    if not entry:
        return "-"
    b, n = entry["bounded"]["mean"], entry["noloss"]["mean"]
    return f"{fmt(b)} / {fmt(n)}"


def render_markdown(report):
    lines = [f"# Bounded-age vs no-loss arms ({report['schema']})", "",
             "Inputs: " + ", ".join(report["inputs"]), "",
             f"Cases: {report['case_counts']['total']} found, {report['case_counts']['measured']} measured, "
             f"{report['case_counts']['excluded']} excluded as setup failures "
             f"({', '.join(f'{k} {v}' for k, v in sorted(report['setup_failures']['by_class'].items())) or 'none'}).",
             "", "Each cell is bounded / no-loss (mean over repeats; per-window ages are the maxima the app logged). "
             "A single repeat is never conclusive.", "",
             "## Per pair and direction", "",
             "| pair | direction | reps b/n | " + " | ".join(m for m in TABLE_METRICS) + " |",
             "| --- | --- | --- | " + " | ".join("---" for _ in TABLE_METRICS) + " |"]
    for pair in report["pairs"]:
        reps = f"{pair['repeats']['bounded']}/{pair['repeats']['noloss']}"
        if not pair["directions"]:
            lines.append(f"| {pair['label']} | (no measured direction) | {reps} |" + " - |" * len(TABLE_METRICS))
        for direction, metrics in pair["directions"].items():
            lines.append(f"| {pair['label']} | {direction} | {reps} | " +
                         " | ".join(cell(metrics.get(m)) for m in TABLE_METRICS) + " |")
    lines += ["", "## Overall verdict per metric", "", "| metric | better | verdict |", "| --- | --- | --- |"]
    for metric, verdict in report["metric_verdicts"].items():
        lines.append(f"| {metric} | {verdict['better_direction'] or 'info'} | {verdict['verdict']} |")
    lines += ["", "## Setup failures and exclusions", ""]
    failures = report["setup_failures"]["cases"]
    if failures:
        lines += ["| run | case | arm | class | reason |", "| --- | --- | --- | --- | --- |"]
        for item in failures:
            lines.append(f"| {item['run']} | {item['case']} | {item.get('arm') or '-'} | "
                         f"{item['classification']} | {str(item.get('reason') or '').replace('|', '/')} |")
    else:
        lines.append("None.")
    lines += ["", "## Caveats", ""]
    lines += [f"- {c}" for c in report["caveats"]] or ["- None."]
    return "\n".join(lines) + "\n"


def analyze(run_dirs):
    cases = discover_cases(run_dirs)
    pair_summaries = [summarize_pair(pair) for pair in group_pairs(cases)]
    pair_summaries.sort(key=lambda pair: pair["label"])
    excluded = [{k: case.get(k) for k in ("run", "case", "case_id", "arm", "classification", "reason",
                                          "cleanup_status", "attempt_status")}
                for case in cases if case["classification"] != "measured"]
    by_class = {}
    for item in excluded:
        by_class[item["classification"]] = by_class.get(item["classification"], 0) + 1
    caveats = []
    for pair in pair_summaries:
        for caveat in pair["caveats"]:
            caveats.append(f"{pair['label']}: {caveat}")
    if by_class:
        caveats.append("excluded cases are listed under setup failures; quarantined cases ran with an "
                       "unconfirmed phone cleanup and are not evidence")
    pcm_unmeasured = all(pair["received_pcm_continuity"]["status"] == "UNMEASURED" for pair in pair_summaries)
    if pcm_unmeasured:
        caveats.append("received PCM continuity is UNMEASURED: no received-audio observation exists in these runs; "
                       "only the app's audio drop counters are reported")
    return {"schema": SCHEMA, "inputs": [str(Path(p)) for p in run_dirs],
            "case_counts": {"total": len(cases), "measured": len(cases) - len(excluded), "excluded": len(excluded)},
            "setup_failures": {"count": len(excluded), "by_class": by_class, "cases": excluded},
            "pairs": pair_summaries, "metric_verdicts": overall_verdicts(pair_summaries), "caveats": caveats}


def write_outputs(report, out_dir):
    out_dir = Path(out_dir)
    (out_dir / "pairs").mkdir(parents=True, exist_ok=True)
    for pair in report["pairs"]:
        (out_dir / "pairs" / f"{slug(pair['label'])}-{pair['key'][:8]}.json").write_text(
            json.dumps(pair, indent=2), encoding="utf-8")
    (out_dir / "ab-arms-summary.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    (out_dir / "ab-arms-summary.md").write_text(render_markdown(report), encoding="utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("runs", nargs="+", help="run directories; each is one repeat")
    parser.add_argument("--out", required=True, help="output directory (use the OS temp directory)")
    args = parser.parse_args(argv)
    missing = [r for r in args.runs if not Path(r).is_dir()]
    if missing:
        parser.error(f"not a directory: {missing}")
    report = analyze(args.runs)
    write_outputs(report, args.out)
    print(render_markdown(report))
    return 0


if __name__ == "__main__":
    sys.exit(main())
