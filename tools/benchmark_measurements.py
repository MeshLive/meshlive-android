"""Attach hash-bound measurement observations to exact saved executions, without device I/O."""
import hashlib
import importlib
import json
import csv
from pathlib import Path
import re

from benchmark_layout import original, resolve as resolve_input
from evidence_relocation import logical

ADAPTERS = {"visual_markers": "score_visual_markers", "audio_sync": "score_audio_sync",
            "screen_geometry": "score_screen_geometry", "pipeline": "score_pipeline_trace",
            "impairment": "score_impairment_trace", "physical_av": "score_physical_av"}


def record(entry, base):
    if not isinstance(entry, dict) or not isinstance(entry.get("path"), str) or not entry["path"] or not re.fullmatch(
            r"[0-9a-fA-F]{64}", entry.get("sha256", "")):
        raise ValueError("Measurement evidence needs a path and exact SHA256")
    path = resolve_input(entry["path"], base=base)
    with path.open("rb") as handle:
        sha = hashlib.file_digest(handle, "sha256").hexdigest()
    if sha != entry["sha256"].lower():
        raise ValueError(f"Measurement evidence changed: {path}")
    return path, {"path": str(path), "sha256": sha}


def validate_pipeline(spec, manifest, base, manifest_base, binding):
    executions = {manifest["run_id"]: (manifest, manifest_base)}
    for entry in spec.get("related_manifests", []):
        path, _ = record(entry, base)
        other = json.loads(path.read_text(encoding="utf-8-sig"))
        if other.get("run_id") in executions:
            raise ValueError("Duplicate related pipeline execution")
        executions[other["run_id"]] = (other, original(path).parent)
    def identity(row, related=False):
        if not related and row.get("run_id") != manifest["run_id"]:
            raise ValueError("Related pipeline executions are overlap evidence, not current-case gates")
        executed, _ = executions[row["run_id"]]
        if not related and (row.get("phone") != binding["device"] or row.get("source", binding["source"]) != binding["source"]):
            raise ValueError("Pipeline gates must belong to their bound phone/source")
        if not any(item.get("device") == row["phone"] and str(item.get("pid")) == str(row["pid"])
                   for item in executed.get("logs", [])):
            raise ValueError("Pipeline observation phone/PID was not executed")
        if "session" in row and row["session"] != executed.get("media_trace_session"):
            raise ValueError("Pipeline observation session was not executed")
    for trace in spec.get("traces", []):
        identity(trace)
        executed, execution_base = executions[trace["run_id"]]
        phone = trace["phone"]
        log_path, _ = record(trace if trace["format"] == "logcat" else trace["source_log"], base)
        logs = [row for row in executed.get("logs", []) if row.get("device") == phone and str(row.get("pid")) == str(trace["pid"])]
        paths = {resolve_input(row["path"], base=execution_base) for row in logs}
        if log_path not in paths or trace["session"] != executed.get("media_trace_session"):
            raise ValueError("Pipeline trace does not match the executed log/session/PID")
        scenario = executed["scenario"]
        senders = {scenario["caller"], scenario["callee"]} if "caller" in scenario else {scenario["source"]}
        if trace["source"] not in senders:
            raise ValueError("Pipeline trace source is not an executed sender")
    for field in ("plans", "lifecycle_plans", "resource_plans"):
        for row in spec.get(field, []):
            identity(row)
    for field, key in (("lifecycle_events", "events"), ("resources", "events"),
                       ("active_media_intervals", "intervals"), ("clock_anchors", "anchors")):
        if spec.get(field):
            path, _ = record(spec[field], base)
            doc = json.loads(path.read_text(encoding="utf-8-sig"))
            for row in doc.get(key, []):
                identity(row, related=field in {"active_media_intervals", "clock_anchors"})
            if field in {"lifecycle_events", "resources"}:
                sources = doc.get("source_logs", [])
                if not sources:
                    raise ValueError("Derived lifecycle/resource observations require executed source logs")
                for source in sources:
                    path, _ = record(source, base)
                    original_paths = {resolve_input(row["path"], base=original_base)
                        for original, original_base in executions.values() for row in original.get("logs", [])}
                    if path not in original_paths:
                        raise ValueError("Derived pipeline observation source log was not executed")
    return executions


