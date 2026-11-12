"""Drive the Android side of a private call whose other end is an iPhone (or a person).

  callee --serial S --seconds N [--voice]
      Launch the app as a private-call callee (the call screen, available for calls), wait for an
      incoming ring, accept it, hold N seconds from the call becoming ACTIVE, hang up, summarise.
  caller --serial S --peer PREFIX_OR_HANDLE --seconds N [--voice]
      Same, but Android places the call to the peer (12-hex node prefix or its SSSSS-SSSSS handle),
      retrying the invite until the peer has been discovered.

The launch extras are the private-call runner's (tools/run_private_call_benchmark.py through
run_device_benchmark.Runner.launch with test_auto_broadcast swapped for test_private_call_browser)
without any raw-fixture, replay, reference-audio or impairment input: the call uses the real camera and
microphone. Controls are the app's DEBUG `test_control` extras (MainActivity.onNewIntent): accept,
call:PREFIX, voice_call:PREFIX, end, cancel. Needs a DEBUG build installed; it never installs anything.

Everything is written to a fresh folder runs/<timestamp>/ under the interop scratch root: the live logcat
(<serial>-<pid>.log, the app's PID only), an optional device-buffer dump, events and summary.json.
Exit code 0 = the call reached ACTIVE and carried audio both ways (and video both ways unless --voice);
1 = the call ran but failed that check; 2 = it never got going (no ring, no answer, crash, adb error).
"""
import os
import argparse
import datetime as dt
import json
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_tools import default_adb  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))
from device_log_capture import LogCapture, merge_dump  # noqa: E402

PACKAGE = os.environ.get("MESHLIVE_APP_ID", "io.github.meshlive.app")
COMPONENT = f"{PACKAGE}/com.example.bluetoothtest.MainActivity"
APP_LOG_FILTERS = ("BluetoothVideo:I", "GattVideoSetup:I", "AndroidRuntime:E", "*:S")
DEFAULT_ADB = default_adb()
DEFAULT_OUT_ROOT = Path(__file__).resolve().parents[1] / "benchmarks" / "runs" / "interop"
CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

RECORD = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(\S+?)\s*:\s(.*)$")
PHONE_TIME = re.compile(r"\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}")
HEX12 = re.compile(r"[0-9a-f]{12}")


class InteropError(RuntimeError):
    """The call could not be driven (exit code 2)."""


# ----------------------------------------------------------------------------------------------------
# Pure helpers (unit tested)

def parse_records(text):
    """Logcat threadtime lines -> [{time, pid, tid, level, tag, message}]; other lines are skipped."""
    rows = []
    for line in text.splitlines():
        match = RECORD.match(line)
        if match:
            rows.append({"time": match[1], "pid": int(match[2]), "tid": int(match[3]), "level": match[4],
                         "tag": match[5], "message": match[6]})
    return rows


def parse_fields(text):
    """'callId=x | final=true | queue=0/183' -> {'callId': 'x', 'final': 'true', 'queue': '0/183'}."""
    fields = {}
    for part in text.split(" | "):
        key, sep, value = part.partition("=")
        if sep and key.strip() and " " not in key.strip():
            fields[key.strip()] = value.strip()
    return fields


def as_number(value):
    """int/float when the text is numeric, else the text itself ('unavailable' stays 'unavailable')."""
    if isinstance(value, str):
        for kind in (int, float):
            try:
                return kind(value)
            except ValueError:
                pass
    return value


def handle_to_prefix(handle):
    """SSSSS-SSSSS Crockford handle -> 12-hex node prefix (PROTOCOL: prefix48 << 2 in 10 symbols)."""
    symbols = re.sub(r"[-\s]", "", handle).upper().replace("I", "1").replace("L", "1").replace("O", "0")
    if len(symbols) != 10 or any(symbol not in CROCKFORD for symbol in symbols):
        raise ValueError(f"not a 10-symbol handle: {handle!r}")
    value = 0
    for symbol in symbols:
        value = value << 5 | CROCKFORD.index(symbol)
    if value & 3:
        raise ValueError(f"handle tail bits are not zero: {handle!r}")
    return f"{value >> 2:012x}"


