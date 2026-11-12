"""The release-gate join metric, the idle callee, and the user-launch peer/transport gates (design decision 2026-11-10).

A call's join is its caller-clock dial->ringing stage plus the receiving phone's own-clock Accept->first non-black
picture stage; the suite gate passes at 90% within 6 s with nothing over 15 s. The fake phones log the app's
call_join_stage lines on a per-phone clock so a mixed-clock sum would show.
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
sys.path.insert(0, str(Path(__file__).parent))
import release_join
import run_benchmark_suite as suite
import run_private_call_benchmark as runner
from test_user_launch import Fixture


def call(join_ms, idle=True, user=True, status=None, case_id="c"):
    """A suite result carrying a release_join call record."""
    state = status or ("MEASURED" if join_ms is not None else "FAILED_JOIN")
    return {"case_id": case_id, "run_id": "r", "release_join": {
        "status": state, "join_ms": join_ms, "user_launch": user, "reason": "x" if state != "MEASURED" else None,
        "idle_callee": {"idle": idle, "actual_s": 12.0 if idle else 0}}}


class JoinCases(Fixture):
    def case(self, **changes):
        return super().case(**{"transport": "classic", **changes})

    def run_case(self, case, **fakes):
        def script(phones):
            phones.join_stages = fakes.pop("join_stages", True)
            for name, value in fakes.items():
                setattr(phones, name, value)
        return self.execute(case, script)


class PerCallJoinTest(JoinCases, unittest.TestCase):
    def test_join_is_the_caller_ring_stage_plus_the_receivers_accept_to_picture_stage(self):
        _, manifest, score = self.run_case(self.case(), ring_s=0.8, picture_s={"a": 2.0, "b": 1.0})
        self.assertNotIn("runner_error", manifest)
        record = score["release_join"]
        by_receiver = {item["receiver"]: item for item in record["directions"]}
        self.assertEqual("MEASURED", record["status"])
        self.assertAlmostEqual(800, by_receiver["a"]["stage_dial_to_ringing_ms"], delta=5)
        self.assertAlmostEqual(2000, by_receiver["a"]["stage_accept_to_picture_ms"], delta=10)
        self.assertAlmostEqual(1000, by_receiver["b"]["stage_accept_to_picture_ms"], delta=10)
        self.assertEqual(by_receiver["a"]["stage_dial_to_ringing_ms"] + by_receiver["a"]["stage_accept_to_picture_ms"],
                         by_receiver["a"]["join_ms"])
        self.assertEqual(max(item["join_ms"] for item in record["directions"]), record["join_ms"])
        gates = {item["device"]: item["gates"]["release_join_ms"] for item in score["devices"]}
        self.assertEqual(by_receiver["a"]["join_ms"], gates["a"]["value"])
        self.assertTrue(all(gate["status"] == "DIAGNOSTIC" for gate in gates.values()))

    def test_each_stage_uses_one_phones_clock_so_an_offset_between_clocks_changes_nothing(self):
        _, _, aligned = self.run_case(self.case())
        _, _, skewed = self.run_case(self.case(), clock_offset_ms={"a": 7_000_000, "b": 123_456})
        self.assertEqual(aligned["release_join"]["join_ms"], skewed["release_join"]["join_ms"])

    def test_a_black_first_picture_is_unmeasured_with_the_reason_never_a_pass_or_a_fail(self):
        _, _, score = self.run_case(self.case(), black_first={"a"})
        record = score["release_join"]
        self.assertEqual("UNMEASURED", record["status"])
        self.assertIsNone(record["join_ms"])
        black = next(item for item in record["directions"] if item["receiver"] == "a")
        self.assertIn("black", black["reason"])
        self.assertEqual("MEASURED", next(item for item in record["directions"] if item["receiver"] == "b")["status"])
        gate = next(item for item in score["devices"] if item["device"] == "a")["gates"]["release_join_ms"]
        self.assertEqual("UNMEASURED", gate["measured_status"])

    def test_a_dark_direction_reaches_the_score_as_scene_dark_while_the_lit_one_keeps_its_join(self):
        _, _, score = self.run_case(self.case(), black_first={"a"})
        record = score["release_join"]
        self.assertEqual(("UNMEASURED", None, "SCENE_DARK"), (record["status"], record["join_ms"], record["reason_code"]))
        gates = {item["device"]: item["gates"]["release_join_ms"] for item in score["devices"]}
        self.assertEqual("SCENE_DARK", gates["a"]["reason_code"])
        self.assertIsNotNone(gates["b"]["value"])
        self.assertIn("stage1_substages", gates["b"])  # the stage-1 rows travel with the per-phone row
        self.assertEqual(gates["b"]["value"], gates["b"]["stage_dial_to_ringing_ms"] + gates["b"]["stage_accept_to_picture_ms"])

    def test_a_black_picture_followed_by_a_logged_real_one_uses_the_real_one(self):
        rows = [
            {"message": "BENCHMARK call_join_stage: stage=accept | role=callee | sinceStartMs=10 | atMs=1010 | by=local",
             "time": "10-03 00:00:01.010", "at": 1.010},
            {"message": "BENCHMARK call_join_stage: stage=first_frame_shown | role=callee | sinceStartMs=900 | "
                        "atMs=1900 | black=true | meanLuma=0.3", "time": "10-03 00:00:01.900", "at": 1.9},
            {"message": "BENCHMARK call_join_stage: stage=first_frame_shown | role=callee | sinceStartMs=2500 | "
                        "atMs=3500 | black=false | meanLuma=40.0", "time": "10-03 00:00:03.500", "at": 3.5}]
        picture, problem = release_join.picture_stage(release_join.stage_events(rows), None)
        self.assertIsNone(problem)
        self.assertEqual(2490, picture["accept_to_picture_ms"])
        self.assertTrue(picture["first_picture_black"])

    def test_the_apps_first_nonblack_line_after_a_black_first_picture_names_the_real_picture(self):
        rows = [
            {"message": "BENCHMARK call_join_stage: stage=accept | role=callee | sinceStartMs=10 | atMs=1010 | by=local",
             "time": "10-03 00:00:01.010", "at": 1.010},
            {"message": "BENCHMARK call_join_stage: stage=first_frame_shown | role=callee | sinceStartMs=900 | "
                        "atMs=1900 | black=true | meanLuma=0.3", "time": "10-03 00:00:01.900", "at": 1.9},
            {"message": "BENCHMARK call_join_stage: stage=first_nonblack_shown | role=callee | sinceStartMs=1700 | "
                        "atMs=2700 | black=false | meanLuma=22.5 | reached=true | samples=5 | sinceFirstShownMs=800",
             "time": "10-03 00:00:02.700", "at": 2.7}]
        picture, problem = release_join.picture_stage(release_join.stage_events(rows), None)
        self.assertIsNone(problem)
        self.assertEqual(1690, picture["accept_to_picture_ms"])
        self.assertTrue(picture["first_picture_black"])

    def test_no_nonblack_picture_within_the_apps_bound_is_unmeasured_as_a_dark_scene(self):
        rows = [
            {"message": "BENCHMARK call_join_stage: stage=accept | role=callee | sinceStartMs=10 | atMs=1010 | by=local",
             "time": "10-03 00:00:01.010", "at": 1.010},
            {"message": "BENCHMARK call_join_stage: stage=first_frame_shown | role=callee | sinceStartMs=900 | "
                        "atMs=1900 | black=true | meanLuma=0.3", "time": "10-03 00:00:01.900", "at": 1.9},
            {"message": "BENCHMARK call_join_stage: stage=first_nonblack_shown | role=callee | sinceStartMs=15010 | "
                        "atMs=16010 | black=true | meanLuma=0.4 | reached=false | reason=bound",
             "time": "10-03 00:00:16.010", "at": 16.01}]
        picture, problem = release_join.picture_stage(release_join.stage_events(rows), None)
        self.assertIsNone(picture)
        self.assertEqual("UNMEASURED", problem[0])
        self.assertIn("dark scene", problem[1])

    def test_a_build_without_the_stage_lines_is_unmeasured_never_a_failure(self):
        _, manifest, score = self.run_case(self.case(), join_stages=False)
        record = score["release_join"]
        self.assertEqual("UNMEASURED", record["status"])
        self.assertIn("predates", record["reason"])
        gates = [item["gates"]["release_join_ms"] for item in score["devices"]]
        self.assertTrue(all(gate["measured_status"] == "UNMEASURED" for gate in gates))
        self.assertNotIn("release_join_ms", [item["gate"] for item in score["failed_gates"]])
        self.assertNotIn("release_join_ms", [item["gate"] for item in score["unmeasured_gates"]])

    def test_a_ringing_call_whose_receiver_never_shows_a_picture_is_a_failed_join(self):
        _, _, score = self.run_case(self.case(), no_picture={"b"})
        record = score["release_join"]
        self.assertEqual("FAILED_JOIN", record["status"])
        self.assertIsNone(record["join_ms"])
        self.assertIn("no shown picture", record["reason"])

    def test_a_call_that_never_rang_is_a_failed_join_unless_logd_dropped_the_line(self):
        caller = [{"message": "BENCHMARK call_join_stage: stage=dial | role=caller | sinceStartMs=0 | atMs=100",
                   "time": "10-03 00:00:00.100", "at": 0.1}]
        events = release_join.stage_events(caller)
        failed = release_join.direction_record("b", "a", events, events, None)
        self.assertEqual("FAILED_JOIN", failed["status"])
        dropped = release_join.direction_record("b", "a", events, events, None, dropped="log lines dropped by logd")
        self.assertEqual("UNMEASURED", dropped["status"])

    @staticmethod
    def rows(*messages):
        return [{"message": message, "time": "11-11 05:35:%06.3f" % (index / 10), "at": index / 10}
                for index, message in enumerate(messages)]

    def ringing_caller(self):
        return release_join.stage_events(self.rows(
            "BENCHMARK call_join_stage: stage=dial | role=caller | sinceStartMs=0 | atMs=100 | callId=c0ffee",
            "BENCHMARK call_join_stage: stage=ringing | role=caller | sinceStartMs=800 | atMs=900 | callId=c0ffee"))

    def test_a_shown_line_lost_from_the_log_is_recovered_from_the_calls_summary_line(self):
        # u10c 2026-11-11: the Realme lost a ~0.3 s burst of lines at call start, first_frame_shown among them.
        receiver = self.rows(
            "BENCHMARK call_join_stage: stage=accept | role=callee | sinceStartMs=1000 | atMs=5000 | callId=c0ffee | by=user",
            "BENCHMARK call_join_summary: role=callee | startMs=4000 | callId=c0ffee | stages=link_up@4000,"
            "accept@5000[by=user],first_frame_shown@6200[black=false;meanLuma=40.0]")
        events = release_join.stage_events(receiver)
        self.assertEqual(1, sum(1 for item in events if item["stage"] == "accept"))  # a stage with its own line stays one
        record = release_join.direction_record("a", "b", self.ringing_caller(), events, "c0ffee",
                                               receiver_shown=release_join.shown_frames(receiver))
        self.assertEqual("MEASURED", record["status"])
        self.assertEqual(1200, record["stage_accept_to_picture_ms"])
        self.assertEqual(800 + 1200, record["join_ms"])

    def test_a_receiver_that_showed_frames_but_lost_its_shown_line_is_unmeasured_not_a_failed_join(self):
        receiver = self.rows(
            "BENCHMARK call_join_stage: stage=accept | role=callee | sinceStartMs=1000 | atMs=5000 | callId=c0ffee | by=user",
            "BENCHMARK continuity: role=call_rx | final=false | policy=noloss | frames=75 | freezeEvents=0")
        events = release_join.stage_events(receiver)
        lost = release_join.direction_record("a", "b", self.ringing_caller(), events, "c0ffee",
                                             receiver_shown=release_join.shown_frames(receiver))
        self.assertEqual("UNMEASURED", lost["status"])
        self.assertIn("75 frames", lost["reason"])
        nothing = release_join.direction_record("a", "b", self.ringing_caller(), events, "c0ffee", receiver_shown=0)
        self.assertEqual("FAILED_JOIN", nothing["status"])  # no frame reached the screen: the call did not join

    def test_setup_to_first_surface_is_a_target_in_a_user_launch_and_a_failure_in_a_benchmark_launch(self):
        over = runner.first_surface_gate(4376.0, user_launch_phone=True)
        self.assertEqual(("DIAGNOSTIC", "FAIL", False), (over["status"], over["measured_status"], over["decides"]))
        self.assertEqual(3000, over["target_ms"])
        self.assertEqual("FAIL", runner.first_surface_gate(4376.0, user_launch_phone=False)["status"])
        self.assertEqual("PASS", runner.first_surface_gate(2500.0, user_launch_phone=False)["status"])

    def test_a_user_launch_call_slower_than_3_s_is_not_failed_by_the_target(self):
        _, _, score = self.run_case(self.case(), picture_s={"a": 4.0, "b": 4.0})
        record = score["release_join"]
        self.assertGreater(record["join_ms"], runner.DEFAULT_THRESHOLDS["max_startup_ms"])
        self.assertNotIn("setup_to_first_surface_ms", [item["gate"] for item in score["failed_gates"]])


class IdleCalleeTest(JoinCases, unittest.TestCase):
    def test_the_caller_dials_only_after_the_callee_has_been_idle_for_the_requested_time(self):
        phones, manifest, score = self.run_case(self.case(callee_idle_s=12))
        idle = manifest["callee_idle"]
        self.assertEqual("rotid_own", idle["anchor"])  # a user launch waits for the callee's own offer line too
        self.assertGreaterEqual(idle["actual_s"], 12)
        self.assertLess(idle["actual_s"], 13)
        self.assertEqual(12, idle["requested_s"])
        self.assertTrue(score["release_join"]["idle_callee"]["idle"])
        self.assertEqual(1, len(self.dialed(phones)))

    def test_a_case_without_callee_idle_s_dials_at_once_and_is_not_an_idle_call(self):
        phones, manifest, score = self.run_case(self.case())
        self.assertNotIn("callee_idle", manifest)
        self.assertFalse(score["release_join"]["idle_callee"]["idle"])

    def test_an_idle_time_inside_the_apps_fast_window_does_not_qualify(self):
        _, manifest, score = self.run_case(self.case(callee_idle_s=5))
        self.assertGreaterEqual(manifest["callee_idle"]["actual_s"], 5)
        self.assertFalse(score["release_join"]["idle_callee"]["idle"])

    def test_the_benchmark_launch_anchors_the_wait_on_call_available(self):
        _, manifest, _ = self.run_case(self.case(tunables={}, callee_idle_s=12))
        self.assertEqual("call available", manifest["callee_idle"]["anchor"])

    def test_the_option_is_validated(self):
        devices = self.devices
        for bad in (0, -1, True, "12", 301):
            with self.assertRaises(ValueError):
                runner.validate_call_case(self.case(callee_idle_s=bad), devices)
        runner.validate_call_case(self.case(callee_idle_s=release_join.RELEASE_CALLEE_IDLE_S), devices)
        with self.assertRaises(ValueError):
            runner.validate_call_case(self.case(callee_idle_s=12, relays=["r"], relay_shape="on_demand"), devices)

    def test_the_release_default_is_just_beyond_the_apps_fast_window(self):
        self.assertGreater(release_join.RELEASE_CALLEE_IDLE_S, release_join.APP_FAST_WINDOW_S)
        self.assertGreaterEqual(release_join.RELEASE_CALLEE_IDLE_S, release_join.IDLE_QUALIFIES_S)


class SuiteGateTest(unittest.TestCase):
    def joins(self, within, over_line=0, outlier_ms=None):
        calls = [call(4200 + index) for index in range(within)] + [call(7000 + index) for index in range(over_line)]
        return calls + ([call(outlier_ms)] if outlier_ms else [])

    def test_ninety_of_a_hundred_within_six_seconds_passes_and_eighty_nine_fails(self):
        passing = release_join.suite_gate(self.joins(90, 10))
        self.assertEqual(("PASS", 100, 90), (passing["status"], passing["calls_counted"], passing["calls_within_interim_line"]))
        self.assertEqual(90.0, passing["share_within_interim_line_percent"])
        failing = release_join.suite_gate(self.joins(89, 11))
        self.assertEqual("FAIL", failing["status"])
        self.assertIn("89 of 100", failing["reason"])
        self.assertEqual(11, len(failing["calls_over_interim_line"]))

    def test_one_call_over_fifteen_seconds_fails_a_gate_that_is_otherwise_ninety_percent(self):
        gate = release_join.suite_gate(self.joins(99, 0, outlier_ms=16000))
        self.assertEqual("FAIL", gate["status"])
        self.assertEqual(16000, gate["max_ms"])
        self.assertEqual(1, len(gate["calls_over_ceiling"]))
        self.assertEqual("PASS", release_join.suite_gate(self.joins(99, 0, outlier_ms=15000))["status"])

    def test_a_call_that_never_joined_counts_as_over_the_ceiling(self):
        gate = release_join.suite_gate([*self.joins(99), call(None)])
        self.assertEqual("FAIL", gate["status"])
        self.assertEqual(1, len(gate["calls_never_joined"]))

    def test_fewer_than_the_minimum_calls_is_unmeasured_unless_a_measured_call_already_failed_it(self):
        few = release_join.suite_gate(self.joins(19))
        self.assertEqual("UNMEASURED", few["status"])
        self.assertIn("minimum of 20", few["reason"])
        self.assertEqual("PASS", release_join.suite_gate(self.joins(20))["status"])
        self.assertEqual("FAIL", release_join.suite_gate([call(20000)])["status"])
        self.assertEqual("UNMEASURED", release_join.suite_gate([])["status"])

    def test_unmeasured_non_idle_and_benchmark_launch_calls_are_listed_and_not_counted(self):
        results = [*self.joins(20), call(None, status="UNMEASURED"), call(30000, idle=False), call(30000, user=False)]
        gate = release_join.suite_gate(results)
        self.assertEqual(("PASS", 20), (gate["status"], gate["calls_counted"]))
        self.assertEqual({"unmeasured", "not_idle", "not_user_launch"}, set(gate["not_counted"]))
        self.assertEqual("FAIL", release_join.suite_gate(results, require_idle=False)["status"])

    def test_the_suite_report_carries_the_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "out"
            output.mkdir()
            result = {**call(4000), "families": {}, "status": "PASS", "thresholds_version": "device-run-v3"}
            payload = suite.report(output, [result])
        self.assertEqual("UNMEASURED", payload["release_join_gate"]["status"])
        self.assertEqual(1, payload["release_join_gate"]["calls_counted"])


class UserLaunchPeerGatesTest(JoinCases, unittest.TestCase):
    def gates(self, score, name):
        return {item["device"]: item["gates"][name] for item in score["devices"]}

    def test_a_person_launch_authenticates_the_peer_by_the_reveals_confirmed_identity_and_the_link_description(self):
        _, manifest, score = self.run_case(self.case())
        self.assertNotIn("runner_error", manifest)
        for name in ("authenticated_peer", "selected_transport"):
            self.assertEqual({"PASS"}, {gate["status"] for gate in self.gates(score, name).values()}, name)
        evidence = self.gates(score, "authenticated_peer")["a"]["evidence"]
        self.assertEqual(1, evidence["pseudonymous_link"])
        self.assertEqual([self.devices["b"]["node_prefix"]], evidence["identity_confirmed"])
        self.assertEqual(1, evidence["discovery_bound_to_peer_rotid_own"])

    def test_a_link_to_a_stranger_without_the_reveals_confirmed_identity_proves_no_peer(self):
        _, _, score = self.run_case(self.case(), reveal_missing={"a", "b"})
        for gate in self.gates(score, "authenticated_peer").values():
            self.assertEqual("UNMEASURED", gate["status"])
            self.assertIn("unverified stranger", gate["reason"])
        # The link description alone still measures the transport.
        self.assertEqual({"PASS"}, {gate["status"] for gate in self.gates(score, "selected_transport").values()})

    def test_a_revealed_identity_that_is_not_the_requested_peer_fails(self):
        def tamper(phones):
            phones.join_stages = True
            original = phones.log

            def log(phone, message, tag="BluetoothVideo"):
                if phone == "a" and message.startswith("The other phone's identity is confirmed"):
                    message = "The other phone's identity is confirmed: ffffffffffff"
                original(phone, message, tag)
            phones.log = log
        _, _, score = self.execute(self.case(), tamper)
        self.assertEqual("FAIL", self.gates(score, "authenticated_peer")["a"]["status"])
        self.assertEqual("PASS", self.gates(score, "authenticated_peer")["b"]["status"])

    def test_the_wrong_transport_still_fails_under_a_pseudonymous_link(self):
        _, _, score = self.execute(self.case(transport="gatt"))
        self.assertEqual({"FAIL"}, {gate["status"] for gate in self.gates(score, "selected_transport").values()})

    def test_a_benchmark_launch_still_names_the_peer_on_the_link_line(self):
        _, _, score = self.run_case(self.case(tunables={}))
        self.assertEqual({"PASS"}, {gate["status"] for gate in self.gates(score, "authenticated_peer").values()})
        self.assertEqual(0, self.gates(score, "authenticated_peer")["a"]["evidence"]["pseudonymous_link"])


def stage(stage_name, role, since, at, detail="", call_id="1cfa325c-174e-4f49-b3d6-8dc04bdf5067", with_id=True):
    text = (f"BENCHMARK call_join_stage: stage={stage_name} | role={role} | sinceStartMs={since} | atMs={at}" +
            (f" | callId={call_id}" if with_id else "") + (f" | {detail}" if detail else ""))
    return {"message": text, "time": f"11-10 20:21:{at / 1000 % 60:06.3f}", "at": at / 1000}


def plain(text, at):
    return {"message": text, "time": f"11-10 20:21:{at % 60:06.3f}", "at": at}


class TapAndSubStageTest(unittest.TestCase):
    """JT2 P0: the app's tap stage (negative sinceStartMs), the camera sub-stages and the stage-1 rows, read from the
    shapes the app logs (u7bd-20261110u2 A15s caller: dial, link_up, authenticated, ringing, GATT video MTU)."""

    CALLER = [
        stage("tap", "caller", -412, 236896108, "tapToDialMs=412 | source=user", with_id=False),
        stage("dial", "caller", 0, 236896520),
        stage("link_up", "caller", 1068, 236897588, "link=BLE GATT (PHY pending)"),
        plain("BENCHMARK ll_gate: link=gatt-video | role=central | event=link_lost | status=133 | class=setup_or_scheduling", 236897.7),
        plain("GattVideoSetup: attempt=680f elapsedMs=725 stage=finish last=connection_callback success=false "
              "failure=GATT video receiver disconnected (status 133)", 236897.8),
        plain("GATT video MTU 517", 236898.3),
        stage("authenticated", "caller", 2200, 236898720),
        stage("ringing", "caller", 2400, 236898920),
    ]

    @staticmethod
    def rows(items):
        return [dict(item, time=item["time"]) for item in items]

    def caller(self):
        rows = self.rows(self.CALLER)
        return release_join.stage_events(rows), rows

    def test_a_negative_since_start_on_the_tap_stage_parses_and_the_dial_keeps_its_meaning(self):
        events, _ = self.caller()
        tap = next(item for item in events if item["stage"] == "tap")
        self.assertEqual((-412, 236896108, None, "user"), (tap["since_ms"], tap["at_ms"], tap["call_id"], tap["detail"]["source"]))
        self.assertEqual("412", tap["detail"]["tapToDialMs"])
        first, problem = release_join.caller_stage(events, None)
        self.assertIsNone(problem)
        self.assertEqual(2400, first["dial_to_ringing_ms"])  # the tap does not move the gate's stage 1

    def test_tap_to_invite_and_tap_to_ringing_are_diagnostics_on_the_callers_clock(self):
        events, rows = self.caller()
        first, _ = release_join.caller_stage(events, None, rows)
        self.assertEqual(412, first["tap_to_invite_ms"])        # dial atMs - tap atMs
        self.assertEqual(2812, first["tap_to_ringing_ms"])       # ringing atMs - tap atMs
        self.assertEqual("user", first["tap_source"])
        # The join is still dial->ringing + accept->picture: no tap value enters it.
        receiver = release_join.stage_events([stage("accept", "callee", 10, 5010, "by=local"),
                                              stage("first_frame_shown", "callee", 900, 5900, "black=false | meanLuma=61.2")])
        direction = release_join.direction_record("a", "b", events, receiver, None, caller_rows=rows)
        self.assertEqual(2400 + 890, direction["join_ms"])
        self.assertEqual((412, 2812), (direction["tap_to_invite_ms"], direction["tap_to_ringing_ms"]))

    def test_a_build_without_the_tap_stage_leaves_the_diagnostics_empty_not_failed(self):
        events = release_join.stage_events([row for row in self.CALLER if "stage=tap" not in row["message"]])
        first, problem = release_join.caller_stage(events, None)
        self.assertIsNone(problem)
        self.assertEqual((None, None, None), (first["tap_to_invite_ms"], first["tap_to_ringing_ms"], first["tap_source"]))

    def test_a_tap_after_the_dial_is_not_that_dials_tap(self):
        late = [stage("dial", "caller", 0, 1000), stage("ringing", "caller", 500, 1500),
                stage("tap", "caller", -5, 2000, "tapToDialMs=5 | source=debug", with_id=False)]
        first, _ = release_join.caller_stage(release_join.stage_events(late), None)
        self.assertIsNone(first["tap_to_invite_ms"])

    def test_the_stage_1_rows_split_connect_mtu_auth_ring_and_count_status_133(self):
        events, rows = self.caller()
        first, _ = release_join.caller_stage(events, None, rows)
        sub = first["stage1_substages"]
        self.assertEqual(1068, sub["dial_to_link_up_ms"])
        self.assertEqual(1132, sub["link_up_to_authenticated_ms"])
        self.assertEqual(200, sub["authenticated_to_ringing_ms"])
        self.assertEqual("BLE GATT (PHY pending)", sub["link"])
        self.assertEqual(517, sub["mtu"])
        self.assertEqual(1, sub["status_133"])  # the ll_gate line; the setup finish line is the same failure
        self.assertEqual(2400, sub["dial_to_link_up_ms"] + sub["link_up_to_authenticated_ms"] + sub["authenticated_to_ringing_ms"])

    def test_without_log_rows_the_stage_rows_keep_their_stage_values_and_leave_mtu_and_133_unmeasured(self):
        events, _ = self.caller()
        sub = release_join.caller_stage(events, None)[0]["stage1_substages"]
        self.assertEqual(1068, sub["dial_to_link_up_ms"])
        self.assertIsNone(sub["mtu"])
        self.assertIsNone(sub["status_133"])

    def test_the_setup_finish_lines_count_when_the_build_logs_no_ll_gate_line(self):
        rows = [row for row in self.rows(self.CALLER) if "ll_gate" not in row["message"]]
        first, _ = release_join.caller_stage(release_join.stage_events(rows), None, rows)
        self.assertEqual(1, first["stage1_substages"]["status_133"])

    def test_the_senders_camera_sub_stages_and_first_capture_are_listed(self):
        sender = release_join.stage_events([
            stage("camera_open_requested", "callee", 300, 9300, "surface=ready | camera=0"),
            stage("camera_opened", "callee", 520, 9520, "openMs=220 | joined=true"),
            stage("camera_session_configured", "callee", 900, 9900, "createToConfiguredMs=380 | targets=2 | sinceOpenRequestedMs=600"),
            stage("camera_first_frame", "callee", 1500, 10500,
                  "path=native | sensorLagMs=48 | aeState=searching | exposureUs=33333 | iso=800")])
        camera = release_join.sender_camera(sender)
        self.assertEqual(520, camera["camera_opened"]["since_start_ms"])
        self.assertEqual("ready", camera["camera_open_requested"]["surface"])
        self.assertEqual("searching", camera["camera_first_frame"]["aeState"])
        self.assertEqual("800", camera["camera_first_frame"]["iso"])
        self.assertIsNone(release_join.sender_camera([]))


class SceneDarkTest(unittest.TestCase):
    ACCEPT = stage("accept", "callee", 10, 1010, "by=local")

    def receiver(self, *rows):
        return release_join.stage_events([self.ACCEPT, *rows])

    def picture(self, *rows):
        return release_join.picture_stage(self.receiver(*rows), None)

    def test_a_nonblack_stage_that_was_never_reached_is_scene_dark_with_no_join_value(self):
        picture, problem = self.picture(
            stage("first_frame_shown", "callee", 900, 1900, "black=true | meanLuma=0.3"),
            stage("first_nonblack_shown", "callee", 15010, 16010, "black=true | meanLuma=0.4 | reached=false | reason=bound"))
        self.assertIsNone(picture)
        self.assertEqual("UNMEASURED", problem[0])
        self.assertTrue(problem[1].startswith("SCENE_DARK"), problem[1])
        self.assertIn("mean luma 0.3", problem[1])

    def test_every_shown_picture_black_with_no_later_real_one_is_scene_dark(self):
        _, problem = self.picture(stage("first_frame_shown", "callee", 900, 1900, "black=true | meanLuma=1.2"))
        self.assertEqual("UNMEASURED", problem[0])
        self.assertTrue(problem[1].startswith("SCENE_DARK"))

    def test_a_real_picture_only_after_the_apps_whole_dark_bound_is_scene_dark_not_a_15_s_join(self):
        # The u6rm call: black from the first picture, a real picture 15.4 s after Accept.
        picture, problem = self.picture(
            stage("first_frame_shown", "callee", 900, 1900, "black=true | meanLuma=0.4"),
            stage("first_frame_shown", "callee", 15400, 16410, "black=false | meanLuma=44.0"))
        self.assertIsNone(picture)
        self.assertTrue(problem[1].startswith("SCENE_DARK"))
        self.assertIn("15400", problem[1])

    def test_a_black_start_that_clears_inside_the_bound_stays_a_join_value(self):
        picture, problem = self.picture(
            stage("first_frame_shown", "callee", 900, 1900, "black=true | meanLuma=0.4"),
            stage("first_frame_shown", "callee", 4000, 5010, "black=false | meanLuma=44.0"))
        self.assertIsNone(problem)
        self.assertEqual(4000, picture["accept_to_picture_ms"])

    def test_an_unreadable_picture_is_still_plain_unmeasured(self):
        _, problem = self.picture(stage("first_frame_shown", "callee", 900, 1900, "black=unknown"))
        self.assertEqual("UNMEASURED", problem[0])
        self.assertFalse(problem[1].startswith("SCENE_DARK"))

    def test_the_direction_call_and_suite_carry_the_code_and_never_a_join_value(self):
        caller = release_join.stage_events([stage("dial", "caller", 0, 100), stage("ringing", "caller", 800, 900)])
        dark = release_join.direction_record("a", "b", caller, self.receiver(
            stage("first_frame_shown", "callee", 900, 1900, "black=true | meanLuma=0.3"),
            stage("first_nonblack_shown", "callee", 15010, 16010, "black=true | reached=false | reason=bound")), None)
        lit = release_join.direction_record("b", "a", caller, self.receiver(
            stage("first_frame_shown", "callee", 900, 1900, "black=false | meanLuma=60")), None)
        self.assertEqual(("UNMEASURED", None, "SCENE_DARK"), (dark["status"], dark["join_ms"], dark["reason_code"]))
        record = release_join.call_record([dark, lit], {"actual_s": 12.0}, True)
        self.assertEqual(("UNMEASURED", None, "SCENE_DARK"), (record["status"], record["join_ms"], record["reason_code"]))
        self.assertTrue(record["reason"].startswith("SCENE_DARK"))
        verdict = release_join.suite_gate([{"case_id": "c", "run_id": "r", "release_join": record}], minimum=1)
        self.assertEqual(0, verdict["calls_counted"])  # not a join value, not a failure
        self.assertEqual("SCENE_DARK", verdict["not_counted"]["unmeasured"][0]["reason_code"])

    def test_a_failed_join_still_outranks_a_dark_scene(self):
        failed = {"sender": "a", "receiver": "b", "status": "FAILED_JOIN", "join_ms": None, "reason": "never rang"}
        dark = {"sender": "b", "receiver": "a", "status": "UNMEASURED", "join_ms": None, "reason": "SCENE_DARK: x",
                "reason_code": "SCENE_DARK"}
        self.assertEqual("FAILED_JOIN", release_join.call_record([failed, dark], None, True)["status"])


if __name__ == "__main__":
    unittest.main()
