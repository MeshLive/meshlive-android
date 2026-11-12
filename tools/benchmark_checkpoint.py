"""Check or checkpoint exact offline state and its derived index; never contact phones.

State files are streamed (benchmark_state_io): a full inventory has hundreds of thousands of rows, so no step
holds the previous state, the new state and a serialized copy in memory at once.
"""
import argparse
import collections
import copy
import json
from pathlib import Path
import shutil
import tempfile

from benchmark_results import LEDGER, ROOT, render
from benchmark_layout import resolve
from benchmark_state_io import file_sha256, iter_state_rows, read_members, state_header

HISTORY_FIELDS = ("last_result", "last_measured_result", "last_verified_apk", "last_verified_apk_evidence")


def read(path, root=ROOT):
    return json.loads(resolve(path, root=root).read_text(encoding="utf-8-sig"))


def reference(path, root):
    path = resolve(path, root=root)
    return {"path": path.relative_to(root).as_posix(), "sha256": file_sha256(path)}


def _definitions(changes):
    result = {}
    for change in changes:
        change = {key: value for key, value in change.items() if key != "needs_retest"}
        if change["id"] in result and result[change["id"]] != change:
            raise ValueError(f"Changed definition for existing impact: {change['id']}")
        result[change["id"]] = change
    return result


def state_impacts(path):
    """Change definitions by ID from a state file: its change table, else the rows' embedded changes."""
    header = state_header(path)
    if "changes" in header:
        return _definitions(header["changes"])
    return _definitions(item for row in iter_state_rows(path) for item in row.get("changes", []))


def validate_state(ledger, root, state_path=None):
    """Validate the exact state against the bundle, streaming rows and jobs; returns (inventory, header, counts, ids)."""
    from run_benchmark_suite import iter_jobs, request_key, verify_result
    from benchmark_case_state import PRIORITY
    current = ledger["current_inventory"]
    inventory = read_members(root / current["inventory"]["path"], keys=(
        "required_phones", "selected_phones", "extension_phones", "capability_provenance",
        "retired_requirements", "remaining"))
    state_path = Path(state_path) if state_path is not None else resolve(current["state"]["path"], root=root)
    header = state_header(state_path)
    if not set(inventory["required_phones"]) <= set(inventory["selected_phones"]):
        raise ValueError("Shared checkpoints require the complete required phone inventory")
    registry = read(root / "tools/benchmark_devices.json", root)["devices"]
    index, counts, duplicate, unknown = {}, collections.Counter(), False, None
    for row in iter_state_rows(state_path):
        counts[row["state"]] += 1
        if row["case_id"] in index:
            duplicate = True
        # Only a PASS needs its full row later (verification); every other row needs a few small facts.
        index[row["case_id"]] = (row["request_key"], frozenset(row["phones"]), row["state"], row.get("freshness"),
                                 row if row["state"] == "PASS" else None)
        if unknown is None and row.get("state") not in {*PRIORITY, "PASS", "BLOCKED"}:
            unknown = row
    mismatch, problem, seen = duplicate, None, 0
    for job in iter_jobs(root / current["bundle"]["path"], registry):
        seen += 1
        entry = index.get(job["case"]["id"])
        if entry is None:
            mismatch = True
            continue
        key, phones, state, freshness, full = entry
        if problem is None:
            if key != request_key(job["case"]) or phones != set(job["phones"]):
                problem = f"Current state request differs: {job['case']['id']}"
            elif state == "PASS" and freshness == "NEEDS RETEST":
                problem = f"Stale result cannot be a current PASS: {job['case']['id']}"
            elif state == "PASS" and not verify_result(full.get("last_result") or {}, job,
                                                        expected_apk=header.get("candidate_apk_sha256")):
                problem = f"Unverified current PASS: {job['case']['id']}"
    if mismatch or seen != len(index):
        raise ValueError("Current state must contain every exact generated case once")
    if unknown is not None:  # An empty selection never means completion; unknown states are never skipped.
        raise ValueError(f"Unknown or unfinished checkpoint state for {unknown.get('case_id')}: "
                         f"{unknown.get('state')}; regenerate case state")
    if problem is not None:
        raise ValueError(problem)
    counts = dict(counts)
    if counts != header["summary"]:
        raise ValueError("Exact state summary disagrees with its cases")
    return inventory, header, counts, set(index)