def peer_prefix(peer):
    """Accept a 12-hex prefix or a handle and return the lowercase 12-hex prefix."""
    text = peer.strip()
    if HEX12.fullmatch(text.lower()):
        return text.lower()
    return handle_to_prefix(text)


def launch_extras(args):
    """The private-call runner's launch extras for the app's call screen (real camera and microphone)."""
    extras = {"test_transport": args.transport, "test_codec": args.codec, "test_playback_delay": args.playback,
              "test_thermal_reduction": False, "test_private_call_browser": True, "test_role": "send",
              "test_width": args.width, "test_height": args.height, "test_fps": args.fps,
              "test_bitrate_kbps": args.bitrate_kbps, "test_rate_control": args.rate_control,
              "test_playback_gain": args.playback_gain}
    if args.voice:
        extras["test_voice_calls"] = True  # voice invites/offers need it on both endpoints
    for item in getattr(args, "extra", None) or []:
        # A DEBUG benchmark launch reads only its extras, so user-default features (rotating IDs, BMH3, pseudonyms,
        # "Find iPhones") must be passed explicitly: KEY=true|false|<int>|<text>.
        key, _, value = item.partition("=")
        if not key.startswith("test_") or not value:
            raise SystemExit(f"--extra needs test_KEY=VALUE, got {item!r}")
        lowered = value.lower()
        extras[key] = (lowered == "true") if lowered in ("true", "false") else int(value) if value.isdigit() else value
    return extras


def extras_to_args(extras):
    """Same typing rule as the runner: bool --ez, int --ei, float --ef, else --es."""
    out = []
    for key, value in extras.items():
        flag = "--ez" if isinstance(value, bool) else "--ei" if isinstance(value, int) else \
            "--ef" if isinstance(value, float) else "--es"
        out.extend([flag, key, str(value).lower() if isinstance(value, bool) else str(value)])
    return out


def launch_command(extras):
    return ["shell", "am", "start", "-W", "-n", COMPONENT, *extras_to_args(extras)]


def control_command(control):
    return ["shell", "am", "start", "-n", COMPONENT, "--es", "test_control", control]


def invite_control(prefix, voice):
    return ("voice_call:" if voice else "call:") + prefix


def control_ack(rows, control):
    """The last 'BENCHMARK control <control> applied|rejected' row -> (applied, row) or None."""
    pattern = re.compile(rf"^BENCHMARK control {re.escape(control)} (applied|rejected)$")
    for row in reversed(rows):
        match = pattern.match(row["message"])
        if match:
            return match[1] == "applied", row
    return None


