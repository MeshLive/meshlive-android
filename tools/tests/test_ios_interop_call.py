import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import ios_interop_call as interop

CALL = "29cf53d1-c249-4075-8f05-930a9c3428d9"
# Optional: a full saved callee log (set IOS_INTEROP_SAVED_LOG) for the end-to-end parse check below.
SAVED = Path(os.environ["IOS_INTEROP_SAVED_LOG"]) if os.environ.get("IOS_INTEROP_SAVED_LOG") else None
# Excerpts of a real M31 callee call (iPhone interop, 2026-10-10, m31-app-2.txt), plus the final tx line.
REAL = f"""\
10-10 05:02:58.572 18850 18850 I BluetoothVideo: BENCHMARK call available: node=9c8d7e6f5a40
10-10 05:03:08.215 18850 23708 I BluetoothVideo: Encrypted private-call link to 1e2e3e4e5e6e over BLE GATT (PHY TX 2M / RX 2M)
10-10 05:03:08.250 18850 18850 I BluetoothVideo: BENCHMARK call phase INCOMING_RINGING
10-10 05:03:10.997 18850 18850 I BluetoothVideo: BENCHMARK call phase NEGOTIATING_MEDIA
10-10 05:03:11.001 18850 18850 I BluetoothVideo: BENCHMARK call identity: callId={CALL}
10-10 05:03:11.001 18850 18850 I BluetoothVideo: BENCHMARK call_type: callId={CALL} | role=callee | requested=video | local=video | peerOfferedVoice=false | voiceCallsEnabled=false
10-10 05:03:11.924 18850 18850 I BluetoothVideo: BENCHMARK call remote: codec=2 | width=240 | height=426 | fps=15 | rotation=0
10-10 05:03:11.926 18850 18850 I BluetoothVideo: BENCHMARK call phase ACTIVE
10-10 05:03:13.261 18850 23709 I BluetoothVideo: BENCHMARK call tx: callId={CALL} | final=false | queue=0/183 | pendingAudio=0 | videoInputs=0 | audioInputs=63 | configInputs=1 | videoWrites=0 | audioWrites=63 | configWrites=1 | queueDrops=0 | inactiveInputs=0 | mutedAudio=0 | audioDrops=0
10-10 05:03:57.778 18850 18850 I BluetoothVideo: BENCHMARK call phase ENDED
10-10 05:03:57.800 18850 23709 I BluetoothVideo: BENCHMARK call tx: callId={CALL} | final=true | queue=0/183 | pendingAudio=0 | videoInputs=667 | audioInputs=2289 | configInputs=1 | videoWrites=667 | audioWrites=2289 | configWrites=1 | queueDrops=0 | inactiveInputs=1 | mutedAudio=0 | audioDrops=0
10-10 05:03:57.800 18850 23709 I BluetoothVideo: Call RX 0 kbps | 0 video/0 audio | audio drops 0 | starting
10-10 05:03:57.800 18850 23709 I BluetoothVideo: BENCHMARK call rx: callId={CALL} | final=true | audioDrops=unavailable | decoderDrops=unavailable | videoPackets=0 | audioPackets=0
10-10 05:03:57.807 18850 23709 I BluetoothVideo: Private call ended: Call ended
10-10 05:03:57.870 18850 18850 I BluetoothVideo: BENCHMARK call ended_ui: message=Call ended
"""
BOTH_WAYS = REAL.replace("videoPackets=0 | audioPackets=0", "videoPackets=310 | audioPackets=1100")
STAMP = "10-10 05:03:%02d.000 18850 18850 I BluetoothVideo: "
AVAILABLE = STAMP % 0 + "BENCHMARK call available: node=9c8d7e6f5a40"


