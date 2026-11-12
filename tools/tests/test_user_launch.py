"""A user-launch case runs the way a person's launch resolves it (package UL1).

The fake phones answer like the app: a user launch ignores the opt-in extras, reads the stored Settings choices from
the app's preferences file (reached with run-as), announces rotating discovery IDs, and logs what it resolved.
"""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
sys.path.insert(0, str(Path(__file__).parent))
import applied_arms
import run_benchmark_suite as suite
import run_private_call_benchmark as runner
import user_launch
from call_phone_fakes import fake_call

PREFS = user_launch.PACKAGE_PREFS_FILE
OTHER_PREFS = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
               "    <string name=\"my_name\">Ann &amp; Bo</string>\n"
               "    <boolean name=\"onboarding_done\" value=\"true\" />\n"
               "    <int name=\"video_fps\" value=\"15\" />\n</map>\n")


class Fixture:
    devices = {"a": {"serial": "a", "node_prefix": "000000000001"},
               "b": {"serial": "b", "node_prefix": "000000000002"},
               "r": {"serial": "r", "node_prefix": "000000000003"}}
    profile = {"codec": "encoder-a", "width": 640, "height": 360, "fps": 15, "bitrate_kbps": 150,
               "playback": "balanced", "rate_control": "cbr", "thermal_reduction": False}

    def case(self, **changes):
        case = {"id": "ul", "caller": "a", "callee": "b", "transport": "gatt", "duration_s": 12,
                "profiles": {"a": dict(self.profile), "b": dict(self.profile)},
                "tunables": {"user_launch": True}, **changes}
        return case

    def execute(self, case, script=lambda phones: None):
        with tempfile.TemporaryDirectory() as directory, fake_call(self.devices, case, directory) as (phones, instance):
            script(phones)
            manifest = instance.run()
            score_path = instance.output / "score.json"
            score = json.loads(score_path.read_text(encoding="utf-8")) if score_path.exists() else None
        return phones, manifest, score

    @staticmethod
    def dialed(phones, phone="a"):
        return [args[-1] for args in phones.sent(phone, "test_control") if args[-1].startswith(("call:", "voice_call:"))]

    @staticmethod
    def launches(phones, phone):
        return [index for index, (serial, args) in enumerate(phones.commands)
                if serial == phone and args[:3] == ["shell", "am", "start"] and "test_control" not in args]


class UserLaunchDirectCallTest(Fixture, unittest.TestCase):
    def test_a_user_launch_call_dials_the_callees_rotating_id_and_runs(self):
        phones, manifest, score = self.execute(self.case())
        self.assertNotIn("runner_error", manifest)
        self.assertEqual([f"call:ee{self.devices['b']['node_prefix'][2:]}"], self.dialed(phones))
        bound = [event for event in manifest["runner_events"] if event["action"] == "rotating_peer_bound"]
        self.assertEqual({"a", "b"}, {event["device"] for event in bound})
        self.assertTrue(all(event["reason"].endswith("rotid_own") for event in bound))
        # The scorer binds the scanned rotating ID to the peer through the peer's own log: discovery is measured.
        gates = {item["device"]: item["gates"]["discovery_ms"] for item in score["devices"]}
        self.assertTrue(all(gate["value"] is not None for gate in gates.values()), gates)

    def test_a_benchmark_launch_call_is_unchanged(self):
        phones, manifest, _ = self.execute(self.case(tunables={}))
        self.assertNotIn("runner_error", manifest)
        self.assertEqual(["call:" + self.devices["b"]["node_prefix"]], self.dialed(phones))
        self.assertEqual([], [args for _, args in phones.commands if args[:2] == ["shell", "run-as"] or
                              (len(args) > 1 and str(args[1]).startswith("run-as"))])
        self.assertNotIn("user_settings", manifest)

    def test_a_user_launch_without_stored_choices_touches_no_preferences(self):
        phones, manifest, _ = self.execute(self.case())
        self.assertEqual({}, phones.files)
        self.assertNotIn("user_settings", manifest)

    def test_a_user_launch_that_turns_a_shipped_privacy_default_off_is_blocked_before_any_launch(self):
        phones, manifest, _ = self.execute(self.case(tunables={"user_launch": True, "rotating_ids": False}))
        self.assertEqual("user_launch_default_overridden", manifest["runner_blocked"]["reason"])
        self.assertIn("rotating_ids=false", manifest["runner_blocked"]["detail"])
        self.assertEqual("precheck", manifest["call_not_dialed"]["stage"])
        self.assertEqual([], self.launches(phones, "a") + self.launches(phones, "b"))
        self.assertEqual([], self.dialed(phones))


