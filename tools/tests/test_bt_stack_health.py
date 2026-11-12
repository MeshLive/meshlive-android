import itertools
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import bt_stack_health as health
import run_benchmark_suite as suite
import run_private_call_benchmark as runner

RUNS = Path(__file__).parents[2] / "benchmarks" / "runs"

# Excerpts of iab-c2-r02 (S22 Bluetooth process 1713, app 7010): the main thread (1775) last reads at 03:29:53.787,
# the scan thread (1780) and a binder thread (1903) keep logging, and the app ends the call 8.6 s later.
WEDGE_STACK = """\
11-05 03:29:53.786  1713  1775 V bluetooth: system/bta/jv/bta_jv_api.cc:325 BTA_JvL2capRead: handle:5, req_id:15, len:215
11-05 03:29:53.787  1713  1775 V bluetooth: system/bta/jv/bta_jv_api.cc:361 BTA_JvL2capReady: handle:5
11-05 03:29:53.787  1713  1775 V bluetooth: system/bta/jv/bta_jv_api.cc:325 BTA_JvL2capRead: handle:5, req_id:15, len:111
11-05 03:29:54.249  1713  1780 V bt_btm_ble: system/stack/btm/btm_ble_gap.cc:2015 btm_ble_process_adv_addr: bda=xx:xx:xx:xx:e2:76
11-05 03:29:59.854  1713  1780 V bluetooth: system/gd/hci/le_scanning_reassembler.cc:62 ProcessAdvertisingReport: Dropping repeated legacy advertising data
11-05 03:30:02.477  1713  1903 D BtGatt.GattService: unregisterServer(io.github.meshlive.app) - serverIf=55
11-05 03:30:02.505  1713  1903 I BluetoothSocketManagerBinder: createSocketChannel: type=4, serviceName=null, uuid=00000000-0000-0000-0000-000000000000, port=-2, from uid/pid=10336/7010
11-05 03:30:02.505  1713  1903 I bt_btif_sock: system/btif/src/btif_sock.cc:182 btsock_listen: Attempting listen for socket connections for device: 00:00:00:00:00:00, type: 4, channel: -2, app_uid: 10336, data_path: 0, hub_id: 0, endpoint_id: 0, max_rx_packet_size: 0
11-05 03:30:03.049  1713  1762 V BtGatt.GattService: ***** CURRENT BLE ADVERTISING APPS *****
11-05 03:30:06.100  1713  1780 V bluetooth: system/gd/hci/le_scanning_reassembler.cc:62 ProcessAdvertisingReport: Dropping repeated legacy advertising data
"""
WEDGE_APP = """\
11-05 03:29:23.957  7010  7010 I BluetoothVideo: BENCHMARK call available: node=b22000000002
11-05 03:30:02.430  7010  7207 I BluetoothVideo: Private call ended: Call link lost: Call peer stopped responding
11-05 03:30:02.478  7010  7010 I BluetoothVideo: BENCHMARK call ended_ui: message=Call link lost: Call peer stopped responding
"""
# A healthy listen on the same stack (iab-c2-r02 start): allocation on the same binder thread within 1 ms.
HEALTHY_STACK = """\
11-05 03:29:23.855  1713  1735 I BluetoothSocketManagerBinder: createSocketChannel: type=4, serviceName=null, uuid=00000000-0000-0000-0000-000000000000, port=-2, from uid/pid=10336/7010
11-05 03:29:23.855  1713  1735 I bt_btif_sock: system/btif/src/btif_sock.cc:182 btsock_listen: Attempting listen for socket connections for device: 00:00:00:00:00:00, type: 4, channel: -2, app_uid: 10336, data_path: 0, hub_id: 0, endpoint_id: 0, max_rx_packet_size: 0
11-05 03:29:23.855  1713  1735 I bluetooth: system/btif/src/btif_sock_l2cap.cc:405 btsock_l2cap_alloc_l: Allocated l2cap socket structure socket_id:15
11-05 03:29:30.000  1713  1780 V bluetooth: system/gd/hci/le_scanning_reassembler.cc:62 ProcessAdvertisingReport: Dropping repeated legacy advertising data
"""
# Android 10 (A15s) logs its listens differently and never "Allocated": it must never look stalled.
ANDROID10_STACK = """\
11-04 12:28:50.308 13196 14044 D bt_btif_sock: btsock_listen: type=BTSOCK_L2CAP_LE, channel=0x0, original=0xfffffffe, flags=0x24
11-04 12:28:59.000 13196 13338 D bt_btif_sock: btsock_connect: type=BTSOCK_L2CAP_LE, channel=0xc3, flags=0x20
"""
# rw-P1bR7: the 13th CoC app process after a Bluetooth enable.
EXHAUSTED_APP = """\
11-04 13:42:46.839 31771 31771 W BluetoothVideo: Private call availability failed over BLE L2CAP CoC - Experimental
11-04 13:42:46.839 31771 31771 W BluetoothVideo: java.io.IOException: Error: -1
11-04 13:42:46.839 31771 31771 W BluetoothVideo: \tat android.bluetooth.BluetoothAdapter.listenUsingInsecureL2capChannel(BluetoothAdapter.java:3204)
"""
STALLED_APP = ("11-05 03:30:07.510  7010  7300 W BluetoothVideo: BENCHMARK call_availability: state=failed | stage=coc_listen | "
               "reason=stalled | role=private-call | waitedMs=5004 | deadlineMs=5000\n")


