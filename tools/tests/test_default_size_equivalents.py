"""A person's phone stores the camera-mapped size of the shipped default; per-phone stored choices; Battery Saver.

The app's mode pickers replace the stored 426x240 with the camera's nearest mode (`BENCHMARK mode_mapping:
requested=426x240 effective=352x288`) and the next settings save persists it, so a phone that has opened the app holds a
size that is the shipped default in effect. The runner's default check accepts that pair per phone (a table with its
provenance) and nothing else. `user_settings.per_phone` gives one phone its own stored choices.
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
sys.path.insert(0, str(Path(__file__).parent))
import run_private_call_benchmark as runner
import user_launch
from test_app_default_profile import APP, AppDefaultCase, prefs, seeded
from test_user_launch import PREFS

MAPPED = {"video_width": 352, "video_height": 288}
SOURCE = {"line": "BENCHMARK mode_mapping: requested=426x240 effective=352x288 | reason=nearest_camera_mode",
          "run": "u7ad-20261110u-st-gatt-realme-calls-s24-r1", "log": "benchmarks/runs/x/s24.log"}


class WithEquivalents:
    """`a` has a camera-mapped equivalent in the table; `b` has none."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        path = Path(self.directory.name) / "video_default_equivalents.json"
        path.write_text(json.dumps({"schema_version": 1, "phones": {"a": {**MAPPED, "source": SOURCE}}}), encoding="utf-8")
        patcher = patch.object(user_launch, "EQUIVALENTS_PATH", path)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.addCleanup(self.directory.cleanup)


class CameraMappedSizeTest(WithEquivalents, AppDefaultCase, unittest.TestCase):
    def video(self, manifest, phone="a"):
        return {record["device"]: record["video"] for record in manifest["user_settings"]}[phone]

    def test_the_stored_camera_mapped_size_passes_and_is_recorded_with_its_source(self):
        phones, manifest, _ = self.execute(self.app_case(), seeded(prefs(video_width=352, video_height=288), "a"))
        self.assertNotIn("runner_blocked", manifest)
        self.assertNotEqual([], self.dialed(phones))
        video = self.video(manifest)
        self.assertTrue(video["default_equivalent"])
        self.assertEqual(MAPPED, video["default_equivalent_size"])
        self.assertEqual(SOURCE, video["default_equivalent_source"])
        self.assertEqual([], video["differs"])
        self.assertEqual(352, video["stored"]["video_width"])  # what the phone stores is still recorded as stored

    def test_the_shipped_default_size_is_not_marked_as_an_equivalent(self):
        _, manifest, _ = self.execute(self.app_case(), seeded(prefs(video_width=426, video_height=240), "a"))
        self.assertNotIn("runner_blocked", manifest)
        self.assertFalse(self.video(manifest)["default_equivalent"])

    def test_another_stored_size_still_blocks(self):
        phones, manifest, _ = self.execute(self.app_case(), seeded(prefs(video_width=640, video_height=480), "a"))
        self.assertEqual("video_setting_not_default", manifest["runner_blocked"]["reason"])
        self.assertIn("video_width: stored 640", manifest["runner_blocked"]["detail"])
        self.assertEqual([], self.dialed(phones))
        self.assertFalse(self.video(manifest)["default_equivalent"])

    def test_one_half_of_the_pair_is_not_the_equivalent(self):
        _, manifest, _ = self.execute(self.app_case(), seeded(prefs(video_width=352, video_height=240), "a"))
        self.assertEqual("video_setting_not_default", manifest["runner_blocked"]["reason"])
        self.assertIn("video_width: stored 352", manifest["runner_blocked"]["detail"])

    def test_a_phone_without_an_equivalent_keeps_the_strict_check(self):
        _, manifest, _ = self.execute(self.app_case(), seeded(prefs(video_width=352, video_height=288), "b"))
        self.assertEqual("video_setting_not_default", manifest["runner_blocked"]["reason"])
        self.assertEqual("b", manifest["runner_blocked"]["device"])
        self.assertNotIn("default_equivalent_size", self.video(manifest, "b"))

    def test_the_equivalent_does_not_hide_other_keys(self):
        _, manifest, _ = self.execute(self.app_case(), seeded(prefs(video_width=352, video_height=288, video_fps=30), "a"))
        self.assertEqual("video_setting_not_default", manifest["runner_blocked"]["reason"])
        self.assertIn("video_fps: stored 30", manifest["runner_blocked"]["detail"])
        self.assertNotIn("video_width", manifest["runner_blocked"]["detail"])

    def test_a_stated_size_is_compared_with_the_statement_not_the_equivalent(self):
        case = self.app_case(user_settings={"video_width": 640, "video_height": 480})
        phones, manifest, _ = self.execute(case, seeded(prefs(video_width=352, video_height=288), "a"))
        self.assertNotIn("runner_blocked", manifest)  # the case's own statement is written over the stored size
        self.assertEqual({"video_width": 640, "video_height": 480},
                         {key: user_launch.read_values(phones.prefs_at_launch["a"])[key] for key in MAPPED})
        self.assertFalse(self.video(manifest)["default_equivalent"])

    def test_manual_keys_are_not_flagged_while_advanced_settings_are_off(self):
        # The S24 stores CBR_FD and the Realme VBR as the manual allocation: the app reads it only with Advanced on.
        stored = {"manual_rate_control": "CBR_FD", "receiver_playback_delay": "STABLE"}
        for advanced, blocked in ((False, False), (True, True)):
            state = user_launch.video_state({}, {**stored, "advanced_settings": advanced})
            self.assertEqual(blocked, any("manual_rate_control" in item for item in state["differs"]), state["differs"])

    def test_the_registry_holds_the_four_measured_phones_with_their_source(self):
        table = json.loads(Path(user_launch.__file__).with_name("video_default_equivalents.json").read_text(encoding="utf-8"))
        with patch.object(user_launch, "EQUIVALENTS_PATH", Path(user_launch.__file__).with_name("video_default_equivalents.json")):
            for phone in ("s24", "realme", "asus", "a15s"):
                entry = user_launch.default_equivalent(phone)
                self.assertEqual((352, 288), (entry["video_width"], entry["video_height"]))
                self.assertIn("requested=426x240 effective=352x288", entry["source"]["line"])
                self.assertTrue(entry["source"]["run"] and entry["source"]["log"])
            self.assertIsNone(user_launch.default_equivalent("s22"))
            self.assertIsNone(user_launch.default_equivalent("m31"))
        self.assertEqual({"video_width": 426, "video_height": 240}, table["shipped_default"])