class UserSettingsTest(Fixture, unittest.TestCase):
    def relayed_case(self, **changes):
        return self.case(relay="r", relay_shape="on_demand", route_wait_s=0,
                         tunables={"user_launch": True, "relayed_calls": True}, **changes)

    def stored(self, phones, phone):
        """The stored choices the app started with: the file is put back when the case ends."""
        return user_launch.read_bools(phones.prefs_at_launch[phone])

    def test_a_relayed_user_launch_call_stores_the_persons_choice_before_every_launch_and_dials(self):
        case = self.relayed_case(user_settings={"relayed_calls": True})
        phones, manifest, _ = self.execute(case)
        self.assertNotIn("runner_blocked", manifest)
        for phone in ("a", "b", "r"):
            self.assertEqual({"experimental_relayed_calls": True, "relayed_calls_prompted": True,
                              "relayed_calls_help": True, "relayed_calls_consent": True}, self.stored(phones, phone))
            writes = [index for index, (serial, args) in enumerate(phones.commands)
                      if serial == phone and len(args) > 1 and str(args[1]).startswith("run-as") and "mv -f" in args[1]]
            self.assertEqual(1, len(writes))
            self.assertLess(writes[0], self.launches(phones, phone)[0])
        self.assertEqual({"a", "b", "r"}, {record["device"] for record in manifest["user_settings"]})
        for record in manifest["user_settings"]:
            self.assertEqual({"relayed_calls": True}, record["settings"])
            self.assertTrue(all(record["read_back"].values()))
        rows = [row for check in manifest["applied_arms"] for row in check["arms"] if row.get("setting") == "relayed_calls"]
        self.assertEqual([True, True, True], [row["applied"] for row in rows])
        # The call is dialed to the callee's rotating ID (a relayed caller never scans the callee's own offer).
        self.assertEqual([f"call:ee{self.devices['b']['node_prefix'][2:]}"], self.dialed(phones))

    def test_other_stored_values_survive_the_write(self):
        def seed(phones):
            for phone in ("a", "b", "r"):
                phones.files[phone] = {PREFS: OTHER_PREFS}
        phones, _, _ = self.execute(self.relayed_case(user_settings={"relayed_calls": True}), seed)
        text = phones.prefs_at_launch["a"]
        self.assertIn("Ann &amp; Bo", text)
        self.assertEqual(OTHER_PREFS, phones.files["a"][PREFS])
        self.assertEqual({"onboarding_done": True, "experimental_relayed_calls": True, "relayed_calls_prompted": True,
                          "relayed_calls_help": True, "relayed_calls_consent": True}, self.stored(phones, "a"))
        self.assertIn('<int name="video_fps" value="15" />', text)

    def test_a_relayed_user_launch_call_without_the_setting_is_blocked_before_any_launch(self):
        phones, manifest, _ = self.execute(self.relayed_case())
        self.assertEqual("user_setting_missing", manifest["runner_blocked"]["reason"])
        self.assertIn("user_settings.relayed_calls", manifest["runner_blocked"]["detail"])
        self.assertEqual("precheck", manifest["call_not_dialed"]["stage"])
        self.assertEqual([], self.launches(phones, "a") + self.launches(phones, "b") + self.launches(phones, "r"))
        self.assertEqual({}, phones.files)

    def test_a_setting_the_app_does_not_resolve_blocks_the_case_before_the_dial(self):
        def ignores_the_file(phones):
            phones.ignore_stored.add("r")
        phones, manifest, _ = self.execute(self.relayed_case(user_settings={"relayed_calls": True}), ignores_the_file)
        self.assertEqual("user_setting_not_applied", manifest["runner_blocked"]["reason"])
        self.assertIn("relayed_calls stored true, app logged false", manifest["runner_blocked"]["detail"])
        self.assertIn("r:", manifest["runner_error"])
        self.assertEqual([], self.dialed(phones))
        self.assertEqual("relayed_route", manifest["call_not_dialed"]["stage"])

    def test_a_package_that_run_as_refuses_blocks_the_case_before_the_launch(self):
        def refused(phones):
            phones.unwritable.add("b")
        phones, manifest, _ = self.execute(self.relayed_case(user_settings={"relayed_calls": True}), refused)
        self.assertEqual("user_setting_not_stored", manifest["runner_blocked"]["reason"])
        self.assertEqual([], self.launches(phones, "b"))
        self.assertEqual([], self.dialed(phones))

    def test_a_wifi_direct_user_launch_call_stores_the_wifi_switches_a_person_turns_on(self):
        case = self.case(carrier="wifi_direct", transport="ble", user_settings={"wifi": True},
                         tunables={"user_launch": True})
        phones, manifest, _ = self.execute(case)
        self.assertNotIn("runner_blocked", manifest)
        for phone in ("a", "b"):
            self.assertEqual({"wifi_carrier": True, "wifi_direct": True}, self.stored(phones, phone))
        rows = {row["setting"]: row["applied"] for check in manifest["applied_arms"] for row in check["arms"]
                if "setting" in row}
        self.assertEqual({"wifi_carrier": True, "wifi_direct": True}, rows)
        self.assertEqual({"wifi_carrier": True, "wifi_direct": True}, manifest["user_settings"][0]["settings"])

    def test_a_wifi_user_launch_call_without_the_setting_is_blocked_before_any_launch(self):
        phones, manifest, _ = self.execute(self.case(carrier="wifi_aware", transport="ble"))
        self.assertEqual("user_setting_missing", manifest["runner_blocked"]["reason"])
        self.assertIn("user_settings.wifi", manifest["runner_blocked"]["detail"])
        self.assertEqual([], self.launches(phones, "a") + self.launches(phones, "b"))

    def test_a_static_relay_chain_under_a_user_launch_is_blocked_before_any_launch(self):
        phones, manifest, _ = self.execute(self.case(relay="r", user_settings={"relayed_calls": True}))
        self.assertEqual("user_launch_unsupported_route", manifest["runner_blocked"]["reason"])
        self.assertEqual([], self.launches(phones, "r"))


