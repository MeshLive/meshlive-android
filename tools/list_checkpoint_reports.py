"""List the finalized run reports a ledger checkpoint can still join, and say why the others cannot.

A report is joinable when it is not yet indexed by the ledger, has no RUNNING/ANALYZING/PENDING_ANALYSIS case and
its output purpose review is COMPLETE. Everything else is listed with its reason (a running or unfinalized output
belongs to its lane or to `finalize-stopped`). Offline and read-only.
"""
import argparse
import json
from pathlib import Path

from benchmark_layout import LEDGER, RUNS, ROOT, resolve

TRANSIENT = {"RUNNING", "ANALYZING", "PENDING_ANALYSIS"}


def classify(path):
    """(None, "") when joinable, else (reason keyword, explanation)."""
    try:
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        return "UNREADABLE", str(error)
    if payload.get("schema") != "benchmark-suite-v1":
        return "UNSUPPORTED", "not a benchmark-suite-v1 result"
    live = sorted(TRANSIENT & set(payload.get("state_counts", {})))
    if live:
        return "RUNNING", "cases " + "/".join(live) + "; finalize with finalize-stopped once its lane has ended"
    review = payload.get("artifact_review")
    if review is None:
        return "UNFINALIZED", "no output purpose review yet"
    if review.get("status") != "COMPLETE":
        return "REVIEW_PENDING", "purpose review " + str(review.get("status"))
    if any(item.get("cleanup_status") == "UNCONFIRMED" for item in payload.get("results", [])):
        return "CLEANUP_UNCONFIRMED", "phone cleanup unconfirmed; resolve its quarantined lease first"
    return None, ""


def candidates(patterns, runs=RUNS, ledger=LEDGER, root=ROOT):
    indexed = {resolve(item["path"], root=root) for item in json.loads(Path(ledger).read_text(encoding="utf-8")).get(
        "unified_reports", [])}
    seen = set()
    for pattern in patterns:
        for path in sorted(Path(runs).glob(pattern + "/suite-result.json")):
            if path in seen:
                continue
            seen.add(path)
            if resolve(path, root=root) in indexed:
                continue
            yield (path, *classify(path))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--glob", action="append", default=[], help="Run directory pattern under benchmarks/runs (repeatable)")
    parser.add_argument("--skipped", action="store_true", help="List the reports that cannot be joined, with reasons, instead")
    args = parser.parse_args(argv)
    rows = list(candidates(args.glob or ["*"]))
    for path, reason, detail in rows:
        if args.skipped and reason:
            print(f"{reason}\t{path}\t{detail}")
        elif not args.skipped and not reason:
            print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