class ParsingTest(unittest.TestCase):
    def test_records_skip_foreign_lines_and_keep_pid_and_message(self):
        rows = interop.parse_records("--------- beginning of main\n" + REAL + "garbage line\n")
        self.assertEqual(len(rows), 15)
        self.assertEqual(rows[2], {"time": "10-10 05:03:08.250", "pid": 18850, "tid": 18850, "level": "I",
                                   "tag": "BluetoothVideo", "message": "BENCHMARK call phase INCOMING_RINGING"})

    def test_fields_keep_slash_values_and_unavailable(self):
        fields = interop.parse_fields("callId=x | final=true | queue=0/183 | audioDrops=unavailable")
        self.assertEqual(fields, {"callId": "x", "final": "true", "queue": "0/183", "audioDrops": "unavailable"})
        self.assertEqual(interop.as_number("2289"), 2289)
        self.assertEqual(interop.as_number("unavailable"), "unavailable")

    def test_real_callee_summary_has_ring_phases_end_reason_and_link_peer(self):
        summary = interop.summarize(interop.parse_records(REAL))
        self.assertEqual([p["phase"] for p in summary["phases"]],
                         ["INCOMING_RINGING", "NEGOTIATING_MEDIA", "ACTIVE", "ENDED"])
        self.assertEqual(summary["call_id"], CALL)
        self.assertEqual(summary["call_type"]["role"], "callee")
        self.assertEqual(summary["ended"], {"reason": "Call ended", "time": "10-10 05:03:57.807",
                                            "reason_ui": "Call ended"})
        self.assertEqual(summary["link_peer"]["prefix"], "1e2e3e4e5e6e")
        named = interop.summarize(interop.parse_records(REAL.replace(
            "Private call ended: Call ended", "Private call ended: Call ended | callId=" + CALL)))
        self.assertEqual(named["ended"]["reason"], "Call ended")
        self.assertEqual(summary["remote"]["width"], 240)
        media = summary["media"]
        self.assertEqual((media["audio_tx_packets"], media["video_tx_packets"]), (2289, 667))
        self.assertEqual((media["audio_rx_packets"], media["video_rx_packets"]), (0, 0))
        self.assertTrue(media["tx_final_seen"] and media["rx_final_seen"])

    def test_the_real_call_fails_because_the_iphone_sent_nothing(self):
        ok, reasons = interop.judge(interop.summarize(interop.parse_records(REAL)), voice=False)
        self.assertFalse(ok)
        self.assertEqual(reasons, ["no audio received", "no video received"])

    def test_two_way_media_passes_and_voice_ignores_video(self):
        summary = interop.summarize(interop.parse_records(BOTH_WAYS))
        self.assertEqual(interop.judge(summary, voice=False), (True, []))
        voice_text = BOTH_WAYS.replace("videoWrites=667", "videoWrites=0").replace("videoPackets=310", "videoPackets=0")
        voice = interop.summarize(interop.parse_records(voice_text))
        self.assertEqual(interop.judge(voice, voice=True), (True, []))
        self.assertFalse(interop.judge(voice, voice=False)[0])

    def test_missing_final_lines_and_a_never_active_call_are_reported(self):
        kept = [line for line in REAL.splitlines() if "final=true" not in line and "ACTIVE" not in line]
        summary = interop.summarize(interop.parse_records("\n".join(kept)))
        self.assertFalse(summary["media"]["tx_final_seen"])
        self.assertEqual(summary["media"]["audio_tx_packets"], 63)  # last interim report
        reasons = interop.judge(summary, voice=True)[1]
        self.assertIn("call never reached ACTIVE", reasons)
        self.assertIn("final tx/rx report line missing", reasons)

    def test_crash_marker_is_a_failure_reason(self):
        text = REAL + "10-10 05:04:00.000 18850 18850 E AndroidRuntime: FATAL EXCEPTION: main\n"
        reasons = interop.judge(interop.summarize(interop.parse_records(text)), True)[1]
        self.assertIn("application crashed", reasons)

    def test_control_ack_takes_the_latest_answer(self):
        rows = interop.parse_records(
            STAMP % 1 + "BENCHMARK control call:aabbccddeeff rejected\n" +
            STAMP % 3 + "BENCHMARK control call:aabbccddeeff applied\n" +
            STAMP % 4 + "BENCHMARK control accept applied\n")
        self.assertTrue(interop.control_ack(rows, "call:aabbccddeeff")[0])
        self.assertIsNone(interop.control_ack(rows, "end"))

    @unittest.skipUnless(SAVED is not None and SAVED.is_file(), "IOS_INTEROP_SAVED_LOG not set")
    def test_saved_real_log_first_call(self):
        lines = SAVED.read_text(encoding="utf-8", errors="replace").splitlines()[:190]
        summary = interop.summarize(interop.parse_records("\n".join(lines)))
        self.assertEqual(summary["call_id"], CALL)
        self.assertEqual(summary["media"]["audio_tx_packets"], 2289)
        self.assertEqual(summary["ended"]["reason"], "Call ended")