class PerPhoneSettingsTest(AppDefaultCase, unittest.TestCase):
    def per_phone(self, **shared):
        return self.app_case(user_settings={**shared, "per_phone": {"a": {"video_fps": 30}, "b": {"video_fps": 24}}})

    def test_each_phone_stores_its_own_value_and_it_is_read_back(self):
        phones, manifest, _ = self.execute(self.per_phone())
        self.assertNotIn("runner_blocked", manifest)
        self.assertEqual(30, user_launch.read_values(phones.prefs_at_launch["a"])["video_fps"])
        self.assertEqual(24, user_launch.read_values(phones.prefs_at_launch["b"])["video_fps"])
        records = {record["device"]: record for record in manifest["user_settings"]}
        self.assertEqual({"video_fps": 30}, records["a"]["read_back"])
        self.assertEqual({"video_fps": 24}, records["b"]["read_back"])
        self.assertEqual({"video_fps": 24}, records["b"]["video"]["stated"])
        self.assertEqual((30, 24), (manifest["app_chosen_profiles"]["a"]["fps"], manifest["app_chosen_profiles"]["b"]["fps"]))

    def test_a_per_phone_value_overrides_the_shared_one_for_that_phone_only(self):
        case = self.app_case(user_settings={"video_bitrate_kbps": 300, "video_fps": 20,
                                            "per_phone": {"b": {"video_bitrate_kbps": 400}}})
        phones, manifest, _ = self.execute(case)
        self.assertNotIn("runner_blocked", manifest)
        stored = {phone: user_launch.read_values(phones.prefs_at_launch[phone]) for phone in "ab"}
        self.assertEqual((300_000, 400_000), (stored["a"]["video_bitrate"], stored["b"]["video_bitrate"]))
        self.assertEqual((20, 20), (stored["a"]["video_fps"], stored["b"]["video_fps"]))

    def test_every_phone_gets_its_own_file_back_exactly(self):
        originals = {"a": prefs(onboarding_done=True), "b": prefs(my_name="Bo", video_fps=15)}

        def script(fake):
            for phone, text in originals.items():
                fake.files[phone] = {PREFS: text}
        phones, manifest, _ = self.execute(self.per_phone(), script)
        for phone, text in originals.items():
            self.assertEqual(text, phones.files[phone][PREFS])
        self.assertEqual({"RESTORED"}, {item["status"] for item in manifest["user_settings_restored"]})

    def test_a_per_phone_value_the_phone_did_not_store_blocks_that_phone(self):
        phones, manifest, _ = self.execute(self.per_phone(), lambda fake: fake.unwritable.add("b"))
        self.assertEqual("user_setting_not_stored", manifest["runner_blocked"]["reason"])
        self.assertEqual("b", manifest["runner_blocked"]["device"])

    def test_a_case_without_per_phone_is_read_as_before(self):
        case = self.app_case(user_settings={"video_fps": 30})
        self.assertEqual((({"video_fps": 30}), {}), user_launch.split_user_settings(case))
        for phone in ("a", "b", "r"):
            self.assertEqual({"video_fps": 30}, user_launch.video_settings(case, phone))
        self.assertEqual({"video_fps": 30}, user_launch.video_settings(case))
        self.assertEqual({}, user_launch.resolve(self.case()))
        relayed = self.case(user_settings={"relayed_calls": True})
        self.assertEqual(user_launch.resolve(relayed), user_launch.resolve(relayed, "a"))

    def test_the_per_phone_form_is_validated(self):
        bad = ({"per_phone": {}}, {"per_phone": []}, {"per_phone": {"a": {}}}, {"per_phone": {"a": {"video_fps": "30"}}},
               {"per_phone": {"a": {"nonsense": True}}}, {"per_phone": {"z": {"video_fps": 30}}})
        for settings in bad:
            with self.assertRaises(ValueError, msg=settings):
                runner.validate_call_case(self.app_case(user_settings=settings), self.devices)

    def test_a_per_phone_video_choice_needs_an_app_default_endpoint(self):
        case = self.case(profiles={"a": dict(APP), "b": dict(self.profile)},
                         user_settings={"per_phone": {"b": {"video_fps": 30}}})
        with self.assertRaisesRegex(ValueError, "b in user_settings.per_phone applies to a profile_source app_default"):
            runner.validate_call_case(case, self.devices)

    def test_relayed_calls_set_for_one_phone_only_leaves_the_others_missing_it(self):
        base = dict(relay="r", relay_shape="on_demand", route_wait_s=0, tunables={"user_launch": True, "relayed_calls": True})
        route = ["a", "b", "r"]
        every = self.case(user_settings={"relayed_calls": True}, **base)
        self.assertEqual([], user_launch.blockers(every, route))
        everywhere = self.case(user_settings={"per_phone": {p: {"relayed_calls": True} for p in route}}, **base)
        self.assertEqual([], user_launch.blockers(everywhere, route))
        one = self.case(user_settings={"per_phone": {"a": {"relayed_calls": True}}}, **base)
        self.assertEqual(["user_setting_missing"], [reason for reason, _ in user_launch.blockers(one, route)])
        override = self.case(user_settings={"relayed_calls": True, "per_phone": {"r": {"relayed_calls": False}}}, **base)
        self.assertEqual(["user_setting_missing"], [reason for reason, _ in user_launch.blockers(override, route)])