def validate_capture(entry, spec, manifest, manifest_base, base, entry_base):
    kind, scope = entry["kind"], entry["scope"]
    allowed = {}
    def identity(file):
        path = resolve_input(file["path"], base=manifest_base)
        return str(path), file["sha256"].lower()
    for trace in manifest.get("media_traces", []):
        if trace.get("device") == scope["device"] and trace.get("role") == "receiver" and trace.get("state") == "MEASURED":
            if trace.get("summary", {}).get("session") != scope["session"] or str(trace.get("summary", {}).get("pid")) != str(scope["pid"]):
                continue
            for file in trace.get("files", []):
                suffix = Path(file["path"]).suffix
                if kind == "audio_sync" and (trace.get("kind") != "audio_pcm" or suffix != ".wav") or (
                        kind == "visual_markers" and (trace.get("kind") != "video_access_units" or suffix != ".ivf")) or kind == "screen_geometry":
                    continue
                allowed[identity(file)] = {**file, "origin": trace.get("kind"), "trace_files": trace["files"]}
    for capture in manifest.get("measurement_captures", []):
        if capture.get("origin") not in {"DEVICE_CAPTURE", "EXTERNAL_RECORDING"} or any(
                capture.get(field) != scope.get(field) for field in ("device", "source", "pid", "session")) or capture.get("direction") != "incoming":
            continue
        allowed[identity(capture)] = capture
    observed = spec.get("observed") if kind == "visual_markers" else spec["capture"].get("wav") if kind == "audio_sync" else spec["capture"]
    path, retained = record(observed, base)
    provenance = allowed.get((str(path), retained["sha256"]))
    extracted = None
    if (str(path), retained["sha256"]) not in allowed:
        receipt_path, _ = record(entry.get("extraction"), entry_base)
        receipt = json.loads(receipt_path.read_text(encoding="utf-8-sig"))
        original_path, original_record = record(receipt["input"], original(receipt_path).parent)
        extracted = receipt.get("video" if kind == "visual_markers" else "audio", {})
        if receipt.get("schema") != "benchmark-measurement-capture-v1" or receipt.get("measurement_status") != "MEASURED" or (
                str(original_path), original_record["sha256"]) not in allowed or extracted.get("sha256") != retained["sha256"]:
            raise ValueError("Observed samples are not derived from an executed capture")
        provenance = allowed[(str(original_path), original_record["sha256"])]
        if kind == "audio_sync" and (spec["capture"].get("clock") != extracted.get("clock") or spec["capture"].get("timeline") != extracted.get("timeline")):
            raise ValueError("Captured audio clock/continuity differs from the original recording extraction")
        if kind == "visual_markers" and provenance["origin"] == "video_access_units" and spec["observed"].get("stage") != "offline_decode":
            raise ValueError("Captured access units can only be labelled offline_decode, not actual screen output")
        if kind == "visual_markers" and any(extracted.get(key) != spec["observed"].get(key) for key in ("width", "height", "format", "frame_count")):
            raise ValueError("Observed declaration differs from the exact extracted samples")
    record(provenance, manifest_base)
    if kind == "screen_geometry":
        source = scope["source"]
        fixture = manifest.get("source_fixtures", {}).get(source) or manifest.get("source_fixture", {})
        calibration = (fixture.get("declared_metadata") or {}).get("sidecars", {}).get("calibration_reference", {})
        if not calibration or spec["calibration"]["reference"]["sha256"] != calibration.get("sha256"):
            raise ValueError("Calibration reference was not declared in the executed native fixture")
    timing = False
    if kind == "visual_markers" and provenance.get("origin") != "video_access_units":
        original_times = provenance.get("timestamps")
        clock = provenance.get("clock")
        if original_times and isinstance(clock, str) and clock and clock == spec.get("planned", {}).get("clock"):
            path, _ = record(original_times, manifest_base)
            sample_path, _ = record(spec["samples"], base)
            def times(path):
                with path.open(encoding="utf-8-sig", newline="") as handle:
                    return [(int(row["sample_index"]), int(row["time_ns"])) for row in csv.DictReader(handle)]
            timing = times(path) == times(sample_path)
            if extracted:
                extracted_path, _ = record(extracted["timestamps"], original(receipt_path).parent)
                timing = timing and extracted.get("clock") == clock and times(extracted_path) == times(path)
    if kind == "audio_sync" and provenance.get("origin") == "audio_pcm" and spec["capture"].get("clock") and not spec["capture"].get("sample_clock"):
        raise ValueError("AudioTrack accepted PCM requires recorded hardware sample-clock evidence; no invented zero-time anchor")
    if kind == "audio_sync" and provenance.get("origin") == "audio_pcm" and spec["capture"].get("sample_clock"):
        clock_path, _ = record(spec["capture"]["sample_clock"], base)
        clock_evidence = json.loads(clock_path.read_text(encoding="utf-8-sig"))["evidence"]
        executed_files = {identity(file) for file in provenance["trace_files"]}
        for field in ("wav", "chunks", "summary"):
            _, file = record(clock_evidence[field], original(clock_path).parent)
            if (file["path"], file["sha256"]) not in executed_files:
                raise ValueError("AudioTrack sample clock must use the exact executed PCM phase WAV/chunks/summary")
    if kind == "audio_sync" and spec["capture"].get("clock") and not spec["capture"].get("sample_clock"):
        if extracted:
            original_clock = provenance.get("clock")
            original_id = original_clock.get("id") if isinstance(original_clock, dict) else original_clock
            if not original_id or extracted.get("clock", {}).get("id") != original_id:
                raise ValueError("Extracted audio clock is not bound to the registered original recording clock")
            if isinstance(original_clock, dict) and "sample_zero_ns" in original_clock and original_clock["sample_zero_ns"] != extracted["clock"].get("sample_zero_ns"):
                raise ValueError("Extracted audio sample-zero anchor differs from registered capture metadata")
        elif (spec["capture"]["clock"] != provenance.get("clock") or
              spec["capture"].get("timeline") != provenance.get("timeline")):
            raise ValueError("Captured audio clock/continuity is not bound to registered capture metadata")
    framing = (kind == "screen_geometry" and isinstance(provenance.get("viewport"), dict) and
               isinstance(provenance.get("requested_mapping"), dict) and provenance["viewport"] == spec.get("viewport") and
               provenance["requested_mapping"] == spec.get("mapping"))
    return {"origin": provenance.get("origin"), "timing_status": "MEASURED" if timing else "UNMEASURED",
            "framing_request_status": "MEASURED" if framing else "UNMEASURED"}


