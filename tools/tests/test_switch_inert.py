import importlib.util
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))

import switch_inert

spec = importlib.util.spec_from_file_location("runner", Path(__file__).parents[1] / "run_device_benchmark.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class SwitchInertTest(unittest.TestCase):
    def test_a_masked_arm_is_named_with_its_parent(self):
        extras = runner.tunable_extras({"link_pacing": False, "join_probe_seed": True})
        self.assertEqual([("join_probe_seed", "link_pacing=false")], switch_inert.inert_arms(extras))

    def test_an_arm_at_its_default_or_with_its_parent_on_is_no_arm(self):
        self.assertEqual([], switch_inert.inert_arms({"test_link_pacing": False, "test_link_pacer_seed": True}))
        self.assertEqual([], switch_inert.inert_arms({"test_relay_time_queue": True, "test_relay_drain_sustained": True}))
        self.assertEqual([], switch_inert.inert_arms({"test_call_fragment_airtime_ms": 40, "test_call_head_of_line": "on"}))

    def test_the_runner_refuses_a_case_whose_requested_arm_is_inert(self):
        with self.assertRaisesRegex(ValueError, "relay_drain_sustained because relay_time_queue=false"):
            runner.refuse_inert_arms({"tunables": {"relay_drain_sustained": True}})
        # A deliberate regression control, and a user-launch case (user defaults), are not refused.
        runner.refuse_inert_arms({"tunables": {"relay_drain_sustained": True}, "allow_inert_arms": True})
        runner.refuse_inert_arms({"tunables": {"relay_drain_sustained": True, "user_launch": True}})
        runner.refuse_inert_arms({"tunables": {"relay_drain_sustained": True, "relay_time_queue": True}})

    def test_child_side_relay_arms_follow_relay_child_reanchor_unless_set_apart(self):
        extras = runner.tunable_extras({"relay_child_reanchor": False, "relay_parent_trickle": False})
        self.assertEqual([("relay_parent_trickle", "relay_parent_watch=false")], switch_inert.inert_arms(extras))
        extras = runner.tunable_extras({"relay_child_reanchor": False, "relay_parent_watch": True,
                                        "relay_parent_trickle": False, "relay_prefer_parent": True,
                                        "relay_recovery_grace": False})
        self.assertEqual([], switch_inert.inert_arms(extras))
        # The same-parent preference is off by default (build 4): a grace arm alone is inert until it is turned on.
        extras = runner.tunable_extras({"relay_recovery_grace": False})
        self.assertEqual([("relay_recovery_grace", "relay_prefer_parent=false")], switch_inert.inert_arms(extras))
        extras = runner.tunable_extras({"playout_time_budget_ms": 3000})
        self.assertEqual([("playout_time_budget_ms", "playout_time_target=false")], switch_inert.inert_arms(extras))

    def test_register_names_and_shipped_default_features_are_tunables(self):
        extras = runner.tunable_extras({"relay_stall_holdoff": False, "phy_after_auth": True, "user_launch": True,
                                        "rotating_ids": True, "iphone_discovery": True, "messaging_v2": True,
                                        "call_head_of_line": "gatt_default", "stall_rediscover_ms": 20000})
        self.assertEqual(False, extras["test_relay_stall_holdoff"])
        self.assertEqual(True, extras["test_ios_compact_discovery"])
        self.assertEqual("gatt_default", extras["test_call_head_of_line"])

    def test_the_direction_split_needs_adaptive_phy_and_replaces_the_fixed_direction_split(self):
        extras = runner.tunable_extras({"adaptive_phy": False, "phy_direction_split": True})
        self.assertEqual([("phy_direction_split", "adaptive_phy=false")], switch_inert.inert_arms(extras))
        extras = runner.tunable_extras({"phy_direction_split": True, "adaptive_phy_split": True})
        self.assertEqual([("adaptive_phy_split", "phy_direction_split=true")], switch_inert.inert_arms(extras))
        self.assertEqual([], switch_inert.inert_arms(runner.tunable_extras({"phy_direction_split": True})))

    def test_the_aware_port_control_arm_reaches_the_launch_and_is_inert_only_without_a_wifi_carrier(self):
        # W5: the derived port is the app default, so only `false` (the control arm) is an arm.
        case = {"carrier": "wifi_aware", "tunables": {"wifi_aware_derived_port": False}}
        self.assertIs(False, runner.case_extras(case)["test_wifi_aware_derived_port"])
        self.assertIs(True, runner.case_extras(case)["test_wifi_carrier"])
        runner.refuse_inert_arms(case)
        with self.assertRaisesRegex(ValueError, "wifi_aware_derived_port because wifi_carrier=false"):
            runner.refuse_inert_arms({"tunables": {"wifi_aware_derived_port": False}})
        runner.refuse_inert_arms({"tunables": {"wifi_aware_derived_port": True}})


if __name__ == "__main__":
    unittest.main()
