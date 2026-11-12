"""Track exact benchmark requests and declared retest obligations without operating phones."""
import argparse
import collections
import copy
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import acceptance_profiles as acceptance
from benchmark_layout import resolve
from benchmark_state_io import STATE_V2, iter_state_rows as iter_state_rows_file, write_state


ROOT = Path(__file__).resolve().parents[1]
FAMILIES = acceptance.FAMILIES_V3  # default requirement; KNOWN_FAMILIES also admits device-run-v4-user families
KNOWN_FAMILIES = acceptance.KNOWN_FAMILIES
METADATA = {"id", "state", "family", "load_tier", "environment", "planned_environment",
            "capability_basis", "eligibility", "provenance", "expected_apk_sha256"}
REAL_ORIGINS = {"LIVE_ATTEMPT", "RECORDED_EXECUTION_ANALYSIS"}
RESULT_STATES = {"NOT RUN", "PASS", "FAIL", "BLOCKED", "INTERRUPTED", "UNMEASURED",
                 "RUNNING", "ANALYZING", "PENDING_ANALYSIS"}
NO_CASES = "none"  # `affects: "none"`: the change touches no measured behaviour (see _validate_changes)
PRIORITY = {"FAIL": 0, "NEEDS RETEST": 0, "NOT RUN": 1, "UNMEASURED": 1, "INTERRUPTED": 1}


def request_key(case):
    value = {key: item for key, item in case.items() if key not in METADATA}
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                     allow_nan=False).encode()).hexdigest()


def _hash(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-fA-F]{64}", value) is not None


def _time(value):
    if value is None:
        return None
    instant = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if instant.tzinfo is None:
        raise ValueError("Change/report times must include a timezone")
    return int(instant.timestamp() * 1_000_000_000)


def _manifest(result, job):
    """Return verified identity evidence, independently of its measured verdict."""
    if result.get("origin") != "RECORDED_EXECUTION_ANALYSIS":
        return None, "No recorded execution analysis"
    if result.get("case_id") != job["case"]["id"] or not isinstance(result.get("scenario"), dict) or request_key(
            result["scenario"]) != request_key(job["case"]):
        return None, "Result does not match the exact case/request"
    if not isinstance(result.get("manifest"), str) or not _hash(result.get("manifest_sha256")):
        return None, "Missing manifest or valid manifest digest"
    path = resolve(result["manifest"], root=ROOT)
    try:
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != result["manifest_sha256"].lower():
            return None, "Manifest digest mismatch"
        manifest = json.loads(data.decode("utf-8-sig"))
    except (OSError, ValueError) as error:
        return None, f"Manifest cannot be verified: {error}"
    if not isinstance(manifest, dict) or not isinstance(manifest.get("scenario"), dict):
        return None, "Manifest has no request object"
    scenario = manifest["scenario"]
    if scenario.get("id") != job["case"]["id"] or request_key(scenario) != request_key(job["case"]):
        return None, "Manifest does not match the exact case/request"
    phones = set(job["phones"])
    devices = manifest.get("devices", {})
    if not isinstance(devices, dict) or set(devices) != phones or any(not isinstance(item, dict) or
            not _hash(item.get("apk_sha256")) or not item.get("serial") or
            not isinstance(item.get("node_prefix"), str) or not re.fullmatch(r"[0-9a-fA-F]{12}", item["node_prefix"])
            for item in devices.values()):
        return None, "Missing participant identities or verified APK hashes"
    current = job.get("devices")
    if current is not None:
        if not isinstance(current, dict) or set(current) != phones or any(not isinstance(item, dict) or
                not item.get("serial") or not isinstance(item.get("node_prefix"), str) or
                not re.fullmatch(r"[0-9a-fA-F]{12}", item["node_prefix"]) for item in current.values()):
            return None, "Incomplete current participant identities"
        if any(devices[phone]["serial"] != current[phone]["serial"] or
               devices[phone]["node_prefix"].lower() != current[phone]["node_prefix"].lower() for phone in phones):
            return None, "Recorded participant identity differs from the current registry"
    if len({item["apk_sha256"].lower() for item in devices.values()}) != 1:
        return None, "Participants did not use one frozen APK"
    if scenario.get("expected_apk_sha256") and any(
            item["apk_sha256"].lower() != scenario["expected_apk_sha256"].lower() for item in devices.values()):
        return None, "Participant APK differs from the recorded execution guard"
    logs = manifest.get("logs", [])
    if not isinstance(logs, list):
        return None, "Missing participant log PID scope"
    logged = {item.get("device") for item in logs if isinstance(item, dict) and
              isinstance(item.get("pid"), int) and not isinstance(item["pid"], bool) and item["pid"] > 0}
    if not phones <= logged:
        return None, "Missing participant log PID scope"
    return manifest, None


