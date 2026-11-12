import re
import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location("score_device_run", Path(__file__).parents[1] / "score_device_run.py")
scorer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(scorer)


def line(seconds, message, pid=123):
    return f"10-02 19:12:{seconds:06.3f} {pid} {pid} I BluetoothVideo: {message}\n"


def surface(frames, freezes=0, duration=0, p95=66.7, p99=66.7, final=False):
    return (f"Receiver visible continuity{' FINAL' if final else ''}: {frames} frames | "
            f"{freezes} freeze event(s), {duration:.1f} ms, 0.00% | threshold 250.0 ms | "
            f"surface cadence p50/p95/p99/max 66.7/{p95}/{p99}/200.0 ms")


def score(text, **kwargs):
    return scorer.score_log(text, {"device": "test", "expected_fps": 15, **kwargs})


class DeviceRunScoreTest(unittest.TestCase):
    def test_requested_decoder_substitution_fails_the_case(self):
        same = line(0, "BENCHMARK decoder requested=c2.android.vp9.decoder effective=c2.android.vp9.decoder "
                       "reason=requested kind=software-protection tried=1")
        swapped = line(1, "BENCHMARK decoder requested=c2.android.vp9.decoder effective=c2.qti.vp9.decoder "
                          "reason=requested-unsupported kind=software-protection tried=1")
        free = line(2, "BENCHMARK decoder requested=none effective=c2.qti.vp9.decoder reason=fallback kind=none tried=2")
        self.assertEqual("PASS", score(same + free)["gates"]["requested_decoder_mismatches"]["status"])
        result = score(same + swapped)["gates"]["requested_decoder_mismatches"]
        self.assertEqual(("FAIL", 1), (result["status"], result["value"]))
        self.assertNotIn("requested_decoder_mismatches", score(free)["gates"])

    def test_link_phy_fallback_is_explicit_and_fails_only_a_run_requested_phy(self):
        stall = line(0, "BENCHMARK link_phy requested=2M effective=1M reason=setup_stall source=adaptive "
                        "link=call node=0a0b0c0d0e0f")
        result = score(stall)
        self.assertEqual([{"time": "10-02 19:12:00.000", "requested": "2M", "effective": "1M",
                           "reason": "setup_stall", "source": "adaptive"}], result["link_phy"])
        self.assertNotIn("requested_link_phy_mismatches", result["gates"])
        forced = line(1, "BENCHMARK link_phy requested=1M effective=2M reason=stack source=run link=call node=0a")
        gate = score(stall + forced)["gates"]["requested_link_phy_mismatches"]
        self.assertEqual(("FAIL", 1), (gate["status"], gate["value"]))
        self.assertEqual([], score(line(2, "Receiver visible continuity: 1 frames"))["link_phy"])

    def test_nonfinite_or_boolean_values_cannot_earn_a_gate(self):
        for invalid in (float("inf"), float("-inf"), float("nan"), True, "90"):
            with self.subTest(value=invalid):
                self.assertEqual("UNMEASURED", scorer.gate(invalid, 35, "min")["status"])
                self.assertEqual("UNMEASURED", scorer.gate(35, invalid, "min")["status"])

    def test_authenticated_pause_is_not_random_freeze_or_low_motion_fps(self):
        text = (line(0, surface(1)) + line(1, "Stream paused by broadcaster.") +
                line(3, "Stream paused by broadcaster.") + line(4, "Broadcaster resumed the stream.") +
                line(5, surface(30, 1, 2000, p95=500)))
        result = score(text)
        self.assertEqual(result["freezes"]["observed"], 1)
        self.assertEqual(result["freezes"]["lifecycle_overlap"], 1)
        self.assertEqual(result["gates"]["unexpected_freeze_events"]["status"], "UNMEASURED")
        self.assertEqual(result["gates"]["visible_fps"]["status"], "UNMEASURED")
        self.assertEqual(result["evaluation_intervals"][0]["start"], "10-02 19:12:01.000")

    def test_pause_does_not_erase_a_freeze_observed_before_it(self):
        text = (line(0, surface(1)) + line(2, surface(30, 1, 300)) +
                line(3, "Stream paused by broadcaster.") + line(4, "Broadcaster resumed the stream.") +
                line(5, surface(40, 2, 2300)))
        result = score(text)
        self.assertEqual(result["freezes"]["observed"], 2)
        self.assertEqual(result["freezes"]["unexpected"], 1)
        self.assertEqual(result["gates"]["unexpected_freeze_events"]["status"], "FAIL")

    def test_parent_removal_measures_recovery_without_resetting_surface_counter(self):
        manifest = {"topology_changes": [{"device": "test", "time": "10-02 19:12:01.000", "removed_ancestor": "middle"}]}
        text = (line(0, surface(1)) + line(2, "Connection lost. Finding another route...") +
                line(4, "Receiver startup: selection-to-first-surface 2200.0 ms") + line(5, surface(40, 1, 2000)))
        result = scorer.score_log(text, {"device": "test", "expected_fps": 15}, manifest)
        self.assertTrue(result["events"][0]["planned"])
        self.assertEqual(result["actions"][0]["first_surface_ms"]["value"], 3000)
        self.assertEqual(result["actions"][0]["first_surface_ms"]["status"], "PASS")
        self.assertEqual(result["freezes"]["lifecycle_overlap"], 1)

    def test_no_surface_after_planned_parent_loss_is_still_failed_recovery(self):
        manifest = {"topology_changes": [{"device": "test", "time": "10-02 19:12:01.000", "removed_ancestor": "middle"}]}
        result = scorer.score_log(line(0, surface(1)) + line(8, "Finding another route..."),
                                  {"device": "test", "expected_fps": 15}, manifest)
        self.assertEqual(result["actions"][0]["first_surface_ms"]["status"], "FAIL")
        self.assertEqual(result["overall_status"], "FAIL")

    def test_killing_requested_middle_after_direct_fallback_does_not_exercise_ancestor_recovery(self):
        manifest = {"scenario": {"source": "source", "receivers": [{"phone": "test", "parent": "middle"}]},
                    "devices": {"source": {"node_prefix": "aaaaaaaaaaaa"}, "middle": {"node_prefix": "bbbbbbbbbbbb"}},
                    "topology_changes": [{"device": "test", "time": "10-02 19:12:04.000", "removed_ancestor": "middle"}]}
        text = (line(0, "Receiving stream from bbbbbbbbbbbb over link bbbbbbbbbbbb: VP9 640x360 at 15 FPS") +
                line(1, surface(1)) +
                line(2, "Receiving stream from aaaaaaaaaaaa over link aaaaaaaaaaaa: VP9 640x360 at 15 FPS") +
                line(3, surface(30)) + line(8, surface(80, freezes=1, duration=300)))
        result = scorer.score_log(text, {"device": "test", "expected_fps": 15}, manifest)
        self.assertEqual(result["actions"][0]["first_surface_ms"]["status"], "UNMEASURED")
        self.assertFalse(result["actions"][0]["exercised"])
        self.assertEqual(result["evaluation_intervals"], [])
        self.assertEqual(result["gates"]["unexpected_freeze_events"]["status"], "FAIL")
        self.assertEqual(result["gates"]["requested_parent_identity"]["status"], "FAIL")

    def test_actual_middle_loss_after_alternate_route_still_requires_recovery_deadline(self):
        manifest = {"scenario": {"source": "source", "receivers": [{"phone": "test", "parent": "middle"}]},
                    "devices": {"source": {"node_prefix": "aaaaaaaaaaaa"}, "middle": {"node_prefix": "bbbbbbbbbbbb"}},
                    "topology_changes": [{"device": "test", "time": "10-02 19:12:04.000", "removed_ancestor": "middle"}]}
        text = (line(0, "Receiving stream from aaaaaaaaaaaa over link aaaaaaaaaaaa: VP9 640x360 at 15 FPS") +
                line(2, "Receiving stream from bbbbbbbbbbbb over link bbbbbbbbbbbb: VP9 640x360 at 15 FPS") +
                line(3, surface(1)) + line(10, "Connection lost. Finding another route..."))
        result = scorer.score_log(text, {"device": "test", "expected_fps": 15}, manifest)
        self.assertEqual(result["actions"][0]["first_surface_ms"]["status"], "FAIL")

    def test_relay_overflow_before_first_periodic_report_is_not_missing_loss(self):
        result = score(line(4, "Withdrawing congested downstream route: queue 32/32, drops 62"))
        self.assertEqual(result["drops"]["forwarding_drops"]["observed"], 62)
        self.assertEqual(result["gates"]["forwarding_drops"]["status"], "FAIL")
        self.assertEqual(result["gates"]["unexpected_events"]["status"], "FAIL")

    def test_sender_loss_after_last_periodic_report_is_retained_per_child(self):
        template = "Nearby device [{peer}]: End-to-end encrypted BLE GATT TX: 500 kbps | queue 0/33, 0 KB, 0 ms | drops {drops} | thermal 0"
        text = (line(0, template.format(peer="aaaa", drops=26)) +
                line(1, "Nearby device [aaaa]: Bluetooth reached its latency limit; keeping live audio and waiting for a video keyframe (39 packets dropped).") +
                line(2, "Nearby device [aaaa]: Bluetooth reached its latency limit; keeping live audio and waiting for a video keyframe (60 packets dropped).") +
                line(3, template.format(peer="bbbb", drops=0)))
        result = score(text, role="sender")
        self.assertEqual(result["drops"]["sender_drops"]["observed"], 60)
        self.assertEqual(result["gates"]["sender_drops"]["value"], 60)

    def test_sender_keyframe_recovery_notice_is_cumulative_not_additional_loss(self):
        text = (line(0, "Bluetooth is saturated; waiting for a keyframe (25 packets dropped).") +
                line(1, "Bluetooth caught up at a keyframe after dropping 27 packets.") +
                line(2, "End-to-end encrypted Classic RFCOMM TX: 500 kbps | queue 0/33, 0 KB, 0 ms | drops 27 | thermal 0"))
        self.assertEqual(score(text, role="sender")["drops"]["sender_drops"]["observed"], 27)

    def test_native_camera_input_loss_is_distinct_from_zero_transport_drops_and_not_double_counted(self):
        text = (line(0, "Native encoder dropped 1 camera frame(s)") +
                line(1, "Encoder output: 14.2 FPS, 475 kbps, native copy p95 3.2 ms, encode p95 75.3 ms, capture drops 2") +
                line(2, "Encoder output: 14.6 FPS, 484 kbps, capture drops 2") +
                line(3, "End-to-end encrypted BLE GATT TX: 500 kbps | queue 0/33, 0 KB, 0 ms | drops 0 | thermal 0"))
        result = scorer.score_log(text, {"device": "test", "role": "sender", "expected_fps": 15},
                                  {"source_kind": "live_camera"})
        self.assertEqual(result["drops"]["camera_input_drops"]["observed"], 2)
        self.assertEqual(result["gates"]["camera_input_drops"]["status"], "FAIL")
        self.assertEqual(result["gates"]["sender_drops"]["value"], 0)

    def test_raw_input_loss_resets_per_initialized_encoder_and_never_proves_camera_capture(self):
        text = (line(0, "Encoder statistics enabled for c2.exynos.vp9.encoder") +
                line(1, "Encoder output: 30.0 FPS, 1100 kbps, raw input drops 1") +
                line(2, "Encoder output: 30.0 FPS, 1100 kbps, raw input drops 1") +
                line(3, "Encoder statistics unavailable for libaom-native") +
                line(4, "Encoder output: 30.0 FPS, 1100 kbps, capture drops 0, raw input drops 1"))
        result = scorer.score_log(text, {"device": "test", "role": "sender", "expected_fps": 30},
                                  {"source_kind": "raw_fixture_live_encoder"})
        self.assertEqual(result["drops"]["raw_input_drops"]["observed"], 2)
        self.assertEqual(result["gates"]["raw_input_drops"]["status"], "FAIL")
        self.assertNotIn("camera_input_drops", result["gates"])

    def test_absent_input_counters_do_not_invent_zero_loss_from_unavailable_camera_timing(self):
        text = line(0, "Encoder output: 30.0 FPS, 1100 kbps, capture-to-input p50/p95 0.0/0.0 ms (0 samples)")
        for kind, key in (("live_camera", "camera_input_drops"), ("raw_fixture_live_encoder", "raw_input_drops")):
            result = scorer.score_log(text, {"device": "test", "role": "sender", "expected_fps": 30}, {"source_kind": kind})
            self.assertEqual(result["gates"][key]["status"], "UNMEASURED")
        replay = scorer.score_log(text, {"device": "test", "role": "sender", "expected_fps": 30},
                                  {"source_kind": "preencoded_video_only"})
        self.assertNotIn("camera_input_drops", replay["gates"])
        self.assertNotIn("raw_input_drops", replay["gates"])

    def test_static_camera_undershoot_is_not_requested_high_rate_coverage(self):
        result = score(line(5, "Encoder output: 15.0 FPS, 97 kbps"),
                       role="sender", expected_bitrate_kbps=1000)
        self.assertEqual(result["offered_load"]["status"], "UNMEASURED")
        self.assertEqual(result["offered_load"]["median_kbps"], 97)
        self.assertEqual(result["offered_load"]["target_fraction"], 0.097)

    def test_requested_high_rate_has_measured_load_only_when_actually_offered(self):
        result = score(line(5, "Encoder output: 15.0 FPS, 1001 kbps"),
                       role="sender", expected_bitrate_kbps=1000)
        self.assertEqual(result["offered_load"]["status"], "PASS")
        self.assertEqual(result["offered_load"]["median_kbps"], 1001)

    def test_planned_pause_encoder_window_does_not_prove_high_rate_load(self):
        manifest = {"planned_intervals": [{"device": "test", "start": "10-02 19:12:01.000",
                                          "end": "10-02 19:12:03.000", "action": "pause"}]}
        result = scorer.score_log(line(5, "Encoder output: 15.0 FPS, 1001 kbps"),
                                  {"device": "test", "role": "sender", "expected_fps": 15,
                                   "expected_bitrate_kbps": 1000}, manifest)
        self.assertEqual(result["offered_load"]["status"], "UNMEASURED")

    def test_direct_fallback_does_not_pass_requested_receiver_node_route(self):
        manifest = {"scenario": {"receivers": [{"phone": "test", "parent": "middle"}]},
                    "devices": {"middle": {"node_prefix": "bbbbbbbbbbbb"}}}
        text = (line(0, "Receiving end-to-end encrypted stream from aaaaaaaaaaaa over link bbbbbbbbbbbb: VP9 640x360 at 15 FPS") +
                line(5, "Receiving end-to-end encrypted stream from aaaaaaaaaaaa over link aaaaaaaaaaaa: VP9 640x360 at 15 FPS"))
        result = scorer.score_log(text, {"device": "test", "expected_fps": 15}, manifest)
        self.assertEqual(result["gates"]["requested_parent_identity"]["status"], "FAIL")
        self.assertEqual(len(result["route_connections"]), 2)

    def test_planned_parent_removal_allows_fallback_without_claiming_chain_coverage(self):
        manifest = {"scenario": {"receivers": [{"phone": "test", "parent": "middle"}]},
                    "devices": {"middle": {"node_prefix": "bbbbbbbbbbbb"}},
                    "topology_changes": [{"device": "test", "action": "planned_parent_change", "time": "10-02 19:12:03.000"}]}
        text = (line(0, "Receiving end-to-end encrypted stream from aaaaaaaaaaaa over link bbbbbbbbbbbb: VP9 640x360 at 15 FPS") +
                line(5, "Receiving end-to-end encrypted stream from aaaaaaaaaaaa over link aaaaaaaaaaaa: VP9 640x360 at 15 FPS"))
        result = scorer.score_log(text, {"device": "test", "expected_fps": 15}, manifest)
        self.assertEqual(result["gates"]["requested_parent_identity"]["status"], "PASS")
        self.assertTrue(result["route_connections"][1]["after_planned_parent_change"])

    def test_authenticated_fallback_withdrawn_before_receiving_status_still_fails_chain(self):
        manifest = {"scenario": {"receivers": [{"phone": "test", "parent": "middle"}]},
                    "devices": {"middle": {"node_prefix": "bbbbbbbbbbbb"}}}
        text = (line(0, "Authenticated stream identity: original=aaaaaaaaaaaa | parent=bbbbbbbbbbbb | stream=mesh-123 | forwarded=true") +
                line(1, "Receiving stream from bbbbbbbbbbbb over link bbbbbbbbbbbb: VP9 640x360 at 15 FPS") +
                line(5, "Authenticated stream identity: original=aaaaaaaaaaaa | parent=aaaaaaaaaaaa | stream=direct | forwarded=false") +
                line(6, "Parent connection withdrawn."))
        result = scorer.score_log(text, {"device": "test", "expected_fps": 15}, manifest)
        self.assertEqual(result["gates"]["requested_parent_identity"]["status"], "FAIL")
        self.assertEqual(result["gates"]["requested_parent_identity"]["value"], 1)

    def test_only_fallback_after_planned_parent_change_is_excluded(self):
        manifest = {"scenario": {"receivers": [{"phone": "test", "parent": "middle"}]},
                    "devices": {"middle": {"node_prefix": "bbbbbbbbbbbb"}},
                    "topology_changes": [{"device": "test", "time": "10-02 19:12:03.000", "removed_ancestor": "middle"}]}
        identity = "Authenticated stream identity: original=aaaaaaaaaaaa | parent={} | stream=direct | forwarded=false"
        text = line(0, identity.format("bbbbbbbbbbbb")) + line(5, identity.format("aaaaaaaaaaaa"))
        result = scorer.score_log(text, {"device": "test", "expected_fps": 15}, manifest)
        self.assertEqual(result["gates"]["requested_parent_identity"]["status"], "PASS")
        early_fallback = scorer.score_log(text + line(2, identity.format("aaaaaaaaaaaa")),
                                         {"device": "test", "expected_fps": 15}, manifest)
        self.assertEqual(early_fallback["gates"]["requested_parent_identity"]["status"], "FAIL")
        self.assertEqual(early_fallback["gates"]["requested_parent_identity"]["value"], 1)

    def test_route_without_parent_identity_export_stays_unmeasured(self):
        manifest = {"scenario": {"receivers": [{"phone": "test", "parent": "middle"}]}}
        result = scorer.score_log(line(0, "Receiving stream from aaaaaaaaaaaa over link bbbbbbbbbbbb: VP9 640x360 at 15 FPS"),
                                  {"device": "test", "expected_fps": 15}, manifest)
        self.assertEqual(result["gates"]["requested_parent_identity"]["status"], "UNMEASURED")

    def test_forwarded_original_source_is_measured_separately_from_its_adjacent_signer(self):
        manifest = {"scenario": {"source": "source", "receivers": [{"phone": "test", "parent": "middle"}]},
                    "devices": {"source": {"node_prefix": "aaaaaaaaaaaa"}, "middle": {"node_prefix": "bbbbbbbbbbbb"}}}
        text = (line(0, "Authenticated stream identity: original=aaaaaaaaaaaa | parent=bbbbbbbbbbbb | stream=mesh-123 | forwarded=true") +
                line(1, "Receiving stream from bbbbbbbbbbbb over link bbbbbbbbbbbb: VP9 640x360 at 15 FPS"))
        result = scorer.score_log(text, {"device": "test", "expected_fps": 15}, manifest)
        self.assertEqual(result["gates"]["requested_original_source_identity"]["status"], "PASS")
        self.assertEqual(result["gates"]["requested_parent_identity"]["status"], "PASS")
        self.assertEqual(result["authenticated_streams"][0]["original_source_prefix"], "aaaaaaaaaaaa")
        self.assertEqual(result["route_connections"][0]["authenticated_endpoint_prefix"], "bbbbbbbbbbbb")
        wrong = scorer.score_log(text.replace("original=aaaaaaaaaaaa", "original=cccccccccccc"),
                                 {"device": "test", "expected_fps": 15}, manifest)
        self.assertEqual(wrong["gates"]["requested_original_source_identity"]["status"], "FAIL")
        self.assertEqual(wrong["gates"]["requested_parent_identity"]["status"], "PASS")

    def test_legacy_adjacent_identity_does_not_imply_original_forwarded_source_proof(self):
        manifest = {"scenario": {"source": "source", "receivers": [{"phone": "test", "parent": "middle"}]},
                    "devices": {"source": {"node_prefix": "aaaaaaaaaaaa"}, "middle": {"node_prefix": "bbbbbbbbbbbb"}}}
        result = scorer.score_log(line(0, "Receiving stream from bbbbbbbbbbbb over link bbbbbbbbbbbb: VP9 640x360 at 15 FPS"),
                                 {"device": "test", "expected_fps": 15}, manifest)
        self.assertEqual(result["gates"]["requested_original_source_identity"]["status"], "UNMEASURED")

    def test_planned_parent_change_cannot_change_authenticated_original_source(self):
        manifest = {"scenario": {"source": "source", "receivers": [{"phone": "test", "parent": "middle"}]},
                    "devices": {"source": {"node_prefix": "aaaaaaaaaaaa"}, "middle": {"node_prefix": "bbbbbbbbbbbb"}},
                    "topology_changes": [{"device": "test", "time": "10-02 19:12:03.000", "removed_ancestor": "middle"}]}
        text = (line(0, "Authenticated stream identity: original=aaaaaaaaaaaa | parent=bbbbbbbbbbbb | stream=mesh-123 | forwarded=true") +
                line(5, "Authenticated stream identity: original=cccccccccccc | parent=aaaaaaaaaaaa | stream=direct | forwarded=false"))
        result = scorer.score_log(text, {"device": "test", "expected_fps": 15}, manifest)
        self.assertTrue(result["authenticated_streams"][1]["after_planned_parent_change"])
        self.assertEqual(result["gates"]["requested_original_source_identity"]["status"], "FAIL")

    def test_relay_child_loss_is_counted_separately_from_local_decoder_loss(self):
        result = score(line(5, "Relay Classic RFCOMM TX: 579 kbps | write p50/p95/p99/max 0.5/1.6/2.7/4.4 ms | flush p95 1.1 ms | queue 0/32 | drops 3"))
        self.assertEqual(result["drops"]["forwarding_drops"]["observed"], 3)
        self.assertEqual(result["gates"]["forwarding_drops"]["status"], "FAIL")
        self.assertEqual(result["queues"][0]["kind"], "relay")

    def test_jitter_without_long_freezes_fails(self):
        result = score(line(0, surface(1)) + line(5, surface(76, p95=120)))
        self.assertEqual(result["gates"]["unexpected_freeze_events"]["status"], "PASS")
        self.assertEqual(result["gates"]["surface_p95_ms"]["status"], "FAIL")
        self.assertEqual(result["overall_status"], "FAIL")

    def test_missing_counters_and_surface_are_not_pass(self):
        result = score(line(0, "15 rendered/16 submitted, shown cadence p50/p95/p99/max 66.7/66.7/66.7/66.7 ms"))
        self.assertEqual(result["overall_status"], "UNMEASURED")
        for key in ("surface_p95_ms", "unexpected_freeze_events", "decoder_drops", "audio_drops"):
            self.assertEqual(result["gates"][key]["status"], "UNMEASURED")

    def test_multiple_pids_require_scope(self):
        text = line(0, surface(1, 10), pid=111) + line(1, surface(1), pid=222) + line(6, surface(76), pid=222)
        self.assertEqual(score(text)["scope"]["status"], "UNMEASURED")
        result = score(text, pid=222)
        self.assertEqual(result["freezes"]["observed"], 0)
        self.assertEqual(result["scope"]["pid"], 222)

    def test_time_scope_excludes_former_session_same_pid(self):
        text = line(0, surface(150, 4)) + line(20, surface(1)) + line(25, surface(76))
        result = score(text, start="10-02 19:12:19.000")
        self.assertEqual(result["freezes"]["observed"], 0)
        self.assertEqual(len(result["surface_segments"]), 1)

    def test_final_decimal_cadence_and_worst_not_mean(self):
        text = line(0, surface(1)) + line(5, surface(76, p95=90.25)) + line(10, surface(151, p95=67.125, final=True))
        result = score(text)
        self.assertEqual(result["gates"]["surface_p95_ms"]["value"], 90.25)
        self.assertTrue(result["surface_segments"][0][-1]["final"])
        self.assertEqual(result["surface_segments"][0][-1]["cadence_ms"]["p95"], 67.125)

    def test_reset_counters_split_and_sum_segments(self):
        text = line(0, surface(1)) + line(5, surface(70, 2, 300, final=True)) + line(10, surface(1)) + line(15, surface(75, 1, 100))
        result = score(text)
        self.assertEqual(len(result["surface_segments"]), 2)
        self.assertEqual(result["freezes"]["observed"], 3)
        self.assertEqual(result["freeze_duration_ms"]["observed"], 400)
        self.assertEqual(len(result["visible_fps_intervals"]), 2)

    def test_planned_lifecycle_overlap_is_ambiguous_not_subtracted(self):
        text = line(0, surface(1)) + line(5, surface(45, 1, 1800, p95=500)) + line(10, surface(120, 1, 1800))
        manifest = {"planned_intervals": [{"device": "test", "start": "10-02 19:12:01.000", "end": "10-02 19:12:03.000", "action": "background"}]}
        result = scorer.score_log(text, {"device": "test", "expected_fps": 15}, manifest)
        self.assertEqual(result["freezes"]["observed"], 1)
        self.assertEqual(result["freeze_duration_ms"]["observed"], 1800)
        self.assertEqual(result["freezes"]["lifecycle_overlap"], 1)
        self.assertEqual(result["gates"]["unexpected_freeze_events"]["status"], "UNMEASURED")
        self.assertEqual(result["gates"]["surface_p95_ms"]["status"], "UNMEASURED")

    def test_encoder_queues_drops_thermal_parse(self):
        text = (line(0, "Encoder output: 14.8 FPS, 512 kbps, PTS cadence p50/p95/max 66.6/75.0/75.0 ms") +
                line(5, "End-to-end encrypted BLE GATT TX: 500 kbps | frame-call p50/p95/p99 2.4/50.5/75.7 ms | flush p95 73.0 ms | queue 33/33, 41 KB, 1124 ms | drops 4 | thermal 2"))
        result = score(text, role="sender")
        self.assertEqual(result["encoder"]["kbps"]["max"], 512)
        self.assertEqual(result["drops"]["sender_drops"]["observed"], 4)
        self.assertEqual(result["gates"]["sender_drops"]["status"], "FAIL")
        self.assertEqual(result["queues"][0]["oldest_ms"], 1124)
        self.assertEqual(result["thermal"][0]["status"], 2)

    def test_decoder_audio_counts_are_not_surface_counts(self):
        text = line(0, "End-to-end encrypted VP9 480x640: 50 video frames, 100 audio packets, 46 rendered/48 submitted, 520 kbps, 15.0 FPS, BT queue 0/512, decoder 5/15 (292 ms, drops 2), input wait p95 355.7 ms, playout p95 71.6 ms, shown cadence p50/p95/p99/max 66.8/72.8/84.5/100.8 ms via OMX.Exynos.vp9.dec, audio via c2.android.opus.decoder, drops 3")
        result = score(text)
        self.assertEqual(result["drops"]["decoder_drops"]["observed"], 2)
        self.assertEqual(result["drops"]["audio_drops"]["observed"], 3)
        self.assertEqual(result["surface_segments"], [])

    def test_unaligned_optical_result_not_quality_pass(self):
        manifest = {"optical_results": [{"device": "test", "aligned": False, "reference_kind": "raw_fixed_fixture", "coverage_matches": True, "vmaf": 99, "ssim": 0.999, "psnr_db": 50}]}
        result = scorer.score_log(line(0, surface(1)), {"device": "test", "expected_fps": 15}, manifest)
        self.assertEqual(result["quality_status"], "UNMEASURED")

    def test_private_media_startup_not_selection_startup(self):
        result = score(line(0, "Private call startup: media-to-first-surface 400.2 ms"), role="private")
        self.assertEqual(result["startup"][0]["kind"], "media-to-first-surface")
        self.assertEqual(result["gates"]["selection_to_first_surface_ms"]["status"], "UNMEASURED")

    def test_periodic_surface_is_not_exact_recovery_time(self):
        manifest = {"selection_events": [{"device": "test", "time": "10-02 19:12:01.000", "action": "reconnect"}]}
        result = scorer.score_log(line(5, surface(100)), {"device": "test", "expected_fps": 15}, manifest)
        self.assertEqual(result["actions"][0]["first_surface_ms"]["status"], "UNMEASURED")

    def test_harness_bookkeeping_events_without_a_clock_time_are_not_scored_as_actions(self):
        # mesh4-7a6c401c: log_capture_restarted / log_dump_merged / adb_retry events have a device but no time.
        manifest = {"selection_events": [
            {"device": "test", "action": "log_capture_restarted", "restart": 1},
            {"device": "test", "action": "log_dump_merged", "lines": 7},
            {"device": "test", "action": "adb_retry", "attempt": 1},
            {"device": "test", "time": "10-02 19:12:01.000", "action": "reconnect"}]}
        result = scorer.score_log(line(5, surface(100)), {"device": "test", "expected_fps": 15}, manifest)
        self.assertEqual(["reconnect"], [item["action"] for item in result["actions"]])

    def test_missing_expected_fps_stays_unmeasured(self):
        result = scorer.score_log(line(0, surface(1)) + line(5, surface(76)), {"device": "test"})
        self.assertEqual(result["gates"]["surface_p95_ms"]["status"], "UNMEASURED")

    def test_acknowledged_deliberate_end_does_not_score_short_terminal_interval_as_offered_fps(self):
        text = (line(0, surface(1)) + line(5, surface(76)) +
                line(5.095, "Stream ended; local and downstream completion acknowledged.") +
                line(5.148, surface(77, final=True)))
        spec = {"device": "test", "role": "receiver", "expected_fps": 15}
        manifest = {"scenario": {"source": "source"}, "selection_events": [
            {"device": "source", "action": "end", "time": "10-02 19:12:05.000"}]}
        result = scorer.score_log(text, spec, manifest)
        self.assertEqual(result["gates"]["visible_fps"]["status"], "PASS")
        self.assertEqual(result["visible_fps_intervals"][-1]["planned_overlap"], True)
        self.assertEqual(result["freezes"]["observed"], 0)
        self.assertEqual(scorer.score_log(text, spec)["gates"]["visible_fps"]["status"], "FAIL")
        no_ack = text.replace("Stream ended; local and downstream completion acknowledged.", "Receiver visible")
        self.assertEqual(scorer.score_log(no_ack, spec, manifest)["gates"]["visible_fps"]["status"], "FAIL")

    def test_private_planned_end_uses_each_phones_confirmed_phase_clock_and_keeps_prior_failure(self):
        text = (line(0, surface(1).replace("Receiver", "Private call")) +
                line(5, surface(76, p95=100).replace("Receiver", "Private call")) +
                line(5.7, "BENCHMARK call phase ENDED") +
                line(5.8, "Private call ended: Peer ended the call") +
                line(5.801, surface(86, p95=100, final=True).replace("Receiver", "Private call")) +
                line(5.9, "BENCHMARK call available: node=aaaaaaaaaaaa"))
        # The actor's acknowledgment is on another clock; it cannot bound this receiver.
        event = {"device": "peer", "action": "end", "acknowledged": True, "ack_time": "10-02 19:12:00.100",
                 "terminal_evidence": {"test": {"ended": "10-02 19:12:05.800", "available": "10-02 19:12:05.900"},
                                       "peer": {"ended": "10-02 19:12:00.200", "available": "10-02 19:12:00.300"}}}
        spec = {"device": "test", "role": "receiver", "expected_fps": 15}
        result = scorer.score_log(text, spec, {"control_events": [event]})
        self.assertEqual(result["gates"]["visible_fps"]["status"], "PASS")
        self.assertEqual(result["gates"]["surface_p95_ms"]["status"], "FAIL")
        self.assertEqual(result["evaluation_intervals"][0]["start"], "10-02 19:12:05.700")
        for changed in ({}, {"control_events": [{**event, "acknowledged": False}]},
                        {"control_events": [{**event, "state": "FAIL"}]},
                        {"control_events": [{**event, "terminal_evidence": {}}]},
                        {"control_events": [{**event, "terminal_evidence": {"test": event["terminal_evidence"]["test"]}}]},
                        {"control_events": [{**event, "terminal_evidence": {**event["terminal_evidence"], "peer": {"ended": "10-02 19:12:00.200"}}}]}):
            self.assertEqual(scorer.score_log(text, spec, changed)["gates"]["visible_fps"]["status"], "FAIL")
        lost = text.replace("Private call ended: Peer ended the call", "Private call ended: Call link lost")
        self.assertEqual(scorer.score_log(lost, spec, {"control_events": [event]})["gates"]["visible_fps"]["status"], "FAIL")
        no_phase = text.replace("BENCHMARK call phase ENDED", "BENCHMARK call phase ACTIVE")
        self.assertEqual(scorer.score_log(no_phase, spec, {"control_events": [event]})["gates"]["visible_fps"]["status"], "FAIL")
        no_available = text.replace("BENCHMARK call available:", "Call not available:")
        self.assertEqual(scorer.score_log(no_available, spec, {"control_events": [event]})["gates"]["visible_fps"]["status"], "FAIL")
        reopened = text + line(5.95, "BENCHMARK call phase ACTIVE")
        self.assertEqual(scorer.score_log(reopened, spec, {"control_events": [event]})["gates"]["visible_fps"]["status"], "FAIL")
        fatal = text + line(5.95, "FATAL EXCEPTION: main")
        self.assertEqual(scorer.score_log(fatal, spec, {"control_events": [event]})["gates"]["unexpected_events"]["status"], "FAIL")
        # A build that names the call on the record is the same planned end; an abnormal end with an ID stays abnormal.
        with_id = text.replace("Peer ended the call", "Peer ended the call | callId=123e4567-e89b-12d3-a456-426614174000")
        named = scorer.score_log(with_id, spec, {"control_events": [event]})
        self.assertEqual(named["gates"]["visible_fps"]["status"], "PASS")
        self.assertEqual(named["evaluation_intervals"][0]["start"], "10-02 19:12:05.700")
        lost_with_id = lost.replace("Call link lost", "Call link lost | callId=123e4567-e89b-12d3-a456-426614174000")
        self.assertEqual(scorer.score_log(lost_with_id, spec, {"control_events": [event]})["gates"]["visible_fps"]["status"], "FAIL")

    def test_private_end_logged_only_by_the_ui_teardown_record_is_the_same_evidence_when_the_call_thread_record_was_lost(self):
        # calls-noloss-m31asus-51cc8028/0007: ASUS logd dropped the call-thread record; the UI record survived.
        text = (line(0, surface(1).replace("Receiver", "Private call")) +
                line(5, surface(76).replace("Receiver", "Private call")) +
                line(5.7, "BENCHMARK call phase ENDED") +
                line(5.801, surface(86, final=True).replace("Receiver", "Private call")) +
                line(5.82, "BENCHMARK call ended_ui: message=Peer ended the call") +
                line(5.9, "BENCHMARK call available: node=aaaaaaaaaaaa"))
        event = {"device": "peer", "action": "end", "acknowledged": True, "ack_time": "10-02 19:12:00.100",
                 "terminal_evidence": {"test": {"ended": "10-02 19:12:05.820", "available": "10-02 19:12:05.900"},
                                       "peer": {"ended": "10-02 19:12:00.200", "available": "10-02 19:12:00.300"}}}
        spec = {"device": "test", "role": "receiver", "expected_fps": 15}
        result = scorer.score_log(text, spec, {"control_events": [event]})
        self.assertEqual(result["evaluation_intervals"][0]["source"], "planned_control_and_local_terminal")
        self.assertEqual(result["gates"]["unexpected_events"]["status"], "PASS")
        # An abnormal end is never hidden by the fallback record.
        lost = text.replace("message=Peer ended the call", "message=Call link lost: EOFException")
        self.assertEqual(scorer.score_log(lost, spec, {"control_events": [event]})["gates"]["unexpected_events"]["status"], "FAIL")
        # When both records exist the end is counted once.
        both = text.replace(line(5.82, "BENCHMARK call ended_ui: message=Peer ended the call"),
                            line(5.8, "Private call ended: Call link lost: EOFException") +
                            line(5.82, "BENCHMARK call ended_ui: message=Call link lost: EOFException"))
        self.assertEqual(scorer.score_log(both, spec, {})["gates"]["unexpected_events"]["value"], 1)
        # The ended proof names the fallback so the evidence source stays visible.
        row = {"time": "10-02 19:12:05.820", "message": "BENCHMARK call ended_ui: message=Call ended"}
        available = {"time": "10-02 19:12:05.900"}
        self.assertIn("ended_source", scorer.ended_proof(row, available))
        self.assertNotIn("ended_source", scorer.ended_proof({**row, "message": "Private call ended: Call ended"}, available))
        self.assertTrue(re.search(scorer.ENDED_EVIDENCE, row["message"]))
        self.assertTrue(re.search(scorer.ENDED_EVIDENCE, "Private call ended: Call ended"))

    def test_private_local_end_requires_actual_control_ack_and_does_not_hide_existing_freeze(self):
        text = (line(0, surface(1).replace("Receiver", "Private call")) +
                line(5, surface(70).replace("Receiver", "Private call")) +
                line(5.7, "BENCHMARK call phase ENDED") + line(5.7, "BENCHMARK control end applied") +
                line(5.8, "Private call ended: Call ended") +
                line(5.801, surface(76, freezes=1, duration=400, p95=100, final=True).replace("Receiver", "Private call")) +
                line(5.85, "Encoder output: 13.7 FPS, 150.0 kbps") +
                line(5.9, "BENCHMARK call available: node=aaaaaaaaaaaa"))
        event = {"device": "test", "action": "end", "acknowledged": True, "ack_time": "10-02 19:12:05.700",
                 "terminal_evidence": {"test": {"ended": "10-02 19:12:05.800", "available": "10-02 19:12:05.900"},
                                       "peer": {"ended": "10-02 19:12:05.900", "available": "10-02 19:12:06.000"}}}
        spec = {"device": "test", "role": "receiver", "expected_fps": 15}
        result = scorer.score_log(text, spec, {"control_events": [event]})
        self.assertEqual(result["gates"]["unexpected_freeze_events"]["status"], "FAIL")
        self.assertEqual(result["freezes"]["observed"], 1)
        self.assertEqual(result["gates"]["surface_p95_ms"]["value"], 100)
        self.assertEqual(result["visible_fps_intervals"][-1]["planned_overlap"], True)
        outgoing = scorer.score_log(text, {**spec, "role": "sender"}, {"control_events": [event]})
        self.assertEqual(outgoing["gates"]["encoder_fps"]["status"], "FAIL")
        no_ack = text.replace("BENCHMARK control end applied", "BENCHMARK control end rejected")
        self.assertEqual(scorer.score_log(no_ack, spec, {"control_events": [event]})["visible_fps_intervals"][-1]["planned_overlap"], False)

    def test_target_profile_reports_actual_resolution_not_launch_request(self):
        result = score(line(0, "Streaming VP9 - Android compatibility at 640×480, 15 FPS, 500 kbps"),
                       role="sender", expected_width=640, expected_height=360, expected_bitrate_kbps=500)
        self.assertEqual(result["gates"]["profile_resolution_mismatches"]["status"], "FAIL")
        self.assertEqual(result["gates"]["profile_bitrate_mismatches"]["status"], "PASS")

    def test_applied_source_encoder_fps_allocation_detect_hidden_substitution(self):
        manifest = {"scenario": {"profile": {"codec": "c2.exynos.vp9.encoder", "rate_control": "cbr"}}}
        text = (line(0, "Encoder statistics enabled for c2.android.vp9.encoder") +
                line(1, "Streaming VP9 - Android compatibility at 720×480, 15 FPS, 1100 kbps, 500 ms budget, Scene-adaptive bitrate (VBR) mode 1 (camera 15–15 FPS)"))
        result = scorer.score_log(text, {"device": "test", "role": "sender", "expected_fps": 30}, manifest)
        for name in ("profile_encoder_mismatches", "profile_fps_mismatches", "profile_allocation_mismatches"):
            self.assertEqual(result["gates"][name]["status"], "FAIL")

    def test_selected_intent_and_codec_alias_cannot_prove_actual_settings(self):
        manifest = {"scenario": {"profile": {"codec": "c2.exynos.vp9.encoder", "rate_control": "cbr"}}}
        text = line(0, "Test launch: source=true, test_codec=c2.exynos.vp9.encoder, test_rate_control=cbr, test_fps=30")
        result = scorer.score_log(text, {"device": "test", "role": "sender", "expected_fps": 30}, manifest)
        for name in ("profile_encoder_mismatches", "profile_fps_mismatches", "profile_allocation_mismatches"):
            self.assertEqual(result["gates"][name]["status"], "UNMEASURED")
        manifest["scenario"]["profile"]["codec"] = "vp9"
        result = scorer.score_log(line(1, "Encoder statistics enabled for c2.exynos.vp9.encoder"),
                                  {"device": "test", "role": "sender", "expected_fps": 30}, manifest)
        self.assertEqual(result["gates"]["profile_encoder_mismatches"]["status"], "UNMEASURED")

    def test_raw_runtime_marker_proves_exact_profile_without_camera_streaming_marker(self):
        manifest = {"scenario": {"profile": {"codec": "c2.exynos.vp9.encoder", "rate_control": "cbr"}}}
        text = line(1, "Benchmark raw source: benchmark-raw-abc.yuv, 300 looping I420 frames, 720x480 at 30 FPS, c2.exynos.vp9.encoder, 1100 kbps, Steady bitrate (CBR), frame-zero PTS 2685760217440 us; Camera2 and physical motion unmeasured")
        result = scorer.score_log(text, {"device": "test", "role": "sender", "expected_fps": 30,
                                  "expected_width": 720, "expected_height": 480, "expected_bitrate_kbps": 1100}, manifest)
        for name in ("profile_encoder_mismatches", "profile_fps_mismatches", "profile_allocation_mismatches",
                     "profile_resolution_mismatches", "profile_bitrate_mismatches"):
            self.assertEqual(result["gates"][name]["status"], "PASS")
        self.assertEqual(result["profiles"][0]["kind"], "raw_encoder_target")

    def test_live_allocation_mode_fallback_and_later_encoder_restart_remain_failures(self):
        manifest = {"scenario": {"profile": {"codec": "libaom-native", "rate_control": "cbr_fd"}}}
        text = (line(0, "Encoder statistics unavailable for libaom-native") +
                line(1, "Streaming AV1 - optimized software at 640×480, 15 FPS, 500 kbps, 500 ms budget, Steady bitrate + frame dropping (CBR-FD) mode 2 (camera 15–15 FPS)") +
                line(2, "Encoder statistics enabled for c2.android.av1.encoder"))
        result = scorer.score_log(text, {"device": "test", "role": "sender", "expected_fps": 15}, manifest)
        self.assertEqual(result["gates"]["profile_encoder_mismatches"]["status"], "FAIL")
        self.assertEqual(result["gates"]["profile_allocation_mismatches"]["status"], "FAIL")

    def test_rotated_receiver_shape_is_not_false_profile_mismatch(self):
        result = score(line(0, "Receiving end-to-end encrypted stream from x over link y: VP9 480x640 at 15 FPS"),
                       expected_width=640, expected_height=480)
        self.assertEqual(result["gates"]["profile_resolution_mismatches"]["status"], "PASS")

    def test_multiple_sender_routes_count_independently(self):
        template = "Nearby device [{peer}]: End-to-end encrypted BLE GATT TX: 500 kbps | frame-call p50/p95/p99 2.4/50.5/75.7 ms | flush p95 73.0 ms | queue 1/33, 0 KB, 11 ms | drops {drops} | thermal 0"
        text = line(0, template.format(peer="aaaa", drops=3)) + line(1, template.format(peer="bbbb", drops=2)) + line(5, template.format(peer="aaaa", drops=4)) + line(6, template.format(peer="bbbb", drops=3))
        result = score(text, role="sender")
        self.assertEqual(result["drops"]["sender_drops"]["observed"], 7)

    def test_equal_counter_at_new_segment_not_lost(self):
        text = line(0, surface(1, 1, 100)) + line(5, surface(70, 2, 300, final=True)) + line(10, surface(1, 2, 300)) + line(15, surface(75, 3, 400))
        result = score(text)
        self.assertEqual(result["freezes"]["observed"], 5)

    def test_first_surface_not_reused_for_two_actions(self):
        manifest = {"selection_events": [{"device": "test", "time": "10-02 19:12:01.000", "action": "connect"},
                                          {"device": "test", "time": "10-02 19:12:03.000", "action": "reconnect"}]}
        result = scorer.score_log(line(5, surface(1)), {"device": "test", "expected_fps": 15}, manifest)
        self.assertEqual(result["actions"][0]["first_surface_ms"]["status"], "UNMEASURED")
        self.assertEqual(result["actions"][1]["first_surface_ms"]["value"], 2000)

    def test_runner_error_overrides_other_results(self):
        result = scorer.score_manifest({"logs": [], "runner_error": "wireless debugging disconnected"})
        self.assertEqual(result["execution_gates"]["runner_error"]["status"], "FAIL")
        self.assertEqual(result["overall_status"], "FAIL")

    def test_empty_manifest_cannot_pass_execution(self):
        self.assertEqual(scorer.score_manifest({"logs": []})["overall_status"], "UNMEASURED")

    def test_requested_traces_require_verified_phase_for_every_participant(self):
        manifest = {"scenario": {"source": "a", "receivers": [{"phone": "b", "parent": "a"}], "media_trace": True},
                    "logs": [], "media_traces": [{"device": "a", "state": "MEASURED"}]}
        self.assertEqual(scorer.score_manifest(manifest)["execution_gates"]["media_traces"]["status"], "UNMEASURED")
        manifest["media_traces"].append({"device": "b", "state": "FAIL"})
        self.assertEqual(scorer.score_manifest(manifest)["execution_gates"]["media_traces"]["status"], "FAIL")
        manifest["media_traces"][-1]["state"] = "MEASURED"
        self.assertEqual(scorer.score_manifest(manifest)["execution_gates"]["media_traces"]["status"], "PASS")
        manifest["media_traces"].append({"device": "a", "state": "UNMEASURED", "phase": "unfinished-restart"})
        self.assertEqual(scorer.score_manifest(manifest)["execution_gates"]["media_traces"]["status"], "UNMEASURED")

    def test_connection_timeout_later_first_frame_cannot_erase_failure(self):
        manifest = {"selection_events": [{"device": "test", "time": "10-02 19:12:01.000", "action": "connection_timeout"}]}
        result = scorer.score_log(line(2, surface(1)), {"device": "test", "expected_fps": 15}, manifest)
        self.assertEqual(result["actions"][0]["first_surface_ms"]["status"], "FAIL")
        self.assertEqual(result["overall_status"], "FAIL")
        self.assertEqual(scorer.score_manifest(manifest)["overall_status"], "FAIL")


