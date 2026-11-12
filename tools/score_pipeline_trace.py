"""Score hash-bound local stage traces, explicit lifecycle and measured overlap.

Input benchmark-pipeline-observation-v1 declares traces, each with path/sha256,
format(logcat/json/csv), run_id/phone/session/pid/clock/source/direction and
stream_generation. Optional capture_start_ns/capture_end_ns bound trace capture.
Plans declare the same identity, start_ns/end_ns/fps, optional bitrate_kbps,
stages and expected_pts_us. Repeated PTS are never guessed across generations.
Optional lifecycle_events, active_media_intervals, resources and clock_anchors
are independent hashed documents. Optional media_age plans {sender, receiver, from_stage, to_stage}
join two phones' exact source PTS through measured clock_anchors only; unanchored phones stay UNMEASURED. Acceptance limits are explicit engineering
targets. No stage timestamp or interval proves physical pixels or audible output.
"""

import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import itertools
import json
import math
from pathlib import Path

from benchmark_layout import original, output_path, resolve as resolve_input
from stage_trace_lines import stage_records

PREFIX = "BENCHMARK stage v1 "  # the v1 marker; v2 batch lines are read too (stage_trace_lines)

IDENTITY = ("run_id", "phone", "session", "pid", "clock", "source", "direction", "stream_generation")
CLOCK = ("run_id", "phone", "pid", "clock")
STAGES = {"raw_capture", "encode_input", "encode_output", "send_enqueue", "write_begin", "write_end",
          "receive", "decoder_input", "decoder_output", "release", "surface_ack"}
AUXILIARY_STAGES = {"texture_notification"}
SPANS = (("raw_capture", "encode_input"), ("encode_input", "encode_output"), ("encode_output", "send_enqueue"),
         ("send_enqueue", "write_begin"), ("write_begin", "write_end"), ("receive", "decoder_input"),
         ("decoder_input", "decoder_output"), ("decoder_output", "release"), ("release", "surface_ack"))
LIMITATIONS = [
    "Local spans are measured only for exact source/direction/generation/PTS identities; duplicate stages remain ambiguous.",
    "Trace sequence orders logged records; rendered timestamps can predate their callbacks or arrive out of order.",
    "Stage events, release and surface acknowledgements are pipeline proxies, not physical pixels or optical latency.",
    "Trace gaps or unfinished scopes do not prove lost media; incomplete capture cannot pass full-interval gates.",
    "Payload excludes framing/security/container overhead; configured bitrate is not measured offered load.",
    "Actual overlap needs explicit observed active intervals; first/last frames do not prove continuous activity.",
    "Resource coverage alone does not establish unplugged power efficiency, thermal safety or handheld endurance.",
]


def integer(value, field, minimum=None):
    if type(value) is not int or minimum is not None and value < minimum:
        raise ValueError(f"{field} must be an integer" + (f" >= {minimum}" if minimum is not None else ""))
    return value


def number(value, field, minimum=None):
    if type(value) not in (int, float) or not math.isfinite(value) or minimum is not None and value < minimum:
        raise ValueError(f"{field} must be a finite number" + (f" >= {minimum}" if minimum is not None else ""))
    return float(value)


def key(row, fields=IDENTITY):
    return tuple(row.get(field) for field in fields)


def identity(row):
    for field in IDENTITY:
        if field in {"pid", "stream_generation"}:
            integer(row.get(field), field, 1 if field == "pid" else 0)
        elif not isinstance(row.get(field), str) or not row[field]:
            raise ValueError(f"Missing nonempty {field}")


def evidence(entry, base):
    if not isinstance(entry, dict) or not isinstance(entry.get("path"), str):
        raise ValueError("Evidence needs path and sha256")
    digest = entry.get("sha256", "")
    if not isinstance(digest, str) or len(digest) != 64 or any(c not in "0123456789abcdefABCDEF" for c in digest):
        raise ValueError("Evidence sha256 must be 64 hexadecimal characters")
    path = resolve_input(entry["path"], base=base)
    with path.open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if actual != digest.lower():
        raise ValueError(f"Evidence hash mismatch: {path}")
    return path, {"path": str(path), "sha256": actual}


def document(entry, base, schema, retained, name):
    path, retained[name] = evidence(entry, base)
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict) or value.get("schema") != schema:
        raise ValueError(f"Expected {schema}")
    return value


def distribution(values):
    values = sorted(values)
    def percentile(fraction):
        if not values:
            return None
        position = (len(values) - 1) * fraction
        lo, hi = math.floor(position), math.ceil(position)
        return values[lo] + (values[hi] - values[lo]) * (position - lo)
    return {"samples": len(values), "min": values[0] if values else None, "p50": percentile(.5),
            "p95": percentile(.95), "p99": percentile(.99), "max": values[-1] if values else None}


def ranges(values, end):
    result, last = [], 0
    for value in sorted(set(values)):
        if value > last + 1:
            result.append({"start": last + 1, "end": value - 1})
        last = value
    if last < end:
        result.append({"start": last + 1, "end": end})
    return result