class UserSettingsRulesTest(Fixture, unittest.TestCase):
    def resolve(self, **fields):
        return user_launch.resolve(self.case(**fields))

    def test_wifi_means_the_carrier_switch_and_the_sub_switch_the_case_needs(self):
        self.assertEqual({"wifi_carrier": True}, self.resolve(user_settings={"wifi": True}, carrier="wifi_aware"))
        self.assertEqual({"wifi_carrier": True, "wifi_wider_reach": True},
                         self.resolve(user_settings={"wifi": True}, carrier="wifi_aware_wider"))
        self.assertEqual({"wifi_carrier": True, "wifi_direct": True},
                         self.resolve(user_settings={"wifi": True}, carrier="wifi_direct"))

    def test_a_setting_the_harness_cannot_write_is_refused_at_planning(self):
        for settings, message in (({"relayed": True}, "Unknown user setting"),
                                  ({"relayed_calls": "yes"}, "must be true or false"),
                                  ({"wifi_direct": True}, "counts only while wifi_carrier is on"),
                                  ({"wifi": True, "wifi_carrier": False}, "contradict"),
                                  ({}, "must be an object")):
            with self.subTest(settings=settings), self.assertRaisesRegex(ValueError, message):
                runner.validate_call_case(self.case(user_settings=settings, carrier="wifi_direct"), self.devices)

    def test_stored_choices_need_a_user_launch(self):
        with self.assertRaisesRegex(ValueError, "needs the tunable user_launch"):
            runner.validate_call_case(self.case(tunables={}, user_settings={"relayed_calls": True}), self.devices)

    def test_switching_a_choice_off_writes_the_switch_alone(self):
        self.assertEqual({"experimental_relayed_calls": False},
                         user_launch.pref_writes(self.resolve(user_settings={"relayed_calls": False})))

    def test_merge_keeps_every_other_value_and_updates_a_changed_one(self):
        merged = user_launch.merge_prefs(OTHER_PREFS.replace('"onboarding_done" value="true"', '"wifi_direct" value="false"'),
                                         {"wifi_direct": True, "wifi_carrier": True})
        self.assertEqual({"wifi_direct": True, "wifi_carrier": True}, user_launch.read_bools(merged))
        self.assertIn("Ann &amp; Bo", merged)
        with self.assertRaisesRegex(ValueError, "not a boolean"):
            user_launch.merge_prefs(OTHER_PREFS, {"video_fps": True})

    def test_a_large_file_is_written_in_pieces_the_device_shell_accepts(self):
        big = "<map>" + "".join(f'<string name="k{n}">{"x" * 500}</string>' for n in range(200)) + "</map>"
        commands = user_launch.write_commands("pkg", user_launch.merge_prefs(big, {"wifi_carrier": True}))
        self.assertGreater(len(commands), 20)
        self.assertLess(max(len(command) for command in commands), 4200)

    def test_the_apps_line_is_compared_with_the_stored_choice(self):
        on = "x BENCHMARK relayed_calls: enabled=true | help=true | consent=true\n"
        off = "x BENCHMARK relayed_calls: enabled=false | help=true | consent=true\n"
        wifi = "x BENCHMARK wifi_switches: carrier=on | wider=off | direct=off | probe=false\n"
        self.assertEqual([True], [row["applied"] for row in applied_arms.assess_settings({"relayed_calls": True}, on)])
        wrong = applied_arms.assess_settings({"relayed_calls": True}, off)
        self.assertEqual("user_setting_not_applied", applied_arms.blocking(wrong)[0])
        self.assertEqual([None], [row["applied"] for row in applied_arms.assess_settings({"relayed_calls": True}, "")])
        self.assertIsNone(applied_arms.blocking(applied_arms.assess_settings({"relayed_calls": True}, "")))
        rows = applied_arms.assess_settings({"wifi_carrier": True, "wifi_direct": True}, wifi)
        self.assertEqual({"wifi_carrier": True, "wifi_direct": False}, {row["setting"]: row["applied"] for row in rows})


