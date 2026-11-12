"""Acceptance-profile freshness: a gate change retests by rescoring, and never re-labels old verdicts."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
SPEC = importlib.util.spec_from_file_location("benchmark_case_state", Path(__file__).parents[1] / "benchmark_case_state.py")
state = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(state)
from benchmark_state_io import ChangeTable  # noqa: E402
APK = "a" * 64
V3, V4 = "device-run-v3", "device-run-v4-user"
CHANGE = {"id": "acceptance-device-run-v4-user", "at": "2026-10-10T09:00:00+05:30", "files": ["tools/score_device_run.py"],
          "reason": "User-centred gates replace engineering fidelity/FPS-fraction gates", "acceptance_profile": V4}


def job(identifier, phones=("a", "b"), families=("smoothness", "transport")):
    case = {"id": identifier, "source": phones[0], "transport": "gatt", "duration_s": 45,
            "profile": {"codec": "c2.exynos.vp9.encoder", "width": 720, "height": 480, "fps": 30,
                        "bitrate_kbps": 1100, "rate_control": "cbr", "playback": "balanced"},
            "receivers": [{"phone": phones[1], "parent": phones[0]}], "actions": []}
    return {"case": case, "phones": list(phones), "mode": "broadcast", "execution": "concurrent",
            "required_families": list(families), "suite": "suite.json", "suite_sha256": "b" * 64}


class AcceptanceStateTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name).resolve()

    def execution(self, item, version=None, host_end_ns=1, extra=()):
        manifest = {"run_id": "offline-fixture-only", "scenario": {**copy.deepcopy(item["case"]), "expected_apk_sha256": APK},
                    "devices": {phone: {"serial": "fake-" + phone, "node_prefix": f"{index + 1:012x}", "apk_sha256": APK}
                                for index, phone in enumerate(item["phones"])},
                    "logs": [{"device": phone, "pid": 101 + index} for index, phone in enumerate(item["phones"])]}
        path = self.folder / (item["case"]["id"] + "-manifest.json")
        path.write_text(json.dumps(manifest), encoding="utf-8")
        result = {"case_id": item["case"]["id"], "scenario": copy.deepcopy(manifest["scenario"]),
                  "origin": "RECORDED_EXECUTION_ANALYSIS", "status": "PASS", "pipeline_status": "PASS",
                  "manifest": str(path), "manifest_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                  "families": {name: {"status": "PASS", "measurements": [{"status": "PASS", "name": "fixture"}, *extra]}
                               for name in item["required_families"]}}
        if host_end_ns is not None:
            result["host_end_ns"] = host_end_ns
        if version:
            result["thresholds_version"] = version
        return result

    @staticmethod
    def report(*results, version=None, generated_at="2026-10-03T20:00:00+05:30"):
        return {"generated_at": generated_at, "results": list(results), **({"thresholds": {"version": version}} if version else {})}

    def test_v3_pass_needs_retest_under_v4_but_keeps_its_result_and_verdict(self):
        item, untested = job("scored"), job("untested", ("c", "d"))
        original = self.execution(item)
        reports = [self.report(original, version=V3)]
        before = copy.deepcopy(reports)
        built = state.build_state([item, untested], reports, [CHANGE])
        rows = {row["case_id"]: row for row in built["cases"]}
        self.assertEqual("NEEDS RETEST", rows["scored"]["state"])
        self.assertEqual("PASS", rows["scored"]["last_result"]["status"])
        self.assertEqual(V3, rows["scored"]["last_measured_thresholds_version"])
        self.assertIn(V3, rows["scored"]["reason"])
        self.assertIn(V4, rows["scored"]["reason"])
        self.assertTrue(ChangeTable(built).expand(rows["scored"])[0]["needs_retest"])
        self.assertEqual("NOT RUN", rows["untested"]["state"])
        self.assertFalse(ChangeTable(built).expand(rows["untested"])[0]["needs_retest"])
        self.assertEqual(before, reports)

    def test_unrecorded_profile_is_conservative(self):
        item = job("legacy")
        built = state.build_state([item], [self.report(self.execution(item))], [CHANGE])["cases"][0]
        self.assertEqual("NEEDS RETEST", built["state"])
        self.assertIn("unrecorded", built["reason"])

    def test_offline_rescore_under_the_new_profile_clears_it_without_a_new_execution_time(self):
        item = job("rescored")
        original = self.execution(item, V3)
        rescored = self.execution(item, V4, host_end_ns=None)
        whole = state.build_state([item], [self.report(original, version=V3),
                                           self.report(rescored, version=V4, generated_at="2026-10-10T10:00:00+05:30")],
                                  [CHANGE])
        built = whole["cases"][0]
        self.assertEqual("PASS", built["state"])
        self.assertEqual("CURRENT", built["freshness"])
        self.assertEqual(V4, built["last_measured_thresholds_version"])
        self.assertFalse(ChangeTable(whole).expand(built)[0]["needs_retest"])

    def test_report_level_thresholds_version_applies_when_the_result_has_none(self):
        item = job("report-level")
        original = self.execution(item, host_end_ns=None)
        built = state.build_state([item], [self.report(original, version=V4)], [CHANGE])["cases"][0]
        self.assertEqual(V4, built["last_measured_thresholds_version"])
        self.assertEqual("PASS", built["state"])

    def test_unknown_profile_in_a_change_is_rejected(self):
        with self.assertRaises(ValueError):
            state.build_state([job("x")], [], [{**CHANGE, "acceptance_profile": "device-run-v9"}])

    def test_diagnostic_rows_do_not_block_a_pass_but_cannot_be_the_only_evidence(self):
        item = job("diag", families=("smoothness", "media_age"))
        diagnostic = {"status": "DIAGNOSTIC", "measured_status": "FAIL", "name": "visible_fps"}
        result = self.execution(item, V4, extra=(diagnostic,))
        self.assertTrue(state.verify_result(result, item))
        result["families"]["media_age"] = {"status": "UNMEASURED", "measurements": [diagnostic]}
        self.assertFalse(state.verify_result(result, item))

    def test_v4_families_are_known_but_arbitrary_names_are_not(self):
        self.assertTrue(set(("media_age", "task_success", "speech_comprehension")) <= set(state.KNOWN_FAMILIES))
        self.assertEqual(state.FAMILIES, state.KNOWN_FAMILIES[:len(state.FAMILIES)])
        item = job("bad", families=("smoothness", "nonsense"))
        self.assertFalse(state.verify_result(self.execution(item), item))


if __name__ == "__main__":
    unittest.main()
