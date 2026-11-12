"""wifi_census: parsing, privacy, restore safety and probe refusal on anonymised fixtures."""
import io
import json
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest import mock

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import wifi_census as census  # noqa: E402

FIXTURES = Path(__file__).resolve().parent / "fixtures/wifi_census"
FIXED_SALT = bytes(range(32))
RAW_SSID = "HomeNet-5G"
RAW_BSSID = "3c:5a:b4:11:22:33"
RAW_MAC = "9a:11:22:33:44:55"
RAW_P2P_ADDRESS = "7e:22:33:44:55:66"
RAW_P2P_PEER = "12:34:56:78:9a:bc"
RAW_P2P_NAME = "Galaxy-S22"
RAW_P2P_PEER_NAME = "Peer-One"
RAW_GROUP_SSID = "DIRECT-ab-GroupNet"
RAW_ACTIVE_SSID = "DIRECT-xy-ActiveNet"
RAW_ACTIVE_OWNER = "Owner-Three"
EXPECTED_P2P_HASH = "29117597c719519a"  # colon/hyphen/plain forms normalise to one value
PROBE_PATH = f"/sdcard/Android/data/{census.APP_ID}/files/wifi-capabilities.json"


def fixture(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def anonymizer() -> census.Anonymizer:
    return census.Anonymizer(FIXED_SALT)


def digest(value: str) -> str:
    return anonymizer().value(value)


class FakeAdb:
    """Serves one phone fixture, including a stateful Wi-Fi switch and app service state."""

    def __init__(self, phone: dict, state: str = "device", installed: bool = True,
                 status_unavailable: bool = False):
        self.phone = phone
        self.state = state
        self.installed = installed
        self.status_unavailable = status_unavailable
        self.wifi_on = phone["wifi_initially_enabled"]
        self.calls = []
        self.set_wifi_calls = []
        self.set_wifi_results = {"enabled": census.CommandResult(0),
                                 "disabled": census.CommandResult(0)}
        self.app_services = ""
        self.app_services_ok = True
        self.removed_paths = []
        self.fail = {}
        self.probe_text = (FIXTURES / "probe.json").read_text(encoding="utf-8")

    def get_state(self, serial: str):
        self.calls.append(("get-state",))
        return census.CommandResult(0, self.state + "\n")

    def shell(self, serial: str, *args: str, timeout: int = 30):
        args = tuple(args)
        self.calls.append(args)
        if args in self.fail:
            raise self.fail[args]
        if args[:3] == ("dumpsys", "activity", "services"):
            return census.CommandResult(0 if self.app_services_ok else 1, self.app_services)
        if args == ("cmd", "wifi", "status"):
            if self.status_unavailable:
                return census.CommandResult(1, "", "Security exception")
            text = self.phone["wifi_status_on"] if self.wifi_on else self.phone["wifi_status_off"]
            return census.CommandResult(0, text)
        if args == ("settings", "get", "global", "wifi_on"):
            return census.CommandResult(0, "1\n" if self.wifi_on else "0\n")
        if args[:3] == ("cmd", "wifi", "set-wifi-enabled"):
            value = args[3]
            self.set_wifi_calls.append(value)
            result = self.set_wifi_results[value]
            if result.ok:
                self.wifi_on = value == "enabled"
            return result
        if args[:2] == ("getprop", "ro.product.model"):
            return census.CommandResult(0, self.phone["model"] + "\n")
        if args[:2] == ("getprop", "ro.build.version.sdk"):
            return census.CommandResult(0, f"{self.phone['sdk']}\n")
        if args[:2] == ("getprop", "ro.soc.model"):
            return census.CommandResult(0, (self.phone["soc"] + "\n") if self.phone["soc"] else "")
        if args[:2] == ("pm", "has-feature"):
            name = args[2].rsplit(".", 1)[-1]
            supported = self.phone["features"][name]
            return census.CommandResult(0 if supported else 1,
                                        "true\n" if supported else "false\n")
        if args[:2] == ("pm", "path"):
            return census.CommandResult(0, "package:/data/app/base.apk\n" if self.installed else "")
        if args[:2] == ("dumpsys", "wifiaware"):
            return census.CommandResult(0, self.phone["wifiaware"])
        if args[:2] == ("dumpsys", "wifip2p"):
            return census.CommandResult(0, self.phone["wifip2p"])
        if args[:2] == ("dumpsys", "wifi"):
            return census.CommandResult(0, self.phone["wifi"])
        if args[0] == "rm":
            if len(args) > 2:
                self.removed_paths.append(args[2])
            return census.CommandResult(0)
        if args[0] == "am":
            return census.CommandResult(0, "OK (1 test)\n\nINSTRUMENTATION_CODE: -1\n")
        return census.CommandResult(0)

    def exec_out(self, serial: str, *args: str, timeout: int = 30):
        self.calls.append(("exec-out",) + tuple(args))
        if args[:2] == ("cat", PROBE_PATH):
            return census.CommandResult(0, self.probe_text)
        return census.CommandResult(0, "")


class RunResult:
    def __init__(self, code: int, output: Path, fake: FakeAdb, stderr: str):
        self.code = code
        self.output = output
        self.fake = fake
        self.stderr = stderr


class CensusTest(unittest.TestCase):
    def run_main(self, phone=None, probe=False, installed=True, state="device", phones="s22",
                 probe_ok=False, app_services="", app_services_ok=True):
        phone = phone or fixture("android14_aware_5ghz.json")
        fake = FakeAdb(phone, state=state, installed=installed)
        fake.app_services = app_services
        fake.app_services_ok = app_services_ok
        folder = Path(tempfile.mkdtemp(prefix="wifi-census-test-"))
        self.addCleanup(shutil.rmtree, folder, True)
        devices = folder / "devices.json"
        devices.write_text(json.dumps({"devices": {alias: {"serial": f"SERIAL-{alias}"}
                                                   for alias in phones.split(",")}}), encoding="utf-8")
        output = folder / "wifi-census-20261104-1200"
        argv = ["--devices", str(devices), "--phones", phones, "--adb", "adb", "--output", str(output)]
        if probe:
            argv.append("--probe")
        if probe_ok:
            argv.append("--probe-ok-to-stop-app")
        stderr = io.StringIO()
        with mock.patch.object(census.secrets, "token_bytes", return_value=FIXED_SALT), \
                mock.patch.object(census, "Adb", return_value=fake), redirect_stderr(stderr):
            code = census.main(argv)
        return RunResult(code, output, fake, stderr.getvalue())

    def collect(self, phone=None, fake=None, alias="s22"):
        phone = phone or fixture("android14_aware_5ghz.json")
        fake = fake or FakeAdb(phone)
        with mock.patch.object(census.secrets, "token_bytes", return_value=FIXED_SALT):
            return census._collect(fake, alias, "SERIAL", anonymizer(), False), fake

    def test_parses_identity_and_features(self):
        run = self.run_main()
        self.assertEqual(run.code, 0)
        phone = json.loads((run.output / "s22.json").read_text(encoding="utf-8"))
        self.assertEqual(phone["status"], "measured")
        self.assertEqual(phone["model"], "SM-S901E")
        self.assertEqual(phone["sdk"], 34)
        self.assertEqual(phone["soc_model"], "SM8450")
        self.assertEqual(phone["features"], {"wifi_aware": True, "wifi_direct": True, "wifi_rtt": True})

    def test_hashes_network_names_and_addresses(self):
        run = self.run_main()
        phone = json.loads((run.output / "s22.json").read_text(encoding="utf-8"))
        self.assertTrue(phone["wifi"]["enabled"])
        self.assertTrue(phone["wifi"]["connected"])
        self.assertEqual(phone["wifi"]["band_ghz"], 5)
        self.assertEqual(phone["wifi"]["ssid_sha256"], digest(RAW_SSID))
        self.assertEqual(phone["wifi"]["bssid_sha256"], digest(RAW_BSSID))
        self.assertEqual(phone["p2p"]["device_address_sha256"], "not_printed")

    def test_parses_aware_capability_block(self):
        run = self.run_main()
        aware = json.loads((run.output / "s22.json").read_text(encoding="utf-8"))["aware"]
        self.assertTrue(aware["mUsageEnabled"])
        self.assertTrue(aware["availability"])
        self.assertEqual(aware["attachedClients"], 2)
        self.assertEqual(aware["maxNdiInterfaces"], 1)
        self.assertEqual(aware["maxNdpSessions"], 5)
        self.assertEqual(aware["maxPublishes"], 6)
        self.assertEqual(aware["maxSubscribes"], 6)
        self.assertEqual(aware["supportedCipherSuites"], 1)
        self.assertFalse(aware["isInstantCommunicationModeSupported"])
        self.assertFalse(aware["isNanPairingSupported"])
        self.assertFalse(aware["isSuspensionSupported"])
        self.assertFalse(aware["is6gSupported"])
        self.assertFalse(aware["isHeSupported"])

    def test_parses_legacy_capability_spelling(self):
        record, _ = self.collect(fixture("android13_aware_wifi_off.json"), alias="a55")
        self.assertTrue(record["aware"]["isInstantCommunicationModeSupported"])
        self.assertEqual(record["aware"]["maxNdiInterfaces"], 1)
        self.assertEqual(record["aware"]["maxNdpSessions"], 2)
        self.assertEqual(record["aware"]["attachedClients"], 1)

    def test_parses_saved_direct_group_from_real_dump(self):
        phone = fixture("android14_aware_5ghz.json")
        record = census.parse_wifip2p(phone["wifip2p"])
        self.assertEqual(record["state"], "idle")
        self.assertTrue(record["persistent_group_saved"])
        self.assertEqual(record["device_address_randomization"], "not_printed")
        self.assertEqual(record["device_address_sha256"], "not_printed")
        dumped = json.dumps(record)
        for raw in (RAW_GROUP_SSID, RAW_P2P_NAME, RAW_P2P_PEER_NAME, RAW_P2P_ADDRESS,
                    RAW_P2P_PEER):
            self.assertNotIn(raw, dumped)

    def test_empty_saved_group_list_is_false(self):
        phone = fixture("android12_no_aware_24ghz.json")
        record = census.parse_wifip2p(phone["wifip2p"])
        self.assertEqual(record["state"], "disconnected")
        self.assertFalse(record["persistent_group_saved"])
        self.assertEqual(record["device_address_randomization"], "not_printed")

    def test_missing_saved_group_section_is_not_measured(self):
        phone = fixture("android13_aware_wifi_off.json")
        record = census.parse_wifip2p(phone["wifip2p"])
        self.assertEqual(record["persistent_group_saved"], "not_measured")

    def test_active_group_without_saved_network_is_not_persistent(self):
        phone = fixture("android10_vendor_limited.json")
        record = census.parse_wifip2p(phone["wifip2p"])
        self.assertEqual(record["state"], "connected")
        self.assertFalse(record["persistent_group_saved"])
        self.assertNotIn(RAW_ACTIVE_SSID, json.dumps(record))
        self.assertNotIn(RAW_ACTIVE_OWNER, json.dumps(record))

    def test_group_without_network_id_is_not_measured(self):
        text = "mGroups network: DIRECT-xy-Broken\n isGO: false\n\nmAutonomousGroup false\n"
        record = census.parse_wifip2p(text)
        self.assertEqual(record["persistent_group_saved"], "not_measured")

    def test_mclients_without_brackets_is_counted(self):
        parsed = census.parse_wifiaware(
            "  mClients: {0=com.android.server.wifi.aware.WifiAwareClientState@aa11}\n")
        self.assertEqual(parsed["attachedClients"], 1)

    def test_new_chip_numbering(self):
        phone = fixture("android14_aware_5ghz.json")
        record = census.parse_concurrency(phone["wifi"])
        self.assertTrue(record["sta_aware"])
        self.assertTrue(record["sta_p2p"])
        self.assertFalse(record["p2p_aware"])

    def test_old_chip_numbering(self):
        phone = fixture("android12_no_aware_24ghz.json")
        record = census.parse_concurrency(phone["wifi"])
        self.assertTrue(record["sta_aware"])
        self.assertTrue(record["sta_p2p"])
        self.assertFalse(record["p2p_aware"])

    def test_ambiguous_numeric_numbering_is_unknown(self):
        text = ("Dump of HalDeviceManager:\n"
                "WifiChipInfo {availableCombinations=[{limits=[{.types = [0], .maxIfaces = 1}, "
                "{.types = [2], .maxIfaces = 1}]}]}\n"
                "WifiChipInfo {availableCombinations=[{limits=[{types=[0], maxIfaces=1}, "
                "{types=[3], maxIfaces=1}]}]}\n")
        self.assertEqual(census.parse_concurrency(text),
                         {"sta_aware": "unknown", "sta_p2p": "unknown", "p2p_aware": "unknown"})

    def test_type_four_forces_the_new_numbering(self):
        text = ("Dump of HalDeviceManager:\nWifiChipInfo {availableCombinations=[{limits=["
                "{.types = [0], .maxIfaces = 1}, {.types = [4], .maxIfaces = 1}]}]}\n")
        record = census.parse_concurrency(text)
        self.assertTrue(record["sta_aware"])
        self.assertIsNone(record["sta_p2p"])
        self.assertIsNone(record["p2p_aware"])

    def test_partial_chip_modes_leave_pairs_unknown(self):
        text = ("Dump of HalDeviceManager:\nWifiChipInfo {availableCombinations=[{limits=["
                "{types=[0], maxIfaces=1}, {types=[1], maxIfaces=1}]}]}\n")
        self.assertEqual(census.parse_concurrency(text),
                         {"sta_aware": None, "sta_p2p": None, "p2p_aware": None})

    def test_chip_fallbacks_are_anchored_to_their_sections(self):
        decoy = "STA=1, AP=0, P2P=1, NAN=1\nSTA + P2P Concurrency Supported: true\n"
        self.assertEqual(census.parse_concurrency(decoy),
                         {"sta_aware": None, "sta_p2p": None, "p2p_aware": None})
        readable = ("Dump of ActiveModeWarden:\n"
                    "STA + Aware Concurrency Supported: true\n"
                    "STA + P2P Concurrency Supported: true\n"
                    "P2P + Aware Concurrency Supported: false\n")
        self.assertEqual(census.parse_concurrency(readable),
                         {"sta_aware": True, "sta_p2p": True, "p2p_aware": False})
        chip_map = ("Dump of HalDeviceManager:\n"
                    "WifiChipInfo {STA=1, AP=0, P2P=0, NAN=1}\n")
        self.assertEqual(census.parse_concurrency(chip_map),
                         {"sta_aware": True, "sta_p2p": False, "p2p_aware": False})

    def test_missing_blocks_stay_unmeasured(self):
        self.assertEqual(census.parse_wifiaware(""), {
            "mUsageEnabled": None, "availability": None, "attachedClients": None,
            "maxNdiInterfaces": None, "maxNdpSessions": None, "maxPublishes": None,
            "maxSubscribes": None, "supportedCipherSuites": None,
            "isInstantCommunicationModeSupported": None, "isNanPairingSupported": None,
            "isSuspensionSupported": None, "is6gSupported": None, "isHeSupported": None,
        })
        self.assertEqual(census.parse_concurrency(""),
                         {"sta_aware": None, "sta_p2p": None, "p2p_aware": None})

    def test_empty_client_list_is_zero(self):
        self.assertEqual(census.parse_wifiaware("  mClients: [{}]\n"),
                         {**census.parse_wifiaware(""), "attachedClients": 0})

    def test_disconnected_wifiinfo_does_not_take_a_saved_ssid(self):
        text = ('SSID: "SavedNet"\n'
                'mWifiInfo SSID: <unknown ssid>, BSSID: <none>, Supplicant state: DISCONNECTED, '
                'Frequency: 2437MHz\n')
        result = census.parse_wifi_status(text, anonymizer())
        self.assertIsNone(result["connected"])
        self.assertIsNone(result["ssid_sha256"])
        self.assertIsNone(result["bssid_sha256"])
        self.assertIsNone(result["band_ghz"])

    def test_has_feature_false_is_a_value_even_with_exit_1(self):
        fake = FakeAdb(fixture("android12_no_aware_24ghz.json"))
        record, _ = self.collect(fake=fake, alias="m31")
        self.assertFalse(record["features"]["wifi_aware"])
        self.assertTrue(record["features"]["wifi_direct"])
        self.assertFalse(record["features"]["wifi_rtt"])
        self.assertNotIn("pm has-feature android.hardware.wifi.aware", record["commands_failed"])

    def test_vendor_limited_phone_falls_back_to_settings_and_dumpsys(self):
        phone = fixture("android10_vendor_limited.json")
        fake = FakeAdb(phone, status_unavailable=True)
        record, _ = self.collect(fake=fake, alias="a15s")
        self.assertTrue(record["wifi"]["enabled"])
        self.assertTrue(record["wifi"]["connected"])
        self.assertEqual(record["wifi"]["band_ghz"], 2)
        self.assertEqual(record["wifi"]["ssid_sha256"], digest("Cafe-Guest"))
        self.assertEqual(record["wifi"]["bssid_sha256"], digest("44:55:66:77:88:99"))
        self.assertEqual(fake.set_wifi_calls, [])

    def test_switches_wifi_on_for_aware_and_restores_it(self):
        record, fake = self.collect(fixture("android13_aware_wifi_off.json"), alias="a55")
        self.assertFalse(record["wifi"]["original_enabled"])
        self.assertTrue(record["wifi"]["enabled_by_census"])
        self.assertTrue(record["wifi"]["restored"])
        self.assertEqual(fake.set_wifi_calls, ["enabled", "disabled"])
        self.assertEqual(record["wifi"]["band_ghz"], 5)
        self.assertTrue(record["aware"]["availability"])

    def test_switches_wifi_without_cmd_wifi_status(self):
        phone = fixture("android13_aware_wifi_off.json")
        fake = FakeAdb(phone, status_unavailable=True)
        record, _ = self.collect(fake=fake, alias="a55")
        self.assertTrue(record["wifi"]["enabled_by_census"])
        self.assertTrue(record["wifi"]["enabled"])
        self.assertTrue(record["wifi"]["restored"])
        self.assertEqual(fake.set_wifi_calls, ["enabled", "disabled"])

    def test_does_not_touch_wifi_without_aware(self):
        phone = fixture("android12_no_aware_24ghz.json")
        phone["wifi_initially_enabled"] = False
        record, fake = self.collect(phone, alias="m31")
        self.assertEqual(fake.set_wifi_calls, [])
        self.assertFalse(record["wifi"]["enabled_by_census"])
        self.assertIsNone(record["wifi"]["restored"])
        self.assertFalse(record["aware"]["availability"])

    def test_restores_wifi_after_a_command_error(self):
        fake = FakeAdb(fixture("android13_aware_wifi_off.json"))
        fake.fail[("dumpsys", "wifiaware")] = RuntimeError(f"failed near {RAW_BSSID}")
        record, _ = self.collect(fake=fake, alias="a55")
        self.assertEqual(record["status"], "error")
        self.assertIn("RuntimeError", record["unavailable_reason"])
        self.assertEqual(fake.set_wifi_calls, ["enabled", "disabled"])
        self.assertTrue(record["wifi"]["enabled_by_census"])
        self.assertTrue(record["wifi"]["restored"])

    def test_enable_timeout_still_restores(self):
        fake = FakeAdb(fixture("android13_aware_wifi_off.json"))
        fake.set_wifi_results["enabled"] = census.CommandResult(124)
        record, _ = self.collect(fake=fake, alias="a55")
        self.assertTrue(record["wifi"]["enabled_by_census"])
        self.assertTrue(record["wifi"]["restored"])
        self.assertEqual(fake.set_wifi_calls, ["enabled", "disabled"])

    def test_keyboard_interrupt_after_enable_still_restores(self):
        fake = FakeAdb(fixture("android13_aware_wifi_off.json"))
        fake.fail[("dumpsys", "wifiaware")] = KeyboardInterrupt()
        with self.assertRaises(KeyboardInterrupt):
            self.collect(fake=fake, alias="a55")
        self.assertEqual(fake.set_wifi_calls, ["enabled", "disabled"])

    def test_keyboard_interrupt_during_enable_still_restores(self):
        fake = FakeAdb(fixture("android13_aware_wifi_off.json"))
        fake.fail[("cmd", "wifi", "set-wifi-enabled", "enabled")] = KeyboardInterrupt()
        with self.assertRaises(KeyboardInterrupt):
            self.collect(fake=fake, alias="a55")
        self.assertEqual(fake.set_wifi_calls, ["disabled"])

    def test_failed_restore_is_reported_and_exits_nonzero(self):
        phone = fixture("android13_aware_wifi_off.json")
        fake = FakeAdb(phone)
        fake.set_wifi_results["disabled"] = census.CommandResult(1)
        folder = Path(tempfile.mkdtemp(prefix="wifi-census-test-"))
        self.addCleanup(shutil.rmtree, folder, True)
        devices = folder / "devices.json"
        devices.write_text(json.dumps({"devices": {"a55": {"serial": "S"}}}), encoding="utf-8")
        output = folder / "run"
        stderr = io.StringIO()
        with mock.patch.object(census.secrets, "token_bytes", return_value=FIXED_SALT), \
                mock.patch.object(census, "Adb", return_value=fake), redirect_stderr(stderr):
            code = census.main(["--devices", str(devices), "--phones", "a55",
                                "--output", str(output)])
        self.assertEqual(code, 1)
        self.assertIn("a55", stderr.getvalue())
        self.assertIn("restore FAILED", stderr.getvalue())
        record = json.loads((output / "a55.json").read_text(encoding="utf-8"))
        self.assertFalse(record["wifi"]["restored"])

    def test_unavailable_phone_is_recorded_without_shell_commands(self):
        run = self.run_main(state="unauthorized")
        self.assertEqual(run.code, 1)
        phone = json.loads((run.output / "s22.json").read_text(encoding="utf-8"))
        self.assertEqual(phone["status"], "unavailable")
        self.assertEqual(phone["unavailable_reason"], "unauthorized")
        self.assertNotIn("shell", [call[0] for call in run.fake.calls])

    def test_probe_is_not_run_without_the_flag(self):
        run = self.run_main()
        phone = json.loads((run.output / "s22.json").read_text(encoding="utf-8"))
        self.assertEqual(phone["probe"], {"status": "not_requested"})
        self.assertNotIn(("pm", "path", f"{census.APP_ID}.test"), run.fake.calls)
        self.assertNotIn("am", [call[0] for call in run.fake.calls])

    def test_probe_refuses_when_test_apk_is_not_installed(self):
        run = self.run_main(probe=True, installed=False)
        phone = json.loads((run.output / "s22.json").read_text(encoding="utf-8"))
        self.assertEqual(phone["probe"]["status"], "not_installed")
        self.assertNotIn("am", [call[0] for call in run.fake.calls])
        self.assertFalse((run.output / "s22-wifi-capabilities.json").exists())

    def test_probe_refuses_while_the_session_service_is_running(self):
        run = self.run_main(probe=True,
                            app_services="ServiceRecord{aa11 io.github.meshlive.app/.ActiveVideoSessionService}")
        phone = json.loads((run.output / "s22.json").read_text(encoding="utf-8"))
        self.assertEqual(phone["probe"]["status"], "app_active")
        self.assertNotIn("am", [call[0] for call in run.fake.calls])
        self.assertIn("s22", run.stderr)

    def test_probe_refuses_when_app_state_is_unknown(self):
        run = self.run_main(probe=True, app_services_ok=False)
        phone = json.loads((run.output / "s22.json").read_text(encoding="utf-8"))
        self.assertEqual(phone["probe"]["status"], "app_unknown")
        self.assertNotIn("am", [call[0] for call in run.fake.calls])

    def test_probe_ok_to_stop_app_bypasses_the_service_check(self):
        run = self.run_main(probe=True, probe_ok=True,
                            app_services="ServiceRecord{aa11 io.github.meshlive.app/.ActiveVideoSessionService}")
        phone = json.loads((run.output / "s22.json").read_text(encoding="utf-8"))
        self.assertEqual(phone["probe"]["status"], "ok")
        self.assertIn("am", [call[0] for call in run.fake.calls])
        self.assertIn("force-stop", run.stderr)

    def test_probe_runs_pulls_merges_and_deletes_the_phone_file(self):
        run = self.run_main(probe=True)
        phone = json.loads((run.output / "s22.json").read_text(encoding="utf-8"))
        self.assertEqual(phone["probe"]["status"], "ok")
        self.assertEqual(phone["probe"]["capabilities"]["api"], 34)
        self.assertNotIn("p2p_address_matches", phone["probe"])
        pulled = run.output / "s22-wifi-capabilities.json"
        self.assertTrue(pulled.is_file())
        self.assertEqual(json.loads(pulled.read_text(encoding="utf-8")), fixture("probe.json"))
        instrument = next(call for call in run.fake.calls if call[0] == "am")
        self.assertIn("-e", instrument)
        self.assertIn("wifi_census_salt", instrument)
        self.assertIn(FIXED_SALT.hex(), instrument)
        self.assertIn(census.TEST_CLASS, instrument)
        self.assertEqual(run.fake.removed_paths[-1], PROBE_PATH)
        self.assertGreaterEqual(run.fake.removed_paths.count(PROBE_PATH), 2)

    def test_output_files_never_contain_addresses_or_names(self):
        run = self.run_main(probe=True)
        texts = [path.read_text(encoding="utf-8").lower()
                 for path in run.output.rglob("*") if path.is_file()]
        joined = "\n".join(texts)
        for raw in (RAW_SSID, RAW_BSSID, RAW_MAC, RAW_P2P_ADDRESS, RAW_P2P_NAME,
                    RAW_P2P_PEER_NAME, RAW_GROUP_SSID, RAW_ACTIVE_SSID, RAW_ACTIVE_OWNER,
                    "Cafe-Guest", "44:55:66:77:88:99", "Peer-Two", "22:33:44:55:66:77"):
            self.assertNotIn(raw.lower(), joined)
        self.assertIn(EXPECTED_P2P_HASH, joined)
        self.assertNotIn(FIXED_SALT.hex(), joined)

    def test_hash_fields_cannot_carry_raw_text(self):
        cleaned = census.scrub_json({"device_name_sha256": RAW_P2P_NAME,
                                     "device_address_sha256": RAW_P2P_ADDRESS}, anonymizer())
        self.assertNotIn("device_name_sha256", cleaned)
        self.assertEqual(cleaned["device_address_sha256"], digest(RAW_P2P_ADDRESS))
        self.assertNotIn(RAW_P2P_NAME, json.dumps(cleaned))
        self.assertNotIn(RAW_P2P_ADDRESS, json.dumps(cleaned))

    def test_alternate_address_forms_are_scrubbed(self):
        text = "aa-bb-cc-dd-ee-ff aabbccddeeff fe80::1%wlan0"
        cleaned = census.scrub_json({"note": text}, anonymizer())
        for raw in ("aa-bb-cc-dd-ee-ff", "aabbccddeeff", "fe80::1%wlan0"):
            self.assertNotIn(raw, cleaned["note"])
        self.assertIn(digest("aa:bb:cc:dd:ee:ff"), cleaned["note"])
        self.assertIn(census.Anonymizer(FIXED_SALT).value("fe80::1%wlan0"), cleaned["note"])
        same = census.Anonymizer(FIXED_SALT)
        self.assertEqual(same.value("aa:bb:cc:dd:ee:ff"), same.value("AA-BB-CC-DD-EE-FF"))
        self.assertEqual(same.value("aa:bb:cc:dd:ee:ff"), same.value("aabbccddeeff"))

    def test_each_run_uses_a_fresh_salt(self):
        first, second = census.Anonymizer(), census.Anonymizer()
        self.assertNotEqual(first.salt_hex, second.salt_hex)
        self.assertNotEqual(first.value("same"), second.value("same"))

    def test_refuses_an_existing_output_directory(self):
        folder = Path(tempfile.mkdtemp(prefix="wifi-census-test-"))
        self.addCleanup(shutil.rmtree, folder, True)
        output = folder / "wifi-census-20261104-1200"
        output.mkdir()
        devices = folder / "devices.json"
        devices.write_text(json.dumps({"devices": {"s22": {"serial": "SERIAL"}}}), encoding="utf-8")
        with self.assertRaises(SystemExit):
            census.main(["--devices", str(devices), "--phones", "s22", "--output", str(output)])

    def test_refuses_an_output_outside_the_runs_group(self):
        folder = Path(tempfile.mkdtemp(prefix="wifi-census-test-"))
        self.addCleanup(shutil.rmtree, folder, True)
        devices = folder / "devices.json"
        devices.write_text(json.dumps({"devices": {"s22": {"serial": "SERIAL"}}}), encoding="utf-8")
        with self.assertRaises(SystemExit):
            census.main(["--devices", str(devices), "--phones", "s22",
                         "--output", str(census.ROOT / "app/wifi-census-forbidden")])

    def test_refuses_unknown_and_personal_phones(self):
        folder = Path(tempfile.mkdtemp(prefix="wifi-census-test-"))
        self.addCleanup(shutil.rmtree, folder, True)
        devices = folder / "devices.json"
        devices.write_text(json.dumps({"devices": {"s24": {"serial": "SERIAL"}}}), encoding="utf-8")
        with self.assertRaises(SystemExit):
            census.main(["--devices", str(devices), "--phones", "s24",
                         "--output", str(folder / "run")])
        with self.assertRaises(SystemExit):
            census.main(["--devices", str(devices), "--phones", "unknown",
                         "--output", str(folder / "run2")])

    def test_summary_shows_measured_and_unavailable_phones(self):
        measured = census._base_record("s22")
        measured["status"] = "measured"
        measured["model"] = "SM-S901E"
        measured["features"]["wifi_aware"] = True
        measured["wifi"]["enabled"] = True
        missing = census._base_record("a55")
        summary = census._summary({"s22": measured, "a55": missing}, "run")
        self.assertIn("| s22 | measured | SM-S901E |", summary)
        self.assertIn("| a55 | unavailable |", summary)

    def test_census_json_contains_every_phone(self):
        phone = fixture("android14_aware_5ghz.json")
        fake = FakeAdb(phone)
        folder = Path(tempfile.mkdtemp(prefix="wifi-census-test-"))
        self.addCleanup(shutil.rmtree, folder, True)
        devices = folder / "devices.json"
        devices.write_text(json.dumps({"devices": {"s22": {"serial": "S1"}, "a55": {"serial": "S2"}}}),
                           encoding="utf-8")
        output = folder / "run"
        with mock.patch.object(census.secrets, "token_bytes", return_value=FIXED_SALT), \
                mock.patch.object(census, "Adb", return_value=fake):
            census.main(["--devices", str(devices), "--phones", "s22,a55", "--output", str(output)])
        document = json.loads((output / "census.json").read_text(encoding="utf-8"))
        self.assertEqual(set(document["phones"]), {"s22", "a55"})
        self.assertTrue((output / "a55.json").is_file())

    def test_adb_passes_each_serial_to_its_command(self):
        def runner(argv, timeout):
            return census.CommandResult(0, argv[2] + "\n")

        adb = census.Adb("adb.exe", runner=runner)
        self.assertEqual(adb.shell("SERIAL-A", "getprop", "ro.product.model").stdout, "SERIAL-A\n")
        self.assertEqual(adb.shell("SERIAL-B", "getprop", "ro.product.model").stdout, "SERIAL-B\n")

    def test_bool_and_band_helpers(self):
        self.assertTrue(census.parse_bool("TRUE"))
        self.assertFalse(census.parse_bool("not supported"))
        self.assertIsNone(census.parse_bool("maybe"))
        self.assertEqual(census.band_from_mhz(2437), 2)
        self.assertEqual(census.band_from_mhz(5180), 5)
        self.assertEqual(census.band_from_mhz(5955), 6)
        self.assertIsNone(census.band_from_mhz(0))


if __name__ == "__main__":
    unittest.main()