class RotatingRoutePinTest(Fixture, unittest.TestCase):
    """The route pins name a rotating phone by the ID its own `rotid_own` line announced (the app compares a pin with the
    announced prefix); a registry prefix would match nothing and the caller would refuse every call."""

    def relayed_case(self, **changes):
        fields = {"relay": "r", "relay_shape": "on_demand", "route_wait_s": 0, "user_settings": {"relayed_calls": True},
                  "tunables": {"user_launch": True, "relayed_calls": True}, **changes}
        return self.case(**fields)

    @staticmethod
    def pin(phones, phone, extra):
        """The pin values every launch of `phone` carried, oldest first."""
        return [args[args.index(extra) + 1] for args in phones.sent(phone, extra)]

    def test_a_user_launch_relayed_call_pins_the_helper_and_the_next_hop_by_their_own_announced_ids_and_dials(self):
        phones, manifest, _ = self.execute(self.relayed_case())
        self.assertNotIn("runner_blocked", manifest)
        self.assertEqual(["ee0000000003"], self.pin(phones, "a", "test_relayed_calls_helper"))
        self.assertEqual(["ee0000000002"], self.pin(phones, "r", "test_relayed_calls_next_hop"))
        self.assertEqual([], phones.sent("b", "test_relayed_calls_helper") + phones.sent("b", "test_relayed_calls_next_hop"))
        self.assertEqual([f"call:ee{self.devices['b']['node_prefix'][2:]}"], self.dialed(phones))
        pins = {item["phone"]: item for item in manifest["route_pins"]}
        self.assertEqual(("helper", "r", "ee0000000003", "rotid_own"),
                         tuple(pins["a"][key] for key in ("kind", "peer", "id", "source")))
        self.assertEqual(("next_hop", "b", "ee0000000002", "rotid_own"),
                         tuple(pins["r"][key] for key in ("kind", "peer", "id", "source")))
        self.assertEqual(1, pins["a"]["epoch"])
        self.assertTrue(pins["a"]["peer_log_time"])
        self.assertFalse([event for event in manifest["relay_route_events"] if event["action"] == "rotating_pin_rebind"])

    def test_a_benchmark_launch_relayed_call_still_pins_by_the_registry_prefixes(self):
        case = self.relayed_case(tunables={"relayed_calls": True})
        case.pop("user_settings")
        phones, manifest, _ = self.execute(case)
        self.assertNotIn("runner_blocked", manifest)
        self.assertEqual(["000000000003"], self.pin(phones, "a", "test_relayed_calls_helper"))
        self.assertEqual(["000000000002"], self.pin(phones, "r", "test_relayed_calls_next_hop"))
        self.assertEqual({"registry_node_prefix"}, {item["source"] for item in manifest["route_pins"]})
        self.assertEqual(["call:" + self.devices["b"]["node_prefix"]], self.dialed(phones))

    def test_a_phone_that_logs_no_rotid_own_blocks_the_case_before_its_pinner_launches_and_before_the_dial(self):
        phones, manifest, _ = self.execute(self.relayed_case(), lambda phones: phones.no_rotid_own.add("b"))
        self.assertEqual("rotating_pin_unbound", manifest["runner_blocked"]["reason"])
        self.assertEqual("b", manifest["runner_blocked"]["device"])
        self.assertIn("rotid_own", manifest["runner_blocked"]["detail"])
        self.assertIn("r was not launched", manifest["runner_blocked"]["detail"])
        self.assertEqual("relayed_route", manifest["call_not_dialed"]["stage"])
        self.assertEqual([], self.launches(phones, "r") + self.launches(phones, "a"))
        self.assertEqual([], self.dialed(phones))

    def run_with_rotations(self, rotations):
        """The case, with an epoch boundary passing during the first `rotations` route-table waits (after every launch)."""
        case = self.relayed_case()
        with tempfile.TemporaryDirectory() as directory, fake_call(self.devices, case, directory) as (phones, instance):
            waits = []

            def wait(caller):
                waits.append(caller)
                if len(waits) <= rotations:
                    phones.rotate()
            instance.wait_on_demand_route_hint = wait
            manifest = instance.run()
        return phones, manifest

    def test_an_epoch_boundary_before_the_dial_relaunches_the_pinned_phones_with_the_new_ids_and_then_dials(self):
        phones, manifest = self.run_with_rotations(1)
        self.assertNotIn("runner_blocked", manifest)
        self.assertEqual(["ee0000000003", "e20000000003"], self.pin(phones, "a", "test_relayed_calls_helper"))
        self.assertEqual(["ee0000000002", "e20000000002"], self.pin(phones, "r", "test_relayed_calls_next_hop"))
        self.assertEqual(1, len(self.launches(phones, "b")))  # the callee keeps running: it only announces its new ID
        self.assertEqual(["call:e20000000002"], self.dialed(phones))
        rebinds = [event for event in manifest["relay_route_events"] if event["action"] == "rotating_pin_rebind"]
        self.assertEqual(1, len(rebinds))
        self.assertEqual({("a", "ee0000000003", "e20000000003"), ("r", "ee0000000002", "e20000000002")},
                         {(item["phone"], item["pinned"], item["now"]) for item in rebinds[0]["stale"]})
        self.assertEqual(4, len(manifest["route_pins"]))
        dial = next(index for index, (serial, args) in enumerate(phones.commands)
                    if serial == "a" and args[-1].startswith("call:"))
        self.assertLess(self.launches(phones, "a")[-1], dial)  # the dial follows the relaunch that carries the new pins

    def test_a_pin_that_keeps_going_stale_is_blocked_before_the_dial_after_the_bounded_relaunches(self):
        phones, manifest = self.run_with_rotations(10)
        self.assertEqual("rotating_pin_stale", manifest["runner_blocked"]["reason"])
        self.assertIn("pins r as", manifest["runner_blocked"]["detail"])
        self.assertEqual(1 + runner.PIN_REBIND_MAX, len(self.launches(phones, "a")))
        self.assertEqual([], self.dialed(phones))
        self.assertEqual("relayed_route", manifest["call_not_dialed"]["stage"])