def previous_history(state_path):
    """Case IDs and the measured-history facts of a state, without keeping its rows."""
    ids, history = set(), {}
    for row in iter_state_rows(state_path):
        ids.add(row["case_id"])
        present = {field for field in HISTORY_FIELDS if row.get(field)}
        if present:
            history[row["case_id"]] = (row.get("request_key"), present)
    return ids, history


def freshness_counts(state_path):
    return dict(collections.Counter(row.get("freshness", "NOT RUN") for row in iter_state_rows(state_path)))


def checkpoint(ledger_path, output, state_path=None, inventory_dir=None, reports=(), root=ROOT,
               replace_state=False, phones=None):
    root, output = Path(root).resolve(), Path(output).resolve()
    ledger_path = resolve(ledger_path, root=root)
    original = ledger_path.read_bytes()
    ledger = json.loads(original)
    render(ledger_path, output, root, ledger)  # Reject changed earlier evidence before updating anything.
    candidate = copy.deepcopy(ledger)
    current = candidate["current_inventory"]
    state_target = resolve(current["state"]["path"], root=root)
    previous_sha = file_sha256(state_target)
    prepared = None  # a new state file that is not yet at the ledger's state pointer
    if state_path is None and (inventory_dir is not None or reports):
        raise ValueError("An update requires a freshly joined exact state")
    if state_path is not None:
        directory = resolve(inventory_dir, root=root) if inventory_dir else resolve(current["inventory"]["path"], root=root).parent
        for key, name in (("inventory", "inventory.json"), ("bundle", "benchmark-bundle.json"),
                          ("input_requirements", "input_requirements.json")):
            current[key] = reference(directory / name, root)
        if replace_state:
            prepared = resolve(state_path, root=root)
            current["state"] = {"path": state_target.relative_to(root).as_posix(), "sha256": file_sha256(prepared)}
        else:
            current["state"] = reference(state_path, root)
    inventory, header, counts, new_ids = validate_state(candidate, root, prepared)
    if state_path is not None:
        new_file = prepared or resolve(current["state"]["path"], root=root)
        old_ids, history = previous_history(state_target)
        for row in iter_state_rows(new_file):
            previous = history.get(row["case_id"])
            if previous is not None and previous[0] == row["request_key"] and any(
                    not row.get(field) for field in previous[1]):
                raise ValueError("Checkpoint would discard a last measured result; join prior reports as well")
        candidate_impacts = state_impacts(new_file)
        if any(candidate_impacts.get(key) != value for key, value in state_impacts(state_target).items()):
            raise ValueError("Checkpoint would discard prior change impacts; join the accumulated changes as well")
    else:
        old_ids = new_ids
    # A required phone may move to the inventory's `extension_phones` (still compiled, no longer needed by a run);
    # it cannot leave the inventory.
    if not set(ledger["current_inventory"]["required_phones"]) <= (
            set(inventory["required_phones"]) | set(inventory.get("extension_phones", []))):
        raise ValueError("Required phones cannot disappear from a shared checkpoint")
    if current["bundle"] != ledger["current_inventory"]["bundle"]:
        removed = old_ids - new_ids
        retired = {row["id"] for row in inventory.get("retired_requirements", []) if row.get("reason", "").strip()}
        if removed - retired:
            raise ValueError("Removed exact requests require explicit retired_requirements with reasons")
    new_file = prepared or resolve(current["state"]["path"], root=root)
    current.update(required_phones=inventory["required_phones"], selected_phones=inventory["selected_phones"],
                   case_count=len(new_ids), states=counts, candidate_apk_sha256=header.get("candidate_apk_sha256"),
                   freshness=freshness_counts(new_file))
    if state_path is not None:
        candidate.setdefault("historical_capability_bases", copy.deepcopy(candidate.get("capability_bases", {})))
        candidate["capability_bases"] = inventory["capability_provenance"]
        candidate["schema_version"] = 2
        indexed = {item["path"] for item in candidate.get("unified_reports", [])}
        for path in reports:
            pointer = reference(path, root)
            report = read(path, root)
            if any(item.get("status") == "PENDING_ANALYSIS" or item.get("status") == "ANALYZING"
                   and "deferred_analysis" in item for item in report.get("results", [])):
                raise ValueError("Report has cases pending analysis; run finish-analysis before checkpointing")
            review = report.get("artifact_review")
            if review is not None and (review.get("status") != "COMPLETE" or review.get("pending_images") or any(
                    row.get("state") in {"PENDING", "CONSUMED"} for row in review.get("files", {}).values())):
                raise ValueError("Complete the new report's artifact purpose review before checkpointing")
            if review is not None:
                from run_benchmark_suite import validate_artifact_inventory
                validate_artifact_inventory(resolve(path, root=root).parent, review)
            if pointer["path"] not in indexed:
                candidate.setdefault("unified_reports", []).append({**pointer, "state_counts": report["state_counts"],
                    "scope": "Recorded analysis; only exact case/request matches may update current state"})
                indexed.add(pointer["path"])
    page, result = render(ledger_path, output, root, candidate, state_path=prepared)
    if state_path is None:
        return {"action": "CHECKED", **result, "phone_operations": 0}
    # Both complete documents are prepared and verified before either canonical file changes.
    staged = []
    try:
        output.parent.mkdir(parents=True, exist_ok=True)
        documents = [(ledger_path, (json.dumps(candidate, indent=2, allow_nan=False) + "\n").encode("utf-8")),
                     (output, page.encode("utf-8"))]
        if prepared is not None and phones is not None:
            from benchmark_case_state import select_remaining
            ready = select_remaining({"cases": iter_state_rows(prepared)}, phones)
            blocked = [row["case_id"] for row in iter_state_rows(prepared) if row["state"] == "BLOCKED"]
            documents.insert(0, (state_target.with_name("remaining.json"), json.dumps({
                "state": "NOT RUN", "case_ids": [row["case_id"] for row in ready],
                "available_owned_phones": sorted(phones), "blocked_cases": blocked,
                "reason": "Selection is not execution; BLOCKED cases require a newly proved prerequisite."
            }, indent=2, allow_nan=False).encode("utf-8")))
        if prepared is not None:
            temporary = state_target.with_name(state_target.name + ".checkpoint.tmp")
            staged.append((temporary, state_target))
            shutil.copyfile(prepared, temporary)
            if file_sha256(temporary) != current["state"]["sha256"]:
                raise ValueError("Staged state differs from the verified candidate")
        for target, raw in documents:
            temporary = target.with_name(target.name + ".checkpoint.tmp")
            with temporary.open("xb") as handle:
                staged.append((temporary, target))
                handle.write(raw)
        if ledger_path.read_bytes() != original:
            raise ValueError("Ledger changed during preparation; retry under exclusive root ownership")
        if file_sha256(state_target) != previous_sha:
            raise ValueError("State changed during preparation; retry under exclusive root ownership")
        for temporary, target in staged:
            temporary.replace(target)
    finally:
        for temporary, _ in staged:
            temporary.unlink(missing_ok=True)
    return {"action": "UPDATED", **result, "phone_operations": 0,
            "ledger": reference(ledger_path, root), "index": reference(output, root)}