class HandleTest(unittest.TestCase):
    def test_protocol_vectors(self):
        self.assertEqual(interop.handle_to_prefix("04106-1050R"), "010203040506")
        self.assertEqual(interop.handle_to_prefix("m6sc7 n75yr"), "a1b2c3d4e5f6")
        self.assertEqual(interop.handle_to_prefix("00000-00000"), "000000000000")
        self.assertEqual(interop.handle_to_prefix("ZZZZZ-ZZZZW"), "ffffffffffff")
        self.assertEqual(interop.handle_to_prefix("O41O6-1O5OR"), "010203040506")  # O reads as 0

    def test_bad_handles_and_prefix_passthrough(self):
        for bad in ("04106-1050S", "0410-1050R", "xx"):  # tail bits, length, length
            with self.assertRaises(ValueError):
                interop.handle_to_prefix(bad)
        self.assertEqual(interop.peer_prefix("A1B2C3D4E5F6"), "a1b2c3d4e5f6")
        self.assertEqual(interop.peer_prefix("04106-1050R"), "010203040506")


class CommandTest(unittest.TestCase):
    def args(self, *extra):
        return interop.build_parser().parse_args(["callee", "--serial", "S", "--seconds", "20", *extra])

    def test_launch_command_is_the_runner_call_screen_launch_with_no_raw_input(self):
        command = interop.launch_command(interop.launch_extras(self.args()))
        self.assertEqual(command[:6], ["shell", "am", "start", "-W", "-n", "io.github.meshlive.app/com.example.bluetoothtest.MainActivity"])
        flat = " ".join(command)
        for expected in ("--es test_transport gatt", "--es test_codec vp9", "--es test_playback_delay balanced",
                         "--ez test_thermal_reduction false", "--ez test_private_call_browser true",
                         "--es test_role send", "--ei test_width 640", "--ei test_height 360", "--ei test_fps 15",
                         "--ei test_bitrate_kbps 300", "--es test_rate_control automatic",
                         "--ef test_playback_gain 0.05"):
            self.assertIn(expected, flat)
        for absent in ("test_raw_yuv", "test_replay_ivf", "test_reference_audio", "test_media_impairment",
                       "test_auto_broadcast", "test_voice_calls"):
            self.assertNotIn(absent, flat)

    def test_voice_adds_the_voice_call_tunable_and_voice_invite(self):
        flat = " ".join(interop.launch_command(interop.launch_extras(self.args("--voice"))))
        self.assertIn("--ez test_voice_calls true", flat)
        self.assertEqual(interop.invite_control("aabbccddeeff", True), "voice_call:aabbccddeeff")
        self.assertEqual(interop.invite_control("aabbccddeeff", False), "call:aabbccddeeff")
        self.assertEqual(interop.control_command("accept"), ["shell", "am", "start", "-n",
                         "io.github.meshlive.app/com.example.bluetoothtest.MainActivity", "--es", "test_control", "accept"])