if __name__ == "__main__":
    unittest.main()


def test_unexpected_event_pattern_ignores_eof_inside_words():
    import score_device_run
    assert not score_device_run.UNEXPECTED.search(
        "BENCHMARK call_policy: videoFragments=false | firstFrameNow=false")
    assert score_device_run.UNEXPECTED.search("java.io.EOFException: read failed")
    assert score_device_run.UNEXPECTED.search("Stream ended: EOF")


def test_foreground_service_type_refusal_is_an_unexpected_event():
    import score_device_run
    assert not score_device_run.UNEXPECTED.search(
        "BENCHMARK foreground_service: requested=connectedDevice+camera+microphone | "
        "effective=connectedDevice+camera+microphone | refused=none")
    assert score_device_run.UNEXPECTED.search(
        "BENCHMARK foreground_service: requested=connectedDevice+camera+microphone | "
        "effective=connectedDevice | refused=SecurityException: Starting FGS with type camera")
    assert score_device_run.UNEXPECTED.search(
        "BENCHMARK foreground_service: requested=connectedDevice | effective=none | "
        "refused=ForegroundServiceStartNotAllowedException: startForegroundService() not allowed")


def test_recovered_le_setup_retry_notice_is_not_an_unexpected_event():
    import score_device_run
    # BluetoothTransportPolicy.leSetupRetryNotice wording (BluetoothTransportPolicyTest pins it).
    for message in ("GATT video stack returned status 8; retrying once",
                    "BLE control stack returned status 62; retrying connection",
                    "GATT video MTU exchange stalled; retrying once"):
        assert not score_device_run.UNEXPECTED.search(message), message


def test_launch_configuration_listing_is_not_an_unexpected_event():
    import score_device_run
    # 2026-10-17: the device_policy inventory names "link-lost-after-change-rollback" (71ef65b); the pattern
    # "link.*lost" counted one false unexpected event per launch in every stream and call score.
    listing = ("BENCHMARK device_policy: revision=2026-10-14.2 | matchingProposals=ll-procedure-collision-serialise,"
               "link-lost-after-change-rollback,le-priority-interval-reassert")
    assert score_device_run.UNEXPECTED.search(listing)  # the raw pattern still matches the names
    assert listing.startswith(score_device_run.CONFIGURATION_LISTINGS)  # so the scan skips the line
