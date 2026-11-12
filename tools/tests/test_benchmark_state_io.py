"""Streaming state files: the compact v2 form means exactly what the v1 form meant."""
import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmark_case_state import build_state, request_key, stream_state
from benchmark_checkpoint import checkpoint
from benchmark_results import generate
from benchmark_state_io import (STATE_V1, STATE_V2, ChangeTable, iter_array, iter_state_rows, main, migrate_v1_to_v2,
                                read_members, state_header, verify_migration)

CHANGES = [{"id": "gatt-fix", "at": "2026-11-09T00:00:00Z", "reason": "GATT stage fix", "affects": {"transports": ["gatt"]}},
           {"id": "all-fix", "at": "2026-11-10T00:00:00Z", "reason": "Shared stage fix"}]


def v1_row(name, needs=(), applied=("gatt-fix", "all-fix"), state="NOT RUN"):
    definitions = {item["id"]: item for item in CHANGES}
    return {"case_id": name, "request_key": hashlib.sha256(name.encode()).hexdigest(), "phones": ["a", "b"],
            "state": state, "freshness": "NOT RUN", "last_result": None,
            "applicability": 'text with "quotes", braces {} [] and \\ backslash',
            "changes": [{**definitions[i], "needs_retest": i in needs} for i in applied]}


class StreamingReaderTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.folder = Path(self.scratch.name)

    def save(self, name, text):
        path = self.folder / name
        path.write_text(text, encoding="utf-8")
        return path

    def test_reads_members_and_elements_from_compact_and_indented_documents_alike(self):
        document = {"schema": STATE_V1, "cases": [{"case_id": "a", "text": '}]"{[ \\"', "nested": {"x": [1, {"y": []}]}},
                                                     {"case_id": "b", "n": -1.5e3, "none": None, "flag": True}],
                    "summary": {"NOT RUN": 2}, "empty": [], "emptyobject": {}}
        for index, text in enumerate((json.dumps(document), json.dumps(document, indent=2),
                                      json.dumps(document, ensure_ascii=False))):
            path = self.save(f"d{index}.json", text)
            self.assertEqual(document["cases"], list(iter_array(path, "cases")))
            self.assertEqual({"summary": document["summary"], "empty": [], "emptyobject": {}},
                             read_members(path, keys=("summary", "empty", "emptyobject")))
            self.assertEqual({k: v for k, v in document.items() if k != "cases"}, read_members(path, skip=("cases",)))

    def test_malformed_or_missing_content_is_rejected_not_skipped(self):
        for text in ("", "[]", '{"cases": [1, 2', '{"cases": {"a": 1}}', '{"a" 1}'):
            path = self.save("bad.json", text)
            with self.subTest(text=text), self.assertRaises((ValueError, IndexError)):
                list(iter_array(path, "cases"))
        with self.assertRaisesRegex(ValueError, "no top-level array"):
            list(iter_array(self.save("none.json", '{"other": []}'), "cases"))


class MigrationTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.folder = Path(self.scratch.name)
        self.rows = [v1_row("one"), v1_row("two", needs=("all-fix",), state="NEEDS RETEST"),
                     v1_row("three", applied=("all-fix",)), v1_row("four", applied=()), v1_row("five")]
        self.v1 = {"schema": STATE_V1, "candidate_apk_sha256": "a" * 64, "cases": self.rows, "changes": CHANGES,
                   "summary": {"NEEDS RETEST": 1, "NOT RUN": 4}, "scope": "Exact-case state only"}
        self.source = self.folder / "v1.json"
        self.source.write_text(json.dumps(self.v1, indent=2), encoding="utf-8")

    def test_migration_is_lossless_and_stores_each_change_and_outcome_once(self):
        target = self.folder / "v2.json"
        self.assertEqual(5, migrate_v1_to_v2(self.source, target))
        header = state_header(target)
        self.assertEqual(STATE_V2, header["schema"])
        self.assertEqual(CHANGES, header["changes"])
        self.assertEqual(4, len(header["change_sets"]))  # rows one and five share one outcome
        self.assertEqual(5, verify_migration(self.source, target))
        rows = list(iter_state_rows(target))
        self.assertTrue(all("changes" not in row for row in rows))
        table = ChangeTable(header)
        self.assertEqual([("gatt-fix", False), ("all-fix", True)],
                         [(c["id"], c["needs_retest"]) for c in table.expand(rows[1])])
        self.assertEqual([], table.expand(rows[3]))

    def test_verification_catches_a_changed_row_or_definition(self):
        target = self.folder / "v2.json"
        migrate_v1_to_v2(self.source, target)
        header = json.loads(target.read_text())
        tampered = copy.deepcopy(header)
        tampered["cases"][1]["state"] = "FAIL"
        (self.folder / "tampered.json").write_text(json.dumps(tampered))
        with self.assertRaisesRegex(ValueError, "row differs"):
            verify_migration(self.source, self.folder / "tampered.json")
        tampered = copy.deepcopy(header)
        tampered["change_sets"][1]["needs_retest"] = []
        (self.folder / "tampered2.json").write_text(json.dumps(tampered))
        with self.assertRaisesRegex(ValueError, "row differs"):
            verify_migration(self.source, self.folder / "tampered2.json")
        tampered = copy.deepcopy(header)
        tampered["changes"][0]["reason"] = "different"
        (self.folder / "tampered3.json").write_text(json.dumps(tampered))
        with self.assertRaisesRegex(ValueError, "differs in changes"):
            verify_migration(self.source, self.folder / "tampered3.json")

    def test_a_row_whose_embedded_change_disagrees_with_the_table_is_never_guessed(self):
        self.rows[0]["changes"][0]["reason"] = "A different definition"
        self.source.write_text(json.dumps(self.v1), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "differs from the state's change table"):
            migrate_v1_to_v2(self.source, self.folder / "v2.json")

    def test_cli_migrates_verifies_and_refuses_to_overwrite(self):
        target = self.folder / "cli.json"
        self.assertEqual(0, main(["migrate", "--source", str(self.source), "--target", str(target)]))
        self.assertEqual(0, main(["verify", "--source", str(self.source), "--target", str(target)]))
        with self.assertRaises(SystemExit):
            main(["migrate", "--source", str(self.source), "--target", str(target)])


