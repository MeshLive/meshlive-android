"""Offline state/impact regressions; synthetic files are never phone benchmark evidence."""
import copy
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import struct
import tempfile
import unittest
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
SPEC = importlib.util.spec_from_file_location("benchmark_case_state", Path(__file__).parents[1] / "benchmark_case_state.py")
state = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(state)
from benchmark_state_io import ChangeTable, iter_state_rows, state_header  # noqa: E402
APK = "a" * 64


def changes_of(built_state, row):
    return ChangeTable(built_state).expand(row)


def job(identifier, phones=("a", "b"), transport="gatt"):
    case = {"id": identifier, "source": phones[0], "transport": transport, "duration_s": 45,
            "profile": {"codec": "c2.exynos.vp9.encoder", "width": 720, "height": 480, "fps": 30,
                        "bitrate_kbps": 1100, "rate_control": "cbr", "playback": "balanced"},
            "receivers": [{"phone": phones[1], "parent": phones[0]}], "actions": []}
    return {"case": case, "phones": list(phones), "mode": "broadcast", "execution": "concurrent",
            "required_families": ["picture", "transport"], "suite": "original-suite.json", "suite_sha256": "b" * 64}


def report(*results, generated_at="2026-10-03T20:00:00+05:30"):
    return {"generated_at": generated_at, "results": list(results)}


class CaseStateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.folder = Path(self.temporary.name).resolve()
        self.addCleanup(self.temporary.cleanup)

    def execution(self, item, status="PASS", host_end_ns=1):
        manifest = {"run_id": "offline-fixture-only", "scenario": {**copy.deepcopy(item["case"]),
                    "expected_apk_sha256": APK},
                    "devices": {phone: {"serial": "fake-" + phone, "node_prefix": f"{index + 1:012x}",
                                        "apk_sha256": APK} for index, phone in enumerate(item["phones"])},
                    "logs": [{"device": phone, "pid": 101 + index} for index, phone in enumerate(item["phones"])]}
        path = self.folder / (item["case"]["id"] + "-manifest.json")
        path.write_text(json.dumps(manifest), encoding="utf-8")
        return {"case_id": item["case"]["id"], "scenario": copy.deepcopy(manifest["scenario"]),
                "origin": "RECORDED_EXECUTION_ANALYSIS", "status": status, "pipeline_status": status,
                "host_end_ns": host_end_ns, "manifest": str(path),
                "manifest_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "families": {name: {"status": "PASS", "measurements": [{"status": "PASS", "name": "offline-fixture"}]}
                             for name in item["required_families"]}}

    def test_request_identity_ignores_labels_but_preserves_settings_actions_and_sources(self):
        original = job("one")["case"]
        labelled = {**original, **{key: "metadata" for key in state.METADATA}}
        self.assertEqual(state.request_key(original), state.request_key(labelled))
        for changed in ({**original, "profile": {**original["profile"], "bitrate_kbps": 1000}},
                        {**original, "actions": [{"at_s": 5, "action": "pause", "phone": "a"}]},
                        {**original, "source_kind": "raw_fixture_live_encoder", "source_fixture": {"sha256": "f" * 64}}):
            self.assertNotEqual(state.request_key(original), state.request_key(changed))

    def test_transport_change_retests_other_phone_sets_and_keeps_unrelated_old_build_pass(self):
        first, other, classic, untested = job("first"), job("other", ("c", "d")), job("classic", transport="classic"), job("untested", ("e", "f"))
        classic["case"]["expected_apk_sha256"] = "c" * 64
        inputs = [report(*(self.execution(item) for item in (first, other, classic)))]
        before = copy.deepcopy(inputs)
        change = {"id": "gatt-liveness", "reason": "Matched GATT kill/rejoin evidence affects all GATT paths", "files": ["Gatt.kt"],
                  "affects": {"transports": ["gatt"]}}
        built = state.build_state([first, other, classic, untested], inputs, [change])
        rows = {row["case_id"]: row for row in built["cases"]}
        self.assertEqual({"first": "NEEDS RETEST", "other": "NEEDS RETEST", "classic": "PASS", "untested": "NOT RUN"},
                         {name: row["state"] for name, row in rows.items()})
        self.assertEqual("PASS", rows["first"]["last_result"]["status"])
        self.assertEqual({"a": APK, "b": APK}, rows["classic"]["last_verified_apk"])
        self.assertFalse(changes_of(built, rows["untested"])[0]["needs_retest"])
        self.assertEqual(before, inputs)

    def test_case_pending_analysis_is_not_finalized_and_keeps_prior_result(self):
        item = job("standard")
        pending = {"case_id": "standard", "scenario": item["case"], "status": "PENDING_ANALYSIS",
                   "origin": "LIVE_ATTEMPT", "host_end_ns": 5, "families": {}}
        self.assertEqual("NOT RUN", state.build_state([item], [report(pending)])["cases"][0]["state"])
        earlier = self.execution(item, status="FAIL", host_end_ns=1)
        row = state.build_state([item], [report(earlier), report({**pending, "host_end_ns": 9})])["cases"][0]
        self.assertEqual(("FAIL", 1), (row["state"], row["last_result"]["host_end_ns"]))

    def test_new_case_id_and_changed_setting_do_not_inherit_pass(self):
        old = job("standard")
        result = self.execution(old)
        diagnostic = job("diagnostic")
        changed = job("standard")
        changed["case"]["profile"]["fps"] = 15
        self.assertEqual("NOT RUN", state.build_state([diagnostic], [report(result)])["cases"][0]["state"])
        self.assertEqual("NOT RUN", state.build_state([changed], [report(result)])["cases"][0]["state"])

    def test_generated_or_unverified_pass_cannot_complete_case(self):
        item = job("standard")
        valid = self.execution(item)
        self.assertTrue(state.verify_result(valid, item))
        invalid = [{**valid, "origin": "GENERATED"}, {**valid, "manifest_sha256": "f" * 64},
                   {**valid, "manifest": str(self.folder / "missing.json")}, {**valid, "pipeline_status": "FAIL"},
                   {**valid, "families": {**valid["families"], "picture": {"status": "UNMEASURED"}}},
                   {**valid, "families": {name: {"status": "PASS", "measurements": []} for name in item["required_families"]}}]
        for value in invalid:
            with self.subTest(origin=value["origin"], pipeline=value["pipeline_status"], path=value["manifest"]):
                self.assertFalse(state.verify_result(value, item))
                built = state.build_state([item], [report(value)])["cases"][0]
                self.assertEqual("UNMEASURED", built["state"])
                self.assertTrue(built["reason"])

    def test_manifest_pid_and_apk_proof_are_required(self):
        item = job("standard")
        result = self.execution(item)
        path = Path(result["manifest"])
        original = json.loads(path.read_text())
        for field in ("pid", "apk"):
            manifest = copy.deepcopy(original)
            if field == "pid":
                manifest["logs"] = manifest["logs"][:1]
            else:
                manifest["devices"]["b"]["apk_sha256"] = "c" * 64
            path.write_text(json.dumps(manifest))
            result["manifest_sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
            self.assertFalse(state.verify_result(result, item))

    def test_same_alias_replacement_identity_cannot_inherit_measured_pass(self):
        item = job("standard")
        measured = self.execution(item)
        manifest = json.loads(Path(measured["manifest"]).read_text())
        item["devices"] = {phone: {field: device[field] for field in ("serial", "node_prefix")}
                           for phone, device in manifest["devices"].items()}
        self.assertTrue(state.verify_result(measured, item))
        for field, changed in (("serial", "different-physical-phone"), ("node_prefix", "abcdef012345")):
            replacement = copy.deepcopy(item)
            replacement["devices"]["b"][field] = changed
            with self.subTest(field=field):
                self.assertFalse(state.verify_result(measured, replacement))
                row = state.build_state([replacement], [report(measured)])["cases"][0]
                self.assertEqual("UNMEASURED", row["state"])
                self.assertIsNone(row["last_verified_apk"])
        missing = copy.deepcopy(item)
        missing["devices"].pop("b")
        self.assertFalse(state.verify_result(measured, missing))

    def test_latest_real_failure_survives_newer_planned_placeholder(self):
        item = job("standard")
        passed = self.execution(item)
        failed = {"case_id": "standard", "scenario": item["case"], "status": "FAIL", "origin": "LIVE_ATTEMPT", "host_end_ns": 2}
        planned = {**failed, "status": "NOT RUN", "origin": "PLANNED", "host_end_ns": 3}
        built = state.build_state([item], [report(passed), report(failed), report(planned)])["cases"][0]
        self.assertEqual("FAIL", built["state"])
        self.assertEqual(failed, built["last_result"])

    def test_retry_pass_cannot_hide_earlier_failure_on_same_build(self):
        item = job("flaky")
        failed = self.execution(item, status="FAIL", host_end_ns=1)
        passed = self.execution(item, status="PASS", host_end_ns=2)
        built = state.build_state([item], [report(failed), report(passed)])["cases"][0]
        self.assertEqual("FAIL", built["state"])
        self.assertEqual("PASS", built["last_result"]["status"])
        self.assertEqual(1, len(built["same_build_failures"]))
        alone = state.build_state([item], [report(passed)])["cases"][0]
        self.assertEqual("PASS", alone["state"])

    def test_later_actual_retest_clears_change_but_offline_rescoring_does_not(self):
        item = job("standard")
        instant = int(dt.datetime.fromisoformat("2026-10-03T19:00:00+05:30").timestamp() * 1_000_000_000)
        change = {"id": "decoder", "reason": "Measured decoder recovery fix requires preservation", "files": ["Decoder.kt"],
                  "at": "2026-10-03T19:00:00+05:30"}
        for ended, expected in ((instant - 1, "NEEDS RETEST"), (instant + 1, "PASS"), (None, "NEEDS RETEST")):
            with self.subTest(ended=ended):
                result = self.execution(item, host_end_ns=ended)
                built = state.build_state([item], [report(result)], [change])["cases"][0]
                self.assertEqual(expected, built["state"])

    def test_rescore_keeps_verified_same_execution_time_for_freshness(self):
        item = job("standard")
        instant = state._time("2026-10-03T19:00:00+05:30")
        change = {"id": "decoder", "reason": "Shared decoder changed", "files": ["Decoder.kt"],
                  "at": "2026-10-03T19:00:00+05:30"}
        for ended, expected in ((instant - 1, "NEEDS RETEST"), (instant + 1, "CURRENT")):
            with self.subTest(ended=ended):
                executed = self.execution(item, status="FAIL", host_end_ns=ended)
                rescored = {**executed, "status": "UNMEASURED"}
                rescored.pop("host_end_ns")
                reports = [report(executed), report(rescored, generated_at="2026-10-04T20:00:00+05:30")]
                before = copy.deepcopy(reports)
                row = state.build_state([item], reports, [change])["cases"][0]
                self.assertEqual(expected, row["freshness"])
                self.assertEqual("UNMEASURED" if expected == "CURRENT" else "NEEDS RETEST", row["state"])
                self.assertEqual(rescored, row["last_measured_result"])
                self.assertEqual(rescored, row["last_result"])
                self.assertEqual(ended, row["last_verified_apk_evidence"]["host_end_ns"])
                self.assertEqual(executed["manifest"], row["last_verified_apk_evidence"]["execution_time_source"]["manifest"])
                self.assertEqual(before, reports)

    def test_rescore_cannot_borrow_time_from_unmatched_or_unverified_execution(self):
        item = job("standard")
        instant = state._time("2026-10-03T19:00:00+05:30")
        change = {"id": "decoder", "reason": "Shared decoder changed", "files": ["Decoder.kt"],
                  "at": "2026-10-03T19:00:00+05:30"}
        executed = self.execution(item, host_end_ns=instant + 1)
        rescored = {**executed, "host_end_ns": None}
        copied_manifest = self.folder / "another-execution-manifest.json"
        copied_manifest.write_bytes(Path(executed["manifest"]).read_bytes())
        for changed in ({**executed, "manifest": str(copied_manifest)},
                        {**executed, "manifest_sha256": "f" * 64},
                        {**executed, "origin": "LIVE_ATTEMPT"}):
            with self.subTest(manifest=changed["manifest"], origin=changed["origin"]):
                row = state.build_state([item], [report(changed), report(rescored)], [change])["cases"][0]
                self.assertEqual("NEEDS RETEST", row["freshness"])
                self.assertIsNone(row["last_verified_apk_evidence"]["host_end_ns"])

    def test_rescore_conflicting_execution_times_remain_conservative(self):
        item = job("standard")
        instant = state._time("2026-10-03T19:00:00+05:30")
        change = {"id": "decoder", "reason": "Shared decoder changed", "files": ["Decoder.kt"],
                  "at": "2026-10-03T19:00:00+05:30"}
        executed = self.execution(item, host_end_ns=instant + 1)
        conflicting = {**executed, "host_end_ns": instant + 2}
        rescored = {**executed, "host_end_ns": None}
        row = state.build_state([item], [report(executed, conflicting), report(rescored)], [change])["cases"][0]
        self.assertEqual("NEEDS RETEST", row["freshness"])
        self.assertIsNone(row["last_verified_apk_evidence"]["host_end_ns"])

    def test_latest_blocked_attempt_keeps_historical_apk_evidence_without_completing(self):
        item = job("standard")
        passed = self.execution(item)
        blocked = {"case_id": "standard", "scenario": item["case"], "status": "BLOCKED", "origin": "LIVE_ATTEMPT", "host_end_ns": 2}
        built = state.build_state([item], [report(passed), report(blocked)])["cases"][0]
        self.assertEqual("BLOCKED", built["state"])
        self.assertEqual(blocked, built["last_result"])
        self.assertEqual({"a": APK, "b": APK}, built["last_verified_apk"])
        self.assertEqual(passed["manifest"], built["last_verified_apk_evidence"]["manifest"])
        self.assertFalse(state.verify_result(built["last_result"], item))
        self.assertEqual([], state.select_remaining({"cases": [built]}, {"a", "b"}))

    def test_candidate_apk_verification_reads_manifest_instead_of_state_metadata(self):
        item = job("standard")
        result = self.execution(item)
        self.assertTrue(state.verify_result(result, item, expected_apk=APK.upper()))
        self.assertFalse(state.verify_result(result, item, expected_apk="c" * 64))

    def raw_execution(self, item):
        private = "caller" in item["case"]
        senders = item["phones"] if private else [item["case"]["source"]]
        paths, references = {}, {}
        for sender in senders:
            path = self.folder / (sender + ".yuv")
            path.write_bytes(b"\x00" * 6)
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            paths[sender] = path
            references[sender] = {"path": str(path), "sha256": digest, "format": "I420",
                                  "width": 2, "height": 2, "frames": 1, "bytes": 6,
                                  "declared_metadata": copy.deepcopy(item["case"].get("raw_fixture_metadatas", {}).get(sender)
                                      if private else item["case"].get("raw_fixture_metadata"))}
        if private:
            item["case"]["raw_fixture_files"] = {phone: str(path) for phone, path in paths.items()}
            item["case"]["raw_fixture_sha256s"] = {phone: ref["sha256"] for phone, ref in references.items()}
        else:
            item["case"]["raw_fixture_file"] = str(paths[senders[0]])
            item["case"]["raw_fixture_sha256"] = references[senders[0]]["sha256"]
        measured = self.execution(item)
        manifest_path = Path(measured["manifest"])
        manifest = json.loads(manifest_path.read_text())
        manifest["source_fixtures" if private else "source_fixture"] = references if private else references[senders[0]]
        manifest_path.write_text(json.dumps(manifest))
        measured["manifest_sha256"] = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
        return measured, paths

    def test_same_path_raw_content_change_requires_retest_without_erasing_old_measurement(self):
        item = job("raw")
        measured, paths = self.raw_execution(item)
        self.assertTrue(state.verify_result(measured, item))
        paths["a"].write_bytes(b"\xff" * 6)
        self.assertFalse(state.verify_result(measured, item))
        row = state.build_state([item], [report(measured)])["cases"][0]
        self.assertEqual("NEEDS RETEST", row["state"])
        self.assertEqual("NEEDS RETEST", row["freshness"])
        self.assertEqual(measured, row["last_measured_result"])
        self.assertEqual({"a": APK, "b": APK}, row["last_verified_apk"])

    def test_replay_freshness_requires_actual_staged_digest_and_preserves_prior_measurement(self):
        path = self.folder / "finite.ivf"
        path.write_bytes(struct.pack("<4sHH4sHHIIII", b"DKIF", 0, 32, b"VP90", 720, 480, 30, 1, 2251, 0) +
                         b"".join(struct.pack("<IQ", 1, index) + b"\x00" for index in range(2251)))
        item = job("replay")
        item["case"]["replay_file"] = str(path)
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        measured = self.execution(item)
        manifest_path = Path(measured["manifest"])
        manifest = json.loads(manifest_path.read_text())
        record = {"path": str(path), "sha256": digest, "format": "IVF", "codec": "VP90", "width": 720,
                  "height": 480, "fps": 30, "rate": 30, "scale": 1, "frames": 2251, "bytes": path.stat().st_size,
                  "loops": False, "staging": {"device": "a", "remote": "/phone/finite.ivf", "sha256": digest,
                                               "status": "VERIFIED"}}
        manifest["replay_fixture"] = record
        manifest_path.write_text(json.dumps(manifest))
        measured["manifest_sha256"] = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
        self.assertTrue(state.verify_result(measured, item))
        before = manifest_path.read_bytes()
        content = path.read_bytes()
        path.write_bytes(content[:-1] + b"\x01")
        self.assertFalse(state.verify_result(measured, item))
        row = state.build_state([item], [report(measured)])["cases"][0]
        self.assertEqual("NEEDS RETEST", row["state"])
        self.assertEqual("NEEDS RETEST", row["freshness"])
        self.assertEqual(measured, row["last_measured_result"])
        self.assertEqual(before, manifest_path.read_bytes())
        path.write_bytes(content)
        for defect in ("missing_record", "unstaged", "wrong_device", "wrong_digest", "native_geometry"):
            with self.subTest(defect=defect):
                changed = copy.deepcopy(manifest)
                if defect == "missing_record":
                    changed.pop("replay_fixture")
                elif defect == "unstaged":
                    changed["replay_fixture"].pop("staging")
                elif defect == "wrong_device":
                    changed["replay_fixture"]["staging"]["device"] = "b"
                elif defect == "wrong_digest":
                    changed["replay_fixture"]["staging"]["sha256"] = "f" * 64
                else:
                    changed["replay_fixture"]["width"] = 1280
                manifest_path.write_text(json.dumps(changed))
                measured["manifest_sha256"] = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
                self.assertFalse(state.verify_result(measured, item))
                row = state.build_state([item], [report(measured)])["cases"][0]
                self.assertEqual("NEEDS RETEST", row["state"])
        guarded = copy.deepcopy(item)
        guarded["case"]["replay_sha256"] = digest
        self.assertNotEqual(state.request_key(item["case"]), state.request_key(guarded["case"]))
        self.assertEqual("NOT RUN", state.build_state([guarded], [report(measured)])["cases"][0]["state"])

    def test_raw_pass_requires_recorded_reference_and_matching_declared_guard(self):
        for defect in ("reference", "guard", "file"):
            with self.subTest(defect=defect):
                item = job("raw-" + defect)
                measured, paths = self.raw_execution(item)
                manifest_path = Path(measured["manifest"])
                manifest = json.loads(manifest_path.read_text())
                if defect == "reference":
                    manifest.pop("source_fixture")
                elif defect == "guard":
                    item["case"]["raw_fixture_sha256"] = "f" * 64
                    manifest["scenario"]["raw_fixture_sha256"] = "f" * 64
                    measured["scenario"]["raw_fixture_sha256"] = "f" * 64
                else:
                    paths["a"].unlink()
                manifest_path.write_text(json.dumps(manifest))
                measured["manifest_sha256"] = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
                self.assertFalse(state.verify_result(measured, item))

    def test_private_call_raw_input_guards_cover_both_independent_directions(self):
        item = job("call-raw")
        profile = item["case"]["profile"]
        item["case"] = {"id": "call-raw", "caller": "a", "callee": "b", "transport": "gatt",
                        "duration_s": 45, "profiles": {"a": profile, "b": copy.deepcopy(profile)}}
        item["mode"] = "private_call"
        measured, paths = self.raw_execution(item)
        self.assertTrue(state.verify_result(measured, item))
        paths["b"].write_bytes(b"\xff" * 6)
        self.assertFalse(state.verify_result(measured, item))

    def test_changed_raw_sidecar_requires_retest_without_erasing_measurement(self):
        for name in ("frames", "events", "reference", "reference_audio", "calibration_reference"):
            with self.subTest(sidecar=name):
                sidecar = self.folder / (name + ".bin")
                sidecar.write_bytes(b"original reference declaration")
                item = job("raw-" + name)
                item["case"]["raw_fixture_metadata"] = {"sidecars": {name: {
                    "path": str(sidecar), "sha256": hashlib.sha256(sidecar.read_bytes()).hexdigest()}}}
                measured, _ = self.raw_execution(item)
                self.assertTrue(state.verify_result(measured, item))
                sidecar.write_bytes(b"changed at the same path")
                self.assertFalse(state.verify_result(measured, item))
                row = state.build_state([item], [report(measured)])["cases"][0]
                self.assertEqual("NEEDS RETEST", row["state"])
                self.assertEqual("NEEDS RETEST", row["freshness"])
                self.assertEqual(measured, row["last_measured_result"])

    def test_raw_pass_requires_recorded_native_metadata_and_sidecars(self):
        sidecar = self.folder / "frames.csv"
        for defect in ("missing_metadata", "changed_metadata", "missing_sidecar", "wrong_path"):
            with self.subTest(defect=defect):
                sidecar.write_bytes(b"frame_id\n0\n")
                item = job("raw-" + defect)
                item["case"]["raw_fixture_metadata"] = {"color": {"range": "limited"}, "sidecars": {
                    "frames": {"path": str(sidecar), "sha256": hashlib.sha256(sidecar.read_bytes()).hexdigest()}}}
                measured, _ = self.raw_execution(item)
                manifest_path = Path(measured["manifest"])
                manifest = json.loads(manifest_path.read_text())
                if defect == "missing_metadata":
                    manifest["source_fixture"].pop("declared_metadata")
                elif defect == "changed_metadata":
                    manifest["source_fixture"]["declared_metadata"]["color"]["range"] = "full"
                elif defect == "missing_sidecar":
                    sidecar.unlink()
                else:
                    manifest["source_fixture"]["path"] = str(self.folder / "unrecorded.yuv")
                manifest_path.write_text(json.dumps(manifest))
                measured["manifest_sha256"] = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
                self.assertFalse(state.verify_result(measured, item))

    def test_private_call_sidecar_freshness_covers_reverse_outgoing_reference(self):
        item = job("call-sidecar")
        profile = item["case"]["profile"]
        frames = self.folder / "reverse-frames.csv"
        frames.write_bytes(b"frame_id\n0\n")
        item["case"] = {"id": item["case"]["id"], "caller": "a", "callee": "b", "transport": "gatt",
                        "duration_s": 45, "profiles": {"a": profile, "b": copy.deepcopy(profile)},
                        "raw_fixture_metadatas": {"b": {"sidecars": {"frames": {
                            "path": str(frames), "sha256": hashlib.sha256(frames.read_bytes()).hexdigest()}}}}}
        item["mode"] = "private_call"
        measured, _ = self.raw_execution(item)
        self.assertTrue(state.verify_result(measured, item))
        frames.write_bytes(b"frame_id\n1\n")
        self.assertFalse(state.verify_result(measured, item))
        row = state.build_state([item], [report(measured)])["cases"][0]
        self.assertEqual("NEEDS RETEST", row["state"])
        self.assertIn("for b", row["reason"])
        self.assertEqual(measured, row["last_measured_result"])

    def test_reference_audio_and_impairment_need_recorded_current_bytes_in_each_direction(self):
        for private in (False, True):
            for name, plural in (("reference_audio", "reference_audios"), ("media_impairment", "media_impairments")):
                with self.subTest(private=private, input=name):
                    item = job("inputs-" + str(private) + "-" + name)
                    if private:
                        profile = item["case"]["profile"]
                        item["case"] = {"id": item["case"]["id"], "caller": "a", "callee": "b", "transport": "gatt",
                                        "duration_s": 45, "profiles": {"a": profile, "b": copy.deepcopy(profile)}}
                        item["mode"] = "private_call"
                    item["case"]["media_trace"] = True
                    inputs = {}
                    for sender in item["phones"] if private else ["a"]:
                        path = self.folder / (sender + "-" + name + ".bin")
                        path.write_bytes(b"frozen offline input")
                        inputs[sender] = {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                    item["case"][plural if private else name] = inputs if private else inputs["a"]
                    measured, _ = self.raw_execution(item)
                    manifest_path = Path(measured["manifest"])
                    manifest = json.loads(manifest_path.read_text())
                    manifest[plural if private else name] = copy.deepcopy(inputs if private else inputs["a"])
                    manifest_path.write_text(json.dumps(manifest))
                    measured["manifest_sha256"] = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
                    self.assertTrue(state.verify_result(measured, item))
                    target = "b" if private else "a"
                    Path(inputs[target]["path"]).write_bytes(b"changed frozen input")
                    self.assertFalse(state.verify_result(measured, item))
                    row = state.build_state([item], [report(measured)])["cases"][0]
                    self.assertEqual("NEEDS RETEST", row["state"])
                    self.assertEqual(measured, row["last_measured_result"])
                    self.assertEqual({phone: APK for phone in item["phones"]}, row["last_verified_apk"])
                    Path(inputs[target]["path"]).write_bytes(b"frozen offline input")
                    manifest.pop(plural if private else name)
                    manifest_path.write_text(json.dumps(manifest))
                    measured["manifest_sha256"] = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
                    self.assertFalse(state.verify_result(measured, item))

    def test_current_candidate_marks_old_build_retest_and_retains_measured_evidence(self):
        item = job("standard")
        measured = self.execution(item)
        candidate = "c" * 64
        built = state.build_state([item], [report(measured)], expected_apk=candidate.upper())
        row = built["cases"][0]
        self.assertEqual(candidate, built["candidate_apk_sha256"])
        self.assertEqual(candidate, row["candidate_apk_sha256"])
        self.assertEqual("NEEDS RETEST", row["state"])
        self.assertEqual("NEEDS RETEST", row["freshness"])
        self.assertEqual(measured, row["last_measured_result"])
        self.assertEqual({phone: APK for phone in item["phones"]}, row["last_verified_apk"])
        same = state.build_state([item], [report(measured)], expected_apk=APK)["cases"][0]
        self.assertEqual("PASS", same["state"])
        self.assertEqual("CURRENT", same["freshness"])
        for invalid in ("new-build", "a" * 63, 123):
            with self.subTest(candidate=invalid), self.assertRaises(ValueError):
                state.build_state([item], [], expected_apk=invalid)

    def test_blocked_retry_cannot_clear_earlier_measurement_change(self):
        item = job("standard")
        instant = int(dt.datetime.fromisoformat("2026-10-04T01:00:00+05:30").timestamp() * 1_000_000_000)
        measured = self.execution(item, host_end_ns=instant - 1)
        blocked = {"case_id": item["case"]["id"], "scenario": item["case"], "status": "BLOCKED",
                   "origin": "LIVE_ATTEMPT", "host_end_ns": instant + 1, "reason": "Phone is unavailable"}
        change = {"id": "decoder-fix", "reason": "A decoder-stage fix affects earlier playback measurements",
                  "files": ["Decoder.kt"], "at": "2026-10-04T01:00:00+05:30"}
        whole = state.build_state([item], [report(measured, blocked)], [change])
        built = whole["cases"][0]
        self.assertTrue(changes_of(whole, built)[0]["needs_retest"])
        self.assertEqual("BLOCKED", built["state"])
        self.assertEqual("NEEDS RETEST", built["freshness"])
        self.assertEqual(measured, built["last_measured_result"])
        self.assertEqual(blocked, built["last_result"])
        self.assertEqual([], state.select_remaining({"cases": [built]}, {"a", "b"}))

    def test_unexecuted_blocked_case_has_no_measurement_to_retest(self):
        item = job("standard")
        blocked = {"case_id": item["case"]["id"], "scenario": item["case"], "status": "BLOCKED",
                   "origin": "LIVE_ATTEMPT", "host_end_ns": 1}
        change = {"id": "decoder-fix", "reason": "Decoder change affects every eligible playback case",
                  "files": ["Decoder.kt"]}
        whole = state.build_state([item], [report(blocked)], [change])
        built = whole["cases"][0]
        self.assertEqual("BLOCKED", built["state"])
        self.assertEqual("NOT RUN", built["freshness"])
        self.assertIsNone(built["last_measured_result"])
        self.assertFalse(changes_of(whole, built)[0]["needs_retest"])

    def test_unknown_or_transient_state_cannot_silently_hide_remaining_cases(self):
        for invalid in ("COMPLETE", "SKIPPED", "RUNNING", "ANALYZING", None):
            with self.subTest(state=invalid):
                for phones in (("a", "b"), ("a", "unavailable")):
                    with self.assertRaises(ValueError):
                        state.select_remaining({"cases": [{"case_id": "standard", "phones": list(phones),
                                                          "state": invalid}]}, {"a", "b"})

    def test_availability_keeps_missing_and_future_devices_and_prioritizes_failures(self):
        untested, failed, affected, blocked, missing = [job(name) for name in ("untested", "failed", "affected", "blocked", "missing")]
        missing["phones"] = ["a", "future-phone"]
        results = [self.execution(failed, "FAIL"), self.execution(affected)]
        results.append({"case_id": "blocked", "scenario": blocked["case"], "status": "BLOCKED", "origin": "LIVE_ATTEMPT"})
        affected["case"]["transport"] = "classic"
        results[1] = self.execution(affected)
        change = {"id": "classic", "reason": "Classic scanner change measured on this path", "files": ["Classic.kt"],
                  "affects": {"transports": ["classic"]}}
        built = state.build_state([untested, failed, affected, blocked, missing], [report(*results)], [change])
        self.assertEqual(5, len(built["cases"]))
        self.assertEqual(["failed", "affected", "untested"], [row["case_id"] for row in state.select_remaining(built, {"a", "b"})])
        self.assertEqual("BLOCKED", built["cases"][3]["state"])
        self.assertEqual("NOT RUN", built["cases"][4]["state"])

    def test_impact_selectors_are_exact_and_unknown_selectors_are_rejected(self):
        first, other = job("first"), job("other", ("c", "d"))
        change = {"id": "narrow", "reason": "Device-specific trace proves the fix is limited to phone a and this encoder", "files": ["Codec.kt"],
                  "affects": {"phones": ["a"], "encoders": [first["case"]["profile"]["codec"]]}}
        built = state.build_state([first, other], [report(self.execution(first), self.execution(other))], [change])
        self.assertEqual(["NEEDS RETEST", "PASS"], [row["state"] for row in built["cases"]])
        for invalid in ({**change, "affects": {"transport": ["gatt"]}}, {**change, "reason": ""}):
            with self.assertRaises(ValueError):
                state.build_state([first], [], [invalid])

    def test_cli_keeps_full_inventory_and_never_calls_devices(self):
        items = [job("pair" + str(index), ("a", phone)) for index, phone in enumerate("bcdefg")]
        registry = {phone: {"serial": "fake-" + phone, "node_prefix": f"{index + 1:012x}"}
                    for index, phone in enumerate("abcdefg")}
        suite_path, devices_path = self.folder / "suite.json", self.folder / "devices.json"
        suite_path.write_text(json.dumps({"cases": [item["case"] for item in items]}))
        devices_path.write_text(json.dumps({"devices": registry}))
        original = suite_path.read_bytes()
        output = self.folder / "state-output"
        with patch.object(sys, "path", [str(Path(__file__).parents[1]), *sys.path]), \
             patch("subprocess.run", side_effect=AssertionError("Device I/O is forbidden")), \
             patch("subprocess.Popen", side_effect=AssertionError("Device process is forbidden")):
            self.assertEqual(0, state.main(["state", "--suite", str(suite_path), "--devices", str(devices_path),
                                          "--phones", "a,b", "--apk-sha256", APK, "--output", str(output)]))
        saved = json.loads((output / "state.json").read_text())
        remaining = json.loads((output / "remaining.json").read_text())
        self.assertEqual(6, len(saved["cases"]))
        self.assertEqual(APK, saved["candidate_apk_sha256"])
        self.assertTrue(all(row["candidate_apk_sha256"] == APK for row in saved["cases"]))
        self.assertEqual(["pair0"], remaining["case_ids"])
        self.assertEqual(["g"], saved["cases"][-1]["availability"]["missing_phones"])
        self.assertEqual(original, suite_path.read_bytes())

    def test_initial_state_can_share_bound_catalog_without_overwriting_definitions_or_prior_state(self):
        item = job("bound")
        suite_path = self.folder / "raw-suite.json"
        devices_path = self.folder / "devices.json"
        suite_path.write_text(json.dumps({"cases": [item["case"]]}), encoding="utf-8")
        devices_path.write_text(json.dumps({"devices": {p: {"serial": "fake-" + p, "node_prefix": f"{i + 1:012x}"}
                                                     for i, p in enumerate("ab")}}), encoding="utf-8")
        original = suite_path.read_bytes()
        args = ["state", "--suite", str(suite_path), "--devices", str(devices_path), "--phones=", "--output", str(self.folder)]
        with patch("subprocess.run", side_effect=AssertionError("Phones paused")):
            self.assertEqual(0, state.main(args))
            before = {p: p.read_bytes() for p in self.folder.iterdir() if p.is_file()}
            with self.assertRaises(FileExistsError):
                state.main(args)
        self.assertEqual(original, suite_path.read_bytes())
        self.assertEqual(before, {p: p.read_bytes() for p in self.folder.iterdir() if p.is_file()})
        self.assertEqual([], json.loads((self.folder / "remaining.json").read_text())["case_ids"])

    def test_cli_combines_repeated_change_files_and_rejects_duplicate_change_ids(self):
        first, second = job("gatt"), job("classic", transport="classic")
        registry = {phone: {"serial": "fake-" + phone, "node_prefix": f"{index + 1:012x}"}
                    for index, phone in enumerate("ab")}
        suite_path, devices_path, report_path = [self.folder / name for name in ("suite.json", "devices.json", "report.json")]
        suite_path.write_text(json.dumps({"cases": [first["case"], second["case"]]}))
        devices_path.write_text(json.dumps({"devices": registry}))
        report_path.write_text(json.dumps(report(self.execution(first), self.execution(second))))
        changes = []
        for transport in ("gatt", "classic"):
            path = self.folder / (transport + "-change.json")
            path.write_text(json.dumps({"changes": [{"id": transport, "reason": transport + " stage fix",
                                                   "affects": {"transports": [transport]}}]}))
            changes.extend(["--changes", str(path)])
        args = ["state", "--suite", str(suite_path), "--devices", str(devices_path), "--phones", "a,b",
                "--reports", str(report_path), *changes]
        with patch.object(sys, "path", [str(Path(__file__).parents[1]), *sys.path]), \
             patch("subprocess.run", side_effect=AssertionError("Device I/O is forbidden")), \
             patch("subprocess.Popen", side_effect=AssertionError("Device process is forbidden")):
            self.assertEqual(0, state.main([*args, "--output", str(self.folder / "combined")]))
            stored = state_header(self.folder / "combined/state.json")
            rows = list(iter_state_rows(self.folder / "combined/state.json"))
            self.assertEqual(["NEEDS RETEST", "NEEDS RETEST"], [row["state"] for row in rows])
            self.assertEqual([["gatt"], ["classic"]],
                             [[change["id"] for change in changes_of(stored, row)] for row in rows])
            with self.assertRaises(ValueError):
                state.main([*args, "--changes", str(self.folder / "gatt-change.json"),
                            "--output", str(self.folder / "duplicate")])
        self.assertFalse((self.folder / "duplicate").exists())

    def test_a_change_declared_to_affect_no_case_is_recorded_but_retests_nothing(self):
        item = job("standard")
        inputs = [report(self.execution(item))]
        logging = {"id": "debug-log-only", "reason": "Adds a DEBUG log line", "files": ["Log.kt"], "affects": "none",
                   "affects_none_reason": "DEBUG logging only; no release or measured behaviour changes"}
        behaviour = {"id": "decoder-fix", "reason": "Decoder stage fix", "files": ["Decoder.kt"]}
        quiet = state.build_state([item], inputs, [logging])
        self.assertEqual(["PASS"], [row["state"] for row in quiet["cases"]])
        self.assertEqual([], changes_of(quiet, quiet["cases"][0]))
        self.assertEqual([logging], quiet["changes"])  # still recorded, so a later checkpoint keeps its definition
        mixed = state.build_state([item], inputs, [logging, behaviour])
        self.assertEqual(["NEEDS RETEST"], [row["state"] for row in mixed["cases"]])
        self.assertEqual(["decoder-fix"], [c["id"] for c in changes_of(mixed, mixed["cases"][0])])

    def test_affects_none_needs_a_stated_reason_and_cannot_hide_a_gate_change(self):
        item = job("standard")
        base = {"id": "docs", "reason": "Documentation", "affects": "none"}
        for bad in (base, {**base, "affects_none_reason": " "},
                    {**base, "affects_none_reason": "Docs", "acceptance_profile": "device-run-v4-user"}):
            with self.subTest(change=bad), self.assertRaisesRegex(ValueError, "affects_none_reason"):
                state.build_state([item], [], [bad])
        with self.assertRaises(ValueError):
            state.build_state([item], [], [{**base, "affects": "nothing", "affects_none_reason": "Docs"}])

    def test_carrier_change_retests_only_carrier_cases(self):
        carrier, bluetooth = job("carrier"), job("bluetooth")
        carrier["case"]["carrier"] = "wifi_aware"
        inputs = [report(*(self.execution(item) for item in (carrier, bluetooth)))]
        wifi_change = {"id": "wifi-aware-change", "reason": "Wi-Fi Aware path changed", "files": ["Wifi.kt"],
                       "affects": {"carriers": ["wifi_aware"]}}
        rows = {row["case_id"]: row for row in state.build_state([carrier, bluetooth], inputs, [wifi_change])["cases"]}
        self.assertEqual("NEEDS RETEST", rows["carrier"]["state"])
        self.assertEqual("PASS", rows["bluetooth"]["state"])

        bluetooth_change = {"id": "bluetooth-change", "reason": "Bluetooth path changed", "files": ["Bt.kt"],
                            "affects": {"carriers": ["bluetooth"]}}
        rows = {row["case_id"]: row for row in state.build_state([carrier, bluetooth], inputs, [bluetooth_change])["cases"]}
        self.assertEqual("PASS", rows["carrier"]["state"])
        self.assertEqual("NEEDS RETEST", rows["bluetooth"]["state"])


if __name__ == "__main__":
    unittest.main()