def summarize(rows):
    """Call evidence from the app's own BENCHMARK lines (last call in the log)."""
    phases, controls, call_ids = [], [], []
    ended = {}
    final_tx, final_rx, last_tx, call_type, remote = {}, {}, {}, {}, {}
    link_peer = crashed = None
    offer = {}
    for row in rows:
        message = row["message"]
        if row["tag"] == "AndroidRuntime" and message.startswith("FATAL EXCEPTION"):
            crashed = row["time"]
        match = re.fullmatch(r"BENCHMARK call phase (\w+)", message)
        if match:
            phases.append({"phase": match[1], "time": row["time"]})
            continue
        match = re.fullmatch(r"BENCHMARK control (\S+) (applied|rejected)", message)
        if match:
            controls.append({"control": match[1], "applied": match[2] == "applied", "time": row["time"]})
            continue
        match = re.fullmatch(r"BENCHMARK call identity: callId=(\S+)", message)
        if match:
            call_ids.append(match[1])
            continue
        if message.startswith("BENCHMARK call_type: "):
            call_type = parse_fields(message[len("BENCHMARK call_type: "):])
        elif message.startswith("BENCHMARK call_type offer: "):
            offer = parse_fields(message[len("BENCHMARK call_type offer: "):])
        elif message.startswith("BENCHMARK call remote: "):
            remote = {key: as_number(value) for key, value in
                      parse_fields(message[len("BENCHMARK call remote: "):]).items()}
        elif message.startswith("BENCHMARK call tx: "):
            fields = {key: as_number(value) for key, value in
                      parse_fields(message[len("BENCHMARK call tx: "):]).items()}
            last_tx = fields
            if fields.get("final") == "true":
                final_tx = fields
        elif message.startswith("BENCHMARK call rx: "):
            fields = {key: as_number(value) for key, value in
                      parse_fields(message[len("BENCHMARK call rx: "):]).items()}
            if fields.get("final") == "true":
                final_rx = fields
        elif message.startswith("Private call ended: "):
            # DEBUG builds append " | callId=<uuid>" after the message.
            ended["reason"] = message[len("Private call ended: "):].split(" | callId=", 1)[0]
            ended["time"] = row["time"]
        elif message.startswith("BENCHMARK call ended_ui: message="):
            ended["reason_ui"] = message[len("BENCHMARK call ended_ui: message="):]
        else:
            match = re.match(r"Encrypted private-call link to ([0-9a-f]{12}) over (.+?)(?: \(|$)", message)
            if match:
                link_peer = {"prefix": match[1], "transport": match[2], "time": row["time"]}
    tx = final_tx or last_tx
    media = {
        "tx_final_seen": bool(final_tx), "rx_final_seen": bool(final_rx),
        "audio_tx_packets": tx.get("audioWrites"), "video_tx_packets": tx.get("videoWrites"),
        "audio_tx_inputs": tx.get("audioInputs"), "video_tx_inputs": tx.get("videoInputs"),
        "audio_rx_packets": final_rx.get("audioPackets"), "video_rx_packets": final_rx.get("videoPackets"),
    }
    return {"call_id": call_ids[-1] if call_ids else final_rx.get("callId") or tx.get("callId"),
            "call_type": call_type, "offer": offer, "link_peer": link_peer, "remote": remote,
            "phases": phases, "controls": controls, "ended": ended, "tx": final_tx or None,
            "tx_last_interim": None if final_tx else (last_tx or None), "rx": final_rx or None,
            "media": media, "crash_time": crashed}


def positive(value):
    return isinstance(value, (int, float)) and value > 0


def judge(summary, voice):
    """(ok, reasons): ACTIVE reached, audio both ways, and video both ways unless voice."""
    media, reasons = summary["media"], []
    if not any(item["phase"] == "ACTIVE" for item in summary["phases"]):
        reasons.append("call never reached ACTIVE")
    if summary.get("crash_time"):
        reasons.append("application crashed")
    if not positive(media["audio_tx_packets"]):
        reasons.append("no audio sent")
    if not positive(media["audio_rx_packets"]):
        reasons.append("no audio received")
    if not voice:
        if not positive(media["video_tx_packets"]):
            reasons.append("no video sent")
        if not positive(media["video_rx_packets"]):
            reasons.append("no video received")
    if not media["tx_final_seen"] or not media["rx_final_seen"]:
        reasons.append("final tx/rx report line missing")
    return not reasons, reasons


# ----------------------------------------------------------------------------------------------------
# Device driver