def _pass_error(result, job, manifest, error):
    if error:
        return error
    if result.get("status") != "PASS" or result.get("pipeline_status") != "PASS" or manifest.get("runner_error"):
        return "Execution/pipeline is not PASS"
    required = job.get("required_families", FAMILIES)
    if not required or set(required) - set(KNOWN_FAMILIES):
        return "Required measurement scopes are missing or unknown"
    for name in required:
        family = result.get("families", {}).get(name, {})
        if not isinstance(family, dict):
            return f"Required {name} measurements are invalid"
        measurements = family.get("measurements", [])
        if family.get("status") != "PASS" or not isinstance(measurements, list) or not measurements or any(
                not isinstance(item, dict) or item.get("status") not in {"PASS", "DIAGNOSTIC"} for item in measurements):
            return f"Required {name} measurements are not all PASS"
    return None


def _input_file_error(requested, recorded, label):
    if not isinstance(requested, dict) or not _hash(requested.get("sha256")) or not isinstance(
            requested.get("path"), str) or not requested["path"]:
        return f"{label} has no declared path/digest"
    if not isinstance(recorded, dict) or not _hash(recorded.get("sha256")) or not isinstance(
            recorded.get("path"), str) or not recorded["path"]:
        return f"{label} has no verified recorded path/digest"
    path = resolve(requested["path"], root=ROOT)
    original = resolve(recorded["path"], root=ROOT)
    if path != original or requested["sha256"].lower() != recorded["sha256"].lower():
        return f"{label} differs from the recorded path/digest"
    try:
        with path.open("rb") as handle:
            current = hashlib.file_digest(handle, "sha256").hexdigest()
    except OSError as error:
        return f"{label} cannot be verified: {error}"
    return f"{label} changed at its recorded path" if current != recorded["sha256"].lower() else None


