import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, mock_open, patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import run_device_benchmark as device_runner
import run_private_call_benchmark as runner


class PrivateCallBenchmarkTest(unittest.TestCase):
    def setUp(self):
        self.devices = {"a": {"serial": "a", "node_prefix": "000000000001"},
                        "b": {"serial": "b", "node_prefix": "000000000002"}}
        profile = {"codec": "encoder-a", "width": 640, "height": 360, "fps": 15,
                   "bitrate_kbps": 150, "playback": "balanced", "rate_control": "cbr",
                   "thermal_reduction": False}
        self.case = {"id": "test-call", "caller": "a", "callee": "b", "transport": "classic",
                     "duration_s": 60, "profiles": {"a": profile, "b": {**profile, "codec": "encoder-b", "fps": 30}}}

    @staticmethod
    def line(second, message, pid=123):
        return f"10-03 00:00:{second:06.3f} {pid} 123 I BluetoothVideo: {message}\n"

    def test_peer_native_input_rejection_stops_surface_wait_without_a_timeout(self):
        instance = runner.CallRunner("adb", self.devices, self.case, Path("call-input-rejection"))
        instance.logs = [{"device": phone} for phone in self.devices]
        rejected = "BENCHMARK native call input rejected: Native raw video 640x480@15 differs from negotiated 426x240@15; no conversion"
        instance.log_text = Mock(side_effect=lambda phone: self.line(2, rejected) if phone == "b" else "")
        with patch.object(runner.time, "monotonic", side_effect=[0, 0, 2]), patch.object(runner.time, "sleep") as sleep:
            with self.assertRaisesRegex(RuntimeError, "b:.*native call input rejected.*no conversion"):
                instance.wait_for("a", r"Private call startup: media-to-first-surface", timeout=1)
            sleep.assert_not_called()

    def test_availability_wait_does_not_read_a_peer_before_its_launch(self):
        instance = runner.CallRunner("adb", self.devices, self.case, Path("call-availability"))
        instance.logs = [{"device": "b"}]
        def text(phone):
            if phone != "b":
                raise StopIteration()
            return ""
        instance.log_text = Mock(side_effect=text)
        with patch.object(runner.time, "monotonic", side_effect=[0, 0, 2]), patch.object(runner.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "b: no app evidence"):
                instance.wait_for("b", r"BENCHMARK call available:", timeout=1)
        self.assertEqual({"b"}, {call.args[0] for call in instance.log_text.call_args_list})

    def test_an_availability_line_the_live_capture_missed_is_recovered_from_the_device_buffer(self):
        # voice-ptt-e151a61f case 3: capture attached after the app logged 'call available'.
        with tempfile.TemporaryDirectory() as directory:
            instance = runner.CallRunner("adb", self.devices, self.case, Path(directory))
            instance.logs = [{"device": "b", "pid": 7, "path": "b-7.log"}]
            instance.pids["b"] = "7"
            instance.output.mkdir(parents=True, exist_ok=True)
            (instance.output / "b-7.log").write_text("", encoding="utf-8")
            line = self.line(1, "BENCHMARK call available: node=000000000002", pid=7).rstrip()
            with patch.object(instance, "adb", return_value=line), \
                 patch.object(runner.time, "monotonic", side_effect=[0, 0, 2]), patch.object(runner.time, "sleep"):
                row = instance.wait_for("b", r"BENCHMARK call available: node=([0-9a-f]{12})", timeout=1)
            self.assertTrue(row["message"].endswith("000000000002"))
            self.assertEqual(["log_lines_recovered", "evidence_after_deadline"], [event["action"] for event in instance.events])
            self.assertEqual("logcat_dump", row["source"])

    def test_a_missing_line_is_still_a_failure_when_the_device_buffer_lacks_it_too(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = runner.CallRunner("adb", self.devices, self.case, Path(directory))
            instance.logs = [{"device": "b", "pid": 7, "path": "b-7.log"}]
            instance.pids["b"] = "7"
            instance.output.mkdir(parents=True, exist_ok=True)
            (instance.output / "b-7.log").write_text("", encoding="utf-8")
            with patch.object(instance, "adb", return_value=""), \
                 patch.object(runner.time, "monotonic", side_effect=[0, 0, 2]), patch.object(runner.time, "sleep"):
                with self.assertRaisesRegex(RuntimeError, "b: no app evidence"):
                    instance.wait_for("b", r"BENCHMARK call available:", timeout=1)

    def lagging_runner(self, directory, live_text, pid="7"):
        instance = runner.CallRunner("adb", self.devices, self.case, Path(directory))
        instance.logs = [{"device": "b", "pid": int(pid), "path": f"b-{pid}.log"}]
        instance.pids["b"] = pid
        instance.output.mkdir(parents=True, exist_ok=True)
        (instance.output / f"b-{pid}.log").write_text(live_text, encoding="utf-8")
        return instance

    def test_an_acknowledgement_the_lagging_live_stream_delivers_late_is_found_after_the_deadline(self):
        # voice-speech-s24a15s-51cc8028 case 4: the A15s live stream ran 5-21 s behind the app, so
        # 'control ptt_press applied' reached the host file after the 5 s wait expired. The old dump
        # fallback searched only the lines the live file lacked, so it could not satisfy the wait.
        applied = self.line(6.5, "BENCHMARK control ptt_press applied", pid=7).rstrip()
        with tempfile.TemporaryDirectory() as directory:
            instance = self.lagging_runner(directory, "")

            def dump_while_the_line_arrives(*args, **kwargs):
                (instance.output / "b-7.log").write_text(applied + "\n", encoding="utf-8")
                return applied

            with patch.object(instance, "adb", side_effect=dump_while_the_line_arrives), \
                 patch.object(runner.time, "monotonic", side_effect=[0, 0, 6]), patch.object(runner.time, "sleep"):
                row = instance.wait_for("b", r"BENCHMARK control ptt_press (?:applied|rejected)",
                                        "10-03 00:00:06.161", timeout=5)
            self.assertEqual("BENCHMARK control ptt_press applied", row["message"])
            self.assertEqual("live_capture_after_deadline", row["source"])
            self.assertEqual("10-03 00:00:06.500", row["time"])  # the app's own timestamp, not the arrival time
            self.assertEqual("evidence_after_deadline", instance.events[-1]["action"])

    def test_a_line_only_the_device_dump_holds_is_accepted_and_labelled_as_dump_evidence(self):
        applied = self.line(12.893, "BENCHMARK control accept applied", pid=7).rstrip()
        with tempfile.TemporaryDirectory() as directory:
            instance = self.lagging_runner(directory, "")
            with patch.object(instance, "adb", return_value=applied), \
                 patch.object(runner.time, "monotonic", side_effect=[0, 0, 6]), patch.object(runner.time, "sleep"):
                row = instance.wait_for("b", r"BENCHMARK control accept (?:applied|rejected)",
                                        "10-03 00:00:12.579", timeout=5)
            self.assertEqual("logcat_dump", row["source"])
            event = instance.events[-1]
            self.assertEqual(("evidence_after_deadline", "logcat_dump", 7), (event["action"], event["source"], event["pid"]))

    def test_dump_evidence_must_follow_the_control_and_belong_to_this_pid(self):
        before = self.line(12.100, "BENCHMARK control accept applied", pid=7).rstrip()      # an earlier attempt
        other_pid = self.line(12.893, "BENCHMARK control accept applied", pid=99).rstrip()  # a previous process
        with tempfile.TemporaryDirectory() as directory:
            instance = self.lagging_runner(directory, "")
            with patch.object(instance, "adb", return_value=before + "\n" + other_pid), \
                 patch.object(runner.time, "monotonic", side_effect=[0, 0, 6]), patch.object(runner.time, "sleep"):
                with self.assertRaisesRegex(RuntimeError, "b: no app evidence"):
                    instance.wait_for("b", r"BENCHMARK control accept (?:applied|rejected)",
                                      "10-03 00:00:12.579", timeout=5)
            self.assertEqual([], [event for event in instance.events if event["action"] == "evidence_after_deadline"])

    def test_control_records_that_its_acknowledgement_came_from_the_dump(self):
        instance = runner.CallRunner("adb", self.devices, self.case, Path("call-control-source"))
        instance.phone_time = Mock(return_value="10-03 00:00:12.579")
        instance.adb = Mock()
        instance.wait_for = Mock(return_value={"message": "BENCHMARK control accept applied",
                                               "time": "10-03 00:00:12.893", "source": "logcat_dump"})
        event = instance.control("b", "accept")
        self.assertEqual(("logcat_dump", "10-03 00:00:12.893"), (event["ack_source"], event["ack_time"]))
        instance.wait_for = Mock(return_value={"message": "BENCHMARK control accept applied", "time": "10-03 00:00:12.893"})
        self.assertNotIn("ack_source", instance.control("b", "accept"))

    def test_exception_without_a_message_remains_a_failed_call(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = runner.CallRunner("adb", self.devices, self.case, Path(directory) / "run")
            instance.inventory = Mock(return_value={"apk_sha256": "a" * 64})
            instance.launch = Mock(side_effect=StopIteration())
            instance.phone_time = Mock(return_value="10-03 00:00:00.000")
            instance.adb = Mock()
            instance.beep = Mock()
            manifest = instance.run()
            self.assertEqual("StopIteration", manifest["runner_error"])
            self.assertEqual("FAIL", json.loads((instance.output / "score.json").read_text())["overall_status"])

    def log(self, phone, own_fps, remote_fps, wrong_peer=False, requested_rate=150, negotiated_rate=150):
        peer = "b" if phone == "a" else "a"
        encoder = self.case["profiles"][phone]["codec"]
        peer_prefix = "ffffffffffff" if wrong_peer else self.devices[peer]["node_prefix"]
        local = self.line(0.1, f"BENCHMARK call available: node={self.devices[phone]['node_prefix']}")
        local += self.line(0.2, f"BENCHMARK call discovered: node={self.devices[peer]['node_prefix']} | transport=CLASSIC_RFCOMM")
        local += self.line(0.3, f"BENCHMARK call selected: encoder={encoder} | width=640 | height=360 | "
                           f"fps={own_fps} | bitrateKbps={requested_rate} | playbackMs=250 | rateControl=CBR | thermalReduction=false")
        local += self.line(1, f"Encrypted private-call link to {peer_prefix} over Classic RFCOMM (MPS 4096)")
        local += self.line(2, f"BENCHMARK call effective: encoder={encoder} | codec=2 | width=640 | height=360 | "
                           f"fps={own_fps} | bitrateKbps={negotiated_rate} | audioKbps=32 | playbackMs=250 | rateControl=CBR")
        local += self.line(2.1, f"Call camera: Streaming VP9 at 640x360, {own_fps} FPS, {negotiated_rate} kbps, CBR mode 2")
        local += self.line(2.5, f"BENCHMARK call remote: codec=2 | width=640 | height=360 | fps={remote_fps} | rotation=0")
        local += self.line(3, "Private call startup: media-to-first-surface 1000.0 ms")
        period = 1000 / remote_fps
        for second, frames in ((3, 1), (8, 1 + remote_fps * 5), (13, 1 + remote_fps * 10)):
            local += self.line(second, f"Private call visible continuity: {frames} frames | 0 freeze event(s), "
                               f"0.0 ms, 0.00% | threshold 250.0 ms | surface cadence p50/p95/p99/max "
                               f"{period:.1f}/{period:.1f}/{period:.1f}/{period:.1f} ms")
        local += self.line(7, f"Encoder output: {own_fps}.0 FPS, {negotiated_rate} kbps")
        local += self.line(8, "Call RX 150 kbps | 80 video/100 audio | audio drops 0 | 80/80 shown, queue 1/11, drops 0")
        return local

    def score(self, a, b, **extra):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / "a.log").write_text(a, encoding="utf-8")
            (folder / "b.log").write_text(b, encoding="utf-8")
            manifest = {"scenario": self.case, "devices": self.devices,
                        "logs": [{"device": phone, "path": phone + ".log", "pid": 123} for phone in "ab"],
                        "setup_anchors": [{"device": phone, "time": "10-03 00:00:00.500"} for phone in "ab"], **extra}
            return runner.score_call_manifest(manifest, folder)

    def test_independent_profiles_and_duplicate_identity_validation(self):
        self.assertEqual(runner.validate_call_case(self.case, self.devices), {"a", "b"})
        devices = copy.deepcopy(self.devices)
        devices["b"]["serial"] = devices["a"]["serial"]
        with self.assertRaises(ValueError):
            runner.validate_call_case(self.case, devices)
        case = copy.deepcopy(self.case)
        del case["profiles"]["b"]
        with self.assertRaises(ValueError):
            runner.validate_call_case(case, self.devices)

    def test_call_tunables_reach_both_endpoint_launches(self):
        call = {**self.case, "tunables": {"bounded_media_age": True}}
        for phone in (call["caller"], call["callee"]):
            self.assertEqual({"bounded_media_age": True}, runner.broadcast_shape(call, phone)["tunables"])
        self.assertNotIn("tunables", runner.broadcast_shape(self.case, self.case["caller"]))

    def test_phone_tunables_reach_only_their_endpoint(self):
        call = {**self.case, "tunables": {"call_link_renewal": True},
                "phone_tunables": {self.case["caller"]: {"call_tx_stall_after_s": 20}}}
        self.assertEqual({"call_link_renewal": True, "call_tx_stall_after_s": 20},
                         runner.broadcast_shape(call, call["caller"])["tunables"])
        self.assertEqual({"call_link_renewal": True}, runner.broadcast_shape(call, call["callee"])["tunables"])
        self.assertEqual({"a", "b"}, runner.validate_call_case(call, self.devices))
        for bad in ({"c": {"call_tx_stall_after_s": 20}}, {"a": {"no_such_tunable": 1}},
                    {"a": {"call_tx_stall_after_s": True}}, {"a": 20}):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                runner.validate_call_case({**self.case, "phone_tunables": bad}, self.devices)

    def test_requested_call_trace_is_initialized_for_both_endpoint_launches(self):
        call = {**self.case, "media_trace": True}
        run = runner.CallRunner("adb", self.devices, call, Path("recorded-motion-call"))
        self.assertEqual(run.trace_session, run.run_id)
        for phone in (call["caller"], call["callee"]):
            self.assertTrue(runner.broadcast_shape(call, phone)["media_trace"])

    def test_call_trace_identity_distinguishes_same_named_suite_outputs(self):
        call = {**self.case, "media_trace": True}
        with tempfile.TemporaryDirectory() as directory:
            runs = [runner.CallRunner("adb", self.devices, call, Path(directory) / root / "case" / "physical")
                    for root in ("first", "second", "first")]
            self.assertEqual(len(runs), len({run.run_id for run in runs}))
            self.assertEqual(len(runs), len({run.trace_session for run in runs}))

    def test_call_trace_collection_rejects_the_other_endpoints_pid_for_both_roles(self):
        for role in ("sender", "receiver"):
            with self.subTest(role=role), tempfile.TemporaryDirectory() as directory:
                instance = runner.CallRunner("adb", self.devices, {**self.case, "media_trace": True}, Path(directory))
                instance.logs = [{"device": "a", "pid": 123}, {"device": "b", "pid": 456}]
                phase = instance.trace_session + "-" + role + "-123"
                files = {phase + "-summary.json": json.dumps({"session": instance.trace_session, "role": role,
                    "phase": phase, "pid": 456, "csv": phase + ".csv", "ivf": phase + ".ivf"}).encode(),
                    phase + ".csv": b"samples", phase + ".ivf": b"payload"}
                def adb(phone, *args, **kwargs):
                    if args[:2] == ("shell", "ls"):
                        return "\n".join(files)
                    if args[:2] == ("shell", "sha256sum"):
                        return hashlib.sha256(files[Path(args[2]).name]).hexdigest() + " file"
                    if args[0] == "pull":
                        Path(args[2]).write_bytes(files[Path(args[1]).name])
                    return ""
                instance.adb = Mock(side_effect=adb)
                instance.collect_media_traces("a", role)
                self.assertEqual("FAIL", instance.trace_artifacts[0]["state"])
                self.assertIn("PID", instance.trace_artifacts[0]["reason"])
                self.assertFalse(any(call.args[1:4] == ("shell", "rm", "-f") for call in instance.adb.call_args_list))


    def test_call_trace_stays_disabled_when_not_requested(self):
        run = runner.CallRunner("adb", self.devices, self.case, Path("ordinary-call"))
        self.assertIsNone(run.trace_session)

    def test_bitrate_diagnostic_requires_an_explicit_direct_gatt_or_classic_boolean(self):
        case = {**self.case, "transport": "ble", "preserve_call_bitrate": True}
        with self.assertRaises(ValueError):
            runner.validate_call_case(case, self.devices)
        case["transport"] = "classic"
        self.assertEqual(runner.validate_call_case(case, self.devices), {"a", "b"})
        case["transport"] = "gatt"
        self.assertEqual(runner.validate_call_case(case, self.devices), {"a", "b"})
        case["preserve_call_bitrate"] = "true"
        with self.assertRaises(ValueError):
            runner.validate_call_case(case, self.devices)

    def test_priority_order_diagnostic_is_gatt_only_and_survives_both_launches(self):
        case = {**self.case, "gatt_defer_connection_priority": True}
        with self.assertRaises(ValueError):
            runner.validate_call_case(case, self.devices)
        case["transport"] = "gatt"
        self.assertEqual(runner.validate_call_case(case, self.devices), {"a", "b"})
        for value in ("true", 1):
            with self.assertRaises(ValueError):
                runner.validate_call_case({**case, "gatt_defer_connection_priority": value}, self.devices)
        for enabled in (False, True):
            instance = runner.CallRunner("adb", self.devices, {**case, "gatt_defer_connection_priority": enabled}, Path("unused"))
            for phone in (case["caller"], case["callee"]):
                with patch.object(runner.Runner, "launch", side_effect=lambda phone, source=False:
                                  instance.adb(phone, "shell", "am", "start", "--ez", "test_auto_broadcast", "true")):
                    with patch.object(runner.Runner, "adb", return_value="") as adb:
                        instance.launch(phone)
                        argv = adb.call_args.args
                        if enabled:
                            self.assertEqual(argv[argv.index("test_gatt_defer_connection_priority") + 1], "true")
                        else:
                            self.assertNotIn("test_gatt_defer_connection_priority", argv)

    def test_bitrate_diagnostic_is_sent_to_each_call_endpoint_only_when_requested(self):
        for override in (False, True):
            case = {**self.case, "transport": "gatt", "preserve_call_bitrate": override}
            instance = runner.CallRunner("adb", self.devices, case, Path("unused"))
            for phone in (case["caller"], case["callee"]):
                with patch.object(runner.Runner, "adb", return_value="") as adb:
                    instance.adb(phone, "shell", "am", "start", "--ez", "test_auto_broadcast", "true")
                    launch = adb.call_args.args
                    if override:
                        self.assertEqual(launch[launch.index("test_preserve_call_bitrate") + 1], "true")
                    else:
                        self.assertNotIn("test_preserve_call_bitrate", launch)
                    self.assertIn("test_private_call_browser", launch)

    def test_pipeline_timing_is_validated_and_preserved_for_each_endpoint(self):
        for value in ("true", 1):
            with self.assertRaises(ValueError):
                runner.validate_call_case({**self.case, "call_pipeline_timing": value}, self.devices)
        for enabled in (False, True):
            case = {**self.case, "call_pipeline_timing": enabled}
            self.assertEqual(runner.validate_call_case(case, self.devices), {"a", "b"})
            instance = runner.CallRunner("adb", self.devices, case, Path("unused"))
            for phone in (case["caller"], case["callee"]):
                with patch.object(runner.Runner, "launch", side_effect=lambda phone, source=False:
                                  instance.adb(phone, "shell", "am", "start", "--ez", "test_auto_broadcast", "true")):
                    with patch.object(runner.Runner, "adb", return_value="") as adb:
                        instance.launch(phone)
                        argv = adb.call_args.args
                        if enabled:
                            self.assertEqual(argv[argv.index("test_call_pipeline_timing") + 1], "true")
                        else:
                            self.assertNotIn("test_call_pipeline_timing", argv)

    def test_names_before_answer_for_one_endpoint_reaches_only_that_endpoints_launch(self):
        case = {**self.case, "tunables": {"bounded_media_age": True},
                "phone_tunables": {"b": {"names_before_answer": True}}}
        self.assertEqual({"a", "b"}, runner.validate_call_case(case, self.devices))
        instance = runner.CallRunner("adb", self.devices, case, Path("unused"))
        seen = {}
        def record(phone, source=False):
            seen[phone] = device_runner.phone_tunables(instance.case, phone)
        with patch.object(runner.Runner, "launch", side_effect=record):
            instance.launch("a")
            instance.launch("b")
        self.assertEqual({"bounded_media_age": True}, seen["a"])
        self.assertEqual({"bounded_media_age": True, "names_before_answer": True}, seen["b"])
        self.assertEqual({"test_bounded_media_age": True, "test_names_before_answer": True},
                         device_runner.tunable_extras(seen["b"]))

    def test_broadcast_output_diagnostics_are_rejected_for_calls(self):
        for field in ("video_presentation_timing", "release_video_at_playout"):
            for value in (False, True):
                with self.assertRaisesRegex(ValueError, "broadcast receivers only"):
                    runner.validate_call_case({**self.case, field: value}, self.devices)

    def test_invalid_or_clamped_setting_is_rejected_before_device_control(self):
        for field, value in (("fps", 61), ("bitrate_kbps", 20), ("playback", "medium"),
                             ("rate_control", "unknown"), ("thermal_reduction", "false")):
            case = copy.deepcopy(self.case)
            case["profiles"]["a"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                runner.validate_call_case(case, self.devices)

    def test_unknown_relay_and_invalid_setup_controls_are_explicit(self):
        for changes in ({"relay": "c"}, {"setup_action": "busy"},
                        {"setup_action": "decline", "actions": [{"phone": "a", "action": "mute", "at_s": 10}]},
                        {"actions": [{"phone": "b", "action": "pause", "at_s": 10}]}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                runner.validate_call_case({**self.case, **changes}, self.devices)

    def test_encoded_replay_is_rejected_for_calls_before_any_device_control(self):
        for key, value in (("replay_file", "never-staged.ivf"), ("replay_files", {"a": "never-staged.ivf"}),
                           ("replay_sha256", "a" * 64), ("replay_sha256s", {"a": "a" * 64})):
            with self.subTest(key=key), patch.object(runner.subprocess, "run", side_effect=AssertionError("device I/O")), \
                 patch.object(runner.subprocess, "Popen", side_effect=AssertionError("device process")):
                requested = {**self.case, key: value}
                with self.assertRaisesRegex(ValueError, "replay.*not supported.*calls"):
                    runner.validate_call_case(requested, self.devices)
                with self.assertRaisesRegex(ValueError, "replay.*not supported.*calls"):
                    runner.CallRunner("adb", self.devices, requested, Path("unused"))

    def test_asymmetric_call_scores_own_encoder_and_peer_visible_fps_independently(self):
        score = self.score(self.log("a", 15, 30), self.log("b", 30, 15))
        for result, own, peer in zip(score["devices"], (15, 30), (30, 15)):
            self.assertEqual(result["outgoing"]["expected_fps"], own)
            self.assertEqual(result["incoming"]["expected_fps"], peer)
            self.assertEqual(result["outgoing"]["gates"]["encoder_fps"]["status"], "PASS")
            self.assertEqual(result["incoming"]["gates"]["visible_fps"]["status"], "PASS")
            self.assertEqual(result["incoming"]["gates"]["profile_fps_mismatches"]["status"], "PASS")
            self.assertEqual(result["gates"]["negotiated_remote_header_mismatches"]["status"], "PASS")
            self.assertEqual(result["requested_profile_coverage"], "MEASURED")
        self.assertEqual(score["overall_status"], "UNMEASURED")
        self.assertEqual(score["devices"][0]["outgoing"]["gates"]["sender_drops"]["status"], "UNMEASURED")

    def drop_first_surface_line(self, log, ago_ms=None):
        # Oppo A15s calls-noloss-s24a15s-1756e738: logd lost the one-off first-surface line (and 80-90% of
        # app lines) while a periodic 'visible continuity' report from the same TextureView survived.
        kept = [line for line in log.splitlines(True) if "media-to-first-surface" not in line
                and not (" 1 frames" in line and "visible continuity" in line)]
        if ago_ms is not None:
            kept = [line.rstrip(chr(10)) + f" | first surface 1000.0 ms after start, {ago_ms:.1f} ms ago" + chr(10)
                    if "visible continuity:" in line else line for line in kept]
        return "".join(kept)

    def test_a_surviving_continuity_report_dates_the_lost_first_surface_line(self):
        full = self.score(self.log("a", 15, 15), self.log("b", 15, 15))
        # First continuity row survives at 00:00:08.000 and says the first frame was 5000 ms earlier (00:03).
        lossy = self.score(self.drop_first_surface_line(self.log("a", 15, 15), 5000.0),
                           self.drop_first_surface_line(self.log("b", 15, 15), 5000.0))
        for before, after in zip(full["devices"], lossy["devices"]):
            self.assertEqual(2500.0, before["gates"]["setup_to_first_surface_ms"]["value"])
            self.assertAlmostEqual(before["gates"]["setup_to_first_surface_ms"]["value"],
                                   after["gates"]["setup_to_first_surface_ms"]["value"], places=3)

    def test_continuity_without_a_first_surface_date_leaves_startup_unmeasured(self):
        lossy = self.score(self.drop_first_surface_line(self.log("a", 15, 15)),
                           self.drop_first_surface_line(self.log("b", 15, 15)))
        for result in lossy["devices"]:
            self.assertEqual("UNMEASURED", result["gates"]["setup_to_first_surface_ms"]["status"])

    def test_surface_wait_accepts_only_a_continuity_report_with_rendered_frames(self):
        rows = runner.records(self.line(4, "Private call visible continuity: 0 frames | 0 freeze event(s), 0.0 ms")
                              + self.line(5, "Private call visible continuity FINAL: 9 frames | 0 freeze event(s)"))
        self.assertFalse(any(runner.re.search(runner.VIDEO_SURFACE_EVIDENCE, row["message"]) for row in rows))
        for message in ("Private call startup: media-to-first-surface 812.0 ms",
                        "Private call visible continuity: 248 frames | 1 freeze event(s), 1213.1 ms, 5.76%"):
            self.assertRegex(message, runner.VIDEO_SURFACE_EVIDENCE)
        self.assertEqual((None, None), runner.first_surface_time(rows))

    def test_negotiated_call_budget_never_passes_the_higher_requested_load(self):
        self.case["profiles"]["a"]["bitrate_kbps"] = 1000
        score = self.score(self.log("a", 15, 30, requested_rate=1000, negotiated_rate=250), self.log("b", 30, 15))
        result = score["devices"][0]
        self.assertEqual(result["gates"]["selected_profile_mismatches"]["status"], "PASS")
        self.assertEqual(result["requested_profile_coverage"], "UNMEASURED")
        self.assertEqual(result["negotiated_changes"]["bitrate_kbps"], {"requested": 1000, "negotiated": "250"})
        self.assertEqual(result["outgoing"]["offered_load"]["requested_kbps"], 250)
        self.assertEqual(score["quality_status"], "UNMEASURED")

    def test_wrong_authenticated_peer_is_failure_and_missing_headers_unmeasured(self):
        score = self.score(self.log("a", 15, 30, wrong_peer=True), self.log("b", 30, 15))
        self.assertEqual(score["devices"][0]["gates"]["authenticated_peer"]["status"], "FAIL")
        self.assertEqual(score["overall_status"], "FAIL")
        a = "\n".join(line for line in self.log("a", 15, 30).splitlines() if "call remote:" not in line)
        score = self.score(a, self.log("b", 30, 15))
        self.assertEqual(score["devices"][0]["gates"]["negotiated_remote_header_mismatches"]["status"], "UNMEASURED")
        self.assertEqual(score["devices"][0]["incoming"]["gates"]["profile_fps_mismatches"]["status"], "UNMEASURED")

    def test_authenticated_private_header_fps_drift_fails_incoming_profile(self):
        a = self.log("a", 15, 30).replace("fps=30 | rotation=0", "fps=29 | rotation=0")
        score = self.score(a, self.log("b", 30, 15))
        self.assertEqual(score["devices"][0]["incoming"]["gates"]["profile_fps_mismatches"]["status"], "FAIL")

    def test_selected_substitution_and_wrong_actual_allocation_cannot_pass(self):
        a = self.log("a", 15, 30).replace("fps=15 | bitrateKbps=150 | playbackMs=250", "fps=10 | bitrateKbps=150 | playbackMs=250", 1)
        a += self.line(6, "Call camera: Streaming VP9 at 640x360, 15 FPS, 150 kbps, Scene-adaptive bitrate (VBR) mode 1")
        score = self.score(a, self.log("b", 30, 15))
        gates = score["devices"][0]["gates"]
        self.assertEqual(gates["selected_profile_mismatches"]["status"], "FAIL")
        self.assertEqual(gates["actual_allocation_mismatches"]["status"], "FAIL")
        self.assertEqual(score["devices"][0]["requested_profile_coverage"], "UNMEASURED")
        self.assertEqual(score["overall_status"], "FAIL")

    def test_missing_actual_encoder_profile_does_not_measure_requested_settings(self):
        a = "\n".join(line for line in self.log("a", 15, 30).splitlines() if "Call camera:" not in line)
        score = self.score(a, self.log("b", 30, 15))
        self.assertEqual(score["devices"][0]["requested_profile_coverage"], "UNMEASURED")

    def test_nested_statuses_use_the_final_call_specific_gates(self):
        a = self.log("a", 15, 30) + self.line(9, "Call video recovered at a keyframe after 4 media drops")
        score = self.score(a, self.log("b", 30, 15))
        outgoing = score["devices"][0]["outgoing"]
        self.assertEqual(outgoing["gates"]["sender_drops"]["status"], "FAIL")
        self.assertEqual(outgoing["log_status"], "FAIL")
        self.assertEqual(outgoing["overall_status"], "FAIL")

    def tx(self, second=14, final=True, drops=0, call_id="00000000-0000-0000-0000-000000000001", pid=123):
        return self.line(second, f"BENCHMARK call tx: callId={call_id} | final={str(final).lower()} | "
                         f"queue=2/48 | pendingAudio=1 | videoInputs=180 | audioInputs=200 | configInputs=2 | "
                         f"videoWrites=178 | audioWrites=99 | configWrites=2 | queueDrops={drops} | "
                         "inactiveInputs=3 | mutedAudio=50", pid=pid)

    def identified_log(self, phone, own_fps, remote_fps):
        return self.log(phone, own_fps, remote_fps) + self.line(
            2, "BENCHMARK call identity: callId=00000000-0000-0000-0000-000000000001")

    def test_final_tx_loss_cannot_be_hidden_by_the_last_periodic_zero(self):
        a = self.identified_log("a", 15, 30) + self.tx(10, final=False) + self.tx(drops=3)
        b = self.identified_log("b", 30, 15) + self.tx()
        score = self.score(a, b)
        outgoing = score["devices"][0]["outgoing"]
        self.assertEqual(outgoing["gates"]["sender_drops"]["value"], 3)
        self.assertEqual(outgoing["gates"]["sender_drops"]["status"], "FAIL")
        self.assertEqual(outgoing["overall_status"], "FAIL")

    def test_periodic_zero_without_owned_final_tx_cannot_pass(self):
        b = self.identified_log("b", 30, 15) + self.tx()
        base = self.identified_log("a", 15, 30) + self.tx(10, final=False)
        for extra in ("", self.tx(call_id="00000000-0000-0000-0000-000000000002"), self.tx(pid=999)):
            with self.subTest(extra=extra):
                score = self.score(base + extra, b)
                self.assertEqual(score["devices"][0]["outgoing"]["gates"]["sender_drops"]["status"], "UNMEASURED")

    def test_muted_inactive_and_pending_end_cleanup_are_separate_from_queue_loss(self):
        a = self.identified_log("a", 15, 30) + self.tx()
        b = self.identified_log("b", 30, 15) + self.tx()
        score = self.score(a, b)
        outgoing = score["devices"][0]["outgoing"]
        self.assertEqual(outgoing["gates"]["sender_drops"]["status"], "PASS")
        self.assertEqual(outgoing["call_tx"]["final_report"]["mutedAudio"], "50")
        self.assertEqual(outgoing["call_tx"]["final_report"]["configWrites"], "2")
        self.assertEqual(score["devices"][0]["gates"]["call_id_mismatches"]["status"], "PASS")
        self.assertEqual(score["quality_status"], "UNMEASURED")

    def test_cumulative_tx_counter_cannot_reset_to_zero_before_final(self):
        a = self.identified_log("a", 15, 30) + self.tx(10, final=False, drops=3) + self.tx()
        b = self.identified_log("b", 30, 15) + self.tx()
        score = self.score(a, b)
        gates = score["devices"][0]["outgoing"]["gates"]
        self.assertEqual(gates["sender_drops"]["status"], "FAIL")
        self.assertEqual(gates["call_tx_counter_regressions"]["status"], "FAIL")

    def test_different_authenticated_call_ids_cannot_share_zero_loss_proof(self):
        a = self.identified_log("a", 15, 30) + self.tx()
        b = (self.identified_log("b", 30, 15) + self.tx()).replace(
            "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002")
        score = self.score(a, b)
        self.assertEqual(score["devices"][0]["gates"]["call_id_mismatches"]["status"], "FAIL")

    def test_final_rx_late_losses_remain_failures_and_missing_totals_do_not_pass(self):
        a = self.identified_log("a", 15, 30) + self.tx()
        b = self.identified_log("b", 30, 15) + self.tx()
        score = self.score(a, b)
        gates = score["devices"][0]["incoming"]["gates"]
        self.assertEqual(gates["audio_drops"]["status"], "UNMEASURED")
        self.assertEqual(gates["decoder_drops"]["status"], "UNMEASURED")
        a += self.line(14, "BENCHMARK call rx: callId=00000000-0000-0000-0000-000000000001 | "
                       "final=true | audioDrops=3 | decoderDrops=2 | videoPackets=150 | audioPackets=150")
        score = self.score(a, b)
        gates = score["devices"][0]["incoming"]["gates"]
        self.assertEqual(gates["audio_drops"]["value"], 3)
        self.assertEqual(gates["decoder_drops"]["value"], 2)
        self.assertEqual(gates["audio_drops"]["status"], "FAIL")
        self.assertEqual(gates["decoder_drops"]["status"], "FAIL")

    def test_final_rx_unavailable_decoder_is_not_an_invented_zero(self):
        a = self.identified_log("a", 15, 30) + self.tx() + self.line(14,
            "BENCHMARK call rx: callId=00000000-0000-0000-0000-000000000001 | "
            "final=true | audioDrops=0 | decoderDrops=unavailable | videoPackets=0 | audioPackets=150")
        b = self.identified_log("b", 30, 15) + self.tx()
        score = self.score(a, b)
        gates = score["devices"][0]["incoming"]["gates"]
        self.assertEqual(gates["audio_drops"]["status"], "PASS")
        self.assertEqual(gates["decoder_drops"]["status"], "UNMEASURED")

    def test_lifecycle_local_ack_without_remote_or_surface_evidence_cannot_pass(self):
        for name, extra in (("mute", {}), ("flip", {}), ("video_on", {"remote_ack": {"device": "b"}}),
                            ("accept", {}), ("end", {}), ("background", {}),
                            ("call:000000000002", {"remote_ack": {"device": "b"}})):
            event = {"device": "a", "action": name, "acknowledged": True, **extra}
            score = self.score(self.log("a", 15, 30), self.log("b", 30, 15), control_events=[event])
            with self.subTest(action=name):
                self.assertEqual(score["lifecycle"][0]["gate"]["status"], "UNMEASURED")

    def test_lifecycle_surface_recovery_limit_and_normal_hangup_reuse_are_measured(self):
        events = [{"device": "a", "action": "video_on", "acknowledged": True,
                   "remote_ack": {"device": "b"}, "surface_recovery": {"device": "b", "ms": 6000}},
                  {"device": "a", "action": "end", "acknowledged": True,
                   "terminal_evidence": {phone: {"ended": "10-03 00:00:15.000", "available": "10-03 00:00:15.100"}
                                         for phone in "ab"}}]
        score = self.score(self.log("a", 15, 30), self.log("b", 30, 15), control_events=events)
        self.assertEqual(score["lifecycle"][0]["gate"]["status"], "FAIL")
        self.assertEqual(score["lifecycle"][1]["gate"]["status"], "PASS")

    def test_legacy_audio_negotiation_is_explicit_and_not_default_opus32_coverage(self):
        a = self.log("a", 15, 30).replace("audioKbps=32", "audioKbps=16")
        score = self.score(a, self.log("b", 30, 15))
        result = score["devices"][0]
        self.assertEqual(result["negotiated_changes"]["audio_kbps"], {"requested_default": 32, "negotiated": 16})
        self.assertEqual(result["requested_profile_coverage"], "UNMEASURED")

    def test_launcher_replaces_only_broadcast_mode_and_preserves_settings(self):
        instance = runner.CallRunner("adb", self.devices, self.case, Path("unused"))
        with patch.object(runner.Runner, "adb", return_value="") as adb:
            instance.adb("a", "shell", "am", "start", "--ez", "test_auto_broadcast", "true",
                         "--ei", "test_bitrate_kbps", "1100")
        argv = adb.call_args.args
        self.assertIn("test_private_call_browser", argv)
        self.assertNotIn("test_auto_broadcast", argv)
        self.assertEqual(argv[-2:], ("test_bitrate_kbps", "1100"))

    def test_applied_invite_without_outgoing_phase_stops_before_remote_ringing_wait(self):
        instance = runner.CallRunner("adb", self.devices, self.case, Path("unused"))
        instance.control = Mock(return_value={"acknowledged": True, "time": "10-03 00:00:10.000",
                                             "ack_time": "10-03 00:00:10.100"})
        instance.wait_for = Mock(side_effect=RuntimeError("No outgoing phase after applied selection"))
        with self.assertRaisesRegex(RuntimeError, "No outgoing phase"):
            instance.invite()
        instance.control.assert_called_once_with("a", "call:000000000002")
        self.assertEqual(instance.wait_for.call_count, 1)
        self.assertEqual(instance.wait_for.call_args.args[0], "a")
        self.assertIn("OUTGOING_INVITE", instance.wait_for.call_args.args[1])

    def test_both_call_raw_hash_guards_reach_runtime_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            case = copy.deepcopy(self.case)
            content = bytes(640 * 360 * 3 // 2)
            case["raw_fixture_files"], case["raw_fixture_sha256s"], case["raw_fixture_metadatas"] = {}, {}, {}
            for phone in "ab":
                fixture = Path(directory) / (phone + ".yuv")
                fixture.write_bytes(content)
                case["raw_fixture_files"][phone] = str(fixture)
                case["raw_fixture_sha256s"][phone] = hashlib.sha256(content).hexdigest()
                case["raw_fixture_metadatas"][phone] = {"source": phone}
                self.assertEqual(case["raw_fixture_sha256s"][phone], runner.broadcast_shape(case, phone)["raw_fixture_sha256"])
            instance = runner.CallRunner("adb", self.devices, case, Path("unused"))
            self.assertEqual({"source": "b"}, instance.raw_fixtures["b"]["declared_metadata"])
            Path(case["raw_fixture_files"]["b"]).write_bytes(b"\x01" + content[1:])
            with patch.object(runner.subprocess, "run", side_effect=AssertionError("device I/O")):
                with self.assertRaisesRegex(ValueError, "frozen case SHA256"):
                    runner.CallRunner("adb", self.devices, case, Path("unused"))
            case["raw_fixture_sha256s"]["unknown"] = "a" * 64
            with self.assertRaisesRegex(ValueError, "hash guards"):
                runner.validate_call_case(case, self.devices)

    def test_each_raw_call_endpoint_stages_its_own_exact_geometry_and_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            case = copy.deepcopy(self.case)
            case["profiles"]["b"]["height"] = 480
            case["raw_fixture_files"] = {}
            for phone in "ab":
                fixture = Path(directory) / (phone + ".yuv")
                profile = case["profiles"][phone]
                fixture.write_bytes(bytes(profile["width"] * profile["height"] * 3 // 2))
                case["raw_fixture_files"][phone] = str(fixture)
            instance = runner.CallRunner("adb", self.devices, case, Path("unused"))
            staged = []
            def launch(phone, source=False):
                staged.append((phone, instance.raw_fixture["height"], instance.raw_fixture["sha256"],
                               instance.case["raw_fixture_file"]))
            with patch.object(runner.Runner, "launch", side_effect=launch):
                instance.launch("b")
                instance.launch("a")
            self.assertEqual([item[1] for item in staged], [480, 360])
            self.assertNotEqual(staged[0][2], staged[1][2])
            self.assertEqual(staged[0][3], case["raw_fixture_files"]["b"])
            self.assertEqual(instance.case, case)
            case["actions"] = [{"phone": "a", "action": "flip", "at_s": 10}]
            with self.assertRaisesRegex(ValueError, "physical camera flip"):
                runner.validate_call_case(case, self.devices)
            case["actions"] = []
            case["raw_fixture_files"]["b"] = case["raw_fixture_files"]["a"]
            with self.assertRaisesRegex(ValueError, "exact-size"):
                runner.validate_call_case(case, self.devices)

    def test_invite_retains_actual_outgoing_and_incoming_phase_evidence(self):
        instance = runner.CallRunner("adb", self.devices, self.case, Path("unused"))
        instance.control = Mock(return_value={"acknowledged": True, "time": "10-03 00:00:10.000",
                                             "ack_time": "10-03 00:00:10.100"})
        instance.wait_for = Mock(side_effect=[{"time": "10-03 00:00:10.300", "message": "BENCHMARK call phase OUTGOING_INVITE"},
                                             {"time": "10-03 00:00:10.500", "message": "BENCHMARK call phase INCOMING_RINGING"}])
        event = instance.invite()
        self.assertEqual([call.args[0] for call in instance.wait_for.call_args_list], ["a", "b"])
        self.assertEqual(event["outgoing_phase"]["time"], "10-03 00:00:10.300")
        # A relayed call logs its phase just before the control ack, so the search starts at the request.
        self.assertEqual(instance.wait_for.call_args_list[0].args[2], "10-03 00:00:10.000")
        self.assertEqual(event["remote_ack"]["device"], "b")
        self.assertEqual(event["remote_ack"]["time"], "10-03 00:00:10.500")

    def test_video_control_intervals_do_not_hide_the_unrelated_direction(self):
        instance = runner.CallRunner("adb", self.devices, self.case, Path("unused"))
        instance.phone_time = Mock(return_value="10-03 00:00:10.000")
        instance.control = Mock(side_effect=[{"time": "10-03 00:00:10.000", "ack_time": "10-03 00:00:10.100"},
                                            {"time": "10-03 00:00:15.000", "ack_time": "10-03 00:00:15.100"}])
        instance.wait_for = Mock(side_effect=[{"time": "10-03 00:00:10.200", "at": runner.timestamp("10-03 00:00:10.200"), "message": "camera off"},
                                             {"time": "10-03 00:00:15.200", "at": runner.timestamp("10-03 00:00:15.200"), "message": "camera on"},
                                             {"time": "10-03 00:00:15.400", "at": runner.timestamp("10-03 00:00:15.400"), "message": "surface"}])
        instance.action({"phone": "a", "action": "video_off"})
        instance.action({"phone": "a", "action": "video_on"})
        self.assertEqual([item["device"] for item in instance.outgoing_intervals], ["a"])
        self.assertEqual([item["device"] for item in instance.incoming_intervals], ["b"])
        self.assertEqual(instance.incoming_intervals[0]["end"], "10-03 00:00:15.400")

    def test_end_accepts_the_ui_teardown_record_when_the_call_thread_record_is_lost_and_says_so(self):
        # calls-noloss-m31asus-51cc8028/0007: ASUS logd lost "Private call ended:"; the end had been handled.
        instance = runner.CallRunner("adb", self.devices, self.case, Path("unused"))
        instance.phone_time = Mock(return_value="10-03 00:00:10.000")
        event = {"time": "10-03 00:00:10.000", "ack_time": "10-03 00:00:10.100"}
        instance.control = Mock(return_value=event)
        row = lambda time, message: {"time": time, "at": runner.timestamp(time), "message": message}
        instance.wait_for = Mock(side_effect=[
            row("10-03 00:00:10.300", "Private call ended: Call ended"), row("10-03 00:00:10.500", "BENCHMARK call available: x"),
            row("10-03 00:00:10.620", "BENCHMARK call ended_ui: message=Peer ended the call"),
            row("10-03 00:00:10.800", "BENCHMARK call available: x")])
        instance.action({"phone": "a", "action": "end"})
        self.assertEqual(instance.wait_for.call_args_list[0].args[1], runner.ENDED_EVIDENCE)
        self.assertNotIn("ended_source", event["terminal_evidence"]["a"])
        self.assertIn("ended_source", event["terminal_evidence"]["b"])
        self.assertEqual(event["terminal_evidence"]["b"]["ended"], "10-03 00:00:10.620")

    def test_terminal_proof_names_the_ui_record_only_when_it_replaced_the_call_thread_record(self):
        available = {"time": "10-03 00:00:10.800"}
        callback = runner.ended_proof({"time": "t", "message": "Private call ended: Call ended"}, available)
        ui = runner.ended_proof({"time": "t", "message": "BENCHMARK call ended_ui: message=Call ended"}, available)
        self.assertEqual(callback, {"ended": "t", "available": "10-03 00:00:10.800"})
        self.assertIn("ended_source", ui)
        self.assertEqual(ui["ended"], "t")

    def test_inventory_failure_is_a_failed_run_not_an_empty_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            manifest = {"scenario": self.case, "logs": [], "runner_error": "ADB absent"}
            self.assertEqual(runner.score_call_manifest(manifest, Path(folder))["overall_status"], "FAIL")

    def test_both_same_old_apk_hashes_do_not_satisfy_the_required_candidate(self):
        with tempfile.TemporaryDirectory() as directory:
            case = {**self.case, "expected_apk_sha256": "a" * 64}
            instance = runner.CallRunner("adb", self.devices, case, Path(directory) / "run")
            instance.inventory = Mock(return_value={"apk_sha256": "b" * 64})
            instance.launch = Mock()
            instance.phone_time = Mock(return_value="10-03 00:00:00.000")
            instance.adb = Mock()
            instance.beep = Mock()
            manifest = instance.run()
            self.assertEqual(manifest["run_id"], instance.run_id)
            instance.launch.assert_not_called()
            self.assertIn("frozen APK hash", manifest["runner_error"])


class RotatingCalleeDiscoveryTest(unittest.TestCase):
    """A stranger (rotating-ID) callee is found only through the discovery ID its own log announced."""

    def setUp(self):
        self.devices = {"a": {"serial": "a", "node_prefix": "000000000001"},
                        "b": {"serial": "b", "node_prefix": "000000000002"}}
        profile = {"codec": "e", "width": 640, "height": 360, "fps": 15, "bitrate_kbps": 150,
                   "playback": "balanced", "rate_control": "cbr", "thermal_reduction": False}
        self.case = {"id": "rot", "caller": "a", "callee": "b", "transport": "gatt", "duration_s": 60,
                     "profiles": {"a": profile, "b": profile}, "tunables": {"rotating_ids": True},
                     "connection_timeout_s": 3}
        self.logs = {"a": "", "b": ""}

    @staticmethod
    def line(second, message, pid=123):
        return f"10-03 00:00:{second:06.3f} {pid} 123 I BluetoothVideo: {message}\n"

    def own(self, second, rdi, epoch=1):
        return self.line(second, f"BENCHMARK rotid_own: role=call | epoch={epoch} | rdi={rdi}")

    def seen(self, second, node):
        return self.line(second, f"BENCHMARK call discovered: node={node} | transport=BLE_GATT")

    def runner(self, case=None):
        instance = runner.CallRunner("adb", self.devices, case or self.case, Path("rot-discovery"))
        instance.logs = [{"device": "a"}, {"device": "b"}]
        instance.log_text = lambda phone: self.logs[phone]
        instance.supervise_captures = Mock()
        return instance

    def clock(self, ticks_before_deadline=50):
        now = [0.0]
        def monotonic():
            return now[0]
        def sleep(seconds):
            now[0] += seconds
            if self.on_poll:
                self.on_poll()
        return monotonic, sleep

    on_poll = None

    def test_a_rotating_callee_is_matched_through_its_own_advertised_id(self):
        self.logs["b"] = self.own(1, "3c0544356ffd")
        self.logs["a"] = self.seen(2, "3c0544356ffd")
        instance = self.runner()
        row = instance.discover_peer("a", "b")
        self.assertIn("node=3c0544356ffd", row["message"])
        self.assertEqual("3c0544356ffd", instance.dial_prefix("a", "b"))
        self.assertEqual("rotating_peer_bound", instance.events[-1]["action"])

    def test_another_phones_rotating_id_is_never_accepted(self):
        self.logs["b"] = self.own(1, "3c0544356ffd")
        self.logs["a"] = self.seen(2, "4f171123640a") + self.seen(3, "c3c7176b4aa7") + self.seen(4, "000000000002")
        instance = self.runner()
        monotonic, sleep = self.clock()
        with patch.object(runner.time, "monotonic", monotonic), patch.object(runner.time, "sleep", sleep),              patch.object(instance, "late_evidence", return_value=None),              patch.object(instance, "dump_log_lines", return_value=([], [])),              patch.object(instance, "log_loss_error", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "a: no app evidence for .*3c0544356ffd"):
                instance.discover_peer("a", "b")
        self.assertEqual({}, instance.dial_ids)

    def test_a_callee_that_never_logs_its_own_id_cannot_be_bound(self):
        self.logs["a"] = self.seen(2, "4f171123640a")
        instance = self.runner()
        monotonic, sleep = self.clock()
        with patch.object(runner.time, "monotonic", monotonic), patch.object(runner.time, "sleep", sleep):
            with self.assertRaisesRegex(RuntimeError, "b: rotating IDs are on but the app logged no 'rotid_own'"):
                instance.discover_peer("a", "b")

    def test_the_wait_follows_an_epoch_rollover_to_the_new_id(self):
        # The caller has scanned the old ID; the callee then rolls over and the caller scans the new one.
        self.logs["b"] = self.own(1, "aaaaaaaaaaaa", epoch=1)
        self.logs["a"] = self.seen(2, "aaaaaaaaaaaa")
        instance = self.runner()
        instance.dial_ids["b"] = "aaaaaaaaaaaa"
        self.assertEqual("aaaaaaaaaaaa", instance.dial_prefix("a", "b"))
        self.logs["b"] += self.own(5, "bbbbbbbbbbbb", epoch=2)  # boundary: the old row is stale now
        polls = []
        def on_poll():
            polls.append(1)
            if len(polls) == 3:
                self.logs["a"] += self.seen(6, "bbbbbbbbbbbb")
        monotonic, sleep = self.clock()
        self.on_poll = on_poll
        with patch.object(runner.time, "monotonic", monotonic), patch.object(runner.time, "sleep", sleep):
            self.assertEqual("bbbbbbbbbbbb", instance.dial_prefix("a", "b"))
        self.assertGreaterEqual(len(polls), 3)
        self.assertEqual("bbbbbbbbbbbb", instance.events[-1]["rdi"])

    def test_a_fixed_id_callee_keeps_the_registry_prefix(self):
        case = {**self.case, "tunables": {}}
        self.logs["a"] = self.seen(2, "000000000002")
        instance = self.runner(case)
        self.assertIn("node=000000000002", instance.discover_peer("a", "b")["message"])
        self.assertEqual("000000000002", instance.dial_prefix("a", "b"))

    def test_the_invite_dials_the_rotating_id(self):
        self.logs["b"] = self.own(1, "3c0544356ffd")
        self.logs["a"] = self.seen(2, "3c0544356ffd")
        instance = self.runner()
        instance.discover_peer("a", "b")
        instance.control = Mock(return_value={"ack_time": "10-03 00:00:03.000", "time": "10-03 00:00:03.000"})
        instance.wait_for = Mock(return_value={"time": "10-03 00:00:03.100", "message": "x"})
        instance.invite()
        instance.control.assert_called_once_with("a", "call:3c0544356ffd")


class RelayedPrivateCallTest(unittest.TestCase):
    """caller a -> opaque relay r -> callee b (one middle hop, BluetoothPrivateCallRelay)."""

    def setUp(self):
        self.devices = {"a": {"serial": "a", "node_prefix": "000000000001"},
                        "b": {"serial": "b", "node_prefix": "000000000002"},
                        "r": {"serial": "r", "node_prefix": "000000000003"}}
        profile = {"codec": "encoder-a", "width": 352, "height": 288, "fps": 15,
                   "bitrate_kbps": 110, "playback": "balanced", "rate_control": "cbr", "thermal_reduction": False}
        self.case = {"id": "relay-call", "caller": "a", "callee": "b", "relay": "r", "transport": "gatt",
                     "duration_s": 45, "profiles": {"a": profile, "b": {**profile, "codec": "encoder-b"}},
                     "tunables": {"bounded_media_age": True}}

    line = staticmethod(PrivateCallBenchmarkTest.line)

    def test_relay_schema_accepts_one_named_middle_phone_as_a_participant(self):
        self.assertEqual(runner.validate_call_case(self.case, self.devices), {"a", "b", "r"})
        self.assertEqual(runner.validate_call_case({**self.case, "relay": ["r"]}, self.devices), {"a", "b", "r"})
        self.assertEqual(runner.call_relays(self.case), ["r"])

    def test_relay_schema_rejects_what_the_app_cannot_route(self):
        devices = {**self.devices, "s": {"serial": "s", "node_prefix": "000000000004"}}
        twin = copy.deepcopy(self.devices)
        twin["r"]["node_prefix"] = twin["b"]["node_prefix"]
        for changes, registry in (({"relays": ["r", "r"]}, self.devices), ({"relay": "a"}, self.devices),
                                  ({"relays": ["r"]}, devices),
                                  ({"relay": "zz"}, self.devices), ({"relay": []}, self.devices),
                                  ({"relay": 3}, self.devices), ({}, twin),
                                  ({"gatt_defer_connection_priority": True}, self.devices),
                                  ({"preserve_call_bitrate": True}, self.devices)):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                runner.validate_call_case({**self.case, **changes}, registry)
        profiles = {**self.case["profiles"], "r": self.case["profiles"]["a"]}
        with self.assertRaises(ValueError):
            runner.validate_call_case({**self.case, "profiles": profiles}, self.devices)

    def test_relay_launch_keeps_transport_and_tunables_and_drops_media_settings(self):
        argv = ["shell", "am", "start", "-W", "-n", runner.COMPONENT, "--es", "test_transport", "gatt",
                "--es", "test_codec", "encoder-a", "--es", "test_media_trace", "run-1",
                "--ez", "test_auto_broadcast", "true", "--es", "test_role", "send", "--ei", "test_width", "352",
                "--ei", "test_bitrate_kbps", "110", "--ez", "test_bounded_media_age", "true",
                "--ef", "test_playback_gain", "0.05"]
        result = runner.relay_launch_args(argv)
        self.assertEqual(result[:6], argv[:6])
        pairs = dict(zip(result[7::3], result[8::3]))
        self.assertEqual(pairs, {"test_transport": "gatt", "test_bounded_media_age": "true", "test_playback_gain": "0.05",
                                 "test_auto_start": "true", "test_role": "relay", "test_private_call_relay": "true"})

    def test_only_the_relay_launch_is_rewritten(self):
        instance = runner.CallRunner("adb", self.devices, self.case, Path("unused"))
        launch = ["shell", "am", "start", "-W", "-n", runner.COMPONENT, "--ez", "test_auto_broadcast", "true",
                  "--es", "test_role", "send"]
        with patch.object(runner.Runner, "adb", return_value="") as adb:
            instance.relay_launch = "r"
            instance.adb("r", *launch)
            instance.adb("a", *launch)
        relay, endpoint = (call.args for call in adb.call_args_list)
        self.assertIn("test_private_call_relay", relay)
        self.assertNotIn("test_private_call_browser", relay)
        self.assertIn("test_private_call_browser", endpoint)
        self.assertNotIn("test_private_call_relay", endpoint)

    def test_relay_runner_owns_all_three_phones_but_media_inputs_only_on_endpoints(self):
        instance = runner.CallRunner("adb", self.devices, self.case, Path("unused"))
        self.assertEqual(instance.participants, {"a", "b", "r"})
        self.assertEqual(set(instance.raw_fixtures), {"a", "b"})

    def test_route_starts_callee_then_relay_then_caller_and_stops_on_a_wrong_callee(self):
        for authenticated, error in (("000000000002", None), ("0000000000ff", "not its requested child b")):
            instance = runner.CallRunner("adb", self.devices, self.case, Path("unused"))
            order = []
            instance.launch = Mock(side_effect=order.append)
            instance.launch_relay = Mock(side_effect=order.append)
            instance.endpoint_ready = Mock()

            def wait(phone, expression, since=None, timeout=None, node=authenticated):
                if expression == runner.RELAY_CALLEE:
                    return {"time": "t1", "message": f"Private-call relay authenticated callee {node}."}
                return {"time": "t2", "message": "Private-call GATT relay is advertising for its caller."}
            instance.wait_for = Mock(side_effect=wait)
            with self.subTest(authenticated=authenticated):
                if error:
                    with self.assertRaisesRegex(RuntimeError, error):
                        instance.start_relayed_route("a", "b")
                    self.assertEqual(order, ["b", "r"])
                else:
                    instance.start_relayed_route("a", "b")
                    self.assertEqual(order, ["b", "r", "a"])
                    self.assertRegex(instance.wait_for.call_args_list[-1].args[1], "discovered: node=000000000002")

    def test_relay_join_must_name_the_requested_caller_and_callee(self):
        instance = runner.CallRunner("adb", self.devices, self.case, Path("unused"))
        message = ("Relaying an endpoint-encrypted private call between 000000000001 and 000000000002; "
                   "this relay cannot decrypt call media.")
        instance.wait_for = Mock(return_value={"time": "t", "message": message})
        instance.confirm_relayed_join("a", "b")
        self.assertEqual(instance.relay_events[-1]["parent"], "000000000001")
        instance.wait_for = Mock(return_value={"time": "t", "message": message.replace("000000000001", "0000000000aa")})
        with self.assertRaisesRegex(RuntimeError, "not its requested hop a -> r -> b"):
            instance.confirm_relayed_join("a", "b")

    def endpoint_log(self, phone):
        peer = "b" if phone == "a" else "a"
        return (self.line(0.1, f"BENCHMARK call available: node={self.devices[phone]['node_prefix']}") +
                self.line(1, f"Encrypted private-call link to {self.devices[peer]['node_prefix']} over BLE GATT (2M)") +
                self.line(2, "BENCHMARK call phase ACTIVE"))

    HOST_START_NS = 100 * 10**9  # the host clock reading when the relay was launched (its log starts at 00:00:00.000)

    def timeline(self, end_at=8.0, launched=True):
        """The runner's record of a call whose planned End went out `end_at` seconds after the relay's launch."""
        events = {"runner_events": [{"device": "r", "action": "launch", "time": "10-03 00:00:00.000",
                                     "host_monotonic_ns": self.HOST_START_NS}]} if launched else {"runner_events": []}
        events["control_events"] = [] if end_at is None else [
            {"device": "a", "action": "end", "acknowledged": True, "time": "10-03 00:00:08.000",
             "host_monotonic_ns": self.HOST_START_NS + int(end_at * 10**9)}]
        return events

    def score(self, relay_text, **extra):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            for phone in "ab":
                (folder / f"{phone}.log").write_text(self.endpoint_log(phone), encoding="utf-8")
            logs = [{"device": phone, "path": phone + ".log", "pid": 123} for phone in "ab"]
            if relay_text is not None:
                (folder / "r.log").write_text(relay_text, encoding="utf-8")
                logs.append({"device": "r", "path": "r.log", "pid": 123, "role": "call_relay",
                             "start": "10-03 00:00:00.000"})
            manifest = {"scenario": self.case, "devices": self.devices, "logs": logs, **(self.timeline() | extra)}
            return runner.score_call_manifest(manifest, folder)

    def relay_log(self, callee="000000000002", caller="000000000001"):
        return (self.line(0.5, f"Private-call relay authenticated callee {callee}.") +
                self.line(0.6, "Private-call GATT relay is advertising for its caller.") +
                self.line(1, f"Relaying an endpoint-encrypted private call between {caller} and 000000000002; "
                             "this relay cannot decrypt call media.") +
                self.line(9, "Private-call caller path closed: Socket closed"))

    def test_proven_relay_hop_passes_route_and_keeps_forwarding_unmeasured(self):
        score = self.score(self.relay_log())
        self.assertEqual([item["device"] for item in score["devices"]], ["a", "b"])
        hop = score["relays"][0]
        self.assertEqual(score["requested_route"], ["a", "r", "b"])
        self.assertEqual({name: item["status"] for name, item in hop["gates"].items()},
                         {"relay_adjacent_child_identity": "PASS", "relay_adjacent_parent_identity": "PASS",
                          "relay_unplanned_closures": "PASS", "requested_relay_route": "PASS"})
        self.assertEqual({item["status"] for item in hop["forwarding"].values()}, {"UNMEASURED"})
        self.assertIn("forwarding", " ".join(score["limitations"]))

    def closure_gate(self, **timeline):
        hop = self.score(self.relay_log(), **self.timeline(**timeline))["relays"][0]
        return hop["gates"]["relay_unplanned_closures"]

    def test_a_relay_closure_after_the_planned_end_is_planned_and_one_before_it_fails(self):
        # relay_log() closes the caller path 9 s after the relay's launch.
        self.assertEqual("PASS", self.closure_gate(end_at=8.0)["status"])
        before = self.closure_gate(end_at=12.0)
        self.assertEqual(("FAIL", 1), (before["status"], before["value"]))
        self.assertEqual("FAIL", self.closure_gate(end_at=None)["status"])

    def test_a_closure_the_live_health_check_never_saw_still_fails(self):
        # health_events is empty: the relay's own log is the evidence, not whether the runner polled it in time.
        score = self.score(self.relay_log(), **self.timeline(end_at=None), health_events=[])
        hop = score["relays"][0]
        self.assertEqual("FAIL", hop["gates"]["relay_unplanned_closures"]["status"])
        self.assertEqual("FAIL", hop["log_status"])
        self.assertEqual("FAIL", score["overall_status"])

    def test_a_closure_that_cannot_be_placed_on_the_host_clock_is_unmeasured_not_a_pass(self):
        gate = self.closure_gate(launched=False)
        self.assertEqual("UNMEASURED", gate["status"])
        self.assertIn("host clock", gate["reason"])

    def test_a_relay_that_never_closed_passes_without_any_planned_end(self):
        quiet = "".join(item for item in self.relay_log().splitlines(keepends=True) if "closed" not in item)
        score = self.score(quiet, **self.timeline(end_at=None, launched=False))
        self.assertEqual("PASS", score["relays"][0]["gates"]["relay_unplanned_closures"]["status"])

    def test_a_planned_kill_without_a_clock_cannot_excuse_a_closure(self):
        events = self.timeline(end_at=None)
        events["control_events"] = [{"device": "a", "action": "kill", "acknowledged": True, "time": "10-03 00:00:08.000"}]
        gate = self.score(self.relay_log(), **events)["relays"][0]["gates"]["relay_unplanned_closures"]
        self.assertEqual("UNMEASURED", gate["status"])

    def test_active_call_without_the_relay_join_is_a_failed_requested_chain(self):
        for text in (None, self.line(0.5, "Private-call relay authenticated callee 000000000002.")):
            with self.subTest(relay_log=text is not None):
                score = self.score(text)
                self.assertEqual(score["relays"][0]["gates"]["requested_relay_route"]["status"], "FAIL")
                self.assertEqual(score["overall_status"], "FAIL")

    def test_a_relay_join_line_logd_dropped_is_unmeasured_not_a_failed_chain(self):
        relay = self.line(0.5, "Private-call relay authenticated callee 000000000002.")
        for drops, expected in ((self.line(0.8, "x").replace("BluetoothVideo: x", "liblog  : 9"), "UNMEASURED"),
                                ("", "FAIL")):
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as directory:
                folder = Path(directory)
                for phone in "ab":
                    (folder / f"{phone}.log").write_text(self.endpoint_log(phone), encoding="utf-8")
                (folder / "r.log").write_text(relay, encoding="utf-8")
                (folder / "r-liblog.log").write_text(drops, encoding="utf-8")
                logs = [*({"device": phone, "path": phone + ".log", "pid": 123} for phone in "ab"),
                        {"device": "r", "path": "r.log", "pid": 123, "role": "call_relay"}]
                capture = {"device": "r", "pid": 123, "path": "r-liblog.log",
                           "sha256": hashlib.sha256((folder / "r-liblog.log").read_bytes()).hexdigest()}
                score = runner.score_call_manifest({"scenario": self.case, "devices": self.devices, "logs": logs,
                                                    "log_drops": [capture]}, folder)
                route = score["relays"][0]["gates"]["requested_relay_route"]
                self.assertEqual(expected, route["status"])
                self.assertEqual(expected == "UNMEASURED", route.get("reason") == "log lines dropped by logd")

    def test_wrong_adjacent_identity_or_unplanned_relay_loss_fails(self):
        for text, extra, name in ((self.relay_log(callee="0000000000ff"), {}, "relay_adjacent_child_identity"),
                                  (self.relay_log(caller="0000000000ee"), {}, "relay_adjacent_parent_identity"),
                                  (self.relay_log(), {"health_events": [{"device": "r", "planned": False,
                                                                         "message": "relay stopped"}]},
                                   "relay_unplanned_closures")):
            with self.subTest(name=name):
                hop = self.score(text, **extra)["relays"][0]
                self.assertEqual(hop["gates"][name]["status"], "FAIL")
                self.assertEqual(hop["log_status"], "FAIL")

    def test_relay_health_check_fails_on_an_unplanned_relay_closure_only(self):
        instance = runner.CallRunner("adb", self.devices, self.case, Path("unused"))
        instance.pids["r"] = "7"
        instance.logs = [{"device": "r", "path": "r.log"}]
        process = Mock()
        process.poll.return_value = None
        instance.captures = [(process, None)]
        instance.adb = Mock(side_effect=lambda phone, *args, **kw: "device" if args[0] == "get-state" else "7")
        instance.beep = Mock()
        instance.log_text = Mock(return_value=self.line(1, "Private-call callee path closed: EOF"))
        with self.assertRaisesRegex(RuntimeError, "relay closed before the planned end"):
            instance.health_check("r")
        instance.observed_lines = {}
        instance.expected_terminal = True
        instance.health_check("r")


class RelayChainPrivateCallTest(unittest.TestCase):
    """caller a -> r1 -> r2 -> callee b: the schema, route proof and launch order for several relays."""

    def setUp(self):
        self.devices = {"a": {"serial": "a", "node_prefix": "000000000001"},
                        "b": {"serial": "b", "node_prefix": "000000000002"},
                        "r1": {"serial": "r1", "node_prefix": "000000000011"},
                        "r2": {"serial": "r2", "node_prefix": "000000000012"}}
        profile = {"codec": "encoder-a", "width": 352, "height": 288, "fps": 15,
                   "bitrate_kbps": 110, "playback": "balanced", "rate_control": "cbr", "thermal_reduction": False}
        self.case = {"id": "relay-chain", "caller": "a", "callee": "b", "relays": ["r1", "r2"], "transport": "ble",
                     "duration_s": 45, "profiles": {"a": profile, "b": {**profile, "codec": "encoder-b"}}}

    line = staticmethod(PrivateCallBenchmarkTest.line)

    def test_chain_schema_keeps_hop_order_and_the_shorthand(self):
        self.assertEqual(runner.validate_call_case(self.case, self.devices), {"a", "b", "r1", "r2"})
        self.assertEqual(runner.call_route(self.case), ["a", "r1", "r2", "b"])
        shorthand = {key: value for key, value in self.case.items() if key != "relays"}
        self.assertEqual(runner.call_route({**shorthand, "relay": "r1"}), ["a", "r1", "b"])
        with self.assertRaises(ValueError):
            runner.validate_call_case({**self.case, "relay": "r1"}, self.devices)

    def test_a_chain_the_app_cannot_build_is_blocked_before_any_phone(self):
        # The app builds up to PrivateCallRelayChain.MAX_RELAYS relays; the two-relay chain is runnable.
        self.assertEqual(runner.APP_MAX_CALL_RELAYS, 4)
        self.assertIsNone(runner.relay_chain_limit(self.case))
        self.assertIsNone(runner.relay_chain_limit({**self.case, "relays": ["r1"]}))
        devices = {**self.devices, **{f"r{index}": {"serial": f"r{index}", "node_prefix": f"0000000000{index:02d}"}
                                      for index in range(3, 6)}}
        too_long = {**self.case, "relays": ["r1", "r2", "r3", "r4", "r5"]}
        self.assertIn("PrivateCallRelayChain.MAX_RELAYS", runner.relay_chain_limit(too_long))
        # The control arm keeps the one-relay rule: a chain is BLOCKED under it, one relay is not.
        chain_off = {**self.case, "tunables": {"call_relay_chain": False}}
        self.assertIn("call_relay_chain=false", runner.relay_chain_limit(chain_off))
        self.assertIsNone(runner.relay_chain_limit({**chain_off, "relays": ["r1"]}))
        for case in (too_long, chain_off):
            with self.subTest(relays=case["relays"]), \
                 patch.object(runner.subprocess, "run", side_effect=AssertionError("device I/O")), \
                 self.assertRaisesRegex(ValueError, "BLOCKED by the app"):
                runner.CallRunner("adb", devices, case, Path("unused"))

    def test_each_relay_is_launched_for_its_requested_child_offer(self):
        self.assertEqual(runner.relay_child_offer(self.case, "r2"), ("b", 0))
        self.assertEqual(runner.relay_child_offer(self.case, "r1"), ("b", 1))
        argv = ["shell", "am", "start", "-W", "-n", runner.COMPONENT, "--es", "test_transport", "ble",
                "--ez", "test_auto_broadcast", "true"]
        result = runner.relay_launch_args(argv, "000000000002", 1)
        pairs = dict(zip(result[7::3], result[8::3]))
        self.assertEqual(pairs["test_relay_callee"], "000000000002")
        self.assertEqual(pairs["test_relay_route_cost"], "1")
        self.assertEqual(result[result.index("test_relay_route_cost") - 1], "--ei")
        self.assertNotIn("test_relay_callee", runner.relay_launch_args(argv))
        for prefix, cost in (("B", 0), ("000000000002", runner.APP_MAX_CALL_RELAYS), ("000000000002", True)):
            with self.subTest(prefix=prefix, cost=cost), self.assertRaises(ValueError):
                runner.relay_launch_args(argv, prefix, cost)
        instance = runner.CallRunner("adb", self.devices, self.case, Path("unused"))
        launched = []
        with patch.object(runner.Runner, "launch", lambda runner_self, phone, source=False: launched.append(
                runner_self.adb(phone, *argv))), patch.object(runner.Runner, "adb", side_effect=lambda phone, *args, **_: args):
            instance.logs = [{}]
            instance.launch_relay("r1")
            instance.launch_relay("r2")
        targets = [dict(zip(args[7::3], args[8::3])) for args in launched]
        self.assertEqual([(item["test_relay_callee"], item["test_relay_route_cost"]) for item in targets],
                         [("000000000002", "1"), ("000000000002", "0")])
        self.assertIsNone(instance.relay_target)

    def test_chain_is_built_from_the_callee_outwards_and_checks_each_child(self):
        instance = runner.CallRunner("adb", self.devices, self.case, Path("unused"))
        order, children = [], {"r2": "000000000002", "r1": "000000000012"}
        instance.launch = Mock(side_effect=order.append)
        instance.launch_relay = Mock(side_effect=order.append)
        instance.endpoint_ready = Mock()

        def wait(phone, expression, since=None, timeout=None):
            if expression == runner.RELAY_CALLEE:
                return {"time": "t", "message": f"Private-call relay authenticated callee {children[phone]}."}
            return {"time": "t", "message": "Private-call CoC relay is advertising for its caller."}
        instance.wait_for = Mock(side_effect=wait)
        instance.start_relayed_route("a", "b")
        self.assertEqual(order, ["b", "r2", "r1", "a"])
        children["r1"] = "000000000002"  # r1 reached the callee directly: a shortcut past r2
        order.clear()
        with self.assertRaisesRegex(RuntimeError, "r1: relay authenticated 000000000002, not its requested child r2"):
            instance.start_relayed_route("a", "b")
        self.assertEqual(order, ["b", "r2", "r1"])

    def join(self, parent, child):
        return self.line(1, f"Relaying an endpoint-encrypted private call between {parent} and {child}; "
                            "this relay cannot decrypt call media.")

    def score(self, relay_logs):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            logs = []
            for phone in "ab":
                peer = "b" if phone == "a" else "a"
                (folder / f"{phone}.log").write_text(
                    self.line(1, f"Encrypted private-call link to {self.devices[peer]['node_prefix']} over LE L2CAP") +
                    self.line(2, "BENCHMARK call phase ACTIVE"), encoding="utf-8")
                logs.append({"device": phone, "path": phone + ".log", "pid": 123})
            for phone, text in relay_logs.items():
                (folder / f"{phone}.log").write_text(text, encoding="utf-8")
                logs.append({"device": phone, "path": phone + ".log", "pid": 123, "role": "call_relay"})
            return runner.score_call_manifest({"scenario": self.case, "devices": self.devices, "logs": logs}, folder)

    def test_every_hop_must_join_its_requested_parent_and_child(self):
        child = lambda node: self.line(0.5, f"Private-call relay authenticated callee {node}.")
        good = {"r1": child("000000000012") + self.join("000000000001", "000000000012"),
                "r2": child("000000000002") + self.join("000000000011", "000000000002")}
        score = self.score(good)
        self.assertEqual(score["requested_route"], ["a", "r1", "r2", "b"])
        self.assertEqual([(hop["device"], hop["parent"], hop["child"], hop["log_status"]) for hop in score["relays"]],
                         [("r1", "a", "r2", "PASS"), ("r2", "r1", "b", "PASS")])
        shortcut = {"r2": child("000000000002") + self.join("000000000001", "000000000002")}  # caller skipped r1
        hops = self.score(shortcut)["relays"]
        self.assertEqual(hops[0]["gates"]["requested_relay_route"]["status"], "FAIL")
        self.assertEqual(hops[1]["gates"]["relay_adjacent_parent_identity"]["status"], "FAIL")

    def test_a_relayed_carrier_case_launches_the_relay_with_the_carrier(self):
        case = {**self.case, "carrier": "wifi_aware", "relays": ["c"]}
        devices = {**self.devices, "c": {"serial": "c", "node_prefix": "000000000003"}}
        runner.validate_call_case(case, devices)
        launch = runner.relay_launch_case(case, "c")
        self.assertEqual("wifi_aware", launch["carrier"])
        self.assertEqual(self.case["transport"], launch["transport"])
        self.assertNotIn("carrier", runner.relay_launch_case(self.case, "c"))

class RelayedCalleeSettleTest(unittest.TestCase):
    """A callee with relayed calls on rebuilt its idle endpoint at its first helper tick (rc-ctl-20261106): the relay is
    launched only after that tick, and a relay timeout caused by a later restart says so."""

    def setUp(self):
        RelayedPrivateCallTest.setUp(self)  # the a -> r -> b case and registry
        self.case["tunables"] = {"relayed_calls": True, "relayed_calls_consent": True}
        self.clock = [0.0]

    line = staticmethod(PrivateCallBenchmarkTest.line)
    AVAILABLE = "BENCHMARK call available: node=000000000002"
    RUNNING = "BENCHMARK relayed_helper: state=RUNNING | reason=NONE | scan=BALANCED | relaying=false"

    def instance(self, callee_log, relay_log=""):
        instance = runner.CallRunner("adb", self.devices, self.case, Path("unused"))
        instance.log_text = lambda phone: callee_log if phone == "b" else relay_log
        instance.check_cancelled = Mock()

        def wait(phone, expression, since=None, timeout=None):
            rows = [row for row in runner.records(instance.log_text(phone))
                    if (since is None or row["at"] > runner.timestamp(since)) and runner.re.search(expression, row["message"])]
            if not rows:
                raise RuntimeError(f"{phone}: no app evidence for {expression!r} within the deadline")
            return rows[0]
        instance.wait_for = Mock(side_effect=wait)
        instance.logs = [{"device": "b", "path": "b.log"}]
        return instance

    def settle(self, instance):
        def sleep(seconds):
            self.clock[0] += seconds
        with patch.object(runner.time, "monotonic", lambda: self.clock[0]), patch.object(runner.time, "sleep", sleep):
            return instance.settle_relayed_callee("b", instance.endpoint_ready("b"))

    def test_a_restart_at_the_first_tick_is_waited_out_before_the_relay(self):
        log = (self.line(0.1, self.AVAILABLE) + self.line(5.1, self.RUNNING) +
               self.line(5.13, "BENCHMARK incoming_handshake: result=failed | reason=EOFException") +
               self.line(5.14, "Stopped.") + self.line(5.75, self.AVAILABLE))
        instance = self.instance(log)
        ready = self.settle(instance)
        self.assertEqual(ready["time"], "10-03 00:00:05.750")
        self.assertEqual([event["action"] for event in instance.relay_events], ["callee_endpoint_restarted", "callee_settled"])
        self.assertEqual(instance.relay_events[-1]["restarts"], 1)

    def test_a_quiet_first_tick_launches_the_relay_without_the_full_wait(self):
        instance = self.instance(self.line(0.1, self.AVAILABLE) + self.line(5.1, self.RUNNING))
        ready = self.settle(instance)
        self.assertEqual(ready["time"], "10-03 00:00:00.100")
        self.assertEqual(instance.relay_events, [{"device": "b", "action": "callee_settled", "time": ready["time"], "restarts": 0}])
        self.assertLess(self.clock[0], runner.CALLEE_SETTLE_S)

    def test_no_tick_waits_the_settle_window_and_relayed_calls_off_does_not_wait(self):
        instance = self.instance(self.line(0.1, self.AVAILABLE))
        self.settle(instance)
        self.assertGreaterEqual(self.clock[0], runner.CALLEE_SETTLE_S)
        self.case["tunables"] = {"bounded_media_age": True}
        self.clock[0] = 0.0
        instance = self.instance(self.line(0.1, self.AVAILABLE) + self.line(5.14, "Stopped."))
        self.settle(instance)
        self.assertEqual(self.clock[0], 0.0)
        self.assertEqual(instance.relay_events, [])
        self.case["tunables"] = {"relayed_calls": True}
        self.case["callee_settle"] = False
        instance = self.instance(self.line(0.1, self.AVAILABLE) + self.line(5.14, "Stopped."))
        self.settle(instance)
        self.assertEqual((self.clock[0], instance.relay_events), (0.0, []))

    def test_an_endpoint_that_keeps_restarting_fails_the_setup_explicitly(self):
        log = self.line(0.1, self.AVAILABLE)
        for second in (5, 10, 15):
            log += self.line(second + 0.1, "Stopped.") + self.line(second + 0.7, self.AVAILABLE)
        with self.assertRaisesRegex(RuntimeError, "callee endpoint restarted 3 times"):
            self.settle(self.instance(log))

    def test_a_relay_timeout_after_a_callee_restart_names_the_restart(self):
        log = self.line(0.1, self.AVAILABLE) + self.line(5.1, self.RUNNING)
        instance = self.instance(log)
        instance.callee_settled = self.settle(instance)
        # The callee restarts later, while the relay holds its hop (a build without the restart guard).
        instance.log_text = lambda phone: log + self.line(12.0, "Stopped.") if phone == "b" else ""
        with self.assertRaisesRegex(RuntimeError, r"no app evidence .*; callee endpoint restarted at 10-03 00:00:12.000"):
            instance.relay_wait("r", runner.RELAY_JOINED, "b")
        self.assertEqual(instance.relay_events[-1]["action"], "callee_endpoint_restarted")
        # Without a restart the relay's own error is unchanged.
        instance = self.instance(self.line(0.1, self.AVAILABLE) + self.line(5.1, self.RUNNING))
        instance.callee_settled = self.settle(instance)
        with self.assertRaisesRegex(RuntimeError, r"within the deadline$"):
            instance.relay_wait("r", runner.RELAY_JOINED, "b")


class LoggerDropTest(unittest.TestCase):
    """logd drops app lines under a log burst (ASUS, Android 11, release-window-pres: the one-time
    `call phase ACTIVE` vanished at camera open while the call showed video). liblog reports the count
    in the events buffer on the process's next stored write. A missing one-time line inside a reported
    window is UNMEASURED, never FAIL and never PASS; without a report it stays a failure."""

    line = staticmethod(PrivateCallBenchmarkTest.line)

    def setUp(self):
        self.base = PrivateCallBenchmarkTest()
        self.base.setUp()
        self.case, self.devices = self.base.case, self.base.devices

    @staticmethod
    def drop(second, count, pid=123):
        return f"10-03 00:00:{second:06.3f} {pid} {pid + 17} I liblog  : {count}\n"

    def endpoint(self, phone, active=True):
        own, remote = (15, 30) if phone == "a" else (30, 15)
        return (self.base.log(phone, own, remote) + self.line(1.5, "BENCHMARK call phase NEGOTIATING_MEDIA") +
                (self.line(2.2, "BENCHMARK call phase ACTIVE") if active else "") +
                self.line(14, "BENCHMARK call phase ENDED"))

    def score(self, logs, drops, **extra):
        """logs/drops: phone -> app log text / liblog capture text (None = no capture recorded)."""
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            captures, tamper = [], extra.pop("tamper", ())
            for phone, text in logs.items():
                (folder / f"{phone}.log").write_text(text, encoding="utf-8")
                if drops.get(phone) is not None:
                    path = folder / f"{phone}-123-liblog.log"
                    path.write_text(drops[phone], encoding="utf-8")
                    captures.append({"device": phone, "pid": 123, "path": path.name, "status": "MEASURED",
                                     "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
            for item in captures:
                if item["device"] in tamper:
                    (folder / item["path"]).write_text(self.drop(3, 1), encoding="utf-8")
            manifest = {"scenario": self.case, "devices": self.devices, "log_drops": captures,
                        "logs": [{"device": phone, "path": phone + ".log", "pid": 123} for phone in logs],
                        "setup_anchors": [{"device": phone, "time": "10-03 00:00:00.500"} for phone in logs], **extra}
            return runner.score_call_manifest(manifest, folder)

    def active_gates(self, score):
        return {item["device"]: item["gates"].get("call_phase_active", {}).get("status") for item in score["devices"]}

    def test_callee_active_line_lost_in_a_reported_drop_is_unmeasured_not_failed(self):
        # The caller's log shows no call picture here, so nothing but the callee's own (dropped) line could prove
        # ACTIVE. With the peer's picture the gate is proven by the peer (test_call_validity, 2026-11-10).
        caller = "".join(text for text in self.endpoint("a").splitlines(True)
                         if "visible continuity" not in text and "first-surface" not in text)
        score = self.score({"a": caller, "b": self.endpoint("b", active=False)},
                           {"a": "", "b": self.drop(2.4, 12)})
        self.assertEqual({"a": "PASS", "b": "UNMEASURED"}, self.active_gates(score))
        callee = score["devices"][1]
        self.assertEqual("log lines dropped by logd", callee["gates"]["call_phase_active"]["reason"])
        self.assertNotEqual("FAIL", callee["log_status"])
        self.assertNotEqual("FAIL", score["log_status"])

    def test_active_is_counted_on_both_endpoints_and_a_missing_line_without_drops_fails(self):
        for missing in "ab":
            with self.subTest(missing=missing):
                score = self.score({phone: self.endpoint(phone, active=phone != missing) for phone in "ab"},
                                   {"a": "", "b": ""})
                self.assertEqual({phone: "FAIL" if phone == missing else "PASS" for phone in "ab"},
                                 self.active_gates(score))
                self.assertEqual("FAIL", score["log_status"])

    def test_only_a_report_between_the_last_phase_and_ended_can_hide_active(self):
        # Before NEGOTIATING_MEDIA (stored at 1.5 s) or after ENDED (stored at 14 s) a report covers other lines.
        for drops in (self.drop(1.2, 40), self.drop(14.5, 40), self.drop(2.4, 9, pid=99)):
            with self.subTest(drops=drops):
                score = self.score({"a": self.endpoint("a"), "b": self.endpoint("b", active=False)}, {"a": "", "b": drops})
                self.assertEqual("FAIL", self.active_gates(score)["b"])

    def test_a_present_active_line_passes_despite_drops_and_a_changed_capture_confirms_nothing(self):
        score = self.score({phone: self.endpoint(phone) for phone in "ab"}, {"a": self.drop(2.4, 5), "b": self.drop(2.4, 5)})
        self.assertEqual({"a": "PASS", "b": "PASS"}, self.active_gates(score))
        score = self.score({"a": self.endpoint("a"), "b": self.endpoint("b", active=False)},
                           {"a": "", "b": self.drop(2.4, 12)}, tamper={"b"})
        self.assertEqual("FAIL", self.active_gates(score)["b"])

    def test_runs_without_a_drop_capture_keep_their_previous_gates(self):
        score = self.score({"a": self.endpoint("a"), "b": self.endpoint("b", active=False)}, {})
        self.assertEqual({"a": None, "b": None}, self.active_gates(score))

    def test_a_runner_stop_on_a_dropped_one_time_line_is_unmeasured_only_when_the_capture_confirms_it(self):
        loss = {"device": "b", "pid": 123, "pattern": "BENCHMARK call phase INCOMING_RINGING",
                "after": "10-03 00:00:01.000", "reason": "log lines dropped by logd"}
        logs = {phone: self.endpoint(phone) for phone in "ab"}
        for drops, expected in ((self.drop(2.4, 7), "UNMEASURED"), ("", "FAIL"), (self.drop(0.8, 7), "FAIL")):
            with self.subTest(drops=drops):
                score = self.score(logs, {"a": "", "b": drops}, runner_error="b: no app evidence; log lines dropped by logd",
                                   runner_error_log_loss=loss)
                runner_gate = next(item for item in score["lifecycle"] if item["action"] == "runner")
                self.assertEqual(expected, runner_gate["gate"]["status"])
                self.assertEqual(expected == "FAIL", score["log_status"] == "FAIL")

    def test_voice_accept_evidence_names_the_logger_drop(self):
        event = {"device": "b", "action": "accept", "acknowledged": True, "surface_evidence": {"a": "10-03 00:00:02.200"},
                 "surface_log_loss": {"b": {"device": "b", "pid": 123, "reason": "log lines dropped by logd"}}}
        score = self.score({phone: self.endpoint(phone) for phone in "ab"}, {}, control_events=[event])
        evidence = score["lifecycle"][0]["evidence"]["both_visible_surfaces"]
        self.assertEqual(("UNMEASURED", "log lines dropped by logd"), (evidence["status"], evidence["reason"]))

    def test_every_drop_capture_is_stopped_and_hash_bound_even_when_the_run_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = runner.CallRunner("adb", self.devices, self.case, Path(directory) / "run")
            instance.inventory = Mock(return_value={"apk_sha256": "a" * 64})
            capture = Mock()

            def launch(phone, source=False):
                instance.drop_captures.append((capture, capture))
                instance.drop_logs.append({"device": phone, "pid": 7, "path": f"{phone}-7-liblog.log",
                                           "start": "10-03 00:00:00.000"})
                raise RuntimeError("launch failed")

            instance.launch = launch
            instance.phone_time = Mock(return_value="10-03 00:00:00.000")
            instance.adb = Mock(return_value=self.drop(0.4, 2, pid=7).rstrip())
            instance.beep = Mock()
            instance.graceful_stop = Mock()
            manifest = instance.run()
            capture.terminate.assert_called_once()
            capture.close.assert_called_once()
            entry = manifest["log_drops"][0]
            self.assertEqual(("MEASURED", 2), (entry["status"], entry["total_dropped"]))
            self.assertEqual(hashlib.sha256((instance.output / entry["path"]).read_bytes()).hexdigest(), entry["sha256"])

    def waiting_runner(self, directory, drops):
        instance = runner.CallRunner("adb", self.devices, self.case, Path(directory))
        instance.output.mkdir(parents=True, exist_ok=True)
        instance.logs = [{"device": "b", "pid": 7, "path": "b-7.log"}]
        instance.pids["b"] = "7"
        (instance.output / "b-7.log").write_text(self.line(1.5, "BENCHMARK call phase NEGOTIATING_MEDIA", pid=7),
                                                 encoding="utf-8")
        instance.drop_logs = [{"device": "b", "pid": 7, "start": "10-03 00:00:00.000", "path": "b-7-liblog.log"}]
        (instance.output / "b-7-liblog.log").write_text(drops, encoding="utf-8")
        return instance

    def test_waiting_for_a_dropped_active_line_reports_log_loss_and_a_plain_absence_still_fails(self):
        for drops, dropped in ((self.drop(2.4, 12, pid=7), True), ("", False), (self.drop(0.9, 12, pid=7), False)):
            with self.subTest(drops=drops), tempfile.TemporaryDirectory() as directory:
                instance = self.waiting_runner(directory, drops)
                with patch.object(instance, "adb", return_value=""), \
                     patch.object(runner.time, "monotonic", side_effect=[0, 0, 2]), patch.object(runner.time, "sleep"):
                    with self.assertRaisesRegex(RuntimeError, "b: no app evidence") as raised:
                        instance.wait_for("b", r"BENCHMARK call phase ACTIVE", timeout=1, drops_after="10-03 00:00:01.000")
                self.assertEqual(dropped, isinstance(raised.exception, runner.LogLinesDropped))
                self.assertEqual(dropped, "log lines dropped by logd" in str(raised.exception))
                self.assertEqual(dropped, any(event["action"] == "log_lines_dropped" for event in instance.events))

    def test_a_drop_report_only_the_events_buffer_holds_is_found_at_the_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.waiting_runner(directory, "")
            buffer = self.drop(2.4, 3, pid=7).rstrip()
            with patch.object(instance, "adb", side_effect=lambda phone, *args, **kwargs: buffer if "events" in args else ""), \
                 patch.object(runner.time, "monotonic", side_effect=[0, 0, 2]), patch.object(runner.time, "sleep"):
                with self.assertRaises(runner.LogLinesDropped) as raised:
                    instance.wait_for("b", r"BENCHMARK call phase ACTIVE", timeout=1)
            self.assertEqual([{"time": "10-03 00:00:02.400", "pid": 7, "dropped": 3}], raised.exception.record["reports"])


class SelectedProfileAnnotationTest(unittest.TestCase):
    # 2026-10-17: every relayed and direct call since fe2acce (2026-10-10) scored selected_profile_mismatches=2 because the
    # app logs "thermalReduction=false (setting=false)" and the whole string was compared with "false".
    def test_annotation_is_not_a_mismatch(self):
        requested = {"codec": "libaom-native", "width": 352, "height": 288, "fps": 15, "bitrate_kbps": 180,
                     "rate_control": "automatic", "playback": "balanced", "thermal_reduction": False}
        observed = {"encoder": "libaom-native", "width": "352", "height": "288", "fps": "15", "bitrateKbps": "180",
                    "playbackMs": "250", "rateControl": "AUTOMATIC", "thermalReduction": "false (setting=false)"}
        self.assertEqual(runner.requested_matches(requested, observed), 0)
        self.assertEqual(runner.requested_matches(requested, {**observed, "thermalReduction": "true (setting=true)"}), 1)



class OnDemandRelayedCallTest(unittest.TestCase):
    """relay_shape "on_demand": the middle phones are ordinary call-ready helpers, as in production (Design C), not the
    DEBUG call relay. The caller must pick the requested helper and each helper must dial the requested next hop."""

    def setUp(self):
        self.devices = {"a": {"serial": "a", "node_prefix": "000000000001"},
                        "b": {"serial": "b", "node_prefix": "000000000002"},
                        "r1": {"serial": "r1", "node_prefix": "000000000011"},
                        "r2": {"serial": "r2", "node_prefix": "000000000012"},
                        "r3": {"serial": "r3", "node_prefix": "000000000013"}}
        profile = {"codec": "encoder-a", "width": 352, "height": 288, "fps": 15,
                   "bitrate_kbps": 110, "playback": "balanced", "rate_control": "cbr", "thermal_reduction": False}
        self.case = {"id": "relay-on-demand", "caller": "a", "callee": "b", "relays": ["r1"], "transport": "gatt",
                     "relay_shape": "on_demand", "duration_s": 45,
                     "profiles": {"a": profile, "b": {**profile, "codec": "encoder-b"}},
                     "tunables": {"bounded_media_age": True}}

    line = staticmethod(PrivateCallBenchmarkTest.line)

    def chain(self, count):
        return {**self.case, "relays": ["r1", "r2", "r3"][:count], "transport": "ble"}

    def test_pins_name_each_requested_next_hop_and_never_the_callee_side(self):
        prefixes = {phone: device["node_prefix"] for phone, device in self.devices.items()}
        two = self.chain(2)
        self.assertEqual(runner.on_demand_pins(two, "a", prefixes), {"relayed_calls_helper": "000000000011"})
        self.assertEqual(runner.on_demand_pins(two, "r1", prefixes), {"relayed_calls_next_hop": "000000000012"})
        self.assertEqual(runner.on_demand_pins(two, "r2", prefixes), {"relayed_calls_next_hop": "000000000002"})
        self.assertEqual(runner.on_demand_pins(two, "b", prefixes), {})
        self.assertEqual(runner.on_demand_pins({**two, "relay_shape": "static"}, "a", prefixes), {})

    def test_a_pin_without_a_registered_prefix_is_refused(self):
        with self.assertRaises(ValueError):
            runner.on_demand_pins(self.case, "a", {"r1": None, "a": "000000000001", "b": "000000000002"})

    def test_pins_are_launch_extras(self):
        self.assertEqual(device_runner.TUNABLES["relayed_calls_helper"], ("test_relayed_calls_helper", str))
        self.assertEqual(device_runner.TUNABLES["relayed_calls_next_hop"], ("test_relayed_calls_next_hop", str))

    def test_the_default_shape_is_the_debug_relay_and_an_unknown_shape_is_refused(self):
        static = {key: value for key, value in self.case.items() if key != "relay_shape"}
        self.assertEqual(runner.relay_shape(static), "static")
        self.assertEqual({phone: runner.on_demand_tunables(static, phone) for phone in ("a", "b", "r1")},
                         {"a": {}, "b": {}, "r1": {}})
        self.assertNotIn("relayed_calls", runner.broadcast_shape(static, "a")["tunables"])
        with self.assertRaisesRegex(ValueError, "relay_shape must be one of"):
            runner.validate_call_case({**self.case, "relay_shape": "dynamic"}, self.devices)

    def test_each_phone_is_launched_with_the_tunables_that_force_the_requested_chain(self):
        one = self.chain(1)
        self.assertEqual(runner.on_demand_tunables(one, "a"), {"relayed_calls": True, "relayed_calls_require": 1})
        self.assertEqual(runner.on_demand_tunables(one, "r1"), {"relayed_calls": True})
        self.assertEqual(runner.on_demand_tunables(one, "b"), {"relayed_calls": True})
        self.assertEqual(runner.on_demand_tunables(one, "r2"), {})  # not on this route
        # Two relays: the caller and every relay but the last cannot hear the callee, or the table would take the shortest path.
        two = self.chain(2)
        self.assertEqual(runner.on_demand_tunables(two, "a"),
                         {"relayed_calls": True, "relayed_calls_require": 2, "relayed_calls_no_direct": True})
        self.assertEqual(runner.on_demand_tunables(two, "r1"), {"relayed_calls": True, "relayed_calls_no_direct": True})
        self.assertEqual(runner.on_demand_tunables(two, "r2"), {"relayed_calls": True})
        # Above the app's default cap of 2 relays every helper must accept the larger budget.
        three = self.chain(3)
        for phone in ("a", "r1", "r2", "r3", "b"):
            self.assertEqual(runner.on_demand_tunables(three, phone)["relayed_calls_max_relays"], 3)
        # The case's own tunables are kept and the endpoints' launch case carries the implied ones.
        self.assertEqual(runner.broadcast_shape(one, "a")["tunables"],
                         {"bounded_media_age": True, "relayed_calls": True, "relayed_calls_require": 1})

    def test_the_schema_refuses_what_the_app_cannot_route_or_the_harness_cannot_prove(self):
        self.assertEqual(runner.validate_call_case(self.case, self.devices), {"a", "b", "r1"})
        self.assertEqual(runner.validate_call_case(self.chain(2), self.devices), {"a", "b", "r1", "r2"})
        for name, changes in (("no relay", {"relays": None}), ("classic", {"transport": "classic"}),
                              ("rotating ids", {"tunables": {"rotating_ids": True}}),
                              ("phone rotating ids", {"phone_tunables": {"b": {"rotating_ids": True}}}),
                              ("relayed calls off", {"tunables": {"relayed_calls": False}}),
                              ("a different requirement", {"phone_tunables": {"a": {"relayed_calls_require": 2}}}),
                              ("a bad wait", {"route_wait_s": "soon"})):
            case = {key: value for key, value in {**self.case, **changes}.items() if value is not None}
            with self.subTest(name), self.assertRaises(ValueError):
                runner.validate_call_case(case, self.devices)
        # A matching explicit value, and a larger cap than the chain needs, are the case's own request and are kept.
        runner.validate_call_case({**self.chain(3), "tunables": {"relayed_calls": True, "relayed_calls_max_relays": 4}},
                                  self.devices)

    def launched(self, case, launcher, phone):
        """The `am start` extras one launch sends, with the device calls mocked."""
        with tempfile.TemporaryDirectory() as directory:
            instance = runner.CallRunner("adb", self.devices, case, Path(directory))
            instance.phone_time = Mock(return_value="10-03 17:00:00.000")
            instance.logs = []
            with patch.object(runner.Runner, "adb", return_value="123") as adb, \
                 patch.object(Path, "open", mock_open()), patch.object(runner.subprocess, "Popen"):
                getattr(instance, launcher)(phone)
            argv = next(call.args[1:] for call in adb.call_args_list if "start" in call.args)  # args[0] is the phone
            return dict(zip(argv[7::3], argv[8::3])), instance

    def test_the_middle_phone_launches_as_an_ordinary_helper_not_the_debug_relay(self):
        helper, instance = self.launched(self.case, "launch_helper", "r1")
        self.assertIn("test_private_call_browser", helper)
        self.assertEqual(helper["test_relayed_calls"], "true")
        self.assertEqual(helper["test_bounded_media_age"], "true")
        for debug_relay in ("test_private_call_relay", "test_relay_callee", "test_relay_route_cost", "test_relayed_calls_require"):
            self.assertNotIn(debug_relay, helper)
        self.assertNotEqual(helper.get("test_role"), "relay")
        self.assertEqual(instance.logs[-1]["role"], "call_relay")
        # The same phone as the DEBUG relay keeps its old launch (the default shape is unchanged).
        static = {key: value for key, value in self.case.items() if key != "relay_shape"}
        debug, _ = self.launched(static, "launch_relay", "r1")
        self.assertEqual((debug["test_role"], debug["test_private_call_relay"]), ("relay", "true"))
        self.assertNotIn("test_relayed_calls", debug)

    def test_an_on_demand_helper_takes_its_own_tunables_and_the_static_relay_refuses_them(self):
        # 2026-11-10: the relayed GATT stall needed one helper's device-policy off-switch as the test arm.
        arm = {"device_policy_off": "qcom-legacy-call-relay-gatt-acknowledged-writes"}
        case = {**self.case, "phone_tunables": {"r1": arm}}
        runner.validate_call_case(case, self.devices)
        helper, _ = self.launched(case, "launch_helper", "r1")
        self.assertEqual(helper["test_device_policy_off"], arm["device_policy_off"])
        caller, _ = self.launched(case, "launch", "a")
        self.assertNotIn("test_device_policy_off", caller)
        static = {key: value for key, value in case.items() if key != "relay_shape"}
        with self.assertRaisesRegex(ValueError, "call endpoints"):
            runner.validate_call_case(static, self.devices)
        # The `relay` shorthand names the same helper.
        short = {key: value for key, value in case.items() if key != "relays"}
        short["relay"] = "r1"
        runner.validate_call_case(short, self.devices)

    def test_the_caller_is_told_to_accept_only_the_requested_number_of_relays(self):
        caller, _ = self.launched(self.chain(2), "launch", "a")
        self.assertEqual((caller["test_relayed_calls"], caller["test_relayed_calls_require"],
                          caller["test_relayed_calls_no_direct"]), ("true", "2", "true"))
        callee, _ = self.launched(self.chain(2), "launch", "b")
        self.assertEqual(callee["test_relayed_calls"], "true")
        self.assertNotIn("test_relayed_calls_require", callee)
        self.assertNotIn("test_relayed_calls_no_direct", callee)
        self.assertNotIn("test_private_call_relay", caller)

    def test_the_route_is_built_callee_then_helpers_then_caller_without_the_debug_relay(self):
        instance = runner.CallRunner("adb", self.devices, self.chain(2), Path("unused"))
        order = []
        instance.logs = [{}]
        instance.launch = Mock(side_effect=lambda phone: order.append(("endpoint", phone)))
        instance.launch_helper = Mock(side_effect=lambda phone: order.append(("helper", phone)))
        instance.launch_relay = Mock(side_effect=AssertionError("the DEBUG relay is the static shape"))
        instance.endpoint_ready = Mock(return_value={"time": "t", "message": "BENCHMARK call available: node=x"})
        instance.settle_relayed_callee = Mock()
        instance.wait_on_demand_route_hint = Mock()
        instance.start_relayed_route("a", "b")
        self.assertEqual(order, [("endpoint", "b"), ("helper", "r2"), ("helper", "r1"), ("endpoint", "a")])
        self.assertEqual([item["device"] for item in instance.relay_events if item["action"] == "helper_ready"], ["r2", "r1"])
        instance.wait_on_demand_route_hint.assert_called_once_with("a")

    def test_each_helper_settles_before_the_next_phone_although_it_has_no_camera_profile(self):
        # Lane 2, 2026-11-10: every on-demand case stopped with KeyError('asus') before the caller launched, because the
        # settle step looked up a camera profile for the helper (a case carries profiles for the two endpoints only).
        instance = runner.CallRunner("adb", self.devices, self.chain(2), Path("unused"))
        order = []
        instance.logs = [{}]
        instance.launch = Mock(side_effect=order.append)
        instance.launch_helper = Mock(side_effect=order.append)
        instance.endpoint_ready = Mock(return_value={"time": "10-03 17:00:00.000",
                                                     "message": "BENCHMARK call available: node=x"})
        instance.wait_on_demand_route_hint = Mock()
        instance.log_text = lambda phone: ""
        instance.check_cancelled = Mock()
        clock = [0.0]

        def sleep(seconds):
            clock[0] += seconds
        with patch.object(runner.time, "monotonic", lambda: clock[0]), patch.object(runner.time, "sleep", sleep):
            instance.start_relayed_route("a", "b")
        self.assertEqual(order, ["b", "r2", "r1", "a"])
        self.assertEqual([item["device"] for item in instance.relay_events if item["action"] == "callee_settled"],
                         ["b", "r2", "r1"])

    def test_the_route_wait_is_a_recorded_hint_and_never_blocks_the_dial(self):
        instance = runner.CallRunner("adb", self.devices, self.chain(2), Path("unused"))
        table = ("BENCHMARK relayed_reach_table: entries=3 | rotating_entries=0 | pages=4 | offers=9 | "
                 "costs=c0=1,c2=1 | rotating_costs=none")
        instance.wait_for = Mock(return_value={"time": "t", "message": table})
        instance.wait_on_demand_route_hint("a")
        self.assertRegex(table, instance.wait_for.call_args.args[1])
        self.assertNotRegex(table.replace("c2=1", "c1=1"), instance.wait_for.call_args.args[1])  # another relay count
        self.assertTrue(instance.relay_events[-1]["found"])
        instance.wait_for = Mock(side_effect=RuntimeError("a: no app evidence for 'x' within the deadline"))
        instance.wait_on_demand_route_hint("a")
        self.assertFalse(instance.relay_events[-1]["found"])
        instance.wait_for = Mock(side_effect=RuntimeError("a: call application crashed"))
        with self.assertRaisesRegex(RuntimeError, "crashed"):
            instance.wait_on_demand_route_hint("a")

    def test_a_caller_that_selects_another_helper_or_relay_count_stops_the_run(self):
        instance = runner.CallRunner("adb", self.devices, self.chain(2), Path("unused"))
        route = lambda helper, budget: {"time": "t", "message": (
            f"BENCHMARK relayed_route: role=caller | target=000000000002 | helper={helper} | id=node | budget={budget}")}
        instance.wait_for = Mock(return_value=route("000000000011", 2))
        instance.confirm_on_demand_route("a", "b")
        self.assertEqual(instance.relay_events[-1]["helper"], "000000000011")
        for helper, budget in (("000000000013", 2), ("000000000011", 1)):
            instance.wait_for = Mock(return_value=route(helper, budget))
            with self.subTest(helper=helper, budget=budget), self.assertRaisesRegex(RuntimeError, "not the requested r1"):
                instance.confirm_on_demand_route("a", "b")

    def hop_logs(self, count, caller_extra=""):
        """Endpoint and helper logs of a call that really went a -> r1 [-> r2] -> b through production's helper path."""
        nodes = ["000000000001", "000000000011", "000000000012", "000000000002"]
        if count == 1:
            nodes = ["000000000001", "000000000011", "000000000002"]
        logs = {"a": (self.line(0.5, f"BENCHMARK relayed_route: role=caller | target=000000000002 | helper={nodes[1]} | "
                                     f"id=node | budget={count}") + caller_extra +
                      self.line(1, f"BENCHMARK relayed_setup: role=caller | result=ready | budget={count} | relays={count}"))}
        for index in range(count):
            nxt = nodes[index + 2]
            kind = "target" if index == count - 1 else "helper"
            logs[f"r{index + 1}"] = (
                self.line(0.7, f"BENCHMARK relayed_setup: role=helper | result=accepted | budget={count - index} | "
                               f"next_budget={count - index - 1} | next={kind}:{nxt}") +
                self.line(1, f"Relaying an endpoint-encrypted private call between {nodes[index]} and {nxt}; "
                             "this relay cannot decrypt call media.") +
                self.line(9, "Private-call caller path closed: Socket closed"))
        return logs

    def score(self, case, logs):
        with tempfile.TemporaryDirectory() as directory:
            folder, entries = Path(directory), []
            for phone in "ab":
                peer = "b" if phone == "a" else "a"
                (folder / f"{phone}.log").write_text(
                    logs.get(phone, "") + self.line(1, f"Encrypted private-call link to {self.devices[peer]['node_prefix']} "
                                                       "over BLE GATT (2M)") + self.line(2, "BENCHMARK call phase ACTIVE"),
                    encoding="utf-8")
                entries.append({"device": phone, "path": phone + ".log", "pid": 123})
            for phone, text in logs.items():
                if phone not in "ab":
                    (folder / f"{phone}.log").write_text(text, encoding="utf-8")
                    entries.append({"device": phone, "path": phone + ".log", "pid": 123, "role": "call_relay"})
            return runner.score_call_manifest({"scenario": case, "devices": self.devices, "logs": entries}, folder)

    def test_a_call_through_the_requested_helpers_passes_without_a_debug_relay_authentication_line(self):
        for count in (1, 2):
            with self.subTest(relays=count):
                score = self.score(self.chain(count), self.hop_logs(count))
                self.assertEqual(score["requested_route"], ["a", *["r1", "r2"][:count], "b"])
                for hop in score["relays"]:
                    self.assertEqual(hop["gates"]["requested_relay_route"]["status"], "PASS", hop["gates"])
                    self.assertEqual(hop["gates"]["helper_accepted_requested_next_hop"]["status"], "PASS")
                    self.assertEqual(hop["gates"]["relay_adjacent_child_identity"]["status"], "PASS")
                    self.assertEqual(hop["gates"]["relay_adjacent_parent_identity"]["status"], "PASS")
                first = score["relays"][0]
                self.assertEqual(first["gates"]["caller_selected_requested_helper"]["status"], "PASS")
                self.assertEqual(first["gates"]["caller_ready_relay_count"]["status"], "PASS")
                self.assertEqual(first["authenticated_child_links"], ["000000000012" if count == 2 else "000000000002"])

    def test_a_direct_fallback_or_another_helper_is_a_failed_requested_chain(self):
        direct = self.hop_logs(1)
        direct["a"] = ""  # a direct dial logs no relayed route and no relayed SETUP
        direct.pop("r1")  # no helper carried it
        other_helper = self.hop_logs(1)
        other_helper["a"] = other_helper["a"].replace("helper=000000000011", "helper=000000000013")
        moved = self.hop_logs(1, caller_extra=self.line(
            0.9, "BENCHMARK relayed_route: role=caller | attempt=2 | helper=000000000013 | target=000000000002 | "
                 "budget=1 | afterMs=5000 | previousError=IOException"))
        shorter = self.hop_logs(1)
        shorter["a"] = shorter["a"].replace("relays=1", "relays=0")
        for name, logs, gate_name in (("direct", direct, "requested_relay_route"),
                                      ("other helper", other_helper, "caller_selected_requested_helper"),
                                      ("candidate moved", moved, "caller_selected_requested_helper"),
                                      ("fewer relays than asked", shorter, "caller_ready_relay_count")):
            with self.subTest(name):
                hop = self.score(self.chain(1), logs)["relays"][0]
                self.assertEqual(hop["gates"]["requested_relay_route"]["status"], "FAIL")
                self.assertEqual(hop["log_status"], "FAIL")
                if gate_name in hop["gates"]:
                    self.assertIn(hop["gates"][gate_name]["status"], ("FAIL", "UNMEASURED"))
        self.assertEqual(self.score(self.chain(1), other_helper)["relays"][0]["gates"]
                         ["caller_selected_requested_helper"]["status"], "FAIL")

    def test_a_helper_that_dials_another_next_hop_fails_even_if_it_joined_the_requested_pair(self):
        logs = self.hop_logs(2)
        logs["r1"] = logs["r1"].replace("next=helper:000000000012", "next=helper:000000000013")
        hops = self.score(self.chain(2), logs)["relays"]
        self.assertEqual(hops[0]["gates"]["helper_accepted_requested_next_hop"]["status"], "FAIL")
        self.assertEqual(hops[0]["gates"]["requested_relay_route"]["status"], "FAIL")
        self.assertEqual(hops[1]["gates"]["requested_relay_route"]["status"], "PASS")
        budget = self.hop_logs(2)
        budget["r2"] = budget["r2"].replace("budget=1 | next_budget=0", "budget=2 | next_budget=1")
        hop = self.score(self.chain(2), budget)["relays"][1]
        self.assertEqual(hop["gates"]["helper_accepted_requested_next_hop"]["status"], "FAIL")

    def test_a_missing_helper_log_line_is_unmeasured_not_a_pass(self):
        logs = self.hop_logs(1)
        logs["r1"] = "".join(row for row in logs["r1"].splitlines(True) if "result=accepted" not in row)
        hop = self.score(self.chain(1), logs)["relays"][0]
        self.assertEqual(hop["gates"]["helper_accepted_requested_next_hop"]["status"], "UNMEASURED")
        self.assertEqual(hop["gates"]["requested_relay_route"]["status"], "UNMEASURED")
        self.assertNotEqual(hop["log_status"], "PASS")

    def test_bmh3_hides_the_callers_identity_from_the_first_helper_only(self):
        case = {**self.chain(2), "tunables": {"bmh3": True}}
        logs = self.hop_logs(2)
        logs["r1"] = logs["r1"].replace("between 000000000001 and", "between 0000000000ee and")  # a throwaway
        hops = self.score(case, logs)["relays"]
        self.assertEqual(hops[0]["gates"]["relay_adjacent_parent_identity"]["status"], "UNMEASURED")
        self.assertEqual(hops[0]["gates"]["requested_relay_route"]["status"], "PASS")
        self.assertEqual(hops[1]["gates"]["relay_adjacent_parent_identity"]["status"], "PASS")
        logs["r2"] = logs["r2"].replace("between 000000000011 and", "between 0000000000ee and")
        self.assertEqual(self.score(case, logs)["relays"][1]["gates"]["relay_adjacent_parent_identity"]["status"], "FAIL")


if __name__ == "__main__":
    unittest.main()