class RotatingRouteScoringTest(Fixture, unittest.TestCase):
    """A relayed route is named by rotating IDs in the helper's and the caller's lines; only each phone's own log binds one."""

    prefixes = {"a": "000000000001", "b": "000000000002", "r": "000000000003"}

    def gates(self, target, helper, ids):
        case = self.case(relay="r", relay_shape="on_demand")
        relay_rows = [{"message": f"BENCHMARK relayed_setup: role=helper | result=accepted | budget=1 | next_budget=0 | "
                                  f"next=target:{target}"}]
        caller_rows = [{"message": f"BENCHMARK relayed_route: role=caller | target={target} | helper={helper} | id=rdi | "
                                   "budget=1"}]
        return runner.on_demand_hop_gates(case, 0, self.prefixes, relay_rows, caller_rows, ids)

    def test_the_rotating_ids_the_phones_announced_prove_the_requested_helper_and_target(self):
        ids = {"b": {"000000000002", "ee0000000002"}, "r": {"000000000003", "ee0000000003"}}
        gates = self.gates("ee0000000002", "ee0000000003", ids)
        self.assertEqual("PASS", gates["helper_accepted_requested_next_hop"]["status"])
        self.assertEqual("PASS", gates["caller_selected_requested_helper"]["status"])

    def test_an_id_no_requested_phone_announced_is_a_failed_chain(self):
        ids = {"b": {"000000000002", "ee0000000002"}, "r": {"000000000003", "ee0000000003"}}
        gates = self.gates("ee0000000002", "ee0000000009", ids)
        self.assertEqual("FAIL", gates["caller_selected_requested_helper"]["status"])
        gates = self.gates("ee0000000009", "ee0000000003", ids)
        self.assertEqual("FAIL", gates["helper_accepted_requested_next_hop"]["status"])

    def test_without_announced_ids_only_the_registry_prefix_counts(self):
        gates = self.gates("ee0000000002", "ee0000000003", None)
        self.assertEqual("FAIL", gates["caller_selected_requested_helper"]["status"])