def job(identifier, transport="gatt"):
    case = {"id": identifier, "source": "a", "transport": transport, "duration_s": 45,
            "profile": {"codec": "c2.exynos.vp9.encoder", "width": 720, "height": 480, "fps": 30,
                        "bitrate_kbps": 600, "rate_control": "cbr", "playback": "balanced"},
            "receivers": [{"phone": "b", "parent": "a"}]}
    return {"case": case, "phones": ["a", "b"], "mode": "broadcast", "execution": "concurrent",
            "required_families": ["picture"], "suite": "suite.json", "suite_sha256": "s" * 64}


class BuildAndStreamTest(unittest.TestCase):
    def test_streamed_state_equals_the_in_memory_state(self):
        jobs = [job("one"), job("two", "classic"), job("three")]
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.json"
            summary = stream_state(path, iter(jobs), iter([]), CHANGES, expected_apk="a" * 64)
            whole = build_state(jobs, [], CHANGES, expected_apk="a" * 64)
            self.assertEqual(whole["summary"], summary)
            self.assertEqual(whole, json.loads(path.read_text()))
            self.assertFalse(Path(str(path) + ".partial").exists())

    def test_a_failed_build_leaves_no_state_file(self):
        def broken():
            yield job("one")
            raise ValueError("Duplicate case ID")
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.json"
            with self.assertRaises(ValueError):
                stream_state(path, broken(), [], CHANGES)
            self.assertEqual([], list(Path(folder).iterdir()))

    def test_only_the_affected_cases_list_the_change_and_outcomes_are_shared(self):
        jobs = [job(f"g{i}") for i in range(50)] + [job(f"c{i}", "classic") for i in range(50)]
        whole = build_state(jobs, [], CHANGES)
        table = ChangeTable(whole)
        self.assertEqual(2, len(whole["change_sets"]))
        by_id = {row["case_id"]: [c["id"] for c in table.expand(row)] for row in whole["cases"]}
        self.assertEqual(["gatt-fix", "all-fix"], by_id["g0"])
        self.assertEqual(["all-fix"], by_id["c0"])


