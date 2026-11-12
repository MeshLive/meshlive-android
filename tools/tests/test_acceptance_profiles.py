import importlib.util
from pathlib import Path
import sys
import unittest

TOOLS = Path(__file__).parents[1]
sys.path.insert(0, str(TOOLS))


def load(name):
    spec = importlib.util.spec_from_file_location(name, TOOLS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


scorer = load("score_device_run")
profiles = load("acceptance_profiles")
V4 = {"version": "device-run-v4-user"}


def line(seconds, message, pid=123):
    return f"10-02 19:{int(seconds // 60) + 12:02d}:{seconds % 60:06.3f} {pid} {pid} I BluetoothVideo: {message}\n"


def surface(frames, freezes=0, duration=0.0, ratio=0.0, p95=66.7, p99=66.7, maximum=200.0, label="Receiver"):
    return (f"{label} visible continuity: {frames} frames | {freezes} freeze event(s), {duration:.1f} ms, {ratio:.2f}% | "
            f"threshold 250.0 ms | surface cadence p50/p95/p99/max 66.7/{p95}/{p99}/{maximum} ms")


def steady(maximum=200.0, label="Receiver", seconds=(0, 10, 20, 30), fps=15, **final):
    """Cumulative reports every 10 s at a steady rate; the last report may carry freeze counters."""
    text = ""
    for index, second in enumerate(seconds):
        frames = 1 if second == 0 else second * fps
        last = index == len(seconds) - 1
        text += line(second, surface(frames, maximum=maximum, label=label, **(final if last else {})))
    return text


def score(text, thresholds=None, manifest=None, **spec):
    return scorer.score_log(text, {"device": "test", "expected_fps": 15, **spec}, manifest, thresholds)


class ProfileVersionTest(unittest.TestCase):
    def test_v3_stays_the_default_and_is_unchanged(self):
        self.assertEqual("device-run-v3", scorer.DEFAULT_THRESHOLDS["version"])
        self.assertEqual(0.95, scorer.DEFAULT_THRESHOLDS["visible_fps_fraction"])
        self.assertEqual(90, scorer.DEFAULT_THRESHOLDS["min_vmaf"])
        result = score(steady())
        self.assertEqual("device-run-v3", result["thresholds_version"])
        self.assertIsNone(result["acceptance"])
        self.assertNotIn("longest_stall_ms", result["gates"])
        self.assertNotIn("DIAGNOSTIC", {gate["status"] for gate in result["gates"].values()})

    def test_v4_overlay_adds_user_targets_without_dropping_v3_keys(self):
        limits = scorer.limits_for(V4)
        self.assertEqual("device-run-v4-user", limits["version"])
        self.assertEqual(500, limits["max_stall_ms_call"])
        self.assertEqual(1000, limits["max_stall_ms_stream"])
        self.assertEqual(1.0, limits["max_frozen_time_share_percent"])
        self.assertEqual(3000, limits["max_startup_ms"])
        self.assertEqual(scorer.DEFAULT_THRESHOLDS["version"], scorer.limits_for(None)["version"])
        self.assertEqual("custom", scorer.limits_for({"version": "custom"})["version"])
        self.assertNotIn("max_stall_ms_call", scorer.limits_for({"version": "custom"}))

    def test_diagnostic_rows_never_decide_but_keep_their_measured_verdict(self):
        failing = {"status": "FAIL", "value": 1, "limit": 0}
        diagnostic = scorer.as_diagnostic(failing)
        self.assertEqual("DIAGNOSTIC", diagnostic["status"])
        self.assertEqual("FAIL", diagnostic["measured_status"])
        self.assertEqual("PASS", scorer.aggregate_status([{"status": "PASS"}, diagnostic]))
        self.assertEqual("FAIL", scorer.aggregate_status([{"status": "FAIL"}, diagnostic]))
        self.assertEqual("UNMEASURED", scorer.aggregate_status([diagnostic]))

    def test_unknown_link_class_is_healthy_and_impairment_is_impaired(self):
        self.assertEqual(("healthy", "default"), profiles.link_class({}))
        self.assertEqual(("impaired", "media_impairment"), profiles.link_class({"scenario": {"media_impairment": {"path": "x"}}}))
        self.assertEqual(("impaired", "media_impairment"), profiles.link_class({"scenario": {"media_impairments": {"a": {}}}}))
        self.assertEqual(("impaired", "declared"), profiles.link_class({"acceptance": {"link_class": "impaired"}}))
        self.assertEqual(("healthy", "declared"), profiles.link_class(
            {"acceptance": {"link_class": "healthy"}, "scenario": {"media_impairment": {"path": "x"}}}))

    def test_family_lists_keep_v3_and_add_unmeasured_user_families(self):
        self.assertEqual(profiles.FAMILIES_V3, profiles.families_for(None))
        self.assertEqual(profiles.FAMILIES_V3, profiles.families_for("device-run-v3"))
        self.assertEqual(("media_age", "task_success", "speech_comprehension"), profiles.families_for(profiles.V4)[-3:])
        self.assertNotIn("picture", profiles.sweep_families_for(profiles.V4))
        self.assertIn("picture", profiles.sweep_families_for(profiles.V3))
        with self.assertRaises(ValueError):
            profiles.families_for("device-run-v9")


class LongestStallTest(unittest.TestCase):
    def test_stream_limit_is_one_second_and_call_limit_is_half_a_second(self):
        for maximum, stream, call in ((400.0, "PASS", "PASS"), (900.0, "PASS", "FAIL"), (1100.0, "FAIL", "FAIL")):
            with self.subTest(maximum=maximum):
                self.assertEqual(stream, score(steady(maximum), V4)["gates"]["longest_stall_ms"]["status"])
                called = score(steady(maximum, label="Private call"), V4)["gates"]["longest_stall_ms"]
                self.assertEqual(call, called["status"])
                self.assertEqual(500, called["limit"])

    def test_pass_requires_the_rolling_window_to_have_seen_every_gap(self):
        text = (line(0, surface(1)) + line(20, surface(1 + 300)))
        stall = score(text, V4)["gates"]["longest_stall_ms"]
        self.assertEqual("UNMEASURED", stall["status"])
        self.assertFalse(stall["rolling_window_covered"])
        self.assertEqual("FAIL", score(line(0, surface(1)) + line(20, surface(301, maximum=2000.0)), V4)["gates"]["longest_stall_ms"]["status"])

    def test_sender_has_no_stall_gate(self):
        self.assertNotIn("longest_stall_ms", score(steady(), V4, role="sender")["gates"])


class FrozenShareTest(unittest.TestCase):
    def test_share_is_unexpected_freeze_time_over_scored_time(self):
        small = score(steady(freezes=1, duration=300.0, ratio=1.0), V4)["gates"]["frozen_time_share_percent"]
        self.assertEqual("PASS", small["status"])
        self.assertAlmostEqual(1.0, small["value"], places=6)
        self.assertEqual(30.0, small["scored_seconds"])
        large = score(steady(freezes=1, duration=600.0, ratio=2.0), V4)["gates"]["frozen_time_share_percent"]
        self.assertEqual("FAIL", large["status"])

    def test_planned_overlap_cannot_be_allocated_so_share_is_unmeasured(self):
        text = (line(0, surface(1)) + line(5, "Stream paused by broadcaster.") + line(8, "Broadcaster resumed the stream.") +
                line(10, surface(100, freezes=1, duration=2000.0, ratio=7.0)))
        self.assertEqual("UNMEASURED", score(text, V4)["gates"]["frozen_time_share_percent"]["status"])


class LinkClassTest(unittest.TestCase):
    freezing = staticmethod(lambda: steady(freezes=2, duration=200.0, ratio=0.67))

    def test_healthy_link_keeps_zero_unexpected_freezes_decisive(self):
        result = score(self.freezing(), V4)
        self.assertEqual("healthy", result["acceptance"]["link_class"])
        self.assertEqual("FAIL", result["gates"]["unexpected_freeze_events"]["status"])
        self.assertEqual("FAIL", result["overall_status"])

    def test_impaired_link_reports_freeze_count_but_judges_stall_and_share(self):
        manifest = {"scenario": {"media_impairment": {"path": "x", "sha256": "y"}}}
        result = score(self.freezing(), V4, manifest)
        freeze = result["gates"]["unexpected_freeze_events"]
        self.assertEqual("DIAGNOSTIC", freeze["status"])
        self.assertEqual("FAIL", freeze["measured_status"])
        self.assertEqual("PASS", result["gates"]["longest_stall_ms"]["status"])
        self.assertEqual("PASS", result["gates"]["frozen_time_share_percent"]["status"])
        self.assertNotEqual("FAIL", result["overall_status"])

    def test_impaired_stall_over_the_limit_still_fails(self):
        manifest = {"acceptance": {"link_class": "impaired"}}
        result = score(steady(maximum=1500.0, freezes=1, duration=1250.0, ratio=4.0), V4, manifest)
        self.assertEqual("FAIL", result["gates"]["longest_stall_ms"]["status"])
        self.assertEqual("FAIL", result["gates"]["frozen_time_share_percent"]["status"])
        self.assertEqual("FAIL", result["overall_status"])

    def test_loss_counters_are_diagnostic_when_impaired_but_audio_drops_stay_decisive(self):
        text = steady() + line(31, "Call RX 300 kbps | 10 video/20 audio | audio drops 3 | queue 4/11, drops 5") + \
            line(32, "Call RX 300 kbps | 10 video/20 audio | audio drops 3 | queue 4/11, drops 5")
        manifest = {"acceptance": {"link_class": "impaired"}}
        result = score(text, V4, manifest)
        self.assertEqual("DIAGNOSTIC", result["gates"]["decoder_drops"]["status"])
        self.assertEqual("FAIL", result["gates"]["audio_drops"]["status"])
        healthy = score(text, V4)
        self.assertEqual("FAIL", healthy["gates"]["decoder_drops"]["status"])


class EffectiveCadenceTest(unittest.TestCase):
    def test_visible_fps_fraction_is_diagnostic_and_effective_cadence_decides(self):
        text = (line(0, "Receiving stream from aaaaaaaaaaaa over link aaaaaaaaaaaa: VP9 640x360 at 15 FPS") +
                line(1, surface(1)) + line(11, surface(151)) +
                line(12, "Receiving stream from aaaaaaaaaaaa over link aaaaaaaaaaaa: VP9 640x360 at 10 FPS") +
                line(21, surface(256)) + line(31, surface(356)))
        v3 = score(text)
        self.assertEqual("FAIL", v3["gates"]["visible_fps"]["status"])
        result = score(text, V4)
        self.assertEqual("DIAGNOSTIC", result["gates"]["visible_fps"]["status"])
        self.assertEqual("FAIL", result["gates"]["visible_fps"]["measured_status"])
        cadence = result["gates"]["effective_profile_fps"]
        self.assertEqual("PASS", cadence["status"])
        self.assertEqual(2, cadence["declared_effective_changes"])
        self.assertLess(result["gates"]["requested_profile_shortfall"]["value"], 0.95)
        self.assertGreater(result["gates"]["degraded_time_share_percent"]["value"], 0.0)
        self.assertEqual("DIAGNOSTIC", result["gates"]["requested_profile_shortfall"]["status"])

    def test_delivery_below_the_declared_effective_profile_fails(self):
        text = (line(0, "Receiving stream from aaaaaaaaaaaa over link aaaaaaaaaaaa: VP9 640x360 at 15 FPS") +
                line(1, surface(1)) + line(11, surface(101)) + line(21, surface(201)))
        self.assertEqual("FAIL", score(text, V4)["gates"]["effective_profile_fps"]["status"])

    @staticmethod
    def _run(slow_intervals, intervals=25, slow_frames=100, fps=15):
        """Cumulative reports every 10 s; the listed 1-based intervals deliver slow_frames instead of 10 * fps."""
        text, frames = line(0, surface(1)), 1
        for index in range(1, intervals + 1):
            frames += slow_frames if index in slow_intervals else 10 * fps
            text += line(index * 10, surface(frames))
        return text

    def test_cadence_judges_the_share_of_time_below_95_percent_and_keeps_the_old_rule_as_a_comparison(self):
        # One slow 10 s window out of 25 (4% of the time): passes the share rule, fails the old slowest-window rule.
        gate = score(self._run({5}), V4)["gates"]["effective_profile_fps"]
        self.assertEqual("PASS", gate["status"])
        self.assertEqual("time_share_below_fraction", gate["rule"])
        self.assertEqual(5.0, gate["limit"])
        self.assertAlmostEqual(4.0, gate["value"], places=6)
        self.assertEqual(10.0, gate["below_seconds"])
        self.assertEqual(250.0, gate["scored_seconds"])
        legacy = gate["legacy_slowest_window"]
        self.assertEqual("FAIL", legacy["status"])
        self.assertAlmostEqual(100 / 150, legacy["value"], places=6)
        self.assertEqual(0.95, legacy["limit"])
        self.assertIn("comparison only", legacy["label"])
        # Two slow windows (8% of the time) fail the share rule.
        failing = score(self._run({5, 6}), V4)["gates"]["effective_profile_fps"]
        self.assertEqual("FAIL", failing["status"])
        self.assertAlmostEqual(8.0, failing["value"], places=6)
        # The longest stall stays its own decisive gate and is echoed on the cadence row.
        self.assertIn("longest_stall_ms", failing)
        self.assertEqual("PASS", score(self._run({5}), V4)["gates"]["longest_stall_ms"]["status"])

    def test_a_steady_run_passes_both_the_share_rule_and_the_legacy_comparison(self):
        gate = score(self._run(set()), V4)["gates"]["effective_profile_fps"]
        self.assertEqual("PASS", gate["status"])
        self.assertEqual(0.0, gate["value"])
        self.assertEqual("PASS", gate["legacy_slowest_window"]["status"])

    def test_the_v3_profile_has_no_cadence_gate_and_is_unchanged(self):
        self.assertNotIn("effective_profile_fps", score(self._run({5}))["gates"])
        self.assertNotIn("effective_fps_below_share_percent", scorer.DEFAULT_THRESHOLDS)

    def test_without_a_declared_change_it_equals_the_requested_profile(self):
        gate = score(steady(), V4)["gates"]["effective_profile_fps"]
        self.assertEqual("PASS", gate["status"])
        self.assertEqual(0, gate["declared_effective_changes"])


class FidelityTest(unittest.TestCase):
    def test_low_vmaf_is_reported_but_does_not_fail_v4(self):
        manifest = {"optical_results": [{"device": "test", "aligned": True, "reference_kind": "raw_fixed_fixture",
                                         "coverage_matches": True, "vmaf": 60, "ssim": 0.8, "psnr_db": 20}]}
        v3 = score(steady(), None, manifest)
        self.assertEqual("FAIL", v3["optical_gates"]["vmaf"]["status"])
        self.assertEqual("FAIL", v3["overall_status"])
        v4 = score(steady(), V4, manifest)
        for name in ("vmaf", "ssim", "psnr_db"):
            self.assertEqual("DIAGNOSTIC", v4["optical_gates"][name]["status"])
            self.assertEqual("FAIL", v4["optical_gates"][name]["measured_status"])
        self.assertNotEqual("FAIL", v4["overall_status"])

    def test_queue_age_is_labelled_a_proxy_never_media_age(self):
        text = steady() + line(5, "Relay x TX: 300 kbps | queue 3 | backlog 450 ms")
        gates = score(text, V4)["gates"]
        self.assertEqual("DIAGNOSTIC", gates["queue_age_proxy_ms"]["status"])
        self.assertTrue(gates["queue_age_proxy_ms"]["proxy"])
        self.assertNotIn("media_age_ms", gates)


class JoinGateTest(unittest.TestCase):
    """Q-STARTUP-GATE (owner, 2026-11-06): the join gate is selection-to-first-surface; launch is only reported."""

    LAUNCH = {"selection_events": [{"device": "test", "time": "10-02 19:12:00.000", "action": "launch"}]}

    def join(self, thresholds, selected_ms=None, launch_surface_at=5.0):
        text = line(launch_surface_at, surface(1)) + line(launch_surface_at + 10, surface(150))
        if selected_ms is not None:
            text += line(launch_surface_at, f"Receiver startup: selection-to-first-surface {selected_ms} ms")
        result = score(text, thresholds, self.LAUNCH)
        launch = next(item for item in result["actions"] if item["action"] == "launch")["first_surface_ms"]
        return result, launch

    def test_slow_launch_with_fast_selection_passes_the_join_gate(self):
        result, launch = self.join(V4, selected_ms=2100.0)
        self.assertEqual(("PASS", 2100.0), (result["gates"]["selection_to_first_surface_ms"]["status"],
                                           result["gates"]["selection_to_first_surface_ms"]["value"]))
        # Launch-to-first-surface (5.0 s) is still computed and shown, but no longer decides.
        self.assertEqual(("DIAGNOSTIC", "FAIL", 5000.0), (launch["status"], launch["measured_status"], launch["value"]))
        self.assertEqual("launch_to_first_surface_ms", launch["metric"])
        self.assertEqual("PASS", result["lifecycle_status"])
        self.assertNotEqual("FAIL", result["overall_status"])

    def test_a_slow_selection_still_fails_the_join_gate(self):
        result, _ = self.join(V4, selected_ms=3200.0)
        self.assertEqual("FAIL", result["gates"]["selection_to_first_surface_ms"]["status"])
        self.assertEqual("FAIL", result["overall_status"])

    def test_no_selection_timestamp_is_unmeasured_not_a_pass(self):
        result, _ = self.join(V4, selected_ms=None)
        self.assertEqual("UNMEASURED", result["gates"]["selection_to_first_surface_ms"]["status"])
        self.assertEqual("UNMEASURED", result["overall_status"])

    def test_a_connection_timeout_is_still_a_failure_under_the_selection_basis(self):
        timeout = {"selection_events": [{"device": "test", "time": "10-02 19:12:01.000", "action": "connection_timeout"}]}
        result = score(line(2, surface(1)), V4, timeout)
        self.assertEqual("FAIL", result["actions"][0]["first_surface_ms"]["status"])
        self.assertEqual("FAIL", result["overall_status"])

    def test_v3_keeps_launch_to_first_surface_as_its_gate(self):
        _, launch = self.join(None, selected_ms=2100.0)
        self.assertEqual(("FAIL", 5000.0), (launch["status"], launch["value"]))


class RunnerSelectionTest(unittest.TestCase):
    def test_manifest_scoring_records_the_profile_and_cli_selects_it(self):
        manifest = {"run_id": "r", "logs": []}
        self.assertEqual("device-run-v3", scorer.score_manifest(manifest)["thresholds_version"])
        self.assertEqual("device-run-v4-user", scorer.score_manifest(manifest, thresholds=V4)["thresholds_version"])


if __name__ == "__main__":
    unittest.main()
