"""A planned Wi-Fi toggle takes the Wi-Fi carrier away mid-call: the fall back to Bluetooth is measured, the death it causes
is planned, and the phone always gets its Wi-Fi back (lane C 2026-11-10: the first Wi-Fi-off proof needed a hand toggle)."""
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import run_private_call_benchmark as runner
import score_device_run as scorer


class FakeWifi:
    """adb as far as Wi-Fi goes: `svc wifi` changes the phone's `wifi_on` setting unless the phone ignores it."""

    def __init__(self, ignored=()):
        self.state = {}
        self.ignored = set(ignored)
        self.commands = []

    def adb(self, phone, *args, timeout=None):
        self.commands.append((phone, *args))
        if args[:2] == ("shell", "svc") and args[2] == "wifi" and phone not in self.ignored:
            self.state[phone] = "0" if args[3] == "disable" else "1"
        if args[:4] == ("shell", "settings", "get", "global"):
            return self.state.get(phone, "1") + "\n"
        return ""


class WifiToggleActionTest(unittest.TestCase):
    def setUp(self):
        self.devices = {"a": {"serial": "a", "node_prefix": "000000000001"},
                        "b": {"serial": "b", "node_prefix": "000000000002"}}
        profile = {"profile_source": "app_default"}
        self.case = {"id": "wifi-off", "caller": "a", "callee": "b", "transport": "gatt", "carrier": "wifi_aware",
                     "duration_s": 60, "profiles": {"a": profile, "b": profile}, "tunables": {"user_launch": True},
                     "actions": [{"phone": "b", "action": "wifi_off", "at_s": 30}]}

    def runner_with(self, wifi):
        instance = runner.CallRunner("adb", self.devices, self.case, Path("unused"))
        instance.adb = wifi.adb
        instance.phone_time = Mock(return_value="11-11 00:00:30.000")
        return instance

    def test_wifi_off_mid_call_measures_the_time_back_on_bluetooth(self):
        wifi = FakeWifi()
        instance = self.runner_with(wifi)
        instance.wait_for = Mock(return_value={"time": "11-11 00:00:30.450", "at": runner.timestamp("11-11 00:00:30.450"),
                                               "message": "BENCHMARK carrier_switch: to=BLUETOOTH"})
        with patch.object(runner.time, "sleep"):
            instance.action({"phone": "b", "action": "wifi_off"})
        self.assertIn(("b", "shell", "svc", "wifi", "disable"), wifi.commands)
        event = instance.control_events[-1]
        self.assertTrue(event["acknowledged"])
        self.assertAlmostEqual(event["back_on_bluetooth"]["ms"], 450, delta=1)

    def test_a_phone_whose_wifi_does_not_go_off_fails_the_action(self):
        instance = self.runner_with(FakeWifi(ignored={"b"}))
        with patch.object(runner.time, "sleep"), self.assertRaisesRegex(RuntimeError, "did not reach the requested state"):
            instance.action({"phone": "b", "action": "wifi_off"})

    def test_wifi_comes_back_on_whatever_ended_the_case(self):
        wifi = FakeWifi()
        wifi.state["b"] = "0"  # the case stopped right after its wifi_off
        instance = self.runner_with(wifi)
        with patch.object(runner.time, "sleep"):
            instance.restore_wifi()
        self.assertEqual(wifi.state["b"], "1")
        self.assertEqual([(item["device"], item["status"]) for item in instance.wifi_restored], [("b", "RESTORED")])

    def test_a_phone_left_without_wifi_is_recorded_unverified(self):
        wifi = FakeWifi(ignored={"b"})
        wifi.state["b"] = "0"
        instance = self.runner_with(wifi)
        with patch.object(runner.time, "sleep"):
            instance.restore_wifi()
        self.assertEqual(instance.wifi_restored[0]["status"], "UNVERIFIED")

    def test_a_case_must_turn_wifi_off_before_it_turns_it_on(self):
        with self.assertRaisesRegex(ValueError, "needs an earlier wifi_off"):
            runner.validate_wifi_actions({"actions": [{"phone": "b", "action": "wifi_on", "at_s": 10}]})
        with self.assertRaisesRegex(ValueError, "already planned off"):
            runner.validate_wifi_actions({"actions": [{"phone": "b", "action": "wifi_off", "at_s": 10},
                                                      {"phone": "b", "action": "wifi_off", "at_s": 20}]})
        runner.validate_wifi_actions({"actions": [{"phone": "b", "action": "wifi_off", "at_s": 10},
                                                  {"phone": "b", "action": "wifi_on", "at_s": 20}]})


class PlannedWifiOffDeathTest(unittest.TestCase):
    """The Wi-Fi carrier's death after a planned toggle is planned; any other carrier death stays unexpected."""

    manifest = {"control_events": [{"device": "b", "action": "wifi_off", "time": "11-11 00:00:30.000",
                                    "acknowledged": True}]}

    def planned(self, message, time):
        return scorer.planned_wifi_off_death(message, scorer.timestamp(time), scorer.planned_wifi_offs(self.manifest))

    def test_the_toggled_phone_naming_the_toggle_is_planned(self):
        self.assertTrue(self.planned("BENCHMARK carrier_death: carrier=WIFI_AWARE | reason=aware_down_wifi_off",
                                     "11-11 00:00:30.340"))

    def test_the_peer_seeing_the_socket_close_right_after_the_toggle_is_planned(self):
        self.assertTrue(self.planned("BENCHMARK carrier_death: carrier=WIFI_AWARE | reason=read failed: SocketException",
                                     "11-11 00:00:30.500"))

    def test_a_socket_failure_long_after_the_toggle_is_unexpected(self):
        self.assertFalse(self.planned("BENCHMARK carrier_death: carrier=WIFI_AWARE | reason=read failed: SocketException",
                                      "11-11 00:00:40.000"))

    def test_a_carrier_death_before_the_toggle_is_unexpected(self):
        self.assertFalse(self.planned("BENCHMARK carrier_death: carrier=WIFI_AWARE | reason=aware_down_wifi_off",
                                      "11-11 00:00:20.000"))


if __name__ == "__main__":
    unittest.main()
