"""Scripted phones for the call runner's flow tests: stand in for adb and for the app's log lines.

`FakePhones.run` replaces `subprocess.run` for every adb command the runner issues, and `capture` replaces the
live logcat reader. The fake app answers the runner's launches and controls with the lines the real app logs,
on one fake clock that `sleep` advances, so a whole case runs in milliseconds. Tests change the script (what a
phone logs, and when) and read the manifest, the score and the adb commands the runner sent.
"""
import base64
import contextlib
import hashlib
import datetime as dt
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import run_device_benchmark as device_runner
import run_private_call_benchmark as runner
import user_launch

CALL_ID = "00000000-0000-0000-0000-000000000001"
APK = "a" * 64
EPOCH = dt.datetime(2000, 10, 3, 0, 0, 0)


class FakeCapture:
    """A live logcat reader that never needs supervision; the fake app appends to its file."""

    def __init__(self, phones, adb_path, serial, pid, since, path, filters=()):
        self.path, self.filters, self.restarts, self.stopped = path, tuple(filters), [], False
        self.command = [adb_path, "-s", serial, "logcat", f"--pid={pid}", "-T", since, "-v", "threadtime", *filters]
        path.write_text("", encoding="utf-8")
        phones.captures.append(self)
        if self.filters == tuple(device_runner.APP_LOG_FILTERS):
            phones.app_logs[serial] = path
        elif self.filters == tuple(device_runner.LOG_DROP_FILTERS):
            path.write_text(phones.report_text(serial, pid), encoding="utf-8")

    def poll(self):
        return 0 if self.stopped else None

    def supervise(self, alive):
        return None

    def silent_s(self):
        return 0.0

    def terminate(self):
        self.stopped = True

    kill = close = stop = terminate

    def wait(self, timeout=None):
        return 0