def load_traces(spec, base, retained):
    events, scopes, envelopes, filtered = [], defaultdict(list), [], 0
    event_seen, scope_seen, declarations = set(), defaultdict(set), []
    for index, declaration in enumerate(spec.get("traces", [])):
        identity(declaration)
        path, retained[f"trace_{index}"] = evidence(declaration, base)
        selected_scopes = declaration.get("scopes")
        if selected_scopes is not None and (not isinstance(selected_scopes, list) or not selected_scopes
                                          or any(not isinstance(value, str) or not value for value in selected_scopes)):
            raise ValueError("Declared scopes must be a nonempty string list")
        for previous in declarations:
            common = ("run_id", "phone", "session", "pid", "clock", "source", "direction")
            if previous["path"] == str(path) and key(previous, common) == key(declaration, common) and previous["stream_generation"] != declaration["stream_generation"]:
                if not selected_scopes or not previous.get("scopes") or set(selected_scopes) & set(previous["scopes"]):
                    raise ValueError("Reusing one trace across generations requires explicit disjoint scope selections")
        declarations.append({**declaration, "path": str(path)})
        fmt = declaration.get("format")
        if fmt == "logcat":
            with path.open(encoding="utf-8-sig") as source:
                raw = [record for line in source for record in stage_records(line)]
        elif fmt == "json":
            doc = json.loads(path.read_text(encoding="utf-8-sig"))
            if doc.get("schema") != "benchmark-stage-events-v1":
                raise ValueError("Expected benchmark-stage-events-v1")
            raw = doc["events"]
        elif fmt == "csv":
            with path.open(encoding="utf-8-sig", newline="") as source:
                raw = list(csv.DictReader(source))
            for row in raw:
                for field in ("pid", "sequence", "pts_us", "at_ns", "bytes", "stream_generation"):
                    if field in row and row[field] != "":
                        row[field] = int(row[field])
                if row.get("final", "false").lower() not in {"true", "false"}:
                    raise ValueError("CSV final must be true or false")
                row["final"] = row.get("final", "false").lower() == "true"
        else:
            raise ValueError("Trace format must be logcat, json or csv")
        envelope = dict(zip(IDENTITY, key(declaration)))
        envelope.update({field: declaration.get(field) for field in ("capture_start_ns", "capture_end_ns")})
        for field in ("capture_start_ns", "capture_end_ns"):
            if envelope[field] is not None:
                integer(envelope[field], field, 0)
        envelopes.append(envelope)
        for raw_row in raw:
            if raw_row.get("session", declaration["session"]) != declaration["session"] or raw_row.get("pid", declaration["pid"]) != declaration["pid"]:
                filtered += 1
                continue
            if selected_scopes is not None and raw_row.get("scope") not in selected_scopes:
                filtered += 1
                continue
            if raw_row.get("stream_generation", declaration["stream_generation"]) != declaration["stream_generation"]:
                filtered += 1
                continue
            if raw_row.get("clock", declaration["clock"]) != declaration["clock"]:
                raise ValueError("Trace clock contradicts its declared phone/session/PID")
            row = {**dict(zip(IDENTITY, key(declaration))), **raw_row}
            for field in ("run_id", "phone", "source"):
                if row[field] != declaration[field]:
                    raise ValueError(f"Trace {field} contradicts declaration")
            identity(row)
            if not isinstance(row.get("scope"), str) or not row["scope"]:
                raise ValueError("Trace scope must be nonempty")
            integer(row.get("sequence"), "sequence", 1)
            integer(row.get("at_ns"), "at_ns", 0)
            integer(row.get("pts_us"), "pts_us", 0)
            integer(row.get("bytes"), "bytes", 0)
            if type(row.get("final", False)) is not bool or row.get("stage") not in STAGES | AUXILIARY_STAGES | {"final"}:
                raise ValueError("Unsupported trace stage or final value")
            if (row["stage"] == "final") != row.get("final", False):
                raise ValueError("Final sentinel must declare stage=final, final=true")
            scope_key = (*key(declaration, ("run_id", "phone", "session", "pid", "clock")), row["scope"])
            # A mixed phone log can be selected twice for opposite directions.
            canonical = {field: row.get(field, False if field == "final" else None)
                         for field in ("scope", "sequence", "stage", "pts_us", "at_ns", "bytes", "final", "pid", "clock", "session")}
            fingerprint = tuple(canonical.values())
            if fingerprint not in scope_seen[scope_key]:
                scopes[scope_key].append(canonical)
                scope_seen[scope_key].add(fingerprint)
            if not row.get("final", False) and row["direction"] == declaration["direction"]:
                fingerprint = (*key(row), row["scope"], row["sequence"], row["stage"], row["pts_us"], row["at_ns"], row["bytes"])
                if fingerprint not in event_seen:
                    events.append(row)
                    event_seen.add(fingerprint)
    summaries = []
    for scope_key, rows in sorted(scopes.items()):
        counts = Counter(row["sequence"] for row in rows)
        finals = [row for row in rows if row.get("final", False)]
        end = max(counts, default=0)
        gaps = ranges(counts, end)
        duplicate = [sequence for sequence, count in counts.items() if count > 1]
        issues = []
        if gaps:
            issues.append("missing_trace_sequences")
        if duplicate:
            issues.append("conflicting_sequence_records")
        backtracks = sum(b["sequence"] < a["sequence"] for a, b in zip(rows, rows[1:]))
        if backtracks:
            issues.append("sequence_order_backtracks")
        if len(finals) != 1:
            issues.append("missing_or_multiple_final_sentinels")
        elif finals[0]["sequence"] != end:
            issues.append("records_after_final_sentinel")
        summaries.append({**dict(zip(("run_id", "phone", "session", "pid", "clock", "scope"), scope_key)),
                          "status": "COMPLETE" if not issues else "INCOMPLETE", "issues": issues,
                          "missing_sequences": gaps, "conflicting_sequences": duplicate,
                          "sequence_order_backtracks": backtracks,
                          "records": len(rows), "final_at_ns": finals[0]["at_ns"] if len(finals) == 1 else None})
    return events, summaries, envelopes, filtered