class WedgeDetectorTest(unittest.TestCase):
    def test_a_silent_main_thread_before_a_peer_timeout_is_this_phones_stack(self):
        wedge = health.detect_wedge(WEDGE_STACK, WEDGE_APP)
        self.assertEqual(health.LOCAL_STACK_WEDGED, wedge["reason"])
        self.assertEqual((1713, 1775), (wedge["stack_pid"], wedge["main_tid"]))
        self.assertGreaterEqual(wedge["main_silent_s"], 8.6)

    def test_a_peer_that_ended_the_call_is_not_a_wedge(self):
        app = WEDGE_APP.replace("Call link lost: Call peer stopped responding", "Peer ended the call")
        self.assertIsNone(health.detect_wedge(WEDGE_STACK, app))

    def test_a_main_thread_that_was_still_reading_is_not_a_wedge(self):
        app = WEDGE_APP.replace("03:30:02.430", "03:29:56.000")
        self.assertIsNone(health.detect_wedge(WEDGE_STACK, app))

    def test_a_stack_log_with_no_coc_reads_says_nothing(self):
        self.assertIsNone(health.detect_wedge(ANDROID10_STACK, WEDGE_APP))


class ListenStallTest(unittest.TestCase):
    def test_a_listen_the_stack_began_but_never_allocated_is_pending(self):
        pending = health.pending_listen(WEDGE_STACK, app_pid=7010)
        self.assertEqual("11-05 03:30:02.505", pending["time"])
        self.assertTrue(pending["log_past_grace"])
        self.assertIsNone(health.pending_listen(WEDGE_STACK, app_pid=9999), "another process's listen is not ours")

    def test_healthy_and_android10_listens_are_never_pending(self):
        self.assertIsNone(health.pending_listen(HEALTHY_STACK, app_pid=7010))
        self.assertIsNone(health.pending_listen(ANDROID10_STACK))

    def test_a_listen_before_this_wait_is_ignored(self):
        self.assertIsNone(health.pending_listen(WEDGE_STACK, 7010, since=health.at("11-05 03:30:03.000")))

    def test_the_app_reports_stalled_and_exhausted_listens(self):
        self.assertEqual(health.LISTENER_EXHAUSTED, health.app_availability_block(EXHAUSTED_APP)[0])
        self.assertEqual(health.LISTEN_STALLED, health.app_availability_block(STALLED_APP)[0])
        gatt = EXHAUSTED_APP.replace("java.io.IOException: Error: -1", "java.lang.IllegalStateException: GATT server failed")
        self.assertIsNone(health.app_availability_block(gatt))
        self.assertIsNone(health.app_availability_block(EXHAUSTED_APP, pid=1))


