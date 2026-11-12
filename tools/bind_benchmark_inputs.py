"""Bind provided native I420 inputs to explicit existing cases; no conversion or device I/O."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
from benchmark_layout import resolve as locate, original, output_path
import re

from benchmark_case_state import request_key
from run_device_benchmark import reference_audio_metadata
from run_benchmark_suite import load_jobs, participants, read, resolve, write

COLOR_FIELDS = ("range", "primaries", "matrix", "transfer", "chroma_location")


def file_record(specification, base):
    if not isinstance(specification.get("path"), str) or not specification["path"]:
        raise ValueError("Every input/sidecar needs a nonempty path")
    expected = specification.get("sha256")
    if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", expected):
        raise ValueError("Every input/sidecar needs its exact SHA256")
    path = locate(specification["path"], base=base)
    with path.open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if actual != expected.lower():
        raise ValueError(f"SHA256 mismatch: {path}")
    return {**specification, "path": str(path), "sha256": actual, "bytes": path.stat().st_size}


def fixture_index(manifest):
    if type(manifest.get("schema_version")) is not int or manifest["schema_version"] != 1:
        raise ValueError("Raw fixture manifest needs schema_version 1")
    rows = manifest.get("fixtures")
    if not isinstance(rows, list) or not rows:
        raise ValueError("Raw fixture manifest needs a nonempty fixtures list")
    index = {}
    for row in rows:
        if not isinstance(row, dict) or any(type(row.get(k)) is not int or row[k] <= 0 for k in ("width", "height", "fps")):
            raise ValueError("Fixture width, height and FPS must be positive integers")
        key = (row["width"], row["height"], row["fps"])
        if key in index:
            raise ValueError(f"Ambiguous duplicate native fixture: {key}")
        index[key] = row
    return index


def validate_fixture(specification, base):
    if specification.get("format") != "I420" or type(specification.get("bit_depth")) is not int or specification["bit_depth"] != 8:
        raise ValueError("Only explicitly declared 8-bit I420 is supported; no conversion")
    width, height = specification["width"], specification["height"]
    if width % 2 or height % 2:
        raise ValueError("Native I420 dimensions must be even; no resizing")
    count = specification.get("frame_count")
    if type(count) is not int or count <= 0:
        raise ValueError("Fixture frame_count must be a positive integer")
    color = specification.get("color")
    if not isinstance(color, dict) or any(not isinstance(color.get(k), str) or not color[k].strip() for k in COLOR_FIELDS):
        raise ValueError("Explicit color range/primaries/matrix/transfer/chroma_location are required; use UNSPECIFIED instead of guessing")
    record = file_record(specification, base)
    frame_bytes = width * height * 3 // 2
    if not record["bytes"] or record["bytes"] % frame_bytes or record["bytes"] != frame_bytes * count:
        raise ValueError("Fixture size must equal its nonempty declared whole I420 frame count")
    sidecars = record.get("sidecars", {})
    if not isinstance(sidecars, dict) or any(not isinstance(value, dict) for value in sidecars.values()):
        raise ValueError("Optional sidecars must map names to path/SHA256 objects")
    record["sidecars"] = {name: file_record(value, base) for name, value in sidecars.items()}
    return record


def bind_jobs(jobs, index, base, devices, with_reference_audio=False):
    verified, bound, bindings = {}, {}, []
    groups = {}
    for job in jobs:
        original = job["case"]
        if original.get("replay_file") or original.get("raw_fixture_file") or original.get("raw_fixture_files"):
            raise ValueError(f"{original['id']}: select an unbound Camera2 request; replay/mixed/existing raw inputs are not replaced")
        if any(action.get("action") == "flip" for action in original.get("actions", [])):
            raise ValueError(f"{original['id']}: raw input cannot measure a physical camera flip")
        profiles = original["profiles"] if "caller" in original else {original["source"]: original["profile"]}
        inputs = {}
        for phone, profile in profiles.items():
            if profile.get("profile_source") == "app_default":
                raise ValueError(f"{original['id']}/{phone}: profile_source app_default declares no geometry, so no raw input binds to it")
            key = (profile["width"], profile["height"], profile["fps"])
            if key not in index:
                raise ValueError(f"{original['id']}/{phone}: missing native {key[0]}x{key[1]} at {key[2]} FPS; no substitution")
            if key not in verified:
                verified[key] = validate_fixture(index[key], base)
            inputs[phone] = verified[key]
        case = copy.deepcopy(original)
        case["state"] = "NOT RUN"
        case["environment"] = "Native raw input bound offline; actual execution and radio environment UNMEASURED"
        case["eligibility"] = "Bound file hashes/declarations verified; live readiness, sustained load and quality unmeasured"
        metadata = {phone: {key: copy.deepcopy(value) for key, value in record.items() if key not in ("path", "sha256")}
                    for phone, record in inputs.items()}
        if "caller" in case:
            case["raw_fixture_files"] = {phone: record["path"] for phone, record in inputs.items()}
            case["raw_fixture_sha256s"] = {phone: record["sha256"] for phone, record in inputs.items()}
            case["raw_fixture_metadatas"] = metadata
        else:
            source = case["source"]
            case["raw_fixture_file"] = inputs[source]["path"]
            case["raw_fixture_sha256"] = inputs[source]["sha256"]
            case["raw_fixture_metadata"] = metadata[source]
        if with_reference_audio:
            audios = {}
            case["media_trace"] = True
            for phone, record in inputs.items():
                if "reference_audio" not in record["sidecars"]:
                    raise ValueError(f"{original['id']}/{phone}: supplied native reference audio is missing")
                entry = {key: record["sidecars"]["reference_audio"][key] for key in ("path", "sha256")}
                check = {"raw_fixture_file": record["path"], "profile": profiles[phone],
                         "reference_audio": entry, "media_trace": True}
                reference_audio_metadata(check)
                audios[phone] = entry
            if "caller" in case:
                case["reference_audios"] = audios
            else:
                if case.get("audio_enabled") is False:
                    raise ValueError("Reference audio cannot replace an explicitly audio-disabled request")
                case["reference_audio"] = audios[case["source"]]
        # New bindings use their actual locator; reviewed storage aliases preserve older requests.
        case["id"] = "raw-" + request_key(case)[:24]
        if case["id"] in bound:
            raise ValueError("Selected source cases produce duplicate exact raw requests; execute repeats separately")
        participants(case, devices)
        bound[case["id"]] = case
        group = (job["execution"], tuple(job["required_families"]), job.get("acceptance_profile", "device-run-v3"))
        groups.setdefault(group, []).append(case["id"])
        bindings.append({"original_case_id": original["id"], "original_request_key": request_key(original),
            "case_id": case["id"], "request_key": request_key(case), "source_suite": job["suite"],
            "source_suite_sha256": job["suite_sha256"], "source_inputs": inputs, "state": "NOT RUN"})
    return sorted(bound.values(), key=lambda c: c["id"]), bindings, groups


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", type=Path, required=True)
    parser.add_argument("--case", action="append", required=True, help="Explicit existing ID; repeat to bind more cases")
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--devices", type=Path, default="tools/benchmark_devices.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--with-reference-audio", action="store_true", help="Create separate explicit reference-PCM requests with media traces")
    args = parser.parse_args(argv)
    output, source = output_path(args.output, "catalog"), resolve(args.fixtures)
    if output.exists():
        raise FileExistsError(output)
    if len(set(args.case)) != len(args.case):
        raise ValueError("Selected case IDs must be unique")
    data = source.read_bytes()
    index = fixture_index(json.loads(data.decode("utf-8-sig")))
    devices = read(resolve(args.devices))["devices"]
    jobs = load_jobs(args.suite, devices, args.case)
    cases, bindings, groups = bind_jobs(jobs, index, original(source).parent, devices, args.with_reference_audio)
    output.mkdir(parents=True, exist_ok=False)
    (output / "source-fixtures.json").write_bytes(data)
    suite = output / "raw-suite.json"
    write(suite, {"schema_version": 1, "cases": cases,
        "source_suites": {job["suite"]: job["suite_sha256"] for job in jobs}})
    write(output / "benchmark-bundle.json", {"schema_version": 1, "suites": [
        {"path": str(suite), "execution": execution, "required_families": list(required), "case_ids": sorted(ids),
         **({"acceptance_profile": profile} if profile != "device-run-v3" else {})}
        for (execution, required, profile), ids in sorted(groups.items())]})
    manifest = {"schema": "benchmark-input-binding-v1", "state": "NOT RUN", "bindings": bindings,
        "source_manifest": {"path": str(source), "sha256": hashlib.sha256(data).hexdigest(),
            "retained_copy": str(output / "source-fixtures.json")},
        "identity": "Exact request includes input SHA/declarations and its recorded locator; a reviewed exact storage alias relocates the same bytes without rewriting that request",
        "adapters": {"marker_analysis": "UNMEASURED", "audio_injection_sync": "UNMEASURED",
            "ROI_scoring": "UNMEASURED", "encoder_color_application": "UNMEASURED"},
        "scope": "Provided file integrity and declarations only; no generation/conversion/device execution or pattern certification",
        "measured_executions": 0, "passes": 0}
    write(output / "binding-manifest.json", manifest)
    print(json.dumps({"state": "NOT RUN", "bound_cases": len(cases), "native_inputs": len(index),
        "verified_inputs": len({(r["path"], r["sha256"]) for b in bindings for r in b["source_inputs"].values()}),
        "measured_executions": 0, "passes": 0, "bundle": str(output / "benchmark-bundle.json")}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