class Driver:
    def __init__(self, args, out):
        self.args, self.out = args, out
        self.events, self.pid, self.capture, self.log_path = [], None, None, None

    # -- adb
    def adb(self, *command, timeout=30, check=True):
        full = [self.args.adb, "-s", self.args.serial, *map(str, command)]
        result = subprocess.run(full, capture_output=True, text=True, timeout=timeout, encoding="utf-8",
                                errors="replace")
        if check and result.returncode:
            raise InteropError(f"adb {' '.join(map(str, command[:4]))} failed ({result.returncode}): "
                               f"{(result.stderr or result.stdout).strip()[:300]}")
        return result.stdout.strip()

    def phone_time(self):
        value = self.adb("shell", "date '+%m-%d %H:%M:%S.%3N'")
        if not PHONE_TIME.fullmatch(value):
            raise InteropError(f"device clock format unavailable: {value!r}")
        return value

    def event(self, action, **detail):
        self.events.append({"action": action, "host_time": dt.datetime.now().isoformat(timespec="milliseconds"),
                            "host_monotonic_ns": time.monotonic_ns(), **detail})
        print(f"[{self.events[-1]['host_time'][11:]}] {action} {detail if detail else ''}", flush=True)

    # -- log
    def rows(self):
        return parse_records(self.log_path.read_text(encoding="utf-8", errors="replace"))

    def dump_rows(self):
        """One bounded `logcat -d --pid` read of the device buffer (the live stream can lag behind)."""
        text = self.adb("logcat", "-d", f"--pid={self.pid}", "-v", "threadtime", *APP_LOG_FILTERS,
                        timeout=40, check=False)
        return text.splitlines()

    def wait_for(self, expression, timeout, after=0, abort=None):
        """Block until a record message after row index `after` matches; returns (index, row)."""
        deadline = time.monotonic() + timeout
        pattern = re.compile(expression)
        while True:
            rows = self.rows()
            for index in range(after, len(rows)):
                if pattern.search(rows[index]["message"]):
                    return index, rows[index]
            if any(row["tag"] == "AndroidRuntime" and row["message"].startswith("FATAL EXCEPTION") for row in rows):
                raise InteropError("the application crashed")
            if abort:
                reason = abort(rows)
                if reason:
                    raise InteropError(reason)
            if time.monotonic() >= deadline:
                break
            time.sleep(0.25)
        dump = parse_records("\n".join(self.dump_rows()))
        live_keys = {(row["time"], row["message"]) for row in rows}
        for row in dump:
            if (row["time"], row["message"]) not in live_keys and pattern.search(row["message"]):
                self.event("evidence_from_dump", pattern=expression, app_time=row["time"])
                return len(rows), row
        raise InteropError(f"no app evidence for {expression!r} within {timeout:.0f} s")

    def own_node_prefix(self):
        """This phone's public node prefix from 'BENCHMARK call available: node=' (None if not logged)."""
        try:
            _, row = self.wait_for(r"^BENCHMARK call available: node=", 20)
        except InteropError:
            self.event("call_available_line_missing")
            return None
        match = re.search(r"node=([0-9a-f]{12})", row["message"])
        return match[1] if match else None

    def peek(self, expression, after=0):
        pattern = re.compile(expression)
        rows = self.rows()
        return next(((i, rows[i]) for i in range(after, len(rows)) if pattern.search(rows[i]["message"])), None)

    # -- app
    def launch(self):
        self.adb("shell", "am", "force-stop", PACKAGE)
        try:
            self.adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
            self.adb("shell", "wm", "dismiss-keyguard")
        except InteropError as error:
            if "INJECT_EVENTS" not in str(error):
                raise
            policy = self.adb("shell", "dumpsys", "window", "policy")
            if not (re.search(r"^\s*showing=false\s*$", policy, re.M) and "screenState=SCREEN_STATE_ON" in policy):
                raise InteropError("unlock the phone: OEM policy blocks automatic wake") from error
            self.event("already_unlocked")
        started = self.phone_time()
        extras = launch_extras(self.args)
        self.adb(*launch_command(extras), timeout=60)
        pid = self.adb("shell", "pidof", PACKAGE)
        if not pid.split()[:1] or not pid.split()[0].isdigit():
            raise InteropError("the application did not start")
        self.pid = pid.split()[0]
        self.log_path = self.out / f"{self.args.serial}-{self.pid}.log"
        self.capture = LogCapture(self.args.adb, self.args.serial, self.pid, started, self.log_path, APP_LOG_FILTERS)
        self.event("launch", pid=int(self.pid), phone_time=started, extras=extras)

    def control(self, control, timeout=6, retry_until=None, retry_gap=2.0):
        """Send a test_control and wait for the app's 'applied|rejected' answer. With retry_until (a
        monotonic deadline) a rejection is retried (a caller's peer may not be discovered yet)."""
        while True:
            after = len(self.rows())
            when = self.phone_time()
            self.adb(*control_command(control))
            index, row = self.wait_for(rf"^BENCHMARK control {re.escape(control)} (?:applied|rejected)$", timeout, after)
            applied = row["message"].endswith("applied")
            self.event("control", control=control, applied=applied, app_time=row["time"], sent_at=when)
            if applied or retry_until is None or time.monotonic() >= retry_until:
                return applied
            time.sleep(retry_gap)

    def finish(self):
        """Stop the app and the log capture; return the merged log rows."""
        if self.pid is None:
            return []
        try:
            dump = self.dump_rows()
        except Exception:  # the dump only fills gaps
            dump = []
        try:
            self.adb("shell", "am", "force-stop", PACKAGE, check=False)
            self.event("force_stop")
        finally:
            if self.capture:
                self.capture.stop()
        live = self.log_path.read_text(encoding="utf-8", errors="replace").splitlines()
        merged, added = merge_dump(live, dump, self.pid)
        if added:
            (self.out / f"{self.args.serial}-{self.pid}-merged.log").write_text("\n".join(merged) + "\n",
                                                                                encoding="utf-8")
            self.event("log_lines_recovered", lines=len(added))
        return parse_records("\n".join(merged))