def validate_physical_capture(entry, spec, manifest, manifest_base, base, entry_base):
    """Physical observations require actual acquisition, never decoder/PCM traces."""
    scope = entry["scope"]
    path, retained = record(spec["recording"], base)
    candidates = [row for row in manifest.get("measurement_captures", [])
        if row.get("origin") in {"EXTERNAL_RECORDING", "DEVICE_CAPTURE"}
        and all(row.get(field) == scope.get(field) for field in ("device", "source", "pid", "session"))
        and row.get("direction") == "incoming"
        and resolve_input(row["path"], base=manifest_base) == path
        and row.get("sha256", "").lower() == retained["sha256"]]
    if len(candidates) != 1:
        raise ValueError("Physical recording is not uniquely registered to this executed source/receiver/PID/session")
    capture = candidates[0]
    record(capture, manifest_base)
    if capture.get("run_id") != scope["run_id"] or capture.get("apk_sha256", "").lower() != scope["apk_sha256"].lower():
        raise ValueError("Physical recording run/build differs from execution")
    if capture.get("capture_method") not in {"independent_camera", "independent_camera_microphone"} or not {
            "source_target", "receiver_screen"}.issubset(set(capture.get("observes", []))):
        raise ValueError("Physical screen delay needs an independent recording of the actual source target and receiver screen")
    if capture["origin"] == "DEVICE_CAPTURE" and capture.get("recorder") in {None, scope["device"], scope["source"]}:
        raise ValueError("Device capture must name an independent observing device; screenshots/decoder output are proxies")
    clock = capture.get("clock")
    clock_id = clock.get("id") if isinstance(clock, dict) else clock
    if not clock_id or clock_id != spec.get("planned", {}).get("clock"):
        raise ValueError("Physical observation must use the registered original recording clock")
    if spec.get("source_audio") is not None and spec.get("audio") is None:
        raise ValueError("A source microphone needs its separate receiver microphone observation")
    extracted_channels = {}
    for field, role, observes, receipt_field in (("audio", "physical_microphone", "receiver_speaker", "extraction"),
            ("source_audio", "physical_source_microphone", "source_speaker", "source_extraction")):
        audio_spec = spec.get(field)
        if audio_spec is None:
            continue
        reference = manifest.get("reference_audios", {}).get(scope["source"]) or manifest.get("reference_audio")
        if reference:
            fixture = manifest.get("source_fixtures", {}).get(scope["source"]) or manifest.get("source_fixture", {})
            events = (fixture.get("declared_metadata") or {}).get("sidecars", {}).get("events", {})
            if audio_spec["reference"]["wav"]["sha256"] != reference.get("sha256"):
                raise ValueError("Physical audio cue reference was not injected in this execution")
            if not events or audio_spec["reference"]["events"]["sha256"] != events.get("sha256"):
                raise ValueError("Physical audio/visual cue schedule differs from the executed native input sidecar")
        else:
            stimulus = capture.get("source_stimulus", {})
            source_kind = manifest.get("source_kinds", {}).get(scope["source"], manifest.get("source_kind"))
            if source_kind != "live_camera" or capture.get("source_emission_role") != "source_acoustic_stimulus":
                raise ValueError("Non-injected cues require a registered actual live microphone source acoustic stimulus")
            for reference_field in ("wav", "events"):
                _, declared = record(stimulus[reference_field], manifest_base)
                _, observed = record(audio_spec["reference"][reference_field], base)
                if declared["sha256"] != observed["sha256"]:
                    raise ValueError("Microphone reference differs from the registered actual source stimulus")
        if capture.get("capture_method") != "independent_camera_microphone" or observes not in capture.get("observes", []):
            raise ValueError("Physical speaker observations require actual separate independent microphone recordings")
        audio_capture = audio_spec["capture"]
        if audio_capture.get("role") != role or audio_capture.get("sample_clock") is not None:
            raise ValueError("Accepted/decoded PCM and AudioTrack estimates cannot become microphone observations")
        receipt_path, _ = record(entry.get(receipt_field), entry_base)
        receipt_base = original(receipt_path).parent
        receipt = json.loads(receipt_path.read_text(encoding="utf-8-sig"))
        recorded_path, recorded = record(receipt["input"], receipt_base)
        extracted = receipt.get("audio", {})
        microphone_path, microphone = record(audio_capture["wav"], base)
        extracted_path, extracted_record = record(extracted, receipt_base)
        if (receipt.get("schema") != "benchmark-measurement-capture-v1" or receipt.get("measurement_status") != "MEASURED"
                or recorded_path != path or recorded["sha256"] != retained["sha256"]
                or extracted_path != microphone_path or extracted_record["sha256"] != microphone["sha256"]):
            raise ValueError("Physical microphone WAV is not derived from the same registered original recording")
        if any(audio_capture.get(field) != extracted.get(field) for field in ("clock", "timeline")):
            raise ValueError("Physical microphone clock/continuity differs from original extraction")
        if extracted.get("clock", {}).get("id") != clock_id or isinstance(clock, dict) and (
                "sample_zero_ns" in clock and clock["sample_zero_ns"] != extracted["clock"].get("sample_zero_ns")):
            raise ValueError("Physical microphone sample-zero anchor differs from the original recording clock")
        audio_capture["execution_scope"] = dict(scope)
        extracted_channels[field] = extracted
    if spec.get("source_audio") is not None:
        receiver, source = extracted_channels["audio"], extracted_channels["source_audio"]
        receiver_channel, source_channel = receiver.get("channel_selection", {}), source.get("channel_selection", {})
        native_channels = receiver_channel.get("native_channels")
        if (type(receiver_channel.get("channel_index")) is not int or type(source_channel.get("channel_index")) is not int
                or type(native_channels) is not int or native_channels < 2
                or not 0 <= receiver_channel["channel_index"] < native_channels
                or not 0 <= source_channel["channel_index"] < native_channels
                or receiver_channel["channel_index"] == source_channel["channel_index"]
                or receiver_channel.get("native_channels") != source_channel.get("native_channels")
                or type(receiver.get("stream_index")) is not int or receiver["stream_index"] != source.get("stream_index")
                or any(receiver.get(key) != source.get(key) for key in ("clock", "sample_count", "timeline"))):
            raise ValueError("Source and receiver microphones need distinct explicit channels of the same native ADC stream/clock")
        if any(spec[field]["capture"].get("channel_selection") != extracted_channels[field].get("channel_selection")
               for field in ("audio", "source_audio")):
            raise ValueError("Microphone native channel selection differs from the registered extraction")
        emission = capture.get("source_emission_role")
        if emission not in {"source_acoustic_stimulus", "source_reference_preview"}:
            raise ValueError("Observed source acoustic emission/preview role must be explicitly declared")
        if emission == "source_acoustic_stimulus" and reference is not None:
            raise ValueError("Reference injection bypasses microphone capture; a preview cannot become the actual source acoustic stimulus")
    return {"origin": capture["origin"], "physical_observation_status": "MEASURED", "clock": clock_id,
            "capture_method": capture["capture_method"], "observes": capture["observes"],
            "observation_plan": capture.get("observation_plan"), "conditions": capture.get("conditions"),
            "source_emission_role": capture.get("source_emission_role")}