class BatterySaverScoreTest(AppDefaultCase, unittest.TestCase):
    DOZE = ("BENCHMARK doze_state: state=awake | deviceIdle=false | lightIdle=false | lowPowerStandby=false | "
            "powerSave={} | lowPowerStandbyExempt=true | trigger=attach")

    def wifi_case(self, **changes):
        return self.case(transport="classic", user_settings={"wifi": True}, **changes)

    def run_case(self, case, saver):
        def script(fake):
            for phone, on in saver.items():
                fake.effect_lines[phone] = (self.DOZE.format("true" if on else "false"),)
        _, manifest, score = self.execute(case, script)
        self.assertNotIn("runner_error", manifest)
        return score

    def test_a_wifi_call_whose_participant_log_shows_power_save_is_unmeasured_not_a_pass(self):
        score = self.run_case(self.wifi_case(), {"a": False, "b": True})
        self.assertEqual("UNMEASURED", score["overall_status"])
        for item in score["devices"]:  # the Wi-Fi claim is the call's: both endpoints carry the reason
            gate = item["gates"]["wifi_battery_saver"]
            self.assertEqual("UNMEASURED", gate["status"])
            self.assertIn("battery_saver_on", gate["reason"])
            self.assertIn("app log of b shows powerSave=true", gate["reason"])
            self.assertEqual("UNMEASURED", item["log_status"])

    def test_the_same_wifi_call_with_no_power_save_has_no_such_gate(self):
        score = self.run_case(self.wifi_case(), {"a": False, "b": False})
        for item in score["devices"]:
            self.assertNotIn("wifi_battery_saver", item["gates"])

    def test_power_save_does_not_unmeasure_a_bluetooth_only_call(self):
        score = self.run_case(self.case(transport="classic"), {"a": True, "b": True})
        for item in score["devices"]:
            self.assertNotIn("wifi_battery_saver", item["gates"])

    def test_the_saved_doze_line_and_dumpsys_text_are_read(self):
        # Shapes from u7ad-20261110u-st-wd-s24-calls-realme-r1 (S24 with Battery Saver on).
        self.assertTrue(user_launch.power_save_in_log(
            "11-10 20:33:16.365 26404 26404 I BluetoothVideo: BENCHMARK doze_state: state=awake | deviceIdle=false | "
            "lightIdle=false | lowPowerStandby=false | powerSave=true | lowPowerStandbyExempt=true | trigger=attach"))
        self.assertFalse(user_launch.power_save_in_log("BENCHMARK doze_state: state=awake | powerSave=false | trigger=attach"))
        self.assertIs(True, user_launch.battery_saver("  Battery Saver is currently: ON\n"))
        self.assertIs(False, user_launch.battery_saver("Battery Saver is currently: OFF"))
        self.assertIsNone(user_launch.battery_saver("mWakefulness=Awake"))

    def test_the_live_state_machine_wins_over_a_stale_statistics_line(self):
        # ASUS Android 11, 2026-11-11: the statistics said ON while Battery Saver was off and the app saw powerSave=false.
        dump = ("Battery saving stats:\n  Battery Saver is currently: ON\n    Times enabled: 27\n"
                "Battery saver state machine:\n  Enabled=false\n    full=false\n    adaptive=false\n  mState=1\n")
        self.assertIs(False, user_launch.battery_saver(dump))
        self.assertIs(True, user_launch.battery_saver(dump.replace("Enabled=false", "Enabled=true")))


if __name__ == "__main__":
    unittest.main()