class CheckpointV2Test(unittest.TestCase):
    """A compact state replaces the ledger's old one, and vanished requests need a stated retirement."""

    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name).resolve()
        self.cases = [job("old-one")["case"], job("old-two", "classic")["case"]]
        self.save("tools/benchmark_devices.json", {"devices": {"a": {"serial": "phone-a", "node_prefix": "a" * 12},
                                                              "b": {"serial": "phone-b", "node_prefix": "b" * 12}}})
        self.inventory = {"required_phones": ["a", "b"], "selected_phones": ["a", "b"],
                          "capability_provenance": {"a": {"sha256": "c" * 64}}, "remaining": ["Physical adapters unmeasured"]}
        self.ledger = {"current_inventory": {"required_phones": ["a", "b"], "candidate_apk_sha256": "a" * 64},
                       "unified_reports": [], "executions": []}
        self.output = self.root / "benchmarks/index.html"
        self.ledger_path = self.root / "ledger.json"
        self.install("old", self.cases)

    def save(self, relative, value):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding="utf-8")
        return {"path": relative, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def rows(self, cases):
        return [{"case_id": c["id"], "request_key": request_key(c), "phones": ["a", "b"], "state": "NOT RUN",
                 "freshness": "NOT RUN", "last_result": None} for c in cases]

    def inventory_dir(self, name, cases, **inventory):
        self.save(f"{name}/suite.json", {"cases": cases})
        return {"inventory": self.save(f"{name}/inventory.json", {**self.inventory, **inventory}),
                "bundle": self.save(f"{name}/benchmark-bundle.json", {"suites": [{"path": str(self.root / name / "suite.json")}]}),
                "input_requirements": self.save(f"{name}/input_requirements.json", {})}

    def install(self, name, cases):
        rows = self.rows(cases)
        state = {"schema": STATE_V1, "candidate_apk_sha256": "a" * 64, "cases": [{**r, "changes": []} for r in rows],
                 "summary": {"NOT RUN": len(rows)}, "changes": []}
        pointers = self.inventory_dir(name, cases)
        pointers["state"] = self.save(f"{name}/state.json", state)
        self.ledger["current_inventory"] = {**self.ledger["current_inventory"], **pointers,
                                            "states": {"NOT RUN": len(rows)}, "case_count": len(rows)}
        self.save("ledger.json", self.ledger)
        generate(self.ledger_path, self.output, self.root)

    def write_new_state(self, cases):
        jobs = [{"case": c, "phones": ["a", "b"], "mode": "broadcast", "execution": "concurrent",
                 "required_families": [], "suite": None, "suite_sha256": None} for c in cases]
        path = self.root / "new/state.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        stream_state(path, iter(jobs), [], [], expected_apk="a" * 64)
        return path

    def test_a_v1_ledger_state_is_replaced_by_a_compact_state_that_the_index_reads(self):
        new_cases = [*self.cases, job("added")["case"]]
        self.inventory_dir("new", new_cases)
        result = checkpoint(self.ledger_path, self.output, self.write_new_state(new_cases), inventory_dir=self.root / "new",
                            root=self.root, replace_state=True, phones={"a", "b"})
        self.assertEqual({"NOT RUN": 3}, result["states"])
        stored = state_header(self.root / "old/state.json")
        self.assertEqual(STATE_V2, stored["schema"])
        self.assertEqual(3, len(list(iter_state_rows(self.root / "old/state.json"))))
        self.assertEqual(3, len(json.loads((self.root / "old/remaining.json").read_text())["case_ids"]))
        self.assertEqual({"NOT RUN": 3}, generate(self.ledger_path, self.output, self.root)["states"])

    def test_removed_requests_need_an_explicit_reason_and_keep_the_rest(self):
        survivors = self.cases[:1] + [job("replacement")["case"]]
        self.inventory_dir("new", survivors)
        state = self.write_new_state(survivors)
        before = self.ledger_path.read_bytes()
        with self.assertRaisesRegex(ValueError, "Removed exact requests"):
            checkpoint(self.ledger_path, self.output, state, inventory_dir=self.root / "new", root=self.root,
                       replace_state=True, phones=set())
        self.assertEqual(before, self.ledger_path.read_bytes())
        retired = [{"id": self.cases[1]["id"], "reason": ""}]
        self.inventory_dir("new", survivors, retired_requirements=retired)
        with self.assertRaisesRegex(ValueError, "Removed exact requests"):
            checkpoint(self.ledger_path, self.output, state, inventory_dir=self.root / "new", root=self.root,
                       replace_state=True, phones=set())
        retired = [{"id": self.cases[1]["id"], "reason": "Capability refresh changed the sampled profiles"}]
        self.inventory_dir("new", survivors, retired_requirements=retired)
        result = checkpoint(self.ledger_path, self.output, state, inventory_dir=self.root / "new", root=self.root,
                            replace_state=True, phones=set())
        self.assertEqual("UPDATED", result["action"])
        self.assertEqual({row["case_id"] for row in iter_state_rows(self.root / "old/state.json")},
                         {c["id"] for c in survivors})

    def test_a_retired_cases_result_stays_readable_in_the_reports_the_ledger_indexes(self):
        measured = {"case_id": self.cases[1]["id"], "scenario": self.cases[1], "status": "FAIL", "origin": "LIVE_ATTEMPT",
                    "families": {"camera": {"status": "UNMEASURED"}}}
        report = {"schema": "benchmark-suite-v1", "generated_at": "2026-10-04T00:00:00Z", "state_counts": {"FAIL": 1},
                  "results": [measured]}
        pointer = self.save("runs/old/suite-result.json", report)
        (self.root / "runs/old/report.html").write_text("Original report", encoding="utf-8")
        self.ledger["unified_reports"] = [{**pointer, "state_counts": {"FAIL": 1}}]
        self.save("ledger.json", self.ledger)
        generate(self.ledger_path, self.output, self.root)
        survivors = self.cases[:1]
        retired = [{"id": self.cases[1]["id"], "reason": "Requests no longer exist after a capability refresh"}]
        self.inventory_dir("new", survivors, retired_requirements=retired)
        checkpoint(self.ledger_path, self.output, self.write_new_state(survivors), inventory_dir=self.root / "new",
                   root=self.root, replace_state=True, phones=set())
        page = self.output.read_text(encoding="utf-8")
        self.assertIn(self.cases[1]["id"], page)  # the retired case's recorded FAIL is still in the index
        self.assertEqual(report, json.loads((self.root / "runs/old/suite-result.json").read_text()))


if __name__ == "__main__":
    unittest.main()