def _input_error(case, manifest):
    if case.get("replay_file"):
        reference = manifest.get("replay_fixture")
        requested = {"path": case["replay_file"], "sha256": case.get("replay_sha256") or
                     (reference.get("sha256") if isinstance(reference, dict) else None)}
        error = _input_file_error(requested, reference, "Finite replay input")
        if error:
            return error
        if (reference.get("format") != "IVF" or reference.get("codec") not in {"VP90", "AV01"} or
                reference.get("loops") is not False or any(reference.get(key) != case["profile"][key]
                    for key in ("width", "height", "fps")) or any(type(reference.get(key)) is not int or
                    reference[key] <= 0 for key in ("frames", "bytes", "rate", "scale"))):
            return "Finite replay input has no matching recorded native format/count"
        staged = reference.get("staging")
        if (not isinstance(staged, dict) or staged.get("status") != "VERIFIED" or
                staged.get("device") != case.get("source") or not staged.get("remote") or
                not _hash(staged.get("sha256")) or staged["sha256"].lower() != reference["sha256"].lower()):
            return "Finite replay input has no verified source upload digest"
    elif "replay_sha256" in case:
        return "Replay input hash guard has no requested file"
    files = case.get("raw_fixture_files", {})
    guards = case.get("raw_fixture_sha256s")
    metadatas = case.get("raw_fixture_metadatas", {})
    references = manifest.get("source_fixtures", {})
    if case.get("raw_fixture_file") is not None:
        sender = case.get("source")
        files = {sender: case["raw_fixture_file"]}
        guards = {sender: case["raw_fixture_sha256"]} if "raw_fixture_sha256" in case else None
        metadatas = {sender: case.get("raw_fixture_metadata")}
        references = {sender: manifest.get("source_fixture")}
    if not isinstance(files, dict) or files and (not isinstance(references, dict) or set(references) != set(files)):
        return "Raw input references do not match the requested senders"
    if not files and guards:
        return "Raw input hash guard has no requested file"
    if guards is not None and (not isinstance(guards, dict) or set(guards) != set(files)):
        return "Raw input guards do not match the requested senders"
    if not isinstance(metadatas, dict) or set(metadatas) - set(files):
        return "Raw input metadata does not match the requested senders"
    for sender, source in files.items():
        reference = references.get(sender)
        if not isinstance(reference, dict) or not _hash(reference.get("sha256")):
            return f"Raw input for {sender} has no verified recorded digest"
        error = _input_file_error({"path": source, "sha256": guards[sender] if guards is not None else
                                   reference["sha256"]}, reference, f"Raw input for {sender}")
        if error:
            return error
        metadata = metadatas.get(sender)
        if metadata is not None:
            if not isinstance(metadata, dict) or metadata != reference.get("declared_metadata"):
                return f"Raw input for {sender} differs from its recorded native declarations"
            sidecars = metadata.get("sidecars", {})
            if not isinstance(sidecars, dict):
                return f"Raw input for {sender} has invalid sidecar declarations"
            for name, entry in sidecars.items():
                error = _input_file_error(entry, entry, f"Raw {name} sidecar for {sender}")
                if error:
                    return error
    for name, plural in (("reference_audio", "reference_audios"), ("media_impairment", "media_impairments")):
        requested, recorded = case.get(plural, {}), manifest.get(plural, {})
        if name in case:
            requested = {case.get("source"): case[name]}
            recorded = {case.get("source"): manifest.get(name)}
        if not isinstance(requested, dict) or requested and (not isinstance(recorded, dict) or set(recorded) != set(requested)):
            return f"{name} references do not match the requested senders"
        for sender, entry in requested.items():
            error = _input_file_error(entry, recorded.get(sender), f"{name} for {sender}")
            if error:
                return error
    return None


def verify_result(result, job, expected_apk=None):
    manifest, error = _manifest(result, job)
    if _pass_error(result, job, manifest, error) is not None or _input_error(job["case"], manifest) is not None:
        return False
    return expected_apk is None or _hash(expected_apk) and all(
        item["apk_sha256"].lower() == expected_apk.lower() for item in manifest["devices"].values())


def _validate_changes(changes):
    seen = set()
    for change in changes:
        if not change.get("id") or change["id"] in seen or not str(change.get("reason", "")).strip():
            raise ValueError("Each change needs a unique ID and an evidence-based reason")
        seen.add(change["id"])
        selectors = change.get("affects", {})
        if selectors == NO_CASES:
            # Declared to change no measured behaviour (documentation, tests, logging that only reports): it is
            # recorded, but retests nothing. The reason must say why, so a behaviour change cannot hide here.
            if not str(change.get("affects_none_reason", "")).strip() or change.get("acceptance_profile") is not None:
                raise ValueError("A change declared to affect no case needs an explicit affects_none_reason and no acceptance profile")
            _time(change.get("at"))
            continue
        if not isinstance(selectors, dict) or set(selectors) - {"transports", "phones", "encoders", "carriers"}:
            raise ValueError("Unknown change impact selector")
        profile = change.get("acceptance_profile")
        if profile is not None:
            acceptance.families_for(profile)  # Unknown profile versions raise.
        if any(not isinstance(values, list) or not values or any(
                not isinstance(value, str) or not value for value in values) for values in selectors.values()):
            raise ValueError("Change selectors must contain nonempty lists of exact values")
        _time(change.get("at"))


def _affected(job, change):
    case, selectors = job["case"], change.get("affects", {})
    if selectors == NO_CASES:
        return False
    encoders = {profile["codec"] for profile in case.get("profiles", {"source": case.get("profile", {})}).values()
                if "codec" in profile}
    values = {"transports": {case.get("transport")}, "carriers": {case.get("carrier", "bluetooth")}, "phones": set(job["phones"]), "encoders": encoders}
    return all(values[name].intersection(selected) for name, selected in selectors.items())


