"""Does the harness tell a good private call from a bad one? (audit 2026-11-10)

Every test feeds the scorer or the runner small synthetic device logs and reads the state, events or adb
commands that come out.
"""
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).parents[1]))
sys.path.insert(0, str(Path(__file__).parent))
import run_private_call_benchmark as runner
import score_device_run as device_scorer
from call_phone_fakes import fake_call

CALL_ID = "00000000-0000-0000-0000-000000000001"


def line(second, message, pid=123, tag="BluetoothVideo"):
    minute, second = divmod(second, 60)
    return f"10-03 00:{int(minute):02d}:{second:06.3f} {pid} 123 I {tag}: {message}\n"


class CallFixture:
    """One 15 fps Classic call between phones a (caller) and b (callee), as the app logs it."""

    devices = {"a": {"serial": "a", "node_prefix": "000000000001"},
               "b": {"serial": "b", "node_prefix": "000000000002"}}
    profile = {"codec": "encoder-a", "width": 640, "height": 360, "fps": 15, "bitrate_kbps": 150,
               "playback": "balanced", "rate_control": "cbr", "thermal_reduction": False}

    def case(self):
        return {"id": "test-call", "caller": "a", "callee": "b", "transport": "classic", "duration_s": 60,
                "profiles": {"a": dict(self.profile), "b": dict(self.profile)}}

    def continuity(self, second, frames, freezes=0, frozen_ms=0.0, cadence=(66.7, 66.7, 66.7, 66.7), final=False):
        return line(second, f"Private call visible continuity{' FINAL' if final else ''}: {frames} frames | "
                            f"{freezes} freeze event(s), {frozen_ms:.1f} ms, 0.00% | threshold 250.0 ms | surface cadence "
                            f"p50/p95/p99/max {'/'.join(f'{value:.1f}' for value in cadence)} ms")

    def endpoint_log(self, phone, active=True, final_tx=True, camera_counter=True, cadence=(66.7, 66.7, 66.7, 66.7),
                     refresh_hz=None):
        """A healthy call: every line an automated gate reads is present and inside its limit."""
        peer = "b" if phone == "a" else "a"
        text = line(0.05, f"BENCHMARK display: refreshHz={refresh_hz}") if refresh_hz else ""
        text += line(0.1, f"BENCHMARK call available: node={self.devices[phone]['node_prefix']}")
        text += line(0.2, f"BENCHMARK call discovered: node={self.devices[peer]['node_prefix']} | transport=CLASSIC_RFCOMM")
        text += line(0.3, "BENCHMARK call selected: encoder=encoder-a | width=640 | height=360 | fps=15 | "
                          "bitrateKbps=150 | playbackMs=250 | rateControl=CBR | thermalReduction=false")
        text += line(1, f"Encrypted private-call link to {self.devices[peer]['node_prefix']} over Classic RFCOMM (MPS 4096)")
        text += line(1.5, "BENCHMARK call phase NEGOTIATING_MEDIA")
        text += line(2, f"BENCHMARK call identity: callId={CALL_ID}")
        text += line(2, "BENCHMARK call effective: encoder=encoder-a | codec=2 | width=640 | height=360 | fps=15 | "
                        "bitrateKbps=150 | audioKbps=32 | playbackMs=250 | rateControl=CBR")
        text += line(2.1, "Call camera: Streaming VP9 at 640x360, 15 FPS, 150 kbps, CBR mode 2")
        if active:
            text += line(2.2, "BENCHMARK call phase ACTIVE")
        text += line(2.5, "BENCHMARK call remote: codec=2 | width=640 | height=360 | fps=15 | rotation=0")
        text += line(3, "Private call startup: media-to-first-surface 1000.0 ms")
        text += self.continuity(3, 1) + self.continuity(8, 76, cadence=cadence) + self.continuity(13, 151, cadence=cadence)
        text += line(7, "Encoder output: 15.0 FPS, 150 kbps")
        if camera_counter:
            text += line(7.1, "Encoder input: capture drops 0")
        text += line(8, "Call RX 150 kbps | 80 video/100 audio | audio drops 0 | 80/80 shown, queue 1/11, drops 0")
        # Without its final report a zero in the last periodic report cannot prove that nothing was lost later.
        text += line(14 if final_tx else 10, f"BENCHMARK call tx: callId={CALL_ID} | final={str(final_tx).lower()} | "
                     "queue=0/48 | videoInputs=180 | audioInputs=200 | videoWrites=180 | audioWrites=100 | queueDrops=0")
        text += line(14, f"BENCHMARK call rx: callId={CALL_ID} | final=true | audioDrops=0 | decoderDrops=0 | "
                         "videoPackets=180 | audioPackets=100")
        return text

    def lifecycle(self):
        """The runner's own record of a complete invite, accept and planned End."""
        proof = {phone: {"ended": "10-03 00:00:15.000", "available": "10-03 00:00:15.100"} for phone in "ab"}
        return [{"device": "a", "action": "call:000000000002", "acknowledged": True,
                 "remote_ack": {"device": "b"}, "outgoing_phase": {"time": "10-03 00:00:00.600"}},
                {"device": "b", "action": "accept", "acknowledged": True,
                 "surface_evidence": {"a": "10-03 00:00:03.000", "b": "10-03 00:00:03.000"}},
                {"device": "a", "action": "end", "acknowledged": True, "terminal_evidence": proof}]

    def score(self, logs, drops=None, case=None, **extra):
        """logs: phone -> app log text. drops: phone -> liblog capture text (absent = no capture recorded)."""
        with tempfile.TemporaryDirectory() as directory:
            folder, captures = Path(directory), []
            for phone, text in logs.items():
                (folder / f"{phone}.log").write_text(text, encoding="utf-8")
            for phone, text in (drops or {}).items():
                path = folder / f"{phone}-123-liblog.log"
                path.write_text(text, encoding="utf-8")
                captures.append({"device": phone, "pid": 123, "path": path.name, "status": "MEASURED",
                                 "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
            manifest = {"scenario": case or self.case(), "devices": self.devices, "log_drops": captures,
                        "logs": [{"device": phone, "path": phone + ".log", "pid": 123} for phone in logs],
                        "setup_anchors": [{"device": phone, "time": "10-03 00:00:00.500"} for phone in logs],
                        "control_events": self.lifecycle(), **extra}
            score = runner.score_call_manifest(manifest, folder)
            json.dumps(score)  # a score is always written to score.json
            return score


class CallStateFollowsItsGatesTest(CallFixture, unittest.TestCase):
    """A call scores PASS when every automated gate was measured and passed, FAIL when one failed, and
    UNMEASURED when none failed but one could not be measured. Missing evidence never reads as PASS."""

    def test_a_call_whose_every_gate_was_measured_and_passed_scores_pass(self):
        score = self.score({phone: self.endpoint_log(phone) for phone in "ab"})
        self.assertEqual([], score["failed_gates"])
        self.assertEqual([], score["unmeasured_gates"])
        self.assertEqual("PASS", score["overall_status"])

    def test_one_failed_gate_makes_the_call_fail_and_names_the_gate(self):
        late = self.endpoint_log("b").replace("10-03 00:00:03.000 123 123 I BluetoothVideo: Private call startup",
                                              "10-03 00:00:09.000 123 123 I BluetoothVideo: Private call startup")
        score = self.score({"a": self.endpoint_log("a"), "b": late})
        self.assertEqual("FAIL", score["overall_status"])
        self.assertEqual([("b", "setup_to_first_surface_ms")],
                         [(item["device"], item["gate"]) for item in score["failed_gates"]])

    def test_missing_evidence_is_unmeasured_never_pass_and_the_missing_gate_stays_listed(self):
        score = self.score({"a": self.endpoint_log("a", final_tx=False), "b": self.endpoint_log("b")})
        self.assertEqual("UNMEASURED", score["overall_status"])
        self.assertEqual([], score["failed_gates"])
        self.assertEqual([("a", "outgoing", "sender_drops")],
                         [(item["device"], item["section"], item["gate"]) for item in score["unmeasured_gates"]])
        self.assertTrue(score["unmeasured_gates"][0]["reason"])

    def test_a_failure_outranks_missing_evidence(self):
        never_active = self.endpoint_log("b", active=False, final_tx=False)
        score = self.score({"a": self.endpoint_log("a"), "b": never_active}, drops={"a": "", "b": ""})
        self.assertEqual("FAIL", score["overall_status"])
        self.assertIn(("b", "call_phase_active"), [(item["device"], item["gate"]) for item in score["failed_gates"]])
        self.assertIn(("b", "sender_drops"), [(item["device"], item["gate"]) for item in score["unmeasured_gates"]])

    def test_a_pass_says_which_measurement_families_it_does_not_cover(self):
        # The automated gates read app logs only: what the camera saw, what the speaker played and the delay a
        # person would see are separate measurements, and a passing log score must not read as a pass for them.
        score = self.score({phone: self.endpoint_log(phone) for phone in "ab"})
        self.assertEqual("PASS", score["overall_status"])
        self.assertEqual("UNMEASURED", score["quality_status"])
        self.assertEqual("UNMEASURED", score["full_experience_status"])
        self.assertIn("picture", score["unmeasured_families"])

    def test_a_run_that_stopped_before_any_call_evidence_is_still_a_failure(self):
        score = self.score({}, runner_error="b: application did not start", control_events=[])
        self.assertEqual("FAIL", score["overall_status"])
        self.assertEqual([("runner", "b: application did not start")],
                         [(item["gate"], item["reason"]) for item in score["failed_gates"]])


class FrameIntervalLimitsAndTheDisplayGridTest(CallFixture, unittest.TestCase):
    """A 15 fps picture on a 60 Hz screen is shown 4 or 5 refreshes apart, so a healthy call's p95 interval is
    83.33 ms plus timer noise: exactly the 1.25-period limit. The limits keep their meaning (1.25 and 2 source
    periods); the comparison allows half a refresh of the receiving screen, the size of that grid's rounding."""

    def incoming(self, **log):
        score = self.score({"a": self.endpoint_log("a"), "b": self.endpoint_log("b", **log)})
        return score, next(item for item in score["devices"] if item["device"] == "b")["incoming"]["gates"]

    def test_a_healthy_cadence_on_the_refresh_grid_passes(self):
        # q26-20261109e and q24x-20261109e: 83.4-85.2 ms p95 and 133.4-135.1 ms p99 on calls with zero freezes.
        for cadence in ((66.7, 83.4, 133.4, 150.0), (66.7, 85.2, 135.1, 150.0)):
            with self.subTest(cadence=cadence):
                score, gates = self.incoming(cadence=cadence)
                self.assertEqual(("PASS", "PASS"), (gates["surface_p95_ms"]["status"], gates["surface_p99_ms"]["status"]))
                self.assertEqual("PASS", score["overall_status"])

    def test_a_frame_shown_a_whole_refresh_late_at_p95_still_fails(self):
        score, gates = self.incoming(cadence=(66.7, 100.0, 133.4, 150.0))
        self.assertEqual("FAIL", gates["surface_p95_ms"]["status"])
        self.assertEqual("FAIL", score["overall_status"])
        _, gates = self.incoming(cadence=(66.7, 83.4, 150.0, 150.0))
        self.assertEqual("FAIL", gates["surface_p99_ms"]["status"])

    def test_the_limit_keeps_its_meaning_and_the_score_records_the_tolerance_used(self):
        _, gates = self.incoming(cadence=(66.7, 83.4, 133.4, 150.0))
        p95, p99 = gates["surface_p95_ms"], gates["surface_p99_ms"]
        self.assertAlmostEqual(1000 / 15 * 1.25, p95["limit"])
        self.assertAlmostEqual(1000 / 15 * 2.0, p99["limit"])
        self.assertAlmostEqual(1000 / 60 / 2, p95["tolerance"])
        self.assertEqual((60.0, "default"), (p95["display_refresh_hz"], p95["display_refresh_source"]))
        self.assertEqual(83.4, p95["value"])

    def test_the_receiving_phones_logged_refresh_rate_sets_the_tolerance(self):
        # On a 120 Hz screen the grid is 8.33 ms, so only 4.17 ms is rounding: 90 ms at p95 is a late frame there.
        _, at_60 = self.incoming(cadence=(66.7, 90.0, 133.4, 150.0))
        _, at_120 = self.incoming(cadence=(66.7, 90.0, 133.4, 150.0), refresh_hz=120)
        self.assertEqual(("PASS", "FAIL"), (at_60["surface_p95_ms"]["status"], at_120["surface_p95_ms"]["status"]))
        self.assertEqual((120.0, "app_log"), (at_120["surface_p95_ms"]["display_refresh_hz"],
                                               at_120["surface_p95_ms"]["display_refresh_source"]))
        self.assertAlmostEqual(1000 / 120 / 2, at_120["surface_p95_ms"]["tolerance"])

    def test_a_stream_receiver_gets_the_same_comparison(self):
        text = (line(1, "Receiver startup: selection-to-first-surface 900.0 ms") +
                "".join(line(second, f"Receiver visible continuity: {frames} frames | 0 freeze event(s), 0.0 ms, 0.00% | "
                                     "threshold 250.0 ms | surface cadence p50/p95/p99/max 66.7/83.5/133.5/150.0 ms")
                        for second, frames in ((1, 1), (6, 76), (11, 151))))
        gates = device_scorer.score_log(text, {"device": "b", "pid": 123, "role": "receiver", "expected_fps": 15})["gates"]
        self.assertEqual(("PASS", "PASS"), (gates["surface_p95_ms"]["status"], gates["surface_p99_ms"]["status"]))
        strict = device_scorer.score_log(text, {"device": "b", "pid": 123, "role": "receiver", "expected_fps": 15},
                                         thresholds={"surface_refresh_tolerance_periods": 0})["gates"]
        self.assertEqual("FAIL", strict["surface_p95_ms"]["status"])  # the earlier rule stays reproducible


class MissingLogLinesAreNotAFailedCallTest(CallFixture, unittest.TestCase):
    """logd drops app lines on some phones and reports how many (liblog, events buffer). A gate that needs a line
    from a phone whose lines were dropped where that line was due is UNMEASURED 'log lines dropped by logd', unless
    the peer's log proves the event. A case that was never dialed says so, and fails no call gate."""

    @staticmethod
    def drop(second, count, pid=123):
        return line(second, str(count), pid=pid, tag="liblog  ")

    def launched_only(self, phone, discovered=True):
        peer = "b" if phone == "a" else "a"
        text = line(0.1, f"BENCHMARK call available: node={self.devices[phone]['node_prefix']}")
        if discovered:
            text += line(0.2, f"BENCHMARK call discovered: node={self.devices[peer]['node_prefix']} | transport=CLASSIC_RFCOMM")
        return text

    def test_a_case_that_was_never_dialed_because_a_line_was_dropped_says_so_and_is_unmeasured(self):
        # q18-20261107/0020: the callee's `call discovered` line was among 130 dropped lines; the call was never
        # dialed, and the other phone's missing ACTIVE line then scored the case FAIL.
        error = ("b: no app evidence for 'BENCHMARK call discovered: node=000000000001' within the deadline; log lines "
                 "dropped by logd: 9 line(s) in 1 liblog report(s) after 10-03 00:00:00.050, so the missing line is UNMEASURED")
        loss = {"device": "b", "pid": 123, "pattern": "BENCHMARK call discovered: node=000000000001",
                "after": "10-03 00:00:00.050", "reason": "log lines dropped by logd"}
        score = self.score({"a": self.launched_only("a"), "b": self.launched_only("b", discovered=False)},
                           drops={"a": "", "b": self.drop(0.4, 9)}, control_events=[], setup_anchors=[],
                           runner_error=error, runner_error_log_loss=loss)
        self.assertIs(False, score["call_dialed"])
        self.assertIn("log lines dropped by logd", score["not_dialed"]["reason"])
        self.assertEqual("b", score["not_dialed"]["device"])
        self.assertEqual([], score["failed_gates"])
        self.assertEqual("UNMEASURED", score["overall_status"])
        self.assertEqual({"The call was not dialed"},
                         {item["reason"] for item in score["unmeasured_gates"] if item["gate"] == "call_phase_active"})

    def test_a_case_never_dialed_because_a_phone_never_became_available_fails_and_says_it_was_not_dialed(self):
        error = "b: no app evidence for 'BENCHMARK call available: node=([0-9a-f]{12})' within the deadline"
        score = self.score({"a": self.launched_only("a", discovered=False), "b": ""}, drops={"a": "", "b": ""},
                           control_events=[], setup_anchors=[], runner_error=error)
        self.assertIs(False, score["call_dialed"])
        self.assertEqual(error, score["not_dialed"]["reason"])
        self.assertEqual("FAIL", score["overall_status"])
        self.assertEqual(["runner"], [item["gate"] for item in score["failed_gates"]])

    def test_a_dialed_call_is_marked_dialed(self):
        score = self.score({phone: self.endpoint_log(phone) for phone in "ab"})
        self.assertIs(True, score["call_dialed"])
        self.assertNotIn("not_dialed", score)

    def test_a_dropped_active_line_is_proven_by_the_peer_showing_this_phones_video(self):
        logs = {"a": self.endpoint_log("a"), "b": self.endpoint_log("b", active=False)}
        gate = self.score(logs, drops={"a": "", "b": self.drop(2.4, 12)})["devices"][1]["gates"]["call_phase_active"]
        self.assertEqual(("PASS", "peer_rendered_frames"), (gate["status"], gate["source"]))
        self.assertEqual("log lines dropped by logd", gate["own_line"])

    def test_without_the_peers_proof_a_dropped_active_line_is_unmeasured_and_without_a_drop_report_it_fails(self):
        no_picture = "".join(text for text in self.endpoint_log("a").splitlines(True)
                             if "visible continuity" not in text and "first-surface" not in text)
        logs = {"a": no_picture, "b": self.endpoint_log("b", active=False)}
        gate = self.score(logs, drops={"a": "", "b": self.drop(2.4, 12)})["devices"][1]["gates"]["call_phase_active"]
        self.assertEqual(("UNMEASURED", "log lines dropped by logd"), (gate["status"], gate["reason"]))
        logs = {"a": self.endpoint_log("a"), "b": self.endpoint_log("b", active=False)}
        gate = self.score(logs, drops={"a": "", "b": ""})["devices"][1]["gates"]["call_phase_active"]
        self.assertEqual("FAIL", gate["status"])  # the peer's picture never replaces a line nobody reported lost

    def test_a_gate_whose_line_may_be_among_the_dropped_ones_names_the_log_drop(self):
        lost = "".join(text for text in self.endpoint_log("b").splitlines(True) if "BENCHMARK call rx:" not in text)
        score = self.score({"a": self.endpoint_log("a"), "b": lost}, drops={"a": "", "b": self.drop(14.5, 3)})
        named = {item["gate"]: item for item in score["unmeasured_gates"] if item["device"] == "b"}
        self.assertEqual({"audio_drops", "decoder_drops"}, set(named))
        self.assertEqual({"log lines dropped by logd"}, {item["reason"] for item in named.values()})
        self.assertEqual("UNMEASURED", score["overall_status"])
        # The same missing report with no drop reported stays unmeasured for its own reason.
        score = self.score({"a": self.endpoint_log("a"), "b": lost}, drops={"a": "", "b": ""})
        self.assertNotIn("log lines dropped by logd", {item["reason"] for item in score["unmeasured_gates"]})

    def test_a_counter_the_app_never_logs_is_not_blamed_on_log_drops(self):
        no_counter = self.endpoint_log("b", camera_counter=False)
        score = self.score({"a": self.endpoint_log("a"), "b": no_counter}, drops={"a": "", "b": self.drop(5, 3)},
                           source_kind="live_camera_bidirectional")
        (gate,) = score["unmeasured_gates"]
        self.assertEqual("camera_input_drops", gate["gate"])
        self.assertNotEqual("log lines dropped by logd", gate["reason"])

    def test_a_control_acknowledgement_lost_to_a_log_drop_is_unmeasured_not_a_failed_control(self):
        events = self.lifecycle()
        mute = {"device": "b", "action": "mute", "time": "10-03 00:00:09.000"}  # the runner never saw an answer
        error = "b: no app evidence for 'BENCHMARK control mute' within the deadline; log lines dropped by logd"
        loss = {"device": "b", "pid": 123, "pattern": "BENCHMARK control mute", "after": "10-03 00:00:09.000"}
        logs = {phone: self.endpoint_log(phone) for phone in "ab"}
        score = self.score(logs, drops={"a": "", "b": self.drop(9.5, 4)}, control_events=[*events[:2], mute],
                           runner_error=error, runner_error_log_loss=loss)
        control = next(item for item in score["lifecycle"] if item["action"] == "mute")
        self.assertEqual(("UNMEASURED", "log lines dropped by logd"),
                         (control["evidence"]["app_ack"]["status"], control["evidence"]["app_ack"]["reason"]))
        self.assertEqual([], score["failed_gates"])
        # The listed gate names why it is open, not just that it is.
        row = next(item for item in score["unmeasured_gates"] if item["gate"] == "mute")
        self.assertEqual("log lines dropped by logd", row["reason"])
        self.assertIn("app_ack", row["evidence"])
        score = self.score(logs, drops={"a": "", "b": ""}, control_events=[*events[:2], mute], runner_error=error)
        self.assertIn("mute", [item["gate"] for item in score["failed_gates"]])


class NotDialedIsRecordedByTheRunnerTest(CallFixture, unittest.TestCase):
    def test_a_run_that_stops_before_the_dial_records_that_no_call_was_dialed_and_why(self):
        with tempfile.TemporaryDirectory() as directory, fake_call(self.devices, self.case(), directory) as (phones, instance):
            phones.silent["b"] = ("BENCHMARK call discovered",)
            manifest = instance.run()
            score = json.loads((instance.output / "score.json").read_text(encoding="utf-8"))
        self.assertEqual([], manifest["control_events"])
        self.assertEqual("discovery", manifest["call_not_dialed"]["stage"])
        self.assertIn("no app evidence for 'BENCHMARK call discovered", manifest["call_not_dialed"]["reason"])
        self.assertEqual([], [args for args in phones.sent("a", "test_control") if args[-1].startswith("call:")])
        self.assertIs(False, score["call_dialed"])
        self.assertEqual("discovery", score["not_dialed"]["stage"])

    def test_a_dialed_run_records_no_such_thing(self):
        with tempfile.TemporaryDirectory() as directory, fake_call(self.devices, self.case(), directory) as (phones, instance):
            manifest = instance.run()
        self.assertNotIn("call_not_dialed", manifest)


class RetriedCallIsMeasuredToItsEndTest(CallFixture, unittest.TestCase):
    """A first attempt that failed before any ring is not the call. The callee logs 'Private call ended: Incoming
    call failed: EOFException' for it, then the retry rings and goes ACTIVE; the runner read that earlier line as
    the active call ending and stopped the case a few seconds into media."""

    def run_case(self, script=lambda phones: None, duration_s=12):
        case = {**self.case(), "duration_s": duration_s}
        with tempfile.TemporaryDirectory() as directory, fake_call(self.devices, case, directory) as (phones, instance):
            script(phones)
            manifest = instance.run()
            return phones, manifest, json.loads((instance.output / "score.json").read_text(encoding="utf-8"))

    def test_a_call_that_connected_after_a_failed_first_attempt_runs_to_its_planned_end(self):
        def first_attempt_fails(phones):
            phones.first_attempt_fails = True
        phones, manifest, score = self.run_case(first_attempt_fails)
        self.assertNotIn("runner_error", manifest)
        self.assertEqual(["call:000000000002", "accept", "end"], [item["action"] for item in manifest["control_events"]])
        self.assertEqual(set("ab"), set(manifest["control_events"][-1]["terminal_evidence"]))
        failed = [item for item in manifest["runner_events"] if item["action"] == "setup_attempt_failed"]
        self.assertEqual([("b", "Private call ended: Incoming call failed: EOFException")],
                         [(item["device"], item["message"]) for item in failed])
        self.assertEqual([], manifest["health_events"])

    def test_the_failed_first_attempt_stays_visible_in_the_score(self):
        def first_attempt_fails(phones):
            phones.first_attempt_fails = True
        _, _, score = self.run_case(first_attempt_fails)
        callee = next(item for item in score["devices"] if item["device"] == "b")
        self.assertEqual(["Private call ended: Incoming call failed: EOFException"],
                         [item["message"] for item in callee["setup_attempts_failed"]])
        self.assertEqual("FAIL", callee["incoming"]["gates"]["unexpected_events"]["status"])

    def test_an_end_line_after_the_call_went_active_still_stops_the_case(self):
        def link_lost_mid_call(phones):
            phones.after(60, lambda: phones.log("b", "Private call ended: Call link lost: EOFException"))
        _, manifest, score = self.run_case(link_lost_mid_call, duration_s=120)
        self.assertIn("b: unexpected call termination", manifest["runner_error"])
        self.assertEqual([("b", False)], [(item["device"], item["planned"]) for item in manifest["health_events"]])
        self.assertEqual("FAIL", score["overall_status"])

    def test_a_call_that_dropped_and_came_back_is_still_an_unexpected_termination(self):
        # The measured call is the first one that went ACTIVE after the dial. A later ACTIVE does not excuse its end.
        def drop_and_return(phones):
            def reconnect():
                phones.log("b", "Private call ended: Call link lost: EOFException")
                phones.log("b", "BENCHMARK call phase INCOMING_RINGING")
                phones.log("b", "BENCHMARK call phase ACTIVE")
            phones.after(60, reconnect)
        _, manifest, _ = self.run_case(drop_and_return, duration_s=120)
        self.assertIn("b: unexpected call termination", manifest["runner_error"])

    def test_a_declined_call_is_proven_by_its_own_end_not_by_the_failed_first_attempt(self):
        case = {**self.case(), "setup_action": "decline"}
        with tempfile.TemporaryDirectory() as directory, fake_call(self.devices, case, directory) as (phones, instance):
            phones.first_attempt_fails = True
            manifest = instance.run()
        self.assertNotIn("runner_error", manifest)
        invite, decline = manifest["control_events"]
        self.assertGreater(decline["terminal_evidence"]["b"]["ended"], invite["remote_ack"]["time"])

    def health_check(self, callee_lines, evidence=None, anchor=None):
        """Run one health check of callee b over the given (second, message) log lines."""
        instance = runner.CallRunner("adb", self.devices, self.case(), Path("unused"))
        instance.pids["b"] = "123"
        instance.logs = [{"device": "b", "path": "b.log", "pid": 123}]
        process = Mock()
        process.poll.return_value = None
        instance.captures = [(process, None)]
        instance.adb = Mock(side_effect=lambda phone, *args, **kw: "device" if args[0] == "get-state" else "123")
        instance.beep = Mock()
        instance.supervise_captures = Mock()
        instance.log_text = Mock(return_value="".join(line(second, message) for second, message in callee_lines))
        if anchor is not None:
            instance.setup_anchors = [{"device": "b", "action": "call_setup", "time": f"10-03 00:00:{anchor:06.3f}"}]
        if evidence is not None:
            instance.call_evidence["b"] = f"10-03 00:00:{evidence:06.3f}"
        instance.health_check("b")
        return instance

    FAILED_ATTEMPT = "Private call ended: Incoming call failed: EOFException"

    def test_without_the_active_line_the_first_media_the_runner_saw_binds_the_call(self):
        # logd can drop the one-time ACTIVE line; the first-surface evidence the runner waited for is then the proof.
        lines = [(14, self.FAILED_ATTEMPT), (17.8, "BENCHMARK call phase INCOMING_RINGING")]
        instance = self.health_check(lines, evidence=19, anchor=10)
        self.assertEqual(["setup_attempt_failed"], [item["action"] for item in instance.events])
        with self.assertRaisesRegex(RuntimeError, "unexpected call termination"):
            self.health_check([*lines, (25, "Private call ended: Call link lost: EOFException")], evidence=19, anchor=10)

    def test_with_no_proof_of_an_active_call_only_lines_before_the_dial_are_set_aside(self):
        instance = self.health_check([(5, self.FAILED_ATTEMPT)], anchor=10)
        self.assertEqual(["setup_attempt_failed"], [item["action"] for item in instance.events])
        with self.assertRaisesRegex(RuntimeError, "unexpected call termination"):
            self.health_check([(14, self.FAILED_ATTEMPT)], anchor=10)

    def test_a_superseded_attempt_is_recorded_once_however_often_the_log_is_read(self):
        lines = [(14, self.FAILED_ATTEMPT), (14.02, "BENCHMARK call ended_ui: message=Incoming call failed: EOFException"),
                 (17.8, "BENCHMARK call phase INCOMING_RINGING"), (18.5, "BENCHMARK call phase ACTIVE")]
        instance = self.health_check(lines, anchor=10)
        instance.observed_lines = {}
        instance.health_check("b")
        self.assertEqual(1, len(instance.events))
        self.assertEqual("10-03 00:00:18.500", instance.events[0]["call_active_since"])


class PlannedHangUpIsNotAnUnexpectedFreezeTest(CallFixture, unittest.TestCase):
    """The receiver's FINAL report is taken when the call ends, so the gap since the last frame (the peer stopped
    sending) shows up as one short freeze. That is the hang-up, not a freeze in the call (w2rm2-20261110a)."""

    def hung_up(self, phone, final_freezes=0, final_frozen_ms=0.0):
        """A healthy log of `phone` that ends at 15.0 s on the runner's planned End, with its FINAL report after."""
        text = self.endpoint_log(phone)
        text += line(15.0, "BENCHMARK call phase ENDED")
        if phone == "a":
            text += line(15.0, "BENCHMARK control end applied")
        text += line(15.0, "Private call ended: " + ("Call ended" if phone == "a" else "Peer ended the call"))
        text += line(15.1, f"BENCHMARK call available: node={self.devices[phone]['node_prefix']}")
        text += self.continuity(15.2, 190, final_freezes, final_frozen_ms, final=True)
        return text

    def score_with_end(self, logs):
        events = self.lifecycle()
        events[-1]["ack_time"] = "10-03 00:00:15.000"
        return self.score(logs, control_events=events)

    def freeze_gate(self, score, phone):
        device = next(item for item in score["devices"] if item["device"] == phone)
        return device["incoming"]["gates"]["unexpected_freeze_events"], device

    def test_a_short_freeze_in_the_final_report_after_the_hang_up_is_planned(self):
        score = self.score_with_end({"a": self.hung_up("a", 1, 270.0), "b": self.hung_up("b", 1, 221.0)})
        for phone in "ab":
            gate, device = self.freeze_gate(score, phone)
            self.assertEqual("PASS", gate["status"], phone)
            self.assertEqual(1, device["incoming"]["freezes"]["planned_end"], phone)
        self.assertEqual("PASS", score["overall_status"])

    def test_the_same_freeze_ten_seconds_before_the_hang_up_still_fails(self):
        # The freeze is in the periodic reports from 8 s on (the report at 13 s repeats it): it happened mid-call.
        late = {phone: self.hung_up(phone, 1, 270.0).replace(
            self.continuity(8, 76), self.continuity(8, 76, 1, 270.0)).replace(
            self.continuity(13, 151), self.continuity(13, 151, 1, 270.0)) for phone in "ab"}
        score = self.score_with_end(late)
        gate, _ = self.freeze_gate(score, "b")
        self.assertEqual("FAIL", gate["status"])
        self.assertEqual(1, gate["value"])

    def test_a_freeze_in_the_final_report_that_is_too_long_to_be_the_hang_up_still_fails(self):
        score = self.score_with_end({"a": self.hung_up("a", 1, 3000.0), "b": self.hung_up("b", 1, 3000.0)})
        gate, _ = self.freeze_gate(score, "b")
        self.assertEqual("FAIL", gate["status"])

    def test_a_final_report_without_the_planned_end_is_not_excused(self):
        # No End control: nobody asked for a hang-up, so a freeze at the end of the log is a freeze.
        logs = {phone: self.hung_up(phone, 1, 270.0) for phone in "ab"}
        score = self.score(logs, control_events=[event for event in self.lifecycle() if event["action"] != "end"])
        gate, _ = self.freeze_gate(score, "b")
        self.assertEqual("FAIL", gate["status"])


class WifiCarrierCloseAtPlannedEndTest(CallFixture, unittest.TestCase):
    """The phone that did not hang up logs its Wi-Fi carrier's read failing 0.1-0.3 s before its own call end: the peer
    closed the socket as part of the planned End (w2v1h-20261110k, w2hi-20261110k: 19 of 20 calls). Any other
    carrier death is a failure."""

    hung_up = PlannedHangUpIsNotAnUnexpectedFreezeTest.hung_up
    score_with_end = PlannedHangUpIsNotAnUnexpectedFreezeTest.score_with_end

    def death(self, second, reason="read failed: EOFException", age=130):
        return line(second, f"BENCHMARK carrier_death: carrier=WIFI_DIRECT | reason={reason} | ageMs={age}")

    def with_death(self, second, reason="read failed: EOFException"):
        log = self.hung_up("b")
        return log.replace(line(15.0, "BENCHMARK call phase ENDED"),
                           self.death(second, reason) + line(15.0, "BENCHMARK call phase ENDED"), 1) if second > 12 else (
            log.replace(line(14, f"BENCHMARK call rx: callId={CALL_ID} | final=true | audioDrops=0 | decoderDrops=0 | "
                                 "videoPackets=180 | audioPackets=100"),
                        self.death(second, reason) + line(14, f"BENCHMARK call rx: callId={CALL_ID} | final=true | "
                                                              "audioDrops=0 | decoderDrops=0 | videoPackets=180 | "
                                                              "audioPackets=100"), 1))

    def events_gate(self, score):
        device = next(item for item in score["devices"] if item["device"] == "b")
        return device["incoming"]["gates"]["unexpected_events"]

    def score_death(self, log, ended=True):
        logs = {"a": self.hung_up("a"), "b": log}
        return self.score_with_end(logs) if ended else self.score(
            logs, control_events=[event for event in self.lifecycle() if event["action"] != "end"])

    def test_the_read_failure_just_before_the_planned_end_is_not_an_unexpected_event(self):
        for reason in ("read failed: EOFException", "read failed: SocketException"):
            with self.subTest(reason=reason):
                gate = self.events_gate(self.score_death(self.with_death(14.8, reason)))
                self.assertEqual("PASS", gate["status"], gate)

    def test_the_same_line_in_the_middle_of_the_call_is_an_unexpected_event(self):
        gate = self.events_gate(self.score_death(self.with_death(9.0)))
        self.assertEqual(("FAIL", 1), (gate["status"], gate["value"]))

    def test_a_death_more_than_a_second_before_the_end_is_an_unexpected_event(self):
        gate = self.events_gate(self.score_death(self.with_death(13.5)))
        self.assertEqual("FAIL", gate["status"])

    def test_a_write_stall_or_silence_death_at_the_end_is_still_unexpected(self):
        for reason in ("write stalled", "silence"):
            with self.subTest(reason=reason):
                gate = self.events_gate(self.score_death(self.with_death(14.8, reason)))
                self.assertEqual("FAIL", gate["status"], reason)

    def test_without_a_confirmed_planned_end_the_death_is_unexpected(self):
        gate = self.events_gate(self.score_death(self.with_death(14.8), ended=False))
        self.assertEqual("FAIL", gate["status"])

    def test_the_hang_up_phone_whose_peer_proof_was_lost_still_has_its_own_end(self):
        # The peer's terminal records are missing (log loss), so no planned-end interval is confirmed; the hang-up phone's
        # own ENDED sits at its End acknowledgement and the peer closed the socket 50 ms later.
        def score(log):
            events = self.lifecycle()
            events[-1]["ack_time"] = "10-03 00:00:15.000"
            events[-1]["terminal_evidence"] = {"a": events[-1]["terminal_evidence"]["a"]}
            return self.score({"a": log, "b": self.hung_up("b")}, control_events=events)
        ended = line(15.0, "BENCHMARK call phase ENDED")
        after = self.hung_up("a").replace(ended, ended + self.death(15.05))
        mid = self.hung_up("a").replace(ended, self.death(9.0) + ended)
        gate = lambda result: next(item for item in result["devices"] if item["device"] == "a")["incoming"]["gates"]["unexpected_events"]
        self.assertEqual("PASS", gate(score(after))["status"])
        self.assertEqual("FAIL", gate(score(mid))["status"])


class CleanLinkCloseAtPlannedEndTest(CallFixture, unittest.TestCase):
    """The central logs its own clean close of the GATT link as the call ends (un2g-20261110n ASUS > S22, w2hi-20261110k
    m31): that is the hang-up. Another status or class, or the same line mid-call, is a lost link."""

    hung_up = PlannedHangUpIsNotAnUnexpectedFreezeTest.hung_up
    score_with_end = PlannedHangUpIsNotAnUnexpectedFreezeTest.score_with_end

    def lost(self, second, status=0, link_class="clean"):
        return line(second, f"BENCHMARK ll_gate: link=gatt-video | role=central | event=link_lost | status={status} | "
                            f"class={link_class} | ageMs=98237 | recentChanges=prio@-98095ms")

    def log(self, extra, at_end=True):
        ended = line(15.0, "BENCHMARK call phase ENDED")
        return self.hung_up("a").replace(ended, ended + extra if at_end else extra + ended)

    def gate(self, score):
        return next(item for item in score["devices"] if item["device"] == "a")["incoming"]["gates"]["unexpected_events"]

    def score(self, log, proof_lost=False, ended=True):
        events = self.lifecycle()
        events[-1]["ack_time"] = "10-03 00:00:15.000"
        if proof_lost:
            events[-1]["terminal_evidence"] = {"a": events[-1]["terminal_evidence"]["a"]}
        if not ended:
            events = [event for event in events if event["action"] != "end"]
        return super().score({"a": log, "b": self.hung_up("b")}, control_events=events)

    def test_a_clean_close_after_the_planned_end_is_not_an_unexpected_event(self):
        for second in (15.001, 15.3, 15.4):
            with self.subTest(second=second):
                self.assertEqual("PASS", self.gate(self.score(self.log(self.lost(second))))["status"])

    def test_the_same_close_when_the_peers_end_proof_was_lost_still_counts_as_planned(self):
        self.assertEqual("PASS", self.gate(self.score(self.log(self.lost(15.3)), proof_lost=True))["status"])

    def test_a_close_in_the_middle_of_the_call_is_an_unexpected_event(self):
        gate = self.gate(self.score(self.log(self.lost(9.0), at_end=False)))
        self.assertEqual(("FAIL", 1), (gate["status"], gate["value"]))
        self.assertEqual("FAIL", self.gate(self.score(self.log(self.lost(9.0), at_end=False), proof_lost=True))["status"])

    def test_another_status_or_class_at_the_end_is_still_a_lost_link(self):
        for status, link_class in ((8, "clean"), (0, "timeout"), (133, "error")):
            with self.subTest(status=status, link_class=link_class):
                self.assertEqual("FAIL", self.gate(self.score(self.log(self.lost(15.3, status, link_class))))["status"])

    def test_without_a_planned_end_a_close_is_a_lost_link(self):
        self.assertEqual("FAIL", self.gate(self.score(self.log(self.lost(15.3)), ended=False))["status"])


class LogLossAtTheEndIsNotARunnerError(CallFixture, unittest.TestCase):
    """The ASUS lost 37-49 lines per case (un2g-20261110n). The call was measured to its planned End; the lost 'ended' or
    'available' line only leaves the end proof UNMEASURED. A missing line that logd did not confirm dropping is a failure."""

    ENDED = ("Private call ended", "BENCHMARK call ended_ui")

    def run_case(self, silent, drop=True):
        case = {**self.case(), "duration_s": 6}
        with tempfile.TemporaryDirectory() as directory, fake_call(self.devices, case, directory) as (phones, instance):
            phones.silent_after_end["a"] = silent
            if drop:
                phones.drop_reports["a"] = line(60, "9", pid="{pid}", tag="liblog  ")
            manifest = instance.run()
            score = json.loads((instance.output / "score.json").read_text(encoding="utf-8"))
        end = next(item for item in manifest["control_events"] if item["action"] == "end")
        gate = next(item for item in score["lifecycle"] if item["action"] == "end")["evidence"]["ended_and_available"]
        return manifest, score, end, gate

    def test_a_dropped_ended_line_leaves_the_end_proof_unmeasured_not_a_runner_error(self):
        manifest, score, end, gate = self.run_case(self.ENDED)
        self.assertNotIn("runner_error", manifest)
        self.assertEqual(["b"], list(end["terminal_evidence"]))
        self.assertEqual("a", next(iter(end["terminal_log_loss"])))
        self.assertEqual(("UNMEASURED", "log lines dropped by logd"), (gate["status"], gate["reason"]))
        self.assertNotIn("runner", [item["action"] for item in score["lifecycle"]])

    def test_a_dropped_available_line_is_the_same(self):
        manifest, _, end, gate = self.run_case(("BENCHMARK call available",))
        self.assertNotIn("runner_error", manifest)
        self.assertEqual("UNMEASURED", gate["status"])

    def test_the_same_missing_line_without_a_confirmed_drop_stays_a_runner_error(self):
        manifest, score, end, _ = self.run_case(self.ENDED, drop=False)
        self.assertIn("no app evidence", manifest["runner_error"])
        self.assertEqual("FAIL", next(item for item in score["lifecycle"] if item["action"] == "runner")["gate"]["status"])


class FreezeAtTheEndWhenTheEndProofWasLostTest(CallFixture, unittest.TestCase):
    """When logd dropped an endpoint's end line, no planned End is confirmed for either phone. A FINAL-report freeze that
    the planned-end rule would have judged is then UNMEASURED, not excused and not blamed; earlier freezes still count."""

    hung_up = PlannedHangUpIsNotAnUnexpectedFreezeTest.hung_up
    freeze_gate = PlannedHangUpIsNotAnUnexpectedFreezeTest.freeze_gate

    def score_lost(self, logs, lost=True):
        events = self.lifecycle()
        events[-1]["ack_time"] = "10-03 00:00:15.000"
        events[-1]["terminal_evidence"] = {"b": events[-1]["terminal_evidence"]["b"]}
        if lost:
            events[-1]["terminal_log_loss"] = {"a": {"device": "a", "pid": 123, "reason": "log lines dropped by logd"}}
        return self.score(logs, control_events=events)

    def test_a_final_report_freeze_is_unmeasured_on_both_phones(self):
        score = self.score_lost({"a": self.hung_up("a", 1, 270.0), "b": self.hung_up("b", 1, 221.0)})
        for phone in "ab":
            gate, _ = self.freeze_gate(score, phone)
            self.assertEqual(("UNMEASURED", "log lines dropped by logd"), (gate["status"], gate["reason"]), phone)
            self.assertIn("log_loss", gate)

    def test_a_clean_call_is_not_made_unmeasured_by_the_lost_proof(self):
        score = self.score_lost({"a": self.hung_up("a"), "b": self.hung_up("b")})
        for phone in "ab":
            self.assertEqual("PASS", self.freeze_gate(score, phone)[0]["status"], phone)

    def test_a_freeze_in_a_periodic_report_still_fails(self):
        late = {phone: self.hung_up(phone, 1, 270.0).replace(
            self.continuity(8, 76), self.continuity(8, 76, 1, 270.0)).replace(
            self.continuity(13, 151), self.continuity(13, 151, 1, 270.0)) for phone in "ab"}
        gate, _ = self.freeze_gate(self.score_lost(late), "b")
        self.assertEqual("FAIL", gate["status"])

    def test_a_missing_end_proof_without_a_confirmed_drop_stays_a_failure(self):
        gate, _ = self.freeze_gate(self.score_lost({"a": self.hung_up("a", 1, 270.0), "b": self.hung_up("b", 1, 221.0)},
                                                   lost=False), "b")
        self.assertEqual("FAIL", gate["status"])

    def test_a_freeze_too_long_to_be_the_hang_up_still_fails(self):
        gate, _ = self.freeze_gate(self.score_lost({"a": self.hung_up("a", 1, 3000.0), "b": self.hung_up("b", 1, 3000.0)}), "b")
        self.assertEqual("FAIL", gate["status"])


def crash_line(second, message, tag="AndroidRuntime", pid=4242, tid=4242, level="E"):
    return f"10-03 00:00:{second:06.3f} {pid} {tid} {level} {tag}: {message}\n"


class AppCrashesAndAnrsAreCaughtTest(CallFixture, unittest.TestCase):
    """A Java crash is logged by the dying app, an ANR by ActivityManager in the system process, a native crash by
    debuggerd: none of them reaches the per-PID app capture, so a call that ended in one read as a quiet call."""

    JAVA = (crash_line(5, "FATAL EXCEPTION: main") + crash_line(5.001, "Process: com.example.bluetoothtest, PID: 4242") +
            crash_line(5.002, "java.lang.IllegalStateException: boom"))
    ANR = crash_line(6, "ANR in com.example.bluetoothtest (com.example.bluetoothtest/.MainActivity)",
                     tag="ActivityManager", pid=900, tid=950)
    NATIVE = crash_line(7, "pid: 4242, tid: 4300, name: RenderThread  >>> com.example.bluetoothtest <<<", tag="DEBUG", level="F")

    def run_case(self, script=lambda phones: None):
        case = {**self.case(), "duration_s": 12}
        with tempfile.TemporaryDirectory() as directory, fake_call(self.devices, case, directory) as (phones, instance):
            script(phones)
            manifest = instance.run()
            score = json.loads((instance.output / "score.json").read_text(encoding="utf-8"))
            files = {item.name: item.read_bytes() for item in instance.output.iterdir() if item.is_file()}
            return phones, manifest, score, files

    def gate(self, score, phone):
        device = next(item for item in score["devices"] if item["device"] == phone)
        return device["gates"].get("app_crash_or_anr")

    def test_a_clean_case_reads_the_buffers_of_every_phone_and_passes_the_gate(self):
        phones, manifest, score, _ = self.run_case()
        self.assertEqual({"a", "b"}, {item["device"] for item in manifest["crash_logs"]})
        for phone in "ab":
            self.assertEqual(("PASS", 0), (self.gate(score, phone)["status"], self.gate(score, phone)["value"]))
            dumps = [args for args in phones.sent(phone, "-b") if args[2] in ("crash", "system")]
            self.assertEqual(["crash", "system"], [args[2] for args in dumps])
            # Only the case window is dumped: nothing from before this launch.
            self.assertTrue(all("-T" in args for args in dumps))

    def test_each_kind_of_crash_fails_the_phone_that_crashed_and_only_that_one(self):
        for name, crash, buffer in (("java", self.JAVA, "crash"), ("anr", self.ANR, "system"), ("native", self.NATIVE, "crash")):
            with self.subTest(name):
                def script(phones, crash=crash, buffer=buffer):
                    phones.system_buffers[("a", buffer)] = crash
                _, manifest, score, _ = self.run_case(script)
                self.assertEqual(("FAIL", 1), (self.gate(score, "a")["status"], self.gate(score, "a")["value"]))
                self.assertEqual("PASS", self.gate(score, "b")["status"])
                self.assertIn(("a", "app_crash_or_anr"), [(item["device"], item["gate"]) for item in score["failed_gates"]])
                self.assertEqual("FAIL", score["overall_status"])

    def test_another_apps_crash_and_a_crash_before_this_launch_are_not_this_cases(self):
        other = (crash_line(5, "FATAL EXCEPTION: main") + crash_line(5.001, "Process: com.other.app, PID: 77") +
                 crash_line(6, "ANR in com.example.bluetoothtestextra", tag="ActivityManager"))
        def script(phones):
            phones.system_buffers[("a", "crash")] = other
            # The phone's own clock starts at 00:00:00; this crash predates the launch window of the fake clock.
            phones.system_buffers[("b", "system")] = "09-30 12:00:00.000 900 950 E ActivityManager: ANR in com.example.bluetoothtest\n"
        _, _, score, _ = self.run_case(script)
        self.assertEqual("PASS", self.gate(score, "a")["status"])
        self.assertEqual("PASS", self.gate(score, "b")["status"])

    def test_a_phone_whose_buffers_cannot_be_read_is_unmeasured_not_clean(self):
        def script(phones):
            phones.unreachable_buffers.add("a")
        _, manifest, score, _ = self.run_case(script)
        gate = self.gate(score, "a")
        self.assertEqual("UNMEASURED", gate["status"])
        self.assertTrue(gate["reason"])
        self.assertEqual("PASS", self.gate(score, "b")["status"])

    def test_the_dump_is_kept_as_hash_bound_evidence(self):
        def script(phones):
            phones.system_buffers[("a", "crash")] = self.JAVA
        _, manifest, _, files = self.run_case(script)
        entry = next(item for item in manifest["crash_logs"] if item["device"] == "a")
        self.assertEqual("MEASURED", entry["status"])
        self.assertEqual([("JAVA_CRASH", "10-03 00:00:05.000")], [(item["kind"], item["time"]) for item in entry["events"]])
        self.assertEqual(entry["sha256"], hashlib.sha256(files[entry["path"]]).hexdigest())

    def test_a_stream_receiver_that_crashed_fails_its_run(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / "rx.log").write_text(self.endpoint_log("b"), encoding="utf-8")
            dump = "### logcat -b crash" + chr(10) + self.JAVA
            (folder / "rx-crash-events.log").write_bytes(dump.encode())
            crash = {"device": "rx", "start": "10-03 00:00:00.000", "end": "10-03 00:00:30.000", "status": "MEASURED",
                     "path": "rx-crash-events.log", "sha256": hashlib.sha256(dump.encode()).hexdigest()}
            manifest = {"logs": [{"device": "rx", "path": "rx.log", "pid": 123, "role": "receiver"}], "crash_logs": [crash]}
            score = device_scorer.score_manifest(manifest, folder)
        self.assertEqual("FAIL", score["devices"][0]["gates"]["app_crash_or_anr"]["status"])
        self.assertEqual("FAIL", score["devices"][0]["log_status"])
        self.assertEqual("FAIL", score["overall_status"])

    def test_a_manifest_recorded_before_this_capture_existed_has_no_such_gate(self):
        score = self.score({phone: self.endpoint_log(phone) for phone in "ab"})
        self.assertNotIn("app_crash_or_anr", score["devices"][0]["gates"])
        self.assertEqual("PASS", score["overall_status"])


class VolumeIsLoweredDuringTheCallTest(CallFixture, unittest.TestCase):
    """Owner rule: every phone stays at its lowest volume; the runner presses volume-down on every call participant
    once the call is active, never volume-up, and never at the cost of the call."""

    def run_case(self, script=lambda phones: None):
        case = {**self.case(), "duration_s": 12}
        with tempfile.TemporaryDirectory() as directory, fake_call(self.devices, case, directory) as (phones, instance):
            script(phones)
            return phones, instance.run()

    def test_every_participant_gets_eight_volume_down_presses_in_one_command_after_the_accept(self):
        phones, manifest = self.run_case()
        for phone in "ab":
            presses = phones.sent(phone, "KEYCODE_VOLUME_DOWN")
            self.assertEqual(1, len(presses), phone)
            self.assertEqual(["shell", "input", "keyevent"] + ["KEYCODE_VOLUME_DOWN"] * 8, presses[0])
        self.assertEqual({"a", "b"}, {item["device"] for item in manifest["runner_events"] if item["action"] == "volume_down"})
        # After the call was accepted, before it ended.
        def index(word):
            return next(position for position, (_, args) in enumerate(phones.commands) if word in args)
        self.assertTrue(index("accept") < index("KEYCODE_VOLUME_DOWN") < index("end"))

    def test_the_volume_is_never_raised(self):
        phones, _ = self.run_case()
        self.assertFalse([args for _, args in phones.commands if any(word.startswith("KEYCODE_VOLUME_UP") or
                                                                      "KEYCODE_VOLUME_MUTE" in word for word in args)])

    def test_a_phone_that_refuses_shell_input_does_not_fail_the_call(self):
        def refuse(phones):
            phones.refuse_input.update("ab")
        phones, manifest = self.run_case(refuse)
        self.assertNotIn("runner_error", manifest)
        self.assertEqual(["end"], [item["action"] for item in manifest["control_events"]][-1:])
        self.assertEqual({"a", "b"}, {item["device"] for item in manifest["runner_events"] if item["action"] == "volume_down_failed"})


class CameraDropsOnTheHardwareEncoderPathAreReadTest(CallFixture, unittest.TestCase):
    """camera_input_drops stays a required gate. The app will log it on the surface (hardware-encoder) path in the same
    format as the native path (`capture drops N`); the scorer must read that line, not wait for native timing."""

    live = {"source_kind": "live_camera_bidirectional", "source_kinds": {"a": "live_camera", "b": "live_camera"}}

    def gate(self, text_for_a):
        score = self.score({"a": text_for_a, "b": self.endpoint_log("b")}, **self.live)
        device = next(item for item in score["devices"] if item["device"] == "a")
        return device["outgoing"]["gates"].get("camera_input_drops"), score

    def test_a_surface_path_line_with_no_drops_passes_the_gate(self):
        gate, score = self.gate(self.endpoint_log("a").replace("Encoder input: capture drops 0",
                                                              "Encoder input: capture drops 0, surface path"))
        self.assertEqual(("PASS", 0), (gate["status"], gate["value"]))
        self.assertEqual("PASS", score["overall_status"])

    def test_a_surface_path_line_with_drops_fails_it(self):
        gate, score = self.gate(self.endpoint_log("a").replace("Encoder input: capture drops 0",
                                                              "Encoder input: capture drops 3"))
        self.assertEqual(("FAIL", 3), (gate["status"], gate["value"]))
        self.assertEqual("FAIL", score["overall_status"])

    def test_without_any_such_line_the_gate_stays_unmeasured_and_the_call_cannot_pass(self):
        gate, score = self.gate(self.endpoint_log("a", camera_counter=False))
        self.assertEqual("UNMEASURED", gate["status"])
        self.assertEqual("UNMEASURED", score["overall_status"])


class AnArmMustBeSeenAppliedTest(CallFixture, unittest.TestCase):
    """An experiment arm the app never received looked like a valid A/B (TEST-14). The runner records, per requested
    extra, whether the app's launch_extras echo listed it and whether its effect line shows the value; an arm the app
    demonstrably did not take blocks the case before the dial, and a missing echo is unknown, never a pass."""

    POLICY = ("BENCHMARK call_policy: videoFragments=false | fragmentAirtimeMs=0 | speechLedPlayout=false | "
              "audioHeadroom=true | codecFallback=true | legacyLowDelay=false | legacyRelief=false | lockScreen=x | "
              "firstFrameNow=false | encoderFit=false | relayChain={chain} | relayedMeasuredCap=false | closeWaitsForPeer=true")

    def run_case(self, script=lambda phones: None, tunables=None):
        case = {**self.case(), "duration_s": 12, "tunables": tunables or {"call_relay_chain": False, "gatt_gap_fast_fail": False}}
        with tempfile.TemporaryDirectory() as directory, fake_call(self.devices, case, directory) as (phones, instance):
            script(phones)
            manifest = instance.run()
            return phones, manifest

    def arms(self, manifest, phone):
        check = next(item for item in manifest["applied_arms"] if item["device"] == phone)
        return {row["extra"]: row for row in check["arms"]}

    def test_a_delivered_arm_with_a_matching_effect_line_is_recorded_as_applied(self):
        def script(phones):
            phones.effect_lines["a"] = [self.POLICY.format(chain="false")]
        _, manifest = self.run_case(script)
        row = self.arms(manifest, "a")["test_call_relay_chain"]
        self.assertEqual((True, True), (row["delivered"], row["applied"]))
        self.assertNotIn("runner_error", manifest)

    def test_an_arm_without_a_known_effect_line_is_delivered_but_not_claimed_applied(self):
        _, manifest = self.run_case()
        row = self.arms(manifest, "a")["test_gatt_gap_fast_fail"]
        self.assertEqual((True, None), (row["delivered"], row["applied"]))
        self.assertIn("not proven applied", row["applied_basis"])

    def test_an_arm_the_echo_does_not_list_blocks_the_case_before_the_dial(self):
        def script(phones):
            phones.drop_launch_extra = "test_call_relay_chain"
        phones, manifest = self.run_case(script)
        self.assertEqual("arm_not_delivered", manifest["runner_blocked"]["reason"])
        self.assertIn("test_call_relay_chain", manifest["runner_blocked"]["detail"])
        self.assertEqual("launch", manifest["call_not_dialed"]["stage"])
        self.assertFalse(phones.sent("a", "test_control"))
        self.assertFalse(manifest.get("control_events"))

    def test_an_effect_line_that_shows_another_value_blocks_the_case(self):
        def script(phones):
            phones.effect_lines["a"] = [self.POLICY.format(chain="true")]  # the arm asked for false
        _, manifest = self.run_case(script)
        self.assertEqual("arm_not_applied", manifest["runner_blocked"]["reason"])
        self.assertIn("test_call_relay_chain requested false, app logged true", manifest["runner_blocked"]["detail"])

    def test_a_missing_echo_is_unknown_not_blocked_and_says_why(self):
        def script(phones):
            phones.echo_launch = False
        _, manifest = self.run_case(script)
        self.assertNotIn("runner_blocked", manifest)
        row = self.arms(manifest, "a")["test_call_relay_chain"]
        self.assertEqual((None, None), (row["delivered"], row["applied"]))
        self.assertIn("echo absent", row["delivered_basis"])

    def test_an_echo_among_the_lines_logd_dropped_says_so_and_does_not_block(self):
        def script(phones):
            phones.echo_drops.add("a")
            phones.drop_reports["a"] = line(0.5, "9", pid="{pid}", tag="liblog  ")
        _, manifest = self.run_case(script)
        self.assertNotIn("runner_blocked", manifest)
        self.assertIn("logd dropped", self.arms(manifest, "a")["test_call_relay_chain"]["delivered_basis"])

    def test_the_echo_is_read_as_the_app_writes_it(self):
        import applied_arms
        text = line(0.2, "BENCHMARK launch_extras: count=2 | test_codec=vp9;x,test_role=send") + line(0.3, "other")
        self.assertEqual({"test_codec": "vp9;x", "test_role": "send"}, applied_arms.parse_launch_echo(text))
        self.assertEqual({}, applied_arms.parse_launch_echo(line(0.2, "BENCHMARK launch_extras: count=0")))
        self.assertIsNone(applied_arms.parse_launch_echo(line(0.2, "BENCHMARK call available: node=1")))
        # A value the app cut at 64 characters still matches what the runner sent.
        sent = {"test_x": "v" * 80}
        echo = line(0.2, "BENCHMARK launch_extras: count=1 | test_x=" + "v" * 64 + "...")
        self.assertTrue(applied_arms.assess(sent, echo)[0]["delivered"])

    def test_the_runners_own_playback_gain_is_not_an_arm(self):
        _, manifest = self.run_case()
        self.assertNotIn("test_playback_gain", self.arms(manifest, "a"))


if __name__ == "__main__":
    unittest.main()