def _join_inputs(ledger, phones, reports, changes, expected_apk, root, inventory_dir):
    """Jobs, lazily read reports, accumulated changes and the availability hook for an indexed refresh."""
    from run_benchmark_suite import iter_jobs
    root = Path(root).resolve()
    current = ledger["current_inventory"]
    registry = read(root / "tools/benchmark_devices.json", root)["devices"]
    phones = set(phones)
    if phones - set(registry):
        raise ValueError(f"Unknown available aliases: {sorted(phones - set(registry))}")
    accumulated = state_impacts(resolve(current["state"]["path"], root=root))
    for change in [item for path in changes for item in read(path, root)["changes"]]:
        change = {key: value for key, value in change.items() if key != "needs_retest"}
        if change["id"] in accumulated and accumulated[change["id"]] != change:
            raise ValueError(f"Changed definition for existing impact: {change['id']}")
        accumulated[change["id"]] = change
    paths = {resolve(item["path"], root=root) for item in ledger.get("unified_reports", [])}
    paths.update(resolve(path, root=root) for path in reports)
    bundle = resolve(inventory_dir, root=root) / "benchmark-bundle.json" if inventory_dir else resolve(current["bundle"]["path"], root=root)

    def availability(row):
        row["availability"] = {"all_participants_available": set(row["phones"]) <= phones,
                               "missing_phones": sorted(set(row["phones"]) - phones), "preflight": "UNMEASURED"}

    # Reports are read one at a time while the state is built; only recorded executions stay resident.
    return (iter_jobs(bundle, registry), (read(path, root) for path in sorted(paths)), list(accumulated.values()),
            expected_apk or current["candidate_apk_sha256"], availability)