class ChangeSets:
    """Interns each row's (affecting changes, needs_retest) outcome so the state stores it once, not per row."""

    def __init__(self):
        self.table, self._index = [], {}

    def intern(self, applied):
        key = (tuple(identity for identity, _ in applied), tuple(identity for identity, needs in applied if needs))
        if key not in self._index:
            self._index[key] = len(self.table)
            self.table.append({"changes": list(key[0]), "needs_retest": list(key[1])})
        return self._index[key]


STATE_SCOPE = "Exact-case state only; old APK results retain their build labels. Availability is separate from applicability."


def build_state(jobs, reports, changes=(), expected_apk=None, row_hook=None):
    """Keep latest real exact-case results; only independently verified PASS completes a case.

    The result is a benchmark-case-state-v2 state in memory: change definitions once in ``changes``, the distinct
    per-row outcomes in ``change_sets`` and ``change_set`` on every row (see benchmark_state_io).
    """
    sets = ChangeSets()
    rows = list(iter_state_rows(jobs, reports, changes, expected_apk, sets, row_hook))
    return {"schema": STATE_V2, "candidate_apk_sha256": expected_apk.lower() if expected_apk is not None else None,
            "cases": rows, "changes": copy.deepcopy(list(changes)), "change_sets": sets.table,
            "summary": {state: sum(row["state"] == state for row in rows)
                        for state in sorted({row["state"] for row in rows})},
            "scope": STATE_SCOPE}


def stream_state(path, jobs, reports, changes=(), expected_apk=None, row_hook=None):
    """Build the same state as build_state but write it row by row; returns its summary."""
    sets, counts = ChangeSets(), collections.Counter()

    def rows():
        for row in iter_state_rows(jobs, reports, changes, expected_apk, sets, row_hook):
            counts[row["state"]] += 1
            yield row

    header = {"schema": STATE_V2, "candidate_apk_sha256": expected_apk.lower() if expected_apk is not None else None,
              "changes": copy.deepcopy(list(changes))}
    partial = Path(str(path) + ".partial")  # a failed build never leaves a plausible state file behind
    try:
        write_state(partial, header, rows(), lambda: {"change_sets": sets.table,
                                                      "summary": {state: counts[state] for state in sorted(counts)},
                                                      "scope": STATE_SCOPE})
        partial.replace(path)
    finally:
        partial.unlink(missing_ok=True)
    return {state: counts[state] for state in sorted(counts)}