def validate_adapter(entry, manifest, manifest_path, base):
    """Verify one adapter's execution/evidence binding without scoring or device I/O."""
    manifest_path, base = resolve_input(manifest_path), Path(base)
    kind = entry.get("kind")
    if not isinstance(kind, str) or kind not in ADAPTERS or not isinstance(entry.get("id"), str) or not re.fullmatch(r"[A-Za-z0-9_-]+", entry["id"]):
        raise ValueError("Unknown adapter kind or invalid ID")
    scope = entry.get("scope", {})
    if not isinstance(scope, dict):
        raise ValueError("Measurement scope must be an object")
    device, devices = scope.get("device"), manifest.get("devices", {})
    if scope.get("run_id") != manifest.get("run_id") or device not in devices:
        raise ValueError("Measurement run/device does not match execution")
    if not devices[device].get("apk_sha256") or scope.get("apk_sha256", "").lower() != devices[device]["apk_sha256"].lower():
        raise ValueError("Measurement APK does not match execution")
    pids = {str(item["pid"]) for item in manifest.get("logs", []) if item.get("device") == device and item.get("pid") is not None}
    if not scope.get("session") or str(scope.get("pid")) not in pids:
        raise ValueError("Measurement session or executed PID scope is missing")
    expected_session = manifest.get("media_trace_session")
    if expected_session is not None and scope["session"] != expected_session:
        raise ValueError("Measurement session differs from the executed trace session")
    source, scenario = scope.get("source"), manifest["scenario"]
    senders = {scenario["caller"], scenario["callee"]} if "caller" in scenario else {scenario["source"]}
    if source not in senders or source == device and kind in {"visual_markers", "audio_sync", "screen_geometry", "physical_av"}:
        raise ValueError("Measurement source/direction is not an executed media path")
    profile = scenario["profiles"][source] if "profiles" in scenario else scenario["profile"]
    if profile.get("profile_source") == "app_default" and kind == "visual_markers":
        raise ValueError("Marker reference needs a declared native profile; an app_default endpoint declares none")
    spec_path, spec_record = record(entry["spec"], base)
    spec = json.loads(spec_path.read_text(encoding="utf-8-sig"))
    if not isinstance(spec, dict):
        raise ValueError("Measurement spec must be an object")
    if kind == "visual_markers":
        reference = spec["reference"]
        fixture = manifest.get("source_fixtures", {}).get(source) or manifest.get("source_fixture")
        if not fixture or reference.get("sha256") != fixture.get("sha256") or any(
                reference.get(key) != profile[key] for key in ("width", "height", "fps")):
            raise ValueError("Marker reference differs from executed native input/profile")
    if kind == "audio_sync":
        reference = manifest.get("reference_audios", {}).get(source) or manifest.get("reference_audio")
        if not reference or spec["reference"]["wav"]["sha256"] != reference.get("sha256"):
            raise ValueError("Audio reference was not injected in this execution")
    if kind == "screen_geometry" and any(spec.get("calibration", {}).get(key) != profile[key] for key in ("width", "height")):
        raise ValueError("Calibration geometry differs from executed native profile")
    capture_provenance = None
    if kind in {"visual_markers", "audio_sync", "screen_geometry"}:
        capture_provenance = validate_capture(entry, spec, manifest, original(manifest_path).parent, original(spec_path).parent, base)
        if kind == "audio_sync":
            spec["capture"]["execution_scope"] = scope
    if kind == "pipeline":
        validate_pipeline(spec, manifest, original(spec_path).parent, original(manifest_path).parent, scope)
    if kind == "physical_av":
        capture_provenance = validate_physical_capture(entry, spec, manifest, original(manifest_path).parent,
                                                       original(spec_path).parent, base)
        spec["_capture_provenance"] = capture_provenance
    if kind == "impairment":
        impairment = manifest.get("media_impairments", {}).get(source) or manifest.get("media_impairment")
        if source != device or not impairment or spec.get("config", {}).get("sha256") != impairment["sha256"]:
            raise ValueError("Impairment config/direction was not executed")
        if spec.get("session") != scope["session"] or str(spec.get("pid")) != str(scope["pid"]):
            raise ValueError("Impairment trace session/PID differs from execution")
        log_path, _ = record(spec.get("log"), original(spec_path).parent)
        paths = {resolve_input(row["path"], base=original(manifest_path).parent)
            for row in manifest.get("logs", []) if row.get("device") == device and str(row.get("pid")) == str(scope["pid"])}
        if log_path not in paths:
            raise ValueError("Impairment observation is not the executed log")
    return {"spec": spec, "spec_path": spec_path, "spec_record": spec_record,
            "scope": scope, "device": device, "source": source, "pids": pids,
            "capture_provenance": capture_provenance}


