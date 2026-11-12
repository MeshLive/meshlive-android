"""An output whose runner stopped before it could finalize is frozen honestly: interrupted, never scored or deleted."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("benchmark_suite", Path(__file__).parents[1] / "run_benchmark_suite.py")
suite = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(suite)


def result(identifier, state, **extra):
    return {"case_id": identifier, "origin": "LIVE_ATTEMPT", "status": state,
            "families": {name: dict(suite.UNKNOWN) for name in suite.FAMILIES}, **extra}


class FinalizeStoppedTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name).resolve() / "stopped-run"
        (self.output / "0001-case/physical").mkdir(parents=True)
        suite.write(self.output / "0001-case/physical/manifest.json", {"devices": {}})
        (self.output / "0001-case/preflight.json").write_text("{}", encoding="utf-8")
        self.results = [result("failed", "FAIL"),
                        result("running", "RUNNING", evidence_directory=str(self.output / "0001-case")),
                        result("waiting", "NOT RUN")]
        for name in ("plan.json", "selection.json", "execution.json"):
            suite.write(self.output / name, {"state": "NOT RUN"})
        suite.report(self.output, self.results)

    def test_unfinished_cases_become_interrupted_with_their_evidence_and_nothing_is_deleted(self):
        before = {path: path.read_bytes() for path in self.output.rglob("*") if path.is_file()
                  and path.name not in ("suite-result.json", "report.html", "coverage.json")}
        review = suite.finalize_stopped(self.output, "Lane ended; runner process killed before finalization")
        payload = suite.read(self.output / "suite-result.json")
        by_id = {item["case_id"]: item for item in payload["results"]}
        self.assertEqual({"failed": "FAIL", "running": "INTERRUPTED", "waiting": "NOT RUN"},
                         {name: item["status"] for name, item in by_id.items()})
        self.assertEqual({"FAIL": 1, "INTERRUPTED": 1, "NOT RUN": 1}, payload["state_counts"])
        self.assertEqual("Lane ended; runner process killed before finalization", by_id["running"]["reason"])
        manifest = self.output / "0001-case/physical/manifest.json"
        self.assertEqual(str(manifest), by_id["running"]["manifest"])
        self.assertEqual(suite.digest(manifest), by_id["running"]["manifest_sha256"])
        self.assertEqual(["running"], payload["stopped_before_finalization"]["cases"])
        self.assertIn(review["status"], {"COMPLETE", "PENDING"})
        self.assertEqual(review, payload["artifact_review"])
        self.assertTrue(all(path.read_bytes() == raw for path, raw in before.items()))  # no evidence rewritten or removed
        self.assertIn("INTERRUPTED", (self.output / "report.html").read_text(encoding="utf-8"))

    def test_it_refuses_an_unstated_reason_a_finalized_output_and_deferred_analysis(self):
        with self.assertRaisesRegex(ValueError, "State why"):
            suite.finalize_stopped(self.output, " ")
        suite.finalize_stopped(self.output, "Runner killed")
        with self.assertRaisesRegex(ValueError, "already finalized"):
            suite.finalize_stopped(self.output, "Runner killed")
        other = self.output.parent / "deferred-run"
        other.mkdir()
        suite.report(other, [result("deferred", suite.DEFERRED)])
        with self.assertRaisesRegex(ValueError, "pending analysis"):
            suite.finalize_stopped(other, "Runner killed")

    def test_an_output_with_only_terminal_results_is_frozen_without_changing_a_result(self):
        other = self.output.parent / "terminal-run"
        other.mkdir()
        suite.report(other, [result("blocked", "BLOCKED")])
        before = json.loads((other / "suite-result.json").read_text())["results"]
        suite.finalize_stopped(other, "Runner finished; only the purpose review was missing")
        payload = json.loads((other / "suite-result.json").read_text())
        self.assertEqual(before, payload["results"])
        self.assertNotIn("stopped_before_finalization", payload)
        self.assertIn("artifact_review", payload)

    def test_an_unconfirmed_cleanup_case_needs_a_named_verification_and_keeps_its_earlier_verdict(self):
        other = self.output.parent / "unconfirmed-run"
        other.mkdir()
        quarantined = result("phone-left", "FAIL", cleanup_status="UNCONFIRMED",
                             reason="Phone cleanup is unconfirmed; quarantined phones cannot be reassigned")
        suite.report(other, [quarantined, result("fine", "FAIL")])
        with self.assertRaisesRegex(ValueError, "Recorder cleanup is unconfirmed"):
            suite.finalize_stopped(other, "Runner finished")  # nothing says a person verified the phone
        with self.assertRaisesRegex(ValueError, "Not an unconfirmed-cleanup case"):
            suite.finalize_stopped(other, "Verified by reinstall", ["fine"])
        evidence = "phone left mid-run; app process verified stopped by the later reinstall at 2026-11-10 20:15:11"
        suite.finalize_stopped(other, evidence, ["phone-left"])
        payload = suite.read(other / "suite-result.json")
        by_id = {item["case_id"]: item for item in payload["results"]}
        self.assertEqual(("INTERRUPTED", evidence, "RESOLVED_BY_EVIDENCE"),
                         tuple(by_id["phone-left"][key] for key in ("status", "reason", "cleanup_status")))
        self.assertEqual("FAIL", by_id["phone-left"]["cleanup_resolution"]["previous_status"])
        self.assertEqual("UNCONFIRMED", by_id["phone-left"]["cleanup_resolution"]["previous_cleanup_status"])
        self.assertEqual("FAIL", by_id["fine"]["status"])
        self.assertEqual({"FAIL": 1, "INTERRUPTED": 1}, payload["state_counts"])


if __name__ == "__main__":
    unittest.main()
