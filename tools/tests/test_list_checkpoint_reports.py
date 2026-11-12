"""Only finalized, unindexed reports are offered to a checkpoint; the rest are named with their reason."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from list_checkpoint_reports import candidates, classify


def report(states, review="COMPLETE", **extra):
    payload = {"schema": "benchmark-suite-v1", "state_counts": states,
               "results": [{"case_id": "x", "status": next(iter(states)), **extra}]}
    if review is not None:
        payload["artifact_review"] = {"status": review, "files": {}}
    return payload


class ListCheckpointReportsTest(unittest.TestCase):
    def test_joinable_reports_exclude_indexed_running_unfinalized_pending_and_unconfirmed_ones(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            runs = root / "benchmarks/runs"
            outputs = {"done": report({"FAIL": 1}), "indexed": report({"FAIL": 1}), "running": report({"RUNNING": 1, "NOT RUN": 2}),
                       "bare": report({"FAIL": 1}, review=None), "pending": report({"FAIL": 1}, review="PENDING"),
                       "quarantined": report({"FAIL": 1}, cleanup_status="UNCONFIRMED")}
            for name, payload in outputs.items():
                (runs / name).mkdir(parents=True)
                (runs / name / "suite-result.json").write_text(json.dumps(payload), encoding="utf-8")
            (runs / "broken").mkdir()
            (runs / "broken/suite-result.json").write_text("{", encoding="utf-8")
            (runs / "empty").mkdir()  # no suite-result.json at all: not a candidate
            ledger = root / "ledger.json"
            ledger.write_text(json.dumps({"unified_reports": [{"path": "benchmarks/runs/indexed/suite-result.json"}]}), encoding="utf-8")
            found = {path.parent.name: reason for path, reason, _ in candidates(["*"], runs, ledger, root)}
            self.assertEqual({"done": None, "running": "RUNNING", "bare": "UNFINALIZED", "pending": "REVIEW_PENDING",
                              "quarantined": "CLEANUP_UNCONFIRMED", "broken": "UNREADABLE"}, found)

    def test_classify_names_the_transient_state(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "suite-result.json"
            path.write_text(json.dumps(report({"ANALYZING": 1})), encoding="utf-8")
            self.assertEqual("RUNNING", classify(path)[0])


if __name__ == "__main__":
    unittest.main()