def hold_and_hang_up(driver, args, summary_after):
    """From ACTIVE: hold `seconds` (leaving early if the peer ends the call), then hang up with `end`."""
    index, active = driver.wait_for(r"^BENCHMARK call phase ACTIVE$", args.active_timeout, summary_after)
    driver.event("active", app_time=active["time"])
    started = time.monotonic()
    outcome = "held"
    while time.monotonic() - started < args.seconds:
        if driver.peek(r"^BENCHMARK call phase ENDED$", index):
            outcome = "peer_ended"
            driver.event("peer_ended_call", held_s=round(time.monotonic() - started, 1))
            break
        time.sleep(0.5)
    held = time.monotonic() - started
    if outcome == "held":
        driver.control("end")
        try:
            driver.wait_for(r"^BENCHMARK call phase ENDED$", 10, index)
        except InteropError:
            driver.event("end_not_confirmed")
            outcome = "end_not_confirmed"
    time.sleep(1.5)  # the final tx/rx reports follow the ENDED phase
    return outcome, held


def run(args):
    out = args.out_root / dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    out.mkdir(parents=True, exist_ok=False)
    driver = Driver(args, out)
    result = {"tool": "ios_interop_call", "role": args.role, "serial": args.serial, "voice": args.voice,
              "requested_seconds": args.seconds, "output": str(out), "launch_extras": launch_extras(args)}
    code = 2
    try:
        driver.launch()
        result["pid"] = int(driver.pid)
        if args.role == "callee":
            result["own_node_prefix"] = driver.own_node_prefix()  # call screen up, advertising
            driver.event("waiting_for_ring", own_node_prefix=result["own_node_prefix"], timeout_s=args.ring_timeout)
            ring_index, ring = driver.wait_for(r"^BENCHMARK call phase INCOMING_RINGING$", args.ring_timeout)
            driver.event("ringing", app_time=ring["time"])
            deadline = time.monotonic() + 15
            if not driver.control("accept", retry_until=deadline, retry_gap=1.0):
                raise InteropError("the application rejected accept")
            start_index = ring_index
        else:
            prefix = peer_prefix(args.peer)
            result["peer_prefix"] = prefix
            result["own_node_prefix"] = driver.own_node_prefix()
            start_index = len(driver.rows())
            deadline = time.monotonic() + args.discover_timeout
            driver.event("inviting", control=invite_control(prefix, args.voice), timeout_s=args.discover_timeout)
            if not driver.control(invite_control(prefix, args.voice), timeout=10, retry_until=deadline):
                raise InteropError("the peer was not discovered/accepted as callable before the deadline")
            try:
                hold_after = start_index
                driver.wait_for(r"^BENCHMARK call phase (?:OUTGOING_INVITE|OUTGOING_RINGING)$", 20, hold_after)
                driver.wait_for(r"^BENCHMARK call phase ACTIVE$", args.answer_timeout, hold_after)
            except InteropError:
                driver.control("cancel", timeout=5)
                raise
        outcome, held = hold_and_hang_up(driver, args, start_index)
        result.update(outcome=outcome, held_s=round(held, 1))
        code = None
    except (InteropError, subprocess.TimeoutExpired) as error:
        result.update(outcome="failed", error=str(error))
        driver.event("error", error=str(error))
    except KeyboardInterrupt:
        result.update(outcome="interrupted")
        driver.event("interrupted")
        code = 2
    finally:
        try:
            rows = driver.finish()
        except Exception as error:  # cleanup must still write the summary
            rows = []
            result["cleanup_error"] = str(error)
    summary = summarize(rows)
    ok, reasons = judge(summary, args.voice)
    result.update(call=summary, ok=ok and code is None, reasons=reasons if not ok else [], events=driver.events)
    if code is None:
        code = 0 if ok else 1
    (out / "summary.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    media = summary["media"]
    print(f"summary: {out / 'summary.json'}\n  outcome={result.get('outcome')} ok={result['ok']} "
          f"end={summary['ended'].get('reason')!r}\n  audio tx/rx={media['audio_tx_packets']}/{media['audio_rx_packets']} "
          f"video tx/rx={media['video_tx_packets']}/{media['video_rx_packets']}", flush=True)
    if result.get("error"):
        print(f"  error: {result['error']}", flush=True)
    return code


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="role", required=True)
    for name in ("callee", "caller"):
        item = sub.add_parser(name)
        item.add_argument("--serial", required=True)
        item.add_argument("--seconds", type=float, required=True, help="hold time after the call is ACTIVE")
        item.add_argument("--voice", action="store_true", help="voice call (test_voice_calls; caller sends voice_call:)")
        item.add_argument("--adb", default=DEFAULT_ADB)
        item.add_argument("--out-root", type=Path, default=DEFAULT_OUT_ROOT)
        item.add_argument("--transport", default="gatt", choices=("gatt", "classic", "ble"))
        item.add_argument("--codec", default="vp9")
        item.add_argument("--playback", default="balanced")
        item.add_argument("--width", type=int, default=640)
        item.add_argument("--height", type=int, default=360)
        item.add_argument("--fps", type=int, default=15)
        item.add_argument("--bitrate-kbps", type=int, default=300)
        item.add_argument("--rate-control", default="automatic")
        item.add_argument("--playback-gain", type=float, default=0.05, help="runner default 0.05; 1.0 = full volume")
        item.add_argument("--active-timeout", type=float, default=45, help="accept/answer to ACTIVE")
        item.add_argument("--extra", action="append", metavar="test_KEY=VALUE",
                          help="additional launch extra, repeatable (e.g. test_rotating_ids=true)")
    callee, caller = sub.choices["callee"], sub.choices["caller"]
    callee.add_argument("--ring-timeout", type=float, default=180, help="wait for the incoming ring")
    caller.add_argument("--peer", required=True, help="12-hex node prefix or SSSSS-SSSSS handle")
    caller.add_argument("--discover-timeout", type=float, default=60, help="retry the invite until the peer is found")
    caller.add_argument("--answer-timeout", type=float, default=90, help="invite to ACTIVE")
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    if args.seconds <= 0:
        raise SystemExit("--seconds must be positive")
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