def clock_mapper(spec, base, retained):
    anchors = {}
    if spec.get("clock_anchors") is not None:
        doc = document(spec["clock_anchors"], base, "benchmark-clock-anchors-v1", retained, "clock_anchors")
        for row in doc.get("anchors", []):
            domain = key(row, CLOCK)
            if domain in anchors or any(value is None for value in domain) or row.get("basis") != "measured_pairs":
                raise ValueError("Clock anchors require unique domains and measured_pairs basis")
            if not isinstance(row.get("common_clock"), str) or not row["common_clock"] or not row.get("method"):
                raise ValueError("Clock anchors require a common clock and measurement method")
            pairs = row.get("pairs", [])
            if len(pairs) < 2:
                raise ValueError("At least two measured clock pairs required")
            pairs = sorted(pairs, key=lambda x: integer(x.get("local_ns"), "local_ns", 0))
            for pair in pairs:
                integer(pair.get("common_ns"), "common_ns", 0)
                number(pair.get("uncertainty_ns"), "uncertainty_ns", 0)
            x0, y0 = pairs[0]["local_ns"], pairs[0]["common_ns"]
            xs = [pair["local_ns"] - x0 for pair in pairs]
            ys = [pair["common_ns"] - y0 for pair in pairs]
            average_x, average_y = sum(xs) / len(xs), sum(ys) / len(ys)
            denominator = sum((x - average_x) ** 2 for x in xs)
            if denominator == 0 or len(set(xs)) != len(xs):
                raise ValueError("Clock pair local times must be distinct")
            scale = sum((x - average_x) * (y - average_y) for x, y in zip(xs, ys)) / denominator
            if scale <= 0:
                raise ValueError("Measured clock map must preserve time order")
            intercept = average_y - scale * average_x
            error = max(abs(y - (scale * x + intercept)) + pair["uncertainty_ns"] for x, y, pair in zip(xs, ys, pairs))
            anchors[domain] = {"clock": row["common_clock"], "x0": x0, "y0": y0, "scale": scale,
                               "intercept": intercept, "error": error, "lo": pairs[0]["local_ns"], "hi": pairs[-1]["local_ns"]}
    def mapped(row, at):
        domain = key(row, CLOCK)
        anchor = anchors.get(domain)
        if anchor is None:
            return {"clock": domain, "at_ns": at, "uncertainty_ns": 0}
        if not anchor["lo"] <= at <= anchor["hi"]:
            return None  # No silent extrapolation beyond measured anchors.
        return {"clock": anchor["clock"], "at_ns": anchor["y0"] + anchor["scale"] * (at - anchor["x0"]) + anchor["intercept"],
                "uncertainty_ns": anchor["error"]}
    return mapped


def append_measurement(result, name, family, value, limit=None, complete=False, minimum=False, reason=None, **scope):
    status = "UNMEASURED"
    if limit is not None:
        limit = number(limit, name + " limit", 0)
    if value is not None and limit is not None and complete:
        status = "PASS" if (value >= limit if minimum else value <= limit) else "FAIL"
    result["measurements"].append({"name": name, "family": family, "status": status, "value": value, "limit": limit,
        "reason": reason or ("Compared with declared engineering target" if status != "UNMEASURED" else
                              "Measured diagnostic or incomplete evidence; full gate unavailable"), **scope})


def span_diagnostics(events):
    grouped = defaultdict(lambda: defaultdict(list))
    for row in events:
        if row["stage"] in STAGES:
            grouped[(*key(row), row["pts_us"])][row["stage"]].append(row)
    spans, ambiguous = [], []
    for identity_key, stages in sorted(grouped.items()):
        scope = dict(zip((*IDENTITY, "pts_us"), identity_key))
        for stage, rows in stages.items():
            if len(rows) > 1:
                ambiguous.append({**scope, "stage": stage, "records": len(rows), "reason": "Duplicate exact stage/PTS identity"})
        for before, after in SPANS:
            if before not in stages and after not in stages:
                continue
            rows_a, rows_b = stages.get(before, []), stages.get(after, [])
            delta = rows_b[0]["at_ns"] - rows_a[0]["at_ns"] if len(rows_a) == len(rows_b) == 1 else None
            spans.append({**scope, "from_stage": before, "to_stage": after, "duration_ns": delta,
                          "status": "MEASURED" if delta is not None and delta >= 0 else "UNMEASURED" if not rows_a or not rows_b else "AMBIGUOUS",
                          "reason": "Exact local identity" if delta is not None and delta >= 0 else
                                    "Missing exact begin/completion stage" if not rows_a or not rows_b else
                                    "Duplicate stage or signed negative event-time span"})
    return spans, ambiguous