class FakePhone:
    """A scripted phone: adb answers, and the app's log grows as the controls arrive."""

    def __init__(self, rejections=0):
        self.path, self.sent, self.rejections = None, [], rejections
        self.tail = BOTH_WAYS.splitlines()[-5:]  # tx final, RX line, rx final, ended, ended_ui

    def append(self, *lines):
        with self.path.open("a", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")

    def adb(self, *command):
        command = [str(item) for item in command]
        if command[:2] == ["shell", "pidof"]:
            return "18850"
        if command[0] == "shell" and command[1].startswith("date"):
            return "10-10 05:02:50.000"
        if "test_control" in command:
            control = command[command.index("test_control") + 1]
            self.sent.append(control)
            if control.endswith("aabbccddeeff") and self.rejections:
                self.rejections -= 1
                self.append(STAMP % 1 + f"BENCHMARK control {control} rejected")
            elif control == "accept" or control.endswith("aabbccddeeff"):
                invite = [STAMP % 2 + "BENCHMARK call phase OUTGOING_INVITE"] if control != "accept" else []
                self.append(STAMP % 2 + f"BENCHMARK control {control} applied", *invite,
                            STAMP % 3 + "BENCHMARK call phase ACTIVE")
            elif control == "end":
                self.append(STAMP % 9 + "BENCHMARK control end applied", STAMP % 9 + "BENCHMARK call phase ENDED",
                            *self.tail)
        return ""


def fake_capture(phone, first_lines):
    class FakeCapture:
        closed = False

        def __init__(self, adb, serial, pid, since, path, filters=()):
            phone.path = path
            path.write_text("\n".join(first_lines) + "\n", encoding="utf-8")

        def stop(self):
            self.closed = True
    return FakeCapture


class FlowTest(unittest.TestCase):
    def drive(self, argv, phone, first_lines):
        tmp = tempfile.mkdtemp()
        args = interop.build_parser().parse_args(argv + ["--out-root", tmp])
        with patch.object(interop, "LogCapture", fake_capture(phone, first_lines)), \
                patch.object(interop.Driver, "adb", lambda self, *a, **k: phone.adb(*a)), \
                patch.object(interop.time, "sleep", lambda seconds: None):
            code = interop.run(args)
        return code, json.loads(next(Path(tmp).glob("*/summary.json")).read_text(encoding="utf-8"))

    def test_callee_accepts_the_ring_holds_and_hangs_up(self):
        phone = FakePhone()
        ring = STAMP % 0 + "BENCHMARK call phase INCOMING_RINGING"
        code, summary = self.drive(["callee", "--serial", "S", "--seconds", "0.01"], phone, [AVAILABLE, ring])
        self.assertEqual(phone.sent, ["accept", "end"])
        self.assertEqual((code, summary["ok"], summary["outcome"]), (0, True, "held"))
        self.assertEqual(summary["own_node_prefix"], "9c8d7e6f5a40")
        self.assertEqual(summary["call"]["media"]["audio_rx_packets"], 1100)

    def test_caller_retries_a_rejected_invite_until_the_peer_is_found(self):
        phone = FakePhone(rejections=1)
        argv = ["caller", "--serial", "S", "--seconds", "0.01", "--voice", "--peer", "AABBCCDDEEFF"]
        code, summary = self.drive(argv, phone, [AVAILABLE])
        self.assertEqual(phone.sent, ["voice_call:aabbccddeeff"] * 2 + ["end"])
        self.assertEqual((code, summary["peer_prefix"], summary["outcome"]), (0, "aabbccddeeff", "held"))

    def test_no_ring_is_a_setup_failure_exit_2(self):
        phone = FakePhone()
        code, summary = self.drive(["callee", "--serial", "S", "--seconds", "1", "--ring-timeout", "0.2"],
                                   phone, [AVAILABLE])
        self.assertEqual(code, 2)
        self.assertEqual(summary["outcome"], "failed")
        self.assertIn("INCOMING_RINGING", summary["error"])
        self.assertFalse(summary["ok"])


if __name__ == "__main__":
    unittest.main()