class PreflightTest(unittest.TestCase):
    history = {"manifests": [], "wedges": [], "coc_app_processes": []}

    def test_verdicts(self):
        ok = dict(self.history)
        self.assertIsNone(health.stack_preflight("s22", 36, 1713, "ON", ok))
        self.assertIn("restart Bluetooth (owner)", health.stack_preflight("s22", 36, 1713, "ON",
                      {**ok, "wedges": [{"manifest": "iab-c2-r02/manifest.json"}]}))
        self.assertIn("TURNING_OFF", health.stack_preflight("s22", 36, 1713, "TURNING_OFF", ok))
        self.assertIn("not found", health.stack_preflight("s22", 36, None, "ON", ok))
        self.assertIsNone(health.stack_preflight("a15s", 29, 13196, "ON", {**ok, "coc_app_processes": list(range(10))}))
        self.assertIn(health.A10_BUDGET, health.stack_preflight("a15s", 29, 13196, "ON",
                      {**ok, "coc_app_processes": list(range(11))}))
        self.assertIsNone(health.stack_preflight("s24", 35, 5, "ON", {**ok, "coc_app_processes": list(range(40))}))
        self.assertIsNone(health.stack_preflight("s22", 36, 1713, None, ok), "an unstated state is recorded, not guessed")

    def test_parsers(self):
        self.assertEqual("TURNING_OFF", health.bluetooth_manager_state("Bluetooth Status\n  enabled: false\n  state: TURNING_OFF\n"))
        self.assertIsNone(health.bluetooth_manager_state("nothing"))
        stat = "1713 (droid.bluetooth) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 50000 20 21"
        self.assertAlmostEqual(500.0, health.process_age_s(stat, "1000.00 2000.00"))
        self.assertIsNone(health.process_age_s("garbage", "1000"))

    def test_history_counts_this_stack_process_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index, (stack_pid, app_pid) in enumerate([(13196, 100), (13196, 101), (999, 102)]):
                folder = root / f"suite-{index}" / "0001-x" / "physical"
                folder.mkdir(parents=True)
                (folder / "a15s.log").write_text(
                    f"11-04 13:33:47.770 {app_pid} 1 I BluetoothVideo: LE CoC listener lease: role=private-call | "
                    f"owner=private-call | psm={128 + index} | listens=1 | leases=1\n", encoding="utf-8")
                manifest = {"bluetooth_stack_logs": [{"device": "a15s", "pid": stack_pid}],
                            "logs": [{"device": "a15s", "path": "a15s.log"}]}
                if index == 1:
                    manifest["bluetooth_stack_wedges"] = [{"device": "a15s", "stack_pid": 13196, "reason": health.LOCAL_STACK_WEDGED}]
                (folder / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            found = health.recent_manifests(root, 0)
            self.assertEqual(3, len(found))
            history = health.stack_history(found, "a15s", 13196, lambda path: json.loads(path.read_text(encoding="utf-8")))
            self.assertEqual([100, 101], history["coc_app_processes"])
            self.assertEqual(1, len(history["wedges"]))
            self.assertEqual([], health.recent_manifests(root, 4102444800))

    def test_suite_preflight_blocks_a_wedged_stack_without_touching_the_phone(self):
        calls = []

        def run(command, **kwargs):
            calls.append(command)
            tail = command[4:]
            out = {"pidof": "1713\n", "dumpsys": "  state: ON\n", "cat": ""}[tail[0]]
            return Mock(stdout=out, returncode=0)
        history = {"manifests": ["m"], "wedges": [{"manifest": "iab-c2-r02"}], "coc_app_processes": []}
        with patch.object(suite.subprocess, "run", side_effect=run), \
             patch.object(suite.stack_health, "stack_history", return_value=history):
            evidence, blocked = suite.coc_stack_preflight("adb", "serial", "s22", 36, runs_root=Path("missing"))
        self.assertIn(health.LOCAL_STACK_WEDGED, blocked)
        self.assertEqual(1713, evidence["pid"])
        read_only = {"pidof", "dumpsys", "cat"}
        self.assertTrue(all(command[4] in read_only for command in calls))


class RunnerFailFastTest(unittest.TestCase):
    def setUp(self):
        self.devices = {"a": {"serial": "a", "node_prefix": "000000000001"},
                        "b": {"serial": "b", "node_prefix": "000000000002"}}
        profile = {"codec": "encoder-a", "width": 640, "height": 360, "fps": 15, "bitrate_kbps": 150,
                   "playback": "balanced", "rate_control": "cbr", "thermal_reduction": False}
        self.case = {"id": "test-call", "caller": "a", "callee": "b", "transport": "ble", "duration_s": 60,
                     "profiles": {"a": profile, "b": profile}}

    def runner(self, directory, app_text, stack_text):
        instance = runner.CallRunner("adb", self.devices, self.case, Path(directory))
        instance.output.mkdir(parents=True, exist_ok=True)
        (instance.output / "b-bluetooth-1713.log").write_text(stack_text, encoding="utf-8")
        instance.logs = [{"device": "b", "pid": 7010, "path": "b-7010.log"}]
        instance.stack_logs = [{"device": "b", "pid": 1713, "path": "b-bluetooth-1713.log"}]
        instance.pids["b"] = "7010"
        instance.log_text = Mock(return_value=app_text)
        return instance

    def wait(self, instance):
        # The availability deadline is 30 s; a blocked stack must stop the wait on its first poll.
        with patch.object(runner.time, "monotonic", side_effect=itertools.count(0, 0.5)), patch.object(runner.time, "sleep"):
            instance.wait_for("b", r"BENCHMARK call available: node=([0-9a-f]{12})")

    def test_a_stalled_stack_listen_stops_the_availability_wait(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(runner.StackBlocked) as raised:
                self.wait(self.runner(directory, "", WEDGE_STACK))
            self.assertEqual(health.LISTEN_STALLED, raised.exception.record["reason"])

    def test_an_exhausted_android10_stack_stops_the_availability_wait(self):
        app = EXHAUSTED_APP.replace("31771 31771", "7010 7010")
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(runner.StackBlocked) as raised:
                self.wait(self.runner(directory, app, ANDROID10_STACK))
            self.assertEqual(health.LISTENER_EXHAUSTED, raised.exception.record["reason"])

    def test_a_healthy_listen_does_not_block(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.runner(directory, "", HEALTHY_STACK)
            instance.check_availability_blocked("b", None)

    def score(self, **extra):
        with tempfile.TemporaryDirectory() as directory:
            manifest = {"scenario": self.case, "logs": [], "runner_error": "b: BLOCKED", **extra}
            return runner.score_call_manifest(manifest, Path(directory))

    def test_a_blocked_stop_is_not_an_app_failure_and_a_wedge_names_the_failure(self):
        blocked = {"device": "b", "reason": health.LISTEN_STALLED, "detail": "no allocation"}
        score = self.score(runner_blocked=blocked)
        self.assertEqual("UNMEASURED", score["overall_status"])
        self.assertEqual(blocked, score["blocked"])
        wedge = {"device": "b", "reason": health.LOCAL_STACK_WEDGED, "stack_pid": 1713}
        score = self.score(runner_blocked=blocked, bluetooth_stack_wedges=[wedge])
        self.assertEqual("FAIL", score["overall_status"])
        self.assertEqual(health.LOCAL_STACK_WEDGED, score["failure_reason"])
        self.assertNotIn("blocked", score)


@unittest.skipUnless((RUNS / "iab-c2-r02-iab-coc-m31-calls-s22").is_dir(), "saved S22/A15s evidence not present")
class SavedEvidenceTest(unittest.TestCase):
    @staticmethod
    def text(path):
        return path.read_text(encoding="utf-8", errors="replace")

    def physical(self, name):
        return next((RUNS / name).glob("*/physical"))

    def test_iab_wedge_runs_match_and_the_healthy_run_does_not(self):
        c2r02 = self.physical("iab-c2-r02-iab-coc-m31-calls-s22")
        wedge = health.detect_wedge(self.text(c2r02 / "s22-bluetooth-1713.log"), self.text(c2r02 / "s22-7010.log"))
        self.assertEqual(1775, wedge["main_tid"])
        c2r03 = self.physical("iab-c2-r03-iab-coc-m31-calls-s22")
        self.assertIsNotNone(health.pending_listen(self.text(c2r03 / "s22-bluetooth-1713.log"), app_pid=7825))
        c2r01 = self.physical("iab-c2-r01-iab-coc-m31-calls-s22")
        stack = self.text(c2r01 / "s22-bluetooth-1713.log")
        app = "".join(self.text(path) for path in c2r01.glob("s22-*.log") if "bluetooth" not in path.name and "liblog" not in path.name)
        self.assertIsNone(health.detect_wedge(stack, app))
        self.assertIsNone(health.pending_listen(stack))

    def test_rw_p1b_r7_is_exhausted(self):
        folder = self.physical("rw-P1bR7-r01-rw-coc-a15s-calls-asus-off")
        self.assertEqual(health.LISTENER_EXHAUSTED, health.app_availability_block(self.text(folder / "a15s-31771.log"))[0])


if __name__ == "__main__":
    unittest.main()