def timing_plans(spec, events, scopes, envelopes, result):
    targets = spec.get("acceptance", {})
    windows = []
    for plan_index, plan in enumerate(spec.get("plans", [])):
        identity(plan)
        start, end = integer(plan.get("start_ns"), "start_ns", 0), integer(plan.get("end_ns"), "end_ns", 1)
        fps = number(plan.get("fps"), "fps", .000001)
        if end <= start:
            raise ValueError("Planned end must exceed start")
        expected = plan.get("expected_pts_us")
        if expected is not None and (not isinstance(expected, list) or len(set(expected)) != len(expected)
                                     or any(type(pts) is not int or pts < 0 for pts in expected)):
            raise ValueError("expected_pts_us must be unique explicit nonnegative source PTS")
        selected = [row for row in events if key(row) == key(plan)]
        bounds = [row for row in envelopes if key(row) == key(plan)]
        capture_covers = any(type(row.get("capture_start_ns")) is int and type(row.get("capture_end_ns")) is int
                             and row["capture_start_ns"] <= start and row["capture_end_ns"] >= end for row in bounds)
        stages = plan.get("stages", ["encode_output", "surface_ack"])
        if not isinstance(stages, list) or not stages or len(set(stages)) != len(stages) or set(stages) - STAGES:
            raise ValueError("Plan stages must be a unique supported nonempty list")
        for stage in stages:
            rows = [row for row in selected if row["stage"] == stage and start <= row["at_ns"] < end]
            duplicates = [pts for pts, count in Counter(row["pts_us"] for row in rows).items() if count > 1]
            unique = sorted((row for row in rows if row["pts_us"] not in duplicates), key=lambda row: row["at_ns"])
            used_scopes = {row["scope"] for row in rows}
            scope_rows = [row for row in scopes if key(row, ("run_id", "phone", "session", "pid", "clock")) == key(plan, ("run_id", "phone", "session", "pid", "clock")) and row["scope"] in used_scopes]
            finalized = bool(scope_rows) and all(row["status"] == "COMPLETE" and row["final_at_ns"] >= end for row in scope_rows)
            complete = capture_covers and finalized and not duplicates
            intervals = [b["at_ns"] - a["at_ns"] for a, b in zip(unique, unique[1:])]
            gaps = ([unique[0]["at_ns"] - start] + intervals + [end - unique[-1]["at_ns"]]) if unique else [end - start]
            measured_fps = len(unique) * 1e9 / (end - start)
            threshold = targets.get("freeze_threshold_ms")
            if threshold is not None:
                threshold = number(threshold, "freeze_threshold_ms", .000001)
            freezes = [gap / 1e6 for gap in gaps if threshold is not None and gap > threshold * 1e6]
            observed_pts = {row["pts_us"] for row in unique}
            missing = sorted(set(expected) - observed_pts) if expected is not None else None
            window = {"plan_index": plan_index, **dict(zip(IDENTITY, key(plan))), "stage": stage,
                      "start_ns": start, "end_ns": end, "status": "COMPLETE" if complete else "INCOMPLETE",
                      "capture_covers_plan": capture_covers, "scopes_finalized_through_end": finalized,
                      "observed_unique_frames": len(unique), "duplicate_pts_us": duplicates,
                      "expected_frames": len(expected) if expected is not None else None, "missing_pts_us": missing,
                      "unexpected_pts_us": sorted(observed_pts - set(expected)) if expected is not None else None,
                      "fps_over_full_plan": measured_fps, "interval_ns": distribution(intervals),
                      "interval_jitter_rms_ns": math.sqrt(sum((value - 1e9 / fps) ** 2 for value in intervals) / len(intervals)) if intervals else None,
                      "initial_gap_ns": gaps[0], "terminal_tail_ns": gaps[-1],
                      "freeze_threshold_ms": threshold, "freeze_gaps_ms": freezes if threshold is not None else None,
                      "event_time_reorders_in_sequence": sum(b["at_ns"] < a["at_ns"] for a, b in zip(rows, rows[1:]))}
            if stage == "encode_output":
                window["frame_payload_bytes"] = [{"pts_us": row["pts_us"], "at_ns": row["at_ns"], "bytes": row["bytes"]} for row in unique]
                window["payload_bins"] = {}
                for size in (100_000_000, 1_000_000_000):
                    bins = []
                    payload_by_bin = defaultdict(int)
                    for row in unique:
                        payload_by_bin[(row["at_ns"] - start) // size] += row["bytes"]
                    for bin_index, lo in enumerate(range(start, end, size)):
                        hi = min(end, lo + size)
                        payload = payload_by_bin[bin_index]
                        bins.append({"start_ns": lo, "end_ns": hi, "bytes": payload,
                                     "kbps": payload * 8e6 / (hi - lo), "partial": hi - lo != size})
                    window["payload_bins"][str(size)] = bins
                full = [row["kbps"] for row in window["payload_bins"]["1000000000"] if not row["partial"]]
                median = distribution(full)["p50"]
                rate = plan.get("bitrate_kbps")
                if rate is not None:
                    rate = number(rate, "bitrate_kbps", .000001)
                append_measurement(result, "encoded_payload_load_fraction", "transport", median / rate if median is not None and rate else None,
                                   targets.get("offered_load_fraction"), complete, minimum=True, plan_index=plan_index, stage=stage)
            windows.append(window)
            family = "smoothness"
            append_measurement(result, "pipeline_fps_fraction", family, measured_fps / fps, targets.get("fps_fraction"), complete,
                               minimum=True, plan_index=plan_index, stage=stage)
            for percentile in ("p95", "p99"):
                value = window["interval_ns"][percentile]
                append_measurement(result, "pipeline_interval_" + percentile + "_periods", family,
                                   value * fps / 1e9 if value is not None else None,
                                   targets.get("interval_" + percentile + "_periods"), complete, plan_index=plan_index, stage=stage)
            append_measurement(result, "pipeline_freeze_gaps", family, len(freezes) if threshold is not None else None,
                               targets.get("max_freezes"), complete, plan_index=plan_index, stage=stage)
            if expected is not None:
                append_measurement(result, "pipeline_missing_expected_frames", family, len(missing), targets.get("max_missing_frames"),
                                   complete, plan_index=plan_index, stage=stage)
    return windows


def media_age_plans(spec, events, scopes, mapped, result):
    """Capture-to-playout age per exact source PTS across two phones, only through measured clock anchors."""
    outcomes = []
    targets = spec.get("acceptance", {})
    for plan_index, plan in enumerate(spec.get("media_age", [])):
        sender, receiver = plan.get("sender"), plan.get("receiver")
        for side in (sender, receiver):
            if not isinstance(side, dict):
                raise ValueError("media_age needs sender and receiver identities")
            identity(side)
        if sender["run_id"] != receiver["run_id"] or sender["source"] != receiver["source"] or sender["phone"] == receiver["phone"]:
            raise ValueError("media_age sides must be two phones of one run and one original source")
        from_stage, to_stage = plan.get("from_stage", "raw_capture"), plan.get("to_stage", "release")
        if from_stage not in STAGES or to_stage not in STAGES or from_stage == to_stage:
            raise ValueError("media_age stages must be two different supported stages")
        start, end = plan.get("start_ns"), plan.get("end_ns")
        if (start is None) != (end is None) or start is not None and (integer(start, "start_ns", 0) >= integer(end, "end_ns", 1)):
            raise ValueError("media_age window needs start_ns < end_ns on the common clock")
        sent = [row for row in events if key(row) == key(sender) and row["stage"] == from_stage]
        played = [row for row in events if key(row) == key(receiver) and row["stage"] == to_stage]
        sent_counts, played_counts = Counter(row["pts_us"] for row in sent), Counter(row["pts_us"] for row in played)
        duplicates = sorted(pts for pts, count in {**sent_counts, **played_counts}.items()
                            if sent_counts[pts] > 1 or played_counts[pts] > 1)
        sent_by, played_by = ({row["pts_us"]: row for row in rows} for rows in (sent, played))
        ages, uncertainties, unaligned, negative, outside = [], [], 0, 0, 0
        for pts in sorted(set(sent_by) & set(played_by) - set(duplicates)):
            a, b = mapped(sent_by[pts], sent_by[pts]["at_ns"]), mapped(played_by[pts], played_by[pts]["at_ns"])
            if a is None or b is None:
                outside += 1
            elif a["clock"] != b["clock"] or isinstance(a["clock"], tuple):
                unaligned += 1  # No measured anchor joins these two phone clocks.
            elif start is not None and not start <= a["at_ns"] < end:
                continue
            elif b["at_ns"] - a["at_ns"] + a["uncertainty_ns"] + b["uncertainty_ns"] < 0:
                negative += 1
            else:
                ages.append((b["at_ns"] - a["at_ns"]) / 1e6)
                uncertainties.append((a["uncertainty_ns"] + b["uncertainty_ns"]) / 1e6)
        finalized = all(row["status"] == "COMPLETE" for row in scopes
                        if key(row, ("run_id", "phone", "session", "pid", "clock")) in
                        {key(sender, ("run_id", "phone", "session", "pid", "clock")),
                         key(receiver, ("run_id", "phone", "session", "pid", "clock"))}) and any(
            key(row, ("run_id", "phone", "session", "pid", "clock")) == key(sender, ("run_id", "phone", "session", "pid", "clock"))
            for row in scopes) and any(
            key(row, ("run_id", "phone", "session", "pid", "clock")) == key(receiver, ("run_id", "phone", "session", "pid", "clock"))
            for row in scopes)
        complete = bool(ages) and finalized and not (duplicates or unaligned or negative or outside)
        distribution_ms = distribution(ages)
        uncertainty = max(uncertainties, default=None)
        issues = [name for name, count in (("duplicate_pts", len(duplicates)), ("unaligned_clocks", unaligned),
                  ("negative_age_beyond_uncertainty", negative), ("outside_measured_anchors", outside)) if count]
        if not finalized:
            issues.append("trace_scopes_not_finalized")
        outcomes.append({"plan_index": plan_index, "source": sender["source"], "sender": sender["phone"],
                         "receiver": receiver["phone"], "from_stage": from_stage, "to_stage": to_stage,
                         "samples": len(ages), "sent_pts": len(sent_by), "played_pts": len(played_by),
                         "sent_never_played_pts": len(set(sent_by) - set(played_by)), "age_ms": distribution_ms,
                         "max_uncertainty_ms": uncertainty, "issues": issues, "status": "COMPLETE" if complete else "INCOMPLETE",
                         "scope": "Capture-to-release/ack age of frames that reached playout; pipeline proxy, not physical screen time"})
        for name, value in (("media_age_p95_ms", distribution_ms["p95"]), ("media_age_max_ms", distribution_ms["max"])):
            upper = value + uncertainty if value is not None and uncertainty is not None else None
            append_measurement(result, name, "media_age", upper, targets.get(name), complete, plan_index=plan_index,
                               stage=to_stage, nominal_ms=value, uncertainty_ms=uncertainty, sender=sender["phone"],
                               receiver=receiver["phone"],
                               reason=("Upper bound (nominal + clock uncertainty) compared with the declared target; "
                                       "pipeline proxy for playout, not physical screen time") if complete else
                                      "No complete anchored cross-phone sample set: " + (", ".join(issues) or "no matched PTS"))
    return outcomes


def lifecycle(spec, base, retained, mapped, result):
    if spec.get("lifecycle_events") is None:
        return []
    doc = document(spec["lifecycle_events"], base, "benchmark-lifecycle-events-v1", retained, "lifecycle_events")
    rows = doc.get("events", [])
    outcomes = []
    for plan in spec.get("lifecycle_plans", []):
        required = ("run_id", "phone", "session", "attempt", "action")
        if any(not isinstance(plan.get(field), str) or not plan[field] for field in required):
            raise ValueError("Lifecycle requires actual run/phone/session/attempt/action identity")
        integer(plan.get("pid"), "lifecycle pid", 1)
        if not isinstance(plan.get("clock"), str) or not plan["clock"]:
            raise ValueError("Lifecycle requires an explicit clock")
        start, end = integer(plan.get("start_ns"), "lifecycle start_ns", 0), integer(plan.get("end_ns"), "lifecycle end_ns", 1)
        if end <= start:
            raise ValueError("Invalid lifecycle planned interval")
        chosen = [row for row in rows if key(row, required) == key(plan, required)]
        kinds = ("fault", "command", "ack", "first_surface") if plan.get("requires_fault") else ("command", "ack", "first_surface")
        if plan.get("requires_surface", True) is False:
            kinds = tuple(kind for kind in kinds if kind != "first_surface")
        selected, reasons = {}, []
        for kind in kinds:
            pid = integer(plan.get(kind + "_pid", plan.get("pid")), kind + "_pid", 1)
            matches = [row for row in chosen if row.get("kind") == kind and row.get("pid") == pid and row.get("clock") == plan.get("clock")]
            if len(matches) != 1 or kind == "ack" and matches[0].get("authenticated") is not True:
                reasons.append("missing_or_ambiguous_authenticated_" + kind if kind == "ack" else "missing_or_ambiguous_" + kind)
                continue
            integer(matches[0].get("at_ns"), "lifecycle at_ns", 0)
            selected[kind] = mapped(matches[0], matches[0]["at_ns"])
        boundary_a, boundary_b = mapped(plan, start), mapped(plan, end)
        comparable = (len(selected) == len(kinds) and all(row is not None for row in selected.values())
                      and boundary_a is not None and boundary_b is not None
                      and len({str(row["clock"]) for row in [*selected.values(), boundary_a, boundary_b] if row}) == 1)
        command_ack, recovery, uncertainty = None, None, None
        if comparable:
            command_ack = (selected["ack"]["at_ns"] - selected["command"]["at_ns"]) / 1e6
            origin = selected.get("fault", selected["command"])
            finish = selected.get("first_surface", selected["ack"])
            recovery = (finish["at_ns"] - origin["at_ns"]) / 1e6
            uncertainty = (finish["uncertainty_ns"] + origin["uncertainty_ns"]) / 1e6
            if command_ack < 0 or recovery < 0:
                comparable = False
                reasons.append("signed_negative_lifecycle_span")
            if any(not boundary_a["at_ns"] <= row["at_ns"] <= boundary_b["at_ns"] for row in selected.values()):
                comparable = False
                reasons.append("completion_outside_planned_lifecycle_interval")
        else:
            reasons.append("missing_completion_or_common_measured_clock")
        outcome = {**plan, "status": "MEASURED" if comparable else "UNMEASURED", "issues": reasons,
                   "command_ack_ms": command_ack, "fault_or_command_to_first_surface_ms": recovery,
                   "uncertainty_ms": uncertainty, "planned_duration_ms": (end - start) / 1e6,
                   "remaining_tail_ms": (boundary_b["at_ns"] - max((row["at_ns"] for row in selected.values() if row), default=boundary_a["at_ns"])) / 1e6
                   if boundary_a and boundary_b else None}
        outcomes.append(outcome)
        ack_uncertainty = (selected["ack"]["uncertainty_ns"] + selected["command"]["uncertainty_ns"]) / 1e6 if comparable else 0
        append_measurement(result, "lifecycle_command_ack_ms", "lifecycle", command_ack + ack_uncertainty if comparable else None,
                           spec.get("acceptance", {}).get("command_ack_ms"), comparable, action=plan["action"], attempt=plan["attempt"])
        append_measurement(result, "full_fault_recovery_ms" if plan.get("requires_fault") else "lifecycle_first_surface_ms", "lifecycle",
                           recovery + uncertainty if comparable else None, spec.get("acceptance", {}).get("recovery_ms"), comparable,
                           action=plan["action"], attempt=plan["attempt"])
    return outcomes


def union(intervals):
    merged = []
    for lo, hi in sorted(intervals):
        if hi <= lo:
            continue
        if merged and lo <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], hi)
        else:
            merged.append([lo, hi])
    return merged