def join_indexed(ledger, phones, reports=(), changes=(), expected_apk=None, root=ROOT, inventory_dir=None):
    """Reuse all indexed results and impacts; availability never shrinks the inventory. Returns the v2 state."""
    from benchmark_case_state import build_state
    jobs, lazy_reports, accumulated, apk, availability = _join_inputs(
        ledger, phones, reports, changes, expected_apk, root, inventory_dir)
    return build_state(jobs, lazy_reports, accumulated, expected_apk=apk, row_hook=availability)


def join_indexed_to_file(ledger, phones, path, reports=(), changes=(), expected_apk=None, root=ROOT, inventory_dir=None):
    """The same join, written row by row to ``path`` (no full state in memory)."""
    from benchmark_case_state import stream_state
    jobs, lazy_reports, accumulated, apk, availability = _join_inputs(
        ledger, phones, reports, changes, expected_apk, root, inventory_dir)
    return stream_state(path, jobs, lazy_reports, accumulated, expected_apk=apk, row_hook=availability)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("check", "update"))
    parser.add_argument("--state", type=Path, help="Explicit freshly joined complete state.json")
    parser.add_argument("--phones", help="Refresh indexed state in place for these owned aliases; empty means none assigned")
    parser.add_argument("--apk-sha256", help="Candidate for indexed refresh; defaults to the indexed candidate")
    parser.add_argument("--changes", type=Path, action="append", default=[], help="Additional change impacts (repeatable)")
    parser.add_argument("--inventory", type=Path, help="New complete inventory; defaults to the indexed inventory")
    parser.add_argument("--report", action="append", type=Path, default=[], help="Append finalized suite-result.json (repeatable)")
    parser.add_argument("--scratch", type=Path, help="Directory for the prepared state file (default: the OS temporary directory)")
    args = parser.parse_args(argv)
    if args.action == "update" and (args.state is None) == (args.phones is None):
        parser.error("Update requires either --phones for indexed refresh or --state for an explicit join")
    if args.state is not None and (args.changes or args.apk_sha256):
        parser.error("Explicit state must already contain the candidate and change impacts")
    if args.action == "check" and (args.state or args.inventory or args.report or args.phones is not None or args.changes or args.apk_sha256):
        parser.error("Check reads indexed evidence only")
    try:
        if args.phones is None:
            result = checkpoint(LEDGER, ROOT / "benchmarks/index.html", args.state, args.inventory, args.report,
                                replace_state=args.state is not None)
        else:
            checkpoint(LEDGER, ROOT / "benchmarks/index.html")
            phones = set(filter(None, (name.strip() for name in args.phones.split(","))))
            with tempfile.TemporaryDirectory(prefix="bluetooth-state-", dir=args.scratch) as scratch:
                path = Path(scratch) / "state.json"
                join_indexed_to_file(read(LEDGER), phones, path, args.report, args.changes, args.apk_sha256,
                                     inventory_dir=args.inventory)
                result = checkpoint(LEDGER, ROOT / "benchmarks/index.html", path, args.inventory, args.report,
                                    replace_state=True, phones=phones)
    except (ValueError, KeyError, OSError) as error:
        parser.exit(2, f"Benchmark checkpoint blocked: {error}\n")
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