def iter_state_rows(jobs, reports, changes, expected_apk, sets, row_hook=None):
    if expected_apk is not None and not _hash(expected_apk):
        raise ValueError("Candidate APK must be a valid SHA256")
    expected_apk = expected_apk.lower() if expected_apk is not None else None
    _validate_changes(changes)
    records = {}
    rejected = {}
    versions = {}  # id(result) -> thresholds version it was scored with; recorded bytes stay untouched
    for index, report in enumerate(reports):
        report_time = _time(report.get("generated_at"))
        for result in report.get("results", []):
            versions[id(result)] = result.get("thresholds_version") or (report.get("thresholds") or {}).get("version")
            if result.get("status") not in RESULT_STATES:
                raise ValueError(f"Unknown result state: {result.get('status')}")
            if not isinstance(result.get("scenario"), dict):
                continue
            key = (result.get("case_id"), request_key(result["scenario"]))
            if result["status"] in {"NOT RUN", "RUNNING", "ANALYZING", "PENDING_ANALYSIS"}:
                continue
            if result.get("origin") not in REAL_ORIGINS:
                if result["status"] == "PASS":
                    rejected[key] = "Unverified/generated PASS is not execution evidence"
                continue
            ended = result.get("host_end_ns")
            if not isinstance(ended, int) or isinstance(ended, bool) or ended <= 0:
                ended = None
            records.setdefault(key, []).append((ended or report_time or 0, index, ended, result))
    rows, ids = [], set()
    for job in jobs:
        case = job["case"]
        if case["id"] in ids:
            raise ValueError(f"Duplicate case ID: {case['id']}")
        ids.add(case["id"])
        key = (case["id"], request_key(case))
        options = records.get(key, [])
        last = max(options, key=lambda item: item[:2]) if options else None
        result = last[3] if last else None
        row = {"case_id": case["id"], "request_key": key[1], "phones": list(job["phones"]),
               "mode": job["mode"], "execution": job["execution"], "suite": job.get("suite"),
               "suite_sha256": job.get("suite_sha256"), "required_families": list(job.get("required_families", FAMILIES)),
               "applicability": copy.deepcopy(case.get("eligibility", "Not established by this state inventory")),
               "availability": "UNMEASURED", "state": result["status"] if result else "NOT RUN",
               "last_result": copy.deepcopy(result), "last_verified_apk": None,
               "last_verified_apk_evidence": None, "last_measured_result": None,
               "candidate_apk_sha256": expected_apk, "freshness": "NOT RUN"}
        measured, measured_manifest, measured_end = None, None, None
        if result:
            manifest, error = _manifest(result, job)
            for prior in sorted(options, key=lambda item: item[:2], reverse=True):
                verified, _ = (manifest, error) if prior is last else _manifest(prior[3], job)
                if verified:
                    measured = prior
                    measured_manifest = verified
                    row["last_measured_result"] = copy.deepcopy(prior[3])
                    row["last_measured_thresholds_version"] = versions.get(id(prior[3]))
                    row["freshness"] = "CURRENT"
                    row["last_verified_apk"] = {phone: item["apk_sha256"].lower()
                                                for phone, item in verified["devices"].items()}
                    row["last_verified_apk_evidence"] = {name: prior[3].get(name)
                                                         for name in ("manifest", "manifest_sha256", "host_end_ns")}
                    break
            if measured is not None:
                measured_end = measured[2]
                if measured_end is None:
                    # An offline rescore can omit timing; only its verified exact execution can supply it.
                    timed = {}
                    measured_path = resolve(measured[3]["manifest"], root=ROOT)
                    for candidate in options:
                        other = candidate[3]
                        if candidate[2] is None or not _hash(other.get("manifest_sha256")) or other[
                                "manifest_sha256"].lower() != measured[3]["manifest_sha256"].lower():
                            continue
                        verified, _ = _manifest(other, job)
                        if verified and resolve(other["manifest"], root=ROOT) == measured_path:
                            timed[candidate[2]] = other
                    if len(timed) == 1:
                        measured_end, source = next(iter(timed.items()))
                        evidence = row["last_verified_apk_evidence"]
                        evidence["host_end_ns"] = measured_end
                        evidence["execution_time_source"] = {name: source.get(name)
                                                              for name in ("manifest", "manifest_sha256", "host_end_ns")}
            if result["status"] == "PASS":
                reason = _pass_error(result, job, manifest, error)
                if reason:
                    row.update(state="UNMEASURED", reason=reason)
                elif manifest is not None:
                    # A retry must not launder a failure: an earlier verified FAIL of this exact request
                    # on the same build keeps the case failing until a change or new build addresses it.
                    passed_build = {item["apk_sha256"].lower() for item in manifest["devices"].values()}
                    failures = []
                    for prior in options:
                        if prior is last or prior[3].get("status") != "FAIL":
                            continue
                        verified, _ = _manifest(prior[3], job)
                        if verified and {item["apk_sha256"].lower() for item in verified["devices"].values()} == passed_build:
                            failures.append(prior[3].get("run_id") or prior[3].get("manifest"))
                    if failures:
                        row.update(state="FAIL", same_build_failures=failures,
                                   reason="PASS followed a verified FAIL of this exact request on the same build")
        elif key in rejected:
            row.update(state="UNMEASURED", reason=rejected[key])
        if expected_apk is not None and measured is not None and any(
                value != expected_apk for value in row["last_verified_apk"].values()):
            row.update(freshness="NEEDS RETEST", reason="Measured APK differs from the requested candidate")
            if row["state"] != "BLOCKED":
                row["state"] = "NEEDS RETEST"
        if measured_manifest is not None:
            input_error = _input_error(case, measured_manifest)
            if input_error:
                row.update(freshness="NEEDS RETEST", reason=input_error)
                if row["state"] != "BLOCKED":
                    row["state"] = "NEEDS RETEST"
        applied = []
        for change in changes:
            if not _affected(job, change):
                continue
            at = _time(change.get("at"))
            needs = measured is not None and (at is None or measured_end is None or measured_end <= at)
            profile = change.get("acceptance_profile")
            if profile is not None:
                # An acceptance-profile change is resolved by a result scored under that profile (an offline
                # re-analysis of the same exact manifest is enough); execution time does not matter.
                needs = measured is not None and row.get("last_measured_thresholds_version") != profile
            applied.append((change["id"], needs))
            if needs:
                row.update(freshness="NEEDS RETEST", reason="A declared change affects this earlier measured execution"
                           if profile is None else f"Last result was scored under "
                           f"{row.get('last_measured_thresholds_version') or 'an unrecorded profile'}; "
                           f"re-analyze the same exact manifest under {profile} or rerun")
                if row["state"] != "BLOCKED":
                    row["state"] = "NEEDS RETEST"
        row["change_set"] = sets.intern(applied)
        if row_hook is not None:
            row_hook(row)
        yield row