def overlap(spec, base, retained, mapped, result):
    outcome = {"status": "UNMEASURED", "pairs": [], "issues": []}
    if spec.get("active_media_intervals") is None:
        outcome["issues"].append("missing_explicit_active_media_intervals")
        return outcome
    doc = document(spec["active_media_intervals"], base, "benchmark-active-media-intervals-v1", retained, "active_media_intervals")
    lanes, clocks = defaultdict(lambda: {"participants": None, "nominal": [], "guaranteed": [], "possible": []}), set()
    for row in doc.get("intervals", []):
        identity(row)
        participants = row.get("participants")
        if row.get("basis") != "observed_active_media_interval" or not isinstance(row.get("lane"), str) or not row["lane"]:
            outcome["issues"].append("interval_not_explicitly_observed")
            continue
        if not isinstance(participants, list) or not participants or any(not isinstance(phone, str) or not phone for phone in participants) or row["phone"] not in participants:
            raise ValueError("Observed lane needs explicit participant phone aliases")
        lo, hi = integer(row.get("start_ns"), "media start_ns", 0), integer(row.get("end_ns"), "media end_ns", 1)
        if hi <= lo:
            raise ValueError("Invalid active media interval")
        a, b = mapped(row, lo), mapped(row, hi)
        if a is None or b is None or a["clock"] != b["clock"]:
            outcome["issues"].append("interval_outside_measured_clock_anchors")
            continue
        clocks.add(str(a["clock"]))
        lane = lanes[row["lane"]]
        if lane["participants"] is not None and lane["participants"] != sorted(set(participants)):
            raise ValueError("Lane participant set changed within observation")
        lane["participants"] = sorted(set(participants))
        lane["nominal"].append([a["at_ns"], b["at_ns"]])
        lane["guaranteed"].append([a["at_ns"] + a["uncertainty_ns"], b["at_ns"] - b["uncertainty_ns"]])
        lane["possible"].append([a["at_ns"] - a["uncertainty_ns"], b["at_ns"] + b["uncertainty_ns"]])
    if len(clocks) != 1 or len(lanes) < 2:
        outcome["issues"].append("missing_two_lanes_in_same_measured_clock")
    if outcome["issues"]:
        return outcome
    if any(set(lanes[left]["participants"]) & set(lanes[right]["participants"]) for left, right in itertools.combinations(lanes, 2)):
        outcome["issues"].append("lanes_share_phone_ownership")
        return outcome
    for left, right in itertools.combinations(sorted(lanes), 2):
        pair = {"left": left, "right": right}
        for mode in ("nominal", "guaranteed", "possible"):
            intersections = [[max(a, c), min(b, d)] for a, b in union(lanes[left][mode]) for c, d in union(lanes[right][mode])]
            intersections = union(intersections)
            pair[mode + "_intervals_ns"] = intersections
            pair[mode + "_duration_ms"] = sum(b - a for a, b in intersections) / 1e6
        outcome["pairs"].append(pair)
        minimum = spec.get("acceptance", {}).get("minimum_overlap_ms")
        append_measurement(result, "actual_media_overlap_ms", "transport", pair["guaranteed_duration_ms"], minimum,
                           minimum is not None and number(minimum, "minimum_overlap_ms", .000001) > 0,
                           minimum=True, left=left, right=right)
    outcome["status"] = "MEASURED" if outcome["pairs"] and not outcome["issues"] else "UNMEASURED"
    outcome["common_clock"] = next(iter(clocks))
    return outcome


