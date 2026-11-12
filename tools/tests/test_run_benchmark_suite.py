"""Offline regressions for scheduling, ownership and truthful suite evidence."""
import contextlib
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import struct
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location("benchmark_suite", Path(__file__).parents[1] / "run_benchmark_suite.py")
suite = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(suite)


def devices(aliases="abcdef"):
    return {alias: {"serial": "serial-" + alias, "node_prefix": f"{index + 1:012x}"}
            for index, alias in enumerate(aliases)}


def case(identifier="one", source="a", receiver="b"):
    return {"id": identifier, "source": source, "transport": "classic", "duration_s": 10,
            "profile": {"codec": "encoder-a", "width": 720, "height": 480, "fps": 30,
                        "bitrate_kbps": 1100, "rate_control": "cbr", "playback": "balanced"},
            "receivers": [{"phone": receiver, "parent": source}]}


def capabilities(registry):
    return {phone: {"node_prefix": device["node_prefix"], "fingerprint": "fingerprint-" + phone,
                   "decoders": ["vp9"], "encoders": [{"name": "encoder-a", "codec": "vp9",
                    "rate_controls": ["automatic", "cbr"], "modes": [{"width": 720, "height": 480,
                        "bitrate_kbps": 1100, "fps": [30]}]}]} for phone, device in registry.items()}


def result(identifier, state, key=None):
    value = {"case_id": identifier, "origin": "LIVE_ATTEMPT", "status": state,
             "families": {name: dict(suite.UNKNOWN) for name in suite.FAMILIES}}
    if key is not None:
        value["comparison_key"] = key
    return value


class UnifiedSuiteTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name).resolve()

    def inputs(self, cases):
        registry = devices()
        suite.write(self.folder / "devices.json", {"devices": registry})
        suite.write(self.folder / "input.json", {"cases": cases})
        return registry

    def test_atomic_json_bytes_match_the_prepared_hash_on_windows(self):
        value = {"literal": "line one\nline two", "state": "NOT RUN"}
        path = self.folder / "portable.json"
        suite.write(path, value)
        expected = (json.dumps(value, indent=2, allow_nan=False) + "\n").encode("utf-8")
        self.assertEqual(expected, path.read_bytes())
        self.assertEqual(value, suite.read(path))

    def run_args(self):
        return ["run", "--suite", str(self.folder / "input.json"), "--devices", str(self.folder / "devices.json"),
                "--output", str(self.folder / "output"), "--phones", "a,b,c,d,e,f", "--owner", "offline-test",
                "--apk-sha256", "a" * 64]

    def recorded_state_row(self, requested, registry, apk="a" * 64):
        """A local dummy manifest tests verification rules; it is not a phone PASS."""
        phones = [requested["source"], requested["receivers"][0]["phone"]]
        manifest = {"scenario": {**requested, "expected_apk_sha256": apk},
                    "devices": {phone: {**registry[phone], "apk_sha256": apk} for phone in phones},
                    "logs": [{"device": phone, "pid": 100 + index} for index, phone in enumerate(phones)]}
        path = self.folder / (requested["id"] + "-dummy-manifest.json")
        suite.write(path, manifest)
        measured = {"case_id": requested["id"], "scenario": manifest["scenario"],
                    "origin": "RECORDED_EXECUTION_ANALYSIS", "status": "PASS", "pipeline_status": "PASS",
                    "manifest": str(path), "manifest_sha256": suite.digest(path),
                    "families": {name: {"status": "PASS", "measurements": [{"status": "PASS", "name": "dummy"}]}
                                 for name in suite.FAMILIES}}
        return {"case_id": requested["id"], "request_key": suite.request_key(requested), "phones": phones,
                "state": "PASS", "last_result": measured, "last_verified_apk": {phone: apk for phone in phones}}

    def blocked_mock_attempt(self, job, *_, **__):
        return {"result": {**result(job["case"]["id"], "BLOCKED"), "scenario": job["case"],
                           "reason": "Offline mock stops before any device work"}}

    def test_awake_proof_accepts_current_power_dump_and_requires_explicit_unlock(self):
        policy = "KeyguardServiceDelegate\n  showing=false\n  inputRestricted=false\n  screenState=SCREEN_STATE_ON"
        self.assertTrue(suite.awake_unlocked("mWakefulness=Awake\n", policy))
        for power, window in [("mWakefulness=Asleep", policy), ("", policy),
                              ("mWakefulness=Awake", ""),
                              ("mWakefulness=Awake", policy.replace("showing=false", "showing=true")),
                              ("mWakefulness=Awake", policy.replace("inputRestricted=false", "inputRestricted=true"))]:
            with self.subTest(power=power, window=window):
                self.assertFalse(suite.awake_unlocked(power, window))

    def test_unconfirmed_phone_cleanup_quarantines_identity_and_blocks_refill(self):
        registry = devices("ab")
        output, leases = self.folder / "cleanup", self.folder / "leases"
        output.mkdir()
        with suite.DeviceLease(leases, registry, "ab", True, "owner") as lease:
            def probe(argv, **_):
                if "serial-a" in argv:
                    return subprocess.CompletedProcess(argv, 0, "1234\n", "")
                return subprocess.CompletedProcess(argv, 1, "", "")
            with patch.object(suite.subprocess, "run", side_effect=probe):
                with self.assertRaisesRegex(RuntimeError, "cleanup.*unconfirmed"):
                    suite.verify_phone_cleanup("mock-adb", registry, ["a", "b"], output, lease)
        receipt = suite.read(output / "cleanup.json")
        self.assertEqual("UNCONFIRMED", receipt["status"])
        self.assertEqual("1234", receipt["phones"]["a"]["pidof_stdout"])
        self.assertEqual("STOPPED", receipt["phones"]["b"]["status"])
        remaining = list(leases.glob("phone-*.json"))
        self.assertEqual(2, len(remaining))
        self.assertTrue(all(suite.read(path)["status"] == "QUARANTINED" for path in remaining))
        with self.assertRaises(suite.PrerequisiteError):
            with suite.DeviceLease(leases, registry, ["a"], False, "next-owner"):
                self.fail("Still-running phone was reassigned")

    def test_a_killed_suite_no_longer_blocks_its_phones_but_a_quarantined_one_still_does(self):
        registry = devices("ab")
        leases = self.folder / "leases"
        ended = subprocess.Popen([sys.executable, "-c", "pass"])
        ended.wait()
        lease = suite.DeviceLease(leases, registry, "a", False, "killed-runner")
        leases.mkdir(parents=True)
        for key in lease.keys + ["session-" + lease.token]:
            suite.write(leases / (key + ".json"), {"owner": "killed-runner", "pid": ended.pid, "token": lease.token})
        with suite.DeviceLease(leases, registry, "a", False, "next-owner"):
            pass
        stuck = suite.DeviceLease(leases, registry, "b", False, "quarantined-runner")
        for key in stuck.keys:
            suite.write(leases / (key + ".json"), {"owner": "quarantined-runner", "pid": ended.pid, "token": stuck.token,
                                                   "status": "QUARANTINED"})
        with self.assertRaises(suite.PrerequisiteError):
            with suite.DeviceLease(leases, registry, "b", False, "next-owner"):
                self.fail("A phone left in an unconfirmed state was reassigned")
        live = suite.DeviceLease(leases, registry, "a", False, "live-runner").__enter__()
        try:
            with self.assertRaises(suite.PrerequisiteError):
                with suite.DeviceLease(leases, registry, "a", False, "next-owner"):
                    self.fail("A running suite's phone was reassigned")
        finally:
            live.__exit__(None, None, None)

    def test_unknown_phone_cleanup_is_not_treated_as_stopped(self):
        registry = devices("a")
        for outcome in [subprocess.CompletedProcess([], 1, "", "error: device offline"),
                        subprocess.TimeoutExpired(["mock-adb"], 5)]:
            with self.subTest(outcome=outcome):
                output = self.folder / str(len(list(self.folder.iterdir())))
                output.mkdir()
                with suite.DeviceLease(self.folder / (output.name + "-leases"), registry, "a", False, "owner") as lease:
                    with patch.object(suite.subprocess, "run", side_effect=outcome if isinstance(outcome, Exception) else None,
                                      return_value=outcome):
                        with self.assertRaisesRegex(RuntimeError, "cleanup.*unconfirmed"):
                            suite.verify_phone_cleanup("mock-adb", registry, ["a"], output, lease)
                self.assertEqual("UNCONFIRMED", suite.read(output / "cleanup.json")["status"])

    def test_confirmed_phone_cleanup_releases_lease(self):
        registry = devices("a")
        output, leases = self.folder / "cleanup", self.folder / "leases"
        output.mkdir()
        with suite.DeviceLease(leases, registry, "a", False, "owner") as lease:
            with patch.object(suite.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, "", "")):
                suite.verify_phone_cleanup("mock-adb", registry, ["a"], output, lease)
        self.assertEqual("STOPPED", suite.read(output / "cleanup.json")["status"])
        self.assertFalse(list(leases.glob("*.json")))

    def test_recorder_cleanup_and_registration_finish_before_resources_are_released(self):
        registry = self.inputs([case()])
        job = suite.load_jobs(self.folder / "input.json", registry)[0]
        job.update(owner="offline-test", observation_capture={"spec": {}}, capture_resources=["external-camera:one"])
        order = []
        leases, output = self.folder / "leases", self.folder / "execution"
        output.mkdir()
        recorder = Mock(output=output / "observation")

        def start():
            recorder.output.mkdir()
            order.append("recording-start")

        def physical():
            order.append("runner")
            suite.write(output / "physical/manifest.json", {"run_id": "physical", "scenario": case()})

        def stop():
            self.assertTrue(list(leases.glob("capture-*.json")))
            order.append("recording-stopped")

        def attach(manifest):
            manifest["observation_recordings"] = [{"status": "INTERRUPTED", "reason": "Missing recording frames"}]
            order.append("registered")

        recorder.start.side_effect, recorder.stop.side_effect, recorder.attach.side_effect = start, stop, attach
        runner = Mock()
        runner.run.side_effect = physical
        with patch.object(suite, "preflight"), patch.object(suite, "verify_phone_cleanup"), \
             patch.object(suite, "Runner", return_value=runner), \
             patch.object(suite, "ObservationRecorder", return_value=recorder):
            measured = suite.execute(job, registry, "mock-adb", "a" * 64, output, leases)
        self.assertIn("manifest", measured)
        self.assertEqual(["recording-start", "runner", "recording-stopped", "registered"], order)
        self.assertFalse(list(leases.glob("*.json")))
        self.assertEqual("INTERRUPTED", suite.read(measured["manifest"])["observation_recordings"][0]["status"])

    def test_recorder_is_stopped_after_runner_failure_and_original_failure_remains(self):
        registry = self.inputs([case()])
        job = suite.load_jobs(self.folder / "input.json", registry)[0]
        job.update(owner="offline-test", observation_capture={"spec": {}}, capture_resources=["external-camera:one"])
        output, leases = self.folder / "failed", self.folder / "leases"
        output.mkdir()
        recorder = Mock(output=output / "observation")
        recorder.start.side_effect = lambda: recorder.output.mkdir()
        runner = Mock()
        runner.run.side_effect = RuntimeError("Actual runner failure")
        with patch.object(suite, "preflight"), patch.object(suite, "verify_phone_cleanup"), \
             patch.object(suite, "Runner", return_value=runner), \
             patch.object(suite, "ObservationRecorder", return_value=recorder):
            failed = suite.execute(job, registry, "mock-adb", "a" * 64, output, leases)
        self.assertEqual("FAIL", failed["result"]["status"])
        self.assertEqual("Actual runner failure", failed["result"]["reason"])
        recorder.stop.assert_called_once()
        self.assertFalse(list(leases.glob("*.json")))

    def test_relay_chain_the_app_cannot_build_is_blocked_without_leasing_a_phone(self):
        # The app builds up to four relays (PrivateCallRelayChain.MAX_RELAYS); five stay BLOCKED, and so
        # does any chain under the call_relay_chain=false control arm.
        profile = {"codec": "e", "width": 352, "height": 288, "fps": 15, "bitrate_kbps": 110}
        relays = ["b", "c", "e", "f", "g"]
        cases = {"too-long": ({"relays": relays}, "PrivateCallRelayChain.MAX_RELAYS"),
                 "chain-off": ({"relays": ["b", "c"], "tunables": {"call_relay_chain": False}}, "call_relay_chain=false")}
        for name, (changes, reason) in cases.items():
            job = {"case": {"id": name, "caller": "a", "callee": "d", "transport": "ble",
                            "profiles": {"a": profile, "d": profile}, **changes},
                   "mode": "private_call", "phones": ["a", *changes["relays"], "d"], "execution": "concurrent",
                   "owner": "offline-test"}
            output = self.folder / f"blocked-{name}"
            output.mkdir()
            with self.subTest(name), patch.object(suite, "DeviceLease", side_effect=AssertionError("phone leased")), \
                 patch.object(suite, "preflight", side_effect=AssertionError("phone touched")):
                blocked = suite.execute(job, {}, "mock-adb", "a" * 64, output, self.folder / "leases")
                self.assertEqual("BLOCKED", blocked["result"]["status"])
                self.assertIn(reason, blocked["result"]["reason"])

    def test_unconfirmed_recorder_quarantines_its_instrument_and_blocks_refill(self):
        registry = self.inputs([case()])
        job = suite.load_jobs(self.folder / "input.json", registry)[0]
        job.update(owner="offline-test", observation_capture={"spec": {}}, capture_resources=["external-camera:one"])
        output, leases = self.folder / "failed-cleanup", self.folder / "leases"
        output.mkdir()
        recorder = Mock(output=output / "observation", result={"process_pid": 77})
        recorder.start.side_effect = lambda: recorder.output.mkdir()
        recorder.process.poll.return_value = None
        recorder.stop.side_effect = PermissionError("Recorder termination API failed")
        with patch.object(suite, "preflight"), patch.object(suite, "verify_phone_cleanup"), \
             patch.object(suite, "Runner", return_value=Mock()), \
             patch.object(suite, "ObservationRecorder", return_value=recorder):
            failed = suite.execute(job, registry, "mock-adb", "a" * 64, output, leases)
        self.assertEqual("UNCONFIRMED", failed["result"]["cleanup_status"])
        self.assertFalse(list(leases.glob("phone-*.json")))
        remaining = list(leases.glob("capture-*.json"))
        self.assertEqual(1, len(remaining))
        self.assertEqual("QUARANTINED", suite.read(remaining[0])["status"])
        self.assertEqual(77, suite.read(remaining[0])["recorder_pid"])
        with self.assertRaises(suite.PrerequisiteError):
            with suite.DeviceLease(leases, registry, "cd", False, "two", job["capture_resources"]):
                self.fail("Still-running instrument was reused")
        suite.write(output / "suite-result.json", {"results": [failed["result"]]})
        with self.assertRaisesRegex(ValueError, "cleanup is unconfirmed"):
            suite.finalize_artifacts(output)

    def test_state_prioritizes_retests_and_failures_keeps_unavailable_selection_and_disjoint_batches(self):
        requested = [case("pending"), case("missing", "e", "f"), case("retest"), case("failed", "c", "d")]
        self.inputs(requested)
        suite.write(self.folder / "bundle.json", {"suites": [{"path": str(self.folder / "input.json"), "execution": "concurrent"}]})
        rows = [{"case_id": item["id"], "request_key": suite.request_key(item),
                 "phones": [item["source"], item["receivers"][0]["phone"]], "state": status}
                for item, status in zip(requested, ("NOT RUN", "NOT RUN", "NEEDS RETEST", "FAIL"))]
        state_path = self.folder / "state.json"
        suite.write(state_path, {"cases": rows})
        original = state_path.read_bytes()
        with patch.object(suite.subprocess, "run", side_effect=AssertionError("device I/O")), \
             patch.object(suite.subprocess, "Popen", side_effect=AssertionError("device process")):
            suite.main(["plan", "--suite", str(self.folder / "bundle.json"), "--devices", str(self.folder / "devices.json"),
                        "--phones", "a,b,c,d", "--state", str(state_path), "--parallel", "2", "--output", str(self.folder / "output")])
        planned = suite.read(self.folder / "output/plan.json")
        self.assertEqual(["retest", "failed", "pending"], [item["case"]["id"] for item in planned["jobs"]])
        self.assertEqual([["retest", "failed"], ["pending"]], planned["batches"])
        self.assertEqual([{"case_id": "missing", "missing_phones": ["e", "f"]}], planned["selection"]["unavailable"])
        self.assertEqual(4, planned["selection"]["inventory_cases"])
        self.assertEqual(original, state_path.read_bytes())

    def test_explicit_empty_phone_assignment_never_expands_offline_plan(self):
        self.inputs([case()])
        suite.write(self.folder / "bundle.json", {"suites": [{"path": str(self.folder / "input.json")}]})
        for with_state in (False, True):
            output = self.folder / f"paused-plan-{with_state}"
            args = ["plan", "--suite", str(self.folder / "bundle.json"), "--devices", str(self.folder / "devices.json"),
                    "--phones=", "--output", str(output)]
            if with_state:
                suite.write(self.folder / "state.json", {"cases": [{"case_id": "one", "request_key": suite.request_key(case()),
                    "phones": ["a", "b"], "state": "NOT RUN"}]})
                args += ["--state", str(self.folder / "state.json")]
            with self.subTest(with_state=with_state), patch.object(suite, "preflight", side_effect=AssertionError("Phones paused")):
                self.assertEqual(0, suite.main(args))
            planned = json.loads((output / "plan.json").read_text())
            self.assertEqual([], planned["jobs"])
            self.assertEqual(0, planned["duration_budget"]["selected_media_seconds"])
            self.assertEqual(10, planned["duration_budget"]["inventory_media_seconds"])
            self.assertEqual(0, planned["selection"]["selected_cases"])
            self.assertGreater(planned["selection"]["inventory_cases"], 0)
            self.assertTrue(planned["selection"]["unavailable"])

    def test_state_forged_pass_is_not_skipped(self):
        requested = case()
        registry = self.inputs([requested])
        row = self.recorded_state_row(requested, registry)
        row["last_result"]["manifest"] = str(self.folder / "missing-manifest.json")
        suite.write(self.folder / "state.json", {"cases": [row]})
        with patch.object(suite, "execute", side_effect=self.blocked_mock_attempt) as execute, \
             patch.object(suite.subprocess, "run", side_effect=AssertionError("device I/O")), \
             patch.object(suite.subprocess, "Popen", side_effect=AssertionError("device process")):
            suite.main([*self.run_args(), "--state", str(self.folder / "state.json")])
        self.assertEqual(1, execute.call_count)
        self.assertEqual([], suite.read(self.folder / "output/selection.json")["completed_skipped"])
        self.assertEqual("BLOCKED", suite.read(self.folder / "output/suite-result.json")["results"][0]["status"])

    def test_changed_registry_identity_cannot_skip_same_alias_old_pass(self):
        requested = case()
        registry = self.inputs([requested])
        row = self.recorded_state_row(requested, registry)
        suite.write(self.folder / "state.json", {"cases": [row]})
        registry["b"]["node_prefix"] = "abcdef012345"
        suite.write(self.folder / "devices.json", {"devices": registry})
        with patch.object(suite, "execute", side_effect=self.blocked_mock_attempt) as execute, \
             patch.object(suite.subprocess, "run", side_effect=AssertionError("device I/O")), \
             patch.object(suite.subprocess, "Popen", side_effect=AssertionError("device process")):
            suite.main([*self.run_args(), "--state", str(self.folder / "state.json")])
        self.assertEqual(1, execute.call_count)
        self.assertEqual([], suite.read(self.folder / "output/selection.json")["completed_skipped"])

    def test_state_verified_old_apk_pass_becomes_retest_and_is_not_skipped(self):
        requested = case()
        registry = self.inputs([requested])
        row = self.recorded_state_row(requested, registry)
        suite.write(self.folder / "state.json", {"cases": [row]})
        args = self.run_args()
        args[args.index("--apk-sha256") + 1] = "c" * 64
        with patch.object(suite, "execute", side_effect=self.blocked_mock_attempt) as execute, \
             patch.object(suite, "select_remaining", wraps=suite.select_remaining) as remaining, \
             patch.object(suite.subprocess, "run", side_effect=AssertionError("device I/O")), \
             patch.object(suite.subprocess, "Popen", side_effect=AssertionError("device process")):
            suite.main([*args, "--state", str(self.folder / "state.json")])
        self.assertEqual("NEEDS RETEST", remaining.call_args.args[0]["cases"][0]["state"])
        self.assertEqual(1, execute.call_count)
        self.assertEqual([], suite.read(self.folder / "output/selection.json")["completed_skipped"])

    def test_state_verified_same_apk_pass_is_skipped_without_device_work(self):
        requested = case()
        registry = self.inputs([requested])
        suite.write(self.folder / "state.json", {"cases": [self.recorded_state_row(requested, registry)]})
        with patch.object(suite, "execute", side_effect=AssertionError("completed case was executed")) as execute, \
             patch.object(suite.subprocess, "run", side_effect=AssertionError("device I/O")), \
             patch.object(suite.subprocess, "Popen", side_effect=AssertionError("device process")):
            suite.main([*self.run_args(), "--state", str(self.folder / "state.json")])
        execute.assert_not_called()
        selection = suite.read(self.folder / "output/selection.json")
        self.assertEqual([requested["id"]], selection["completed_skipped"])
        self.assertEqual(0, selection["selected_cases"])

    def test_copied_apk_state_metadata_cannot_hide_old_build_retest(self):
        requested = case()
        registry = self.inputs([requested])
        row = self.recorded_state_row(requested, registry)
        row["last_verified_apk"] = {phone: "c" * 64 for phone in row["phones"]}
        suite.write(self.folder / "state.json", {"cases": [row]})
        args = self.run_args()
        args[args.index("--apk-sha256") + 1] = "c" * 64
        with patch.object(suite, "execute", side_effect=self.blocked_mock_attempt) as execute, \
             patch.object(suite.subprocess, "run", side_effect=AssertionError("device I/O")), \
             patch.object(suite.subprocess, "Popen", side_effect=AssertionError("device process")):
            suite.main([*args, "--state", str(self.folder / "state.json")])
        self.assertEqual(1, execute.call_count)
        self.assertEqual([], suite.read(self.folder / "output/selection.json")["completed_skipped"])

    def test_offline_plan_rechecks_sidecar_bytes_before_skipping_saved_pass(self):
        raw, frames = self.folder / "native.yuv", self.folder / "frames.csv"
        raw.write_bytes(b"\x00" * (720 * 480 * 3 // 2))
        frames.write_bytes(b"frame_id\n0\n")
        requested = {**case(), "raw_fixture_file": str(raw), "raw_fixture_sha256": suite.digest(raw),
                     "raw_fixture_metadata": {"sidecars": {"frames": {
                         "path": str(frames), "sha256": suite.digest(frames)}}}}
        registry = self.inputs([requested])
        row = self.recorded_state_row(requested, registry)
        manifest_path = Path(row["last_result"]["manifest"])
        manifest = suite.read(manifest_path)
        manifest["source_fixture"] = {"path": str(raw), "sha256": suite.digest(raw),
                                      "declared_metadata": copy.deepcopy(requested["raw_fixture_metadata"])}
        suite.write(manifest_path, manifest)
        row["last_result"]["manifest_sha256"] = suite.digest(manifest_path)
        suite.write(self.folder / "state.json", {"cases": [row]})
        frames.write_bytes(b"frame_id\n1\n")
        with patch.object(suite.subprocess, "run", side_effect=AssertionError("device I/O")), \
             patch.object(suite.subprocess, "Popen", side_effect=AssertionError("device process")), \
             patch.object(suite, "execute", side_effect=AssertionError("offline plan executed a case")):
            self.assertEqual(0, suite.main(["plan", "--suite", str(self.folder / "input.json"),
                "--devices", str(self.folder / "devices.json"), "--state", str(self.folder / "state.json"),
                "--phones", "a,b", "--apk-sha256", "a" * 64, "--output", str(self.folder / "output")]))
        planned = suite.read(self.folder / "output/plan.json")
        self.assertEqual(1, planned["cases"])
        self.assertEqual([], planned["selection"]["completed_skipped"])

    def test_unknown_state_blocks_offline_selection_before_any_execution(self):
        requested = case()
        self.inputs([requested])
        row = {"case_id": requested["id"], "request_key": suite.request_key(requested),
               "phones": ["a", "b"], "state": "SILENTLY_DONE"}
        suite.write(self.folder / "state.json", {"cases": [row]})
        with patch.object(suite, "execute", side_effect=AssertionError("Unknown state reached execution")) as execute, \
             patch.object(suite.subprocess, "run", side_effect=AssertionError("device I/O")), \
             patch.object(suite.subprocess, "Popen", side_effect=AssertionError("device process")):
            with self.assertRaises(ValueError):
                suite.main([*self.run_args(), "--state", str(self.folder / "state.json")])
        execute.assert_not_called()
        self.assertFalse((self.folder / "output").exists())

    def test_simultaneous_alias_lease_requests_have_exactly_one_owner(self):
        registry = devices("ab")
        registry["b"]["node_prefix"] = registry["a"]["node_prefix"]
        leases = self.folder / "leases"
        ready = threading.Barrier(2)
        rejected = threading.Event()
        outcomes = []
        errors = []
        def compete(phone):
            try:
                ready.wait(timeout=2)
                try:
                    with suite.DeviceLease(leases, registry, [phone], False, "owner-" + phone):
                        outcomes.append("owned")
                        self.assertTrue(rejected.wait(timeout=2), "Competing request did not finish while lease was held")
                except suite.PrerequisiteError:
                    outcomes.append("blocked")
                    rejected.set()
            except BaseException as error:
                errors.append(error)
        workers = [threading.Thread(target=compete, args=(phone,)) for phone in "ab"]
        for worker in workers:
            worker.start()
        for worker in workers:
            worker.join(timeout=4)
        self.assertFalse(any(worker.is_alive() for worker in workers))
        self.assertEqual([], errors)
        self.assertCountEqual(["owned", "blocked"], outcomes)
        self.assertEqual([], list(leases.iterdir()))

    def test_batches_are_disjoint_and_cannot_cross_an_isolation_barrier(self):
        def job(identifier, phones, execution="concurrent"):
            return {"case": {"id": identifier}, "phones": list(phones), "execution": execution}
        jobs = [job("first", "ab"), job("conflict", "bc"), job("independent", "de"),
                job("isolated", "af", "isolated"), job("after", "cd")]
        batches = list(suite.batches(jobs, 3))
        self.assertEqual([["first", "independent"], ["conflict"], ["isolated"], ["after"]],
                         [[j["case"]["id"] for j in batch] for batch in batches])
        for batch in batches:
            phones = [p for job in batch for p in job["phones"]]
            self.assertEqual(len(phones), len(set(phones)))
        self.assertEqual([[j] for j in jobs], list(suite.batches(jobs, 1)))

    def scheduling_job(self, identifier, phones, execution="concurrent", state="NOT RUN"):
        return {"case": case(identifier, *phones), "phones": list(phones), "mode": "broadcast",
                "execution": execution, "state": state}

    def test_ready_selection_is_disjoint_read_only_and_obeys_isolation_barrier(self):
        active = [self.scheduling_job("active", "ab")]
        conflict = self.scheduling_job("conflict", "ab")
        independent = self.scheduling_job("independent", "cd")
        isolated = self.scheduling_job("isolated", "ef", "isolated")
        after = self.scheduling_job("after", "ef")
        waiting = [conflict, independent, isolated, after]
        before = copy.deepcopy((waiting, active))
        chosen = suite.schedule_ready(waiting, active, {}, {}, 2)
        self.assertIs(independent, chosen)
        self.assertEqual(before, (waiting, active))
        self.assertIsNone(suite.schedule_ready([conflict, isolated, after], active, {}, {}, 2))
        self.assertIsNone(suite.schedule_ready([isolated], active, {}, {}, 2))
        self.assertIs(isolated, suite.schedule_ready([isolated, after], [], {}, {}, 2))
        self.assertIsNone(suite.schedule_ready([independent], [isolated], {}, {}, 2))
        self.assertIsNone(suite.schedule_ready([independent], active, {}, {}, 1))

    def test_ready_selection_prioritizes_retest_and_failure_before_unused_phones(self):
        pending = self.scheduling_job("pending", "ab")
        retest = self.scheduling_job("retest", "cd", state="NEEDS RETEST")
        failed = self.scheduling_job("failed", "ef", state="FAIL")
        waiting = [pending, retest, failed]
        usage = {phone: value for phone, value in zip("abcdef", (0, 0, 10, 10, 5, 5))}
        self.assertIs(failed, suite.schedule_ready(waiting, [], usage, {}, 2))
        self.assertIs(retest, suite.schedule_ready(waiting, [self.scheduling_job("occupied", "ef")], usage, {}, 2))

    def test_ready_selection_rotates_phone_use_then_roles_with_stable_ties(self):
        first = self.scheduling_job("first", "ab")
        other = self.scheduling_job("other", "cd")
        waiting = [first, other]
        self.assertIs(other, suite.schedule_ready(waiting, [], {"a": 10, "b": 0, "c": 6, "d": 6}, {}, 2))
        roles = {("a", "sender"): 5, ("b", "receiver"): 5}
        self.assertIs(other, suite.schedule_ready(waiting, [], {}, roles, 2))
        self.assertIs(first, suite.schedule_ready(waiting, [], {}, {}, 2))

    def test_ready_selection_finishes_phone_fairness_before_role_fairness(self):
        first = self.scheduling_job("first", "ab")
        other = self.scheduling_job("other", "cd")
        usage = {"a": 6, "b": 0, "c": 6, "d": 6}
        roles = {("a", "sender"): 10, ("b", "receiver"): 10}
        self.assertIs(first, suite.schedule_ready([first, other], [], usage, roles, 2))

    def test_execution_refills_freed_phones_before_other_disjoint_job_finishes(self):
        requests = [case("first"), case("slow", "c", "d"), case("refill")]
        self.inputs(requests)
        suite.write(self.folder / "bundle.json", {"suites": [{"path": str(self.folder / "input.json"),
                                                                "execution": "concurrent"}]})
        slow_started, refill_started, slow_finished = threading.Event(), threading.Event(), threading.Event()
        occupied, overlap_errors, observations = set(), [], []
        lock = threading.Lock()
        def execute(job, *_, **__):
            identifier, phones = job["case"]["id"], set(job["phones"])
            with lock:
                if occupied.intersection(phones):
                    overlap_errors.append(identifier)
                occupied.update(phones)
            try:
                if identifier == "first":
                    self.assertTrue(slow_started.wait(timeout=2), "Other worker did not start")
                elif identifier == "slow":
                    slow_started.set()
                    self.assertTrue(refill_started.wait(timeout=2), "Freed phones stayed idle until the batch ended")
                else:
                    observations.append(not slow_finished.is_set())
                    refill_started.set()
                return self.blocked_mock_attempt(job)
            finally:
                with lock:
                    occupied.difference_update(phones)
                if identifier == "slow":
                    slow_finished.set()
        args = self.run_args()
        args[args.index("--suite") + 1] = str(self.folder / "bundle.json")
        with patch.object(suite, "execute", side_effect=execute), \
             patch.object(suite.subprocess, "run", side_effect=AssertionError("device I/O")), \
             patch.object(suite.subprocess, "Popen", side_effect=AssertionError("device process")):
            suite.main([*args, "--parallel", "2"])
        self.assertEqual([True], observations)
        self.assertEqual([], overlap_errors)
        self.assertTrue(refill_started.is_set())
        self.assertEqual({"BLOCKED"}, {item["status"] for item in suite.read(self.folder / "output/suite-result.json")["results"]})

    def test_alias_collision_between_separate_cases_is_rejected_before_execution(self):
        registry = self.inputs([case(), case("two", "c", "d")])
        for field in ("serial", "node_prefix"):
            with self.subTest(field=field):
                collided = copy.deepcopy(registry)
                collided["c"][field] = collided["a"][field]
                with self.assertRaises(ValueError):
                    suite.load_jobs(self.folder / "input.json", collided)

    def test_physical_alias_lease_and_radio_window_block_other_owners(self):
        registry = devices("abcd")
        registry["c"]["node_prefix"] = registry["a"]["node_prefix"]
        leases = self.folder / "leases"
        with suite.DeviceLease(leases, registry, ["a"], False, "owner-one"):
            with self.assertRaises(suite.PrerequisiteError):
                with suite.DeviceLease(leases, registry, ["c"], False, "owner-two"):
                    self.fail("Alias reached an occupied physical phone")
            with self.assertRaises(suite.PrerequisiteError):
                with suite.DeviceLease(leases, registry, ["d"], True, "isolated"):
                    self.fail("Isolated case entered an occupied radio window")
        with suite.DeviceLease(leases, registry, ["a"], True, "isolated"):
            with self.assertRaises(suite.PrerequisiteError):
                with suite.DeviceLease(leases, registry, ["d"], False, "other"):
                    self.fail("Concurrent case entered an isolated window")
        self.assertEqual([], list(leases.iterdir()))

    def test_lease_releases_after_interrupted_body(self):
        leases = self.folder / "leases"
        with self.assertRaises(KeyboardInterrupt):
            with suite.DeviceLease(leases, devices("ab"), ["a", "b"], True, "interrupted"):
                raise KeyboardInterrupt()
        self.assertEqual([], list(leases.iterdir()))

    def test_lease_acquisition_rolls_back_if_interrupted_after_first_write(self):
        leases = self.folder / "leases"
        original_write = suite.write
        calls = 0
        def interrupted_write(path, value):
            nonlocal calls
            calls += 1
            original_write(path, value)
            if calls == 2:
                raise KeyboardInterrupt()
        with patch.object(suite, "write", side_effect=interrupted_write):
            with self.assertRaises(KeyboardInterrupt):
                with suite.DeviceLease(leases, devices("ab"), ["a", "b"], False, "interrupted"):
                    self.fail("Interrupted acquisition entered its body")
        self.assertEqual([], list(leases.iterdir()))

    def test_blocked_attempts_without_comparison_keys_do_not_match_baselines(self):
        current = result("current", "BLOCKED")
        previous = result("previous", "BLOCKED")
        payload = suite.report(self.folder, [current], {"results": [previous]})
        self.assertEqual("UNMATCHED", payload["results"][0]["baseline"]["status"])
        self.assertNotIn("previous_status", payload["results"][0]["baseline"])

    def test_baseline_strong_label_requires_frozen_input_for_every_sender(self):
        camera = {**result("camera", "UNMEASURED", "c" * 64), "source_kind": "live_camera",
                  "scenario": case()}
        raw = {**camera, "source_kind": "raw_fixture_live_encoder", "source_fixture": {"sha256": "f" * 64}}
        malformed = {**raw, "source_fixture": {"sha256": "unverified"}}
        call = {**camera, "scenario": {"id": "call", "caller": "a", "callee": "b"},
                "source_kind": "mixed_camera_recorded_motion", "source_fixtures": {"a": {"sha256": "f" * 64}}}
        both = {**call, "source_kind": "recorded_motion_bidirectional",
                "source_fixtures": {"a": {"sha256": "f" * 64}, "b": {"sha256": "e" * 64}}}
        wrong_keys = {**both, "source_fixtures": {"a": {"sha256": "f" * 64}, "unrelated": {"sha256": "e" * 64}}}
        for value, label in ((camera, "MATCHED_REQUEST_ONLY"), (raw, "MATCHED_INPUT_AND_REQUEST"),
                             (malformed, "MATCHED_REQUEST_ONLY"), (call, "MATCHED_REQUEST_ONLY"),
                             (both, "MATCHED_INPUT_AND_REQUEST"), (wrong_keys, "MATCHED_REQUEST_ONLY")):
            with self.subTest(kind=value["source_kind"], fixture_count=len(value.get("source_fixtures", {}))):
                current, previous = copy.deepcopy(value), copy.deepcopy(value)
                suite.report(self.folder, [current], {"results": [previous]})
                self.assertEqual(label, current["baseline"]["status"])
        for previous in ({**raw, "source_fixture": {}}, {**raw, "source_fixture": {"sha256": "e" * 64}}):
            current = copy.deepcopy(raw)
            suite.report(self.folder, [current], {"results": [previous]})
            self.assertEqual("MATCHED_REQUEST_ONLY", current["baseline"]["status"])

    def test_blocked_preflight_does_not_launch_runner_or_hold_lease(self):
        registry = self.inputs([case()])
        job = {**suite.load_jobs(self.folder / "input.json", registry)[0], "owner": "offline-test"}
        leases = self.folder / "leases"
        with patch.object(suite, "preflight", side_effect=suite.PrerequisiteError("offer is unavailable")), \
             patch.object(suite, "Runner", side_effect=AssertionError("runner must stay idle")) as runner:
            attempted = suite.execute(job, registry, "adb", "a" * 64, self.folder / "attempt", leases)
        self.assertEqual("BLOCKED", attempted["result"]["status"])
        self.assertNotIn("comparison_key", attempted["result"])
        runner.assert_not_called()
        self.assertEqual([], list(leases.iterdir()))

    def test_a_case_blocked_for_heat_is_kept_with_its_reason_and_never_launches(self):
        registry = self.inputs([case()])
        job = {**suite.load_jobs(self.folder / "input.json", registry)[0], "owner": "offline-test"}
        leases = self.folder / "leases"
        with patch.object(suite, "preflight", side_effect=suite.ThermalBlocked("thermal: a status 4")), \
             patch.object(suite, "Runner", side_effect=AssertionError("runner must stay idle")):
            attempted = suite.execute(job, registry, "adb", "a" * 64, self.folder / "attempt", leases)
        self.assertEqual(("BLOCKED", "thermal"), (attempted["result"]["status"], attempted["result"]["blocked_reason"]))
        self.assertEqual("thermal: a status 4", attempted["result"]["reason"])
        self.assertEqual([], list(leases.iterdir()))

    def test_broadcast_checks_sender_offer_without_requiring_receiver_encoder(self):
        exports = capabilities(devices("ab"))
        exports["b"]["encoders"] = []
        suite.validate_capabilities(case(), exports)
        for mutation in (lambda caps: caps["a"]["encoders"].clear(),
                         lambda caps: caps["a"]["encoders"][0].update(rate_controls=["automatic"]),
                         lambda caps: caps["a"]["encoders"][0]["modes"][0].update(fps=[15]),
                         lambda caps: caps["a"]["encoders"][0]["modes"][0].update(width=640),
                         lambda caps: caps["a"]["encoders"][0]["modes"][0].update(bitrate_kbps=900),
                         lambda caps: caps["b"].update(decoders=["av1"])):
            invalid = copy.deepcopy(exports)
            mutation(invalid)
            with self.assertRaises(suite.PrerequisiteError):
                suite.validate_capabilities(case(), invalid)

    def test_a_wifi_exact_rate_needs_an_offered_geometry_but_not_a_settings_step(self):
        exports = capabilities(devices("ab"))
        for phone in "ab":
            exports[phone]["transport_max_kbps"] = {"gatt": 1100}
        fast = {**case(), "transport": "gatt", "carrier": "wifi_direct",
                "profile": {**case()["profile"], "bitrate_kbps": 3000}, "tunables": {"wifi_exact_kbps": 3000}}
        suite.validate_capabilities(fast, exports)
        for invalid in ({**fast, "tunables": {}}, {**fast, "tunables": {"wifi_exact_kbps": 2000}},
                        {key: value for key, value in fast.items() if key != "carrier"},
                        {**fast, "profile": {**fast["profile"], "width": 1280}}):
            with self.subTest(invalid=invalid), self.assertRaises(suite.PrerequisiteError):
                suite.validate_capabilities(invalid, exports)

    def test_private_call_checks_both_independent_outgoing_encoders(self):
        exports = capabilities(devices("ab"))
        call = {"id": "call", "caller": "a", "callee": "b", "transport": "classic", "duration_s": 10,
                "profiles": {p: case()["profile"] for p in "ab"}}
        suite.validate_capabilities(call, exports)
        exports["b"]["encoders"] = []
        with self.assertRaises(suite.PrerequisiteError):
            suite.validate_capabilities(call, exports)

    def test_finite_replay_checks_actual_offered_codec_and_keeps_hash_bound_baseline(self):
        fixture = self.folder / "finite.ivf"
        fixture.write_bytes(struct.pack("<4sHH4sHHIIII", b"DKIF", 0, 32, b"AV01", 720, 480, 30, 1, 1501, 0) +
                            b"".join(struct.pack("<IQ", 1, index) + b"\x00" for index in range(1501)))
        requested = {**case(), "replay_file": str(fixture), "replay_sha256": suite.digest(fixture)}
        exports = capabilities(devices("ab"))
        with patch.object(suite.subprocess, "run", side_effect=AssertionError("device I/O")), \
             patch.object(suite.subprocess, "Popen", side_effect=AssertionError("device process")):
            with self.assertRaisesRegex(suite.PrerequisiteError, "replay codec"):
                suite.validate_capabilities(requested, exports)
            exports["a"]["encoders"][0]["codec"] = "av1"
            exports["b"]["decoders"] = ["av1"]
            suite.validate_capabilities(requested, exports)
        manifest = {"scenario": requested, "source_kind": "preencoded_video_only",
                    "replay_fixture": suite.replay_fixture_metadata(requested), "devices": devices("ab")}
        original_key = suite.comparison_key(manifest)
        current = {**result("replay", "UNMEASURED", original_key), **manifest}
        previous = copy.deepcopy(current)
        suite.report(self.folder, [current], {"results": [previous]})
        self.assertEqual("MATCHED_INPUT_AND_REQUEST", current["baseline"]["status"])
        manifest["replay_fixture"]["sha256"] = "f" * 64
        self.assertNotEqual(original_key, suite.comparison_key(manifest))

    def test_suite_rejects_unsupported_private_call_replay(self):
        profile = case()["profile"]
        requested = {"id": "call-replay", "caller": "a", "callee": "b", "transport": "classic", "duration_s": 10,
                     "profiles": {"a": profile, "b": dict(profile)}, "replay_files": {"a": "never-staged.ivf"}}
        registry = self.inputs([requested])
        with patch.object(suite.subprocess, "run", side_effect=AssertionError("device I/O")), \
             patch.object(suite.subprocess, "Popen", side_effect=AssertionError("device process")):
            with self.assertRaisesRegex(ValueError, "replay.*not supported.*calls"):
                suite.load_jobs(self.folder / "input.json", registry)

    def test_private_call_checks_each_endpoints_own_playback_buffer(self):
        exports = capabilities(devices("ab"))
        exports["a"]["playback"], exports["b"]["playback"] = ["low"], ["high"]
        call = {"id": "call", "caller": "a", "callee": "b", "transport": "classic", "duration_s": 10,
                "profiles": {"a": {**case()["profile"], "playback": "low"},
                             "b": {**case()["profile"], "playback": "high"}}}
        original = copy.deepcopy(call)
        suite.validate_capabilities(call, exports)
        self.assertEqual(original, call)
        for phone in "ab":
            invalid = copy.deepcopy(exports)
            invalid[phone]["playback"] = ["balanced"]
            with self.subTest(phone=phone), self.assertRaises(suite.PrerequisiteError):
                suite.validate_capabilities(call, invalid)

    def test_loading_many_cases_hashes_the_authoritative_suite_once(self):
        registry = self.inputs([case("one"), case("two", "c", "d"), case("three", "e", "f")])
        with patch.object(suite, "digest", wraps=suite.digest) as digest:
            jobs = suite.load_jobs(self.folder / "input.json", registry)
        digest.assert_called_once_with((self.folder / "input.json").resolve())
        self.assertEqual(3, len(jobs))
        self.assertEqual({suite.digest(self.folder / "input.json")}, {job["suite_sha256"] for job in jobs})

    def test_explicit_empty_bundle_selection_cannot_expand_to_every_case(self):
        registry = self.inputs([case("one"), case("two", "c", "d")])
        bundle = self.folder / "bundle.json"
        suite.write(bundle, {"suites": [{"path": str(self.folder / "input.json"), "case_ids": []}]})
        with patch.object(suite.subprocess, "run", side_effect=AssertionError("device I/O")), \
             patch.object(suite.subprocess, "Popen", side_effect=AssertionError("device process")), \
             patch.object(suite, "execute", side_effect=AssertionError("empty selection reached execution")):
            with self.assertRaisesRegex(ValueError, "No cases selected"):
                suite.load_jobs(bundle, registry)
            with self.assertRaisesRegex(ValueError, "Selected cases absent"):
                suite.load_jobs(bundle, registry, ["one"])

    def test_isolated_preflight_cannot_treat_offline_peer_as_quiet(self):
        registry = devices("abc")
        exports = capabilities(registry)
        permissions = "\n".join("android.permission." + p + ": granted=true" for p in
                                  ("CAMERA", "RECORD_AUDIO", "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE"))
        def command(argv, **_):
            phone = argv[argv.index("-s") + 1].removeprefix("serial-")
            operation = argv[3:]
            if phone == "c":
                return subprocess.CompletedProcess(argv, 1, "", "error: device offline")
            text = {("get-state",): "device", ("shell", "settings", "get", "global", "bluetooth_on"): "1",
                    ("shell", "dumpsys", "window", "policy"): "KeyguardServiceDelegate\n showing=false\n inputRestricted=false",
                    ("shell", "dumpsys", "power"): "mWakefulness=Awake",
                    ("shell", "dumpsys", "package", suite.PACKAGE): permissions,
                    ("shell", "getprop", "ro.build.version.sdk"): "36",
                    ("shell", "pm", "path", suite.PACKAGE): "package:/data/app/base.apk",
                    ("shell", "sha256sum", "/data/app/base.apk"): "a" * 64 + " /data/app/base.apk",
                    ("shell", "getprop", "ro.build.fingerprint"): "fingerprint-" + phone,
                    ("shell", "cat", f"/sdcard/Android/data/{suite.PACKAGE}/files/benchmark-capabilities.json"): json.dumps(exports[phone])}
            if tuple(operation) not in text:
                self.fail("Unexpected mocked preflight command: " + repr(operation))
            return subprocess.CompletedProcess(argv, 0, text[tuple(operation)], "")
        job = {"case": case(), "phones": ["a", "b"], "execution": "isolated", "owner": "offline-test"}
        with patch.object(suite.subprocess, "run", side_effect=command):
            with self.assertRaises(suite.PrerequisiteError):
                suite.preflight("adb", registry, job, self.folder, "a" * 64)

    def test_preflight_names_bluetooth_off_as_the_reason_a_phone_is_not_ready(self):
        registry = devices("ab")
        permissions = "\n".join("android.permission." + p + ": granted=true" for p in
                                  ("CAMERA", "RECORD_AUDIO", "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE"))
        def command(argv, **_):
            phone = argv[argv.index("-s") + 1].removeprefix("serial-")
            text = {("get-state",): "device",
                    ("shell", "settings", "get", "global", "bluetooth_on"): "0" if phone == "a" else "1",
                    ("shell", "dumpsys", "window", "policy"): "KeyguardServiceDelegate\n showing=false\n inputRestricted=false",
                    ("shell", "dumpsys", "power"): "mWakefulness=Awake",
                    ("shell", "dumpsys", "package", suite.PACKAGE): permissions,
                    ("shell", "getprop", "ro.build.version.sdk"): "36"}
            return subprocess.CompletedProcess(argv, 0, text.get(tuple(argv[3:]), ""), "")
        job = {"case": case(), "phones": ["a", "b"], "execution": "concurrent", "owner": "offline-test"}
        with patch.object(suite.subprocess, "run", side_effect=command):
            with self.assertRaisesRegex(suite.PrerequisiteError, r"a: not ready; Bluetooth off$"):
                suite.preflight("adb", registry, job, self.folder, "a" * 64)

    def test_not_ready_reason_lists_every_cause_and_only_the_real_ones(self):
        self.assertEqual("a: not ready; denied permissions=['CAMERA']",
                         suite.not_ready_reason("a", {"adb_state": "device", "bluetooth_on": "1"}, ["CAMERA"]))
        self.assertEqual("a: not ready; Bluetooth off; denied permissions=['CAMERA']",
                         suite.not_ready_reason("a", {"adb_state": "device", "bluetooth_on": "0"}, ["CAMERA"]))
        self.assertIn("adb state='offline'",
                      suite.not_ready_reason("a", {"adb_state": "offline", "bluetooth_on": "1"}, []))

    def test_interrupt_report_retains_active_and_not_started_cases(self):
        self.inputs([case(), case("two", "c", "d"), case("three", "e", "f")])
        seen = []
        def interrupted(job, *_, **__):
            seen.append(job["case"]["id"])
            initial = suite.read(self.folder / "output/suite-result.json")
            self.assertEqual({"one", "two", "three"}, {x["case_id"] for x in initial["results"]})
            raise KeyboardInterrupt()
        with patch.object(suite, "execute", side_effect=interrupted), patch.object(suite, "analyze") as analysis:
            try:
                suite.main(self.run_args())
            except KeyboardInterrupt:
                pass
        payload = suite.read(self.folder / "output/suite-result.json")
        states = {x["case_id"]: x["status"] for x in payload["results"]}
        self.assertEqual({"one": "INTERRUPTED", "two": "NOT RUN", "three": "NOT RUN"}, states)
        self.assertEqual(["one"], seen)
        analysis.assert_not_called()
        self.assertEqual(3, len(suite.read(self.folder / "output/coverage.json")["cases"]))

    def test_scoring_exception_keeps_failed_case_in_final_report(self):
        self.inputs([case()])
        def executed(job, _devices, _adb, _hash, output, _leases, *_, **__):
            manifest = output / "physical/manifest.json"
            suite.write(manifest, {"scenario": job["case"], "run_id": "recorded-one", "source_kind": "live_camera",
                                   "devices": {}, "logs": []})
            return {"manifest": manifest, "job": job, "output": output,
                    "host_start_ns": 1, "host_end_ns": 2}
        with patch.object(suite, "execute", side_effect=executed), patch.object(suite, "analyze", side_effect=RuntimeError("scoring failure proof")):
            suite.main(self.run_args())
        payload = suite.read(self.folder / "output/suite-result.json")
        self.assertEqual(["one"], [x["case_id"] for x in payload["results"]])
        self.assertEqual("FAIL", payload["results"][0]["status"])
        self.assertIn("scoring failure proof", json.dumps(payload["results"][0]))

    def test_analyze_uses_recorded_adapters_without_device_operations(self):
        manifest = {"scenario": case(), "run_id": "recorded", "source_kind": "raw_fixture_live_encoder", "devices": {}}
        suite.write(self.folder / "manifest.json", manifest)
        with patch.object(suite, "score_manifest", return_value={"devices": [], "overall_status": "UNMEASURED"}), \
             patch.object(suite, "score_motion", return_value={"timing_state": "UNMEASURED", "capture_state": "COMPLETE"}), \
             patch.object(suite, "score_phase_delivery", return_value={"quality": {}}), \
             patch.object(suite, "preflight", side_effect=AssertionError("device I/O")) as preflight, \
             patch.object(suite, "Runner", side_effect=AssertionError("device I/O")) as runner, \
             patch.object(suite, "CallRunner", side_effect=AssertionError("device I/O")) as call_runner, \
             patch.object(suite.subprocess, "run", side_effect=AssertionError("unexpected process")):
            analyzed = suite.analyze(self.folder / "manifest.json", self.folder / "analysis")
        self.assertEqual("RECORDED_EXECUTION_ANALYSIS", analyzed["origin"])
        for adapter in (preflight, runner, call_runner):
            adapter.assert_not_called()
        self.assertEqual("UNMEASURED", analyzed["families"]["physical_latency"]["status"])
        self.assertFalse((self.folder / "analysis/.debug-images").exists())
        self.assertFalse((self.folder / "analysis/debug-image-lifecycle.json").exists())

    def test_analysis_consumes_new_debug_image_without_mutating_original_source_evidence(self):
        source, output = self.folder / "source", self.folder / "analysis"
        source.mkdir()
        manifest_path = source / "manifest.json"
        suite.write(manifest_path, {"scenario": case(), "devices": {}})
        original_images = suite.DebugImages(source)
        original = original_images.reserve("original.png", "Still needed for an unresolved source visual defect")
        original.write_bytes(b"original source pixels")
        original_images.register("original.png")
        frozen = {path: path.read_bytes() for path in (manifest_path, original, original_images.index)}

        def score(*_):
            images = suite.DebugImages(output)
            capture = images.reserve("review.png", "Temporary analysis visualization")
            capture.write_bytes(b"temporary analysis pixels")
            images.register("review.png")
            with images.review("review.png") as reviewed:
                self.assertEqual(b"temporary analysis pixels", reviewed.read_bytes())
            return {"devices": [], "overall_status": "UNMEASURED"}

        with patch.object(suite, "score_manifest", side_effect=score), \
             patch.object(suite, "score_motion", return_value={"timing_state": "UNMEASURED", "capture_state": "COMPLETE"}), \
             patch.object(suite, "score_phase_delivery", return_value={"quality": {}}), \
             patch.object(suite.subprocess, "run", side_effect=AssertionError("device or process I/O")):
            suite.analyze(manifest_path, output)
        self.assertFalse((output / ".debug-images/review.png").exists())
        self.assertEqual("DELETED", suite.read(output / "debug-image-lifecycle.json")["images"]["review.png"]["state"])
        self.assertEqual(frozen, {path: path.read_bytes() for path in frozen})

    def test_content_region_diagnostics_cannot_override_failed_primary_quality(self):
        manifest = {"scenario": case(), "run_id": "recorded", "source_kind": "raw_fixture_live_encoder", "devices": {}}
        suite.write(self.folder / "manifest.json", manifest)
        detail = {"measurement_status": "MEASURED", "acceptance_status": "UNMEASURED",
                  "evidence": "reference-regions.json", "sha256": "a" * 64}
        with patch.object(suite, "score_manifest", return_value={"devices": [], "overall_status": "PASS"}), \
             patch.object(suite, "score_motion", return_value={"timing_state": "UNMEASURED", "capture_state": "COMPLETE"}), \
             patch.object(suite, "score_phase_delivery", return_value={"measurement_status": "MEASURED",
                 "quality": {"vmaf": 10, "ssim": .2, "psnr_db": 10}, "content_regions": detail}):
            analyzed = suite.analyze(self.folder / "manifest.json", self.folder / "analysis")
        self.assertEqual([{**detail, "device": "b", "source": "a"}], analyzed["families"]["picture"]["content_regions"])
        self.assertEqual("FAIL", analyzed["families"]["picture"]["status"])
        self.assertEqual("FAIL", analyzed["status"])

    def test_failed_analysis_preserves_pending_debug_capture(self):
        manifest_path, output = self.folder / "manifest.json", self.folder / "analysis"
        suite.write(manifest_path, {"scenario": case(), "devices": {}})
        frozen = manifest_path.read_bytes()

        def failed(*_):
            images = suite.DebugImages(output)
            capture = images.reserve("failure.png", "Analysis interrupted before this diagnostic was reviewed")
            capture.write_bytes(b"unconsumed diagnostic pixels")
            images.register("failure.png")
            raise RuntimeError("analysis consumer failed")

        with patch.object(suite, "score_manifest", side_effect=failed), self.assertRaisesRegex(RuntimeError, "consumer failed"):
            suite.analyze(manifest_path, output)
        self.assertEqual(frozen, manifest_path.read_bytes())
        self.assertEqual(b"unconsumed diagnostic pixels", (output / ".debug-images/failure.png").read_bytes())
        self.assertEqual("PENDING", suite.read(output / "debug-image-lifecycle.json")["images"]["failure.png"]["state"])

    def test_analysis_cannot_finalize_debug_images_inside_original_source_output(self):
        manifest = self.folder / "manifest.json"
        suite.write(manifest, {"scenario": case()})
        with patch.object(suite, "_analyze", side_effect=AssertionError("unsafe analysis started")), \
             self.assertRaisesRegex(ValueError, "original input evidence"):
            suite.analyze(manifest, self.folder)

    def test_execution_finalizes_only_consumed_debug_capture_in_its_owned_output(self):
        output = self.folder / "attempt"

        def executed(*_):
            images = suite.DebugImages(output)
            for name in ("consumed.png", "pending.png"):
                images.reserve(name, "Review temporary execution setup").write_bytes(b"owned debug pixels")
                images.register(name)
            with patch.object(images, "finalize"):
                images.consume("consumed.png")
            (output / "reference.png").write_bytes(b"reference asset")
            return {"result": {"status": "BLOCKED"}}

        with patch.object(suite, "_execute", side_effect=executed), \
             patch.object(suite.subprocess, "run", side_effect=AssertionError("device I/O")):
            actual = suite.execute({}, {}, "unused-adb", "a" * 64, output, self.folder / "leases")
        self.assertEqual("BLOCKED", actual["result"]["status"])
        self.assertFalse((output / ".debug-images/consumed.png").exists())
        self.assertTrue((output / ".debug-images/pending.png").exists())
        self.assertEqual(b"reference asset", (output / "reference.png").read_bytes())

    def cancellation_during_connection(self, private):
        registry = devices("ab")
        request = case()
        if private:
            request = {"id": "call", "caller": "a", "callee": "b", "transport": "classic", "duration_s": 10,
                       "profiles": {p: case()["profile"] for p in "ab"}}
        request["connection_timeout_s"] = 30
        instance = (suite.CallRunner if private else suite.Runner)("unused-adb", registry, request, self.folder / "physical")
        instance.cancel_event = threading.Event()
        instance.inventory = Mock(side_effect=lambda phone: {**registry[phone], "apk_sha256": "a" * 64})
        instance.phone_time = Mock(return_value="10-03 01:00:00.000")
        instance.adb = Mock(return_value="")
        instance.beep = Mock()
        captures = []
        def launch(phone, source=False):
            log = instance.output / f"{phone}.log"
            log.write_text("")
            instance.logs.append({"device": phone, "role": "private" if private else "sender" if source else "receiver",
                                  "pid": 123, "start": "10-03 01:00:00.000", "path": log.name})
            instance.pids[phone] = "123"
            process, handle = Mock(), Mock()
            captures.append((process, handle))
            instance.captures.append((process, handle))
        instance.launch = Mock(side_effect=launch)
        def stop_on_first_wait(_seconds):
            self.assertFalse(instance.cancel_event.is_set(), "Runner slept again after cancellation")
            instance.cancel_event.set()
        started = time.perf_counter()
        with patch("time.sleep", side_effect=stop_on_first_wait) as sleep, \
             patch.object(suite.subprocess, "run", side_effect=AssertionError("real device I/O")), \
             patch.object(suite.subprocess, "Popen", side_effect=AssertionError("real device capture")):
            manifest = instance.run()
        self.assertLess(time.perf_counter() - started, 2)
        sleep.assert_called_once()
        self.assertIn("Suite stopped", manifest["runner_error"])
        self.assertEqual(manifest["runner_error"], suite.read(instance.output / "manifest.json")["runner_error"])
        self.assertNotEqual("PASS", suite.read(instance.output / "score.json")["overall_status"])
        cleanup = {call.args[0] for call in instance.adb.call_args_list if call.args[1:5] == ("shell", "am", "force-stop", suite.PACKAGE)}
        self.assertEqual({"a", "b"}, cleanup)
        for process, handle in captures:
            process.terminate.assert_called_once()
            process.wait.assert_called_once()
            handle.close.assert_called_once()

    def test_broadcast_runner_cancelled_while_waiting_finalizes_and_cleans_participants(self):
        self.cancellation_during_connection(private=False)

    def test_call_runner_cancelled_while_waiting_finalizes_and_cleans_participants(self):
        self.cancellation_during_connection(private=True)

    def preflight_copy(self, corrupt):
        evidence, error, _, pulled, apk, expected, unrelated = self.run_preflight(corrupt=corrupt)
        if corrupt:
            self.assertIsInstance(error, suite.PrerequisiteError)
        else:
            self.assertIsNone(error)
        self.assertEqual(b"not the pulled APK", unrelated.read_bytes())
        if corrupt:
            self.assertEqual(1, len(pulled))
            self.assertEqual(b"mismatched copy", pulled[0].read_bytes())
        else:
            self.assertEqual(2, len(pulled))
            self.assertTrue(all(not path.exists() for path in pulled))
            for phone in "ab":
                receipt = evidence[phone]["independent_apk_verification"]
                self.assertEqual(expected, receipt["sha256"])
                self.assertEqual(len(apk), receipt["bytes"])

    def run_preflight(self, corrupt=False, apk=b"offline test frozen APK bytes", tunables=None, pull_timeouts=0,
                      thermal=lambda phone, poll: 0, power=lambda phone: "mWakefulness=Awake", case_fields=None):
        """suite.preflight against a scripted adb. Returns (evidence, error, operations sent, pulled copies, apk,
        expected hash, an unrelated file that must survive)."""
        registry = devices("ab")
        exports = capabilities(registry)
        expected = hashlib.sha256(apk).hexdigest()
        sent, polls, timeouts_left = [], {}, [pull_timeouts]
        output = self.folder / "preflight"
        output.mkdir()
        unrelated = output / "retained-other.apk"
        unrelated.write_bytes(b"not the pulled APK")
        pulled = []
        permissions = "\n".join("android.permission." + p + ": granted=true" for p in
                                  ("CAMERA", "RECORD_AUDIO", "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE"))
        def command(argv, **_):
            phone = argv[argv.index("-s") + 1].removeprefix("serial-")
            operation = argv[3:]
            sent.append((phone, tuple(operation)))
            if operation == ["shell", "dumpsys", "thermalservice"]:
                polls[phone] = polls.get(phone, -1) + 1
                return subprocess.CompletedProcess(argv, 0, f"Thermal Status: {thermal(phone, polls[phone])}\n", "")
            if operation[0] == "pull" and timeouts_left[0] > 0:
                timeouts_left[0] -= 1
                raise subprocess.TimeoutExpired(argv, 30)
            if operation[0] == "pull":
                destination = Path(operation[2]).resolve()
                destination.relative_to(output.resolve())
                destination.write_bytes(b"mismatched copy" if corrupt else apk)
                pulled.append(destination)
                return subprocess.CompletedProcess(argv, 0, "1 file pulled", "")
            values = {("get-state",): "device", ("shell", "settings", "get", "global", "bluetooth_on"): "1",
                      ("shell", "dumpsys", "window", "policy"): "KeyguardServiceDelegate\n showing=false\n inputRestricted=false",
                      ("shell", "dumpsys", "power"): power(phone),
                      ("shell", "dumpsys", "package", suite.PACKAGE): permissions,
                      ("shell", "getprop", "ro.build.version.sdk"): "36",
                      ("shell", "input", "keyevent", "KEYCODE_WAKEUP"): "",
                      ("shell", "pm", "path", suite.PACKAGE): "package:/data/app/base.apk",
                      ("shell", "sha256sum", "/data/app/base.apk"): expected + " /data/app/base.apk",
                      ("shell", "getprop", "ro.build.fingerprint"): "fingerprint-" + phone,
                      ("shell", "cat", f"/sdcard/Android/data/{suite.PACKAGE}/files/benchmark-capabilities.json"): json.dumps(exports[phone]),
                      ("shell", "dumpsys", "bluetooth_manager"): {
                          "a": "  address: AA:AA:AA:AA:AA:01\n  Bonded devices:\n    BB:BB:BB:BB:BB:02 [ DUAL ] b\n",
                          "b": "  address: BB:BB:BB:BB:BB:02\n  Bonded devices:\n    AA:AA:AA:AA:AA:01 [ DUAL ] a\n",
                      }[phone]}
            self.assertIn(tuple(operation), values, "Unexpected mocked preflight command")
            return subprocess.CompletedProcess(argv, 0, values[tuple(operation)], "")
        job = {"case": {**case(), **({"tunables": tunables} if tunables else {}), **(case_fields or {})}, "phones": ["a", "b"],
               "execution": "concurrent", "owner": "offline-test"}
        suite.BUILD_TEST_EXTRAS.pop(expected, None)
        suite.VERIFIED_INSTALLS.clear()
        error = None
        with patch.object(suite.subprocess, "run", side_effect=command), patch.object(suite.time, "sleep"):
            try:
                suite.preflight("unused-adb", registry, job, output, expected)
            except suite.PrerequisiteError as caught:
                error = caught
        evidence = suite.read(output / "preflight.json") if (output / "preflight.json").exists() else None
        return evidence, error, sent, pulled, apk, expected, unrelated

    SAVER_ON = "mWakefulness=Awake\nBattery Saver is currently: ON\n"
    SAVER_OFF = "mWakefulness=Awake\nBattery Saver is currently: OFF\n"

    def test_battery_saver_on_blocks_a_wifi_case_in_the_preflight_and_names_the_phone(self):
        evidence, error, sent, *_ = self.run_preflight(
            tunables={"user_launch": True}, case_fields={"user_settings": {"wifi": True}},
            power=lambda phone: self.SAVER_ON if phone == "b" else self.SAVER_OFF)
        self.assertIsInstance(error, suite.BatterySaverBlocked)
        self.assertEqual("battery_saver_on", error.reason_code)
        self.assertTrue(str(error).startswith("b: battery_saver_on"), str(error))
        self.assertIs(False, evidence["a"]["battery_saver"])
        self.assertIs(True, evidence["b"]["battery_saver"])
        self.assertFalse([operation for who, operation in sent if who == "b" and operation[0] == "pull"])  # it stopped at b

    def test_battery_saver_does_not_block_a_case_that_asks_for_no_wifi(self):
        evidence, error, *_ = self.run_preflight(power=lambda phone: self.SAVER_ON)
        self.assertIsNone(error)
        self.assertTrue(all(evidence[phone]["battery_saver"] is True for phone in "ab"))  # recorded, not a block

    def test_a_wifi_case_with_battery_saver_off_is_not_blocked(self):
        evidence, error, *_ = self.run_preflight(tunables={"user_launch": True}, case_fields={"user_settings": {"wifi": True}},
                                                 power=lambda phone: self.SAVER_OFF)
        self.assertIsNone(error)
        self.assertIs(False, evidence["a"]["battery_saver"])

    def test_a_wifi_case_whose_power_dump_has_no_battery_saver_line_is_unknown_not_blocked(self):
        evidence, error, *_ = self.run_preflight(tunables={"user_launch": True}, case_fields={"user_settings": {"wifi": True}})
        self.assertIsNone(error)
        self.assertIsNone(evidence["a"]["battery_saver"])

    def test_the_manifest_records_battery_saver_per_device_from_the_preflight_text(self):
        manifest = self.folder / "manifest.json"
        suite.write(manifest, {"devices": {"a": {"serial": "x"}, "b": {"serial": "y"}}})
        suite.record_battery_saver(manifest, {"a": {"battery_saver": False}, "b": {"battery_saver": True}})
        recorded = suite.read(manifest)["devices"]
        self.assertEqual((False, True), (recorded["a"]["battery_saver"], recorded["b"]["battery_saver"]))
        suite.record_battery_saver(self.folder / "missing.json", {})  # no manifest, nothing to record

    def test_a_pull_that_times_out_after_the_phone_woke_from_doze_is_retried_once(self):
        evidence, error, sent, pulled, *_ = self.run_preflight(pull_timeouts=1)
        self.assertIsNone(error)
        for phone in "ab":
            self.assertEqual({"attempts": 2 if phone == "a" else 1, "woke": True},  # one scripted timeout, on the first pull
                             {key: evidence[phone]["apk_pull"][key] for key in ("attempts", "woke")})
            operations = [operation for who, operation in sent if who == phone]
            first_pull = next(index for index, operation in enumerate(operations) if operation[0] == "pull")
            self.assertIn(("shell", "input", "keyevent", "KEYCODE_WAKEUP"), operations[:first_pull])  # woke before pulling
            self.assertEqual(2 if phone == "a" else 1, sum(operation[0] == "pull" for operation in operations))
        self.assertFalse([operation for _, operation in sent if "KEYCODE_SLEEP" in operation])

    def test_a_pull_that_times_out_twice_blocks_the_case(self):
        _, error, sent, *_ = self.run_preflight(pull_timeouts=2)
        self.assertIsInstance(error, suite.PrerequisiteError)
        self.assertIn("timed out", str(error))
        self.assertEqual(2, sum(operation[0] == "pull" for who, operation in sent if who == "a"))

    def test_a_pull_that_fails_for_another_reason_is_not_retried(self):
        _, error, sent, *_ = self.run_preflight(corrupt=True)
        self.assertEqual(1, sum(operation[0] == "pull" for who, operation in sent if who == "a"))

    @staticmethod
    def apk_with(*extras):
        import io
        import zipfile
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr("classes.dex", b"dex\n035\x00" + b"".join(b"\x14" + name.encode() + b"\x00" for name in extras))
            archive.writestr("classes2.dex", b"dex\n035\x00\x0dtest_in_dex_2\x00")
        return stream.getvalue()

    def test_a_tunable_whose_extra_is_not_in_the_frozen_build_blocks_before_any_launch(self):
        apk = self.apk_with("test_gatt_gap_fast_fail")
        evidence, error, *_ = self.run_preflight(apk=apk, tunables={"post_phy_liveness": True, "gatt_gap_fast_fail": False})
        self.assertIsInstance(error, suite.PrerequisiteError)
        self.assertIn("tunable not in this build: test_post_phy_liveness", str(error))
        self.assertEqual("FAIL", evidence["a"]["tunables_in_build"]["status"])
        self.assertEqual(["test_post_phy_liveness"], evidence["a"]["tunables_in_build"]["missing"])

    def test_tunables_found_in_any_dex_of_the_build_pass_and_are_recorded(self):
        apk = self.apk_with("test_gatt_gap_fast_fail", "test_post_phy_liveness")
        evidence, error, *_ = self.run_preflight(apk=apk, tunables={"post_phy_liveness": True, "gatt_gap_fast_fail": False})
        self.assertIsNone(error)
        self.assertEqual("PASS", evidence["b"]["tunables_in_build"]["status"])

    def test_a_longer_extra_name_does_not_stand_in_for_a_shorter_one(self):
        apk = self.apk_with("test_post_phy_liveness_extra")
        _, error, *_ = self.run_preflight(apk=apk, tunables={"post_phy_liveness": True})
        self.assertIn("test_post_phy_liveness", str(error))

    def test_an_apk_that_cannot_be_scanned_is_unmeasured_not_a_block(self):
        evidence, error, *_ = self.run_preflight(tunables={"post_phy_liveness": True})  # bytes, not a zip
        self.assertIsNone(error)
        self.assertEqual("UNMEASURED", evidence["a"]["tunables_in_build"]["status"])

    def test_the_thermal_status_at_start_is_recorded_for_every_participant(self):
        evidence, error, *_ = self.run_preflight()
        self.assertEqual({"verdict": "COOL", "at_start": {"a": 0, "b": 0}, "waited_s": 0},
                         {key: evidence["thermal_gate"][key] for key in ("verdict", "at_start", "waited_s")})

    def test_a_participant_at_moderate_makes_the_case_wait_until_all_are_cooler(self):
        evidence, error, sent, *_ = self.run_preflight(thermal=lambda phone, poll: 3 if phone == "b" and poll < 2 else 1)
        self.assertIsNone(error)
        gate = evidence["thermal_gate"]
        self.assertEqual(({"a": 1, "b": 3}, "WAITED"), (gate["at_start"], gate["verdict"]))
        self.assertEqual({"a": 1, "b": 1}, gate["at_end"])
        self.assertEqual(2, len(gate["polls"]))

    def test_a_participant_that_stays_hot_blocks_the_case_with_reason_thermal(self):
        clock = iter(range(0, 100000, 30))
        with patch.object(suite.time, "monotonic", side_effect=lambda: next(clock)):
            evidence, error, *_ = self.run_preflight(thermal=lambda phone, poll: 4 if phone == "a" else 0)
        self.assertIsInstance(error, suite.ThermalBlocked)
        self.assertEqual("thermal", error.reason_code)
        self.assertIn("a status 4", str(error))
        self.assertEqual("BLOCKED", evidence["thermal_gate"]["verdict"])
        self.assertGreaterEqual(evidence["thermal_gate"]["waited_s"], suite.THERMAL_WAIT_S - suite.THERMAL_POLL_S)

    def test_a_phone_that_reports_no_thermal_status_is_never_held_back(self):
        evidence, error, *_ = self.run_preflight(thermal=lambda phone, poll: "")
        self.assertIsNone(error)
        self.assertEqual({"a": None, "b": None}, evidence["thermal_gate"]["at_start"])

    def test_preflight_retains_independent_hash_receipt_and_removes_only_matched_copy(self):
        self.preflight_copy(corrupt=False)

    def test_preflight_keeps_mismatched_pulled_copy_as_failure_evidence(self):
        self.preflight_copy(corrupt=True)

    def test_already_cancelled_execution_does_no_device_io_and_releases_lease(self):
        registry = self.inputs([case()])
        event = threading.Event()
        event.set()
        job = {**suite.load_jobs(self.folder / "input.json", registry)[0], "owner": "offline-test", "_cancel_event": event}
        leases = self.folder / "leases"
        with patch.object(suite.subprocess, "run", side_effect=AssertionError("device call after cancellation")) as adb, \
             patch.object(suite, "Runner", side_effect=AssertionError("runner after cancellation")) as runner:
            attempted = suite.execute(job, registry, "unused-adb", "a" * 64, self.folder / "attempt", leases)
        self.assertEqual("INTERRUPTED", attempted["result"]["status"])
        adb.assert_not_called()
        runner.assert_not_called()
        self.assertTrue((self.folder / "attempt/attempt.json").is_file())
        self.assertFalse(leases.exists() and list(leases.iterdir()))

    def test_quiet_peer_timeout_keeps_checkpoint_and_attempt_and_releases_lease(self):
        registry = devices("abc")
        suite.write(self.folder / "input.json", {"cases": [case()]})
        job = {**suite.load_jobs(self.folder / "input.json", registry)[0], "owner": "offline-test"}
        leases = self.folder / "leases"
        output = self.folder / "attempt"
        output.mkdir()
        def command(argv, **_):
            if argv[3:] == ["get-state"]:
                return subprocess.CompletedProcess(argv, 0, "device", "")
            raise subprocess.TimeoutExpired(argv, 5)
        with patch.object(suite.subprocess, "run", side_effect=command), \
             patch.object(suite, "Runner", side_effect=AssertionError("runner after failed preflight")) as runner:
            attempted = suite.execute(job, registry, "unused-adb", "a" * 64, output, leases)
        self.assertEqual("BLOCKED", attempted["result"]["status"])
        self.assertIn("timed out", attempted["result"]["reason"])
        self.assertEqual([], list(leases.iterdir()))
        runner.assert_not_called()
        self.assertTrue((output / "attempt.json").is_file())
        checkpoint = suite.read(output / "preflight.json")
        self.assertIn("c", checkpoint["quiet_peers"])
        self.assertIn("timed out", json.dumps(checkpoint["quiet_peers"]["c"]))

    def artifact_output(self):
        output = self.folder / "owned"
        output.mkdir()
        trace = output / "physical/receiver.log"
        trace.parent.mkdir()
        trace.write_text("needed trace sample", encoding="utf-8")
        source = self.folder / "source.mp4"
        source.write_bytes(b"original source outside the new output")
        manifest = output / "physical/manifest.json"
        suite.write(manifest, {"logs": [{"path": str(trace)}], "source": str(source)})
        measured = {**result("one", "FAIL", "exact-request"), "manifest": str(manifest),
                    "manifest_sha256": suite.digest(manifest), "evidence_directory": str(output)}
        baseline = {"results": [{**result("before", "FAIL", "exact-request")}]}
        suite.report(output, [measured], baseline)
        for name in ("ui.xml", "setup.log", "receipt-copy.json", "preview.mp4", "empty.txt"):
            (output / name).write_bytes(b"" if name == "empty.txt" else b"reviewed setup scratch")
        suite.finalize_artifacts(output)
        return output, source

    def test_artifact_finalization_exposes_all_unused_file_types_and_preserves_evidence(self):
        output, source = self.artifact_output()
        review = suite.read(output / "suite-result.json")["artifact_review"]
        self.assertEqual("PENDING", review["status"])
        self.assertEqual({"ui.xml", "setup.log", "receipt-copy.json", "preview.mp4", "empty.txt"},
                         {name for name, row in review["files"].items() if row["state"] == "PENDING"})
        self.assertEqual("RETAINED", review["files"]["physical/receiver.log"]["state"])
        self.assertIn("physical/manifest.json", review["files"]["physical/receiver.log"]["required_by"])
        self.assertNotIn(str(source), review["files"])
        self.assertTrue(all((output / name).exists() for name in review["files"]))

    def test_artifact_review_consumes_exact_scratch_and_retains_named_remaining_use(self):
        output, source = self.artifact_output()
        original = suite.read(output / "suite-result.json")["results"]
        suite.review_artifacts(output, consume=["setup.log", "ui.xml", "empty.txt"],
                               retain=["preview.mp4", "receipt-copy.json"], purpose="Compare unresolved setup defect")
        payload = suite.read(output / "suite-result.json")
        self.assertEqual("COMPLETE", payload["artifact_review"]["status"])
        self.assertEqual(original, payload["results"])
        self.assertEqual("DELETED", payload["artifact_review"]["files"]["empty.txt"]["state"])
        self.assertEqual("Compare unresolved setup defect", payload["artifact_review"]["files"]["preview.mp4"]["purpose"])
        self.assertTrue(source.exists())
        self.assertTrue((output / "physical/receiver.log").exists())
        self.assertFalse((output / "setup.log").exists())
        suite.review_artifacts(output, consume=["preview.mp4"], purpose="Comparison completed; source retained")
        self.assertFalse((output / "preview.mp4").exists())

    def test_artifact_review_checks_entire_batch_before_deleting_any_file(self):
        output, _ = self.artifact_output()
        (output / "ui.xml").write_bytes(b"changed after review inventory")
        before = (output / "suite-result.json").read_bytes()
        with self.assertRaises(ValueError):
            suite.review_artifacts(output, consume=["setup.log", "ui.xml"], purpose="Review complete")
        self.assertTrue((output / "setup.log").exists())
        self.assertEqual(before, (output / "suite-result.json").read_bytes())

    def test_artifact_review_rejects_sources_references_unknown_files_and_traversal(self):
        output, source = self.artifact_output()
        (output / "introduced-later.log").write_bytes(b"unregistered")
        for name in (str(source), "../source.mp4", "physical/receiver.log", "physical/manifest.json",
                     "suite-result.json", "introduced-later.log"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                suite.review_artifacts(output, consume=["setup.log", name], purpose="Review complete")
            self.assertTrue((output / "setup.log").exists())
        self.assertTrue(source.exists())

    def test_artifact_review_rejects_reparse_paths_before_deleting_the_batch(self):
        output, _ = self.artifact_output()
        guard = suite.DebugImages._guard
        def reject_link(path):
            if path.name == "ui.xml":
                raise ValueError("Path contains a junction")
            guard(path)
        with patch.object(suite.DebugImages, "_guard", side_effect=reject_link), self.assertRaises(ValueError):
            suite.review_artifacts(output, consume=["setup.log", "ui.xml"], purpose="Review complete")
        self.assertTrue((output / "setup.log").exists())

    def test_later_measurement_needs_explicit_retention_before_review_can_finish(self):
        output, _ = self.artifact_output()
        new = output / "clock-measurement.json"
        suite.write(new, {"basis": "Actual finalized run PCM and clock brackets"})
        original = suite.read(output / "suite-result.json")["results"]
        with self.assertRaisesRegex(ValueError, "Unregistered output"):
            suite.review_artifacts(output)
        review = suite.review_artifacts(output, retain=[new.name], purpose="Diagnose incomplete AudioTrack clock coverage")
        self.assertEqual(suite.digest(new), review["files"][new.name]["sha256"])
        self.assertEqual("RETAINED", review["files"][new.name]["state"])
        self.assertEqual(original, suite.read(output / "suite-result.json")["results"])

    def test_repeated_stage_identity_strings_do_not_repeat_filesystem_reference_lookups(self):
        output = self.folder / "owned"
        output.mkdir()
        samples = output / "samples.csv"
        samples.write_text("retained clock samples", encoding="utf-8")
        stage = output / "stage.json"
        suite.write(stage, {"events": [{"session": "opaque-session", "clock": "System.nanoTime",
                                        "state": "MEASURED", "samples": "samples.csv"}] * 1000})
        resolve = Path.resolve
        with patch.object(Path, "resolve", autospec=True, side_effect=resolve) as calls:
            required = suite.artifact_dependencies(output, {stage: "stage.json", samples: "samples.csv"}, {stage})
        self.assertEqual({samples: {"stage.json"}}, required)
        self.assertLess(calls.call_count, 20)

    def test_artifact_review_cli_is_offline_and_does_not_change_case_or_baseline(self):
        output, _ = self.artifact_output()
        before = suite.read(output / "suite-result.json")["results"]
        with patch.object(suite.subprocess, "run", side_effect=AssertionError("device I/O")), \
             patch.object(suite.subprocess, "Popen", side_effect=AssertionError("device process")):
            code = suite.main(["review", "--output", str(output), "--consume", "setup.log", "--purpose", "Setup review complete"])
        self.assertEqual(2, code)  # Other unreviewed outputs remain visible.
        self.assertEqual(before, suite.read(output / "suite-result.json")["results"])

    def test_interrupted_artifact_deletion_records_consumed_bytes_and_retries_exactly(self):
        output, _ = self.artifact_output()
        unlink = Path.unlink
        def interrupted(path, *args, **kwargs):
            if path.name == "ui.xml":
                raise PermissionError("Simulated locked reviewed file")
            return unlink(path, *args, **kwargs)
        with patch.object(Path, "unlink", interrupted), self.assertRaises(PermissionError):
            suite.review_artifacts(output, consume=["setup.log", "ui.xml"], purpose="Review complete")
        review = suite.read(output / "suite-result.json")["artifact_review"]
        self.assertEqual("DELETED", review["files"]["setup.log"]["state"])
        self.assertEqual("CONSUMED", review["files"]["ui.xml"]["state"])
        suite.review_artifacts(output, consume=["ui.xml"], purpose="Finish interrupted exact deletion")
        self.assertFalse((output / "ui.xml").exists())

    def test_pending_managed_images_remain_visible_until_their_review_completes(self):
        output = self.folder / "owned"
        output.mkdir()
        images = suite.DebugImages(output)
        image = images.reserve("preview.png", "Inspect image")
        image.write_bytes(b"pixels")
        images.register("preview.png")
        suite.report(output, [result("one", "INTERRUPTED")])
        review = suite.finalize_artifacts(output)
        self.assertEqual("PENDING", review["status"])
        self.assertEqual(["debug-image-lifecycle.json: preview.png"], review["pending_images"])
        images.consume("preview.png")
        self.assertEqual("COMPLETE", suite.review_artifacts(output)["status"])

    def test_retained_comparison_protects_its_dependencies_in_the_same_and_later_batches(self):
        output = self.folder / "owned"
        output.mkdir()
        samples = output / "samples.csv"
        samples.write_text("needed measurement samples", encoding="utf-8")
        suite.write(output / "comparison.json", {"samples": {"path": "samples.csv", "sha256": suite.digest(samples)}})
        suite.report(output, [result("one", "FAIL")])
        suite.finalize_artifacts(output)
        with self.assertRaises(ValueError):
            suite.review_artifacts(output, retain=["comparison.json"], consume=["samples.csv"], purpose="Unresolved comparison")
        self.assertTrue(samples.exists())
        review = suite.review_artifacts(output, retain=["comparison.json"], purpose="Unresolved comparison")
        self.assertEqual("RETAINED", review["files"]["samples.csv"]["state"])
        with self.assertRaises(ValueError):
            suite.review_artifacts(output, consume=["samples.csv"], purpose="Review complete")
        suite.review_artifacts(output, consume=["comparison.json", "samples.csv"], purpose="Comparison resolved; original result retained")
        self.assertFalse(samples.exists())

    def test_consumed_missing_bytes_can_only_finish_deletion_not_be_retained(self):
        output, _ = self.artifact_output()
        payload = suite.read(output / "suite-result.json")
        payload["artifact_review"]["files"]["setup.log"]["state"] = "CONSUMED"
        suite.write(output / "suite-result.json", payload)
        (output / "setup.log").unlink()  # Interruption after deletion, before checkpoint.
        before = (output / "suite-result.json").read_bytes()
        with self.assertRaises(ValueError):
            suite.review_artifacts(output, retain=["setup.log"], purpose="Keep missing evidence")
        self.assertEqual(before, (output / "suite-result.json").read_bytes())
        suite.review_artifacts(output, consume=["setup.log"], purpose="Finish recorded deletion")
        self.assertEqual("DELETED", suite.read(output / "suite-result.json")["artifact_review"]["files"]["setup.log"]["state"])

    def test_changed_retained_parent_cannot_release_its_frozen_dependency(self):
        output = self.folder / "owned"
        output.mkdir()
        (output / "samples.csv").write_text("needed samples", encoding="utf-8")
        suite.write(output / "comparison.json", {"samples": "samples.csv"})
        suite.report(output, [result("one", "FAIL")])
        suite.finalize_artifacts(output)
        suite.review_artifacts(output, retain=["comparison.json"], purpose="Unresolved comparison")
        suite.write(output / "comparison.json", {"reference_removed": True})
        before = (output / "suite-result.json").read_bytes()
        with self.assertRaises(ValueError):
            suite.review_artifacts(output, consume=["samples.csv"], purpose="Reference apparently absent")
        self.assertEqual(before, (output / "suite-result.json").read_bytes())
        self.assertTrue((output / "samples.csv").exists())

    def test_released_comparison_dependencies_need_review_instead_of_staying_retained(self):
        output = self.folder / "owned"
        output.mkdir()
        (output / "samples.csv").write_text("needed samples", encoding="utf-8")
        suite.write(output / "comparison.json", {"samples": "samples.csv"})
        suite.report(output, [result("one", "FAIL")])
        suite.finalize_artifacts(output)
        suite.review_artifacts(output, retain=["comparison.json"], purpose="Unresolved comparison")
        review = suite.review_artifacts(output, consume=["comparison.json"], purpose="Comparison finished")
        self.assertEqual("PENDING", review["files"]["samples.csv"]["state"])
        self.assertEqual("PENDING", review["status"])
        self.assertEqual("COMPLETE", suite.review_artifacts(output, consume=["samples.csv"], purpose="No remaining consumer")["status"])

    def test_windows_review_paths_normalize_before_dependency_release(self):
        output = self.folder / "owned"
        (output / "scratch").mkdir(parents=True)
        (output / "scratch/samples.csv").write_text("needed samples", encoding="utf-8")
        suite.write(output / "scratch/comparison.json", {"samples": "samples.csv"})
        suite.report(output, [result("one", "FAIL")])
        suite.finalize_artifacts(output)
        suite.review_artifacts(output, retain=[r"scratch\comparison.json"], purpose="Unresolved comparison")
        review = suite.review_artifacts(output, consume=[r"scratch\comparison.json", r"scratch\samples.csv"], purpose="Comparison finished")
        self.assertEqual("COMPLETE", review["status"])
        self.assertFalse((output / "scratch/samples.csv").exists())

    def test_atomic_write_does_not_follow_an_existing_temporary_symlink(self):
        original = self.folder / "source.txt"
        original.write_text("user bytes", encoding="utf-8")
        target = self.folder / "suite-result.json"
        link = target.with_name(target.name + ".tmp")
        try:
            link.symlink_to(original)
        except OSError:
            self.skipTest("Host lacks symlink permission")
        suite.write(target, {"result": "new"})
        self.assertEqual("user bytes", original.read_text(encoding="utf-8"))
        self.assertEqual({"result": "new"}, suite.read(target))

    def test_suite_finish_records_artifact_review_without_a_new_ledger_file(self):
        self.inputs([case()])
        with patch.object(suite, "execute", side_effect=self.blocked_mock_attempt):
            suite.main(self.run_args())
        output = self.folder / "output"
        self.assertEqual("COMPLETE", suite.read(output / "suite-result.json")["artifact_review"]["status"])
        self.assertEqual({"selection.json", "execution.json", "plan.json", "suite-result.json", "coverage.json", "report.html"},
                         {path.name for path in output.iterdir() if path.is_file()})



class DeferredAnalysisTest(unittest.TestCase):
    """--defer-analysis frees phones first; finish-analysis must reproduce a normal run's analysis."""

    setUp, inputs, run_args = UnifiedSuiteTest.setUp, UnifiedSuiteTest.inputs, UnifiedSuiteTest.run_args

    def scrub(self, name, text):
        root = self.folder / name
        for form in (json.dumps(str(root))[1:-1], str(root), root.as_posix()):
            text = text.replace(form, "OUT")
        return text

    def executed(self, job, _devices, _adb, _hash, output, _leases, *_, **__):
        manifest = output / "physical/manifest.json"
        suite.write(manifest, {"scenario": job["case"], "run_id": "recorded-" + job["case"]["id"],
                               "source_kind": "live_camera", "devices": {}, "logs": []})
        suite.write(output / "physical/receiver.log.json", {"note": "scratch left by the runner"})
        return {"manifest": manifest, "job": job, "output": output, "host_start_ns": 1, "host_end_ns": 2}

    def scorers(self, seen=None):
        def score(manifest, *_):
            if seen is not None:
                seen.append(manifest["scenario"]["id"])
            return {"devices": [], "overall_status": "PASS" if manifest["scenario"]["id"] == "one" else "FAIL"}
        return (patch.object(suite, "execute", side_effect=self.executed),
                patch.object(suite, "score_manifest", side_effect=score),
                patch.object(suite, "score_motion", return_value={"timing_state": "UNMEASURED", "capture_state": "COMPLETE"}),
                patch.object(suite, "score_phase_delivery", return_value={"quality": {}}))

    def run_suite(self, name, *extra, mocks=None):
        args = self.run_args()
        args[args.index("--output") + 1] = str(self.folder / name)
        with contextlib.ExitStack() as stack:
            for mock in mocks or self.scorers():
                stack.enter_context(mock)
            return suite.main([*args, *extra])

    def normalized(self, name, relative):
        return self.scrub(name, (self.folder / name / relative).read_text(encoding="utf-8"))

    def test_deferred_run_releases_phones_without_scoring_and_is_not_finalized(self):
        self.inputs([case(), case("two", "c", "d")])
        seen = []
        status = self.run_suite("deferred", "--defer-analysis", mocks=(
            *self.scorers(seen)[:1], patch.object(suite, "analyze", side_effect=AssertionError("scored before exit"))))
        self.assertEqual(0, status)
        output = self.folder / "deferred"
        payload = suite.read(output / "suite-result.json")
        self.assertEqual({"PENDING_ANALYSIS"}, {item["status"] for item in payload["results"]})
        self.assertEqual({"PENDING_ANALYSIS": 2}, payload["state_counts"])
        self.assertNotIn("artifact_review", payload)
        self.assertFalse(list(output.glob("*/analysis")))
        self.assertTrue((output / "execution.json").is_file())
        self.assertEqual({"baseline": None}, suite.read(output / "execution.json")["defer_analysis"])
        for item in payload["results"]:
            self.assertEqual(suite.digest(Path(item["manifest"])), item["deferred_analysis"]["manifest_sha256"])
        with self.assertRaises(ValueError):
            suite.review_artifacts(output)
        self.assertEqual([], seen)

    def test_defer_analysis_applies_only_to_run_and_finish_takes_no_selection(self):
        for argv in (["analyze", "--defer-analysis", "--manifest", "m.json", "--output", "o"],
                     ["finish-analysis", "--output", "o", "--suite", "s.json"],
                     ["finish-analysis", "--output", "o", "--phones", "a"]):
            with self.subTest(argv=argv), self.assertRaises(SystemExit):
                suite.main(argv)

    def test_finish_equals_normal_run_for_the_same_evidence(self):
        self.inputs([case(), case("two", "c", "d")])
        baseline = {"results": [{**result("one", "FAIL", "unrelated-key")}]}
        suite.write(self.folder / "baseline.json", baseline)
        self.assertEqual(2, self.run_suite("normal", "--baseline", str(self.folder / "baseline.json")))
        self.run_suite("deferred", "--defer-analysis", "--baseline", str(self.folder / "baseline.json"),
                       mocks=(*self.scorers()[:1], patch.object(suite, "analyze", side_effect=AssertionError("early"))))
        with contextlib.ExitStack() as stack:
            for mock in self.scorers()[1:]:
                stack.enter_context(mock)
            self.assertEqual(2, suite.main(["finish-analysis", "--output", str(self.folder / "deferred")]))
        normal, deferred = (suite.read(self.folder / name / "suite-result.json") for name in ("normal", "deferred"))
        self.assertNotIn("PENDING_ANALYSIS", {item["status"] for item in deferred["results"]})
        self.assertEqual(2, len({item["status"] for item in deferred["results"]}))
        self.assertEqual(self.scrub("normal", json.dumps(normal["results"])),
                         self.scrub("deferred", json.dumps(deferred["results"])))  # Same values and key order
        for key in ("state_counts", "thresholds", "scope"):
            self.assertEqual(normal[key], deferred[key])
        self.assertNotIn("deferred_analysis", json.dumps(deferred))
        for relative in ("analysis/analysis.json", "analysis/computed-score.json", "analysis/motion-timing.json"):
            for directory in sorted(path.name for path in (self.folder / "normal").iterdir() if path.is_dir()):
                self.assertEqual(self.normalized("normal", f"{directory}/{relative}"),
                                 self.normalized("deferred", f"{directory}/{relative}"), relative)
        review_a, review_b = normal["artifact_review"], deferred["artifact_review"]
        self.assertEqual("COMPLETE" if review_a["status"] == "COMPLETE" else "PENDING", review_b["status"])
        self.assertEqual(sorted(review_a["files"]), sorted(review_b["files"]))
        self.assertEqual({k: {f: v for f, v in row.items() if f != "sha256"} for k, row in review_a["files"].items()},
                         {k: {f: v for f, v in row.items() if f != "sha256"} for k, row in review_b["files"].items()})
        self.assertEqual(sorted(review_a["protected"]), sorted(review_b["protected"]))
        self.assertEqual(sorted(review_a["protected_hashes"]), sorted(review_b["protected_hashes"]))
        self.assertEqual(review_a["pending_images"], review_b["pending_images"])
        for name in ("coverage.json", "report.html"):
            self.assertEqual(self.normalized("normal", name).count("PENDING_ANALYSIS"),
                             self.normalized("deferred", name).count("PENDING_ANALYSIS"))
        self.assertEqual(self.normalized("normal", "coverage.json"), self.normalized("deferred", "coverage.json"))
        self.assertFalse((self.folder / "deferred/.finish-analysis.lock").exists())
        self.assertEqual({"selection.json", "execution.json", "plan.json", "suite-result.json", "coverage.json", "report.html"},
                         {path.name for path in (self.folder / "deferred").iterdir() if path.is_file()})
        suite.review_artifacts(self.folder / "deferred")  # Existing purpose review applies to the finished output.

    def test_finish_blocks_changed_manifest_changed_baseline_second_owner_and_finished_output(self):
        self.inputs([case()])
        suite.write(self.folder / "baseline.json", {"results": []})
        self.run_suite("deferred", "--defer-analysis", "--baseline", str(self.folder / "baseline.json"),
                       mocks=(*self.scorers()[:1],))
        output = self.folder / "deferred"
        (output / ".finish-analysis.lock").write_text("owner", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Another finish-analysis"):
            suite.finish_analysis(output)
        (output / ".finish-analysis.lock").unlink()
        suite.write(self.folder / "baseline.json", {"results": [result("x", "FAIL")]})
        with self.assertRaisesRegex(ValueError, "Baseline"):
            suite.finish_analysis(output)
        suite.write(self.folder / "baseline.json", {"results": []})
        manifest = Path(suite.read(output / "suite-result.json")["results"][0]["manifest"])
        original = manifest.read_bytes()
        suite.write(manifest, {"changed": "after release"})
        with patch.object(suite, "analyze", side_effect=AssertionError("must not score changed evidence")):
            suite.finish_analysis(output)
        row = suite.read(output / "suite-result.json")["results"][0]
        self.assertEqual("FAIL", row["status"])
        self.assertIn("changed since", row["reason"])
        self.assertNotIn("deferred_analysis", row)
        with self.assertRaisesRegex(ValueError, "already finalized"):
            suite.finish_analysis(output)
        manifest.write_bytes(original)

    def test_interrupted_finish_leaves_cases_pending_and_unfrozen(self):
        self.inputs([case()])
        self.run_suite("deferred", "--defer-analysis", mocks=(*self.scorers()[:1],))
        output = self.folder / "deferred"
        with patch.object(suite, "analyze", side_effect=KeyboardInterrupt()):
            suite.finish_analysis(output)
        payload = suite.read(output / "suite-result.json")
        self.assertEqual("PENDING_ANALYSIS", payload["results"][0]["status"])
        self.assertNotIn("artifact_review", payload)
        self.assertFalse((output / ".finish-analysis.lock").exists())
        with contextlib.ExitStack() as stack:
            for mock in self.scorers()[1:]:
                stack.enter_context(mock)
            suite.finish_analysis(output)
        self.assertNotEqual("PENDING_ANALYSIS", suite.read(output / "suite-result.json")["results"][0]["status"])
        self.assertIn("artifact_review", suite.read(output / "suite-result.json"))


class ClassicBondPreflightTest(unittest.TestCase):
    SAMSUNG = "Bluetooth Status|  address: XX:XX:XX:XX:78:80||  Bonded devices:|		XX:XX:XX:XX:A3:B2 => XX:XX:XX:XX:72:8F [ DUAL ] Other|		XX:XX:XX:XX:88:87 [ DUAL ] Galaxy M31|		XX:XX:XX:XX:EB:32 [ DUAL ] ASUS6z||Enable log:".replace("|", chr(10))
    ASUS = "  address: AA:BB:CC:DD:EB:32|  Bonded devices:|    11:22:33:44:78:80 [ DUAL ] S24|    11:22:33:44:55:66 [BR/EDR] Car".replace("|", chr(10))
    UNPAIRED = "  address: AA:BB:CC:DD:88:87|  Bonded devices:|mSnoopLogSettingAtEnable = empty".replace("|", chr(10))

    def identities(self):
        return {name: dict(zip(("address", "bonded"), suite.bluetooth_bonds(dump)))
                for name, dump in {"s24": self.SAMSUNG, "asus": self.ASUS, "m31": self.UNPAIRED}.items()}

    def test_mutual_bond_passes_with_samsung_masked_addresses(self):
        self.assertEqual([], suite.missing_classic_bonds(self.identities(), [("asus", "s24")]))

    def test_one_sided_or_absent_bond_blocks_classic_before_media(self):
        missing = suite.missing_classic_bonds(self.identities(), [("s24", "m31"), ("m31", "asus")])
        self.assertEqual(2, len(missing))
        self.assertIn("s24 has m31: True; m31 has s24: False", missing[0])

    def test_bond_free_cases_record_missing_bonds_without_requiring_them(self):
        self.assertFalse(suite.classic_bond_free({"transport": "classic"}))
        self.assertFalse(suite.classic_bond_free({"transport": "classic", "tunables": {"classic_bond_free": False}}))
        self.assertTrue(suite.classic_bond_free({"transport": "classic", "tunables": {"classic_bond_free": True}}))

    def test_classic_edges_cover_calls_and_every_chain_hop(self):
        self.assertEqual([("a", "b")], suite.classic_edges({"caller": "a", "callee": "b"}))
        relayed = {"caller": "a", "callee": "c", "relay": "b"}
        self.assertEqual([("a", "b"), ("b", "c")], suite.classic_edges(relayed))
        self.assertEqual([("a", "caller"), ("b", "middle"), ("c", "callee")], suite.job_roles({"case": relayed}))
        chain = {"receivers": [{"phone": "b", "parent": "a"}, {"phone": "c", "parent": "b"}]}
        self.assertEqual([("a", "b"), ("b", "c")], suite.classic_edges(chain))

    def test_wifi_client_band_is_read_from_cmd_wifi_status(self):
        self.assertEqual(2437, suite.wifi_client_mhz("Wifi is enabled\nFrequency: 2437MHz, ..."))
        self.assertIsNone(suite.wifi_client_mhz("Wifi is disconnected"))
        self.assertIsNone(suite.wifi_client_mhz(""))

if __name__ == "__main__":
    unittest.main()
