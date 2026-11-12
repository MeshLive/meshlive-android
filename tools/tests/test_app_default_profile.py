"""A user-launch call endpoint can take the app's own video choice (`profile_source: app_default`).

The runner sends no resolution, rate, encoder, allocation, buffer or thermal extra for such an endpoint, so the app
resolves them from its stored video settings and its Automatic encoder choice, as a person's launch does. The fake app
mirrors that fallback (MainActivity.applyDebugLaunchExtras reads each `test_` value with the stored value as default).
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
sys.path.insert(0, str(Path(__file__).parent))
import run_benchmark_suite as suite
import run_private_call_benchmark as runner
import user_launch
from test_user_launch import Fixture, PREFS

APP = {"profile_source": "app_default"}
PROFILE_EXTRAS = {"test_width", "test_height", "test_fps", "test_bitrate_kbps", "test_rate_control", "test_codec",
                  "test_playback_delay", "test_thermal_reduction"}


def prefs(**values):
    body = "".join(
        f'    <boolean name="{key}" value="{str(value).lower()}" />\n' if isinstance(value, bool) else
        f'    <int name="{key}" value="{value}" />\n' if isinstance(value, int) else
        f'    <string name="{key}">{value}</string>\n' for key, value in values.items())
    return "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" + body + "</map>\n"


def seeded(text, *phones):
    def script(fake):
        for phone in phones:
            fake.files[phone] = {PREFS: text}
    return script


class AppDefaultCase(Fixture):
    def app_case(self, **changes):
        return self.case(profiles={"a": dict(APP), "b": dict(APP)}, **changes)


class ExtrasTest(AppDefaultCase, unittest.TestCase):
    def test_an_app_default_endpoint_sends_no_profile_extras_and_keeps_the_transport(self):
        phones, manifest, _ = self.execute(self.app_case())
        self.assertNotIn("runner_error", manifest)
        for phone in ("a", "b"):
            sent = phones.launch_extras[phone]
            self.assertEqual(set(), PROFILE_EXTRAS & set(sent), sent)
            self.assertEqual("gatt", sent["test_transport"])
            self.assertEqual("true", sent["test_user_launch"])
            self.assertEqual("true", sent["test_private_call_browser"])

    def test_one_endpoint_can_pin_its_profile_while_the_other_takes_the_app_default(self):
        phones, manifest, score = self.execute(self.case(profiles={"a": dict(self.profile), "b": dict(APP)}))
        self.assertNotIn("runner_error", manifest)
        self.assertEqual(PROFILE_EXTRAS, PROFILE_EXTRAS & set(phones.launch_extras["a"]))
        self.assertEqual(set(), PROFILE_EXTRAS & set(phones.launch_extras["b"]))
        self.assertEqual({"b"}, set(manifest["app_chosen_profiles"]))

    def test_an_explicit_profile_still_sends_every_extra_and_records_no_app_choice(self):
        phones, manifest, score = self.execute(self.case())
        self.assertEqual(PROFILE_EXTRAS, PROFILE_EXTRAS & set(phones.launch_extras["a"]))
        self.assertNotIn("app_chosen_profiles", manifest)
        self.assertNotIn("video", [key for record in manifest.get("user_settings", []) for key in record])
        self.assertNotIn("app_chosen_profile", score["devices"][0])


class ReadBackTest(AppDefaultCase, unittest.TestCase):
    def test_the_stored_video_settings_are_read_back_and_recorded_for_every_app_default_phone(self):
        _, manifest, _ = self.execute(self.app_case())
        records = {record["device"]: record for record in manifest["user_settings"]}
        self.assertEqual({"a", "b"}, set(records))
        for record in records.values():
            video = record["video"]
            self.assertEqual([], video["differs"])
            self.assertEqual({}, video["stated"])
            self.assertEqual(426, video["shipped_default"]["video_width"])
            self.assertEqual(200, video["shipped_default"]["video_bitrate_kbps"])
            self.assertIsNone(video["stored"]["video_fps"])  # no stored value: the shipped default applies

    def test_a_default_stored_value_is_recorded_as_stored_and_does_not_block(self):
        _, manifest, _ = self.execute(self.app_case(), seeded(prefs(video_fps=15, video_bitrate=200_000), "a"))
        self.assertNotIn("runner_blocked", manifest)
        video = {record["device"]: record["video"] for record in manifest["user_settings"]}["a"]
        self.assertEqual(15, video["stored"]["video_fps"])
        self.assertEqual(200, video["stored"]["video_bitrate_kbps"])

    def test_a_stored_value_that_is_not_the_shipped_default_blocks_the_case_before_the_dial(self):
        phones, manifest, _ = self.execute(self.app_case(), seeded(prefs(video_fps=30, video_bitrate=600_000), "b"))
        self.assertEqual("video_setting_not_default", manifest["runner_blocked"]["reason"])
        self.assertEqual("b", manifest["runner_blocked"]["device"])
        self.assertIn("video_fps: stored 30", manifest["runner_blocked"]["detail"])
        self.assertIn("video_bitrate_kbps: stored 600", manifest["runner_blocked"]["detail"])
        self.assertEqual([], self.dialed(phones))
        self.assertIn("call_not_dialed", manifest)

    def test_a_manual_value_the_app_does_not_read_cannot_block(self):
        # Advanced settings are off: the stored manual allocation and buffer never reach the call.
        _, manifest, _ = self.execute(self.app_case(), seeded(prefs(manual_rate_control="VBR", receiver_playback_delay="STABLE"), "a"))
        self.assertNotIn("runner_blocked", manifest)

    def test_advanced_settings_on_block_unless_the_case_states_them(self):
        text = prefs(advanced_settings=True, manual_rate_control="VBR")
        _, manifest, _ = self.execute(self.app_case(), seeded(text, "a"))
        self.assertEqual("video_setting_not_default", manifest["runner_blocked"]["reason"])
        self.assertIn("advanced_settings", manifest["runner_blocked"]["detail"])
        _, manifest, _ = self.execute(self.app_case(user_settings={"advanced_settings": True, "manual_rate_control": "VBR"}),
                                      seeded(text, "a"))
        self.assertNotIn("runner_blocked", manifest)

    def test_a_choice_the_case_states_is_written_read_back_and_allowed(self):
        case = self.app_case(user_settings={"video_fps": 30, "video_bitrate_kbps": 400})
        phones, manifest, _ = self.execute(case)
        self.assertNotIn("runner_blocked", manifest)
        stored = user_launch.read_values(phones.prefs_at_launch["a"])
        self.assertEqual({"video_fps": 30, "video_bitrate": 400_000}, stored)  # the Settings screen stores bps
        record = {item["device"]: item for item in manifest["user_settings"]}["a"]
        self.assertEqual({"video_fps": 30, "video_bitrate": 400_000}, record["read_back"])
        self.assertEqual({"video_fps": 30, "video_bitrate_kbps": 400}, record["video"]["stated"])
        # The app took the stated choice from its stored settings: that is what it logged and what is recorded.
        chosen = manifest["app_chosen_profiles"]["a"]
        self.assertEqual((30, 400), (chosen["fps"], chosen["bitrate_kbps"]))

    def test_a_package_that_run_as_refuses_cannot_prove_its_video_settings(self):
        phones, manifest, _ = self.execute(self.app_case(), lambda fake: fake.unwritable.add("a"))
        self.assertEqual("user_setting_not_stored", manifest["runner_blocked"]["reason"])
        self.assertEqual([], self.dialed(phones))

    def test_the_preferences_are_given_back_after_the_case(self):
        text = prefs(onboarding_done=True)
        phones, manifest, _ = self.execute(self.app_case(user_settings={"video_fps": 24}), seeded(text, "a", "b"))
        self.assertEqual(text, phones.files["a"][PREFS])
        self.assertEqual({"RESTORED"}, {item["status"] for item in manifest["user_settings_restored"]})


class ActualChoiceTest(AppDefaultCase, unittest.TestCase):
    def test_the_manifest_and_the_scorer_record_what_the_app_chose(self):
        _, manifest, score = self.execute(self.app_case())
        expected = {"encoder": "c2.auto.encoder", "width": 426, "height": 240, "fps": 15, "bitrate_kbps": 200,
                    "rate_control": "AUTOMATIC", "playback_ms": 250, "audio_kbps": 32}
        for phone in ("a", "b"):
            chosen = manifest["app_chosen_profiles"][phone]
            self.assertEqual(expected, {key: chosen[key] for key in expected})
            self.assertEqual("automatic", chosen["encoder_choice"]["reason"])
            self.assertEqual("true", chosen["encoder_choice"]["hardware"])
        for item in score["devices"]:
            self.assertEqual(manifest["app_chosen_profiles"][item["device"]], item["app_chosen_profile"])
            self.assertEqual("APP_DEFAULT", item["requested_profile_coverage"])
            self.assertEqual(APP, item["requested_profile"])
            self.assertEqual({}, item["negotiated_changes"])
            # Nothing was requested, so nothing is matched against a request.
            self.assertNotIn("selected_profile_mismatches", item["gates"])
            self.assertNotIn("actual_allocation_mismatches", item["gates"])

    def test_a_pinned_endpoint_keeps_the_request_gates(self):
        _, _, score = self.execute(self.case())
        for item in score["devices"]:
            self.assertIn("selected_profile_mismatches", item["gates"])
            self.assertIn("actual_allocation_mismatches", item["gates"])

    def test_the_app_chosen_profile_is_empty_before_the_app_logged_one(self):
        self.assertIsNone(runner.app_chosen_profile([]))


class RefusalTest(AppDefaultCase, unittest.TestCase):
    def test_app_default_is_refused_without_user_launch(self):
        for tunables in ({}, {"user_launch": False}):
            with self.assertRaisesRegex(ValueError, "needs the tunable user_launch"):
                runner.validate_call_case(self.app_case(tunables=tunables), self.devices)

    def test_one_phone_without_user_launch_is_refused(self):
        case = self.app_case(tunables={"user_launch": True}, phone_tunables={"b": {"user_launch": False}})
        with self.assertRaisesRegex(ValueError, "b: profile_source app_default needs the tunable user_launch"):
            runner.validate_call_case(case, self.devices)

    def test_an_app_default_endpoint_states_no_setting(self):
        case = self.case(profiles={"a": {**APP, "bitrate_kbps": 150}, "b": dict(APP)})
        with self.assertRaisesRegex(ValueError, "states no setting"):
            runner.validate_call_case(case, self.devices)

    def test_a_stored_video_choice_needs_an_app_default_endpoint(self):
        with self.assertRaisesRegex(ValueError, "applies to a profile_source app_default endpoint"):
            runner.validate_call_case(self.case(user_settings={"video_fps": 30}), self.devices)

    def test_stored_video_choices_are_typed(self):
        for bad in ({"video_fps": "30"}, {"video_fps": 0}, {"advanced_settings": 1}, {"manual_rate_control": ""}):
            with self.assertRaises(ValueError, msg=bad):
                runner.validate_call_case(self.app_case(user_settings=bad), self.devices)

    def test_raw_input_declares_a_geometry_and_cannot_take_the_app_default(self):
        case = self.app_case(raw_fixture_files={"a": "x.yuv"})
        with self.assertRaisesRegex(ValueError, "cannot carry raw_fixture_files"):
            runner.validate_call_case(case, self.devices)

    def test_a_broadcast_profile_cannot_be_app_default(self):
        shape = runner.broadcast_shape(self.app_case(), "a")
        shape.pop("call_endpoint")
        with self.assertRaisesRegex(ValueError, "private-call endpoint choice"):
            runner.validate_case(shape, self.devices)


class OfflineChecksTest(AppDefaultCase, unittest.TestCase):
    def test_the_suite_does_not_demand_an_offered_encoder_for_an_app_default_endpoint(self):
        capabilities = {phone: {"encoders": []} for phone in ("a", "b")}
        suite.validate_capabilities(self.app_case(), capabilities)  # raises when the request is not offered
        with self.assertRaisesRegex(suite.PrerequisiteError, "encoder/allocation is not offered"):
            suite.validate_capabilities(self.case(), capabilities)


class StoredValueTest(unittest.TestCase):
    def test_merge_writes_numbers_and_names_and_keeps_every_other_value(self):
        merged = user_launch.merge_prefs(prefs(onboarding_done=True, video_fps=15),
                                         {"video_fps": 30, "manual_rate_control": "VBR", "advanced_settings": True})
        self.assertEqual({"onboarding_done": True, "video_fps": 30, "manual_rate_control": "VBR",
                          "advanced_settings": True}, user_launch.read_values(merged))
        self.assertIn('<int name="video_fps" value="30" />', merged)

    def test_a_value_stored_as_another_type_is_not_overwritten_silently(self):
        with self.assertRaisesRegex(ValueError, "not the <int>"):
            user_launch.merge_prefs(prefs(video_fps="fast"), {"video_fps": 30})

    def test_an_absent_key_is_the_shipped_default_and_names_compare_without_case(self):
        state = user_launch.video_state({}, user_launch.read_values(prefs(advanced_settings=True, manual_rate_control="cbr",
                                                                          receiver_playback_delay="auto")))
        self.assertEqual(["advanced_settings: stored True, shipped default False"], state["differs"])


class MeasurementWindowTest(Fixture, unittest.TestCase):
    def test_the_planned_window_is_recorded_with_the_actual_one(self):
        _, manifest, _ = self.execute(self.case(duration_s=12))
        window = manifest["measurement_window"]
        self.assertEqual(12, window["planned_s"])
        self.assertAlmostEqual(12, window["actual_s"], delta=1.0)
        self.assertLessEqual(window["overrun_s"], 0.5)  # the loop sleeps in 0.2 s steps
        self.assertGreaterEqual(window["pre_window_s"], 0)
        self.assertLessEqual(window["start_host_monotonic_ns"], window["end_host_monotonic_ns"])
        self.assertFalse([e for e in manifest["runner_events"] if e["action"] == "measurement_window_overrun"])

    def test_a_stalled_health_check_cannot_stretch_the_window_by_the_checks_of_the_other_phones(self):
        _, manifest, _ = self.execute(self.case(duration_s=12), lambda fake: setattr(fake, "slow_health_s", 20.0))
        window = manifest["measurement_window"]
        # One check of one phone may overrun (it cannot be interrupted); the next phone's check is skipped.
        self.assertLess(window["actual_s"], 12 + 2 * 20.0 + 1)
        self.assertGreaterEqual(window["slowest_health_check_s"], 20.0)
        self.assertGreater(window["overrun_s"], 5.0)
        self.assertTrue([e for e in manifest["runner_events"] if e["action"] == "measurement_window_overrun"])


if __name__ == "__main__":
    unittest.main()