def resources(spec, base, retained, windows, result):
    if spec.get("resources") is None:
        return []
    doc = document(spec["resources"], base, "benchmark-resource-events-v1", retained, "resources")
    outcomes = []
    for plan in spec.get("resource_plans", []):
        domain = ("run_id", "phone", "session", "pid", "clock")
        start, end = integer(plan.get("start_ns"), "resource start_ns", 0), integer(plan.get("end_ns"), "resource end_ns", 1)
        maximum_gap = integer(plan.get("maximum_gap_ns"), "maximum_gap_ns", 1)
        minimum_seconds = number(plan.get("minimum_duration_seconds"), "minimum_duration_seconds", .000001)
        required_metrics = plan.get("required_metrics")
        supported_metrics = {"cpu_percent", "battery_percent", "temperature_c", "rss_bytes"}
        if not isinstance(required_metrics, list) or not required_metrics or set(required_metrics) - supported_metrics:
            raise ValueError("Resource plan needs explicit supported required_metrics")
        if end <= start:
            raise ValueError("Resource plan end must exceed start")
        rows = sorted((row for row in doc.get("events", []) if key(row, domain) == key(plan, domain)), key=lambda row: integer(row.get("at_ns"), "resource at_ns", 0))
        intervals = [b["at_ns"] - a["at_ns"] for a, b in zip(rows, rows[1:])]
        covered = bool(rows) and rows[0]["at_ns"] <= start and rows[-1]["at_ns"] >= end and max(intervals, default=end - start) <= maximum_gap
        timing = [row for row in windows if key(row, domain) == key(plan, domain) and row["start_ns"] <= start and row["end_ns"] >= end]
        measured_resources = bool(rows) and all(all(row.get(field) is not None for field in required_metrics) for row in rows)
        complete = covered and measured_resources and doc.get("finalized") is True and bool(timing) and all(row["status"] == "COMPLETE" for row in timing)
        values = {field: distribution([number(row[field], field) for row in rows if row.get(field) is not None])
                  for field in ("cpu_percent", "battery_percent", "temperature_c", "rss_bytes")}
        outcome = {**plan, "status": "MEASURED" if complete else "UNMEASURED", "planned_seconds": (end - start) / 1e9,
                   "observed_samples": len(rows), "resource_coverage_complete": covered,
                   "required_metrics_complete": measured_resources,
                   "finalized": doc.get("finalized") is True, "timing_coverage_complete": bool(timing) and all(row["status"] == "COMPLETE" for row in timing),
                   "gap_ns": distribution(intervals), "values": values, "unplugged_power_thermal_quality": "UNMEASURED"}
        outcomes.append(outcome)
        append_measurement(result, "endurance_observation_duration_seconds", "endurance", outcome["planned_seconds"], minimum_seconds,
                           complete, minimum=True, phone=plan.get("phone"))
        append_measurement(result, "endurance_power_thermal_quality", "endurance", None,
                           reason="Resource timestamps do not prove unplugged power, heat or preserved quality", phone=plan.get("phone"))
    return outcomes


