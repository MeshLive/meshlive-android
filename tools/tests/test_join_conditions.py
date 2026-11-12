"""JT2 P0 runner conditions: `dial_via`, `callee_state` and the launch order of the two endpoints.

The fake phones answer `am broadcast` only when their app has the DEBUG dial receiver (an app without it drops the
broadcast silently), report the screen and the window focus through `dumpsys`, and follow the Home, Sleep and Wake keys.
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
sys.path.insert(0, str(Path(__file__).parent))
import run_private_call_benchmark as runner
from test_user_launch import Fixture

PACKAGE = runner.PACKAGE


class Conditions(Fixture):
    def case(self, **changes):
        return super().case(**{"transport": "classic", **changes})

    def keys(self, phones, phone, key):
        return [index for index, (serial, args) in enumerate(phones.commands)
                if serial == phone and args[:3] == ["shell", "input", "keyevent"] and key in args]

    @staticmethod
    def dial_broadcasts(phones):
        return [item for item in getattr(phones, "broadcasts", []) if item[1] == runner.DIAL_BROADCAST_ACTION]

    def invite_index(self, phones):
        return next(index for index, (serial, args) in enumerate(phones.commands)
                    if serial == "a" and ("test_control" in args) and any(str(item).startswith("call:") for item in args))


class DialViaTest(Conditions, unittest.TestCase):
    def test_the_default_is_the_intent_and_the_control_event_is_unchanged(self):
        phones, manifest, _ = self.execute(self.case())
        self.assertNotIn("runner_error", manifest)
        self.assertEqual([], self.dial_broadcasts(phones))
        dial = next(item for item in manifest["control_events"] if item["action"].startswith("call:"))
        self.assertNotIn("via", dial)

    def test_a_broadcast_dial_reaches_the_app_without_an_activity_start_and_records_the_apps_handled_line(self):
        phones, manifest, _ = self.execute(self.case(dial_via="broadcast"))
        self.assertNotIn("runner_error", manifest)
        (serial, action, extras), = self.dial_broadcasts(phones)
        self.assertEqual(("a", runner.DIAL_BROADCAST_ACTION), (serial, action))
        self.assertTrue(extras["test_control"].startswith("call:"))
        self.assertEqual([], [args for who, args in phones.commands
                              if who == "a" and "test_control" in args and "am" in args and "start" in args
                              and any(str(item).startswith("call:") for item in args)])
        dial = next(item for item in manifest["control_events"] if item["action"].startswith("call:"))
        self.assertEqual("broadcast", dial["via"])
        self.assertIn("handled=true", dial["broadcast_handled"]["message"])

    def test_an_app_that_never_handles_the_broadcast_blocks_the_case_and_nothing_counts_as_dialed(self):
        phones, manifest, _ = self.execute(self.case(dial_via="broadcast"), lambda fake: fake.broadcast_ignored.add("a"))
        self.assertEqual("dial_via_not_handled", manifest["runner_blocked"]["reason"])
        self.assertEqual("a", manifest["runner_blocked"]["device"])
        self.assertIn("call_not_dialed", manifest)
        self.assertEqual([], [item for item in manifest["control_events"] if item["action"].startswith("call:")])
        self.assertEqual(1, len(self.dial_broadcasts(phones)))  # sent once; the app never answered it

    def test_an_unknown_value_is_refused_at_planning(self):
        with self.assertRaisesRegex(ValueError, "dial_via must be one of"):
            runner.validate_call_case(self.case(dial_via="sms"), self.devices)


class CalleeStateTest(Conditions, unittest.TestCase):
    def devices_with(self, **lock):
        return {name: {**entry, **lock.get(name, {})} for name, entry in self.devices.items()}

    def test_foreground_is_today_s_behaviour_and_records_nothing(self):
        phones, manifest, _ = self.execute(self.case(callee_state="foreground"))
        self.assertNotIn("callee_state", manifest)
        self.assertEqual([], self.keys(phones, "b", "KEYCODE_HOME") + self.keys(phones, "b", "KEYCODE_SLEEP"))

    def test_background_sends_home_to_the_callee_only_before_the_dial_and_proves_the_focus_moved(self):
        phones, manifest, _ = self.execute(self.case(callee_state="background"))
        self.assertNotIn("runner_error", manifest)
        home = self.keys(phones, "b", "KEYCODE_HOME")
        self.assertEqual(1, len(home))
        self.assertLess(home[0], self.invite_index(phones))
        self.assertEqual([], self.keys(phones, "a", "KEYCODE_HOME"))
        record = manifest["callee_state"]
        self.assertEqual(("b", "background", True, "Awake"), (record["device"], record["requested"], record["reached"],
                                                              record["wakefulness"]))
        self.assertNotIn(PACKAGE, record["focus"])

    def test_screen_off_needs_the_registry_to_say_no_secure_lock_and_wakes_the_screen_afterwards(self):
        devices = self.devices_with(b={"secure_lock": False})
        phones, manifest, _ = self.execute_on(devices, self.case(callee_state="screen_off"))
        self.assertNotIn("runner_error", manifest)
        sleep = self.keys(phones, "b", "KEYCODE_SLEEP")
        self.assertEqual(1, len(sleep))
        self.assertLess(sleep[0], self.invite_index(phones))
        self.assertEqual("Asleep", manifest["callee_state"]["wakefulness"])
        self.assertTrue(manifest["callee_state"]["reached"])
        self.assertTrue(manifest["callee_state"]["restored"])
        self.assertEqual([], self.keys(phones, "a", "KEYCODE_SLEEP"))
        self.assertNotIn("b", phones.asleep)  # the case leaves the screen on

    def test_screen_off_is_refused_at_planning_on_a_secure_lock_or_an_unknown_phone(self):
        for lock in ({"b": {"secure_lock": True}}, {}):  # the ASUS has a pattern; a phone the registry says nothing about
            with self.assertRaisesRegex(ValueError, "screen_off is refused for b"):
                runner.validate_call_case(self.case(callee_state="screen_off"), self.devices_with(**lock))
        runner.validate_call_case(self.case(callee_state="screen_off"), self.devices_with(b={"secure_lock": False}))

    def test_the_real_registry_never_allows_screen_off_on_the_asus_or_the_s24(self):
        import json
        registry = json.loads((Path(runner.__file__).with_name("benchmark_devices.example.json")).read_text(encoding="utf-8-sig"))["devices"]
        self.assertTrue(registry["asus"]["secure_lock"])
        self.assertTrue(registry["s24"]["secure_lock"])
        self.assertTrue(registry["a15s"]["slow_start"])
        for phone in ("asus", "s24"):
            case = {**self.case(callee=phone, caller="s22"), "profiles": {"s22": dict(self.profile), phone: dict(self.profile)},
                    "callee_state": "screen_off"}
            devices = {name: registry[name] for name in ("s22", phone)}
            with self.assertRaisesRegex(ValueError, "screen_off is refused"):
                runner.validate_call_case(case, devices)

    def test_the_runner_itself_refuses_to_sleep_a_phone_the_registry_does_not_clear(self):
        devices = self.devices_with(b={"secure_lock": False})
        phones, _, _ = self.execute_on(devices, self.case(callee_state="screen_off"), lambda fake: None)
        instance = type("Probe", (), {"devices": {"b": {"secure_lock": True}}, "case": {"callee_state": "screen_off"}})()
        with self.assertRaises(runner.applied_arms.ArmBlocked) as raised:
            runner.CallRunner.apply_callee_state(instance, "b")
        self.assertEqual("callee_state_refused", raised.exception.record["reason"])

    def test_a_state_the_phone_does_not_reach_blocks_before_the_dial(self):
        devices = self.devices_with(b={"secure_lock": False})
        phones, manifest, _ = self.execute_on(devices, self.case(callee_state="screen_off"),
                                              lambda fake: fake.stuck_awake.add("b"))
        self.assertEqual("callee_state_not_reached", manifest["runner_blocked"]["reason"])
        self.assertEqual("b", manifest["runner_blocked"]["device"])
        self.assertEqual([], self.dialed(phones))
        self.assertFalse(manifest["callee_state"]["reached"])

    def test_unknown_values_and_a_relayed_route_are_refused_at_planning(self):
        with self.assertRaisesRegex(ValueError, "callee_state must be one of"):
            runner.validate_call_case(self.case(callee_state="doze"), self.devices)
        with self.assertRaisesRegex(ValueError, "callee_state applies to direct calls"):
            runner.validate_call_case(self.case(callee_state="background", relay="r", relay_shape="on_demand", transport="gatt"), self.devices)

    def execute_on(self, devices, case, script=lambda phones: None):
        saved = self.devices
        self.devices = devices
        try:
            return self.execute(case, script)
        finally:
            self.devices = saved


class LaunchOrderTest(Conditions, unittest.TestCase):
    def order(self, devices, case):
        saved, self.devices = self.devices, devices
        try:
            phones, manifest, _ = self.execute(case)
        finally:
            self.devices = saved
        self.assertNotIn("runner_error", manifest)
        first = {phone: self.launches(phones, phone)[0] for phone in ("a", "b")}
        return sorted(first, key=first.get)

    def with_slow(self, phone):
        return {name: ({**entry, "slow_start": True} if name == phone else dict(entry)) for name, entry in self.devices.items()}

    def test_without_a_slow_phone_the_callee_launches_first_as_before(self):
        self.assertEqual(["b", "a"], self.order(self.devices, self.case()))

    def test_a_slow_start_caller_launches_before_the_callee(self):
        self.assertEqual(["a", "b"], self.order(self.with_slow("a"), self.case()))

    def test_a_slow_start_callee_still_launches_first(self):
        self.assertEqual(["b", "a"], self.order(self.with_slow("b"), self.case()))

    def test_an_explicit_launch_order_overrides_the_registry(self):
        self.assertEqual(["a", "b"], self.order(self.with_slow("b"), self.case(launch_order=["a", "b"])))
        self.assertEqual(["b", "a"], self.order(self.with_slow("a"), self.case(launch_order=["b", "a"])))

    def test_a_launch_order_must_name_the_two_endpoints_once(self):
        for bad in (["a"], ["a", "a"], ["a", "r"], "ab"):
            with self.assertRaisesRegex(ValueError, "launch_order must list"):
                runner.validate_call_case(self.case(launch_order=bad), self.devices)


if __name__ == "__main__":
    unittest.main()
