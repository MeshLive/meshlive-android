"""Crashes and ANRs of the app, read from the phone's crash and system log buffers.

The per-PID app capture cannot show them: a Java crash is logged by the app process (and a dead PID ends its
stream), an ANR is logged by ActivityManager in the system process, and a native crash by debuggerd. This module
builds the two bounded dumps taken at the end of a case and parses their lines. A planned `am force-stop` logs
none of these, so it is never read as a crash.
"""
import hashlib
import re
from datetime import datetime

PACKAGE = "com.example.bluetoothtest"
# (buffer name, extra logcat arguments). `-T <start>` is added per phone: only the case window is dumped.
DUMPS = (("crash", ()), ("system", ("ActivityManager:E", "*:S")))
THREADTIME = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]*):\s?(.*)$")


def dump_args(buffer, extra, start):
    return ("logcat", "-b", buffer, "-d", "-T", start, "-v", "threadtime", *extra)


def _stamp(value):
    return datetime.strptime("2000-" + value, "%Y-%m-%d %H:%M:%S.%f").timestamp()


def parse_crash_events(text, package=PACKAGE, start=None, end=None):
    """[{kind: ANR | JAVA_CRASH | NATIVE_CRASH, time, message}] naming `package` (or one of its processes) inside
    [start, end] (logcat times, either optional). Lines that name other apps are ignored."""
    lines = []
    for raw in text.splitlines():
        match = THREADTIME.match(raw)
        if match:
            lines.append({"time": match[1], "tag": match[5].strip(), "message": match[6], "raw": raw})
    name = rf"{re.escape(package)}(?::\w+)?"
    events = []
    for index, row in enumerate(lines):
        message = row["message"]
        if re.search(rf"\bANR in {name}(?![\w.])", message):
            events.append({"kind": "ANR", "time": row["time"], "message": message[:300]})
        elif "FATAL EXCEPTION" in message:
            follow = [item["message"] for item in lines[index + 1:index + 6]]
            if any(re.search(rf"\bProcess: {name}(?![\w.])", item) for item in follow):
                events.append({"kind": "JAVA_CRASH", "time": row["time"], "message": message[:300]})
        elif re.search(rf">>> {name} <<<", message):
            events.append({"kind": "NATIVE_CRASH", "time": row["time"], "message": message[:300]})
    lower = _stamp(start) if start else None
    upper = _stamp(end) if end else None
    events = [item for item in events if (lower is None or _stamp(item["time"]) >= lower) and
              (upper is None or _stamp(item["time"]) <= upper)]
    # One native crash prints the package on several lines: the same kind within 5 s is one event.
    distinct = []
    for item in events:
        if not any(other["kind"] == item["kind"] and abs(_stamp(other["time"]) - _stamp(item["time"])) <= 5
                   for other in distinct):
            distinct.append(item)
    return distinct


def sha256(data):
    return hashlib.sha256(data).hexdigest()
