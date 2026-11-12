"""Offline checkpoint failures must preserve the ledger, view and historical evidence."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmark_case_state import request_key
from benchmark_checkpoint import checkpoint, join_indexed
from benchmark_results import generate


class BenchmarkCheckpointTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name).resolve()
        self.output = self.root / "benchmarks/index.html"
        self.ledger_path = self.root / "ledger.json"
        self.case = {"id": "exact", "source": "a", "transport": "classic", "duration_s": 10,
            "profile": {"codec": "encoder", "width": 320, "height": 180, "fps": 15,
                        "bitrate_kbps": 150, "rate_control": "cbr", "playback": "balanced"},
            "receivers": [{"phone": "b", "parent": "a"}]}
        self.save("tools/benchmark_devices.json", {"devices": {
            "a": {"serial": "phone-a", "node_prefix": "a" * 12},
            "b": {"serial": "phone-b", "node_prefix": "b" * 12}}})
        self.save("inventory/suite.json", {"cases": [self.case]})
        self.inventory = {"required_phones": ["a", "b"], "selected_phones": ["a", "b"],
                          "capability_provenance": {"a": {"sha256": "c" * 64}}, "remaining": ["Physical adapters unmeasured"]}
        self.row = {"case_id": "exact", "request_key": request_key(self.case), "phones": ["a", "b"],
                    "state": "NOT RUN", "freshness": "NOT RUN"}
        self.state = {"schema": "benchmark-case-state-v1", "candidate_apk_sha256": "a" * 64,
                      "cases": [self.row], "summary": {"NOT RUN": 1}}
        pointers = {"inventory": self.save("inventory/inventory.json", self.inventory),
                    "bundle": self.save("inventory/benchmark-bundle.json", {"suites": [{"path": str(self.root / "inventory/suite.json") }]}),
                    "input_requirements": self.save("inventory/input_requirements.json", {"state": "NOT RUN"}),
                    "state": self.save("state/old.json", self.state)}
        self.report = {"schema": "benchmark-suite-v1", "generated_at": "2026-10-04T00:00:00Z", "state_counts": {"FAIL": 1},
            "results": [{"case_id": "old", "status": "FAIL", "families": {"camera": {"status": "UNMEASURED"}}}]}
        report = self.save("old/suite-result.json", self.report)
        report["state_counts"] = {"FAIL": 1}
        (self.root / "old/report.html").write_text("Retained original report", encoding="utf-8")
        self.ledger = {"current_inventory": {**pointers, "states": {"NOT RUN": 1}, "case_count": 1,
            "required_phones": ["a", "b"], "candidate_apk_sha256": "a" * 64},
            "unified_reports": [report], "executions": [], "phase_delivery_fidelity": [{"status": "FAIL"}],
            "historical_capability_bases": {"original": {"sha256": "d" * 64}}, "capability_bases": {"a": {"sha256": "c" * 64}}}
        self.save("ledger.json", self.ledger)
        generate(self.ledger_path, self.output, self.root)

    def save(self, relative, value):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding="utf-8")
        return {"path": relative, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def update(self, state=None, **kwargs):
        self.save("state/new.json", state if state is not None else self.state)
        return checkpoint(self.ledger_path, self.output, self.root / "state/new.json", root=self.root, **kwargs)

    def assert_blocked(self, state, message):
        before = (self.ledger_path.read_bytes(), self.output.read_bytes())
        with self.assertRaisesRegex(ValueError, message):
            self.update(state)
        self.assertEqual(before, (self.ledger_path.read_bytes(), self.output.read_bytes()))

    def test_check_is_read_only_and_never_uses_phone_preflight(self):
        before = {path: path.read_bytes() for path in self.root.rglob("*") if path.is_file()}
        with patch("run_benchmark_suite.preflight", side_effect=AssertionError("Phones paused")):
            result = checkpoint(self.ledger_path, self.output, root=self.root)
        self.assertEqual("CHECKED", result["action"])
        self.assertEqual({"NOT RUN": 1}, result["states"])
        for path, raw in before.items():
            self.assertEqual(raw, path.read_bytes())

    def test_refresh_reuses_state_path_and_writes_current_selection(self):
        before_files = set(self.root.rglob("*"))
        with patch("run_benchmark_suite.preflight", side_effect=AssertionError("Phones paused")):
            result = self.update(replace_state=True, phones=set())
        after = json.loads(self.ledger_path.read_text())
        self.assertEqual("UPDATED", result["action"])
        self.assertEqual("state/old.json", after["current_inventory"]["state"]["path"])
        self.assertEqual([], json.loads((self.root / "state/remaining.json").read_text())["case_ids"])
        self.assertNotIn("checkpoint.tmp", self.output.read_text())
        self.assertEqual({self.root / "state/new.json", self.root / "state/remaining.json"}, set(self.root.rglob("*")) - before_files)

    def test_refresh_rejects_invalid_state_before_replacing_old_state(self):
        before = (self.root / "state/old.json").read_bytes()
        self.save("state/new.json", {**self.state, "cases": [], "summary": {}})
        with self.assertRaisesRegex(ValueError, "every exact"):
            checkpoint(self.ledger_path, self.output, self.root / "state/new.json", root=self.root,
                       replace_state=True, phones=set())
        self.assertEqual(before, (self.root / "state/old.json").read_bytes())

    def test_indexed_join_preserves_prior_reports_and_impacts_without_phone_activity(self):
        change = {"id": "shared", "at": "2026-10-04T00:00:00Z", "reason": "Shared-stage correction"}
        blocked = {"case_id": "exact", "scenario": self.case, "status": "BLOCKED", "origin": "LIVE_ATTEMPT"}
        self.save("old/suite-result.json", {**self.report, "results": [blocked], "state_counts": {"BLOCKED": 1}})
        self.save("state/old.json", {**self.state, "changes": [change]})
        with patch("run_benchmark_suite.preflight", side_effect=AssertionError("Phones paused")):
            joined = join_indexed(self.ledger, set(), root=self.root)
        self.assertEqual("BLOCKED", joined["cases"][0]["state"])
        self.assertEqual(blocked, joined["cases"][0]["last_result"])
        self.assertEqual([change], joined["changes"])
        self.assertFalse(joined["cases"][0]["availability"]["all_participants_available"])
        self.save("changes.json", {"changes": [{**change, "reason": "Different definition"}]})
        with self.assertRaisesRegex(ValueError, "existing impact"):
            join_indexed(self.ledger, set(), changes=[self.root / "changes.json"], root=self.root)

    def test_update_appends_reports_preserves_history_and_links_canonical_ledger(self):
        self.save("new/suite-result.json", self.report)
        (self.root / "new/report.html").write_text("New report", encoding="utf-8")
        result = self.update(reports=[self.root / "new/suite-result.json", self.root / "old/suite-result.json"])
        after = json.loads(self.ledger_path.read_text())
        self.assertEqual("UPDATED", result["action"])
        self.assertEqual(2, len(after["unified_reports"]))
        self.assertEqual(self.ledger["phase_delivery_fidelity"], after["phase_delivery_fidelity"])
        self.assertEqual(self.ledger["historical_capability_bases"], after["historical_capability_bases"])
        self.assertEqual("state/new.json", after["current_inventory"]["state"]["path"])
        page = self.output.read_text()
        self.assertIn('../ledger.json', page)
        self.assertNotIn(".checkpoint.tmp", page)

    def test_changed_earlier_report_blocks_update_without_discarding_it(self):
        (self.root / "old/suite-result.json").write_text("{}")
        self.assert_blocked(self.state, "Indexed evidence changed")

    def test_explicit_join_cannot_discard_earlier_change_impacts(self):
        change = {"id": "shared-stage", "reason": "Earlier stage change requires preservation", "at": "2026-10-04T00:00:00Z"}
        prior = {**self.state, "cases": [{**self.row, "changes": [{**change, "needs_retest": False}]}]}
        self.ledger["current_inventory"]["state"] = self.save("state/old.json", prior)
        self.save("ledger.json", self.ledger)
        generate(self.ledger_path, self.output, self.root)
        self.assert_blocked(self.state, "discard prior change impacts")
        self.assertEqual("UPDATED", self.update({**self.state, "changes": [change]})["action"])

    def test_missing_duplicate_or_changed_request_cannot_shrink_scope(self):
        for rows in ([], [self.row, self.row], [{**self.row, "request_key": "b" * 64}]):
            self.assert_blocked({**self.state, "cases": rows}, "every exact|request differs")

    def test_invented_or_stale_pass_cannot_replace_unrun_case(self):
        for freshness in ("CURRENT", "NEEDS RETEST"):
            row = {**self.row, "state": "PASS", "freshness": freshness, "last_result": {"status": "PASS"}}
            self.assert_blocked({**self.state, "cases": [row], "summary": {"PASS": 1}}, "Unverified|Stale")

    def test_wrong_summary_or_unknown_state_blocks_both_outputs(self):
        self.assert_blocked({**self.state, "summary": {"PASS": 1}}, "summary disagrees")
        self.assert_blocked({**self.state, "cases": [{**self.row, "state": "DONE"}]}, "Unknown")

    def test_new_report_missing_view_does_not_checkpoint_ledger_first(self):
        self.save("new/suite-result.json", self.report)
        before = (self.ledger_path.read_bytes(), self.output.read_bytes())
        with self.assertRaisesRegex(ValueError, "Indexed evidence is missing"):
            self.update(reports=[self.root / "new/suite-result.json"])
        self.assertEqual(before, (self.ledger_path.read_bytes(), self.output.read_bytes()))

    def test_new_report_requires_completed_artifact_purpose_review(self):
        path = self.root / "new/suite-result.json"
        before = (self.ledger_path.read_bytes(), self.output.read_bytes())
        for review in ({"status": "PENDING", "files": {}},
                       {"status": "COMPLETE", "files": {"setup.log": {"state": "PENDING"}}},
                       {"status": "COMPLETE", "files": {"setup.log": {"state": "CONSUMED"}}},
                       {"status": "COMPLETE", "files": {}, "pending_images": ["preview.png"]}):
            self.save("new/suite-result.json", {**self.report, "artifact_review": review})
            (path.parent / "report.html").write_text("Report", encoding="utf-8")
            with self.subTest(review=review), self.assertRaisesRegex(ValueError, "purpose review"):
                self.update(reports=[path])
            self.assertEqual(before, (self.ledger_path.read_bytes(), self.output.read_bytes()))
        self.save("new/suite-result.json", {**self.report, "artifact_review": {"status": "COMPLETE", "files": {}}})
        self.assertEqual("UPDATED", self.update(reports=[path])["action"])

    def test_report_with_cases_pending_analysis_cannot_be_checkpointed(self):
        path = self.root / "new/suite-result.json"
        (path.parent).mkdir(parents=True, exist_ok=True)
        (path.parent / "report.html").write_text("Report", encoding="utf-8")
        before = (self.ledger_path.read_bytes(), self.output.read_bytes())
        for status, extra in (("PENDING_ANALYSIS", {}), ("ANALYZING", {"deferred_analysis": {}})):
            pending = {**self.report, "results": [{"case_id": "exact", "status": status, **extra}]}
            self.save("new/suite-result.json", pending)
            with self.subTest(status=status), self.assertRaisesRegex(ValueError, "finish-analysis"):
                self.update(reports=[path])
            self.assertEqual(before, (self.ledger_path.read_bytes(), self.output.read_bytes()))

    def test_completed_review_cannot_hide_an_unregistered_later_output(self):
        path = self.root / "new/suite-result.json"
        self.save("new/suite-result.json", {**self.report, "artifact_review": {"status": "COMPLETE", "files": {}}})
        (path.parent / "report.html").write_text("Report", encoding="utf-8")
        (path.parent / "later-clock.json").write_text("Unregistered observation", encoding="utf-8")
        before = (self.ledger_path.read_bytes(), self.output.read_bytes())
        with self.assertRaisesRegex(ValueError, "Unregistered output"):
            self.update(reports=[path])
        self.assertEqual(before, (self.ledger_path.read_bytes(), self.output.read_bytes()))

    def test_omitting_prior_reports_cannot_erase_last_measurement(self):
        prior = {**self.state, "cases": [{**self.row, "state": "FAIL", "freshness": "CURRENT",
                 "last_result": {"case_id": "exact", "status": "FAIL", "manifest": "retained-original"}}], "summary": {"FAIL": 1}}
        self.ledger["current_inventory"]["state"] = self.save("state/old.json", prior)
        self.ledger["current_inventory"]["states"] = {"FAIL": 1}
        self.save("ledger.json", self.ledger)
        generate(self.ledger_path, self.output, self.root)
        self.assert_blocked(self.state, "discard a last measured result")

    def test_blocked_retry_cannot_discard_prior_measured_result_or_build(self):
        measurement = {"case_id": "exact", "status": "FAIL", "manifest": "original", "host_end_ns": 100}
        prior_row = {**self.row, "state": "FAIL", "freshness": "CURRENT", "last_result": measurement,
                     "last_measured_result": measurement, "last_verified_apk": {"a": "a" * 64, "b": "a" * 64},
                     "last_verified_apk_evidence": {"manifest": "original"}}
        self.ledger["current_inventory"]["state"] = self.save("state/old.json",
            {**self.state, "cases": [prior_row], "summary": {"FAIL": 1}})
        self.ledger["current_inventory"]["states"] = {"FAIL": 1}
        self.save("ledger.json", self.ledger)
        generate(self.ledger_path, self.output, self.root)
        blocked = {**prior_row, "state": "BLOCKED", "last_result": {"status": "BLOCKED", "host_end_ns": 200}}
        for missing in ("last_measured_result", "last_verified_apk", "last_verified_apk_evidence"):
            with self.subTest(missing=missing):
                self.assert_blocked({**self.state, "cases": [{**blocked, missing: None}], "summary": {"BLOCKED": 1}},
                                    "discard a last measured result")
        self.assertEqual("UPDATED", self.update({**self.state, "cases": [blocked], "summary": {"BLOCKED": 1}})["action"])


    def test_subset_inventory_or_unreviewed_removed_request_cannot_replace_complete_scope(self):
        for selected in (["a"], ["a", "b"]):
            self.save("subset/inventory.json", {**self.inventory, "selected_phones": selected})
            new_case = {**self.case, "id": "changed", "duration_s": 11}
            self.save("subset/suite.json", {"cases": [new_case]})
            self.save("subset/benchmark-bundle.json", {"suites": [{"path": str(self.root / "subset/suite.json")}]})
            self.save("subset/input_requirements.json", {})
            new_state = {**self.state, "cases": [{**self.row, "case_id": "changed", "request_key": request_key(new_case)}]}
            before = self.ledger_path.read_bytes()
            with self.assertRaisesRegex(ValueError, "complete required|Removed exact requests"):
                self.update(new_state, inventory_dir=self.root / "subset")
            self.assertEqual(before, self.ledger_path.read_bytes())

    def save_moved_inventory(self, **inventory):
        self.save("moved/inventory.json", {**self.inventory, **inventory})
        self.save("moved/suite.json", {"cases": [self.case]})
        self.save("moved/benchmark-bundle.json", {"suites": [{"path": str(self.root / "moved/suite.json")}]})
        self.save("moved/input_requirements.json", {})

    def test_a_required_phone_may_move_to_extension_phones(self):
        self.save_moved_inventory(required_phones=["a"], extension_phones=["b"])
        result = self.update(inventory_dir=self.root / "moved")
        self.assertEqual("UPDATED", result["action"])
        self.assertEqual(["a"], json.loads(self.ledger_path.read_text())["current_inventory"]["required_phones"])

    def test_a_required_phone_cannot_leave_the_inventory(self):
        self.save_moved_inventory(required_phones=["a"])
        with self.assertRaisesRegex(ValueError, "cannot disappear"):
            self.update(inventory_dir=self.root / "moved")


if __name__ == "__main__":
    unittest.main()