def analyze(path, manifest_path, manifest, output):
    """The sidecar binds evidence to the manifest; observations cannot change its request."""
    if path is None:
        return {"schema": "benchmark-measurement-analysis-v1", "adapters": [], "measurements": [], "errors": []}
    path = resolve_input(path)
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    if data.get("schema") != "benchmark-measurements-v1" or not isinstance(data.get("cases"), list):
        raise ValueError("Expected benchmark-measurements-v1 with cases")
    manifest_path = resolve_input(manifest_path)
    matched = []
    for case in data["cases"]:
        if case.get("case_id") == manifest["scenario"]["id"]:
            recorded_manifest, receipt = record(case["manifest"], original(path).parent)
            if recorded_manifest == manifest_path:
                matched.append((case, receipt))
    if len(matched) != 1:
        raise ValueError("Measurements need exactly one matching case/manifest hash")
    case, manifest_record = matched[0]
    entries = case.get("adapters")
    if not isinstance(entries, list) or not entries or any(not isinstance(item, dict) or not isinstance(item.get("id"), str)
            or not item["id"] for item in entries) or len({item["id"] for item in entries}) != len(entries):
        raise ValueError("Measurement adapters need unique nonempty IDs")
    result = {"schema": "benchmark-measurement-analysis-v1", "manifest": manifest_record,
              "contract": {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()},
              "adapters": [], "measurements": [], "errors": []}
    for entry in entries:
        kind = entry.get("kind")
        family = {"screen_geometry": "picture", "audio_sync": "acoustic", "visual_markers": "smoothness", "physical_av": "physical_latency"}.get(kind, "transport") if isinstance(kind, str) else "transport"
        try:
            bound = validate_adapter(entry, manifest, manifest_path, original(path).parent)
            scope, device, source, pids = (bound[key] for key in ("scope", "device", "source", "pids"))
            spec, spec_path, spec_record = (bound[key] for key in ("spec", "spec_path", "spec_record"))
            capture_provenance = bound["capture_provenance"]
            module = importlib.import_module(ADAPTERS[kind])
            scored = module.analyze(spec, original(spec_path).parent)
            if kind == "visual_markers" and capture_provenance["timing_status"] != "MEASURED":
                scored.setdefault("scope", {})["timing_status"] = "UNMEASURED"
                scored["scope"]["timing_reason"] = "Capture timestamps are not bound to actual observation-clock evidence"
                content = {"missing_frames", "repeated_frames", "out_of_order_frames", "unknown_frames", "missing_prefix_frames", "missing_tail_frames"}
                for row in scored.get("measurements", []):
                    if row.get("name") in content:
                        row["family"] = "transport"
                    elif row.get("family") == "smoothness" and row.get("status") in {"PASS", "FAIL"}:
                        row.update(status="UNMEASURED", reason=scored["scope"]["timing_reason"])
            if kind == "screen_geometry" and capture_provenance["framing_request_status"] != "MEASURED":
                for row in scored.get("measurements", []):
                    if row.get("status") in {"PASS", "FAIL"}:
                        row.update(status="UNMEASURED", reason="Viewport and intended mapping are not bound to execution capture metadata")
            destination = Path(output) / (entry["id"] + ".json")
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.exists():
                raise ValueError("Adapter result already exists; use a fresh analysis output")
            measured_scope = dict(scope)
            if kind == "pipeline":
                measured_scope.pop("pid", None)
                measured_scope["pids"] = sorted(pids)
            scored["execution_scope"] = measured_scope
            scored["spec"] = spec_record
            destination.write_text(json.dumps(scored, indent=2, allow_nan=False) + "\n", encoding="utf-8")
            result["adapters"].append({"id": entry["id"], "kind": kind, "scope": scope,
                "measurement_status": scored.get("measurement_status", "UNMEASURED"),
                "actual_media_overlap": scored.get("diagnostics", {}).get("actual_media_overlap") if kind == "pipeline" else None,
                "evidence": str(logical(destination))})
            for row in scored.get("measurements", []):
                row = dict(row)
                if row.get("status") == "MEASURED":
                    continue  # Values without gates stay in the supporting report.
                if row.get("family") == "screen_geometry":
                    row["family"] = "picture"
                row.setdefault("family", family)
                if row.get("status") not in {"PASS", "FAIL", "UNMEASURED"}:
                    raise ValueError("Adapter reported an unknown verdict")
                result["measurements"].append({**row, "device": device, "source": source,
                    "adapter": entry["id"], "execution_scope": measured_scope, "evidence": str(logical(destination))})
        except (ValueError, OSError, KeyError, TypeError, AttributeError, ImportError) as error:
            result["errors"].append({"adapter": entry.get("id"), "kind": kind, "reason": str(error)})
            result["measurements"].append({"name": "adapter_validity", "family": family, "status": "UNMEASURED",
                "adapter": entry.get("id"), "reason": str(error)})
    return result