class SuitePreflightTest(Fixture, unittest.TestCase):
    def test_the_suite_blocks_an_impossible_user_launch_case_before_it_plans_a_run(self):
        with self.assertRaisesRegex(suite.PrerequisiteError, "user_setting_missing.*user_settings.relayed_calls"):
            suite.validate_capabilities(self.case(relay="r", relay_shape="on_demand"), {})

    def test_a_benchmark_launch_case_is_not_touched(self):
        capabilities = {phone: {"encoders": []} for phone in ("a", "b")}
        with self.assertRaisesRegex(suite.PrerequisiteError, "encoder/allocation is not offered"):
            suite.validate_capabilities(self.case(tunables={}, relay="r"), capabilities)


class RestoreStoredChoicesTest(Fixture, unittest.TestCase):
    """The stored choices a user-launch case wrote are taken back at cleanup, byte for byte."""

    CRLF = chr(13) + chr(10)
    SEED = (OTHER_PREFS.replace(chr(10), CRLF)
            .replace("</map>", '    <boolean name="wifi_direct" value="false" />' + CRLF + "</map>") + "  " + CRLF)

    def wifi_case(self):
        return self.case(carrier="wifi_direct", transport="ble", user_settings={"wifi": True})

    def relayed_case(self):
        return self.case(relay="r", relay_shape="on_demand", route_wait_s=0, user_settings={"relayed_calls": True},
                         tunables={"user_launch": True, "relayed_calls": True})

    @staticmethod
    def seeded(*phone_names):
        def script(phones):
            phones.shell_adds_cr = True
            for phone in phone_names:
                phones.files[phone] = {PREFS: RestoreStoredChoicesTest.SEED}
        return script

    def restored(self, manifest):
        return {item["device"]: item for item in manifest["user_settings_restored"]}

    def test_the_file_is_back_exactly_as_found_after_the_call(self):
        phones, manifest, _ = self.execute(self.wifi_case(), self.seeded("a", "b"))
        for phone in ("a", "b"):
            self.assertEqual(self.SEED, phones.files[phone][PREFS])
            item = self.restored(manifest)[phone]
            self.assertEqual("RESTORED", item["status"])
            self.assertEqual(item["sha256_before"], item["sha256_after"])
        # The choices really were on during the call.
        self.assertTrue(all(record["read_back"]["wifi_direct"] for record in manifest["user_settings"]))

    def test_a_file_that_did_not_exist_is_deleted_again(self):
        phones, manifest, _ = self.execute(self.wifi_case())
        for phone in ("a", "b"):
            self.assertNotIn(PREFS, phones.files[phone])
            self.assertEqual({"status": "RESTORED", "existed": False}, {
                key: self.restored(manifest)[phone][key] for key in ("status", "existed")})

    def test_a_case_blocked_after_the_write_still_restores(self):
        def script(phones):
            self.seeded("a", "b", "r")(phones)
            phones.ignore_stored.add("r")  # the app does not resolve the stored choice: BLOCKED after the write
        phones, manifest, _ = self.execute(self.relayed_case(), script)
        self.assertEqual("user_setting_not_applied", manifest["runner_blocked"]["reason"])
        for phone in ("a", "b", "r"):
            self.assertEqual(self.SEED, phones.files[phone][PREFS])
        self.assertEqual({"RESTORED"}, {item["status"] for item in manifest["user_settings_restored"]})

    def test_a_case_that_fails_after_the_write_still_restores(self):
        def script(phones):
            self.seeded("a", "b")(phones)
            phones.silent["b"] = ("BENCHMARK call phase INCOMING_RINGING",)  # the call never rings: the case fails
        phones, manifest, _ = self.execute(self.wifi_case(), script)
        self.assertIn("runner_error", manifest)
        for phone in ("a", "b"):
            self.assertEqual(self.SEED, phones.files[phone][PREFS])

    def test_a_restore_that_cannot_be_verified_is_flagged_and_the_phone_is_quarantined(self):
        def script(phones):
            self.seeded("a", "b")(phones)
            phones.readonly_after_launch.add("a")
        phones, manifest, _ = self.execute(self.wifi_case(), script)
        items = self.restored(manifest)
        self.assertEqual(("UNVERIFIED", "RESTORED"), (items["a"]["status"], items["b"]["status"]))
        self.assertIn("error", items["a"])
        self.assertEqual(self.SEED, phones.files["b"][PREFS])
        # The suite's cleanup receipt quarantines exactly the unverified phone.
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            (output / "physical").mkdir()
            (output / "physical" / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            quarantined = []
            lease = type("Lease", (), {"quarantine_phone": lambda self, phone, reason, path: quarantined.append((phone, reason))})()
            stopped = lambda command, **_: subprocess.CompletedProcess(command, 1, "", "")
            with patch.object(suite.subprocess, "run", stopped):
                with self.assertRaisesRegex(RuntimeError, "unconfirmed"):
                    suite.verify_phone_cleanup("adb", self.devices, ["a", "b"], output, lease, sleep=lambda _: None)
        self.assertEqual([("a", "Stored user settings could not be restored")], quarantined)

    def test_a_benchmark_launch_snapshots_and_restores_nothing(self):
        phones, manifest, _ = self.execute(self.case(tunables={}), self.seeded("a", "b"))
        self.assertNotIn("user_settings_restored", manifest)
        self.assertEqual(self.SEED, phones.files["a"][PREFS])


if __name__ == "__main__":
    unittest.main()


class LiveDisclosureTest(unittest.TestCase):
    """A person who goes live has confirmed the one-time "Before you go live" notice; Go live never waits on it."""

    def stream_case(self, **user_settings):
        case = {"source": "a", "receivers": ["b"], "tunables": {"user_launch": True}}
        if user_settings:
            case["user_settings"] = user_settings
        return case

    def settings_for(self, case, phone):
        import types
        import run_device_benchmark
        runner_like = types.SimpleNamespace(case=case, settings_case=case)
        return run_device_benchmark.Runner.settings_for(runner_like, phone)

    def test_the_user_launch_source_stores_the_confirmed_notice(self):
        settings = self.settings_for(self.stream_case(), "a")
        self.assertEqual(settings, {"live_disclosed": True})
        self.assertEqual(user_launch.pref_writes(settings), {"video_live_disclosed": True})

    def test_a_viewer_and_a_call_endpoint_store_nothing_for_it(self):
        self.assertEqual(self.settings_for(self.stream_case(), "b"), {})
        call = {"caller": "a", "callee": "b", "tunables": {"user_launch": True}}
        import types
        import run_device_benchmark
        launch = types.SimpleNamespace(case={"source": "a", "receivers": [{"phone": "b"}]}, settings_case=call)
        self.assertEqual(run_device_benchmark.Runner.settings_for(launch, "a"), {})

    def test_a_case_that_states_the_first_run_notice_keeps_it(self):
        settings = self.settings_for(self.stream_case(live_disclosed=False), "a")
        self.assertEqual(user_launch.pref_writes(settings), {"video_live_disclosed": False})

    def test_the_stored_value_is_the_evidence_since_the_app_logs_no_line(self):
        rows = user_launch.assess({"live_disclosed": True}, "BENCHMARK wifi_switches: carrier=on")
        self.assertIsNone(rows[0]["applied"])
        self.assertIsNone(user_launch.blocking(rows))