class FakePhones:
    def __init__(self, devices, case):
        self.devices, self.case = devices, case
        self.now = 0.0
        self.pids, self.next_pid = {}, 1000
        self.app_logs, self.captures, self.commands = {}, [], []
        self.drop_reports = {}        # serial -> liblog capture text of the app process
        self.silent = {}              # serial -> substrings of app lines this phone never stores (logd drops)
        self.silent_after_end = {}    # serial -> substrings of app lines it stops storing once the call was ended
        self.ended = False
        self.echo_launch = True       # the app's launch_extras line
        self.first_attempt_fails = False
        self.active = False
        self.timers = []              # (due time, function)
        self.launch_extras = {}
        self.stage_start = {}
        self.system_buffers = {}      # (serial, "crash" | "system") -> what that logcat buffer holds
        self.unreachable_buffers = set()
        self.drop_launch_extra = None  # an extra that never reaches the app (an unknown or ignored one)
        self.echo_drops = set()       # serials whose launch_extras echo is among the lines logd dropped
        self.effect_lines = {}        # serial -> app lines logged at launch, after the echo (a policy line, for example)
        self.refuse_input = set()     # serials whose shell refuses `input keyevent` (OEM INJECT_EVENTS policy)
        self.files = {}               # serial -> {app-relative path: text}: the app's private files, reached by run-as
        self.rotating = set()         # serials whose app offers its call under a rotating discovery ID (user launch)
        self.epoch = 1                # the rotating-ID epoch every phone is in; `rotate` moves to the next one
        self.no_rotid_own = set()     # serials whose app announces its rotating ID but logs no `rotid_own` line
        self.ignore_stored = set()    # serials whose app reads no stored choice (a build that ignores the file)
        self.unwritable = set()       # serials where run-as is refused (a package that is not debuggable)
        self.readonly_after_launch = set()  # serials whose file cannot be written once the app has started (restore fails)
        self.launched_serials = set()
        self.prefs_at_launch = {}
        self.shell_adds_cr = False    # `adb shell` output gets a carriage return per line (Windows host); exec-out never does
        self.reveal_missing = set()   # serials of a person's launch whose "identity is confirmed" status line is not logged
        # The app's call_join_stage lines (build B5 and later). Off by default: an older build logs none.
        self.join_stages = False
        self.slow_health_s = 0.0      # seconds the host loses on each adb get-state while the call is active (a stalled adb server)
        self.ring_s = 0.4             # dial -> the caller's ringing (the caller's clock)
        self.picture_s = {}           # receiving serial -> Accept -> shown picture, default 1.5 s
        self.black_first = set()      # receiving serials whose first shown picture is black
        self.no_picture = set()       # receiving serials that never show a picture
        self.clock_offset_ms = {}     # serial -> its elapsedRealtime minus the fake clock: clocks that must never be mixed
        self.broadcast_ignored = set()  # serials whose app has no DEBUG dial receiver (an `am broadcast` is dropped silently)
        self.asleep, self.at_home = set(), set()  # serials whose screen was put to sleep / whose app lost the window focus
        self.stuck_awake, self.stuck_foreground = set(), set()  # serials that ignore the sleep / home key
        self.wifi = {}                # serial -> the phone's `wifi_on` setting ("1" when never toggled)
        self.wifi_ignored = set()     # serials whose `svc wifi` changes nothing (the setting stays as it was)
        self.on_wifi_off = None       # called with the serial when its Wi-Fi goes off: the lines the app logs for it

    # --- the fake clock -------------------------------------------------------------------------
    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += max(0.0, seconds)
        due = sorted((item for item in self.timers if item[0] <= self.now), key=lambda item: item[0])
        self.timers = [item for item in self.timers if item[0] > self.now]
        for _, action in due:
            action()

    def stamp(self):
        self.now += 0.001
        return (EPOCH + dt.timedelta(seconds=self.now)).strftime("%m-%d %H:%M:%S.%f")[:-3]

    def after(self, seconds, action):
        self.timers.append((self.now + seconds, action))

    # --- the fake app ---------------------------------------------------------------------------
    def log(self, phone, message, tag="BluetoothVideo"):
        if phone not in self.pids or any(part in message for part in self.silent.get(phone, ())):
            return
        if self.ended and any(part in message for part in self.silent_after_end.get(phone, ())):
            return
        with self.app_logs[phone].open("a", encoding="utf-8") as handle:
            handle.write(f"{self.stamp()} {self.pids[phone]} {self.pids[phone]} I {tag}: {message}\n")

    def report_text(self, serial, pid):
        """The scripted liblog drop report of a phone; `{pid}` stands for the app process it is about."""
        return self.drop_reports.get(serial, "").replace("{pid}", str(pid))

    def prefix(self, phone):
        return self.devices[phone]["node_prefix"]

    def rdi(self, phone):
        """The rotating discovery ID the fake app announces: another 12 hex digits than its fixed prefix."""
        return ("ee" if self.epoch == 1 else f"e{self.epoch % 10}") + self.prefix(phone)[2:]

    def rotate(self):
        """An epoch boundary: every rotating phone announces its next ID and says so with a `rotid_own` line."""
        self.epoch += 1
        for phone in sorted(self.rotating):
            if phone in self.pids and phone not in self.no_rotid_own:
                self.log(phone, f"BENCHMARK rotid_own: role=call | epoch={self.epoch} | rdi={self.rdi(phone)}")

    def pseudonymous(self, phone):
        """A person's launch (rotating IDs on) runs BMH3 pseudonyms: the link line names a stranger until the reveal."""
        return phone in self.rotating

    def stage(self, phone, stage, role, at_s=None, **detail):
        """One `call_join_stage` line on this phone's own elapsedRealtime clock (a fixed offset from the fake clock);
        `at_s` is the moment it happened when a timer logs it late."""
        if not self.join_stages:
            return
        at = int(round((self.now if at_s is None else at_s) * 1000)) + self.clock_offset_ms.get(phone, 0)
        started = self.stage_start.setdefault(phone, at)
        extra = "".join(f" | {key}={value}" for key, value in detail.items())
        self.log(phone, f"BENCHMARK call_join_stage: stage={stage} | role={role} | sinceStartMs={at - started} | "
                        f"atMs={at} | callId={CALL_ID}{extra}")

    def advertised(self, phone):
        return self.rdi(phone) if phone in self.rotating else self.prefix(phone)

    def user_launch_lines(self, phone, extras):
        """What a person's launch logs: stored choices decide, the opt-in extras are ignored, rotating IDs are on."""
        stored = {} if phone in self.ignore_stored else user_launch.read_bools(
            self.files.get(phone, {}).get(user_launch.PACKAGE_PREFS_FILE, ""))
        relayed = stored.get("experimental_relayed_calls", False)
        self.log(phone, f"BENCHMARK relayed_calls: enabled={str(relayed).lower()} | "
                        f"help={str(stored.get('relayed_calls_help', True)).lower()} | "
                        f"consent={str(stored.get('relayed_calls_consent', True)).lower()} | maxRelays=2 | require=0 | "
                        "no_direct=false | "
                        f"helperPin={extras.get('test_relayed_calls_helper', 'none')} | "
                        f"nextHopPin={extras.get('test_relayed_calls_next_hop', 'none')}")
        # The Wi-Fi extras decide in any debug launch (FeatureSpec.extraInAnyDebugLaunch); the stored choice otherwise.
        carrier = extras.get("test_wifi_carrier") == "true" or stored.get("wifi_carrier", False)
        wider = carrier and (extras.get("test_wifi_wider_reach") == "true" or stored.get("wifi_wider_reach", False))
        direct = carrier and (extras.get("test_wifi_direct") == "true" or stored.get("wifi_direct", False))
        word = lambda value: "on" if value else "off"
        self.log(phone, f"BENCHMARK wifi_switches: carrier={word(carrier)} | wider={word(wider)} | direct={word(direct)}")

    def app_profile_lines(self, phone):
        """The profile a launch without resolution/rate/encoder extras resolves, as the app logs it.

        Only an endpoint launched with no `test_width` (a case's app_default profile) logs these here: its size, rate
        and FPS come from the stored video settings (MainActivity.applyDebugLaunchExtras falls back to them), its
        encoder from the Automatic choice. An endpoint with a pinned profile keeps the fake's earlier silence."""
        extras = self.launch_extras.get(phone, {})
        if "test_width" in extras or extras.get("test_user_launch") != "true":
            return
        stored = user_launch.read_values(self.prefs_at_launch.get(phone) or "")
        width, height = stored.get("video_width", 426), stored.get("video_height", 240)
        fps, kbps = stored.get("video_fps", 15), stored.get("video_bitrate", 200_000) // 1000
        encoder = "c2.auto.encoder"
        self.log(phone, f"BENCHMARK encoder_choice: role=call | encoder={encoder} | codec=VP9 | hardware=true | "
                        "prefer_hw_av1=false | peer_decode_mask=7 | reason=automatic")
        self.log(phone, f"BENCHMARK call selected: encoder={encoder} | width={width} | height={height} | fps={fps} | "
                        f"bitrateKbps={kbps} | playbackMs=250 | rateControl=AUTOMATIC | "
                        "thermalReduction=true (setting=true)")
        self.log(phone, f"BENCHMARK call effective: encoder={encoder} | codec=2 | width={width} | height={height} | "
                        f"fps={fps} | bitrateKbps={kbps} | audioKbps=32 | playbackMs=250 | rateControl=AUTOMATIC")

    def run_as(self, serial, command, raw=False):
        """(returncode, stdout, stderr) of `run-as <package> ...`: cat, and `sh -c` with mkdir, `: >`, base64 writes, mv, rm."""
        if serial in self.unwritable:
            return 1, "", f"run-as: package not debuggable: {device_runner.PACKAGE}"
        files = self.files.setdefault(serial, {})
        body = command.split(device_runner.PACKAGE + " ", 1)[1]
        if body.startswith("cat "):
            path = body[4:].strip()
            if path not in files:
                return 1, "", f"cat: {path}: No such file or directory"
            return 0, files[path].replace(chr(10), chr(13) + chr(10)) if self.shell_adds_cr and not raw else files[path], ""
        if body.startswith("sha256sum "):
            path = body.split()[1]
            if path not in files:
                return 1, "", f"sha256sum: {path}: No such file or directory"
            return 0, f"{hashlib.sha256(files[path].encode('utf-8')).hexdigest()}  {path}", ""
        script = body[len("sh -c '"):-1]
        if script.startswith("test -e "):
            return 0, "present" if script.split()[2] in files else "absent", ""
        if serial in self.readonly_after_launch and serial in self.launched_serials:
            return 1, "", "sh: Read-only file system"
        for statement in script.split("; "):
            words = statement.split()
            if words[:2] == ["mkdir", "-p"]:
                continue
            if words[:2] == [":", ">"]:
                files[words[2]] = ""
            elif words[:1] == ["echo"] and "base64" in words:
                files[words[-1]] += base64.b64decode(words[1]).decode("utf-8")
            elif words[:2] == ["mv", "-f"]:
                files[words[3]] = files.pop(words[2])
            elif words[:2] == ["rm", "-f"]:
                for path in words[2:]:
                    files.pop(path, None)
            else:
                return 1, "", f"unsupported fake statement: {statement}"
        return 0, "", ""

    def launched(self, phone, extras):
        self.launch_extras[phone] = extras
        self.launched_serials.add(phone)
        # The preferences file the app started with (a user-launch case restores the file when it ends).
        self.prefs_at_launch[phone] = self.files.get(phone, {}).get(user_launch.PACKAGE_PREFS_FILE)
        user = extras.get("test_user_launch") == "true"
        if user or extras.get("test_rotating_ids") == "true":
            self.rotating.add(phone)
        else:
            self.rotating.discard(phone)
        if self.echo_launch and phone not in self.echo_drops:
            # The app's own format (LaunchExtrasEcho.kt): every test_ extra that reached it, sorted, comma-separated.
            names = sorted(name for name in extras if name.startswith("test_") and name != self.drop_launch_extra)
            self.log(phone, f"BENCHMARK launch_extras: count={len(names)}" +
                     (" | " + ",".join(f"{name}={extras[name]}" for name in names) if names else ""))
        for effect in self.effect_lines.get(phone, ()):
            self.log(phone, effect)
        if user and phone not in self.effect_lines:
            self.user_launch_lines(phone, extras)
        self.log(phone, f"BENCHMARK call available: node={self.prefix(phone)}")
        if phone in self.rotating and phone not in self.no_rotid_own:
            self.log(phone, f"BENCHMARK rotid_own: role=call | epoch={self.epoch} | rdi={self.rdi(phone)}")
        for other in self.pids:
            if other != phone:
                self.log(phone, f"BENCHMARK call discovered: node={self.advertised(other)} | transport=CLASSIC_RFCOMM")
                self.log(other, f"BENCHMARK call discovered: node={self.advertised(phone)} | transport=CLASSIC_RFCOMM")

    def control(self, phone, action):
        caller, callee = self.case["caller"], self.case["callee"]
        peer = callee if phone == caller else caller
        if action == "shutdown":
            self.pids.pop(phone, None)
            return
        self.log(phone, f"BENCHMARK control {action} applied")
        if action.startswith("call:"):
            self.log(caller, "BENCHMARK call phase OUTGOING_INVITE")
            if self.first_attempt_fails:
                # The callee's side of a stalled first attempt: it fails before any ring, then the retry rings.
                self.log(callee, "BENCHMARK incoming_handshake: result=failed | reason=EOFException")
                self.log(callee, "Private call ended: Incoming call failed: EOFException")
                self.log(callee, "BENCHMARK call ended_ui: message=Incoming call failed: EOFException")
                self.log(callee, f"BENCHMARK call available: node={self.prefix(callee)}")
            self.stage_start.clear()
            self.stage(caller, "dial", "caller")
            self.stage(callee, "link_up", "callee", link="Classic RFCOMM")
            self.stage(callee, "ringing", "callee")
            self.log(callee, "BENCHMARK call phase INCOMING_RINGING")
            self.log(caller, "BENCHMARK call phase OUTGOING_RINGING")
            ringing = self.now + self.ring_s
            self.after(self.ring_s, lambda: self.stage(caller, "ringing", "caller", at_s=ringing))
        elif action == "accept":
            self.stage(callee, "accept", "callee", by="local")
            self.stage(caller, "accept", "caller", by="peer")
            for endpoint in (callee, caller):
                if endpoint in self.no_picture:
                    continue
                dark = endpoint in self.black_first
                shown = self.now + self.picture_s.get(endpoint, 1.5)
                self.after(self.picture_s.get(endpoint, 1.5), lambda endpoint=endpoint, dark=dark, shown=shown: self.stage(
                    endpoint, "first_frame_shown", "caller" if endpoint == caller else "callee", at_s=shown,
                    black="true" if dark else "false", meanLuma="0.4" if dark else "61.2"))
            for endpoint in (callee, caller):
                self.app_profile_lines(endpoint)
                other = caller if endpoint == callee else callee
                who = "an unverified stranger" if self.pseudonymous(endpoint) else self.prefix(other)
                self.log(endpoint, f"Encrypted private-call link to {who} over Classic RFCOMM (MPS 4096)")
                if self.pseudonymous(endpoint) and endpoint not in self.reveal_missing:
                    self.log(endpoint, f"The other phone's identity is confirmed: {self.prefix(other)}")
                self.log(endpoint, "BENCHMARK call phase NEGOTIATING_MEDIA")
                self.log(endpoint, f"BENCHMARK call identity: callId={CALL_ID}")
                self.log(endpoint, "BENCHMARK call phase ACTIVE")
                self.log(endpoint, "Private call startup: media-to-first-surface 400.0 ms")
            self.active = True
        elif action in ("end", "decline", "cancel"):
            self.active = False
            self.ended = True
            for endpoint, message in ((phone, "Call ended"), (peer, "Peer ended the call")):
                self.log(endpoint, "BENCHMARK call phase ENDED")
                self.log(endpoint, f"Private call ended: {message}")
                self.log(endpoint, f"BENCHMARK call available: node={self.prefix(endpoint)}")

    # --- adb ------------------------------------------------------------------------------------
    def run(self, command, **_):
        serial, args = command[2], [str(item) for item in command[3:]]
        self.commands.append((serial, args))
        code, out = 0, ""
        if args[:1] == ["get-state"]:
            out = "device"
            if self.active:
                self.now += self.slow_health_s
        elif args[:2] == ["shell", "pidof"]:
            pid = self.pids.get(serial) if args[2] == device_runner.PACKAGE else None
            code, out = (0, str(pid)) if pid else (1, "")
        elif args[:1] == ["shell"] and args[1].startswith("date "):
            out = self.stamp()
        elif args[:2] == ["exec-out", "run-as"]:
            code, out, err = self.run_as(serial, " ".join(args[1:]), raw=True)
            # Raw bytes, as the real exec-out carries them; the text is what the fake file holds.
            return SimpleNamespace(returncode=code, stdout=out.encode("utf-8"), stderr=err.encode("utf-8"))
        elif args[:1] == ["shell"] and len(args) > 1 and args[1].startswith("run-as "):
            code, out, err = self.run_as(serial, args[1])
            return SimpleNamespace(returncode=code, stdout=out, stderr=err)
        elif args[:3] == ["shell", "pm", "path"]:
            out = "package:/data/app/base.apk"
        elif args[:2] == ["shell", "sha256sum"]:
            out = f"{APK}  {args[2]}"
        elif args[:3] == ["shell", "input", "keyevent"] and "KEYCODE_VOLUME_DOWN" in args and serial in self.refuse_input:
            return SimpleNamespace(returncode=1, stdout="", stderr="Injecting to another application requires INJECT_EVENTS")
        elif args[:3] == ["shell", "input", "keyevent"]:
            for key in args[3:]:
                if key == "KEYCODE_HOME" and serial not in self.stuck_foreground:
                    self.at_home.add(serial)
                elif key == "KEYCODE_SLEEP" and serial not in self.stuck_awake:
                    self.asleep.add(serial)
                elif key == "KEYCODE_WAKEUP":
                    self.asleep.discard(serial)
        elif args[:3] == ["shell", "svc", "wifi"]:
            if serial not in self.wifi_ignored:
                self.wifi[serial] = "0" if args[3] == "disable" else "1"
                if args[3] == "disable" and self.on_wifi_off:
                    self.on_wifi_off(serial)
        elif args[:5] == ["shell", "settings", "get", "global", "wifi_on"]:
            out = self.wifi.get(serial, "1")
        elif args[:3] == ["shell", "dumpsys", "power"]:
            out = f"mWakefulness={'Asleep' if serial in self.asleep else 'Awake'}"
        elif args[:3] == ["shell", "dumpsys", "window"]:
            front = serial not in self.at_home and serial not in self.asleep
            out = "mCurrentFocus=Window{4f2a u0 " + (f"{device_runner.PACKAGE}/.MainActivity}}" if front else "com.android.launcher/.Launcher}")
        elif args[:3] == ["shell", "am", "broadcast"]:
            extras = {args[index + 1]: args[index + 2] for index in range(len(args)) if args[index].startswith("--e")}
            self.broadcasts = getattr(self, "broadcasts", []) + [(serial, args[args.index("-a") + 1], extras)]
            if serial in self.pids and serial not in self.broadcast_ignored and "test_control" in extras:
                self.log(serial, f"BENCHMARK control_broadcast: control={extras['test_control']} | handled=true | via=broadcast")
                self.control(serial, extras["test_control"])  # no activity start: the app keeps its window focus
            out = "Broadcast completed: result=0"
        elif args[:3] == ["shell", "am", "force-stop"]:
            self.pids.pop(serial, None)
        elif args[:3] == ["shell", "am", "start"]:
            extras = {args[index + 1]: args[index + 2] for index in range(len(args)) if args[index].startswith("--e")}
            if "test_control" in extras:
                self.at_home.discard(serial)  # the intent brings the app to the front
                self.asleep.discard(serial)
                if serial in self.pids:
                    self.control(serial, extras["test_control"])
            else:
                self.next_pid += 1
                self.pids[serial] = self.next_pid
                # The runner attaches its log capture after the launch returns; the app logs from then on.
                self.after(0.05, lambda: self.launched(serial, extras))
        elif args[:2] == ["logcat", "-g"]:
            out = "main: ring buffer is 8 MiB (7 MiB consumed), max entry is 5120 B, max payload is 4068 B"
        elif args[:2] == ["logcat", "-d"]:
            path = self.app_logs.get(serial)
            if "liblog" in args:
                out = self.report_text(serial, self.pids.get(serial))
            elif path is not None:
                out = path.read_text(encoding="utf-8")
        elif args[:1] == ["logcat"] and "-b" in args and args[args.index("-b") + 1] in ("crash", "system"):
            # The crash and system buffers the runner dumps at the end of a case; tests script what they hold.
            out = self.system_buffers.get((serial, args[args.index("-b") + 1]), "")
            if serial in self.unreachable_buffers:
                code, out = 1, ""
        return SimpleNamespace(returncode=code, stdout=out, stderr="")

    def sent(self, phone, *words):
        """The adb commands sent to one phone that contain every given word, in order sent."""
        return [args for serial, args in self.commands if serial == phone and all(word in args for word in words)]


@contextlib.contextmanager
def fake_call(devices, case, folder):
    """(phones, runner) for one scripted case: adb, the log readers, the clock and the beep are fakes."""
    phones = FakePhones(devices, case)

    class Capture(FakeCapture):  # a class, because the runner recognises its readers with isinstance
        def __init__(self, *args, **kwargs):
            super().__init__(phones, *args, **kwargs)

    with patch.object(device_runner.subprocess, "run", phones.run), \
            patch.object(device_runner, "LogCapture", Capture), patch.object(runner, "LogCapture", Capture), \
            patch.object(device_runner.time, "monotonic", phones.monotonic), \
            patch.object(device_runner.time, "sleep", phones.sleep), \
            patch.object(device_runner.Runner, "beep", staticmethod(lambda: None)):
        instance = runner.CallRunner("adb", devices, case, Path(folder) / "run")
        yield phones, instance
