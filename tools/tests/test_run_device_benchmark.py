import importlib.util
import unittest
import struct
import tempfile
import hashlib
import json
import sys
from unittest.mock import Mock, patch, mock_open
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))

spec = importlib.util.spec_from_file_location("runner", Path(__file__).parents[1] / "run_device_benchmark.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class BenchmarkPlanTest(unittest.TestCase):
    def test_mesh_fix_tunables_map_to_their_debug_extras(self):
        self.assertEqual({"test_link_pacer_seed": False, "test_fanout_early_release": True},
                         runner.tunable_extras({"link_pacer_seed": False, "fanout_early_release": True}))

    def test_native_wedge_guard_tunable_maps_to_its_debug_extra(self):
        self.assertEqual({"test_gatt_native_wedge_guard": True},
                         runner.tunable_extras({"gatt_native_wedge_guard": True}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"gatt_native_wedge_guard": "on"})

    def test_call_prewarm_maps_to_its_debug_extra_and_takes_only_a_boolean(self):
        self.assertEqual({"test_call_prewarm": True}, runner.tunable_extras({"call_prewarm": True}))
        self.assertEqual({"test_call_prewarm": False}, runner.tunable_extras({"call_prewarm": False}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"call_prewarm": "on"})

    def test_call_video_restart_arms_map_to_their_debug_extras(self):
        # B4-media A/B (S22 calls A15s): off, the in-place decoder stall recovery, and the call video restart.
        self.assertEqual({"test_call_video_restart": True, "test_decoder_stall_recovery": False},
                         runner.tunable_extras({"call_video_restart": True, "decoder_stall_recovery": False}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"call_video_restart": "on"})

    def test_hop_integrity_resend_diff_maps_to_its_debug_extra_and_takes_only_a_boolean(self):
        self.assertEqual({"test_hop_integrity_resend_diff": True}, runner.tunable_extras({"hop_integrity_resend_diff": True}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"hop_integrity_resend_diff": "on"})

    def test_relay_acknowledged_writes_tunable_maps_to_its_debug_extra(self):
        self.assertEqual({"test_relay_gatt_acknowledged_writes": True},
                         runner.tunable_extras({"relay_gatt_acknowledged_writes": True}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"relay_gatt_acknowledged_writes": "on"})

    def test_le_link_tunables_map_to_their_debug_extras(self):
        self.assertEqual(
            {"test_coc_sequenced_phy": False, "test_gatt_request_mtu": 498, "test_ble_phy": "coded-s2",
             "test_gatt_aligned_writes": True, "test_le_priority_retry": False, "test_link_pacer_peer_seed": True},
            runner.tunable_extras({"coc_sequenced_phy": False, "gatt_request_mtu": 498, "ble_phy": "coded-s2",
                                   "gatt_aligned_writes": True, "le_priority_retry": False,
                                   "link_pacer_peer_seed": True}),
        )
        with self.assertRaises(ValueError):
            runner.tunable_extras({"gatt_request_mtu": True})

    def test_auto_ladder_tunables_map_to_their_debug_extras(self):
        self.assertEqual({"test_auto_ladder": True, "test_auto_ladder_class": "weak"},
                         runner.tunable_extras({"auto_ladder": True, "auto_ladder_class": "weak"}))
        self.assertEqual({"test_auto_ladder": False}, runner.tunable_extras({"auto_ladder": False}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"auto_ladder": "on"})
        with self.assertRaises(ValueError):
            runner.tunable_extras({"auto_ladder_class": True})

    def test_phy_setup_stall_tunable_maps_to_its_debug_extra(self):
        # Default-on call 2M setup-stall guard; false is the control arm (2026-10-10).
        self.assertEqual({"test_phy_setup_stall": False}, runner.tunable_extras({"phy_setup_stall": False}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"phy_setup_stall": "off"})

    def test_message_dial_and_progress_total_tunables_map_to_their_debug_extras(self):
        # Default-on control arms of the store-and-forward dial fixes (2026-10-13).
        self.assertEqual({"test_message_adaptive_phy": False}, runner.tunable_extras({"message_adaptive_phy": False}))
        self.assertEqual({"test_mv2_progress_total": False}, runner.tunable_extras({"mv2_progress_total": False}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"message_adaptive_phy": "off"})

    def test_coc_phy_after_connect_tunable_maps_to_its_debug_extra(self):
        # Experimental CoC PHY request after connect + hop auth (app default off, 2026-10-10).
        self.assertEqual({"test_coc_phy_after_connect": True}, runner.tunable_extras({"coc_phy_after_connect": True}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"coc_phy_after_connect": "on"})

    def test_peer_address_book_tunable_maps_to_its_debug_extra(self):
        self.assertEqual({"test_peer_address_book": True}, runner.tunable_extras({"peer_address_book": True}))

    def test_coc_coalesce_sdus_tunable_maps_to_its_debug_extra(self):
        self.assertEqual({"test_coc_coalesce_sdus": True}, runner.tunable_extras({"coc_coalesce_sdus": True}))

    def test_relay_coc_coalesce_tunable_maps_to_its_debug_extra(self):
        self.assertEqual({"test_relay_coc_coalesce": True}, runner.tunable_extras({"relay_coc_coalesce": True}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"relay_coc_coalesce": "on"})

    def test_call_fragment_airtime_tunable_maps_to_its_integer_extra(self):
        # Fourth call head-of-line arm: fragments sized by link airtime (ms), 0/absent = fixed 2 KB.
        self.assertEqual(
            {"test_call_video_fragments": True, "test_call_fragment_airtime_ms": 40},
            runner.tunable_extras({"call_video_fragments": True, "call_fragment_airtime_ms": 40}),
        )
        with self.assertRaises(ValueError):
            runner.tunable_extras({"call_fragment_airtime_ms": True})
        with self.assertRaises(ValueError):
            runner.tunable_extras({"call_fragment_airtime_ms": "40"})

    def test_bounded_media_age_tunable_maps_to_its_debug_extra(self):
        # Experimental bounded media age A/B arms set this on every participant (app default off).
        self.assertEqual({"test_bounded_media_age": True}, runner.tunable_extras({"bounded_media_age": True}))
        self.assertEqual({"test_bounded_media_age": False}, runner.tunable_extras({"bounded_media_age": False}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"bounded_media_age": 1})

    def test_relay_child_media_age_tunable_maps_to_its_debug_extra(self):
        # Experimental relay-child-only shedding is set on the relay phone (app default off).
        self.assertEqual({"test_relay_child_media_age": True}, runner.tunable_extras({"relay_child_media_age": True}))
        self.assertEqual({"test_relay_child_media_age": False}, runner.tunable_extras({"relay_child_media_age": False}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"relay_child_media_age": 1})

    def test_relay_child_thinning_tunable_maps_to_its_debug_extra(self):
        # Experimental relay child thinning is set on every phone of the chain (app default off).
        self.assertEqual({"test_relay_child_thinning": True}, runner.tunable_extras({"relay_child_thinning": True}))
        self.assertEqual({"test_relay_child_thinning": False}, runner.tunable_extras({"relay_child_thinning": False}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"relay_child_thinning": 1})

    def test_relay_parent_trickle_and_one_pdu_move_map_to_their_debug_extras(self):
        self.assertEqual(
            {"test_relay_parent_trickle": False, "test_relay_one_pdu_move": False},
            runner.tunable_extras({"relay_parent_trickle": False, "relay_one_pdu_move": False}),
        )

    def test_learned_state_reset_and_ceiling_probe_recovery_map_to_their_debug_extras(self):
        self.assertEqual(
            {"test_reset_learned_state": True, "test_ceiling_probe_recovery": True},
            runner.tunable_extras({"reset_learned_state": True, "ceiling_probe_recovery": True}),
        )
        with self.assertRaises(ValueError):
            runner.tunable_extras({"reset_learned_state": 1})

    def test_fanout_proven_links_maps_to_its_debug_extra(self):
        self.assertEqual({"test_fanout_proven_links": False}, runner.tunable_extras({"fanout_proven_links": False}))

    def test_relay_establish_fallback_maps_to_its_debug_extra(self):
        self.assertEqual({"test_relay_establish_fallback": False}, runner.tunable_extras({"relay_establish_fallback": False}))

    def test_relay_establish_budget_maps_to_its_debug_extra(self):
        self.assertEqual({"test_relay_establish_budget": True}, runner.tunable_extras({"relay_establish_budget": True}))

    def test_call_prewarm_maps_to_its_debug_extra_and_takes_only_a_boolean(self):
        self.assertEqual({"test_call_prewarm": True}, runner.tunable_extras({"call_prewarm": True}))
        self.assertEqual({"test_call_prewarm": False}, runner.tunable_extras({"call_prewarm": False}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"call_prewarm": "on"})

    def test_call_camera_first_maps_to_its_debug_extra_and_takes_only_a_boolean(self):
        self.assertEqual({"test_call_camera_first": True}, runner.tunable_extras({"call_camera_first": True}))
        self.assertEqual({"test_call_camera_first": False}, runner.tunable_extras({"call_camera_first": False}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"call_camera_first": "on"})

    def test_hop_integrity_resend_diff_maps_to_its_debug_extra_and_takes_only_a_boolean(self):
        self.assertEqual({"test_hop_integrity_resend_diff": True}, runner.tunable_extras({"hop_integrity_resend_diff": True}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"hop_integrity_resend_diff": "on"})

    def test_relay_cold_start_maps_to_its_debug_extra(self):
        self.assertEqual({"test_relay_cold_start": False}, runner.tunable_extras({"relay_cold_start": False}))

    def test_ap7_tunables_map_to_their_debug_extras(self):
        # AP7: the delivered-control write, the slow 2M hop-authentication arm and the DEBUG static relay child guard.
        self.assertEqual(
            {"test_gatt_first_write_counts_control": True, "test_stall_slow_auth_ms": 500,
             "test_relay_start_hop_guard": True},
            runner.tunable_extras({"gatt_first_write_counts_control": True, "stall_slow_auth_ms": 500,
                                   "relay_start_hop_guard": True}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"stall_slow_auth_ms": "500"})

    def test_ap5_tunables_map_to_their_debug_extras(self):
        # AP5: the two Experimental arms, and the codec masters an Automatic-codec A/B needs (a benchmark launch keeps them off).
        self.assertEqual(
            {"test_relay_no_ceiling_floor": True, "test_sw_shortfall_avc": True, "test_h264_codec": True,
             "test_hevc_codec": True, "test_prefer_libvpx": True, "test_codec_power_aware": False},
            runner.tunable_extras({"relay_no_ceiling_floor": True, "sw_shortfall_avc": True, "h264_codec": True,
                                   "hevc_codec": True, "prefer_libvpx": True, "codec_power_aware": False}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"sw_shortfall_avc": "on"})

    def test_call_video_restart_arms_map_to_their_debug_extras(self):
        # B4-media A/B (S22 calls A15s): off, the in-place decoder stall recovery, and the call video restart.
        self.assertEqual({"test_call_video_restart": True, "test_decoder_stall_recovery": False},
                         runner.tunable_extras({"call_video_restart": True, "decoder_stall_recovery": False}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"call_video_restart": "on"})

    def test_relay_child_reanchor_tunable_maps_to_its_debug_extra(self):
        # Default-on relay child re-anchoring; false is the control arm.
        self.assertEqual({"test_relay_child_reanchor": False}, runner.tunable_extras({"relay_child_reanchor": False}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"relay_child_reanchor": 0})

    def test_time_based_sizing_tunables_map_to_their_debug_extras(self):
        # Q31: both arms are experiments (app default off); the budgets are integers in milliseconds.
        self.assertEqual(
            {"test_relay_time_queue": True, "test_playout_time_target": True,
             "test_time_queue_budget_ms": 1200, "test_time_queue_floor_ms": 300},
            runner.tunable_extras({"relay_time_queue": True, "playout_time_target": True,
                                   "time_queue_budget_ms": 1200, "time_queue_floor_ms": 300}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"relay_time_queue": 1})
        # The sustained-drain estimator is a separate experiment (app default off).
        self.assertEqual({"test_relay_drain_sustained": True},
                         runner.tunable_extras({"relay_drain_sustained": True}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"relay_drain_sustained": 1})

    def test_relay_pacing_tunables_map_to_their_debug_extras(self):
        # relay_pace_head is a default-on fix (control arm false); relay_pace_lead_cap is an experiment.
        self.assertEqual({"test_relay_pace_head": False, "test_relay_pace_lead_cap": True},
                         runner.tunable_extras({"relay_pace_head": False, "relay_pace_lead_cap": True}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"relay_pace_head": 0})

    def test_relay_dual_role_tunables_map_to_their_debug_extras(self):
        # relay_child_stall_guard is a default-on fix (control arm false); relay_upstream_dck is an experiment.
        self.assertEqual({"test_relay_child_stall_guard": False, "test_relay_upstream_dck": True},
                         runner.tunable_extras({"relay_child_stall_guard": False, "relay_upstream_dck": True}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"relay_upstream_dck": 1})

    def test_decoder_size_rebuild_is_a_default_on_control_arm(self):
        self.assertEqual({"test_decoder_size_rebuild": False},
                         runner.tunable_extras({"decoder_size_rebuild": False}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"decoder_size_rebuild": "no"})

    def test_call_audio_tunables_map_to_their_debug_extras(self):
        # Gap realignment is a default-on fix (control arm false); the other two are experiments.
        self.assertEqual({"test_audio_gap_align": False, "test_voice_call_low_delay": True,
                          "test_call_audio_own_clock": True},
                         runner.tunable_extras({"audio_gap_align": False, "voice_call_low_delay": True,
                                                "call_audio_own_clock": True}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"voice_call_low_delay": "yes"})

    def test_classic_experiment_tunables_map_to_their_debug_extras(self):
        # Experimental bond-free Classic and call-link pacing are set on every participant (app default off).
        self.assertEqual(
            {"test_classic_bond_free": True, "test_call_link_pacing": True,
             "test_classic_addresses": "9c8d7e6f5a40=AA:BB:CC:DD:EE:FF"},
            runner.tunable_extras({"classic_bond_free": True, "call_link_pacing": True,
                                   "classic_addresses": "9c8d7e6f5a40=AA:BB:CC:DD:EE:FF"}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"classic_bond_free": "yes"})

    def test_exception_without_a_message_remains_a_failed_broadcast(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = runner.Runner("adb", self.devices("ab"), self.case([{"phone": "b", "parent": "a"}]), Path(directory) / "run")
            instance.inventory = Mock(return_value={"apk_sha256": "a" * 64})
            instance.launch = Mock(side_effect=StopIteration())
            instance.phone_time = Mock(return_value="10-03 00:00:00.000")
            instance.adb = Mock()
            instance.beep = Mock()
            manifest = instance.run()
            self.assertEqual("StopIteration", manifest["runner_error"])
            self.assertEqual("FAIL", json.loads((instance.output / "score.json").read_text())["overall_status"])

    def test_moved_native_and_replay_inputs_keep_the_exact_request(self):
        import benchmark_layout
        with tempfile.TemporaryDirectory(prefix="relocated-inputs-") as directory:
            project = Path(directory)
            inputs = project / "benchmarks/inputs/fixtures"
            inputs.mkdir(parents=True)
            raw, replay = inputs / "raw.yuv", inputs / "replay.ivf"
            raw.write_bytes(bytes(18))
            self.replay(replay, width=2, height=2)
            aliases = {"artifacts/old/raw.yuv": raw.relative_to(project).as_posix(),
                       "artifacts/old/replay.ivf": replay.relative_to(project).as_posix()}
            catalog = project / "benchmarks/catalog"
            catalog.mkdir()
            (catalog / "ledger.json").write_text(json.dumps({"storage": {"paths": aliases}}))
            for field, source in (("raw_fixture_file", raw), ("replay_file", replay)):
                with self.subTest(field=field):
                    case = self.case([{"phone": "b", "parent": "a"}])
                    case["profile"].update(width=2, height=2)
                    case[field] = "artifacts/old/" + source.name
                    requested = json.dumps(case, sort_keys=True)
                    with patch.object(runner, "resolve", side_effect=lambda path, base=None: benchmark_layout.resolve(path, base, project)):
                        runner.validate_case(case, self.devices("ab"))
                        metadata = (runner.raw_fixture_metadata if field == "raw_fixture_file" else runner.replay_fixture_metadata)(case)
                    self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(), metadata["sha256"])
                    self.assertEqual(requested, json.dumps(case, sort_keys=True))
                    self.assertFalse((project / case[field]).exists())

    def test_cli_rejects_legacy_output_before_reading_inputs_or_using_devices(self):
        argv = ["runner", "--devices", "missing.json", "--suite", "missing.json", "--case", "case",
                "--output", str(Path(__file__).parents[2] / "artifacts/forbidden-new-run")]
        with patch.object(sys, "argv", argv), patch.object(runner, "Runner") as devices, \
                patch.object(runner, "resolve") as inputs:
            with self.assertRaisesRegex(ValueError, "benchmarks/runs"):
                runner.main()
        devices.assert_not_called()
        inputs.assert_not_called()

    def replay(self, path, frames=1501, width=640, height=360, fps=15, codec=b"VP90"):
        """Container-only fixture; the dummy payloads are not decoded phone evidence."""
        path.write_bytes(struct.pack("<4sHH4sHHIIII", b"DKIF", 0, 32, codec, width, height, fps, 1, frames, 0) +
                         b"".join(struct.pack("<IQ", 1, index) + b"\x00" for index in range(frames)))
        return path

    def test_inventory_retains_public_identity_for_route_scoring(self):
        instance = runner.Runner("adb", self.devices("ab"), self.case([{"phone": "b", "parent": "a"}]), Path("unused"))
        instance.adb = Mock(side_effect=["[ro.product.model]: [test]", "package:/test.apk", "abc /test.apk", "battery", "thermal"])
        self.assertEqual(instance.inventory("b")["node_prefix"], self.devices("ab")["b"]["node_prefix"])

    def test_parent_kill_marks_descendants_in_their_own_clock_not_siblings(self):
        case = self.case([{"phone": "b", "parent": "a"}, {"phone": "c", "parent": "b"},
                          {"phone": "d", "parent": "a"}])
        instance = runner.Runner("adb", self.devices("abcd"), case, Path("unused"))
        instance.phone_time = Mock(side_effect=lambda phone: {"b": "10-02 20:00:01.000", "c": "10-02 20:00:02.000"}[phone])
        instance.adb = Mock(return_value="")
        instance.logs = [{"device": "b"}]
        instance.action({"phone": "b", "action": "kill"})
        self.assertEqual(instance.topology_changes, [{"device": "c", "action": "planned_parent_change",
                                                    "time": "10-02 20:00:02.000", "removed_ancestor": "b"}])

    def devices(self, aliases):
        return {alias: {"serial": alias, "node_prefix": f"{index:012x}"} for index, alias in enumerate(aliases)}

    def case(self, receivers):
        return {"source": "a", "transport": "gatt", "receivers": receivers,
                "profile": {"width": 640, "height": 360, "fps": 15, "bitrate_kbps": 500}, "duration_s": 60}

    def test_chain_and_branch(self):
        for parents in [("a", "b"), ("a", "a")]:
            case = self.case([{"phone": "b", "parent": parents[0]}, {"phone": "c", "parent": parents[1]}])
            self.assertEqual(runner.validate_case(case, self.devices("abc")), set("abc"))

    def test_cycle_rejected(self):
        with self.assertRaises(ValueError):
            runner.validate_case(self.case([{"phone": "b", "parent": "c"}, {"phone": "c", "parent": "b"}]), self.devices("abc"))

    def test_duplicate_roles_rejected(self):
        with self.assertRaises(ValueError):
            runner.validate_case(self.case([{"phone": "b", "parent": "a"}, {"phone": "b", "parent": "a"}]), self.devices("ab"))

    def test_unknown_parent_rejected(self):
        with self.assertRaises(ValueError):
            runner.validate_case(self.case([{"phone": "b", "parent": "missing"}]), self.devices("ab"))

    def test_source_restart_not_mistaken_for_receiver_recovery(self):
        case = self.case([{"phone": "b", "parent": "a"}])
        case["actions"] = [{"phone": "a", "action": "restart_receiver", "at_s": 2}]
        with self.assertRaises(ValueError):
            runner.validate_case(case, self.devices("ab"))

    def test_alias_collision_is_rejected_before_touching_phones(self):
        devices = self.devices("ab")
        devices["b"]["serial"] = devices["a"]["serial"]
        with self.assertRaises(ValueError):
            runner.validate_case(self.case([{"phone": "b", "parent": "a"}]), devices)

    def test_invalid_profile_and_unreachable_actions(self):
        for field in ("width", "height", "fps", "bitrate_kbps"):
            case = self.case([{"phone": "b", "parent": "a"}])
            case["profile"][field] = 0
            with self.assertRaises(ValueError):
                runner.validate_case(case, self.devices("ab"))
        case = self.case([{"phone": "b", "parent": "a"}])
        case["actions"] = [{"phone": "b", "action": "kill", "at_s": 60}]
        with self.assertRaises(ValueError):
            runner.validate_case(case, self.devices("ab"))

    def test_receiver_cannot_acknowledge_a_broadcast_pause(self):
        case = self.case([{"phone": "b", "parent": "a"}])
        case["actions"] = [{"phone": "b", "action": "pause", "at_s": 2}]
        with self.assertRaises(ValueError):
            runner.validate_case(case, self.devices("ab"))

    def test_presentation_observer_requires_boolean_and_is_receiver_only(self):
        case = {**self.case([{"phone": "b", "parent": "a"}]), "video_presentation_timing": True}
        for value in ("true", 1):
            with self.assertRaises(ValueError):
                runner.validate_case({**case, "video_presentation_timing": value}, self.devices("ab"))
        self.assertEqual(runner.validate_case(case, self.devices("ab")), set("ab"))
        for enabled in (False, True):
            instance = runner.Runner("adb", self.devices("ab"), {**case, "video_presentation_timing": enabled}, Path("unused"))
            instance.phone_time = Mock(return_value="10-03 17:00:00.000")
            instance.adb = Mock(return_value="123")
            with patch.object(Path, "open", mock_open()), patch.object(runner.subprocess, "Popen"):
                instance.launch("a", source=True)
                instance.launch("b")
            launches = [call.args for call in instance.adb.call_args_list if "start" in call.args]
            self.assertNotIn("test_video_presentation_timing", launches[0])
            if enabled:
                self.assertEqual(launches[1][launches[1].index("test_video_presentation_timing") + 1], "true")
            else:
                self.assertNotIn("test_video_presentation_timing", launches[1])

    def test_phone_tunables_reach_only_their_own_phones_launch(self):
        case = {**self.case([{"phone": "b", "parent": "a"}]),
                "tunables": {"stream_buffer_kib": 64}, "phone_tunables": {"b": {"names_before_answer": True}}}
        self.assertEqual(set("ab"), runner.validate_case(case, self.devices("ab")))
        instance = runner.Runner("adb", self.devices("ab"), case, Path("unused"))
        instance.phone_time = Mock(return_value="10-03 17:00:00.000")
        instance.adb = Mock(return_value="123")
        with patch.object(Path, "open", mock_open()), patch.object(runner.subprocess, "Popen"):
            instance.launch("a", source=True)
            instance.launch("b")
        launches = [call.args for call in instance.adb.call_args_list if "start" in call.args]
        for argv in launches:
            self.assertEqual("64", argv[argv.index("test_stream_buffer_kib") + 1])
        self.assertNotIn("test_names_before_answer", launches[0])
        self.assertEqual("true", launches[1][launches[1].index("test_names_before_answer") + 1])

    def test_phone_tunables_are_validated_like_shared_tunables(self):
        base = self.case([{"phone": "b", "parent": "a"}])
        for bad in ({"c": {"names_before_answer": True}}, {"b": {"no_such_tunable": True}},
                    {"b": {"names_before_answer": "yes"}}, {"b": True}, ["b"]):
            with self.assertRaises(ValueError):
                runner.validate_case({**base, "phone_tunables": bad}, self.devices("abc"))

    def test_output_hold_requires_traces_and_reaches_receivers_only(self):
        case = {**self.case([{"phone": "b", "parent": "a"}]), "video_presentation_timing": True}
        for value in ("true", 1):
            with self.assertRaises(ValueError):
                runner.validate_case({**case, "release_video_at_playout": value}, self.devices("ab"))
        with self.assertRaises(ValueError):
            runner.validate_case({**case, "video_presentation_timing": False, "release_video_at_playout": True},
                                 self.devices("ab"))
        for enabled in (None, False, True):
            request = dict(case)
            if enabled is not None:
                request["release_video_at_playout"] = enabled
            runner.validate_case(request, self.devices("ab"))
            instance = runner.Runner("adb", self.devices("ab"), request, Path("unused"))
            instance.phone_time = Mock(return_value="10-03 18:00:00.000")
            instance.adb = Mock(return_value="123")
            with patch.object(Path, "open", mock_open()), patch.object(runner.subprocess, "Popen"):
                instance.launch("a", source=True)
                instance.launch("b")
            launches = [call.args for call in instance.adb.call_args_list if "start" in call.args]
            self.assertNotIn("test_release_video_at_playout", launches[0])
            if enabled:
                self.assertEqual(launches[1][launches[1].index("test_release_video_at_playout") + 1], "true")
            else:
                self.assertNotIn("test_release_video_at_playout", launches[1])

    def test_settings_requests_cannot_be_silently_clamped_or_defaulted(self):
        for field, value in [("fps", 15.5), ("bitrate_kbps", 1500),
                             ("playback", "medium"), ("rate_control", "manual")]:
            case = self.case([{"phone": "b", "parent": "a"}])
            case["profile"][field] = value
            with self.assertRaises(ValueError):
                runner.validate_case(case, self.devices("ab"))

    def test_open_intentional_intervals_are_retained_separately(self):
        case = self.case([{"phone": "b", "parent": "a"}])
        instance = runner.Runner("adb", self.devices("ab"), case, Path("unused"))
        instance.active_intervals = {("a", action): {"device": "a", "action": action, "start": "10-02 20:00:00.000"}
                                     for action in ("background", "pause")}
        instance.close_intervals("a", "10-02 20:01:00.000")
        self.assertEqual(len(instance.intervals), 2)
        self.assertFalse(instance.active_intervals)

    def test_app_death_is_not_confused_with_wireless_adb_loss(self):
        instance = runner.Runner("adb", self.devices("ab"), self.case([{"phone": "b", "parent": "a"}]), Path("unused"))
        instance.pids["b"] = "123"
        instance.adb = Mock(side_effect=["device", "456"])
        with self.assertRaisesRegex(RuntimeError, "application exited or restarted"):
            instance.health_check("b")
        instance.adb = Mock(return_value="device")
        instance.expected_stopped.add("b")
        instance.health_check("b")
        self.assertEqual(instance.adb.call_count, 1)

    def test_deliberate_parent_loss_does_not_alarm_but_a_later_failure_does(self):
        case = self.case([{"phone": "b", "parent": "a"}, {"phone": "c", "parent": "b"}])
        with tempfile.TemporaryDirectory() as directory:
            instance = runner.Runner("adb", self.devices("abc"), case, Path(directory))
            instance.phone_time = Mock(return_value="10-02 22:56:02.098")
            instance.adb = Mock(return_value="")
            instance.logs = [{"device": "b"}, {"device": "c", "path": "c.log"}]
            instance.action({"phone": "b", "action": "kill"})
            instance.pids["c"] = "8053"
            instance.adb = Mock(side_effect=["device", "8053"])
            instance.captures = [(Mock(), None), (Mock(poll=Mock(return_value=None)), None)]
            (Path(directory) / "c.log").write_text(
                "10-02 22:56:02.482 8053 8053 I BluetoothVideo: Connection lost. Finding another route...\n"
                "10-02 22:56:07.292 8053 8053 I BluetoothVideo: Receiver startup: selection-to-first-surface 4647.7 ms\n"
                "10-02 22:56:10.000 8053 8053 I BluetoothVideo: Connection lost. Finding another route...\n",
                encoding="utf-8")
            instance.beep = Mock()
            instance.health_check("c")
            self.assertEqual([event["planned"] for event in instance.health_events], [True, False])
            self.assertEqual(instance.health_events[0]["removed_ancestor"], "b")
            instance.beep.assert_called_once()

    def test_earlier_loss_or_fatal_error_is_never_hidden_by_a_planned_kill(self):
        for message in ["10-02 22:56:01.900 8053 8053 I BluetoothVideo: Connection lost",
                        "10-02 22:56:02.500 8053 8053 E AndroidRuntime: FATAL EXCEPTION: main",
                        "10-02 22:56:33.000 8053 8053 I BluetoothVideo: Connection lost"]:
            with tempfile.TemporaryDirectory() as directory:
                instance = runner.Runner("adb", self.devices("ab"), self.case([{"phone": "b", "parent": "a"}]), Path(directory))
                instance.pending_planned_losses["b"] = {"time": "10-02 22:56:02.098", "removed_ancestor": "a"}
                instance.pids["b"] = "8053"
                instance.logs = [{"device": "b", "path": "b.log"}]
                instance.captures = [(Mock(poll=Mock(return_value=None)), None)]
                instance.adb = Mock(side_effect=["device", "8053"])
                (Path(directory) / "b.log").write_text(message + "\n", encoding="utf-8")
                instance.beep = Mock()
                instance.health_check("b")
                self.assertFalse(instance.health_events[0]["planned"])
                instance.beep.assert_called_once()

    def test_oem_input_restriction_only_skips_wake_when_screen_is_unlocked(self):
        for policy, proceeds in [("showing=false\nscreenState=SCREEN_STATE_ON", True),
                                 ("showing=true\nscreenState=SCREEN_STATE_ON", False),
                                 ("showing=false\nscreenState=SCREEN_STATE_OFF", False)]:
            instance = runner.Runner("adb", self.devices("ab"), self.case([{"phone": "b", "parent": "a"}]), Path("unused"))
            instance.phone_time = Mock(return_value="10-02 20:00:00.000")
            instance.adb = Mock(side_effect=["", RuntimeError("SecurityException: INJECT_EVENTS"), policy, "", "123"])
            with patch.object(Path, "open", mock_open()), patch.object(runner.subprocess, "Popen"):
                if proceeds:
                    instance.launch("b")
                    self.assertEqual(instance.pids["b"], "123")
                    self.assertEqual(instance.events[0]["action"], "already_unlocked")
                else:
                    with self.assertRaisesRegex(RuntimeError, "unlock the phone"):
                        instance.launch("b")

    def test_broadcast_control_waits_for_delayed_application_ack(self):
        instance = runner.Runner("adb", self.devices("ab"), self.case([{"phone": "b", "parent": "a"}]), Path("unused"))
        instance.pids["a"] = "123"
        instance.phone_time = Mock(return_value="10-02 20:00:00.000")
        instance.adb = Mock(side_effect=["Activity started", "", "BENCHMARK control end applied"])
        with patch.object(runner.time, "sleep"), patch.object(runner.time, "monotonic", side_effect=[0, 0, 0.3, 0.8]):
            instance.action({"phone": "a", "action": "end"})
        self.assertEqual(instance.adb.call_count, 3)

    def test_receiver_restart_during_pause_waits_for_paused_authentication(self):
        for paused in (False, True):
            instance = runner.Runner("adb", self.devices("ab"), self.case([{"phone": "b", "parent": "a"}]), Path("unused"))
            instance.phone_time = Mock(return_value="10-02 20:00:00.000")
            instance.launch = Mock()
            instance.wait_first_frame = Mock()
            instance.wait_paused_connection = Mock()
            if paused:
                instance.active_intervals[("a", "pause")] = {"device": "a", "action": "pause"}
            instance.action({"phone": "b", "action": "restart_receiver"})
            self.assertEqual(instance.wait_first_frame.call_count, 0 if paused else 1)
            self.assertEqual(instance.wait_paused_connection.call_count, 1 if paused else 0)
            self.assertEqual(("a", "pause") in instance.active_intervals, paused)

    def test_paused_join_requires_the_authenticated_original_and_parent(self):
        instance = runner.Runner("adb", self.devices("ab"), self.case([{"phone": "b", "parent": "a"}]), Path("unused"))
        instance.logs = [{"device": "b", "path": "b.log"}]
        instance.phone_time = Mock(return_value="10-02 20:00:00.000")
        identity = "Authenticated stream identity: original=000000000000 | parent=000000000000 | stream=direct"
        with patch.object(Path, "read_text", side_effect=[
            identity.replace("parent=000000000000", "parent=000000000099") + "\nStream paused by broadcaster.",
            identity,
            identity + "\nStream paused by broadcaster.",
        ]) as read, patch.object(runner.time, "sleep"), patch.object(runner.time, "monotonic", side_effect=[0, 0, 1, 2]):
            instance.wait_paused_connection("b")
        self.assertEqual(read.call_count, 3)
        self.assertEqual(instance.events[-1]["action"], "authenticated_paused_join")

    def test_setup_timeouts_raise_before_timed_controls_and_retain_the_reason(self):
        for method, action in (("wait_first_frame", "connection_timeout"),
                               ("wait_paused_connection", "paused_connection_timeout")):
            with self.subTest(method=method):
                instance = runner.Runner("adb", self.devices("ab"), self.case([{"phone": "b", "parent": "a"}]), Path("unused"))
                instance.logs = [{"device": "b", "path": "b.log"}]
                instance.phone_time = Mock(return_value="10-05 00:00:00.000")
                instance.beep = Mock()
                with patch.object(runner.time, "monotonic", side_effect=[0, 31]):
                    with self.assertRaisesRegex(RuntimeError, "connection timeout") as error:
                        getattr(instance, method)("b")
                self.assertEqual(action, instance.events[-1]["action"])
                self.assertEqual(str(error.exception), instance.events[-1]["reason"])

    def failed_raw_setup(self, directory):
        fixture = Path(directory) / "source.yuv"
        fixture.write_bytes(bytes(12))
        case = self.case([{"phone": "b", "parent": "a"}])
        case.update(raw_fixture_file=str(fixture), media_trace=True,
                    actions=[{"phone": "a", "action": "pause", "at_s": 0}])
        case["profile"].update(width=2, height=2)
        instance = runner.Runner("mock-adb", self.devices("ab"), case, Path(directory) / "run")
        instance.inventory = Mock(return_value={})
        def launch(phone, source=False):
            instance.pids[phone] = "123" if source else "456"
            instance.logs.append({"device": phone, "pid": int(instance.pids[phone])})
        instance.launch = Mock(side_effect=launch)
        instance.wait_first_frame = Mock(side_effect=RuntimeError("first surface setup failed"))
        instance.phone_time = Mock(return_value="10-05 00:00:00.000")
        instance.adb = Mock(return_value="123")
        instance.beep = Mock()
        return instance

    def test_failed_setup_ends_owned_source_before_trace_collection_and_keeps_original_error(self):
        for end_fails in (False, True):
            with self.subTest(end_fails=end_fails), tempfile.TemporaryDirectory() as directory:
                instance = self.failed_raw_setup(directory)
                order = []
                def action(event):
                    order.append(event["action"])
                    if end_fails:
                        raise RuntimeError("End acknowledgement unavailable")
                instance.action = Mock(side_effect=action)
                instance.collect_media_traces = Mock(side_effect=lambda *args: order.append("traces"))
                fake_scorer = Mock(score_manifest=Mock(return_value={"overall_status": "FAIL"}))
                with patch.object(runner.time, "sleep"), patch.dict("sys.modules", {"score_device_run": fake_scorer}):
                    manifest = instance.run()
                self.assertEqual("first surface setup failed", manifest["runner_error"])
                self.assertEqual(["end", "traces", "traces"], order)
                self.assertEqual(end_fails, "trace_finalization_error" in manifest)
                if end_fails:
                    self.assertIn("End acknowledgement", manifest["trace_finalization_error"])

    def test_failed_cleanup_does_not_control_a_replacement_source_pid(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.failed_raw_setup(directory)
            instance.adb = Mock(return_value="999")
            instance.action = Mock()
            instance.collect_media_traces = Mock()
            fake_scorer = Mock(score_manifest=Mock(return_value={"overall_status": "FAIL"}))
            with patch.dict("sys.modules", {"score_device_run": fake_scorer}):
                manifest = instance.run()
            instance.action.assert_not_called()
            self.assertEqual("first surface setup failed", manifest["runner_error"])
            self.assertIn("PID", manifest["trace_finalization_error"])
            self.assertEqual(2, instance.collect_media_traces.call_count)

    def test_acknowledged_end_is_not_duplicated_after_a_later_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.failed_raw_setup(directory)
            instance.adb = Mock(side_effect=lambda phone, *args, **kwargs:
                                "BENCHMARK control end applied" if args[0] == "logcat" else "123")
            def wait(phone):
                instance.action({"phone": "a", "action": "end"})
                raise RuntimeError("failure after acknowledged End")
            instance.wait_first_frame = Mock(side_effect=wait)
            instance.collect_media_traces = Mock()
            fake_scorer = Mock(score_manifest=Mock(return_value={"overall_status": "FAIL"}))
            with patch.object(runner.time, "monotonic", return_value=0), \
                    patch.dict("sys.modules", {"score_device_run": fake_scorer}):
                manifest = instance.run()
            end_commands = [call for call in instance.adb.call_args_list
                            if "test_control" in call.args and "end" in call.args]
            self.assertEqual(1, len(end_commands))
            self.assertNotIn("trace_finalization_error", manifest)

    def test_replay_must_cover_measurement_and_connection_not_silently_freeze(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.replay(Path(directory) / "fixture.ivf", frames=300)
            case = self.case([{"phone": "b", "parent": "a"}])
            case["replay_file"] = str(fixture)
            with self.assertRaisesRegex(ValueError, "shorter than measurement"):
                runner.validate_case(case, self.devices("ab"))
            self.replay(fixture, frames=2250)
            self.assertEqual(runner.validate_case(case, self.devices("ab")), set("ab"))

    def test_replay_guard_and_complete_native_container_are_checked_offline(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.replay(Path(directory) / "fixture.ivf")
            case = {**self.case([{"phone": "b", "parent": "a"}]), "replay_file": str(fixture),
                    "replay_sha256": hashlib.sha256(fixture.read_bytes()).hexdigest()}
            with patch.object(runner.subprocess, "run", side_effect=AssertionError("device I/O")), \
                 patch.object(runner.subprocess, "Popen", side_effect=AssertionError("device process")):
                record = runner.replay_fixture_metadata(case)
                self.assertEqual(record["sha256"], case["replay_sha256"])
                self.assertEqual((record["format"], record["codec"], record["frames"], record["bytes"], record["loops"]),
                                 ("IVF", "VP90", 1501, fixture.stat().st_size, False))
                for defect in ("hash", "truncated", "count", "geometry", "fps", "fractional", "codec", "timestamps", "timing_overflow"):
                    with self.subTest(defect=defect):
                        self.replay(fixture)
                        content = bytearray(fixture.read_bytes())
                        guarded = dict(case)
                        if defect == "hash":
                            content[-1] = 1
                        else:
                            guarded.pop("replay_sha256")
                            if defect == "truncated":
                                content.pop()
                            elif defect == "count":
                                struct.pack_into("<I", content, 24, 1502)
                            elif defect == "geometry":
                                struct.pack_into("<H", content, 12, 1280)
                            elif defect == "fps":
                                struct.pack_into("<I", content, 16, 30)
                            elif defect == "fractional":
                                struct.pack_into("<II", content, 16, 30000, 1001)
                            elif defect == "codec":
                                content[8:12] = b"VP80"
                            elif defect == "timing_overflow":
                                struct.pack_into("<Q", content, 32 + 13 * 1500 + 4, 2 ** 63 - 1)
                            else:
                                struct.pack_into("<Q", content, 32 + 13 * 2 + 4, 0)
                        fixture.write_bytes(content)
                        with self.assertRaises(ValueError):
                            runner.Runner("adb", self.devices("ab"), guarded, Path(directory) / "unused")

    def test_replay_staging_verifies_remote_bytes_and_never_launches_changed_input(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.replay(Path(directory) / "fixture.ivf")
            case = {**self.case([{"phone": "b", "parent": "a"}]), "replay_file": str(fixture)}
            instance = runner.Runner("adb", self.devices("ab"), case, Path(directory) / "run")
            digest = hashlib.sha256(fixture.read_bytes()).hexdigest()
            instance.adb = Mock(side_effect=["", digest + " file"])
            instance.phone_time = Mock(return_value="10-04 00:00:00.000")
            remote = instance.stage_replay_fixture("a")
            self.assertEqual(instance.adb.call_args_list[0].args, ("a", "push", str(fixture.resolve()), remote))
            self.assertIn(digest, remote)
            self.assertEqual(instance.replay_fixture["staging"]["sha256"], digest)
            self.assertEqual(instance.replay_fixture["staging"]["status"], "VERIFIED")
            instance.adb = Mock(side_effect=["", "f" * 64 + " file"])
            with self.assertRaisesRegex(RuntimeError, "uploaded replay.*hash mismatch"):
                instance.stage_replay_fixture("a")
            self.assertNotEqual("VERIFIED", instance.replay_fixture["staging"]["status"])
            self.replay(fixture)
            content = fixture.read_bytes()
            fixture.write_bytes(content[:-1] + b"\x01")
            instance.adb = Mock(side_effect=AssertionError("Changed input touched a device"))
            with patch.object(runner.subprocess, "Popen", side_effect=AssertionError("device process")):
                with self.assertRaisesRegex(RuntimeError, "Replay input changed"):
                    instance.launch("a", source=True)
            instance.adb.assert_not_called()

    def test_new_replay_manifest_keeps_actual_finite_input_and_staging_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.replay(Path(directory) / "fixture.ivf")
            case = {**self.case([{"phone": "b", "parent": "a"}]), "duration_s": 1, "replay_file": str(fixture)}
            instance = runner.Runner("adb", self.devices("ab"), case, Path(directory) / "run")
            instance.inventory = Mock(return_value={})
            def launch(phone, source=False):
                if source:
                    instance.stage_replay_fixture(phone)
            instance.launch = Mock(side_effect=launch)
            instance.wait_first_frame = Mock()
            instance.action = Mock()
            instance.adb = Mock(side_effect=lambda *args, **kwargs: instance.replay_fixture["sha256"] + " file"
                                if "sha256sum" in args else "")
            instance.phone_time = Mock(return_value="10-04 00:00:00.000")
            fake_scorer = Mock(score_manifest=Mock(return_value={"overall_status": "UNMEASURED"}))
            with patch.object(runner.subprocess, "run", side_effect=AssertionError("device I/O")), \
                 patch.object(runner.subprocess, "Popen", side_effect=AssertionError("device process")), \
                 patch.object(runner.time, "monotonic", side_effect=[0, 2]), patch.object(runner.time, "sleep"), \
                 patch.dict("sys.modules", {"score_device_run": fake_scorer}):
                manifest = instance.run()
            self.assertEqual("preencoded_video_only", manifest["source_kind"])
            self.assertEqual(case, manifest["scenario"])
            self.assertEqual(instance.replay_fixture, manifest["replay_fixture"])
            self.assertEqual(1501, manifest["replay_fixture"]["frames"])
            self.assertEqual("a", manifest["replay_fixture"]["staging"]["device"])

    def test_rejected_control_is_not_misreported_as_acknowledgement_timeout(self):
        instance = runner.Runner("adb", self.devices("ab"), self.case([{"phone": "b", "parent": "a"}]), Path("unused"))
        instance.pids["a"] = "123"
        instance.phone_time = Mock(return_value="10-02 20:00:00.000")
        instance.adb = Mock(side_effect=["Activity started", "BENCHMARK control end rejected"])
        with self.assertRaisesRegex(RuntimeError, "explicitly rejected control end"):
            instance.action({"phone": "a", "action": "end"})

    def test_diagnostic_audio_and_flush_are_exact_source_only_extras(self):
        case = self.case([{"phone": "b", "parent": "a"}])
        case.update(audio_enabled=False, stream_flush="frame")
        for phone, source in [("a", True), ("b", False)]:
            instance = runner.Runner("adb", self.devices("ab"), case, Path("unused"))
            instance.phone_time = Mock(return_value="10-02 20:00:00.000")
            instance.adb = Mock(side_effect=["", "", "", "", "123"])
            with patch.object(Path, "open", mock_open()), patch.object(runner.subprocess, "Popen"):
                instance.launch(phone, source=source)
            launch = instance.adb.call_args_list[3].args
            if source:
                self.assertEqual(launch[launch.index("test_audio") + 1], "false")
                self.assertEqual(launch[launch.index("test_stream_flush") + 1], "frame")
            else:
                self.assertNotIn("test_audio", launch)
                self.assertNotIn("test_stream_flush", launch)
        for field, value in [("audio_enabled", "false"), ("stream_flush", "every_frame")]:
            case[field] = value
            with self.assertRaises(ValueError):
                runner.validate_case(case, self.devices("ab"))
            case.update(audio_enabled=False, stream_flush="frame")

    def test_forwarder_priority_override_is_exact_and_only_sent_to_its_middle(self):
        case = self.case([{"phone": "b", "parent": "a", "forwarder_gatt_priority": "dck"},
                          {"phone": "c", "parent": "b"}])
        for phone, source in [("a", True), ("b", False), ("c", False)]:
            instance = runner.Runner("adb", self.devices("abc"), case, Path("unused"))
            instance.phone_time = Mock(return_value="10-02 20:00:00.000")
            instance.adb = Mock(side_effect=["", "", "", "", "123"])
            with patch.object(Path, "open", mock_open()), patch.object(runner.subprocess, "Popen"):
                instance.launch(phone, source=source)
            launch = instance.adb.call_args_list[3].args
            if phone == "b":
                self.assertEqual(launch[launch.index("test_forwarder_gatt_priority") + 1], "dck")
            else:
                self.assertNotIn("test_forwarder_gatt_priority", launch)
        for priority in ("high", "dck"):
            case["receivers"][0]["forwarder_gatt_priority"] = priority
            self.assertEqual(runner.validate_case(case, self.devices("abc")), set("abc"))

    def test_forwarder_priority_rejects_typos_leaf_and_other_transports(self):
        for receivers, transport in [
            ([{"phone": "b", "parent": "a", "forwarder_gatt_priority": "fast"},
              {"phone": "c", "parent": "b"}], "gatt"),
            ([{"phone": "b", "parent": "a", "forwarder_gatt_priority": "high"}], "gatt"),
            ([{"phone": "b", "parent": "a", "forwarder_gatt_priority": "dck"},
              {"phone": "c", "parent": "b"}], "classic")]:
            case = self.case(receivers)
            case["transport"] = transport
            with self.assertRaises(ValueError):
                runner.validate_case(case, self.devices("abc"))

    def test_empty_relay_diagnostic_enables_forwarding_without_creating_child(self):
        case = self.case([{"phone": "b", "parent": "a", "forwarding_enabled": True}])
        self.assertEqual(runner.validate_case(case, self.devices("ab")), set("ab"))
        instance = runner.Runner("adb", self.devices("ab"), case, Path("unused"))
        instance.phone_time = Mock(return_value="10-02 20:00:00.000")
        instance.adb = Mock(side_effect=["", "", "", "", "123"])
        with patch.object(Path, "open", mock_open()), patch.object(runner.subprocess, "Popen"):
            instance.launch("b")
        launch = instance.adb.call_args_list[3].args
        self.assertEqual(launch[launch.index("test_forward_received_streams") + 1], "true")
        self.assertEqual(case["receivers"], [{"phone": "b", "parent": "a", "forwarding_enabled": True}])

    def test_direct_gatt_heartbeat_diagnostic_reaches_both_endpoints(self):
        case = self.case([{"phone": "b", "parent": "a", "forwarding_enabled": False}])
        for enabled in (False, True):
            case["direct_gatt_heartbeat"] = enabled
            for phone, source in [("a", True), ("b", False)]:
                instance = runner.Runner("adb", self.devices("ab"), case, Path("unused"))
                instance.phone_time = Mock(return_value="10-02 20:00:00.000")
                instance.adb = Mock(side_effect=["", "", "", "", "123"])
                with patch.object(Path, "open", mock_open()), patch.object(runner.subprocess, "Popen"):
                    instance.launch(phone, source=source)
                launch = instance.adb.call_args_list[3].args
                self.assertEqual(launch[launch.index("test_direct_gatt_heartbeat") + 1], str(enabled).lower())

    def test_direct_gatt_heartbeat_diagnostic_rejects_role_and_transport_changes(self):
        for receivers, transport, enabled in [
            ([{"phone": "b", "parent": "a"}], "gatt", "true"),
            ([{"phone": "b", "parent": "a"}], "classic", True),
            ([{"phone": "b", "parent": "a", "forwarding_enabled": True}], "gatt", True),
            ([{"phone": "b", "parent": "a"}, {"phone": "c", "parent": "b"}], "gatt", True),
        ]:
            case = {**self.case(receivers), "transport": transport, "direct_gatt_heartbeat": enabled}
            with self.assertRaises(ValueError):
                runner.validate_case(case, self.devices("abc"))

    def test_gatt_write_timing_is_explicit_and_source_only(self):
        case = self.case([{"phone": "b", "parent": "a", "forwarding_enabled": False}])
        for enabled in (False, True):
            case["gatt_write_timing"] = enabled
            for phone, source in [("a", True), ("b", False)]:
                instance = runner.Runner("adb", self.devices("ab"), case, Path("unused"))
                instance.phone_time = Mock(return_value="10-02 20:00:00.000")
                instance.adb = Mock(side_effect=["", "", "", "", "123"])
                with patch.object(Path, "open", mock_open()), patch.object(runner.subprocess, "Popen"):
                    instance.launch(phone, source=source)
                launch = instance.adb.call_args_list[3].args
                if source:
                    self.assertEqual(launch[launch.index("test_gatt_write_timing") + 1], str(enabled).lower())
                else:
                    self.assertNotIn("test_gatt_write_timing", launch)

    def test_gatt_write_timing_rejects_ambiguous_type_and_uninstrumented_transports(self):
        for receivers, transport, enabled in [
            ([{"phone": "b", "parent": "a"}], "gatt", "true"),
            ([{"phone": "b", "parent": "a"}], "classic", True),
            ([{"phone": "b", "parent": "a"}], "ble", True),
            ([{"phone": "b", "parent": "a"}, {"phone": "c", "parent": "b"}], "gatt", True),
        ]:
            case = {**self.case(receivers), "transport": transport, "gatt_write_timing": enabled}
            with self.assertRaises(ValueError):
                runner.validate_case(case, self.devices("abc"))

    def test_forwarding_diagnostic_rejects_ambiguous_boolean_transport_and_false_middle(self):
        for receivers, transport in [
            ([{"phone": "b", "parent": "a", "forwarding_enabled": "true"}], "gatt"),
            ([{"phone": "b", "parent": "a", "forwarding_enabled": True}], "classic"),
            ([{"phone": "b", "parent": "a", "forwarding_enabled": False},
              {"phone": "c", "parent": "b"}], "gatt")]:
            case = self.case(receivers)
            case["transport"] = transport
            with self.assertRaises(ValueError):
                runner.validate_case(case, self.devices("abc"))

    def test_full_slot_scan_pause_is_explicit_for_classic_and_gatt_only(self):
        case = self.case([{"phone": "b", "parent": "a"}])
        case["viewer_scan_mode"] = "off_when_full"
        for transport in ("classic", "gatt"):
            case["transport"] = transport
            self.assertEqual(runner.validate_case(case, self.devices("ab")), set("ab"))
        case["transport"] = "ble"
        with self.assertRaises(ValueError):
            runner.validate_case(case, self.devices("ab"))
        case["viewer_scan_mode"] = "off"
        with self.assertRaises(ValueError):
            runner.validate_case(case, self.devices("ab"))

    def test_unsupported_scan_modes_are_rejected_instead_of_silent_fallback(self):
        case = self.case([{"phone": "b", "parent": "a"}])
        for transport in ("gatt", "ble"):
            case["transport"] = transport
            for mode in ("balanced", "low_latency"):
                case["viewer_scan_mode"] = mode
                with self.assertRaises(ValueError):
                    runner.validate_case(case, self.devices("ab"))

    def test_gatt_scan_pause_extra_is_source_only_and_absent_by_default(self):
        for override in (None, "off_when_full"):
            case = self.case([{"phone": "b", "parent": "a"}])
            if override:
                case["viewer_scan_mode"] = override
            for phone, source in (("a", True), ("b", False)):
                instance = runner.Runner("adb", self.devices("ab"), case, Path("unused"))
                instance.phone_time = Mock(return_value="10-02 20:00:00.000")
                instance.adb = Mock(side_effect=["", "", "", "", "123"])
                with patch.object(Path, "open", mock_open()), patch.object(runner.subprocess, "Popen"):
                    instance.launch(phone, source=source)
                launch = instance.adb.call_args_list[3].args
                if source and override:
                    self.assertEqual(launch[launch.index("test_viewer_scan_mode") + 1], override)
                else:
                    self.assertNotIn("test_viewer_scan_mode", launch)

    def test_frozen_raw_input_rejects_changed_bytes_before_any_device_operation(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "native.yuv"
            content = bytes(640 * 360 * 3 // 2)
            fixture.write_bytes(content)
            case = {**self.case([{"phone": "b", "parent": "a"}]), "raw_fixture_file": str(fixture),
                    "raw_fixture_sha256": hashlib.sha256(content).hexdigest(),
                    "raw_fixture_metadata": {"color": {"range": "limited"}}}
            self.assertEqual(runner.raw_fixture_metadata(case)["declared_metadata"], case["raw_fixture_metadata"])
            fixture.write_bytes(b"\x01" + content[1:])
            with patch.object(runner.subprocess, "run", side_effect=AssertionError("device I/O")):
                with self.assertRaisesRegex(ValueError, "frozen case SHA256"):
                    runner.Runner("adb", self.devices("ab"), case, Path("unused"))
            for bad in (None, "bad", 3):
                case["raw_fixture_sha256"] = bad
                with self.assertRaisesRegex(ValueError, "Raw fixture SHA256"):
                    runner.validate_case(case, self.devices("ab"))

    def test_raw_fixture_rejects_wrong_geometry_and_mixed_source_modes_before_device_actions(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "motion.yuv"
            fixture.write_bytes(b"\x00" * (640 * 360 * 3 // 2))
            case = self.case([{"phone": "b", "parent": "a"}])
            case["raw_fixture_file"] = str(fixture)
            self.assertEqual(runner.validate_case(case, self.devices("ab")), set("ab"))
            fixture.write_bytes(fixture.read_bytes() + b"\x00")
            with self.assertRaisesRegex(ValueError, "whole.*I420"):
                runner.validate_case(case, self.devices("ab"))
            fixture.write_bytes(b"\x00" * (640 * 360 * 3 // 2))
            case["replay_file"] = str(fixture)
            with self.assertRaisesRegex(ValueError, "mutually exclusive"):
                runner.validate_case(case, self.devices("ab"))
            case.pop("replay_file")
            case["profile"]["width"] = 639
            with self.assertRaisesRegex(ValueError, "even"):
                runner.validate_case(case, self.devices("ab"))

    def test_raw_fixture_launch_is_selected_encoder_with_independent_audio_and_source_only_input(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "motion.yuv"
            fixture.write_bytes(b"\x00" * (640 * 360 * 3 // 2))
            case = self.case([{"phone": "b", "parent": "a"}])
            case.update(raw_fixture_file=str(fixture), audio_enabled=True)
            case["profile"]["codec"] = "exact.offered.encoder"
            for phone, source in [("a", True), ("b", False)]:
                instance = runner.Runner("adb", self.devices("ab"), case, Path(directory) / "run")
                instance.phone_time = Mock(return_value="10-03 00:01:00.000")
                instance.adb = Mock(side_effect=lambda *args, **kwargs: "123" if "pidof" in args else
                                    instance.raw_fixture["sha256"] + "  file" if "sha256sum" in args else "")
                with patch.object(Path, "open", mock_open()), patch.object(runner.subprocess, "Popen"):
                    instance.launch(phone, source=source)
                launch = next(call.args for call in instance.adb.call_args_list if runner.COMPONENT in call.args)
                self.assertEqual(launch[launch.index("test_codec") + 1], "exact.offered.encoder")
                if source:
                    self.assertEqual(launch[launch.index("test_audio") + 1], "true")
                    self.assertIn("test_raw_yuv", launch)
                    self.assertTrue(launch[launch.index("test_raw_yuv") + 1].endswith(".yuv"))
                    # The declared geometry travels with the file: the app never infers it from the encoder.
                    self.assertEqual(launch[launch.index("test_raw_width") + 1], "640")
                    self.assertEqual(launch[launch.index("test_raw_height") + 1], "360")
                    self.assertTrue(any(call.args[1] == "push" for call in instance.adb.call_args_list))
                else:
                    self.assertNotIn("test_raw_yuv", launch)
                    self.assertNotIn("test_raw_width", launch)

    def test_raw_manifest_retains_exact_fixture_identity_and_distinct_scope(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "motion.yuv"
            fixture.write_bytes(b"\x01" * (640 * 360 * 3 // 2) * 2)
            case = self.case([{"phone": "b", "parent": "a"}])
            case.update(raw_fixture_file=str(fixture), duration_s=1)
            instance = runner.Runner("adb", self.devices("ab"), case, Path(directory) / "run")
            instance.inventory = Mock(return_value={})
            instance.launch = Mock()
            instance.wait_first_frame = Mock()
            instance.action = Mock()
            instance.adb = Mock(return_value="")
            instance.phone_time = Mock(return_value="10-03 00:01:00.000")
            fake_scorer = Mock(score_manifest=Mock(return_value={"overall_status": "UNMEASURED"}))
            with patch.object(runner.time, "monotonic", side_effect=[0, 2]), patch.object(runner.time, "sleep"), \
                    patch.dict("sys.modules", {"score_device_run": fake_scorer}):
                manifest = instance.run()
            self.assertEqual(manifest["source_kind"], "raw_fixture_live_encoder")
            self.assertEqual(manifest["run_id"], instance.run_id)
            self.assertEqual(manifest["source_fixture"]["sha256"], hashlib.sha256(fixture.read_bytes()).hexdigest())
            self.assertEqual(manifest["source_fixture"]["frames"], 2)
            self.assertTrue(manifest["source_fixture"]["loops"])

    def test_raw_cache_requires_exact_remote_hash_and_verifies_upload(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "motion.yuv"
            fixture.write_bytes(b"\x01" * (640 * 360 * 3 // 2))
            case = self.case([{"phone": "b", "parent": "a"}])
            case["raw_fixture_file"] = str(fixture)
            instance = runner.Runner("adb", self.devices("ab"), case, Path(directory) / "run")
            instance.phone_time = Mock(return_value="10-03 00:01:00.000")
            digest = instance.raw_fixture["sha256"]
            instance.adb = Mock(return_value=digest + "  cached.yuv")
            remote = instance.stage_raw_fixture("a")
            self.assertIn(digest, remote)
            self.assertEqual(instance.adb.call_count, 1)
            self.assertEqual(instance.events[-1]["action"], "raw_fixture_reused")
            instance.adb = Mock(side_effect=["0" * 64 + "  bad.yuv", "", digest + "  cached.yuv"])
            self.assertEqual(instance.stage_raw_fixture("a"), remote)
            self.assertEqual(instance.adb.call_args_list[1].args[1], "push")
            self.assertEqual(instance.events[-1]["action"], "raw_fixture_uploaded")
            instance.adb = Mock(side_effect=["", "", "0" * 64 + "  corrupted.yuv"])
            with self.assertRaisesRegex(RuntimeError, "hash mismatch"):
                instance.stage_raw_fixture("a")

    def test_input_folder_is_created_by_the_app_and_shell_owned_folder_blocks(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = runner.Runner("adb", self.devices("ab"), self.case([{"phone": "b", "parent": "a"}]),
                                     Path(directory) / "run")
            instance.adb = Mock(return_value="u0_a489")
            instance.prepare_app_files_dir("a")
            command = instance.adb.call_args.args[2]
            self.assertTrue(command.startswith(f"run-as {runner.PACKAGE} mkdir -p "))
            self.assertEqual(instance.events[-1], {"device": "a", "action": "app_files_dir_ready", "owner": "u0_a489"})
            instance.adb = Mock(return_value="shell")
            with self.assertRaisesRegex(RuntimeError, "owned by shell"):
                instance.prepare_app_files_dir("a")
            # HyperOS: run-as fails, one app launch creates the folder.
            instance.adb = Mock(side_effect=[RuntimeError("run-as: Permission denied"), "", "", "u0_a59"])
            instance.prepare_app_files_dir("a")
            self.assertEqual(instance.adb.call_args_list[1].args[1:4], ("shell", "am", "start"))
            self.assertEqual(instance.events[-1]["owner"], "u0_a59")

    def trace_instance(self, directory):
        case = self.case([{"phone": "b", "parent": "a"}])
        case["media_trace"] = True
        instance = runner.Runner("adb", self.devices("ab"), case, Path(directory) / "run")
        instance.output.mkdir()
        instance.trace_session = "run"
        instance.logs = [{"device": "a", "pid": 123}, {"device": "a", "pid": 456},
                         {"device": "b", "pid": 789}]
        return instance

    def test_trace_identity_distinguishes_suite_roots_and_same_path_retries(self):
        case = {**self.case([{"phone": "b", "parent": "a"}]), "media_trace": True}
        with tempfile.TemporaryDirectory() as directory:
            outputs = [Path(directory) / root / "0001-case" / "physical" for root in ("first", "second", "first")]
            runs = [runner.Runner("adb", self.devices("ab"), case, output) for output in outputs]
            self.assertEqual(len(runs), len({run.trace_session for run in runs}))
            self.assertEqual(len(runs), len({run.run_id for run in runs}))
            for run in runs:
                self.assertEqual(run.run_id, run.trace_session)
                self.assertLessEqual(len(run.trace_session), 80)
                self.assertRegex(run.trace_session, r"^[A-Za-z0-9_.-]+$")

    def test_phase_pid_must_belong_to_this_phone_execution_before_payload_pull_or_deletion(self):
        for audio in (False, True):
            for pid in (None, True, 789, 999, 123, 456):
                with self.subTest(audio=audio, pid=pid), tempfile.TemporaryDirectory() as directory:
                    instance = self.trace_instance(directory)
                    phase = "run-audio-sender-123" if audio else "run-sender-123"
                    extension = ".wav" if audio else ".ivf"
                    summary = {"session": "run", "role": "sender", "phase": phase, "pid": pid,
                               "csv": phase + ".csv", "wav" if audio else "ivf": phase + extension,
                               "finalized": True}
                    files = {phase + "-summary.json": json.dumps(summary).encode(),
                             phase + ".csv": b"samples", phase + extension: b"payload"}
                    def adb(phone, *args, **kwargs):
                        if args[:2] == ("shell", "ls"):
                            return "\n".join(files)
                        if args[:2] == ("shell", "sha256sum"):
                            return hashlib.sha256(files[Path(args[2]).name]).hexdigest() + " file"
                        if args[0] == "pull":
                            Path(args[2]).write_bytes(files[Path(args[1]).name])
                        return ""
                    instance.adb = Mock(side_effect=adb)
                    instance.collect_media_traces("a", "sender")
                    artifact = instance.trace_artifacts[0]
                    if type(pid) is int and pid in (123, 456):
                        self.assertEqual("MEASURED", artifact["state"])
                        deleted = [call for call in instance.adb.call_args_list if call.args[1:4] == ("shell", "rm", "-f")]
                        self.assertEqual(3, len(deleted))
                        continue
                    self.assertEqual("FAIL", artifact["state"])
                    self.assertIn("PID", artifact["reason"])
                    self.assertFalse(any(call.args[1:4] == ("shell", "rm", "-f") for call in instance.adb.call_args_list))
                    pulled = [call.args[2] for call in instance.adb.call_args_list if call.args[1] == "pull"]
                    self.assertEqual([phase + "-summary.json"], [Path(path).name for path in pulled])

    def test_trace_collection_keeps_all_exact_owned_phases_and_deletes_only_verified_files(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.trace_instance(directory)
            files = {}
            for base in ("run-sender", "run-sender-123", "run-sender-456"):
                files[base + "-summary.json"] = json.dumps({"session": "run", "role": "sender", "frames": 2,
                    "pid": 456 if base.endswith("-456") else 123,
                    "phase": base, "csv": base + ".csv", "ivf": base + ".ivf"}).encode()
                files[base + ".csv"] = b"frame_id,pts_us\n1,1\n2,2\n"
                files[base + ".ivf"] = b"encoded"
            names = list(files) + ["run-sender-bad-summary.json", "another-run-sender-999-summary.json", "run-receiver-321-summary.json"]

            def adb(phone, *args, **kwargs):
                if args[:2] == ("shell", "ls"):
                    return "\n".join(names)
                if args[:2] == ("shell", "sha256sum"):
                    return hashlib.sha256(files[Path(args[2]).name]).hexdigest() + "  file"
                if args[0] == "pull":
                    Path(args[2]).write_bytes(files[Path(args[1]).name])
                return ""

            instance.adb = Mock(side_effect=adb)
            instance.collect_media_traces("a", "sender")
            self.assertEqual(len(instance.trace_artifacts), 3)
            self.assertEqual({item["phase"] for item in instance.trace_artifacts}, {"run-sender", "run-sender-123", "run-sender-456"})
            deleted = [call.args[4] for call in instance.adb.call_args_list if call.args[1:4] == ("shell", "rm", "-f")]
            self.assertEqual({Path(path).name for path in deleted}, set(files))
            self.assertTrue(all(len(item["files"]) == 3 and item["state"] == "MEASURED" for item in instance.trace_artifacts))

    def test_trace_bad_pull_never_deletes_its_remote_phase(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.trace_instance(directory)
            summary = json.dumps({"session": "run", "role": "sender", "frames": 1,
                                  "csv": "run-sender-123.csv", "ivf": "run-sender-123.ivf"}).encode()

            def adb(phone, *args, **kwargs):
                if args[:2] == ("shell", "ls"):
                    return "run-sender-123-summary.json"
                if args[:2] == ("shell", "sha256sum"):
                    return hashlib.sha256(summary).hexdigest() + "  summary"
                if args[0] == "pull":
                    Path(args[2]).write_bytes(b"corrupted")
                return ""

            instance.adb = Mock(side_effect=adb)
            instance.collect_media_traces("a", "sender")
            self.assertEqual(instance.trace_artifacts[0]["state"], "FAIL")
            self.assertFalse(any(call.args[1:4] == ("shell", "rm", "-f") for call in instance.adb.call_args_list))

    def test_trace_long_remote_phase_uses_short_local_path_and_rejects_failed_finalization(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.trace_instance(directory)
            instance.output = Path(directory) / ("output-" + "o" * 100)
            instance.output.mkdir()
            instance.trace_session = "s" * 80
            phase = instance.trace_session + "-sender-1234567890123456"
            files = {phase + "-summary.json": json.dumps({"session": instance.trace_session, "role": "sender", "pid": 123,
                "phase": phase, "csv": phase + ".csv", "ivf": phase + ".ivf", "finalized": True}).encode(),
                phase + ".csv": b"frame_id,pts_us\n1,1\n", phase + ".ivf": b"encoded"}
            def adb(phone, *args, **kwargs):
                if args[:2] == ("shell", "ls"):
                    return "\n".join(files)
                if args[:2] == ("shell", "sha256sum"):
                    return hashlib.sha256(files[Path(args[2]).name]).hexdigest() + " file"
                if args[0] == "pull":
                    if len(args[2]) >= 260:
                        raise RuntimeError("ADB Windows path exceeds260 characters")
                    Path(args[2]).write_bytes(files[Path(args[1]).name])
                return ""
            instance.adb = Mock(side_effect=adb)
            instance.collect_media_traces("a", "sender")
            self.assertEqual(instance.trace_artifacts[0]["state"], "MEASURED")
            self.assertEqual({item["remote_name"] for item in instance.trace_artifacts[0]["files"]}, set(files))
            instance.trace_artifacts.clear()
            files[phase + "-summary.json"] = files[phase + "-summary.json"].replace(b'"finalized": true', b'"finalized": false')
            instance.adb.reset_mock()
            instance.collect_media_traces("a", "sender")
            self.assertEqual(instance.trace_artifacts[0]["state"], "FAIL")
            self.assertFalse(any(call.args[1:4] == ("shell", "rm", "-f") for call in instance.adb.call_args_list))

    def test_trace_unfinalized_phase_stays_unmeasured_and_is_not_deleted(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.trace_instance(directory)
            instance.adb = Mock(return_value="run-sender-123.csv\nrun-sender-123.ivf")
            with patch("run_device_benchmark.time.monotonic", side_effect=[0, 4]):
                instance.collect_media_traces("a", "sender")
            self.assertEqual(instance.trace_artifacts[0]["state"], "UNMEASURED")
            self.assertFalse(any(call.args[1:4] == ("shell", "rm", "-f") for call in instance.adb.call_args_list))

    def test_trace_poll_collects_completed_summary_and_timeout_preserves_partial_bytes(self):
        for complete in (True, False):
            with self.subTest(complete=complete), tempfile.TemporaryDirectory() as directory:
                instance = self.trace_instance(directory)
                phase = "run-sender-123"
                files = {phase + ".csv": b"frame_id,pts_us\n1,1\n", phase + ".ivf": b"encoded"}
                summary = json.dumps({"session": "run", "role": "sender", "phase": phase, "pid": 123,
                    "csv": phase + ".csv", "ivf": phase + ".ivf", "finalized": True}).encode()
                listings = 0
                def adb(phone, *args, **kwargs):
                    nonlocal listings
                    if args[:2] == ("shell", "ls"):
                        listings += 1
                        if complete and listings > 1:
                            files[phase + "-summary.json"] = summary
                        return "\n".join(files)
                    if args[:2] == ("shell", "sha256sum"):
                        return hashlib.sha256(files[Path(args[2]).name]).hexdigest() + " file"
                    if args[0] == "pull":
                        Path(args[2]).write_bytes(files[Path(args[1]).name])
                    return ""
                instance.adb = Mock(side_effect=adb)
                with patch("run_device_benchmark.time.monotonic", side_effect=[0, 1 if complete else 4]), patch("run_device_benchmark.time.sleep"):
                    instance.collect_media_traces("a", "sender")
                trace = instance.trace_artifacts[0]
                self.assertEqual("MEASURED" if complete else "UNMEASURED", trace["state"])
                self.assertEqual(3 if complete else 2, len(trace["files"]))
                self.assertTrue(all((instance.output / item["path"]).read_bytes() == files[item["remote_name"]] for item in trace["files"]))
                if not complete:
                    self.assertFalse(any(call.args[1:4] == ("shell", "rm", "-f") for call in instance.adb.call_args_list))

    def test_required_frozen_apk_mismatch_stops_before_app_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            case = {**self.case([{"phone": "b", "parent": "a"}]), "expected_apk_sha256": "a" * 64}
            instance = runner.Runner("adb", self.devices("ab"), case, Path(directory) / "run")
            instance.inventory = Mock(return_value={"apk_sha256": "b" * 64})
            instance.launch = Mock()
            instance.phone_time = Mock(return_value="10-03 00:00:00.000")
            instance.adb = Mock()
            instance.beep = Mock()
            manifest = instance.run()
            instance.launch.assert_not_called()
            self.assertIn("frozen APK hash", manifest["runner_error"])
            for value in ("short", 123, False):
                with self.subTest(value=value), self.assertRaises(ValueError):
                    runner.validate_case({**case, "expected_apk_sha256": value}, self.devices("ab"))

    def test_wifi_arm_without_carrier_is_refused_as_inert(self):
        with self.assertRaises(ValueError):
            runner.refuse_inert_arms({"tunables": {"wifi_icm": True}})
        runner.refuse_inert_arms({"carrier": "wifi_aware", "tunables": {"wifi_icm": True}})

    def test_an_arm_masked_on_one_phone_only_is_refused_and_a_user_launch_phone_is_not_checked(self):
        masked = {"link_pacing": False, "link_pacer_seed": True}
        with self.assertRaisesRegex(ValueError, "link_pacer_seed because link_pacing=false"):
            runner.refuse_inert_arms({"tunables": {"link_pacing": False}, "phone_tunables": {"b": {"link_pacer_seed": False}}})
        runner.refuse_inert_arms({"tunables": {"link_pacing": False}, "phone_tunables": {"b": {"link_pacer_seed": False}},
                                  "allow_inert_arms": True})
        runner.refuse_inert_arms({"tunables": {"user_launch": True, **masked}})
        self.assertEqual({"test_link_pacing": False, "test_link_pacer_seed": False},
                         runner.case_extras({"tunables": {"link_pacing": False}, "phone_tunables": {"b": {"link_pacer_seed": False}}}, "b"))

    def test_a_reserved_wifi_switch_with_no_consumer_is_refused(self):
        with self.assertRaises(ValueError):
            runner.tunable_extras({"wifi_ble_standby_low_power": True})

    def test_carrier_cases_set_the_wifi_extras_and_native_is_refused(self):
        case = {**self.case([{"phone": "b", "parent": "a"}]), "carrier": "wifi_aware_wider"}
        runner.validate_case(case, self.devices("ab"))
        with self.assertRaises(ValueError):
            runner.validate_case({**case, "carrier": "wifi_aware_native"}, self.devices("ab"))
        with self.assertRaisesRegex(ValueError, "sets the Wi-Fi switches itself"):
            runner.validate_case({**case, "tunables": {"wifi_direct": True}}, self.devices("ab"))
        self.assertIn("test_wifi_direct", runner.WIFI_CARRIER_ARMS["wifi_direct"])
        self.assertEqual("direct", runner.WIFI_CARRIER_ARMS["wifi_direct"]["test_wifi_force_carrier"])

    def test_a_carrier_case_turns_on_the_wifi_opt_in_and_refuses_it_off(self):
        case = {**self.case([{"phone": "b", "parent": "a"}]), "carrier": "wifi_direct"}
        for arms in runner.WIFI_CARRIER_ARMS.values():
            self.assertIs(True, arms["test_bmh3"])
        with self.assertRaisesRegex(ValueError, "needs bmh3"):
            runner.validate_case({**case, "tunables": {"bmh3": False}}, self.devices("ab"))
        runner.validate_case({**case, "tunables": {"bmh3": True}}, self.devices("ab"))

    def test_a_wifi_exact_rate_may_exceed_the_bluetooth_maximum_only_when_it_covers_the_request(self):
        case = {**self.case([{"phone": "b", "parent": "a"}]), "carrier": "wifi_direct"}
        fast = {**case, "profile": {**case["profile"], "bitrate_kbps": 2000}}
        runner.validate_case({**fast, "tunables": {"wifi_exact_kbps": 2000}}, self.devices("ab"))
        for tunables in ({}, {"wifi_exact_kbps": 1500}):
            with self.subTest(tunables=tunables), self.assertRaisesRegex(ValueError, "Settings limit"):
                runner.validate_case({**fast, "tunables": tunables}, self.devices("ab"))
        automatic = {**fast, "profile": {**fast["profile"], "rate_control": "automatic"}, "tunables": {"wifi_exact_kbps": 2000}}
        with self.assertRaisesRegex(ValueError, "ignores it under automatic"):
            runner.validate_case(automatic, self.devices("ab"))
        bluetooth = {key: value for key, value in fast.items() if key != "carrier"}
        with self.assertRaisesRegex(ValueError, "Settings limit"):
            runner.validate_case({**bluetooth, "tunables": {"wifi_exact_kbps": 2000}}, self.devices("ab"))

    def test_a_carrier_arm_without_the_wifi_opt_in_is_refused(self):
        case = {**self.case([{"phone": "b", "parent": "a"}]), "carrier": "wifi_direct"}
        arm = {key: value for key, value in runner.WIFI_CARRIER_ARMS["wifi_direct"].items() if key != "test_bmh3"}
        with patch.dict(runner.WIFI_CARRIER_ARMS, {"wifi_direct": arm}):
            with self.assertRaisesRegex(ValueError, "needs bmh3"):
                runner.validate_case(case, self.devices("ab"))

    def test_aware_band_strict_tunable_maps_to_its_debug_extra(self):
        # AP9: the old strict Aware band rule as a DEBUG control arm (the new default judges by the 5 GHz data channel).
        self.assertEqual({"test_aware_band_strict": True}, runner.tunable_extras({"aware_band_strict": True}))
        self.assertEqual({"test_aware_band_strict": False}, runner.tunable_extras({"aware_band_strict": False}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"aware_band_strict": "on"})

    def test_aware_24ghz_alone_tunable_maps_to_its_debug_extra(self):
        # AP10 (Experimental, app default off): true lets a phone with no other Bluetooth media link accept a
        # 2.4 GHz-only Aware path; false is the app default, the 5 GHz rule.
        self.assertEqual({"test_aware_24ghz_alone": False}, runner.tunable_extras({"aware_24ghz_alone": False}))
        self.assertEqual({"test_aware_24ghz_alone": True}, runner.tunable_extras({"aware_24ghz_alone": True}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"aware_24ghz_alone": "off"})

    def test_aware_goodput_guard_tunable_maps_to_its_debug_extra(self):
        # AP10 (Experimental, app default off): the Aware goodput guard only logs the fallback it would have made;
        # true is the arm in which it returns the hop to Bluetooth.
        self.assertEqual({"test_aware_goodput_guard": False}, runner.tunable_extras({"aware_goodput_guard": False}))
        self.assertEqual({"test_aware_goodput_guard": True}, runner.tunable_extras({"aware_goodput_guard": True}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"aware_goodput_guard": 0})

    def test_call_voice_when_camera_refused_tunable_maps_to_its_debug_extra(self):
        # UI-01: a video call with only the camera refused goes on as voice (app default on; false is the control arm).
        self.assertEqual({"test_call_voice_when_camera_refused": True},
                         runner.tunable_extras({"call_voice_when_camera_refused": True}))
        self.assertEqual({"test_call_voice_when_camera_refused": False},
                         runner.tunable_extras({"call_voice_when_camera_refused": False}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"call_voice_when_camera_refused": "on"})

    def test_call_quiet_pre_ring_failure_tunable_maps_to_its_debug_extra(self):
        # CALL-02: an incoming link that failed before it rang shows nothing (app default off; either arm can be set).
        self.assertEqual({"test_call_quiet_pre_ring_failure": True},
                         runner.tunable_extras({"call_quiet_pre_ring_failure": True}))
        self.assertEqual({"test_call_quiet_pre_ring_failure": False},
                         runner.tunable_extras({"call_quiet_pre_ring_failure": False}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"call_quiet_pre_ring_failure": 1})

    def test_exact_kbps_tunable_maps_to_its_extra(self):
        self.assertEqual({"test_wifi_exact_kbps": 2000}, runner.tunable_extras({"wifi_exact_kbps": 2000}))
        with self.assertRaises(ValueError):
            runner.tunable_extras({"wifi_exact_kbps": True})


class AdbRobustnessTest(unittest.TestCase):
    def runner(self, directory):
        devices = {"a": {"serial": "a", "node_prefix": "000000000001"}, "b": {"serial": "b", "node_prefix": "000000000002"}}
        case = {"id": "x", "source": "a", "receivers": [{"phone": "b", "parent": "a"}], "transport": "classic",
                "profile": {"codec": "vp9", "width": 640, "height": 360, "fps": 15, "bitrate_kbps": 150,
                            "playback": "balanced", "rate_control": "cbr", "thermal_reduction": False},
                "duration_s": 60}
        return runner.Runner("adb", devices, case, Path(directory) / "run")

    @staticmethod
    def done(code, out="", err=""):
        return Mock(returncode=code, stdout=out, stderr=err)

    def test_spawn_failure_is_retried_with_backoff_then_succeeds(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.runner(directory)
            results = [self.done(3221225794), self.done(3221225794), self.done(0, "1234\n")]
            with patch.object(runner.subprocess, "run", side_effect=results) as run, \
                 patch.object(runner.time, "sleep") as sleep:
                self.assertEqual("1234", instance.adb("a", "shell", "pidof", runner.PACKAGE))
            self.assertEqual(3, run.call_count)
            self.assertEqual([0.5, 1.5], [call.args[0] for call in sleep.call_args_list])
            self.assertEqual([1, 2], [event["attempt"] for event in instance.events if event["action"] == "adb_retry"])

    def test_spawn_failure_is_retried_even_for_a_command_that_must_not_run_twice(self):
        # adb never started, so nothing was delivered to the phone.
        with tempfile.TemporaryDirectory() as directory:
            instance = self.runner(directory)
            with patch.object(runner.subprocess, "run", side_effect=[self.done(-1073741502), self.done(0, "Status: ok")]) as run, \
                 patch.object(runner.time, "sleep"):
                instance.adb("a", "shell", "am", "start", "-W", "-n", runner.COMPONENT)
            self.assertEqual(2, run.call_count)

    def test_retries_are_bounded_and_the_last_failure_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.runner(directory)
            with patch.object(runner.subprocess, "run", return_value=self.done(3221225794, err="init failed")) as run, \
                 patch.object(runner.time, "sleep"):
                with self.assertRaisesRegex(RuntimeError, "a: ADB .*exited 3221225794.*init failed"):
                    instance.adb("a", "shell", "pidof", runner.PACKAGE)
            self.assertEqual(1 + len(runner.ADB_RETRY_DELAYS_S), run.call_count)

    def test_offline_transport_is_retried_only_for_idempotent_commands(self):
        offline = lambda: self.done(1, err="error: device offline")
        with tempfile.TemporaryDirectory() as directory:
            instance = self.runner(directory)
            with patch.object(runner.subprocess, "run", side_effect=[offline(), self.done(0, "device")]) as run, \
                 patch.object(runner.time, "sleep"):
                self.assertEqual("device", instance.adb("a", "get-state"))
            self.assertEqual(2, run.call_count)
            with patch.object(runner.subprocess, "run", side_effect=[offline(), self.done(0, "device")]) as run, \
                 patch.object(runner.time, "sleep"):
                instance.adb("a", "shell", "am", "force-stop", runner.PACKAGE)
            self.assertEqual(2, run.call_count)
            for destructive in (("shell", "am", "start", "-W"), ("shell", "input", "tap", "1", "2"),
                                ("shell", "am", "broadcast", "-a", "x")):
                with patch.object(runner.subprocess, "run", return_value=offline()) as run, \
                     patch.object(runner.time, "sleep") as sleep:
                    with self.assertRaisesRegex(RuntimeError, "device offline"):
                        instance.adb("a", *destructive)
                self.assertEqual(1, run.call_count, destructive)
                sleep.assert_not_called()

    def test_other_failures_and_timeouts_are_not_retried(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.runner(directory)
            with patch.object(runner.subprocess, "run", return_value=self.done(1, err="error: closed")) as run, \
                 patch.object(runner.time, "sleep"):
                with self.assertRaises(RuntimeError):
                    instance.adb("a", "shell", "pidof", runner.PACKAGE)
            self.assertEqual(1, run.call_count)
            with patch.object(runner.subprocess, "run",
                              side_effect=runner.subprocess.TimeoutExpired("adb", 1)) as run:
                with self.assertRaises(runner.subprocess.TimeoutExpired):
                    instance.adb("a", "get-state")
            self.assertEqual(1, run.call_count)

    def test_lines_missing_from_the_live_capture_are_recovered_into_a_separate_file(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.runner(directory)
            instance.output.mkdir(parents=True)
            early = "10-09 10:00:00.100  900  900 I BluetoothVideo: BENCHMARK call available: node=aaaaaaaaaaaa"
            late = "10-09 10:00:01.000  900  900 I BluetoothVideo: BENCHMARK call discovered: node=bbbbbbbbbbbb"
            (instance.output / "a-900.log").write_text("--------- beginning of main\n" + late + "\n", encoding="utf-8")
            instance.pids["a"] = "900"
            instance.logs.append({"device": "a", "pid": 900, "path": "a-900.log"})
            dump = "--------- beginning of main\n" + early + "\n" + late + "\n"
            with patch.object(instance, "adb", return_value=dump) as adb:
                self.assertEqual([early], instance.recover_log_lines("a"))
            self.assertEqual(("a", "logcat", "-d", "--pid=900"), adb.call_args.args[:4])
            self.assertEqual(early + "\n", (instance.output / "a-900-recovered.log").read_text(encoding="utf-8"))
            self.assertIn(late, (instance.output / "a-900.log").read_text(encoding="utf-8"))
            self.assertEqual("log_lines_recovered", instance.events[-1]["action"])
            with patch.object(instance, "adb", return_value="--------- beginning of main\n" + late):
                self.assertEqual([], instance.recover_log_lines("a"))

    def test_adb_text_is_decoded_as_utf8_so_a_dump_equals_the_live_capture(self):
        # voice-speech-s24a15s-51cc8028: the host locale (cp1252) turned a middle dot into mojibake, so a
        # line the live file already held was listed as recovered.
        with tempfile.TemporaryDirectory() as directory:
            instance = self.runner(directory)
            with patch.object(runner.subprocess, "run", return_value=self.done(0, out="ok")) as run:
                instance.adb("a", "get-state")
            self.assertEqual(("utf-8", "replace"), (run.call_args.kwargs["encoding"], run.call_args.kwargs["errors"]))
            self.assertNotIn("text", run.call_args.kwargs)

    def test_dump_lines_return_the_whole_dump_while_recovery_lists_only_the_missing_ones(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.runner(directory)
            instance.output.mkdir(parents=True)
            held = "10-09 10:00:01.000  900  900 I BluetoothVideo: Call details: AV1 · recorded scene"
            lagging = "10-09 10:00:02.000  900  900 I BluetoothVideo: BENCHMARK control accept applied"
            (instance.output / "a-900.log").write_text(held + "\n", encoding="utf-8")
            instance.pids["a"] = "900"
            instance.logs.append({"device": "a", "pid": 900, "path": "a-900.log"})
            dump = "--------- beginning of main\n" + held + "\n" + lagging
            with patch.object(instance, "adb", return_value=dump):
                every, missing = instance.dump_log_lines("a")
            self.assertEqual([held, lagging], every)
            self.assertEqual([lagging], missing)

    def test_recovery_is_silent_when_the_device_cannot_be_read(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.runner(directory)
            self.assertEqual([], instance.recover_log_lines("a"))
            instance.pids["a"] = "900"
            instance.logs.append({"device": "a", "pid": 900, "path": "missing.log"})
            with patch.object(instance, "adb", side_effect=RuntimeError("offline")):
                self.assertEqual([], instance.recover_log_lines("a"))

class StageSequenceGapTests(unittest.TestCase):
    @staticmethod
    def stage(sequence, role="broadcast_receive", scope="317644621801431"):
        record = {"session": "s", "scope": scope, "role": role, "sequence": sequence, "stage": "receive"}
        return ("10-09 19:11:21.821 17082 17139 I BluetoothVideo: BENCHMARK stage v1 "
                + json.dumps(record, separators=(",", ":")))

    def test_contiguous_records_report_no_loss(self):
        lines = [self.stage(n) for n in (1, 2, 3)] + [self.stage(n, role="video_decoder", scope="9") for n in (1, 2)]
        self.assertEqual((0, 2), runner.stage_sequence_gaps(lines))

    def test_gaps_count_the_records_logd_lost_per_stream(self):
        # ASUS mesh4-smoke-b9029e48 0001: receive records jumped 121 -> 323 across 14 silent seconds.
        lines = [self.stage(121), self.stage(323), self.stage(1, role="video_decoder", scope="9"),
                 self.stage(4, role="video_decoder", scope="9")]
        self.assertEqual((201 + 2, 2), runner.stage_sequence_gaps(lines))

    def test_unparsable_and_repeated_lines_are_ignored(self):
        lines = ["BENCHMARK stage v1 {broken", "unrelated", self.stage(5), self.stage(5), self.stage(6)]
        self.assertEqual((0, 1), runner.stage_sequence_gaps(lines))

    @staticmethod
    def batch(sequences, role="video_decoder", scope="9", stage="receive", pid=7):
        rows = [[n, stage, n * 10, n * 100, 0, 0, None] for n in sequences]
        record = {"session": "s", "scope": scope, "role": role, "pid": pid, "clock": "System.nanoTime", "rows": rows}
        return "10-09 19:11:21.821 17082 17139 I BluetoothVideo: BENCHMARK stage v2 " + json.dumps(record, separators=(",", ":"))

    def test_v2_batches_report_no_loss_when_contiguous_within_and_across_lines(self):
        lines = [self.batch([1, 2, 3]), self.batch([4, 5]), self.stage(1), self.stage(2),
                 self.batch([1], role="video_encoder", scope="3")]
        self.assertEqual((0, 3), runner.stage_sequence_gaps(lines))

    def test_v2_gaps_are_found_inside_a_batch_and_between_batches(self):
        self.assertEqual((2, 1), runner.stage_sequence_gaps([self.batch([1, 2, 5, 6])]))
        self.assertEqual((3, 1), runner.stage_sequence_gaps([self.batch([1, 2]), self.batch([6, 7])]))

    def test_a_lost_v2_batch_shows_as_a_gap_and_a_broken_one_is_ignored(self):
        broken = self.batch([4, 5, 6])[:-9]
        lines = [self.batch([1, 2, 3]), broken, self.batch([7, 8]), "BENCHMARK stage v2 {broken"]
        self.assertEqual((3, 1), runner.stage_sequence_gaps(lines))

    def test_v1_and_v2_lines_of_one_stream_are_checked_together(self):
        lines = [self.stage(1, role="video_decoder", scope="9"), self.batch([2, 3]),
                 self.stage(7, role="video_decoder", scope="9")]
        self.assertEqual((3, 1), runner.stage_sequence_gaps(lines))

    def test_first_surface_timeout_names_log_loss(self):
        instance = object.__new__(runner.Runner)
        with tempfile.TemporaryDirectory() as directory:
            instance.output = Path(directory)
            instance.case = {"connection_timeout_s": 0}
            instance.logs = [{"device": "asus", "path": "asus-1.log"}]
            instance.events = []
            (instance.output / "asus-1.log").write_text(
                chr(10).join([self.stage(1), self.stage(9)]), encoding="utf-8")
            instance.check_cancelled = lambda: None
            instance.phone_time = lambda phone: "t"
            instance.dump_log_lines = lambda phone: ([], [])
            with self.assertRaisesRegex(RuntimeError, "UNMEASURED log loss"):
                instance.wait_first_frame("asus")
            self.assertEqual(["log_loss", "connection_timeout"], [e["action"] for e in instance.events])
            self.assertEqual(7, instance.events[0]["missing_stage_records"])

    def test_first_surface_timeout_without_gaps_is_unchanged(self):
        instance = object.__new__(runner.Runner)
        with tempfile.TemporaryDirectory() as directory:
            instance.output = Path(directory)
            instance.case = {"connection_timeout_s": 0}
            instance.logs = [{"device": "asus", "path": "asus-1.log"}]
            instance.events = []
            (instance.output / "asus-1.log").write_text(self.stage(1), encoding="utf-8")
            instance.check_cancelled = lambda: None
            instance.phone_time = lambda phone: "t"
            instance.dump_log_lines = lambda phone: ([], [])
            with self.assertRaises(RuntimeError) as raised:
                instance.wait_first_frame("asus")
            self.assertNotIn("log loss", str(raised.exception))
            self.assertEqual(["connection_timeout"], [e["action"] for e in instance.events])


if __name__ == "__main__":
    unittest.main()


class GracefulStopLateRelaunchTest(unittest.TestCase):
    def test_late_relaunch_after_first_empty_pidof_is_force_stopped(self):
        # mesh4-b9726176-loop3-1: the asynchronous shutdown intent relaunched the app after the
        # first empty pidof, and the suite's cleanup check then quarantined the phone.
        pidof = iter(["", "", "", "21638", ""] + [""] * 40)
        calls = []

        def adb(phone, *args, timeout=None):
            calls.append(args)
            if args[:2] == ("shell", "pidof"):
                return next(pidof)
            return ""

        stub = Mock(adb=adb)
        clock = None
        with patch.object(runner.time, "sleep"):
            runner.Runner.graceful_stop(stub, "asus", wait_s=0.25)
        stops = [c for c in calls if c[:3] == ("shell", "am", "force-stop")]
        self.assertGreaterEqual(len(stops), 1)
        last_stop = max(i for i, c in enumerate(calls) if c[:3] == ("shell", "am", "force-stop"))
        later_pidofs = [c for c in calls[last_stop:] if c[:2] == ("shell", "pidof")]
        self.assertGreaterEqual(len(later_pidofs), 2)


class CleanShutdownTest(unittest.TestCase):
    """ASUS uncleanExits=99: drivers force-stopped the app while it scanned. The clean shutdown comes first."""

    def test_broadcast_arguments_and_done_line_match_only_their_nonce(self):
        self.assertEqual(
            ("shell", "am", "broadcast", "-a", "com.example.bluetoothtest.debug.CLEAN_SHUTDOWN", "-p", runner.PACKAGE,
             "--es", "nonce", "r7"),
            runner.clean_shutdown_broadcast_args("r7"),
        )
        line = "I BluetoothVideo: BENCHMARK clean_shutdown: done | nonce=r12 | settled=true | scanners=0 | advertisers=0"
        self.assertTrue(runner.clean_shutdown_done(line, "r12"))
        self.assertFalse(runner.clean_shutdown_done(line, "r1"))
        self.assertFalse(runner.clean_shutdown_done(None, "r12"))

    def _stub(self, done_logged):
        calls = []
        state = {"nonce": None}

        def adb(phone, *args, timeout=None):
            calls.append(args)
            if args[:2] == ("shell", "pidof"):
                return "4242" if len([c for c in calls if c[:2] == ("shell", "pidof")]) == 1 else ""
            if args[:3] == ("shell", "am", "broadcast"):
                state["nonce"] = args[-1]
            if args[:2] == ("shell", "logcat") and done_logged and state["nonce"]:
                return f"BENCHMARK clean_shutdown: done | nonce={state['nonce']} | settled=true | scanners=0"
            return ""

        stub = Mock(adb=adb, events=[])
        stub.clean_shutdown = lambda phone, wait_s=3.0: runner.Runner.clean_shutdown(stub, phone, wait_s)
        return stub, calls

    def test_a_build_with_the_control_is_shut_down_cleanly_before_the_force_stop(self):
        stub, calls = self._stub(done_logged=True)
        with patch.object(runner.time, "sleep"):
            runner.Runner.graceful_stop(stub, "asus", wait_s=1.0)
        kinds = [c[:3] for c in calls]
        self.assertIn(("shell", "am", "broadcast"), kinds)
        self.assertNotIn(("shell", "am", "start"), kinds)
        self.assertLess(kinds.index(("shell", "am", "broadcast")), kinds.index(("shell", "am", "force-stop")))
        self.assertEqual([True], [e["done"] for e in stub.events if e["action"] == "clean_shutdown"])

    def test_an_older_build_without_the_control_gets_the_former_shutdown_intent(self):
        stub, calls = self._stub(done_logged=False)
        with patch.object(runner.time, "sleep"):
            runner.Runner.graceful_stop(stub, "asus", wait_s=0.5)
        kinds = [c[:3] for c in calls]
        self.assertIn(("shell", "am", "broadcast"), kinds)
        self.assertIn(("shell", "am", "start"), kinds)
        self.assertIn(("shell", "am", "force-stop"), kinds)
        self.assertEqual([False], [e["done"] for e in stub.events if e["action"] == "clean_shutdown"])

    def test_an_app_that_is_not_running_is_never_relaunched(self):
        calls = []

        def adb(phone, *args, timeout=None):
            calls.append(args)
            return ""

        stub = Mock(adb=adb, events=[])
        with patch.object(runner.time, "sleep"):
            runner.Runner.graceful_stop(stub, "asus", wait_s=0.5)
        kinds = [c[:3] for c in calls]
        self.assertNotIn(("shell", "am", "broadcast"), kinds)
        self.assertNotIn(("shell", "am", "start"), kinds)


class LoggerDropCaptureTests(unittest.TestCase):
    """liblog reports lines logd dropped (events buffer, tag liblog) on the process's next stored write.
    The ASUS (Android 11) lost one-time app lines at camera open that way; the app log alone cannot show it."""

    @staticmethod
    def drop(second, count, pid=8053):
        return f"10-02 22:56:{second:06.3f} {pid} {pid + 20} I liblog  : {count}"

    def runner_with_capture(self, directory, live):
        instance = object.__new__(runner.Runner)
        instance.output = Path(directory)
        instance.case = {"connection_timeout_s": 0}
        instance.pids = {"asus": "8053"}
        instance.logs = [{"device": "asus", "pid": 8053, "path": "asus-8053.log", "start": "10-02 22:56:00.000",
                          "end": "10-02 22:56:40.000"}]
        instance.drop_logs = [{"device": "asus", "pid": 8053, "path": "asus-8053-liblog.log",
                               "start": "10-02 22:56:00.000"}]
        instance.events = []
        (instance.output / "asus-8053.log").write_text("", encoding="utf-8")
        (instance.output / "asus-8053-liblog.log").write_text(live, encoding="utf-8")
        instance.check_cancelled = lambda: None
        instance.phone_time = lambda phone: "10-02 22:56:31.000"
        instance.dump_log_lines = lambda phone: ([], [])
        return instance

    def test_drop_capture_merges_the_events_buffer_and_is_hash_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.runner_with_capture(directory, self.drop(5, 12) + chr(10))
            dump = chr(10).join([self.drop(5, 12), self.drop(9, 3), self.drop(9.5, 50, pid=77)])
            instance.adb = Mock(return_value=dump)
            instance.finalize_drop_logs()
            entry = instance.drop_logs[0]
            path = instance.output / entry["path"]
            self.assertEqual(("MEASURED", 15, 1), (entry["status"], entry["total_dropped"], entry["dump_merged_lines"]))
            self.assertEqual([{"time": "10-02 22:56:05.000", "dropped": 12}, {"time": "10-02 22:56:09.000", "dropped": 3}],
                             entry["drops"])
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), entry["sha256"])
            self.assertEqual(path.stat().st_size, entry["bytes"])
        with tempfile.TemporaryDirectory() as directory:
            instance = self.runner_with_capture(directory, self.drop(5, 12) + chr(10))
            instance.adb = Mock(side_effect=RuntimeError("device offline"))
            instance.finalize_drop_logs()
            self.assertEqual(("LIVE_ONLY", 12), (instance.drop_logs[0]["status"], instance.drop_logs[0]["total_dropped"]))

    def scored_timeout(self, live):
        """(raised error, runner_error gate) of a first-surface timeout scored from its own finalized capture."""
        with tempfile.TemporaryDirectory() as directory:
            instance = self.runner_with_capture(directory, live)
            instance.adb = Mock(return_value="")
            with self.assertRaises(RuntimeError) as raised:
                instance.wait_first_frame("asus")
            instance.finalize_drop_logs()
            manifest = {"runner_error": str(raised.exception), "log_drops": instance.drop_logs,
                        **({"runner_error_log_loss": raised.exception.record}
                           if isinstance(raised.exception, runner.LogLinesDropped) else {})}
            score = {"execution_gates": {"runner_error": {"status": "FAIL", "value": manifest["runner_error"]}},
                     "devices": [], "overall_status": "FAIL"}
            runner.apply_log_loss(score, manifest, instance.output)
            return raised.exception, score

    def test_a_first_surface_line_lost_in_a_reported_drop_is_unmeasured(self):
        error, score = self.scored_timeout(self.drop(3, 40) + chr(10))
        self.assertIsInstance(error, runner.LogLinesDropped)
        self.assertIn("log lines dropped by logd", str(error))
        self.assertEqual(("UNMEASURED", "log lines dropped by logd"),
                         (score["execution_gates"]["runner_error"]["status"], score["execution_gates"]["runner_error"]["reason"]))
        self.assertEqual("UNMEASURED", score["overall_status"])

    def test_a_first_surface_timeout_without_a_drop_report_stays_a_failure(self):
        for live in ("", self.drop(3, 40, pid=77) + chr(10)):
            with self.subTest(live=live):
                error, score = self.scored_timeout(live)
                self.assertNotIsInstance(error, runner.LogLinesDropped)
                self.assertEqual("FAIL", score["execution_gates"]["runner_error"]["status"])
                self.assertEqual("FAIL", score["overall_status"])