def select_remaining(state, available_phones):
    """Select unfinished cases whose entire participant group is available; preflight still applies."""
    available = set(available_phones)
    rows = []
    for row in state["cases"]:  # a single pass, so a streamed row iterator works too
        if row.get("state") not in {*PRIORITY, "PASS", "BLOCKED"}:
            raise ValueError(f"Unknown or unfinished checkpoint state for {row.get('case_id')}: {row.get('state')}; regenerate case state")
        if row["state"] in PRIORITY and set(row["phones"]) <= available:
            rows.append(row)
    return sorted(rows, key=lambda row: PRIORITY[row["state"]])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("state",))
    parser.add_argument("--suite", type=Path, required=True)
    parser.add_argument("--reports", type=Path, action="append", default=[])
    parser.add_argument("--changes", type=Path, action="append", default=[])
    parser.add_argument("--apk-sha256", help="Candidate APK SHA256; measured older builds require retesting")
    parser.add_argument("--devices", type=Path, default=ROOT / "tools/benchmark_devices.json")
    parser.add_argument("--phones", required=True, help="Available, exclusively owned aliases; availability never deletes scope")
    parser.add_argument("--output", type=Path, required=True, help="Owned catalog directory; initial state/remaining files must be new")
    args = parser.parse_args(argv)
    if args.apk_sha256 is not None and not _hash(args.apk_sha256):
        parser.error("Candidate APK must be a valid SHA256")
    from run_benchmark_suite import load_jobs, read, resolve, write
    registry = read(resolve(args.devices))["devices"]
    phones = set(filter(None, (name.strip() for name in args.phones.split(","))))
    if phones - set(registry):
        parser.error(f"Unknown available aliases: {sorted(phones - set(registry))}")
    jobs = load_jobs(args.suite, registry)
    changes = [change for path in args.changes for change in read(resolve(path))["changes"]]
    _validate_changes(changes)  # before any output is created

    def availability(row):
        row["availability"] = {"all_participants_available": set(row["phones"]) <= phones,
                               "missing_phones": sorted(set(row["phones"]) - phones),
                               "preflight": "UNMEASURED"}

    from benchmark_layout import output_path
    output = output_path(args.output, "catalog", root=ROOT)
    if any((output / name).exists() for name in ("state.json", "remaining.json")):
        raise FileExistsError("State outputs already exist; refresh through the indexed checkpoint or prepare in OS temporary scratch")
    output.mkdir(parents=True, exist_ok=True)
    summary = stream_state(output / "state.json", jobs, [read(resolve(path)) for path in args.reports], changes,
                           expected_apk=args.apk_sha256, row_hook=availability)
    ready = select_remaining({"cases": iter_state_rows_file(output / "state.json")}, phones)
    blocked = [row["case_id"] for row in iter_state_rows_file(output / "state.json") if row["state"] == "BLOCKED"]
    write(output / "remaining.json", {"state": "NOT RUN", "case_ids": [row["case_id"] for row in ready],
                                     "available_owned_phones": sorted(phones),
                                     "blocked_cases": blocked,
                                     "reason": "Selection is not execution; BLOCKED cases require a newly proved prerequisite."})
    state = {"summary": summary}
    print(json.dumps({"summary": state["summary"], "selected": len(ready), "output": str(output)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