def analyze(spec, base):
    if spec.get("schema") != "benchmark-pipeline-observation-v1":
        raise ValueError("Expected benchmark-pipeline-observation-v1")
    base, retained = Path(base), {}
    result = {"schema": "benchmark-pipeline-trace-v1", "measurement_status": "UNMEASURED",
              "scope": {"physical_latency": "UNMEASURED", "clocks": "run/phone/PID-qualified; no automatic device alignment"},
              "evidence": retained, "diagnostics": {}, "measurements": [], "limitations": LIMITATIONS}
    events, scopes, envelopes, filtered = load_traces(spec, base, retained)
    mapped = clock_mapper(spec, base, retained)
    spans, ambiguous = span_diagnostics(events)
    windows = timing_plans(spec, events, scopes, envelopes, result)
    result["diagnostics"].update({"scopes": scopes, "filtered_unrelated_records": filtered,
                                  "events": events, "local_spans": spans, "ambiguous_stage_identities": ambiguous,
                                  "timing_windows": windows,
                                  "lifecycle": lifecycle(spec, base, retained, mapped, result),
                                  "actual_media_overlap": overlap(spec, base, retained, mapped, result),
                                  "media_age": media_age_plans(spec, events, scopes, mapped, result),
                                  "resources": resources(spec, base, retained, windows, result)})
    for before, after in SPANS:
        matching = [row for row in spans if row["from_stage"] == before and row["to_stage"] == after]
        samples = [row["duration_ns"] for row in matching if row["status"] == "MEASURED"]
        limit = spec.get("acceptance", {}).get("span_max_ms", {}).get(before + "->" + after)
        # Scope completion is needed to claim no omitted slower span. Values remain useful diagnostics.
        complete = bool(samples) and bool(scopes) and all(row["status"] == "COMPLETE" for row in scopes) and not ambiguous and all(row["status"] == "MEASURED" for row in matching)
        append_measurement(result, before + "_to_" + after + "_max_ms", "transport", max(samples) / 1e6 if samples else None,
                           limit, complete)
    append_measurement(result, "physical_pipeline_latency", "physical_latency", None,
                       reason="Stage proxies cannot measure physical screen or acoustic latency")
    measured = bool(events) or any(row["status"] == "MEASURED" for row in result["diagnostics"]["lifecycle"]) or result["diagnostics"]["actual_media_overlap"]["status"] == "MEASURED"
    result["measurement_status"] = "MEASURED" if measured else "UNMEASURED"
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    args.output = output_path(args.output, "runs")
    try:
        if args.output.exists():
            raise ValueError("Output exists; choose a fresh explicit path")
        spec_path = resolve_input(args.spec)
        result = analyze(json.loads(spec_path.read_text(encoding="utf-8-sig")), original(spec_path).parent)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    except (ValueError, OSError, TypeError, KeyError) as error:
        parser.error(str(error))
    print(f"{result['measurement_status']}: {len(result['diagnostics']['local_spans'])} local spans; physical latency UNMEASURED. {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
